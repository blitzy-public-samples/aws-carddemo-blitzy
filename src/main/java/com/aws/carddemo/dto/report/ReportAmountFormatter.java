package com.aws.carddemo.dto.report;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Shared, display-only helper that reproduces the two COBOL numeric edit masks used for the
 * monetary amounts on the AWS CardDemo daily transaction report.
 *
 * <p><strong>Origin:</strong> {@code legacy/cpy/CVTRA07Y.cpy}
 * (the {@code TRAN-REPORT-AMT} detail-line amount and the {@code REPT-PAGE-TOTAL},
 * {@code REPT-ACCOUNT-TOTAL} and {@code REPT-GRAND-TOTAL} total-line amounts). This class is the
 * single source of truth for those masks: the four amount-bearing report DTOs delegate their
 * {@code getFormatted*} accessors here, and the batch report writer relies on the exact
 * {@value #WIDTH}-character output to keep the 133-byte report line byte-faithful (the amount
 * occupies report columns 98-112).</p>
 *
 * <h2>Edit masks reproduced</h2>
 * <ul>
 *   <li>{@code -ZZZ,ZZZ,ZZZ.ZZ} &mdash; used by {@code TRAN-REPORT-AMT} (detail lines). The leading
 *       sign position holds a {@code '-'} for negative values and a space for zero/positive values.
 *       Exposed as {@link #formatSigned(BigDecimal)}.</li>
 *   <li>{@code +ZZZ,ZZZ,ZZZ.ZZ} &mdash; used by the page, account and grand total lines. The leading
 *       sign position holds a {@code '-'} for negative values and a forced {@code '+'} for
 *       zero/positive values. Exposed as {@link #formatForcedSign(BigDecimal)}.</li>
 * </ul>
 *
 * <h2>Field layout (both masks are exactly {@value #WIDTH} characters wide)</h2>
 * <pre>
 * pos  1      : sign position ('-', ' ' or '+') - a single fixed sign symbol that does NOT shift
 * pos  2..12  : ZZZ,ZZZ,ZZZ   9 integer digits with leading-zero suppression + 2 grouping commas
 * pos 13      : .             fixed decimal point
 * pos 14..15  : ZZ            2 fractional digits
 * </pre>
 *
 * <h2>COBOL editing semantics (reproduced exactly)</h2>
 * <ul>
 *   <li><em>Leading-zero suppression</em>: leading zeros in the 9-digit integer part are replaced by
 *       spaces from left to right, stopping at the first significant digit. Grouping commas that fall
 *       within the suppressed region are blanked as well; commas to the right of the first
 *       significant digit print normally.</li>
 *   <li><em>Fractional digits</em>: the two digits after the decimal point are never suppressed; they
 *       always print (for example {@code .05} or {@code .00}) - except in the all-zero case below.</li>
 *   <li><em>All-zero special case</em> (IBM Enterprise COBOL rule for an all-{@code Z} numeric
 *       picture): when the value is exactly zero the entire numeric portion, including the decimal
 *       point, blanks to spaces. Only the sign symbol survives, because the sign is a control symbol
 *       rather than a {@code Z} digit position. Thus {@link #formatSigned(BigDecimal)} of zero yields
 *       {@value #WIDTH} spaces, while {@link #formatForcedSign(BigDecimal)} of zero yields
 *       {@code '+'} followed by 14 spaces.</li>
 * </ul>
 *
 * <h2>Parity constraints</h2>
 * <ul>
 *   <li>Amounts are always {@link BigDecimal}; floating-point types are never used (AAP 0.6.1).</li>
 *   <li>Formatting is <strong>display-only</strong>: the caller's {@code BigDecimal} is never mutated
 *       and its scale/precision is preserved. A local, scale-2 display copy is used internally.</li>
 *   <li>Grouping always uses {@link Locale#ROOT} so the separator is a comma regardless of the JVM
 *       default locale.</li>
 *   <li>Values are assumed to lie within the COBOL source range {@code S9(09)V99}
 *       (magnitude {@code <= 999,999,999.99}); out-of-range behavior is documented on the two public
 *       methods.</li>
 * </ul>
 *
 * <p>Design rationale for centralizing the edit masks here (rather than in {@code util}) is recorded
 * in {@code docs/decision-log.md}.</p>
 */
public final class ReportAmountFormatter {

    /** Fixed width, in characters, of every edited amount produced by this class. */
    public static final int WIDTH = 15;

    /** Width of the integer region {@code ZZZ,ZZZ,ZZZ} (9 digit positions + 2 grouping commas). */
    private static final int INTEGER_REGION_WIDTH = 11;

    /** Fixed scale (number of fractional digits) of the edited amount. */
    private static final int DISPLAY_SCALE = 2;

    /** Divisor/modulus that splits the scaled "cents" magnitude into integer and fraction parts. */
    private static final BigInteger ONE_HUNDRED = BigInteger.valueOf(100L);

    /**
     * Non-instantiable: this is a pure static utility. Mirrors the {@code com.aws.carddemo.util}
     * convention of a private constructor that fails fast if reflection attempts instantiation.
     */
    private ReportAmountFormatter() {
        throw new AssertionError("No instances of ReportAmountFormatter");
    }

    /**
     * Formats an amount using the {@code -ZZZ,ZZZ,ZZZ.ZZ} mask (space-or-minus leading sign) used by
     * the {@code TRAN-REPORT-AMT} detail line: the sign position holds {@code '-'} for negative
     * values and a space for zero/positive values.
     *
     * <p>Examples (each result is exactly {@value #WIDTH} characters):</p>
     * <ul>
     *   <li>{@code 1234.56}  -&gt; {@code "       1,234.56"}</li>
     *   <li>{@code -1234.56} -&gt; {@code "-      1,234.56"}</li>
     *   <li>{@code 0.05}     -&gt; {@code "            .05"}</li>
     *   <li>{@code 0.00}     -&gt; 15 spaces</li>
     * </ul>
     *
     * <p>Values whose integer part exceeds the COBOL source range {@code S9(09)V99}
     * (integer part {@code > 999,999,999}) are outside the defined range; no clamping is performed and
     * the result may then exceed {@value #WIDTH} characters.</p>
     *
     * @param value the amount to edit; {@code null} is treated as zero. The instance is not mutated.
     * @return the edited amount string (normally exactly {@value #WIDTH} characters)
     */
    public static String formatSigned(BigDecimal value) {
        return format(value, ' ');
    }

    /**
     * Formats an amount using the {@code +ZZZ,ZZZ,ZZZ.ZZ} mask (forced {@code '+'}/{@code '-'} sign)
     * used by the {@code REPT-PAGE-TOTAL}, {@code REPT-ACCOUNT-TOTAL} and {@code REPT-GRAND-TOTAL}
     * total lines: the sign position holds {@code '-'} for negative values and a forced {@code '+'}
     * for zero/positive values.
     *
     * <p>Examples (each result is exactly {@value #WIDTH} characters):</p>
     * <ul>
     *   <li>{@code 1234.56}  -&gt; {@code "+      1,234.56"}</li>
     *   <li>{@code -1234.56} -&gt; {@code "-      1,234.56"}</li>
     *   <li>{@code 0.00}     -&gt; {@code '+'} followed by 14 spaces</li>
     *   <li>{@code 12345.00} -&gt; {@code "+     12,345.00"}</li>
     * </ul>
     *
     * <p>Values whose integer part exceeds the COBOL source range {@code S9(09)V99}
     * (integer part {@code > 999,999,999}) are outside the defined range; no clamping is performed and
     * the result may then exceed {@value #WIDTH} characters.</p>
     *
     * @param value the amount to edit; {@code null} is treated as zero. The instance is not mutated.
     * @return the edited amount string (normally exactly {@value #WIDTH} characters)
     */
    public static String formatForcedSign(BigDecimal value) {
        return format(value, '+');
    }

    /**
     * Shared implementation of both edit masks, parameterized by the character emitted in the sign
     * position for zero/positive values ({@code ' '} for the signed mask, {@code '+'} for the
     * forced-sign mask). A {@code '-'} is emitted for negative values by either mask.
     *
     * @param value        the amount to edit; {@code null} is treated as zero and never mutated
     * @param positiveSign the sign character emitted for zero/positive values
     * @return the edited amount string (normally exactly {@value #WIDTH} characters)
     */
    private static String format(BigDecimal value, char positiveSign) {
        // Work on a local, display-only copy. BigDecimal is immutable, so the caller's instance -
        // including its scale and precision - is never altered by this method. Extra fractional
        // digits are truncated toward zero, matching a COBOL MOVE into an S9(09)V99 field.
        BigDecimal displayValue = (value == null) ? BigDecimal.ZERO : value;
        displayValue = displayValue.setScale(DISPLAY_SCALE, RoundingMode.DOWN);

        // The sign occupies the fixed leading position; a single sign symbol does not shift.
        char sign = (displayValue.signum() < 0) ? '-' : positiveSign;

        // Collapse the value into a single non-negative "cents" magnitude (integer * 100 + fraction).
        BigInteger cents = displayValue.abs().movePointRight(DISPLAY_SCALE).toBigInteger();

        // All-zero special case: the whole numeric portion (digits AND decimal point) blanks to
        // spaces; only the sign control symbol remains.
        if (cents.signum() == 0) {
            return sign + " ".repeat(WIDTH - 1);
        }

        BigInteger integerPart = cents.divide(ONE_HUNDRED);
        long fraction = cents.mod(ONE_HUNDRED).longValue();

        // Integer region (11 chars): comma-grouped, right-justified, with leading zeros suppressed to
        // spaces. A zero integer part (for example 0.05) blanks the entire region.
        String integerRegion;
        if (integerPart.signum() == 0) {
            integerRegion = " ".repeat(INTEGER_REGION_WIDTH);
        } else {
            String grouped = String.format(Locale.ROOT, "%,d", integerPart);
            integerRegion = padLeft(grouped, INTEGER_REGION_WIDTH);
        }

        // Fraction region (3 chars): fixed decimal point followed by exactly two digits.
        String fractionRegion = "." + String.format(Locale.ROOT, "%02d", fraction);

        return sign + integerRegion + fractionRegion;
    }

    /**
     * Right-justifies {@code text} within {@code width} by prefixing spaces. If {@code text} is
     * already at least {@code width} characters it is returned unchanged (no truncation), which keeps
     * out-of-range amounts intact rather than corrupting them.
     *
     * @param text  the value to pad
     * @param width the target minimum width
     * @return the space-padded, right-justified value
     */
    private static String padLeft(String text, int width) {
        int deficit = width - text.length();
        return (deficit <= 0) ? text : " ".repeat(deficit) + text;
    }
}
