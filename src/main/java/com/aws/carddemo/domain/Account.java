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
 * JPA entity mapping the legacy VSAM {@code ACCTDATA} KSDS account master, migrated from COBOL
 * copybook {@code CVACT01Y} (legacy source {@code legacy/app/cpy/CVACT01Y.cpy}; {@code 01
 * ACCOUNT-RECORD}, RECLN 300). Per Agent Action Plan &sect;0.4.2, exactly one Java type is produced
 * per copybook 01-level, so legacy {@code COPY CVACT01Y} call sites translate to {@code import
 * com.aws.carddemo.domain.Account}.
 *
 * <p>The entity maps the {@code account} table whose <strong>authoritative</strong> definition
 * lives in the Flyway migration {@code src/main/resources/db/migration/V1__schema.sql}. The
 * application runs with {@code spring.jpa.hibernate.ddl-auto=validate}, so Hibernate never
 * generates DDL: every column name, SQL type, length, precision and scale declared below mirrors
 * that migration exactly. Any drift between this entity and {@code V1__schema.sql} makes Hibernate
 * schema validation fail at startup, so the migration — not this class — is the single source of
 * truth.
 *
 * <p><strong>Preserved interleaved column order.</strong> The three date columns ({@code
 * acct_open_date}, {@code acct_expiraion_date}, {@code acct_reissue_date}) intentionally sit
 * <em>between</em> the two monetary balance/limit groups, reproducing the exact field order of the
 * copybook rather than grouping like-typed columns together.
 *
 * <p><strong>Preserved legacy misspelling.</strong> The copybook field {@code ACCT-EXPIRAION-DATE}
 * (CVACT01Y line 11) is misspelled "expiraion". Both the Java field {@link #acctExpiraionDate} and
 * the column {@code acct_expiraion_date} retain that spelling verbatim for traceability; it is
 * deliberately <em>not</em> corrected to "expiration".
 *
 * <p><strong>Decimal fidelity.</strong> The five monetary balance/limit fields are COBOL
 * fixed-point {@code PIC S9(10)V99} values and map to {@link java.math.BigDecimal} with {@code
 * precision = 12, scale = 2} — never {@code float}/{@code double} (AAP &sect;0.6.1). This entity is
 * a pure data holder: COBOL {@code COMPUTE} truncation/rounding semantics (for example the interest
 * calculation {@code (bal * rate) / 1200} with {@link java.math.RoundingMode#DOWN}) are the
 * responsibility of the service layer, not of this entity.
 *
 * <p><strong>Type-code pinning.</strong> {@code char(n)} columns are pinned with {@link
 * org.hibernate.type.SqlTypes#CHAR} so Hibernate maps {@link String} to JDBC {@code CHAR} (not
 * {@code VARCHAR}); the genuine numeric primary key {@code acct_id} is pinned with {@link
 * org.hibernate.type.SqlTypes#NUMERIC} so {@link Long} maps to JDBC {@code NUMERIC} (not {@code
 * BIGINT}). Both pins are required for Hibernate 6 {@code validate} to succeed against the
 * PostgreSQL {@code char(n)} and {@code numeric(11)} columns. Values read back from {@code char(n)}
 * columns are blank-padded, matching COBOL fixed-width semantics (AAP &sect;0.6.2).
 *
 * <p>The trailing COBOL {@code FILLER PIC X(178)} carries no business meaning and is intentionally
 * not modeled. The {@code account} table has no alternate index, so no {@code @Index} is declared,
 * and {@code V1__schema.sql} has no version column, so optimistic-locking parity (the legacy
 * READ-for-update / REWRITE pattern, AAP &sect;0.3.3) is deferred to the service layer — no
 * {@code @Version} field is added here, which would otherwise break {@code validate}.
 */
@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor
public class Account implements Serializable {

  /** Serialization version identifier for this entity. */
  private static final long serialVersionUID = 1L;

  /**
   * Account identifier and primary key. COBOL {@code ACCT-ID PIC 9(11)} (genuine numeric id); maps
   * the VSAM {@code ACCTDATA} KSDS key (KEYLEN 11). Pinned to JDBC {@code NUMERIC} so the {@link
   * Long} validates against the {@code numeric(11)} column.
   */
  @Id
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "acct_id", precision = 11, nullable = false)
  private Long acctId;

  /** COBOL {@code ACCT-ACTIVE-STATUS PIC X(01)}; fixed-width {@code char(1)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "acct_active_status", length = 1, nullable = false)
  private String acctActiveStatus;

  /** Current account balance. COBOL {@code ACCT-CURR-BAL PIC S9(10)V99}; {@code numeric(12,2)}. */
  @Column(name = "acct_curr_bal", precision = 12, scale = 2, nullable = false)
  private BigDecimal acctCurrBal;

  /** Account credit limit. COBOL {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}; {@code numeric(12,2)}. */
  @Column(name = "acct_credit_limit", precision = 12, scale = 2, nullable = false)
  private BigDecimal acctCreditLimit;

  /**
   * Cash credit limit. COBOL {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}; {@code numeric(12,2)}.
   */
  @Column(name = "acct_cash_credit_limit", precision = 12, scale = 2, nullable = false)
  private BigDecimal acctCashCreditLimit;

  /**
   * Account open date (text). COBOL {@code ACCT-OPEN-DATE PIC X(10)}; fixed-width {@code char(10)}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "acct_open_date", length = 10, nullable = false)
  private String acctOpenDate;

  /**
   * Account expiration date (text). COBOL {@code ACCT-EXPIRAION-DATE PIC X(10)} (legacy "expiraion"
   * misspelling preserved verbatim); fixed-width {@code char(10)}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "acct_expiraion_date", length = 10, nullable = false)
  private String acctExpiraionDate;

  /**
   * Account reissue date (text). COBOL {@code ACCT-REISSUE-DATE PIC X(10)}; fixed-width {@code
   * char(10)}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "acct_reissue_date", length = 10, nullable = false)
  private String acctReissueDate;

  /**
   * Current cycle credit total. COBOL {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}; {@code
   * numeric(12,2)}.
   */
  @Column(name = "acct_curr_cyc_credit", precision = 12, scale = 2, nullable = false)
  private BigDecimal acctCurrCycCredit;

  /**
   * Current cycle debit total. COBOL {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}; {@code
   * numeric(12,2)}.
   */
  @Column(name = "acct_curr_cyc_debit", precision = 12, scale = 2, nullable = false)
  private BigDecimal acctCurrCycDebit;

  /**
   * Account address ZIP code. COBOL {@code ACCT-ADDR-ZIP PIC X(10)}; fixed-width {@code char(10)}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "acct_addr_zip", length = 10, nullable = false)
  private String acctAddrZip;

  /**
   * Account group identifier. COBOL {@code ACCT-GROUP-ID PIC X(10)}; fixed-width {@code char(10)}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "acct_group_id", length = 10, nullable = false)
  private String acctGroupId;
}
