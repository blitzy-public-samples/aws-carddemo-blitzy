package com.carddemo.cobol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that {@link CobolDecimal} truncates toward zero at every money-moving arithmetic site in
 * the CardDemo COBOL source, and that half-up rounding returns a different value.
 *
 * <p>The {@code ROUNDED} phrase appears zero times across all 28 programs in {@code app/cbl/}.
 * Every COBOL arithmetic store therefore truncates toward zero, the language default when no
 * rounding phrase is present.
 *
 * <p>Ten statements move money. {@link #everyMoneyMovingStatementIsPresentInTheSource()} reads the
 * three programs and asserts each statement at its locator, so this list is executable rather than
 * narrative.
 *
 * <ol>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L403-L405} computes the overlimit working balance in one
 *       {@code COMPUTE}, and {@code app/cbl/CBTRN02C.cbl:L407} compares it against the credit
 *       limit.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L508} adds the amount to a category balance the program is
 *       creating.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L527} adds the amount to a category balance the program is
 *       updating.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L547} adds the transaction amount to the account balance.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L549} adds the amount to the cycle credit accumulator.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L551} adds the amount to the cycle debit accumulator.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl:L234} subtracts the payment amount from the account
 *       balance.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L352} adds the accumulated interest to the account
 *       balance.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L464-L465} multiplies a category balance by an interest rate
 *       and divides the product by 1200.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L467} adds the monthly interest to the interest total.</li>
 * </ol>
 *
 * <p>A {@code COMPUTE} stores once. {@code app/cbl/CBTRN02C.cbl:L403-L405} and
 * {@code app/cbl/CBACT04C.cbl:L464-L465} each evaluate a whole expression and truncate the result
 * on the single store into the target field, so this class evaluates the expression exactly and
 * then applies one store. An {@code ADD} or a {@code SUBTRACT} stores once as well, into the
 * field it names.
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
 * <p>Every guard {@link CobolDecimal} declares has a method below that names the exception type
 * and checks the message carries no operand value.
 */
class CobolDecimalTruncationTest {

    // The source inventory this class reads. app/cbl holds the three programs.

    /** Directory under the repository root that holds the COBOL tree. */
    private static final String SOURCE_MARKER_DIRECTORY = "app";

    /** Directory under {@link #SOURCE_MARKER_DIRECTORY} that holds the programs. */
    private static final String COBOL_DIRECTORY = "cbl";

    /** The batch posting program, which holds six of the ten money-moving statements. */
    private static final String POSTING_PROGRAM = "CBTRN02C.cbl";

    /** The online bill payment program, which holds one. */
    private static final String BILL_PAYMENT_PROGRAM = "COBIL00C.cbl";

    /** The interest program, which holds three. */
    private static final String INTEREST_PROGRAM = "CBACT04C.cbl";

    /** The COBOL phrase that would round a store instead of truncating it. */
    private static final String ROUNDED_PHRASE = "ROUNDED";

    /** The count of distinct money-moving statements across the three programs. */
    private static final int MONEY_MOVING_STATEMENT_COUNT = 10;

    /**
     * One locator per money-moving statement, written as the program file name and the line
     * number a citation carries. {@code app/cbl/CBTRN02C.cbl:L403} covers the whole
     * {@code COMPUTE} that spans lines 403 through 405, and
     * {@code app/cbl/CBACT04C.cbl:L464} covers the one that spans 464 and 465.
     */
    private static final List<String> DISTINCT_STORE_LOCATORS = List.of(
            POSTING_PROGRAM + ":L403",
            POSTING_PROGRAM + ":L508",
            POSTING_PROGRAM + ":L527",
            POSTING_PROGRAM + ":L547",
            POSTING_PROGRAM + ":L549",
            POSTING_PROGRAM + ":L551",
            BILL_PAYMENT_PROGRAM + ":L234",
            INTEREST_PROGRAM + ":L352",
            INTEREST_PROGRAM + ":L464",
            INTEREST_PROGRAM + ":L467");

    // Guard inputs. Each value reaches one guard CobolDecimal declares.

    /** A scale below zero, which no Picture clause declares. */
    private static final int NEGATIVE_SCALE = -1;

    /**
     * The largest year {@code DB2-YYYY PIC 9(004)} at {@code app/cbl/CBTRN02C.cbl:L161} renders.
     */
    private static final int LARGEST_RENDERABLE_YEAR = 9999;

    /** A moment whose year runs one past {@link #LARGEST_RENDERABLE_YEAR}. */
    private static final LocalDateTime MOMENT_PAST_THE_LARGEST_YEAR =
            LocalDateTime.of(LARGEST_RENDERABLE_YEAR + 1, 1, 1, 0, 0, 0);

    // Site 1. Overlimit working balance. app/cbl/CBTRN02C.cbl:L403-L405 and L407.

    /** {@code ACCT-CURR-CYC-CREDIT}, {@code app/cpy/CVACT01Y.cpy:L13}, with a third decimal. */
    private static final BigDecimal CYCLE_CREDIT_THREE_DECIMALS = new BigDecimal("1234.567");

    /** {@code ACCT-CURR-CYC-DEBIT}, {@code app/cpy/CVACT01Y.cpy:L14}, with a third decimal. */
    private static final BigDecimal CYCLE_DEBIT_THREE_DECIMALS = new BigDecimal("200.001");

    /** {@code DALYTRAN-AMT}, {@code app/cpy/CVTRA06Y.cpy:L10}, with a third decimal. */
    private static final BigDecimal AMOUNT_THREE_DECIMALS = new BigDecimal("500.009");

    /**
     * The exact value the expression at {@code app/cbl/CBTRN02C.cbl:L403-L405} evaluates to before
     * the store: 1234.567 minus 200.001 plus 500.009.
     */
    private static final BigDecimal EXACT_WORKING_BALANCE_EXPRESSION = new BigDecimal("1534.575");

    /**
     * The working balance the single store yields. {@code COMPUTE} truncates once, on the store
     * into {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187}.
     */
    private static final BigDecimal TRUNCATED_WORKING_BALANCE = new BigDecimal("1534.57");

    /** The half-up working balance from the same expression. */
    private static final BigDecimal HALF_UP_WORKING_BALANCE = new BigDecimal("1534.58");

    /**
     * The working balance a truncating store applied after every operator would yield: 1034.56
     * from the subtraction, then 1534.56 from the addition. The source stores once, so this value
     * is one cent below the value the source computes.
     */
    private static final BigDecimal STEPWISE_STORE_WORKING_BALANCE = new BigDecimal("1534.56");

    /**
     * A credit limit sitting between {@link #STEPWISE_STORE_WORKING_BALANCE} and
     * {@link #TRUNCATED_WORKING_BALANCE}. {@code app/cbl/CBTRN02C.cbl:L407} declines against the
     * value the source computes and would approve against the stepwise value.
     */
    private static final BigDecimal CREDIT_LIMIT_BETWEEN_THE_TWO_STORES = new BigDecimal("1534.56");

    /** One cent at the scale of {@code WS-TEMP-BAL}, the gap the two store points produce. */
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

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

    // Site 2. Account balance. app/cbl/CBTRN02C.cbl:L547.

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

    // Site 3. Cycle accumulator branch. app/cbl/CBTRN02C.cbl:L548-L551.

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

    // Sites 4 and 5. Category balance. app/cbl/CBTRN02C.cbl:L508 and L527.

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

    // Site 6. Bill payment. app/cbl/COBIL00C.cbl:L224 and L234.

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

    // Site 7. Interest. app/cbl/CBACT04C.cbl:L464-L465, L467, and L352.

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

    // Precision narrowings. app/cbl/CBTRN02C.cbl:L187 and app/cbl/CBACT04C.cbl:L168-L169.

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

    // Scale-helper operands, used by the two mode-agnostic truncation assertions.

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

    // Processing timestamp. app/cbl/CBTRN02C.cbl:L159-L174, L692-L705, and L438.

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
     * Site 1. Asserts that
     * {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} at
     * {@code app/cbl/CBTRN02C.cbl:L403-L405} evaluates the whole expression and truncates once, on
     * the store into {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187}.
     *
     * <p>The three operands evaluate to 1534.575 exactly, which the single store truncates to
     * 1534.57. A store applied after every operator would yield 1534.56, one cent lower, and
     * half-up rounding on the single store would yield 1534.58. This method asserts the source
     * value and asserts it differs from both alternatives.
     */
    @Test
    void overlimitWorkingBalanceEvaluatesTheWholeExpressionAndTruncatesOnce() {
        BigDecimal expression = CYCLE_CREDIT_THREE_DECIMALS
                .subtract(CYCLE_DEBIT_THREE_DECIMALS)
                .add(AMOUNT_THREE_DECIMALS);

        assertEquals(EXACT_WORKING_BALANCE_EXPRESSION, expression);

        BigDecimal workingBalance = CobolDecimal.truncateToPictureField(expression,
                PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(TRUNCATED_WORKING_BALANCE, workingBalance);
        assertEquals(PicClause.WS_TEMP_BAL_SCALE, workingBalance.scale());

        assertEquals(HALF_UP_WORKING_BALANCE,
                expression.setScale(PicClause.WS_TEMP_BAL_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_WORKING_BALANCE, workingBalance);

        BigDecimal stepwise = CobolDecimal.add(
                CobolDecimal.subtract(CYCLE_CREDIT_THREE_DECIMALS, CYCLE_DEBIT_THREE_DECIMALS,
                        PicClause.WS_TEMP_BAL_SCALE),
                AMOUNT_THREE_DECIMALS, PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(STEPWISE_STORE_WORKING_BALANCE, stepwise);
        assertNotEquals(stepwise, workingBalance);
    }

    /**
     * Site 1. Asserts that the one-cent difference between the single store and a stepwise store
     * changes the outcome of {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} at
     * {@code app/cbl/CBTRN02C.cbl:L407}.
     *
     * <p>A credit limit of 1534.56 covers the stepwise value and falls one cent short of the value
     * the source computes, so the source declines and the stepwise reading would approve. The
     * decline carries reason 102 at {@code app/cbl/CBTRN02C.cbl:L410}.
     */
    @Test
    void oneCentBetweenTheTwoStoresFlipsTheCreditLimitDecision() {
        BigDecimal sourceWorkingBalance = CobolDecimal.truncateToPictureField(
                CYCLE_CREDIT_THREE_DECIMALS.subtract(CYCLE_DEBIT_THREE_DECIMALS)
                        .add(AMOUNT_THREE_DECIMALS),
                PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);

        assertEquals(TRUNCATED_WORKING_BALANCE, sourceWorkingBalance);
        assertFalse(CREDIT_LIMIT_BETWEEN_THE_TWO_STORES.compareTo(sourceWorkingBalance) >= 0,
                "the credit limit covered the working balance the single store yields");
        assertTrue(CREDIT_LIMIT_BETWEEN_THE_TWO_STORES
                        .compareTo(STEPWISE_STORE_WORKING_BALANCE) >= 0,
                "the credit limit stopped covering the working balance a stepwise store yields");

        assertEquals(ONE_CENT,
                sourceWorkingBalance.subtract(STEPWISE_STORE_WORKING_BALANCE),
                "the two stores stopped differing by exactly one cent");
    }

    /**
     * Site 1. Asserts the two outcomes of {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} at
     * {@code app/cbl/CBTRN02C.cbl:L407}. The credit limit covers one working balance and falls
     * short of the other, which reaches {@code MOVE 102} at {@code app/cbl/CBTRN02C.cbl:L410}.
     */
    @Test
    void creditLimitComparisonCoversOneWorkingBalanceAndRejectsTheOther() {
        BigDecimal insideLimit = overlimitWorkingBalance(CYCLE_CREDIT, CYCLE_DEBIT,
                AMOUNT_INSIDE_LIMIT);

        assertEquals(WORKING_BALANCE_INSIDE_LIMIT, insideLimit);
        assertTrue(CREDIT_LIMIT.compareTo(insideLimit) >= 0,
                "credit limit stopped covering the working balance: " + insideLimit);

        BigDecimal overLimit = overlimitWorkingBalance(CYCLE_CREDIT, CYCLE_DEBIT,
                AMOUNT_OVER_LIMIT);

        assertEquals(WORKING_BALANCE_OVER_LIMIT, overLimit);
        assertFalse(CREDIT_LIMIT.compareTo(overLimit) >= 0,
                "credit limit covered an overlimit working balance: " + overLimit);
    }

    /**
     * Evaluates {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} exactly and
     * stores the result once, reproducing the {@code COMPUTE} at
     * {@code app/cbl/CBTRN02C.cbl:L403-L405}.
     *
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT}, {@code app/cpy/CVACT01Y.cpy:L13}
     * @param cycleDebit  {@code ACCT-CURR-CYC-DEBIT}, {@code app/cpy/CVACT01Y.cpy:L14}
     * @param amount      {@code DALYTRAN-AMT}, {@code app/cpy/CVTRA06Y.cpy:L10}
     * @return the value {@code WS-TEMP-BAL PIC S9(09)V99} holds after the single store
     */
    private static BigDecimal overlimitWorkingBalance(BigDecimal cycleCredit,
            BigDecimal cycleDebit, BigDecimal amount) {
        return CobolDecimal.truncateToPictureField(
                cycleCredit.subtract(cycleDebit).add(amount),
                PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);
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
     * Site 3. Asserts that the branch {@code IF DALYTRAN-AMT >= 0} at
     * {@code app/cbl/CBTRN02C.cbl:L548} sends the amount to the cycle credit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L549} and leaves the cycle debit accumulator alone, and that a
     * negative amount does the opposite at {@code app/cbl/CBTRN02C.cbl:L551}. Zero takes the credit
     * branch.
     *
     * <p>{@link AccountRecordUnderPosting} holds both accumulators and the balance in mutable
     * fields, so each assertion compares the state after the update against the state before it.
     * The branch itself belongs to the ledger posting service; this method exercises the branch as
     * the source writes it and the accumulator arithmetic {@link CobolDecimal} supplies.
     */
    @Test
    void theSignOfTheAmountSelectsOneAccumulatorAndLeavesTheOtherUnchanged() {
        AccountRecordUnderPosting purchase =
                new AccountRecordUnderPosting(ACCOUNT_BALANCE, CYCLE_CREDIT_BEFORE_PURCHASE,
                        NEGATIVE_CYCLE_DEBIT);
        BigDecimal debitBeforePurchase = purchase.cycleDebit;

        purchase.post(PURCHASE_AMOUNT);

        assertEquals(CYCLE_CREDIT_AFTER_PURCHASE, purchase.cycleCredit,
                "a non-negative amount stopped reaching the cycle credit accumulator");
        assertEquals(debitBeforePurchase, purchase.cycleDebit,
                "a non-negative amount moved the cycle debit accumulator");

        AccountRecordUnderPosting zeroAmount =
                new AccountRecordUnderPosting(ACCOUNT_BALANCE, CYCLE_CREDIT_BEFORE_PURCHASE,
                        NEGATIVE_CYCLE_DEBIT);

        zeroAmount.post(ZERO_ACCUMULATOR);

        assertEquals(CYCLE_CREDIT_BEFORE_PURCHASE, zeroAmount.cycleCredit,
                "zero stopped taking the cycle credit branch");
        assertEquals(NEGATIVE_CYCLE_DEBIT, zeroAmount.cycleDebit,
                "zero moved the cycle debit accumulator");

        AccountRecordUnderPosting refund =
                new AccountRecordUnderPosting(ACCOUNT_BALANCE, CYCLE_CREDIT_BEFORE_PURCHASE,
                        NEGATIVE_CYCLE_DEBIT);

        refund.post(NEGATIVE_AMOUNT_THREE_DECIMALS);

        assertEquals(TRUNCATED_CYCLE_DEBIT, refund.cycleDebit,
                "a negative amount stopped reaching the cycle debit accumulator");
        assertEquals(CYCLE_CREDIT_BEFORE_PURCHASE, refund.cycleCredit,
                "a negative amount moved the cycle credit accumulator");
    }

    /**
     * Site 3. Asserts that a refund recorded in the cycle debit accumulator at
     * {@code app/cbl/CBTRN02C.cbl:L551} raises the working balance the overlimit formula
     * computes, since {@code app/cbl/CBTRN02C.cbl:L404} subtracts that accumulator. The source
     * behaviour is reproduced here and carried unchanged.
     */
    @Test
    void refundInTheCycleDebitAccumulatorRaisesTheOverlimitWorkingBalance() {
        BigDecimal withoutRefund = overlimitWorkingBalance(CYCLE_CREDIT_ONE_THOUSAND,
                ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

        assertEquals(WORKING_BALANCE_WITHOUT_REFUND, withoutRefund);

        BigDecimal withRefund = overlimitWorkingBalance(CYCLE_CREDIT_ONE_THOUSAND,
                CYCLE_DEBIT_AFTER_REFUND, ZERO_ACCUMULATOR);

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
        AccountRecordUnderPayment record = new AccountRecordUnderPayment(BALANCE_BEFORE_PAYMENT,
                CYCLE_CREDIT, CYCLE_DEBIT);
        BigDecimal cycleCreditBeforePayment = record.cycleCredit;
        BigDecimal cycleDebitBeforePayment = record.cycleDebit;

        BigDecimal paymentAmount = CobolDecimal.truncateToPictureField(record.currentBalance,
                PicClause.TRAN_AMT_PRECISION, PicClause.TRAN_AMT_SCALE);

        assertEquals(BALANCE_BEFORE_PAYMENT, paymentAmount);

        record.pay(paymentAmount);

        assertEquals(BALANCE_AFTER_PAYMENT, record.currentBalance);
        assertEquals(0, record.currentBalance.signum());
        assertEquals(PicClause.ACCT_CURR_BAL_SCALE, record.currentBalance.scale());

        assertNotEquals(BALANCE_BEFORE_PAYMENT, record.currentBalance,
                "the payment left the balance where it started");
        assertSame(cycleCreditBeforePayment, record.cycleCredit,
                "the payment path moved the cycle credit accumulator");
        assertSame(cycleDebitBeforePayment, record.cycleDebit,
                "the payment path moved the cycle debit accumulator");
        assertEquals(CYCLE_CREDIT, record.cycleCredit);
        assertEquals(CYCLE_DEBIT, record.cycleDebit);

        AccountRecordUnderPayment partial = new AccountRecordUnderPayment(ACCOUNT_BALANCE,
                CYCLE_CREDIT, CYCLE_DEBIT);

        partial.pay(PAYMENT_WITH_THIRD_DECIMAL);

        assertEquals(TRUNCATED_BALANCE_AFTER_PARTIAL_PAYMENT, partial.currentBalance);
        assertEquals(HALF_UP_BALANCE_AFTER_PARTIAL_PAYMENT, ACCOUNT_BALANCE
                .subtract(PAYMENT_WITH_THIRD_DECIMAL)
                .setScale(PicClause.ACCT_CURR_BAL_SCALE, RoundingMode.HALF_UP));
        assertNotEquals(HALF_UP_BALANCE_AFTER_PARTIAL_PAYMENT, partial.currentBalance);
        assertEquals(CYCLE_CREDIT, partial.cycleCredit,
                "the partial payment moved the cycle credit accumulator");
        assertEquals(CYCLE_DEBIT, partial.cycleDebit,
                "the partial payment moved the cycle debit accumulator");
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

    // The source-site inventory, read from the three programs rather than narrated.

    /**
     * Asserts that each of the ten money-moving statements listed on this class sits at its
     * locator in the source, and that the two {@code COMPUTE} statements each name one target
     * field.
     *
     * <p>The three programs are read from disk, so this inventory fails when a locator drifts
     * rather than when a comment goes stale. The count is ten because
     * {@code app/cbl/CBTRN02C.cbl:L549} and {@code app/cbl/CBTRN02C.cbl:L551} are two statements
     * inside one {@code IF}, and because {@code app/cbl/CBACT04C.cbl:L352} and
     * {@code app/cbl/CBACT04C.cbl:L467} each store money as well.
     */
    @Test
    void everyMoneyMovingStatementIsPresentInTheSource() {
        List<SourceStatement> inventory = List.of(
                new SourceStatement(POSTING_PROGRAM, 403, "COMPUTE WS-TEMP-BAL"),
                new SourceStatement(POSTING_PROGRAM, 404, "- ACCT-CURR-CYC-DEBIT"),
                new SourceStatement(POSTING_PROGRAM, 405, "+ DALYTRAN-AMT"),
                new SourceStatement(POSTING_PROGRAM, 508, "ADD DALYTRAN-AMT TO TRAN-CAT-BAL"),
                new SourceStatement(POSTING_PROGRAM, 527, "ADD DALYTRAN-AMT TO TRAN-CAT-BAL"),
                new SourceStatement(POSTING_PROGRAM, 547, "ADD DALYTRAN-AMT  TO ACCT-CURR-BAL"),
                new SourceStatement(POSTING_PROGRAM, 548, "IF DALYTRAN-AMT >= 0"),
                new SourceStatement(POSTING_PROGRAM, 549,
                        "ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT"),
                new SourceStatement(POSTING_PROGRAM, 551,
                        "ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT"),
                new SourceStatement(BILL_PAYMENT_PROGRAM, 234,
                        "COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT"),
                new SourceStatement(INTEREST_PROGRAM, 352, "ADD WS-TOTAL-INT  TO ACCT-CURR-BAL"),
                new SourceStatement(INTEREST_PROGRAM, 464, "COMPUTE WS-MONTHLY-INT"),
                new SourceStatement(INTEREST_PROGRAM, 465, "( TRAN-CAT-BAL * DIS-INT-RATE) / 1200"),
                new SourceStatement(INTEREST_PROGRAM, 467, "ADD WS-MONTHLY-INT  TO WS-TOTAL-INT"));

        for (SourceStatement statement : inventory) {
            String line = sourceLine(statement.programFileName(), statement.oneIndexedLine());

            assertTrue(line.contains(statement.expectedText()),
                    "app/cbl/" + statement.programFileName() + ":L" + statement.oneIndexedLine()
                            + " stopped holding the expected statement");
        }

        assertEquals(MONEY_MOVING_STATEMENT_COUNT, DISTINCT_STORE_LOCATORS.size(),
                "the money-moving statement count changed");
        assertEquals(MONEY_MOVING_STATEMENT_COUNT, Set.copyOf(DISTINCT_STORE_LOCATORS).size(),
                "the money-moving statement list repeats a locator");
    }

    /**
     * Asserts that the {@code ROUNDED} phrase appears in none of the three programs this class
     * reads. Every store in those programs therefore truncates toward zero.
     */
    @Test
    void theRoundedPhraseAppearsInNoneOfTheThreeProgramsThisClassReads() {
        for (String programFileName : List.of(POSTING_PROGRAM, BILL_PAYMENT_PROGRAM,
                INTEREST_PROGRAM)) {
            for (String line : programLines(programFileName)) {
                assertFalse(line.contains(ROUNDED_PHRASE),
                        "app/cbl/" + programFileName + " gained a ROUNDED phrase");
            }
        }
    }

    // The guards CobolDecimal declares. Each method names the exception type and checks the
    // message carries no operand value.

    /**
     * Asserts that every arithmetic method rejects a {@code null} operand with
     * {@link NullPointerException} and names the parameter in the message.
     */
    @Test
    void everyArithmeticMethodRejectsANullOperand() {
        assertEquals("augend must not be null", assertThrows(NullPointerException.class,
                () -> CobolDecimal.add(null, ACCOUNT_BALANCE, PicClause.ACCT_CURR_BAL_SCALE))
                .getMessage());
        assertEquals("addend must not be null", assertThrows(NullPointerException.class,
                () -> CobolDecimal.add(ACCOUNT_BALANCE, null, PicClause.ACCT_CURR_BAL_SCALE))
                .getMessage());

        assertThrows(NullPointerException.class,
                () -> CobolDecimal.subtract(null, ACCOUNT_BALANCE, PicClause.ACCT_CURR_BAL_SCALE));
        assertThrows(NullPointerException.class,
                () -> CobolDecimal.subtract(ACCOUNT_BALANCE, null, PicClause.ACCT_CURR_BAL_SCALE));

        assertThrows(NullPointerException.class,
                () -> CobolDecimal.multiply(null, ACCOUNT_BALANCE, PicClause.ACCT_CURR_BAL_SCALE));
        assertThrows(NullPointerException.class,
                () -> CobolDecimal.multiply(ACCOUNT_BALANCE, null, PicClause.ACCT_CURR_BAL_SCALE));

        assertThrows(NullPointerException.class,
                () -> CobolDecimal.divide(null, CobolDecimal.INTEREST_DIVISOR,
                        PicClause.WS_MONTHLY_INT_SCALE));
        assertThrows(NullPointerException.class,
                () -> CobolDecimal.divide(ACCOUNT_BALANCE, null, PicClause.WS_MONTHLY_INT_SCALE));

        assertThrows(NullPointerException.class,
                () -> CobolDecimal.multiplyThenDivide(null, ACCOUNT_BALANCE,
                        CobolDecimal.INTEREST_DIVISOR, PicClause.WS_MONTHLY_INT_SCALE));
        assertThrows(NullPointerException.class,
                () -> CobolDecimal.truncateToScale(null, PicClause.TRAN_AMT_SCALE));
        assertThrows(NullPointerException.class,
                () -> CobolDecimal.truncateToPictureField(null, PicClause.WS_TEMP_BAL_PRECISION,
                        PicClause.WS_TEMP_BAL_SCALE));
        assertThrows(NullPointerException.class,
                () -> CobolDecimal.formatProcessingTimestamp(null));
    }

    /**
     * Asserts that a divisor of zero reaches {@link ArithmeticException} rather than returning a
     * value, on both the plain division and the multiply-then-divide form.
     */
    @Test
    void aDivisorOfZeroIsRejected() {
        assertThrows(ArithmeticException.class,
                () -> CobolDecimal.divide(ACCOUNT_BALANCE, BigDecimal.ZERO,
                        PicClause.WS_MONTHLY_INT_SCALE));
        assertThrows(ArithmeticException.class,
                () -> CobolDecimal.multiplyThenDivide(ACCOUNT_BALANCE, DISCLOSURE_INTEREST_RATE,
                        BigDecimal.ZERO, PicClause.WS_MONTHLY_INT_SCALE));
    }

    /**
     * Asserts that a negative scale is rejected by every method that takes one, and that the
     * message names the scale and no operand value.
     */
    @Test
    void aNegativeScaleIsRejectedByEveryMethodThatTakesOne() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.add(ACCOUNT_BALANCE, AMOUNT_INSIDE_LIMIT, NEGATIVE_SCALE));

        assertTrue(rejected.getMessage().contains(String.valueOf(NEGATIVE_SCALE)),
                "the rejection stopped naming the scale");
        assertFalse(rejected.getMessage().contains(ACCOUNT_BALANCE.toPlainString()),
                "the rejection carried an operand value");

        assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.subtract(ACCOUNT_BALANCE, AMOUNT_INSIDE_LIMIT, NEGATIVE_SCALE));
        assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.multiply(ACCOUNT_BALANCE, DISCLOSURE_INTEREST_RATE, NEGATIVE_SCALE));
        assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.divide(ACCOUNT_BALANCE, CobolDecimal.INTEREST_DIVISOR,
                        NEGATIVE_SCALE));
        assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.multiplyThenDivide(ACCOUNT_BALANCE, DISCLOSURE_INTEREST_RATE,
                        CobolDecimal.INTEREST_DIVISOR, NEGATIVE_SCALE));
        assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.truncateToScale(ACCOUNT_BALANCE, NEGATIVE_SCALE));
        assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.truncateToPictureField(ACCOUNT_BALANCE,
                        PicClause.WS_TEMP_BAL_PRECISION, NEGATIVE_SCALE));
    }

    /**
     * Asserts that a Picture field whose precision does not exceed its scale is rejected. A field
     * holds at least one integer digit, so {@code PIC S9(00)V99} names no field the source
     * declares.
     */
    @Test
    void aPictureFieldWithNoIntegerDigitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.truncateToPictureField(ACCOUNT_BALANCE,
                        PicClause.WS_TEMP_BAL_SCALE, PicClause.WS_TEMP_BAL_SCALE));
        assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.truncateToPictureField(ACCOUNT_BALANCE,
                        PicClause.WS_TEMP_BAL_SCALE - 1, PicClause.WS_TEMP_BAL_SCALE));
    }

    /**
     * Asserts that a moment whose year runs past the four digits
     * {@code DB2-YYYY PIC 9(004)} at {@code app/cbl/CBTRN02C.cbl:L161} holds is rejected rather
     * than rendered short.
     */
    @Test
    void aYearPastFourDigitsIsRejectedByTheTimestampRenderer() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> CobolDecimal.formatProcessingTimestamp(MOMENT_PAST_THE_LARGEST_YEAR));

        assertTrue(rejected.getMessage()
                        .contains(String.valueOf(PicClause.PROCESSING_TIMESTAMP_YEAR_WIDTH)),
                "the rejection stopped naming the width the year field holds");
        assertTrue(rejected.getMessage()
                        .contains(String.valueOf(MOMENT_PAST_THE_LARGEST_YEAR.getYear())),
                "the rejection stopped naming the year it refused");

        assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, CobolDecimal
                .formatProcessingTimestamp(
                        MOMENT_PAST_THE_LARGEST_YEAR.withYear(LARGEST_RENDERABLE_YEAR))
                .length(),
                "the largest year the field holds stopped rendering");
    }

    // Private helpers and holders.

    /**
     * Returns one line of one program under {@code app/cbl}.
     *
     * @param programFileName file name of the program, such as {@link #POSTING_PROGRAM}
     * @param oneIndexedLine  the line number as a locator writes it
     * @return the line, with its trailing sequence area intact
     */
    private static String sourceLine(String programFileName, int oneIndexedLine) {
        List<String> lines = programLines(programFileName);

        assertTrue(oneIndexedLine <= lines.size(),
                "app/cbl/" + programFileName + " is shorter than line " + oneIndexedLine);
        return lines.get(oneIndexedLine - 1);
    }

    /**
     * Reads every line of one program under {@code app/cbl}. The file is opened for reading only.
     *
     * @param programFileName file name of the program
     * @return the lines in file order
     */
    private static List<String> programLines(String programFileName) {
        Path program = cobolSourceDirectory().resolve(programFileName);

        assertTrue(Files.isRegularFile(program),
                "program " + program + " is not a readable file");
        try {
            return Files.readAllLines(program, StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("reading " + program + " failed", unreadable);
        }
    }

    /**
     * Resolves {@code app/cbl} by walking upward from the working directory until a directory
     * named {@code app} holding {@code cbl} appears. Maven runs a module build from the module
     * directory and an aggregator build from the aggregator directory, and the walk finds the same
     * directory from either.
     *
     * @return the absolute path of {@code app/cbl}
     */
    private static Path cobolSourceDirectory() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            Path cobol = candidate.resolve(SOURCE_MARKER_DIRECTORY).resolve(COBOL_DIRECTORY);
            if (Files.isDirectory(cobol)) {
                return cobol;
            }
        }
        throw new IllegalStateException("walked upward from " + start
                + " to the filesystem root without finding a directory named '"
                + SOURCE_MARKER_DIRECTORY + "' holding '" + COBOL_DIRECTORY + "'");
    }

    /**
     * One money-moving statement in the source.
     *
     * @param programFileName file name under {@code app/cbl}
     * @param oneIndexedLine  the line number as a locator writes it
     * @param expectedText    text the line holds
     */
    private record SourceStatement(String programFileName, int oneIndexedLine,
                                   String expectedText) { }

    /**
     * The three account fields {@code 2800-UPDATE-ACCOUNT-REC} at
     * {@code app/cbl/CBTRN02C.cbl:L545-L560} changes, held in mutable fields so a test can compare
     * the state after an update against the state before it.
     */
    private static final class AccountRecordUnderPosting {

        /** {@code ACCT-CURR-BAL}, {@code app/cpy/CVACT01Y.cpy:L7}. */
        private BigDecimal currentBalance;

        /** {@code ACCT-CURR-CYC-CREDIT}, {@code app/cpy/CVACT01Y.cpy:L13}. */
        private BigDecimal cycleCredit;

        /** {@code ACCT-CURR-CYC-DEBIT}, {@code app/cpy/CVACT01Y.cpy:L14}. */
        private BigDecimal cycleDebit;

        AccountRecordUnderPosting(BigDecimal currentBalance, BigDecimal cycleCredit,
                BigDecimal cycleDebit) {
            this.currentBalance = currentBalance;
            this.cycleCredit = cycleCredit;
            this.cycleDebit = cycleDebit;
        }

        /**
         * Applies {@code app/cbl/CBTRN02C.cbl:L547-L552}: the amount reaches the balance, then the
         * sign of the amount selects one accumulator.
         *
         * @param amount {@code DALYTRAN-AMT}, {@code app/cpy/CVTRA06Y.cpy:L10}
         */
        void post(BigDecimal amount) {
            currentBalance = CobolDecimal.add(currentBalance, amount,
                    PicClause.ACCT_CURR_BAL_SCALE);
            if (amount.compareTo(BigDecimal.ZERO) >= 0) {
                cycleCredit = CobolDecimal.add(cycleCredit, amount,
                        PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
            } else {
                cycleDebit = CobolDecimal.add(cycleDebit, amount,
                        PicClause.ACCT_CURR_CYC_DEBIT_SCALE);
            }
        }
    }

    /**
     * The same three account fields under the online payment path. {@code app/cbl/COBIL00C.cbl}
     * holds no reference to either accumulator, so {@link #pay(BigDecimal)} changes the balance
     * alone.
     */
    private static final class AccountRecordUnderPayment {

        /** {@code ACCT-CURR-BAL}, {@code app/cpy/CVACT01Y.cpy:L7}. */
        private BigDecimal currentBalance;

        /** {@code ACCT-CURR-CYC-CREDIT}, {@code app/cpy/CVACT01Y.cpy:L13}. */
        private BigDecimal cycleCredit;

        /** {@code ACCT-CURR-CYC-DEBIT}, {@code app/cpy/CVACT01Y.cpy:L14}. */
        private BigDecimal cycleDebit;

        AccountRecordUnderPayment(BigDecimal currentBalance, BigDecimal cycleCredit,
                BigDecimal cycleDebit) {
            this.currentBalance = currentBalance;
            this.cycleCredit = cycleCredit;
            this.cycleDebit = cycleDebit;
        }

        /**
         * Applies {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} at
         * {@code app/cbl/COBIL00C.cbl:L234}.
         *
         * @param paymentAmount {@code TRAN-AMT}, {@code app/cpy/CVTRA05Y.cpy:L10}
         */
        void pay(BigDecimal paymentAmount) {
            currentBalance = CobolDecimal.truncateToScale(
                    currentBalance.subtract(paymentAmount), PicClause.ACCT_CURR_BAL_SCALE);
        }
    }
}
