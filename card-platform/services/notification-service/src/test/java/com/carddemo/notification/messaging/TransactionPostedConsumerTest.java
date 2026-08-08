package com.carddemo.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.CardholderContextReader;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.domain.NotificationService.CardholderDetails;
import com.carddemo.notification.entity.CardholderContextEntity;
import com.carddemo.notification.entity.NotificationLogEntity;
import com.carddemo.notification.entity.ProcessedEventEntity;
import com.carddemo.notification.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import com.carddemo.notification.repository.CardholderContextRepository;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds {@link TransactionPostedConsumer} to its delivery contract. One event writes one read-model
 * row once, and a repeat delivery of that event writes nothing. The offset commit lands after the
 * local transaction has closed.
 *
 * <p>Three parts of that contract are ADDITIVE: the duplicate guard, the masked Primary Account
 * Number the event carries, and the one local transaction the writes share.
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579} writes the
 * transaction record under no guard. That paragraph ends the run on any file status other than the
 * normal one, so a replayed feed meets a duplicate key there. The choices behind all three sit in
 * {@code card-platform/docs/decision-log.md}.
 *
 * <p>Each collaborator is a hand-written fake that records the order it was called in. The
 * transaction runner is a real {@link TransactionTemplate} over a fake manager, so the callback the
 * listener passes runs and its boundary reaches an assertion.
 */
@DisplayName("TransactionPostedConsumer, one event applied once inside one local transaction")
class TransactionPostedConsumerTest {

    /** The topic the shipped configuration resolves the listener's topic placeholder to. */
    private static final String TOPIC = "transaction.posted";

    /** The group the shipped configuration resolves the listener's group placeholder to. */
    private static final String GROUP = "notification-posted";

    /** The placeholder the topic attribute holds, keyed in {@code application.yml}. */
    private static final String TOPIC_PLACEHOLDER = "${carddemo.kafka.topics.transaction-posted}";

    /** The placeholder the group attribute holds, keyed in {@code application.yml}. */
    private static final String GROUP_PLACEHOLDER = "${carddemo.kafka.groups.transaction-posted}";

    /** The account each event names, from {@code XREF-ACCT-ID PIC 9(11)}. */
    private static final String ACCOUNT_ID = "00000000007";

    /** Another account, which one delivery names in its key alone. */
    private static final String OTHER_ACCOUNT_ID = "00000000011";

    /** The transaction one event reports, from {@code TRNX-ID PIC X(16)}. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** A card token of the shape the derivation produces: sixty-four lower-case hex characters. */
    private static final String CARD_TOKEN =
            "4824915b44bde613503579ba7f770e44b0ece5adaede366c22ba1e4966efde3d";

    /** The masked card number one event carries: twelve mask characters, then four digits. */
    private static final String MASKED_CARD = "************7065";

    /** The transaction type code, from {@code TRNX-TYPE-CD PIC X(02)}. */
    private static final String TYPE_CODE = "01";

    /** The merchant category code, from {@code TRNX-CAT-CD PIC 9(04)}. */
    private static final String CATEGORY_CODE = "0001";

    /** Where the transaction entered the platform, from {@code TRNX-SOURCE PIC X(10)}. */
    private static final String SOURCE = "POS TERM";

    /** Text describing the transaction, from {@code TRNX-DESC PIC X(100)}. */
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";

    /** The merchant identifier, from {@code TRNX-MERCHANT-ID PIC 9(09)}. */
    private static final String MERCHANT_ID = "800000000";

    /** The merchant name, from {@code TRNX-MERCHANT-NAME PIC X(50)}. */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /** The merchant city, from {@code TRNX-MERCHANT-CITY PIC X(50)}. */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /** The merchant mail code, from {@code TRNX-MERCHANT-ZIP PIC X(10)}. */
    private static final String MERCHANT_ZIP = "72112";

    /** A synthetic amount at the scale {@code TRAN-AMT PIC S9(09)V99} declares. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** A synthetic balance at the scale {@code ACCT-CURR-BAL PIC S9(10)V99} declares. */
    private static final BigDecimal NEW_BALANCE = new BigDecimal("697.77");

    /**
     * When the transaction originated, from {@code TRAN-ORIG-TS PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L16}: a space at position 11, colons at 14 and 17.
     */
    private static final String ORIGIN_TIMESTAMP = "2022-07-19 23:16:01.470000";

    /**
     * When the platform recorded it, from {@code TRAN-PROC-TS PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L17}: a dash at position 11, dots at 14 and 17, then two
     * hundredths digits and four zero characters, which {@code app/cbl/CBTRN02C.cbl:L173} and
     * {@code app/cbl/CBTRN02C.cbl:L701} fix.
     */
    private static final String POSTED_AT = "2022-07-19-23.16.01.470000";

    /** One-based positions the separators of both twenty-six character layouts occupy. */
    private static final int DAY_HOUR_SEPARATOR = 11;
    private static final int HOUR_MINUTE_SEPARATOR = 14;
    private static final int MINUTE_SECOND_SEPARATOR = 17;
    private static final int FRACTION_SEPARATOR = 20;

    /**
     * Columns the read model holds: the twelve source fields of
     * {@code app/cpy/COSTM01.CPY:L23-L35} that carry no card number, plus the two representations
     * that stand in for {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22} — the
     * card token that keys a row, and the masked display value beside it. No column and no event
     * property carries a full card number.
     */
    private static final int COLUMN_COUNT = 14;

    /** Fields one failure reports, from {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy}. */
    private static final int ABEND_FIELD_COUNT = 4;

    /** A digit run this wide names a card or an account, and reaches no diagnostic. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{12,}");

    /** Simple name of the annotation that binds one method to a topic and a group. */
    private static final String LISTENER_ANNOTATION = "KafkaListener";

    /** The eight calls the fakes record, which an assertion then reads in order. */
    private static final String TRANSACTION_BEGUN = "transaction begun";
    private static final String MARKER_CLAIMED = "marker claimed";
    private static final String ROW_READ = "row read";
    private static final String ROW_WRITTEN = "row written";
    private static final String ALERT_RENDERED = "alert rendered";
    private static final String TRANSACTION_COMMITTED = "transaction committed";
    private static final String TRANSACTION_ROLLED_BACK = "transaction rolled back";
    private static final String OFFSET_COMMITTED = "offset committed";

    /** Every call the listener made, in the order it made them. */
    private final List<String> sequence = new ArrayList<>();

    /** Commits the offset, and is the one Kafka type this test names. */
    private final Acknowledgment acknowledgment = () -> this.sequence.add(OFFSET_COMMITTED);

    /** Store of read-model rows. */
    private FakeStatementTransactions statementTransactions;

    /** Store of duplicate-delivery markers. */
    private FakeProcessedEvents processedEvents;

    /** Renderer of the cardholder alert. */
    private FakeNotificationService notificationService;

    /** Opener of the one local transaction the listener writes in. */
    private FakeTransactionManager transactionManager;

    /** The meters {@code config/ObservabilityConfig} registers, over a registry held in memory. */
    private NotificationMetrics metrics;

    /** Every diagnostic line the listener wrote during one test. */
    private ListAppender<ILoggingEvent> logRecords;

    /** The logger the listener writes to. */
    private ch.qos.logback.classic.Logger listenerLogger;

    /** The level that logger carried before this test lowered it. */
    private Level originalLevel;

    /** The listener under test. */
    private TransactionPostedConsumer consumer;

    @BeforeEach
    void buildListenerOverFakes() {
        this.statementTransactions = new FakeStatementTransactions(this.sequence);
        this.processedEvents = new FakeProcessedEvents(this.sequence);
        this.metrics = new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry());
        this.notificationService = new FakeNotificationService(this.sequence,
                this.statementTransactions, this.metrics);
        this.transactionManager = new FakeTransactionManager(this.sequence);
        this.consumer = new TransactionPostedConsumer(this.statementTransactions,
                this.processedEvents,
                new CardholderContextReader(new FakeCardholderContexts(heldContext())),
                this.notificationService, new TransactionTemplate(this.transactionManager),
                this.metrics);

        this.listenerLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(TransactionPostedConsumer.class);
        this.originalLevel = this.listenerLogger.getLevel();
        this.listenerLogger.setLevel(Level.DEBUG);
        this.logRecords = new ListAppender<>();
        this.logRecords.start();
        this.listenerLogger.addAppender(this.logRecords);
    }

    @AfterEach
    void releaseTheLogger() {
        this.listenerLogger.detachAppender(this.logRecords);
        this.logRecords.stop();
        this.listenerLogger.setLevel(this.originalLevel);
    }

    /** A resolved topic or group name in either attribute is the defect this test reports. */
    @Test
    @DisplayName("The listener names two property placeholders and no resolved topic or group")
    void theListenerNamesTwoPropertyPlaceholders() throws ReflectiveOperationException {
        Annotation binding = listenerBinding(soleListenerMethod());

        assertThat((String[]) attributeOf(binding, "topics")).containsExactly(TOPIC_PLACEHOLDER)
                .doesNotContain(TOPIC);
        assertThat((String) attributeOf(binding, "groupId")).isEqualTo(GROUP_PLACEHOLDER)
                .isNotEqualTo(GROUP);
    }

    /** One bound method, taking one event and one acknowledgement and returning nothing. */
    @Test
    @DisplayName("One method carries the binding, returns nothing, and takes one record at a time")
    void oneMethodCarriesTheBinding() {
        Method listener = soleListenerMethod();

        assertThat(Modifier.isPublic(listener.getModifiers())).isTrue();
        assertThat(listener.getReturnType()).isEqualTo(void.class);
        assertThat(listener.getParameterTypes()[0]).isEqualTo(TransactionPosted.class);
        assertThat(listener.getParameterTypes()).contains(Acknowledgment.class);
        for (Class<?> parameter : listener.getParameterTypes()) {
            assertThat(Iterable.class.isAssignableFrom(parameter))
                    .as("one delivery carries one record").isFalse();
        }
    }

    /**
     * The alert renders over the card token, the masked number and the projected cardholder, where
     * {@code app/jcl/CREASTMT.JCL:L61} copied a whole sorted file into the keyed cluster once.
     */
    @Test
    @DisplayName("One delivery claims the event, stores one row, and renders one alert")
    void oneDeliveryClaimsStoresAndRenders() {
        TransactionPosted event = postedEvent();

        deliver(event);

        assertThat(this.processedEvents.claimedEvents()).containsExactly(event.eventId());
        assertThat(this.statementTransactions.storedRows()).hasSize(1);
        assertThat(this.notificationService.alerts())
                .containsExactly(List.of(CARD_TOKEN, MASKED_CARD, TRANSACTION_ID, ACCOUNT_ID,
                        NEW_BALANCE, expectedCardholder(), RenderedFormat.PLAIN_TEXT));
        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_TRANSACTION_POSTED)
                .count()).isEqualTo(1);
    }

    /**
     * The claim, the row and the alert run inside the unit the template opens. The three writes at
     * {@code app/cbl/CBTRN02C.cbl:L440-L442} ran under no such unit.
     */
    @Test
    @DisplayName("The claim, the row and the alert commit as one unit, and the offset follows it")
    void theClaimTheRowAndTheAlertCommitAsOneUnit() {
        deliver(postedEvent());

        assertThat(this.sequence).containsExactly(TRANSACTION_BEGUN, MARKER_CLAIMED, ROW_READ,
                ROW_WRITTEN, ALERT_RENDERED, TRANSACTION_COMMITTED, OFFSET_COMMITTED);
        assertThat(insideTheUnit()).as("the callback the listener handed the template ran")
                .containsExactly(MARKER_CLAIMED, ROW_READ, ROW_WRITTEN, ALERT_RENDERED)
                .doesNotContain(OFFSET_COMMITTED);
        assertThat(this.transactionManager.unitsBegun()).isEqualTo(1);
        assertThat(this.transactionManager.unitsCommitted()).isEqualTo(1);
        assertThat(this.sequence.indexOf(OFFSET_COMMITTED))
                .isGreaterThan(this.sequence.indexOf(TRANSACTION_COMMITTED));
    }

    /**
     * A repeat delivery leaves the first row and alert standing alone, where the unguarded write at
     * {@code app/cbl/CBTRN02C.cbl:L562-L579} met a duplicate key and ended the run.
     */
    @Test
    @DisplayName("A repeat delivery of one event identifier stores no row and renders no alert")
    void aRepeatDeliveryStoresNoRowAndRendersNoAlert() {
        TransactionPosted event = postedEvent();
        deliver(event);
        this.sequence.clear();

        deliver(event);

        assertThat(this.sequence).containsExactly(TRANSACTION_BEGUN, MARKER_CLAIMED,
                TRANSACTION_COMMITTED, OFFSET_COMMITTED);
        assertThat(this.statementTransactions.storedRows()).hasSize(1);
        assertThat(this.notificationService.alerts()).hasSize(1);
        assertThat(this.sequence).containsOnlyOnce(OFFSET_COMMITTED);
    }

    /** A repeat delivery counts as a duplicate and writes one diagnostic line at debug. */
    @Test
    @DisplayName("A repeat delivery counts as a duplicate and raises no failure")
    void aRepeatDeliveryRaisesNoFailure() {
        TransactionPosted event = postedEvent();
        deliver(event);
        this.logRecords.list.clear();

        deliver(event);

        assertThat(this.metrics.duplicatesSkipped().count()).isEqualTo(1);
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_PERSISTENCE).count()).isZero();
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_RENDERING).count()).isZero();
        assertThat(eventsFrom(Level.WARN)).isEmpty();
        assertThat(eventsAt(Level.DEBUG)).hasSize(1);
    }

    /** Two deliveries of one event cannot both find the marker absent. */
    @Test
    @DisplayName("The guard is the claim statement alone, with no marker read and no marker write")
    void theGuardIsTheClaimStatementAlone() {
        TransactionPosted event = postedEvent();

        deliver(event);
        deliver(event);

        assertThat(this.processedEvents.claimCalls()).isEqualTo(2);
        assertThat(this.processedEvents.existenceReads()).as("existsById is never called").isZero();
        assertThat(this.processedEvents.markerWrites()).isZero();
        assertThat(this.processedEvents.recordedTopics()).containsOnly(TOPIC);
    }

    /**
     * The key order is the one {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at
     * {@code app/jcl/CREASTMT.JCL:L53} produced, under the 32-byte key of
     * {@code app/jcl/CREASTMT.JCL:L30}.
     */
    @Test
    @DisplayName("The row key holds the card token, then the transaction identifier")
    void theRowKeyHoldsTheCardTokenThenTheTransactionIdentifier() {
        deliver(postedEvent());

        StatementTransactionId key = storedRow().getId();
        assertThat(key.getCardToken()).isEqualTo(CARD_TOKEN).hasSize(PanMasker.CARD_TOKEN_LENGTH);
        assertThat(key.getTransactionId()).isEqualTo(TRANSACTION_ID)
                .hasSize(PicClause.TRAN_ID_WIDTH);
        assertThat(key.getCardToken()).isNotEqualTo(MASKED_CARD);
    }

    /**
     * Every column carries a value the event supplied: the twelve source fields that carry no card
     * number, and the card token and masked display value that stand in for
     * {@code TRNX-CARD-NUM PIC X(16)}.
     */
    @Test
    @DisplayName("All fourteen columns carry a value the event supplied, and none is null")
    void allFourteenColumnsCarryAValueTheEventSupplied() {
        deliver(postedEvent());

        StatementTransactionEntity row = storedRow();
        assertThat(columnValues(row)).hasSize(COLUMN_COUNT).doesNotContainNull();
        assertThat(row.getMaskedCardNumber()).isEqualTo(MASKED_CARD);
        assertThat(row.getTypeCode()).isEqualTo(padded(TYPE_CODE, PicClause.TRAN_TYPE_CD_WIDTH));
        assertThat(row.getCategoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(row.getSource()).isEqualTo(padded(SOURCE, PicClause.TRAN_SOURCE_WIDTH));
        assertThat(row.getDescription()).isEqualTo(padded(DESCRIPTION, PicClause.TRAN_DESC_WIDTH));
        assertThat(row.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(row.getMerchantName())
                .isEqualTo(padded(MERCHANT_NAME, PicClause.TRAN_MERCHANT_NAME_WIDTH));
        assertThat(row.getMerchantCity())
                .isEqualTo(padded(MERCHANT_CITY, PicClause.TRAN_MERCHANT_CITY_WIDTH));
        assertThat(row.getMerchantZip())
                .isEqualTo(padded(MERCHANT_ZIP, PicClause.TRAN_MERCHANT_ZIP_WIDTH));
        assertThat(row.getOriginTimestamp()).isEqualTo(ORIGIN_TIMESTAMP);
        assertThat(row.getProcessingTimestamp()).isEqualTo(POSTED_AT);
    }

    /**
     * Each column holds the width the {@code PIC X(n)} field of
     * {@code app/cpy/COSTM01.CPY:L22-L35} declares, which is the state a {@code MOVE} left.
     */
    @Test
    @DisplayName("Each text column reaches the width its source field declares")
    void eachTextColumnReachesTheWidthItsSourceFieldDeclares() {
        deliver(postedEvent());

        StatementTransactionEntity row = storedRow();
        assertThat(row.getTypeCode()).hasSize(PicClause.TRAN_TYPE_CD_WIDTH);
        assertThat(row.getCategoryCode()).hasSize(PicClause.TRAN_CAT_CD_WIDTH);
        assertThat(row.getSource()).hasSize(PicClause.TRAN_SOURCE_WIDTH);
        assertThat(row.getDescription()).hasSize(PicClause.TRAN_DESC_WIDTH);
        assertThat(row.getMerchantId()).hasSize(PicClause.TRAN_MERCHANT_ID_WIDTH);
        assertThat(row.getMerchantName()).hasSize(PicClause.TRAN_MERCHANT_NAME_WIDTH);
        assertThat(row.getMerchantCity()).hasSize(PicClause.TRAN_MERCHANT_CITY_WIDTH);
        assertThat(row.getMerchantZip()).hasSize(PicClause.TRAN_MERCHANT_ZIP_WIDTH);
    }

    /** {@code app/cpy/COSTM01.CPY} declares no account identifier field and no balance field. */
    @Test
    @DisplayName("The account identifier and the balance reach no column of the row")
    void theAccountIdentifierAndTheBalanceReachNoColumn() {
        deliver(postedEvent());

        assertThat(columnValues(storedRow())).doesNotContain(ACCOUNT_ID, NEW_BALANCE)
                .noneMatch(value -> value.toString().contains(ACCOUNT_ID))
                .noneMatch(value -> value.toString().contains(NEW_BALANCE.toPlainString()));
        assertThat(mappedFieldNames()).noneMatch(name -> name.contains("account"))
                .noneMatch(name -> name.contains("balance"));
    }

    /**
     * Both hold twenty-six characters, from {@code app/cpy/CVTRA05Y.cpy:L16-L17}, at the hundredths
     * precision {@code app/cbl/CBTRN02C.cbl:L173} and {@code app/cbl/CBTRN02C.cbl:L701} fix. One
     * instant in both layouts differs at three positions, and position 20 is a dot in each.
     */
    @Test
    @DisplayName("Both timestamps hold twenty-six characters and differ at three positions")
    void bothTimestampsHoldTwentySixCharactersAndDifferAtThreePositions() {
        deliver(postedEvent());

        StatementTransactionEntity row = storedRow();
        assertThat(row.getOriginTimestamp()).hasSize(TransactionPosted.ORIGIN_TIMESTAMP_LENGTH);
        assertThat(row.getProcessingTimestamp()).hasSize(TransactionPosted.POSTED_AT_LENGTH);
        assertThat(differingPositions(row.getOriginTimestamp(), row.getProcessingTimestamp()))
                .containsExactly(DAY_HOUR_SEPARATOR, HOUR_MINUTE_SEPARATOR,
                        MINUTE_SECOND_SEPARATOR);
        assertThat(characterAt(row.getOriginTimestamp(), FRACTION_SEPARATOR)).isEqualTo('.');
        assertThat(characterAt(row.getProcessingTimestamp(), FRACTION_SEPARATOR)).isEqualTo('.');
    }

    /**
     * The listener copies the amount and computes no new value from it. The scale is the one
     * {@code TRAN-AMT PIC S9(09)V99} declares at {@code app/cpy/CVTRA05Y.cpy:L10}.
     */
    @Test
    @DisplayName("The stored amount equals the amount the event carried, at the same scale")
    void theStoredAmountEqualsTheAmountTheEventCarried() {
        deliver(postedEvent());

        assertThat(storedRow().getAmount()).isEqualTo(AMOUNT);
        assertThat(storedRow().getAmount().scale()).isEqualTo(PicClause.TRAN_AMT_SCALE);
    }

    /**
     * Two events naming one transaction share one read-model key, the 32-byte key of
     * {@code app/jcl/CREASTMT.JCL:L30}.
     */
    @Test
    @DisplayName("A key the read model already holds takes new values and gains no second row")
    void aKeyTheReadModelAlreadyHoldsGainsNoSecondRow() {
        deliver(postedEvent());
        assertThat(this.statementTransactions.rowCount()).isEqualTo(1);

        deliver(postedEvent());

        assertThat(this.statementTransactions.writeCalls()).isEqualTo(2);
        assertThat(this.statementTransactions.rowCount()).isEqualTo(1);
        assertThat(this.sequence).contains(ROW_READ);
    }

    /**
     * The key is the account identifier of {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}, carried as text so its leading zeros survive.
     */
    @Test
    @DisplayName("The message key is the eleven-digit account identifier, leading zeros kept")
    void theMessageKeyIsTheElevenDigitAccountIdentifier() {
        TransactionPosted event = postedEvent();

        deliver(event);

        assertThat(event.aggregateId()).isEqualTo(ACCOUNT_ID)
                .hasSize(PicClause.XREF_ACCT_ID_WIDTH)
                .matches(EventEnvelope.AGGREGATE_ID_PATTERN).startsWith("0");
        assertThat(this.statementTransactions.storedRows()).hasSize(1);
    }

    /** The key check runs ahead of the claim and every write, and inside the measured block. */
    @Test
    @DisplayName("A key naming another aggregate is refused, and nothing is written")
    void aKeyNamingAnotherAggregateIsRefused() {
        TransactionPosted event = postedEvent();

        assertThatThrownBy(() -> this.consumer.onTransactionPosted(event, this.acknowledgment,
                TOPIC, OTHER_ACCOUNT_ID)).isInstanceOf(IllegalArgumentException.class);

        assertThat(this.sequence).isEmpty();
        assertThat(this.statementTransactions.storedRows()).isEmpty();
        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_TRANSACTION_POSTED).count())
                .as("the delivery reached this listener, so it is counted as read even though it "
                        + "was refused; this record is the one that travels to the dead-letter topic")
                .isEqualTo(1.0d);
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_RENDERING).count())
                .as("the refusal is counted as a failed attempt of this listener")
                .isEqualTo(1.0d);
        assertThat(this.metrics.processingLatency(NotificationMetrics.EVENT_TRANSACTION_POSTED)
                .count())
                .as("the refusal is timed like every other delivery")
                .isEqualTo(1L);
    }

    /** An unkeyed record is ordered against none of its account's other events. */
    @Test
    @DisplayName("A delivery carrying no key is refused, and nothing is written")
    void aDeliveryCarryingNoKeyIsRefused() {
        TransactionPosted event = postedEvent();

        assertThatThrownBy(() -> this.consumer.onTransactionPosted(event, this.acknowledgment,
                TOPIC, null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(this.sequence).isEmpty();
        assertThat(this.processedEvents.claimCalls()).isZero();
        assertThat(this.metrics.eventsConsumed(NotificationMetrics.EVENT_TRANSACTION_POSTED).count())
                .as("an unkeyed delivery is still a delivery this listener read")
                .isEqualTo(1.0d);
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_RENDERING).count())
                .isEqualTo(1.0d);
    }

    /**
     * An uncommitted offset has the delivery taken again, which the claim keeps harmless. The abend
     * at {@code app/cbl/CBTRN02C.cbl:L707-L711} ended the run at the same point.
     */
    @Test
    @DisplayName("A refused write rolls the unit back and leaves the offset uncommitted")
    void aRefusedWriteRollsTheUnitBackAndLeavesTheOffsetUncommitted() {
        this.statementTransactions.refuseWriteWith(
                new DataAccessResourceFailureException("the read-model write was refused"));

        assertThatThrownBy(() -> deliver(postedEvent())).isInstanceOf(DataAccessException.class);

        assertThat(this.sequence).containsExactly(TRANSACTION_BEGUN, MARKER_CLAIMED, ROW_READ,
                ROW_WRITTEN, TRANSACTION_ROLLED_BACK);
        assertThat(this.sequence).doesNotContain(OFFSET_COMMITTED);
        assertThat(this.transactionManager.unitsRolledBack()).isEqualTo(1);
        assertThat(this.metrics.failures(NotificationMetrics.FAILURE_PERSISTENCE).count())
                .isEqualTo(1);
    }

    /**
     * One failure reports the four fields of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L28}, and a digit run in the fault text reaches none of them.
     */
    @Test
    @DisplayName("One failure reports four fields, none carrying a run of twelve digits")
    void oneFailureReportsFourFieldsWithNoLongDigitRun() {
        String sixteenDigits = "9876543210" + "987654";
        this.statementTransactions.refuseWriteWith(new DataAccessResourceFailureException(
                "the read-model write was refused under " + sixteenDigits));

        assertThatThrownBy(() -> deliver(postedEvent())).isInstanceOf(DataAccessException.class);

        List<ILoggingEvent> failures = eventsFrom(Level.WARN);
        assertThat(failures).hasSize(1);
        assertThat(reportedFields(failures.get(0))).hasSize(ABEND_FIELD_COUNT)
                .noneMatch(value -> LONG_DIGIT_RUN.matcher(value).find())
                .noneMatch(value -> value.contains(sixteenDigits));
    }

    /** Six collaborators, none of them a publisher and none a client of a sibling service. */
    @Test
    @DisplayName("The listener holds six collaborators, none a publisher and none a client")
    void theListenerHoldsSixCollaborators() {
        List<Class<?>> collaborators = collaboratorTypes();

        assertThat(collaborators).containsExactlyInAnyOrder(StatementTransactionRepository.class,
                ProcessedEventRepository.class, CardholderContextReader.class,
                NotificationService.class, TransactionTemplate.class, NotificationMetrics.class);
        assertThat(collaborators).allMatch(type -> type.getPackageName()
                .startsWith("com.carddemo.notification")
                || type.getPackageName().startsWith("org.springframework.transaction"));
    }

    /** Hands one delivery to the listener under the account identifier its payload names. */
    private void deliver(TransactionPosted event) {
        this.consumer.onTransactionPosted(event, this.acknowledgment, TOPIC, event.aggregateId());
    }

    private static TransactionPosted postedEvent() {
        EventEnvelope envelope = EventEnvelope.of(TransactionPosted.EVENT_TYPE, ACCOUNT_ID,
                TransactionPosted.TRANSACTION_DETAIL_SCHEMA_VERSION);

        return TransactionPosted.of(envelope, TRANSACTION_ID, NEW_BALANCE, POSTED_AT, AMOUNT,
                MASKED_CARD, CARD_TOKEN, TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION,
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, ORIGIN_TIMESTAMP);
    }

    private StatementTransactionEntity storedRow() {
        assertThat(this.statementTransactions.storedRows()).isNotEmpty();
        return this.statementTransactions.storedRows().get(0);
    }

    /** Lists the fourteen column values of one row, key parts first, nulls kept. */
    private static List<Object> columnValues(StatementTransactionEntity row) {
        return Arrays.asList(row.getId().getCardToken(), row.getId().getTransactionId(),
                row.getMaskedCardNumber(), row.getTypeCode(), row.getCategoryCode(),
                row.getSource(), row.getDescription(), row.getAmount(), row.getMerchantId(),
                row.getMerchantName(), row.getMerchantCity(), row.getMerchantZip(),
                row.getOriginTimestamp(), row.getProcessingTimestamp());
    }

    /** Lists the mapped field names of the read-model entity and its key, folded to lower case. */
    private static List<String> mappedFieldNames() {
        List<String> names = new ArrayList<>();
        for (Class<?> mapped : List.of(StatementTransactionEntity.class,
                StatementTransactionId.class)) {
            for (Field field : mapped.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    names.add(field.getName().toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    /** Lists the types of the collaborator fields the listener holds. */
    private static List<Class<?>> collaboratorTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (Field field : TransactionPostedConsumer.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                types.add(field.getType());
            }
        }
        return types;
    }

    /** Finds the one method the listener binds to a topic. */
    private static Method soleListenerMethod() {
        List<Method> bound = new ArrayList<>();
        for (Method candidate : TransactionPostedConsumer.class.getDeclaredMethods()) {
            if (bindingOrNull(candidate) != null) {
                bound.add(candidate);
            }
        }
        assertThat(bound).hasSize(1);
        return bound.get(0);
    }

    private static Annotation listenerBinding(Method listener) {
        Annotation binding = bindingOrNull(listener);
        assertThat(binding).isNotNull();
        return binding;
    }

    private static Annotation bindingOrNull(Method candidate) {
        for (Annotation annotation : candidate.getAnnotations()) {
            if (LISTENER_ANNOTATION.equals(annotation.annotationType().getSimpleName())) {
                return annotation;
            }
        }
        return null;
    }

    private static Object attributeOf(Annotation binding, String name)
            throws ReflectiveOperationException {
        return binding.annotationType().getMethod(name).invoke(binding);
    }

    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /** Lists the one-based positions at which two texts differ. */
    private static List<Integer> differingPositions(String left, String right) {
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < Math.min(left.length(), right.length()); index++) {
            if (left.charAt(index) != right.charAt(index)) {
                positions.add(index + 1);
            }
        }
        return positions;
    }

    private static char characterAt(String value, int position) {
        return value.charAt(position - 1);
    }

    /** Lists the calls recorded between the opening of the transaction and its commit. */
    private List<String> insideTheUnit() {
        return this.sequence.subList(this.sequence.indexOf(TRANSACTION_BEGUN) + 1,
                this.sequence.indexOf(TRANSACTION_COMMITTED));
    }

    /** Lists the diagnostic lines the listener wrote at one level. */
    private List<ILoggingEvent> eventsAt(Level level) {
        List<ILoggingEvent> matching = new ArrayList<>();
        for (ILoggingEvent line : this.logRecords.list) {
            if (line.getLevel() == level) {
                matching.add(line);
            }
        }
        return matching;
    }

    /** Lists the diagnostic lines the listener wrote at one level or above it. */
    private List<ILoggingEvent> eventsFrom(Level level) {
        List<ILoggingEvent> matching = new ArrayList<>();
        for (ILoggingEvent line : this.logRecords.list) {
            if (line.getLevel().isGreaterOrEqual(level)) {
                matching.add(line);
            }
        }
        return matching;
    }

    /** Lists the field values one diagnostic line reported beside its message. */
    private static List<String> reportedFields(ILoggingEvent failure) {
        List<String> values = new ArrayList<>();
        for (KeyValuePair pair : failure.getKeyValuePairs()) {
            values.add(String.valueOf(pair.value));
        }
        return values;
    }

    /** One populated row of the account-keyed cardholder projection. */
    private static CardholderContextEntity heldContext() {
        return new CardholderContextEntity(ACCOUNT_ID, "Immanuel", "Madeline", "Kessler",
                "618 Deshaun Route", "Apt. 802", "Altenwerthshire", "NC", "USA", "12546", "274",
                Instant.EPOCH, Instant.EPOCH);
    }

    /** The ten cardholder fields {@link #heldContext()} maps onto. */
    private static CardholderDetails expectedCardholder() {
        return new CardholderDetails("Immanuel", "Madeline", "Kessler", "618 Deshaun Route",
                "Apt. 802", "Altenwerthshire", "NC", "USA", "12546", "274");
    }

    /** The refusal every operation the listener leaves alone raises. */
    private static UnsupportedOperationException unused() {
        return new UnsupportedOperationException("the listener under test calls no such operation");
    }

    /** Store of read-model rows, recording each call and able to refuse one write. */
    private static final class FakeStatementTransactions implements StatementTransactionRepository {

        private final List<String> sequence;
        private final Map<StatementTransactionId, StatementTransactionEntity> rows =
                new LinkedHashMap<>();
        private final List<StatementTransactionEntity> stored = new ArrayList<>();
        private RuntimeException writeFault;
        private int writeCalls;

        FakeStatementTransactions(List<String> sequence) {
            this.sequence = sequence;
        }

        void refuseWriteWith(RuntimeException fault) {
            this.writeFault = fault;
        }

        List<StatementTransactionEntity> storedRows() { return this.stored; }

        int rowCount() { return this.rows.size(); }

        int writeCalls() { return this.writeCalls; }

        @Override
        public Optional<StatementTransactionEntity> findById(StatementTransactionId key) {
            this.sequence.add(ROW_READ);
            return Optional.ofNullable(this.rows.get(key));
        }

        @Override
        public <S extends StatementTransactionEntity> S save(S row) {
            this.sequence.add(ROW_WRITTEN);
            this.writeCalls++;
            if (this.writeFault != null) {
                throw this.writeFault;
            }
            this.rows.put(row.getId(), row);
            this.stored.add(row);
            return row;
        }

        // Operations the listener leaves alone.
        @Override public boolean existsById(StatementTransactionId key) { throw unused(); }
        @Override public List<StatementTransactionEntity> findAll() { throw unused(); }
        @Override public long count() { throw unused(); }
        @Override public void deleteById(StatementTransactionId key) { throw unused(); }
        @Override public void delete(StatementTransactionEntity row) { throw unused(); }
        @Override public void deleteAll() { throw unused(); }
        @Override public int deleteProcessedBefore(String horizon, int limit) { throw unused(); }

        @Override
        public List<StatementTransactionEntity> findByIdCardTokenOrderByIdTransactionIdAsc(
                String cardToken, Limit limit) { throw unused(); }

        @Override
        public <S extends StatementTransactionEntity> List<S> saveAll(Iterable<S> batch) {
            throw unused();
        }

        @Override
        public List<StatementTransactionEntity> findAllById(
                Iterable<StatementTransactionId> keys) { throw unused(); }

        @Override
        public void deleteAllById(Iterable<? extends StatementTransactionId> keys) {
            throw unused();
        }

        @Override
        public void deleteAll(Iterable<? extends StatementTransactionEntity> batch) {
            throw unused();
        }
    }

    /**
     * Store of duplicate-delivery markers, claiming one event identifier once per topic.
     *
     * <p>The claim is keyed on the identifier AND the topic, as {@code pk_processed_event} is after
     * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql}. A fake keyed on the
     * identifier alone would suppress a different event that happened to share one, which is the
     * defect the migration removed, and a test running against it would pass while the shipped
     * schema behaved differently.
     */
    private static final class FakeProcessedEvents implements ProcessedEventRepository {

        private final List<String> sequence;
        private final Set<ProcessedEventId> claimed = new LinkedHashSet<>();
        private final List<UUID> claimedEvents = new ArrayList<>();
        private final List<String> recordedTopics = new ArrayList<>();
        private int claimCalls;
        private int existenceReads;
        private int markerWrites;

        FakeProcessedEvents(List<String> sequence) {
            this.sequence = sequence;
        }

        List<UUID> claimedEvents() { return this.claimedEvents; }

        List<String> recordedTopics() { return this.recordedTopics; }

        int claimCalls() { return this.claimCalls; }

        int existenceReads() { return this.existenceReads; }

        int markerWrites() { return this.markerWrites; }

        @Override
        public int claimEvent(UUID eventId, Instant processedAt, String consumedTopic) {
            this.sequence.add(MARKER_CLAIMED);
            this.claimCalls++;
            this.recordedTopics.add(consumedTopic);
            if (!this.claimed.add(new ProcessedEventId(eventId, consumedTopic))) {
                return ProcessedEventRepository.ALREADY_CLAIMED;
            }
            this.claimedEvents.add(eventId);
            return 1;
        }

        @Override
        public boolean existsById(ProcessedEventId key) {
            this.existenceReads++;
            return this.claimed.contains(key);
        }

        @Override
        public <S extends ProcessedEventEntity> S save(S marker) {
            this.markerWrites++;
            return marker;
        }

        // Operations the listener leaves alone.
        @Override
        public Optional<ProcessedEventEntity> findById(ProcessedEventId key) { throw unused(); }
        @Override public List<ProcessedEventEntity> findAll() { throw unused(); }
        @Override public long count() { throw unused(); }
        @Override public void deleteById(ProcessedEventId key) { throw unused(); }
        @Override public void delete(ProcessedEventEntity marker) { throw unused(); }
        @Override public void deleteAll() { throw unused(); }

        @Override
        public int deleteMarkersProcessedBefore(Instant horizon, int limit) { throw unused(); }

        @Override
        public <S extends ProcessedEventEntity> List<S> saveAll(Iterable<S> batch) {
            throw unused();
        }

        @Override
        public List<ProcessedEventEntity> findAllById(Iterable<ProcessedEventId> keys) {
            throw unused();
        }

        @Override
        public void deleteAllById(Iterable<? extends ProcessedEventId> keys) { throw unused(); }

        @Override
        public void deleteAll(Iterable<? extends ProcessedEventEntity> batch) { throw unused(); }
    }

    /** Store of the account-keyed cardholder projection, holding one row. */
    private static final class FakeCardholderContexts implements CardholderContextRepository {

        private final CardholderContextEntity held;

        FakeCardholderContexts(CardholderContextEntity held) {
            this.held = held;
        }

        @Override
        public Optional<CardholderContextEntity> findById(String accountId) {
            return Optional.ofNullable(this.held);
        }

        @Override
        public int applyContextChange(String accountId, String firstName, String middleName,
                String lastName, String addressLine1, String addressLine2, String addressLine3,
                String stateCode, String countryCode, String zipCode, String ficoScore,
                Instant sourceOccurredAt, Instant observedAt) { throw unused(); }
    }

    /** Store of delivery-attempt rows, which the overridden alert operation never reaches. */
    private static final class FakeDeliveryAttempts implements NotificationLogRepository {

        @Override public NotificationLogEntity save(NotificationLogEntity attempt) {
            throw unused();
        }

        @Override public long count() { throw unused(); }
        @Override public int deleteRenderedBefore(Instant horizon, int limit) {
            throw unused();
        }
    }

    /** Renderer of the cardholder alert, recording the seven arguments each request carried. */
    private static final class FakeNotificationService extends NotificationService {

        private final List<String> sequence;
        private final List<List<Object>> alerts = new ArrayList<>();

        FakeNotificationService(List<String> sequence, StatementTransactionRepository rows,
                NotificationMetrics metrics) {
            super(List.of(), rows, new FakeDeliveryAttempts(), metrics);
            this.sequence = sequence;
        }

        List<List<Object>> alerts() { return this.alerts; }

        @Override
        public String renderPostedTransactionAlert(String cardToken, String cardNumber,
                String transactionId, String accountId, BigDecimal newBalance,
                CardholderDetails cardholder, RenderedFormat format) {
            this.sequence.add(ALERT_RENDERED);
            this.alerts.add(List.of(cardToken, cardNumber, transactionId, accountId, newBalance,
                    cardholder, format));
            return "";
        }
    }

    /** Opener of one transaction, recording each boundary the template crosses. */
    private static final class FakeTransactionManager implements PlatformTransactionManager {

        private final List<String> sequence;
        private int unitsBegun;
        private int unitsCommitted;
        private int unitsRolledBack;

        FakeTransactionManager(List<String> sequence) {
            this.sequence = sequence;
        }

        int unitsBegun() { return this.unitsBegun; }

        int unitsCommitted() { return this.unitsCommitted; }

        int unitsRolledBack() { return this.unitsRolledBack; }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            this.unitsBegun++;
            this.sequence.add(TRANSACTION_BEGUN);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            this.unitsCommitted++;
            this.sequence.add(TRANSACTION_COMMITTED);
        }

        @Override
        public void rollback(TransactionStatus status) {
            this.unitsRolledBack++;
            this.sequence.add(TRANSACTION_ROLLED_BACK);
        }
    }
}
