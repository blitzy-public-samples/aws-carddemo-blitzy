package com.carddemo.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.CardholderContextReader;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.domain.NotificationService.CardholderDetails;
import com.carddemo.notification.entity.CardholderContextEntity;
import com.carddemo.notification.entity.ProcessedEventEntity;
import com.carddemo.notification.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.notification.repository.CardholderContextRepository;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds {@link FraudFlaggedConsumer} to its contract on the assessment topic. One flagged
 * assessment renders one alert once, a repeat delivery of that assessment renders none, and the
 * offset commit lands after the local transaction closes. Every collaborator is a hand-written fake
 * recording the order it was called in, over a real {@link TransactionTemplate} whose callback
 * therefore runs.
 *
 * <p>Three parts of that contract are additive: the fraud capability, the duplicate guard and the
 * single local transaction. {@code app/cbl/CBSTM03A.CBL} copies four record layouts at L51, L53,
 * L55 and L57, none of which scores risk, and {@code app/cbl/CBTRN02C.cbl:L562-L579} writes the
 * transaction record under no guard. All three sit in {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("FraudFlaggedConsumer, one assessment applied once inside one local transaction")
class FraudFlaggedConsumerTest {

    /** The two names the shipped configuration resolves this listener's placeholders to. */
    private static final String TOPIC = "fraud.assessed";
    private static final String GROUP = "notification-fraud";

    /** The two placeholders the binding holds, keyed in {@code application.yml}. */
    private static final String TOPIC_PLACEHOLDER = "${carddemo.kafka.topics.fraud-assessed}";
    private static final String GROUP_PLACEHOLDER = "${carddemo.kafka.groups.fraud-assessed}";

    /** The group key the read-model listener holds, and the name that key resolves to. */
    private static final String POSTED_GROUP_PLACEHOLDER =
            "${carddemo.kafka.groups.transaction-posted}";
    private static final String POSTED_GROUP = "notification-posted";

    /**
     * The account each assessment names, eleven digits from {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}, and the transaction it names, sixteen characters from
     * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}.
     */
    private static final String ACCOUNT_ID = "00000000007";

    /**
     * An account no payload here names, used to misroute one delivery.
     *
     * <p>Eleven digits with leading zeros kept, because the key is compared as text.
     */
    private static final String OTHER_ACCOUNT_ID = "00000000008";
    private static final String TRANSACTION_ID = "0000000000683580";

    /** A score inside the bounds the flagged event declares. */
    private static final int RISK_SCORE = 87;

    /** The three rule identifiers a flagged event may name, in enumeration order. */
    private static final List<String> RULES = List.of(FraudFlagged.VELOCITY_RULE,
            FraudFlagged.AMOUNT_ANOMALY_RULE, FraudFlagged.MERCHANT_CATEGORY_RULE);

    /** When the rules finished, and when the producer wrote the event, both in universal time. */
    private static final Instant ASSESSED_AT = Instant.parse("2022-07-19T23:16:01.470Z");
    private static final Instant OCCURRED_AT = Instant.parse("2022-07-19T23:16:02.510Z");

    /** Fields one failure reports, from {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy}. */
    private static final int ABEND_FIELD_COUNT = 4;

    /** A digit run this wide names a card or an account, and reaches no diagnostic. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{12,}");

    /** Type names a collaborator that publishes, or that calls a sibling service, would carry. */
    private static final Pattern PUBLISHER_OR_CLIENT =
            Pattern.compile("Producer|Publisher|Outbox|Relay|Client");

    /** Simple name of the annotation that binds one method to a topic and a group. */
    private static final String LISTENER_ANNOTATION = "KafkaListener";

    /** The seven calls the fakes record, which an assertion then reads in order. */
    private static final String TRANSACTION_BEGUN = "transaction begun";
    private static final String MARKER_CLAIMED = "marker claimed";
    private static final String CARDHOLDER_READ = "cardholder read";
    private static final String ALERT_RENDERED = "alert rendered";
    private static final String TRANSACTION_COMMITTED = "transaction committed";
    private static final String TRANSACTION_ROLLED_BACK = "transaction rolled back";
    private static final String OFFSET_COMMITTED = "offset committed";

    /** Every call the listener made, in the order it made them. */
    private final List<String> sequence = new ArrayList<>();

    /** Every operation a store the listener must never reach was asked for. */
    private final List<String> storesReached = new ArrayList<>();

    /** Commits the offset, and is the one Kafka type this test names. */
    private final Acknowledgment acknowledgment = () -> this.sequence.add(OFFSET_COMMITTED);

    /** The four fakes the listener holds: marker store, projection store, renderer, opener. */
    private FakeProcessedEvents processedEvents;
    private FakeCardholderContexts cardholderContexts;
    private FakeAlertRenderer alertRenderer;
    private FakeTransactionManager transactionManager;

    /** The meters {@code config/ObservabilityConfig} registers, over a registry held in memory. */
    private NotificationMetrics metrics;

    /** Every diagnostic line the listener wrote during one test, and the logger it wrote to. */
    private ListAppender<ILoggingEvent> logRecords;
    private ch.qos.logback.classic.Logger listenerLogger;

    /** The level that logger carried before this test lowered it. */
    private Level originalLevel;

    /** The listener under test. */
    private FraudFlaggedConsumer consumer;

    @BeforeEach
    void buildListenerOverFakes() {
        this.processedEvents = new FakeProcessedEvents(this.sequence);
        this.metrics = new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry());
        this.alertRenderer = new FakeAlertRenderer(this.sequence,
                unreachedStore(StatementTransactionRepository.class),
                unreachedStore(NotificationLogRepository.class), this.metrics);
        this.transactionManager = new FakeTransactionManager(this.sequence);
        this.cardholderContexts = new FakeCardholderContexts(this.sequence);
        this.consumer = new FraudFlaggedConsumer(
                new CardholderContextReader(this.cardholderContexts),
                this.processedEvents, this.alertRenderer,
                new TransactionTemplate(this.transactionManager), this.metrics);

        this.listenerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FraudFlaggedConsumer.class);
        this.originalLevel = this.listenerLogger.getLevel();
        this.listenerLogger.setLevel(Level.DEBUG);
        this.logRecords = new ListAppender<>();
        this.logRecords.start();
        this.listenerLogger.addAppender(this.logRecords);
    }

    @AfterEach
    void releaseTheLogger() {
        this.listenerLogger.detachAppender(this.logRecords);
        this.logRecords.stop();
        this.listenerLogger.setLevel(this.originalLevel);
    }

    /**
     * A resolved topic or group name in either attribute is the defect this test reports. The group
     * key differs from the one the read-model listener names, so each tracks its own progress.
     */
    @Test
    @DisplayName("The listener names two property placeholders and no resolved topic or group")
    void theListenerNamesTwoPropertyPlaceholders() throws ReflectiveOperationException {
        Annotation binding = bindingOrNull(soleListenerMethod());

        assertThat(binding).isNotNull();
        assertThat((String[]) attributeOf(binding, "topics")).containsExactly(TOPIC_PLACEHOLDER)
                .doesNotContain(TOPIC);
        assertThat((String) attributeOf(binding, "groupId")).isEqualTo(GROUP_PLACEHOLDER)
                .isNotEqualTo(GROUP).isNotEqualTo(POSTED_GROUP_PLACEHOLDER);
        assertThat(GROUP).isNotEqualTo(POSTED_GROUP);
    }

    /** One bound method, returning nothing and admitting either outcome, one record at a time. */
    @Test
    @DisplayName("One method carries the binding, returns nothing, and takes one record at a time")
    void oneMethodCarriesTheBinding() {
        Method listener = soleListenerMethod();

        assertThat(Modifier.isPublic(listener.getModifiers())).isTrue();
        assertThat(listener.getReturnType()).isEqualTo(void.class);
        assertThat(listener.getParameterTypes()).contains(Acknowledgment.class);
        assertThat(listener.getParameterTypes()[0].isAssignableFrom(FraudFlagged.class)).isTrue();
        assertThat(listener.getParameterTypes()[0].isAssignableFrom(FraudCleared.class)).isTrue();
        for (Class<?> parameter : listener.getParameterTypes()) {
            assertThat(Iterable.class.isAssignableFrom(parameter))
                    .as("one delivery carries one record").isFalse();
        }
    }

    /**
     * The alert reports the transaction, the account, the score, the rules and the ten cardholder
     * fields, and it carries no monetary value: neither assessment outcome declares one.
     */
    @Test
    @DisplayName("One flagged delivery claims the event once and renders one alert per format")
    void oneFlaggedDeliveryClaimsTheEventAndRendersOneAlert() {
        FraudFlagged event = flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES);

        deliver(event);

        assertThat(this.processedEvents.claimedEvents()).containsExactly(event.eventId());
        // One alert per format, in the order RenderedFormat declares them.
        // app/cbl/CBSTM03A.CBL:L44-L47 declares one output file per format and the program writes
        // both in one run.
        assertThat(this.alertRenderer.alerts()).containsExactly(
                List.of(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE, RULES, expectedCardholder(),
                        RenderedFormat.PLAIN_TEXT),
                List.of(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE, RULES, expectedCardholder(),
                        RenderedFormat.HTML));
        assertThat(this.alertRenderer.alerts().get(0))
                .as("no monetary value travels on either assessment outcome")
                .noneMatch(value -> value instanceof BigDecimal);
        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_FLAGGED).count())
                .isEqualTo(1);
    }

    /**
     * The claim and the alert run inside the unit the template opens, and the offset commit follows
     * it. The three writes at {@code app/cbl/CBTRN02C.cbl:L440-L442} ran under no such unit.
     */
    @Test
    @DisplayName("The claim and the alert commit as one unit, and the offset commit follows it")
    void theClaimAndTheAlertCommitAsOneUnit() {
        deliver(flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES));

        assertThat(this.sequence).containsExactly(TRANSACTION_BEGUN, MARKER_CLAIMED,
                CARDHOLDER_READ, ALERT_RENDERED, ALERT_RENDERED, TRANSACTION_COMMITTED,
                OFFSET_COMMITTED);
        assertThat(insideTheUnit()).as("the callback the listener handed the template ran")
                .containsExactly(MARKER_CLAIMED, CARDHOLDER_READ, ALERT_RENDERED, ALERT_RENDERED)
                .doesNotContain(OFFSET_COMMITTED);
        assertThat(this.transactionManager.unitsBegun()).isEqualTo(1);
        assertThat(this.transactionManager.unitsCommitted()).isEqualTo(1);
        assertThat(this.sequence.indexOf(OFFSET_COMMITTED))
                .isGreaterThan(this.sequence.indexOf(TRANSACTION_COMMITTED));
    }

    /**
     * A refused alert propagates, and the recorded sequence ends at a rollback with no offset commit.
     * The transaction manager and the marker store are doubles that record calls, so what this proves
     * is the order and the outcome the listener drives, not the atomicity a database provides. The
     * abend at {@code app/cbl/CBTRN02C.cbl:L707-L711} ended the run at the same point.
     */
    @Test
    @DisplayName("A refused alert rolls the unit back and leaves the offset uncommitted")
    void aRefusedAlertRollsTheUnitBackAndLeavesTheOffsetUncommitted() {
        this.alertRenderer.refuseAlertWith(
                new DataAccessResourceFailureException("the alert store was refused"));

        assertThatThrownBy(() -> deliver(flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES)))
                .isInstanceOf(DataAccessException.class);

        assertThat(this.sequence).containsExactly(TRANSACTION_BEGUN, MARKER_CLAIMED,
                CARDHOLDER_READ, ALERT_RENDERED, TRANSACTION_ROLLED_BACK)
                .doesNotContain(OFFSET_COMMITTED);
        assertThat(this.transactionManager.unitsRolledBack()).isEqualTo(1);
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_PERSISTENCE).count())
                .isEqualTo(1);
    }

    /**
     * A missing projection row is reported on this path as well, and no alert is rendered from it.
     *
     * <p>{@code domain/CardholderContextReader} refuses to fill an alert it has no row for, and both
     * listeners of this service read through it. Only the read-model listener asserted that refusal,
     * so this path held the guarantee and demonstrated none of it: a change answering a missing row
     * with blank fields was caught on one listener and not on the other. The refusal names no
     * cardholder field, because it reaches a log line and the dead-letter diagnostic.
     */
    @Test
    @DisplayName("A missing cardholder projection is reported rather than rendered blank")
    void aMissingCardholderProjectionIsReported() {
        this.cardholderContexts.holdNoRow();

        assertThatThrownBy(() -> deliver(flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES)))
                .isInstanceOf(CardholderContextReader.CardholderContextMissingException.class)
                .hasMessageNotContaining(ACCOUNT_ID)
                .hasMessageNotContaining("Kessler");

        assertThat(this.alertRenderer.alerts()).isEmpty();
        assertThat(this.sequence).containsExactly(TRANSACTION_BEGUN, MARKER_CLAIMED, CARDHOLDER_READ,
                        TRANSACTION_ROLLED_BACK)
                .doesNotContain(ALERT_RENDERED, OFFSET_COMMITTED);
        assertThat(this.transactionManager.unitsRolledBack()).isEqualTo(1);
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_RENDERING).count())
                .isEqualTo(1);
    }

    /**
     * A repeat delivery renders no second alert, where the unguarded write at
     * {@code app/cbl/CBTRN02C.cbl:L562-L579} met a duplicate key and ended the run. The listener
     * calls the claim once per delivery and acts only on the call that took the marker; the double
     * here records those calls in order, and {@code outbox/OutboxAtomicityIT} is where a real
     * PostgreSQL primary key settles two concurrent claims.
     */
    @Test
    @DisplayName("A repeat delivery renders no alert, and the listener acts only on the claim "
            + "that took the marker")
    void aRepeatDeliveryRendersNoAlert() {
        FraudFlagged event = flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES);
        deliver(event);
        this.sequence.clear();

        deliver(event);

        assertThat(this.sequence).containsExactly(TRANSACTION_BEGUN, MARKER_CLAIMED,
                TRANSACTION_COMMITTED, OFFSET_COMMITTED).containsOnlyOnce(OFFSET_COMMITTED);
        // The first delivery rendered one alert per format; the repeat adds none.
        assertThat(this.alertRenderer.alerts()).hasSize(RenderedFormat.values().length);
        assertThat(this.processedEvents.claimedEvents()).containsExactly(event.eventId());
        assertThat(this.processedEvents.claimCalls()).isEqualTo(2);
        assertThat(this.processedEvents.existenceReads()).as("existsById is never called").isZero();
        assertThat(this.processedEvents.markerWrites()).isZero();
        assertThat(this.processedEvents.recordedTopics()).containsOnly(TOPIC);
    }

    /** A repeat delivery counts as a duplicate and writes one diagnostic line at debug. */
    @Test
    @DisplayName("A repeat delivery counts as a duplicate and raises no failure")
    void aRepeatDeliveryRaisesNoFailure() {
        FraudFlagged event = flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES);
        deliver(event);
        this.logRecords.list.clear();

        deliver(event);

        assertThat(this.metrics.duplicatesSkipped().count()).isEqualTo(1);
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_PERSISTENCE).count()).isZero();
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_RENDERING).count()).isZero();
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_SCHEMA_VALIDATION).count())
                .isZero();
        assertThat(eventsFrom(Level.WARN)).isEmpty();
        assertThat(this.logRecords.list).hasSize(1);
        assertThat(this.logRecords.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
    }

    /**
     * One marker table serves every listener of this service, keyed on the event identifier and the
     * topic it arrived on. A marker already held for that pair stops the alert here, and no consumer
     * group is read: the topic already separates two listeners, and a group name is configuration
     * that would change identity whenever it was renamed.
     */
    @Test
    @DisplayName("A marker already held for one event identifier is honoured, and no group is read")
    void aMarkerAlreadyHeldForOneEventIdentifierIsHonoured() {
        UUID sharedIdentifier = UUID.randomUUID();
        this.processedEvents.holdMarkerFor(sharedIdentifier, TOPIC);

        deliver(flaggedEvent(sharedIdentifier, RISK_SCORE, RULES));

        assertThat(this.alertRenderer.alerts()).isEmpty();
        assertThat(this.metrics.duplicatesSkipped().count()).isEqualTo(1);
        assertThat(this.sequence).containsExactly(TRANSACTION_BEGUN, MARKER_CLAIMED,
                TRANSACTION_COMMITTED, OFFSET_COMMITTED);
        assertThat(this.processedEvents.claimArguments())
                .containsExactly(List.of(sharedIdentifier, TOPIC))
                .noneMatch(arguments -> arguments.contains(GROUP));
    }

    /**
     * A cleared assessment tells a cardholder nothing, so no alert and no projection read follows
     * it, and none of its payload values travels past the claim.
     */
    @Test
    @DisplayName("A cleared assessment claims its event, renders no alert, and acknowledges")
    void aClearedAssessmentClaimsItsEventAndRendersNoAlert() {
        FraudCleared event = clearedEvent(UUID.randomUUID());

        deliver(event);

        assertThat(this.processedEvents.claimedEvents()).containsExactly(event.eventId());
        assertThat(this.alertRenderer.alerts()).isEmpty();
        assertThat(this.sequence).containsExactly(TRANSACTION_BEGUN, MARKER_CLAIMED,
                TRANSACTION_COMMITTED, OFFSET_COMMITTED)
                .doesNotContain(CARDHOLDER_READ, ALERT_RENDERED);
        assertThat(this.transactionManager.unitsBegun()).isEqualTo(1);
        assertThat(this.storesReached).isEmpty();
        assertThat(this.processedEvents.claimArguments())
                .noneMatch(arguments -> arguments.contains(TRANSACTION_ID))
                .noneMatch(arguments -> arguments.contains(ACCOUNT_ID));
        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_CLEARED).count())
                .isEqualTo(1);
        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_FLAGGED).count())
                .isZero();
    }

    /**
     * One cleared and one flagged event carrying the same transaction, account and assessment
     * instant reach different outcomes, so the event type alone decides the path.
     */
    @Test
    @DisplayName("Two outcomes alike but for their event type reach two different paths")
    void twoOutcomesAlikeButForTheirEventTypeReachTwoDifferentPaths() {
        deliver(flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES));
        int alertsAfterFlagged = this.alertRenderer.alerts().size();

        deliver(clearedEvent(UUID.randomUUID()));

        // The flagged outcome renders one alert per format; the cleared outcome renders none.
        assertThat(alertsAfterFlagged).isEqualTo(RenderedFormat.values().length);
        assertThat(this.alertRenderer.alerts()).hasSize(alertsAfterFlagged);
        assertThat(FraudFlagged.EVENT_TYPE).isNotEqualTo(FraudCleared.EVENT_TYPE);
        assertThat(this.processedEvents.claimCalls()).isEqualTo(2);
    }

    /** A record the assessment topic never carries reaches no claim and no offset commit. */
    @Test
    @DisplayName("A payload outside the two assessment outcomes is refused, and nothing is claimed")
    void aPayloadOutsideTheTwoAssessmentOutcomesIsRefused() {
        assertThatThrownBy(() -> deliver(new UnknownAssessment("no assessment")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(this.sequence).isEmpty();
        assertThat(this.processedEvents.claimCalls()).isZero();
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_SCHEMA_VALIDATION).count())
                .isEqualTo(1);
    }

    /**
     * A refused payload is still measured, on the {@code unknown} series of both families.
     *
     * <p>Recording the consumed count and the latency inside the two apply methods would leave a
     * refused payload moving neither, since it never reaches them, and a rejected delivery would then
     * be indistinguishable from one that never arrived. {@code unknown} is already a declared value
     * of the {@code eventType} tag, so measuring it introduces no new tag value and the meter count
     * stays fixed.
     */
    @Test
    @DisplayName("A refused payload is counted and timed on the unknown series")
    void aRefusedPayloadIsCountedAndTimedOnTheUnknownSeries() {
        assertThatThrownBy(() -> deliver(new UnknownAssessment("no assessment")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(this.metrics.eventsConsumed(NotificationMetrics.UNKNOWN).count())
                .as("a record that arrived was consumed whatever became of it")
                .isEqualTo(1);
        assertThat(this.metrics.processingLatency(NotificationMetrics.UNKNOWN).count())
                .as("the latency of the refusal, recorded in a finally")
                .isEqualTo(1);
        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_FLAGGED).count())
                .as("a refused payload is no flagged assessment")
                .isZero();
        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_CLEARED).count())
                .isZero();
    }

    /**
     * A key that names another account is counted, timed and classified.
     *
     * <p>This refusal counted nothing at all. It is classified on {@code schema_validation} beside
     * the refused payload, because both reject a record for disagreeing with the document behind the
     * event rather than for a fault in this service, and the document states the key rule as plainly
     * as it states a field type.
     */
    @Test
    @DisplayName("A key naming another account is counted, timed and classified")
    void aKeyNamingAnotherAccountIsCountedTimedAndClassified() {
        FraudFlagged event = flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES);

        assertThatThrownBy(() -> this.consumer.onFraudAssessed(event, OTHER_ACCOUNT_ID,
                this.acknowledgment, TOPIC))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_FLAGGED).count())
                .isEqualTo(1);
        assertThat(this.metrics.processingLatency(NotificationMetrics.EVENT_FRAUD_FLAGGED).count())
                .isEqualTo(1);
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_SCHEMA_VALIDATION).count())
                .as("a misrouted record is a contract failure, and it used to count nothing")
                .isEqualTo(1);
        assertThat(this.processedEvents.claimCalls())
                .as("nothing is claimed under a key the payload does not name")
                .isZero();
        assertThat(this.sequence).isEmpty();
    }

    /** A record carrying no key at all is refused on the same series. */
    @Test
    @DisplayName("A record carrying no key is counted, timed and classified")
    void aRecordCarryingNoKeyIsCountedTimedAndClassified() {
        FraudCleared event = clearedEvent(UUID.randomUUID());

        assertThatThrownBy(() -> this.consumer.onFraudAssessed(event, null, this.acknowledgment,
                TOPIC))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_CLEARED).count())
                .isEqualTo(1);
        assertThat(this.metrics.processingLatency(NotificationMetrics.EVENT_FRAUD_CLEARED).count())
                .isEqualTo(1);
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_SCHEMA_VALIDATION).count())
                .isEqualTo(1);
    }

    /**
     * A refusal on the key reports the four abend fields and names no identifier.
     *
     * <p>The account identifier is eleven digits and the transaction identifier is sixteen, so a
     * refusal that quoted either would put a run of digits into a diagnostic. The metadata names the
     * contract field by its JSON pointer instead.
     */
    @Test
    @DisplayName("A key refusal names no account identifier and no transaction identifier")
    void aKeyRefusalNamesNoIdentifier() {
        FraudFlagged event = flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES);

        assertThatThrownBy(() -> this.consumer.onFraudAssessed(event, OTHER_ACCOUNT_ID,
                this.acknowledgment, TOPIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(ACCOUNT_ID)
                .hasMessageNotContaining(OTHER_ACCOUNT_ID)
                .hasMessageNotContaining(TRANSACTION_ID);
    }

    /**
     * Five collaborators, none a publisher and none a client of a sibling service. The listener does
     * reach the marker store and the cardholder-context reader on the flagged path; what neither
     * outcome writes is a {@code statement_transaction} row or a {@code notification_log} row,
     * because neither event carries a card number or a monetary value for the card-keyed read model
     * of {@code app/cpy/COSTM01.CPY} to take.
     */
    @Test
    @DisplayName("The listener holds five collaborators, and writes neither read-model nor "
            + "rendered-alert row")
    void theListenerHoldsFiveCollaboratorsAndReachesNoRowStore() {
        deliver(flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES));
        deliver(clearedEvent(UUID.randomUUID()));

        List<Class<?>> collaborators = collaboratorTypes();
        assertThat(this.storesReached)
                .as("no statement_transaction row and no rendered-alert row is written")
                .isEmpty();
        assertThat(collaborators).containsExactlyInAnyOrder(CardholderContextReader.class,
                ProcessedEventRepository.class, NotificationService.class,
                TransactionTemplate.class, NotificationMetrics.class);
        assertThat(collaborators).allMatch(type -> type.getPackageName()
                .startsWith("com.carddemo.notification")
                || type.getPackageName().startsWith("org.springframework.transaction"));
        assertThat(collaborators)
                .noneMatch(type -> PUBLISHER_OR_CLIENT.matcher(type.getSimpleName()).find());
    }

    /**
     * The score is one integer inside the bounds the flagged event declares, and it is not money:
     * every monetary value on this platform travels as a decimal string.
     */
    @Test
    @DisplayName("The risk score is a bounded integer, taken at both bounds and refused outside")
    void theRiskScoreIsABoundedInteger() {
        deliver(flaggedEvent(UUID.randomUUID(), FraudFlagged.MINIMUM_RISK_SCORE, RULES));
        deliver(flaggedEvent(UUID.randomUUID(), FraudFlagged.MAXIMUM_RISK_SCORE, RULES));

        // Two deliveries, and one alert per format on each, so each score appears once per format.
        assertThat(this.alertRenderer.scores()).containsExactly(FraudFlagged.MINIMUM_RISK_SCORE,
                FraudFlagged.MINIMUM_RISK_SCORE, FraudFlagged.MAXIMUM_RISK_SCORE,
                FraudFlagged.MAXIMUM_RISK_SCORE);
        assertThatThrownBy(() -> flaggedEvent(UUID.randomUUID(),
                FraudFlagged.MINIMUM_RISK_SCORE - 1, RULES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flaggedEvent(UUID.randomUUID(),
                FraudFlagged.MAXIMUM_RISK_SCORE + 1, RULES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The list the event holds is a copy, so a later change to the caller's list leaves it be. */
    @Test
    @DisplayName("The rule list names known identifiers once each and refuses every mutation")
    void theRuleListNamesKnownIdentifiersOnceEach() {
        List<String> supplied = new ArrayList<>(List.of(FraudFlagged.VELOCITY_RULE));
        FraudFlagged event = flaggedEvent(UUID.randomUUID(), RISK_SCORE, supplied);
        supplied.add(FraudFlagged.AMOUNT_ANOMALY_RULE);

        assertThat(event.triggeredRules()).containsExactly(FraudFlagged.VELOCITY_RULE);
        assertThatThrownBy(() -> event.triggeredRules().add(FraudFlagged.MERCHANT_CATEGORY_RULE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(RULES).hasSameElementsAs(FraudFlagged.RULE_IDENTIFIERS);
        assertThatThrownBy(() -> flaggedEvent(UUID.randomUUID(), RISK_SCORE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flaggedEvent(UUID.randomUUID(), RISK_SCORE,
                List.of(FraudFlagged.VELOCITY_RULE, FraudFlagged.VELOCITY_RULE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Rule order carries no meaning to the listener, so both orderings render one alert each. */
    @Test
    @DisplayName("Two orderings of one rule set produce the same behaviour")
    void twoOrderingsOfOneRuleSetProduceTheSameBehaviour() {
        deliver(flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES));
        deliver(flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES.reversed()));

        // Two deliveries, each rendered once per format, so four renderings carry the same set.
        assertThat(this.alertRenderer.rules())
                .hasSize(2 * RenderedFormat.values().length)
                .allSatisfy(rendered -> assertThat(rendered).hasSameElementsAs(RULES));
        assertThat(this.processedEvents.claimedEvents()).hasSize(2);
        assertThat(this.metrics.duplicatesSkipped().count()).isZero();
    }

    /**
     * The key is the account identifier of {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}, carried as text so its leading zeros survive.
     */
    @Test
    @DisplayName("The message key is the eleven-digit account identifier, leading zeros kept")
    void theMessageKeyIsTheElevenDigitAccountIdentifier() {
        FraudFlagged event = flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES);

        deliver(event);

        assertThat(event.aggregateId()).isEqualTo(ACCOUNT_ID).isEqualTo(event.accountId())
                .hasSize(PicClause.XREF_ACCT_ID_WIDTH)
                .matches(EventEnvelope.AGGREGATE_ID_PATTERN).startsWith("0");
        assertThat(event.transactionId()).hasSize(PicClause.TRAN_ID_WIDTH);
        assertThat(this.alertRenderer.alerts().get(0)).contains(ACCOUNT_ID);
    }

    /**
     * One failure reports the four fields of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L28}, and a digit run in the fault text reaches none of them.
     */
    @Test
    @DisplayName("One failure reports four fields, none carrying a run of twelve digits")
    void oneFailureReportsFourFieldsWithNoLongDigitRun() {
        String sixteenDigits = "9182736450" + "192837";
        this.processedEvents.refuseClaimWith(new DataAccessResourceFailureException(
                "the claim was refused under " + sixteenDigits));

        assertThatThrownBy(() -> deliver(flaggedEvent(UUID.randomUUID(), RISK_SCORE, RULES)))
                .isInstanceOf(DataAccessException.class);

        List<ILoggingEvent> failures = eventsFrom(Level.WARN);
        assertThat(failures).hasSize(1);
        assertThat(reportedFields(failures.get(0))).hasSize(ABEND_FIELD_COUNT)
                .noneMatch(value -> LONG_DIGIT_RUN.matcher(value).find())
                .noneMatch(value -> value.contains(sixteenDigits));
    }

    /** Hands one delivery to the listener on the topic the configuration resolves. */
    private void deliver(Record event) {
        this.consumer.onFraudAssessed(event, ACCOUNT_ID, this.acknowledgment, TOPIC);
    }

    private static FraudFlagged flaggedEvent(UUID eventId, int riskScore, List<String> rules) {
        return new FraudFlagged(eventId, FraudFlagged.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION,
                OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID, riskScore, rules, ASSESSED_AT, ACCOUNT_ID);
    }

    private static FraudCleared clearedEvent(UUID eventId) {
        return new FraudCleared(new EventEnvelope(eventId, FraudCleared.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID), TRANSACTION_ID, ACCOUNT_ID,
                ASSESSED_AT);
    }

    /** One populated row of the account-keyed cardholder projection. */
    private static CardholderContextEntity heldContext() {
        return new CardholderContextEntity(ACCOUNT_ID, "Immanuel", "Madeline", "Kessler",
                "618 Deshaun Route", "Apt. 802", "Altenwerthshire", "NC", "USA", "12546", "274",
                Instant.EPOCH, Instant.EPOCH);
    }

    /** The ten cardholder fields {@link #heldContext()} maps onto. */
    private static CardholderDetails expectedCardholder() {
        return new CardholderDetails("Immanuel", "Madeline", "Kessler", "618 Deshaun Route",
                "Apt. 802", "Altenwerthshire", "NC", "USA", "12546", "274");
    }

    /** Lists the types of the collaborator fields the listener holds. */
    private static List<Class<?>> collaboratorTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (Field field : FraudFlaggedConsumer.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                types.add(field.getType());
            }
        }
        return types;
    }

    /** Finds the one method the listener binds to a topic. */
    private static Method soleListenerMethod() {
        List<Method> bound = new ArrayList<>();
        for (Method candidate : FraudFlaggedConsumer.class.getDeclaredMethods()) {
            if (bindingOrNull(candidate) != null) {
                bound.add(candidate);
            }
        }
        assertThat(bound).hasSize(1);
        return bound.get(0);
    }

    private static Annotation bindingOrNull(Method candidate) {
        for (Annotation annotation : candidate.getAnnotations()) {
            if (LISTENER_ANNOTATION.equals(annotation.annotationType().getSimpleName())) {
                return annotation;
            }
        }
        return null;
    }

    private static Object attributeOf(Annotation binding, String name)
            throws ReflectiveOperationException {
        return binding.annotationType().getMethod(name).invoke(binding);
    }

    /** Lists the calls recorded between the opening of the transaction and its commit. */
    private List<String> insideTheUnit() {
        return this.sequence.subList(this.sequence.indexOf(TRANSACTION_BEGUN) + 1,
                this.sequence.indexOf(TRANSACTION_COMMITTED));
    }

    /** Lists the diagnostic lines the listener wrote at one level or above it. */
    private List<ILoggingEvent> eventsFrom(Level level) {
        List<ILoggingEvent> matching = new ArrayList<>();
        for (ILoggingEvent line : this.logRecords.list) {
            if (line.getLevel().isGreaterOrEqual(level)) {
                matching.add(line);
            }
        }
        return matching;
    }

    /** Lists the field values one diagnostic line reported beside its message. */
    private static List<String> reportedFields(ILoggingEvent failure) {
        List<String> values = new ArrayList<>();
        for (KeyValuePair pair : failure.getKeyValuePairs()) {
            values.add(String.valueOf(pair.value));
        }
        return values;
    }

    /** Stands in for a store the listener must never reach, recording and refusing every call. */
    private <T> T unreachedStore(Class<T> contract) {
        return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(),
                new Class<?>[] {contract}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) {
                            return contract.getSimpleName() + " stand-in";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        return proxy == arguments[0];
                    }
                    this.storesReached.add(contract.getSimpleName() + "." + method.getName());
                    throw unused();
                }));
    }

    /** The refusal every operation the listener leaves alone raises. */
    private static UnsupportedOperationException unused() {
        return new UnsupportedOperationException("the listener under test calls no such operation");
    }

    /** A record the assessment topic never carries, which the listener refuses. */
    private record UnknownAssessment(String description) {
    }

    /**
     * Store of duplicate-delivery markers, claiming one event identifier once per topic.
     *
     * <p>The claim is keyed on the identifier AND the topic, as {@code pk_processed_event} is after
     * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql}. A fake keyed on the
     * identifier alone would suppress a different event that happened to share one, which is the
     * defect the migration removed, and a test running against it would pass while the shipped
     * schema behaved differently.
     */
    private static final class FakeProcessedEvents implements ProcessedEventRepository {

        private final List<String> sequence;
        private final Set<ProcessedEventId> claimed = new LinkedHashSet<>();
        private final List<UUID> claimedEvents = new ArrayList<>();
        private final List<String> recordedTopics = new ArrayList<>();
        private final List<List<Object>> claimArguments = new ArrayList<>();
        private RuntimeException claimFault;
        private int claimCalls;
        private int existenceReads;
        private int markerWrites;

        FakeProcessedEvents(List<String> sequence) {
            this.sequence = sequence;
        }

        void holdMarkerFor(UUID eventId, String consumedTopic) {
            this.claimed.add(new ProcessedEventId(eventId, consumedTopic));
        }

        void refuseClaimWith(RuntimeException fault) {
            this.claimFault = fault;
        }

        List<UUID> claimedEvents() { return this.claimedEvents; }

        List<String> recordedTopics() { return this.recordedTopics; }

        List<List<Object>> claimArguments() { return this.claimArguments; }

        int claimCalls() { return this.claimCalls; }

        int existenceReads() { return this.existenceReads; }

        int markerWrites() { return this.markerWrites; }

        @Override
        public int claimEvent(UUID eventId, Instant processedAt, String consumedTopic) {
            this.sequence.add(MARKER_CLAIMED);
            this.claimCalls++;
            this.recordedTopics.add(consumedTopic);
            this.claimArguments.add(List.of(eventId, String.valueOf(consumedTopic)));
            if (this.claimFault != null) {
                throw this.claimFault;
            }
            if (!this.claimed.add(new ProcessedEventId(eventId, consumedTopic))) {
                return ProcessedEventRepository.ALREADY_CLAIMED;
            }
            this.claimedEvents.add(eventId);
            return 1;
        }

        @Override
        public boolean existsById(ProcessedEventId key) {
            this.existenceReads++;
            return this.claimed.contains(key);
        }

        @Override
        public <S extends ProcessedEventEntity> S save(S marker) {
            this.markerWrites++;
            return marker;
        }

        // Operations the listener leaves alone.
        @Override
        public Optional<ProcessedEventEntity> findById(ProcessedEventId key) { throw unused(); }
        @Override public List<ProcessedEventEntity> findAll() { throw unused(); }
        @Override public long count() { throw unused(); }
        @Override public void deleteById(ProcessedEventId key) { throw unused(); }
        @Override public void delete(ProcessedEventEntity marker) { throw unused(); }
        @Override public void deleteAll() { throw unused(); }

        @Override
        public <S extends ProcessedEventEntity> List<S> saveAll(Iterable<S> batch) {
            throw unused();
        }

        @Override
        public List<ProcessedEventEntity> findAllById(Iterable<ProcessedEventId> keys) {
            throw unused();
        }

        @Override
        public void deleteAllById(Iterable<? extends ProcessedEventId> keys) { throw unused(); }

        @Override
        public void deleteAll(Iterable<? extends ProcessedEventEntity> batch) { throw unused(); }
    }

    /** Store of the account-keyed cardholder projection, holding one row or none. */
    private static final class FakeCardholderContexts implements CardholderContextRepository {

        private final List<String> sequence;
        private boolean holdsARow = true;

        FakeCardholderContexts(List<String> sequence) {
            this.sequence = sequence;
        }

        /** Empties the projection, which is the state a newly opened account arrives in. */
        void holdNoRow() {
            this.holdsARow = false;
        }

        @Override
        public Optional<CardholderContextEntity> findById(String accountId) {
            this.sequence.add(CARDHOLDER_READ);
            return this.holdsARow ? Optional.of(heldContext()) : Optional.empty();
        }

        @Override
        public int applyContextChange(String accountId, String firstName, String middleName,
                String lastName, String addressLine1, String addressLine2, String addressLine3,
                String stateCode, String countryCode, String zipCode, String ficoScore,
                Instant sourceOccurredAt, Instant observedAt) { throw unused(); }
    }

    /** Renderer of the cardholder alert, recording the six arguments each request carried. */
    private static final class FakeAlertRenderer extends NotificationService {

        private final List<String> sequence;
        private final List<List<Object>> alerts = new ArrayList<>();
        private final List<Integer> scores = new ArrayList<>();
        private final List<List<String>> rules = new ArrayList<>();
        private RuntimeException alertFault;

        FakeAlertRenderer(List<String> sequence, StatementTransactionRepository rows,
                NotificationLogRepository attempts, NotificationMetrics metrics) {
            super(List.of(), rows, attempts, metrics);
            this.sequence = sequence;
        }

        void refuseAlertWith(RuntimeException fault) {
            this.alertFault = fault;
        }

        List<List<Object>> alerts() { return this.alerts; }

        List<Integer> scores() { return this.scores; }

        List<List<String>> rules() { return this.rules; }

        @Override
        public String renderFraudAlert(String transactionId, String accountId, int riskScore,
                List<String> triggeredRules, CardholderDetails cardholder, RenderedFormat format) {
            this.sequence.add(ALERT_RENDERED);
            this.alerts.add(List.of(transactionId, accountId, riskScore, triggeredRules, cardholder,
                    format));
            this.scores.add(riskScore);
            this.rules.add(List.copyOf(triggeredRules));
            if (this.alertFault != null) {
                throw this.alertFault;
            }
            return "";
        }
    }

    /** Opener of one transaction, recording each boundary the template crosses. */
    private static final class FakeTransactionManager implements PlatformTransactionManager {

        private final List<String> sequence;
        private int unitsBegun;
        private int unitsCommitted;
        private int unitsRolledBack;

        FakeTransactionManager(List<String> sequence) {
            this.sequence = sequence;
        }

        int unitsBegun() { return this.unitsBegun; }

        int unitsCommitted() { return this.unitsCommitted; }

        int unitsRolledBack() { return this.unitsRolledBack; }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            this.unitsBegun++;
            this.sequence.add(TRANSACTION_BEGUN);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            this.unitsCommitted++;
            this.sequence.add(TRANSACTION_COMMITTED);
        }

        @Override
        public void rollback(TransactionStatus status) {
            this.unitsRolledBack++;
            this.sequence.add(TRANSACTION_ROLLED_BACK);
        }
    }
}
