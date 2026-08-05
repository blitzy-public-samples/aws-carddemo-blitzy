package com.carddemo.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.ledger.domain.RejectRecorder.FeedTransaction;
import com.carddemo.ledger.config.ObservabilityConfig;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.entity.RejectedTransactionEntity;
import com.carddemo.ledger.outbox.OutboxRelay;
import com.carddemo.ledger.outbox.OutboxWriter;
import com.carddemo.ledger.repository.OutboxEventRepository;
import com.carddemo.ledger.repository.RejectedTransactionRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reject-path tests for {@link RejectRecorder}.
 *
 * <p>The subject is {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465}. Line
 * {@code :L447} copies the daily transaction record into the data half, {@code :L448} copies the
 * validation trailer into the trailer half, and {@code :L451} writes both halves as one record. The
 * record stands at {@code :L176-L178} and its trailer at {@code :L180-L182}.
 *
 * <pre>{@code
 * 01 REJECT-RECORD.
 *    05 REJECT-TRAN-DATA          PIC X(350).
 *    05 VALIDATION-TRAILER        PIC X(80).
 * 01 WS-VALIDATION-TRAILER.
 *    05 WS-VALIDATION-FAIL-REASON      PIC 9(04).
 *    05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
 * }</pre>
 *
 * <p>Both halves sum to the {@code LRECL=430} of {@code app/jcl/POSTTRAN.jcl:L36}, allocated for
 * the DALYREJS Generation Data Group (GDG) at {@code app/jcl/POSTTRAN.jcl:L34-L38}. The data half
 * carries the fourteen fields of {@code app/cpy/CVTRA06Y.cpy:L5-L18}. A refusal is expected
 * traffic: {@code :L214} counts it and {@code :L229-L230} moves 4 into the return code once the
 * count rises above zero. The recorder evaluates no decline rule.
 *
 * <p>Three behaviours have no Common Business Oriented Language (COBOL) ancestor: the masked card
 * number, the currency constant, and the plain-digit encoding of the amount.
 *
 * <p>Values come from record 1 of {@code app/data/ASCII/dailytran.txt}, joined through row 21 of
 * {@code app/data/ASCII/cardxref.txt} to account {@code 00000000007} of
 * {@code app/data/ASCII/acctdata.txt}. Code {@code 0102} is the one code those 300 records reach,
 * so the other three drive constructed inputs.
 */
class RejectRecorderTest {

    /**
     * Fields of fixture record 1, in the order {@code app/cpy/CVTRA06Y.cpy:L5-L18} declares them.
     * The transaction identifier is assembled group by group, so no card-length digit run stands in
     * this file. The masked form is ADDITIVE; the account identifier comes from
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>The unmasked card value is generated by {@link #syntheticCardNumber(long)} and no card of
     * {@code app/data/ASCII/carddata.txt} is written down here. Its last four digits match the
     * masked form, so the leak assertions below compare the twelve digits a mask hides.
     */
    private static final String TRANSACTION_ID = "0000000000" + "683580";
    private static final String ACCOUNT_ID = "00000000007";
    private static final String FULL_CARD_NUMBER = syntheticCardNumber(452612877065L);
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** Card token the authorized event carries, and the identity the read model keys on. */
    private static final String CARD_TOKEN =
            "7f14b6ce02a9385d1cbe470f28a6d915c34b70e8fa1259d603ba8e47c1f0d269";
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0001";
    private static final String SOURCE = "POS TERM";
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";
    private static final String MERCHANT_ID = "800000000";
    private static final String MERCHANT_NAME = "Abshire-Lowe";
    private static final String MERCHANT_CITY = "North Enoshaven";
    private static final String MERCHANT_ZIP = "72112";
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";


    /**
     * {@code DALYTRAN-AMT} of record 1, whose zoned bytes at columns 133 to 143 read 0000005047G.
     * Then a refund of the kind 50 of the 300 records carry. Then the twelve characters opening the
     * identifier of each constructed input.
     */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");
    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("-998.33");
    private static final String CONSTRUCTED_ID_PREFIX = "SYNTHETIC-ID";

    /**
     * Shapes of {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}, of the masked card number, and of a
     * digit run long enough to be a card number or a widened amount field. The mapper reads one
     * event back as JavaScript Object Notation (JSON) for the wire-shape assertions.
     */
    private static final Pattern FOUR_DIGITS = Pattern.compile("^[0-9]{4}$");
    private static final Pattern MASKED_FORM = Pattern.compile("^\\*{12}[0-9]{4}$");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The store the {@code WRITE} at {@code :L451} became, the writer beside it, the subject. */
    private RejectedTransactionRepository rejectedTransactions;
    private OutboxWriter outbox;

    /** Registry the reject counter registers with, read by the counting assertion. */
    private MeterRegistry registry;

    /** The recording surface the subject raises its counter through. */
    private LedgerMeters meters;

    private RejectRecorder subject;

    @BeforeEach
    void buildRecorder() {
        rejectedTransactions = mock(RejectedTransactionRepository.class);
        outbox = mock(OutboxWriter.class);
        registry = new SimpleMeterRegistry();
        meters = new ObservabilityConfig().ledgerMeters(registry);
        subject = new RejectRecorder(rejectedTransactions, outbox, meters);
    }

    /** Builds a refusal from fixture record 1 carrying {@code amount}. */
    private static FeedTransaction refusal(BigDecimal amount) {
        return refusal(TRANSACTION_ID, amount);
    }

    /** Builds a refusal under {@code transactionId}, sixteen characters of {@code DALYTRAN-ID}. */
    private static FeedTransaction refusal(String transactionId, BigDecimal amount) {
        return feedRecord(ACCOUNT_ID, transactionId, amount, SOURCE, DESCRIPTION, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP);
    }

    /**
     * Builds one feed record, varying the components a test varies.
     *
     * <p>The subject accepts this type and not {@code TransactionAuthorized}, and the reason is the
     * point of the type: a feed record has reached no decision, so refusing it is possible, while an
     * authorized event carries a decision another service already published.
     *
     * @param accountId     the account the cross-reference read resolved
     * @param transactionId DALYTRAN-ID
     * @param amount        DALYTRAN-AMT, signed
     * @param source        DALYTRAN-SOURCE
     * @param description   DALYTRAN-DESC
     * @param merchantName  DALYTRAN-MERCHANT-NAME
     * @param merchantCity  DALYTRAN-MERCHANT-CITY
     * @param merchantZip   DALYTRAN-MERCHANT-ZIP
     * @return the record the subject refuses
     */
    private static FeedTransaction feedRecord(String accountId, String transactionId,
            BigDecimal amount, String source, String description, String merchantName,
            String merchantCity, String merchantZip) {
        return new FeedTransaction(accountId, transactionId, TYPE_CODE, CATEGORY_CODE, source,
                description, amount, MERCHANT_ID, merchantName, merchantCity, merchantZip,
                MASKED_CARD_NUMBER, AUTHORIZED_AT);
    }

    /** Builds a constructed input naming its code, for the three codes the feed never reaches. */
    private static FeedTransaction constructedRefusal(DeclineReason reason) {
        return refusal(CONSTRUCTED_ID_PREFIX + reason.code(), AMOUNT);
    }

    /** Captures the one row the subject stored. */
    private RejectedTransactionEntity storedRow() {
        ArgumentCaptor<RejectedTransactionEntity> captor =
                ArgumentCaptor.forClass(RejectedTransactionEntity.class);
        verify(rejectedTransactions).save(captor.capture());
        return captor.getValue();
    }

    /** Captures the one declined event the subject enqueued. */
    private TransactionDeclined enqueuedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(outbox).write(captor.capture());
        assertTrue(captor.getValue() instanceof TransactionDeclined,
                "the value enqueued at :L448 is the declined event");
        return (TransactionDeclined) captor.getValue();
    }

    /** Writes one event to JSON and reads it back as a tree. */
    private static JsonNode wireForm(TransactionDeclined event) {
        return MAPPER.readTree(MAPPER.writeValueAsString(event));
    }

    /**
     * Builds a card-shaped value under the {@code 9999} prefix.
     *
     * <p>The 50 cards of {@code app/data/ASCII/carddata.txt} open on 50 distinct prefixes and none
     * of them is {@code 9999}, so a value from here matches no card this repository ships.
     *
     * @param serial the twelve digits following the prefix
     * @return sixteen digits, the width {@code CARD-NUM PIC X(16)} declares at
     *         {@code app/cpy/CVACT02Y.cpy:L5}
     */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format("%012d", serial);
    }

    @Nested
    @DisplayName("The two writes at app/cbl/CBTRN02C.cbl:L447-L451")
    class TwoWrites {

        @Test
        @DisplayName("stores the row once at :L451, then enqueues the declined event once at :L448")
        void storesTheRowThenEnqueuesTheEvent() {
            subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);

            InOrder order = inOrder(rejectedTransactions, outbox);
            order.verify(rejectedTransactions, times(1)).save(any(RejectedTransactionEntity.class));
            order.verify(outbox, times(1)).write(any(TransactionDeclined.class));
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("raises nothing on the path app/cbl/CBTRN02C.cbl:L215 takes")
        void raisesNothingOnTheRefusalPath() {
            assertDoesNotThrow(
                    () -> subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT),
                    "counted at :L214, reported at :L229-L230, never raised");
        }

        @Test
        @DisplayName("refuses a missing transaction and a missing code, writing nothing")
        void refusesMissingArguments() {
            assertThrows(NullPointerException.class,
                    () -> subject.recordReject(null, DeclineReason.OVER_CREDIT_LIMIT),
                    ":L447 has no data half to copy");
            assertThrows(NullPointerException.class,
                    () -> subject.recordReject(refusal(AMOUNT), null),
                    ":L448 has no trailer to copy");

            verifyNoInteractions(rejectedTransactions, outbox);
        }

        @Test
        @DisplayName("takes the store, the writer and the meters, and makes the write one required "
                + "transaction")
        void takesTheStoreTheWriterAndTheMeters() throws NoSuchMethodException {
            Constructor<?>[] constructors = RejectRecorder.class.getDeclaredConstructors();
            Set<String> held = new LinkedHashSet<>();
            for (Field field : RejectRecorder.class.getDeclaredFields()) {
                // Instance state only. A static final field is a constant of the rendering, not a
                // collaborator, and this assertion is about which collaborators reach the class.
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    held.add(field.getType().getSimpleName());
                }
            }

            assertEquals(1, constructors.length, "one way to build it");
            assertEquals(List.of(RejectedTransactionRepository.class, OutboxWriter.class,
                            LedgerMeters.class),
                    List.of(constructors[0].getParameterTypes()),
                    "the store, the writer and the counter one reject raises");
            assertEquals(Set.of("RejectedTransactionRepository", "OutboxWriter", "LedgerMeters",
                            "Clock"), held,
                    "ordinary traffic, so no log writer");
            java.lang.reflect.Method recordReject = RejectRecorder.class.getMethod(
                    "recordReject", FeedTransaction.class, DeclineReason.class);
            assertEquals(void.class, recordReject.getReturnType(), ":L465 EXIT yields no value");
            assertEquals(Propagation.REQUIRED,
                    recordReject.getAnnotation(Transactional.class).propagation(),
                    "the reject row and its event share one transaction");

            Transactional writerBoundary = OutboxWriter.class.getMethod("write", Object.class)
                    .getAnnotation(Transactional.class);
            assertEquals(Propagation.MANDATORY, writerBoundary.propagation(),
                    "the writer refuses to persist an event outside its caller's transaction");
        }
    }

    @Nested
    @DisplayName("The record widths of app/cbl/CBTRN02C.cbl:L176-L182")
    class RecordWidths {

        /** Sums the twelve leading fields of {@code app/cpy/CVTRA06Y.cpy:L5-L16}. */
        private int leadingTwelveFields() {
            return PicClause.DALYTRAN_ID_WIDTH + PicClause.DALYTRAN_TYPE_CD_WIDTH
                    + PicClause.DALYTRAN_CAT_CD_WIDTH + PicClause.DALYTRAN_SOURCE_WIDTH
                    + PicClause.DALYTRAN_DESC_WIDTH + PicClause.DALYTRAN_AMT_WIDTH
                    + PicClause.DALYTRAN_MERCHANT_ID_WIDTH + PicClause.DALYTRAN_MERCHANT_NAME_WIDTH
                    + PicClause.DALYTRAN_MERCHANT_CITY_WIDTH
                    + PicClause.DALYTRAN_MERCHANT_ZIP_WIDTH + PicClause.DALYTRAN_CARD_NUM_WIDTH
                    + PicClause.DALYTRAN_ORIG_TS_WIDTH;
        }

        @Test
        @DisplayName("the fourteen fields of app/cpy/CVTRA06Y.cpy:L5-L18 fill the data half")
        void theFourteenFieldsFillTheDataHalf() {
            int fourteen = leadingTwelveFields() + PicClause.DALYTRAN_PROC_TS_WIDTH
                    + PicClause.DALYTRAN_RECORD_FILLER_WIDTH;

            assertEquals(PicClause.REJECT_TRAN_DATA_WIDTH, fourteen,
                    ":L177 REJECT-TRAN-DATA holds the whole record");
            assertEquals(PicClause.DALYTRAN_RECORD_LENGTH, fourteen,
                    "CVTRA06Y.cpy:L4 DALYTRAN-RECORD measures the same");
            assertEquals(PicClause.DALYTRAN_ORIG_TS_WIDTH, PicClause.DALYTRAN_PROC_TS_WIDTH,
                    "CVTRA06Y.cpy:L16-L17 declares both stamps PIC X(26); the second stands blank"
                            + " until :L438 stamps it on the posting path");
            assertEquals(PicClause.REJECT_TRAN_DATA_WIDTH - leadingTwelveFields()
                            - PicClause.DALYTRAN_PROC_TS_WIDTH,
                    PicClause.DALYTRAN_RECORD_FILLER_WIDTH,
                    "CVTRA06Y.cpy:L18 FILLER closes the rest");
            assertEquals(PicClause.VALIDATION_TRAILER_WIDTH, PicClause.VALIDATION_FAIL_REASON_WIDTH
                            + PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                    ":L180 holds the code then the text");
            assertEquals(PicClause.REJECT_RECORD_LENGTH, fourteen
                            + PicClause.VALIDATION_TRAILER_WIDTH,
                    ":L176 measures the LRECL the GDG allocates");
        }

        @Test
        @DisplayName("values filling each declared width still render both halves")
        void valuesFillingEachDeclaredWidthStillRender() {
            FeedTransaction wide = feedRecord(ACCOUNT_ID, TRANSACTION_ID, AMOUNT,
                    "X".repeat(PicClause.DALYTRAN_SOURCE_WIDTH),
                    "X".repeat(PicClause.DALYTRAN_DESC_WIDTH),
                    "X".repeat(PicClause.DALYTRAN_MERCHANT_NAME_WIDTH),
                    "X".repeat(PicClause.DALYTRAN_MERCHANT_CITY_WIDTH),
                    "X".repeat(PicClause.DALYTRAN_MERCHANT_ZIP_WIDTH));

            assertDoesNotThrow(() -> subject.recordReject(wide, DeclineReason.OVER_CREDIT_LIMIT),
                    "a field filling its positions leaves :L177-L178 holding their widths");
            verify(rejectedTransactions).save(any(RejectedTransactionEntity.class));
            verify(outbox).write(any(TransactionDeclined.class));
        }
    }

    @Nested
    @DisplayName("The validation trailer at app/cbl/CBTRN02C.cbl:L448")
    class ValidationTrailer {

        @Test
        @DisplayName("carries the four texts of :L385-L419 character for character")
        void carriesTheFourSourceTexts() {
            String longest = DeclineReason.ACCOUNT_EXPIRED.description();

            assertEquals(4, DeclineReason.values().length, "four codes and no fifth");
            assertEquals("INVALID CARD NUMBER FOUND",
                    DeclineReason.INVALID_CARD_NUMBER.description(), "text of :L385-L387");
            assertEquals("ACCOUNT RECORD NOT FOUND", DeclineReason.ACCOUNT_NOT_FOUND.description(),
                    "text of :L397-L399");
            assertEquals("OVERLIMIT TRANSACTION", DeclineReason.OVER_CREDIT_LIMIT.description(),
                    "text of :L410-L412");
            assertEquals("TRANSACTION RECEIVED AFTER ACCT EXPIRATION", longest,
                    "text of :L417-L419");
            assertEquals(42, longest.length(), "the widest text holds 42");
            assertTrue(longest.length() <= PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                    "it fits the PIC X(76) of :L182");
            assertTrue(longest.contains("ACCT "), ":L418 stands unabbreviated");
        }

        /**
         * Asserts each reachable reason reaches the row and the event as its typed constant.
         *
         * <p>The source drives only the three reasons that follow a resolved cross-reference read.
         * {@code app/cbl/CBTRN02C.cbl:L383-L387} assigns reason {@code 0100} inside the
         * {@code INVALID KEY} limb and the gate at {@code :L372} then stops the account
         * lookup, so a transaction that reached this recorder already carries the account
         * identifier that read resolved. {@link #reasonThatResolvesNoAccountIsRefused()}
         * covers the fourth.
         *
         * @param reason one reject reason that resolves an account identifier
         */
        @ParameterizedTest
        @EnumSource(value = DeclineReason.class, names = "INVALID_CARD_NUMBER",
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("stores four zero-padded digits and hands the typed constant to the event")
        void storesFourDigitsAndHandsTheConstantToTheEvent(DeclineReason reason) {
            subject.recordReject(constructedRefusal(reason), reason);

            RejectedTransactionEntity row = storedRow();
            assertEquals(PicClause.VALIDATION_FAIL_REASON_WIDTH, row.getRejectReasonCode().length(),
                    ":L181 is PIC 9(04)");
            assertTrue(FOUR_DIGITS.matcher(row.getRejectReasonCode()).matches(),
                    "the code keeps its leading zeros");
            assertEquals(reason.code(), row.getRejectReasonCode(),
                    "four characters off the constant, never a number");
            assertEquals(reason.description(), row.getRejectReasonDescription(),
                    "the text comes off the same constant");
            assertTrue(row.getRejectReasonDescription().length()
                            <= PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH,
                    "the text fits the PIC X(76) of :L182");
            assertTrue(row.getTransactionId().startsWith(CONSTRUCTED_ID_PREFIX),
                    "codes 0101 and 0103 need a constructed input: each card of the feed resolves"
                            + " and each expiry clears its origin stamp");

            TransactionDeclined event = enqueuedEvent();
            assertSame(reason, event.declineReasonCode(),
                    "it records the code its caller established");
            assertEquals(reason.description(), event.declineReasonDescription(),
                    "event text and row text share one constant");

            JsonNode code = wireForm(event).get("declineReasonCode");
            assertTrue(code.isString(), "four characters, not a number");
            assertEquals(reason.code(), code.asString(), "the wire form keeps its zero");
        }

        /**
         * Asserts the one reason that resolves no account identifier is refused, and that neither
         * store is touched.
         *
         * <p>{@code app/cbl/CBTRN02C.cbl:L383-L387} assigns reason {@code 0100} before
         * {@code MOVE XREF-ACCT-ID TO FD-ACCT-ID} at {@code :L394} has run. Every
         * {@link TransactionAuthorized} carries the identifier that move copies, so the two states
         * cannot both hold. The guard refuses the call, and no account identity is derived for it.
         */
        @Test
        @DisplayName(
                "the reason that resolves no account identifier is refused, and nothing stores")
        void reasonThatResolvesNoAccountIsRefused() {
            FeedTransaction event = refusal(AMOUNT);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> subject.recordReject(event, DeclineReason.INVALID_CARD_NUMBER),
                    "reason 0100 was recorded for an event that already carries an account"
                            + " identifier, so the two mutually exclusive states were both held");

            assertTrue(refused.getMessage().contains(DeclineReason.INVALID_CARD_NUMBER.code()),
                    "the refusal does not name the reason it refused: " + refused.getMessage());
            assertFalse(refused.getMessage().contains(ACCOUNT_ID),
                    "the refusal echoed the account identifier: " + refused.getMessage());
            verifyNoInteractions(rejectedTransactions);
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("records code 0102, the one the 300 records of dailytran.txt reach")
        void recordsTheOneFixtureReachableCode() {
            subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);

            assertEquals("0102", storedRow().getRejectReasonCode(),
                    ":L407 refuses a record of the feed");
        }
    }

    /**
     * The reject model this recorder admits, and the one it refuses.
     *
     * <p>Reason {@code 0100} is assigned inside the {@code INVALID KEY} limb of the cross-reference
     * read at {@code app/cbl/CBTRN02C.cbl:L383-L387}, before
     * {@code MOVE XREF-ACCT-ID TO FD-ACCT-ID} at {@code :L394} has run, and the gate at
     * {@code :L372} then stops the account read. The three other reasons are assigned after
     * that move. Every {@link TransactionAuthorized} carries the identifier that move copies,
     * so a transaction reaching this recorder and reason {@code 0100} are mutually exclusive.
     *
     * <p>Both layers refuse the combination. {@link RejectRecorder#recordReject} refuses it before
     * any write, and {@link TransactionDeclined} refuses it in its canonical constructor, so
     * no path derives an account identity for a read that resolved none.
     */
    @Nested
    @DisplayName("The reject model, and the account identity no path fabricates")
    class RejectModel {

        /** A second account, so the assertions below read the identity that travelled through. */
        private static final String OTHER_ACCOUNT_ID = "00000000042";

        @Test
        @DisplayName("three of the four reasons resolve an account, and reason 0100 resolves none")
        void threeReasonsResolveAnAccountAndOneResolvesNone() {
            assertEquals(4, DeclineReason.values().length, "four codes and no fifth");
            assertFalse(DeclineReason.INVALID_CARD_NUMBER.resolvesAccount(),
                    "reason 0100 follows a read that resolved no account, at :L383-L387");
            assertTrue(DeclineReason.ACCOUNT_NOT_FOUND.resolvesAccount(),
                    "reason 0101 follows the move at :L394");
            assertTrue(DeclineReason.OVER_CREDIT_LIMIT.resolvesAccount(),
                    "reason 0102 follows the move at :L394");
            assertTrue(DeclineReason.ACCOUNT_EXPIRED.resolvesAccount(),
                    "reason 0103 follows the move at :L394");
        }

        /**
         * Asserts the declined event names the account the refused transaction named.
         *
         * @param reason one reject reason that resolves an account identifier
         */
        @ParameterizedTest
        @EnumSource(value = DeclineReason.class, names = "INVALID_CARD_NUMBER",
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName(
                "the declined event names the refused transaction's own account, and keys on it")
        void theDeclinedEventNamesTheRefusedTransactionsAccount(DeclineReason reason) {
            FeedTransaction refused = feedRecord(OTHER_ACCOUNT_ID,
                    CONSTRUCTED_ID_PREFIX + reason.code(), AMOUNT, SOURCE, DESCRIPTION,
                    MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP);

            subject.recordReject(refused, reason);

            TransactionDeclined event = enqueuedEvent();
            assertEquals(OTHER_ACCOUNT_ID, event.accountId(),
                    "the event named an account the refused transaction never carried");
            assertEquals(refused.accountId(), event.accountId(),
                    "the identity is copied from the input and not derived");
            assertEquals(event.accountId(), event.envelope().aggregateId(),
                    "the account identifier is the key the relay publishes under");
            assertEquals(refused.transactionId(), event.transactionId(),
                    "the event named a transaction the refused input never carried");
        }

        @Test
        @DisplayName(
                "the declined contract itself refuses reason 0100, whatever the caller supplies")
        void theDeclinedContractRefusesReasonZeroOneHundred() {
            IllegalArgumentException refused =
                    assertThrows(IllegalArgumentException.class,
                            () -> TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                                    DeclineReason.INVALID_CARD_NUMBER, AMOUNT, MASKED_CARD_NUMBER),
                            "the contract admitted a decline for a read that resolved no account,"
                                    + " so the impossible model is open again");

            assertTrue(refused.getMessage().contains(DeclineReason.INVALID_CARD_NUMBER.code()),
                    "the refusal does not name the reason it refused: " + refused.getMessage());
            assertFalse(refused.getMessage().contains(ACCOUNT_ID),
                    "the refusal echoed the account identifier: " + refused.getMessage());
            assertFalse(LONG_DIGIT_RUN.matcher(refused.getMessage()).find(),
                    "the refusal carried a long digit run: " + refused.getMessage());
        }

        @Test
        @DisplayName("an unresolved card attempt is the authorization service's row, and no event")
        void anUnresolvedCardAttemptProducesNoEvent() {
            assertThrows(IllegalArgumentException.class,
                    () -> subject.recordReject(refusal(AMOUNT), DeclineReason.INVALID_CARD_NUMBER));

            verifyNoInteractions(rejectedTransactions);
            verifyNoInteractions(outbox);
        }
    }

    @Nested
    @DisplayName("The reject row of app/cbl/CBTRN02C.cbl:L451")
    class RejectRow {

        @Test
        @DisplayName("carries a fresh identifier, since the DALYREJS dataset declares no key")
        void carriesAFreshIdentifier() {
            subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);
            UUID first = storedRow().getId();

            buildRecorder();
            subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);

            assertNotNull(first, "the recorder supplies the Universally Unique Identifier (UUID)");
            assertNotEquals(first, storedRow().getId(),
                    "two refusals store two rows: POSTTRAN.jcl:L34 declares no KEYS");
        }

        @Test
        @DisplayName("carries each field of CVTRA06Y.cpy at its width, leading zeros intact")
        void carriesEachFieldAtItsDeclaredWidth() {
            subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);

            RejectedTransactionEntity row = storedRow();
            assertEquals(PicClause.DALYTRAN_TYPE_CD_WIDTH, row.getTransactionTypeCode().length(),
                    "CVTRA06Y.cpy:L6 PIC X(02)");
            assertEquals(CATEGORY_CODE, row.getTransactionCategoryCode(),
                    ":L7 PIC 9(04) holds category 1 over three zeros");
            assertTrue(FOUR_DIGITS.matcher(row.getTransactionCategoryCode()).matches(),
                    "four decimal digits and nothing else");
            assertEquals(MERCHANT_ID, row.getMerchantId(),
                    ":L11 PIC 9(09) holds nine digits right-justified");
            assertEquals(AUTHORIZED_AT, row.getOriginTimestamp(),
                    ":L16 PIC X(26), shaped YYYY-MM-DD HH:MM:SS.ffffff");
            assertEquals(PicClause.DALYTRAN_ORIG_TS_WIDTH, row.getOriginTimestamp().length(),
                    "twenty-six characters, as :L16 declares");
            assertEquals(MASKED_CARD_NUMBER, row.getMaskedCardNumber(),
                    "the masked form measures the PIC X(16) of :L15");
            assertTrue(MASKED_FORM.matcher(row.getMaskedCardNumber()).matches(),
                    "twelve mask characters then four digits");
            assertFalse(row.getMaskedCardNumber().contains(FULL_CARD_NUMBER.substring(0, 12)),
                    "no leading Primary Account Number (PAN) digit reaches the row");
        }

        @Test
        @DisplayName("stores the amount at the scale S9(09)V99 declares, refund sign intact")
        void storesTheAmountAtTheDeclaredScale() {
            subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);
            RejectedTransactionEntity purchase = storedRow();

            buildRecorder();
            subject.recordReject(refusal(REFUND_AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);
            RejectedTransactionEntity refund = storedRow();

            assertEquals(PicClause.DALYTRAN_AMT_SCALE, purchase.getTransactionAmount().scale(),
                    "CVTRA06Y.cpy:L10 declares two fractional digits");
            assertEquals(AMOUNT, purchase.getTransactionAmount(),
                    "the zoned bytes 0000005047G read as 504.77");
            assertEquals(REFUND_AMOUNT, refund.getTransactionAmount(),
                    "the sign lives here; :L177 holds digits alone");
            assertEquals(-1, refund.getTransactionAmount().signum(),
                    "50 of the 300 records carry a refund");
        }
    }

    @Nested
    @DisplayName("The declined event of app/cbl/CBTRN02C.cbl:L448")
    class DeclinedEvent {

        @Test
        @DisplayName("renders as one flat object of the eleven properties its document requires")
        void rendersAsOneFlatObject() {
            subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);

            JsonNode wire = wireForm(enqueuedEvent());
            List<String> stamps = new ArrayList<>();
            for (String property : wire.propertyNames()) {
                if (property.endsWith("At")) {
                    stamps.add(property);
                }
                assertFalse(wire.get(property).isObject() || wire.get(property).isArray(),
                        "property " + property + " opens a second level");
                assertTrue(wire.get(property).asString().length()
                                < PicClause.VALIDATION_TRAILER_WIDTH,
                        "property " + property + " carries a record half");
            }

            assertEquals(Set.of("eventId", "eventType", "schemaVersion", "occurredAt",
                    "aggregateId", "transactionId", "accountId", "declineReasonCode",
                    "declineReasonDescription", "amount", "maskedCardNumber"),
                    new LinkedHashSet<>(wire.propertyNames()),
                    "transaction-declined-v1.json names eleven required properties, all at one"
                            + " level");
            assertEquals(List.of("occurredAt"), stamps,
                    "the one stamp is the envelope producer stamp");
            assertEquals(ACCOUNT_ID, wire.get("aggregateId").asString(),
                    "the key is the account, leading zeros intact");
            assertTrue(wire.get("aggregateId").asString()
                            .matches(EventEnvelope.AGGREGATE_ID_PATTERN),
                    "CVACT03Y.cpy:L7 XREF-ACCT-ID PIC 9(11) gives eleven digits");
            assertEquals(EventEnvelope.SCHEMA_VERSION, wire.get("schemaVersion").intValue(),
                    "a resolved account travels under version 1");
        }

        @Test
        @DisplayName("carries the amount as a decimal string, and no card number at all")
        void carriesTheAmountAsADecimalStringAndNoCardNumber() {
            subject.recordReject(refusal(REFUND_AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);

            TransactionDeclined event = enqueuedEvent();
            JsonNode amount = wireForm(event).get("amount");
            assertTrue(amount.isString(), "text, never a JSON number");
            assertTrue(amount.asString().matches(TransactionDeclined.AMOUNT_PATTERN),
                    "CVTRA06Y.cpy:L10 S9(09)V99 allows nine digits then two");
            assertEquals(REFUND_AMOUNT.toPlainString(), amount.asString(),
                    "the minus sign survives");

            String rendered = MAPPER.writeValueAsString(event);
            assertFalse(rendered.contains(FULL_CARD_NUMBER.substring(0, 12)),
                    "twelve leading card digits reached the wire");

            // The event identifier is a generated Universally Unique Identifier whose text holds
            // twelve digits in one run often enough to matter, and it names no card and no account.
            String beyondTheEventId = rendered.replace(event.eventId().toString(), "");
            Matcher runs = LONG_DIGIT_RUN.matcher(beyondTheEventId);
            while (runs.find()) {
                assertEquals(TRANSACTION_ID, runs.group(),
                        "the one long run is DALYTRAN-ID PIC X(16), naming no card and no account");
            }
        }
    }

    @Nested
    @DisplayName("The failure path of app/cbl/CBTRN02C.cbl:L452-L464")
    class FailurePath {

        @Test
        @DisplayName("hands a store failure to the caller and enqueues nothing")
        void handsAStoreFailureToTheCaller() {
            when(rejectedTransactions.save(any(RejectedTransactionEntity.class)))
                    .thenThrow(new DataIntegrityViolationException("rejected_transaction refused"));

            assertThrows(DataIntegrityViolationException.class,
                    () -> subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT),
                    ":L452-L464 becomes a failure the caller sees");
            verifyNoInteractions(outbox);
        }

        @Test
        @DisplayName("hands an enqueue failure to the caller after the row is stored")
        void handsAnEnqueueFailureToTheCaller() {
            when(outbox.write(any(TransactionDeclined.class)))
                    .thenThrow(new DataIntegrityViolationException("outbox_event refused"));

            assertThrows(DataIntegrityViolationException.class,
                    () -> subject.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT),
                    "a failed :L448 enqueue reaches the caller holding the :L451 row");
            verify(rejectedTransactions).save(any(RejectedTransactionEntity.class));
        }
    }

    /**
     * The transaction boundary {@link RejectRecorder} opens or joins.
     *
     * <p>The recorder starts a required transaction when none exists and joins a caller's required
     * transaction when one does. The row of {@code app/cbl/CBTRN02C.cbl:L451} and the outbox row
     * beside it therefore commit together or vanish together. The source runs its three updates at
     * {@code app/cbl/CBTRN02C.cbl:L440-L442} with no rollback, and each file definition in
     * {@code app/csd/CARDDEMO.CSD} carries {@code RECOVERY(NONE)}, so atomicity is ADDITIVE.
     *
     * <p>Flyway owns the schema and Hibernate validates its mapping against it at start-up. No
     * broker is reached: a stand-in replaces the producer template.
     */
    @Nested
    @Testcontainers
    @SpringBootTest(properties = {
        // The listener of messaging/TransactionAuthorizedConsumer must not retry an absent broker.
        "spring.kafka.listener.auto-startup=false",
        "TOPIC_DEAD_LETTER_SUFFIX=.DLT",
        // The four credentials application.yml leaves without a default. Each value below is a
        // generated fake this repository states nowhere else, and config/SecurityConfig refuses a
        // blank, published or unprefixed one at start-up.
        "KAFKA_SASL_PASSWORD=a-generated-broker-value-for-the-boundary-test",
        "ADMIN_PASSWORD_HASH={noop}a-generated-admin-value-for-the-boundary-test",
        "USER_PASSWORD_HASH={noop}a-generated-user-value-for-the-boundary-test",
        "MONITORING_PASSWORD_HASH={noop}a-generated-monitoring-value-for-the-boundary-test",
    })
    @DisplayName("The shared transaction over app/cbl/CBTRN02C.cbl:L447-L451")
    class TransactionBoundary {

        /** Login the container creates, and the schema owner Flyway migrates under. */
        private static final String DATABASE_LOGIN = "carddemo_ledger_svc";

        /** A generated value for this run, matching no provider credential shape. */
        private static final String DATABASE_SECRET = "a-generated-database-value-for-the-boundary";

        /** The image tag the compose stack pins. */
        @Container
        static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
                .withDatabaseName("carddemo_ledger")
                .withUsername(DATABASE_LOGIN)
                .withPassword(DATABASE_SECRET);

        static {
            POSTGRES.start();
        }

        /** Replaces the producer template, so the context starts with no broker reachable. */
        @MockitoBean
        private org.springframework.kafka.core.KafkaTemplate<String, Object> producerTemplate;

        /** Prevents the scheduled publisher from racing the transaction-boundary assertions. */
        @MockitoBean
        private OutboxRelay relay;

        private RejectRecorder recorder;
        private RejectedTransactionRepository rows;
        private OutboxEventRepository events;
        private TransactionTemplate boundary;
        private JdbcTemplate database;

        @DynamicPropertySource
        static void containerDatasource(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }

        @BeforeEach
        void resolveBeans(ApplicationContext context) {
            recorder = context.getBean(RejectRecorder.class);
            rows = context.getBean(RejectedTransactionRepository.class);
            events = context.getBean(OutboxEventRepository.class);
            boundary = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            database = context.getBean(JdbcTemplate.class);
        }

        @Test
        @DisplayName("leaves neither row when the caller's transaction rolls back")
        void leavesNeitherRowOnRollback() {
            long rowsBefore = rows.count();
            long eventsBefore = events.count();

            boundary.executeWithoutResult(status -> {
                recorder.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);
                status.setRollbackOnly();
            });

            assertEquals(rowsBefore, rows.count(), "the :L451 row belongs to the caller");
            assertEquals(eventsBefore, events.count(),
                    "no outbox row outlives a rolled-back caller");
        }

        @Test
        @DisplayName("rolls back the reject row when the outbox insert fails")
        void rollsBackTheRejectRowWhenTheOutboxInsertFails() {
            long rowsBefore = rows.count();
            long eventsBefore = events.count();

            database.execute("""
                    CREATE OR REPLACE FUNCTION ledger_service.fail_test_outbox_insert()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        RAISE EXCEPTION 'forced outbox failure';
                    END
                    $$
                    """);
            database.execute("""
                    CREATE TRIGGER fail_test_outbox_insert
                    BEFORE INSERT ON ledger_service.outbox_event
                    FOR EACH ROW
                    EXECUTE FUNCTION ledger_service.fail_test_outbox_insert()
                    """);

            try {
                assertThrows(DataAccessException.class,
                        () -> recorder.recordReject(
                                refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT));
                assertEquals(rowsBefore, rows.count(),
                        "the reject row rolls back with the refused outbox insert");
                assertEquals(eventsBefore, events.count(),
                        "the failed outbox insert leaves no event row");
            } finally {
                database.execute("""
                        DROP TRIGGER IF EXISTS fail_test_outbox_insert
                        ON ledger_service.outbox_event
                        """);
                database.execute("""
                        DROP FUNCTION IF EXISTS ledger_service.fail_test_outbox_insert()
                        """);
            }
        }

        @Test
        @DisplayName("opens a transaction and leaves one row in each table on commit")
        void leavesOneRowInEachTableOnCommit() {
            long rowsBefore = rows.count();
            long eventsBefore = events.count();

            recorder.recordReject(refusal(AMOUNT), DeclineReason.OVER_CREDIT_LIMIT);

            assertEquals(rowsBefore + 1, rows.count(), "the :L451 write stores one reject row");
            assertEquals(eventsBefore + 1, events.count(), "the :L448 trailer enqueues one event");
        }
    }
}
