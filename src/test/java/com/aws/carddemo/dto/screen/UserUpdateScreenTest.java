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
 * Unit tests for {@link UserUpdateScreen}, the screen view contract migrated from the legacy
 * CICS/BMS map {@code COUSR02} (mapset {@code COUSR2A}, transaction {@code CU02}, admin-only).
 *
 * <p>These are pure POJO tests — no Spring context, database, or mocking — that lock the screen's
 * behavioural contract to the legacy specification ({@code legacy/app/cpy-bms/COUSR02.CPY} and
 * {@code legacy/app/bms/COUSR02.bms}):
 *
 * <ul>
 *   <li>every {@code PIC X(n)} field round-trips through its getter/setter (AAP §0.4.1);
 *   <li>the fixed BMS field widths are preserved as {@code @Size(max = n)} constraints (AAP
 *       §0.4.1);
 *   <li>the sensitive {@code passwd} field is readable via its accessor yet never leaks through
 *       {@link UserUpdateScreen#toString()} (credential hygiene, AAP §0.6.6).
 * </ul>
 */
class UserUpdateScreenTest {

  /** The six display-only header / chrome fields must round-trip unchanged. */
  @Test
  void header_fields_round_trip() {
    UserUpdateScreen screen = new UserUpdateScreen();

    screen.setTrnName("CU02");
    screen.setTitle01("AWS Mainframe Modernization");
    screen.setCurDate("01/02/23");
    screen.setPgmName("COUSR02C");
    screen.setTitle02("Update User");
    screen.setCurTime("14:30:00");

    assertThat(screen.getTrnName()).isEqualTo("CU02");
    assertThat(screen.getTitle01()).isEqualTo("AWS Mainframe Modernization");
    assertThat(screen.getCurDate()).isEqualTo("01/02/23");
    assertThat(screen.getPgmName()).isEqualTo("COUSR02C");
    assertThat(screen.getTitle02()).isEqualTo("Update User");
    assertThat(screen.getCurTime()).isEqualTo("14:30:00");
  }

  /** The editable user-record fields and the user-id lookup key must round-trip unchanged. */
  @Test
  void editable_user_fields_round_trip() {
    UserUpdateScreen screen = new UserUpdateScreen();

    screen.setUsrIdIn("USER0001");
    screen.setFName("John");
    screen.setLName("Doe");
    screen.setUsrType("U");
    screen.setErrMsg("User updated successfully");

    assertThat(screen.getUsrIdIn()).isEqualTo("USER0001");
    assertThat(screen.getFName()).isEqualTo("John");
    assertThat(screen.getLName()).isEqualTo("Doe");
    assertThat(screen.getUsrType()).isEqualTo("U");
    assertThat(screen.getErrMsg()).isEqualTo("User updated successfully");
  }

  /**
   * Credential hygiene (AAP §0.6.6): the clear-text password is readable through its accessor (the
   * service layer re-hashes it with BCrypt before persistence) but must never be exposed by {@link
   * UserUpdateScreen#toString()} — it is annotated {@code @ToString.Exclude}. Non-sensitive fields
   * such as the user id remain visible so {@code toString()} stays useful for diagnostics.
   */
  @Test
  void password_round_trips_via_getter_but_is_excluded_from_toString() {
    UserUpdateScreen screen = new UserUpdateScreen();
    screen.setUsrIdIn("USER0001");
    screen.setPasswd("SECRET99");

    assertThat(screen.getPasswd()).isEqualTo("SECRET99");

    String rendered = screen.toString();
    assertThat(rendered).contains("USER0001");
    assertThat(rendered).doesNotContain("SECRET99");
  }

  /**
   * Each variable-data field accepts a value of its full BMS width, documenting the fixed 3270
   * field lengths: the user id and password are 8, first / last name are 20, and the message line
   * is 78.
   */
  @Test
  void string_fields_accept_full_bms_widths() {
    UserUpdateScreen screen = new UserUpdateScreen();

    String width8 = "1".repeat(8);
    String width20 = "N".repeat(20);
    String width78 = "M".repeat(78);

    screen.setUsrIdIn(width8);
    screen.setPasswd(width8);
    screen.setFName(width20);
    screen.setLName(width20);
    screen.setErrMsg(width78);

    assertThat(screen.getUsrIdIn()).hasSize(8).isEqualTo(width8);
    assertThat(screen.getPasswd()).hasSize(8).isEqualTo(width8);
    assertThat(screen.getFName()).hasSize(20).isEqualTo(width20);
    assertThat(screen.getLName()).hasSize(20).isEqualTo(width20);
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
    assertMaxSize("passwd", 8);
    assertMaxSize("usrType", 1);
    assertMaxSize("errMsg", 78);
  }

  /**
   * A freshly constructed screen has no populated fields — every value defaults to {@code null}.
   */
  @Test
  void fresh_instance_has_null_fields() {
    UserUpdateScreen screen = new UserUpdateScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getUsrIdIn()).isNull();
    assertThat(screen.getFName()).isNull();
    assertThat(screen.getLName()).isNull();
    assertThat(screen.getPasswd()).isNull();
    assertThat(screen.getUsrType()).isNull();
    assertThat(screen.getErrMsg()).isNull();
  }

  /**
   * Asserts that the named field of {@link UserUpdateScreen} carries a {@link Size} constraint
   * whose {@code max} equals the expected BMS field width.
   */
  private static void assertMaxSize(String fieldName, int expectedMax) throws NoSuchFieldException {
    Size size = UserUpdateScreen.class.getDeclaredField(fieldName).getAnnotation(Size.class);
    assertThat(size).as("field '%s' should declare @Size", fieldName).isNotNull();
    assertThat(size.max()).as("@Size(max) for field '%s'", fieldName).isEqualTo(expectedMax);
  }
}
