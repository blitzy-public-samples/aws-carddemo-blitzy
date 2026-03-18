package com.cardemo.service.batch;

/*
 * StatementEngineService.java — Faithful translation of CBSTM03A.CBL
 *
 * COBOL Source: app/cbl/CBSTM03A.CBL (Statement generation main engine)
 * COBOL Subroutine: CBSTM03B.CBL → {@link StatementIoService}
 *
 * COBOL Paragraph → Java Method Traceability:
 * ──────────────────────────────────────────────────────────────────────
 *   PROCEDURE DIVISION entry        → generateStatements(String)
 *   0000-START                      → dispatchFileOperation(String)
 *   1000-MAINLINE                   → processMainline(List, Map, BW, BW)
 *   1000-XREFFILE-GET-NEXT          → getNextXref(Iterator)
 *   2000-CUSTFILE-GET               → getCustomer(String)
 *   3000-ACCTFILE-GET               → getAccount(String)
 *   4000-TRNXFILE-GET               → writeTransactions(CardXref, Map, BW, BW)
 *   5000-CREATE-STATEMENT           → createStatement(Customer, Account, CardXref, BW, BW)
 *   5100-WRITE-HTML-HEADER          → writeHtmlHeader(BufferedWriter)
 *   5200-WRITE-HTML-NMADBS          → writeHtmlNameAddress(Customer, Account, BW)
 *   6000-WRITE-TRANS                → writeTransactionLine(Transaction, BW, BW)
 *   8100–8400 file opens            → StatementIoService.open*() methods
 *   8500-READTRNX-READ              → loadTransactionTable()
 *   9100–9400 file closes           → StatementIoService.close*() methods
 *   9999-ABEND-PROGRAM              → throws CardDemoException
 * ──────────────────────────────────────────────────────────────────────
 *
 * This class is the service-layer orchestrator for statement generation.
 * It uses {@link StatementIoService} for coordinated dataset open/close
 * lifecycle (mapping the COBOL {@code CALL 'CBSTM03B'} I/O subroutine
 * pattern) and repositories for bulk data loading where beneficial.
 *
 * The companion {@link com.cardemo.batch.processor.StatementProcessor}
 * provides a Spring Batch {@code ItemProcessor} adapter for chunk-based
 * batch processing.  This service provides an independent orchestration
 * entry point that can be invoked outside the Spring Batch step pipeline.
 */

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Statement generation engine — faithful translation of CBSTM03A.CBL.
 *
 * <p>Orchestrates the complete statement generation process: opens data files
 * via {@link StatementIoService} (← CALL 'CBSTM03B'), loads all transactions
 * into an in-memory table (← WS-TRNX-TABLE), iterates cross-reference records
 * (← STARTBR/READNEXT CARDXREF), resolves customer and account data via keyed
 * reads, and produces both plain-text (80-column, PIC X(80)) and HTML statement
 * output files.</p>
 *
 * <h3>COBOL Working-Storage Equivalents</h3>
 * <ul>
 *   <li>WS-TRNX-TABLE (51 cards × 10 trans) → {@code Map<String, List<Transaction>>}</li>
 *   <li>WS-TRNX-TOTAL PIC S9(10)V99 → {@link BigDecimal} with scale 2</li>
 *   <li>ST-LINE0 through ST-LINE15 (PIC X(80)) → {@link BufferedWriter} lines</li>
 *   <li>WS-FL-DD-*-STATUS (file status codes) → {@link Optional} return semantics</li>
 * </ul>
 *
 * @see StatementIoService
 * @see com.cardemo.batch.processor.StatementProcessor
 */
@Service
public class StatementEngineService {

    private static final Logger logger = LoggerFactory.getLogger(StatementEngineService.class);

    // ─── Working-Storage Constants (← CBSTM03A WORKING-STORAGE SECTION) ───────

    /** Fixed line width for plain-text output (← PIC X(80) record length). */
    static final int LINE_WIDTH = 80;

    /** Maximum distinct cards in transaction table (← WS-MAX-CARDS VALUE 51). */
    static final int MAX_CARDS = 51;

    /** Maximum transactions per card (← WS-MAX-TRNX-PER-CARD VALUE 10). */
    static final int MAX_TRNX_PER_CARD = 10;

    /** Bank name header text (← WS-BANK-NAME VALUE 'CARIBEAN BANK OF BAHAMAS'). */
    static final String BANK_NAME = "CARIBEAN BANK OF BAHAMAS";

    /** 31-star left border for bank name line. */
    private static final String STARS_31 = "*".repeat(31);

    /** Full 80-star separator line. */
    private static final String STARS_80 = "*".repeat(LINE_WIDTH);

    /** Full 80-dash separator line (← ST-LINE12 / ST-LINE14). */
    private static final String DASHES_80 = "-".repeat(LINE_WIDTH);

    /** Statement text output file name (← STMT-FILE DD). */
    private static final String STMT_FILE_NAME = "statements.txt";

    /** Statement HTML output file name (← HTML-FILE DD). */
    private static final String HTML_FILE_NAME = "statements.html";

    /** Timestamp formatter for logging. */
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─── HTML Color Constants (← CBSTM03A inline CSS hex values) ──────────────

    /** Header/footer bar (← background-color #1d1d96b3). */
    private static final String COLOR_HEADER_FOOTER = "#1d1d96b3";

    /** Bank info row (← background-color #FFAF33). */
    private static final String COLOR_BANK_INFO = "#FFAF33";

    /** Data rows (← background-color #f2f2f2). */
    private static final String COLOR_DATA_ROW = "#f2f2f2";

    /** Section headers (← background-color #33FFD1). */
    private static final String COLOR_SECTION_HEADER = "#33FFD1";

    /** Column headers (← background-color #33FF5E). */
    private static final String COLOR_COLUMN_HEADER = "#33FF5E";

    // ─── Injected Dependencies ────────────────────────────────────────────────

    private final StatementIoService statementIoService;
    private final TransactionRepository transactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;

    /**
     * Constructs the statement engine with all required dependencies.
     *
     * <p>Dependencies mirror the COBOL resource model:</p>
     * <ul>
     *   <li>{@code statementIoService} — I/O subroutine (← CALL 'CBSTM03B')</li>
     *   <li>{@code transactionRepository} — bulk transaction loading
     *       (← 8500-READTRNX-READ sequential file scan)</li>
     *   <li>{@code cardXrefRepository} — cross-reference access
     *       (← XREFFILE STARTBR/READNEXT)</li>
     *   <li>{@code customerRepository} — customer keyed reads
     *       (← CUSTFILE READ by key)</li>
     *   <li>{@code accountRepository} — account keyed reads
     *       (← ACCTFILE READ by key)</li>
     * </ul>
     *
     * @param statementIoService    I/O subroutine service (← CBSTM03B.CBL)
     * @param transactionRepository transaction data access
     * @param cardXrefRepository    card cross-reference data access
     * @param customerRepository    customer data access
     * @param accountRepository     account data access
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
    //  Main Entry Point (← PROCEDURE DIVISION / 0000-START)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generates statements for all card-holder accounts.
     *
     * <p>Orchestrates the complete CBSTM03A statement generation flow:</p>
     * <ol>
     *   <li>Create output directory and open text + HTML output files</li>
     *   <li>Load all transactions into an in-memory table grouped by card number
     *       (← 8500-READTRNX-READ → WS-TRNX-TABLE)</li>
     *   <li>Open dataset files via {@link StatementIoService}
     *       (← 8100-8400 file opens via CALL 'CBSTM03B')</li>
     *   <li>Iterate cross-references and generate per-account statements
     *       (← 1000-MAINLINE loop)</li>
     *   <li>Close all dataset files (← 9100-9400 file closes)</li>
     * </ol>
     *
     * <p>← PROCEDURE DIVISION / 0000-START</p>
     *
     * @param outputDir directory path for statement output files
     * @throws CardDemoException if a fatal error occurs (← 9999-ABEND-PROGRAM)
     */
    public void generateStatements(String outputDir) {
        logger.info("Statement generation started — output directory: {}", outputDir);
        LocalDateTime startTime = LocalDateTime.now();

        Path outPath = Paths.get(outputDir);
        try {
            Files.createDirectories(outPath);
        } catch (IOException e) {
            logger.error("Failed to create output directory: {}", outputDir, e);
            throw new CardDemoException(
                    "Statement generation abend — cannot create output directory: " + outputDir, e);
        }

        try (BufferedWriter textWriter = Files.newBufferedWriter(
                     outPath.resolve(STMT_FILE_NAME), StandardCharsets.UTF_8);
             BufferedWriter htmlWriter = Files.newBufferedWriter(
                     outPath.resolve(HTML_FILE_NAME), StandardCharsets.UTF_8)) {

            // Write HTML document preamble
            htmlWriter.write("<!DOCTYPE html>\n<html>\n<head>\n");
            htmlWriter.write("<meta charset=\"UTF-8\">\n");
            htmlWriter.write("<title>CardDemo Account Statements</title>\n");
            htmlWriter.write("</head>\n<body>\n");

            // ← 8500-READTRNX-READ: Load all transactions into WS-TRNX-TABLE
            Map<String, List<Transaction>> trnxTable = loadTransactionTable();
            logger.info("Transaction table loaded — {} distinct cards, {} total transactions",
                    trnxTable.size(),
                    trnxTable.values().stream().mapToInt(List::size).sum());

            // ← 8100-XREFFILE-OPEN through 8400-ACCTFILE-OPEN
            dispatchFileOperation("XREFFILE");
            dispatchFileOperation("CUSTFILE");
            dispatchFileOperation("ACCTFILE");

            // ← Retrieve xref records for sequential iteration
            List<CardXref> xrefs = cardXrefRepository.findAll();

            // ← 1000-MAINLINE: Process all statements
            processMainline(xrefs, trnxTable, textWriter, htmlWriter);

            // Close HTML document
            htmlWriter.write("</body>\n</html>\n");

            // ← 9100-9400: Close all dataset files
            statementIoService.closeXrefFile();
            statementIoService.closeCustomerFile();
            statementIoService.closeAccountFile();
            statementIoService.closeTransactionFile();

            textWriter.flush();
            htmlWriter.flush();

            LocalDateTime endTime = LocalDateTime.now();
            logger.info("Statement generation completed — started={}, ended={}",
                    startTime.format(TIMESTAMP_FMT), endTime.format(TIMESTAMP_FMT));

        } catch (IOException e) {
            // ← 9999-ABEND-PROGRAM
            logger.error("Fatal I/O error during statement generation", e);
            throw new CardDemoException("Statement generation abend — I/O error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  0000-START — File Operation Dispatcher
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Dispatches a dataset file-open operation to {@link StatementIoService}.
     *
     * <p>← 0000-START: EVALUATE WS-M03B-DD dispatching file opens to CBSTM03B.</p>
     *
     * @param ddName logical dataset name (XREFFILE, CUSTFILE, ACCTFILE, TRNXFILE)
     * @throws CardDemoException if the DD name is unrecognized
     */
    void dispatchFileOperation(String ddName) {
        logger.debug("Opening dataset: {}", ddName);
        switch (ddName) {
            case "XREFFILE" -> statementIoService.openXrefFile();
            case "CUSTFILE" -> statementIoService.openCustomerFile();
            case "ACCTFILE" -> statementIoService.openAccountFile();
            case "TRNXFILE" -> statementIoService.openTransactionFile();
            default -> {
                logger.error("Unrecognized DD name in dispatch: {}", ddName);
                throw new CardDemoException(
                        "Statement generation abend — unknown DD name: " + ddName);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  8500-READTRNX-READ — Load Transaction Table
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Loads all transactions into an in-memory table grouped by card number.
     *
     * <p>← 8500-READTRNX-READ: Reads the entire TRANSACT dataset sequentially
     * and populates WS-TRNX-TABLE indexed by card number.  The COBOL table has
     * a capacity of {@link #MAX_CARDS} × {@link #MAX_TRNX_PER_CARD} entries;
     * this Java implementation uses a {@link LinkedHashMap} with unbounded
     * capacity but logs a warning if COBOL limits are exceeded.</p>
     *
     * @return map of card number → list of transactions for that card
     */
    Map<String, List<Transaction>> loadTransactionTable() {
        List<Transaction> allTxns = transactionRepository.findAllByOrderByTranIdAsc();
        Map<String, List<Transaction>> table = new LinkedHashMap<>();

        for (Transaction txn : allTxns) {
            String cardNum = txn.getCardNum();
            if (cardNum != null && !cardNum.isBlank()) {
                table.computeIfAbsent(cardNum, k -> new ArrayList<>()).add(txn);
            }
        }

        // COBOL parity check — WS-TRNX-TABLE capacity is 51 cards × 10 transactions
        if (table.size() > MAX_CARDS) {
            logger.warn("Transaction table exceeds COBOL WS-MAX-CARDS limit: {} > {}",
                    table.size(), MAX_CARDS);
        }
        for (Map.Entry<String, List<Transaction>> entry : table.entrySet()) {
            if (entry.getValue().size() > MAX_TRNX_PER_CARD) {
                logger.warn("Card {} exceeds COBOL WS-MAX-TRNX-PER-CARD limit: {} > {}",
                        entry.getKey(), entry.getValue().size(), MAX_TRNX_PER_CARD);
            }
        }

        return table;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  1000-MAINLINE — Main Processing Loop
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Processes all cross-reference records and generates a statement for each.
     *
     * <p>← 1000-MAINLINE: PERFORM 1000-XREFFILE-GET-NEXT / PERFORM UNTIL
     * WS-FL-DD-XREFFILE-STATUS = '10'.  For each XREF record, resolves the
     * linked customer (← 2000-CUSTFILE-GET) and account (← 3000-ACCTFILE-GET),
     * writes the statement header (← 5000-CREATE-STATEMENT), writes matching
     * transactions (← 4000-TRNXFILE-GET), and appends the total footer.</p>
     *
     * @param xrefs      all cross-reference records to process
     * @param trnxTable  pre-loaded transaction table grouped by card number
     * @param textWriter plain-text output writer (← STMT-FILE)
     * @param htmlWriter HTML output writer (← HTML-FILE)
     * @throws IOException if an output write fails
     */
    void processMainline(List<CardXref> xrefs,
                         Map<String, List<Transaction>> trnxTable,
                         BufferedWriter textWriter,
                         BufferedWriter htmlWriter) throws IOException {
        int statementsGenerated = 0;
        int xrefsSkipped = 0;
        Iterator<CardXref> iterator = xrefs.iterator();

        // ← PERFORM 1000-XREFFILE-GET-NEXT
        Optional<CardXref> xrefOpt = getNextXref(iterator);

        // ← PERFORM UNTIL WS-FL-DD-XREFFILE-STATUS = '10'
        while (xrefOpt.isPresent()) {
            CardXref xref = xrefOpt.get();

            // ← 2000-CUSTFILE-GET: Read customer by XREF-CUST-ID
            Optional<Customer> custOpt = getCustomer(xref.getCustId());

            // ← 3000-ACCTFILE-GET: Read account by XREF-ACCT-ID
            Optional<Account> acctOpt = getAccount(xref.getAccountId());

            if (custOpt.isPresent() && acctOpt.isPresent()) {
                Customer customer = custOpt.get();
                Account account = acctOpt.get();

                // ← 5000-CREATE-STATEMENT: Write header (name, address, account info)
                createStatement(customer, account, xref, textWriter, htmlWriter);

                // ← 4000-TRNXFILE-GET: Write matching transactions, accumulate total
                BigDecimal total = writeTransactions(xref, trnxTable, textWriter, htmlWriter);

                // Write statement footer with accumulated total
                writeStatementFooter(total, textWriter, htmlWriter);

                statementsGenerated++;
            } else {
                xrefsSkipped++;
                logger.warn("Skipping XREF card={} — customer={} account={}",
                        xref.getXrefCardNum(),
                        custOpt.isPresent() ? "found" : xref.getCustId() + " NOT FOUND",
                        acctOpt.isPresent() ? "found" : xref.getAccountId() + " NOT FOUND");
            }

            // ← PERFORM 1000-XREFFILE-GET-NEXT (loop advance)
            xrefOpt = getNextXref(iterator);
        }

        logger.info("Mainline complete — {} statements generated, {} xrefs skipped",
                statementsGenerated, xrefsSkipped);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  1000-XREFFILE-GET-NEXT — Sequential XREF Read
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Reads the next cross-reference record from the sequential iterator.
     *
     * <p>← 1000-XREFFILE-GET-NEXT: CALL 'CBSTM03B' with OP-READ-NEXT on
     * XREFFILE.  Returns {@link Optional#empty()} when EOF is reached
     * (← WS-FL-DD-XREFFILE-STATUS = '10').</p>
     *
     * @param iterator cross-reference record iterator
     * @return next CardXref, or empty if EOF
     */
    Optional<CardXref> getNextXref(Iterator<CardXref> iterator) {
        if (iterator.hasNext()) {
            return Optional.of(iterator.next());
        }
        return Optional.empty();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  2000-CUSTFILE-GET — Customer Keyed Read
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Reads a customer record by primary key.
     *
     * <p>← 2000-CUSTFILE-GET: CALL 'CBSTM03B' with OP-READ-KEY on CUSTFILE
     * using XREF-CUST-ID as the key.  Returns {@link Optional#empty()} if the
     * customer is not found (← WS-FL-DD-CUSTFILE-STATUS = '23').</p>
     *
     * @param custId customer identifier (← XREF-CUST-ID, 9 bytes)
     * @return customer record, or empty if not found
     */
    Optional<Customer> getCustomer(String custId) {
        return customerRepository.findById(custId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  3000-ACCTFILE-GET — Account Keyed Read
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Reads an account record by primary key.
     *
     * <p>← 3000-ACCTFILE-GET: CALL 'CBSTM03B' with OP-READ-KEY on ACCTFILE
     * using XREF-ACCT-ID as the key.  Returns {@link Optional#empty()} if the
     * account is not found (← WS-FL-DD-ACCTFILE-STATUS = '23').</p>
     *
     * @param acctId account identifier (← XREF-ACCT-ID, 11 bytes)
     * @return account record, or empty if not found
     */
    Optional<Account> getAccount(String acctId) {
        return accountRepository.findById(acctId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  5000-CREATE-STATEMENT — Statement Header Generation
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes the statement header section for a single card-holder account.
     *
     * <p>← 5000-CREATE-STATEMENT: Formats customer name, address, and account
     * details into both plain-text (ST-LINE0 through ST-LINE12) and HTML
     * (table header via 5100/5200 sub-paragraphs) output.</p>
     *
     * @param customer   customer record (← CUSTFILE)
     * @param account    account record (← ACCTFILE)
     * @param xref       cross-reference record (current iteration)
     * @param textWriter plain-text output (← STMT-FILE)
     * @param htmlWriter HTML output (← HTML-FILE)
     * @throws IOException if a write operation fails
     */
    void createStatement(Customer customer, Account account, CardXref xref,
                         BufferedWriter textWriter, BufferedWriter htmlWriter)
            throws IOException {

        String fullName = buildFullName(customer);

        // ── Plain-text header (← ST-LINE0 through ST-LINE12) ──

        // ST-LINE0: Star separator
        writeLine(textWriter, STARS_80);

        // ST-LINE1: Bank name (centered with star borders)
        writeLine(textWriter, STARS_31 + " " + BANK_NAME + " " + "*".repeat(
                LINE_WIDTH - STARS_31.length() - BANK_NAME.length() - 2));

        // ST-LINE0 again: Star separator
        writeLine(textWriter, STARS_80);

        // ST-LINE2: Customer name
        writeLine(textWriter, padRight("Name               : " + fullName, LINE_WIDTH));

        // ST-LINE3: Address line 1
        writeLine(textWriter, padRight("Address            : "
                + safeStr(customer.getAddrLine1()), LINE_WIDTH));

        // ST-LINE4: City, state, zip
        String cityLine = safeStr(customer.getAddrLine2()) + " "
                + safeStr(customer.getAddrStateCode()) + " "
                + safeStr(customer.getAddrZip());
        writeLine(textWriter, padRight("                     " + cityLine.trim(), LINE_WIDTH));

        // ST-LINE5: Statement for account
        writeLine(textWriter, padRight("Statement for Acct : "
                + safeStr(account.getAcctId()), LINE_WIDTH));

        // ST-LINE6: Status and balance
        writeLine(textWriter, padRight("Status: " + safeStr(account.getActiveStatus())
                + "   Balance: " + formatAmount(account.getCurrBal()), LINE_WIDTH));

        // ST-LINE7: Credit limit
        writeLine(textWriter, padRight("Credit Limit       : "
                + formatAmount(account.getCreditLimit()), LINE_WIDTH));

        // ST-LINE8: Cash credit limit
        writeLine(textWriter, padRight("Cash Credit Limit  : "
                + formatAmount(account.getCashCreditLimit()), LINE_WIDTH));

        // ST-LINE9: FICO score (← CUST-FICO-CREDIT-SCORE PIC 9(03))
        Integer ficoScore = customer.getFicoCreditScore();
        int ficoDisplay = ficoScore != null ? ficoScore : 0;
        writeLine(textWriter, padRight("FICO Score         : " + ficoDisplay, LINE_WIDTH));

        // ST-LINE10: Open and expiration dates
        writeLine(textWriter, padRight("Open Date: " + safeStr(account.getOpenDate())
                + "   Exp Date: " + safeStr(account.getExpirationDate()), LINE_WIDTH));

        // ST-LINE11: Transaction column headers
        writeLine(textWriter, padRight(
                "Tran ID    Type Cat Source Description              Amount", LINE_WIDTH));

        // ST-LINE12: Dash separator
        writeLine(textWriter, DASHES_80);

        // ── HTML header sections ──

        // ← 5100-WRITE-HTML-HEADER
        writeHtmlHeader(htmlWriter);

        // ← 5200-WRITE-HTML-NMADBS
        writeHtmlNameAddress(customer, account, htmlWriter);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  5100-WRITE-HTML-HEADER — HTML Table Header
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes the HTML table opening and bank name header row.
     *
     * <p>← 5100-WRITE-HTML-HEADER: Opens HTML table element with the bank name
     * in a full-width header bar colored {@value #COLOR_HEADER_FOOTER}.</p>
     *
     * @param htmlWriter HTML output writer
     * @throws IOException if the write fails
     */
    void writeHtmlHeader(BufferedWriter htmlWriter) throws IOException {
        htmlWriter.write("<table style=\"width:100%; border-collapse:collapse; "
                + "margin-bottom:20px; font-family:Arial,sans-serif;\">\n");
        htmlWriter.write("<tr style=\"background-color:" + COLOR_HEADER_FOOTER
                + "; color:white;\">\n");
        htmlWriter.write("<td colspan=\"6\" style=\"padding:10px; text-align:center; "
                + "font-size:18px;\"><strong>" + escapeHtml(BANK_NAME)
                + "</strong></td></tr>\n");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  5200-WRITE-HTML-NMADBS — HTML Name, Address, Account Details
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes the HTML customer name, address, and account detail rows.
     *
     * <p>← 5200-WRITE-HTML-NMADBS: Outputs customer information on a
     * {@value #COLOR_BANK_INFO} background, account details on
     * {@value #COLOR_DATA_ROW} rows, and transaction column headers on
     * {@value #COLOR_COLUMN_HEADER} background.</p>
     *
     * @param customer   customer record
     * @param account    account record
     * @param htmlWriter HTML output writer
     * @throws IOException if the write fails
     */
    void writeHtmlNameAddress(Customer customer, Account account,
                              BufferedWriter htmlWriter) throws IOException {

        String fullName = buildFullName(customer);
        Integer ficoScore = customer.getFicoCreditScore();
        int ficoDisplay = ficoScore != null ? ficoScore : 0;

        // Customer info row (← #FFAF33)
        htmlWriter.write("<tr style=\"background-color:" + COLOR_BANK_INFO + ";\">\n");
        htmlWriter.write("<td colspan=\"6\" style=\"padding:8px;\">\n");
        htmlWriter.write("<strong>Customer:</strong> " + escapeHtml(fullName) + "<br>\n");
        htmlWriter.write("<strong>Address:</strong> "
                + escapeHtml(safeStr(customer.getAddrLine1())) + "<br>\n");
        htmlWriter.write(escapeHtml(safeStr(customer.getAddrLine2()) + " "
                + safeStr(customer.getAddrStateCode()) + " "
                + safeStr(customer.getAddrZip())) + "\n");
        htmlWriter.write("</td></tr>\n");

        // Account section header (← #33FFD1)
        htmlWriter.write("<tr style=\"background-color:" + COLOR_SECTION_HEADER + ";\">\n");
        htmlWriter.write("<td colspan=\"6\" style=\"padding:8px;\"><strong>Account: "
                + escapeHtml(safeStr(account.getAcctId())) + "</strong></td></tr>\n");

        // Account detail rows (← #f2f2f2)
        htmlWriter.write("<tr style=\"background-color:" + COLOR_DATA_ROW + ";\">\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:4px;\">Status: "
                + escapeHtml(safeStr(account.getActiveStatus())) + "</td>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:4px;\">Balance: "
                + formatAmount(account.getCurrBal()) + "</td></tr>\n");

        htmlWriter.write("<tr style=\"background-color:" + COLOR_DATA_ROW + ";\">\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:4px;\">Credit Limit: "
                + formatAmount(account.getCreditLimit()) + "</td>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:4px;\">Cash Credit Limit: "
                + formatAmount(account.getCashCreditLimit()) + "</td></tr>\n");

        // FICO score and dates row (← #f2f2f2)
        htmlWriter.write("<tr style=\"background-color:" + COLOR_DATA_ROW + ";\">\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:4px;\">FICO Score: "
                + ficoDisplay + "</td>\n");
        htmlWriter.write("<td colspan=\"3\" style=\"padding:4px;\">Open: "
                + escapeHtml(safeStr(account.getOpenDate())) + " / Exp: "
                + escapeHtml(safeStr(account.getExpirationDate())) + "</td></tr>\n");

        // Transaction column headers (← #33FF5E)
        htmlWriter.write("<tr style=\"background-color:" + COLOR_COLUMN_HEADER + ";\">\n");
        htmlWriter.write("<td style=\"padding:4px;\"><strong>Tran ID</strong></td>\n");
        htmlWriter.write("<td style=\"padding:4px;\"><strong>Type</strong></td>\n");
        htmlWriter.write("<td style=\"padding:4px;\"><strong>Cat</strong></td>\n");
        htmlWriter.write("<td style=\"padding:4px;\"><strong>Source</strong></td>\n");
        htmlWriter.write("<td style=\"padding:4px;\"><strong>Description</strong></td>\n");
        htmlWriter.write("<td style=\"padding:4px;\"><strong>Amount</strong></td></tr>\n");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  4000-TRNXFILE-GET — Write Matching Transactions
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes all transactions matching the given cross-reference card number.
     *
     * <p>← 4000-TRNXFILE-GET: Scans WS-TRNX-TABLE for entries where
     * WS-TRNX-CARD-NUM matches XREF-CARD-NUM, calls 6000-WRITE-TRANS for each
     * match, and accumulates WS-TRNX-TOTAL using {@link BigDecimal} arithmetic
     * with {@link RoundingMode#HALF_UP} (matching COBOL default rounding).</p>
     *
     * @param xref       current cross-reference record
     * @param trnxTable  pre-loaded transaction table
     * @param textWriter plain-text output writer
     * @param htmlWriter HTML output writer
     * @return accumulated transaction total for this card
     * @throws IOException if a write operation fails
     */
    BigDecimal writeTransactions(CardXref xref,
                                 Map<String, List<Transaction>> trnxTable,
                                 BufferedWriter textWriter,
                                 BufferedWriter htmlWriter) throws IOException {

        BigDecimal total = BigDecimal.ZERO;
        String cardNum = xref.getXrefCardNum();
        List<Transaction> transactions = trnxTable.getOrDefault(cardNum, Collections.emptyList());

        for (Transaction txn : transactions) {
            // ← 6000-WRITE-TRANS
            writeTransactionLine(txn, textWriter, htmlWriter);

            // Accumulate total (← ADD WS-TRNX-AMT TO WS-TRNX-TOTAL)
            if (txn.getAmount() != null) {
                total = total.add(txn.getAmount());
            }
        }

        if (transactions.isEmpty()) {
            // Write informational notice when no transactions match this card
            writeLine(textWriter, padRight("  (No transactions for this period)", LINE_WIDTH));
            htmlWriter.write("<tr style=\"background-color:" + COLOR_DATA_ROW + ";\">\n");
            htmlWriter.write("<td colspan=\"6\" style=\"padding:4px; font-style:italic;\">"
                    + "No transactions for this period</td></tr>\n");
        }

        return total;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  6000-WRITE-TRANS — Single Transaction Line
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes a single transaction detail line to both output formats.
     *
     * <p>← 6000-WRITE-TRANS: Formats a single WS-TRNX-TABLE entry into
     * ST-LINE13 (PIC X(80) plain-text) and an HTML table row.</p>
     *
     * @param txn        transaction record
     * @param textWriter plain-text output writer
     * @param htmlWriter HTML output writer
     * @throws IOException if a write operation fails
     */
    void writeTransactionLine(Transaction txn,
                              BufferedWriter textWriter,
                              BufferedWriter htmlWriter) throws IOException {

        // ── Plain-text line (← ST-LINE13 layout) ──
        String catStr = txn.getCategoryCode() != null
                ? String.valueOf(txn.getCategoryCode()) : "";
        String line = String.format("%-10s %-4s %-3s %-6s %-24s %12s",
                safeStr(txn.getTranId()),
                safeStr(txn.getTypeCode()),
                catStr,
                safeStr(txn.getSource()),
                truncate(safeStr(txn.getDescription()), 24),
                formatAmount(txn.getAmount()));
        writeLine(textWriter, padRight(line, LINE_WIDTH));

        // ── HTML row (← #f2f2f2) ──
        htmlWriter.write("<tr style=\"background-color:" + COLOR_DATA_ROW + ";\">\n");
        htmlWriter.write("<td style=\"padding:4px;\">"
                + escapeHtml(safeStr(txn.getTranId())) + "</td>\n");
        htmlWriter.write("<td style=\"padding:4px;\">"
                + escapeHtml(safeStr(txn.getTypeCode())) + "</td>\n");
        htmlWriter.write("<td style=\"padding:4px;\">" + escapeHtml(catStr) + "</td>\n");
        htmlWriter.write("<td style=\"padding:4px;\">"
                + escapeHtml(safeStr(txn.getSource())) + "</td>\n");
        htmlWriter.write("<td style=\"padding:4px;\">"
                + escapeHtml(safeStr(txn.getDescription())) + "</td>\n");
        htmlWriter.write("<td style=\"padding:4px; text-align:right;\">"
                + formatAmount(txn.getAmount()) + "</td></tr>\n");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Statement Footer — Total Line
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes the statement footer with the accumulated transaction total.
     *
     * <p>← Post-4000-TRNXFILE-GET in 1000-MAINLINE: Outputs the dash separator
     * (ST-LINE14), total line (ST-LINE15), and closing star separator for
     * plain-text.  For HTML, outputs the total row and closes the table.</p>
     *
     * @param total      accumulated transaction total (← WS-TRNX-TOTAL)
     * @param textWriter plain-text output writer
     * @param htmlWriter HTML output writer
     * @throws IOException if a write operation fails
     */
    void writeStatementFooter(BigDecimal total,
                              BufferedWriter textWriter,
                              BufferedWriter htmlWriter) throws IOException {

        // ── Plain-text footer ──

        // ST-LINE14: Dash separator
        writeLine(textWriter, DASHES_80);

        // ST-LINE15: Total line
        writeLine(textWriter, padRight("TOTAL: " + formatAmount(total), LINE_WIDTH));

        // Closing star separator
        writeLine(textWriter, STARS_80);

        // Blank line between statements
        textWriter.newLine();

        // ── HTML footer (← #1d1d96b3) ──
        htmlWriter.write("<tr style=\"background-color:" + COLOR_HEADER_FOOTER
                + "; color:white;\">\n");
        htmlWriter.write("<td colspan=\"5\" style=\"padding:8px; text-align:right;\">"
                + "<strong>TOTAL:</strong></td>\n");
        htmlWriter.write("<td style=\"padding:8px; text-align:right;\"><strong>"
                + formatAmount(total) + "</strong></td></tr>\n");
        htmlWriter.write("</table>\n<br>\n");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Utility Methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds the customer full name from first, middle, and last name fields.
     *
     * <p>← STRING CUST-FIRST-NAME DELIMITED BY '  ' ... INTO WS-CUST-FULL-NAME.
     * Trims each component and concatenates with single-space separators.</p>
     *
     * @param customer customer record
     * @return full name string, never null
     */
    String buildFullName(Customer customer) {
        StringBuilder sb = new StringBuilder();
        String first = safeStr(customer.getFirstName()).trim();
        String middle = safeStr(customer.getMiddleName()).trim();
        String last = safeStr(customer.getLastName()).trim();
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
     * Writes a line to the text writer, padded or truncated to {@link #LINE_WIDTH}.
     *
     * @param writer text writer
     * @param line   line content
     * @throws IOException if the write fails
     */
    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
    }

    /**
     * Returns the input string or empty string if null (← SPACES in COBOL).
     *
     * @param value input string
     * @return non-null string
     */
    static String safeStr(String value) {
        return value != null ? value : "";
    }

    /**
     * Right-pads or truncates text to the specified width.
     *
     * <p>Matches COBOL PIC X(n) fixed-width field behavior: values shorter than
     * the width are right-padded with spaces; values longer are truncated.</p>
     *
     * @param text  input text
     * @param width target width
     * @return fixed-width string
     */
    static String padRight(String text, int width) {
        if (text == null) {
            return " ".repeat(width);
        }
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    /**
     * Truncates text to the specified maximum length.
     *
     * @param text   input text
     * @param maxLen maximum character length
     * @return truncated string, never null
     */
    static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    /**
     * Formats a {@link BigDecimal} amount with exactly 2 decimal places.
     *
     * <p>← PIC S9(10)V99 display formatting. Uses {@link RoundingMode#HALF_UP}
     * matching COBOL default rounding behavior.</p>
     *
     * @param amount monetary amount, may be null
     * @return formatted string (e.g., "1234.56"), "0.00" if null
     */
    static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Escapes HTML special characters to prevent XSS in generated statements.
     *
     * @param text raw text
     * @return HTML-safe text
     */
    static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
