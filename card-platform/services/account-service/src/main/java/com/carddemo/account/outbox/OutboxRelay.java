package com.carddemo.account.outbox;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.ObservabilityConfig.AccountMeters;
import com.carddemo.account.entity.OutboxEventEntity.RelayState;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.DeadLetterMetadata;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.OutboxEventRepository;
import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.serde.EventContracts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes {@code outbox_event} rows that are due, one claimed batch per sweep, and marks each one
 * the broker accepted.
 *
 * <p>No COBOL ancestor. The one ancestor construct is the Customer Information Control System
 * (CICS) Transient Data Queue write of the paragraph {@code WIRTE-JOBSUB-TDQ} at {@code
 * app/cbl/CORPT00C.cbl:L515-L523}. That write is the single asynchronous handoff in the CardDemo
 * source.
 *
 * <p>The message key is the account identifier, eleven digits wide, from
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. The {@code payload} column
 * holds one event already serialized as JavaScript Object Notation (JSON). This class passes that
 * stored text through and reads no field of it.
 *
 * <h2>What one sweep does</h2>
 *
 * <p>A sweep recovers stranded claims, claims the rows that are due, then publishes them in order.
 * All of it runs in one transaction, because the claim depends on it: the claim query holds each row
 * it returns with {@code FOR UPDATE SKIP LOCKED}, and that lock lives exactly as long as the
 * transaction that took it. Two relay instances sweeping the same table therefore return disjoint
 * batches and neither waits for the other.
 *
 * <p>The claim query returns at most the head row of each account. A refusal schedules that account
 * head for another attempt while the sweep continues with other accounts; no later event of the
 * failed account is in the batch, so per-account ordering remains intact without one account
 * blocking the whole table.
 *
 * <p>A row that keeps failing does not block the table for ever. Each failure raises the attempt
 * count and pushes {@code next_attempt_at} further out, and
 * {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts move the row to
 * {@link RelayState#ABANDONED}, which the claim query does not return.
 *
 * <p>Nothing here records a meter. A counter takes no part in a database transaction, so an
 * increment inside one survives a rollback and reports work that did not commit. The sweep returns
 * what it did and {@link #publishPendingEvents()} records it once the transaction has committed.
 *
 * <p>Scheduling is enabled on {@code AccountApplication}, and the sweep below depends on it.
 */
@Component
public class OutboxRelay {

    /** Diagnostic output of this class. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Reason recorded on a row whose claiming instance died before it finished. */
    private static final String CLAIM_EXPIRED = "ClaimExpired";

    /** Failure code carried by a terminal outbox recovery. */
    private static final String DEAD_LETTER_CODE = "0902";

    /** Failure classification carried by a terminal outbox recovery. */
    private static final String DEAD_LETTER_REASON = "outbox publish exhausted";

    /** Operator detail carried by a terminal outbox recovery. */
    private static final String DEAD_LETTER_MESSAGE = "outbox event exhausted automatic attempts";

    /** Access to the {@code outbox_event} rows of this service. */
    private final OutboxEventRepository outboxEventRepository;

    /** The event bus every account event travels through. */
    private final EventPublisherPort eventPublisherPort;

    /** Destination of an account-state event. */
    private final String accountStateChangedTopic;

    /** Destination of a customer-context event. */
    private final String customerContextChangedTopic;

    /** The boundary one sweep runs inside, so the claim holds and no meter joins it. */
    private final TransactionTemplate transactionTemplate;

    /** The recording surface, written to only after a sweep has committed. */
    private final AccountMeters meters;

    /** Rows one sweep claims, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Base of the retry backoff, from {@code carddemo.outbox.relay.fixed-delay-ms}. */
    private final Duration sweepDelay;

    /** How long a claim may stand before another sweep recovers it, and the backoff ceiling. */
    private final Duration claimTimeout;

    /** What this instance writes into {@code claimed_by}, so a claim can be traced to a process. */
    private final String instanceId;

    /** Total time one sweep may wait on broker acknowledgements, on the monotonic clock. */
    private final long maxDurationNanos;

    /** Topic a row that exhausted its attempts reports itself on. */
    private final String deadLetterTopic;

    /** Renders a schema-governed dead-letter envelope into its wire form. */
    private final ObjectMapper objectMapper;

    /** Supplies the moment one sweep reads, so a test can fix it. */
    private final Clock clock;

    /**
     * Wires this relay to its table, its event bus, its topic, its transaction boundary and its
     * meters.
     *
     * <p>Every tunable arrives through {@link AccountProperties}, which validates each one at
     * start-up. Nothing here is hard-coded: a delay or a batch size written into this class would
     * silently ignore the shipped file, and a reader comparing the two would have no way to tell
     * which one the running service used.
     *
     * @param outboxEventRepository    access to the {@code outbox_event} rows of this service
     * @param eventPublisherPort       the event bus every account event travels through
     * @param transactionTemplate      the boundary one sweep runs inside
     * @param properties               the bound {@code carddemo} block, read for the relay settings
     * @param objectMapper             renders the terminal dead-letter envelope, resolved by name
     * @param meters                   the recording surface of this service
     * @throws NullPointerException if any argument is null
     */
    @Autowired
    public OutboxRelay(OutboxEventRepository outboxEventRepository,
            EventPublisherPort eventPublisherPort,
            TransactionTemplate transactionTemplate,
            AccountProperties properties,
            @Qualifier("accountEventObjectMapper") ObjectMapper objectMapper,
            AccountMeters meters) {
        this(outboxEventRepository, eventPublisherPort, transactionTemplate, properties,
                objectMapper, meters, Clock.systemUTC(),
                Objects.requireNonNull(properties, "properties").outbox().relay().instanceId());
    }

    /**
     * Wires a relay on an explicit clock and instance identifier.
     *
     * <p>Two settings arrive as arguments rather than from {@link AccountProperties}, so a test can
     * fix the moment a sweep reads and can run two instances of one configuration against one table.
     * Nothing else differs from the constructor above, which supplies the wall clock and the
     * configured identifier.
     *
     * @param outboxEventRepository access to the {@code outbox_event} rows of this service
     * @param eventPublisherPort    the event bus every account event travels through
     * @param transactionTemplate   the boundary one sweep runs inside
     * @param properties            the bound {@code carddemo} block, read for the relay settings
     * @param objectMapper          renders the terminal dead-letter envelope
     * @param meters                the recording surface of this service
     * @param clock                 supplies the moment one sweep reads
     * @param instanceId            what this instance writes into {@code claimed_by}
     * @throws NullPointerException if any argument is null
     */
    OutboxRelay(OutboxEventRepository outboxEventRepository,
            EventPublisherPort eventPublisherPort,
            TransactionTemplate transactionTemplate,
            AccountProperties properties,
            ObjectMapper objectMapper,
            AccountMeters meters,
            Clock clock,
            String instanceId) {
        this.outboxEventRepository =
                Objects.requireNonNull(outboxEventRepository, "outboxEventRepository");
        this.eventPublisherPort = Objects.requireNonNull(eventPublisherPort, "eventPublisherPort");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");

        AccountProperties checked = Objects.requireNonNull(properties, "properties");
        this.accountStateChangedTopic = checked.kafka().topics().accountStateChanged();
        this.customerContextChangedTopic = checked.kafka().topics().customerContextChanged();
        this.deadLetterTopic = checked.kafka().topics().deadLetter();
        AccountProperties.Outbox.Relay relay = checked.outbox().relay();
        this.batchSize = relay.batchSize();
        this.sweepDelay = Duration.ofMillis(relay.fixedDelayMs());
        this.claimTimeout = relay.claimTimeout();
        this.maxDurationNanos = Duration.ofMillis(relay.maxDurationMs()).toNanos();
    }

    /**
     * Runs one sweep, then records what it published once that sweep has committed.
     *
     * <p>The transaction boundary is explicit rather than an annotation on this method. An annotated
     * method called from the scheduler would be proxied correctly, but the counters below would then
     * sit inside the transaction, and a commit that failed afterwards would leave a count of rows
     * that were never published.
     *
     * <p>A sweep that cannot commit throws, the scheduler logs it, and nothing is counted — which is
     * the honest outcome, because nothing was published.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms:500}")
    public void publishPendingEvents() {
        SweepResult result = transactionTemplate.execute(status -> sweepOnce());

        Objects.requireNonNull(result, "the sweep must answer with a result").record(meters);
    }

    /**
     * Recovers stranded claims, claims the due rows and publishes them in order.
     *
     * @return what this sweep published and how many rows it failed on
     */
    private SweepResult sweepOnce() {
        Instant now = clock.instant();
        long deadline = System.nanoTime() + maxDurationNanos;
        int failed = recoverStrandedClaims(now);

        List<OutboxEventEntity> due = outboxEventRepository.claimDueRows(now, Limit.of(batchSize));
        int published = 0;
        for (OutboxEventEntity row : due) {
            row.claim(instanceId, now);
            String destination = topicFor(row.getEventType());
            try {
                await(eventPublisherPort.publish(destination, row.getAggregateId(),
                        row.getPayload()), deadline);
                row.markPublished(now);
                outboxEventRepository.save(row);
                published = published + 1;
            } catch (RuntimeException failure) {
                recordRefusedRow(row, destination, failure, now);
                failed = failed + 1;
            }
        }
        return new SweepResult(published, failed);
    }

    /**
     * Returns rows a dead instance left claimed to {@link RelayState#PENDING}.
     *
     * <p>Without this one crash costs one event permanently: the row stays {@link RelayState#CLAIMED},
     * the claim query filters on {@link RelayState#PENDING}, and nothing looks at it again. The
     * recovery counts as an attempt, so a row that strands repeatedly is eventually abandoned rather
     * than recovered for ever.
     *
     * @param now the moment this sweep started
     */
    private int recoverStrandedClaims(Instant now) {
        List<OutboxEventEntity> stranded =
                outboxEventRepository.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                        RelayState.CLAIMED, now.minus(claimTimeout), Limit.of(batchSize));

        for (OutboxEventEntity row : stranded) {
            row.recordFailure(CLAIM_EXPIRED, now, now);
            outboxEventRepository.save(row);
            log.warn("Outbox event {} was claimed by an instance that did not finish, so it is due "
                    + "again. Attempt {} of {}.", row.getEventId(), row.getAttemptCount(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        }
        return stranded.size();
    }

    /**
     * Schedules another attempt for one refused row, and logs the refusal.
     *
     * <p>Neither the stored reason nor the log line carries anything but the failure's class name.
     * A database or broker failure message can quote the row it was raised for, and this row's
     * payload holds a credit limit, a cycle balance and an account identifier. A class name says
     * which kind of failure occurred and cannot carry any of that.
     *
     * @param row         the row the broker refused
     * @param destination the topic selected from the stored event type
     * @param failure     the failure the publish raised
     * @param now         the moment this sweep started
     */
    private void recordRefusedRow(OutboxEventEntity row, String destination,
            RuntimeException failure, Instant now) {
        String failureClass = rootCause(failure).getClass().getSimpleName();
        row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEventRepository.save(row);

        log.warn("Outbox event {} did not reach topic {} after a {}. Attempt {} of {}; that "
                        + "account waits behind this row while other account heads may continue.",
                row.getEventId(), destination, failureClass, row.getAttemptCount(),
                OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);

        if (row.getRelayState() == RelayState.ABANDONED) {
            publishDeadLetter(row, destination, failure);
        }
    }

    /** Resolves the configured destination of one governed event type. */
    private String topicFor(String eventType) {
        return switch (eventType) {
            case EventContracts.ACCOUNT_STATE_CHANGED -> accountStateChangedTopic;
            case EventContracts.CUSTOMER_CONTEXT_CHANGED -> customerContextChangedTopic;
            default -> throw new IllegalArgumentException(
                    "the account outbox has no configured topic for eventType " + eventType);
        };
    }

    /**
     * Returns how long to wait before attempting a row again.
     *
     * <p>The wait doubles per attempt from the sweep delay and stops at the claim timeout, so a row
     * the broker keeps refusing is retried less and less often without ever falling out of the
     * sweep's reach. Both bounds are configured values rather than numbers written here.
     *
     * @param attemptsSoFar attempts this row had taken before the one that just failed
     * @return the wait, never longer than the claim timeout
     */
    private Duration backoffAfter(int attemptsSoFar) {
        Duration doubled = sweepDelay;
        for (int step = 0; step < attemptsSoFar && doubled.compareTo(claimTimeout) < 0; step++) {
            doubled = doubled.multipliedBy(2L);
        }
        return doubled.compareTo(claimTimeout) > 0 ? claimTimeout : doubled;
    }

    /**
     * Returns the deepest cause of one failure.
     *
     * <p>A publish wraps the fault it met, so the outermost type names the wrapper and not the
     * failure. The walk ends on a chain that names itself as its own cause.
     *
     * @param failure the failure raised
     * @return the deepest cause, or {@code failure} when it has none
     */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * What one sweep did, carried out of the transaction so it can be counted after the commit.
     *
     * @param published rows the broker accepted and this sweep marked
     * @param failed    rows the broker refused, which is at most one because the sweep stops there
     */
    private record SweepResult(int published, int failed) {

        /**
         * Records this result against {@code meters}.
         *
         * @param meters the recording surface of this service
         */
        void record(AccountMeters meters) {
            if (published > 0) {
                meters.recordOutboxPublished(published);
            }
            for (int failure = 0; failure < failed; failure++) {
                meters.recordPublishFailure();
            }
        }
    }

    /**
     * Waits for one broker acknowledgement without crossing this sweep's total deadline.
     *
     * @param publication broker acknowledgement stage
     * @param deadline monotonic deadline shared by the entire sweep
     */
    private static void await(CompletionStage<Void> publication, long deadline) {
        Objects.requireNonNull(publication, "publisher returned no completion stage");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            throw new RelayDeadlineExceededException();
        }
        try {
            publication.toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("outbox relay was interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("outbox publication failed", cause);
        } catch (TimeoutException timedOut) {
            publication.toCompletableFuture().cancel(true);
            throw new RelayDeadlineExceededException();
        }
    }

    /** Fixed exception used when the configured pass deadline expires. */
    private static final class RelayDeadlineExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RelayDeadlineExceededException() {
            super("the outbox relay pass deadline expired");
        }
    }

    /** Publishes one terminal diagnostic containing no field from the failed payload. */
    private void publishDeadLetter(OutboxEventEntity row, String sourceTopic,
            RuntimeException failure) {
        DeadLetterMetadata metadata = DeadLetterMetadata.fromFailure(
                DEAD_LETTER_CODE, failure, DEAD_LETTER_REASON, DEAD_LETTER_MESSAGE);
        DeadLetterEnvelope envelope = metadata.toEnvelope(
                row.getAggregateId(), sourceTopic, 0, 0L,
                row.getEventId().toString(), row.getEventType(), row.getAttemptCount());
        eventPublisherPort.publish(deadLetterTopic, row.getAggregateId(),
                objectMapper.writeValueAsString(envelope));
    }
}
