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
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA Entity representing the account cross-reference table for customer-to-account relationships.
 * 
 * <p>This entity replaces the VSAM XREF file which stored customer-account relationships as
 * alternate index entries in the mainframe system. In the COBOL/VSAM architecture, the XREF
 * file enabled bidirectional traversal:
 * <ul>
 *   <li>COBOL START/READ NEXT from customer ID to find all associated accounts</li>
 *   <li>COBOL START/READ NEXT from account ID to find all associated customers (joint accounts)</li>
 * </ul>
 * </p>
 * 
 * <p>In the normalized PostgreSQL design, this cross-reference pattern is implemented as a
 * dedicated join table with composite primary key and foreign key constraints, enabling
 * efficient many-to-many Customer-Account relationships per Section 0.3 transformation rules.</p>
 * 
 * <p><strong>Database Design:</strong></p>
 * <ul>
 *   <li>Table name: account_xref</li>
 *   <li>Composite primary key: (customer_id, account_id)</li>
 *   <li>Foreign key: customer_id REFERENCES customer(customer_id) ON DELETE CASCADE</li>
 *   <li>Foreign key: account_id REFERENCES account(account_id) ON DELETE CASCADE</li>
 *   <li>Index on customer_id for customer-to-accounts queries</li>
 *   <li>Index on account_id for account-to-customers queries</li>
 * </ul>
 * 
 * <p><strong>Usage Patterns:</strong></p>
 * <pre>
 * // Find all accounts for a customer (replaces COBOL START XREF-FILE KEY IS CUST-ID)
 * List&lt;AccountXref&gt; customerAccounts = accountXrefRepository.findByCustomerId(customerId);
 * 
 * // Find all customers for an account (joint account scenarios)
 * List&lt;AccountXref&gt; accountCustomers = accountXrefRepository.findByAccountId(accountId);
 * 
 * // Join query to get account details for a customer
 * SELECT a.* FROM account a
 * JOIN account_xref x ON a.account_id = x.account_id
 * WHERE x.customer_id = ?
 * </pre>
 * 
 * <p><strong>Referential Integrity (Section 0.9 Critical Requirement):</strong></p>
 * <p>Cross-reference data relationships preserved with 100% referential integrity through:</p>
 * <ul>
 *   <li>Foreign key constraints enforced at database level</li>
 *   <li>CASCADE delete ensures orphaned xref records are automatically removed</li>
 *   <li>JPA relationship mappings with proper fetch strategies</li>
 *   <li>Service layer validation prevents duplicate relationships</li>
 * </ul>
 * 
 * <p><strong>VSAM Source:</strong> XREF file (alternate index)</p>
 * <p><strong>Target Table:</strong> account_xref (PostgreSQL join table)</p>
 * <p><strong>Migration Context:</strong> Section 0.6 - File-by-File Transformation Plan</p>
 * 
 * @see Customer
 * @see Account
 * @see <a href="Section 0.3">VSAM to PostgreSQL Transformation Rules</a>
 * @see <a href="Section 0.9">Referential Integrity Requirements</a>
 */
@Entity
@Table(name = "account_xref")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountXref implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key containing customer ID and account ID.
     * 
     * <p>JPA requires composite keys to be represented as a separate @Embeddable class
     * that implements Serializable and provides proper equals() and hashCode() implementations.
     * This ensures correct entity identity management and collection behavior.</p>
     * 
     * <p>The composite key guarantees uniqueness constraint: each customer-account pair
     * can exist only once in the cross-reference table, preventing duplicate relationships.</p>
     */
    @EmbeddedId
    private AccountXrefId id;

    /**
     * Many-to-one relationship to Customer entity (read-only navigation property).
     * 
     * <p>This field enables navigation from AccountXref to Customer details without
     * modifying the composite key. The relationship is marked insertable=false and
     * updatable=false because the customer_id value is managed through the composite
     * key's customerId field.</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers Customer entity retrieval
     * until explicitly accessed, optimizing query performance per Section 0.2 requirements
     * (sub-200ms response times under 10,000 TPS).</p>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues when AccountXref entities are serialized in REST API responses.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false)
    @JsonIgnore
    private Customer customer;

    /**
     * Many-to-one relationship to Account entity (read-only navigation property).
     * 
     * <p>This field enables navigation from AccountXref to Account details without
     * modifying the composite key. The relationship is marked insertable=false and
     * updatable=false because the account_id value is managed through the composite
     * key's accountId field.</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers Account entity retrieval
     * until explicitly accessed, optimizing query performance per Section 0.2 requirements.</p>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues when AccountXref entities are serialized in REST API responses.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    @JsonIgnore
    private Account account;

    /**
     * Timestamp when the customer-account relationship was established.
     * 
     * <p>This audit field tracks when the cross-reference entry was created, supporting
     * regulatory compliance audit trail requirements per Section 0.9. In COBOL/VSAM
     * systems, relationship creation timestamps were typically not tracked; this field
     * represents an enhancement for better audit capabilities in the modernized system.</p>
     * 
     * <p>The field is populated automatically when a new AccountXref entity is persisted,
     * typically using @PrePersist JPA lifecycle callback or service layer logic.</p>
     */
    @Column(name = "created_date", nullable = false)
    private LocalDateTime createdDate;

    /**
     * Timestamp when the customer-account relationship was last modified.
     * 
     * <p>This audit field tracks when the cross-reference entry was last updated, supporting
     * regulatory compliance audit trail requirements per Section 0.9. Although cross-reference
     * relationships are typically static (created once and deleted when no longer valid),
     * this field supports scenarios where relationship metadata might be updated.</p>
     * 
     * <p>The field is populated automatically when an AccountXref entity is updated,
     * typically using @PreUpdate JPA lifecycle callback or service layer logic.</p>
     */
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    /**
     * Embeddable composite primary key class for AccountXref entity.
     * 
     * <p>This class represents the compound primary key consisting of customer_id and
     * account_id. JPA requires composite key classes to:</p>
     * <ul>
     *   <li>Be annotated with @Embeddable</li>
     *   <li>Implement Serializable interface</li>
     *   <li>Provide public no-argument constructor</li>
     *   <li>Override equals() and hashCode() methods using all key fields</li>
     * </ul>
     * 
     * <p>The composite key ensures uniqueness constraint: each (customer_id, account_id)
     * pair can exist only once, preventing duplicate cross-reference entries.</p>
     * 
     * <p><strong>VSAM Equivalent:</strong> In COBOL/VSAM, this corresponds to the
     * composite alternate index key on the XREF file.</p>
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountXrefId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Customer identifier component of the composite key (9-digit numeric).
         * 
         * <p>Maps to customer.customer_id foreign key reference. This field is part
         * of the composite primary key and must not be null.</p>
         * 
         * <p><strong>COBOL Source:</strong> XREF-CUST-ID PIC 9(09)</p>
         */
        @Column(name = "customer_id", nullable = false)
        private Long customerId;

        /**
         * Account identifier component of the composite key (11-digit numeric).
         * 
         * <p>Maps to account.account_id foreign key reference. This field is part
         * of the composite primary key and must not be null.</p>
         * 
         * <p><strong>COBOL Source:</strong> XREF-ACCT-ID PIC 9(11)</p>
         */
        @Column(name = "account_id", nullable = false)
        private Long accountId;

        /**
         * Determines equality based on both customerId and accountId.
         * 
         * <p>This method is critical for JPA entity identity management and proper
         * functioning of collections (HashSet, HashMap) containing AccountXref entities.
         * Two AccountXrefId objects are equal if and only if both customerId and
         * accountId fields match.</p>
         * 
         * @param o Object to compare with this AccountXrefId
         * @return true if both customerId and accountId are equal, false otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AccountXrefId that = (AccountXrefId) o;
            return Objects.equals(customerId, that.customerId) &&
                   Objects.equals(accountId, that.accountId);
        }

        /**
         * Generates hash code based on both customerId and accountId.
         * 
         * <p>This method must be consistent with equals() to maintain the general
         * contract for Object.hashCode(). Uses Objects.hash() for null-safe hash
         * code generation combining both key fields.</p>
         * 
         * @return Hash code integer value
         */
        @Override
        public int hashCode() {
            return Objects.hash(customerId, accountId);
        }
    }

    /**
     * Custom toString implementation that provides detailed cross-reference information
     * without exposing the parent Customer and Account relationships (to prevent circular references).
     * 
     * <p>This method is useful for debugging and logging, showing the relationship identifiers
     * and audit timestamps without triggering lazy loading of the associated entities.</p>
     * 
     * @return String representation of AccountXref entity
     */
    @Override
    public String toString() {
        return "AccountXref{" +
                "id=" + id +
                ", createdDate=" + createdDate +
                ", updatedDate=" + updatedDate +
                '}';
    }
}
