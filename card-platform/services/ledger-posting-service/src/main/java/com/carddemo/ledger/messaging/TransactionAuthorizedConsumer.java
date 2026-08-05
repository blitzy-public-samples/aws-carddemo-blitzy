package com.carddemo.ledger.messaging;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.domain.PostingService;
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
 * {@code transaction.authorized}, and this class posts it.
 *
 * <p>This listener replaces a job step. {@code app/jcl/POSTTRAN.jcl:L23} names program
 * {@code CBTRN02C} and {@code :L30-L31} allocates the sequential feed {@code DALYTRAN} it reads, so
 * the nightly window decided when a transaction reached an account balance. The same work now
 * follows the event, and the {@code DALYTRAN} file becomes a topic. Nothing else about the work
 * changes: the two updaters this class reaches through {@code domain/PostingService} carry the source
 * arithmetic unchanged.
 *
 * <p>This class reaches no decision and records no reject. All four reject reasons of
 * {@code 1500-VALIDATE-TRAN} at {@code app/cbl/CBTRN02C.cbl:L370-L420} are established by the
 * authorization service before it publishes, including reason {@code 0101} from
 * {@code 1500-B-LOOKUP-ACCT} at {@code :L395-L399}. An event that arrives here has passed all four,
 * so the only outcomes left are that it posts or that this service cannot post it.
 *
 * <p>The second of those is a fault and not a reject. {@code account_balance_projection} is a replica
 * kept current by {@code messaging/AccountStateChangedConsumer}, so a missing row means this service's
 * replica is behind and not that the account does not exist — the authorization service read its own
 * copy of that account moments earlier. {@code domain/AccountBalanceUpdater} raises
 * {@code AccountBalanceRowMissingException}, the transaction rolls back, the offset stays uncommitted
 * and the record retries and then reaches the dead-letter topic with nothing applied. Recording a
 * reject instead would publish a {@code TransactionDeclined} for a transaction another service had
 * already approved, and no consumer of that topic can tell such a decline from a real one.
 *
 * <p>Idempotency is ADDITIVE and the source proves the need. A replayed feed reaches
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579}, hits a duplicate
 * key and lands in the four-statement abend at {@code :L707-L711}. Apache Kafka 4.2.1 in Kafka Raft
 * mode delivers at least once, so redelivery follows a consumer group rebalance, a restart
 * mid-batch, or a crash after side effects and before the offset commit.
 * {@link ProcessedEventRepository#claimEvent} closes the window with one insert that either takes
 * the identifier or reports that another delivery already has it.
 *
 * <p>{@link #applyOneEvent} performs the claim, the domain call and its writes inside one local
 * transaction. The marker and the effects it guards therefore commit
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
     * @param processedEvents store of duplicate-delivery markers
     * @param meters          counters and the timer this class records
     * @param self            provider of this bean, through which the transactional method is called
     * @throws NullPointerException if any argument is {@code null}
     */
    public TransactionAuthorizedConsumer(PostingService postingService,
            ProcessedEventRepository processedEvents, LedgerMeters meters,
            ObjectProvider<TransactionAuthorizedConsumer> self) {
        this.postingService = Objects.requireNonNull(postingService, "postingService is required");
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
     * untouched. The posting follows. Every write joins this transaction, so the marker and the
     * effects it guards commit together or not at all — including on the failure path, where the
     * rolled-back claim is what lets the redelivery try again.
     *
     * <p>There is no dispatch and no second outcome to choose. A posting this service cannot complete
     * raises out of {@code domain/PostingService}, and the exception travels to the caller and then to
     * the container's error handler.
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
     * @throws com.carddemo.ledger.domain.AccountBalanceUpdater.AccountBalanceRowMissingException
     *         when this service holds no balance row for the account the event names, which is a
     *         replica fault and reaches the dead-letter topic rather than becoming a decline
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
            case DUPLICATE -> meters.recordDuplicateSkipped();
        }
    }

    /**
     * What one delivery did. The two values are exhaustive and mutually exclusive.
     *
     * <p>{@link #POSTED} raises {@code WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:L185}.
     * {@link #DUPLICATE} is ADDITIVE, because the source has no duplicate detection at all.
     *
     * <p>There is no rejected value. A reject is a feed-validation failure and
     * {@code domain/RejectRecorder} records it against {@code WS-REJECT-COUNT} at {@code :L186}; an
     * event reaching this class carries a decision the authorization service already published, so
     * this class has nothing left to refuse. A transaction it cannot post is a fault and leaves by
     * the failure path rather than as an outcome.
     */
    public enum Outcome {

        /** The transaction reached an account balance, a category balance and a transaction row. */
        POSTED,

        /** Another delivery of this event had already been claimed, so nothing was applied. */
        DUPLICATE
    }
}
