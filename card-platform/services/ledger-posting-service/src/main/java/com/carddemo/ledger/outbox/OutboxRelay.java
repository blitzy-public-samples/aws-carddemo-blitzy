package com.carddemo.ledger.outbox;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.config.LedgerProperties;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.entity.OutboxEventEntity.RelayState;
import com.carddemo.ledger.messaging.DeadLetterMetadata;
import com.carddemo.ledger.repository.OutboxEventRepository;
import com.carddemo.events.correlation.CorrelationScope;
import com.carddemo.events.correlation.EventCorrelation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
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
 * <h2>How one pass bounds itself</h2>
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
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class OutboxRelay {

    /** Diagnostic output of this class. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Reason recorded on a row whose claiming instance died before it finished. */
    private static final String CLAIM_EXPIRED = "ClaimExpired";

    /** Reason recorded on a row whose event type has no ledger destination. */
    private static final String UNKNOWN_EVENT_TYPE = "UnknownEventType";

    /**
     * Sends this relay keeps in flight at once.
     *
     * <p>Five, because {@code max.in.flight.requests.per.connection} in
     * {@code src/main/resources/application.yml} is five: a wider window would not put a sixth
     * request on the wire, it would only leave a sixth send sitting in the producer's accumulator
     * unawaited. Narrower than five leaves the pipeline idle while this pass waits.
     *
     * <p>Every row of one window names a different account, because {@code claimDueRows} returns
     * one head row per aggregate, so a window is concurrent across accounts and never within one.
     */
    static final int MAX_SENDS_IN_FLIGHT = 5;

    /** Holds the rows to publish, and records each publication. */
    private final OutboxEventRepository outboxEvents;

    /** Sends one event record to one topic, keyed on the account identifier. */
    private final KafkaTemplate<String, Object> ledgerEventTemplate;

    /** Reads one stored payload back into the record it was written from. */
    private final JsonMapper jsonMapper;

    /** Rows one sweep claims, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Sends awaited together, {@link #MAX_SENDS_IN_FLIGHT} or the batch when it is smaller. */
    private final int sendsInFlight;

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
        this.sendsInFlight = Math.min(this.batchSize, MAX_SENDS_IN_FLIGHT);
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

    /**
     * Runs one pass and records what it did once every transaction of that pass has committed.
     *
     * <p>The scheduled method holds no transaction of its own. Each step below opens the shortest
     * transaction that step needs, so no database connection and no claimed row is held across a
     * broker wait.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    public void publishPendingEvents() {
        long deadline = System.nanoTime() + maxDurationNanos;
        SweepResult result;
        try {
            result = runOnePass(deadline);
        } catch (RuntimeException failure) {
            meters.recordFailure(LedgerMeters.PUBLISH_STAGE);
            log.warn("The ledger outbox sweep failed after {} and will run again",
                    rootCause(failure).getClass().getSimpleName());
            return;
        }
        Objects.requireNonNull(result, "the sweep must answer with a result").record(meters);
    }

    /**
     * Claims the due account heads, publishes them, and records each outcome on its own.
     *
     * <p>A pass takes three kinds of transaction, and the split is the point rather than a detail.
     * One claim transaction recovers stranded claims and locks the due rows, and it commits before
     * any send starts. No transaction at all surrounds the sends: a batch of {@code batch-size} rows
     * waiting on a broker inside one transaction held a database connection and every row lock it
     * took for the sum of those waits, and every later event of every account waited behind it. One
     * short transaction then records each outcome.
     *
     * <p>Sends are issued {@value #MAX_SENDS_IN_FLIGHT} at a time and every one of them is awaited
     * against the one deadline this pass shares. {@code claimDueRows} returns the due head row of
     * each aggregate and never two rows of one account, so the rows of one window name distinct
     * accounts and no two events of one account are ever in flight together. The account identifier
     * is the message key, so that restriction is what keeps every consumer of an account reading its
     * events in the order this service wrote them.
     *
     * <p>One claim is one event per account, so a pass that claimed once published one event per
     * account however many that account was owed, and the scheduled delay then set the rate: a burst
     * on one account drained at two events a second whatever {@code batch-size} allowed. A pass
     * therefore repeats the claim-and-publish cycle. Each cycle takes the head each account now has,
     * which is the row behind the one the previous cycle published, so the ordering guarantee above
     * is the same guarantee it always was.
     *
     * <p>{@code batch-size} still bounds one pass, and that is the bound the cycles share: a pass
     * claims at most that many rows in total, however many cycles it takes to reach them. What
     * changed is that those rows no longer have to name distinct accounts. A pass also stops when
     * the claim answers nothing, when a cycle publishes nothing, or when the deadline arrives, and it
     * claims any one row at most once: a broker refusing everything costs one batch of attempts per
     * pass rather than the whole pass. The claim itself carries that last guard, so a row a later
     * cycle is offered again is left {@code PENDING} and untouched rather than claimed and handed
     * back.
     *
     * <p>A row this pass claimed and did not attempt is released rather than left holding its claim.
     * The claim query passes over a claimed row, so a row left that way waited for the claim timeout
     * and its whole account waited with it; released, it is due again immediately and the next pass
     * takes it.
     *
     * @param deadline monotonic deadline shared by every acknowledgement this pass waits for
     * @return the work this pass completed
     */
    private SweepResult runOnePass(long deadline) {
        int published = 0;
        int failed = 0;
        int abandoned = 0;
        Set<UUID> attempted = new HashSet<>();
        boolean recoverStranded = true;

        while (attempted.size() < batchSize && System.nanoTime() - deadline < 0L) {
            ClaimedBatch batch = claimBatch(clock.instant(), recoverStranded, attempted, batchSize - attempted.size());
            recoverStranded = false;
            failed += batch.recovered();
            List<OutboxEventEntity> rows = batch.rows();
            if (rows.isEmpty()) {
                break;
            }

            int publishedThisCycle = 0;
            int at = 0;
            for (; at < rows.size(); at += sendsInFlight) {
                if (System.nanoTime() - deadline >= 0L) {
                    break;
                }
                List<OutboxEventEntity> window =
                        rows.subList(at, Math.min(at + sendsInFlight, rows.size()));
                List<Dispatch> dispatched = new ArrayList<>(window.size());
                for (OutboxEventEntity row : window) {
                    dispatched.add(dispatch(row));
                }
                for (Dispatch attempt : dispatched) {
                    Attempt outcome = settle(attempt, deadline);
                    publishedThisCycle += outcome.published();
                    failed += outcome.failed();
                    abandoned += outcome.abandoned();
                }
            }
            published += publishedThisCycle;

            if (at < rows.size()) {
                List<OutboxEventEntity> unattempted = rows.subList(at, rows.size());
                log.debug("The ledger outbox pass reached its deadline with {} claimed rows "
                        + "unattempted; they are released for the next pass", unattempted.size());
                releaseUnattemptedClaims(unattempted);
                break;
            }
            if (publishedThisCycle == 0) {
                break;
            }
        }
        return new SweepResult(published, failed, abandoned);
    }

    /**
     * Returns rows this pass claimed and did not attempt to {@code PENDING}, in one transaction.
     *
     * <p>Each row is re-read inside that transaction rather than saved from the copy the claim
     * loaded, for the reason {@link #markPublished(UUID)} re-reads it, and only a row this instance
     * still holds is released: a claim another instance has since recovered and re-taken belongs to
     * that instance.
     *
     * @param claimed rows this pass holds and issued no send for, possibly empty
     */
    private void releaseUnattemptedClaims(List<OutboxEventEntity> claimed) {
        if (claimed.isEmpty()) {
            return;
        }
        transactionTemplate.execute(status -> {
            for (OutboxEventEntity row : claimed) {
                outboxEvents.findById(row.getEventId())
                        .filter(stored -> stored.getRelayState() == RelayState.CLAIMED
                                && instanceId.equals(stored.getClaimedBy()))
                        .ifPresent(stored -> {
                            stored.releaseUnattemptedClaim();
                            outboxEvents.save(stored);
                        });
            }
            return null;
        });
    }

    /**
     * Recovers stranded claims and claims the due account heads, in one transaction that commits
     * before any send starts.
     *
     * <p>Committing the claim is what makes the recovery observable. A claim written and left
     * uncommitted while the pass published would be rolled back by the process death it exists to
     * survive, and the row would meet every later pass in {@link RelayState#PENDING} with an
     * unchanged attempt count.
     *
     * <p>{@code recoverStranded} is true for the first cycle of a pass and false for every cycle
     * after it. Recovery reads the claims older than the claim timeout, and a pass that repeated
     * that read on every cycle would spend the read to find the same nothing each time.
     *
     * @param now             the moment this pass records
     * @param recoverStranded whether this cycle also recovers claims a stopped instance left
     * @return the rows this pass holds and the number of claims it recovered
     */
    private ClaimedBatch claimBatch(Instant now, boolean recoverStranded, Set<UUID> alreadyTaken,
            int allowance) {
        ClaimedBatch claimed = transactionTemplate.execute(status -> {
            int recovered = recoverStranded ? recoverStrandedClaims(now) : 0;
            List<OutboxEventEntity> due = outboxEvents.claimDueRows(now, Limit.of(allowance));
            List<OutboxEventEntity> taken = new ArrayList<>(due.size());
            for (OutboxEventEntity row : due) {
                if (!alreadyTaken.add(row.getEventId())) {
                    continue;
                }
                row.claim(instanceId, now);
                outboxEvents.save(row);
                taken.add(row);
            }
            return new ClaimedBatch(List.copyOf(taken), recovered);
        });
        return claimed == null ? ClaimedBatch.EMPTY : claimed;
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
     * Issues the send for one claimed row, outside every transaction, and waits for nothing.
     *
     * <p>Reading the stored payload back into its record happens here as well, so a payload that
     * cannot be read is one row's failure and not the pass's.
     *
     * @param row the claimed row
     * @return the send to await, or the reason no send was issued
     */
    private Dispatch dispatch(OutboxEventEntity row) {
        Destination destination = destinations.get(row.getEventType());
        if (destination == null) {
            log.error("A ledger outbox row carries the unconfigured event type {}. "
                            + "Attempt {} of {}.", row.getEventType(), row.getAttemptCount() + 1,
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
            return Dispatch.unroutable(row);
        }
        try (CorrelationScope _ = scopeOf(row)) {
            Object event = jsonMapper.readValue(row.getPayload(), destination.eventClass());
            return Dispatch.issued(row, destination.topic(), ledgerEventTemplate.send(
                    correlatedRecord(destination.topic(), row.getAggregateId(), event)));
        } catch (RuntimeException notSent) {
            String failureClass = rootCause(notSent).getClass().getSimpleName();
            // The failed send itself is reported once, at warning, by SafeProducerListener,
            // which every template of this service installs. A second line here named the
            // same send, so an operator counting reports counted each one twice.
            return Dispatch.refused(row, destination.topic(), failureClass);
        }
    }

    /**
     * Waits for one acknowledgement, then records that outcome in a transaction of its own.
     *
     * <p>The mark is written after the broker acknowledges, so no row is marked for a message the
     * broker did not accept. A send that returns while the mark fails leaves the row claimed until
     * the claim timeout returns it, the next pass sends it again, and the processed-event table of
     * each consumer absorbs the duplicate.
     *
     * @param dispatch what {@link #dispatch(OutboxEventEntity)} issued
     * @param deadline monotonic deadline shared by the whole pass
     * @return what this attempt did
     */
    private Attempt settle(Dispatch dispatch, long deadline) {
        OutboxEventEntity row = dispatch.row();
        if (dispatch.publication() == null) {
            return recordRefusedRow(row, dispatch.failureClass(), dispatch.topic(), deadline);
        }
        try {
            await(dispatch.publication(), deadline);
        } catch (RuntimeException failure) {
            return recordRefusedRow(row, rootCause(failure).getClass().getSimpleName(),
                    dispatch.topic(), deadline);
        }
        markPublished(row.getEventId());
        return Attempt.PUBLISHED;
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
            Optional<OutboxEventEntity> found = outboxEvents.findById(eventId);
            if (found.isEmpty()) {
                log.warn("A ledger outbox row disappeared between its claim and its mark");
                return null;
            }
            OutboxEventEntity stored = found.get();
            stored.markPublished(clock.instant());
            outboxEvents.save(stored);
            return null;
        });
    }

    /**
     * Schedules another attempt for a row the broker refused, in a transaction of its own.
     *
     * <p>The row is re-read inside that transaction rather than saved from the copy the claim
     * loaded, for the same reason {@link #markPublished(UUID)} re-reads it.
     *
     * <p>An attempt that reaches {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} abandons the row,
     * and the diagnostic naming it is published inside that same short transaction and waited for.
     * A broker that refuses the diagnostic therefore rolls the abandonment back, and the next pass
     * offers the row again rather than leaving it terminal with no record of it anywhere. That one
     * transaction spans one send, and only on the attempt that gives up on a row; the pass's other
     * sends are outside every transaction.
     *
     * <p>One row is abandoned without a diagnostic: the row whose event type this service configures
     * no destination for. The envelope declares the topic the row was bound for, and that row was
     * bound for none, so the abandoned-row counter and the error log below are its whole record. That
     * asymmetry against a refused send is asserted by a test rather than left to be rediscovered.
     *
     * @param claimed      the refused row, as the claim loaded it
     * @param failureClass class name of the failure, or a fixed reason for a row nobody can route
     * @param sourceTopic  the topic the row was bound for, or {@code null} when it was bound for none
     * @param deadline     monotonic deadline shared by the whole pass
     * @return what this attempt did
     */
    private Attempt recordRefusedRow(OutboxEventEntity claimed, String failureClass,
            String sourceTopic, long deadline) {
        Boolean abandoned = transactionTemplate.execute(status -> {
            Optional<OutboxEventEntity> found = outboxEvents.findById(claimed.getEventId());
            if (found.isEmpty()) {
                log.warn("A ledger outbox row disappeared between its claim and its failure "
                        + "record");
                return Boolean.FALSE;
            }
            OutboxEventEntity row = found.get();
            Instant now = clock.instant();
            row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
            outboxEvents.save(row);

            if (row.getRelayState() != RelayState.ABANDONED) {
                // The failed send itself is reported once, at warning, by SafeProducerListener,
                // which every template of this service installs. A second line here named the
                // same send, so an operator counting reports counted each one twice.
                return Boolean.FALSE;
            }

            if (sourceTopic == null) {
                log.error("A ledger outbox row of the unconfigured event type {} was abandoned "
                                + "after {} attempts. No dead letter names it, because the "
                                + "diagnostic declares the topic the row was bound for and this "
                                + "row was bound for none. Inspect outbox_event by event_id.",
                        row.getEventType(), row.getAttemptCount());
                return Boolean.TRUE;
            }

            publishDeadLetter(row, failureClass, sourceTopic, deadline);
            log.error("A ledger outbox row of type {} was abandoned after {} attempts, the last "
                            + "failing with {}. One dead letter names it on {}.",
                    row.getEventType(), row.getAttemptCount(), failureClass, deadLetterTopic);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(abandoned) ? Attempt.ABANDONED : Attempt.FAILED;
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
     * @param row          the row this relay gave up on
     * @param failureClass class name of the failure of its last attempt
     * @param sourceTopic  the topic the row was bound for
     * @param deadline     monotonic deadline shared by the whole sweep
     */
    private void publishDeadLetter(OutboxEventEntity row, String failureClass, String sourceTopic,
            long deadline) {
        DeadLetterEnvelope envelope = DeadLetterMetadata
                .fromFailure(ABANDONED_ROW_ABEND_CODE, new AbandonedRowException(failureClass),
                        ABANDONED_ROW_REASON, ABANDONED_ROW_MESSAGE)
                .toEnvelope(row.getAggregateId(), sourceTopic, NO_SOURCE_PARTITION,
                        NO_SOURCE_OFFSET, row.getEventId().toString(), row.getEventType(),
                        row.getAttemptCount());

        try (CorrelationScope _ = scopeOf(row)) {
            await(ledgerEventTemplate.send(
                    correlatedRecord(deadLetterTopic, row.getAggregateId(), envelope)), deadline);
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
     * identifier, so every record this relay publishes carries a correlation identifier a reader can
     * join on. A row carrying
     * neither opens a scope naming neither rather than failing the sweep.
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
     * Builds the record one send carries, with the two correlation headers of the open scope.
     *
     * <p>ADDITIVE. The headers travel beside the payload rather than inside it, so the five-field
     * envelope AAP 0.3.1 fixes is untouched and a consumer that ignores them is unaffected.
     *
     * @param topic       the destination topic
     * @param aggregateId the account identifier, which is the message key
     * @param payload     the event this record carries
     * @param <V>         the payload type
     * @return the record to send
     */
    private static <V> ProducerRecord<String, V> correlatedRecord(String topic,
            String aggregateId, V payload) {
        return new ProducerRecord<>(topic, null, aggregateId, payload,
                EventCorrelation.headersFor(
                        EventCorrelation.currentCorrelationId().orElse(null),
                        EventCorrelation.currentCausationId().orElse(null)));
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
     * The rows one pass holds, and the claims it recovered on the way to them.
     *
     * @param rows      the claimed account heads, at most one row per account
     * @param recovered claims a stopped instance left behind that this pass returned to pending
     */
    private record ClaimedBatch(List<OutboxEventEntity> rows, int recovered) {

        /** What a claim transaction that answered with nothing stands for. */
        static final ClaimedBatch EMPTY = new ClaimedBatch(List.of(), 0);
    }

    /**
     * One send this pass issued, or the reason it issued none.
     *
     * @param row          the claimed row
     * @param topic        the topic the row was bound for, {@code null} when it was bound for none
     * @param publication  the acknowledgement to await, or {@code null} when no send was issued
     * @param failureClass why no send was issued, or {@code null} when one was
     */
    private record Dispatch(OutboxEventEntity row, String topic, CompletableFuture<?> publication,
            String failureClass) {

        static Dispatch issued(OutboxEventEntity row, String topic,
                CompletableFuture<?> publication) {
            return new Dispatch(row, topic, Objects.requireNonNull(publication,
                    "the producer returned no acknowledgement"), null);
        }

        static Dispatch refused(OutboxEventEntity row, String topic, String failureClass) {
            return new Dispatch(row, topic, null, failureClass);
        }

        /** @return a row whose event type resolves to no topic, so no diagnostic can name one */
        static Dispatch unroutable(OutboxEventEntity row) {
            return new Dispatch(row, null, null, UNKNOWN_EVENT_TYPE);
        }
    }

    /**
     * What one attempt did, in the three counts one pass totals.
     *
     * @param published rows the broker acknowledged
     * @param failed    attempts that could not be completed
     * @param abandoned rows that reached {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS}
     */
    private record Attempt(int published, int failed, int abandoned) {

        static final Attempt PUBLISHED = new Attempt(1, 0, 0);
        static final Attempt FAILED = new Attempt(0, 1, 0);
        static final Attempt ABANDONED = new Attempt(0, 1, 1);
    }

    /**
     * Carries the class name of a last failure into the dead-letter metadata.
     *
     * <p>{@code DeadLetterMetadata.fromFailure} reads a throwable, and the failure a row was
     * abandoned for was raised in an earlier transaction and is no longer in hand. This carries the
     * one fact the diagnostic reports and no stack of its own.
     */
    private static final class AbandonedRowException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AbandonedRowException(String failureClass) {
            super(Objects.requireNonNull(failureClass, "a diagnostic names the failure it reports"),
                    null, false, false);
        }
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
