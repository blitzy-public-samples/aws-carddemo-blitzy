/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.common.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.aws.carddemo.common.util.FixedWidthCodec.FieldDef;
import com.aws.carddemo.common.util.FixedWidthCodec.FieldType;

/**
 * Unit tests for {@link FixedWidthCodec}, asserting byte-for-byte parity with the COBOL external
 * file contracts ({@code legacy/cpy/CVTRA06Y.cpy}, {@code legacy/cbl/CBTRN02C.cbl},
 * {@code legacy/cbl/CBSTM03A.CBL}). The zoned-decimal overpunch expectations are taken from the
 * real seed file {@code legacy/data/ASCII/dailytran.txt}.
 */
class FixedWidthCodecTest {

    /** The real DALYTRAN seed file, read relative to the Maven project base directory. */
    private static final Path DALYTRAN_SEED = Path.of("legacy/data/ASCII/dailytran.txt");

    /** The 350-byte DALYTRAN record layout from {@code legacy/cpy/CVTRA06Y.cpy}. */
    private static final FieldDef[] DALYTRAN_FIELDS = {
            FieldDef.alphanumeric("DALYTRAN-ID", 0, 16),
            FieldDef.alphanumeric("DALYTRAN-TYPE-CD", 16, 2),
            FieldDef.numeric("DALYTRAN-CAT-CD", 18, 4),
            FieldDef.alphanumeric("DALYTRAN-SOURCE", 22, 10),
            FieldDef.alphanumeric("DALYTRAN-DESC", 32, 100),
            FieldDef.signedDecimal("DALYTRAN-AMT", 132, 11, 2),
            FieldDef.numeric("DALYTRAN-MERCHANT-ID", 143, 9),
            FieldDef.alphanumeric("DALYTRAN-MERCHANT-NAME", 152, 50),
            FieldDef.alphanumeric("DALYTRAN-MERCHANT-CITY", 202, 50),
            FieldDef.alphanumeric("DALYTRAN-MERCHANT-ZIP", 252, 10),
            FieldDef.alphanumeric("DALYTRAN-CARD-NUM", 262, 16),
            FieldDef.alphanumeric("DALYTRAN-ORIG-TS", 278, 26),
            FieldDef.alphanumeric("DALYTRAN-PROC-TS", 304, 26),
            FieldDef.alphanumeric("FILLER", 330, 20)
    };

    private static final int DALYTRAN_RECORD_LENGTH = 350;
    private static final int DALYREJS_RECORD_LENGTH = 430;

    // ------------------------------------------------------------------------
    // Phase 3 - Overpunch READ (verified values from the seed file)
    // ------------------------------------------------------------------------

    @Test
    void readSignedDecimalDecodesPositiveOverpunch() {
        Assertions.assertEquals(new BigDecimal("504.77"),
                FixedWidthCodec.readSignedDecimal("0000005047G", 0, 11, 2));
        Assertions.assertEquals(new BigDecimal("67.88"),
                FixedWidthCodec.readSignedDecimal("0000000678H", 0, 11, 2));
        Assertions.assertEquals(new BigDecimal("281.77"),
                FixedWidthCodec.readSignedDecimal("0000002817G", 0, 11, 2));
        Assertions.assertEquals(new BigDecimal("454.66"),
                FixedWidthCodec.readSignedDecimal("0000004546F", 0, 11, 2));
        Assertions.assertEquals(new BigDecimal("849.99"),
                FixedWidthCodec.readSignedDecimal("0000008499I", 0, 11, 2));
    }

    @Test
    void readSignedDecimalDecodesNegativeOverpunch() {
        Assertions.assertEquals(new BigDecimal("-919.00"),
                FixedWidthCodec.readSignedDecimal("0000009190}", 0, 11, 2));
        Assertions.assertEquals(new BigDecimal("-56.77"),
                FixedWidthCodec.readSignedDecimal("0000000567P", 0, 11, 2));
    }

    @Test
    void readSignedDecimalDecodesPlainTrailingDigitAsPositive() {
        // A plain digit in the last position denotes a positive value (no overpunch applied).
        Assertions.assertEquals(new BigDecimal("504.77"),
                FixedWidthCodec.readSignedDecimal("0000005047" + "7", 0, 11, 2));
    }

    @Test
    void readSignedDecimalReturnsExactlyRequestedScale() {
        BigDecimal value = FixedWidthCodec.readSignedDecimal("0000005047G", 0, 11, 2);
        Assertions.assertEquals(2, value.scale());
    }

    // ------------------------------------------------------------------------
    // Phase 4 - Overpunch WRITE + round-trip
    // ------------------------------------------------------------------------

    @Test
    void writeSignedDecimalReproducesVerifiedFields() {
        Assertions.assertEquals("0000005047G",
                FixedWidthCodec.writeSignedDecimal(new BigDecimal("504.77"), 11, 2));
        Assertions.assertEquals("0000009190}",
                FixedWidthCodec.writeSignedDecimal(new BigDecimal("-919.00"), 11, 2));
        Assertions.assertEquals("0000000678H",
                FixedWidthCodec.writeSignedDecimal(new BigDecimal("67.88"), 11, 2));
        Assertions.assertEquals("0000000567P",
                FixedWidthCodec.writeSignedDecimal(new BigDecimal("-56.77"), 11, 2));
    }

    @Test
    void writeSignedDecimalEncodesPositiveZeroWithPositiveOverpunch() {
        Assertions.assertEquals("0000000000{",
                FixedWidthCodec.writeSignedDecimal(new BigDecimal("0.00"), 11, 2));
    }

    @Test
    void signedDecimalRoundTripsForEveryPositiveOverpunchDigit() {
        // Positive table: last digit 0..9 -> { A B C D E F G H I
        String[] positives = {
                "0000000000{", "0000000001A", "0000000002B", "0000000003C", "0000000004D",
                "0000000005E", "0000000006F", "0000000007G", "0000000008H", "0000000009I"
        };
        for (String field : positives) {
            BigDecimal value = FixedWidthCodec.readSignedDecimal(field, 0, 11, 2);
            Assertions.assertEquals(field, FixedWidthCodec.writeSignedDecimal(value, 11, 2),
                    "round-trip failed for positive overpunch field " + field);
        }
    }

    @Test
    void signedDecimalRoundTripsForEveryNegativeOverpunchDigit() {
        // Negative table: last digit 0..9 -> } J K L M N O P Q R
        String[] negatives = {
                "0000000000}", "0000000001J", "0000000002K", "0000000003L", "0000000004M",
                "0000000005N", "0000000006O", "0000000007P", "0000000008Q", "0000000009R"
        };
        for (String field : negatives) {
            BigDecimal value = FixedWidthCodec.readSignedDecimal(field, 0, 11, 2);
            // Negative zero ("...0}") normalizes to +0.00 -> positive-zero overpunch on re-emit.
            String expected = value.signum() == 0 ? "0000000000{" : field;
            Assertions.assertEquals(expected, FixedWidthCodec.writeSignedDecimal(value, 11, 2),
                    "round-trip failed for negative overpunch field " + field);
        }
    }

    @Test
    void writeSignedDecimalRejectsOverflow() {
        // 11 digits max; 100000000000 (12 digits) overflows the field.
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.writeSignedDecimal(new BigDecimal("1000000000.00"), 11, 2));
    }

    @Test
    void readSignedDecimalRejectsInvalidOverpunch() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readSignedDecimal("0000000000*", 0, 11, 2));
    }

    @Test
    void readSignedDecimalRejectsNonDigitBody() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readSignedDecimal("00000X0000G", 0, 11, 2));
    }

    // ------------------------------------------------------------------------
    // Alphanumeric primitives
    // ------------------------------------------------------------------------

    @Test
    void writeAlphanumericLeftJustifiesAndSpacePads() {
        Assertions.assertEquals("POS TERM  ", FixedWidthCodec.writeAlphanumeric("POS TERM", 10));
    }

    @Test
    void readAlphanumericTrimmedStripsTrailingSpacesOnly() {
        String padded = FixedWidthCodec.writeAlphanumeric("POS TERM", 10);
        Assertions.assertEquals("POS TERM", FixedWidthCodec.readAlphanumericTrimmed(padded, 0, 10));
        // Leading spaces are preserved.
        Assertions.assertEquals("  LEAD", FixedWidthCodec.readAlphanumericTrimmed("  LEAD    ", 0, 10));
    }

    @Test
    void writeAlphanumericTruncatesOnTheRight() {
        Assertions.assertEquals("ABCDE", FixedWidthCodec.writeAlphanumeric("ABCDEFGH", 5));
    }

    @Test
    void writeAlphanumericTreatsNullAsEmpty() {
        Assertions.assertEquals("     ", FixedWidthCodec.writeAlphanumeric(null, 5));
    }

    @Test
    void readAlphanumericPreservesExactWidth() {
        String record = "ABCDEFGHIJ";
        Assertions.assertEquals("CDE", FixedWidthCodec.readAlphanumeric(record, 2, 3));
    }

    // ------------------------------------------------------------------------
    // Numeric primitives
    // ------------------------------------------------------------------------

    @Test
    void writeNumericZeroPads() {
        Assertions.assertEquals("0001", FixedWidthCodec.writeNumeric(1, 4));
        Assertions.assertEquals("0000", FixedWidthCodec.writeNumeric(0, 4));
    }

    @Test
    void readNumericParsesLeadingZeros() {
        Assertions.assertEquals(1L, FixedWidthCodec.readNumeric("0001", 0, 4));
        Assertions.assertEquals(800000000L, FixedWidthCodec.readNumeric("800000000", 0, 9));
    }

    @Test
    void readNumericIntParsesValue() {
        Assertions.assertEquals(1, FixedWidthCodec.readNumericInt("0001", 0, 4));
    }

    @Test
    void writeNumericRejectsOverflow() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.writeNumeric(12345, 4));
    }

    @Test
    void writeNumericRejectsNegative() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.writeNumeric(-1, 4));
    }

    @Test
    void readNumericRejectsNonDigits() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readNumeric("00X1", 0, 4));
    }

    // ------------------------------------------------------------------------
    // Bounds and descriptor validation
    // ------------------------------------------------------------------------

    @Test
    void readAlphanumericRejectsOutOfRange() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readAlphanumeric("ABC", 2, 5));
    }

    @Test
    void fieldDefRejectsInvalidArguments() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FieldDef("bad-offset", -1, 4, FieldType.NUMERIC, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FieldDef("bad-length", 0, 0, FieldType.NUMERIC, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new FieldDef("bad-scale", 0, 4, FieldType.SIGNED_DECIMAL, 4));
        Assertions.assertThrows(NullPointerException.class,
                () -> new FieldDef(null, 0, 4, FieldType.NUMERIC, 0));
    }

    @Test
    void readWithMismatchedFieldTypeIsRejected() {
        FieldDef numeric = FieldDef.numeric("N", 0, 4);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readAlphanumeric("0001", numeric));
    }

    @Test
    void fieldDefEndOffsetIsOffsetPlusLength() {
        Assertions.assertEquals(143, FieldDef.signedDecimal("AMT", 132, 11, 2).endOffset());
    }

    // ------------------------------------------------------------------------
    // RecordBuilder + DALYREJS trailer
    // ------------------------------------------------------------------------

    @Test
    void recordBuilderPlacesFieldsAtOffsets() {
        FieldDef id = FieldDef.alphanumeric("ID", 0, 4);
        FieldDef cat = FieldDef.numeric("CAT", 4, 4);
        FieldDef amt = FieldDef.signedDecimal("AMT", 8, 11, 2);
        String record = FixedWidthCodec.of(19)
                .put(id, "AB")
                .put(cat, 7L)
                .put(amt, new BigDecimal("504.77"))
                .build();
        Assertions.assertEquals(19, record.length());
        Assertions.assertEquals("AB  ", record.substring(0, 4));
        Assertions.assertEquals("0007", record.substring(4, 8));
        Assertions.assertEquals("0000005047G", record.substring(8, 19));
    }

    @Test
    void recordBuilderRejectsTypeMismatch() {
        FieldDef numeric = FieldDef.numeric("N", 0, 4);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.of(4).put(numeric, "abc"));
    }

    @Test
    void dalyrejsRecordIsUntouchedTransactionPlusTrailer() throws Exception {
        String dalytran = firstSeedLine();
        String rejectRecord = dalytran
                + FixedWidthCodec.writeNumeric(102, 4)
                + FixedWidthCodec.writeAlphanumeric("OVERLIMIT TRANSACTION", 76);

        Assertions.assertEquals(DALYREJS_RECORD_LENGTH, rejectRecord.length());
        // The original 350-byte transaction image is preserved verbatim.
        Assertions.assertEquals(dalytran, rejectRecord.substring(0, DALYTRAN_RECORD_LENGTH));
        // Trailer: reason code 0102 followed by the space-padded description.
        Assertions.assertEquals("0102", rejectRecord.substring(350, 354));
        Assertions.assertEquals("OVERLIMIT TRANSACTION",
                FixedWidthCodec.readAlphanumericTrimmed(rejectRecord, 354, 76));
    }

    @Test
    void recordBuilderPutRawPlacesSegmentVerbatim() {
        // Canonical DALYREJS assembly: an untouched 350-byte image at offset 0 plus an 80-byte
        // trailer built from primitives.
        String image = "X".repeat(DALYTRAN_RECORD_LENGTH);
        String trailer = FixedWidthCodec.writeNumeric(103, 4)
                + FixedWidthCodec.writeAlphanumeric("TRANSACTION AFTER EXPIRATION", 76);
        String reject = FixedWidthCodec.of(DALYREJS_RECORD_LENGTH)
                .putRaw(0, image)
                .putRaw(DALYTRAN_RECORD_LENGTH, trailer)
                .build();
        Assertions.assertEquals(DALYREJS_RECORD_LENGTH, reject.length());
        Assertions.assertEquals(image, reject.substring(0, DALYTRAN_RECORD_LENGTH));
        Assertions.assertEquals("0103", reject.substring(350, 354));
    }

    @Test
    void recordBuilderRejectsOutOfRangePlacement() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.of(4).putRaw(2, "ABCDE"));
    }

    @Test
    void fieldDefWriteOverloadsRenderExactWidths() {
        Assertions.assertEquals("POS TERM  ",
                FixedWidthCodec.writeAlphanumeric("POS TERM", FieldDef.alphanumeric("SRC", 0, 10)));
        Assertions.assertEquals("0001",
                FixedWidthCodec.writeNumeric(1L, FieldDef.numeric("CAT", 0, 4)));
        Assertions.assertEquals("0000005047G",
                FixedWidthCodec.writeSignedDecimal(new BigDecimal("504.77"),
                        FieldDef.signedDecimal("AMT", 0, 11, 2)));
    }

    @Test
    void readSignedDecimalRejectsInvalidScale() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readSignedDecimal("0000000000G", 0, 11, 11));
    }

    @Test
    void readNumericIntRejectsValueBeyondIntRange() {
        // 9999999999 (10 digits) exceeds Integer.MAX_VALUE.
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readNumericInt("9999999999", 0, 10));
    }

    // ------------------------------------------------------------------------
    // Full external-contract guarantee: real DALYTRAN records round-trip exactly
    // ------------------------------------------------------------------------

    @Test
    void firstDalytranRecordParsesIntoExpectedFields() throws Exception {
        String line = firstSeedLine();
        Assertions.assertEquals("0000000000683580",
                FixedWidthCodec.readAlphanumeric(line, DALYTRAN_FIELDS[0]));
        Assertions.assertEquals(1L, FixedWidthCodec.readNumeric(line, DALYTRAN_FIELDS[2]));
        Assertions.assertEquals("POS TERM",
                FixedWidthCodec.readAlphanumericTrimmed(line, DALYTRAN_FIELDS[3]));
        Assertions.assertEquals(new BigDecimal("504.77"),
                FixedWidthCodec.readSignedDecimal(line, DALYTRAN_FIELDS[5]));
        Assertions.assertEquals(800000000L,
                FixedWidthCodec.readNumeric(line, DALYTRAN_FIELDS[6]));
    }

    @Test
    void everyDalytranRecordRoundTripsByteForByte() throws Exception {
        List<String> lines = Files.readAllLines(DALYTRAN_SEED, StandardCharsets.ISO_8859_1);
        Assertions.assertFalse(lines.isEmpty(), "seed file must not be empty");

        int index = 0;
        for (String line : lines) {
            Assertions.assertEquals(DALYTRAN_RECORD_LENGTH, line.length(),
                    "record " + index + " is not 350 bytes");
            String rebuilt = reencode(line);
            Assertions.assertEquals(line, rebuilt, "record " + index + " did not round-trip");
            index++;
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Reads the first record of the real DALYTRAN seed file.
     */
    private static String firstSeedLine() throws Exception {
        Assertions.assertTrue(Files.exists(DALYTRAN_SEED),
                "required seed fixture not found: " + DALYTRAN_SEED.toAbsolutePath());
        List<String> lines = Files.readAllLines(DALYTRAN_SEED, StandardCharsets.ISO_8859_1);
        Assertions.assertFalse(lines.isEmpty(), "seed file must not be empty");
        return lines.get(0);
    }

    /**
     * Decodes every field of a DALYTRAN record and re-encodes it via {@link FixedWidthCodec},
     * exercising both the read and write paths for all three field types.
     */
    private static String reencode(String line) {
        FixedWidthCodec.RecordBuilder builder = FixedWidthCodec.of(DALYTRAN_RECORD_LENGTH);
        for (FieldDef field : DALYTRAN_FIELDS) {
            switch (field.type()) {
                case ALPHANUMERIC -> builder.put(field, FixedWidthCodec.readAlphanumeric(line, field));
                case NUMERIC -> builder.put(field, FixedWidthCodec.readNumeric(line, field));
                case SIGNED_DECIMAL -> builder.put(field, FixedWidthCodec.readSignedDecimal(line, field));
            }
        }
        return builder.build();
    }
}
