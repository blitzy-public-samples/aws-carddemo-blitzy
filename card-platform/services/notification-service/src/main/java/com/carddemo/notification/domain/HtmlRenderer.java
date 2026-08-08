package com.carddemo.notification.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Renders one cardholder alert as markup, reproducing the {@code HTMLFILE} records of
 * {@code app/cbl/CBSTM03A.CBL}.
 *
 * <p>The source writes 75 markup records, and a statement carrying one transaction row emits the
 * same 75. Four source regions supply them: the header at
 * {@code app/cbl/CBSTM03A.CBL:L506-L552}, the name and address block at L558 through L669, the
 * transaction rows at L681 through L721 and the trailer at L439 through L454.
 *
 * <p>{@link NotificationRenderer} defines the COBOL terms these methods use.</p>
 *
 * <p>Every record holds {@value #HTML_RECORD_WIDTH} characters, matching
 * {@code FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47} and
 * {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at {@code app/jcl/CREASTMT.JCL:L94}.</p>
 *
 * <p>The source assembles its value-bearing records with two different delimiters, so this class
 * carries two assembly methods. {@link #assembleDoubleSpaceDelimitedLine(String, String)} serves
 * the name and the three address lines. {@link #assembleAsteriskDelimitedLine(String, String)}
 * serves the account identifier, the balance, the credit score, and the three transaction fields.
 * The asterisk form applied to a padded name emits that padding inside the paragraph, and the
 * double-space form applied to an edited amount contributes no characters at all.</p>
 *
 * <p>The class holds no mutable state, so one instance serves every consumer thread.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public final class HtmlRenderer implements NotificationRenderer {

    /**
     * Width of every markup record, from {@code FD-HTMLFILE-REC PIC X(100)} at
     * {@code app/cbl/CBSTM03A.CBL:L47}.
     *
     * <p>{@code HTML-FIXED-LN PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L149} is the shared
     * output area the 34 condition names below set, and it holds the same width.</p>
     */
    private static final int HTML_RECORD_WIDTH = 100;

    /**
     * Width of {@code L11-ACCT}, {@code PIC X(20)} at {@code app/cbl/CBSTM03A.CBL:L215}. The
     * {@code MOVE ACCT-ID TO L11-ACCT} at {@code app/cbl/CBSTM03A.CBL:L529} fills it.
     */
    private static final int L11_ACCT_WIDTH = 20;

    /**
     * Width of {@code L23-NAME}, {@code PIC X(50)} at {@code app/cbl/CBSTM03A.CBL:L220}.
     *
     * <p>{@code ST-NAME} is {@code PIC X(75)} at {@code app/cbl/CBSTM03A.CBL:L91}, so the
     * {@code MOVE ST-NAME TO L23-NAME} at {@code app/cbl/CBSTM03A.CBL:L560} drops the last 25
     * characters before any delimiter runs. The three address lines take no such step: the
     * {@code STRING} statements at {@code app/cbl/CBSTM03A.CBL:L571},
     * {@code app/cbl/CBSTM03A.CBL:L579} and {@code app/cbl/CBSTM03A.CBL:L587} read
     * {@code ST-ADD1}, {@code ST-ADD2} and {@code ST-ADD3} at their own widths.</p>
     */
    private static final int L23_NAME_WIDTH = 50;

    /**
     * Field width the risk score renders at. No COBOL ancestor.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL} carries no fraud concept. The score renders at the width of
     * the nearest score field the source declares, {@code ST-FICO-SCORE PIC X(20)} at
     * {@code app/cbl/CBSTM03A.CBL:L118}.</p>
     */
    private static final int FRAUD_RISK_SCORE_WIDTH = 20;

    /**
     * Field width the triggered rule list renders at. No COBOL ancestor.
     *
     * <p>The list renders at the width of {@code ST-TRANDT PIC X(49)} at
     * {@code app/cbl/CBSTM03A.CBL:L135}, the widest detail field the source declares. A long list
     * loses its tail, as a long description does.</p>
     */
    private static final int FRAUD_TRIGGERED_RULES_WIDTH = 49;

    /**
     * Delimiter of the second component of the name and address {@code STRING} statements,
     * {@code DELIMITED BY '  '} at {@code app/cbl/CBSTM03A.CBL:L563},
     * {@code app/cbl/CBSTM03A.CBL:L571}, {@code app/cbl/CBSTM03A.CBL:L579} and
     * {@code app/cbl/CBSTM03A.CBL:L587}.
     *
     * <p>A fixed-width field pads on the right with spaces, so the first pair of spaces marks
     * where the value ends.</p>
     */
    private static final String DOUBLE_SPACE_DELIMITER = "  ";

    /**
     * Delimiter of every component of the data-line {@code STRING} statements,
     * {@code DELIMITED BY '*'} at {@code app/cbl/CBSTM03A.CBL:L614-L616},
     * {@code app/cbl/CBSTM03A.CBL:L621-L623}, {@code app/cbl/CBSTM03A.CBL:L628-L630},
     * {@code app/cbl/CBSTM03A.CBL:L687-L689}, {@code app/cbl/CBSTM03A.CBL:L699-L701} and
     * {@code app/cbl/CBSTM03A.CBL:L711-L713}.
     */
    private static final String ASTERISK_DELIMITER = "*";

    /**
     * Third component of the name and address {@code STRING} statements,
     * {@code '  ' DELIMITED BY SIZE} at {@code app/cbl/CBSTM03A.CBL:L564},
     * {@code app/cbl/CBSTM03A.CBL:L572}, {@code app/cbl/CBSTM03A.CBL:L580} and
     * {@code app/cbl/CBSTM03A.CBL:L588}. Both spaces reach the record.
     */
    private static final String TWO_SPACES_IN_FULL = "  ";

    /**
     * Separator placed between triggered rule identifiers. No COBOL ancestor.
     */
    private static final String TRIGGERED_RULES_SEPARATOR = ", ";

    /** {@code HTML-L01}, the condition name at {@code app/cbl/CBSTM03A.CBL:L150}. */
    private static final String HTML_L01 = "<!DOCTYPE html>";

    /** {@code HTML-L02}, the condition name at {@code app/cbl/CBSTM03A.CBL:L151}. */
    private static final String HTML_L02 = "<html lang=\"en\">";

    /** {@code HTML-L03}, the condition name at {@code app/cbl/CBSTM03A.CBL:L152}. */
    private static final String HTML_L03 = "<head>";

    /** {@code HTML-L04}, the condition name at {@code app/cbl/CBSTM03A.CBL:L153}. */
    private static final String HTML_L04 = "<meta charset=\"utf-8\">";

    /** {@code HTML-L05}, the condition name at {@code app/cbl/CBSTM03A.CBL:L154}. */
    private static final String HTML_L05 = "<title>HTML Table Layout</title>";

    /** {@code HTML-L06}, the condition name at {@code app/cbl/CBSTM03A.CBL:L155}. */
    private static final String HTML_L06 = "</head>";

    /** {@code HTML-L07}, the condition name at {@code app/cbl/CBSTM03A.CBL:L156}. */
    private static final String HTML_L07 = "<body style=\"margin:0px;\">";

    /**
     * {@code HTML-L08}, the condition name at {@code app/cbl/CBSTM03A.CBL:L157-L158}.
     *
     * <p>The literal spans two source lines, which COBOL joins with no separator: the first line
     * ends after {@code styl} in column 72 and the second resumes at {@code e="width:70%;}. Two
     * spaces follow the element name in the source, and the font stack reads
     * {@code font:12px Segoe UI,sans-serif}.</p>
     */
    private static final String HTML_L08 =
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; "
                    + "font:12px Segoe UI,sans-serif;\">";

    /** {@code HTML-LTRS}, the condition name at {@code app/cbl/CBSTM03A.CBL:L159}. */
    private static final String HTML_LTRS = "<tr>";

    /** {@code HTML-LTRE}, the condition name at {@code app/cbl/CBSTM03A.CBL:L160}. */
    private static final String HTML_LTRE = "</tr>";

    /**
     * {@code HTML-LTDS}, the condition name at {@code app/cbl/CBSTM03A.CBL:L161}. No
     * {@code WRITE} statement in {@code app/cbl/CBSTM03A.CBL} names it, so no record carries it.
     * Every open cell comes from one of the styled variants below.
     */
    private static final String HTML_LTDS = "<td>";

    /** {@code HTML-LTDE}, the condition name at {@code app/cbl/CBSTM03A.CBL:L162}. */
    private static final String HTML_LTDE = "</td>";

    /**
     * {@code HTML-L10}, the condition name at {@code app/cbl/CBSTM03A.CBL:L163-L164}. The
     * literal spans two source lines and joins with no separator, so no space separates
     * {@code 5px;} from {@code background-color}. The colour reads {@code #1d1d96b3} in the
     * source, in lower case and with eight hexadecimal digits.
     */
    private static final String HTML_L10 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";

    /**
     * {@code HTML-L15}, the condition name at {@code app/cbl/CBSTM03A.CBL:L165-L166}. The
     * literal spans two source lines and joins with no separator. The colour reads
     * {@code #FFAF33} in the source, in upper case.
     */
    private static final String HTML_L15 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";

    /** {@code HTML-L16}, the condition name at {@code app/cbl/CBSTM03A.CBL:L167-L168}. */
    private static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";

    /** {@code HTML-L17}, the condition name at {@code app/cbl/CBSTM03A.CBL:L169-L170}. */
    private static final String HTML_L17 = "<p>410 Terry Ave N</p>";

    /** {@code HTML-L18}, the condition name at {@code app/cbl/CBSTM03A.CBL:L171-L172}. */
    private static final String HTML_L18 = "<p>Seattle WA 99999</p>";

    /**
     * {@code HTML-L22-35}, the condition name at {@code app/cbl/CBSTM03A.CBL:L173-L175}. The
     * literal spans two source lines and joins with no separator. The colour reads
     * {@code #f2f2f2} in the source, in lower case.
     */
    private static final String HTML_L22_35 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";

    /**
     * {@code HTML-L30-42}, the condition name at {@code app/cbl/CBSTM03A.CBL:L176-L178}. The
     * literal spans two source lines and joins with no separator. The colour reads
     * {@code #33FFD1} in the source, and the cell centres its content.
     */
    private static final String HTML_L30_42 =
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; "
                    + "text-align:center;\">";

    /** {@code HTML-L31}, the condition name at {@code app/cbl/CBSTM03A.CBL:L179-L180}. */
    private static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";

    /** {@code HTML-L43}, the condition name at {@code app/cbl/CBSTM03A.CBL:L181-L182}. */
    private static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";

    /**
     * {@code HTML-L47}, the condition name at {@code app/cbl/CBSTM03A.CBL:L183-L185}. The
     * literal spans two source lines, breaking after {@code background-} and resuming at
     * {@code color:#33FF5E}. The cell takes 25 percent of the table width and aligns left.
     */
    private static final String HTML_L47 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; "
                    + "text-align:left;\">";

    /** {@code HTML-L48}, the condition name at {@code app/cbl/CBSTM03A.CBL:L186-L187}. */
    private static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";

    /**
     * {@code HTML-L50}, the condition name at {@code app/cbl/CBSTM03A.CBL:L188-L190}. The
     * literal spans two source lines. The cell takes 55 percent of the table width and aligns
     * left.
     */
    private static final String HTML_L50 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; "
                    + "text-align:left;\">";

    /** {@code HTML-L51}, the condition name at {@code app/cbl/CBSTM03A.CBL:L191-L192}. */
    private static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";

    /**
     * {@code HTML-L53}, the condition name at {@code app/cbl/CBSTM03A.CBL:L193-L195}. The
     * literal spans two source lines. The cell takes 20 percent of the table width and aligns
     * right.
     */
    private static final String HTML_L53 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; "
                    + "text-align:right;\">";

    /** {@code HTML-L54}, the condition name at {@code app/cbl/CBSTM03A.CBL:L196-L197}. */
    private static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";

    /**
     * {@code HTML-L58}, the condition name at {@code app/cbl/CBSTM03A.CBL:L198-L200}. The
     * literal spans two source lines. The cell carries the same 25 percent width and left
     * alignment as {@link #HTML_L47}, over the lighter {@code #f2f2f2}.
     */
    private static final String HTML_L58 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; "
                    + "text-align:left;\">";

    /**
     * {@code HTML-L61}, the condition name at {@code app/cbl/CBSTM03A.CBL:L201-L203}. The
     * literal spans two source lines. The cell carries the same 55 percent width and left
     * alignment as {@link #HTML_L50}.
     */
    private static final String HTML_L61 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; "
                    + "text-align:left;\">";

    /**
     * {@code HTML-L64}, the condition name at {@code app/cbl/CBSTM03A.CBL:L204-L206}. The
     * literal spans two source lines. The cell carries the same 20 percent width and right
     * alignment as {@link #HTML_L53}.
     */
    private static final String HTML_L64 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; "
                    + "text-align:right;\">";

    /** {@code HTML-L75}, the condition name at {@code app/cbl/CBSTM03A.CBL:L207-L208}. */
    private static final String HTML_L75 = "<h3>End of Statement</h3>";

    /** {@code HTML-L78}, the condition name at {@code app/cbl/CBSTM03A.CBL:L209}. */
    private static final String HTML_L78 = "</table>";

    /** {@code HTML-L79}, the condition name at {@code app/cbl/CBSTM03A.CBL:L210}. */
    private static final String HTML_L79 = "</body>";

    /** {@code HTML-L80}, the condition name at {@code app/cbl/CBSTM03A.CBL:L211}. */
    private static final String HTML_L80 = "</html>";

    /**
     * First member of {@code HTML-L11}, {@code FILLER PIC X(34)} at
     * {@code app/cbl/CBSTM03A.CBL:L213-L214}.
     */
    private static final String L11_HEADING_PREFIX = "<h3>Statement for Account Number: ";

    /**
     * Third member of {@code HTML-L11}, {@code FILLER PIC X(05)} at
     * {@code app/cbl/CBSTM03A.CBL:L216}.
     *
     * <p>{@code HTML-L11} spans 59 of its 100 characters and carries this closing member, so the
     * {@code WRITE FD-HTMLFILE-REC FROM HTML-L11} at {@code app/cbl/CBSTM03A.CBL:L530} names it
     * as a whole group.</p>
     */
    private static final String L11_HEADING_SUFFIX = "</h3>";

    /**
     * Opening tag of the first name line, the literal at {@code app/cbl/CBSTM03A.CBL:L562}.
     *
     * <p>The same 26 characters form the only other member of {@code HTML-L23} at
     * {@code app/cbl/CBSTM03A.CBL:L218-L219}. {@code HTML-L23} spans 76 of its 100 characters
     * and declares no closing member, so the {@code STRING} at
     * {@code app/cbl/CBSTM03A.CBL:L562-L567} supplies {@link #CLOSE_PARAGRAPH} as its own fourth
     * component and writes the record at {@code app/cbl/CBSTM03A.CBL:L568}.</p>
     */
    private static final String L23_OPEN_PARAGRAPH = "<p style=\"font-size:16px\">";

    /**
     * Opening tag of every other assembled line, the literal at
     * {@code app/cbl/CBSTM03A.CBL:L570}, {@code app/cbl/CBSTM03A.CBL:L578},
     * {@code app/cbl/CBSTM03A.CBL:L586}, {@code app/cbl/CBSTM03A.CBL:L687},
     * {@code app/cbl/CBSTM03A.CBL:L699} and {@code app/cbl/CBSTM03A.CBL:L711}.
     */
    private static final String OPEN_PARAGRAPH = "<p>";

    /**
     * Closing tag every assembled line supplies as its own last component, the literal at
     * {@code app/cbl/CBSTM03A.CBL:L565}, {@code app/cbl/CBSTM03A.CBL:L573},
     * {@code app/cbl/CBSTM03A.CBL:L581}, {@code app/cbl/CBSTM03A.CBL:L589},
     * {@code app/cbl/CBSTM03A.CBL:L616}, {@code app/cbl/CBSTM03A.CBL:L623},
     * {@code app/cbl/CBSTM03A.CBL:L630}, {@code app/cbl/CBSTM03A.CBL:L689},
     * {@code app/cbl/CBSTM03A.CBL:L701} and {@code app/cbl/CBSTM03A.CBL:L713}.
     */
    private static final String CLOSE_PARAGRAPH = "</p>";

    /**
     * First component of the account-identifier data line, the 24-character literal at
     * {@code app/cbl/CBSTM03A.CBL:L614}. The label repeats
     * {@code 'Account ID         :' PIC X(20)} from {@code app/cbl/CBSTM03A.CBL:L108} and adds
     * one trailing space.
     */
    private static final String ACCOUNT_ID_LABEL = "<p>Account ID         : ";

    /**
     * First component of the balance data line, the 24-character literal at
     * {@code app/cbl/CBSTM03A.CBL:L621}. The label repeats
     * {@code 'Current Balance    :' PIC X(20)} from {@code app/cbl/CBSTM03A.CBL:L112} and adds
     * one trailing space.
     */
    private static final String CURRENT_BALANCE_LABEL = "<p>Current Balance    : ";

    /**
     * First component of the credit-score data line, the 24-character literal at
     * {@code app/cbl/CBSTM03A.CBL:L628}. The label repeats
     * {@code 'FICO Score         :' PIC X(20)} from {@code app/cbl/CBSTM03A.CBL:L117} and adds
     * one trailing space.
     */
    private static final String FICO_SCORE_LABEL = "<p>FICO Score         : ";

    /**
     * Centred heading of a fraud alert. No COBOL ancestor.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL} carries no fraud concept. The heading takes the form of
     * {@code HTML-L31} at {@code app/cbl/CBSTM03A.CBL:L179-L180} and {@code HTML-L43} at
     * {@code app/cbl/CBSTM03A.CBL:L181-L182}, the two headings the source centres.</p>
     */
    private static final String FRAUD_ALERT_HEADING =
            "<p style=\"font-size:16px\">Fraud Alert</p>";

    /**
     * Label of the transaction-identifier line of a fraud alert. No COBOL ancestor. The label
     * holds 24 characters, in the form of {@link #ACCOUNT_ID_LABEL}.
     */
    private static final String TRANSACTION_ID_LABEL = "<p>Transaction ID     : ";

    /**
     * Label of the risk-score line of a fraud alert. No COBOL ancestor. The label
     * holds 24 characters, in the form of {@link #ACCOUNT_ID_LABEL}.
     */
    private static final String RISK_SCORE_LABEL = "<p>Risk Score         : ";

    /**
     * Label of the triggered-rule line of a fraud alert. No COBOL ancestor. The
     * label holds 24 characters, in the form of {@link #ACCOUNT_ID_LABEL}.
     */
    private static final String TRIGGERED_RULES_LABEL = "<p>Triggered Rules    : ";

    /**
     * Reports that this renderer produces markup.
     *
     * @return {@link RenderedFormat#HTML}, matching {@code FD-HTMLFILE-REC} at
     *         {@code app/cbl/CBSTM03A.CBL:L46-L47}
     */
    @Override
    public RenderedFormat format() {
        return RenderedFormat.HTML;
    }

    /**
     * Renders a statement alert as markup.
     *
     * <p>The result carries 64 records plus 11 for each row, so a statement covering one row
     * carries the 75 markup records {@code app/cbl/CBSTM03A.CBL} writes. Records join with
     * {@link #LINE_SEPARATOR}, and each holds {@value #HTML_RECORD_WIDTH} characters.</p>
     *
     * <p>The blocks run in the order the source writes them.</p>
     *
     * <ul>
     *   <li>the header at {@code app/cbl/CBSTM03A.CBL:L506-L552}</li>
     *   <li>the name and address lines at {@code app/cbl/CBSTM03A.CBL:L558-L592}</li>
     *   <li>the {@code Basic Details} heading at {@code app/cbl/CBSTM03A.CBL:L594-L611}</li>
     *   <li>the three detail lines at {@code app/cbl/CBSTM03A.CBL:L613-L637}</li>
     *   <li>the {@code Transaction Summary} heading at
     *       {@code app/cbl/CBSTM03A.CBL:L638-L647}</li>
     *   <li>the column headers at {@code app/cbl/CBSTM03A.CBL:L648-L669}</li>
     *   <li>one row for each transaction at {@code app/cbl/CBSTM03A.CBL:L681-L721}</li>
     *   <li>the trailer at {@code app/cbl/CBSTM03A.CBL:L439-L454}</li>
     * </ul>
     *
     * <p>A blank field raises no exception. The name and the address lines then contribute no
     * characters and render an empty paragraph, which is what the double-space delimiter at
     * {@code app/cbl/CBSTM03A.CBL:L563} produces for a field of spaces.</p>
     *
     * @param context the cardholder fields, already assembled and already edited; must not be
     *                {@code null}
     * @param rows    the detail rows in the order they render; must not be {@code null}, and no
     *                element may be {@code null}
     * @param total   the transaction total. The markup trailer at
     *                {@code app/cbl/CBSTM03A.CBL:L439-L454} writes 8 records and names no total
     *                field, so no record carries this value. The text trailer does carry one, on
     *                {@code ST-LINE14A} at {@code app/cbl/CBSTM03A.CBL:L436}.
     * @return the rendered alert
     * @throws NullPointerException     if {@code context} or {@code rows} is {@code null}, or if
     *                                  {@code rows} holds a {@code null} element
     * @throws IllegalArgumentException if {@code rows} holds more than
     *                                  {@link NotificationRenderer#MAXIMUM_STATEMENT_ROWS}
     *                                  elements
     */
    @Override
    public String renderStatementAlert(CardholderContext context, List<TransactionRow> rows,
                                       BigDecimal total) {
        Objects.requireNonNull(context, "context must not be null");
        NotificationRenderer.requireRenderableRowCount(rows);

        List<String> records = new ArrayList<>();

        writeMarkupHeader(records, context.accountId());
        writeNameAndAddressLines(records, context);
        writeHeadingRowAndOpenDataCell(records, HTML_L31);
        writeBasicDetails(records, context);
        writeHeadingRow(records, HTML_L43);
        writeColumnHeaderRow(records);

        for (TransactionRow row : rows) {
            Objects.requireNonNull(row, "rows must hold no null element");
            writeTransactionRow(records, row);
        }

        writeMarkupTrailer(records);

        return String.join(LINE_SEPARATOR, records);
    }

    /**
     * Renders a fraud alert as markup. No COBOL ancestor.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL} carries no fraud concept, so no source paragraph writes
     * these records. Every literal and both assembly methods come from the statement path. The
     * four blocks reproduce the header at {@code app/cbl/CBSTM03A.CBL:L506-L552}, the name and
     * address lines at {@code app/cbl/CBSTM03A.CBL:L558-L592}, the heading shape at
     * {@code app/cbl/CBSTM03A.CBL:L594-L611} and the trailer at
     * {@code app/cbl/CBSTM03A.CBL:L439-L454}.</p>
     *
     * <p>The result carries 48 records, joined with {@link #LINE_SEPARATOR}. The parameters match
     * the payload of the {@code FraudFlagged} event, which carries no amount and no card
     * number.</p>
     *
     * @param context        the cardholder fields, already assembled and already edited; must not
     *                       be {@code null}
     * @param transactionId  the identifier of the flagged transaction, rendered at
     *                       {@value #ST_TRANID_WIDTH} characters; must not be {@code null}
     * @param riskScore      the score the fraud service assigned, rendered at
     *                       {@value #FRAUD_RISK_SCORE_WIDTH} characters
     * @param triggeredRules the identifiers of the rules that fired, joined and rendered at
     *                       {@value #FRAUD_TRIGGERED_RULES_WIDTH} characters; must not be
     *                       {@code null}, and no element may be {@code null}
     * @return the rendered alert
     * @throws NullPointerException if any reference parameter is {@code null}, or if
     *                              {@code triggeredRules} holds a {@code null} element
     */
    @Override
    public String renderFraudAlert(CardholderContext context, String transactionId, int riskScore,
                                   List<String> triggeredRules) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(triggeredRules, "triggeredRules must not be null");

        for (String rule : triggeredRules) {
            Objects.requireNonNull(rule, "triggeredRules must hold no null element");
        }

        List<String> records = new ArrayList<>();

        writeMarkupHeader(records, context.accountId());
        writeNameAndAddressLines(records, context);
        writeHeadingRowAndOpenDataCell(records, FRAUD_ALERT_HEADING);
        writeFraudDetails(records, transactionId, riskScore, triggeredRules);
        writeMarkupTrailer(records);

        return String.join(LINE_SEPARATOR, records);
    }

    /**
     * Appends the 22 records of {@code 5100-WRITE-HTML-HEADER} at
     * {@code app/cbl/CBSTM03A.CBL:L506-L552}.
     *
     * <p>The block opens the document and the table, writes the account-number heading, then
     * writes the bank identity block and opens the cell the name and address lines fill. One
     * record comes from a group item rather than the shared output area: the
     * {@code WRITE FD-HTMLFILE-REC FROM HTML-L11} at {@code app/cbl/CBSTM03A.CBL:L530}.</p>
     *
     * @param records   the record list this block appends to
     * @param accountId the value the {@code MOVE ACCT-ID TO L11-ACCT} at
     *                  {@code app/cbl/CBSTM03A.CBL:L529} supplies
     */
    private static void writeMarkupHeader(List<String> records, String accountId) {
        emit(records, HTML_L01);
        emit(records, HTML_L02);
        emit(records, HTML_L03);
        emit(records, HTML_L04);
        emit(records, HTML_L05);
        emit(records, HTML_L06);
        emit(records, HTML_L07);
        emit(records, HTML_L08);
        emit(records, HTML_LTRS);
        emit(records, HTML_L10);
        emit(records, assembleAccountNumberHeading(accountId));
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
        emit(records, HTML_LTRS);
        emit(records, HTML_L15);
        emit(records, HTML_L16);
        emit(records, HTML_L17);
        emit(records, HTML_L18);
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
        emit(records, HTML_LTRS);
        emit(records, HTML_L22_35);
    }

    /**
     * Appends the 4 records of {@code app/cbl/CBSTM03A.CBL:L558-L592}.
     *
     * <p>All four use the double-space delimiter. The first takes the long opening tag and the
     * name, staged through {@code L23-NAME} at {@code app/cbl/CBSTM03A.CBL:L560}, which narrows
     * the value to {@value #L23_NAME_WIDTH} characters. The next three take the short opening tag
     * and the three address lines at their own widths.</p>
     *
     * @param records the record list this block appends to
     * @param context the cardholder fields these four records read
     */
    private static void writeNameAndAddressLines(List<String> records,
                                                 CardholderContext context) {
        String stagedName = NotificationRenderer.pic(context.assembledName(), L23_NAME_WIDTH);

        emit(records, assembleDoubleSpaceDelimitedLine(L23_OPEN_PARAGRAPH, stagedName));
        emit(records, assembleDoubleSpaceDelimitedLine(OPEN_PARAGRAPH, context.addressLine1()));
        emit(records, assembleDoubleSpaceDelimitedLine(OPEN_PARAGRAPH, context.addressLine2()));
        emit(records, assembleDoubleSpaceDelimitedLine(OPEN_PARAGRAPH, context.addressLine3()));
    }

    /**
     * Appends the 9 records of {@code app/cbl/CBSTM03A.CBL:L594-L611}.
     *
     * <p>The block closes the open cell and row, then writes a centred heading in a row of its
     * own. It ends by opening the next row and the light cell the detail lines fill. The statement
     * path supplies {@link #HTML_L31} and a fraud alert supplies
     * {@link #FRAUD_ALERT_HEADING}.</p>
     *
     * @param records          the record list this block appends to
     * @param headingParagraph the paragraph the centred cell carries
     */
    private static void writeHeadingRowAndOpenDataCell(List<String> records,
                                                       String headingParagraph) {
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
        emit(records, HTML_LTRS);
        emit(records, HTML_L30_42);
        emit(records, headingParagraph);
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
        emit(records, HTML_LTRS);
        emit(records, HTML_L22_35);
    }

    /**
     * Appends the 5 records of {@code app/cbl/CBSTM03A.CBL:L613-L637}.
     *
     * <p>Three lines use the asterisk delimiter, so each value reaches the record with its
     * fixed-width padding intact. The block then closes the cell and the row.</p>
     *
     * @param records the record list this block appends to
     * @param context the cardholder fields these three lines read, at
     *                {@code app/cbl/CBSTM03A.CBL:L615}, {@code app/cbl/CBSTM03A.CBL:L622} and
     *                {@code app/cbl/CBSTM03A.CBL:L629}
     */
    private static void writeBasicDetails(List<String> records, CardholderContext context) {
        emit(records, assembleAsteriskDelimitedLine(ACCOUNT_ID_LABEL, context.accountId()));
        emit(records, assembleAsteriskDelimitedLine(CURRENT_BALANCE_LABEL,
                context.editedCurrentBalance()));
        emit(records, assembleAsteriskDelimitedLine(FICO_SCORE_LABEL, context.ficoScore()));
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
    }

    /**
     * Appends the 3 detail records and the 2 closing records of a fraud alert. No COBOL ancestor.
     *
     * <p>The block takes the shape of {@code app/cbl/CBSTM03A.CBL:L613-L637} and uses the same
     * asterisk delimiter, so each value reaches the record with its padding intact.</p>
     *
     * @param records        the record list this block appends to
     * @param transactionId  the identifier of the flagged transaction
     * @param riskScore      the score the fraud service assigned
     * @param triggeredRules the identifiers of the rules that fired
     */
    private static void writeFraudDetails(List<String> records, String transactionId,
                                          int riskScore, List<String> triggeredRules) {
        emit(records, assembleAsteriskDelimitedLine(TRANSACTION_ID_LABEL,
                NotificationRenderer.pic(transactionId, ST_TRANID_WIDTH)));
        emit(records, assembleAsteriskDelimitedLine(RISK_SCORE_LABEL,
                NotificationRenderer.pic(Integer.toString(riskScore), FRAUD_RISK_SCORE_WIDTH)));
        emit(records, assembleAsteriskDelimitedLine(TRIGGERED_RULES_LABEL,
                NotificationRenderer.pic(String.join(TRIGGERED_RULES_SEPARATOR, triggeredRules),
                        FRAUD_TRIGGERED_RULES_WIDTH)));
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
    }

    /**
     * Appends the 5 records of {@code app/cbl/CBSTM03A.CBL:L638-L647}.
     *
     * <p>The block writes a centred heading in a row of its own and closes that row. It opens no
     * following cell, which is how it differs from
     * {@link #writeHeadingRowAndOpenDataCell(List, String)}.</p>
     *
     * @param records          the record list this block appends to
     * @param headingParagraph the paragraph the centred cell carries
     */
    private static void writeHeadingRow(List<String> records, String headingParagraph) {
        emit(records, HTML_LTRS);
        emit(records, HTML_L30_42);
        emit(records, headingParagraph);
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
    }

    /**
     * Appends the 11 records of {@code app/cbl/CBSTM03A.CBL:L648-L669}.
     *
     * <p>The row carries three heading cells over {@code #33FF5E}, at 25 percent aligned left, 55
     * percent aligned left and 20 percent aligned right. The three data cells of
     * {@link #writeTransactionRow(List, TransactionRow)} repeat those widths and alignments over
     * the lighter {@code #f2f2f2}.</p>
     *
     * @param records the record list this block appends to
     */
    private static void writeColumnHeaderRow(List<String> records) {
        emit(records, HTML_LTRS);
        emit(records, HTML_L47);
        emit(records, HTML_L48);
        emit(records, HTML_LTDE);
        emit(records, HTML_L50);
        emit(records, HTML_L51);
        emit(records, HTML_LTDE);
        emit(records, HTML_L53);
        emit(records, HTML_L54);
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
    }

    /**
     * Appends the 11 records of {@code 6000-WRITE-TRANS} at
     * {@code app/cbl/CBSTM03A.CBL:L681-L721}.
     *
     * <p>All three value lines use the asterisk delimiter, at
     * {@code app/cbl/CBSTM03A.CBL:L688}, {@code app/cbl/CBSTM03A.CBL:L700} and
     * {@code app/cbl/CBSTM03A.CBL:L712}. The amount therefore keeps the leading spaces a
     * {@code Z} digit position renders, and the description arrives already narrowed to
     * {@value #ST_TRANDT_WIDTH} characters by the {@code MOVE} at
     * {@code app/cbl/CBSTM03A.CBL:L677}.</p>
     *
     * @param records the record list this block appends to
     * @param row     the three fields this row renders
     */
    private static void writeTransactionRow(List<String> records, TransactionRow row) {
        emit(records, HTML_LTRS);
        emit(records, HTML_L58);
        emit(records, assembleAsteriskDelimitedLine(OPEN_PARAGRAPH, row.transactionId()));
        emit(records, HTML_LTDE);
        emit(records, HTML_L61);
        emit(records, assembleAsteriskDelimitedLine(OPEN_PARAGRAPH, row.description()));
        emit(records, HTML_LTDE);
        emit(records, HTML_L64);
        emit(records, assembleAsteriskDelimitedLine(OPEN_PARAGRAPH, row.editedAmount()));
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
    }

    /**
     * Appends the 8 records of the markup trailer at {@code app/cbl/CBSTM03A.CBL:L439-L454}.
     *
     * <p>The block writes a full-width row carrying the end-of-statement heading, then closes the
     * table, the body and the document. The source block names 8 condition names and no total
     * field, so no record here carries a total. The text trailer differs: the
     * {@code WRITE FD-STMTFILE-REC FROM ST-LINE14A} at {@code app/cbl/CBSTM03A.CBL:L436} carries
     * one.</p>
     *
     * @param records the record list this block appends to
     */
    private static void writeMarkupTrailer(List<String> records) {
        emit(records, HTML_LTRS);
        emit(records, HTML_L10);
        emit(records, HTML_L75);
        emit(records, HTML_LTDE);
        emit(records, HTML_LTRE);
        emit(records, HTML_L78);
        emit(records, HTML_L79);
        emit(records, HTML_L80);
    }

    /**
     * Assembles the account-number heading, reproducing {@code HTML-L11} at
     * {@code app/cbl/CBSTM03A.CBL:L212-L216}.
     *
     * <p>The group item holds three members: a 34-character prefix, the 20-character account
     * field, and a 5-character closing member. Those 59 characters are complete, so the source
     * writes the group as a whole at {@code app/cbl/CBSTM03A.CBL:L530}.</p>
     *
     * @param accountId the account identifier, held at {@value #L11_ACCT_WIDTH} characters
     * @return the assembled heading, before the record width is applied
     */
    private static String assembleAccountNumberHeading(String accountId) {
        String field = NotificationRenderer.pic(accountId, L11_ACCT_WIDTH);

        return L11_HEADING_PREFIX + NotificationRenderer.escapeHtmlText(field)
                + L11_HEADING_SUFFIX;
    }

    /**
     * Assembles one name or address line, reproducing the four-component {@code STRING}
     * statements at {@code app/cbl/CBSTM03A.CBL:L562-L567},
     * {@code app/cbl/CBSTM03A.CBL:L570-L575}, {@code app/cbl/CBSTM03A.CBL:L578-L583} and
     * {@code app/cbl/CBSTM03A.CBL:L586-L591}.
     *
     * <p>The four components are the opening tag, the value {@code DELIMITED BY '  '}, two
     * literal spaces {@code DELIMITED BY SIZE}, and the closing tag. The value therefore stops at
     * its first pair of spaces, which strips the padding a fixed-width field carries. A field of
     * spaces contributes nothing and the paragraph holds the two literal spaces alone.</p>
     *
     * <p>{@link #assembleAsteriskDelimitedLine(String, String)} serves the data lines. Applying
     * that method here would emit 50 or 80 characters of padding inside the paragraph.</p>
     *
     * @param openTag the first component, {@link #L23_OPEN_PARAGRAPH} for the name line and
     *                {@link #OPEN_PARAGRAPH} for the three address lines
     * @param value   the second component, a fixed-width name or address field
     * @return the assembled line, before the record width is applied
     */
    private static String assembleDoubleSpaceDelimitedLine(String openTag, String value) {
        String contribution = contributionDelimitedBy(value, DOUBLE_SPACE_DELIMITER);

        return openTag + NotificationRenderer.escapeHtmlText(contribution) + TWO_SPACES_IN_FULL
                + CLOSE_PARAGRAPH;
    }

    /**
     * Assembles one data line, reproducing the three-component {@code STRING} statements at
     * {@code app/cbl/CBSTM03A.CBL:L614-L618}, {@code app/cbl/CBSTM03A.CBL:L621-L625},
     * {@code app/cbl/CBSTM03A.CBL:L628-L632}, {@code app/cbl/CBSTM03A.CBL:L687-L691},
     * {@code app/cbl/CBSTM03A.CBL:L699-L703} and {@code app/cbl/CBSTM03A.CBL:L711-L715}.
     *
     * <p>All three components are {@code DELIMITED BY '*'}: the label, the value, and the closing
     * tag. None of the six source values carries an asterisk, so each value reaches the record in
     * full, with its fixed-width padding intact. An edited amount keeps the leading spaces a
     * {@code Z} digit position renders.</p>
     *
     * <p>{@link #assembleDoubleSpaceDelimitedLine(String, String)} serves the name and the
     * address lines. Applying that method to an edited amount would stop at position 0 and render
     * an empty cell.</p>
     *
     * @param label the first component, a 24-character label for a detail line and
     *              {@link #OPEN_PARAGRAPH} for a transaction line
     * @param value the second component, a fixed-width identifier, amount or description
     * @return the assembled line, before the record width is applied
     */
    private static String assembleAsteriskDelimitedLine(String label, String value) {
        String contribution = contributionDelimitedBy(value, ASTERISK_DELIMITER);

        return label + NotificationRenderer.escapeHtmlText(contribution) + CLOSE_PARAGRAPH;
    }

    /**
     * Returns the characters a {@code STRING} component contributes under a
     * {@code DELIMITED BY} phrase.
     *
     * <p>A component stops at the first occurrence of its delimiter and contributes every
     * character before it. A component holding no delimiter contributes all of its characters,
     * and a component starting with its delimiter contributes none.</p>
     *
     * <p>The twelve {@code STRING} statements of {@code app/cbl/CBSTM03A.CBL} name three
     * delimiters. This class serves the ten at {@code app/cbl/CBSTM03A.CBL:L562-L591} and
     * {@code app/cbl/CBSTM03A.CBL:L614-L632}, plus
     * {@code app/cbl/CBSTM03A.CBL:L687-L715}. {@link NotificationRenderer} serves the two at
     * {@code app/cbl/CBSTM03A.CBL:L462-L481}.</p>
     *
     * @param value     one sending field of a COBOL {@code STRING} statement, or {@code null}
     * @param delimiter the character sequence named in the {@code DELIMITED BY} phrase
     * @return the contributed characters, never {@code null}
     */
    private static String contributionDelimitedBy(String value, String delimiter) {
        if (value == null) {
            return "";
        }
        int end = value.indexOf(delimiter);

        return end < 0 ? value : value.substring(0, end);
    }

    /**
     * Appends one record, normalised to {@value #HTML_RECORD_WIDTH} characters.
     *
     * <p>Every record this class emits passes through here, reproducing the fixed-length write to
     * {@code FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47}. A shorter value
     * gains trailing spaces and a longer value loses its tail, matching the receiving field of a
     * COBOL {@code STRING} statement.</p>
     *
     * @param records the record list this method appends to
     * @param content the record content, before the record width is applied
     */
    private static void emit(List<String> records, String content) {
        records.add(NotificationRenderer.pic(content, HTML_RECORD_WIDTH));
    }
}
