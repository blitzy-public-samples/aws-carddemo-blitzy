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
 * JPA entity for the {@code tran_type} reference table — the transaction-type lookup that maps a
 * two-character transaction-type code to its human-readable description.
 *
 * <p><strong>Origin.</strong> Migrated from the legacy z/OS COBOL copybook {@code
 * legacy/app/cpy/CVTRA03Y.cpy} (source-branch {@code app/cpy/CVTRA03Y.cpy}), record {@code 01
 * TRAN-TYPE-RECORD} with a fixed record length of {@code RECLN = 60}. Per AAP §0.4.2 ("one Java
 * type per copybook {@code 01}-level"), this single copybook group becomes this single entity. The
 * trailing {@code FILLER PIC X(08)} of the copybook is positional padding with no business meaning
 * and is intentionally <em>not</em> modeled as a column — fixed-width record reconstruction for
 * golden-file parity is the responsibility of the file-I/O layer, not the database.
 *
 * <p><strong>Copybook field layout.</strong>
 *
 * <pre>
 *   offset  len  field            COBOL PIC      JPA mapping
 *   ------  ---  ---------------  -------------  ----------------------------------------
 *        0    2  TRAN-TYPE        PIC X(02)      {@link #tranType}     (primary key, char(2))
 *        2   50  TRAN-TYPE-DESC   PIC X(50)      {@link #tranTypeDesc} (char(50))
 *       52    8  FILLER           PIC X(08)      (not modeled)
 *   ------  ---
 *       60  total record width (RECLN)
 * </pre>
 *
 * <p><strong>Schema authority.</strong> The application runs with {@code
 * spring.jpa.hibernate.ddl-auto=validate}, so Hibernate never generates DDL; the single source of
 * truth for the schema is the Flyway migration {@code
 * src/main/resources/db/migration/V1__schema.sql} (table {@code tran_type}). Every mapping below
 * mirrors that DDL exactly so Hibernate schema validation passes at startup:
 *
 * <ul>
 *   <li>{@code tran_type} — {@code char(2)} {@code NOT NULL}, {@code PRIMARY KEY}.
 *   <li>{@code tran_type_desc} — {@code char(50)} {@code NOT NULL}.
 * </ul>
 *
 * <p><strong>Fixed-width parity (AAP §0.6.2).</strong> Both columns are PostgreSQL {@code char(n)}
 * (blank-padded {@code bpchar}), which reports JDBC type {@link SqlTypes#CHAR}. Each {@link String}
 * field is therefore pinned with {@link JdbcTypeCode @JdbcTypeCode(SqlTypes.CHAR)}; a plain {@code
 * String} would default to {@code VARCHAR} and make Hibernate {@code validate} fail. Values read
 * back from a {@code char(n)} column are space-padded to the declared width, preserving VSAM
 * ordering and equality semantics; trimming, where a caller needs it, is the service layer's
 * responsibility.
 *
 * <p>This is a small, immutable-in-practice reference table seeded by {@code
 * V2__seed_reference_data.sql}; it has no monetary fields, no version column, and no alternate
 * index.
 */
@Entity
@Table(name = "tran_type")
@Getter
@Setter
@NoArgsConstructor
public class TransactionType implements Serializable {

  /** Serialization version identifier (standard JPA entity practice). */
  private static final long serialVersionUID = 1L;

  /**
   * Transaction-type code — COBOL {@code TRAN-TYPE PIC X(02)}; column {@code tran_type char(2) NOT
   * NULL}, the table's primary key. Pinned to JDBC {@link SqlTypes#CHAR} so the mapping matches the
   * PostgreSQL {@code char(2)} (blank-padded) column under Hibernate {@code validate}.
   */
  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_type", length = 2, nullable = false)
  private String tranType;

  /**
   * Transaction-type description — COBOL {@code TRAN-TYPE-DESC PIC X(50)}; column {@code
   * tran_type_desc char(50) NOT NULL}. Pinned to JDBC {@link SqlTypes#CHAR} so the mapping matches
   * the PostgreSQL {@code char(50)} (blank-padded) column under Hibernate {@code validate}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tran_type_desc", length = 50, nullable = false)
  private String tranTypeDesc;
}
