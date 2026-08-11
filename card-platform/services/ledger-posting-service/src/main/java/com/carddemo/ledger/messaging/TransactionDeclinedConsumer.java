package com.carddemo.ledger.messaging;

import com.carddemo.events.TransactionDeclined;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.domain.RejectRecorder;
import com.carddemo.ledger.domain.RejectRecorder.FeedTransaction;
import com.carddemo.ledger.entity.ProcessedEventEntity;
import com.carddemo.ledger.entity.ProcessedEventEntity.ProcessedEventId;
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
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records one reject row per refused transaction.
 *
 * <p>This listener is the production ingress of the reject path. In the source both outcomes of one
 * feed record are reached from one driver loop: {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378} either falls through to
 * {@code 2000-POST-TRANSACTION} or branches to {@code 2500-WRITE-REJECT-REC} at
 * {@code app/cbl/CBTRN02C.cbl:L446-L465}. The target splits that fork across two services, because
 * AAP 0.1.1 makes the authorization service the sole writer of the decision. The approval travels on
 * {@code transaction.authorized} to {@code TransactionAuthorizedConsumer}, and the refusal travels on
 * {@code transaction.declined} to this class. Both halves of the source fork therefore stay reachable
 * in production, and the reject row keeps living in the schema AAP 0.3.1 assigns it.
 *
 * <p>Three parts of this class are ADDITIVE and have no Common Business Oriented Language (COBOL)
 * ancestor: the processed-event guard, the key check, and the dead-letter route a spent delivery
 * takes.
 *
 * <p>Consumer group {@code ledger-reject} reads the topic, separately from the group the posting
 * listener uses, so the two streams lag, rebalance and reset independently. The account identifier is
 * the message key of every declined event, so one account's refusals keep their order.
 *
 * <p>Every decline this platform publishes writes one reject row here, whichever of the four reject
 * reasons stands. {@code app/cbl/CBTRN02C.cbl:L446-L465} writes one reject record for every record
 * {@code 1500-VALIDATE-TRAN} refused, so a reason that produced no row would be a reject the source
 * has and this platform does not. Reject reason {@code 0100} used to be that reason, and it is one no
 * longer: the authorization service names the account the caller declared where the cross-reference
 * read resolves none, and publishes the same detail-bearing contract as the other three.
 *
 * <h2>What this listener acknowledges without recording</h2>
 *
 * <p>A declined event at a retained contract version carries the reason and the amount and none of the
 * nine descriptive values of {@code app/cpy/CVTRA06Y.cpy:L5-L18}. Such a version cannot produce the
 * 350-byte {@code REJECT-TRAN-DATA} the source copies, and inventing the values that were not sent is
 * the one outcome equivalence forbids. Such a delivery is therefore acknowledged with no row written,
 * and the fact is logged once.
 *
 * <p>No producer writes a retained version, so that arm is reached only by a record published before
 * every decline carried those nine values. It stays because such a record is on the topic for as long
 * as its retention holds, and a consumer that meets one must account for it rather than dead-letter
 * it. The 38 reject records the fixture {@code app/data/ASCII/dailytran.txt} produces are all reject
 * reason {@code 0102} and reach this listener with their detail, so the parity evidence is unaffected
 * either way.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class TransactionDeclinedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a payload value. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionDeclinedConsumer.class);

    /**
     * Bean name of the listener container factory this listener runs on.
     *
     * <p>The same factory the posting listener uses, so both carry one acknowledgement mode, one
     * deserializer pair, one retry policy and one recoverer. The group differs, not the machinery.
     */
    private static final String CONTAINER_FACTORY = "kafkaListenerContainerFactory";

    /**
     * Classification token a diagnostic from this listener carries, at the width of
     * {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    private static final String ABEND_CODE = "LREJ";

    /**
     * Names this component on a diagnostic, at the width of {@code ABEND-CULPRIT PIC X(8)} at
     * {@code app/cpy/CSMSG02Y.cpy:L24}. The analogue is {@code LIT-THISPGM PIC X(8)} at
     * {@code app/cbl/COACTVWC.cbl:L143}.
     */
    private static final String CULPRIT = "LEDGREJ";

    /**
     * The one classification this listener assigns, at the width of {@code ABEND-REASON PIC X(50)}
     * at {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    private static final String REJECT_NOT_RECORDED = "REJECT RECORD WAS NOT WRITTEN";

    /** Writes the 430-byte reject row, {@code 2500-WRITE-REJECT-REC}. */
    private final RejectRecorder rejectRecorder;

    /** Store of the duplicate-delivery markers. */
    private final ProcessedEventRepository processedEvents;

    /** Counts events read, outcomes and failures, and times one delivery. */
    private final LedgerMeters meters;

    /** Opens the one transaction the guard, the reject row and the marker commit in. */
    private final TransactionTemplate transactionTemplate;

    /** Topic this listener subscribes to, recorded on every marker it writes. */
    private final String declinedTopic;

    /** Topic the container addresses a spent record to, named in the failure line. */
    private final String deadLetterTopic;

    /** Supplies the instant a marker records. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the reject path, the marker store, the meters, the transaction boundary and two topic
     * names.
     *
     * @param rejectRecorder      writes the reject row, {@code 2500-WRITE-REJECT-REC}
     * @param processedEvents     store of the duplicate-delivery markers
     * @param transactionTemplate opens the one transaction per delivery
     * @param meters              counters and the timer this class records
     * @param declinedTopic       the topic this listener reads
     * @param deadLetterTopic     the topic a spent record is addressed to
     * @throws NullPointerException if any argument is {@code null}
     */
    public TransactionDeclinedConsumer(RejectRecorder rejectRecorder,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate,
            LedgerMeters meters,
            @Value("${carddemo.kafka.topics.transaction-declined}") String declinedTopic,
            @Value("${carddemo.kafka.topics.dead-letter}") String deadLetterTopic) {
        this.rejectRecorder = Objects.requireNonNull(rejectRecorder, "rejectRecorder is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
        this.meters = Objects.requireNonNull(meters, "meters is required");
        this.declinedTopic = Objects.requireNonNull(declinedTopic, "declinedTopic is required");
        this.deadLetterTopic =
                Objects.requireNonNull(deadLetterTopic, "deadLetterTopic is required");
    }

    /**
     * Records one refusal, counts what it did, and acknowledges the offset once the transaction has
     * committed.
     *
     * <p>A failure leaves the offset uncommitted and travels to the container's error handler.
     *
     * @param event          the validated event this delivery carries
     * @param messageKey     the key the record arrived under, which must name the aggregate the
     *                       payload names
     * @param consumedTopic  the topic the record arrived on, which is half of the marker key
     * @param acknowledgment the offset commit, invoked after the commit
     * @throws NullPointerException     if {@code event} or {@code acknowledgment} is {@code null}
     * @throws IllegalArgumentException if the key is absent or names another aggregate
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.transaction-declined}",
            groupId = "${carddemo.kafka.groups.transaction-declined:ledger-reject}",
            containerFactory = CONTAINER_FACTORY)
    public void onTransactionDeclined(TransactionDeclined event,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey,
            @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String consumedTopic,
            Acknowledgment acknowledgment) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        meters.recordEventConsumed();
        long startedAt = System.nanoTime();
        try {
            requireKeyNamesAggregate(messageKey, event);
            switch (transactionTemplate.execute(status -> applyOneEvent(event, consumedTopic))) {
                // The rejected counter is raised here rather than beside the row, because the
                // transaction has committed by the time this switch runs. RejectRecorder counted it
                // inside the transaction, so a rollback left a durable increment reporting a reject
                // the store never kept. Reaching this arm means the row and its marker committed.
                case RECORDED -> {
                    meters.recordTransactionRejected();
                    LOG.debug("Event {} produced one reject row.", event.eventId());
                }
                case DUPLICATE -> meters.recordDuplicateSkipped();
                // Counted as neither a reject nor a duplicate, because it is neither: no row was
                // written and no earlier delivery of this event was suppressed. The events-consumed
                // counter above already accounts for it, and applyOneEvent logs the reason once.
                case NO_DETAIL -> LOG.debug("Event {} carried no reject detail.", event.eventId());
                case null -> throw new IllegalStateException(
                        "the transaction answered with no outcome");
            }
        } catch (RuntimeException failure) {
            meters.recordProcessFailure();
            // WARN rather than ERROR, because this line reports ONE ATTEMPT and a retry may still
            // succeed. The terminal outcome is reported once, at ERROR, by the recoverer in
            // config/KafkaConsumerConfig when the attempts are spent.
            LOG.warn("Event {} was not recorded on this attempt. Diagnostic {}. The offset stays"
                    + " uncommitted, and a record the container gives up on is addressed to {}.",
                    event.eventId(), describe(failure), deadLetterTopic);
            throw failure;
        } finally {
            meters.recordProcessingLatency(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
    }

    /**
     * Guards, records and marks inside the open transaction, marker after the row.
     *
     * <p>A delivery carrying no descriptive detail writes no row and still takes its marker, because
     * it has been handled: replaying it would reach the same conclusion, and leaving it unmarked
     * would make every rebalance read it again. That arm is reached only by a record published under
     * a retained declined contract before every decline carried the nine values
     * {@code REJECT-TRAN-DATA} needs. No producer writes those contracts any more, so every decline
     * arriving from here on writes its reject row, reject code {@code 0100} included, and the arm
     * exists for the records already on the topic.
     *
     * @param event         the validated event to record
     * @param consumedTopic the topic the record arrived on, half of the marker key
     * @return what this delivery did
     */
    private Outcome applyOneEvent(TransactionDeclined event, String consumedTopic) {
        UUID eventId = event.eventId();
        if (processedEvents.existsById(markerKey(eventId, consumedTopic))) {
            LOG.debug("Event {} already carries a marker for the topic it arrived on, so this"
                    + " delivery recorded nothing.", eventId);
            return Outcome.DUPLICATE;
        }

        if (!event.carriesTransactionDetail()) {
            LOG.info("Event {} arrived at contract version {}, which carries none of the nine"
                    + " descriptive values REJECT-TRAN-DATA needs, so no reject row was written."
                    + " The marker is stored, because replaying this delivery would reach the same"
                    + " conclusion.", eventId, event.schemaVersion());
            processedEvents.save(marker(eventId, consumedTopic));
            return Outcome.NO_DETAIL;
        }

        rejectRecorder.recordReject(feedRecordOf(event), event.declineReasonCode());
        processedEvents.save(marker(eventId, consumedTopic));
        return Outcome.RECORDED;
    }

    /**
     * What one delivery did, so the caller counts the three outcomes apart.
     *
     * <p>A boolean could not: a delivery that wrote no row because a marker already covered it and a
     * delivery that wrote no row because the event carried no detail are different facts, and
     * reporting the second as a duplicate would put a number in the demo that means nothing.
     */
    private enum Outcome {

        /** One reject row was written and one marker stored. */
        RECORDED,

        /** A marker already covered this event on the topic it arrived on. */
        DUPLICATE,

        /** The event carried none of the nine descriptive values, so no row could be rendered. */
        NO_DETAIL
    }

    /**
     * Rebuilds the refused feed record from the event that carries it.
     *
     * <p>Each of the thirteen components comes off the event. {@code app/cbl/CBTRN02C.cbl:L447}
     * copies the arriving record whole into the reject area, so nothing here may be derived, defaulted
     * or looked up: a refused transaction posted nowhere and exists in no dataset this service could
     * read it back out of.
     *
     * <p>The category code is the event's {@code merchantCategoryCode}, which is
     * {@code DALYTRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA06Y.cpy:L7} under the name the event
     * contract gives it.
     *
     * @param event the declined event this delivery carries, at the detail-bearing version
     * @return the feed record the reject row and its 430 bytes are rendered from
     */
    private static FeedTransaction feedRecordOf(TransactionDeclined event) {
        return new FeedTransaction(
                event.accountId(),
                event.transactionId(),
                event.transactionTypeCode(),
                event.merchantCategoryCode(),
                event.source(),
                event.description(),
                event.amount(),
                event.merchantId(),
                event.merchantName(),
                event.merchantCity(),
                event.merchantZip(),
                event.maskedCardNumber(),
                event.originTimestamp());
    }

    /**
     * Refuses a record whose key does not name the aggregate its payload names.
     *
     * <p>Kafka orders records inside one partition and nowhere else, and the key chooses the
     * partition. AAP 0.3.1 makes the account identifier the key of every event for exactly that
     * reason. A record whose key names another aggregate arrived on a partition that does not order
     * it, and a record with no key at all was partitioned at random.
     *
     * <p>The aggregate a declined event names is its own {@code aggregateId}, which is the account
     * identifier at contract versions 1 and 3 and the transaction identifier at version 2, where the
     * cross-reference read at {@code app/cbl/CBTRN02C.cbl:L385-L387} resolved no account. Comparing
     * against the envelope rather than against the account covers both shapes with one check.
     *
     * <p>The refusal is an {@link IllegalArgumentException}, so the delivery is retried and then
     * routed to the dead-letter topic. Nothing is written, because the check runs before the
     * transaction opens. Neither message names the key or the aggregate, so no identifier reaches a
     * log line through them.
     *
     * @param messageKey the key the record arrived under, possibly {@code null}
     * @param event      the refusal this delivery carries
     * @throws IllegalArgumentException when the key is absent or names another aggregate
     */
    private static void requireKeyNamesAggregate(String messageKey, TransactionDeclined event) {
        if (messageKey == null || messageKey.isBlank()) {
            throw new IllegalArgumentException("this record carries no message key, so the partition"
                    + " it arrived on is not the one that orders its aggregate");
        }
        if (!messageKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException("the message key does not name the aggregate this"
                    + " payload names, so the partition this record arrived on is not the one that"
                    + " orders that aggregate");
        }
    }

    /**
     * Builds the marker this delivery records, keyed on the event and the topic it arrived on.
     *
     * @param eventId       identifier of the recorded event
     * @param consumedTopic the topic the delivery arrived on, possibly absent
     * @return the marker to store
     */
    private ProcessedEventEntity marker(UUID eventId, String consumedTopic) {
        return new ProcessedEventEntity(eventId, clock.instant(), recordedTopic(consumedTopic));
    }

    /**
     * Builds the key this delivery claims, which is its event and the topic it arrived on.
     *
     * @param eventId       identifier of the event this delivery carries
     * @param consumedTopic the topic the delivery arrived on, possibly absent
     * @return the whole marker key
     */
    private ProcessedEventId markerKey(UUID eventId, String consumedTopic) {
        return new ProcessedEventId(eventId, recordedTopic(consumedTopic));
    }

    /**
     * Returns the topic a marker records for this delivery.
     *
     * <p>The {@code RECEIVED_TOPIC} header is preferred over the configured topic name, because a
     * marker should record what the delivery carried rather than what this listener was configured
     * to read. The configured name is the fallback for a delivery that arrived with no header at
     * all, and {@link ProcessedEventEntity#NO_CONSUMED_TOPIC} is the last resort, because the topic
     * is half of the key and a key column holds no null.
     *
     * @param consumedTopic the {@code RECEIVED_TOPIC} header, possibly absent or blank
     * @return the topic to record, never blank
     */
    private String recordedTopic(String consumedTopic) {
        if (consumedTopic != null && !consumedTopic.isBlank()) {
            return consumedTopic;
        }
        return declinedTopic.isBlank() ? ProcessedEventEntity.NO_CONSUMED_TOPIC : declinedTopic;
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
        return DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REJECT_NOT_RECORDED,
                failure.getClass().getSimpleName());
    }
}
