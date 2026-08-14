package com.carddemo.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.NotificationRenderer.CardholderContext;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationRenderer.TransactionRow;
import com.carddemo.notification.domain.NotificationService.CardholderDetails;
import com.carddemo.notification.entity.NotificationLogEntity;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;

/**
 * Tests how {@link NotificationService} orchestrates one cardholder alert and totals one card.
 *
 * <p>The subject is {@code 4000-TRNXFILE-GET} at {@code app/cbl/CBSTM03A.CBL:L416-L437}, the
 * per-card loop that drives it at {@code app/cbl/CBSTM03A.CBL:L316-L326}, and the cardholder
 * fields {@code 5000-CREATE-STATEMENT} assembles at {@code app/cbl/CBSTM03A.CBL:L458-L504}.
 * Common Business Oriented Language (COBOL) calls a named block of statements a paragraph, and a
 * {@code PERFORM} runs one.
 *
 * <p>Every test drives the service directly over a recording renderer, a recording
 * rendered-alert repository and a stubbed read-model repository. No Spring application context
 * starts, no container runs, no broker runs and no database runs. {@code mvn test} therefore
 * passes on a clean machine with nothing else started.
 *
 * <p>Rendered layout sits outside these tests. {@code PlainTextRendererTest} holds the fixed-width
 * text records, {@code HtmlRendererTest} holds the markup records,
 * {@code NotificationRendererTest} holds the fixed-width helpers, and
 * {@code StatementRowCapTest} holds the ceiling on how many rows one alert may carry.
 *
 * <p>The customer fields this service renders trace to the {@code CUSTREC} copybook that
 * {@code app/cbl/CBSTM03A.CBL:L55} copies. That copybook forks from {@code CVCUS01Y}:
 * {@code app/cpy/CUSTREC.cpy:L19} declares {@code CUST-DOB-YYYYMMDD} while
 * {@code app/cpy/CVCUS01Y.cpy:L19} declares {@code CUST-DOB-YYYY-MM-DD}. The service takes every
 * cardholder field as a call argument, so neither copybook's field naming reaches it.
 */
@DisplayName("NotificationService, the per-card totalling and orchestration of CBSTM03A")
class NotificationServiceTest {

    /**
     * A full card number, at the width {@code DALYTRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA06Y.cpy:L15} declares. No test emits this value into an assertion target;
     * every test proves it does not survive into one.
     *
     * <p>The value is composed without a committed literal, and its four leading digits are
     * {@code 9999}. No card of {@code app/data/ASCII/carddata.txt} begins with them, so no fixture
     * card number is committed to this file.</p>
     */
    private static final String FULL_CARD_NUMBER = syntheticCardNumber("123456789010");

    /**
     * The masked form of {@link #FULL_CARD_NUMBER}, which the rendered-alert row holds for
     * display. Twelve mask characters then four digits.
     */
    private static final String MASKED_CARD_NUMBER = PanMasker.maskCardNumber(FULL_CARD_NUMBER);

    /**
     * The token the read model keys on, which {@code PanMasker.tokenOf} builds and no method
     * reverses. It stands where {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22} carried a full card number.
     */
    private static final String CARD_TOKEN = PanMasker.tokenOf(FULL_CARD_NUMBER);

    /**
     * A second full card number, driving the per-card reset at
     * {@code app/cbl/CBSTM03A.CBL:L325}. Composed the same way as {@link #FULL_CARD_NUMBER}.
     */
    private static final String SECOND_FULL_CARD_NUMBER = syntheticCardNumber("987654321087");

    /**
     * The masked form of {@link #SECOND_FULL_CARD_NUMBER} , held for display as at
     * {@code app/cpy/COSTM01.CPY:L22} .
     */
    private static final String SECOND_MASKED_CARD_NUMBER =
            PanMasker.maskCardNumber(SECOND_FULL_CARD_NUMBER);

    /** The token of {@link #SECOND_FULL_CARD_NUMBER}, which keys its rows. */
    private static final String SECOND_CARD_TOKEN =
            PanMasker.tokenOf(SECOND_FULL_CARD_NUMBER);

    /**
     * A third full card number whose last four digits are the last four of
     * {@link #FULL_CARD_NUMBER}.
     *
     * <p>The two share one masked form and carry two tokens, which is what the isolation tests
     * read.</p>
     */
    private static final String SAME_TAIL_FULL_CARD_NUMBER = syntheticCardNumber("555555559010");

    /** The token of {@link #SAME_TAIL_FULL_CARD_NUMBER}. */
    private static final String SAME_TAIL_CARD_TOKEN =
            PanMasker.cardToken(SAME_TAIL_FULL_CARD_NUMBER);

    /**
     * Composes a card number at the stored width from a twelve-digit serial.
     *
     * @param serial the twelve digits following the {@code 9999} prefix
     * @return sixteen digit characters
     */
    private static String syntheticCardNumber(String serial) {
        return "9999" + serial;
    }

    /**
     * The instant every rendered-alert row this class writes records.
     *
     * <p>{@link #FIXED_CLOCK} answers it on every read, so an assertion on the stored instant is an
     * equality and not a comparison against the wall clock.</p>
     */
    private static final Instant ATTEMPT_INSTANT = Instant.parse("2024-01-15T10:30:01.470Z");

    /** The clock the service under test reads, answering {@link #ATTEMPT_INSTANT} every time. */
    private static final Clock FIXED_CLOCK = Clock.fixed(ATTEMPT_INSTANT, ZoneOffset.UTC);

    /** Tag on a test that pins behaviour the source produces and a corrected one does not. */
    private static final String LEGACY_DIVERGENCE_TAG = "legacy-divergence";

    /** Tag on a test whose expected value stands open for a human decision. */
    private static final String HUMAN_REVIEW_TAG = "human-review";

    /** The row ceiling one read names, matching {@code NotificationRenderer.MAXIMUM_STATEMENT_ROWS}. */
    private static final Limit PAGE_LIMIT =
            Limit.of(NotificationRenderer.MAXIMUM_STATEMENT_ROWS);

    /**
     * A transaction identifier at the width {@code TRNX-ID PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L23} declares.
     */
    private static final String TRANSACTION_ID = "0000000000000042";

    /**
     * The account identifier the {@code MOVE ACCT-ID TO ST-ACCT-ID} at
     * {@code app/cbl/CBSTM03A.CBL:L483} carries.
     */
    private static final String ACCOUNT_ID = "00000000042";

    /** A second account identifier used to prove that histories do not share a scope. */
    private static final String SECOND_ACCOUNT_ID = "00000000043";

    /**
     * The balance the {@code MOVE ACCT-CURR-BAL TO ST-CURR-BAL} at
     * {@code app/cbl/CBSTM03A.CBL:L484} carries.
     */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1750.25");

    /**
     * The scale of a transaction amount, from {@code TRNX-AMT PIC S9(09)V99} at
     * {@code app/cpy/COSTM01.CPY:L29}. Two digits follow the decimal point.
     */
    private static final int AMOUNT_SCALE = 2;

    /**
     * The integer digit positions a summed total holds, from
     * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65} and
     * {@code WS-TRN-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L68}.
     */
    private static final int TOTAL_INTEGER_DIGITS = 9;

    /**
     * The width of an edited total, from {@code ST-TOTAL-TRAMT PIC Z(9).99-} at
     * {@code app/cbl/CBSTM03A.CBL:L142}. Nine digit positions, the decimal point, two decimal
     * digits and one trailing sign position.
     */
    private static final int EDITED_TOTAL_WIDTH = 13;

    /**
     * The stored width of a description, from {@code TRNX-DESC PIC X(100)} at
     * {@code app/cpy/COSTM01.CPY:L28}.
     */
    private static final int STORED_DESCRIPTION_WIDTH = 100;

    /**
     * The rendered width of a description, from {@code ST-TRANDT PIC X(49)} at
     * {@code app/cbl/CBSTM03A.CBL:L135}. The {@code MOVE TRNX-DESC TO ST-TRANDT} at
     * {@code app/cbl/CBSTM03A.CBL:L677} drops the last 51 characters once.
     */
    private static final int RENDERED_DESCRIPTION_WIDTH = 49;

    /**
     * The transaction type code, {@code TRNX-TYPE-CD PIC X(02)} at {@code app/cpy/COSTM01.CPY:L25}
     * .
     */
    private static final String TYPE_CODE = "01";

    /**
     * The transaction category code, {@code TRNX-CAT-CD PIC 9(04)} at
     * {@code app/cpy/COSTM01.CPY:L26} .
     */
    private static final String CATEGORY_CODE = "0001";

    /**
     * Where the transaction entered the platform, {@code TRNX-SOURCE PIC X(10)} at
     * {@code app/cpy/COSTM01.CPY:L27} .
     */
    private static final String SOURCE = "POS";

    /**
     * The merchant identifier, {@code TRNX-MERCHANT-ID PIC 9(09)} at
     * {@code app/cpy/COSTM01.CPY:L30} .
     */
    private static final String MERCHANT_ID = "000000077";

    /**
     * The merchant name, {@code TRNX-MERCHANT-NAME PIC X(50)} at {@code app/cpy/COSTM01.CPY:L31} .
     */
    private static final String MERCHANT_NAME = "SPRINGFIELD HARDWARE";

    /**
     * The merchant city, {@code TRNX-MERCHANT-CITY PIC X(50)} at {@code app/cpy/COSTM01.CPY:L32} .
     */
    private static final String MERCHANT_CITY = "SPRINGFIELD";

    /**
     * The merchant mail code, {@code TRNX-MERCHANT-ZIP PIC X(10)} at
     * {@code app/cpy/COSTM01.CPY:L33} .
     */
    private static final String MERCHANT_ZIP = "62704";

    /**
     * When the transaction originated, {@code TRNX-ORIG-TS PIC X(26)} at
     * {@code app/cpy/COSTM01.CPY:L34} .
     */
    private static final String ORIGIN_TIMESTAMP = "2024-03-14 08.15.22.470000";

    /**
     * When the platform recorded it, {@code TRNX-PROC-TS PIC X(26)} at
     * {@code app/cpy/COSTM01.CPY:L35} .
     */
    private static final String PROCESSING_TIMESTAMP = "2024-03-14 08.15.23.510000";

    /**
     * The value a recording renderer returns for a plain-text alert, standing in for the records
     * the writes at {@code app/cbl/CBSTM03A.CBL:L488-L502} emit.
     */
    private static final String TEXT_ALERT = "recorded-text-alert";

    /**
     * The value a recording renderer returns for a markup alert, standing in for the records
     * {@code 5100-WRITE-HTML-HEADER} at {@code app/cbl/CBSTM03A.CBL:L506} begins.
     */
    private static final String MARKUP_ALERT = "recorded-markup-alert";

    /**
     * The risk score a fraud alert reports. ADDITIVE: {@code ST-LINE14} at
     * {@code app/cbl/CBSTM03A.CBL:L132-L137} carries no score.
     */
    private static final int RISK_SCORE = 82;

    /**
     * The rules a fraud alert reports. ADDITIVE: {@code ST-LINE14} at
     * {@code app/cbl/CBSTM03A.CBL:L132-L137} carries no rule identifier.
     */
    private static final List<String> TRIGGERED_RULES = List.of("VELOCITY", "AMOUNT_ANOMALY");

    /**
     * Type-name fragments that would name a delivery gateway, a producer or an outbox relay. The
     * source writes its statement to a sequential dataset at
     * {@code app/cbl/CBSTM03A.CBL:L488-L502} and reaches no such collaborator.
     */
    private static final List<String> GATEWAY_TYPE_FRAGMENTS = List.of(
            "mailsender", "javamail", "kafkatemplate", "kafkaproducer", "resttemplate",
            "restclient", "webclient", "restoperations", "messagingtemplate", "smsclient",
            "pushclient", "producer", "publisher", "outbox", "relay", "eventbus", "notifier");

    /**
     * Package prefixes of the five sibling services. The aggregator's enforcer
     * {@code bannedDependencies} rule bars each one at the {@code validate} phase, and these
     * assertions hold the same line inside one module. The source reads only the four copybooks
     * it copies at {@code app/cbl/CBSTM03A.CBL:L51-L57} and calls no other program.
     */
    private static final List<String> FOREIGN_SERVICE_PACKAGES = List.of(
            "com.carddemo.authorization.", "com.carddemo.ledger.", "com.carddemo.fraud.",
            "com.carddemo.account.", "com.carddemo.card.");

    /**
     * Records what one renderer is handed, standing in for the two output paths of
     * {@code app/cbl/CBSTM03A.CBL:L458-L504} .
     */
    private RecordingRenderer textRenderer;

    /**
     * Captures the rows the {@code save} of {@code app/cbl/CBSTM03A.CBL:L488-L502} has no ancestor
     * for.
     */
    private RecordingAttemptLog attemptLog;

    /**
     * Supplies one card's rows in the order {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at
     * {@code app/jcl/CREASTMT.JCL:L53} produces.
     */
    private StatementTransactionRepository statementTransactions;

    /**
     * The meter holder the service increments once per alert, registered as at
     * {@code app/cbl/CBSTM03A.CBL:L488} .
     */
    private NotificationMetrics metrics;

    /**
     * Builds the four collaborators the service constructor takes.
     *
     * <p>The meter holder comes from the configuration class over a plain in-memory registry, so
     * no application context starts. The read-model repository is a stub whose unstubbed finder
     * answers an empty list, which is the state a card with no rows reaches at
     * {@code app/cbl/CBSTM03A.CBL:L417-L432}.</p>
     */
    @BeforeEach
    void createCollaborators() {
        this.textRenderer = new RecordingRenderer(RenderedFormat.PLAIN_TEXT, TEXT_ALERT);
        this.attemptLog = new RecordingAttemptLog();
        this.statementTransactions = Mockito.mock(StatementTransactionRepository.class);
        this.metrics = new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry());
    }

    /**
     * Builds the service over the supplied renderers and the collaborators of
     * {@link #createCollaborators()}.
     *
     * <p>One instance serves one card at a time, as the loop at
     * {@code app/cbl/CBSTM03A.CBL:L316-L326} does.</p>
     *
     * @param renderers the renderers to inject, in the order supplied
     * @return the service under test
     */
    private NotificationService serviceWith(NotificationRenderer... renderers) {
        return new NotificationService(List.of(renderers), this.statementTransactions,
                this.attemptLog, this.metrics, FIXED_CLOCK);
    }

    /**
     * Stubs the read-model finder for one card token, at the limit the service names.
     *
     * <p>{@code rows} is supplied in the order the sort at {@code app/jcl/CREASTMT.JCL:L53}
     * produces, ascending by card then transaction identifier, because that is the order the alert
     * presents. The service reads from the newest end so that a bounded alert always carries the
     * transaction it reports, so this stubs the descending finder with the reverse of what the caller
     * supplied and the service reverses it back. It answers only for {@link #PAGE_LIMIT}, so a
     * service that read without a limit would find no stubbed answer.</p>
     *
     * <p>{@code rows} is the card's whole history, so this stub applies the row ceiling the way the
     * database does: {@link Limit} reaches the statement as a row count, and a real finder returns at
     * most that many. A stub that answered the whole list would let the service hand a renderer more
     * rows than {@link NotificationRenderer#requireRenderableRowCount(List)} permits, which no
     * deployment can do.</p>
     *
     * @param cardToken the card token the key column holds
     * @param rows the card's rows, oldest first
     */
    private void holdRows(String cardToken, List<StatementTransactionEntity> rows) {
        List<StatementTransactionEntity> newestFirst = new ArrayList<>(rows);
        Collections.reverse(newestFirst);
        List<StatementTransactionEntity> upToTheCeiling = newestFirst.subList(0,
                Math.min(newestFirst.size(), NotificationRenderer.MAXIMUM_STATEMENT_ROWS));
        Mockito.when(this.statementTransactions
                .findByIdCardTokenOrderByIdTransactionIdDesc(cardToken, PAGE_LIMIT))
                .thenReturn(List.copyOf(upToTheCeiling));
    }

    /**
     * Builds one read-model row of {@link #CARD_TOKEN}.
     *
     * <p>Every column comes from {@code 01 TRNX-RECORD.} at {@code app/cpy/COSTM01.CPY:L20-L36}.
     * The amount carries the scale {@code TRNX-AMT PIC S9(09)V99} at
     * {@code app/cpy/COSTM01.CPY:L29} declares.</p>
     *
     * @param transactionId the transaction identifier, at the width
     *        {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} declares
     * @param description the description, at its stored width
     * @param amount the amount, in decimal text at scale 2
     * @return the row
     */
    private static StatementTransactionEntity row(String transactionId, String description,
            String amount) {
        return rowOfCard(CARD_TOKEN, MASKED_CARD_NUMBER, transactionId, description, amount);
    }

    /**
     * Builds one read-model row of any card.
     *
     * <p>The composite key is the group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21},
     * the card then the transaction identifier. The card half is the token, and the masked form
     * travels beside it as display data.</p>
     *
     * @param cardToken the card token the key column holds
     * @param maskedCardNumber the masked card number the display column holds
     * @param transactionId the transaction identifier, at the width
     *        {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} declares
     * @param description the description, at its stored width
     * @param amount the amount, in decimal text at scale 2
     * @return the row
     */
    private static StatementTransactionEntity rowOfCard(String cardToken,
            String maskedCardNumber, String transactionId, String description, String amount) {
        return new StatementTransactionEntity(
                new StatementTransactionId(cardToken, transactionId), maskedCardNumber,
                TYPE_CODE, CATEGORY_CODE, SOURCE, description, new BigDecimal(amount),
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                ORIGIN_TIMESTAMP, PROCESSING_TIMESTAMP);
    }

    /**
     * Builds a description at the stored width of {@code TRNX-DESC PIC X(100)} at
     * {@code app/cpy/COSTM01.CPY:L28}.
     *
     * <p>The first 49 characters repeat {@code head} and the remaining 51 repeat {@code tail}, so
     * the drop the {@code MOVE} at {@code app/cbl/CBSTM03A.CBL:L677} performs is visible in the
     * value.</p>
     *
     * @param head the character filling the 49 characters that survive
     * @param tail the character filling the 51 characters that fall away
     * @return a description of exactly 100 characters
     */
    private static String storedDescription(char head, char tail) {
        return String.valueOf(head).repeat(RENDERED_DESCRIPTION_WIDTH)
                + String.valueOf(tail)
                        .repeat(STORED_DESCRIPTION_WIDTH - RENDERED_DESCRIPTION_WIDTH);
    }

    /**
     * Builds the cardholder fields {@code 5000-CREATE-STATEMENT} reads at
     * {@code app/cbl/CBSTM03A.CBL:L462-L485}.
     *
     * @return one set of cardholder fields
     */
    private static CardholderDetails cardholder() {
        return new CardholderDetails("JOHN", "Q", "PUBLIC", "12 MAIN STREET", "SUITE 400",
                "SPRINGFIELD", "IL", "USA", "62704", "742");
    }

    /**
     * Builds a second, different set of the cardholder fields at
     * {@code app/cbl/CBSTM03A.CBL:L462-L485}.
     *
     * @return a second set of cardholder fields
     */
    private static CardholderDetails secondCardholder() {
        return new CardholderDetails("MARIA", "T", "SANTOS", "48 ELM AVENUE", "UNIT 6",
                "AURORA", "CO", "USA", "80014", "688");
    }

    /**
     * Folds a list of amounts the way {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at
     * {@code app/cbl/CBSTM03A.CBL:L429} folds them, with plain {@link BigDecimal}.
     *
     * <p>The store happens once per row into a field of scale 2, so the rounding mode applies
     * once per row and not once over the whole sum.</p>
     *
     * @param mode the rounding mode each store applies
     * @param amounts the row amounts in the order they arrive
     * @return the folded total at scale 2
     */
    private static BigDecimal foldPerRow(RoundingMode mode, List<BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO.setScale(AMOUNT_SCALE, mode);
        for (BigDecimal amount : amounts) {
            total = total.add(amount).setScale(AMOUNT_SCALE, mode);
        }
        return total;
    }

    /**
     * Collects every type one class names in a declared field, constructor or method.
     *
     * <p>The statement path of {@code app/cbl/CBSTM03A.CBL:L458-L504} reaches a dataset and no
     * gateway, so the set this method returns is where a gateway collaborator would show up.</p>
     *
     * @param subject the class to inspect
     * @return the types named, with an array reduced to its component type
     */
    private static Set<Class<?>> typesNamedBy(Class<?> subject) {
        Set<Class<?>> named = new LinkedHashSet<>();
        for (Field field : subject.getDeclaredFields()) {
            named.add(componentTypeOf(field.getType()));
        }
        for (Constructor<?> constructor : subject.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                named.add(componentTypeOf(parameter));
            }
        }
        for (Method method : subject.getDeclaredMethods()) {
            named.add(componentTypeOf(method.getReturnType()));
            for (Class<?> parameter : method.getParameterTypes()) {
                named.add(componentTypeOf(parameter));
            }
        }
        return named;
    }

    /**
     * Reduces an array type to the type of its elements.
     *
     * <p>An array of one type carries the same reachability as the type itself, in the same sense
     * that {@code WS-TRAN-TBL OCCURS 10 TIMES} at {@code app/cbl/CBSTM03A.CBL:L228} carries the
     * reachability of {@code WS-TRAN-NUM} at {@code app/cbl/CBSTM03A.CBL:L229}.</p>
     *
     * @param type the type to reduce
     * @return the element type of an array, or {@code type} itself
     */
    private static Class<?> componentTypeOf(Class<?> type) {
        Class<?> reduced = type;
        while (reduced.isArray()) {
            reduced = reduced.getComponentType();
        }
        return reduced;
    }

    /**
     * Collects the lower-case names of every declared field and method of one class.
     *
     * <p>A member naming a value the source stores in the clear, such as
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}, would show up here.</p>
     *
     * @param subject the class to inspect
     * @return the member names, in lower case
     */
    private static List<String> declaredMemberNames(Class<?> subject) {
        List<String> names = new ArrayList<>();
        for (Field field : subject.getDeclaredFields()) {
            names.add(field.getName().toLowerCase());
        }
        for (Method method : subject.getDeclaredMethods()) {
            names.add(method.getName().toLowerCase());
        }
        return names;
    }

    /**
     * One call the service made to {@link NotificationRenderer#renderStatementAlert}.
     *
     * <p>The three components are the fields the trailer writes at
     * {@code app/cbl/CBSTM03A.CBL:L435-L437} and the detail writes at
     * {@code app/cbl/CBSTM03A.CBL:L679} read.</p>
     *
     * @param context the cardholder fields of {@code app/cbl/CBSTM03A.CBL:L462-L485}
     * @param rows the detail rows of {@code app/cbl/CBSTM03A.CBL:L676-L678}
     * @param total the value {@code ST-TOTAL-TRAMT} at {@code app/cbl/CBSTM03A.CBL:L142} carries
     */
    private record StatementAlertCall(CardholderContext context, List<TransactionRow> rows,
            BigDecimal total) {
    }

    /**
     * One call the service made to {@link NotificationRenderer#renderFraudAlert}.
     *
     * <p>ADDITIVE. {@code app/cbl/CBSTM03A.CBL} carries no fraud concept, so only the cardholder
     * fields of {@code app/cbl/CBSTM03A.CBL:L462-L485} trace to the source.</p>
     *
     * @param context the cardholder fields of {@code app/cbl/CBSTM03A.CBL:L462-L485}
     * @param transactionId the flagged transaction
     * @param riskScore the score the fraud service assigned
     * @param triggeredRules the identifiers of the rules that fired
     */
    private record FraudAlertCall(CardholderContext context, String transactionId, int riskScore,
            List<String> triggeredRules) {
    }

    /**
     * A renderer that records what it is handed and returns a fixed value.
     *
     * <p>Stands in for the two output paths of {@code 5000-CREATE-STATEMENT} at
     * {@code app/cbl/CBSTM03A.CBL:L458-L504}: the plain-text writes beginning at
     * {@code app/cbl/CBSTM03A.CBL:L488} and the markup block at
     * {@code app/cbl/CBSTM03A.CBL:L486}. The format it reports arrives in its constructor, so one
     * class serves either format.</p>
     */
    private static final class RecordingRenderer implements NotificationRenderer {

        /**
         * The format this renderer reports through {@link #format()}, one per output file of
         * {@code app/cbl/CBSTM03A.CBL:L44-L47}.
         */
        private final RenderedFormat declaredFormat;

        /**
         * The value both render operations return, standing in for the records the writes at
         * {@code app/cbl/CBSTM03A.CBL:L488-L502} emit.
         */
        private final String renderedValue;

        /**
         * Every statement-alert call, in the order the service made them, one per pass of the loop
         * at {@code app/cbl/CBSTM03A.CBL:L317-L329}.
         */
        private final List<StatementAlertCall> statementCalls = new ArrayList<>();

        /**
         * Every fraud-alert call, in the order the service made them. ADDITIVE, with no ancestor
         * paragraph in {@code app/cbl/CBSTM03A.CBL:L458-L504}.
         */
        private final List<FraudAlertCall> fraudCalls = new ArrayList<>();

        /**
         * Takes the format to report and the value to return.
         *
         * <p>The source declares one output file per format, at
         * {@code app/cbl/CBSTM03A.CBL:L44-L47}.</p>
         *
         * @param declaredFormat the format {@link #format()} answers
         * @param renderedValue the value both render operations answer
         */
        RecordingRenderer(RenderedFormat declaredFormat, String renderedValue) {
            this.declaredFormat = declaredFormat;
            this.renderedValue = renderedValue;
        }

        @Override
        public RenderedFormat format() {
            return this.declaredFormat;
        }

        @Override
        public String renderStatementAlert(CardholderContext context, List<TransactionRow> rows,
                BigDecimal total) {
            this.statementCalls.add(new StatementAlertCall(context, List.copyOf(rows), total));
            return this.renderedValue;
        }

        @Override
        public String renderFraudAlert(CardholderContext context, String transactionId,
                int riskScore, List<String> triggeredRules) {
            this.fraudCalls.add(new FraudAlertCall(context, transactionId, riskScore,
                    List.copyOf(triggeredRules)));
            return this.renderedValue;
        }

        /**
         * Returns the one statement-alert call this renderer received.
         *
         * @return the single call, which the trailer writes at
         *         {@code app/cbl/CBSTM03A.CBL:L435-L437} correspond to
         */
        StatementAlertCall onlyStatementCall() {
            assertThat(this.statementCalls).hasSize(1);
            return this.statementCalls.get(0);
        }

        /**
         * Returns every statement-alert call in order.
         *
         * @return the calls, one per pass of the loop at {@code app/cbl/CBSTM03A.CBL:L317-L329}
         */
        List<StatementAlertCall> statementCalls() {
            return List.copyOf(this.statementCalls);
        }

        /**
         * Returns every fraud-alert call in order.
         *
         * @return the calls, which carry only the cardholder fields of
         *         {@code app/cbl/CBSTM03A.CBL:L462-L485} from the source
         */
        List<FraudAlertCall> fraudCalls() {
            return List.copyOf(this.fraudCalls);
        }
    }

    /**
     * A rendered-alert repository that keeps every row handed to it.
     *
     * <p>ADDITIVE. {@code app/cbl/CBSTM03A.CBL} writes its statement records at
     * {@code app/cbl/CBSTM03A.CBL:L488-L502} and records nothing about the write, so the aggregate
     * this fake stands for has no source ancestor.</p>
     */
    private static final class RecordingAttemptLog implements NotificationLogRepository {

        /**
         * Answers the bounded retention delete by removing nothing.
         *
         * @param horizon ignored
         * @param limit   ignored
         * @return 0, because this fake holds every row handed to it
         */
        @Override
        public int deleteRenderedBefore(java.time.Instant horizon, int limit) {
            return 0;
        }

        /**
         * Every row saved, in the order the service saved them. ADDITIVE: the writes at
         * {@code app/cbl/CBSTM03A.CBL:L488-L502} record nothing about themselves.
         */
        private final List<NotificationLogEntity> savedRows = new ArrayList<>();

        @Override
        public NotificationLogEntity save(NotificationLogEntity attempt) {
            this.savedRows.add(attempt);
            return attempt;
        }

        @Override
        public long count() {
            return this.savedRows.size();
        }

        /**
         * Returns every saved row in order.
         *
         * @return the rows, none of which carries a rendered document, as
         *         {@code app/cbl/CBSTM03A.CBL:L488-L502} records none
         */
        List<NotificationLogEntity> savedRows() {
            return List.copyOf(this.savedRows);
        }
    }

    /**
     * A read-model row whose amount carries a third decimal digit.
     *
     * <p>{@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29} holds two decimal
     * digits, and the column of the read model holds the same two. A third digit reaches the
     * addition at {@code app/cbl/CBSTM03A.CBL:L429} only through an override, so this subclass
     * supplies one and the truncation the store applies becomes observable.</p>
     */
    private static final class UnroundedAmountRow extends StatementTransactionEntity {

        /** The composite key of {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}. */
        private final StatementTransactionId key;

        /** The description of {@code TRNX-DESC PIC X(100)} at {@code app/cpy/COSTM01.CPY:L28}. */
        private final String storedDescription;

        /** An amount carrying more decimal digits than {@code app/cpy/COSTM01.CPY:L29} holds. */
        private final BigDecimal unroundedAmount;

        /**
         * Takes the identifier and the amount.
         *
         * @param transactionId the transaction identifier, at the width
         *        {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} declares
         * @param unroundedAmount the amount, at any scale, whose stored form is
         *        {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29}
         */
        UnroundedAmountRow(String transactionId, BigDecimal unroundedAmount) {
            this.key = new StatementTransactionId(CARD_TOKEN, transactionId);
            this.storedDescription = storedDescription('U', 'V');
            this.unroundedAmount = unroundedAmount;
        }

        @Override
        public StatementTransactionId getId() {
            return this.key;
        }

        @Override
        public String getDescription() {
            return this.storedDescription;
        }

        @Override
        public BigDecimal getAmount() {
            return this.unroundedAmount;
        }
    }

    /**
     * A read-model row that fails when its amount is read.
     *
     * <p>The write at {@code app/cbl/CBSTM03A.CBL:L428} precedes the addition at
     * {@code app/cbl/CBSTM03A.CBL:L429}, and both sit inside the loop that closes at
     * {@code app/cbl/CBSTM03A.CBL:L430}. A row that fails inside that loop reaches neither the
     * trailer writes at {@code app/cbl/CBSTM03A.CBL:L435-L437} nor anything after them.</p>
     */
    private static final class FailingAmountRow extends StatementTransactionEntity {

        /**
         * The text the raised fault carries, standing in for a fault inside the loop that closes at
         * {@code app/cbl/CBSTM03A.CBL:L430}.
         */
        static final String FAULT_MESSAGE = "the row refused to answer its amount";

        /** The composite key of {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}. */
        private final StatementTransactionId key;

        /**
         * Takes the identifier.
         *
         * @param transactionId the transaction identifier, at the width
         *        {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} declares
         */
        FailingAmountRow(String transactionId) {
            this.key = new StatementTransactionId(CARD_TOKEN, transactionId);
        }

        @Override
        public StatementTransactionId getId() {
            return this.key;
        }

        @Override
        public String getDescription() {
            return storedDescription('F', 'G');
        }

        @Override
        public BigDecimal getAmount() {
            throw new IllegalStateException(FAULT_MESSAGE);
        }
    }

    /**
     * Holds how the service picks a renderer out of the collection it was given.
     *
     * <p>{@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504} runs both output
     * paths from one paragraph: the markup block at {@code app/cbl/CBSTM03A.CBL:L486} and the
     * plain-text writes beginning at {@code app/cbl/CBSTM03A.CBL:L488}. The service reaches each
     * path through the format its renderer reports.</p>
     *
     * <p>These tests hand the service a renderer class that no production code names, then ask for
     * the format that class reports. A rendered format therefore arrives with its class and needs
     * no edit to the service.</p>
     */
    @Nested
    @DisplayName("Renderer selection")
    class RendererSelection {

        /**
         * Holds that a class outside the production set serves the format it reports.
         *
         * <p>The list holds one renderer, and the call asks for the format that renderer reports.
         * The path the service takes corresponds to the plain-text writes beginning at
         * {@code app/cbl/CBSTM03A.CBL:L488}.</p>
         */
        @Test
        @DisplayName("A renderer outside the production set serves the format it reports")
        void aTestLocalRendererServesItsOwnFormat() {
            NotificationService service = serviceWith(textRenderer);

            String alert = service.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(alert).isEqualTo(TEXT_ALERT);
            assertThat(textRenderer.fraudCalls()).hasSize(1);
        }

        /**
         * Holds that both per-event operations select by the reported format.
         *
         * <p>The statement operation corresponds to {@code app/cbl/CBSTM03A.CBL:L458-L504} and the
         * fraud operation has no ancestor there. Both reach the same renderer.</p>
         */
        @Test
        @DisplayName("Both per-event operations reach the renderer that reports the format")
        void bothOperationsSelectByReportedFormat() {
            NotificationService service = serviceWith(textRenderer);

            String posted = service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);
            String flagged = service.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(posted).isEqualTo(TEXT_ALERT);
            assertThat(flagged).isEqualTo(TEXT_ALERT);
            assertThat(textRenderer.statementCalls()).hasSize(1);
            assertThat(textRenderer.fraudCalls()).hasSize(1);
        }

        /**
         * Holds that the order of the injected collection changes nothing.
         *
         * <p>Both output paths of {@code app/cbl/CBSTM03A.CBL:L458-L504} stay reachable whichever
         * order the two renderers arrive in.</p>
         */
        @Test
        @DisplayName("Reversing the injected collection leaves every selection unchanged")
        void selectionSurvivesAReversedCollection() {
            RecordingRenderer markupRenderer =
                    new RecordingRenderer(RenderedFormat.HTML, MARKUP_ALERT);
            NotificationService forwardOrder = serviceWith(textRenderer, markupRenderer);
            NotificationService reversedOrder = serviceWith(markupRenderer, textRenderer);

            assertThat(forwardOrder.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.PLAIN_TEXT))
                    .isEqualTo(TEXT_ALERT);
            assertThat(reversedOrder.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.PLAIN_TEXT))
                    .isEqualTo(TEXT_ALERT);
            assertThat(forwardOrder.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.HTML)).isEqualTo(MARKUP_ALERT);
            assertThat(reversedOrder.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.HTML)).isEqualTo(MARKUP_ALERT);
        }

        /**
         * Holds that a production renderer and a test-local one coexist on distinct formats.
         *
         * <p>The markup answer comes from {@link HtmlRenderer}, whose records begin at
         * {@code app/cbl/CBSTM03A.CBL:L506}. The recording renderer sees no markup call.</p>
         */
        @Test
        @DisplayName("A production renderer and a test-local one each keep their own format")
        void productionAndTestLocalRenderersCoexist() {
            NotificationService service = serviceWith(textRenderer, new HtmlRenderer());

            String text = service.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.PLAIN_TEXT);
            String markup = service.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.HTML);

            assertThat(text).isEqualTo(TEXT_ALERT);
            assertThat(markup).isNotBlank().isNotEqualTo(TEXT_ALERT);
            assertThat(textRenderer.fraudCalls()).hasSize(1);
        }

        /**
         * Holds what the service does for a format no renderer reports.
         *
         * <p>The refusal names the format asked for and every format a renderer does report. No
         * format falls back to the other path of {@code app/cbl/CBSTM03A.CBL:L458-L504}.</p>
         */
        @Test
        @DisplayName("A format no renderer reports is refused, and the refusal names both sides")
        void anUnservedFormatIsRefused() {
            NotificationService service = serviceWith(textRenderer);

            assertThatThrownBy(() -> service.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID,
                    RISK_SCORE, TRIGGERED_RULES, cardholder(), RenderedFormat.HTML))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no renderer reports format")
                    .hasMessageContaining(RenderedFormat.HTML.name())
                    .hasMessageContaining(RenderedFormat.PLAIN_TEXT.name());
            assertThat(textRenderer.fraudCalls()).isEmpty();
        }

        /**
         * Holds that a missing format argument is refused by name.
         *
         * <p>Neither output path of {@code app/cbl/CBSTM03A.CBL:L458-L504} runs for a caller that
         * names no format.</p>
         */
        @Test
        @DisplayName("A missing format argument is refused and the refusal names the argument")
        void aMissingFormatIsRefused() {
            NotificationService service = serviceWith(textRenderer);

            assertThatThrownBy(() -> service.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID,
                    RISK_SCORE, TRIGGERED_RULES, cardholder(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("format must not be null");
        }

        /**
         * Holds that the format enumeration carries two constants and that both are served.
         *
         * <p>{@code app/cbl/CBSTM03A.CBL} declares one output file for each, at
         * {@code app/cbl/CBSTM03A.CBL:L44-L47}. A third constant added without a matching
         * implementation fails the second half of this test.</p>
         */
        @Test
        @DisplayName("The format enumeration carries two constants and both are served")
        void everyDeclaredFormatIsServed() {
            assertThat(RenderedFormat.values()).hasSize(2);
            assertThat(RenderedFormat.values()).extracting(Enum::name)
                    .containsExactlyInAnyOrder("PLAIN_TEXT", "HTML");

            NotificationService service = serviceWith(new PlainTextRenderer(), new HtmlRenderer());
            for (RenderedFormat format : RenderedFormat.values()) {
                assertThatCode(() -> service.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID,
                        RISK_SCORE, TRIGGERED_RULES, cardholder(), format))
                        .doesNotThrowAnyException();
            }
        }

        /**
         * Holds that two renderers reporting one format fail construction.
         *
         * <p>The refusal names the format and both classes. Neither output path of
         * {@code app/cbl/CBSTM03A.CBL:L458-L504} can end up with an ambiguous owner.</p>
         */
        @Test
        @DisplayName("Two renderers reporting one format fail construction, naming both")
        void aDuplicateFormatFailsConstruction() {
            RecordingRenderer duplicate =
                    new RecordingRenderer(RenderedFormat.PLAIN_TEXT, MARKUP_ALERT);

            assertThatThrownBy(() -> serviceWith(textRenderer, duplicate))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("two renderers report format")
                    .hasMessageContaining(RenderedFormat.PLAIN_TEXT.name())
                    .hasMessageContaining(RecordingRenderer.class.getName());
        }
    }

    /**
     * Holds that one set of cardholder fields serves one whole invocation.
     *
     * <p>{@code 5000-CREATE-STATEMENT} assembles those fields once at
     * {@code app/cbl/CBSTM03A.CBL:L462-L485}. The name comes from
     * {@code app/cbl/CBSTM03A.CBL:L462-L469}, address line 1 from
     * {@code app/cbl/CBSTM03A.CBL:L470} and address line 2 from
     * {@code app/cbl/CBSTM03A.CBL:L471}. Address line 3 comes from
     * {@code app/cbl/CBSTM03A.CBL:L472-L481}, the account identifier from
     * {@code app/cbl/CBSTM03A.CBL:L483}, the balance from
     * {@code app/cbl/CBSTM03A.CBL:L484} and the credit score from
     * {@code app/cbl/CBSTM03A.CBL:L485}. The markup block at
     * {@code app/cbl/CBSTM03A.CBL:L486} and the plain-text writes beginning at
     * {@code app/cbl/CBSTM03A.CBL:L488} read the same assembled fields.</p>
     */
    @Nested
    @DisplayName("The shared cardholder fields")
    class SharedCardholderFields {

        /**
         * Holds that every row of one invocation travels under one set of cardholder fields.
         *
         * <p>The three rows correspond to three passes of the inner loop at
         * {@code app/cbl/CBSTM03A.CBL:L422-L430}, and one assembly at
         * {@code app/cbl/CBSTM03A.CBL:L462-L485} precedes all three.</p>
         */
        @Test
        @DisplayName("Three rows of one invocation travel under one set of cardholder fields")
        void oneAssemblyServesEveryRow() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00"),
                    row("0000000000000002", storedDescription('C', 'D'), "20.00"),
                    row("0000000000000003", storedDescription('E', 'F'), "30.00")));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            StatementAlertCall call = textRenderer.onlyStatementCall();
            assertThat(call.rows()).hasSize(3);
            assertThat(call.context()).isNotNull();
        }

        /**
         * Holds that two invocations assemble two separate sets of cardholder fields.
         *
         * <p>The loop at {@code app/cbl/CBSTM03A.CBL:L317-L329} runs
         * {@code PERFORM 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L323} once per
         * card, so no field of one card survives into the next.</p>
         */
        @Test
        @DisplayName("Two invocations assemble two separate sets of cardholder fields")
        void twoInvocationsAssembleSeparateFields() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00")));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);
            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, secondCardholder(), RenderedFormat.PLAIN_TEXT);
            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            List<StatementAlertCall> calls = textRenderer.statementCalls();
            assertThat(calls).hasSize(3);
            assertThat(calls.get(1).context()).isNotEqualTo(calls.get(0).context());
            assertThat(calls.get(2).context()).isNotSameAs(calls.get(0).context());
        }

        /**
         * Holds every component the assembly at {@code app/cbl/CBSTM03A.CBL:L462-L485} fills.
         *
         * <p>Each component reaches the renderer at the width its statement field declares, and
         * each carries the value of the argument the caller supplied.</p>
         */
        @Test
        @DisplayName("Every assembled component reaches the renderer at its statement field width")
        void everyComponentReachesTheRendererAtItsWidth() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "70.35")));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            CardholderContext context = textRenderer.onlyStatementCall().context();
            assertThat(context.assembledName())
                    .isEqualTo(NotificationRenderer.assembleName("JOHN", "Q", "PUBLIC"))
                    .hasSize(NotificationRenderer.ST_NAME_WIDTH);
            assertThat(context.addressLine1())
                    .isEqualTo(NotificationRenderer.pic("12 MAIN STREET",
                            NotificationRenderer.ST_ADD1_WIDTH))
                    .hasSize(NotificationRenderer.ST_ADD1_WIDTH);
            assertThat(context.addressLine2())
                    .isEqualTo(NotificationRenderer.pic("SUITE 400",
                            NotificationRenderer.ST_ADD2_WIDTH))
                    .hasSize(NotificationRenderer.ST_ADD2_WIDTH);
            assertThat(context.addressLine3())
                    .isEqualTo(NotificationRenderer.assembleAddress3("SPRINGFIELD", "IL", "USA",
                            "62704"))
                    .hasSize(NotificationRenderer.ST_ADD3_WIDTH);
            assertThat(context.accountId())
                    .isEqualTo(NotificationRenderer.pic(ACCOUNT_ID,
                            NotificationRenderer.ST_ACCT_ID_WIDTH))
                    .hasSize(NotificationRenderer.ST_ACCT_ID_WIDTH);
            assertThat(context.editedCurrentBalance())
                    .isEqualTo(NotificationRenderer.editTrailingSign9(CURRENT_BALANCE))
                    .hasSize(EDITED_TOTAL_WIDTH);
            assertThat(context.ficoScore())
                    .isEqualTo(NotificationRenderer.pic("742",
                            NotificationRenderer.ST_FICO_SCORE_WIDTH))
                    .hasSize(NotificationRenderer.ST_FICO_SCORE_WIDTH);
        }

        /**
         * Holds that a description loses its tail once, before the row reaches the renderer.
         *
         * <p>{@code MOVE TRNX-DESC TO ST-TRANDT} at {@code app/cbl/CBSTM03A.CBL:L677} carries a
         * value of {@code TRNX-DESC PIC X(100)} at {@code app/cpy/COSTM01.CPY:L28} into
         * {@code ST-TRANDT PIC X(49)} at {@code app/cbl/CBSTM03A.CBL:L135}. Both output paths then
         * read the shortened value: the plain-text detail write at
         * {@code app/cbl/CBSTM03A.CBL:L679} and the markup description cell at
         * {@code app/cbl/CBSTM03A.CBL:L700}.</p>
         */
        @Test
        @DisplayName("A description loses its tail once and the stored column keeps 100")
        void aDescriptionIsShortenedOnceAndSharedByBothPaths() {
            StatementTransactionEntity stored =
                    row("0000000000000001", storedDescription('A', 'B'), "70.35");
            holdRows(CARD_TOKEN, List.of(stored));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            TransactionRow rendered = textRenderer.onlyStatementCall().rows().get(0);
            assertThat(rendered.description())
                    .hasSize(RENDERED_DESCRIPTION_WIDTH)
                    .isEqualTo("A".repeat(RENDERED_DESCRIPTION_WIDTH))
                    .doesNotContain("B");
            assertThat(stored.getDescription()).hasSize(STORED_DESCRIPTION_WIDTH).contains("B");
        }
    }

    /**
     * Holds that the running total starts at zero for every card.
     *
     * <p>{@code 1000-MAINLINE} at {@code app/cbl/CBSTM03A.CBL:L316} drives one card per pass of
     * the loop at {@code app/cbl/CBSTM03A.CBL:L317}. Each pass runs
     * {@code PERFORM 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L323},
     * {@code MOVE 1 TO CR-JMP} at {@code app/cbl/CBSTM03A.CBL:L324},
     * {@code MOVE ZERO TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L325} and
     * {@code PERFORM 4000-TRNXFILE-GET} at {@code app/cbl/CBSTM03A.CBL:L326}, in that order. The
     * header fields are therefore assembled before the total is cleared, and the clearing happens
     * once per card.</p>
     */
    @Nested
    @DisplayName("The per-card total reset")
    class PerCardTotalReset {

        /**
         * Holds that a second card's total excludes the first card's amounts.
         *
         * <p>Two passes of the loop at {@code app/cbl/CBSTM03A.CBL:L317-L329} clear the total twice
         * at {@code app/cbl/CBSTM03A.CBL:L325}.</p>
         */
        @Test
        @DisplayName("A second card's total excludes every amount of the first card")
        void eachCardKeepsItsOwnTotal() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00"),
                    row("0000000000000002", storedDescription('C', 'D'), "20.00")));
            holdRows(SECOND_CARD_TOKEN, List.of(
                    rowOfCard(SECOND_CARD_TOKEN, SECOND_MASKED_CARD_NUMBER, "0000000000000004",
                            storedDescription('G', 'H'), "5.50")));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);
            service.renderPostedTransactionAlert(SECOND_CARD_TOKEN, SECOND_FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            List<StatementAlertCall> calls = textRenderer.statementCalls();
            assertThat(calls).hasSize(2);
            assertThat(calls.get(0).total()).isEqualByComparingTo("30.00");
            assertThat(calls.get(1).total()).isEqualByComparingTo("5.50");
        }

        /**
         * Holds that the header fields carry no value drawn from the accumulation.
         *
         * <p>{@code MOVE ACCT-CURR-BAL TO ST-CURR-BAL} at {@code app/cbl/CBSTM03A.CBL:L484} runs
         * inside {@code 5000-CREATE-STATEMENT}, which the loop performs at
         * {@code app/cbl/CBSTM03A.CBL:L323}, one statement ahead of the clearing at
         * {@code app/cbl/CBSTM03A.CBL:L325}. The balance the renderer reads therefore comes from
         * the account, and the total comes from the rows.</p>
         */
        @Test
        @DisplayName("The header balance comes from the account and never from the row total")
        void theHeaderBalanceIsAssembledAheadOfTheReset() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00"),
                    row("0000000000000002", storedDescription('C', 'D'), "20.00")));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            StatementAlertCall call = textRenderer.onlyStatementCall();
            assertThat(call.context().editedCurrentBalance())
                    .isEqualTo(NotificationRenderer.editTrailingSign9(CURRENT_BALANCE));
            assertThat(call.total()).isEqualByComparingTo("30.00");
            assertThat(call.context().editedCurrentBalance().trim())
                    .isNotEqualTo(NotificationRenderer.editTrailingSignZ(call.total()).trim());
        }

        /**
         * Holds what a card with no rows produces.
         *
         * <p>The loops at {@code app/cbl/CBSTM03A.CBL:L417-L432} add nothing, and the trailer
         * writes at {@code app/cbl/CBSTM03A.CBL:L435-L437} still run. The total keeps the value
         * {@code MOVE ZERO TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L325} left.</p>
         */
        @Test
        @DisplayName("A card with no rows totals zero and still reaches the renderer once")
        void aCardWithNoRowsTotalsZero() {
            holdRows(CARD_TOKEN, List.of());
            NotificationService service = serviceWith(textRenderer);

            String alert = service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            StatementAlertCall call = textRenderer.onlyStatementCall();
            assertThat(alert).isEqualTo(TEXT_ALERT);
            assertThat(call.rows()).isEmpty();
            assertThat(call.total()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(call.total().scale()).isEqualTo(AMOUNT_SCALE);
            assertThat(NotificationRenderer.editTrailingSignZ(call.total()))
                    .hasSize(EDITED_TOTAL_WIDTH);
        }
    }

    /**
     * Holds the order in which rows render and amounts accumulate.
     *
     * <p>{@code 4000-TRNXFILE-GET} at {@code app/cbl/CBSTM03A.CBL:L416} walks one card with an
     * outer loop at {@code app/cbl/CBSTM03A.CBL:L417-L432} and an inner loop at
     * {@code app/cbl/CBSTM03A.CBL:L422-L430}. Inside the inner loop,
     * {@code PERFORM 6000-WRITE-TRANS} at {@code app/cbl/CBSTM03A.CBL:L428} precedes
     * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429}. The total then
     * takes one hop through an intermediate field:
     * {@code MOVE WS-TOTAL-AMT TO WS-TRN-AMT} at {@code app/cbl/CBSTM03A.CBL:L433}, then
     * {@code MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT} at {@code app/cbl/CBSTM03A.CBL:L434}, and the
     * trailer writes follow at {@code app/cbl/CBSTM03A.CBL:L435-L437}.</p>
     *
     * <p>A detail line carries the amount of its own row alone. {@code ST-LINE14} at
     * {@code app/cbl/CBSTM03A.CBL:L132-L137} holds {@code ST-TRANAMT}, filled by
     * {@code MOVE TRNX-AMT TO ST-TRANAMT} at {@code app/cbl/CBSTM03A.CBL:L678}, and no running
     * total reaches it.</p>
     *
     * <p>{@code ST-TOTAL-TRAMT} has no markup counterpart. The identifier appears at exactly two
     * places in the 924 lines of {@code app/cbl/CBSTM03A.CBL}: its declaration at
     * {@code app/cbl/CBSTM03A.CBL:L142} and the move at {@code app/cbl/CBSTM03A.CBL:L434}. No
     * {@code STRING} statement names it, so the markup trailer carries no total.
     * {@code HtmlRendererTest} holds that absence in the emitted markup.</p>
     */
    @Nested
    @DisplayName("Row order and accumulation order")
    class RowAndAccumulationOrder {

        /**
         * The row amounts these tests use, in the order the sort at
         * {@code app/jcl/CREASTMT.JCL:L53} produces.
         */
        private static final List<String> ROW_AMOUNTS =
                List.of("7.11", "13.27", "21.43", "35.61");

        /**
         * The running totals after rows two, three and four. No detail line carries one:
         * {@code ST-TRANAMT} at {@code app/cbl/CBSTM03A.CBL:L137} holds one row's own amount.
         */
        private static final List<String> LATER_PARTIAL_TOTALS =
                List.of("20.38", "41.81", "77.42");

        /**
         * Builds the four rows of {@link #ROW_AMOUNTS} in ascending transaction-identifier order.
         *
         * @return the rows, keyed as {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}
         *         declares
         */
        private List<StatementTransactionEntity> ascendingRows() {
            List<StatementTransactionEntity> rows = new ArrayList<>(ROW_AMOUNTS.size());
            for (int index = 0; index < ROW_AMOUNTS.size(); index++) {
                rows.add(row(String.format("%016d", index + 1),
                        storedDescription((char) ('A' + index), 'Z'), ROW_AMOUNTS.get(index)));
            }
            return rows;
        }

        /**
         * Holds that rows render in the order the finder answers.
         *
         * <p>The finder answers in the order the sort at {@code app/jcl/CREASTMT.JCL:L53}
         * produces, and the inner loop at {@code app/cbl/CBSTM03A.CBL:L422-L430} keeps it.</p>
         */
        @Test
        @DisplayName("Rows render in the ascending transaction-identifier order the finder answers")
        void rowsRenderInTheOrderTheFinderAnswers() {
            holdRows(CARD_TOKEN, ascendingRows());
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(textRenderer.onlyStatementCall().rows())
                    .extracting(TransactionRow::transactionId)
                    .containsExactly(
                            NotificationRenderer.pic("0000000000000001",
                                    NotificationRenderer.ST_TRANID_WIDTH),
                            NotificationRenderer.pic("0000000000000002",
                                    NotificationRenderer.ST_TRANID_WIDTH),
                            NotificationRenderer.pic("0000000000000003",
                                    NotificationRenderer.ST_TRANID_WIDTH),
                            NotificationRenderer.pic("0000000000000004",
                                    NotificationRenderer.ST_TRANID_WIDTH));
        }

        /**
         * Holds that the trailer total covers every row.
         *
         * <p>The addition at {@code app/cbl/CBSTM03A.CBL:L429} runs once per pass of the inner
         * loop, and the move at {@code app/cbl/CBSTM03A.CBL:L434} carries the result to the
         * trailer.</p>
         */
        @Test
        @DisplayName("The trailer total covers every row the alert carries")
        void theTrailerTotalCoversEveryRow() {
            holdRows(CARD_TOKEN, ascendingRows());
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(textRenderer.onlyStatementCall().total()).isEqualByComparingTo("77.42");
        }

        /**
         * Holds that one total crosses the boundary, edited once after the loop.
         *
         * <p>Each detail row carries the amount of its own row, matching
         * {@code MOVE TRNX-AMT TO ST-TRANAMT} at {@code app/cbl/CBSTM03A.CBL:L678}. No detail row
         * carries a running total, and the hop at {@code app/cbl/CBSTM03A.CBL:L433-L434} happens
         * once.</p>
         */
        @Test
        @DisplayName("Each detail row carries its own amount and no running total")
        void noDetailRowCarriesARunningTotal() {
            holdRows(CARD_TOKEN, ascendingRows());
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(textRenderer.statementCalls()).hasSize(1);
            List<TransactionRow> rendered = textRenderer.onlyStatementCall().rows();
            for (int index = 0; index < ROW_AMOUNTS.size(); index++) {
                assertThat(rendered.get(index).editedAmount()).isEqualTo(NotificationRenderer
                        .editTrailingSignZ(new BigDecimal(ROW_AMOUNTS.get(index))));
            }
            List<String> editedPartialTotals = new ArrayList<>();
            for (String partial : LATER_PARTIAL_TOTALS) {
                editedPartialTotals
                        .add(NotificationRenderer.editTrailingSignZ(new BigDecimal(partial)));
            }
            assertThat(rendered).extracting(TransactionRow::editedAmount)
                    .doesNotContainAnyElementsOf(editedPartialTotals);
        }

        /**
         * Holds what a row that fails inside the loop leaves behind.
         *
         * <p>The loop closes at {@code app/cbl/CBSTM03A.CBL:L430} before the hop at
         * {@code app/cbl/CBSTM03A.CBL:L433-L434} and the trailer writes at
         * {@code app/cbl/CBSTM03A.CBL:L435-L437}. A fault inside the loop therefore reaches
         * neither the renderer nor the rendered-alert row.</p>
         */
        @Test
        @DisplayName("A row failing inside the loop reaches neither renderer nor attempt row")
        void aFaultInsideTheLoopStopsBeforeTheTrailer() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00"),
                    new FailingAmountRow("0000000000000002"),
                    row("0000000000000003", storedDescription('C', 'D'), "20.00")));
            NotificationService service = serviceWith(textRenderer);

            assertThatThrownBy(() -> service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER,
                    TRANSACTION_ID, ACCOUNT_ID, CURRENT_BALANCE, cardholder(),
                    RenderedFormat.PLAIN_TEXT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(FailingAmountRow.FAULT_MESSAGE);
            assertThat(textRenderer.statementCalls()).isEmpty();
            assertThat(attemptLog.savedRows()).isEmpty();
        }
    }

    /**
     * Holds that every store truncates toward zero.
     *
     * <p>A recursive, case-insensitive search of {@code app/} finds no occurrence of the
     * {@code ROUNDED} phrase. Every arithmetic store in the source therefore discards the digits
     * that do not fit and moves no digit up, the store at {@code app/cbl/CBSTM03A.CBL:L429}
     * included. Agent Action Plan section 0.1.1 item I1 records the same
     * measurement, and section 0.6.2 carries its consequences.</p>
     *
     * <p>Floor agrees with truncation toward zero for a positive value and disagrees for a
     * negative one, and negative amounts are ordinary traffic. Decoding the zoned-decimal amount
     * field of all 300 records of {@code app/data/ASCII/dailytran.txt} gives exactly 50 negative
     * amounts and 250 positive ones. These tests build their amounts in line and read no file.</p>
     *
     * <p>{@code CobolDecimal} takes no rounding mode and offers no overload that does, so each
     * comparison value below comes from plain {@link BigDecimal} arithmetic in the test.</p>
     */
    @Nested
    @DisplayName("Truncation toward zero")
    class TruncationTowardZero {

        /**
         * An amount whose third decimal digit makes the three rounding modes disagree at the store
         * of {@code app/cbl/CBSTM03A.CBL:L429}.
         */
        private static final String POSITIVE_THIRD_DECIMAL = "250.005";

        /**
         * The same magnitude, negative, which separates truncation from floor at the store of
         * {@code app/cbl/CBSTM03A.CBL:L429}.
         */
        private static final String NEGATIVE_THIRD_DECIMAL = "-250.005";

        /**
         * Builds one row per amount, each carrying its amount at the scale supplied.
         *
         * @param amounts the amounts in decimal text
         * @return the rows the addition at {@code app/cbl/CBSTM03A.CBL:L429} folds
         */
        private List<StatementTransactionEntity> unroundedRows(List<String> amounts) {
            List<StatementTransactionEntity> rows = new ArrayList<>(amounts.size());
            for (int index = 0; index < amounts.size(); index++) {
                rows.add(new UnroundedAmountRow(String.format("%016d", index + 1),
                        new BigDecimal(amounts.get(index))));
            }
            return rows;
        }

        /**
         * Converts decimal text to values.
         *
         * @param amounts the amounts in decimal text
         * @return the same amounts as values, in the form
         *         {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29} stores
         */
        private List<BigDecimal> amountsOf(List<String> amounts) {
            List<BigDecimal> values = new ArrayList<>(amounts.size());
            for (String amount : amounts) {
                values.add(new BigDecimal(amount));
            }
            return values;
        }

        /**
         * Holds that the total matches a fold that truncates toward zero at each store.
         *
         * <p>The store at {@code app/cbl/CBSTM03A.CBL:L429} lands in
         * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}, once per row.</p>
         */
        @Test
        @DisplayName("The total matches a fold that truncates toward zero at every store")
        void theTotalMatchesATruncatingFold() {
            List<String> amounts =
                    List.of(POSITIVE_THIRD_DECIMAL, "13.279", NEGATIVE_THIRD_DECIMAL, "4.001");
            NotificationService service = serviceWith(textRenderer);

            BigDecimal total = service.totalOf(unroundedRows(amounts));

            assertThat(total)
                    .isEqualTo(foldPerRow(RoundingMode.DOWN, amountsOf(amounts)));
        }

        /**
         * Holds that a positive third decimal digit separates truncation from half-up rounding.
         *
         * <p>Truncation keeps the lower cent and half-up rounding moves to the higher one.
         * The store under test is the one at {@code app/cbl/CBSTM03A.CBL:L429}.</p>
         */
        @Test
        @DisplayName("A positive third decimal digit separates truncation from half-up rounding")
        void aPositiveThirdDecimalSeparatesTruncationFromHalfUp() {
            List<String> amounts = List.of(POSITIVE_THIRD_DECIMAL);
            NotificationService service = serviceWith(textRenderer);

            BigDecimal total = service.totalOf(unroundedRows(amounts));

            assertThat(total).isEqualTo(new BigDecimal("250.00"));
            assertThat(total).isEqualTo(foldPerRow(RoundingMode.DOWN, amountsOf(amounts)));
            assertThat(total)
                    .isNotEqualTo(foldPerRow(RoundingMode.HALF_UP, amountsOf(amounts)));
        }

        /**
         * Holds that a negative third decimal digit separates truncation from both other modes.
         *
         * <p>Truncation keeps the cent nearer zero. Half-up rounding and floor both move to the
         * cent further from zero. The store under test is the one at
         * {@code app/cbl/CBSTM03A.CBL:L429}, which the source reaches for a negative amount as
         * readily as for a positive one.</p>
         */
        @Test
        @DisplayName("A negative third decimal separates truncation from half-up and floor")
        void aNegativeThirdDecimalSeparatesTruncationFromHalfUpAndFloor() {
            List<String> amounts = List.of(NEGATIVE_THIRD_DECIMAL);
            NotificationService service = serviceWith(textRenderer);

            BigDecimal total = service.totalOf(unroundedRows(amounts));

            assertThat(total).isEqualTo(new BigDecimal("-250.00"));
            assertThat(total).isEqualTo(foldPerRow(RoundingMode.DOWN, amountsOf(amounts)));
            assertThat(total)
                    .isNotEqualTo(foldPerRow(RoundingMode.HALF_UP, amountsOf(amounts)));
            assertThat(total).isNotEqualTo(foldPerRow(RoundingMode.FLOOR, amountsOf(amounts)));
        }

        /**
         * Holds that every store truncates, and not the sum alone.
         *
         * <p>Four amounts of five thousandths each total zero cents, one store at a time. Summing
         * first and truncating once would total two cents. The addition at
         * {@code app/cbl/CBSTM03A.CBL:L429} lands in a field of two decimal digits on every pass
         * of the loop at {@code app/cbl/CBSTM03A.CBL:L422-L430}.</p>
         */
        @Test
        @DisplayName("Every store truncates, so four half-cent amounts total zero cents")
        void everyStoreTruncatesAndNotTheSumAlone() {
            List<String> amounts = List.of("0.005", "0.005", "0.005", "0.005");
            NotificationService service = serviceWith(textRenderer);

            BigDecimal total = service.totalOf(unroundedRows(amounts));

            BigDecimal wholeSum = BigDecimal.ZERO;
            for (BigDecimal amount : amountsOf(amounts)) {
                wholeSum = wholeSum.add(amount);
            }

            assertThat(total).isEqualTo(new BigDecimal("0.00"));
            assertThat(total)
                    .isNotEqualTo(wholeSum.setScale(AMOUNT_SCALE, RoundingMode.DOWN));
        }

        /**
         * Holds the scale of the total.
         *
         * <p>{@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29} holds two decimal
         * digits, and so does {@code WS-TOTAL-AMT PIC S9(9)V99} at
         * {@code app/cbl/CBSTM03A.CBL:L65}.</p>
         */
        @Test
        @DisplayName("The total carries two decimal digits for an empty card and a filled one")
        void theTotalCarriesTwoDecimalDigits() {
            NotificationService service = serviceWith(textRenderer);

            assertThat(service.totalOf(List.of()).scale()).isEqualTo(AMOUNT_SCALE);
            assertThat(service.totalOf(unroundedRows(List.of("12.3456", "0.1"))).scale())
                    .isEqualTo(AMOUNT_SCALE);
        }
    }

    /**
     * Holds the ceiling a summed total meets, and what crossing it does.
     *
     * <p>{@code 01 COMP3-VARIABLES COMP-3.} at {@code app/cbl/CBSTM03A.CBL:L64} opens the group
     * that declares {@code 05 WS-TOTAL-AMT PIC S9(9)V99 VALUE 0.} at
     * {@code app/cbl/CBSTM03A.CBL:L65}, and {@code 05 WS-TRN-AMT PIC S9(9)V99 VALUE 0.} sits at
     * {@code app/cbl/CBSTM03A.CBL:L68}. Both hold nine integer digits, so the move at
     * {@code app/cbl/CBSTM03A.CBL:L433} drops any digit above the ninth and reports nothing.</p>
     *
     * <p><b>The accumulator meets the ceiling before the print field does.</b>
     * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429} names
     * {@code WS-TOTAL-AMT} as its receiving field and carries no {@code ON SIZE ERROR} phrase, so a
     * sum needing a tenth integer digit loses that digit at the addition and every later addition
     * works from the truncated value. The total this service reports through
     * {@code GET /notifications/{cardToken}} is therefore the same truncated value the statement
     * renders, which is the whole point: an endpoint that answered with an untruncated total would
     * disagree with the statement for the same rows, and one that tried to serialize ten integer
     * digits into the nine-digit contract would fail the request outright.</p>
     *
     * <p>The finding is carried with its citations in
     * {@code card-platform/docs/business-rule-flags.md}.</p>
     */
    @Nested
    @DisplayName("The nine-integer-digit ceiling on a total")
    class NineIntegerDigitCeiling {

        /**
         * Two of these rows carry the total past the nine integer digits
         * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65} holds.
         */
        private static final String LARGE_AMOUNT = "600000000.00";

        /**
         * The nine low-order integer digits of the summed total, with its two decimal digits, as
         * {@code WS-TRN-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L68} holds them.
         */
        private static final String SURVIVING_TOTAL = "200000000.00";

        /**
         * The arithmetic sum of the two rows, at ten integer digits.
         *
         * <p>No field in {@code app/cbl/CBSTM03A.CBL} holds this value. The addition at
         * {@code app/cbl/CBSTM03A.CBL:L429} computes it and stores {@link #SURVIVING_TOTAL} of it,
         * because its receiving field holds nine integer digits. It is the value a widened
         * accumulator would carry, and it appears here only as that comparison.</p>
         */
        private static final String ACCUMULATED_TOTAL = "1200000000.00";

        /**
         * Holds that a total past nine integer digits loses its high-order digit at the addition.
         *
         * <p>{@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429} stores into
         * a field of nine integer digits and carries no {@code ON SIZE ERROR} phrase, so the total
         * the renderer receives already holds {@link #SURVIVING_TOTAL}. The move at
         * {@code app/cbl/CBSTM03A.CBL:L433} into {@code WS-TRN-AMT PIC S9(9)V99} at
         * {@code app/cbl/CBSTM03A.CBL:L68} then has nothing left to drop, and the edited value that
         * {@code app/cbl/CBSTM03A.CBL:L434} places in the trailer reads the same either way.</p>
         */
        @Test
        @DisplayName("A total past nine integer digits loses its high-order digit at the addition")
        void aTotalPastNineIntegerDigitsLosesItsHighOrderDigit() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), LARGE_AMOUNT),
                    row("0000000000000002", storedDescription('C', 'D'), LARGE_AMOUNT)));
            NotificationService service = serviceWith(textRenderer);

            assertThatCode(() -> service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER,
                    TRANSACTION_ID, ACCOUNT_ID, CURRENT_BALANCE, cardholder(),
                    RenderedFormat.PLAIN_TEXT)).doesNotThrowAnyException();

            BigDecimal total = textRenderer.onlyStatementCall().total();
            assertThat(total)
                    .as("the receiving field of the ADD holds nine integer digits")
                    .isEqualByComparingTo(SURVIVING_TOTAL);
            assertThat(total.precision() - total.scale())
                    .as("so the stored total never exceeds the field, however many rows are summed")
                    .isLessThanOrEqualTo(TOTAL_INTEGER_DIGITS);

            String editedTotal = NotificationRenderer.editTrailingSignZ(total);
            assertThat(editedTotal).isEqualTo(NotificationRenderer
                    .editTrailingSignZ(new BigDecimal(SURVIVING_TOTAL)));
            assertThat(editedTotal.trim()).startsWith("2").doesNotStartWith("1");
        }

        /**
         * Holds that the total an API response reports equals the total a statement renders.
         *
         * <p>Both come from the one accumulation, so a caller reading
         * {@code GET /notifications/{cardToken}} and a cardholder reading the statement see the same
         * digits. This is the property that failed before the store was performed where the source
         * performs it: the endpoint assembled an untruncated ten-digit total, the response contract
         * of nine integer digits refused it, and the request answered {@code 500} for as long as
         * those rows stood.</p>
         */
        @Test
        @DisplayName("The API total and the rendered total agree past the ceiling")
        void theApiTotalAgreesWithTheRenderedTotal() {
            List<StatementTransactionEntity> rows = List.of(
                    row("0000000000000001", storedDescription('A', 'B'), LARGE_AMOUNT),
                    row("0000000000000002", storedDescription('C', 'D'), LARGE_AMOUNT));
            holdRows(CARD_TOKEN, rows);
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(service.totalOf(rows))
                    .as("one accumulation serves the endpoint and the statement")
                    .isEqualByComparingTo(textRenderer.onlyStatementCall().total());
        }

        /**
         * Holds that the truncation happens once per addition rather than once at the end.
         *
         * <p>A COBOL {@code ADD} stores its result before the next statement runs, so the third row
         * here is added to {@link #SURVIVING_TOTAL} and not to the arithmetic sum of the first two.
         * Summing every row first and truncating once would report a different value, which is why
         * the order matters and is asserted rather than assumed.</p>
         */
        @Test
        @DisplayName("Each addition stores before the next one reads")
        void eachAdditionStoresBeforeTheNextOneReads() {
            NotificationService service = serviceWith(textRenderer);
            List<StatementTransactionEntity> rows = List.of(
                    row("0000000000000001", storedDescription('A', 'B'), LARGE_AMOUNT),
                    row("0000000000000002", storedDescription('C', 'D'), LARGE_AMOUNT),
                    row("0000000000000003", storedDescription('E', 'F'), LARGE_AMOUNT));

            assertThat(service.totalOf(rows))
                    .as("200000000.00 + 600000000.00 stays inside the field, and 1800000000.00"
                            + " truncated once would not")
                    .isEqualByComparingTo("800000000.00");
        }

        /**
         * States what a widened accumulator and print field would report for the same two rows.
         *
         * <p>The arithmetic sum of the two rows is {@link #ACCUMULATED_TOTAL}. A field of ten
         * integer digits would hold every digit of it, and the shipped nine-digit pair of
         * {@code app/cbl/CBSTM03A.CBL:L65} and {@code app/cbl/CBSTM03A.CBL:L142} holds nine. The
         * two therefore differ, and that difference is the divergence the tests above pin.</p>
         *
         * <p>The expected value here is the intended target behaviour and not the shipped one, so
         * this test fails if the field is widened without the siblings above being retired
         * together. Widening it is a decision only the owner of the equivalence contract can take,
         * and {@code card-platform/docs/business-rule-flags.md} records it as owed.</p>
         */
        @Test
        @Tag(HUMAN_REVIEW_TAG)
        @DisplayName("INTENDED TARGET: ten integer digits keep the high-order digit, and the "
                + "shipped nine-digit field drops it")
        void theIntendedPrintWidthKeepsTheHighOrderDigit() {
            BigDecimal summed = new BigDecimal(LARGE_AMOUNT).add(new BigDecimal(LARGE_AMOUNT));

            String intended = summed.toPlainString();
            String shipped = serviceWith(textRenderer).totalOf(List.of(
                    row("0000000000000001", storedDescription('A', 'B'), LARGE_AMOUNT),
                    row("0000000000000002", storedDescription('C', 'D'), LARGE_AMOUNT)))
                    .toPlainString();

            assertThat(intended)
                    .as("a ten-digit field carries every digit the arithmetic produced")
                    .isEqualTo(ACCUMULATED_TOTAL)
                    .startsWith("1");
            assertThat(shipped)
                    .as("the shipped nine-digit field holds the nine low-order digits")
                    .isNotEqualTo(intended)
                    .startsWith("2");
        }

        /**
         * Holds that the edited total keeps its width whether or not the ceiling was crossed.
         *
         * <p>{@code ST-TOTAL-TRAMT PIC Z(9).99-} at {@code app/cbl/CBSTM03A.CBL:L142} holds nine
         * digit positions, the decimal point, two decimal digits and one trailing sign
         * position.</p>
         *
         * <p>Two values are edited. The first is the total these rows accumulate to, which crossed
         * the ceiling and was stored truncated. The second is the arithmetic sum, built by hand
         * because no field in the source holds it, and it proves the edit holds its width for a
         * value wider than the field as well.</p>
         */
        @Test
        @DisplayName("The edited total holds 13 characters whether or not the ceiling is crossed")
        void theEditedTotalKeepsItsWidth() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), LARGE_AMOUNT),
                    row("0000000000000002", storedDescription('C', 'D'), LARGE_AMOUNT)));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            BigDecimal total = textRenderer.onlyStatementCall().total();
            BigDecimal wider = new BigDecimal(ACCUMULATED_TOTAL);
            assertThat(total.precision() - total.scale()).isLessThanOrEqualTo(TOTAL_INTEGER_DIGITS);
            assertThat(wider.precision() - wider.scale()).isGreaterThan(TOTAL_INTEGER_DIGITS);
            assertThat(NotificationRenderer.editTrailingSignZ(total))
                    .hasSize(EDITED_TOTAL_WIDTH);
            assertThat(NotificationRenderer.editTrailingSignZ(wider))
                    .hasSize(EDITED_TOTAL_WIDTH);
        }
    }

    /**
     * Holds what the rendered-alert row carries and what it never carries.
     *
     * <p>ADDITIVE. {@code app/cbl/CBSTM03A.CBL} writes its statement records at
     * {@code app/cbl/CBSTM03A.CBL:L488-L502} and records nothing about the write, so this
     * aggregate has no source ancestor.</p>
     *
     * <p>Masking is additive too. No masking exists in the source: the card detail map shows all
     * sixteen characters unprotected, with {@code LENGTH=16} at
     * {@code app/bms/COCRDSL.bms:L99}. The source also stores the card verification value in the
     * clear as {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}, and this service
     * holds no field for it. Both additions are recorded in
     * {@code card-platform/docs/decision-log.md}.</p>
     */
    @Nested
    @DisplayName("The rendered-alert row")
    class RenderedAlertRow {

        /**
         * Field-name fragments that would name a rendered document. The writes at
         * {@code app/cbl/CBSTM03A.CBL:L488-L502} emit one and record none.
         *
         * <p>The fragments are matched against text-holding fields alone. {@code renderedAt} is an
         * {@link java.time.Instant} and contains the fragment {@code rendered}, and a timestamp
         * cannot hold a document however it is named. Testing the type rather than exempting the
         * name keeps the check strict where it matters: any new {@code String} field naming any of
         * these fragments still fails.</p>
         */
        private static final List<String> DOCUMENT_FIELD_FRAGMENTS = List.of(
                "body", "content", "payload", "document", "rendered", "text", "html", "markup",
                "message", "alert");

        /**
         * Field-name fragments that would name a card verification value, which the source stores
         * as {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}.
         */
        private static final List<String> VERIFICATION_FIELD_FRAGMENTS = List.of(
                "cvv", "cvc", "verificationvalue", "securitycode", "cardverification");

        /**
         * Method-name fragments that would reverse a mask. The card detail map shows all sixteen
         * characters, with {@code LENGTH=16} at {@code app/bms/COCRDSL.bms:L99}.
         */
        private static final List<String> UNMASK_METHOD_FRAGMENTS = List.of(
                "unmask", "unredact", "reverse", "restore", "decode", "reveal", "plain");

        /**
         * Holds that one posted alert writes one attempt row, and two write two.
         *
         * <p>The write at {@code app/cbl/CBSTM03A.CBL:L488} runs once per statement, and the
         * attempt row follows the same count.</p>
         */
        @Test
        @DisplayName("Each posted alert writes exactly one rendered-alert row")
        void eachPostedAlertWritesOneAttemptRow() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00")));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(attemptLog.savedRows()).hasSize(1);
            assertThat(attemptLog.count()).isEqualTo(1L);
            NotificationLogEntity saved = attemptLog.savedRows().get(0);
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getChannel()).isEqualTo(RenderedFormat.PLAIN_TEXT.name());
            assertThat(saved.getTransactionId()).isEqualTo(NotificationRenderer
                    .pic(TRANSACTION_ID, NotificationLogEntity.TRANSACTION_ID_LENGTH));
            assertThat(saved.getRenderedAt()).isEqualTo(ATTEMPT_INSTANT);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);
            assertThat(attemptLog.savedRows()).hasSize(2);
            assertThat(attemptLog.savedRows().get(1).getId())
                    .isNotEqualTo(attemptLog.savedRows().get(0).getId());
        }

        /**
         * Holds that the fraud alert writes no attempt row.
         *
         * <p>The column {@code card_number} holds a masked card number, and the payload of a fraud
         * assessment carries none to mask. The alert still renders, reaching the cardholder fields
         * of {@code app/cbl/CBSTM03A.CBL:L462-L485}.</p>
         */
        @Test
        @DisplayName("A fraud alert renders and writes no rendered-alert row")
        void aFraudAlertWritesNoAttemptRow() {
            NotificationService service = serviceWith(textRenderer);

            String alert = service.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(alert).isEqualTo(TEXT_ALERT);
            assertThat(textRenderer.fraudCalls()).hasSize(1);
            assertThat(attemptLog.savedRows()).isEmpty();
            assertThat(attemptLog.count()).isZero();
        }

        /**
         * Holds that the account identifier reaches the finder and both identifiers reach the
         * attempt row in their proper roles.
         *
         * <p>The read-model key is the eleven-digit account identifier. The masked card number is
         * display-only and reaches the attempt row without becoming an authorization scope.</p>
         */
        @Test
        @DisplayName("Only the masked card number reaches the finder and the attempt row")
        void theMaskedCardNumberReachesTheFinderAndTheAttemptRow() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00")));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            Mockito.verify(statementTransactions)
                    .findByIdCardTokenOrderByIdTransactionIdDesc(CARD_TOKEN, PAGE_LIMIT);
            Mockito.verify(statementTransactions, Mockito.never())
                    .findByIdCardTokenOrderByIdTransactionIdDesc(FULL_CARD_NUMBER, PAGE_LIMIT);

            NotificationLogEntity stored = attemptLog.savedRows().get(0);
            assertThat(stored.getCardToken()).isEqualTo(CARD_TOKEN);
            String storedCardNumber = stored.getMaskedCardNumber();
            assertThat(storedCardNumber)
                    .isEqualTo(PanMasker.maskCardNumber(FULL_CARD_NUMBER))
                    .hasSize(NotificationLogEntity.CARD_NUMBER_LENGTH)
                    .isNotEqualTo(FULL_CARD_NUMBER)
                    .doesNotContain(FULL_CARD_NUMBER.substring(0,
                            NotificationLogEntity.CARD_NUMBER_LENGTH
                                    - PanMasker.VISIBLE_DIGIT_COUNT))
                    .startsWith(String.valueOf(PanMasker.MASK_CHARACTER)
                            .repeat(NotificationLogEntity.CARD_NUMBER_LENGTH
                                    - PanMasker.VISIBLE_DIGIT_COUNT));
            assertThat(storedCardNumber.chars()
                    .filter(Character::isDigit).count())
                    .isEqualTo(PanMasker.VISIBLE_DIGIT_COUNT);
        }

        /**
         * Holds that the rendered-alert aggregate declares seven fields and no rendered document.
         *
         * <p>The seven are the identifier, the card token, the masked card number, the transaction
         * identifier at the width {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23}
         * declares, the format name, the instant rendering finished and the outcome. The writes at
         * {@code app/cbl/CBSTM03A.CBL:L488-L502} emit the document to a dataset, and no column
         * holds it.</p>
         *
         * <p>{@code outcome} is the seventh and it arrived with
         * {@code db/migration/V5__rendered_not_delivered.sql}. Nothing on this platform sends
         * anything, so the row states that itself rather than leaving it to prose: the value is
         * always {@code RENDERED_NOT_SENT} and a check constraint admits no other.</p>
         */
        @Test
        @DisplayName("The rendered-alert aggregate declares seven fields and none holds a document")
        void theRenderedAlertAggregateDeclaresSevenFieldsAndNoDocument() {
            List<Field> declared = new ArrayList<>();
            for (Field field : NotificationLogEntity.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    declared.add(field);
                }
            }

            assertThat(declared).hasSize(7);
            assertThat(declared).extracting(Field::getName).containsExactlyInAnyOrder(
                    "id", "cardToken", "maskedCardNumber", "transactionId", "channel",
                    "renderedAt", "outcome");
            for (Field field : declared) {
                if (!CharSequence.class.isAssignableFrom(field.getType())
                        && !byte[].class.equals(field.getType())) {
                    continue;
                }
                assertThat(DOCUMENT_FIELD_FRAGMENTS)
                        .noneMatch(fragment -> field.getName().toLowerCase().contains(fragment));
            }
        }

        /**
         * Holds that every row this service writes says it was rendered and not sent.
         *
         * <p>This is the assertion the finding asked for. The service reaches no mail, message,
         * webhook or push gateway, and the value below is the only one
         * {@code ck_notification_log_outcome} permits, so a reader of the table cannot mistake a
         * row for evidence that a cardholder was told anything.</p>
         */
        @Test
        @DisplayName("Every stored row carries RENDERED_NOT_SENT, and no caller can change it")
        void everyStoredRowCarriesRenderedNotSent() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00")));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(attemptLog.savedRows())
                    .isNotEmpty()
                    .allSatisfy(saved -> assertThat(saved.getOutcome())
                            .isEqualTo(NotificationLogEntity.RENDERED_NOT_SENT));
            assertThat(Arrays.stream(NotificationLogEntity.class.getConstructors())
                    .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                    .filter(String.class::equals)
                    .count())
                    .as("the public constructor takes four strings, none of them the outcome")
                    .isEqualTo(4L);
        }

        /**
         * Holds that no rendered document reaches the attempt repository.
         *
         * <p>The value the renderer returned is the document. No string the saved row carries
         * equals it or contains it, so the writes at {@code app/cbl/CBSTM03A.CBL:L488-L502} have no
         * persisted counterpart here.</p>
         */
        @Test
        @DisplayName("No rendered document reaches the attempt repository")
        void noRenderedDocumentReachesTheRepository() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00")));
            NotificationService service = serviceWith(textRenderer);

            String alert = service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            NotificationLogEntity saved = attemptLog.savedRows().get(0);
            assertThat(List.of(saved.getCardToken(), saved.getMaskedCardNumber(),
                    saved.getTransactionId(),
                    saved.getChannel(), saved.getId().toString()))
                    .noneMatch(value -> value.contains(alert));
        }

        /**
         * Holds that no card verification value has a place in this service.
         *
         * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} stores the value in
         * the clear, and neither the attempt aggregate nor the service names it. The masker also
         * offers no way back to a full Primary Account Number (PAN).</p>
         */
        @Test
        @DisplayName("No member names a card verification value and no method unmasks")
        void noCardVerificationValueOrUnmaskingExists() {
            for (String name : declaredMemberNames(NotificationLogEntity.class)) {
                assertThat(VERIFICATION_FIELD_FRAGMENTS)
                        .noneMatch(fragment -> name.contains(fragment));
            }
            for (String name : declaredMemberNames(NotificationService.class)) {
                assertThat(VERIFICATION_FIELD_FRAGMENTS)
                        .noneMatch(fragment -> name.contains(fragment));
            }
            for (Method method : PanMasker.class.getDeclaredMethods()) {
                assertThat(UNMASK_METHOD_FRAGMENTS).noneMatch(
                        fragment -> method.getName().toLowerCase().contains(fragment));
            }
        }
    }

    /**
     * Holds which collaborators this service reaches.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL} reads four datasets through the copybooks at
     * {@code app/cbl/CBSTM03A.CBL:L51-L57} and writes two, at
     * {@code app/cbl/CBSTM03A.CBL:L488-L502} and {@code app/cbl/CBSTM03A.CBL:L506}. It reaches no
     * queue, no terminal and no other program. This service keeps that reach: it renders a
     * document, records the attempt and answers its caller.</p>
     */
    @Nested
    @DisplayName("The collaborators this service reaches")
    class CollaboratorShape {

        /**
         * Method-name fragments that would name an outbound emission. The source writes to a
         * dataset at {@code app/cbl/CBSTM03A.CBL:L488-L502} and reaches no queue.
         */
        private static final List<String> EMISSION_METHOD_FRAGMENTS = List.of(
                "publish", "send", "emit", "dispatch", "produce", "notifyby", "deliver");

        /**
         * Type and member fragments that would name a duplicate-delivery guard. The accumulation
         * at {@code app/cbl/CBSTM03A.CBL:L429} adds a replayed amount twice. The fragment
         * {@code processed} is the broadest of the six and matches any type or member built on
         * the word.
         */
        private static final List<String> DUPLICATE_GUARD_FRAGMENTS = List.of(
                "processed", "idempoten", "duplicate", "dedup", "replay", "guard");

        /**
         * Holds that one public constructor takes the renderers and three collaborators.
         *
         * <p>Nothing in the parameter list opens a path off this service other than the read model
         * of {@code app/cpy/COSTM01.CPY:L20-L36}, the attempt aggregate and the meter holder.</p>
         */
        @Test
        @DisplayName("One public constructor takes the renderers and three collaborators")
        void oneConstructorTakesTheRenderersAndThreeCollaborators() {
            List<Constructor<?>> publicConstructors = new ArrayList<>();
            for (Constructor<?> constructor : NotificationService.class.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    publicConstructors.add(constructor);
                }
            }

            assertThat(publicConstructors).hasSize(1);
            assertThat(publicConstructors.get(0).getParameterTypes()).containsExactly(
                    List.class, StatementTransactionRepository.class,
                    NotificationLogRepository.class, NotificationMetrics.class);
        }

        /**
         * Holds that no type this service names is a gateway, a producer or an outbox relay.
         *
         * <p>The source writes to a dataset at {@code app/cbl/CBSTM03A.CBL:L488-L502}. No mail
         * sender, short-message client, push client, messaging template or web client has a
         * counterpart here, and this module publishes no event.</p>
         */
        @Test
        @DisplayName("No type this service names is a gateway, a producer or an outbox relay")
        void noTypeNamedIsAGatewayOrAProducer() {
            for (Class<?> named : typesNamedBy(NotificationService.class)) {
                String simpleName = named.getSimpleName().toLowerCase();
                assertThat(GATEWAY_TYPE_FRAGMENTS)
                        .noneMatch(fragment -> simpleName.contains(fragment));
            }
        }

        /**
         * Holds that no method of this service emits anything outward.
         *
         * <p>Every method either assembles a value the writes at
         * {@code app/cbl/CBSTM03A.CBL:L488-L502} would carry, reads the read model, or records the
         * attempt.</p>
         */
        @Test
        @DisplayName("No method of this service publishes, sends or dispatches")
        void noMethodEmitsAnythingOutward() {
            for (Method method : NotificationService.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase();
                assertThat(EMISSION_METHOD_FRAGMENTS).noneMatch(name::contains);
            }
        }

        /**
         * Holds that no type this service names belongs to a sibling service.
         *
         * <p>The ledger, account and card aggregates sit in other modules. The read model at
         * {@code app/cpy/COSTM01.CPY:L20-L36} is this service's own copy, keyed by card number
         * then transaction identifier as {@code app/cpy/COSTM01.CPY:L21-L23} declares.</p>
         */
        @Test
        @DisplayName("No type this service names belongs to a sibling service module")
        void noTypeNamedBelongsToASiblingService() {
            for (Class<?> named : typesNamedBy(NotificationService.class)) {
                String qualifiedName = named.getName();
                assertThat(FOREIGN_SERVICE_PACKAGES)
                        .noneMatch(qualifiedName::startsWith);
            }
        }

        /**
         * Holds that no duplicate-delivery guard sits in this class.
         *
         * <p>The guard belongs to the consumers that call this service. The source has no
         * duplicate detection of any kind, and the accumulation at
         * {@code app/cbl/CBSTM03A.CBL:L429} adds a replayed amount twice.</p>
         */
        @Test
        @DisplayName("No type or member of this service names a duplicate-delivery guard")
        void noDuplicateDeliveryGuardSitsHere() {
            for (Class<?> named : typesNamedBy(NotificationService.class)) {
                String simpleName = named.getSimpleName().toLowerCase();
                assertThat(DUPLICATE_GUARD_FRAGMENTS)
                        .noneMatch(fragment -> simpleName.contains(fragment));
            }
            for (String name : declaredMemberNames(NotificationService.class)) {
                assertThat(DUPLICATE_GUARD_FRAGMENTS).noneMatch(name::contains);
            }
        }

        /**
         * Holds that the service works over those collaborators alone.
         *
         * <p>One posted alert reads the read model once and touches it no further. The read
         * reproduces the sequential walk of {@code app/cbl/CBSTM03A.CBL:L417-L432}, which reads and
         * never writes.</p>
         */
        @Test
        @DisplayName("One posted alert reads the read model once and writes nothing back to it")
        void theServiceWorksOverThoseCollaboratorsAlone() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "10.00")));
            NotificationService service = serviceWith(textRenderer);

            String alert = service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(alert).isEqualTo(TEXT_ALERT);
            Mockito.verify(statementTransactions)
                    .findByIdCardTokenOrderByIdTransactionIdDesc(CARD_TOKEN, PAGE_LIMIT);
            Mockito.verifyNoMoreInteractions(statementTransactions);
            assertThat(attemptLog.savedRows()).hasSize(1);
        }
    }

    /**
     * Holds that no table ceiling of the source survives into this service.
     *
     * <p>{@code 01 WS-TRNX-TABLE.} at {@code app/cbl/CBSTM03A.CBL:L225} declares
     * {@code 05 WS-CARD-TBL OCCURS 51 TIMES.} at {@code app/cbl/CBSTM03A.CBL:L226}, holding
     * {@code 10 WS-CARD-NUM PIC X(16)} at {@code app/cbl/CBSTM03A.CBL:L227} and
     * {@code 10 WS-TRAN-TBL OCCURS 10 TIMES.} at {@code app/cbl/CBSTM03A.CBL:L228}, which holds
     * {@code 15 WS-TRAN-NUM PIC X(16)} at {@code app/cbl/CBSTM03A.CBL:L229} and
     * {@code 15 WS-TRAN-REST PIC X(318)} at {@code app/cbl/CBSTM03A.CBL:L230}. Fifty-one cards and
     * ten transactions per card is the shape the source holds in memory.</p>
     *
     * <p>Neither ceiling is reproduced. The one ceiling this service applies is its own, additive
     * and far larger: an alert renders at most
     * {@link NotificationRenderer#MAXIMUM_STATEMENT_ROWS} rows, and they are the card's most recent
     * ones, so the transaction the alert reports is always among them. The last test below is that
     * property. The mapping from that table to the read model is recorded in
     * {@code card-platform/docs/traceability-matrix.md}.</p>
     */
    @Nested
    @DisplayName("The table ceilings of the source")
    class UnboundedRowCount {

        /**
         * More rows than {@code 10 WS-TRAN-TBL OCCURS 10 TIMES.} at
         * {@code app/cbl/CBSTM03A.CBL:L228} holds.
         */
        private static final int ROWS_PAST_THE_TABLE = 12;

        /**
         * More cards than {@code 05 WS-CARD-TBL OCCURS 51 TIMES.} at
         * {@code app/cbl/CBSTM03A.CBL:L226} holds.
         */
        private static final int CARDS_PAST_THE_TABLE = 52;

        /**
         * Holds that a card with twelve rows renders and totals every one of them.
         *
         * <p>Each row reaches the renderer, and the addition at
         * {@code app/cbl/CBSTM03A.CBL:L429} covers all twelve.</p>
         */
        @Test
        @DisplayName("A card with twelve rows renders twelve detail rows and totals all twelve")
        void twelveRowsAllRenderAndAllTotal() {
            List<StatementTransactionEntity> rows = new ArrayList<>(ROWS_PAST_THE_TABLE);
            for (int index = 1; index <= ROWS_PAST_THE_TABLE; index++) {
                rows.add(row(String.format("%016d", index), storedDescription('A', 'B'), "3.25"));
            }
            holdRows(CARD_TOKEN, rows);
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            StatementAlertCall call = textRenderer.onlyStatementCall();
            assertThat(call.rows()).hasSize(ROWS_PAST_THE_TABLE);
            assertThat(call.rows()).extracting(TransactionRow::transactionId)
                    .doesNotHaveDuplicates();
            assertThat(call.total()).isEqualByComparingTo("39.00");
        }

        /**
         * Holds that fifty-two cards in sequence each render.
         *
         * <p>Each pass corresponds to one pass of the loop at
         * {@code app/cbl/CBSTM03A.CBL:L317-L329}, and each carries its own total from the clearing
         * at {@code app/cbl/CBSTM03A.CBL:L325}.</p>
         */
        @Test
        @DisplayName("Fifty-two cards in sequence each render and each keeps its own total")
        void fiftyTwoCardsAllRender() {
            String maskPrefix = String.valueOf(PanMasker.MASK_CHARACTER)
                    .repeat(NotificationLogEntity.CARD_NUMBER_LENGTH
                            - PanMasker.VISIBLE_DIGIT_COUNT);
            List<String> cardTokens = new ArrayList<>(CARDS_PAST_THE_TABLE);
            List<String> maskedCardNumbers = new ArrayList<>(CARDS_PAST_THE_TABLE);
            for (int index = 0; index < CARDS_PAST_THE_TABLE; index++) {
                String accountId = String.format("%011d", 100 + index);
                String maskedCardNumber = maskPrefix + String.format("%04d", index);
                String cardToken = PanMasker.cardToken(String.format("%016d", 4_000_000 + index));
                cardTokens.add(cardToken);
                maskedCardNumbers.add(maskedCardNumber);
                holdRows(cardToken, List.of(rowOfCard(cardToken, maskedCardNumber,
                        String.format("%016d", index + 1), storedDescription('A', 'B'), "2.50")));
            }
            NotificationService service = serviceWith(textRenderer);

            for (int index = 0; index < CARDS_PAST_THE_TABLE; index++) {
                service.renderPostedTransactionAlert(cardTokens.get(index),
                        maskedCardNumbers.get(index), TRANSACTION_ID, ACCOUNT_ID, CURRENT_BALANCE,
                        cardholder(), RenderedFormat.PLAIN_TEXT);
            }

            assertThat(textRenderer.statementCalls()).hasSize(CARDS_PAST_THE_TABLE);
            assertThat(textRenderer.statementCalls())
                    .allSatisfy(call -> assertThat(call.total()).isEqualByComparingTo("2.50"));
            assertThat(attemptLog.savedRows()).hasSize(CARDS_PAST_THE_TABLE);
        }

        /**
         * Holds that a card past the renderer's own ceiling still renders and totals the transaction
         * the alert reports.
         *
         * <p>The card is given one row more than
         * {@link NotificationRenderer#MAXIMUM_STATEMENT_ROWS}, and the newest of them is the
         * transaction the alert names. Reading the oldest rows under that ceiling would drop exactly
         * that row, leave the body describing transactions the cardholder had already been shown, and
         * total a subset that excluded the amount the alert exists to report — while still recording a
         * rendered-alert row under the omitted identifier.</p>
         *
         * <p>Each of the rows past the ceiling carries a distinct amount, so the total identifies
         * which rows were summed and not merely how many.</p>
         */
        @Test
        @DisplayName("A card past the row ceiling renders the newest rows, and the triggering "
                + "transaction is in the body and the total")
        void aCardPastTheCeilingStillRendersAndTotalsTheTriggeringTransaction() {
            int ceiling = NotificationRenderer.MAXIMUM_STATEMENT_ROWS;
            int held = ceiling + 1;
            List<StatementTransactionEntity> oldestFirst = new ArrayList<>(held);
            for (int index = 1; index <= held; index++) {
                oldestFirst.add(row(String.format("%016d", index), storedDescription('A', 'B'),
                        index == held ? "7.00" : "1.00"));
            }
            String triggering = String.format("%016d", held);
            String oldest = String.format("%016d", 1);
            holdRows(CARD_TOKEN, oldestFirst);
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, triggering, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            StatementAlertCall call = textRenderer.onlyStatementCall();
            assertThat(call.rows()).as("the alert renders the ceiling, not the whole history")
                    .hasSize(ceiling);
            assertThat(call.rows()).extracting(TransactionRow::transactionId)
                    .as("the transaction the alert reports is in the body, and the row dropped is "
                            + "the oldest")
                    .contains(triggering)
                    .doesNotContain(oldest);
            assertThat(call.rows()).extracting(TransactionRow::transactionId)
                    .as("the rows still present oldest first, as the source sort produced")
                    .isSorted();
            assertThat(call.total())
                    .as("the total covers the rows rendered, including the triggering amount: "
                            + "199 rows of 1.00 and the 7.00 that triggered the alert")
                    .isEqualByComparingTo("206.00");
            assertThat(attemptLog.savedRows()).hasSize(1);
            assertThat(attemptLog.savedRows().get(0).getTransactionId().strip())
                    .as("the recorded identifier is the one the body carries")
                    .isEqualTo(triggering.strip());
        }
    }

    /**
     * Holds that two cards ending in the same four digits stay separate.
     *
     * <p>A masked card number names every card sharing its last four digits, and
     * {@link PanMasker#maskCardNumber(String)} is not injective. The key of
     * {@code statement_transaction} therefore carries {@link PanMasker#cardToken(String)}, which is
     * derived per card. These tests supply two card numbers whose masked forms are equal and whose
     * tokens are not, and read what the service does with each.</p>
     *
     * <p>The card half of the source key is {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22}, and {@code KEYS(32 0)} at
     * {@code app/jcl/CREASTMT.JCL:L30} spans it. The source held a full Primary Account Number
     * there, which distinguished the two cards. This platform holds neither a full number nor a
     * masked one in that position.</p>
     */
    @Nested
    @DisplayName("Two cards ending in the same four digits")
    class SameLastFourIsolation {

        /** An amount the first card's row carries. */
        private static final String FIRST_AMOUNT = "10.00";

        /** An amount the second card's row carries, distinct from {@link #FIRST_AMOUNT}. */
        private static final String SECOND_AMOUNT = "25.50";

        @Test
        @DisplayName("the two share one masked form and carry two tokens")
        void theTwoShareOneMaskedFormAndCarryTwoTokens() {
            assertThat(PanMasker.maskCardNumber(SAME_TAIL_FULL_CARD_NUMBER))
                    .as("the masked form is what a masked key would gather rows under")
                    .isEqualTo(MASKED_CARD_NUMBER);
            assertThat(SAME_TAIL_CARD_TOKEN)
                    .as("the token separates what the masked form conflates")
                    .isNotEqualTo(CARD_TOKEN);
            assertThat(SAME_TAIL_FULL_CARD_NUMBER)
                    .as("the two are distinct cards")
                    .isNotEqualTo(FULL_CARD_NUMBER);
        }

        @Test
        @DisplayName("each alert reads its own card's rows and totals them alone")
        void eachAlertReadsItsOwnCardsRows() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), FIRST_AMOUNT)));
            holdRows(SAME_TAIL_CARD_TOKEN, List.of(
                    rowOfCard(SAME_TAIL_CARD_TOKEN, MASKED_CARD_NUMBER, "0000000000000002",
                            storedDescription('C', 'D'), SECOND_AMOUNT)));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);
            service.renderPostedTransactionAlert(SAME_TAIL_CARD_TOKEN, SAME_TAIL_FULL_CARD_NUMBER,
                    TRANSACTION_ID, ACCOUNT_ID, CURRENT_BALANCE, cardholder(),
                    RenderedFormat.PLAIN_TEXT);

            List<StatementAlertCall> calls = textRenderer.statementCalls();
            assertThat(calls).hasSize(2);
            assertThat(calls.get(0).total()).isEqualByComparingTo(FIRST_AMOUNT);
            assertThat(calls.get(1).total()).isEqualByComparingTo(SECOND_AMOUNT);
            assertThat(calls.get(0).rows()).hasSize(1);
            assertThat(calls.get(1).rows()).hasSize(1);
            assertThat(calls.get(0).rows().get(0).transactionId())
                    .isNotEqualTo(calls.get(1).rows().get(0).transactionId());
        }

        @Test
        @DisplayName("a token reads no row of the other card, and a masked form reads none at all")
        void aTokenReadsNoRowOfTheOtherCard() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), FIRST_AMOUNT)));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(SAME_TAIL_CARD_TOKEN, SAME_TAIL_FULL_CARD_NUMBER,
                    TRANSACTION_ID, ACCOUNT_ID, CURRENT_BALANCE, cardholder(),
                    RenderedFormat.PLAIN_TEXT);

            assertThat(textRenderer.onlyStatementCall().rows())
                    .as("the second card holds no row, and the first card's row is not its own")
                    .isEmpty();
            Mockito.verify(statementTransactions)
                    .findByIdCardTokenOrderByIdTransactionIdDesc(SAME_TAIL_CARD_TOKEN, PAGE_LIMIT);
            Mockito.verify(statementTransactions, Mockito.never())
                    .findByIdCardTokenOrderByIdTransactionIdDesc(MASKED_CARD_NUMBER, PAGE_LIMIT);
        }

        @Test
        @DisplayName("each attempt row records its own token beside the shared masked form")
        void eachAttemptRowRecordsItsOwnToken() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), FIRST_AMOUNT)));
            holdRows(SAME_TAIL_CARD_TOKEN, List.of(
                    rowOfCard(SAME_TAIL_CARD_TOKEN, MASKED_CARD_NUMBER, "0000000000000002",
                            storedDescription('C', 'D'), SECOND_AMOUNT)));
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);
            service.renderPostedTransactionAlert(SAME_TAIL_CARD_TOKEN, SAME_TAIL_FULL_CARD_NUMBER,
                    TRANSACTION_ID, ACCOUNT_ID, CURRENT_BALANCE, cardholder(),
                    RenderedFormat.PLAIN_TEXT);

            List<NotificationLogEntity> saved = attemptLog.savedRows();
            assertThat(saved).hasSize(2);
            assertThat(saved).extracting(NotificationLogEntity::getCardToken)
                    .containsExactly(CARD_TOKEN, SAME_TAIL_CARD_TOKEN);
            assertThat(saved).extracting(NotificationLogEntity::getMaskedCardNumber)
                    .as("both rows carry the one masked form the two cards share")
                    .containsExactly(MASKED_CARD_NUMBER, MASKED_CARD_NUMBER);
        }
    }

    /**
     * Holds what each per-event operation does, and that both report the same total.
     *
     * <p>One operation carries the payload of a posted transaction and reproduces
     * {@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504} with the detail rows
     * of {@code 6000-WRITE-TRANS} at {@code app/cbl/CBSTM03A.CBL:L675-L679}. The other carries a
     * risk assessment and is additive, with no ancestor in {@code app/cbl/CBSTM03A.CBL}.</p>
     */
    @Nested
    @DisplayName("The two per-event operations")
    class PerEventOperations {

        /**
         * Holds that the posted-transaction operation renders and records.
         *
         * <p>The rendered value reaches the caller, the row reaches the renderer, and one attempt
         * row follows. The rendering reproduces {@code app/cbl/CBSTM03A.CBL:L458-L504}.</p>
         */
        @Test
        @DisplayName("The posted-transaction operation renders the alert and records one attempt")
        void thePostedOperationRendersAndRecords() {
            holdRows(CARD_TOKEN, List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "18.75")));
            NotificationService service = serviceWith(textRenderer);

            String alert = service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID,
                    ACCOUNT_ID, CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(alert).isEqualTo(TEXT_ALERT);
            assertThat(textRenderer.onlyStatementCall().rows()).hasSize(1);
            assertThat(textRenderer.onlyStatementCall().total()).isEqualByComparingTo("18.75");
            assertThat(attemptLog.savedRows()).hasSize(1);
        }

        /**
         * Holds that the fraud operation renders and carries the assessment through.
         *
         * <p>The cardholder fields are the fields of {@code app/cbl/CBSTM03A.CBL:L462-L485}. The
         * score and the rule identifiers are additive.</p>
         */
        @Test
        @DisplayName("The fraud operation renders the alert and carries the score and the rules")
        void theFraudOperationRendersAndCarriesTheAssessment() {
            NotificationService service = serviceWith(textRenderer);

            String alert = service.renderFraudAlert(TRANSACTION_ID, ACCOUNT_ID, RISK_SCORE,
                    TRIGGERED_RULES, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(alert).isEqualTo(TEXT_ALERT);
            FraudAlertCall call = textRenderer.fraudCalls().get(0);
            assertThat(textRenderer.fraudCalls()).hasSize(1);
            assertThat(call.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(call.riskScore()).isEqualTo(RISK_SCORE);
            assertThat(call.triggeredRules()).containsExactlyElementsOf(TRIGGERED_RULES);
            assertThat(call.context().assembledName())
                    .isEqualTo(NotificationRenderer.assembleName("JOHN", "Q", "PUBLIC"));
        }

        /**
         * Holds that the total operation agrees with the trailer total for one card.
         *
         * <p>Both fold the same rows through the addition at
         * {@code app/cbl/CBSTM03A.CBL:L429}, so a rendered alert and an answer to a caller report
         * one figure.</p>
         */
        @Test
        @DisplayName("The total operation reports the same figure the trailer carries")
        void theTotalOperationAgreesWithTheTrailer() {
            List<StatementTransactionEntity> rows = List.of(
                    row("0000000000000001", storedDescription('A', 'B'), "7.11"),
                    row("0000000000000002", storedDescription('C', 'D'), "13.27"),
                    row("0000000000000003", storedDescription('E', 'F'), "21.43"));
            holdRows(CARD_TOKEN, rows);
            NotificationService service = serviceWith(textRenderer);

            service.renderPostedTransactionAlert(CARD_TOKEN, FULL_CARD_NUMBER, TRANSACTION_ID, ACCOUNT_ID,
                    CURRENT_BALANCE, cardholder(), RenderedFormat.PLAIN_TEXT);

            assertThat(service.totalOf(rows))
                    .isEqualTo(textRenderer.onlyStatementCall().total());
            assertThat(service.totalOf(rows)).isEqualByComparingTo("41.81");
        }
    }
}
