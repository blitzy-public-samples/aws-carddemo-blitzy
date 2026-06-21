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
 * Screen view contract for the legacy CICS/BMS <strong>Add User</strong> screen.
 *
 * <p>This DTO is the modernized, framework-light transport object that replaces the 3270 BMS map
 * {@code COUSR01} (CICS transaction {@code CU01}, an <em>admin-only</em> function). It preserves
 * the exact field inventory and fixed widths of the legacy symbolic map so that the online
 * navigation contract is reproduced with byte/semantic parity.
 *
 * <p>Field names, order, and lengths are derived from the authoritative input map {@code COUSR1AI}
 * in {@code legacy/app/cpy-bms/COUSR01.CPY}; rendering semantics (notably the masked password
 * field, {@code PASSWD ATTRB=(DRK,...)}) are taken from {@code legacy/app/bms/COUSR01.bms}. Only
 * the meaningful {@code <NAME>I} data value fields are modeled; all BMS plumbing fields (the {@code
 * L}/{@code F}/{@code A}/{@code C}/{@code P}/{@code H}/ {@code V} suffixed attributes and {@code
 * FILLER} pads) are intentionally omitted because they carry no business data.
 *
 * <p>Each {@code PIC X(n)} alphanumeric field maps to a {@link String} constrained with {@link
 * Size @Size(max = n)} to preserve the original column widths.
 *
 * <p><strong>Security (AAP &sect;0.6.6 — credential hygiene):</strong> the {@code passwd} field is
 * a transient, masked transport value only. On the legacy screen it is rendered dark (non-display);
 * here it carries the clear-text password solely between the browser and the service layer, where
 * {@code service.online.UserAddService} hashes it (BCrypt) before persisting to the {@code
 * user_security} table. The value is never logged and is redacted from {@link #toString()}.
 *
 * <p>This is a plain POJO screen contract — it is intentionally <em>not</em> a JPA entity and has
 * no dependency on the persistence ({@code com.aws.carddemo.domain.*}) layer.
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 *     <p>Origin: {@code legacy/app/cpy-bms/COUSR01.CPY} + {@code legacy/app/bms/COUSR01.bms}.
 *     Authority: AAP &sect;0.4.1 (screen DTOs preserve field lengths), &sect;0.3.4 (BMS screen view
 *     contracts), &sect;0.6.6 (credential hygiene).
 */
public class UserAddScreen {

  /** Transaction identifier displayed on the screen header. COBOL: {@code TRNNAME PIC X(4)}. */
  @Size(max = 4)
  private String trnName;

  /** First title line (application/company banner). COBOL: {@code TITLE01 PIC X(40)}. */
  @Size(max = 40)
  private String title01;

  /** Current date as rendered on the screen (mm/dd/yy). COBOL: {@code CURDATE PIC X(8)}. */
  @Size(max = 8)
  private String curDate;

  /** Program name displayed on the screen header. COBOL: {@code PGMNAME PIC X(8)}. */
  @Size(max = 8)
  private String pgmName;

  /** Second title line (screen subtitle). COBOL: {@code TITLE02 PIC X(40)}. */
  @Size(max = 40)
  private String title02;

  /** Current time as rendered on the screen (hh:mm:ss). COBOL: {@code CURTIME PIC X(8)}. */
  @Size(max = 8)
  private String curTime;

  /** New user's first name input. COBOL: {@code FNAME PIC X(20)}. */
  @Size(max = 20)
  private String fName;

  /** New user's last name input. COBOL: {@code LNAME PIC X(20)}. */
  @Size(max = 20)
  private String lName;

  /** New user's user id input (8 characters). COBOL: {@code USERID PIC X(8)}. */
  @Size(max = 8)
  private String userId;

  /**
   * New user's password input. COBOL: {@code PASSWD PIC X(8)}.
   *
   * <p><strong>SENSITIVE.</strong> Rendered dark/masked on the BMS map ({@code ATTRB=(DRK,...)}).
   * This holds the clear-text password only as transient transport between the UI and the service
   * layer, which hashes it (BCrypt) before persistence (AAP &sect;0.6.6). It is redacted from
   * {@link #toString()} and must never be logged.
   */
  @Size(max = 8)
  private String passwd;

  /**
   * New user's type flag: {@code 'A'} = admin, {@code 'U'} = user. COBOL: {@code USRTYPE PIC X(1)}.
   */
  @Size(max = 1)
  private String usrType;

  /** Error/informational message line shown to the operator. COBOL: {@code ERRMSG PIC X(78)}. */
  @Size(max = 78)
  private String errMsg;

  /**
   * Green success-confirmation line shown to the operator on a successful add ({@code "User
   * &lt;id&gt; has been added ..."}).
   *
   * <p>The legacy {@code COUSR01C} writes the confirmation into the single {@code ERRMSG} field but
   * flips its colour attribute to {@code DFHGREEN} (L254) so success is visually distinct from the
   * red validation/error messages. The modernized UI cannot carry a separate colour-attribute byte,
   * so the green channel is modeled as this dedicated field: when an add succeeds the message is
   * placed here (rendered green) and {@link #errMsg} is left blank; on any validation or
   * persistence failure {@link #errMsg} carries the red message and this field is left blank. The
   * two are always mutually exclusive (QA F4-1).
   */
  @Size(max = 78)
  private String successMsg;

  /** Creates an empty Add User screen contract with all fields unset. */
  public UserAddScreen() {
    // No-args constructor for framework binding (Spring MVC / serialization).
  }

  /**
   * Returns the transaction identifier ({@code TRNNAME}).
   *
   * @return the transaction id, or {@code null} if unset
   */
  public String getTrnName() {
    return trnName;
  }

  /**
   * Sets the transaction identifier ({@code TRNNAME}).
   *
   * @param trnName the transaction id (max 4 characters)
   */
  public void setTrnName(String trnName) {
    this.trnName = trnName;
  }

  /**
   * Returns the first title line ({@code TITLE01}).
   *
   * @return the first title line, or {@code null} if unset
   */
  public String getTitle01() {
    return title01;
  }

  /**
   * Sets the first title line ({@code TITLE01}).
   *
   * @param title01 the first title line (max 40 characters)
   */
  public void setTitle01(String title01) {
    this.title01 = title01;
  }

  /**
   * Returns the current date string ({@code CURDATE}).
   *
   * @return the current date string, or {@code null} if unset
   */
  public String getCurDate() {
    return curDate;
  }

  /**
   * Sets the current date string ({@code CURDATE}).
   *
   * @param curDate the current date string (max 8 characters)
   */
  public void setCurDate(String curDate) {
    this.curDate = curDate;
  }

  /**
   * Returns the program name ({@code PGMNAME}).
   *
   * @return the program name, or {@code null} if unset
   */
  public String getPgmName() {
    return pgmName;
  }

  /**
   * Sets the program name ({@code PGMNAME}).
   *
   * @param pgmName the program name (max 8 characters)
   */
  public void setPgmName(String pgmName) {
    this.pgmName = pgmName;
  }

  /**
   * Returns the second title line ({@code TITLE02}).
   *
   * @return the second title line, or {@code null} if unset
   */
  public String getTitle02() {
    return title02;
  }

  /**
   * Sets the second title line ({@code TITLE02}).
   *
   * @param title02 the second title line (max 40 characters)
   */
  public void setTitle02(String title02) {
    this.title02 = title02;
  }

  /**
   * Returns the current time string ({@code CURTIME}).
   *
   * @return the current time string, or {@code null} if unset
   */
  public String getCurTime() {
    return curTime;
  }

  /**
   * Sets the current time string ({@code CURTIME}).
   *
   * @param curTime the current time string (max 8 characters)
   */
  public void setCurTime(String curTime) {
    this.curTime = curTime;
  }

  /**
   * Returns the new user's first name ({@code FNAME}).
   *
   * @return the first name, or {@code null} if unset
   */
  public String getFName() {
    return fName;
  }

  /**
   * Sets the new user's first name ({@code FNAME}).
   *
   * @param fName the first name (max 20 characters)
   */
  public void setFName(String fName) {
    this.fName = fName;
  }

  /**
   * Returns the new user's last name ({@code LNAME}).
   *
   * @return the last name, or {@code null} if unset
   */
  public String getLName() {
    return lName;
  }

  /**
   * Sets the new user's last name ({@code LNAME}).
   *
   * @param lName the last name (max 20 characters)
   */
  public void setLName(String lName) {
    this.lName = lName;
  }

  /**
   * Returns the new user's user id ({@code USERID}).
   *
   * @return the user id, or {@code null} if unset
   */
  public String getUserId() {
    return userId;
  }

  /**
   * Sets the new user's user id ({@code USERID}).
   *
   * @param userId the user id (max 8 characters)
   */
  public void setUserId(String userId) {
    this.userId = userId;
  }

  /**
   * Returns the new user's clear-text password ({@code PASSWD}).
   *
   * <p>This is sensitive transport data; callers must not log the returned value. The service layer
   * hashes it before persistence (AAP &sect;0.6.6).
   *
   * @return the clear-text password, or {@code null} if unset
   */
  public String getPasswd() {
    return passwd;
  }

  /**
   * Sets the new user's clear-text password ({@code PASSWD}).
   *
   * @param passwd the clear-text password (max 8 characters); hashed downstream, never persisted in
   *     clear text
   */
  public void setPasswd(String passwd) {
    this.passwd = passwd;
  }

  /**
   * Returns the user type flag ({@code USRTYPE}): {@code 'A'} = admin, {@code 'U'} = user.
   *
   * @return the user type flag, or {@code null} if unset
   */
  public String getUsrType() {
    return usrType;
  }

  /**
   * Sets the user type flag ({@code USRTYPE}): {@code 'A'} = admin, {@code 'U'} = user.
   *
   * @param usrType the user type flag (max 1 character)
   */
  public void setUsrType(String usrType) {
    this.usrType = usrType;
  }

  /**
   * Returns the error/informational message line ({@code ERRMSG}).
   *
   * @return the message line, or {@code null} if unset
   */
  public String getErrMsg() {
    return errMsg;
  }

  /**
   * Sets the error/informational message line ({@code ERRMSG}).
   *
   * @param errMsg the message line (max 78 characters)
   */
  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }

  /**
   * Returns the green success-confirmation line ({@code ERRMSG} rendered with {@code DFHGREEN}).
   *
   * @return the success message line, or {@code null} if unset
   */
  public String getSuccessMsg() {
    return successMsg;
  }

  /**
   * Sets the green success-confirmation line ({@code ERRMSG} rendered with {@code DFHGREEN}).
   *
   * @param successMsg the success message line (max 78 characters)
   */
  public void setSuccessMsg(String successMsg) {
    this.successMsg = successMsg;
  }

  /**
   * Returns a diagnostic representation of this screen contract.
   *
   * <p>The {@code passwd} field is deliberately redacted to avoid leaking sensitive credentials
   * into logs or diagnostics (AAP &sect;0.6.6).
   *
   * @return a string representation with the password redacted
   */
  @Override
  public String toString() {
    return "UserAddScreen{"
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
        + ", fName="
        + fName
        + ", lName="
        + lName
        + ", userId="
        + userId
        + ", passwd=[PROTECTED]"
        + ", usrType="
        + usrType
        + ", errMsg="
        + errMsg
        + ", successMsg="
        + successMsg
        + '}';
  }
}
