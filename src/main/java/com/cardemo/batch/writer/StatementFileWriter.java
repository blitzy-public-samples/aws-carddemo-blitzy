/*
 * StatementFileWriter.java — Spring Batch ItemWriter for Statement Output
 *
 * Source COBOL Program: CBSTM03A.CBL (Main statement generation engine)
 * Supporting Subroutine: CBSTM03B.CBL (I/O subroutine for input files)
 *
 * Generates account statements in TWO simultaneous output formats:
 *   - Plain text (STMT-FILE): 80-char fixed-width lines (FD-STMTFILE-REC PIC X(80))
 *   - HTML (HTML-FILE): Inline-styled HTML table (FD-HTMLFILE-REC PIC X(100))
 *
 * COBOL Paragraph → Java Method Traceability:
 *   OPEN OUTPUT STMT-FILE/HTML-FILE → open(ExecutionContext)
 *   5000-CREATE-STATEMENT           → writeTextStatementHeader() + writeHtmlDocumentHeader()
 *                                     + writeHtmlNameAddressDetails()
 *   5100-WRITE-HTML-HEADER          → writeHtmlDocumentHeader()
 *   5200-WRITE-HTML-NMADBS          → writeHtmlNameAddressDetails()
 *   6000-WRITE-TRANS                → writeTextTransactionLine() + writeHtmlTransactionLine()
 *   (footer after tran loop)        → writeTextStatementFooter() + writeHtmlStatementFooter()
 *   CLOSE STMT-FILE/HTML-FILE       → close()
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.batch.writer;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.entity.Account;
import com.cardemo.entity.Customer;
import com.cardemo.entity.Transaction;
import com.cardemo.service.batch.StatementIoService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Spring Batch {@link ItemWriter} that writes generated statement data to both
 * plain-text (STMT-FILE) and HTML (HTML-FILE) output files simultaneously.
 *
 * <p>Faithfully translates the statement output writing logic from CBSTM03A.CBL.
 * Each {@link StatementData} item represents a complete account statement with
 * customer information, account details, and a list of transactions.</p>
 *
 * <p>The writer implements {@link ItemStream} for file lifecycle management:
 * {@link #open(ExecutionContext)} opens both output files,
 * {@link #close()} flushes and closes them.</p>
 *
 * <p>For data lookups during output formatting, the writer delegates to
 * {@link StatementIoService} (CALL 'CBSTM03B' parity).</p>
 *
 * @see StatementData
 * @see StatementIoService
 */
@Component
public class StatementFileWriter
        implements ItemWriter<StatementFileWriter.StatementData>, ItemStream {

    private static final Logger log = LoggerFactory.getLogger(StatementFileWriter.class);

    /** Width of plain text statement lines — FD-STMTFILE-REC PIC X(80). */
    private static final int TEXT_WIDTH = 80;

    // -----------------------------------------------------------------------
    // Injected dependencies
    // -----------------------------------------------------------------------

    /** Statement I/O service for data lookups (CALL 'CBSTM03B' parity). */
    private final StatementIoService statementIoService;

    /** Path for plain text statement output file (STMT-FILE). */
    @Value("${cardemo.batch.statement-text-path:statements.txt}")
    private String textOutputPath;

    /** Path for HTML statement output file (HTML-FILE). */
    @Value("${cardemo.batch.statement-html-path:statements.html}")
    private String htmlOutputPath;

    // -----------------------------------------------------------------------
    // Output writers (STMT-FILE and HTML-FILE file descriptors)
    // -----------------------------------------------------------------------

    /** BufferedWriter for plain text output (STMT-FILE, PIC X(80) records). */
    private BufferedWriter textWriter;

    /** BufferedWriter for HTML output (HTML-FILE, PIC X(100) records). */
    private BufferedWriter htmlWriter;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates a new StatementFileWriter with the required StatementIoService.
     *
     * @param statementIoService service for input data lookups (CALL 'CBSTM03B')
     */
    @Autowired
    public StatementFileWriter(StatementIoService statementIoService) {
        this.statementIoService = statementIoService;
    }

    // =======================================================================
    // ItemStream lifecycle — OPEN OUTPUT / CLOSE for STMT-FILE and HTML-FILE
    // =======================================================================

    /**
     * Opens both plain text and HTML output files for writing.
     * Maps to COBOL: {@code OPEN OUTPUT STMT-FILE} and {@code OPEN OUTPUT HTML-FILE}.
     *
     * @param executionContext Spring Batch execution context
     * @throws ItemStreamException if files cannot be opened (maps to COBOL ABEND)
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            textWriter = new BufferedWriter(new FileWriter(textOutputPath));
            htmlWriter = new BufferedWriter(new FileWriter(htmlOutputPath));
            log.info("Statement files opened: text={}, html={}",
                    textOutputPath, htmlOutputPath);
        } catch (IOException e) {
            log.error("ERROR OPENING STATEMENT FILES: {}", e.getMessage());
            throw new ItemStreamException("Failed to open statement files", e);
        }
    }

    /**
     * Updates the execution context for restart capability.
     * No restart state is required — files are regenerated from scratch.
     *
     * @param executionContext Spring Batch execution context
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // No restart state to persist for statement file output
    }

    /**
     * Closes both output files, flushing any buffered content.
     * Maps to COBOL: {@code CLOSE STMT-FILE} and {@code CLOSE HTML-FILE}.
     *
     * @throws ItemStreamException if files cannot be closed (maps to COBOL ABEND)
     */
    @Override
    public void close() throws ItemStreamException {
        IOException firstException = null;
        try {
            if (textWriter != null) {
                textWriter.flush();
                textWriter.close();
            }
        } catch (IOException e) {
            log.error("ERROR CLOSING TEXT STATEMENT FILE: {}", e.getMessage());
            firstException = e;
        }
        try {
            if (htmlWriter != null) {
                htmlWriter.flush();
                htmlWriter.close();
            }
        } catch (IOException e) {
            log.error("ERROR CLOSING HTML STATEMENT FILE: {}", e.getMessage());
            if (firstException != null) {
                firstException.addSuppressed(e);
            } else {
                firstException = e;
            }
        }
        if (firstException != null) {
            throw new ItemStreamException("Failed to close statement files",
                    firstException);
        }
        log.info("Statement files closed successfully");
    }

    // =======================================================================
    // ItemWriter — Main write method processing each statement chunk
    // =======================================================================

    /**
     * Writes a chunk of statement data to both plain text and HTML files.
     * Each {@link StatementData} produces one complete statement in both formats.
     *
     * @param chunk the chunk of StatementData items to write
     * @throws Exception if writing fails (maps to COBOL 9999-ABEND-PROGRAM)
     */
    @Override
    public void write(Chunk<? extends StatementData> chunk) throws Exception {
        try {
            for (StatementData data : chunk) {
                Customer customer = data.customer();
                Account account = data.account();
                List<Transaction> transactions = data.transactions() != null
                        ? data.transactions() : List.of();

                if (customer == null || account == null) {
                    throw new CardDemoException(
                            "Statement generation failed: missing customer or account data");
                }

                // Verify data linkage via StatementIoService (CALL 'CBSTM03B' parity)
                verifyStatementData(customer, account, transactions);

                BigDecimal totalAmount = BigDecimal.ZERO;

                // 5000-CREATE-STATEMENT: text header (ST-LINE0 through ST-LINE13)
                writeTextStatementHeader(customer, account);

                // 5100-WRITE-HTML-HEADER: HTML document header
                writeHtmlDocumentHeader(account);
                // 5200-WRITE-HTML-NMADBS: HTML name, address, basic details, tran headers
                writeHtmlNameAddressDetails(customer, account);

                // 6000-WRITE-TRANS: each transaction to both outputs
                for (Transaction tran : transactions) {
                    writeTextTransactionLine(tran);
                    writeHtmlTransactionLine(tran);
                    totalAmount = totalAmount.add(
                            tran.getAmount() != null ? tran.getAmount() : BigDecimal.ZERO);
                }

                // Footer with total (text + HTML)
                writeTextStatementFooter(totalAmount);
                writeHtmlStatementFooter(totalAmount);

                log.info("Statement written: acctId={}, txns={}, total={}",
                        account.getAcctId(), transactions.size(), totalAmount);
            }
            textWriter.flush();
            htmlWriter.flush();
        } catch (IOException e) {
            // Maps to COBOL 9999-ABEND-PROGRAM on unrecoverable I/O error
            log.error("ABEND — statement file write failed: {}", e.getMessage());
            throw new CardDemoException(
                    "Statement file write failed — ABEND equivalent", e);
        }
    }

    // =======================================================================
    // Data verification — CALL 'CBSTM03B' parity for input file I/O
    // =======================================================================

    /**
     * Verifies statement data integrity via StatementIoService lookups.
     * Maps to COBOL CALL 'CBSTM03B' with operations on CUSTFILE, ACCTFILE,
     * XREFFILE, and TRNXFILE datasets.
     */
    private void verifyStatementData(Customer customer, Account account,
                                     List<Transaction> transactions) {
        // CUSTFILE keyed read (DD='CUSTFILE', OPER='K')
        statementIoService.readCustomerByKey(customer.getCustId());
        // ACCTFILE keyed read (DD='ACCTFILE', OPER='K')
        statementIoService.readAccountByKey(account.getAcctId());
        // XREFFILE keyed read (DD='XREFFILE', OPER='K')
        statementIoService.readXrefByKey(account.getAcctId());
        // TRNXFILE keyed read (DD='TRNXFILE', OPER='K')
        if (!transactions.isEmpty()) {
            statementIoService.readTransactionByKey(
                    transactions.getFirst().getTranId());
        }
    }

    // =======================================================================
    // Plain text output — STMT-FILE (80-char fixed-width lines)
    // =======================================================================

    /**
     * Writes the plain text statement header lines (ST-LINE0 through ST-LINE13).
     * Maps to COBOL paragraph 5000-CREATE-STATEMENT text output portion.
     *
     * <p>Writes: START banner, customer name, address (3 lines), separator,
     * Basic Details header, account ID, balance, FICO score, separator,
     * TRANSACTION SUMMARY header, separator, column headers, separator.</p>
     */
    private void writeTextStatementHeader(Customer customer, Account account)
            throws IOException {
        // ST-LINE0: START OF STATEMENT banner
        writeLine(textWriter, "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31));

        // ST-LINE1: Customer full name (STRING DELIMITED BY ' ')
        String fullName = buildTextName(customer);
        writeLine(textWriter, padRight(fullName, 75) + " ".repeat(5));

        // ST-LINE2: Address line 1
        writeLine(textWriter, padRight(safe(customer.getAddrLine1()), 50)
                + " ".repeat(30));

        // ST-LINE3: Address line 2
        writeLine(textWriter, padRight(safe(customer.getAddrLine2()), 50)
                + " ".repeat(30));

        // ST-LINE4: Address line 3 (city/state/country/zip)
        writeLine(textWriter, padRight(buildTextAddressLine3(customer), TEXT_WIDTH));

        // ST-LINE5: Separator
        writeLine(textWriter, "-".repeat(TEXT_WIDTH));

        // ST-LINE6: "Basic Details" centered (33 + 14 + 33 = 80)
        writeLine(textWriter, " ".repeat(33) + "Basic Details " + " ".repeat(33));

        // ST-LINE5 again: Separator (written twice around Basic Details header)
        writeLine(textWriter, "-".repeat(TEXT_WIDTH));

        // ST-LINE7: Account ID (20-char label + 20-char value + 40 spaces)
        writeLine(textWriter, "Account ID         :"
                + padRight(safe(account.getAcctId()), 20) + " ".repeat(40));

        // ST-LINE8: Current Balance (PIC 9(9).99- format)
        writeLine(textWriter, "Current Balance    :"
                + formatBalance(account.getCurrBal()) + " ".repeat(47));

        // ST-LINE9: FICO Score
        String ficoStr = customer.getFicoCreditScore() != null
                ? customer.getFicoCreditScore().toString() : "";
        writeLine(textWriter, "FICO Score         :"
                + padRight(ficoStr, 20) + " ".repeat(40));

        // ST-LINE10: Separator
        writeLine(textWriter, "-".repeat(TEXT_WIDTH));

        // ST-LINE11: TRANSACTION SUMMARY centered (30 + 20 + 30 = 80)
        writeLine(textWriter, " ".repeat(30) + "TRANSACTION SUMMARY "
                + " ".repeat(30));

        // ST-LINE12: Separator
        writeLine(textWriter, "-".repeat(TEXT_WIDTH));

        // ST-LINE13: Column headers (16 + 51 + 13 = 80)
        writeLine(textWriter, "Tran ID         "
                + padRight("Tran Details", 51) + "  Tran Amount");

        // ST-LINE12 again: Separator after column headers
        writeLine(textWriter, "-".repeat(TEXT_WIDTH));
    }

    /**
     * Writes one transaction line to the plain text output.
     * Maps to COBOL paragraph 6000-WRITE-TRANS text portion (ST-LINE14).
     *
     * <p>Format: ST-TRANID(16) + ' '(1) + ST-TRANDT(49) + '$'(1) +
     * ST-TRANAMT PIC Z(9).99-(13) = 80 chars.</p>
     */
    private void writeTextTransactionLine(Transaction tran) throws IOException {
        String tranId = padRight(safe(tran.getTranId()), 16);
        String tranDesc = padRight(safe(tran.getDescription()), 49);
        String tranAmt = formatAmount(tran.getAmount());
        writeLine(textWriter, tranId + " " + tranDesc + "$" + tranAmt);
    }

    /**
     * Writes the plain text statement footer (separator, total, end banner).
     * Maps to COBOL footer logic in 4000-TRNXFILE-GET after transaction loop.
     *
     * <p>Writes ST-LINE12 (separator), ST-LINE14A (Total EXP), ST-LINE15
     * (END OF STATEMENT banner).</p>
     */
    private void writeTextStatementFooter(BigDecimal totalAmount) throws IOException {
        // ST-LINE12: Separator before total
        writeLine(textWriter, "-".repeat(TEXT_WIDTH));

        // ST-LINE14A: Total line (10 + 56 + 1 + 13 = 80)
        writeLine(textWriter, "Total EXP:" + " ".repeat(56) + "$"
                + formatAmount(totalAmount));

        // ST-LINE15: END OF STATEMENT banner
        writeLine(textWriter, "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));
    }

    // =======================================================================
    // HTML output — HTML-FILE (inline-styled HTML table)
    // =======================================================================

    /**
     * Writes the HTML document header and bank branding section.
     * Maps to COBOL paragraph 5100-WRITE-HTML-HEADER.
     *
     * <p>Writes: DOCTYPE, html/head/body, table open, account number header
     * (#1d1d96b3), bank info (#FFAF33), and opens the name/address section
     * (#f2f2f2).</p>
     */
    private void writeHtmlDocumentHeader(Account account) throws IOException {
        // HTML boilerplate (HTML-L01 through HTML-L08)
        htmlLine("<!DOCTYPE html>");
        htmlLine("<html lang=\"en\">");
        htmlLine("<head>");
        htmlLine("<meta charset=\"utf-8\">");
        htmlLine("<title>HTML Table Layout</title>");
        htmlLine("</head>");
        htmlLine("<body style=\"margin:0px;\">");
        htmlLine("<table align=\"center\" frame=\"box\" style=\"width:70%;"
                + " font:12px Segoe UI,sans-serif;\">");

        // Account number header row — #1d1d96b3 (HTML-L09 through HTML-L13)
        htmlLine("<tr>");
        htmlLine("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#1d1d96b3;\">");
        htmlLine("<h3>Statement for Account Number: "
                + safe(account.getAcctId()) + "</h3>");
        htmlLine("</td>");
        htmlLine("</tr>");

        // Bank branding row — #FFAF33 (HTML-L14 through HTML-L20)
        htmlLine("<tr>");
        htmlLine("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#FFAF33;\">");
        htmlLine("<p style=\"font-size:16px\">Bank of XYZ</p>");
        htmlLine("<p>410 Terry Ave N</p>");
        htmlLine("<p>Seattle WA 99999</p>");
        htmlLine("</td>");
        htmlLine("</tr>");

        // Open name/address section — #f2f2f2 (HTML-L21 through HTML-L22)
        htmlLine("<tr>");
        htmlLine("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#f2f2f2;\">");
    }

    /**
     * Writes the HTML customer name, address, basic details, and transaction
     * column headers. Maps to COBOL paragraph 5200-WRITE-HTML-NMADBS.
     *
     * <p>Writes: customer name and address in #f2f2f2, Basic Details header
     * in #33FFD1, account/balance/FICO in #f2f2f2, Transaction Summary in
     * #33FFD1, column headers (Tran ID/Details/Amount) in #33FF5E.</p>
     */
    private void writeHtmlNameAddressDetails(Customer customer, Account account)
            throws IOException {
        // Customer name and address (inside #f2f2f2 td opened by header method)
        String htmlName = buildHtmlName(customer);
        htmlLine("<p style=\"font-size:16px\">" + htmlName + "</p>");
        htmlLine("<p>" + safe(customer.getAddrLine1()) + "</p>");
        htmlLine("<p>" + safe(customer.getAddrLine2()) + "</p>");
        htmlLine("<p>" + buildHtmlAddressLine3(customer) + "</p>");
        htmlLine("</td>");
        htmlLine("</tr>");

        // Basic Details header — #33FFD1
        htmlLine("<tr>");
        htmlLine("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#33FFD1; text-align:center;\">");
        htmlLine("<p style=\"font-size:16px\">Basic Details</p>");
        htmlLine("</td>");
        htmlLine("</tr>");

        // Account ID, Balance, FICO — #f2f2f2
        htmlLine("<tr>");
        htmlLine("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#f2f2f2;\">");
        htmlLine("<p>Account ID         : " + safe(account.getAcctId()) + "</p>");
        htmlLine("<p>Current Balance    : "
                + htmlFormatAmount(account.getCurrBal()) + "</p>");
        String ficoHtml = customer.getFicoCreditScore() != null
                ? customer.getFicoCreditScore().toString() : "";
        htmlLine("<p>FICO Credit Score  : " + ficoHtml + "</p>");
        htmlLine("</td>");
        htmlLine("</tr>");

        // Transaction Summary header — #33FFD1
        htmlLine("<tr>");
        htmlLine("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#33FFD1; text-align:center;\">");
        htmlLine("<p style=\"font-size:16px\">Transaction Summary</p>");
        htmlLine("</td>");
        htmlLine("</tr>");

        // Column headers — #33FF5E
        htmlLine("<tr>");
        htmlLine("<td style=\"width:25%; padding:0px 5px;"
                + " background-color:#33FF5E; text-align:left;\">");
        htmlLine("<p style=\"font-size:16px\">Tran ID</p></td>");
        htmlLine("<td style=\"width:55%; padding:0px 5px;"
                + " background-color:#33FF5E; text-align:left;\">");
        htmlLine("<p style=\"font-size:16px\">Tran Details</p></td>");
        htmlLine("<td style=\"width:20%; padding:0px 5px;"
                + " background-color:#33FF5E; text-align:right;\">");
        htmlLine("<p style=\"font-size:16px\">Amount</p></td>");
        htmlLine("</tr>");
    }

    /**
     * Writes one transaction row to the HTML output.
     * Maps to COBOL paragraph 6000-WRITE-TRANS HTML portion.
     *
     * <p>Each transaction row uses #f2f2f2 background with three cells:
     * Tran ID (25%, left), Tran Details (55%, left), Amount (20%, right).</p>
     */
    private void writeHtmlTransactionLine(Transaction tran) throws IOException {
        htmlLine("<tr>");
        // Tran ID cell — #f2f2f2 (HTML-L57/L58)
        htmlLine("<td style=\"width:25%; padding:0px 5px;"
                + " background-color:#f2f2f2; text-align:left;\">");
        htmlLine("<p>" + safe(tran.getTranId()) + "</p></td>");
        // Tran Details cell — #f2f2f2 (HTML-L59/L60)
        htmlLine("<td style=\"width:55%; padding:0px 5px;"
                + " background-color:#f2f2f2; text-align:left;\">");
        htmlLine("<p>" + safe(tran.getDescription()) + "</p></td>");
        // Amount cell — #f2f2f2 (HTML-L61/L62)
        htmlLine("<td style=\"width:20%; padding:0px 5px;"
                + " background-color:#f2f2f2; text-align:right;\">");
        htmlLine("<p>$" + htmlFormatAmount(tran.getAmount()) + "</p></td>");
        htmlLine("</tr>");
    }

    /**
     * Writes the HTML statement footer: total row, end-of-statement row,
     * and closing table/body/html tags.
     */
    private void writeHtmlStatementFooter(BigDecimal totalAmount) throws IOException {
        // Total row — #f2f2f2
        htmlLine("<tr>");
        htmlLine("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#f2f2f2;\">");
        htmlLine("<p>Total EXP: $" + htmlFormatAmount(totalAmount) + "</p>");
        htmlLine("</td>");
        htmlLine("</tr>");

        // End of Statement row — #1d1d96b3
        htmlLine("<tr>");
        htmlLine("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#1d1d96b3;\">");
        htmlLine("<h3>End of Statement</h3>");
        htmlLine("</td>");
        htmlLine("</tr>");

        // Close table, body, html
        htmlLine("</table>");
        htmlLine("</body>");
        htmlLine("</html>");
    }

    // =======================================================================
    // Name and address building — COBOL STRING DELIMITED BY translation
    // =======================================================================

    /**
     * Builds the customer full name for plain text output.
     * Translates COBOL: {@code STRING CUST-FIRST-NAME DELIMITED BY ' '
     * ' ' DELIMITED BY SIZE CUST-MIDDLE-NAME DELIMITED BY ' '
     * ' ' DELIMITED BY SIZE CUST-LAST-NAME DELIMITED BY ' '
     * INTO ST-NAME}.
     *
     * <p>DELIMITED BY ' ' (single space) stops at the first space character,
     * effectively extracting only the first word of each name component.</p>
     */
    private String buildTextName(Customer customer) {
        String first = delimiterTrim(safe(customer.getFirstName()), " ");
        String middle = delimiterTrim(safe(customer.getMiddleName()), " ");
        String last = delimiterTrim(safe(customer.getLastName()), " ");
        return joinNonEmpty(first, middle, last);
    }

    /**
     * Builds the customer full name for HTML output.
     * Translates COBOL STRING with {@code DELIMITED BY '  '} (double space),
     * which preserves internal single spaces in name components.
     */
    private String buildHtmlName(Customer customer) {
        String first = delimiterTrim(safe(customer.getFirstName()), "  ");
        String middle = delimiterTrim(safe(customer.getMiddleName()), "  ");
        String last = delimiterTrim(safe(customer.getLastName()), "  ");
        return joinNonEmpty(first, middle, last);
    }

    /**
     * Builds address line 3 (city/state/country/zip) for plain text output.
     * Translates COBOL STRING with {@code DELIMITED BY ' '} (single space).
     */
    private String buildTextAddressLine3(Customer customer) {
        String addr3 = delimiterTrim(safe(customer.getAddrLine3()), " ");
        String state = delimiterTrim(safe(customer.getAddrStateCode()), " ");
        String country = delimiterTrim(safe(customer.getAddrCountryCode()), " ");
        String zip = delimiterTrim(safe(customer.getAddrZip()), " ");
        return joinNonEmpty(addr3, state, country, zip);
    }

    /**
     * Builds address line 3 (city/state/country/zip) for HTML output.
     * Translates COBOL STRING with {@code DELIMITED BY '  '} (double space).
     */
    private String buildHtmlAddressLine3(Customer customer) {
        String addr3 = delimiterTrim(safe(customer.getAddrLine3()), "  ");
        String state = delimiterTrim(safe(customer.getAddrStateCode()), "  ");
        String country = delimiterTrim(safe(customer.getAddrCountryCode()), "  ");
        String zip = delimiterTrim(safe(customer.getAddrZip()), "  ");
        return joinNonEmpty(addr3, state, country, zip);
    }

    // =======================================================================
    // Amount formatting — COBOL PIC editing translation
    // =======================================================================

    /**
     * Formats a BigDecimal amount as COBOL {@code PIC Z(9).99-} (zero-suppressed,
     * trailing sign). Returns exactly 13 characters: 9-digit zero-suppressed
     * integer, decimal point, 2 decimal digits, trailing minus (or space).
     *
     * <p>Examples: 12345.67 → "    12345.67 ", -99.50 → "       99.50-",
     * 0.00 → "         .00 ".</p>
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        boolean negative = amount.signum() < 0;
        BigDecimal abs = amount.abs().setScale(2, RoundingMode.HALF_UP);
        String[] parts = abs.toPlainString().split("\\.");
        String intPart = parts[0];
        String decPart = parts.length > 1 ? parts[1] : "00";
        if (decPart.length() < 2) {
            decPart = decPart + "0";
        }

        // PIC Z(9): 9 positions, zero-suppressed (leading zeros → spaces)
        String intStr;
        if ("0".equals(intPart)) {
            intStr = " ".repeat(9);
        } else {
            intStr = String.format("%9s", intPart);
        }
        return intStr + "." + decPart + (negative ? "-" : " ");
    }

    /**
     * Formats a BigDecimal as COBOL {@code PIC 9(9).99-} (leading zeros shown,
     * trailing sign). Returns exactly 13 characters: 9-digit zero-filled
     * integer, decimal point, 2 decimal digits, trailing minus (or space).
     *
     * <p>Used for the Current Balance field (ST-LINE8).</p>
     */
    private String formatBalance(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        boolean negative = amount.signum() < 0;
        BigDecimal abs = amount.abs().setScale(2, RoundingMode.HALF_UP);
        String[] parts = abs.toPlainString().split("\\.");
        String intPart = parts[0];
        String decPart = parts.length > 1 ? parts[1] : "00";
        if (decPart.length() < 2) {
            decPart = decPart + "0";
        }

        // PIC 9(9): 9 digits with leading zeros
        String intStr = String.format("%09d", Long.parseLong(intPart));
        return intStr + "." + decPart + (negative ? "-" : " ");
    }

    /**
     * Formats a BigDecimal for HTML display (plain decimal, no fixed-width).
     * Returns the amount with exactly 2 decimal places.
     */
    private String htmlFormatAmount(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // =======================================================================
    // Utility methods
    // =======================================================================

    /**
     * Writes a line to the text writer and appends a newline.
     * The line is NOT padded — callers must ensure correct width.
     */
    private void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
    }

    /** Writes a line to the HTML writer and appends a newline. */
    private void htmlLine(String line) throws IOException {
        htmlWriter.write(line);
        htmlWriter.newLine();
    }

    /**
     * Pads or truncates a string to exactly the specified width.
     * Right-pads with spaces if shorter, truncates if longer.
     */
    private String padRight(String value, int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /** Returns the value if non-null, or empty string if null. */
    private String safe(String value) {
        return value != null ? value : "";
    }

    /**
     * Implements COBOL {@code DELIMITED BY} semantics. Returns the substring
     * of {@code value} before the first occurrence of {@code delimiter}.
     * If the delimiter is not found, returns the full value.
     */
    private String delimiterTrim(String value, String delimiter) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int idx = value.indexOf(delimiter);
        if (idx >= 0) {
            return value.substring(0, idx);
        }
        return value;
    }

    /**
     * Joins non-empty strings with single-space separators.
     * Mirrors the COBOL STRING ... ' ' DELIMITED BY SIZE pattern.
     */
    private String joinNonEmpty(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    // =======================================================================
    // StatementData — Aggregated data record for one account statement
    // =======================================================================

    /**
     * Aggregated data for a single account statement, consumed by the writer
     * to generate both plain text and HTML output.
     *
     * <p>This record is assembled by the {@code StatementProcessor} in the
     * Spring Batch pipeline. It aggregates the customer, account, and
     * transaction data that was originally spread across multiple VSAM
     * datasets (CUSTDATA, ACCTDATA, TRANSACT) and loaded into the COBOL
     * working-storage table WS-TRNX-TABLE (51 cards × 10 transactions).</p>
     *
     * @param customer     the customer whose statement is being generated
     * @param account      the account for which the statement is generated
     * @param transactions the list of transactions to include in the statement
     */
    public record StatementData(
            Customer customer,
            Account account,
            List<Transaction> transactions
    ) { }
}
