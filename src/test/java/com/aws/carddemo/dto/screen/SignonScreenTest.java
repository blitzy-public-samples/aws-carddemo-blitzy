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
 * Pure unit tests for {@link SignonScreen}, the Java view contract migrated from the legacy
 * CICS/BMS sign-on screen {@code COSGN00} (CICS transaction {@code CC00}).
 *
 * <p>These tests are intentionally framework-light: they exercise the plain data-transfer object
 * directly with {@code new SignonScreen()} and AssertJ assertions only — no Spring context, no
 * database, no Testcontainers, and no mocking. They guard the parity-critical invariants for this
 * screen:
 *
 * <ul>
 *   <li><b>Getter/setter round-trip</b> — every one of the eleven logical fields stores and returns
 *       its value verbatim (no auto-padding or truncation).
 *   <li><b>Field-width fidelity</b> (AAP &sect;0.4.1 / &sect;0.3.4) — each field's {@link Size}
 *       maximum equals the BMS map {@code LENGTH} metadata declared in {@code
 *       legacy/app/cpy-bms/COSGN00.CPY} and {@code legacy/app/bms/COSGN00.bms}. Note that {@code
 *       curTime} is {@code X(9)} on this screen, one wider than the {@code X(8)} used on most other
 *       screens.
 *   <li><b>Credential hygiene</b> (AAP &sect;0.6.6) — the masked {@code passwd} field ({@code
 *       ATTRB=DRK} on the BMS map) must never leak through {@link SignonScreen#toString()}.
 * </ul>
 */
class SignonScreenTest {

  /**
   * Round-trips all eleven logical screen fields through their setters and getters, confirming the
   * DTO stores each value exactly as supplied.
   */
  @Test
  void all_eleven_fields_round_trip_through_getters_and_setters() {
    SignonScreen screen = new SignonScreen();

    String errMsg = "E".repeat(78);

    screen.setTrnName("CC00");
    screen.setTitle01("AWS Mainframe Modernization CardDemo");
    screen.setCurDate("01/02/26");
    screen.setPgmName("COSGN00C");
    screen.setTitle02("Sign-On Screen");
    screen.setCurTime("12:00:00");
    screen.setApplId("CICSAWS1");
    screen.setSysId("SYSA0001");
    screen.setUserId("ADMIN001");
    screen.setPasswd("PASSW123");
    screen.setErrMsg(errMsg);

    assertThat(screen.getTrnName()).isEqualTo("CC00");
    assertThat(screen.getTitle01()).isEqualTo("AWS Mainframe Modernization CardDemo");
    assertThat(screen.getCurDate()).isEqualTo("01/02/26");
    assertThat(screen.getPgmName()).isEqualTo("COSGN00C");
    assertThat(screen.getTitle02()).isEqualTo("Sign-On Screen");
    assertThat(screen.getCurTime()).isEqualTo("12:00:00");
    assertThat(screen.getApplId()).isEqualTo("CICSAWS1");
    assertThat(screen.getSysId()).isEqualTo("SYSA0001");
    assertThat(screen.getUserId()).isEqualTo("ADMIN001");
    assertThat(screen.getPasswd()).isEqualTo("PASSW123");
    assertThat(screen.getErrMsg()).isEqualTo(errMsg);
  }

  /**
   * Asserts that every field's {@link Size} maximum equals the corresponding BMS map {@code LENGTH}
   * metadata, locking in the COBOL {@code PIC X(n)} widths from {@code COSGN00.CPY} / {@code
   * COSGN00.bms}.
   */
  @Test
  void field_widths_match_bms_length_metadata_via_size_annotations() throws NoSuchFieldException {
    assertThat(sizeMax("trnName")).isEqualTo(4);
    assertThat(sizeMax("title01")).isEqualTo(40);
    assertThat(sizeMax("curDate")).isEqualTo(8);
    assertThat(sizeMax("pgmName")).isEqualTo(8);
    assertThat(sizeMax("title02")).isEqualTo(40);
    assertThat(sizeMax("curTime")).isEqualTo(9);
    assertThat(sizeMax("applId")).isEqualTo(8);
    assertThat(sizeMax("sysId")).isEqualTo(8);
    assertThat(sizeMax("userId")).isEqualTo(8);
    assertThat(sizeMax("passwd")).isEqualTo(8);
    assertThat(sizeMax("errMsg")).isEqualTo(78);
  }

  /**
   * Documents the parity widths for the data-entry and message fields by round-tripping values of
   * the exact BMS lengths — {@code userId}/{@code passwd} at {@code X(8)}, {@code curTime} at
   * {@code X(9)}, and {@code errMsg} at {@code X(78)} — confirming the DTO preserves them verbatim
   * with no padding or truncation.
   */
  @Test
  void width_sensitive_fields_preserve_exact_length_values() {
    SignonScreen screen = new SignonScreen();

    String userId = "U2345678";
    String passwd = "P2345678";
    String curTime = "A23:59:59";
    String errMsg = "E".repeat(78);

    screen.setUserId(userId);
    screen.setPasswd(passwd);
    screen.setCurTime(curTime);
    screen.setErrMsg(errMsg);

    assertThat(screen.getUserId()).isEqualTo(userId).hasSize(8);
    assertThat(screen.getPasswd()).isEqualTo(passwd).hasSize(8);
    assertThat(screen.getCurTime()).isEqualTo(curTime).hasSize(9);
    assertThat(screen.getErrMsg()).isEqualTo(errMsg).hasSize(78);
  }

  /**
   * Verifies credential hygiene (AAP &sect;0.6.6): the password round-trips through its accessor
   * but its clear-text value is never exposed by {@link SignonScreen#toString()}. This holds for a
   * default {@code Object.toString()} and for a masked custom {@code toString()}, and fails only if
   * a custom {@code toString()} leaks the raw password.
   */
  @Test
  void password_is_not_exposed_through_to_string() {
    SignonScreen screen = new SignonScreen();
    screen.setPasswd("SECRET99");

    assertThat(screen.getPasswd()).isEqualTo("SECRET99");
    assertThat(screen.toString()).doesNotContain("SECRET99");
  }

  /** Confirms a freshly constructed screen leaves every field at its {@code null} default. */
  @Test
  void freshly_constructed_screen_has_all_null_fields() {
    SignonScreen screen = new SignonScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getApplId()).isNull();
    assertThat(screen.getSysId()).isNull();
    assertThat(screen.getUserId()).isNull();
    assertThat(screen.getPasswd()).isNull();
    assertThat(screen.getErrMsg()).isNull();
  }

  /**
   * Reflectively reads the {@link Size#max()} declared on the named {@link SignonScreen} field.
   *
   * @param fieldName the declared field name to inspect
   * @return the {@code @Size(max = …)} value annotated on that field
   * @throws NoSuchFieldException if the field does not exist on {@link SignonScreen}
   */
  private static int sizeMax(String fieldName) throws NoSuchFieldException {
    return SignonScreen.class.getDeclaredField(fieldName).getAnnotation(Size.class).max();
  }
}
