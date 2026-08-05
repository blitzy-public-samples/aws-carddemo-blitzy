package com.carddemo.card.outbox;

import com.carddemo.card.config.CardProperties;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.messaging.DeadLetterMetadata;
import com.carddemo.card.messaging.EventPublisherPort;
import com.carddemo.card.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>Scheduling is enabled on {@code com.carddemo.card.CardApplication}, which is what starts the
 * sweep. The three values this class reads come from the {@code carddemo} block of
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

    /** Reads unpublished rows and stores the mark this class writes. */
    private final OutboxEventRepository outboxEvents;

    /** Sends one payload to one topic under one key. */
    private final EventPublisherPort publisher;

    /** Counts events that reached the broker, from {@code carddemo.card.events.published}. */
    private final Counter eventsPublished;

    /** Counts attempts that failed, from {@code carddemo.card.failures}. */
    private final Counter infrastructureFailures;

    /** Times one publish attempt, from {@code carddemo.card.publish.latency}. */
    private final Timer publishLatency;

    /** Rows one sweep claims, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Topic a card update travels on, from {@code carddemo.kafka.topics.card-updated}. */
    private final String cardUpdatedTopic;

    /** The boundary one sweep runs inside, so the claim holds and no counter joins it. */
    private final TransactionTemplate transactionTemplate;

    /** Base of the retry backoff, from {@code carddemo.outbox.relay.fixed-delay-ms}. */
    private final Duration sweepDelay;

    /** How long a claim may stand before another sweep recovers it, and the backoff ceiling. */
    private final Duration claimTimeout;

    /** What this instance writes into {@code claimed_by}, so a claim can be traced to a process. */
    private final String instanceId;

    /**
     * Takes the store, the publish port, the three meters and the two configured values.
     *
     * <p>Neither property below carries a default here. An absent {@code carddemo} block stops
     * start-up with the missing key named.
     *
     * @param outboxEvents           store of unpublished rows
     * @param publisher              the event bus seam, which one new implementation replaces
     * @param eventsPublished        counter incremented after a publish succeeds
     * @param infrastructureFailures counter incremented after an attempt fails
     * @param timers                 holder of the publish attempt timer
     * @param cardUpdatedTopic       topic a card update travels on
     * @param transactionTemplate    the boundary one sweep runs inside
     * @param properties             the bound {@code carddemo} block, read for the relay settings
     * @throws NullPointerException if any collaborator is absent
     */
    public OutboxRelay(OutboxEventRepository outboxEvents, EventPublisherPort publisher,
            @Qualifier("cardEventsPublishedCounter") Counter eventsPublished,
            @Qualifier("cardInfrastructureFailureCounter") Counter infrastructureFailures,
            CardLatencyTimers timers,
            @Value("${carddemo.kafka.topics.card-updated}") String cardUpdatedTopic,
            TransactionTemplate transactionTemplate,
            CardProperties properties) {

        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.eventsPublished = Objects.requireNonNull(eventsPublished, "eventsPublished");
        this.infrastructureFailures =
                Objects.requireNonNull(infrastructureFailures, "infrastructureFailures");
        this.publishLatency = Objects.requireNonNull(timers, "timers").eventPublish();
        this.cardUpdatedTopic = Objects.requireNonNull(cardUpdatedTopic, "cardUpdatedTopic");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");

        CardProperties.Outbox.Relay relay =
                Objects.requireNonNull(properties, "properties").outbox().relay();
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
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    public void publishPendingEvents() {
        SweepResult result;
        try {
            result = transactionTemplate.execute(status -> sweepOnce());
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
     * @return what this sweep published and how many failures it recorded
     * @throws RuntimeException when the store cannot be read or a mark cannot be written
     */
    private SweepResult sweepOnce() {
        Instant now = Instant.now();
        int failures = recoverStrandedClaims(now);
        List<Timer.Sample> publishAttempts = new ArrayList<>();

        List<OutboxEventEntity> due = outboxEvents.claimDueRows(now, Limit.of(batchSize));
        int published = 0;

        for (OutboxEventEntity row : due) {
            row.claim(instanceId, now);

            if (!CardUpdated.EVENT_TYPE.equals(row.getEventType())) {
                recordUnpublishable(row, "no configured topic for the stored event type",
                        "stored event type reaches no topic of this service", now);
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
                recordUnpublishable(row,
                        "publish port refused: " + refused.getClass().getSimpleName(),
                        "payload refused for topic " + cardUpdatedTopic, now);
                failures = failures + 1;
            } catch (RuntimeException failure) {
                publishAttempts.add(publishAttempt);
                recordRefusedRow(row, failure, now);
                return new SweepResult(published, failures + 1, publishAttempts);
            }
        }
        return new SweepResult(published, failures, publishAttempts);
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
     * @return how many rows were recovered
     */
    private int recoverStrandedClaims(Instant now) {
        List<OutboxEventEntity> stranded =
                outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                        OutboxEventEntity.RelayState.CLAIMED, now.minus(claimTimeout),
                        Limit.of(batchSize));

        for (OutboxEventEntity row : stranded) {
            row.recordFailure("claim expired", now, now);
            outboxEvents.save(row);
            log.warn("Card event {} was claimed by an instance that did not finish, so it is due "
                            + "again. Attempt {} of {}.", row.getEventId(), row.getAttemptCount(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        }
        return stranded.size();
    }

    /**
     * Records one attempt against a row the broker did not accept, and schedules the next attempt.
     *
     * <p>Only the failure's class name is stored and logged. A broker or database failure message can
     * quote the row it was raised for, and this row's payload holds an account identifier and a masked
     * card number.
     *
     * @param row     the row the broker refused
     * @param failure the failure the publish raised
     * @param now     the moment this sweep started
     */
    private void recordRefusedRow(OutboxEventEntity row, RuntimeException failure, Instant now) {
        String failureClass = failure.getClass().getSimpleName();
        row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEvents.save(row);

        log.warn("Publishing card event {} failed with {}, so the row stays unpublished, becomes due "
                        + "again after a backoff, and the sweep stops here. Attempt {} of {}.",
                row.getEventId(), failureClass, row.getAttemptCount(),
                OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
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
     * @param published rows the broker accepted and this sweep marked
     * @param failed    attempts this sweep recorded against rows it could not publish
     */
    private record SweepResult(int published, int failed, List<Timer.Sample> publishAttempts) {

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
     * @param row     the row no send will carry
     * @param reason  the failure classification, at most 50 characters kept
     * @param message the failure detail, at most 72 characters kept
     * @param now     the moment this sweep started
     */
    private void recordUnpublishable(OutboxEventEntity row, String reason, String message,
            Instant now) {

        String diagnostic = describe(DeadLetterMetadata.of(ABEND_CODE, CULPRIT, reason, message));

        row.recordFailure(diagnostic, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEvents.save(row);

        log.error("Card event {} carries no publishable form: {}", row.getEventId(), diagnostic);
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
