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
 * Composite primary key for {@link com.aws.carddemo.domain.DisclosureGroup} (table {@code
 * disclosure_group}).
 *
 * <p>Maps the COBOL {@code 05 DIS-GROUP-KEY} group of copybook {@code CVTRA02Y} (legacy source
 * {@code legacy/app/cpy/CVTRA02Y.cpy}), which is the key of the VSAM {@code DISCGRP} KSDS with a
 * composite KEYLEN of 16 bytes = {@code DIS-ACCT-GROUP-ID X(10)} + {@code DIS-TRAN-TYPE-CD X(02)} +
 * {@code DIS-TRAN-CAT-CD 9(04)}. Only the three {@code 10}-level fields under {@code DIS-GROUP-KEY}
 * are modeled here; the non-key {@code DIS-INT-RATE} field and the trailing {@code FILLER} belong
 * to the owning entity and are intentionally excluded (Agent Action Plan &sect;0.4.2 — one Java
 * type per copybook 01-level key group).
 *
 * <p>Every component is a fixed-length COBOL text/code part, so each maps to a {@link String}
 * column pinned with {@link JdbcTypeCode}({@link SqlTypes#CHAR}). The column names, lengths, and
 * JDBC type codes mirror the authoritative Flyway schema {@code V1__schema.sql} (table {@code
 * disclosure_group}, constraint {@code pk_disclosure_group}) field-for-field so that Hibernate
 * {@code spring.jpa.hibernate.ddl-auto=validate} succeeds at startup (AAP &sect;0.6.2 — VSAM ->
 * PostgreSQL key/index semantics). Note that {@code DIS-TRAN-CAT-CD} is {@code PIC 9(04)} in COBOL
 * yet is an enumerated category code carrying mandatory leading zeros; it is therefore persisted as
 * {@code char(4)} rather than a numeric type.
 *
 * <p>This class is referenced via {@link jakarta.persistence.EmbeddedId} by the sibling entity
 * {@code com.aws.carddemo.domain.DisclosureGroup}.
 */
@Embeddable
public class DisclosureGroupId implements Serializable {

  private static final long serialVersionUID = 1L;

  /** COBOL {@code DIS-ACCT-GROUP-ID PIC X(10)} -> column {@code dis_acct_group_id char(10)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dis_acct_group_id", length = 10)
  private String disAcctGroupId;

  /** COBOL {@code DIS-TRAN-TYPE-CD PIC X(02)} -> column {@code dis_tran_type_cd char(2)}. */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dis_tran_type_cd", length = 2)
  private String disTranTypeCd;

  /**
   * COBOL {@code DIS-TRAN-CAT-CD PIC 9(04)} (enumerated code) -> column {@code dis_tran_cat_cd
   * char(4)}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "dis_tran_cat_cd", length = 4)
  private String disTranCatCd;

  /** Creates an empty key. Required no-arg constructor for JPA. */
  public DisclosureGroupId() {
    // Intentionally empty; JPA populates the fields reflectively.
  }

  /**
   * Creates a fully populated composite key.
   *
   * @param disAcctGroupId the disclosure account group id ({@code DIS-ACCT-GROUP-ID})
   * @param disTranTypeCd the transaction type code ({@code DIS-TRAN-TYPE-CD})
   * @param disTranCatCd the transaction category code ({@code DIS-TRAN-CAT-CD})
   */
  public DisclosureGroupId(String disAcctGroupId, String disTranTypeCd, String disTranCatCd) {
    this.disAcctGroupId = disAcctGroupId;
    this.disTranTypeCd = disTranTypeCd;
    this.disTranCatCd = disTranCatCd;
  }

  public String getDisAcctGroupId() {
    return disAcctGroupId;
  }

  public void setDisAcctGroupId(String disAcctGroupId) {
    this.disAcctGroupId = disAcctGroupId;
  }

  public String getDisTranTypeCd() {
    return disTranTypeCd;
  }

  public void setDisTranTypeCd(String disTranTypeCd) {
    this.disTranTypeCd = disTranTypeCd;
  }

  public String getDisTranCatCd() {
    return disTranCatCd;
  }

  public void setDisTranCatCd(String disTranCatCd) {
    this.disTranCatCd = disTranCatCd;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DisclosureGroupId that = (DisclosureGroupId) o;
    return Objects.equals(disAcctGroupId, that.disAcctGroupId)
        && Objects.equals(disTranTypeCd, that.disTranTypeCd)
        && Objects.equals(disTranCatCd, that.disTranCatCd);
  }

  @Override
  public int hashCode() {
    return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
  }
}
