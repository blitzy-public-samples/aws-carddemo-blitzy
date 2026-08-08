package com.carddemo.ledger.outbox;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.config.LedgerProperties;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.entity.OutboxEventEntity.RelayState;
import com.carddemo.ledger.messaging.DeadLetterMetadata;
import com.carddemo.ledger.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the unpublished rows of {@code outbox_event} on a fixed delay, then marks each one
 * sent.
 *
 * <p>No COBOL ancestor. The one ancestor construct is the Customer Information Control System
 * (CICS) transient data queue write at {@code app/cbl/CORPT00C.cbl:L517-L518}, whose record a
 * separate job picks up later. That write is the single asynchronous handoff of the CardDemo
 * source. Neither the {@code outbox_event} row this relay reads nor the processed-event marker
 * each consumer writes has any counterpart there.
 *
 * <p>{@code outbox/OutboxWriter} stores those rows in its caller's transaction. The relay opens a
 * transaction of its own and publishes through the {@link KafkaTemplate} that
 * {@code config/KafkaProducerConfig} supplies. Every message is keyed on the eleven-digit account
 * identifier of {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, as text, so a
 * leading zero survives.
 *
 * <h2>Why one pass bounds itself</h2>
 *
 * <p>Every broker acknowledgement this pass waits for is awaited against one monotonic deadline
 * derived from {@code carddemo.outbox.relay.max-duration-ms}. Without it a broker that accepts a
 * connection and never answers holds the scheduled thread and the database connection this pass's
 * transaction owns for as long as the producer waits, which is the whole
 * {@code delivery.timeout.ms}. The relay runs on a fixed delay, so that one stall stops every later
 * event of every account behind it.
 *
 * <p>The producer window is configured to close one send inside the deadline anyway:
 * {@code max.block.ms} plus {@code delivery.timeout.ms} stays below {@code max-duration-ms}, and a
 * test asserts the relationship. The deadline is what keeps the guarantee true if either value is
 * later raised alone, and the cancellation on expiry is what stops this pass observing a send it has
 * given up on and would otherwise publish a second time.
 *
 * <p>Rationale: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class OutboxRelay {

    /** Diagnostic output of this class. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Reason recorded on a row whose claiming instance died before it finished. */
    private static final String CLAIM_EXPIRED = "ClaimExpired";

    /** Reason recorded on a row whose event type has no ledger destination. */
    private static final String UNKNOWN_EVENT_TYPE = "UnknownEventType";

    /** Holds the rows to publish, and records each publication. */
    private final OutboxEventRepository outboxEvents;

    /** Sends one event record to one topic, keyed on the account identifier. */
    private final KafkaTemplate<String, Object> ledgerEventTemplate;

    /** Reads one stored payload back into the record it was written from. */
    private final JsonMapper jsonMapper;

    /** Rows one sweep claims, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Each event type this service publishes, mapped to its destination. */
    private final Map<String, Destination> destinations;

    /** The boundary one sweep runs inside, so the claim holds and no counter joins it. */
    private final TransactionTemplate transactionTemplate;

    /** The recording surface, used after a sweep commits. */
    private final LedgerMeters meters;

    /** Base of the retry backoff, from {@code carddemo.outbox.relay.fixed-delay-ms}. */
    private final Duration sweepDelay;

    /** How long a claim may stand before another sweep recovers it, and the backoff ceiling. */
    private final Duration claimTimeout;

    /** What this instance writes into {@code claimed_by}. */
    private final String instanceId;

    /**
     * Wall-clock ceiling on one whole sweep, in nanoseconds, from
     * {@code carddemo.outbox.relay.max-duration-ms}.
     *
     * <p>Nanoseconds because the deadline is measured from {@link System#nanoTime()}, which is
     * monotonic. A wall-clock instant would move under a clock correction and could put the deadline
     * behind the moment the pass started.
     */
    private final long maxDurationNanos;

    /**
     * Where a row this relay gave up on is published, from
     * {@code carddemo.kafka.topics.dead-letter}.
     *
     * <p>The shared topic and not a source-specific one. {@code EventContracts} binds the dead-letter
     * envelope to this one topic, so it is the only topic the event template will serialize an
     * envelope to.
     */
    private final String deadLetterTopic;

    /** Stamps {@code published_at}, in Coordinated Universal Time. */
    private final Clock clock = Clock.systemUTC();

    /**
     * {@code ABEND-CODE} of a dead letter this relay publishes, four characters.
     *
     * <p>Distinct from the code a spent consumer record carries, so the two paths are separable on
     * the topic without reading anything else.
     */
    private static final String ABANDONED_ROW_ABEND_CODE = "OUTB";

    /** {@code ABEND-REASON} of a dead letter this relay publishes, at most fifty characters. */
    private static final String ABANDONED_ROW_REASON = "outbox row abandoned after the attempt "
            + "ceiling";

    /** {@code ABEND-MESSAGE} of a dead letter this relay publishes, at most seventy-two. */
    private static final String ABANDONED_ROW_MESSAGE = "row not published; inspect outbox_event by "
            + "the failed event identifier";

    /**
     * Source partition an outbox dead letter declares.
     *
     * <p>An outbox row never arrived on a partition, so there is no coordinate to report. The
     * envelope requires the component, and zero states the absence rather than inventing a location.
     */
    private static final int NO_SOURCE_PARTITION = 0;

    /** Source offset an outbox dead letter declares, absent for the same reason. */
    private static final long NO_SOURCE_OFFSET = 0L;

    /**
     * Takes the row store, the producer template, the payload reader and the configured names.
     *
     * @param outboxEvents             store of unpublished events
     * @param ledgerEventKafkaTemplate the template {@code config/KafkaProducerConfig} declares
     * @param jsonMapper               the framework-supplied mapper that reads a stored payload
     * @param transactionTemplate      boundary one sweep runs inside
     * @param properties               the bound {@code carddemo} settings
     * @param meters                   the recording surface of this service
     * @throws NullPointerException if a collaborator or a topic name is {@code null}
     */
    public OutboxRelay(OutboxEventRepository outboxEvents,
            @Qualifier("ledgerEventKafkaTemplate")
            KafkaTemplate<String, Object> ledgerEventKafkaTemplate,
            JsonMapper jsonMapper, TransactionTemplate transactionTemplate,
            LedgerProperties properties, LedgerMeters meters) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must be present");
        this.ledgerEventTemplate = Objects.requireNonNull(ledgerEventKafkaTemplate,
                "ledgerEventKafkaTemplate must be present");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must be present");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");

        LedgerProperties checked = Objects.requireNonNull(properties, "properties must be present");
        LedgerProperties.Outbox.Relay relay = checked.outbox().relay();
        LedgerProperties.Kafka.Topics topics = checked.kafka().topics();
        this.batchSize = relay.batchSize();
        this.sweepDelay = Duration.ofMillis(relay.fixedDelayMs());
        this.claimTimeout = relay.claimTimeout();
        this.instanceId = relay.instanceId();
        this.maxDurationNanos = Duration.ofMillis(relay.maxDurationMs()).toNanos();
        this.deadLetterTopic = Objects.requireNonNull(topics.deadLetter(),
                "the dead-letter topic must be present");
        this.destinations = Map.of(
                TransactionPosted.EVENT_TYPE,
                new Destination(topics.transactionPosted(), TransactionPosted.class));
    }

    /** Runs one claimed sweep and records its failures after the transaction commits. */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    public void publishPendingEvents() {
        long deadline = System.nanoTime() + maxDurationNanos;
        SweepResult result;
        try {
            result = transactionTemplate.execute(status -> sweepOnce(deadline));
        } catch (RuntimeException failure) {
            meters.recordFailure(LedgerMeters.PUBLISH_STAGE);
            log.warn("The ledger outbox sweep failed after {} and will run again",
                    rootCause(failure).getClass().getSimpleName());
            return;
        }
        Objects.requireNonNull(result, "the sweep must answer with a result").record(meters);
    }

    /**
     * Recovers stranded claims, claims due rows and publishes them in order.
     *
     * @param deadline monotonic deadline shared by every acknowledgement this sweep waits for
     * @return the work this committed sweep completed
     */
    private SweepResult sweepOnce(long deadline) {
        Instant now = clock.instant();
        int failed = recoverStrandedClaims(now);
        int published = 0;
        int abandoned = 0;

        List<OutboxEventEntity> claimed = outboxEvents.claimDueRows(now, Limit.of(batchSize));
        for (OutboxEventEntity row : claimed) {
            row.claim(instanceId, now);
            Destination destination = destinations.get(row.getEventType());

            if (destination == null) {
                row.recordFailure(UNKNOWN_EVENT_TYPE, now,
                        now.plus(backoffAfter(row.getAttemptCount())));
                outboxEvents.save(row);
                failed++;
                if (row.getRelayState() == RelayState.ABANDONED) {
                    abandoned++;
                }
                log.error("A ledger outbox row carries the unconfigured event type {}. "
                                + "Attempt {} of {}.", row.getEventType(), row.getAttemptCount(),
                        OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
                continue;
            }
            try {
                publishAndMark(row, destination, deadline);
            } catch (RuntimeException failure) {
                boolean abandonedNow = recordRefusedRow(row, failure, now, deadline);
                return new SweepResult(published, failed + 1,
                        abandonedNow ? abandoned + 1 : abandoned);
            }
            published++;
        }
        return new SweepResult(published, failed, abandoned);
    }

    /**
     * Returns claims left by a stopped instance to {@link RelayState#PENDING}.
     *
     * @param now the moment this sweep started
     * @return the number of recovered rows
     */
    private int recoverStrandedClaims(Instant now) {
        List<OutboxEventEntity> stranded =
                outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                        RelayState.CLAIMED, now.minus(claimTimeout), Limit.of(batchSize));
        for (OutboxEventEntity row : stranded) {
            row.recordFailure(CLAIM_EXPIRED, now, now);
            outboxEvents.save(row);
            log.warn("A ledger outbox claim expired for event type {}. Attempt {} of {}.",
                    row.getEventType(), row.getAttemptCount(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        }
        return stranded.size();
    }

    /**
     * Schedules another attempt for a row the broker refused.
     *
     * @param row      the refused row
     * @param failure  the publish failure
     * @param now      the moment this sweep started
     * @param deadline monotonic deadline shared by the whole sweep
     * @return {@code true} when this failure abandoned the row
     */
    private boolean recordRefusedRow(OutboxEventEntity row, RuntimeException failure, Instant now,
            long deadline) {
        String failureClass = rootCause(failure).getClass().getSimpleName();
        row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEvents.save(row);

        if (row.getRelayState() != RelayState.ABANDONED) {
            log.warn("A ledger outbox row of type {} stays unpublished after {}. "
                            + "Attempt {} of {}; the sweep stops here.",
                    row.getEventType(), failureClass, row.getAttemptCount(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
            return false;
        }

        publishDeadLetter(row, failure, deadline);
        log.error("A ledger outbox row of type {} was abandoned after {} attempts, the last failing "
                        + "with {}. One dead letter names it on {}.",
                row.getEventType(), row.getAttemptCount(), failureClass, deadLetterTopic);
        return true;
    }

    /**
     * Publishes one dead letter for a row this relay will not attempt again.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L707-L711} answers a write it cannot complete with four
     * statements that display one message, move 999 into an abend code and call {@code CEE3ABD},
     * terminating the address space and leaving the operator the job log. Abandoning one row and
     * naming it on a topic is the target form: the service keeps running and the row is still
     * accounted for.
     *
     * <p>The envelope is the governed form of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}, and it names the row rather than carrying its payload.
     * The four diagnostics say what failed; {@code failedEventId} and {@code failedEventType} say
     * which row, so an operator can reach it without the payload ever leaving this service.
     *
     * <p>The send is awaited, so a broker that refuses the dead letter is a failure of this sweep
     * rather than a silent loss. It is awaited against this pass's deadline like every other send,
     * and an expiry rolls the whole sweep back, which returns the row to the state it held before the
     * sweep so the next one attempts the abandonment again.
     *
     * @param row      the row this relay gave up on
     * @param failure  the failure of its last attempt
     * @param deadline monotonic deadline shared by the whole sweep
     */
    private void publishDeadLetter(OutboxEventEntity row, RuntimeException failure, long deadline) {
        Destination destination = destinations.get(row.getEventType());
        String sourceTopic = destination == null ? deadLetterTopic : destination.topic();

        DeadLetterEnvelope envelope = DeadLetterMetadata
                .fromFailure(ABANDONED_ROW_ABEND_CODE, rootCause(failure), ABANDONED_ROW_REASON,
                        ABANDONED_ROW_MESSAGE)
                .toEnvelope(row.getAggregateId(), sourceTopic, NO_SOURCE_PARTITION,
                        NO_SOURCE_OFFSET, row.getEventId().toString(), row.getEventType(),
                        row.getAttemptCount());

        await(ledgerEventTemplate.send(deadLetterTopic, row.getAggregateId(), envelope), deadline);
    }

    /**
     * Returns the configured exponential wait before another attempt.
     *
     * @param attemptsSoFar attempts recorded before the failure being scheduled
     * @return a wait no longer than the claim timeout
     */
    private Duration backoffAfter(int attemptsSoFar) {
        Duration doubled = sweepDelay;
        for (int step = 0; step < attemptsSoFar && doubled.compareTo(claimTimeout) < 0; step++) {
            doubled = doubled.multipliedBy(2L);
        }
        return doubled.compareTo(claimTimeout) > 0 ? claimTimeout : doubled;
    }

    /**
     * Publishes one row, then marks it sent.
     *
     * <p>The stored payload reads back into the event record its {@code event_type} names, and the
     * serializer of {@code config/KafkaProducerConfig} writes that record to the topic. The mark
     * follows the broker acknowledgement, so no row is marked for a message the broker never took.
     *
     * @param row         the unpublished row
     * @param destination the topic its event type travels on, and the record its payload holds
     * @param deadline    monotonic deadline shared by the whole sweep
     */
    private void publishAndMark(OutboxEventEntity row, Destination destination, long deadline) {
        Object event = jsonMapper.readValue(row.getPayload(), destination.eventClass());

        await(ledgerEventTemplate.send(destination.topic(), row.getAggregateId(), event), deadline);
        row.markPublished(clock.instant());
        outboxEvents.save(row);
    }

    /**
     * Waits for one broker acknowledgement without crossing this sweep's deadline.
     *
     * <p>The future is cancelled when the deadline expires, so this sweep stops observing a send it
     * has given up on. A send left running would be delivered later, and the retry this sweep
     * schedules would then publish the same event a second time.
     *
     * @param publication the broker acknowledgement
     * @param deadline    monotonic deadline shared by the whole sweep
     * @throws IllegalStateException when the wait is interrupted, or when the send failed with a
     *                               checked cause
     */
    private static void await(CompletableFuture<?> publication, long deadline) {
        Objects.requireNonNull(publication, "the producer returned no acknowledgement");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            throw new RelayDeadlineExceededException();
        }
        try {
            publication.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the ledger outbox relay was interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("a ledger outbox publication failed", cause);
        } catch (TimeoutException timedOut) {
            publication.cancel(true);
            throw new RelayDeadlineExceededException();
        }
    }

    /** Fixed exception used when the configured sweep deadline expires. */
    private static final class RelayDeadlineExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RelayDeadlineExceededException() {
            super("the ledger outbox relay sweep deadline expired");
        }
    }

    /** Returns the deepest cause of one failure. */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * What one sweep committed, carried outside the transaction for recording.
     *
     * <p>{@code failed} counts attempts this sweep could not complete and {@code abandoned} counts
     * rows it will not attempt again, so a row that reached the attempt ceiling raises both by one.
     * The two answer different questions and are recorded under different stages: a publish failure
     * is expected traffic that a later sweep may clear, and an abandoned row is terminal and needs an
     * operator.
     *
     * @param published rows this sweep marked sent
     * @param failed    attempts this sweep could not complete
     * @param abandoned rows that reached {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS}
     */
    private record SweepResult(int published, int failed, int abandoned) {

        void record(LedgerMeters meters) {
            if (published > 0) {
                meters.recordEventsPublished(published);
                log.debug("Published {} ledger outbox rows", published);
            }
            for (int failure = 0; failure < failed; failure++) {
                meters.recordFailure(LedgerMeters.PUBLISH_STAGE);
            }
            for (int terminal = 0; terminal < abandoned; terminal++) {
                meters.recordAbandonedRow();
            }
        }
    }

    /**
     * Where one event type goes, and what its stored payload holds.
     *
     * @param topic      the topic that event type travels on
     * @param eventClass the record the payload reads back into
     */
    private record Destination(String topic, Class<?> eventClass) {

        Destination {
            Objects.requireNonNull(topic, "topic must be present");
            Objects.requireNonNull(eventClass, "eventClass must be present");
        }
    }
}
