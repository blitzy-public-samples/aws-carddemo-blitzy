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
package com.aws.carddemo.dto.screen;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserDeleteScreen}, the screen view contract migrated from the legacy
 * CICS/BMS map {@code COUSR03} (mapset {@code COUSR3A}, transaction {@code CU03}, admin-only).
 *
 * <p>These are pure POJO tests — no Spring context, database, or mocking — that lock the screen's
 * behavioural contract to the legacy specification ({@code legacy/app/cpy-bms/COUSR03.CPY} and
 * {@code legacy/app/bms/COUSR03.bms}):
 *
 * <ul>
 *   <li>every {@code PIC X(n)} field round-trips through its getter/setter (AAP §0.4.1);
 *   <li>the fixed BMS field widths are preserved as {@code @Size(max = n)} constraints (AAP
 *       §0.4.1);
 *   <li>the Delete User map carries <strong>no</strong> password field — unlike the Add ({@code
 *       COUSR01}) and Update ({@code COUSR02}) screens — so no credential can ever leak through
 *       this DTO (credential hygiene, AAP §0.6.6).
 * </ul>
 */
class UserDeleteScreenTest {

  /** The six display-only header / chrome fields must round-trip unchanged. */
  @Test
  void header_fields_round_trip() {
    UserDeleteScreen screen = new UserDeleteScreen();

    screen.setTrnName("CU03");
    screen.setTitle01("AWS Mainframe Modernization");
    screen.setCurDate("01/02/23");
    screen.setPgmName("COUSR03C");
    screen.setTitle02("Delete User");
    screen.setCurTime("14:30:00");

    assertThat(screen.getTrnName()).isEqualTo("CU03");
    assertThat(screen.getTitle01()).isEqualTo("AWS Mainframe Modernization");
    assertThat(screen.getCurDate()).isEqualTo("01/02/23");
    assertThat(screen.getPgmName()).isEqualTo("COUSR03C");
    assertThat(screen.getTitle02()).isEqualTo("Delete User");
    assertThat(screen.getCurTime()).isEqualTo("14:30:00");
  }

  /**
   * The user-id lookup key and the read-only attributes fetched for delete confirmation must
   * round-trip unchanged. On {@code ENTER} the service populates {@code fName}, {@code lName} and
   * {@code usrType} from the {@code user_security} record; on {@code F5} the record is deleted.
   */
  @Test
  void user_record_fields_round_trip() {
    UserDeleteScreen screen = new UserDeleteScreen();

    screen.setUsrIdIn("USER0001");
    screen.setFName("John");
    screen.setLName("Doe");
    screen.setUsrType("U");
    screen.setErrMsg("User deleted successfully");

    assertThat(screen.getUsrIdIn()).isEqualTo("USER0001");
    assertThat(screen.getFName()).isEqualTo("John");
    assertThat(screen.getLName()).isEqualTo("Doe");
    assertThat(screen.getUsrType()).isEqualTo("U");
    assertThat(screen.getErrMsg()).isEqualTo("User deleted successfully");
  }

  /**
   * Credential-hygiene invariant (AAP §0.6.6): the Delete User screen confirms an existing user by
   * its id and never re-enters a password, so the DTO must declare <strong>no</strong> credential
   * field. Asserting the absence by reflection guards against an accidental {@code passwd} field
   * being copied in from the Add or Update screens.
   */
  @Test
  void delete_screen_exposes_no_password_field() {
    assertThat(
            java.util.Arrays.stream(UserDeleteScreen.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
        .as("Delete User screen must carry no credential field (AAP §0.6.6)")
        .doesNotContain("passwd", "password", "pwd");
  }

  /**
   * Each variable-data field accepts a value of its full BMS width, documenting the fixed 3270
   * field lengths: the user id is 8, first / last name are 20, the user type is 1, and the message
   * line is 78.
   */
  @Test
  void string_fields_accept_full_bms_widths() {
    UserDeleteScreen screen = new UserDeleteScreen();

    String width8 = "1".repeat(8);
    String width20 = "N".repeat(20);
    String width78 = "M".repeat(78);

    screen.setUsrIdIn(width8);
    screen.setFName(width20);
    screen.setLName(width20);
    screen.setUsrType("A");
    screen.setErrMsg(width78);

    assertThat(screen.getUsrIdIn()).hasSize(8).isEqualTo(width8);
    assertThat(screen.getFName()).hasSize(20).isEqualTo(width20);
    assertThat(screen.getLName()).hasSize(20).isEqualTo(width20);
    assertThat(screen.getUsrType()).hasSize(1).isEqualTo("A");
    assertThat(screen.getErrMsg()).hasSize(78).isEqualTo(width78);
  }

  /**
   * The {@code @Size(max = n)} bean-validation constraints must match the BMS {@code LENGTH} of
   * each field exactly, preserving the legacy fixed-width record layout (AAP §0.4.1).
   */
  @Test
  void size_constraints_match_bms_field_widths() throws NoSuchFieldException {
    assertMaxSize("trnName", 4);
    assertMaxSize("title01", 40);
    assertMaxSize("curDate", 8);
    assertMaxSize("pgmName", 8);
    assertMaxSize("title02", 40);
    assertMaxSize("curTime", 8);
    assertMaxSize("usrIdIn", 8);
    assertMaxSize("fName", 20);
    assertMaxSize("lName", 20);
    assertMaxSize("usrType", 1);
    assertMaxSize("errMsg", 78);
  }

  /**
   * A freshly constructed screen has no populated fields — every value defaults to {@code null}.
   */
  @Test
  void fresh_instance_has_null_fields() {
    UserDeleteScreen screen = new UserDeleteScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getUsrIdIn()).isNull();
    assertThat(screen.getFName()).isNull();
    assertThat(screen.getLName()).isNull();
    assertThat(screen.getUsrType()).isNull();
    assertThat(screen.getErrMsg()).isNull();
  }

  /**
   * Asserts that the named field of {@link UserDeleteScreen} carries a {@link Size} constraint
   * whose {@code max} equals the expected BMS field width.
   */
  private static void assertMaxSize(String fieldName, int expectedMax) throws NoSuchFieldException {
    Size size = UserDeleteScreen.class.getDeclaredField(fieldName).getAnnotation(Size.class);
    assertThat(size).as("field '%s' should declare @Size", fieldName).isNotNull();
    assertThat(size.max()).as("@Size(max) for field '%s'", fieldName).isEqualTo(expectedMax);
  }
}
