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
package com.aws.carddemo.domain.id;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Composite primary key for {@link com.aws.carddemo.domain.TransactionCategory} (table {@code
 * tran_category}).
 *
 * <p>This {@code @Embeddable} maps the COBOL {@code 05 TRAN-CAT-KEY} group of copybook {@code
 * CVTRA04Y} (legacy source {@code legacy/app/cpy/CVTRA04Y.cpy}). That group is the VSAM key of the
 * {@code TRANCATG} dataset, composed of two parts in this exact order:
 *
 * <ul>
 *   <li>{@code 10 TRAN-TYPE-CD PIC X(02)} &rarr; {@link #tranTypeCd} (column {@code tran_type_cd},
 *       {@code char(2)})
 *   <li>{@code 10 TRAN-CAT-CD PIC 9(04)} &rarr; {@link #tranCatCd} (column {@code tran_cat_cd},
 *       {@code char(4)})
 * </ul>
 *
 * <p>The combined key length is <strong>KEYLEN 6</strong> (2 + 4), matching the VSAM catalog
 * definition (Agent Action Plan &sect;0.6.2). The non-key {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)}
 * field and the trailing {@code 05 FILLER PIC X(04)} are intentionally <em>not</em> modeled here:
 * the description belongs to the owning entity {@code TransactionCategory}, and FILLER is
 * positional padding with no business meaning.
 *
 * <p><strong>Schema authority.</strong> The Spring Boot application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}; the single source of truth for column names and types is
 * the Flyway migration {@code src/main/resources/db/migration/V1__schema.sql}. Its {@code
 * tran_category} composite primary key — {@code PRIMARY KEY (tran_type_cd, tran_cat_cd)} — is
 * mirrored field-for-field here. Both columns are fixed-width {@code char(n)} so VSAM ordering and
 * equality semantics are preserved; although {@code TRAN-CAT-CD} is {@code PIC 9(04)} in COBOL, it
 * is an enumerated category code with mandatory leading zeros and is therefore stored as {@code
 * char(4)} text rather than a numeric type. Each field is pinned with {@link JdbcTypeCode}({@link
 * SqlTypes#CHAR}) because a plain {@link String} would default to {@code VARCHAR}, which would
 * cause Hibernate's {@code validate} check to fail against the {@code char} columns at startup.
 *
 * <p>The class is {@link Serializable} as required for JPA {@code @EmbeddedId} keys, and implements
 * value-based {@link #equals(Object)} and {@link #hashCode()} over both key parts so it behaves
 * correctly as a {@code Map} key and in {@code findById} lookups.
 */
@Embeddable
public class TransactionCategoryId implements Serializable {

  /** Serialization version identifier for this JPA composite key. */
  private static final long serialVersionUID = 1L;

  /**
   * Transaction type code — COBOL {@code TRAN-TYPE-CD PIC X(02)}; first part of the composite key
   * (column {@code tran_type_cd}, {@code char(2)}).
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_type_cd", length = 2)
  private String tranTypeCd;

  /**
   * Transaction category code — COBOL {@code TRAN-CAT-CD PIC 9(04)} (enumerated code, leading zeros
   * preserved); second part of the composite key (column {@code tran_cat_cd}, {@code char(4)}).
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_cat_cd", length = 4)
  private String tranCatCd;

  /** Required no-arg constructor for JPA. */
  public TransactionCategoryId() {
    // Intentionally empty: JPA instantiates the key reflectively and populates fields via setters.
  }

  /**
   * Convenience all-args constructor enabling lookups such as {@code findById(new
   * TransactionCategoryId("01", "0001"))}.
   *
   * @param tranTypeCd the transaction type code ({@code TRAN-TYPE-CD}, length 2)
   * @param tranCatCd the transaction category code ({@code TRAN-CAT-CD}, length 4)
   */
  public TransactionCategoryId(String tranTypeCd, String tranCatCd) {
    this.tranTypeCd = tranTypeCd;
    this.tranCatCd = tranCatCd;
  }

  /**
   * Returns the transaction type code ({@code TRAN-TYPE-CD}).
   *
   * @return the transaction type code
   */
  public String getTranTypeCd() {
    return tranTypeCd;
  }

  /**
   * Sets the transaction type code ({@code TRAN-TYPE-CD}).
   *
   * @param tranTypeCd the transaction type code to set
   */
  public void setTranTypeCd(String tranTypeCd) {
    this.tranTypeCd = tranTypeCd;
  }

  /**
   * Returns the transaction category code ({@code TRAN-CAT-CD}).
   *
   * @return the transaction category code
   */
  public String getTranCatCd() {
    return tranCatCd;
  }

  /**
   * Sets the transaction category code ({@code TRAN-CAT-CD}).
   *
   * @param tranCatCd the transaction category code to set
   */
  public void setTranCatCd(String tranCatCd) {
    this.tranCatCd = tranCatCd;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TransactionCategoryId that = (TransactionCategoryId) o;
    return Objects.equals(tranTypeCd, that.tranTypeCd) && Objects.equals(tranCatCd, that.tranCatCd);
  }

  @Override
  public int hashCode() {
    return Objects.hash(tranTypeCd, tranCatCd);
  }
}
