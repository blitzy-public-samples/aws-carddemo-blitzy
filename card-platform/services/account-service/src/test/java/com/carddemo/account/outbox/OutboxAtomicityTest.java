package com.carddemo.account.outbox;

import com.carddemo.account.domain.AccountUpdateService;
import com.carddemo.account.domain.BillingCycleService;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.AbstractAccountPostgresTest;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.account.repository.OutboxEventRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record change and the outbox row of one call commit together, or neither commits.
 *
 * <p>Two paths in this service mutate state. {@code domain/AccountUpdateService} stores an account
 * row, a customer row and two event rows. {@code domain/BillingCycleService} stores an account row
 * and one event row. Both methods of {@link OutboxWriter} carry {@code Propagation.MANDATORY}, so
 * an event row reaches the table only inside a transaction its caller already opened.
 *
 * <p>Each test here opens that transaction through {@link TransactionTemplate}. The domain service
 * joins it at {@code PROPAGATION_REQUIRED} and opens none of its own. A test that marks the
 * transaction rollback-only discards the record change and the event row together. A test that
 * lets it commit keeps both.
 *
 * <p>The rollback legs discriminate. A service that opened a transaction of its own would leave
 * the record change behind after the outer rollback, and the assertion on the unchanged column
 * would then fail.
 *
 * <p><b>Source provenance for the update path.</b> {@code app/cbl/COACTUPC.cbl:L4066} rewrites the
 * account record and {@code app/cbl/COACTUPC.cbl:L4086} rewrites the customer record: two files,
 * one unit of work.
 *
 * <p>The customer branch reaches {@code SYNCPOINT ROLLBACK} at
 * {@code app/cbl/COACTUPC.cbl:L4100}. That file holds two synchronization points, the plain one at
 * {@code app/cbl/COACTUPC.cbl:L953} and that rollback. The account branch at
 * {@code app/cbl/COACTUPC.cbl:L4076-L4081} leaves the paragraph with no rollback, and nothing has
 * been rewritten at that point.
 *
 * <p>All eight file definitions of {@code app/csd/CARDDEMO.CSD} carry {@code RECOVERY(NONE)} and
 * {@code JOURNAL(NO)}. The account file declares {@code READINTEG(UNCOMMITTED)} at
 * {@code app/csd/CARDDEMO.CSD:L3}, {@code JOURNAL(NO)} at {@code app/csd/CARDDEMO.CSD:L7} and
 * {@code RECOVERY(NONE)} at {@code app/csd/CARDDEMO.CSD:L9}.
 *
 * <p><b>Source provenance for the close path.</b> The close reproduces two statements of
 * {@code 1050-UPDATE-ACCOUNT.} at {@code app/cbl/CBACT04C.cbl:L350}:
 * {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at {@code app/cbl/CBACT04C.cbl:L353} and
 * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at {@code app/cbl/CBACT04C.cbl:L354}. Both fields are
 * {@code PIC S9(10)V99}, at {@code app/cpy/CVACT01Y.cpy:L13} and
 * {@code app/cpy/CVACT01Y.cpy:L14}. The interest addition at {@code app/cbl/CBACT04C.cbl:L352}
 * has no counterpart here, and no assertion below reads a balance a close could have touched.
 *
 * <p><b>Publication sequence.</b> {@code outbox/OutboxRelay} publishes a stored row afterwards, in
 * a transaction of its own, and it cannot see an uncommitted row. Each committing test therefore
 * asserts that nothing left the publisher while its own transaction was still open. Every other
 * statement about the relay belongs to {@code outbox/OutboxRelayTest}.
 *
 * <p><b>What the siblings own.</b> {@code domain/BillingCycleServiceTest} asserts both
 * accumulators at scale 2, an unchanged balance, the event content and the change kind.
 * {@code domain/AccountUpdateServiceTest} asserts the verdict, the edit order and the event
 * content. Neither reaches a database. The assertions below read committed columns and committed
 * rows, and they repeat none of that content.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. The outbox figure sits in
 * {@code card-platform/docs/event-flow.md}.
 */
@DisplayName("one local transaction carries the record change and the outbox row together")
class OutboxAtomicityTest extends AbstractAccountPostgresTest {

    /** Account of record 1 of {@code app/data/ASCII/acctdata.txt}, columns 1 to 11. */
    private static final String ACCOUNT_ID = "00000000001";

    /**
     * Customer of {@link #ACCOUNT_ID}. One {@code account_customer_link} row joins the two, seeded by
     * {@code src/main/resources/db/migration/V4__card_cross_reference_replica.sql}.
     */
    private static final String CUSTOMER_ID = "000000001";

    /**
     * Cycle credit written and committed ahead of a close.
     *
     * <p>Columns 79 to 90 of all 50 records of {@code app/data/ASCII/acctdata.txt} decode to
     * {@code 0.00}, so a close of a seeded row moves no column. Both legs of the close therefore
     * lift both accumulators above zero first.</p>
     */
    private static final BigDecimal PRIMED_CYCLE_CREDIT = new BigDecimal("500.00");

    /**
     * Cycle debit written and committed ahead of a close. Columns 91 to 102 of all 50 records of
     * {@code app/data/ASCII/acctdata.txt} decode to {@code 0.00}.
     */
    private static final BigDecimal PRIMED_CYCLE_DEBIT = new BigDecimal("250.00");

    /**
     * Balance the update submits. Record 1 of {@code app/data/ASCII/acctdata.txt} holds
     * {@code 194.00} at columns 13 to 24.
     */
    private static final BigDecimal SUBMITTED_BALANCE = new BigDecimal("195.00");

    /**
     * Postal code the update submits. {@code app/cpy/CSLKPCDY.cpy} lists {@code 'NC27'} and lists
     * no {@code 'NC12'}, and record 1 of {@code app/data/ASCII/custdata.txt} holds {@code 12546}.
     */
    private static final String SUBMITTED_ZIP = "27604     ";

    /**
     * Second telephone number the update submits. {@code app/cpy/CSLKPCDY.cpy} lists {@code '919'}
     * and lists no {@code '373'}. Record 1 of {@code app/data/ASCII/custdata.txt} carries
     * {@code '373'} as its second area code.
     */
    private static final String SUBMITTED_PHONE_NUMBER_2 = "(919)693-8684  ";

    /**
     * Credit score the update submits. {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
     * {@code app/cbl/COACTUPC.cbl:L848-L849} bounds the field, and record 1 of
     * {@code app/data/ASCII/custdata.txt} holds 274.
     */
    private static final BigDecimal SUBMITTED_CREDIT_SCORE = new BigDecimal("688");

    /** Event rows one update stores: one account change and one cardholder change. */
    private static final int UPDATE_EVENT_ROWS = 2;

    /** Event rows one close stores. */
    private static final int CLOSE_EVENT_ROWS = 1;

    /** The update path under test, the container-managed instance. */
    @Autowired
    private AccountUpdateService accountUpdateService;

    /** The close path under test, the container-managed instance. */
    @Autowired
    private BillingCycleService billingCycleService;

    /** Reads the account row and restores it. */
    @Autowired
    private AccountRepository accounts;

    /** Reads the customer row and restores it. */
    @Autowired
    private CustomerRepository customers;

    /** Reads the event rows and deletes the ones a test committed. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** Opens every transaction this class uses. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The publisher every publish of this context reaches. */
    @Autowired
    private RecordingEventPublisher publisher;

    /** Boundary around one leg, one priming write, one read and one restore. */
    private TransactionTemplate transactions;

    /** The account row as the running test found it, restored afterwards. */
    private AccountEntity accountAsFound;

    /** The customer row as the running test found it, restored afterwards. */
    private CustomerEntity customerAsFound;

    /** Event identifiers present for {@link #ACCOUNT_ID} before the running test wrote anything. */
    private Set<UUID> eventIdsAsFound;

    /**
     * Empties the recording and measures the three pieces of state a test compares against.
     *
     * <p>Every assertion below reads a difference from what this method measured, so no assertion
     * depends on the order the module runs its test classes in.</p>
     */
    @BeforeEach
    void measureTheStateThisTestFound() {
        transactions = new TransactionTemplate(transactionManager);
        publisher.reset();
        accountAsFound = accountAsStored();
        customerAsFound = customerAsStored();
        eventIdsAsFound = eventIdsForTheAccount();
    }

    /**
     * Returns the two records and the outbox table to the state
     * {@link #measureTheStateThisTestFound} measured.
     *
     * <p>The merge writes every column of both records back. The delete removes the event rows a
     * committing leg added, and finds none after a rolled-back leg. {@code repository/} asserts
     * seeded row counts, seeded column values and an empty outbox table, and each of those holds
     * again once this method returns.</p>
     */
    @AfterEach
    void restoreTheStateThisTestFound() {
        if (accountAsFound == null || customerAsFound == null) {
            return;
        }

        Set<UUID> added = eventIdsAddedSince(eventIdsAsFound);
        transactions.executeWithoutResult(status -> {
            accounts.save(accountAsFound);
            customers.save(customerAsFound);
            outboxEvents.deleteAllById(added);
        });
    }

    /**
     * A rolled-back close leaves both accumulators as primed and adds no event row.
     *
     * <p>Both accumulators still hold {@link #PRIMED_CYCLE_CREDIT} and
     * {@link #PRIMED_CYCLE_DEBIT} afterwards, and the outbox table holds no new row for the
     * account. A close that had written its account row in a transaction of its own would show
     * two zeroed accumulators here.</p>
     */
    @Test
    @DisplayName("a rolled-back close leaves the primed accumulators standing and adds no event "
            + "row, so neither half of the pair survives alone")
    void aRolledBackCloseLeavesNeitherHalfOfThePair() {
        primeBothAccumulators();
        Set<UUID> eventIdsBefore = eventIdsForTheAccount();

        transactions.executeWithoutResult(status -> {
            billingCycleService.closeBillingCycle(ACCOUNT_ID).orElseThrow();
            status.setRollbackOnly();
        });

        AccountEntity afterRollback = accountAsStored();
        assertThat(afterRollback.getCurrentCycleCredit())
                .as("cycle credit of account %s after the rollback", ACCOUNT_ID)
                .isEqualByComparingTo(PRIMED_CYCLE_CREDIT);
        assertThat(afterRollback.getCurrentCycleDebit())
                .as("cycle debit of account %s after the rollback", ACCOUNT_ID)
                .isEqualByComparingTo(PRIMED_CYCLE_DEBIT);
        assertThat(eventIdsAddedSince(eventIdsBefore))
                .as("outbox_event rows added for account %s by the rolled-back close", ACCOUNT_ID)
                .isEmpty();
    }

    /**
     * A rolled-back update leaves the account row, the customer row and the outbox as found.
     *
     * <p>The submitted pair moves a balance on one record and a credit score on the other, which
     * reproduces the two rewrites at {@code app/cbl/COACTUPC.cbl:L4066} and
     * {@code app/cbl/COACTUPC.cbl:L4086}. Both columns hold their measured values afterwards, and
     * the outbox table holds no new row for the account.</p>
     */
    @Test
    @DisplayName("a rolled-back update leaves both records at their measured values and adds no "
            + "event row, so neither half of the pair survives alone")
    void aRolledBackUpdateLeavesNeitherHalfOfThePair() {
        Set<UUID> eventIdsBefore = eventIdsForTheAccount();

        transactions.executeWithoutResult(status -> {
            accountUpdateService.updateAccount(submittedAccount(), submittedCustomer(),
                    copyOf(accountAsFound), copyOf(customerAsFound));
            status.setRollbackOnly();
        });

        assertThat(accountAsStored().getCurrentBalance())
                .as("current balance of account %s after the rollback", ACCOUNT_ID)
                .isEqualByComparingTo(accountAsFound.getCurrentBalance());
        assertThat(customerAsStored().getFicoCreditScore())
                .as("credit score of customer %s after the rollback", CUSTOMER_ID)
                .isEqualByComparingTo(customerAsFound.getFicoCreditScore());
        assertThat(eventIdsAddedSince(eventIdsBefore))
                .as("outbox_event rows added for account %s by the rolled-back update", ACCOUNT_ID)
                .isEmpty();
    }

    /**
     * A committed close keeps the zeroed accumulators and the event row it wrote beside them.
     *
     * <p>The assertion inside the transaction reads the publisher before the commit. The row is
     * still uncommitted at that moment, so the relay cannot have taken it.</p>
     *
     * <p>The two accumulator assertions compare values and ignore scale.
     * {@code domain/BillingCycleServiceTest} asserts the scale.</p>
     */
    @Test
    @DisplayName("a committed close keeps both zeroed accumulators and the one event row it wrote, "
            + "and publishes nothing before the commit")
    void aCommittedCloseKeepsBothHalvesOfThePair() {
        primeBothAccumulators();
        Set<UUID> eventIdsBefore = eventIdsForTheAccount();

        transactions.executeWithoutResult(status -> {
            billingCycleService.closeBillingCycle(ACCOUNT_ID).orElseThrow();
            assertThat(publisher.publicationsKeyedTo(ACCOUNT_ID))
                    .as("publications for account %s while its transaction is still open",
                            ACCOUNT_ID)
                    .isEmpty();
        });

        AccountEntity afterCommit = accountAsStored();
        assertThat(afterCommit.getCurrentCycleCredit())
                .as("cycle credit of account %s after the commit", ACCOUNT_ID)
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterCommit.getCurrentCycleDebit())
                .as("cycle debit of account %s after the commit", ACCOUNT_ID)
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(eventIdsAddedSince(eventIdsBefore))
                .as("outbox_event rows added for account %s by the committed close", ACCOUNT_ID)
                .hasSize(CLOSE_EVENT_ROWS);
    }

    /**
     * A committed update keeps both record changes and the two event rows it wrote beside them.
     *
     * <p>The assertion inside the transaction reads the publisher before the commit. Both rows are
     * still uncommitted at that moment, so the relay cannot have taken either.</p>
     *
     * <p>The verdict travels into the assertion descriptions and carries no assertion of its own.
     * {@code domain/AccountUpdateServiceTest} asserts what a verdict holds.</p>
     */
    @Test
    @DisplayName("a committed update keeps both record changes and the two event rows it wrote, "
            + "and publishes nothing before the commit")
    void aCommittedUpdateKeepsBothHalvesOfThePair() {
        Set<UUID> eventIdsBefore = eventIdsForTheAccount();

        EditResult verdict = transactions.execute(status -> {
            EditResult answered = accountUpdateService.updateAccount(submittedAccount(),
                    submittedCustomer(), copyOf(accountAsFound), copyOf(customerAsFound)).verdict();
            assertThat(publisher.publicationsKeyedTo(ACCOUNT_ID))
                    .as("publications for account %s while its transaction is still open",
                            ACCOUNT_ID)
                    .isEmpty();
            return answered;
        });

        String committedUpdate = "after the commit of an update that answered " + verdict;
        assertThat(accountAsStored().getCurrentBalance())
                .as("current balance of account %s %s", ACCOUNT_ID, committedUpdate)
                .isEqualByComparingTo(SUBMITTED_BALANCE);
        assertThat(customerAsStored().getFicoCreditScore())
                .as("credit score of customer %s %s", CUSTOMER_ID, committedUpdate)
                .isEqualByComparingTo(SUBMITTED_CREDIT_SCORE);
        assertThat(eventIdsAddedSince(eventIdsBefore))
                .as("outbox_event rows added for account %s %s", ACCOUNT_ID, committedUpdate)
                .hasSize(UPDATE_EVENT_ROWS);
    }

    /**
     * {@link OutboxWriter} declares no field of type {@link EventPublisherPort}.
     *
     * <p>The writer stores a row and reaches no broker, so a call on either mutating path cannot
     * publish. {@code outbox/OutboxRelayTest} covers the publishing side.</p>
     */
    @Test
    @DisplayName("OutboxWriter declares no field of type EventPublisherPort")
    void theWriterDeclaresNoPublisherField() {
        List<Class<?>> declaredTypes = Arrays.stream(OutboxWriter.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertThat(declaredTypes)
                .as("declared field types of OutboxWriter")
                .doesNotContain(EventPublisherPort.class);
    }

    /**
     * Commits both accumulators above zero, in a transaction of its own.
     *
     * <p>The row this method reads is a different instance from {@link #accountAsFound}, so the
     * measured state stays intact and {@link #restoreTheStateThisTestFound} returns the seeded
     * values.</p>
     */
    private void primeBothAccumulators() {
        transactions.executeWithoutResult(status -> {
            AccountEntity row = accounts.findByAccountId(ACCOUNT_ID).orElseThrow();
            row.setCurrentCycleCredit(PRIMED_CYCLE_CREDIT);
            row.setCurrentCycleDebit(PRIMED_CYCLE_DEBIT);
            accounts.save(row);
        });
    }

    /**
     * Reads the committed account row in a fresh transaction.
     *
     * @return the row {@link #ACCOUNT_ID} names
     */
    private AccountEntity accountAsStored() {
        return transactions.execute(status -> accounts.findByAccountId(ACCOUNT_ID).orElseThrow());
    }

    /**
     * Reads the committed customer row in a fresh transaction.
     *
     * @return the row {@link #CUSTOMER_ID} names
     */
    private CustomerEntity customerAsStored() {
        return transactions
                .execute(status -> customers.findByCustomerId(CUSTOMER_ID).orElseThrow());
    }

    /**
     * Reads the committed event identifiers of one aggregate in a fresh transaction.
     *
     * <p>The aggregate identifier of every event this service writes is the account identifier, so
     * one filter selects the rows of both mutating paths. The column holds eleven characters and
     * needs no trimming.</p>
     *
     * @return the identifiers held for {@link #ACCOUNT_ID}, in the order the table returned them
     */
    private Set<UUID> eventIdsForTheAccount() {
        return transactions.execute(status -> outboxEvents.findAll().stream()
                .filter(row -> ACCOUNT_ID.equals(row.getAggregateId()))
                .map(OutboxEventEntity::getEventId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /**
     * Returns the event identifiers held now and absent from an earlier reading.
     *
     * @param earlier the identifiers a caller measured before the operation under test
     * @return the identifiers the operation added, empty when it added none
     */
    private Set<UUID> eventIdsAddedSince(Set<UUID> earlier) {
        Set<UUID> added = eventIdsForTheAccount();
        added.removeAll(earlier);
        return added;
    }

    /**
     * Builds the account the update submits: the measured row with one moved balance.
     *
     * @return the submitted account
     */
    private AccountEntity submittedAccount() {
        AccountEntity submitted = copyOf(accountAsFound);
        submitted.setCurrentBalance(SUBMITTED_BALANCE);
        return submitted;
    }

    /**
     * Builds the customer the update submits: the measured row with three moved fields.
     *
     * <p>Record 1 of {@code app/data/ASCII/custdata.txt} fails three of the field edits, and each
     * constant this method sets carries the locator of the edit that refuses the stored value.</p>
     *
     * @return the submitted customer
     */
    private CustomerEntity submittedCustomer() {
        CustomerEntity submitted = copyOf(customerAsFound);
        submitted.setAddressZip(SUBMITTED_ZIP);
        submitted.setPhoneNumber2(SUBMITTED_PHONE_NUMBER_2);
        submitted.setFicoCreditScore(SUBMITTED_CREDIT_SCORE);
        return submitted;
    }

    /**
     * Copies the twelve mapped columns of one account.
     *
     * <p>The copy is what the update reads as the pair the caller was shown, and
     * {@link #accountAsFound} stays untouched for the restore.</p>
     *
     * @param source the row to copy
     * @return a detached copy carrying the same twelve values
     */
    private static AccountEntity copyOf(AccountEntity source) {
        AccountEntity copy = new AccountEntity();
        copy.setAccountId(source.getAccountId());
        copy.setActiveStatus(source.getActiveStatus());
        copy.setCurrentBalance(source.getCurrentBalance());
        copy.setCreditLimit(source.getCreditLimit());
        copy.setCashCreditLimit(source.getCashCreditLimit());
        copy.setOpenDate(source.getOpenDate());
        copy.setExpirationDate(source.getExpirationDate());
        copy.setReissueDate(source.getReissueDate());
        copy.setCurrentCycleCredit(source.getCurrentCycleCredit());
        copy.setCurrentCycleDebit(source.getCurrentCycleDebit());
        copy.setAddressZip(source.getAddressZip());
        copy.setGroupId(source.getGroupId());
        return copy;
    }

    /**
     * Copies the eighteen mapped columns of one customer.
     *
     * @param source the row to copy
     * @return a detached copy carrying the same eighteen values
     */
    private static CustomerEntity copyOf(CustomerEntity source) {
        CustomerEntity copy = new CustomerEntity();
        copy.setCustomerId(source.getCustomerId());
        copy.setFirstName(source.getFirstName());
        copy.setMiddleName(source.getMiddleName());
        copy.setLastName(source.getLastName());
        copy.setAddressLine1(source.getAddressLine1());
        copy.setAddressLine2(source.getAddressLine2());
        copy.setAddressCity(source.getAddressCity());
        copy.setAddressStateCode(source.getAddressStateCode());
        copy.setAddressCountryCode(source.getAddressCountryCode());
        copy.setAddressZip(source.getAddressZip());
        copy.setPhoneNumber1(source.getPhoneNumber1());
        copy.setPhoneNumber2(source.getPhoneNumber2());
        copy.setSocialSecurityNumber(source.getSocialSecurityNumber());
        copy.setGovernmentIssuedId(source.getGovernmentIssuedId());
        copy.setDateOfBirth(source.getDateOfBirth());
        copy.setEftAccountId(source.getEftAccountId());
        copy.setPrimaryCardHolderIndicator(source.getPrimaryCardHolderIndicator());
        copy.setFicoCreditScore(source.getFicoCreditScore());
        return copy;
    }

    /** Registers {@link RecordingEventPublisher} over the Kafka-backed publisher of the module. */
    @TestConfiguration
    static class RecordingPublisherConfiguration {

        /**
         * Builds the publisher every publish of this context reaches.
         *
         * @return the recording publisher, primary among the publishers of the context
         */
        @Bean
        @Primary
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    /**
     * Keeps every publication in call order and completes each one normally.
     *
     * <p>The scheduled relay and the running test both reach this publisher, so the recording is a
     * thread-safe list. The stub starts no broker and needs no created topic.</p>
     */
    static final class RecordingEventPublisher implements EventPublisherPort {

        /** Every publication this publisher took, in call order. */
        private final List<Publication> publications = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Void> publish(String topic, String aggregateId, String payload) {
            publications.add(new Publication(topic, aggregateId, payload));
            return CompletableFuture.completedFuture(null);
        }

        /** Empties the recording. */
        void reset() {
            publications.clear();
        }

        /**
         * Returns the publications carrying one message key, in call order.
         *
         * @param aggregateId the eleven-digit account identifier the key holds
         * @return the recorded publications that key selects
         */
        List<Publication> publicationsKeyedTo(String aggregateId) {
            return publications.stream()
                    .filter(publication -> aggregateId.equals(publication.key()))
                    .toList();
        }
    }

    /**
     * One publication, as {@link EventPublisherPort#publish(String, String, String)} received it.
     *
     * @param topic   the destination topic name
     * @param key     the message key, an eleven-digit account identifier
     * @param payload the event text
     */
    record Publication(String topic, String key, String payload) {
    }
}
