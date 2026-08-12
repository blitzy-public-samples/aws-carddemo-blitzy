package com.carddemo.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.LedgerServiceDatabase;
import com.carddemo.ledger.TestIdentityPasswords;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.entity.TransactionEntity;
import com.carddemo.ledger.outbox.OutboxRelay;
import com.carddemo.ledger.outbox.OutboxWriter;
import com.carddemo.ledger.repository.OutboxEventRepository;
import com.carddemo.ledger.repository.TransactionRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers {@link PostingService}, the target of paragraph {@code 2000-POST-TRANSACTION} at
 * {@code app/cbl/CBTRN02C.cbl:L424-L444} and of the insert {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579}.
 *
 * <p>Three properties carry that paragraph. Twelve {@code MOVE} statements at
 * {@code app/cbl/CBTRN02C.cbl:L425-L436} copy twelve values onto the record declared at
 * {@code app/cpy/CVTRA05Y.cpy:L5-L16}. One stamp taken at {@code app/cbl/CBTRN02C.cbl:L437} reaches
 * {@code TRAN-PROC-TS} at {@code app/cpy/CVTRA05Y.cpy:L17} through
 * {@code app/cbl/CBTRN02C.cbl:L438}. Three updates then run unconditionally and in fixed order:</p>
 *
 * <pre>
 *            PERFORM 2700-UPDATE-TCATBAL
 *            PERFORM 2800-UPDATE-ACCOUNT-REC
 *            PERFORM 2900-WRITE-TRANSACTION-FILE
 * </pre>
 *
 * <p>Thirteen values reach one row: the twelve copies and the stamp. The trailing
 * {@code FILLER PIC X(20)} at {@code app/cpy/CVTRA05Y.cpy:L18} owns no field and no column.</p>
 *
 * <p>Three behaviours here have no COBOL ancestor. Single-transaction
 * atomicity is the first: the source runs the three writes above with no rollback, and all eight
 * file definitions in {@code app/csd/CARDDEMO.CSD} specify {@code RECOVERY(NONE)} and {@code
 * JOURNAL(NO)}. The masked card number is the second, and {@code app/data/ASCII/dailytran.txt}
 * holds zero mask characters across its 300 records. The currency constant is the third.
 *
 * <p>Each group stubs the four collaborators and starts no application context, except
 * {@link TransactionBoundary}, which runs the shipped wiring over PostgreSQL.</p>
 *
 * <p>Domain note for a new reader: the two billing-cycle accumulators return to zero only through
 * the cycle-close operation of the account service, which reproduces
 * {@code app/cbl/CBACT04C.cbl:L353-L354}. Without it, available credit shrinks until every
 * authorization declines.</p>
 *
 * <p>Scope stays narrow. The record-by-record sweep of all 300 fixture records lives in
 * {@code card-platform/equivalence-tests}. The sign branch and the accumulator arithmetic belong to
 * {@link AccountBalanceUpdaterTest}, the create-or-update fork to
 * {@link CategoryBalanceUpdaterTest}, and duplicate handling to the messaging package.</p>
 */
@DisplayName("PostingService, the target of 2000-POST-TRANSACTION")
final class PostingServiceTest {

    /*
     * Record 1 of app/data/ASCII/dailytran.txt, field by field. Its card reaches account
     * 00000000007 through row 21 of app/data/ASCII/cardxref.txt, which is row 7 of
     * app/data/ASCII/acctdata.txt and not row 1. The identifier is held as two eight-digit halves;
     * TRAN-ID PIC X(16) at app/cpy/CVTRA05Y.cpy:L5 fixes its width, which KEYS(16 0) at
     * app/jcl/TRANFILE.jcl:L53 repeats and app/cbl/CBTRN02C.cbl:L564 writes. The amount reads
     * 504.77: the fixture holds the eleven bytes 0000005047G, whose trailing byte overpunches the
     * sign and the last digit as plus seven. DALYTRAN-ORIG-TS is the one distinct value all 300
     * records carry, with a space at position eleven and colons between the time parts. The masked
     * card number has no COBOL ancestor: twelve mask characters ahead of the last four digits of
     * the Primary Account Number (PAN), and the fixture holds zero mask characters.
     */
    private static final String ACCOUNT_ID = "00000000007";
    private static final String TRANSACTION_ID = "00000000" + "00683580";
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0001";
    private static final String SOURCE = "POS TERM";
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");
    private static final String MERCHANT_ID = "800000000";
    private static final String MERCHANT_NAME = "Abshire-Lowe";
    private static final String MERCHANT_CITY = "North Enoshaven";
    private static final String MERCHANT_ZIP = "72112";
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** Card token the authorized event carries, and the identity the read model keys on. */
    private static final String CARD_TOKEN =
            "7f14b6ce02a9385d1cbe470f28a6d915c34b70e8fa1259d603ba8e47c1f0d269";
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";


    /*
     * Balances. Account row 7 stores 193.00 in ACCT-CURR-BAL PIC S9(10)V99 at
     * app/cpy/CVACT01Y.cpy:L7. app/cbl/CBTRN02C.cbl:L547 then yields 697.77 for the amount above,
     * and -805.33 for the most negative amount the 300 fixture records reach. The widest balance
     * that Picture clause holds carries ten integer digits.
     */
    private static final BigDecimal POSTED_BALANCE = new BigDecimal("697.77");

    /** An instant a fixed clock reads, chosen so every rendered component differs. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-04T05:06:07.890Z");

    /**
     * {@link FIXED_INSTANT} rendered by {@code CobolDecimal.formatProcessingTimestamp}.
     *
     * <p>{@code PicClause.PROCESSING_TIMESTAMP_SHAPE} is {@code YYYY-MM-DD-HH.MM.SS.hh0000}, so the
     * 890 milliseconds become 89 hundredths and four zeros follow, which is the two-significant-digit
     * fraction {@code app/cbl/CBTRN02C.cbl:L701} writes.
     */
    private static final String FIXED_STAMP = "2026-03-04-05.06.07.890000";
    private static final BigDecimal WIDEST_BALANCE = new BigDecimal("9999999999.99");
    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("-998.33");
    private static final BigDecimal REFUNDED_BALANCE = new BigDecimal("-805.33");

    /** A run of twelve or more decimal digits, which no published money or card value holds. */
    private static final String LONG_DIGIT_RUN = "[0-9]{12,}";

    /**
     * The twenty-one property names one serialized {@link TransactionPosted} carries.
     *
     * <p>Sixteen payload names sit beside the five envelope ones. Eleven of the sixteen are the
     * fields the card-keyed read model at {@code app/cpy/COSTM01.CPY:L20-L36} stores, which
     * paragraph {@code 2000-POST-TRANSACTION} at {@code app/cbl/CBTRN02C.cbl:L425-L436} moves onto
     * the posted record.
     */
    private static final List<String> WIRE_PROPERTIES = List.of("eventId", "eventType",
            "schemaVersion", "occurredAt", "aggregateId", "transactionId", "accountId",
            "newBalance", "postedAt", "amount", "maskedCardNumber", "cardToken",
            "transactionTypeCode", "merchantCategoryCode", "source", "description", "merchantId",
            "merchantName", "merchantCity", "merchantZip", "originTimestamp");

    /** Writes one event to JavaScript Object Notation (JSON) text and reads that text back. */
    private static final JsonMapper WIRE_MAPPER = JsonMapper.builder().build();

    /*
     * The four collaborators, standing in for app/cbl/CBTRN02C.cbl:L440, :L441 and :L442 plus the
     * writer one posted event is enqueued through, and the subject built over them.
     */
    private final CategoryBalanceUpdater categoryBalances = mock(CategoryBalanceUpdater.class);
    private final AccountBalanceUpdater accountBalances = mock(AccountBalanceUpdater.class);
    private final TransactionRepository postedRows = mock(TransactionRepository.class);
    private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
    private final PostingService postingService =
            new PostingService(categoryBalances, accountBalances, postedRows, outboxWriter);

    /** Builds record 1 of the daily feed with one amount substituted. */
    private static TransactionAuthorized authorized(BigDecimal amount) {
        return authorized(ACCOUNT_ID, TRANSACTION_ID, amount);
    }

    /** Builds that same record against a named account and transaction identifier. */
    private static TransactionAuthorized authorized(String accountId, String transactionId,
            BigDecimal amount) {
        return TransactionAuthorized.of(accountId, transactionId, TYPE_CODE, CATEGORY_CODE, SOURCE,
                DESCRIPTION, amount, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT);
    }

    /** Stubs the second update to yield one balance. */
    private void yieldBalance(BigDecimal postedBalance) {
        when(accountBalances.updateBalances(any(), any())).thenReturn(postedBalance);
    }

    /** Captures the single row the third update inserted. */
    private TransactionEntity insertedRow() {
        ArgumentCaptor<TransactionEntity> row = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(postedRows).save(row.capture());
        return row.getValue();
    }

    /** Captures the single event enqueued through the outbox writer. */
    private TransactionPosted enqueuedEvent() {
        ArgumentCaptor<TransactionPosted> event = ArgumentCaptor.forClass(TransactionPosted.class);
        verify(outboxWriter).write(event.capture());
        return event.getValue();
    }

    /** Reads one event back as the flat JSON object a topic would carry. */
    private static JsonNode wireForm(TransactionPosted event) {
        return WIRE_MAPPER.readTree(WIRE_MAPPER.writeValueAsString(event));
    }

    /** Names the instance fields one persisted type declares, in declaration order. */
    private static List<String> instanceFieldNames(Class<?> persisted) {
        return Arrays.stream(persisted.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    /** The twelve copies at {@code app/cbl/CBTRN02C.cbl:L425-L436}. */
    @Nested
    @DisplayName("The twelve field copies")
    class FieldCopies {

        @Test
        @DisplayName("all twelve values of app/cbl/CBTRN02C.cbl:L425-L436 land on their own field")
        void allTwelveValuesLandOnTheirOwnField() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            TransactionEntity row = insertedRow();
            assertThat(row.getTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(row.getTypeCode()).isEqualTo(TYPE_CODE);
            assertThat(row.getCategoryCode()).isEqualTo(CATEGORY_CODE);
            assertThat(row.getSource()).isEqualTo(SOURCE);
            assertThat(row.getDescription()).isEqualTo(DESCRIPTION);
            assertThat(row.getAmount()).isEqualTo(AMOUNT);
            assertThat(row.getAmount().scale()).isEqualTo(PicClause.TRAN_AMT_SCALE);
            assertThat(row.getMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(row.getMerchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(row.getMerchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(row.getMerchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(row.getCardNumber()).isEqualTo(MASKED_CARD_NUMBER);
            assertThat(row.getOriginTimestamp()).isEqualTo(AUTHORIZED_AT)
                    .hasSize(PicClause.TRAN_ORIG_TS_WIDTH)
                    .matches(TransactionAuthorized.AUTHORIZED_AT_PATTERN);
        }

        @Test
        @DisplayName("the row declares thirteen fields, so the filler at CVTRA05Y.cpy:L18 has none")
        void theRowDeclaresThirteenFields() {
            assertThat(instanceFieldNames(TransactionEntity.class)).containsExactly("transactionId",
                    "typeCode", "categoryCode", "source", "description", "amount", "merchantId",
                    "merchantName", "merchantCity", "merchantZip", "cardNumber", "originTimestamp",
                    "processedTimestamp");
        }

        @Test
        @DisplayName("app/cbl/CBTRN02C.cbl:L435 copies the masked card number and no full PAN")
        void onlyTheMaskedCardNumberReachesTheRow() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            TransactionEntity row = insertedRow();
            assertThat(row.getCardNumber()).matches(TransactionEntity.MASKED_CARD_NUMBER_PATTERN)
                    .hasSize(PicClause.TRAN_CARD_NUM_WIDTH)
                    .doesNotContainPattern(LONG_DIGIT_RUN);
            assertThat(row.toString()).doesNotContain(row.getCardNumber());
        }
    }

    /** The one stamp at {@code app/cbl/CBTRN02C.cbl:L437} and its store at {@code :L438}. */
    @Nested
    @DisplayName("The processing timestamp")
    class ProcessingTimestamp {

        @Test
        @DisplayName("app/cbl/CBTRN02C.cbl:L437 stamps once and one value reaches row and event")
        void oneStampReachesBothTheRowAndTheEvent() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            String stamped = insertedRow().getProcessedTimestamp();
            assertThat(stamped).isSameAs(enqueuedEvent().postedAt())
                    .hasSize(PicClause.PROCESSING_TIMESTAMP_WIDTH)
                    .hasSameSizeAs(PicClause.PROCESSING_TIMESTAMP_SHAPE)
                    .endsWith(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS)
                    .matches(TransactionPosted.POSTED_AT_PATTERN);
        }

        @Test
        @DisplayName("TRAN-PROC-TS at CVTRA05Y.cpy:L17 takes the stamp and no inbound value")
        void theProcessingTimestampTakesTheStampAndNoInboundValue() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            TransactionEntity row = insertedRow();
            assertThat(row.getProcessedTimestamp()).isNotBlank()
                    .isNotEqualTo(row.getOriginTimestamp())
                    .hasSameSizeAs(row.getOriginTimestamp())
                    .doesNotMatch(TransactionAuthorized.AUTHORIZED_AT_PATTERN)
                    .startsWith(row.getProcessedTimestamp().substring(0, 4));
            assertThat(row.getOriginTimestamp())
                    .doesNotMatch(TransactionPosted.POSTED_AT_PATTERN);
            assertThat(row.getProcessedTimestamp().charAt(10))
                    .isEqualTo(PicClause.PROCESSING_TIMESTAMP_DASH);
        }

        /**
         * The stamp is read from an injected clock, so a test can name the value it expects.
         *
         * <p>The service took the machine clock at the default zone before, which left this the one
         * value of a posted row nothing could assert and made the stamp move with the container's
         * zone. Both are fixed here: the clock arrives through the constructor and the instant is
         * read at {@link ZoneOffset#UTC}.
         */
        @Test
        @DisplayName("an injected clock fixes the stamp, and the reading is taken at UTC")
        void anInjectedClockFixesTheStamp() {
            yieldBalance(POSTED_BALANCE);
            PostingService fixed = new PostingService(categoryBalances, accountBalances, postedRows,
                    outboxWriter, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

            fixed.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            assertThat(insertedRow().getProcessedTimestamp()).isEqualTo(FIXED_STAMP);
            assertThat(enqueuedEvent().postedAt()).isEqualTo(FIXED_STAMP);
        }

        /**
         * A clock in another zone yields the same stamp, which is what reading at UTC buys.
         *
         * <p>{@code Clock.fixed} carries a zone, and {@code LocalDateTime.now(clock)} would have
         * honoured it. The service converts the instant at {@link ZoneOffset#UTC} instead, so a
         * container running in one zone and a container running in another stamp one instant the
         * same way. Without that, two postings of the same moment would disagree by hours and the
         * disagreement would only show in production.
         */
        @Test
        @DisplayName("a clock in another zone yields the same stamp, so the container's zone is out")
        void aClockInAnotherZoneYieldsTheSameStamp() {
            yieldBalance(POSTED_BALANCE);
            PostingService elsewhere = new PostingService(categoryBalances, accountBalances,
                    postedRows, outboxWriter,
                    Clock.fixed(FIXED_INSTANT, ZoneId.of("Asia/Kolkata")));

            elsewhere.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            assertThat(insertedRow().getProcessedTimestamp()).isEqualTo(FIXED_STAMP);
        }
    }

    /** The three updates at {@code app/cbl/CBTRN02C.cbl:L440}, {@code :L441} and {@code :L442}. */
    @Nested
    @DisplayName("The three updates and their fixed order")
    class UpdateOrder {

        @Test
        @DisplayName("app/cbl/CBTRN02C.cbl:L440, :L441 and :L442 run in that order and nothing more")
        void theThreeUpdatesRunInSourceOrder() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            InOrder ordered = inOrder(categoryBalances, accountBalances, postedRows, outboxWriter);
            ordered.verify(categoryBalances)
                    .updateCategoryBalance(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE, AMOUNT);
            ordered.verify(accountBalances).updateBalances(ACCOUNT_ID, AMOUNT);
            ordered.verify(postedRows).save(any(TransactionEntity.class));
            ordered.verify(outboxWriter).write(any(TransactionPosted.class));
            ordered.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("one call drives :L440, :L441 and :L442 once, and the row store sees no read")
        void oneCallDrivesOneOfEachUpdate() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            verify(categoryBalances, times(1)).updateCategoryBalance(any(), any(), any(), any());
            verify(accountBalances, times(1)).updateBalances(any(), any());
            verify(postedRows, times(1)).save(any(TransactionEntity.class));
            verify(outboxWriter, times(1)).write(any());
            verifyNoMoreInteractions(categoryBalances, accountBalances, postedRows, outboxWriter);
        }

        @Test
        @DisplayName("a failing :L440 stops :L441 and :L442, and the failure reaches the caller")
        void aFailingCategoryUpdateStopsTheRemainingTwo() {
            RuntimeException storeFailure = new IllegalStateException("category balance store down");
            doThrow(storeFailure).when(categoryBalances)
                    .updateCategoryBalance(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE, AMOUNT);

            assertThatThrownBy(() ->
                    postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID))
                    .isSameAs(storeFailure);

            verifyNoInteractions(accountBalances, postedRows, outboxWriter);
        }

        @Test
        @DisplayName("a missing balance row stops :L442, keeps :L440, and reaches the caller named")
        void aMissingBalanceRowStopsTheInsertAndTheEvent() {
            when(accountBalances.updateBalances(ACCOUNT_ID, AMOUNT)).thenThrow(
                    new AccountBalanceUpdater.AccountBalanceRowMissingException(ACCOUNT_ID));

            assertThatThrownBy(() ->
                    postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID))
                    .isInstanceOf(AccountBalanceUpdater.AccountBalanceRowMissingException.class);

            verify(categoryBalances)
                    .updateCategoryBalance(ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE, AMOUNT);
            verifyNoInteractions(postedRows, outboxWriter);
        }

        @Test
        @DisplayName("app/cbl/CBTRN02C.cbl:L444 answers whether the category store wrapped, and a"
                + " null event runs no update")
        void theMethodAnswersTheWrapAndRefusesANullEvent() throws NoSuchMethodException {
            assertThat(PostingService.class
                    .getMethod("postTransaction", TransactionAuthorized.class, String.class)
                    .getReturnType())
                    .as("the caller reports the wrap after this transaction commits, so the answer"
                            + " has to leave the method")
                    .isEqualTo(boolean.class);

            assertThatThrownBy(() -> postingService.postTransaction(null, ACCOUNT_ID))
                    .isInstanceOf(NullPointerException.class);

            verifyNoInteractions(categoryBalances, accountBalances, postedRows, outboxWriter);
        }

        @Test
        @DisplayName("a missing or mismatched message key runs no posting side effect")
        void aMessageKeyMustNameThePayloadAggregate() {
            TransactionAuthorized event = authorized(AMOUNT);

            assertThatThrownBy(() -> postingService.postTransaction(event, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("message key");
            assertThatThrownBy(() -> postingService.postTransaction(event, "00000000099"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("aggregate identifier");

            verifyNoInteractions(categoryBalances, accountBalances, postedRows, outboxWriter);
        }
    }

    /** The one {@link TransactionPosted} each call enqueues. */
    @Nested
    @DisplayName("The posted event")
    class PostedEvent {

        @Test
        @DisplayName("newBalance takes ten digits from CVACT01Y.cpy:L7 and amount nine from CVTRA05Y.cpy:L10")
        void theTwoMoneyWidthsStayDistinct() {
            yieldBalance(WIDEST_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            TransactionPosted posted = enqueuedEvent();
            assertThat(posted.newBalance().toPlainString())
                    .matches(TransactionPosted.TEN_INTEGER_DIGIT_BALANCE_PATTERN)
                    .doesNotMatch(TransactionPosted.NINE_INTEGER_DIGIT_AMOUNT_PATTERN);
            assertThat(posted.amount().toPlainString())
                    .matches(TransactionPosted.NINE_INTEGER_DIGIT_AMOUNT_PATTERN);
            assertThat(TransactionPosted.TEN_INTEGER_DIGIT_BALANCE_PATTERN)
                    .isNotEqualTo(TransactionPosted.NINE_INTEGER_DIGIT_AMOUNT_PATTERN);
        }

        @Test
        @DisplayName("the sixteen payload values arrive from the event, :L547 and the stamp at "
                + ":L438")
        void theSixteenPayloadValuesComeFromTheirSources() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            TransactionPosted posted = enqueuedEvent();
            assertThat(posted.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(posted.newBalance()).isEqualTo(POSTED_BALANCE);
            assertThat(posted.newBalance().scale()).isEqualTo(PicClause.ACCT_CURR_BAL_SCALE);
            assertThat(posted.newBalance().toPlainString())
                    .isEqualTo(POSTED_BALANCE.toPlainString());
            assertThat(posted.postedAt()).matches(TransactionPosted.POSTED_AT_PATTERN);
            assertThat(posted.amount()).isEqualTo(AMOUNT);
            assertThat(posted.maskedCardNumber()).isEqualTo(MASKED_CARD_NUMBER);
            assertThat(posted.cardToken()).isEqualTo(CARD_TOKEN);
            assertThat(posted.transactionTypeCode()).isEqualTo(TYPE_CODE);
            assertThat(posted.merchantCategoryCode()).isEqualTo(CATEGORY_CODE);
            assertThat(posted.source()).isEqualTo(SOURCE);
            assertThat(posted.description()).isEqualTo(DESCRIPTION);
            assertThat(posted.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(posted.merchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(posted.merchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(posted.merchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(posted.originTimestamp()).isEqualTo(AUTHORIZED_AT);
            assertThat(posted.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(posted.aggregateId()).isEqualTo(posted.accountId())
                    .matches(EventEnvelope.AGGREGATE_ID_PATTERN)
                    .startsWith("0");
            assertThat(posted.envelope().aggregateId())
                    .matches(EventEnvelope.AGGREGATE_ID_PATTERN);
            assertThat(posted.cardToken()).isEqualTo(CARD_TOKEN);
            assertThat(posted.transactionTypeCode()).isEqualTo(TYPE_CODE);
            assertThat(posted.merchantCategoryCode()).isEqualTo(CATEGORY_CODE);
            assertThat(posted.source()).isEqualTo(SOURCE);
            assertThat(posted.description()).isEqualTo(DESCRIPTION);
            assertThat(posted.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(posted.merchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(posted.merchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(posted.merchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(posted.originTimestamp()).isEqualTo(AUTHORIZED_AT);
        }

        @Test
        @DisplayName("the serialized event is one flat object of every property, no envelope key")
        void theSerializedEventIsOneFlatObject() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            JsonNode wire = wireForm(enqueuedEvent());
            assertThat(wire.propertyNames()).containsExactlyInAnyOrderElementsOf(WIRE_PROPERTIES);
            assertThat(wire.size()).isEqualTo(WIRE_PROPERTIES.size());
            assertThat(wire.get("envelope")).isNull();
            assertThat(wire.get("eventType").stringValue()).isEqualTo(TransactionPosted.EVENT_TYPE);
            assertThat(wire.get("schemaVersion").intValue())
                    .isEqualTo(TransactionPosted.TRANSACTION_DETAIL_SCHEMA_VERSION);
            assertThat(wire.get("newBalance").isString()).isTrue();
            assertThat(wire.get("amount").isString()).isTrue();
        }

        /**
         * Asserts the payload carries no property another record owns, and no run of twelve or more
         * digits beyond its two identifiers.
         *
         * <p>{@code authorizedAt} is not on the forbidden list. Paragraph
         * {@code 2000-POST-TRANSACTION} at {@code app/cbl/CBTRN02C.cbl:L425-L436} moves
         * {@code DALYTRAN-ORIG-TS} onto the posted record, and the statement row at
         * {@code app/cpy/COSTM01.CPY:L20-L36} stores it, so the posted event carries it. The two
         * billing-cycle accumulators and the credit limit stay on the account record at
         * {@code app/cpy/CVACT01Y.cpy:L8,L12-L13} and no consumer of this event reads them.
         */
        @Test
        @DisplayName("the ten transaction values come from the authorized event, not from blanks")
        void theTenTransactionValuesComeFromTheAuthorizedEvent() {
            yieldBalance(POSTED_BALANCE);
            TransactionAuthorized source = authorized(AMOUNT);

            postingService.postTransaction(source, ACCOUNT_ID);

            TransactionPosted posted = enqueuedEvent();
            assertThat(posted.cardToken()).isEqualTo(source.cardToken());
            assertThat(posted.transactionTypeCode()).isEqualTo(source.transactionTypeCode());
            assertThat(posted.merchantCategoryCode()).isEqualTo(source.merchantCategoryCode());
            assertThat(posted.source()).isEqualTo(source.source());
            assertThat(posted.description()).isEqualTo(source.description());
            assertThat(posted.merchantId()).isEqualTo(source.merchantId());
            assertThat(posted.merchantName()).isEqualTo(source.merchantName());
            assertThat(posted.merchantCity()).isEqualTo(source.merchantCity());
            assertThat(posted.merchantZip()).isEqualTo(source.merchantZip());
            assertThat(posted.originTimestamp()).isEqualTo(source.authorizedAt());
            assertThat(posted.originTimestamp()).isNotEqualTo(posted.postedAt());
        }

        @Test
        @DisplayName("no forbidden property joins the payload and no long digit run survives it")
        void noForbiddenPropertyJoinsThePayload() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            TransactionPosted posted = enqueuedEvent();
            JsonNode wire = wireForm(posted);
            assertThat(wire.propertyNames()).doesNotContain("cycleCredit", "cycleDebit",
                    "categoryBalance", "creditLimit", "cardStatus", "accountStatus", "filler",
                    "currency", "declineReasonCode", "cardNumber", "cvv");
            assertThat(wire.get("postedAt").stringValue()).doesNotContain("T").doesNotEndWith("Z");

            String beyondIdentifiers = WIRE_MAPPER.writeValueAsString(posted)
                    .replace(posted.eventId().toString(), "")
                    .replace(posted.transactionId(), "")
                    .replace(posted.merchantId(), "");
            assertThat(beyondIdentifiers).doesNotContainPattern(LONG_DIGIT_RUN);
        }
    }

    /**
     * The collaborators this service takes, and the checks the source never performs.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl} allocates nine data definitions across its 45 lines and names
     * no card file. {@code app/cbl/CBTRN02C.cbl} selects six files and copies five record layouts,
     * none of them a card layout. A card-status check is therefore out of reach of this paragraph.
     * All 50 rows of {@code app/data/ASCII/acctdata.txt} carry an active status of {@code Y}, so a
     * closed account is synthetic here.</p>
     */
    @Nested
    @DisplayName("The collaborators and the absent checks")
    class Collaborators {

        @Test
        @DisplayName("the one constructor takes the four collaborators and nothing else")
        void theOneConstructorTakesFourCollaborators() {
            Constructor<?>[] declared = PostingService.class.getConstructors();

            assertThat(declared).hasSize(1);
            assertThat(declared[0].getParameterTypes()).containsExactly(
                    CategoryBalanceUpdater.class, AccountBalanceUpdater.class,
                    TransactionRepository.class, OutboxWriter.class);
            assertThat(declared[0].getParameterTypes())
                    .doesNotContain(RejectRecorder.class, OutboxEventRepository.class);
        }

        @Test
        @DisplayName("app/cbl/CBTRN02C.cbl:L564 writes the key the event carries and mints none")
        void theTransactionIdentifierArrivesFromTheEvent() {
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(authorized(AMOUNT), ACCOUNT_ID);

            assertThat(insertedRow().getTransactionId()).isEqualTo(TRANSACTION_ID)
                    .hasSize(PicClause.TRAN_ID_WIDTH);
            assertThat(enqueuedEvent().transactionId()).isEqualTo(TRANSACTION_ID);
            verifyNoMoreInteractions(postedRows);
        }

        @Test
        @DisplayName("a negative amount posts, since app/cbl/CBTRN02C.cbl:L425-L442 tests no sign")
        void aNegativeAmountPosts() {
            yieldBalance(REFUNDED_BALANCE);

            postingService.postTransaction(authorized(REFUND_AMOUNT), ACCOUNT_ID);

            assertThat(insertedRow().getAmount()).isEqualTo(REFUND_AMOUNT);
            TransactionPosted posted = enqueuedEvent();
            assertThat(posted.amount()).isEqualTo(REFUND_AMOUNT);
            assertThat(posted.newBalance()).isEqualTo(REFUNDED_BALANCE);
            assertThat(posted.amount().toPlainString())
                    .matches(TransactionPosted.NINE_INTEGER_DIGIT_AMOUNT_PATTERN);
        }

        @Test
        @DisplayName("a closed account posts, since no path reads ACCT-ACTIVE-STATUS at CVACT01Y.cpy:L6")
        void aClosedAccountStillPosts() {
            String syntheticAccount = "00000000099";
            yieldBalance(POSTED_BALANCE);

            postingService.postTransaction(
                    authorized(syntheticAccount, TRANSACTION_ID, AMOUNT), syntheticAccount);

            verify(categoryBalances)
                    .updateCategoryBalance(syntheticAccount, TYPE_CODE, CATEGORY_CODE, AMOUNT);
            verify(accountBalances).updateBalances(syntheticAccount, AMOUNT);
            assertThat(insertedRow().getTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(enqueuedEvent().accountId()).isEqualTo(syntheticAccount);
        }
    }

    /**
     * The transaction boundary, over PostgreSQL. The one group here that starts an application
     * context.
     *
     * <p>{@code postTransaction} carries {@link Transactional} at default propagation, so the three
     * updates and the outbox row join the transaction the caller opened and settle together.
     * Single-transaction atomicity has no COBOL ancestor, and {@code
     * app/cbl/CBTRN02C.cbl:L440-L442} runs its three writes with no rollback at all.</p>
     *
     * <p>Flyway owns each schema object and {@code ddl-auto} stays {@code validate}, so a drift
     * between an entity and a migration stops start-up. No broker is reached: the one message
     * template bean is replaced with a stand-in, and the listener of
     * {@code messaging/TransactionAuthorizedConsumer} does not start. Account {@code 00000000007}
     * arrives through {@code V2__seed.sql}, which seeds neither the {@code transaction} table nor
     * {@code outbox_event}. The alternate index {@code KEYS(26 304)} at
     * {@code app/jcl/TRANIDX.jcl:L27} becomes the secondary index over the processing
     * timestamp.</p>
     *
     * <p>Each case here writes under its own transaction identifier, asserts on that
     * identifier, and empties the two tables it wrote afterwards, so no case depends on
     * another having run first.</p>
     */
    @Nested
    @DisplayName("The transaction boundary over PostgreSQL")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
            "spring.kafka.listener.auto-startup=false",
            "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
            "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
            "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
            "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
            "TOPIC_DEAD_LETTER_SUFFIX=.DLT",
            "spring.jpa.hibernate.ddl-auto=validate"
    })
    class TransactionBoundary {

        /** The login this group's database server accepts, and the name of its database. */
        private static final String DATABASE_LOGIN = "carddemo";

        /** The sixteen-character key of the posting this group rolls back. */
        private static final String ROLLED_BACK_ID = "SLICE-ROLLBACK-1";

        /** The sixteen-character key of the posting this group commits. */
        private static final String COMMITTED_ID = "SLICE-COMMIT-001";

        /** A publication instant far enough back that every retention horizon here precedes it. */
        private static final java.time.Instant RETENTION_EXPIRED_AT =
                java.time.Instant.parse("2020-01-01T00:00:00Z");

        /** The horizon the retention tests apply, which every expired row above precedes. */
        private static final java.time.Instant RETENTION_HORIZON =
                java.time.Instant.parse("2021-01-01T00:00:00Z");

        /** Rows the bounded retention delete removes per statement when a test wants them all. */
        private static final int RETENTION_BATCH_SIZE = 1000;

        /**
         * The one container the module fork runs, which this class reads a login from.
         *
         * <p>{@link LedgerServiceDatabase} owns it and hands this class a database of its own inside it.
         * Nothing here starts or stops a container.
         */
        private static final PostgreSQLContainer POSTGRES = LedgerServiceDatabase.container();

        /** The replaced publish channel, held so no message leaves this group. */
        @MockitoBean
        private org.springframework.kafka.core.KafkaTemplate<String, Object> publishChannel;

        /** Prevents the scheduled publisher from consuming rows this boundary test inspects. */
        @MockitoBean
        private OutboxRelay relay;

        /** The subject, wired by the context. */
        private PostingService postings;

        /** The store of posted rows. */
        private TransactionRepository storedRows;

        /** The store of unpublished events. */
        private OutboxEventRepository storedEvents;

        /** The boundary a caller opens around one posting. */
        private TransactionTemplate callerTransaction;

        /**
         * The statement channel this group empties {@code transaction} through.
         *
         * <p>{@link TransactionRepository} declares {@code save}, {@code findById},
         * {@code existsById} and {@code count} and no delete.
         */
        private org.springframework.jdbc.core.JdbcTemplate jdbc;

        /** Points the datasource at the started server, on the schema the shipped URL names. */
        @DynamicPropertySource
        static void datasourceProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", TransactionBoundary::jdbcUrlOnTheServiceSchema);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }

        /**
         * The container's connection string, carrying the {@code currentSchema} parameter the
         * shipped {@code spring.datasource.url} carries.
         *
         * <p>{@code hibernate.default_schema} qualifies the statements Hibernate writes and
         * leaves a statement written by hand unqualified, so the search path decides where an
         * unqualified name resolves.
         */
        private static String jdbcUrlOnTheServiceSchema() {
            return LedgerServiceDatabase.urlFor(PostingServiceTest.class);
        }

        /** Reads the five beans this group drives out of the started context. */
        @BeforeEach
        void resolveBeans(ApplicationContext context) {
            postings = context.getBean(PostingService.class);
            storedRows = context.getBean(TransactionRepository.class);
            storedEvents = context.getBean(OutboxEventRepository.class);
            callerTransaction = context.getBean(TransactionTemplate.class);
            jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        }

        /** Empties the two tables this group writes, so each case arranges its own state. */
        @AfterEach
        void emptyTheTablesThisGroupWrites() {
            storedEvents.deleteAll();
            jdbc.update("DELETE FROM \"transaction\"");
        }

        @Test
        @DisplayName("a rolled-back caller transaction leaves no posted row and no outbox event")
        void aRolledBackCallerTransactionLeavesNothing() {
            callerTransaction.executeWithoutResult(status -> {
                postings.postTransaction(
                        authorized(ACCOUNT_ID, ROLLED_BACK_ID, AMOUNT), ACCOUNT_ID);
                status.setRollbackOnly();
            });

            assertThat(storedRows.findById(ROLLED_BACK_ID)).isEmpty();
            assertThat(storedEvents.findAll())
                    .noneMatch(row -> row.getPayload().contains(ROLLED_BACK_ID));
        }

        @Test
        @DisplayName("a committed posting leaves one row and one unpublished outbox event")
        void aCommittedPostingLeavesOneRowAndOneUnpublishedEvent() {
            postings.postTransaction(
                    authorized(ACCOUNT_ID, COMMITTED_ID, AMOUNT), ACCOUNT_ID);

            assertThat(storedRows.findById(COMMITTED_ID)).isPresent();

            List<OutboxEventEntity> enqueued = storedEvents.findAll().stream()
                    .filter(row -> row.getPayload().contains(COMMITTED_ID))
                    .toList();
            assertThat(enqueued).hasSize(1);
            OutboxEventEntity row = enqueued.getFirst();
            assertThat(row.isPublished()).isFalse();
            assertThat(row.getEventType()).isEqualTo(TransactionPosted.EVENT_TYPE);
            assertThat(row.getAggregateId()).isEqualTo(ACCOUNT_ID);
            assertThat(row.getPayload()).doesNotContain("\"envelope\"");
        }

        @Test
        @DisplayName("postTransaction carries default propagation and opens no transaction of its own")
        void postTransactionCarriesDefaultPropagation() throws NoSuchMethodException {
            Transactional boundary = PostingService.class
                    .getMethod("postTransaction", TransactionAuthorized.class, String.class)
                    .getAnnotation(Transactional.class);

            assertThat(boundary).isNotNull();
            assertThat(boundary.propagation()).isEqualTo(Propagation.REQUIRED)
                    .isNotEqualTo(Propagation.REQUIRES_NEW);

            Transactional writerBoundary = OutboxWriter.class.getMethod("write", Object.class)
                    .getAnnotation(Transactional.class);
            assertThat(writerBoundary.propagation())
                    .as("the writer refuses to persist an event outside its caller's transaction, "
                            + "which is what makes the posted event and the three updates of "
                            + "app/cbl/CBTRN02C.cbl:L440-L442 one unit")
                    .isEqualTo(Propagation.MANDATORY);
        }

        @Test
        @Order(4)
        @DisplayName("the retention delete takes the published row past the horizon and leaves the "
                + "unpublished one this group wrote")
        void retentionDeleteTakesOnlyPublishedRowsPastTheHorizon() {
            OutboxEventEntity unpublished = new OutboxEventEntity(
                    java.util.UUID.fromString("00000000-0000-4000-8000-0000000000f0"),
                    TransactionPosted.EVENT_TYPE, ACCOUNT_ID, "{}", RETENTION_EXPIRED_AT);
            OutboxEventEntity expired = new OutboxEventEntity(
                    java.util.UUID.fromString("00000000-0000-4000-8000-0000000000f1"),
                    TransactionPosted.EVENT_TYPE, ACCOUNT_ID, "{}", RETENTION_EXPIRED_AT);
            expired.markPublished(RETENTION_EXPIRED_AT);
            callerTransaction.executeWithoutResult(status -> {
                storedEvents.save(unpublished);
                storedEvents.save(expired);
            });

            int removed = callerTransaction.execute(status ->
                    storedEvents.deletePublishedBefore(RETENTION_HORIZON, RETENTION_BATCH_SIZE));

            assertThat(removed).isEqualTo(1);
            assertThat(storedEvents.findById(expired.getEventId())).isEmpty();
            assertThat(storedEvents.findById(unpublished.getEventId()))
                    .describedAs("the relay has not published this row, so retention must not take it")
                    .isPresent();
        }

        @Test
        @Order(5)
        @DisplayName("the retention delete honours its row limit and reports zero when nothing is due")
        void retentionDeleteHonoursItsLimitAndReportsZero() {
            for (int row = 0; row < 3; row++) {
                OutboxEventEntity expired = new OutboxEventEntity(
                        java.util.UUID.fromString("00000000-0000-4000-8000-0000000000e" + row),
                        TransactionPosted.EVENT_TYPE, ACCOUNT_ID, "{}",
                        RETENTION_EXPIRED_AT.plusSeconds(row));
                expired.markPublished(RETENTION_EXPIRED_AT.plusSeconds(row));
                callerTransaction.executeWithoutResult(status -> storedEvents.save(expired));
            }

            int firstStatement = callerTransaction.execute(status ->
                    storedEvents.deletePublishedBefore(RETENTION_HORIZON, 2));
            int secondStatement = callerTransaction.execute(status ->
                    storedEvents.deletePublishedBefore(RETENTION_HORIZON, 2));
            int thirdStatement = callerTransaction.execute(status ->
                    storedEvents.deletePublishedBefore(RETENTION_HORIZON, 2));

            assertThat(firstStatement).describedAs("the limit bounds one statement").isEqualTo(2);
            assertThat(secondStatement).describedAs("the remainder follows").isEqualTo(1);
            assertThat(thirdStatement)
                    .describedAs("outbox/RetentionSweeper stops on a count below the batch size")
                    .isZero();
        }
    }
}
