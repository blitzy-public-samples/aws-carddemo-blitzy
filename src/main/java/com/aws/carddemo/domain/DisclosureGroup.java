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

import com.aws.carddemo.domain.id.DisclosureGroupId;
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
 * JPA entity for the {@code disclosure_group} reference table — the interest-rate lookup that, for
 * a given disclosure account group, transaction type, and transaction category, yields the annual
 * disclosure interest rate used by the interest-calculation batch job.
 *
 * <p><strong>Origin.</strong> Migrated from the legacy z/OS COBOL copybook {@code
 * legacy/app/cpy/CVTRA02Y.cpy} (source-branch {@code app/cpy/CVTRA02Y.cpy}), record {@code 01
 * DIS-GROUP-RECORD} with a fixed record length of {@code RECLN = 50}. It maps the VSAM {@code
 * DISCGRP} KSDS, whose composite key has a {@code KEYLEN} of 16 bytes ({@code DIS-ACCT-GROUP-ID
 * X(10)} + {@code DIS-TRAN-TYPE-CD X(02)} + {@code DIS-TRAN-CAT-CD 9(04)} = 10 + 2 + 4). Per AAP
 * §0.4.2 ("one Java type per copybook {@code 01}-level"), this single copybook group becomes this
 * single entity. The trailing {@code FILLER PIC X(28)} of the copybook is positional padding with
 * no business meaning and is intentionally <em>not</em> modeled as a column.
 *
 * <p><strong>Copybook field layout.</strong>
 *
 * <pre>
 *   offset  len  field               COBOL PIC        mapping
 *   ------  ---  ------------------  ---------------  -------------------------------------------
 *        0   10  DIS-ACCT-GROUP-ID   PIC X(10)        {@link DisclosureGroupId#getDisAcctGroupId()}
 *       10    2  DIS-TRAN-TYPE-CD    PIC X(02)        {@link DisclosureGroupId#getDisTranTypeCd()}
 *       12    4  DIS-TRAN-CAT-CD     PIC 9(04)        {@link DisclosureGroupId#getDisTranCatCd()}
 *       16    6  DIS-INT-RATE        PIC S9(04)V99    {@link #disIntRate} (numeric(6,2))
 *       22   28  FILLER              PIC X(28)        (not modeled)
 *   ------  ---
 *       50  total record width (RECLN)
 * </pre>
 *
 * <p><strong>Composite primary key.</strong> The COBOL {@code 05 DIS-GROUP-KEY} group maps to the
 * embeddable {@link DisclosureGroupId} (referenced via {@link EmbeddedId}), which owns the three
 * key-column mappings ({@code dis_acct_group_id}, {@code dis_tran_type_cd}, {@code
 * dis_tran_cat_cd}) and the {@code pk_disclosure_group} primary-key constraint. Those column
 * mappings live exclusively in the id class and are deliberately <em>not</em> duplicated here.
 *
 * <p><strong>Schema authority.</strong> The application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}, so Hibernate never generates DDL; the single source of
 * truth for the schema is the Flyway migration {@code
 * src/main/resources/db/migration/V1__schema.sql} (table {@code disclosure_group}). The only
 * non-key mapping below mirrors that DDL exactly so Hibernate schema validation passes at startup:
 *
 * <ul>
 *   <li>{@code dis_int_rate} — {@code numeric(6,2)} {@code NOT NULL}.
 * </ul>
 *
 * <p><strong>Decimal fidelity (AAP §0.6.1).</strong> The disclosure interest rate is a COBOL
 * fixed-point {@code PIC S9(04)V99} value and maps to {@link java.math.BigDecimal} with {@code
 * precision = 6, scale = 2} — never {@code float}/{@code double}. This entity is a pure data
 * holder: it stores the rate verbatim, while the COBOL {@code COMPUTE} truncation semantics of the
 * interest calculation {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} — which carries no {@code
 * ROUNDED} phrase and therefore truncates with {@link java.math.RoundingMode#DOWN} at scale 2 — are
 * the responsibility of the interest-calculation service layer, not this mapping.
 *
 * <p>This is a small, immutable-in-practice reference table seeded by {@code
 * V2__seed_reference_data.sql}; it has no version column and no alternate index.
 */
@Entity
@Table(name = "disclosure_group")
@Getter
@Setter
@NoArgsConstructor
public class DisclosureGroup implements Serializable {

  /** Serialization version identifier (standard JPA entity practice). */
  private static final long serialVersionUID = 1L;

  /**
   * Composite primary key — COBOL {@code 05 DIS-GROUP-KEY} group ({@code DIS-ACCT-GROUP-ID X(10)} +
   * {@code DIS-TRAN-TYPE-CD X(02)} + {@code DIS-TRAN-CAT-CD 9(04)}; {@code KEYLEN} 16). Mapped by
   * the embeddable {@link DisclosureGroupId}, which carries the three key-column definitions and
   * the {@code pk_disclosure_group} primary-key constraint.
   */
  @EmbeddedId private DisclosureGroupId id;

  /**
   * Disclosure interest rate — COBOL {@code DIS-INT-RATE PIC S9(04)V99}; column {@code dis_int_rate
   * numeric(6,2) NOT NULL}. Modeled as {@link BigDecimal} ({@code precision = 6, scale = 2}) to
   * preserve exact decimal fidelity; this is the rate consumed by the interest-calculation batch
   * job (AAP §0.6.1). The native PostgreSQL {@code numeric} type maps directly, so no
   * {@code @JdbcTypeCode} override is required.
   */
  @Column(name = "dis_int_rate", precision = 6, scale = 2, nullable = false)
  private BigDecimal disIntRate;
}
