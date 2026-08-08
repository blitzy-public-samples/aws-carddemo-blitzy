package com.carddemo.fraud.outbox;

import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.messaging.DeadLetterMetadata;
import com.carddemo.fraud.messaging.EventPublisherPort;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
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
 * <h2>The row that runs out of attempts</h2>
 *
 * <p>A row the broker keeps refusing is a third case, and it used to be the silent one. After
 * {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts the row becomes
 * {@link OutboxEventEntity.RelayState#ABANDONED}, and since the claim query returns
 * {@link OutboxEventEntity.RelayState#PENDING} rows only, the tick that abandoned it was the last
 * tick to look at it. The assessment reached no consumer and nothing named it anywhere but this
 * container's log.
 *
 * <p>That gap costs more in this service than in its siblings. Fraud detection is ADDITIVE, so
 * there is no batch job an operator can re-run and no reject dataset holding what was missed: the
 * published assessment is the only record that the rules ever ran on a transaction. Abandonment
 * therefore records a durable obligation on the row itself, in the same write that abandons it, and
 * this relay discharges that obligation by naming the row on the dead-letter topic. The head of
 * every pass offers each obligation still outstanding, so a broker outage that swallows the
 * diagnostic delays it rather than losing it.
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

    /** Classification a dead letter carries for a row whose delivery attempts are spent. */
    private static final String ABANDONED_REASON = "delivery attempts spent, row abandoned";

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
     * The one seam this relay reaches a broker through.
     *
     * <p>{@code messaging/KafkaEventPublisher} is the shipped implementation and the only class in this
     * service that holds a broker client. This relay used to hold the {@code KafkaTemplate} itself,
     * which named Kafka in its own signature and made the broker a compile-time dependency of the
     * relay; every other producing service on this platform already published through a port. The
     * payload is written, validated against its schema document and checked against its topic inside
     * the configured serializer, which is why an event record travels through here rather than text.
     */
    private final EventPublisherPort publisher;

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
     * <p>This is a wall-time limit on the pass and not a ceiling per send. A broker that accepts a
     * connection and never answers would otherwise hold the scheduled thread for the life of the
     * process. A pass issues no new send once this budget is spent, and a send it has issued waits
     * only for whatever of the budget is left, so the pass ends inside its stated bound rather than
     * one send window past it. The rows a pass did not reach stay due and the next tick starts fresh.
     * The shipped producer settings resolve one send well inside this value, and
     * {@code src/main/resources/application.yml} states that relationship where it declares them.
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
     * @param publisher          the one seam both topics are reached through
     * @param meters             the recording surface of this service
     * @param fraudAssessedTopic topic both assessment outcomes travel on, from
     *                           {@code carddemo.kafka.topics.fraud-assessed}
     * @param deadLetterTopic    topic an unpublishable row travels on, from
     *                           {@code carddemo.kafka.topics.dead-letter}
     * @param transactionTemplate the boundary one tick runs inside
     * @param properties          the bound {@code carddemo} block, read for the relay settings
     * @throws NullPointerException     if the store, the publisher or the meters is null
     * @throws IllegalArgumentException if the batch size or duration is outside its accepted range,
     *                                  or if either topic name resolves to no usable value
     */
    public OutboxRelay(OutboxEventRepository outboxEvents,
            EventPublisherPort publisher,
            FraudMeters meters,
            @Value("${carddemo.kafka.topics.fraud-assessed:fraud.assessed}")
                    String fraudAssessedTopic,
            @Value("${carddemo.kafka.topics.dead-letter:carddemo.dead-letter}")
                    String deadLetterTopic,
            TransactionTemplate transactionTemplate,
            FraudProperties properties) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must be present");
        this.publisher = Objects.requireNonNull(publisher, "publisher must be present");
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
        Owed owed = dischargeOwedDiagnostics(deadline);
        int failed = recoverStrandedClaims(now);

        List<OutboxEventEntity> due = outboxEvents.claimDueRows(now, Limit.of(batchSize));
        if (due.isEmpty() && failed == 0) {
            return new TickResult(0, 0, owed.published(), owed.failed(), 0);
        }

        int published = 0;
        int abandoned = 0;
        int deadLettersPublished = owed.published();
        int deadLettersFailed = owed.failed();
        for (OutboxEventEntity row : due) {
            row.claim(instanceId, now);
            Class<?> recordType = recordTypesByEventType.get(row.getEventType());
            if (recordType == null) {
                failed++;
                abandoned++;
                if (routeToDeadLetter(row, DeadLetterMetadata.of(ABEND_CODE, CULPRIT,
                        UNKNOWN_TYPE_REASON, UNKNOWN_TYPE_MESSAGE), UNKNOWN_TYPE_REASON,
                        deadline)) {
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
                abandoned += terminal.abandoned();
                if (terminal.rowIsClosed()) {
                    continue;
                }
                log.info("Outbox relay published {} rows this tick and failed {}", published, failed);
                return new TickResult(published, failed, deadLettersPublished, deadLettersFailed,
                        abandoned);
            }
        }
        log.info("Outbox relay published {} rows this tick and failed {}", published, failed);
        return new TickResult(published, failed, deadLettersPublished, deadLettersFailed, abandoned);
    }

    /**
     * Offers the dead-letter topic every diagnostic an earlier pass left owing, oldest first.
     *
     * <p>This runs before the claim, and it is the reason an abandonment is delayed rather than lost.
     * A row records its obligation in the same write that abandons it, so the obligation survives the
     * broker outage, the restart or the redeployment that stopped the diagnostic reaching a topic.
     * Nothing else would ever look at the row again: it is {@link OutboxEventEntity.RelayState#ABANDONED}
     * and {@link OutboxEventEntity#claimDueRows} returns {@link OutboxEventEntity.RelayState#PENDING}
     * rows only.
     *
     * <p>The set is empty while the relay is healthy, and the partial index
     * {@code ix_outbox_event_dead_letter_required} covers exactly it, so a pass that has nothing to
     * report pays for one indexed read of no rows.
     *
     * <p>A diagnostic the broker refuses again leaves the obligation standing, so the next pass
     * offers it once more. Only an acknowledgement clears it.
     *
     * @param deadline the pass deadline on the monotonic clock
     * @return how many owed diagnostics this pass named and how many it could not
     */
    private Owed dischargeOwedDiagnostics(long deadline) {
        List<OutboxEventEntity> owing = outboxEvents.findByDeadLetterStateOrderByLastAttemptAtAsc(
                OutboxEventEntity.DeadLetterState.REQUIRED, Limit.of(batchSize));
        if (owing.isEmpty()) {
            return Owed.NONE;
        }

        int named = 0;
        int unnamed = 0;
        for (OutboxEventEntity row : owing) {
            if (nameAbandonedRow(row, row.getLastError(), deadline)) {
                named++;
            } else {
                unnamed++;
            }
        }
        log.warn("Outbox relay named {} abandoned rows on topic {} that an earlier pass left owing,"
                + " and {} still owe one", named, deadLetterTopic, unnamed);
        return new Owed(named, unnamed);
    }

    /**
     * Names one abandoned row on the dead-letter topic and clears its obligation on acknowledgement.
     *
     * <p>The row cannot be marked published: it is {@link OutboxEventEntity.RelayState#ABANDONED},
     * which is terminal, and the event it holds was never delivered. What is recorded instead is that
     * the diagnostic reached the broker, so a later pass stops offering it. That distinction is the
     * whole point of a separate column: a reader can tell a delivered event from an event that was
     * given up on and merely reported.
     *
     * <p>The dead letter carries the four diagnostic values, the topic the row was bound for, the
     * event identifier and the attempts it took. It carries no property of the payload.
     *
     * @param row          the abandoned row owing a diagnostic
     * @param failureClass the last failure recorded on the row, or null when none was
     * @param deadline     the pass deadline on the monotonic clock
     * @return {@code true} when the broker acknowledged the diagnostic
     */
    private boolean nameAbandonedRow(OutboxEventEntity row, String failureClass, long deadline) {
        try {
            Object deadLetter = DeadLetterMetadata
                    .of(ABEND_CODE, CULPRIT, ABANDONED_REASON, abandonedMessage(failureClass))
                    .toEnvelope(row.getAggregateId(), fraudAssessedTopic, NO_SOURCE_PARTITION,
                            NO_SOURCE_OFFSET, row.getEventId().toString(),
                            reportableEventType(row.getEventType()), row.getAttemptCount());

            sendWithinDeadline(deadLetterTopic, row.getAggregateId(), deadLetter, deadline);
        } catch (RuntimeException undelivered) {
            log.error("The diagnostic naming an abandoned outbox row of type {} did not reach topic"
                            + " {} after {}. The row still owes one and a later pass offers it"
                            + " again.", row.getEventType(), deadLetterTopic,
                    rootCause(undelivered).getClass().getSimpleName());
            return false;
        }
        row.markDeadLetterPublished(Instant.now());
        outboxEvents.save(row);
        log.error("An abandoned outbox row of type {} is named on topic {}. Its assessment reached"
                + " no consumer.", row.getEventType(), deadLetterTopic);
        return true;
    }

    /**
     * Returns the failing-pointer text a diagnostic carries for an abandoned row.
     *
     * <p>It names the attempt ceiling and the last failure class and nothing else. The class name is
     * a type, never a value, so the text quotes no property of the payload and no message key.
     *
     * @param failureClass the last failure recorded on the row, or null when none was
     * @return the text, which {@link DeadLetterMetadata} holds to its own width
     */
    private static String abandonedMessage(String failureClass) {
        return "spent " + OutboxEventEntity.MAX_DELIVERY_ATTEMPTS + " attempts, last "
                + (failureClass == null || failureClass.isBlank() ? "unrecorded" : failureClass);
    }

    /**
     * What the owed-diagnostic pass at the head of one tick achieved.
     *
     * <p>Both components count ROWS, and each row appears in exactly one of them.
     *
     * @param published owed diagnostics the broker acknowledged on this pass
     * @param failed    owed diagnostics the broker refused again, still owing
     */
    private record Owed(int published, int failed) {

        /** Nothing was owed, which is the reading of a healthy relay. */
        private static final Owed NONE = new Owed(0, 0);
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
     * <p>{@code failed} counts ATTEMPTS and the three terminal components count RECORDS, which is why
     * they are separate components rather than one total. A row that will be attempted again appears
     * in {@code failed} alone; a row this relay can never publish appears in {@code failed} once and
     * in exactly one of the two dead-letter components. Nothing is counted twice, and a reader can
     * tell a retry from a permanent loss without opening a log.
     *
     * <p>{@code abandoned} counts the rows this tick gave up on, by either of the two routes: one whose
     * attempts ran out, and one whose failure is permanent and which is therefore given up on outright.
     * It is deliberately independent of the two dead-letter components rather than derived from them. An
     * abandonment whose diagnostic the broker refused raises {@code abandoned} and
     * {@code deadLettersFailed}, and a later pass that finally names it raises
     * {@code deadLettersPublished} and not {@code abandoned} again. Comparing the two readings over time
     * therefore answers whether every row this service gave up on has been named somewhere, which one
     * combined total could not.
     *
     * @param published            rows the broker accepted and this tick marked
     * @param failed               publish attempts this tick could not complete
     * @param deadLettersPublished rows this relay gave up on whose diagnostic the broker acknowledged
     * @param deadLettersFailed    rows this relay gave up on whose diagnostic the broker refused
     * @param abandoned            rows this tick gave up on, whether named or still owing
     */
    private record TickResult(int published, int failed, int deadLettersPublished,
            int deadLettersFailed, int abandoned) {

        /**
         * Records this result against {@code meters}.
         *
         * @param meters the recording surface of this service
         */
        void record(FraudMeters meters) {
            meters.recordEventsPublished(published);
            for (int failure = 0; failure < failed; failure++) {
                meters.recordPublishFailure();
            }
            for (int named = 0; named < deadLettersPublished; named++) {
                meters.recordDeadLetterPublished();
            }
            for (int refused = 0; refused < deadLettersFailed; refused++) {
                meters.recordDeadLetterFailure();
            }
            for (int spent = 0; spent < abandoned; spent++) {
                meters.recordOutboxAbandoned();
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
     * <p>Unless that attempt was the last one. A row at its attempt ceiling is abandoned, which the
     * recorded failure itself decides, and an abandoned row is never claimed again. It is therefore
     * named on the dead-letter topic here, and the tick carries on rather than stopping: the ordering
     * the message key protects is already lost once one event of an account is given up on, and
     * holding the rows behind it would give up on those too.
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
                    CONTRACT_REASON, CONTRACT_MESSAGE), cause.getClass().getSimpleName(), deadline)
                    ? Terminal.ABANDONED_NAMED
                    : Terminal.ABANDONED_UNNAMED;
        }
        String failureClass = cause.getClass().getSimpleName();
        row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEvents.save(row);

        if (row.owesDeadLetter()) {
            // That attempt was the last one. The row is now ABANDONED and the claim query will never
            // return it again, so this is the only moment at which anything still holds it. The
            // obligation was recorded in the same write as the abandonment, so a diagnostic refused
            // here is delayed to a later pass rather than lost.
            boolean named = nameAbandonedRow(row, failureClass, deadline);
            log.error("An outbox row of type {} spent all {} attempts, the last on {}, and its"
                            + " assessment reaches no consumer.", row.getEventType(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS, failureClass);
            return named ? Terminal.ABANDONED_NAMED : Terminal.ABANDONED_UNNAMED;
        }

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
     * @param abandoned   1 when this failure spent the row's last attempt, otherwise 0
     * @param rowIsClosed whether the row takes no further attempt
     */
    private record Terminal(int published, int failed, int abandoned, boolean rowIsClosed) {

        /** The row stays open and becomes due again after a backoff. */
        private static final Terminal RETRY = new Terminal(0, 0, 0, false);

        /**
         * The row was given up on and its diagnostic reached the broker.
         *
         * <p>Reached two ways, and both are abandonments. A row at its attempt ceiling is abandoned by
         * the failure it just recorded; a row whose failure is permanent is abandoned outright, because
         * a payload its schema refuses fails the same way every later time. Both count here: an event
         * that reaches no consumer is an abandonment whichever of the two closed it, and reporting one
         * of them as a publish is what made the metric untrue.
         *
         * <p>Closed, so the tick carries on. The ordering the message key protects is already lost once
         * a row is abandoned, and holding the rows behind it would lose them too.
         */
        private static final Terminal ABANDONED_NAMED = new Terminal(1, 0, 1, true);

        /**
         * The row was given up on and its diagnostic was refused.
         *
         * <p>Closed to this tick, and the obligation stands. The head of a later pass offers the
         * diagnostic again, which is what the durable {@code dead_letter_state} column buys.
         */
        private static final Terminal ABANDONED_UNNAMED = new Terminal(0, 1, 1, true);
    }

    /**
     * Sends one dead letter for a row no tick can publish, then closes that row.
     *
     * <p>The dead letter carries the four diagnostic values, the topic the row was bound for, the
     * event identifier and the attempt this tick made. It carries no property of the failing
     * payload. Its message key is the account identifier of the row, so a dead letter lands on the
     * partition of the account it concerns.
     *
     * <p><strong>The row is abandoned before the diagnostic is sent, and it is never marked
     * published.</strong> This method used to call {@code markPublished} once the send returned, which
     * recorded an event that reached no consumer as {@link OutboxEventEntity.RelayState#PUBLISHED}: in
     * the table, in the retention sweep and in every metric it was then indistinguishable from an
     * assessment the broker acknowledged, and the only thing that had actually been published was the
     * diagnostic saying it had not been. {@link OutboxEventEntity#abandon(String, Instant)} writes the
     * truthful state instead, and it writes it first, so the obligation is durable before anything is
     * attempted. A diagnostic the broker refuses therefore leaves
     * {@link OutboxEventEntity.DeadLetterState#REQUIRED} standing and
     * {@link #dischargeOwedDiagnostics(long)} offers it again at the head of a later pass; a crash
     * between the two writes lands in the same place. Only an acknowledgement clears it.
     *
     * @param row          the row this relay cannot publish
     * @param metadata     the four diagnostic values, each already held to its own width
     * @param failureClass the failure recorded on the row, naming a type and never a value
     * @param deadline     the pass deadline on the monotonic clock
     * @return {@code true} when the dead letter reached the broker
     */
    private boolean routeToDeadLetter(OutboxEventEntity row, DeadLetterMetadata metadata,
            String failureClass, long deadline) {

        row.abandon(failureClass, Instant.now());
        outboxEvents.save(row);
        try {
            Object deadLetter = metadata.toEnvelope(row.getAggregateId(), fraudAssessedTopic,
                    NO_SOURCE_PARTITION, NO_SOURCE_OFFSET, row.getEventId().toString(),
                    reportableEventType(row.getEventType()), row.getAttemptCount());

            sendWithinDeadline(deadLetterTopic, row.getAggregateId(), deadLetter, deadline);
        } catch (RuntimeException undelivered) {
            log.error("An outbox row of type {} is abandoned and its dead letter did not reach topic"
                            + " {} after {}. The row still owes one and a later pass offers it"
                            + " again.", row.getEventType(), deadLetterTopic,
                    rootCause(undelivered).getClass().getSimpleName());
            return false;
        }
        row.markDeadLetterPublished(Instant.now());
        outboxEvents.save(row);
        log.error("An outbox row of type {} is abandoned and named on topic {}. Its assessment"
                + " reaches no consumer.", row.getEventType(), deadLetterTopic);
        return true;
    }

    /**
     * Sends one record, and waits for that send to resolve.
     *
     * <p>The wait is the budget the pass has left, and nothing longer.
     * {@code carddemo.outbox.relay.max-duration-ms} bounds one whole sweep, so waiting that value per
     * send let a send admitted a millisecond before the deadline keep the sweep running for another
     * whole window: the setting named a wall-time limit and behaved as a per-send ceiling, and a sweep
     * could overrun by close to twice its stated bound. The account service's relay already waited
     * only the remaining budget, so the two relays now read the same setting the same way.
     *
     * <p>The deadline is read before the send as well, so a tick with no budget left issues no send at
     * all. A send the producer still holds when the budget runs out raises the deadline failure and
     * leaves the row unpublished, and the next sweep offers it again; each consumer's processed-event
     * marker absorbs the duplicate that a late delivery of the first copy would otherwise cause.
     *
     * @throws RelayDeadlineExceededException when the pass has no budget left, or when a send outlived
     *                                        the budget that was left
     */
    private void sendWithinDeadline(String topic, String key, Object event, long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0L) {
            throw new RelayDeadlineExceededException();
        }
        try {
            publisher.publish(topic, key, event).toCompletableFuture()
                    .get(remaining, TimeUnit.NANOSECONDS);
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
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} governs reading, which is all this mapper does: it
     * refuses a stored payload carrying a property the event record does not declare. What closes
     * the written document is the {@code additionalProperties} of {@code false} in both fraud schema
     * documents, applied by {@code com.carddemo.events.serde.EventContracts} before
     * {@link OutboxWriter} saves a row. No setting quotes an ordinary number, so
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
