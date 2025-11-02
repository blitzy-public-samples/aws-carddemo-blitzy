/*
 * CardStatus.java
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.constants;

/**
 * Enumeration defining all possible card status values transformed from COBOL 88-level conditions.
 * 
 * This enum preserves the COBOL card status field definitions from COCRDUPC.cbl where
 * CARD-UPDATE-ACTIVE-STATUS is defined as PIC X(01) with validation requiring 'Y' or 'N'.
 * 
 * The enum provides type-safe constant access replacing COBOL VALUE clauses and includes
 * extended status values (EXPIRED, BLOCKED, CLOSED, PENDING) for comprehensive card lifecycle
 * management in the modernized Java application.
 * 
 * COBOL Mapping:
 * <pre>
 * COBOL: 10 CARD-UPDATE-ACTIVE-STATUS  PIC X(01).
 *        88 CARD-ACTIVE                 VALUE 'Y'.
 *        88 CARD-INACTIVE               VALUE 'N'.
 * 
 * Java:  CardStatus.ACTIVE ('Y')
 *        CardStatus.INACTIVE ('N')
 * </pre>
 * 
 * Thread-safe and immutable for concurrent card operations.
 * 
 * @see com.carddemo.entity.Card
 * @see com.carddemo.service.CardUpdateService
 * @since 1.0
 */
public enum CardStatus {
    
    /**
     * Card is active and can be used for transactions.
     * Corresponds to COBOL VALUE 'Y' in CARD-UPDATE-ACTIVE-STATUS field.
     */
    ACTIVE('Y', "Active", "Card is active and can be used for transactions"),
    
    /**
     * Card is temporarily inactive but can be reactivated.
     * Corresponds to COBOL VALUE 'N' in CARD-UPDATE-ACTIVE-STATUS field.
     */
    INACTIVE('N', "Inactive", "Card is temporarily inactive"),
    
    /**
     * Card has passed its expiration date and cannot be used.
     * Extended status for expired cards requiring replacement.
     */
    EXPIRED('E', "Expired", "Card has passed expiration date"),
    
    /**
     * Card is blocked due to security concerns or suspected fraud.
     * Extended status for security-related card suspension.
     */
    BLOCKED('B', "Blocked", "Card is blocked due to security concerns"),
    
    /**
     * Card has been permanently closed and cannot be reactivated.
     * Extended status for permanently terminated cards.
     */
    CLOSED('C', "Closed", "Card has been permanently closed"),
    
    /**
     * Card activation is pending customer confirmation.
     * Extended status for newly issued cards awaiting activation.
     */
    PENDING('P', "Pending", "Card activation is pending");
    
    /**
     * Single-character code stored in the database.
     * Matches COBOL PIC X(1) field definition.
     */
    private final char code;
    
    /**
     * Human-readable display name for UI presentation.
     */
    private final String displayName;
    
    /**
     * Detailed description of the card status.
     */
    private final String description;
    
    /**
     * Constructs a CardStatus enum value with specified attributes.
     *
     * @param code         Single-character database code matching COBOL PIC X(1)
     * @param displayName  Human-readable name for UI display
     * @param description  Detailed status description
     */
    CardStatus(char code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }
    
    /**
     * Gets the single-character database code for this status.
     * 
     * This code is stored in the PostgreSQL database and corresponds to the
     * COBOL PIC X(1) field CARD-UPDATE-ACTIVE-STATUS.
     *
     * @return Single-character status code ('Y', 'N', 'E', 'B', 'C', or 'P')
     */
    public char getCode() {
        return code;
    }
    
    /**
     * Gets the human-readable display name for this status.
     * 
     * Suitable for presentation in user interfaces and reports.
     *
     * @return Display name (e.g., "Active", "Inactive", "Expired")
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Gets the detailed description of this card status.
     * 
     * Provides additional context about the status meaning and implications.
     *
     * @return Detailed status description
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * Converts a single-character database code to the corresponding CardStatus enum value.
     * 
     * This method performs reverse lookup from the database character representation
     * to the type-safe enum value, essential for ORM entity mapping and DTO conversions.
     * 
     * Replaces COBOL 88-level condition checks:
     * <pre>
     * COBOL: IF CARD-ACTIVE THEN ...
     * Java:  if (CardStatus.fromCode('Y') == CardStatus.ACTIVE) ...
     * </pre>
     *
     * @param code Single-character status code from database ('Y', 'N', 'E', 'B', 'C', 'P')
     * @return Corresponding CardStatus enum value
     * @throws IllegalArgumentException if code does not match any valid status
     */
    public static CardStatus fromCode(char code) {
        for (CardStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException(
            String.format("Invalid card status code: '%c'. Valid codes are: Y, N, E, B, C, P", code)
        );
    }
    
    /**
     * Converts a string database code to the corresponding CardStatus enum value.
     * 
     * Convenience method for string-based status code conversion, commonly used
     * when reading from text files, API requests, or database query results returned as strings.
     * 
     * Validates that the string contains exactly one character before conversion.
     *
     * @param code String containing single-character status code (e.g., "Y", "N")
     * @return Corresponding CardStatus enum value
     * @throws IllegalArgumentException if code is null, empty, or longer than one character,
     *                                  or if the character does not match any valid status
     */
    public static CardStatus fromString(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException(
                "Card status code cannot be null or empty. Valid codes are: Y, N, E, B, C, P"
            );
        }
        if (code.length() != 1) {
            throw new IllegalArgumentException(
                String.format(
                    "Card status code must be exactly one character. Received: '%s' (length: %d)",
                    code, code.length()
                )
            );
        }
        return fromCode(code.charAt(0));
    }
    
    /**
     * Checks if a card with this status can be used for financial transactions.
     * 
     * Implements the business rule from COBOL program COCRDUPC.cbl where only cards
     * with CARD-ACTIVE status (value 'Y') are permitted for transaction processing.
     * 
     * This method is used by:
     * - AuthorizationService to validate card before processing transactions
     * - TransactionCreationService to prevent transactions on unusable cards
     * - CardUpdateService to enforce status transition rules
     * 
     * COBOL Equivalent:
     * <pre>
     * COBOL: IF CARD-ACTIVE THEN
     *            PERFORM PROCESS-TRANSACTION
     *        END-IF
     * 
     * Java:  if (cardStatus.isUsable()) {
     *            processTransaction();
     *        }
     * </pre>
     *
     * @return true if status is ACTIVE, false otherwise
     */
    public boolean isUsable() {
        return this == ACTIVE;
    }
    
    /**
     * Checks if a card with this status can be activated.
     * 
     * Determines valid status transitions to ACTIVE status. Cards can only be
     * activated if they are currently INACTIVE or PENDING activation.
     * 
     * Business rules:
     * - INACTIVE cards can be reactivated by customer service
     * - PENDING cards can be activated by customer upon receipt
     * - EXPIRED, BLOCKED, and CLOSED cards cannot be activated (require replacement)
     * 
     * Used by CardUpdateService.updateCardStatus() to validate status change requests
     * before updating the database.
     *
     * @return true if status is INACTIVE or PENDING, false otherwise
     */
    public boolean canActivate() {
        return this == INACTIVE || this == PENDING;
    }
    
    /**
     * Checks if a card with this status can be blocked for security reasons.
     * 
     * Determines if a card is eligible to be blocked due to suspected fraud,
     * lost/stolen card report, or other security concerns.
     * 
     * Business rules:
     * - ACTIVE cards can be blocked immediately upon fraud detection
     * - INACTIVE cards can be blocked as a precautionary measure
     * - EXPIRED, CLOSED, and PENDING cards cannot be blocked (no longer in use)
     * 
     * Used by:
     * - AdminService.blockCard() for fraud prevention operations
     * - CardUpdateService for status transition validation
     * - Security monitoring systems for automatic blocking
     *
     * @return true if status is ACTIVE or INACTIVE, false otherwise
     */
    public boolean canBlock() {
        return this == ACTIVE || this == INACTIVE;
    }
}

