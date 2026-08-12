package com.carddemo.account.outbox;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.ObservabilityConfig.AccountMeters;
import com.carddemo.account.entity.OutboxEventEntity.RelayState;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.DeadLetterMetadata;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.OutboxEventRepository;
import com.carddemo.events.correlation.CorrelationScope;
import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.serde.EventContracts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

    /**
     * Sends this relay keeps in flight at once.
     *
     * <p>Five, because {@code max.in.flight.requests.per.connection} in
     * {@code src/main/resources/application.yml} is five: a wider window would not put a sixth
     * request on the wire, it would only leave a sixth send sitting in the producer's accumulator
     * unawaited. Narrower than five leaves the pipeline idle while this sweep waits.
     *
     * <p>Every row of one window names a different account, because {@code claimDueRows} returns one
     * head row per aggregate, so a window is concurrent across accounts and never within one.
     */
    static final int MAX_SENDS_IN_FLIGHT = 5;

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

    /** Sends awaited together, {@link #MAX_SENDS_IN_FLIGHT} or the batch when it is smaller. */
    private final int sendsInFlight;

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
        this.sendsInFlight = Math.min(this.batchSize, MAX_SENDS_IN_FLIGHT);
        this.sweepDelay = Duration.ofMillis(relay.fixedDelayMs());
        this.claimTimeout = relay.claimTimeout();
        this.maxDurationNanos = Duration.ofMillis(relay.maxDurationMs()).toNanos();
    }

    /**
     * Runs one sweep, then records what it published once every transaction of that sweep has
     * committed.
     *
     * <p>This method holds no transaction, and neither does the sweep as a whole. Each step below
     * opens the shortest transaction that step needs: one to claim, then one per outcome. A batch of
     * {@code batch-size} rows waiting on a broker inside one transaction held a database connection
     * and every row lock it took for the sum of those waits, and every later event of every account
     * waited behind it.
     *
     * <p>Counting sits outside every one of those transactions. A counter takes no part in a database
     * transaction, so an increment inside one survives the rollback that discarded the work it
     * counted.
     *
     * <p>A sweep that throws is logged and nothing is counted. That is not a claim that nothing
     * reached the broker: a send the broker acknowledged whose mark then failed has been published,
     * and its row stays claimable, so a later sweep publishes it again. Delivery here is at least
     * once, and each consumer's processed-event marker is what makes the repeat harmless.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms:500}")
    public void publishPendingEvents() {
        runOnePass().record(meters);
    }

    /**
     * Offers the diagnostics still owed, claims the due account heads, and publishes them.
     *
     * <p>Sends are issued {@value #MAX_SENDS_IN_FLIGHT} at a time and each one is awaited against
     * the one deadline this sweep shares. {@code claimDueRows} returns the due head row of each
     * aggregate and never two rows of one account, so the rows of one window name distinct accounts
     * and no two events of one account are ever in flight together. The account identifier is the
     * message key, so that restriction is what keeps every consumer of an account reading its events
     * in the order this service wrote them.
     *
     * @return what this sweep published and how many rows it failed on
     */
    private SweepResult runOnePass() {
        Instant now = clock.instant();
        long deadline = System.nanoTime() + maxDurationNanos;
        Terminal terminal = publishOwedDeadLetters(deadline);
        ClaimedBatch batch = claimBatch(now);
        List<OutboxEventEntity> rows = batch.rows();

        int published = 0;
        int failed = batch.recovered();

        for (int from = 0; from < rows.size(); from += sendsInFlight) {
            if (System.nanoTime() - deadline >= 0L) {
                log.debug("The account outbox sweep reached its deadline with {} claimed rows "
                        + "unattempted; the next sweep recovers them", rows.size() - from);
                break;
            }
            List<OutboxEventEntity> window =
                    rows.subList(from, Math.min(from + sendsInFlight, rows.size()));
            List<Dispatch> dispatched = new ArrayList<>(window.size());
            for (OutboxEventEntity row : window) {
                dispatched.add(dispatch(row));
            }
            for (Dispatch attempt : dispatched) {
                Attempt outcome = settle(attempt, deadline);
                published = published + outcome.published();
                failed = failed + outcome.failed();
                terminal = terminal.plus(outcome.terminal());
            }
        }
        return new SweepResult(published, failed, terminal);
    }

    /**
     * Recovers stranded claims and claims the due account heads, in one transaction that commits
     * before any send starts.
     *
     * <p>Committing the claim is what makes the recovery observable. A claim written and left
     * uncommitted while the sweep published would be rolled back by the process death it exists to
     * survive, and the row would meet every later sweep in {@link RelayState#PENDING} with an
     * unchanged attempt count.
     *
     * @param now the moment this sweep records
     * @return the rows this sweep holds and the number of claims it recovered
     */
    private ClaimedBatch claimBatch(Instant now) {
        ClaimedBatch claimed = transactionTemplate.execute(status -> {
            int recovered = recoverStrandedClaims(now);
            List<OutboxEventEntity> due =
                    outboxEventRepository.claimDueRows(now, Limit.of(batchSize));
            for (OutboxEventEntity row : due) {
                row.claim(instanceId, now);
                outboxEventRepository.save(row);
            }
            return new ClaimedBatch(List.copyOf(due), recovered);
        });
        return claimed == null ? ClaimedBatch.EMPTY : claimed;
    }

    /**
     * Issues the send for one claimed row, outside every transaction, and waits for nothing.
     *
     * @param row the claimed row
     * @return the send to await, or the reason no send was issued
     */
    private Dispatch dispatch(OutboxEventEntity row) {
        String destination;
        try {
            destination = topicFor(row.getEventType());
        } catch (IllegalArgumentException unconfigured) {
            log.error("Outbox event {} carries the unconfigured event type {}. Attempt {} of {}.",
                    row.getEventId(), row.getEventType(), row.getAttemptCount() + 1,
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
            return Dispatch.refused(row, UNRESOLVED_DESTINATION,
                    rootCause(unconfigured).getClass().getSimpleName());
        }
        try (CorrelationScope scope = scopeOf(row)) {
            return Dispatch.issued(row, destination, eventPublisherPort.publish(destination,
                    row.getAggregateId(), row.getPayload()));
        } catch (RuntimeException notSent) {
            String failureClass = rootCause(notSent).getClass().getSimpleName();
            // The failed send itself is reported once, at warning, by SafeProducerListener,
            // which every template of this service installs. A second line here named the
            // same send, so an operator counting reports counted each one twice.
            return Dispatch.refused(row, destination, failureClass);
        }
    }

    /**
     * Opens the correlation scope of one row, so the send it carries and every line written about it
     * name the unit of work behind it.
     *
     * <p>ADDITIVE. A relay sweeps on its own schedule, long after the thread that wrote the row has
     * gone, so the two identifiers are read back off the row rather than inherited from a caller.
     * The scope is what the publish port reads to attach the headers.
     *
     * <p>A row recording no correlation identifier starts its own trace under its own event
     * identifier. That covers a row predating {@code V9__outbox_correlation.sql}, which added the
     * nullable column, so every record this relay publishes carries a correlation identifier a
     * reader can join on. A row carrying neither opens a scope naming neither rather than failing
     * the sweep.
     *
     * @param row the row this pass is working on
     * @return the open scope, closed by the try-with-resources that opened it
     */
    private static CorrelationScope scopeOf(OutboxEventEntity row) {
        UUID recorded = row.getCorrelationId();
        return CorrelationScope.open()
                .withCorrelation(recorded != null ? recorded : row.getEventId())
                .withCausation(row.getCausationId())
                .withEvent(row.getEventId(), row.getEventType());
    }

    /**
     * Waits for one acknowledgement, then records that outcome in a transaction of its own.
     *
     * <p>The mark is written after the broker acknowledges, so no row is marked for a message the
     * broker did not accept. A send that returns while the mark fails leaves the row claimed until
     * the claim timeout returns it, a later sweep sends it again, and each consumer's processed-event
     * marker absorbs the duplicate.
     *
     * @param dispatch what {@link #dispatch(OutboxEventEntity)} issued
     * @param deadline monotonic deadline shared by the entire sweep
     * @return what this attempt did
     */
    private Attempt settle(Dispatch dispatch, long deadline) {
        OutboxEventEntity row = dispatch.row();
        if (dispatch.publication() == null) {
            return new Attempt(0, 1, recordRefusedRow(row, dispatch.destination(),
                    dispatch.failureClass(), deadline));
        }
        try {
            await(dispatch.publication(), deadline);
        } catch (RuntimeException failure) {
            return new Attempt(0, 1, recordRefusedRow(row, dispatch.destination(),
                    rootCause(failure).getClass().getSimpleName(), deadline));
        }
        markPublished(row.getEventId());
        return new Attempt(1, 0, Terminal.NONE);
    }

    /**
     * Marks one row published, in a transaction of its own.
     *
     * <p>The row is re-read inside that transaction rather than saved from the copy the claim
     * loaded. A detached copy carries the state it had before the claim committed, and saving it
     * would write that older state back over the claim.
     *
     * @param eventId the row the broker accepted
     */
    private void markPublished(UUID eventId) {
        transactionTemplate.execute(status -> {
            Optional<OutboxEventEntity> found = outboxEventRepository.findById(eventId);
            if (found.isEmpty()) {
                log.warn("An account outbox row disappeared between its claim and its mark");
                return null;
            }
            OutboxEventEntity stored = found.get();
            stored.markPublished(clock.instant());
            outboxEventRepository.save(stored);
            return null;
        });
    }

    /**
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
     * Schedules another attempt for one refused row, in a transaction of its own, and logs the
     * refusal.
     *
     * <p>The row is re-read inside that transaction rather than saved from the copy the claim
     * loaded, for the same reason {@link #markPublished(UUID)} re-reads it.
     *
     * <p>Neither the stored reason nor the log line carries anything but the failure's class name.
     * A database or broker failure message can quote the row it was raised for, and this row's
     * payload holds a credit limit, a cycle balance and an account identifier. A class name says
     * which kind of failure occurred and cannot carry any of that.
     *
     * <p>The row this method abandons owes a terminal diagnostic, and
     * {@link OutboxEventEntity#recordFailure(String, Instant, Instant)} writes that obligation in
     * the same call that abandons it, so the two facts commit together. The diagnostic is then
     * offered outside that transaction; a broker that refuses leaves the obligation standing and
     * {@link #publishOwedDeadLetters(long)} takes it on a later sweep.
     *
     * @param claimed      the row the broker refused, as the claim loaded it
     * @param destination  the topic selected from the stored event type, or
     *                     {@link #UNRESOLVED_DESTINATION} when the stored type named none
     * @param failureClass class name of the failure the publish raised
     * @param deadline     monotonic deadline shared by the entire sweep
     * @return what this refusal did to the terminal counts
     */
    private Terminal recordRefusedRow(OutboxEventEntity claimed, String destination,
            String failureClass, long deadline) {
        Abandonment abandonment = transactionTemplate.execute(status -> {
            Optional<OutboxEventEntity> found =
                    outboxEventRepository.findById(claimed.getEventId());
            if (found.isEmpty()) {
                log.warn("An account outbox row disappeared between its claim and its failure "
                        + "record");
                return Abandonment.NONE;
            }
            OutboxEventEntity row = found.get();
            Instant now = clock.instant();
            row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
            outboxEventRepository.save(row);

            if (row.getRelayState() != RelayState.ABANDONED) {
                // The failed send itself is reported once, at warning, by SafeProducerListener,
                // which every template of this service installs. A second line here named the
                // same send, so an operator counting reports counted each one twice.
                return Abandonment.NONE;
            }
            return new Abandonment(true, row.getAttemptCount(), row.getLastError());
        });

        Abandonment given = abandonment == null ? Abandonment.NONE : abandonment;
        if (!given.abandoned()) {
            return Terminal.NONE;
        }

        log.error("Outbox event {} was abandoned after {} attempts on topic {}, the last failing "
                        + "with {}. It owes one dead letter on {} until the broker acknowledges "
                        + "one.",
                claimed.getEventId(), given.attemptCount(), destination, failureClass,
                deadLetterTopic);
        return Terminal.ABANDONED.plus(publishDeadLetter(claimed.getEventId(),
                claimed.getAggregateId(), claimed.getEventType(), given.attemptCount(), destination,
                new AbandonedRowException(given.lastError()), deadline));
    }

    /**
     * Discharges the diagnostics abandoned rows still owe, oldest attempt first.
     *
     * <p>This runs at the head of a sweep rather than at its end. The set is empty while the relay
     * is healthy, and a business row that yields its place is retried on the next sweep with nothing
     * lost. The abandoned row itself is retained in the terminal state, so the payload survives
     * whether or not its diagnostic has landed.
     *
     * <p>A refusal here is not a failure of the sweep. The obligation is durable, so the row
     * survives to be attempted again, and the attempt is counted so an operator can see a diagnostic
     * that is not landing.
     *
     * <p>The read below takes its own short transaction and the sends sit outside it. The finder
     * takes a pessimistic lock with {@code SKIP LOCKED}, so it needs a transaction at all, and
     * holding that one across the sends would hold a database connection and every locked row for
     * the sum of the broker waits — which is the shape this relay was rewritten to remove. The
     * consequence is stated rather than hidden: two instances reading either side of one clear can
     * both name the same row, so a diagnostic can appear twice. Clearing the obligation is filtered
     * on {@code owesDeadLetter}, so the second clear is a no-op, and the two sibling relays that
     * carry the same durable obligation already read it without a lock.
     *
     * @param deadline monotonic deadline shared by the entire sweep
     * @return what this step did to the terminal counts
     */
    private Terminal publishOwedDeadLetters(long deadline) {
        List<OutboxEventEntity> owed = readRowsOwingADiagnostic();

        Terminal counts = Terminal.NONE;
        for (OutboxEventEntity row : owed) {
            if (System.nanoTime() - deadline >= 0L) {
                return counts;
            }
            log.warn("Outbox event {} has owed a dead letter on {} since {}, so this sweep offers "
                            + "it again.", row.getEventId(), deadLetterTopic,
                    row.getLastAttemptAt());
            counts = counts.plus(publishDeadLetter(row.getEventId(), row.getAggregateId(),
                    row.getEventType(), row.getAttemptCount(),
                    topicOrUnresolved(row.getEventType()),
                    new AbandonedRowException(row.getLastError()), deadline));
        }
        return counts;
    }

    /**
     * Reads the rows still owing a diagnostic, in a transaction of its own.
     *
     * <p>The rows are detached by the time this returns, and every field the diagnostic reads is a
     * column of the row itself, so nothing is loaded after the transaction closes.
     *
     * @return the rows owing a diagnostic, oldest attempt first, at most one batch of them
     */
    private List<OutboxEventEntity> readRowsOwingADiagnostic() {
        List<OutboxEventEntity> owed = transactionTemplate.execute(status ->
                List.copyOf(outboxEventRepository.findByDeadLetterStateOrderByLastAttemptAtAsc(
                        OutboxEventEntity.DeadLetterState.REQUIRED, Limit.of(batchSize))));
        return owed == null ? List.of() : owed;
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
     * The rows one sweep holds, and the claims it recovered on the way to them.
     *
     * @param rows      the claimed account heads, at most one row per account
     * @param recovered claims a stopped instance left behind that this sweep returned to pending
     */
    private record ClaimedBatch(List<OutboxEventEntity> rows, int recovered) {

        /** What a claim transaction that answered with nothing stands for. */
        private static final ClaimedBatch EMPTY = new ClaimedBatch(List.of(), 0);
    }

    /**
     * One send this sweep issued, or the reason it issued none.
     *
     * @param row          the claimed row
     * @param destination  the topic the send was addressed to
     * @param publication  the acknowledgement to await, or {@code null} when no send was issued
     * @param failureClass why no send was issued, or {@code null} when one was
     */
    private record Dispatch(OutboxEventEntity row, String destination,
            CompletionStage<Void> publication, String failureClass) {

        static Dispatch issued(OutboxEventEntity row, String destination,
                CompletionStage<Void> publication) {
            return new Dispatch(row, destination, Objects.requireNonNull(publication,
                    "publisher returned no completion stage"), null);
        }

        static Dispatch refused(OutboxEventEntity row, String destination, String failureClass) {
            return new Dispatch(row, destination, null, failureClass);
        }
    }

    /**
     * What one attempt did: the two per-attempt counts and its effect on the terminal path.
     *
     * @param published rows the broker acknowledged, one or none
     * @param failed    attempts that could not be completed, one or none
     * @param terminal  what the attempt did to the terminal counts
     */
    private record Attempt(int published, int failed, Terminal terminal) {
    }

    /**
     * Whether one recorded failure abandoned its row, and what the row then held.
     *
     * <p>Read out of the transaction that recorded the failure, because the diagnostic that names an
     * abandoned row is published outside it.
     *
     * @param abandoned    whether the attempt reached the ceiling
     * @param attemptCount attempts the row had taken once the failure was recorded
     * @param lastError    the redacted reason the row now holds
     */
    private record Abandonment(boolean abandoned, int attemptCount, String lastError) {

        /** The row survives for another attempt. */
        private static final Abandonment NONE = new Abandonment(false, 0, null);
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
     * {@code failedEventType} and carries no field of the abandoned payload, so no credit limit and
     * no cycle balance leaves this service on the diagnostic. The account identifier does travel:
     * it is the aggregate identifier of the envelope and the message key of the send, which is what
     * lets an operator find the abandoned event.
     *
     * <p>A refusal is logged and counted and does not propagate. The caller is either the refused-row
     * path, which has already recorded the business failure that abandoned the row, or the owed-
     * diagnostic sweep, where one unreachable broker must not stop the rows behind it.
     *
     * @param eventId      the row this relay gave up on
     * @param aggregateId  the account the abandoned event names, the diagnostic's key
     * @param eventType    the type the abandoned row carries
     * @param attemptCount attempts the abandoned row had taken
     * @param sourceTopic  the topic the abandoned event was meant for
     * @param failure      the failure of its last attempt
     * @param deadline     monotonic deadline shared by the entire sweep
     * @return {@link Terminal#DEAD_LETTER_PUBLISHED} on acknowledgement, otherwise
     *         {@link Terminal#DEAD_LETTER_FAILED}
     */
    private Terminal publishDeadLetter(UUID eventId, String aggregateId, String eventType,
            int attemptCount, String sourceTopic, RuntimeException failure, long deadline) {
        DeadLetterMetadata metadata = DeadLetterMetadata.fromFailure(
                DEAD_LETTER_CODE, failure, DEAD_LETTER_REASON, DEAD_LETTER_MESSAGE);
        DeadLetterEnvelope envelope = metadata.toEnvelope(
                aggregateId, sourceTopic, 0, 0L,
                eventId.toString(), eventType, attemptCount);

        try {
            await(eventPublisherPort.publish(deadLetterTopic, aggregateId,
                    objectMapper.writeValueAsString(envelope)), deadline);
        } catch (RuntimeException refused) {
            log.error("The dead letter naming outbox event {} did not reach {} after a {}. The row "
                            + "still owes one, and a later sweep offers it again.",
                    eventId, deadLetterTopic, rootCause(refused).getClass().getSimpleName());
            return Terminal.DEAD_LETTER_FAILED;
        }

        // The obligation is cleared in a transaction of its own, and only after the broker took the
        // diagnostic. A row that has gone in the meantime owes nothing.
        transactionTemplate.execute(status -> {
            outboxEventRepository.findById(eventId)
                    .filter(OutboxEventEntity::owesDeadLetter)
                    .ifPresent(stored -> {
                        stored.markDeadLetterPublished(clock.instant());
                        outboxEventRepository.save(stored);
                    });
            return null;
        });
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
