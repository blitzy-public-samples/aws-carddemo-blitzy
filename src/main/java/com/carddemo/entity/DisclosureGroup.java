package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * JPA entity representing disclosure group reference data.
 * 
 * Transformed from COBOL copybook CVTRA02Y.cpy (DIS-GROUP-RECORD).
 * Maps to PostgreSQL disclosure_group table with composite primary key.
 * 
 * Disclosure groups define interest rates for different combinations of
 * account groups, transaction types, and transaction categories.
 * 
 * Original COBOL structure:
 * <pre>
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *        10 DIS-ACCT-GROUP-ID       PIC X(10).
 *        10 DIS-TRAN-TYPE-CD        PIC X(02).
 *        10 DIS-TRAN-CAT-CD         PIC 9(04).
 *     05  DIS-INT-RATE              PIC S9(04)V99.
 * </pre>
 * 
 * This entity uses a composite primary key (DisclosureGroupId) consisting of:
 * - Account Group ID (10 characters)
 * - Transaction Type Code (2 characters)
 * - Transaction Category Code (4 digits)
 * 
 * The interest rate field preserves COBOL COMP-3 decimal precision using
 * BigDecimal with scale=2 for exact financial calculations per Agent Action
 * Plan section 0.10 requirement 7.
 * 
 * @see DisclosureGroupId
 */
@Entity
@Table(name = "disclosure_group")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DisclosureGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key containing account group ID, transaction type code,
     * and transaction category code.
     * 
     * Transformed from COBOL DIS-GROUP-KEY structure.
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Interest rate for this disclosure group.
     * 
     * Transformed from COBOL field: DIS-INT-RATE PIC S9(04)V99
     * Precision: 6 total digits (4 integer + 2 decimal)
     * Scale: 2 decimal places
     * 
     * Uses BigDecimal to preserve exact COBOL COMP-3 packed decimal precision.
     * All interest rate calculations must use BigDecimal arithmetic to maintain
     * identical precision to the original mainframe implementation.
     */
    @Column(name = "interest_rate", precision = 6, scale = 2, nullable = false)
    private BigDecimal interestRate;

    /**
     * Version field for optimistic locking.
     * 
     * Prevents concurrent update conflicts by tracking entity version.
     * JPA automatically increments this value on each update operation.
     * If two transactions attempt to update the same entity simultaneously,
     * the second transaction will fail with an OptimisticLockException.
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Composite primary key class for DisclosureGroup entity.
     * 
     * Contains three components that together uniquely identify a disclosure group:
     * 1. Account Group ID - categorizes accounts by type (e.g., standard, premium)
     * 2. Transaction Type Code - identifies transaction type (e.g., purchase, cash advance)
     * 3. Transaction Category Code - identifies transaction category (e.g., retail, dining)
     * 
     * This class must be Serializable and implement equals() and hashCode()
     * for proper JPA composite key functionality.
     * 
     * Transformed from COBOL structure DIS-GROUP-KEY in CVTRA02Y.cpy.
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DisclosureGroupId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Account group identifier.
         * 
         * Transformed from COBOL field: DIS-ACCT-GROUP-ID PIC X(10)
         * Maximum length: 10 characters
         * 
         * Categorizes accounts into groups for interest rate calculation purposes.
         * Examples: "STANDARD", "PREMIUM", "PLATINUM"
         */
        @Column(name = "account_group_id", length = 10, nullable = false)
        private String accountGroupId;

        /**
         * Transaction type code.
         * 
         * Transformed from COBOL field: DIS-TRAN-TYPE-CD PIC X(02)
         * Maximum length: 2 characters
         * 
         * Identifies the type of transaction for interest rate determination.
         * Examples: "PU" (Purchase), "CA" (Cash Advance), "BT" (Balance Transfer)
         */
        @Column(name = "transaction_type_code", length = 2, nullable = false)
        private String transactionTypeCode;

        /**
         * Transaction category code.
         * 
         * Transformed from COBOL field: DIS-TRAN-CAT-CD PIC 9(04)
         * Format: 4-digit numeric string (e.g., "0001", "0012", "0100")
         * 
         * Identifies the category of transaction for interest rate determination.
         * Stored as String to preserve leading zeros from COBOL PIC 9(04) format.
         * Examples: "0001" (Retail), "0002" (Dining), "0003" (Travel)
         */
        @Column(name = "transaction_category_code", length = 4, nullable = false)
        private String transactionCategoryCode;

        /**
         * Custom equals implementation for composite key comparison.
         * 
         * Required by JPA specification for composite primary keys.
         * Compares all three key fields for equality.
         * 
         * @param o the object to compare
         * @return true if all key fields are equal, false otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DisclosureGroupId that = (DisclosureGroupId) o;
            return accountGroupId != null && accountGroupId.equals(that.accountGroupId) &&
                   transactionTypeCode != null && transactionTypeCode.equals(that.transactionTypeCode) &&
                   transactionCategoryCode != null && transactionCategoryCode.equals(that.transactionCategoryCode);
        }

        /**
         * Custom hashCode implementation for composite key hashing.
         * 
         * Required by JPA specification for composite primary keys.
         * Combines hash codes of all three key fields.
         * 
         * @return combined hash code of all key fields
         */
        @Override
        public int hashCode() {
            int result = accountGroupId != null ? accountGroupId.hashCode() : 0;
            result = 31 * result + (transactionTypeCode != null ? transactionTypeCode.hashCode() : 0);
            result = 31 * result + (transactionCategoryCode != null ? transactionCategoryCode.hashCode() : 0);
            return result;
        }

        /**
         * String representation of the composite key.
         * 
         * Provides human-readable format for logging and debugging.
         * Format: "DisclosureGroupId{accountGroupId='...', transactionTypeCode='...', transactionCategoryCode='...'}"
         * 
         * @return string representation of this composite key
         */
        @Override
        public String toString() {
            return "DisclosureGroupId{" +
                    "accountGroupId='" + accountGroupId + '\'' +
                    ", transactionTypeCode='" + transactionTypeCode + '\'' +
                    ", transactionCategoryCode='" + transactionCategoryCode + '\'' +
                    '}';
        }
    }

    /**
     * Custom equals implementation for entity comparison.
     * 
     * Compares entities based on their composite primary key.
     * Two DisclosureGroup entities are equal if they have the same composite key.
     * 
     * @param o the object to compare
     * @return true if entities have the same composite key, false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DisclosureGroup that = (DisclosureGroup) o;
        return id != null && id.equals(that.id);
    }

    /**
     * Custom hashCode implementation for entity hashing.
     * 
     * Uses the composite primary key's hash code for consistency with equals().
     * 
     * @return hash code based on composite primary key
     */
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

    /**
     * String representation of the entity.
     * 
     * Provides human-readable format for logging and debugging.
     * Includes all entity fields: composite key, interest rate, and version.
     * 
     * @return string representation of this entity
     */
    @Override
    public String toString() {
        return "DisclosureGroup{" +
                "id=" + id +
                ", interestRate=" + interestRate +
                ", version=" + version +
                '}';
    }
}
