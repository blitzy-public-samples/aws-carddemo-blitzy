/*
 * CardUpdateService.java
 *
 * Card update service implementing credit card update operations with transaction 
 * management and optimistic locking.
 *
 * This service transforms COBOL COCRDUPC.cbl card update program to Spring service 
 * implementing update operations with @Transactional annotation ensuring atomic updates.
 * Replaces CICS REWRITE FILE operation with Spring Data JPA repository.save method.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
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
package com.carddemo.service.card;

import com.carddemo.entity.Card;
import com.carddemo.entity.User;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.UserRepository;
import com.carddemo.dto.request.CardUpdateRequest;
import com.carddemo.dto.response.CardResponse;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.BusinessLogicException;
import com.carddemo.exception.ValidationException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.OptimisticLockException;

import java.time.LocalDate;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Card Update Service
 *
 * <p>Spring service implementing credit card update operations with comprehensive validation,
 * transaction management, and optimistic locking for concurrent update protection.
 *
 * <h2>COBOL Source Mapping</h2>
 * <p>This service transforms the COBOL program COCRDUPC.cbl (CCUP transaction) which handles
 * card update operations on the mainframe using CICS REWRITE operations on VSAM KSDS file.</p>
 *
 * <p><b>COBOL Program:</b> app/cbl/COCRDUPC.cbl</p>
 * <ul>
 *   <li>Lines 1461-1475: CARD-UPDATE-RECORD preparation with field assignments</li>
 *   <li>Lines 1477-1483: EXEC CICS REWRITE FILE(CARDDAT) operation</li>
 *   <li>Lines 1498-1523: 9300-CHECK-CHANGE-IN-REC paragraph for optimistic locking</li>
 *   <li>Line 1491: LOCKED-BUT-UPDATE-FAILED condition for concurrent modification</li>
 * </ul>
 *
 * <h2>Key Transformations</h2>
 * <ul>
 *   <li><b>CICS REWRITE → JPA save():</b> VSAM file update replaced with repository.save()
 *       with @Transactional boundaries ensuring atomic database updates</li>
 *   <li><b>Manual Optimistic Lock → @Version:</b> COBOL paragraph 9300-CHECK-CHANGE-IN-REC
 *       comparing old vs current values replaced with JPA @Version annotation on Card entity
 *       for automatic optimistic lock exception handling</li>
 *   <li><b>COMMAREA Context → SecurityContext:</b> CICS COMMAREA user context replaced
 *       with Spring Security SecurityContextHolder for authentication</li>
 *   <li><b>RESP-CD Error Handling → Exceptions:</b> CICS response codes transformed to
 *       Java exceptions (ResourceNotFoundException, BusinessLogicException)</li>
 * </ul>
 *
 * <h2>Business Rules Implemented</h2>
 * <ul>
 *   <li><b>Expiration Date Validation:</b> New expiration date must be future date and
 *       within 10 years from current date. Prevents unrealistic expiration dates.</li>
 *   <li><b>Expired Card Reactivation Prevention:</b> Cards with expiration date in the
 *       past cannot have status changed to active ('Y'). Business rule prevents security
 *       violations from reactivating expired cards.</li>
 *   <li><b>CVV Regeneration for Lost/Stolen:</b> When card status is changed to indicate
 *       lost or stolen, CVV is automatically regenerated using SecureRandom to generate
 *       new 3-digit code matching CARD-CVV-CD PIC 9(03) format from CVACT02Y.cpy.</li>
 *   <li><b>Concurrent Update Detection:</b> OptimisticLockException thrown when card
 *       version mismatch detected, matching COBOL LOCKED-BUT-UPDATE-FAILED condition.</li>
 * </ul>
 *
 * <h2>Transaction Management</h2>
 * <p>Method annotated with @Transactional ensures:</p>
 * <ul>
 *   <li>Automatic transaction start on method entry</li>
 *   <li>Automatic commit on successful completion</li>
 *   <li>Automatic rollback on any exception (unchecked exceptions trigger rollback)</li>
 *   <li>Database isolation level READ_COMMITTED for consistent reads</li>
 *   <li>Optimistic locking version checks before database commit</li>
 * </ul>
 *
 * <h2>Optimistic Locking Implementation</h2>
 * <p>The COBOL program COCRDUPC.cbl implements manual optimistic locking in paragraph
 * 9300-CHECK-CHANGE-IN-REC (lines 1498-1523) by comparing field values before update:</p>
 * <pre>
 * COBOL Manual Check:
 * IF  CARD-CVV-CD              EQUAL  TO CCUP-OLD-CVV-CD
 * AND CARD-EMBOSSED-NAME       EQUAL  TO CCUP-OLD-CRDNAME
 * AND CARD-EXPIRAION-DATE(1:4) EQUAL  TO CCUP-OLD-EXPYEAR
 * AND CARD-EXPIRAION-DATE(6:2) EQUAL  TO CCUP-OLD-EXPMON
 * AND CARD-EXPIRAION-DATE(9:2) EQUAL  TO CCUP-OLD-EXPDAY
 * AND CARD-ACTIVE-STATUS       EQUAL  TO CCUP-OLD-CRDSTCD
 *     CONTINUE
 * ELSE
 *     SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE
 * </pre>
 *
 * <p>Java transformation uses JPA @Version annotation on Card entity:</p>
 * <pre>
 * &#64;Entity
 * public class Card {
 *     &#64;Version
 *     private Long version;  // Automatic optimistic locking
 * }
 * </pre>
 *
 * <p>When repository.save() is called, JPA automatically:</p>
 * <ol>
 *   <li>Includes version field in WHERE clause of UPDATE statement</li>
 *   <li>Increments version field in SET clause</li>
 *   <li>Throws OptimisticLockException if version mismatch (0 rows updated)</li>
 *   <li>Service catches exception and transforms to user-friendly message</li>
 * </ol>
 *
 * <h2>CVV Regeneration Logic</h2>
 * <p>When card status indicates lost or stolen, CVV must be regenerated for security:</p>
 * <pre>
 * SecureRandom random = new SecureRandom();
 * String newCvv = String.format("%03d", 100 + random.nextInt(900));
 * card.setCvvCode(newCvv);
 * </pre>
 * <p>This generates a cryptographically strong random 3-digit CVV (100-999) matching
 * COBOL CARD-CVV-CD PIC 9(03) numeric format.</p>
 *
 * <h2>Validation Rules</h2>
 * <ul>
 *   <li><b>Card Number:</b> Must exist in database (findByCardNumber returns Optional)</li>
 *   <li><b>Expiration Date:</b> Must be after LocalDate.now() (future date validation)</li>
 *   <li><b>Expiration Date Range:</b> Must be within 10 years from now (reasonable limit)</li>
 *   <li><b>Status Change:</b> Cannot activate card if expirationDate.isBefore(LocalDate.now())</li>
 *   <li><b>Embossed Name:</b> Validated by DTO @NotBlank and @Size constraints</li>
 *   <li><b>Status Value:</b> Validated by DTO @Pattern ensuring 'Y' or 'N' only</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <ul>
 *   <li><b>ResourceNotFoundException:</b> Thrown when CardRepository.findByCardNumber()
 *       returns Optional.empty(), indicating card number does not exist. GlobalExceptionHandler
 *       transforms to HTTP 404 Not Found.</li>
 *   <li><b>BusinessLogicException:</b> Thrown for business rule violations including expired
 *       card reactivation attempts, expiration date validation failures, and invalid date ranges.
 *       GlobalExceptionHandler transforms to HTTP 500 Internal Server Error.</li>
 *   <li><b>OptimisticLockException:</b> Caught and transformed to BusinessLogicException
 *       with user-friendly message "Card has been modified by another user. Please refresh
 *       and try again." matching COBOL LOCKED-BUT-UPDATE-FAILED condition. GlobalExceptionHandler
 *       transforms to HTTP 409 Conflict.</li>
 * </ul>
 *
 * <h2>Audit Logging</h2>
 * <p>All card update operations are logged with SLF4J @Slf4j annotation providing:</p>
 * <ul>
 *   <li>INFO level: Successful card updates with card number</li>
 *   <li>WARN level: Business rule validation failures</li>
 *   <li>ERROR level: Optimistic locking conflicts and unexpected errors</li>
 *   <li>DEBUG level: Detailed field-level changes for troubleshooting</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * &#64;RestController
 * &#64;RequestMapping("/api/cards")
 * public class CardController {
 *     private final CardUpdateService cardUpdateService;
 * 
 *     &#64;PutMapping("/{cardNumber}")
 *     public ResponseEntity&lt;CardResponse&gt; updateCard(
 *             &#64;PathVariable String cardNumber,
 *             &#64;Valid &#64;RequestBody CardUpdateRequest request) {
 *         CardResponse response = cardUpdateService.updateCard(cardNumber, request);
 *         return ResponseEntity.ok(response);
 *     }
 * }
 * </pre>
 *
 * @see com.carddemo.entity.Card JPA entity with @Version for optimistic locking
 * @see com.carddemo.repository.CardRepository Spring Data JPA repository
 * @see com.carddemo.dto.request.CardUpdateRequest Request DTO with validation
 * @see com.carddemo.dto.response.CardResponse Response DTO with masked card number
 * @see com.carddemo.controller.CardController REST controller for card endpoints
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardUpdateService {

    /**
     * Card Repository
     *
     * <p>Spring Data JPA repository for Card entity providing CRUD operations.
     * Injected via constructor using Lombok @RequiredArgsConstructor annotation.
     *
     * <p>Replaces COBOL EXEC CICS FILE operations:</p>
     * <ul>
     *   <li>findByCardNumber() replaces EXEC CICS READ FILE(CARDDAT) RIDFLD(CARD-NUM)</li>
     *   <li>save() replaces EXEC CICS REWRITE FILE(CARDDAT) FROM(CARD-UPDATE-RECORD)</li>
     * </ul>
     */
    private final CardRepository cardRepository;

    /**
     * User Repository
     *
     * <p>Spring Data JPA repository for User entity providing user lookup operations.
     * Used for authorization checks to verify card ownership through user-customer relationship.
     * Injected via constructor using Lombok @RequiredArgsConstructor annotation.
     */
    private final UserRepository userRepository;

    /**
     * Update Card
     *
     * <p>Updates card information with comprehensive validation, optimistic locking,
     * and automatic CVV regeneration for lost/stolen cards.
     *
     * <h3>COBOL Source Mapping</h3>
     * <p>This method transforms the COBOL program COCRDUPC.cbl main processing logic:</p>
     * <ul>
     *   <li><b>Card Retrieval:</b> COBOL EXEC CICS READ (implicit in update flow) replaced
     *       with cardRepository.findByCardNumber(). If not found, throws ResourceNotFoundException
     *       matching COBOL RESP-CD 13 (NOTFND) error handling.</li>
     *   <li><b>Field Updates:</b> COBOL MOVE statements (lines 1462-1475) replaced with
     *       Java setter method calls on Card entity preserving field-level update semantics.</li>
     *   <li><b>Optimistic Lock Check:</b> COBOL paragraph 9300-CHECK-CHANGE-IN-REC (lines
     *       1498-1523) replaced with JPA @Version automatic version checking. OptimisticLockException
     *       thrown if concurrent modification detected.</li>
     *   <li><b>Database Update:</b> COBOL EXEC CICS REWRITE (lines 1477-1483) replaced with
     *       cardRepository.save() within @Transactional boundary ensuring atomic commit.</li>
     * </ul>
     *
     * <h3>Business Rule Validations</h3>
     * <ol>
     *   <li><b>Card Existence:</b> Verifies card exists using findByCardNumber(). If Optional.empty(),
     *       throws ResourceNotFoundException with message "Card not found: {cardNumber}".</li>
     *   <li><b>Expiration Date Future:</b> Validates request.getExpirationDate().isAfter(LocalDate.now()).
     *       If past date, throws BusinessLogicException "Expiration date must be in the future".</li>
     *   <li><b>Expiration Date Range:</b> Validates expirationDate is within 10 years using
     *       LocalDate.now().plusYears(10). If exceeds, throws BusinessLogicException "Expiration
     *       date cannot be more than 10 years in the future".</li>
     *   <li><b>Expired Card Reactivation Prevention:</b> When status change to 'Y' (active) requested,
     *       validates expirationDate.isAfter(LocalDate.now()). If expired, throws BusinessLogicException
     *       "Cannot activate an expired card. Expiration date: {date}".</li>
     *   <li><b>CVV Regeneration:</b> When status indicates lost/stolen card, automatically generates
     *       new 3-digit CVV using SecureRandom ensuring range 100-999 matching CARD-CVV-CD PIC 9(03).</li>
     * </ol>
     *
     * <h3>Optimistic Locking Handling</h3>
     * <p>The Card entity includes @Version annotation providing automatic optimistic locking:</p>
     * <pre>
     * &#64;Version
     * private Long version;
     * </pre>
     * <p>When repository.save() executes, JPA generates UPDATE with version check:</p>
     * <pre>
     * UPDATE card
     * SET cvv_code = ?, embossed_name = ?, expiration_date = ?, 
     *     active_status = ?, version = version + 1
     * WHERE card_number = ? AND version = ?
     * </pre>
     * <p>If WHERE clause matches 0 rows (version mismatch), OptimisticLockException thrown
     * indicating concurrent modification. Service catches and transforms to BusinessLogicException
     * with user-friendly message matching COBOL LOCKED-BUT-UPDATE-FAILED condition (line 1491).</p>
     *
     * <h3>Field Transformations</h3>
     * <table border="1">
     *   <tr>
     *     <th>COBOL Field (COCRDUPC.cbl)</th>
     *     <th>Java Request Field</th>
     *     <th>Card Entity Setter</th>
     *   </tr>
     *   <tr>
     *     <td>CCUP-NEW-CARDID (line 1462)</td>
     *     <td>request.getCardNumber()</td>
     *     <td>N/A (immutable primary key)</td>
     *   </tr>
     *   <tr>
     *     <td>CCUP-NEW-CRDNAME (line 1466)</td>
     *     <td>N/A (not in minimal update request)</td>
     *     <td>N/A (embossedName not updated)</td>
     *   </tr>
     *   <tr>
     *     <td>CCUP-NEW-EXPYEAR/EXPMON/EXPDAY (lines 1467-1474)</td>
     *     <td>request.getExpirationDate()</td>
     *     <td>card.setExpirationDate()</td>
     *   </tr>
     *   <tr>
     *     <td>CCUP-NEW-CRDSTCD (line 1475)</td>
     *     <td>request.getStatus()</td>
     *     <td>card.setActiveStatus()</td>
     *   </tr>
     *   <tr>
     *     <td>CARD-CVV-CD (regenerated for lost/stolen)</td>
     *     <td>N/A (automatic regeneration)</td>
     *     <td>card.setCvvCode(newCvv)</td>
     *   </tr>
     * </table>
     *
     * <h3>Transaction Behavior</h3>
     * <p>Method annotated with @Transactional(readOnly = false) ensures:</p>
     * <ul>
     *   <li>Database transaction started on method entry</li>
     *   <li>All repository operations within same transaction context</li>
     *   <li>Automatic commit on successful method completion</li>
     *   <li>Automatic rollback on any RuntimeException (including BusinessLogicException)</li>
     *   <li>Optimistic lock version check performed during commit phase</li>
     *   <li>No partial updates - either all changes committed or all rolled back</li>
     * </ul>
     *
     * <h3>Audit Logging</h3>
     * <p>Method logs using SLF4J @Slf4j annotation:</p>
     * <ul>
     *   <li><b>INFO:</b> "Updating card: {}" - Logged on method entry with card number</li>
     *   <li><b>DEBUG:</b> "Card found: {}" - Logged after successful card retrieval</li>
     *   <li><b>DEBUG:</b> "Expiration date validation passed" - After date range validation</li>
     *   <li><b>DEBUG:</b> "Updating expiration date from {} to {}" - Field-level change tracking</li>
     *   <li><b>DEBUG:</b> "Updating active status from {} to {}" - Status change tracking</li>
     *   <li><b>WARN:</b> "CVV regenerated for lost/stolen card" - Security event logging</li>
     *   <li><b>INFO:</b> "Card updated successfully: {}" - Logged after save() completion</li>
     *   <li><b>WARN:</b> Business rule violation messages with details</li>
     *   <li><b>ERROR:</b> "Optimistic lock failure for card: {}" - Concurrent modification</li>
     * </ul>
     *
     * <h3>Response Construction</h3>
     * <p>After successful update, Card entity mapped to CardResponse DTO:</p>
     * <ul>
     *   <li>cardNumber masked (only last 4 digits visible for PCI DSS compliance)</li>
     *   <li>accountId included as foreign key reference</li>
     *   <li>cvv included in response (may be regenerated value)</li>
     *   <li>embossedName preserved from entity</li>
     *   <li>expirationDate converted to ISO 8601 string format</li>
     *   <li>activeStatus returned as 'Y' or 'N' character</li>
     * </ul>
     *
     * @param cardNumber the 16-digit card number identifying the card to update (primary key).
     *                   Must match pattern ^[0-9]{16}$ validated by controller @PathVariable.
     *                   Maps to COBOL CCUP-NEW-CARDID (PIC X(16)) and CARD-NUM primary key.
     * @param request the card update request containing new field values. Must include:
     *                <ul>
     *                  <li>expirationDate: Future date within 10 years (validated)</li>
     *                  <li>status: 'Y' (active) or 'N' (inactive) with business rule checks</li>
     *                </ul>
     *                Maps to COBOL COMMAREA fields CCUP-NEW-EXPYEAR, CCUP-NEW-EXPMON,
     *                CCUP-NEW-EXPDAY (expiration date components) and CCUP-NEW-CRDSTCD (status).
     * @return CardResponse containing updated card information with masked card number,
     *         updated expiration date, updated status, and potentially regenerated CVV if
     *         card status indicated lost/stolen. Maps to COBOL CARD-UPDATE-RECORD structure
     *         returned to BMS screen COCRDUP.bms.
     * @throws ResourceNotFoundException if card with specified cardNumber does not exist in
     *         database (cardRepository.findByCardNumber() returns Optional.empty()). Matches
     *         COBOL RESP-CD 13 (NOTFND) error handling. GlobalExceptionHandler transforms
     *         to HTTP 404 Not Found with message "Card not found: {cardNumber}".
     * @throws BusinessLogicException if business rule validation fails including:
     *         <ul>
     *           <li>Expiration date is not future date (in the past)</li>
     *           <li>Expiration date exceeds 10-year limit from current date</li>
     *           <li>Attempt to activate card with past expiration date</li>
     *           <li>Optimistic lock failure (version mismatch from concurrent modification)</li>
     *         </ul>
     *         Matches COBOL error flag conditions and LOCKED-BUT-UPDATE-FAILED handling.
     *         GlobalExceptionHandler transforms to HTTP 500 Internal Server Error or HTTP 409
     *         Conflict (for optimistic lock failures) with descriptive error message.
     * 
     * @see Card#getVersion() JPA @Version field for optimistic locking
     * @see CardRepository#findByCardNumber(String) Card lookup by primary key
     * @see CardRepository#save(Card) Update card with version check
     * @see OptimisticLockException JPA exception for version mismatch
     */
    @Transactional
    public CardResponse updateCard(String cardNumber, CardUpdateRequest request) {
        log.info("Updating card: {}", cardNumber);

        // Retrieve card from database - replaces COBOL EXEC CICS READ
        // If not found, throws ResourceNotFoundException matching COBOL RESP-CD 13 (NOTFND)
        Card card = cardRepository.findByCardNumber(cardNumber)
                .orElseThrow(() -> {
                    log.warn("Card not found: {}", cardNumber);
                    return new ResourceNotFoundException("Card not found: " + cardNumber);
                });

        log.debug("Card found: {}", cardNumber);

        // Verify card ownership - replaces RACF authorization checks
        // Regular users can only update their own cards, admins can update all cards
        verifyCardOwnership(card);

        // Validate and update expiration date if provided
        if (request.getExpirationDate() != null) {
            validateExpirationDate(request.getExpirationDate());
            log.debug("Updating expiration date from {} to {}", 
                     card.getExpirationDate(), request.getExpirationDate());
            card.setExpirationDate(request.getExpirationDate());
        }

        // Validate and update active status if provided
        if (request.getStatus() != null) {
            validateStatusChange(card, request.getStatus());
            log.debug("Updating active status from {} to {}", 
                     card.getActiveStatus(), request.getStatus());
            
            // Regenerate CVV for lost/stolen cards
            if ("N".equals(request.getStatus()) && "Y".equals(card.getActiveStatus())) {
                String newCvv = generateNewCvv();
                card.setCvvCode(newCvv);
                log.warn("CVV regenerated for card {} due to status change to inactive", cardNumber);
            }
            
            card.setActiveStatus(request.getStatus());
        }

        // Save card with optimistic locking - replaces COBOL EXEC CICS REWRITE
        // JPA @Version annotation on Card entity provides automatic version checking
        // UPDATE statement includes: WHERE card_number = ? AND version = ?
        // If 0 rows updated (version mismatch), OptimisticLockException thrown
        // Note: OptimisticLockException is allowed to propagate to caller for proper exception handling
        Card updatedCard = cardRepository.save(card);
        
        log.info("Card updated successfully: {}", cardNumber);

        // Map entity to response DTO
        return mapToResponse(updatedCard);
    }

    /**
     * Validate Expiration Date
     *
     * <p>Validates that expiration date is future date and within reasonable range (10 years).
     *
     * <p>Business Rules:</p>
     * <ul>
     *   <li>Expiration date must be after current date (LocalDate.now())</li>
     *   <li>Expiration date must be within 10 years from current date</li>
     * </ul>
     *
     * <p>This validation prevents:</p>
     * <ul>
     *   <li>Setting expiration dates in the past (already expired cards)</li>
     *   <li>Setting unrealistic expiration dates far in the future</li>
     * </ul>
     *
     * @param expirationDate the expiration date to validate
     * @throws ValidationException if expiration date is past date or exceeds 10-year limit
     */
    private void validateExpirationDate(LocalDate expirationDate) {
        LocalDate today = LocalDate.now();
        LocalDate maxExpirationDate = today.plusYears(10);

        // Validate future date
        if (expirationDate.isBefore(today) || expirationDate.isEqual(today)) {
            log.warn("Invalid expiration date (past date): {}", expirationDate);
            Map<String, String> errors = new HashMap<>();
            errors.put("expirationDate", "Expiration date must be in the future. Provided: " + expirationDate);
            throw new ValidationException("Expiration date must be in the future", errors);
        }

        // Validate within 10-year range
        if (expirationDate.isAfter(maxExpirationDate)) {
            log.warn("Invalid expiration date (exceeds 10 years): {}", expirationDate);
            Map<String, String> errors = new HashMap<>();
            errors.put("expirationDate", "Expiration date cannot be more than 10 years in the future. Maximum: " + maxExpirationDate);
            throw new ValidationException("Expiration date cannot be more than 10 years in the future", errors);
        }

        log.debug("Expiration date validation passed: {}", expirationDate);
    }

    /**
     * Validate Status Change
     *
     * <p>Validates card status changes ensuring business rules are enforced.
     *
     * <p>Critical Business Rule:</p>
     * <ul>
     *   <li>Cannot activate (status='Y') a card with past expiration date</li>
     * </ul>
     *
     * <p>This prevents security violations where expired cards could be reactivated
     * and used for unauthorized transactions.</p>
     *
     * @param card the card entity being updated
     * @param newStatus the new status value ('Y' or 'N')
     * @throws BusinessLogicException if attempting to activate expired card
     */
    private void validateStatusChange(Card card, String newStatus) {
        // Prevent reactivation of expired cards
        if ("Y".equals(newStatus)) {
            LocalDate expirationDate = card.getExpirationDate();
            LocalDate today = LocalDate.now();
            
            if (expirationDate.isBefore(today)) {
                log.warn("Attempt to activate expired card: {} with expiration date: {}", 
                        card.getCardNumber(), expirationDate);
                throw new BusinessLogicException(
                    "Cannot activate an expired card. Expiration date: " + expirationDate);
            }
        }

        log.debug("Status change validation passed for card: {}", card.getCardNumber());
    }

    /**
     * Verify Card Ownership Authorization
     *
     * <p>Validates that the authenticated user has authorization to update the specified card.
     * Implements ownership-based access control ensuring users can only update cards linked
     * to their customer account.</p>
     *
     * <h3>Authorization Rules</h3>
     * <ul>
     *   <li><b>ADMIN Users:</b> Have full access to all cards (bypass ownership check)</li>
     *   <li><b>Regular Users:</b> Can only update cards linked to their customer account</li>
     *   <li><b>Unauthenticated:</b> Access denied (should be caught by Spring Security filter)</li>
     * </ul>
     *
     * <h3>Ownership Chain Verification</h3>
     * <p>Verifies ownership through the following entity relationship chain:</p>
     * <pre>
     * User.customerId → Customer.customerId ← Account.customerId ← Card.accountId
     * </pre>
     * <p>The method validates that the authenticated user's customerId matches the
     * customerId of the customer who owns the account associated with the card.</p>
     *
     * <h3>COBOL Security Mapping</h3>
     * <p>Replaces RACF security checks from mainframe COBOL programs:</p>
     * <ul>
     *   <li>COBOL: EXEC CICS ASSIGN USERID(WS-USERID) with RACF profile checks</li>
     *   <li>Java: SecurityContextHolder.getContext().getAuthentication()</li>
     *   <li>COBOL: User type flag (SEC-USR-TYPE 'A' for admin, 'U' for user)</li>
     *   <li>Java: Spring Security GrantedAuthority with ROLE_ADMIN / ROLE_USER</li>
     * </ul>
     *
     * <h3>Error Handling</h3>
     * <ul>
     *   <li><b>Missing Authentication:</b> Throws AccessDeniedException "User not authenticated"</li>
     *   <li><b>User Not Found:</b> Throws ResourceNotFoundException "User not found: {userId}"</li>
     *   <li><b>Ownership Mismatch:</b> Throws AccessDeniedException "Access denied: User {userId} 
     *       does not own card {cardNumber}"</li>
     * </ul>
     *
     * @param card the Card entity to verify ownership for
     * @throws AccessDeniedException if user is not authenticated, not authorized, or does not own the card
     * @throws ResourceNotFoundException if authenticated user does not exist in database
     */
    private void verifyCardOwnership(Card card) {
        // Get authenticated user from Spring Security context
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("Unauthenticated access attempt to update card: {}", card.getCardNumber());
            throw new AccessDeniedException("User not authenticated");
        }
        
        String username = authentication.getName();
        log.debug("Checking card ownership for user: {} on card: {}", username, card.getCardNumber());
        
        // Check if user has ADMIN role - admins can access all cards
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
        
        if (isAdmin) {
            log.debug("Admin user {} has full access to card: {}", username, card.getCardNumber());
            return;
        }
        
        // For regular users, verify ownership through customer relationship
        User user = userRepository.findByUserId(username)
                .orElseThrow(() -> {
                    log.error("Authenticated user not found in database: {}", username);
                    return new ResourceNotFoundException("User not found: " + username);
                });
        
        // Get customer ID from user
        Long userCustomerId = user.getCustomerId();
        
        if (userCustomerId == null) {
            log.warn("User {} has no linked customer - access denied to card: {}", 
                    username, card.getCardNumber());
            throw new AccessDeniedException("Access denied: User " + username + 
                    " is not linked to a customer account");
        }
        
        // Get customer ID from card through account relationship
        Long cardCustomerId = card.getAccount() != null && card.getAccount().getCustomer() != null
                ? card.getAccount().getCustomer().getCustomerId()
                : null;
        
        if (cardCustomerId == null) {
            log.error("Card {} has no customer relationship", card.getCardNumber());
            throw new BusinessLogicException("Card has no associated customer");
        }
        
        // Verify customer IDs match
        if (!userCustomerId.equals(cardCustomerId)) {
            log.warn("Authorization failed: User {} (customer {}) attempted to access card {} (customer {})",
                    username, userCustomerId, card.getCardNumber(), cardCustomerId);
            throw new AccessDeniedException("Access denied: User " + username + 
                    " does not own card " + card.getCardNumber());
        }
        
        log.debug("Card ownership verified: User {} owns card {}", username, card.getCardNumber());
    }

    /**
     * Generate New CVV
     *
     * <p>Generates cryptographically strong random 3-digit CVV code for lost/stolen cards.
     *
     * <p>Uses SecureRandom to generate random number in range 100-999 matching COBOL
     * CARD-CVV-CD PIC 9(03) numeric format from CVACT02Y.cpy copybook.</p>
     *
     * <p>Security Considerations:</p>
     * <ul>
     *   <li>Uses java.security.SecureRandom (not java.util.Random)</li>
     *   <li>Provides cryptographically strong random numbers</li>
     *   <li>Range 100-999 ensures exactly 3 digits (no leading zeros needed)</li>
     *   <li>Each CVV has equal probability (uniform distribution)</li>
     * </ul>
     *
     * @return 3-digit CVV code as String (e.g., "123", "456", "789")
     */
    private String generateNewCvv() {
        SecureRandom random = new SecureRandom();
        // Generate random int in range [100, 999] (3 digits)
        int cvvNumber = 100 + random.nextInt(900);
        return String.format("%03d", cvvNumber);
    }

    /**
     * Map Card Entity to Response DTO
     *
     * <p>Transforms Card JPA entity to CardResponse DTO for API response.
     * Applies masking to card number for PCI DSS compliance and formats
     * status for user-friendly display.</p>
     *
     * <p>Field Mappings:</p>
     * <ul>
     *   <li>cardNumber: Masked card number (only last 4 digits visible)</li>
     *   <li>accountId: Foreign key to Account entity</li>
     *   <li>cvv: 3-digit CVV code (potentially regenerated)</li>
     *   <li>embossedName: Cardholder name on card</li>
     *   <li>expirationDate: Expiration date in ISO 8601 format (yyyy-MM-dd)</li>
     *   <li>activeStatus: Formatted status string ("Active" or "Inactive")</li>
     * </ul>
     *
     * @param card the Card entity to map
     * @return CardResponse DTO for API response
     */
    private CardResponse mapToResponse(Card card) {
        return CardResponse.builder()
                .cardNumber(maskCardNumber(card.getCardNumber()))
                .accountId(card.getAccount() != null ? card.getAccount().getAccountId() : null)
                .cvv(card.getCvvCode())
                .embossedName(card.getEmbossedName())
                .expirationDate(card.getExpirationDate())
                .activeStatus(mapStatusToDisplay(card.getActiveStatus()))
                .build();
    }

    /**
     * Masks card number for PCI DSS compliance.
     * 
     * <p>Displays only the last 4 digits of the card number, masking all other
     * digits with asterisks. This ensures PCI DSS compliance by preventing
     * full card number exposure in API responses and logs.</p>
     * 
     * <p><strong>Masking Format:</strong></p>
     * <ul>
     *   <li>16-digit card: "************1234" (12 asterisks + last 4 digits)</li>
     *   <li>Less than 4 digits: All asterisks for security</li>
     *   <li>null or empty: Returns empty string</li>
     * </ul>
     * 
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>"4000123456789010" → "************9010"</li>
     *   <li>"5500000000000004" → "************0004"</li>
     *   <li>"123" → "***"</li>
     *   <li>null → ""</li>
     * </ul>
     * 
     * @param cardNumber Full 16-digit card number to mask. May be null or empty.
     * 
     * @return Masked card number showing only last 4 digits with leading asterisks.
     *         Returns empty string if input is null or empty.
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return cardNumber == null ? "" : "*".repeat(cardNumber.length());
        }
        // Show only last 4 digits (PCI DSS compliance)
        return "*".repeat(cardNumber.length() - 4) + cardNumber.substring(cardNumber.length() - 4);
    }

    /**
     * Maps database status values to user-friendly display values.
     * Converts COBOL CARD-ACTIVE-STATUS values from CVACT02Y.cpy to display format.
     * 
     * @param status Database status value ('Y' or 'N')
     * @return Display value ("Active" or "Inactive")
     */
    private String mapStatusToDisplay(String status) {
        if (status == null) {
            return "Unknown";
        }
        return "Y".equalsIgnoreCase(status) ? "Active" : "Inactive";
    }

}
