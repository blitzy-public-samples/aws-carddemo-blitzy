package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.equivalence.CopybookRecordParser.AccountRecord;
import com.carddemo.equivalence.CopybookRecordParser.CardCrossReferenceRecord;
import com.carddemo.equivalence.CopybookRecordParser.DailyTransactionRecord;
import com.carddemo.equivalence.CopybookRecordParser.DisclosureGroupRecord;
import com.carddemo.equivalence.CopybookRecordParser.TransactionCategoryBalanceRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins truncation toward zero at the money-moving statements of the source programs.
 *
 * <p>The {@code ROUNDED} phrase appears zero times across all twenty-eight programs in
 * {@code app/cbl/}, so every arithmetic store truncates. Two scale-two operands leave nothing to
 * discard, so no rounding mode moves a result at an add or a subtract. The divide at
 * {@code app/cbl/CBACT04C.cbl:L464-L465} is the one rounding-observable statement in the source.
 *
 * <p>All fifty {@code app/data/ASCII/tcatbal.txt} balances are 0.00, so the seeded fixtures reach
 * that divide with zeros. The chained-row derivation and the synthetic negative case below are
 * ADDITIVE, and {@code card-platform/docs/decision-log.md} carries an entry for each.
 *
 * <p>Failsafe runs this class at {@code integration-test}. Only {@code mvn verify} runs it;
 * {@code mvn test} runs none of it and reports a pass.
 */
@DisplayName("Decimal truncation parity at the money-moving source statements")
class DecimalTruncationEquivalenceTest {

    /** {@code DIS-ACCT-GROUP-ID} the retry at {@code app/cbl/CBACT04C.cbl:L437} moves into the key. */
    private static final String DEFAULT_GROUP_NAME = "DEFAULT";

    /**
     * {@link #DEFAULT_GROUP_NAME} padded to the {@link PicClause#DIS_ACCT_GROUP_ID_WIDTH} bytes
     * {@code app/data/ASCII/discgrp.txt} holds.
     */
    private static final String DEFAULT_GROUP_ID = DEFAULT_GROUP_NAME
            + " ".repeat(PicClause.DIS_ACCT_GROUP_ID_WIDTH - DEFAULT_GROUP_NAME.length());

    /** {@code TRAN-CAT-BAL} after the {@code INITIALIZE} at {@code app/cbl/CBTRN02C.cbl:L504}. */
    private static final BigDecimal INITIALIZED_CATEGORY_BALANCE =
            BigDecimal.ZERO.setScale(PicClause.TRAN_CAT_BAL_SCALE);

    /**
     * {@code DIS-INT-RATE} of the {@code DEFAULT} group at type {@code 01} category {@code 0001},
     * and the rate every chained type {@code 01} row resolves to.
     */
    private static final BigDecimal STANDARD_RATE = new BigDecimal("15.00");

    /**
     * {@code DIS-INT-RATE} of the {@code DEFAULT} group at type {@code 01} categories {@code 0002}
     * through {@code 0004}. Every fixture record carries category {@code 0001}, so no chained row
     * reaches this rate.
     */
    private static final BigDecimal UNEXERCISED_CATEGORY_RATE = new BigDecimal("25.00");

    /** {@code DIS-INT-RATE} of the {@code DEFAULT} group at type {@code 03} category {@code 0001}. */
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");

    /**
     * Multiplies a discarded remainder by two for comparison against
     * {@link CobolDecimal#INTEREST_DIVISOR}, which locates the halfway point with no second divide.
     */
    private static final BigDecimal ROUNDING_HALFWAY_MULTIPLIER = BigDecimal.valueOf(2L);

    /**
     * A working value of ten integer digits, one more than an {@code S9(09)V99} field holds. All
     * fifty fixture accounts open with cycle accumulators of 0.00 and credit limits reaching
     * 9750.00, so no fixture value comes near this magnitude.
     */
    private static final BigDecimal OVERWIDE_WORKING_VALUE = new BigDecimal("2000000456.789");

    /** {@link #OVERWIDE_WORKING_VALUE} as an {@code S9(09)V99} field holds it. */
    private static final BigDecimal OVERWIDE_WORKING_VALUE_STORED = new BigDecimal("456.78");

    /** {@link #OVERWIDE_WORKING_VALUE} negated. */
    private static final BigDecimal OVERWIDE_NEGATIVE_WORKING_VALUE =
            OVERWIDE_WORKING_VALUE.negate();

    /** {@link #OVERWIDE_WORKING_VALUE_STORED} negated. */
    private static final BigDecimal OVERWIDE_NEGATIVE_WORKING_VALUE_STORED =
            OVERWIDE_WORKING_VALUE_STORED.negate();

    /**
     * An {@code ACCT-CREDIT-LIMIT} above {@link #OVERWIDE_WORKING_VALUE_STORED} and below
     * {@link #OVERWIDE_WORKING_VALUE}.
     */
    private static final BigDecimal LIMIT_BETWEEN_THE_TWO_VALUES = new BigDecimal("1000.00");

    /** A chained category balance of type {@code 01}, and the first of the two the fixture holds. */
    private static final InterestDivide FIRST_CHAINED_ROW = new InterestDivide(
            "TRAN-CAT-BAL 1287.09 at DIS-INT-RATE 15.00",
            new BigDecimal("1287.09"), STANDARD_RATE,
            new BigDecimal("16.08"), new BigDecimal("16.09"), new BigDecimal("16.08"));

    /** A second chained category balance of type {@code 01}. */
    private static final InterestDivide SECOND_CHAINED_ROW = new InterestDivide(
            "TRAN-CAT-BAL 2339.97 at DIS-INT-RATE 15.00",
            new BigDecimal("2339.97"), STANDARD_RATE,
            new BigDecimal("29.24"), new BigDecimal("29.25"), new BigDecimal("29.24"));

    /**
     * A positive balance whose quotient lands exactly halfway between two cents. The value is the
     * {@code ACCT-CURR-BAL} of the first record of {@code app/data/ASCII/acctdata.txt}.
     */
    private static final InterestDivide HALF_CENT_BOUNDARY_ROW = new InterestDivide(
            "TRAN-CAT-BAL 194.00 at DIS-INT-RATE 15.00",
            new BigDecimal("194.00"), STANDARD_RATE,
            new BigDecimal("2.42"), new BigDecimal("2.43"), new BigDecimal("2.42"));

    /**
     * ADDITIVE. A chained negative balance paired with {@link #UNEXERCISED_CATEGORY_RATE}, the one
     * construction that separates {@link RoundingMode#DOWN} from {@link RoundingMode#FLOOR}. Both
     * operands come from the fixtures; no chained row pairs them.
     */
    private static final InterestDivide SYNTHETIC_NEGATIVE_ROW = new InterestDivide(
            "TRAN-CAT-BAL -763.00 at DIS-INT-RATE 25.00",
            new BigDecimal("-763.00"), UNEXERCISED_CATEGORY_RATE,
            new BigDecimal("-15.89"), new BigDecimal("-15.90"), new BigDecimal("-15.90"));

    /** The four worked divides, in the order the assertions below read them. */
    private static final List<InterestDivide> WORKED_DIVIDES = List.of(
            FIRST_CHAINED_ROW, SECOND_CHAINED_ROW, HALF_CENT_BOUNDARY_ROW, SYNTHETIC_NEGATIVE_ROW);

    @Nested
    @DisplayName("Add and subtract statements, which discard nothing at scale two")
    class ExactAddAndSubtractSites {

        @Test
        @DisplayName("CBTRN02C L547 stores the exact sum into the account balance")
        void theAccountBalanceStoreKeepsTheExactSum() {
            AccountRecord account = firstFixtureAccount();
            BigDecimal amount = firstCreditAmount();

            BigDecimal balance = CobolDecimal.add(account.currentBalance(), amount,
                    PicClause.ACCT_CURR_BAL_SCALE);

            assertEquals(0, account.currentBalance().add(amount).compareTo(balance),
                    "app/cbl/CBTRN02C.cbl:L547 stores the exact sum; compareTo reads the value "
                            + "alone");
            assertEquals(PicClause.ACCT_CURR_BAL_SCALE, balance.scale(),
                    "app/cbl/CBTRN02C.cbl:L547 stores into ACCT-CURR-BAL, which holds two decimals");
        }

        @Test
        @DisplayName("CBTRN02C L549 and L551 each store the exact sum and leave the other accumulator alone")
        void oneAccumulatorTakesTheWholeAmount() {
            AccountRecord account = firstFixtureAccount();
            BigDecimal credit = account.currentCycleCredit();
            BigDecimal debit = account.currentCycleDebit();

            CycleAccumulators afterCredit = postToAccumulators(credit, debit, firstCreditAmount());
            CycleAccumulators afterDebit = postToAccumulators(credit, debit, firstDebitAmount());

            assertEquals(0, credit.add(firstCreditAmount()).compareTo(afterCredit.credit()),
                    "app/cbl/CBTRN02C.cbl:L549 stores the exact sum into ACCT-CURR-CYC-CREDIT");
            assertEquals(0, debit.compareTo(afterCredit.debit()),
                    "app/cbl/CBTRN02C.cbl:L551 did not run for an amount of zero or more");
            assertEquals(0, debit.add(firstDebitAmount()).compareTo(afterDebit.debit()),
                    "app/cbl/CBTRN02C.cbl:L551 stores the exact sum into ACCT-CURR-CYC-DEBIT, and a "
                            + "negative amount lowers that accumulator");
            assertEquals(0, credit.compareTo(afterDebit.credit()),
                    "app/cbl/CBTRN02C.cbl:L549 did not run for a negative amount");
        }

        @Test
        @DisplayName("CBTRN02C L508 and L527 store the exact sum into the category balance")
        void theCategoryBalanceStoresKeepTheExactSum() {
            BigDecimal amount = firstFixtureAmount();
            BigDecimal created = CobolDecimal.add(INITIALIZED_CATEGORY_BALANCE, amount,
                    PicClause.TRAN_CAT_BAL_SCALE);
            BigDecimal updated = CobolDecimal.add(created, amount, PicClause.TRAN_CAT_BAL_SCALE);

            assertEquals(0, amount.compareTo(created),
                    "app/cbl/CBTRN02C.cbl:L508 adds into the record INITIALIZE cleared at L504");
            assertEquals(0, amount.add(amount).compareTo(updated),
                    "app/cbl/CBTRN02C.cbl:L527 adds into the record the keyed read returned");
            assertEquals(PicClause.TRAN_CAT_BAL_SCALE, updated.scale(),
                    "app/cbl/CBTRN02C.cbl:L527 stores into TRAN-CAT-BAL, which holds two decimals");
        }

        @Test
        @DisplayName("CBTRN02C L403-L405 stores the exact three-operand working balance")
        void theOverlimitWorkingBalanceKeepsTheExactValue() {
            AccountRecord account = firstFixtureAccount();
            BigDecimal amount = firstFixtureAmount();
            BigDecimal exact = account.currentCycleCredit()
                    .subtract(account.currentCycleDebit()).add(amount);

            BigDecimal working = overlimitWorkingBalance(account.currentCycleCredit(),
                    account.currentCycleDebit(), amount);

            assertEquals(0, exact.compareTo(working),
                    "app/cbl/CBTRN02C.cbl:L403-L405 stores cycle credit less cycle debit plus the "
                            + "amount with no digit discarded");
            assertEquals(PicClause.WS_TEMP_BAL_SCALE, working.scale(),
                    "app/cbl/CBTRN02C.cbl:L187 declares WS-TEMP-BAL with two decimals");
        }

        @Test
        @DisplayName("COBIL00C L224 and L234 leave a zero balance across all fifty fixture accounts")
        void theBillPaymentSubtractionKeepsTheExactValue() {
            for (AccountRecord account : CardDemoFixtureLoader.loadAccounts()) {
                BigDecimal paymentAmount = account.currentBalance();
                BigDecimal remaining = CobolDecimal.subtract(account.currentBalance(),
                        paymentAmount, PicClause.ACCT_CURR_BAL_SCALE);

                assertEquals(0, remaining.compareTo(BigDecimal.ZERO),
                        "app/cbl/COBIL00C.cbl:L234 subtracts the balance app/cbl/COBIL00C.cbl:L224 "
                                + "copied, which leaves zero; compareTo reads the value alone");
                assertEquals(PicClause.ACCT_CURR_BAL_SCALE, remaining.scale(),
                        "app/cbl/COBIL00C.cbl:L234 stores into ACCT-CURR-BAL, which holds two "
                                + "decimals");
            }
        }

        @Test
        @DisplayName("HALF_UP moves no result at any add or subtract site across all 300 fixture amounts")
        void halfUpIsImmaterialAtEveryAddAndSubtractSite() {
            PostingRun run = postingRun();

            assertEquals(0L, run.unresolvedRecords(),
                    "every fixture card resolves through app/cbl/CBTRN02C.cbl:L385 and every "
                            + "cross-reference through app/cbl/CBTRN02C.cbl:L397");
            assertTrue(run.storeComparisons() > 0L,
                    "the sweep compared no store, so the fixture load returned nothing");
            assertEquals(0L, run.modeSensitiveStores(),
                    "HALF_UP is immaterial at app/cbl/CBTRN02C.cbl:L403-L405, L508, L527 and "
                            + "L547-L551: a scale-two operand added to or subtracted from a "
                            + "scale-two operand discards no digit. Compared "
                            + run.storeComparisons() + " stores.");
        }

        @Test
        @DisplayName("CBTRN02C L548 routes an amount of zero to the cycle credit accumulator")
        void aZeroAmountReachesTheCycleCreditAccumulator() {
            BigDecimal zeroAtStoreScale = INITIALIZED_CATEGORY_BALANCE;

            assertNotEquals(zeroAtStoreScale, BigDecimal.ZERO,
                    "0.00 and 0 differ under BigDecimal.equals, which reads the scale as well as "
                            + "the value");
            assertEquals(0, zeroAtStoreScale.compareTo(BigDecimal.ZERO),
                    "0.00 and 0 hold one numeric value, which compareTo reads");
            assertEquals(CycleAccumulator.CREDIT, accumulatorFor(zeroAtStoreScale),
                    "app/cbl/CBTRN02C.cbl:L548 tests DALYTRAN-AMT >= 0, so 0.00 reaches "
                            + "ACCT-CURR-CYC-CREDIT at app/cbl/CBTRN02C.cbl:L549");
            assertEquals(CycleAccumulator.CREDIT, accumulatorFor(BigDecimal.ZERO),
                    "app/cbl/CBTRN02C.cbl:L548 reads the value, so the scale of a zero amount does "
                            + "not change the accumulator");
            assertEquals(CycleAccumulator.CREDIT, accumulatorFor(FIRST_CHAINED_ROW.balance()),
                    "app/cbl/CBTRN02C.cbl:L549 takes a positive amount");
            assertEquals(CycleAccumulator.DEBIT, accumulatorFor(SYNTHETIC_NEGATIVE_ROW.balance()),
                    "app/cbl/CBTRN02C.cbl:L551 takes a negative amount");
        }
    }

    @Nested
    @DisplayName("The interest divide at CBACT04C L464-L465, the one rounding-observable statement")
    class RoundingObservableInterestDivide {

        @Test
        @DisplayName("A chained balance of 1287.09 truncates to 16.08 where HALF_UP gives 16.09")
        void theFirstChainedRowSeparatesTruncationFromHalfUp() {
            assertWorkedDivide(FIRST_CHAINED_ROW);
        }

        @Test
        @DisplayName("A chained balance of 2339.97 truncates to 29.24 where HALF_UP gives 29.25")
        void theSecondChainedRowSeparatesTruncationFromHalfUp() {
            assertWorkedDivide(SECOND_CHAINED_ROW);
        }

        @Test
        @DisplayName("A positive quotient at the half-cent boundary separates HALF_UP and not FLOOR")
        void aPositiveQuotientLeavesFloorAndTruncationAgreeing() {
            assertWorkedDivide(HALF_CENT_BOUNDARY_ROW);

            assertEquals(0, HALF_CENT_BOUNDARY_ROW.truncated()
                            .compareTo(HALF_CENT_BOUNDARY_ROW.floorResult()),
                    "FLOOR and DOWN agree on a positive quotient, so a positive value proves "
                            + "nothing about FLOOR at app/cbl/CBACT04C.cbl:L465");
        }

        @Test
        @DisplayName("ADDITIVE: a negative chained balance at 25.00 separates truncation from HALF_UP and FLOOR")
        void theSyntheticNegativeRowSeparatesTruncationFromBothAlternatives() {
            assertWorkedDivide(SYNTHETIC_NEGATIVE_ROW);

            assertEquals(0, SYNTHETIC_NEGATIVE_ROW.halfUp()
                            .compareTo(SYNTHETIC_NEGATIVE_ROW.floorResult()),
                    "HALF_UP and FLOOR both move a negative quotient away from zero, and "
                            + "app/cbl/CBACT04C.cbl:L465 moves it toward zero");
            assertTrue(SYNTHETIC_NEGATIVE_ROW.truncated()
                            .compareTo(SYNTHETIC_NEGATIVE_ROW.floorResult()) > 0,
                    "truncation toward zero leaves the larger of the two negative results");
        }

        @Test
        @DisplayName("CobolDecimal reproduces every worked divide and no alternative mode does")
        void everyWorkedDivideTruncatesTowardZero() {
            for (InterestDivide divide : WORKED_DIVIDES) {
                assertEquals(divide.truncated(), monthlyInterest(divide.balance(), divide.rate()),
                        "app/cbl/CBACT04C.cbl:L464-L465 truncates toward zero for " + divide.label()
                                + "; assertEquals reads the value and the scale");
                assertNotEquals(divide.truncated(),
                        monthlyInterestUnder(divide.balance(), divide.rate(), RoundingMode.HALF_UP),
                        "HALF_UP moves the result for " + divide.label()
                                + ", so it is not equivalent to the source store");
            }
        }

        @Test
        @DisplayName("A zero DEFAULT rate leaves the divide at zero under every mode")
        void aZeroRateLeavesNothingForAnyModeToMove() {
            BigDecimal truncated = monthlyInterest(SYNTHETIC_NEGATIVE_ROW.balance(), ZERO_RATE);

            assertEquals(0, truncated.compareTo(BigDecimal.ZERO),
                    "app/cbl/CBACT04C.cbl:L465 divides a zero product, which leaves zero");
            assertEquals(0, monthlyInterestUnder(SYNTHETIC_NEGATIVE_ROW.balance(), ZERO_RATE,
                            RoundingMode.HALF_UP).compareTo(truncated),
                    "a zero rate discards no digit, so HALF_UP matches the source store");
            assertEquals(0, monthlyInterestUnder(SYNTHETIC_NEGATIVE_ROW.balance(), ZERO_RATE,
                            RoundingMode.FLOOR).compareTo(truncated),
                    "a zero rate discards no digit, so FLOOR matches the source store");
        }
    }

    @Nested
    @DisplayName("ADDITIVE: category balances chained from the 300 fixture records into the divide")
    class ChainedCategoryBalances {

        @Test
        @DisplayName("The validation gate at CBTRN02C L370-L420 admits some records and stops others")
        void theValidationGateSplitsTheFixtureFeed() {
            PostingRun run = postingRun();

            assertEquals(PicClause.DAILYTRAN_FIXTURE_RECORD_COUNT,
                    run.postedRecords() + run.gatedRecords(),
                    "every fixture record either reached app/cbl/CBTRN02C.cbl:L424 or "
                            + "app/cbl/CBTRN02C.cbl:L446");
            assertTrue(run.postedRecords() > 0L,
                    "app/cbl/CBTRN02C.cbl:L424 posted no record, so the chain holds no balance");
            assertTrue(run.gatedRecords() > 0L,
                    "app/cbl/CBTRN02C.cbl:L403-L420 stopped no record. The chained balances and "
                            + "every count derived from them change when the gate stops admitting "
                            + "records.");
            assertTrue(run.categoryBalances().size() > 0,
                    "app/cbl/CBTRN02C.cbl:L467 created no category balance row");
        }

        @Test
        @DisplayName("The DEFAULT group retry at CBACT04C L437 resolves a rate for every chained row")
        void theDefaultGroupResolvesEveryChainedRow() {
            DivideTally tally = divideTally();

            assertEquals(0L, tally.unresolvedRates(),
                    "app/cbl/CBACT04C.cbl:L437 moves DEFAULT into the group identifier and leaves "
                            + "the type and category codes, so every chained key resolves");
            assertTrue(tally.rateBearingRows() > 0L,
                    "app/cbl/CBACT04C.cbl:L214 admitted no row to the divide");
            assertTrue(tally.rateBearingRows() < tally.rows(),
                    "app/cbl/CBACT04C.cbl:L214 admitted every row, and the DEFAULT group holds a "
                            + "zero rate at type 03 category 0001");
        }

        @Test
        @DisplayName("The derived HALF_UP divergence count agrees with exact remainder arithmetic")
        void theDerivedHalfUpCountAgreesWithRemainderArithmetic() {
            DivideTally tally = divideTally();

            assertEquals(tally.halfUpByRemainder(), tally.halfUpRows(),
                    "the count of rows where HALF_UP moves the quotient must equal the count whose "
                            + "discarded remainder reaches half of "
                            + CobolDecimal.INTEREST_DIVISOR + ". Rows measured: " + tally.rows());
            assertTrue(tally.halfUpRows() > 0L,
                    "no chained row separates truncation from HALF_UP, so the divide at "
                            + "app/cbl/CBACT04C.cbl:L465 observes no rounding on this fixture");
            assertTrue(tally.halfUpRows() <= tally.rateBearingRows(),
                    "a row app/cbl/CBACT04C.cbl:L214 kept out of the divide cannot diverge");
        }

        @Test
        @DisplayName("No chained row separates truncation from FLOOR")
        void noChainedRowSeparatesTruncationFromFloor() {
            DivideTally tally = divideTally();

            assertEquals(tally.floorByRemainder(), tally.floorRows(),
                    "the count of rows where FLOOR moves the quotient must equal the count with a "
                            + "negative product and a discarded remainder");
            assertEquals(0L, tally.floorRows(),
                    "no chained row separates truncation from FLOOR across " + tally.rows()
                            + " rows, which is what the synthetic negative case covers");
        }

        @Test
        @DisplayName("Every negative chained balance carries the zero DEFAULT rate at type 03")
        void everyNegativeChainedRowCarriesTheZeroRate() {
            DivideTally tally = divideTally();

            assertTrue(tally.negativeRows() > 0L,
                    "the chained rows hold no negative balance, so app/cbl/CBTRN02C.cbl:L551 never "
                            + "ran");
            assertEquals(tally.negativeRows(), tally.negativeRowsAtZeroRate(),
                    "every negative chained row resolves to the DEFAULT rate of 0.00 at type 03 "
                            + "category 0001, so app/cbl/CBACT04C.cbl:L214 keeps every negative "
                            + "balance out of the divide");
        }
    }

    @Nested
    @DisplayName("Picture-field narrowing at the three nine-digit working fields")
    class PictureFieldNarrowing {

        @Test
        @DisplayName("CBTRN02C L187 drops the high-order digit of a value reaching one billion")
        void theOverlimitWorkingFieldDropsTheHighOrderDigit() {
            BigDecimal stored = CobolDecimal.truncateToPictureField(OVERWIDE_WORKING_VALUE,
                    PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);

            assertEquals(OVERWIDE_WORKING_VALUE_STORED, stored,
                    "WS-TEMP-BAL at app/cbl/CBTRN02C.cbl:L187 holds "
                            + (PicClause.WS_TEMP_BAL_PRECISION - PicClause.WS_TEMP_BAL_SCALE)
                            + " integer digits and drops the digits above them");
            assertTrue(stored.compareTo(OVERWIDE_WORKING_VALUE) < 0,
                    "the store into the narrower field lowers the value the comparison reads");
        }

        @Test
        @DisplayName("CBTRN02C L407 approves the narrowed value under a limit the full value exceeds")
        void theNarrowedWorkingBalanceFitsUnderALimitTheFullValueExceeds() {
            BigDecimal stored = CobolDecimal.truncateToPictureField(OVERWIDE_WORKING_VALUE,
                    PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);

            assertTrue(LIMIT_BETWEEN_THE_TWO_VALUES.compareTo(stored) >= 0,
                    "app/cbl/CBTRN02C.cbl:L407 tests ACCT-CREDIT-LIMIT >= WS-TEMP-BAL, and the "
                            + "narrowed value passes that test");
            assertTrue(LIMIT_BETWEEN_THE_TWO_VALUES.compareTo(OVERWIDE_WORKING_VALUE) < 0,
                    "the same limit fails the same test against the value before the store");
        }

        @Test
        @DisplayName("The narrowing truncates a negative value toward zero and not toward FLOOR")
        void theNarrowingKeepsTheSignAndTruncatesTowardZero() {
            BigDecimal stored = CobolDecimal.truncateToPictureField(
                    OVERWIDE_NEGATIVE_WORKING_VALUE, PicClause.WS_TEMP_BAL_PRECISION,
                    PicClause.WS_TEMP_BAL_SCALE);

            assertEquals(OVERWIDE_NEGATIVE_WORKING_VALUE_STORED, stored,
                    "a COBOL store into a narrower numeric field keeps the sign and truncates "
                            + "toward zero");
            assertTrue(stored.signum() < 0,
                    "the store into WS-TEMP-BAL at app/cbl/CBTRN02C.cbl:L187 keeps the sign");
        }

        /**
         * Narrows a value into each interest working field of
         * {@code app/cbl/CBACT04C.cbl:L164-L169} and into {@code WS-TEMP-BAL}.
         */
        @Test
        @DisplayName("CBACT04C L164-L169 narrows the two interest working fields the same way")
        void theInterestWorkingFieldsNarrowTheSameWay() {
            BigDecimal monthly = CobolDecimal.truncateToPictureField(OVERWIDE_WORKING_VALUE,
                    PicClause.WS_MONTHLY_INT_PRECISION, PicClause.WS_MONTHLY_INT_SCALE);
            BigDecimal total = CobolDecimal.truncateToPictureField(OVERWIDE_WORKING_VALUE,
                    PicClause.WS_TOTAL_INT_PRECISION, PicClause.WS_TOTAL_INT_SCALE);

            assertEquals(OVERWIDE_WORKING_VALUE_STORED, monthly,
                    "WS-MONTHLY-INT at app/cbl/CBACT04C.cbl:L168 holds nine integer digits");
            assertEquals(OVERWIDE_WORKING_VALUE_STORED, total,
                    "WS-TOTAL-INT at app/cbl/CBACT04C.cbl:L169 holds nine integer digits");
            assertEquals(PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_MONTHLY_INT_PRECISION,
                    "the three nine-digit working fields share one precision");
        }

        @Test
        @DisplayName("CBACT04C L352 accumulates interest into the wider account balance")
        void theAccountBalanceHoldsMoreIntegerDigitsThanTheInterestFields() {
            BigDecimal accumulated = CobolDecimal.add(OVERWIDE_WORKING_VALUE_STORED,
                    OVERWIDE_WORKING_VALUE_STORED, PicClause.ACCT_CURR_BAL_SCALE);
            BigDecimal stored = CobolDecimal.truncateToPictureField(accumulated,
                    PicClause.ACCT_CURR_BAL_PRECISION, PicClause.ACCT_CURR_BAL_SCALE);

            assertTrue(PicClause.ACCT_CURR_BAL_PRECISION > PicClause.WS_TOTAL_INT_PRECISION,
                    "ACCT-CURR-BAL at app/cpy/CVACT01Y.cpy:L7 holds more digits than WS-TOTAL-INT "
                            + "at app/cbl/CBACT04C.cbl:L169");
            assertEquals(accumulated, stored,
                    "app/cbl/CBACT04C.cbl:L352 adds WS-TOTAL-INT into ACCT-CURR-BAL, which drops "
                            + "no digit of this sum");
        }
    }

    // Worked divides and accumulator selection.

    /**
     * One interest divide of {@code app/cbl/CBACT04C.cbl:L464-L465} together with the result each
     * rounding mode produces at {@link PicClause#WS_MONTHLY_INT_SCALE}.
     *
     * @param label       the two operands, for a failure message
     * @param balance     {@code TRAN-CAT-BAL}
     * @param rate        {@code DIS-INT-RATE}
     * @param truncated   the result {@link RoundingMode#DOWN} produces
     * @param halfUp      the result {@link RoundingMode#HALF_UP} produces
     * @param floorResult the result {@link RoundingMode#FLOOR} produces
     */
    private record InterestDivide(String label, BigDecimal balance, BigDecimal rate,
            BigDecimal truncated, BigDecimal halfUp, BigDecimal floorResult) { }

    /** The accumulator {@code app/cbl/CBTRN02C.cbl:L548} selects for one amount. */
    private enum CycleAccumulator { CREDIT, DEBIT }

    /**
     * {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT} after one posting.
     *
     * @param credit {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13}
     * @param debit  {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14}
     */
    private record CycleAccumulators(BigDecimal credit, BigDecimal debit) { }

    /** The three parts of {@code TRAN-CAT-KEY} at {@code app/cpy/CVTRA01Y.cpy:L5-L8}. */
    private record CategoryKey(String accountId, String typeCode, String categoryCode) { }

    /** The type and category parts of {@code DIS-GROUP-KEY} at {@code app/cpy/CVTRA02Y.cpy:L7-L8}. */
    private record RateCode(String typeCode, String categoryCode) { }

    /**
     * One pass of the documented posting rule over {@code app/data/ASCII/dailytran.txt}.
     *
     * @param categoryBalances    the rows {@code app/cbl/CBTRN02C.cbl:L467} created or updated
     * @param postedRecords       records that reached {@code app/cbl/CBTRN02C.cbl:L424}
     * @param gatedRecords        records the validation at {@code app/cbl/CBTRN02C.cbl:L370-L420}
     *                            stopped
     * @param unresolvedRecords   records whose card or account did not resolve
     * @param storeComparisons    add and subtract stores the sweep measured
     * @param modeSensitiveStores stores where HALF_UP moved the result
     */
    private record PostingRun(Map<CategoryKey, BigDecimal> categoryBalances, long postedRecords,
            long gatedRecords, long unresolvedRecords, long storeComparisons,
            long modeSensitiveStores) { }

    /**
     * Counts taken over the chained rows at the divide of {@code app/cbl/CBACT04C.cbl:L464-L465}.
     *
     * @param rows                    chained category balance rows
     * @param rateBearingRows         rows {@code app/cbl/CBACT04C.cbl:L214} admits to the divide
     * @param halfUpRows              rows where {@link RoundingMode#HALF_UP} moves the quotient
     * @param halfUpByRemainder       the same count taken from the discarded remainder alone
     * @param floorRows               rows where {@link RoundingMode#FLOOR} moves the quotient
     * @param floorByRemainder        the same count taken from the discarded remainder alone
     * @param negativeRows            rows holding a negative balance
     * @param negativeRowsAtZeroRate  negative rows whose resolved rate is zero
     * @param unresolvedRates         rows for which no rate resolved
     */
    private record DivideTally(long rows, long rateBearingRows, long halfUpRows,
            long halfUpByRemainder, long floorRows, long floorByRemainder, long negativeRows,
            long negativeRowsAtZeroRate, long unresolvedRates) { }

    /** Reproduces the accumulator choice at {@code app/cbl/CBTRN02C.cbl:L548}. */
    private static CycleAccumulator accumulatorFor(BigDecimal amount) {
        return amount.signum() >= 0 ? CycleAccumulator.CREDIT : CycleAccumulator.DEBIT;
    }

    /** Reproduces {@code app/cbl/CBTRN02C.cbl:L548-L552} and leaves the other accumulator alone. */
    private static CycleAccumulators postToAccumulators(BigDecimal credit, BigDecimal debit,
            BigDecimal amount) {
        if (accumulatorFor(amount) == CycleAccumulator.CREDIT) {
            return new CycleAccumulators(
                    CobolDecimal.add(credit, amount, PicClause.ACCT_CURR_CYC_CREDIT_SCALE), debit);
        }
        return new CycleAccumulators(credit,
                CobolDecimal.add(debit, amount, PicClause.ACCT_CURR_CYC_DEBIT_SCALE));
    }

    /** Reproduces {@code app/cbl/CBTRN02C.cbl:L403-L405} and the store its Picture clause narrows. */
    private static BigDecimal overlimitWorkingBalance(BigDecimal cycleCredit, BigDecimal cycleDebit,
            BigDecimal amount) {
        BigDecimal difference = CobolDecimal.subtract(cycleCredit, cycleDebit,
                PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal computed = CobolDecimal.add(difference, amount, PicClause.WS_TEMP_BAL_SCALE);
        return CobolDecimal.truncateToPictureField(computed, PicClause.WS_TEMP_BAL_PRECISION,
                PicClause.WS_TEMP_BAL_SCALE);
    }

    /** Reproduces {@code app/cbl/CBACT04C.cbl:L464-L465} through the pinned helper. */
    private static BigDecimal monthlyInterest(BigDecimal balance, BigDecimal rate) {
        return CobolDecimal.multiplyThenDivide(balance, rate, CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE);
    }

    /** Computes the same divide under an alternative mode, for the negative assertions above. */
    private static BigDecimal monthlyInterestUnder(BigDecimal balance, BigDecimal rate,
            RoundingMode mode) {
        return balance.multiply(rate).divide(CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE, mode);
    }

    /**
     * Reports whether {@link RoundingMode#HALF_UP} moves the quotient, taken from the discarded
     * remainder and no rounding mode. The remainder reaches half the divisor exactly when a
     * half-up store lands on the next cent.
     */
    private static boolean halfUpMovesTheQuotient(BigDecimal balance, BigDecimal rate) {
        BigDecimal discarded = scaledProduct(balance, rate)
                .remainder(CobolDecimal.INTEREST_DIVISOR).abs();
        return discarded.multiply(ROUNDING_HALFWAY_MULTIPLIER)
                .compareTo(CobolDecimal.INTEREST_DIVISOR) >= 0;
    }

    /**
     * Reports whether {@link RoundingMode#FLOOR} moves the quotient, taken from the discarded
     * remainder and no rounding mode. FLOOR parts from truncation on a negative quotient carrying a
     * remainder.
     */
    private static boolean floorMovesTheQuotient(BigDecimal balance, BigDecimal rate) {
        BigDecimal product = scaledProduct(balance, rate);
        return product.signum() < 0
                && product.remainder(CobolDecimal.INTEREST_DIVISOR).signum() != 0;
    }

    /** The product of {@code app/cbl/CBACT04C.cbl:L465} carried up to the scale of the store. */
    private static BigDecimal scaledProduct(BigDecimal balance, BigDecimal rate) {
        return balance.multiply(rate).movePointRight(PicClause.WS_MONTHLY_INT_SCALE);
    }

    /** Asserts one worked divide against all three modes. */
    private static void assertWorkedDivide(InterestDivide divide) {
        assertEquals(divide.truncated(), monthlyInterest(divide.balance(), divide.rate()),
                "app/cbl/CBACT04C.cbl:L464-L465 truncates toward zero for " + divide.label()
                        + "; assertEquals reads the value and the scale");
        assertEquals(divide.halfUp(),
                monthlyInterestUnder(divide.balance(), divide.rate(), RoundingMode.HALF_UP),
                "HALF_UP gives a different result for " + divide.label());
        assertEquals(divide.floorResult(),
                monthlyInterestUnder(divide.balance(), divide.rate(), RoundingMode.FLOOR),
                "FLOOR gives this result for " + divide.label());
        assertEquals(halfUpMovesTheQuotient(divide.balance(), divide.rate()),
                divide.truncated().compareTo(divide.halfUp()) != 0,
                "the remainder test and the two stores must agree for " + divide.label());
        assertEquals(floorMovesTheQuotient(divide.balance(), divide.rate()),
                divide.truncated().compareTo(divide.floorResult()) != 0,
                "the remainder test and the two stores must agree for " + divide.label());
    }

    // The chained posting run and the counts taken over its rows.

    /**
     * Runs the documented posting rule over all {@link PicClause#DAILYTRAN_FIXTURE_RECORD_COUNT}
     * fixture records and measures every add and subtract store on the way.
     *
     * <p>Validation follows {@code app/cbl/CBTRN02C.cbl:L370-L420} and admits a record to
     * {@code app/cbl/CBTRN02C.cbl:L467} and {@code app/cbl/CBTRN02C.cbl:L545}. The cycle
     * accumulators a posting moves feed the credit limit test of every later record.</p>
     *
     * @return the rows the run produced together with the store counts
     */
    private static PostingRun postingRun() {
        Map<String, AccountRecord> accounts = CardDemoFixtureLoader.accountsByAccountId();
        Map<String, CardCrossReferenceRecord> crossReferences =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
        Map<CategoryKey, BigDecimal> seeds = seededCategoryBalances();
        Map<CategoryKey, BigDecimal> balances = new LinkedHashMap<>();
        Map<String, BigDecimal> currentBalance = new LinkedHashMap<>();
        Map<String, BigDecimal> cycleCredit = new LinkedHashMap<>();
        Map<String, BigDecimal> cycleDebit = new LinkedHashMap<>();
        for (AccountRecord account : accounts.values()) {
            currentBalance.put(account.accountId(), account.currentBalance());
            cycleCredit.put(account.accountId(), account.currentCycleCredit());
            cycleDebit.put(account.accountId(), account.currentCycleDebit());
        }

        long posted = 0L;
        long gated = 0L;
        long unresolved = 0L;
        long comparisons = 0L;
        long modeSensitive = 0L;
        for (DailyTransactionRecord transaction : CardDemoFixtureLoader.loadDailyTransactions()) {
            CardCrossReferenceRecord crossReference =
                    crossReferences.get(transaction.cardNumber());
            AccountRecord account = crossReference == null
                    ? null : accounts.get(crossReference.accountId());
            if (account == null) {
                unresolved++;
                gated++;
                continue;
            }

            String accountId = account.accountId();
            BigDecimal amount = transaction.amount();
            BigDecimal credit = cycleCredit.get(accountId);
            BigDecimal debit = cycleDebit.get(accountId);

            comparisons += 2L;
            modeSensitive += modeSensitiveSubtract(credit, debit, PicClause.WS_TEMP_BAL_SCALE);
            modeSensitive += modeSensitiveAdd(
                    CobolDecimal.subtract(credit, debit, PicClause.WS_TEMP_BAL_SCALE), amount,
                    PicClause.WS_TEMP_BAL_SCALE);

            BigDecimal working = overlimitWorkingBalance(credit, debit, amount);
            boolean overLimit = account.creditLimit().compareTo(working) < 0;
            boolean expired = account.expirationDate().compareTo(
                    CopybookRecordParser.timestampDatePart(transaction.originTimestamp())) < 0;
            if (overLimit || expired) {
                gated++;
                continue;
            }

            CategoryKey key = new CategoryKey(accountId, transaction.typeCode(),
                    transaction.categoryCode());
            BigDecimal categoryBase = balances.containsKey(key)
                    ? balances.get(key)
                    : seeds.getOrDefault(key, INITIALIZED_CATEGORY_BALANCE);
            BigDecimal accountBase = currentBalance.get(accountId);

            comparisons += 3L;
            modeSensitive += modeSensitiveAdd(categoryBase, amount, PicClause.TRAN_CAT_BAL_SCALE);
            modeSensitive += modeSensitiveAdd(accountBase, amount, PicClause.ACCT_CURR_BAL_SCALE);
            modeSensitive += accumulatorFor(amount) == CycleAccumulator.CREDIT
                    ? modeSensitiveAdd(credit, amount, PicClause.ACCT_CURR_CYC_CREDIT_SCALE)
                    : modeSensitiveAdd(debit, amount, PicClause.ACCT_CURR_CYC_DEBIT_SCALE);

            balances.put(key, CobolDecimal.add(categoryBase, amount,
                    PicClause.TRAN_CAT_BAL_SCALE));
            currentBalance.put(accountId, CobolDecimal.add(accountBase, amount,
                    PicClause.ACCT_CURR_BAL_SCALE));
            CycleAccumulators moved = postToAccumulators(credit, debit, amount);
            cycleCredit.put(accountId, moved.credit());
            cycleDebit.put(accountId, moved.debit());
            posted++;
        }
        return new PostingRun(Map.copyOf(balances), posted, gated, unresolved, comparisons,
                modeSensitive);
    }

    /** Takes every count of {@link DivideTally} over the rows {@link #postingRun()} produced. */
    private static DivideTally divideTally() {
        Map<CategoryKey, BigDecimal> balances = postingRun().categoryBalances();
        Map<RateCode, BigDecimal> rates = defaultGroupRates();
        long rateBearing = 0L;
        long halfUp = 0L;
        long halfUpByRemainder = 0L;
        long floor = 0L;
        long floorByRemainder = 0L;
        long negative = 0L;
        long negativeAtZeroRate = 0L;
        long unresolvedRates = 0L;

        for (Map.Entry<CategoryKey, BigDecimal> row : balances.entrySet()) {
            BigDecimal balance = row.getValue();
            BigDecimal rate = rates.get(
                    new RateCode(row.getKey().typeCode(), row.getKey().categoryCode()));
            if (rate == null) {
                unresolvedRates++;
                continue;
            }
            if (balance.signum() < 0) {
                negative++;
                if (rate.signum() == 0) {
                    negativeAtZeroRate++;
                }
            }
            if (rate.signum() == 0) {
                continue;
            }

            rateBearing++;
            BigDecimal truncated = monthlyInterest(balance, rate);
            if (truncated.compareTo(monthlyInterestUnder(balance, rate, RoundingMode.HALF_UP)) != 0) {
                halfUp++;
            }
            if (truncated.compareTo(monthlyInterestUnder(balance, rate, RoundingMode.FLOOR)) != 0) {
                floor++;
            }
            if (halfUpMovesTheQuotient(balance, rate)) {
                halfUpByRemainder++;
            }
            if (floorMovesTheQuotient(balance, rate)) {
                floorByRemainder++;
            }
        }
        return new DivideTally(balances.size(), rateBearing, halfUp, halfUpByRemainder, floor,
                floorByRemainder, negative, negativeAtZeroRate, unresolvedRates);
    }

    /** Indexes {@code app/data/ASCII/tcatbal.txt} by the key of {@code app/cpy/CVTRA01Y.cpy:L5}. */
    private static Map<CategoryKey, BigDecimal> seededCategoryBalances() {
        Map<CategoryKey, BigDecimal> seeds = new LinkedHashMap<>();
        for (TransactionCategoryBalanceRecord row
                : CardDemoFixtureLoader.loadTransactionCategoryBalances()) {
            seeds.put(new CategoryKey(row.accountId(), row.typeCode(), row.categoryCode()),
                    row.balance());
        }
        return Map.copyOf(seeds);
    }

    /**
     * Reads the {@code DEFAULT} group rows of {@code app/data/ASCII/discgrp.txt}.
     *
     * <p>{@code ACCT-GROUP-ID} at {@code app/cpy/CVACT01Y.cpy:L16} is ten spaces on all fifty
     * fixture accounts, so the keyed read at {@code app/cbl/CBACT04C.cbl:L416} misses and the retry
     * at {@code app/cbl/CBACT04C.cbl:L437-L438} resolves every row through this group.</p>
     *
     * @return the resolved rate for each type and category the group carries
     */
    private static Map<RateCode, BigDecimal> defaultGroupRates() {
        Map<RateCode, BigDecimal> rates = new LinkedHashMap<>();
        for (DisclosureGroupRecord row : CardDemoFixtureLoader.loadDisclosureGroups()) {
            if (DEFAULT_GROUP_ID.equals(row.accountGroupId())) {
                rates.put(new RateCode(row.transactionTypeCode(), row.transactionCategoryCode()),
                        row.interestRate());
            }
        }
        return Map.copyOf(rates);
    }

    /** Reports 1 when HALF_UP moves the sum this store keeps, and 0 when it does not. */
    private static long modeSensitiveAdd(BigDecimal augend, BigDecimal addend, int scale) {
        BigDecimal exact = augend.add(addend);
        return exact.setScale(scale, RoundingMode.DOWN)
                .compareTo(exact.setScale(scale, RoundingMode.HALF_UP)) == 0 ? 0L : 1L;
    }

    /** Reports 1 when HALF_UP moves the difference this store keeps, and 0 when it does not. */
    private static long modeSensitiveSubtract(BigDecimal minuend, BigDecimal subtrahend, int scale) {
        BigDecimal exact = minuend.subtract(subtrahend);
        return exact.setScale(scale, RoundingMode.DOWN)
                .compareTo(exact.setScale(scale, RoundingMode.HALF_UP)) == 0 ? 0L : 1L;
    }

    /** The first record of {@code app/data/ASCII/acctdata.txt}. */
    private static AccountRecord firstFixtureAccount() {
        return CardDemoFixtureLoader.loadAccounts().getFirst();
    }

    /** {@code DALYTRAN-AMT} of the first record of {@code app/data/ASCII/dailytran.txt}. */
    private static BigDecimal firstFixtureAmount() {
        return CardDemoFixtureLoader.loadDailyTransactions().getFirst().amount();
    }

    /** The first {@code DALYTRAN-AMT} of the fixture that {@code app/cbl/CBTRN02C.cbl:L549} takes. */
    private static BigDecimal firstCreditAmount() {
        return firstFixtureAmountWhere(CycleAccumulator.CREDIT);
    }

    /** The first {@code DALYTRAN-AMT} of the fixture that {@code app/cbl/CBTRN02C.cbl:L551} takes. */
    private static BigDecimal firstDebitAmount() {
        return firstFixtureAmountWhere(CycleAccumulator.DEBIT);
    }

    /**
     * Finds the first fixture amount the branch at {@code app/cbl/CBTRN02C.cbl:L548} routes to one
     * accumulator.
     *
     * @param accumulator the accumulator the amount reaches
     * @return the first matching {@code DALYTRAN-AMT}
     * @throws IllegalStateException when the fixture holds no amount reaching {@code accumulator}
     */
    private static BigDecimal firstFixtureAmountWhere(CycleAccumulator accumulator) {
        for (DailyTransactionRecord transaction : CardDemoFixtureLoader.loadDailyTransactions()) {
            if (accumulatorFor(transaction.amount()) == accumulator) {
                return transaction.amount();
            }
        }
        throw new IllegalStateException("app/data/ASCII/dailytran.txt holds no DALYTRAN-AMT that "
                + "app/cbl/CBTRN02C.cbl:L548 routes to " + accumulator);
    }
}
