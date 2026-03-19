package com.cardemo.service.batch;

/*
 * StatementEngineService.java — Faithful translation of CBSTM03A.CBL
 *
 * COBOL Source: app/cbl/CBSTM03A.CBL (Statement generation main engine)
 * COBOL Subroutine: CBSTM03B.CBL → {@link StatementIoService}
 *
 * COBOL Paragraph → Java Method Traceability:
 * ──────────────────────────────────────────────────────────────────────
 *   PROCEDURE DIVISION entry   → generateStatements(String outputDir)
 *   8100-8400 file opens       → openAllFiles()
 *   8500-READTRNX-READ         → loadTransactionTable()
 *   1000-MAINLINE              → [loop inside generateStatements]
 *   1000-XREFFILE-GET-NEXT     → getNextXref()               [private]
 *   2000-CUSTFILE-GET           → getCustomer(String)         [private]
 *   3000-ACCTFILE-GET           → getAccount(String)          [private]
 *   5000-CREATE-STATEMENT       → createStatement(Customer, Account, Writer, Writer)
 *   5100-WRITE-HTML-HEADER      → writeHtmlHeader(Writer)
 *   5200-WRITE-HTML-NMADBS      → writeHtmlNameAddress(Customer, Account, Writer)
 *   4000-TRNXFILE-GET           → writeTransactions(CardXref, Map, Writer, Writer)
 *   6000-WRITE-TRANS            → writeTransactionLine(Transaction, Writer, Writer)
 *   9100-9400 file closes       → closeAllFiles()
 *   9999-ABEND-PROGRAM          → throws CardDemoException
 * ──────────────────────────────────────────────────────────────────────
 */

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Statement generation engine — faithful translation of CBSTM03A.CBL.
 *
 * <p>Orchestrates the complete statement generation process: opens data files
 * via {@link StatementIoService} (← CALL 'CBSTM03B'), loads all transactions
 * into an in-memory table (← WS-TRNX-TABLE), iterates cross-reference records
 * (← STARTBR/READNEXT XREFFILE), resolves customer and account data via keyed
 * reads, and produces both plain-text (80-column PIC X(80)) and HTML statement
 * output files.</p>
 *
 * <h3>COBOL Working-Storage Equivalents</h3>
 * <ul>
 *   <li>WS-TRNX-TABLE (51 cards × 10 trans) → {@code Map<String, List<Transaction>>}</li>
 *   <li>WS-TOTAL-AMT PIC S9(9)V99 → {@link BigDecimal} with scale 2</li>
 *   <li>ST-LINE0 through ST-LINE15 (PIC X(80)) → formatted string constants</li>
 *   <li>HTML-L01 through HTML-L80 → inline HTML write statements</li>
 * </ul>
 *
 * <p><strong>Thread Safety Note:</strong> This service mirrors the single-threaded
 * COBOL batch processing model. The {@code totalAmount} instance field is reset
 * per-statement within {@link #writeTransactions}. Concurrent invocations of
 * {@link #generateStatements} are not supported.</p>
 *
 * @see StatementIoService
 */
@Service
public class StatementEngineService {

    private static final Logger logger = LoggerFactory.getLogger(StatementEngineService.class);

    // ─── Text Statement Format Constants (← CBSTM03A WORKING-STORAGE) ─────────

    /** Fixed line width for plain-text output (← PIC X(80) record length). */
    private static final int LINE_WIDTH = 80;

    /** ST-LINE0: Start separator — 31 stars + "START OF STATEMENT" + 31 stars = 80. */
    private static final String START_SEPARATOR =
            "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);

    /** ST-LINE15: End separator — 32 stars + "END OF STATEMENT" + 32 stars = 80. */
    private static final String END_SEPARATOR =
            "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    /** ST-LINE5 / ST-LINE10 / ST-LINE12: Dash separator line (80 dashes). */
    private static final String DASH_LINE = "-".repeat(LINE_WIDTH);

    /** ST-LINE6: "Basic Details" centered — 33 spaces + 14 chars + 33 spaces = 80. */
    private static final String BASIC_DETAILS_LINE =
            " ".repeat(33) + "Basic Details " + " ".repeat(33);

    /** ST-LINE11: "TRANSACTION SUMMARY" centered — 30 spaces + 20 chars + 30 spaces = 80. */
    private static final String TRAN_SUMMARY_LINE =
            " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30);

    /** ST-LINE13: Column headers — "Tran ID"(16) + "Tran Details"(51) + "  Tran Amount"(13) = 80. */
    private static final String COLUMN_HEADER =
            String.format("%-16s%-51s%13s", "Tran ID", "Tran Details", "Tran Amount");

    /** Output text file name (← STMT-FILE DD). */
    private static final String STMT_FILE_NAME = "statements.txt";

    /** Output HTML file name (← HTML-FILE DD). */
    private static final String HTML_FILE_NAME = "statements.html";

    // ─── HTML Color Constants (← CBSTM03A inline CSS hex values) ──────────────

    /** Header/footer bar color (← background-color #1d1d96b3). */
    private static final String COLOR_HEADER_FOOTER = "#1d1d96b3";

    /** Bank info row color (← background-color #FFAF33). */
    private static final String COLOR_BANK_INFO = "#FFAF33";

    /** Data/content row color (← background-color #f2f2f2). */
    private static final String COLOR_DATA_ROW = "#f2f2f2";

    /** Section header color (← background-color #33FFD1). */
    private static final String COLOR_SECTION_HDR = "#33FFD1";

    /** Column header color (← background-color #33FF5E). */
    private static final String COLOR_COL_HDR = "#33FF5E";

    /** Maximum distinct cards in transaction table (← WS-MAX-CARDS VALUE 51). */
    private static final int MAX_CARDS = 51;

    /** Maximum transactions per card (← WS-MAX-TRNX-PER-CARD VALUE 10). */
    private static final int MAX_TRNX_PER_CARD = 10;

    // ─── Injected Dependencies (← COBOL CALL 'CBSTM03B' + VSAM file access) ──

    private final StatementIoService statementIoService;
    private final TransactionRepository transactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;

    // ─── Working Storage (← WS-TOTAL-AMT PIC S9(9)V99 COMP-3) ────────────────

    /** Per-statement transaction total accumulator (← WS-TOTAL-AMT). */
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * Constructs the statement engine with all required dependencies.
     *
     * @param statementIoService    I/O subroutine service (← CBSTM03B.CBL)
     * @param transactionRepository transaction data access (← TRANSACT VSAM)
     * @param cardXrefRepository    card cross-reference data access (← CARDXREF VSAM)
     * @param customerRepository    customer data access (← CUSTDATA VSAM)
     * @param accountRepository     account data access (← ACCTDATA VSAM)
     */
    @Autowired
    public StatementEngineService(StatementIoService statementIoService,
                                  TransactionRepository transactionRepository,
                                  CardXrefRepository cardXrefRepository,
                                  CustomerRepository customerRepository,
                                  AccountRepository accountRepository) {
        this.statementIoService = statementIoService;
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  generateStatements(String outputDir) — Main Entry Point
    //  ← PROCEDURE DIVISION entry / 0000-START / 1000-MAINLINE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generates statements for all card-holder accounts.
     *
     * <p>Orchestrates the complete CBSTM03A statement generation flow:</p>
     * <ol>
     *   <li>Open dataset files via {@link StatementIoService}
     *       (← 8100–8400 file opens via CALL 'CBSTM03B')</li>
     *   <li>Load all transactions into an in-memory table grouped by card number
     *       (← 8500-READTRNX-READ → WS-TRNX-TABLE)</li>
     *   <li>Open output files (STMT-FILE and HTML-FILE)</li>
     *   <li>Iterate cross-references and generate per-account statements
     *       (← 1000-MAINLINE loop)</li>
     *   <li>Close all dataset files (← 9100–9400 file closes)</li>
     * </ol>
     *
     * @param outputDir directory path for statement output files
     * @throws CardDemoException if a fatal error occurs (← 9999-ABEND-PROGRAM)
     */
    public void generateStatements(String outputDir) {
        logger.info("Statement generation started — output directory: {}", outputDir);

        // Ensure output directory exists (← JCL DD allocation)
        File outDir = new File(outputDir);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new CardDemoException(
                    "Statement generation abend — cannot create output directory: " + outputDir);
        }

        // ← 8100–8400: Open all dataset files via CBSTM03B
        openAllFiles();

        // ← 8500-READTRNX-READ: Load all transactions into WS-TRNX-TABLE
        Map<String, List<Transaction>> trnxTable = loadTransactionTable();

        // Cross-validate data availability via direct repository access
        List<CardXref> allXrefs = cardXrefRepository.findAll();
        List<Transaction> allTransactions = transactionRepository.findAll();
        logger.info("Data available: {} cross-references, {} transactions",
                allXrefs.size(), allTransactions.size());

        // Open output files (← OPEN OUTPUT STMT-FILE, HTML-FILE)
        File stmtFile = new File(outDir, STMT_FILE_NAME);
        File htmlFile = new File(outDir, HTML_FILE_NAME);

        try (Writer stmtWriter = new BufferedWriter(new FileWriter(stmtFile));
             Writer htmlWriter = new BufferedWriter(new FileWriter(htmlFile))) {

            int statementsGenerated = 0;

            // ← 1000-MAINLINE: PERFORM UNTIL END-OF-FILE = 'Y'
            Optional<CardXref> xrefOpt = getNextXref();

            while (xrefOpt.isPresent()) {
                CardXref xref = xrefOpt.get();

                // ← 2000-CUSTFILE-GET: Keyed read by XREF-CUST-ID
                Customer customer = getCustomer(xref.getCustId());

                // ← 3000-ACCTFILE-GET: Keyed read by XREF-ACCT-ID
                Account account = getAccount(xref.getAccountId());

                // ← 5000-CREATE-STATEMENT: Write header (text + HTML)
                createStatement(customer, account, stmtWriter, htmlWriter);

                // ← MOVE ZERO TO WS-TOTAL-AMT, PERFORM 4000-TRNXFILE-GET
                writeTransactions(xref, trnxTable, stmtWriter, htmlWriter);

                statementsGenerated++;

                // ← PERFORM 1000-XREFFILE-GET-NEXT (loop advance)
                xrefOpt = getNextXref();
            }

            // ← 9100–9400: Close all dataset files
            closeAllFiles();

            logger.info("Statement generation complete — {} statements generated",
                    statementsGenerated);

        } catch (IOException e) {
            // ← 9999-ABEND-PROGRAM
            logger.error("Fatal I/O error during statement generation", e);
            throw new CardDemoException("Statement generation abend — I/O error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  openAllFiles() — Open All Dataset Files
    //  ← 8100-TRNXFILE-OPEN through 8400-ACCTFILE-OPEN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Opens all dataset files via {@link StatementIoService}.
     *
     * <p>← 8100-TRNXFILE-OPEN, 8200-XREFFILE-OPEN, 8300-CUSTFILE-OPEN,
     * 8400-ACCTFILE-OPEN: Each calls CBSTM03B with M03B-OPEN operation.</p>
     *
     * @throws CardDemoException if any file open fails (← 9999-ABEND-PROGRAM)
     */
    public void openAllFiles() {
        logger.debug("Opening all dataset files (← 8100–8400)");
        try {
            statementIoService.openTransactionFile();
            statementIoService.openXrefFile();
            statementIoService.openCustomerFile();
            statementIoService.openAccountFile();
            logger.info("All dataset files opened successfully");
        } catch (FileStatusException e) {
            logger.error("Failed to open dataset — file status: {}", e.getFileStatusCode());
            throw new CardDemoException(
                    "Statement generation abend — file open error, status: "
                            + e.getFileStatusCode(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  closeAllFiles() — Close All Dataset Files
    //  ← 9100-TRNXFILE-CLOSE through 9400-ACCTFILE-CLOSE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Closes all dataset files via {@link StatementIoService}.
     *
     * <p>← 9100-TRNXFILE-CLOSE, 9200-XREFFILE-CLOSE, 9300-CUSTFILE-CLOSE,
     * 9400-ACCTFILE-CLOSE: Each calls CBSTM03B with M03B-CLOSE operation.</p>
     */
    public void closeAllFiles() {
        logger.debug("Closing all dataset files (← 9100–9400)");
        statementIoService.closeTransactionFile();
        statementIoService.closeXrefFile();
        statementIoService.closeCustomerFile();
        statementIoService.closeAccountFile();
        logger.info("All dataset files closed");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  loadTransactionTable() — Build In-Memory Transaction Table
    //  ← 8500-READTRNX-READ
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Loads all transactions into an in-memory table grouped by card number.
     *
     * <p>← 8500-READTRNX-READ: Reads the entire TRANSACT dataset sequentially
     * and populates WS-TRNX-TABLE indexed by card number. The COBOL table has
     * a capacity of {@value #MAX_CARDS} × {@value #MAX_TRNX_PER_CARD} entries;
     * this Java implementation uses a {@link LinkedHashMap} preserving insertion
     * order but logs a warning if COBOL limits are exceeded.</p>
     *
     * @return map of card number → list of transactions for that card
     */
    public Map<String, List<Transaction>> loadTransactionTable() {
        logger.debug("Loading transaction table (← 8500-READTRNX-READ)");
        Map<String, List<Transaction>> table = new LinkedHashMap<>();
        int totalRecords = 0;

        // Sequential read via StatementIoService (← PERFORM UNTIL WS-M03B-RC = '10')
        Optional<Transaction> opt = statementIoService.readNextTransaction();
        while (opt.isPresent()) {
            Transaction tran = opt.get();
            String cardNum = tran.getCardNum();
            if (cardNum != null && !cardNum.isBlank()) {
                table.computeIfAbsent(cardNum, k -> new ArrayList<>()).add(tran);
                totalRecords++;
            }
            opt = statementIoService.readNextTransaction();
        }

        // COBOL parity warnings — WS-TRNX-TABLE capacity is 51 × 10
        if (table.size() > MAX_CARDS) {
            logger.warn("Transaction table exceeds COBOL WS-MAX-CARDS limit: {} > {}",
                    table.size(), MAX_CARDS);
        }
        for (Map.Entry<String, List<Transaction>> entry : table.entrySet()) {
            if (entry.getValue().size() > MAX_TRNX_PER_CARD) {
                logger.warn("Card {} exceeds COBOL limit: {} transactions > {}",
                        entry.getKey(), entry.getValue().size(), MAX_TRNX_PER_CARD);
            }
        }

        logger.info("Transaction table loaded: {} cards, {} records", table.size(), totalRecords);
        return table;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  createStatement(Customer, Account, Writer, Writer)
    //  ← 5000-CREATE-STATEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes the statement header section for a single card-holder account.
     *
     * <p>← 5000-CREATE-STATEMENT: Formats customer name (STRING CUST-FIRST-NAME
     * DELIMITED BY ' ' ... INTO ST-NAME), address lines, account ID, current
     * balance (PIC 9(9).99-), and FICO score into both plain-text (ST-LINE0
     * through ST-LINE13 with dashes) and HTML output (via
     * {@link #writeHtmlHeader} and {@link #writeHtmlNameAddress}).</p>
     *
     * <p>Exact COBOL write order preserved: ST-LINE0 → HTML header → build
     * name/address/account fields → HTML name/address → ST-LINE1 through
     * ST-LINE12 with column headers.</p>
     *
     * @param customer   customer record (← CUSTFILE keyed read)
     * @param account    account record (← ACCTFILE keyed read)
     * @param stmtWriter plain-text output writer (← STMT-FILE)
     * @param htmlWriter HTML output writer (← HTML-FILE)
     * @throws IOException if a write operation fails
     */
    public void createStatement(Customer customer, Account account,
                                Writer stmtWriter, Writer htmlWriter) throws IOException {

        // ── ST-LINE0: Start separator (← WRITE FD-STMTFILE-REC FROM ST-LINE0) ──
        writeLine(stmtWriter, START_SEPARATOR);

        // ← PERFORM 5100-WRITE-HTML-HEADER THRU 5100-EXIT
        writeHtmlHeader(htmlWriter);

        // Account heading row (← L10, L11 from 5100 — uses ACCT-ID)
        String acctId = safeStr(account.getAcctId()).trim();
        htmlWriter.write("<tr>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:" + COLOR_HEADER_FOOTER + ";\">\n");
        htmlWriter.write("<h3>Statement for Account Number: "
                + escapeHtml(acctId) + "</h3>\n");
        htmlWriter.write("</td>\n");
        htmlWriter.write("</tr>\n");

        // Bank info row (← L15–L18 from 5100)
        htmlWriter.write("<tr>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:" + COLOR_BANK_INFO + ";\">\n");
        htmlWriter.write("<p style=\"font-size:16px\">Bank of XYZ</p>\n");
        htmlWriter.write("<p>410 Terry Ave N</p>\n");
        htmlWriter.write("<p>Seattle WA 99999</p>\n");
        htmlWriter.write("</td>\n");
        htmlWriter.write("</tr>\n");

        // Start name section (← L22 from 5100)
        htmlWriter.write("<tr>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:" + COLOR_DATA_ROW + ";\">\n");

        // ── Build customer name (← STRING ... INTO ST-NAME) ──
        String fullName = buildCustomerName(customer);

        // ── Build city/state/country/zip (← STRING ... INTO ST-ADD3) ──
        String cityStateZip = buildCityStateZip(customer);

        // ── Format account fields ──
        String balanceStr = formatBalance9V99(account.getCurrBal());
        Integer fico = customer.getFicoCreditScore();
        String ficoStr = fico != null ? String.valueOf(fico) : "0";

        // ← PERFORM 5200-WRITE-HTML-NMADBS THRU 5200-EXIT
        writeHtmlNameAddress(customer, account, htmlWriter);

        // ← 5000-CREATE-STATEMENT text output: ST-LINE1 through ST-LINE13
        writeTextStatementHeader(customer, stmtWriter, fullName, cityStateZip,
                acctId, balanceStr, ficoStr);
    }

    /**
     * Writes the fixed-width plain-text statement header lines (ST-LINE1 through
     * ST-LINE13) to the text output file.
     *
     * <p>Extracted from {@link #createStatement} to separate text formatting from
     * HTML formatting. Each line is padded/aligned to exactly {@value #LINE_WIDTH}
     * characters, matching the COBOL {@code FD-STMTFILE-REC PIC X(80)} output.</p>
     *
     * <p>← CBSTM03A WORKING-STORAGE: ST-LINE1 through ST-LINE13 definitions.</p>
     *
     * @param customer     customer record for name/address fields
     * @param stmtWriter   plain-text output writer (← STMT-FILE)
     * @param fullName     pre-built customer full name (← STRING ... INTO ST-NAME)
     * @param cityStateZip pre-built city/state/zip string (← STRING ... INTO ST-ADD3)
     * @param acctId       trimmed account ID string
     * @param balanceStr   formatted current balance (← PIC 9(9).99-)
     * @param ficoStr      FICO credit score string
     * @throws IOException if a write operation fails
     */
    private void writeTextStatementHeader(Customer customer, Writer stmtWriter,
            String fullName, String cityStateZip, String acctId,
            String balanceStr, String ficoStr) throws IOException {

        // ST-LINE1: Customer name (PIC X(75) + FILLER X(5) = 80)
        writeLine(stmtWriter, padRight(fullName, 75) + "     ");

        // ST-LINE2: Address line 1 (PIC X(50) + FILLER X(30) = 80)
        writeLine(stmtWriter, padRight(safeStr(customer.getAddrLine1()).trim(), 50)
                + " ".repeat(30));

        // ST-LINE3: Address line 2 (PIC X(50) + FILLER X(30) = 80)
        writeLine(stmtWriter, padRight(safeStr(customer.getAddrLine2()).trim(), 50)
                + " ".repeat(30));

        // ST-LINE4: City/State/Country/Zip (PIC X(80) = 80)
        writeLine(stmtWriter, padRight(cityStateZip, LINE_WIDTH));

        // ST-LINE5: Dashes
        writeLine(stmtWriter, DASH_LINE);

        // ST-LINE6: "Basic Details" centered
        writeLine(stmtWriter, BASIC_DETAILS_LINE);

        // ST-LINE5 again: Dashes (COBOL writes ST-LINE5 twice around Basic Details)
        writeLine(stmtWriter, DASH_LINE);

        // ST-LINE7: Account ID (← "Account ID         :" PIC X(20) + ST-ACCT-ID X(20) + X(40))
        writeLine(stmtWriter, "Account ID         :" + padRight(acctId, 20) + " ".repeat(40));

        // ST-LINE8: Current Balance (← PIC 9(9).99- = 13 chars + FILLER X(7) + X(40))
        writeLine(stmtWriter, "Current Balance    :" + balanceStr + " ".repeat(47));

        // ST-LINE9: FICO Score (← "FICO Score         :" X(20) + ST-FICO-SCORE X(20) + X(40))
        writeLine(stmtWriter, "FICO Score         :" + padRight(ficoStr, 20) + " ".repeat(40));

        // ST-LINE10: Dashes
        writeLine(stmtWriter, DASH_LINE);

        // ST-LINE11: "TRANSACTION SUMMARY" centered
        writeLine(stmtWriter, TRAN_SUMMARY_LINE);

        // ST-LINE12: Dashes
        writeLine(stmtWriter, DASH_LINE);

        // ST-LINE13: Column headers
        writeLine(stmtWriter, COLUMN_HEADER);

        // ST-LINE12 again: Dashes under column headers
        writeLine(stmtWriter, DASH_LINE);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  writeHtmlHeader(Writer) — HTML Document Header
    //  ← 5100-WRITE-HTML-HEADER (L01–L08: Document structure)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes the HTML document structure and table opening tag.
     *
     * <p>← 5100-WRITE-HTML-HEADER (L01–L08): Outputs the DOCTYPE, html, head,
     * meta charset, title, head close, body, and opening table element. The
     * account-specific heading row (L10/L11) and bank info (L15–L18) are written
     * by {@link #createStatement} since they require account data not available
     * in this method's parameter list.</p>
     *
     * @param htmlWriter HTML output writer (← HTML-FILE)
     * @throws IOException if a write operation fails
     */
    public void writeHtmlHeader(Writer htmlWriter) throws IOException {
        // L01: <!DOCTYPE html>
        htmlWriter.write("<!DOCTYPE html>\n");
        // L02: <html lang="en">
        htmlWriter.write("<html lang=\"en\">\n");
        // L03: <head>
        htmlWriter.write("<head>\n");
        // L04: <meta charset="utf-8">
        htmlWriter.write("<meta charset=\"utf-8\">\n");
        // L05: <title>HTML Table Layout</title>
        htmlWriter.write("<title>HTML Table Layout</title>\n");
        // L06: </head>
        htmlWriter.write("</head>\n");
        // L07: <body style="margin:0px;">
        htmlWriter.write("<body style=\"margin:0px;\">\n");
        // L08: <table ...>
        htmlWriter.write("<table align=\"center\" frame=\"box\" "
                + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">\n");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  writeHtmlNameAddress(Customer, Account, Writer)
    //  ← 5200-WRITE-HTML-NMADBS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes the HTML customer name, address, account details, and transaction
     * column headers.
     *
     * <p>← 5200-WRITE-HTML-NMADBS: Outputs customer name (16px font), address
     * lines, closes the name section, then writes "Basic Details" header
     * (← #33FFD1), account info rows (Account ID, Current Balance, FICO Score
     * on #f2f2f2 background), "Transaction Summary" header, and column headers
     * (Tran ID, Tran Details, Amount on #33FF5E background).</p>
     *
     * @param customer   customer record
     * @param account    account record
     * @param htmlWriter HTML output writer
     * @throws IOException if a write operation fails
     */
    public void writeHtmlNameAddress(Customer customer, Account account,
                                     Writer htmlWriter) throws IOException {

        String fullName = buildCustomerName(customer);
        String addr1 = safeStr(customer.getAddrLine1()).trim();
        String addr2 = safeStr(customer.getAddrLine2()).trim();
        String cityStateZip = buildCityStateZip(customer);
        String acctId = safeStr(account.getAcctId()).trim();
        BigDecimal balance = account.getCurrBal();
        String balStr = balance != null
                ? balance.setScale(2, RoundingMode.HALF_UP).toPlainString() : "0.00";
        Integer fico = customer.getFicoCreditScore();
        String ficoStr = fico != null ? String.valueOf(fico) : "0";

        // Customer name (← <p style="font-size:16px">{name}  </p>)
        htmlWriter.write("<p style=\"font-size:16px\">"
                + escapeHtml(fullName) + "  </p>\n");

        // Address lines (← <p>{addr}  </p>)
        htmlWriter.write("<p>" + escapeHtml(addr1) + "  </p>\n");
        htmlWriter.write("<p>" + escapeHtml(addr2) + "  </p>\n");
        htmlWriter.write("<p>" + escapeHtml(cityStateZip) + "  </p>\n");

        // Close name section (← </td></tr>)
        htmlWriter.write("</td>\n");
        htmlWriter.write("</tr>\n");

        // ── "Basic Details" header row (← L30-42 with #33FFD1) ──
        htmlWriter.write("<tr>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:" + COLOR_SECTION_HDR + "; text-align:center;\">\n");
        htmlWriter.write("<p style=\"font-size:16px\">Basic Details</p>\n");
        htmlWriter.write("</td>\n");
        htmlWriter.write("</tr>\n");

        // ── Account details section (← L22-35 background #f2f2f2) ──
        htmlWriter.write("<tr>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:" + COLOR_DATA_ROW + ";\">\n");
        htmlWriter.write("<p>Account ID         : " + escapeHtml(acctId) + "</p>\n");
        htmlWriter.write("<p>Current Balance    : " + escapeHtml(balStr) + "</p>\n");
        htmlWriter.write("<p>FICO Score         : " + escapeHtml(ficoStr) + "</p>\n");
        htmlWriter.write("</td>\n");
        htmlWriter.write("</tr>\n");

        // ── "Transaction Summary" header row (← L30-42 with #33FFD1) ──
        htmlWriter.write("<tr>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:" + COLOR_SECTION_HDR + "; text-align:center;\">\n");
        htmlWriter.write("<p style=\"font-size:16px\">Transaction Summary</p>\n");
        htmlWriter.write("</td>\n");
        htmlWriter.write("</tr>\n");

        // ── Column headers (← L47–L54 with #33FF5E) ──
        htmlWriter.write("<tr>\n");
        // Tran ID column (← L47-L48)
        htmlWriter.write("<td style=\"width:25%; padding:0px 5px; "
                + "background-color:" + COLOR_COL_HDR + "; text-align:left;\">\n");
        htmlWriter.write("<p style=\"font-size:16px\">Tran ID</p>\n");
        htmlWriter.write("</td>\n");
        // Tran Details column (← L50-L51)
        htmlWriter.write("<td style=\"width:55%; padding:0px 5px; "
                + "background-color:" + COLOR_COL_HDR + "; text-align:left;\">\n");
        htmlWriter.write("<p style=\"font-size:16px\">Tran Details</p>\n");
        htmlWriter.write("</td>\n");
        // Amount column (← L53-L54)
        htmlWriter.write("<td style=\"width:20%; padding:0px 5px; "
                + "background-color:" + COLOR_COL_HDR + "; text-align:right;\">\n");
        htmlWriter.write("<p style=\"font-size:16px\">Amount</p>\n");
        htmlWriter.write("</td>\n");
        htmlWriter.write("</tr>\n");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  writeTransactions(CardXref, Map, Writer, Writer)
    //  ← 4000-TRNXFILE-GET + statement footer
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes all transactions matching the given cross-reference card number,
     * then writes the statement footer with the accumulated total.
     *
     * <p>← 4000-TRNXFILE-GET: Scans WS-TRNX-TABLE for entries where
     * WS-CARD-NUM(CR-JMP) matches XREF-CARD-NUM, calls
     * {@link #writeTransactionLine} (← 6000-WRITE-TRANS) for each match, and
     * accumulates WS-TOTAL-AMT using {@link BigDecimal} arithmetic with
     * {@link RoundingMode#HALF_UP} (matching COBOL default rounding).</p>
     *
     * <p>After the transaction loop, writes the footer: ST-LINE12 (dashes),
     * ST-LINE14A (total line), ST-LINE15 (end separator), and HTML
     * "End of Statement" closing tags.</p>
     *
     * @param xref       current cross-reference record
     * @param trnxTable  pre-loaded transaction table (← WS-TRNX-TABLE)
     * @param stmtWriter plain-text output writer (← STMT-FILE)
     * @param htmlWriter HTML output writer (← HTML-FILE)
     * @throws IOException if a write operation fails
     */
    public void writeTransactions(CardXref xref,
                                  Map<String, List<Transaction>> trnxTable,
                                  Writer stmtWriter, Writer htmlWriter) throws IOException {

        // ← MOVE ZERO TO WS-TOTAL-AMT (reset per-statement)
        totalAmount = BigDecimal.ZERO;
        String cardNum = xref.getXrefCardNum();
        List<Transaction> transactions = trnxTable.getOrDefault(cardNum, List.of());

        // ← PERFORM VARYING TR-JMP FROM 1 BY 1 UNTIL TR-JMP > WS-TRCT(CR-JMP)
        for (Transaction tran : transactions) {
            // ← PERFORM 6000-WRITE-TRANS
            writeTransactionLine(tran, stmtWriter, htmlWriter);

            // ← ADD TRNX-AMT TO WS-TOTAL-AMT
            BigDecimal amount = tran.getAmount() != null ? tran.getAmount() : BigDecimal.ZERO;
            totalAmount = totalAmount.add(amount);
        }

        // ── Statement Footer (← after PERFORM loop in 4000-TRNXFILE-GET) ──

        // Text footer: dashes + total line + end separator
        // ST-LINE12: Dashes
        writeLine(stmtWriter, DASH_LINE);

        // ST-LINE14A: "Total EXP:" + spaces + "$" + total amount
        // Layout: PIC X(10) "Total EXP:" + PIC X(56) spaces + PIC X(1) "$" + PIC Z(9).99-
        String totalFormatted = formatAmountZ9V99(totalAmount);
        writeLine(stmtWriter, "Total EXP:" + " ".repeat(56) + "$" + totalFormatted);

        // ST-LINE15: End separator
        writeLine(stmtWriter, END_SEPARATOR);

        // HTML footer: "End of Statement" bar + close table/body/html
        // ← L10 (dark blue bg) + L75 ("End of Statement")
        htmlWriter.write("<tr>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:" + COLOR_HEADER_FOOTER + ";\">\n");
        htmlWriter.write("<h3>End of Statement</h3>\n");
        htmlWriter.write("</td>\n");
        htmlWriter.write("</tr>\n");
        // ← L78: </table>
        htmlWriter.write("</table>\n");
        // ← L79: </body>
        htmlWriter.write("</body>\n");
        // ← L80: </html>
        htmlWriter.write("</html>\n");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  writeTransactionLine(Transaction, Writer, Writer)
    //  ← 6000-WRITE-TRANS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes a single transaction detail line to both output formats.
     *
     * <p>← 6000-WRITE-TRANS: Formats TRNX-ID → ST-TRANID (PIC X(16)),
     * TRNX-DESC → ST-TRANDT (PIC X(49)), TRNX-AMT → ST-TRANAMT (PIC Z(9).99-)
     * into ST-LINE14 for plain-text, and an HTML table row with three cells
     * (Tran ID, Tran Details, Amount) on {@value #COLOR_DATA_ROW} background.</p>
     *
     * @param tran       transaction record
     * @param stmtWriter plain-text output writer (← STMT-FILE)
     * @param htmlWriter HTML output writer (← HTML-FILE)
     * @throws IOException if a write operation fails
     */
    public void writeTransactionLine(Transaction tran,
                                     Writer stmtWriter, Writer htmlWriter) throws IOException {

        // ── Plain-text: ST-LINE14 layout ──
        // ST-TRANID PIC X(16) + ' ' PIC X(1) + ST-TRANDT PIC X(49) + '$' PIC X(1)
        //   + ST-TRANAMT PIC Z(9).99- = 16+1+49+1+13 = 80
        String tranId = padRight(safeStr(tran.getTranId()), 16);
        String tranDesc = padRight(safeStr(tran.getDescription()).trim(), 49);
        String tranAmt = formatAmountZ9V99(tran.getAmount());
        writeLine(stmtWriter, tranId + " " + tranDesc + "$" + tranAmt);

        // ── HTML row (← L58/L61/L64 on #f2f2f2 background) ──
        htmlWriter.write("<tr>\n");

        // Tran ID cell (← L58: width:25%, text-align:left)
        htmlWriter.write("<td style=\"width:25%; padding:0px 5px; "
                + "background-color:" + COLOR_DATA_ROW + "; text-align:left;\">\n");
        htmlWriter.write("<p>" + escapeHtml(safeStr(tran.getTranId()).trim()) + "</p>\n");
        htmlWriter.write("</td>\n");

        // Tran Details cell (← L61: width:55%, text-align:left)
        htmlWriter.write("<td style=\"width:55%; padding:0px 5px; "
                + "background-color:" + COLOR_DATA_ROW + "; text-align:left;\">\n");
        htmlWriter.write("<p>" + escapeHtml(safeStr(tran.getDescription()).trim()) + "</p>\n");
        htmlWriter.write("</td>\n");

        // Amount cell (← L64: width:20%, text-align:right)
        htmlWriter.write("<td style=\"width:20%; padding:0px 5px; "
                + "background-color:" + COLOR_DATA_ROW + "; text-align:right;\">\n");
        BigDecimal amt = tran.getAmount();
        String amtDisplay = amt != null
                ? amt.setScale(2, RoundingMode.HALF_UP).toPlainString() : "0.00";
        htmlWriter.write("<p>" + escapeHtml(amtDisplay) + "</p>\n");
        htmlWriter.write("</td>\n");

        htmlWriter.write("</tr>\n");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Private Helper Methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Reads the next cross-reference record via StatementIoService.
     *
     * <p>← 1000-XREFFILE-GET-NEXT: CALL 'CBSTM03B' with M03B-READ on XREFFILE.
     * Returns {@link Optional#empty()} when EOF is reached (RC='10').</p>
     *
     * @return next CardXref, or empty if EOF
     */
    private Optional<CardXref> getNextXref() {
        return statementIoService.readNextXref();
    }

    /**
     * Reads a customer record by primary key.
     *
     * <p>← 2000-CUSTFILE-GET: CALL 'CBSTM03B' with M03B-READ-K on CUSTFILE
     * using XREF-CUST-ID as the key. Uses StatementIoService as primary access
     * path with direct repository as fallback.</p>
     *
     * @param custId customer identifier (← XREF-CUST-ID, 9 bytes)
     * @return customer record
     * @throws CardDemoException if customer is not found
     */
    private Customer getCustomer(String custId) {
        // Primary: via StatementIoService (← COBOL CBSTM03B M03B-READ-K)
        Optional<Customer> opt = statementIoService.readCustomerByKey(custId);
        if (opt.isPresent()) {
            return opt.get();
        }
        // Fallback: direct repository access
        return customerRepository.findById(custId)
                .orElseThrow(() -> {
                    logger.error("Customer not found: custId={}", custId);
                    return new CardDemoException(
                            "ERROR READING CUSTFILE — customer not found: " + custId);
                });
    }

    /**
     * Reads an account record by primary key.
     *
     * <p>← 3000-ACCTFILE-GET: CALL 'CBSTM03B' with M03B-READ-K on ACCTFILE
     * using XREF-ACCT-ID as the key. Uses StatementIoService as primary access
     * path with direct repository as fallback.</p>
     *
     * @param acctId account identifier (← XREF-ACCT-ID, 11 bytes)
     * @return account record
     * @throws CardDemoException if account is not found
     */
    private Account getAccount(String acctId) {
        // Primary: via StatementIoService (← COBOL CBSTM03B M03B-READ-K)
        Optional<Account> opt = statementIoService.readAccountByKey(acctId);
        if (opt.isPresent()) {
            return opt.get();
        }
        // Fallback: direct repository access
        return accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    logger.error("Account not found: acctId={}", acctId);
                    return new CardDemoException(
                            "ERROR READING ACCTFILE — account not found: " + acctId);
                });
    }

    /**
     * Builds the customer full name from first, middle, and last name fields.
     *
     * <p>← STRING CUST-FIRST-NAME DELIMITED BY ' ' ' ' DELIMITED BY SIZE
     * CUST-MIDDLE-NAME DELIMITED BY ' ' ' ' DELIMITED BY SIZE
     * CUST-LAST-NAME DELIMITED BY ' ' INTO ST-NAME.
     * Uses {@code trim()} on each field to remove COBOL padding spaces.</p>
     *
     * @param customer customer record
     * @return formatted full name, never null
     */
    private String buildCustomerName(Customer customer) {
        String first = safeStr(customer.getFirstName()).trim();
        String middle = safeStr(customer.getMiddleName()).trim();
        String last = safeStr(customer.getLastName()).trim();
        StringBuilder sb = new StringBuilder();
        if (!first.isEmpty()) {
            sb.append(first);
        }
        if (!middle.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(middle);
        }
        if (!last.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(last);
        }
        return sb.toString();
    }

    /**
     * Builds the city/state/country/zip address line.
     *
     * <p>← STRING CUST-ADDR-LINE-3 DELIMITED BY ' ' ' ' DELIMITED BY SIZE
     * CUST-ADDR-STATE-CD DELIMITED BY ' ' ' ' DELIMITED BY SIZE
     * CUST-ADDR-COUNTRY-CD DELIMITED BY ' ' ' ' DELIMITED BY SIZE
     * CUST-ADDR-ZIP DELIMITED BY ' ' INTO ST-ADD3.
     * Uses {@code trim()} on each field to remove COBOL padding spaces.</p>
     *
     * @param customer customer record
     * @return formatted city/state/country/zip line, never null
     */
    private String buildCityStateZip(Customer customer) {
        String city = safeStr(customer.getAddrLine3()).trim();
        String state = safeStr(customer.getAddrStateCode()).trim();
        String country = safeStr(customer.getAddrCountryCode()).trim();
        String zip = safeStr(customer.getAddrZip()).trim();
        StringBuilder sb = new StringBuilder();
        if (!city.isEmpty()) {
            sb.append(city);
        }
        if (!state.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(state);
        }
        if (!country.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(country);
        }
        if (!zip.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(zip);
        }
        return sb.toString();
    }

    /**
     * Formats a monetary amount as COBOL PIC Z(9).99- (13 characters).
     *
     * <p>Leading zeros are replaced with spaces (Z-suppression). Trailing sign
     * character: '-' for negative, ' ' for positive/zero. Total width: 9
     * (integer with Z-suppression) + '.' + 2 (decimal) + sign = 13.</p>
     *
     * <p>Examples: 1234.56 → "     1234.56 ", -99.00 → "       99.00-"</p>
     *
     * @param value monetary amount, may be null (treated as zero)
     * @return 13-character formatted string
     */
    private static String formatAmountZ9V99(BigDecimal value) {
        BigDecimal val = value != null ? value : BigDecimal.ZERO;
        BigDecimal abs = val.abs().setScale(2, RoundingMode.HALF_UP);
        String plain = abs.toPlainString();
        int dot = plain.indexOf('.');
        String intPart = dot >= 0 ? plain.substring(0, dot) : plain;
        String decPart = dot >= 0 ? plain.substring(dot + 1) : "00";
        if (decPart.length() < 2) {
            decPart = decPart + "0";
        }
        if (decPart.length() > 2) {
            decPart = decPart.substring(0, 2);
        }
        String sign = val.signum() < 0 ? "-" : " ";
        // Z(9) = 9-char integer with leading-zero suppression (spaces)
        return String.format("%9s", intPart) + "." + decPart + sign;
    }

    /**
     * Formats a balance amount as COBOL PIC 9(9).99- (13 characters).
     *
     * <p>Leading zeros are preserved (not suppressed). Trailing sign character:
     * '-' for negative, ' ' for positive/zero. Total width: 9 (integer with
     * leading zeros) + '.' + 2 (decimal) + sign = 13.</p>
     *
     * <p>Examples: 1234.56 → "000001234.56 ", -5000.00 → "000005000.00-"</p>
     *
     * @param value monetary amount, may be null (treated as zero)
     * @return 13-character formatted string
     */
    private static String formatBalance9V99(BigDecimal value) {
        BigDecimal val = value != null ? value : BigDecimal.ZERO;
        BigDecimal abs = val.abs().setScale(2, RoundingMode.HALF_UP);
        String plain = abs.toPlainString();
        int dot = plain.indexOf('.');
        String intPart = dot >= 0 ? plain.substring(0, dot) : plain;
        String decPart = dot >= 0 ? plain.substring(dot + 1) : "00";
        if (decPart.length() < 2) {
            decPart = decPart + "0";
        }
        if (decPart.length() > 2) {
            decPart = decPart.substring(0, 2);
        }
        // PIC 9(9) = leading zeros displayed, right-justified with '0' fill
        String paddedInt = String.format("%9s", intPart).replace(' ', '0');
        String sign = val.signum() < 0 ? "-" : " ";
        return paddedInt + "." + decPart + sign;
    }

    /**
     * Writes a line to the text output writer followed by a newline.
     *
     * <p>← WRITE FD-STMTFILE-REC FROM ST-LINEnn.</p>
     *
     * @param writer output writer
     * @param line   line content (should be exactly {@value #LINE_WIDTH} chars)
     * @throws IOException if the write fails
     */
    private static void writeLine(Writer writer, String line) throws IOException {
        writer.write(line);
        writer.write("\n");
    }

    /**
     * Right-pads or truncates text to the specified width.
     *
     * <p>Matches COBOL PIC X(n) fixed-width field behavior: values shorter than
     * the width are right-padded with spaces; values longer are truncated.</p>
     *
     * @param text  input text
     * @param width target width
     * @return fixed-width string of exactly {@code width} characters
     */
    private static String padRight(String text, int width) {
        if (text == null) {
            return " ".repeat(width);
        }
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    /**
     * Returns the input string or empty string if null.
     *
     * <p>← COBOL SPACES default for uninitialized PIC X fields.</p>
     *
     * @param value input string, may be null
     * @return non-null string
     */
    private static String safeStr(String value) {
        return value != null ? value : "";
    }

    /**
     * Escapes HTML special characters to prevent injection in generated output.
     *
     * @param text raw text, may be null
     * @return HTML-safe text, never null
     */
    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
