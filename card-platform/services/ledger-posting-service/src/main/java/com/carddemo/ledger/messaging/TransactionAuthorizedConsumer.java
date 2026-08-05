package com.carddemo.ledger.messaging;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.domain.PostingService;
import com.carddemo.ledger.domain.RejectRecorder;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ingress of the ledger posting service. One authorized transaction arrives from topic
 * {@code transaction.authorized}, and this class either posts it or records it as a reject.
 *
 * <p>This listener replaces a job step. {@code app/jcl/POSTTRAN.jcl:L23} names program
 * {@code CBTRN02C} and {@code :L30-L31} allocates the sequential feed {@code DALYTRAN} it reads, so
 * the nightly window decided when a transaction reached an account balance. The same work now
 * follows the event, and the {@code DALYTRAN} file becomes a topic. Nothing else about the work
 * changes: the two updaters and the reject writer this class calls carry the source arithmetic
 * unchanged.
 *
 * <p>The order of the two decisions is the source's own. {@code 1500-B-LOOKUP-ACCT} reads the
 * account record at {@code app/cbl/CBTRN02C.cbl:L395} and assigns reject reason {@code 0101} on an
 * invalid key at {@code :L397-L399}; only a record that read cleanly reaches
 * {@code 2000-POST-TRANSACTION} at {@code :L424}. This class therefore reads the balance row first
 * and dispatches on the answer, rather than letting a posting fail partway and undoing it. The other
 * three reject reasons never arrive here, because the authorization service establishes them before
 * it publishes.
 *
 * <p>A reject is expected traffic, not an error.
 * {@code app/cbl/CBTRN02C.cbl:L229-L230} moves 4 into {@code RETURN-CODE} when the reject count is
 * positive and raises no abend, so a reject here commits, publishes one
 * {@code TransactionDeclined} through the outbox and acknowledges normally.
 *
 * <p>Idempotency is ADDITIVE and the source proves the need. A replayed feed reaches
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579}, hits a duplicate
 * key and lands in the four-statement abend at {@code :L707-L711}. Apache Kafka 4.2.1 in Kafka Raft
 * mode delivers at least once, so redelivery follows a consumer group rebalance, a restart
 * mid-batch, or a crash after side effects and before the offset commit.
 * {@link ProcessedEventRepository#claimEvent} closes the window with one insert that either takes
 * the identifier or reports that another delivery already has it.
 *
 * <p>{@link #applyOneEvent} performs the claim, the balance-row read, the domain call and its
 * writes inside one local transaction. The marker and the effects it guards therefore commit
 * together or roll back together. Nothing here publishes: {@code OutboxRelay} in the sibling
 * {@code outbox} package reads the rows the domain classes write.
 *
 * <p>Acknowledgement follows the commit. Automatic commit is off, the acknowledgement mode is
 * manual and immediate, and {@link Acknowledgment#acknowledge()} is the last statement of the
 * listener. A failure leaves the offset uncommitted and reaches the container's error handler, which
 * retries the delivery {@code carddemo.consumer.retry.max-attempts} times and then addresses the
 * record to {@code carddemo.kafka.topics.dead-letter}. Retry counting and dead-letter routing belong
 * to {@code config/KafkaConsumerConfig} and appear nowhere in this class.
 *
 * <p>The wire form is flat. One serialized {@link TransactionAuthorized} holds nineteen properties
 * in one JavaScript Object Notation (JSON) object, and a payload that nests the five envelope
 * properties fails the contract. A payload that fails schema validation never reaches this class:
 * the value deserializer refuses it inside the poll and the record takes the dead-letter route with
 * no retry.
 *
 * <p>The card number arrives already masked, so no Primary Account Number reaches this service. No
 * log line here holds a card number, an account identifier or an amount.
 */
@Component
public class TransactionAuthorizedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a payload value. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionAuthorizedConsumer.class);

    /**
     * Bean name of the listener container factory this listener runs on.
     *
     * <p>The bean carries the acknowledgement mode, the automatic-commit setting, the deserializer
     * pair and the error handler that decides between a retry and the dead-letter topic. This class
     * builds no factory and holds no error handler.
     */
    private static final String CONTAINER_FACTORY = "kafkaListenerContainerFactory";

    /** Applies one posting: category balance, account balance, transaction row, posted event. */
    private final PostingService postingService;

    /** Writes the reject row and the declined event for a transaction the ledger cannot post. */
    private final RejectRecorder rejectRecorder;

    /** Answers whether the ledger holds a balance row for the account the event names. */
    private final AccountBalanceProjectionRepository accountBalances;

    /** Claims one event identifier, so a redelivery changes nothing. */
    private final ProcessedEventRepository processedEvents;

    /** Counts events read, outcomes, failures, and the wall time of one event. */
    private final LedgerMeters meters;

    /**
     * Supplies the instant the marker records.
     *
     * <p>Fixed to Coordinated Universal Time here, as {@code domain/RejectRecorder} and
     * {@code outbox/OutboxWriter} do, so no bean of this type has to exist in the context.
     */
    private final Clock clock = Clock.systemUTC();

    /**
     * This bean as the framework holds it.
     *
     * <p>{@link #applyOneEvent} is reached through this handle, so the transaction interceptor runs
     * and every write below joins one transaction. A direct call from one method of this class to
     * another reaches the target and no interceptor.
     */
    private final ObjectProvider<TransactionAuthorizedConsumer> self;

    /**
     * Takes the two domain entry points, the two stores, the meters, the clock and the handle the
     * transactional call travels through.
     *
     * @param postingService  the posting path, {@code 2000-POST-TRANSACTION}
     * @param rejectRecorder  the reject path, {@code 2500-WRITE-REJECT-REC}
     * @param accountBalances store the balance-row read runs against
     * @param processedEvents store of duplicate-delivery markers
     * @param meters          counters and the timer this class records
     * @param self            provider of this bean, through which the transactional method is called
     * @throws NullPointerException if any argument is {@code null}
     */
    public TransactionAuthorizedConsumer(PostingService postingService,
            RejectRecorder rejectRecorder, AccountBalanceProjectionRepository accountBalances,
            ProcessedEventRepository processedEvents, LedgerMeters meters,
            ObjectProvider<TransactionAuthorizedConsumer> self) {
        this.postingService = Objects.requireNonNull(postingService, "postingService is required");
        this.rejectRecorder = Objects.requireNonNull(rejectRecorder, "rejectRecorder is required");
        this.accountBalances =
                Objects.requireNonNull(accountBalances, "accountBalances is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.meters = Objects.requireNonNull(meters, "meters is required");
        this.self = Objects.requireNonNull(self, "self is required");
    }

    /**
     * Reads one record, applies it through {@link #applyOneEvent}, counts the outcome, and
     * acknowledges.
     *
     * <p>One record reaches this method per invocation, and no batch does. The topic and the
     * consumer group resolve from configuration, each with the shipped value behind it as a default,
     * so the listener starts even where neither key is set.
     *
     * <p>A failure leaves the offset uncommitted and travels to the container's error handler. The
     * acknowledgement below is unreachable on that path, so a duplicate delivery is the expected
     * outcome and the claim inside {@link #applyOneEvent} is what makes it harmless.
     *
     * @param consumerRecord the delivery, carrying one validated event and the topic it arrived on
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if the delivery carries no payload
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.transaction-authorized:transaction.authorized}",
            groupId = "${spring.kafka.consumer.group-id:ledger-posting}",
            containerFactory = CONTAINER_FACTORY)
    public void onTransactionAuthorized(
            ConsumerRecord<String, TransactionAuthorized> consumerRecord,
            Acknowledgment acknowledgment) {
        Objects.requireNonNull(consumerRecord, "consumerRecord is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        TransactionAuthorized event = consumerRecord.value();
        if (event == null) {
            meters.recordDeserializeFailure();
            throw new IllegalArgumentException(
                    "the delivery carries no payload, so no transaction could be applied");
        }

        meters.recordEventConsumed();
        long startedAt = System.nanoTime();
        try {
            countOutcome(self.getObject().applyOneEvent(event, consumerRecord.key(),
                    consumerRecord.topic()));
        } catch (RuntimeException failure) {
            meters.recordProcessFailure();
            LOG.warn("Event {} was not applied. The failure was a {}, the offset stays uncommitted,"
                            + " and the container decides between a retry and the dead-letter"
                            + " topic.",
                    event.eventId(), failure.getClass().getSimpleName());
            throw failure;
        } finally {
            meters.recordProcessingLatency(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
    }

    /**
     * Applies one event inside one local transaction, in a fixed order.
     *
     * <p>The claim runs first, and an event another delivery already claimed leaves every table
     * untouched. The balance-row read follows, and its answer decides between the posting path and
     * the reject path. Every write of either path joins this transaction, so the marker and the
     * effects it guards commit together or not at all.
     *
     * <p>The read at {@code app/cbl/CBTRN02C.cbl:L395} is what this class reproduces before it
     * dispatches. An absent row is reject reason {@code 0101} from {@code :L397-L399}, whose
     * verbatim text {@code ACCOUNT RECORD NOT FOUND} travels on the declined event
     * {@link RejectRecorder} writes.
     *
     * <p>The account identifier and the transaction identifier travel as text, and a leading zero
     * belongs to the value.
     *
     * <p>The message key is checked before the claim and therefore before any side effect. A key
     * that names another aggregate than the payload does would post one account's transaction onto
     * another account's balance, so the delivery is refused rather than applied.
     *
     * @param event         the validated event to apply
     * @param messageKey    the Kafka message key the delivery carried, which must name the same
     *                      aggregate the payload names
     * @param consumedTopic the topic the delivery arrived on, recorded on the marker
     * @return what this delivery did
     * @throws NullPointerException     if {@code event} is {@code null}
     * @throws IllegalArgumentException if {@code messageKey} is absent or names another aggregate
     */
    @Transactional
    public Outcome applyOneEvent(TransactionAuthorized event, String messageKey,
            String consumedTopic) {
        Objects.requireNonNull(event, "event is required");
        if (messageKey == null || !messageKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException(
                    "the message key must equal the payload aggregate identifier");
        }

        UUID eventId = event.eventId();
        if (processedEvents.claimEvent(eventId, clock.instant(), consumedTopic) == 0) {
            LOG.debug("Event {} carries a marker already, so this delivery writes nothing.",
                    eventId);
            return Outcome.DUPLICATE;
        }

        if (accountBalances.findById(event.accountId()).isEmpty()) {
            rejectRecorder.recordReject(event, DeclineReason.ACCOUNT_NOT_FOUND);
            LOG.info("Event {} was rejected under reason {}, which is expected traffic and not a"
                    + " fault.", eventId, DeclineReason.ACCOUNT_NOT_FOUND.code());
            return Outcome.REJECTED;
        }

        postingService.postTransaction(event, messageKey);
        LOG.info("Event {} was posted, and one TransactionPosted row entered the outbox.", eventId);
        return Outcome.POSTED;
    }

    /**
     * Counts one applied event under the outcome it reached.
     *
     * @param outcome what the delivery did
     */
    private void countOutcome(Outcome outcome) {
        switch (outcome) {
            case POSTED -> meters.recordTransactionPosted();
            case REJECTED -> meters.recordTransactionRejected();
            case DUPLICATE -> meters.recordDuplicateSkipped();
        }
    }

    /**
     * What one delivery did. The three values are exhaustive and mutually exclusive.
     *
     * <p>{@link #POSTED} and {@link #REJECTED} are the two outcomes the source has: a posted record
     * raises {@code WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:L185} and a rejected one
     * raises {@code WS-REJECT-COUNT} at {@code :L186}. {@link #DUPLICATE} is ADDITIVE, because the
     * source has no duplicate detection at all.
     */
    public enum Outcome {

        /** The transaction reached an account balance, a category balance and a transaction row. */
        POSTED,

        /** The ledger held no balance row for the account, so reject reason {@code 0101} stands. */
        REJECTED,

        /** Another delivery of this event had already been claimed, so nothing was applied. */
        DUPLICATE
    }
}
