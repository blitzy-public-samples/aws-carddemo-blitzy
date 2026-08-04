package com.carddemo.notification.messaging;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
 * <p>One event carries six payload fields and one row holds thirteen columns, so eight columns take
 * a filled default. {@code INITIALIZE STATEMENT-LINES} at {@code app/cbl/CBSTM03A.CBL:L459} runs
 * ahead of every {@code MOVE} in that paragraph, so a field the program never fills stays spaces or
 * zeros and each line still occupies its declared width.
 * {@link #readModelRow(TransactionPosted)} names each column and the source line it maps.
 *
 * <p>The read-model key holds the masked card number. {@code app/cpy/COSTM01.CPY:L22} declares
 * {@code TRNX-CARD-NUM PIC X(16)}, which carried a full Primary Account Number (PAN). Each event of
 * this platform carries the masked form alone, so the primary key of {@code statement_transaction}
 * is the masked card number.
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

    /**
     * The character the origin layout carries between the day and the hour, where the posting
     * layout carries a dash.
     *
     * <p>All 300 records of {@code app/data/ASCII/dailytran.txt} carry a space at that position.
     */
    private static final char ORIGIN_DATE_SEPARATOR = ' ';

    /**
     * The character the origin layout carries between two time components, where the posting layout
     * carries a dot.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L703} moves that dot into all three positions of the posting
     * layout, and the origin layout of {@code app/data/ASCII/dailytran.txt} carries a colon in the
     * first two.
     */
    private static final char ORIGIN_TIME_SEPARATOR = ':';

    /** Reads and writes one row of the card-keyed read model. */
    private final StatementTransactionRepository statementTransactions;

    /** Answers whether an event identifier has been handled, and stores the marker. */
    private final ProcessedEventRepository processedEvents;

    /** Renders one cardholder alert and records the delivery attempt. */
    private final NotificationService notificationService;

    /** Runs the guard, the row, the alert and the marker inside one local transaction. */
    private final TransactionTemplate transactionTemplate;

    /** Counts events read and duplicates skipped, times one delivery, and counts a failure. */
    private final NotificationMetrics metrics;

    /**
     * Takes the two repositories, the domain service, the transaction runner and the meters.
     *
     * @param statementTransactions store of read-model rows
     * @param processedEvents       store of duplicate-delivery markers
     * @param notificationService   renderer of the cardholder alert and writer of the attempt row
     * @param transactionTemplate   runner of the one local transaction this listener opens
     * @param metrics               the meter holder {@code config/ObservabilityConfig} registers
     * @throws NullPointerException if any argument is null
     */
    public TransactionPostedConsumer(StatementTransactionRepository statementTransactions,
            ProcessedEventRepository processedEvents, NotificationService notificationService,
            TransactionTemplate transactionTemplate, NotificationMetrics metrics) {
        this.statementTransactions =
                Objects.requireNonNull(statementTransactions, "statementTransactions is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
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
     * <p>A marker written by a delivery running alongside this one collides on the primary key of
     * {@code processed_event}. That collision rolls this transaction back, counts one skipped
     * duplicate and acknowledges, so the other delivery's writes stand alone.
     *
     * @param event          the validated event this delivery carries
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @throws NullPointerException if {@code event} or {@code acknowledgment} is null
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.transaction-posted}",
            groupId = "${carddemo.kafka.groups.transaction-posted}")
    public void onTransactionPosted(TransactionPosted event, Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        metrics.eventsConsumed(NotificationMetrics.EVENT_TRANSACTION_POSTED).increment();
        long startedAt = System.nanoTime();
        try {
            StatementTransactionEntity row = readModelRow(event);
            transactionTemplate
                    .executeWithoutResult(status -> applyOneEvent(event, row, consumedTopic));
        } catch (DataIntegrityViolationException markerCollision) {
            metrics.duplicatesSkipped().increment();
            LOG.debug("Event {} gained a marker from a delivery running alongside this one, so this"
                    + " one wrote nothing.", event.eventId());
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
     * <p>The marker check runs first, and an event that already carries a marker leaves all three
     * tables untouched. The row follows, then the alert, then the marker insert. Each write joins
     * the transaction the caller opened, so the marker and the effects it guards commit together or
     * not at all.
     *
     * <p>The marker goes last in the source's own order: {@code PERFORM 6000-WRITE-TRANS} at
     * {@code app/cbl/CBSTM03A.CBL:L428} writes the row, and
     * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429} accounts for the
     * amount afterwards.
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
        if (processedEvents.existsById(eventId)) {
            metrics.duplicatesSkipped().increment();
            LOG.debug("Event {} carries a marker already, so this delivery writes nothing.",
                    eventId);
            return;
        }

        upsertReadModelRow(row);
        notificationService.renderPostedTransactionAlert(event.maskedCardNumber(),
                event.transactionId(), event.accountId(), event.newBalance(),
                CardholderDetails.blank(), RenderedFormat.PLAIN_TEXT);
        processedEvents.save(marker(eventId, consumedTopic));
    }

    /**
     * Writes one row of the read model under its composite key, whether or not that key is held.
     *
     * <p>The key is the 32-byte key of {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30}: the
     * card number, then the transaction identifier. One read holds those two parts in the order
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} produced,
     * which the primary key of {@code statement_transaction} keeps.
     *
     * <p>The entity carries no setter, so the eleven non-key values arrive on a fresh instance and
     * {@code save} carries them onto the stored row under the same key.
     *
     * @param row the row to store, carrying all thirteen column values
     */
    private void upsertReadModelRow(StatementTransactionEntity row) {
        String cardNumber = row.getId().getCardNumber();
        String transactionId = row.getId().getTransactionId();

        if (statementTransactions.findById(row.getId()).isPresent()) {
            LOG.debug("Card {} transaction {} holds a read-model row already, and this delivery"
                    + " replaces its eleven non-key values.", cardNumber, transactionId);
        } else {
            LOG.debug("Card {} transaction {} enters the read model.", cardNumber, transactionId);
        }
        statementTransactions.save(row);
    }

    /**
     * Maps one event onto the thirteen column values of one read-model row.
     *
     * <p>Four columns copy a payload field. {@code card_number} takes the masked card number of
     * {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}, and
     * {@code transaction_id} takes {@code TRNX-ID PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L23}. {@code amount} takes {@code TRNX-AMT PIC S9(09)V99} at
     * {@code app/cpy/COSTM01.CPY:L29} at scale {@value PicClause#TRAN_AMT_SCALE}, and
     * {@code processing_timestamp} takes {@code TRNX-PROC-TS PIC X(26)} at
     * {@code app/cpy/COSTM01.CPY:L35} character for character.
     *
     * <p>Eight columns take the filled state {@code INITIALIZE STATEMENT-LINES} at
     * {@code app/cbl/CBSTM03A.CBL:L459} leaves. Six carry spaces at their declared widths:
     * {@code type_code} from L25, {@code source} from L27, {@code description} from L28,
     * {@code merchant_name} from L31, {@code merchant_city} from L32 and {@code merchant_zip} from
     * L33 of {@code app/cpy/COSTM01.CPY}. Two carry zero digits: {@code category_code} from
     * {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26} and {@code merchant_id} from
     * {@code TRNX-MERCHANT-ID PIC 9(09)} at {@code app/cpy/COSTM01.CPY:L30}.
     *
     * <p>{@link #originTimestamp(String)} derives the thirteenth value, {@code origin_timestamp}
     * from {@code TRNX-ORIG-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L34}. The event's new
     * balance reaches no column, and {@link NotificationService} reads it for the balance the alert
     * carries.
     *
     * @param event the validated event this delivery carries
     * @return a row carrying all thirteen column values, none of them null
     * @throws IllegalArgumentException if the card number is not masked, if the transaction
     *                                  identifier is not {@value PicClause#TRAN_ID_WIDTH}
     *                                  characters, or if the posting timestamp is not
     *                                  {@value PicClause#PROCESSING_TIMESTAMP_WIDTH} characters
     */
    private static StatementTransactionEntity readModelRow(TransactionPosted event) {
        StatementTransactionEntity.StatementTransactionId identifier =
                new StatementTransactionEntity.StatementTransactionId(event.maskedCardNumber(),
                        event.transactionId());
        String processingTimestamp = event.postedAt();

        return new StatementTransactionEntity(identifier,
                spaces(PicClause.TRAN_TYPE_CD_WIDTH),
                zeroDigits(PicClause.TRAN_CAT_CD_WIDTH),
                spaces(PicClause.TRAN_SOURCE_WIDTH),
                spaces(PicClause.TRAN_DESC_WIDTH),
                CobolDecimal.truncateToScale(event.amount(), PicClause.TRAN_AMT_SCALE),
                zeroDigits(PicClause.TRAN_MERCHANT_ID_WIDTH),
                spaces(PicClause.TRAN_MERCHANT_NAME_WIDTH),
                spaces(PicClause.TRAN_MERCHANT_CITY_WIDTH),
                spaces(PicClause.TRAN_MERCHANT_ZIP_WIDTH),
                originTimestamp(processingTimestamp),
                processingTimestamp);
    }

    /**
     * Derives the origin timestamp from the posting timestamp by replacing three separators.
     *
     * <p>Both layouts hold {@value PicClause#PROCESSING_TIMESTAMP_WIDTH} characters. The posting
     * layout carries a dash ahead of the hour and a dot ahead of the minute and the second, moved
     * there by {@code app/cbl/CBTRN02C.cbl:L702} and {@code app/cbl/CBTRN02C.cbl:L703}. The origin
     * layout carries a space and two colons at those three positions, and every other character is
     * copied as it stands.
     *
     * <p>A census of all 300 records of {@code app/data/ASCII/dailytran.txt} reports a space, a
     * colon, a colon and a dot at the four separator positions of the origin timestamp. The dot
     * ahead of the fraction and the six fraction characters therefore need no change.
     *
     * @param postedAt the posting timestamp the event carries
     * @return the same moment in the origin layout, at the same width
     * @throws IllegalArgumentException if {@code postedAt} is null or holds another width
     */
    private static String originTimestamp(String postedAt) {
        if (postedAt == null || postedAt.length() != PicClause.PROCESSING_TIMESTAMP_WIDTH) {
            throw new IllegalArgumentException("/postedAt holds "
                    + (postedAt == null ? "no value" : postedAt.length() + " characters")
                    + " and this column holds " + PicClause.PROCESSING_TIMESTAMP_WIDTH);
        }

        char[] characters = postedAt.toCharArray();
        characters[separatorAhead(PicClause.PROCESSING_TIMESTAMP_HOUR_OFFSET)] =
                ORIGIN_DATE_SEPARATOR;
        characters[separatorAhead(PicClause.PROCESSING_TIMESTAMP_MINUTE_OFFSET)] =
                ORIGIN_TIME_SEPARATOR;
        characters[separatorAhead(PicClause.PROCESSING_TIMESTAMP_SECOND_OFFSET)] =
                ORIGIN_TIME_SEPARATOR;
        return new String(characters);
    }

    /**
     * The index of the separator that sits ahead of one timestamp component.
     *
     * @param componentOffset the index of the component, from {@link PicClause}
     * @return the index one separator width ahead of that component
     */
    private static int separatorAhead(int componentOffset) {
        return componentOffset - PicClause.PROCESSING_TIMESTAMP_SEPARATOR_WIDTH;
    }

    /**
     * A run of spaces at one declared width, the state a {@code PIC X(n)} field holds ahead of a
     * {@code MOVE}.
     *
     * @param width the declared width, from {@link PicClause}
     * @return exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return " ".repeat(width);
    }

    /**
     * A run of zero digits at one declared width, the state a numeric display field holds ahead
     * of a {@code MOVE}.
     *
     * <p>Both columns filled this way hold text under a digit check, so a zero-filled value keeps
     * every leading zero the source field carried.
     *
     * @param width the declared width, from {@link PicClause}
     * @return exactly {@code width} zero characters
     */
    private static String zeroDigits(int width) {
        return "0".repeat(width);
    }

    /**
     * Builds the duplicate-delivery marker for one handled event.
     *
     * @param eventId       the identifier the publishing service assigned, and the idempotency key
     * @param consumedTopic the topic the delivery arrived on, or null to record none
     * @return the marker to store
     */
    private static ProcessedEventEntity marker(UUID eventId, String consumedTopic) {
        ProcessedEventEntity marker = new ProcessedEventEntity(eventId, Instant.now());
        marker.setConsumedTopic(
                consumedTopic == null || consumedTopic.isBlank() ? null : consumedTopic);
        return marker;
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
     * <p>A data-access fault counts as a persistence failure and every other fault as a rendering
     * failure. {@code config/ObservabilityConfig} registers both series.
     *
     * @param failure the fault this delivery raised
     * @return the tag value the failure counter carries
     */
    private static String failureKind(RuntimeException failure) {
        return failure instanceof DataAccessException
                ? NotificationMetrics.FAILURE_PERSISTENCE
                : NotificationMetrics.FAILURE_RENDERING;
    }
}
