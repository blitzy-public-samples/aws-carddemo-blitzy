package com.carddemo.ledger.messaging;

import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Keeps {@code account_balance_projection} current from topic {@code account.state-changed}.
 *
 * <p>ADDITIVE IN FULL, and the reason is structural. {@code app/cbl/CBTRN02C.cbl:L545} reads the
 * {@code ACCTDAT} record it is about to add to, so the posting program and the account it posts to
 * were one dataset and nothing had to be told when either moved. Here the account service owns that
 * record and this service holds a copy of three of its fields, so the copy has to be told.
 *
 * <p>Two gaps close with this listener, and both were reachable without it.
 *
 * <ul>
 *   <li>An account opened after deployment had no projection row, because {@code V2__seed.sql} loads
 *       the fifty rows of {@code app/data/ASCII/acctdata.txt} and nothing added a fifty-first. Every
 *       transaction on such an account failed the balance read and reached the dead-letter topic, and
 *       no later delivery could have fixed it.</li>
 *   <li>A billing cycle closed at {@code app/cbl/CBACT04C.cbl:L353-L354} never reached the two
 *       accumulators here, so {@code cycle_credit} and {@code cycle_debit} in this service grew
 *       without bound while the account service's own copies were being zeroed each cycle.</li>
 * </ul>
 *
 * <p>The write replaces the three value columns rather than adding to them.
 * {@code app/cbl/COACTUPC.cbl:L3964-L3974} moves a balance and both accumulators from the screen onto
 * the record, overwriting whatever the posting program had accumulated, and the cycle close moves
 * zero into both. Both are rewrites of the one shared record, so replacing is what reproduces them.
 * {@link AccountBalanceProjectionRepository#applyStateChange} is one statement that inserts an absent
 * row and replaces a present one, and it discards a change that did not occur after the change the
 * row already carries.
 *
 * <p>This listener consumes and never publishes. It writes no outbox row, because a replica refresh
 * is not a fact about this service's aggregate: the account service already published the fact, and
 * republishing it would put two producers on one contract.
 *
 * <p>Idempotency is ADDITIVE. Kafka delivers at least once, so a rebalance, a restart mid-batch or a
 * crash between the write and the offset commit all redeliver.
 * {@link ProcessedEventRepository#claimEvent} takes the event identifier in the same transaction as
 * the projection write, so a redelivery finds the identifier taken and changes nothing. The claim and
 * the write commit together or roll back together, which is what makes the rolled-back claim on a
 * failure the reason a retry can still apply the change.
 *
 * <p>Acknowledgement follows the commit. A failure leaves the offset uncommitted and reaches the
 * container's error handler, which retries and then routes the record to the dead-letter topic. Retry
 * counting and dead-letter routing belong to {@code config/KafkaConsumerConfig}.
 *
 * <p>The payload arrives as a schema-checked tree rather than as a bound record.
 * {@code libs/event-contracts} holds the document for this event and names no class to build, because
 * the record belongs to the account service and no service module may depend on another.
 * {@link AccountStateChanged#from(JsonNode)} reads the checked tree into the three components this
 * service replicates.
 *
 * <p>No log line here holds a balance, an accumulator or a card number.
 */
@Component
public class AccountStateChangedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a monetary value. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountStateChangedConsumer.class);

    /**
     * Bean name of the listener container factory this listener runs on.
     *
     * <p>The same factory the posted-transaction listener uses. It carries the acknowledgement mode,
     * the automatic-commit setting, the deserializer pair and the error handler, and its value
     * deserializer accepts every governed event type: this event binds no record class, so the
     * payload arrives as a tree while a {@code TransactionAuthorized} on the sibling listener arrives
     * bound.
     */
    private static final String CONTAINER_FACTORY = "kafkaListenerContainerFactory";

    /** Applies one change to the projection, ordered and idempotent in one statement. */
    private final AccountBalanceProjectionRepository accountBalances;

    /** Claims one event identifier, so a redelivery changes nothing. */
    private final ProcessedEventRepository processedEvents;

    /** Counts events read, failures, and the wall time of one event. */
    private final LedgerMeters meters;

    /**
     * Supplies the instant the marker records.
     *
     * <p>Fixed to Coordinated Universal Time here, as the sibling listener does, so no bean of this
     * type has to exist in the context.
     */
    private final Clock clock = Clock.systemUTC();

    /**
     * This bean as the framework holds it.
     *
     * <p>{@link #applyOneEvent} is reached through this handle, so the transaction interceptor runs
     * and both writes join one transaction. A direct call from one method of this class to another
     * reaches the target and no interceptor.
     */
    private final ObjectProvider<AccountStateChangedConsumer> self;

    /**
     * Takes the store this listener writes through, the marker store, the meters and its own handle.
     *
     * @param accountBalances store over {@code account_balance_projection}
     * @param processedEvents store of duplicate-delivery markers
     * @param meters          counters and the timer this class records
     * @param self            provider of this bean, through which the transactional method is called
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountStateChangedConsumer(AccountBalanceProjectionRepository accountBalances,
            ProcessedEventRepository processedEvents, LedgerMeters meters,
            ObjectProvider<AccountStateChangedConsumer> self) {
        this.accountBalances =
                Objects.requireNonNull(accountBalances, "accountBalances is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.meters = Objects.requireNonNull(meters, "meters is required");
        this.self = Objects.requireNonNull(self, "self is required");
    }

    /**
     * Reads one record, applies it through {@link #applyOneEvent}, and acknowledges.
     *
     * <p>A {@code null} payload is Kafka's tombstone. This stream produces none: an account is never
     * deleted by a state change, and this projection has no row to remove. One is refused rather than
     * ignored, because silently accepting a message this contract cannot produce would hide a producer
     * defect.
     *
     * @param message        the schema-checked message tree, or {@code null} for a tombstone
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @throws NullPointerException     if {@code acknowledgment} is {@code null}
     * @throws IllegalArgumentException if the payload is a tombstone
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.account-state-changed:account.state-changed}",
            groupId = "${carddemo.kafka.groups.account-state-changed:ledger-account-state}",
            containerFactory = CONTAINER_FACTORY)
    public void onAccountStateChanged(JsonNode message, Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {

        Objects.requireNonNull(acknowledgment, "acknowledgment is required");
        if (message == null) {
            meters.recordDeserializeFailure();
            throw new IllegalArgumentException("account.state-changed carries no tombstone, and a "
                    + "record with no payload cannot name the account it changed");
        }

        AccountStateChanged event = AccountStateChanged.from(message);
        meters.recordEventConsumed();
        long startedAt = System.nanoTime();
        try {
            self.getObject().applyOneEvent(event, consumedTopic);
        } catch (RuntimeException failure) {
            meters.recordProcessFailure();
            LOG.warn("Change {} was not applied. The failure was a {}, the offset stays uncommitted,"
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
     * Applies one change inside one local transaction: the claim first, then the projection write.
     *
     * <p>The claim runs first, and a change another delivery already claimed leaves the projection
     * untouched. Both writes join this transaction, so the marker and the row it guards commit
     * together or roll back together.
     *
     * <p>A write that answers zero is not a failure. It means the row already carries a change that
     * occurred later, so this delivery is a redelivery arriving behind a newer one and leaving the
     * row alone is the correct outcome. The marker is still taken, because the delivery has been
     * dealt with.
     *
     * @param event         the validated change this delivery carries
     * @param consumedTopic the topic the delivery arrived on, recorded on the marker
     * @return {@code true} when the projection moved, and {@code false} when a later change stood
     */
    @Transactional
    public boolean applyOneEvent(AccountStateChanged event, String consumedTopic) {
        Objects.requireNonNull(event, "event is required");

        if (processedEvents.claimEvent(event.eventId(), clock.instant(), consumedTopic) == 0) {
            LOG.debug("Change {} carries a marker already, so this delivery writes nothing.",
                    event.eventId());
            return false;
        }

        int applied = accountBalances.applyStateChange(event.accountId(), event.currentBalance(),
                event.currentCycleCredit(), event.currentCycleDebit(), event.eventId(),
                event.occurredAt());

        if (applied == 0) {
            LOG.info("Change {} for account {} moved nothing, because the projection already carries"
                            + " a later change.", event.eventId(),
                    "*".repeat(event.accountId().length()));
            return false;
        }
        return true;
    }
}
