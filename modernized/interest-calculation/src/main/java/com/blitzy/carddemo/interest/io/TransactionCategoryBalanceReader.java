/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.interest.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.blitzy.carddemo.interest.model.TransactionCategoryBalance;

/**
 * Ports CBACT04C {@code 1000-TCATBALF-GET-NEXT} (app/cbl/CBACT04C.cbl:L325-348). TCATBAL OPEN INPUT
 * sequential driver; record = CVTRA01Y {@code TRAN-CAT-BAL-RECORD} (RECLN 50).
 *
 * <p>In the legacy program, {@code TCATBAL-FILE} is opened {@code INPUT} (read-only) and read
 * record-by-record; it is the <strong>primary driver</strong> of the interest-calculation job. The
 * {@code PROCEDURE DIVISION} main loop (L188-232) performs {@code 1000-TCATBALF-GET-NEXT} once per
 * record, and its account-break logic (L194) depends on the records arriving in the exact
 * <strong>file order</strong>. This class reproduces that contract: it hands the parsed records to
 * the service layer, in order, so the downstream break/accumulate logic is byte-for-byte faithful
 * (AAP &sect;0.3.1, &sect;0.6.1; business rule BR-01).</p>
 *
 * <h2>Record layout &mdash; CVTRA01Y TRAN-CAT-BAL-RECORD (RECLN 50)</h2>
 * <p>Field offsets are 0-based, half-open; verified against {@code app/cpy/CVTRA01Y.cpy} (L4-L10)
 * and the {@code app/data/ASCII/tcatbal.txt} fixture (AAP &sect;0.6.2):</p>
 * <ul>
 *   <li>{@code [0,11)}  TRANCAT-ACCT-ID PIC 9(11)     &rarr; {@code acctId}  (String; preserves
 *       leading zeros &mdash; it is a lookup key, not an arithmetic operand)</li>
 *   <li>{@code [11,13)} TRANCAT-TYPE-CD PIC X(02)     &rarr; {@code typeCd}  (String)</li>
 *   <li>{@code [13,17)} TRANCAT-CD      PIC 9(04)     &rarr; {@code catCd}   (String; preserves
 *       leading zeros, e.g. {@code "0001"})</li>
 *   <li>{@code [17,28)} TRAN-CAT-BAL    PIC S9(09)V99 &rarr; {@code balance} ({@link BigDecimal}
 *       scale 2 via the overpunch/implied-decimal codec)</li>
 *   <li>{@code [28,50)} FILLER          PIC X(22)     &rarr; ignored on read</li>
 * </ul>
 * <p>Only {@code balance} (signed zoned decimal) is decoded through
 * {@link FixedWidthCodec#decodeNumeric(String, int, int, int)} (which delegates to
 * {@code support.ZonedDecimal}); the three key components are plain fixed-width substrings via
 * {@link FixedWidthCodec#slice(String, int, int)} &mdash; they are unsigned/alphanumeric and are
 * <em>not</em> run through the zoned-decimal codec.</p>
 *
 * <h2>EOF &amp; error semantics (faithful to {@code 1000-TCATBALF-GET-NEXT})</h2>
 * <ul>
 *   <li>COBOL file status {@code '00'} &rarr; record returned (L327-328).</li>
 *   <li>COBOL file status {@code '10'} &rarr; <strong>clean end-of-file</strong>: the program sets
 *       {@code END-OF-FILE = 'Y'} and the driver loop simply ends &mdash; no abend (L330, L339-340).
 *       The Java analogue is reaching the end of the read lines; this class stops without throwing.</li>
 *   <li>Any other status (read failure) &rarr; <strong>fatal</strong>: the program displays
 *       {@code 'ERROR READING TRANSACTION CATEGORY FILE'} and performs {@code 9999-ABEND-PROGRAM}
 *       (L342-345). This class mirrors that by throwing an unchecked exception carrying a clear
 *       message and the underlying cause.</li>
 * </ul>
 *
 * <h2>Scope (minimal-change / behavior preservation, AAP &sect;0.7)</h2>
 * <p>This class is <strong>read-only</strong> and performs framing/decoding only. It contains no
 * business logic (account-break, interest accumulation, and the final account update all live in the
 * {@code service} package), performs no field-content validation or normalization beyond decoding,
 * and always reads with the explicit {@link StandardCharsets#US_ASCII} charset (never the
 * platform default). Reverse-engineered strictly from CBACT04C and its copybook; it modifies nothing
 * under {@code app/}.</p>
 *
 * <p>Dependencies: standard JDK plus the sibling {@link TransactionCategoryBalance} model and the
 * same-package {@link FixedWidthCodec}. No third-party libraries.</p>
 */
public final class TransactionCategoryBalanceReader
        implements Iterable<TransactionCategoryBalance>, Closeable {

    // ---------------------------------------------------------------------------------------------
    // CVTRA01Y TRAN-CAT-BAL-RECORD field offsets/widths (0-based, half-open); RECLN 50.
    // Source: app/cpy/CVTRA01Y.cpy:L4-L10 (verified against app/data/ASCII/tcatbal.txt).
    // ---------------------------------------------------------------------------------------------

    /** TRANCAT-ACCT-ID PIC 9(11) at {@code [0,11)} (COBOL group TRAN-CAT-KEY). */
    private static final int ACCT_ID_OFFSET = 0;
    private static final int ACCT_ID_WIDTH = 11;

    /** TRANCAT-TYPE-CD PIC X(02) at {@code [11,13)} (COBOL group TRAN-CAT-KEY). */
    private static final int TYPE_CD_OFFSET = 11;
    private static final int TYPE_CD_WIDTH = 2;

    /** TRANCAT-CD PIC 9(04) at {@code [13,17)} (COBOL group TRAN-CAT-KEY). */
    private static final int CAT_CD_OFFSET = 13;
    private static final int CAT_CD_WIDTH = 4;

    /** TRAN-CAT-BAL PIC S9(09)V99 at {@code [17,28)} &mdash; 11 stored digits, implied {@code V99}. */
    private static final int BALANCE_OFFSET = 17;
    private static final int BALANCE_WIDTH = 11;

    /** Implied fractional digits for every {@code V99} field in this module. */
    private static final int BALANCE_SCALE = 2;

    /**
     * The TCATBAL input file path. The Java analogue of {@code OPEN INPUT TCATBAL-FILE}: recording
     * the path is the "open"; the actual sequential read happens on demand in {@link #readAll()}.
     */
    private final Path path;

    /**
     * Records the TCATBAL input path (the Java analogue of COBOL {@code OPEN INPUT TCATBAL-FILE}).
     *
     * <p>The driver is consumed once, front-to-back, so reading is performed on demand by
     * {@link #readAll()} / {@link #iterator()} rather than eagerly here; there is therefore no
     * persistent OS file handle held by this reader between calls.</p>
     *
     * @param path the path of the fixed-width TCATBAL fixture (one 50-byte record per line); must be
     *             non-null
     * @throws IllegalArgumentException if {@code path} is null
     */
    public TransactionCategoryBalanceReader(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("TCATBAL input path must be non-null");
        }
        this.path = path;
    }

    /**
     * Reads every TCATBAL record, in file order, and returns them as a {@link List}.
     *
     * <p>This is the primary API the service uses to drive the interest calculation. Order is
     * preserved exactly because the COBOL account-break logic (L194) relies on it. Each non-terminal
     * line is framed and decoded into one {@link TransactionCategoryBalance}.</p>
     *
     * @return the parsed records in file order (never null; empty only if the file has no records)
     * @throws UncheckedIOException if the file cannot be read (fatal; see class documentation)
     * @throws RuntimeException     if a record is malformed and cannot be framed/decoded (fatal)
     */
    public List<TransactionCategoryBalance> readAll() {
        final List<String> lines = readLines();
        final int lineCount = lines.size();
        final List<TransactionCategoryBalance> records = new ArrayList<>(lineCount);

        for (int index = 0; index < lineCount; index++) {
            final String line = lines.get(index);

            // COBOL status '10' = end of file -> clean stop, no abend (L330, L339-340).
            // The natural end of the line list IS end-of-file. Additionally tolerate a single
            // zero-length FINAL line produced by a trailing newline: skip it WITHOUT masking
            // mid-file corruption -- only the last line may be skipped, and only when it is empty.
            // (The in-repo fixtures are clean: 50 records, no trailing blank; a mid-file blank line
            //  is NOT skipped and instead fails framing below, exactly like a COBOL read error.)
            if (line.isEmpty() && index == lineCount - 1) {
                break;
            }

            records.add(parseRecord(line));
        }

        return records;
    }

    /**
     * Reads the raw fixed-width record lines with the explicit US-ASCII charset.
     *
     * <p>{@link Files#readAllLines(Path, java.nio.charset.Charset)} opens, reads, and closes the file
     * itself, so no file handle outlives this call. Splitting the lines on the line terminator is the
     * Java analogue of the COBOL sequential {@code READ NEXT} over fixed-length records.</p>
     *
     * @return the raw record lines, in file order
     * @throws UncheckedIOException if reading fails (fatal)
     */
    private List<String> readLines() {
        try {
            // TCATBAL OPEN INPUT + READ NEXT: US-ASCII (1 byte == 1 char), one 50-byte record per line.
            return Files.readAllLines(path, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            // other status -> 9999-ABEND-PROGRAM equivalent: fatal RuntimeException (L342-345).
            // Mirrors CBACT04C DISPLAY 'ERROR READING TRANSACTION CATEGORY FILE' followed by the abend.
            throw new UncheckedIOException(
                    "ERROR READING TRANSACTION CATEGORY FILE: " + path, e);
        }
    }

    /**
     * Frames and decodes a single 50-byte line into a {@link TransactionCategoryBalance}.
     *
     * <p>Field offsets cite CVTRA01Y {@code TRAN-CAT-BAL-RECORD} (app/cpy/CVTRA01Y.cpy:L4-L10). Only
     * the signed {@code balance} is routed through the zoned-decimal codec; the three key components
     * are raw fixed-width substrings that preserve leading zeros.</p>
     *
     * @param record the raw de-newlined fixed-width record line
     * @return the parsed immutable record
     * @throws RuntimeException if the line is malformed (too short, bad overpunch, etc.) &mdash; fatal
     */
    private TransactionCategoryBalance parseRecord(String record) {
        try {
            // CVTRA01Y TRAN-CAT-BAL-RECORD offsets (0-based, half-open); app/cpy/CVTRA01Y.cpy:L4-L10.
            // [0,11)  TRANCAT-ACCT-ID 9(11)     -> String (preserve leading zeros; key, not arithmetic).
            final String acctId = FixedWidthCodec.slice(record, ACCT_ID_OFFSET, ACCT_ID_WIDTH);
            // [11,13) TRANCAT-TYPE-CD X(02)     -> String.
            final String typeCd = FixedWidthCodec.slice(record, TYPE_CD_OFFSET, TYPE_CD_WIDTH);
            // [13,17) TRANCAT-CD      9(04)     -> String (preserve leading zeros, e.g. "0001").
            final String catCd = FixedWidthCodec.slice(record, CAT_CD_OFFSET, CAT_CD_WIDTH);
            // [17,28) TRAN-CAT-BAL    S9(09)V99 -> BigDecimal scale 2 (overpunch sign + implied V99).
            final BigDecimal balance =
                    FixedWidthCodec.decodeNumeric(record, BALANCE_OFFSET, BALANCE_WIDTH, BALANCE_SCALE);
            // FILLER X(22) at [28,50) is intentionally ignored on read.
            return new TransactionCategoryBalance(acctId, typeCd, catCd, balance);
        } catch (RuntimeException e) {
            // other status -> 9999-ABEND-PROGRAM equivalent: fatal RuntimeException (L342-345).
            // A malformed/short record (slice/decode failure) is a hard error, exactly like the COBOL
            // read-error path: DISPLAY 'ERROR READING TRANSACTION CATEGORY FILE' then abend.
            throw new RuntimeException(
                    "ERROR READING TRANSACTION CATEGORY FILE: malformed TRAN-CAT-BAL-RECORD in "
                            + path + ": \"" + record + "\"", e);
        }
    }

    /**
     * Returns an iterator over the TCATBAL records in file order, so the service can stream the driver
     * with an enhanced-for loop.
     *
     * <p>End-of-file is simply the end of the returned sequence (the COBOL {@code END-OF-FILE = 'Y'}
     * clean stop, L339-340). Order is preserved because {@link #readAll()} preserves file order, on
     * which the account-break logic depends.</p>
     *
     * @return an iterator over the parsed records, in file order
     * @throws UncheckedIOException if the file cannot be read (fatal)
     * @throws RuntimeException     if a record is malformed (fatal)
     */
    @Override
    public Iterator<TransactionCategoryBalance> iterator() {
        return readAll().iterator();
    }

    /**
     * Closes the reader. Implemented to mirror the COBOL {@code OPEN INPUT -> READ NEXT -> CLOSE}
     * lifecycle ({@code 9000-TCATBALF-CLOSE}) and to support try-with-resources at the call site.
     *
     * <p>This is intentionally a no-op and never throws: {@link #readAll()} uses
     * {@link Files#readAllLines(Path, java.nio.charset.Charset)}, which opens and closes the
     * underlying file itself, so this reader holds no long-lived OS handle to release.</p>
     */
    @Override
    public void close() {
        // No resource to release: Files.readAllLines self-manages the file handle (see Javadoc).
    }
}
