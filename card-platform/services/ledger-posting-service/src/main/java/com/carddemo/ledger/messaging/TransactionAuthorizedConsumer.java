package com.carddemo.ledger.messaging;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.domain.PostingService;
import com.carddemo.ledger.entity.ProcessedEventEntity;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts one authorized transaction per invocation.
 *
 * <p>One iteration of the driver loop at {@code app/cbl/CBTRN02C.cbl:L202-L219} equals one call to
 * {@link #onTransactionAuthorized}. The job step {@code app/jcl/POSTTRAN.jcl:L23} that named
 * program {@code CBTRN02C} equals this listener, and the sequential feed it read equals a topic.
 *
 * <p>Two parts of this class are ADDITIVE and have no Common Business Oriented Language (COBOL)
 * ancestor: the processed-event guard, and the dead-letter route a failed delivery takes.
 *
 * <p>Apache Kafka 4.2.1 serves {@code transaction.authorized} on three partitions at replication
 * factor 1, and consumer group {@code ledger-posting} reads it. The account identifier is the
 * message key, so one account's events keep their order.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class TransactionAuthorizedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a payload value. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionAuthorizedConsumer.class);

    /**
     * Bean name of the listener container factory this listener runs on.
     *
     * <p>{@code config/KafkaConsumerConfig} publishes it carrying the acknowledgement mode, the
     * deserializer pair, the retry policy and the recoverer that addresses a spent record to the
     * dead-letter topic.
     */
    private static final String CONTAINER_FACTORY = "kafkaListenerContainerFactory";

    /**
     * Classification token a diagnostic from this listener carries, at the width of
     * {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    private static final String ABEND_CODE = "LPST";

    /**
     * Names this component on a diagnostic, at the width of {@code ABEND-CULPRIT PIC X(8)} at
     * {@code app/cpy/CSMSG02Y.cpy:L24}. The analogue is {@code LIT-THISPGM PIC X(8)} at
     * {@code app/cbl/COACTVWC.cbl:L143}.
     */
    private static final String CULPRIT = "LEDGPOST";

    /**
     * The one classification this listener assigns, at the width of {@code ABEND-REASON PIC X(50)}
     * at {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    private static final String POSTING_NOT_COMPLETED = "POSTING DID NOT COMPLETE";

    /** Applies one posting: category balance, account balance, transaction row, posted event. */
    private final PostingService postingService;

    /** Store of the duplicate-delivery markers. */
    private final ProcessedEventRepository processedEvents;

    /** Counts events read, outcomes and failures, and times one delivery. */
    private final LedgerMeters meters;

    /** Opens the one transaction the guard, the posting and the marker commit in. */
    private final TransactionTemplate transactionTemplate;

    /** Topic this listener subscribes to, recorded on every marker it writes. */
    private final String authorizedTopic;

    /** Topic the container addresses a spent record to, named in the failure line. */
    private final String deadLetterTopic;

    /** Supplies the instant a marker records. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the posting path, the marker store, the meters, the transaction boundary and two topic
     * names.
     *
     * @param postingService      the posting path, {@code 2000-POST-TRANSACTION}
     * @param processedEvents     store of the duplicate-delivery markers
     * @param transactionTemplate opens the one transaction per delivery
     * @param meters              counters and the timer this class records
     * @param authorizedTopic     the topic this listener reads
     * @param deadLetterTopic     the topic a spent record is addressed to
     * @throws NullPointerException if any argument is {@code null}
     */
    public TransactionAuthorizedConsumer(PostingService postingService,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate,
            LedgerMeters meters,
            @Value("${carddemo.kafka.topics.transaction-authorized}") String authorizedTopic,
            @Value("${carddemo.kafka.topics.dead-letter}") String deadLetterTopic) {
        this.postingService = Objects.requireNonNull(postingService, "postingService is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
        this.meters = Objects.requireNonNull(meters, "meters is required");
        this.authorizedTopic =
                Objects.requireNonNull(authorizedTopic, "authorizedTopic is required");
        this.deadLetterTopic =
                Objects.requireNonNull(deadLetterTopic, "deadLetterTopic is required");
    }

    /**
     * Applies one event, counts what it did, and acknowledges the offset once the transaction has
     * committed.
     *
     * <p>A failure leaves the offset uncommitted and travels to the container's error handler.
     *
     * @param event          the validated event this delivery carries
     * @param acknowledgment the offset commit, invoked after the commit
     * @throws NullPointerException if either argument is {@code null}
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.transaction-authorized}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = CONTAINER_FACTORY)
    public void onTransactionAuthorized(TransactionAuthorized event,
            Acknowledgment acknowledgment) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        meters.recordEventConsumed();
        long startedAt = System.nanoTime();
        try {
            if (Boolean.TRUE.equals(transactionTemplate.execute(status -> applyOneEvent(event)))) {
                meters.recordTransactionPosted();
            } else {
                meters.recordDuplicateSkipped();
            }
        } catch (RuntimeException failure) {
            meters.recordProcessFailure();
            LOG.error("Event {} was not applied. Diagnostic {}. The offset stays uncommitted, and a"
                    + " record the container gives up on is addressed to {}.", event.eventId(),
                    describe(failure), deadLetterTopic);
            throw failure;
        } finally {
            meters.recordProcessingLatency(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
    }

    /**
     * Guards, posts and marks inside the open transaction, marker after the posting.
     *
     * <p>The three updates {@code app/cbl/CBTRN02C.cbl:L440-L442} fixes run in that order inside
     * {@code domain/PostingService}.
     *
     * @param event the validated event to apply
     * @return {@code true} when this delivery posted, and {@code false} when a marker already
     *         covered the event
     */
    private boolean applyOneEvent(TransactionAuthorized event) {
        UUID eventId = event.eventId();
        if (processedEvents.existsById(eventId)) {
            LOG.debug("Event {} already carries a marker, so this delivery posted nothing.",
                    eventId);
            return false;
        }

        postingService.postTransaction(event, event.aggregateId());
        processedEvents.save(marker(eventId));
        return true;
    }

    /**
     * Builds the marker this delivery records, naming the topic it arrived on.
     *
     * @param eventId identifier of the applied event
     * @return the marker to store
     */
    private ProcessedEventEntity marker(UUID eventId) {
        ProcessedEventEntity marker = new ProcessedEventEntity(eventId, clock.instant());
        marker.setConsumedTopic(authorizedTopic);
        return marker;
    }

    /**
     * Builds the one diagnostic a failed delivery carries, holding no payload value.
     *
     * <p>The four components are fixed text and the failure's simple class name.
     * {@code DeadLetterMetadata} holds the widths and sanitises each one.
     *
     * @param failure the failure this delivery raised
     * @return the diagnostic naming the classification and the failure type
     */
    private static DeadLetterMetadata describe(RuntimeException failure) {
        return DeadLetterMetadata.of(ABEND_CODE, CULPRIT, POSTING_NOT_COMPLETED,
                failure.getClass().getSimpleName());
    }
}
