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
 * Screen view contract for the legacy CICS/BMS <em>Card View / Select</em> screen.
 *
 * <p>Migrated field-for-field from the BMS mapset {@code COCRDSL} (CICS transaction {@code CCDL},
 * online program {@code COCRDSLC}). This is the read-only single-card detail view; its editable
 * twin is {@code COCRDUP} (card update), which additionally carries an expiry-day field and a
 * second function-key legend line.
 *
 * <p>This is a plain, framework-light screen "view contract" POJO &mdash; <strong>not</strong> a
 * JPA entity. It carries no persistence, domain, or monetary semantics; the actual card read is
 * performed by {@code com.aws.carddemo.service.online.CardViewService} (keyed on the {@linkplain
 * #getAcctSid() account id} plus the {@linkplain #getCardSid() card number}) against the card
 * repository.
 *
 * <p>Each property corresponds to exactly one BMS map data field. Fixed widths are preserved
 * exactly as declared in the symbolic copybook so the modern screen honours the original 3270
 * field-length contract; every {@code String} is annotated with {@link Size} carrying the COBOL
 * {@code PIC X(n)} length. No screen "plumbing" sub-fields (length {@code L}, flag {@code F},
 * attribute {@code A}, colour {@code C}, programmed-symbol {@code P}, highlight {@code H} or
 * validation {@code V}) and no {@code FILLER} padding are modelled.
 *
 * <p>Origin: {@code legacy/app/cpy-bms/COCRDSL.CPY} (authoritative field names and lengths) and
 * {@code legacy/app/bms/COCRDSL.bms} (3270 rendering reference).
 *
 * <p>Authority: Agent Action Plan &sect;0.4.1 (<em>"dto/screen/*.java (17) &larr; app/cpy-bms/*.CPY
 * + app/bms/*.bms &mdash; preserving field lengths"</em>) and &sect;0.3.4 (the UI fidelity
 * reference is the BMS map field contract itself).
 */
public class CardViewScreen {

  // TRNNAME PIC X(4) - transaction id echoed in the screen header.
  @Size(max = 4)
  private String trnName;

  // TITLE01 PIC X(40) - first header title line (application title).
  @Size(max = 40)
  private String title01;

  // CURDATE PIC X(8) - current date header field (mm/dd/yy).
  @Size(max = 8)
  private String curDate;

  // PGMNAME PIC X(8) - current program name echoed in the header.
  @Size(max = 8)
  private String pgmName;

  // TITLE02 PIC X(40) - second header title line (screen title).
  @Size(max = 40)
  private String title02;

  // CURTIME PIC X(8) - current time header field (hh:mm:ss).
  @Size(max = 8)
  private String curTime;

  // ACCTSID PIC X(11) - account number entered/selected for the card lookup.
  @Size(max = 11)
  private String acctSid;

  // CARDSID PIC X(16) - card number entered/selected for the card lookup.
  @Size(max = 16)
  private String cardSid;

  // CRDNAME PIC X(50) - embossed name on the card.
  @Size(max = 50)
  private String crdName;

  // CRDSTCD PIC X(1) - card active status code (Y = active, N = inactive).
  @Size(max = 1)
  private String crdStcd;

  // EXPMON PIC X(2) - card expiry month (mm).
  @Size(max = 2)
  private String expMon;

  // EXPYEAR PIC X(4) - card expiry year (yyyy).
  @Size(max = 4)
  private String expYear;

  // INFOMSG PIC X(40) - informational message line.
  @Size(max = 40)
  private String infoMsg;

  // ERRMSG PIC X(80) - error message line.
  @Size(max = 80)
  private String errMsg;

  // FKEYS PIC X(75) - function-key legend text (e.g. "ENTER=Search Cards  F3=Exit").
  @Size(max = 75)
  private String fkeys;

  /** Creates an empty screen contract for framework instantiation and form binding. */
  public CardViewScreen() {
    // Intentionally empty: a public no-argument constructor is required for bean/form binding.
  }

  /**
   * Returns the transaction identifier echoed in the screen header.
   *
   * @return the {@code TRNNAME} value (maximum 4 characters)
   */
  public String getTrnName() {
    return trnName;
  }

  /**
   * Sets the transaction identifier echoed in the screen header.
   *
   * @param trnName the {@code TRNNAME} value (maximum 4 characters)
   */
  public void setTrnName(String trnName) {
    this.trnName = trnName;
  }

  /**
   * Returns the first header title line (application title).
   *
   * @return the {@code TITLE01} value (maximum 40 characters)
   */
  public String getTitle01() {
    return title01;
  }

  /**
   * Sets the first header title line (application title).
   *
   * @param title01 the {@code TITLE01} value (maximum 40 characters)
   */
  public void setTitle01(String title01) {
    this.title01 = title01;
  }

  /**
   * Returns the current date header field (mm/dd/yy).
   *
   * @return the {@code CURDATE} value (maximum 8 characters)
   */
  public String getCurDate() {
    return curDate;
  }

  /**
   * Sets the current date header field (mm/dd/yy).
   *
   * @param curDate the {@code CURDATE} value (maximum 8 characters)
   */
  public void setCurDate(String curDate) {
    this.curDate = curDate;
  }

  /**
   * Returns the current program name echoed in the header.
   *
   * @return the {@code PGMNAME} value (maximum 8 characters)
   */
  public String getPgmName() {
    return pgmName;
  }

  /**
   * Sets the current program name echoed in the header.
   *
   * @param pgmName the {@code PGMNAME} value (maximum 8 characters)
   */
  public void setPgmName(String pgmName) {
    this.pgmName = pgmName;
  }

  /**
   * Returns the second header title line (screen title).
   *
   * @return the {@code TITLE02} value (maximum 40 characters)
   */
  public String getTitle02() {
    return title02;
  }

  /**
   * Sets the second header title line (screen title).
   *
   * @param title02 the {@code TITLE02} value (maximum 40 characters)
   */
  public void setTitle02(String title02) {
    this.title02 = title02;
  }

  /**
   * Returns the current time header field (hh:mm:ss).
   *
   * @return the {@code CURTIME} value (maximum 8 characters)
   */
  public String getCurTime() {
    return curTime;
  }

  /**
   * Sets the current time header field (hh:mm:ss).
   *
   * @param curTime the {@code CURTIME} value (maximum 8 characters)
   */
  public void setCurTime(String curTime) {
    this.curTime = curTime;
  }

  /**
   * Returns the account number entered or selected for the card lookup.
   *
   * @return the {@code ACCTSID} value (maximum 11 characters)
   */
  public String getAcctSid() {
    return acctSid;
  }

  /**
   * Sets the account number entered or selected for the card lookup.
   *
   * @param acctSid the {@code ACCTSID} value (maximum 11 characters)
   */
  public void setAcctSid(String acctSid) {
    this.acctSid = acctSid;
  }

  /**
   * Returns the card number entered or selected for the card lookup.
   *
   * @return the {@code CARDSID} value (maximum 16 characters)
   */
  public String getCardSid() {
    return cardSid;
  }

  /**
   * Sets the card number entered or selected for the card lookup.
   *
   * @param cardSid the {@code CARDSID} value (maximum 16 characters)
   */
  public void setCardSid(String cardSid) {
    this.cardSid = cardSid;
  }

  /**
   * Returns the embossed name on the card.
   *
   * @return the {@code CRDNAME} value (maximum 50 characters)
   */
  public String getCrdName() {
    return crdName;
  }

  /**
   * Sets the embossed name on the card.
   *
   * @param crdName the {@code CRDNAME} value (maximum 50 characters)
   */
  public void setCrdName(String crdName) {
    this.crdName = crdName;
  }

  /**
   * Returns the card active status code (Y = active, N = inactive).
   *
   * @return the {@code CRDSTCD} value (maximum 1 character)
   */
  public String getCrdStcd() {
    return crdStcd;
  }

  /**
   * Sets the card active status code (Y = active, N = inactive).
   *
   * @param crdStcd the {@code CRDSTCD} value (maximum 1 character)
   */
  public void setCrdStcd(String crdStcd) {
    this.crdStcd = crdStcd;
  }

  /**
   * Returns the card expiry month (mm).
   *
   * @return the {@code EXPMON} value (maximum 2 characters)
   */
  public String getExpMon() {
    return expMon;
  }

  /**
   * Sets the card expiry month (mm).
   *
   * @param expMon the {@code EXPMON} value (maximum 2 characters)
   */
  public void setExpMon(String expMon) {
    this.expMon = expMon;
  }

  /**
   * Returns the card expiry year (yyyy).
   *
   * @return the {@code EXPYEAR} value (maximum 4 characters)
   */
  public String getExpYear() {
    return expYear;
  }

  /**
   * Sets the card expiry year (yyyy).
   *
   * @param expYear the {@code EXPYEAR} value (maximum 4 characters)
   */
  public void setExpYear(String expYear) {
    this.expYear = expYear;
  }

  /**
   * Returns the informational message line.
   *
   * @return the {@code INFOMSG} value (maximum 40 characters)
   */
  public String getInfoMsg() {
    return infoMsg;
  }

  /**
   * Sets the informational message line.
   *
   * @param infoMsg the {@code INFOMSG} value (maximum 40 characters)
   */
  public void setInfoMsg(String infoMsg) {
    this.infoMsg = infoMsg;
  }

  /**
   * Returns the error message line.
   *
   * @return the {@code ERRMSG} value (maximum 80 characters)
   */
  public String getErrMsg() {
    return errMsg;
  }

  /**
   * Sets the error message line.
   *
   * @param errMsg the {@code ERRMSG} value (maximum 80 characters)
   */
  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }

  /**
   * Returns the function-key legend text.
   *
   * @return the {@code FKEYS} value (maximum 75 characters)
   */
  public String getFkeys() {
    return fkeys;
  }

  /**
   * Sets the function-key legend text.
   *
   * @param fkeys the {@code FKEYS} value (maximum 75 characters)
   */
  public void setFkeys(String fkeys) {
    this.fkeys = fkeys;
  }
}
