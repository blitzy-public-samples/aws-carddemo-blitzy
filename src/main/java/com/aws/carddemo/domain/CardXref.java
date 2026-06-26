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
 * JPA entity for the {@code card_xref} table — the card-to-customer/account cross-reference store.
 *
 * <p>This is the modernized, one-to-one Java translation of the legacy COBOL copybook {@code
 * app/cpy/CVACT03Y.cpy} ({@code 01 CARD-XREF-RECORD}, record length 50), honouring the Agent Action
 * Plan rule of exactly one Java type per copybook 01-level. It maps the legacy VSAM {@code
 * CARDXREF} KSDS, whose primary key was {@code XREF-CARD-NUM} (KEYLEN 16).
 *
 * <p><strong>Schema authority.</strong> The application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}, so this entity must mirror the {@code card_xref} table
 * defined in {@code src/main/resources/db/migration/V1__schema.sql} exactly, or Hibernate fails
 * fast at startup. The three business columns are modelled in copybook order; the trailing COBOL
 * {@code FILLER PIC X(14)} is intentionally not modelled.
 *
 * <p><strong>Alternate index.</strong> {@link #xrefAcctId} backs the non-unique alternate index
 * {@code ix_card_xref_acct_id} (legacy VSAM {@code CARDXREF} AIX: KEYLEN 11, AXRKP 25, NONUNIQKEY).
 * That index is created by the V1 Flyway migration — never by this entity — so no {@code @Index} or
 * {@code @Table(indexes = ...)} metadata is declared here.
 *
 * <p>Per the migration's decimal-fidelity rules the identifier columns use {@link Long} pinned to
 * {@link SqlTypes#NUMERIC} (never {@code float}/{@code double}); the fixed-width card number uses
 * {@link String} pinned to {@link SqlTypes#CHAR} so blank-padding and ordering match the original
 * VSAM key semantics.
 */
@Entity
@Table(name = "card_xref")
@Getter
@Setter
@NoArgsConstructor
public class CardXref implements Serializable {

  /** Serialization version identifier for this entity. */
  private static final long serialVersionUID = 1L;

  /**
   * Card number — primary key. Legacy {@code XREF-CARD-NUM PIC X(16)}; persisted as a fixed-length
   * {@code char(16)} column so blank-padding and ordering match the original VSAM key semantics.
   */
  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "xref_card_num", length = 16)
  private String xrefCardNum;

  /**
   * Customer identifier the card is associated with. Legacy {@code XREF-CUST-ID PIC 9(09)}; a
   * genuine numeric id persisted as {@code numeric(9)}.
   */
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "xref_cust_id", precision = 9)
  private Long xrefCustId;

  /**
   * Account identifier the card is associated with. Legacy {@code XREF-ACCT-ID PIC 9(11)}; a
   * genuine numeric id persisted as {@code numeric(11)}. Backs the non-unique alternate index
   * {@code ix_card_xref_acct_id}.
   */
  @JdbcTypeCode(SqlTypes.NUMERIC)
  @Column(name = "xref_acct_id", precision = 11)
  private Long xrefAcctId;
}
