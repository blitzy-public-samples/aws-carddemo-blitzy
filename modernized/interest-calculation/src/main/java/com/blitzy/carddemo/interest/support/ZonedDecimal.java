package com.blitzy.carddemo.interest.support;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Codec for COBOL <strong>USAGE DISPLAY</strong> zoned-decimal numeric fields that carry the sign
 * as a <em>trailing-byte overpunch</em> and an <em>implied (unstored) decimal point</em>
 * ({@code Vnn}), converting to and from {@link java.math.BigDecimal}.
 *
 * <p>This class is the foundation of decimal fidelity for the entire interest-calculation module
 * (AAP &sect;0.6.2; business rule <strong>BR-18</strong>). Every {@code io.*} reader/writer routes
 * each signed {@code S9(n)Vnn} money/rate field through {@link #decode(String, int)} on read and
 * {@link #encode(BigDecimal, int, int)} on write, so a read&rarr;write round-trip is byte-identical
 * and the 300-byte account REWRITE and 350-byte transaction outputs match the golden master.</p>
 *
 * <h2>Why zoned-decimal (source evidence)</h2>
 * <p>The migrated program {@code app/cbl/CBACT04C.cbl} and its five copybooks declare every numeric
 * field as {@code USAGE DISPLAY} (the default) zoned decimal &mdash; there is
 * <strong>no COMP-3 and no REDEFINES anywhere</strong>; all numerics are zoned DISPLAY with an
 * overpunch sign plus an implied decimal. In a signed ({@code S...}) DISPLAY field the sign is
 * folded ("overpunched") onto the <em>trailing byte</em>, and the {@code V99} marks an implied
 * decimal position that occupies no storage. The signed PIC clauses that use this codec (every one
 * is scale&nbsp;2, i.e. {@code V99}) are:</p>
 * <ul>
 *   <li>{@code TRAN-CAT-BAL  PIC S9(09)V99} &rarr; 11 bytes ({@code app/cpy/CVTRA01Y.cpy:L9})</li>
 *   <li>{@code DIS-INT-RATE  PIC S9(04)V99} &rarr; 6 bytes  ({@code app/cpy/CVTRA02Y.cpy:L9})</li>
 *   <li>{@code ACCT-CURR-BAL} / {@code ACCT-CREDIT-LIMIT} / {@code ACCT-CASH-CREDIT-LIMIT} /
 *       {@code ACCT-CURR-CYC-CREDIT} / {@code ACCT-CURR-CYC-DEBIT  PIC S9(10)V99} &rarr; 12 bytes
 *       each ({@code app/cpy/CVACT01Y.cpy:L7-14})</li>
 *   <li>{@code TRAN-AMT      PIC S9(09)V99} &rarr; 11 bytes ({@code app/cpy/CVTRA05Y.cpy:L10})</li>
 * </ul>
 *
 * <h2>Fixture evidence ({@code app/data/ASCII/})</h2>
 * <p>Every signed field in the in-repo fixtures ends in {@code '{'} because the trailing digit is
 * always {@code 0} in that data:</p>
 * <ul>
 *   <li>{@code discgrp.txt}: {@code DIS-INT-RATE} raw {@code 00150{} &rarr; {@code 15.00},
 *       {@code 00250{} &rarr; {@code 25.00}, {@code 00000{} &rarr; {@code 0.00}</li>
 *   <li>{@code tcatbal.txt}: {@code TRAN-CAT-BAL} raw {@code 0000000000{} (11 bytes)
 *       &rarr; {@code 0.00}</li>
 *   <li>{@code acctdata.txt}: 300-byte account record, e.g. {@code ACCT-CURR-BAL} raw
 *       {@code 00000001940{} (12 bytes) &rarr; {@code 194.00}; signed balances end in {@code &hellip;{}</li>
 * </ul>
 *
 * <h2>Overpunch table (exact)</h2>
 * <p>The <strong>trailing byte</strong> encodes (sign + last digit); all <strong>leading bytes</strong>
 * are plain ASCII digits {@code '0'}&ndash;{@code '9'}:</p>
 * <pre>
 * Positive:  '{' = +0,  'A'=+1 'B'=+2 'C'=+3 'D'=+4 'E'=+5 'F'=+6 'G'=+7 'H'=+8 'I'=+9
 * Negative:  '}' = -0,  'J'=-1 'K'=-2 'L'=-3 'M'=-4 'N'=-5 'O'=-6 'P'=-7 'Q'=-8 'R'=-9
 * </pre>
 *
 * <h2>Zero canonicalization (documented, do NOT "fix")</h2>
 * <p>{@link #encode} always emits {@code '{'} for a zero value (positive zero), which matches every
 * zero in the fixtures. {@link #decode} of {@code '}'} yields numeric {@code 0}. Byte-identical
 * round-trip therefore holds for all fixture data and all non-zero values; the only non-round-trip
 * is the degenerate stored {@code -0} ({@code '}'}), which is numerically equal to {@code +0} and
 * re-encodes to {@code '{'}. This is intentional and preserves the observable COBOL contract.</p>
 *
 * <p>This is a stateless utility: all methods are {@code static} and the class is never instantiated.
 * Reverse-engineered strictly from the copybook PIC layouts and the {@code app/data/ASCII/} overpunch
 * evidence; it modifies nothing under {@code app/} (AAP &sect;0.7 traceability).</p>
 */
public final class ZonedDecimal {

    /**
     * Non-instantiable: {@code ZonedDecimal} is a pure, stateless static utility.
     */
    private ZonedDecimal() {
        // Intentionally empty -- prevents instantiation of this utility class.
    }

    /**
     * Decodes a fixed-width COBOL zoned-decimal field into a {@link BigDecimal} at {@code scale}.
     *
     * <p>The total number of stored digits equals {@code raw.length()} (e.g. 6 for
     * {@code S9(04)V99}, 11 for {@code S9(09)V99}, 12 for {@code S9(10)V99}). The <em>last</em>
     * character carries the overpunched sign + final digit (see the overpunch table on the class);
     * the preceding {@code raw.length()-1} characters are plain ASCII digits. The implied
     * {@code Vnn} decimal point is realized by constructing a {@link BigDecimal} from the unsigned
     * {@link BigInteger} magnitude at the given {@code scale} (equivalent to {@code &divide;10^scale}),
     * so the result has exactly {@code scale} fractional digits.</p>
     *
     * <p>No {@code Integer.parseInt}/{@code Long.parseLong} is used; parsing is performed digit-wise
     * via {@link BigInteger}, which both handles arbitrary field widths and preserves leading zeros.</p>
     *
     * <p>Examples (scale 2): {@code decode("00150{", 2)} &rarr; {@code 15.00};
     * {@code decode("0000000000{", 2)} &rarr; {@code 0.00};
     * {@code decode("00000001940{", 2)} &rarr; {@code 194.00}.</p>
     *
     * @param raw   the raw fixed-width zoned-decimal field (leading digits + overpunched trailing byte);
     *              must be non-null and non-empty
     * @param scale the number of implied fractional digits ({@code 2} for every {@code V99} field in
     *              this module); must be {@code >= 0}
     * @return the decoded value as a {@link BigDecimal} whose {@code scale()} equals {@code scale}
     * @throws IllegalArgumentException if {@code raw} is null/empty, {@code scale} is negative, the
     *                                  trailing byte is not a valid overpunch character, or the
     *                                  leading bytes are not all digits
     */
    public static BigDecimal decode(String raw, int scale) {
        // Defensive guards (agent_prompt decode step 6).
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("raw zoned-decimal field must be non-null and non-empty");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("scale must be >= 0, but was " + scale);
        }

        final int length = raw.length();

        // The trailing byte carries the overpunched sign + last digit; leading bytes are plain '0'-'9'.
        final char trailing = raw.charAt(length - 1);
        final int[] signAndDigit = decodeOverpunch(trailing); // {digit (0-9), sign (+1 or -1)}
        final int lastDigit = signAndDigit[0];
        final int sign = signAndDigit[1];

        // Reassemble the unsigned digit run: (length-1) plain leading digits + the decoded last digit.
        // BigInteger correctly handles the leading zeros that pad every canonical COBOL field.
        final String digits = raw.substring(0, length - 1) + lastDigit;
        BigInteger unscaled = new BigInteger(digits);

        // Apply the overpunched sign.
        if (sign < 0) {
            unscaled = unscaled.negate();
        }

        // Realize the implied Vnn decimal point: value = unscaled / 10^scale, at exactly `scale` digits.
        return new BigDecimal(unscaled, scale);
    }

    /**
     * Encodes a {@link BigDecimal} back into a fixed-width COBOL zoned-decimal field string.
     *
     * <p>This is the <strong>exact inverse</strong> of {@link #decode(String, int)} so that a
     * read&rarr;write round-trip is byte-identical &mdash; a requirement for the 300-byte account
     * REWRITE and the 350-byte transaction output, where any divergence would corrupt the golden
     * master. The value's magnitude is left-padded with {@code '0'} to {@code totalDigits} and the
     * trailing byte is overpunched with the sign (see the overpunch table on the class).</p>
     *
     * <p><strong>Zero canonicalization:</strong> the sign is taken from the original {@code value}
     * with zero treated as positive, so a zero always encodes to a {@code '{'} trailing byte (matching
     * every zero in the fixtures). The stored {@code -0} form ({@code '}'}) is therefore never emitted;
     * it is numerically equal to {@code +0} and re-encodes to {@code '{'} (documented on the class).</p>
     *
     * <p>Example: {@code encode(new BigDecimal("15.00"), 6, 2)} &rarr; magnitude {@code 1500}
     * &rarr; padded {@code 001500} &rarr; last digit {@code 0}, positive &rarr; {@code '{'}
     * &rarr; {@code "00150{"} (byte-identical to the {@code discgrp.txt} fixture).</p>
     *
     * @param value       the value to encode; must be non-null and already at (or below) the target
     *                    {@code scale} of fractional precision
     * @param totalDigits the fixed field width in stored digits (e.g. {@code 6}, {@code 11}, or
     *                    {@code 12} in this module); must be {@code >= 1}
     * @param scale       the number of implied fractional digits ({@code 2} for every {@code V99}
     *                    field); must be {@code >= 0}
     * @return the fixed-width zoned-decimal field string of length {@code totalDigits}
     * @throws ArithmeticException      if {@code value} has more fractional digits than {@code scale}
     *                                  (via {@link BigDecimal#setScale(int)} fail-fast -- callers pass
     *                                  values already at the target scale)
     * @throws IllegalArgumentException if {@code value} is null, {@code totalDigits < 1},
     *                                  {@code scale < 0}, or the magnitude does not fit in
     *                                  {@code totalDigits} digits ("value too large for field width")
     */
    public static String encode(BigDecimal value, int totalDigits, int scale) {
        // Defensive guards.
        if (value == null) {
            throw new IllegalArgumentException("value must be non-null");
        }
        if (totalDigits < 1) {
            throw new IllegalArgumentException("totalDigits must be >= 1, but was " + totalDigits);
        }
        if (scale < 0) {
            throw new IllegalArgumentException("scale must be >= 0, but was " + scale);
        }

        // Normalize to the field scale. setScale(int) (RoundingMode.UNNECESSARY) throws
        // ArithmeticException if `value` carries MORE fractional digits than the field can store --
        // the correct fail-fast, since callers always pass values already at the target scale.
        final BigDecimal scaled = value.setScale(scale);

        // Unsigned magnitude of the stored (unscaled) integer, e.g. 15.00 -> 1500.
        final BigInteger magnitude = scaled.unscaledValue().abs();
        final String digits = magnitude.toString();

        // Overflow guard: the magnitude must fit within the fixed field width.
        if (digits.length() > totalDigits) {
            throw new IllegalArgumentException("value too large for field width");
        }

        // Manual left-pad with '0' to exactly totalDigits (no locale-sensitive formatting).
        final StringBuilder padded = new StringBuilder(totalDigits);
        for (int i = digits.length(); i < totalDigits; i++) {
            padded.append('0');
        }
        padded.append(digits);

        // Sign comes from the ORIGINAL value; zero is treated as positive => '{' (zero canonicalization).
        final boolean positive = value.signum() >= 0;

        // Fold the sign onto the trailing byte via the overpunch table.
        final int lastDigit = padded.charAt(totalDigits - 1) - '0';
        final char overpunch = encodeOverpunch(lastDigit, positive);

        return padded.substring(0, totalDigits - 1) + overpunch;
    }

    /**
     * Decodes a single overpunched trailing byte into its {@code (digit, sign)} pair per the exact
     * overpunch table documented on the class.
     *
     * <p>Mapping: {@code '{'} &rarr; {@code (0, +1)}; {@code 'A'..'I'} &rarr; {@code (c-'A'+1, +1)};
     * {@code '}'} &rarr; {@code (0, -1)}; {@code 'J'..'R'} &rarr; {@code (c-'J'+1, -1)}. A plain
     * {@code '0'..'9'} trailing digit is decoded <em>defensively</em> as {@code (c-'0', +1)} -- an
     * un-overpunched trailing byte is treated as positive. Any other character is rejected.</p>
     *
     * @param c the trailing byte of a zoned-decimal field
     * @return a two-element array {@code {digit (0-9), sign (+1 or -1)}}
     * @throws IllegalArgumentException if {@code c} is not a valid overpunch or plain-digit character
     */
    private static int[] decodeOverpunch(char c) {
        if (c == '{') {
            return new int[] {0, 1};          // positive zero
        }
        if (c >= 'A' && c <= 'I') {
            return new int[] {c - 'A' + 1, 1}; // positive 1..9
        }
        if (c == '}') {
            return new int[] {0, -1};          // negative zero (numerically 0; see class doc)
        }
        if (c >= 'J' && c <= 'R') {
            return new int[] {c - 'J' + 1, -1}; // negative 1..9
        }
        if (c >= '0' && c <= '9') {
            return new int[] {c - '0', 1};      // defensive: un-overpunched digit read as positive
        }
        throw new IllegalArgumentException("Invalid overpunch char: '" + c + "'");
    }

    /**
     * Encodes a {@code (digit, sign)} pair into its overpunched trailing byte, the exact inverse of
     * the primary branches of {@link #decodeOverpunch(char)}.
     *
     * <p>Mapping: positive &rarr; digit {@code 0} yields {@code '{'}, digits {@code 1..9} yield
     * {@code (char)('A'+digit-1)}; negative &rarr; digit {@code 0} yields {@code '}'}, digits
     * {@code 1..9} yield {@code (char)('J'+digit-1)}.</p>
     *
     * @param digit    the final digit, guaranteed {@code 0..9} (derived from one padded character)
     * @param positive {@code true} for a non-negative value (zero canonicalizes to positive)
     * @return the overpunched trailing byte
     */
    private static char encodeOverpunch(int digit, boolean positive) {
        if (positive) {
            return digit == 0 ? '{' : (char) ('A' + digit - 1);
        }
        return digit == 0 ? '}' : (char) ('J' + digit - 1);
    }
}
