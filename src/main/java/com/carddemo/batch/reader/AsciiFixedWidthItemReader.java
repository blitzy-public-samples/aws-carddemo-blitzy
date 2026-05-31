package com.carddemo.batch.reader;

import com.carddemo.util.FixedWidthRecordParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.NonTransientResourceException;
import org.springframework.batch.item.ParseException;
import org.springframework.batch.item.UnexpectedInputException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Spring Batch {@link ItemReader} + {@link ItemStream} implementation that streams
 * fixed-width ASCII records line-by-line from a {@link Resource} and maps each line
 * to a typed record {@code T} via a caller-supplied {@link RecordMapper}.
 *
 * <p>This reader is the modern replacement for the legacy IDCAMS
 * {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)} command (see {@code app/ctl/REPROCT.ctl}),
 * used to load the nine fixture files under {@code app/data/ASCII/*.txt} that preserve the
 * original VSAM record layouts in ASCII form. It is the foundation for the Spring Batch
 * seeding jobs in the parent {@code com.carddemo.batch} package
 * ({@code DataInitializationJobConfig} and {@code DailyTransactionReadJobConfig}).
 *
 * <p>The fixtures and their COBOL record-defining copybooks:
 * <table border="1">
 *   <caption>Fixed-width fixture inventory</caption>
 *   <tr><th>Fixture</th><th>Record width</th><th>Copybook</th></tr>
 *   <tr><td>{@code acctdata.txt}</td><td>300</td><td>{@code CVACT01Y.cpy} (5 zoned-decimal money fields)</td></tr>
 *   <tr><td>{@code carddata.txt}</td><td>150</td><td>{@code CVACT02Y.cpy}</td></tr>
 *   <tr><td>{@code cardxref.txt}</td><td>50</td><td>{@code CVACT03Y.cpy}</td></tr>
 *   <tr><td>{@code custdata.txt}</td><td>500</td><td>{@code CVCUS01Y.cpy}</td></tr>
 *   <tr><td>{@code dailytran.txt}</td><td>350</td><td>{@code CVTRA06Y.cpy} (1 zoned-decimal amount)</td></tr>
 *   <tr><td>{@code discgrp.txt}</td><td>50</td><td>{@code CVTRA02Y.cpy} (1 zoned-decimal rate)</td></tr>
 *   <tr><td>{@code tcatbal.txt}</td><td>50</td><td>{@code CVTRA01Y.cpy} (1 zoned-decimal balance)</td></tr>
 *   <tr><td>{@code trancatg.txt}</td><td>60</td><td>{@code CVTRA04Y.cpy}</td></tr>
 *   <tr><td>{@code trantype.txt}</td><td>60</td><td>{@code CVTRA03Y.cpy}</td></tr>
 * </table>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Memory-efficient streaming &mdash; reads exactly one line at a time via a
 *       {@link BufferedReader}; the entire file is never held in memory.</li>
 *   <li>Checkpoint/restart support via {@link ExecutionContext} &mdash; the number of lines
 *       consumed is persisted under the key {@code name + ".linesRead"} on every
 *       {@link #update(ExecutionContext)} and replayed by {@link #open(ExecutionContext)}
 *       so a failed job resumes from the last committed chunk.</li>
 *   <li>COBOL zoned-decimal sign overpunch decoding for {@code PIC S9(n)V99} money fields
 *       via {@link #decodeZonedDecimalSign(String)} / {@link #parseZonedDecimal(String, int, int, int)}
 *       ({@code '{'=+0, 'A'-'I'=+1..+9, '}'=-0, 'J'-'R'=-1..-9}).</li>
 *   <li>Defensive blank-line skipping (trailing blank lines from copy/paste artifacts are
 *       ignored while still advancing the restart counter).</li>
 *   <li>UTF-8 charset decoding.</li>
 * </ul>
 *
 * <h2>Relationship to {@link FixedWidthRecordParser}</h2>
 * <p>Plain (unsigned) numeric, alphanumeric, and date fields are extracted by the stateless
 * {@link FixedWidthRecordParser} static methods
 * ({@code parseString}/{@code parseLong}/{@code parseInteger}/{@code parseBigDecimal}/{@code parseDate}).
 * That parser intentionally operates only on already-decoded plain numeric content; decoding the
 * COBOL zoned-decimal sign overpunch is the responsibility of this reader, whose
 * {@link #parseZonedDecimal(String, int, int, int)} helper complements
 * {@code FixedWidthRecordParser.parseBigDecimal} for signed money fields.
 *
 * <h2>Preservation rules</h2>
 * <ul>
 *   <li><b>PR-13</b> (record-length fidelity) &mdash; callers supply the COBOL {@code PIC}-clause
 *       offsets/lengths; this reader makes no assumption about field positions.</li>
 *   <li><b>PR-16</b> (exact decimal) &mdash; money fields are parsed to {@link BigDecimal} with
 *       scale 2 and {@link RoundingMode#HALF_UP}; {@code float}/{@code double} are never used.</li>
 *   <li><b>PR-25</b> (single monolith) &mdash; pure Java, no external services.</li>
 *   <li><b>PR-28</b> (Jakarta EE baseline) &mdash; no {@code javax.*} imports.</li>
 *   <li><b>PR-29</b> (constructor injection) &mdash; the {@link Resource} and {@link RecordMapper}
 *       are mandatory constructor arguments; no field injection.</li>
 * </ul>
 *
 * <h2>Lifecycle and threading</h2>
 * <p>An instance is bound to a single {@link Resource} and is therefore <em>not</em> a Spring
 * {@code @Component}; callers (e.g., {@code DataInitializationJobConfig}) instantiate one per
 * fixture file, typically inside a {@code @StepScope} {@code @Bean} factory method. The instance
 * is stateful (it holds an open {@link BufferedReader} and a line counter between
 * {@link #open(ExecutionContext)} and {@link #close()}) and is therefore <em>not</em>
 * thread-safe; Spring Batch drives a single reader on a single thread per step partition, which
 * matches this contract. The {@code static} helper methods, by contrast, are stateless and safe
 * for concurrent use.
 *
 * <h2>Example usage</h2>
 * <pre>{@code
 * Resource r = AsciiFixedWidthItemReader.resolveFixtureResource("acctdata.txt");
 * AsciiFixedWidthItemReader<Account> reader = new AsciiFixedWidthItemReader<>(r, line -> {
 *     Account a = new Account();
 *     a.setAcctId(FixedWidthRecordParser.parseLong(line, 0, 11));
 *     a.setAcctActiveStatus(FixedWidthRecordParser.parseString(line, 11, 1));
 *     a.setAcctCurrBal(AsciiFixedWidthItemReader.parseZonedDecimal(line, 12, 12, 2));
 *     return a;
 * });
 * reader.setName("accountFixtureReader");
 * }</pre>
 *
 * @param <T> the type of record produced by the supplied {@link RecordMapper}
 * @see FixedWidthRecordParser
 * @see ItemReader
 * @see ItemStream
 */
public class AsciiFixedWidthItemReader<T> implements ItemReader<T>, ItemStream {

    private static final Logger log = LoggerFactory.getLogger(AsciiFixedWidthItemReader.class);

    /** Default {@link ExecutionContext} key prefix, overridable via {@link #setName(String)}. */
    private static final String DEFAULT_NAME = "AsciiFixedWidthItemReader";

    /** Suffix appended to {@link #name} to form the restart line-count key. */
    private static final String LINES_READ_SUFFIX = ".linesRead";

    /** Classpath prefix tried first by {@link #resolveFixtureResource(String)}. */
    private static final String CLASSPATH_FIXTURE_PREFIX = "fixtures/";

    /** Filesystem fallback prefix used by {@link #resolveFixtureResource(String)}. */
    private static final String FILESYSTEM_FIXTURE_PREFIX = "app/data/ASCII/";

    /** The fixture file to read; required and non-null (constructor-validated). */
    private final Resource resource;

    /** Maps each non-blank line to a typed record; required and non-null (constructor-validated). */
    private final RecordMapper<T> recordMapper;

    /** {@link ExecutionContext} key prefix; defaults to {@link #DEFAULT_NAME}. */
    private String name = DEFAULT_NAME;

    /** The active reader; {@code null} until {@link #open(ExecutionContext)} and after {@link #close()}. */
    private BufferedReader reader;

    /** Count of lines consumed so far (including skipped blank lines); the restart cursor. */
    private long linesRead = 0L;

    /** Guards {@link #read()}/{@link #update(ExecutionContext)} against use before open / after close. */
    private boolean opened = false;

    /**
     * Creates a reader bound to the given {@link Resource} and {@link RecordMapper}.
     *
     * <p>No I/O is performed here; the underlying stream is opened lazily by
     * {@link #open(ExecutionContext)} so the bean can be constructed (e.g., as a
     * {@code @StepScope} {@code @Bean}) without touching the filesystem.
     *
     * @param resource     the fixed-width fixture file to stream (must not be {@code null})
     * @param recordMapper the line-to-record mapper (must not be {@code null})
     * @throws NullPointerException if either argument is {@code null}
     */
    public AsciiFixedWidthItemReader(Resource resource, RecordMapper<T> recordMapper) {
        this.resource = Objects.requireNonNull(resource, "resource must not be null");
        this.recordMapper = Objects.requireNonNull(recordMapper, "recordMapper must not be null");
    }

    /**
     * Sets the {@link ExecutionContext} key prefix used to persist the restart cursor.
     *
     * <p>When a single Step wires several readers of this type (for example,
     * {@code DataInitializationJobConfig} chains one per fixture file), each must use a distinct
     * name so their restart counters do not collide in the shared {@link ExecutionContext}.
     *
     * @param name the non-null, non-blank key prefix
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public void setName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
    }

    /**
     * Returns the current {@link ExecutionContext} key prefix.
     *
     * @return the key prefix (never {@code null})
     */
    public String getName() {
        return name;
    }

    /**
     * Opens the underlying stream and restores the restart cursor from the
     * {@link ExecutionContext} if present.
     *
     * <p>If the context already contains {@code name + ".linesRead"} (i.e., this is a restart),
     * that many lines are skipped so reading resumes at the first un-consumed line. Otherwise the
     * cursor starts at zero. The stream is decoded as UTF-8.
     *
     * @param executionContext the Spring Batch execution context (never {@code null} in practice)
     * @throws ItemStreamException if the reader is already open, the resource does not exist, or
     *                             an {@link IOException} occurs while opening the stream
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (opened) {
            throw new ItemStreamException("Reader is already open");
        }
        if (!resource.exists()) {
            throw new ItemStreamException("Resource not found: " + resource.getDescription());
        }
        try {
            reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
            String stateKey = name + LINES_READ_SUFFIX;
            if (executionContext != null && executionContext.containsKey(stateKey)) {
                long restoreCount = executionContext.getLong(stateKey);
                for (long i = 0; i < restoreCount; i++) {
                    String skipped = reader.readLine();
                    if (skipped == null) {
                        log.warn("ExecutionContext indicated {} lines read but EOF reached at line {}; "
                                + "treating as completed", restoreCount, i);
                        break;
                    }
                }
                this.linesRead = restoreCount;
                log.info("Restarting AsciiFixedWidthItemReader at line {} for resource {}",
                        restoreCount, resource.getDescription());
            } else {
                this.linesRead = 0L;
                log.info("Starting AsciiFixedWidthItemReader from beginning for resource {}",
                        resource.getDescription());
            }
            opened = true;
        } catch (IOException e) {
            throw new ItemStreamException("Failed to open resource: " + resource.getDescription(), e);
        }
    }

    /**
     * Reads, maps, and returns the next record, or {@code null} at end of file.
     *
     * <p>Blank (empty or whitespace-only) lines are skipped defensively; each skipped line still
     * advances the restart counter so a subsequent restart resumes past them. Returning
     * {@code null} signals end-of-input to Spring Batch &mdash; this method never throws at EOF.
     *
     * @return the next mapped record, or {@code null} when the stream is exhausted
     * @throws IllegalStateException       if invoked before {@link #open(ExecutionContext)}
     * @throws NonTransientResourceException if an {@link IOException} occurs while reading
     * @throws ParseException              if the {@link RecordMapper} fails to map a line
     * @throws Exception                   per the {@link ItemReader#read()} contract
     */
    @Override
    public T read() throws Exception, UnexpectedInputException, ParseException, NonTransientResourceException {
        if (!opened) {
            throw new IllegalStateException("Reader not opened — call open(ExecutionContext) before read()");
        }
        String line;
        try {
            // Skip blank lines defensively; count every consumed line (including blanks).
            do {
                line = reader.readLine();
                if (line == null) {
                    return null;
                }
                linesRead++;
            } while (line.isBlank());
        } catch (IOException e) {
            throw new NonTransientResourceException("IO error reading line " + linesRead
                    + " of " + resource.getDescription(), e);
        }
        try {
            return recordMapper.mapLine(line);
        } catch (Exception e) {
            throw new ParseException("Failed to parse line " + linesRead
                    + " of " + resource.getDescription() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Persists the current restart cursor ({@link #linesRead}) into the {@link ExecutionContext}
     * under {@code name + ".linesRead"}. No-op if the reader is not open.
     *
     * @param executionContext the Spring Batch execution context to update
     * @throws ItemStreamException never thrown directly; declared to satisfy the {@link ItemStream} contract
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        if (!opened || executionContext == null) {
            return;
        }
        executionContext.putLong(name + LINES_READ_SUFFIX, linesRead);
    }

    /**
     * Closes the underlying stream and resets the reader to its pre-open state.
     *
     * <p>An {@link IOException} raised while closing is logged at WARN level and swallowed, since a
     * failure to release the handle must not mask the (likely successful) processing result. After
     * this call the instance may be re-opened via {@link #open(ExecutionContext)}.
     *
     * @throws ItemStreamException never thrown directly; declared to satisfy the {@link ItemStream} contract
     */
    @Override
    public void close() throws ItemStreamException {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                log.warn("Error closing reader for resource {}: {}",
                        resource.getDescription(), e.getMessage());
            } finally {
                reader = null;
            }
        }
        opened = false;
    }

    /**
     * Decodes a COBOL zoned-decimal numeric string by interpreting its last character as an
     * overpunched sign+digit (standard EBCDIC encoding).
     *
     * <p>Positive mapping: {@code '{' -> +0, 'A' -> +1, 'B' -> +2, ..., 'I' -> +9}.<br>
     * Negative mapping: {@code '}' -> -0, 'J' -> -1, 'K' -> -2, ..., 'R' -> -9}.
     *
     * <p>If the last character is already a plain digit (no overpunch), the input is returned
     * unchanged (treated as unsigned positive). Examples:
     * <pre>
     *   "00000001940{" -&gt; "000000019400"   (positive, +0)
     *   "0000005047G"  -&gt; "00000050477"    (positive, +7)
     *   "0000009190}"  -&gt; "-00000091900"   (negative, -0)
     *   "0000000123J"  -&gt; "-00000001231"   (negative, -1)
     *   "12345"        -&gt; "12345"          (plain digit, unchanged)
     * </pre>
     *
     * <p>This is the decoding step that {@link FixedWidthRecordParser} intentionally omits; pair it
     * with {@link #parseZonedDecimal(String, int, int, int)} for signed money fields.
     *
     * @param overpunched the raw fixed-width numeric string (digits with an optional trailing
     *                    overpunch character)
     * @return the plain numeric string (prefixed with {@code '-'} when negative), {@code null} if
     *         the input is {@code null}, or the input unchanged if it is empty
     * @throws IllegalArgumentException if the trailing character is neither a digit nor a recognized
     *                                  overpunch character
     */
    public static String decodeZonedDecimalSign(String overpunched) {
        if (overpunched == null) {
            return null;
        }
        if (overpunched.isEmpty()) {
            return overpunched;
        }
        char lastChar = overpunched.charAt(overpunched.length() - 1);

        // Plain digit (no overpunch) — treat as positive unsigned and return unchanged.
        if (lastChar >= '0' && lastChar <= '9') {
            return overpunched;
        }

        char digitChar;
        boolean negative;

        switch (lastChar) {
            case '{': digitChar = '0'; negative = false; break;
            case 'A': digitChar = '1'; negative = false; break;
            case 'B': digitChar = '2'; negative = false; break;
            case 'C': digitChar = '3'; negative = false; break;
            case 'D': digitChar = '4'; negative = false; break;
            case 'E': digitChar = '5'; negative = false; break;
            case 'F': digitChar = '6'; negative = false; break;
            case 'G': digitChar = '7'; negative = false; break;
            case 'H': digitChar = '8'; negative = false; break;
            case 'I': digitChar = '9'; negative = false; break;
            case '}': digitChar = '0'; negative = true; break;
            case 'J': digitChar = '1'; negative = true; break;
            case 'K': digitChar = '2'; negative = true; break;
            case 'L': digitChar = '3'; negative = true; break;
            case 'M': digitChar = '4'; negative = true; break;
            case 'N': digitChar = '5'; negative = true; break;
            case 'O': digitChar = '6'; negative = true; break;
            case 'P': digitChar = '7'; negative = true; break;
            case 'Q': digitChar = '8'; negative = true; break;
            case 'R': digitChar = '9'; negative = true; break;
            default:
                throw new IllegalArgumentException(
                        "Invalid zoned-decimal overpunch character '" + lastChar
                                + "' at end of: " + overpunched);
        }

        String digitsOnly = overpunched.substring(0, overpunched.length() - 1) + digitChar;
        return negative ? "-" + digitsOnly : digitsOnly;
    }

    /**
     * Extracts a substring at the given offset/length, decodes the COBOL zoned-decimal sign
     * overpunch, and parses the result to a scaled {@link BigDecimal}.
     *
     * <p>Combines {@link #decodeZonedDecimalSign(String)} with {@link BigDecimal} scaling. The implied
     * decimal point is positioned with {@link BigDecimal#movePointLeft(int)} and the target scale is
     * enforced with {@link BigDecimal#setScale(int, RoundingMode)} using {@link RoundingMode#HALF_UP},
     * preserving COBOL {@code S9(n)V99} {@code ROUNDED} semantics (PR-16). No {@code float}/{@code double}
     * is used.
     *
     * <p>Examples: {@code parseZonedDecimal("00000001940{", 0, 12, 2)} yields {@code 194.00};
     * {@code parseZonedDecimal("0000009190}", 0, 11, 2)} yields {@code -91.90}.
     *
     * @param line   the full fixed-width line (may be {@code null})
     * @param offset zero-based starting offset of the zoned-decimal field
     * @param length total length of the field, including the trailing overpunch character
     * @param scale  the number of implied decimal positions (typically {@code 2} for money)
     * @return the parsed {@link BigDecimal} at the given scale, or {@code null} if the field is blank
     *         or the line is too short / arguments are out of bounds
     * @throws IllegalArgumentException if the decoded field is not a valid number, or the trailing
     *                                  character is an unrecognized overpunch
     */
    public static BigDecimal parseZonedDecimal(String line, int offset, int length, int scale) {
        if (line == null || offset < 0 || length <= 0 || offset + length > line.length()) {
            return null;
        }
        String raw = line.substring(offset, offset + length).trim();
        if (raw.isEmpty()) {
            return null;
        }
        String decoded = decodeZonedDecimalSign(raw);
        try {
            return new BigDecimal(decoded).movePointLeft(scale).setScale(scale, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Failed to parse zoned-decimal at offset=" + offset
                            + " length=" + length + " value='" + raw
                            + "' decoded='" + decoded + "'", e);
        }
    }

    /**
     * Resolves a fixture file by name using a classpath-first, filesystem-fallback strategy.
     *
     * <p>Search order:
     * <ol>
     *   <li>Classpath: {@code fixtures/<fileName>} (tests and embedded resources)</li>
     *   <li>Filesystem: {@code app/data/ASCII/<fileName>} (production seeding from the repository tree)</li>
     * </ol>
     *
     * <p>The classpath resource is returned only if it actually exists; otherwise the filesystem
     * resource is returned regardless of existence, so callers either verify {@link Resource#exists()}
     * or rely on {@link #open(ExecutionContext)} to raise an {@link ItemStreamException} for a missing
     * file.
     *
     * @param fileName the bare fixture file name (for example, {@code "acctdata.txt"})
     * @return the first resource that exists, or the filesystem fallback if neither exists
     * @throws NullPointerException if {@code fileName} is {@code null}
     */
    public static Resource resolveFixtureResource(String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Resource classpathResource = new ClassPathResource(CLASSPATH_FIXTURE_PREFIX + fileName);
        if (classpathResource.exists()) {
            return classpathResource;
        }
        return new FileSystemResource(FILESYSTEM_FIXTURE_PREFIX + fileName);
    }

    /**
     * Functional interface for mapping a single fixed-width line to a typed record.
     *
     * <p>Callers supply a lambda that uses {@link FixedWidthRecordParser} static methods (and, for
     * signed money fields, {@link AsciiFixedWidthItemReader#parseZonedDecimal(String, int, int, int)})
     * to extract typed values at offsets matching the COBOL {@code PIC} clauses.
     *
     * <p>Example:
     * <pre>{@code
     * RecordMapper<Account> mapper = line -> {
     *     Account a = new Account();
     *     a.setAcctId(FixedWidthRecordParser.parseLong(line, 0, 11));
     *     a.setAcctActiveStatus(FixedWidthRecordParser.parseString(line, 11, 1));
     *     a.setAcctCurrBal(AsciiFixedWidthItemReader.parseZonedDecimal(line, 12, 12, 2));
     *     // ... remaining fields
     *     return a;
     * };
     * }</pre>
     *
     * @param <T> the type of record produced
     */
    @FunctionalInterface
    public interface RecordMapper<T> {

        /**
         * Maps a single fixed-width line to a typed record.
         *
         * @param line the raw line read from the fixture (never {@code null} or blank when invoked by
         *             {@link AsciiFixedWidthItemReader#read()})
         * @return the mapped record
         * @throws Exception if the line cannot be mapped; the reader wraps it in a
         *                   {@link ParseException} that includes the offending line number
         */
        T mapLine(String line) throws Exception;
    }
}
