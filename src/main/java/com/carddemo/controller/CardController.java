/*
 * CardController.java
 * 
 * REST API controller for credit card management operations replacing CICS
 * transactions CCLI (card list from COCRDLIC.cbl), CCDL (card detail view
 * from COCRDSLC.cbl), and CCUP (card update from COCRDUPC.cbl).
 * 
 * Exposes three primary endpoints:
 * - GET /api/cards: List cards with pagination (7 cards per page)
 * - GET /api/cards/{id}: Retrieve detailed card information
 * - PUT /api/cards/{id}: Update card status and expiration date
 * 
 * Transforms CICS pseudo-conversational transaction processing to stateless
 * RESTful HTTP operations with Spring Security JWT authentication replacing
 * RACF security controls. Implements role-based authorization ensuring users
 * can only view/update their own cards while admin users can access all cards.
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

import com.carddemo.dto.request.CardUpdateRequest;
import com.carddemo.dto.response.CardResponse;
import com.carddemo.service.card.CardDetailService;
import com.carddemo.service.card.CardListService;
import com.carddemo.service.card.CardUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for credit card management operations.
 * 
 * <p>This controller implements RESTful endpoints for card management,
 * replacing three mainframe CICS COBOL programs with modern Spring Boot REST APIs:</p>
 * 
 * <h2>COBOL Program Transformations</h2>
 * <table border="1" cellpadding="5">
 *   <tr>
 *     <th>CICS Transaction</th>
 *     <th>COBOL Program</th>
 *     <th>REST Endpoint</th>
 *     <th>HTTP Method</th>
 *     <th>Operation</th>
 *   </tr>
 *   <tr>
 *     <td>CCLI</td>
 *     <td>COCRDLIC.cbl</td>
 *     <td>/api/cards</td>
 *     <td>GET</td>
 *     <td>List cards with pagination (7 per page)</td>
 *   </tr>
 *   <tr>
 *     <td>CCDL</td>
 *     <td>COCRDSLC.cbl</td>
 *     <td>/api/cards/{id}</td>
 *     <td>GET</td>
 *     <td>View card detail with transaction summary</td>
 *   </tr>
 *   <tr>
 *     <td>CCUP</td>
 *     <td>COCRDUPC.cbl</td>
 *     <td>/api/cards/{id}</td>
 *     <td>PUT</td>
 *     <td>Update card status and expiration date</td>
 *   </tr>
 * </table>
 * 
 * <h2>Security Model</h2>
 * <p>All endpoints require JWT authentication with role-based authorization:</p>
 * <ul>
 *   <li><b>Regular Users</b>: Can only view/update cards associated with their customer ID</li>
 *   <li><b>Admin Users</b>: Can view/update all cards without ownership restrictions</li>
 * </ul>
 * 
 * <h2>Data Transformation</h2>
 * <p>VSAM CARDDAT file operations are replaced with Spring Data JPA:</p>
 * <ul>
 *   <li>EXEC CICS STARTBR/READNEXT → cardRepository.findAll(Pageable)</li>
 *   <li>EXEC CICS READ → cardRepository.findByCardNumber()</li>
 *   <li>EXEC CICS REWRITE → cardRepository.save() with @Transactional</li>
 * </ul>
 * 
 * <h2>Pagination</h2>
 * <p>Card listing maintains original BMS screen pagination of 7 cards per page,
 * matching COBOL WS-MAX-SCREEN-LINES = 7 from COCRDLIC.cbl. PF7/PF8 scroll keys
 * are replaced with page number query parameters enabling React frontend navigation.</p>
 * 
 * <h2>PCI DSS Compliance</h2>
 * <p>Card numbers are masked in list views (showing only last 4 digits) for
 * security. Full card numbers are only displayed in detail views to authorized users.
 * CVV values are never exposed in API responses.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * 
 * @see CardListService Service implementing card list pagination
 * @see CardDetailService Service implementing card detail retrieval
 * @see CardUpdateService Service implementing card updates with validation
 * @see CardResponse DTO for card data with masked card numbers
 * @see CardUpdateRequest DTO for card update operations
 */
@Slf4j
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
@Tag(
    name = "Card Management",
    description = "Credit card operations including list with pagination (7 cards per page), " +
                  "detail view with transaction summary, and update operations for card status " +
                  "and expiration date. Replaces CICS transactions CCLI, CCDL, and CCUP with " +
                  "RESTful HTTP endpoints using JWT authentication and role-based authorization."
)
public class CardController {

    private final CardListService cardListService;
    private final CardDetailService cardDetailService;
    private final CardUpdateService cardUpdateService;

    /**
     * List Credit Cards with Pagination
     * 
     * <p>Retrieves a paginated list of credit cards with optional filtering by account ID.
     * This endpoint replaces CICS transaction CCLI (COCRDLIC.cbl) which displayed cards
     * using BMS mapset COCRDLI with 7 cards per screen and PF7/PF8 scroll keys for
     * navigation.</p>
     * 
     * <h3>COBOL Program Mapping</h3>
     * <p>Transforms COCRDLIC.cbl logic to Spring Boot REST endpoint:</p>
     * <ul>
     *   <li><b>VSAM STARTBR</b>: Replaced with Spring Data JPA findAll() with Pageable</li>
     *   <li><b>VSAM READNEXT</b>: Automatic pagination via Page object</li>
     *   <li><b>WS-MAX-SCREEN-LINES</b>: Fixed page size of 7 cards maintained</li>
     *   <li><b>COMMAREA pagination state</b>: Replaced with stateless HTTP page parameter</li>
     *   <li><b>EXEC CICS SEND MAP</b>: Replaced with JSON ResponseEntity</li>
     * </ul>
     * 
     * <h3>Pagination Parameters</h3>
     * <ul>
     *   <li><b>page</b>: Zero-based page number (default: 0)</li>
     *   <li><b>size</b>: Cards per page (fixed at 7, matching original BMS screen)</li>
     *   <li><b>sort</b>: Sort criteria (default: cardNumber,asc)</li>
     * </ul>
     * 
     * <h3>Filtering Options</h3>
     * <ul>
     *   <li><b>accountId</b>: Optional Long parameter to filter cards by account</li>
     *   <li>No accountId (admin only): Returns all cards across all accounts</li>
     *   <li>With accountId: Returns only cards for specified account</li>
     * </ul>
     * 
     * <h3>Security and Authorization</h3>
     * <p>Regular users can only view cards for accounts they own. The service layer
     * validates ownership by comparing the card's account's customer ID against the
     * authenticated user's customer ID extracted from JWT token. Admin users can view
     * all cards without ownership restrictions, enabling customer service operations.</p>
     * 
     * <h3>Response Structure</h3>
     * <p>Returns Spring Data Page object containing:</p>
     * <ul>
     *   <li><b>content</b>: Array of CardResponse objects with masked card numbers</li>
     *   <li><b>pageable</b>: Pagination information (page number, size)</li>
     *   <li><b>totalElements</b>: Total number of cards matching filters</li>
     *   <li><b>totalPages</b>: Total number of pages</li>
     *   <li><b>first</b>: Boolean indicating if this is the first page</li>
     *   <li><b>last</b>: Boolean indicating if this is the last page</li>
     * </ul>
     * 
     * <h3>Card Number Masking</h3>
     * <p>Card numbers are displayed with only the last 4 digits visible (e.g., "************1234")
     * for PCI DSS compliance. This matches the COBOL behavior where full card numbers are only
     * displayed in detail views with appropriate authorization.</p>
     * 
     * @param pageable Pagination parameters (page, size, sort) automatically resolved
     *                 from query parameters. Page size is enforced as 7 cards to match
     *                 original BMS screen display. Defaults to page=0, sort=cardNumber,asc
     * @param accountId Optional account ID filter. When provided, returns only cards
     *                  associated with the specified account. Maps to COBOL
     *                  CARD-ACCT-ID-N PIC 9(11) field filter from COCRDLIC.cbl
     * @return ResponseEntity containing Page of CardResponse objects with HTTP 200 OK.
     *         Each CardResponse contains masked card number, account ID, card type,
     *         expiration date, and active status
     */
    @Operation(
        summary = "List credit cards with pagination",
        description = "Retrieves paginated list of credit cards with optional filtering by account ID. " +
                      "Replaces CICS transaction CCLI (COCRDLIC.cbl) VSAM browse operations with Spring Data JPA queries. " +
                      "Default page size is 7 cards matching original BMS COCRDLI screen display. " +
                      "Card numbers are masked showing only last 4 digits for PCI DSS compliance. " +
                      "Regular users can only view cards for accounts they own; admin users can view all cards.",
        security = @SecurityRequirement(name = "bearer-jwt")
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Cards retrieved successfully. Returns paginated list of cards with metadata. " +
                          "Card numbers are masked showing only last 4 digits (e.g., '************1234'). " +
                          "Each card includes account ID, card type (VISA, MASTERCARD, AMEX), expiration date, and active status.",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = Page.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request parameters. Common errors include invalid account ID format " +
                          "(must be numeric Long), invalid pagination parameters (negative page number), " +
                          "or invalid sort field specification.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized. JWT token is missing, expired, or invalid. User must authenticate " +
                          "to access card data. Corresponds to RACF authentication failure in CICS environment.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden. User attempting to access cards for accounts they don't own. " +
                          "Regular users can only view cards for their own customer accounts; " +
                          "admin users can view all cards. Maps to CICS transaction security checks.",
            content = @Content(mediaType = "application/json")
        )
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<Page<CardResponse>> listCards(
            @PageableDefault(size = 7, sort = "cardNumber", direction = Sort.Direction.ASC)
            @Parameter(
                description = "Pagination and sorting parameters. Use 'page' for page number (0-based), " +
                              "'size' for cards per page (fixed at 7 to match BMS screen), and 'sort' for ordering. " +
                              "Page size is enforced at 7 cards matching COBOL WS-MAX-SCREEN-LINES = 7 constraint. " +
                              "Example: ?page=0&size=7&sort=cardNumber,asc",
                example = "page=0&size=7&sort=cardNumber,asc"
            )
            Pageable pageable,
            
            @RequestParam(required = false)
            @Parameter(
                description = "Optional filter by account ID. When provided, returns only cards associated " +
                              "with the specified account. Must be a valid numeric Long value (11 digits). " +
                              "Maps to COBOL CARD-ACCT-ID-N PIC 9(11) field from CVACT02Y.cpy copybook. " +
                              "When omitted, admin users see all cards; regular users see cards for their owned accounts only.",
                example = "00000000001"
            )
            Long accountId
    ) {
        log.info("Received card list request: page={}, size={}, accountId={}",
                pageable.getPageNumber(), pageable.getPageSize(), accountId);
        
        // Extract page number from Pageable and delegate to service
        // Service enforces fixed page size of 7 cards matching COBOL screen constraints
        // Maps COBOL CDEMO-CB00-PAGE-NUM from COMMAREA to stateless HTTP page parameter
        int page = pageable.getPageNumber();
        Page<CardResponse> cards = cardListService.listCards(page, accountId);
        
        log.info("Returning {} cards (page {} of {}), accountId filter: {}",
                cards.getNumberOfElements(),
                cards.getNumber() + 1,
                cards.getTotalPages(),
                accountId != null ? accountId : "none");
        
        return ResponseEntity.ok(cards);
    }

    /**
     * Get Card Detail by Card Number
     * 
     * <p>Retrieves detailed information for a specific credit card including account
     * information, transaction summary, and complete card attributes. This endpoint
     * replaces CICS transaction CCDL (COCRDSLC.cbl) which displayed card details
     * using BMS mapset COCRDSL.</p>
     * 
     * <h3>COBOL Program Mapping</h3>
     * <p>Transforms COCRDSLC.cbl logic to Spring Boot REST endpoint:</p>
     * <ul>
     *   <li><b>VSAM READ</b>: Replaced with cardRepository.findByCardNumber()</li>
     *   <li><b>CARD-RECORD from CVACT02Y.cpy</b>: Mapped to Card entity and CardResponse DTO</li>
     *   <li><b>Card number masking logic</b>: Applied for PCI DSS compliance</li>
     *   <li><b>Transaction summary aggregation</b>: Computed from related transactions</li>
     *   <li><b>EXEC CICS SEND MAP</b>: Replaced with JSON ResponseEntity</li>
     * </ul>
     * 
     * <h3>Response Data</h3>
     * <p>CardResponse contains:</p>
     * <ul>
     *   <li><b>cardNumber</b>: 16-digit card number (masked in list view, full in detail view for authorized users)</li>
     *   <li><b>accountId</b>: Associated account ID with balance information</li>
     *   <li><b>cardType</b>: Card type code (VISA, MASTERCARD, AMEX, DISCOVER)</li>
     *   <li><b>expirationDate</b>: Card expiration date in YYYY-MM format</li>
     *   <li><b>embossedName</b>: Name printed on card</li>
     *   <li><b>cvv</b>: Card verification value (masked as '***' for security)</li>
     *   <li><b>activeStatus</b>: Card status ('Y' active, 'N' inactive)</li>
     *   <li><b>issuedDate</b>: Date card was issued</li>
     *   <li><b>transactionSummary</b>: Last 10 transactions with total amount</li>
     * </ul>
     * 
     * <h3>Security and Authorization</h3>
     * <p>Regular users can only view details for cards associated with their customer accounts.
     * The service validates ownership by comparing the card's account's customer ID against
     * the authenticated user's customer ID from JWT token. Admin users can view all card
     * details without ownership restrictions. CVV display is restricted based on user role.</p>
     * 
     * <h3>Error Handling</h3>
     * <p>Returns HTTP 404 Not Found if card does not exist, matching COBOL RESP(NOTFND)
     * condition. Returns HTTP 403 Forbidden if user attempts to access a card they don't own.</p>
     * 
     * @param cardNumber 16-digit card number used as primary key. Maps to COBOL
     *                   CARD-NUM PIC X(16) field from CVACT02Y.cpy copybook.
     *                   Must be exactly 16 numeric digits
     * @return ResponseEntity containing CardResponse with complete card details and HTTP 200 OK,
     *         or HTTP 404 if card not found, or HTTP 403 if unauthorized access
     */
    @Operation(
        summary = "Get card detail by card number",
        description = "Retrieves detailed information for a specific credit card including account details, " +
                      "transaction summary, and complete card attributes. Replaces CICS transaction CCDL (COCRDSLC.cbl) " +
                      "VSAM READ operation with Spring Data JPA findByCardNumber query. " +
                      "Returns full card information including last 10 transactions and total transaction amount. " +
                      "Card number display and CVV visibility are controlled by user role for security.",
        security = @SecurityRequirement(name = "bearer-jwt")
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Card details retrieved successfully. Returns complete card information including " +
                          "card number (full for authorized users), account details, expiration date, embossed name, " +
                          "card type, active status, issued date, and transaction summary with last 10 transactions.",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CardResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid card number format. Card number must be exactly 16 numeric digits. " +
                          "Maps to COBOL PIC X(16) validation from CVACT02Y.cpy copybook.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized. JWT token is missing, expired, or invalid. User must authenticate " +
                          "to access card details. Corresponds to RACF authentication failure in CICS environment.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden. User attempting to access card details for a card they don't own. " +
                          "Regular users can only view cards associated with their customer accounts; " +
                          "admin users can view all cards. Maps to CICS transaction authorization checks.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Card not found. No card exists with the specified card number. " +
                          "Maps to COBOL EXEC CICS READ RESP(NOTFND) condition from COCRDSLC.cbl.",
            content = @Content(mediaType = "application/json")
        )
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<CardResponse> getCardById(
            @PathVariable("id")
            @Parameter(
                description = "16-digit card number serving as unique identifier. Maps to COBOL " +
                              "CARD-NUM PIC X(16) field from CVACT02Y.cpy copybook. Must be exactly " +
                              "16 numeric digits. Example: '4111111111111111' for Visa card.",
                example = "4111111111111111",
                required = true
            )
            String cardNumber
    ) {
        log.info("Received card detail request for card: {}", maskCardNumberForLog(cardNumber));
        
        // Validate card number format - must be exactly 16 digits
        // Maps to COBOL PIC X(16) validation from CVACT02Y.cpy copybook
        if (cardNumber == null || !cardNumber.matches("^[0-9]{16}$")) {
            log.warn("Invalid card number format received: {}", 
                    cardNumber != null ? maskCardNumberForLog(cardNumber) : "null");
            throw new com.carddemo.exception.ValidationException(
                    "Card number must be exactly 16 numeric digits");
        }
        
        // Delegate to service which performs authorization checks and retrieves full card details
        // Service implements ownership validation matching COBOL security checks in COCRDSLC.cbl
        // Returns CardResponse as specified in schema (service may return CardDetailResponse internally)
        CardResponse card = cardDetailService.getCardDetail(cardNumber);
        
        log.info("Returning card details for card: {}, accountId: {}",
                maskCardNumberForLog(cardNumber),
                card.getAccountId());
        
        return ResponseEntity.ok(card);
    }

    /**
     * Update Card Information
     * 
     * <p>Updates credit card information including expiration date and active status.
     * This endpoint replaces CICS transaction CCUP (COCRDUPC.cbl) which updated card
     * records using BMS mapset COCRDUP and VSAM REWRITE operations.</p>
     * 
     * <h3>COBOL Program Mapping</h3>
     * <p>Transforms COCRDUPC.cbl logic to Spring Boot REST endpoint:</p>
     * <ul>
     *   <li><b>VSAM READ for update</b>: Replaced with cardRepository.findByCardNumber() with pessimistic locking</li>
     *   <li><b>VSAM REWRITE</b>: Replaced with cardRepository.save() within @Transactional method</li>
     *   <li><b>SYNCPOINT</b>: Automatic commit at @Transactional method completion</li>
     *   <li><b>ROLLBACK</b>: Automatic rollback on validation failure or exception</li>
     *   <li><b>Optimistic locking</b>: Implemented with @Version annotation for concurrent update protection</li>
     *   <li><b>EXEC CICS SEND MAP</b>: Replaced with JSON ResponseEntity</li>
     * </ul>
     * 
     * <h3>Updateable Fields</h3>
     * <ul>
     *   <li><b>expirationDate</b>: Card expiration date (must be future date within 10 years)</li>
     *   <li><b>status</b>: Card active status ('Y' for active, 'N' for inactive)</li>
     * </ul>
     * 
     * <h3>Validation Rules</h3>
     * <p>Matching COBOL validation from COCRDUPC.cbl:</p>
     * <ul>
     *   <li>Card number must exist in database (404 if not found)</li>
     *   <li>Expiration date must be in the future</li>
     *   <li>Expiration date must be within 10 years from today</li>
     *   <li>Status must be 'Y' or 'N' (maps to COBOL 88-level FLG-CARD-ACTIVE/INACTIVE)</li>
     *   <li>Cannot reactivate expired cards</li>
     *   <li>CVV regeneration for lost/stolen cards (automatic)</li>
     * </ul>
     * 
     * <h3>Security and Authorization</h3>
     * <p>Regular users can only update cards associated with their customer accounts.
     * The service validates ownership by comparing the card's account's customer ID
     * against the authenticated user's customer ID from JWT token. Admin users can
     * update all cards without ownership restrictions.</p>
     * 
     * <h3>Transaction Management</h3>
     * <p>Update operation is atomic with automatic rollback on validation failure,
     * matching CICS SYNCPOINT/ROLLBACK behavior. Uses optimistic locking with @Version
     * to prevent lost updates in concurrent scenarios.</p>
     * 
     * <h3>Audit Logging</h3>
     * <p>All update operations are logged for audit trail, including user ID, timestamp,
     * old values, and new values, matching COBOL audit logging requirements.</p>
     * 
     * @param cardNumber 16-digit card number identifying card to update. Maps to COBOL
     *                   CARD-NUM PIC X(16) field from CVACT02Y.cpy copybook
     * @param request CardUpdateRequest DTO containing fields to update with validation
     *                annotations. Maps to COBOL BMS input fields from COCRDUP mapset
     * @return ResponseEntity containing updated CardResponse with HTTP 200 OK,
     *         or HTTP 404 if card not found, or HTTP 400 for validation errors,
     *         or HTTP 403 if unauthorized access
     */
    @Operation(
        summary = "Update card information",
        description = "Updates credit card expiration date and active status with comprehensive validation. " +
                      "Replaces CICS transaction CCUP (COCRDUPC.cbl) VSAM REWRITE operation with Spring Data JPA " +
                      "save within @Transactional method ensuring atomic updates with automatic rollback on failures. " +
                      "Implements optimistic locking using @Version annotation to prevent lost updates. " +
                      "Validates expiration date is future date within 10 years and prevents reactivation of expired cards. " +
                      "Regular users can only update their own cards; admin users can update all cards.",
        security = @SecurityRequirement(name = "bearer-jwt")
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Card updated successfully. Returns complete updated card information including " +
                          "new expiration date and status. Update is committed atomically matching CICS SYNCPOINT behavior.",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CardResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request data. Common validation errors include: " +
                          "expiration date in the past, expiration date more than 10 years in future, " +
                          "invalid status value (must be 'Y' or 'N'), attempt to reactivate expired card, " +
                          "missing required fields, or invalid card number format (must be 16 digits). " +
                          "Maps to COBOL validation flags WS-EDIT-CARD-FLAG and WS-INPUT-FLAG.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized. JWT token is missing, expired, or invalid. User must authenticate " +
                          "to update card data. Corresponds to RACF authentication failure in CICS environment.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden. User attempting to update card they don't own. " +
                          "Regular users can only update cards associated with their customer accounts; " +
                          "admin users can update all cards. Maps to CICS transaction authorization checks.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Card not found. No card exists with the specified card number. " +
                          "Maps to COBOL EXEC CICS READ RESP(NOTFND) condition from COCRDUPC.cbl.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "409",
            description = "Conflict. Concurrent update detected via optimistic locking version mismatch. " +
                          "Client should retry the update with latest card data. Maps to COBOL file update " +
                          "conflict handling with automatic retry logic.",
            content = @Content(mediaType = "application/json")
        )
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<CardResponse> updateCard(
            @PathVariable("id")
            @Parameter(
                description = "16-digit card number identifying card to update. Maps to COBOL " +
                              "CARD-NUM PIC X(16) field from CVACT02Y.cpy copybook. Must be exactly " +
                              "16 numeric digits. Example: '4111111111111111' for Visa card.",
                example = "4111111111111111",
                required = true
            )
            String cardNumber,
            
            @Valid
            @RequestBody
            @Parameter(
                description = "Card update request containing fields to update. Includes expiration date " +
                              "(LocalDate in YYYY-MM-DD format, must be future date within 10 years) and " +
                              "status (String 'Y' for active or 'N' for inactive matching COBOL 88-level conditions). " +
                              "Maps to BMS input fields from COCRDUP mapset. Bean Validation annotations ensure " +
                              "automatic validation before method execution.",
                required = true
            )
            CardUpdateRequest request
    ) {
        log.info("Received card update request for card: {}, expirationDate: {}, status: {}",
                maskCardNumberForLog(cardNumber),
                request.getExpirationDate(),
                request.getStatus());
        
        // Delegate to service which performs validation, authorization checks, and atomic update
        // Service implements @Transactional ensuring automatic rollback on failures matching CICS behavior
        // Returns updated CardResponse after successful commit
        CardResponse updatedCard = cardUpdateService.updateCard(cardNumber, request);
        
        log.info("Successfully updated card: {}, new status: {}, new expiration: {}",
                maskCardNumberForLog(cardNumber),
                updatedCard.getActiveStatus(),
                updatedCard.getExpirationDate());
        
        return ResponseEntity.ok(updatedCard);
    }

    /**
     * Mask Card Number for Logging
     * 
     * <p>Masks card number for security in log entries, showing only last 4 digits.
     * Prevents full card numbers from appearing in application logs, matching
     * PCI DSS requirement 3.3 to mask PAN when displayed.</p>
     * 
     * @param cardNumber Full 16-digit card number
     * @return Masked card number showing only last 4 digits (e.g., "************1234")
     */
    private String maskCardNumberForLog(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "************" + cardNumber.substring(cardNumber.length() - 4);
    }
}
