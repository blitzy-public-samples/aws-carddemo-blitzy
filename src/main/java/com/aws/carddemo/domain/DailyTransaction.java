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
 * JPA entity mapping the {@code daily_transaction} table — the inbound daily transaction record.
 *
 * <p>Translated from COBOL copybook {@code legacy/app/cpy/CVTRA06Y.cpy} ({@code 01
 * DALYTRAN-RECORD}, RECLN 350) under the one-Java-type-per-copybook-01-level rule (Agent Action
 * Plan &sect;0.4.2). The record is read sequentially from the legacy {@code DALYTRAN} file and
 * consumed by the daily transaction posting batch ({@code app/cbl/CBTRN02C.cbl}).
 *
 * <p>This entity is a structural mirror of the {@code Transaction} entity (table {@code
 * transaction}) but uses the {@code dalytran_} column prefix and, unlike {@code transaction},
 * carries <strong>no alternate index</strong>: the authoritative schema declares only the single
 * primary key on {@code dalytran_id} for this table.
 *
 * <p>Type fidelity (Agent Action Plan &sect;0.6.1, &sect;0.6.2) is preserved against the
 * authoritative schema {@code src/main/resources/db/migration/V1__schema.sql}, which Hibernate
 * validates at startup ({@code spring.jpa.hibernate.ddl-auto=validate}):
 *
 * <ul>
 *   <li>every fixed-width COBOL {@code X(n)} field is a {@code char(n)} column pinned with {@link
 *       JdbcTypeCode}({@link SqlTypes#CHAR}) so VSAM ordering / equality and blank-padding
 *       semantics are preserved (this includes the timestamp fields, kept as fixed-width text, and
 *       the category code {@code DALYTRAN-CAT-CD}, kept as {@code char(4)} so mandatory leading
 *       zeros survive);
 *   <li>the monetary amount {@code DALYTRAN-AMT PIC S9(09)V99} is a {@code numeric(11,2)} {@link
 *       BigDecimal} — never a binary floating-point type — preserving exact decimal scale;
 *   <li>the merchant identifier {@code DALYTRAN-MERCHANT-ID PIC 9(09)} is a {@code numeric(9)}
 *       {@link Long} pinned with {@link JdbcTypeCode}({@link SqlTypes#NUMERIC}).
 * </ul>
 *
 * <p>The trailing COBOL {@code FILLER PIC X(20)} is positional padding with no business meaning and
 * is intentionally not modeled as a column. Every column is {@code NOT NULL} in the schema,
 * mirrored here with {@link Column#nullable()} set to {@code false}.
 */
@Entity
@Table(name = "daily_transaction")
@Getter
@Setter
@NoArgsConstructor
public class DailyTransaction implements Serializable {

  /** Serialization version identifier for this entity. */
  private static final long serialVersionUID = 1L;

  /**
   * {@code DALYTRAN-ID PIC X(16)} — primary key uniquely identifying the daily transaction record.
   */
  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_id", length = 16, nullable = false)
  private String dalytranId;

  /** {@code DALYTRAN-TYPE-CD PIC X(02)} — transaction type code. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_type_cd", length = 2, nullable = false)
  private String dalytranTypeCd;

  /**
   * {@code DALYTRAN-CAT-CD PIC 9(04)} — transaction category code. Stored as fixed-width {@code
   * char(4)} (not numeric) so mandatory leading zeros are preserved for cross-table consistency.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_cat_cd", length = 4, nullable = false)
  private String dalytranCatCd;

  /** {@code DALYTRAN-SOURCE PIC X(10)} — channel / source that originated the transaction. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_source", length = 10, nullable = false)
  private String dalytranSource;

  /** {@code DALYTRAN-DESC PIC X(100)} — free-text transaction description. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_desc", length = 100, nullable = false)
  private String dalytranDesc;

  /**
   * {@code DALYTRAN-AMT PIC S9(09)V99} — signed transaction amount with scale 2. Mapped to {@code
   * numeric(11,2)} via {@link BigDecimal}; never a floating-point type, to keep exact decimal
   * fidelity.
   */
  @Column(name = "dalytran_amt", precision = 11, scale = 2, nullable = false)
  private BigDecimal dalytranAmt;

  /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)} — genuine numeric merchant identifier. */
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "dalytran_merchant_id", precision = 9, nullable = false)
  private Long dalytranMerchantId;

  /** {@code DALYTRAN-MERCHANT-NAME PIC X(50)} — merchant name. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_merchant_name", length = 50, nullable = false)
  private String dalytranMerchantName;

  /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)} — merchant city. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_merchant_city", length = 50, nullable = false)
  private String dalytranMerchantCity;

  /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} — merchant ZIP / postal code. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_merchant_zip", length = 10, nullable = false)
  private String dalytranMerchantZip;

  /** {@code DALYTRAN-CARD-NUM PIC X(16)} — card number associated with the transaction. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_card_num", length = 16, nullable = false)
  private String dalytranCardNum;

  /**
   * {@code DALYTRAN-ORIG-TS PIC X(26)} — origination timestamp, kept as fixed-width text so its
   * byte layout and ordering match the legacy VSAM record.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_orig_ts", length = 26, nullable = false)
  private String dalytranOrigTs;

  /**
   * {@code DALYTRAN-PROC-TS PIC X(26)} — processing timestamp, kept as fixed-width text so its byte
   * layout and ordering match the legacy VSAM record.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dalytran_proc_ts", length = 26, nullable = false)
  private String dalytranProcTs;
}
