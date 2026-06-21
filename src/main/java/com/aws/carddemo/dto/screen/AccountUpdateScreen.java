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
 * Screen view contract for the Account Update screen.
 *
 * <p>Migrated from the legacy CICS/BMS mapset {@code COACTUP} (CICS transaction {@code CAUP}), this
 * plain framework-light POJO carries the field values exchanged with the 3270 Account Update map.
 * It is the editable counterpart of the Account View screen: each date is split into year / month /
 * day components, the SSN into three parts, and each telephone number into area / prefix / line
 * components, mirroring the separate underlined ({@code HILIGHT=UNDERLINE}) input fields exposed by
 * the BMS map so that field-level validation and rendering parity are preserved.
 *
 * <p>Every {@code PIC X(n)} display field is modelled as a {@link String} bounded to its exact
 * fixed-width length via {@link Size}. The five monetary fields (credit limit, cash credit limit,
 * current balance, current cycle credit and current cycle debit) are the legacy {@code ACRDLIM} /
 * {@code ACSHLIM} / {@code ACURBAL} / {@code ACRCYCR} / {@code ACRCYDB} screen inputs &mdash; each
 * a {@code PIC X(15)} character field on the 3270 map &mdash; and are therefore modelled as {@link
 * String} (bounded to 15) exactly like every other input. This faithfully reproduces the COBOL
 * behaviour in which the raw characters the operator typed are received first and only validated /
 * parsed in {@code 1250-EDIT-SIGNED-9V2} ({@code FUNCTION TEST-NUMVAL-C} then {@code NUMVAL-C}); a
 * malformed amount yields the legacy "{@code <field> is not valid}" message rather than a binding
 * failure. Decimal fidelity is preserved downstream: the service parses each validated amount into
 * a {@link java.math.BigDecimal} truncated to scale 2 ({@code RoundingMode.DOWN}) before it touches
 * the entity (AAP &sect;0.6.1); floating-point types are never used for monetary data.
 *
 * <p>For optimistic-locking parity ({@code 9700-CHECK-CHANGE-IN-REC}) the screen also carries the
 * {@code old*} snapshot fields &mdash; the account and customer values that were fetched and shown
 * on the display turn ({@code 9500-STORE-FETCHED-DATA} &rarr; {@code ACUP-OLD-DETAILS}). They are
 * rendered as hidden inputs so they round-trip across the pseudo-conversational boundary exactly as
 * the COBOL {@code WS-THIS-PROGCOMMAREA} carried {@code ACUP-OLD-DETAILS}; the service compares the
 * freshly re-read record against this carried snapshot to detect a concurrent change.
 *
 * <p>This class intentionally contains no formatting, parsing, re-assembly or optimistic-locking
 * logic &mdash; those concerns live in the service layer (for example {@code
 * service.online.AccountUpdateService}) and in {@code util.NumberFormatter}.
 *
 * <p>Authoritative source: {@code legacy/app/cpy-bms/COACTUP.CPY} (field names and lengths) with
 * {@code legacy/app/bms/COACTUP.bms} as the rendering reference. See Agent Action Plan &sect;0.4.1
 * (screen DTOs preserve field lengths), &sect;0.3.4 (UI parity) and &sect;0.6.1 (monetary &rarr;
 * {@code BigDecimal}).
 */
public class AccountUpdateScreen {

  // Screen header (program/transaction banner, date and time).
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

  // Account identifier and status.
  @Size(max = 11)
  private String acctSid; // ACCTSID PIC X(11)

  @Size(max = 1)
  private String acstTus; // ACSTTUS PIC X(1)

  // Account open date, split into year / month / day input components.
  @Size(max = 4)
  private String opnYear; // OPNYEAR PIC X(4)

  @Size(max = 2)
  private String opnMon; // OPNMON PIC X(2)

  @Size(max = 2)
  private String opnDay; // OPNDAY PIC X(2)

  // Credit limit (monetary). ACRDLIM is a PIC X(15) screen input: the raw typed characters are
  // received here and validated/parsed in the service (1250-EDIT-SIGNED-9V2 -> NUMVAL-C).
  @Size(max = 15)
  private String acrdLim; // ACRDLIM PIC X(15)

  // Card expiry date, split into year / month / day input components.
  @Size(max = 4)
  private String expYear; // EXPYEAR PIC X(4)

  @Size(max = 2)
  private String expMon; // EXPMON PIC X(2)

  @Size(max = 2)
  private String expDay; // EXPDAY PIC X(2)

  // Cash credit limit (monetary). ACSHLIM is a PIC X(15) screen input; validated/parsed in the
  // service (1250-EDIT-SIGNED-9V2 -> NUMVAL-C).
  @Size(max = 15)
  private String acshLim; // ACSHLIM PIC X(15)

  // Card reissue date, split into year / month / day input components.
  @Size(max = 4)
  private String risYear; // RISYEAR PIC X(4)

  @Size(max = 2)
  private String risMon; // RISMON PIC X(2)

  @Size(max = 2)
  private String risDay; // RISDAY PIC X(2)

  // Current balance (monetary). ACURBAL is a PIC X(15) screen input; validated/parsed in the
  // service (1250-EDIT-SIGNED-9V2 -> NUMVAL-C).
  @Size(max = 15)
  private String acurBal; // ACURBAL PIC X(15)

  // Current cycle credit (monetary). ACRCYCR is a PIC X(15) screen input; validated/parsed in the
  // service (1250-EDIT-SIGNED-9V2 -> NUMVAL-C).
  @Size(max = 15)
  private String acrCycr; // ACRCYCR PIC X(15)

  // Account group identifier.
  @Size(max = 10)
  private String aaddGrp; // AADDGRP PIC X(10)

  // Current cycle debit (monetary). ACRCYDB is a PIC X(15) screen input; validated/parsed in the
  // service (1250-EDIT-SIGNED-9V2 -> NUMVAL-C).
  @Size(max = 15)
  private String acrCydb; // ACRCYDB PIC X(15)

  // Customer number.
  @Size(max = 9)
  private String acstNum; // ACSTNUM PIC X(9)

  // Customer SSN, split into the three underlined input components.
  @Size(max = 3)
  private String actSsn1; // ACTSSN1 PIC X(3)

  @Size(max = 2)
  private String actSsn2; // ACTSSN2 PIC X(2)

  @Size(max = 4)
  private String actSsn3; // ACTSSN3 PIC X(4)

  // Customer date of birth, split into year / month / day input components.
  @Size(max = 4)
  private String dobYear; // DOBYEAR PIC X(4)

  @Size(max = 2)
  private String dobMon; // DOBMON PIC X(2)

  @Size(max = 2)
  private String dobDay; // DOBDAY PIC X(2)

  // Customer FICO score, name and address fields.
  @Size(max = 3)
  private String acstFco; // ACSTFCO PIC X(3)

  @Size(max = 25)
  private String acsFnam; // ACSFNAM PIC X(25)

  @Size(max = 25)
  private String acsMnam; // ACSMNAM PIC X(25)

  @Size(max = 25)
  private String acsLnam; // ACSLNAM PIC X(25)

  @Size(max = 50)
  private String acsAdl1; // ACSADL1 PIC X(50)

  @Size(max = 2)
  private String acsStte; // ACSSTTE PIC X(2)

  @Size(max = 50)
  private String acsAdl2; // ACSADL2 PIC X(50)

  @Size(max = 5)
  private String acsZipc; // ACSZIPC PIC X(5)

  @Size(max = 50)
  private String acsCity; // ACSCITY PIC X(50)

  @Size(max = 3)
  private String acsCtry; // ACSCTRY PIC X(3)

  // Customer primary phone, split into area / prefix / line input components.
  @Size(max = 3)
  private String acsPh1a; // ACSPH1A PIC X(3)

  @Size(max = 3)
  private String acsPh1b; // ACSPH1B PIC X(3)

  @Size(max = 4)
  private String acsPh1c; // ACSPH1C PIC X(4)

  // Customer government-issued identifier.
  @Size(max = 20)
  private String acsGovt; // ACSGOVT PIC X(20)

  // Customer secondary phone, split into area / prefix / line input components.
  @Size(max = 3)
  private String acsPh2a; // ACSPH2A PIC X(3)

  @Size(max = 3)
  private String acsPh2b; // ACSPH2B PIC X(3)

  @Size(max = 4)
  private String acsPh2c; // ACSPH2C PIC X(4)

  // Customer EFT account code and primary-card holder flag.
  @Size(max = 10)
  private String acsEftc; // ACSEFTC PIC X(10)

  @Size(max = 1)
  private String acsPflg; // ACSPFLG PIC X(1)

  // Informational / error message lines and PF-key legend buffers.
  @Size(max = 45)
  private String infoMsg; // INFOMSG PIC X(45)

  @Size(max = 78)
  private String errMsg; // ERRMSG PIC X(78)

  @Size(max = 21)
  private String fkeys; // FKEYS PIC X(21)

  @Size(max = 7)
  private String fkey05; // FKEY05 PIC X(7)

  @Size(max = 10)
  private String fkey12; // FKEY12 PIC X(10)

  // ===========================================================================================
  // OLD snapshot — the account/customer values fetched and shown on the DISPLAY turn
  // (9500-STORE-FETCHED-DATA -> ACUP-OLD-DETAILS). These are rendered as hidden form inputs so
  // they round-trip across the pseudo-conversational boundary, exactly as the COBOL carried
  // ACUP-OLD-DETAILS in WS-THIS-PROGCOMMAREA. The service reconstructs the OLD entities from
  // these fields and compares them, field by field, against the freshly re-read records on the
  // PF5 write turn (9700-CHECK-CHANGE-IN-REC). They are NOT re-captured on the validate/write
  // turns, so a concurrent change made after the display turn is detected.
  //
  // Numeric values are carried as their canonical text form (money: plain decimal at scale 2;
  // SSN / FICO: the integer digits; dates: yyyy-MM-dd) and re-parsed by the service. Lengths are
  // generous text bounds (not 3270 map widths — these are hidden state, never displayed).

  // OLD account snapshot.
  @Size(max = 11)
  private String oldAcctId;

  @Size(max = 1)
  private String oldActiveStatus;

  @Size(max = 15)
  private String oldCurrBal;

  @Size(max = 15)
  private String oldCreditLimit;

  @Size(max = 15)
  private String oldCashCreditLimit;

  @Size(max = 15)
  private String oldCurrCycCredit;

  @Size(max = 15)
  private String oldCurrCycDebit;

  @Size(max = 10)
  private String oldOpenDate; // yyyy-MM-dd

  @Size(max = 10)
  private String oldExpiryDate; // yyyy-MM-dd

  @Size(max = 10)
  private String oldReissueDate; // yyyy-MM-dd

  @Size(max = 10)
  private String oldGroupId;

  // OLD customer snapshot.
  @Size(max = 9)
  private String oldCustId;

  @Size(max = 25)
  private String oldFirstName;

  @Size(max = 25)
  private String oldMiddleName;

  @Size(max = 25)
  private String oldLastName;

  @Size(max = 50)
  private String oldAddrLine1;

  @Size(max = 50)
  private String oldAddrLine2;

  @Size(max = 50)
  private String oldAddrLine3;

  @Size(max = 2)
  private String oldStateCd;

  @Size(max = 3)
  private String oldCountryCd;

  @Size(max = 10)
  private String oldZip;

  @Size(max = 15)
  private String oldPhone1; // (aaa)bbb-cccc

  @Size(max = 15)
  private String oldPhone2; // (aaa)bbb-cccc

  @Size(max = 9)
  private String oldSsn; // 9-digit numeric text

  @Size(max = 20)
  private String oldGovtId;

  @Size(max = 10)
  private String oldDob; // yyyy-MM-dd

  @Size(max = 10)
  private String oldEftId;

  @Size(max = 1)
  private String oldPriHolder;

  @Size(max = 3)
  private String oldFico; // numeric text

  /** Creates an empty screen view contract; fields are populated through the setters. */
  public AccountUpdateScreen() {
    // No initialisation required: this is a framework-light data holder.
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

  public String getAcctSid() {
    return acctSid;
  }

  public void setAcctSid(String acctSid) {
    this.acctSid = acctSid;
  }

  public String getAcstTus() {
    return acstTus;
  }

  public void setAcstTus(String acstTus) {
    this.acstTus = acstTus;
  }

  public String getOpnYear() {
    return opnYear;
  }

  public void setOpnYear(String opnYear) {
    this.opnYear = opnYear;
  }

  public String getOpnMon() {
    return opnMon;
  }

  public void setOpnMon(String opnMon) {
    this.opnMon = opnMon;
  }

  public String getOpnDay() {
    return opnDay;
  }

  public void setOpnDay(String opnDay) {
    this.opnDay = opnDay;
  }

  public String getAcrdLim() {
    return acrdLim;
  }

  public void setAcrdLim(String acrdLim) {
    this.acrdLim = acrdLim;
  }

  public String getExpYear() {
    return expYear;
  }

  public void setExpYear(String expYear) {
    this.expYear = expYear;
  }

  public String getExpMon() {
    return expMon;
  }

  public void setExpMon(String expMon) {
    this.expMon = expMon;
  }

  public String getExpDay() {
    return expDay;
  }

  public void setExpDay(String expDay) {
    this.expDay = expDay;
  }

  public String getAcshLim() {
    return acshLim;
  }

  public void setAcshLim(String acshLim) {
    this.acshLim = acshLim;
  }

  public String getRisYear() {
    return risYear;
  }

  public void setRisYear(String risYear) {
    this.risYear = risYear;
  }

  public String getRisMon() {
    return risMon;
  }

  public void setRisMon(String risMon) {
    this.risMon = risMon;
  }

  public String getRisDay() {
    return risDay;
  }

  public void setRisDay(String risDay) {
    this.risDay = risDay;
  }

  public String getAcurBal() {
    return acurBal;
  }

  public void setAcurBal(String acurBal) {
    this.acurBal = acurBal;
  }

  public String getAcrCycr() {
    return acrCycr;
  }

  public void setAcrCycr(String acrCycr) {
    this.acrCycr = acrCycr;
  }

  public String getAaddGrp() {
    return aaddGrp;
  }

  public void setAaddGrp(String aaddGrp) {
    this.aaddGrp = aaddGrp;
  }

  public String getAcrCydb() {
    return acrCydb;
  }

  public void setAcrCydb(String acrCydb) {
    this.acrCydb = acrCydb;
  }

  public String getAcstNum() {
    return acstNum;
  }

  public void setAcstNum(String acstNum) {
    this.acstNum = acstNum;
  }

  public String getActSsn1() {
    return actSsn1;
  }

  public void setActSsn1(String actSsn1) {
    this.actSsn1 = actSsn1;
  }

  public String getActSsn2() {
    return actSsn2;
  }

  public void setActSsn2(String actSsn2) {
    this.actSsn2 = actSsn2;
  }

  public String getActSsn3() {
    return actSsn3;
  }

  public void setActSsn3(String actSsn3) {
    this.actSsn3 = actSsn3;
  }

  public String getDobYear() {
    return dobYear;
  }

  public void setDobYear(String dobYear) {
    this.dobYear = dobYear;
  }

  public String getDobMon() {
    return dobMon;
  }

  public void setDobMon(String dobMon) {
    this.dobMon = dobMon;
  }

  public String getDobDay() {
    return dobDay;
  }

  public void setDobDay(String dobDay) {
    this.dobDay = dobDay;
  }

  public String getAcstFco() {
    return acstFco;
  }

  public void setAcstFco(String acstFco) {
    this.acstFco = acstFco;
  }

  public String getAcsFnam() {
    return acsFnam;
  }

  public void setAcsFnam(String acsFnam) {
    this.acsFnam = acsFnam;
  }

  public String getAcsMnam() {
    return acsMnam;
  }

  public void setAcsMnam(String acsMnam) {
    this.acsMnam = acsMnam;
  }

  public String getAcsLnam() {
    return acsLnam;
  }

  public void setAcsLnam(String acsLnam) {
    this.acsLnam = acsLnam;
  }

  public String getAcsAdl1() {
    return acsAdl1;
  }

  public void setAcsAdl1(String acsAdl1) {
    this.acsAdl1 = acsAdl1;
  }

  public String getAcsStte() {
    return acsStte;
  }

  public void setAcsStte(String acsStte) {
    this.acsStte = acsStte;
  }

  public String getAcsAdl2() {
    return acsAdl2;
  }

  public void setAcsAdl2(String acsAdl2) {
    this.acsAdl2 = acsAdl2;
  }

  public String getAcsZipc() {
    return acsZipc;
  }

  public void setAcsZipc(String acsZipc) {
    this.acsZipc = acsZipc;
  }

  public String getAcsCity() {
    return acsCity;
  }

  public void setAcsCity(String acsCity) {
    this.acsCity = acsCity;
  }

  public String getAcsCtry() {
    return acsCtry;
  }

  public void setAcsCtry(String acsCtry) {
    this.acsCtry = acsCtry;
  }

  public String getAcsPh1a() {
    return acsPh1a;
  }

  public void setAcsPh1a(String acsPh1a) {
    this.acsPh1a = acsPh1a;
  }

  public String getAcsPh1b() {
    return acsPh1b;
  }

  public void setAcsPh1b(String acsPh1b) {
    this.acsPh1b = acsPh1b;
  }

  public String getAcsPh1c() {
    return acsPh1c;
  }

  public void setAcsPh1c(String acsPh1c) {
    this.acsPh1c = acsPh1c;
  }

  public String getAcsGovt() {
    return acsGovt;
  }

  public void setAcsGovt(String acsGovt) {
    this.acsGovt = acsGovt;
  }

  public String getAcsPh2a() {
    return acsPh2a;
  }

  public void setAcsPh2a(String acsPh2a) {
    this.acsPh2a = acsPh2a;
  }

  public String getAcsPh2b() {
    return acsPh2b;
  }

  public void setAcsPh2b(String acsPh2b) {
    this.acsPh2b = acsPh2b;
  }

  public String getAcsPh2c() {
    return acsPh2c;
  }

  public void setAcsPh2c(String acsPh2c) {
    this.acsPh2c = acsPh2c;
  }

  public String getAcsEftc() {
    return acsEftc;
  }

  public void setAcsEftc(String acsEftc) {
    this.acsEftc = acsEftc;
  }

  public String getAcsPflg() {
    return acsPflg;
  }

  public void setAcsPflg(String acsPflg) {
    this.acsPflg = acsPflg;
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

  public String getFkey05() {
    return fkey05;
  }

  public void setFkey05(String fkey05) {
    this.fkey05 = fkey05;
  }

  public String getFkey12() {
    return fkey12;
  }

  public void setFkey12(String fkey12) {
    this.fkey12 = fkey12;
  }

  // ===========================================================================================
  // OLD snapshot accessors (hidden carried state — ACUP-OLD-DETAILS).
  // ===========================================================================================

  public String getOldAcctId() {
    return oldAcctId;
  }

  public void setOldAcctId(String oldAcctId) {
    this.oldAcctId = oldAcctId;
  }

  public String getOldActiveStatus() {
    return oldActiveStatus;
  }

  public void setOldActiveStatus(String oldActiveStatus) {
    this.oldActiveStatus = oldActiveStatus;
  }

  public String getOldCurrBal() {
    return oldCurrBal;
  }

  public void setOldCurrBal(String oldCurrBal) {
    this.oldCurrBal = oldCurrBal;
  }

  public String getOldCreditLimit() {
    return oldCreditLimit;
  }

  public void setOldCreditLimit(String oldCreditLimit) {
    this.oldCreditLimit = oldCreditLimit;
  }

  public String getOldCashCreditLimit() {
    return oldCashCreditLimit;
  }

  public void setOldCashCreditLimit(String oldCashCreditLimit) {
    this.oldCashCreditLimit = oldCashCreditLimit;
  }

  public String getOldCurrCycCredit() {
    return oldCurrCycCredit;
  }

  public void setOldCurrCycCredit(String oldCurrCycCredit) {
    this.oldCurrCycCredit = oldCurrCycCredit;
  }

  public String getOldCurrCycDebit() {
    return oldCurrCycDebit;
  }

  public void setOldCurrCycDebit(String oldCurrCycDebit) {
    this.oldCurrCycDebit = oldCurrCycDebit;
  }

  public String getOldOpenDate() {
    return oldOpenDate;
  }

  public void setOldOpenDate(String oldOpenDate) {
    this.oldOpenDate = oldOpenDate;
  }

  public String getOldExpiryDate() {
    return oldExpiryDate;
  }

  public void setOldExpiryDate(String oldExpiryDate) {
    this.oldExpiryDate = oldExpiryDate;
  }

  public String getOldReissueDate() {
    return oldReissueDate;
  }

  public void setOldReissueDate(String oldReissueDate) {
    this.oldReissueDate = oldReissueDate;
  }

  public String getOldGroupId() {
    return oldGroupId;
  }

  public void setOldGroupId(String oldGroupId) {
    this.oldGroupId = oldGroupId;
  }

  public String getOldCustId() {
    return oldCustId;
  }

  public void setOldCustId(String oldCustId) {
    this.oldCustId = oldCustId;
  }

  public String getOldFirstName() {
    return oldFirstName;
  }

  public void setOldFirstName(String oldFirstName) {
    this.oldFirstName = oldFirstName;
  }

  public String getOldMiddleName() {
    return oldMiddleName;
  }

  public void setOldMiddleName(String oldMiddleName) {
    this.oldMiddleName = oldMiddleName;
  }

  public String getOldLastName() {
    return oldLastName;
  }

  public void setOldLastName(String oldLastName) {
    this.oldLastName = oldLastName;
  }

  public String getOldAddrLine1() {
    return oldAddrLine1;
  }

  public void setOldAddrLine1(String oldAddrLine1) {
    this.oldAddrLine1 = oldAddrLine1;
  }

  public String getOldAddrLine2() {
    return oldAddrLine2;
  }

  public void setOldAddrLine2(String oldAddrLine2) {
    this.oldAddrLine2 = oldAddrLine2;
  }

  public String getOldAddrLine3() {
    return oldAddrLine3;
  }

  public void setOldAddrLine3(String oldAddrLine3) {
    this.oldAddrLine3 = oldAddrLine3;
  }

  public String getOldStateCd() {
    return oldStateCd;
  }

  public void setOldStateCd(String oldStateCd) {
    this.oldStateCd = oldStateCd;
  }

  public String getOldCountryCd() {
    return oldCountryCd;
  }

  public void setOldCountryCd(String oldCountryCd) {
    this.oldCountryCd = oldCountryCd;
  }

  public String getOldZip() {
    return oldZip;
  }

  public void setOldZip(String oldZip) {
    this.oldZip = oldZip;
  }

  public String getOldPhone1() {
    return oldPhone1;
  }

  public void setOldPhone1(String oldPhone1) {
    this.oldPhone1 = oldPhone1;
  }

  public String getOldPhone2() {
    return oldPhone2;
  }

  public void setOldPhone2(String oldPhone2) {
    this.oldPhone2 = oldPhone2;
  }

  public String getOldSsn() {
    return oldSsn;
  }

  public void setOldSsn(String oldSsn) {
    this.oldSsn = oldSsn;
  }

  public String getOldGovtId() {
    return oldGovtId;
  }

  public void setOldGovtId(String oldGovtId) {
    this.oldGovtId = oldGovtId;
  }

  public String getOldDob() {
    return oldDob;
  }

  public void setOldDob(String oldDob) {
    this.oldDob = oldDob;
  }

  public String getOldEftId() {
    return oldEftId;
  }

  public void setOldEftId(String oldEftId) {
    this.oldEftId = oldEftId;
  }

  public String getOldPriHolder() {
    return oldPriHolder;
  }

  public void setOldPriHolder(String oldPriHolder) {
    this.oldPriHolder = oldPriHolder;
  }

  public String getOldFico() {
    return oldFico;
  }

  public void setOldFico(String oldFico) {
    this.oldFico = oldFico;
  }
}
