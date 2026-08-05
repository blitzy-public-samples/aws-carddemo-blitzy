package com.carddemo.ledger.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.ledger.config.ObservabilityConfig;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.domain.PostingService;
import com.carddemo.ledger.domain.RejectRecorder;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Direct tests for {@link TransactionAuthorizedConsumer}, the ingress that replaces a job step.
 *
 * <p>The subject stands in for {@code app/jcl/POSTTRAN.jcl:L23}, which names program
 * {@code CBTRN02C}, and for the sequential feed {@code :L30-L31} allocates. Two source facts decide
 * every assertion below.
 *
 * <p>First, the order of the two decisions. {@code 1500-B-LOOKUP-ACCT} reads the account record at
 * {@code app/cbl/CBTRN02C.cbl:L395} and assigns reject reason {@code 0101} on an invalid key at
 * {@code :L397-L399}. Only a record that read cleanly reaches {@code 2000-POST-TRANSACTION} at
 * {@code :L424}. A missing balance row must therefore reach the reject path and never the posting
 * path.
 *
 * <p>Second, a reject is expected traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} moves 4 into
 * {@code RETURN-CODE} when the reject count is positive and raises no abend, so a rejected delivery
 * acknowledges and counts under an outcome rather than under a failure.
 *
 * <p>Duplicate handling is ADDITIVE, and the source proves the need: a replayed feed reaches
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code :L562-L579}, hits a duplicate key and abends. The
 * claim is one insert, so the tests below drive it by its return value rather than by a prior read.
 *
 * <p>Every test runs in memory. None opens a database connection, and none contacts a broker.
 */
final class TransactionAuthorizedConsumerTest {

    /** Account of row 7 of {@code app/data/ASCII/acctdata.txt}, eleven digits with zeros held. */
    private static final String ACCOUNT_ID = "00000000007";

    /** Transaction identifier width from {@code TRAN-ID PIC X(16)}. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** The card number as it arrives: twelve mask characters, then four digits. */
    private static final String MASKED_CARD_NUMBER = "************5740";

    /**
     * The card token every fixture event carries: {@value com.carddemo.cobol.PanMasker#CARD_TOKEN_LENGTH}
     * lower-case hexadecimal characters, the rendering {@code PanMasker.cardToken} produces.
     */
    private static final String CARD_TOKEN =
            com.carddemo.cobol.PanMasker.cardToken("4859452612877065");

    /** The origin timestamp layout all 300 records of the daily feed carry. */
    private static final String AUTHORIZED_AT = "2022-07-19 23:16:01.470000";

    /** An amount at the scale {@code TRAN-AMT PIC S9(09)V99} declares. */
    private static final BigDecimal AMOUNT = new BigDecimal("38.72");

    /** The topic name a delivery reports, and the value the marker records. */
    private static final String TOPIC = "transaction.authorized";

    private PostingService postingService;
    private RejectRecorder rejectRecorder;
    private AccountBalanceProjectionRepository accountBalances;
    private ProcessedEventRepository processedEvents;
    private MeterRegistry registry;
    private LedgerMeters meters;
    private Acknowledgment acknowledgment;
    private TransactionAuthorizedConsumer consumer;

    /**
     * Builds the subject over stubbed collaborators and a real meter registry.
     *
     * <p>The self-provider returns the subject itself, so the listener reaches the apply method
     * directly. That is what the framework's proxy does at runtime, minus the transaction
     * interceptor, which no unit test can supply and none needs: each assertion below observes the
     * calls, not the commit.
     */
    @BeforeEach
    void buildSubject() {
        postingService = mock(PostingService.class);
        rejectRecorder = mock(RejectRecorder.class);
        accountBalances = mock(AccountBalanceProjectionRepository.class);
        processedEvents = mock(ProcessedEventRepository.class);
        registry = new SimpleMeterRegistry();
        meters = new ObservabilityConfig().ledgerMeters(registry);
        acknowledgment = mock(Acknowledgment.class);
        consumer = new TransactionAuthorizedConsumer(postingService, rejectRecorder, accountBalances,
                processedEvents, meters, selfProviderReturningSubject());
    }

    /**
     * Supplies a provider whose {@code getObject} answers with the consumer under test.
     *
     * @return the provider the constructor takes
     */
    private ObjectProvider<TransactionAuthorizedConsumer> selfProviderReturningSubject() {
        return new ObjectProvider<>() {
            @Override
            public TransactionAuthorizedConsumer getObject() {
                return consumer;
            }

            @Override
            public TransactionAuthorizedConsumer getObject(Object... args) {
                return consumer;
            }

            @Override
            public TransactionAuthorizedConsumer getIfAvailable() {
                return consumer;
            }

            @Override
            public TransactionAuthorizedConsumer getIfUnique() {
                return consumer;
            }
        };
    }

    /**
     * Builds one authorized event carrying the fixture values.
     *
     * @return the event a delivery carries
     */
    private static TransactionAuthorized anEvent() {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", AMOUNT, "800000000", "Abshire-Lowe", "North Enoshaven",
                "72112", MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT);
    }

    /**
     * Wraps one event in a delivery from the authorized topic.
     *
     * @param event the event the delivery carries, or {@code null} for a delivery with no payload
     * @return the delivery the listener reads
     */
    private static ConsumerRecord<String, TransactionAuthorized> aDelivery(
            TransactionAuthorized event) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, ACCOUNT_ID, event);
    }

    /** Reports the value of one counter series. */
    private double counter(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    /** Answers the claim with {@code 1}, so this delivery is the first to hold the identifier. */
    private void claimSucceeds() {
        when(processedEvents.claimEvent(any(UUID.class), any(Instant.class), anyString()))
                .thenReturn(1);
    }

    /** Stores a balance row for the account, so the posting path is the one that runs. */
    private void balanceRowExists() {
        when(accountBalances.findById(ACCOUNT_ID)).thenReturn(Optional
                .of(new AccountBalanceProjectionEntity(ACCOUNT_ID, new BigDecimal("193.00"),
                        new BigDecimal("0.00"), new BigDecimal("0.00"))));
    }

    @Nested
    @DisplayName("The posting path, app/cbl/CBTRN02C.cbl:L424-L444")
    class PostingPath {

        @Test
        @DisplayName("one delivery with a balance row posts once and acknowledges once")
        void oneDeliveryPostsAndAcknowledges() {
            claimSucceeds();
            balanceRowExists();
            TransactionAuthorized event = anEvent();

            consumer.onTransactionAuthorized(aDelivery(event), acknowledgment);

            verify(postingService, times(1)).postTransaction(event, ACCOUNT_ID);
            verifyNoInteractions(rejectRecorder);
            verify(acknowledgment, times(1)).acknowledge();
        }

        @Test
        @DisplayName("the event travels to the posting service unchanged")
        void theEventTravelsUnchanged() {
            claimSucceeds();
            balanceRowExists();
            TransactionAuthorized event = anEvent();

            TransactionAuthorizedConsumer.Outcome outcome = consumer.applyOneEvent(event, ACCOUNT_ID, TOPIC);

            assertEquals(TransactionAuthorizedConsumer.Outcome.POSTED, outcome,
                    "a delivery whose account holds a balance row posts");
            verify(postingService).postTransaction(assertSameEvent(event), ACCOUNT_ID);
        }

        /**
         * Matches the one event the caller passed, by identity.
         *
         * @param expected the event the delivery carried
         * @return the same reference, so Mockito compares by identity
         */
        private TransactionAuthorized assertSameEvent(TransactionAuthorized expected) {
            assertNotNull(expected, "the test must supply an event");
            return expected;
        }

        @Test
        @DisplayName("a posting counts one consumed event and one posted outcome")
        void aPostingCountsItsOutcome() {
            claimSucceeds();
            balanceRowExists();

            consumer.onTransactionAuthorized(aDelivery(anEvent()), acknowledgment);

            assertEquals(1.0d, registry.get("carddemo.ledger.events.consumed").counter().count(),
                    "one delivery is one consumed event");
            assertEquals(1.0d, counter("carddemo.ledger.transactions.processed", "outcome",
                    "posted"), "WS-TRANSACTION-COUNT at app/cbl/CBTRN02C.cbl:L185 counts a posting");
            assertEquals(0.0d, counter("carddemo.ledger.transactions.processed", "outcome",
                    "rejected"), "a posting is not a reject");
            assertTrue(registry.get("carddemo.ledger.processing.latency").timer().count() == 1L,
                    "the latency timer records one observation per delivery");
        }
    }

    @Nested
    @DisplayName("The reject path, app/cbl/CBTRN02C.cbl:L395-L399 then L446-L465")
    class RejectPath {

        @Test
        @DisplayName("an absent balance row rejects under reason 0101 and never posts")
        void anAbsentBalanceRowRejects() {
            claimSucceeds();
            when(accountBalances.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
            TransactionAuthorized event = anEvent();

            TransactionAuthorizedConsumer.Outcome outcome = consumer.applyOneEvent(event, ACCOUNT_ID, TOPIC);

            assertEquals(TransactionAuthorizedConsumer.Outcome.REJECTED, outcome,
                    "the account read of app/cbl/CBTRN02C.cbl:L395 decides before the post");
            verify(rejectRecorder).recordReject(event, DeclineReason.ACCOUNT_NOT_FOUND);
            verifyNoInteractions(postingService);
        }

        @Test
        @DisplayName("the reject reason carries the verbatim text of app/cbl/CBTRN02C.cbl:L398")
        void theReasonCarriesItsSourceText() {
            assertEquals("0101", DeclineReason.ACCOUNT_NOT_FOUND.code(),
                    "reject reason 0101 is assigned at app/cbl/CBTRN02C.cbl:L397");
            assertEquals("ACCOUNT RECORD NOT FOUND", DeclineReason.ACCOUNT_NOT_FOUND.description(),
                    "the text is moved at app/cbl/CBTRN02C.cbl:L398");
        }

        @Test
        @DisplayName("a reject acknowledges and counts as an outcome, not as a failure")
        void aRejectIsExpectedTraffic() {
            claimSucceeds();
            when(accountBalances.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            consumer.onTransactionAuthorized(aDelivery(anEvent()), acknowledgment);

            verify(acknowledgment, times(1)).acknowledge();
            assertEquals(1.0d, counter("carddemo.ledger.transactions.processed", "outcome",
                    "rejected"), "WS-REJECT-COUNT at app/cbl/CBTRN02C.cbl:L186 counts a reject");
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "app/cbl/CBTRN02C.cbl:L229-L230 answers a reject with return code 4 and no"
                            + " abend, so a reject is not a failure");
        }
    }

    @Nested
    @DisplayName("Duplicate delivery, the gap at app/cbl/CBTRN02C.cbl:L562-L579")
    class DuplicateDelivery {

        @Test
        @DisplayName("a claimed identifier leaves every table untouched and still acknowledges")
        void aClaimedIdentifierAppliesNothing() {
            when(processedEvents.claimEvent(any(UUID.class), any(Instant.class), anyString()))
                    .thenReturn(0);

            consumer.onTransactionAuthorized(aDelivery(anEvent()), acknowledgment);

            verifyNoInteractions(postingService);
            verifyNoInteractions(rejectRecorder);
            verifyNoInteractions(accountBalances);
            verify(acknowledgment, times(1)).acknowledge();
            assertEquals(1.0d, counter("carddemo.ledger.transactions.processed", "outcome",
                    "duplicate"), "a duplicate delivery counts under its own outcome");
        }

        @Test
        @DisplayName("the claim runs before the balance read, so no read precedes the guard")
        void theClaimRunsFirst() {
            when(processedEvents.claimEvent(any(UUID.class), any(Instant.class), anyString()))
                    .thenReturn(0);

            assertEquals(TransactionAuthorizedConsumer.Outcome.DUPLICATE,
                    consumer.applyOneEvent(anEvent(), ACCOUNT_ID, TOPIC),
                    "the claim decides the outcome on its own");
            verify(processedEvents, times(1)).claimEvent(any(UUID.class), any(Instant.class),
                    eq(TOPIC));
            verifyNoInteractions(accountBalances);
        }

        @Test
        @DisplayName("the marker records the topic the delivery arrived on")
        void theMarkerRecordsTheTopic() {
            claimSucceeds();
            balanceRowExists();

            consumer.applyOneEvent(anEvent(), ACCOUNT_ID, TOPIC);

            verify(processedEvents).claimEvent(any(UUID.class), any(Instant.class), eq(TOPIC));
        }
    }

    @Nested
    @DisplayName("Failure handling and acknowledgement")
    class FailureHandling {

        @Test
        @DisplayName("a posting fault leaves the offset uncommitted and counts one failure")
        void aPostingFaultRefusesTheAcknowledgement() {
            claimSucceeds();
            balanceRowExists();
            doThrow(new IllegalStateException("store unavailable")).when(postingService)
                    .postTransaction(any(TransactionAuthorized.class), eq(ACCOUNT_ID));

            assertThrows(IllegalStateException.class,
                    () -> consumer.onTransactionAuthorized(aDelivery(anEvent()), acknowledgment),
                    "a fault must reach the container, which decides between retry and dead letter");
            verify(acknowledgment, never()).acknowledge();
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "a store fault counts under the process stage");
        }

        @Test
        @DisplayName("a delivery with no payload counts a deserialize failure and applies nothing")
        void aDeliveryWithNoPayloadIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onTransactionAuthorized(aDelivery(null), acknowledgment),
                    "a delivery carrying no payload names no transaction to apply");
            verify(acknowledgment, never()).acknowledge();
            verifyNoInteractions(postingService);
            verifyNoInteractions(processedEvents);
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "deserialize"),
                    "a payload the deserializer could not supply counts under its own stage");
        }

        @Test
        @DisplayName("the latency timer records even when the delivery failed")
        void theTimerRecordsAFailedDelivery() {
            claimSucceeds();
            balanceRowExists();
            doThrow(new IllegalStateException("store unavailable")).when(postingService)
                    .postTransaction(any(TransactionAuthorized.class), eq(ACCOUNT_ID));

            assertThrows(IllegalStateException.class,
                    () -> consumer.onTransactionAuthorized(aDelivery(anEvent()), acknowledgment));

            assertEquals(1L, registry.get("carddemo.ledger.processing.latency").timer().count(),
                    "the timer is stopped in a finally block, so a failure is measured too");
        }

        @Test
        @DisplayName("neither argument may be absent")
        void neitherArgumentMayBeAbsent() {
            assertThrows(NullPointerException.class,
                    () -> consumer.onTransactionAuthorized(null, acknowledgment),
                    "a listener with no delivery has nothing to read");
            assertThrows(NullPointerException.class,
                    () -> consumer.onTransactionAuthorized(aDelivery(anEvent()), null),
                    "a listener with no acknowledgement could never commit its offset");
            assertThrows(NullPointerException.class, () -> consumer.applyOneEvent(null, ACCOUNT_ID, TOPIC),
                    "the apply method requires an event");
        }
    }

    @Nested
    @DisplayName("The meter families a demonstration reads")
    class MeterFamilies {

        @Test
        @DisplayName("all three required families register before the first message")
        void allThreeFamiliesRegisterAtStartUp() {
            assertNotNull(registry.find("carddemo.ledger.events.consumed").counter(),
                    "events consumed is one of the three families the platform requires");
            assertNotNull(registry.find("carddemo.ledger.processing.latency").timer(),
                    "processing latency is the second");
            assertNotNull(registry.find("carddemo.ledger.failures").tag("stage", "process")
                            .counter(),
                    "failure count is the third");
            assertEquals(0.0d, registry.get("carddemo.ledger.events.consumed").counter().count(),
                    "a scrape taken before the first message reports zero rather than nothing");
        }

        @Test
        @DisplayName("the two source counters and the additive one are distinct series")
        void theOutcomeSeriesAreDistinct() {
            meters.recordTransactionPosted();
            meters.recordTransactionRejected();
            meters.recordDuplicateSkipped();

            assertEquals(1.0d,
                    counter("carddemo.ledger.transactions.processed", "outcome", "posted"));
            assertEquals(1.0d,
                    counter("carddemo.ledger.transactions.processed", "outcome", "rejected"));
            assertEquals(1.0d,
                    counter("carddemo.ledger.transactions.processed", "outcome", "duplicate"));
        }

        @Test
        @DisplayName("the four failure stages are distinct series under one name")
        void theFailureStagesAreDistinct() {
            meters.recordDeserializeFailure();
            meters.recordProcessFailure();
            meters.recordPublishFailure();
            meters.recordAbandonedRow();

            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "deserialize"));
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "process"));
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "publish"));
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "abandon"));
        }

        @Test
        @DisplayName("no meter name and no tag value carries an identifier")
        void noMeterCarriesAnIdentifier() {
            claimSucceeds();
            balanceRowExists();
            consumer.onTransactionAuthorized(aDelivery(anEvent()), acknowledgment);

            registry.getMeters().forEach(meter -> {
                String rendered = meter.getId().getName() + " "
                        + meter.getId().getTags().toString();
                assertTrue(!rendered.contains(ACCOUNT_ID) && !rendered.contains(TRANSACTION_ID)
                                && !rendered.contains(MASKED_CARD_NUMBER),
                        () -> "meter " + rendered + " must carry no identifier, so the series count"
                                + " stays fixed however much traffic arrives");
            });
        }
    }
}
