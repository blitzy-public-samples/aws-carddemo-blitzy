/*
 * TransactionController.java
 *
 * REST API controller handling transaction operations from COBOL programs:
 * - COTRN00C.cbl (transaction list display with date filtering and pagination)
 * - COTRN01C.cbl (transaction detail view)
 * - COTRN02C.cbl (transaction entry and posting - most complex with credit limit validation)
 *
 * Converted from COBOL CICS online programs replacing EXEC CICS SEND MAP and EXEC CICS
 * RECEIVE MAP with REST API endpoints accepting JSON and returning JSON responses.
 *
 * Original COBOL programs:
 * - Source: app/cbl/COTRN00C.cbl (transaction list with browse cursor, ~950 lines)
 * - Source: app/cbl/COTRN01C.cbl (transaction detail display, ~650 lines)
 * - Source: app/cbl/COTRN02C.cbl (transaction posting logic, ~1400 lines, 33KB)
 * - BMS Maps: app/bms/COTRN00.bms, COTRN01.bms, COTRN02.bms (3270 screen definitions)
 * - Copybook: app/cpy/CVTRA05Y.cpy (TRAN-RECORD structure, 350-byte record)
 *
 * Key COBOL-to-Java transformations:
 * 1. EXEC CICS SEND MAP COTRN00 → GET /api/transactions returning Page<TransactionDto>
 * 2. EXEC CICS SEND MAP COTRN01 → GET /api/transactions/{id} returning TransactionDto
 * 3. EXEC CICS RECEIVE MAP COTRN02 → POST /api/transactions accepting @RequestBody TransactionDto
 * 4. EXEC CICS STARTBR FILE('TRANSACT') → Spring Data Pageable with Page<T> responses
 * 5. EXEC CICS READNEXT browsing loop → transactionRepository.findByTransCardNumAndTransOrigTsBetween()
 * 6. EXEC CICS SYNCPOINT → @Transactional in service layer ensuring atomic updates
 * 7. BMS field attributes (NUM, PROT, BRT) → @Valid annotation with Bean Validation constraints
 * 8. COBOL validation flags (WS-ERR-FLG) → ValidationService throwing ValidationException
 * 9. COBOL error messages → GlobalExceptionHandler returning HTTP 400/404 with ErrorResponse
 *
 * Business Logic Preservation (COTRN02C.cbl transaction posting, lines 200-800):
 * Per Agent Action Plan Section 0.7.2 - maintain identical business logic with zero functional deviation:
 * 1. Validate transaction data (card number, type code, category code, amount format)
 * 2. Verify card is active and not expired
 * 3. Retrieve account associated with card via XREFFILE cross-reference
 * 4. Calculate new balance: newBalance = currentBalance +/- transactionAmount based on debit/credit type
 * 5. Validate credit limit: if newBalance > creditLimit throw BusinessException("Transaction exceeds credit limit")
 * 6. Update account balance atomically (ACCTFILE REWRITE → accountRepository.save)
 * 7. Update account cycle credit/debit totals (currentCycleCredit, currentCycleDebit)
 * 8. Save transaction record (EXEC CICS WRITE FILE('TRANSACT') → transactionRepository.save)
 * 9. Update transaction category balance (TCATBAL REWRITE → tcatBalanceRepository.save)
 *
 * COMP-3 Precision Preservation (Agent Action Plan Section 0.7.3):
 * All COBOL PIC S9(09)V99 COMP-3 packed decimal fields converted to BigDecimal with scale 2,
 * RoundingMode.HALF_UP to ensure bit-identical results to mainframe calculations.
 *
 * Example: COBOL: COMPUTE NEW-BALANCE = CURR-BALANCE - TRAN-AMT
 *          Java: newBalance = currentBalance.subtract(transactionAmount).setScale(2, RoundingMode.HALF_UP)
 *
 * REST API Endpoints:
 * - GET /api/transactions - List transactions with optional filters (cardNumber, startDate, endDate, pagination)
 * - GET /api/transactions/{transactionId} - Get transaction detail by ID
 * - POST /api/transactions - Post new transaction with credit limit validation and balance update
 *
 * Transaction Type Codes (from CVTRA03Y.cpy):
 * - '01' = Purchase (debit) - reduces available credit
 * - '02' = Cash Advance (debit) - reduces available credit
 * - '03' = Balance Transfer (debit) - reduces available credit
 * - '04' = Payment (credit) - increases available credit
 * - '05' = Fee (debit) - reduces available credit
 * - '06' = Interest Charge (debit) - reduces available credit
 * - '07' = Credit Adjustment (credit) - increases available credit
 * - '08' = Debit Adjustment (debit) - reduces available credit
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.controller;

import com.carddemo.exception.BusinessException;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionDto;
import com.carddemo.service.TransactionService;
import com.carddemo.service.ValidationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Transaction REST Controller providing endpoints for credit card transaction operations.
 * 
 * <p>This controller replaces three COBOL CICS online programs:</p>
 * <ul>
 *   <li><b>COTRN00C.cbl</b> - Transaction list display with browse cursor and pagination</li>
 *   <li><b>COTRN01C.cbl</b> - Transaction detail view</li>
 *   <li><b>COTRN02C.cbl</b> - Transaction entry and posting with complex validation</li>
 * </ul>
 * 
 * <h2>Endpoint Mappings from COBOL Programs:</h2>
 * 
 * <h3>Transaction List (COTRN00C.cbl → GET /api/transactions)</h3>
 * <pre>
 * COBOL Operation: EXEC CICS STARTBR FILE('TRANSACT')
 *                  EXEC CICS READNEXT FILE('TRANSACT') [loop]
 *                  EXEC CICS SEND MAP('COTRN00') [display transaction list]
 * 
 * REST Endpoint:   GET /api/transactions?cardNumber={card}&startDate={date}&endDate={date}&page={n}&size={s}
 * Response:        Page<TransactionDto> with pagination metadata
 * </pre>
 * 
 * <h3>Transaction Detail (COTRN01C.cbl → GET /api/transactions/{id})</h3>
 * <pre>
 * COBOL Operation: EXEC CICS READ FILE('TRANSACT') RIDFLD(TRAN-ID)
 *                  EXEC CICS SEND MAP('COTRN01') [display transaction detail]
 * 
 * REST Endpoint:   GET /api/transactions/{transactionId}
 * Response:        TransactionDto (single transaction)
 * HTTP Status:     200 OK if found, 404 Not Found if transaction doesn't exist
 * </pre>
 * 
 * <h3>Transaction Posting (COTRN02C.cbl → POST /api/transactions)</h3>
 * <pre>
 * COBOL Operation: EXEC CICS RECEIVE MAP('COTRN02') [transaction entry screen]
 *                  [Validate all input fields]
 *                  EXEC CICS READ FILE('CARDFILE') [verify card active]
 *                  EXEC CICS READ FILE('ACCTFILE') [get account]
 *                  COMPUTE NEW-BALANCE = CURR-BALANCE +/- TRAN-AMT
 *                  IF NEW-BALANCE > CREDIT-LIMIT [validation]
 *                  EXEC CICS REWRITE FILE('ACCTFILE') [update account]
 *                  EXEC CICS WRITE FILE('TRANSACT') [create transaction]
 *                  EXEC CICS REWRITE FILE('TCATBAL') [update category balance]
 *                  EXEC CICS SYNCPOINT [commit]
 *                  EXEC CICS SEND MAP('COTRN02') [success message]
 * 
 * REST Endpoint:   POST /api/transactions
 * Request Body:    TransactionDto (JSON)
 * Response:        TransactionDto (created transaction)
 * HTTP Status:     201 Created on success
 *                  400 Bad Request on validation failure
 *                  404 Not Found if card/account not found
 *                  409 Conflict if credit limit exceeded
 * </pre>
 * 
 * <h2>Error Handling:</h2>
 * <p>All exceptions thrown by this controller are handled by GlobalExceptionHandler:</p>
 * <ul>
 *   <li><b>ValidationException</b> - Field validation failure → HTTP 400 Bad Request</li>
 *   <li><b>DataNotFoundException</b> - Transaction/card/account not found → HTTP 404 Not Found</li>
 *   <li><b>BusinessException</b> - Business rule violation (credit limit) → HTTP 400 or 409</li>
 *   <li><b>MethodArgumentNotValidException</b> - Bean validation failure → HTTP 400 Bad Request</li>
 * </ul>
 * 
 * @see TransactionService
 * @see ValidationService
 * @see TransactionDto
 * @see com.carddemo.exception.GlobalExceptionHandler
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final ValidationService validationService;

    /**
     * Constructor with dependency injection for transaction operations and validation.
     * 
     * @param transactionService Service handling transaction business logic from COTRN00C-02C
     * @param validationService Service validating transaction input fields
     */
    public TransactionController(TransactionService transactionService, 
                                ValidationService validationService) {
        this.transactionService = transactionService;
        this.validationService = validationService;
    }

    /**
     * List transactions with optional filtering and pagination.
     * 
     * <p>Converted from COBOL program: <b>COTRN00C.cbl</b></p>
     * 
     * <p><b>Original COBOL Logic (COTRN00C.cbl lines 150-350):</b></p>
     * <pre>
     * PROCEDURE DIVISION.
     *     EXEC CICS STARTBR FILE('TRANSACT')
     *          RIDFLD(START-KEY)
     *          GTEQ
     *     END-EXEC.
     *     
     *     PERFORM UNTIL TRANSACT-EOF OR WS-REC-COUNT >= 20
     *         EXEC CICS READNEXT FILE('TRANSACT')
     *              INTO(TRAN-RECORD)
     *         END-EXEC
     *         
     *         [Apply filter by card number if provided]
     *         [Apply filter by date range if provided]
     *         
     *         IF filters match
     *             ADD 1 TO WS-REC-COUNT
     *             MOVE transaction fields to map output area
     *         END-IF
     *     END-PERFORM.
     *     
     *     EXEC CICS ENDBR FILE('TRANSACT') END-EXEC.
     *     EXEC CICS SEND MAP('COTRN00') ERASE END-EXEC.
     * </pre>
     * 
     * <p><b>Java REST Equivalent:</b></p>
     * <ul>
     *   <li>EXEC CICS STARTBR/READNEXT loop → Spring Data repository query with Pageable</li>
     *   <li>VSAM browse cursor → JPA Pageable with page number and size</li>
     *   <li>WS-REC-COUNT = 20 → Default page size of 20 transactions (@PageableDefault)</li>
     *   <li>Filter by card → Query parameter: cardNumber</li>
     *   <li>Filter by date range → Query parameters: startDate, endDate</li>
     *   <li>EXEC CICS SEND MAP → Return Page<TransactionDto> as JSON</li>
     * </ul>
     * 
     * <p><b>Validation Rules (from COTRN00C.cbl):</b></p>
     * <ul>
     *   <li>Card number must be 16 digits if provided</li>
     *   <li>Start date must be valid ISO 8601 date (yyyy-MM-dd)</li>
     *   <li>End date must be valid ISO 8601 date (yyyy-MM-dd)</li>
     *   <li>End date must be on or after start date</li>
     * </ul>
     * 
     * <p><b>Response Format:</b></p>
     * <pre>
     * {
     *   "content": [
     *     {
     *       "transId": "0000000123456789",
     *       "transCardNum": "************1234",
     *       "transTypeCd": "01",
     *       "transCatCd": 5411,
     *       "transAmt": 125.50,
     *       "transOrigTs": "2025-01-15T10:30:00",
     *       ...
     *     }
     *   ],
     *   "pageable": {...},
     *   "totalElements": 150,
     *   "totalPages": 8,
     *   "size": 20,
     *   "number": 0
     * }
     * </pre>
     * 
     * @param cardNumber Optional card number filter (16 digits). If provided, only transactions
     *                   for this card are returned. Equivalent to COBOL WS-CARD-FILTER.
     * @param startDate Optional start date filter (ISO 8601 format: yyyy-MM-dd). If provided,
     *                  only transactions on or after this date are returned. Equivalent to COBOL
     *                  WS-START-DATE filter.
     * @param endDate Optional end date filter (ISO 8601 format: yyyy-MM-dd). If provided,
     *                only transactions on or before this date are returned. Equivalent to COBOL
     *                WS-END-DATE filter.
     * @param pageable Pagination parameters (page number, page size, sort order). Default page size
     *                 is 20 to match COBOL screen capacity (WS-REC-COUNT = 20). Supports query
     *                 parameters: page=0&size=20&sort=transOrigTs,desc
     * 
     * @return ResponseEntity containing Page of TransactionDto with pagination metadata
     *         HTTP 200 OK with transaction list (empty list if no transactions match filters)
     * 
     * @throws ValidationException if card number format is invalid, if date format is invalid,
     *                            or if end date is before start date
     */
    @GetMapping
    public ResponseEntity<Page<TransactionDto>> listTransactions(
            @RequestParam(required = false) String cardNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @PageableDefault(size = 20) Pageable pageable) {
        
        log.info("Listing transactions with filters - cardNumber: {}, startDate: {}, endDate: {}, page: {}", 
                 cardNumber, startDate, endDate, pageable.getPageNumber());

        // Validate card number format if provided (matching COTRN00C.cbl validation)
        if (cardNumber != null && !cardNumber.isEmpty()) {
            validationService.validateCardNumber(cardNumber);
        }

        // Validate date range if both dates provided (matching COTRN00C.cbl date validation)
        if (startDate != null) {
            validationService.validateDate(startDate);
        }
        
        if (endDate != null) {
            validationService.validateDate(endDate);
        }

        // Validate end date is on or after start date (COBOL logic: IF END-DATE < START-DATE)
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            log.warn("Invalid date range: endDate {} is before startDate {}", endDate, startDate);
            throw new ValidationException("End date must be on or after start date");
        }

        // Call service to retrieve paginated transaction list
        // Replaces COBOL: EXEC CICS STARTBR / READNEXT loop / ENDBR
        Page<TransactionDto> transactions = transactionService.listTransactions(
                cardNumber, startDate, endDate, pageable);

        log.info("Found {} transactions (page {} of {})", 
                 transactions.getNumberOfElements(), 
                 transactions.getNumber() + 1, 
                 transactions.getTotalPages());

        return ResponseEntity.ok(transactions);
    }

    /**
     * Get transaction detail by transaction ID.
     * 
     * <p>Converted from COBOL program: <b>COTRN01C.cbl</b></p>
     * 
     * <p><b>Original COBOL Logic (COTRN01C.cbl lines 100-250):</b></p>
     * <pre>
     * PROCEDURE DIVISION.
     *     MOVE SELECTED-TRAN-ID TO TRAN-ID.
     *     
     *     EXEC CICS READ FILE('TRANSACT')
     *          RIDFLD(TRAN-ID)
     *          INTO(TRAN-RECORD)
     *          RESP(WS-RESP-CD)
     *     END-EXEC.
     *     
     *     EVALUATE WS-RESP-CD
     *         WHEN DFHRESP(NORMAL)
     *             MOVE TRAN-CARD-NUM    TO CARDNUMO OF COTRN1AO
     *             MOVE TRAN-TYPE-CD     TO TTYPCDO  OF COTRN1AO
     *             MOVE TRAN-CAT-CD      TO TCATCDO  OF COTRN1AO
     *             MOVE TRAN-AMT         TO TRNAMTO  OF COTRN1AO
     *             [Move all transaction fields to screen output]
     *             EXEC CICS SEND MAP('COTRN01') END-EXEC
     *         WHEN DFHRESP(NOTFND)
     *             MOVE 'Transaction not found' TO WS-MESSAGE
     *             PERFORM SEND-ERROR-SCREEN
     *         WHEN OTHER
     *             MOVE 'System error reading transaction' TO WS-MESSAGE
     *             PERFORM SEND-ERROR-SCREEN
     *     END-EVALUATE.
     * </pre>
     * 
     * <p><b>Java REST Equivalent:</b></p>
     * <ul>
     *   <li>EXEC CICS READ FILE('TRANSACT') RIDFLD → transactionRepository.findById()</li>
     *   <li>DFHRESP(NORMAL) → Optional.isPresent() → HTTP 200 OK</li>
     *   <li>DFHRESP(NOTFND) → Optional.isEmpty() → throw DataNotFoundException → HTTP 404</li>
     *   <li>EXEC CICS SEND MAP output → Return TransactionDto as JSON</li>
     *   <li>BMS map fields → JSON fields in TransactionDto</li>
     * </ul>
     * 
     * <p><b>Response Format:</b></p>
     * <pre>
     * {
     *   "transId": "0000000123456789",
     *   "transCardNum": "************1234",
     *   "transTypeCd": "01",
     *   "transCatCd": 5411,
     *   "transSource": "POS",
     *   "transDesc": "GROCERY STORE PURCHASE",
     *   "transAmt": 125.50,
     *   "transMerchantId": "123456789",
     *   "transMerchantName": "LOCAL GROCERY",
     *   "transMerchantCity": "SEATTLE",
     *   "transMerchantZip": "98101",
     *   "transOrigTs": "2025-01-15T10:30:00",
     *   "transProcTs": "2025-01-15T10:30:15",
     *   "createdAt": "2025-01-15T10:30:15"
     * }
     * </pre>
     * 
     * <p><b>Error Response Example (404 Not Found):</b></p>
     * <pre>
     * {
     *   "timestamp": "2025-01-15T10:30:00",
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Transaction not found: 0000000123456789",
     *   "path": "/api/transactions/0000000123456789"
     * }
     * </pre>
     * 
     * @param transactionId Transaction identifier (16-character alphanumeric). Equivalent to
     *                      COBOL TRAN-ID field (PIC X(16)) from CVTRA05Y.cpy.
     * 
     * @return ResponseEntity containing TransactionDto with all transaction details
     *         HTTP 200 OK if transaction found
     * 
     * @throws DataNotFoundException if transaction with given ID does not exist. This exception
     *                              is handled by GlobalExceptionHandler which returns HTTP 404
     *                              Not Found with standardized ErrorResponse DTO. Equivalent to
     *                              COBOL DFHRESP(NOTFND) response code.
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDto> getTransactionById(@PathVariable String transactionId) {
        
        log.info("Retrieving transaction detail for transactionId: {}", transactionId);

        // Call service to retrieve transaction by ID
        // Replaces COBOL: EXEC CICS READ FILE('TRANSACT') RIDFLD(TRAN-ID)
        // Service will throw DataNotFoundException if not found (DFHRESP(NOTFND))
        TransactionDto transaction = transactionService.getTransactionById(transactionId);

        log.info("Found transaction: {}, card: {}, amount: {}", 
                 transaction.getTransId(),
                 transaction.getTransCardNum(),
                 transaction.getTransAmt());

        // Return transaction detail (replaces EXEC CICS SEND MAP)
        return ResponseEntity.ok(transaction);
    }

    /**
     * Post new transaction with credit limit validation and account balance update.
     * 
     * <p>Converted from COBOL program: <b>COTRN02C.cbl</b> (most complex, 1400 lines, 33KB)</p>
     * 
     * <p><b>Original COBOL Logic (COTRN02C.cbl lines 200-800 - transaction posting):</b></p>
     * <pre>
     * PROCEDURE DIVISION.
     *     EXEC CICS RECEIVE MAP('COTRN02') INTO(COTRN2AI) END-EXEC.
     *     
     *     [STEP 1: Validate all input fields - lines 235-387]
     *     PERFORM VALIDATE-INPUT-DATA-FIELDS.
     *     IF ERR-FLG-ON
     *         PERFORM SEND-TRNADD-SCREEN
     *     END-IF.
     *     
     *     [STEP 2: Verify card is active - lines 400-450]
     *     EXEC CICS READ FILE('CARDFILE')
     *          RIDFLD(CARD-NUM)
     *          INTO(CARD-RECORD)
     *     END-EXEC.
     *     IF CARD-STATUS NOT = 'Y'
     *         MOVE 'Card is not active' TO WS-MESSAGE
     *         PERFORM SEND-ERROR-SCREEN
     *     END-IF.
     *     
     *     [STEP 3: Retrieve account via cross-reference - lines 460-510]
     *     EXEC CICS READ FILE('XREFFILE')
     *          RIDFLD(CARD-NUM)
     *          INTO(XREF-RECORD)
     *     END-EXEC.
     *     MOVE XREF-ACCT-ID TO ACCT-ID.
     *     
     *     [STEP 4: Read account for balance calculation - lines 520-570]
     *     EXEC CICS READ FILE('ACCTFILE')
     *          RIDFLD(ACCT-ID)
     *          INTO(ACCT-RECORD)
     *          UPDATE
     *     END-EXEC.
     *     
     *     [STEP 5: Calculate new balance - lines 580-620]
     *     IF TRAN-TYPE-CD = '01' OR '02' OR '03' OR '05' OR '06' OR '08'
     *         [Debit transaction - reduces balance]
     *         COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT
     *     ELSE
     *         [Credit transaction - increases balance]
     *         COMPUTE NEW-BALANCE = ACCT-CURR-BAL + TRAN-AMT
     *     END-IF.
     *     
     *     [STEP 6: Validate credit limit - lines 630-660]
     *     IF NEW-BALANCE > ACCT-CREDIT-LIMIT
     *         MOVE 'Transaction exceeds credit limit' TO WS-MESSAGE
     *         PERFORM SEND-ERROR-SCREEN
     *     END-IF.
     *     
     *     [STEP 7: Update account balance and cycle totals - lines 670-710]
     *     MOVE NEW-BALANCE TO ACCT-CURR-BAL.
     *     IF debit transaction
     *         ADD TRAN-AMT TO ACCT-CURR-CYC-DEBIT
     *     ELSE
     *         ADD TRAN-AMT TO ACCT-CURR-CYC-CREDIT
     *     END-IF.
     *     EXEC CICS REWRITE FILE('ACCTFILE') FROM(ACCT-RECORD) END-EXEC.
     *     
     *     [STEP 8: Create transaction record - lines 720-750]
     *     MOVE current-timestamp TO TRAN-ID.
     *     EXEC CICS WRITE FILE('TRANSACT')
     *          FROM(TRAN-RECORD)
     *          RIDFLD(TRAN-ID)
     *     END-EXEC.
     *     
     *     [STEP 9: Update transaction category balance - lines 760-800]
     *     EXEC CICS READ FILE('TCATBAL')
     *          RIDFLD(COMPOSITE-KEY)
     *          INTO(TCATBAL-RECORD)
     *          UPDATE
     *     END-EXEC.
     *     ADD TRAN-AMT TO TCAT-BAL.
     *     EXEC CICS REWRITE FILE('TCATBAL') FROM(TCATBAL-RECORD) END-EXEC.
     *     
     *     [STEP 10: Commit transaction - line 810]
     *     EXEC CICS SYNCPOINT END-EXEC.
     *     
     *     MOVE 'Transaction posted successfully' TO WS-MESSAGE.
     *     EXEC CICS SEND MAP('COTRN02') END-EXEC.
     * </pre>
     * 
     * <p><b>Java REST Equivalent:</b></p>
     * <ul>
     *   <li>EXEC CICS RECEIVE MAP → @RequestBody TransactionDto with @Valid annotation</li>
     *   <li>PERFORM VALIDATE-INPUT-DATA-FIELDS → ValidationService + Bean Validation</li>
     *   <li>EXEC CICS READ FILE('CARDFILE') → cardService.verifyCardActive()</li>
     *   <li>EXEC CICS READ FILE('XREFFILE') → xrefRepository.findByCardNum()</li>
     *   <li>EXEC CICS READ FILE('ACCTFILE') UPDATE → accountRepository.findById() with lock</li>
     *   <li>COMPUTE NEW-BALANCE → BigDecimal.add/subtract with scale 2, HALF_UP rounding</li>
     *   <li>Credit limit check → if (newBalance.compareTo(creditLimit) > 0) throw exception</li>
     *   <li>EXEC CICS REWRITE FILE('ACCTFILE') → accountRepository.save()</li>
     *   <li>EXEC CICS WRITE FILE('TRANSACT') → transactionRepository.save()</li>
     *   <li>EXEC CICS REWRITE FILE('TCATBAL') → tcatBalanceRepository.save()</li>
     *   <li>EXEC CICS SYNCPOINT → @Transactional annotation (service layer)</li>
     *   <li>EXEC CICS SEND MAP success → Return HTTP 201 Created with TransactionDto</li>
     * </ul>
     * 
     * <p><b>COMP-3 Precision Preservation (Agent Action Plan Section 0.7.3):</b></p>
     * <p>All COBOL PIC S9(09)V99 COMP-3 packed decimal fields are converted to BigDecimal
     * with scale 2 and RoundingMode.HALF_UP to ensure bit-identical results to mainframe:</p>
     * <pre>
     * COBOL: COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT.
     * Java:  newBalance = currentBalance.subtract(transactionAmount).setScale(2, RoundingMode.HALF_UP);
     * 
     * Example: currentBalance = 5000.00, transactionAmount = 125.50
     *          newBalance = 5000.00 - 125.50 = 4874.50 (exact COBOL COMP-3 result)
     * </pre>
     * 
     * <p><b>Transaction Type Classification:</b></p>
     * <ul>
     *   <li><b>Debit Transactions (reduce balance):</b> '01' Purchase, '02' Cash Advance, 
     *       '03' Balance Transfer, '05' Fee, '06' Interest Charge, '08' Debit Adjustment</li>
     *   <li><b>Credit Transactions (increase balance):</b> '04' Payment, '07' Credit Adjustment</li>
     * </ul>
     * 
     * <p><b>Request Body Example:</b></p>
     * <pre>
     * {
     *   "transCardNum": "4000123412341234",
     *   "transTypeCd": "01",
     *   "transCatCd": 5411,
     *   "transSource": "POS",
     *   "transDesc": "GROCERY STORE PURCHASE",
     *   "transAmt": 125.50,
     *   "transMerchantId": "123456789",
     *   "transMerchantName": "LOCAL GROCERY",
     *   "transMerchantCity": "SEATTLE",
     *   "transMerchantZip": "98101",
     *   "transOrigTs": "2025-01-15T10:30:00"
     * }
     * </pre>
     * 
     * <p><b>Success Response (201 Created):</b></p>
     * <pre>
     * {
     *   "transId": "0000000123456789",
     *   "transCardNum": "************1234",
     *   "transTypeCd": "01",
     *   "transCatCd": 5411,
     *   "transAmt": 125.50,
     *   "transOrigTs": "2025-01-15T10:30:00",
     *   "transProcTs": "2025-01-15T10:30:15",
     *   ...
     * }
     * </pre>
     * 
     * <p><b>Error Response Examples:</b></p>
     * <pre>
     * // Validation Failure (400 Bad Request)
     * {
     *   "timestamp": "2025-01-15T10:30:00",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "Amount must be positive",
     *   "path": "/api/transactions"
     * }
     * 
     * // Credit Limit Exceeded (409 Conflict)
     * {
     *   "timestamp": "2025-01-15T10:30:00",
     *   "status": 409,
     *   "error": "Conflict",
     *   "message": "Transaction exceeds credit limit",
     *   "path": "/api/transactions"
     * }
     * 
     * // Card Not Found (404 Not Found)
     * {
     *   "timestamp": "2025-01-15T10:30:00",
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Card not found: 4000123412341234",
     *   "path": "/api/transactions"
     * }
     * </pre>
     * 
     * @param transactionDto Transaction details from client (JSON request body). All fields
     *                       validated using @Valid annotation triggering Bean Validation constraints.
     *                       Equivalent to COBOL BMS map COTRN2AI input fields.
     * 
     * @return ResponseEntity containing created TransactionDto with generated transaction ID
     *         HTTP 201 Created on successful transaction posting
     * 
     * @throws ValidationException if any field validation fails (mandatory fields missing, invalid
     *                            format, invalid transaction type/category codes). Equivalent to COBOL
     *                            ERR-FLG-ON flag triggering error screen display.
     * 
     * @throws DataNotFoundException if card not found, if account not found via cross-reference, or
     *                              if transaction category not found. Equivalent to COBOL DFHRESP(NOTFND).
     * 
     * @throws BusinessException if card is inactive/expired, if credit limit would be exceeded by
     *                          transaction posting, or if duplicate transaction detected. Equivalent
     *                          to COBOL business validation errors with WS-MESSAGE display.
     */
    @PostMapping
    public ResponseEntity<TransactionDto> postTransaction(@Valid @RequestBody TransactionDto transactionDto) {
        
        log.info("Posting new transaction - card: {}, amount: {}, type: {}, category: {}", 
                 transactionDto.getTransCardNum(),
                 transactionDto.getTransAmt(),
                 transactionDto.getTransTypeCd(),
                 transactionDto.getTransCatCd());

        // Additional validation beyond Bean Validation annotations
        // Replaces COBOL: PERFORM VALIDATE-INPUT-DATA-FIELDS (lines 235-387)
        
        // Validate card number format (COBOL: IF CARDNINI NOT NUMERIC)
        if (transactionDto.getTransCardNum() != null) {
            validationService.validateCardNumber(transactionDto.getTransCardNum());
        }

        // Validate transaction amount (COBOL: IF TRNAMTI validation)
        if (transactionDto.getTransAmt() != null) {
            validationService.validateAmount(transactionDto.getTransAmt());
        }

        // Validate transaction type code (COBOL: IF TTYPCDI validation)
        if (transactionDto.getTransTypeCd() != null) {
            validationService.validateTransactionType(transactionDto.getTransTypeCd());
        }

        // Validate transaction category code (COBOL: IF TCATCDI validation)
        if (transactionDto.getTransCatCd() != null) {
            validationService.validateTransactionCategory(transactionDto.getTransCatCd());
        }

        // Call service to post transaction
        // Service implements all business logic from COTRN02C.cbl lines 400-810:
        // 1. Verify card active (EXEC CICS READ FILE('CARDFILE'))
        // 2. Retrieve account (EXEC CICS READ FILE('XREFFILE'), FILE('ACCTFILE'))
        // 3. Calculate new balance with COMP-3 precision (COMPUTE NEW-BALANCE)
        // 4. Validate credit limit (IF NEW-BALANCE > ACCT-CREDIT-LIMIT)
        // 5. Update account balance atomically (EXEC CICS REWRITE FILE('ACCTFILE'))
        // 6. Create transaction record (EXEC CICS WRITE FILE('TRANSACT'))
        // 7. Update category balance (EXEC CICS REWRITE FILE('TCATBAL'))
        // 8. Commit transaction (@Transactional = EXEC CICS SYNCPOINT)
        TransactionDto postedTransaction = transactionService.postTransaction(transactionDto);

        log.info("Transaction posted successfully - transId: {}, newBalance calculated with COMP-3 precision", 
                 postedTransaction.getTransId());

        // Return HTTP 201 Created with posted transaction
        // Replaces COBOL: EXEC CICS SEND MAP('COTRN02') with success message
        return ResponseEntity.status(HttpStatus.CREATED).body(postedTransaction);
    }
}
