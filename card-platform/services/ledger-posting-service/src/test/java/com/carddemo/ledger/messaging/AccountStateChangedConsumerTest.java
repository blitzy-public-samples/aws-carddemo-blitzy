package com.carddemo.ledger.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.cobol.PicClause;
import com.carddemo.ledger.config.ObservabilityConfig;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Covers the listener that keeps {@code account_balance_projection} current.
 *
 * <p>Two gaps were reachable without this listener, and both are asserted here. An account opened
 * after deployment had no projection row, because {@code V2__seed.sql} loads the fifty rows of
 * {@code app/data/ASCII/acctdata.txt} and nothing added a fifty-first, so every transaction on such an
 * account failed the balance read for good. And a billing cycle closed at
 * {@code app/cbl/CBACT04C.cbl:L353-L354} never reached the two accumulators here, so they grew without
 * bound while the account service's own copies were zeroed each cycle.
 *
 * <p>The third property asserted here is ordering. The projection is a copy, so a redelivery arriving
 * behind a newer change must leave the row alone: a regressed cycle balance raises nothing and quietly
 * accumulates onto a number the account service had already superseded.
 *
 * <p>The fourth is ownership, and it is the property most easily broken. All three value columns are
 * produced by the posting arithmetic at {@code app/cbl/CBTRN02C.cbl:L545-L560}. The account
 * service applies that same arithmetic to its own record, because its
 * {@code messaging/TransactionPostedConsumer} consumes {@code TransactionPosted}, so the copy an
 * account change carries trails this projection by every posting whose event it has not consumed yet
 * rather than carrying none of them. It is also, in that case, a copy of this service's own output
 * returning one hop later. So a change that names an ordinary field update must leave a row this
 * projection already holds exactly as it stands, and only a cycle close may write to one — zeroing
 * the two accumulators and nothing else.
 */
@DisplayName("The account-state listener that keeps the balance projection current")
class AccountStateChangedConsumerTest {

    /** The account every change below names, at the width {@code ACCT-ID PIC 9(11)} declares. */
    private static final String ACCOUNT_ID = "00000000001";

    /** The topic a delivery reports, and the value the marker records. */
    private static final String TOPIC = "account.state-changed";

    /** Reads one message into a tree, as the schema-validating deserializer hands it over. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AccountBalanceProjectionRepository accountBalances;
    private ProcessedEventRepository processedEvents;
    private MeterRegistry registry;
    private LedgerMeters meters;
    private Acknowledgment acknowledgment;
    private AccountStateChangedConsumer consumer;

    /** Builds the subject over stubbed stores and a real meter registry. */
    @BeforeEach
    void buildSubject() {
        accountBalances = mock(AccountBalanceProjectionRepository.class);
        processedEvents = mock(ProcessedEventRepository.class);
        registry = new SimpleMeterRegistry();
        meters = new ObservabilityConfig().ledgerMeters(registry);
        acknowledgment = mock(Acknowledgment.class);
        consumer = new AccountStateChangedConsumer(accountBalances, processedEvents, meters,
                selfProviderReturningSubject());
    }

    /**
     * Supplies a provider whose {@code getObject} answers with the consumer under test.
     *
     * @return the provider the constructor takes
     */
    private ObjectProvider<AccountStateChangedConsumer> selfProviderReturningSubject() {
        return new ObjectProvider<>() {
            @Override
            public AccountStateChangedConsumer getObject() {
                return consumer;
            }

            @Override
            public AccountStateChangedConsumer getObject(Object... arguments) {
                return consumer;
            }

            @Override
            public AccountStateChangedConsumer getIfAvailable() {
                return consumer;
            }

            @Override
            public AccountStateChangedConsumer getIfUnique() {
                return consumer;
            }
        };
    }

    /** Answers the claim with {@code 1}, so this delivery is the first to hold the identifier. */
    private void claimSucceeds() {
        when(processedEvents.claimEvent(any(UUID.class), any(Instant.class), anyString()))
                .thenReturn(1);
    }

    /** Answers the bootstrap insert with {@code 1}, so the projection held no row for the account. */
    private void bootstrapInserts() {
        when(accountBalances.insertMissingProjection(anyString(), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), any(UUID.class), any(Instant.class)))
                .thenReturn(1);
    }

    /** Answers the bootstrap insert with {@code 0}, so the projection already holds the row. */
    private void rowAlreadyHeld() {
        when(accountBalances.insertMissingProjection(anyString(), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), any(UUID.class), any(Instant.class)))
                .thenReturn(0);
    }

    /** Answers the accumulator zeroing with {@code 1}, so the cycle close is the newest change. */
    private void cycleCloseApplies() {
        when(accountBalances.closeBillingCycle(anyString(), any(UUID.class), any(Instant.class)))
                .thenReturn(1);
    }

    /** Answers the accumulator zeroing with {@code 0}, so a later change already stood. */
    private void cycleCloseDiscarded() {
        when(accountBalances.closeBillingCycle(anyString(), any(UUID.class), any(Instant.class)))
                .thenReturn(0);
    }

    /**
     * Builds one schema-shaped message tree.
     *
     * @param balance     ACCT-CURR-BAL as the producer sends it, a decimal string
     * @param cycleCredit ACCT-CURR-CYC-CREDIT as a decimal string
     * @param cycleDebit  ACCT-CURR-CYC-DEBIT as a decimal string
     * @param occurredAt  the producer's clock
     * @return the tree the deserializer would hand over
     */
    private static JsonNode message(String balance, String cycleCredit, String cycleDebit,
            Instant occurredAt) {
        return message(balance, cycleCredit, cycleDebit, occurredAt,
                AccountStateChanged.CHANGE_KIND_ACCOUNT_UPDATED);
    }

    /**
     * Builds one schema-shaped message tree naming one change kind.
     *
     * @param balance     ACCT-CURR-BAL as the producer sends it, a decimal string
     * @param cycleCredit ACCT-CURR-CYC-CREDIT as a decimal string
     * @param cycleDebit  ACCT-CURR-CYC-DEBIT as a decimal string
     * @param occurredAt  the producer's clock
     * @param changeKind  the value the {@code changeKind} property carries
     * @return the tree the deserializer would hand over
     */
    private static JsonNode message(String balance, String cycleCredit, String cycleDebit,
            Instant occurredAt, String changeKind) {
        ObjectNode tree = MAPPER.createObjectNode();
        tree.put("eventId", UUID.randomUUID().toString());
        tree.put("eventType", AccountStateChanged.EVENT_TYPE);
        tree.put("schemaVersion", 1);
        tree.put("occurredAt", occurredAt.toString());
        tree.put("aggregateId", ACCOUNT_ID);
        tree.put("accountId", ACCOUNT_ID);
        tree.put("currentBalance", balance);
        tree.put("creditLimit", "5000.00");
        tree.put("currentCycleCredit", cycleCredit);
        tree.put("currentCycleDebit", cycleDebit);
        tree.put("expirationDate", "2099-12-31");
        tree.put("changeKind", changeKind);
        return tree;
    }

    /** A change carrying the fixture values of record one, naming an ordinary field update. */
    private static JsonNode aChange() {
        return message("193.00", "0.00", "0.00", Instant.parse("2026-01-01T00:00:00Z"));
    }

    /**
     * A cycle close, which is what {@code app/cbl/CBACT04C.cbl:L353-L354} publishes.
     *
     * <p>The account service zeroes both accumulators before it publishes, so the payload carries
     * zeroes. This replica reads the change kind and not those values, because a cycle close is a
     * command rather than a reading.
     *
     * @return the tree the deserializer would hand over
     */
    private static JsonNode aCycleClose() {
        return message("193.00", "0.00", "0.00", Instant.parse("2026-02-01T00:00:00Z"),
                AccountStateChanged.CHANGE_KIND_BILLING_CYCLE_CLOSED);
    }

    /**
     * Reads one counter.
     *
     * @param name     the meter name
     * @param tagKey   the tag key
     * @param tagValue the tag value
     * @return the count
     */
    private double counter(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    /**
     * Reads one untagged counter.
     *
     * @param name the meter name
     * @return its count
     */
    private double counterOf(String name) {
        return registry.get(name).counter().count();
    }

    /**
     * Reads how many recordings one timer holds.
     *
     * @param name the meter name
     * @return the number of recordings, which is what proves a latency was recorded at all
     */
    private long timerCount(String name) {
        return registry.get(name).timer().count();
    }

    @Nested
    @DisplayName("Bootstrap, the gap an account opened after deployment fell into")
    class Bootstrap {

        @Test
        @DisplayName("an absent row is opened from the values the change carried")
        void anAbsentRowIsOpenedFromTheChange() {
            claimSucceeds();
            bootstrapInserts();

            consumer.onAccountStateChanged(aChange(), ACCOUNT_ID, acknowledgment, TOPIC);

            verify(accountBalances, times(1)).insertMissingProjection(eq(ACCOUNT_ID),
                    eq(new BigDecimal("193.00")), eq(new BigDecimal("0.00")),
                    eq(new BigDecimal("0.00")), any(UUID.class), any(Instant.class));
            verify(acknowledgment, times(1)).acknowledge();
        }

        @Test
        @DisplayName("the bootstrap reports that the projection moved")
        void theBootstrapReportsThatTheProjectionMoved() {
            claimSucceeds();
            bootstrapInserts();

            assertTrue(consumer.applyOneEvent(AccountStateChanged.from(aChange()), TOPIC),
                    "an absent row is inserted, which is how a new account first gets one");
        }

        @Test
        @DisplayName("a bootstrap needs no cycle-close write, whatever the change kind names")
        void aBootstrapNeedsNoSecondWrite() {
            claimSucceeds();
            bootstrapInserts();

            consumer.onAccountStateChanged(aCycleClose(), ACCOUNT_ID, acknowledgment, TOPIC);

            verify(accountBalances, times(1)).insertMissingProjection(anyString(),
                    any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class),
                    any(UUID.class), any(Instant.class));
            verify(accountBalances, never()).closeBillingCycle(anyString(), any(UUID.class),
                    any(Instant.class));
        }
    }

    @Nested
    @DisplayName("Ownership of the three value columns the posting arithmetic derives")
    class Ownership {

        @Test
        @DisplayName("an ordinary field update leaves a row this projection already holds alone")
        void anAccountUpdateLeavesAHeldRowAlone() {
            claimSucceeds();
            rowAlreadyHeld();

            assertFalse(consumer.applyOneEvent(AccountStateChanged.from(aChange()), TOPIC),
                    "the balance and both accumulators are derived here, and this change carries"
                            + " the account service's copy of them, which holds no posting");

            verify(accountBalances, never()).closeBillingCycle(anyString(), any(UUID.class),
                    any(Instant.class));
        }

        @Test
        @DisplayName("an ordinary field update still acknowledges and counts no failure")
        void anAccountUpdateStillAcknowledges() {
            claimSucceeds();
            rowAlreadyHeld();

            consumer.onAccountStateChanged(aChange(), ACCOUNT_ID, acknowledgment, TOPIC);

            verify(acknowledgment, times(1)).acknowledge();
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "writing nothing is the correct outcome and not a fault");
        }

        @Test
        @DisplayName("a cycle close zeroes both accumulators and names no balance")
        void aCycleCloseZeroesBothAccumulators() {
            claimSucceeds();
            rowAlreadyHeld();
            cycleCloseApplies();

            assertTrue(consumer.applyOneEvent(AccountStateChanged.from(aCycleClose()), TOPIC),
                    "the cycle close is the one change this replica carries out on a held row");

            verify(accountBalances, times(1)).closeBillingCycle(eq(ACCOUNT_ID), any(UUID.class),
                    eq(Instant.parse("2026-02-01T00:00:00Z")));
        }

        @Test
        @DisplayName("a cycle close carrying a stale balance cannot move the balance")
        void aCycleCloseCarryingAStaleBalanceCannotMoveIt() {
            claimSucceeds();
            rowAlreadyHeld();
            cycleCloseApplies();
            JsonNode staleBalance = message("1.00", "0.00", "0.00",
                    Instant.parse("2026-02-01T00:00:00Z"),
                    AccountStateChanged.CHANGE_KIND_BILLING_CYCLE_CLOSED);

            consumer.onAccountStateChanged(staleBalance, ACCOUNT_ID, acknowledgment, TOPIC);

            // The zeroing statement names two columns and takes no balance argument at all, so a
            // balance in the payload has no route to the column that holds the posted value.
            verify(accountBalances, times(1)).closeBillingCycle(anyString(), any(UUID.class),
                    any(Instant.class));
        }
    }

    @Nested
    @DisplayName("Ordering, so a redelivery cannot move a cycle balance backwards")
    class Ordering {

        @Test
        @DisplayName("a cycle close a later change superseded writes nothing and still"
                + " acknowledges")
        void asupersededCycleCloseWritesNothing() {
            claimSucceeds();
            rowAlreadyHeld();
            cycleCloseDiscarded();

            assertFalse(consumer.applyOneEvent(AccountStateChanged.from(aCycleClose()), TOPIC),
                    "the row already carries a later change, so this one leaves it alone");
        }

        @Test
        @DisplayName("a write that moved nothing is not a failure")
        void aWriteThatMovedNothingIsNotAFailure() {
            claimSucceeds();
            rowAlreadyHeld();
            cycleCloseDiscarded();

            consumer.onAccountStateChanged(aCycleClose(), ACCOUNT_ID, acknowledgment, TOPIC);

            verify(acknowledgment, times(1)).acknowledge();
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "an out-of-order delivery is expected traffic and not a fault");
        }

        @Test
        @DisplayName("the producer clock travels to the write, not this service's clock")
        void theProducerClockTravelsToTheWrite() {
            claimSucceeds();
            rowAlreadyHeld();
            cycleCloseApplies();
            Instant occurredAt = Instant.parse("2026-03-04T05:06:07Z");

            consumer.applyOneEvent(AccountStateChanged.from(message("1.00", "0.00", "0.00",
                    occurredAt, AccountStateChanged.CHANGE_KIND_BILLING_CYCLE_CLOSED)), TOPIC);

            verify(accountBalances).closeBillingCycle(anyString(), any(UUID.class), eq(occurredAt));
        }

        @Test
        @DisplayName("the bootstrap carries the producer clock as the provenance of the new row")
        void theBootstrapCarriesTheProducerClock() {
            claimSucceeds();
            bootstrapInserts();
            Instant occurredAt = Instant.parse("2026-03-04T05:06:07Z");

            consumer.applyOneEvent(
                    AccountStateChanged.from(message("1.00", "0.00", "0.00", occurredAt)), TOPIC);

            verify(accountBalances).insertMissingProjection(anyString(), any(BigDecimal.class),
                    any(BigDecimal.class), any(BigDecimal.class), any(UUID.class), eq(occurredAt));
        }
    }

    @Nested
    @DisplayName("Duplicate delivery and failure handling")
    class DuplicateAndFailure {

        @Test
        @DisplayName("a claimed identifier leaves the projection untouched and still acknowledges")
        void aClaimedIdentifierAppliesNothing() {
            when(processedEvents.claimEvent(any(UUID.class), any(Instant.class), anyString()))
                    .thenReturn(0);

            consumer.onAccountStateChanged(aChange(), ACCOUNT_ID, acknowledgment, TOPIC);

            verifyNoInteractions(accountBalances);
            verify(acknowledgment, times(1)).acknowledge();
        }

        @Test
        @DisplayName("the claim runs before the write, so no write precedes the guard")
        void theClaimRunsFirst() {
            when(processedEvents.claimEvent(any(UUID.class), any(Instant.class), anyString()))
                    .thenReturn(0);

            assertFalse(consumer.applyOneEvent(AccountStateChanged.from(aChange()), TOPIC),
                    "the claim decides on its own");
            verify(processedEvents, times(1)).claimEvent(any(UUID.class), any(Instant.class),
                    eq(TOPIC));
            verifyNoInteractions(accountBalances);
        }

        @Test
        @DisplayName("a tombstone is refused rather than ignored")
        void aTombstoneIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onAccountStateChanged(null, ACCOUNT_ID, acknowledgment, TOPIC),
                    "this stream produces no tombstone, so one means a producer defect");

            verify(acknowledgment, never()).acknowledge();
            verifyNoInteractions(accountBalances);
        }

        /**
         * A tombstone is counted and timed as well as classified.
         *
         * <p>It was classified on the deserialization series and counted on neither of the other
         * two, so a refused delivery raised a failure reading with no consumed event and no latency
         * behind it. A record that arrived was consumed whatever became of it.
         */
        @Test
        @DisplayName("a tombstone is counted, timed and classified as a read failure")
        void aTombstoneIsCountedTimedAndClassified() {
            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onAccountStateChanged(null, ACCOUNT_ID, acknowledgment, TOPIC));

            assertEquals(1.0d, counterOf("carddemo.ledger.events.consumed"),
                    "a record that arrived was consumed whatever became of it");
            assertEquals(1L, timerCount("carddemo.ledger.processing.latency"),
                    "the latency of the refusal, recorded in a finally");
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "deserialize"),
                    "a payload that cannot be read is a read failure, not a processing failure");
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "nothing was processed, so the processing series stays flat");
        }

        /**
         * A tree the record refuses is measured exactly as a tombstone is.
         *
         * <p>The record is built before anything is claimed, and its own checks reject a tree that
         * passed schema validation. That rejection used to run before the consumed count, so it
         * moved none of the three families and the delivery was invisible on all of them.
         */
        @Test
        @DisplayName("an unreadable payload is counted, timed and classified as a read failure")
        void anUnreadablePayloadIsCountedTimedAndClassified() {
            JsonNode unreadable = JsonMapper.builder().build().createObjectNode();

            assertThrows(RuntimeException.class, () -> consumer.onAccountStateChanged(unreadable,
                    ACCOUNT_ID, acknowledgment, TOPIC));

            assertEquals(1.0d, counterOf("carddemo.ledger.events.consumed"));
            assertEquals(1L, timerCount("carddemo.ledger.processing.latency"));
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "deserialize"));
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "process"));
            verify(acknowledgment, never()).acknowledge();
            verifyNoInteractions(accountBalances);
        }

        /** A key that names another account is measured on the processing series. */
        @Test
        @DisplayName("a misrouted key is counted, timed and classified as a processing failure")
        void aMisroutedKeyIsCountedTimedAndClassified() {
            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onAccountStateChanged(aChange(), "00000000099", acknowledgment,
                            TOPIC));

            assertEquals(1.0d, counterOf("carddemo.ledger.events.consumed"));
            assertEquals(1L, timerCount("carddemo.ledger.processing.latency"));
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "the payload read cleanly, so the refusal belongs to processing");
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "deserialize"));
        }

        @Test
        @DisplayName("a successful delivery counts one consumed event, one latency and no failure")
        void aSuccessfulDeliveryCountsConsumedAndLatencyAndNoFailure() {
            claimSucceeds();
            bootstrapInserts();

            consumer.onAccountStateChanged(aChange(), ACCOUNT_ID, acknowledgment, TOPIC);

            assertEquals(1.0d, counterOf("carddemo.ledger.events.consumed"));
            assertEquals(1L, timerCount("carddemo.ledger.processing.latency"));
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "process"));
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "deserialize"));
        }

        @Test
        @DisplayName("a write fault refuses the acknowledgement and counts one failure")
        void aWriteFaultRefusesTheAcknowledgement() {
            claimSucceeds();
            when(accountBalances.insertMissingProjection(anyString(), any(BigDecimal.class),
                    any(BigDecimal.class), any(BigDecimal.class), any(UUID.class),
                    any(Instant.class))).thenThrow(new IllegalStateException("the store refused"));

            assertThrows(IllegalStateException.class,
                    () -> consumer.onAccountStateChanged(aChange(), ACCOUNT_ID,
                            acknowledgment, TOPIC));

            verify(acknowledgment, never()).acknowledge();
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "the fault is counted, and the offset stays uncommitted");
        }

        @Test
        @DisplayName("a cycle-close fault refuses the acknowledgement and counts one failure")
        void aCycleCloseFaultRefusesTheAcknowledgement() {
            claimSucceeds();
            rowAlreadyHeld();
            when(accountBalances.closeBillingCycle(anyString(), any(UUID.class),
                    any(Instant.class))).thenThrow(new IllegalStateException("the store refused"));

            assertThrows(IllegalStateException.class,
                    () -> consumer.onAccountStateChanged(aCycleClose(), ACCOUNT_ID,
                            acknowledgment, TOPIC));

            verify(acknowledgment, never()).acknowledge();
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "the fault is counted, and the offset stays uncommitted");
        }

        @Test
        @DisplayName("no log line and no meter tag carries the account identifier")
        void nothingCarriesTheAccountIdentifier() {
            claimSucceeds();
            bootstrapInserts();

            consumer.onAccountStateChanged(aChange(), ACCOUNT_ID, acknowledgment, TOPIC);

            registry.getMeters().forEach(meter -> meter.getId().getTags()
                    .forEach(tag -> assertFalse(tag.getValue().contains(ACCOUNT_ID),
                            "meter " + meter.getId().getName() + " tags an account identifier")));
        }
    }

    @Nested
    @DisplayName("The payload the listener binds")
    class Payload {

        @Test
        @DisplayName("the record holds the change kind and the three projected columns, and no more")
        void theRecordHoldsTheChangeKindAndTheThreeProjectedColumns() {
            AccountStateChanged event = AccountStateChanged.from(aChange());

            assertEquals(ACCOUNT_ID, event.accountId(), "the account the change describes");
            assertEquals(AccountStateChanged.CHANGE_KIND_ACCOUNT_UPDATED, event.changeKind(),
                    "the change kind decides what this delivery writes, so it is bound");
            assertEquals(new BigDecimal("193.00"), event.currentBalance(), "ACCT-CURR-BAL");
            assertEquals(new BigDecimal("0.00"), event.currentCycleCredit(),
                    "ACCT-CURR-CYC-CREDIT");
            assertEquals(new BigDecimal("0.00"), event.currentCycleDebit(), "ACCT-CURR-CYC-DEBIT");
            assertEquals(7, AccountStateChanged.class.getRecordComponents().length,
                    "the credit limit and the expiry date belong to the decline rules, so this "
                            + "service does not hold a copy of either");
        }

        @Test
        @DisplayName("the change kind names which of the two mutations produced the event")
        void theChangeKindNamesTheMutation() {
            assertFalse(AccountStateChanged.from(aChange()).closesBillingCycle(),
                    "a field update closes no cycle");
            assertTrue(AccountStateChanged.from(aCycleClose()).closesBillingCycle(),
                    "a cycle close is the one change this replica carries out on a held row");
        }

        @Test
        @DisplayName("a change kind the schema does not enumerate is refused")
        void anUnknownChangeKindIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> AccountStateChanged.from(message("193.00", "0.00", "0.00",
                            Instant.parse("2026-01-01T00:00:00Z"), "BALANCE_ADJUSTED")),
                    "this replica decides what to write from the change kind, so a value it does"
                            + " not recognise names a producer it no longer understands");
        }

        @Test
        @DisplayName("money arrives as text, so no scale is lost to a binary double")
        void moneyArrivesAsText() {
            AccountStateChanged event = AccountStateChanged.from(
                    message("193.40", "12.34", "-5.60", Instant.parse("2026-01-01T00:00:00Z")));

            assertEquals(PicClause.ACCT_CURR_BAL_SCALE, event.currentBalance().scale(),
                    "the scale the producer sent survives the read");
            assertEquals(new BigDecimal("-5.60"), event.currentCycleDebit(),
                    "a negative accumulator keeps its sign and its scale");
        }

        @Test
        @DisplayName("a component at the wrong scale stops here rather than reaching a column")
        void aComponentAtTheWrongScaleIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> AccountStateChanged.from(message("193.4", "0.00", "0.00",
                            Instant.parse("2026-01-01T00:00:00Z"))),
                    "the native upsert bypasses the entity constructor, so the scale is checked "
                            + "here or nowhere");
        }
    }

    @Nested
    @DisplayName("The message key has to name the account the payload names")
    class MessageKeyGuard {

        /**
         * A record keyed on another account arrived on a partition that does not order this
         * account's events. AAP 0.3.1 keys every event by account identifier for exactly that
         * reason, and applying such a record would move a balance onto an account the message never
         * named. The check runs before the transaction opens, so nothing is written and no marker
         * claims the event: a correctly keyed redelivery can still apply it.
         */
        @Test
        @DisplayName("a key naming another account is refused and writes nothing")
        void aKeyNamingAnotherAccountIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onAccountStateChanged(aChange(), "00000000099",
                            acknowledgment, TOPIC),
                    "the partition this record arrived on does not order the account it names");

            verifyNoInteractions(accountBalances);
            verifyNoInteractions(acknowledgment);
        }

        /** A record with no key at all was partitioned at random, which is the same defect. */
        @Test
        @DisplayName("a record carrying no key is refused")
        void aRecordCarryingNoKeyIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onAccountStateChanged(aChange(), null, acknowledgment, TOPIC),
                    "a record with no key was partitioned at random");

            verifyNoInteractions(accountBalances);
            verifyNoInteractions(acknowledgment);
        }

        /** A blank key names nothing, so it is refused for the same reason as an absent one. */
        @Test
        @DisplayName("a blank key is refused")
        void aBlankKeyIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onAccountStateChanged(aChange(), "   ", acknowledgment, TOPIC),
                    "a blank key names no account");

            verifyNoInteractions(accountBalances);
        }

        /**
         * A key that agrees with the envelope while the payload names something else routes
         * correctly and writes to the wrong row, so all three values are compared rather than two.
         */
        @Test
        @DisplayName("an envelope and a payload that disagree are refused even when the key matches"
                + " one of them")
        void anEnvelopeAndPayloadThatDisagreeAreRefused() {
            ObjectNode disagreeing = (ObjectNode) aChange();
            disagreeing.put("accountId", "00000000099");

            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onAccountStateChanged(disagreeing, ACCOUNT_ID, acknowledgment,
                            TOPIC),
                    "the key names the aggregate and the payload names another account");

            verifyNoInteractions(accountBalances);
        }

        /** A refusal is counted, so a mis-keyed producer is visible rather than silent. */
        @Test
        @DisplayName("a refusal counts one consumed event and one process failure")
        void aRefusalCountsOneFailure() {
            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onAccountStateChanged(aChange(), "00000000099",
                            acknowledgment, TOPIC));

            assertEquals(1.0d, registry.get("carddemo.ledger.events.consumed").counter().count(),
                    "the delivery was read");
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage",
                            LedgerMeters.POST_STAGE),
                    "and its refusal is counted, so a mis-keyed producer is visible");
        }

        /** The correctly keyed delivery still applies, so the guard refuses nothing it should not. */
        @Test
        @DisplayName("the key the fixture carries still applies the change")
        void theMatchingKeyStillApplies() {
            consumer.onAccountStateChanged(aChange(), ACCOUNT_ID, acknowledgment, TOPIC);

            verify(acknowledgment, times(1)).acknowledge();
        }
    }
}
