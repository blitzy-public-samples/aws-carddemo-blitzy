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
package com.carddemo.transaction.batch;

import com.carddemo.common.batch.BatchOutputPathResolver;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.common.exception.TransactionRejectException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Unit tests for :class:`RejectFileItemWriter`.
 *
 * :purpose: Verify the ``CBTRN02C`` ``2500-WRITE-REJECT-REC`` record shape that
 *     AAP §0.6.4 freezes: a 430-byte reject record made of the 350-byte
 *     ``DALYTRAN-RECORD`` image (``CVTRA06Y``, RECLN 350) followed by the 80-byte
 *     validation trailer — a ``PIC 9(04)`` reason code and a ``PIC X(76)``
 *     description — with every field at its exact offset and width, the
 *     ``S9(09)V99`` amount carrying a trailing overpunch sign.
 * :output: JUnit 5 / AssertJ assertions against a real file written to a JUnit
 *     temporary directory; no Spring context and no database.
 */
class RejectFileItemWriterTest {

    /** :purpose: Total reject-record width (350-byte image + 80-byte trailer). */
    private static final int RECORD_LENGTH = 430;

    /** :purpose: Width of the reconstructed ``DALYTRAN-RECORD`` image. */
    private static final int IMAGE_LENGTH = 350;

    /**
     * Job-execution id of the run under test, used as the DALYREJS generation number the
     * writer qualifies its file name with.
     */
    private static final long EXECUTION_ID = 33L;

    /** The generation the writer produces for {@link #EXECUTION_ID}. */
    private static final String GENERATION_FILE = "dalyrejs.G0033V00.txt";

    @TempDir
    private Path tempDir;

    /**
     * Builds a fully populated daily-transaction record.
     *
     * :param amount: the ``DALYTRAN-AMT`` value written as ``S9(09)V99`` zoned decimal.
     * :output: the record whose 350-byte image the writer reconstructs.
     */
    private static DailyTransaction dailyTransaction(String amount) {
        DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId("0000000000000501");
        dt.setDalytranTypeCd("01");
        dt.setDalytranCatCd(5001);
        dt.setDalytranSource("POS TERM");
        dt.setDalytranDesc("Point of sale purchase");
        dt.setDalytranAmt(new BigDecimal(amount));
        dt.setDalytranMerchantId(123456789L);
        dt.setDalytranMerchantName("Mercado Central");
        dt.setDalytranMerchantCity("Springfield");
        dt.setDalytranMerchantZip("22770");
        dt.setDalytranCardNum("4111111111111111");
        dt.setDalytranOrigTs("2024-06-15-13.45.30.123456");
        dt.setDalytranProcTs("2024-06-16-01.00.00.000000");
        return dt;
    }

    /**
     * Runs the writer over a chunk and returns the lines it produced.
     *
     * :param items: the posting items handed to the writer.
     * :output: the lines of the reject file, in write order.
     */
    private List<String> writeAndRead(PostingItem... items) throws Exception {
        // The writer resolves its destination through the shared output-path resolver, which
        // confines every generated artifact to a configured, writable root; the temp dir
        // stands in for the writable mount the container provides.
        // The writer generation-qualifies its destination per RUN, exactly as the legacy
        // job stream allocated a new DALYREJS GDG generation per run, so the expected file
        // name carries the execution id of the run under test.
        RejectFileItemWriter writer = new RejectFileItemWriter(
                "dalyrejs.txt",
                EXECUTION_ID,
                new BatchOutputPathResolver(tempDir.toString(), tempDir.toString()));
        Path rejectFile = tempDir.resolve(GENERATION_FILE);
        writer.open(new ExecutionContext());
        try {
            Chunk<PostingItem> chunk = new Chunk<>();
            for (PostingItem item : items) {
                chunk.add(item);
            }
            writer.write(chunk);
            writer.update(new ExecutionContext());
        } finally {
            writer.close();
        }
        return readLines(tempDir.resolve(GENERATION_FILE));
    }

    /**
     * Reads a file's lines, tolerating a file the writer never created.
     *
     * :param file: the reject file path.
     * :output: the lines present, or an empty list when the file holds nothing.
     */
    private static List<String> readLines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    /**
     * Runs the writer over a chunk and returns the RAW BYTES of each record it wrote.
     *
     * :param items: the posting items handed to the writer.
     * :output: one byte array per written record, line separators removed.
     */
    private List<byte[]> writeAndReadBytes(PostingItem... items) throws Exception {
        byte[] all = writeAndReadRawBytes(items);
        List<byte[]> records = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\n' || all[i] == '\r') {
                if (i > start) {
                    records.add(Arrays.copyOfRange(all, start, i));
                }
                start = i + 1;
            }
        }
        if (start < all.length) {
            records.add(Arrays.copyOfRange(all, start, all.length));
        }
        return records;
    }

    /**
     * Runs the writer over a chunk and returns the reject file's bytes UNSPLIT, so the
     * record delimiter itself can be asserted.
     *
     * :param items: the posting items handed to the writer.
     * :output: every byte the writer emitted, delimiters included.
     */
    private byte[] writeAndReadRawBytes(PostingItem... items) throws Exception {
        RejectFileItemWriter writer = new RejectFileItemWriter(
                "dalyrejs.txt",
                EXECUTION_ID,
                new BatchOutputPathResolver(tempDir.toString(), tempDir.toString()));
        writer.open(new ExecutionContext());
        try {
            Chunk<PostingItem> chunk = new Chunk<>();
            for (PostingItem item : items) {
                chunk.add(item);
            }
            writer.write(chunk);
            writer.update(new ExecutionContext());
        } finally {
            writer.close();
        }
        return Files.readAllBytes(tempDir.resolve(GENERATION_FILE));
    }

    @Nested
    @DisplayName("Restart tolerance (a re-driven posting cycle must be able to open the file)")
    class RestartTolerance {

        /**
         * :purpose: A re-driven cycle must open cleanly even though the failed attempt's
         *     reject generation was deleted by the cleanup listener. The writer previously
         *     saved its byte offset, so the restart required that file to still exist and
         *     died in ``open`` before reading a record — which is what made a failed posting
         *     date permanently un-redrivable.
         */
        @Test
        @DisplayName("opens cleanly on a restart whose previous reject file was removed")
        void opensOnRestartAfterTheFileWasRemoved() throws Exception {
            // The writer generation-qualifies its destination per RUN, so the file a
            // re-driven attempt opens is the generation of the execution under test.
            RejectFileItemWriter writer = new RejectFileItemWriter(
                    "dalyrejs.txt",
                    EXECUTION_ID,
                    new BatchOutputPathResolver(tempDir.toString(), tempDir.toString()));
            Path rejectFile = tempDir.resolve(GENERATION_FILE);

            // First attempt writes one record, then its output is deleted exactly as
            // rejectFileCleanupListener does for an unsuccessful step.
            ExecutionContext firstAttempt = new ExecutionContext();
            writer.open(firstAttempt);
            Chunk<PostingItem> chunk = new Chunk<>();
            chunk.add(PostingItem.rejected(dailyTransaction("100.00"),
                    TransactionRejectException.INVALID_CARD_NUMBER,
                    TransactionRejectException.MSG_INVALID_CARD_NUMBER, null, null));
            writer.write(chunk);
            writer.update(firstAttempt);
            writer.close();
            Files.deleteIfExists(rejectFile);

            // The restart is handed the SAME execution context the first attempt updated.
            writer.open(firstAttempt);
            try {
                writer.write(chunk);
                writer.update(firstAttempt);
            } finally {
                writer.close();
            }

            assertThat(readLines(rejectFile))
                    .as("the re-driven attempt writes its own complete reject generation")
                    .hasSize(1);
            assertThat(readLines(rejectFile).get(0)).hasSize(RECORD_LENGTH);
        }

        /**
         * :purpose: A fresh attempt must not append to a reject generation left behind by an
         *     earlier one: the legacy job stream allocated a new ``DALYREJS`` generation per
         *     run, so each attempt's file holds only its own rejects.
         */
        @Test
        @DisplayName("a later attempt truncates a reject file left behind rather than appending")
        void truncatesAnExistingRejectFile() throws Exception {
            // The writer generation-qualifies its destination per RUN, so the file a
            // re-driven attempt opens is the generation of the execution under test.
            RejectFileItemWriter writer = new RejectFileItemWriter(
                    "dalyrejs.txt",
                    EXECUTION_ID,
                    new BatchOutputPathResolver(tempDir.toString(), tempDir.toString()));
            Path rejectFile = tempDir.resolve(GENERATION_FILE);
            Chunk<PostingItem> chunk = new Chunk<>();
            chunk.add(PostingItem.rejected(dailyTransaction("100.00"),
                    TransactionRejectException.INVALID_CARD_NUMBER,
                    TransactionRejectException.MSG_INVALID_CARD_NUMBER, null, null));

            for (int attempt = 0; attempt < 3; attempt++) {
                ExecutionContext context = new ExecutionContext();
                writer.open(context);
                writer.write(chunk);
                writer.update(context);
                writer.close();
                assertThat(readLines(rejectFile))
                        .as("attempt %d must leave exactly its own record", attempt)
                        .hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("Frozen 430-BYTE record contract for non-ASCII data")
    class ByteContract {

        /**
         * :purpose: ``DALYREJS`` is a ``RECFM=F LRECL=430`` data set
         *     [app/jcl/POSTTRAN.jcl], so 430 is a BYTE count. Under the platform default
         *     UTF-8 a single accented character in a text field made the record 431+
         *     bytes and byte-shifted every field after it, so a byte-offset downstream
         *     reader lost the reject reason code entirely. One character
         *     maps to exactly one byte in the writer's ISO-8859-1 encoding.
         */
        @Test
        @DisplayName("an accented merchant name and description still yield exactly 430 bytes")
        void accentedDataKeepsTheRecordAt430Bytes() throws Exception {
            DailyTransaction accented = dailyTransaction("25.00");
            accented.setDalytranDesc("Purchase at Café Müller");
            accented.setDalytranMerchantName("Café Müller");
            accented.setDalytranMerchantCity("Zürich");

            List<byte[]> records = writeAndReadBytes(PostingItem.rejected(accented,
                    TransactionRejectException.INVALID_CARD_NUMBER,
                    TransactionRejectException.MSG_INVALID_CARD_NUMBER, null, null));

            assertThat(records).hasSize(1);
            assertThat(records.get(0)).hasSize(RECORD_LENGTH);
            // The reason code must still sit at bytes 351-354 (0-based 350-353).
            assertThat(new String(Arrays.copyOfRange(records.get(0), IMAGE_LENGTH, IMAGE_LENGTH + 4),
                    StandardCharsets.ISO_8859_1)).isEqualTo("0100");
        }

        /**
         * :purpose: A character outside ISO-8859-1 (an emoji) degrades to a single
         *     replacement byte rather than a multi-byte sequence, so the fixed-width
         *     contract holds for any input the staging table can carry.
         */
        @Test
        @DisplayName("characters outside ISO-8859-1 still yield exactly 430 bytes")
        void unicodeDataKeepsTheRecordAt430Bytes() throws Exception {
            DailyTransaction unicode = dailyTransaction("25.00");
            unicode.setDalytranDesc("Purchase \uD83D\uDE00 emoji \u4E2D\u6587");
            unicode.setDalytranMerchantName("\uD83D\uDE00 Store");

            List<byte[]> records = writeAndReadBytes(PostingItem.rejected(unicode,
                    TransactionRejectException.ACCOUNT_EXPIRED,
                    TransactionRejectException.MSG_ACCOUNT_EXPIRED, null, null));

            assertThat(records).hasSize(1);
            assertThat(records.get(0)).hasSize(RECORD_LENGTH);
            assertThat(new String(Arrays.copyOfRange(records.get(0), IMAGE_LENGTH, IMAGE_LENGTH + 4),
                    StandardCharsets.ISO_8859_1)).isEqualTo("0103");
        }

        /**
         * :purpose: The record delimiter is pinned to a single ``LF``, so a stream of
         *     ``n`` reject records occupies exactly ``n * (430 + 1)`` bytes. The
         *     ``System.lineSeparator()`` default would emit ``CRLF`` on a Windows host
         *     or under ``-Dline.separator``, adding a byte to every record; the
         *     record-splitting helper tolerates either delimiter, so only a raw byte
         *     assertion can detect that regression.
         */
        @Test
        @DisplayName("the record delimiter is exactly one LF byte, never CRLF")
        void recordDelimiterIsASingleLineFeed() throws Exception {
            PostingItem first = PostingItem.rejected(dailyTransaction("10.00"),
                    TransactionRejectException.INVALID_CARD_NUMBER,
                    TransactionRejectException.MSG_INVALID_CARD_NUMBER, null, null);
            PostingItem second = PostingItem.rejected(dailyTransaction("20.00"),
                    TransactionRejectException.ACCOUNT_NOT_FOUND,
                    TransactionRejectException.MSG_ACCOUNT_NOT_FOUND, null, null);

            byte[] all = writeAndReadRawBytes(first, second);

            assertThat(all).hasSize(2 * (RECORD_LENGTH + 1));
            assertThat(all[RECORD_LENGTH]).isEqualTo((byte) '\n');
            assertThat(all[2 * RECORD_LENGTH + 1]).isEqualTo((byte) '\n');
            assertThat(all).doesNotContain((byte) '\r');
        }
    }

    @Nested
    @DisplayName("430-byte record layout")
    class RecordLayout {

        @Test
        @DisplayName("a reject record is exactly 430 characters: a 350-byte image plus an 80-byte trailer")
        void recordIsExactly430Characters() throws Exception {
            PostingItem item = PostingItem.rejected(dailyTransaction("250.75"),
                    TransactionRejectException.OVER_LIMIT,
                    TransactionRejectException.MSG_OVER_LIMIT, null, 12345678901L);

            List<String> lines = writeAndRead(item);

            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).hasSize(RECORD_LENGTH);
            assertThat(lines.get(0).substring(0, IMAGE_LENGTH)).hasSize(350);
            assertThat(lines.get(0).substring(IMAGE_LENGTH)).hasSize(80);
        }

        @Test
        @DisplayName("every DALYTRAN field sits at its exact CVTRA06Y offset and width")
        void everyFieldSitsAtItsDeclaredOffset() throws Exception {
            PostingItem item = PostingItem.rejected(dailyTransaction("250.75"),
                    TransactionRejectException.OVER_LIMIT,
                    TransactionRejectException.MSG_OVER_LIMIT, null, 12345678901L);

            String record = writeAndRead(item).get(0);

            assertField(record, "DALYTRAN-ID X(16)", "0000000000000501", 0, 16);
            assertField(record, "DALYTRAN-TYPE-CD X(02)", "01", 16, 18);
            assertField(record, "DALYTRAN-CAT-CD 9(04)", "5001", 18, 22);
            assertField(record, "DALYTRAN-SOURCE X(10)", padRight("POS TERM", 10), 22, 32);
            assertField(record, "DALYTRAN-DESC X(100)", padRight("Point of sale purchase", 100), 32, 132);
            assertField(record, "DALYTRAN-AMT S9(09)V99", "0000002507E", 132, 143);
            assertField(record, "DALYTRAN-MERCHANT-ID 9(09)", "123456789", 143, 152);
            assertField(record, "DALYTRAN-MERCHANT-NAME X(50)", padRight("Mercado Central", 50), 152, 202);
            assertField(record, "DALYTRAN-MERCHANT-CITY X(50)", padRight("Springfield", 50), 202, 252);
            assertField(record, "DALYTRAN-MERCHANT-ZIP X(10)", padRight("22770", 10), 252, 262);
            assertField(record, "DALYTRAN-CARD-NUM X(16)", "4111111111111111", 262, 278);
            assertField(record, "DALYTRAN-ORIG-TS X(26)", "2024-06-15-13.45.30.123456", 278, 304);
            assertField(record, "DALYTRAN-PROC-TS X(26)", "2024-06-16-01.00.00.000000", 304, 330);
            assertField(record, "FILLER X(20)", " ".repeat(20), 330, 350);
        }

        @Test
        @DisplayName("the 80-byte trailer is a 4-digit reason code followed by the 76-character description")
        void trailerCarriesTheZeroPaddedCodeAndPaddedDescription() throws Exception {
            PostingItem item = PostingItem.rejected(dailyTransaction("10.00"),
                    TransactionRejectException.INVALID_CARD_NUMBER,
                    TransactionRejectException.MSG_INVALID_CARD_NUMBER, null, null);

            String record = writeAndRead(item).get(0);

            assertField(record, "WS-VALIDATION-FAIL-REASON 9(04)", "0100", 350, 354);
            assertField(record, "WS-VALIDATION-FAIL-REASON-DESC X(76)", padRight("INVALID CARD NUMBER FOUND", 76), 354, 430);
        }

        @ParameterizedTest
        @DisplayName("each reject code is written zero-padded to four digits with its verbatim description")
        @CsvSource({
                "100, 0100, INVALID CARD NUMBER FOUND",
                "101, 0101, ACCOUNT RECORD NOT FOUND",
                "102, 0102, OVERLIMIT TRANSACTION",
                "103, 0103, TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
        })
        void everyRejectCodeIsWrittenAsFourDigits(int code, String expectedDigits,
                                                  String description) throws Exception {
            PostingItem item = PostingItem.rejected(dailyTransaction("10.00"), code, description,
                    null, 12345678901L);

            String record = writeAndRead(item).get(0);

            assertThat(record).hasSize(RECORD_LENGTH);
            assertField(record, "reason code", expectedDigits, 350, 354);
            assertThat(record.substring(354).trim()).isEqualTo(description);
        }
    }

    @Nested
    @DisplayName("S9(09)V99 zoned-decimal amount encoding")
    class AmountEncoding {

        @ParameterizedTest
        @DisplayName("the eleventh character encodes both the final digit and the sign")
        @CsvSource({
                "250.75,      0000002507E",   // +5 -> 'E'
                "-250.75,     0000002507N",   // -5 -> 'N'
                "0.00,        0000000000{",   // +0 -> '{'
                "-0.01,       0000000000J",   // -1 -> 'J'
                "1.00,        0000000010{",
                "9.99,        0000000099I",   // +9 -> 'I'
                "-9.99,       0000000099R",   // -9 -> 'R'
                "999999999.99, 9999999999I"
        })
        void amountIsEncodedWithATrailingOverpunch(String amount, String expected) throws Exception {
            PostingItem item = PostingItem.rejected(dailyTransaction(amount),
                    TransactionRejectException.OVER_LIMIT,
                    TransactionRejectException.MSG_OVER_LIMIT, null, 12345678901L);

            String record = writeAndRead(item).get(0);

            assertField(record, "DALYTRAN-AMT", expected, 132, 143);
            assertThat(record).hasSize(RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Routing and padding behaviour")
    class RoutingAndPadding {

        @Test
        @DisplayName("valid items are never written to the reject sink")
        void validItemsAreFilteredOut() throws Exception {
            PostingItem valid = PostingItem.valid(dailyTransaction("10.00"), null, 12345678901L);

            assertThat(writeAndRead(valid)).isEmpty();
        }

        @Test
        @DisplayName("a mixed chunk writes only its rejected items, in order")
        void onlyRejectedItemsOfAMixedChunkAreWritten() throws Exception {
            DailyTransaction first = dailyTransaction("10.00");
            first.setDalytranId("0000000000000001");
            DailyTransaction second = dailyTransaction("20.00");
            second.setDalytranId("0000000000000002");
            DailyTransaction third = dailyTransaction("30.00");
            third.setDalytranId("0000000000000003");

            List<String> lines = writeAndRead(
                    PostingItem.rejected(first, TransactionRejectException.INVALID_CARD_NUMBER,
                            TransactionRejectException.MSG_INVALID_CARD_NUMBER, null, null),
                    PostingItem.valid(second, null, 12345678901L),
                    PostingItem.rejected(third, TransactionRejectException.ACCOUNT_EXPIRED,
                            TransactionRejectException.MSG_ACCOUNT_EXPIRED, null, 12345678901L));

            assertThat(lines).hasSize(2);
            assertThat(lines.get(0)).hasSize(RECORD_LENGTH).startsWith("0000000000000001");
            assertThat(lines.get(1)).hasSize(RECORD_LENGTH).startsWith("0000000000000003");
            assertThat(lines.get(0).substring(350, 354)).isEqualTo("0100");
            assertThat(lines.get(1).substring(350, 354)).isEqualTo("0103");
        }

        @Test
        @DisplayName("an over-long alphanumeric value is truncated to its field width, keeping the record at 430")
        void overLongValuesAreTruncatedToTheFieldWidth() throws Exception {
            DailyTransaction dt = dailyTransaction("10.00");
            dt.setDalytranDesc("D".repeat(140));
            dt.setDalytranMerchantName("N".repeat(60));

            String record = writeAndRead(PostingItem.rejected(dt,
                    TransactionRejectException.OVER_LIMIT,
                    TransactionRejectException.MSG_OVER_LIMIT, null, 12345678901L)).get(0);

            assertThat(record).hasSize(RECORD_LENGTH);
            assertField(record, "truncated DALYTRAN-DESC", "D".repeat(100), 32, 132);
            assertField(record, "truncated MERCHANT-NAME", "N".repeat(50), 152, 202);
        }

        @Test
        @DisplayName("null alphanumeric and numeric values become spaces and zeros, keeping the record at 430")
        void nullValuesAreSpaceAndZeroFilled() throws Exception {
            DailyTransaction dt = new DailyTransaction();
            dt.setDalytranId("0000000000000009");
            dt.setDalytranAmt(new BigDecimal("0.00"));
            dt.setDalytranOrigTs("2024-06-15-13.45.30.123456");

            String record = writeAndRead(PostingItem.rejected(dt,
                    TransactionRejectException.ACCOUNT_NOT_FOUND,
                    TransactionRejectException.MSG_ACCOUNT_NOT_FOUND, null, 12345678901L)).get(0);

            assertThat(record).hasSize(RECORD_LENGTH);
            assertField(record, "null DALYTRAN-TYPE-CD", " ".repeat(2), 16, 18);
            assertField(record, "null DALYTRAN-CAT-CD", "0000", 18, 22);
            assertField(record, "null MERCHANT-ID", "000000000", 143, 152);
            assertField(record, "null DALYTRAN-PROC-TS", " ".repeat(26), 304, 330);
        }
    }

    /**
     * Asserts that a half-open column range of a record holds exactly the expected
     * characters, naming the COBOL field in the failure message.
     *
     * :param record: the whole 430-character reject record.
     * :param field: the COBOL field name reported when the assertion fails.
     * :param expected: the expected slice content, including its padding.
     * :param from: inclusive start column (0-based).
     * :param to: exclusive end column.
     */
    private static void assertField(String record, String field, String expected, int from, int to) {
        assertThat(record.substring(from, to))
                .as("%s at columns [%d,%d)", field, from, to)
                .isEqualTo(expected);
    }

    /**
     * Left-justifies and space-pads a value to a fixed width.
     *
     * :param value: the value to pad.
     * :param width: the target width.
     * :output: the padded value.
     */
    private static String padRight(String value, int width) {
        return value + " ".repeat(width - value.length());
    }
}
