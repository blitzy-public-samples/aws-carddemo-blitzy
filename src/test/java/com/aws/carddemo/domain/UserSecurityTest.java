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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Pure POJO unit tests for {@link UserSecurity}, the JPA entity migrated from the legacy z/OS COBOL
 * copybook {@code legacy/app/cpy/CSUSR01Y.cpy} (record {@code 01 SEC-USER-DATA}, RECLN 80). The
 * entity maps the legacy VSAM {@code USRSEC} KSDS (primary key {@code SEC-USR-ID}, KEYLEN 8) onto
 * the {@code user_security} table and is the credential/role store consulted by the sign-on
 * transaction.
 *
 * <p>These tests are intentionally framework-light — no Spring context, no database, no
 * Testcontainers, and no {@code @DataJpaTest}. They exercise only the plain Java object model with
 * {@code new UserSecurity()} and Java reflection, matching the production class's framework-free
 * design and keeping the suite fast and deterministic (AAP &sect;0.6.7).
 *
 * <p>The two <strong>security-critical</strong> object-model facts pinned here directly encode the
 * credential-hardening requirement (AAP &sect;0.6.6, &sect;0.7.2):
 *
 * <ul>
 *   <li>{@code secUsrPwd} is a variable-length {@code String} mapped to a widened {@code
 *       varchar(60)} column sized for a 60-character BCrypt hash — <em>not</em> the legacy 8-byte
 *       clear-text {@code SEC-USR-PWD PIC X(08)} width;
 *   <li>the entity never leaks the password through {@code toString()} (it declares no Lombok
 *       {@code @ToString}/{@code @Data}, so the default {@code Object.toString} cannot emit field
 *       values) — this test guards against a future accidental annotation that would expose the
 *       credential.
 * </ul>
 *
 * <p>The remaining tests pin the COBOL-to-JPA parity invariants the persistence layer depends on:
 * the Lombok-generated accessors round-trip every modeled field, the entity maps to table {@code
 * user_security} with {@code secUsrId} as the {@code char(8)} {@link Id} primary key, the role flag
 * accepts the {@code 'A'} (admin) and {@code 'U'} (user) values, and no field uses a binary
 * floating-point type (the decimal-fidelity guard shared by every entity test in this package, AAP
 * &sect;0.6.1).
 *
 * <p>Per AAP &sect;0.6.6/&sect;0.7.2 this test contains <strong>no real or default
 * credentials</strong>: the only password-shaped literal is {@link #FAKE_HASH}, an obviously-fake,
 * meaningless test stub. The test asserts the object model only and deliberately performs no real
 * hashing or verification — that is the responsibility of the externalized service/seeder layer.
 *
 * <p>The production entity is authoritative: if any accessor, field, column name, length, or
 * annotation differs, the test is the side that must change.
 */
class UserSecurityTest {

  // Obviously-fake, non-secret test stub — not a real credential (AAP §0.6.6).
  private static final String FAKE_HASH =
      "$2a$10$DUMMYbcrypthashForUnitTestOnly000000000000000000000000";

  /**
   * Every accessor pair round-trips its value, and a freshly constructed entity leaves all five
   * modeled fields unset ({@code null}) — mirroring the COBOL record before any {@code MOVE}
   * populates it.
   */
  @Test
  void gettersAndSettersRoundTrip() {
    UserSecurity user = new UserSecurity();
    user.setSecUsrId("TESTUSER");
    user.setSecUsrFname("Test");
    user.setSecUsrLname("User");
    user.setSecUsrPwd(FAKE_HASH);
    user.setSecUsrType("U");

    assertThat(user.getSecUsrId()).isEqualTo("TESTUSER");
    assertThat(user.getSecUsrFname()).isEqualTo("Test");
    assertThat(user.getSecUsrLname()).isEqualTo("User");
    assertThat(user.getSecUsrPwd()).isEqualTo(FAKE_HASH);
    assertThat(user.getSecUsrType()).isEqualTo("U");

    UserSecurity fresh = new UserSecurity();
    assertThat(fresh.getSecUsrId()).isNull();
    assertThat(fresh.getSecUsrFname()).isNull();
    assertThat(fresh.getSecUsrLname()).isNull();
    assertThat(fresh.getSecUsrPwd()).isNull();
    assertThat(fresh.getSecUsrType()).isNull();
  }

  /**
   * {@code secUsrPwd} is a variable-length {@link String} mapped to {@code varchar(60)} column
   * {@code sec_usr_pwd}, sized for a 60-character BCrypt hash. The length of {@code 60} (rather
   * than the legacy {@code 8}) is the object-model marker of the credential-hardening migration
   * (AAP &sect;0.6.6).
   */
  @Test
  void passwordFieldIsVariableLengthStringForBcrypt() throws NoSuchFieldException {
    Field field = UserSecurity.class.getDeclaredField("secUsrPwd");
    Column column = field.getAnnotation(Column.class);

    assertThat(field.getType()).isEqualTo(String.class);
    assertThat(column.name()).isEqualTo("sec_usr_pwd");
    assertThat(column.length()).isEqualTo(60);
  }

  /**
   * The entity must never expose the password hash through {@code toString()}. Because {@code
   * UserSecurity} declares no Lombok {@code @ToString}/{@code @Data}, its {@code toString()} is the
   * default {@code Object.toString} ({@code ClassName@hashCode}) which cannot leak field values.
   * This test guards against a future accidental {@code @ToString}/{@code @Data} that would expose
   * the credential (AAP &sect;0.6.6).
   */
  @Test
  void toStringDoesNotExposePassword() {
    UserSecurity user = new UserSecurity();
    user.setSecUsrPwd(FAKE_HASH);

    assertThat(user.toString()).doesNotContain(FAKE_HASH);
  }

  /**
   * The role flag {@code secUsrType} round-trips both legacy values, {@code 'A'} ({@code
   * CDEMO-USRTYP-ADMIN}) and {@code 'U'} ({@code CDEMO-USRTYP-USER}). It is modeled as a {@code
   * String} of length one (COBOL {@code SEC-USR-TYPE PIC X(01)}); no enum is introduced.
   */
  @Test
  void userTypeAcceptsAdminAndUser() {
    UserSecurity user = new UserSecurity();

    user.setSecUsrType("A");
    assertThat(user.getSecUsrType()).isEqualTo("A");

    user.setSecUsrType("U");
    assertThat(user.getSecUsrType()).isEqualTo("U");
  }

  /** The entity is mapped to the {@code user_security} table. */
  @Test
  void tableNameIsUserSecurity() {
    assertThat(UserSecurity.class.getAnnotation(Table.class).name()).isEqualTo("user_security");
  }

  /**
   * {@code secUsrId} is the {@link Id} primary key and maps to {@code char(8)} column {@code
   * sec_usr_id} (COBOL {@code SEC-USR-ID PIC X(08)}, the VSAM {@code USRSEC} KSDS key, KEYLEN 8).
   */
  @Test
  void primaryKeyIsSecUsrId() throws NoSuchFieldException {
    Field field = UserSecurity.class.getDeclaredField("secUsrId");
    Column column = field.getAnnotation(Column.class);

    assertThat(field.isAnnotationPresent(Id.class)).isTrue();
    assertThat(column.name()).isEqualTo("sec_usr_id");
    assertThat(column.length()).isEqualTo(8);
  }

  /**
   * Decimal-fidelity guard (AAP &sect;0.6.1): no declared field may use a binary floating-point
   * type. Synthetic fields (e.g. the JaCoCo {@code $jacocoData} instrumentation field added during
   * coverage runs) are skipped because they are compiler/agent generated, not part of the modeled
   * record.
   */
  @Test
  void hasNoFloatingPointFields() {
    for (Field field : UserSecurity.class.getDeclaredFields()) {
      if (field.isSynthetic()) {
        continue;
      }
      assertThat(field.getType()).isNotIn(float.class, double.class, Float.class, Double.class);
    }
  }
}
