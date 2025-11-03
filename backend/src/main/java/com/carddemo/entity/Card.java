package com.carddemo.entity;

import com.carddemo.constants.CardStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * JPA Entity representing the Card master data table.
 * 
 * <p>This entity is transformed from COBOL copybook CVACT02Y.cpy (CARD-RECORD)
 * which defines a 150-byte record structure for credit card information stored in the
 * VSAM CARDDAT KSDS file. This entity establishes the critical card-to-account
 * relationship and contains all card identification and security data.</p>
 * 
 * <p><strong>PCI-DSS Compliance and Security Requirements (Section 0.4 and 0.9):</strong></p>
 * <ul>
 *   <li>Card number (PAN) is Level 1 PII - MUST be encrypted at rest in database</li>
 *   <li>CVV code is Level 1 PII - MUST be encrypted at rest and NEVER logged</li>
 *   <li>Card number display MUST mask all but last 4 digits (e.g., **** **** **** 1234)</li>
 *   <li>CVV code MUST NEVER be displayed or logged under any circumstance</li>
 *   <li>All card data access requires audit trail per Section 0.9 requirements</li>
 *   <li>Database encryption required for cardNumber and cvvCode columns</li>
 * </ul>
 * 
 * <p><strong>Entity Relationships:</strong></p>
 * <ul>
 *   <li>Parent entity: Account (many-to-one via account_id foreign key)</li>
 *   <li>Child entities: Transaction (one-to-many), CardXref (cross-reference)</li>
 *   <li>16-character card number serves as primary key</li>
 * </ul>
 * 
 * <p><strong>Data Transformation Details:</strong></p>
 * <ul>
 *   <li>CARD-NUM PIC X(16) → String cardNumber (primary key, PII)</li>
 *   <li>CARD-ACCT-ID PIC 9(11) → Long accountId (foreign key to Account)</li>
 *   <li>CARD-CVV-CD PIC 9(03) → String cvvCode (3-digit CVV, PII - encrypted)</li>
 *   <li>CARD-EMBOSSED-NAME PIC X(50) → String embossedName (cardholder name)</li>
 *   <li>CARD-EXPIRAION-DATE PIC X(10) → LocalDate expirationDate (typo in COBOL)</li>
 *   <li>CARD-ACTIVE-STATUS PIC X(01) → String activeStatus (status code character)</li>
 *   <li>FILLER PIC X(59) → NOT MAPPED (unused COBOL padding)</li>
 * </ul>
 * 
 * <p><strong>Card Status Enumeration (COBOL 88-Level Preservation):</strong></p>
 * <ul>
 *   <li>Integrates CardStatus enum per Section 0.9 COBOL construct preservation</li>
 *   <li>CARD-ACTIVE ('Y') → CardStatus.ACTIVE</li>
 *   <li>CARD-INACTIVE ('N') → CardStatus.INACTIVE</li>
 *   <li>CARD-BLOCKED ('B') → CardStatus.BLOCKED</li>
 *   <li>CARD-EXPIRED ('E') → CardStatus.EXPIRED</li>
 *   <li>CARD-CLOSED ('C') → CardStatus.CLOSED</li>
 *   <li>CARD-PENDING ('P') → CardStatus.PENDING</li>
 * </ul>
 * 
 * <p><strong>Usage in COBOL Programs (Transformation Context):</strong></p>
 * <ul>
 *   <li>COCRDLIC.cbl → CardListService (card list display with pagination)</li>
 *   <li>COCRDSLC.cbl → CardDetailService (card detail view)</li>
 *   <li>COCRDUPC.cbl → CardUpdateService (card information update)</li>
 *   <li>COTRN00C.cbl → TransactionListService (transaction processing)</li>
 *   <li>CBCRD01C.cbl → CardDataLoadJob (batch data load)</li>
 * </ul>
 * 
 * <p><strong>COBOL Source:</strong> app/cpy/CVACT02Y.cpy</p>
 * <p><strong>VSAM File:</strong> CARDDAT KSDS (Key-Sequenced Dataset)</p>
 * <p><strong>Record Length:</strong> 150 bytes</p>
 * 
 * @see Account
 * @see CardStatus
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.3">COBOL to Java Type Conversion Rules</a>
 * @see <a href="Section 0.9">Security and PCI-DSS Requirements</a>
 */
@Entity
@Table(name = "card")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Card implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Card number - Primary Access Number (PAN) - 16 characters.
     * Maps to COBOL field: CARD-NUM PIC X(16)
     * 
     * <p><strong>PRIMARY KEY for Card entity.</strong></p>
     * 
     * <p><strong>PCI-DSS Level 1 PII - CRITICAL SECURITY REQUIREMENTS:</strong></p>
     * <ul>
     *   <li>MUST be encrypted at rest in PostgreSQL database</li>
     *   <li>MUST be masked in all log outputs (show only last 4 digits)</li>
     *   <li>MUST be masked in UI displays (format: **** **** **** 1234)</li>
     *   <li>MUST be transmitted only over TLS 1.3+ encrypted channels</li>
     *   <li>Access MUST be logged in audit trail per Section 0.9</li>
     * </ul>
     * 
     * <p>Stored as String to preserve leading zeros (not numeric for arithmetic).
     * Standard format: 16 consecutive digits (e.g., "4532123456789012").</p>
     */
    @Id
    @Column(name = "card_number", length = 16, nullable = false)
    private String cardNumber;

    /**
     * Account identifier - Foreign key to Account entity (11-digit numeric).
     * Maps to COBOL field: CARD-ACCT-ID PIC 9(11)
     * 
     * <p>Establishes many-to-one relationship from Card to Account entity.
     * Multiple cards can be associated with a single account for primary
     * cardholders, authorized users, and replacement cards.</p>
     * 
     * <p>This field enables:</p>
     * <ul>
     *   <li>Card-to-account lookup for transaction authorization</li>
     *   <li>Account balance verification before transaction approval</li>
     *   <li>Credit limit checks via parent Account entity</li>
     *   <li>Cardholder account statement generation</li>
     * </ul>
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Card Verification Value (CVV) - 3-digit security code.
     * Maps to COBOL field: CARD-CVV-CD PIC 9(03)
     * 
     * <p><strong>PCI-DSS Level 1 HIGHEST SENSITIVITY PII - CRITICAL SECURITY:</strong></p>
     * <ul>
     *   <li>MUST be encrypted at rest with strongest available encryption</li>
     *   <li>MUST NEVER be logged under ANY circumstance (not even masked)</li>
     *   <li>MUST NEVER be displayed in UI (not even partially)</li>
     *   <li>MUST be transmitted ONLY during initial card issuance (secure channel)</li>
     *   <li>MUST be purged from application memory immediately after use</li>
     *   <li>Access violations MUST trigger immediate security alerts</li>
     * </ul>
     * 
     * <p>Stored as String to preserve leading zeros (e.g., "007", "012").
     * Not stored as numeric since CVV is not used for arithmetic operations.</p>
     * 
     * <p><strong>WARNING:</strong> This field should typically not be stored long-term
     * per PCI-DSS best practices. Consider storing only during card activation
     * workflow and purging after initial use.</p>
     */
    @Column(name = "cvv_code", length = 3, nullable = false)
    private String cvvCode;

    /**
     * Embossed name - Cardholder name as it appears on the physical card.
     * Maps to COBOL field: CARD-EMBOSSED-NAME PIC X(50)
     * 
     * <p>Maximum 50 characters including spaces. Typically formatted as:</p>
     * <ul>
     *   <li>Format: "FIRSTNAME LASTNAME" or "LASTNAME, FIRSTNAME"</li>
     *   <li>May include middle initial or name suffix (Jr., Sr., III)</li>
     *   <li>All uppercase per card embossing standards</li>
     *   <li>Special characters limited to space, comma, period, hyphen, apostrophe</li>
     * </ul>
     * 
     * <p>This name must match the account holder's legal name for fraud
     * prevention and PCI-DSS compliance purposes.</p>
     */
    @Column(name = "embossed_name", length = 50, nullable = false)
    private String embossedName;

    /**
     * Card expiration date - Month and year when card expires.
     * Maps to COBOL field: CARD-EXPIRAION-DATE PIC X(10)
     * 
     * <p><strong>Note:</strong> COBOL field name has typo "EXPIRAION" instead of "EXPIRATION".</p>
     * 
     * <p>Original COBOL format: String date representation (typically MM/YY or YYYY-MM-DD).
     * Converted to LocalDate for type-safe date handling with ISO-8601 formatting.</p>
     * 
     * <p>Business Rules:</p>
     * <ul>
     *   <li>Expiration occurs at end of month (e.g., 12/2024 means valid through 12/31/2024)</li>
     *   <li>Cards typically valid for 3-5 years from issue date</li>
     *   <li>Replacement cards issued 60 days before expiration</li>
     *   <li>Expired cards automatically transition to EXPIRED status</li>
     * </ul>
     * 
     * <p>Used for:</p>
     * <ul>
     *   <li>Transaction authorization validation (reject if expired)</li>
     *   <li>Card replacement scheduling (batch job CBCRD01C)</li>
     *   <li>Automatic status update to EXPIRED (daily batch processing)</li>
     * </ul>
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * Card active status indicator - Single character status code.
     * Maps to COBOL field: CARD-ACTIVE-STATUS PIC X(01)
     * 
     * <p>Stores single-character database code corresponding to CardStatus enum values.
     * Provides backward compatibility with COBOL character-based status while enabling
     * type-safe status handling via CardStatus enum conversion methods.</p>
     * 
     * <p><strong>Valid Status Codes (per CardStatus enum):</strong></p>
     * <ul>
     *   <li>'Y' → ACTIVE - Card is active and can be used for transactions</li>
     *   <li>'N' → INACTIVE - Card is temporarily inactive (can be reactivated)</li>
     *   <li>'B' → BLOCKED - Card is blocked due to security concerns or fraud</li>
     *   <li>'E' → EXPIRED - Card has passed expiration date (requires replacement)</li>
     *   <li>'C' → CLOSED - Card permanently closed (cannot be reactivated)</li>
     *   <li>'P' → PENDING - Card activation pending customer confirmation</li>
     * </ul>
     * 
     * <p>Status transitions enforced by CardUpdateService per business rules:
     * PENDING → ACTIVE, INACTIVE ↔ ACTIVE, ACTIVE/INACTIVE → BLOCKED, * → CLOSED</p>
     */
    @Column(name = "active_status", length = 1, nullable = false)
    private String activeStatus;

    /**
     * Parent account relationship (many-to-one).
     * 
     * <p>Establishes foreign key relationship from Card to Account entity via accountId.
     * Each card belongs to exactly one account, but an account may have multiple cards
     * (primary card, authorized user cards, replacement cards).</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers parent account retrieval
     * until explicitly accessed, optimizing query performance and reducing memory
     * footprint. This maintains sub-200ms response times per Section 0.2 performance
     * requirements under 10,000 TPS load.</p>
     * 
     * <p><strong>JoinColumn Configuration:</strong></p>
     * <ul>
     *   <li>insertable = false, updatable = false: Prevents JPA from managing this side
     *       of the bidirectional relationship (accountId field is the owning side)</li>
     *   <li>Avoids duplicate column mapping conflicts with accountId field</li>
     *   <li>accountId remains the single source of truth for foreign key value</li>
     * </ul>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues when Card entities are serialized in REST API responses, avoiding
     * infinite recursion between Card and Account entities in bidirectional relationships.</p>
     * 
     * <p>Usage: Access account balance, credit limit, and account status for
     * transaction authorization decisions via card.getAccount().</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    @JsonIgnore
    private Account account;

    /**
     * Gets the CardStatus enum value corresponding to the current activeStatus character code.
     * 
     * <p>Converts the single-character database status code to type-safe CardStatus enum,
     * enabling compile-time type checking and business logic validation. Preserves COBOL
     * 88-level condition name pattern per Section 0.9 requirements.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL: IF CARD-ACTIVE THEN ...
     * Java:  if (card.getCardStatus() == CardStatus.ACTIVE) { ... }
     * </pre>
     * 
     * @return CardStatus enum value (ACTIVE, INACTIVE, BLOCKED, EXPIRED, CLOSED, PENDING)
     * @throws IllegalArgumentException if activeStatus contains invalid character code
     */
    public CardStatus getCardStatus() {
        if (activeStatus == null || activeStatus.isEmpty()) {
            throw new IllegalArgumentException("Card status cannot be null or empty");
        }
        return CardStatus.fromString(activeStatus);
    }

    /**
     * Sets the card status using CardStatus enum value.
     * 
     * <p>Converts type-safe CardStatus enum to single-character database code for persistence.
     * Enforces valid status values at compile time, preventing invalid status codes.</p>
     * 
     * @param cardStatus CardStatus enum value to set
     * @throws IllegalArgumentException if cardStatus is null
     */
    public void setCardStatus(CardStatus cardStatus) {
        if (cardStatus == null) {
            throw new IllegalArgumentException("CardStatus cannot be null");
        }
        this.activeStatus = String.valueOf(cardStatus.getCode());
    }

    /**
     * Checks if the card is currently active and usable for transactions.
     * 
     * <p>Implements the business rule from COBOL program COCRDUPC.cbl where only cards
     * with CARD-ACTIVE status (value 'Y') are permitted for transaction processing.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL: IF CARD-ACTIVE THEN
     *            PERFORM PROCESS-TRANSACTION
     *        END-IF
     * 
     * Java:  if (card.isActive()) {
     *            processTransaction();
     *        }
     * </pre>
     * 
     * <p>Used by:</p>
     * <ul>
     *   <li>TransactionCreationService - Validate card before posting transaction</li>
     *   <li>BillPaymentService - Verify card can be charged for bill payment</li>
     *   <li>CardListService - Filter active cards for display</li>
     *   <li>Authorization endpoints - Reject transactions on inactive cards</li>
     * </ul>
     * 
     * @return true if card status is ACTIVE ('Y'), false otherwise
     */
    public boolean isActive() {
        try {
            return getCardStatus().isUsable();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Checks if the card has expired based on expiration date.
     * 
     * <p>A card is considered expired if the current date is after the last day of the
     * expiration month. For example, a card expiring 12/2024 is valid through 12/31/2024
     * and expires on 01/01/2025.</p>
     * 
     * <p>Business Rules:</p>
     * <ul>
     *   <li>Expired cards automatically rejected for transaction authorization</li>
     *   <li>Daily batch job (CBCRD01C equivalent) updates status to EXPIRED</li>
     *   <li>Expired cards trigger replacement card issuance workflow</li>
     *   <li>Grace period: Some issuers allow transactions up to 30 days post-expiration</li>
     * </ul>
     * 
     * @return true if expirationDate is in the past, false if still valid or expirationDate is null
     */
    public boolean isExpired() {
        if (expirationDate == null) {
            return false;
        }
        return LocalDate.now().isAfter(expirationDate);
    }

    /**
     * Checks if the card is approaching expiration (within 60 days).
     * 
     * <p>Used by batch processing jobs to identify cards requiring replacement card issuance.
     * Replacement cards are typically mailed 60 days before expiration to ensure
     * cardholder receives new card before current card expires.</p>
     * 
     * <p>Triggers:</p>
     * <ul>
     *   <li>Replacement card generation in batch job CBCRD01C</li>
     *   <li>Notification emails to cardholder</li>
     *   <li>Customer service alert for follow-up</li>
     * </ul>
     * 
     * @return true if card expires within next 60 days, false otherwise
     */
    public boolean isExpiringSoon() {
        if (expirationDate == null) {
            return false;
        }
        LocalDate sixtyDaysFromNow = LocalDate.now().plusDays(60);
        return expirationDate.isBefore(sixtyDaysFromNow);
    }

    /**
     * Checks if the card can be activated based on current status.
     * 
     * <p>Determines valid status transitions to ACTIVE status using CardStatus enum
     * business rules. Cards can only be activated if they are currently INACTIVE
     * or PENDING activation.</p>
     * 
     * <p>Status Transition Rules:</p>
     * <ul>
     *   <li>PENDING → ACTIVE: Customer activates new card upon receipt</li>
     *   <li>INACTIVE → ACTIVE: Customer service reactivates temporarily disabled card</li>
     *   <li>EXPIRED/BLOCKED/CLOSED → ACTIVE: NOT ALLOWED (requires replacement card)</li>
     * </ul>
     * 
     * <p>Used by CardUpdateService.updateCardStatus() to validate activation requests
     * before updating the database per Section 0.9 transaction boundary requirements.</p>
     * 
     * @return true if status is INACTIVE or PENDING, false otherwise
     */
    public boolean canActivate() {
        try {
            return getCardStatus().canActivate();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Checks if the card can be blocked for security reasons.
     * 
     * <p>Determines if a card is eligible to be blocked due to suspected fraud,
     * lost/stolen card report, or other security concerns using CardStatus enum
     * business rules.</p>
     * 
     * <p>Blocking Rules:</p>
     * <ul>
     *   <li>ACTIVE cards: Can be blocked immediately upon fraud detection</li>
     *   <li>INACTIVE cards: Can be blocked as precautionary measure</li>
     *   <li>EXPIRED/CLOSED/PENDING cards: Cannot be blocked (no longer in active use)</li>
     * </ul>
     * 
     * <p>Used by:</p>
     * <ul>
     *   <li>AdminService.blockCard() - Manual fraud prevention operations</li>
     *   <li>Fraud detection systems - Automatic blocking on suspicious activity</li>
     *   <li>CardUpdateService - Status transition validation</li>
     * </ul>
     * 
     * @return true if status is ACTIVE or INACTIVE, false otherwise
     */
    public boolean canBlock() {
        try {
            return getCardStatus().canBlock();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns masked card number for secure display and logging.
     * 
     * <p><strong>PCI-DSS Compliance:</strong> Card numbers must be masked in all displays
     * and logs, showing only the last 4 digits for cardholder verification. This method
     * implements the standard masking format required by PCI-DSS Section 3.3.</p>
     * 
     * <p>Masking Format: "**** **** **** 1234" (spaces added for readability)</p>
     * 
     * <p>Usage:</p>
     * <ul>
     *   <li>REST API responses - Card list and detail endpoints</li>
     *   <li>Application logs - Transaction processing logs</li>
     *   <li>User interfaces - Card selection screens</li>
     *   <li>Error messages - Card validation failures</li>
     *   <li>Audit trails - Security event logging</li>
     * </ul>
     * 
     * <p><strong>CRITICAL:</strong> The full card number (PAN) must NEVER appear in:</p>
     * <ul>
     *   <li>Application logs (use this masked version instead)</li>
     *   <li>Error messages or stack traces</li>
     *   <li>User interface displays (except secure activation flow)</li>
     *   <li>Email notifications or printed statements</li>
     *   <li>Third-party integrations (unless PCI-compliant)</li>
     * </ul>
     * 
     * @return Masked card number showing only last 4 digits (e.g., "**** **** **** 1234")
     *         or "****" if cardNumber is null or too short
     */
    public String getMaskedCardNumber() {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        String lastFour = cardNumber.substring(cardNumber.length() - 4);
        return "**** **** **** " + lastFour;
    }

    /**
     * Returns formatted expiration date in MM/YY format for display.
     * 
     * <p>Converts LocalDate expirationDate to the standard MM/YY format used on
     * physical credit cards and in user interfaces. This format is familiar to
     * cardholders and matches industry standard card display conventions.</p>
     * 
     * <p>Examples:</p>
     * <ul>
     *   <li>2024-12-31 → "12/24"</li>
     *   <li>2025-03-31 → "03/25"</li>
     *   <li>null → "N/A"</li>
     * </ul>
     * 
     * @return Expiration date in MM/YY format (e.g., "12/24") or "N/A" if expirationDate is null
     */
    public String getFormattedExpirationDate() {
        if (expirationDate == null) {
            return "N/A";
        }
        int month = expirationDate.getMonthValue();
        int year = expirationDate.getYear() % 100;
        return String.format("%02d/%02d", month, year);
    }

    /**
     * Gets the human-readable display name for the current card status.
     * 
     * <p>Converts the single-character status code to a user-friendly display name
     * suitable for UI presentation and reports. Uses CardStatus enum getDisplayName()
     * method per members_accessed requirements.</p>
     * 
     * <p>Examples:</p>
     * <ul>
     *   <li>'Y' → "Active"</li>
     *   <li>'N' → "Inactive"</li>
     *   <li>'B' → "Blocked"</li>
     *   <li>'E' → "Expired"</li>
     * </ul>
     * 
     * @return Display name for current status (e.g., "Active", "Blocked")
     * @throws IllegalArgumentException if activeStatus is invalid
     */
    public String getStatusDisplayName() {
        return getCardStatus().getDisplayName();
    }

    /**
     * Gets the detailed description for the current card status.
     * 
     * <p>Provides additional context about the status meaning and implications
     * using CardStatus enum getDescription() method. Useful for help text,
     * tooltips, and detailed status explanations in user interfaces.</p>
     * 
     * <p>Examples:</p>
     * <ul>
     *   <li>ACTIVE → "Card is active and can be used for transactions"</li>
     *   <li>BLOCKED → "Card is blocked due to security concerns"</li>
     *   <li>EXPIRED → "Card has passed expiration date"</li>
     * </ul>
     * 
     * @return Detailed description of current status
     * @throws IllegalArgumentException if activeStatus is invalid
     */
    public String getStatusDescription() {
        return getCardStatus().getDescription();
    }

    /**
     * Checks if the card is usable for financial transactions.
     * 
     * <p>Comprehensive validation combining status check and expiration check.
     * A card is usable only if it is both ACTIVE status and not expired.</p>
     * 
     * <p>This method provides a single point of validation for transaction
     * authorization workflows, consolidating business rules from COBOL programs
     * COCRDUPC.cbl (status validation) and transaction processing logic.</p>
     * 
     * <p>Validation Rules:</p>
     * <ul>
     *   <li>Card must have ACTIVE status ('Y')</li>
     *   <li>Card must not be past expiration date</li>
     *   <li>Both conditions must be true for card to be usable</li>
     * </ul>
     * 
     * <p>Used by:</p>
     * <ul>
     *   <li>TransactionCreationService - Pre-transaction validation</li>
     *   <li>BillPaymentService - Payment authorization</li>
     *   <li>Authorization REST endpoints - Transaction approval</li>
     * </ul>
     * 
     * @return true if card is ACTIVE and not expired, false otherwise
     */
    public boolean isUsableForTransactions() {
        return isActive() && !isExpired();
    }

    /**
     * Validates that the card number passes Luhn algorithm check.
     * 
     * <p>The Luhn algorithm (also known as modulus 10 or mod 10 algorithm) is a checksum
     * formula used to validate credit card numbers. This validation helps detect simple
     * errors in card number entry such as typos or digit transposition.</p>
     * 
     * <p><strong>Algorithm Steps:</strong></p>
     * <ol>
     *   <li>Starting from the rightmost digit, double every second digit</li>
     *   <li>If doubling results in a two-digit number, add the digits (e.g., 16 → 1+6 = 7)</li>
     *   <li>Sum all the digits</li>
     *   <li>If the total modulo 10 equals 0, the number is valid</li>
     * </ol>
     * 
     * <p>Usage:</p>
     * <ul>
     *   <li>Input validation in card creation and update forms</li>
     *   <li>Data quality checks in batch card data load (CBCRD01C)</li>
     *   <li>Fraud detection - invalid checksums indicate potential fraud</li>
     * </ul>
     * 
     * @return true if card number passes Luhn check, false otherwise or if cardNumber is null/invalid
     */
    public boolean passesLuhnCheck() {
        if (cardNumber == null || cardNumber.isEmpty()) {
            return false;
        }
        
        try {
            int sum = 0;
            boolean alternate = false;
            
            for (int i = cardNumber.length() - 1; i >= 0; i--) {
                int digit = Character.getNumericValue(cardNumber.charAt(i));
                
                if (digit < 0 || digit > 9) {
                    return false; // Invalid character
                }
                
                if (alternate) {
                    digit *= 2;
                    if (digit > 9) {
                        digit = (digit % 10) + 1;
                    }
                }
                
                sum += digit;
                alternate = !alternate;
            }
            
            return (sum % 10 == 0);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Custom toString implementation with PII masking for security compliance.
     * 
     * <p><strong>PCI-DSS Compliance (Section 3.3 and 3.4):</strong></p>
     * <ul>
     *   <li>Card number: Masked to show only last 4 digits (**** **** **** 1234)</li>
     *   <li>CVV code: Completely masked (***) - NEVER displayed or logged</li>
     *   <li>Other fields: Displayed in full (embossed name, dates, status are not PII)</li>
     * </ul>
     * 
     * <p>This toString() method is safe for:</p>
     * <ul>
     *   <li>Application logging (INFO, DEBUG, ERROR levels)</li>
     *   <li>Exception stack traces</li>
     *   <li>Debugging output</li>
     *   <li>Audit trail entries</li>
     * </ul>
     * 
     * <p><strong>CRITICAL:</strong> Do NOT create custom toString() that exposes full
     * card number or CVV code. Such exposure violates PCI-DSS Section 3.3 and creates
     * security vulnerabilities and compliance violations.</p>
     * 
     * @return String representation of Card entity with PII fields properly masked
     */
    @Override
    public String toString() {
        return "Card{" +
                "cardNumber='" + getMaskedCardNumber() + '\'' +
                ", accountId=" + accountId +
                ", cvvCode='***'" +
                ", embossedName='" + embossedName + '\'' +
                ", expirationDate=" + expirationDate +
                ", activeStatus='" + activeStatus + '\'' +
                ", statusDisplayName='" + getStatusDisplayName() + '\'' +
                '}';
    }
}
