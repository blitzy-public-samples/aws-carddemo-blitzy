package com.carddemo.fraud.outbox;

import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.messaging.DeadLetterMetadata;
import com.carddemo.fraud.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the {@code outbox_event} rows {@link OutboxWriter} stored, then marks each one it sent.
 *
 * <p>No COBOL ancestor. The one ancestor construct of the outbox pattern
 * is the Customer Information Control System (CICS) transient data queue write at
 * {@code app/cbl/CORPT00C.cbl:L517-L523}. One program writes a Job Control Language (JCL) record
 * there and a separate job reads it later. Shape only, no logic.
 *
 * <p>One tick reads a batch of unpublished rows in creation order. The relay reads each stored
 * payload back into its record type, sends that record to the topic {@code fraud.assessed}, and
 * marks the row published. The tick runs in a transaction of its own. The assessment row and the
 * event row already committed inside the listener's transaction, which this class never joins.
 *
 * <p>Every message is keyed on the eleven-digit account identifier the row holds as
 * {@code aggregate_id}, from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
 * (shape only, no logic). The key selects the partition, so the events of one account stay in
 * publish order. The row's {@code event_type} selects the record type, and the topic name never
 * does: {@code FraudFlagged} and {@code FraudCleared} travel together on the one topic.
 *
 * <p>A send the broker does not accept leaves its row unpublished, and the next tick takes that row
 * again. A row naming a type this service does not publish reaches the dead-letter topic. So does a
 * row whose payload no longer satisfies its event contract. Both are then marked published, so no
 * later tick takes either one again.
 *
 * <p>Two facts a reader needs. {@code FraudApplication} carries {@code @EnableScheduling}, and
 * without it this application starts, reports healthy, and publishes nothing. The wire form is
 * flat: the five envelope properties sit beside the payload properties in one JavaScript Object
 * Notation (JSON) object. A written {@link FraudFlagged} holds ten properties, and a written
 * {@link FraudCleared} holds eight.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class OutboxRelay {

    /** Diagnostic output of this class. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** The failure code every dead letter of this relay carries. */
    private static final String ABEND_CODE = "0999";

    /** Reason recorded on a row whose claiming instance died before it finished. */
    private static final String CLAIM_EXPIRED = "ClaimExpired";

    /** The component every dead letter of this relay names. */
    private static final String CULPRIT = "RELAY";

    /** Classification a dead letter carries for a type this service never publishes. */
    private static final String UNKNOWN_TYPE_REASON =
            "event type is not one this service publishes";

    /** Failing pointer a dead letter carries for a row naming such a type. */
    private static final String UNKNOWN_TYPE_MESSAGE = "failing pointer /eventType";

    /** Classification a dead letter carries for a payload its own event contract refuses. */
    private static final String CONTRACT_REASON = "payload does not satisfy its event contract";

    /** Failing pointer a dead letter carries for such a payload. */
    private static final String CONTRACT_MESSAGE = "failing pointer / at the document root";

    /**
     * The event type a dead letter may report, which is the pattern
     * {@code schemas/dead-letter-v1.json} sets on its {@code failedEventType} property.
     */
    private static final Pattern REPORTABLE_EVENT_TYPE =
            Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,63}$");

    /** Partition every dead letter reports. A row of {@code outbox_event} arrives from no topic. */
    private static final int NO_SOURCE_PARTITION = 0;

    /** Offset every dead letter reports, on the same footing as the partition above. */
    private static final long NO_SOURCE_OFFSET = 0L;

    /** Largest configured pass duration accepted, five minutes. */
    private static final long MAX_DURATION_MS = 300_000L;

    /** Reads unpublished rows and stores the flag each published row carries. */
    private final OutboxEventRepository outboxEvents;

    /**
     * Sends one event record under one message key. {@code config/KafkaProducerConfig} builds this
     * template, and its value serializer takes a registered event record and no other value.
     */
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** Counts one failed row, under {@code carddemo.fraud.failures} with stage publish. */
    private final FraudMeters meters;

    /** Reads one stored payload back into its record type. Jackson 3, matching the platform. */
    private final ObjectMapper objectMapper;

    /**
     * The two event types this service publishes, each mapped to the record its payload reads into.
     * The map is the type guard: a row naming any other type never reaches a topic.
     */
    private final Map<String, Class<?>> recordTypesByEventType;

    /** The topic both assessment outcomes travel on. */
    private final String fraudAssessedTopic;

    /** The topic a row no tick can publish travels on. */
    private final String deadLetterTopic;

    /** Rows one tick claims. */
    private final int batchSize;

    /** The boundary one tick runs inside, so the claim holds and no counter joins it. */
    private final TransactionTemplate transactionTemplate;

    /** Base of the retry backoff, from {@code carddemo.outbox.relay.fixed-delay-ms}. */
    private final Duration tickDelay;

    /** How long a claim may stand before another tick recovers it, and the backoff ceiling. */
    private final Duration claimTimeout;

    /** What this instance writes into {@code claimed_by}, so a claim can be traced to a process. */
    private final String instanceId;

    /**
     * Nanoseconds one whole pass may take, from {@code carddemo.outbox.relay.max-duration-ms}.
     *
     * <p>A broker that accepts a connection and never answers would otherwise hold the scheduled
     * thread for the life of the process. A pass stops at this deadline, the rows it did not reach
     * stay due, and the next tick starts fresh.
     */
    private final long maxDurationNanos;

    /**
     * Takes the store, the template and the meters, reads the four configured values, and builds
     * the one mapper this relay reads payloads with.
     *
     * <p>Each property key carries a default, so this service runs before a
     * deployment sets any of them.
     *
     * @param outboxEvents       store of unpublished events
     * @param kafkaTemplate      the template both topics are reached through
     * @param meters             the recording surface of this service
     * @param fraudAssessedTopic topic both assessment outcomes travel on, from
     *                           {@code carddemo.kafka.topics.fraud-assessed}
     * @param deadLetterTopic    topic an unpublishable row travels on, from
     *                           {@code carddemo.kafka.topics.dead-letter}
     * @param transactionTemplate the boundary one tick runs inside
     * @param properties          the bound {@code carddemo} block, read for the relay settings
     * @throws NullPointerException     if the store, the template or the meters is null
     * @throws IllegalArgumentException if the batch size or duration is outside its accepted range,
     *                                  or if either topic name resolves to no usable value
     */
    public OutboxRelay(OutboxEventRepository outboxEvents,
            @Qualifier("fraudEventKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            FraudMeters meters,
            @Value("${carddemo.kafka.topics.fraud-assessed:fraud.assessed}")
                    String fraudAssessedTopic,
            @Value("${carddemo.kafka.topics.dead-letter:carddemo.dead-letter}")
                    String deadLetterTopic,
            TransactionTemplate transactionTemplate,
            FraudProperties properties) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must be present");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate must be present");
        FraudProperties.Outbox.Relay relay =
                Objects.requireNonNull(properties, "properties must be present").outbox().relay();
        this.batchSize = requireBatchSize(relay.batchSize());
        this.tickDelay = Duration.ofMillis(relay.fixedDelayMs());
        this.claimTimeout = relay.claimTimeout();
        this.instanceId = relay.instanceId();
        this.maxDurationNanos = requireMaxDurationNanos(relay.maxDurationMs());
        this.fraudAssessedTopic =
                requireTopic(fraudAssessedTopic, "carddemo.kafka.topics.fraud-assessed");
        this.deadLetterTopic = requireTopic(deadLetterTopic, "carddemo.kafka.topics.dead-letter");
        this.objectMapper = eventMapper();
        this.recordTypesByEventType = Map.of(FraudFlagged.EVENT_TYPE, FraudFlagged.class,
                FraudCleared.EVENT_TYPE, FraudCleared.class);
    }

    /**
     * Runs one tick and records what it did once that tick has committed.
     *
     * <p>The tick runs inside one transaction because the claim depends on it:
     * {@link OutboxEventRepository#claimDueRows} holds each row it returns with
     * {@code FOR NO KEY UPDATE ... SKIP LOCKED}, and that lock lives exactly as long as the
     * transaction that took it. Two instances of this service therefore claim disjoint batches instead
     * of both publishing every assessment.
     *
     * <p>The boundary is opened here rather than declared with an annotation so that the counters sit
     * outside it. A counter takes no part in a database transaction, so an increment made inside one
     * survives a rollback and reports assessments as published that were never marked.
     *
     * <p>The delay is measured from the end of one tick to the start of the next. The repository
     * takes pessimistic write locks with skip-locked semantics, so concurrent service instances claim
     * disjoint batches. An empty batch writes no log line.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms:500}")
    public void publishPendingEvents() {
        TickResult result = transactionTemplate.execute(status -> publishOneTick());

        Objects.requireNonNull(result, "the tick must answer with a result").record(meters);
    }

    /**
     * Recovers stranded claims, claims the due rows, and works through them longest-waiting first.
     *
     * <p>A row whose payload or schema is refused is permanently unpublishable, so it is dead-lettered
     * and closed, and the tick carries on: nothing about the rows behind it is affected by a row that
     * will never be sent.
     *
     * <p>A row the broker could not take is a different matter. It stays unpublished, becomes due again
     * after a backoff, and the tick stops there. Continuing would publish a later assessment of the
     * same account while an earlier one had not been sent, which is exactly the reordering the message
     * key and the ordering exist to prevent.
     *
     * @return what this tick published and how many rows it failed on
     */
    private TickResult publishOneTick() {
        long deadline = System.nanoTime() + maxDurationNanos;
        Instant now = Instant.now();
        int failed = recoverStrandedClaims(now);

        List<OutboxEventEntity> due = outboxEvents.claimDueRows(now, Limit.of(batchSize));
        if (due.isEmpty() && failed == 0) {
            return new TickResult(0, 0, 0, 0);
        }

        int published = 0;
        int deadLettersPublished = 0;
        int deadLettersFailed = 0;
        for (OutboxEventEntity row : due) {
            row.claim(instanceId, now);
            Class<?> recordType = recordTypesByEventType.get(row.getEventType());
            if (recordType == null) {
                failed++;
                if (routeToDeadLetter(row, DeadLetterMetadata.of(ABEND_CODE, CULPRIT,
                        UNKNOWN_TYPE_REASON, UNKNOWN_TYPE_MESSAGE), deadline)) {
                    deadLettersPublished++;
                } else {
                    deadLettersFailed++;
                }
                continue;
            }
            try {
                publishAndMark(row, recordType, deadline);
                published++;
            } catch (RuntimeException failure) {
                failed++;
                Terminal terminal = onFailedRow(row, failure, now, deadline);
                deadLettersPublished += terminal.published();
                deadLettersFailed += terminal.failed();
                if (terminal.rowIsClosed()) {
                    continue;
                }
                log.info("Outbox relay published {} rows this tick and failed {}", published, failed);
                return new TickResult(published, failed, deadLettersPublished, deadLettersFailed);
            }
        }
        log.info("Outbox relay published {} rows this tick and failed {}", published, failed);
        return new TickResult(published, failed, deadLettersPublished, deadLettersFailed);
    }

    /**
     * Returns rows a dead instance left claimed to {@link OutboxEventEntity.RelayState#PENDING}.
     *
     * <p>Without this one crash costs one assessment permanently: the row stays
     * {@link OutboxEventEntity.RelayState#CLAIMED}, the claim query filters on
     * {@link OutboxEventEntity.RelayState#PENDING}, and nothing looks at it again. The recovery counts
     * as an attempt, so a row that strands repeatedly is eventually abandoned.
     *
     * @param now the moment this tick started
     * @return how many rows were recovered
     */
    private int recoverStrandedClaims(Instant now) {
        List<OutboxEventEntity> stranded =
                outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                        OutboxEventEntity.RelayState.CLAIMED, now.minus(claimTimeout),
                        Limit.of(batchSize));

        for (OutboxEventEntity row : stranded) {
            row.recordFailure(CLAIM_EXPIRED, now, now);
            outboxEvents.save(row);
            log.warn("An outbox row of type {} was claimed by an instance that did not finish, so it "
                            + "is due again. Attempt {} of {}.", row.getEventType(),
                    row.getAttemptCount(), OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        }
        return stranded.size();
    }

    /**
     * Returns how long to wait before attempting a row again.
     *
     * <p>The wait doubles per attempt from the tick delay and stops at the claim timeout, so a row the
     * broker keeps refusing is retried less and less often while always staying claimable within one
     * timeout. Both bounds are configured values rather than numbers written here.
     *
     * @param attemptsSoFar attempts this row had taken before the one that just failed
     * @return the wait, never longer than the claim timeout
     */
    private Duration backoffAfter(int attemptsSoFar) {
        Duration doubled = tickDelay;
        for (int step = 0; step < attemptsSoFar && doubled.compareTo(claimTimeout) < 0; step++) {
            doubled = doubled.multipliedBy(2L);
        }
        return doubled.compareTo(claimTimeout) > 0 ? claimTimeout : doubled;
    }

    /**
     * What one tick did, carried out of the transaction so it can be counted after the commit.
     *
     * <p>{@code failed} counts ATTEMPTS and the two terminal components count RECORDS, which is why
     * they are separate components rather than one total. A row that will be attempted again appears
     * in {@code failed} alone; a row this relay can never publish appears in {@code failed} once and
     * in exactly one of the two terminal components. Nothing is counted twice, and a reader can tell a
     * retry from a permanent loss without opening a log.
     *
     * @param published            rows the broker accepted and this tick marked
     * @param failed               publish attempts this tick could not complete
     * @param deadLettersPublished rows this relay gave up on whose diagnostic the broker acknowledged
     * @param deadLettersFailed    rows this relay gave up on whose diagnostic the broker refused
     */
    private record TickResult(int published, int failed, int deadLettersPublished,
            int deadLettersFailed) {

        /**
         * Records this result against {@code meters}.
         *
         * @param meters the recording surface of this service
         */
        void record(FraudMeters meters) {
            for (int failure = 0; failure < failed; failure++) {
                meters.recordPublishFailure();
            }
            for (int named = 0; named < deadLettersPublished; named++) {
                meters.recordDeadLetterPublished();
            }
            for (int refused = 0; refused < deadLettersFailed; refused++) {
                meters.recordDeadLetterFailure();
            }
        }
    }

    /**
     * Reads one row back into its record type, sends it, and marks the row published.
     *
     * <p>The stored text is read without reshaping, so the writer's document and the broker's
     * document are one flat object. A send that returns while the mark fails leaves the row
     * unpublished, and the next tick sends it again.
     *
     * @param row        the unpublished row
     * @param recordType the record its payload reads into
     * @param deadline   the pass deadline on the monotonic clock
     * @throws SerializationException if the event does not satisfy its schema document
     * @throws JacksonException       if the payload does not read into {@code recordType}
     */
    private void publishAndMark(OutboxEventEntity row, Class<?> recordType, long deadline) {
        Object event = objectMapper.readValue(row.getPayload(), recordType);

        sendWithinDeadline(fraudAssessedTopic, row.getAggregateId(), event, deadline);
        row.markPublished(Instant.now());
        outboxEvents.save(row);
    }

    /**
     * Routes one failed row to the dead-letter topic, or schedules it for another attempt.
     *
     * <p>A failure the event contract raised is permanent, and the row travels to the dead-letter
     * topic and is closed. Every other failure is a broker or a network fault: the row records the
     * attempt, becomes due again after a backoff, and the caller stops the tick so a later assessment
     * of the same account cannot overtake it.
     *
     * <p>The log line names the event type and the failure class. It carries no payload, no message
     * key and no event value, and neither does the reason stored on the row.
     *
     * @param row     the row whose send failed
     * @param failure the failure the send raised
     * @param now      the moment this tick started
     * @param deadline the pass deadline on the monotonic clock
     * @return what this failure did to the terminal counts, and whether the row is closed so the
     *         tick may carry on
     */
    private Terminal onFailedRow(OutboxEventEntity row, RuntimeException failure, Instant now,
            long deadline) {
        Throwable cause = rootCause(failure);
        if (isPermanent(failure)) {
            return routeToDeadLetter(row, DeadLetterMetadata.fromFailure(ABEND_CODE, cause,
                    CONTRACT_REASON, CONTRACT_MESSAGE), deadline)
                    ? Terminal.DEAD_LETTER_PUBLISHED
                    : Terminal.DEAD_LETTER_FAILED;
        }
        String failureClass = cause.getClass().getSimpleName();
        row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEvents.save(row);

        log.warn("An outbox row of type {} stays unpublished after {}, becomes due again after a "
                        + "backoff, and the tick stops there. Attempt {} of {}.", row.getEventType(),
                failureClass, row.getAttemptCount(), OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        return Terminal.RETRY;
    }

    /**
     * What one permanent failure did to the terminal counts, and whether the row is closed.
     *
     * <p>A closed row takes no further attempt, so the tick may move to the row behind it. A row left
     * for a retry stops the tick, because publishing a later assessment of one account while an
     * earlier one waits is the reordering the message key exists to prevent.
     *
     * @param published   1 when a diagnostic reached the broker, otherwise 0
     * @param failed      1 when a diagnostic was refused, otherwise 0
     * @param rowIsClosed whether the row takes no further attempt
     */
    private record Terminal(int published, int failed, boolean rowIsClosed) {

        /** The row stays open and becomes due again after a backoff. */
        private static final Terminal RETRY = new Terminal(0, 0, false);

        /** The row is closed and its diagnostic reached the broker. */
        private static final Terminal DEAD_LETTER_PUBLISHED = new Terminal(1, 0, true);

        /** The row is closed to this tick and its diagnostic was refused. */
        private static final Terminal DEAD_LETTER_FAILED = new Terminal(0, 1, true);
    }

    /**
     * Sends one dead letter for a row no tick can publish, then closes that row.
     *
     * <p>The dead letter carries the four diagnostic values, the topic the row was bound for, the
     * event identifier and the attempt this tick made. It carries no property of the failing
     * payload. Its message key is the account identifier of the row, so a dead letter lands on the
     * partition of the account it concerns.
     *
     * <p>The row is marked published once the send returns, which takes it out of every later
     * batch. A send that does not return leaves the row unpublished, and the next tick attempts the
     * row and this dead letter again.
     *
     * @param row      the row this relay cannot publish
     * @param metadata the four diagnostic values, each already held to its own width
     * @param deadline the pass deadline on the monotonic clock
     * @return {@code true} when the dead letter reached the broker
     */
    private boolean routeToDeadLetter(
            OutboxEventEntity row, DeadLetterMetadata metadata, long deadline) {
        try {
            Object deadLetter = metadata.toEnvelope(row.getAggregateId(), fraudAssessedTopic,
                    NO_SOURCE_PARTITION, NO_SOURCE_OFFSET, row.getEventId().toString(),
                    reportableEventType(row.getEventType()), row.getAttemptCount() + 1);

            sendWithinDeadline(deadLetterTopic, row.getAggregateId(), deadLetter, deadline);
        } catch (RuntimeException undelivered) {
            log.error("The dead letter for an outbox row of type {} did not reach topic {} after "
                    + "{}, and the row stays unpublished", row.getEventType(), deadLetterTopic,
                    rootCause(undelivered).getClass().getSimpleName());
            return false;
        }
        row.markPublished(Instant.now());
        outboxEvents.save(row);
        log.error("An outbox row of type {} reached topic {} and takes no further attempt",
                row.getEventType(), deadLetterTopic);
        return true;
    }

    /**
     * Sends one record and waits no longer than the time left in this relay pass.
     */
    private void sendWithinDeadline(String topic, String key, Object event, long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0L) {
            throw new RelayDeadlineExceededException();
        }
        try {
            kafkaTemplate.send(topic, key, event).get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CompletionException(interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            throw new CompletionException(cause == null ? failed : cause);
        } catch (TimeoutException timedOut) {
            throw new RelayDeadlineExceededException();
        }
    }

    /** Remaining nanoseconds before one pass must stop. */
    private static long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    /**
     * Reports whether one failure is permanent.
     *
     * <p>The serializer raises {@link SerializationException} for an event its schema document
     * refuses, and the mapper raises {@link JacksonException} for a payload that does not read into
     * its record. Neither improves on a later attempt, and a broker or a network fault carries
     * neither type.
     *
     * <p>The walk follows the cause chain, since a resolved send wraps its cause, and it ends on a
     * chain that names itself as its own cause.
     *
     * @param failure the failure the send raised
     * @return true when a later attempt fails the same way
     */
    private static boolean isPermanent(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SerializationException || cause instanceof JacksonException) {
                return true;
            }
            Throwable next = cause.getCause();
            cause = next == cause ? null : next;
        }
        return false;
    }

    /**
     * Returns the deepest cause of one failure.
     *
     * <p>A resolved send wraps its cause, so the outermost type names the completion and not the
     * fault. The value returned here is the type a log line reports and the type a dead letter
     * carries as its culprit. The walk ends on a chain that names itself as its own cause.
     *
     * @param failure the failure the send raised
     * @return the deepest cause, and {@code failure} itself when it wraps none
     */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Returns the event type a dead letter may report, or null.
     *
     * <p>A stored type outside {@link #REPORTABLE_EVENT_TYPE} travels as null, which the
     * {@code failedEventType} property of {@code schemas/dead-letter-v1.json} accepts. Carrying
     * such a type fails the dead letter on its own document.
     *
     * @param eventType the type the row holds
     * @return {@code eventType} when the dead-letter document takes it, and null otherwise
     */
    private static String reportableEventType(String eventType) {
        if (eventType == null || !REPORTABLE_EVENT_TYPE.matcher(eventType).matches()) {
            return null;
        }
        return eventType;
    }

    /**
     * Checks the configured batch size and returns it.
     *
     * @param batchSize rows one tick reads
     * @return {@code batchSize}
     * @throws IllegalArgumentException if {@code batchSize} is below one
     */
    private static int requireBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "carddemo.outbox.relay.batch-size holds at least one row and resolved to "
                            + batchSize);
        }
        return batchSize;
    }

    /** Validates and converts the configured pass deadline. */
    private static long requireMaxDurationNanos(long maxDurationMs) {
        if (maxDurationMs < 1L || maxDurationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException(
                    "carddemo.outbox.relay.max-duration-ms must be between 1 and "
                            + MAX_DURATION_MS);
        }
        return TimeUnit.MILLISECONDS.toNanos(maxDurationMs);
    }

    /**
     * Checks one configured topic name and returns it trimmed. No failure message holds the value.
     *
     * @param topic        the resolved property value
     * @param propertyName the property that supplied it, named in a failure
     * @return {@code topic}, trimmed
     * @throws IllegalArgumentException if {@code topic} is null or blank
     */
    private static String requireTopic(String topic, String propertyName) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Property " + propertyName + " resolved to no usable topic name.");
        }
        return topic.trim();
    }

    /**
     * Builds the one mapper this relay holds.
     *
     * <p>The setting that fails on an unknown property is enabled, matching the
     * {@code additionalProperties} of {@code false} both fraud schema documents set and the mapper
     * {@link OutboxWriter} writes with. No setting quotes an ordinary number, so
     * {@code schemaVersion} and {@code riskScore} read back as integers. No setting reads a date as
     * a number, so {@code occurredAt} and {@code assessedAt} read back from ISO-8601 text.
     *
     * @return the mapper, built once for each instance of this class
     */
    private static ObjectMapper eventMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Stable type used when the pass deadline expires; it carries no event value. */
    private static final class RelayDeadlineExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private RelayDeadlineExceededException() {
            super("outbox relay pass deadline exceeded");
        }
    }
}
