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
import java.math.BigDecimal;

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
 * current balance, current cycle credit and current cycle debit) are modelled as {@link
 * java.math.BigDecimal} with a conceptual scale of 2 to guarantee decimal fidelity; floating point
 * types are never used for monetary data. This class intentionally contains no formatting, parsing,
 * re-assembly or optimistic-locking logic &mdash; those concerns live in the service layer (for
 * example {@code service.online.AccountUpdateService}) and in {@code util.NumberFormatter}.
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

  // Credit limit (monetary).
  // ACRDLIM credit limit (screen PIC X(15)); monetary value -> BigDecimal (scale 2)
  private BigDecimal acrdLim;

  // Card expiry date, split into year / month / day input components.
  @Size(max = 4)
  private String expYear; // EXPYEAR PIC X(4)

  @Size(max = 2)
  private String expMon; // EXPMON PIC X(2)

  @Size(max = 2)
  private String expDay; // EXPDAY PIC X(2)

  // Cash credit limit (monetary).
  // ACSHLIM cash credit limit (screen PIC X(15)); monetary value -> BigDecimal (scale 2)
  private BigDecimal acshLim;

  // Card reissue date, split into year / month / day input components.
  @Size(max = 4)
  private String risYear; // RISYEAR PIC X(4)

  @Size(max = 2)
  private String risMon; // RISMON PIC X(2)

  @Size(max = 2)
  private String risDay; // RISDAY PIC X(2)

  // Current balance (monetary).
  // ACURBAL current balance (screen PIC X(15)); monetary value -> BigDecimal (scale 2)
  private BigDecimal acurBal;

  // Current cycle credit (monetary).
  // ACRCYCR current cycle credit (screen PIC X(15)); monetary value -> BigDecimal (scale 2)
  private BigDecimal acrCycr;

  // Account group identifier.
  @Size(max = 10)
  private String aaddGrp; // AADDGRP PIC X(10)

  // Current cycle debit (monetary).
  // ACRCYDB current cycle debit (screen PIC X(15)); monetary value -> BigDecimal (scale 2)
  private BigDecimal acrCydb;

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

  public BigDecimal getAcrdLim() {
    return acrdLim;
  }

  public void setAcrdLim(BigDecimal acrdLim) {
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

  public BigDecimal getAcshLim() {
    return acshLim;
  }

  public void setAcshLim(BigDecimal acshLim) {
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

  public BigDecimal getAcurBal() {
    return acurBal;
  }

  public void setAcurBal(BigDecimal acurBal) {
    this.acurBal = acurBal;
  }

  public BigDecimal getAcrCycr() {
    return acrCycr;
  }

  public void setAcrCycr(BigDecimal acrCycr) {
    this.acrCycr = acrCycr;
  }

  public String getAaddGrp() {
    return aaddGrp;
  }

  public void setAaddGrp(String aaddGrp) {
    this.aaddGrp = aaddGrp;
  }

  public BigDecimal getAcrCydb() {
    return acrCydb;
  }

  public void setAcrCydb(BigDecimal acrCydb) {
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
}
