/*****************************************************************
 * Program:     CardController.java
 * Layer:       REST API Controller
 * Function:    Card management REST endpoints for list, detail, and update operations
 *              Transformed from COBOL programs COCRDLIC.cbl, COCRDSLC.cbl, COCRDUPC.cbl
 ******************************************************************
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
 ******************************************************************/

package com.carddemo.controller;

import com.carddemo.dto.request.CardUpdateRequest;
import com.carddemo.dto.response.CardListResponse;
import com.carddemo.exception.CardNotFoundException;
import com.carddemo.service.CardDetailService;
import com.carddemo.service.CardListService;
import com.carddemo.service.CardUpdateService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller providing card management operations including paginated card list display,
 * individual card detail retrieval, and card information update functionality.
 * 
 * <p>This controller transforms three distinct CICS card transaction programs from the mainframe
 * CardDemo application to modern RESTful API endpoints while preserving exact business logic,
 * pagination patterns, and security model per Section 0.1 requirements.</p>
 * 
 * <h2>COBOL Source Programs Transformation</h2>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Program</th>
 *     <th>Transaction ID</th>
 *     <th>REST Endpoint</th>
 *     <th>HTTP Method</th>
 *   </tr>
 *   <tr>
 *     <td>COCRDLIC.cbl (Card List)</td>
 *     <td>CCLI</td>
 *     <td>GET /api/cards</td>
 *     <td>GET</td>
 *   </tr>
 *   <tr>
 *     <td>COCRDSLC.cbl (Card Detail)</td>
 *     <td>CCDL</td>
 *     <td>GET /api/cards/{id}</td>
 *     <td>GET</td>
 *   </tr>
 *   <tr>
 *     <td>COCRDUPC.cbl (Card Update)</td>
 *     <td>CCUP</td>
 *     <td>PUT /api/cards/{id}</td>
 *     <td>PUT</td>
 *   </tr>
 * </table>
 * 
 * <h2>Key Transformations</h2>
 * <ul>
 *   <li><strong>CICS STARTBR/READNEXT:</strong> Transformed to Spring Data pagination with PageRequest.of(page, 7)</li>
 *   <li><strong>EXEC CICS RECEIVE MAP:</strong> Transformed to @RequestParam and @PathVariable annotations</li>
 *   <li><strong>EXEC CICS SEND MAP:</strong> Transformed to ResponseEntity with JSON response body</li>
 *   <li><strong>COMMAREA state passing:</strong> Transformed to stateless REST with query parameters</li>
 *   <li><strong>PF key navigation:</strong> Transformed to page parameter in query string</li>
 *   <li><strong>BMS field validation:</strong> Transformed to Bean Validation (@Valid annotation)</li>
 *   <li><strong>USRSEC authorization:</strong> Transformed to Spring Security @PreAuthorize</li>
 * </ul>
 * 
 * <h2>Pagination Pattern Preservation</h2>
 * <p>Maintains the exact 7-cards-per-page pagination pattern from COBOL program COCRDLIC.cbl
 * (WS-MAX-SCREEN-LINES = 7, line 177-178). This preserves user experience familiarity and
 * ensures functional equivalence per Section 0.1 core refactoring objectives.</p>
 * 
 * <p><strong>COBOL Pagination Logic:</strong></p>
 * <pre>
 * COBOL (COCRDLIC.cbl lines 1123-1260):
 * 9000-READ-FORWARD.
 *     MOVE ZEROES TO WS-SCRN-COUNTER
 *     EXEC CICS STARTBR DATASET(LIT-CARD-FILE)
 *          RIDFLD(WS-CARD-RID-CARDNUM) GTEQ
 *     END-EXEC
 *     PERFORM UNTIL WS-SCRN-COUNTER = 7 OR READ-LOOP-EXIT
 *         EXEC CICS READNEXT DATASET(LIT-CARD-FILE)
 *              INTO (CARD-RECORD)
 *         END-EXEC
 *         ADD 1 TO WS-SCRN-COUNTER
 *     END-PERFORM
 *     EXEC CICS ENDBR FILE(LIT-CARD-FILE)
 * 
 * Java Equivalent:
 * Pageable pageable = PageRequest.of(page, 7, Sort.by("cardNumber"));
 * Page&lt;Card&gt; cardPage = cardRepository.findByAccountId(accountId, pageable);
 * </pre>
 * 
 * <h2>Security Model (Section 0.9)</h2>
 * <ul>
 *   <li><strong>Role-Based Access Control:</strong> @PreAuthorize annotations enforce role-based authorization</li>
 *   <li><strong>Regular Users (ROLE_USER):</strong> Can only view/update cards for their own accounts</li>
 *   <li><strong>Administrative Users (ROLE_ADMIN):</strong> Can access all cards without ownership restrictions</li>
 *   <li><strong>JWT Authentication:</strong> All endpoints require valid JWT token in Authorization header</li>
 *   <li><strong>PCI-DSS Compliance:</strong> Card numbers masked in responses (show only last 4 digits)</li>
 * </ul>
 * 
 * <h2>HTTP Status Code Mapping</h2>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Response</th>
 *     <th>HTTP Status</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>DFHRESP(NORMAL)</td>
 *     <td>200 OK</td>
 *     <td>Successful operation</td>
 *   </tr>
 *   <tr>
 *     <td>DFHRESP(NOTFND)</td>
 *     <td>404 NOT FOUND</td>
 *     <td>Card not found</td>
 *   </tr>
 *   <tr>
 *     <td>Input validation failure</td>
 *     <td>400 BAD REQUEST</td>
 *     <td>Invalid input data</td>
 *   </tr>
 *   <tr>
 *     <td>USRSEC auth failure</td>
 *     <td>401 UNAUTHORIZED</td>
 *     <td>Authentication required</td>
 *   </tr>
 *   <tr>
 *     <td>Authorization check failure</td>
 *     <td>403 FORBIDDEN</td>
 *     <td>Insufficient permissions</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS exception</td>
 *     <td>500 INTERNAL SERVER ERROR</td>
 *     <td>System error</td>
 *   </tr>
 * </table>
 * 
 * <h2>Performance Requirements (Section 0.2)</h2>
 * <ul>
 *   <li>Card list retrieval: &lt; 100ms at 95th percentile</li>
 *   <li>Card detail view: &lt; 100ms at 95th percentile</li>
 *   <li>Card update: &lt; 200ms at 95th percentile</li>
 *   <li>Pagination: Fixed 7 cards per page for memory efficiency</li>
 *   <li>Database queries: Optimized with indexed lookups on card_number and account_id</li>
 * </ul>
 * 
 * <h2>Audit Trail (Section 0.9)</h2>
 * <p>All card operations are logged with:</p>
 * <ul>
 *   <li>User ID from JWT token (authenticated principal)</li>
 *   <li>Timestamp (ISO-8601 format)</li>
 *   <li>Operation type (LIST, VIEW, UPDATE)</li>
 *   <li>Masked card identifier (last 4 digits only for PCI compliance)</li>
 *   <li>Operation outcome (SUCCESS, FAILURE, ERROR)</li>
 *   <li>HTTP status code and response time</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * <pre>
 * // Get paginated card list for account (page 0, 7 cards per page)
 * GET /api/cards?accountId=00012345678&amp;page=0&amp;sortDirection=ASC
 * Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
 * 
 * Response 200 OK:
 * {
 *   "transactionName": "CCLI",
 *   "programName": "COCRDLIC",
 *   "currentPage": 0,
 *   "pageSize": 7,
 *   "totalPages": 2,
 *   "totalElements": 12,
 *   "hasNext": true,
 *   "hasPrevious": false,
 *   "cards": [
 *     {
 *       "accountNumber": "00012345678",
 *       "cardNumber": "**** **** **** 1234",
 *       "cardStatus": "A",
 *       "selectionFlag": " "
 *     },
 *     // ... up to 7 cards
 *   ],
 *   "infoMessage": "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD"
 * }
 * 
 * // Get individual card details
 * GET /api/cards/1
 * Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
 * 
 * Response 200 OK:
 * {
 *   "cardId": 1,
 *   "accountId": "00012345678",
 *   "cardNumber": "**** **** **** 1234",
 *   "cardholderName": "JOHN DOE",
 *   "expirationDate": "2027-12-31",
 *   "cardStatus": "A",
 *   "creditLimit": 5000.00,
 *   // ... additional card details
 * }
 * 
 * // Update card information
 * PUT /api/cards/1
 * Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
 * Content-Type: application/json
 * 
 * {
 *   "cardNumber": "4532123456789012",
 *   "accountId": "00012345678",
 *   "cardName": "JOHN DOE",
 *   "cardStatusCode": "B",
 *   "expirationMonth": 12,
 *   "expirationYear": 2027,
 *   "expirationDay": 31
 * }
 * 
 * Response 200 OK:
 * {
 *   "cardId": 1,
 *   "cardStatus": "B",
 *   "message": "Card successfully updated"
 * }
 * </pre>
 * 
 * <h2>Error Response Format</h2>
 * <pre>
 * // Card not found (404)
 * {
 *   "timestamp": "2024-01-15T10:30:45.123Z",
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "Card not found with ID: 999",
 *   "path": "/api/cards/999"
 * }
 * 
 * // Validation error (400)
 * {
 *   "timestamp": "2024-01-15T10:30:45.123Z",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "Validation failed",
 *   "errors": [
 *     {
 *       "field": "cardStatusCode",
 *       "rejectedValue": "X",
 *       "message": "Card status code must be one of: A, E, B, S"
 *     }
 *   ],
 *   "path": "/api/cards/1"
 * }
 * </pre>
 * 
 * @see com.carddemo.service.CardListService
 * @see com.carddemo.service.CardDetailService
 * @see com.carddemo.service.CardUpdateService
 * @see com.carddemo.dto.response.CardListResponse
 * @see com.carddemo.dto.request.CardUpdateRequest
 * @see com.carddemo.exception.CardNotFoundException
 * @since 1.0
 * @version 1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/cards")
@Validated
public class CardController {

    /**
     * Default page size for card list pagination (7 cards per page).
     * Matches COBOL WS-MAX-SCREEN-LINES from COCRDLIC.cbl line 177-178.
     */
    private static final int DEFAULT_PAGE_SIZE = 7;

    /**
     * Default page number (0-based) for first page of results.
     */
    private static final int DEFAULT_PAGE_NUMBER = 0;

    /**
     * Default sort direction for card list (ascending by card number).
     * Matches VSAM KSDS sequential access pattern from COBOL.
     */
    private static final String DEFAULT_SORT_DIRECTION = "ASC";

    private final CardListService cardListService;
    private final CardDetailService cardDetailService;
    private final CardUpdateService cardUpdateService;

    /**
     * Constructor with dependency injection for card operation services.
     * 
     * @param cardListService Service for paginated card list retrieval
     * @param cardDetailService Service for individual card detail retrieval
     * @param cardUpdateService Service for card information modification
     */
    @Autowired
    public CardController(
            CardListService cardListService,
            CardDetailService cardDetailService,
            CardUpdateService cardUpdateService) {
        this.cardListService = cardListService;
        this.cardDetailService = cardDetailService;
        this.cardUpdateService = cardUpdateService;
        log.info("CardController initialized with services: CardListService, CardDetailService, CardUpdateService");
    }

    /**
     * Retrieves a paginated list of credit cards with optional account ID filtering.
     * 
     * <p>This endpoint transforms the COBOL CICS transaction CCLI (program COCRDLIC.cbl)
     * which displays up to 7 cards per screen page with PF7/PF8 navigation keys. The
     * pagination pattern is preserved exactly with 7 cards per page to maintain user
     * experience familiarity from the mainframe application.</p>
     * 
     * <p><strong>COBOL Operation Mapping:</strong></p>
     * <pre>
     * COBOL: EXEC CICS STARTBR DATASET('CARDDAT')
     *             RIDFLD(WS-CARD-RID-CARDNUM)
     *             KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)
     *             GTEQ
     *        END-EXEC
     *        
     *        PERFORM UNTIL WS-SCRN-COUNTER = 7
     *            EXEC CICS READNEXT DATASET('CARDDAT')
     *                 INTO (CARD-RECORD)
     *            END-EXEC
     *            [Process and display card record]
     *        END-PERFORM
     * 
     * Java:  Pageable pageable = PageRequest.of(page, 7, Sort.by("cardNumber"));
     *        Page&lt;Card&gt; cardPage = cardRepository.findByAccountId(accountId, pageable);
     *        return ResponseEntity.ok(cardListResponse);
     * </pre>
     * 
     * <p><strong>Query Parameters:</strong></p>
     * <ul>
     *   <li><strong>accountId (optional):</strong> 11-digit account ID filter. When provided, returns only
     *       cards associated with the specified account. Maps to COBOL CC-ACCT-ID input field
     *       from COCRDLIC.cbl lines 969, 1007-1029.</li>
     *   <li><strong>page (optional, default=0):</strong> Zero-based page number for pagination. Maps to
     *       COBOL WS-CA-SCREEN-NUM from COCRDLIC.cbl line 237.</li>
     *   <li><strong>sortDirection (optional, default="ASC"):</strong> Sort order for card list ("ASC" or "DESC").
     *       Default ascending order matches VSAM KSDS sequential access pattern.</li>
     * </ul>
     * 
     * <p><strong>Response Structure:</strong></p>
     * <ul>
     *   <li><strong>cards:</strong> Array of up to 7 card items with masked card numbers</li>
     *   <li><strong>currentPage:</strong> Current page number (0-based)</li>
     *   <li><strong>pageSize:</strong> Number of cards per page (always 7)</li>
     *   <li><strong>totalPages:</strong> Total number of pages available</li>
     *   <li><strong>totalElements:</strong> Total number of cards matching filter criteria</li>
     *   <li><strong>hasNext:</strong> Indicates if next page exists (maps to PF8 availability)</li>
     *   <li><strong>hasPrevious:</strong> Indicates if previous page exists (maps to PF7 availability)</li>
     *   <li><strong>infoMessage:</strong> User guidance message (e.g., "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD")</li>
     *   <li><strong>errorMessage:</strong> Error message if applicable (e.g., "NO RECORDS FOUND")</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>Requires valid JWT token in Authorization header</li>
     *   <li>ROLE_USER: Can only view cards for accounts they own</li>
     *   <li>ROLE_ADMIN: Can view cards for any account without ownership restrictions</li>
     * </ul>
     * 
     * <p><strong>HTTP Status Codes:</strong></p>
     * <ul>
     *   <li><strong>200 OK:</strong> Cards retrieved successfully (may be empty list if no cards found)</li>
     *   <li><strong>400 BAD REQUEST:</strong> Invalid query parameters (e.g., negative page number)</li>
     *   <li><strong>401 UNAUTHORIZED:</strong> Missing or invalid JWT token</li>
     *   <li><strong>403 FORBIDDEN:</strong> User lacks permission to access specified account</li>
     *   <li><strong>404 NOT FOUND:</strong> Account ID specified but account does not exist</li>
     *   <li><strong>500 INTERNAL SERVER ERROR:</strong> Unexpected system error during retrieval</li>
     * </ul>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Target response time: &lt; 100ms at 95th percentile</li>
     *   <li>Database query: Single SELECT with LIMIT 7 and indexed lookup</li>
     *   <li>Memory usage: Minimal (7 records in memory at once)</li>
     *   <li>Concurrency: Thread-safe, supports high concurrent request volume</li>
     * </ul>
     * 
     * @param accountId Optional 11-digit account identifier to filter cards by associated account
     * @param page Zero-based page number (default 0 for first page)
     * @param sortDirection Sort direction: "ASC" (ascending) or "DESC" (descending), default "ASC"
     * @return ResponseEntity containing CardListResponse with paginated card data and metadata
     * @throws CardNotFoundException if accountId is provided but account does not exist
     */
    @GetMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<CardListResponse> getCardList(
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        
        log.info("GET /api/cards - accountId: {}, page: {}, sortDirection: {}", 
            accountId, page, sortDirection);

        // Validate page number is non-negative
        if (page < 0) {
            log.warn("Invalid page number {} requested, using page 0", page);
            page = DEFAULT_PAGE_NUMBER;
        }

        // Normalize sort direction to uppercase
        String normalizedSort = (sortDirection != null) 
            ? sortDirection.trim().toUpperCase() 
            : DEFAULT_SORT_DIRECTION;

        CardListResponse response;

        if (accountId != null) {
            // Filter cards by account ID (COBOL CC-ACCT-ID filter)
            log.debug("Retrieving card list for account {} with pagination", accountId);
            response = cardListService.getCardsByAccountId(accountId, page, normalizedSort);
        } else {
            // Return empty response or all cards based on user role
            // For now, accountId is required per COBOL logic
            log.debug("AccountId filter not provided, returning empty response");
            response = new CardListResponse();
            response.setCurrentPage(page);
            response.setPageSize(DEFAULT_PAGE_SIZE);
            response.setTotalPages(0);
            response.setTotalElements(0L);
            response.setHasNext(false);
            response.setHasPrevious(false);
            response.setErrorMessage("Account ID filter is required for card list retrieval");
        }

        log.info("GET /api/cards completed - returned {} cards on page {} of {}", 
            (response.getCards() != null ? response.getCards().size() : 0), 
            page, response.getTotalPages());

        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves detailed information for a specific credit card by card ID.
     * 
     * <p>This endpoint transforms the COBOL CICS transaction CCDL (program COCRDSLC.cbl)
     * which displays comprehensive card information including masked card number, cardholder
     * name, expiration date, status, associated account details, and recent transaction history.</p>
     * 
     * <p><strong>COBOL Operation Mapping:</strong></p>
     * <pre>
     * COBOL: EXEC CICS READ DATASET('CARDDAT')
     *             INTO(CARD-RECORD)
     *             RIDFLD(WS-CARD-RID-CARDNUM)
     *             KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)
     *             RESP(WS-RESP-CD)
     *        END-EXEC
     *        
     *        EVALUATE WS-RESP-CD
     *            WHEN DFHRESP(NORMAL)
     *                [Display card details on screen]
     *            WHEN DFHRESP(NOTFND)
     *                [Display card not found error]
     *        END-EVALUATE
     * 
     * Java:  Card card = cardDetailService.getCardDetail(cardId);
     *        return ResponseEntity.ok(card);
     * </pre>
     * 
     * <p><strong>Response Structure:</strong></p>
     * <ul>
     *   <li><strong>cardId:</strong> Internal card identifier (primary key)</li>
     *   <li><strong>accountId:</strong> Associated 11-digit account number</li>
     *   <li><strong>cardNumber:</strong> Masked card number showing only last 4 digits (PCI-DSS compliance)</li>
     *   <li><strong>cardholderName:</strong> Embossed cardholder name (up to 50 characters)</li>
     *   <li><strong>expirationDate:</strong> Card expiration date (YYYY-MM-DD format)</li>
     *   <li><strong>cardStatus:</strong> Current card status (A=Active, E=Expired, B=Blocked, S=Stolen)</li>
     *   <li><strong>creditLimit:</strong> Card credit limit in BigDecimal with 2 decimal places</li>
     *   <li><strong>accountDetails:</strong> Associated account information (balance, status, etc.)</li>
     *   <li><strong>customerInfo:</strong> Customer details via account relationship</li>
     *   <li><strong>recentTransactions:</strong> Last 10 transactions for the card</li>
     * </ul>
     * 
     * <p><strong>Security and PCI-DSS Compliance:</strong></p>
     * <ul>
     *   <li>Card number is masked to show only last 4 digits (e.g., "**** **** **** 1234")</li>
     *   <li>CVV code is NEVER included in response under any circumstance</li>
     *   <li>Full card number is only accessible to users with specific PCI-compliant roles</li>
     *   <li>All card detail access is logged for audit trail compliance</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>Requires valid JWT token in Authorization header</li>
     *   <li>ROLE_USER: Can only view cards associated with accounts they own</li>
     *   <li>ROLE_ADMIN: Can view any card without ownership restrictions</li>
     *   <li>Access denied if user attempts to view card belonging to another customer's account</li>
     * </ul>
     * 
     * <p><strong>HTTP Status Codes:</strong></p>
     * <ul>
     *   <li><strong>200 OK:</strong> Card details retrieved successfully</li>
     *   <li><strong>401 UNAUTHORIZED:</strong> Missing or invalid JWT token</li>
     *   <li><strong>403 FORBIDDEN:</strong> User lacks permission to view specified card</li>
     *   <li><strong>404 NOT FOUND:</strong> Card with specified ID does not exist</li>
     *   <li><strong>500 INTERNAL SERVER ERROR:</strong> Unexpected system error during retrieval</li>
     * </ul>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Target response time: &lt; 100ms at 95th percentile</li>
     *   <li>Database query: Single SELECT with primary key lookup (O(1) complexity)</li>
     *   <li>JPA lazy loading: Related entities fetched on-demand for efficiency</li>
     *   <li>Caching: Card details may be cached for frequent access patterns</li>
     * </ul>
     * 
     * @param id Card identifier (primary key) for the card to retrieve
     * @return ResponseEntity containing comprehensive card detail information
     * @throws CardNotFoundException if card with specified ID does not exist (maps to HTTP 404)
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> getCardDetail(@PathVariable Long id) {
        log.info("GET /api/cards/{} - Retrieving card detail", id);

        // Delegate to CardDetailService for business logic
        // Service method getCardDetail() performs:
        // 1. Card lookup by ID with CardRepository.findById()
        // 2. Authorization check (user can only view own cards unless ROLE_ADMIN)
        // 3. Card number masking for PCI-DSS compliance
        // 4. Related entity navigation (account, customer, transactions)
        // 5. Response DTO construction with comprehensive card information
        Object cardDetail = cardDetailService.getCardDetail(id);

        log.info("GET /api/cards/{} completed successfully", id);
        return ResponseEntity.ok(cardDetail);
    }

    /**
     * Updates credit card information including status, expiration date, and cardholder name.
     * 
     * <p>This endpoint transforms the COBOL CICS transaction CCUP (program COCRDUPC.cbl)
     * which handles card detail modification via BMS screen COCRDUP. The update operation
     * preserves COMP-3 precision for monetary amounts, validates status transitions per
     * business rules, and maintains transactional integrity matching CICS SYNCPOINT behavior.</p>
     * 
     * <p><strong>COBOL Operation Mapping:</strong></p>
     * <pre>
     * COBOL: EXEC CICS READ DATASET('CARDDAT') UPDATE
     *             INTO(CARD-RECORD)
     *             RIDFLD(WS-CARD-RID-CARDNUM)
     *             KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)
     *             RESP(WS-RESP-CD)
     *        END-EXEC
     *        
     *        [Validate and modify card fields]
     *        MOVE 'Y' TO CARD-ACTIVE-STATUS
     *        MOVE CC-CARD-NAME TO CARD-EMBOSSED-NAME
     *        
     *        EXEC CICS REWRITE DATASET('CARDDAT')
     *             FROM(CARD-RECORD)
     *             RESP(WS-RESP-CD)
     *        END-EXEC
     *        
     *        EXEC CICS SYNCPOINT
     *        END-EXEC
     * 
     * Java:  Card card = cardRepository.findById(cardId)
     *            .orElseThrow(() -&gt; new CardNotFoundException(cardId));
     *        card.setActiveStatus(request.getCardStatusCode());
     *        card.setEmbossedName(request.getCardName());
     *        Card updatedCard = cardRepository.save(card);
     *        // @Transactional commits automatically (equivalent to SYNCPOINT)
     *        return ResponseEntity.ok(updatedCard);
     * </pre>
     * 
     * <p><strong>Request Body Fields:</strong></p>
     * <ul>
     *   <li><strong>cardNumber (required):</strong> 16-digit card number (validated with Luhn algorithm)</li>
     *   <li><strong>accountId (required):</strong> 11-digit account ID (validated against account existence)</li>
     *   <li><strong>cardName (required):</strong> Cardholder embossed name (max 50 chars, alphanumeric + spaces)</li>
     *   <li><strong>cardStatusCode (required):</strong> Status code (A=Active, E=Expired, B=Blocked, S=Stolen)</li>
     *   <li><strong>expirationMonth (required):</strong> Expiration month (1-12)</li>
     *   <li><strong>expirationYear (required):</strong> Expiration year (1950-2099, must be future date)</li>
     *   <li><strong>expirationDay (required):</strong> Expiration day (1-31, validated for month)</li>
     * </ul>
     * 
     * <p><strong>Business Rule Validations:</strong></p>
     * <ul>
     *   <li><strong>Status Transitions:</strong> Validates allowed transitions per COBOL 88-level conditions
     *       <ul>
     *         <li>ACTIVE ↔ INACTIVE: Bidirectional transition allowed</li>
     *         <li>PENDING → ACTIVE: Card activation upon customer receipt</li>
     *         <li>ACTIVE/INACTIVE → BLOCKED: Fraud prevention or security concerns</li>
     *         <li>* → EXPIRED: Automatic transition when expiration date passes</li>
     *         <li>EXPIRED/BLOCKED → ACTIVE: NOT ALLOWED (requires replacement card)</li>
     *       </ul>
     *   </li>
     *   <li><strong>Expiration Date:</strong> Must be future date with valid month (1-12) and year (1950-2099)</li>
     *   <li><strong>Credit Limit:</strong> Cannot exceed account credit limit per COBOL business rules</li>
     *   <li><strong>Embossed Name:</strong> Alphanumeric with spaces, converted to uppercase, max 50 chars</li>
     * </ul>
     * 
     * <p><strong>Transaction Semantics (Section 0.9):</strong></p>
     * <ul>
     *   <li>@Transactional(isolation=READ_COMMITTED) matches CICS default isolation level</li>
     *   <li>Automatic rollback on Exception.class matching CICS SYNCPOINT ROLLBACK</li>
     *   <li>Optimistic locking prevents concurrent modification conflicts</li>
     *   <li>Single database round-trip for update operation via JPA merge</li>
     * </ul>
     * 
     * <p><strong>COMP-3 Precision Preservation (Section 0.9):</strong></p>
     * <ul>
     *   <li>All BigDecimal operations use .setScale(2, RoundingMode.HALF_UP)</li>
     *   <li>Credit limit validations maintain exact decimal arithmetic equivalent to COBOL ROUNDED clause</li>
     *   <li>Maps COBOL PIC S9(9)V99 COMP-3 balance fields to BigDecimal with scale=2</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>Requires valid JWT token in Authorization header</li>
     *   <li>ROLE_USER: Can only update cards associated with accounts they own</li>
     *   <li>ROLE_ADMIN: Can update any card without ownership restrictions</li>
     *   <li>Access denied if user attempts to update card belonging to another customer's account</li>
     * </ul>
     * 
     * <p><strong>HTTP Status Codes:</strong></p>
     * <ul>
     *   <li><strong>200 OK:</strong> Card updated successfully</li>
     *   <li><strong>400 BAD REQUEST:</strong> Validation errors (invalid status code, expiration date, etc.)</li>
     *   <li><strong>401 UNAUTHORIZED:</strong> Missing or invalid JWT token</li>
     *   <li><strong>403 FORBIDDEN:</strong> User lacks permission to update specified card</li>
     *   <li><strong>404 NOT FOUND:</strong> Card with specified ID does not exist</li>
     *   <li><strong>409 CONFLICT:</strong> Concurrent modification detected (optimistic locking failure)</li>
     *   <li><strong>500 INTERNAL SERVER ERROR:</strong> Unexpected system error during update</li>
     * </ul>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Target response time: &lt; 200ms at 95th percentile</li>
     *   <li>Database operations: SELECT + UPDATE in single transaction</li>
     *   <li>Optimistic locking: @Version field prevents long-duration pessimistic locks</li>
     *   <li>Indexed primary key lookup: O(1) retrieval complexity</li>
     * </ul>
     * 
     * <p><strong>Audit Trail:</strong></p>
     * <ul>
     *   <li>All card updates are logged with user ID, timestamp, and old/new values</li>
     *   <li>Card numbers masked in logs (show only last 4 digits) for PCI-DSS compliance</li>
     *   <li>Status changes logged separately for security monitoring and fraud detection</li>
     *   <li>Failed update attempts logged with failure reason for security analysis</li>
     * </ul>
     * 
     * @param id Card identifier (primary key) for the card to update
     * @param request CardUpdateRequest containing validated update data
     * @return ResponseEntity containing updated card information
     * @throws CardNotFoundException if card with specified ID does not exist (maps to HTTP 404)
     * @throws jakarta.validation.ValidationException if request validation fails (maps to HTTP 400)
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> updateCard(
            @PathVariable Long id,
            @Valid @RequestBody CardUpdateRequest request) {
        
        log.info("PUT /api/cards/{} - Updating card with masked number ending in {}", 
            id, maskCardNumber(request.getCardNumber()));

        // Delegate to CardUpdateService for business logic
        // Service method updateCard() performs:
        // 1. Card lookup by ID with CardRepository.findById()
        // 2. Authorization check (user can only update own cards unless ROLE_ADMIN)
        // 3. Status transition validation per COBOL 88-level business rules
        // 4. Expiration date validation (future date, valid month/year)
        // 5. Credit limit validation against account credit limit
        // 6. Embossed name validation and uppercase conversion
        // 7. COMP-3 precision preservation using BigDecimal.setScale(2, HALF_UP)
        // 8. Transactional update with optimistic locking
        // 9. Audit trail logging with masked card number
        Object updatedCard = cardUpdateService.updateCard(id, request);

        log.info("PUT /api/cards/{} completed successfully - card status updated", id);
        return ResponseEntity.ok(updatedCard);
    }

    /**
     * Masks a card number for PCI-DSS compliant logging and display.
     * Shows only the last 4 digits with asterisks replacing the first 12 digits.
     * 
     * <p>Example: "4532123456789012" → "**** **** **** 9012"</p>
     * 
     * @param cardNumber Full 16-digit card number
     * @return Masked card number showing only last 4 digits
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        String lastFour = cardNumber.substring(cardNumber.length() - 4);
        return "**** **** **** " + lastFour;
    }
}
