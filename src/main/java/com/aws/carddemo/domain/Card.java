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
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapping the {@code card} table — the modernized form of the legacy VSAM {@code
 * CARDDATA} KSDS. Migrated one-to-one from COBOL copybook {@code legacy/app/cpy/CVACT02Y.cpy}
 * (source-branch {@code app/cpy/CVACT02Y.cpy}), record {@code 01 CARD-RECORD} (RECLN 150). One Java
 * type per copybook {@code 01}-level (AAP §0.4.2).
 *
 * <p><strong>Schema authority.</strong> The application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}; Hibernate never generates DDL. The single source of
 * truth for every column name, type, length and key is {@code
 * src/main/resources/db/migration/V1__schema.sql} (the {@code card} table). This entity mirrors
 * that table <em>exactly</em>; any drift makes Hibernate {@code validate} fail at startup.
 *
 * <p><strong>Keys and indexes</strong> (AAP §0.6.2, derived from {@code app/catlg/LISTCAT.txt}).
 * The primary key is {@link #cardNum} ({@code CARD-NUM}, VSAM {@code CARDDATA} KSDS KEYLEN 16). The
 * {@link #cardAcctId} column backs the <em>non-unique</em> alternate index {@code ix_card_acct_id}
 * (LISTCAT {@code CARDDATA} AIX KEYLEN 11, AXRKP 16 — the alternate key begins at byte offset 16,
 * immediately after the 16-byte card number; multiple cards may share one account id). That index
 * is created by {@code V1__schema.sql}, <em>not</em> by this entity — no {@code @Table(indexes =
 * ...)} / {@code @Index} is declared here (Hibernate {@code validate} does not inspect indexes, so
 * the entity is kept clean).
 *
 * <p><strong>Type fidelity</strong> (AAP §0.6.1, §0.6.2). COBOL {@code X(n)} text fields map to
 * fixed-width {@code char(n)} columns pinned to {@link SqlTypes#CHAR} via {@link JdbcTypeCode}, so
 * the VSAM blank-padding, ordering and equality semantics are preserved (a default {@code String}
 * mapping would emit {@code varchar} and fail {@code validate} against a {@code char} column).
 * {@link #cardAcctId} is the only genuine numeric field: COBOL {@code 9(11)} maps to {@code
 * numeric(11)} and is modeled as a {@link Long} pinned to {@link SqlTypes#NUMERIC}. {@link
 * #cardCvvCd}, although declared {@code PIC 9(03)} in COBOL, is a {@code char(3)} {@link String}
 * because the CVV carries mandatory leading zeros and is never used in arithmetic. No {@code
 * float}/{@code double} is used anywhere.
 *
 * <p><strong>Preserved legacy spelling.</strong> The field {@link #cardExpiraionDate} keeps the
 * copybook's misspelling "expiraion" (column {@code card_expiraion_date}) verbatim for exact
 * traceability with the source copybook and the V1 schema.
 *
 * <p>The trailing COBOL {@code FILLER PIC X(59)} of {@code CARD-RECORD} is positional padding with
 * no business meaning and is intentionally not modeled as a column (AAP §0.4.1; fixed-width record
 * reconstruction for golden-file parity is handled by the file-I/O layer, not the database). There
 * is no optimistic-lock column in {@code V1__schema.sql}, so this entity declares no
 * {@code @Version}; the legacy read-for-update / REWRITE concurrency parity (AAP §0.3.3) is handled
 * in the service layer.
 */
@Entity
@Table(name = "card")
@Getter
@Setter
@NoArgsConstructor
public class Card implements Serializable {

  /** Serialization version for this entity; the layout is stable at version {@code 1}. */
  private static final long serialVersionUID = 1L;

  /**
   * {@code CARD-NUM PIC X(16)} → {@code card_num char(16)} — the primary key. The 16-character card
   * number and the original VSAM {@code CARDDATA} KSDS primary key (LISTCAT KEYLEN 16). Pinned to
   * {@link SqlTypes#CHAR} so the fixed-width, blank-padded VSAM ordering/equality semantics are
   * preserved.
   */
  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "card_num", length = 16, nullable = false)
  private String cardNum;

  /**
   * {@code CARD-ACCT-ID PIC 9(11)} → {@code card_acct_id numeric(11)}. The owning account id, a
   * genuine numeric identifier modeled as {@link Long} to stay consistent with {@code
   * Account.acctId} (the relationship is FK-by-convention only — the legacy VSAM enforced no
   * referential integrity, so there is no database foreign key). This column backs the non-unique
   * alternate index {@code ix_card_acct_id} created by {@code V1__schema.sql}.
   */
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "card_acct_id", precision = 11, nullable = false)
  private Long cardAcctId;

  /**
   * {@code CARD-CVV-CD PIC 9(03)} → {@code card_cvv_cd char(3)}. The card verification value.
   * Although the COBOL PIC is numeric, the CVV carries mandatory leading zeros and is never used in
   * arithmetic, so it is modeled as a fixed-width {@link String} ({@code char(3)}), not a number —
   * matching the authoritative {@code V1__schema.sql} mapping.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "card_cvv_cd", length = 3, nullable = false)
  private String cardCvvCd;

  /**
   * {@code CARD-EMBOSSED-NAME PIC X(50)} → {@code card_embossed_name char(50)}. The cardholder name
   * embossed on the physical card, stored as fixed-width text.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "card_embossed_name", length = 50, nullable = false)
  private String cardEmbossedName;

  /**
   * {@code CARD-EXPIRAION-DATE PIC X(10)} → {@code card_expiraion_date char(10)}. The card
   * expiration date stored as fixed-width text. <strong>The legacy misspelling "expiraion" (missing
   * the second "t") is preserved verbatim</strong> from the copybook field name and the {@code
   * V1__schema.sql} column name for exact traceability.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "card_expiraion_date", length = 10, nullable = false)
  private String cardExpiraionDate;

  /**
   * {@code CARD-ACTIVE-STATUS PIC X(01)} → {@code card_active_status char(1)}. The single-character
   * active-status flag (for example {@code 'Y'} active / {@code 'N'} inactive).
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "card_active_status", length = 1, nullable = false)
  private String cardActiveStatus;
}
