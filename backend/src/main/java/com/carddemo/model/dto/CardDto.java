package com.carddemo.model.dto;

import com.carddemo.model.entity.Card;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for Card entity with PCI-compliant masked card numbers.
 * 
 * Converted from COBOL copybook: CVACT02Y.cpy (CARD-RECORD)
 * Original record length: 150 bytes
 * 
 * This DTO is used in REST API responses for card management operations,
 * providing a secure representation of card data with sensitive information
 * properly masked per PCI-DSS compliance requirements.
 * 
 * Security Features:
 * - Card number is masked to show only last 4 digits (e.g., "****1234")
 * - CVV code is NEVER included in API responses (not even in masked form)
 * - Suitable for transmission over network and logging without exposing sensitive data
 * 
 * Used by:
 * - CardController.listCards() for GET /api/cards endpoint
 * - CardController.getCard() for GET /api/cards/{id} endpoint
 * - CardController.updateCard() for PUT /api/cards/{id} response
 * - CardController.createCard() for POST /api/cards response
 * 
 * Conversion notes:
 * - COBOL PIC X(16) CARD-NUM → String cardNum (masked format: ****XXXX)
 * - COBOL PIC 9(11) CARD-ACCT-ID → Long cardAcctId
 * - COBOL PIC X(50) CARD-EMBOSSED-NAME → String cardEmbossedName
 * - COBOL PIC X(10) CARD-EXPIRAION-DATE → LocalDate cardExpirationDate
 * - COBOL PIC X(01) CARD-ACTIVE-STATUS → String cardStatus
 * - COBOL PIC 9(03) CARD-CVV-CD → EXPLICITLY EXCLUDED per PCI-DSS Section 3.2
 * - Added cardActiveDate from database schema (not in original COBOL)
 * - Added audit timestamps createdAt and updatedAt for API transparency
 * 
 * Field Mapping from Card Entity:
 * - cardNum: Masked version of Card.cardNum (shows only last 4 digits)
 * - cardAcctId: Direct mapping from Card.cardAcctId
 * - cardEmbossedName: Direct mapping from Card.cardEmbossedName
 * - cardExpirationDate: Direct mapping from Card.cardExpirationDate
 * - cardStatus: Direct mapping from Card.cardStatus
 * - cardActiveDate: Direct mapping from Card.cardActiveDate
 * - createdAt: Converted from Card.createdAt (Timestamp → LocalDateTime)
 * - updatedAt: Converted from Card.updatedAt (Timestamp → LocalDateTime)
 * 
 * @see Card
 * @see com.carddemo.controller.CardController
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardDto {

    /**
     * Masked card number showing only last 4 digits for PCI compliance.
     * 
     * Format: "****XXXX" where XXXX are the last 4 digits of the actual card number.
     * Example: Full card number "4111111111111234" is masked as "****1234"
     * 
     * This field provides sufficient information for card identification in UI
     * (e.g., "Card ending in 1234") while protecting the full card number from
     * unauthorized access.
     * 
     * The masking is performed by the static fromEntity() factory method and
     * ensures that full card numbers never appear in:
     * - API response bodies
     * - Application logs
     * - Browser network traces
     * - Third-party monitoring tools
     * 
     * Original COBOL field: CARD-NUM PIC X(16)
     * PCI-DSS Requirement: 3.3 (Mask PAN when displayed)
     */
    private String cardNum;

    /**
     * Associated account ID (foreign key to Account entity).
     * 
     * Converted from: COBOL PIC 9(11) CARD-ACCT-ID
     * Maximum value: 99,999,999,999 (11 digits)
     * 
     * Links this card to its associated credit card account. Used by frontend
     * to navigate from card details to account details, and by backend to
     * retrieve account balance, credit limit, and transaction history for the card.
     * 
     * This field is safe to expose in API responses as account IDs are not
     * considered sensitive data under PCI-DSS (though they are internal identifiers
     * and should not be shared with external parties).
     */
    private Long cardAcctId;

    /**
     * Embossed cardholder name.
     * 
     * Converted from: COBOL PIC X(50) CARD-EMBOSSED-NAME
     * Maximum length: 50 characters
     * Format: Usually "FIRSTNAME LASTNAME" or "LASTNAME/FIRSTNAME"
     * 
     * Name embossed on physical card. Displayed in card management UI for
     * cardholder identification and verification.
     * 
     * May be null for virtual cards or corporate cards with generic embossing.
     * Typically all uppercase per card production standards.
     * 
     * Note: This is cardholder name, not account owner name. For authorized user
     * cards, this reflects the authorized user's name, not the primary account holder.
     */
    private String cardEmbossedName;

    /**
     * Card expiration date.
     * 
     * Converted from: COBOL PIC X(10) CARD-EXPIRAION-DATE
     * Format: YYYY-MM-DD (ISO-8601 date format in JSON)
     * 
     * Date when card expires and can no longer be used for transactions.
     * Displayed in UI as MM/YY per standard card display format.
     * 
     * Used by frontend to:
     * - Display expiration date on card list and detail screens
     * - Highlight cards nearing expiration (e.g., within 60 days)
     * - Trigger card reissuance workflow when approaching expiry
     * 
     * JSON serialization format specified by @JsonFormat annotation.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate cardExpirationDate;

    /**
     * Card status indicator.
     * 
     * Converted from: COBOL PIC X(01) CARD-ACTIVE-STATUS
     * Length: 1 character
     * 
     * Valid values:
     * - 'Y' = Active (card can be used for transactions)
     * - 'N' = Inactive (card cannot be used, awaiting activation)
     * - 'S' = Stolen (card reported stolen, block all transactions)
     * - 'L' = Lost (card reported lost, block all transactions)
     * - 'E' = Expired (card past expiration date)
     * - 'C' = Closed (card permanently closed)
     * 
     * Used by frontend to:
     * - Display status badge with appropriate color coding
     * - Enable/disable card management actions based on status
     * - Filter card lists by status
     * 
     * Used by backend to validate transaction eligibility before processing.
     */
    private String cardStatus;

    /**
     * Card activation date.
     * 
     * Added field (not in original COBOL copybook CVACT02Y.cpy).
     * Database schema field: card_active_date DATE
     * Format: YYYY-MM-DD (ISO-8601 date format in JSON)
     * 
     * Date when card was activated by cardholder. Null if card has not been
     * activated yet (newly issued cards awaiting activation).
     * 
     * Used by frontend to:
     * - Display activation status
     * - Show activation date in card details
     * - Distinguish between "issued" and "active" cards
     * 
     * Cards must be activated before first use. If null, frontend shows
     * "Activation Required" message and provides activation workflow.
     * 
     * JSON serialization format specified by @JsonFormat annotation.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate cardActiveDate;

    /**
     * Record creation timestamp.
     * 
     * Added field (not in original COBOL copybook).
     * Format: ISO-8601 date-time format (YYYY-MM-DD'T'HH:MM:SS) in JSON
     * 
     * Timestamp when card record was created in the system, corresponding to
     * card issuance date in business terms.
     * 
     * Used by frontend for audit trail display and card issuance reporting.
     * Converted from java.sql.Timestamp in entity to LocalDateTime for better
     * JSON serialization and client-side date handling.
     * 
     * JSON serialization format specified by @JsonFormat annotation.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * Record last update timestamp.
     * 
     * Added field (not in original COBOL copybook).
     * Format: ISO-8601 date-time format (YYYY-MM-DD'T'HH:MM:SS) in JSON
     * 
     * Timestamp when card record was last modified. Updated automatically on
     * any card status changes, embossed name updates, or expiration date extensions.
     * 
     * Used by frontend for:
     * - Audit trail display
     * - Cache invalidation decisions
     * - Optimistic locking conflict resolution
     * 
     * Converted from java.sql.Timestamp in entity to LocalDateTime for better
     * JSON serialization and client-side date handling.
     * 
     * JSON serialization format specified by @JsonFormat annotation.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * Static factory method to convert Card entity to CardDto with card number masking.
     * 
     * This method implements PCI-DSS compliant card number masking by replacing
     * all digits except the last 4 with asterisks. This ensures that full card
     * numbers (Primary Account Numbers / PANs) never appear in API responses.
     * 
     * Card Number Masking Logic:
     * - If card number is null or empty: return "****"
     * - If card number length <= 4: return masked as "****" (protect short numbers)
     * - If card number length > 4: return "****" + last 4 digits
     * 
     * Examples:
     * - Input: "4111111111111234" → Output: "****1234"
     * - Input: "5500000000000004" → Output: "****0004"
     * - Input: "123" → Output: "****" (protect short number)
     * - Input: null → Output: "****"
     * - Input: "" → Output: "****"
     * 
     * Timestamp Conversion:
     * - Entity uses java.sql.Timestamp (JDBC standard)
     * - DTO uses java.time.LocalDateTime (Java 8 date-time API)
     * - Conversion: timestamp.toLocalDateTime() for better JSON handling
     * 
     * Field Exclusions:
     * - Card CVV code is NEVER copied (not present in entity, per PCI-DSS 3.2)
     * - Card.cardCardmemberId is excluded (internal ID not needed in basic DTO)
     * - Card.account relationship is excluded (prevents circular serialization)
     * - Card.version is excluded (internal optimistic locking field)
     * 
     * Usage in Controllers:
     * <pre>
     * // Convert single entity
     * Card card = cardRepository.findById(cardNum).orElseThrow();
     * CardDto dto = CardDto.fromEntity(card);
     * 
     * // Convert list of entities
     * List&lt;Card&gt; cards = cardRepository.findAll();
     * List&lt;CardDto&gt; dtos = cards.stream()
     *     .map(CardDto::fromEntity)
     *     .collect(Collectors.toList());
     * </pre>
     * 
     * PCI-DSS Compliance:
     * - Requirement 3.3: Mask PAN when displayed (showing only last 4 digits)
     * - Requirement 3.2.2: Do not store CVV code (excluded from entity and DTO)
     * - Requirement 4.2: Never send unprotected PANs (masking before transmission)
     * 
     * @param card the Card entity to convert (must not be null)
     * @return CardDto with masked card number and all non-sensitive fields populated
     * @throws NullPointerException if card parameter is null
     * 
     * @see Card
     * @see com.carddemo.controller.CardController
     */
    public static CardDto fromEntity(Card card) {
        // Implement card number masking logic
        String maskedCardNum;
        if (card.getCardNum() == null || card.getCardNum().isEmpty()) {
            maskedCardNum = "****";
        } else if (card.getCardNum().length() <= 4) {
            // For card numbers with 4 or fewer digits, mask completely
            // to avoid exposing the entire number
            maskedCardNum = "****";
        } else {
            // Standard masking: show only last 4 digits
            String lastFour = card.getCardNum().substring(card.getCardNum().length() - 4);
            maskedCardNum = "****" + lastFour;
        }

        // Convert Timestamp to LocalDateTime for better JSON serialization
        LocalDateTime createdAtLocal = card.getCreatedAt() != null 
            ? card.getCreatedAt().toLocalDateTime() 
            : null;
        LocalDateTime updatedAtLocal = card.getUpdatedAt() != null 
            ? card.getUpdatedAt().toLocalDateTime() 
            : null;

        // Build and return DTO with masked card number and all fields
        return CardDto.builder()
                .cardNum(maskedCardNum)
                .cardAcctId(card.getCardAcctId())
                .cardEmbossedName(card.getCardEmbossedName())
                .cardExpirationDate(card.getCardExpirationDate())
                .cardStatus(card.getCardStatus())
                .cardActiveDate(card.getCardActiveDate())
                .createdAt(createdAtLocal)
                .updatedAt(updatedAtLocal)
                .build();
    }
}
