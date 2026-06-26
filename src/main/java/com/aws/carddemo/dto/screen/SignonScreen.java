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

/**
 * Screen view contract for the migrated CardDemo sign-on / login screen.
 *
 * <p>This DTO is the modernized Java equivalent of the legacy CICS/BMS sign-on screen {@code
 * COSGN00} (CICS transaction {@code CC00}). It carries only the meaningful data-value fields
 * exchanged between the 3270 terminal and the application and is consumed by the web {@code
 * SignonController} together with {@code service.online.SignonService}.
 *
 * <p>Origin and authority:
 *
 * <ul>
 *   <li>Field names and fixed widths are derived from the symbolic copybook {@code
 *       legacy/app/cpy-bms/COSGN00.CPY} (the authoritative {@code COSGN0AI} input map).
 *   <li>Field placement, length, and attributes (e.g. the masked password field) are confirmed by
 *       the BMS mapset {@code legacy/app/bms/COSGN00.bms}.
 * </ul>
 *
 * <p>Each {@link String} property maps one BMS screen field whose COBOL {@code PIC X(n)} clause is
 * preserved as a {@link Size} maximum length, mirroring the original 24x80 terminal layout for
 * behavioral parity (AAP section 0.4.1 / section 0.3.4). Only logical value fields are modeled; the
 * BMS plumbing fields (length halfword, attribute byte, and color/protect/highlight/validation
 * bytes) and the redefining {@code COSGN0AO} output map are intentionally omitted, since the input
 * value field and its output counterpart represent the same logical field.
 *
 * <p>Security: the {@code passwd} field corresponds to a BMS {@code ATTRB=(DRK,...)} non-display
 * (masked) field. It is never written to logs and is deliberately excluded from {@link #toString()}
 * to preserve credential hygiene (AAP section 0.6.6). Authentication and password hashing are
 * performed in the security/service layer, not in this view contract.
 *
 * <p>This is a plain, framework-light data-transfer object: it has no JPA mapping and no dependency
 * on the domain layer; entity-to-DTO mapping is the responsibility of the service/mapper layer.
 */
public class SignonScreen {

  // TRNNAME PIC X(4) - transaction identifier displayed on the screen
  @Size(max = 4)
  private String trnName;

  // TITLE01 PIC X(40) - title line 1
  @Size(max = 40)
  private String title01;

  // CURDATE PIC X(8) - current date (mm/dd/yy)
  @Size(max = 8)
  private String curDate;

  // PGMNAME PIC X(8) - program name
  @Size(max = 8)
  private String pgmName;

  // TITLE02 PIC X(40) - title line 2
  @Size(max = 40)
  private String title02;

  // CURTIME PIC X(9) - current time (9 characters on this screen)
  @Size(max = 9)
  private String curTime;

  // APPLID PIC X(8) - CICS application id
  @Size(max = 8)
  private String applId;

  // SYSID PIC X(8) - CICS system id
  @Size(max = 8)
  private String sysId;

  // USERID PIC X(8) - user id input
  @Size(max = 8)
  private String userId;

  // PASSWD PIC X(8) - password input (SENSITIVE: BMS ATTRB=DRK, masked / non-display)
  @Size(max = 8)
  private String passwd;

  // ERRMSG PIC X(78) - error message line
  @Size(max = 78)
  private String errMsg;

  /** Creates an empty sign-on screen contract; all fields are initially {@code null}. */
  public SignonScreen() {
    // Intentionally empty: no-args constructor for framework instantiation and form data binding.
  }

  public String getTrnName() {
    return trnName;
  }

  public void setTrnName(String trnName) {
    this.trnName = trnName;
  }

  public String getTitle01() {
    return title01;
  }

  public void setTitle01(String title01) {
    this.title01 = title01;
  }

  public String getCurDate() {
    return curDate;
  }

  public void setCurDate(String curDate) {
    this.curDate = curDate;
  }

  public String getPgmName() {
    return pgmName;
  }

  public void setPgmName(String pgmName) {
    this.pgmName = pgmName;
  }

  public String getTitle02() {
    return title02;
  }

  public void setTitle02(String title02) {
    this.title02 = title02;
  }

  public String getCurTime() {
    return curTime;
  }

  public void setCurTime(String curTime) {
    this.curTime = curTime;
  }

  public String getApplId() {
    return applId;
  }

  public void setApplId(String applId) {
    this.applId = applId;
  }

  public String getSysId() {
    return sysId;
  }

  public void setSysId(String sysId) {
    this.sysId = sysId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getPasswd() {
    return passwd;
  }

  public void setPasswd(String passwd) {
    this.passwd = passwd;
  }

  public String getErrMsg() {
    return errMsg;
  }

  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }

  /**
   * Returns a diagnostic representation of this screen contract.
   *
   * <p>The {@code passwd} field is intentionally omitted to avoid leaking credentials into logs or
   * diagnostics (AAP section 0.6.6).
   *
   * @return a string describing the non-sensitive screen fields
   */
  @Override
  public String toString() {
    return "SignonScreen{"
        + "trnName="
        + trnName
        + ", title01="
        + title01
        + ", curDate="
        + curDate
        + ", pgmName="
        + pgmName
        + ", title02="
        + title02
        + ", curTime="
        + curTime
        + ", applId="
        + applId
        + ", sysId="
        + sysId
        + ", userId="
        + userId
        + ", errMsg="
        + errMsg
        + '}';
  }
}
