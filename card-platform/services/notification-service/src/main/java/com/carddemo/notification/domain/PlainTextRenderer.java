package com.carddemo.notification.domain;

import static com.carddemo.notification.domain.NotificationRenderer.editTrailingSignZ;
import static com.carddemo.notification.domain.NotificationRenderer.pic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Renders one cardholder alert as fixed-width text records, reproducing the text statement of
 * {@code app/cbl/CBSTM03A.CBL}.
 *
 * <p>Every record holds exactly {@value #RECORD_WIDTH} characters, matching
 * {@code FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45}. The job that runs the
 * source program allocates the same width, {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at
 * {@code app/jcl/CREASTMT.JCL:L89}. One helper checks the width of every record before adding
 * it, so a builder that lost or gained a character fails at the record it built.</p>
 *
 * <p>{@link NotificationRenderer} defines the four COBOL terms these methods use.</p>
 *
 * <p>{@code 01 STATEMENT-LINES} at {@code app/cbl/CBSTM03A.CBL:L85-L146} declares 17 record groups,
 * and one method below builds each group. Four write blocks emit them in source order, and
 * {@code card-platform/docs/traceability-matrix.md} (planned) carries the record-by-record
 * mapping.</p>
 *
 * <p>Every field arrives at its final width. {@link CardholderContext} and
 * {@link TransactionRow} normalise each component in their canonical constructors, and the two
 * edited amounts they carry are already edited. This renderer edits the total only, because the
 * total is computed once per rendering.</p>
 *
 * <p>The class holds no state, so one instance serves every consumer thread.</p>
 *
 * @see NotificationRenderer
 */
@Component
public final class PlainTextRenderer implements NotificationRenderer {

    /**
     * Characters in every record this renderer emits.
     *
     * <p>Matches {@code FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45} and
     * {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at {@code app/jcl/CREASTMT.JCL:L89}.</p>
     */
    public static final int RECORD_WIDTH = 80;

    /**
     * Header records {@code 5000-CREATE-STATEMENT} writes at
     * {@code app/cbl/CBSTM03A.CBL:L488-L502}.
     *
     * <p>The block writes 15 records from 13 distinct groups. {@code ST-LINE5} is written twice
     * and {@code ST-LINE12} is written twice.</p>
     */
    public static final int HEADER_RECORD_COUNT = 15;

    /**
     * Trailer records {@code 4000-TRNXFILE-GET} writes at
     * {@code app/cbl/CBSTM03A.CBL:L435-L437}: the rule, the total and the closing banner.
     */
    public static final int TRAILER_RECORD_COUNT = 3;

    /**
     * Asterisk field width of {@code ST-LINE0}, {@code PIC X(31)} at
     * {@code app/cbl/CBSTM03A.CBL:L87} and {@code app/cbl/CBSTM03A.CBL:L89}.
     */
    private static final int OPENING_BANNER_FILL_WIDTH = 31;

    /**
     * Text field width of {@code ST-LINE0}, {@code PIC X(18)} at
     * {@code app/cbl/CBSTM03A.CBL:L88}.
     */
    private static final int OPENING_BANNER_TEXT_WIDTH = 18;

    /**
     * Asterisk field width of {@code ST-LINE15}, {@code PIC X(32)} at
     * {@code app/cbl/CBSTM03A.CBL:L144} and {@code app/cbl/CBSTM03A.CBL:L146}. The opening
     * banner divides its 80 characters differently.
     */
    private static final int CLOSING_BANNER_FILL_WIDTH = 32;

    /**
     * Text field width of {@code ST-LINE15}, {@code PIC X(16)} at
     * {@code app/cbl/CBSTM03A.CBL:L145}.
     */
    private static final int CLOSING_BANNER_TEXT_WIDTH = 16;

    /**
     * Filler width of {@code ST-LINE1}, {@code PIC X(05)} at {@code app/cbl/CBSTM03A.CBL:L92}.
     */
    private static final int NAME_FILLER_WIDTH = 5;

    /**
     * Filler width of {@code ST-LINE2} and {@code ST-LINE3}, {@code PIC X(30)} at
     * {@code app/cbl/CBSTM03A.CBL:L95} and {@code app/cbl/CBSTM03A.CBL:L98}.
     */
    private static final int ADDRESS_FILLER_WIDTH = 30;

    /**
     * Leading and trailing filler width of {@code ST-LINE6}, {@code PIC X(33)} at
     * {@code app/cbl/CBSTM03A.CBL:L104} and {@code app/cbl/CBSTM03A.CBL:L106}.
     */
    private static final int BASIC_DETAILS_FILLER_WIDTH = 33;

    /**
     * Heading field width of {@code ST-LINE6}, {@code PIC X(14)} at
     * {@code app/cbl/CBSTM03A.CBL:L105}. The literal it holds is 13 characters, so the field
     * carries one trailing space.
     */
    private static final int BASIC_DETAILS_HEADING_WIDTH = 14;

    /**
     * Label width of {@code ST-LINE7}, {@code ST-LINE8} and {@code ST-LINE9},
     * {@code PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L108},
     * {@code app/cbl/CBSTM03A.CBL:L112} and {@code app/cbl/CBSTM03A.CBL:L117}. Each of the
     * three literals fills the field exactly.
     */
    private static final int LABEL_WIDTH = 20;

    /**
     * Trailing filler width of {@code ST-LINE7}, {@code ST-LINE8} and {@code ST-LINE9},
     * {@code PIC X(40)} at {@code app/cbl/CBSTM03A.CBL:L110},
     * {@code app/cbl/CBSTM03A.CBL:L115} and {@code app/cbl/CBSTM03A.CBL:L119}.
     */
    private static final int LABEL_TRAILING_FILLER_WIDTH = 40;

    /**
     * First of the two trailing fillers of {@code ST-LINE8}, {@code PIC X(07)} at
     * {@code app/cbl/CBSTM03A.CBL:L114}. The second is
     * {@link #LABEL_TRAILING_FILLER_WIDTH} at {@code app/cbl/CBSTM03A.CBL:L115}.
     */
    private static final int BALANCE_FIRST_FILLER_WIDTH = 7;

    /**
     * Leading and trailing filler width of {@code ST-LINE11}, {@code PIC X(30)} at
     * {@code app/cbl/CBSTM03A.CBL:L123} and {@code app/cbl/CBSTM03A.CBL:L125}.
     */
    private static final int SUMMARY_FILLER_WIDTH = 30;

    /**
     * Heading field width of {@code ST-LINE11}, {@code PIC X(20)} at
     * {@code app/cbl/CBSTM03A.CBL:L124}. The literal it holds carries its own trailing space
     * and fills the field exactly.
     */
    private static final int SUMMARY_HEADING_WIDTH = 20;

    /**
     * First column heading width of {@code ST-LINE13}, {@code PIC X(16)} at
     * {@code app/cbl/CBSTM03A.CBL:L129}.
     */
    private static final int TRAN_ID_COLUMN_WIDTH = 16;

    /**
     * Second column heading width of {@code ST-LINE13}, {@code PIC X(51)} at
     * {@code app/cbl/CBSTM03A.CBL:L130}. The literal it holds is 16 characters, so the field
     * carries 35 trailing spaces.
     */
    private static final int TRAN_DETAILS_COLUMN_WIDTH = 51;

    /**
     * Third column heading width of {@code ST-LINE13}, {@code PIC X(13)} at
     * {@code app/cbl/CBSTM03A.CBL:L131}. The literal it holds fills the field exactly and
     * carries two leading spaces.
     */
    private static final int TRAN_AMOUNT_COLUMN_WIDTH = 13;

    /**
     * Separator width of {@code ST-LINE14}, {@code PIC X(01)} at
     * {@code app/cbl/CBSTM03A.CBL:L134}.
     */
    private static final int DETAIL_SEPARATOR_WIDTH = 1;

    /**
     * Currency sign width of {@code ST-LINE14} and {@code ST-LINE14A}, {@code PIC X(01)} at
     * {@code app/cbl/CBSTM03A.CBL:L136} and {@code app/cbl/CBSTM03A.CBL:L141}.
     */
    private static final int CURRENCY_SIGN_WIDTH = 1;

    /**
     * Label width of {@code ST-LINE14A}, {@code PIC X(10)} at
     * {@code app/cbl/CBSTM03A.CBL:L139}.
     */
    private static final int TOTAL_LABEL_WIDTH = 10;

    /**
     * Filler width of {@code ST-LINE14A}, {@code PIC X(56)} at
     * {@code app/cbl/CBSTM03A.CBL:L140}.
     */
    private static final int TOTAL_FILLER_WIDTH = 56;

    /**
     * Width of the rule field of {@code ST-LINE5}, {@code ST-LINE10} and {@code ST-LINE12},
     * {@code PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L102},
     * {@code app/cbl/CBSTM03A.CBL:L121} and {@code app/cbl/CBSTM03A.CBL:L127}. The field spans
     * the whole record.
     */
    private static final int RULE_WIDTH = RECORD_WIDTH;

    /**
     * Width the fraud alert gives a joined rule identifier list. ADDITIVE, with no COBOL
     * ancestor.
     *
     * <p>A rule identifier list outgrows the {@link #ST_ACCT_ID_WIDTH} value field of
     * {@code ST-LINE7}. The fraud alert therefore spends that field and its trailing filler on
     * one field: label {@value #LABEL_WIDTH} and value {@value #FRAUD_RULE_LIST_WIDTH},
     * totalling {@value #RECORD_WIDTH}.</p>
     */
    private static final int FRAUD_RULE_LIST_WIDTH = RECORD_WIDTH - LABEL_WIDTH;

    /**
     * Character the two banner fillers repeat, {@code VALUE ALL '*'} at
     * {@code app/cbl/CBSTM03A.CBL:L87}, {@code app/cbl/CBSTM03A.CBL:L89},
     * {@code app/cbl/CBSTM03A.CBL:L144} and {@code app/cbl/CBSTM03A.CBL:L146}.
     */
    private static final String BANNER_FILL_CHARACTER = "*";

    /**
     * Character the three rule records repeat, {@code VALUE ALL '-'} at
     * {@code app/cbl/CBSTM03A.CBL:L102}, {@code app/cbl/CBSTM03A.CBL:L121} and
     * {@code app/cbl/CBSTM03A.CBL:L127}.
     */
    private static final String RULE_CHARACTER = "-";

    /**
     * Text of {@code ST-LINE0}, {@code VALUE ALL 'START OF STATEMENT'} at
     * {@code app/cbl/CBSTM03A.CBL:L88}.
     *
     * <p>{@code ALL} repeats a literal until the field is full. This literal is 18 characters in
     * an {@code X(18)} field, so it appears once.</p>
     */
    private static final String OPENING_BANNER_TEXT = "START OF STATEMENT";

    /**
     * Text of {@code ST-LINE15}, {@code VALUE ALL 'END OF STATEMENT'} at
     * {@code app/cbl/CBSTM03A.CBL:L145}.
     *
     * <p>This literal is 16 characters in an {@code X(16)} field, so {@code ALL} places it
     * once.</p>
     */
    private static final String CLOSING_BANNER_TEXT = "END OF STATEMENT";

    /**
     * Heading of {@code ST-LINE6}, {@code VALUE 'Basic Details'} at
     * {@code app/cbl/CBSTM03A.CBL:L105}.
     */
    private static final String BASIC_DETAILS_HEADING = "Basic Details";

    /**
     * Label of {@code ST-LINE7}, {@code VALUE 'Account ID         :'} at
     * {@code app/cbl/CBSTM03A.CBL:L108}.
     */
    private static final String ACCOUNT_ID_LABEL = "Account ID         :";

    /**
     * Label of {@code ST-LINE8}, {@code VALUE 'Current Balance    :'} at
     * {@code app/cbl/CBSTM03A.CBL:L112}.
     */
    private static final String CURRENT_BALANCE_LABEL = "Current Balance    :";

    /**
     * Label of {@code ST-LINE9}, {@code VALUE 'FICO Score         :'} at
     * {@code app/cbl/CBSTM03A.CBL:L117}.
     */
    private static final String FICO_SCORE_LABEL = "FICO Score         :";

    /**
     * Heading of {@code ST-LINE11}, {@code VALUE 'TRANSACTION SUMMARY '} at
     * {@code app/cbl/CBSTM03A.CBL:L124}. The literal carries its own trailing space.
     */
    private static final String SUMMARY_HEADING = "TRANSACTION SUMMARY ";

    /**
     * First column heading of {@code ST-LINE13}, {@code VALUE 'Tran ID         '} at
     * {@code app/cbl/CBSTM03A.CBL:L129}.
     */
    private static final String TRAN_ID_COLUMN_HEADING = "Tran ID         ";

    /**
     * Second column heading of {@code ST-LINE13}, {@code VALUE 'Tran Details    '} at
     * {@code app/cbl/CBSTM03A.CBL:L130}.
     */
    private static final String TRAN_DETAILS_COLUMN_HEADING = "Tran Details    ";

    /**
     * Third column heading of {@code ST-LINE13}, {@code VALUE '  Tran Amount'} at
     * {@code app/cbl/CBSTM03A.CBL:L131}. The literal carries its own two leading spaces.
     */
    private static final String TRAN_AMOUNT_COLUMN_HEADING = "  Tran Amount";

    /**
     * Separator of {@code ST-LINE14}, {@code VALUE ' '} at
     * {@code app/cbl/CBSTM03A.CBL:L134}.
     */
    private static final String DETAIL_SEPARATOR = " ";

    /**
     * Currency sign of {@code ST-LINE14} and {@code ST-LINE14A}, {@code VALUE '$'} at
     * {@code app/cbl/CBSTM03A.CBL:L136} and {@code app/cbl/CBSTM03A.CBL:L141}.
     */
    private static final String CURRENCY_SIGN = "$";

    /**
     * Label of {@code ST-LINE14A}, {@code VALUE 'Total EXP:'} at
     * {@code app/cbl/CBSTM03A.CBL:L139}.
     */
    private static final String TOTAL_LABEL = "Total EXP:";

    /**
     * Label the fraud alert gives the flagged transaction. ADDITIVE, with no COBOL ancestor.
     * Holds {@value #LABEL_WIDTH} characters, matching the labels of {@code ST-LINE7} through
     * {@code ST-LINE9}.
     */
    private static final String TRANSACTION_ID_LABEL = "Transaction ID     :";

    /**
     * Label the fraud alert gives the risk score. ADDITIVE, with no COBOL ancestor. Holds
     * {@value #LABEL_WIDTH} characters.
     */
    private static final String RISK_SCORE_LABEL = "Risk Score         :";

    /**
     * Label the fraud alert gives the rule identifier list. ADDITIVE, with no COBOL ancestor.
     * Holds {@value #LABEL_WIDTH} characters.
     */
    private static final String TRIGGERED_RULES_LABEL = "Triggered Rules    :";

    /**
     * Separator the fraud alert places between rule identifiers. ADDITIVE, with no COBOL
     * ancestor.
     */
    private static final String TRIGGERED_RULE_SEPARATOR = ", ";

    public PlainTextRenderer() {
    }

    /**
     * Reports that this renderer produces fixed-width text.
     *
     * @return {@link RenderedFormat#PLAIN_TEXT}, matching
     *         {@code FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45}
     */
    @Override
    public RenderedFormat format() {
        return RenderedFormat.PLAIN_TEXT;
    }

    /**
     * Renders a statement covering one cardholder and the supplied transactions.
     *
     * <p>Emits the opening banner from {@code app/cbl/CBSTM03A.CBL:L460}, the
     * {@value #HEADER_RECORD_COUNT} header records from
     * {@code app/cbl/CBSTM03A.CBL:L488-L502}, one detail record per row from
     * {@code app/cbl/CBSTM03A.CBL:L679}, and the {@value #TRAILER_RECORD_COUNT} trailer records
     * from {@code app/cbl/CBSTM03A.CBL:L435-L437}.</p>
     *
     * <p>An empty row list yields the banner, the header and the trailer, and no detail record.
     * A blank or space-filled field renders as spaces and raises nothing.</p>
     *
     * @param context the cardholder fields, already assembled and already edited; must not be
     *                {@code null}
     * @param rows    the detail rows in the order they render; must not be {@code null}, and no
     *                element may be {@code null}
     * @param total   the transaction total, matching {@code WS-TOTAL-AMT PIC S9(9)V99} at
     *                {@code app/cbl/CBSTM03A.CBL:L65}; must not be {@code null}
     * @return the records joined by {@link #LINE_SEPARATOR}, each
     *         {@value #RECORD_WIDTH} characters wide
     * @throws NullPointerException     if any argument is {@code null}, or if a row is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code rows} holds more than
     *                                  {@link NotificationRenderer#MAXIMUM_STATEMENT_ROWS}
     *                                  elements
     */
    @Override
    public String renderStatementAlert(CardholderContext context, List<TransactionRow> rows,
                                       BigDecimal total) {
        Objects.requireNonNull(context, "context must not be null");
        NotificationRenderer.requireRenderableRowCount(rows);
        Objects.requireNonNull(total, "total must not be null");

        List<String> records = new ArrayList<>();

        writeOpeningBanner(records);
        writeHeaderRecords(records, context);
        for (TransactionRow row : rows) {
            writeDetailRecord(records, row);
        }
        writeTrailerRecords(records, total);

        return String.join(LINE_SEPARATOR, records);
    }

    /**
     * Renders an alert covering one flagged transaction.
     *
     * <p>ADDITIVE. {@code app/cbl/CBSTM03A.CBL} carries no fraud concept, so this operation has
     * no COBOL ancestor. The alert borrows the statement vocabulary, including the banners of
     * {@code ST-LINE0} and {@code ST-LINE15}. It also reuses the rule of {@code ST-LINE5} and
     * {@code ST-LINE12}, the name record of {@code ST-LINE1}, and the label-and-value shape of
     * {@code ST-LINE7}.</p>
     *
     * <p>The alert carries the cardholder name, the account identifier, the flagged transaction
     * identifier, the risk score and the rule identifiers that fired. It carries no amount and no
     * card number, matching the payload of the {@code FraudFlagged} event.</p>
     *
     * <p>Every record holds {@value #RECORD_WIDTH} characters. A blank field and an empty rule
     * list both render as spaces.</p>
     *
     * @param context        the cardholder fields, already assembled and already edited; must not
     *                       be {@code null}
     * @param transactionId  the identifier of the flagged transaction; must not be {@code null}
     * @param riskScore      the score the fraud service assigned
     * @param triggeredRules the identifiers of the rules that fired; must not be {@code null},
     *                       and no element may be {@code null}
     * @return the records joined by {@link #LINE_SEPARATOR}, each
     *         {@value #RECORD_WIDTH} characters wide
     * @throws NullPointerException if any argument is {@code null}, or if a rule identifier is
     *                              {@code null}
     */
    @Override
    public String renderFraudAlert(CardholderContext context, String transactionId, int riskScore,
                                   List<String> triggeredRules) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(triggeredRules, "triggeredRules must not be null");

        List<String> records = new ArrayList<>();

        writeOpeningBanner(records);
        append(records, "ST-LINE1", nameRecord(context));
        append(records, "ST-LINE5", separatorRuleRecord());
        append(records, "ST-LINE7", accountIdRecord(context));
        append(records, "ST-LINE7", labelledValueRecord(TRANSACTION_ID_LABEL, transactionId));
        append(records, "ST-LINE7",
                labelledValueRecord(RISK_SCORE_LABEL, Integer.toString(riskScore)));
        append(records, "ST-LINE7", triggeredRulesRecord(triggeredRules));
        append(records, "ST-LINE12", separatorRuleRecord());
        writeClosingBanner(records);

        return String.join(LINE_SEPARATOR, records);
    }

    /**
     * Writes the opening banner, which {@code 5000-CREATE-STATEMENT} emits on its own at
     * {@code app/cbl/CBSTM03A.CBL:L460}, before any header record.
     *
     * @param records the records built so far
     */
    private static void writeOpeningBanner(List<String> records) {
        append(records, "ST-LINE0", openingBannerRecord());
    }

    /**
     * Writes the {@value #HEADER_RECORD_COUNT} header records of
     * {@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L488-L502}, in source order.
     *
     * <p>The block writes 15 records from 13 distinct groups. {@code ST-LINE5} brackets the
     * {@code Basic Details} heading, and {@code ST-LINE12} brackets the column heading, so each
     * is written twice.</p>
     *
     * @param records the records built so far
     * @param context the cardholder fields
     */
    private static void writeHeaderRecords(List<String> records, CardholderContext context) {
        append(records, "ST-LINE1", nameRecord(context));
        append(records, "ST-LINE2", addressLine1Record(context));
        append(records, "ST-LINE3", addressLine2Record(context));
        append(records, "ST-LINE4", addressLine3Record(context));
        append(records, "ST-LINE5", separatorRuleRecord());
        append(records, "ST-LINE6", basicDetailsHeadingRecord());
        append(records, "ST-LINE5", separatorRuleRecord());
        append(records, "ST-LINE7", accountIdRecord(context));
        append(records, "ST-LINE8", currentBalanceRecord(context));
        append(records, "ST-LINE9", ficoScoreRecord(context));
        append(records, "ST-LINE10", separatorRuleRecord());
        append(records, "ST-LINE11", summaryHeadingRecord());
        append(records, "ST-LINE12", separatorRuleRecord());
        append(records, "ST-LINE13", columnHeadingRecord());
        append(records, "ST-LINE12", separatorRuleRecord());
    }

    /**
     * Writes one detail record, which {@code 6000-WRITE-TRANS} emits once per transaction at
     * {@code app/cbl/CBSTM03A.CBL:L679}.
     *
     * @param records the records built so far
     * @param row     the transaction to render; must not be {@code null}
     * @throws NullPointerException if {@code row} is {@code null}
     */
    private static void writeDetailRecord(List<String> records, TransactionRow row) {
        Objects.requireNonNull(row, "rows must not contain a null element");

        append(records, "ST-LINE14", detailRecord(row));
    }

    /**
     * Writes the {@value #TRAILER_RECORD_COUNT} trailer records of
     * {@code 4000-TRNXFILE-GET} at {@code app/cbl/CBSTM03A.CBL:L435-L437}: the rule, the total
     * and the closing banner.
     *
     * @param records the records built so far
     * @param total   the transaction total
     */
    private static void writeTrailerRecords(List<String> records, BigDecimal total) {
        append(records, "ST-LINE12", separatorRuleRecord());
        append(records, "ST-LINE14A", totalRecord(total));
        writeClosingBanner(records);
    }

    /**
     * Writes the closing banner, the last record of
     * {@code 4000-TRNXFILE-GET} at {@code app/cbl/CBSTM03A.CBL:L437}.
     *
     * @param records the records built so far
     */
    private static void writeClosingBanner(List<String> records) {
        append(records, "ST-LINE15", closingBannerRecord());
    }

    /**
     * Builds {@code ST-LINE0} from {@code app/cbl/CBSTM03A.CBL:L86-L89}.
     *
     * <p>Composition: asterisks {@value #OPENING_BANNER_FILL_WIDTH}, text
     * {@value #OPENING_BANNER_TEXT_WIDTH}, asterisks
     * {@value #OPENING_BANNER_FILL_WIDTH}, totalling {@value #RECORD_WIDTH}. The text field
     * carries {@link #OPENING_BANNER_TEXT} once.</p>
     *
     * @return the opening banner record
     */
    private static String openingBannerRecord() {
        return BANNER_FILL_CHARACTER.repeat(OPENING_BANNER_FILL_WIDTH)
                + pic(OPENING_BANNER_TEXT, OPENING_BANNER_TEXT_WIDTH)
                + BANNER_FILL_CHARACTER.repeat(OPENING_BANNER_FILL_WIDTH);
    }

    /**
     * Builds {@code ST-LINE1} from {@code app/cbl/CBSTM03A.CBL:L90-L92}.
     *
     * <p>Composition: name {@value #ST_NAME_WIDTH}, filler {@value #NAME_FILLER_WIDTH},
     * totalling {@value #RECORD_WIDTH}. The name is the output of
     * {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L462-L469}.</p>
     *
     * @param context the cardholder fields
     * @return the name record
     */
    private static String nameRecord(CardholderContext context) {
        return pic(context.assembledName(), ST_NAME_WIDTH)
                + spaces(NAME_FILLER_WIDTH);
    }

    /**
     * Builds {@code ST-LINE2} from {@code app/cbl/CBSTM03A.CBL:L93-L95}.
     *
     * <p>Composition: address {@value #ST_ADD1_WIDTH}, filler
     * {@value #ADDRESS_FILLER_WIDTH}, totalling {@value #RECORD_WIDTH}. The
     * {@code MOVE CUST-ADDR-LINE-1 TO ST-ADD1} at {@code app/cbl/CBSTM03A.CBL:L470} fills
     * it.</p>
     *
     * @param context the cardholder fields
     * @return the first address record
     */
    private static String addressLine1Record(CardholderContext context) {
        return pic(context.addressLine1(), ST_ADD1_WIDTH)
                + spaces(ADDRESS_FILLER_WIDTH);
    }

    /**
     * Builds {@code ST-LINE3} from {@code app/cbl/CBSTM03A.CBL:L96-L98}.
     *
     * <p>Composition: address {@value #ST_ADD2_WIDTH}, filler
     * {@value #ADDRESS_FILLER_WIDTH}, totalling {@value #RECORD_WIDTH}. The
     * {@code MOVE CUST-ADDR-LINE-2 TO ST-ADD2} at {@code app/cbl/CBSTM03A.CBL:L471} fills
     * it.</p>
     *
     * @param context the cardholder fields
     * @return the second address record
     */
    private static String addressLine2Record(CardholderContext context) {
        return pic(context.addressLine2(), ST_ADD2_WIDTH)
                + spaces(ADDRESS_FILLER_WIDTH);
    }

    /**
     * Builds {@code ST-LINE4} from {@code app/cbl/CBSTM03A.CBL:L99-L100}.
     *
     * <p>Composition: one field of {@value #ST_ADD3_WIDTH} and no filler. The group declares
     * {@code ST-ADD3 PIC X(80)} alone, unlike {@code ST-LINE2} and {@code ST-LINE3}. The value
     * is the output of {@code STRING} at {@code app/cbl/CBSTM03A.CBL:L472-L481}.</p>
     *
     * @param context the cardholder fields
     * @return the third address record
     */
    private static String addressLine3Record(CardholderContext context) {
        return pic(context.addressLine3(), ST_ADD3_WIDTH);
    }

    /**
     * Builds {@code ST-LINE5}, {@code ST-LINE10} and {@code ST-LINE12} from
     * {@code app/cbl/CBSTM03A.CBL:L101-L102}, {@code app/cbl/CBSTM03A.CBL:L120-L121} and
     * {@code app/cbl/CBSTM03A.CBL:L126-L127}.
     *
     * <p>Each of the three groups declares one {@code FILLER VALUE ALL '-' PIC X(80)}, so all
     * three records carry the same {@value #RULE_WIDTH} characters. The write blocks name which
     * group each record reproduces.</p>
     *
     * @return a rule record
     */
    private static String separatorRuleRecord() {
        return RULE_CHARACTER.repeat(RULE_WIDTH);
    }

    /**
     * Builds {@code ST-LINE6} from {@code app/cbl/CBSTM03A.CBL:L103-L106}.
     *
     * <p>Composition: filler {@value #BASIC_DETAILS_FILLER_WIDTH}, heading
     * {@value #BASIC_DETAILS_HEADING_WIDTH}, filler
     * {@value #BASIC_DETAILS_FILLER_WIDTH}, totalling {@value #RECORD_WIDTH}. The heading
     * literal is 13 characters, so the heading field ends with one space.</p>
     *
     * @return the {@code Basic Details} heading record
     */
    private static String basicDetailsHeadingRecord() {
        return spaces(BASIC_DETAILS_FILLER_WIDTH)
                + pic(BASIC_DETAILS_HEADING, BASIC_DETAILS_HEADING_WIDTH)
                + spaces(BASIC_DETAILS_FILLER_WIDTH);
    }

    /**
     * Builds {@code ST-LINE7} from {@code app/cbl/CBSTM03A.CBL:L107-L110}.
     *
     * <p>Composition: label {@value #LABEL_WIDTH}, account identifier
     * {@value #ST_ACCT_ID_WIDTH}, filler {@value #LABEL_TRAILING_FILLER_WIDTH}, totalling
     * {@value #RECORD_WIDTH}. The {@code MOVE ACCT-ID TO ST-ACCT-ID} at
     * {@code app/cbl/CBSTM03A.CBL:L483} fills the value field.</p>
     *
     * @param context the cardholder fields
     * @return the account identifier record
     */
    private static String accountIdRecord(CardholderContext context) {
        return labelledValueRecord(ACCOUNT_ID_LABEL, context.accountId());
    }

    /**
     * Builds {@code ST-LINE8} from {@code app/cbl/CBSTM03A.CBL:L111-L115}.
     *
     * <p>Composition: label {@value #LABEL_WIDTH}, edited balance
     * {@value #EDITED_AMOUNT_WIDTH}, filler {@value #BALANCE_FIRST_FILLER_WIDTH}, filler
     * {@value #LABEL_TRAILING_FILLER_WIDTH}, totalling {@value #RECORD_WIDTH}. The group
     * declares two consecutive fillers rather than one.</p>
     *
     * <p>The balance arrives edited. {@code ST-CURR-BAL} is {@code PIC 9(9).99-} at
     * {@code app/cbl/CBSTM03A.CBL:L113}, so every digit position renders a digit, and
     * {@link CardholderContext#editedCurrentBalance()} already carries that form.</p>
     *
     * @param context the cardholder fields
     * @return the current balance record
     */
    private static String currentBalanceRecord(CardholderContext context) {
        return pic(CURRENT_BALANCE_LABEL, LABEL_WIDTH)
                + pic(context.editedCurrentBalance(), EDITED_AMOUNT_WIDTH)
                + spaces(BALANCE_FIRST_FILLER_WIDTH)
                + spaces(LABEL_TRAILING_FILLER_WIDTH);
    }

    /**
     * Builds {@code ST-LINE9} from {@code app/cbl/CBSTM03A.CBL:L116-L119}.
     *
     * <p>Composition: label {@value #LABEL_WIDTH}, score {@value #ST_FICO_SCORE_WIDTH}, filler
     * {@value #LABEL_TRAILING_FILLER_WIDTH}, totalling {@value #RECORD_WIDTH}. The
     * {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE} at
     * {@code app/cbl/CBSTM03A.CBL:L485} fills the value field.</p>
     *
     * @param context the cardholder fields
     * @return the credit score record
     */
    private static String ficoScoreRecord(CardholderContext context) {
        return labelledValueRecord(FICO_SCORE_LABEL, context.ficoScore());
    }

    /**
     * Builds {@code ST-LINE11} from {@code app/cbl/CBSTM03A.CBL:L122-L125}.
     *
     * <p>Composition: filler {@value #SUMMARY_FILLER_WIDTH}, heading
     * {@value #SUMMARY_HEADING_WIDTH}, filler {@value #SUMMARY_FILLER_WIDTH}, totalling
     * {@value #RECORD_WIDTH}. The heading literal carries its own trailing space and fills its
     * field exactly.</p>
     *
     * @return the {@code TRANSACTION SUMMARY} heading record
     */
    private static String summaryHeadingRecord() {
        return spaces(SUMMARY_FILLER_WIDTH)
                + pic(SUMMARY_HEADING, SUMMARY_HEADING_WIDTH)
                + spaces(SUMMARY_FILLER_WIDTH);
    }

    /**
     * Builds {@code ST-LINE13} from {@code app/cbl/CBSTM03A.CBL:L128-L131}.
     *
     * <p>Composition: first heading {@value #TRAN_ID_COLUMN_WIDTH}, second heading
     * {@value #TRAN_DETAILS_COLUMN_WIDTH}, third heading
     * {@value #TRAN_AMOUNT_COLUMN_WIDTH}, totalling {@value #RECORD_WIDTH}. The second literal
     * is 16 characters in a field of {@value #TRAN_DETAILS_COLUMN_WIDTH}, so 35 spaces follow
     * it. The third literal carries two leading spaces.</p>
     *
     * @return the column heading record
     */
    private static String columnHeadingRecord() {
        return pic(TRAN_ID_COLUMN_HEADING, TRAN_ID_COLUMN_WIDTH)
                + pic(TRAN_DETAILS_COLUMN_HEADING, TRAN_DETAILS_COLUMN_WIDTH)
                + pic(TRAN_AMOUNT_COLUMN_HEADING, TRAN_AMOUNT_COLUMN_WIDTH);
    }

    /**
     * Builds {@code ST-LINE14} from {@code app/cbl/CBSTM03A.CBL:L132-L137}.
     *
     * <p>Composition: transaction identifier {@value #ST_TRANID_WIDTH}, separator
     * {@value #DETAIL_SEPARATOR_WIDTH}, description {@value #ST_TRANDT_WIDTH}, currency sign
     * {@value #CURRENCY_SIGN_WIDTH}, edited amount {@value #EDITED_AMOUNT_WIDTH}, totalling
     * {@value #RECORD_WIDTH}.</p>
     *
     * <p>The description contributes {@value #ST_TRANDT_WIDTH} characters. {@code TRNX-DESC} is
     * {@code PIC X(100)} at {@code app/cpy/COSTM01.CPY:L28} and {@code ST-TRANDT} is
     * {@code PIC X(49)} at {@code app/cbl/CBSTM03A.CBL:L135}, so the
     * {@code MOVE TRNX-DESC TO ST-TRANDT} at {@code app/cbl/CBSTM03A.CBL:L677} drops the last 51
     * characters. The loss belongs to this width alone, and the stored column keeps all 100.</p>
     *
     * <p>The amount arrives edited. {@code ST-TRANAMT} is {@code PIC Z(9).99-} at
     * {@code app/cbl/CBSTM03A.CBL:L137}, so a leading zero renders as a space, and
     * {@link TransactionRow#editedAmount()} already carries that form.</p>
     *
     * @param row the transaction to render
     * @return the detail record
     */
    private static String detailRecord(TransactionRow row) {
        return pic(row.transactionId(), ST_TRANID_WIDTH)
                + pic(DETAIL_SEPARATOR, DETAIL_SEPARATOR_WIDTH)
                + pic(row.description(), ST_TRANDT_WIDTH)
                + pic(CURRENCY_SIGN, CURRENCY_SIGN_WIDTH)
                + pic(row.editedAmount(), EDITED_AMOUNT_WIDTH);
    }

    /**
     * Builds {@code ST-LINE14A} from {@code app/cbl/CBSTM03A.CBL:L138-L142}.
     *
     * <p>Composition: label {@value #TOTAL_LABEL_WIDTH}, filler
     * {@value #TOTAL_FILLER_WIDTH}, currency sign {@value #CURRENCY_SIGN_WIDTH}, edited total
     * {@value #EDITED_AMOUNT_WIDTH}, totalling {@value #RECORD_WIDTH}.</p>
     *
     * <p>This record carries the only amount the renderer edits, because the total is computed
     * once per rendering. {@code ST-TOTAL-TRAMT} is {@code PIC Z(9).99-} at
     * {@code app/cbl/CBSTM03A.CBL:L142}, so
     * {@link NotificationRenderer#editTrailingSignZ(BigDecimal)} applies. That helper truncates
     * toward zero, matching every arithmetic store in the source.</p>
     *
     * <p>{@code MOVE WS-TOTAL-AMT TO WS-TRN-AMT} at {@code app/cbl/CBSTM03A.CBL:L433} converts a
     * packed field to a display field before the edit at {@code app/cbl/CBSTM03A.CBL:L434}. The
     * conversion changes no value, so one edit reproduces both moves.</p>
     *
     * @param total the transaction total
     * @return the total record
     */
    private static String totalRecord(BigDecimal total) {
        return pic(TOTAL_LABEL, TOTAL_LABEL_WIDTH)
                + spaces(TOTAL_FILLER_WIDTH)
                + pic(CURRENCY_SIGN, CURRENCY_SIGN_WIDTH)
                + pic(editTrailingSignZ(total), EDITED_AMOUNT_WIDTH);
    }

    /**
     * Builds {@code ST-LINE15} from {@code app/cbl/CBSTM03A.CBL:L143-L146}.
     *
     * <p>Composition: asterisks {@value #CLOSING_BANNER_FILL_WIDTH}, text
     * {@value #CLOSING_BANNER_TEXT_WIDTH}, asterisks
     * {@value #CLOSING_BANNER_FILL_WIDTH}, totalling {@value #RECORD_WIDTH}. The opening banner
     * divides its {@value #RECORD_WIDTH} characters differently, so the two records differ. The
     * text field carries {@link #CLOSING_BANNER_TEXT} once.</p>
     *
     * @return the closing banner record
     */
    private static String closingBannerRecord() {
        return BANNER_FILL_CHARACTER.repeat(CLOSING_BANNER_FILL_WIDTH)
                + pic(CLOSING_BANNER_TEXT, CLOSING_BANNER_TEXT_WIDTH)
                + BANNER_FILL_CHARACTER.repeat(CLOSING_BANNER_FILL_WIDTH);
    }

    /**
     * Builds a record in the shape of {@code ST-LINE7} at
     * {@code app/cbl/CBSTM03A.CBL:L107-L110}.
     *
     * <p>Composition: label {@value #LABEL_WIDTH}, value {@value #ST_ACCT_ID_WIDTH}, filler
     * {@value #LABEL_TRAILING_FILLER_WIDTH}, totalling {@value #RECORD_WIDTH}.
     * {@code ST-LINE9} at {@code app/cbl/CBSTM03A.CBL:L116-L119} declares the same three
     * widths, so both statement records and the fraud alert share this shape.</p>
     *
     * @param label the label, {@value #LABEL_WIDTH} characters in every caller
     * @param value the value the label introduces
     * @return the labelled value record
     */
    private static String labelledValueRecord(String label, String value) {
        return pic(label, LABEL_WIDTH)
                + pic(value, ST_ACCT_ID_WIDTH)
                + spaces(LABEL_TRAILING_FILLER_WIDTH);
    }

    /**
     * Builds the rule identifier record of the fraud alert. ADDITIVE, with no COBOL ancestor.
     *
     * <p>Composition: label {@value #LABEL_WIDTH}, joined identifiers
     * {@value #FRAUD_RULE_LIST_WIDTH}, totalling {@value #RECORD_WIDTH}. The value field spans
     * the value field and the trailing filler of {@code ST-LINE7}, because a rule identifier list
     * outgrows {@value #ST_ACCT_ID_WIDTH} characters.</p>
     *
     * <p>An empty list renders as spaces. A list longer than the field loses its tail, which is
     * what a COBOL {@code MOVE} into a shorter alphanumeric field does.</p>
     *
     * @param triggeredRules the identifiers of the rules that fired; no element may be
     *                       {@code null}
     * @return the rule identifier record
     * @throws NullPointerException if a rule identifier is {@code null}
     */
    private static String triggeredRulesRecord(List<String> triggeredRules) {
        for (String rule : triggeredRules) {
            Objects.requireNonNull(rule, "triggeredRules must not contain a null element");
        }

        return pic(TRIGGERED_RULES_LABEL, LABEL_WIDTH)
                + pic(String.join(TRIGGERED_RULE_SEPARATOR, triggeredRules),
                        FRAUD_RULE_LIST_WIDTH);
    }

    /**
     * Adds one record after checking its width.
     *
     * <p>Every record of {@code STMT-FILE} is {@code FD-STMTFILE-REC PIC X(80)} at
     * {@code app/cbl/CBSTM03A.CBL:L45}. A record of any other width means a builder above
     * miscounted, so this method rejects it rather than emitting it.</p>
     *
     * <p>The message names the source group and the width found, and carries no field value:
     * every value-bearing field of a statement names a cardholder, an account or an amount.</p>
     *
     * @param records  the records built so far
     * @param lineName the source group the record reproduces, such as {@code ST-LINE8}
     * @param record   the record to add
     * @throws IllegalStateException if the record is not {@value #RECORD_WIDTH} characters wide
     */
    private static void append(List<String> records, String lineName, String record) {
        if (record.length() != RECORD_WIDTH) {
            throw new IllegalStateException("record width must be " + RECORD_WIDTH
                    + ": line=" + lineName + ", width=" + record.length());
        }

        records.add(record);
    }

    /**
     * Builds a {@code FILLER VALUE SPACES} field of the given width.
     *
     * <p>Delegates to {@link NotificationRenderer#pic(String, int)}, so a filler and a value
     * field are padded by the same helper.</p>
     *
     * @param width the field width, taken from a Picture clause
     * @return a string of exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return pic("", width);
    }
}
