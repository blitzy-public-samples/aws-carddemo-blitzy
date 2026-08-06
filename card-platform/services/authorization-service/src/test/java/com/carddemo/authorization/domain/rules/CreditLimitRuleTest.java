package com.carddemo.authorization.domain.rules;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.Order;

/**
 * Unit tests for {@link CreditLimitRule}, which assigns reject code {@code 0102}.
 *
 * <p>Subject lines {@code app/cbl/CBTRN02C.cbl:L403-L405} and {@code app/cbl/CBTRN02C.cbl:L407},
 * inside the {@code NOT INVALID KEY} limb of the account read. The assignment sits at
 * {@code app/cbl/CBTRN02C.cbl:L410-L412}. All three are quoted below.
 *
 * <pre>{@code
 *      *         DISPLAY 'ACCT-CREDIT-LIMIT:' ACCT-CREDIT-LIMIT
 *      *         DISPLAY 'TRAN-AMT         :' DALYTRAN-AMT
 *                COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
 *                                    - ACCT-CURR-CYC-DEBIT
 *                                    + DALYTRAN-AMT
 *
 *                IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
 *                  CONTINUE
 *                ELSE
 *                  MOVE 102 TO WS-VALIDATION-FAIL-REASON
 *                  MOVE 'OVERLIMIT TRANSACTION'
 *                    TO WS-VALIDATION-FAIL-REASON-DESC
 *                END-IF
 * }</pre>
 *
 * <p>Both commented lines above are reproduced nowhere, and this rule writes no log line.
 *
 * <p>The approving condition is tested and {@code CONTINUE} sits on its limb, so the reject reason
 * is assigned on the {@code ELSE}. A limit equal to the working balance answers with nothing.
 *
 * <p>Three source findings are reproduced here, and each carries an assertion below.
 *
 * <ul>
 *   <li>The formula reads the two cycle accumulators and the amount. {@code ACCT-CURR-BAL
 *       PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7} takes no part, and
 *       {@link AccountCreditSnapshotEntity} carries no column for it.</li>
 *   <li>{@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187} holds nine integer
 *       digits. {@code ACCT-CREDIT-LIMIT} at {@code app/cpy/CVACT01Y.cpy:L8},
 *       {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13} and
 *       {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14} each hold ten. A working
 *       balance reaching one billion loses its high-order digit on the store, and the narrowed
 *       value then sits under a limit the full value exceeds.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L548-L552} routes a negative amount to the cycle debit
 *       accumulator at {@code app/cbl/CBTRN02C.cbl:L551}, and
 *       {@code app/cbl/CBTRN02C.cbl:L404} subtracts that accumulator. A refund raises the working
 *       balance.</li>
 * </ul>
 *
 * <p>Amount scale comes from {@code DALYTRAN-AMT PIC S9(09)V99} at
 * {@code app/cpy/CVTRA06Y.cpy:L10}. The phrase {@code ROUNDED} appears in none of the twenty-eight
 * members of {@code app/cbl}, so every store truncates toward zero.
 *
 * <p>Reject code {@code 0102} is the one reason the fixtures reach: 13 of the 300 records of
 * {@code app/data/ASCII/dailytran.txt} against the seeded accounts, spread over four accounts.
 * Scenarios below therefore mix fixture-grounded values with constructed ones. Account 7 and
 * account 30 of {@code app/data/ASCII/acctdata.txt} supply the fixture-grounded values, sliced at
 * the copybook offsets. The one-billion case is constructed, since all fifty fixture accounts carry
 * {@code 0.00} in both accumulators.
 *
 * <p>Snapshot rows are stubs. Their own column contract is asserted under
 * {@code src/test/java/com/carddemo/authorization/entity}.
 *
 * <p>Five tests a reader may expect are absent from the source, and so from here.
 *
 * <ul>
 *   <li>No cash credit limit test. {@code ACCT-CASH-CREDIT-LIMIT} at
 *       {@code app/cpy/CVACT01Y.cpy:L9} holds no column on the row.</li>
 *   <li>No account status test. {@code ACCT-ACTIVE-STATUS PIC X(01)} at
 *       {@code app/cpy/CVACT01Y.cpy:L6} holds none either, and no paragraph reads it before
 *       posting.</li>
 *   <li>No card status test. {@code app/cbl/CBTRN02C.cbl:L28-L61} declares six files without a card
 *       file, and {@code app/jcl/POSTTRAN.jcl} allocates none.</li>
 *   <li>No card number format test. This rule reads no card number.</li>
 *   <li>No expiry test. {@code app/cbl/CBTRN02C.cbl:L414} places that one after this one, and
 *       {@link AccountExpirationRule} owns it.</li>
 * </ul>
 *
 * <p>Neither accumulator is written here. The account service zeroes both at its cycle-close
 * operation, reproducing {@code app/cbl/CBACT04C.cbl:L353-L354}.
 *
 * <p>Flagged source findings: {@code card-platform/docs/business-rule-flags.md}.
 */
final class CreditLimitRuleTest {

    /**
     * The card number record one of {@code app/data/ASCII/dailytran.txt} carries at positions
     * 263-278. Row 21 of {@code app/data/ASCII/cardxref.txt} resolves it to account
     * {@code 00000000007}. This rule reads no card number.
     */
    private static final String FIXTURE_CARD_NUMBER = "4859452612877065";

    /**
     * A card number of sixteen zero characters, for the scenario proving the card number does not
     * decide this rule.
     */
    private static final String ALL_ZERO_CARD_NUMBER = "0000000000000000";

    /**
     * The capture timestamp record one of {@code app/data/ASCII/dailytran.txt} carries at positions
     * 279-304, held as the text {@code DALYTRAN-ORIG-TS PIC X(26)} declares at
     * {@code app/cpy/CVTRA06Y.cpy:L16}. This rule reads no timestamp.
     */
    private static final String FIXTURE_ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The amount record one of {@code app/data/ASCII/dailytran.txt} carries at positions 133-143.
     * The bytes read {@code 0000005047G}, whose trailing overpunch marks a positive 7, giving
     * {@code +504.77} under {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}.
     */
    private static final BigDecimal FIXTURE_AMOUNT = new BigDecimal("504.77");

    /**
     * The credit limit account 7 of {@code app/data/ASCII/acctdata.txt} carries at bytes 25-36,
     * under {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L8}.
     */
    private static final BigDecimal ACCOUNT_7_CREDIT_LIMIT = new BigDecimal("2065.00");

    /**
     * The current balance account 7 of {@code app/data/ASCII/acctdata.txt} carries at bytes 13-24,
     * under {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}. No column of
     * {@link AccountCreditSnapshotEntity} holds it, and the formula at
     * {@code app/cbl/CBTRN02C.cbl:L403-L405} omits it.
     */
    private static final BigDecimal ACCOUNT_7_CURRENT_BALANCE = new BigDecimal("193.00");

    /**
     * An amount that sits under the credit limit of account 7 and over the sum of that account's
     * current balance and the same limit. The two readings of the formula disagree on it.
     */
    private static final BigDecimal AMOUNT_UNDER_LIMIT_OVER_BALANCE_SUM = new BigDecimal("2000.00");

    /**
     * The credit limit account 30 of {@code app/data/ASCII/acctdata.txt} carries at bytes 25-36.
     * It is the narrowest limit among the four accounts the fixture declines.
     */
    private static final BigDecimal ACCOUNT_30_CREDIT_LIMIT = new BigDecimal("120.00");

    /**
     * The value all fifty accounts of {@code app/data/ASCII/acctdata.txt} carry in both cycle
     * accumulators, at bytes 79-90 and 91-102.
     */
    private static final BigDecimal ZERO_ACCUMULATOR = new BigDecimal("0.00");

    /** The smallest step the two fractional digits of a money field hold. */
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

    /** A round limit for the scenarios that sit one cent either side of it. */
    private static final BigDecimal ROUND_LIMIT = new BigDecimal("500.00");

    /**
     * The cycle debit accumulator after a refund of {@code 100.00} posted through
     * {@code app/cbl/CBTRN02C.cbl:L551}. Fifty of the 300 records of
     * {@code app/data/ASCII/dailytran.txt} carry a negative amount.
     */
    private static final BigDecimal REFUNDED_CYCLE_DEBIT = new BigDecimal("-100.00");

    /**
     * The working balance {@code app/cbl/CBTRN02C.cbl:L404} computes once
     * {@link #REFUNDED_CYCLE_DEBIT} is subtracted from a zero cycle credit.
     */
    private static final BigDecimal REFUND_RAISED_BALANCE = new BigDecimal("100.00");

    /**
     * A credit limit between the working balance before the refund and the working balance after
     * it, so the refund decides the outcome.
     */
    private static final BigDecimal LIMIT_BETWEEN_THE_TWO_BALANCES = new BigDecimal("50.00");

    /** Integer digits {@code WS-TEMP-BAL} holds, from its Picture clause. */
    private static final int WORKING_INTEGER_DIGITS =
            PicClause.WS_TEMP_BAL_PRECISION - PicClause.WS_TEMP_BAL_SCALE;

    /** Integer digits the cycle credit accumulator holds, from its Picture clause. */
    private static final int ACCUMULATOR_INTEGER_DIGITS =
            PicClause.ACCT_CURR_CYC_CREDIT_PRECISION - PicClause.ACCT_CURR_CYC_CREDIT_SCALE;

    /**
     * The smallest magnitude that overflows {@code WS-TEMP-BAL}, derived from
     * {@link PicClause#WS_TEMP_BAL_PRECISION} and {@link PicClause#WS_TEMP_BAL_SCALE} and not from
     * a literal. Ten raised to the ninth power is one billion.
     */
    private static final BigDecimal FIRST_OVERFLOWING_MAGNITUDE = CobolDecimal.truncateToScale(
            BigDecimal.TEN.pow(WORKING_INTEGER_DIGITS), PicClause.WS_TEMP_BAL_SCALE);

    /** A working balance of four integer digits, which the target field holds intact. */
    private static final BigDecimal SURVIVING_DIFFERENCE = new BigDecimal("1000.00");

    /**
     * The cycle debit that leaves {@link #SURVIVING_DIFFERENCE} once subtracted from
     * {@link #FIRST_OVERFLOWING_MAGNITUDE}. Both operands overflow the target field and their
     * difference does not.
     */
    private static final BigDecimal OFFSETTING_CYCLE_DEBIT = CobolDecimal.subtract(
            FIRST_OVERFLOWING_MAGNITUDE, SURVIVING_DIFFERENCE, PicClause.WS_TEMP_BAL_SCALE);

    /** A positive amount carrying a third fractional digit, which no money column holds. */
    private static final BigDecimal THIRD_DECIMAL_AMOUNT = new BigDecimal("10.005");

    /** {@link #THIRD_DECIMAL_AMOUNT} as a truncating store holds it. */
    private static final BigDecimal THIRD_DECIMAL_TRUNCATED = new BigDecimal("10.00");

    /**
     * A negative amount carrying a third fractional digit. Its magnitude is the largest refund the
     * 300 records of {@code app/data/ASCII/dailytran.txt} carry, with a third digit appended.
     */
    private static final BigDecimal NEGATIVE_THIRD_DECIMAL_AMOUNT = new BigDecimal("-998.335");

    /** {@link #NEGATIVE_THIRD_DECIMAL_AMOUNT} as a truncating store holds it. */
    private static final BigDecimal NEGATIVE_THIRD_DECIMAL_TRUNCATED = new BigDecimal("-998.33");

    /** An expiry date long past every capture timestamp the fixture carries. */
    private static final String LONG_PAST_EXPIRY_DATE = "2000-01-01";

    /** Characters {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} holds at
     * {@code app/cbl/CBTRN02C.cbl:L181}. */
    private static final int REASON_CODE_WIDTH = 4;

    /** Characters the reject reason text of this rule holds. */
    private static final int REASON_TEXT_LENGTH = 21;

    /** Characters {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} holds at
     * {@code app/cbl/CBTRN02C.cbl:L182}. */
    private static final int REASON_TEXT_WIDTH = 76;

    /** Words the reject reason text of this rule holds. */
    private static final int REASON_TEXT_WORDS = 2;

    /** The position this rule declares in the chain. */
    private static final int CHAIN_POSITION = 30;

    /** The step between declared chain positions, which leaves room between any two of them. */
    private static final int CHAIN_POSITION_STRIDE = 10;

    /** The rule under test, which takes no collaborator. */
    private CreditLimitRule rule;

    @BeforeEach
    void prepareRule() {
        rule = new CreditLimitRule();
    }

    /** The comparison at {@code app/cbl/CBTRN02C.cbl:L407}. */
    @Nested
    @DisplayName("The limit comparison")
    class LimitComparison {

        @Test
        @DisplayName("answers with nothing when the limit equals the working balance")
        void answersWithNothingAtTheLimit() {
            Optional<DeclineReason> outcome =
                    outcomeOf(ROUND_LIMIT, ROUND_LIMIT, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            assertEquals(Optional.empty(), outcome,
                    "app/cbl/CBTRN02C.cbl:L407 tests ACCT-CREDIT-LIMIT >= WS-TEMP-BAL, and"
                            + " app/cbl/CBTRN02C.cbl:L408 puts CONTINUE on that limb");
        }

        @Test
        @DisplayName("answers with reject code 0102 one cent over the limit")
        void answersWithRejectCodeOneCentOver() {
            BigDecimal oneCentOver = CobolDecimal.add(ROUND_LIMIT, ONE_CENT,
                    PicClause.WS_TEMP_BAL_SCALE);

            Optional<DeclineReason> outcome =
                    outcomeOf(ROUND_LIMIT, oneCentOver, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            assertAll(
                    () -> assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome,
                            "app/cbl/CBTRN02C.cbl:L410 assigns the reject reason on the ELSE at"
                                    + " app/cbl/CBTRN02C.cbl:L409"),
                    () -> assertEquals(0, oneCentOver.compareTo(new BigDecimal("500.01")),
                            "one cent over 500.00 is 500.01"));
        }

        @ParameterizedTest(name = "[{index}] a working balance of {0} against a limit of 500.00")
        @ValueSource(strings = {"0.00", "499.98", "499.99", "500.00"})
        @DisplayName("answers with nothing at or under the limit")
        void answersWithNothingUpToTheLimit(String workingBalance) {
            Optional<DeclineReason> outcome = outcomeOf(ROUND_LIMIT, new BigDecimal(workingBalance),
                    ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            assertEquals(Optional.empty(), outcome,
                    "app/cbl/CBTRN02C.cbl:L407 approves every value the limit reaches");
        }

        @ParameterizedTest(name = "[{index}] a working balance of {0} against a limit of 500.00")
        @ValueSource(strings = {"500.01", "500.02", "600.00", "999999999.99"})
        @DisplayName("answers with reject code 0102 over the limit")
        void answersWithRejectCodeOverTheLimit(String workingBalance) {
            Optional<DeclineReason> outcome = outcomeOf(ROUND_LIMIT, new BigDecimal(workingBalance),
                    ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome,
                    "app/cbl/CBTRN02C.cbl:L410-L412 assigns 102 and its text for every value the"
                            + " limit falls short of");
        }

        @Test
        @DisplayName("reads the value of the limit and not its scale")
        void readsTheValueAndNotTheScale() {
            BigDecimal limitWithoutFractionalDigits = new BigDecimal("500");
            BigDecimal workingBalance = CobolDecimal.add(
                    CobolDecimal.subtract(ROUND_LIMIT, ZERO_ACCUMULATOR,
                            PicClause.WS_TEMP_BAL_SCALE),
                    ZERO_ACCUMULATOR, PicClause.WS_TEMP_BAL_SCALE);

            Optional<DeclineReason> outcome = outcomeOf(limitWithoutFractionalDigits, ROUND_LIMIT,
                    ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            assertAll(
                    () -> assertNotEquals(limitWithoutFractionalDigits, workingBalance,
                            "the two carry one value at two scales, which equality separates"),
                    () -> assertEquals(0, limitWithoutFractionalDigits.compareTo(workingBalance),
                            "a comparison by value joins them"),
                    () -> assertEquals(Optional.empty(), outcome,
                            "app/cbl/CBTRN02C.cbl:L407 compares two numeric fields, and a store"
                                    + " into WS-TEMP-BAL carries no scale with it"));
        }

        @Test
        @DisplayName("answers with nothing for record one of the daily feed against account 7")
        void answersWithNothingForTheFixtureRecord() {
            Optional<DeclineReason> outcome = outcomeOf(ACCOUNT_7_CREDIT_LIMIT, ZERO_ACCUMULATOR,
                    ZERO_ACCUMULATOR, FIXTURE_AMOUNT);

            assertEquals(Optional.empty(), outcome,
                    "app/cbl/CBTRN02C.cbl:L403-L405 over record one of"
                            + " app/data/ASCII/dailytran.txt leaves 504.77, and account 7 of"
                            + " app/data/ASCII/acctdata.txt carries a limit of 2065.00");
        }

        @Test
        @DisplayName("answers with reject code 0102 for the same amount against account 30")
        void answersWithRejectCodeForTheNarrowestFixtureLimit() {
            Optional<DeclineReason> outcome = outcomeOf(ACCOUNT_30_CREDIT_LIMIT, ZERO_ACCUMULATOR,
                    ZERO_ACCUMULATOR, FIXTURE_AMOUNT);

            assertAll(
                    () -> assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), outcome,
                            "account 30 of app/data/ASCII/acctdata.txt carries a limit of 120.00,"
                                    + " which the working balance of"
                                    + " app/cbl/CBTRN02C.cbl:L403-L405 exceeds"),
                    () -> assertTrue(
                            ACCOUNT_30_CREDIT_LIMIT.compareTo(ACCOUNT_7_CREDIT_LIMIT) < 0,
                            "account 30 carries the narrower of the two fixture limits"));
        }
    }

    /**
     * The three values the formula reads, and the one it does not.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L403-L405} names {@code ACCT-CURR-CYC-CREDIT},
     * {@code ACCT-CURR-CYC-DEBIT} and {@code DALYTRAN-AMT}. It does not name
     * {@code ACCT-CURR-BAL}.
     */
    @Nested
    @DisplayName("The current balance")
    class CurrentBalance {

        @Test
        @DisplayName("holds no column on the snapshot row")
        void holdsNoColumn() {
            assertAll(
                    () -> assertEquals(Set.of(), rowAccessorsNaming("balance"),
                            "ACCT-CURR-BAL at app/cpy/CVACT01Y.cpy:L7 holds no column, so no"
                                    + " accessor can reach it"),
                    () -> assertEquals(Set.of(), rowAccessorsNaming("cashcredit"),
                            "ACCT-CASH-CREDIT-LIMIT at app/cpy/CVACT01Y.cpy:L9 holds none either"),
                    () -> assertEquals(Set.of(), rowAccessorsNaming("status"),
                            "ACCT-ACTIVE-STATUS at app/cpy/CVACT01Y.cpy:L6 holds none either"));
        }

        @Test
        @DisplayName("does not decide the outcome, where a balance-aware formula would")
        void doesNotDecideTheOutcome() {
            Optional<DeclineReason> outcome = outcomeOf(ACCOUNT_7_CREDIT_LIMIT, ZERO_ACCUMULATOR,
                    ZERO_ACCUMULATOR, AMOUNT_UNDER_LIMIT_OVER_BALANCE_SUM);
            BigDecimal balanceAwareReading = CobolDecimal.add(ACCOUNT_7_CURRENT_BALANCE,
                    AMOUNT_UNDER_LIMIT_OVER_BALANCE_SUM, PicClause.WS_TEMP_BAL_SCALE);

            assertAll(
                    () -> assertTrue(
                            ACCOUNT_7_CREDIT_LIMIT.compareTo(balanceAwareReading) < 0,
                            "193.00 plus 2000.00 reaches 2193.00, which the limit of 2065.00 falls"
                                    + " short of"),
                    () -> assertEquals(Optional.empty(), outcome,
                            "app/cbl/CBTRN02C.cbl:L403-L405 reads the two accumulators and the"
                                    + " amount, so the working balance is 2000.00"));
        }

        /**
         * Counts the reads {@code app/cbl/CBTRN02C.cbl:L403-L405} performs against the account
         * record, and refuses a fourth.
         */
        @Test
        @DisplayName("is one of three values the rule reads off the row and no fourth")
        void leavesTheRowWithThreeReads() {
            AccountCreditSnapshotEntity row =
                    snapshotOf(ACCOUNT_7_CREDIT_LIMIT, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            rule.evaluate(contextCarrying(FIXTURE_AMOUNT, row));

            verify(row).getCurrentCycleCredit();
            verify(row).getCurrentCycleDebit();
            verify(row).getCreditLimit();
            verifyNoMoreInteractions(row);
        }
    }

    /**
     * The store into {@code WS-TEMP-BAL PIC S9(09)V99} at {@code app/cbl/CBTRN02C.cbl:L187}.
     *
     * <p>No account of {@code app/data/ASCII/acctdata.txt} reaches one billion in either
     * accumulator, so every value below is constructed.
     */
    @Nested
    @DisplayName("The narrowed working field")
    class NarrowedWorkingField {

        @Test
        @DisplayName("holds one integer digit fewer than the values it meets")
        void holdsOneDigitFewer() {
            assertAll(
                    () -> assertEquals(11, PicClause.WS_TEMP_BAL_PRECISION,
                            "WS-TEMP-BAL PIC S9(09)V99 at app/cbl/CBTRN02C.cbl:L187 holds eleven"
                                    + " digits"),
                    () -> assertEquals(2, PicClause.WS_TEMP_BAL_SCALE,
                            "two of those eleven sit after the decimal point"),
                    () -> assertEquals(12, PicClause.ACCT_CURR_CYC_DEBIT_PRECISION,
                            "ACCT-CURR-CYC-DEBIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L14 holds"
                                    + " twelve"),
                    () -> assertEquals(ACCUMULATOR_INTEGER_DIGITS - 1, WORKING_INTEGER_DIGITS,
                            "WS-TEMP-BAL holds nine integer digits where"
                                    + " app/cpy/CVACT01Y.cpy:L13 and app/cpy/CVACT01Y.cpy:L14 hold"
                                    + " ten"),
                    () -> assertEquals(ACCUMULATOR_INTEGER_DIGITS,
                            PicClause.ACCT_CREDIT_LIMIT_PRECISION
                                    - PicClause.ACCT_CREDIT_LIMIT_SCALE,
                            "ACCT-CREDIT-LIMIT at app/cpy/CVACT01Y.cpy:L8 holds ten as well"),
                    () -> assertEquals(PicClause.WS_TEMP_BAL_SCALE, PicClause.DALYTRAN_AMT_SCALE,
                            "DALYTRAN-AMT at app/cpy/CVTRA06Y.cpy:L10 shares the fractional width"),
                    () -> assertEquals(0, FIRST_OVERFLOWING_MAGNITUDE.compareTo(
                            new BigDecimal("1000000000.00")),
                            "ten raised to the ninth power is one billion"));
        }

        @Test
        @DisplayName("answers with nothing at one billion, where the full value would not")
        void answersWithNothingAtOneBillion() {
            BigDecimal narrowed = CobolDecimal.truncateToPictureField(FIRST_OVERFLOWING_MAGNITUDE,
                    PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);

            Optional<DeclineReason> outcome = outcomeOf(ACCOUNT_7_CREDIT_LIMIT,
                    FIRST_OVERFLOWING_MAGNITUDE, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            assertAll(
                    () -> assertEquals(0, narrowed.compareTo(ZERO_ACCUMULATOR),
                            "one billion loses its high-order digit and nine zeros remain"),
                    () -> assertTrue(
                            ACCOUNT_7_CREDIT_LIMIT.compareTo(FIRST_OVERFLOWING_MAGNITUDE) < 0,
                            "the limit of 2065.00 falls short of the full value"),
                    () -> assertEquals(Optional.empty(), outcome,
                            "app/cbl/CBTRN02C.cbl:L403 stores into WS-TEMP-BAL before"
                                    + " app/cbl/CBTRN02C.cbl:L407 reads it"));
        }

        @Test
        @DisplayName("stores once at the end, so a surviving difference reaches the comparison")
        void storesOnceAtTheEnd() {
            BigDecimal limitOneCentUnder = CobolDecimal.subtract(SURVIVING_DIFFERENCE, ONE_CENT,
                    PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal narrowedOperandReading = CobolDecimal.subtract(
                    CobolDecimal.truncateToPictureField(FIRST_OVERFLOWING_MAGNITUDE,
                            PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE),
                    CobolDecimal.truncateToPictureField(OFFSETTING_CYCLE_DEBIT,
                            PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE),
                    PicClause.WS_TEMP_BAL_SCALE);

            Optional<DeclineReason> atTheDifference = outcomeOf(SURVIVING_DIFFERENCE,
                    FIRST_OVERFLOWING_MAGNITUDE, OFFSETTING_CYCLE_DEBIT, ZERO_ACCUMULATOR);
            Optional<DeclineReason> oneCentUnder = outcomeOf(limitOneCentUnder,
                    FIRST_OVERFLOWING_MAGNITUDE, OFFSETTING_CYCLE_DEBIT, ZERO_ACCUMULATOR);

            assertAll(
                    () -> assertEquals(0, OFFSETTING_CYCLE_DEBIT.compareTo(
                            new BigDecimal("999999000.00")),
                            "one billion less 1000.00 leaves 999999000.00, and"
                                    + " app/cpy/CVACT01Y.cpy:L14 holds either operand"),
                    () -> assertEquals(Optional.empty(), atTheDifference,
                            "the working balance is 1000.00, which a limit of 1000.00 reaches"),
                    () -> assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), oneCentUnder,
                            "a limit of 999.99 falls one cent short of 1000.00"),
                    () -> assertTrue(
                            limitOneCentUnder.compareTo(narrowedOperandReading) >= 0,
                            "a store applied to each operand leaves -999999000.00, which a limit"
                                    + " of 999.99 reaches"));
        }
    }

    /**
     * The sign routing at {@code app/cbl/CBTRN02C.cbl:L548-L552}, quoted below, read together with
     * the subtraction at {@code app/cbl/CBTRN02C.cbl:L404}.
     *
     * <pre>{@code
     *       2800-UPDATE-ACCOUNT-REC.
     *      * Update the balances in account record to reflect posted trans.
     *           ADD DALYTRAN-AMT  TO ACCT-CURR-BAL
     *           IF DALYTRAN-AMT >= 0
     *              ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
     *           ELSE
     *              ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
     *           END-IF
     * }</pre>
     */
    @Nested
    @DisplayName("A refunded cycle debit")
    class RefundedCycleDebit {

        @Test
        @DisplayName("raises the working balance to 100.00")
        void raisesTheWorkingBalance() {
            BigDecimal oneCentUnder = CobolDecimal.subtract(REFUND_RAISED_BALANCE, ONE_CENT,
                    PicClause.WS_TEMP_BAL_SCALE);

            Optional<DeclineReason> atTheBalance = outcomeOf(REFUND_RAISED_BALANCE,
                    ZERO_ACCUMULATOR, REFUNDED_CYCLE_DEBIT, ZERO_ACCUMULATOR);
            Optional<DeclineReason> oneCentUnderTheBalance = outcomeOf(oneCentUnder,
                    ZERO_ACCUMULATOR, REFUNDED_CYCLE_DEBIT, ZERO_ACCUMULATOR);
            Optional<DeclineReason> atZero = outcomeOf(ZERO_ACCUMULATOR, ZERO_ACCUMULATOR,
                    REFUNDED_CYCLE_DEBIT, ZERO_ACCUMULATOR);

            assertAll(
                    () -> assertEquals(Optional.empty(), atTheBalance,
                            "a limit of 100.00 reaches the working balance"
                                    + " app/cbl/CBTRN02C.cbl:L404 computes"),
                    () -> assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT),
                            oneCentUnderTheBalance,
                            "a limit of 99.99 falls one cent short of it"),
                    () -> assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), atZero,
                            "a limit of 0.00 falls short as well, so the working balance is"
                                    + " neither 0.00 nor -100.00"));
        }

        @Test
        @DisplayName("turns an answer of nothing into reject code 0102")
        void turnsNothingIntoARejectCode() {
            Optional<DeclineReason> withTheRefund = outcomeOf(LIMIT_BETWEEN_THE_TWO_BALANCES,
                    ZERO_ACCUMULATOR, REFUNDED_CYCLE_DEBIT, ZERO_ACCUMULATOR);
            Optional<DeclineReason> withoutIt = outcomeOf(LIMIT_BETWEEN_THE_TWO_BALANCES,
                    ZERO_ACCUMULATOR, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            assertAll(
                    () -> assertEquals(Optional.empty(), withoutIt,
                            "a cycle debit of 0.00 leaves a working balance of 0.00, which a limit"
                                    + " of 50.00 reaches"),
                    () -> assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT), withTheRefund,
                            "app/cbl/CBTRN02C.cbl:L551 seats -100.00 in the accumulator and"
                                    + " app/cbl/CBTRN02C.cbl:L404 subtracts it"),
                    () -> assertTrue(REFUNDED_CYCLE_DEBIT.compareTo(ZERO_ACCUMULATOR) < 0,
                            "the accumulator carries the sign the source stored in it"));
        }

        @ParameterizedTest(name = "[{index}] a cycle debit of {0} against a limit of 50.00")
        @ValueSource(strings = {"-0.01", "-50.01", "-100.00", "-998.33"})
        @DisplayName("tightens the outcome as the refunded magnitude grows")
        void tightensTheOutcome(String cycleDebit) {
            BigDecimal refunded = new BigDecimal(cycleDebit);
            BigDecimal raised = CobolDecimal.subtract(ZERO_ACCUMULATOR, refunded,
                    PicClause.WS_TEMP_BAL_SCALE);

            Optional<DeclineReason> outcome = outcomeOf(LIMIT_BETWEEN_THE_TWO_BALANCES,
                    ZERO_ACCUMULATOR, refunded, ZERO_ACCUMULATOR);

            boolean overTheLimit =
                    LIMIT_BETWEEN_THE_TWO_BALANCES.compareTo(raised) < 0;
            assertEquals(overTheLimit ? Optional.of(DeclineReason.OVER_CREDIT_LIMIT)
                    : Optional.empty(), outcome,
                    "app/cbl/CBTRN02C.cbl:L404 raises the working balance by the magnitude the"
                            + " accumulator carries");
        }
    }

    /** The reject code and its text, from {@code app/cbl/CBTRN02C.cbl:L410-L412}. */
    @Nested
    @DisplayName("The reject reason")
    class RejectReason {

        @Test
        @DisplayName("carries the zero-padded four-character code 0102")
        void carriesTheWireCode() {
            DeclineReason answer = rejectReasonOverTheLimit();

            assertAll(
                    () -> assertEquals("0102", answer.code(),
                            "WS-VALIDATION-FAIL-REASON PIC 9(04) at app/cbl/CBTRN02C.cbl:L181"
                                    + " zero-pads the 102 that app/cbl/CBTRN02C.cbl:L410 moves"),
                    () -> assertEquals(REASON_CODE_WIDTH, answer.code().length(),
                            "the field holds four characters"),
                    () -> assertEquals(102, answer.numericCode(),
                            "app/cbl/CBTRN02C.cbl:L410 moves 102"));
        }

        @Test
        @DisplayName("carries the text of app/cbl/CBTRN02C.cbl:L411 unedited")
        void carriesTheSourceText() {
            String text = rejectReasonOverTheLimit().description();

            assertAll(
                    () -> assertEquals(REASON_TEXT_LENGTH, text.length(),
                            "the literal at app/cbl/CBTRN02C.cbl:L411 holds twenty-one"
                                    + " characters"),
                    () -> assertTrue(text.length() <= REASON_TEXT_WIDTH,
                            "WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at"
                                    + " app/cbl/CBTRN02C.cbl:L182 holds seventy-six"),
                    () -> assertEquals(text.toUpperCase(Locale.ROOT), text,
                            "the source literal is upper case throughout"),
                    () -> assertEquals(text.trim(), text,
                            "the source literal carries no leading or trailing space"),
                    () -> assertEquals(-1, text.indexOf('-'),
                            "the source literal carries no hyphen"),
                    () -> assertEquals(REASON_TEXT_WORDS, text.split(" ").length,
                            "the source literal is two words separated by one space"));
        }
    }

    /**
     * Truncation toward zero, which every store performs where no {@code ROUNDED} phrase appears.
     *
     * <p>The three values the formula reads all carry two fractional digits, so their combination
     * is exact at that scale and no mode can change it. The first two scenarios below therefore
     * guard {@link CobolDecimal} directly with an operand carrying a third fractional digit. The
     * third drives the rule with such an operand.
     *
     * <p>This group is the one place in this package that names a rounding mode.
     */
    @Nested
    @DisplayName("Truncation toward zero")
    class TruncationTowardZero {

        @Test
        @DisplayName("guards the shared helper: it truncates where half-up rounds away")
        void theSharedHelperTruncatesWhereHalfUpRoundsAway() {
            BigDecimal truncated = CobolDecimal.add(ZERO_ACCUMULATOR, THIRD_DECIMAL_AMOUNT,
                    PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal halfUp = roundedTheWrongWay(THIRD_DECIMAL_AMOUNT, RoundingMode.HALF_UP);

            assertAll(
                    () -> assertEquals(0, truncated.compareTo(THIRD_DECIMAL_TRUNCATED),
                            "10.005 truncates to 10.00, and the store at"
                                    + " app/cbl/CBTRN02C.cbl:L403 carries no ROUNDED phrase"),
                    () -> assertNotEquals(0, truncated.compareTo(halfUp),
                            "half-up answers 10.01 on the same operand, so the two modes part"
                                    + " company"),
                    () -> assertEquals(0, roundedTheWrongWay(THIRD_DECIMAL_AMOUNT,
                            RoundingMode.FLOOR).compareTo(truncated),
                            "floor answers 10.00 on a positive operand, which is why the next"
                                    + " scenario uses a negative one"));
        }

        @Test
        @DisplayName("guards the shared helper: floor is wrong on a negative operand too")
        void theSharedHelperTruncatesWhereFloorRoundsDown() {
            BigDecimal truncated = CobolDecimal.add(ZERO_ACCUMULATOR,
                    NEGATIVE_THIRD_DECIMAL_AMOUNT, PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal floor = roundedTheWrongWay(NEGATIVE_THIRD_DECIMAL_AMOUNT,
                    RoundingMode.FLOOR);
            BigDecimal halfUp = roundedTheWrongWay(NEGATIVE_THIRD_DECIMAL_AMOUNT,
                    RoundingMode.HALF_UP);

            assertAll(
                    () -> assertEquals(0, truncated.compareTo(NEGATIVE_THIRD_DECIMAL_TRUNCATED),
                            "-998.335 truncates toward zero to -998.33, and fifty records of"
                                    + " app/data/ASCII/dailytran.txt carry a negative amount"),
                    () -> assertNotEquals(0, truncated.compareTo(floor),
                            "floor answers -998.34, one cent further from zero"),
                    () -> assertNotEquals(0, truncated.compareTo(halfUp),
                            "half-up answers -998.34 as well"),
                    () -> assertTrue(truncated.compareTo(floor) > 0,
                            "truncation toward zero leaves the larger of the two values"));
        }

        @Test
        @DisplayName("drives the rule: the working balance carries the truncated amount")
        void theRuleReadsTheTruncatedAmount() {
            BigDecimal halfUp = roundedTheWrongWay(THIRD_DECIMAL_AMOUNT, RoundingMode.HALF_UP);

            Optional<DeclineReason> outcome = outcomeOf(THIRD_DECIMAL_TRUNCATED, ZERO_ACCUMULATOR,
                    ZERO_ACCUMULATOR, THIRD_DECIMAL_AMOUNT);

            assertAll(
                    () -> assertTrue(THIRD_DECIMAL_TRUNCATED.compareTo(halfUp) < 0,
                            "a limit of 10.00 falls short of the half-up reading of 10.01"),
                    () -> assertEquals(Optional.empty(), outcome,
                            "app/cbl/CBTRN02C.cbl:L403 truncates, so a limit of 10.00 reaches the"
                                    + " working balance of 10.00"));
        }

        /**
         * Applies a mode the source never asks for, at the scale
         * {@link PicClause#WS_TEMP_BAL_SCALE} declares.
         *
         * @param value     the operand
         * @param wrongMode a mode other than truncation toward zero
         * @return the value that mode produces
         */
        private BigDecimal roundedTheWrongWay(BigDecimal value, RoundingMode wrongMode) {
            return value.setScale(PicClause.WS_TEMP_BAL_SCALE, wrongMode);
        }
    }

    /** What the rule exposes, and what it declares. */
    @Nested
    @DisplayName("The rule surface")
    class RuleSurface {

        @Test
        @DisplayName("exposes evaluate and segment, and neither answers with a flag")
        void exposesTwoMethods() throws NoSuchMethodException {
            Set<String> exposed = new TreeSet<>();
            for (Method method : CreditLimitRule.class.getDeclaredMethods()) {
                if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                exposed.add(method.getName());
                assertNotEquals(boolean.class, method.getReturnType(),
                        "app/cbl/CBTRN02C.cbl:L407 tests the approving condition and"
                                + " app/cbl/CBTRN02C.cbl:L410 assigns on the ELSE, so the answer"
                                + " must be a reject reason and never a flag");
                assertNotEquals(Boolean.class, method.getReturnType(),
                        "the answer must be a reject reason and never a flag");
            }

            assertEquals(Set.of("evaluate", "segment"), exposed,
                    "the public surface must be the two methods DeclineRule declares, so no"
                            + " differently named entry point can grow beside them");

            Method evaluation =
                    CreditLimitRule.class.getMethod("evaluate", DeclineRule.Context.class);
            assertEquals("java.util.Optional<com.carddemo.events.DeclineReason>",
                    evaluation.getGenericReturnType().getTypeName(),
                    "an absent answer is an approval and a present answer is the reject reason");
            assertEquals(0, evaluation.getDeclaredAnnotations().length,
                    "the evaluation method declares no runtime annotation, so no transaction"
                            + " boundary sits here");
        }

        @Test
        @DisplayName("takes no collaborator through one unannotated constructor")
        void takesNoCollaborator() {
            Constructor<?>[] constructors = CreditLimitRule.class.getConstructors();
            Set<String> fields = new TreeSet<>();
            for (Field field : CreditLimitRule.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    fields.add(field.getName());
                }
            }

            assertAll(
                    () -> assertEquals(1, constructors.length,
                            "the rule must offer one way to build it"),
                    () -> assertArrayEquals(new Class<?>[] {}, constructors[0].getParameterTypes(),
                            "app/cbl/CBTRN02C.cbl:L403-L407 opens no dataset of its own, so no"
                                    + " repository, collaborator or port reaches this rule"),
                    () -> assertEquals(0, constructors[0].getAnnotations().length,
                            "a sole constructor needs no injection annotation"),
                    () -> assertEquals(Set.of(), fields,
                            "a rule that reads its three values off the context holds no state"),
                    () -> assertNotNull(assertDoesNotThrow(CreditLimitRule::new),
                            "building the rule takes no argument"));
        }

        @Test
        @DisplayName("declares only the component and position annotations")
        void declaresTwoAnnotations() {
            Set<String> declared = new TreeSet<>();
            for (Annotation annotation : CreditLimitRule.class.getDeclaredAnnotations()) {
                declared.add(annotation.annotationType().getSimpleName());
            }

            assertEquals(Set.of("Component", "Order"), declared,
                    "app/cbl/CBTRN02C.cbl:L403-L413 writes nothing, so the rule declares no"
                            + " transaction boundary of its own");
        }

        @Test
        @DisplayName("sits at position 30 with room on either side")
        void sitsAtPositionThirty() {
            Order declared = CreditLimitRule.class.getAnnotation(Order.class);

            assertAll(
                    () -> assertNotNull(declared, "the rule must declare its chain position"),
                    () -> assertEquals(CHAIN_POSITION, declared.value(),
                            "the chain at app/cbl/CBTRN02C.cbl:L370-L378 reaches this test third"),
                    () -> assertEquals(0, declared.value() % CHAIN_POSITION_STRIDE,
                            "positions advance in tens, so a further rule slots between any two of"
                                    + " them without renumbering one"));
        }

        @Test
        @DisplayName("declares that a later rule of its segment can overwrite its answer")
        void declaresTheLastDeclineWinsSegment() {
            assertEquals(DeclineRule.Segment.LAST_DECLINE_WINS, rule.segment(),
                    "app/cbl/CBTRN02C.cbl:L413 closes this test and app/cbl/CBTRN02C.cbl:L414"
                            + " opens the next with no gate between them");
        }
    }

    /** A context reaching the rule before the account read seated its row. */
    @Nested
    @DisplayName("A missing snapshot")
    class MissingSnapshot {

        @Test
        @DisplayName("raises when no snapshot reached the call")
        void raisesWhenNoSnapshotReachedTheCall() {
            DeclineRule.Context context = new DeclineRule.Context(FIXTURE_CARD_NUMBER,
                    FIXTURE_AMOUNT, FIXTURE_ORIGIN_TIMESTAMP);

            NullPointerException raised = assertThrows(NullPointerException.class,
                    () -> rule.evaluate(context),
                    "app/cbl/CBTRN02C.cbl:L403 sits inside the NOT INVALID KEY limb of the account"
                            + " read, so no snapshot means the chain ran out of order");

            assertAll(
                    () -> assertNotNull(raised.getMessage(),
                            "the failure names what was missing"),
                    () -> assertTrue(raised.getMessage().contains("account snapshot"),
                            "the failure names the account snapshot"),
                    () -> assertNull(context.getAccountCreditSnapshot(),
                            "the rule seats nothing of its own"));
        }

        @Test
        @DisplayName("answers with neither an approval nor reject code 0102")
        void answersWithNeitherOutcome() {
            DeclineRule.Context context = new DeclineRule.Context(FIXTURE_CARD_NUMBER,
                    FIXTURE_AMOUNT, FIXTURE_ORIGIN_TIMESTAMP);
            AtomicReference<Optional<DeclineReason>> answer = new AtomicReference<>(null);

            assertThrows(NullPointerException.class,
                    () -> answer.set(rule.evaluate(context)),
                    "app/cbl/CBTRN02C.cbl:L410 is the one place this rule assigns a reject"
                            + " reason, and a missing snapshot never reaches it");

            assertNull(answer.get(),
                    "the call produced no answer at all, so neither an empty result nor reject"
                            + " code 0102 reached the caller");
        }
    }

    /** Behaviour a reader may expect that {@code app/cbl/CBTRN02C.cbl} does not have. */
    @Nested
    @DisplayName("Deliberate non-additions")
    class DeliberateNonAdditions {

        @Test
        @DisplayName("the expiry date on the row is never read")
        void leavesTheExpiryDateUnread() {
            AccountCreditSnapshotEntity row = snapshotAlsoCarrying(ACCOUNT_7_CREDIT_LIMIT,
                    ZERO_ACCUMULATOR, ZERO_ACCUMULATOR, LONG_PAST_EXPIRY_DATE);

            Optional<DeclineReason> outcome =
                    rule.evaluate(contextCarrying(FIXTURE_AMOUNT, row));

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "app/cbl/CBTRN02C.cbl:L414 places the expiry test after this one"),
                    () -> verify(row, never()).getAccountExpirationDate());
        }

        @Test
        @DisplayName("the card number does not decide the outcome")
        void leavesTheCardNumberUnread() {
            Optional<DeclineReason> againstTheFixtureCard = rule.evaluate(contextCarrying(
                    FIXTURE_CARD_NUMBER, FIXTURE_AMOUNT,
                    snapshotOf(ACCOUNT_30_CREDIT_LIMIT, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR)));
            Optional<DeclineReason> againstZeros = rule.evaluate(contextCarrying(
                    ALL_ZERO_CARD_NUMBER, FIXTURE_AMOUNT,
                    snapshotOf(ACCOUNT_30_CREDIT_LIMIT, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR)));

            assertAll(
                    () -> assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT),
                            againstTheFixtureCard,
                            "the limit of account 30 falls short of 504.77"),
                    () -> assertEquals(againstTheFixtureCard, againstZeros,
                            "app/cbl/CBTRN02C.cbl:L403-L407 names no card field, and"
                                    + " app/jcl/POSTTRAN.jcl allocates no card dataset"));
        }
    }

    /**
     * Runs the rule over one set of the four values the comparison reads.
     *
     * <p>Most scenarios above differ in one of these four, so each states only what it changes.
     *
     * @param creditLimit {@code ACCT-CREDIT-LIMIT} from {@code app/cpy/CVACT01Y.cpy:L8}
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT} from {@code app/cpy/CVACT01Y.cpy:L13}
     * @param cycleDebit  {@code ACCT-CURR-CYC-DEBIT} from {@code app/cpy/CVACT01Y.cpy:L14}
     * @param amount      {@code DALYTRAN-AMT} from {@code app/cpy/CVTRA06Y.cpy:L10}
     * @return the answer the rule gives for those values
     */
    private Optional<DeclineReason> outcomeOf(BigDecimal creditLimit, BigDecimal cycleCredit,
            BigDecimal cycleDebit, BigDecimal amount) {
        return rule.evaluate(contextCarrying(FIXTURE_CARD_NUMBER, amount,
                snapshotOf(creditLimit, cycleCredit, cycleDebit)));
    }

    /**
     * Runs the rule over a working balance one cent past the limit and returns what it assigns.
     *
     * @return the reject reason of {@code app/cbl/CBTRN02C.cbl:L410-L412}
     */
    private DeclineReason rejectReasonOverTheLimit() {
        Optional<DeclineReason> outcome = outcomeOf(ROUND_LIMIT,
                CobolDecimal.add(ROUND_LIMIT, ONE_CENT, PicClause.WS_TEMP_BAL_SCALE),
                ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);
        return outcome.orElseThrow();
    }

    /**
     * Builds the values one authorization call carries, with a snapshot row already seated.
     *
     * <p>The preceding rule seats that row, mirroring the working-storage area
     * {@code app/cbl/CBTRN02C.cbl:L395} reads into.
     *
     * @param cardNumber the card number, already padded to sixteen characters
     * @param amount     the transaction amount
     * @param row        the row an account read resolved
     * @return the values the rule reads
     */
    private static DeclineRule.Context contextCarrying(String cardNumber, BigDecimal amount,
            AccountCreditSnapshotEntity row) {
        DeclineRule.Context context =
                new DeclineRule.Context(cardNumber, amount, FIXTURE_ORIGIN_TIMESTAMP);
        context.setAccountCreditSnapshot(row);
        return context;
    }

    /**
     * Builds the values one authorization call carries, for the fixture card number.
     *
     * @param amount the transaction amount
     * @param row    the row an account read resolved
     * @return the values the rule reads
     */
    private static DeclineRule.Context contextCarrying(BigDecimal amount,
            AccountCreditSnapshotEntity row) {
        return contextCarrying(FIXTURE_CARD_NUMBER, amount, row);
    }

    /**
     * Builds the snapshot row an account read resolves to, carrying the three values the comparison
     * reads and nothing else.
     *
     * <p>The row is a stub, and it answers for those three accessors alone. A fourth read therefore
     * shows up as an unverified interaction.
     *
     * @param creditLimit the credit limit at two digits after the decimal point
     * @param cycleCredit the cycle credit accumulator, either sign
     * @param cycleDebit  the cycle debit accumulator, either sign
     * @return the resolved row
     */
    private static AccountCreditSnapshotEntity snapshotOf(BigDecimal creditLimit,
            BigDecimal cycleCredit, BigDecimal cycleDebit) {
        AccountCreditSnapshotEntity row = mock(AccountCreditSnapshotEntity.class);
        when(row.getCreditLimit()).thenReturn(creditLimit);
        when(row.getCurrentCycleCredit()).thenReturn(cycleCredit);
        when(row.getCurrentCycleDebit()).thenReturn(cycleDebit);
        return row;
    }

    /**
     * Builds the snapshot row an account read resolves to, also answering for the expiry date.
     *
     * @param creditLimit    the credit limit at two digits after the decimal point
     * @param cycleCredit    the cycle credit accumulator, either sign
     * @param cycleDebit     the cycle debit accumulator, either sign
     * @param expirationDate the expiry date, ten characters held as text
     * @return the resolved row
     */
    private static AccountCreditSnapshotEntity snapshotAlsoCarrying(BigDecimal creditLimit,
            BigDecimal cycleCredit, BigDecimal cycleDebit, String expirationDate) {
        AccountCreditSnapshotEntity row = snapshotOf(creditLimit, cycleCredit, cycleDebit);
        when(row.getAccountExpirationDate()).thenReturn(expirationDate);
        return row;
    }

    /**
     * Collects every public accessor of the snapshot row whose name carries one fragment.
     *
     * @param fragment the lower-case fragment to look for
     * @return the names of any accessors carrying it
     */
    private static Set<String> rowAccessorsNaming(String fragment) {
        Set<String> named = new TreeSet<>();
        for (Method method : AccountCreditSnapshotEntity.class.getMethods()) {
            if (method.getName().toLowerCase(Locale.ROOT).contains(fragment)) {
                named.add(method.getName());
            }
        }
        return named;
    }
}
