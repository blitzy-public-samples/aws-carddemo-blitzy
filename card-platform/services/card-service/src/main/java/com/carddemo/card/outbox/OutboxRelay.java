package com.carddemo.card.outbox;

import com.carddemo.card.config.CardProperties;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.messaging.DeadLetterMetadata;
import com.carddemo.card.messaging.EventPublisherPort;
import com.carddemo.card.repository.OutboxEventRepository;
import com.carddemo.events.correlation.CorrelationScope;
import com.carddemo.events.DeadLetterEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes the unpublished rows of {@code outbox_event}, then marks each one sent.
 *
 * <p>No CardDemo program relays an event. The source holds one asynchronous handoff:
 * paragraph {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl:L515-L523} writes one record to
 * a Customer Information Control System (CICS) transient data queue, and a separate job reads it
 * later. The write and the send are two units of work there, as they are here.
 *
 * <p>The card update path stores its row in the same local transaction as the card change it
 * describes. This class runs afterwards and handles one claimed batch in a separate transaction.
 * Request handling publishes nothing, so an unreachable broker delays an event and never fails an
 * update.
 *
 * <p>All eight {@code DEFINE FILE} blocks of {@code app/csd/CARDDEMO.CSD} carry
 * {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, eight occurrences of each. Atomicity and
 * idempotency have no COBOL ancestor.
 *
 * <p>A publish precedes its mark. A process that stops between the two publishes the row again on a
 * later sweep, so delivery is at least once and every consumer absorbs the repeat.
 *
 * <p>A row this relay gives up on is not silently dropped. After
 * {@value OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts the row reaches
 * {@link OutboxEventEntity.RelayState#ABANDONED} and this class publishes one governed
 * {@link DeadLetterEnvelope} naming it on {@code carddemo.kafka.topics.dead-letter}. That envelope
 * carries the four diagnostic components and no field of the payload, so an operator can reach the
 * abandoned event without a card number or an account identifier travelling on the diagnostic.
 * {@link EventPublisherPort#publish} waits for the broker, so the abandonment and the diagnostic
 * either both commit or neither does: a broker that refuses the diagnostic ends the sweep, the
 * boundary rolls the abandonment back, and the row is offered again rather than left terminal with
 * no record of it anywhere.
 *
 * <p>Scheduling is enabled on {@code com.carddemo.card.CardApplication}, which is what starts the
 * sweep. Every value this class reads comes from the {@code carddemo} block of
 * {@code src/main/resources/application.yml}.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * Failure code every recorded diagnostic carries, four characters wide, the width of
     * {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22}. The source abend routine
     * moves 999 into its own code at {@code app/cbl/CBTRN02C.cbl:L710}.
     */
    private static final String ABEND_CODE = "0999";

    /**
     * Component name every recorded diagnostic carries, eight characters wide, the width of
     * {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}. The source fills that
     * field from the eight-character {@code LIT-THISPGM} at {@code app/cbl/COCRDUPC.cbl:L219}.
     */
    private static final String CULPRIT = "CARDRLAY";

    /**
     * Sends this relay keeps in flight at once.
     *
     * <p>Five, because {@code max.in.flight.requests.per.connection} in
     * {@code src/main/resources/application.yml} is five: a wider window would not put a sixth
     * request on the wire, it would only leave a sixth send sitting in the producer's accumulator
     * unawaited. Narrower than five leaves the pipeline idle while this sweep waits.
     *
     * <p>Every row of one window names a different account, because the claim query returns one head
     * row per aggregate, so a window is concurrent across accounts and never within one.
     */
    static final int MAX_SENDS_IN_FLIGHT = 5;

    /**
     * Failure classification a diagnostic carries when the broker refused every attempt, at most
     * {@value DeadLetterMetadata#REASON_MAX_LENGTH} characters kept.
     */
    private static final String REFUSED_REASON = "broker refused every delivery attempt";

    /**
     * Failure detail a diagnostic carries when the broker refused every attempt, at most
     * {@value DeadLetterMetadata#MESSAGE_MAX_LENGTH} characters kept.
     */
    private static final String REFUSED_MESSAGE = "outbox row abandoned after its attempts were "
            + "spent";

    /**
     * The {@code sourceTopic} a diagnostic carries when the stored event type names no topic of this
     * service.
     *
     * <p>The value satisfies the {@code sourceTopic} pattern of {@code schemas/dead-letter-v1.json},
     * which admits letters, digits, the period, the underscore and the hyphen. A placeholder holding
     * a space or a bracket would read well in a log and would then be refused by the one gate the
     * diagnostic has to pass, which would lose the diagnostic for exactly the row that needs it.
     */
    private static final String UNRESOLVED_DESTINATION = "no-configured-topic";

    /** An outbox row has no Kafka coordinates, so the envelope reports the first partition. */
    private static final int NO_SOURCE_PARTITION = 0;

    /** An outbox row has no Kafka coordinates, so the envelope reports the first offset. */
    private static final long NO_SOURCE_OFFSET = 0L;

    /**
     * The {@code failedEventType} shape {@code schemas/dead-letter-v1.json} admits.
     *
     * <p>A stored event type this relay cannot publish is one of the reasons a row is abandoned, and
     * such a value has not passed any contract on the way in. The document admits null for this
     * field, so a value outside this shape is reported as absent rather than turned into a second
     * failure.
     */
    private static final Pattern GOVERNED_EVENT_TYPE = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,63}$");

    /** Reads unpublished rows and stores the mark this class writes. */
    private final OutboxEventRepository outboxEvents;

    /** Sends one payload to one topic under one key. */
    private final EventPublisherPort publisher;

    /** Counts events that reached the broker, from {@code carddemo.card.events.published}. */
    private final Counter eventsPublished;

    /** Counts attempts that failed, from {@code carddemo.card.failures}. */
    private final Counter infrastructureFailures;

    /**
     * Counts rows this relay gave up on, from {@code carddemo.card.outbox.abandoned}. One increment
     * is one card update no consumer will ever see, and it is recorded only after the broker has
     * acknowledged the dead letter naming that row.
     */
    private final Counter outboxAbandoned;

    /**
     * Counts terminal diagnostics the broker refused, from
     * {@code carddemo.card.dead.letters.failed}. It stays apart from {@link #infrastructureFailures}
     * because a refusal here concerns a row whose attempts are already spent.
     */
    private final Counter deadLettersFailed;

    /** Times one publish attempt, from {@code carddemo.card.publish.latency}. */
    private final Timer publishLatency;

    /** Rows one sweep claims, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Sends awaited together, {@link #MAX_SENDS_IN_FLIGHT} or the batch when it is smaller. */
    private final int sendsInFlight;

    /** Topic a card update travels on, from {@code carddemo.kafka.topics.card-updated}. */
    private final String cardUpdatedTopic;

    /**
     * Topic a terminal diagnostic travels on, from {@code carddemo.kafka.topics.dead-letter}. It is
     * shared by every service and carries no card update.
     */
    private final String deadLetterTopic;

    /**
     * Renders one {@link DeadLetterEnvelope} as the text the publish port takes.
     *
     * <p>{@code outbox/OutboxWriter} builds its payload the same way, so the diagnostic and the
     * event it names are serialized identically and the one schema gate reads both.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The boundary one sweep runs inside, so the claim holds and no counter joins it. */
    private final TransactionTemplate transactionTemplate;

    /** Base of the retry backoff, from {@code carddemo.outbox.relay.fixed-delay-ms}. */
    private final Duration sweepDelay;

    /** How long a claim may stand before another sweep recovers it, and the backoff ceiling. */
    private final Duration claimTimeout;

    /** What this instance writes into {@code claimed_by}, so a claim can be traced to a process. */
    private final String instanceId;

    /**
     * Wall time one sweep may spend waiting for broker acknowledgements, in nanoseconds.
     *
     * <p>Nanoseconds because the deadline is measured from {@link System#nanoTime()}, which is
     * monotonic. A wall clock stepped backwards by an adjustment would extend a sweep that was
     * already over its bound.
     */
    private final long maxDurationNanos;

    /**
     * Takes the store, the publish port, the five meters and the configured values.
     *
     * <p>No property below carries a default here. An absent {@code carddemo} block stops start-up
     * with the missing key named, and a blank topic name is refused by {@link CardProperties}.
     *
     * @param outboxEvents           store of unpublished rows
     * @param publisher              the event bus seam, which one new implementation replaces
     * @param eventsPublished        counter incremented after a publish succeeds
     * @param infrastructureFailures counter incremented after an attempt fails
     * @param outboxAbandoned        counter incremented once per row this relay gave up on
     * @param deadLettersFailed      counter incremented once per refused terminal diagnostic
     * @param timers                 holder of the publish attempt timer
     * @param cardUpdatedTopic       topic a card update travels on
     * @param transactionTemplate    the boundary one sweep runs inside
     * @param properties             the bound {@code carddemo} block, read for the relay settings
     *                               and for the dead-letter topic
     * @throws NullPointerException if any collaborator is absent
     */
    public OutboxRelay(OutboxEventRepository outboxEvents, EventPublisherPort publisher,
            @Qualifier("cardEventsPublishedCounter") Counter eventsPublished,
            @Qualifier("cardInfrastructureFailureCounter") Counter infrastructureFailures,
            @Qualifier("cardOutboxAbandonedCounter") Counter outboxAbandoned,
            @Qualifier("cardDeadLettersFailedCounter") Counter deadLettersFailed,
            CardLatencyTimers timers,
            @Value("${carddemo.kafka.topics.card-updated}") String cardUpdatedTopic,
            TransactionTemplate transactionTemplate,
            CardProperties properties) {

        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.eventsPublished = Objects.requireNonNull(eventsPublished, "eventsPublished");
        this.infrastructureFailures =
                Objects.requireNonNull(infrastructureFailures, "infrastructureFailures");
        this.outboxAbandoned = Objects.requireNonNull(outboxAbandoned, "outboxAbandoned");
        this.deadLettersFailed = Objects.requireNonNull(deadLettersFailed, "deadLettersFailed");
        this.publishLatency = Objects.requireNonNull(timers, "timers").eventPublish();
        this.cardUpdatedTopic = Objects.requireNonNull(cardUpdatedTopic, "cardUpdatedTopic");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");

        CardProperties checked = Objects.requireNonNull(properties, "properties");
        this.deadLetterTopic = Objects.requireNonNull(checked.kafka().topics().deadLetter(),
                "deadLetterTopic");
        CardProperties.Outbox.Relay relay = checked.outbox().relay();
        this.batchSize = relay.batchSize();
        this.sendsInFlight = Math.min(this.batchSize, MAX_SENDS_IN_FLIGHT);
        this.sweepDelay = Duration.ofMillis(relay.fixedDelayMs());
        this.claimTimeout = relay.claimTimeout();
        this.instanceId = relay.instanceId();
        this.maxDurationNanos = Duration.ofMillis(relay.maxDurationMs()).toNanos();
    }

    /**
     * Runs one sweep and records what it did once every transaction of that sweep has committed.
     *
     * <p>This method holds no transaction, and neither does the sweep as a whole. Each step opens the
     * shortest transaction it needs: one to claim the due rows, then one per outcome. A batch of
     * {@code batch-size} rows waiting on a broker inside one transaction held a database connection
     * and every row lock it took for the sum of those waits, and every later event of every account
     * waited behind it.
     *
     * <p>The counters sit outside every one of those transactions. A counter takes no part in a
     * database transaction, so an increment made inside one survives a rollback and reports rows as
     * published that were never marked.
     *
     * <p>No failure leaves this method. An unreachable database or broker yields one log line and one
     * counted failure per sweep, and the schedule carries on.
     *
     * <p>A refused terminal diagnostic is counted in its own series, because it says something
     * different from a counted attempt. A counted infrastructure failure is one attempt on a row still
     * in flight. A refused diagnostic means the row whose attempts are spent has no record of it on
     * the topic yet, and because that one row's transaction rolled its abandonment back, the row is
     * claimable again and a later sweep offers it and its diagnostic together.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    public void publishPendingEvents() {
        long deadline = System.nanoTime() + maxDurationNanos;
        SweepResult result;
        try {
            result = runOnePass(deadline);
        } catch (RelayDeadlineExceededException lapsed) {
            log.warn("The outbox sweep reached its {} deadline before it finished, so the rows it "
                            + "claimed are offered to the next sweep.",
                    Duration.ofNanos(maxDurationNanos));
            return;
        } catch (RuntimeException failure) {
            infrastructureFailures.increment();
            log.warn("The outbox sweep ended early after {}, and a later sweep runs again",
                    failure.getClass().getSimpleName());
            return;
        }
        Objects.requireNonNull(result, "the sweep must answer with a result").record(this);
    }

    /**
     * Recovers stranded claims, claims the due account heads and publishes them.
     *
     * <p>Sends are issued {@value #MAX_SENDS_IN_FLIGHT} at a time and each is awaited against the one
     * deadline this sweep shares. {@link OutboxEventRepository#claimDueRows} returns the due head row
     * of each aggregate and never two rows of one account, so the rows of one window name distinct
     * accounts and no two events of one account are ever in flight together. Every message carries the
     * account identifier as its key, so that restriction is what keeps one account's events in the
     * order their updates committed.
     *
     * <p>A row the broker refuses pauses its own account for the sweep and leaves every other account
     * eligible. Such a row becomes due again after a backoff and reaches
     * {@link OutboxEventEntity.RelayState#ABANDONED} after
     * {@value OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts, from which point the claim query
     * stops returning it.
     *
     * <p>The sweep is idle when no card changed, since a card list and a card read store no row.
     *
     * @param deadline the monotonic instant this sweep must not publish beyond
     * @return what this sweep published, how many failures it recorded and how many rows it abandoned
     */
    private SweepResult runOnePass(long deadline) {
        Instant now = Instant.now();
        Recovery recovery = recoverStrandedClaims(now, deadline);
        int failures = recovery.attempts();
        int abandoned = recovery.abandoned();
        int refusedDiagnostics = recovery.refusedDiagnostics();
        List<Timer.Sample> publishAttempts = new ArrayList<>();

        List<OutboxEventEntity> rows = claimBatch(now);
        int published = 0;

        for (int from = 0; from < rows.size(); from += sendsInFlight) {
            if (System.nanoTime() - deadline >= 0L) {
                log.debug("The card outbox sweep reached its deadline with {} claimed rows "
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
                failures = failures + outcome.failed();
                abandoned = abandoned + outcome.abandoned();
                refusedDiagnostics = refusedDiagnostics + outcome.refusedDiagnostics();
                if (outcome.sample() != null) {
                    publishAttempts.add(outcome.sample());
                }
            }
        }
        return new SweepResult(published, failures, abandoned, refusedDiagnostics, publishAttempts);
    }

    /**
     * Claims the due account heads in one transaction that commits before any send starts.
     *
     * <p>The claim depends on that transaction: {@link OutboxEventRepository#claimDueRows} holds each
     * row it returns with {@code FOR NO KEY UPDATE ... SKIP LOCKED}, and that lock lives exactly as
     * long as the transaction that took it. Two instances of this service — one rolling deployment,
     * one manual scale-out — therefore claim disjoint batches instead of both publishing every row.
     * {@code claimed_by} is what survives the commit, and a claim whose instance died is recovered by
     * {@link #recoverStrandedClaims(Instant, long)}.
     *
     * @param now the moment this sweep started
     * @return the claimed rows, longest-waiting first, at most one row per account
     */
    private List<OutboxEventEntity> claimBatch(Instant now) {
        List<OutboxEventEntity> claimed = transactionTemplate.execute(status -> {
            List<OutboxEventEntity> due = outboxEvents.claimDueRows(now, Limit.of(batchSize));
            for (OutboxEventEntity row : due) {
                row.claim(instanceId, now);
                outboxEvents.save(row);
            }
            return List.copyOf(due);
        });
        return claimed == null ? List.of() : claimed;
    }

    /**
     * Issues the send for one claimed row, outside every transaction, and waits for nothing.
     *
     * <p>A port that refuses the send here rather than through its acknowledgement is still one row's
     * failure and not the sweep's, so every runtime failure is caught and carried back as this row's
     * outcome. A producer that cannot reach the broker at all raises on the call, and letting that
     * leave this method would end the sweep at the first such row and leave every other account
     * unattempted.
     *
     * @param row the claimed row
     * @return the send to await, or the reason no send was issued
     */
    private Dispatch dispatch(OutboxEventEntity row) {
        if (!CardUpdated.EVENT_TYPE.equals(row.getEventType())) {
            return Dispatch.unpublishable(row, "no configured topic for the stored event type",
                    "stored event type reaches no topic of this service");
        }
        Timer.Sample publishAttempt = Timer.start();
        try (CorrelationScope scope = scopeOf(row)) {
            return Dispatch.issued(row, publishAttempt,
                    publisher.publish(cardUpdatedTopic, row.getAggregateId(), row.getPayload())
                            .toCompletableFuture());
        } catch (IllegalArgumentException refused) {
            return Dispatch.refused(row, publishAttempt, refused,
                    "publish port refused: " + refused.getClass().getSimpleName(),
                    "payload refused for topic " + cardUpdatedTopic);
        } catch (RuntimeException notSent) {
            // Reason and message left absent, so this row is diagnosed exactly as a refused
            // acknowledgement is: the failure's own class name and the shared refusal wording.
            return Dispatch.refused(row, publishAttempt, notSent, null, null);
        }
    }

    /**
     * Waits for one acknowledgement, then records that outcome in a transaction of its own.
     *
     * <p>The mark is written after the broker acknowledges, so no row is marked for a message the
     * broker did not accept. A send that returns while the mark fails leaves the row claimed until the
     * claim timeout returns it, a later sweep sends it again, and the processed-event table of each
     * consumer absorbs the duplicate.
     *
     * @param dispatch what {@link #dispatch(OutboxEventEntity)} issued
     * @param deadline the monotonic instant this sweep must not publish beyond
     * @return what this attempt did
     */
    private Attempt settle(Dispatch dispatch, long deadline) {
        OutboxEventEntity row = dispatch.row();
        if (dispatch.publication() == null) {
            if (dispatch.refusal() == null) {
                log.error("Card event {} carries no publishable form: {}", row.getEventId(),
                        dispatch.reason());
                return recordUnpublishable(row, dispatch.reason(), dispatch.message(), deadline)
                        .withSample(dispatch.sample());
            }
            return recordRefusedRow(row, dispatch.refusal(), dispatch.reason(), dispatch.message(),
                    deadline).withSample(dispatch.sample());
        }
        try {
            await(dispatch.publication(), deadline);
        } catch (RelayDeadlineExceededException lapsed) {
            throw lapsed;
        } catch (RuntimeException failure) {
            // The failed send itself is reported once, at warning, by SafeProducerListener,
            // which every template of this service installs. A second line here named the
            // same send, so an operator counting reports counted each one twice.
            return recordRefusedRow(row, failure, null, null, deadline)
                    .withSample(dispatch.sample());
        }
        markPublished(row.getEventId());
        return Attempt.published(dispatch.sample());
    }

    /**
     * Marks one row published, in a transaction of its own.
     *
     * <p>The row is re-read inside that transaction rather than saved from the copy the claim loaded.
     * A detached copy carries the state it had before the claim committed, and saving it would write
     * that older state back over the claim.
     *
     * @param eventId the row the broker accepted
     */
    private void markPublished(UUID eventId) {
        transactionTemplate.execute(status -> {
            outboxEvents.findById(eventId).ifPresentOrElse(stored -> {
                stored.markPublished(Instant.now());
                outboxEvents.save(stored);
            }, () -> log.warn("A card outbox row disappeared between its claim and its mark"));
            return null;
        });
    }

    /**
     * Returns rows a dead instance left claimed to {@link OutboxEventEntity.RelayState#PENDING}, one
     * short transaction per row.
     *
     * <p>Without this one crash costs one event permanently: the row stays
     * {@link OutboxEventEntity.RelayState#CLAIMED}, the claim query filters on
     * {@link OutboxEventEntity.RelayState#PENDING}, and nothing looks at it again. The recovery counts
     * as an attempt, so a row that strands repeatedly is eventually abandoned.
     *
     * @param now      the moment this sweep started
     * @param deadline the monotonic instant this sweep must not publish beyond
     * @return how many rows were recovered, how many of them were abandoned, and how many of those
     *         diagnostics the broker refused
     */
    private Recovery recoverStrandedClaims(Instant now, long deadline) {
        List<OutboxEventEntity> stranded = readStrandedClaims(now);

        int abandoned = 0;
        int refusedDiagnostics = 0;
        for (OutboxEventEntity row : stranded) {
            Attempt recovered = recordFailure(row.getEventId(), "claim expired",
                    DeadLetterMetadata.of(ABEND_CODE, CULPRIT, "claim expired on every attempt",
                            "outbox row stranded by a relay instance that did not finish"),
                    deadline);
            abandoned = abandoned + recovered.abandoned();
            refusedDiagnostics = refusedDiagnostics + recovered.refusedDiagnostics();
        }
        return new Recovery(stranded.size(), abandoned, refusedDiagnostics);
    }

    /**
     * Reads the claims a stopped instance left behind, in a transaction of its own.
     *
     * <p>{@link OutboxEventRepository#findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc} holds
     * each row it returns with {@code FOR NO KEY UPDATE ... SKIP LOCKED}, so it needs a transaction
     * to take that lock in and would raise without one. The transaction ends here rather than
     * enclosing the recovery, because each recovered row is then recorded in a transaction of its
     * own, and one of those may publish a diagnostic and wait for the broker.
     *
     * <p>The rows are detached by the time this returns. Every field the recovery reads is a column
     * of the row itself, so nothing is loaded after the transaction closes.
     *
     * @param now the moment this sweep started
     * @return the stranded rows, oldest claim first, at most one batch of them
     */
    private List<OutboxEventEntity> readStrandedClaims(Instant now) {
        List<OutboxEventEntity> stranded = transactionTemplate.execute(status ->
                List.copyOf(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                        OutboxEventEntity.RelayState.CLAIMED, now.minus(claimTimeout),
                        Limit.of(batchSize))));
        return stranded == null ? List.of() : stranded;
    }

    /**
     * What one recovery pass did, so its outcomes reach the meters separately.
     *
     * @param attempts           attempts this pass recorded against rows a dead instance left claimed
     * @param abandoned          rows of that set this relay gave up on, each named by an acknowledged
     *                           diagnostic
     * @param refusedDiagnostics diagnostics of that set the broker refused, each leaving its row
     *                           claimable and its abandonment rolled back
     */
    private record Recovery(int attempts, int abandoned, int refusedDiagnostics) {
    }

    /**
     * Records one attempt against a row the broker did not accept, and schedules the next attempt.
     *
     * <p>Only the failure's class name is stored and logged. A broker or database failure message can
     * quote the row it was raised for, and this row's payload holds an account identifier and a masked
     * card number.
     *
     * @param row      the row the broker refused
     * @param failure  the failure the publish raised
     * @param reason   the failure classification for the diagnostic, or {@code null} to take the one
     *                 the refusal itself supplies
     * @param message  the failure detail for the diagnostic, or {@code null} for the same reason
     * @param deadline the monotonic instant this sweep must not publish beyond
     * @return what this attempt did
     */
    private Attempt recordRefusedRow(OutboxEventEntity row, RuntimeException failure, String reason,
            String message, long deadline) {
        DeadLetterMetadata diagnostics = reason == null
                ? DeadLetterMetadata.fromFailure(ABEND_CODE, failure, REFUSED_REASON,
                        REFUSED_MESSAGE)
                : DeadLetterMetadata.of(ABEND_CODE, CULPRIT, reason, message);
        return recordFailure(row.getEventId(), failure.getClass().getSimpleName(), diagnostics,
                deadline);
    }

    /**
     * Records one failed attempt on a row no send will carry, and schedules it for the next sweep.
     *
     * <p>The four diagnostic components follow {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21}, which bounds them to 4, 8, 50 and 72 characters.
     * {@link DeadLetterMetadata} shortens each component to its own width and holds no card number,
     * no account identifier and no fragment of the payload.
     *
     * <p>The source answered an unwritable record with the abend routine at
     * {@code app/cbl/CBTRN02C.cbl:L707-L711}, reached from {@code :L577}. Here the row records the
     * attempt and the container keeps running.
     *
     * @param row      the row no send will carry
     * @param reason   the failure classification, at most 50 characters kept
     * @param message  the failure detail, at most 72 characters kept
     * @param deadline the monotonic instant this sweep must not publish beyond
     * @return what this attempt did
     */
    private Attempt recordUnpublishable(OutboxEventEntity row, String reason, String message,
            long deadline) {
        DeadLetterMetadata diagnostics = DeadLetterMetadata.of(ABEND_CODE, CULPRIT, reason, message);
        return recordFailure(row.getEventId(), describe(diagnostics), diagnostics, deadline);
    }

    /**
     * Records one failed attempt, and names the row on the dead-letter topic when that attempt gave up
     * on it, all in one transaction of its own.
     *
     * <p>The row is re-read inside that transaction rather than saved from the copy the claim loaded,
     * for the same reason {@link #markPublished(UUID)} re-reads it.
     *
     * <p>The diagnostic of an abandoned row is published inside this same transaction and waited for,
     * so a broker that refuses it rolls that row's abandonment back and the next sweep offers the row
     * and its diagnostic together. This service records no separate obligation, so the rollback is
     * what keeps an abandoned row from ending terminal with no record of it anywhere. It is the one
     * transaction of a sweep that spans a send, it spans exactly one, and it is opened only by the
     * attempt that gives up on a row.
     *
     * @param eventId     the row this attempt failed on
     * @param diagnostic  what to record in {@code last_error}, carrying no value of the row
     * @param diagnostics the four components naming this failure on the dead-letter topic
     * @param deadline    the monotonic instant this sweep must not publish beyond
     * @return what this attempt did
     */
    private Attempt recordFailure(UUID eventId, String diagnostic, DeadLetterMetadata diagnostics,
            long deadline) {
        Attempt recorded = transactionTemplate.execute(status -> {
            java.util.Optional<OutboxEventEntity> found = outboxEvents.findById(eventId);
            if (found.isEmpty()) {
                log.warn("A card outbox row disappeared between its claim and its failure record");
                return Attempt.FAILED;
            }
            OutboxEventEntity row = found.get();
            Instant now = Instant.now();
            row.recordFailure(diagnostic, now, now.plus(backoffAfter(row.getAttemptCount())));
            outboxEvents.save(row);
            log.warn("Card event {} recorded attempt {} of {} and stays unpublished.",
                    row.getEventId(), row.getAttemptCount(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
            try {
                return deadLetterIfAbandoned(row, diagnostics, deadline)
                        ? Attempt.ABANDONED
                        : Attempt.FAILED;
            } catch (DeadLetterRefusedException refused) {
                // The abandonment goes back with this transaction, so the row is claimable again and
                // a later sweep offers it and its diagnostic together. Rolling back is the whole
                // point: a terminal row whose only record reached nowhere is the loss this prevents.
                status.setRollbackOnly();
                log.error("The dead letter naming card event {} did not reach {} after a {}. That "
                                + "row is not abandoned, and a later sweep offers it and its "
                                + "diagnostic again.", refused.eventId(), deadLetterTopic,
                        refused.failureClass());
                return Attempt.DIAGNOSTIC_REFUSED;
            }
        });
        return recorded == null ? Attempt.FAILED : recorded;
    }

    /**
     * Returns how long to wait before attempting a row again.
     *
     * <p>The wait doubles per attempt from the sweep delay and stops at the claim timeout, so a row
     * the broker keeps refusing is retried less and less often while always staying claimable within
     * one timeout. Both bounds are configured values rather than numbers written here.
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
     * What one sweep did, carried out of the transaction so it can be counted after the commit.
     *
     * @param published       rows the broker accepted and this sweep marked
     * @param failed          attempts this sweep recorded against rows it could not publish
     * @param abandoned       rows this sweep gave up on, each named by an acknowledged diagnostic
     * @param publishAttempts one timing sample per publish attempt this sweep made
     */
    private record SweepResult(int published, int failed, int abandoned, int refusedDiagnostics,
            List<Timer.Sample> publishAttempts) {

        private SweepResult {
            publishAttempts = List.copyOf(publishAttempts);
        }

        /**
         * Records this result against the meters of {@code relay}.
         *
         * @param relay the relay whose counters this result belongs to
         */
        void record(OutboxRelay relay) {
            for (Timer.Sample attempt : publishAttempts) {
                attempt.stop(relay.publishLatency);
            }
            for (int row = 0; row < published; row++) {
                relay.eventsPublished.increment();
            }
            for (int failure = 0; failure < failed; failure++) {
                relay.infrastructureFailures.increment();
            }
            for (int given = 0; given < abandoned; given++) {
                relay.outboxAbandoned.increment();
            }
            for (int refused = 0; refused < refusedDiagnostics; refused++) {
                relay.deadLettersFailed.increment();
            }
        }
    }

    /**
     * Opens the correlation scope of one row, so the send it carries and every line written about it
     * name the unit of work behind it.
     *
     * <p>ADDITIVE. A relay sweeps on its own schedule, long after the thread that wrote the row has
     * gone, so the two identifiers are read back off the row rather than inherited from a caller.
     * The scope is what the publish port reads to attach the two record headers.
     *
     * <p>A row recording no correlation identifier starts its own trace under its own event
     * identifier. That covers a row written before this column existed, so every record this
     * relay publishes carries a correlation identifier a reader can join on. A row carrying
     * neither opens a scope naming neither rather than failing the sweep.
     *
     * @param row the row this sweep is working on
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
     * One send this sweep issued, or the reason it issued none.
     *
     * @param row         the claimed row
     * @param sample      the timing sample of the publish attempt, or {@code null} when no send was
     *                    attempted at all
     * @param publication the acknowledgement to await, or {@code null} when no send was issued
     * @param refusal     the failure the publish port raised before any send, or {@code null}
     * @param reason      the diagnostic classification for a row nothing will carry, or {@code null}
     * @param message     the diagnostic detail for a row nothing will carry, or {@code null}
     */
    private record Dispatch(OutboxEventEntity row, Timer.Sample sample,
            CompletableFuture<?> publication, RuntimeException refusal, String reason,
            String message) {

        static Dispatch issued(OutboxEventEntity row, Timer.Sample sample,
                CompletableFuture<?> publication) {
            return new Dispatch(row, sample, Objects.requireNonNull(publication,
                    "the producer returned no acknowledgement"), null, null, null);
        }

        static Dispatch refused(OutboxEventEntity row, Timer.Sample sample,
                RuntimeException refusal, String reason, String message) {
            return new Dispatch(row, sample, null, refusal, reason, message);
        }

        static Dispatch unpublishable(OutboxEventEntity row, String reason, String message) {
            return new Dispatch(row, null, null, null, reason, message);
        }
    }

    /**
     * What one attempt did, in the counts one sweep totals.
     *
     * @param published          rows the broker acknowledged, one or none
     * @param failed             attempts that could not be completed, one or none
     * @param abandoned          rows given up on with an acknowledged diagnostic, one or none
     * @param refusedDiagnostics diagnostics the broker refused, one or none
     * @param sample             the timing sample of the publish attempt, or {@code null}
     */
    private record Attempt(int published, int failed, int abandoned, int refusedDiagnostics,
            Timer.Sample sample) {

        /** One row the broker refused, still in flight. */
        private static final Attempt FAILED = new Attempt(0, 1, 0, 0, null);

        /** One row given up on, named by an acknowledged diagnostic. */
        private static final Attempt ABANDONED = new Attempt(0, 1, 1, 0, null);

        /**
         * One row whose diagnostic the broker refused, so its abandonment rolled back.
         *
         * <p>Counted in the refused-diagnostic series alone. An infrastructure failure is one attempt
         * on a row still in flight, and this row's attempts are spent, so adding it to that series
         * would report the same event twice under two meanings.
         */
        private static final Attempt DIAGNOSTIC_REFUSED = new Attempt(0, 0, 0, 1, null);

        static Attempt published(Timer.Sample sample) {
            return new Attempt(1, 0, 0, 0, sample);
        }

        Attempt withSample(Timer.Sample taken) {
            return taken == null ? this
                    : new Attempt(published, failed, abandoned, refusedDiagnostics, taken);
        }
    }

    /**
     * Publishes one governed diagnostic for a row this relay has just given up on, and does nothing
     * for a row that is still in flight.
     *
     * <p>{@link OutboxEventEntity#recordFailure} moves a row to
     * {@link OutboxEventEntity.RelayState#ABANDONED} once its attempts are spent, and the claim query
     * stops returning it from that moment. This is therefore the one place an abandoned card event is
     * named anywhere outside its own database, which is why the send is awaited rather than left in
     * flight: {@link EventPublisherPort#publish} answers a {@link java.util.concurrent.CompletionStage}
     * and {@link #await} holds this thread until the broker acknowledges the record, bounded by the
     * sweep deadline that {@code carddemo.outbox.relay.publish-timeout} feeds.
     *
     * <p>A refusal is raised rather than swallowed. This send is the one a sweep makes inside a
     * transaction — {@link #recordFailure} opened it and no other send of the sweep is inside one —
     * so the raised failure rolls the abandonment back with it, along with the attempt just recorded,
     * and the row returns to the claim query with its attempt count as it was. A later sweep therefore offers the row and its diagnostic together, and
     * neither is lost. Swallowing the refusal instead would leave the row terminal with its only
     * record nowhere, which is the loss this method exists to prevent.
     *
     * <p>The envelope is the governed form of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}. It names the row through {@code failedEventId} and
     * {@code failedEventType} and carries no field of the payload, so no card number, no card
     * verification value and no account identifier reaches the topic on the diagnostic. The account
     * identifier is the message key, exactly as it is for a card update, so a diagnostic and the
     * events it follows stay on one partition.
     *
     * @param row         the row whose latest failure has just been recorded
     * @param diagnostics the four components describing that failure
     * @return {@code true} when the row was abandoned and the broker acknowledged its diagnostic,
     *         {@code false} when the row is still in flight
     * @throws DeadLetterRefusedException when the broker refused the diagnostic
     */
    private boolean deadLetterIfAbandoned(OutboxEventEntity row,
            DeadLetterMetadata diagnostics, long deadline) {
        if (row.getRelayState() != OutboxEventEntity.RelayState.ABANDONED) {
            return false;
        }

        DeadLetterEnvelope envelope = diagnostics.toEnvelope(row.getAggregateId(),
                sourceTopicOf(row.getEventType()), NO_SOURCE_PARTITION, NO_SOURCE_OFFSET,
                row.getEventId().toString(), governedTypeOrAbsent(row.getEventType()),
                row.getAttemptCount());

        try (CorrelationScope scope = scopeOf(row)) {
            await(publisher.publish(deadLetterTopic, row.getAggregateId(),
                    objectMapper.writeValueAsString(envelope)).toCompletableFuture(), deadline);
        } catch (RelayDeadlineExceededException lapsed) {
            throw lapsed;
        } catch (RuntimeException refused) {
            throw new DeadLetterRefusedException(row.getEventId(), refused);
        }

        log.error("Card event {} was abandoned after {} attempts, and one dead letter names it on "
                        + "{}. No consumer will see that update.",
                row.getEventId(), row.getAttemptCount(), deadLetterTopic);
        return true;
    }

    /**
     * Waits for one acknowledgement, no longer than the sweep has left.
     *
     * <p>The wait is what makes the publish observed. Without it the sweep would mark a row
     * published on the strength of a send having been started, and a send the broker refused would
     * leave a row marked for a message no consumer ever sees.
     *
     * <p>The bound is the sweep's, not the send's. {@code messaging/KafkaEventPublisher} already
     * fails its stage at {@code carddemo.outbox.relay.publish-timeout}, but that bound applies to
     * each send separately, so a sweep of {@code batch-size} rows could spend it once per row. This
     * deadline is taken once for the whole sweep, from {@link System#nanoTime()}, and every send
     * shares it.
     *
     * <p>A wait that runs out cancels what it gave up on. Cancelling does not recall a record the
     * producer already placed, which is why the producer window in
     * {@code src/main/resources/application.yml} expires first: by the time this deadline is
     * reached the producer has stopped trying too, so the retry on a later sweep cannot race a
     * send still in flight.
     *
     * @param publication the stage the acknowledgement completes
     * @param deadline    the monotonic instant this sweep must not publish beyond
     * @throws RelayDeadlineExceededException when the sweep has no time left, or the wait runs out
     * @throws RuntimeException               when the send itself failed
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
            throw new IllegalStateException("the card outbox relay was interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("a card outbox publication failed", cause);
        } catch (TimeoutException timedOut) {
            publication.cancel(true);
            throw new RelayDeadlineExceededException();
        }
    }

    /**
     * Reports that one sweep reached its configured deadline.
     *
     * <p>Carried out through the transaction boundary, so the sweep rolls back and every row it
     * claimed becomes claimable again. No row is left marked published on a send that was
     * abandoned.
     */
    private static final class RelayDeadlineExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RelayDeadlineExceededException() {
            super("the card outbox relay reached its configured sweep deadline");
        }
    }

    /**
     * Names the topic the abandoned event was meant for.
     *
     * @param eventType the stored event type
     * @return the configured card update topic, or {@link #UNRESOLVED_DESTINATION} when the stored
     *         type names no topic of this service
     */
    private String sourceTopicOf(String eventType) {
        return CardUpdated.EVENT_TYPE.equals(eventType) ? cardUpdatedTopic
                : UNRESOLVED_DESTINATION;
    }

    /**
     * Returns one stored event type when the dead-letter contract admits its shape, and null
     * otherwise.
     *
     * @param eventType the stored event type
     * @return the event type, or null when it is absent or outside {@link #GOVERNED_EVENT_TYPE}
     */
    private static String governedTypeOrAbsent(String eventType) {
        return eventType != null && GOVERNED_EVENT_TYPE.matcher(eventType).matches() ? eventType
                : null;
    }

    /**
     * Carries a refused diagnostic out of the sweep, so the boundary rolls the abandonment back with
     * it and the counter for a refused diagnostic stays apart from the counter for a failed attempt.
     */
    private static final class DeadLetterRefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The row whose diagnostic was refused. */
        private final UUID eventId;

        /** The simple name of the refusal, which carries no value of the row. */
        private final String failureClass;

        /**
         * Takes the row and the refusal.
         *
         * @param eventId the identifier of the row whose diagnostic was refused
         * @param refused the failure the publish raised, kept as its cause
         */
        DeadLetterRefusedException(UUID eventId, RuntimeException refused) {
            super("the dead letter naming outbox event " + eventId + " was refused", refused);
            this.eventId = eventId;
            this.failureClass = refused.getClass().getSimpleName();
        }

        /**
         * The row whose diagnostic was refused.
         *
         * @return the event identifier
         */
        UUID eventId() {
            return eventId;
        }

        /**
         * The kind of refusal, named without any value of the row.
         *
         * @return the simple class name of the refusal
         */
        String failureClass() {
            return failureClass;
        }
    }

    /**
     * Joins the four diagnostic components into one line for the row and the log.
     *
     * <p>The joined text runs to at most 137 characters, inside the width the row stores.
     *
     * @param diagnostics the shortened diagnostic components
     * @return the four components separated by single spaces
     */
    private static String describe(DeadLetterMetadata diagnostics) {
        return diagnostics.abendCode() + " " + diagnostics.culprit() + " " + diagnostics.reason()
                + " " + diagnostics.message();
    }
}
