package com.carddemo.cobol;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that {@link CobolDecimal} truncates toward zero at every money-moving arithmetic site in
 * the CardDemo COBOL source, and that half-up rounding returns a different value.
 *
 * <p>The {@code ROUNDED} phrase appears zero times across all 28 programs in {@code app/cbl/}.
 * Every COBOL arithmetic store therefore truncates toward zero, the language default when no
 * rounding phrase is present.
 *
 * <p>Seven statements move money. Each one has a method below, and each method names its locator.
 *
 * <ol>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L403-L405} computes the overlimit working balance, and
 *       {@code app/cbl/CBTRN02C.cbl:L407} compares it against the credit limit.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L547} adds the transaction amount to the account balance.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L548-L551} routes the amount to the cycle credit accumulator
 *       or to the cycle debit accumulator on its sign.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L508} adds the amount to a category balance the program is
 *       creating.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L527} adds the amount to a category balance the program is
 *       updating.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl:L234} subtracts the payment amount from the account
 *       balance.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L464-L465} multiplies a category balance by an interest rate
 *       and divides the product by 1200.</li>
 * </ol>
 *
 * <p>Three programs supply every expected value in this class: {@code app/cbl/CBTRN02C.cbl},
 * {@code app/cbl/COBIL00C.cbl}, and {@code app/cbl/CBACT04C.cbl}. Scales and precisions come from
 * {@link PicClause}, so no method here writes a scale as a literal.
 *
 * <p>Several methods name {@link RoundingMode#HALF_UP} and pin the value that mode returns, then
 * assert it differs from the value {@link CobolDecimal} returns. A rounding-mode change in the
 * production class fails those methods. Operand sets carry a non-zero digit past the target scale,
 * so the two modes genuinely disagree.
 *
 * <p>Negative operands appear throughout. Truncation toward zero shrinks a magnitude and never
 * grows one, and {@link #truncationNeverGrowsTheMagnitudeOfAValue()} asserts that property across
 * both signs.
 *
 * <p>Rationale lives in {@code card-platform/docs/decision-log.md}. Flagged source rules live in
 * {@code card-platform/docs/business-rule-flags.md}.
 */
class CobolDecimalTruncationTest {

    // ---------------------------------------------------------------------------------------
    // Site 1. Overlimit working balance. app/cbl/CBTRN02C.cbl:L403-L405 and L407.
    // ---------------------------------------------------------------------------------------

    /** {@code ACCT-CURR-CYC-CREDIT}, {@code app/cpy/CVACT01Y.cpy:L13}, with a third decimal. */
    private static final BigDecimal CYCLE_CREDIT_THREE_DECIMALS = new BigDecimal("1234.567");

    /** {@code ACCT-CURR-CYC-DEBIT}, {@code app/cpy/CVACT01Y.cpy:L14}, with a third decimal. */
    private static final BigDecimal CYCLE_DEBIT_THREE_DECIMALS = new BigDecimal("200.001");

    /** {@code DALYTRAN-AMT}, {@code app/cpy/CVTRA06Y.cpy:L10}, with a third decimal. */
    private static final BigDecimal AMOUNT_THREE_DECIMALS = new BigDecimal("500.009");

    /** The truncated difference of {@link #CYCLE_CREDIT_THREE_DECIMALS} and the cycle debit. */
    private static final BigDecimal TRUNCATED_CYCLE_DIFFERENCE = new BigDecimal("1034.56");

    /** The half-up difference of the same two operands. */
    private static final BigDecimal HALF_UP_CYCLE_DIFFERENCE = new BigDecimal("1034.57");

    /** The truncated working balance the source formula yields from the three operands. */
    private static final BigDecimal TRUNCATED_WORKING_BALANCE = new BigDecimal("1534.56");

    /** The half-up working balance from the same three operands. */
    private static final BigDecimal HALF_UP_WORKING_BALANCE = new BigDecimal("1534.57");

    /** {@code ACCT-CREDIT-LIMIT}, {@code app/cpy/CVACT01Y.cpy:L8}. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /** A cycle credit accumulator at the stored scale of two. */
    private static final BigDecimal CYCLE_CREDIT = new BigDecimal("5000.00");

    /** A cycle debit accumulator at the stored scale of two. */
    private static final BigDecimal CYCLE_DEBIT = new BigDecimal("1200.00");

    /** An amount that keeps the working balance inside the credit limit. */
    private static final BigDecimal AMOUNT_INSIDE_LIMIT = new BigDecimal("250.75");

    /** The working balance the approved case yields. */
    private static final BigDecimal WORKING_BALANCE_INSIDE_LIMIT = new BigDecimal("4050.75");

    /** An amount that carries the working balance past the credit limit. */
    private static final BigDecimal AMOUNT_OVER_LIMIT = new BigDecimal("2000.00");

    /** The working balance the declined case yields. */
    private static final BigDecimal WORKING_BALANCE_OVER_LIMIT = new BigDecimal("5800.00");

    // ---------------------------------------------------------------------------------------
    // Site 2. Account balance. app/cbl/CBTRN02C.cbl:L547.
    // ---------------------------------------------------------------------------------------

    /** {@code ACCT-CURR-BAL}, {@code app/cpy/CVACT01Y.cpy:L7}, at the stored scale of two. */
    private static final BigDecimal ACCOUNT_BALANCE = new BigDecimal("1000.00");

    /** A transaction amount carrying a third decimal digit. */
    private static final BigDecimal AMOUNT_WITH_TRAILING_FIVE = new BigDecimal("25.555");

    /** The truncated balance after the add at {@code app/cbl/CBTRN02C.cbl:L547}. */
    private static final BigDecimal TRUNCATED_ACCOUNT_BALANCE = new BigDecimal("1025.55");

    /** The half-up balance from the same two operands. */
    private static final BigDecimal HALF_UP_ACCOUNT_BALANCE = new BigDecimal("1025.56");

    /** The balance after adding an amount already at the stored scale. */
    private static final BigDecimal ACCOUNT_BALANCE_AFTER_SCALED_AMOUNT = new BigDecimal("1250.75");

    // ---------------------------------------------------------------------------------------
    // Site 3. Cycle accumulator branch. app/cbl/CBTRN02C.cbl:L548-L551.
    // ---------------------------------------------------------------------------------------

    /** A cycle debit accumulator already holding a negative value. */
    private static final BigDecimal NEGATIVE_CYCLE_DEBIT = new BigDecimal("-100.00");

    /** A refund amount carrying a third decimal digit. */
    private static final BigDecimal NEGATIVE_AMOUNT_THREE_DECIMALS = new BigDecimal("-50.555");

    /** The truncated cycle debit accumulator after the add at L551. */
    private static final BigDecimal TRUNCATED_CYCLE_DEBIT = new BigDecimal("-150.55");

    /** The half-up cycle debit accumulator from the same two operands. */
    private static final BigDecimal HALF_UP_CYCLE_DEBIT = new BigDecimal("-150.56");

    /** A refund of half a cent, the smallest value that separates the two rounding modes. */
    private static final BigDecimal HALF_CENT_REFUND = new BigDecimal("-0.005");

    /** A cycle accumulator holding zero at the stored scale. */
    private static final BigDecimal ZERO_ACCUMULATOR = new BigDecimal("0.00");

    /** The half-up accumulator after a refund of half a cent. */
    private static final BigDecimal HALF_UP_ONE_CENT_DEBIT = new BigDecimal("-0.01");

    /** A cycle credit accumulator before the add at L549. */
    private static final BigDecimal CYCLE_CREDIT_BEFORE_PURCHASE = new BigDecimal("300.00");

    /** A purchase amount, which L548 sends to the cycle credit accumulator. */
    private static final BigDecimal PURCHASE_AMOUNT = new BigDecimal("50.00");

    /** The cycle credit accumulator after the add at L549. */
    private static final BigDecimal CYCLE_CREDIT_AFTER_PURCHASE = new BigDecimal("350.00");

    /** A cycle credit accumulator used by the refund sign assertion. */
    private static final BigDecimal CYCLE_CREDIT_ONE_THOUSAND = new BigDecimal("1000.00");

    /** A refund already recorded in the cycle debit accumulator. */
    private static final BigDecimal CYCLE_DEBIT_AFTER_REFUND = new BigDecimal("-50.00");

    /** The working balance the overlimit formula yields with no refund recorded. */
    private static final BigDecimal WORKING_BALANCE_WITHOUT_REFUND = new BigDecimal("1000.00");

    /** The working balance the overlimit formula yields once a refund is recorded. */
    private static final BigDecimal WORKING_BALANCE_WITH_REFUND = new BigDecimal("1050.00");

    // ---------------------------------------------------------------------------------------
    // Sites 4 and 5. Category balance. app/cbl/CBTRN02C.cbl:L508 and L527.
    // ---------------------------------------------------------------------------------------

    /** An amount carrying a third decimal digit, for the create branch at L508. */
    private static final BigDecimal AMOUNT_WITH_TRAILING_NINE = new BigDecimal("75.999");

    /** The truncated category balance the create branch writes at L510. */
    private static final BigDecimal TRUNCATED_CREATED_CATEGORY_BALANCE = new BigDecimal("75.99");

    /** The half-up category balance from the same amount. */
    private static final BigDecimal HALF_UP_CREATED_CATEGORY_BALANCE = new BigDecimal("76.00");

    /** {@code TRAN-CAT-BAL}, {@code app/cpy/CVTRA01Y.cpy:L9}, as the update branch reads it. */
    private static final BigDecimal EXISTING_CATEGORY_BALANCE = new BigDecimal("250.50");

    /** An amount carrying a third decimal digit, for the update branch at L527. */
    private static final BigDecimal AMOUNT_WITH_TRAILING_SIX = new BigDecimal("99.996");

    /** The truncated category balance the update branch rewrites at L528. */
    private static final BigDecimal TRUNCATED_UPDATED_CATEGORY_BALANCE = new BigDecimal("350.49");

    /** The half-up category balance from the same two operands. */
    private static final BigDecimal HALF_UP_UPDATED_CATEGORY_BALANCE = new BigDecimal("350.50");

    // ---------------------------------------------------------------------------------------
    // Site 6. Bill payment. app/cbl/COBIL00C.cbl:L224 and L234.
    // ---------------------------------------------------------------------------------------

    /** The account balance the payment path reads before the move at L224. */
    private static final BigDecimal BALANCE_BEFORE_PAYMENT = new BigDecimal("1234.56");

    /** The balance the subtraction at L234 reaches. */
    private static final BigDecimal BALANCE_AFTER_PAYMENT = new BigDecimal("0.00");

    /** A payment amount carrying a third decimal digit. */
    private static final BigDecimal PAYMENT_WITH_THIRD_DECIMAL = new BigDecimal("250.005");

    /** The truncated balance after subtracting {@link #PAYMENT_WITH_THIRD_DECIMAL}. */
    private static final BigDecimal TRUNCATED_BALANCE_AFTER_PARTIAL_PAYMENT =
            new BigDecimal("749.99");

    /** The half-up balance from the same two operands. */
    private static final BigDecimal HALF_UP_BALANCE_AFTER_PARTIAL_PAYMENT =
            new BigDecimal("750.00");

    // ---------------------------------------------------------------------------------------
    // Site 7. Interest. app/cbl/CBACT04C.cbl:L464-L465, L467, and L352.
    // ---------------------------------------------------------------------------------------

    /** {@code TRAN-CAT-BAL} as the interest computation at L465 reads it. */
    private static final BigDecimal INTEREST_CATEGORY_BALANCE = new BigDecimal("1000.00");

    /** {@code DIS-INT-RATE}, {@code app/cpy/CVTRA02Y.cpy:L9}. */
    private static final BigDecimal DISCLOSURE_INTEREST_RATE = new BigDecimal("12.50");

    /** The whole product of the balance and the rate, before the divide at L465. */
    private static final BigDecimal WHOLE_INTEREST_PRODUCT = new BigDecimal("12500.0000");

    /** The truncated monthly interest the store into {@code WS-MONTHLY-INT} keeps. */
    private static final BigDecimal TRUNCATED_MONTHLY_INTEREST = new BigDecimal("10.41");

    /** The half-up monthly interest from the same quotient. */
    private static final BigDecimal HALF_UP_MONTHLY_INTEREST = new BigDecimal("10.42");

    /** A category balance that drives the quotient negative. */
    private static final BigDecimal NEGATIVE_INTEREST_CATEGORY_BALANCE = new BigDecimal("-1000.00");

    /** The truncated monthly interest on a negative balance. */
    private static final BigDecimal TRUNCATED_NEGATIVE_MONTHLY_INTEREST = new BigDecimal("-10.41");

    /** The half-up monthly interest on the same negative balance. */
    private static final BigDecimal HALF_UP_NEGATIVE_MONTHLY_INTEREST = new BigDecimal("-10.42");

    /** A category balance whose product with a rate carries a third decimal digit. */
    private static final BigDecimal CATEGORY_BALANCE_ODD_PRODUCT = new BigDecimal("500.05");

    /** A rate whose product with {@link #CATEGORY_BALANCE_ODD_PRODUCT} ends in a five. */
    private static final BigDecimal RATE_ODD_PRODUCT = new BigDecimal("15.15");

    /** The truncated product of the previous two operands. */
    private static final BigDecimal TRUNCATED_ODD_PRODUCT = new BigDecimal("7575.75");

    /** The half-up product of the same two operands. */
    private static final BigDecimal HALF_UP_ODD_PRODUCT = new BigDecimal("7575.76");

    /** {@code WS-TOTAL-INT}, {@code app/cbl/CBACT04C.cbl:L169}, before the add at L467. */
    private static final BigDecimal TOTAL_INTEREST_BEFORE_ADD = new BigDecimal("0.00");

    /** {@code WS-TOTAL-INT} after the add at L467. */
    private static final BigDecimal TOTAL_INTEREST_AFTER_ADD = new BigDecimal("10.41");

    /** {@code ACCT-CURR-BAL} after the add at L352. */
    private static final BigDecimal ACCOUNT_BALANCE_AFTER_INTEREST = new BigDecimal("1010.41");

    /** The integer divisor the source writes at {@code app/cbl/CBACT04C.cbl:L465}. */
    private static final BigDecimal EXPECTED_INTEREST_DIVISOR = new BigDecimal("1200");

    /** The scale that keeps the whole product of a balance and a rate. */
    private static final int WHOLE_PRODUCT_SCALE =
            PicClause.TRAN_CAT_BAL_SCALE + PicClause.DIS_INT_RATE_SCALE;

    // ---------------------------------------------------------------------------------------
    // Precision narrowings. app/cbl/CBTRN02C.cbl:L187 and app/cbl/CBACT04C.cbl:L168-L169.
    // ---------------------------------------------------------------------------------------

    /** A working balance one hundred above one billion, past what nine integer digits hold. */
    private static final BigDecimal WORKING_BALANCE_PAST_ONE_BILLION =
            new BigDecimal("1000000100.00");

    /** The value a {@code PIC S9(09)V99} field holds after the high-order digit drops. */
    private static final BigDecimal NARROWED_WORKING_BALANCE = new BigDecimal("100.00");

    /** A working balance whose ten integer digits all differ, showing which digit drops. */
    private static final BigDecimal WORKING_BALANCE_TEN_DISTINCT_DIGITS =
            new BigDecimal("1234567890.99");

    /** The nine low-order integer digits the narrowed field keeps. */
    private static final BigDecimal NARROWED_TEN_DISTINCT_DIGITS = new BigDecimal("234567890.99");

    /** The negative counterpart of {@link #WORKING_BALANCE_PAST_ONE_BILLION}. */
    private static final BigDecimal NEGATIVE_BALANCE_PAST_ONE_BILLION =
            new BigDecimal("-1000000100.00");

    /** The narrowed negative working balance, sign kept. */
    private static final BigDecimal NARROWED_NEGATIVE_WORKING_BALANCE = new BigDecimal("-100.00");

    /** The largest magnitude nine integer digits and two decimals hold. */
    private static final BigDecimal LARGEST_VALUE_THAT_FITS = new BigDecimal("999999999.99");

    /** A total interest figure past what {@code WS-TOTAL-INT} holds. */
    private static final BigDecimal TOTAL_INTEREST_PAST_ONE_BILLION =
            new BigDecimal("1000000000.00");

    /** The value {@code WS-TOTAL-INT} holds once the high-order digit drops. */
    private static final BigDecimal NARROWED_TOTAL_INTEREST = new BigDecimal("0.00");

    /** Integer digits in {@code PIC S9(09)V99}, the width of the three narrowed working fields. */
    private static final int NARROWED_INTEGER_DIGITS = 9;

    /** Integer digits in {@code PIC S9(10)V99}, the width of the five account money fields. */
    private static final int ACCOUNT_INTEGER_DIGITS = 10;

    /** Integer digits the three working fields give up against the account money fields. */
    private static final int NARROWING_IN_INTEGER_DIGITS = 1;

    // ---------------------------------------------------------------------------------------
    // Scale-helper operands, used by the two mode-agnostic truncation assertions.
    // ---------------------------------------------------------------------------------------

    /** A positive value carrying a third decimal digit of seven. */
    private static final BigDecimal POSITIVE_THREE_DECIMALS = new BigDecimal("1234.567");

    /** The truncated form of {@link #POSITIVE_THREE_DECIMALS}. */
    private static final BigDecimal TRUNCATED_POSITIVE = new BigDecimal("1234.56");

    /** The half-up form of {@link #POSITIVE_THREE_DECIMALS}. */
    private static final BigDecimal HALF_UP_POSITIVE = new BigDecimal("1234.57");

    /** The negative counterpart of {@link #POSITIVE_THREE_DECIMALS}. */
    private static final BigDecimal NEGATIVE_THREE_DECIMALS = new BigDecimal("-1234.567");

    /** The truncated form of {@link #NEGATIVE_THREE_DECIMALS}, closer to zero. */
    private static final BigDecimal TRUNCATED_NEGATIVE = new BigDecimal("-1234.56");

    /** The half-up form of {@link #NEGATIVE_THREE_DECIMALS}, further from zero. */
    private static final BigDecimal HALF_UP_NEGATIVE = new BigDecimal("-1234.57");

    // ---------------------------------------------------------------------------------------
    // Processing timestamp. app/cbl/CBTRN02C.cbl:L159-L174, L692-L705, and L438.
    // ---------------------------------------------------------------------------------------

    /** Nanoseconds in one hundredth of a second. */
    private static final int NANOSECONDS_PER_HUNDREDTH = 10_000_000;

    /** A moment whose nanosecond field lands exactly on a hundredth. */
    private static final LocalDateTime MOMENT_ON_A_HUNDREDTH =
            LocalDateTime.of(2022, 7, 19, 23, 15, 59, 87 * NANOSECONDS_PER_HUNDREDTH);

    /** The rendered form of {@link #MOMENT_ON_A_HUNDREDTH}. */
    private static final String RENDERED_MOMENT_ON_A_HUNDREDTH = "2022-07-19-23.15.59.870000";

    /** A moment one nanosecond short of the next hundredth. */
    private static final LocalDateTime MOMENT_JUST_BELOW_THE_NEXT_HUNDREDTH =
            LocalDateTime.of(2022, 7, 19, 23, 15, 59, 879_999_999);

    /** A moment five milliseconds past the second, half of one hundredth. */
    private static final LocalDateTime MOMENT_FIVE_MILLISECONDS_PAST_THE_SECOND =
            LocalDateTime.of(2022, 7, 19, 23, 15, 59, 5_000_000);

    /** The rendered form of {@link #MOMENT_FIVE_MILLISECONDS_PAST_THE_SECOND}. */
    private static final String RENDERED_MOMENT_FIVE_MILLISECONDS = "2022-07-19-23.15.59.000000";

    /** A moment one nanosecond short of the next second. */
    private static final LocalDateTime MOMENT_JUST_BELOW_THE_NEXT_SECOND =
            LocalDateTime.of(2022, 7, 19, 23, 15, 59, 999_999_999);

    /** The rendered form of {@link #MOMENT_JUST_BELOW_THE_NEXT_SECOND}. */
    private static final String RENDERED_MOMENT_JUST_BELOW_THE_NEXT_SECOND =
            "2022-07-19-23.15.59.990000";

    /** A moment whose month, day, hour, minute, second, and fraction each need padding. */
    private static final LocalDateTime MOMENT_NEEDING_PADDING =
            LocalDateTime.of(2022, 1, 2, 3, 4, 5, 6 * NANOSECONDS_PER_HUNDREDTH);

    /** The rendered form of {@link #MOMENT_NEEDING_PADDING}. */
    private static final String RENDERED_MOMENT_NEEDING_PADDING = "2022-01-02-03.04.05.060000";

    /** The 1-indexed offset of the dash separating the day from the hour. */
    private static final int ONE_INDEXED_DAY_HOUR_DASH_OFFSET = 11;

    /** The 1-indexed offsets of the three dashes, from the redefine at L162, L164, and L166. */
    private static final int[] ONE_INDEXED_DASH_OFFSETS = {5, 8, ONE_INDEXED_DAY_HOUR_DASH_OFFSET};

    /** The 1-indexed offsets of the three dots, from the redefine at L168, L170, and L172. */
    private static final int[] ONE_INDEXED_DOT_OFFSETS = {14, 17, 20};

    /** The two fraction digits {@link #MOMENT_ON_A_HUNDREDTH} renders. */
    private static final String EXPECTED_HUNDREDTHS = "87";

    /** The largest fraction two digits hold. */
    private static final String LARGEST_HUNDREDTHS = "99";

    /** The second component of every moment that sits on second 59. */
    private static final String SECOND_COMPONENT_FIFTY_NINE = "59";

    /** The year every moment in this class renders. */
    private static final String RENDERED_YEAR = "2022";

    /** The character each padded timestamp component opens with. */
    private static final char PADDING_DIGIT = '0';

    /**
     * Site 1. Asserts that the overlimit working balance truncates toward zero at each step of
     * {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT},
     * {@code app/cbl/CBTRN02C.cbl:L403-L405}. Half-up rounding lifts both steps by one cent.
     */
    @Test
    void overlimitWorkingBalanceTruncatesTowardZeroAndHalfUpDiffers() {
        BigDecimal difference = CobolDecimal.subtract(CYCLE_CREDIT_THREE_DECIMALS,
                CYCLE_DEBIT_THREE_DECIMALS, PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(TRUNCATED_CYCLE_DIFFERENCE, difference);
        assertEquals(HALF_UP_CYCLE_DIFFERENCE,
                CYCLE_CREDIT_THREE_DECIMALS.subtract(CYCLE_DEBIT_THREE_DECIMALS)
                        .setScale(PicClause.WS_TEMP_BAL_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_CYCLE_DIFFERENCE, difference);

        BigDecimal workingBalance = CobolDecimal.add(difference, AMOUNT_THREE_DECIMALS,
                PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(TRUNCATED_WORKING_BALANCE, workingBalance);
        assertEquals(HALF_UP_WORKING_BALANCE, difference.add(AMOUNT_THREE_DECIMALS)
                .setScale(PicClause.WS_TEMP_BAL_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_WORKING_BALANCE, workingBalance);
        assertEquals(PicClause.WS_TEMP_BAL_SCALE, workingBalance.scale());
    }

    /**
     * Site 1. Asserts the two outcomes of {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} at
     * {@code app/cbl/CBTRN02C.cbl:L407}. The credit limit covers one working balance and falls
     * short of the other, which reaches {@code MOVE 102} at
     * {@code app/cbl/CBTRN02C.cbl:L410}.
     */
    @Test
    void creditLimitComparisonCoversOneWorkingBalanceAndRejectsTheOther() {
        BigDecimal insideLimit = CobolDecimal.add(
                CobolDecimal.subtract(CYCLE_CREDIT, CYCLE_DEBIT, PicClause.WS_TEMP_BAL_SCALE),
                AMOUNT_INSIDE_LIMIT, PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(WORKING_BALANCE_INSIDE_LIMIT, insideLimit);
        assertTrue(CREDIT_LIMIT.compareTo(insideLimit) >= 0,
                "credit limit stopped covering the working balance: " + insideLimit);

        BigDecimal overLimit = CobolDecimal.add(
                CobolDecimal.subtract(CYCLE_CREDIT, CYCLE_DEBIT, PicClause.WS_TEMP_BAL_SCALE),
                AMOUNT_OVER_LIMIT, PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(WORKING_BALANCE_OVER_LIMIT, overLimit);
        assertFalse(CREDIT_LIMIT.compareTo(overLimit) >= 0,
                "credit limit covered an overlimit working balance: " + overLimit);
    }

    /**
     * Site 2. Asserts that {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL} at
     * {@code app/cbl/CBTRN02C.cbl:L547} truncates toward zero. Half-up rounding adds a cent the
     * source never adds.
     */
    @Test
    void accountBalanceAddTruncatesTowardZeroAndHalfUpDiffers() {
        BigDecimal posted = CobolDecimal.add(ACCOUNT_BALANCE, AMOUNT_WITH_TRAILING_FIVE,
                PicClause.ACCT_CURR_BAL_SCALE);

        assertEquals(TRUNCATED_ACCOUNT_BALANCE, posted);
        assertEquals(HALF_UP_ACCOUNT_BALANCE, ACCOUNT_BALANCE.add(AMOUNT_WITH_TRAILING_FIVE)
                .setScale(PicClause.ACCT_CURR_BAL_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_ACCOUNT_BALANCE, posted);

        assertEquals(ACCOUNT_BALANCE_AFTER_SCALED_AMOUNT, CobolDecimal.add(ACCOUNT_BALANCE,
                AMOUNT_INSIDE_LIMIT, PicClause.ACCT_CURR_BAL_SCALE));
    }

    /**
     * Site 3. Asserts that a negative amount reaches the cycle debit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L551} and truncates toward zero. The truncated accumulator
     * holds the smaller magnitude; half-up rounding drives it one cent further from zero.
     */
    @Test
    void negativeAmountInTheCycleDebitAccumulatorTruncatesTowardZeroAndHalfUpDiffers() {
        BigDecimal cycleDebit = CobolDecimal.add(NEGATIVE_CYCLE_DEBIT,
                NEGATIVE_AMOUNT_THREE_DECIMALS, PicClause.ACCT_CURR_CYC_DEBIT_SCALE);

        assertEquals(TRUNCATED_CYCLE_DEBIT, cycleDebit);
        assertEquals(HALF_UP_CYCLE_DEBIT,
                NEGATIVE_CYCLE_DEBIT.add(NEGATIVE_AMOUNT_THREE_DECIMALS)
                        .setScale(PicClause.ACCT_CURR_CYC_DEBIT_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_CYCLE_DEBIT, cycleDebit);
        assertTrue(cycleDebit.abs().compareTo(HALF_UP_CYCLE_DEBIT.abs()) < 0,
                "truncation grew the magnitude of the cycle debit accumulator: " + cycleDebit);

        BigDecimal halfCentDebit = CobolDecimal.add(ZERO_ACCUMULATOR, HALF_CENT_REFUND,
                PicClause.ACCT_CURR_CYC_DEBIT_SCALE);

        assertEquals(ZERO_ACCUMULATOR, halfCentDebit);
        assertEquals(HALF_UP_ONE_CENT_DEBIT, ZERO_ACCUMULATOR.add(HALF_CENT_REFUND)
                .setScale(PicClause.ACCT_CURR_CYC_DEBIT_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_ONE_CENT_DEBIT, halfCentDebit);
    }

    /**
     * Site 3. Asserts that a non-negative amount reaches the cycle credit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L549}, the branch {@code IF DALYTRAN-AMT >= 0} at
     * {@code app/cbl/CBTRN02C.cbl:L548} selects. Zero takes the same branch.
     */
    @Test
    void nonNegativeAmountAddsToTheCycleCreditAccumulator() {
        assertTrue(PURCHASE_AMOUNT.compareTo(BigDecimal.ZERO) >= 0,
                "the purchase amount stopped selecting the cycle credit branch");

        assertEquals(CYCLE_CREDIT_AFTER_PURCHASE, CobolDecimal.add(CYCLE_CREDIT_BEFORE_PURCHASE,
                PURCHASE_AMOUNT, PicClause.ACCT_CURR_CYC_CREDIT_SCALE));

        assertTrue(ZERO_ACCUMULATOR.compareTo(BigDecimal.ZERO) >= 0,
                "zero stopped selecting the cycle credit branch");
        assertEquals(CYCLE_CREDIT_BEFORE_PURCHASE, CobolDecimal.add(CYCLE_CREDIT_BEFORE_PURCHASE,
                ZERO_ACCUMULATOR, PicClause.ACCT_CURR_CYC_CREDIT_SCALE));
    }

    /**
     * Site 3. Asserts that a refund recorded in the cycle debit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L551} raises the working balance the overlimit formula
     * computes, since {@code app/cbl/CBTRN02C.cbl:L404} subtracts that accumulator. The source
     * behaviour is reproduced here and carried unchanged.
     */
    @Test
    void refundInTheCycleDebitAccumulatorRaisesTheOverlimitWorkingBalance() {
        BigDecimal withoutRefund = CobolDecimal.subtract(CYCLE_CREDIT_ONE_THOUSAND,
                ZERO_ACCUMULATOR, PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(WORKING_BALANCE_WITHOUT_REFUND, withoutRefund);

        BigDecimal withRefund = CobolDecimal.subtract(CYCLE_CREDIT_ONE_THOUSAND,
                CYCLE_DEBIT_AFTER_REFUND, PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(WORKING_BALANCE_WITH_REFUND, withRefund);
        assertTrue(withRefund.compareTo(withoutRefund) > 0,
                "the refund stopped raising the working balance: " + withRefund);
    }

    /**
     * Site 4. Asserts that {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at
     * {@code app/cbl/CBTRN02C.cbl:L508} truncates toward zero. The paragraph at
     * {@code app/cbl/CBTRN02C.cbl:L503} runs {@code INITIALIZE TRAN-CAT-BAL-RECORD} at L504, so
     * the add starts from zero and the record reaches {@code WRITE} at L510.
     */
    @Test
    void categoryBalanceCreateBranchTruncatesTowardZeroAndHalfUpDiffers() {
        BigDecimal created = CobolDecimal.add(BigDecimal.ZERO, AMOUNT_WITH_TRAILING_NINE,
                PicClause.TRAN_CAT_BAL_SCALE);

        assertEquals(TRUNCATED_CREATED_CATEGORY_BALANCE, created);
        assertEquals(HALF_UP_CREATED_CATEGORY_BALANCE, BigDecimal.ZERO
                .add(AMOUNT_WITH_TRAILING_NINE)
                .setScale(PicClause.TRAN_CAT_BAL_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_CREATED_CATEGORY_BALANCE, created);
        assertEquals(PicClause.TRAN_CAT_BAL_SCALE, created.scale());
    }

    /**
     * Site 5. Asserts that the same {@code ADD} at {@code app/cbl/CBTRN02C.cbl:L527} truncates
     * toward zero when the paragraph at {@code app/cbl/CBTRN02C.cbl:L526} adds to a category
     * balance already on file and rewrites it at L528.
     */
    @Test
    void categoryBalanceUpdateBranchTruncatesTowardZeroAndHalfUpDiffers() {
        BigDecimal updated = CobolDecimal.add(EXISTING_CATEGORY_BALANCE, AMOUNT_WITH_TRAILING_SIX,
                PicClause.TRAN_CAT_BAL_SCALE);

        assertEquals(TRUNCATED_UPDATED_CATEGORY_BALANCE, updated);
        assertEquals(HALF_UP_UPDATED_CATEGORY_BALANCE, EXISTING_CATEGORY_BALANCE
                .add(AMOUNT_WITH_TRAILING_SIX)
                .setScale(PicClause.TRAN_CAT_BAL_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_UPDATED_CATEGORY_BALANCE, updated);
    }

    /**
     * Site 6. Asserts that the online payment path reaches a zero balance and leaves both cycle
     * accumulators as it found them. {@code app/cbl/COBIL00C.cbl:L224} moves the whole balance
     * into {@code TRAN-AMT}, and {@code app/cbl/COBIL00C.cbl:L234} subtracts that amount. The
     * text {@code ACCT-CURR-CYC} appears zero times in that program.
     */
    @Test
    void billPaymentSubtractReachesZeroAndLeavesBothCycleAccumulatorsUntouched() {
        BigDecimal cycleCreditBeforePayment = CYCLE_CREDIT;
        BigDecimal cycleDebitBeforePayment = CYCLE_DEBIT;

        BigDecimal paymentAmount = CobolDecimal.truncateToPictureField(BALANCE_BEFORE_PAYMENT,
                PicClause.TRAN_AMT_PRECISION, PicClause.TRAN_AMT_SCALE);

        assertEquals(BALANCE_BEFORE_PAYMENT, paymentAmount);

        BigDecimal balanceAfter = CobolDecimal.subtract(BALANCE_BEFORE_PAYMENT, paymentAmount,
                PicClause.ACCT_CURR_BAL_SCALE);

        assertEquals(BALANCE_AFTER_PAYMENT, balanceAfter);
        assertEquals(0, balanceAfter.signum());
        assertEquals(PicClause.ACCT_CURR_BAL_SCALE, balanceAfter.scale());

        assertEquals(cycleCreditBeforePayment, CYCLE_CREDIT,
                "the payment path moved the cycle credit accumulator");
        assertEquals(cycleDebitBeforePayment, CYCLE_DEBIT,
                "the payment path moved the cycle debit accumulator");

        BigDecimal partialPayment = CobolDecimal.subtract(ACCOUNT_BALANCE,
                PAYMENT_WITH_THIRD_DECIMAL, PicClause.ACCT_CURR_BAL_SCALE);

        assertEquals(TRUNCATED_BALANCE_AFTER_PARTIAL_PAYMENT, partialPayment);
        assertEquals(HALF_UP_BALANCE_AFTER_PARTIAL_PAYMENT, ACCOUNT_BALANCE
                .subtract(PAYMENT_WITH_THIRD_DECIMAL)
                .setScale(PicClause.ACCT_CURR_BAL_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_BALANCE_AFTER_PARTIAL_PAYMENT, partialPayment);
    }

    /**
     * Site 7. Asserts that {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
     * at {@code app/cbl/CBACT04C.cbl:L464-L465} truncates toward zero. The quotient has no
     * terminating decimal expansion, and half-up rounding lifts it by one cent on both signs.
     */
    @Test
    void interestMultiplyThenDivideTruncatesTowardZeroAndHalfUpDiffers() {
        assertEquals(EXPECTED_INTEREST_DIVISOR, CobolDecimal.INTEREST_DIVISOR);

        BigDecimal monthlyInterest = CobolDecimal.multiplyThenDivide(INTEREST_CATEGORY_BALANCE,
                DISCLOSURE_INTEREST_RATE, CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE);

        assertEquals(TRUNCATED_MONTHLY_INTEREST, monthlyInterest);
        assertEquals(HALF_UP_MONTHLY_INTEREST, INTEREST_CATEGORY_BALANCE
                .multiply(DISCLOSURE_INTEREST_RATE)
                .divide(CobolDecimal.INTEREST_DIVISOR, PicClause.WS_MONTHLY_INT_SCALE,
                        RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_MONTHLY_INTEREST, monthlyInterest);

        BigDecimal negativeInterest = CobolDecimal.multiplyThenDivide(
                NEGATIVE_INTEREST_CATEGORY_BALANCE, DISCLOSURE_INTEREST_RATE,
                CobolDecimal.INTEREST_DIVISOR, PicClause.WS_MONTHLY_INT_SCALE);

        assertEquals(TRUNCATED_NEGATIVE_MONTHLY_INTEREST, negativeInterest);
        assertEquals(HALF_UP_NEGATIVE_MONTHLY_INTEREST, NEGATIVE_INTEREST_CATEGORY_BALANCE
                .multiply(DISCLOSURE_INTEREST_RATE)
                .divide(CobolDecimal.INTEREST_DIVISOR, PicClause.WS_MONTHLY_INT_SCALE,
                        RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_NEGATIVE_MONTHLY_INTEREST, negativeInterest);
    }

    /**
     * Site 7. Asserts that the multiply keeps the whole product and the divide truncates toward
     * zero, the two halves of {@code app/cbl/CBACT04C.cbl:L464-L465}. A separate operand pair
     * shows the multiply truncating on its own.
     */
    @Test
    void interestMultiplyKeepsTheProductAndDivideTruncatesTowardZeroAndHalfUpDiffers() {
        BigDecimal wholeProduct = CobolDecimal.multiply(INTEREST_CATEGORY_BALANCE,
                DISCLOSURE_INTEREST_RATE, WHOLE_PRODUCT_SCALE);

        assertEquals(WHOLE_INTEREST_PRODUCT, wholeProduct);

        BigDecimal quotient = CobolDecimal.divide(wholeProduct, CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE);

        assertEquals(TRUNCATED_MONTHLY_INTEREST, quotient);
        assertEquals(HALF_UP_MONTHLY_INTEREST, wholeProduct.divide(CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_MONTHLY_INTEREST, quotient);

        BigDecimal oddProduct = CobolDecimal.multiply(CATEGORY_BALANCE_ODD_PRODUCT,
                RATE_ODD_PRODUCT, PicClause.TRAN_CAT_BAL_SCALE);

        assertEquals(TRUNCATED_ODD_PRODUCT, oddProduct);
        assertEquals(HALF_UP_ODD_PRODUCT, CATEGORY_BALANCE_ODD_PRODUCT.multiply(RATE_ODD_PRODUCT)
                .setScale(PicClause.TRAN_CAT_BAL_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_ODD_PRODUCT, oddProduct);
    }

    /**
     * Site 7. Asserts the two accumulating adds that carry interest to the account balance.
     * {@code app/cbl/CBACT04C.cbl:L467} adds the monthly figure to {@code WS-TOTAL-INT}, and
     * {@code app/cbl/CBACT04C.cbl:L352} adds that total to {@code ACCT-CURR-BAL}.
     */
    @Test
    void monthlyInterestAccumulatesIntoTotalInterestThenIntoTheAccountBalance() {
        BigDecimal totalInterest = CobolDecimal.add(TOTAL_INTEREST_BEFORE_ADD,
                TRUNCATED_MONTHLY_INTEREST, PicClause.WS_TOTAL_INT_SCALE);

        assertEquals(TOTAL_INTEREST_AFTER_ADD, totalInterest);

        BigDecimal balanceWithInterest = CobolDecimal.add(ACCOUNT_BALANCE, totalInterest,
                PicClause.ACCT_CURR_BAL_SCALE);

        assertEquals(ACCOUNT_BALANCE_AFTER_INTEREST, balanceWithInterest);
        assertEquals(PicClause.ACCT_CURR_BAL_SCALE, balanceWithInterest.scale());
    }

    /**
     * Asserts that the scale helper truncates toward zero on both signs, and pins the two values
     * {@link RoundingMode#HALF_UP} returns for the same operands. Every monetary store in the
     * source drops the digits past the scale its Picture clause fixes, among them
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}.
     */
    @Test
    void truncateToScaleTruncatesTowardZeroOnBothSignsAndHalfUpDiffers() {
        BigDecimal positive = CobolDecimal.truncateToScale(POSITIVE_THREE_DECIMALS,
                PicClause.TRAN_AMT_SCALE);

        assertEquals(TRUNCATED_POSITIVE, positive);
        assertEquals(HALF_UP_POSITIVE, POSITIVE_THREE_DECIMALS
                .setScale(PicClause.TRAN_AMT_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_POSITIVE, positive);

        BigDecimal negative = CobolDecimal.truncateToScale(NEGATIVE_THREE_DECIMALS,
                PicClause.TRAN_AMT_SCALE);

        assertEquals(TRUNCATED_NEGATIVE, negative);
        assertEquals(HALF_UP_NEGATIVE, NEGATIVE_THREE_DECIMALS
                .setScale(PicClause.TRAN_AMT_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_NEGATIVE, negative);
    }

    /**
     * Asserts that truncation shrinks a magnitude and never grows one, on positive and negative
     * operands alike, and that no result crosses zero. A rounding mode that carries a value away
     * from zero on either sign fails this method, whichever mode it is.
     *
     * <p>The operands take the scale of {@code TRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy:L10}. No program in {@code app/cbl/} writes the {@code ROUNDED}
     * phrase, so no store in the source moves a value away from zero.
     */
    @Test
    void truncationNeverGrowsTheMagnitudeOfAValue() {
        BigDecimal[] operands = {
                CYCLE_CREDIT_THREE_DECIMALS,
                NEGATIVE_THREE_DECIMALS,
                AMOUNT_WITH_TRAILING_NINE,
                NEGATIVE_AMOUNT_THREE_DECIMALS,
                HALF_CENT_REFUND,
                HALF_CENT_REFUND.negate(),
        };

        for (BigDecimal operand : operands) {
            BigDecimal truncated = CobolDecimal.truncateToScale(operand, PicClause.TRAN_AMT_SCALE);

            assertTrue(truncated.abs().compareTo(operand.abs()) <= 0,
                    "truncation grew the magnitude of " + operand + " to " + truncated);
            assertTrue(truncated.signum() * operand.signum() >= 0,
                    "truncation carried " + operand + " across zero to " + truncated);
            assertEquals(PicClause.TRAN_AMT_SCALE, truncated.scale(),
                    "truncation returned an unexpected scale for " + operand);
        }
    }

    /**
     * Asserts that {@code WS-TEMP-BAL}, declared {@code PIC S9(09)V99} at
     * {@code app/cbl/CBTRN02C.cbl:L187}, holds one fewer integer digit than the three account
     * fields the overlimit formula reads. Those three are {@code ACCT-CREDIT-LIMIT} at
     * {@code app/cpy/CVACT01Y.cpy:L8}, {@code ACCT-CURR-CYC-CREDIT} at
     * {@code app/cpy/CVACT01Y.cpy:L13}, and {@code ACCT-CURR-CYC-DEBIT} at
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    @Test
    void workingBalanceFieldHoldsOneFewerIntegerDigitThanTheCycleAccumulators() {
        assertEquals(NARROWED_INTEGER_DIGITS,
                PicClause.WS_TEMP_BAL_PRECISION - PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(ACCOUNT_INTEGER_DIGITS,
                PicClause.ACCT_CREDIT_LIMIT_PRECISION - PicClause.ACCT_CREDIT_LIMIT_SCALE);
        assertEquals(ACCOUNT_INTEGER_DIGITS,
                PicClause.ACCT_CURR_CYC_CREDIT_PRECISION - PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
        assertEquals(ACCOUNT_INTEGER_DIGITS,
                PicClause.ACCT_CURR_CYC_DEBIT_PRECISION - PicClause.ACCT_CURR_CYC_DEBIT_SCALE);

        assertEquals(NARROWING_IN_INTEGER_DIGITS,
                ACCOUNT_INTEGER_DIGITS - NARROWED_INTEGER_DIGITS);
        assertEquals(PicClause.ACCT_CURR_CYC_CREDIT_SCALE, PicClause.WS_TEMP_BAL_SCALE);
    }

    /**
     * Asserts that a working balance past one billion loses its high-order digit, and that the
     * comparison at {@code app/cbl/CBTRN02C.cbl:L407} then covers a value the full magnitude
     * would exceed. The narrowing is reproduced at the width
     * {@code app/cbl/CBTRN02C.cbl:L187} declares, and no method widens the field.
     */
    @Test
    void narrowedWorkingBalanceDropsTheHighOrderDigitPastOneBillion() {
        BigDecimal narrowed = CobolDecimal.truncateToPictureField(
                WORKING_BALANCE_PAST_ONE_BILLION, PicClause.WS_TEMP_BAL_PRECISION,
                PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(NARROWED_WORKING_BALANCE, narrowed);

        assertFalse(CREDIT_LIMIT.compareTo(WORKING_BALANCE_PAST_ONE_BILLION) >= 0,
                "the credit limit covered the full working balance: "
                        + WORKING_BALANCE_PAST_ONE_BILLION);
        assertTrue(CREDIT_LIMIT.compareTo(narrowed) >= 0,
                "the credit limit stopped covering the narrowed working balance: " + narrowed);

        assertEquals(NARROWED_TEN_DISTINCT_DIGITS, CobolDecimal.truncateToPictureField(
                WORKING_BALANCE_TEN_DISTINCT_DIGITS, PicClause.WS_TEMP_BAL_PRECISION,
                PicClause.WS_TEMP_BAL_SCALE));
    }

    /**
     * Asserts that the narrowed field passes through a value it holds and keeps the sign of one
     * it does not. The field is {@code WS-TEMP-BAL}, declared {@code PIC S9(09)V99} at
     * {@code app/cbl/CBTRN02C.cbl:L187}, and the largest magnitude it carries survives unchanged.
     */
    @Test
    void narrowedFieldKeepsAFittingValueWholeAndKeepsTheSignOfOneThatOverflows() {
        assertEquals(LARGEST_VALUE_THAT_FITS, CobolDecimal.truncateToPictureField(
                LARGEST_VALUE_THAT_FITS, PicClause.WS_TEMP_BAL_PRECISION,
                PicClause.WS_TEMP_BAL_SCALE));

        assertEquals(LARGEST_VALUE_THAT_FITS.negate(), CobolDecimal.truncateToPictureField(
                LARGEST_VALUE_THAT_FITS.negate(), PicClause.WS_TEMP_BAL_PRECISION,
                PicClause.WS_TEMP_BAL_SCALE));

        BigDecimal narrowedNegative = CobolDecimal.truncateToPictureField(
                NEGATIVE_BALANCE_PAST_ONE_BILLION, PicClause.WS_TEMP_BAL_PRECISION,
                PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(NARROWED_NEGATIVE_WORKING_BALANCE, narrowedNegative);
        assertEquals(-1, narrowedNegative.signum());
    }

    /**
     * Asserts that {@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT}, both declared
     * {@code PIC S9(09)V99} at {@code app/cbl/CBACT04C.cbl:L168-L169}, hold one fewer integer
     * digit than {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:L7}, the field the add at
     * {@code app/cbl/CBACT04C.cbl:L352} carries them into.
     */
    @Test
    void interestWorkingFieldsHoldOneFewerIntegerDigitThanTheAccountBalance() {
        assertEquals(NARROWED_INTEGER_DIGITS,
                PicClause.WS_MONTHLY_INT_PRECISION - PicClause.WS_MONTHLY_INT_SCALE);
        assertEquals(NARROWED_INTEGER_DIGITS,
                PicClause.WS_TOTAL_INT_PRECISION - PicClause.WS_TOTAL_INT_SCALE);
        assertEquals(ACCOUNT_INTEGER_DIGITS,
                PicClause.ACCT_CURR_BAL_PRECISION - PicClause.ACCT_CURR_BAL_SCALE);

        assertEquals(NARROWED_TOTAL_INTEREST, CobolDecimal.truncateToPictureField(
                TOTAL_INTEREST_PAST_ONE_BILLION, PicClause.WS_TOTAL_INT_PRECISION,
                PicClause.WS_TOTAL_INT_SCALE));
    }

    /**
     * Asserts that every rendered processing timestamp is 26 characters wide, the width
     * {@code DB2-FORMAT-TS PIC X(26)} declares at {@code app/cbl/CBTRN02C.cbl:L159} and the width
     * {@code TRAN-PROC-TS} receives at {@code app/cbl/CBTRN02C.cbl:L438}. Each moment below is a
     * fixed value, so no assertion here reads the system clock.
     */
    @Test
    void processingTimestampIsTwentySixCharactersWide() {
        LocalDateTime[] moments = {
                MOMENT_ON_A_HUNDREDTH,
                MOMENT_JUST_BELOW_THE_NEXT_HUNDREDTH,
                MOMENT_FIVE_MILLISECONDS_PAST_THE_SECOND,
                MOMENT_JUST_BELOW_THE_NEXT_SECOND,
                MOMENT_NEEDING_PADDING,
        };

        for (LocalDateTime moment : moments) {
            String rendered = CobolDecimal.formatProcessingTimestamp(moment);

            assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, rendered.length(),
                    "rendered width changed for " + moment + ": " + rendered);
        }

        assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH,
                PicClause.PROCESSING_TIMESTAMP_SHAPE.length());
        assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, PicClause.TRAN_PROC_TS_WIDTH);
    }

    /**
     * Asserts that a dash separates the day from the hour at 1-indexed offset 11, the position
     * {@code DB2-STREEP-3} occupies in the redefine at {@code app/cbl/CBTRN02C.cbl:L166}. The
     * three dashes land at 1-indexed offsets 5, 8, and 11, and the three dots at 14, 17, and 20.
     */
    @Test
    void processingTimestampCarriesADashAtOneIndexedOffsetEleven() {
        String rendered = CobolDecimal.formatProcessingTimestamp(MOMENT_ON_A_HUNDREDTH);

        assertEquals(RENDERED_MOMENT_ON_A_HUNDREDTH, rendered);

        assertEquals(PicClause.PROCESSING_TIMESTAMP_DASH,
                rendered.charAt(ONE_INDEXED_DAY_HOUR_DASH_OFFSET - 1),
                "the day and the hour stopped being separated by a dash: " + rendered);
        assertEquals(ONE_INDEXED_DAY_HOUR_DASH_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_HOUR_OFFSET,
                "the dash stopped sitting immediately before the hour");

        for (int offset : ONE_INDEXED_DASH_OFFSETS) {
            assertEquals(PicClause.PROCESSING_TIMESTAMP_DASH, rendered.charAt(offset - 1),
                    "offset " + offset + " stopped holding a dash: " + rendered);
        }

        for (int offset : ONE_INDEXED_DOT_OFFSETS) {
            assertEquals(PicClause.PROCESSING_TIMESTAMP_DOT, rendered.charAt(offset - 1),
                    "offset " + offset + " stopped holding a dot: " + rendered);
        }
    }

    /**
     * Asserts that the rendered timestamp keeps two significant fraction digits and closes with
     * four literal zeros. {@code app/cbl/CBTRN02C.cbl:L700} moves the two-character hundredths
     * field into {@code DB2-MIL}, and {@code app/cbl/CBTRN02C.cbl:L701} moves {@code '0000'} into
     * {@code DB2-REST}.
     */
    @Test
    void processingTimestampKeepsHundredthsAndEndsWithFourZeros() {
        String rendered = CobolDecimal.formatProcessingTimestamp(MOMENT_ON_A_HUNDREDTH);

        assertEquals(RENDERED_MOMENT_ON_A_HUNDREDTH, rendered);

        String fraction = rendered.substring(PicClause.PROCESSING_TIMESTAMP_FRACTION_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET);

        assertEquals(EXPECTED_HUNDREDTHS, fraction);
        assertEquals(PicClause.PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS, fraction.length());

        String trailing = rendered.substring(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET);

        assertEquals(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS, trailing);
        assertEquals(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZERO_DIGITS, trailing.length());
        assertTrue(rendered.endsWith(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS),
                "the rendered timestamp stopped closing with four zeros: " + rendered);
    }

    /**
     * Asserts that a fraction below one hundredth of a second drops. One nanosecond short of the
     * next hundredth still renders the lower hundredth, five milliseconds render as zero, and one
     * nanosecond short of the next second renders 99. The field holds two digits, declared
     * {@code DB2-MIL PIC 9(002)} at {@code app/cbl/CBTRN02C.cbl:L173}.
     */
    @Test
    void processingTimestampTruncatesASubHundredthFractionAndNeverRoundsIt() {
        assertEquals(RENDERED_MOMENT_ON_A_HUNDREDTH,
                CobolDecimal.formatProcessingTimestamp(MOMENT_JUST_BELOW_THE_NEXT_HUNDREDTH));

        assertEquals(RENDERED_MOMENT_FIVE_MILLISECONDS,
                CobolDecimal.formatProcessingTimestamp(MOMENT_FIVE_MILLISECONDS_PAST_THE_SECOND));

        assertEquals(RENDERED_MOMENT_JUST_BELOW_THE_NEXT_SECOND,
                CobolDecimal.formatProcessingTimestamp(MOMENT_JUST_BELOW_THE_NEXT_SECOND));

        String justBelowTheNextSecond =
                CobolDecimal.formatProcessingTimestamp(MOMENT_JUST_BELOW_THE_NEXT_SECOND);

        assertEquals(LARGEST_HUNDREDTHS, justBelowTheNextSecond.substring(
                PicClause.PROCESSING_TIMESTAMP_FRACTION_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET));
        assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, justBelowTheNextSecond.length());
        assertEquals(SECOND_COMPONENT_FIFTY_NINE, justBelowTheNextSecond.substring(
                PicClause.PROCESSING_TIMESTAMP_SECOND_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_SECOND_OFFSET
                        + PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH));
    }

    /**
     * Asserts that a single-digit month, day, hour, minute, second, and fraction each render
     * padded to two characters. Every component of the redefine at
     * {@code app/cbl/CBTRN02C.cbl:L161-L174} holds a fixed width.
     */
    @Test
    void processingTimestampZeroPadsEveryComponent() {
        String rendered = CobolDecimal.formatProcessingTimestamp(MOMENT_NEEDING_PADDING);

        assertEquals(RENDERED_MOMENT_NEEDING_PADDING, rendered);
        assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, rendered.length());

        int[] componentOffsets = {
                PicClause.PROCESSING_TIMESTAMP_MONTH_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_DAY_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_HOUR_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_MINUTE_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_SECOND_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_FRACTION_OFFSET,
        };

        for (int offset : componentOffsets) {
            String component = rendered.substring(offset,
                    offset + PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH);

            assertEquals(PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH, component.length(),
                    "component at offset " + offset + " changed width: " + rendered);
            assertEquals(PADDING_DIGIT, component.charAt(0),
                    "component at offset " + offset + " lost its padding: " + rendered);
        }

        assertEquals(RENDERED_YEAR, rendered.substring(
                PicClause.PROCESSING_TIMESTAMP_YEAR_OFFSET,
                PicClause.PROCESSING_TIMESTAMP_YEAR_OFFSET
                        + PicClause.PROCESSING_TIMESTAMP_YEAR_WIDTH));
    }

    /**
     * Asserts that no {@link CobolDecimal} method accepts a {@link RoundingMode} argument. One
     * mode governs every arithmetic site, and no caller selects another.
     *
     * <p>The seven statements listed on this class hold every store that moves money, from
     * {@code app/cbl/CBTRN02C.cbl:L403} through {@code app/cbl/CBACT04C.cbl:L465}. None of them
     * carries a {@code ROUNDED} phrase, and the phrase appears nowhere in {@code app/cbl/}.
     */
    @Test
    void noCobolDecimalMethodAcceptsARoundingModeParameter() {
        for (Method method : CobolDecimal.class.getDeclaredMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertNotEquals(RoundingMode.class, parameterType,
                        "CobolDecimal." + method.getName() + " accepts a rounding mode");
            }
        }
    }
}
