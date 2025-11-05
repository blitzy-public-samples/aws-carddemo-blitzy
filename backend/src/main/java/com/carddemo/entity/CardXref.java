package com.carddemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA Entity representing the Card Cross-Reference table.
 * 
 * <p>This entity is transformed from COBOL copybook CVACT03Y.cpy (CARD-XREF-RECORD)
 * which defines a 50-byte record structure for card cross-reference relationships stored
 * in the VSAM CXACAIX alternate index file. This entity establishes comprehensive
 * cross-reference relationships between cards, customers, and accounts, enabling efficient
 * multi-dimensional queries for card management operations.</p>
 * 
 * <p><strong>Purpose and Functional Equivalence:</strong></p>
 * <ul>
 *   <li>Replaces VSAM CXACAIX alternate index file for card relationship lookups</li>
 *   <li>Enables finding all cards for a given customer (customer-to-cards navigation)</li>
 *   <li>Enables finding all cards for a given account (account-to-cards navigation)</li>
 *   <li>Enables finding customer and account for a given card (card-to-relationship navigation)</li>
 *   <li>Supports card list views in COCRDLIC and COCRDSLC program migrations</li>
 *   <li>Essential for card authorization workflows and account management operations</li>
 * </ul>
 * 
 * <p><strong>Composite Primary Key Strategy (Section 0.9):</strong></p>
 * <ul>
 *   <li>Uses @EmbeddedId for composite primary key containing three fields</li>
 *   <li>Card number (16 characters) + Customer ID (9 digits) + Account ID (11 digits)</li>
 *   <li>Ensures unique card-customer-account relationship combinations</li>
 *   <li>Prevents duplicate cross-reference entries at database level</li>
 * </ul>
 * 
 * <p><strong>Foreign Key Relationships (Section 0.9 Referential Integrity):</strong></p>
 * <ul>
 *   <li>@ManyToOne to Card entity via cardNumber field</li>
 *   <li>@ManyToOne to Customer entity via customerId field</li>
 *   <li>@ManyToOne to Account entity via accountId field</li>
 *   <li>All relationships use LAZY fetch for performance optimization</li>
 *   <li>CASCADE delete ensures orphaned xref records are removed</li>
 *   <li>insertable=false, updatable=false on @JoinColumn to prevent mapping conflicts</li>
 * </ul>
 * 
 * <p><strong>Data Transformation Details (COBOL to Java):</strong></p>
 * <ul>
 *   <li>XREF-CARD-NUM PIC X(16) → String cardNumber (16 chars, PK component)</li>
 *   <li>XREF-CUST-ID PIC 9(09) → Long customerId (9 digits, PK component)</li>
 *   <li>XREF-ACCT-ID PIC 9(11) → Long accountId (11 digits, PK component)</li>
 *   <li>FILLER PIC X(14) → NOT MAPPED (unused COBOL padding)</li>
 * </ul>
 * 
 * <p><strong>VSAM to PostgreSQL Migration Pattern:</strong></p>
 * <pre>
 * COBOL VSAM CXACAIX Access:
 *   EXEC CICS START
 *        FILE('CXACAIX')
 *        RIDFLD(CUSTOMER-ID)
 *        GTEQ
 *   END-EXEC.
 *   EXEC CICS READNEXT
 *        FILE('CXACAIX')
 *        INTO(CARD-XREF-RECORD)
 *   END-EXEC.
 * 
 * Java Spring Data JPA Equivalent:
 *   List&lt;CardXref&gt; cardXrefs = cardXrefRepository.findByCustomerId(customerId);
 *   // Or with JOIN:
 *   SELECT c.* FROM card c
 *   JOIN card_xref x ON c.card_number = x.card_number
 *   WHERE x.customer_id = ? AND x.account_id = ?
 * </pre>
 * 
 * <p><strong>Usage in COBOL Program Migrations:</strong></p>
 * <ul>
 *   <li>COCRDLIC.cbl → CardListService (display cards for customer with pagination)</li>
 *   <li>COCRDSLC.cbl → CardDetailService (view card details with account context)</li>
 *   <li>COCRDUPC.cbl → CardUpdateService (update card with relationship validation)</li>
 *   <li>CBCRD01C.cbl → CardDataLoadJob (batch load with cross-reference integrity)</li>
 * </ul>
 * 
 * <p><strong>Database Indexes for Performance (Section 0.2):</strong></p>
 * <ul>
 *   <li>Primary key index: (card_number, customer_id, account_id)</li>
 *   <li>Secondary index on customer_id for customer-to-cards queries</li>
 *   <li>Secondary index on account_id for account-to-cards queries</li>
 *   <li>Secondary index on card_number for card-to-relationship queries</li>
 *   <li>Ensures sub-200ms response times per Section 0.2 requirements</li>
 * </ul>
 * 
 * <p><strong>Data Integrity Constraints (CRITICAL per Section 0.9):</strong></p>
 * <ul>
 *   <li>Primary key: (card_number, customer_id, account_id) ensures uniqueness</li>
 *   <li>Foreign key: card_number REFERENCES card(card_number) ON DELETE CASCADE</li>
 *   <li>Foreign key: customer_id REFERENCES customer(customer_id) ON DELETE CASCADE</li>
 *   <li>Foreign key: account_id REFERENCES account(account_id) ON DELETE CASCADE</li>
 *   <li>Referential integrity enforced at database level, not just application level</li>
 * </ul>
 * 
 * <p><strong>COBOL Source:</strong> app/cpy/CVACT03Y.cpy</p>
 * <p><strong>VSAM File:</strong> CXACAIX alternate index file</p>
 * <p><strong>Record Length:</strong> 50 bytes</p>
 * 
 * @see Card
 * @see Customer
 * @see Account
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.3">VSAM File to PostgreSQL Table Mapping</a>
 * @see <a href="Section 0.9">Cross-Reference Data Relationships Preservation</a>
 */
@Entity
@Table(name = "card_xref")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardXref implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key containing card number, customer ID, and account ID.
     * 
     * <p>This embedded ID implements the three-field composite key strategy required
     * to uniquely identify each card-customer-account relationship. The composite key
     * ensures that the same combination of card, customer, and account cannot be
     * inserted twice, maintaining data integrity at the database level.</p>
     * 
     * <p><strong>Components:</strong></p>
     * <ul>
     *   <li>cardNumber: 16-character card number (PK component 1)</li>
     *   <li>customerId: 9-digit customer identifier (PK component 2)</li>
     *   <li>accountId: 11-digit account identifier (PK component 3)</li>
     * </ul>
     * 
     * <p>The CardXrefId class implements Serializable and provides proper equals()
     * and hashCode() implementations using Objects utility methods for null-safe
     * comparisons, as required by JPA specification for composite primary keys.</p>
     */
    @EmbeddedId
    private CardXrefId id;

    /**
     * Parent card relationship (many-to-one).
     * 
     * <p>Establishes foreign key relationship from CardXref to Card entity via cardNumber.
     * This relationship enables navigation from the cross-reference entry to the full
     * card details including CVV, expiration date, embossed name, and active status.</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers card entity retrieval
     * until explicitly accessed via card.getCard(), optimizing query performance and
     * reducing memory footprint. This maintains sub-200ms response times per Section 0.2
     * performance requirements under 10,000 TPS load.</p>
     * 
     * <p><strong>JoinColumn Configuration:</strong></p>
     * <ul>
     *   <li>insertable = false, updatable = false: Prevents JPA from managing this side
     *       of the relationship (id.cardNumber field is the owning side)</li>
     *   <li>Avoids duplicate column mapping conflicts with embedded ID field</li>
     *   <li>id.cardNumber remains the single source of truth for foreign key value</li>
     * </ul>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues when CardXref entities are serialized in REST API responses, avoiding
     * infinite recursion between CardXref and Card entities in bidirectional relationships.</p>
     * 
     * <p>Usage: Access card details from cross-reference via cardXref.getCard() for
     * display in card list views (COCRDLIC migration) and card detail screens (COCRDSLC).</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_number", insertable = false, updatable = false)
    @JsonIgnore
    private Card card;

    /**
     * Parent customer relationship (many-to-one).
     * 
     * <p>Establishes foreign key relationship from CardXref to Customer entity via customerId.
     * This relationship enables navigation from the cross-reference entry to the full
     * customer profile including name, address, contact information, and account relationships.</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers customer entity retrieval
     * until explicitly accessed via cardXref.getCustomer(), optimizing query performance
     * especially when retrieving multiple cross-reference records for card listings.</p>
     * 
     * <p><strong>JoinColumn Configuration:</strong></p>
     * <ul>
     *   <li>insertable = false, updatable = false: Prevents JPA from managing this side
     *       of the relationship (id.customerId field is the owning side)</li>
     *   <li>Avoids duplicate column mapping conflicts with embedded ID field</li>
     *   <li>id.customerId remains the single source of truth for foreign key value</li>
     * </ul>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues during JSON serialization, breaking potential loops between CardXref and
     * Customer entities in REST API responses.</p>
     * 
     * <p>Usage: Access customer information from cross-reference for authorization workflows,
     * customer service inquiries, and card authorization validations per COBOL programs
     * COCRDLIC and COCRDUPC migration requirements.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false)
    @JsonIgnore
    private Customer customer;

    /**
     * Parent account relationship (many-to-one).
     * 
     * <p>Establishes foreign key relationship from CardXref to Account entity via accountId.
     * This relationship enables navigation from the cross-reference entry to the full
     * account details including balance, credit limit, and transaction history.</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers account entity retrieval
     * until explicitly accessed via cardXref.getAccount(), optimizing performance for
     * cross-reference queries that don't require full account data.</p>
     * 
     * <p><strong>JoinColumn Configuration:</strong></p>
     * <ul>
     *   <li>insertable = false, updatable = false: Prevents JPA from managing this side
     *       of the relationship (id.accountId field is the owning side)</li>
     *   <li>Avoids duplicate column mapping conflicts with embedded ID field</li>
     *   <li>id.accountId remains the single source of truth for foreign key value</li>
     * </ul>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues during JSON serialization in REST API responses, breaking bidirectional
     * relationship loops between CardXref and Account entities.</p>
     * 
     * <p>Usage: Access account balance and credit limit from cross-reference for transaction
     * authorization decisions, card usage validation, and credit limit checks per Section 0.2
     * transaction processing requirements (sub-200ms authorization).</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    @JsonIgnore
    private Account account;

    /**
     * Composite Primary Key class for CardXref entity.
     * 
     * <p>This embedded ID class implements the three-field composite primary key strategy
     * required by JPA specification for entities with multi-column primary keys. It contains
     * the three identifying fields that together uniquely identify a card-customer-account
     * relationship: card number (16 characters), customer ID (9 digits), and account ID
     * (11 digits).</p>
     * 
     * <p><strong>JPA Requirements for Composite Keys:</strong></p>
     * <ul>
     *   <li>Must be public static class nested within entity class</li>
     *   <li>Must implement Serializable for entity identity management</li>
     *   <li>Must provide no-arg constructor for JPA instantiation</li>
     *   <li>Must override equals() and hashCode() for proper entity identity semantics</li>
     *   <li>All fields must be annotated with @Column for column mapping</li>
     * </ul>
     * 
     * <p><strong>Serializable Requirement:</strong></p>
     * <p>Serializable implementation is REQUIRED by JPA specification for composite key
     * classes to support entity identity management, distributed caching (Redis session
     * management per Section 0.5), and remote method invocation. This enables CardXref
     * entities to be stored in Redis-backed HTTP sessions and passed across service
     * boundaries.</p>
     * 
     * <p><strong>Equality Semantics:</strong></p>
     * <p>The equals() and hashCode() methods use Objects.equals() and Objects.hash()
     * utility methods for null-safe comparisons and hash code generation. This ensures
     * correct behavior when CardXref entities are stored in HashMaps, HashSets, or used
     * as keys in collections, which is critical for JPA entity identity and caching.</p>
     * 
     * <p><strong>COBOL Mapping:</strong></p>
     * <ul>
     *   <li>XREF-CARD-NUM PIC X(16) → cardNumber (String, 16 chars)</li>
     *   <li>XREF-CUST-ID PIC 9(09) → customerId (Long, 9 digits)</li>
     *   <li>XREF-ACCT-ID PIC 9(11) → accountId (Long, 11 digits)</li>
     * </ul>
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CardXrefId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Card number component of composite primary key (16 characters).
         * Maps to COBOL field: XREF-CARD-NUM PIC X(16)
         * 
         * <p>Stored as String to preserve leading zeros and maintain exact format matching
         * Card entity primary key field. Card numbers are not used for arithmetic operations,
         * so String type is appropriate and prevents numeric conversion issues.</p>
         * 
         * <p>This field serves dual purpose:</p>
         * <ul>
         *   <li>Component of the composite primary key for CardXref entity</li>
         *   <li>Foreign key reference to Card entity (card_number column)</li>
         * </ul>
         * 
         * <p>Format: 16 consecutive digits (e.g., "4532123456789012")</p>
         * <p>PCI-DSS Note: Card numbers in logs must be masked to show only last 4 digits</p>
         */
        @Column(name = "card_number", length = 16, nullable = false)
        private String cardNumber;

        /**
         * Customer ID component of composite primary key (9 digits).
         * Maps to COBOL field: XREF-CUST-ID PIC 9(09)
         * 
         * <p>Stored as Long to accommodate 9-digit numeric identifier (up to 999,999,999).
         * Long type chosen over Integer because Integer max value (2,147,483,647) provides
         * insufficient range for 9-digit customer identifiers in large customer bases.</p>
         * 
         * <p>This field serves dual purpose:</p>
         * <ul>
         *   <li>Component of the composite primary key for CardXref entity</li>
         *   <li>Foreign key reference to Customer entity (customer_id column)</li>
         * </ul>
         * 
         * <p>COBOL PIC 9(09) directly maps to Java Long without data loss or conversion issues</p>
         */
        @Column(name = "customer_id", nullable = false)
        private Long customerId;

        /**
         * Account ID component of composite primary key (11 digits).
         * Maps to COBOL field: XREF-ACCT-ID PIC 9(11)
         * 
         * <p>Stored as Long to accommodate 11-digit numeric identifier (up to 99,999,999,999).
         * Long type is REQUIRED because 11-digit values exceed Integer max value range.
         * Maximum Long value (9,223,372,036,854,775,807) easily accommodates 11-digit
         * account identifiers.</p>
         * 
         * <p>This field serves dual purpose:</p>
         * <ul>
         *   <li>Component of the composite primary key for CardXref entity</li>
         *   <li>Foreign key reference to Account entity (account_id column)</li>
         * </ul>
         * 
         * <p>COBOL PIC 9(11) directly maps to Java Long without data loss per Section 0.3
         * data type conversion rules</p>
         */
        @Column(name = "account_id", nullable = false)
        private Long accountId;

        /**
         * Compares this composite key with another object for equality.
         * 
         * <p>Required by JPA specification for composite primary key classes to enable
         * proper entity identity management. Two CardXrefId instances are considered equal
         * if and only if all three component fields (cardNumber, customerId, accountId)
         * are equal using null-safe comparison.</p>
         * 
         * <p><strong>JPA Entity Identity Requirement:</strong></p>
         * <p>JPA uses equals() to determine if two entity instances represent the same
         * database row. Correct equals() implementation is CRITICAL for:</p>
         * <ul>
         *   <li>Entity caching in persistence context (first-level cache)</li>
         *   <li>Entity identity comparison in collections (Set, Map)</li>
         *   <li>Merge operation behavior during entity updates</li>
         *   <li>Detached entity reattachment to persistence context</li>
         * </ul>
         * 
         * <p><strong>Null Safety:</strong></p>
         * <p>Uses Objects.equals() for null-safe field comparison, preventing
         * NullPointerException if any field is null. This is essential for defensive
         * programming in entity equality checks.</p>
         * 
         * <p><strong>Implementation Pattern:</strong></p>
         * <pre>
         * 1. Check if objects are same instance (this == o) - performance optimization
         * 2. Check if other object is null or different class - type safety
         * 3. Compare all fields using Objects.equals() - null-safe equality
         * </pre>
         * 
         * @param o Object to compare with this CardXrefId for equality
         * @return true if objects are equal (all three fields match), false otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CardXrefId that = (CardXrefId) o;
            return Objects.equals(cardNumber, that.cardNumber) &&
                   Objects.equals(customerId, that.customerId) &&
                   Objects.equals(accountId, that.accountId);
        }

        /**
         * Generates hash code for this composite key.
         * 
         * <p>Required by JPA specification for composite primary key classes to enable
         * proper entity storage in hash-based collections (HashMap, HashSet) and
         * first-level cache identity management.</p>
         * 
         * <p><strong>Hash Code Contract (per Object.hashCode() specification):</strong></p>
         * <ul>
         *   <li>If equals() returns true, hashCode() MUST return same value for both objects</li>
         *   <li>Multiple invocations of hashCode() must return same value (consistency)</li>
         *   <li>Equal objects MUST have equal hash codes (equals → same hashCode)</li>
         *   <li>Unequal objects SHOULD have different hash codes (performance optimization)</li>
         * </ul>
         * 
         * <p><strong>JPA Cache and Collection Requirements:</strong></p>
         * <p>JPA persistence context (first-level cache) stores entities in HashMap using
         * entity ID as key. Correct hashCode() implementation is CRITICAL for:</p>
         * <ul>
         *   <li>Efficient entity lookup in persistence context cache</li>
         *   <li>Entity storage in HashSet collections without duplicates</li>
         *   <li>Entity identity comparison in JPA merge operations</li>
         *   <li>Performance of entity equality checks in large collections</li>
         * </ul>
         * 
         * <p><strong>Null Safety:</strong></p>
         * <p>Uses Objects.hash() utility method which handles null values gracefully,
         * returning consistent hash codes even when fields are null. This prevents
         * NullPointerException during hash code calculation.</p>
         * 
         * @return Hash code computed from all three fields (cardNumber, customerId, accountId)
         */
        @Override
        public int hashCode() {
            return Objects.hash(cardNumber, customerId, accountId);
        }
    }

    /**
     * Gets the card number from the composite primary key.
     * 
     * <p>Convenience method to access card number component without navigating through
     * the embedded ID. This simplifies code that needs to extract the card number for
     * display, logging, or query construction.</p>
     * 
     * <p>Usage Example:</p>
     * <pre>
     * CardXref xref = cardXrefRepository.findById(cardXrefId).orElseThrow();
     * String cardNumber = xref.getCardNumber(); // Direct access without xref.getId().getCardNumber()
     * </pre>
     * 
     * @return 16-character card number from composite primary key, or null if id is null
     */
    public String getCardNumber() {
        return id != null ? id.getCardNumber() : null;
    }

    /**
     * Gets the customer ID from the composite primary key.
     * 
     * <p>Convenience method to access customer ID component without navigating through
     * the embedded ID. Simplifies service layer code that needs the customer ID for
     * query construction or business logic validation.</p>
     * 
     * <p>Usage Example:</p>
     * <pre>
     * CardXref xref = cardXrefRepository.findById(cardXrefId).orElseThrow();
     * Long customerId = xref.getCustomerId(); // Direct access
     * Customer customer = customerRepository.findById(customerId).orElseThrow();
     * </pre>
     * 
     * @return 9-digit customer identifier from composite primary key, or null if id is null
     */
    public Long getCustomerId() {
        return id != null ? id.getCustomerId() : null;
    }

    /**
     * Gets the account ID from the composite primary key.
     * 
     * <p>Convenience method to access account ID component without navigating through
     * the embedded ID. Simplifies transaction authorization code that needs the account
     * ID for balance verification and credit limit checks.</p>
     * 
     * <p>Usage Example:</p>
     * <pre>
     * CardXref xref = cardXrefRepository.findById(cardXrefId).orElseThrow();
     * Long accountId = xref.getAccountId(); // Direct access
     * Account account = accountRepository.findById(accountId).orElseThrow();
     * BigDecimal availableCredit = account.getAvailableCredit();
     * </pre>
     * 
     * @return 11-digit account identifier from composite primary key, or null if id is null
     */
    public Long getAccountId() {
        return id != null ? id.getAccountId() : null;
    }

    /**
     * Custom toString implementation for debugging and logging.
     * 
     * <p>Provides human-readable representation of CardXref entity showing the three
     * composite key components (card number, customer ID, account ID) without exposing
     * full parent entity details that could cause lazy loading issues or circular
     * reference problems.</p>
     * 
     * <p><strong>PCI-DSS Compliance Note:</strong></p>
     * <p>For production logging, consider masking the card number to show only last 4
     * digits. Current implementation shows full card number for debugging purposes.
     * In production code, replace with:</p>
     * <pre>
     * String maskedCardNumber = getMaskedCardNumber(); // "**** **** **** 1234"
     * </pre>
     * 
     * <p><strong>Lazy Loading Safety:</strong></p>
     * <p>This toString() does NOT access the card, customer, or account relationship
     * fields to prevent accidental lazy loading during logging operations. Accessing
     * these fields during logging could trigger database queries, impacting performance
     * and potentially causing LazyInitializationException if entity is detached.</p>
     * 
     * <p>Usage: Application logging, debugging output, exception messages</p>
     * 
     * @return String representation showing composite key values without parent entities
     */
    @Override
    public String toString() {
        return "CardXref{" +
                "cardNumber='" + getCardNumber() + '\'' +
                ", customerId=" + getCustomerId() +
                ", accountId=" + getAccountId() +
                '}';
    }
}
