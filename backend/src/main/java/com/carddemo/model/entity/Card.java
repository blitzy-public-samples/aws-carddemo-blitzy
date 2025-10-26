package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * JPA entity representing credit card master data.
 * 
 * Converted from COBOL copybook: CVACT02Y.cpy (CARD-RECORD)
 * Original record length: 150 bytes
 * 
 * This entity stores credit card information including card number, account association,
 * cardholder details, expiration date, and status. Contains sensitive PII data (card number)
 * requiring masking per security requirements in transaction logs and API responses.
 * 
 * Conversion notes:
 * - COBOL PIC X(16) CARD-NUM converted to String primary key with length 16
 * - COBOL PIC 9(11) CARD-ACCT-ID converted to Long foreign key with @ManyToOne relationship to Account entity
 * - COBOL PIC 9(03) CARD-CVV-CD INTENTIONALLY OMITTED from entity per PCI-DSS compliance requirements.
 *   CVV codes must never be persisted to database and should only exist in-memory during
 *   transaction authorization processing per Section 0.7.9 security requirements.
 * - COBOL PIC X(50) CARD-EMBOSSED-NAME converted to String with length 50
 * - COBOL PIC X(10) CARD-EXPIRAION-DATE (correcting COBOL typo) converted to LocalDate cardExpirationDate
 * - COBOL PIC X(01) CARD-ACTIVE-STATUS mapped to database column card_status (per Section 0.3.4 schema)
 * - Additional field card_cardmember_id added per database schema (not in original COBOL copybook)
 * - Additional field card_active_date added per database schema (not in original COBOL copybook)
 * - COBOL FILLER field (59 bytes) removed as not used
 * - Added audit fields createdAt and updatedAt for tracking record lifecycle
 * - Added version field for JPA optimistic locking (replicates VSAM RBA locking semantics)
 * 
 * Referenced by:
 * - CardAccountXref entity (card_num is part of composite primary key)
 * - Transaction entity (trans_card_num references card.card_num)
 * - DailyTransaction entity (trans_card_num references card.card_num)
 * 
 * Database indexes:
 * - Primary key index on card_num (automatic)
 * - Index on card_acct_id (idx_card_acct) for account-based queries
 * - Index on card_status (idx_card_status) for filtering by status
 * 
 * @see Account
 * @see CardAccountXref
 * @see Transaction
 * @see DailyTransaction
 */
@Entity
@Table(name = "card", indexes = {
    @Index(name = "idx_card_acct", columnList = "card_acct_id"),
    @Index(name = "idx_card_status", columnList = "card_status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Card {

    /**
     * Card number (primary key).
     * 
     * Converted from: COBOL PIC X(16) CARD-NUM
     * Maximum length: 16 characters
     * Format: Typically 16-digit card number (may include hyphens or spaces in display)
     * 
     * This is the primary key for card records, equivalent to the VSAM KSDS
     * primary key in the mainframe CARDFILE dataset.
     * 
     * SECURITY NOTE: This field contains sensitive PII (Payment Card Number).
     * - Must be masked in logs and API responses (show only last 4 digits)
     * - Must be encrypted at rest per PCI-DSS requirements
     * - Access must be restricted to authorized personnel only
     * - Must not appear in URLs or query parameters
     */
    @Id
    @Column(name = "card_num", nullable = false, length = 16)
    private String cardNum;

    /**
     * Associated account ID (foreign key to Account entity).
     * 
     * Converted from: COBOL PIC 9(11) CARD-ACCT-ID
     * Maximum value: 99,999,999,999 (11 digits)
     * 
     * Links this card to its associated credit card account. Multiple cards
     * can be issued for a single account (e.g., primary cardholder, authorized users).
     * 
     * This foreign key relationship enables navigation from Card to Account entity
     * to access account balance, credit limit, and transaction history.
     */
    @Column(name = "card_acct_id", nullable = false)
    private Long cardAcctId;

    /**
     * Account entity relationship.
     * 
     * Many-to-One relationship: Multiple cards can belong to one account.
     * 
     * Enables JPA to automatically fetch Account details when accessing Card.account
     * and maintains referential integrity through foreign key constraint.
     * 
     * Join column card_acct_id references account.acct_id primary key per
     * database schema defined in Section 0.3.4.
     */
    @ManyToOne
    @JoinColumn(name = "card_acct_id", referencedColumnName = "acct_id", insertable = false, updatable = false)
    private Account account;

    /**
     * Cardholder member identifier.
     * 
     * Added field (not in original COBOL copybook CVACT02Y.cpy).
     * Database schema field: card_cardmember_id BIGINT NOT NULL
     * 
     * Identifies the individual cardholder associated with this card.
     * Typically links to customer master record (CVCUS01Y.cpy / customer table)
     * to distinguish primary cardholder from authorized users.
     * 
     * Note: Original COBOL copybook did not include this field, but database schema
     * in Section 0.3.4 requires it for proper card-to-customer association tracking.
     */
    @Column(name = "card_cardmember_id", nullable = false)
    private Long cardCardmemberId;

    /**
     * Card status indicator.
     * 
     * Converted from: COBOL PIC X(01) CARD-ACTIVE-STATUS
     * Database column: card_status (per Section 0.3.4 schema)
     * Length: 1 character
     * 
     * Valid values:
     * - 'Y' = Active (card can be used for transactions)
     * - 'N' = Inactive (card cannot be used)
     * - 'S' = Stolen (card reported stolen, block all transactions)
     * - 'L' = Lost (card reported lost, block all transactions)
     * - 'E' = Expired (card past expiration date)
     * - 'C' = Closed (card permanently closed)
     * 
     * Used in transaction authorization logic to validate card eligibility
     * before processing purchases or cash advances.
     * 
     * Note: Database schema uses "card_status" not "card_active_status" as
     * the actual column name per Section 0.3.4.
     * 
     * Column Definition: CHAR(1) to preserve COBOL fixed-length character semantics.
     * Per Section 0.7.2: Must maintain exact COBOL PIC X(01) behavior.
     */
    @Column(name = "card_status", nullable = false, columnDefinition = "CHAR(1)")
    private String cardStatus;

    /**
     * Embossed cardholder name.
     * 
     * Converted from: COBOL PIC X(50) CARD-EMBOSSED-NAME
     * Maximum length: 50 characters
     * Format: Usually "FIRSTNAME LASTNAME" or "LASTNAME/FIRSTNAME"
     * 
     * Name embossed on physical card. Used for cardholder verification during
     * in-person transactions and card replacement processing.
     * 
     * May be null for virtual cards or corporate cards with generic embossing.
     * Typically all uppercase per card production standards.
     */
    @Column(name = "card_embossed_name", length = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date.
     * 
     * Converted from: COBOL PIC X(10) CARD-EXPIRAION-DATE
     * (Note: COBOL field name contains typo "EXPIRAION" but mapped correctly to "cardExpirationDate")
     * Format: YYYY-MM-DD in database, typically displayed as MM/YY on physical card
     * 
     * Date when card expires and can no longer be used for transactions.
     * Transaction authorization logic compares transaction date against this field
     * to reject transactions on expired cards.
     * 
     * Batch job CBACT04C.cbl processes card expirations and triggers reissuance
     * workflow for cards nearing expiration (typically 60 days before expiry).
     * 
     * This field is required and must always have a valid future date when card
     * is active. Database schema defines as DATE NOT NULL per Section 0.3.4.
     */
    @Column(name = "card_expiration_date", nullable = false)
    private LocalDate cardExpirationDate;

    /**
     * Card activation date.
     * 
     * Added field (not in original COBOL copybook CVACT02Y.cpy).
     * Database schema field: card_active_date DATE
     * 
     * Date when card was activated by cardholder (e.g., via phone, web, or mobile app).
     * Nullable because newly issued cards are not activated immediately.
     * 
     * Cards must be activated before use. Transaction authorization checks this field
     * to ensure card has been activated before approving transactions.
     * 
     * If null, card is in "issued but not activated" state and transactions will be declined
     * with "Card not activated" response code.
     */
    @Column(name = "card_active_date")
    private LocalDate cardActiveDate;

    /**
     * Record creation timestamp.
     * 
     * Added field (not in original COBOL copybook).
     * 
     * Automatically set to current timestamp when record is first inserted into
     * the database. Used for audit tracking, card issuance reporting, and data lineage.
     * 
     * Tracks when card record was created in PostgreSQL, which corresponds to
     * card issuance date in business terms.
     */
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    /**
     * Record last update timestamp.
     * 
     * Added field (not in original COBOL copybook).
     * 
     * Automatically updated to current timestamp whenever record is modified.
     * Used for audit tracking, cache invalidation, and optimistic locking support.
     * 
     * Tracks changes to card status (activation, closure, lost/stolen reporting),
     * embossed name updates, and expiration date extensions.
     */
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp updatedAt;

    /**
     * Version number for optimistic locking.
     * 
     * Added field (not in original COBOL copybook).
     * 
     * JPA version field automatically incremented on each update to prevent
     * concurrent update conflicts. Replicates COBOL VSAM RBA (Relative Byte Address)
     * optimistic locking semantics from mainframe CICS transaction processing.
     * 
     * When a transaction attempts to update a card record (e.g., change status from 'Y' to 'L'
     * when reporting lost), JPA compares the version number in memory with the database.
     * If they differ, another transaction has modified the record (e.g., card was already
     * reported stolen), and an OptimisticLockException is thrown, requiring the transaction
     * to re-read and retry or abort based on business rules.
     * 
     * Critical for maintaining data integrity in high-concurrency card management operations
     * where multiple channels (web, mobile, call center) may update same card simultaneously.
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private Integer version;

    /**
     * JPA lifecycle callback: Automatically set timestamps before persisting new entity.
     * 
     * This method is invoked automatically by JPA before INSERT operations.
     * Sets both createdAt and updatedAt to current timestamp for new records.
     * 
     * Replaces manual timestamp setting that would be required in COBOL programs
     * (e.g., COSGN00C.cbl, COCRDUPC.cbl) where developers explicitly set
     * CURRENT-DATE or CURRENT-TIMESTAMP fields using ACCEPT statements.
     * 
     * Example COBOL equivalent:
     *   ACCEPT WS-CURRENT-DATE FROM DATE YYYYMMDD
     *   ACCEPT WS-CURRENT-TIME FROM TIME
     *   MOVE WS-CURRENT-TIMESTAMP TO CARD-CREATE-TS
     */
    @PrePersist
    protected void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        this.createdAt = now;
        this.updatedAt = now;
        if (this.version == null) {
            this.version = 0;
        }
    }

    /**
     * JPA lifecycle callback: Automatically update timestamp before updating entity.
     * 
     * This method is invoked automatically by JPA before UPDATE operations.
     * Updates the updatedAt timestamp to current time, leaving createdAt unchanged.
     * 
     * Replaces manual timestamp updating that would be required in COBOL programs
     * during EXEC CICS REWRITE operations (e.g., in COCRDUPC.cbl when updating
     * card status or embossed name).
     * 
     * Example COBOL equivalent:
     *   ACCEPT WS-CURRENT-TIMESTAMP FROM TIME
     *   MOVE WS-CURRENT-TIMESTAMP TO CARD-UPDATE-TS
     *   EXEC CICS REWRITE FILE('CARDFILE') FROM(CARD-RECORD) END-EXEC
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
