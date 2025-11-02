package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * JPA Entity representing account disclosure group reference data for interest rate management.
 * 
 * Transformed from COBOL copybook CVTRA02Y.cpy (DIS-GROUP-RECORD).
 * 
 * This entity maps account groups to interest rates based on transaction type and category,
 * enabling differentiated interest rate application during batch processing and account management.
 * 
 * Original COBOL structure:
 * <pre>
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *        10 DIS-ACCT-GROUP-ID         PIC X(10).
 *        10 DIS-TRAN-TYPE-CD          PIC X(02).
 *        10 DIS-TRAN-CAT-CD           PIC 9(04).
 *     05  DIS-INT-RATE                PIC S9(04)V99.
 * </pre>
 * 
 * Key characteristics:
 * - Uses composite primary key (account group ID, transaction type, transaction category)
 * - Interest rate stored as BigDecimal with precision 6, scale 2 matching COBOL S9(04)V99
 * - Reference data table loaded via Flyway migration V9__load_reference_data.sql
 * - Used by InterestCalculationJob batch processing for account interest calculations
 * 
 * @see com.carddemo.batch.job.InterestCalculationJob
 */
@Entity
@Table(name = "account_group")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key containing account group ID, transaction type code,
     * and transaction category code.
     */
    @EmbeddedId
    private GroupId id;

    /**
     * Interest rate applicable to this account group and transaction type/category combination.
     * 
     * Precision and scale match COBOL PIC S9(04)V99:
     * - Precision: 6 (4 integer digits + 2 decimal digits)
     * - Scale: 2 (2 decimal places)
     * - Rounding: HALF_UP (matches COBOL COMP-3 rounding semantics)
     * 
     * CRITICAL: All arithmetic operations must explicitly set scale and rounding mode
     * to maintain COBOL COMP-3 precision equivalence per Section 0.9 requirements.
     * 
     * Example usage:
     * <pre>
     * BigDecimal rate = accountGroup.getInterestRate();
     * BigDecimal interest = balance.multiply(rate)
     *                              .setScale(2, RoundingMode.HALF_UP);
     * </pre>
     */
    @Column(name = "interest_rate", precision = 6, scale = 2, nullable = false)
    private BigDecimal interestRate;

    /**
     * Sets the interest rate ensuring proper scale and rounding mode.
     * 
     * Automatically applies HALF_UP rounding to 2 decimal places to maintain
     * COBOL COMP-3 precision equivalence.
     * 
     * @param interestRate the interest rate to set (will be scaled to 2 decimals)
     */
    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate != null 
            ? interestRate.setScale(2, RoundingMode.HALF_UP) 
            : null;
    }

    /**
     * Convenience method to get the account group ID from the composite key.
     * 
     * @return account group ID (10 characters), or null if id is null
     */
    public String getAccountGroupId() {
        return this.id != null ? this.id.getAccountGroupId() : null;
    }

    /**
     * Convenience method to get the transaction type code from the composite key.
     * 
     * @return transaction type code (2 characters), or null if id is null
     */
    public String getTransactionTypeCode() {
        return this.id != null ? this.id.getTransactionTypeCode() : null;
    }

    /**
     * Convenience method to get the transaction category code from the composite key.
     * 
     * @return transaction category code (4 digits), or null if id is null
     */
    public Integer getTransactionCategoryCode() {
        return this.id != null ? this.id.getTransactionCategoryCode() : null;
    }

    /**
     * Composite primary key for AccountGroup entity.
     * 
     * Maps the COBOL DIS-GROUP-KEY structure containing three components:
     * - Account Group ID (10 characters)
     * - Transaction Type Code (2 characters)
     * - Transaction Category Code (4 digits)
     * 
     * Implements Serializable as required by JPA specification for composite keys.
     * Provides proper equals() and hashCode() implementation for correct JPA
     * entity identity and collection behavior.
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Account group identifier.
         * 
         * Maps COBOL field: DIS-ACCT-GROUP-ID PIC X(10)
         * Maximum length: 10 characters
         */
        @Column(name = "account_group_id", length = 10, nullable = false)
        private String accountGroupId;

        /**
         * Transaction type code.
         * 
         * Maps COBOL field: DIS-TRAN-TYPE-CD PIC X(02)
         * Maximum length: 2 characters
         * 
         * References TransactionType entity via this code field.
         */
        @Column(name = "transaction_type_code", length = 2, nullable = false)
        private String transactionTypeCode;

        /**
         * Transaction category code.
         * 
         * Maps COBOL field: DIS-TRAN-CAT-CD PIC 9(04)
         * Range: 0-9999 (4 digits)
         * 
         * References TransactionCategory entity via this code field.
         */
        @Column(name = "transaction_category_code", nullable = false)
        private Integer transactionCategoryCode;

        /**
         * Checks equality based on all composite key components.
         * 
         * Required for proper JPA entity identity management and collection behavior.
         * 
         * @param o object to compare
         * @return true if all key components are equal
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GroupId groupId = (GroupId) o;
            return Objects.equals(accountGroupId, groupId.accountGroupId) &&
                   Objects.equals(transactionTypeCode, groupId.transactionTypeCode) &&
                   Objects.equals(transactionCategoryCode, groupId.transactionCategoryCode);
        }

        /**
         * Generates hash code from all composite key components.
         * 
         * Required for proper JPA entity identity management and collection behavior.
         * 
         * @return hash code based on all key fields
         */
        @Override
        public int hashCode() {
            return Objects.hash(accountGroupId, transactionTypeCode, transactionCategoryCode);
        }
    }
}
