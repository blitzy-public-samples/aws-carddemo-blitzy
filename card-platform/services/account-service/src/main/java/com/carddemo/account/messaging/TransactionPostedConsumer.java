package com.carddemo.account.messaging;

import com.carddemo.account.config.ObservabilityConfig.AccountMeters;
import com.carddemo.account.domain.PostedTransactionService;
import com.carddemo.account.entity.ProcessedEventEntity;
import com.carddemo.account.repository.ProcessedEventRepository;
import com.carddemo.events.TransactionPosted;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads one {@code TransactionPosted} event and adds its amount to the account record this service
 * owns.
 *
 * <p>The source needed no listener here, because it needed no message. {@code ACCTDAT} was one
 * dataset: the batch posting program added the transaction amount to the account record at
 * {@code app/cbl/CBTRN02C.cbl:L545-L560}, the account view program read that same record at
 * {@code app/cbl/COACTVWC.cbl}, and the credit-limit test at
 * {@code app/cbl/CBTRN02C.cbl:L403-L413} read the two billing-cycle accumulators the same posting
 * had just moved. One record and one writer.
 *
 * <p>The target holds three copies of parts of that record, in three services that may not call one
 * another, so the writer and the readers are joined by an event instead. This listener is that
 * join. {@code ledger-posting-service} publishes {@code TransactionPosted} after its own posting
 * commits; this listener applies the same amount to the record here through
 * {@link PostedTransactionService}, and the state change it queues refreshes the authorization
 * service's copy of the two accumulators through the listener that service already runs.
 *
 * <p>Both halves of that chain were missing before, and the two failures it caused were silent.
 * Cumulative cycle exposure was never enforced, so reason 102 tested one amount against the credit
 * limit and approved a sixth transaction that took an account five times past its limit. And
 * {@code GET /accounts/{id}} reported the balance as it stood at deployment while
 * {@code GET /balances/{id}} reported the posted one, with no error from either.
 *
 * <p>This listener publishes no event of its own directly. It writes one outbox row through
 * {@link PostedTransactionService}, and {@code outbox/OutboxRelay} publishes that row on a later
 * sweep, so nothing is sent from inside a transaction that can still roll back.
 *
 * <p>Idempotency is ADDITIVE. No COBOL program detects a duplicate delivery: a replayed feed drives
 * the transaction write at {@code app/cbl/CBTRN02C.cbl:L562-L579} into a duplicate key and reaches
 * {@code 9999-ABEND-PROGRAM} at {@code :L577}. Here
 * {@link ProcessedEventRepository#claimEvent} takes the event identifier in the same local
 * transaction as the account row and the outbox row. A redelivery finds the identifier taken and
 * changes nothing, and a failure rolls the claim back with the writes it guarded, so the retry that
 * follows is free to apply the amount.
 *
 * <p>Acknowledgement follows the commit. A failure leaves the offset uncommitted and travels to the
 * container's error handler, which retries under {@code carddemo.consumer.retry} and then routes the
 * record to the dead-letter topic of this source topic. That wiring is
 * {@code config/KafkaConsumerConfig}.
 *
 * <p>No log line here holds a balance, an amount or a card number.
 *
 * <p>Event paths: {@code card-platform/docs/event-flow.md}. Design decisions:
 * {@code card-platform/docs/decision-log.md}.
 */
@Component
public class TransactionPostedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a monetary value. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionPostedConsumer.class);

    /** What {@link ProcessedEventRepository#claimEvent} reports when the event was already taken. */
    private static final int ALREADY_CLAIMED = 0;

    /** Applies the posting arithmetic and queues the state change. */
    private final PostedTransactionService postedTransactionService;

    /** Claims one event identifier, so a second delivery of it changes nothing. */
    private final ProcessedEventRepository processedEvents;

    /** Opens the one local transaction the claim, the account row and the outbox row commit in. */
    private final TransactionTemplate transactionTemplate;

    /** Counts events read, duplicates skipped and failures, and times one delivery. */
    private final AccountMeters meters;

    /** Supplies the instant the marker records, fixed to Coordinated Universal Time. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the domain service, the marker store, the transaction boundary and the meters.
     *
     * @param postedTransactionService applies the amount and queues the state change
     * @param processedEvents          store of duplicate-delivery markers
     * @param transactionTemplate      opens the one transaction per delivery
     * @param meters                   the recording surface this listener counts against
     * @throws NullPointerException when an argument is {@code null}
     */
    public TransactionPostedConsumer(PostedTransactionService postedTransactionService,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate,
            AccountMeters meters) {
        this.postedTransactionService = Objects.requireNonNull(postedTransactionService,
                "postedTransactionService must be present");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents must be present");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");
    }

    /**
     * Reads one delivery, applies it inside one local transaction, and acknowledges.
     *
     * <p>The topic and the group both resolve from configuration, so neither name appears here as
     * text. One record reaches this method per invocation.
     *
     * <p>The message key is checked against the aggregate the payload names before any side effect
     * runs. {@link #requireKeyNamesPayloadAggregate(String, TransactionPosted)} states why.
     *
     * <p>The check runs inside the measured block rather than ahead of it, and the delivery is
     * counted before it. A refused key is a delivery this service consumed and could not apply, so
     * counting it anywhere else left the one record that reaches retry and then the dead-letter topic
     * invisible on the consumed counter, the failure counter and the latency timer at once: the meters
     * reported a quiet service while the dead-letter topic filled.
     *
     * <p>A failure leaves the offset uncommitted and reaches the container, which decides between
     * another attempt and the dead-letter topic. The acknowledgement below is unreachable on that
     * path, so the delivery arrives again and the marker keeps the repeat harmless.
     *
     * @param event          the validated event this delivery carries
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @param messageKey     the key the delivery arrived under, which must name the payload's
     *                       aggregate
     * @throws NullPointerException     when {@code event} or {@code acknowledgment} is {@code null}
     * @throws IllegalArgumentException when the key names an aggregate the payload does not
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.transaction-posted}",
            groupId = "${carddemo.kafka.groups.transaction-posted}")
    public void onTransactionPosted(TransactionPosted event, Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey) {

        Objects.requireNonNull(event, "event must be present");
        Objects.requireNonNull(acknowledgment, "acknowledgment must be present");

        meters.recordEventConsumed();
        long startedAt = System.nanoTime();
        try {
            requireKeyNamesPayloadAggregate(messageKey, event);
            transactionTemplate.executeWithoutResult(status -> applyOneEvent(event, consumedTopic));
        } catch (RuntimeException failure) {
            meters.recordPostingFailure();
            LOG.warn("Posted transaction {} was not applied. The failure was a {}, the offset stays"
                            + " uncommitted, and the container decides between a retry and the"
                            + " dead-letter topic.",
                    event.transactionId(), failure.getClass().getSimpleName());
            throw failure;
        } finally {
            meters.recordPostingLatency(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
    }

    /**
     * Applies one event inside the open transaction: the claim first, then the arithmetic.
     *
     * <p>The claim is one statement, so no delivery reads the marker table and then writes it. Two
     * deliveries of one event therefore cannot both pass it, and the loser leaves the account row
     * and the outbox untouched.
     *
     * <p>{@link PostedTransactionService#applyPostedAmount} joins this transaction, so the marker,
     * the account row and the outbox row commit together or roll back together.
     *
     * @param event         the validated event this delivery carries
     * @param consumedTopic the topic the delivery arrived on, recorded on the marker
     */
    private void applyOneEvent(TransactionPosted event, String consumedTopic) {
        Instant now = clock.instant();
        if (processedEvents.claimEvent(event.eventId(), now,
                recordedTopic(consumedTopic)) == ALREADY_CLAIMED) {
            meters.recordPostingDuplicateSkipped();
            LOG.debug("Posted transaction {} carries a marker already, so this delivery writes"
                    + " nothing.", event.transactionId());
            return;
        }

        postedTransactionService.applyPostedAmount(event.accountId(), event.amount(),
                event.transactionId());
        meters.recordPostingApplied();
    }

    /**
     * Returns the topic to record on the marker, never blank.
     *
     * <p>The topic is half of the marker's primary key since
     * {@code src/main/resources/db/migration/V6__processed_event_topic_key.sql}, and a key
     * column holds no null. A delivery that carried no topic header records
     * {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}, which states that absence rather than leaving
     * the key half unset. That sentinel holds spaces and parentheses and a Kafka topic name holds
     * only {@code [a-zA-Z0-9._-]}, so it can never collide with a real topic name.</p>
     *
     * @param consumedTopic the topic the delivery arrived on, possibly absent or blank
     * @return the topic name, or {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}
     */
    private static String recordedTopic(String consumedTopic) {
        return consumedTopic == null || consumedTopic.isBlank()
                ? ProcessedEventEntity.NO_CONSUMED_TOPIC
                : consumedTopic;
    }

    /**
     * Refuses a delivery whose key names an aggregate the payload does not.
     *
     * <p>Kafka orders messages within a partition and nothing else, and the key selects the
     * partition. Every event this platform publishes is keyed by its account identifier, so all
     * events of one account land on one partition and stay in order. That ordering is what makes the
     * arithmetic here safe: two postings for one account are applied in the order the ledger applied
     * them. A record keyed under another account breaks the guarantee for both accounts at once.
     *
     * <p>The check runs ahead of the claim and every write. A mismatch is not retryable, since a
     * redelivery carries the same key, so it raises {@link IllegalArgumentException}, which
     * {@code config/KafkaConsumerConfig} routes to the dead-letter topic of this source topic once
     * the attempts are spent.
     *
     * <p>The message names neither the key nor the aggregate. A key is producer-controlled, so it can
     * hold anything a payload can, and repeating it in a refusal would put it in a log line.
     *
     * @param messageKey the key the delivery arrived under, or {@code null} for an unkeyed record
     * @param event      the validated event this delivery carries
     * @throws IllegalArgumentException when the key is absent or names another aggregate
     */
    private static void requireKeyNamesPayloadAggregate(String messageKey,
            TransactionPosted event) {

        if (messageKey == null || messageKey.isBlank()) {
            throw new IllegalArgumentException(
                    "This delivery carries no message key, and an unkeyed record is not ordered"
                            + " against the other events of its account.");
        }
        if (!messageKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException(
                    "The message key does not name the aggregate this payload names, so the"
                            + " partition this record arrived on is not the one that orders it.");
        }
    }
}
