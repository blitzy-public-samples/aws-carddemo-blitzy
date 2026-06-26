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

import com.aws.carddemo.domain.id.TransactionCategoryId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for the {@code tran_category} reference table — the transaction-category lookup that
 * maps a (transaction-type, category) code pair to its human-readable description.
 *
 * <p><strong>Origin.</strong> Migrated from the legacy z/OS COBOL copybook {@code
 * legacy/app/cpy/CVTRA04Y.cpy} (source-branch {@code app/cpy/CVTRA04Y.cpy}), record {@code 01
 * TRAN-CAT-RECORD} with a fixed record length of {@code RECLN = 60}. Per AAP §0.4.2 ("one Java type
 * per copybook {@code 01}-level"), this single copybook group becomes this single entity. The
 * trailing {@code FILLER PIC X(04)} is positional padding with no business meaning and is
 * intentionally <em>not</em> modeled as a column — fixed-width record reconstruction for
 * golden-file parity is the responsibility of the file-I/O layer, not the database.
 *
 * <p><strong>Copybook field layout.</strong>
 *
 * <pre>
 *   offset  len  field               COBOL PIC      JPA mapping
 *   ------  ---  ------------------  -------------  ---------------------------------------------
 *        0    2  TRAN-TYPE-CD        PIC X(02)      {@link TransactionCategoryId#getTranTypeCd()}
 *        2    4  TRAN-CAT-CD         PIC 9(04)      {@link TransactionCategoryId#getTranCatCd()}
 *        6   50  TRAN-CAT-TYPE-DESC  PIC X(50)      {@link #tranCatTypeDesc} (char(50))
 *       56    4  FILLER              PIC X(04)      (not modeled)
 *   ------  ---
 *       60  total record width (RECLN)
 * </pre>
 *
 * <p>The COBOL {@code 05 TRAN-CAT-KEY} group (the first two fields) is the VSAM key of the {@code
 * TRANCATG} dataset; it is modeled as the {@code @EmbeddedId} composite key {@link
 * TransactionCategoryId} with a combined <strong>KEYLEN 6</strong> (2 + 4), matching the VSAM
 * catalog definition (AAP §0.6.2). The key column mappings live exclusively in that
 * {@code @Embeddable} class and are deliberately <em>not</em> duplicated on this entity.
 *
 * <p><strong>Schema authority.</strong> The application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}, so Hibernate never generates DDL; the single source of
 * truth for the schema is the Flyway migration {@code
 * src/main/resources/db/migration/V1__schema.sql} (table {@code tran_category}). Every mapping
 * mirrors that DDL exactly so Hibernate schema validation passes at startup:
 *
 * <ul>
 *   <li>{@code tran_type_cd} — {@code char(2)} {@code NOT NULL} (composite key part 1).
 *   <li>{@code tran_cat_cd} — {@code char(4)} {@code NOT NULL} (composite key part 2).
 *   <li>{@code tran_cat_type_desc} — {@code char(50)} {@code NOT NULL}.
 *   <li>{@code PRIMARY KEY (tran_type_cd, tran_cat_cd)} — composite, KEYLEN 6.
 * </ul>
 *
 * <p><strong>Fixed-width parity (AAP §0.6.2).</strong> The non-key {@code tran_cat_type_desc}
 * column is PostgreSQL {@code char(50)} (blank-padded {@code bpchar}), which reports JDBC type
 * {@link SqlTypes#CHAR}. The {@link String} field is therefore pinned with {@link
 * JdbcTypeCode @JdbcTypeCode(SqlTypes.CHAR)}; a plain {@code String} would default to {@code
 * VARCHAR} and make Hibernate {@code validate} fail. Values read back from a {@code char(n)} column
 * are space-padded to the declared width, preserving VSAM ordering and equality semantics;
 * trimming, where a caller needs it, is the service layer's responsibility.
 *
 * <p>This is a small, immutable-in-practice reference table seeded by {@code
 * V2__seed_reference_data.sql}; it has no monetary fields, no version column, and no alternate
 * index.
 */
@Entity
@Table(name = "tran_category")
@Getter
@Setter
@NoArgsConstructor
public class TransactionCategory implements Serializable {

  /** Serialization version identifier (standard JPA entity practice). */
  private static final long serialVersionUID = 1L;

  /**
   * Composite primary key — COBOL {@code 05 TRAN-CAT-KEY} group of {@code CVTRA04Y}, combining
   * {@code TRAN-TYPE-CD PIC X(02)} (column {@code tran_type_cd char(2)}) and {@code TRAN-CAT-CD PIC
   * 9(04)} (column {@code tran_cat_cd char(4)}) for a combined KEYLEN of 6. The individual column
   * mappings — each pinned to JDBC {@link SqlTypes#CHAR} — are defined solely on {@link
   * TransactionCategoryId} and are intentionally not repeated here to avoid duplicate mappings.
   */
  @EmbeddedId private TransactionCategoryId id;

  /**
   * Transaction-category description — COBOL {@code TRAN-CAT-TYPE-DESC PIC X(50)}; column {@code
   * tran_cat_type_desc char(50) NOT NULL}. Pinned to JDBC {@link SqlTypes#CHAR} so the mapping
   * matches the PostgreSQL {@code char(50)} (blank-padded) column under Hibernate {@code validate}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_cat_type_desc", length = 50, nullable = false)
  private String tranCatTypeDesc;
}
