package com.carddemo.notification.messaging;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.CardholderContextReader;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.domain.NotificationService.CardholderDetails;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads one {@code TransactionPosted} event, writes one row of the card-keyed read model, and asks
 * the domain layer for one cardholder alert.
 *
 * <p>The nightly sort-then-copy sequence of {@code app/jcl/CREASTMT.JCL}, a Job Control Language
 * (JCL) member, becomes one upsert per event in the same key order. That job sorted the transaction
 * file on card number then transaction identifier at {@code app/jcl/CREASTMT.JCL:L53}, copied the
 * result into a keyed cluster at {@code app/jcl/CREASTMT.JCL:L61}, and ran the rendering program at
 * {@code app/jcl/CREASTMT.JCL:L79}. Rendering belongs to {@link NotificationService}.
 *
 * <p>One event fills every column of one row. {@code TransactionPosted} at schema version
 * {@value TransactionPosted#TRANSACTION_DETAIL_SCHEMA_VERSION} carries the whole posted transaction
 * record, so no column takes a filled default and no field of a response reads as spaces or zeros
 * waiting for a second event. {@link #readModelRow(TransactionPosted)} names each column and the
 * event field it copies. Each text value reaches its column at the declared width, which is the
 * state a {@code MOVE} into a {@code PIC X(n)} field leaves.
 *
 * <p>An event at schema version 1 carries four of those values and no card token, so it can key no
 * row. {@link #readModelRow(TransactionPosted)} refuses it, and
 * {@code config/KafkaConsumerConfig} routes the delivery to the dead-letter topic once the retries
 * configured under {@code carddemo.consumer.retry} are spent. Refusing is the honest answer: a row
 * keyed on an invented token, or one carrying eight blank columns, is worse than a delivery an
 * operator can see and replay.
 *
 * <p>The read-model key holds the card token. {@code app/cpy/COSTM01.CPY:L22} declares
 * {@code TRNX-CARD-NUM PIC X(16)}, which carried a full Primary Account Number (PAN). This platform
 * stores no PAN, and the masked form cannot take the key's place: two cards sharing their last four
 * digits mask to one value, so a key over it would merge their histories. The masked form travels
 * beside the key as display data.
 *
 * <p>The idempotency guard here is ADDITIVE. No Common Business Oriented Language (COBOL) program
 * detects a duplicate delivery, and the transaction write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} meets a duplicate key on a replayed feed and ends the run.
 * The failure metadata this listener fills is ADDITIVE on the same measure, and the masked card
 * number the event carries is ADDITIVE.
 *
 * <p>Two measured facts meet a contributor here. The posting timestamp carries two significant
 * fractional digits and four zero characters, fixed by {@code DB2-MIL PIC 9(002)} at
 * {@code app/cbl/CBTRN02C.cbl:L173} and by the four zeros at {@code app/cbl/CBTRN02C.cbl:L701}. A
 * byte-for-byte comparison of that text against a Java instant therefore fails on every record.
 * Monetary values truncate toward zero and never round upward, which {@link CobolDecimal} pins for
 * every caller.
 *
 * <p>A new consumer reads an event that already travels, so adding one needs no change to the
 * service that publishes it. For the path one message takes from publish through consume to the
 * dead-letter topic, read {@code card-platform/docs/event-flow.md}. The choices behind this
 * listener sit in {@code card-platform/docs/decision-log.md}.
 */
@Component
public class TransactionPostedConsumer {

    /** Writes the four diagnostic lines this class emits, none carrying a payload value. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionPostedConsumer.class);

    /**
     * Failure classifier the dead-letter metadata carries, four characters wide.
     *
     * <p>{@code MOVE 999 TO ABCODE} at {@code app/cbl/CBTRN02C.cbl:L710} supplies the digits, and
     * {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22} supplies the width.
     */
    private static final String ABEND_CODE = "0999";

    /**
     * Identity of this listener in the dead-letter metadata, eight characters wide, from
     * {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    private static final String CULPRIT = "notif-tp";

    /**
     * Detail the dead-letter metadata carries on every failure.
     *
     * <p>The text names one field by its JavaScript Object Notation (JSON) pointer and holds no
     * field value. One local transaction covers the row, the alert and the marker, so a failure on
     * any of the three leaves all three unwritten.
     */
    private static final String NOTHING_WRITTEN =
            "no read-model row, alert or marker written for /transactionId";

    /** Reads and writes one row of the card-keyed read model. */
    private final StatementTransactionRepository statementTransactions;

    /** Claims one event identifier, so a second delivery of it changes nothing. */
    private final ProcessedEventRepository processedEvents;

    /** Reads the account-keyed cardholder fields one alert reports. */
    private final CardholderContextReader cardholderContextReader;

    /** Renders one cardholder alert and records the delivery attempt. */
    private final NotificationService notificationService;

    /** Runs the guard, the row, the alert and the marker inside one local transaction. */
    private final TransactionTemplate transactionTemplate;

    /** Counts events read and duplicates skipped, times one delivery, and counts a failure. */
    private final NotificationMetrics metrics;

    /**
     * Takes the three repositories, the domain service, the transaction runner and the meters.
     *
     * @param statementTransactions store of read-model rows
     * @param processedEvents       store of duplicate-delivery markers
     * @param cardholderContextReader reader of the account-keyed cardholder projection
     * @param notificationService   renderer of the cardholder alert and writer of the attempt row
     * @param transactionTemplate   runner of the one local transaction this listener opens
     * @param metrics               the meter holder {@code config/ObservabilityConfig} registers
     * @throws NullPointerException if any argument is null
     */
    public TransactionPostedConsumer(StatementTransactionRepository statementTransactions,
            ProcessedEventRepository processedEvents,
            CardholderContextReader cardholderContextReader,
            NotificationService notificationService,
            TransactionTemplate transactionTemplate, NotificationMetrics metrics) {
        this.statementTransactions =
                Objects.requireNonNull(statementTransactions, "statementTransactions is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.cardholderContextReader = Objects.requireNonNull(cardholderContextReader,
                "cardholderContextReader is required");
        this.notificationService =
                Objects.requireNonNull(notificationService, "notificationService is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
    }

    /**
     * Reads one delivery, applies it inside one local transaction, and acknowledges.
     *
     * <p>The topic and the consumer group both resolve from configuration, so neither name appears
     * here as text. One record reaches this method per invocation.
     *
     * <p>A failure leaves the offset uncommitted and travels to the listener container, which
     * decides between another delivery attempt and the dead-letter topic. The acknowledgement below
     * is unreachable on that path, so a repeat delivery follows and the marker keeps it harmless.
     *
     * <p>A delivery running alongside this one is settled by the claim
     * {@link #applyOneEvent(TransactionPosted, StatementTransactionEntity, String)} takes, which
     * reports whether this delivery took the event. Every integrity violation that reaches this
     * method is therefore a fault of a write and not a duplicate, and it travels on unchanged.
     *
     * <p>The message key is checked against the aggregate the payload names before any side effect
     * runs. {@link #requireKeyNamesPayloadAggregate(String, TransactionPosted)} states why.
     *
     * @param event          the validated event this delivery carries
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @param messageKey     the key the delivery arrived under, which must name the payload's
     *                       aggregate
     * @throws NullPointerException if {@code event} or {@code acknowledgment} is null
     * @throws IllegalArgumentException if the key names an aggregate the payload does not
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.transaction-posted}",
            groupId = "${carddemo.kafka.groups.transaction-posted}")
    public void onTransactionPosted(TransactionPosted event, Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");
        requireKeyNamesPayloadAggregate(messageKey, event);

        metrics.eventsConsumed(NotificationMetrics.EVENT_TRANSACTION_POSTED).increment();
        long startedAt = System.nanoTime();
        try {
            StatementTransactionEntity row = readModelRow(event);
            transactionTemplate
                    .executeWithoutResult(status -> applyOneEvent(event, row, consumedTopic));
        } catch (RuntimeException failure) {
            reportFailure(event.eventId(), failure);
            throw failure;
        } finally {
            metrics.processingLatency(NotificationMetrics.EVENT_TRANSACTION_POSTED)
                    .record(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
    }

    /**
     * Applies one event inside the open transaction, in a fixed order.
     *
     * <p>The claim runs first and reports whether this delivery took the event. An event another
     * delivery already claimed leaves all three tables untouched. The row follows, then the alert.
     * Each write joins the transaction the caller opened, so the claim and the effects it guards
     * commit together or not at all.
     *
     * <p>One statement claims the event, so no delivery reads the marker table and then writes it.
     * Two deliveries of one event therefore cannot both pass the claim.
     *
     * <p>The alert text reaches no table: {@code src/main/resources/db/migration/V1__schema.sql}
     * declares no column for a rendered document. {@link NotificationService} writes the attempt
     * row and totals the amounts the alert carries.
     *
     * @param event         the validated event this delivery carries
     * @param row           the read-model row built from that event
     * @param consumedTopic the topic the delivery arrived on, recorded on the marker
     */
    private void applyOneEvent(TransactionPosted event, StatementTransactionEntity row,
            String consumedTopic) {
        UUID eventId = event.eventId();
        if (processedEvents.claimEvent(eventId, Instant.now(), topicOrNull(consumedTopic))
                == ProcessedEventRepository.ALREADY_CLAIMED) {
            metrics.duplicatesSkipped().increment();
            LOG.debug("Event {} carries a marker already, so this delivery writes nothing.",
                    eventId);
            return;
        }

        upsertReadModelRow(row);
        notificationService.renderPostedTransactionAlert(row.getId().getCardToken(),
                event.maskedCardNumber(), event.transactionId(), event.accountId(),
                event.newBalance(), cardholderDetails(event.accountId()),
                RenderedFormat.PLAIN_TEXT);
    }

    /**
     * Writes one row of the read model under its composite key, whether or not that key is held.
     *
     * <p>The key stands in for the 32-byte key of {@code KEYS(32 0)} at
     * {@code app/jcl/CREASTMT.JCL:L30}: the card, then the transaction identifier. One read holds
     * those two parts in the order {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at
     * {@code app/jcl/CREASTMT.JCL:L53} produced, which the primary key of
     * {@code statement_transaction} keeps.
     *
     * <p>The entity carries no setter, so the twelve non-key values arrive on a fresh instance and
     * {@code save} carries them onto the stored row under the same key.
     *
     * <p>The two diagnostic lines below name the transaction and not the card. The card token is
     * derived from the card number under a deployment key, so it is stable for one card across every
     * request: a token in a log line is a durable identifier for a cardholder even though it reveals
     * no digit of the number it stands for. The transaction identifier names one event instead.
     *
     * @param row the row to store, carrying all fourteen column values
     */
    private void upsertReadModelRow(StatementTransactionEntity row) {
        String transactionId = row.getId().getTransactionId();

        if (statementTransactions.findById(row.getId()).isPresent()) {
            LOG.debug("Transaction {} holds a read-model row already, and this delivery replaces its"
                    + " twelve non-key values.", transactionId);
        } else {
            LOG.debug("Transaction {} enters the read model.", transactionId);
        }
        statementTransactions.save(row);
    }

    /**
     * Maps one event onto the fourteen column values of one read-model row.
     *
     * <p>Every column copies an event field, and none takes a filled default. The event carries the
     * whole posted transaction record at schema version
     * {@value TransactionPosted#TRANSACTION_DETAIL_SCHEMA_VERSION}, copied there from the
     * authorization event by {@code TransactionPosted.forAuthorized}, so a row is complete on the
     * first delivery and stays complete.
     *
     * <p>Column by column, against {@code app/cpy/COSTM01.CPY}:</p>
     *
     * <pre>
     * card_token            L22   cardToken             identity, stands in for TRNX-CARD-NUM
     * transaction_id        L23   transactionId         TRNX-ID
     * masked_card_number    L22   maskedCardNumber      display
     * type_code             L25   transactionTypeCode   TRNX-TYPE-CD
     * category_code         L26   merchantCategoryCode  TRNX-CAT-CD
     * source                L27   source                TRNX-SOURCE
     * description           L28   description           TRNX-DESC
     * amount                L29   amount                TRNX-AMT
     * merchant_id           L30   merchantId            TRNX-MERCHANT-ID
     * merchant_name         L31   merchantName          TRNX-MERCHANT-NAME
     * merchant_city         L32   merchantCity          TRNX-MERCHANT-CITY
     * merchant_zip          L33   merchantZip           TRNX-MERCHANT-ZIP
     * origin_timestamp      L34   originTimestamp       TRNX-ORIG-TS
     * processing_timestamp  L35   postedAt              TRNX-PROC-TS
     * </pre>
     *
     * <p>{@code origin_timestamp} carries the moment the transaction originated, which the
     * authorization service captured and the event carries. It is not derived from the posting
     * timestamp: the two are different moments, and reporting one as the other would misstate when a
     * cardholder's transaction happened.
     *
     * <p>The event's new balance reaches no column, and {@link NotificationService} reads it for the
     * balance the alert carries.
     *
     * @param event the validated event this delivery carries
     * @return a row carrying all fourteen column values, none of them null
     * @throws IllegalArgumentException if the event carries no card token, which is every event at
     *                                  schema version 1, if the card number is not masked, if the
     *                                  transaction identifier is not
     *                                  {@value PicClause#TRAN_ID_WIDTH} characters, or if a text
     *                                  value is wider than the column that holds it
     */
    private static StatementTransactionEntity readModelRow(TransactionPosted event) {
        StatementTransactionEntity.StatementTransactionId identifier =
                new StatementTransactionEntity.StatementTransactionId(requireCardToken(event),
                        event.transactionId());

        return new StatementTransactionEntity(identifier,
                event.maskedCardNumber(),
                atWidth(event.transactionTypeCode(), PicClause.TRAN_TYPE_CD_WIDTH, "typeCode"),
                event.merchantCategoryCode(),
                atWidth(event.source(), PicClause.TRAN_SOURCE_WIDTH, "source"),
                atWidth(event.description(), PicClause.TRAN_DESC_WIDTH, "description"),
                CobolDecimal.truncateToScale(event.amount(), PicClause.TRAN_AMT_SCALE),
                event.merchantId(),
                atWidth(event.merchantName(), PicClause.TRAN_MERCHANT_NAME_WIDTH, "merchantName"),
                atWidth(event.merchantCity(), PicClause.TRAN_MERCHANT_CITY_WIDTH, "merchantCity"),
                atWidth(event.merchantZip(), PicClause.TRAN_MERCHANT_ZIP_WIDTH, "merchantZip"),
                event.originTimestamp(),
                event.postedAt());
    }

    /**
     * Returns the card token the event carries, refusing an event that carries none.
     *
     * <p>Only schema version {@value TransactionPosted#TRANSACTION_DETAIL_SCHEMA_VERSION} carries a
     * token. An event at version 1 predates the card identity this read model is keyed on, and no
     * token can be recovered from the masked card number it carries: masking discards twelve of the
     * sixteen digits the derivation reads.
     *
     * <p>The refusal names the field by its JavaScript Object Notation (JSON) pointer and the
     * version that carries it, and no card number or event value reaches the message.
     *
     * @param event the validated event this delivery carries
     * @return the card token
     * @throws IllegalArgumentException when the event carries no card token
     */
    private static String requireCardToken(TransactionPosted event) {
        String cardToken = event.cardToken();
        if (cardToken == null) {
            throw new IllegalArgumentException("/cardToken carries no value, so this event keys no"
                    + " read-model row: schema version "
                    + TransactionPosted.TRANSACTION_DETAIL_SCHEMA_VERSION
                    + " carries the card token and this event reports version "
                    + event.schemaVersion());
        }
        return cardToken;
    }

    /**
     * Returns one text value at the width its column declares, padding on the right with spaces.
     *
     * <p>A {@code PIC X(n)} field always holds n characters, and a {@code MOVE} of a shorter value
     * into one pads the remainder with spaces. Padding here keeps the row this method builds equal to
     * the row a later read returns from a {@code CHAR(n)} column, which pads on the same rule.
     *
     * <p>The event validates each of these values against the same width, so a longer value cannot
     * arrive through the listener. The check stays because a direct caller is not bound by that
     * validation, and a silent truncation would drop the tail of a merchant name.
     *
     * @param value the value the event carries
     * @param width the declared width, from {@link PicClause}
     * @param field the field name the message reports
     * @return the value at exactly {@code width} characters
     * @throws NullPointerException when the value is null
     * @throws IllegalArgumentException when the value is wider than the column
     */
    private static String atWidth(String value, int width, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.length() > width) {
            throw new IllegalArgumentException(field + " holds " + value.length()
                    + " characters and this column holds " + width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Normalises the consumed topic for the marker column, which holds null for none.
     *
     * @param consumedTopic the topic the delivery arrived on, possibly null or blank
     * @return the topic, or null when the delivery named none
     */
    private static String topicOrNull(String consumedTopic) {
        return consumedTopic == null || consumedTopic.isBlank() ? null : consumedTopic;
    }

    /**
     * Counts one failure and reports its four metadata fields, leaving the throw to the caller.
     *
     * <p>The four fields carry the layout of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}. {@code ABEND-REASON} names the exception type and
     * {@code ABEND-MSG} names one field by its JSON pointer, so no field value and no payload
     * reaches a log line. Retry counting and routing to the dead-letter
     * topic belong to the listener container in {@code com.carddemo.notification.config}.
     *
     * @param eventId the identifier of the event that failed
     * @param failure the fault this delivery raised
     */
    private void reportFailure(UUID eventId, RuntimeException failure) {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(ABEND_CODE, CULPRIT,
                failure.getClass().getSimpleName(), NOTHING_WRITTEN);
        metrics.failures(failureKind(failure)).increment();

        LOG.atError()
                .addKeyValue("abendCode", metadata.abendCode())
                .addKeyValue("abendCulprit", metadata.culprit())
                .addKeyValue("abendReason", metadata.reason())
                .addKeyValue("abendMessage", metadata.message())
                .log("Event {} was not applied, and this delivery stays unacknowledged.", eventId);
    }

    /**
     * Names the failure kind one fault counts under.
     *
     * <p>A database fault counts as a persistence failure and every other fault as a rendering
     * failure. {@code ObservabilityConfig.NotificationMetrics#isPersistenceFault} decides the first
     * case for the whole service: a paused or unreachable database raises
     * {@code CannotCreateTransactionException} from the connection pool, which is a transaction
     * fault and not a data-access fault, so testing for the latter alone counted a database outage as
     * a rendering failure and left the persistence series at zero.
     * {@code config/ObservabilityConfig} registers both series.
     *
     * @param failure the fault this delivery raised
     * @return the tag value the failure counter carries
     */
    private static String failureKind(RuntimeException failure) {
        return NotificationMetrics.isPersistenceFault(failure)
                ? NotificationMetrics.FAILURE_PERSISTENCE
                : NotificationMetrics.FAILURE_RENDERING;
    }

    /**
     * Refuses a delivery whose key names an aggregate the payload does not.
     *
     * <p>Kafka orders messages within a partition and nothing else, and the key selects the
     * partition. Every event this platform publishes is keyed by its account identifier, so all
     * events of one account land on one partition and stay in order. A producer that keyed an event
     * for account B under account A's key breaks that guarantee for both accounts at once: A's
     * partition now carries a message about B, and B's own messages can be reordered against it.
     * The read model is keyed by card, so the write would still land on the card's own rows while
     * every ordering assumption behind it had already failed.
     *
     * <p>The check runs ahead of the marker read and every write. A mismatch is not retryable --
     * a redelivery carries the same key -- so it raises {@link IllegalArgumentException}, which
     * {@code config/KafkaConsumerConfig} routes to the dead-letter topic of this source topic after
     * its attempts are spent.
     *
     * <p>A delivery carrying no key at all is refused for the same reason: an unkeyed record is
     * assigned a partition by the broker, so nothing keeps it ordered against the account's other
     * events.
     *
     * <p>The message names neither the key nor the aggregate. A key is producer-controlled, so it
     * can hold anything a payload can, and repeating it in a refusal would put it in a log line.
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

    /**
     * Reads the ten cardholder fields one account carries, refusing when the projection holds none.
     *
     * <p>{@code messaging/CustomerContextChangedConsumer} fills {@code cardholder_context} from the
     * account service, which owns the customer record, and {@code db/migration/V2__seed.sql}
     * bootstraps it for the fifty accounts of {@code app/data/ASCII/custdata.txt}. An account with no
     * row there once yielded blank fields, so the alert rendered with no name, no address and no
     * credit score, and nothing recorded that it had.
     * {@link CardholderContextReader#require(String)} reports the gap instead, which leaves the
     * offset uncommitted and has the delivery taken again rather than rendered empty.
     *
     * @param accountId the account the posted transaction belongs to
     * @return the cardholder fields the projection holds for the account
     */
    private CardholderDetails cardholderDetails(String accountId) {
        return cardholderContextReader.require(accountId);
    }

}
