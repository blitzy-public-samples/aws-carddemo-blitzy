package com.carddemo.ledger.outbox;

import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.config.LedgerProperties;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.entity.OutboxEventEntity.RelayState;
import com.carddemo.ledger.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Stamps {@code published_at}, in Coordinated Universal Time. */
    private final Clock clock = Clock.systemUTC();

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
        this.destinations = Map.of(
                TransactionPosted.EVENT_TYPE,
                new Destination(topics.transactionPosted(), TransactionPosted.class),
                TransactionDeclined.EVENT_TYPE,
                new Destination(topics.transactionDeclined(), TransactionDeclined.class));
    }

    /** Runs one claimed sweep and records its failures after the transaction commits. */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    public void publishPendingEvents() {
        SweepResult result;
        try {
            result = transactionTemplate.execute(status -> sweepOnce());
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
     * @return the work this committed sweep completed
     */
    private SweepResult sweepOnce() {
        Instant now = clock.instant();
        int failed = recoverStrandedClaims(now);
        int published = 0;

        List<OutboxEventEntity> claimed = outboxEvents.claimDueRows(now, Limit.of(batchSize));
        for (OutboxEventEntity row : claimed) {
            row.claim(instanceId, now);
            Destination destination = destinations.get(row.getEventType());

            if (destination == null) {
                row.recordFailure(UNKNOWN_EVENT_TYPE, now,
                        now.plus(backoffAfter(row.getAttemptCount())));
                outboxEvents.save(row);
                failed++;
                log.error("A ledger outbox row carries the unconfigured event type {}. "
                                + "Attempt {} of {}.", row.getEventType(), row.getAttemptCount(),
                        OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
                continue;
            }
            try {
                publishAndMark(row, destination);
            } catch (RuntimeException failure) {
                recordRefusedRow(row, failure, now);
                return new SweepResult(published, failed + 1);
            }
            published++;
        }
        return new SweepResult(published, failed);
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
     * @param row     the refused row
     * @param failure the publish failure
     * @param now     the moment this sweep started
     */
    private void recordRefusedRow(OutboxEventEntity row, RuntimeException failure, Instant now) {
        String failureClass = rootCause(failure).getClass().getSimpleName();
        row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEvents.save(row);
        log.warn("A ledger outbox row of type {} stays unpublished after {}. "
                        + "Attempt {} of {}; the sweep stops here.",
                row.getEventType(), failureClass, row.getAttemptCount(),
                OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
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
     */
    private void publishAndMark(OutboxEventEntity row, Destination destination) {
        Object event = jsonMapper.readValue(row.getPayload(), destination.eventClass());

        ledgerEventTemplate.send(destination.topic(), row.getAggregateId(), event).join();
        row.markPublished(clock.instant());
        outboxEvents.save(row);
    }

    /** Returns the deepest cause of one failure. */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** What one sweep committed, carried outside the transaction for recording. */
    private record SweepResult(int published, int failed) {

        void record(LedgerMeters meters) {
            if (published > 0) {
                log.debug("Published {} ledger outbox rows", published);
            }
            for (int failure = 0; failure < failed; failure++) {
                meters.recordFailure(LedgerMeters.PUBLISH_STAGE);
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
