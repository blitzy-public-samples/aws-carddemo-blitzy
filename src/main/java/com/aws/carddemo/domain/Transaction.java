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
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for the {@code transaction} table — the persistent transaction master, migrated from
 * the COBOL {@code 01}-level record {@code TRAN-RECORD} in copybook {@code
 * legacy/app/cpy/CVTRA05Y.cpy} (source-branch {@code app/cpy/CVTRA05Y.cpy}, {@code RECLN = 350}).
 * One Java type per copybook {@code 01}-level (AAP &sect;0.4.2).
 *
 * <p>In the legacy z/OS application this record was stored in the VSAM KSDS {@code TRANSACT}
 * (primary key {@code TRAN-ID}, {@code KEYLEN 16}). The dataset also carried a <em>non-unique</em>
 * alternate index keyed on the processing timestamp {@code TRAN-PROC-TS} (LISTCAT {@code TRANSACT}
 * AIX, {@code AXRKP 304}), used to retrieve transactions in processing-time order. That alternate
 * index is reproduced as the {@code ix_transaction_proc_ts} index created by the Flyway migration
 * {@code V1__schema.sql}; it is intentionally <strong>not</strong> declared here with
 * {@code @Index}, because the migration — not Hibernate — owns all schema objects (see below).
 *
 * <p><strong>Schema authority.</strong> The application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}, so Hibernate never generates DDL. The single source of
 * truth for every column name, type, length, precision and scale is {@code
 * src/main/resources/db/migration/V1__schema.sql}. The mappings below mirror that {@code
 * transaction} table exactly, in copybook order; any drift fails Hibernate schema validation at
 * startup.
 *
 * <p><strong>Fixed-width and decimal fidelity</strong> (AAP &sect;0.6.1, &sect;0.6.2):
 *
 * <ul>
 *   <li>All COBOL {@code X(n)} text/code fields and the {@code X(26)} timestamp fields map to
 *       {@link String} pinned to JDBC {@link SqlTypes#CHAR} so they bind to PostgreSQL {@code
 *       char(n)} (blank-padded fixed width). This preserves VSAM ordering/equality semantics and,
 *       for {@code tran_proc_ts}, keeps ISO-8601 text ordering equal to chronological ordering for
 *       the alternate index.
 *   <li>{@code TRAN-CAT-CD PIC 9(04)} is an enumerated category code with mandatory leading zeros,
 *       so it is modeled as a {@code char(4)} {@link String} — <strong>not</strong> a numeric type.
 *   <li>{@code TRAN-AMT PIC S9(09)V99} maps to {@link BigDecimal} with {@code precision = 11, scale
 *       = 2} (PostgreSQL {@code numeric(11,2)}); decimal money fields are <strong>never</strong>
 *       {@code float}/{@code double}.
 *   <li>{@code TRAN-MERCHANT-ID PIC 9(09)} is a genuine numeric identifier and maps to {@link Long}
 *       pinned to JDBC {@link SqlTypes#NUMERIC} with {@code precision = 9} (PostgreSQL {@code
 *       numeric(9)}).
 * </ul>
 *
 * <p>The trailing COBOL {@code FILLER PIC X(20)} carries no business data and is not modeled,
 * matching {@code V1__schema.sql}, which persists the 330 business bytes and omits the filler.
 *
 * <pre>
 *   column                type           COBOL PIC      Java field
 *   --------------------  -------------  -------------  ------------------
 *   tran_id (PK)          char(16)       X(16)          tranId
 *   tran_type_cd          char(2)        X(02)          tranTypeCd
 *   tran_cat_cd           char(4)        9(04)          tranCatCd
 *   tran_source           char(10)       X(10)          tranSource
 *   tran_desc             char(100)      X(100)         tranDesc
 *   tran_amt              numeric(11,2)  S9(09)V99      tranAmt
 *   tran_merchant_id      numeric(9)     9(09)          tranMerchantId
 *   tran_merchant_name    char(50)       X(50)          tranMerchantName
 *   tran_merchant_city    char(50)       X(50)          tranMerchantCity
 *   tran_merchant_zip     char(10)       X(10)          tranMerchantZip
 *   tran_card_num         char(16)       X(16)          tranCardNum
 *   tran_orig_ts          char(26)       X(26)          tranOrigTs
 *   tran_proc_ts          char(26)       X(26)          tranProcTs  (alt-indexed)
 * </pre>
 */
@Entity
@Table(name = "transaction")
@Getter
@Setter
@NoArgsConstructor
public class Transaction implements Serializable {

  /**
   * Serialization version. Declared explicitly because JPA entities may be detached and serialized
   * across tiers; the value is fixed at {@code 1L} for the stable record layout documented above.
   */
  private static final long serialVersionUID = 1L;

  /**
   * {@code TRAN-ID PIC X(16)} — primary key. The VSAM {@code TRANSACT} KSDS primary key ({@code
   * KEYLEN 16}); a fixed-width 16-character transaction identifier.
   */
  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_id", length = 16)
  private String tranId;

  /** {@code TRAN-TYPE-CD PIC X(02)} — transaction type code (debit/credit classification). */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_type_cd", length = 2)
  private String tranTypeCd;

  /**
   * {@code TRAN-CAT-CD PIC 9(04)} — transaction category code. An enumerated code with mandatory
   * leading zeros, modeled as fixed-width {@code char(4)} text (not numeric) for parity.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_cat_cd", length = 4)
  private String tranCatCd;

  /** {@code TRAN-SOURCE PIC X(10)} — originating source of the transaction. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_source", length = 10)
  private String tranSource;

  /** {@code TRAN-DESC PIC X(100)} — free-form transaction description. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_desc", length = 100)
  private String tranDesc;

  /**
   * {@code TRAN-AMT PIC S9(09)V99} — signed transaction amount. Fixed-point money mapped to {@link
   * BigDecimal} at {@code numeric(11,2)}; never {@code float}/{@code double} (AAP &sect;0.6.1).
   */
  @Column(name = "tran_amt", precision = 11, scale = 2)
  private BigDecimal tranAmt;

  /**
   * {@code TRAN-MERCHANT-ID PIC 9(09)} — merchant identifier. A genuine numeric id mapped to {@link
   * Long} at {@code numeric(9)}.
   */
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "tran_merchant_id", precision = 9)
  private Long tranMerchantId;

  /** {@code TRAN-MERCHANT-NAME PIC X(50)} — merchant name. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_merchant_name", length = 50)
  private String tranMerchantName;

  /** {@code TRAN-MERCHANT-CITY PIC X(50)} — merchant city. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_merchant_city", length = 50)
  private String tranMerchantCity;

  /** {@code TRAN-MERCHANT-ZIP PIC X(10)} — merchant postal code. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_merchant_zip", length = 10)
  private String tranMerchantZip;

  /** {@code TRAN-CARD-NUM PIC X(16)} — card number associated with the transaction. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_card_num", length = 16)
  private String tranCardNum;

  /**
   * {@code TRAN-ORIG-TS PIC X(26)} — origination timestamp stored as fixed-width ISO-8601 text (26
   * characters, e.g. {@code yyyy-MM-dd-HH.mm.ss.ffffff}).
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_orig_ts", length = 26)
  private String tranOrigTs;

  /**
   * {@code TRAN-PROC-TS PIC X(26)} — processing timestamp stored as fixed-width ISO-8601 text (26
   * characters). Backs the non-unique alternate index {@code ix_transaction_proc_ts} (LISTCAT
   * {@code TRANSACT} AIX, {@code AXRKP 304}); {@code char(26)} keeps text ordering equal to
   * chronological ordering.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_proc_ts", length = 26)
  private String tranProcTs;
}
