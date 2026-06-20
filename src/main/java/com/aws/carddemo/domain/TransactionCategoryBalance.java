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

import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity mapping the legacy VSAM {@code TCATBALF} KSDS transaction-category-balance store,
 * migrated from COBOL copybook {@code CVTRA01Y} (legacy source {@code legacy/app/cpy/CVTRA01Y.cpy},
 * source-branch {@code app/cpy/CVTRA01Y.cpy}; record {@code 01 TRAN-CAT-BAL-RECORD}, RECLN 50). Per
 * Agent Action Plan &sect;0.4.2 ("one Java type per copybook {@code 01}-level"), this single
 * copybook record becomes this single entity, so legacy {@code COPY CVTRA01Y} call sites translate
 * to {@code import com.aws.carddemo.domain.TransactionCategoryBalance}.
 *
 * <p>The row stores the running balance accumulated for one (account, transaction-type,
 * transaction-category) combination — the per-category subtotal that the daily posting and interest
 * jobs read, upsert and rewrite.
 *
 * <p><strong>Copybook field layout.</strong>
 *
 * <pre>
 *   offset  len  field             COBOL PIC       JPA mapping
 *   ------  ---  ----------------  --------------  --------------------------------------------
 *        0   17  TRAN-CAT-KEY      (group)         {@link #id} (&#64;EmbeddedId, composite PK)
 *        0   11   TRANCAT-ACCT-ID  PIC 9(11)        id.trancatAcctId  (numeric(11))
 *       11    2   TRANCAT-TYPE-CD  PIC X(02)        id.trancatTypeCd  (char(2))
 *       13    4   TRANCAT-CD       PIC 9(04)        id.trancatCd      (char(4), leading zeros)
 *       17   11  TRAN-CAT-BAL      PIC S9(09)V99   {@link #tranCatBal} (numeric(11,2))
 *       28   22  FILLER            PIC X(22)       (not modeled)
 *   ------  ---
 *       50  total record width (RECLN)
 * </pre>
 *
 * <p><strong>Composite primary key (KEYLEN 17).</strong> The copybook {@code 05 TRAN-CAT-KEY} group
 * is the VSAM key (key length {@code 17 = 11 + 2 + 4}) and is modeled as the embeddable {@link
 * TransactionCategoryBalanceId} referenced through {@link #id}. The three key columns — {@code
 * trancat_acct_id}, {@code trancat_type_cd}, {@code trancat_cd} — together with their JDBC
 * type-code pins are declared <em>only</em> on that {@code @Embeddable} class; they are
 * deliberately not re-annotated here to avoid duplicate column mappings (Agent Action Plan
 * &sect;0.6.2).
 *
 * <p><strong>Schema authority.</strong> The application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}, so Hibernate never generates DDL; the single source of
 * truth for the schema is the Flyway migration {@code
 * src/main/resources/db/migration/V1__schema.sql} (table {@code tran_cat_balance}). Every mapping
 * below mirrors that DDL exactly so Hibernate schema validation passes at startup:
 *
 * <ul>
 *   <li>{@code trancat_acct_id} — {@code numeric(11)} {@code NOT NULL} (in the Id class).
 *   <li>{@code trancat_type_cd} — {@code char(2)} {@code NOT NULL} (in the Id class).
 *   <li>{@code trancat_cd} — {@code char(4)} {@code NOT NULL} (in the Id class).
 *   <li>{@code tran_cat_bal} — {@code numeric(11,2)} {@code NOT NULL}, declared here.
 *   <li>{@code PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)}.
 * </ul>
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> {@code TRAN-CAT-BAL} is a COBOL
 * fixed-point {@code PIC S9(09)V99} value (9 integer digits plus 2 fraction digits) and therefore
 * maps to {@link java.math.BigDecimal} with {@code precision = 11, scale = 2} — never {@code
 * float}/{@code double}. As a {@code numeric} column it needs no {@link
 * org.hibernate.annotations.JdbcTypeCode} pin (Hibernate's default {@code BigDecimal} mapping is
 * already JDBC {@code NUMERIC}). This entity is a pure data holder: the COBOL truncation/rounding
 * arithmetic that maintains the balance (for example the un-{@code ROUNDED} interest computation
 * {@code (bal * rate) / 1200} with {@link java.math.RoundingMode#DOWN}) is the responsibility of
 * the service layer, not of this entity.
 *
 * <p><strong>No optimistic-locking / no alternate index.</strong> Although {@code TCATBAL} is
 * maintained with the legacy READ-for-update / REWRITE and find-or-create (upsert) pattern (AAP
 * &sect;0.3.3, &sect;0.6.4), {@code V1__schema.sql} has no version column and no alternate index
 * for this table. Adding a {@code @Version} field would introduce a column that does not exist and
 * break {@code validate}, so optimistic-locking and upsert parity are deferred to the service
 * layer; no {@code @Version} and no {@code @Index} are declared here.
 *
 * <p>The trailing COBOL {@code FILLER PIC X(22)} carries no business meaning and is intentionally
 * not modeled as a column; fixed-width record reconstruction for golden-file parity, where needed,
 * is the responsibility of the file-I/O layer rather than the database.
 */
@Entity
@Table(name = "tran_cat_balance")
@Getter
@Setter
@NoArgsConstructor
public class TransactionCategoryBalance implements Serializable {

  /** Serialization version identifier for this entity. */
  private static final long serialVersionUID = 1L;

  /**
   * Composite primary key. Maps the COBOL {@code 05 TRAN-CAT-KEY} group of copybook {@code
   * CVTRA01Y} (KEYLEN 17): {@code TRANCAT-ACCT-ID PIC 9(11)} + {@code TRANCAT-TYPE-CD PIC X(02)} +
   * {@code TRANCAT-CD PIC 9(04)}. The individual key columns and their JDBC type-code pins are
   * declared on the {@link TransactionCategoryBalanceId} {@code @Embeddable}, so they are not
   * repeated on this entity.
   */
  @EmbeddedId private TransactionCategoryBalanceId id;

  /**
   * Accumulated category balance for this (account, type, category) key. COBOL {@code TRAN-CAT-BAL
   * PIC S9(09)V99}; column {@code tran_cat_bal numeric(11,2) NOT NULL}. Held as {@link BigDecimal}
   * (never {@code float}/{@code double}) so the two-decimal monetary scale and COBOL truncation
   * semantics are preserved exactly (AAP &sect;0.6.1).
   */
  @Column(name = "tran_cat_bal", precision = 11, scale = 2, nullable = false)
  private BigDecimal tranCatBal;
}
