package com.carddemo.card.outbox;

import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.messaging.DeadLetterMetadata;
import com.carddemo.card.messaging.EventPublisherPort;
import com.carddemo.card.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the unpublished rows of {@code outbox_event}, then marks each one sent.
 *
 * <p>ADDITIVE. No CardDemo program relays an event. The source holds one asynchronous handoff:
 * paragraph {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl:L515-L523} writes one record to
 * a Customer Information Control System (CICS) transient data queue, and a separate job reads it
 * later. The write and the send are two units of work there, as they are here.
 *
 * <p>The card update path stores its row in the same local transaction as the card change it
 * describes. This class runs afterwards on a schedule, and each row it marks commits on its own.
 * Request handling publishes nothing, so an unreachable broker delays an event and never fails an
 * update.
 *
 * <p>All eight {@code DEFINE FILE} blocks of {@code app/csd/CARDDEMO.CSD} carry
 * {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}, eight occurrences of each. Atomicity and
 * idempotency are ADDITIVE.
 *
 * <p>A publish precedes its mark. A process that stops between the two publishes the row again on a
 * later sweep, so delivery is at least once and every consumer absorbs the repeat.
 *
 * <p>Scheduling is enabled on {@code com.carddemo.card.CardApplication}, which is what starts the
 * sweep. The three values this class reads come from the {@code carddemo} block of
 * {@code src/main/resources/application.yml}.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md} (planned).
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

    /** Rows one sweep reads, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Topic a card update travels on, from {@code carddemo.kafka.topics.card-updated}. */
    private final String cardUpdatedTopic;

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
     * @param batchSize              rows one sweep reads
     * @param cardUpdatedTopic       topic a card update travels on
     * @throws NullPointerException if any collaborator is absent
     */
    public OutboxRelay(OutboxEventRepository outboxEvents, EventPublisherPort publisher,
            @Qualifier("cardEventsPublishedCounter") Counter eventsPublished,
            @Qualifier("cardInfrastructureFailureCounter") Counter infrastructureFailures,
            CardLatencyTimers timers,
            @Value("${carddemo.outbox.relay.batch-size}") int batchSize,
            @Value("${carddemo.kafka.topics.card-updated}") String cardUpdatedTopic) {

        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.eventsPublished = Objects.requireNonNull(eventsPublished, "eventsPublished");
        this.infrastructureFailures =
                Objects.requireNonNull(infrastructureFailures, "infrastructureFailures");
        this.publishLatency = Objects.requireNonNull(timers, "timers").eventPublish();
        this.batchSize = batchSize;
        this.cardUpdatedTopic = Objects.requireNonNull(cardUpdatedTopic, "cardUpdatedTopic");
    }

    /**
     * Publishes every unpublished row, oldest first, and marks each row it sends.
     *
     * <p>Oldest first keeps one account's events in the order their updates committed. The writer
     * stamps each row as it stores it. Every message carries the account identifier as its key, so
     * one account's events land on one partition and stay ordered.
     *
     * <p>A send that fails on infrastructure leaves its row untouched and ends the sweep, so a later
     * event of one account cannot overtake an earlier one still waiting. The next sweep resumes at
     * that row. The configured delay between sweeps is the whole retry mechanism.
     *
     * <p>A row the publish port refuses takes one recorded failure and the sweep continues to the
     * next row. Such a row reaches {@code ABANDONED} after
     * {@value OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts, and
     * {@link OutboxEventEntity#isTerminal()} skips it from then on.
     *
     * <p>The sweep is idle when no card changed, since a card list and a card read store no row.
     *
     * <p>No failure leaves this method. An unreachable database or broker yields one log line and one
     * counted failure per sweep, and the schedule carries on.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    public void publishPendingEvents() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            infrastructureFailures.increment();
            log.warn("The outbox sweep ended early after {}, and a later sweep runs again",
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Reads one batch and works through it, oldest row first.
     *
     * @throws RuntimeException when the store cannot be read or a mark cannot be written
     */
    private void sweepOnce() {

        List<OutboxEventEntity> pending =
                outboxEvents.findByPublishedFalseOrderByCreatedAtAsc(Limit.of(batchSize));

        for (OutboxEventEntity row : pending) {

            if (row.isTerminal()) {
                continue;
            }

            if (!CardUpdated.EVENT_TYPE.equals(row.getEventType())) {
                recordUnpublishable(row, "no configured topic for the stored event type",
                        "stored event type reaches no topic of this service");
                continue;
            }

            try {
                publishAndMark(row);
                eventsPublished.increment();
            } catch (IllegalArgumentException refused) {
                recordUnpublishable(row,
                        "publish port refused: " + refused.getClass().getSimpleName(),
                        "payload refused for topic " + cardUpdatedTopic);
            } catch (RuntimeException failure) {
                infrastructureFailures.increment();
                log.warn("Publishing card event {} failed with {}, so the row stays unpublished "
                        + "and a later sweep sends it", row.getEventId(),
                        failure.getClass().getSimpleName());
                return;
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
     * did not acknowledge. The mark commits on its own, which keeps every earlier row of the sweep
     * marked when a later one fails.
     *
     * @param row the unpublished row
     * @throws IllegalArgumentException when the publish port refuses the key or the payload
     * @throws RuntimeException         when the send fails
     */
    void publishAndMark(OutboxEventEntity row) {
        publishLatency.record(
                () -> publisher.publish(cardUpdatedTopic, row.getAggregateId(), row.getPayload()));
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
     */
    private void recordUnpublishable(OutboxEventEntity row, String reason, String message) {

        String diagnostic = describe(DeadLetterMetadata.of(ABEND_CODE, CULPRIT, reason, message));
        Instant attemptedAt = Instant.now();

        row.recordFailure(diagnostic, attemptedAt, attemptedAt);
        outboxEvents.save(row);
        infrastructureFailures.increment();

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
