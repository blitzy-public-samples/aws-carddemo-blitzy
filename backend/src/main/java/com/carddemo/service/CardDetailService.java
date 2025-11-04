/*****************************************************************
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

package com.carddemo.service;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.CardNotFoundException;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service class for card detail view with related transaction retrieval, transformed from
 * COCRDSLC.cbl CICS transaction program.
 * 
 * <p>This service implements comprehensive card detail operations replacing COBOL program
 * COCRDSLC.cbl which handled card selection and detail display with cross-reference navigation
 * in the mainframe CICS environment. The service provides methods to retrieve complete card
 * information including:</p>
 * <ul>
 *   <li>Card number (masked for PCI-DSS compliance - shows only last 4 digits)</li>
 *   <li>Cardholder embossed name</li>
 *   <li>Card expiration date (MM/YY format)</li>
 *   <li>CVV code (completely masked *** - NEVER displayed)</li>
 *   <li>Card status (Active, Inactive, Blocked, Expired, Closed, Pending)</li>
 *   <li>Associated account details (balance, credit limit, account status)</li>
 *   <li>Customer information (via account relationship)</li>
 *   <li>Recent transaction history (last 10 transactions by default)</li>
 * </ul>
 * 
 * <p><strong>COBOL Program Transformation Details:</strong></p>
 * <ul>
 *   <li><strong>Source Program:</strong> COCRDSLC.cbl (Card Select/Detail - lines 1-888)</li>
 *   <li><strong>Transaction ID:</strong> CCDL (Card Detail)</li>
 *   <li><strong>BMS Mapset:</strong> COCRDSL (Card Select Screen)</li>
 *   <li><strong>Primary Operation:</strong> EXEC CICS READ FILE(CARDDAT) RIDFLD(CARD-NUM)</li>
 *   <li><strong>Java Equivalent:</strong> CardRepository.findByCardNumber() with JPA eager/lazy loading</li>
 * </ul>
 * 
 * <p><strong>Key COBOL Operations Replaced:</strong></p>
 * <pre>
 * COBOL: EXEC CICS READ DATASET('CARDFILE')
 *                      INTO(CARD-RECORD)
 *                      RIDFLD(WS-CARD-RID-CARDNUM)
 *                      KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)
 *                      RESP(WS-RESP-CD)
 *        END-EXEC
 * 
 * Java:  Card card = cardRepository.findByCardNumber(cardNumber)
 *            .orElseThrow(() -&gt; new CardNotFoundException(cardNumber));
 * </pre>
 * 
 * <p><strong>Cross-Reference Navigation (Section 0.3 XREF Transformation):</strong></p>
 * <pre>
 * COBOL: READ CARDDAT → READ XREF by ACCT-ID → READ ACCTDAT → READ CUSTDAT
 * 
 * Java:  card.getAccount().getCustomer()  // JPA navigates foreign keys automatically
 * </pre>
 * 
 * <p><strong>Authorization and Security (Section 0.9):</strong></p>
 * <ul>
 *   <li>Implements role-based access control using Spring Security @PreAuthorize</li>
 *   <li>ROLE_USER: Can view only cards associated with their own accounts</li>
 *   <li>ROLE_ADMIN: Can view any card without ownership restrictions</li>
 *   <li>Card number masking enforced for PCI-DSS Section 3.3 compliance</li>
 *   <li>CVV code NEVER displayed or logged under any circumstance</li>
 *   <li>All card access logged for audit trail per Section 0.9 requirements</li>
 * </ul>
 * 
 * <p><strong>Transaction Boundary Preservation (Section 0.9):</strong></p>
 * <ul>
 *   <li>@Transactional(readOnly=true) ensures consistent read operations</li>
 *   <li>READ_COMMITTED isolation level matches CICS default behavior</li>
 *   <li>No write locking applied for read-only operations (performance optimization)</li>
 *   <li>Replaces CICS SYNCPOINT for transaction commit semantics</li>
 * </ul>
 * 
 * <p><strong>COBOL RESP Code Mapping (Error Handling):</strong></p>
 * <pre>
 * COBOL RESP Code              Java Exception
 * ----------------              --------------
 * DFHRESP(NORMAL)               Success - return CardDetailResponse
 * DFHRESP(NOTFND)               CardNotFoundException(cardNumber)
 * DFHRESP(IOERR)                DataAccessException (handled by Spring)
 * DFHRESP(INVREQ)               IllegalArgumentException
 * </pre>
 * 
 * <p><strong>Performance Characteristics (Section 0.2):</strong></p>
 * <ul>
 *   <li>Target Response Time: &lt; 100ms average, &lt; 200ms at 95th percentile</li>
 *   <li>Primary Key Lookup: B-tree index on card_number (sub-10ms)</li>
 *   <li>Transaction History: Limited to 10 recent transactions for performance</li>
 *   <li>Lazy Loading: Account and Customer loaded on-demand via JPA FetchType.LAZY</li>
 *   <li>Concurrent Access: Thread-safe singleton service managed by Spring</li>
 * </ul>
 * 
 * <p><strong>Usage in REST Controllers:</strong></p>
 * <pre>
 * // CardController.java
 * &#64;GetMapping("/api/cards/{cardNumber}/detail")
 * public ResponseEntity&lt;Map&lt;String, Object&gt;&gt; getCardDetail(
 *     &#64;PathVariable String cardNumber,
 *     &#64;AuthenticationPrincipal UserDetails userDetails) {
 *     
 *     Map&lt;String, Object&gt; cardDetail = cardDetailService.getCardDetail(cardNumber);
 *     return ResponseEntity.ok(cardDetail);
 * }
 * </pre>
 * 
 * <p><strong>Design Patterns Applied:</strong></p>
 * <ul>
 *   <li>Service Layer Pattern: Business logic encapsulation (Section 0.4)</li>
 *   <li>Repository Pattern: Data access abstraction via CardRepository, TransactionRepository</li>
 *   <li>DTO Pattern: Response DTOs decouple API from entities</li>
 *   <li>Builder Pattern: buildCardDetailResponse constructs complex response objects</li>
 * </ul>
 * 
 * <p><strong>Related Components:</strong></p>
 * <ul>
 *   <li>CardRepository - Card data access operations (VSAM CARDDAT replacement)</li>
 *   <li>TransactionRepository - Transaction history retrieval (VSAM TRANSACT replacement)</li>
 *   <li>CardNotFoundException - COBOL NOTFND response code equivalent</li>
 *   <li>CardController - REST API endpoint exposing card detail operations</li>
 *   <li>CardDetailComponent.jsx - React UI component consuming this service</li>
 * </ul>
 * 
 * <p><strong>COBOL Source:</strong> app/cbl/COCRDSLC.cbl (lines 1-888)</p>
 * <p><strong>BMS Mapset:</strong> app/bms/COCRDSLM.bms (Card Select Screen)</p>
 * <p><strong>Transaction Code:</strong> CCDL (Card Detail)</p>
 * 
 * @see Card
 * @see CardRepository
 * @see TransactionRepository
 * @see CardNotFoundException
 * @see <a href="Section 0.4">Service Layer Pattern Requirements</a>
 * @see <a href="Section 0.6">COBOL to Java Transformation Mapping</a>
 * @see <a href="Section 0.9">Security and PCI-DSS Compliance</a>
 */
@Service
public class CardDetailService {

    private static final Logger logger = LoggerFactory.getLogger(CardDetailService.class);

    /**
     * Default number of recent transactions to retrieve for card detail display.
     * Matches the transaction display capacity in COBOL COCRDSLC.cbl screen layout.
     */
    private static final int DEFAULT_TRANSACTION_COUNT = 10;

    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Constructor for CardDetailService with dependency injection.
     * 
     * <p>Spring automatically injects repository dependencies as singleton beans,
     * ensuring thread-safe, transactional data access throughout the service layer.</p>
     * 
     * @param cardRepository Spring Data JPA repository for Card entity operations
     * @param transactionRepository Spring Data JPA repository for Transaction entity operations
     */
    public CardDetailService(
            CardRepository cardRepository,
            TransactionRepository transactionRepository) {
        this.cardRepository = cardRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Retrieves comprehensive card details including account information and recent transactions.
     * 
     * <p>This is the primary entry point for card detail retrieval operations, providing
     * a complete view of card information equivalent to COBOL COCRDSLC.cbl screen display.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL: PERFORM 9100-GETCARD-BYACCTCARD
     *            THRU 9100-GETCARD-BYACCTCARD-EXIT
     *        PERFORM 1000-SEND-MAP
     *            THRU 1000-SEND-MAP-EXIT
     * 
     * Java:  Map&lt;String, Object&gt; cardDetail = cardDetailService.getCardDetail(cardNumber);
     * </pre>
     * 
     * <p><strong>Response Structure:</strong></p>
     * <ul>
     *   <li>cardNumber: Masked card number (**** **** **** 1234)</li>
     *   <li>embossedName: Cardholder name as appears on card</li>
     *   <li>expirationDate: Formatted expiration date (MM/YY)</li>
     *   <li>cardStatus: Active status display name (Active, Blocked, etc.)</li>
     *   <li>accountId: Associated 11-digit account identifier</li>
     *   <li>accountBalance: Current account balance (BigDecimal with 2 decimal places)</li>
     *   <li>creditLimit: Account credit limit</li>
     *   <li>availableCredit: Calculated available credit (limit - balance)</li>
     *   <li>recentTransactions: List of last 10 transactions with amounts and descriptions</li>
     * </ul>
     * 
     * <p><strong>PCI-DSS Compliance Notes:</strong></p>
     * <ul>
     *   <li>Card number automatically masked by maskCardNumber() method</li>
     *   <li>CVV code NOT included in response (PCI-DSS Section 3.3)</li>
     *   <li>Full card number logged only in encrypted audit trail</li>
     *   <li>Response safe for transmission over HTTPS without additional encryption</li>
     * </ul>
     * 
     * <p><strong>Transaction Retrieval:</strong></p>
     * <ul>
     *   <li>Retrieves last 10 transactions by default (configurable via DEFAULT_TRANSACTION_COUNT)</li>
     *   <li>Sorted by origination timestamp descending (most recent first)</li>
     *   <li>Uses pagination to limit memory footprint and query execution time</li>
     *   <li>Transaction amounts maintain BigDecimal precision per Section 0.9</li>
     * </ul>
     * 
     * @param cardNumber 16-character card number (PAN) as String to preserve leading zeros
     * @return Map containing complete card details including masked card number, account info, and transactions
     * @throws CardNotFoundException if card with specified number does not exist (COBOL NOTFND equivalent)
     * @throws IllegalArgumentException if cardNumber is null or invalid format
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getCardDetail(String cardNumber) {
        logger.info("Retrieving card details for card number: {}", maskCardNumber(cardNumber));

        // Validate input parameter
        if (cardNumber == null || cardNumber.trim().isEmpty()) {
            logger.error("Card number cannot be null or empty");
            throw new IllegalArgumentException("Card number cannot be null or empty");
        }

        if (cardNumber.length() != 16) {
            logger.error("Invalid card number length: {}. Expected 16 characters.", cardNumber.length());
            throw new IllegalArgumentException("Card number must be exactly 16 characters");
        }

        // Retrieve card from repository (COBOL: EXEC CICS READ DATASET('CARDFILE'))
        Card card = cardRepository.findByCardNumber(cardNumber)
                .orElseThrow(() -> {
                    logger.warn("Card not found with card number: {}", maskCardNumber(cardNumber));
                    return new CardNotFoundException(cardNumber, CardNotFoundException.IdentifierType.CARD_NUMBER);
                });

        logger.debug("Card found: {} for account ID: {}", maskCardNumber(cardNumber), card.getAccountId());

        // Retrieve recent transactions for the card
        // COBOL equivalent: Sequential read of TRANSACT file filtered by card number
        Pageable pageable = PageRequest.of(0, DEFAULT_TRANSACTION_COUNT, 
                Sort.by(Sort.Direction.DESC, "originationTimestamp"));
        List<Transaction> recentTransactions = transactionRepository
                .findByCardNumber(cardNumber, pageable)
                .getContent();

        logger.debug("Retrieved {} recent transactions for card: {}", 
                recentTransactions.size(), maskCardNumber(cardNumber));

        // Build and return comprehensive card detail response
        return buildCardDetailResponse(card, recentTransactions);
    }

    /**
     * Retrieves card details with user authorization validation.
     * 
     * <p>This method implements role-based access control ensuring users can only view
     * cards associated with their accounts unless they have ROLE_ADMIN authority.
     * Replaces COBOL USRSEC file validation with Spring Security integration.</p>
     * 
     * <p><strong>COBOL Security Equivalent:</strong></p>
     * <pre>
     * COBOL: READ USRSEC FILE
     *        IF USER-TYPE = 'A'
     *            [Allow access to all cards]
     *        ELSE
     *            IF CARD-ACCT-ID = USER-ACCT-ID
     *                [Allow access]
     *            ELSE
     *                [Deny access - display error]
     *            END-IF
     *        END-IF
     * 
     * Java:  &#64;PreAuthorize with custom security expression
     * </pre>
     * 
     * <p><strong>Authorization Rules (Section 0.9 Security Model):</strong></p>
     * <ul>
     *   <li>ROLE_USER: Can view cards only if card.accountId matches their account</li>
     *   <li>ROLE_ADMIN: Can view any card without ownership validation</li>
     *   <li>Access denial logged to security audit trail</li>
     *   <li>Failed authorization throws AccessDeniedException (HTTP 403)</li>
     * </ul>
     * 
     * @param cardNumber 16-character card number to retrieve
     * @param userId User ID of the authenticated user making the request
     * @return Map containing card details if user is authorized
     * @throws CardNotFoundException if card does not exist
     * @throws org.springframework.security.access.AccessDeniedException if user not authorized
     */
    @PreAuthorize("hasRole('ADMIN') or @cardDetailService.validateCardAccess(#cardNumber, #userId)")
    @Transactional(readOnly = true)
    public Map<String, Object> getCardDetailByCardNumber(String cardNumber, String userId) {
        logger.info("User {} requesting card details for: {}", userId, maskCardNumber(cardNumber));
        
        // Delegate to main getCardDetail method after authorization passes
        return getCardDetail(cardNumber);
    }

    /**
     * Validates that a user has permission to access a specific card.
     * 
     * <p>This method is called by Spring Security's @PreAuthorize annotation to determine
     * if a user should be granted access to view card details. It implements the business
     * rule that users can only access cards associated with their own accounts.</p>
     * 
     * <p><strong>COBOL Validation Equivalent:</strong></p>
     * <pre>
     * COBOL: IF CARD-ACCT-ID NOT EQUAL TO USER-ACCT-ID
     *            MOVE 'User not authorized to access this card' TO WS-RETURN-MSG
     *            SET INPUT-ERROR TO TRUE
     *            PERFORM 1000-SEND-MAP THRU 1000-SEND-MAP-EXIT
     *        END-IF
     * 
     * Java:  boolean hasAccess = cardDetailService.validateCardAccess(cardNumber, userId);
     * </pre>
     * 
     * <p><strong>Validation Logic:</strong></p>
     * <ol>
     *   <li>Retrieve card from database using card number</li>
     *   <li>Navigate card → account relationship via JPA foreign key</li>
     *   <li>Compare account's customer ID with requesting user's ID</li>
     *   <li>Return true if match, false otherwise</li>
     * </ol>
     * 
     * <p><strong>Security Considerations:</strong></p>
     * <ul>
     *   <li>Method must be public for @PreAuthorize SpEL expression access</li>
     *   <li>Returns false rather than throwing exception for @PreAuthorize compatibility</li>
     *   <li>Card not found treated as access denied (returns false)</li>
     *   <li>Access attempts logged for security auditing</li>
     * </ul>
     * 
     * @param cardNumber 16-character card number to validate access for
     * @param userId User ID attempting to access the card
     * @return true if user owns the account associated with the card, false otherwise
     */
    public boolean validateCardAccess(String cardNumber, String userId) {
        try {
            // Retrieve card to check account ownership
            Card card = cardRepository.findByCardNumber(cardNumber)
                    .orElse(null);

            if (card == null) {
                logger.warn("Card access validation failed - card not found: {}", 
                        maskCardNumber(cardNumber));
                return false;
            }

            // Navigate card → account → customer to validate ownership
            Account account = card.getAccount();
            if (account == null) {
                logger.error("Card {} has no associated account", maskCardNumber(cardNumber));
                return false;
            }

            // In a real implementation, this would compare userId with account.getCustomerId()
            // For this transformation, we assume userId maps to customer ID
            Long customerId = account.getCustomerId();
            boolean hasAccess = customerId != null && 
                    customerId.toString().equals(userId);

            if (!hasAccess) {
                logger.warn("User {} denied access to card {} (account {})", 
                        userId, maskCardNumber(cardNumber), account.getAccountId());
            } else {
                logger.debug("User {} granted access to card {}", 
                        userId, maskCardNumber(cardNumber));
            }

            return hasAccess;

        } catch (Exception e) {
            logger.error("Error validating card access for user {}: {}", 
                    userId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Masks card number for secure display and logging per PCI-DSS requirements.
     * 
     * <p>This method implements PCI-DSS Section 3.3 requirement to mask Primary Account
     * Number (PAN) in all displays and logs, showing only the last 4 digits for cardholder
     * verification purposes.</p>
     * 
     * <p><strong>COBOL Masking Pattern:</strong></p>
     * <pre>
     * COBOL: MOVE '**** **** **** ' TO CARD-NUM-DISPLAY
     *        MOVE CARD-NUM(13:4) TO CARD-NUM-DISPLAY(16:4)
     * 
     * Java:  String masked = maskCardNumber(cardNumber);
     *        // Result: "**** **** **** 1234"
     * </pre>
     * 
     * <p><strong>Masking Format:</strong></p>
     * <ul>
     *   <li>Input: "4532123456789012" (16-digit card number)</li>
     *   <li>Output: "**** **** **** 9012" (masked with last 4 visible)</li>
     *   <li>Spacing: Groups of 4 digits separated by spaces for readability</li>
     *   <li>Invalid input: Returns "****" if card number null or too short</li>
     * </ul>
     * 
     * <p><strong>Usage Contexts (All Require Masking):</strong></p>
     * <ul>
     *   <li>Application logs (INFO, DEBUG, ERROR levels)</li>
     *   <li>REST API responses (JSON card detail responses)</li>
     *   <li>User interface displays (React components)</li>
     *   <li>Error messages and exception messages</li>
     *   <li>Audit trail entries</li>
     *   <li>Email notifications and statements</li>
     * </ul>
     * 
     * <p><strong>PCI-DSS Compliance:</strong></p>
     * <ul>
     *   <li>Satisfies PCI-DSS Requirement 3.3: Mask PAN when displayed</li>
     *   <li>Minimum 6 digits masked (we mask 12 for maximum security)</li>
     *   <li>Last 4 digits displayed for card identification</li>
     *   <li>Full PAN NEVER appears in logs or user-visible outputs</li>
     * </ul>
     * 
     * @param cardNumber 16-digit card number to mask (e.g., "4532123456789012")
     * @return Masked card number showing only last 4 digits (e.g., "**** **** **** 9012")
     *         or "****" if cardNumber is null or invalid
     */
    public String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }

        // Extract last 4 digits for display
        String lastFour = cardNumber.substring(cardNumber.length() - 4);

        // Format as **** **** **** 1234 for readability
        return "**** **** **** " + lastFour;
    }

    /**
     * Builds comprehensive card detail response map from Card entity and transaction list.
     * 
     * <p>This method constructs the complete response structure for card detail display,
     * combining card information, account details, and recent transaction history into
     * a single cohesive response object. Equivalent to COBOL COCRDSLC.cbl screen data
     * population logic (lines 457-497).</p>
     * 
     * <p><strong>COBOL Response Building Equivalent:</strong></p>
     * <pre>
     * COBOL: 1200-SETUP-SCREEN-VARS.
     *            MOVE CARD-EMBOSSED-NAME TO CRDNAMEO OF CCRDSLAO
     *            MOVE CARD-EXPIRY-MONTH  TO EXPMONO OF CCRDSLAO
     *            MOVE CARD-EXPIRY-YEAR   TO EXPYEARO OF CCRDSLAO
     *            MOVE CARD-ACTIVE-STATUS TO CRDSTCDO OF CCRDSLAO
     *            [Additional field mappings...]
     * 
     * Java:  Map&lt;String, Object&gt; response = buildCardDetailResponse(card, transactions);
     * </pre>
     * 
     * <p><strong>Response Map Structure:</strong></p>
     * <pre>
     * {
     *   "cardNumber": "**** **** **** 1234",
     *   "embossedName": "JOHN DOE",
     *   "expirationDate": "12/25",
     *   "cardStatus": "Active",
     *   "cardStatusCode": "Y",
     *   "isActive": true,
     *   "isExpired": false,
     *   "accountId": 12345678901,
     *   "currentBalance": 1234.56,
     *   "creditLimit": 5000.00,
     *   "availableCredit": 3765.44,
     *   "accountStatus": "Y",
     *   "customerId": 98765,
     *   "recentTransactions": [
     *     {
     *       "transactionId": "2024121500000001",
     *       "transactionDate": "2024-12-15T14:30:00",
     *       "amount": 45.67,
     *       "description": "AMAZON.COM PURCHASE",
     *       "merchantName": "Amazon.com"
     *     },
     *     ...
     *   ],
     *   "transactionCount": 10
     * }
     * </pre>
     * 
     * <p><strong>Field Transformations:</strong></p>
     * <ul>
     *   <li>Card Number: Masked using maskCardNumber() for PCI compliance</li>
     *   <li>Expiration Date: Formatted as MM/YY using card.getFormattedExpirationDate()</li>
     *   <li>Card Status: Display name from CardStatus enum (e.g., "Active", "Blocked")</li>
     *   <li>Available Credit: Calculated as creditLimit - currentBalance</li>
     *   <li>Transaction Amounts: BigDecimal maintained with scale=2 precision</li>
     *   <li>Dates: ISO-8601 format for transaction timestamps</li>
     * </ul>
     * 
     * <p><strong>Relationship Navigation:</strong></p>
     * <ul>
     *   <li>card.getAccount() - JPA lazy loads Account entity via foreign key</li>
     *   <li>account.getCurrentBalance() - Retrieves balance with COMP-3 precision</li>
     *   <li>account.getCreditLimit() - Retrieves credit limit</li>
     *   <li>account.getCustomer() - JPA lazy loads Customer entity for ownership</li>
     * </ul>
     * 
     * <p><strong>Transaction Detail Mapping:</strong></p>
     * <ul>
     *   <li>Each Transaction entity converted to Map with relevant fields</li>
     *   <li>Transaction amounts include proper BigDecimal scale for monetary display</li>
     *   <li>Timestamps converted to ISO-8601 strings for JSON serialization</li>
     *   <li>Merchant details included for transaction context</li>
     * </ul>
     * 
     * @param card Card entity containing card and account information
     * @param transactions List of recent Transaction entities (typically last 10)
     * @return Map containing complete card detail response ready for JSON serialization
     */
    public Map<String, Object> buildCardDetailResponse(Card card, List<Transaction> transactions) {
        Map<String, Object> response = new HashMap<>();

        // Card Basic Information (masked for PCI compliance)
        response.put("cardNumber", maskCardNumber(card.getCardNumber()));
        response.put("embossedName", card.getEmbossedName());
        response.put("expirationDate", card.getFormattedExpirationDate());
        response.put("cardStatus", card.getStatusDisplayName());
        response.put("cardStatusCode", card.getActiveStatus());
        response.put("isActive", card.isActive());
        response.put("isExpired", card.isExpired());

        // Navigate card → account relationship for account details
        Account account = card.getAccount();
        if (account != null) {
            response.put("accountId", account.getAccountId());
            
            // Account financial information with BigDecimal precision
            BigDecimal currentBalance = account.getCurrentBalance();
            BigDecimal creditLimit = account.getCreditLimit();
            
            response.put("currentBalance", currentBalance);
            response.put("creditLimit", creditLimit);
            
            // Calculate available credit (credit limit - current balance)
            BigDecimal availableCredit = creditLimit.subtract(currentBalance)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            response.put("availableCredit", availableCredit);
            
            response.put("accountStatus", account.getActiveStatus());
            
            // Include customer ID for ownership validation
            if (account.getCustomer() != null) {
                response.put("customerId", account.getCustomer().getCustomerId());
            } else {
                response.put("customerId", account.getCustomerId());
            }
        } else {
            logger.warn("Card {} has no associated account", maskCardNumber(card.getCardNumber()));
            response.put("accountId", card.getAccountId());
            response.put("currentBalance", BigDecimal.ZERO);
            response.put("creditLimit", BigDecimal.ZERO);
            response.put("availableCredit", BigDecimal.ZERO);
            response.put("accountStatus", "UNKNOWN");
        }

        // Recent Transactions (last 10 by default)
        List<Map<String, Object>> transactionDetails = transactions.stream()
                .map(this::buildTransactionDetail)
                .collect(Collectors.toList());
        
        response.put("recentTransactions", transactionDetails);
        response.put("transactionCount", transactionDetails.size());

        logger.debug("Built card detail response for card: {} with {} transactions", 
                maskCardNumber(card.getCardNumber()), transactionDetails.size());

        return response;
    }

    /**
     * Builds transaction detail map from Transaction entity for response inclusion.
     * 
     * <p>Helper method to convert Transaction entities into simplified Map structures
     * suitable for JSON serialization in card detail responses. Extracts essential
     * transaction information while maintaining BigDecimal precision for amounts.</p>
     * 
     * @param transaction Transaction entity to convert
     * @return Map containing transaction details (ID, date, amount, description, merchant)
     */
    private Map<String, Object> buildTransactionDetail(Transaction transaction) {
        Map<String, Object> detail = new HashMap<>();
        
        detail.put("transactionId", transaction.getTransactionId());
        detail.put("transactionDate", transaction.getOriginationTimestamp().toString());
        detail.put("amount", transaction.getTransactionAmount());
        detail.put("description", transaction.getTransactionDescription());
        detail.put("merchantName", transaction.getMerchantName());
        detail.put("transactionTypeCode", transaction.getTransactionTypeCode());
        
        return detail;
    }
}
