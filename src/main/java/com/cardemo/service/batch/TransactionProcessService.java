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
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Transaction;
import com.cardemo.entity.TransactionCategoryRef;
import com.cardemo.entity.TransactionTypeRef;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.TransactionCategoryRefRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRefRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Spring {@link Service @Service} that generates the Transaction Detail Report.
 *
 * <p>Faithfully translates COBOL batch program {@code CBTRN03C.cbl} which reads
 * the TRANSACT dataset sequentially, enriches each record via indexed lookups
 * (XREF for account-ID resolution, TRANTYPE for type description, TRANCATG for
 * category description), filters by a date range, groups by card number with
 * account subtotals, and writes a paginated 133-character-wide report with page
 * subtotals and a grand total.</p>
 *
 * <h3>COBOL Paragraph &rarr; Java Method Traceability</h3>
 * <table>
 *   <caption>100% paragraph mapping from CBTRN03C.cbl</caption>
 *   <tr><th>COBOL Paragraph</th><th>Java Method</th></tr>
 *   <tr><td>MAIN (PROCEDURE DIVISION)</td>
 *       <td>{@link #processTransactionReport(String, String, Writer)}</td></tr>
 *   <tr><td>0000-TRANFILE-OPEN &hellip; 0500-DATEPARM-OPEN</td>
 *       <td>N/A &mdash; JPA manages data access</td></tr>
 *   <tr><td>0550-DATEPARM-READ</td>
 *       <td>Parameters of {@code processTransactionReport}</td></tr>
 *   <tr><td>1000-TRANFILE-GET-NEXT</td>
 *       <td>Iterator over {@code findAll()} result in main loop</td></tr>
 *   <tr><td>1100-WRITE-TRANSACTION-REPORT</td>
 *       <td>{@link #writeReport(Transaction, CardXref, TransactionTypeRef,
 *           TransactionCategoryRef, Writer)}</td></tr>
 *   <tr><td>1110-WRITE-PAGE-TOTALS</td>
 *       <td>{@code writePageTotals(Writer)} (private)</td></tr>
 *   <tr><td>1110-WRITE-REPORT-LINE</td>
 *       <td>{@link #writeReportLine(String, Writer)}</td></tr>
 *   <tr><td>1111-WRITE-REPORT-REC</td>
 *       <td>{@link #writeReportRecord(Writer, String)}</td></tr>
 *   <tr><td>1120-WRITE-HEADERS</td>
 *       <td>{@link #writeHeaders(Writer)}</td></tr>
 *   <tr><td>1120-WRITE-DETAIL</td>
 *       <td>{@link #writeDetail(Transaction, String, String, String, Writer)}</td></tr>
 *   <tr><td>1120-WRITE-ACCOUNT-TOTALS</td>
 *       <td>{@code writeAccountTotals(Writer)} (private)</td></tr>
 *   <tr><td>1110-WRITE-GRAND-TOTALS</td>
 *       <td>{@code writeGrandTotals(Writer)} (private)</td></tr>
 *   <tr><td>1500-A-LOOKUP-XREF</td>
 *       <td>{@link #lookupXref(String)}</td></tr>
 *   <tr><td>1500-B-LOOKUP-TRANTYPE</td>
 *       <td>{@link #lookupTransactionType(String)}</td></tr>
 *   <tr><td>1500-C-LOOKUP-TRANCATG</td>
 *       <td>{@link #lookupTransactionCategory(String, String)}</td></tr>
 *   <tr><td>9000&ndash;9500 close paragraphs</td>
 *       <td>N/A &mdash; JPA manages data access</td></tr>
 *   <tr><td>9910-DISPLAY-IO-STATUS</td>
 *       <td>Structured SLF4J error logging</td></tr>
 *   <tr><td>9999-ABEND-PROGRAM</td>
 *       <td>{@code throw new CardDemoException(...)}</td></tr>
 * </table>
 *
 * <h3>Report Layout</h3>
 * <ul>
 *   <li>Line width: 133 characters (PIC X(133) matching COBOL FD-REPTFILE-REC)</li>
 *   <li>Page size: 20 lines (WS-PAGE-SIZE COMP-3 VALUE 20)</li>
 *   <li>Amount format (detail): PIC -ZZZ,ZZZ,ZZZ.ZZ (15 characters)</li>
 *   <li>Amount format (totals): PIC +ZZZ,ZZZ,ZZZ.ZZ (15 characters)</li>
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Working-storage fields ({@code lineCounter},
 * {@code pageTotal}, etc.) are reset at the start of each report generation.
 * Concurrent invocations are not supported &mdash; callers must synchronize
 * externally if needed, consistent with the COBOL single-invocation batch model.</p>
 *
 * @see Transaction
 * @see CardXref
 * @see TransactionTypeRef
 * @see TransactionCategoryRef
 */
@Service
public class TransactionProcessService {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessService.class);

    // ---------------------------------------------------------------
    // Constants matching COBOL report layout from CVTRA07Y.cpy
    // ---------------------------------------------------------------

    /** Report line width — COBOL FD-REPTFILE-REC PIC X(133). */
    private static final int REPORT_LINE_WIDTH = 133;

    /** Lines per page before page break — WS-PAGE-SIZE COMP-3 VALUE 20. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** Report short name — REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'. */
    private static final String REPORT_SHORT_NAME = "DALYREPT";

    /** Report title — REPT-LONG-NAME PIC X(41). */
    private static final String REPORT_LONG_NAME = "Daily Transaction Report";

    /** Date header label — REPT-DATE-HEADER PIC X(12). */
    private static final String DATE_HEADER_LABEL = "Date Range: ";

    /** Date separator — FILLER PIC X(04). */
    private static final String DATE_SEPARATOR = " to ";

    /** Length of the date portion extracted from TRAN-PROC-TS. */
    private static final int DATE_PORTION_LENGTH = 10;

    // ---------------------------------------------------------------
    // Injected repositories (← COBOL file OPEN / keyed READ patterns)
    // ---------------------------------------------------------------

    private final TransactionRepository transactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TransactionTypeRefRepository transactionTypeRefRepository;
    private final TransactionCategoryRefRepository transactionCategoryRefRepository;

    // ---------------------------------------------------------------
    // Working-storage fields (← WS-REPORT-VARS in CBTRN03C.cbl)
    // ---------------------------------------------------------------

    /** WS-FIRST-TIME PIC X VALUE 'Y' — indicates first record being processed. */
    private boolean firstTime;

    /** WS-LINE-COUNTER PIC 9(09) COMP-3 VALUE 0 — current line on the page. */
    private int lineCounter;

    /** WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20 — maximum lines per page. */
    private int pageSize;

    /** WS-PAGE-TOTAL PIC S9(09)V99 — running total for the current page. */
    private BigDecimal pageTotal;

    /** WS-ACCOUNT-TOTAL PIC S9(09)V99 — running total for the current card group. */
    private BigDecimal accountTotal;

    /** WS-GRAND-TOTAL PIC S9(09)V99 — accumulation of all page totals. */
    private BigDecimal grandTotal;

    /** WS-CURR-CARD-NUM PIC X(16) — card number of the current group. */
    private String currentCardNum;

    /** Stored start date for header rendering (← WS-START-DATE PIC X(10)). */
    private String reportStartDate;

    /** Stored end date for header rendering (← WS-END-DATE PIC X(10)). */
    private String reportEndDate;

    // ---------------------------------------------------------------
    // Constructor — dependency injection (← COBOL file OPEN patterns)
    // ---------------------------------------------------------------

    /**
     * Constructs the service with all required repository dependencies.
     *
     * <p>Translates the COBOL file-open paragraphs (0000-TRANFILE-OPEN through
     * 0500-DATEPARM-OPEN) into Spring dependency injection. Each repository
     * replaces a VSAM file opened with ORGANIZATION IS INDEXED or SEQUENTIAL.</p>
     *
     * @param transactionRepository         TRANSACT VSAM sequential file equivalent
     * @param cardXrefRepository            XREF-FILE indexed file equivalent
     * @param transactionTypeRefRepository  TRANTYPE-FILE indexed file equivalent
     * @param transactionCategoryRefRepository TRANCATG-FILE indexed file equivalent
     */
    @Autowired
    public TransactionProcessService(
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            TransactionTypeRefRepository transactionTypeRefRepository,
            TransactionCategoryRefRepository transactionCategoryRefRepository) {
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionTypeRefRepository = transactionTypeRefRepository;
        this.transactionCategoryRefRepository = transactionCategoryRefRepository;
        this.pageSize = DEFAULT_PAGE_SIZE;
    }

    // ===================================================================
    // PUBLIC METHODS — mapped from COBOL paragraphs
    // ===================================================================

    /**
     * Generates the transaction detail report for the specified date range.
     *
     * <p>Translates the PROCEDURE DIVISION (MAIN) of CBTRN03C.cbl:</p>
     * <ol>
     *   <li>Open files &rarr; JPA repository injection</li>
     *   <li>Read date parameters (0550-DATEPARM-READ) &rarr; method parameters</li>
     *   <li>Sequential read loop (1000-TRANFILE-GET-NEXT) &rarr; {@code findAll()} iteration</li>
     *   <li>Date filter: {@code TRAN-PROC-TS(1:10) >= startDate AND <= endDate}</li>
     *   <li>Card-number grouping with account subtotals</li>
     *   <li>Enrichment lookups: XREF, TRANTYPE, TRANCATG</li>
     *   <li>Write paginated report with page/account/grand totals</li>
     * </ol>
     *
     * @param startDate    report start date in {@code YYYY-MM-DD} format
     *                     (from WS-START-DATE PIC X(10))
     * @param endDate      report end date in {@code YYYY-MM-DD} format
     *                     (from WS-END-DATE PIC X(10))
     * @param reportWriter output writer for the 133-character-wide report
     * @throws CardDemoException if date parameters are invalid, lookup fails
     *         (INVALID KEY), or a write error occurs
     */
    public void processTransactionReport(String startDate, String endDate,
                                         Writer reportWriter) {
        log.info("START OF EXECUTION OF PROGRAM CBTRN03C");

        // Validate date parameters (← 0550-DATEPARM-READ error handling)
        if (startDate == null || endDate == null) {
            log.error("ERROR READING DATEPARM FILE");
            throw new CardDemoException(
                    "Date parameters are required: startDate and endDate must not be null");
        }
        if (reportWriter == null) {
            log.error("ERROR OPENING REPTFILE");
            throw new CardDemoException("Report writer must not be null");
        }

        log.info("Reporting from {} to {}", startDate, endDate);

        // Reset working storage (← WS-REPORT-VARS initialization)
        resetWorkingStorage(startDate, endDate);

        // Read all transactions sequentially (← 0000-TRANFILE-OPEN + 1000-TRANFILE-GET-NEXT)
        // Create a mutable copy since findAll() may return an unmodifiable list.
        List<Transaction> transactions = new ArrayList<>(
                transactionRepository.findAll());

        // Sort by card number for proper grouping, then by tran ID within each group.
        // The COBOL program assumes the TRANSACT file is pre-sorted by card number
        // (via the COMBTRAN JCL sort step). Explicit sorting ensures identical grouping.
        transactions.sort(Comparator.comparing(
                (Transaction t) -> t.getCardNum() != null ? t.getCardNum() : "")
                .thenComparing(t -> t.getTranId() != null ? t.getTranId() : ""));

        // Cached XREF result — only re-looked-up when card number changes
        // (← COBOL performs XREF lookup once per card-number change, not per record)
        CardXref currentXref = null;

        // Main processing loop (← PERFORM UNTIL END-OF-FILE = 'Y')
        for (Transaction tran : transactions) {

            // Date filtering (← IF TRAN-PROC-TS(1:10) >= WS-START-DATE
            //                      AND TRAN-PROC-TS(1:10) <= WS-END-DATE)
            String procDate = extractDatePortion(tran.getProcTimestamp());
            if (procDate.compareTo(startDate) < 0
                    || procDate.compareTo(endDate) > 0) {
                continue; // Skip — equivalent to COBOL NEXT SENTENCE on date mismatch
            }

            log.debug("Processing transaction record: {}", tran.getTranId());

            // Card-number grouping (← IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM)
            String tranCardNum = tran.getCardNum() != null ? tran.getCardNum() : "";
            if (!currentCardNum.equals(tranCardNum)) {
                if (!firstTime) {
                    // Write account subtotal for the previous card group
                    // (← PERFORM 1120-WRITE-ACCOUNT-TOTALS)
                    writeAccountTotals(reportWriter);
                }
                currentCardNum = tranCardNum;
                // XREF lookup on card-number change only
                // (← MOVE TRAN-CARD-NUM TO FD-XREF-CARD-NUM; PERFORM 1500-A-LOOKUP-XREF)
                currentXref = lookupXref(tranCardNum);
            }

            // Type lookup (← MOVE TRAN-TYPE-CD TO FD-TRAN-TYPE; PERFORM 1500-B-LOOKUP-TRANTYPE)
            TransactionTypeRef typeRef = lookupTransactionType(tran.getTypeCode());

            // Category lookup (← MOVE keys; PERFORM 1500-C-LOOKUP-TRANCATG)
            String catCode = tran.getCategoryCode() != null
                    ? String.valueOf(tran.getCategoryCode()) : "0";
            TransactionCategoryRef catRef = lookupTransactionCategory(
                    tran.getTypeCode(), catCode);

            // Write report line (← PERFORM 1100-WRITE-TRANSACTION-REPORT)
            writeReport(tran, currentXref, typeRef, catRef, reportWriter);
        }

        // End-of-file processing: write final page totals and grand total
        // (← COBOL EOF branch: PERFORM 1110-WRITE-PAGE-TOTALS; 1110-WRITE-GRAND-TOTALS)
        if (!firstTime) {
            writePageTotals(reportWriter);
            writeGrandTotals(reportWriter);
        }

        log.info("END OF EXECUTION OF PROGRAM CBTRN03C");
    }

    /**
     * Looks up the card cross-reference record by card number.
     *
     * <p>Translates CBTRN03C.cbl paragraph <strong>1500-A-LOOKUP-XREF</strong>:</p>
     * <pre>
     * READ XREF-FILE INTO CARD-XREF-RECORD
     *    INVALID KEY
     *       DISPLAY 'INVALID CARD NUMBER : ' FD-XREF-CARD-NUM
     *       PERFORM 9910-DISPLAY-IO-STATUS
     *       PERFORM 9999-ABEND-PROGRAM
     * END-READ
     * </pre>
     *
     * @param cardNum the 16-character card number (TRAN-CARD-NUM / FD-XREF-CARD-NUM)
     * @return the resolved {@link CardXref} record
     * @throws CardDemoException if no XREF exists for the card number (INVALID KEY)
     */
    public CardXref lookupXref(String cardNum) {
        return cardXrefRepository.findByXrefCardNum(cardNum)
                .orElseThrow(() -> {
                    log.error("INVALID CARD NUMBER: {}", cardNum);
                    return new CardDemoException("INVALID CARD NUMBER: " + cardNum);
                });
    }

    /**
     * Looks up the transaction type reference by type code.
     *
     * <p>Translates CBTRN03C.cbl paragraph <strong>1500-B-LOOKUP-TRANTYPE</strong>:</p>
     * <pre>
     * READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD
     *    INVALID KEY
     *       DISPLAY 'INVALID TRANSACTION TYPE : ' FD-TRAN-TYPE
     *       PERFORM 9910-DISPLAY-IO-STATUS
     *       PERFORM 9999-ABEND-PROGRAM
     * END-READ
     * </pre>
     *
     * @param typeCode the 2-character transaction type code (TRAN-TYPE-CD)
     * @return the resolved {@link TransactionTypeRef} record
     * @throws CardDemoException if no type exists for the code (INVALID KEY)
     */
    public TransactionTypeRef lookupTransactionType(String typeCode) {
        Optional<TransactionTypeRef> result =
                transactionTypeRefRepository.findById(typeCode != null ? typeCode : "");
        if (!result.isPresent()) {
            log.error("INVALID TRANSACTION TYPE: {}", typeCode);
            throw new CardDemoException("INVALID TRANSACTION TYPE: " + typeCode);
        }
        return result.get();
    }

    /**
     * Looks up the transaction category reference by composite key (type + category).
     *
     * <p>Translates CBTRN03C.cbl paragraph <strong>1500-C-LOOKUP-TRANCATG</strong>:</p>
     * <pre>
     * READ TRANCATG-FILE INTO TRAN-CAT-RECORD
     *    INVALID KEY
     *       DISPLAY 'INVALID TRAN CATG KEY : ' FD-TRAN-CAT-KEY
     *       PERFORM 9910-DISPLAY-IO-STATUS
     *       PERFORM 9999-ABEND-PROGRAM
     * END-READ
     * </pre>
     *
     * @param typeCode the 2-character transaction type code (FD-TRAN-TYPE-CD)
     * @param catCode  the category code as a string (FD-TRAN-CAT-CD PIC 9(04))
     * @return the resolved {@link TransactionCategoryRef} record
     * @throws CardDemoException if no category exists for the composite key (INVALID KEY)
     */
    public TransactionCategoryRef lookupTransactionCategory(String typeCode,
                                                            String catCode) {
        Integer categoryCode;
        try {
            categoryCode = Integer.parseInt(catCode);
        } catch (NumberFormatException e) {
            log.error("INVALID TRAN CATG KEY: {}{}", typeCode, catCode);
            throw new CardDemoException(
                    "INVALID TRAN CATG KEY: " + typeCode + catCode, e);
        }

        TransactionCategoryRef.TransactionCategoryRefId compositeKey =
                new TransactionCategoryRef.TransactionCategoryRefId(
                        typeCode != null ? typeCode : "", categoryCode);

        Optional<TransactionCategoryRef> result =
                transactionCategoryRefRepository.findById(compositeKey);
        if (result.isPresent()) {
            return result.get();
        }

        // Provide enhanced diagnostics using findByTypeCode — reports how many
        // categories exist for the given type (mirrors COBOL 9910-DISPLAY-IO-STATUS
        // diagnostic output to SYSOUT before 9999-ABEND-PROGRAM)
        List<TransactionCategoryRef> typeCategories =
                transactionCategoryRefRepository.findByTypeCode(
                        typeCode != null ? typeCode : "");
        log.error("INVALID TRAN CATG KEY: {} {} (type '{}' has {} known categories)",
                typeCode, catCode, typeCode, typeCategories.size());
        throw new CardDemoException(
                "INVALID TRAN CATG KEY: " + typeCode + catCode);
    }

    /**
     * Writes one transaction to the report with headers and page-overflow handling.
     *
     * <p>Translates CBTRN03C.cbl paragraph
     * <strong>1100-WRITE-TRANSACTION-REPORT</strong>:</p>
     * <ol>
     *   <li>First-time check &rarr; write headers</li>
     *   <li>Page-overflow check (MOD line counter / page size = 0) &rarr;
     *       write page totals + new headers</li>
     *   <li>Accumulate {@code TRAN-AMT} into page and account totals</li>
     *   <li>Write detail line via {@link #writeDetail}</li>
     * </ol>
     *
     * @param tran   the current transaction record
     * @param xref   the resolved XREF for the transaction's card number
     * @param type   the resolved transaction type reference
     * @param cat    the resolved transaction category reference
     * @param writer the report output writer
     */
    public void writeReport(Transaction tran, CardXref xref,
                            TransactionTypeRef type, TransactionCategoryRef cat,
                            Writer writer) {
        // First-time initialization (← IF WS-FIRST-TIME = 'Y')
        if (firstTime) {
            firstTime = false;
            writeHeaders(writer);
        }

        // Page-overflow check
        // (← IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0)
        if (lineCounter > 0 && lineCounter % pageSize == 0) {
            writePageTotals(writer);
            writeHeaders(writer);
        }

        // Accumulate transaction amount into page and account totals
        // (← ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL)
        BigDecimal tranAmt = tran.getAmount() != null
                ? tran.getAmount() : BigDecimal.ZERO;
        pageTotal = pageTotal.add(tranAmt);
        accountTotal = accountTotal.add(tranAmt);

        // Extract display values from enrichment entities
        String acctId = xref != null ? xref.getAccountId() : "";
        String typeDesc = type != null ? type.getTypeDescription() : "";
        String catDesc = cat != null ? cat.getCategoryDescription() : "";

        // Diagnostic logging — uses all accessed members from enrichment entities
        // (mirrors COBOL DISPLAY TRAN-RECORD statement in the main loop)
        if (log.isDebugEnabled()) {
            log.debug("Detail: tran={}, card={}, acct={}, type={}[{}], cat={}[{}]",
                    tran.getTranId(),
                    xref != null ? xref.getXrefCardNum() : "",
                    acctId,
                    tran.getTypeCode(), typeDesc,
                    cat != null ? cat.getTypeCode() + "-"
                            + cat.getCategoryCode() : "",
                    catDesc);
        }

        // Write detail line (← PERFORM 1120-WRITE-DETAIL)
        writeDetail(tran, acctId, typeDesc, catDesc, writer);
    }

    /**
     * Writes a single report line and increments the line counter.
     *
     * <p>Combines the COBOL pattern of writing a record via
     * {@code 1111-WRITE-REPORT-REC} followed by
     * {@code ADD 1 TO WS-LINE-COUNTER}.</p>
     *
     * @param line   the report line content (will be right-padded to 133 characters)
     * @param writer the report output writer
     * @throws CardDemoException if a write error occurs
     */
    public void writeReportLine(String line, Writer writer) {
        writeReportRecord(writer, padRight(line, REPORT_LINE_WIDTH));
        lineCounter++;
    }

    /**
     * Writes a single record to the report file and validates the write status.
     *
     * <p>Translates CBTRN03C.cbl paragraph <strong>1111-WRITE-REPORT-REC</strong>:</p>
     * <pre>
     * WRITE FD-REPTFILE-REC
     * IF TRANREPT-STATUS NOT = '00'
     *    DISPLAY 'ERROR WRITING REPTFILE'
     *    PERFORM 9910-DISPLAY-IO-STATUS
     *    PERFORM 9999-ABEND-PROGRAM
     * END-IF
     * </pre>
     *
     * @param writer the report output writer
     * @param record the complete record string to write
     * @throws CardDemoException if the write operation fails (maps to ABEND)
     */
    public void writeReportRecord(Writer writer, String record) {
        try {
            writer.write(record);
            writer.write(System.lineSeparator());
        } catch (IOException e) {
            log.error("ERROR WRITING REPTFILE");
            displayIoStatus(e);
            throw new CardDemoException("ERROR WRITING REPTFILE", e);
        }
    }

    /**
     * Writes the multi-line report header block.
     *
     * <p>Translates CBTRN03C.cbl paragraph <strong>1120-WRITE-HEADERS</strong>
     * which outputs four lines:</p>
     * <ol>
     *   <li>Report name header (REPORT-NAME-HEADER from CVTRA07Y.cpy)</li>
     *   <li>Blank line (WS-BLANK-LINE PIC X(133) VALUE SPACES)</li>
     *   <li>Column header (TRANSACTION-HEADER-1)</li>
     *   <li>Separator line (TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-')</li>
     * </ol>
     *
     * @param writer the report output writer
     */
    public void writeHeaders(Writer writer) {
        // Report name header (← MOVE REPORT-NAME-HEADER TO FD-REPTFILE-REC)
        writeReportLine(buildReportNameHeader(), writer);

        // Blank line (← MOVE WS-BLANK-LINE TO FD-REPTFILE-REC)
        writeReportLine("", writer);

        // Column header (← MOVE TRANSACTION-HEADER-1 TO FD-REPTFILE-REC)
        writeReportLine(buildColumnHeader(), writer);

        // Separator (← MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC)
        writeReportLine(buildSeparatorLine(), writer);
    }

    /**
     * Builds and writes a detail line for one transaction.
     *
     * <p>Translates CBTRN03C.cbl paragraph <strong>1120-WRITE-DETAIL</strong>,
     * constructing the TRANSACTION-DETAIL-REPORT record layout from CVTRA07Y.cpy:</p>
     * <pre>
     * TRAN-REPORT-TRANS-ID(16) | SP(1) | ACCOUNT-ID(11) | SP(1) |
     * TYPE-CD(2) | '-' | TYPE-DESC(15) | SP(1) | CAT-CD(4) | '-' |
     * CAT-DESC(29) | SP(1) | SOURCE(10) | SP(4) | AMOUNT(15) | SP(2)
     * </pre>
     *
     * @param tran     the transaction record
     * @param acctId   the resolved account ID from XREF (XREF-ACCT-ID)
     * @param typeDesc the resolved type description (TRAN-TYPE-DESC)
     * @param catDesc  the resolved category description (TRAN-CAT-TYPE-DESC)
     * @param writer   the report output writer
     */
    public void writeDetail(Transaction tran, String acctId,
                            String typeDesc, String catDesc, Writer writer) {
        // Build detail line matching TRANSACTION-DETAIL-REPORT layout (CVTRA07Y.cpy)
        StringBuilder detail = new StringBuilder(REPORT_LINE_WIDTH);

        // TRAN-REPORT-TRANS-ID PIC X(16)
        detail.append(padRight(tran.getTranId(), 16));
        // FILLER PIC X(01) VALUE SPACES
        detail.append(' ');
        // TRAN-REPORT-ACCOUNT-ID PIC X(11)
        detail.append(padRight(acctId, 11));
        // FILLER PIC X(01) VALUE SPACES
        detail.append(' ');
        // TRAN-REPORT-TYPE-CD PIC X(02)
        detail.append(padRight(tran.getTypeCode(), 2));
        // FILLER PIC X(01) VALUE '-'
        detail.append('-');
        // TRAN-REPORT-TYPE-DESC PIC X(15)
        detail.append(padRight(typeDesc, 15));
        // FILLER PIC X(01) VALUE SPACES
        detail.append(' ');
        // TRAN-REPORT-CAT-CD PIC 9(04)
        detail.append(formatCategoryCode(tran.getCategoryCode()));
        // FILLER PIC X(01) VALUE '-'
        detail.append('-');
        // TRAN-REPORT-CAT-DESC PIC X(29)
        detail.append(padRight(catDesc, 29));
        // FILLER PIC X(01) VALUE SPACES
        detail.append(' ');
        // TRAN-REPORT-SOURCE PIC X(10)
        detail.append(padRight(tran.getSource(), 10));
        // FILLER PIC X(04) VALUE SPACES
        detail.append("    ");
        // TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ
        detail.append(formatCobolAmount(tran.getAmount(), false));
        // FILLER PIC X(02) VALUE SPACES
        detail.append("  ");

        writeReportLine(detail.toString(), writer);
    }

    // ===================================================================
    // PRIVATE METHODS — internal report mechanics
    // ===================================================================

    /**
     * Resets all working-storage fields to their COBOL initial values.
     *
     * @param startDate the report start date for header rendering
     * @param endDate   the report end date for header rendering
     */
    private void resetWorkingStorage(String startDate, String endDate) {
        firstTime = true;           // WS-FIRST-TIME VALUE 'Y'
        lineCounter = 0;            // WS-LINE-COUNTER VALUE 0
        pageSize = DEFAULT_PAGE_SIZE; // WS-PAGE-SIZE VALUE 20
        pageTotal = BigDecimal.ZERO;  // WS-PAGE-TOTAL VALUE 0
        accountTotal = BigDecimal.ZERO; // WS-ACCOUNT-TOTAL VALUE 0
        grandTotal = BigDecimal.ZERO;   // WS-GRAND-TOTAL VALUE 0
        currentCardNum = "";        // WS-CURR-CARD-NUM VALUE SPACES
        reportStartDate = startDate;
        reportEndDate = endDate;
    }

    /**
     * Writes the page total line and a separator, accumulates into grand total.
     *
     * <p>Translates CBTRN03C.cbl paragraph <strong>1110-WRITE-PAGE-TOTALS</strong>:</p>
     * <pre>
     * MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL
     * WRITE FD-REPTFILE-REC
     * ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL
     * MOVE 0 TO WS-PAGE-TOTAL
     * ADD 1 TO WS-LINE-COUNTER
     * WRITE TRANSACTION-HEADER-2
     * ADD 1 TO WS-LINE-COUNTER
     * </pre>
     *
     * @param writer the report output writer
     */
    private void writePageTotals(Writer writer) {
        // Write page total line (← MOVE REPORT-PAGE-TOTALS TO FD-REPTFILE-REC)
        String totalLine = buildPageTotalLine(pageTotal);
        writeReportRecord(writer, padRight(totalLine, REPORT_LINE_WIDTH));

        // Accumulate into grand total, then reset page total
        // (← ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL; MOVE 0 TO WS-PAGE-TOTAL)
        grandTotal = grandTotal.add(pageTotal);
        pageTotal = BigDecimal.ZERO;
        lineCounter++;

        // Write separator line (← MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC)
        writeReportRecord(writer, buildSeparatorLine());
        lineCounter++;
    }

    /**
     * Writes the account subtotal line and a separator, resets account total.
     *
     * <p>Translates CBTRN03C.cbl paragraph
     * <strong>1120-WRITE-ACCOUNT-TOTALS</strong>:</p>
     * <pre>
     * MOVE WS-ACCOUNT-TOTAL TO REPT-ACCOUNT-TOTAL
     * WRITE FD-REPTFILE-REC
     * MOVE 0 TO WS-ACCOUNT-TOTAL
     * ADD 1 TO WS-LINE-COUNTER
     * WRITE TRANSACTION-HEADER-2
     * ADD 1 TO WS-LINE-COUNTER
     * </pre>
     *
     * @param writer the report output writer
     */
    private void writeAccountTotals(Writer writer) {
        // Write account total line
        String totalLine = buildAccountTotalLine(accountTotal);
        writeReportRecord(writer, padRight(totalLine, REPORT_LINE_WIDTH));

        // Reset account total
        accountTotal = BigDecimal.ZERO;
        lineCounter++;

        // Write separator
        writeReportRecord(writer, buildSeparatorLine());
        lineCounter++;
    }

    /**
     * Writes the grand total line (final line of the report).
     *
     * <p>Translates CBTRN03C.cbl paragraph
     * <strong>1110-WRITE-GRAND-TOTALS</strong>:</p>
     * <pre>
     * MOVE WS-GRAND-TOTAL TO REPT-GRAND-TOTAL
     * WRITE FD-REPTFILE-REC
     * </pre>
     *
     * <p>No line counter increment — this is the last line of the report.</p>
     *
     * @param writer the report output writer
     */
    private void writeGrandTotals(Writer writer) {
        String totalLine = buildGrandTotalLine(grandTotal);
        writeReportRecord(writer, padRight(totalLine, REPORT_LINE_WIDTH));
    }

    // ===================================================================
    // PRIVATE HELPERS — report line construction
    // ===================================================================

    /**
     * Extracts the date portion (first 10 characters) from a timestamp string.
     *
     * <p>Mirrors the COBOL reference string operation
     * {@code TRAN-PROC-TS(1:10)} which extracts the {@code YYYY-MM-DD}
     * prefix from the 26-character ISO-8601 extended timestamp.</p>
     *
     * @param timestamp the full 26-character timestamp, or {@code null}
     * @return the first 10 characters, or empty string if timestamp is
     *         {@code null} or too short
     */
    private String extractDatePortion(String timestamp) {
        if (timestamp == null || timestamp.length() < DATE_PORTION_LENGTH) {
            return "";
        }
        return timestamp.substring(0, DATE_PORTION_LENGTH);
    }

    /**
     * Builds the report name header line.
     *
     * <p>Matches CVTRA07Y.cpy REPORT-NAME-HEADER layout:</p>
     * <pre>
     * REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'
     * REPT-LONG-NAME  PIC X(41) VALUE 'Daily Transaction Report'
     * REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: '
     * REPT-START-DATE PIC X(10)
     * FILLER PIC X(04) VALUE ' to '
     * REPT-END-DATE PIC X(10)
     * </pre>
     *
     * @return the formatted header string (115 characters before padding)
     */
    private String buildReportNameHeader() {
        StringBuilder header = new StringBuilder(REPORT_LINE_WIDTH);
        header.append(padRight(REPORT_SHORT_NAME, 38));
        header.append(padRight(REPORT_LONG_NAME, 41));
        header.append(padRight(DATE_HEADER_LABEL, 12));
        header.append(padRight(reportStartDate, 10));
        header.append(DATE_SEPARATOR);
        header.append(padRight(reportEndDate, 10));
        return header.toString();
    }

    /**
     * Builds the column header line.
     *
     * <p>Matches CVTRA07Y.cpy TRANSACTION-HEADER-1 layout:</p>
     * <pre>
     * 'Transaction ID'  PIC X(17)
     * 'Account ID'      PIC X(12)
     * 'Transaction Type' PIC X(19)
     * 'Tran Category'   PIC X(35)
     * 'Tran Source'      PIC X(14)
     * SPACES            PIC X
     * '        Amount'  PIC X(16)
     * </pre>
     *
     * @return the formatted column header string (114 characters before padding)
     */
    private String buildColumnHeader() {
        StringBuilder header = new StringBuilder(REPORT_LINE_WIDTH);
        header.append(padRight("Transaction ID", 17));
        header.append(padRight("Account ID", 12));
        header.append(padRight("Transaction Type", 19));
        header.append(padRight("Tran Category", 35));
        header.append(padRight("Tran Source", 14));
        header.append(' ');
        header.append(padRight("        Amount", 16));
        return header.toString();
    }

    /**
     * Builds the separator line (all dashes).
     *
     * <p>Matches CVTRA07Y.cpy TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'.</p>
     *
     * @return a 133-character string of dashes
     */
    private String buildSeparatorLine() {
        return "-".repeat(REPORT_LINE_WIDTH);
    }

    /**
     * Builds the page total line.
     *
     * <p>Matches CVTRA07Y.cpy REPORT-PAGE-TOTALS:</p>
     * <pre>
     * 'Page Total' PIC X(11)
     * ALL '.'      PIC X(86)
     * total        PIC +ZZZ,ZZZ,ZZZ.ZZ
     * </pre>
     *
     * @param total the page total amount
     * @return the formatted page total string (112 characters before padding)
     */
    private String buildPageTotalLine(BigDecimal total) {
        StringBuilder line = new StringBuilder(REPORT_LINE_WIDTH);
        line.append(padRight("Page Total", 11));
        line.append(".".repeat(86));
        line.append(formatCobolAmount(total, true));
        return line.toString();
    }

    /**
     * Builds the account total line.
     *
     * <p>Matches CVTRA07Y.cpy REPORT-ACCOUNT-TOTALS:</p>
     * <pre>
     * 'Account Total' PIC X(13)
     * ALL '.'         PIC X(84)
     * total           PIC +ZZZ,ZZZ,ZZZ.ZZ
     * </pre>
     *
     * @param total the account total amount
     * @return the formatted account total string (112 characters before padding)
     */
    private String buildAccountTotalLine(BigDecimal total) {
        StringBuilder line = new StringBuilder(REPORT_LINE_WIDTH);
        line.append(padRight("Account Total", 13));
        line.append(".".repeat(84));
        line.append(formatCobolAmount(total, true));
        return line.toString();
    }

    /**
     * Builds the grand total line.
     *
     * <p>Matches CVTRA07Y.cpy REPORT-GRAND-TOTALS:</p>
     * <pre>
     * 'Grand Total' PIC X(11)
     * ALL '.'       PIC X(86)
     * total         PIC +ZZZ,ZZZ,ZZZ.ZZ
     * </pre>
     *
     * @param total the grand total amount
     * @return the formatted grand total string (112 characters before padding)
     */
    private String buildGrandTotalLine(BigDecimal total) {
        StringBuilder line = new StringBuilder(REPORT_LINE_WIDTH);
        line.append(padRight("Grand Total", 11));
        line.append(".".repeat(86));
        line.append(formatCobolAmount(total, true));
        return line.toString();
    }

    /**
     * Formats a {@link BigDecimal} amount in COBOL-compatible edited numeric format.
     *
     * <p>Replicates the behaviour of COBOL PIC editing for monetary report fields:</p>
     * <ul>
     *   <li>{@code showPlusSign = false}: PIC -ZZZ,ZZZ,ZZZ.ZZ &mdash;
     *       shows '-' for negative, space for non-negative
     *       (used for detail line TRAN-REPORT-AMT)</li>
     *   <li>{@code showPlusSign = true}: PIC +ZZZ,ZZZ,ZZZ.ZZ &mdash;
     *       shows '+' for non-negative, '-' for negative
     *       (used for page/account/grand total amounts)</li>
     * </ul>
     *
     * <p>All arithmetic uses {@link BigDecimal} with {@link RoundingMode#HALF_UP}
     * matching COBOL default rounding. The resulting string is exactly 15 characters.</p>
     *
     * @param amount       the monetary amount to format (may be {@code null})
     * @param showPlusSign {@code true} for '+' on positive (total lines),
     *                     {@code false} for space on positive (detail lines)
     * @return a 15-character formatted amount string
     */
    private String formatCobolAmount(BigDecimal amount, boolean showPlusSign) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);

        // Determine sign character
        char sign;
        if (amount.signum() < 0) {
            sign = '-';
        } else {
            sign = showPlusSign ? '+' : ' ';
        }

        // Extract integer and decimal parts from the absolute value
        BigDecimal absVal = amount.abs();
        long intPart = absVal.longValue();
        int decPart = absVal.subtract(BigDecimal.valueOf(intPart))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();

        // Format number string with commas (matching COBOL Z editing with commas)
        String numStr;
        if (intPart == 0) {
            // COBOL Z editing suppresses all leading zeros including the units digit
            numStr = "." + String.format(Locale.US, "%02d", decPart);
        } else {
            String intFormatted = String.format(Locale.US, "%,d", intPart);
            numStr = intFormatted + "." + String.format(Locale.US, "%02d", decPart);
        }

        // Right-justify number in 14 characters, prepend sign → total 15 chars
        return String.format(Locale.US, "%c%14s", sign, numStr);
    }

    /**
     * Formats a category code as a 4-digit zero-padded string.
     *
     * <p>Matches COBOL PIC 9(04) display format for TRAN-REPORT-CAT-CD
     * which always shows 4 digits with leading zeros.</p>
     *
     * @param categoryCode the category code integer, or {@code null}
     * @return the 4-character zero-padded string (e.g., "0001")
     */
    private String formatCategoryCode(Integer categoryCode) {
        if (categoryCode == null) {
            return "0000";
        }
        return String.format(Locale.US, "%04d", categoryCode);
    }

    /**
     * Pads or truncates a string to the specified width (right-padded with spaces).
     *
     * <p>Replicates COBOL MOVE semantics where a shorter source is right-padded
     * with spaces to fill the target PIC X(n) field, and a longer source is
     * truncated on the right.</p>
     *
     * @param str    the input string (may be {@code null})
     * @param length the target field width
     * @return a string of exactly {@code length} characters
     */
    private String padRight(String str, int length) {
        if (str == null) {
            str = "";
        }
        if (str.length() >= length) {
            return str.substring(0, length);
        }
        return String.format(Locale.US, "%-" + length + "s", str);
    }

    /**
     * Logs I/O error diagnostics.
     *
     * <p>Translates CBTRN03C.cbl paragraph <strong>9910-DISPLAY-IO-STATUS</strong>
     * which formats and displays the two-byte VSAM file status code to SYSOUT.
     * In Java, the {@link IOException} message and stack trace provide equivalent
     * diagnostic information via structured SLF4J logging.</p>
     *
     * @param e the I/O exception to log
     */
    private void displayIoStatus(IOException e) {
        log.error("FILE STATUS IS: {}", e.getMessage(), e);
    }
}
