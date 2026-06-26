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
 * JPA entity for the {@code customer} table.
 *
 * <p>Direct translation of COBOL copybook {@code CVCUS01Y.cpy} ({@code 01 CUSTOMER-RECORD}, RECLN
 * 500), which describes the legacy VSAM {@code CUSTDATA} KSDS (primary key {@code CUST-ID}, KEYLEN
 * 9). One Java type is produced per copybook 01-level (AAP 0.4.2).
 *
 * <p>The authoritative column definitions live in {@code
 * src/main/resources/db/migration/V1__schema.sql}. The application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}, so every field below mirrors that migration exactly
 * (column name, JDBC type code, length/precision) or Hibernate schema validation fails at startup.
 *
 * <p>Type-code pinning (required for Hibernate 6 {@code validate}, which compares JDBC type codes):
 *
 * <ul>
 *   <li>COBOL {@code X(n)} text columns map to PostgreSQL {@code char(n)} (JDBC {@code CHAR}), so
 *       each {@link String} field is pinned with {@code @JdbcTypeCode(SqlTypes.CHAR)} and is read
 *       blank-padded for COBOL parity (the service layer trims as needed). A plain {@code String}
 *       would default to {@code VARCHAR} and fail validation.
 *   <li>COBOL genuine-numeric ids ({@code CUST-ID}, {@code CUST-SSN}, {@code
 *       CUST-FICO-CREDIT-SCORE}) map to PostgreSQL {@code numeric(n)} (JDBC {@code NUMERIC}), so
 *       each {@link Long} field is pinned with {@code @JdbcTypeCode(SqlTypes.NUMERIC)}. A plain
 *       {@code Long} would default to {@code BIGINT} and fail validation. Leading-zero display
 *       formatting of SSN and FICO is a service/DTO concern, never stored as text.
 * </ul>
 *
 * <p>The trailing COBOL {@code FILLER PIC X(168)} carries no business meaning and is intentionally
 * not modeled. The {@code customer} table has no alternate index.
 */
@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
public class Customer implements Serializable {

  private static final long serialVersionUID = 1L;

  /** {@code CUST-ID PIC 9(09)} - primary key; genuine numeric id. */
  @Id
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "cust_id", precision = 9, nullable = false)
  private Long custId;

  /** {@code CUST-FIRST-NAME PIC X(25)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_first_name", length = 25, nullable = false)
  private String custFirstName;

  /** {@code CUST-MIDDLE-NAME PIC X(25)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_middle_name", length = 25, nullable = false)
  private String custMiddleName;

  /** {@code CUST-LAST-NAME PIC X(25)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_last_name", length = 25, nullable = false)
  private String custLastName;

  /** {@code CUST-ADDR-LINE-1 PIC X(50)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_addr_line_1", length = 50, nullable = false)
  private String custAddrLine1;

  /** {@code CUST-ADDR-LINE-2 PIC X(50)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_addr_line_2", length = 50, nullable = false)
  private String custAddrLine2;

  /** {@code CUST-ADDR-LINE-3 PIC X(50)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_addr_line_3", length = 50, nullable = false)
  private String custAddrLine3;

  /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_addr_state_cd", length = 2, nullable = false)
  private String custAddrStateCd;

  /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_addr_country_cd", length = 3, nullable = false)
  private String custAddrCountryCd;

  /** {@code CUST-ADDR-ZIP PIC X(10)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_addr_zip", length = 10, nullable = false)
  private String custAddrZip;

  /** {@code CUST-PHONE-NUM-1 PIC X(15)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_phone_num_1", length = 15, nullable = false)
  private String custPhoneNum1;

  /** {@code CUST-PHONE-NUM-2 PIC X(15)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_phone_num_2", length = 15, nullable = false)
  private String custPhoneNum2;

  /**
   * {@code CUST-SSN PIC 9(09)} - genuine numeric; service/DTO formats leading zeros for display.
   */
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "cust_ssn", precision = 9, nullable = false)
  private Long custSsn;

  /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_govt_issued_id", length = 20, nullable = false)
  private String custGovtIssuedId;

  /** {@code CUST-DOB-YYYY-MM-DD PIC X(10)} - date stored as text 'YYYY-MM-DD' (not a date type). */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_dob_yyyy_mm_dd", length = 10, nullable = false)
  private String custDobYyyyMmDd;

  /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_eft_account_id", length = 10, nullable = false)
  private String custEftAccountId;

  /** {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "cust_pri_card_holder_ind", length = 1, nullable = false)
  private String custPriCardHolderInd;

  /**
   * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} - genuine numeric; service/DTO formats for display.
   */
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "cust_fico_credit_score", precision = 3, nullable = false)
  private Long custFicoCreditScore;
}
