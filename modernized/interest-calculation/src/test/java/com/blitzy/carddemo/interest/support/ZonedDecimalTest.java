/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.interest.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Characterization unit tests for {@link ZonedDecimal} &mdash; business rule <strong>BR-18</strong>
 * (overpunch zoned-decimal sign + implied-decimal {@code V99}); AAP &sect;0.6.2, &sect;0.6.3.
 *
 * <p>These tests lock the <em>observable contract</em> of the production codec; they neither redefine
 * nor "improve" it (AAP &sect;0.7 minimal-change rule). Correctness of the whole migration is
 * established through this suite plus static reasoning, never against a live system (AAP &sect;0.7).
 * The suite is fully self-contained: every vector is an in-line literal, so {@code mvn test} runs
 * with zero external resources (no file I/O, fixtures, or network).</p>
 *
 * <h2>What is under test (do NOT redefine here)</h2>
 * <p>COBOL {@code USAGE DISPLAY} zoned-decimal numeric fields carry the sign as an <strong>overpunch
 * on the trailing byte</strong> and the {@code V99} is an <strong>implied (unstored) decimal
 * point</strong>. The migrated program {@code app/cbl/CBACT04C.cbl} and its five copybooks were
 * verified to contain <strong>no COMP-3 and no REDEFINES</strong> anywhere &mdash; every numeric is
 * zoned DISPLAY with an overpunch sign plus an implied {@code V99} decimal. {@link ZonedDecimal}
 * converts these fixed-width field strings &harr; {@link BigDecimal} at <strong>scale 2</strong>.</p>
 *
 * <h2>Source lineage (READ-ONLY REFERENCE; additive migration, never modified)</h2>
 * <ul>
 *   <li>{@code TRAN-CAT-BAL  PIC S9(09)V99} &rarr; 11 bytes ({@code app/cpy/CVTRA01Y.cpy:L9})</li>
 *   <li>{@code DIS-INT-RATE  PIC S9(04)V99} &rarr; 6 bytes  ({@code app/cpy/CVTRA02Y.cpy:L9})</li>
 *   <li>{@code ACCT-CURR-BAL} / {@code ACCT-CREDIT-LIMIT} / {@code ACCT-CASH-CREDIT-LIMIT} /
 *       {@code ACCT-CURR-CYC-CREDIT} / {@code ACCT-CURR-CYC-DEBIT  PIC S9(10)V99} &rarr; 12 bytes
 *       each ({@code app/cpy/CVACT01Y.cpy:L7-14})</li>
 *   <li>{@code TRAN-AMT      PIC S9(09)V99} &rarr; 11 bytes ({@code app/cpy/CVTRA05Y.cpy:L10})</li>
 * </ul>
 *
 * <h2>Fixture overpunch evidence ({@code app/data/ASCII/discgrp.txt})</h2>
 * <p>The {@code DIS-INT-RATE} field (cols 17-22) reads {@code 00150{}&rarr;{@code 15.00},
 * {@code 00250{}&rarr;{@code 25.00}, {@code 00000{}&rarr;{@code 0.00}. Every fixture trailing byte is
 * {@code '{'} because the trailing digit in that data is always {@code 0}.</p>
 *
 * <h2>Overpunch table asserted by this suite (exact)</h2>
 * <pre>
 * Positive:  '{' = +0,  'A'=+1 'B'=+2 'C'=+3 'D'=+4 'E'=+5 'F'=+6 'G'=+7 'H'=+8 'I'=+9
 * Negative:  '}' = -0,  'J'=-1 'K'=-2 'L'=-3 'M'=-4 'N'=-5 'O'=-6 'P'=-7 'Q'=-8 'R'=-9
 * Defensive: a plain trailing '0'..'9' is treated as POSITIVE (un-overpunched digit).
 * </pre>
 *
 * <p><strong>{@link BigDecimal} equality note:</strong> {@link BigDecimal#equals(Object)} is
 * scale-sensitive, so {@code assertEquals(new BigDecimal("15.00"), actual)} verifies BOTH the numeric
 * value AND that the scale is 2. Expected literals are always written with two decimals, and each
 * decode result additionally asserts {@code scale() == 2} to lock the implied-{@code V99}
 * realization. Comparisons never use {@code ==} on {@code BigDecimal} references.</p>
 *
 * @see ZonedDecimal
 */
public class ZonedDecimalTest {

    // ---------------------------------------------------------------------------------------------
    // Phase 3 -- decode() happy path (fixture-derived, multi-width). Verified vectors, scale-2 locked.
    // ---------------------------------------------------------------------------------------------

    /**
     * BR-18: {@code decode} of the canonical {@code DIS-INT-RATE}/{@code TRAN-CAT-BAL}/account-money
     * fixtures yields the exact scale-2 {@link BigDecimal}. Raw strings and expected values are the
     * real {@code app/data/ASCII} bytes and copybook widths (6 / 11 / 12), asserted verbatim.
     */
    @Test
    @DisplayName("BR-18 decode: fixture rates + multi-width zero + 194.00 -> exact scale-2 BigDecimal")
    void decodeHappyPathVerifiedVectors() {
        // DIS-INT-RATE S9(04)V99, 6 bytes -- app/data/ASCII/discgrp.txt cols 17-22.
        BigDecimal rate15 = ZonedDecimal.decode("00150{", 2);
        assertEquals(new BigDecimal("15.00"), rate15);
        assertEquals(2, rate15.scale());

        BigDecimal rate25 = ZonedDecimal.decode("00250{", 2);
        assertEquals(new BigDecimal("25.00"), rate25);
        assertEquals(2, rate25.scale());

        // Zero-rate fixture (ZEROAPR group rows in discgrp.txt).
        BigDecimal rate0 = ZonedDecimal.decode("00000{", 2);
        assertEquals(new BigDecimal("0.00"), rate0);
        assertEquals(2, rate0.scale());

        // TRAN-CAT-BAL S9(09)V99, 11 bytes -- CVTRA01Y.cpy:L9; tcatbal.txt zero balance.
        BigDecimal tranCatBalZero = ZonedDecimal.decode("0000000000{", 2);
        assertEquals(new BigDecimal("0.00"), tranCatBalZero);
        assertEquals(2, tranCatBalZero.scale());

        // ACCT money S9(10)V99, 12 bytes -- CVACT01Y.cpy:L7; acctdata.txt ACCT-CURR-BAL 194.00.
        // Confirms the codec handles all three field widths uniformly.
        BigDecimal acct194 = ZonedDecimal.decode("00000001940{", 2);
        assertEquals(new BigDecimal("194.00"), acct194);
        assertEquals(2, acct194.scale());
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4 -- full overpunch table (positive, negative, zero) + multi-byte sign + defensive digit.
    // ---------------------------------------------------------------------------------------------

    /**
     * BR-18: positive overpunch trailing bytes {@code '{'} and {@code 'A'..'I'} decode to
     * {@code +0.00 .. +0.09}. The 5 leading bytes are held at {@code "00000"} so only the
     * sign+last-digit byte varies. Single-quoted CSV keeps {@code '{'} an ordinary token.
     */
    @ParameterizedTest
    @DisplayName("BR-18 decode overpunch positive: '{' A..I -> 0.00..0.09")
    @CsvSource({
        "'00000{', 0.00",
        "'00000A', 0.01",
        "'00000B', 0.02",
        "'00000C', 0.03",
        "'00000D', 0.04",
        "'00000E', 0.05",
        "'00000F', 0.06",
        "'00000G', 0.07",
        "'00000H', 0.08",
        "'00000I', 0.09"
    })
    void decodeOverpunchPositive(String raw, String expected) {
        BigDecimal actual = ZonedDecimal.decode(raw, 2);
        assertEquals(new BigDecimal(expected), actual);
        assertEquals(2, actual.scale());
    }

    /**
     * BR-18: negative overpunch trailing bytes {@code '}'} and {@code 'J'..'R'} decode to
     * {@code -0.00 .. -0.09}. Negative zero ({@code '}'}) is numerically {@code 0.00}.
     */
    @ParameterizedTest
    @DisplayName("BR-18 decode overpunch negative: '}' J..R -> -0.00(=0.00)..-0.09")
    @CsvSource({
        "'00000}', 0.00",
        "'00000J', -0.01",
        "'00000K', -0.02",
        "'00000L', -0.03",
        "'00000M', -0.04",
        "'00000N', -0.05",
        "'00000O', -0.06",
        "'00000P', -0.07",
        "'00000Q', -0.08",
        "'00000R', -0.09"
    })
    void decodeOverpunchNegative(String raw, String expected) {
        BigDecimal actual = ZonedDecimal.decode(raw, 2);
        assertEquals(new BigDecimal(expected), actual);
        assertEquals(2, actual.scale());
    }

    /**
     * BR-18: the overpunch sign is decoded from the <em>trailing</em> byte of a multi-digit field
     * (E=+5, N=-5), and a plain un-overpunched trailing digit is defensively treated as POSITIVE.
     */
    @Test
    @DisplayName("BR-18 decode: multi-byte sign (E=+5, N=-5) + defensive plain-digit '5' -> +0.05")
    void decodeMultiByteSignAndDefensiveDigit() {
        BigDecimal positive = ZonedDecimal.decode("00150E", 2);
        assertEquals(new BigDecimal("15.05"), positive);
        assertEquals(2, positive.scale());

        BigDecimal negative = ZonedDecimal.decode("00150N", 2);
        assertEquals(new BigDecimal("-15.05"), negative);
        assertEquals(2, negative.scale());

        // Defensive branch: an un-overpunched trailing '5' is read as POSITIVE (+0.05).
        BigDecimal defensivePlainDigit = ZonedDecimal.decode("000005", 2);
        assertEquals(new BigDecimal("0.05"), defensivePlainDigit);
        assertEquals(2, defensivePlainDigit.scale());
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 5 -- encode() verified vectors + length()==totalDigits (exact inverse of decode).
    // ---------------------------------------------------------------------------------------------

    /**
     * BR-18: {@code encode} is the exact inverse of {@code decode}. Verified vectors cover fixture
     * identity, zero and negative-zero canonicalization to {@code '{'}, positive/negative non-zero
     * trailing-digit folding to letters, multi-byte sign, and the 12-byte account-money width.
     */
    @Test
    @DisplayName("BR-18 encode: BigDecimal -> fixed-width zoned field (exact inverse of decode)")
    void encodeVerifiedVectors() {
        // Byte-identical to the discgrp.txt fixture.
        assertEquals("00150{", ZonedDecimal.encode(new BigDecimal("15.00"), 6, 2));
        // Zero encodes with a trailing '{' (zero is treated as positive).
        assertEquals("00000{", ZonedDecimal.encode(new BigDecimal("0.00"), 6, 2));
        // Negative-zero canonicalizes to positive '{' (see Phase 6 caveat).
        assertEquals("00000{", ZonedDecimal.encode(new BigDecimal("-0.00"), 6, 2));
        // Positive non-zero trailing digit -> letter (1 -> A, 9 -> I).
        assertEquals("00000A", ZonedDecimal.encode(new BigDecimal("0.01"), 6, 2));
        assertEquals("00000I", ZonedDecimal.encode(new BigDecimal("0.09"), 6, 2));
        // Negative non-zero trailing digit -> letter (1 -> J, 9 -> R).
        assertEquals("00000J", ZonedDecimal.encode(new BigDecimal("-0.01"), 6, 2));
        assertEquals("00000R", ZonedDecimal.encode(new BigDecimal("-0.09"), 6, 2));
        // Multi-byte sign folding (E = +5, N = -5).
        assertEquals("00150E", ZonedDecimal.encode(new BigDecimal("15.05"), 6, 2));
        assertEquals("00150N", ZonedDecimal.encode(new BigDecimal("-15.05"), 6, 2));
        // 12-byte account-money width.
        assertEquals("00000001940{", ZonedDecimal.encode(new BigDecimal("194.00"), 12, 2));
    }

    /**
     * BR-18: the encoded field width always equals {@code totalDigits} (6 rate, 11 txn-amt /
     * tran-cat-bal, 12 account money) &mdash; a precondition for the byte-exact 300-byte account
     * REWRITE and 350-byte transaction output.
     */
    @Test
    @DisplayName("BR-18 encode: returned field length equals totalDigits (6 / 11 / 12)")
    void encodeLengthEqualsTotalDigits() {
        assertEquals(6, ZonedDecimal.encode(new BigDecimal("15.00"), 6, 2).length());
        assertEquals(11, ZonedDecimal.encode(new BigDecimal("12.34"), 11, 2).length());
        assertEquals(12, ZonedDecimal.encode(new BigDecimal("194.00"), 12, 2).length());
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 6 -- round-trip symmetry (encode <-> decode): the core byte-exact fidelity guarantee.
    // ---------------------------------------------------------------------------------------------

    /**
     * BR-18: {@code value -> encode -> decode -> value} holds for all values (positive, negative,
     * zero) across every field width, with the scale-2 realization preserved on the way back.
     */
    @ParameterizedTest
    @DisplayName("BR-18 round-trip: value -> encode -> decode -> value (scale 2 preserved)")
    @CsvSource({
        "15.00, 6",
        "15.05, 6",
        "-15.05, 6",
        "0.00, 6",
        "194.00, 12",
        "0.00, 12",
        "12.34, 11"
    })
    void roundTripValueEncodeDecode(String value, int totalDigits) {
        BigDecimal original = new BigDecimal(value);

        String encoded = ZonedDecimal.encode(original, totalDigits, 2);
        assertEquals(totalDigits, encoded.length());

        BigDecimal decoded = ZonedDecimal.decode(encoded, 2);
        assertEquals(original, decoded);
        assertEquals(2, decoded.scale());
    }

    /**
     * BR-18: {@code bytes -> decode -> encode -> bytes} is byte-identical for canonical positive and
     * negative NON-zero fields and for positive-zero {@code '{'} &mdash; this locks the read&rarr;write
     * fidelity that keeps the golden-master outputs exact.
     */
    @ParameterizedTest
    @DisplayName("BR-18 round-trip: raw bytes -> decode -> encode -> identical bytes")
    @CsvSource({
        "'00150{'",
        "'00150E'",
        "'00150N'",
        "'00000{'"
    })
    void roundTripBytesDecodeEncode(String raw) {
        BigDecimal decoded = ZonedDecimal.decode(raw, 2);
        String reencoded = ZonedDecimal.encode(decoded, raw.length(), 2);
        assertEquals(raw, reencoded);
    }

    /**
     * BR-18: the ONLY intentional non-symmetric case. A stored negative-zero {@code '}'} is
     * numerically {@code 0.00} (identical to positive-zero {@code '{'}) and therefore re-encodes to
     * {@code '{'}, never {@code '}'}. This degenerate {@code -0 -> +0} canonicalization is documented
     * on {@link ZonedDecimal#encode(BigDecimal, int, int)} and preserves the observable COBOL
     * contract; it does not affect any fixture (every fixture zero already stores {@code '{'}).
     */
    @Test
    @DisplayName("BR-18 caveat: stored -0 ('}') is numerically 0.00 and re-encodes to '{' "
            + "(only intentional non-symmetric case: degenerate -0 -> +0)")
    void negativeZeroCanonicalizesToPositiveZero() {
        // decode('}') and decode('{') are both numerically 0.00 (BigInteger has no -0).
        BigDecimal fromNegativeZero = ZonedDecimal.decode("00000}", 2);
        BigDecimal fromPositiveZero = ZonedDecimal.decode("00000{", 2);
        assertEquals(new BigDecimal("0.00"), fromNegativeZero);
        assertEquals(fromPositiveZero, fromNegativeZero);

        // Re-encoding that numeric zero yields '{' (positive-zero canonical form), NOT '}'.
        assertEquals("00000{", ZonedDecimal.encode(fromNegativeZero, 6, 2));
        assertNotEquals("00000}", ZonedDecimal.encode(fromNegativeZero, 6, 2));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 7 -- error / guard behavior (negative tests). Fail-fast is part of the observable contract.
    // ---------------------------------------------------------------------------------------------

    /**
     * BR-18: a trailing byte that is neither a valid overpunch character nor a plain digit
     * ({@code '*'}) is rejected with {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("BR-18 guard: invalid overpunch char '*' -> IllegalArgumentException")
    void decodeRejectsInvalidOverpunchChar() {
        assertThrows(IllegalArgumentException.class, () -> ZonedDecimal.decode("00150*", 2));
    }

    /**
     * BR-18: {@code decode} defensively rejects a {@code null} or empty raw field with
     * {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("BR-18 guard: null / empty raw -> IllegalArgumentException")
    void decodeRejectsNullOrEmpty() {
        assertThrows(IllegalArgumentException.class, () -> ZonedDecimal.decode(null, 2));
        assertThrows(IllegalArgumentException.class, () -> ZonedDecimal.decode("", 2));
    }

    /**
     * BR-18: {@code encode} rejects a magnitude that does not fit the fixed field width with
     * {@link IllegalArgumentException} ({@code 1234567} = 7 digits exceeds a 6-digit field).
     */
    @Test
    @DisplayName("BR-18 guard: encode magnitude wider than the field -> IllegalArgumentException")
    void encodeRejectsFieldOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> ZonedDecimal.encode(new BigDecimal("12345.67"), 6, 2));
    }

    /**
     * BR-18: {@code encode} of an over-precise value (more fractional digits than {@code scale})
     * fails fast with {@link ArithmeticException} via {@code setScale(2)} (RoundingMode.UNNECESSARY).
     */
    @Test
    @DisplayName("BR-18 guard: encode over-precise value (scale>2) -> ArithmeticException (fail-fast)")
    void encodeRejectsOverPreciseValue() {
        assertThrows(ArithmeticException.class,
                () -> ZonedDecimal.encode(new BigDecimal("1.234"), 6, 2));
    }
}
