package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies fixed-point stores at the money-moving COBOL arithmetic sites.
 * The {@code ROUNDED} phrase occurs in none of the twenty-eight source programs.
 * Add and subtract sites are exact at scale two; the interest divide exposes rounding.
 * Failsafe runs this class only under {@code mvn verify}.
 */
@DisplayName("Decimal truncation parity at the money-moving source statements")
class DecimalTruncationEquivalenceTest {

    private static final BigDecimal ADD_LEFT = new BigDecimal("123.45");
    private static final BigDecimal ADD_RIGHT = new BigDecimal("67.89");
    private static final BigDecimal ADD_RESULT = new BigDecimal("191.34");
    private static final BigDecimal SUBTRACT_RESULT = new BigDecimal("55.56");
    private static final BigDecimal FIRST_REAL_BALANCE = new BigDecimal("1287.09");
    private static final BigDecimal FIRST_REAL_DOWN = new BigDecimal("16.08");
    private static final BigDecimal FIRST_REAL_HALF_UP = new BigDecimal("16.09");
    private static final BigDecimal SECOND_REAL_BALANCE = new BigDecimal("2339.97");
    private static final BigDecimal SECOND_REAL_DOWN = new BigDecimal("29.24");
    private static final BigDecimal SECOND_REAL_HALF_UP = new BigDecimal("29.25");
    private static final BigDecimal POSITIVE_BOUNDARY_BALANCE = new BigDecimal("194.00");
    private static final BigDecimal POSITIVE_BOUNDARY_DOWN = new BigDecimal("2.42");
    private static final BigDecimal POSITIVE_BOUNDARY_HALF_UP = new BigDecimal("2.43");
    private static final BigDecimal STANDARD_RATE = new BigDecimal("15.00");
    private static final BigDecimal HIGH_RATE = new BigDecimal("25.00");
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");
    private static final BigDecimal SYNTHETIC_NEGATIVE_BALANCE = new BigDecimal("-763.00");
    private static final BigDecimal SYNTHETIC_NEGATIVE_DOWN = new BigDecimal("-15.89");
    private static final BigDecimal SYNTHETIC_NEGATIVE_OTHER = new BigDecimal("-15.90");
    private static final BigDecimal PRECISION_BOUNDARY_VALUE =
            new BigDecimal("1000000100.009");
    private static final BigDecimal PRECISION_BOUNDARY_STORED = new BigDecimal("100.00");
    private static final String DEFAULT_GROUP =
            "DEFAULT" + " ".repeat(PicClause.DIS_ACCT_GROUP_ID_WIDTH - "DEFAULT".length());

    @Nested
    @DisplayName("Add and subtract statements")
    class ExactScaleTwoSites {

        @Test
        void categoryAndAccountAddsPreserveTheExactScaleTwoValue() {
            BigDecimal categoryCreate = CobolDecimal.add(BigDecimal.ZERO, ADD_RIGHT,
                    PicClause.TRAN_CAT_BAL_SCALE);
            BigDecimal categoryUpdate = CobolDecimal.add(ADD_LEFT, ADD_RIGHT,
                    PicClause.TRAN_CAT_BAL_SCALE);
            BigDecimal accountUpdate = CobolDecimal.add(ADD_LEFT, ADD_RIGHT,
                    PicClause.ACCT_CURR_BAL_SCALE);

            assertNumericEquals(ADD_RIGHT, categoryCreate,
                    "CBTRN02C L508 adds into an initialized category balance");
            assertNumericEquals(ADD_RESULT, categoryUpdate,
                    "CBTRN02C L527 adds into an existing category balance");
            assertNumericEquals(ADD_RESULT, accountUpdate,
                    "CBTRN02C L547 adds into the current account balance");
        }

        @Test
        void theOverlimitAndBillPaymentSubtractionsPreserveTheExactValue() {
            BigDecimal overlimitDifference = CobolDecimal.subtract(ADD_LEFT, ADD_RIGHT,
                    PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal billPaymentBalance = CobolDecimal.subtract(ADD_LEFT, ADD_LEFT,
                    PicClause.ACCT_CURR_BAL_SCALE);

            assertNumericEquals(SUBTRACT_RESULT, overlimitDifference,
                    "CBTRN02C L403-L404 subtracts cycle debit from cycle credit");
            assertNumericEquals(BigDecimal.ZERO, billPaymentBalance,
                    "COBIL00C L234 subtracts the copied balance from itself");
        }

        @Test
        void halfUpIsImmaterialAtTheExactAddAndSubtractSites() {
            BigDecimal halfUpAdd = ADD_LEFT.add(ADD_RIGHT).setScale(
                    PicClause.ACCT_CURR_BAL_SCALE, RoundingMode.HALF_UP);
            BigDecimal halfUpSubtract = ADD_LEFT.subtract(ADD_RIGHT).setScale(
                    PicClause.WS_TEMP_BAL_SCALE, RoundingMode.HALF_UP);

            assertNumericEquals(CobolDecimal.add(ADD_LEFT, ADD_RIGHT,
                            PicClause.ACCT_CURR_BAL_SCALE),
                    halfUpAdd, "two scale-two addends produce no discarded fraction");
            assertNumericEquals(CobolDecimal.subtract(ADD_LEFT, ADD_RIGHT,
                            PicClause.WS_TEMP_BAL_SCALE),
                    halfUpSubtract, "two scale-two operands produce no discarded fraction");
        }
    }

    @Nested
    @DisplayName("Interest divide at CBACT04C L464-L465")
    class RoundingObservableSite {

        @Test
        void theFirstRealChainedBalanceSeparatesDownFromHalfUp() {
            BigDecimal down = monthlyInterest(FIRST_REAL_BALANCE, STANDARD_RATE);
            BigDecimal halfUp = roundedMonthlyInterest(FIRST_REAL_BALANCE, STANDARD_RATE,
                    RoundingMode.HALF_UP);

            assertEquals(FIRST_REAL_DOWN, down,
                    "1287.09 at 15.00 truncates to 16.08");
            assertEquals(FIRST_REAL_HALF_UP, halfUp,
                    "HALF_UP would produce 16.09 for the same source values");
            assertFalse(down.equals(halfUp),
                    "the negative assertion keeps HALF_UP from replacing truncation");
        }

        @Test
        void theSecondRealChainedBalanceSeparatesDownFromHalfUp() {
            assertEquals(SECOND_REAL_DOWN,
                    monthlyInterest(SECOND_REAL_BALANCE, STANDARD_RATE),
                    "2339.97 at 15.00 truncates to 29.24");
            assertEquals(SECOND_REAL_HALF_UP,
                    roundedMonthlyInterest(SECOND_REAL_BALANCE, STANDARD_RATE,
                            RoundingMode.HALF_UP),
                    "HALF_UP would produce 29.25");
        }

        @Test
        void aPositiveBoundaryDoesNotSeparateDownFromFloor() {
            BigDecimal down = monthlyInterest(POSITIVE_BOUNDARY_BALANCE, STANDARD_RATE);
            BigDecimal halfUp = roundedMonthlyInterest(POSITIVE_BOUNDARY_BALANCE, STANDARD_RATE,
                    RoundingMode.HALF_UP);
            BigDecimal floor = roundedMonthlyInterest(POSITIVE_BOUNDARY_BALANCE, STANDARD_RATE,
                    RoundingMode.FLOOR);

            assertEquals(POSITIVE_BOUNDARY_DOWN, down,
                    "194.00 at 15.00 truncates to 2.42");
            assertEquals(POSITIVE_BOUNDARY_HALF_UP, halfUp,
                    "HALF_UP would produce 2.43");
            assertEquals(POSITIVE_BOUNDARY_DOWN, floor,
                    "FLOOR matches DOWN on this positive value");
        }

        @Test
        @DisplayName("ADDITIVE: a real 25.00 rate with a synthetic negative balance separates FLOOR")
        void aSyntheticNegativeCaseSeparatesDownFromFloor() {
            BigDecimal down = monthlyInterest(SYNTHETIC_NEGATIVE_BALANCE, HIGH_RATE);
            BigDecimal halfUp = roundedMonthlyInterest(SYNTHETIC_NEGATIVE_BALANCE, HIGH_RATE,
                    RoundingMode.HALF_UP);
            BigDecimal floor = roundedMonthlyInterest(SYNTHETIC_NEGATIVE_BALANCE, HIGH_RATE,
                    RoundingMode.FLOOR);

            assertEquals(SYNTHETIC_NEGATIVE_DOWN, down,
                    "-763.00 at the fixture rate 25.00 truncates toward zero");
            assertEquals(SYNTHETIC_NEGATIVE_OTHER, halfUp,
                    "HALF_UP moves the negative result away from zero");
            assertEquals(SYNTHETIC_NEGATIVE_OTHER, floor,
                    "FLOOR moves the negative result toward negative infinity");
        }
    }

    @Nested
    @DisplayName("ADDITIVE chained fixture balances")
    class ChainedFixtureMeasurement {

        @Test
        void theDerivedHalfUpAndFloorCountsMatchExactRemainderArithmetic() {
            Map<CategoryKey, BigDecimal> balances = categoryBalancesAfterPosting();
            Map<RateCode, BigDecimal> rates = defaultRates();
            long actualHalfUpDifferences = 0;
            long actualFloorDifferences = 0;
            long expectedHalfUpDifferences = 0;
            long expectedFloorDifferences = 0;

            for (Map.Entry<CategoryKey, BigDecimal> row : balances.entrySet()) {
                BigDecimal rate = rates.get(
                        new RateCode(row.getKey().typeCode(), row.getKey().categoryCode()));
                BigDecimal down = monthlyInterest(row.getValue(), rate);
                BigDecimal halfUp = roundedMonthlyInterest(row.getValue(), rate,
                        RoundingMode.HALF_UP);
                BigDecimal floor = roundedMonthlyInterest(row.getValue(), rate,
                        RoundingMode.FLOOR);
                if (down.compareTo(halfUp) != 0) {
                    actualHalfUpDifferences++;
                }
                if (down.compareTo(floor) != 0) {
                    actualFloorDifferences++;
                }
                if (halfUpChangesAtTargetScale(row.getValue(), rate)) {
                    expectedHalfUpDifferences++;
                }
                if (floorChangesAtTargetScale(row.getValue(), rate)) {
                    expectedFloorDifferences++;
                }
            }

            assertEquals(expectedHalfUpDifferences, actualHalfUpDifferences,
                    "HALF_UP differences equal the exact half-divisor remainder count");
            assertEquals(expectedFloorDifferences, actualFloorDifferences,
                    "FLOOR differences equal the negative non-integral remainder count");
            assertTrue(actualHalfUpDifferences > 0,
                    "the chained fixture balances expose the HALF_UP divergence");
            assertEquals(0L, actualFloorDifferences,
                    "the fixture's negative category rows carry the zero rate");
        }

        @Test
        void everyNegativeChainedBalanceUsesTheZeroDefaultRate() {
            Map<RateCode, BigDecimal> rates = defaultRates();
            Map<CategoryKey, BigDecimal> balances = categoryBalancesAfterPosting();

            for (Map.Entry<CategoryKey, BigDecimal> row : balances.entrySet()) {
                if (row.getValue().signum() < 0) {
                    BigDecimal rate = rates.get(
                            new RateCode(row.getKey().typeCode(), row.getKey().categoryCode()));
                    assertEquals(ZERO_RATE, rate,
                            "negative fixture rows use DEFAULT 03/0001 at 0.00");
                }
            }
        }
    }

    @Nested
    @DisplayName("Picture-field narrowing")
    class PictureNarrowing {

        @Test
        void lowOrderAndHighOrderDigitsAreBothDropped() {
            BigDecimal stored = CobolDecimal.truncateToPictureField(PRECISION_BOUNDARY_VALUE,
                    PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);

            assertEquals(PRECISION_BOUNDARY_STORED, stored,
                    "S9(09)V99 keeps the low nine integer digits and two fractional digits");
        }

        @Test
        void theSameNarrowingShapeAppliesToMonthlyAndTotalInterest() {
            BigDecimal monthly = CobolDecimal.truncateToPictureField(PRECISION_BOUNDARY_VALUE,
                    PicClause.WS_MONTHLY_INT_PRECISION, PicClause.WS_MONTHLY_INT_SCALE);
            BigDecimal total = CobolDecimal.truncateToPictureField(PRECISION_BOUNDARY_VALUE,
                    PicClause.WS_TOTAL_INT_PRECISION, PicClause.WS_TOTAL_INT_SCALE);

            assertEquals(PRECISION_BOUNDARY_STORED, monthly,
                    "WS-MONTHLY-INT at CBACT04C L168 uses the narrowed shape");
            assertEquals(PRECISION_BOUNDARY_STORED, total,
                    "WS-TOTAL-INT at CBACT04C L169 uses the narrowed shape");
        }
    }

    private static BigDecimal monthlyInterest(BigDecimal balance, BigDecimal rate) {
        return CobolDecimal.multiplyThenDivide(balance, rate, CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE);
    }

    private static BigDecimal roundedMonthlyInterest(BigDecimal balance, BigDecimal rate,
            RoundingMode mode) {
        return balance.multiply(rate).divide(CobolDecimal.INTEREST_DIVISOR,
                PicClause.WS_MONTHLY_INT_SCALE, mode);
    }

    private static boolean halfUpChangesAtTargetScale(BigDecimal balance, BigDecimal rate) {
        BigDecimal scaledProduct =
                balance.multiply(rate).movePointRight(PicClause.WS_MONTHLY_INT_SCALE);
        BigDecimal remainder = scaledProduct.remainder(CobolDecimal.INTEREST_DIVISOR).abs();
        return remainder.multiply(BigDecimal.valueOf(2L))
                .compareTo(CobolDecimal.INTEREST_DIVISOR) >= 0;
    }

    private static boolean floorChangesAtTargetScale(BigDecimal balance, BigDecimal rate) {
        BigDecimal scaledProduct =
                balance.multiply(rate).movePointRight(PicClause.WS_MONTHLY_INT_SCALE);
        BigDecimal remainder = scaledProduct.remainder(CobolDecimal.INTEREST_DIVISOR);
        return scaledProduct.signum() < 0 && remainder.signum() != 0;
    }

    private static Map<CategoryKey, BigDecimal> categoryBalancesAfterPosting() {
        Map<String, MutableAccount> accounts = mutableAccounts();
        Map<String, CardCrossReferenceRecord> xrefs =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
        Map<CategoryKey, BigDecimal> balances = new LinkedHashMap<>();
        for (TransactionCategoryBalanceRecord row
                : CardDemoFixtureLoader.loadTransactionCategoryBalances()) {
            balances.put(new CategoryKey(row.accountId(), row.typeCode(), row.categoryCode()),
                    row.balance());
        }

        for (DailyTransactionRecord transaction : CardDemoFixtureLoader.loadDailyTransactions()) {
            CardCrossReferenceRecord xref = xrefs.get(transaction.cardNumber());
            MutableAccount account = xref == null ? null : accounts.get(xref.accountId());
            if (account == null || account.declines(transaction)) {
                continue;
            }
            CategoryKey key =
                    new CategoryKey(xref.accountId(), transaction.typeCode(),
                            transaction.categoryCode());
            balances.put(key, CobolDecimal.add(
                    balances.getOrDefault(key, ZERO_RATE), transaction.amount(),
                    PicClause.TRAN_CAT_BAL_SCALE));
            account.apply(transaction.amount());
        }
        return Map.copyOf(balances);
    }

    private static Map<String, MutableAccount> mutableAccounts() {
        Map<String, MutableAccount> accounts = new LinkedHashMap<>();
        for (AccountRecord account : CardDemoFixtureLoader.loadAccounts()) {
            accounts.put(account.accountId(), new MutableAccount(account.creditLimit(),
                    account.expirationDate(), account.currentCycleCredit(),
                    account.currentCycleDebit()));
        }
        return accounts;
    }

    private static Map<RateCode, BigDecimal> defaultRates() {
        Map<RateCode, BigDecimal> rates = new LinkedHashMap<>();
        for (DisclosureGroupRecord row : CardDemoFixtureLoader.loadDisclosureGroups()) {
            if (row.accountGroupId().equals(DEFAULT_GROUP)) {
                rates.put(new RateCode(row.transactionTypeCode(),
                        row.transactionCategoryCode()), row.interestRate());
            }
        }
        return Map.copyOf(rates);
    }

    private static void assertNumericEquals(BigDecimal expected, BigDecimal actual,
            String message) {
        assertEquals(0, actual.compareTo(expected), message);
    }

    private record CategoryKey(String accountId, String typeCode, String categoryCode) {
    }

    private record RateCode(String typeCode, String categoryCode) {
    }

    private static final class MutableAccount {
        private final BigDecimal creditLimit;
        private final String expirationDate;
        private BigDecimal cycleCredit;
        private BigDecimal cycleDebit;

        private MutableAccount(BigDecimal creditLimit, String expirationDate,
                BigDecimal cycleCredit, BigDecimal cycleDebit) {
            this.creditLimit = creditLimit;
            this.expirationDate = expirationDate;
            this.cycleCredit = cycleCredit;
            this.cycleDebit = cycleDebit;
        }

        private boolean declines(DailyTransactionRecord transaction) {
            BigDecimal difference = CobolDecimal.subtract(cycleCredit, cycleDebit,
                    PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal computed = CobolDecimal.add(difference, transaction.amount(),
                    PicClause.WS_TEMP_BAL_SCALE);
            BigDecimal working = CobolDecimal.truncateToPictureField(computed,
                    PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);
            boolean overLimit = creditLimit.compareTo(working) < 0;
            boolean expired = expirationDate.compareTo(
                    CopybookRecordParser.timestampDatePart(transaction.originTimestamp())) < 0;
            return overLimit || expired;
        }

        private void apply(BigDecimal amount) {
            if (amount.signum() >= 0) {
                cycleCredit = CobolDecimal.add(cycleCredit, amount,
                        PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
            } else {
                cycleDebit = CobolDecimal.add(cycleDebit, amount,
                        PicClause.ACCT_CURR_CYC_DEBIT_SCALE);
            }
        }
    }
}