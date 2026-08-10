package com.carddemo.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.domain.CardholderContextReader;
import com.carddemo.notification.entity.CardholderContextEntity;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.repository.CardholderContextRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the order the three listeners of this service apply one delivery in.
 *
 * <p>This service replaces only the cardholder-facing tail of statement generation. The batch
 * program assembled a statement once per cycle from a sorted copy of the transaction file
 * [{@code app/jcl/CREASTMT.JCL}], so it had no notion of one delivery and no duplicate detection.
 * The claim asserted here is the addition that makes a redelivery harmless.
 *
 * <p>Three listeners read three topics, and each is checked for the same four properties: the claim
 * reaches {@code processed_event} before any effect, a claim already held leaves every table
 * untouched, the acknowledgement follows the transactional unit, and a failure leaves the offset
 * uncommitted.
 *
 * <p>A {@link TransactionTemplate} built over a replaced transaction manager runs its callback and
 * commits nothing. {@code entity/NotificationEntityPersistenceTest} owns the runtime proof that the
 * statements reach their tables.
 */
@DisplayName("The three notification listeners: guard, apply, mark, acknowledge")
class NotificationConsumerTest {

    /** The count a claim returns when this delivery is a redelivery. */
    private static final int ALREADY_CLAIMED = 0;

    /** The count a claim returns when this delivery took the claim. */
    private static final int CLAIM_TAKEN = 1;

    /** The card this service keys its read model by, tokenized. */
    private static final String CARD_TOKEN = PanMasker.tokenOf("4859452612877065");

    /** The account every delivery below names. */
    private static final String ACCOUNT_ID = "00000000007";

    /** The transaction every delivery below names, from {@code TRAN-ID PIC X(16)}. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /**
     * The posting stamp, in the form {@code TransactionPosted.POSTED_AT_PATTERN} requires.
     *
     * <p>Dashes separate every date and time part and the last four fractional digits are zero,
     * reproducing the hard-coded remainder at {@code app/cbl/CBTRN02C.cbl:L701}.
     */
    private static final String POSTED_AT = "2022-06-10-19.27.54.120000";

    /** The instant both assessment fixtures below carry. */
    private static final Instant ASSESSED_AT = Instant.parse("2026-02-01T10:15:31Z");

    /** Reads the text-block fixtures below into the tree a listener receives. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ProcessedEventRepository processedEvents;
    private CardholderContextRepository cardholderContexts;
    private Acknowledgment acknowledgment;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void buildCollaborators() {
        processedEvents = mock(ProcessedEventRepository.class);
        cardholderContexts = mock(CardholderContextRepository.class);
        // A present row is the realistic state: db/migration/V2__seed.sql bootstraps the projection
        // for every account of the customer fixture. A missing row is reported as a fault now rather
        // than rendered as blank cardholder fields, so leaving this unstubbed would assert against a
        // failure path in tests that are about ordering and idempotency.
        lenient().when(cardholderContexts.findById(anyString()))
                .thenReturn(Optional.of(seededContext(ACCOUNT_ID)));
        acknowledgment = mock(Acknowledgment.class);
        transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
    }

    @Nested
    @DisplayName("TransactionPostedConsumer, which builds the card-keyed read model")
    class PostedTransactions {

        private StatementTransactionRepository statementTransactions;
        private NotificationService notificationService;
        private TransactionPostedConsumer consumer;

        @BeforeEach
        void buildConsumer() {
            statementTransactions = mock(StatementTransactionRepository.class);
            notificationService = mock(NotificationService.class);
            consumer = new TransactionPostedConsumer(statementTransactions, processedEvents,
                    new CardholderContextReader(cardholderContexts), notificationService,
                    transactionTemplate,
                    new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()));
        }

        @Test
        @DisplayName("the claim reaches processed_event before the read-model write")
        void theClaimRunsBeforeTheReadModelWrite() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIM_TAKEN);

            consumer.onTransactionPosted(posted(), acknowledgment, "transaction.posted", ACCOUNT_ID);

            InOrder order = inOrder(processedEvents, statementTransactions, acknowledgment);
            order.verify(processedEvents).claimEvent(any(), any(), eq("transaction.posted"));
            // One upsert statement, inside the same transaction as the claim above it.
            order.verify(statementTransactions).upsertRow(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                    anyString(), anyString(), anyString(), anyString(), anyString());
            order.verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("a redelivery writes nothing and still acknowledges")
        void aRedeliveryWritesNothing() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(ALREADY_CLAIMED);

            consumer.onTransactionPosted(posted(), acknowledgment, "transaction.posted", ACCOUNT_ID);

            verifyNoInteractions(statementTransactions);
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("a failing write leaves the offset uncommitted")
        void aFailingWriteLeavesTheOffsetUncommitted() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIM_TAKEN);
            // The write is one upsert statement now, not a read followed by a merge, so the fault is
            // injected where the row is actually stored.
            when(statementTransactions.upsertRow(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), any(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new DataAccessResourceFailureException("read model unreachable"));

            assertThrows(DataAccessResourceFailureException.class, () ->
                    consumer.onTransactionPosted(posted(), acknowledgment, "transaction.posted", ACCOUNT_ID));

            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        @DisplayName("the listener resolves its topic and its own group through configuration")
        void theListenerResolvesItsSubscriptionThroughConfiguration() throws NoSuchMethodException {
            KafkaListener subscription = TransactionPostedConsumer.class
                    .getMethod("onTransactionPosted", TransactionPosted.class,
                            Acknowledgment.class, String.class, String.class)
                    .getAnnotation(KafkaListener.class);

            assertThat(subscription).isNotNull();
            assertThat(subscription.topics())
                    .containsExactly("${carddemo.kafka.topics.transaction-posted}");
            assertThat(subscription.groupId())
                    .isEqualTo("${carddemo.kafka.groups.transaction-posted}");
        }
    }

    @Nested
    @DisplayName("FraudFlaggedConsumer, the second independent reader of a second event")
    class FraudAssessments {

        private NotificationService notificationService;
        private FraudFlaggedConsumer consumer;

        @BeforeEach
        void buildConsumer() {
            notificationService = mock(NotificationService.class);
            consumer = new FraudFlaggedConsumer(new CardholderContextReader(cardholderContexts),
                    processedEvents, notificationService, transactionTemplate,
                    new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()));
        }

        @Test
        @DisplayName("the claim reaches processed_event before the alert is rendered")
        void theClaimRunsBeforeTheAlert() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIM_TAKEN);

            consumer.onFraudAssessed(flagged(), ACCOUNT_ID, acknowledgment, "fraud.assessed");

            InOrder order = inOrder(processedEvents, notificationService, acknowledgment);
            order.verify(processedEvents).claimEvent(any(), any(), eq("fraud.assessed"));
            order.verify(notificationService).renderFraudAlert(eq(TRANSACTION_ID), eq(ACCOUNT_ID),
                    anyInt(), any(), any(), any());
            order.verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("a redelivery renders nothing and still acknowledges")
        void aRedeliveryRendersNothing() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(ALREADY_CLAIMED);

            consumer.onFraudAssessed(flagged(), ACCOUNT_ID, acknowledgment, "fraud.assessed");

            verifyNoInteractions(notificationService);
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("a cleared assessment claims its event and renders no alert")
        void aClearedAssessmentRendersNoAlert() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIM_TAKEN);

            consumer.onFraudAssessed(cleared(), ACCOUNT_ID, acknowledgment, "fraud.assessed");

            verify(processedEvents).claimEvent(any(), any(), eq("fraud.assessed"));
            verifyNoInteractions(notificationService);
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("a payload that is neither outcome is refused and leaves the offset")
        void aPayloadThatIsNeitherOutcomeIsRefused() {
            assertThrows(IllegalArgumentException.class, () ->
                    consumer.onFraudAssessed(new NotAnAssessment("not an assessment"), ACCOUNT_ID,
                            acknowledgment,
                            "fraud.assessed"));

            verifyNoInteractions(processedEvents);
            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        @DisplayName("a failing alert leaves the offset uncommitted")
        void aFailingAlertLeavesTheOffsetUncommitted() {
            when(processedEvents.claimEvent(any(), any(), anyString()))
                    .thenThrow(new DataAccessResourceFailureException("marker table unreachable"));

            assertThrows(DataAccessResourceFailureException.class, () ->
                    consumer.onFraudAssessed(flagged(), ACCOUNT_ID,
                            acknowledgment, "fraud.assessed"));

            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        @DisplayName("the listener holds a group of its own, so its progress is independent")
        void theListenerHoldsAGroupOfItsOwn() throws NoSuchMethodException {
            KafkaListener subscription = FraudFlaggedConsumer.class
                    .getMethod("onFraudAssessed", Record.class, String.class,
                            Acknowledgment.class, String.class)
                    .getAnnotation(KafkaListener.class);

            assertThat(subscription).isNotNull();
            assertThat(subscription.topics()).containsExactly("${carddemo.kafka.topics.fraud-assessed}");
            assertThat(subscription.groupId())
                    .isEqualTo("${carddemo.kafka.groups.fraud-assessed}");
        }
    }

    @Nested
    @DisplayName("CustomerContextChangedConsumer, which keeps the cardholder projection current")
    class CardholderContext {

        private CustomerContextChangedConsumer consumer;

        @BeforeEach
        void buildConsumer() {
            consumer = new CustomerContextChangedConsumer(cardholderContexts, processedEvents,
                    transactionTemplate,
                    new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()));
        }

        @Test
        @DisplayName("the claim reaches processed_event before the projection write")
        void theClaimRunsBeforeTheProjectionWrite() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIM_TAKEN);
            when(cardholderContexts.applyContextChange(anyString(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

            consumer.onCustomerContextChanged(contextChanged(), ACCOUNT_ID, acknowledgment,
                    "customer.context-changed");

            InOrder order = inOrder(processedEvents, cardholderContexts, acknowledgment);
            order.verify(processedEvents).claimEvent(any(), any(), eq("customer.context-changed"));
            order.verify(cardholderContexts).applyContextChange(eq(ACCOUNT_ID), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
            order.verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("a redelivery writes nothing and still acknowledges")
        void aRedeliveryWritesNothing() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(ALREADY_CLAIMED);

            consumer.onCustomerContextChanged(contextChanged(), ACCOUNT_ID, acknowledgment,
                    "customer.context-changed");

            verify(cardholderContexts, never()).applyContextChange(anyString(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("a stale change writing no row still acknowledges, keeping its claim")
        void aStaleChangeWritingNoRowStillAcknowledges() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIM_TAKEN);
            when(cardholderContexts.applyContextChange(anyString(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

            consumer.onCustomerContextChanged(contextChanged(), ACCOUNT_ID, acknowledgment,
                    "customer.context-changed");

            verify(acknowledgment)
                    .acknowledge();
        }

        @Test
        @DisplayName("a failing projection write leaves the offset uncommitted")
        void aFailingProjectionWriteLeavesTheOffsetUncommitted() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIM_TAKEN);
            when(cardholderContexts.applyContextChange(anyString(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new DataAccessResourceFailureException("projection unreachable"));

            assertThrows(DataAccessResourceFailureException.class, () ->
                    consumer.onCustomerContextChanged(contextChanged(), ACCOUNT_ID, acknowledgment,
                            "customer.context-changed"));

            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        @DisplayName("the listener holds a third group, so three readers advance independently")
        void theListenerHoldsAThirdGroup() throws NoSuchMethodException {
            KafkaListener subscription = CustomerContextChangedConsumer.class
                    .getMethod("onCustomerContextChanged", JsonNode.class, String.class,
                            Acknowledgment.class, String.class)
                    .getAnnotation(KafkaListener.class);

            assertThat(subscription).isNotNull();
            assertThat(subscription.topics())
                    .containsExactly("${carddemo.kafka.topics.customer-context-changed}");
            assertThat(subscription.groupId())
                    .isEqualTo("${carddemo.kafka.groups.customer-context-changed}");
        }
    }

    /**
     * Builds one posted transaction carrying record 1 of {@code app/data/ASCII/dailytran.txt}.
     *
     * @return the event
     */
    private static TransactionPosted posted() {
        TransactionAuthorized authorized = TransactionAuthorized.of(ACCOUNT_ID,
                TRANSACTION_ID, "01", "0001", "POS TERM", "Purchase at Abshire-Lowe",
                new BigDecimal("38.72"), "800000000", "Abshire-Lowe", "North Enoshaven", "72112",
                "************7065", CARD_TOKEN, "2022-06-10 19:27:53.000000");
        return TransactionPosted.forAuthorized(authorized, new BigDecimal("1038.72"), POSTED_AT);
    }

    /**
     * Builds one flagged assessment, the outcome that carries an alert.
     *
     * @return the event
     */
    private static FraudFlagged flagged() {
        return FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, 72,
                List.of("VELOCITY", "AMOUNT_ANOMALY"), ASSESSED_AT);
    }

    /**
     * Builds one cleared assessment, the outcome that carries no alert.
     *
     * @return the event
     */
    private static FraudCleared cleared() {
        return FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, ASSESSED_AT);
    }

    /**
     * Builds one cardholder-context change as the flat tree a topic carries.
     *
     * @return the tree
     */
    private static JsonNode contextChanged() {
        return MAPPER.readTree("""
                {
                  "eventId": "4f8a1b74-5c6d-4e3f-8a2b-7c4d5e6f7a91",
                  "eventType": "CustomerContextChanged",
                  "schemaVersion": 1,
                  "occurredAt": "2026-02-01T10:16:00Z",
                  "aggregateId": "00000000007",
                  "accountId": "00000000007",
                  "firstName": "Lucious",
                  "middleName": "R",
                  "lastName": "O'Connell",
                  "addressLine1": "1 Market Street",
                  "addressLine2": "Suite 200",
                  "addressLine3": "North Enoshaven",
                  "stateCode": "AR",
                  "countryCode": "USA",
                  "zipCode": "72112",
                  "ficoScore": "0712"
                }
                """);
    }

    /**
     * A record that is neither assessment outcome, so the listener's default branch is reachable.
     *
     * <p>The listener parameter is {@code java.lang.Record} and not {@code Object}, because an
     * {@code Object} parameter matches the {@code ConsumerRecord} the container supplies before
     * payload resolution runs. A plain {@code String} therefore no longer compiles here.
     *
     * @param value any text
     */
    private record NotAnAssessment(String value) {
    }

    /**
     * One populated cardholder projection row, as {@code db/migration/V2__seed.sql} loads it.
     *
     * @param accountId the account the row is keyed by
     * @return the row, with every field carrying a value
     */
    private static CardholderContextEntity seededContext(String accountId) {
        return new CardholderContextEntity(accountId, "Immanuel", "Madeline", "Kessler",
                "618 Deshaun Route", "Apt. 802", "Altenwerthshire", "NC", "USA", "12546", "274",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH);
    }

    @Nested
    @DisplayName("The message key has to name the account each payload names")
    class MessageKeyGuard {

        private NotificationService notificationService;
        private FraudFlaggedConsumer fraudConsumer;
        private CustomerContextChangedConsumer contextConsumer;

        @BeforeEach
        void buildConsumers() {
            notificationService = mock(NotificationService.class);
            fraudConsumer = new FraudFlaggedConsumer(new CardholderContextReader(
                    cardholderContexts), processedEvents, notificationService, transactionTemplate,
                    new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()));
            contextConsumer = new CustomerContextChangedConsumer(cardholderContexts,
                    processedEvents, transactionTemplate,
                    new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()));
        }

        /**
         * An assessment applied under another account's key would address one cardholder's fraud
         * alert with another cardholder's stored name and address, because the account this payload
         * names is the account the alert is addressed from.
         */
        @Test
        @DisplayName("a flagged assessment keyed on another account renders nothing")
        void aFlaggedAssessmentKeyedOnAnotherAccountRendersNothing() {
            assertThrows(IllegalArgumentException.class,
                    () -> fraudConsumer.onFraudAssessed(flagged(), "00000000099", acknowledgment,
                            "fraud.assessed"));

            verifyNoInteractions(notificationService);
            verifyNoInteractions(processedEvents);
            verifyNoInteractions(acknowledgment);
        }

        /** A cleared assessment renders nothing but still claims, so it is checked as well. */
        @Test
        @DisplayName("a cleared assessment keyed on another account claims nothing")
        void aClearedAssessmentKeyedOnAnotherAccountClaimsNothing() {
            assertThrows(IllegalArgumentException.class,
                    () -> fraudConsumer.onFraudAssessed(cleared(), "00000000099", acknowledgment,
                            "fraud.assessed"));

            verifyNoInteractions(processedEvents);
            verifyNoInteractions(acknowledgment);
        }

        /**
         * The context row supplies the name and address every rendered alert carries, so a change
         * applied under another account's key would rewrite the wrong cardholder's details.
         */
        @Test
        @DisplayName("a context change keyed on another account writes nothing")
        void aContextChangeKeyedOnAnotherAccountWritesNothing() {
            assertThrows(IllegalArgumentException.class,
                    () -> contextConsumer.onCustomerContextChanged(contextChanged(), "00000000099",
                            acknowledgment, "customer.context-changed"));

            verifyNoInteractions(cardholderContexts);
            verifyNoInteractions(processedEvents);
            verifyNoInteractions(acknowledgment);
        }

        /** A record with no key at all was partitioned at random. */
        @Test
        @DisplayName("a record carrying no key is refused on both streams")
        void aRecordCarryingNoKeyIsRefusedOnBothStreams() {
            assertThrows(IllegalArgumentException.class,
                    () -> fraudConsumer.onFraudAssessed(flagged(), null, acknowledgment,
                            "fraud.assessed"));
            assertThrows(IllegalArgumentException.class,
                    () -> contextConsumer.onCustomerContextChanged(contextChanged(), null,
                            acknowledgment, "customer.context-changed"));

            verifyNoInteractions(notificationService);
            verifyNoInteractions(cardholderContexts);
            verifyNoInteractions(acknowledgment);
        }

        /** A blank key names nothing, so it is refused for the same reason as an absent one. */
        @Test
        @DisplayName("a blank key is refused on both streams")
        void aBlankKeyIsRefusedOnBothStreams() {
            assertThrows(IllegalArgumentException.class,
                    () -> fraudConsumer.onFraudAssessed(flagged(), " ", acknowledgment,
                            "fraud.assessed"));
            assertThrows(IllegalArgumentException.class,
                    () -> contextConsumer.onCustomerContextChanged(contextChanged(), " ",
                            acknowledgment, "customer.context-changed"));

            verifyNoInteractions(notificationService);
            verifyNoInteractions(cardholderContexts);
        }

        /**
         * The correctly keyed deliveries still apply, so the guard refuses nothing it should
         * not.
         */
        @Test
        @DisplayName("the key each fixture carries still applies its delivery")
        void theMatchingKeyStillApplies() {
            when(processedEvents.claimEvent(any(), any(), anyString())).thenReturn(CLAIM_TAKEN);
            when(cardholderContexts.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(seededContext(ACCOUNT_ID)));
            when(cardholderContexts.applyContextChange(anyString(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

            fraudConsumer.onFraudAssessed(flagged(), ACCOUNT_ID, acknowledgment, "fraud.assessed");
            contextConsumer.onCustomerContextChanged(contextChanged(), ACCOUNT_ID, acknowledgment,
                    "customer.context-changed");

            verify(acknowledgment, org.mockito.Mockito.times(2)).acknowledge();
        }
    }
}
