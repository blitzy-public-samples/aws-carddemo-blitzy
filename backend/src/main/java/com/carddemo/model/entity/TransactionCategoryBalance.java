package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity representing transaction category balance aggregates per account.
 * 
 * Converted from COBOL copybook: CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD)
 * Original record length: 50 bytes
 * 
 * This entity stores running balances aggregated by account, transaction type, and
 * category combination. Used for category-level balance tracking and reporting to
 * support transaction categorization analytics and customer spending insights.
 * 
 * Conversion notes:
 * - Composite key TRAN-CAT-KEY (TRANCAT-ACCT-ID + TRANCAT-TYPE-CD + TRANCAT-CD)
 *   converted to @IdClass pattern using TransactionCategoryBalanceId composite key class
 * - COBOL PIC 9(11) TRANCAT-ACCT-ID converted to Long foreign key with @ManyToOne
 *   relationship to Account entity
 * - COBOL PIC X(02) TRANCAT-TYPE-CD converted to String with max length 2
 * - COBOL PIC 9(04) TRANCAT-CD converted to Integer
 * - COBOL PIC S9(09)V99 COMP-3 TRAN-CAT-BAL converted to BigDecimal with scale 2 and
 *   precision 11 to preserve exact packed decimal precision per Section 0.7.2
 * - COBOL FILLER field (22 bytes) removed as not used
 * - No audit fields (createdAt, updatedAt) or version field per database schema in
 *   Section 0.3.4 as this is an aggregated balance table updated by batch processes
 * 
 * Database table: transaction_category_balance
 * Primary key: (tcat_acct_id, tcat_type_cd, tcat_cat_cd)
 * Foreign key: tcat_acct_id references account(acct_id)
 * 
 * Referenced by:
 * - Batch job CBTRN03C (transaction category summarization) updates these balances
 * - Report generation processes query these balances for category analysis
 * 
 * @see Account
 */
@Entity
@Table(name = "transaction_category_balance")
@IdClass(TransactionCategoryBalance.TransactionCategoryBalanceId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionCategoryBalance {

    /**
     * Account identifier (part of composite primary key, foreign key to Account).
     * 
     * Converted from: COBOL PIC 9(11) TRANCAT-ACCT-ID
     * Maximum value: 99,999,999,999 (11 digits)
     * 
     * Foreign key to Account entity. This field is part of the composite primary key
     * and establishes the relationship to the account for which category balances
     * are tracked. Uses insertable=false and updatable=false because this field is
     * part of the composite key and managed through the key class.
     */
    @Id
    @Column(name = "tcat_acct_id", nullable = false)
    private Long tcatAcctId;

    /**
     * Transaction type code (part of composite primary key).
     * 
     * Converted from: COBOL PIC X(02) TRANCAT-TYPE-CD
     * Maximum length: 2 characters
     * 
     * Two-character code identifying the transaction type (e.g., 'PU' for purchase,
     * 'CA' for cash advance, 'PM' for payment). Combined with category code to
     * provide granular balance tracking by both type and category dimensions.
     */
    @Id
    @Column(name = "tcat_type_cd", nullable = false, length = 2)
    private String tcatTypeCd;

    /**
     * Transaction category code (part of composite primary key).
     * 
     * Converted from: COBOL PIC 9(04) TRANCAT-CD
     * Maximum value: 9999 (4 digits)
     * 
     * Numeric code identifying the transaction category (e.g., 1000 for groceries,
     * 2000 for gas, 3000 for dining). Categories provide detailed classification
     * of spending patterns within each transaction type for customer analytics.
     */
    @Id
    @Column(name = "tcat_cat_cd", nullable = false)
    private Integer tcatCatCd;

    /**
     * Category balance amount.
     * 
     * Converted from: COBOL PIC S9(09)V99 COMP-3 TRAN-CAT-BAL
     * Precision: 11 digits total, 2 decimal places
     * Range: -999,999,999.99 to 999,999,999.99
     * Default: 0.00
     * 
     * Running balance for this specific account/type/category combination. Updated
     * by batch job CBTRN03C during daily transaction category summarization processing.
     * Uses BigDecimal to preserve COBOL COMP-3 packed decimal precision and ensure
     * bit-identical financial calculations per Section 0.7.2 requirement.
     * 
     * Positive balances indicate net debits (purchases, fees), negative balances
     * indicate net credits (payments, refunds) for the category within the current
     * reporting period.
     */
    @Column(name = "tcat_bal", nullable = false, precision = 11, scale = 2, columnDefinition = "NUMERIC(11,2) DEFAULT 0.00")
    private BigDecimal tcatBal;

    /**
     * Many-to-one relationship to Account entity.
     * 
     * Establishes foreign key relationship from tcat_acct_id to account.acct_id.
     * This relationship enables navigation from category balance records to their
     * parent account and supports referential integrity enforcement.
     * 
     * Uses insertable=false and updatable=false because tcatAcctId is part of the
     * composite primary key and already mapped as @Id field. JPA requires these
     * flags to avoid duplicate column mapping conflicts.
     */
    @ManyToOne
    @JoinColumn(name = "tcat_acct_id", referencedColumnName = "acct_id", nullable = false, insertable = false, updatable = false)
    private Account account;

    /**
     * Composite primary key class for TransactionCategoryBalance entity.
     * 
     * Implements Serializable as required by JPA specification for composite key classes.
     * Contains the three fields that form the composite key: account ID, transaction
     * type code, and transaction category code.
     * 
     * This class is used with @IdClass annotation to map the composite key from COBOL
     * TRAN-CAT-KEY structure (TRANCAT-ACCT-ID + TRANCAT-TYPE-CD + TRANCAT-CD) to JPA
     * entity mapping.
     * 
     * Requirements per JPA specification:
     * - Must be public and implement Serializable
     * - Must have public no-arg constructor
     * - Must override equals() and hashCode() using all key fields
     * - Field names must match exactly the @Id field names in the entity class
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionCategoryBalanceId implements Serializable {
        
        private static final long serialVersionUID = 1L;

        /**
         * Account identifier (part of composite key).
         * Field name must match @Id field in TransactionCategoryBalance entity.
         */
        private Long tcatAcctId;

        /**
         * Transaction type code (part of composite key).
         * Field name must match @Id field in TransactionCategoryBalance entity.
         */
        private String tcatTypeCd;

        /**
         * Transaction category code (part of composite key).
         * Field name must match @Id field in TransactionCategoryBalance entity.
         */
        private Integer tcatCatCd;

        /**
         * Compares this composite key with another for equality.
         * 
         * Required by JPA for composite key comparison during entity loading and caching.
         * Two composite keys are equal if all three component fields are equal.
         * 
         * @param o object to compare
         * @return true if keys are equal, false otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) o;
            return Objects.equals(tcatAcctId, that.tcatAcctId) &&
                   Objects.equals(tcatTypeCd, that.tcatTypeCd) &&
                   Objects.equals(tcatCatCd, that.tcatCatCd);
        }

        /**
         * Generates hash code for this composite key.
         * 
         * Required by JPA for entity caching and collection operations.
         * Hash code is computed from all three component fields.
         * 
         * @return hash code value
         */
        @Override
        public int hashCode() {
            return Objects.hash(tcatAcctId, tcatTypeCd, tcatCatCd);
        }
    }
}
