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
package com.cardemo.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.entity.Transaction;
import com.cardemo.service.online.TransactionAddService;
import com.cardemo.service.online.TransactionListService;
import com.cardemo.service.online.TransactionViewService;

/**
 * REST Controller for transaction operations, combining three CICS transactions
 * into a single {@code /api/transactions} REST resource.
 *
 * <p>This controller translates the following COBOL online CICS programs into
 * stateless REST endpoints, preserving 100% business logic parity through
 * delegation to the respective service classes:</p>
 *
 * <table>
 *   <caption>CICS Transaction-to-REST Endpoint Mapping</caption>
 *   <tr><th>CICS Txn</th><th>COBOL Program</th><th>REST Endpoint</th>
 *       <th>HTTP Method</th></tr>
 *   <tr><td>CT00</td><td>COTRN00C.cbl</td>
 *       <td>{@code /api/transactions}</td><td>GET</td></tr>
 *   <tr><td>CT01</td><td>COTRN01C.cbl</td>
 *       <td>{@code /api/transactions/{id}}</td><td>GET</td></tr>
 *   <tr><td>CT02</td><td>COTRN02C.cbl</td>
 *       <td>{@code /api/transactions}</td><td>POST</td></tr>
 * </table>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><strong>Delegation only</strong> &mdash; All business logic resides in
 *       the service layer. This controller handles HTTP request/response mapping
 *       exclusively.</li>
 *   <li><strong>Stateless</strong> &mdash; The CICS pseudo-conversational model
 *       is mapped to stateless REST endpoints with no server-side session.</li>
 *   <li><strong>Constructor injection</strong> &mdash; All three service
 *       dependencies are injected via the constructor (no field injection).</li>
 *   <li><strong>No feature expansion</strong> &mdash; Only the three endpoints
 *       corresponding to the original COBOL programs are exposed.</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <ul>
 *   <li>{@link RecordNotFoundException} &rarr; HTTP 404 Not Found (maps VSAM
 *       file status {@code '23'} / CICS RESP=13 NOTFND)</li>
 *   <li>{@link ValidationException} &rarr; HTTP 400 Bad Request (maps field
 *       validation failures from BMS map input processing)</li>
 * </ul>
 *
 * @see TransactionListService
 * @see TransactionViewService
 * @see TransactionAddService
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger logger =
            LoggerFactory.getLogger(TransactionController.class);

    /** Minimum number of characters required for card number masking. */
    private static final int CARD_MASK_VISIBLE_DIGITS = 4;

    private final TransactionListService transactionListService;
    private final TransactionViewService transactionViewService;
    private final TransactionAddService transactionAddService;

    /**
     * Constructs the transaction controller with all required service
     * dependencies via Spring constructor injection.
     *
     * <p>Maps to the COBOL pattern where three separate CICS programs
     * (COTRN00C, COTRN01C, COTRN02C) are each compiled and deployed
     * independently. In the Java layer, Spring manages the lifecycle of each
     * service bean and injects them into this controller.</p>
     *
     * @param transactionListService service for paginated transaction browsing
     *                               (COTRN00C.cbl &rarr; CT00)
     * @param transactionViewService service for single transaction detail view
     *                               (COTRN01C.cbl &rarr; CT01)
     * @param transactionAddService  service for new transaction creation
     *                               (COTRN02C.cbl &rarr; CT02)
     */
    public TransactionController(
            TransactionListService transactionListService,
            TransactionViewService transactionViewService,
            TransactionAddService transactionAddService) {
        this.transactionListService = transactionListService;
        this.transactionViewService = transactionViewService;
        this.transactionAddService = transactionAddService;
    }

    // ========================================================================
    // Endpoint 1 — GET /api/transactions (← COTRN00C.cbl, CT00)
    // ========================================================================

    /**
     * Lists transactions with pagination support.
     *
     * <p>Translates the COBOL transaction list screen (COTRN00C.cbl) which
     * uses STARTBR/READNEXT/READPREV on the TRANSACT VSAM dataset to display
     * 10 repeating rows (SEL0001..SEL0010) with PF7/PF8 page navigation.</p>
     *
     * <p><strong>COBOL Paragraph Mapping:</strong></p>
     * <ul>
     *   <li>MAIN-PARA (line 95) &rarr; request entry</li>
     *   <li>PROCESS-PAGE-FORWARD (line 279) &rarr; {@code page} parameter
     *       (PF8 forward navigation)</li>
     *   <li>PROCESS-PAGE-BACKWARD (line 333) &rarr; {@code page} parameter
     *       (PF7 backward navigation)</li>
     * </ul>
     *
     * @param transactionId optional transaction ID filter for browse start
     *                      position (maps to TRNIDIN search field in
     *                      COTRN00.bms)
     * @param page          zero-based page index (default 0); maps to PF7/PF8
     *                      page navigation
     * @param size          page size (default 10); maps to COBOL
     *                      WS-MAX-SCREEN-LINES (10 rows per BMS screen).
     *                      The service layer enforces a fixed page size of 10
     *                      records matching the original COBOL display.
     * @return paginated transaction list wrapped in {@link ResponseEntity} with
     *         HTTP 200 OK
     */
    @GetMapping
    public ResponseEntity<Page<Transaction>> listTransactions(
            @RequestParam(value = "transactionId", required = false)
            String transactionId,
            @RequestParam(value = "page", defaultValue = "0")
            int page,
            @RequestParam(value = "size", defaultValue = "10")
            int size) {

        logger.info("Listing transactions — filter: {}, page: {}, size: {}",
                transactionId != null ? transactionId : "N/A", page, size);

        // Delegate to TransactionListService which enforces the COBOL-matching
        // page size of 10 records internally (WS-MAX-SCREEN-LINES).
        // The 'size' parameter is accepted for API completeness but the service
        // uses its own constant page size to preserve COBOL parity.
        Page<Transaction> result =
                transactionListService.listTransactions(transactionId, page);

        logger.debug("Transaction list returned {} of {} total records, "
                        + "page {} of {}",
                result.getNumberOfElements(),
                result.getTotalElements(),
                result.getNumber(),
                result.getTotalPages());

        return ResponseEntity.ok(result);
    }

    // ========================================================================
    // Endpoint 2 — GET /api/transactions/{id} (← COTRN01C.cbl, CT01)
    // ========================================================================

    /**
     * Retrieves a single transaction by its unique identifier.
     *
     * <p>Translates the COBOL transaction view screen (COTRN01C.cbl) which
     * performs a primary-key READ on the TRANSACT VSAM dataset:</p>
     * <pre>
     * EXEC CICS READ DATASET(WS-TRANSACT-FILE)
     *                INTO(TRAN-RECORD)
     *                RIDFLD(WS-TRAN-ID)
     * END-EXEC
     * </pre>
     *
     * <p><strong>COBOL Paragraph Mapping:</strong></p>
     * <ul>
     *   <li>MAIN-PARA (line 86) &rarr; request entry</li>
     *   <li>READ-TRANSACT-FILE (line 267) &rarr; JPA
     *       {@code findById(id)}</li>
     * </ul>
     *
     * <p>Response includes all fields from the 350-byte TRAN-RECORD defined
     * in CVTRA05Y.cpy: tranId, typeCode, categoryCode, source, description,
     * amount (BigDecimal PIC S9(09)V99), merchantId, merchantName,
     * merchantCity, merchantZip, cardNum, origTimestamp (ISO-8601 26-char),
     * and procTimestamp.</p>
     *
     * @param id the transaction identifier (maps to TRNID / WS-TRAN-ID,
     *           variable-length key)
     * @return the transaction detail wrapped in {@link ResponseEntity} with
     *         HTTP 200 OK
     * @throws RecordNotFoundException if the transaction ID does not exist
     *         (maps to CICS RESP=13 NOTFND)
     */
    @GetMapping("/{id}")
    public ResponseEntity<Transaction> viewTransaction(
            @PathVariable("id") String id) {

        logger.info("Viewing transaction detail — transactionId: {}", id);

        Transaction transaction =
                transactionViewService.viewTransaction(id);

        logger.debug("Transaction found — tranId: {}, type: {}, "
                        + "category: {}, description: {}, amount: {}, "
                        + "origTimestamp: {}",
                transaction.getTranId(),
                transaction.getTypeCode(),
                transaction.getCategoryCode(),
                transaction.getDescription(),
                transaction.getAmount(),
                transaction.getOrigTimestamp());

        return ResponseEntity.ok(transaction);
    }

    // ========================================================================
    // Endpoint 3 — POST /api/transactions (← COTRN02C.cbl, CT02)
    // ========================================================================

    /**
     * Creates a new transaction.
     *
     * <p>Translates the COBOL transaction add screen (COTRN02C.cbl) which
     * performs multi-step validation and write:</p>
     * <ol>
     *   <li>Validate key fields: account ID + card number cross-reference
     *       (READ-CXACAIX-FILE / READ-CCXREF-FILE)</li>
     *   <li>Validate data fields: amount range
     *       ({@code -99999999.99..+99999999.99}), date format, type/category
     *       codes, description length</li>
     *   <li>Generate transaction ID via browse-last technique per AAP 0.7.4:
     *       {@code findFirstByOrderByTranIdDesc()} &rarr; increment</li>
     *   <li>Set timestamps: origTimestamp = current time in ISO-8601 format
     *       {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 chars, microsecond
     *       precision)</li>
     *   <li>Write: {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)} &rarr;
     *       {@code transactionRepository.save()}</li>
     * </ol>
     *
     * <p><strong>COBOL Paragraph Mapping:</strong></p>
     * <ul>
     *   <li>MAIN-PARA (line 107) &rarr; request entry</li>
     *   <li>PROCESS-ENTER-KEY (line 164) &rarr; orchestration</li>
     *   <li>VALIDATE-INPUT-KEY-FIELDS (line 193) &rarr; key field
     *       validation</li>
     *   <li>VALIDATE-INPUT-DATA-FIELDS (line 235) &rarr; data field
     *       validation</li>
     *   <li>ADD-TRANSACTION (line 442) &rarr; ID generation + write</li>
     * </ul>
     *
     * @param request the transaction add request containing all screen fields
     *                from COTRN02.bms (accountId, cardNum, typeCode,
     *                categoryCode, source, description, amount, origDate,
     *                merchantId, merchantName, merchantCity, merchantZip,
     *                confirm)
     * @return the created transaction (including generated ID and timestamps)
     *         wrapped in {@link ResponseEntity} with HTTP 201 Created
     * @throws ValidationException     if field validation fails (amount range,
     *         date format, missing type/category codes, description length)
     * @throws RecordNotFoundException if account/card not found during
     *         cross-reference validation
     */
    @PostMapping
    public ResponseEntity<Transaction> addTransaction(
            @Valid @RequestBody TransactionAddService.TransactionAddRequest request) {

        logger.info("Adding new transaction — accountId: {}, cardNum: {}",
                request.accountId(),
                maskCardNumber(request.cardNum()));

        Transaction created =
                transactionAddService.addTransaction(request);

        logger.info("Transaction created successfully — tranId: {}, "
                        + "amount: {}, origTimestamp: {}",
                created.getTranId(),
                created.getAmount(),
                created.getOrigTimestamp());

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ========================================================================
    // Exception handlers — HTTP status mapping
    // ========================================================================

    /**
     * Handles {@link RecordNotFoundException} by returning HTTP 404 Not Found.
     *
     * <p>Maps the VSAM file status code {@code '23'} (record not found) and
     * CICS RESP=13 (NOTFND) error paths from the original COBOL programs to
     * the appropriate HTTP status code. Applies to:</p>
     * <ul>
     *   <li>{@code GET /api/transactions/{id}} when the transaction ID does
     *       not exist in the TRANSACT dataset</li>
     *   <li>{@code POST /api/transactions} when account/card cross-reference
     *       lookup fails during validation</li>
     * </ul>
     *
     * @param ex the record-not-found exception thrown by the service layer
     * @return error response with HTTP 404 and error message body
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRecordNotFound(
            RecordNotFoundException ex) {

        logger.warn("Record not found: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Handles {@link ValidationException} by returning HTTP 400 Bad Request.
     *
     * <p>Maps field validation failures from the BMS map input processing
     * to the appropriate HTTP status code. Applies to:</p>
     * <ul>
     *   <li>{@code GET /api/transactions} when invalid filter parameters
     *       are provided</li>
     *   <li>{@code POST /api/transactions} when input field validation fails
     *       (amount range, date format, missing type/category codes,
     *       description length)</li>
     * </ul>
     *
     * @param ex the validation exception thrown by the service layer
     * @return error response with HTTP 400 and error message body
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(
            ValidationException ex) {

        logger.warn("Validation error: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    // ========================================================================
    // Private utility methods
    // ========================================================================

    /**
     * Masks a card number for secure logging, showing only the last 4 digits.
     *
     * <p>Card numbers (PIC X(16) from CVTRA05Y.cpy) are sensitive PII and
     * must not appear in full in log output. This method replaces all but the
     * last 4 characters with asterisks.</p>
     *
     * @param cardNum the full card number string (may be {@code null})
     * @return masked card number (e.g., {@code "****1234"}) or {@code "****"}
     *         for {@code null}/short values
     */
    private static String maskCardNumber(String cardNum) {
        if (cardNum == null || cardNum.length() < CARD_MASK_VISIBLE_DIGITS) {
            return "****";
        }
        return "****" + cardNum.substring(
                cardNum.length() - CARD_MASK_VISIBLE_DIGITS);
    }
}
