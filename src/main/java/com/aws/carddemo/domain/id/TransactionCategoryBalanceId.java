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
 * Composite primary key for {@link com.aws.carddemo.domain.TransactionCategoryBalance} (table
 * {@code tran_cat_balance}).
 *
 * <p>Maps the COBOL {@code 05 TRAN-CAT-KEY} group of copybook {@code CVTRA01Y} (legacy source
 * {@code legacy/app/cpy/CVTRA01Y.cpy}, source-branch {@code app/cpy/CVTRA01Y.cpy}). In the legacy
 * z/OS application this was the key of the VSAM KSDS {@code TCATBALF}, whose composite key length
 * is {@code KEYLEN 17 = 11 + 2 + 4}: {@code TRANCAT-ACCT-ID PIC 9(11)} + {@code TRANCAT-TYPE-CD PIC
 * X(02)} + {@code TRANCAT-CD PIC 9(04)} (Agent Action Plan &sect;0.6.2). Per AAP &sect;0.4.2
 * exactly one Java type is produced for this copybook key group.
 *
 * <p>The non-key field {@code TRAN-CAT-BAL PIC S9(09)V99} and the trailing {@code FILLER PIC X(22)}
 * are intentionally <em>not</em> modeled here: the balance belongs to the owning entity {@code
 * TransactionCategoryBalance}, and COBOL FILLER is positional padding with no business meaning.
 *
 * <p><strong>Schema authority.</strong> The Spring Boot application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}, so the authoritative source of truth is the Flyway
 * migration {@code src/main/resources/db/migration/V1__schema.sql}. The column names, JDBC type
 * codes, and lengths/precision below mirror the {@code tran_cat_balance} composite primary key in
 * that migration field-for-field — {@code PRIMARY KEY (trancat_acct_id, trancat_type_cd,
 * trancat_cd)} over {@code numeric(11)}, {@code char(2)}, {@code char(4)} — so Hibernate 6 schema
 * validation (which compares JDBC type codes) succeeds at startup. The {@link JdbcTypeCode} pins
 * are mandatory: a plain {@code String} would default to {@code VARCHAR} and a plain {@code Long}
 * to {@code BIGINT}, either of which would fail validation against the {@code char}/{@code numeric}
 * columns.
 *
 * <p>{@code trancatCd} originates from COBOL {@code PIC 9(04)} but is an enumerated transaction
 * category code with mandatory leading zeros; per the authoritative schema it is therefore a
 * fixed-length {@code char(4)} {@code String} (not a numeric type) so VSAM ordering and equality
 * are preserved.
 */
@Embeddable
public class TransactionCategoryBalanceId implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * COBOL {@code TRANCAT-ACCT-ID PIC 9(11)} — genuine numeric account id; V1 {@code numeric(11)}.
   */
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "trancat_acct_id", precision = 11)
  private Long trancatAcctId;

  /** COBOL {@code TRANCAT-TYPE-CD PIC X(02)} — transaction type code; V1 {@code char(2)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "trancat_type_cd", length = 2)
  private String trancatTypeCd;

  /**
   * COBOL {@code TRANCAT-CD PIC 9(04)} — enumerated category code with mandatory leading zeros; V1
   * {@code char(4)} (modeled as fixed-length {@code String}, never numeric).
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "trancat_cd", length = 4)
  private String trancatCd;

  /** Required no-arg constructor for JPA. */
  public TransactionCategoryBalanceId() {
    // Intentionally empty: JPA instantiates the embeddable reflectively.
  }

  /**
   * Convenience all-args constructor used by repository lookups, e.g. {@code findById(new
   * TransactionCategoryBalanceId(1L, "01", "0001"))}.
   *
   * @param trancatAcctId the account id ({@code TRANCAT-ACCT-ID})
   * @param trancatTypeCd the transaction type code ({@code TRANCAT-TYPE-CD})
   * @param trancatCd the transaction category code ({@code TRANCAT-CD})
   */
  public TransactionCategoryBalanceId(Long trancatAcctId, String trancatTypeCd, String trancatCd) {
    this.trancatAcctId = trancatAcctId;
    this.trancatTypeCd = trancatTypeCd;
    this.trancatCd = trancatCd;
  }

  public Long getTrancatAcctId() {
    return trancatAcctId;
  }

  public void setTrancatAcctId(Long trancatAcctId) {
    this.trancatAcctId = trancatAcctId;
  }

  public String getTrancatTypeCd() {
    return trancatTypeCd;
  }

  public void setTrancatTypeCd(String trancatTypeCd) {
    this.trancatTypeCd = trancatTypeCd;
  }

  public String getTrancatCd() {
    return trancatCd;
  }

  public void setTrancatCd(String trancatCd) {
    this.trancatCd = trancatCd;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) o;
    return Objects.equals(trancatAcctId, that.trancatAcctId)
        && Objects.equals(trancatTypeCd, that.trancatTypeCd)
        && Objects.equals(trancatCd, that.trancatCd);
  }

  @Override
  public int hashCode() {
    return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
  }
}
