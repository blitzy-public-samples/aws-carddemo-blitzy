package com.carddemo.notification.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.NotificationRenderer.CardholderContext;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationRenderer.TransactionRow;
import com.carddemo.notification.entity.NotificationLogEntity;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.carddemo.notification.domain.NotificationRenderer.EDITED_AMOUNT_WIDTH;
import static com.carddemo.notification.domain.NotificationRenderer.MAXIMUM_STATEMENT_ROWS;
import static com.carddemo.notification.domain.NotificationRenderer.REDACTED;
import static com.carddemo.notification.domain.NotificationRenderer.ST_ACCT_ID_WIDTH;
import static com.carddemo.notification.domain.NotificationRenderer.ST_ADD1_WIDTH;
import static com.carddemo.notification.domain.NotificationRenderer.ST_ADD2_WIDTH;
import static com.carddemo.notification.domain.NotificationRenderer.ST_FICO_SCORE_WIDTH;
import static com.carddemo.notification.domain.NotificationRenderer.assembleAddress3;
import static com.carddemo.notification.domain.NotificationRenderer.assembleName;
import static com.carddemo.notification.domain.NotificationRenderer.editTrailingSign9;
import static com.carddemo.notification.domain.NotificationRenderer.editTrailingSignZ;
import static com.carddemo.notification.domain.NotificationRenderer.pic;

/**
 * Renders one cardholder alert per consumed event, then records the delivery attempt.
 *
 * <p>Reproduces {@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504}. A
 * paragraph, in Common Business Oriented Language (COBOL), is a named block of statements that a
 * {@code PERFORM} runs. That paragraph assembles the cardholder fields both output formats read,
 * so this class assembles them once per invocation and hands them to a renderer.
 *
 * <p>Per-event alerting replaces per-cycle statement assembly. {@code 1000-MAINLINE} at
 * {@code app/cbl/CBSTM03A.CBL:L316-L329} walks a cross-reference file one card at a time. Each
 * operation below runs once per event and reads one account's rows from the read model that
 * {@code 01 TRNX-RECORD.} at {@code app/cpy/COSTM01.CPY:L20} declares.
 *
 * <p>The source resets the running total per card. The target's unique scope is the account, so
 * each invocation totals the bounded account page it reads. Each row renders before its amount
 * joins the total, matching
 * {@code PERFORM 6000-WRITE-TRANS} at {@code app/cbl/CBSTM03A.CBL:L428} followed by
 * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429}.
 *
 * <p>Every addition runs through {@link CobolDecimal}, which truncates toward zero and offers no
 * choice of rounding. A Picture clause, written {@code PIC}, fixes a field's width and form, and
 * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65} fixes the scale of this
 * total. That field carries usage {@code COMP-3}, meaning packed decimal, so the two moves at
 * {@code app/cbl/CBSTM03A.CBL:L433-L434} convert it to a display field and change no value.
 *
 * <p>A renderer arrives in the injected collection and is chosen by the format it reports. A third
 * output format needs one more implementation of {@link NotificationRenderer} and one more
 * {@link RenderedFormat} constant, and no edit here.
 *
 * <p>ADDITIVE: the fraud alert, since {@code app/cbl/CBSTM03A.CBL} carries no fraud concept.
 * ADDITIVE: the delivery-attempt row, since no COBOL program records one. This service reaches no
 * mail, message or push gateway. It renders a document, records the attempt and returns the
 * document to its caller.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. Source-to-target
 * mapping: {@code card-platform/docs/traceability-matrix.md}.
 */
@Service
public class NotificationService {

    /** Reports what this service rendered, naming no card, account or transaction. */
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

    /**
     * The total an account with no rows carries.
     *
     * <p>The scale comes from {@link PicClause#TRAN_AMT_SCALE}, matching
     * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65} and
     * {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29}.</p>
     */
    private static final BigDecimal NO_TRANSACTIONS =
            BigDecimal.ZERO.setScale(PicClause.TRAN_AMT_SCALE);

    /**
     * The balance a fraud alert carries.
     *
     * <p>The {@code FraudFlagged} event carries no balance, so the balance field renders as
     * spaces. {@code INITIALIZE STATEMENT-LINES} at {@code app/cbl/CBSTM03A.CBL:L459} leaves every
     * statement field in that same state.</p>
     */
    private static final String NO_BALANCE = "";

    /**
     * The metric tag each output format records against.
     *
     * <p>{@code config/ObservabilityConfig} registers one series per value here, plus
     * {@link NotificationMetrics#UNKNOWN}. A format absent from this map records against that
     * value and still renders its alert.</p>
     */
    private static final Map<RenderedFormat, String> METRIC_TAGS = Map.of(
            RenderedFormat.PLAIN_TEXT, NotificationMetrics.FORMAT_TEXT,
            RenderedFormat.HTML, NotificationMetrics.FORMAT_HTML);

    /** One renderer per output format, keyed by the format each one reports. */
    private final Map<RenderedFormat, NotificationRenderer> renderersByFormat;

    /** Reads one account's rows from the read model. */
    private final StatementTransactionRepository statementTransactions;

    /** Writes one row per delivery attempt. */
    private final NotificationLogRepository deliveryAttempts;

    /** Counts the alerts this service rendered, one series per output format. */
    private final NotificationMetrics metrics;

    /** Reads the instant each delivery-attempt row records. */
    private final Clock clock;

    /**
     * Takes the renderers and the collaborators this service reads and writes.
     *
     * <p>The renderers are keyed by {@link NotificationRenderer#format()} once, here, and looked up
     * by key on every call. The second renderer to report a format already keyed fails
     * construction.</p>
     *
     * <p>The instant each attempt row records comes from {@link Clock#systemUTC()}. The
     * constructor below takes another clock, and a test supplies a fixed one through it.</p>
     *
     * @param renderers one renderer per output format, in any order
     * @param statementTransactions the read-model repository
     * @param deliveryAttempts the delivery-attempt repository
     * @param metrics the meter holder {@code config/ObservabilityConfig} registers
     * @throws NullPointerException when an argument is {@code null}
     * @throws IllegalStateException when a renderer reports no format, or when two renderers report
     *         the same format
     */
    @Autowired
    public NotificationService(List<NotificationRenderer> renderers,
            StatementTransactionRepository statementTransactions,
            NotificationLogRepository deliveryAttempts,
            NotificationMetrics metrics) {
        this(renderers, statementTransactions, deliveryAttempts, metrics, Clock.systemUTC());
    }

    /**
     * Takes the renderers, the collaborators and the clock the attempt row reads.
     *
     * @param renderers one renderer per output format, in any order
     * @param statementTransactions the read-model repository
     * @param deliveryAttempts the delivery-attempt repository
     * @param metrics the meter holder {@code config/ObservabilityConfig} registers
     * @param clock the clock each delivery-attempt row records its instant from
     * @throws NullPointerException when an argument is {@code null}
     * @throws IllegalStateException when a renderer reports no format, or when two renderers report
     *         the same format
     */
    NotificationService(List<NotificationRenderer> renderers,
            StatementTransactionRepository statementTransactions,
            NotificationLogRepository deliveryAttempts,
            NotificationMetrics metrics,
            Clock clock) {
        Objects.requireNonNull(renderers, "renderers must not be null");

        Map<RenderedFormat, NotificationRenderer> byFormat = new EnumMap<>(RenderedFormat.class);
        for (NotificationRenderer renderer : renderers) {
            RenderedFormat format = renderer.format();
            if (format == null) {
                throw new IllegalStateException(
                        renderer.getClass().getName() + " reports no output format");
            }
            NotificationRenderer previous = byFormat.put(format, renderer);
            if (previous != null) {
                throw new IllegalStateException("two renderers report format " + format + ": "
                        + previous.getClass().getName() + " and " + renderer.getClass().getName());
            }
        }

        this.renderersByFormat = Map.copyOf(byFormat);
        this.statementTransactions = Objects.requireNonNull(statementTransactions,
                "statementTransactions must not be null");
        this.deliveryAttempts =
                Objects.requireNonNull(deliveryAttempts, "deliveryAttempts must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Renders the alert one posted transaction produces, then records the attempt.
     *
     * <p>Reproduces {@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504} for
     * the cardholder fields, {@code 6000-WRITE-TRANS} at {@code app/cbl/CBSTM03A.CBL:L675-L679}
     * for each detail row, and {@code 4000-TRNXFILE-GET} at {@code app/cbl/CBSTM03A.CBL:L416-L437}
     * for the total. The parameters match the payload of the {@code TransactionPosted} event.</p>
     *
     * <p>The card token names the card the rows are read under, and the card number reaches this
     * method in either form and is masked before it reaches the attempt row.
     * {@code statement_transaction.card_token} carries the key and
     * {@code statement_transaction.masked_card_number} carries the masked display value, which the
     * check constraint {@code ck_statement_transaction_masked_card_number} in
     * {@code src/main/resources/db/migration/V1__schema.sql} enforces.</p>
     *
     * <p>Rows arrive in ascending transaction-identifier order, reproducing
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53}. The total
     * covers the rows the alert carries. A blank or space-filled row field renders as spaces and
     * raises nothing.</p>
     *
     * <p>ADDITIVE: the attempt row this method writes. It carries the card token, the masked card
     * number, the transaction identifier, the format name and the instant of the attempt, and no
     * rendered document.</p>
     *
     * @param cardToken the card identity the read model is keyed on, which selects the rows this
     *        alert renders; must not be {@code null}
     * @param cardNumber the card this alert displays, in full or already masked
     * @param transactionId the posted transaction this alert reports; must not be {@code null}
     * @param accountId the account identifier, filling {@code ST-ACCT-ID} at
     *        {@code app/cbl/CBSTM03A.CBL:L483}
     * @param newBalance the balance after posting, filling {@code ST-CURR-BAL} at
     *        {@code app/cbl/CBSTM03A.CBL:L484}; must not be {@code null}
     * @param cardholder the cardholder fields the projection holds for the account; must
     *        not be {@code null}
     * @param format the output format to render; must not be {@code null}
     * @return the rendered alert
     * @throws NullPointerException when {@code cardToken}, {@code transactionId},
     *         {@code newBalance}, {@code cardholder} or {@code format} is {@code null}
     * @throws IllegalArgumentException when no renderer reports {@code format}
     */
    @Transactional
    public String renderPostedTransactionAlert(String cardToken, String cardNumber,
            String transactionId, String accountId, BigDecimal newBalance,
            CardholderDetails cardholder, RenderedFormat format) {
        Objects.requireNonNull(cardToken, "cardToken must not be null");
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(newBalance, "newBalance must not be null");
        Objects.requireNonNull(cardholder, "cardholder must not be null");

        NotificationRenderer renderer = rendererFor(format);
        String maskedCardNumber = PanMasker.maskCardNumber(cardNumber);
        CardholderContext context =
                cardholderContext(cardholder, accountId, editTrailingSign9(newBalance));

        List<StatementTransactionEntity> rows = renderableRows(cardToken);
        List<TransactionRow> detailRows = new ArrayList<>(rows.size());
        BigDecimal total = NO_TRANSACTIONS;
        for (StatementTransactionEntity row : rows) {
            detailRows.add(detailRow(row));
            total = accumulate(total, row.getAmount());
        }

        String alert = renderer.renderStatementAlert(context, detailRows, total);
        this.metrics.notificationsRendered(metricTag(format)).increment();
        recordAttempt(cardToken, maskedCardNumber, transactionId, format);
        LOGGER.info("Rendered a posted-transaction alert as {} over {} rows",
                format, detailRows.size());

        return alert;
    }

    /**
     * Renders the alert one flagged transaction produces.
     *
     * <p>ADDITIVE. {@code app/cbl/CBSTM03A.CBL} carries no fraud concept, so this operation has no
     * COBOL ancestor. The cardholder fields it renders are the fields
     * {@code 5000-CREATE-STATEMENT} assembles at {@code app/cbl/CBSTM03A.CBL:L458-L504}.</p>
     *
     * <p>The parameters match the payload of the {@code FraudFlagged} event, which carries no
     * amount and no card number. The alert therefore carries no detail row, no total and no
     * balance, and the balance field renders as spaces.</p>
     *
     * <p>This method writes no attempt row. {@code notification_log.masked_card_number} requires a
     * masked card number, and {@code FraudFlagged} carries none to store.</p>
     *
     * @param transactionId the flagged transaction; must not be {@code null}
     * @param accountId the account identifier, filling {@code ST-ACCT-ID} at
     *        {@code app/cbl/CBSTM03A.CBL:L483}
     * @param riskScore the score the fraud service assigned
     * @param triggeredRules the identifiers of the rules that fired; must not be {@code null}, and
     *        no element may be {@code null}
     * @param cardholder the cardholder fields the projection holds for the account; must
     *        not be {@code null}
     * @param format the output format to render; must not be {@code null}
     * @return the rendered alert
     * @throws NullPointerException when {@code transactionId}, {@code triggeredRules},
     *         {@code cardholder} or {@code format} is {@code null}, or when a rule identifier is
     *         {@code null}
     * @throws IllegalArgumentException when no renderer reports {@code format}
     */
    public String renderFraudAlert(String transactionId, String accountId, int riskScore,
            List<String> triggeredRules, CardholderDetails cardholder, RenderedFormat format) {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(triggeredRules, "triggeredRules must not be null");
        Objects.requireNonNull(cardholder, "cardholder must not be null");

        NotificationRenderer renderer = rendererFor(format);
        CardholderContext context = cardholderContext(cardholder, accountId, NO_BALANCE);

        String alert = renderer.renderFraudAlert(context, transactionId, riskScore,
                List.copyOf(triggeredRules));
        this.metrics.notificationsRendered(metricTag(format)).increment();
        LOGGER.info("Rendered a fraud alert as {} over {} triggered rules",
                format, triggeredRules.size());

        return alert;
    }

    /**
     * Renders the alert one authorized transaction produces.
     *
     * <p>ADDITIVE. The source has no authorization program at all: the decision rules this alert
     * follows live in the batch validation paragraphs at {@code app/cbl/CBTRN02C.cbl:L380-L420}, and
     * {@code app/cbl/CBSTM03A.CBL} renders a statement per billing cycle rather than per transaction.
     * The cardholder fields reported here are the fields {@code 5000-CREATE-STATEMENT} assembles at
     * {@code app/cbl/CBSTM03A.CBL:L458-L504}.</p>
     *
     * <p>WHY THE BALANCE RENDERS AS SPACES. An authorization establishes no balance. The ledger
     * derives the new balance when it posts the transaction, and publishes it on
     * {@code TransactionPosted}, which
     * {@link #renderPostedTransactionAlert(String, String, String, String, BigDecimal,
     * CardholderDetails, RenderedFormat)} reports. This alert says a transaction was authorized and
     * nothing about what the account now holds, so its balance field is left in the state
     * {@code INITIALIZE STATEMENT-LINES} at {@code app/cbl/CBSTM03A.CBL:L459} leaves, exactly as a
     * fraud alert is.</p>
     *
     * <p>WHY ONE DETAIL ROW AND NOT THE READ MODEL. The read model holds transactions the ledger has
     * posted. An authorized transaction has not been posted yet, so reading the model would report
     * every earlier transaction and omit the one this alert is about. The single detail row is built
     * from the event, and the total is that one amount, which is what
     * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429} accumulates over a
     * one-row set.</p>
     *
     * @param cardToken the token of the card the authorization named; must not be {@code null}
     * @param cardNumber the masked card number the event carries, reported as supplied
     * @param transactionId the authorized transaction; must not be {@code null}
     * @param accountId the account identifier, filling {@code ST-ACCT-ID} at
     *        {@code app/cbl/CBSTM03A.CBL:L483}
     * @param description the transaction description, filling the detail row
     * @param amount the authorized amount; must not be {@code null}
     * @param cardholder the cardholder fields; must not be {@code null}
     * @param format the output format to render; must not be {@code null}
     * @return the rendered alert
     * @throws NullPointerException when {@code cardToken}, {@code transactionId}, {@code amount},
     *         {@code cardholder} or {@code format} is {@code null}
     * @throws IllegalArgumentException when no renderer reports {@code format}
     */
    public String renderAuthorizationAlert(String cardToken, String cardNumber,
            String transactionId, String accountId, String description, BigDecimal amount,
            CardholderDetails cardholder, RenderedFormat format) {
        Objects.requireNonNull(cardToken, "cardToken must not be null");
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(cardholder, "cardholder must not be null");

        NotificationRenderer renderer = rendererFor(format);
        CardholderContext context = cardholderContext(cardholder, accountId, NO_BALANCE);

        // The event already carries this field masked. Masking is idempotent, because it keeps the
        // last four characters and rewrites the rest, so passing an already-masked value through
        // returns it unchanged. Applying it anyway means a producer that ever sent a full number
        // still writes a masked one to notification_log, which is what AAP 0.6.4 requires of every
        // stored and published rendering of a card number.
        String maskedCardNumber = PanMasker.maskCardNumber(cardNumber);

        List<TransactionRow> detailRows =
                List.of(new TransactionRow(transactionId, description, editTrailingSignZ(amount)));
        BigDecimal total = accumulate(NO_TRANSACTIONS, amount);

        String alert = renderer.renderStatementAlert(context, detailRows, total);
        this.metrics.notificationsRendered(metricTag(format)).increment();
        recordAttempt(cardToken, maskedCardNumber, transactionId, format);
        LOGGER.info("Rendered an authorization alert as {} over one detail row", format);

        return alert;
    }

    /**
     * Totals the amounts of one bounded row set.
     *
     * <p>Reproduces {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429} over
     * the rows supplied. The accumulation is the one
     * {@link #renderPostedTransactionAlert(String, String, String, String, BigDecimal,
     * CardholderDetails, RenderedFormat)} performs, so a rendered alert and an API response report the same total for
     * the same rows.</p>
     *
     * <p>An empty list totals zero at the scale {@link PicClause#TRAN_AMT_SCALE} declares.</p>
     *
     * @param rows the rows to total, in any order; must not be {@code null}
     * @return the total, truncated toward zero at each addition
     * @throws NullPointerException when {@code rows} is {@code null}, or when a row is {@code null}
     */
    public BigDecimal totalOf(List<StatementTransactionEntity> rows) {
        Objects.requireNonNull(rows, "rows must not be null");

        BigDecimal total = NO_TRANSACTIONS;
        for (StatementTransactionEntity row : rows) {
            total = accumulate(total, row.getAmount());
        }

        return total;
    }

    /**
     * Returns the renderer that reports one format.
     *
     * <p>A format no renderer reports raises a refusal naming that format and every format a
     * renderer does report. No format falls back to another.</p>
     *
     * @param format the format the caller asked for
     * @return the renderer that reports {@code format}
     * @throws NullPointerException when {@code format} is {@code null}
     * @throws IllegalArgumentException when no renderer reports {@code format}
     */
    private NotificationRenderer rendererFor(RenderedFormat format) {
        Objects.requireNonNull(format, "format must not be null");

        NotificationRenderer renderer = this.renderersByFormat.get(format);
        if (renderer == null) {
            throw new IllegalArgumentException("no renderer reports format " + format
                    + "; the registered formats are " + this.renderersByFormat.keySet());
        }

        return renderer;
    }

    /**
     * Reads one account's rows, up to the count one alert may carry.
     *
     * <p>The limit reaches the database rather than a list already in memory, so a card holding more
     * rows than {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS} costs one page and not a whole
     * history. {@link NotificationRenderer#requireRenderableRowCount(List)} refuses more than that
     * many rows, and this method cannot return more, so the two agree by construction. That is the
     * same page {@code GET /notifications/{cardToken}} returns.</p>
     *
     * @param cardToken the card token the key column holds
     * @return the rows to render, in ascending transaction-identifier order
     */
    private List<StatementTransactionEntity> renderableRows(String cardToken) {
        return this.statementTransactions.findByIdCardTokenOrderByIdTransactionIdAsc(cardToken,
                Limit.of(MAXIMUM_STATEMENT_ROWS));
    }

    /**
     * Assembles the cardholder fields both output formats read.
     *
     * <p>Reproduces {@code app/cbl/CBSTM03A.CBL:L462-L485}. The name comes from
     * {@code L462-L469}, the first two address lines from {@code L470-L471} and the third from
     * {@code L472-L481}. The account identifier comes from {@code L483}, the balance from
     * {@code L484} and the credit score from {@code L485}.</p>
     *
     * <p>{@link NotificationRenderer#assembleName(String, String, String)} takes each component up
     * to its first space, so a middle name of {@code "Ann Marie"} contributes {@code "Ann"}. A
     * {@code null} or blank component renders as spaces.</p>
     *
     * @param cardholder the cardholder fields as the caller holds them
     * @param accountId the account identifier
     * @param editedCurrentBalance the balance already edited, or {@link #NO_BALANCE} where the
     *        event carries none
     * @return the context, every component held at the width its source field declares
     */
    private static CardholderContext cardholderContext(CardholderDetails cardholder,
            String accountId, String editedCurrentBalance) {
        return new CardholderContext(
                assembleName(cardholder.firstName(), cardholder.middleName(),
                        cardholder.lastName()),
                pic(cardholder.addressLine1(), ST_ADD1_WIDTH),
                pic(cardholder.addressLine2(), ST_ADD2_WIDTH),
                assembleAddress3(cardholder.addressLine3(), cardholder.stateCode(),
                        cardholder.countryCode(), cardholder.zipCode()),
                pic(accountId, ST_ACCT_ID_WIDTH),
                pic(editedCurrentBalance, EDITED_AMOUNT_WIDTH),
                pic(cardholder.ficoScore(), ST_FICO_SCORE_WIDTH));
    }

    /**
     * Builds one detail row from one read-model row.
     *
     * <p>Reproduces {@code 6000-WRITE-TRANS} at {@code app/cbl/CBSTM03A.CBL:L676-L678}, which moves
     * {@code TRNX-ID}, then {@code TRNX-DESC}, then {@code TRNX-AMT}. The description travels at
     * its stored width of {@value PicClause#TRAN_DESC_WIDTH} characters, and
     * {@link NotificationRenderer.TransactionRow} holds it to the width
     * {@code ST-TRANDT PIC X(49)} at {@code app/cbl/CBSTM03A.CBL:L135} declares.</p>
     *
     * @param row one row of the read model
     * @return the detail row, its amount edited with leading spaces
     */
    private static TransactionRow detailRow(StatementTransactionEntity row) {
        return new TransactionRow(row.getId().getTransactionId(), row.getDescription(),
                editTrailingSignZ(row.getAmount()));
    }

    /**
     * Adds one amount to the running total and stores the sum in the field the source stores it in.
     *
     * <p>Reproduces {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429}. The
     * {@code ROUNDED} phrase appears zero times across the twenty-eight programs in
     * {@code app/cbl/}, so every arithmetic store in the source truncates toward zero.
     * {@link CobolDecimal#add(BigDecimal, BigDecimal, int)} truncates the same way for a negative
     * total as for a positive one.</p>
     *
     * <p><b>The store is part of the arithmetic, not a rendering step.</b> A COBOL {@code ADD}
     * names its receiving field, and that field is
     * {@code WS-TOTAL-AMT PIC S9(9)V99 VALUE 0} at {@code app/cbl/CBSTM03A.CBL:L65}: nine integer
     * digits and two fractional ones. The statement carries no {@code ON SIZE ERROR} phrase, so a
     * sum needing a tenth integer digit loses that digit where it stands and the program continues.
     * Every subsequent {@code ADD} then works from the truncated value, which is why the truncation
     * happens once per addition here and not once at the end.
     * {@link CobolDecimal#truncateToPictureField(BigDecimal, int, int)} performs that store,
     * dropping any digit above the ninth and holding the sign.</p>
     *
     * <p>The precision constant is {@link PicClause#TRAN_AMT_PRECISION}, which
     * {@code NotificationRenderer} already documents as matching {@code WS-TOTAL-AMT} at
     * {@code app/cbl/CBSTM03A.CBL:L65}; the two fields carry the same Picture clause and the
     * renderer edits this total through the same pair of constants at
     * {@code app/cbl/CBSTM03A.CBL:L433}. Sharing them is what makes the total this method returns
     * equal, digit for digit, the total a rendered statement reports.</p>
     *
     * <p><b>A dropped digit is reported rather than hidden.</b> Transformation rule T7 of the
     * Agent Action Plan requires a reproduced source defect to be visible, so the one addition that
     * loses a high-order digit writes a warning. The warning names the capacity and the row count
     * and withholds the value, because a statement total belongs to one cardholder.
     * {@code card-platform/docs/business-rule-flags.md} carries the flag.</p>
     *
     * @param total the running total, as {@code WS-TOTAL-AMT} holds it
     * @param amount the amount to add
     * @return the new total, stored in the {@code WS-TOTAL-AMT} field shape
     */
    private static BigDecimal accumulate(BigDecimal total, BigDecimal amount) {
        BigDecimal sum = CobolDecimal.add(total, amount, PicClause.TRAN_AMT_SCALE);
        BigDecimal stored = CobolDecimal.truncateToPictureField(
                sum, PicClause.TRAN_AMT_PRECISION, PicClause.TRAN_AMT_SCALE);

        if (stored.compareTo(sum) != 0) {
            LOGGER.warn("A statement total needed more than the {} integer digits WS-TOTAL-AMT holds"
                            + " at app/cbl/CBSTM03A.CBL:L65, so the high-order digits were dropped"
                            + " where the source ADD at :L429 drops them. The rendered statement and"
                            + " this total agree, and both report less than the rows sum to. See"
                            + " docs/business-rule-flags.md.",
                    PicClause.TRAN_AMT_PRECISION - PicClause.TRAN_AMT_SCALE);
        }

        return stored;
    }

    /**
     * Writes one row recording that this service rendered one alert.
     *
     * <p>ADDITIVE, with no COBOL ancestor. The row carries the card token, the masked card number,
     * the transaction identifier at the width {@code TRNX-ID PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L23} declares, the format name and the instant of the attempt.
     * {@link NotificationLogEntity} declares no column for a rendered document, so no document is
     * stored.</p>
     *
     * @param cardToken the token of the card the alert covered
     * @param maskedCardNumber the masked card number
     * @param transactionId the transaction the alert reported
     * @param format the format the attempt carried
     */
    private void recordAttempt(String cardToken, String maskedCardNumber, String transactionId,
            RenderedFormat format) {
        this.deliveryAttempts.save(new NotificationLogEntity(UUID.randomUUID(), cardToken,
                maskedCardNumber,
                pic(transactionId, NotificationLogEntity.TRANSACTION_ID_LENGTH), format.name(),
                this.clock.instant()));
    }

    /**
     * Returns the metric tag one format records against.
     *
     * @param format the format that rendered the alert
     * @return the tag value, or {@link NotificationMetrics#UNKNOWN} for a format
     *         {@code config/ObservabilityConfig} registers no series for
     */
    private static String metricTag(RenderedFormat format) {
        return METRIC_TAGS.getOrDefault(format, NotificationMetrics.UNKNOWN);
    }

    /**
     * The cardholder fields an alert reports, as the caller holds them.
     *
     * <p>Every component traces to the {@code CUSTREC} copybook that
     * {@code app/cbl/CBSTM03A.CBL:L55} copies, except {@code accountId}, which each operation takes
     * separately. {@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L462-L485} reads the
     * same fields.</p>
     *
     * <p>A component may be {@code null} or blank, and then renders as spaces, which is the state
     * {@code INITIALIZE STATEMENT-LINES} at {@code app/cbl/CBSTM03A.CBL:L459} leaves.
     *
     * <p>There is deliberately no factory for an all-blank set. Each listener reads these fields
     * through {@link CardholderContextReader#require(String)}, which reports a missing projection row
     * rather than substituting blanks: an alert with no name and no address is a failure that used to
     * look like a rendering, and the only way it can be noticed is if nothing manufactures it.</p>
     *
     * @param firstName {@code CUST-FIRST-NAME}
     * @param middleName {@code CUST-MIDDLE-NAME}
     * @param lastName {@code CUST-LAST-NAME}
     * @param addressLine1 {@code CUST-ADDR-LINE-1}
     * @param addressLine2 {@code CUST-ADDR-LINE-2}
     * @param addressLine3 {@code CUST-ADDR-LINE-3}
     * @param stateCode {@code CUST-ADDR-STATE-CD}
     * @param countryCode {@code CUST-ADDR-COUNTRY-CD}
     * @param zipCode {@code CUST-ADDR-ZIP}
     * @param ficoScore {@code CUST-FICO-CREDIT-SCORE}
     */
    public record CardholderDetails(String firstName, String middleName, String lastName,
            String addressLine1, String addressLine2, String addressLine3, String stateCode,
            String countryCode, String zipCode, String ficoScore) {

        /**
         * Renders the type and the component count, and no component value.
         *
         * <p>Every component names a person, an address or a credit score. The rendering a record
         * generates carries all ten components, and reaches any log line or exception message that
         * names this record. A caller reads the fields through their accessors.</p>
         *
         * @return a fixed description carrying no cardholder value
         */
        @Override
        public String toString() {
            return "CardholderDetails[10 cardholder fields " + REDACTED + "]";
        }
    }
}
