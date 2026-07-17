/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * JPA entity mapping the COBOL disclosure-group reference record to the
 * PostgreSQL {@code disclosure_group} table.
 *
 * <p><strong>Source mapping.</strong> Derived from copybook
 * {@code CVTRA02Y.cpy} ({@code legacy/cpy/CVTRA02Y.cpy}), record
 * {@code DIS-GROUP-RECORD} (VSAM dataset {@code DISCGRP}, RECLN 50,
 * KEYLEN 16):</p>
 *
 * <pre>
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *         10  DIS-ACCT-GROUP-ID  PIC X(10).     -&gt; group_id  VARCHAR(10)
 *         10  DIS-TRAN-TYPE-CD   PIC X(02).     -&gt; type_cd   VARCHAR(2)
 *         10  DIS-TRAN-CAT-CD    PIC 9(04).     -&gt; cat_cd    INTEGER
 *     05  DIS-INT-RATE           PIC S9(04)V99. -&gt; int_rate  DECIMAL(6,2)
 *     05  FILLER                 PIC X(28).     (padding, deliberately not mapped)
 * </pre>
 *
 * <p>The three-part key {@code DIS-GROUP-KEY} (10 + 2 + 4 = KEYLEN 16) becomes a
 * composite primary key modelled with {@link DisclosureGroupId} via
 * {@link EmbeddedId}. The trailing {@code FILLER} is record padding on the
 * mainframe and is intentionally not represented as a column.</p>
 *
 * <p><strong>Behavioral significance.</strong> {@code int_rate}
 * ({@code DIS-INT-RATE}) is the annual interest rate that drives the
 * parity-critical monthly-interest computation in batch program
 * {@code CBACT04C} ({@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL *
 * DIS-INT-RATE) / 1200}). It is therefore modelled as a {@link BigDecimal} at
 * scale 2 to preserve exact decimal arithmetic; floating-point types are never
 * used for monetary or rate values.</p>
 *
 * <p><strong>Schema ownership.</strong> The physical schema is owned by the
 * Flyway migration {@code V1__schema.sql}; Hibernate runs with
 * {@code ddl-auto=validate}. The table name, column names, SQL types, lengths,
 * precision/scale and composite primary key declared here must match that
 * migration exactly.</p>
 */
@Entity
@Table(name = "disclosure_group")
public class DisclosureGroup {

    /**
     * Composite primary key for {@link DisclosureGroup}, mapping the COBOL
     * {@code DIS-GROUP-KEY} group from {@code CVTRA02Y.cpy}.
     *
     * <p>Field order follows the copybook: {@code group_id} (10 chars),
     * {@code type_cd} (2 chars) and {@code cat_cd} (4 digits) — together the
     * 16-byte VSAM key. As required by the JPA specification, this embeddable
     * is {@link Serializable} and defines value-based {@link #equals(Object)}
     * and {@link #hashCode()} over all three key components.</p>
     */
    @Embeddable
    public static class DisclosureGroupId implements Serializable {

        /** Serialization version identifier. */
        private static final long serialVersionUID = 1L;

        /**
         * Disclosure / account group identifier
         * ({@code DIS-ACCT-GROUP-ID PIC X(10)}). Stored trimmed by the
         * reference-data seed (for example {@code A000000000}, {@code DEFAULT},
         * {@code ZEROAPR}), matching the trimmed convention used by
         * {@code account.group_id}; this mapping neither pads nor trims the
         * value.
         */
        @Column(name = "group_id", length = 10, nullable = false)
        private String groupId;

        /**
         * Transaction type code ({@code DIS-TRAN-TYPE-CD PIC X(02)}).
         */
        @Column(name = "type_cd", length = 2, nullable = false)
        private String typeCd;

        /**
         * Transaction category code ({@code DIS-TRAN-CAT-CD PIC 9(04)}).
         */
        @Column(name = "cat_cd", nullable = false)
        private Integer catCd;

        /**
         * Protected no-argument constructor required by the JPA specification
         * for embeddable types.
         */
        protected DisclosureGroupId() {
            // Intentionally empty: required by JPA for embeddable instantiation.
        }

        /**
         * Creates a fully populated composite key.
         *
         * @param groupId the disclosure / account group identifier
         * @param typeCd  the transaction type code
         * @param catCd   the transaction category code
         */
        public DisclosureGroupId(String groupId, String typeCd, Integer catCd) {
            this.groupId = groupId;
            this.typeCd = typeCd;
            this.catCd = catCd;
        }

        /**
         * Returns the disclosure / account group identifier.
         *
         * @return the group identifier
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * Sets the disclosure / account group identifier.
         *
         * @param groupId the group identifier
         */
        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        /**
         * Returns the transaction type code.
         *
         * @return the transaction type code
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
         * @return the transaction category code
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
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DisclosureGroupId that)) {
                return false;
            }
            return Objects.equals(groupId, that.groupId)
                    && Objects.equals(typeCd, that.typeCd)
                    && Objects.equals(catCd, that.catCd);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, typeCd, catCd);
        }

        @Override
        public String toString() {
            return "DisclosureGroupId{groupId=" + groupId
                    + ", typeCd=" + typeCd
                    + ", catCd=" + catCd + '}';
        }
    }

    /**
     * Composite primary key ({@code DIS-GROUP-KEY}).
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Annual interest rate ({@code DIS-INT-RATE PIC S9(04)V99}) mapped to
     * {@code DECIMAL(6,2)}. Modelled as a {@link BigDecimal} at scale 2 to
     * preserve the exact decimal arithmetic of the {@code CBACT04C} interest
     * calculation; never a floating-point type.
     */
    @Column(name = "int_rate", precision = 6, scale = 2)
    private BigDecimal intRate;

    /**
     * Protected no-argument constructor required by the JPA specification for
     * entity types.
     */
    protected DisclosureGroup() {
        // Intentionally empty: required by JPA for entity instantiation.
    }

    /**
     * Creates a disclosure-group row from an existing composite key.
     *
     * @param id      the composite primary key
     * @param intRate the annual interest rate
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal intRate) {
        this.id = id;
        this.intRate = intRate;
    }

    /**
     * Convenience constructor that assembles the composite key from its
     * individual components.
     *
     * @param groupId the disclosure / account group identifier
     * @param typeCd  the transaction type code
     * @param catCd   the transaction category code
     * @param intRate the annual interest rate
     */
    public DisclosureGroup(String groupId, String typeCd, Integer catCd, BigDecimal intRate) {
        this(new DisclosureGroupId(groupId, typeCd, catCd), intRate);
    }

    /**
     * Returns the composite primary key.
     *
     * @return the composite primary key
     */
    public DisclosureGroupId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the composite primary key
     */
    public void setId(DisclosureGroupId id) {
        this.id = id;
    }

    /**
     * Returns the annual interest rate.
     *
     * @return the annual interest rate as a scale-2 {@link BigDecimal}
     */
    public BigDecimal getIntRate() {
        return intRate;
    }

    /**
     * Sets the annual interest rate.
     *
     * @param intRate the annual interest rate as a scale-2 {@link BigDecimal}
     */
    public void setIntRate(BigDecimal intRate) {
        this.intRate = intRate;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DisclosureGroup that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "DisclosureGroup{id=" + id + ", intRate=" + intRate + '}';
    }
}
