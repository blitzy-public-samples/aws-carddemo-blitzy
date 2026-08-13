package com.carddemo.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.CardholderContextReader;
import com.carddemo.notification.domain.CardholderContextReader.CardholderContextMissingException;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.entity.CardholderContextEntity;
import com.carddemo.notification.repository.CardholderContextRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Covers the third independent direct consumer of {@code transaction.authorized}.
 *
 * <p>AAP 0.1.1 and 0.8.3 require that authorizing a transaction produces one event which at least
 * three independent services then consume. The ledger and the fraud detector read that topic
 * directly; this service read only events those two derive from it, so the count was two. The
 * listener under test makes it three.
 *
 * <p>Independence is a property of the build and of the broker rather than of this class: no service
 * module may depend on another under AAP 0.4.2, and each of the three reads under a group of its own.
 * What is asserted here is the listener's own contract: it guards before it renders, it marks before
 * it acknowledges, and it refuses rather than renders when it cannot tell the cardholder who they
 * are.
 */
@DisplayName("TransactionAuthorizedConsumer, the third independent reader of the authorization event")
class TransactionAuthorizedConsumerTest {

    /** The account every event below names, at the width {@code ACCT-ID PIC 9(11)} declares. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The topic the listener reads, as the shipped configuration names it. */
    private static final String TOPIC = "transaction.authorized";

    /** The transaction the event carries, at the width {@code TRAN-ID PIC X(16)} declares. */
    private static final String TRANSACTION_ID = "0000000000009101";

    /** A card token of the shape the derivation produces: sixty-four lower-case hex characters. */
    private static final String CARD_TOKEN =
            "60628a15d4ee3589bede34459a1d80437b5273de072ef89536460b793c9bda07";

    /** The masked card number the event carries: twelve mask characters then four digits. */
    private static final String MASKED_CARD = "************7065";

    /** The authorized amount, at the scale {@code TRAN-AMT PIC S9(09)V99} declares. */
    private static final BigDecimal AMOUNT = new BigDecimal("1250.75");

    /** The answer {@code claimEvent} gives when this delivery takes the marker. */
    private static final int CLAIM_TAKEN = 1;

    private ProcessedEventRepository processedEvents;
    private CardholderContextRepository cardholderContexts;
    private NotificationService notificationService;
    private Acknowledgment acknowledgment;
    private MeterRegistry registry;
    private TransactionAuthorizedConsumer consumer;

    /** Builds the listener over stubbed collaborators and a real meter registry. */
    @BeforeEach
    void buildConsumer() {
        processedEvents = mock(ProcessedEventRepository.class);
        lenient().when(processedEvents.claimEvent(any(), any(), any())).thenReturn(CLAIM_TAKEN);
        cardholderContexts = mock(CardholderContextRepository.class);
        lenient().when(cardholderContexts.findById(anyString()))
                .thenReturn(Optional.of(seededContext()));
        notificationService = mock(NotificationService.class);
        acknowledgment = mock(Acknowledgment.class);
        registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new ObservabilityConfig().notificationMetrics(registry);
        consumer = new TransactionAuthorizedConsumer(
                new CardholderContextReader(cardholderContexts), processedEvents,
                notificationService,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), metrics);
    }

    /** One populated projection row, as {@code db/migration/V2__seed.sql} loads it. */
    private static CardholderContextEntity seededContext() {
        return new CardholderContextEntity(ACCOUNT_ID, "Immanuel", "Madeline", "Kessler",
                "618 Deshaun Route", "Apt. 802", "Altenwerthshire", "NC", "USA", "12546", "274",
                Instant.EPOCH, Instant.EPOCH);
    }

    /** One authorization carrying every field the alert reports. */
    private static TransactionAuthorized authorized() {
        return authorizedWithToken(CARD_TOKEN);
    }

    /**
     * One authorization carrying the supplied card token.
     *
     * @param cardToken the token the event carries, possibly {@code null}
     * @return the event
     */
    private static TransactionAuthorized authorizedWithToken(String cardToken) {
        if (cardToken == null) {
            // Version 2 requires the property and version 1 forbids it, so an event carrying no
            // token is stamped with the version that declares none. The listener still has to
            // refuse it, because notification_log is keyed by card token.
            return new TransactionAuthorized(UUID.randomUUID(), TransactionAuthorized.EVENT_TYPE,
                    com.carddemo.events.EventEnvelope.SCHEMA_VERSION,
                    Instant.parse("2026-01-01T00:00:00Z"), ACCOUNT_ID, TRANSACTION_ID, "01", "0001",
                    "POS", "GROCERY PURCHASE", AMOUNT, "000049936", "Sunshine Foods", "Raleigh",
                    "27601", MASKED_CARD, null, "2026-01-01 00:00:00.000000", ACCOUNT_ID, "USD");
        }
        return new TransactionAuthorized(UUID.randomUUID(), TransactionAuthorized.EVENT_TYPE,
                TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION,
                Instant.parse("2026-01-01T00:00:00Z"), ACCOUNT_ID, TRANSACTION_ID, "01", "0001",
                "POS", "GROCERY PURCHASE", AMOUNT, "000049936", "Sunshine Foods", "Raleigh",
                "27601", MASKED_CARD, cardToken, "2026-01-01 00:00:00.000000", ACCOUNT_ID, "USD");
    }

    /**
     * Reads one untagged counter.
     *
     * @param name the meter name
     * @return the count
     */
    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    /**
     * Reads one tagged counter.
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
    @DisplayName("The alert one authorization produces")
    class Alerting {

        @Test
        @DisplayName("renders over the event's own fields and acknowledges")
        void rendersOverTheEventsOwnFields() {
            consumer.onTransactionAuthorized(authorized(), acknowledgment, ACCOUNT_ID, TOPIC);

            verify(notificationService).renderAuthorizationAlert(eq(CARD_TOKEN), eq(MASKED_CARD),
                    eq(TRANSACTION_ID), eq(ACCOUNT_ID), eq("GROCERY PURCHASE"), eq(AMOUNT),
                    any(NotificationService.CardholderDetails.class),
                    eq(RenderedFormat.PLAIN_TEXT));
            verify(acknowledgment).acknowledge();
        }

        /**
         * Asserts the alert is rendered in every format the enum declares.
         *
         * <p>{@code app/cbl/CBSTM03A.CBL} declares one output file per format at
         * {@code app/cbl/CBSTM03A.CBL:L44-L47} and writes both in one run, so a listener that
         * rendered one of them left the other renderer registered and unreachable, and its counter
         * permanently at zero. That is what this case guards against returning.</p>
         */
        @Test
        @DisplayName("renders every format app/cbl/CBSTM03A.CBL writes, not one of them")
        void rendersEveryFormatTheSourceWrites() {
            consumer.onTransactionAuthorized(authorized(), acknowledgment, ACCOUNT_ID, TOPIC);

            for (RenderedFormat format : RenderedFormat.values()) {
                verify(notificationService).renderAuthorizationAlert(eq(CARD_TOKEN),
                        eq(MASKED_CARD), eq(TRANSACTION_ID), eq(ACCOUNT_ID),
                        eq("GROCERY PURCHASE"), eq(AMOUNT),
                        any(NotificationService.CardholderDetails.class), eq(format));
            }
            verify(notificationService, times(RenderedFormat.values().length))
                    .renderAuthorizationAlert(anyString(), anyString(), anyString(), anyString(),
                            anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("guards before it renders, and marks before it acknowledges")
        void guardsBeforeItRendersAndMarksBeforeItAcknowledges() {
            consumer.onTransactionAuthorized(authorized(), acknowledgment, ACCOUNT_ID, TOPIC);

            InOrder order = inOrder(processedEvents, notificationService, acknowledgment);
            order.verify(processedEvents).claimEvent(any(), any(), eq(TOPIC));
            // One render per format, and every one of them behind the claim and ahead of the
            // acknowledgement. app/cbl/CBSTM03A.CBL:L44-L47 declares one output file per format.
            order.verify(notificationService, times(RenderedFormat.values().length))
                    .renderAuthorizationAlert(anyString(), anyString(), anyString(), anyString(),
                            anyString(), any(), any(), any());
            order.verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("counts the event under its own series and not the fallback")
        void countsTheEventUnderItsOwnSeries() {
            consumer.onTransactionAuthorized(authorized(), acknowledgment, ACCOUNT_ID, TOPIC);

            assertThat(counter("carddemo.notification.events.consumed", "event.type",
                    NotificationMetrics.EVENT_TRANSACTION_AUTHORIZED))
                    .as("a series that fell back to the unknown tag would attribute this"
                            + " listener's events to a tag naming nothing")
                    .isEqualTo(1.0D);
            assertThat(counter("carddemo.notification.events.consumed", "event.type",
                    NotificationMetrics.UNKNOWN)).isZero();
        }
    }

    @Nested
    @DisplayName("Duplicate delivery")
    class Duplicates {

        @Test
        @DisplayName("a claimed identifier renders nothing and still acknowledges")
        void aClaimedIdentifierRendersNothing() {
            when(processedEvents.claimEvent(any(), any(), any()))
                    .thenReturn(ProcessedEventRepository.ALREADY_CLAIMED);

            consumer.onTransactionAuthorized(authorized(), acknowledgment, ACCOUNT_ID, TOPIC);

            verifyNoInteractions(notificationService);
            verify(acknowledgment).acknowledge();
        }
    }

    /**
     * A contract version this service can apply nothing for is accounted for, not refused.
     *
     * <p>Version 1 of {@code TransactionAuthorized} declares no {@code cardToken}, and
     * {@code notification_log} is keyed on that token. The listener used to throw, which spent three
     * delivery attempts and put a governed, schema-valid event on the dead-letter topic as though it
     * were poison. Since every consumer group starts at the earliest offset, a group added to a topic
     * that still retains version 1 records met that route on every one of them, and the backward
     * compatibility the platform's versioning exists to provide did not hold.
     */
    @Nested
    @DisplayName("A contract version that names no card")
    class VersionsThatNameNoCard {

        @Test
        @DisplayName("a version 1 delivery renders nothing, writes nothing, and is acknowledged")
        void aVersionOneDeliveryIsAcknowledgedWithoutRendering() {
            consumer.onTransactionAuthorized(authorizedWithToken(null), acknowledgment, ACCOUNT_ID,
                    TOPIC);

            verify(acknowledgment).acknowledge();
            verifyNoInteractions(notificationService);
            verifyNoInteractions(processedEvents);
        }

        @Test
        @DisplayName("a version 1 delivery is counted as consumed and as unapplied, not as a failure")
        void aVersionOneDeliveryIsCountedAsUnapplied() {
            consumer.onTransactionAuthorized(authorizedWithToken(null), acknowledgment, ACCOUNT_ID,
                    TOPIC);

            assertAll(
                    () -> assertThat(counter("carddemo.notification.events.unapplied", "event.type",
                            "TransactionAuthorized"))
                            .as("one delivery this listener applied nothing for")
                            .isEqualTo(1.0d),
                    () -> assertThat(counter("carddemo.notification.events.consumed", "event.type",
                            "TransactionAuthorized"))
                            .as("the delivery was still consumed")
                            .isEqualTo(1.0d),
                    () -> assertThat(counter("carddemo.notification.duplicates.skipped"))
                            .as("nothing was skipped as a duplicate: the event was never applied")
                            .isEqualTo(0.0d));
        }

        @Test
        @DisplayName("a version 2 delivery moves no unapplied counter")
        void aVersionTwoDeliveryMovesNoUnappliedCounter() {
            consumer.onTransactionAuthorized(authorized(), acknowledgment, ACCOUNT_ID, TOPIC);

            assertThat(counter("carddemo.notification.events.unapplied", "event.type",
                    "TransactionAuthorized"))
                    .as("a delivery this listener did apply")
                    .isEqualTo(0.0d);
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("a version 1 delivery under a key naming another aggregate is still refused")
        void aVersionOneDeliveryUnderAWrongKeyIsStillRefused() {
            assertThatThrownBy(() -> consumer.onTransactionAuthorized(authorizedWithToken(null),
                    acknowledgment, "00000000099", TOPIC))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not name the aggregate");

            verify(acknowledgment, never()).acknowledge();
        }
    }

    @Nested
    @DisplayName("Refusals, each leaving the offset uncommitted")
    class Refusals {

        @Test
        @DisplayName("a missing cardholder projection is reported rather than rendered blank")
        void aMissingCardholderProjectionIsReported() {
            when(cardholderContexts.findById(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> consumer.onTransactionAuthorized(authorized(), acknowledgment,
                    ACCOUNT_ID, TOPIC))
                    .isInstanceOf(CardholderContextMissingException.class);

            verify(acknowledgment, never()).acknowledge();
            verify(notificationService, never()).renderAuthorizationAlert(anyString(), anyString(),
                    anyString(), anyString(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("the refusal names no account identifier and no cardholder field")
        void theRefusalNamesNothingPersonal() {
            when(cardholderContexts.findById(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> consumer.onTransactionAuthorized(authorized(), acknowledgment,
                    ACCOUNT_ID, TOPIC))
                    .hasMessageNotContaining(ACCOUNT_ID)
                    .hasMessageNotContaining("Kessler");
        }

        @Test
        @DisplayName("a key naming another aggregate arrived on a partition that does not order it")
        void aKeyNamingAnotherAggregateIsRefused() {
            assertThatThrownBy(() -> consumer.onTransactionAuthorized(authorized(), acknowledgment,
                    "00000000099", TOPIC))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not name the aggregate");

            verify(acknowledgment, never()).acknowledge();
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("a record with no key at all is refused")
        void aRecordWithNoKeyIsRefused() {
            assertThatThrownBy(() -> consumer.onTransactionAuthorized(authorized(), acknowledgment,
                    null, TOPIC))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no message key");

            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        @DisplayName("a refusal counts one failure under a registered series")
        void aRefusalCountsOneFailure() {
            when(cardholderContexts.findById(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> consumer.onTransactionAuthorized(authorized(), acknowledgment,
                    ACCOUNT_ID, TOPIC)).isInstanceOf(CardholderContextMissingException.class);

            assertThat(counter("carddemo.notification.failures", "failure.kind",
                    NotificationMetrics.FAILURE_RENDERING)).isEqualTo(1.0D);
        }
    }
}
