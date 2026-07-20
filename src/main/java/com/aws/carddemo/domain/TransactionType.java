/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity for the transaction-type reference table.
 *
 * <p>Migrated from the COBOL copybook {@code CVTRA03Y.cpy} record
 * {@code TRAN-TYPE-RECORD} (relocated to {@code legacy/cpy/CVTRA03Y.cpy}), which
 * backed the {@code TRANTYPE} VSAM KSDS reference dataset. Each row is a small
 * lookup entry pairing a two-character transaction-type code with a
 * human-readable description (for example {@code 01} = Purchase,
 * {@code 02} = Payment).</p>
 *
 * <p>Field mapping (source PIC clause &rarr; relational column):</p>
 * <ul>
 *   <li>{@code TRAN-TYPE      PIC X(02)} &rarr; {@code type_cd}
 *       (natural primary key; VSAM {@code KEYLEN=2})</li>
 *   <li>{@code TRAN-TYPE-DESC PIC X(50)} &rarr; {@code type_desc}</li>
 *   <li>{@code FILLER         PIC X(08)} &rarr; not mapped (record padding only)</li>
 * </ul>
 *
 * <p>The relational schema is owned and created by the Flyway migration
 * {@code V1__schema.sql}; Hibernate runs in {@code validate} mode, so the table
 * and column names and lengths declared here mirror that DDL exactly. Rows are
 * loaded by {@code V2__reference_data.sql}. Because the code is natural
 * reference data supplied by the seed migration, no {@code @GeneratedValue}
 * strategy is used, and the immutable reference nature of the table means no
 * optimistic-lock {@code @Version} column is required.</p>
 */
@Entity
@Table(name = "transaction_type")
public class TransactionType {

    /**
     * Two-character transaction-type code and natural primary key
     * (COBOL {@code TRAN-TYPE PIC X(02)} &rarr; column {@code type_cd}).
     */
    @Id
    @Column(name = "type_cd", length = 2, nullable = false)
    private String typeCd;

    /**
     * Human-readable transaction-type description
     * (COBOL {@code TRAN-TYPE-DESC PIC X(50)} &rarr; column {@code type_desc}).
     */
    @Column(name = "type_desc", length = 50)
    private String typeDesc;

    /**
     * Protected no-argument constructor required by the JPA specification for
     * entity instantiation via reflection. Application code should prefer
     * {@link #TransactionType(String, String)}.
     */
    protected TransactionType() {
        // Required by JPA; intentionally empty.
    }

    /**
     * Creates a fully populated transaction-type reference row.
     *
     * @param typeCd   the two-character transaction-type code (primary key)
     * @param typeDesc the transaction-type description
     */
    public TransactionType(String typeCd, String typeDesc) {
        // Direct field assignment (no setter invocation) keeps the constructor
        // free of a 'this-escape', preserving the zero-warning -Xlint:all build.
        this.typeCd = typeCd;
        this.typeDesc = typeDesc;
    }

    /**
     * Returns the two-character transaction-type code (primary key).
     *
     * @return the transaction-type code
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Sets the two-character transaction-type code (primary key).
     *
     * @param typeCd the transaction-type code to set
     */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /**
     * Returns the transaction-type description.
     *
     * @return the transaction-type description
     */
    public String getTypeDesc() {
        return typeDesc;
    }

    /**
     * Sets the transaction-type description.
     *
     * @param typeDesc the transaction-type description to set
     */
    public void setTypeDesc(String typeDesc) {
        this.typeDesc = typeDesc;
    }

    /**
     * Two {@code TransactionType} instances are equal when they are of the same
     * concrete type and share the same {@code type_cd} natural key.
     *
     * @param o the reference object with which to compare
     * @return {@code true} if this object is equal to {@code o}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TransactionType that = (TransactionType) o;
        return Objects.equals(typeCd, that.typeCd);
    }

    /**
     * Hash code derived from the {@code type_cd} natural key, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this entity
     */
    @Override
    public int hashCode() {
        return Objects.hash(typeCd);
    }

    /**
     * Returns a diagnostic string representation. This reference entity carries
     * no sensitive data, so both fields are included.
     *
     * @return a string representation of this entity
     */
    @Override
    public String toString() {
        return "TransactionType{"
                + "typeCd='" + typeCd + '\''
                + ", typeDesc='" + typeDesc + '\''
                + '}';
    }
}
