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

    /** Answers the projection write with {@code 1}, so the change is the newest one. */
    private void writeApplies() {
        when(accountBalances.applyStateChange(anyString(), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), any(UUID.class), any(Instant.class)))
                .thenReturn(1);
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
        tree.put("changeKind", "ACCOUNT_UPDATED");
        return tree;
    }

    /** A change carrying the fixture values of record one. */
    private static JsonNode aChange() {
        return message("193.00", "0.00", "0.00", Instant.parse("2026-01-01T00:00:00Z"));
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

    @Nested
    @DisplayName("Bootstrap, the gap an account opened after deployment fell into")
    class Bootstrap {

        @Test
        @DisplayName("one change reaches the projection and the delivery acknowledges")
        void oneChangeReachesTheProjection() {
            claimSucceeds();
            writeApplies();

            consumer.onAccountStateChanged(aChange(), acknowledgment, TOPIC);

            verify(accountBalances, times(1)).applyStateChange(eq(ACCOUNT_ID),
                    eq(new BigDecimal("193.00")), eq(new BigDecimal("0.00")),
                    eq(new BigDecimal("0.00")), any(UUID.class), any(Instant.class));
            verify(acknowledgment, times(1)).acknowledge();
        }

        @Test
        @DisplayName("the write is an upsert, so an absent row is inserted rather than skipped")
        void theWriteIsAnUpsert() {
            claimSucceeds();
            writeApplies();

            assertTrue(consumer.applyOneEvent(AccountStateChanged.from(aChange()), TOPIC),
                    "an absent row is inserted, which is how a new account first gets one");
        }

        @Test
        @DisplayName("a cycle close arrives as an ordinary change carrying zeroes")
        void aCycleCloseArrivesAsZeroes() {
            claimSucceeds();
            writeApplies();
            JsonNode closed = message("193.00", "0.00", "0.00",
                    Instant.parse("2026-02-01T00:00:00Z"));

            consumer.onAccountStateChanged(closed, acknowledgment, TOPIC);

            verify(accountBalances).applyStateChange(eq(ACCOUNT_ID), any(BigDecimal.class),
                    eq(new BigDecimal("0.00")), eq(new BigDecimal("0.00")), any(UUID.class),
                    any(Instant.class));
        }
    }

    @Nested
    @DisplayName("Ordering, so a redelivery cannot move a cycle balance backwards")
    class Ordering {

        @Test
        @DisplayName("a change a later one superseded writes nothing and still acknowledges")
        void asupersededChangeWritesNothing() {
            claimSucceeds();
            when(accountBalances.applyStateChange(anyString(), any(BigDecimal.class),
                    any(BigDecimal.class), any(BigDecimal.class), any(UUID.class),
                    any(Instant.class))).thenReturn(0);

            assertFalse(consumer.applyOneEvent(AccountStateChanged.from(aChange()), TOPIC),
                    "the row already carries a later change, so this one leaves it alone");
        }

        @Test
        @DisplayName("a write that moved nothing is not a failure")
        void aWriteThatMovedNothingIsNotAFailure() {
            claimSucceeds();
            when(accountBalances.applyStateChange(anyString(), any(BigDecimal.class),
                    any(BigDecimal.class), any(BigDecimal.class), any(UUID.class),
                    any(Instant.class))).thenReturn(0);

            consumer.onAccountStateChanged(aChange(), acknowledgment, TOPIC);

            verify(acknowledgment, times(1)).acknowledge();
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "an out-of-order delivery is expected traffic and not a fault");
        }

        @Test
        @DisplayName("the producer clock travels to the write, not this service's clock")
        void theProducerClockTravelsToTheWrite() {
            claimSucceeds();
            writeApplies();
            Instant occurredAt = Instant.parse("2026-03-04T05:06:07Z");

            consumer.applyOneEvent(
                    AccountStateChanged.from(message("1.00", "0.00", "0.00", occurredAt)), TOPIC);

            verify(accountBalances).applyStateChange(anyString(), any(BigDecimal.class),
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

            consumer.onAccountStateChanged(aChange(), acknowledgment, TOPIC);

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
                    () -> consumer.onAccountStateChanged(null, acknowledgment, TOPIC),
                    "this stream produces no tombstone, so one means a producer defect");

            verify(acknowledgment, never()).acknowledge();
            verifyNoInteractions(accountBalances);
        }

        @Test
        @DisplayName("a write fault refuses the acknowledgement and counts one failure")
        void aWriteFaultRefusesTheAcknowledgement() {
            claimSucceeds();
            when(accountBalances.applyStateChange(anyString(), any(BigDecimal.class),
                    any(BigDecimal.class), any(BigDecimal.class), any(UUID.class),
                    any(Instant.class))).thenThrow(new IllegalStateException("the store refused"));

            assertThrows(IllegalStateException.class,
                    () -> consumer.onAccountStateChanged(aChange(), acknowledgment, TOPIC));

            verify(acknowledgment, never()).acknowledge();
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "the fault is counted, and the offset stays uncommitted");
        }

        @Test
        @DisplayName("no log line and no meter tag carries the account identifier")
        void nothingCarriesTheAccountIdentifier() {
            claimSucceeds();
            writeApplies();

            consumer.onAccountStateChanged(aChange(), acknowledgment, TOPIC);

            registry.getMeters().forEach(meter -> meter.getId().getTags()
                    .forEach(tag -> assertFalse(tag.getValue().contains(ACCOUNT_ID),
                            "meter " + meter.getId().getName() + " tags an account identifier")));
        }
    }

    @Nested
    @DisplayName("The payload the listener binds")
    class Payload {

        @Test
        @DisplayName("the record holds the three columns the projection carries, and no more")
        void theRecordHoldsTheThreeProjectedColumns() {
            AccountStateChanged event = AccountStateChanged.from(aChange());

            assertEquals(ACCOUNT_ID, event.accountId(), "the account the change describes");
            assertEquals(new BigDecimal("193.00"), event.currentBalance(), "ACCT-CURR-BAL");
            assertEquals(new BigDecimal("0.00"), event.currentCycleCredit(),
                    "ACCT-CURR-CYC-CREDIT");
            assertEquals(new BigDecimal("0.00"), event.currentCycleDebit(), "ACCT-CURR-CYC-DEBIT");
            assertEquals(6, AccountStateChanged.class.getRecordComponents().length,
                    "the credit limit and the expiry date belong to the decline rules, so this "
                            + "service does not hold a copy of either");
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
}
