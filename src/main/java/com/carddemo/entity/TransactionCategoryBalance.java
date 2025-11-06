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
 * JPA Entity representing Transaction Category Balance.
 * 
 * Transformed from COBOL copybook CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD).
 * Maps VSAM KSDS transaction category balance records to PostgreSQL 
 * transaction_category_balance table.
 * 
 * This entity tracks balance amounts by account, transaction type, and category.
 * Uses composite primary key combining account ID, transaction type code, and category code.
 * 
 * COBOL Structure:
 * - TRAN-CAT-KEY (Composite Key):
 *   - TRANCAT-ACCT-ID PIC 9(11) → accountId
 *   - TRANCAT-TYPE-CD PIC X(02) → transactionTypeCode
 *   - TRANCAT-CD PIC 9(04) → categoryCode
 * - TRAN-CAT-BAL PIC S9(09)V99 → balance (BigDecimal with precision=12, scale=2)
 * 
 * Maintains COBOL COMP-3 decimal precision using Java BigDecimal per section 0.10 requirement 7.
 */
@Entity
@Table(name = "transaction_category_balance")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionCategoryBalance implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key containing account ID, transaction type code, and category code.
     * Maps to TRAN-CAT-KEY from COBOL copybook.
     */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Transaction category balance amount.
     * 
     * Maps from COBOL field: TRAN-CAT-BAL PIC S9(09)V99
     * Precision: 12 digits total (9 integer + 2 fractional + 1 sign)
     * Scale: 2 decimal places for cents
     * 
     * Uses BigDecimal to preserve exact COBOL COMP-3 packed decimal precision.
     * All monetary calculations must use BigDecimal arithmetic with explicit 
     * RoundingMode.HALF_UP per section 0.10 requirement 7.
     */
    @Column(name = "balance", precision = 12, scale = 2, nullable = false)
    private BigDecimal balance;

    /**
     * Version field for optimistic locking.
     * Automatically incremented by JPA on each update to prevent lost updates
     * in concurrent access scenarios, replacing VSAM record locking behavior.
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Embeddable composite primary key class for TransactionCategoryBalance.
     * 
     * Represents the TRAN-CAT-KEY composite key from COBOL copybook CVTRA01Y.cpy.
     * Combines three fields to uniquely identify a transaction category balance:
     * - Account ID (11-digit numeric)
     * - Transaction Type Code (2-character alphanumeric)
     * - Category Code (4-digit numeric, stored as String to preserve leading zeros)
     * 
     * Must implement Serializable and override equals/hashCode for JPA composite key requirements.
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TransactionCategoryBalanceId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Account identifier.
         * Maps from COBOL field: TRANCAT-ACCT-ID PIC 9(11)
         * 11-digit numeric account identifier.
         */
        @Column(name = "account_id", nullable = false)
        private Long accountId;

        /**
         * Transaction type code.
         * Maps from COBOL field: TRANCAT-TYPE-CD PIC X(02)
         * 2-character alphanumeric transaction type identifier.
         */
        @Column(name = "transaction_type_code", length = 2, nullable = false)
        private String transactionTypeCode;

        /**
         * Transaction category code.
         * Maps from COBOL field: TRANCAT-CD PIC 9(04)
         * 4-digit numeric category identifier stored as String to preserve leading zeros.
         */
        @Column(name = "category_code", length = 4, nullable = false)
        private String categoryCode;
    }
}
