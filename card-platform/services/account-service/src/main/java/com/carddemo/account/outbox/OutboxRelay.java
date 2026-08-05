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
 * <p>A sweep recovers stranded claims, offers the diagnostics abandoned rows still owe, claims the
 * rows that are due, then publishes them in order. All of it runs in one transaction, because the
 * claim depends on it: the claim query holds each row it returns with
 * {@code FOR UPDATE SKIP LOCKED}, and that lock lives exactly as long as the transaction that took
 * it. Two relay instances sweeping the same table therefore return disjoint batches and neither
 * waits for the other.
 *
 * <p>The claim query returns at most the head row of each account. A refusal schedules that account
 * head for another attempt while the sweep continues with other accounts; no later event of the
 * failed account is in the batch, so per-account ordering remains intact without one account
 * blocking the whole table.
 *
 * <p>A row that keeps failing does not block the table for ever. Each failure raises the attempt
 * count and pushes {@code next_attempt_at} further out, and
 * {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts move the row to
 * {@link RelayState#ABANDONED}, which the claim query does not return. Every reason a publish can
 * fail is inside that boundary, the destination lookup included, so no row can fail in a way that
 * leaves its attempt count where it was.
 *
 * <p>An abandoned row is not a lost row. Abandoning it records a durable obligation to name it on
 * the dead-letter topic, in the same transaction, and this relay discharges that obligation only
 * against a broker acknowledgement. A refused diagnostic is offered again on the next sweep, for as
 * long as it takes, because that diagnostic is the last remaining record of an event this service
 * gave up on.
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

    /**
     * Destination named on a row whose stored event type this relay has no configured topic for.
     *
     * <p>Such a row can only be an event type shipped without its topic, and it fails every attempt.
     * The placeholder keeps that failure inside the per-row boundary: the attempt is recorded, the
     * backoff applies, the row reaches {@link RelayState#ABANDONED} on schedule and its diagnostic
     * names why. Resolving the topic outside that boundary threw before any bookkeeping ran, the
     * claim rolled back with it, and the row met every later sweep with an unchanged attempt count.
     *
     * <p>The value satisfies the {@code sourceTopic} pattern of {@code schemas/dead-letter-v1.json},
     * which admits letters, digits, the period, the underscore and the hyphen. That is a constraint
     * and not a style choice: this placeholder reaches {@link DeadLetterMetadata#toEnvelope} as the
     * source topic of the diagnostic, and a value the document refuses would lose the diagnostic for
     * exactly the row that has no other record of itself.
     */
    private static final String UNRESOLVED_DESTINATION = "no-configured-topic";

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
        Terminal terminal = publishOwedDeadLetters(now, deadline);

        List<OutboxEventEntity> due = outboxEventRepository.claimDueRows(now, Limit.of(batchSize));
        int published = 0;
        for (OutboxEventEntity row : due) {
            row.claim(instanceId, now);
            String destination = UNRESOLVED_DESTINATION;
            try {
                destination = topicFor(row.getEventType());
                await(eventPublisherPort.publish(destination, row.getAggregateId(),
                        row.getPayload()), deadline);
                row.markPublished(now);
                outboxEventRepository.save(row);
                published = published + 1;
            } catch (RuntimeException failure) {
                terminal = terminal.plus(recordRefusedRow(row, destination, failure, now,
                        deadline));
                failed = failed + 1;
            }
        }
        return new SweepResult(published, failed, terminal);
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
     * <p>The row this method abandons owes a terminal diagnostic, and
     * {@link OutboxEventEntity#recordFailure(String, Instant, Instant)} writes that obligation in
     * the same call that abandons it, so the two facts commit together. This method then tries to
     * discharge it immediately; a broker that refuses leaves the obligation standing and
     * {@link #publishOwedDeadLetters(Instant, long)} takes it on the next sweep.
     *
     * @param row         the row the broker refused
     * @param destination the topic selected from the stored event type, or
     *                    {@link #UNRESOLVED_DESTINATION} when the stored type named none
     * @param failure     the failure the publish raised
     * @param now         the moment this sweep started
     * @param deadline    monotonic deadline shared by the entire sweep
     * @return what this refusal did to the terminal counts
     */
    private Terminal recordRefusedRow(OutboxEventEntity row, String destination,
            RuntimeException failure, Instant now, long deadline) {
        String failureClass = rootCause(failure).getClass().getSimpleName();
        row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEventRepository.save(row);

        if (row.getRelayState() != RelayState.ABANDONED) {
            log.warn("Outbox event {} did not reach topic {} after a {}. Attempt {} of {}; that "
                            + "account waits behind this row while other account heads may "
                            + "continue.",
                    row.getEventId(), destination, failureClass, row.getAttemptCount(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
            return Terminal.NONE;
        }

        log.error("Outbox event {} was abandoned after {} attempts on topic {}, the last failing "
                        + "with {}. It owes one dead letter on {} until the broker acknowledges "
                        + "one.",
                row.getEventId(), row.getAttemptCount(), destination, failureClass, deadLetterTopic);
        return Terminal.ABANDONED.plus(publishDeadLetter(row, destination, failure, now, deadline));
    }

    /**
     * Discharges the diagnostics abandoned rows still owe, oldest attempt first.
     *
     * <p>This runs at the head of a sweep rather than at its end. An owed diagnostic is the only
     * remaining record of an event this service gave up on, the set is empty while the relay is
     * healthy, and a business row that yields its place is retried on the next sweep with nothing
     * lost.
     *
     * <p>A refusal here is not a failure of the sweep. The obligation is durable, so the row
     * survives to be attempted again, and the attempt is counted so an operator can see a diagnostic
     * that is not landing.
     *
     * @param now      the moment this sweep started
     * @param deadline monotonic deadline shared by the entire sweep
     * @return what this step did to the terminal counts
     */
    private Terminal publishOwedDeadLetters(Instant now, long deadline) {
        List<OutboxEventEntity> owed = outboxEventRepository
                .findByDeadLetterStateOrderByLastAttemptAtAsc(
                        OutboxEventEntity.DeadLetterState.REQUIRED, Limit.of(batchSize));

        Terminal counts = Terminal.NONE;
        for (OutboxEventEntity row : owed) {
            log.warn("Outbox event {} has owed a dead letter on {} since {}, so this sweep offers "
                            + "it again.", row.getEventId(), deadLetterTopic,
                    row.getLastAttemptAt());
            counts = counts.plus(publishDeadLetter(row, topicOrUnresolved(row.getEventType()),
                    new AbandonedRowException(row.getLastError()), now, deadline));
        }
        return counts;
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
     * Names the destination of one stored event type, or {@link #UNRESOLVED_DESTINATION}.
     *
     * <p>A diagnostic reports the topic the event was meant for, and an event type this relay has no
     * topic for is one of the reasons a row is abandoned. Reporting that as a placeholder rather
     * than as a second failure keeps the diagnostic publishable, which is the whole point of
     * publishing it.
     *
     * @param eventType the stored event type
     * @return the configured topic, or the placeholder when the type names none
     */
    private String topicOrUnresolved(String eventType) {
        try {
            return topicFor(eventType);
        } catch (IllegalArgumentException unconfigured) {
            return UNRESOLVED_DESTINATION;
        }
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
     * What one sweep did to the terminal path, kept separate from the per-attempt counts.
     *
     * <p>Three outcomes are distinguishable rather than summed into one failure count: a row given
     * up on, a diagnostic the broker acknowledged, and a diagnostic the broker refused. An operator
     * reading only a failure count cannot tell a service retrying normally from one that has
     * silently stopped recording what it lost.
     *
     * @param abandoned            rows this sweep gave up on
     * @param deadLettersPublished diagnostics the broker acknowledged in this sweep
     * @param deadLettersFailed    diagnostic attempts the broker refused in this sweep
     */
    private record Terminal(int abandoned, int deadLettersPublished, int deadLettersFailed) {

        /** Nothing terminal happened. */
        private static final Terminal NONE = new Terminal(0, 0, 0);

        /** One row was given up on. */
        private static final Terminal ABANDONED = new Terminal(1, 0, 0);

        /** One diagnostic reached the broker. */
        private static final Terminal DEAD_LETTER_PUBLISHED = new Terminal(0, 1, 0);

        /** One diagnostic did not reach the broker, and the row still owes it. */
        private static final Terminal DEAD_LETTER_FAILED = new Terminal(0, 0, 1);

        /**
         * Adds two sets of terminal counts.
         *
         * @param other the counts to add
         * @return the sum, leaving both operands unchanged
         */
        Terminal plus(Terminal other) {
            return new Terminal(abandoned + other.abandoned,
                    deadLettersPublished + other.deadLettersPublished,
                    deadLettersFailed + other.deadLettersFailed);
        }
    }

    /**
     * What one sweep did, carried out of the transaction so it can be counted after the commit.
     *
     * <p>This record is the one owner of {@code carddemo.account.publish.failed}. The publisher
     * counts nothing: it is called once per row per sweep and it cannot see a failure that never
     * reached it, such as a stored event type this relay has no topic for or a claim a dead instance
     * stranded. Counting in both places reported one refusal twice, so a dashboard showed a
     * healthy service failing at double its real rate.
     *
     * @param failed   attempts against a row that failed in this sweep: a refused publish, an
     *                 unresolved destination, an expired sweep deadline, or a claim recovered from
     *                 an instance that died mid-attempt
     * @param published rows the broker accepted and this sweep marked
     * @param terminal  what this sweep did to the terminal path
     */
    private record SweepResult(int published, int failed, Terminal terminal) {

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
            for (int given = 0; given < terminal.abandoned(); given++) {
                meters.recordOutboxAbandoned();
            }
            for (int named = 0; named < terminal.deadLettersPublished(); named++) {
                meters.recordDeadLetterPublished();
            }
            for (int refused = 0; refused < terminal.deadLettersFailed(); refused++) {
                meters.recordDeadLetterFailure();
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

    /**
     * Publishes one terminal diagnostic, waits for the broker, and clears the obligation only once
     * the broker has acknowledged it.
     *
     * <p>The wait is what makes the obligation meaningful. A send that is dispatched and not awaited
     * reports success the instant it is queued, so an unreachable broker and a healthy one look
     * identical, and the row is left terminal either way. The wait shares the sweep's deadline with
     * every other send, so a broker that never answers costs one sweep rather than one thread.
     *
     * <p>The envelope is the governed form of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}. It names the row through {@code failedEventId} and
     * {@code failedEventType} and carries no field of the payload, so an operator can reach the
     * abandoned event without a credit limit, a cycle balance or an account identifier leaving this
     * service on the diagnostic.
     *
     * <p>A refusal is logged and counted and does not propagate. The caller is either the refused-row
     * path, which has already recorded the business failure that abandoned the row, or the owed-
     * diagnostic sweep, where one unreachable broker must not stop the rows behind it.
     *
     * @param row         the row this relay gave up on
     * @param sourceTopic the topic the abandoned event was meant for
     * @param failure     the failure of its last attempt
     * @param now         the moment this sweep started
     * @param deadline    monotonic deadline shared by the entire sweep
     * @return {@link Terminal#DEAD_LETTER_PUBLISHED} on acknowledgement, otherwise
     *         {@link Terminal#DEAD_LETTER_FAILED}
     */
    private Terminal publishDeadLetter(OutboxEventEntity row, String sourceTopic,
            RuntimeException failure, Instant now, long deadline) {
        DeadLetterMetadata metadata = DeadLetterMetadata.fromFailure(
                DEAD_LETTER_CODE, failure, DEAD_LETTER_REASON, DEAD_LETTER_MESSAGE);
        DeadLetterEnvelope envelope = metadata.toEnvelope(
                row.getAggregateId(), sourceTopic, 0, 0L,
                row.getEventId().toString(), row.getEventType(), row.getAttemptCount());

        try {
            await(eventPublisherPort.publish(deadLetterTopic, row.getAggregateId(),
                    objectMapper.writeValueAsString(envelope)), deadline);
        } catch (RuntimeException refused) {
            log.error("The dead letter naming outbox event {} did not reach {} after a {}. The row "
                            + "still owes one, and a later sweep offers it again.",
                    row.getEventId(), deadLetterTopic,
                    rootCause(refused).getClass().getSimpleName());
            return Terminal.DEAD_LETTER_FAILED;
        }

        row.markDeadLetterPublished(now);
        outboxEventRepository.save(row);
        return Terminal.DEAD_LETTER_PUBLISHED;
    }

    /**
     * Names the abandonment an owed diagnostic reports, when the failure that caused it is gone.
     *
     * <p>A diagnostic offered on a later sweep has no live exception behind it: the failure that
     * abandoned the row happened in an earlier sweep, and only the redacted reason
     * {@code last_error} kept survives. This exception carries that reason so
     * {@link DeadLetterMetadata#fromFailure} names the same culprit it would have named at the time.
     */
    private static final class AbandonedRowException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Takes the redacted reason stored on the row.
         *
         * @param recordedReason {@code last_error} as the row holds it, which names a failure class
         *                       and no event value, or null when the row holds none
         */
        AbandonedRowException(String recordedReason) {
            super(recordedReason == null ? "outbox publish exhausted" : recordedReason);
        }
    }
}
