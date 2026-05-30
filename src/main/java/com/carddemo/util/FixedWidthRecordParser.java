package com.carddemo.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Stateless, low-level utility for extracting typed values from fixed-width
 * COBOL-PIC-format records read from the CardDemo seed/fixture ASCII data files.
 *
 * <p>This class provides the foundational primitive operations used to parse the
 * fixed-position substrings of a single input line into Java value types
 * ({@link String}, {@link Long}, {@link Integer}, {@link BigDecimal},
 * {@link LocalDate}, {@link LocalDateTime}). It has zero internal dependencies
 * and relies solely on the {@code java.math} and {@code java.time} standard
 * libraries.
 *
 * <h2>Source files supported</h2>
 * <p>The nine fixed-width fixtures under {@code app/data/ASCII/} preserve the
 * original VSAM record layouts and are consumed by the Spring Batch seeding jobs
 * ({@code com.carddemo.batch.DataInitializationJobConfig},
 * {@code com.carddemo.batch.DailyTransactionReadJobConfig}) via
 * {@code com.carddemo.batch.reader.AsciiFixedWidthItemReader}:
 * <ul>
 *   <li>{@code acctdata.txt} (300-byte records)</li>
 *   <li>{@code carddata.txt} (150-byte records)</li>
 *   <li>{@code cardxref.txt} (50-byte records)</li>
 *   <li>{@code custdata.txt} (500-byte records)</li>
 *   <li>{@code dailytran.txt} (350-byte records)</li>
 *   <li>{@code discgrp.txt}, {@code tcatbal.txt}, {@code trancatg.txt},
 *       {@code trantype.txt} (reference-data records)</li>
 * </ul>
 *
 * <h2>Copybook references for offset/length</h2>
 * <p>Callers derive the {@code offset}/{@code length} arguments from the COBOL
 * {@code PIC} clauses in the record-defining copybooks (preserved unchanged as
 * REFERENCE per PR-27). Example field map for {@code ACCOUNT-RECORD}
 * [app/cpy/CVACT01Y.cpy, RECLN 300]:
 * <pre>
 *   ACCT-ID                PIC 9(11)      offset 0,  length 11
 *   ACCT-ACTIVE-STATUS     PIC X(01)      offset 11, length 1
 *   ACCT-CURR-BAL          PIC S9(10)V99  offset 12, length 12
 *   ACCT-OPEN-DATE         PIC X(10)      offset 48, length 10
 *   ACCT-EXPIRAION-DATE    PIC X(10)      offset 58, length 10
 * </pre>
 * Additional layouts: {@code app/cpy/CVACT02Y.cpy} (CARD-RECORD, 150),
 * {@code app/cpy/CVACT03Y.cpy} (CARD-XREF-RECORD, 50),
 * {@code app/cpy/CVCUS01Y.cpy} (CUSTOMER-RECORD, 500),
 * {@code app/cpy/CVTRA05Y.cpy} (TRAN-RECORD, 350),
 * {@code app/cpy/CVTRA06Y.cpy} (DALYTRAN-RECORD, 350).
 *
 * <h2>COBOL PIC-to-Java type mapping</h2>
 * <ul>
 *   <li>{@code PIC X(n)} (alphanumeric) &rarr; {@link #parseString} &rarr; {@link String}</li>
 *   <li>{@code PIC 9(n)} (unsigned numeric, n &gt; 9) &rarr; {@link #parseLong} &rarr; {@link Long}</li>
 *   <li>{@code PIC 9(n)} (unsigned numeric, n &le; 9) &rarr; {@link #parseInteger} &rarr; {@link Integer}</li>
 *   <li>{@code PIC S9(n)V99} (signed packed-decimal money) &rarr; {@link #parseBigDecimal}
 *       &rarr; {@link BigDecimal} (scale 2, {@link RoundingMode#HALF_UP} per PR-16)</li>
 *   <li>{@code PIC X(10)} date &rarr; {@link #parseDate} &rarr; {@link LocalDate}</li>
 *   <li>{@code PIC X(26)} timestamp &rarr; {@link #parseLocalDateTime} &rarr; {@link LocalDateTime}</li>
 * </ul>
 *
 * <h2>Decimal precision (PR-16)</h2>
 * <p>{@link #parseBigDecimal} inserts the implicit COBOL decimal point via
 * {@link BigDecimal#movePointLeft(int)} and enforces the target scale with
 * {@link BigDecimal#setScale(int, RoundingMode)} using
 * {@link RoundingMode#HALF_UP} (matching the COBOL {@code ROUNDED} clause). No
 * {@code float} or {@code double} is used anywhere — exact decimal arithmetic
 * only.
 *
 * <h2>Zoned-decimal sign characters (caller responsibility)</h2>
 * <p>The ASCII fixtures store signed numeric fields in COBOL zoned-decimal form,
 * where the sign is overpunched on the final digit (e.g., {@code '{'} = positive
 * {@code 0}, {@code '}'} = negative {@code 0}, {@code 'A'}-{@code 'I'} = positive
 * {@code 1}-{@code 9}, {@code 'J'}-{@code 'R'} = negative {@code 1}-{@code 9}). A
 * raw value such as {@code "00000001940{"} therefore is NOT a plain number and
 * would cause {@link #parseBigDecimal} to throw {@link NumberFormatException}.
 * Decoding the sign character into a plain digit-and-sign string is the
 * responsibility of the higher-level
 * {@code com.carddemo.batch.reader.AsciiFixedWidthItemReader}; this class
 * intentionally operates only on already-decoded plain numeric content, keeping
 * it minimal and single-purpose.
 *
 * <h2>Null and blank handling</h2>
 * <p>Every method degrades gracefully rather than throwing on absent input:
 * {@link #parseString} returns {@code null} for a {@code null} line, negative
 * arguments, or an {@code offset + length} that exceeds the line length (so
 * short/truncated records do not explode), and the typed parsers return
 * {@code null} for a {@code null} or blank extracted field. Malformed
 * (non-null, non-blank) numeric or date content propagates the corresponding
 * {@link NumberFormatException} / {@link java.time.format.DateTimeParseException}
 * so callers can detect genuinely dirty data.
 *
 * <h2>Thread safety</h2>
 * <p>This class is stateless — it declares no instance or static mutable fields
 * and exposes only {@code public static} methods. {@link DateTimeFormatter}
 * instances are created per call from the supplied pattern and are not shared.
 * All methods may be invoked concurrently from multiple threads (for example,
 * by parallel Spring Batch chunk processing) without external synchronization.
 *
 * @see java.math.BigDecimal
 * @see java.time.LocalDate
 * @see java.time.format.DateTimeFormatter
 */
public final class FixedWidthRecordParser {

    private FixedWidthRecordParser() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Extracts a trimmed {@link String} value from a fixed-width record at the
     * given offset and length.
     *
     * <p>Maps to COBOL {@code PIC X(n)} alphanumeric fields. The extracted
     * substring is trimmed of leading and trailing whitespace, which removes the
     * COBOL space padding used to fill fixed-width fields. A field that is all
     * spaces yields an empty string {@code ""}.
     *
     * <p>This is the foundational method: every other parser delegates here for
     * substring extraction and bounds checking.
     *
     * <p>This method never throws for out-of-range input — it returns
     * {@code null} instead, allowing callers to handle short or truncated
     * records gracefully.
     *
     * @param line   the input record line (may be {@code null})
     * @param offset zero-based starting position of the field
     * @param length number of characters in the field
     * @return the trimmed substring, or {@code null} if {@code line} is
     *         {@code null}, {@code offset} or {@code length} is negative, or
     *         {@code offset + length} exceeds the line length
     */
    public static String parseString(String line, int offset, int length) {
        if (line == null || offset < 0 || length < 0 || offset + length > line.length()) {
            return null;
        }
        return line.substring(offset, offset + length).trim();
    }

    /**
     * Extracts a {@link Long} value from a fixed-width record at the given offset
     * and length.
     *
     * <p>Maps to COBOL {@code PIC 9(n)} unsigned numeric fields (e.g.,
     * {@code ACCT-ID PIC 9(11)}, {@code CARD-ACCT-ID PIC 9(11)}). Leading zeros
     * present in the fixed-width representation are discarded by
     * {@link Long#parseLong(String)} (so {@code "00000000001"} becomes
     * {@code 1L}).
     *
     * @param line   the input record line (may be {@code null})
     * @param offset zero-based starting position of the field
     * @param length number of characters in the field
     * @return the parsed {@link Long}, or {@code null} if the extracted field is
     *         {@code null}, empty, or blank
     * @throws NumberFormatException if the extracted field contains non-numeric
     *                               characters
     */
    public static Long parseLong(String line, int offset, int length) {
        String s = parseString(line, offset, length);
        if (s == null || s.isBlank()) {
            return null;
        }
        return Long.parseLong(s);
    }

    /**
     * Extracts an {@link Integer} value from a fixed-width record at the given
     * offset and length.
     *
     * <p>Maps to COBOL {@code PIC 9(n)} unsigned numeric fields where
     * {@code n} &le; 9 and the value is therefore guaranteed to fit the
     * {@code int} range (e.g., {@code CARD-CVV-CD PIC 9(03)},
     * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}, {@code TRAN-CAT-CD PIC 9(04)}).
     * Use {@link #parseLong} for wider numeric fields.
     *
     * @param line   the input record line (may be {@code null})
     * @param offset zero-based starting position of the field
     * @param length number of characters in the field
     * @return the parsed {@link Integer}, or {@code null} if the extracted field
     *         is {@code null}, empty, or blank
     * @throws NumberFormatException if the extracted field contains non-numeric
     *                               characters
     */
    public static Integer parseInteger(String line, int offset, int length) {
        String s = parseString(line, offset, length);
        if (s == null || s.isBlank()) {
            return null;
        }
        return Integer.parseInt(s);
    }

    /**
     * Extracts a {@link BigDecimal} value from a fixed-width record at the given
     * offset and length, inserting an implicit decimal point at the specified
     * scale.
     *
     * <p>Maps to COBOL {@code PIC S9(n)V99} signed packed-decimal money fields,
     * in which the {@code V} denotes an implied decimal point with no physical
     * character in the record. The {@code scale} argument is the number of
     * implied fractional digits (typically {@code 2} for money). For example,
     * the plain digit string {@code "0012345"} with {@code scale=2} yields
     * {@code 123.45}.
     *
     * <p><b>PR-16 compliance:</b> the value is computed as
     * {@code new BigDecimal(s).movePointLeft(scale).setScale(scale,
     * RoundingMode.HALF_UP)}. The {@link BigDecimal#BigDecimal(String)}
     * constructor (never {@code double}-based factories) preserves exact decimal
     * precision; {@link BigDecimal#movePointLeft(int)} positions the implied
     * decimal point; and {@link BigDecimal#setScale(int, RoundingMode)} with
     * {@link RoundingMode#HALF_UP} enforces the target scale, matching the COBOL
     * {@code ROUNDED} clause.
     *
     * <p><b>Note:</b> this method requires already-decoded plain numeric content.
     * Raw fixture fields containing COBOL zoned-decimal sign characters (e.g.,
     * {@code "00000001940{"}) must first be normalized by the calling
     * {@code com.carddemo.batch.reader.AsciiFixedWidthItemReader}; passing such a
     * raw value here throws {@link NumberFormatException}.
     *
     * @param line   the input record line (may be {@code null})
     * @param offset zero-based starting position of the field
     * @param length number of characters in the field
     * @param scale  the implied decimal scale to apply (e.g., {@code 2} for money)
     * @return the parsed {@link BigDecimal} scaled to {@code scale} with
     *         {@link RoundingMode#HALF_UP}, or {@code null} if the extracted
     *         field is {@code null}, empty, or blank
     * @throws NumberFormatException if the extracted field is not a valid number
     */
    public static BigDecimal parseBigDecimal(String line, int offset, int length, int scale) {
        String s = parseString(line, offset, length);
        if (s == null || s.isBlank()) {
            return null;
        }
        return new BigDecimal(s).movePointLeft(scale).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Extracts a {@link LocalDate} value from a fixed-width record at the given
     * offset and length, parsed using the supplied {@link DateTimeFormatter}
     * pattern.
     *
     * <p>Maps to COBOL {@code PIC X(10)} date fields. In the CardDemo ASCII
     * fixtures these are stored in ISO form {@code yyyy-MM-dd}, but the pattern
     * is caller-supplied so any {@link DateTimeFormatter}-compatible layout is
     * supported.
     *
     * <p>Examples:
     * <pre>
     *   parseDate(line, 48, 10, "yyyy-MM-dd")  // ACCT-OPEN-DATE      "2014-11-20" -&gt; 2014-11-20
     *   parseDate(line, 58, 10, "yyyy-MM-dd")  // ACCT-EXPIRAION-DATE "2025-05-20" -&gt; 2025-05-20
     *   parseDate(line, 80, 10, "yyyy-MM-dd")  // CARD-EXPIRAION-DATE "2024-08-11" -&gt; 2024-08-11
     * </pre>
     *
     * @param line    the input record line (may be {@code null})
     * @param offset  zero-based starting position of the field
     * @param length  number of characters in the field
     * @param pattern the {@link DateTimeFormatter} pattern (e.g.,
     *                {@code "yyyy-MM-dd"})
     * @return the parsed {@link LocalDate}, or {@code null} if the extracted
     *         field is {@code null}, empty, or blank
     * @throws java.time.format.DateTimeParseException if the extracted field
     *                                                 cannot be parsed with the
     *                                                 given pattern
     * @throws IllegalArgumentException                if {@code pattern} is not a
     *                                                 valid pattern
     */
    public static LocalDate parseDate(String line, int offset, int length, String pattern) {
        String s = parseString(line, offset, length);
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * Extracts a {@link LocalDateTime} value from a fixed-width record at the
     * given offset and length, parsed using the supplied
     * {@link DateTimeFormatter} pattern.
     *
     * <p>Useful for the 26-character COBOL {@code PIC X(26)} timestamp fields
     * ({@code TRAN-ORIG-TS}, {@code TRAN-PROC-TS}) carried in the transaction
     * fixtures. For example, the {@code dailytran.txt} fixture stores
     * {@code TRAN-ORIG-TS} as {@code "2022-06-10 19:27:53.000000"}, which parses
     * with the pattern {@code "yyyy-MM-dd HH:mm:ss.SSSSSS"}; the DB2 external
     * form {@code "2022-06-10-19.27.53.000000"} parses with
     * {@code "yyyy-MM-dd-HH.mm.ss.SSSSSS"}. The pattern is caller-supplied so the
     * exact source layout is honored.
     *
     * @param line    the input record line (may be {@code null})
     * @param offset  zero-based starting position of the field
     * @param length  number of characters in the field
     * @param pattern the {@link DateTimeFormatter} pattern
     * @return the parsed {@link LocalDateTime}, or {@code null} if the extracted
     *         field is {@code null}, empty, or blank
     * @throws java.time.format.DateTimeParseException if the extracted field
     *                                                 cannot be parsed with the
     *                                                 given pattern
     * @throws IllegalArgumentException                if {@code pattern} is not a
     *                                                 valid pattern
     */
    public static LocalDateTime parseLocalDateTime(String line, int offset, int length, String pattern) {
        String s = parseString(line, offset, length);
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern));
    }
}
