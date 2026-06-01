package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Builds the HTML customer statement, reproducing the output of the COBOL batch
 * program {@code app/cbl/CBSTM03A.CBL} <strong>byte-for-byte</strong> (preservation
 * rule PR-09).
 *
 * <p>This is the Java port of the four HTML-emission paragraphs of {@code CBSTM03A}:</p>
 * <ul>
 *   <li><strong>{@code 5100-WRITE-HTML-HEADER}</strong> (CBSTM03A.CBL L506-L555) &rarr;
 *       {@link #renderHtmlHeader(Account)} &mdash; DOCTYPE, {@code <head>}, the bank-info
 *       table opening, and the "Statement for Account Number" heading.</li>
 *   <li><strong>{@code 5200-WRITE-HTML-NMADBS}</strong> (CBSTM03A.CBL L557-L672) &rarr;
 *       {@link #renderHtmlCustomerAndBasic(Customer, Account)} &mdash; the customer name and
 *       address block, plus the "Basic Details" (Account ID, Current Balance, FICO Score) and
 *       "Transaction Summary" header rows.</li>
 *   <li><strong>{@code 6000-WRITE-TRANS}</strong> (CBSTM03A.CBL L675-L723) &rarr;
 *       {@link #renderTransactionRow(Transaction)} &mdash; one three-cell table row per
 *       transaction (Tran ID, Tran Details, Amount).</li>
 *   <li><strong>{@code 4000-TRNXFILE-GET}</strong> footer sequence (CBSTM03A.CBL L416-L456,
 *       specifically L439-L454) &rarr; {@link #renderHtmlFooter()} &mdash; the "End of Statement"
 *       row and the closing {@code </table></body></html>} tags.</li>
 * </ul>
 *
 * <p>The 88-level {@code HTML-LINES} literal constants (CBSTM03A.CBL L148-L223) are reproduced
 * here as {@code private static final String} fields. Every literal &mdash; including the
 * <em>two</em> spaces between {@code <table} and {@code align} in {@link #HTML_L08}, the exact
 * CSS values, attribute order, hex colors, and the differing spacing in the {@code style}
 * attribute between the {@code colspan} cells ({@code padding:0px 5px;background-color}) and the
 * {@code width} cells ({@code padding:0px 5px; background-color}) &mdash; matches the COBOL
 * character-for-character. The {@code StatementGenerationParityTest} compares the rendered output
 * against the COBOL reference byte-for-byte, so any divergence fails the build.</p>
 *
 * <h2>Edited-field fidelity (COBOL is the single source of truth)</h2>
 *
 * <p>The dynamic values are formatted to match the exact COBOL {@code PIC} edit semantics, NOT a
 * naive {@code String.format}. This mirrors the convention established by
 * {@code TransactionPostingParityTest}: <em>the COBOL source is authoritative and overrides any
 * incorrect AAP commentary</em>. The relevant {@code PIC} clauses are:</p>
 * <ul>
 *   <li><strong>Account ID</strong> &mdash; {@code ACCT-ID PIC 9(11)} moved to {@code ST-ACCT-ID}
 *       / {@code L11-ACCT PIC X(20)}: the 11-digit value is rendered with leading zeros, then the
 *       20-character alphanumeric field is left-justified and space-padded
 *       (e.g. account {@code 1} &rarr; {@code "00000000001"} + 9 spaces). See
 *       {@link #formatAcctId20(Long)}.</li>
 *   <li><strong>Current Balance</strong> &mdash; {@code ACCT-CURR-BAL PIC S9(10)V99} moved to
 *       {@code ST-CURR-BAL PIC 9(9).99-} (13 chars): 9 integer digits with leading zeros, a
 *       decimal point, 2 fraction digits, and a trailing sign position (space when non-negative,
 *       {@code '-'} when negative). See {@link #formatStCurrBal(BigDecimal)}.</li>
 *   <li><strong>Transaction Amount</strong> &mdash; {@code TRNX-AMT PIC S9(09)V99} moved to
 *       {@code ST-TRANAMT PIC Z(9).99-} (13 chars): 9 integer positions with leading-zero
 *       <em>suppression</em> (blanks; an all-zero integer is 9 blanks), a decimal point, 2
 *       fraction digits, and a trailing sign position. See {@link #formatStTranAmt(BigDecimal)}.</li>
 *   <li><strong>FICO Score</strong> &mdash; {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} moved to
 *       {@code ST-FICO-SCORE PIC X(20)}: 3 digits with leading zeros, then left-justified and
 *       space-padded to 20. See {@link #formatFico20(Integer)}.</li>
 *   <li><strong>Tran ID / Tran Details</strong> &mdash; {@code TRNX-ID PIC X(16)} and
 *       {@code TRNX-DESC PIC X(100)} moved to {@code ST-TRANID PIC X(16)} / {@code ST-TRANDT
 *       PIC X(49)} and emitted whole (COBOL {@code DELIMITED BY '*'} on a string with no
 *       {@code '*'} returns the entire fixed-width field, trailing blanks included).</li>
 * </ul>
 *
 * <p>The customer name and address-line-3 are assembled exactly as the COBOL {@code STRING ...
 * DELIMITED BY ' '} statements in {@code 5000-CREATE-STATEMENT} (L462-L481) and then emitted with
 * {@code DELIMITED BY '  '} (two spaces) in {@code 5200-WRITE-HTML-NMADBS}. This double-space
 * delimiter intentionally preserves a COBOL quirk: an empty intermediate token (e.g. a blank
 * middle name) produces a double space that truncates everything after it. See
 * {@link #buildStName(Customer)}, {@link #buildStAdd3(Customer)}, {@link #upToFirstSpace(String)},
 * and {@link #upToFirstDoubleSpace(String)}.</p>
 *
 * <h2>Money (PR-16)</h2>
 *
 * <p>All monetary values are {@link BigDecimal} formatted with scale 2 and
 * {@link RoundingMode#HALF_UP} (mirroring the COBOL {@code ROUNDED} behavior).
 * {@code float}/{@code double} are never used.</p>
 *
 * <h2>Stored-XSS defense for dynamic text</h2>
 *
 * <p>Unlike the COBOL program &mdash; which emitted persisted field values into the HTML
 * file verbatim &mdash; this builder HTML-escapes every <em>dynamic free-text</em> value
 * before appending it (see {@link #htmlEscape(String)}): the customer name, the three
 * address lines, the transaction id, and the transaction description. Without escaping, a
 * persisted name, address, id, or description containing markup (for example
 * {@code <script>...}) would become executable HTML/JavaScript when the generated statement
 * is opened in a browser. The five metacharacters {@code & < > " '} are mapped to their
 * entities.</p>
 *
 * <p>Escaping is deliberately scoped: it is applied <strong>only</strong> to the dynamic
 * free-text fields, never to the fixed HTML literal constants (whose markup is intentional)
 * nor to the numeric edited fields ({@link #formatAcctId20(Long)},
 * {@link #formatStCurrBal(BigDecimal)}, {@link #formatFico20(Integer)},
 * {@link #formatStTranAmt(BigDecimal)}), which can only contain digits, spaces, {@code '.'}
 * and {@code '-'}. Because {@link #htmlEscape(String)} returns its input unchanged when no
 * metacharacter is present, the rendered output for the safe CardDemo fixture data is
 * byte-for-byte identical to the COBOL reference, so PR-09 parity (verified by
 * {@code StatementGenerationParityTest}) is preserved.</p>
 *
 * <h2>Line structure</h2>
 *
 * <p>The COBOL {@code FD-HTMLFILE-REC} is {@code PIC X(100)} (RECFM=FB LRECL=100), so on the
 * mainframe each record is blank-padded to 100 bytes. This builder emits only the meaningful
 * content of each record followed by a single {@code '\n'} ({@link #NEWLINE}); the fixed-block
 * padding is a file-format concern, not part of the logical HTML. The embedded fixed-width
 * <em>edited fields</em> (the 20-char account, 13-char money, 16-char Tran ID, 49-char details,
 * 20-char FICO) are content and are preserved verbatim, including their internal trailing blanks.</p>
 *
 * <p>This is a stateless Spring {@code @Component} (PR-29 constructor-injection pattern preserved
 * via {@link RequiredArgsConstructor}, although no collaborators are presently required). It is
 * autowired into {@code StatementGenerationTasklet} (same package) and {@code StatementService}
 * (the {@code com.carddemo.service} package).</p>
 *
 * @see Account
 * @see Customer
 * @see Transaction
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StatementHtmlBuilder {

    // -----------------------------------------------------------------------------------------
    // HTML 88-level literal constants — CBSTM03A.CBL L148-L223 (PR-09 BYTE-FOR-BYTE)
    // -----------------------------------------------------------------------------------------

    /** {@code HTML-L01} (CBSTM03A.CBL L150). */
    private static final String HTML_L01 = "<!DOCTYPE html>";
    /** {@code HTML-L02} (CBSTM03A.CBL L151). */
    private static final String HTML_L02 = "<html lang=\"en\">";
    /** {@code HTML-L03} (CBSTM03A.CBL L152). */
    private static final String HTML_L03 = "<head>";
    /** {@code HTML-L04} (CBSTM03A.CBL L153). */
    private static final String HTML_L04 = "<meta charset=\"utf-8\">";
    /** {@code HTML-L05} (CBSTM03A.CBL L154). */
    private static final String HTML_L05 = "<title>HTML Table Layout</title>";
    /** {@code HTML-L06} (CBSTM03A.CBL L155). */
    private static final String HTML_L06 = "</head>";
    /** {@code HTML-L07} (CBSTM03A.CBL L156). */
    private static final String HTML_L07 = "<body style=\"margin:0px;\">";
    /**
     * {@code HTML-L08} (CBSTM03A.CBL L157-L158). NOTE: there are EXACTLY TWO spaces between
     * {@code <table} and {@code align} — preserved verbatim from the COBOL literal.
     */
    private static final String HTML_L08 =
        "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
    /** {@code HTML-LTRS} (CBSTM03A.CBL L159) — table-row start. */
    private static final String HTML_LTRS = "<tr>";
    /** {@code HTML-LTRE} (CBSTM03A.CBL L160) — table-row end. */
    private static final String HTML_LTRE = "</tr>";
    /** {@code HTML-LTDS} (CBSTM03A.CBL L161) — table-cell start; preserved for fidelity. */
    private static final String HTML_LTDS = "<td>";
    /** {@code HTML-LTDE} (CBSTM03A.CBL L162) — table-cell end. */
    private static final String HTML_LTDE = "</td>";
    /** {@code HTML-L10} (CBSTM03A.CBL L163-L164) — dark header cell (no space after first ';'). */
    private static final String HTML_L10 =
        "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";
    /** {@code HTML-L11} prefix (CBSTM03A.CBL L213-L214) — 34 chars incl. trailing space. */
    private static final String HTML_L11_PREFIX = "<h3>Statement for Account Number: ";
    /** {@code HTML-L11} suffix (CBSTM03A.CBL L216) — 5 chars. */
    private static final String HTML_L11_SUFFIX = "</h3>";
    /** {@code HTML-L15} (CBSTM03A.CBL L165-L166) — orange header cell. */
    private static final String HTML_L15 =
        "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";
    /** {@code HTML-L16} (CBSTM03A.CBL L167-L168). */
    private static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";
    /** {@code HTML-L17} (CBSTM03A.CBL L169-L170). */
    private static final String HTML_L17 = "<p>410 Terry Ave N</p>";
    /** {@code HTML-L18} (CBSTM03A.CBL L171-L172). */
    private static final String HTML_L18 = "<p>Seattle WA 99999</p>";
    /** {@code HTML-L22-35} (CBSTM03A.CBL L173-L175) — light-gray cell. */
    private static final String HTML_L22_35 =
        "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
    /** {@code HTML-L23} prefix (CBSTM03A.CBL L217-L219) — 26 chars. */
    private static final String HTML_L23_PREFIX = "<p style=\"font-size:16px\">";
    /** {@code HTML-L30-42} (CBSTM03A.CBL L176-L178) — teal centered cell. */
    private static final String HTML_L30_42 =
        "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";
    /** {@code HTML-L31} (CBSTM03A.CBL L179-L180). */
    private static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";
    /** {@code HTML-L43} (CBSTM03A.CBL L181-L182). */
    private static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";
    /** {@code HTML-L47} (CBSTM03A.CBL L183-L185) — green left-aligned 25% cell. */
    private static final String HTML_L47 =
        "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    /** {@code HTML-L48} (CBSTM03A.CBL L186-L187). */
    private static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";
    /** {@code HTML-L50} (CBSTM03A.CBL L188-L190) — green left-aligned 55% cell. */
    private static final String HTML_L50 =
        "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    /** {@code HTML-L51} (CBSTM03A.CBL L191-L192). */
    private static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";
    /** {@code HTML-L53} (CBSTM03A.CBL L193-L195) — green right-aligned 20% cell. */
    private static final String HTML_L53 =
        "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
    /** {@code HTML-L54} (CBSTM03A.CBL L196-L197). */
    private static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";
    /** {@code HTML-L58} (CBSTM03A.CBL L198-L200) — gray left-aligned 25% cell. */
    private static final String HTML_L58 =
        "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    /** {@code HTML-L61} (CBSTM03A.CBL L201-L203) — gray left-aligned 55% cell. */
    private static final String HTML_L61 =
        "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    /** {@code HTML-L64} (CBSTM03A.CBL L204-L206) — gray right-aligned 20% cell. */
    private static final String HTML_L64 =
        "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";
    /** {@code HTML-L75} (CBSTM03A.CBL L207-L208). */
    private static final String HTML_L75 = "<h3>End of Statement</h3>";
    /** {@code HTML-L78} (CBSTM03A.CBL L209). */
    private static final String HTML_L78 = "</table>";
    /** {@code HTML-L79} (CBSTM03A.CBL L210). */
    private static final String HTML_L79 = "</body>";
    /** {@code HTML-L80} (CBSTM03A.CBL L211). */
    private static final String HTML_L80 = "</html>";

    // -----------------------------------------------------------------------------------------
    // Composite STRING literals — CBSTM03A.CBL 5200-WRITE-HTML-NMADBS (L614, L621, L628)
    // Each label is exactly 24 characters and is emitted whole (DELIMITED BY '*').
    // -----------------------------------------------------------------------------------------

    /** {@code '<p>Account ID         : '} (CBSTM03A.CBL L614) — 24 chars. */
    private static final String BASIC_ACCT_ID_LABEL = "<p>Account ID         : ";
    /** {@code '<p>Current Balance    : '} (CBSTM03A.CBL L621) — 24 chars. */
    private static final String BASIC_CURR_BAL_LABEL = "<p>Current Balance    : ";
    /** {@code '<p>FICO Score         : '} (CBSTM03A.CBL L628) — 24 chars. */
    private static final String BASIC_FICO_LABEL = "<p>FICO Score         : ";

    /** Plain paragraph open tag, used inline by the COBOL STRING statements for addresses/cells. */
    private static final String P_OPEN = "<p>";
    /** Paragraph close tag, used inline by the COBOL STRING statements. */
    private static final String P_CLOSE = "</p>";
    /** The two-space literal appended after name/address content in 5200 ({@code '  '}). */
    private static final String TWO_SPACES = "  ";

    /**
     * Logical line separator. The COBOL writes fixed-block 100-byte records; this builder emits
     * content + a single newline per record (the X(100) padding is a file-format concern).
     */
    private static final String NEWLINE = "\n";

    // -----------------------------------------------------------------------------------------
    // Fixed-width edit lengths (COBOL PIC widths) used by the formatting helpers.
    // -----------------------------------------------------------------------------------------

    /** {@code ST-ACCT-ID} / {@code L11-ACCT} / {@code ST-FICO-SCORE} are {@code PIC X(20)}. */
    private static final int FIELD_WIDTH_20 = 20;
    /** {@code ACCT-ID} is {@code PIC 9(11)}. */
    private static final int ACCT_ID_DIGITS = 11;
    /** {@code CUST-FICO-CREDIT-SCORE} is {@code PIC 9(03)}. */
    private static final int FICO_DIGITS = 3;
    /** {@code ST-TRANID} is {@code PIC X(16)}. */
    private static final int TRAN_ID_WIDTH = 16;
    /** {@code ST-TRANDT} is {@code PIC X(49)}. */
    private static final int TRAN_DESC_WIDTH = 49;
    /** {@code ST-NAME} is {@code PIC X(75)}. */
    private static final int ST_NAME_WIDTH = 75;
    /** {@code L23-NAME} is {@code PIC X(50)}. */
    private static final int L23_NAME_WIDTH = 50;
    /** {@code ST-ADD3} is {@code PIC X(80)}. */
    private static final int ST_ADD3_WIDTH = 80;
    /** {@code ST-ADD1} / {@code ST-ADD2} are {@code PIC X(50)}. */
    private static final int ST_ADDR_WIDTH = 50;
    /** Integer-digit count for the {@code PIC 9(9)} / {@code PIC Z(9)} edited money fields. */
    private static final int MONEY_INT_DIGITS = 9;
    /** Money scale (2 fraction digits) per PR-16. */
    private static final int MONEY_SCALE = 2;

    // =========================================================================================
    // Public API
    // =========================================================================================

    /**
     * Renders a complete HTML customer statement for a single account, reproducing the full
     * {@code CBSTM03A} emission sequence: header ({@code 5100-WRITE-HTML-HEADER}), customer name /
     * address / basic details / transaction-summary header ({@code 5200-WRITE-HTML-NMADBS}), one
     * row per transaction ({@code 6000-WRITE-TRANS}), and the footer ({@code 4000-TRNXFILE-GET}
     * tail).
     *
     * @param customer     the statement's customer (must not be {@code null})
     * @param account      the statement's account (must not be {@code null})
     * @param transactions the transactions to list, in the order they should appear (must not be
     *                     {@code null}; may be empty)
     * @return the fully assembled HTML document as a single {@link String}
     * @throws NullPointerException if {@code customer}, {@code account}, or {@code transactions}
     *                              is {@code null}
     */
    public String renderFullStatement(
            Customer customer,
            Account account,
            List<Transaction> transactions) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(transactions, "transactions");

        if (log.isDebugEnabled()) {
            log.debug("Rendering HTML statement for account {} with {} transaction(s)",
                    account.getAcctId(), transactions.size());
        }

        StringBuilder sb = new StringBuilder(16384);
        sb.append(renderHtmlHeader(account));
        sb.append(renderHtmlCustomerAndBasic(customer, account));
        for (Transaction tx : transactions) {
            sb.append(renderTransactionRow(tx));
        }
        sb.append(renderHtmlFooter());
        return sb.toString();
    }

    /**
     * Emits the statement header — the Java port of {@code 5100-WRITE-HTML-HEADER}
     * (CBSTM03A.CBL L506-L555).
     *
     * <p>COBOL write sequence preserved verbatim: L01, L02, L03, L04, L05, L06, L07, L08, LTRS,
     * L10, L11 (the {@code "Statement for Account Number: "} heading with the 20-char account id),
     * LTDE, LTRE, LTRS, L15, L16, L17, L18, LTDE, LTRE, LTRS, L22-35.</p>
     *
     * @param account the account whose id is rendered into the {@code <h3>} heading
     *                (must not be {@code null})
     * @return the header HTML fragment
     * @throws NullPointerException if {@code account} is {@code null}
     */
    public String renderHtmlHeader(Account account) {
        Objects.requireNonNull(account, "account");

        StringBuilder sb = new StringBuilder(2048);
        // L01-L08: document preamble and the table opening tag (note the two spaces in HTML_L08).
        appendLine(sb, HTML_L01);
        appendLine(sb, HTML_L02);
        appendLine(sb, HTML_L03);
        appendLine(sb, HTML_L04);
        appendLine(sb, HTML_L05);
        appendLine(sb, HTML_L06);
        appendLine(sb, HTML_L07);
        appendLine(sb, HTML_L08);

        // Row 1 — dark header cell carrying the "Statement for Account Number" heading.
        appendLine(sb, HTML_LTRS);
        appendLine(sb, HTML_L10);
        // HTML-L11: MOVE ACCT-ID TO L11-ACCT (PIC X(20)); STRING prefix + acct + suffix.
        sb.append(HTML_L11_PREFIX)
          .append(formatAcctId20(account.getAcctId()))
          .append(HTML_L11_SUFFIX)
          .append(NEWLINE);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_LTRE);

        // Row 2 — orange bank-info cell.
        appendLine(sb, HTML_LTRS);
        appendLine(sb, HTML_L15);
        appendLine(sb, HTML_L16);
        appendLine(sb, HTML_L17);
        appendLine(sb, HTML_L18);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_LTRE);

        // Row 3 — light-gray cell opener; the customer name/address paragraphs follow in 5200.
        appendLine(sb, HTML_LTRS);
        appendLine(sb, HTML_L22_35);

        return sb.toString();
    }

    /**
     * Emits the customer name / address block, the "Basic Details" rows (Account ID, Current
     * Balance, FICO Score), and the "Transaction Summary" column-header row — the Java port of
     * {@code 5200-WRITE-HTML-NMADBS} (CBSTM03A.CBL L557-L672).
     *
     * <p>The name is assembled in {@code 5000-CREATE-STATEMENT} into {@code ST-NAME PIC X(75)} via
     * {@code STRING ... DELIMITED BY ' '}, moved to {@code L23-NAME PIC X(50)}, then emitted with
     * {@code DELIMITED BY '  '} followed by a literal two-space run and {@code </p>}. The three
     * address paragraphs follow the same two-space emit rule: {@code ST-ADD1}/{@code ST-ADD2} are a
     * direct MOVE of {@code CUST-ADDR-LINE-1}/{@code -2} (internal single spaces preserved), while
     * {@code ST-ADD3} is assembled token-by-token from address-line-3, state, country and zip.</p>
     *
     * <p>The three basic-detail lines reproduce the COBOL {@code STRING '<p>...: ' DELIMITED BY '*'
     * field DELIMITED BY '*' '</p>' DELIMITED BY '*'} statements: a fixed 24-character label, the
     * fixed-width edited field, then {@code </p>}.</p>
     *
     * <p><strong>Stored-XSS defense:</strong> the dynamic name and three address values are
     * passed through {@link #htmlEscape(String)} before being appended, so persisted markup
     * cannot become executable HTML. The numeric Account ID, Current Balance and FICO fields are
     * edited via {@link #formatAcctId20(Long)} / {@link #formatStCurrBal(BigDecimal)} /
     * {@link #formatFico20(Integer)} and need no escaping. For safe fixture data the escape is a
     * no-op, preserving PR-09 byte-for-byte parity.</p>
     *
     * @param customer the customer whose name, address and FICO score are rendered
     *                 (must not be {@code null})
     * @param account  the account whose id and current balance are rendered (must not be
     *                 {@code null})
     * @return the customer / basic-details / transaction-summary-header HTML fragment
     * @throws NullPointerException if {@code customer} or {@code account} is {@code null}
     */
    public String renderHtmlCustomerAndBasic(Customer customer, Account account) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(account, "account");

        StringBuilder sb = new StringBuilder(2048);

        // --- Customer name line ---------------------------------------------------------------
        // 5000-CREATE-STATEMENT builds ST-NAME (PIC X(75)); 5200 does MOVE ST-NAME TO L23-NAME
        // (PIC X(50)), then STRING prefix + (L23-NAME DELIMITED BY '  ') + '  ' + '</p>'.
        String stName = fitField(buildStName(customer), ST_NAME_WIDTH);
        String l23Name = fitField(stName, L23_NAME_WIDTH);
        sb.append(HTML_L23_PREFIX)
          .append(htmlEscape(upToFirstDoubleSpace(l23Name)))
          .append(TWO_SPACES)
          .append(P_CLOSE)
          .append(NEWLINE);

        // --- Address line 1 (direct MOVE CUST-ADDR-LINE-1 TO ST-ADD1, PIC X(50)) --------------
        String stAdd1 = fitField(nullSafe(customer.getAddrLine1()), ST_ADDR_WIDTH);
        sb.append(P_OPEN)
          .append(htmlEscape(upToFirstDoubleSpace(stAdd1)))
          .append(TWO_SPACES)
          .append(P_CLOSE)
          .append(NEWLINE);

        // --- Address line 2 (direct MOVE CUST-ADDR-LINE-2 TO ST-ADD2, PIC X(50)) --------------
        String stAdd2 = fitField(nullSafe(customer.getAddrLine2()), ST_ADDR_WIDTH);
        sb.append(P_OPEN)
          .append(htmlEscape(upToFirstDoubleSpace(stAdd2)))
          .append(TWO_SPACES)
          .append(P_CLOSE)
          .append(NEWLINE);

        // --- Address line 3 (built via STRING ... DELIMITED BY ' ' into ST-ADD3, PIC X(80)) ---
        String stAdd3 = fitField(buildStAdd3(customer), ST_ADD3_WIDTH);
        sb.append(P_OPEN)
          .append(htmlEscape(upToFirstDoubleSpace(stAdd3)))
          .append(TWO_SPACES)
          .append(P_CLOSE)
          .append(NEWLINE);

        // Close name/address cell, end the row, then open the "Basic Details" heading row.
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_LTRE);
        appendLine(sb, HTML_LTRS);
        appendLine(sb, HTML_L30_42);
        appendLine(sb, HTML_L31);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_LTRE);
        appendLine(sb, HTML_LTRS);
        appendLine(sb, HTML_L22_35);

        // --- Basic-detail lines (Account ID, Current Balance, FICO Score) ---------------------
        // MOVE ACCT-ID TO ST-ACCT-ID; MOVE ACCT-CURR-BAL TO ST-CURR-BAL;
        // MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE (all in 5000-CREATE-STATEMENT).
        sb.append(BASIC_ACCT_ID_LABEL)
          .append(formatAcctId20(account.getAcctId()))
          .append(P_CLOSE)
          .append(NEWLINE);
        sb.append(BASIC_CURR_BAL_LABEL)
          .append(formatStCurrBal(account.getCurrBal()))
          .append(P_CLOSE)
          .append(NEWLINE);
        sb.append(BASIC_FICO_LABEL)
          .append(formatFico20(customer.getFicoScore()))
          .append(P_CLOSE)
          .append(NEWLINE);

        // Close basic cell, end the row, then open the "Transaction Summary" heading row.
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_LTRE);
        appendLine(sb, HTML_LTRS);
        appendLine(sb, HTML_L30_42);
        appendLine(sb, HTML_L43);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_LTRE);
        appendLine(sb, HTML_LTRS);

        // Column-header cells: Tran ID | Tran Details | Amount.
        appendLine(sb, HTML_L47);
        appendLine(sb, HTML_L48);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_L50);
        appendLine(sb, HTML_L51);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_L53);
        appendLine(sb, HTML_L54);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_LTRE);

        return sb.toString();
    }

    /**
     * Emits a single three-cell transaction row — the Java port of {@code 6000-WRITE-TRANS}
     * (CBSTM03A.CBL L675-L723).
     *
     * <p>COBOL sequence preserved: LTRS, L58 (gray 25% cell), {@code <p>}{@code ST-TRANID}{@code
     * </p>}, LTDE, L61 (gray 55% cell), {@code <p>}{@code ST-TRANDT}{@code </p>}, LTDE, L64 (gray
     * 20% cell), {@code <p>}{@code ST-TRANAMT}{@code </p>}, LTDE, LTRE. The plain-text
     * {@code ST-LINE14} record (written to the {@code STMTFILE} dataset, not the HTML file) has no
     * HTML counterpart and is intentionally not emitted here.</p>
     *
     * <p>{@code MOVE TRNX-ID TO ST-TRANID} fits to {@code PIC X(16)}; {@code MOVE TRNX-DESC TO
     * ST-TRANDT} fits {@code PIC X(100)} into {@code PIC X(49)} (truncating to 49); {@code MOVE
     * TRNX-AMT TO ST-TRANAMT} formats to {@code PIC Z(9).99-}.</p>
     *
     * <p><strong>Stored-XSS defense:</strong> the dynamic transaction id and description are
     * passed through {@link #htmlEscape(String)} before being appended, so persisted markup
     * cannot become executable HTML. The numeric amount, edited via
     * {@link #formatStTranAmt(BigDecimal)}, needs no escaping. For safe fixture data the escape
     * is a no-op, preserving PR-09 byte-for-byte parity.</p>
     *
     * @param tx the transaction to render (must not be {@code null})
     * @return the transaction-row HTML fragment
     * @throws NullPointerException if {@code tx} is {@code null}
     */
    public String renderTransactionRow(Transaction tx) {
        Objects.requireNonNull(tx, "tx");

        StringBuilder sb = new StringBuilder(512);

        String tranId = fitField(nullSafe(tx.getTranId()), TRAN_ID_WIDTH);
        String tranDesc = fitField(nullSafe(tx.getDescription()), TRAN_DESC_WIDTH);
        String tranAmt = formatStTranAmt(tx.getAmount());

        appendLine(sb, HTML_LTRS);
        appendLine(sb, HTML_L58);
        sb.append(P_OPEN).append(htmlEscape(tranId)).append(P_CLOSE).append(NEWLINE);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_L61);
        sb.append(P_OPEN).append(htmlEscape(tranDesc)).append(P_CLOSE).append(NEWLINE);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_L64);
        sb.append(P_OPEN).append(tranAmt).append(P_CLOSE).append(NEWLINE);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_LTRE);

        return sb.toString();
    }

    /**
     * Emits the statement footer — the Java port of the HTML tail of {@code 4000-TRNXFILE-GET}
     * (CBSTM03A.CBL L439-L454).
     *
     * <p>COBOL sequence preserved: LTRS, L10 (dark cell), L75 ({@code <h3>End of Statement</h3>}),
     * LTDE, LTRE, L78 ({@code </table>}), L79 ({@code </body>}), L80 ({@code </html>}). The
     * preceding plain-text records ({@code ST-LINE12}, {@code ST-LINE14A} "Total EXP" line,
     * {@code ST-LINE15}) belong to the {@code STMTFILE} dataset, not the HTML file, and have no
     * HTML counterpart.</p>
     *
     * @return the footer HTML fragment closing the table, body and document
     */
    public String renderHtmlFooter() {
        StringBuilder sb = new StringBuilder(256);
        appendLine(sb, HTML_LTRS);
        appendLine(sb, HTML_L10);
        appendLine(sb, HTML_L75);
        appendLine(sb, HTML_LTDE);
        appendLine(sb, HTML_LTRE);
        appendLine(sb, HTML_L78);
        appendLine(sb, HTML_L79);
        appendLine(sb, HTML_L80);
        return sb.toString();
    }

    // =========================================================================================
    // Private helpers — COBOL STRING/MOVE/PIC-edit semantics
    // =========================================================================================

    /** Appends {@code content} followed by a single {@link #NEWLINE} (one COBOL WRITE record). */
    private static void appendLine(StringBuilder sb, String content) {
        sb.append(content).append(NEWLINE);
    }

    /** Returns {@code ""} for a {@code null} string, otherwise the string unchanged. */
    private static String nullSafe(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * Escapes the five HTML metacharacters in dynamic free-text so that persisted
     * customer/transaction values cannot inject markup or script into the generated
     * statement when it is opened in a browser (stored-XSS defense). The standard
     * XML/HTML entity set is applied, with {@code &} escaped first so that an
     * already-present entity is not double-escaped:
     * <ul>
     *   <li>{@code &} &rarr; {@code &amp;}</li>
     *   <li>{@code <} &rarr; {@code &lt;}</li>
     *   <li>{@code >} &rarr; {@code &gt;}</li>
     *   <li>{@code "} &rarr; {@code &quot;}</li>
     *   <li>{@code '} &rarr; {@code &#39;}</li>
     * </ul>
     *
     * <p><strong>PR-09 byte-for-byte parity preserved.</strong> The method takes an
     * identity fast-path: when the input contains none of the five metacharacters it
     * is returned <em>unchanged</em>, so the rendered statement for the safe CardDemo
     * fixture data is identical to the COBOL reference output (the
     * {@code StatementGenerationParityTest} still passes). Only genuinely unsafe
     * characters &mdash; which do not occur in the legacy fixtures and which the
     * original COBOL would have emitted raw &mdash; are transformed.</p>
     *
     * <p>Applied ONLY to dynamic free-text fields (customer name, address lines,
     * transaction id, transaction description). It is deliberately NOT applied to the
     * fixed HTML literal constants (which contain the intentional markup) nor to the
     * numeric edited fields ({@code formatAcctId20}, {@code formatStCurrBal},
     * {@code formatFico20}, {@code formatStTranAmt}), which can only ever contain
     * digits, spaces, {@code '.'}, {@code '-'} and never a metacharacter.</p>
     *
     * @param s the raw, already fixed-width/truncated text (may be {@code null})
     * @return the escaped text; the original unchanged string when it contains no
     *         metacharacter; or {@code null} if {@code s} is {@code null}
     */
    private static String htmlEscape(String s) {
        if (s == null) {
            return null;
        }
        // Identity fast-path — guarantees byte-for-byte parity for safe fixture data.
        boolean needsEscape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') {
                needsEscape = true;
                break;
            }
        }
        if (!needsEscape) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Reproduces COBOL {@code STRING ... DELIMITED BY ' '}: returns the substring up to (but not
     * including) the first single space. A leading space yields an empty string; no space yields
     * the whole string.
     */
    private static String upToFirstSpace(String s) {
        if (s == null) {
            return "";
        }
        int idx = s.indexOf(' ');
        return (idx < 0) ? s : s.substring(0, idx);
    }

    /**
     * Reproduces COBOL {@code STRING ... DELIMITED BY '  '} (two spaces): returns the substring up
     * to (but not including) the first run of two consecutive spaces. This preserves the COBOL
     * quirk whereby an empty intermediate name/address token introduces a double space that
     * truncates everything after it (e.g. a blank middle name shows only the first name).
     */
    private static String upToFirstDoubleSpace(String s) {
        if (s == null) {
            return "";
        }
        int idx = s.indexOf(TWO_SPACES);
        return (idx < 0) ? s : s.substring(0, idx);
    }

    /**
     * Reproduces a COBOL {@code MOVE} into an alphanumeric {@code PIC X(width)} field: the value is
     * left-justified and either truncated to {@code width} characters or right-padded with spaces
     * to {@code width} characters.
     */
    private static String fitField(String s, int width) {
        String v = (s == null) ? "" : s;
        if (v.length() == width) {
            return v;
        }
        if (v.length() > width) {
            return v.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(v);
        for (int i = v.length(); i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Left-pads {@code s} with {@code '0'} characters up to {@code width}. If {@code s} is already
     * at least {@code width} characters long it is returned unchanged (callers truncate to the
     * low-order digits afterwards where the COBOL target field is narrower).
     */
    private static String padLeftZeros(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        StringBuilder sb = new StringBuilder(width);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(s);
        return sb.toString();
    }

    /**
     * Reproduces COBOL {@code PIC Z(n)} zero suppression: leading {@code '0'} characters are
     * replaced with spaces. If every character is {@code '0'} the entire field becomes spaces
     * (matching the editing of a zero integer part).
     */
    private static String zeroSuppress(String digits) {
        char[] cs = digits.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] == '0') {
                cs[i] = ' ';
            } else {
                break;
            }
        }
        return new String(cs);
    }

    /**
     * Builds {@code ST-NAME} as in {@code 5000-CREATE-STATEMENT} (CBSTM03A.CBL L462-L469):
     * {@code firstTok + ' ' + middleTok + ' ' + lastTok + ' '}, where each token is the customer
     * name field up to its first space (COBOL {@code DELIMITED BY ' '}). The interleaved literal
     * single spaces are {@code DELIMITED BY SIZE}. The caller fits the result into
     * {@code PIC X(75)} and then {@code PIC X(50)}.
     */
    private static String buildStName(Customer c) {
        String first = upToFirstSpace(nullSafe(c.getFirstName()));
        String middle = upToFirstSpace(nullSafe(c.getMiddleName()));
        String last = upToFirstSpace(nullSafe(c.getLastName()));
        return first + " " + middle + " " + last + " ";
    }

    /**
     * Builds {@code ST-ADD3} as in {@code 5000-CREATE-STATEMENT} (CBSTM03A.CBL L472-L481):
     * {@code addr3Tok + ' ' + stateTok + ' ' + countryTok + ' ' + zipTok + ' '}, where each token
     * is the corresponding field up to its first space (COBOL {@code DELIMITED BY ' '}). The
     * caller fits the result into {@code PIC X(80)}.
     */
    private static String buildStAdd3(Customer c) {
        String addr3 = upToFirstSpace(nullSafe(c.getAddrLine3()));
        String state = upToFirstSpace(nullSafe(c.getStateCd()));
        String country = upToFirstSpace(nullSafe(c.getCountryCd()));
        String zip = upToFirstSpace(nullSafe(c.getZipCd()));
        return addr3 + " " + state + " " + country + " " + zip + " ";
    }

    /**
     * Formats the account id as the COBOL {@code MOVE ACCT-ID TO ST-ACCT-ID} / {@code L11-ACCT}
     * produces: the {@code PIC 9(11)} unsigned numeric (leading zeros, low-order 11 digits if
     * longer) moved into an alphanumeric {@code PIC X(20)} field (left-justified, space-padded).
     *
     * @param acctId the account id; {@code null} is treated as {@code 0}
     * @return a 20-character string, e.g. account {@code 1} &rarr; {@code "00000000001"} + 9 spaces
     */
    private static String formatAcctId20(Long acctId) {
        long v = (acctId == null) ? 0L : acctId;
        String raw = Long.toString(v);
        if (raw.startsWith("-")) {
            // ACCT-ID is PIC 9(11) (unsigned); mirror COBOL by dropping the sign.
            raw = raw.substring(1);
        }
        raw = padLeftZeros(raw, ACCT_ID_DIGITS);
        if (raw.length() > ACCT_ID_DIGITS) {
            raw = raw.substring(raw.length() - ACCT_ID_DIGITS);
        }
        return fitField(raw, FIELD_WIDTH_20);
    }

    /**
     * Formats the FICO score as the COBOL {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE}
     * produces: the {@code PIC 9(03)} unsigned numeric (leading zeros) moved into an alphanumeric
     * {@code PIC X(20)} field (left-justified, space-padded).
     *
     * @param fico the FICO score; {@code null} is treated as {@code 0} (rendered {@code "000"})
     * @return a 20-character string, e.g. {@code 750} &rarr; {@code "750"} + 17 spaces
     */
    private static String formatFico20(Integer fico) {
        int v = (fico == null) ? 0 : fico;
        String raw = Integer.toString(v);
        if (raw.startsWith("-")) {
            raw = raw.substring(1);
        }
        raw = padLeftZeros(raw, FICO_DIGITS);
        if (raw.length() > FICO_DIGITS) {
            raw = raw.substring(raw.length() - FICO_DIGITS);
        }
        return fitField(raw, FIELD_WIDTH_20);
    }

    /**
     * Formats a monetary value as the COBOL edited field {@code ST-CURR-BAL PIC 9(9).99-}
     * (13 characters): 9 integer digits with leading zeros (low-order 9 digits if the value is
     * larger, mirroring the {@code MOVE} from {@code S9(10)V99}), a decimal point, 2 fraction
     * digits, then a trailing sign position (a space when non-negative, {@code '-'} when negative).
     *
     * <p>Money arithmetic is exact: the value is scaled to 2 fraction digits with
     * {@link RoundingMode#HALF_UP} (PR-16). {@code float}/{@code double} are never used.</p>
     *
     * @param value the balance; {@code null} is treated as {@link BigDecimal#ZERO}
     * @return a 13-character edited string, e.g. {@code 12.34} &rarr; {@code "000000012.34 "}
     */
    private static String formatStCurrBal(BigDecimal value) {
        BigDecimal v = (value == null) ? BigDecimal.ZERO : value;
        BigDecimal scaled = v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        boolean negative = scaled.signum() < 0;
        String plain = scaled.abs().toPlainString();
        int dot = plain.indexOf('.');
        String intPart = (dot >= 0) ? plain.substring(0, dot) : plain;
        String fracPart = (dot >= 0) ? plain.substring(dot + 1) : "00";
        String int9 = padLeftZeros(intPart, MONEY_INT_DIGITS);
        if (int9.length() > MONEY_INT_DIGITS) {
            int9 = int9.substring(int9.length() - MONEY_INT_DIGITS);
        }
        return int9 + "." + fracPart + (negative ? "-" : " ");
    }

    /**
     * Formats a monetary value as the COBOL edited field {@code ST-TRANAMT PIC Z(9).99-}
     * (13 characters): 9 integer positions with leading-zero suppression (blanks; an all-zero
     * integer part is 9 blanks), a decimal point, 2 fraction digits, then a trailing sign position
     * (a space when non-negative, {@code '-'} when negative).
     *
     * <p>Money arithmetic is exact: the value is scaled to 2 fraction digits with
     * {@link RoundingMode#HALF_UP} (PR-16). {@code float}/{@code double} are never used.</p>
     *
     * @param value the transaction amount; {@code null} is treated as {@link BigDecimal#ZERO}
     * @return a 13-character edited string, e.g. {@code 12.34} &rarr; {@code "       12.34 "},
     *         {@code 0.00} &rarr; {@code "         .00 "}
     */
    private static String formatStTranAmt(BigDecimal value) {
        BigDecimal v = (value == null) ? BigDecimal.ZERO : value;
        BigDecimal scaled = v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        boolean negative = scaled.signum() < 0;
        String plain = scaled.abs().toPlainString();
        int dot = plain.indexOf('.');
        String intPart = (dot >= 0) ? plain.substring(0, dot) : plain;
        String fracPart = (dot >= 0) ? plain.substring(dot + 1) : "00";
        String int9 = padLeftZeros(intPart, MONEY_INT_DIGITS);
        if (int9.length() > MONEY_INT_DIGITS) {
            int9 = int9.substring(int9.length() - MONEY_INT_DIGITS);
        }
        int9 = zeroSuppress(int9);
        return int9 + "." + fracPart + (negative ? "-" : " ");
    }
}
