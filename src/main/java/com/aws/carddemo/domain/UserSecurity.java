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
 * JPA entity mapping the {@code user_security} table — the modernized representation of the legacy
 * security record copybook {@code CSUSR01Y} ({@code 01 SEC-USER-DATA}, RECLN 80; source {@code
 * legacy/app/cpy/CSUSR01Y.cpy}).
 *
 * <p>In the original z/OS application this record was stored in the VSAM KSDS {@code USRSEC} keyed
 * on {@code SEC-USR-ID} (KEYLEN 8) and was the credential/role store consulted by the sign-on
 * transaction. Per Agent Action Plan &sect;0.4.2 ("one Java type per copybook 01-level"), exactly
 * one Java type is produced for this copybook, and {@code COPY CSUSR01Y} call sites are translated
 * to {@code import com.aws.carddemo.domain.UserSecurity}. The VSAM file becomes the PostgreSQL
 * table {@code user_security}, with the primary key {@code sec_usr_id} preserving the original KSDS
 * key semantics.
 *
 * <p><strong>Schema authority.</strong> The single source of truth for the physical schema is the
 * Flyway migration {@code src/main/resources/db/migration/V1__schema.sql}, and the application runs
 * Hibernate with {@code spring.jpa.hibernate.ddl-auto=validate}. The field set, column names,
 * lengths, and JDBC type codes declared here therefore mirror that {@code user_security} table
 * definition <em>exactly</em>; any divergence would fail schema validation at startup.
 *
 * <p><strong>Fixed-width contract.</strong> The COBOL alphanumeric ({@code PIC X(n)}) key and text
 * fields are stored in PostgreSQL {@code char(n)} columns and are pinned to JDBC {@link
 * SqlTypes#CHAR} via {@link JdbcTypeCode} so that the blank-padded, fixed-width ordering and
 * equality behavior of the legacy VSAM record are preserved.
 *
 * <p><strong>Security hardening (AAP &sect;0.6.6).</strong> The legacy clear-text password field
 * {@code SEC-USR-PWD PIC X(08)} is <em>not</em> reproduced as an 8-byte clear-text value. It is
 * replaced by a BCrypt hash stored in a widened {@code varchar(60)} column ({@link #secUsrPwd}). A
 * BCrypt hash has no fixed-width VSAM ordering semantics, so this column is a plain {@code VARCHAR}
 * (no {@link JdbcTypeCode}) rather than {@code char}. This entity never stores, defaults, or
 * exposes a plaintext password, and <strong>no credentials are hardcoded here</strong>: the table
 * is created empty by {@code V1}, and the two seed users ({@code ADMIN001} type {@code 'A'} and
 * {@code USER0001} type {@code 'U'}) are inserted at startup by a dedicated bootstrap seeder that
 * reads the {@code CARDDEMO_ADMIN_PASSWORD} / {@code CARDDEMO_USER_PASSWORD} environment variables
 * and BCrypt-encodes them.
 *
 * <p><strong>Omissions and deferrals.</strong> The trailing {@code SEC-USR-FILLER PIC X(23)}
 * reserve area is intentionally not modeled. No {@code @Version} optimistic-lock column is declared
 * because {@code V1} has no such column; the legacy READ-for-update / REWRITE concurrency behavior
 * (AAP &sect;0.3.3) is reproduced in the service layer rather than via JPA optimistic locking. No
 * secondary index is declared because the VSAM {@code USRSEC} file has no alternate index.
 *
 * <p><strong>PII hygiene.</strong> Lombok generates only {@code @Getter}/{@code @Setter} and a
 * no-argument constructor; {@code @ToString}/{@code @EqualsAndHashCode}/{@code @Data} are
 * deliberately avoided so the BCrypt password hash is never emitted through a generated {@code
 * toString()}.
 */
@Entity
@Table(name = "user_security")
@Getter
@Setter
@NoArgsConstructor
public class UserSecurity implements Serializable {

  /**
   * Serialization version identifier. Domain entities may be serialized (for example when held in
   * caches or session state), so an explicit, stable {@code serialVersionUID} is declared.
   */
  private static final long serialVersionUID = 1L;

  /**
   * User identifier — primary key. Legacy {@code SEC-USR-ID PIC X(08)}, the VSAM {@code USRSEC}
   * KSDS key (KEYLEN 8). Stored as a fixed-width {@code char(8)} column so the eight-character,
   * blank-padded key ordering of the original VSAM file is preserved.
   */
  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "sec_usr_id", length = 8, nullable = false)
  private String secUsrId;

  /**
   * User first name. Legacy {@code SEC-USR-FNAME PIC X(20)} stored as fixed-width {@code char(20)}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "sec_usr_fname", length = 20, nullable = false)
  private String secUsrFname;

  /**
   * User last name. Legacy {@code SEC-USR-LNAME PIC X(20)} stored as fixed-width {@code char(20)}.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "sec_usr_lname", length = 20, nullable = false)
  private String secUsrLname;

  /**
   * BCrypt-hashed password. Replaces the legacy clear-text {@code SEC-USR-PWD PIC X(08)} (AAP
   * &sect;0.6.6). Stored as a plain {@code varchar(60)} (a BCrypt hash is 60 characters and has no
   * fixed-width ordering semantics), hence no {@link JdbcTypeCode} is applied. The value is
   * supplied exclusively by the externalized bootstrap seeder; it is never hardcoded or defaulted
   * here.
   */
  @Column(name = "sec_usr_pwd", length = 60, nullable = false)
  private String secUsrPwd;

  /**
   * User type / role flag. Legacy {@code SEC-USR-TYPE PIC X(01)} stored as fixed-width {@code
   * char(1)}: {@code 'A'} for an administrator ({@code CDEMO-USRTYP-ADMIN}) and {@code 'U'} for a
   * standard user ({@code CDEMO-USRTYP-USER}). Drives the role-based gating of online functions.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "sec_usr_type", length = 1, nullable = false)
  private String secUsrType;
}
