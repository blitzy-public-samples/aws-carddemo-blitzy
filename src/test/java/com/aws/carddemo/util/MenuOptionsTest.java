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
package com.aws.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.util.MenuOptions.MenuOption;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests (JUnit 5 + AssertJ; no Spring, no database, no mocks) that pin the COBOL-to-Java
 * parity invariants of {@link MenuOptions}.
 *
 * <p>{@link MenuOptions} translates four legacy copybooks into immutable Java lookup tables:
 *
 * <ul>
 *   <li>{@code app/cpy/COMEN02Y.cpy} (main menu) &rarr; {@link MenuOptions#MAIN_MENU_OPTIONS} /
 *       {@link MenuOptions#MAIN_MENU_OPT_COUNT}
 *   <li>{@code app/cpy/COADM02Y.cpy} (admin menu) &rarr; {@link MenuOptions#ADMIN_MENU_OPTIONS} /
 *       {@link MenuOptions#ADMIN_MENU_OPT_COUNT}
 *   <li>{@code app/cpy/COTTL01Y.cpy} (screen titles) &rarr; {@link MenuOptions#TITLE_LINE_1},
 *       {@link MenuOptions#TITLE_LINE_2}, {@link MenuOptions#TITLE_THANK_YOU}
 *   <li>{@code app/cpy/CSDAT01Y.cpy} (date/time masks) &rarr; {@link
 *       MenuOptions#DATE_MASK_MM_DD_YY}, {@link MenuOptions#TIME_MASK_HH_MM_SS}, {@link
 *       MenuOptions#TIMESTAMP_MASK}
 * </ul>
 *
 * <p>The non-negotiable invariants asserted here are: exact option counts (10 main, 4 admin), exact
 * COBOL field widths (name {@code PIC X(35)}, program {@code PIC X(08)}, title {@code PIC X(40)}),
 * the exact program names and table order, the user-type gating ({@code "U"} for the main menu,
 * {@code null} for the admin menu which has no user-type column), the exact date/time/timestamp
 * mask strings, and the immutability of the published lists. A dropped/renamed option or a wrong
 * program name would re-route a transaction; wrong field widths would break the fixed 24x80 screen
 * layout; wrong masks would break date/time/timestamp rendering parity (Agent Action Plan
 * &sect;0.6.5).
 */
class MenuOptionsTest {

  /**
   * The published menus must contain exactly the active COBOL occurrences: the main menu carries 10
   * options (COBOL {@code OCCURS 12} but {@code CDEMO-MENU-OPT-COUNT VALUE 10}) and the admin menu
   * 4 ({@code OCCURS 9} but {@code CDEMO-ADMIN-OPT-COUNT VALUE 4}). The exposed count constants
   * must agree with the list sizes.
   */
  @Test
  void main_and_admin_menus_have_exact_option_counts() {
    assertThat(MenuOptions.MAIN_MENU_OPTIONS).hasSize(10);
    assertThat(MenuOptions.ADMIN_MENU_OPTIONS).hasSize(4);

    assertThat(MenuOptions.MAIN_MENU_OPT_COUNT).isEqualTo(10);
    assertThat(MenuOptions.ADMIN_MENU_OPT_COUNT).isEqualTo(4);

    // The count constants must stay in lock-step with the list sizes.
    assertThat(MenuOptions.MAIN_MENU_OPTIONS).hasSize(MenuOptions.MAIN_MENU_OPT_COUNT);
    assertThat(MenuOptions.ADMIN_MENU_OPTIONS).hasSize(MenuOptions.ADMIN_MENU_OPT_COUNT);
  }

  /**
   * Every main-menu entry must preserve the fixed COBOL field widths ({@code PIC X(35)} name,
   * {@code PIC X(08)} program) and the standard-user gating value {@code "U"} ({@code PIC X(01)}).
   * The widths include the original right space-padding and must never be trimmed.
   */
  @Test
  void main_menu_option_fields_have_fixed_cobol_widths_and_user_type_U() {
    assertThat(MenuOptions.MAIN_MENU_OPTIONS)
        .allSatisfy(
            opt -> {
              assertThat(opt.name()).hasSize(35);
              assertThat(opt.programName()).hasSize(8);
              assertThat(opt.userType()).isEqualTo("U");
            });
  }

  /**
   * Every admin-menu entry must preserve the same fixed COBOL field widths ({@code PIC X(35)} name,
   * {@code PIC X(08)} program), but the admin copybook ({@code COADM02Y}) has no user-type column,
   * so each entry's {@code userType} must be {@code null}.
   */
  @Test
  void admin_menu_option_fields_have_fixed_widths_and_null_user_type() {
    assertThat(MenuOptions.ADMIN_MENU_OPTIONS)
        .allSatisfy(
            opt -> {
              assertThat(opt.name()).hasSize(35);
              assertThat(opt.programName()).hasSize(8);
              assertThat(opt.userType()).isNull();
            });
  }

  /**
   * Spot-checks the menu table order and the exact target program names at the boundaries and at
   * the historically tricky main-menu entry #8, which must carry the ACTIVE value {@code
   * "Transaction Add"} / {@code COTRN02C} (not the commented-out {@code "Transaction Add (Admin
   * Only)"} variant from {@code COMEN02Y.cpy}). Lists are 0-indexed while the record's {@code
   * number()} is the 1-based COBOL selector.
   */
  @Test
  void menu_options_preserve_program_names_and_order() {
    MenuOption main1 = MenuOptions.MAIN_MENU_OPTIONS.get(0);
    assertThat(main1.number()).isEqualTo(1);
    assertThat(main1.name().strip()).isEqualTo("Account View");
    assertThat(main1.programName()).isEqualTo("COACTVWC");
    assertThat(main1.userType()).isEqualTo("U");

    MenuOption main8 = MenuOptions.MAIN_MENU_OPTIONS.get(7);
    assertThat(main8.number()).isEqualTo(8);
    assertThat(main8.programName()).isEqualTo("COTRN02C");
    assertThat(main8.name().strip()).isEqualTo("Transaction Add");

    MenuOption main10 = MenuOptions.MAIN_MENU_OPTIONS.get(9);
    assertThat(main10.number()).isEqualTo(10);
    assertThat(main10.programName()).isEqualTo("COBIL00C");
    assertThat(main10.name().strip()).isEqualTo("Bill Payment");

    MenuOption admin1 = MenuOptions.ADMIN_MENU_OPTIONS.get(0);
    assertThat(admin1.number()).isEqualTo(1);
    assertThat(admin1.programName()).isEqualTo("COUSR00C");
    assertThat(admin1.name().strip()).isEqualTo("User List (Security)");
    assertThat(admin1.userType()).isNull();

    MenuOption admin4 = MenuOptions.ADMIN_MENU_OPTIONS.get(3);
    assertThat(admin4.number()).isEqualTo(4);
    assertThat(admin4.programName()).isEqualTo("COUSR03C");
    assertThat(admin4.name().strip()).isEqualTo("User Delete (Security)");
  }

  /**
   * The three shared screen-title lines from {@code COTTL01Y.cpy} are {@code PIC X(40)} and must be
   * exactly 40 characters (including the original centering/trailing spaces) so the fixed 24x80
   * screen layout is preserved. The trimmed text must match the active copybook values ({@code
   * TITLE_LINE_2} is the {@code CardDemo} line, not the commented {@code CCDA} variant).
   */
  @Test
  void screen_titles_are_forty_chars() {
    assertThat(MenuOptions.TITLE_LINE_1).hasSize(40);
    assertThat(MenuOptions.TITLE_LINE_1.strip()).isEqualTo("AWS Mainframe Modernization");

    assertThat(MenuOptions.TITLE_LINE_2).hasSize(40);
    assertThat(MenuOptions.TITLE_LINE_2.strip()).isEqualTo("CardDemo");

    assertThat(MenuOptions.TITLE_THANK_YOU).hasSize(40);
    assertThat(MenuOptions.TITLE_THANK_YOU.strip()).contains("Thank you for using");
  }

  /**
   * The date/time masks are {@link DateTimeFormatter} patterns translated from the COBOL edit masks
   * in {@code CSDAT01Y.cpy} ({@code MM/DD/YY}, {@code HH:MM:SS}, {@code YYYY-MM-DD HH:MM:SS.NNNNNN}
   * with a 6-digit fraction). The exact pattern strings are pinned, and each must be a valid {@link
   * DateTimeFormatter} pattern so rendering never fails at runtime.
   */
  @Test
  void date_masks_match_cobol_edit_patterns_and_are_valid_formatters() {
    assertThat(MenuOptions.DATE_MASK_MM_DD_YY).isEqualTo("MM/dd/yy");
    assertThat(MenuOptions.TIME_MASK_HH_MM_SS).isEqualTo("HH:mm:ss");
    assertThat(MenuOptions.TIMESTAMP_MASK).isEqualTo("yyyy-MM-dd HH:mm:ss.SSSSSS");
    assertThat(MenuOptions.TIMESTAMP_FRACTION_DIGITS).isEqualTo(6);

    assertThatCode(() -> DateTimeFormatter.ofPattern(MenuOptions.DATE_MASK_MM_DD_YY))
        .doesNotThrowAnyException();
    assertThatCode(() -> DateTimeFormatter.ofPattern(MenuOptions.TIME_MASK_HH_MM_SS))
        .doesNotThrowAnyException();
    assertThatCode(() -> DateTimeFormatter.ofPattern(MenuOptions.TIMESTAMP_MASK))
        .doesNotThrowAnyException();
  }

  /**
   * The published menu lists must be immutable (built with {@link java.util.List#of}); any
   * structural mutation must raise {@link UnsupportedOperationException}, guaranteeing callers
   * cannot corrupt the shared lookup tables.
   */
  @Test
  void menu_lists_are_immutable() {
    assertThatThrownBy(() -> MenuOptions.MAIN_MENU_OPTIONS.add(null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> MenuOptions.ADMIN_MENU_OPTIONS.add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
