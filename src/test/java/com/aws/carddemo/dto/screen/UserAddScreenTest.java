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
 * Pure unit tests for the {@link UserAddScreen} screen view contract — the Java migration of the
 * legacy CICS/BMS add-user map {@code COUSR01} (transaction {@code CU01}, an admin-only function).
 *
 * <p>The tests bind directly to the production DTO with no Spring context, database,
 * Testcontainers, or mocking. They verify, per the Agent Action Plan:
 *
 * <ul>
 *   <li>getter/setter round-trip for all twelve screen fields;
 *   <li><b>credential hygiene (AAP &sect;0.6.6)</b> — the clear-text {@code passwd} never leaks
 *       through {@link UserAddScreen#toString()};
 *   <li>field-width fidelity (AAP &sect;0.4.1) — the {@code @Size(max)} bound of every field equals
 *       the BMS {@code LENGTH} declared in {@code legacy/app/bms/COUSR01.bms};
 *   <li>fresh-instance defaults.
 * </ul>
 *
 * <p>Admin-only ({@code CU01}) semantics are enforced in the service / security layer, not in this
 * transport DTO, and are therefore intentionally out of scope here.
 */
class UserAddScreenTest {

  /**
   * 3a — every one of the twelve {@code COUSR1AI} fields survives a setter then getter round-trip
   * with its exact value, proving the JavaBean contract is complete and lossless.
   */
  @Test
  void all_twelve_fields_round_trip_through_getters_and_setters() {
    UserAddScreen screen = new UserAddScreen();

    screen.setTrnName("CU01");
    screen.setTitle01("AWS CardDemo");
    screen.setCurDate("08/22/22");
    screen.setPgmName("COUSR01C");
    screen.setTitle02("Add User");
    screen.setCurTime("17:02:44");
    screen.setFName("John");
    screen.setLName("Doe");
    screen.setUserId("USER0001");
    screen.setPasswd("PASSWD12");
    screen.setUsrType("U");
    screen.setErrMsg("User added successfully");

    assertThat(screen.getTrnName()).isEqualTo("CU01");
    assertThat(screen.getTitle01()).isEqualTo("AWS CardDemo");
    assertThat(screen.getCurDate()).isEqualTo("08/22/22");
    assertThat(screen.getPgmName()).isEqualTo("COUSR01C");
    assertThat(screen.getTitle02()).isEqualTo("Add User");
    assertThat(screen.getCurTime()).isEqualTo("17:02:44");
    assertThat(screen.getFName()).isEqualTo("John");
    assertThat(screen.getLName()).isEqualTo("Doe");
    assertThat(screen.getUserId()).isEqualTo("USER0001");
    assertThat(screen.getPasswd()).isEqualTo("PASSWD12");
    assertThat(screen.getUsrType()).isEqualTo("U");
    assertThat(screen.getErrMsg()).isEqualTo("User added successfully");
  }

  /**
   * 3b — the password is a normal transport value to and from the screen, so it must survive a
   * setter then getter round-trip unchanged. Masking applies only to diagnostic rendering, verified
   * separately in {@link #to_string_does_not_leak_clear_text_password()}.
   */
  @Test
  void password_round_trips_through_getter_and_setter() {
    UserAddScreen screen = new UserAddScreen();

    screen.setPasswd("SECRET99");

    assertThat(screen.getPasswd()).isEqualTo("SECRET99");
  }

  /**
   * 3b (critical, AAP &sect;0.6.6 credential hygiene) — the clear-text password must never leak
   * into diagnostic output. This holds for the default {@link Object#toString()} and for a masking
   * custom {@code toString()}; it fails only if a future {@code toString()} echoes the raw
   * password, which is exactly the defect this assertion guards against.
   */
  @Test
  void to_string_does_not_leak_clear_text_password() {
    UserAddScreen screen = new UserAddScreen();
    screen.setPasswd("SECRET99");

    assertThat(screen.toString()).doesNotContain("SECRET99");
  }

  /**
   * 3c — exact BMS column widths round-trip without truncation: {@code FNAME}/{@code LNAME} at 20,
   * {@code USERID}/{@code PASSWD} at 8, and {@code ERRMSG} at 78 characters.
   */
  @Test
  void field_widths_round_trip_at_bms_lengths() {
    UserAddScreen screen = new UserAddScreen();

    String fName20 = "12345678901234567890";
    String lName20 = "ABCDEFGHIJKLMNOPQRST";
    String userId8 = "USER0001";
    String passwd8 = "PW345678";
    String errMsg78 = "E".repeat(78);

    screen.setFName(fName20);
    screen.setLName(lName20);
    screen.setUserId(userId8);
    screen.setPasswd(passwd8);
    screen.setErrMsg(errMsg78);

    assertThat(screen.getFName()).isEqualTo(fName20).hasSize(20);
    assertThat(screen.getLName()).isEqualTo(lName20).hasSize(20);
    assertThat(screen.getUserId()).isEqualTo(userId8).hasSize(8);
    assertThat(screen.getPasswd()).isEqualTo(passwd8).hasSize(8);
    assertThat(screen.getErrMsg()).isEqualTo(errMsg78).hasSize(78);
  }

  /**
   * 3c — the {@code @Size(max)} constraint on each field equals the BMS {@code LENGTH} of the
   * matching {@code COUSR01} map field, preserving the original 3270 column widths (AAP
   * &sect;0.4.1).
   */
  @Test
  void size_annotations_match_bms_field_widths() {
    assertSizeMax("trnName", 4);
    assertSizeMax("title01", 40);
    assertSizeMax("curDate", 8);
    assertSizeMax("pgmName", 8);
    assertSizeMax("title02", 40);
    assertSizeMax("curTime", 8);
    assertSizeMax("fName", 20);
    assertSizeMax("lName", 20);
    assertSizeMax("userId", 8);
    assertSizeMax("passwd", 8);
    assertSizeMax("usrType", 1);
    assertSizeMax("errMsg", 78);
  }

  /** 3d — a freshly constructed screen leaves every string field unset (null). */
  @Test
  void fresh_instance_has_all_null_string_fields() {
    UserAddScreen screen = new UserAddScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getFName()).isNull();
    assertThat(screen.getLName()).isNull();
    assertThat(screen.getUserId()).isNull();
    assertThat(screen.getPasswd()).isNull();
    assertThat(screen.getUsrType()).isNull();
    assertThat(screen.getErrMsg()).isNull();
  }

  /**
   * Asserts that the {@code @Size} annotation declared on the named {@link UserAddScreen} field has
   * the expected {@code max} bound, reading the field reflectively.
   *
   * @param fieldName the declared field name on {@link UserAddScreen}
   * @param expectedMax the BMS column width the field must preserve
   */
  private static void assertSizeMax(String fieldName, int expectedMax) {
    Size size;
    try {
      size = UserAddScreen.class.getDeclaredField(fieldName).getAnnotation(Size.class);
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException("UserAddScreen is missing expected field: " + fieldName, e);
    }
    assertThat(size).as("@Size annotation on field '%s'", fieldName).isNotNull();
    assertThat(size.max()).as("@Size(max) on field '%s'", fieldName).isEqualTo(expectedMax);
  }
}
