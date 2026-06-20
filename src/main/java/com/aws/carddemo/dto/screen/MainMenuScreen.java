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

import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * Screen view contract for the CardDemo <strong>Main Menu</strong> screen.
 *
 * <p>This DTO is the Java migration of the legacy CICS/BMS main-menu map {@code COMEN01} (CICS
 * transaction {@code CM00}). It is a plain, framework-light "screen view contract" POJO &mdash;
 * <em>not</em> a JPA entity &mdash; that carries the rendered field values exchanged between the
 * presentation layer and {@code com.aws.carddemo.service.online.MainMenuService}.
 *
 * <p><strong>Source of truth.</strong> Field names, order, and fixed widths are taken verbatim from
 * the BMS symbolic copybook {@code legacy/app/cpy-bms/COMEN01.CPY} (the authoritative {@code
 * COMEN1AI} input map) and cross-checked against the mapset {@code legacy/app/bms/COMEN01.bms}.
 * Only the meaningful {@code <name>I} data-value fields are modeled here; the BMS plumbing
 * sub-fields (length {@code L}, flag {@code F}, attribute {@code A}, colour {@code C},
 * programmed-symbol {@code P}, highlight {@code H}, validation {@code V}) together with the {@code
 * FILLER} pads and the {@code COMEN1AO} output redefinition are intentionally omitted.
 *
 * <p>Each COBOL {@code PIC X(n)} alphanumeric field maps to a {@link String} bounded by a {@link
 * Size} constraint of the same width, preserving the 3270 field geometry. The twelve repeating
 * menu-option text lines ({@code OPTN001I}..{@code OPTN012I}, each {@code PIC X(40)}) follow the
 * "repeating rows &rarr; {@link List}" pattern: {@link #getOptions()} returns up to twelve entries
 * where index {@code 0} corresponds to {@code OPTN001I} and index {@code 11} to {@code OPTN012I},
 * with every entry being at most 40 characters long.
 *
 * <p>The menu option labels and program-routing semantics live in the service layer ({@code
 * service.online.MainMenuService} backed by {@code util.MenuOptions}); this contract only conveys
 * the already-rendered option text and the raw operator selection. The {@code OPTION} field is
 * numeric and right-justified on the BMS map, but is retained verbatim as a {@link String} so that
 * parsing and range validation remain the responsibility of the service layer.
 *
 * <p>Migration authority: AAP &sect;0.4.1 ({@code dto/screen/*.java (17) <- app/cpy-bms/*.CPY +
 * app/bms/*.bms}) and &sect;0.3.4 (the UI-parity reference is the BMS field contract itself).
 */
public class MainMenuScreen {

  @Size(max = 4)
  private String trnName; // TRNNAME PIC X(4)

  @Size(max = 40)
  private String title01; // TITLE01 PIC X(40)

  @Size(max = 8)
  private String curDate; // CURDATE PIC X(8)

  @Size(max = 8)
  private String pgmName; // PGMNAME PIC X(8)

  @Size(max = 40)
  private String title02; // TITLE02 PIC X(40)

  @Size(max = 8)
  private String curTime; // CURTIME PIC X(8)

  /**
   * Rendered menu-option text lines mapping the twelve repeating {@code OPTN001I}..{@code OPTN012I}
   * fields, each {@code PIC X(40)}. Index {@code 0} holds {@code OPTN001I} through index {@code 11}
   * holding {@code OPTN012I}; the list carries at most twelve entries and every entry must be at
   * most 40 characters long. Initialized to an empty mutable list so callers can populate it
   * incrementally without risking a {@link NullPointerException}.
   */
  private List<String> options;

  @Size(max = 2)
  private String option; // OPTION PIC X(2) - operator's raw menu selection input

  @Size(max = 78)
  private String errMsg; // ERRMSG PIC X(78)

  /**
   * Creates an empty main-menu screen contract with an initialized, mutable {@link #options} list.
   */
  public MainMenuScreen() {
    this.options = new ArrayList<>();
  }

  /**
   * Returns the transaction identifier ({@code TRNNAME}, {@code PIC X(4)}) echoed in the header.
   *
   * @return the transaction id, or {@code null} if unset
   */
  public String getTrnName() {
    return trnName;
  }

  /**
   * Sets the transaction identifier ({@code TRNNAME}, {@code PIC X(4)}).
   *
   * @param trnName the transaction id (at most 4 characters)
   */
  public void setTrnName(String trnName) {
    this.trnName = trnName;
  }

  /**
   * Returns the first header title line ({@code TITLE01}, {@code PIC X(40)}).
   *
   * @return the first title line, or {@code null} if unset
   */
  public String getTitle01() {
    return title01;
  }

  /**
   * Sets the first header title line ({@code TITLE01}, {@code PIC X(40)}).
   *
   * @param title01 the first title line (at most 40 characters)
   */
  public void setTitle01(String title01) {
    this.title01 = title01;
  }

  /**
   * Returns the current-date header field ({@code CURDATE}, {@code PIC X(8)}, format {@code
   * mm/dd/yy}).
   *
   * @return the formatted current date, or {@code null} if unset
   */
  public String getCurDate() {
    return curDate;
  }

  /**
   * Sets the current-date header field ({@code CURDATE}, {@code PIC X(8)}).
   *
   * @param curDate the formatted current date (at most 8 characters)
   */
  public void setCurDate(String curDate) {
    this.curDate = curDate;
  }

  /**
   * Returns the owning program name ({@code PGMNAME}, {@code PIC X(8)}) echoed in the header.
   *
   * @return the program name, or {@code null} if unset
   */
  public String getPgmName() {
    return pgmName;
  }

  /**
   * Sets the owning program name ({@code PGMNAME}, {@code PIC X(8)}).
   *
   * @param pgmName the program name (at most 8 characters)
   */
  public void setPgmName(String pgmName) {
    this.pgmName = pgmName;
  }

  /**
   * Returns the second header title line ({@code TITLE02}, {@code PIC X(40)}).
   *
   * @return the second title line, or {@code null} if unset
   */
  public String getTitle02() {
    return title02;
  }

  /**
   * Sets the second header title line ({@code TITLE02}, {@code PIC X(40)}).
   *
   * @param title02 the second title line (at most 40 characters)
   */
  public void setTitle02(String title02) {
    this.title02 = title02;
  }

  /**
   * Returns the current-time header field ({@code CURTIME}, {@code PIC X(8)}, format {@code
   * hh:mm:ss}).
   *
   * @return the formatted current time, or {@code null} if unset
   */
  public String getCurTime() {
    return curTime;
  }

  /**
   * Sets the current-time header field ({@code CURTIME}, {@code PIC X(8)}).
   *
   * @param curTime the formatted current time (at most 8 characters)
   */
  public void setCurTime(String curTime) {
    this.curTime = curTime;
  }

  /**
   * Returns the rendered menu-option text lines ({@code OPTN001I}..{@code OPTN012I}, each {@code
   * PIC X(40)}). The list never exceeds twelve entries; index {@code 0} maps to {@code OPTN001I}
   * and index {@code 11} to {@code OPTN012I}.
   *
   * @return the mutable list of menu-option text lines (never {@code null} by default)
   */
  public List<String> getOptions() {
    return options;
  }

  /**
   * Sets the rendered menu-option text lines ({@code OPTN001I}..{@code OPTN012I}). Callers should
   * supply at most twelve entries, each at most 40 characters long, ordered so that index {@code 0}
   * corresponds to {@code OPTN001I}.
   *
   * @param options the list of menu-option text lines
   */
  public void setOptions(List<String> options) {
    this.options = options;
  }

  /**
   * Returns the operator's raw menu selection ({@code OPTION}, {@code PIC X(2)}). The value is kept
   * verbatim as text; numeric parsing and range validation are performed by the service layer.
   *
   * @return the raw selection text, or {@code null} if unset
   */
  public String getOption() {
    return option;
  }

  /**
   * Sets the operator's raw menu selection ({@code OPTION}, {@code PIC X(2)}).
   *
   * @param option the raw selection text (at most 2 characters)
   */
  public void setOption(String option) {
    this.option = option;
  }

  /**
   * Returns the error/informational message line ({@code ERRMSG}, {@code PIC X(78)}).
   *
   * @return the message text, or {@code null} if unset
   */
  public String getErrMsg() {
    return errMsg;
  }

  /**
   * Sets the error/informational message line ({@code ERRMSG}, {@code PIC X(78)}).
   *
   * @param errMsg the message text (at most 78 characters)
   */
  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }
}
