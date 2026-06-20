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
 * Screen view contract for the CardDemo <strong>Admin Menu</strong> screen.
 *
 * <p>Migrated from the legacy CICS/BMS mapset {@code COADM01} (CICS transaction {@code CA00}), this
 * plain DTO preserves the exact field layout of the BMS input map {@code COADM1AI} defined in
 * {@code legacy/app/cpy-bms/COADM01.CPY}, with every field width cross-verified against the
 * rendering reference {@code legacy/app/bms/COADM01.bms}. It is the admin-only counterpart of
 * {@code MainMenuScreen} (mapset {@code COMEN01}); the two screens share an identical field shape
 * and differ only in semantics.
 *
 * <p>This type is a framework-light "screen view contract" and intentionally carries no persistence
 * concerns: it is <em>not</em> a JPA entity and references no domain types. Each {@code String}
 * field maps to exactly one COBOL {@code PIC X(n)} value field of the BMS map and is annotated with
 * {@link Size} to preserve the original fixed-width constraint. Only the meaningful {@code <NAME>I}
 * value fields of the copybook are modeled; the BMS plumbing fields (length / flag / attribute /
 * colour / hilight) and FILLER padding are deliberately omitted.
 *
 * <p>The admin-menu option labels are supplied by the service layer (see {@code
 * util.MenuOptions.ADMIN_MENU_OPTIONS}); the admin-only access rule (COBOL {@code
 * CDEMO-USRTYP-ADMIN}) is enforced in the security / service layer and is intentionally
 * <em>not</em> represented in this DTO.
 *
 * <p>Authority: AAP section 0.4.1 ("dto/screen/*.java (17) from app/cpy-bms/*.CPY + app/bms/*.bms")
 * and section 0.3.4 (the BMS field contract is the UI fidelity reference).
 */
public class AdminMenuScreen {

  @Size(max = 4)
  private String trnName; // TRNNAME X(4)

  @Size(max = 40)
  private String title01; // TITLE01 X(40)

  @Size(max = 8)
  private String curDate; // CURDATE X(8)

  @Size(max = 8)
  private String pgmName; // PGMNAME X(8)

  @Size(max = 40)
  private String title02; // TITLE02 X(40)

  @Size(max = 8)
  private String curTime; // CURTIME X(8)

  /**
   * Menu option text lines {@code OPTN001I..OPTN012I}, each {@code PIC X(40)} (index 0 = OPTN001,
   * index 11 = OPTN012). Populated by the service layer; initialized to an empty mutable list and
   * never {@code null}.
   */
  @Size(max = 12)
  private List<@Size(max = 40) String> options = new ArrayList<>();

  @Size(max = 2)
  private String option; // OPTION X(2)

  @Size(max = 78)
  private String errMsg; // ERRMSG X(78)

  /** Creates an empty admin-menu screen with an initialized, mutable {@link #options} list. */
  public AdminMenuScreen() {
    // No-args constructor for framework instantiation and HTTP form binding.
  }

  /** Returns the transaction identifier (BMS field {@code TRNNAME}, {@code PIC X(4)}). */
  public String getTrnName() {
    return trnName;
  }

  /** Sets the transaction identifier (BMS field {@code TRNNAME}, max length 4). */
  public void setTrnName(String trnName) {
    this.trnName = trnName;
  }

  /** Returns the primary title line (BMS field {@code TITLE01}, {@code PIC X(40)}). */
  public String getTitle01() {
    return title01;
  }

  /** Sets the primary title line (BMS field {@code TITLE01}, max length 40). */
  public void setTitle01(String title01) {
    this.title01 = title01;
  }

  /** Returns the current date text (BMS field {@code CURDATE}, {@code PIC X(8)}). */
  public String getCurDate() {
    return curDate;
  }

  /** Sets the current date text (BMS field {@code CURDATE}, max length 8). */
  public void setCurDate(String curDate) {
    this.curDate = curDate;
  }

  /** Returns the originating program name (BMS field {@code PGMNAME}, {@code PIC X(8)}). */
  public String getPgmName() {
    return pgmName;
  }

  /** Sets the originating program name (BMS field {@code PGMNAME}, max length 8). */
  public void setPgmName(String pgmName) {
    this.pgmName = pgmName;
  }

  /** Returns the secondary title line (BMS field {@code TITLE02}, {@code PIC X(40)}). */
  public String getTitle02() {
    return title02;
  }

  /** Sets the secondary title line (BMS field {@code TITLE02}, max length 40). */
  public void setTitle02(String title02) {
    this.title02 = title02;
  }

  /** Returns the current time text (BMS field {@code CURTIME}, {@code PIC X(8)}). */
  public String getCurTime() {
    return curTime;
  }

  /** Sets the current time text (BMS field {@code CURTIME}, max length 8). */
  public void setCurTime(String curTime) {
    this.curTime = curTime;
  }

  /** Returns the mutable list of admin-menu option text lines (OPTN001..OPTN012, each max 40). */
  public List<String> getOptions() {
    return options;
  }

  /** Sets the list of admin-menu option text lines (OPTN001..OPTN012, each max 40). */
  public void setOptions(List<String> options) {
    this.options = options;
  }

  /** Returns the user's menu selection (BMS field {@code OPTION}, {@code PIC X(2)}). */
  public String getOption() {
    return option;
  }

  /** Sets the user's menu selection (BMS field {@code OPTION}, max length 2). */
  public void setOption(String option) {
    this.option = option;
  }

  /** Returns the error / status message line (BMS field {@code ERRMSG}, {@code PIC X(78)}). */
  public String getErrMsg() {
    return errMsg;
  }

  /** Sets the error / status message line (BMS field {@code ERRMSG}, max length 78). */
  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }
}
