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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link AdminMenuScreen}, the Java migration of the legacy CICS/BMS admin-menu
 * mapset {@code COADM01} (CICS transaction {@code CA00}).
 *
 * <p>These tests pin the screen view contract to the field layout of the BMS input map {@code
 * COADM1AI} (legacy specs {@code legacy/app/cpy-bms/COADM01.CPY} and {@code
 * legacy/app/bms/COADM01.bms}): the six header fields, the fixed set of twelve menu option lines,
 * the selection field, and the error / status message line. They verify accessor round-trip
 * fidelity, the fresh-instance defaults, and that the {@code @Size} constraints exactly mirror the
 * BMS field widths so the 3270 geometry is preserved.
 *
 * <p>This is a framework-light test by design: it exercises {@code AdminMenuScreen} with a plain
 * {@code new} instance and AssertJ assertions only, with no Spring context, database,
 * Testcontainers, or mocking. The admin-only access rule (COBOL {@code CDEMO-USRTYP-ADMIN}) is
 * enforced in the security / service layer, not in this DTO, and is therefore out of scope here.
 */
class AdminMenuScreenTest {

  /** The fixed number of admin-menu option lines (OPTN001..OPTN012) defined by the BMS map. */
  private static final int MENU_OPTION_COUNT = 12;

  /** The maximum width, in characters, of a single menu option line ({@code PIC X(40)}). */
  private static final int OPTION_LINE_WIDTH = 40;

  @Test
  void header_and_scalar_fields_round_trip() {
    AdminMenuScreen screen = new AdminMenuScreen();

    screen.setTrnName("CA00");
    screen.setTitle01("AWS CardDemo");
    screen.setCurDate("08/22/22");
    screen.setPgmName("COADM01C");
    screen.setTitle02("Admin Menu");
    screen.setCurTime("17:02:42");
    screen.setOption("01");
    screen.setErrMsg("Admin menu loaded successfully.");

    assertThat(screen.getTrnName()).isEqualTo("CA00");
    assertThat(screen.getTitle01()).isEqualTo("AWS CardDemo");
    assertThat(screen.getCurDate()).isEqualTo("08/22/22");
    assertThat(screen.getPgmName()).isEqualTo("COADM01C");
    assertThat(screen.getTitle02()).isEqualTo("Admin Menu");
    assertThat(screen.getCurTime()).isEqualTo("17:02:42");
    assertThat(screen.getOption()).isEqualTo("01");
    assertThat(screen.getErrMsg()).isEqualTo("Admin menu loaded successfully.");
  }

  @Test
  void menu_option_lines_round_trip_with_twelve_options() {
    AdminMenuScreen screen = new AdminMenuScreen();

    List<String> expected = new ArrayList<>();
    for (int i = 1; i <= MENU_OPTION_COUNT; i++) {
      expected.add(String.format("%02d. ADMIN OPTION", i));
    }

    screen.setOptions(expected);

    assertThat(screen.getOptions()).hasSize(MENU_OPTION_COUNT);
    assertThat(screen.getOptions()).containsExactlyElementsOf(expected);
    assertThat(screen.getOptions()).allMatch(option -> option.length() <= OPTION_LINE_WIDTH);
  }

  @Test
  void field_widths_round_trip_preserving_bms_lengths() {
    AdminMenuScreen screen = new AdminMenuScreen();

    String optionLine = "A".repeat(OPTION_LINE_WIDTH);
    String selection = "9".repeat(2);
    String message = "E".repeat(78);

    screen.setOptions(List.of(optionLine));
    screen.setOption(selection);
    screen.setErrMsg(message);

    assertThat(screen.getOptions()).containsExactly(optionLine);
    assertThat(screen.getOptions().get(0)).hasSize(OPTION_LINE_WIDTH);
    assertThat(screen.getOption()).isEqualTo(selection).hasSize(2);
    assertThat(screen.getErrMsg()).isEqualTo(message).hasSize(78);
  }

  @Test
  void size_annotations_match_bms_field_widths() throws NoSuchFieldException {
    assertThat(sizeMaxOf("trnName")).isEqualTo(4);
    assertThat(sizeMaxOf("title01")).isEqualTo(40);
    assertThat(sizeMaxOf("curDate")).isEqualTo(8);
    assertThat(sizeMaxOf("pgmName")).isEqualTo(8);
    assertThat(sizeMaxOf("title02")).isEqualTo(40);
    assertThat(sizeMaxOf("curTime")).isEqualTo(8);
    assertThat(sizeMaxOf("option")).isEqualTo(2);
    assertThat(sizeMaxOf("errMsg")).isEqualTo(78);
    assertThat(sizeMaxOf("options")).isEqualTo(MENU_OPTION_COUNT);
  }

  @Test
  void fresh_instance_has_null_scalars_and_empty_options_list() {
    AdminMenuScreen screen = new AdminMenuScreen();

    assertThat(screen.getTrnName()).isNull();
    assertThat(screen.getTitle01()).isNull();
    assertThat(screen.getCurDate()).isNull();
    assertThat(screen.getPgmName()).isNull();
    assertThat(screen.getTitle02()).isNull();
    assertThat(screen.getCurTime()).isNull();
    assertThat(screen.getOption()).isNull();
    assertThat(screen.getErrMsg()).isNull();
    assertThat(screen.getOptions()).isNotNull().isEmpty();
  }

  /**
   * Reads the {@code max} attribute of the {@link Size} annotation declared on the named {@link
   * AdminMenuScreen} field, asserting the annotation is present.
   *
   * @param fieldName the declared field name to inspect
   * @return the {@code @Size(max = ...)} value declared on that field
   * @throws NoSuchFieldException if {@code AdminMenuScreen} declares no such field
   */
  private static int sizeMaxOf(String fieldName) throws NoSuchFieldException {
    Size size = AdminMenuScreen.class.getDeclaredField(fieldName).getAnnotation(Size.class);
    assertThat(size).as("field '%s' must declare @Size", fieldName).isNotNull();
    return size.max();
  }
}
