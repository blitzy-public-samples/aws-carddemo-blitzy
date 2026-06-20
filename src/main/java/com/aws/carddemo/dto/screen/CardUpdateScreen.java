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
 * Screen view contract for the AWS CardDemo <strong>Card Update</strong> screen.
 *
 * <p>This DTO is the modernized, framework-light equivalent of the legacy CICS/BMS map {@code
 * COCRDUP} (mapset {@code COCRDUP}, map {@code CCRDUPA}), driven by CICS transaction {@code CCUP}.
 * It is the editable twin of the read-only Card View screen ({@code COCRDSL}): in addition to the
 * fields shared with the view screen it exposes an expiry-<em>day</em> field ({@code EXPDAY}) and a
 * second program-function-key legend line ({@code FKEYSC}).
 *
 * <p>Source authority and mapping rules follow the Agent Action Plan sections 0.4.1 ("dto/screen/*
 * &lt;- app/cpy-bms/*.CPY + app/bms/*.bms, preserving field lengths") and 0.3.4. The authoritative
 * field names and lengths are taken from the BMS symbolic copybook {@code legacy/app/cpy-bms/
 * COCRDUP.CPY}; rendering geometry (screen positions, attributes, colours) is taken from the BMS
 * mapset {@code legacy/app/bms/COCRDUP.bms}.
 *
 * <p>The copybook declares two {@code 01}-levels: the input map {@code CCRDUPAI} and the output map
 * {@code CCRDUPAO REDEFINES CCRDUPAI}. Only the meaningful {@code <NAME>I} data-value fields are
 * modeled here (one Java property per logical screen field); all BMS plumbing fields (length {@code
 * L}, flag {@code F}/{@code A}, colour {@code C}, highlight {@code H}, etc.) and {@code FILLER}
 * items are intentionally omitted. Each {@code PIC X(n)} alphanumeric field becomes a {@link
 * String} constrained to its original fixed width via {@link Size}, preserving the 3270 field
 * geometry so downstream rendering and validation remain byte-faithful to the mainframe contract.
 *
 * <p>This type is a pure data-transfer object: it performs no business logic. Field-content
 * validation (e.g. numeric month/year/day ranges, card-status domain) and the optimistic update of
 * the underlying card record are the responsibility of {@code
 * com.aws.carddemo.service.online.CardUpdateService}, mirroring the COBOL control flow of {@code
 * COCRDUPC}. To preserve the layered architecture and the no-monetary-field nature of this screen,
 * no JPA, domain-entity, or floating-point types are referenced.
 *
 * <p>The 17 logical fields, in copybook declaration order, are: {@code TRNNAME}, {@code TITLE01},
 * {@code CURDATE}, {@code PGMNAME}, {@code TITLE02}, {@code CURTIME}, {@code ACCTSID}, {@code
 * CARDSID}, {@code CRDNAME}, {@code CRDSTCD}, {@code EXPMON}, {@code EXPYEAR}, {@code EXPDAY},
 * {@code INFOMSG}, {@code ERRMSG}, {@code FKEYS}, and {@code FKEYSC}.
 */
public class CardUpdateScreen {

  // ===== Header / screen-chrome fields (display-only) =====

  /** Transaction identifier shown in the screen header. */
  @Size(max = 4)
  private String trnName; // TRNNAMEI PIC X(4) - transaction id (BMS field TRNNAME)

  /** Application title line 1. */
  @Size(max = 40)
  private String title01; // TITLE01I PIC X(40) - title line 1 (BMS field TITLE01)

  /** Current date, formatted mm/dd/yy. */
  @Size(max = 8)
  private String curDate; // CURDATEI PIC X(8) - current date (BMS field CURDATE)

  /** Owning program name shown in the screen header. */
  @Size(max = 8)
  private String pgmName; // PGMNAMEI PIC X(8) - program name (BMS field PGMNAME)

  /** Screen title line 2. */
  @Size(max = 40)
  private String title02; // TITLE02I PIC X(40) - title line 2 (BMS field TITLE02)

  /** Current time, formatted hh:mm:ss. */
  @Size(max = 8)
  private String curTime; // CURTIMEI PIC X(8) - current time (BMS field CURTIME)

  // ===== Card identification fields =====

  /** Account number (account id) the card belongs to. */
  @Size(max = 11)
  private String acctSid; // ACCTSIDI PIC X(11) - account id (BMS field ACCTSID)

  /** Card number. */
  @Size(max = 16)
  private String cardSid; // CARDSIDI PIC X(16) - card number (BMS field CARDSID)

  // ===== Editable card-detail fields =====

  /** Embossed name on the card (editable). */
  @Size(max = 50)
  private String crdName; // CRDNAMEI PIC X(50) - name on card (BMS field CRDNAME)

  /** Card active status code, Y/N (editable). */
  @Size(max = 1)
  private String crdStcd; // CRDSTCDI PIC X(1) - card active status (BMS field CRDSTCD)

  /** Card expiry month, two digits (editable). */
  @Size(max = 2)
  private String expMon; // EXPMONI PIC X(2) - expiry month (BMS field EXPMON)

  /** Card expiry year, four digits (editable). */
  @Size(max = 4)
  private String expYear; // EXPYEARI PIC X(4) - expiry year (BMS field EXPYEAR)

  /** Card expiry day, two digits (editable; present on update, absent on the view screen). */
  @Size(max = 2)
  private String expDay; // EXPDAYI PIC X(2) - expiry day (BMS field EXPDAY)

  // ===== Message and program-function-key legend fields =====

  /** Informational (non-error) message line. */
  @Size(max = 40)
  private String infoMsg; // INFOMSGI PIC X(40) - informational message (BMS field INFOMSG)

  /** Error message line. */
  @Size(max = 80)
  private String errMsg; // ERRMSGI PIC X(80) - error message (BMS field ERRMSG)

  /** Program-function-key legend, line 1 (e.g. "ENTER=Process F3=Exit"). */
  @Size(max = 21)
  private String fkeys; // FKEYSI PIC X(21) - PF-key legend line 1 (BMS field FKEYS)

  /** Program-function-key legend, line 2 / continuation (e.g. "F5=Save F12=Cancel"). */
  @Size(max = 18)
  private String fkeysc; // FKEYSCI PIC X(18) - PF-key legend line 2 (BMS field FKEYSC)

  /**
   * Creates an empty card-update screen contract for framework instantiation or manual population.
   */
  public CardUpdateScreen() {
    // Intentionally empty: a screen DTO is populated field-by-field via its setters.
  }

  // ===== Accessors =====

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

  public String getAcctSid() {
    return acctSid;
  }

  public void setAcctSid(String acctSid) {
    this.acctSid = acctSid;
  }

  public String getCardSid() {
    return cardSid;
  }

  public void setCardSid(String cardSid) {
    this.cardSid = cardSid;
  }

  public String getCrdName() {
    return crdName;
  }

  public void setCrdName(String crdName) {
    this.crdName = crdName;
  }

  public String getCrdStcd() {
    return crdStcd;
  }

  public void setCrdStcd(String crdStcd) {
    this.crdStcd = crdStcd;
  }

  public String getExpMon() {
    return expMon;
  }

  public void setExpMon(String expMon) {
    this.expMon = expMon;
  }

  public String getExpYear() {
    return expYear;
  }

  public void setExpYear(String expYear) {
    this.expYear = expYear;
  }

  public String getExpDay() {
    return expDay;
  }

  public void setExpDay(String expDay) {
    this.expDay = expDay;
  }

  public String getInfoMsg() {
    return infoMsg;
  }

  public void setInfoMsg(String infoMsg) {
    this.infoMsg = infoMsg;
  }

  public String getErrMsg() {
    return errMsg;
  }

  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }

  public String getFkeys() {
    return fkeys;
  }

  public void setFkeys(String fkeys) {
    this.fkeys = fkeys;
  }

  public String getFkeysc() {
    return fkeysc;
  }

  public void setFkeysc(String fkeysc) {
    this.fkeysc = fkeysc;
  }
}
