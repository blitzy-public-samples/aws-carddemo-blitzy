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

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA entity for the transaction-category reference table.
 *
 * <p>Migrated from COBOL copybook {@code CVTRA04Y.cpy}
 * ({@code legacy/cpy/CVTRA04Y.cpy}), record {@code TRAN-CAT-RECORD}
 * (RECLN=60), to the PostgreSQL reference table {@code transaction_category}.
 * This is a lookup/reference table that resolves a transaction category
 * description keyed by transaction type code and category code.</p>
 *
 * <p>Copybook-to-column mapping:</p>
 * <table>
 *   <caption>CVTRA04Y.cpy {@code TRAN-CAT-RECORD} field mapping</caption>
 *   <tr><th>COBOL field</th><th>PIC</th><th>Column</th><th>Type</th></tr>
 *   <tr><td>{@code TRAN-TYPE-CD}</td><td>{@code X(02)}</td>
 *       <td>{@code type_cd}</td><td>{@code VARCHAR(2)}</td></tr>
 *   <tr><td>{@code TRAN-CAT-CD}</td><td>{@code 9(04)}</td>
 *       <td>{@code cat_cd}</td><td>{@code INTEGER}</td></tr>
 *   <tr><td>{@code TRAN-CAT-TYPE-DESC}</td><td>{@code X(50)}</td>
 *       <td>{@code cat_type_desc}</td><td>{@code VARCHAR(50)}</td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(04)}</td>
 *       <td>(not mapped)</td><td>&mdash;</td></tr>
 * </table>
 *
 * <p>The COBOL group {@code TRAN-CAT-KEY} (VSAM KEYLEN=6 = 2 + 4) becomes the
 * composite primary key {@code (type_cd, cat_cd)}, modeled with
 * {@link jakarta.persistence.EmbeddedId} over the nested {@code @Embeddable}
 * {@link TransactionCategoryId}.</p>
 *
 * <p>The database schema is owned by the Flyway migration
 * {@code V1__schema.sql}; Hibernate is configured with
 * {@code ddl-auto: validate}, so the table, column names, types and composite
 * primary key declared here must match that migration exactly.</p>
 */
@Entity
@Table(name = "transaction_category")
public class TransactionCategory {

    /**
     * Composite primary key for {@link TransactionCategory}, mapping the COBOL
     * group {@code TRAN-CAT-KEY} ({@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}).
     *
     * <p>Declared as a nested {@code public static} {@code @Embeddable} class so
     * the identifier type travels with the entity it belongs to. Per the JPA
     * specification an {@code @EmbeddedId} class must be {@link Serializable}
     * and provide value-based {@code equals}/{@code hashCode}.</p>
     */
    @Embeddable
    public static class TransactionCategoryId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Transaction type code &mdash; COBOL {@code TRAN-TYPE-CD PIC X(02)}.
         */
        @Column(name = "type_cd", length = 2, nullable = false)
        private String typeCd;

        /**
         * Transaction category code &mdash; COBOL {@code TRAN-CAT-CD PIC 9(04)}.
         * Stored as an {@code INTEGER} in PostgreSQL (not a zero-padded string).
         */
        @Column(name = "cat_cd", nullable = false)
        private Integer catCd;

        /**
         * No-argument constructor required by JPA.
         */
        public TransactionCategoryId() {
            // Required by the JPA specification for embeddable id classes.
        }

        /**
         * Creates a fully populated composite key.
         *
         * @param typeCd the transaction type code ({@code TRAN-TYPE-CD})
         * @param catCd  the transaction category code ({@code TRAN-CAT-CD})
         */
        public TransactionCategoryId(String typeCd, Integer catCd) {
            this.typeCd = typeCd;
            this.catCd = catCd;
        }

        /**
         * Returns the transaction type code.
         *
         * @return the transaction type code, or {@code null} if unset
         */
        public String getTypeCd() {
            return typeCd;
        }

        /**
         * Sets the transaction type code.
         *
         * @param typeCd the transaction type code
         */
        public void setTypeCd(String typeCd) {
            this.typeCd = typeCd;
        }

        /**
         * Returns the transaction category code.
         *
         * @return the transaction category code, or {@code null} if unset
         */
        public Integer getCatCd() {
            return catCd;
        }

        /**
         * Sets the transaction category code.
         *
         * @param catCd the transaction category code
         */
        public void setCatCd(Integer catCd) {
            this.catCd = catCd;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TransactionCategoryId other)) {
                return false;
            }
            return Objects.equals(typeCd, other.typeCd)
                    && Objects.equals(catCd, other.catCd);
        }

        @Override
        public int hashCode() {
            return Objects.hash(typeCd, catCd);
        }

        @Override
        public String toString() {
            return "TransactionCategoryId{"
                    + "typeCd=" + typeCd
                    + ", catCd=" + catCd
                    + '}';
        }
    }

    /**
     * Composite primary key {@code (type_cd, cat_cd)} &mdash; COBOL
     * {@code TRAN-CAT-KEY}.
     */
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Transaction category description &mdash; COBOL
     * {@code TRAN-CAT-TYPE-DESC PIC X(50)}.
     */
    @Column(name = "cat_type_desc", length = 50)
    private String catTypeDesc;

    /**
     * No-argument constructor required by JPA.
     */
    protected TransactionCategory() {
        // Required by the JPA specification; not intended for application use.
    }

    /**
     * Creates a transaction-category record from an existing composite key.
     *
     * @param id          the composite primary key ({@code TRAN-CAT-KEY})
     * @param catTypeDesc the category description ({@code TRAN-CAT-TYPE-DESC})
     */
    public TransactionCategory(TransactionCategoryId id, String catTypeDesc) {
        this.id = id;
        this.catTypeDesc = catTypeDesc;
    }

    /**
     * Creates a transaction-category record from its individual key components.
     *
     * @param typeCd      the transaction type code ({@code TRAN-TYPE-CD})
     * @param catCd       the transaction category code ({@code TRAN-CAT-CD})
     * @param catTypeDesc the category description ({@code TRAN-CAT-TYPE-DESC})
     */
    public TransactionCategory(String typeCd, Integer catCd, String catTypeDesc) {
        this.id = new TransactionCategoryId(typeCd, catCd);
        this.catTypeDesc = catTypeDesc;
    }

    /**
     * Returns the composite primary key.
     *
     * @return the composite primary key, or {@code null} if unset
     */
    public TransactionCategoryId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the composite primary key
     */
    public void setId(TransactionCategoryId id) {
        this.id = id;
    }

    /**
     * Returns the transaction category description.
     *
     * @return the category description, or {@code null} if unset
     */
    public String getCatTypeDesc() {
        return catTypeDesc;
    }

    /**
     * Sets the transaction category description.
     *
     * @param catTypeDesc the category description
     */
    public void setCatTypeDesc(String catTypeDesc) {
        this.catTypeDesc = catTypeDesc;
    }

    /**
     * Convenience accessor for the transaction type code held in the composite
     * key.
     *
     * @return the transaction type code, or {@code null} if the key is unset
     */
    public String getTypeCd() {
        return id == null ? null : id.getTypeCd();
    }

    /**
     * Convenience accessor for the transaction category code held in the
     * composite key.
     *
     * @return the transaction category code, or {@code null} if the key is unset
     */
    public Integer getCatCd() {
        return id == null ? null : id.getCatCd();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategory other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TransactionCategory{"
                + "id=" + id
                + ", catTypeDesc=" + catTypeDesc
                + '}';
    }
}
