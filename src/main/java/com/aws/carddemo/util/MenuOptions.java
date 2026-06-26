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

import java.util.List;

/**
 * Immutable lookup tables for the CardDemo online menus, the shared screen titles, and the
 * date/time format masks used by the presentation layer.
 *
 * <p>The constants in this class reproduce &mdash; byte for byte &mdash; the literal data that was
 * originally hard-coded in the following COBOL copybooks. Preserving the exact COBOL field widths
 * (and therefore the trailing/leading spaces) is required for golden-file parity between the legacy
 * z/OS application and this Spring Boot migration:
 *
 * <ul>
 *   <li>{@code app/cpy/COMEN02Y.cpy} (<code>CARDDEMO-MAIN-MENU-OPTIONS</code>) &rarr; {@link
 *       #MAIN_MENU_OPTIONS} / {@link #MAIN_MENU_OPT_COUNT}
 *   <li>{@code app/cpy/COADM02Y.cpy} (<code>CARDDEMO-ADMIN-MENU-OPTIONS</code>) &rarr; {@link
 *       #ADMIN_MENU_OPTIONS} / {@link #ADMIN_MENU_OPT_COUNT}
 *   <li>{@code app/cpy/COTTL01Y.cpy} (<code>CCDA-SCREEN-TITLE</code>) &rarr; {@link #TITLE_LINE_1},
 *       {@link #TITLE_LINE_2}, {@link #TITLE_THANK_YOU}
 *   <li>{@code app/cpy/CSDAT01Y.cpy} (<code>WS-DATE-TIME</code>) &rarr; {@link
 *       #DATE_MASK_MM_DD_YY}, {@link #TIME_MASK_HH_MM_SS}, {@link #TIMESTAMP_MASK}
 * </ul>
 *
 * <p>Each menu-option {@code name} preserves the COBOL {@code PIC X(35)} width (right space-padded
 * to exactly 35 characters); each {@code programName} preserves {@code PIC X(08)} (exactly 8
 * characters); each screen title preserves {@code PIC X(40)} (exactly 40 characters, including the
 * original leading/trailing spaces). Callers that render fixed 24x80 screens rely on these widths,
 * so the values must never be trimmed.
 */
public final class MenuOptions {

  /**
   * A single online-menu entry.
   *
   * <p>Mirrors one occurrence of the COBOL menu-option table: the numeric selector ({@code PIC
   * 9(02)}), the display {@code name} ({@code PIC X(35)}), the target CICS program {@code
   * programName} ({@code PIC X(08)}), and the required {@code userType} ({@code PIC X(01)}).
   *
   * <p>The admin menu ({@code COADM02Y}) has no user-type column, so admin entries set {@code
   * userType} to {@code null}; main-menu entries ({@code COMEN02Y}) always use {@code "U"}.
   *
   * @param number the 1-based menu selector shown to the user (COBOL {@code CDEMO-*-OPT-NUM})
   * @param name the 35-character, space-padded display label (COBOL {@code CDEMO-*-OPT-NAME})
   * @param programName the 8-character target program id (COBOL {@code CDEMO-*-OPT-PGMNAME})
   * @param userType the required user type {@code "U"} for the main menu, or {@code null} for the
   *     admin menu, which has no user-type field (COBOL {@code CDEMO-MENU-OPT-USRTYPE})
   */
  public record MenuOption(int number, String name, String programName, String userType) {}

  /**
   * Number of active main-menu options, matching {@code CDEMO-MENU-OPT-COUNT} in {@code COMEN02Y}
   * ({@code VALUE 10}). The underlying COBOL table is declared {@code OCCURS 12}, but only the
   * first {@code 10} occurrences carry data.
   */
  public static final int MAIN_MENU_OPT_COUNT = 10;

  /**
   * Number of active admin-menu options, matching {@code CDEMO-ADMIN-OPT-COUNT} in {@code COADM02Y}
   * ({@code VALUE 4}). The underlying COBOL table is declared {@code OCCURS 9}, but only the first
   * {@code 4} occurrences carry data.
   */
  public static final int ADMIN_MENU_OPT_COUNT = 4;

  /**
   * Main (standard-user) menu options, in COBOL table order, from {@code COMEN02Y.cpy} (<code>
   * CARDDEMO-MAIN-MENU-OPTIONS</code>). Every entry has {@code userType == "U"}. The list is
   * immutable; its size equals {@link #MAIN_MENU_OPT_COUNT}.
   */
  public static final List<MenuOption> MAIN_MENU_OPTIONS =
      List.of(
          new MenuOption(1, "Account View                       ", "COACTVWC", "U"),
          new MenuOption(2, "Account Update                     ", "COACTUPC", "U"),
          new MenuOption(3, "Credit Card List                   ", "COCRDLIC", "U"),
          new MenuOption(4, "Credit Card View                   ", "COCRDSLC", "U"),
          new MenuOption(5, "Credit Card Update                 ", "COCRDUPC", "U"),
          new MenuOption(6, "Transaction List                   ", "COTRN00C", "U"),
          new MenuOption(7, "Transaction View                   ", "COTRN01C", "U"),
          new MenuOption(8, "Transaction Add                    ", "COTRN02C", "U"),
          new MenuOption(9, "Transaction Reports                ", "CORPT00C", "U"),
          new MenuOption(10, "Bill Payment                       ", "COBIL00C", "U"));

  /**
   * Admin (security) menu options, in COBOL table order, from {@code COADM02Y.cpy} (<code>
   * CARDDEMO-ADMIN-MENU-OPTIONS</code>). The admin copybook has no user-type column, so every entry
   * sets {@code userType} to {@code null}. The list is immutable; its size equals {@link
   * #ADMIN_MENU_OPT_COUNT}.
   */
  public static final List<MenuOption> ADMIN_MENU_OPTIONS =
      List.of(
          new MenuOption(1, "User List (Security)               ", "COUSR00C", null),
          new MenuOption(2, "User Add (Security)                ", "COUSR01C", null),
          new MenuOption(3, "User Update (Security)             ", "COUSR02C", null),
          new MenuOption(4, "User Delete (Security)             ", "COUSR03C", null));

  /**
   * First screen-title line, {@code CCDA-TITLE01} ({@code PIC X(40)}) from {@code COTTL01Y.cpy}.
   * Exactly 40 characters including the original centering spaces.
   */
  public static final String TITLE_LINE_1 = "      AWS Mainframe Modernization       ";

  /**
   * Second screen-title line, {@code CCDA-TITLE02} ({@code PIC X(40)}) from {@code COTTL01Y.cpy}.
   * The active copybook value is the {@code CardDemo} line; the commented-out alternative {@code
   * "Credit Card Demo Application (CCDA)"} is intentionally not used. Exactly 40 characters.
   */
  public static final String TITLE_LINE_2 = "              CardDemo                  ";

  /**
   * Sign-off / thank-you screen line, {@code CCDA-THANK-YOU} ({@code PIC X(40)}) from {@code
   * COTTL01Y.cpy}. Exactly 40 characters including the trailing space.
   */
  public static final String TITLE_THANK_YOU = "Thank you for using CCDA application... ";

  /**
   * Date mask for {@code WS-CURDATE-MM-DD-YY} in {@code CSDAT01Y.cpy}, whose COBOL group lays out
   * {@code MM "/" DD "/" YY}. Expressed as a {@link java.time.format.DateTimeFormatter} pattern
   * ({@code MM/dd/yy}); the COBOL {@code MM}/{@code DD}/{@code YY} parts map to the java.time
   * pattern letters {@code MM}/{@code dd}/{@code yy}.
   */
  public static final String DATE_MASK_MM_DD_YY = "MM/dd/yy";

  /**
   * Time mask for {@code WS-CURTIME-HH-MM-SS} in {@code CSDAT01Y.cpy}, whose COBOL group lays out
   * {@code HH ":" MM ":" SS}. Expressed as a {@link java.time.format.DateTimeFormatter} pattern
   * ({@code HH:mm:ss}); the COBOL {@code HH}/{@code MM}/{@code SS} parts map to the java.time
   * pattern letters {@code HH}/{@code mm}/{@code ss}.
   */
  public static final String TIME_MASK_HH_MM_SS = "HH:mm:ss";

  /**
   * Timestamp mask for {@code WS-TIMESTAMP} in {@code CSDAT01Y.cpy}, whose COBOL group lays out
   * {@code YYYY "-" MM "-" DD " " HH ":" MM ":" SS "." NNNNNN} (a 4-2-2 date, a space, a 2-2-2
   * time, a dot, then a 6-digit fraction). Expressed as a {@link
   * java.time.format.DateTimeFormatter} pattern ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}); the COBOL
   * {@code YYYY}/{@code DD}/{@code MM}/{@code SS} parts map to the java.time pattern letters {@code
   * yyyy}/{@code dd}/{@code MM}/{@code ss}.
   */
  public static final String TIMESTAMP_MASK = "yyyy-MM-dd HH:mm:ss.SSSSSS";

  /**
   * Number of fractional-second digits in {@link #TIMESTAMP_MASK}, matching {@code
   * WS-TIMESTAMP-TM-MS6} ({@code PIC 9(06)}) in {@code CSDAT01Y.cpy}.
   */
  public static final int TIMESTAMP_FRACTION_DIGITS = 6;

  /** Non-instantiable utility class; all members are {@code static}. */
  private MenuOptions() {
    // utility class
    throw new AssertionError("MenuOptions is a non-instantiable utility class");
  }
}
