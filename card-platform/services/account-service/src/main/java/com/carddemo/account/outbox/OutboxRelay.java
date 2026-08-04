package com.carddemo.account.outbox;

import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes unpublished {@code outbox_event} rows on a fixed delay and marks them sent.
 *
 * <p>ADDITIVE. The one ancestor construct is the Customer Information Control System (CICS)
 * Transient Data Queue write of the paragraph {@code WIRTE-JOBSUB-TDQ} at
 * {@code app/cbl/CORPT00C.cbl:L515-L523}. That write is the single asynchronous handoff in the
 * CardDemo source.
 *
 * <p>The message key is the account identifier, eleven digits wide, from
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. The {@code payload} column
 * holds one event already serialized as JavaScript Object Notation (JSON). This class passes that
 * stored text through and reads no field of it.
 *
 * <p>Scheduling is enabled on {@code AccountApplication}, and the sweep below depends on it.
 *
 * <p>The message path: {@code card-platform/docs/event-flow.md}. The decisions behind it:
 * {@code card-platform/docs/decision-log.md}.
 */
@Component
public class OutboxRelay {

    /** Diagnostic output of this class. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * Name of the meter counting rows a broker accepted. The account service registers this meter
     * at start-up, and a lookup by name returns that registered counter.
     */
    private static final String OUTBOX_PUBLISHED_METER = "carddemo.account.outbox.published";

    /** Access to the {@code outbox_event} rows of this service. */
    private final OutboxEventRepository outboxEventRepository;

    /** The event bus every account event travels through. */
    private final EventPublisherPort eventPublisherPort;

    /** Destination topic of every row in the table. */
    private final String accountStateChangedTopic;

    /** Rows a broker accepted, one increment per row. */
    private final Counter outboxPublishedCounter;

    /**
     * Wires this relay to its table, its event bus, its topic and its meter.
     *
     * @param outboxEventRepository    access to the {@code outbox_event} rows of this service
     * @param eventPublisherPort       the event bus every account event travels through
     * @param accountStateChangedTopic destination topic, read from
     *                                 {@code carddemo.kafka.topics.account-state-changed} and
     *                                 defaulting to {@code account.state-changed}
     * @param meterRegistry            the registry holding {@value #OUTBOX_PUBLISHED_METER}
     */
    public OutboxRelay(OutboxEventRepository outboxEventRepository,
            EventPublisherPort eventPublisherPort,
            @Value("${carddemo.kafka.topics.account-state-changed:account.state-changed}")
            String accountStateChangedTopic,
            MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisherPort = eventPublisherPort;
        this.accountStateChangedTopic = accountStateChangedTopic;
        this.outboxPublishedCounter = meterRegistry.counter(OUTBOX_PUBLISHED_METER);
    }

    /**
     * Publishes one batch of unpublished rows, oldest first, and marks each accepted row sent.
     *
     * <p>Each mark commits on its own. A row a broker refuses stays unpublished, and the next
     * sweep takes it again. The rows behind it in the batch are still attempted.
     */
    @Scheduled(fixedDelay = 500)
    public void publishPendingEvents() {
        List<OutboxEventEntity> pendingEvents = outboxEventRepository
                .findByPublishedFalseOrderByCreatedAtAscEventIdAsc(Limit.of(100));
        for (OutboxEventEntity pendingEvent : pendingEvents) {
            try {
                eventPublisherPort.publish(accountStateChangedTopic,
                        pendingEvent.getAggregateId(), pendingEvent.getPayload());
                pendingEvent.markPublished(Instant.now());
                outboxEventRepository.save(pendingEvent);
                outboxPublishedCounter.increment();
            } catch (RuntimeException failure) {
                log.warn("Outbox event {} did not reach topic {}", pendingEvent.getEventId(),
                        accountStateChangedTopic, failure);
            }
        }
    }
}
