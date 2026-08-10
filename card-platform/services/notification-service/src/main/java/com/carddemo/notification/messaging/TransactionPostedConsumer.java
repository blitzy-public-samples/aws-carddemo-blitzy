package com.carddemo.notification.messaging;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.CardholderContextReader;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.domain.NotificationService.CardholderDetails;
import com.carddemo.notification.entity.ProcessedEventEntity;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * row and fill no row. This listener applies nothing for it, counts it on
 * {@code carddemo.notification.events.unapplied}, reports it once and acknowledges it.
 * {@link #carriesNoCardIdentity(TransactionPosted)} states why nothing is invented in its place, and
 * why the delivery is no longer refused: a version 1 event is governed and valid against its own
 * schema document, so refusing it spent three attempts and put a valid event on the dead-letter topic
 * as though it were poison. Consumer groups start at the earliest offset, so a group added to a topic
 * that retains version 1 records met that route on every one of them.
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

    /** Renders one cardholder alert and records that it was rendered; nothing sends it. */
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
     * <p>That check sits inside the measured block, and the delivery is counted before it. A refused
     * key is a delivery this service consumed and could not apply, and it travels through retry to the
     * dead-letter topic. Refusing it ahead of the meters left exactly that record absent from the
     * consumed counter, the failure counter and the latency timer, so the one delivery an operator
     * needs to see was the one the metrics endpoint did not report.
     *
     * @param event          the validated event this delivery carries
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @param messageKey     the key the delivery arrived under, which must name the payload's
     *                       aggregate
     * <p>A delivery at contract version 1 carries no card token and no descriptive field, so it keys
     * no read-model row and fills none. Such a delivery writes nothing, is counted on
     * {@code carddemo.notification.events.unapplied}, is reported once, and is acknowledged rather
     * than retried and dead-lettered.
     *
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

        metrics.eventsConsumed(NotificationMetrics.EVENT_TRANSACTION_POSTED).increment();
        long startedAt = System.nanoTime();
        try {
            requireKeyNamesPayloadAggregate(messageKey, event);
            if (carriesNoCardIdentity(event)) {
                recordUnappliedVersion(event.eventId(), event.schemaVersion());
            } else {
                StatementTransactionEntity row = readModelRow(event);
                transactionTemplate
                        .executeWithoutResult(status -> applyOneEvent(event, row, consumedTopic));
            }
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
        if (processedEvents.claimEvent(eventId, Instant.now(), consumedTopicOrSentinel(consumedTopic))
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
     * <p>One statement does it. This method used to read the key to choose between two diagnostic
     * lines and then call {@code save}, which for an entity carrying an assigned key is a merge and
     * issues a select of its own. That was three round trips for one event, two of which asked whether
     * the key was held, and the answer changed nothing but a debug message.
     * {@link StatementTransactionRepository#upsertRow} settles it in one, and the message below names
     * the transaction stored without claiming which of the two paths the database took.
     *
     * <p>The diagnostic names the transaction and not the card. The card token is derived from the
     * card number under a deployment key, so it is stable for one card across every request: a token
     * in a log line is a durable identifier for a cardholder even though it reveals no digit of the
     * number it stands for. The transaction identifier names one event instead.
     *
     * @param row the row to store, carrying all fourteen column values
     */
    private void upsertReadModelRow(StatementTransactionEntity row) {
        statementTransactions.upsertRow(
                row.getId().getCardToken(),
                row.getId().getTransactionId(),
                row.getMaskedCardNumber(),
                row.getTypeCode(),
                row.getCategoryCode(),
                row.getSource(),
                row.getDescription(),
                row.getAmount(),
                row.getMerchantId(),
                row.getMerchantName(),
                row.getMerchantCity(),
                row.getMerchantZip(),
                row.getOriginTimestamp(),
                row.getProcessingTimestamp());

        LOG.debug("Transaction {} is stored in the read model under its card and identifier.",
                row.getId().getTransactionId());
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
     * @throws IllegalArgumentException if the event carries no card token, which
     *                                  {@link #carriesNoCardIdentity(TransactionPosted)} settles
     *                                  before this method is reached, if the card number is not
     *                                  masked, if the
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
     * Reports whether this delivery carries a contract version that keys no read-model row.
     *
     * <p>Only schema version {@value TransactionPosted#TRANSACTION_DETAIL_SCHEMA_VERSION} carries a
     * token, and version 1 declares no such property. Two independent things are therefore missing
     * from a version 1 event, not one: the token the composite key holds, and nine of the fourteen
     * column values the row carries.
     *
     * <p><strong>Neither is invented.</strong> No token can be recovered from the masked card number
     * a version 1 event does carry, because masking discards twelve of the sixteen digits the
     * derivation reads, and two cards sharing their last four digits mask to one value. A row keyed on
     * a derived value would merge two cardholders' histories under a key no card resolves to, and a
     * row carrying nine blank columns would answer a history query with a transaction that reports
     * nothing about itself.
     *
     * @param event the validated event this delivery carries
     * @return {@code true} when the event carries no card token
     */
    private static boolean carriesNoCardIdentity(TransactionPosted event) {
        return event.cardToken() == null;
    }

    /**
     * Counts and reports one governed delivery this listener deliberately applied nothing for.
     *
     * <p>No marker is written. {@code processed_event} guards side effects and there are none to
     * guard, so a marker would assert that this event had been applied. A redelivery is counted here
     * again, which is truthful, because the delivery did happen again.
     *
     * <p>Reported at {@code WARN}, because a stream of these means a producer is publishing an older
     * contract version than this read model can be built from. The line names the event identifier and
     * the version, and no field of the payload.
     *
     * @param eventId       the identifier the ledger service assigned
     * @param schemaVersion the contract version the delivery reported
     */
    private void recordUnappliedVersion(UUID eventId, int schemaVersion) {
        metrics.eventsUnapplied(NotificationMetrics.EVENT_TRANSACTION_POSTED).increment();
        LOG.warn("Event {} reports contract version {} and carries no card token, so this service"
                + " writes no read-model row and renders nothing for it. Version {} carries the"
                + " token and the fourteen values one row holds.", eventId, schemaVersion,
                TransactionPosted.TRANSACTION_DETAIL_SCHEMA_VERSION);
    }

    /**
     * Returns the card token the event carries, refusing an event that carries none.
     *
     * <p>Reached only for a delivery {@link #carriesNoCardIdentity(TransactionPosted)} answered false
     * for, so the value is present. The check remains because this method's contract is a non-null
     * token, and a caller that stopped asking the question first would otherwise build a row with a
     * null key component.
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
     * Names the topic this delivery arrived on, for the half of the marker key that holds it.
     *
     * <p>The topic is half of {@code pk_processed_event}, so it holds no null. A delivery that
     * carried no topic header records {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}, which states
     * that absence in a value no real topic name can equal.</p>
     *
     * @param consumedTopic the topic the delivery arrived on, possibly null or blank
     * @return the topic, or {@link ProcessedEventEntity#NO_CONSUMED_TOPIC} when it named none
     */
    private static String consumedTopicOrSentinel(String consumedTopic) {
        return consumedTopic == null || consumedTopic.isBlank()
                ? ProcessedEventEntity.NO_CONSUMED_TOPIC
                : consumedTopic;
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
     * <p>The level is {@code WARN} because this line reports one attempt and a retry may still
     * succeed. The terminal outcome is reported once, at {@code ERROR}, by the recoverer in
     * {@code config/KafkaConsumerConfig} when the attempts are spent, and that line is the one an
     * alerting rule should watch. Reporting each attempt at {@code ERROR} put three of them on a
     * record that recovered on the third try, which made a transient fault indistinguishable from a
     * permanent one.
     *
     * @param eventId the identifier of the event that failed
     * @param failure the fault this delivery raised
     */
    private void reportFailure(UUID eventId, RuntimeException failure) {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(ABEND_CODE, CULPRIT,
                failure.getClass().getSimpleName(), NOTHING_WRITTEN);
        metrics.failures(failureKind(failure)).increment();

        LOG.atWarn()
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
