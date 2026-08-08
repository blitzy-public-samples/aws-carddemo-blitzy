package com.carddemo.authorization.outbox;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.config.ObservabilityConfig;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.entity.OutboxEventEntity.DeadLetterState;
import com.carddemo.authorization.entity.OutboxEventEntity.RelayState;
import com.carddemo.authorization.messaging.DeadLetterMetadata;
import com.carddemo.authorization.messaging.EventPublisherPort;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.EventEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the rows {@code OutboxWriter} stored, outside the transaction that recorded them.
 *
 * <p>This class plays the pick-up half of the one asynchronous handoff the source has. At
 * {@code app/cbl/CORPT00C.cbl:L515-L523} paragraph {@code WIRTE-JOBSUB-TDQ} writes one record to a
 * Customer Information Control System (CICS) transient data queue, and a separate job reads it
 * later. The write and the send are two units of work there, as they are here.
 *
 * <p>Request handling publishes nothing. The decision and its outbox row commit first, and this
 * class sends afterwards, so a broker that is unreachable delays an event and never fails a
 * decision.
 *
 * <h2>Why one pass is several transactions</h2>
 *
 * <p>A pass takes three kinds of transaction, and the split is the point rather than a detail.
 *
 * <ul>
 *   <li><strong>One claim transaction.</strong> It recovers stranded claims, then locks the due rows
 *       with {@code FOR UPDATE SKIP LOCKED} and writes {@link RelayState#CLAIMED} on each of them.
 *       It commits before any send starts. A claim that commits is a claim a later pass can see: a
 *       process that dies mid-pass leaves {@code CLAIMED} rows that
 *       {@link #recoverStrandedClaims(Instant)} returns to {@link RelayState#PENDING}. While the
 *       claim was uncommitted, that recovery could never observe anything, because process death
 *       rolled the claim back along with everything else.</li>
 *   <li><strong>No transaction at all around the sends.</strong> A batch of {@code batch-size} rows
 *       waiting on a broker inside one transaction holds a database connection and every row lock it
 *       took for the sum of those waits. With one hundred rows and a ten-second wait each that is
 *       roughly a thousand seconds of held locks, and every later event of every account waits behind
 *       it.</li>
 *   <li><strong>One short transaction per result.</strong> Marking a row published, or recording its
 *       failure, is a single-row write that opens and commits on its own. A row the broker accepted
 *       is durable before the next row is even attempted.</li>
 * </ul>
 *
 * <p>Every send is awaited against one monotonic deadline shared by the whole pass, from
 * {@code carddemo.outbox.relay.max-duration-ms}. The producer window is configured to resolve one
 * send well inside that deadline, so the relay never abandons a send the broker may still complete,
 * which is what would otherwise publish one event twice: once late by the producer, once again by the
 * next pass.
 *
 * <h2>Why the batch holds one row per account</h2>
 *
 * <p>{@code OutboxEventRepository.claimDueRows} returns the due <em>head</em> row of each aggregate,
 * never two rows of one account. Recording each outcome separately is what makes that necessary: if
 * an older row of an account failed while a newer one succeeded, the retry of the older row would
 * reach the topic behind the newer one, and the account identifier is the message key, so every
 * consumer of that account would see the two events out of order.
 *
 * <p>The same restriction is why one bad row cannot block the table. A refusal pauses that one
 * account for the pass and leaves every other account eligible, so a single unpublishable row delays
 * its own account and nothing else.
 *
 * <h2>What happens to a row nobody can publish</h2>
 *
 * <p>Each failure raises the attempt count and pushes {@code next_attempt_at} further out, and
 * {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts move the row to
 * {@link RelayState#ABANDONED}, which the claim query never returns again. Abandoning it also records
 * a durable obligation to name it on the dead-letter topic, in the same transaction, and that
 * obligation is discharged only against a broker acknowledgement. A refused diagnostic is offered
 * again at the head of every later pass, for as long as it takes, because that diagnostic is the last
 * remaining record of an event this service gave up on.
 *
 * <p>Atomicity and idempotency are both ADDITIVE. The three writes at
 * {@code app/cbl/CBTRN02C.cbl:L440-L442} run under no condition, and no rollback follows them. All
 * eight file definitions in {@code app/csd/CARDDEMO.CSD} carry {@code RECOVERY(NONE)} and
 * {@code JOURNAL(NO)}. The source detects no duplicate at all, and the write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} answers a repeated key with an abend. Delivery here is
 * at-least-once, and each consumer records the event identifier it has processed.
 *
 * <p>The topic follows the event type. Each of the two types this service writes has one bound
 * topic property, so a deployment renames either topic with no change here. A row whose type has no
 * bound topic records an attempt and is abandoned on schedule, and its diagnostic names why.
 *
 * <p>Nothing here records a meter inside a transaction. A counter takes no part in a database
 * transaction, so an increment inside one survives the rollback that discarded the work it counted.
 * Each transaction answers with what it did, and {@link #publishPendingEvents()} records the totals
 * once every one of them has committed.
 *
 * <p>A row whose attempts run out is terminal, and a terminal row is an event no consumer will ever
 * see. One governed {@code DeadLetterEnvelope} names it on {@code carddemo.kafka.topics.dead-letter} at
 * that moment, carrying the four bounded components of {@code 01 ABEND-DATA} at
 * {@code app/cpy/CSMSG02Y.cpy:L21-L29} and no payload, and {@code carddemo.authorization.outbox.abandoned}
 * counts it. Until then the tenth attempt logged the same warning as the nine before it and nothing
 * else, so a dropped event looked exactly like a retried one. The diagnostic is published inside the
 * sweep's transaction and waited for, so a broker that refuses it rolls the abandonment back and the row
 * is offered again rather than left terminal with no record of it anywhere.
 *
 * <p>A different event bus needs one more implementation of {@code messaging/EventPublisherPort}.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class OutboxRelay {

    /** Structured output for this class. No log line carries a payload or a message key. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Reason recorded on a row whose claiming instance died before it finished. */
    private static final String CLAIM_EXPIRED = "ClaimExpired";

    /** Reason recorded on a row whose event type has no authorization destination. */
    private static final String UNKNOWN_EVENT_TYPE = "UnknownEventType";

    /** Partition value of a diagnostic whose subject never arrived on a partition. */
    private static final int NO_SOURCE_PARTITION = 0;

    /** Offset value of a diagnostic whose subject never arrived at an offset. */
    private static final long NO_SOURCE_OFFSET = 0L;

    /**
     * Destination named on the diagnostic of a row whose event type has no configured topic.
     *
     * <p>Such a row cannot be published at all, so it is abandoned on schedule and its diagnostic has
     * to name a source topic anyway. The value satisfies the {@code sourceTopic} pattern of
     * {@code schemas/dead-letter-v1.json}, which admits letters, digits, the period, the underscore
     * and the hyphen. That is a constraint rather than a style choice: a value the document refuses
     * would lose the diagnostic for exactly the row that has no other record of itself.
     */
    private static final String UNRESOLVED_DESTINATION = "no-configured-topic";

    /**
     * The aggregate identifier a diagnostic declares when the abandoned row carries no account.
     *
     * <p>{@code schemas/dead-letter-v1.json} constrains {@code aggregateId} to eleven decimal
     * digits, which is narrower than {@link EventEnvelope#AGGREGATE_KEY_PATTERN}. This service
     * legitimately stores the other permitted form: a decline whose card never resolved has no
     * account to name, so its outbox row is keyed by the sixteen-character transaction identifier
     * instead, and migration {@code V4} permits either width in {@code aggregate_id}.
     *
     * <p>Handing that sixteen-character key to the diagnostic would fail schema validation inside
     * the serializer on every attempt. The obligation {@link OutboxEventEntity#owesDeadLetter()}
     * records would then never clear, and every pass would spend a send budget re-offering a
     * diagnostic that cannot be accepted. Substituting the sentinel is what keeps the obligation
     * dischargeable.
     *
     * <p>Eleven zeros are not an account this platform seeds or issues, so the value states the
     * absence rather than attributing the failure to an account. {@code config/KafkaConsumerConfig}
     * makes the same substitution for the same reason on the consumer side, and the diagnostic still
     * names the row exactly through {@code failedEventId}.
     */
    private static final String UNRESOLVED_ACCOUNT_KEY = "00000000000";

    /**
     * The one aggregate form a dead letter can be keyed on, eleven account digits.
     *
     * <p>{@code schemas/dead-letter-v1.json} declares this pattern and
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN} is the same expression. The other form this service
     * writes, the sixteen-character transaction identifier of a decline whose card resolved to
     * nothing, is admitted on a decision topic and not on the dead-letter topic, which is what
     * {@link #diagnosticKey(String)} exists to answer.
     */
    private static final Pattern ACCOUNT_KEY_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

    /** Failure code carried by the diagnostic of an abandoned row. */
    private static final String DEAD_LETTER_CODE = "0902";

    /** Failure classification carried by the diagnostic of an abandoned row. */
    private static final String DEAD_LETTER_REASON = "outbox publish exhausted";

    /** Operator detail carried by the diagnostic of an abandoned row. */
    private static final String DEAD_LETTER_MESSAGE = "outbox event exhausted automatic attempts";

    /**
     * The {@code event_type} an approval row carries, twenty-one characters.
     *
     * <p>{@code entity/OutboxEventEntity} names this value and the one below as the only two its
     * {@code event_type} column holds for this service.
     */
    private static final String TRANSACTION_AUTHORIZED = "TransactionAuthorized";

    /** The {@code event_type} a decline row carries, nineteen characters. */
    private static final String TRANSACTION_DECLINED = "TransactionDeclined";

    /** Renders the terminal diagnostic, as {@code OutboxWriter} renders an event. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Reads unpublished rows and stores the published flag. */
    private final OutboxEventRepository outboxEvents;

    /** Sends one payload to one topic. */
    private final EventPublisherPort publisher;

    /** Rows swept per tick, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Each registered event type this service publishes, mapped to its configured topic. */
    private final Map<String, String> topics;

    /** Topic a row that exhausted its attempts reports itself on. */
    private final String deadLetterTopic;

    /** The boundary each short transaction of one pass runs inside. */
    private final TransactionTemplate transactionTemplate;

    /** Base of the retry backoff, from {@code carddemo.outbox.relay.fixed-delay-ms}. */
    private final Duration sweepDelay;

    /** How long a claim may stand before another sweep recovers it, and the backoff ceiling. */
    private final Duration claimTimeout;

    /** Total time one pass may spend waiting on acknowledgements, on the monotonic clock. */
    private final long maxDurationNanos;

    /** What this instance writes into {@code claimed_by}. */
    private final String instanceId;

    /** Supplies the moment one pass records, so a test can fix it. */
    private final Clock clock;

    /**
     * Counts one publish fault, the one stage of {@link ObservabilityConfig#FAILURES_COUNTER} this
     * class records. This relay is the only part of the service that publishes.
     */
    private final Counter publishFailures;

    /** Counts the rows the broker acknowledged, after the pass that sent them commits. */
    private final Counter eventsPublished;

    /** Counts one row this service gave up on. */
    private final Counter outboxAbandoned;

    /** Counts one terminal diagnostic the broker acknowledged. */
    private final Counter deadLettersPublished;

    /** Counts one terminal diagnostic attempt the broker refused. */
    private final Counter deadLettersFailed;

    /**
     * Takes the store, the publisher and the configured topic names.
     *
     * <p>Two constructors are declared, so this one is annotated: without the annotation the
     * container finds two candidates, picks neither, and looks for a default constructor that does
     * not exist.
     *
     * @param outboxEvents        store of unpublished events
     * @param publisher           the event bus seam
     * @param meters              registry the counters of this class register with
     * @param transactionTemplate boundary each short transaction of one pass runs inside
     * @param properties          the bound {@code carddemo} settings
     */
    @Autowired
    public OutboxRelay(OutboxEventRepository outboxEvents, EventPublisherPort publisher,
            MeterRegistry meters, TransactionTemplate transactionTemplate,
            AuthorizationProperties properties) {
        this(outboxEvents, publisher, meters, transactionTemplate, properties, Clock.systemUTC());
    }

    /**
     * Takes an explicit clock, so a test can fix the moment one pass records.
     *
     * @param outboxEvents        store of unpublished events
     * @param publisher           the event bus seam
     * @param meters              registry the four counters register with
     * @param transactionTemplate boundary each short transaction of one pass runs inside
     * @param properties          the bound {@code carddemo} settings
     * @param clock               supplies the moment one pass records
     */
    OutboxRelay(OutboxEventRepository outboxEvents, EventPublisherPort publisher,
            MeterRegistry meters, TransactionTemplate transactionTemplate,
            AuthorizationProperties properties, Clock clock) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");

        AuthorizationProperties checked = Objects.requireNonNull(properties, "properties");
        AuthorizationProperties.Outbox.Relay relay = checked.outbox().relay();
        AuthorizationProperties.Kafka.Topics configuredTopics = checked.kafka().topics();
        this.batchSize = relay.batchSize();
        this.sweepDelay = Duration.ofMillis(relay.fixedDelayMs());
        this.claimTimeout = relay.claimTimeout();
        this.maxDurationNanos =
                TimeUnit.MILLISECONDS.toNanos(relay.maxDurationMs());
        this.instanceId = relay.instanceId();
        this.topics = Map.of(
                TRANSACTION_AUTHORIZED, configuredTopics.transactionAuthorized(),
                TRANSACTION_DECLINED, configuredTopics.transactionDeclined());
        this.deadLetterTopic = Objects.requireNonNull(configuredTopics.deadLetter(),
                "deadLetterTopic");

        MeterRegistry registry = Objects.requireNonNull(meters, "meters");
        this.publishFailures = Counter.builder(ObservabilityConfig.FAILURES_COUNTER)
                .tag(ObservabilityConfig.STAGE_TAG, ObservabilityConfig.PUBLISH_STAGE)
                .register(registry);
        this.eventsPublished =
                Counter.builder(ObservabilityConfig.EVENTS_PUBLISHED_COUNTER).register(registry);
        this.outboxAbandoned =
                Counter.builder(ObservabilityConfig.OUTBOX_ABANDONED_COUNTER).register(registry);
        this.deadLettersPublished = Counter.builder(ObservabilityConfig.DEAD_LETTERS_COUNTER)
                .tag(ObservabilityConfig.OUTCOME_OF_DIAGNOSTIC_TAG,
                        ObservabilityConfig.DIAGNOSTIC_PUBLISHED)
                .register(registry);
        this.deadLettersFailed = Counter.builder(ObservabilityConfig.DEAD_LETTERS_COUNTER)
                .tag(ObservabilityConfig.OUTCOME_OF_DIAGNOSTIC_TAG,
                        ObservabilityConfig.DIAGNOSTIC_FAILED)
                .register(registry);
    }

    /**
     * Runs one pass and records what it did once every transaction of that pass has committed.
     *
     * <p>The scheduled method holds no transaction of its own. Each step below opens the shortest
     * transaction that step needs, so no database connection is held across a broker wait.
     *
     * <p>A pass that throws is logged and counted as one publish fault, and the next pass runs
     * against whatever it left behind. A committed claim it did not reach is recovered by
     * {@link #recoverStrandedClaims(Instant)} once the claim timeout has passed.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    public void publishPendingEvents() {
        PassResult result;
        try {
            result = runOnePass();
        } catch (RuntimeException failure) {
            publishFailures.increment();
            log.warn("The authorization outbox pass failed after {} and will run again",
                    rootCause(failure).getClass().getSimpleName());
            return;
        }
        result.record(this);
    }

    /**
     * Offers the diagnostics still owed, claims the due rows, then publishes them in order.
     *
     * @return what this pass published and what it could not
     */
    private PassResult runOnePass() {
        Instant now = clock.instant();
        long deadline = System.nanoTime() + maxDurationNanos;

        Terminal terminal = publishOwedDeadLetters(deadline);
        ClaimedBatch batch = claimBatch(now);

        int published = 0;
        int failed = 0;
        for (OutboxEventEntity row : batch.rows()) {
            if (System.nanoTime() - deadline >= 0L) {
                log.debug("The authorization outbox pass reached its deadline with {} claimed rows "
                        + "unattempted; the next pass recovers them",
                        batch.rows().size() - published - failed);
                break;
            }
            Attempt attempt = attemptOneRow(row, deadline);
            published = published + attempt.published();
            failed = failed + attempt.failed();
            terminal = terminal.plus(attempt.terminal());
        }
        return new PassResult(published, failed + batch.recovered(), terminal);
    }

    /**
     * Recovers stranded claims and claims the due rows, in one transaction that commits before any
     * send starts.
     *
     * <p>Committing the claim is what makes the recovery observable. A claim written and left
     * uncommitted while the pass published would be rolled back by the process death it exists to
     * survive, and the row would meet every later pass in {@link RelayState#PENDING} with an
     * unchanged attempt count.
     *
     * @param now the moment this pass records
     * @return the rows this pass holds and the number of claims it recovered
     */
    private ClaimedBatch claimBatch(Instant now) {
        ClaimedBatch claimed = transactionTemplate.execute(status -> {
            int recovered = recoverStrandedClaims(now);
            List<OutboxEventEntity> due = outboxEvents.claimDueRows(now, Limit.of(batchSize));
            for (OutboxEventEntity row : due) {
                row.claim(instanceId, now);
                outboxEvents.save(row);
            }
            return new ClaimedBatch(due, recovered);
        });
        return claimed == null ? ClaimedBatch.EMPTY : claimed;
    }

    /**
     * Publishes one claimed row and records the result in a short transaction of its own.
     *
     * <p>The send happens outside every transaction. The mark is written after the broker
     * acknowledges, so no row is marked for a message the broker did not accept, and a send that
     * returns while the mark fails leaves the row claimed until the claim timeout returns it — the
     * next pass then sends it again, and each consumer's processed-event table absorbs the duplicate.
     *
     * @param row      the claimed row
     * @param deadline monotonic deadline shared by the whole pass
     * @return what this attempt did
     */
    private Attempt attemptOneRow(OutboxEventEntity row, long deadline) {
        String destination = topics.get(row.getEventType());
        if (destination == null) {
            log.error("An authorization outbox row carries the unconfigured event type {}. "
                    + "Attempt {} of {}.", row.getEventType(), row.getAttemptCount() + 1,
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
            return new Attempt(0, 1, recordFailure(row.getEventId(), UNKNOWN_EVENT_TYPE,
                    UNRESOLVED_DESTINATION, deadline));
        }
        try {
            await(publisher.publish(destination, row.getAggregateId(), row.getPayload()), deadline);
        } catch (RuntimeException failure) {
            String failureClass = rootCause(failure).getClass().getSimpleName();
            log.warn("An authorization outbox row of type {} stays unpublished after {}. "
                    + "Attempt {} of {}.", row.getEventType(), failureClass,
                    row.getAttemptCount() + 1, OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
            return new Attempt(0, 1,
                    recordFailure(row.getEventId(), failureClass, destination, deadline));
        }
        markPublished(row.getEventId());
        return new Attempt(1, 0, Terminal.NONE);
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
        transactionTemplate.executeWithoutResult(status -> {
            Optional<OutboxEventEntity> found = outboxEvents.findById(eventId);
            if (found.isEmpty()) {
                log.warn("An authorization outbox row disappeared between its claim and its mark");
                return;
            }
            OutboxEventEntity row = found.get();
            row.markPublished(clock.instant());
            outboxEvents.save(row);
        });
    }

    /**
     * Records one failed attempt in a transaction of its own, and offers the diagnostic when that
     * attempt abandoned the row.
     *
     * <p>The stored reason and the log line carry the failure's class name and nothing else. A broker
     * or database message can quote the row it was raised for, and this row's payload holds a masked
     * card number, an amount and an account identifier.
     *
     * @param eventId     the row the attempt failed on
     * @param reason      the class name of the failure, or a fixed reason for a row nobody can route
     * @param destination the topic the event was meant for
     * @param deadline    monotonic deadline shared by the whole pass
     * @return what this failure did to the terminal counts
     */
    private Terminal recordFailure(UUID eventId, String reason, String destination, long deadline) {
        Abandonment abandonment = transactionTemplate.execute(status -> {
            Optional<OutboxEventEntity> found = outboxEvents.findById(eventId);
            if (found.isEmpty()) {
                log.warn("An authorization outbox row disappeared between its claim and its "
                        + "failure record");
                return Abandonment.NONE;
            }
            OutboxEventEntity row = found.get();
            Instant at = clock.instant();
            row.recordFailure(reason, at, at.plus(backoffAfter(row.getAttemptCount())));
            outboxEvents.save(row);
            return row.getRelayState() == RelayState.ABANDONED
                    ? new Abandonment(true, row.getEventId(), row.getEventType(),
                            row.getAggregateId(), row.getAttemptCount(), row.getLastError())
                    : Abandonment.NONE;
        });

        Abandonment given = abandonment == null ? Abandonment.NONE : abandonment;
        if (!given.abandoned()) {
            return Terminal.NONE;
        }
        log.error("Authorization outbox event {} was abandoned after {} attempts on topic {}. It "
                        + "owes one dead letter on {} until the broker acknowledges one.",
                given.eventId(), given.attemptCount(), destination, deadLetterTopic);
        return Terminal.ABANDONED.plus(publishDeadLetter(given, destination, deadline));
    }

    /**
     * Offers the diagnostics abandoned rows still owe, oldest attempt first.
     *
     * <p>This runs at the head of a pass rather than at its end. An owed diagnostic is the only
     * remaining record of an event this service gave up on, the set is empty while the relay is
     * healthy, and a business row that yields its place is claimed by the next pass with nothing lost.
     *
     * <p>A refusal here is not a failure of the pass. The obligation is durable, so the row survives
     * to be offered again, and the attempt is counted so an operator can see a diagnostic that is not
     * landing.
     *
     * @param deadline monotonic deadline shared by the whole pass
     * @return what this step did to the terminal counts
     */
    private Terminal publishOwedDeadLetters(long deadline) {
        List<OutboxEventEntity> owed = outboxEvents
                .findByDeadLetterStateOrderByLastAttemptAtAsc(DeadLetterState.REQUIRED,
                        Limit.of(batchSize));

        Terminal counts = Terminal.NONE;
        for (OutboxEventEntity row : owed) {
            if (System.nanoTime() - deadline >= 0L) {
                return counts;
            }
            log.warn("Authorization outbox event {} has owed a dead letter on {} since {}, so this "
                    + "pass offers it again.", row.getEventId(), deadLetterTopic,
                    row.getLastAttemptAt());
            Abandonment owedRow = new Abandonment(true, row.getEventId(), row.getEventType(),
                    row.getAggregateId(), row.getAttemptCount(), row.getLastError());
            counts = counts.plus(publishDeadLetter(owedRow,
                    topics.getOrDefault(row.getEventType(), UNRESOLVED_DESTINATION), deadline));
        }
        return counts;
    }

    /**
     * Publishes one terminal diagnostic and clears the obligation only once the broker has
     * acknowledged it.
     *
     * <p>The wait is what makes the obligation meaningful. A send that is dispatched and not awaited
     * reports success the instant it is queued, so an unreachable broker and a healthy one look
     * identical and the row is left terminal either way.
     *
     * <p>The envelope is the governed form of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}. It names the row through {@code failedEventId} and
     * {@code failedEventType} and carries no field of the payload, so an operator can reach the
     * abandoned event without an account identifier or a masked card number leaving this service on
     * the diagnostic.
     *
     * <p>A refusal is logged and counted and does not propagate. The caller is either the failed-row
     * path, which has already recorded the failure that abandoned the row, or the owed-diagnostic
     * step, where one unreachable broker must not stop the rows behind it.
     *
     * <p>The key the diagnostic travels under is {@link #diagnosticKey(String)} rather than the key
     * of the abandoned row, because a row keyed by a transaction identifier has no account the
     * dead-letter document would accept.
     *
     * @param row         what this relay gave up on
     * @param sourceTopic the topic the abandoned event was meant for
     * @param deadline    monotonic deadline shared by the whole pass
     * @return {@link Terminal#DEAD_LETTER_PUBLISHED} on acknowledgement, otherwise
     *         {@link Terminal#DEAD_LETTER_FAILED}
     */
    private Terminal publishDeadLetter(Abandonment row, String sourceTopic, long deadline) {
        String key = diagnosticKey(row.aggregateId());
        DeadLetterEnvelope envelope = DeadLetterMetadata
                .fromFailure(DEAD_LETTER_CODE, new AbandonedRowException(row.lastError()),
                        DEAD_LETTER_REASON, DEAD_LETTER_MESSAGE)
                .toEnvelope(key, sourceTopic, NO_SOURCE_PARTITION, NO_SOURCE_OFFSET,
                        row.eventId().toString(), row.eventType(), row.attemptCount());

        try {
            await(publisher.publish(deadLetterTopic, key,
                    MAPPER.writeValueAsString(envelope)), deadline);
        } catch (RuntimeException refused) {
            log.error("The dead letter naming authorization outbox event {} did not reach {} after "
                            + "a {}. The row still owes one, and a later pass offers it again.",
                    row.eventId(), deadLetterTopic,
                    rootCause(refused).getClass().getSimpleName());
            return Terminal.DEAD_LETTER_FAILED;
        }

        transactionTemplate.executeWithoutResult(status -> outboxEvents.findById(row.eventId())
                .filter(OutboxEventEntity::owesDeadLetter)
                .ifPresent(stored -> {
                    stored.markDeadLetterPublished(clock.instant());
                    outboxEvents.save(stored);
                }));
        return Terminal.DEAD_LETTER_PUBLISHED;
    }

    /**
     * Narrows an outbox aggregate identifier to the one form a dead letter may declare.
     *
     * <p>An account-shaped key is passed through, so a diagnostic for an account-keyed row still
     * lands on that account's partition and an operator can find it by account. Anything else is
     * the transaction-keyed form a decline with an unresolved card carries, and it becomes
     * {@value #UNRESOLVED_ACCOUNT_KEY}.
     *
     * <p>The substitution loses nothing an operator needs. {@code failedEventId} names the abandoned
     * row exactly, and {@code outbox_event.aggregate_id} still holds the transaction identifier for
     * anyone reading the row itself. What it buys is a diagnostic that can be published at all: the
     * alternative is a schema refusal on every pass and an obligation that never clears.
     *
     * @param aggregateId the {@code aggregate_id} of the abandoned row, either form
     * @return {@code aggregateId} when it is eleven decimal digits, otherwise the sentinel
     */
    private static String diagnosticKey(String aggregateId) {
        return aggregateId != null && ACCOUNT_KEY_MATCHER.matcher(aggregateId).matches()
                ? aggregateId
                : UNRESOLVED_ACCOUNT_KEY;
    }

    /**
     * Returns claims left by a stopped instance to {@link RelayState#PENDING}.
     *
     * <p>Without this, one crash costs one event permanently: the row stays {@link RelayState#CLAIMED},
     * the claim query filters on {@link RelayState#PENDING}, and nothing looks at it again. The
     * recovery counts as an attempt, so a row that strands repeatedly is abandoned rather than
     * recovered for ever.
     *
     * <p>An abandonment reached this way carries the same terminal diagnostic as any other.
     *
     * @param now the moment this pass records
     * @return the number of recovered rows
     */
    private int recoverStrandedClaims(Instant now) {
        List<OutboxEventEntity> stranded =
                outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                        RelayState.CLAIMED, now.minus(claimTimeout), Limit.of(batchSize));
        for (OutboxEventEntity row : stranded) {
            row.recordFailure(CLAIM_EXPIRED, now, now);
            outboxEvents.save(row);
            log.warn("An authorization outbox claim expired for event type {}. Attempt {} of {}.",
                    row.getEventType(), row.getAttemptCount(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        }
        return stranded.size();
    }

    /**
     * Returns the configured exponential wait before another attempt.
     *
     * @param attemptsSoFar attempts recorded before the failure being scheduled
     * @return a wait no longer than the claim timeout
     */
    private Duration backoffAfter(int attemptsSoFar) {
        Duration wait = sweepDelay;
        for (int step = 0; step < attemptsSoFar && wait.compareTo(claimTimeout) < 0; step++) {
            wait = wait.multipliedBy(2L);
        }
        return wait.compareTo(claimTimeout) > 0 ? claimTimeout : wait;
    }

    /**
     * Waits for one broker acknowledgement without crossing this pass's deadline.
     *
     * <p>The stage is cancelled when the deadline expires, so this pass stops observing a send it has
     * given up on. The producer window is configured to close a send inside the deadline anyway, and
     * the cancellation is what keeps that guarantee true if either value is later raised alone.
     *
     * @param publication the broker acknowledgement stage
     * @param deadline    monotonic deadline shared by the whole pass
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
            throw new IllegalStateException("the authorization outbox relay was interrupted",
                    interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("an authorization outbox publication failed", cause);
        } catch (TimeoutException timedOut) {
            publication.toCompletableFuture().cancel(true);
            throw new RelayDeadlineExceededException();
        }
    }

    /**
     * Returns the deepest cause of one failure, stopping on a cause that names itself.
     *
     * @param failure the failure to walk
     * @return the deepest cause, or {@code failure} when it wraps none
     */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Fixed exception used when the configured pass deadline expires. */
    private static final class RelayDeadlineExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RelayDeadlineExceededException() {
            super("the authorization outbox relay pass deadline expired");
        }
    }

    /**
     * Names the abandonment an owed diagnostic reports, when the failure that caused it is gone.
     *
     * <p>A diagnostic offered on a later pass has no live exception behind it: the failure that
     * abandoned the row happened in an earlier pass, and only the redacted reason {@code last_error}
     * kept survives. This exception carries that reason so {@link DeadLetterMetadata#fromFailure} names
     * the same culprit it would have named at the time.
     */
    private static final class AbandonedRowException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Takes the redacted reason stored on the row.
         *
         * @param recordedReason {@code last_error} as the row holds it, which names a failure class and
         *                       no event value, or null when the row holds none
         */
        AbandonedRowException(String recordedReason) {
            super(recordedReason == null ? DEAD_LETTER_REASON : recordedReason);
        }
    }

    /**
     * The facts a diagnostic needs about one abandoned row, read inside the transaction that
     * abandoned it.
     *
     * <p>The row itself is not carried out of that transaction. A detached entity read outside its
     * transaction is a source of stale values, and the diagnostic needs six fields that never change
     * once the row is abandoned.
     *
     * @param abandoned    whether the attempt abandoned the row
     * @param eventId      the row's identifier, which the diagnostic names
     * @param eventType    the row's event type, which the diagnostic names
     * @param aggregateId  the message key the diagnostic travels under
     * @param attemptCount attempts the row had taken when it was abandoned
     * @param lastError    the redacted reason of its last failure
     */
    private record Abandonment(boolean abandoned, UUID eventId, String eventType,
            String aggregateId, int attemptCount, String lastError) {

        /** No row was abandoned by this attempt. */
        private static final Abandonment NONE =
                new Abandonment(false, null, null, null, 0, null);
    }

    /**
     * What the claim transaction of one pass produced.
     *
     * <p>Two numbers come out of that one transaction and only one of them is the batch: a recovered
     * claim is a failed attempt on a row this pass will not publish, and the rows are what this pass
     * holds.
     *
     * @param rows      the rows this pass claimed, longest-waiting first
     * @param recovered claims this pass returned to {@link RelayState#PENDING}
     */
    private record ClaimedBatch(List<OutboxEventEntity> rows, int recovered) {

        /** No row was claimed and no claim was recovered. */
        private static final ClaimedBatch EMPTY = new ClaimedBatch(List.of(), 0);
    }

    /**
     * What one attempt on one row did.
     *
     * @param published 1 when the broker accepted the row, otherwise 0
     * @param failed    1 when the attempt failed, otherwise 0
     * @param terminal  what the attempt did to the terminal counts
     */
    private record Attempt(int published, int failed, Terminal terminal) {
    }

    /**
     * What one pass did to the terminal path, kept separate from the per-attempt counts.
     *
     * <p>Three outcomes are distinguishable rather than summed into one failure count: a row given up
     * on, a diagnostic the broker acknowledged, and a diagnostic the broker refused. An operator
     * reading only a failure count cannot tell a relay retrying normally from one that has silently
     * stopped recording what it lost.
     *
     * @param abandoned            rows this pass gave up on
     * @param deadLettersPublished diagnostics the broker acknowledged in this pass
     * @param deadLettersFailed    diagnostic attempts the broker refused in this pass
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
     * What one pass committed, carried out of every transaction so it can be counted after them.
     *
     * @param published rows the broker accepted and this pass marked
     * @param failed    attempts that failed in this pass: a refused publish, an unroutable event
     *                  type, an expired pass deadline, or a claim recovered from a dead instance
     * @param terminal  what this pass did to the terminal path
     */
    private record PassResult(int published, int failed, Terminal terminal) {

        /**
         * Counts this result against the meters of the relay that produced it.
         *
         * @param relay the relay holding the four counters
         */
        void record(OutboxRelay relay) {
            if (published > 0) {
                relay.eventsPublished.increment(published);
                log.debug("Published {} authorization outbox rows", published);
            }
            for (int failure = 0; failure < failed; failure++) {
                relay.publishFailures.increment();
            }
            for (int given = 0; given < terminal.abandoned(); given++) {
                relay.outboxAbandoned.increment();
            }
            for (int named = 0; named < terminal.deadLettersPublished(); named++) {
                relay.deadLettersPublished.increment();
            }
            for (int refused = 0; refused < terminal.deadLettersFailed(); refused++) {
                relay.deadLettersFailed.increment();
            }
        }
    }

}
