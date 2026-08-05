package com.carddemo.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.repository.Lock;

/**
 * Arithmetic and branch tests for {@link AccountBalanceUpdater}.
 *
 * <p>The subject is paragraph {@code 2800-UPDATE-ACCOUNT-REC} at
 * {@code app/cbl/CBTRN02C.cbl:L545-L560}. Line {@code :L547} adds the posted amount to
 * {@code ACCT-CURR-BAL}, declared {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}. Line
 * {@code :L548} routes the same amount by sign to one of two accumulators:
 * {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13} through {@code :L549}, or
 * {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14} through {@code :L551}. Line
 * {@code :L554} issues the {@code REWRITE} that saves the row.
 *
 * <p>The amount arrives as {@code DALYTRAN-AMT}, declared {@code PIC S9(09)V99} at
 * {@code app/cpy/CVTRA06Y.cpy:L10}. The online bill payment path at
 * {@code app/cbl/COBIL00C.cbl:L234} subtracts from the balance alone and moves neither accumulator.
 *
 * <p>These tests stub the one collaborator and start no application context. Values come from
 * {@code app/data/ASCII/acctdata.txt} and {@code app/data/ASCII/dailytran.txt}, plus constructed
 * boundaries those fixtures do not reach.
 */
final class AccountBalanceUpdaterTest {

    /**
     * Account of row 7 of {@code app/data/ASCII/acctdata.txt}, eleven digits with leading zeros
     * held. Record 1 of the daily feed reaches it through row 21 of
     * {@code app/data/ASCII/cardxref.txt}.
     */
    private static final String ACCOUNT_ID = "00000000007";

    /** Balance that fixture row carries, decoded from its zoned-decimal bytes. */
    private static final BigDecimal STORED_BALANCE = new BigDecimal("193.00");

    /** Value both accumulators of that fixture row carry. */
    private static final BigDecimal ZERO_ACCUMULATOR = new BigDecimal("0.00");

    /** Amount on record 1 of {@code app/data/ASCII/dailytran.txt}, sign overpunched on byte 11. */
    private static final BigDecimal POSTED_AMOUNT = new BigDecimal("504.77");

    /** Sum of {@link #STORED_BALANCE} and {@link #POSTED_AMOUNT}. */
    private static final BigDecimal POSTED_BALANCE = new BigDecimal("697.77");

    /** A refund. 50 of the 300 records of the daily feed carry a negative amount. */
    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("-125.00");

    /** Digits each monetary field keeps after the point, from {@code V99} of {@code S9(10)V99}. */
    private static final int MONEY_SCALE = 2;

    /** The one collaborator, stubbed per test. */
    private AccountBalanceProjectionRepository accountBalances;

    /** The subject under test. */
    private AccountBalanceUpdater updater;

    /** Builds the subject over a stubbed repository ahead of each test. */
    @BeforeEach
    void buildUpdater() {
        accountBalances = mock(AccountBalanceProjectionRepository.class);
        updater = new AccountBalanceUpdater(accountBalances);
    }

    @Test
    @DisplayName("the posting read takes a pessimistic write lock before replacing the balance")
    void thePostingReadTakesAPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = AccountBalanceProjectionRepository.class
                .getMethod("findForUpdateById", String.class)
                .getAnnotation(Lock.class);

        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    /** Stubs the repository to hold one row for {@link #ACCOUNT_ID}, each amount at scale two. */
    private void storeRow(BigDecimal balance, BigDecimal cycleCredit, BigDecimal cycleDebit) {
        when(accountBalances.findForUpdateById(ACCOUNT_ID)).thenReturn(Optional.of(
                new AccountBalanceProjectionEntity(ACCOUNT_ID, balance, cycleCredit, cycleDebit)));
    }

    /** Captures the row the subject saved, failing when the call count differs from one. */
    private AccountBalanceProjectionEntity savedRow() {
        ArgumentCaptor<AccountBalanceProjectionEntity> saved =
                ArgumentCaptor.forClass(AccountBalanceProjectionEntity.class);
        verify(accountBalances, times(1)).save(saved.capture());
        return saved.getValue();
    }

    @Nested
    @DisplayName("The balance add at app/cbl/CBTRN02C.cbl:L547")
    class BalanceAdd {

        /** Line {@code :L547} adds the amount, and the row saved at {@code :L554} carries it. */
        @Test
        @DisplayName("returns the stored balance plus the posted amount and saves it")
        void returnsTheStoredBalancePlusThePostedAmount() {
            storeRow(STORED_BALANCE, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            BigDecimal posted = updater.updateBalances(ACCOUNT_ID, POSTED_AMOUNT);

            AccountBalanceProjectionEntity saved = savedRow();
            assertEquals(POSTED_BALANCE, posted);
            assertEquals(MONEY_SCALE, posted.scale());
            assertEquals(posted, saved.getCurrentBalance());
            assertEquals(MONEY_SCALE, saved.getCurrentBalance().scale());
            assertEquals(ACCOUNT_ID, saved.getAccountId());
        }
    }

    @Nested
    @DisplayName("The sign fork at app/cbl/CBTRN02C.cbl:L548-L552")
    class SignFork {

        /**
         * Line {@code :L548} tests {@code IF DALYTRAN-AMT >= 0}, so zero takes the credit branch at
         * {@code :L549} and a negative amount takes the debit branch at {@code :L551}. The rows
         * either side of zero step by one cent, pinning the fork at zero, and one accumulator moves
         * per call while the other keeps its stored value.
         *
         * @param amount              the posted amount
         * @param expectedCycleCredit accumulator at {@code app/cpy/CVACT01Y.cpy:L13} afterwards
         * @param expectedCycleDebit  accumulator at {@code app/cpy/CVACT01Y.cpy:L14} afterwards
         */
        @ParameterizedTest
        @DisplayName("routes the amount to one accumulator and holds the other")
        @CsvSource({
            "504.77,504.77,0.00",
            "0.01,0.01,0.00",
            "0.00,0.00,0.00",
            "-0.01,0.00,-0.01",
            "-125.00,0.00,-125.00"
        })
        void routesTheAmountToOneAccumulator(String amount, String expectedCycleCredit,
                String expectedCycleDebit) {
            storeRow(STORED_BALANCE, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            updater.updateBalances(ACCOUNT_ID, new BigDecimal(amount));

            AccountBalanceProjectionEntity saved = savedRow();
            assertEquals(new BigDecimal(expectedCycleCredit), saved.getCycleCredit());
            assertEquals(new BigDecimal(expectedCycleDebit), saved.getCycleDebit());
        }

        /**
         * An amount of exactly zero satisfies {@code IF DALYTRAN-AMT >= 0} at {@code :L548} and
         * takes the credit branch at {@code :L549}. Adding zero holds the balance and both
         * accumulators at their stored values, and the row is still saved.
         */
        @Test
        @DisplayName("accepts an amount of exactly zero and still saves the row")
        void acceptsAnAmountOfExactlyZero() {
            storeRow(STORED_BALANCE, new BigDecimal("40.00"), new BigDecimal("-15.00"));

            BigDecimal posted = updater.updateBalances(ACCOUNT_ID, ZERO_ACCUMULATOR);

            AccountBalanceProjectionEntity saved = savedRow();
            assertEquals(STORED_BALANCE, posted);
            assertEquals(STORED_BALANCE, saved.getCurrentBalance());
            assertEquals(new BigDecimal("40.00"), saved.getCycleCredit());
            assertEquals(new BigDecimal("-15.00"), saved.getCycleDebit());
        }
    }

    @Nested
    @DisplayName("Toward-zero arithmetic at app/cbl/CBTRN02C.cbl:L547-L551")
    class TowardZeroArithmetic {

        /**
         * The {@code ROUNDED} phrase appears nowhere among the 28 programs in {@code app/cbl/}. The
         * balance store at {@code :L547} and the credit accumulator store at {@code :L549}
         * therefore drop the digits past the second toward zero. Half-up rounding yields a
         * different balance.
         */
        @Test
        @DisplayName("drops the third decimal digit of a positive sum toward zero")
        void dropsTheThirdDecimalDigitOfAPositiveSum() {
            storeRow(new BigDecimal("100.00"), ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            BigDecimal posted = updater.updateBalances(ACCOUNT_ID, new BigDecimal("0.555"));

            AccountBalanceProjectionEntity saved = savedRow();
            assertEquals(new BigDecimal("100.55"), posted);
            assertEquals(MONEY_SCALE, posted.scale());
            assertEquals(new BigDecimal("0.55"), saved.getCycleCredit());
            assertEquals(ZERO_ACCUMULATOR, saved.getCycleDebit());
            BigDecimal halfUp = new BigDecimal("100.555").setScale(MONEY_SCALE,
                    RoundingMode.HALF_UP);
            assertNotEquals(halfUp, posted);
        }

        /**
         * A sum below zero separates {@link RoundingMode#DOWN} from {@link RoundingMode#FLOOR},
         * which agrees with it above zero. The debit store at {@code :L551} drops the same digits,
         * and a negative amount reaches both stores with its sign held.
         */
        @Test
        @DisplayName("drops the third decimal digit of a negative sum toward zero")
        void dropsTheThirdDecimalDigitOfANegativeSum() {
            storeRow(ZERO_ACCUMULATOR, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            BigDecimal posted = updater.updateBalances(ACCOUNT_ID, new BigDecimal("-0.555"));

            AccountBalanceProjectionEntity saved = savedRow();
            assertEquals(new BigDecimal("-0.55"), posted);
            assertEquals(new BigDecimal("-0.55"), saved.getCycleDebit());
            assertEquals(ZERO_ACCUMULATOR, saved.getCycleCredit());
            BigDecimal floored = new BigDecimal("-0.555").setScale(MONEY_SCALE, RoundingMode.FLOOR);
            BigDecimal halfUp = new BigDecimal("-0.555").setScale(MONEY_SCALE,
                    RoundingMode.HALF_UP);
            assertNotEquals(floored, posted);
            assertNotEquals(halfUp, posted);
        }
    }

    @Nested
    @DisplayName("The absent row at app/cbl/CBTRN02C.cbl:L554-L559")
    class AbsentRow {

        /**
         * The {@code INVALID KEY} branch at {@code :L555-L558} reports
         * {@code 'ACCOUNT RECORD NOT FOUND'} and creates no record. The subject throws and saves
         * nothing, and its message withholds the account.
         */
        @Test
        @DisplayName("throws and saves nothing when no row carries the account")
        void throwsAndSavesNothingWhenNoRowCarriesTheAccount() {
            when(accountBalances.findForUpdateById(ACCOUNT_ID)).thenReturn(Optional.empty());

            AccountBalanceUpdater.AccountBalanceRowMissingException thrown = assertThrows(
                    AccountBalanceUpdater.AccountBalanceRowMissingException.class,
                    () -> updater.updateBalances(ACCOUNT_ID, POSTED_AMOUNT));

            assertFalse(thrown.getMessage().contains(ACCOUNT_ID),
                    "an exception message reaches a log and a dead-letter record, so it names no "
                            + "account");
            verify(accountBalances, never()).save(any(AccountBalanceProjectionEntity.class));
        }

        /**
         * The projection row carries no status column, so row presence is the one guard the subject
         * applies. No program reads {@code ACCT-ACTIVE-STATUS} at {@code app/cpy/CVACT01Y.cpy:L6}
         * before posting, and {@code app/jcl/POSTTRAN.jcl} allocates no card file.
         */
        @Test
        @DisplayName("posts against a row in arrears, applying no status test")
        void postsAgainstARowInArrears() {
            storeRow(new BigDecimal("-4000.00"), new BigDecimal("120.00"),
                    new BigDecimal("-75.00"));

            BigDecimal posted = updater.updateBalances(ACCOUNT_ID, POSTED_AMOUNT);

            AccountBalanceProjectionEntity saved = savedRow();
            assertEquals(new BigDecimal("-3495.23"), posted);
            assertEquals(new BigDecimal("624.77"), saved.getCycleCredit());
            assertEquals(new BigDecimal("-75.00"), saved.getCycleDebit());
        }
    }

    @Nested
    @DisplayName("The save at app/cbl/CBTRN02C.cbl:L554")
    class Save {

        /**
         * The {@code REWRITE} at {@code :L554} runs once per call, and the saved row carries the
         * identifier, the balance from {@code :L547} and both accumulators.
         */
        @Test
        @DisplayName("saves one row carrying the identifier, the balance and both accumulators")
        void savesOneRowCarryingEachColumn() {
            storeRow(STORED_BALANCE, new BigDecimal("40.00"), new BigDecimal("-15.00"));

            updater.updateBalances(ACCOUNT_ID, POSTED_AMOUNT);

            AccountBalanceProjectionEntity saved = savedRow();
            assertEquals(ACCOUNT_ID, saved.getAccountId());
            assertEquals(POSTED_BALANCE, saved.getCurrentBalance());
            assertEquals(new BigDecimal("544.77"), saved.getCycleCredit());
            assertEquals(new BigDecimal("-15.00"), saved.getCycleDebit());
        }

        /**
         * {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7} holds ten digits
         * ahead of the point and two after it. A sum at that full width keeps its high-order digit.
         */
        @Test
        @DisplayName("keeps the high-order digit of a twelve-digit balance")
        void keepsTheHighOrderDigitOfATwelveDigitBalance() {
            storeRow(new BigDecimal("9999999998.99"), ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            BigDecimal posted = updater.updateBalances(ACCOUNT_ID, new BigDecimal("1.00"));

            assertEquals(new BigDecimal("9999999999.99"), posted);
            assertEquals(new BigDecimal("9999999999.99"), savedRow().getCurrentBalance());
        }
    }

    @Nested
    @DisplayName("The refund direction at app/cbl/CBTRN02C.cbl:L551")
    class RefundDirection {

        /**
         * Line {@code :L551} adds a negative amount to {@code ACCT-CURR-CYC-DEBIT} at {@code
         * app/cpy/CVACT01Y.cpy:L14}, carrying the accumulator further below zero. The overlimit
         * computation at {@code app/cbl/CBTRN02C.cbl:L403-L405} subtracts that accumulator, so a
         * refund lifts the balance the credit-limit test reads and tightens the next authorization.
         */
        @Test
        @DisplayName("carries the cycle debit accumulator further below zero")
        void carriesTheCycleDebitAccumulatorFurtherBelowZero() {
            storeRow(STORED_BALANCE, ZERO_ACCUMULATOR, ZERO_ACCUMULATOR);

            updater.updateBalances(ACCOUNT_ID, REFUND_AMOUNT);

            AccountBalanceProjectionEntity saved = savedRow();
            assertEquals(new BigDecimal("-125.00"), saved.getCycleDebit());
            assertTrue(saved.getCycleDebit().compareTo(ZERO_ACCUMULATOR) < 0);
            assertEquals(ZERO_ACCUMULATOR, saved.getCycleCredit());
            assertEquals(new BigDecimal("68.00"), saved.getCurrentBalance());
        }

        /** A second refund carries a stored negative accumulator further below zero. */
        @Test
        @DisplayName("carries a stored negative accumulator further below zero")
        void carriesAStoredNegativeAccumulatorFurtherBelowZero() {
            storeRow(STORED_BALANCE, ZERO_ACCUMULATOR, new BigDecimal("-125.00"));

            updater.updateBalances(ACCOUNT_ID, REFUND_AMOUNT);

            assertEquals(new BigDecimal("-250.00"), savedRow().getCycleDebit());
        }
    }
}
