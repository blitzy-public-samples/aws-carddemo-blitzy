package com.carddemo.card.outbox;

import com.carddemo.card.config.CardProperties;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.messaging.DeadLetterMetadata;
import com.carddemo.card.messaging.EventPublisherPort;
import com.carddemo.card.repository.OutboxEventRepository;
import com.carddemo.events.DeadLetterEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
        this.sweepDelay = Duration.ofMillis(relay.fixedDelayMs());
        this.claimTimeout = relay.claimTimeout();
        this.instanceId = relay.instanceId();
    }

    /**
     * Runs one sweep and records what it did once that sweep has committed.
     *
     * <p>The sweep runs inside one transaction because the claim depends on it:
     * {@link OutboxEventRepository#claimDueRows} holds each row it returns with
     * {@code FOR NO KEY UPDATE ... SKIP LOCKED}, and that lock lives exactly as long as the
     * transaction that took it. Two instances of this service — one rolling deployment, one manual
     * scale-out — therefore claim disjoint batches instead of both publishing every row.
     *
     * <p>The boundary is opened here rather than declared with an annotation so that the counters
     * below sit outside it. A counter takes no part in a database transaction, so an increment made
     * inside one survives a rollback and reports rows as published that were never marked.
     *
     * <p>No failure leaves this method. An unreachable database or broker yields one log line and one
     * counted failure per sweep, and the schedule carries on.
     *
     * <p>A refused terminal diagnostic is caught before the general case and counted in its own
     * series, because the two say different things. A counted infrastructure failure is one attempt
     * on a row still in flight. A refused diagnostic means the row whose attempts are spent has no
     * record of it on the topic yet, and since the boundary rolled that abandonment back the row is
     * claimable again and the diagnostic is attempted again with it.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    public void publishPendingEvents() {
        SweepResult result;
        try {
            result = transactionTemplate.execute(status -> sweepOnce());
        } catch (DeadLetterRefusedException refused) {
            deadLettersFailed.increment();
            log.error("The dead letter naming card event {} did not reach {} after a {}. The sweep "
                            + "rolled back, so that row is not abandoned and a later sweep offers "
                            + "it and its diagnostic again.",
                    refused.eventId(), deadLetterTopic, refused.failureClass());
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
     * Recovers stranded claims, claims the due rows and works through them, longest-waiting first.
     *
     * <p>Longest-waiting first keeps one account's events in the order their updates committed. The
     * writer stamps each row as it stores it. Every message carries the account identifier as its
     * key, so one account's events land on one partition and stay ordered.
     *
     * <p>A send that fails on infrastructure records an attempt against its row and ends the sweep, so
     * a later event of one account cannot overtake an earlier one still waiting. The row becomes due
     * again after a backoff, so one undeliverable row does not have every sweep retry it while the rows
     * behind it wait.
     *
     * <p>A row the publish port refuses takes one recorded failure and the sweep continues to the next
     * row, because nothing about that row is going to change. Such a row reaches {@code ABANDONED}
     * after {@value OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts, and the claim query stops
     * returning it.
     *
     * <p>The sweep is idle when no card changed, since a card list and a card read store no row.
     *
     * @return what this sweep published, how many failures it recorded and how many rows it
     *         abandoned
     * @throws RuntimeException           when the store cannot be read or a mark cannot be written
     * @throws DeadLetterRefusedException when the broker refused the diagnostic of an abandoned row
     */
    private SweepResult sweepOnce() {
        Instant now = Instant.now();
        Recovery recovery = recoverStrandedClaims(now);
        int failures = recovery.attempts();
        int abandoned = recovery.abandoned();
        List<Timer.Sample> publishAttempts = new ArrayList<>();

        List<OutboxEventEntity> due = outboxEvents.claimDueRows(now, Limit.of(batchSize));
        int published = 0;

        for (OutboxEventEntity row : due) {
            row.claim(instanceId, now);

            if (!CardUpdated.EVENT_TYPE.equals(row.getEventType())) {
                if (recordUnpublishable(row, "no configured topic for the stored event type",
                        "stored event type reaches no topic of this service", now)) {
                    abandoned = abandoned + 1;
                }
                failures = failures + 1;
                continue;
            }

            Timer.Sample publishAttempt = Timer.start();
            try {
                publishAndMark(row);
                publishAttempts.add(publishAttempt);
                published = published + 1;
            } catch (IllegalArgumentException refused) {
                publishAttempts.add(publishAttempt);
                if (recordUnpublishable(row,
                        "publish port refused: " + refused.getClass().getSimpleName(),
                        "payload refused for topic " + cardUpdatedTopic, now)) {
                    abandoned = abandoned + 1;
                }
                failures = failures + 1;
            } catch (RuntimeException failure) {
                publishAttempts.add(publishAttempt);
                if (recordRefusedRow(row, failure, now)) {
                    abandoned = abandoned + 1;
                }
                return new SweepResult(published, failures + 1, abandoned, publishAttempts);
            }
        }
        return new SweepResult(published, failures, abandoned, publishAttempts);
    }

    /**
     * Returns rows a dead instance left claimed to {@link OutboxEventEntity.RelayState#PENDING}.
     *
     * <p>Without this one crash costs one event permanently: the row stays
     * {@link OutboxEventEntity.RelayState#CLAIMED}, the claim query filters on
     * {@link OutboxEventEntity.RelayState#PENDING}, and nothing looks at it again. The recovery counts
     * as an attempt, so a row that strands repeatedly is eventually abandoned.
     *
     * @param now the moment this sweep started
     * @return how many rows were recovered and how many of them were abandoned
     * @throws DeadLetterRefusedException when the broker refused the diagnostic of an abandoned row
     */
    private Recovery recoverStrandedClaims(Instant now) {
        List<OutboxEventEntity> stranded =
                outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                        OutboxEventEntity.RelayState.CLAIMED, now.minus(claimTimeout),
                        Limit.of(batchSize));

        int abandoned = 0;
        for (OutboxEventEntity row : stranded) {
            row.recordFailure("claim expired", now, now);
            outboxEvents.save(row);
            log.warn("Card event {} was claimed by an instance that did not finish, so it is due "
                            + "again. Attempt {} of {}.", row.getEventId(), row.getAttemptCount(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
            if (deadLetterIfAbandoned(row, DeadLetterMetadata.of(ABEND_CODE, CULPRIT,
                    "claim expired on every attempt", "outbox row stranded by a relay instance "
                            + "that did not finish"))) {
                abandoned = abandoned + 1;
            }
        }
        return new Recovery(stranded.size(), abandoned);
    }

    /**
     * What one recovery pass did, so its two outcomes reach the meters separately.
     *
     * @param attempts  attempts this pass recorded against rows a dead instance left claimed
     * @param abandoned rows of that set this relay gave up on, each named by an acknowledged
     *                  diagnostic
     */
    private record Recovery(int attempts, int abandoned) {
    }

    /**
     * Records one attempt against a row the broker did not accept, and schedules the next attempt.
     *
     * <p>Only the failure's class name is stored and logged. A broker or database failure message can
     * quote the row it was raised for, and this row's payload holds an account identifier and a masked
     * card number.
     *
     * <p>A row whose attempts are spent by this failure is abandoned, and one diagnostic naming it is
     * published before this method returns.
     *
     * @param row     the row the broker refused
     * @param failure the failure the publish raised
     * @param now     the moment this sweep started
     * @return {@code true} when this failure abandoned the row and its diagnostic was acknowledged
     * @throws DeadLetterRefusedException when the broker refused that diagnostic
     */
    private boolean recordRefusedRow(OutboxEventEntity row, RuntimeException failure, Instant now) {
        String failureClass = failure.getClass().getSimpleName();
        row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEvents.save(row);

        log.warn("Publishing card event {} failed with {}, so the row stays unpublished, becomes due "
                        + "again after a backoff, and the sweep stops here. Attempt {} of {}.",
                row.getEventId(), failureClass, row.getAttemptCount(),
                OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);

        return deadLetterIfAbandoned(row, DeadLetterMetadata.fromFailure(ABEND_CODE, failure,
                REFUSED_REASON, REFUSED_MESSAGE));
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
    private record SweepResult(int published, int failed, int abandoned,
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
        }
    }

    /**
     * Publishes one row, then marks it sent.
     *
     * <p>The key is the eleven-digit account identifier the row stores, passed as text so a leading
     * zero survives. Its width is {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
     * and {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}. The payload travels
     * exactly as the writer stored it, already serialized as JavaScript Object Notation (JSON).
     *
     * <p>The mark is written after the send returns, so no row is marked for a message the broker
     * did not acknowledge. Every mark commits with the rest of the sweep. A rollback may therefore
     * repeat a send, which consumer idempotency absorbs.
     *
     * @param row the unpublished row
     * @throws IllegalArgumentException when the publish port refuses the key or the payload
     * @throws RuntimeException         when the send fails
     */
    void publishAndMark(OutboxEventEntity row) {
        publisher.publish(cardUpdatedTopic, row.getAggregateId(), row.getPayload());
        row.markPublished(Instant.now());
        outboxEvents.save(row);
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
     * <p>The same four components serve twice: joined into the row's stored diagnostic, and carried
     * on the envelope this relay publishes once the row's attempts are spent. Building them once is
     * what keeps the two accounts of one failure from drifting apart.
     *
     * @param row     the row no send will carry
     * @param reason  the failure classification, at most 50 characters kept
     * @param message the failure detail, at most 72 characters kept
     * @param now     the moment this sweep started
     * @return {@code true} when this failure abandoned the row and its diagnostic was acknowledged
     * @throws DeadLetterRefusedException when the broker refused that diagnostic
     */
    private boolean recordUnpublishable(OutboxEventEntity row, String reason, String message,
            Instant now) {

        DeadLetterMetadata diagnostics = DeadLetterMetadata.of(ABEND_CODE, CULPRIT, reason, message);
        String diagnostic = describe(diagnostics);

        row.recordFailure(diagnostic, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEvents.save(row);

        log.error("Card event {} carries no publishable form: {}", row.getEventId(), diagnostic);

        return deadLetterIfAbandoned(row, diagnostics);
    }

    /**
     * Publishes one governed diagnostic for a row this relay has just given up on, and does nothing
     * for a row that is still in flight.
     *
     * <p>{@link OutboxEventEntity#recordFailure} moves a row to
     * {@link OutboxEventEntity.RelayState#ABANDONED} once its attempts are spent, and the claim query
     * stops returning it from that moment. This is therefore the one place an abandoned card event is
     * named anywhere outside its own database, which is why the send is awaited rather than dispatched:
     * {@link EventPublisherPort#publish} returns after the broker has acknowledged the record, bounded
     * by {@code carddemo.outbox.relay.publish-timeout}.
     *
     * <p>A refusal is raised rather than swallowed. The whole sweep runs inside one boundary, so the
     * raised failure rolls the abandonment back with it and the row returns to the claim query with its
     * attempt count as it was. A later sweep therefore offers the row and its diagnostic together, and
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
    private boolean deadLetterIfAbandoned(OutboxEventEntity row, DeadLetterMetadata diagnostics) {
        if (row.getRelayState() != OutboxEventEntity.RelayState.ABANDONED) {
            return false;
        }

        DeadLetterEnvelope envelope = diagnostics.toEnvelope(row.getAggregateId(),
                sourceTopicOf(row.getEventType()), NO_SOURCE_PARTITION, NO_SOURCE_OFFSET,
                row.getEventId().toString(), governedTypeOrAbsent(row.getEventType()),
                row.getAttemptCount());

        try {
            publisher.publish(deadLetterTopic, row.getAggregateId(),
                    objectMapper.writeValueAsString(envelope));
        } catch (RuntimeException refused) {
            throw new DeadLetterRefusedException(row.getEventId(), refused);
        }

        log.error("Card event {} was abandoned after {} attempts, and one dead letter names it on "
                        + "{}. No consumer will see that update.",
                row.getEventId(), row.getAttemptCount(), deadLetterTopic);
        return true;
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
