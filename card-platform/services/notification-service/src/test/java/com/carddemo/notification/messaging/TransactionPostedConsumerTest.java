package com.carddemo.notification.messaging;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.repository.CardholderContextRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consumer tests for {@link TransactionPostedConsumer}, the listener that turns one posted-transaction
 * event into one row of the read model behind {@code GET /notifications/{cardToken}}.
 *
 * <p>Two properties are the subject. Every column of the row carries a value the event supplied, so
 * no field of a later response reads as spaces or zeros: the nightly sort-then-copy of
 * {@code app/jcl/CREASTMT.JCL} produced complete records, and one event per transaction has to do the
 * same. And the row is keyed on the card token, because a masked card number identifies no single
 * card and would merge the histories of two cards sharing their last four digits.
 *
 * <p>Both timestamps are checked separately. {@code origin_timestamp} is the moment the transaction
 * originated and {@code processing_timestamp} the moment the platform recorded it. They are different
 * moments, and deriving one from the other would misstate when a cardholder's transaction happened.
 *
 * <p>Every collaborator is a stub or a mock, so no database and no broker takes part. The transaction
 * runner is real over a stub manager, so the callback the listener passes actually runs.
 */
@DisplayName("TransactionPostedConsumer, one event filling one complete read-model row")
final class TransactionPostedConsumerTest {

    /** A full card number, split so no sixteen-digit literal appears in one piece. */
    private static final String FULL_CARD = "4859452612877" + "065";

    /** The card token that identifies that card, and the card half of the row key. */
    private static final String CARD_TOKEN = PanMasker.cardToken(FULL_CARD);

    /** The masked form of that card, the display value the row carries beside its key. */
    private static final String MASKED_CARD = PanMasker.maskCardNumber(FULL_CARD);

    /** The transaction one event reports, at the width {@code TRNX-ID PIC X(16)} declares. */
    private static final String TRANSACTION_ID = "0000000000009101";

    /** The account the event keys on, at the width {@code ACCT-ID PIC 9(11)} declares. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The transaction type code, from {@code TRNX-TYPE-CD PIC X(02)}. */
    private static final String TYPE_CODE = "01";

    /** The transaction category code, from {@code TRNX-CAT-CD PIC 9(04)}. */
    private static final String CATEGORY_CODE = "0001";

    /** Where the transaction entered the platform, from {@code TRNX-SOURCE PIC X(10)}. */
    private static final String SOURCE = "POS TERM";

    /** Free text describing the transaction, from {@code TRNX-DESC PIC X(100)}. */
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";

    /** The merchant identifier, from {@code TRNX-MERCHANT-ID PIC 9(09)}. */
    private static final String MERCHANT_ID = "800000000";

    /** The merchant name, from {@code TRNX-MERCHANT-NAME PIC X(50)}. */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /** The merchant city, from {@code TRNX-MERCHANT-CITY PIC X(50)}. */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /** The merchant mail code, from {@code TRNX-MERCHANT-ZIP PIC X(10)}. */
    private static final String MERCHANT_ZIP = "72112";

    /** The transaction amount, at the scale {@code TRNX-AMT PIC S9(09)V99} declares. */
    private static final BigDecimal AMOUNT = new BigDecimal("194.00");

    /** The balance after posting, which reaches the alert and no column. */
    private static final BigDecimal NEW_BALANCE = new BigDecimal("1250.75");

    /**
     * When the transaction originated: a space between day and hour, colons between time components.
     *
     * <p>All 300 records of {@code app/data/ASCII/dailytran.txt} carry this shape.</p>
     */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * When the platform recorded the transaction: a dash between day and hour, dots between time
     * components, then two hundredths digits and four zero characters.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L173} fixes the two significant fractional digits and
     * {@code app/cbl/CBTRN02C.cbl:L701} fills the last four characters with zeros.</p>
     */
    private static final String POSTED_AT = "2022-07-19-23.16.01.470000";

    /** The topic one delivery arrived on, recorded on the marker. */
    private static final String TOPIC = "transaction.posted";

    /** Rows the claim statement writes when this delivery takes the event. */
    private static final int CLAIM_TAKEN = 1;

    /** Stores read-model rows. */
    private StatementTransactionRepository statementTransactions;

    /** Claims one event once, so a redelivery writes nothing. */
    private ProcessedEventRepository processedEvents;

    /** Holds the ten cardholder fields one alert reports, keyed by account. */
    private CardholderContextRepository cardholderContexts;

    /** Renders the alert. */
    private NotificationService notificationService;

    /** Commits the offset. */
    private Acknowledgment acknowledgment;

    /** The listener under test. */
    private TransactionPostedConsumer consumer;

    @BeforeEach
    void buildConsumer() {
        statementTransactions = mock(StatementTransactionRepository.class);
        processedEvents = mock(ProcessedEventRepository.class);
        when(processedEvents.claimEvent(any(), any(), any())).thenReturn(CLAIM_TAKEN);
        cardholderContexts = mock(CardholderContextRepository.class);
        when(cardholderContexts.findById(any())).thenReturn(Optional.empty());
        notificationService = mock(NotificationService.class);
        acknowledgment = mock(Acknowledgment.class);
        NotificationMetrics metrics =
                new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry());
        consumer = new TransactionPostedConsumer(statementTransactions, processedEvents,
                cardholderContexts, notificationService, transactionRunner(), metrics);
    }

    @Test
    @DisplayName("Every column of the row carries a value the event supplied")
    void everyColumnCarriesAValueTheEventSupplied() {
        consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC, ACCOUNT_ID);

        StatementTransactionEntity row = savedRow();
        assertThat(row.getTypeCode()).isEqualTo(atWidth(TYPE_CODE, PicClause.TRAN_TYPE_CD_WIDTH));
        assertThat(row.getCategoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(row.getSource()).isEqualTo(atWidth(SOURCE, PicClause.TRAN_SOURCE_WIDTH));
        assertThat(row.getDescription())
                .isEqualTo(atWidth(DESCRIPTION, PicClause.TRAN_DESC_WIDTH));
        assertThat(row.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(row.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(row.getMerchantName())
                .isEqualTo(atWidth(MERCHANT_NAME, PicClause.TRAN_MERCHANT_NAME_WIDTH));
        assertThat(row.getMerchantCity())
                .isEqualTo(atWidth(MERCHANT_CITY, PicClause.TRAN_MERCHANT_CITY_WIDTH));
        assertThat(row.getMerchantZip())
                .isEqualTo(atWidth(MERCHANT_ZIP, PicClause.TRAN_MERCHANT_ZIP_WIDTH));
    }

    @Test
    @DisplayName("No column reads as spaces or as zeros where the event carried a value")
    void noColumnReadsAsSpacesOrZeros() {
        consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC, ACCOUNT_ID);

        StatementTransactionEntity row = savedRow();
        assertThat(row.getTypeCode()).isNotBlank();
        assertThat(row.getSource()).isNotBlank();
        assertThat(row.getDescription()).isNotBlank();
        assertThat(row.getMerchantName()).isNotBlank();
        assertThat(row.getMerchantCity()).isNotBlank();
        assertThat(row.getMerchantZip()).isNotBlank();
        assertThat(row.getCategoryCode())
                .isNotEqualTo("0".repeat(PicClause.TRAN_CAT_CD_WIDTH));
        assertThat(row.getMerchantId())
                .isNotEqualTo("0".repeat(PicClause.TRAN_MERCHANT_ID_WIDTH));
    }

    @Test
    @DisplayName("Each text column reaches its declared width, as a MOVE into a PIC X(n) field does")
    void eachTextColumnReachesItsDeclaredWidth() {
        consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC, ACCOUNT_ID);

        StatementTransactionEntity row = savedRow();
        assertThat(row.getTypeCode()).hasSize(PicClause.TRAN_TYPE_CD_WIDTH);
        assertThat(row.getSource()).hasSize(PicClause.TRAN_SOURCE_WIDTH);
        assertThat(row.getDescription()).hasSize(PicClause.TRAN_DESC_WIDTH);
        assertThat(row.getMerchantName()).hasSize(PicClause.TRAN_MERCHANT_NAME_WIDTH);
        assertThat(row.getMerchantCity()).hasSize(PicClause.TRAN_MERCHANT_CITY_WIDTH);
        assertThat(row.getMerchantZip()).hasSize(PicClause.TRAN_MERCHANT_ZIP_WIDTH);
    }

    @Test
    @DisplayName("The row is keyed on the card token and displays the masked card number")
    void theRowIsKeyedOnTheCardTokenAndDisplaysTheMaskedCardNumber() {
        consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC, ACCOUNT_ID);

        StatementTransactionEntity row = savedRow();
        assertThat(row.getId().getCardToken()).isEqualTo(CARD_TOKEN);
        assertThat(row.getId().getTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(row.getMaskedCardNumber()).isEqualTo(MASKED_CARD);
        assertThat(row.getId().getCardToken())
                .as("no card number of either form reaches the key")
                .isNotEqualTo(MASKED_CARD)
                .isNotEqualTo(FULL_CARD)
                .doesNotContain(FULL_CARD.substring(0, 12));
    }

    @Test
    @DisplayName("The two timestamps are the moments the event reported, neither derived from the other")
    void theTwoTimestampsAreTheMomentsTheEventReported() {
        consumer.onTransactionPosted(postedEvent(), acknowledgment, TOPIC, ACCOUNT_ID);

        StatementTransactionEntity row = savedRow();
        assertThat(row.getOriginTimestamp()).isEqualTo(ORIGIN_TIMESTAMP);
        assertThat(row.getProcessingTimestamp()).isEqualTo(POSTED_AT);
        assertThat(row.getOriginTimestamp())
                .as("the origin moment is captured, not reshaped from the posting moment")
                .isNotEqualTo(row.getProcessingTimestamp());
    }

    @Test
    @DisplayName("The alert renders over the token, and the marker and the offset follow the row")
    void theAlertRendersOverTheTokenAndTheMarkerFollowsTheRow() {
        TransactionPosted event = postedEvent();

        consumer.onTransactionPosted(event, acknowledgment, TOPIC, ACCOUNT_ID);

        verify(notificationService).renderPostedTransactionAlert(CARD_TOKEN, MASKED_CARD,
                TRANSACTION_ID, ACCOUNT_ID, NEW_BALANCE, NotificationService.CardholderDetails
                        .blank(), RenderedFormat.PLAIN_TEXT);
        verify(processedEvents).claimEvent(eq(event.eventId()), any(), eq(TOPIC));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("An event carrying a marker already writes no row")
    void anEventCarryingAMarkerAlreadyWritesNoRow() {
        TransactionPosted event = postedEvent();
        when(processedEvents.claimEvent(eq(event.eventId()), any(), any()))
                .thenReturn(ProcessedEventRepository.ALREADY_CLAIMED);

        consumer.onTransactionPosted(event, acknowledgment, TOPIC, ACCOUNT_ID);

        verify(statementTransactions, never()).save(any());
        verify(notificationService, never()).renderPostedTransactionAlert(any(), any(), any(),
                any(), any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("An event at schema version one keys no row and is refused by name")
    void anEventAtSchemaVersionOneIsRefusedByName() {
        TransactionPosted withoutToken = TransactionPosted.forAccount(ACCOUNT_ID, TRANSACTION_ID,
                NEW_BALANCE, POSTED_AT, AMOUNT, MASKED_CARD);

        assertThatThrownBy(() -> consumer.onTransactionPosted(withoutToken, acknowledgment, TOPIC,
                ACCOUNT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/cardToken")
                .hasMessageContaining(
                        String.valueOf(TransactionPosted.TRANSACTION_DETAIL_SCHEMA_VERSION));

        verify(statementTransactions, never()).save(any());
        verify(processedEvents, never()).save(any());
        verify(acknowledgment, never()).acknowledge();
    }

    /**
     * Builds the enriched event a posting publishes, by way of the authorization event it follows.
     *
     * <p>{@code TransactionPosted.forAuthorized} copies the card token and the nine transaction
     * details forward, which is what lets one event fill a whole row.</p>
     *
     * @return the event one delivery carries
     */
    private static TransactionPosted postedEvent() {
        TransactionAuthorized authorized = TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID,
                TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION, AMOUNT, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, MASKED_CARD, CARD_TOKEN, ORIGIN_TIMESTAMP);
        return TransactionPosted.forAuthorized(authorized, NEW_BALANCE, POSTED_AT);
    }

    /**
     * Reads the one row the listener stored.
     *
     * @return the stored row
     */
    private StatementTransactionEntity savedRow() {
        ArgumentCaptor<StatementTransactionEntity> stored =
                ArgumentCaptor.forClass(StatementTransactionEntity.class);
        verify(statementTransactions).save(stored.capture());
        return stored.getValue();
    }

    /**
     * Returns one value at the width its column declares, padded on the right with spaces.
     *
     * @param value the value the event carried
     * @param width the declared width
     * @return the value at exactly {@code width} characters
     */
    private static String atWidth(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Builds a transaction runner that actually runs the callback it is given.
     *
     * <p>The listener wraps the guard, the row, the alert and the marker in one transaction. A mocked
     * template would swallow that callback and every assertion here would pass over an empty
     * repository, so the template is real and only the manager is a stub.</p>
     *
     * @return the runner the listener opens its one transaction on
     */
    private static TransactionTemplate transactionRunner() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new TransactionTemplate(manager);
    }
}
