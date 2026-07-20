/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity that maps the legacy COBOL copybook {@code CVTRA01Y.cpy}
 * ({@code TRAN-CAT-BAL-RECORD}, RECLN&nbsp;=&nbsp;50) to the PostgreSQL table
 * {@code tran_cat_balance}.
 *
 * <p>The record holds a running per-(account, transaction-type, transaction-category)
 * balance. In the legacy system it was accessed as a VSAM KSDS keyed on the 17-byte
 * {@code TRAN-CAT-KEY} group (11 + 2 + 4). Both the online bill-payment flow and the
 * batch posting / interest-calculation jobs mutate this record via the classic
 * READ&nbsp;-&nbsp;UPDATE&nbsp;-&nbsp;REWRITE cycle, so the entity carries an
 * optimistic-lock {@link Version} column to preserve last-writer integrity within a
 * transactional boundary (AAP&nbsp;&sect;0.7.1 H6). The optimistic lock is a documented
 * integrity improvement over the legacy design, not a behavioral change.</p>
 *
 * <p><strong>Schema ownership.</strong> The physical schema is created and owned by the
 * Flyway migration {@code V1__schema.sql}; Hibernate runs with {@code ddl-auto: validate}
 * and therefore only validates that this mapping matches the DDL. Every table name,
 * column name, type, length, and decimal precision/scale below is deliberately kept
 * identical to that migration.</p>
 *
 * <h2>Copybook-to-column mapping</h2>
 * <table>
 *   <caption>{@code CVTRA01Y.cpy TRAN-CAT-BAL-RECORD} &rarr; {@code tran_cat_balance}</caption>
 *   <tr><th>COBOL field</th><th>PIC</th><th>Java field</th><th>Column (type)</th></tr>
 *   <tr><td>{@code TRANCAT-ACCT-ID}</td><td>{@code 9(11)}</td>
 *       <td>{@code id.acctId}</td><td>{@code acct_id} (BIGINT)</td></tr>
 *   <tr><td>{@code TRANCAT-TYPE-CD}</td><td>{@code X(02)}</td>
 *       <td>{@code id.typeCd}</td><td>{@code type_cd} (VARCHAR(2))</td></tr>
 *   <tr><td>{@code TRANCAT-CD}</td><td>{@code 9(04)}</td>
 *       <td>{@code id.catCd}</td><td>{@code cat_cd} (INTEGER)</td></tr>
 *   <tr><td>{@code TRAN-CAT-BAL}</td><td>{@code S9(09)V99}</td>
 *       <td>{@code bal}</td><td>{@code bal} (DECIMAL(11,2))</td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(22)}</td>
 *       <td colspan="2">intentionally not mapped (record padding)</td></tr>
 * </table>
 *
 * <p>The monetary {@code TRAN-CAT-BAL} field is modeled as a {@link BigDecimal} at
 * scale&nbsp;2; floating-point types are never used for monetary values, preserving the
 * bit-exact semantics of the original {@code COMP-3} packed decimal.</p>
 *
 * <p>The three-part primary key is expressed with an {@link EmbeddedId} referencing the
 * nested {@link TransactionCategoryBalanceId} value class. The alternate/foreign-key
 * relationships that {@code V1__schema.sql} may declare at the database level
 * ({@code acct_id}&nbsp;&rarr;&nbsp;{@code account},
 * {@code (type_cd, cat_cd)}&nbsp;&rarr;&nbsp;{@code transaction_category}) are kept as
 * scalar key attributes here rather than {@code @ManyToOne} associations, consistent with
 * the project-wide scalar foreign-key approach.</p>
 */
@Entity
@Table(name = "tran_cat_balance")
public class TransactionCategoryBalance {

    /**
     * Composite primary key mapping the 17-byte COBOL {@code TRAN-CAT-KEY} group
     * ({@code acct_id}, {@code type_cd}, {@code cat_cd}).
     */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Running balance for the (account, type, category) tuple, mapping
     * {@code TRAN-CAT-BAL PIC S9(09)V99} to {@code DECIMAL(11,2)}. Modeled as
     * {@link BigDecimal} at scale&nbsp;2 to preserve exact monetary arithmetic.
     */
    @Column(name = "bal", precision = 11, scale = 2)
    private BigDecimal bal;

    /**
     * Optimistic-locking version reproducing the integrity of the legacy
     * READ&nbsp;-&nbsp;UPDATE&nbsp;-&nbsp;REWRITE cycle. Managed by the JPA provider;
     * mapped to the non-null {@code version} column ({@code BIGINT}).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Protected no-argument constructor required by the JPA specification. Application
     * code should use {@link #TransactionCategoryBalance(TransactionCategoryBalanceId, BigDecimal)}.
     */
    protected TransactionCategoryBalance() {
        // Required by JPA; intentionally empty.
    }

    /**
     * Convenience constructor for application and test code.
     *
     * @param id  the composite primary key (account, type, category); may be {@code null}
     *            only in transient pre-persist states
     * @param bal the running category balance; {@code null} is permitted by the column
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal bal) {
        this.id = id;
        this.bal = bal;
    }

    /**
     * Returns the composite primary key.
     *
     * @return the {@link TransactionCategoryBalanceId}, or {@code null} if unset
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the composite primary key to assign
     */
    public void setId(TransactionCategoryBalanceId id) {
        this.id = id;
    }

    /**
     * Returns the running category balance ({@code TRAN-CAT-BAL}).
     *
     * @return the balance as a {@link BigDecimal} at scale&nbsp;2, or {@code null} if unset
     */
    public BigDecimal getBal() {
        return bal;
    }

    /**
     * Sets the running category balance ({@code TRAN-CAT-BAL}).
     *
     * @param bal the balance to assign
     */
    public void setBal(BigDecimal bal) {
        this.bal = bal;
    }

    /**
     * Returns the optimistic-locking version.
     *
     * @return the version counter, or {@code null} before the entity is first persisted
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version. Normally managed by the JPA provider; exposed
     * primarily for testing.
     *
     * @param version the version counter to assign
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Two {@code TransactionCategoryBalance} instances are equal when they share the same
     * composite primary key. Identity is delegated to {@link #id}.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code TransactionCategoryBalance} with an equal id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionCategoryBalance that = (TransactionCategoryBalance) o;
        return Objects.equals(id, that.id);
    }

    /**
     * Hash code derived from the composite primary key, consistent with {@link #equals(Object)}.
     *
     * @return the hash code of the id
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Diagnostic string representation. The balance and version are included; no sensitive
     * data is present on this record.
     *
     * @return a human-readable representation of this entity
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance{"
                + "id=" + id
                + ", bal=" + bal
                + ", version=" + version
                + '}';
    }

    /**
     * Embeddable composite primary key for {@link TransactionCategoryBalance}, mapping the
     * COBOL {@code TRAN-CAT-KEY} group of {@code CVTRA01Y.cpy}. The field order mirrors the
     * copybook: account id, transaction-type code, transaction-category code.
     *
     * <p>Implements {@link Serializable} as required by the JPA specification for composite
     * key classes.</p>
     */
    @Embeddable
    public static class TransactionCategoryBalanceId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Account identifier ({@code TRANCAT-ACCT-ID PIC 9(11)}). The eleven-digit domain
         * exceeds the {@code int} range, so it is modeled as {@link Long}
         * ({@code acct_id BIGINT}).
         */
        @Column(name = "acct_id", nullable = false)
        private Long acctId;

        /**
         * Transaction-type code ({@code TRANCAT-TYPE-CD PIC X(02)}) mapped to
         * {@code type_cd VARCHAR(2)}.
         */
        @Column(name = "type_cd", length = 2, nullable = false)
        private String typeCd;

        /**
         * Transaction-category code ({@code TRANCAT-CD PIC 9(04)}) mapped to
         * {@code cat_cd INTEGER}.
         */
        @Column(name = "cat_cd", nullable = false)
        private Integer catCd;

        /**
         * Default no-argument constructor required by the JPA specification.
         */
        public TransactionCategoryBalanceId() {
            // Required by JPA; intentionally empty.
        }

        /**
         * Creates a fully populated composite key.
         *
         * @param acctId the account identifier ({@code TRANCAT-ACCT-ID})
         * @param typeCd the transaction-type code ({@code TRANCAT-TYPE-CD})
         * @param catCd  the transaction-category code ({@code TRANCAT-CD})
         */
        public TransactionCategoryBalanceId(Long acctId, String typeCd, Integer catCd) {
            this.acctId = acctId;
            this.typeCd = typeCd;
            this.catCd = catCd;
        }

        /**
         * Returns the account identifier component of the key.
         *
         * @return the account id, or {@code null} if unset
         */
        public Long getAcctId() {
            return acctId;
        }

        /**
         * Sets the account identifier component of the key.
         *
         * @param acctId the account id to assign
         */
        public void setAcctId(Long acctId) {
            this.acctId = acctId;
        }

        /**
         * Returns the transaction-type code component of the key.
         *
         * @return the transaction-type code, or {@code null} if unset
         */
        public String getTypeCd() {
            return typeCd;
        }

        /**
         * Sets the transaction-type code component of the key.
         *
         * @param typeCd the transaction-type code to assign
         */
        public void setTypeCd(String typeCd) {
            this.typeCd = typeCd;
        }

        /**
         * Returns the transaction-category code component of the key.
         *
         * @return the transaction-category code, or {@code null} if unset
         */
        public Integer getCatCd() {
            return catCd;
        }

        /**
         * Sets the transaction-category code component of the key.
         *
         * @param catCd the transaction-category code to assign
         */
        public void setCatCd(Integer catCd) {
            this.catCd = catCd;
        }

        /**
         * Equality over all three key components, as required for a well-behaved JPA
         * composite key.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code TransactionCategoryBalanceId} with
         *         equal account id, type code, and category code
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) o;
            return Objects.equals(acctId, that.acctId)
                    && Objects.equals(typeCd, that.typeCd)
                    && Objects.equals(catCd, that.catCd);
        }

        /**
         * Hash code over all three key components, consistent with {@link #equals(Object)}.
         *
         * @return the combined hash code of the key components
         */
        @Override
        public int hashCode() {
            return Objects.hash(acctId, typeCd, catCd);
        }

        /**
         * Diagnostic string representation of the composite key.
         *
         * @return a human-readable representation of this key
         */
        @Override
        public String toString() {
            return "TransactionCategoryBalanceId{"
                    + "acctId=" + acctId
                    + ", typeCd='" + typeCd + '\''
                    + ", catCd=" + catCd
                    + '}';
        }
    }
}
