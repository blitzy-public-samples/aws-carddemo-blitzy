package com.carddemo.fraud.messaging;

import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;
import com.carddemo.fraud.domain.RiskScoringService;
import com.carddemo.fraud.domain.RiskScoringService.RiskAssessment;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.entity.ProcessedEventEntity;
import com.carddemo.fraud.outbox.OutboxWriter;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.ProcessedEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one authorized transaction from topic {@code transaction.authorized}, scores it against
 * every risk rule, and hands one assessment event to the transactional outbox. The listener
 * acknowledges the message once that work commits.
 *
 * <p>No COBOL (Common Business Oriented Language) program under {@code app/cbl/} scores risk,
 * counts authorization velocity or reads an event. No COBOL ancestor.
 * Two widths are borrowed from the source records the event carries: {@code TRAN-ID PIC X(16)}
 * through {@code TRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L5-L16}, and
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
 *
 * <p>The duplicate-key gap this class closes sits at {@code app/cbl/CBTRN02C.cbl:L562-L579}, shape
 * only, no logic. Paragraph {@code 2900-WRITE-TRANSACTION-FILE} writes the transaction file at
 * L564, and a status other than {@code '00'} reaches {@code 9999-ABEND-PROGRAM} at L707-L711, four
 * statements ending in {@code CALL 'CEE3ABD'}. A replayed feed hits a duplicate key and abends.
 * Apache Kafka 4.2.1 in Kafka Raft (KRaft) mode delivers at least once. Redelivery follows a
 * consumer group rebalance, a restart mid-batch, or a crash after side effects and before the
 * offset commit.
 *
 * <p>The wire form is flat. One serialized {@link TransactionAuthorized} holds its five envelope
 * properties beside its payload properties in one JavaScript Object Notation (JSON) object: nineteen
 * properties at version one, and twenty at version two, which adds {@code cardToken}. A nested
 * {@code envelope} property fails the contract. Acknowledgement follows the
 * commit: automatic commit is off, the acknowledgement mode is manual and immediate, and
 * {@link Acknowledgment#acknowledge()} is the last statement of the listener.
 *
 * <p>{@link #assessOneEvent} claims the marker, then scores, then writes the assessment row, then
 * writes the outbox row, in that order, inside one local transaction. A delivery that does not take
 * the marker does none of that work and acknowledges: a duplicate is a no-op rather than a second
 * assessment. The single
 * {@code velocity_window} update belongs to {@link RiskScoringService}. {@link OutboxWriter} joins
 * this transaction and opens none, so the assessment row and the event row commit together or not
 * at all. Nothing here publishes: {@code OutboxRelay} in the sibling {@code outbox} package reads
 * the rows written here.
 *
 * <p>Exactly one event leaves per event read. A score that reached
 * {@code carddemo.fraud.risk.flag-threshold} produces a {@link FraudFlagged}, and any score below it
 * produces a {@link FraudCleared} — including one where a rule triggered and scored too little to
 * flag. Both travel topic {@code fraud.assessed}, where a reader routes on {@code eventType}.
 *
 * <p>A failure message names the failing field by its JSON pointer, for example
 * {@code /maskedCardNumber}, and carries no field value and no payload. The event arrives with its
 * card number already masked, so no Primary Account Number (PAN) reaches this service. Retry
 * counting and dead-letter routing belong to the listener container in the sibling {@code config}
 * package: three attempts one second apart, then topic {@code carddemo.dead-letter}. A payload
 * that fails schema validation never reaches this class and takes that route with no retry.
 *
 * <p>The three tables written here are {@code fraud_assessment}, {@code outbox_event} and
 * {@code processed_event} on PostgreSQL 18.4, reached through the Jakarta Persistence API (JPA).
 * Spring Kafka 4.1.0 registers the listener from its annotation alone.
 */
@Component
public class TransactionAuthorizedConsumer {

    /** Writes the two diagnostic lines this class emits, neither carrying a payload value. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionAuthorizedConsumer.class);

    /**
     * Bean name of the listener container factory this listener runs on.
     *
     * <p>The bean carries the acknowledgement mode, the automatic-commit setting, the deserializer
     * pair and the error handler that decides between a retry and the dead-letter topic. This class
     * builds no factory and holds no error handler.
     */
    private static final String CONTAINER_FACTORY = "kafkaListenerContainerFactory";

    /**
     * What {@link ProcessedEventRepository#claimEvent} reports when the event was already taken.
     *
     * <p>The statement writes one row for a new event and none for an event a marker already
     * covers.
     */
    private static final int ALREADY_CLAIMED = 0;

    /** Scores one event against every risk rule and owns the one velocity window update. */
    private final RiskScoringService riskScoring;

    /** Stores one assessment row per transaction identifier. */
    private final FraudAssessmentRepository assessments;

    /** Claims one event identifier, so a redelivered event writes nothing. */
    private final ProcessedEventRepository processedEvents;

    /** Writes the outbox row {@code OutboxRelay} later publishes. */
    private final OutboxWriter outboxWriter;

    /** Counts events read, assessments produced, failures, and the wall time of one event. */
    private final FraudMeters meters;

    /**
     * This bean as the framework holds it.
     *
     * <p>{@link #assessOneEvent} is reached through this handle, so the transaction interceptor
     * runs and {@link OutboxWriter} finds the transaction its {@code MANDATORY} propagation
     * requires. A direct call from one method of this class to another reaches the target and no
     * interceptor.
     */
    private final ObjectProvider<TransactionAuthorizedConsumer> self;

    /**
     * Takes the five collaborators and the handle the transactional call travels through.
     *
     * @param riskScoring     scores one event and updates the velocity window
     * @param assessments     store of assessment rows
     * @param processedEvents store of duplicate-delivery markers
     * @param outboxWriter    writer of the unpublished event row
     * @param meters          counters and the timer this class records
     * @param self            provider of this bean, through which the transactional method is
     *                        called
     * @throws NullPointerException if any argument is null
     */
    public TransactionAuthorizedConsumer(RiskScoringService riskScoring,
            FraudAssessmentRepository assessments, ProcessedEventRepository processedEvents,
            OutboxWriter outboxWriter, FraudMeters meters,
            ObjectProvider<TransactionAuthorizedConsumer> self) {
        this.riskScoring = Objects.requireNonNull(riskScoring, "riskScoring must be present");
        this.assessments = Objects.requireNonNull(assessments, "assessments must be present");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents must be present");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");
        this.self = Objects.requireNonNull(self, "self must be present");
    }

    /**
     * Reads one record, applies it through {@link #assessOneEvent}, counts the outcome, and
     * acknowledges.
     *
     * <p>One record reaches this method per invocation, and no batch does. The topic and the
     * consumer group resolve from configuration, each with the shipped value behind it as a
     * default, so the listener starts even where neither key is set.
     *
     * <p>A failure leaves the offset uncommitted and travels to the container's error handler,
     * which retries the delivery and then routes the record to the dead-letter topic. The
     * acknowledgement below is unreachable on that path, so a duplicate delivery is the expected
     * outcome and the marker written by {@link #assessOneEvent} is what makes it harmless.
     *
     * <p>A record whose payload no deserializer could read never reaches this method: the container
     * raises the deserialization failure before invoking a listener, and
     * {@code config/KafkaConsumerConfig} counts it there. The tombstone check below therefore covers
     * a payload this topic does not carry, and the key check covers a delivery whose key and payload
     * disagree.
     *
     * <p>Every exit is counted, timed and classified, including those two refusals. A record that
     * arrived was consumed whatever became of it, so the consumed count and the latency clock start
     * before the first refusal can be raised and the latency is recorded in a {@code finally}. A
     * refusal that left no count made a rejected delivery indistinguishable from one that never
     * arrived, which is the state an observability review found.
     *
     * @param consumerRecord the delivery, carrying one validated event and the topic it arrived on
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if the delivery carries no payload, no key, or a key that
     *                                  differs from the payload aggregate identifier
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.transaction-authorized:transaction.authorized}",
            groupId = "${spring.kafka.consumer.group-id:fraud-detection}",
            containerFactory = CONTAINER_FACTORY)
    public void onTransactionAuthorized(
            ConsumerRecord<String, TransactionAuthorized> consumerRecord,
            Acknowledgment acknowledgment) {
        Objects.requireNonNull(consumerRecord, "consumerRecord must be present");
        Objects.requireNonNull(acknowledgment, "acknowledgment must be present");

        meters.recordEventConsumed();
        long startedAt = System.nanoTime();
        try {
            TransactionAuthorized event = consumerRecord.value();
            if (event == null) {
                throw new IllegalArgumentException("transaction.authorized carries no tombstone,"
                        + " and a record with no payload names no transaction to assess");
            }
            String messageKey = consumerRecord.key();
            if (messageKey == null || !messageKey.equals(event.aggregateId())) {
                throw new IllegalArgumentException(
                        "the delivery key must equal the payload aggregate identifier");
            }

            self.getObject()
                    .assessOneEvent(event, recordedTopic(consumerRecord.topic()))
                    .ifPresentOrElse(this::countOutcome, meters::recordDuplicateSkipped);
        } catch (RuntimeException failure) {
            meters.recordProcessFailure();
            LOG.warn("Delivery {} was not assessed. The failure was a {}, the offset stays"
                            + " uncommitted, and the container decides between a retry and the"
                            + " dead-letter topic.",
                    deliveryCoordinates(consumerRecord), failure.getClass().getSimpleName());
            throw failure;
        } finally {
            meters.recordProcessingLatency(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
    }

    /**
     * Names one delivery by its broker coordinates.
     *
     * <p>The coordinates identify the record on every exit, including a tombstone that carries no
     * event identifier to name it by. They hold no key and no payload property, so the line stays
     * free of an account identifier, a transaction identifier and a card number.
     *
     * @param consumerRecord the delivery to name
     * @return the topic, partition and offset, in the form the broker's own tooling prints
     */
    private static String deliveryCoordinates(
            ConsumerRecord<String, TransactionAuthorized> consumerRecord) {
        return consumerRecord.topic() + '-' + consumerRecord.partition()
                + '@' + consumerRecord.offset();
    }

    /**
     * Applies one event inside one local transaction, in a fixed order.
     *
     * <p>The marker claim comes first, and an event whose identifier is already claimed leaves
     * every table untouched. The scoring call follows, then the assessment row, then the outbox
     * row. Every one of those writes joins this transaction, so the marker and the effects it
     * guards commit together or not at all.
     *
     * <p>The claim is one conditional insert whose row count reports whether this delivery is the
     * first. A read followed by a later insert has a window in which two deliveries of one event
     * both read nothing and both apply their effects; the primary key of {@code processed_event}
     * closes that window inside the statement.
     *
     * <p>The marker is keyed by the envelope's {@code eventId} together with the topic the delivery
     * arrived on, which is the composite key {@code V4__processed_event_topic_key.sql} declares, so a
     * marker written for one topic never hides a delivery of the same identifier on another. The
     * velocity window is updated once per event, inside
     * {@link RiskScoringService#assess(TransactionAuthorized)}, so a redelivered event adds nothing
     * to a window count.
     *
     * <p>The account identifier and the transaction identifier travel as text, and a leading zero
     * belongs to the value. The authorization timestamp is carried unread: no line here parses it,
     * reformats it or compares it.
     *
     * @param event         the validated event to assess
     * @param consumedTopic the topic the delivery arrived on, recorded on the marker
     * @return the {@code eventType} written to the outbox, or an empty value where the marker
     *         already existed
     * @throws NullPointerException if {@code event} is null
     * @throws org.springframework.transaction.IllegalTransactionStateException if this method was
     *                              reached without the transaction interceptor, which
     *                              {@link OutboxWriter} refuses
     */
    @Transactional
    public Optional<String> assessOneEvent(TransactionAuthorized event, String consumedTopic) {
        Objects.requireNonNull(event, "event must be present");
        UUID eventId = event.eventId();
        int claimed =
                processedEvents.claimEvent(eventId, Instant.now(), recordedTopic(consumedTopic));

        if (claimed == ALREADY_CLAIMED) {
            LOG.debug("Event {} carries a marker already, so this delivery writes nothing.",
                    eventId);
            return Optional.empty();
        }

        RiskAssessment assessment = riskScoring.assess(event);
        assessments.save(assessmentRow(assessment));
        outboxWriter.write(assessmentEvent(assessment));

        return Optional.of(
                assessment.flagged() ? FraudFlagged.EVENT_TYPE : FraudCleared.EVENT_TYPE);
    }

    /**
     * Maps one assessment onto the row of {@code fraud_assessment} it becomes.
     *
     * <p>The row keys on the transaction identifier, so one transaction holds one verdict.
     *
     * @param assessment the score, the verdict and the rules that triggered
     * @return the row to store
     */
    private static FraudAssessmentEntity assessmentRow(RiskAssessment assessment) {
        return new FraudAssessmentEntity(assessment.transactionId(), assessment.accountId(),
                assessment.riskScore(), assessment.flagged(), assessment.triggeredRules(),
                assessment.assessedAt());
    }

    /**
     * Maps one assessment onto the one event it produces.
     *
     * <p>A score at or above the configured flag threshold produces a {@link FraudFlagged}, which
     * its contract requires to name a rule; the threshold is at least one and every rule scores
     * above zero, so a flagged assessment always names one. Anything below the threshold produces a
     * {@link FraudCleared}, which carries the transaction identifier, the account identifier and the
     * assessment time and nothing else — including where a rule did trigger and scored too little to
     * flag. Both stamp a fresh envelope keyed on the account identifier.
     *
     * @param assessment the score, the verdict and the rules that triggered
     * @return one {@link FraudFlagged} or one {@link FraudCleared}, never both and never neither
     */
    private static Object assessmentEvent(RiskAssessment assessment) {
        if (assessment.flagged()) {
            return FraudFlagged.of(assessment.accountId(), assessment.transactionId(),
                    assessment.riskScore(), assessment.triggeredRules(), assessment.assessedAt());
        }
        return FraudCleared.of(assessment.transactionId(), assessment.accountId(),
                assessment.assessedAt());
    }

    /**
     * Counts one assessment event written to the outbox, under the outcome its event type names.
     * The publish itself belongs to {@code OutboxRelay} and is counted nowhere here.
     *
     * @param eventType the {@code eventType} written to the outbox
     */
    private void countOutcome(String eventType) {
        if (FraudFlagged.EVENT_TYPE.equals(eventType)) {
            meters.recordAssessmentFlagged();
        } else {
            meters.recordAssessmentCleared();
        }
    }

    /**
     * Returns the topic to record on the marker, never blank.
     *
     * <p>The topic is half of the marker's primary key since
     * {@code src/main/resources/db/migration/V4__processed_event_topic_key.sql}, and a key
     * column holds no null. A delivery that carried no topic header records
     * {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}, which states that absence rather than leaving
     * the key half unset. That sentinel holds spaces and parentheses and a Kafka topic name holds
     * only {@code [a-zA-Z0-9._-]}, so it can never collide with a real topic name.</p>
     *
     * @param consumedTopic the topic the delivery arrived on, possibly absent or blank
     * @return the topic name, or {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}
     */
    private static String recordedTopic(String consumedTopic) {
        return consumedTopic == null || consumedTopic.isBlank()
                ? ProcessedEventEntity.NO_CONSUMED_TOPIC
                : consumedTopic;
    }
}
