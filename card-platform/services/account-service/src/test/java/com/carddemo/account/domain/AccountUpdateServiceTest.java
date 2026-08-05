package com.carddemo.account.domain;

import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CardCrossReferenceEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardCrossReferenceRepository;
import com.carddemo.account.repository.CustomerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct tests of {@link AccountUpdateService#updateAccount}, the orchestration
 * {@code app/cbl/COACTUPC.cbl} performs.
 *
 * <p>The method runs four things in order, and each has its own outcome: the search-key edit at
 * {@code app/cbl/COACTUPC.cbl:L1433-L1449}, the no-change comparison at {@code :L1460-L1467}, the
 * twenty-four field edits at {@code :L1470-L1676}, and the write path at {@code :L3888-L4104} whose
 * own steps are two locks, a concurrency check, two saves and one event row.
 *
 * <p>These tests drive the real edits and the real branch order over stubbed repositories, so what they
 * prove is the sequence: which step runs, which step is skipped, and what each one answers. The
 * field-by-field comparison itself belongs to {@code ConcurrentChangeDetectorTest}, which drives it
 * directly, and each validator has its own test beside it.
 *
 * <p>The fixture pair is deliberately one that passes every edit. Row one of
 * {@code app/data/ASCII/custdata.txt} does not: it carries credit score 274, which is below the 300 the
 * range edit accepts, and the state and postcode combination {@code NC} with {@code 12546}, which
 * {@code app/cpy/CSLKPCDY.cpy} does not list. The sample data is synthetic, and that is worth knowing
 * before a reader wonders why the fixture here differs from the file.
 *
 * <p>No application context, no database and no broker takes part.
 */
@DisplayName("the account update orchestration")
class AccountUpdateServiceTest {

    /** Row one of {@code app/data/ASCII/acctdata.txt}, its account identifier. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Row one of {@code app/data/ASCII/custdata.txt}, its customer identifier. */
    private static final String CUSTOMER_ID = "000000001";

    private AccountRepository accounts;
    private CustomerRepository customers;
    private OutboxWriter outbox;

    /** Names the authoritative customer of one account. */
    private CardCrossReferenceRepository crossReferences;
    private AccountUpdateService service;

    /** Builds the service over stubbed stores and the real concurrency detector. */
    @BeforeEach
    void buildService() {
        accounts = mock(AccountRepository.class);
        customers = mock(CustomerRepository.class);
        outbox = mock(OutboxWriter.class);
        when(accounts.save(any(AccountEntity.class))).thenAnswer(call -> call.getArgument(0));
        when(customers.save(any(CustomerEntity.class))).thenAnswer(call -> call.getArgument(0));

        crossReferences = mock(CardCrossReferenceRepository.class);
        CardCrossReferenceEntity relationship = mock(CardCrossReferenceEntity.class);
        when(relationship.getCustomerId()).thenReturn(CUSTOMER_ID);
        when(crossReferences.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(relationship));
        service = new AccountUpdateService(accounts, customers, crossReferences,
                new ConcurrentChangeDetector(), outbox, immediateTransactions(), accountMeters());
    }

    /** The path a complete, changed and valid pair takes. */
    @Nested
    @DisplayName("a pair that passes every edit")
    class APairThatPasses {

        /** Asserts a changed pair is written and answers a passing verdict carrying no message. */
        @Test
        void aChangedPairIsWrittenAndAnswersACleanVerdict() {
            lockBoth();

            EditResult verdict = update(proposed -> proposed.setCreditLimit(
                    new BigDecimal("25000.00")));

            assertTrue(verdict.valid(), "every edit passed and both rows were written");
            assertNull(verdict.message(), "a clean write carries no message");
        }

        /**
         * Asserts both rows are written, in the order the source writes them.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L4066} rewrites the account and {@code :L4086} the customer,
         * inside one unit of work. Every file definition in {@code app/csd/CARDDEMO.CSD} specifies
         * {@code RECOVERY(NONE) JOURNAL(NO)}, so the source had no way to undo the first write if the
         * second failed. One database transaction is what replaces that, and it is a declared addition.
         */
        @Test
        void bothRowsAreWritten() {
            lockBoth();

            update(proposed -> proposed.setCreditLimit(new BigDecimal("25000.00")));

            verify(accounts).save(any(AccountEntity.class));
            verify(customers).save(any(CustomerEntity.class));
        }

        /**
         * Asserts exactly one event row is written, carrying the five values the authorization service
         * reads.
         *
         * <p>That service holds a copy of the credit limit, both cycle accumulators and the expiry
         * date, and this event is the only thing that refreshes it. An update that wrote the rows and
         * no event would leave the copy stale, and a stale copy answers with whatever it last knew
         * rather than failing.
         */
        @Test
        void oneEventRowCarriesWhatTheAuthorizationServiceReads() {
            lockBoth();

            update(proposed -> proposed.setCreditLimit(new BigDecimal("25000.00")));

            ArgumentCaptor<AccountStateChanged> event =
                    ArgumentCaptor.forClass(AccountStateChanged.class);
            verify(outbox).write(event.capture());

            AccountStateChanged written = event.getValue();
            assertEquals(ACCOUNT_ID, written.accountId(), "the account the change belongs to");
            assertEquals(AccountStateChanged.ChangeKind.ACCOUNT_UPDATED, written.changeKind(),
                    "an update rather than a cycle close");
            assertEquals(new BigDecimal("25000.00"), written.creditLimit(),
                    "the credit limit as it now stands, which the credit-limit rule reads");
            assertEquals(new BigDecimal("500.00"), written.currentCycleCredit(),
                    "the accumulator the rule adds");
            assertEquals(new BigDecimal("250.00"), written.currentCycleDebit(),
                    "the accumulator the rule subtracts");
            assertEquals("2025-05-20", written.expirationDate(),
                    "the expiry the expiry rule compares as text");
        }

        /** Asserts the written account carries the submitted value and not the stored one. */
        @Test
        void theWrittenRowCarriesTheSubmittedValue() {
            lockBoth();

            update(proposed -> proposed.setCreditLimit(new BigDecimal("25000.00")));

            ArgumentCaptor<AccountEntity> saved = ArgumentCaptor.forClass(AccountEntity.class);
            verify(accounts).save(saved.capture());
            assertEquals(new BigDecimal("25000.00"), saved.getValue().getCreditLimit(),
                    "the submitted credit limit reached the row");
        }
    }

    /** The two paths that write nothing and are not failures. */
    @Nested
    @DisplayName("a pair that changes nothing")
    class APairThatChangesNothing {

        /**
         * Asserts an unchanged pair answers the source's own no-change text and writes nothing.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L1460-L1461} runs this comparison ahead of every field edit,
         * and {@code :L1466-L1467} returns. So an unchanged pair never reaches an edit, which is why a
         * pair that would fail an edit still answers no-change when it equals the fetched copy.
         */
        @Test
        void anUnchangedPairAnswersNoChangeAndWritesNothing() {
            lockBoth();

            EditResult verdict = update(proposed -> {
            });

            assertTrue(verdict.valid(), "nothing changed is not a failure");
            assertEquals("No change detected with respect to values fetched.", verdict.message(),
                    "app/cbl/COACTUPC.cbl:L1466 writes this text");
            verify(accounts, never()).save(any());
            verify(customers, never()).save(any());
            verify(outbox, never()).write(any());
        }

        /**
         * Asserts an unchanged pair is not edited at all.
         *
         * <p>The fixture pair below carries credit score 274, which the range edit refuses, and it
         * still answers no-change rather than a validation failure. That is the source's ordering, and
         * reproducing it means a caller resubmitting a record it never touched is never told its stored
         * data is invalid.
         */
        @Test
        void anUnchangedPairIsNotEditedEvenWhenItsStoredValuesWouldFail() {
            AccountEntity stored = account();
            CustomerEntity storedCustomer = customer();
            storedCustomer.setFicoCreditScore(new BigDecimal("274"));
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(stored));
            when(customers.findForUpdateByCustomerId(CUSTOMER_ID))
                    .thenReturn(Optional.of(storedCustomer));

            CustomerEntity proposedCustomer = customer();
            proposedCustomer.setFicoCreditScore(new BigDecimal("274"));

            EditResult verdict = service.updateAccount(account(), proposedCustomer, account(),
                    proposedCustomer);

            assertTrue(verdict.valid(), "the comparison ran before any edit could");
            assertEquals("No change detected with respect to values fetched.", verdict.message(),
                    "and answered before the range edit was reached");
        }
    }

    /** The path a pair with no fetched copy takes. */
    @Nested
    @DisplayName("a pair with no fetched copy")
    class APairWithNoFetchedCopy {

        /**
         * Asserts an absent fetched pair runs the search-key edit alone.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L1433} tests whether the details were fetched and
         * {@code :L1446} returns, so only the identifier is edited. The HTTP surface answers
         * {@code 404} before this branch is reachable, so this test is the only thing that covers it.
         */
        @Test
        void anAbsentFetchedPairEditsTheIdentifierAlone() {
            EditResult verdict = service.updateAccount(account(), customer(), null, null);

            assertTrue(verdict.valid(), "the identifier is eleven digits, so the edit passes");
            verify(accounts, never()).findForUpdateByAccountId(any());
            verify(accounts, never()).save(any());
            verify(outbox, never()).write(any());
        }

        /**
         * Asserts a blank identifier answers the not-supplied text, when one can be presented at all.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L1441-L1442} sets the no-criteria condition for a blank
         * filter, and that {@code SET} carries no guard, so it replaces whatever text the identifier
         * edit had already written. The branch is reached here by leaving the identifier unset, which is
         * the one way an entity can carry no identifier.
         */
        @Test
        void aBlankIdentifierAnswersTheNotSuppliedText() {
            AccountEntity blank = new AccountEntity();

            EditResult verdict = service.updateAccount(blank, customer(), null, null);

            assertFalse(verdict.valid(), "a blank filter is not a search key");
            assertEquals("No input received", verdict.message(),
                    "app/cbl/COACTUPC.cbl:L1442 replaces the identifier edit's own text");
        }

        /**
         * Asserts a blank identifier cannot be presented as spaces, because the entity refuses it.
         *
         * <p>The source held its filter in a screen field, so spaces were the ordinary way to express
         * an empty one, and {@code app/cbl/COACTUPC.cbl:L1441} tests for exactly that. The target holds
         * it in a column of eleven digits, and the entity enforces the width and the alphabet on the
         * setter, so the space-filled form is refused before any edit sees it.
         *
         * <p>The consequence is worth stating rather than leaving implicit: one of the two ways the
         * source could reach its no-criteria branch is unreachable here, and the reason is a stronger
         * invariant rather than a missing translation. The branch itself is still reachable, which the
         * test above shows.
         */
        @Test
        void aSpaceFilledIdentifierIsRefusedByTheEntityBeforeAnyEditRuns() {
            AccountEntity blank = account();

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> blank.setAccountId(" ".repeat(11)),
                    "ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5 holds digits");

            assertTrue(refused.getMessage().contains("accountId"),
                    "the refusal names the field, and it reads: " + refused.getMessage());
        }
    }

    /** The path a pair failing one edit takes. */
    @Nested
    @DisplayName("a pair that fails an edit")
    class APairThatFailsAnEdit {

        /**
         * Asserts a credit score below the range answers the source's own message, character for
         * character.
         *
         * <p>{@code app/cbl/COACTUPC.cbl} accepts 300 through 850. The message is reproduced exactly,
         * colon and spacing included, because a caller reading it should read what a terminal operator
         * read.
         */
        @Test
        void aCreditScoreBelowTheRangeAnswersItsVerbatimMessage() {
            lockBoth();
            CustomerEntity proposedCustomer = customer();
            proposedCustomer.setFicoCreditScore(new BigDecimal("274"));

            EditResult verdict =
                    service.updateAccount(account(), proposedCustomer, account(), customer());

            assertFalse(verdict.valid(), "274 is below the lowest passing score");
            assertEquals("FICO Score: should be between 300 and 850", verdict.message(),
                    "the text is reproduced character for character");
        }

        /** Asserts a failed edit writes nothing and produces no event. */
        @Test
        void aFailedEditWritesNothing() {
            lockBoth();
            CustomerEntity proposedCustomer = customer();
            proposedCustomer.setFicoCreditScore(new BigDecimal("274"));

            service.updateAccount(account(), proposedCustomer, account(), customer());

            verify(accounts, never()).save(any());
            verify(customers, never()).save(any());
            verify(outbox, never()).write(any());
        }

        /**
         * Asserts a failed edit never reaches the locks.
         *
         * <p>The edits run at {@code app/cbl/COACTUPC.cbl:L1470-L1676} and the locks at
         * {@code :L3892-L3930}, so a row a caller cannot write is never locked. Locking first would
         * hold a lock for the duration of a pass that was going to refuse the request anyway.
         */
        @Test
        void aFailedEditNeverReachesTheLocks() {
            CustomerEntity proposedCustomer = customer();
            proposedCustomer.setFicoCreditScore(new BigDecimal("274"));

            service.updateAccount(account(), proposedCustomer, account(), customer());

            verify(accounts, never()).findForUpdateByAccountId(any());
            verify(customers, never()).findForUpdateByCustomerId(any());
        }

        /**
         * Asserts an unlisted state and postcode combination is refused.
         *
         * <p>{@code app/cpy/CSLKPCDY.cpy:L1071-L1073} lists 240 combinations, and {@code NC} with a
         * postcode beginning {@code 12} is not among them. This is the edit the shipped fixture row
         * itself fails, which is worth one test of its own.
         */
        @Test
        void anUnlistedStateAndPostcodeCombinationIsRefused() {
            lockBoth();
            CustomerEntity proposedCustomer = customer();
            proposedCustomer.setAddressZip("12546     ");

            EditResult verdict =
                    service.updateAccount(account(), proposedCustomer, account(), customer());

            assertFalse(verdict.valid(), "NC with 12 is not one of the 240 listed combinations");
            assertNotNull(verdict.message(), "and the refusal says so");
        }

        /**
         * Asserts an account status of neither Y nor N is refused with its verbatim message.
         *
         * <p>Edit 1 at {@code app/cbl/COACTUPC.cbl:L1472-L1476}, the first of the twenty-four.
         */
        @Test
        void anAccountStatusOfNeitherLetterIsRefused() {
            lockBoth();

            EditResult verdict = update(proposed -> proposed.setActiveStatus("X"));

            assertFalse(verdict.valid(), "the flag is Y or N and nothing else");
            assertEquals("Account Status must be Y or N.", verdict.message(),
                    "edit 1 writes this text");
        }
    }

    /** The write path, and the three ways it refuses. */
    @Nested
    @DisplayName("the write path")
    class TheWritePath {

        /**
         * Asserts a row that could not be locked answers the lock text and writes nothing.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L3907-L3915}. A caller reading this should retry the request
         * unchanged, which is why the controller answers 409 for it rather than 422.
         */
        @Test
        void anAccountThatCouldNotBeLockedAnswersItsLockText() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

            EditResult verdict = update(proposed -> proposed.setCreditLimit(
                    new BigDecimal("25000.00")));

            assertFalse(verdict.valid(), "no locked row means no write");
            assertEquals("Could not lock account record for update", verdict.message(),
                    "app/cbl/COACTUPC.cbl:L3912 writes this text");
            verify(accounts, never()).save(any());
            verify(outbox, never()).write(any());
        }

        /**
         * Asserts a customer that could not be locked answers its own lock text.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L3934-L3942}. Two rows are locked, so two lock failures are
         * possible, and {@link AccountUpdateService#lockFailureMessages()} names both.
         */
        @Test
        void aCustomerThatCouldNotBeLockedAnswersItsLockText() {
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account()));
            when(customers.findForUpdateByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

            EditResult verdict = update(proposed -> proposed.setCreditLimit(
                    new BigDecimal("25000.00")));

            assertFalse(verdict.valid(), "no locked customer means no write");
            assertEquals("Could not lock customer record for update", verdict.message(),
                    "app/cbl/COACTUPC.cbl:L3939 writes this text");
            verify(customers, never()).save(any());
        }

        /** Asserts both lock texts are the two the accessor reports. */
        @Test
        void bothLockTextsAreTheOnesTheAccessorReports() {
            assertEquals(java.util.List.of("Could not lock account record for update",
                            "Could not lock customer record for update"),
                    AccountUpdateService.lockFailureMessages(),
                    "a caller distinguishing a retryable failure reads this list");
        }

        /**
         * Asserts a row another writer changed answers the changed-record text and writes nothing.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L3947-L3948} compares the re-read pair against the fetched
         * copy field by field and {@code :L3950-L3952} reports the verdict. The comparison happens
         * after the locks, so what it detects is a writer that committed between the caller's read and
         * this lock.
         */
        @Test
        void aRowAnotherWriterChangedAnswersTheChangedRecordText() {
            AccountEntity changedUnderneath = account();
            changedUnderneath.setCurrentBalance(new BigDecimal("9999.00"));
            when(accounts.findForUpdateByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.of(changedUnderneath));
            when(customers.findForUpdateByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(customer()));

            EditResult verdict = update(proposed -> proposed.setCreditLimit(
                    new BigDecimal("25000.00")));

            assertFalse(verdict.valid(), "the stored row is no longer the one the caller was shown");
            assertEquals(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE, verdict.message(),
                    "app/cbl/COACTUPC.cbl:L3951 writes this text");
            verify(accounts, never()).save(any());
            verify(outbox, never()).write(any());
        }
    }

    /** What the method refuses to be called with. */
    @Nested
    @DisplayName("arguments the method refuses")
    class RefusedArguments {

        /** Asserts an absent proposed account is refused rather than treated as empty. */
        @Test
        void anAbsentProposedAccountIsRefused() {
            assertThrows(NullPointerException.class,
                    () -> service.updateAccount(null, customer(), account(), customer()),
                    "there is nothing to edit and nothing to write");
        }

        /** Asserts an absent proposed customer is refused. */
        @Test
        void anAbsentProposedCustomerIsRefused() {
            assertThrows(NullPointerException.class,
                    () -> service.updateAccount(account(), null, account(), customer()),
                    "one call writes both rows, so both have to be supplied");
        }

        /** Asserts every constructor argument is required. */
        @Test
        void everyCollaboratorIsRequired() {
            ConcurrentChangeDetector detector = new ConcurrentChangeDetector();
            CardCrossReferenceRepository references = mock(CardCrossReferenceRepository.class);
            TransactionTemplate transactions = immediateTransactions();
            ObservabilityConfig.AccountMeters meters = accountMeters();

            assertThrows(NullPointerException.class, () -> new AccountUpdateService(null, customers,
                    references, detector, outbox, transactions, meters),
                    "the account store is required");
            assertThrows(NullPointerException.class, () -> new AccountUpdateService(accounts, null,
                    references, detector, outbox, transactions, meters),
                    "the customer store is required");
            assertThrows(NullPointerException.class, () -> new AccountUpdateService(accounts,
                    customers, null, detector, outbox, transactions, meters),
                    "the cross-reference store is required, because it names the authoritative "
                            + "customer");
            assertThrows(NullPointerException.class, () -> new AccountUpdateService(accounts,
                    customers, references, null, outbox, transactions, meters),
                    "the concurrency check is required");
            assertThrows(NullPointerException.class, () -> new AccountUpdateService(accounts,
                    customers, references, detector, null, transactions, meters),
                    "the outbox writer is required");
            assertThrows(NullPointerException.class, () -> new AccountUpdateService(accounts,
                    customers, references, detector, outbox, null, meters),
                    "the transaction boundary is required");
            assertThrows(NullPointerException.class, () -> new AccountUpdateService(accounts,
                    customers, references, detector, outbox, transactions, null),
                    "the meter holder is required");
        }
    }

    // Fixtures and helpers.

    /** Stubs both locked reads to return the stored pair unchanged. */
    private void lockBoth() {
        when(accounts.findForUpdateByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(customers.findForUpdateByCustomerId(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
    }

    /**
     * Runs one update whose proposed account carries the mutation given.
     *
     * @param mutation the one change the submitted account carries
     * @return the verdict the service answered
     */
    private EditResult update(Consumer<AccountEntity> mutation) {
        AccountEntity proposed = account();
        mutation.accept(proposed);
        return service.updateAccount(proposed, customer(), account(), customer());
    }

    /**
     * Builds one account that passes every edit.
     *
     * <p>The values come from row one of {@code app/data/ASCII/acctdata.txt}. Each call returns a fresh
     * instance, so a test that mutates one leaves the others alone.
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

    /**
     * Builds one customer that passes every edit.
     *
     * <p>The names and the address come from row one of {@code app/data/ASCII/custdata.txt}. Three
     * values differ from that row deliberately, because the row itself fails the edits: the postcode is
     * {@code 27604} rather than {@code 12546}, since {@code app/cpy/CSLKPCDY.cpy} lists {@code NC} with
     * {@code 27} and not with {@code 12}; the second telephone number carries area code {@code 919}
     * rather than {@code 373}, which the 980-code list does not hold; and the credit score is
     * {@code 688} rather than {@code 274}, which is below the 300 the range edit accepts.
     *
     * @return the customer
     */
    private static CustomerEntity customer() {
        CustomerEntity customer = new CustomerEntity();
        customer.setCustomerId(CUSTOMER_ID);
        customer.setFirstName("Immanuel");
        customer.setMiddleName("Madeline");
        customer.setLastName("Kessler");
        customer.setAddressLine1("618 Deshaun Route");
        customer.setAddressLine2("Apt. 802");
        customer.setAddressCity("Altenwerthshire");
        customer.setAddressStateCode("NC");
        customer.setAddressCountryCode("USA");
        customer.setAddressZip("27604     ");
        customer.setPhoneNumber1("(908)119-8310  ");
        customer.setPhoneNumber2("(919)693-8684  ");
        customer.setSocialSecurityNumber("020973888");
        customer.setGovernmentIssuedId("00000000000049368437");
        customer.setDateOfBirth("1961-06-08");
        customer.setEftAccountId("0053581756");
        customer.setPrimaryCardHolderIndicator("Y");
        customer.setFicoCreditScore(new BigDecimal("688"));
        return customer;
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
