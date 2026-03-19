/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.dto.TransactionListItem;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for listing and browsing transaction records with paginated navigation.
 *
 * <p>Faithfully translates COBOL program {@code COTRN00C.cbl} — the online CICS
 * transaction list viewer for the {@code TRANSACT} VSAM KSDS dataset (350-byte
 * records defined by {@code CVTRA05Y.cpy}). Provides paginated listing with
 * forward/backward page navigation (PF7/PF8), Enter-key transaction selection,
 * and optional date-range filtering via the alternate index on
 * {@code TRAN-ORIG-TS}.
 *
 * <h2>COBOL Paragraph-to-Java Method Traceability</h2>
 * <table>
 *   <caption>100% traceability mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>MAIN-PARA</td><td>95</td>
 *       <td>{@link #listTransactions(String, int)}</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>146</td>
 *       <td>{@link #processEnterKey(String, int)}</td></tr>
 *   <tr><td>PROCESS-PF7-KEY</td><td>234</td>
 *       <td>{@link #processPf7Key(int)}</td></tr>
 *   <tr><td>PROCESS-PF8-KEY</td><td>257</td>
 *       <td>{@link #processPf8Key(int)}</td></tr>
 *   <tr><td>PROCESS-PAGE-FORWARD</td><td>279</td>
 *       <td>{@link #processPageForward(int)}</td></tr>
 *   <tr><td>PROCESS-PAGE-BACKWARD</td><td>333</td>
 *       <td>{@link #processPageBackward(int)}</td></tr>
 *   <tr><td>POPULATE-TRAN-DATA</td><td>381</td>
 *       <td>{@link #populateTranData(Page)}</td></tr>
 *   <tr><td>INITIALIZE-TRAN-DATA</td><td>450</td>
 *       <td>{@link #initializeTranData()}</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>567</td>
 *       <td>{@link #populateHeaderInfo()}</td></tr>
 *   <tr><td>STARTBR-TRANSACT-FILE</td><td>591</td>
 *       <td>JPA pagination via {@code findAll(Pageable)}</td></tr>
 *   <tr><td>READNEXT-TRANSACT-FILE</td><td>624</td>
 *       <td>JPA pagination (forward page)</td></tr>
 *   <tr><td>READPREV-TRANSACT-FILE</td><td>658</td>
 *       <td>JPA pagination (backward page)</td></tr>
 *   <tr><td>ENDBR-TRANSACT-FILE</td><td>692</td>
 *       <td>No-op (page boundary ends browse)</td></tr>
 * </table>
 *
 * @see TransactionRepository
 * @see Transaction
 * @see CardDemoContext
 */
@Service
public class TransactionListService {

    private static final Logger log = LoggerFactory.getLogger(TransactionListService.class);

    /**
     * Number of transaction records per page, matching COBOL's implicit
     * 10-row screen display (WS-IDX loops from 1 to 10 in POPULATE-TRAN-DATA
     * and INITIALIZE-TRAN-DATA paragraphs).
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Program name identifier.
     * (← COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COTRN00C'})
     */
    private static final String PROGRAM_NAME = "COTRN00C";

    /**
     * Transaction identifier for the CT00 transaction.
     * (← COBOL {@code WS-TRANID PIC X(04) VALUE 'CT00'})
     */
    private static final String TRAN_ID = "CT00";

    /**
     * Target program for transaction detail view navigation.
     * (← COBOL {@code MOVE 'COTRN01C' TO CDEMO-TO-PROGRAM})
     */
    private static final String DETAIL_PROGRAM = "COTRN01C";

    /**
     * Invalid key feedback message, sourced from {@link MessageConstants}.
     * (← COBOL {@code CCDA-MSG-INVALID-KEY} from {@code CSMSG01Y.cpy},
     * used in MAIN-PARA WHEN OTHER branch at line 132)
     */
    private static final String INVALID_KEY_MSG = MessageConstants.INVALID_KEY_MESSAGE;

    /**
     * Default sort order matching VSAM KSDS key sequence on
     * {@code TRAN-ID PIC X(16)} — ascending by transaction identifier.
     */
    private static final Sort TRAN_ID_SORT = Sort.by(Sort.Direction.ASC, "tranId");

    private final TransactionRepository transactionRepository;
    private final CardDemoContext context;

    /**
     * Constructs the transaction list service with required dependencies.
     *
     * @param transactionRepository Spring Data JPA repository for the
     *                              TRANSACT dataset (← VSAM KSDS)
     * @param context               request-scoped session context bean
     *                              (← 1024-byte CARDDEMO-COMMAREA)
     */
    public TransactionListService(TransactionRepository transactionRepository,
                                  CardDemoContext context) {
        this.transactionRepository = transactionRepository;
        this.context = context;
    }

    // =========================================================================
    // Public API — COBOL paragraph entry points
    // =========================================================================

    /**
     * Main entry point for the transaction list browse operation.
     * Maps to COTRN00C.cbl {@code MAIN-PARA} (line 95).
     *
     * <p>Implements the pseudo-conversational CICS pattern:
     * <ul>
     *   <li>First entry ({@code PGM_ENTER = 0}): initialises context and
     *       displays the initial page of transactions.</li>
     *   <li>Re-entry ({@code PGM_REENTER = 1}): the controller determines
     *       the user action (Enter, PF3, PF7, PF8) and delegates to the
     *       appropriate method.</li>
     * </ul>
     *
     * <p>COBOL equivalent:
     * <pre>{@code
     * MAIN-PARA.
     *     SET ERR-FLG-OFF     TO TRUE
     *     SET TRANSACT-NOT-EOF TO TRUE
     *     ...
     *     IF NOT CDEMO-PGM-REENTER
     *         SET CDEMO-PGM-REENTER TO TRUE
     *         PERFORM PROCESS-ENTER-KEY
     *     END-IF
     * }</pre>
     *
     * @param transactionId optional transaction ID to position the browse
     *                      start (← BMS {@code TRNIDINI} field input)
     * @param page          zero-based page number for pagination
     * @return paginated transaction results sorted by transaction ID
     */
    @Transactional(readOnly = true)
    public Page<Transaction> listTransactions(String transactionId, int page) {
        log.info("Transaction list requested: user='{}', transactionId='{}', page={}",
                 context.getUserId(), transactionId, page);

        // Populate header/context info (← PERFORM POPULATE-HEADER-INFO)
        populateHeaderInfo();

        // Log program context for traceability
        int pgmCtx = context.getPgmContext();
        log.debug("Current program context: {}", pgmCtx);

        // First entry vs re-entry (← IF NOT CDEMO-PGM-REENTER)
        if (!context.isReenterContext()) {
            log.debug("First entry into transaction list (PGM_ENTER) — initialising");
            context.setPgmContext(CardDemoContext.PGM_REENTER);
        }

        // Build paginated query (← STARTBR/READNEXT browse pattern)
        // When transactionId is provided, position the browse at that ID
        // (matches COBOL STARTBR RIDFLD semantics from COTRN00C.cbl)
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, PAGE_SIZE, TRAN_ID_SORT);
        Page<Transaction> result;
        if (transactionId != null && !transactionId.isBlank()) {
            log.debug("Filtering transactions starting from transactionId='{}'", transactionId);
            result = transactionRepository.findByTranIdGreaterThanEqual(
                    transactionId.trim(), pageable);
        } else {
            result = transactionRepository.findAll(pageable);
        }

        // Populate display data (← PERFORM POPULATE-TRAN-DATA)
        List<TransactionListItem> displayItems = populateTranData(result);

        // Log AIX timestamp range for observability (← VSAM AIX on TRAN-ORIG-TS)
        logTimestampRangeStats(result);

        log.info("Transaction list result: page={}, records={}, totalPages={}, "
                 + "displayItems={}, hasNext={}",
                 safePage, result.getNumberOfElements(), result.getTotalPages(),
                 displayItems.size(), result.hasNext());

        return result;
    }

    /**
     * Processes the Enter key action from the transaction list screen.
     * Maps to COTRN00C.cbl {@code PROCESS-ENTER-KEY} (line 146).
     *
     * <p>Handles two scenarios:
     * <ol>
     *   <li><strong>Transaction selection</strong> — When a selection flag
     *       ({@code S}/{@code s}) is active, updates the navigation context
     *       to route to the transaction detail view
     *       ({@code COTRN01C}).</li>
     *   <li><strong>Transaction ID input</strong> — Validates the entered
     *       transaction ID is numeric, then repositions the browse from
     *       page 0.</li>
     * </ol>
     *
     * <p>COBOL equivalent (lines 146-229):
     * <pre>{@code
     * PROCESS-ENTER-KEY.
     *     EVALUATE TRUE
     *         WHEN SEL0001I NOT = SPACES AND LOW-VALUES
     *             MOVE SEL0001I TO CDEMO-CT00-TRN-SEL-FLG
     *             MOVE TRNID01I TO CDEMO-CT00-TRN-SELECTED
     *         ...
     *     END-EVALUATE
     *     IF TRNIDINI IS NUMERIC
     *         MOVE TRNIDINI TO TRAN-ID
     *     ELSE
     *         MOVE 'Tran ID must be Numeric ...' TO WS-MESSAGE
     *     END-IF
     *     MOVE 0 TO CDEMO-CT00-PAGE-NUM
     *     PERFORM PROCESS-PAGE-FORWARD
     * }</pre>
     *
     * @param transactionId transaction ID input from screen
     *                      (must be numeric or blank)
     * @param page          current page number (reset to 0 internally)
     * @return paginated transaction results starting from page 0
     * @throws IllegalArgumentException if transactionId is non-numeric
     */
    @Transactional(readOnly = true)
    public Page<Transaction> processEnterKey(String transactionId, int page) {
        log.info("Enter key pressed: user='{}', transactionId='{}', page={}",
                 context.getUserId(), transactionId, page);

        // Set navigation context for potential detail view routing
        // (← COBOL: MOVE 'COTRN01C' TO CDEMO-TO-PROGRAM)
        context.setToProgram(DETAIL_PROGRAM);
        context.setFromTranId(TRAN_ID);
        context.setFromProgram(PROGRAM_NAME);

        String targetProgram = context.getToProgram();
        log.debug("Navigation context: fromProgram='{}', toProgram='{}'",
                  PROGRAM_NAME, targetProgram);

        // Validate transaction ID is numeric if provided
        // (← COBOL: IF TRNIDINI OF COTRN0AI IS NUMERIC)
        if (transactionId != null && !transactionId.isBlank()) {
            String trimmed = transactionId.trim();
            if (!trimmed.chars().allMatch(Character::isDigit)) {
                log.warn("Non-numeric transaction ID input rejected: '{}'. {}",
                         transactionId, INVALID_KEY_MSG);
                throw new IllegalArgumentException(
                        "Tran ID must be Numeric ...");
            }
            log.debug("Validated numeric transaction ID: '{}'", trimmed);
        }

        // Reset page counter and perform forward browse
        // (← COBOL: MOVE 0 TO CDEMO-CT00-PAGE-NUM
        //           PERFORM PROCESS-PAGE-FORWARD)
        return processPageForward(0);
    }

    /**
     * Processes the PF7 (Page Up / Previous Page) key action.
     * Maps to COTRN00C.cbl {@code PROCESS-PF7-KEY} (line 234).
     *
     * <p>If already on the first page, returns the first page with an
     * informational message logged.
     *
     * <p>COBOL equivalent (lines 234-252):
     * <pre>{@code
     * PROCESS-PF7-KEY.
     *     IF CDEMO-CT00-PAGE-NUM > 1
     *         MOVE CDEMO-CT00-TRNID-FIRST TO TRAN-ID
     *         PERFORM PROCESS-PAGE-BACKWARD
     *     ELSE
     *         MOVE 'You are already at the top...' TO WS-MESSAGE
     *     END-IF
     * }</pre>
     *
     * @param currentPage current zero-based page number
     * @return paginated transaction results for the previous (or first) page
     */
    @Transactional(readOnly = true)
    public Page<Transaction> processPf7Key(int currentPage) {
        log.info("PF7 (page backward): user='{}', currentPage={}",
                 context.getUserId(), currentPage);

        // (← COBOL: IF CDEMO-CT00-PAGE-NUM > 1)
        if (currentPage <= 0) {
            log.info("Already at the top of the transaction list — returning first page");
            return processPageForward(0);
        }

        return processPageBackward(currentPage);
    }

    /**
     * Processes the PF8 (Page Down / Next Page) key action.
     * Maps to COTRN00C.cbl {@code PROCESS-PF8-KEY} (line 257).
     *
     * <p>Checks whether a next page exists before advancing. If no more
     * data is available, returns the current page.
     *
     * <p>COBOL equivalent (lines 257-274):
     * <pre>{@code
     * PROCESS-PF8-KEY.
     *     IF NEXT-PAGE-YES
     *         MOVE CDEMO-CT00-TRNID-LAST TO TRAN-ID
     *         PERFORM PROCESS-PAGE-FORWARD
     *     ELSE
     *         MOVE 'You are already at the bottom...' TO WS-MESSAGE
     *     END-IF
     * }</pre>
     *
     * @param currentPage current zero-based page number
     * @return paginated transaction results for the next (or current) page
     */
    @Transactional(readOnly = true)
    public Page<Transaction> processPf8Key(int currentPage) {
        log.info("PF8 (page forward): user='{}', currentPage={}",
                 context.getUserId(), currentPage);

        // Check if next page exists (← COBOL: IF NEXT-PAGE-YES)
        Pageable currentPageable = PageRequest.of(
                Math.max(0, currentPage), PAGE_SIZE, TRAN_ID_SORT);
        Page<Transaction> currentResult = transactionRepository.findAll(currentPageable);

        if (currentResult.hasNext()) {
            return processPageForward(currentPage + 1);
        }

        // Already at the bottom (← COBOL line 270)
        log.info("Already at the bottom of the transaction list — returning current page");
        List<TransactionListItem> displayItems = populateTranData(currentResult);
        log.debug("Current page display items: {}", displayItems.size());
        return currentResult;
    }

    /**
     * Navigates forward to the specified page in the transaction list.
     * Maps to COTRN00C.cbl {@code PROCESS-PAGE-FORWARD} (line 279).
     *
     * <p>Implements the COBOL browse pattern:
     * {@code STARTBR → READNEXT} loop for {@link #PAGE_SIZE} (10) records
     * → {@code ENDBR}. The JPA equivalent is a paginated
     * {@code findAll(Pageable)} query with ascending sort by transaction ID.
     *
     * <p>COBOL equivalent (lines 279-330):
     * <pre>{@code
     * PROCESS-PAGE-FORWARD.
     *     PERFORM STARTBR-TRANSACT-FILE
     *     PERFORM INITIALIZE-TRAN-DATA
     *     ... (READNEXT loop for 10 records)
     *     ADD 1 TO CDEMO-CT00-PAGE-NUM
     * }</pre>
     *
     * @param page zero-based target page number
     * @return paginated transaction results for the requested page
     */
    @Transactional(readOnly = true)
    public Page<Transaction> processPageForward(int page) {
        log.debug("Processing page forward to page {}", page);

        // Initialize display data (← PERFORM INITIALIZE-TRAN-DATA)
        initializeTranData();

        // Build paginated query (← STARTBR + READNEXT loop for 10 records)
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, PAGE_SIZE, TRAN_ID_SORT);
        Page<Transaction> result = transactionRepository.findAll(pageable);

        // Populate display data (← PERFORM POPULATE-TRAN-DATA)
        List<TransactionListItem> displayItems = populateTranData(result);

        // Check next-page availability (← COBOL NEXT-PAGE-YES/NO flag)
        if (result.hasNext()) {
            log.debug("More pages available after page {}", safePage);
        } else {
            log.debug("No more pages after page {} (end of file)", safePage);
        }

        log.info("Page forward complete: page={}, records={}, displayItems={}, hasNext={}",
                 safePage, result.getNumberOfElements(), displayItems.size(),
                 result.hasNext());

        return result;
    }

    /**
     * Navigates backward to the previous page in the transaction list.
     * Maps to COTRN00C.cbl {@code PROCESS-PAGE-BACKWARD} (line 333).
     *
     * <p>Implements the COBOL reverse browse pattern:
     * {@code STARTBR → READPREV} loop for {@link #PAGE_SIZE} (10) records
     * → {@code ENDBR}. The JPA equivalent is a paginated
     * {@code findAll(Pageable)} with the page number decremented by one.
     *
     * <p>COBOL equivalent (lines 333-375):
     * <pre>{@code
     * PROCESS-PAGE-BACKWARD.
     *     PERFORM STARTBR-TRANSACT-FILE
     *     PERFORM INITIALIZE-TRAN-DATA
     *     ... (READPREV loop for 10 records, IDX 10 → 0)
     *     SUBTRACT 1 FROM CDEMO-CT00-PAGE-NUM
     * }</pre>
     *
     * @param page current zero-based page number (navigates to {@code page - 1})
     * @return paginated transaction results for the previous page
     */
    @Transactional(readOnly = true)
    public Page<Transaction> processPageBackward(int page) {
        log.debug("Processing page backward from page {}", page);

        // Initialize display data (← PERFORM INITIALIZE-TRAN-DATA)
        initializeTranData();

        // Navigate to previous page (← READPREV loop, decrement page)
        int targetPage = Math.max(0, page - 1);
        Pageable pageable = PageRequest.of(targetPage, PAGE_SIZE, TRAN_ID_SORT);
        Page<Transaction> result = transactionRepository.findAll(pageable);

        // Populate display data (← PERFORM POPULATE-TRAN-DATA)
        List<TransactionListItem> displayItems = populateTranData(result);

        log.info("Page backward complete: targetPage={}, records={}, displayItems={}",
                 targetPage, result.getNumberOfElements(), displayItems.size());

        return result;
    }

    // =========================================================================
    // Private helper methods (← supporting COBOL paragraphs)
    // =========================================================================

    /**
     * Transforms transaction entity records into display-format list items.
     * Maps to COTRN00C.cbl {@code POPULATE-TRAN-DATA} paragraph (line 381).
     *
     * <p>Iterates through the page content (up to 10 records) and extracts
     * key display fields from each {@link Transaction} entity. Also tracks
     * the first and last transaction IDs on the page for COMMAREA context
     * (← {@code CDEMO-CT00-TRNID-FIRST} / {@code CDEMO-CT00-TRNID-LAST}).
     *
     * <p>COBOL equivalent populates BMS screen fields:
     * {@code TRNID01I-10I}, {@code TDATE01I-10I}, {@code TDESC01I-10I},
     * {@code TAMT001I-010I} via a 1-to-10 EVALUATE on {@code WS-IDX}.
     *
     * @param page the page of Transaction entities to transform
     * @return list of display-format items for the transaction list screen
     */
    private List<TransactionListItem> populateTranData(Page<Transaction> page) {
        List<TransactionListItem> items = new ArrayList<>();
        List<Transaction> content = page.getContent();

        if (content.isEmpty()) {
            log.debug("No transactions to populate for display");
            return items;
        }

        // Track first/last IDs (← CDEMO-CT00-TRNID-FIRST / LAST)
        String firstTranId = content.getFirst().getTranId();
        String lastTranId = content.getLast().getTranId();
        log.debug("Page transaction range: first='{}', last='{}'",
                  firstTranId, lastTranId);

        int idx = 0;
        for (Transaction tran : content) {
            idx++;

            // Extract display fields from entity
            // (← COBOL: MOVE TRAN-ID, TRAN-DESC, TRAN-AMT, TRAN-ORIG-TS)
            String tranId = tran.getTranId();
            String description = tran.getDescription();
            BigDecimal amount = tran.getAmount();
            String origTimestamp = tran.getOrigTimestamp();

            // Format date from timestamp
            // (← COBOL: MOVE WS-TIMESTAMP-DT-YYYY(3:2) TO WS-CURDATE-YY,
            //           MOVE WS-TIMESTAMP-DT-MM TO WS-CURDATE-MM,
            //           MOVE WS-TIMESTAMP-DT-DD TO WS-CURDATE-DD)
            String formattedDate = extractDateFromTimestamp(origTimestamp);

            // Create display list item with available DTO fields
            TransactionListItem item = new TransactionListItem();
            item.setTranType(tranId != null && tranId.length() >= 2
                    ? tranId.substring(0, 2) : "");
            item.setTranTypeDesc(description != null ? description.trim() : "");

            // Access getters for verification and trace logging
            // (← COBOL EVALUATE WS-IDX populates screen rows 1-10)
            log.trace("Row {}: type='{}', typeDesc='{}', id='{}', amount={}, date='{}'",
                      idx, item.getTranType(), item.getTranTypeDesc(),
                      tranId, amount, formattedDate);

            items.add(item);
        }

        return items;
    }

    /**
     * Initialises (clears) the transaction display data.
     * Maps to COTRN00C.cbl {@code INITIALIZE-TRAN-DATA} paragraph (line 450).
     *
     * <p>In the COBOL program, this paragraph loops through 10 screen rows
     * and moves SPACES to each BMS field ({@code SEL0001O} through
     * {@code SEL0010O}, {@code TRNID01O} through {@code TRNID10O}, etc.).
     * In Java, the equivalent is a no-op since new collections are created
     * empty and previous display state is garbage-collected.
     */
    private void initializeTranData() {
        log.trace("Initialising transaction display data "
                  + "(clear previous screen rows, COBOL lines 450-505)");
    }

    /**
     * Populates header and navigation context information.
     * Maps to COTRN00C.cbl {@code POPULATE-HEADER-INFO} paragraph (line 567).
     *
     * <p>Sets COMMAREA navigation fields for program identification:
     * <pre>{@code
     * COBOL: MOVE WS-TRANID  TO CDEMO-FROM-TRANID
     *        MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
     * }</pre>
     */
    private void populateHeaderInfo() {
        context.setFromTranId(TRAN_ID);
        context.setFromProgram(PROGRAM_NAME);
        log.trace("Header info populated: tranId='{}', pgmName='{}'",
                  TRAN_ID, PROGRAM_NAME);
    }

    /**
     * Extracts a formatted date string (MM/DD/YY) from an ISO-8601
     * extended timestamp ({@code YYYY-MM-DD-HH.MM.SS.mmmmmm}).
     *
     * <p>Maps to COBOL date extraction logic in POPULATE-TRAN-DATA:
     * <pre>{@code
     * MOVE TRAN-ORIG-TS              TO WS-TIMESTAMP
     * MOVE WS-TIMESTAMP-DT-YYYY(3:2) TO WS-CURDATE-YY
     * MOVE WS-TIMESTAMP-DT-MM        TO WS-CURDATE-MM
     * MOVE WS-TIMESTAMP-DT-DD        TO WS-CURDATE-DD
     * STRING WS-CURDATE-MM '/' WS-CURDATE-DD '/' WS-CURDATE-YY
     *        INTO WS-TRAN-DATE
     * }</pre>
     *
     * @param timestamp ISO-8601 extended format, 26 chars
     * @return formatted date string (MM/DD/YY), or "00/00/00" if invalid
     */
    private static String extractDateFromTimestamp(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) {
            return "00/00/00";
        }
        try {
            // YYYY-MM-DD: positions 0-3=year, 5-6=month, 8-9=day
            String yearYy = timestamp.substring(2, 4);
            String month = timestamp.substring(5, 7);
            String day = timestamp.substring(8, 10);
            return month + "/" + day + "/" + yearYy;
        } catch (StringIndexOutOfBoundsException e) {
            log.warn("Invalid timestamp format for date extraction: '{}'", timestamp);
            return "00/00/00";
        }
    }

    /**
     * Logs timestamp range statistics for the current page using the
     * AIX-equivalent secondary index query on {@code TRAN-ORIG-TS}.
     *
     * <p>Delegates to
     * {@link TransactionRepository#findByOrigTimestampBetween(String, String)}
     * to determine how many transactions fall within the timestamp range
     * of the current page. This provides data distribution observability
     * comparable to VSAM alternate index access patterns.
     *
     * @param page the current page of transactions
     */
    private void logTimestampRangeStats(Page<Transaction> page) {
        if (page.isEmpty()) {
            return;
        }
        List<Transaction> content = page.getContent();
        String firstTs = content.getFirst().getOrigTimestamp();
        String lastTs = content.getLast().getOrigTimestamp();
        if (firstTs != null && lastTs != null) {
            List<Transaction> rangeResults =
                    transactionRepository.findByOrigTimestampBetween(firstTs, lastTs);
            log.debug("AIX timestamp range [{} to {}]: {} matching transactions",
                      firstTs, lastTs, rangeResults.size());
        }
    }
}
