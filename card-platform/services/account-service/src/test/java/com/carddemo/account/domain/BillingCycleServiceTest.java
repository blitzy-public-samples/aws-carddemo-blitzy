package com.carddemo.account.domain;

import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardCrossReferenceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct tests of {@link BillingCycleService#closeBillingCycle}.
 *
 * <p>The method reproduces two statements, {@code app/cbl/CBACT04C.cbl:L353-L354}, and nothing else. It
 * is in this platform because the credit-limit rule at {@code app/cbl/CBTRN02C.cbl:L403-L413} authorizes
 * against cycle credit minus cycle debit, and those two statements are the only code in the whole source
 * repository that returns either accumulator to zero. They live inside the interest program, which the
 * plan keeps as a batch process.
 *
 * <p>What these tests assert is that both accumulators reach zero at the scale the column holds, that
 * the seven other fields are left exactly as found, and that one event carries the result to the
 * authorization service. The last of those is what makes the endpoint useful: zeroing the columns
 * without publishing would leave the copy the decline rules read at its old values.
 *
 * <p>No application context, no database and no broker takes part.
 */
@DisplayName("closing one billing cycle")
class BillingCycleServiceTest {

    /** Row one of {@code app/data/ASCII/acctdata.txt}, its account identifier. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Zero at the two fractional digits {@code PIC S9(10)V99} holds. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private AccountRepository accounts;
    private OutboxWriter outbox;
    private BillingCycleService service;

    /** Builds the service over a stubbed store before each test. */
    @BeforeEach
    void buildService() {
        accounts = mock(AccountRepository.class);
        outbox = mock(OutboxWriter.class);
        when(accounts.save(any(AccountEntity.class))).thenAnswer(call -> call.getArgument(0));

        service = new BillingCycleService(accounts, outbox, immediateTransactions(), accountMeters());
    }

    /** What a close does to the row. */
    @Nested
    @DisplayName("the two statements it reproduces")
    class TheTwoStatements {

        /** Asserts both accumulators reach zero. */
        @Test
        void bothAccumulatorsReachZero() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account()));

            Optional<AccountEntity> closed = service.closeBillingCycle(ACCOUNT_ID);

            assertTrue(closed.isPresent(), "a stored row is closed and returned");
            assertEquals(0, closed.get().getCurrentCycleCredit().compareTo(BigDecimal.ZERO),
                    "app/cbl/CBACT04C.cbl:L353 zeroes the credit accumulator");
            assertEquals(0, closed.get().getCurrentCycleDebit().compareTo(BigDecimal.ZERO),
                    "app/cbl/CBACT04C.cbl:L354 zeroes the debit accumulator");
        }

        /**
         * Asserts both accumulators carry the scale the column holds.
         *
         * <p>{@code NUMERIC(12,2)} holds two fractional digits. Zero at scale 0 and zero at scale 2 are
         * equal in value and different on the wire and in the column, and the column is what the
         * authorization service later reads.
         */
        @Test
        void bothAccumulatorsCarryTwoFractionalDigits() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account()));

            AccountEntity closed = service.closeBillingCycle(ACCOUNT_ID).orElseThrow();

            assertEquals(ZERO, closed.getCurrentCycleCredit(),
                    "zero at the scale PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L12 holds");
            assertEquals(ZERO, closed.getCurrentCycleDebit(),
                    "zero at the scale PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L13 holds");
        }

        /**
         * Asserts the seven other fields are left exactly as found.
         *
         * <p>This is the whole difference between reproducing two statements and migrating the interest
         * program. A close that also touched the balance, either credit limit or the disclosure group
         * would be computing something, and computing interest is out of scope.
         */
        @Test
        void everyOtherFieldIsLeftAsFound() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account()));

            AccountEntity closed = service.closeBillingCycle(ACCOUNT_ID).orElseThrow();

            assertEquals(ACCOUNT_ID, closed.getAccountId(), "the identifier stands");
            assertEquals("Y", closed.getActiveStatus(), "the status stands");
            assertEquals(new BigDecimal("1940.00"), closed.getCurrentBalance(),
                    "the balance stands, because no interest was computed");
            assertEquals(new BigDecimal("20200.00"), closed.getCreditLimit(),
                    "the credit limit stands");
            assertEquals(new BigDecimal("10200.00"), closed.getCashCreditLimit(),
                    "the cash credit limit stands");
            assertEquals("2014-11-20", closed.getOpenDate(), "the open date stands");
            assertEquals("2025-05-20", closed.getExpirationDate(), "the expiry stands");
            assertEquals("2025-05-20", closed.getReissueDate(), "the reissue date stands");
            assertEquals("Premium001", closed.getGroupId(),
                    "the disclosure group stands, and it was not even read");
        }

        /**
         * Asserts the row is read under a write lock rather than plainly.
         *
         * <p>Two callers closing one cycle at once would otherwise both read the accumulators, both
         * write zero and both publish, and the second publication would carry the same values as the
         * first. The lock makes the second wait, and the second event then records a close of an
         * already-closed cycle rather than a phantom one.
         */
        @Test
        void theRowIsReadUnderAWriteLock() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account()));

            service.closeBillingCycle(ACCOUNT_ID);

            verify(accounts).findForUpdateByAccountId(ACCOUNT_ID);
            verify(accounts, never()).findByAccountId(any());
        }

        /** Asserts the row is written once. */
        @Test
        void theRowIsWrittenOnce() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account()));

            service.closeBillingCycle(ACCOUNT_ID);

            verify(accounts, times(1)).save(any(AccountEntity.class));
        }
    }

    /** The event a close produces. */
    @Nested
    @DisplayName("the one event it produces")
    class TheOneEvent {

        /**
         * Asserts one event carries the close and the five values the authorization service reads.
         *
         * <p>Zeroing the two columns and publishing nothing would leave that service's copy at its old
         * values, so the accumulators would look full to every decline rule while the columns held zero.
         * That failure is silent, which is what makes this the most important assertion in the class.
         */
        @Test
        void oneEventCarriesTheCloseAndItsFiveValues() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account()));

            service.closeBillingCycle(ACCOUNT_ID);

            ArgumentCaptor<AccountStateChanged> event =
                    ArgumentCaptor.forClass(AccountStateChanged.class);
            verify(outbox, times(1)).write(event.capture());

            AccountStateChanged written = event.getValue();
            assertEquals(ACCOUNT_ID, written.accountId(), "the account whose cycle closed");
            assertEquals(AccountStateChanged.ChangeKind.BILLING_CYCLE_CLOSED, written.changeKind(),
                    "a cycle close rather than an update, so a consumer can tell them apart");
            assertEquals(new BigDecimal("20200.00"), written.creditLimit(),
                    "the credit limit as it stands, unchanged by the close");
            assertEquals(ZERO, written.currentCycleCredit(), "the accumulator after the close");
            assertEquals(ZERO, written.currentCycleDebit(), "and the other one");
            assertEquals("2025-05-20", written.expirationDate(),
                    "the expiry the expiry rule compares as text");
        }

        /**
         * Asserts the event carries the values after the close and not before it.
         *
         * <p>An event built from the values read rather than the values written would tell the
         * authorization service the accumulators were still full, and the columns and the copy would
         * then disagree with nothing reporting it.
         */
        @Test
        void theEventCarriesTheValuesAfterTheCloseAndNotBefore() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account()));

            service.closeBillingCycle(ACCOUNT_ID);

            ArgumentCaptor<AccountStateChanged> event =
                    ArgumentCaptor.forClass(AccountStateChanged.class);
            verify(outbox).write(event.capture());
            assertEquals(0, event.getValue().currentCycleCredit().compareTo(BigDecimal.ZERO),
                    "the published credit accumulator is the one the column now holds");
            assertNotSame(new BigDecimal("500.00"), event.getValue().currentCycleCredit(),
                    "and not the one it held before the close");
        }
    }

    /** What a close does when there is nothing to close. */
    @Nested
    @DisplayName("an account this service does not hold")
    class AnAccountNotHeld {

        /** Asserts an unknown account answers empty and writes nothing. */
        @Test
        void anUnknownAccountAnswersEmptyAndWritesNothing() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

            Optional<AccountEntity> closed = service.closeBillingCycle(ACCOUNT_ID);

            assertTrue(closed.isEmpty(), "there is nothing to close");
            verify(accounts, never()).save(any());
            verify(outbox, never()).write(any());
        }

        /**
         * Asserts an unknown account is an answer rather than an exception.
         *
         * <p>A keyed read that missed is an outcome the source has too: it moves a text onto the screen
         * and carries on. Raising here would turn a caller's typing mistake into a 500.
         */
        @Test
        void anUnknownAccountRaisesNothing() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertTrue(service.closeBillingCycle(ACCOUNT_ID).isEmpty(),
                    "the empty result is the answer, and no exception travels");
        }
    }

    /** What the method and the constructor refuse. */
    @Nested
    @DisplayName("arguments they refuse")
    class RefusedArguments {

        /** Asserts an absent identifier is refused rather than read as a wildcard. */
        @Test
        void anAbsentIdentifierIsRefused() {
            assertThrows(NullPointerException.class, () -> service.closeBillingCycle(null),
                    "there is no account to close");
        }

        /** Asserts every constructor argument is required. */
        @Test
        void everyCollaboratorIsRequired() {
            TransactionTemplate transactions = immediateTransactions();
            ObservabilityConfig.AccountMeters meters = accountMeters();

            assertThrows(NullPointerException.class,
                    () -> new BillingCycleService(null, outbox, transactions, meters),
                    "the account store is required");
            assertThrows(NullPointerException.class,
                    () -> new BillingCycleService(accounts, null, transactions, meters),
                    "the outbox writer is required, because the close has to be published");
            assertThrows(NullPointerException.class,
                    () -> new BillingCycleService(accounts, outbox, null, meters),
                    "the transaction boundary is required");
            assertThrows(NullPointerException.class,
                    () -> new BillingCycleService(accounts, outbox, transactions, null),
                    "the meter holder is required");
        }
    }

    /**
     * Builds one account carrying both accumulators above zero.
     *
     * <p>The values come from row one of {@code app/data/ASCII/acctdata.txt}. Each call returns a fresh
     * instance, so a test that closes one leaves the others alone.
     *
     * @return the account
     */
    private static AccountEntity account() {
        AccountEntity account = new AccountEntity();
        account.setAccountId(ACCOUNT_ID);
        account.setActiveStatus("Y");
        account.setCurrentBalance(new BigDecimal("1940.00"));
        account.setCreditLimit(new BigDecimal("20200.00"));
        account.setCashCreditLimit(new BigDecimal("10200.00"));
        account.setOpenDate("2014-11-20");
        account.setExpirationDate("2025-05-20");
        account.setReissueDate("2025-05-20");
        account.setCurrentCycleCredit(new BigDecimal("500.00"));
        account.setCurrentCycleDebit(new BigDecimal("250.00"));
        account.setAddressZip("27604     ");
        account.setGroupId("Premium001");
        return account;
    }

    /** Runs a transaction callback directly, so no transaction manager takes part. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {

            private static final long serialVersionUID = 1L;

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    /** The shared meter holder this service records through. */
    private static ObservabilityConfig.AccountMeters accountMeters() {
        return new ObservabilityConfig().accountMeters(new SimpleMeterRegistry());
    }

}
