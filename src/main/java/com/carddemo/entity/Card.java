package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * JPA Entity representing Card master data.
 * 
 * <p>This entity corresponds to the COBOL CARD-RECORD copybook structure
 * defined in CVACT02Y.cpy with a 150-byte record layout. It represents card
 * information stored in the VSAM KSDS CARDDAT file, now migrated to PostgreSQL
 * card table.</p>
 * 
 * <p>Key Transformations from COBOL:</p>
 * <ul>
 *   <li>CARD-NUM (PIC X(16)) → String cardNumber (Primary Key)</li>
 *   <li>CARD-ACCT-ID (PIC 9(11)) → @ManyToOne Account relationship</li>
 *   <li>CARD-CVV-CD (PIC 9(03)) → String cvvCode</li>
 *   <li>CARD-EMBOSSED-NAME (PIC X(50)) → String embossedName</li>
 *   <li>CARD-EXPIRAION-DATE (PIC X(10)) → LocalDate expirationDate</li>
 *   <li>CARD-ACTIVE-STATUS (PIC X(01)) → String activeStatus</li>
 * </ul>
 * 
 * <p><strong>Cross-Reference Relationships:</strong></p>
 * <p>Based on CVACT03Y.cpy (CARD-XREF-RECORD), cards are linked to accounts
 * through XREF-CARD-NUM to XREF-ACCT-ID mapping. This VSAM cross-reference file
 * relationship is replaced with JPA @ManyToOne relationship to Account entity,
 * establishing a foreign key constraint on account_id field. This enables:</p>
 * <ul>
 *   <li>Multiple cards can belong to a single account</li>
 *   <li>Referential integrity enforced by PostgreSQL foreign key</li>
 *   <li>Cascading operations managed by JPA relationship configuration</li>
 *   <li>Efficient navigation from card to account information</li>
 * </ul>
 * 
 * <p><strong>Primary Key Design:</strong></p>
 * <p>The card_number field (16-character alphanumeric) serves as the primary key
 * matching VSAM KSDS key structure for card master file (CARDDAT). This is a
 * natural key representing the actual card number embossed on the physical card.</p>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <p>The CVV code (Card Verification Value) is stored as a 3-digit string. In
 * production systems, this field should be encrypted at rest and in transit.
 * Additional security measures include:</p>
 * <ul>
 *   <li>Masking card numbers in logs and UI (show only last 4 digits)</li>
 *   <li>Encrypting CVV codes in database using PostgreSQL encryption functions</li>
 *   <li>Implementing PCI-DSS compliance requirements for card data storage</li>
 *   <li>Access control restrictions on CVV field retrieval</li>
 * </ul>
 * 
 * <p><strong>Date Handling:</strong></p>
 * <p>The expiration date field converts from COBOL PIC X(10) format to Java
 * LocalDate for proper date handling and validation. This ensures:</p>
 * <ul>
 *   <li>Timezone-agnostic date storage in PostgreSQL DATE column</li>
 *   <li>Built-in date validation and parsing using Java time API</li>
 *   <li>Easy date arithmetic for expiration checking</li>
 *   <li>Standard ISO format (YYYY-MM-DD) for database storage</li>
 * </ul>
 * 
 * <p><strong>Active Status Values:</strong></p>
 * <p>The active status field is a single character indicator:</p>
 * <ul>
 *   <li>'Y' - Active card, can be used for transactions</li>
 *   <li>'N' - Inactive card, blocked from transactions (lost/stolen/expired)</li>
 * </ul>
 * 
 * <p>The entity includes optimistic locking via the @Version annotation to handle
 * concurrent access patterns that replicate VSAM record locking behavior, ensuring
 * data consistency during concurrent updates (e.g., simultaneous card status updates).</p>
 * 
 * <p>Lombok annotations are used to reduce boilerplate:</p>
 * <ul>
 *   <li>@Data - generates getters, setters, equals, hashCode, toString</li>
 *   <li>@NoArgsConstructor - generates no-args constructor required by JPA</li>
 *   <li>@AllArgsConstructor - generates constructor with all fields</li>
 *   <li>@Builder - generates builder pattern for fluent object construction</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <p>The card_number field is indexed as primary key matching VSAM KSDS key structure.
 * Additional indexes are created in Flyway migration V7__create_indexes.sql to support:</p>
 * <ul>
 *   <li>Efficient queries by account_id (foreign key index)</li>
 *   <li>Card lookup by active status</li>
 *   <li>Expiration date range queries for card renewal processing</li>
 * </ul>
 * 
 * <p><strong>Functional Equivalence:</strong></p>
 * <p>This entity maintains complete functional equivalence with the original COBOL
 * CARD-RECORD structure, preserving all field mappings, data types, and business
 * logic constraints. All COBOL programs accessing CARDDAT file (COCRDLIC.cbl,
 * COCRDSLC.cbl, COCRDUPC.cbl) will use this entity through JPA repositories.</p>
 * 
 * @see Account
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cpy/CVACT02Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - Preserve All Existing Functionality</a>
 */
@Entity
@Table(name = "card")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Card implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Card Number - Primary Key
     * 
     * <p>Maps from COBOL CARD-NUM field (PIC X(16)).</p>
     * <p>16-character alphanumeric card number uniquely identifying each card.</p>
     * <p>This field serves as the primary key matching VSAM KSDS key structure
     * for card master file (CARDDAT).</p>
     * <p>Format typically follows industry standards (e.g., 16 digits for major
     * card networks like Visa, Mastercard).</p>
     * 
     * <p><strong>Security Note:</strong> In production, this field should be
     * masked in logs and UI displays, showing only the last 4 digits
     * (e.g., "************1234").</p>
     */
    @Id
    @Column(name = "card_number", nullable = false, length = 16)
    private String cardNumber;

    /**
     * Account - Foreign Key Relationship
     * 
     * <p>ManyToOne relationship to Account entity based on cross-reference
     * file CVACT03Y.cpy (CARD-XREF-RECORD). Multiple cards can belong to
     * a single account (XREF-CARD-NUM to XREF-ACCT-ID mapping).</p>
     * <p>Replaces VSAM cross-reference file relationship with PostgreSQL
     * foreign key constraint, ensuring referential integrity.</p>
     * <p>Maps from COBOL CARD-ACCT-ID field (PIC 9(11)).</p>
     * 
     * <p>This relationship enables:</p>
     * <ul>
     *   <li>Account information retrieval for card operations</li>
     *   <li>Card listing by account (e.g., display all cards for an account)</li>
     *   <li>Balance and credit limit checking during transaction authorization</li>
     *   <li>Account-level operations affecting all associated cards</li>
     * </ul>
     * 
     * <p><strong>Cardinality:</strong> Many cards can belong to one account
     * (e.g., primary card, additional cards for authorized users).</p>
     */
    @ManyToOne
    @JoinColumn(name = "account_id", referencedColumnName = "account_id", nullable = false)
    private Account account;

    /**
     * Card Verification Value (CVV) Code
     * 
     * <p>Maps from COBOL CARD-CVV-CD field (PIC 9(03)).</p>
     * <p>3-digit security code printed on the back of the card (or front for
     * American Express). Used for card-not-present transactions to verify
     * physical card possession.</p>
     * <p>Stored as String to preserve leading zeros (e.g., "007").</p>
     * 
     * <p><strong>Security Requirements:</strong></p>
     * <ul>
     *   <li>Should be encrypted at rest in production databases</li>
     *   <li>Never logged or displayed in plain text</li>
     *   <li>Restricted access in application code</li>
     *   <li>PCI-DSS compliance required for storage and handling</li>
     * </ul>
     * 
     * <p><strong>Validation:</strong> Must be exactly 3 digits (000-999).</p>
     */
    @Column(name = "cvv_code", length = 3)
    private String cvvCode;

    /**
     * Embossed Name
     * 
     * <p>Maps from COBOL CARD-EMBOSSED-NAME field (PIC X(50)).</p>
     * <p>Cardholder name as embossed on the physical card, maximum 50 characters.</p>
     * <p>Typically formatted as "FIRSTNAME LASTNAME" in uppercase to match
     * physical card embossing standards.</p>
     * 
     * <p>This field may differ from the customer's legal name in the customer
     * record due to:</p>
     * <ul>
     *   <li>Length limitations on physical card embossing</li>
     *   <li>Character set restrictions (alphanumeric only)</li>
     *   <li>Authorized user cards with different names</li>
     * </ul>
     */
    @Column(name = "embossed_name", length = 50)
    private String embossedName;

    /**
     * Card Expiration Date
     * 
     * <p>Maps from COBOL CARD-EXPIRAION-DATE field (PIC X(10)).</p>
     * <p>Date when the card expires and can no longer be used for transactions,
     * stored in ISO format (YYYY-MM-DD). Uses LocalDate for timezone-agnostic
     * date storage.</p>
     * 
     * <p><strong>Note:</strong> Original COBOL field name has typo "EXPIRAION"
     * preserved in documentation for traceability.</p>
     * 
     * <p><strong>Business Rules:</strong></p>
     * <ul>
     *   <li>Cards typically expire 2-5 years after issuance</li>
     *   <li>Transactions are declined on or after expiration date</li>
     *   <li>Renewal cards are issued 30-60 days before expiration</li>
     *   <li>Batch job (CBACT02C.cbl) processes expiring cards for renewal</li>
     * </ul>
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * Card Active Status
     * 
     * <p>Maps from COBOL CARD-ACTIVE-STATUS field (PIC X(01)).</p>
     * <p>Single character indicator for card status:</p>
     * <ul>
     *   <li>'Y' - Active card, authorized for transactions</li>
     *   <li>'N' - Inactive card, blocked from transactions</li>
     * </ul>
     * 
     * <p><strong>Reasons for Inactive Status:</strong></p>
     * <ul>
     *   <li>Card reported lost or stolen</li>
     *   <li>Card expired and not yet renewed</li>
     *   <li>Account closed or suspended</li>
     *   <li>Fraud detection triggered card block</li>
     *   <li>Cardholder requested card deactivation</li>
     * </ul>
     * 
     * <p><strong>Transaction Processing:</strong> All transaction authorization
     * requests (COTRN02C.cbl) must check this status before approving charges.</p>
     */
    @Column(name = "active_status", length = 1)
    private String activeStatus;

    /**
     * Card Type
     * 
     * <p>Enhanced field for modernized card system (not present in original COBOL).</p>
     * <p>2-character code indicating the type of card:</p>
     * <ul>
     *   <li>'DC' - Debit Card</li>
     *   <li>'CC' - Credit Card</li>
     * </ul>
     * 
     * <p><strong>Business Rules:</strong></p>
     * <ul>
     *   <li>Debit cards draw from available balance only</li>
     *   <li>Credit cards allow charges up to credit limit</li>
     *   <li>Transaction authorization logic varies by card type</li>
     * </ul>
     * 
     * <p>This field is required for proper transaction processing in the
     * modernized cloud-native system as specified in Agent Action Plan section 0.6.</p>
     */
    @Column(name = "card_type", length = 2)
    private String cardType;

    /**
     * Card Open Date
     * 
     * <p>Enhanced field for modernized card system (not present in original COBOL).</p>
     * <p>Date when the card was first issued and activated for use.</p>
     * <p>Used for audit trail, card lifecycle tracking, and analytics.</p>
     * 
     * <p><strong>Business Use Cases:</strong></p>
     * <ul>
     *   <li>Calculate card age for reissuance planning</li>
     *   <li>Track time from card issuance to first transaction</li>
     *   <li>Support regulatory compliance and audit requirements</li>
     *   <li>Generate card lifecycle reports</li>
     * </ul>
     * 
     * <p>This field is required as specified in Agent Action Plan section 0.6
     * for enhanced audit and tracking capabilities in the cloud-native system.</p>
     */
    @Column(name = "open_date")
    private LocalDate openDate;

    /**
     * Last Used Date
     * 
     * <p>Enhanced field for modernized card system (not present in original COBOL).</p>
     * <p>Date when the card was last used for a transaction. Null if card has
     * never been used.</p>
     * <p>Used for dormant card identification, fraud detection, and analytics.</p>
     * 
     * <p><strong>Business Use Cases:</strong></p>
     * <ul>
     *   <li>Identify inactive/dormant cards for automatic deactivation</li>
     *   <li>Fraud detection - unusual activity after long dormancy</li>
     *   <li>Customer engagement - reactivate dormant cardholders</li>
     *   <li>Regulatory compliance for inactive account monitoring</li>
     * </ul>
     * 
     * <p><strong>Update Pattern:</strong> This field is updated by transaction
     * processing batch jobs whenever a card is used for a purchase, withdrawal,
     * or other transaction.</p>
     * 
     * <p>This field is optional (nullable) as specified in Agent Action Plan
     * section 0.6, since newly issued cards may not have been used yet.</p>
     */
    @Column(name = "last_used_date")
    private LocalDate lastUsedDate;

    /**
     * Version - Optimistic Locking
     * 
     * <p>JPA version field for optimistic locking support, replicating VSAM
     * record locking behavior for concurrent access patterns.</p>
     * <p>Automatically incremented by JPA on each update, preventing lost
     * updates when multiple users attempt to modify the same card
     * simultaneously.</p>
     * 
     * <p><strong>Concurrent Update Scenarios:</strong></p>
     * <ul>
     *   <li>Simultaneous card status updates (e.g., activation and block)</li>
     *   <li>CVV code changes during card reissue</li>
     *   <li>Expiration date updates from batch renewal jobs</li>
     * </ul>
     * 
     * <p>If a concurrent modification is detected, JPA throws
     * OptimisticLockException, allowing the application to handle the
     * conflict appropriately (e.g., refresh and retry with user notification).</p>
     */
    @Version
    @Column(name = "version")
    private Long version;
}
