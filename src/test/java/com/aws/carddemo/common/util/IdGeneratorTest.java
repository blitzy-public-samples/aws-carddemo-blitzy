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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Fast, isolated, pure-logic unit tests for {@link IdGenerator}, asserting
 * behavioral parity with the COBOL transaction-id generation of
 * {@code legacy/cbl/COTRN02C.cbl} (paragraph {@code ADD-TRANSACTION},
 * {@code app/cbl/COTRN02C.cbl} lines 444&ndash;451).
 *
 * <p>The legacy program derives the next transaction id with a
 * <em>reverse browse from {@code HIGH-VALUES}</em>: it reads the greatest existing
 * key, copies it into {@code WS-TRAN-ID-N} ({@code PIC 9(16)}), performs
 * {@code ADD 1}, and stores the result back into {@code TRAN-ID}
 * ({@code PIC X(16)}) &mdash; a 16-character, zero-padded numeric key. When the
 * {@code TRANSACT} file is empty the {@code READPREV} {@code ENDFILE} path runs
 * {@code MOVE ZEROS TO TRAN-ID} (lines 687&ndash;689), so the "current maximum" is
 * treated as {@code 0} and the first generated id becomes
 * {@code "0000000000000001"}.</p>
 *
 * <p>These tests lock down the migration parity contract (AAP &sect;0.7.1 H5,
 * &sect;0.9.2, &sect;0.9.6): empty-table / sentinel handling, the increment step,
 * the fixed 16-digit zero-padded canonical form, and the input-validation and
 * id-space-exhaustion boundaries. The suite is headless and reproducible &mdash;
 * it invokes the {@code static} methods of {@link IdGenerator} directly with no
 * Spring context, database, or Testcontainers &mdash; and contributes to the
 * project's line-coverage gate.</p>
 *
 * <p>{@link IdGenerator} resides in this same package, so it is referenced
 * directly without an import.</p>
 */
class IdGeneratorTest {

    /**
     * The canonical first transaction id: the result whenever the transaction
     * table is empty (current maximum {@code 0}), matching the COBOL
     * {@code ENDFILE}&rarr;{@code ZEROS}&rarr;{@code ADD 1} sequence.
     */
    private static final String FIRST_ID = "0000000000000001";

    // ------------------------------------------------------------------------
    // Phase 2 — Empty-table / ENDFILE sentinel cases (all -> "0000000000000001")
    // ------------------------------------------------------------------------

    @Test
    void nextTransactionId_emptyTableAsZeroMax_yieldsFirstId() {
        // COBOL: READPREV ENDFILE -> MOVE ZEROS TO TRAN-ID -> ADD 1 => id 1.
        assertThat(IdGenerator.nextTransactionId(0L)).isEqualTo(FIRST_ID);
    }

    @Test
    void nextTransactionId_nullString_yieldsFirstId() {
        // Cast null to String to select the String overload (long cannot be null).
        assertThat(IdGenerator.nextTransactionId((String) null)).isEqualTo(FIRST_ID);
    }

    @Test
    void nextTransactionId_blankString_yieldsFirstId() {
        // A fixed-width PIC X(16) column that held no rows is space-padded / empty.
        assertThat(IdGenerator.nextTransactionId("   ")).isEqualTo(FIRST_ID);
        assertThat(IdGenerator.nextTransactionId("")).isEqualTo(FIRST_ID);
    }

    @Test
    void nextTransactionId_allZerosSentinel_yieldsFirstId() {
        // All-zeros parses to 0, reproducing the empty-table maximum.
        assertThat(IdGenerator.nextTransactionId("0000000000000000")).isEqualTo(FIRST_ID);
    }

    // ------------------------------------------------------------------------
    // Phase 3 — Increment + zero-padding parity cases
    // ------------------------------------------------------------------------

    @Test
    void nextTransactionId_numericMaximum_incrementsByOne() {
        // 311 + 1 = 312, zero-padded to 16 digits.
        assertThat(IdGenerator.nextTransactionId(311L)).isEqualTo("0000000000000312");
    }

    @Test
    void nextTransactionId_firstSeedTransactionId_incrementsByOne() {
        // 683580 is the first seeded transaction id; the next generated id is 683581.
        assertThat(IdGenerator.nextTransactionId(683580L)).isEqualTo("0000000000683581");
    }

    @Test
    void nextTransactionId_stringOverload_preservesZeroPadding() {
        // 9 -> 10; the 16-character width is preserved (leading zeros retained).
        assertThat(IdGenerator.nextTransactionId("0000000000000009")).isEqualTo("0000000000000010");
    }

    @Test
    void nextTransactionId_stringOverload_parsesNonPaddedNumeric() {
        // A non-zero-padded numeric string is parsed and incremented identically.
        assertThat(IdGenerator.nextTransactionId("311")).isEqualTo("0000000000000312");
    }

    @Test
    void nextTransactionId_stringOverload_trimsSurroundingWhitespace() {
        // Surrounding whitespace is stripped before parsing: " 9 " -> 10.
        assertThat(IdGenerator.nextTransactionId("  0000000000000009  ")).isEqualTo("0000000000000010");
    }

    // ------------------------------------------------------------------------
    // Phase 4 — Invariants: always 16 characters, all-numeric
    // ------------------------------------------------------------------------

    @Test
    void nextTransactionId_result_isAlwaysSixteenAsciiDigits() {
        assertCanonicalTransactionId(IdGenerator.nextTransactionId(0L));
        assertCanonicalTransactionId(IdGenerator.nextTransactionId(311L));
        assertCanonicalTransactionId(IdGenerator.nextTransactionId(683580L));
        assertCanonicalTransactionId(IdGenerator.nextTransactionId("0000000000000009"));
    }

    @Test
    void nextTransactionId_result_matchesSixteenDigitPattern() {
        // Belt-and-braces regex form of the canonical-shape invariant.
        assertThat(IdGenerator.nextTransactionId(683580L)).matches("\\d{16}");
    }

    @Test
    void tranIdLength_constant_isSixteen() {
        // Matches COBOL TRAN-ID PIC X(16) / WS-TRAN-ID-N PIC 9(16).
        assertThat(IdGenerator.TRAN_ID_LENGTH).isEqualTo(16);
    }

    // ------------------------------------------------------------------------
    // Phase 5 — Error / boundary conditions
    // ------------------------------------------------------------------------

    @Test
    void nextTransactionId_negativeMaximum_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> IdGenerator.nextTransactionId(-1L));
    }

    @Test
    void nextTransactionId_nonNumericString_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> IdGenerator.nextTransactionId("12A4"));
        // A leading sign is a non-digit character and is likewise rejected.
        assertThrows(IllegalArgumentException.class, () -> IdGenerator.nextTransactionId("-5"));
    }

    @Test
    void nextTransactionId_overlongString_throwsIllegalArgument() {
        // 17 characters cannot fit a 16-digit key.
        assertThrows(IllegalArgumentException.class,
                () -> IdGenerator.nextTransactionId("00000000000000001"));
    }

    @Test
    void nextTransactionId_exhaustedIdSpace_throwsIllegalState() {
        // A 17th digit would be required to represent the successor of sixteen nines.
        assertThrows(IllegalStateException.class,
                () -> IdGenerator.nextTransactionId(9_999_999_999_999_999L));
        assertThrows(IllegalStateException.class,
                () -> IdGenerator.nextTransactionId("9999999999999999"));
    }

    @Test
    void nextTransactionId_largestValidMaximum_yieldsSixteenNines() {
        // The greatest maximum that still has a 16-digit successor.
        assertThat(IdGenerator.nextTransactionId(9_999_999_999_999_998L)).isEqualTo("9999999999999999");
    }

    // ------------------------------------------------------------------------
    // format(long) — the public formatting primitive underpinning the ids
    //   (COBOL MOVE WS-TRAN-ID-N TO TRAN-ID, PIC 9(16) -> PIC X(16)).
    // ------------------------------------------------------------------------

    @Test
    void format_numericId_zeroPadsToSixteenDigits() {
        assertThat(IdGenerator.format(0L)).isEqualTo("0000000000000000");
        assertThat(IdGenerator.format(1L)).isEqualTo("0000000000000001");
        assertThat(IdGenerator.format(683580L)).isEqualTo("0000000000683580");
        assertThat(IdGenerator.format(9_999_999_999_999_999L)).isEqualTo("9999999999999999");
    }

    @Test
    void format_result_isAlwaysSixteenAsciiDigits() {
        assertCanonicalTransactionId(IdGenerator.format(0L));
        assertCanonicalTransactionId(IdGenerator.format(683580L));
    }

    @Test
    void format_negativeId_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> IdGenerator.format(-1L));
    }

    @Test
    void format_idTooWideForSixteenDigits_throwsIllegalArgument() {
        // 10_000_000_000_000_000 (1e16) needs 17 digits.
        assertThrows(IllegalArgumentException.class,
                () -> IdGenerator.format(10_000_000_000_000_000L));
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Asserts the canonical transaction-id shape: exactly
     * {@link IdGenerator#TRAN_ID_LENGTH} characters, all ASCII digits.
     *
     * @param id the generated transaction id to check
     */
    private static void assertCanonicalTransactionId(String id) {
        assertThat(id).hasSize(IdGenerator.TRAN_ID_LENGTH);
        assertThat(id).containsOnlyDigits();
    }
}
