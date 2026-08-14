package com.carddemo.cobol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Fixed-point arithmetic for every monetary field in the platform. Arithmetic methods return
 * {@link BigDecimal} values truncated toward zero at a scale the caller supplies;
 * {@link #formatProcessingTimestamp(LocalDateTime)} returns a {@link String} and performs no
 * arithmetic.
 *
 * <p>The {@code ROUNDED} phrase appears zero times across all 28 programs in {@code app/cbl/},
 * so every COBOL arithmetic store truncates. Every arithmetic method here pins
 * {@link RoundingMode#DOWN}, takes no rounding mode, and offers no overload that does.</p>
 *
 * <p>The source statements that move money sit at these locators, and each one is reachable
 * through a method below.</p>
 *
 * <ol>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L403-L405} computes the overlimit working balance from the
 *       two cycle accumulators and the transaction amount.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L547-L551} adds the amount to the account balance, then adds
 *       it to one of the two cycle accumulators on the sign of the amount.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L508} adds the amount to a new category balance.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L527} adds the amount to an existing category balance.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl:L234} subtracts the payment amount from the account
 *       balance.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L464-L465} multiplies a category balance by an interest rate
 *       and divides by 1200.</li>
 * </ol>
 *
 * <p>Three source working fields hold nine integer digits, one fewer than the five account money
 * fields at {@code app/cpy/CVACT01Y.cpy:L7-L9} and {@code app/cpy/CVACT01Y.cpy:L13-L14}. The
 * three are {@code WS-TEMP-BAL} at {@code app/cbl/CBTRN02C.cbl:L187}, and
 * {@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT} at {@code app/cbl/CBACT04C.cbl:L168-L169}.
 * {@link #truncateToPictureField(BigDecimal, int, int)} reproduces all three narrowings.</p>
 *
 * <p>Scales, precisions, and timestamp widths come from {@link PicClause}.</p>
 */
public final class CobolDecimal {

    /**
     * The divisor in the interest computation at {@code app/cbl/CBACT04C.cbl:L465}. The source
     * writes the integer literal {@code 1200}.
     */
    public static final BigDecimal INTEREST_DIVISOR = BigDecimal.valueOf(1200L);

    /** The unit {@link LocalDateTime#getNano()} reports. */
    private static final int NANOSECONDS_PER_SECOND = 1_000_000_000;

    /**
     * Fractional units the rendered timestamp counts per second. Two digits carry hundredths,
     * per {@link PicClause#PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS}.
     */
    private static final int FRACTION_UNITS_PER_SECOND = BigDecimal.TEN
            .pow(PicClause.PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS)
            .intValueExact();

    /** Nanoseconds in one fractional unit of the rendered timestamp. */
    private static final int NANOSECONDS_PER_FRACTION_UNIT =
            NANOSECONDS_PER_SECOND / FRACTION_UNITS_PER_SECOND;

    /**
     * The largest year the rendered timestamp holds, derived from
     * {@link PicClause#PROCESSING_TIMESTAMP_YEAR_WIDTH}.
     */
    private static final int LARGEST_RENDERABLE_YEAR = BigDecimal.TEN
            .pow(PicClause.PROCESSING_TIMESTAMP_YEAR_WIDTH)
            .intValueExact() - 1;

    private static final char PADDING_DIGIT = '0';

    private CobolDecimal() {
    }

    /**
     * Adds two values and truncates the result toward zero at the given scale.
     *
     * <p>This method reproduces the {@code ADD} statements at
     * {@code app/cbl/CBTRN02C.cbl:L508},
     * {@code app/cbl/CBTRN02C.cbl:L527}, {@code app/cbl/CBTRN02C.cbl:L547-L551},
     * {@code app/cbl/CBACT04C.cbl:L352}, and {@code app/cbl/CBACT04C.cbl:L467}.</p>
     *
     * @param augend the running field, such as {@code ACCT-CURR-BAL}; must not be {@code null}
     * @param addend the value added to it, such as {@code DALYTRAN-AMT}; must not be
     *               {@code null}
     * @param scale  digits kept after the decimal point, from {@link PicClause}; must not be
     *               negative
     * @return the sum, truncated toward zero at {@code scale}
     * @throws NullPointerException     if either value is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal add(BigDecimal augend, BigDecimal addend, int scale) {
        Objects.requireNonNull(augend, "augend must not be null");
        Objects.requireNonNull(addend, "addend must not be null");
        requireNonNegativeScale(scale);

        return augend.add(addend).setScale(scale, RoundingMode.DOWN);
    }

    /**
     * Subtracts one value from another and truncates the result toward zero at the given scale.
     *
     * <p>The overlimit computation at {@code app/cbl/CBTRN02C.cbl:L403-L404} subtracts the cycle
     * debit accumulator from the cycle credit accumulator. The bill payment computation at
     * {@code app/cbl/COBIL00C.cbl:L234} subtracts the payment amount from the account balance
     * and leaves both cycle accumulators untouched.</p>
     *
     * @param minuend    the value subtracted from, such as {@code ACCT-CURR-CYC-CREDIT}; must
     *                   not be {@code null}
     * @param subtrahend the value taken away, such as {@code ACCT-CURR-CYC-DEBIT}; must not be
     *                   {@code null}
     * @param scale      digits kept after the decimal point, from {@link PicClause}; must not be
     *                   negative
     * @return the difference, truncated toward zero at {@code scale}
     * @throws NullPointerException     if either value is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal subtract(BigDecimal minuend, BigDecimal subtrahend, int scale) {
        Objects.requireNonNull(minuend, "minuend must not be null");
        Objects.requireNonNull(subtrahend, "subtrahend must not be null");
        requireNonNegativeScale(scale);

        return minuend.subtract(subtrahend).setScale(scale, RoundingMode.DOWN);
    }

    /**
     * Multiplies two values and truncates the product toward zero at the given scale.
     *
     * <p>The interest computation at {@code app/cbl/CBACT04C.cbl:L464-L465} multiplies a
     * category balance by a disclosure group rate. A scale equal to the sum of the two operand
     * scales keeps that product whole.
     * {@link #multiplyThenDivide(BigDecimal, BigDecimal, BigDecimal, int)} reproduces the entire
     * statement in one call.</p>
     *
     * @param multiplicand the first value, such as {@code TRAN-CAT-BAL}; must not be
     *                     {@code null}
     * @param multiplier   the second value, such as {@code DIS-INT-RATE}; must not be
     *                     {@code null}
     * @param scale        digits kept after the decimal point, from {@link PicClause}; must not
     *                     be negative
     * @return the product, truncated toward zero at {@code scale}
     * @throws NullPointerException     if either value is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal multiply(BigDecimal multiplicand, BigDecimal multiplier, int scale) {
        Objects.requireNonNull(multiplicand, "multiplicand must not be null");
        Objects.requireNonNull(multiplier, "multiplier must not be null");
        requireNonNegativeScale(scale);

        return multiplicand.multiply(multiplier).setScale(scale, RoundingMode.DOWN);
    }

    /**
     * Divides one value by another and truncates the quotient toward zero at the given scale.
     *
     * <p>The interest computation at {@code app/cbl/CBACT04C.cbl:L465} divides by the integer
     * literal {@code 1200}, held here as {@link #INTEREST_DIVISOR}.
     * {@link BigDecimal#divide(BigDecimal)} throws on a quotient with no terminating decimal
     * expansion, so every call supplies a scale.</p>
     *
     * @param dividend the value divided, such as the product of a balance and a rate; must not
     *                 be {@code null}
     * @param divisor  the value divided by; must not be {@code null} and must not be zero
     * @param scale    digits kept after the decimal point, from {@link PicClause}; must not be
     *                 negative
     * @return the quotient, truncated toward zero at {@code scale}
     * @throws NullPointerException     if either value is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     * @throws ArithmeticException      if {@code divisor} is zero
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor, int scale) {
        Objects.requireNonNull(dividend, "dividend must not be null");
        Objects.requireNonNull(divisor, "divisor must not be null");
        requireNonNegativeScale(scale);

        return dividend.divide(divisor, scale, RoundingMode.DOWN);
    }

    /**
     * Multiplies two values, divides the whole product by a third, and truncates the result
     * toward zero at the given scale.
     *
     * <p>One statement in the source carries this shape:
     * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at
     * {@code app/cbl/CBACT04C.cbl:L464-L465}. The product keeps every digit of both operands,
     * and the single truncation lands on the store into {@code WS-MONTHLY-INT}, declared
     * {@code PIC S9(09)V99} at {@code app/cbl/CBACT04C.cbl:L168}.</p>
     *
     * @param multiplicand the first value, such as {@code TRAN-CAT-BAL}; must not be
     *                     {@code null}
     * @param multiplier   the second value, such as {@code DIS-INT-RATE}; must not be
     *                     {@code null}
     * @param divisor      the value the product is divided by, such as
     *                     {@link #INTEREST_DIVISOR}; must not be {@code null} and must not be
     *                     zero
     * @param scale        digits kept after the decimal point, from {@link PicClause}; must not
     *                     be negative
     * @return the quotient, truncated toward zero at {@code scale}
     * @throws NullPointerException     if any value is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     * @throws ArithmeticException      if {@code divisor} is zero
     */
    public static BigDecimal multiplyThenDivide(BigDecimal multiplicand,
                                                BigDecimal multiplier,
                                                BigDecimal divisor,
                                                int scale) {
        Objects.requireNonNull(multiplicand, "multiplicand must not be null");
        Objects.requireNonNull(multiplier, "multiplier must not be null");
        Objects.requireNonNull(divisor, "divisor must not be null");
        requireNonNegativeScale(scale);

        BigDecimal wholeProduct = multiplicand.multiply(multiplier);
        return wholeProduct.divide(divisor, scale, RoundingMode.DOWN);
    }

    /**
     * Truncates a value toward zero at the given scale and leaves its other digits alone.
     *
     * <p>Every monetary Picture clause in the source fixes two digits after the point, among them
     * {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7} and
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}. A store into either
     * field drops the digits past those two.</p>
     *
     * <p>The method applies one {@link PicClause} scale, such as
     * {@link PicClause#TRAN_AMT_SCALE} or {@link PicClause#ACCT_CURR_BAL_SCALE}. It performs no
     * arithmetic and drops no high-order digit.
     * {@link #truncateToPictureField(BigDecimal, int, int)} narrows a value into a field of a
     * fixed precision.</p>
     *
     * @param value the value to truncate; must not be {@code null}
     * @param scale digits kept after the decimal point, from {@link PicClause}; must not be
     *              negative
     * @return the value, truncated toward zero at {@code scale}
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative
     */
    public static BigDecimal truncateToScale(BigDecimal value, int scale) {
        Objects.requireNonNull(value, "value must not be null");
        requireNonNegativeScale(scale);

        return value.setScale(scale, RoundingMode.DOWN);
    }

    /**
     * Truncates a value into a COBOL Picture field of the given precision and scale, dropping
     * the excess low-order digits and the excess high-order digits.
     *
     * <p>A COBOL {@code MOVE} into a narrower numeric field discards the digits that do not fit
     * and keeps the sign. Three source working fields hold nine integer digits while the five
     * account money fields at {@code app/cpy/CVACT01Y.cpy:L7-L9} and
     * {@code app/cpy/CVACT01Y.cpy:L13-L14} hold ten. The three are {@code WS-TEMP-BAL} at
     * {@code app/cbl/CBTRN02C.cbl:L187}, and {@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT} at
     * {@code app/cbl/CBACT04C.cbl:L168-L169}. The {@code MOVE} at
     * {@code app/cbl/COBIL00C.cbl:L224} carries {@code ACCT-CURR-BAL} into {@code TRAN-AMT},
     * which is the same narrowing.</p>
     *
     * <p>A magnitude of one billion or more in a {@code PIC S9(09)V99} field loses its
     * high-order digit. The overlimit test at {@code app/cbl/CBTRN02C.cbl:L407} then reads the
     * smaller value and approves the transaction. This method reproduces that outcome and
     * widens no field.</p>
     *
     * @param value     the value being stored; must not be {@code null}
     * @param precision total digits the target field holds, from {@link PicClause}; must be
     *                  greater than {@code scale}
     * @param scale     digits the target field holds after the decimal point, from
     *                  {@link PicClause}; must not be negative
     * @return the value as the target field would hold it
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code scale} is negative, or if {@code precision} is
     *                                  not greater than {@code scale}
     */
    public static BigDecimal truncateToPictureField(BigDecimal value, int precision, int scale) {
        Objects.requireNonNull(value, "value must not be null");
        requireNonNegativeScale(scale);
        if (precision <= scale) {
            throw new IllegalArgumentException("precision must be greater than scale: precision="
                    + precision + ", scale=" + scale);
        }

        BigDecimal lowOrderTruncated = value.setScale(scale, RoundingMode.DOWN);
        int integerDigitsHeld = precision - scale;
        if (integerDigitCount(lowOrderTruncated) <= integerDigitsHeld) {
            return lowOrderTruncated;
        }

        BigDecimal modulus = BigDecimal.TEN.pow(integerDigitsHeld);
        BigDecimal digitsThatFit = lowOrderTruncated.abs().remainder(modulus);
        if (lowOrderTruncated.signum() < 0) {
            digitsThatFit = digitsThatFit.negate();
        }
        return digitsThatFit.setScale(scale, RoundingMode.DOWN);
    }

    /**
     * Renders a moment as the processing timestamp the posting program writes.
     *
     * <p>The shape is {@value PicClause#PROCESSING_TIMESTAMP_SHAPE}, and the result is always
     * {@value PicClause#PROCESSING_TIMESTAMP_WIDTH} characters. A dash separates the day from
     * the hour, at index 10. Two digits carry hundredths of a second, and the final four
     * characters are literal zeros.</p>
     *
     * <p>Five points in the source fix that shape. {@code app/cbl/CBTRN02C.cbl:L149} carries the
     * author's own ruler for it. {@code app/cbl/CBTRN02C.cbl:L166} places the third dash between
     * the day and the hour.</p>
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L173-L174} declares two fractional digits followed by four
     * more characters. {@code app/cbl/CBTRN02C.cbl:L701-L702} fills those four characters with
     * {@code '0000'} and all three separators with {@code '-'}, and
     * {@code app/cbl/CBACT04C.cbl:L622-L623} repeats both moves.</p>
     *
     * <p>The rendered value reaches {@code TRAN-PROC-TS} at
     * {@code app/cbl/CBTRN02C.cbl:L438}. Nanoseconds below one hundredth of a second are
     * dropped.</p>
     *
     * @param moment the moment to render; must not be {@code null}, and its year must fit
     *               {@value PicClause#PROCESSING_TIMESTAMP_YEAR_WIDTH} digits
     * @return the rendered timestamp
     * @throws NullPointerException     if {@code moment} is {@code null}
     * @throws IllegalArgumentException if the year of {@code moment} is negative or needs more
     *                                  than {@value PicClause#PROCESSING_TIMESTAMP_YEAR_WIDTH}
     *                                  digits
     */
    public static String formatProcessingTimestamp(LocalDateTime moment) {
        Objects.requireNonNull(moment, "moment must not be null");

        int year = moment.getYear();
        if (year < 0 || year > LARGEST_RENDERABLE_YEAR) {
            throw new IllegalArgumentException("year must fit "
                    + PicClause.PROCESSING_TIMESTAMP_YEAR_WIDTH + " digits: year=" + year);
        }

        int hundredths = moment.getNano() / NANOSECONDS_PER_FRACTION_UNIT;
        int componentWidth = PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH;

        StringBuilder rendered = new StringBuilder(PicClause.PROCESSING_TIMESTAMP_WIDTH);
        appendZeroPadded(rendered, year, PicClause.PROCESSING_TIMESTAMP_YEAR_WIDTH);
        rendered.append(PicClause.PROCESSING_TIMESTAMP_DASH);
        appendZeroPadded(rendered, moment.getMonthValue(), componentWidth);
        rendered.append(PicClause.PROCESSING_TIMESTAMP_DASH);
        appendZeroPadded(rendered, moment.getDayOfMonth(), componentWidth);
        rendered.append(PicClause.PROCESSING_TIMESTAMP_DASH);
        appendZeroPadded(rendered, moment.getHour(), componentWidth);
        rendered.append(PicClause.PROCESSING_TIMESTAMP_DOT);
        appendZeroPadded(rendered, moment.getMinute(), componentWidth);
        rendered.append(PicClause.PROCESSING_TIMESTAMP_DOT);
        appendZeroPadded(rendered, moment.getSecond(), componentWidth);
        rendered.append(PicClause.PROCESSING_TIMESTAMP_DOT);
        appendZeroPadded(rendered, hundredths,
                PicClause.PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS);
        rendered.append(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS);

        return rendered.toString();
    }

    private static int integerDigitCount(BigDecimal value) {
        return value.precision() - value.scale();
    }

    private static void appendZeroPadded(StringBuilder target, int value, int width) {
        String digits = Integer.toString(value);
        for (int written = digits.length(); written < width; written++) {
            target.append(PADDING_DIGIT);
        }
        target.append(digits);
    }

    private static void requireNonNegativeScale(int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException("scale must not be negative: scale=" + scale);
        }
    }
}
