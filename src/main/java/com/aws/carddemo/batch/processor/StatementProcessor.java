/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.batch.processor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.aws.carddemo.batch.reader.StatementFileService;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.exception.FileStatusException;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the statement-assembly transform of the legacy
 * COBOL batch program {@code CBSTM03A} (source {@code app/cbl/CBSTM03A.CBL}, relocated to
 * {@code legacy/cbl/CBSTM03A.CBL}; triggered by {@code CREASTMT.JCL}) together with the file I/O it
 * delegated to its called subprogram {@code CBSTM03B} ({@code legacy/cbl/CBSTM03B.CBL}).
 *
 * <p>It is the transform stage of {@code StatementGenerationJob} in the parent {@code batch/}
 * package. The reader delivers one {@link CardXref} (one card) per call; this processor produces
 * <strong>one complete statement per card</strong> in <strong>both plain-text and HTML</strong>
 * form, returned as a {@link StatementDocument}. The downstream {@code writer/}
 * ({@code StatementItemWriter}) writes the text lines as 80-byte records and the HTML lines as
 * 100-byte records via {@code common/util/FixedWidthCodec}.</p>
 *
 * <h2>Data access is fully delegated (CBSTM03B &rarr; StatementFileService)</h2>
 * <p>The mainframe driver {@code CBSTM03A} owned no file control of its own: it issued
 * {@code CALL 'CBSTM03B'} (13 times) for every VSAM read. That subprogram is re-platformed as the
 * sibling {@code reader/} bean {@link StatementFileService} (a {@code @Service}). This processor is
 * constructor-injected with that service and performs <strong>no</strong> repository access
 * directly. The three lookups map one-to-one to CBSTM03A paragraphs:</p>
 * <ul>
 *   <li>{@code 2000-CUSTFILE-GET} (key {@code XREF-CUST-ID}) &rarr;
 *       {@link StatementFileService#readCustomer(Long)}</li>
 *   <li>{@code 3000-ACCTFILE-GET} (key {@code XREF-ACCT-ID}) &rarr;
 *       {@link StatementFileService#readAccount(Long)}</li>
 *   <li>{@code 4000-TRNXFILE-GET} (per-card group) &rarr;
 *       {@link StatementFileService#readTransactionsForCard(String)} (already ordered by
 *       {@code tranId} ascending)</li>
 * </ul>
 *
 * <h2>Abend parity (batch return code 8)</h2>
 * <p>In {@code CBSTM03A} the keyed customer and account reads treat any FILE STATUS other than
 * {@code '00'} as fatal: {@code PERFORM 9999-ABEND-PROGRAM} which issues {@code CALL 'CEE3ABD'}.
 * That is reproduced here: an empty {@link java.util.Optional} from the file service (customer or
 * account not found) throws a {@link FileStatusException}
 * ({@link FileStatusException#STATUS_RECORD_NOT_FOUND}), which the job maps to batch return code 8.
 * The record is never silently skipped.</p>
 *
 * <h2>Dual output and authoritative record widths</h2>
 * <p>{@code CBSTM03A} emits two files &mdash; {@code STMTFILE} and {@code HTMLFILE}. Their fixed
 * record widths are taken authoritatively from the COBOL {@code FD} entries, not from the
 * {@code CREASTMT.JCL} STEP030 delete-step DCB (which misleadingly shows {@code LRECL=80} for both):
 * {@code FD-STMTFILE-REC PIC X(80)} &rarr; text lines are &le; 80 characters, and
 * {@code FD-HTMLFILE-REC PIC X(100)} &rarr; HTML lines are &le; 100 characters. Every line assembled
 * here respects those bounds; the writer enforces the exact fixed width by padding/truncation.</p>
 *
 * <h2>COBOL PIC numeric-edit fidelity</h2>
 * <p>{@code FixedWidthCodec} handles record-level overpunch and scale, but <em>not</em> edited
 * display pictures, so the two COBOL trailing-sign editing pictures live here as private static
 * helpers operating purely on {@link BigDecimal}/{@link BigInteger} (never {@code double} or
 * {@code float}):</p>
 * <ul>
 *   <li>{@code formatSignedZeroPadded} &rarr; {@code PIC 9(9).99-} (zero-filled integer positions),
 *       used for {@code ST-CURR-BAL}.</li>
 *   <li>{@code formatSignedSuppressed} &rarr; {@code PIC Z(9).99-} (leading-zero suppression),
 *       used for {@code ST-TRANAMT} and {@code ST-TOTAL-TRAMT}.</li>
 * </ul>
 * <p>Both are 13 characters wide (9 integer + {@code '.'} + 2 fraction + trailing sign) and place a
 * {@code '-'} after negatives and a space after non-negatives, exactly matching COBOL trailing-minus
 * semantics.</p>
 *
 * <h2>Sensitive-data rule</h2>
 * <p>A statement is pure PII (customer name, address, balances, card-derived transaction data). This
 * class therefore performs <strong>no logging of statement or record contents</strong> &mdash; no
 * logger is declared, so no name, address, card number, balance, password or CVV can leak from here.</p>
 *
 * @see StatementFileService
 * @see CardXref
 * @see Customer
 * @see Account
 * @see Transaction
 * @see FileStatusException
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
public class StatementProcessor
        implements ItemProcessor<CardXref, StatementProcessor.StatementDocument> {

    // ------------------------------------------------------------------------------------------
    // Fixed-width record bounds and reusable fixed text/HTML fragments (COBOL FILLER literals).
    // ------------------------------------------------------------------------------------------

    /** {@code FD-STMTFILE-REC PIC X(80)} &mdash; authoritative plain-text record width. */
    private static final int TEXT_RECORD_WIDTH = 80;

    /** Full-width rule line: {@code ST-LINE5}/{@code ST-LINE10}/{@code ST-LINE12} {@code ALL '-'} X(80). */
    private static final String DASHES = "-".repeat(TEXT_RECORD_WIDTH);

    /** {@code ST-LINE0}: {@code '*'} X(31) + {@code 'START OF STATEMENT'} X(18) + {@code '*'} X(31) = 80. */
    private static final String START_BANNER = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);

    /** {@code ST-LINE15}: {@code '*'} X(32) + {@code 'END OF STATEMENT'} X(16) + {@code '*'} X(32) = 80. */
    private static final String END_BANNER = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    /** {@code ST-LINE6}: SPACES X(33) + {@code 'Basic Details'} X(14) + SPACES X(33) = 80. */
    private static final String BASIC_DETAILS_LINE = " ".repeat(33) + "Basic Details" + " ".repeat(34);

    /** {@code ST-LINE11}: SPACES X(30) + {@code 'TRANSACTION SUMMARY '} X(20) + SPACES X(30) = 80. */
    private static final String TRANSACTION_SUMMARY_LINE =
            " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30);

    /** {@code ST-LINE13}: {@code 'Tran ID         '} X(16) + {@code 'Tran Details    '} X(51) + {@code '  Tran Amount'} X(13) = 80. */
    private static final String TRAN_HEADER_LINE =
            "Tran ID         " + "Tran Details    " + " ".repeat(35) + "  Tran Amount";

    // Repeated HTML scaffolding fragments (COBOL 88-level HTML-LTRS / HTML-LTRE / HTML-LTDE).
    private static final String HTML_TR_OPEN = "<tr>";
    private static final String HTML_TR_CLOSE = "</tr>";
    private static final String HTML_TD_CLOSE = "</td>";

    // ------------------------------------------------------------------------------------------
    // Numeric-edit constants (COMP-3 / edited-picture arithmetic uses BigInteger, never double).
    // ------------------------------------------------------------------------------------------

    /** Divisor/modulus separating the two-digit fraction from the integer part. */
    private static final BigInteger HUNDRED = BigInteger.valueOf(100L);

    /** {@code 10^9} &mdash; capacity of the nine integer positions in {@code 9(9)}/{@code Z(9)}. */
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);

    /** Monetary zero at scale 2, reused for the running total seed and null-amount guard. */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);

    /**
     * Read-only statement data-access collaborator (the re-platformed {@code CBSTM03B}). Supplied by
     * constructor injection and held {@code final}; no repositories are injected directly.
     */
    private final StatementFileService statementFileService;

    /**
     * Creates the processor with its statement file service.
     *
     * <p>Constructor injection is the Java analog of the static {@code CALL 'CBSTM03B'} linkage the
     * COBOL driver used for all file access. Because this is the only constructor, Spring uses it for
     * autowiring without an explicit {@code @Autowired} annotation.</p>
     *
     * @param statementFileService the statement file service (re-platform of {@code CBSTM03B});
     *                             must not be {@code null}
     */
    public StatementProcessor(StatementFileService statementFileService) {
        this.statementFileService = statementFileService;
    }

    /**
     * Builds one complete statement (text + HTML) for a single card, reproducing the
     * {@code CBSTM03A 1000-MAINLINE} sequence (source L316-329):
     * {@code 2000-CUSTFILE-GET} &rarr; {@code 3000-ACCTFILE-GET} &rarr; {@code 5000-CREATE-STATEMENT}
     * &rarr; reset {@code WS-TOTAL-AMT} &rarr; {@code 4000-TRNXFILE-GET} (which drives
     * {@code 6000-WRITE-TRANS} per transaction and appends the total and end banner).
     *
     * @param item the card cross-reference delivered by the reader (one card); must not be {@code null}
     * @return the assembled {@link StatementDocument} (never {@code null})
     * @throws FileStatusException if the customer ({@code 2000-CUSTFILE-GET}) or account
     *                             ({@code 3000-ACCTFILE-GET}) cannot be found &mdash; the abend-equivalent
     *                             ({@code 9999-ABEND-PROGRAM}) mapped to batch return code 8
     */
    @Override
    public StatementDocument process(CardXref item) {
        // ---- 2000-CUSTFILE-GET: keyed read by XREF-CUST-ID; non-'00' status abends (RC 8). ----
        Customer customer = statementFileService.readCustomer(item.getCustId())
                .orElseThrow(() -> new FileStatusException(
                        FileStatusException.STATUS_RECORD_NOT_FOUND,
                        "Customer not found for statement: " + item.getCustId()));

        // ---- 3000-ACCTFILE-GET: keyed read by XREF-ACCT-ID; non-'00' status abends (RC 8). ----
        Account account = statementFileService.readAccount(item.getAcctId())
                .orElseThrow(() -> new FileStatusException(
                        FileStatusException.STATUS_RECORD_NOT_FOUND,
                        "Account not found for statement: " + item.getAcctId()));

        // ---- 4000-TRNXFILE-GET: this card's transactions, already tranId-ascending. ----
        List<Transaction> transactions = statementFileService.readTransactionsForCard(item.getXrefCardNum());

        // MOVE 0 TO WS-TOTAL-AMT (reset before the per-transaction accumulation).
        BigDecimal totalAmt = ZERO_MONEY;

        // Derive the edited/display fields shared by the text (ST-*) and HTML (L11/L23/BSIC/TRAN) forms.
        String rawName = buildName(customer);                                    // ST-NAME source (STRING, L462-469)
        String add1Field = fit(nz(customer.getCustAddrLine1()), 50);             // ST-ADD1 (MOVE, X50)
        String add2Field = fit(nz(customer.getCustAddrLine2()), 50);             // ST-ADD2 (MOVE, X50)
        String add3Field = fit(buildAddressLine3(customer), 80);                 // ST-ADD3 (STRING, L470-481, X80)
        String acctField = padRight(formatAccountId(account.getAcctId()), 20);   // ST-ACCT-ID / L11-ACCT (X20)
        String currBalEdited = formatSignedZeroPadded(account.getCurrBal());     // ST-CURR-BAL (PIC 9(9).99-)
        String ficoField = padRight(formatFico(customer.getCustFicoCreditScore()), 20); // ST-FICO-SCORE (X20)

        List<String> textLines = new ArrayList<>();
        List<String> htmlLines = new ArrayList<>();

        // ---- 5000-CREATE-STATEMENT: fixed text header block (ST-LINE0 .. ST-LINE13, ST-LINE12). ----
        appendStatementTextHeader(textLines, rawName, add1Field, add2Field, add3Field,
                acctField, currBalEdited, ficoField);

        // ---- 5100-WRITE-HTML-HEADER + 5200-WRITE-HTML-NMADBS: HTML preamble/name/address/basics. ----
        appendHtmlHeader(htmlLines, acctField);
        appendHtmlNameAddressBasic(htmlLines, rawName, add1Field, add2Field, add3Field,
                acctField, currBalEdited, ficoField);

        // ---- 4000-TRNXFILE-GET loop -> 6000-WRITE-TRANS per transaction (L675-679). ----
        for (Transaction tx : transactions) {
            BigDecimal amt = (tx.getTranAmt() == null) ? ZERO_MONEY : tx.getTranAmt();
            String tranIdField = padRight(nz(tx.getTranId()), 16);               // ST-TRANID (X16)
            String tranDescField = padRight(nz(tx.getTranDesc()), 49);           // ST-TRANDT (X49, truncated)
            String tranAmtEdited = formatSignedSuppressed(amt);                  // ST-TRANAMT (PIC Z(9).99-)

            // ST-LINE14: ST-TRANID(16) + ' ' + ST-TRANDT(49) + '$' + ST-TRANAMT(13) = 80.
            textLines.add(tranIdField + " " + tranDescField + "$" + tranAmtEdited);
            // 6000-WRITE-TRANS HTML row (three table cells).
            appendHtmlTransactionRow(htmlLines, tranIdField, tranDescField, tranAmtEdited);

            // ADD TRNX-AMT TO WS-TOTAL-AMT.
            totalAmt = totalAmt.add(amt);
        }

        // ---- 4000-TRNXFILE-GET tail: ST-LINE12 separator, ST-LINE14A total, ST-LINE15 end banner. ----
        textLines.add(DASHES);                                                   // ST-LINE12
        textLines.add(buildTotalLine(totalAmt));                                 // ST-LINE14A
        textLines.add(END_BANNER);                                               // ST-LINE15
        // ---- 4000-TRNXFILE-GET HTML tail (End of Statement banner + closing tags). ----
        appendHtmlTail(htmlLines);

        return new StatementDocument(textLines, htmlLines);
    }

    // ==========================================================================================
    // Plain-text assembly (STMTFILE, FD-STMTFILE-REC PIC X(80)) — 5000-CREATE-STATEMENT.
    // ==========================================================================================

    /**
     * Appends the fixed statement header text lines in {@code 5000-CREATE-STATEMENT} order
     * (source L451-502): {@code ST-LINE0}, then {@code ST-LINE1}..{@code ST-LINE13} with the two
     * {@code ST-LINE12} rules that bracket the transaction header. Each logical line is &le; 80
     * characters; the writer enforces the exact 80-byte width.
     *
     * @param text          the plain-text accumulator
     * @param rawName       the assembled name (ST-NAME source), truncated here to X(75)
     * @param add1Field     address line 1 (ST-ADD1, X50)
     * @param add2Field     address line 2 (ST-ADD2, X50)
     * @param add3Field     assembled address line 3 (ST-ADD3, X80)
     * @param acctField     account id edited to X(20)
     * @param currBalEdited current balance edited to {@code PIC 9(9).99-} (13)
     * @param ficoField     FICO score edited to X(20)
     */
    private void appendStatementTextHeader(List<String> text, String rawName, String add1Field,
            String add2Field, String add3Field, String acctField, String currBalEdited, String ficoField) {
        text.add(START_BANNER);                              // ST-LINE0
        text.add(fit(rawName, 75));                          // ST-LINE1 (ST-NAME, X75)
        text.add(add1Field);                                 // ST-LINE2 (ST-ADD1)
        text.add(add2Field);                                 // ST-LINE3 (ST-ADD2)
        text.add(add3Field);                                 // ST-LINE4 (ST-ADD3)
        text.add(DASHES);                                    // ST-LINE5
        text.add(BASIC_DETAILS_LINE);                        // ST-LINE6
        text.add(DASHES);                                    // ST-LINE5 (repeated)
        text.add("Account ID         :" + acctField);        // ST-LINE7
        text.add("Current Balance    :" + currBalEdited);    // ST-LINE8
        text.add("FICO Score         :" + ficoField);        // ST-LINE9
        text.add(DASHES);                                    // ST-LINE10
        text.add(TRANSACTION_SUMMARY_LINE);                  // ST-LINE11
        text.add(DASHES);                                    // ST-LINE12
        text.add(TRAN_HEADER_LINE);                          // ST-LINE13
        text.add(DASHES);                                    // ST-LINE12 (repeated)
    }

    /**
     * Builds {@code ST-LINE14A} (source L138-142): {@code 'Total EXP:'} X(10) + SPACES X(56) +
     * {@code '$'} X(1) + {@code ST-TOTAL-TRAMT} edited {@code PIC Z(9).99-} (13) = 80.
     *
     * @param totalAmt the accumulated transaction total (scale 2)
     * @return the formatted total line (80 characters)
     */
    private String buildTotalLine(BigDecimal totalAmt) {
        return "Total EXP:" + " ".repeat(56) + "$" + formatSignedSuppressed(totalAmt);
    }

    // ==========================================================================================
    // HTML assembly (HTMLFILE, FD-HTMLFILE-REC PIC X(100)) — 5100 / 5200 / 6000 / 4000 tail.
    // Fixed structural lines are the COBOL 88-level HTML-Lxx constants, reproduced verbatim.
    // ==========================================================================================

    /**
     * Appends the HTML preamble, {@code 5100-WRITE-HTML-HEADER} (source L506-555): DOCTYPE through
     * the account-number heading and the bank address block, ending with the opening cell that the
     * name/address block fills. Every line is &le; 100 characters.
     *
     * @param html      the HTML accumulator
     * @param acctField account id edited to X(20), embedded in {@code HTML-L11}
     */
    private void appendHtmlHeader(List<String> html, String acctField) {
        html.add("<!DOCTYPE html>");                                                                 // HTML-L01
        html.add("<html lang=\"en\">");                                                              // HTML-L02
        html.add("<head>");                                                                          // HTML-L03
        html.add("<meta charset=\"utf-8\">");                                                        // HTML-L04
        html.add("<title>HTML Table Layout</title>");                                                // HTML-L05
        html.add("</head>");                                                                         // HTML-L06
        html.add("<body style=\"margin:0px;\">");                                                    // HTML-L07
        html.add("<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">"); // HTML-L08
        html.add(HTML_TR_OPEN);                                                                      // HTML-LTRS
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">");         // HTML-L10
        html.add("<h3>Statement for Account Number: " + acctField + "</h3>");                         // HTML-L11
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add(HTML_TR_CLOSE);                                                                     // HTML-LTRE
        html.add(HTML_TR_OPEN);                                                                      // HTML-LTRS
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">");           // HTML-L15
        html.add("<p style=\"font-size:16px\">Bank of XYZ</p>");                                      // HTML-L16
        html.add("<p>410 Terry Ave N</p>");                                                          // HTML-L17
        html.add("<p>Seattle WA 99999</p>");                                                         // HTML-L18
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add(HTML_TR_CLOSE);                                                                     // HTML-LTRE
        html.add(HTML_TR_OPEN);                                                                      // HTML-LTRS
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">");           // HTML-L22-35
    }

    /**
     * Appends {@code 5200-WRITE-HTML-NMADBS} (source L558-627): the customer name and three address
     * lines, the &quot;Basic Details&quot; section (account id, current balance, FICO), and the
     * &quot;Transaction Summary&quot; column headers.
     *
     * <p>The name/address values are wrapped exactly as the COBOL {@code STRING ... DELIMITED BY '  '}
     * does: the field is copied up to the first double-space (trailing-trim), then a literal two
     * spaces and the closing tag are appended (see {@link #upToDoubleSpace(String)}). The basic-detail
     * values use {@code DELIMITED BY '*'}, i.e. the whole fixed-width field including trailing spaces,
     * so the edited/padded fields are emitted verbatim.</p>
     *
     * @param html          the HTML accumulator
     * @param rawName       the assembled name (ST-NAME source); truncated to X(50) for {@code L23-NAME}
     * @param add1Field     address line 1 (ST-ADD1, X50)
     * @param add2Field     address line 2 (ST-ADD2, X50)
     * @param add3Field     assembled address line 3 (ST-ADD3, X80)
     * @param acctField     account id edited to X(20)
     * @param currBalEdited current balance edited to {@code PIC 9(9).99-} (13)
     * @param ficoField     FICO score edited to X(20)
     */
    private void appendHtmlNameAddressBasic(List<String> html, String rawName, String add1Field,
            String add2Field, String add3Field, String acctField, String currBalEdited, String ficoField) {
        // Name + address block (HTML-L23 / HTML-ADDR-LN, DELIMITED BY '  '). The trimmed data value is
        // HTML-escaped (see escapeHtml / decision log D43-D44) so customer-sourced text renders as inert
        // literal text in a browser that opens this artifact; escaping is a no-op for the metacharacter-
        // free data of the golden fixture, so byte-exact parity is preserved.
        html.add("<p style=\"font-size:16px\">" + escapeHtml(upToDoubleSpace(fit(rawName, 50))) + "  </p>"); // L23-NAME
        html.add("<p>" + escapeHtml(upToDoubleSpace(add1Field)) + "  </p>");                          // HTML-ADDR-LN (ADD1)
        html.add("<p>" + escapeHtml(upToDoubleSpace(add2Field)) + "  </p>");                          // HTML-ADDR-LN (ADD2)
        html.add("<p>" + escapeHtml(upToDoubleSpace(add3Field)) + "  </p>");                          // HTML-ADDR-LN (ADD3)
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add(HTML_TR_CLOSE);                                                                     // HTML-LTRE
        html.add(HTML_TR_OPEN);                                                                      // HTML-LTRS
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">"); // HTML-L30-42
        html.add("<p style=\"font-size:16px\">Basic Details</p>");                                    // HTML-L31
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add(HTML_TR_CLOSE);                                                                     // HTML-LTRE
        html.add(HTML_TR_OPEN);                                                                      // HTML-LTRS
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">");           // HTML-L22-35
        // Basic details (HTML-BSIC-LN, DELIMITED BY '*' → full field incl. trailing spaces).
        html.add("<p>Account ID         : " + acctField + "</p>");                                    // HTML-BSIC-LN (ACCT)
        html.add("<p>Current Balance    : " + currBalEdited + "</p>");                                // HTML-BSIC-LN (BAL)
        html.add("<p>FICO Score         : " + ficoField + "</p>");                                    // HTML-BSIC-LN (FICO)
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add(HTML_TR_CLOSE);                                                                     // HTML-LTRE
        html.add(HTML_TR_OPEN);                                                                      // HTML-LTRS
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">"); // HTML-L30-42
        html.add("<p style=\"font-size:16px\">Transaction Summary</p>");                              // HTML-L43
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add(HTML_TR_CLOSE);                                                                     // HTML-LTRE
        html.add(HTML_TR_OPEN);                                                                      // HTML-LTRS
        html.add("<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">");  // HTML-L47
        html.add("<p style=\"font-size:16px\">Tran ID</p>");                                          // HTML-L48
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add("<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">");  // HTML-L50
        html.add("<p style=\"font-size:16px\">Tran Details</p>");                                     // HTML-L51
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add("<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">"); // HTML-L53
        html.add("<p style=\"font-size:16px\">Amount</p>");                                           // HTML-L54
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add(HTML_TR_CLOSE);                                                                     // HTML-LTRE
    }

    /**
     * Appends one HTML transaction row, {@code 6000-WRITE-TRANS} (source L681-721): three cells
     * carrying the transaction id, description and edited amount. Values use {@code DELIMITED BY '*'}
     * (whole fixed-width field), so the already-padded fields are emitted verbatim.
     *
     * @param html          the HTML accumulator
     * @param tranIdField   transaction id (ST-TRANID, X16)
     * @param tranDescField transaction description (ST-TRANDT, X49)
     * @param tranAmtEdited transaction amount edited to {@code PIC Z(9).99-} (13)
     */
    private void appendHtmlTransactionRow(List<String> html, String tranIdField, String tranDescField,
            String tranAmtEdited) {
        html.add(HTML_TR_OPEN);                                                                      // HTML-LTRS
        html.add("<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">");  // HTML-L58
        // Transaction-sourced values are HTML-escaped (see escapeHtml / decision log D43-D44) so an
        // injected tag/script in a description renders as inert literal text; no-op for clean data.
        html.add("<p>" + escapeHtml(tranIdField) + "</p>");                                          // HTML-TRAN-LN (ID)
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add("<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">");  // HTML-L61
        html.add("<p>" + escapeHtml(tranDescField) + "</p>");                                        // HTML-TRAN-LN (DESC)
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add("<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">"); // HTML-L64
        html.add("<p>" + tranAmtEdited + "</p>");                                                    // HTML-TRAN-LN (AMT)
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add(HTML_TR_CLOSE);                                                                     // HTML-LTRE
    }

    /**
     * Appends the HTML statement footer emitted by {@code 4000-TRNXFILE-GET} after the transaction
     * loop (source L604-623): the &quot;End of Statement&quot; banner row and the closing
     * {@code </table></body></html>} tags.
     *
     * @param html the HTML accumulator
     */
    private void appendHtmlTail(List<String> html) {
        html.add(HTML_TR_OPEN);                                                                      // HTML-LTRS
        html.add("<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">");         // HTML-L10
        html.add("<h3>End of Statement</h3>");                                                        // HTML-L75
        html.add(HTML_TD_CLOSE);                                                                     // HTML-LTDE
        html.add(HTML_TR_CLOSE);                                                                     // HTML-LTRE
        html.add("</table>");                                                                        // HTML-L78
        html.add("</body>");                                                                         // HTML-L79
        html.add("</html>");                                                                         // HTML-L80
    }

    // ==========================================================================================
    // Field builders — reproduce the CBSTM03A STRING/MOVE assembly of derived display fields.
    // ==========================================================================================

    /**
     * Assembles {@code ST-NAME} exactly as {@code 5000-CREATE-STATEMENT} does with
     * {@code STRING CUST-FIRST-NAME DELIMITED BY ' ' ... CUST-MIDDLE-NAME ... CUST-LAST-NAME}
     * (source L462-469): each name component is copied up to its first embedded space
     * ({@code DELIMITED BY ' '}) and joined with single-space separators.
     *
     * @param c the customer
     * @return the assembled name (unbounded; callers truncate to the relevant field width)
     */
    private static String buildName(Customer c) {
        return delimitBySpace(nz(c.getCustFirstName()))
                + " " + delimitBySpace(nz(c.getCustMiddleName()))
                + " " + delimitBySpace(nz(c.getCustLastName()));
    }

    /**
     * Assembles {@code ST-ADD3} exactly as {@code 5000-CREATE-STATEMENT} does with
     * {@code STRING CUST-ADDR-LINE-3 DELIMITED BY ' ' ... CUST-ADDR-STATE-CD ... CUST-ADDR-COUNTRY-CD
     * ... CUST-ADDR-ZIP} (source L470-481): each component is copied up to its first embedded space
     * and joined with single-space separators.
     *
     * @param c the customer
     * @return the assembled third address line (unbounded; callers truncate to X(80))
     */
    private static String buildAddressLine3(Customer c) {
        return delimitBySpace(nz(c.getCustAddrLine3()))
                + " " + delimitBySpace(nz(c.getCustAddrStateCd()))
                + " " + delimitBySpace(nz(c.getCustAddrCountryCd()))
                + " " + delimitBySpace(nz(c.getCustAddrZip()));
    }

    /**
     * Edits the account id as the COBOL {@code MOVE ACCT-ID TO ST-ACCT-ID} does: {@code ACCT-ID}
     * is {@code PIC 9(11)}, so it renders as eleven zero-padded digits (later left-justified in the
     * X(20) field by {@link #padRight(String, int)}).
     *
     * @param acctId the account id ({@code null} treated as zero)
     * @return the eleven-digit zero-padded account id
     */
    private static String formatAccountId(Long acctId) {
        return String.format(Locale.ROOT, "%011d", (acctId == null) ? 0L : acctId);
    }

    /**
     * Edits the FICO score as the COBOL {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE} does:
     * {@code CUST-FICO-CREDIT-SCORE} is {@code PIC 9(3)}, so it renders as three zero-padded digits
     * (later left-justified in the X(20) field).
     *
     * @param fico the FICO score ({@code null} treated as zero)
     * @return the three-digit zero-padded score
     */
    private static String formatFico(Integer fico) {
        return String.format(Locale.ROOT, "%03d", (fico == null) ? 0 : fico);
    }

    // ==========================================================================================
    // COBOL PIC numeric-edit helpers — trailing-sign edited pictures (BigDecimal/BigInteger only).
    // ==========================================================================================

    /**
     * Reproduces COBOL {@code PIC 9(9).99-} (the {@code ST-CURR-BAL} edit): nine <em>zero-filled</em>
     * integer positions, a decimal point, two fraction digits, and a trailing sign character
     * ({@code '-'} when negative, a space otherwise). Total width 13.
     *
     * <p>Example: {@code -123.45} &rarr; {@code "000000123.45-"}; {@code 123.45} &rarr;
     * {@code "000000123.45 "} (trailing space).</p>
     *
     * @param v the monetary value ({@code null} treated as zero); never a {@code double}/{@code float}
     * @return the 13-character edited string
     */
    private static String formatSignedZeroPadded(BigDecimal v) {
        return editTrailingSign(v, false);
    }

    /**
     * Reproduces COBOL {@code PIC Z(9).99-} (the {@code ST-TRANAMT}/{@code ST-TOTAL-TRAMT} edit):
     * nine integer positions with <em>leading-zero suppression</em> (leading zeros become spaces; a
     * zero integer part yields nine spaces), a decimal point, two fraction digits, and a trailing sign
     * ({@code '-'} when negative, a space otherwise). Total width 13.
     *
     * <p>Example: {@code -123.45} &rarr; {@code "      123.45-"}; {@code 0.00} &rarr;
     * {@code "         .00 "}.</p>
     *
     * @param v the monetary value ({@code null} treated as zero); never a {@code double}/{@code float}
     * @return the 13-character edited string
     */
    private static String formatSignedSuppressed(BigDecimal v) {
        return editTrailingSign(v, true);
    }

    /**
     * Shared implementation of the two trailing-sign edited pictures. Splits a scaled
     * {@link BigDecimal} into a nine-position integer part (truncated modulo {@code 10^9}, matching
     * the {@code 9(9)}/{@code Z(9)} capacity of the COBOL picture) and a two-digit fraction, then
     * formats the integer part either zero-filled or with leading-zero suppression.
     *
     * @param value    the monetary value ({@code null} treated as zero)
     * @param suppress {@code true} for {@code Z(9)} (leading-zero suppression); {@code false} for
     *                 {@code 9(9)} (zero-fill)
     * @return the 13-character edited string (9 integer + {@code '.'} + 2 fraction + trailing sign)
     */
    private static String editTrailingSign(BigDecimal value, boolean suppress) {
        BigDecimal scaled = ((value == null) ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
        char sign = (scaled.signum() < 0) ? '-' : ' ';
        // Work on the absolute value expressed in cents so integer/fraction split stays exact.
        BigInteger cents = scaled.abs().movePointRight(2).toBigInteger();
        BigInteger intPart = cents.divide(HUNDRED).mod(BILLION); // low-order 9 digits (COBOL truncation)
        BigInteger fracPart = cents.mod(HUNDRED);

        String intStr;
        if (suppress) {
            // PIC Z(9): leading zeros suppressed; a zero integer part is entirely blank.
            String digits = (intPart.signum() == 0) ? "" : intPart.toString();
            intStr = " ".repeat(9 - digits.length()) + digits; // right-justify within 9 positions
        } else {
            // PIC 9(9): zero-filled to nine digits.
            intStr = String.format(Locale.ROOT, "%09d", intPart);
        }
        String fracStr = String.format(Locale.ROOT, "%02d", fracPart);
        return intStr + "." + fracStr + sign;
    }

    // ==========================================================================================
    // String utilities — COBOL MOVE / STRING DELIMITED-BY semantics on fixed-width fields.
    // ==========================================================================================

    /**
     * COBOL {@code STRING ... DELIMITED BY ' '} semantics: the characters up to (but excluding) the
     * first space. A field with no space is returned unchanged; a field that starts with a space
     * yields the empty string.
     *
     * @param s the source value (never {@code null}; use {@link #nz(String)} first)
     * @return the substring preceding the first space
     */
    private static String delimitBySpace(String s) {
        int i = s.indexOf(' ');
        return (i < 0) ? s : s.substring(0, i);
    }

    /**
     * COBOL {@code STRING ... DELIMITED BY '  '} semantics: the characters up to (but excluding) the
     * first run of two consecutive spaces. On a space-padded fixed-width field this trims the trailing
     * padding while preserving single internal spaces.
     *
     * @param s the source value (never {@code null})
     * @return the substring preceding the first double-space
     */
    private static String upToDoubleSpace(String s) {
        int i = s.indexOf("  ");
        return (i < 0) ? s : s.substring(0, i);
    }

    /**
     * COBOL {@code MOVE} into a fixed alphanumeric field: right-truncate a longer value to
     * {@code width}; a shorter value is returned unchanged (the writer supplies trailing padding).
     *
     * @param s     the source value (never {@code null})
     * @param width the field width
     * @return the value truncated to at most {@code width} characters
     */
    private static String fit(String s, int width) {
        return (s.length() > width) ? s.substring(0, width) : s;
    }

    /**
     * Renders an exact fixed-width alphanumeric field: right-truncate a longer value or right-pad a
     * shorter value with spaces so the result is exactly {@code width} characters. Reproduces a COBOL
     * fixed-width field whose trailing spaces are significant (e.g. values consumed via
     * {@code DELIMITED BY '*'} or embedded before further columns).
     *
     * @param s     the source value (never {@code null})
     * @param width the exact target width
     * @return a string of exactly {@code width} characters
     */
    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - s.length());
    }

    /**
     * Null-safe accessor helper: converts a {@code null} field value to the empty string so the
     * downstream fixed-width formatting behaves like a COBOL space-filled field.
     *
     * @param s the possibly-{@code null} value
     * @return {@code s}, or the empty string when {@code s} is {@code null}
     */
    private static String nz(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * HTML-escapes a data-derived field value before it is concatenated into the statement HTML
     * template, so that customer- and transaction-sourced text can never be interpreted as markup or
     * script by a browser that opens the generated {@code HTMLFILE} artifact. This hardens the
     * statement HTML browser surface against stored cross-site scripting (CWE-79) while preserving the
     * byte-exact golden-file contract, because it is applied <em>only</em> to the free-text data values
     * (name, address lines, transaction id and description) and is a strict no-op for the
     * metacharacter-free ASCII data that faithfully-decoded statement fields — and the golden fixtures —
     * contain. The disposition and its parity analysis are recorded in decision log D43 / D44.
     *
     * <p>Two transformations are applied, iterating by Unicode code point:</p>
     * <ul>
     *   <li>The five HTML-significant characters {@code & < > " '} are replaced with their entities
     *       ({@code &amp; &lt; &gt; &quot; &#39;}) — the standard output-encoding set — so an injected
     *       tag, attribute or entity renders as inert literal text rather than active markup.</li>
     *   <li>Every non-ASCII code point ({@code >= 0x80}) is replaced with a numeric character reference
     *       {@code &#nnn;}. Numeric references are charset-independent, so a Latin-1 accented character
     *       (e.g. {@code é}) renders correctly in the browser regardless of the byte-exact legacy
     *       {@code <meta charset="utf-8">} declaration — resolving the charset-mislabel rendering defect
     *       without altering the frozen meta tag or the ISO-8859-1 record encoding. A supplementary-plane
     *       code point (e.g. an emoji surrogate pair) is emitted as one reference.</li>
     * </ul>
     *
     * <p>All other characters (printable ASCII {@code 0x20}-{@code 0x7E} except the five above, and any
     * control characters — which the writer separately sanitises) pass through unchanged, so clean data
     * — including every value in the golden scenario — is returned byte-identical and the fixed-width
     * record framing is preserved (the assembled line is still truncated/padded to its exact record
     * width downstream).</p>
     *
     * @param value the raw data field value (never {@code null} at the call sites; {@code null} is
     *              tolerated and treated as the empty string)
     * @return the HTML-safe rendering of {@code value}
     */
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        // Fast path: clean ASCII data (the overwhelming majority, including the golden scenario) needs
        // no rewriting, so it is returned unchanged with no allocation — guaranteeing byte-exact parity.
        if (isHtmlClean(value)) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        int index = 0;
        final int length = value.length();
        while (index < length) {
            int cp = value.codePointAt(index);
            switch (cp) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> {
                    if (cp >= 0x80) {
                        // Charset-independent numeric character reference (fixes browser mojibake and
                        // renders code points > 0xFF without relying on the output byte encoding).
                        sb.append("&#").append(cp).append(';');
                    } else {
                        sb.appendCodePoint(cp);
                    }
                }
            }
            index += Character.charCount(cp);
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} when {@code value} contains no character that {@link #escapeHtml(String)}
     * would rewrite — i.e. none of {@code & < > " '} and no non-ASCII code unit. Used as the escape
     * fast path so clean data is returned verbatim, guaranteeing a byte-identical golden statement.
     *
     * @param value the value to inspect (never {@code null})
     * @return {@code true} if the value can be emitted verbatim; {@code false} if it needs escaping
     */
    private static boolean isHtmlClean(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x80 || c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') {
                return false;
            }
        }
        return true;
    }

    // ==========================================================================================
    // Output value object.
    // ==========================================================================================

    /**
     * The dual-format statement produced for one card: {@code textLines} are plain-text records
     * (each &le; 80 characters, matching {@code FD-STMTFILE-REC PIC X(80)})
     * and {@code htmlLines} are HTML records (each &le; 100 characters,
     * matching {@code FD-HTMLFILE-REC PIC X(100)}). The downstream writer emits {@code textLines} as
     * 80-byte records and {@code htmlLines} as 100-byte records.
     *
     * @param textLines the ordered plain-text statement records
     * @param htmlLines the ordered HTML statement records
     */
    public static record StatementDocument(List<String> textLines, List<String> htmlLines) {
    }
}
