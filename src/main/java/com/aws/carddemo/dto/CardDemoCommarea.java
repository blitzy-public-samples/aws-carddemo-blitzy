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
package com.aws.carddemo.dto;

import java.io.Serializable;
import java.util.Objects;

/**
 * Migrated representation of the COBOL {@code CARDDEMO-COMMAREA} communication area defined by
 * copybook {@code COCOM01Y} (legacy source {@code legacy/app/cpy/COCOM01Y.cpy}).
 *
 * <p>In the legacy z/OS CICS application this 01-level structure was the pseudo-conversational
 * COMMAREA passed across {@code RECEIVE}/{@code SEND} cycles and {@code EXEC CICS XCTL}/{@code
 * LINK} program transfers. It carried the signed-on user id and role, the from/to
 * transaction-program routing, and the customer/account/card context selected on the previous
 * screen.
 *
 * <p>In the modernized Spring Boot application this becomes the <strong>server-side
 * session/navigation state</strong> object that preserves the original online navigation parity
 * (Agent Action Plan &sect;0.6.5). Per AAP &sect;0.4.2, exactly one Java type is produced for this
 * copybook 01-level, and {@code COPY COCOM01Y} call sites are translated to {@code import
 * com.aws.carddemo.dto.CardDemoCommarea}.
 *
 * <p>This is a plain, framework-light POJO. It is intentionally <em>not</em> a JPA entity and holds
 * no persistence, web, or domain dependencies so that it can be referenced freely by the {@code
 * web} controllers and {@code service.online} services without creating layering cycles.
 *
 * <p><strong>Fixed-width contract.</strong> Every field documents the exact COBOL {@code PIC}
 * picture clause and length it migrates. These lengths are part of the behavioral contract
 * inherited from the 3270/BMS screens and the VSAM record layouts; callers must honor them (for
 * example, zero-padding the numeric card number to 16 digits when rendering) rather than silently
 * truncating.
 *
 * <p><strong>PII hygiene.</strong> This object carries user id, customer id, account id, and card
 * number. {@link #toString()} masks the card number (revealing only the last four digits) per AAP
 * &sect;0.6.6; no password field exists on this copybook and none is introduced here.
 */
public class CardDemoCommarea implements Serializable {

  /**
   * Serialization version identifier. The COMMAREA is stored as server-side session state, which
   * may be serialized by the servlet container (for example during session replication or
   * persistence), so an explicit, stable {@code serialVersionUID} is declared.
   */
  private static final long serialVersionUID = 1L;

  /**
   * Value of {@code CDEMO-USER-TYPE} that flags an administrator, mirroring the COBOL condition
   * name {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}.
   */
  public static final String USER_TYPE_ADMIN = "A";

  /**
   * Value of {@code CDEMO-USER-TYPE} that flags a standard user, mirroring the COBOL condition name
   * {@code 88 CDEMO-USRTYP-USER VALUE 'U'}.
   */
  public static final String USER_TYPE_USER = "U";

  /**
   * Value of {@code CDEMO-PGM-CONTEXT} for a first ("enter") entry into a program, mirroring the
   * COBOL condition name {@code 88 CDEMO-PGM-ENTER VALUE 0}.
   */
  public static final int PGM_CONTEXT_ENTER = 0;

  /**
   * Value of {@code CDEMO-PGM-CONTEXT} for a re-entry into a program, mirroring the COBOL condition
   * name {@code 88 CDEMO-PGM-REENTER VALUE 1}.
   */
  public static final int PGM_CONTEXT_REENTER = 1;

  // ===== CDEMO-GENERAL-INFO =====================================================================

  /** {@code CDEMO-FROM-TRANID} — PIC X(04). Transaction id of the calling (from) transaction. */
  private String fromTranId;

  /** {@code CDEMO-FROM-PROGRAM} — PIC X(08). Program name of the calling (from) program. */
  private String fromProgram;

  /** {@code CDEMO-TO-TRANID} — PIC X(04). Transaction id of the target (to) transaction. */
  private String toTranId;

  /** {@code CDEMO-TO-PROGRAM} — PIC X(08). Program name of the target (to) program. */
  private String toProgram;

  /** {@code CDEMO-USER-ID} — PIC X(08). Signed-on user identifier. */
  private String userId;

  /**
   * {@code CDEMO-USER-TYPE} — PIC X(01). Role flag: {@value #USER_TYPE_ADMIN} for administrator or
   * {@value #USER_TYPE_USER} for standard user. See {@link #isAdmin()} / {@link #isUser()}.
   */
  private String userType;

  /**
   * {@code CDEMO-PGM-CONTEXT} — PIC 9(01). Program entry context: {@value #PGM_CONTEXT_ENTER} for a
   * first entry or {@value #PGM_CONTEXT_REENTER} for a re-entry. See {@link #isPgmEnter()} / {@link
   * #isPgmReenter()}.
   */
  private int pgmContext;

  // ===== CDEMO-CUSTOMER-INFO ====================================================================

  /** {@code CDEMO-CUST-ID} — PIC 9(09). Selected customer identifier (numeric, up to 9 digits). */
  private Long custId;

  /** {@code CDEMO-CUST-FNAME} — PIC X(25). Customer first name. */
  private String custFName;

  /** {@code CDEMO-CUST-MNAME} — PIC X(25). Customer middle name. */
  private String custMName;

  /** {@code CDEMO-CUST-LNAME} — PIC X(25). Customer last name. */
  private String custLName;

  // ===== CDEMO-ACCOUNT-INFO =====================================================================

  /** {@code CDEMO-ACCT-ID} — PIC 9(11). Selected account identifier (numeric, up to 11 digits). */
  private Long acctId;

  /** {@code CDEMO-ACCT-STATUS} — PIC X(01). Account status indicator. */
  private String acctStatus;

  // ===== CDEMO-CARD-INFO ========================================================================

  /**
   * {@code CDEMO-CARD-NUM} — PIC 9(16). Selected card number as a 16-digit numeric value.
   *
   * <p>A 16-digit value fits within a {@link Long}. Because card numbers can carry leading zeros,
   * callers must zero-pad to 16 digits when rendering (for example {@code String.format("%016d",
   * cardNum)}). Floating-point types are never used for this identifier.
   */
  private Long cardNum;

  // ===== CDEMO-MORE-INFO ========================================================================

  /** {@code CDEMO-LAST-MAP} — PIC X(7). BMS map name of the last screen rendered. */
  private String lastMap;

  /** {@code CDEMO-LAST-MAPSET} — PIC X(7). BMS mapset name of the last screen rendered. */
  private String lastMapset;

  /**
   * Creates an empty communication area, used for session/state instantiation and serialization.
   */
  public CardDemoCommarea() {
    // No-args constructor intentionally left empty; fields default to null/zero as in a fresh
    // COMMAREA (low-values/zeroes) and are populated by the controller/service layers.
  }

  // ===== 88-level condition-name helpers (control-flow parity) ==================================

  /**
   * Mirrors the COBOL condition {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}.
   *
   * @return {@code true} when the user type denotes an administrator
   */
  public boolean isAdmin() {
    return USER_TYPE_ADMIN.equals(userType);
  }

  /**
   * Mirrors the COBOL condition {@code 88 CDEMO-USRTYP-USER VALUE 'U'}.
   *
   * @return {@code true} when the user type denotes a standard user
   */
  public boolean isUser() {
    return USER_TYPE_USER.equals(userType);
  }

  /** Sets the user type to administrator, mirroring {@code SET CDEMO-USRTYP-ADMIN TO TRUE}. */
  public void setUsrTypAdmin() {
    this.userType = USER_TYPE_ADMIN;
  }

  /** Sets the user type to standard user, mirroring {@code SET CDEMO-USRTYP-USER TO TRUE}. */
  public void setUsrTypUser() {
    this.userType = USER_TYPE_USER;
  }

  /**
   * Mirrors the COBOL condition {@code 88 CDEMO-PGM-ENTER VALUE 0}.
   *
   * @return {@code true} when the program context denotes a first entry
   */
  public boolean isPgmEnter() {
    return pgmContext == PGM_CONTEXT_ENTER;
  }

  /**
   * Mirrors the COBOL condition {@code 88 CDEMO-PGM-REENTER VALUE 1}.
   *
   * @return {@code true} when the program context denotes a re-entry
   */
  public boolean isPgmReenter() {
    return pgmContext == PGM_CONTEXT_REENTER;
  }

  /** Sets the program context to a first entry, mirroring {@code SET CDEMO-PGM-ENTER TO TRUE}. */
  public void setPgmEnter() {
    this.pgmContext = PGM_CONTEXT_ENTER;
  }

  /** Sets the program context to a re-entry, mirroring {@code SET CDEMO-PGM-REENTER TO TRUE}. */
  public void setPgmReenter() {
    this.pgmContext = PGM_CONTEXT_REENTER;
  }

  // ===== Accessors ==============================================================================

  public String getFromTranId() {
    return fromTranId;
  }

  public void setFromTranId(String fromTranId) {
    this.fromTranId = fromTranId;
  }

  public String getFromProgram() {
    return fromProgram;
  }

  public void setFromProgram(String fromProgram) {
    this.fromProgram = fromProgram;
  }

  public String getToTranId() {
    return toTranId;
  }

  public void setToTranId(String toTranId) {
    this.toTranId = toTranId;
  }

  public String getToProgram() {
    return toProgram;
  }

  public void setToProgram(String toProgram) {
    this.toProgram = toProgram;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getUserType() {
    return userType;
  }

  public void setUserType(String userType) {
    this.userType = userType;
  }

  public int getPgmContext() {
    return pgmContext;
  }

  public void setPgmContext(int pgmContext) {
    this.pgmContext = pgmContext;
  }

  public Long getCustId() {
    return custId;
  }

  public void setCustId(Long custId) {
    this.custId = custId;
  }

  public String getCustFName() {
    return custFName;
  }

  public void setCustFName(String custFName) {
    this.custFName = custFName;
  }

  public String getCustMName() {
    return custMName;
  }

  public void setCustMName(String custMName) {
    this.custMName = custMName;
  }

  public String getCustLName() {
    return custLName;
  }

  public void setCustLName(String custLName) {
    this.custLName = custLName;
  }

  public Long getAcctId() {
    return acctId;
  }

  public void setAcctId(Long acctId) {
    this.acctId = acctId;
  }

  public String getAcctStatus() {
    return acctStatus;
  }

  public void setAcctStatus(String acctStatus) {
    this.acctStatus = acctStatus;
  }

  public Long getCardNum() {
    return cardNum;
  }

  public void setCardNum(Long cardNum) {
    this.cardNum = cardNum;
  }

  public String getLastMap() {
    return lastMap;
  }

  public void setLastMap(String lastMap) {
    this.lastMap = lastMap;
  }

  public String getLastMapset() {
    return lastMapset;
  }

  public void setLastMapset(String lastMapset) {
    this.lastMapset = lastMapset;
  }

  // ===== Object contract ========================================================================

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CardDemoCommarea)) {
      return false;
    }
    CardDemoCommarea that = (CardDemoCommarea) o;
    return pgmContext == that.pgmContext
        && Objects.equals(fromTranId, that.fromTranId)
        && Objects.equals(fromProgram, that.fromProgram)
        && Objects.equals(toTranId, that.toTranId)
        && Objects.equals(toProgram, that.toProgram)
        && Objects.equals(userId, that.userId)
        && Objects.equals(userType, that.userType)
        && Objects.equals(custId, that.custId)
        && Objects.equals(custFName, that.custFName)
        && Objects.equals(custMName, that.custMName)
        && Objects.equals(custLName, that.custLName)
        && Objects.equals(acctId, that.acctId)
        && Objects.equals(acctStatus, that.acctStatus)
        && Objects.equals(cardNum, that.cardNum)
        && Objects.equals(lastMap, that.lastMap)
        && Objects.equals(lastMapset, that.lastMapset);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        fromTranId,
        fromProgram,
        toTranId,
        toProgram,
        userId,
        userType,
        pgmContext,
        custId,
        custFName,
        custMName,
        custLName,
        acctId,
        acctStatus,
        cardNum,
        lastMap,
        lastMapset);
  }

  /**
   * Renders a diagnostic representation of the navigation state. The card number is masked to its
   * last four digits to honor the project's PII hygiene posture (AAP &sect;0.6.6); no other field
   * is a secret (this copybook has no password field).
   *
   * @return a masked, human-readable description of this communication area
   */
  @Override
  public String toString() {
    return "CardDemoCommarea{"
        + "fromTranId="
        + fromTranId
        + ", fromProgram="
        + fromProgram
        + ", toTranId="
        + toTranId
        + ", toProgram="
        + toProgram
        + ", userId="
        + userId
        + ", userType="
        + userType
        + ", pgmContext="
        + pgmContext
        + ", custId="
        + custId
        + ", custFName="
        + custFName
        + ", custMName="
        + custMName
        + ", custLName="
        + custLName
        + ", acctId="
        + acctId
        + ", acctStatus="
        + acctStatus
        + ", cardNum="
        + maskCardNum()
        + ", lastMap="
        + lastMap
        + ", lastMapset="
        + lastMapset
        + '}';
  }

  /**
   * Produces a masked rendering of {@link #cardNum} that reveals only the last four digits,
   * matching the 16-digit {@code PIC 9(16)} width with leading mask characters.
   *
   * @return {@code "null"} when no card number is set, otherwise a 16-character masked value whose
   *     final four characters are the last four digits of the card number
   */
  private String maskCardNum() {
    if (cardNum == null) {
      return "null";
    }
    long lastFour = Math.floorMod(cardNum, 10000L);
    return "************" + String.format("%04d", lastFour);
  }
}
