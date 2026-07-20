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
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fast, isolated, pure-logic unit tests for {@link FixedWidthCodec}, the Java re-platform of the
 * COBOL sequential-file record I/O that defines the CardDemo batch <em>external file contracts</em>
 * (AAP &sect;0.5.5; hotspot M2 in &sect;0.7.2). Every expectation is derived from verified real-seed
 * field values and asserted byte-for-byte against the legacy layouts:
 *
 * <ul>
 *   <li>{@code legacy/cpy/CVTRA06Y.cpy} &mdash; the 350-byte {@code DALYTRAN-RECORD} layout
 *       (source {@code app/cpy/CVTRA06Y.cpy});</li>
 *   <li>{@code legacy/cbl/CBTRN02C.cbl} &mdash; the {@code DALYREJS} reject record composition
 *       ({@code FD-REJS-RECORD} = {@code PIC X(350)} image + {@code PIC X(80)} trailer = 430 bytes;
 *       source {@code app/cbl/CBTRN02C.cbl});</li>
 *   <li>{@code legacy/cbl/CBSTM03A.CBL} &mdash; the 80-byte statement line
 *       ({@code FD-STMTFILE-REC PIC X(80)}; source {@code app/cbl/CBSTM03A.CBL}).</li>
 * </ul>
 *
 * <p>The zoned-decimal overpunch expectations (the parity-critical detail) come from records 1&ndash;6
 * of the real seed file {@code legacy/data/ASCII/dailytran.txt} (source
 * {@code app/data/ASCII/dailytran.txt}). Signed numeric fields store the sign overpunched on the
 * <em>last</em> digit &mdash; positive {@code 0..9} &rarr; <code>{ A B C D E F G H I</code>, negative
 * {@code 0..9} &rarr; <code>} J K L M N O P Q R</code> &mdash; with no leading minus and no physical
 * decimal point.</p>
 *
 * <p>The suite runs headlessly and reproducibly: there is no Spring context, no database, no
 * Testcontainers, and no file I/O. Record data is assembled in-test from the verified seed values so
 * the tests depend only on {@link FixedWidthCodec} (same package, referenced without an import).</p>
 */
class FixedWidthCodecTest {

    // ------------------------------------------------------------------------
    // Verified real-seed constants (records 1-6 of legacy/data/ASCII/dailytran.txt)
    // ------------------------------------------------------------------------

    /** Field width of the {@code DALYTRAN-AMT} {@code PIC S9(09)V99} amount field. */
    private static final int AMT_LENGTH = 11;

    /** Implied fractional digits (scale) of every monetary field. */
    private static final int MONEY_SCALE = 2;

    /** Total width of the {@code DALYTRAN-RECORD} ({@code legacy/cpy/CVTRA06Y.cpy}, RECLN=350). */
    private static final int DALYTRAN_LENGTH = 350;

    /** Total width of the {@code DALYREJS} reject record (350-byte image + 80-byte trailer). */
    private static final int DALYREJS_LENGTH = 430;

    /**
     * The six {@code DALYTRAN-AMT} overpunch fields read directly from records 1-6 of the real seed
     * file, paired with their decoded decimal string. These exercise the full positive alphabet
     * {@code {A-I} and one negative code {@code }} on real data.
     */
    private static final String[] SEED_AMT_FIELDS = {
            "0000005047G", // record 1: +504.77 (G = positive digit 7)
            "0000009190}", // record 2: -919.00 (} = negative digit 0)
            "0000000678H", // record 3: +67.88  (H = positive digit 8)
            "0000002817G", // record 4: +281.77 (G = positive digit 7)
            "0000004546F", // record 5: +454.66 (F = positive digit 6)
            "0000008499I"  // record 6: +849.99 (I = positive digit 9)
    };

    /** Decoded values (as decimal strings) matching {@link #SEED_AMT_FIELDS} element-for-element. */
    private static final String[] SEED_AMT_VALUES = {
            "504.77", "-919.00", "67.88", "281.77", "454.66", "849.99"
    };

    // ========================================================================
    // Phase 2 - Signed-decimal OVERPUNCH READ (parity-critical)
    // ========================================================================

    @Test
    void readSignedDecimalDecodesPositiveOverpunchFromSeedData() {
        assertThat(FixedWidthCodec.readSignedDecimal("0000005047G", 0, AMT_LENGTH, MONEY_SCALE))
                .isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(FixedWidthCodec.readSignedDecimal("0000000678H", 0, AMT_LENGTH, MONEY_SCALE))
                .isEqualByComparingTo(new BigDecimal("67.88"));
        assertThat(FixedWidthCodec.readSignedDecimal("0000002817G", 0, AMT_LENGTH, MONEY_SCALE))
                .isEqualByComparingTo(new BigDecimal("281.77"));
        assertThat(FixedWidthCodec.readSignedDecimal("0000004546F", 0, AMT_LENGTH, MONEY_SCALE))
                .isEqualByComparingTo(new BigDecimal("454.66"));
        assertThat(FixedWidthCodec.readSignedDecimal("0000008499I", 0, AMT_LENGTH, MONEY_SCALE))
                .isEqualByComparingTo(new BigDecimal("849.99"));
    }

    @Test
    void readSignedDecimalDecodesNegativeOverpunchFromSeedData() {
        // Trailing '}' is the negative-zero overpunch: 0000009190} -> -919.00.
        assertThat(FixedWidthCodec.readSignedDecimal("0000009190}", 0, AMT_LENGTH, MONEY_SCALE))
                .isEqualByComparingTo(new BigDecimal("-919.00"));
    }

    @Test
    void readSignedDecimalReturnsExactlyRequestedScale() {
        // The COBOL implied decimal point yields a BigDecimal whose scale equals the requested scale.
        assertThat(FixedWidthCodec.readSignedDecimal("0000005047G", 0, AMT_LENGTH, MONEY_SCALE).scale())
                .isEqualTo(MONEY_SCALE);
        assertThat(FixedWidthCodec.readSignedDecimal("0000009190}", 0, AMT_LENGTH, MONEY_SCALE).scale())
                .isEqualTo(MONEY_SCALE);
    }

    @Test
    void readSignedDecimalRejectsInvalidOverpunchCharacter() {
        // A clearly-invalid trailing character (all-digit body, bogus sign nibble) is rejected.
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readSignedDecimal("0000000000*", 0, AMT_LENGTH, MONEY_SCALE));
    }

    @Test
    void readSignedDecimalRejectsNonDigitBody() {
        // Non-digit characters in the leading (body) positions are rejected even with a valid sign.
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readSignedDecimal("00000X0047G", 0, AMT_LENGTH, MONEY_SCALE));
    }

    // ========================================================================
    // Phase 3 - Signed-decimal OVERPUNCH WRITE + byte-exact ROUND-TRIP
    // ========================================================================

    @Test
    void signedDecimalRoundTripsByteForByteForEverySeedField() {
        // read(field) then write(value) must reproduce the exact original 11-character field.
        for (int i = 0; i < SEED_AMT_FIELDS.length; i++) {
            String field = SEED_AMT_FIELDS[i];
            BigDecimal value = FixedWidthCodec.readSignedDecimal(field, 0, AMT_LENGTH, MONEY_SCALE);
            assertThat(value).isEqualByComparingTo(new BigDecimal(SEED_AMT_VALUES[i]));
            assertThat(FixedWidthCodec.writeSignedDecimal(value, AMT_LENGTH, MONEY_SCALE))
                    .isEqualTo(field);
        }
    }

    @Test
    void writeSignedDecimalEncodesNegativeValueWithNegativeOverpunch() {
        // -919.00 -> last digit 0 with the negative overpunch '}'.
        assertThat(FixedWidthCodec.writeSignedDecimal(new BigDecimal("-919.00"), AMT_LENGTH, MONEY_SCALE))
                .isEqualTo("0000009190}")
                .hasSize(AMT_LENGTH);
    }

    @Test
    void writeSignedDecimalEncodesPositiveValueWithPositiveOverpunch() {
        // 504.77 -> last digit 7 with the positive overpunch 'G'.
        assertThat(FixedWidthCodec.writeSignedDecimal(new BigDecimal("504.77"), AMT_LENGTH, MONEY_SCALE))
                .isEqualTo("0000005047G")
                .hasSize(AMT_LENGTH);
    }

    @Test
    void writeSignedDecimalEncodesExactZeroWithPositiveZeroOverpunch() {
        // Exact zero is non-negative, so it uses the POSITIVE table -> trailing '{'.
        assertThat(FixedWidthCodec.writeSignedDecimal(new BigDecimal("0.00"), AMT_LENGTH, MONEY_SCALE))
                .isEqualTo("0000000000{")
                .hasSize(AMT_LENGTH);
    }

    @Test
    void writeSignedDecimalRejectsOverflow() {
        // 99999999999.99 scales to 13 digits, which does not fit an 11-character field.
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.writeSignedDecimal(
                        new BigDecimal("99999999999.99"), AMT_LENGTH, MONEY_SCALE));
    }

    // ========================================================================
    // Phase 4 - Alphanumeric (PIC X) read/write
    // ========================================================================

    @Test
    void writeAlphanumericLeftJustifiesAndSpacePadsOnTheRight() {
        // "POS TERM" is the SOURCE field of real seed record 1 (PIC X(10)).
        assertThat(FixedWidthCodec.writeAlphanumeric("POS TERM", 10))
                .isEqualTo("POS TERM  ")
                .hasSize(10);
    }

    @Test
    void readAlphanumericTrimmedStripsTrailingSpacesOnly() {
        // Trailing COBOL space padding is stripped; leading and embedded spaces are preserved.
        assertThat(FixedWidthCodec.readAlphanumericTrimmed("POS TERM  ", 0, 10)).isEqualTo("POS TERM");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed("  LEADING ", 0, 10)).isEqualTo("  LEADING");
    }

    @Test
    void readAlphanumericPreservesPaddingVerbatim() {
        // The raw reader returns the field exactly as stored, padding included.
        assertThat(FixedWidthCodec.readAlphanumeric("POS TERM  ", 0, 10))
                .isEqualTo("POS TERM  ")
                .hasSize(10);
    }

    @Test
    void writeAlphanumericTruncatesOnTheRightWhenOverWidth() {
        // Mirrors a COBOL MOVE of a longer value into a shorter PIC X item.
        assertThat(FixedWidthCodec.writeAlphanumeric("ABCDEFGHIJKL", 5)).isEqualTo("ABCDE");
    }

    @Test
    void writeAlphanumericTreatsNullAsAllSpaces() {
        assertThat(FixedWidthCodec.writeAlphanumeric(null, 4))
                .isEqualTo("    ")
                .hasSize(4);
    }

    // ------------------------------------------------------------------------
    // D36 - embedded record-delimiter (LF/CR) sanitization in alphanumeric fields
    // ------------------------------------------------------------------------

    @Test
    void writeAlphanumericReplacesEmbeddedLineFeedWithSpace() {
        // An embedded LF (0x0A) in field data must not survive to be confused with the physical
        // record-framing newline; it is replaced by a space, preserving the field width.
        String out = FixedWidthCodec.writeAlphanumeric("BAD\nDESC", 10);
        assertThat(out).isEqualTo("BAD DESC  ").hasSize(10);
        assertThat(out).doesNotContain("\n");
    }

    @Test
    void writeAlphanumericReplacesEmbeddedCarriageReturnWithSpace() {
        String out = FixedWidthCodec.writeAlphanumeric("A\rB", 5);
        assertThat(out).isEqualTo("A B  ").hasSize(5);
        assertThat(out).doesNotContain("\r");
    }

    @Test
    void writeAlphanumericReplacesCrLfPairWithTwoSpaces() {
        // Each control character maps 1:1 to a space, so a CRLF becomes two spaces (width preserved).
        String out = FixedWidthCodec.writeAlphanumeric("X\r\nY", 6);
        assertThat(out).isEqualTo("X  Y  ").hasSize(6);
        assertThat(out).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void writeAlphanumericSanitizesBeforeTruncationPreservingWidth() {
        // Sanitization happens before the over-width truncation, so the field is still exactly width.
        String out = FixedWidthCodec.writeAlphanumeric("AB\nCDEFGH", 4);
        assertThat(out).isEqualTo("AB C").hasSize(4);
        assertThat(out).doesNotContain("\n");
    }

    @Test
    void writeAlphanumericLeavesCleanDataByteForByteUnchanged() {
        // Clean data (no embedded delimiters) is returned untouched -> golden output is unaffected.
        assertThat(FixedWidthCodec.writeAlphanumeric("Abshire-Lowe", 20))
                .isEqualTo("Abshire-Lowe        ")
                .hasSize(20);
    }

    // ------------------------------------------------------------------------
    // F-P12 - non-single-byte characters are deterministically replaced so the
    // ISO-8859-1 record writers can never abort with an encoding exception that
    // would leave a truncated or zero-byte external file.
    // ------------------------------------------------------------------------

    @Test
    void writeAlphanumericReplacesCharacterAboveLatin1WithQuestionMark() {
        // A single BMP character above U+00FF (Greek capital omega) cannot be represented in the
        // single-byte PIC X output charset, so it is replaced 1:1 by '?', preserving the field width.
        String out = FixedWidthCodec.writeAlphanumeric("A\u03A9B", 6);
        assertThat(out).isEqualTo("A?B   ").hasSize(6);
        assertThat(out).doesNotContain("\u03A9");
    }

    @Test
    void writeAlphanumericReplacesSupplementaryEmojiSurrogatePairWithTwoQuestionMarks() {
        // A supplementary code point (U+1F600, a grinning-face emoji) is stored as a UTF-16 surrogate
        // pair, i.e. two code units. Each half is above U+00FF, so each is replaced by '?' -> the
        // two-unit emoji becomes "??" and the field width is still preserved exactly.
        String out = FixedWidthCodec.writeAlphanumeric("X\uD83D\uDE00Y", 8);
        assertThat(out).isEqualTo("X??Y    ").hasSize(8);
        // No half of the surrogate pair survives to reach the encoder.
        assertThat(out.chars()).allMatch(c -> c <= 0x00FF);
    }

    @Test
    void writeAlphanumericReplacesUnmappableCharacterBeforeTruncationPreservingWidth() {
        // Replacement happens before the over-width truncation and is strictly one-for-one, so a
        // supplementary character can never be split into a lone surrogate at the truncation boundary.
        // "AB<emoji>CDEF" (6 code units) sanitizes to "AB??CDEF" then truncates to width 4 -> "AB??".
        String out = FixedWidthCodec.writeAlphanumeric("AB\uD83D\uDE00CDEF", 4);
        assertThat(out).isEqualTo("AB??").hasSize(4);
        assertThat(out.chars()).allMatch(c -> c <= 0x00FF);
    }

    @Test
    void writeAlphanumericOutputIsAlwaysStrictlyIso88591Encodable() {
        // The whole point of F-P12: after sanitization the rendered field must always be encodable by
        // a strict ISO-8859-1 encoder, so the batch writers (which open a strict ISO-8859-1 stream)
        // can never raise MalformedInput/UnmappableCharacter and abort mid-write. Mix a BMP char
        // above Latin-1, a surrogate pair, and an embedded newline in one value.
        String out = FixedWidthCodec.writeAlphanumeric("caf\u00e9-\u03A9-\uD83D\uDE00-x\ny", 40);
        assertThat(out).hasSize(40);
        assertThat(StandardCharsets.ISO_8859_1.newEncoder().canEncode(out)).isTrue();
        // The Latin-1 'é' (U+00E9) is representable and must survive unchanged; only the truly
        // non-single-byte code units and the newline are rewritten.
        assertThat(out).startsWith("caf\u00e9-?-??-x y");
    }

    // ========================================================================
    // Phase 5 - Numeric (PIC 9) read/write
    // ========================================================================

    @Test
    void writeNumericRightJustifiesAndZeroPads() {
        assertThat(FixedWidthCodec.writeNumeric(1, 4))
                .isEqualTo("0001")
                .hasSize(4);
    }

    @Test
    void readNumericParsesLeadingZeros() {
        assertThat(FixedWidthCodec.readNumeric("0001", 0, 4)).isEqualTo(1L);
    }

    @Test
    void writeNumericRejectsOverflow() {
        assertThrows(IllegalArgumentException.class, () -> FixedWidthCodec.writeNumeric(12345, 4));
    }

    @Test
    void readNumericRejectsNonDigitCharacters() {
        assertThrows(IllegalArgumentException.class, () -> FixedWidthCodec.readNumeric("12A4", 0, 4));
    }

    // ========================================================================
    // Phase 6 - FULL DALYTRAN 350-byte record: parse all 14 fields + re-emit byte-identical
    // ========================================================================

    @Test
    void fullDalytranRecordAssemblesToExactlyThreeHundredFiftyBytes() {
        assertThat(canonicalDalytranRecord()).hasSize(DALYTRAN_LENGTH);
    }

    @Test
    void fullDalytranRecordParsesEveryFieldAtItsVerifiedOffset() {
        String record = canonicalDalytranRecord();

        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 0, 16)).isEqualTo("0000000000683580");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 16, 2)).isEqualTo("01");
        assertThat(FixedWidthCodec.readNumeric(record, 18, 4)).isEqualTo(1L);
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 22, 10)).isEqualTo("POS TERM");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 32, 100))
                .isEqualTo("Purchase at Abshire-Lowe");
        assertThat(FixedWidthCodec.readSignedDecimal(record, 132, AMT_LENGTH, MONEY_SCALE))
                .isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(FixedWidthCodec.readNumeric(record, 143, 9)).isEqualTo(800000000L);
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 152, 50)).isEqualTo("Abshire-Lowe");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 202, 50)).isEqualTo("North Enoshaven");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 252, 10)).isEqualTo("72112");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 262, 16)).isEqualTo("4859452612877065");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 278, 26))
                .isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 304, 26)).isEqualTo("");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record, 330, 20)).isEqualTo("");
    }

    @Test
    void fullDalytranRecordReEmitsByteForByteFromParsedFields() {
        String record = canonicalDalytranRecord();

        // Rebuild each field from the value parsed back out of the record, at its verified width.
        String reEmitted =
                FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 0, 16), 16)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 16, 2), 2)
                + FixedWidthCodec.writeNumeric(FixedWidthCodec.readNumeric(record, 18, 4), 4)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 22, 10), 10)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 32, 100), 100)
                + FixedWidthCodec.writeSignedDecimal(
                        FixedWidthCodec.readSignedDecimal(record, 132, AMT_LENGTH, MONEY_SCALE),
                        AMT_LENGTH, MONEY_SCALE)
                + FixedWidthCodec.writeNumeric(FixedWidthCodec.readNumeric(record, 143, 9), 9)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 152, 50), 50)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 202, 50), 50)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 252, 10), 10)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 262, 16), 16)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 278, 26), 26)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 304, 26), 26)
                + FixedWidthCodec.writeAlphanumeric(FixedWidthCodec.readAlphanumericTrimmed(record, 330, 20), 20);

        assertThat(reEmitted)
                .isEqualTo(record)
                .hasSize(DALYTRAN_LENGTH);
    }

    // ========================================================================
    // Phase 7 - DALYREJS 430-byte reject record trailer (AAP 0.7.1 H4)
    // ========================================================================

    @Test
    void dalyrejsRecordIsUntouchedTransactionImagePlusValidationTrailer() {
        String record = canonicalDalytranRecord();

        // CBTRN02C 2500-WRITE-REJECT-REC: untouched 350-byte image + 80-byte trailer
        // (WS-VALIDATION-FAIL-REASON PIC 9(04) + WS-VALIDATION-FAIL-REASON-DESC PIC X(76)).
        String reject = record
                + FixedWidthCodec.writeNumeric(102, 4)                            // reason 102: over credit limit
                + FixedWidthCodec.writeAlphanumeric("OVERLIMIT TRANSACTION", 76); // reason description

        assertThat(reject).hasSize(DALYREJS_LENGTH);
        assertThat(reject.substring(0, DALYTRAN_LENGTH)).isEqualTo(record);
        assertThat(FixedWidthCodec.readNumeric(reject, 350, 4)).isEqualTo(102L);
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(reject, 354, 76))
                .isEqualTo("OVERLIMIT TRANSACTION");
    }

    // ========================================================================
    // Descriptor-driven API coverage (FieldDef / FieldType / RecordBuilder / overloads).
    // These stay pure-logic (no file I/O) and reference the codec's nested types by qualified name.
    // ========================================================================

    @Test
    void fieldDefFactoriesProduceExpectedDescriptors() {
        FixedWidthCodec.FieldDef alpha = FixedWidthCodec.FieldDef.alphanumeric("SRC", 22, 10);
        assertThat(alpha.name()).isEqualTo("SRC");
        assertThat(alpha.offset()).isEqualTo(22);
        assertThat(alpha.length()).isEqualTo(10);
        assertThat(alpha.type()).isEqualTo(FixedWidthCodec.FieldType.ALPHANUMERIC);
        assertThat(alpha.endOffset()).isEqualTo(32);

        FixedWidthCodec.FieldDef numeric = FixedWidthCodec.FieldDef.numeric("CAT", 18, 4);
        assertThat(numeric.type()).isEqualTo(FixedWidthCodec.FieldType.NUMERIC);

        FixedWidthCodec.FieldDef amount = FixedWidthCodec.FieldDef.signedDecimal("AMT", 132, 11, 2);
        assertThat(amount.type()).isEqualTo(FixedWidthCodec.FieldType.SIGNED_DECIMAL);
        assertThat(amount.scale()).isEqualTo(2);
        assertThat(amount.endOffset()).isEqualTo(143);
    }

    @Test
    void fieldDefConstructorRejectsInvalidInvariants() {
        assertThrows(NullPointerException.class,
                () -> new FixedWidthCodec.FieldDef(null, 0, 4, FixedWidthCodec.FieldType.NUMERIC, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedWidthCodec.FieldDef("neg-offset", -1, 4, FixedWidthCodec.FieldType.NUMERIC, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedWidthCodec.FieldDef("zero-length", 0, 0, FixedWidthCodec.FieldType.NUMERIC, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedWidthCodec.FieldDef("neg-scale", 0, 4, FixedWidthCodec.FieldType.NUMERIC, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedWidthCodec.FieldDef("scale-too-big", 0, 2,
                        FixedWidthCodec.FieldType.SIGNED_DECIMAL, 2));
    }

    @Test
    void descriptorReadOverloadsDecodeEveryFieldType() {
        String record = canonicalDalytranRecord();

        assertThat(FixedWidthCodec.readAlphanumeric(record,
                FixedWidthCodec.FieldDef.alphanumeric("ID", 0, 16))).isEqualTo("0000000000683580");
        assertThat(FixedWidthCodec.readAlphanumericTrimmed(record,
                FixedWidthCodec.FieldDef.alphanumeric("SRC", 22, 10))).isEqualTo("POS TERM");
        assertThat(FixedWidthCodec.readNumeric(record,
                FixedWidthCodec.FieldDef.numeric("CAT", 18, 4))).isEqualTo(1L);
        assertThat(FixedWidthCodec.readSignedDecimal(record,
                FixedWidthCodec.FieldDef.signedDecimal("AMT", 132, 11, 2)))
                .isEqualByComparingTo(new BigDecimal("504.77"));
    }

    @Test
    void descriptorWriteOverloadsRenderExactWidths() {
        assertThat(FixedWidthCodec.writeAlphanumeric("POS TERM",
                FixedWidthCodec.FieldDef.alphanumeric("SRC", 0, 10))).isEqualTo("POS TERM  ");
        assertThat(FixedWidthCodec.writeNumeric(1L,
                FixedWidthCodec.FieldDef.numeric("CAT", 0, 4))).isEqualTo("0001");
        assertThat(FixedWidthCodec.writeSignedDecimal(new BigDecimal("504.77"),
                FixedWidthCodec.FieldDef.signedDecimal("AMT", 0, 11, 2))).isEqualTo("0000005047G");
    }

    @Test
    void descriptorOverloadsRejectFieldTypeMismatch() {
        // Reading a numeric descriptor via the alphanumeric overload is a programming error.
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readAlphanumeric("0001", FixedWidthCodec.FieldDef.numeric("N", 0, 4)));
    }

    @Test
    void readNumericIntParsesValueAndRejectsIntOverflow() {
        assertThat(FixedWidthCodec.readNumericInt("0001", 0, 4)).isEqualTo(1);
        // 9999999999 (10 digits) exceeds Integer.MAX_VALUE and must be rejected.
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readNumericInt("9999999999", 0, 10));
    }

    @Test
    void recordBuilderPlacesFieldsAtDeclaredOffsets() {
        FixedWidthCodec.FieldDef id = FixedWidthCodec.FieldDef.alphanumeric("ID", 0, 4);
        FixedWidthCodec.FieldDef cat = FixedWidthCodec.FieldDef.numeric("CAT", 4, 4);
        FixedWidthCodec.FieldDef amt = FixedWidthCodec.FieldDef.signedDecimal("AMT", 8, 11, 2);

        String record = FixedWidthCodec.of(19)
                .put(id, "AB")
                .put(cat, 7L)
                .put(amt, new BigDecimal("504.77"))
                .build();

        assertThat(record).hasSize(19);
        assertThat(record.substring(0, 4)).isEqualTo("AB  ");
        assertThat(record.substring(4, 8)).isEqualTo("0007");
        assertThat(record.substring(8, 19)).isEqualTo("0000005047G");
    }

    @Test
    void recordBuilderPutRawPlacesSegmentVerbatim() {
        // Canonical DALYREJS assembly path: an untouched image at offset 0 + an 80-byte trailer.
        String image = "X".repeat(DALYTRAN_LENGTH);
        String trailer = FixedWidthCodec.writeNumeric(103, 4)
                + FixedWidthCodec.writeAlphanumeric("TRANSACTION AFTER EXPIRATION", 76);

        String reject = FixedWidthCodec.of(DALYREJS_LENGTH)
                .putRaw(0, image)
                .putRaw(DALYTRAN_LENGTH, trailer)
                .build();

        assertThat(reject).hasSize(DALYREJS_LENGTH);
        assertThat(reject.substring(0, DALYTRAN_LENGTH)).isEqualTo(image);
        assertThat(FixedWidthCodec.readNumeric(reject, 350, 4)).isEqualTo(103L);
    }

    @Test
    void recordBuilderRejectsOutOfRangePlacement() {
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.of(4).putRaw(2, "ABCDE"));
    }

    @Test
    void recordBuilderRejectsFieldTypeMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.of(4).put(FixedWidthCodec.FieldDef.numeric("N", 0, 4), "abc"));
    }

    // ========================================================================
    // Bounds, scale, sign, and length validation (robustness of the class under test)
    // ========================================================================

    @Test
    void readSignedDecimalTreatsPlainTrailingDigitAsPositive() {
        // A plain digit in the last position (no overpunch applied) denotes a positive value.
        assertThat(FixedWidthCodec.readSignedDecimal("00000050477", 0, AMT_LENGTH, MONEY_SCALE))
                .isEqualByComparingTo(new BigDecimal("504.77"));
    }

    @Test
    void readSignedDecimalRejectsScaleNotLessThanLength() {
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readSignedDecimal("0000000000G", 0, AMT_LENGTH, AMT_LENGTH));
    }

    @Test
    void writeSignedDecimalRejectsScaleNotLessThanLength() {
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.writeSignedDecimal(new BigDecimal("1.00"), 4, 4));
    }

    @Test
    void writeNumericRejectsNegativeValue() {
        assertThrows(IllegalArgumentException.class, () -> FixedWidthCodec.writeNumeric(-1, 4));
    }

    @Test
    void readRejectsNegativeOffset() {
        assertThrows(IllegalArgumentException.class, () -> FixedWidthCodec.readAlphanumeric("ABC", -1, 2));
    }

    @Test
    void readRejectsNonPositiveLength() {
        assertThrows(IllegalArgumentException.class, () -> FixedWidthCodec.readAlphanumeric("ABC", 0, 0));
    }

    @Test
    void readRejectsRangeBeyondRecord() {
        assertThrows(IllegalArgumentException.class, () -> FixedWidthCodec.readAlphanumeric("ABC", 2, 5));
    }

    @Test
    void readNumericRejectsValueBeyondLongRange() {
        // 19 nines exceed Long.MAX_VALUE; the digits are valid but the parse must fail cleanly.
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readNumeric("9".repeat(19), 0, 19));
    }

    @Test
    void writeAlphanumericRejectsNonPositiveLength() {
        assertThrows(IllegalArgumentException.class, () -> FixedWidthCodec.writeAlphanumeric("x", 0));
    }

    @Test
    void writeAlphanumericReturnsValueUnchangedWhenExactlyFieldWidth() {
        // Exact-width input is neither truncated nor padded.
        assertThat(FixedWidthCodec.writeAlphanumeric("ABCD", 4)).isEqualTo("ABCD");
    }

    @Test
    void recordBuilderRejectsNonPositiveRecordLength() {
        assertThrows(IllegalArgumentException.class, () -> FixedWidthCodec.of(0));
    }

    @Test
    void recordBuilderPutRawRejectsNegativeOffset() {
        assertThrows(IllegalArgumentException.class, () -> FixedWidthCodec.of(4).putRaw(-1, "x"));
    }

    // ------------------------------------------------------------------------
    // readFixedLengthRecords - RECFM=F(B) framing by position (F3 parity fix)
    // ------------------------------------------------------------------------

    @Test
    void readFixedLengthRecordsSplitsContiguousStreamWithNoDelimiters() {
        // A true fixed-block file: back-to-back records, no line terminator at all. The pre-fix
        // line-oriented read returned this as a single over-length "line" and dropped record 2.
        assertThat(FixedWidthCodec.readFixedLengthRecords("AAAAABBBBB", 5))
                .containsExactly("AAAAA", "BBBBB");
    }

    @Test
    void readFixedLengthRecordsSkipsLfFraming() {
        // One record per line, LF-terminated (the shipped ASCII fixture form) decodes identically.
        assertThat(FixedWidthCodec.readFixedLengthRecords("AAAAA\nBBBBB\n", 5))
                .containsExactly("AAAAA", "BBBBB");
    }

    @Test
    void readFixedLengthRecordsSkipsCrlfFraming() {
        assertThat(FixedWidthCodec.readFixedLengthRecords("AAAAA\r\nBBBBB\r\n", 5))
                .containsExactly("AAAAA", "BBBBB");
    }

    @Test
    void readFixedLengthRecordsHandlesMissingTrailingNewline() {
        assertThat(FixedWidthCodec.readFixedLengthRecords("AAAAA\nBBBBB", 5))
                .containsExactly("AAAAA", "BBBBB");
    }

    @Test
    void readFixedLengthRecordsReturnsSingleRecordForExactLength() {
        assertThat(FixedWidthCodec.readFixedLengthRecords("AAAAA", 5))
                .containsExactly("AAAAA");
    }

    @Test
    void readFixedLengthRecordsReturnsEmptyForEmptyContent() {
        assertThat(FixedWidthCodec.readFixedLengthRecords("", 5)).isEmpty();
    }

    @Test
    void readFixedLengthRecordsReturnsEmptyForFramingOnlyContent() {
        // Only line terminators: every character is inter-record framing, so no records are produced.
        assertThat(FixedWidthCodec.readFixedLengthRecords("\n\r\n\n", 5)).isEmpty();
    }

    @Test
    void readFixedLengthRecordsToleratesBlankTrailingRemainder() {
        // A short trailing remainder that is entirely blank (trailing pad) is discarded, not an error.
        assertThat(FixedWidthCodec.readFixedLengthRecords("AAAAA  ", 5))
                .containsExactly("AAAAA");
        assertThat(FixedWidthCodec.readFixedLengthRecords("AAAAA\n  \n", 5))
                .containsExactly("AAAAA");
    }

    @Test
    void readFixedLengthRecordsThrowsOnNonBlankShortRemainder() {
        // A truncated (short, non-blank) trailing remainder must fail fast rather than load a
        // corrupt record -- the parity requirement behind the F3 fix.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readFixedLengthRecords("AAAAABB", 5));
        assertThat(ex.getMessage()).contains("Trailing partial fixed-length record");
    }

    @Test
    void readFixedLengthRecordsThrowsOnSingleShortRecord() {
        // A 300-character single record when the width is 350 (the QA-reported case) is rejected.
        String threeHundred = "9".repeat(300);
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readFixedLengthRecords(threeHundred, 350));
    }

    @Test
    void readFixedLengthRecordsPreservesRecordBytesVerbatim() {
        // Slices are taken verbatim: interior spaces are NOT trimmed (the field readers trim, not
        // the framer). The record retains its full width exactly.
        List<String> records = FixedWidthCodec.readFixedLengthRecords("AB CDE F  ", 5);
        assertThat(records).containsExactly("AB CD", "E F  ");
        assertThat(records.get(0)).hasSize(5);
        assertThat(records.get(1)).hasSize(5);
    }

    @Test
    void readFixedLengthRecordsRejectsNullContent() {
        assertThrows(NullPointerException.class,
                () -> FixedWidthCodec.readFixedLengthRecords(null, 5));
    }

    @Test
    void readFixedLengthRecordsRejectsNonPositiveLength() {
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readFixedLengthRecords("AAAAA", 0));
        assertThrows(IllegalArgumentException.class,
                () -> FixedWidthCodec.readFixedLengthRecords("AAAAA", -1));
    }

    @Test
    void readFixedLengthRecordsFramesTwoCanonical350ByteRecords() {
        // Two real 350-byte DALYTRAN images concatenated with NO delimiter frame into exactly two
        // records, each byte-identical to the canonical image (contiguous fixed-block parity).
        String canonical = canonicalDalytranRecord();
        assertThat(canonical).hasSize(350);

        List<String> contiguous = FixedWidthCodec.readFixedLengthRecords(canonical + canonical, 350);
        assertThat(contiguous).containsExactly(canonical, canonical);

        // The LF-framed form of the same two records decodes identically.
        List<String> lfFramed =
                FixedWidthCodec.readFixedLengthRecords(canonical + "\n" + canonical + "\n", 350);
        assertThat(lfFramed).containsExactly(canonical, canonical);
    }

    // ------------------------------------------------------------------------
    // Helpers (pure in-memory assembly - no file I/O)
    // ------------------------------------------------------------------------

    /**
     * Assembles the canonical 350-byte {@code DALYTRAN} record from the verified real-seed record-1
     * field values by concatenating the 14 contiguous field writes in order. Because the layout has
     * no gaps, ordered concatenation reproduces the on-file record exactly (verified byte-identical
     * against {@code legacy/data/ASCII/dailytran.txt} record 1). This keeps the tests headless: the
     * record is built in memory, never read from disk.
     *
     * @return the 350-character canonical DALYTRAN record
     */
    private static String canonicalDalytranRecord() {
        return FixedWidthCodec.writeAlphanumeric("0000000000683580", 16)          // 1  DALYTRAN-ID
                + FixedWidthCodec.writeAlphanumeric("01", 2)                        // 2  TYPE-CD
                + FixedWidthCodec.writeNumeric(1, 4)                               // 3  CAT-CD -> "0001"
                + FixedWidthCodec.writeAlphanumeric("POS TERM", 10)                // 4  SOURCE -> "POS TERM  "
                + FixedWidthCodec.writeAlphanumeric("Purchase at Abshire-Lowe", 100) // 5  DESC
                + FixedWidthCodec.writeSignedDecimal(new BigDecimal("504.77"), 11, 2) // 6  AMT -> "0000005047G"
                + FixedWidthCodec.writeNumeric(800000000L, 9)                     // 7  MERCHANT-ID
                + FixedWidthCodec.writeAlphanumeric("Abshire-Lowe", 50)            // 8  MERCHANT-NAME
                + FixedWidthCodec.writeAlphanumeric("North Enoshaven", 50)         // 9  MERCHANT-CITY
                + FixedWidthCodec.writeAlphanumeric("72112", 10)                   // 10 MERCHANT-ZIP
                + FixedWidthCodec.writeAlphanumeric("4859452612877065", 16)        // 11 CARD-NUM (demo PAN)
                + FixedWidthCodec.writeAlphanumeric("2022-06-10 19:27:53.000000", 26) // 12 ORIG-TS
                + FixedWidthCodec.writeAlphanumeric("", 26)                        // 13 PROC-TS (blank)
                + FixedWidthCodec.writeAlphanumeric("", 20);                       // 14 FILLER (blank)
    }
}
