package com.carddemo.account.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One disclosure group row: a sixteen-character composite key and one annual interest rate.
 *
 * <p>Transformed field by field from {@code 01 DIS-GROUP-RECORD.} at
 * {@code app/cpy/CVTRA02Y.cpy:L4}, a 50-byte record. {@code app/jcl/DISCGRP.jcl:L40} declares the
 * Virtual Storage Access Method (VSAM) key as {@code KEYS(16 0)}, and
 * {@code app/jcl/DISCGRP.jcl:L41} declares the record as {@code RECORDSIZE(50 50)}.</p>
 *
 * <p>Four fields map. {@link DisclosureGroupId} carries the three key fields at
 * {@code app/cpy/CVTRA02Y.cpy:L6-L8}, and {@link #getInterestRate()} carries the rate at
 * {@code app/cpy/CVTRA02Y.cpy:L9}. The trailing {@code FILLER PIC X(28)} at
 * {@code app/cpy/CVTRA02Y.cpy:L10} maps to no column;
 * {@code card-platform/docs/traceability-matrix.md} records that omission.</p>
 *
 * <p>{@code app/data/ASCII/discgrp.txt} holds 51 records, each 50 characters wide. This service
 * stores those rows and computes no interest. {@code app/cbl/CBACT04C.cbl:L415} reads the same
 * record inside the interest program, which stays a scheduled batch job.</p>
 *
 * <p>Column names, column types and key order match table {@code disclosure_group} in
 * {@code src/main/resources/db/migration/V1__schema.sql}. Flyway applies that Data Definition
 * Language (DDL), and Jakarta Persistence validates this mapping against the applied schema at
 * start-up. {@code card-platform/docs/data-model.md} maps the eight tables this service owns.</p>
 */
@Entity
@Table(name = "disclosure_group")
public class DisclosureGroupEntity {

    /**
     * Composite key of this row, the group {@code 05 DIS-GROUP-KEY.} at
     * {@code app/cpy/CVTRA02Y.cpy:L5}.
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Annual interest rate. {@code DIS-INT-RATE PIC S9(04)V99} at
     * {@code app/cpy/CVTRA02Y.cpy:L9}. Six total digits, scale 2.
     */
    @Column(name = "interest_rate", nullable = false,
            precision = PicClause.DIS_INT_RATE_PRECISION,
            scale = PicClause.DIS_INT_RATE_SCALE)
    private BigDecimal interestRate;

    /** Builds an empty row. Jakarta Persistence instantiates a loaded row through this. */
    public DisclosureGroupEntity() {
    }

    /**
     * Builds a row from its key and its rate.
     *
     * @param id           the composite key
     * @param interestRate the annual rate at scale 2
     */
    public DisclosureGroupEntity(DisclosureGroupId id, BigDecimal interestRate) {
        this.id = id;
        this.interestRate = interestRate;
    }

    /**
     * Reads the composite key.
     *
     * @return the three key parts of this row
     */
    public DisclosureGroupId getId() {
        return id;
    }

    /**
     * Writes the composite key.
     *
     * @param id the three key parts of this row
     */
    public void setId(DisclosureGroupId id) {
        this.id = id;
    }

    /**
     * Reads the annual interest rate.
     *
     * @return the rate at scale 2
     */
    public BigDecimal getInterestRate() {
        return interestRate;
    }

    /**
     * Writes the annual interest rate.
     *
     * @param interestRate the rate at scale 2
     */
    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    /**
     * The three key fields of {@code 05 DIS-GROUP-KEY.} at {@code app/cpy/CVTRA02Y.cpy:L5}, in
     * copybook order.
     *
     * <p>The parts span 10, 2 and 4 characters and total sixteen, the width
     * {@code app/jcl/DISCGRP.jcl:L40} declares as {@code KEYS(16 0)}. A VSAM key is positional,
     * and the offset {@code 0} in that clause places the key at the first byte of the record.
     * {@code app/data/ASCII/discgrp.txt} carries the three parts at one-based positions 1 to 10,
     * 11 to 12 and 13 to 16.</p>
     *
     * <p>The three columns form {@code pk_disclosure_group} in the same order.</p>
     */
    @Embeddable
    public static class DisclosureGroupId implements Serializable {

        /** Serialization version of this key. */
        private static final long serialVersionUID = 1L;

        /**
         * Type of {@code transaction_type_code} as PostgreSQL reports it. The migration declares
         * the column {@code CHAR(2)}, and PostgreSQL names that type {@code bpchar}.
         */
        private static final String TRANSACTION_TYPE_CODE_COLUMN_TYPE =
                "bpchar(" + PicClause.DIS_TRAN_TYPE_CD_WIDTH + ")";

        /** Digits after the decimal point in {@code transaction_category_code}. */
        private static final int TRANSACTION_CATEGORY_CODE_SCALE = 0;

        /**
         * Account group identifier, first part of the key.
         * {@code DIS-ACCT-GROUP-ID PIC X(10)} at {@code app/cpy/CVTRA02Y.cpy:L6}.
         */
        @Column(name = "account_group_id", nullable = false,
                length = PicClause.DIS_ACCT_GROUP_ID_WIDTH)
        private String accountGroupId;

        /**
         * Transaction type code, second part of the key. {@code DIS-TRAN-TYPE-CD PIC X(02)} at
         * {@code app/cpy/CVTRA02Y.cpy:L7}. The column pads a shorter value to two characters.
         */
        @Column(name = "transaction_type_code", nullable = false,
                length = PicClause.DIS_TRAN_TYPE_CD_WIDTH,
                columnDefinition = TRANSACTION_TYPE_CODE_COLUMN_TYPE)
        private String transactionTypeCode;

        /**
         * Transaction category code, third part of the key. {@code DIS-TRAN-CAT-CD PIC 9(04)} at
         * {@code app/cpy/CVTRA02Y.cpy:L8}. The column is {@code NUMERIC(4,0)}: four digits,
         * scale 0.
         */
        @Column(name = "transaction_category_code", nullable = false,
                precision = PicClause.DIS_TRAN_CAT_CD_WIDTH,
                scale = TRANSACTION_CATEGORY_CODE_SCALE)
        private BigDecimal transactionCategoryCode;

        /** Builds an empty key. Jakarta Persistence instantiates a loaded key through this. */
        public DisclosureGroupId() {
        }

        /**
         * Builds a key from its three parts, in copybook order.
         *
         * @param accountGroupId          the account group identifier, up to ten characters
         * @param transactionTypeCode     the transaction type code, two characters
         * @param transactionCategoryCode the transaction category code, four digits at scale 0
         */
        public DisclosureGroupId(String accountGroupId, String transactionTypeCode,
                BigDecimal transactionCategoryCode) {
            this.accountGroupId = accountGroupId;
            this.transactionTypeCode = transactionTypeCode;
            this.transactionCategoryCode = transactionCategoryCode;
        }

        /**
         * Reads the account group identifier.
         *
         * @return the first key part
         */
        public String getAccountGroupId() {
            return accountGroupId;
        }

        /**
         * Writes the account group identifier.
         *
         * @param accountGroupId the first key part
         */
        public void setAccountGroupId(String accountGroupId) {
            this.accountGroupId = accountGroupId;
        }

        /**
         * Reads the transaction type code.
         *
         * @return the second key part
         */
        public String getTransactionTypeCode() {
            return transactionTypeCode;
        }

        /**
         * Writes the transaction type code.
         *
         * @param transactionTypeCode the second key part
         */
        public void setTransactionTypeCode(String transactionTypeCode) {
            this.transactionTypeCode = transactionTypeCode;
        }

        /**
         * Reads the transaction category code.
         *
         * @return the third key part
         */
        public BigDecimal getTransactionCategoryCode() {
            return transactionCategoryCode;
        }

        /**
         * Writes the transaction category code.
         *
         * @param transactionCategoryCode the third key part
         */
        public void setTransactionCategoryCode(BigDecimal transactionCategoryCode) {
            this.transactionCategoryCode = transactionCategoryCode;
        }

        /**
         * Compares all three key parts.
         *
         * @param other the value to compare against
         * @return true when {@code other} is a key carrying the same three parts
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DisclosureGroupId that)) {
                return false;
            }
            return Objects.equals(accountGroupId, that.accountGroupId)
                    && Objects.equals(transactionTypeCode, that.transactionTypeCode)
                    && Objects.equals(transactionCategoryCode, that.transactionCategoryCode);
        }

        /**
         * Hashes all three key parts.
         *
         * @return the hash of this key
         */
        @Override
        public int hashCode() {
            return Objects.hash(accountGroupId, transactionTypeCode, transactionCategoryCode);
        }
    }
}
