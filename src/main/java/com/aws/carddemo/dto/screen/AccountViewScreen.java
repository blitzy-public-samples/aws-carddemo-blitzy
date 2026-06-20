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

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Screen view contract for the Account View screen (BMS mapset {@code COACTVW}, CICS transaction
 * {@code CAVW}).
 *
 * <p>This is a plain, framework-light data-transfer object that migrates the legacy 3270/BMS
 * Account View screen field contract into the modernized AWS CardDemo Spring Boot application. The
 * screen is read-only: it displays an account together with the details of its owning customer.
 * This type is intentionally <strong>not</strong> a JPA entity and carries no persistence or
 * presentation behavior; entity-to-DTO mapping lives in the service/mapper layer, and the COBOL
 * edited-numeric rendering (for example the {@code +ZZZ,ZZZ,ZZZ.99} amount masks) lives in {@code
 * com.aws.carddemo.util.NumberFormatter}, invoked by the web/service layer. This DTO stores only
 * the raw field values.
 *
 * <p>Field names, fixed-width lengths, and types are derived directly from the authoritative
 * symbolic copybook {@code legacy/app/cpy-bms/COACTVW.CPY} (the input data-value fields whose names
 * carry the {@code I} suffix), with the BMS mapset {@code legacy/app/bms/COACTVW.bms} as the
 * rendering reference (POS / LENGTH / ATTRB / PICOUT). Only the meaningful logical data fields are
 * modeled; all BMS plumbing — the length ({@code L}), attribute ({@code F}/{@code A}), and
 * colour/highlight/validation ({@code C}/{@code P}/{@code H}/{@code V}) sub-fields plus {@code
 * FILLER} pads — is intentionally omitted.
 *
 * <p>Parity rules applied (Agent Action Plan):
 *
 * <ul>
 *   <li>Monetary amounts use {@link java.math.BigDecimal} with scale 2; floating-point types are
 *       prohibited for decimal data (AAP 0.6.1).
 *   <li>The numeric account identifier is modeled as a {@link String} to preserve its exact
 *       11-character fixed width (including leading zeros) and VSAM key semantics (AAP 0.6.2).
 *   <li>Fixed-width field lengths from the copybook are preserved and documented on each field (AAP
 *       0.4.1, 0.3.4).
 * </ul>
 */
public class AccountViewScreen {

  // ----- Screen header: transaction/program identity, titles, date and time -----

  /** TRNNAME PIC X(4) - CICS transaction name shown in the screen header. */
  @Size(max = 4)
  private String trnName;

  /** TITLE01 PIC X(40) - first title line. */
  @Size(max = 40)
  private String title01;

  /** CURDATE PIC X(8) - current date (mm/dd/yy). */
  @Size(max = 8)
  private String curDate;

  /** PGMNAME PIC X(8) - current program name. */
  @Size(max = 8)
  private String pgmName;

  /** TITLE02 PIC X(40) - second title line. */
  @Size(max = 40)
  private String title02;

  /** CURTIME PIC X(8) - current time (hh:mm:ss). */
  @Size(max = 8)
  private String curTime;

  // ----- Account identifier: the sole input (UNPROT) field on the screen -----

  /**
   * ACCTSID PIC 9(11) - account identifier (11 zoned digits; BMS PICIN '99999999999').
   *
   * <p>Modeled as a {@link String} rather than a numeric type to preserve the exact 11-character
   * fixed width, including any leading zeros, and to keep parity with VSAM key semantics (AAP
   * 0.6.2). This is the only enterable field on the read-only view screen.
   */
  @Size(max = 11)
  @Pattern(regexp = "\\d{0,11}")
  private String acctSid;

  // ----- Account detail fields -----

  /** ACSTTUS PIC X(1) - account active status (Y/N). */
  @Size(max = 1)
  private String acstTus;

  /** ADTOPEN PIC X(10) - account open date. */
  @Size(max = 10)
  private String adtOpen;

  /** ACRDLIM - credit limit. Monetary; BMS PICOUT '+ZZZ,ZZZ,ZZZ.99'. {@link BigDecimal} scale 2. */
  private BigDecimal acrdLim;

  /** AEXPDT PIC X(10) - card expiration date. */
  @Size(max = 10)
  private String aexpDt;

  /** ACSHLIM - cash credit limit. Monetary; {@link BigDecimal} scale 2. */
  private BigDecimal acshLim;

  /** AREISDT PIC X(10) - card reissue date. */
  @Size(max = 10)
  private String areisDt;

  /** ACURBAL - current account balance. Monetary; {@link BigDecimal} scale 2. */
  private BigDecimal acurBal;

  /** ACRCYCR - current cycle credit. Monetary; {@link BigDecimal} scale 2. */
  private BigDecimal acrCycr;

  /** AADDGRP PIC X(10) - account group identifier. */
  @Size(max = 10)
  private String aaddGrp;

  /** ACRCYDB - current cycle debit. Monetary; {@link BigDecimal} scale 2. */
  private BigDecimal acrCydb;

  // ----- Customer detail fields (owning customer of the account) -----

  /** ACSTNUM PIC X(9) - customer number. */
  @Size(max = 9)
  private String acstNum;

  /** ACSTSSN PIC X(12) - social security number (PII; masked in {@link #toString()}). */
  @Size(max = 12)
  private String acstSsn;

  /** ACSTDOB PIC X(10) - customer date of birth. */
  @Size(max = 10)
  private String acstDob;

  /** ACSTFCO PIC X(3) - customer FICO credit score. */
  @Size(max = 3)
  private String acstFco;

  /** ACSFNAM PIC X(25) - customer first name. */
  @Size(max = 25)
  private String acsFnam;

  /** ACSMNAM PIC X(25) - customer middle name. */
  @Size(max = 25)
  private String acsMnam;

  /** ACSLNAM PIC X(25) - customer last name. */
  @Size(max = 25)
  private String acsLnam;

  /** ACSADL1 PIC X(50) - customer address line 1. */
  @Size(max = 50)
  private String acsAdl1;

  /** ACSSTTE PIC X(2) - customer state code. */
  @Size(max = 2)
  private String acsStte;

  /** ACSADL2 PIC X(50) - customer address line 2. */
  @Size(max = 50)
  private String acsAdl2;

  /** ACSZIPC PIC X(5) - customer zip code. */
  @Size(max = 5)
  private String acsZipc;

  /** ACSCITY PIC X(50) - customer city. */
  @Size(max = 50)
  private String acsCity;

  /** ACSCTRY PIC X(3) - customer country code. */
  @Size(max = 3)
  private String acsCtry;

  /** ACSPHN1 PIC X(13) - customer phone number 1. */
  @Size(max = 13)
  private String acsPhn1;

  /** ACSGOVT PIC X(20) - government-issued identification reference. */
  @Size(max = 20)
  private String acsGovt;

  /** ACSPHN2 PIC X(13) - customer phone number 2. */
  @Size(max = 13)
  private String acsPhn2;

  /** ACSEFTC PIC X(10) - EFT (electronic funds transfer) account code. */
  @Size(max = 10)
  private String acsEftc;

  /** ACSPFLG PIC X(1) - primary cardholder flag (Y/N). */
  @Size(max = 1)
  private String acsPflg;

  // ----- Screen message fields -----

  /** INFOMSG PIC X(45) - informational message line. */
  @Size(max = 45)
  private String infoMsg;

  /** ERRMSG PIC X(78) - error message line. */
  @Size(max = 78)
  private String errMsg;

  /** Creates an empty screen DTO. Required for framework instantiation and data binding. */
  public AccountViewScreen() {
    // No-args constructor; fields are populated via setters by the web/service layer.
  }

  // ----- Accessors -----

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

  public String getAdtOpen() {
    return adtOpen;
  }

  public void setAdtOpen(String adtOpen) {
    this.adtOpen = adtOpen;
  }

  public BigDecimal getAcrdLim() {
    return acrdLim;
  }

  public void setAcrdLim(BigDecimal acrdLim) {
    this.acrdLim = acrdLim;
  }

  public String getAexpDt() {
    return aexpDt;
  }

  public void setAexpDt(String aexpDt) {
    this.aexpDt = aexpDt;
  }

  public BigDecimal getAcshLim() {
    return acshLim;
  }

  public void setAcshLim(BigDecimal acshLim) {
    this.acshLim = acshLim;
  }

  public String getAreisDt() {
    return areisDt;
  }

  public void setAreisDt(String areisDt) {
    this.areisDt = areisDt;
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

  public String getAcstSsn() {
    return acstSsn;
  }

  public void setAcstSsn(String acstSsn) {
    this.acstSsn = acstSsn;
  }

  public String getAcstDob() {
    return acstDob;
  }

  public void setAcstDob(String acstDob) {
    this.acstDob = acstDob;
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

  public String getAcsPhn1() {
    return acsPhn1;
  }

  public void setAcsPhn1(String acsPhn1) {
    this.acsPhn1 = acsPhn1;
  }

  public String getAcsGovt() {
    return acsGovt;
  }

  public void setAcsGovt(String acsGovt) {
    this.acsGovt = acsGovt;
  }

  public String getAcsPhn2() {
    return acsPhn2;
  }

  public void setAcsPhn2(String acsPhn2) {
    this.acsPhn2 = acsPhn2;
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

  /**
   * Returns a diagnostic representation of this screen DTO. The social security number is masked so
   * that the value is not leaked into logs or debug output; all other fields are rendered verbatim.
   *
   * @return a string describing the current field values, with the SSN masked
   */
  @Override
  public String toString() {
    return "AccountViewScreen{"
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
        + ", acctSid="
        + acctSid
        + ", acstTus="
        + acstTus
        + ", adtOpen="
        + adtOpen
        + ", acrdLim="
        + acrdLim
        + ", aexpDt="
        + aexpDt
        + ", acshLim="
        + acshLim
        + ", areisDt="
        + areisDt
        + ", acurBal="
        + acurBal
        + ", acrCycr="
        + acrCycr
        + ", aaddGrp="
        + aaddGrp
        + ", acrCydb="
        + acrCydb
        + ", acstNum="
        + acstNum
        + ", acstSsn="
        + maskSsn(acstSsn)
        + ", acstDob="
        + acstDob
        + ", acstFco="
        + acstFco
        + ", acsFnam="
        + acsFnam
        + ", acsMnam="
        + acsMnam
        + ", acsLnam="
        + acsLnam
        + ", acsAdl1="
        + acsAdl1
        + ", acsStte="
        + acsStte
        + ", acsAdl2="
        + acsAdl2
        + ", acsZipc="
        + acsZipc
        + ", acsCity="
        + acsCity
        + ", acsCtry="
        + acsCtry
        + ", acsPhn1="
        + acsPhn1
        + ", acsGovt="
        + acsGovt
        + ", acsPhn2="
        + acsPhn2
        + ", acsEftc="
        + acsEftc
        + ", acsPflg="
        + acsPflg
        + ", infoMsg="
        + infoMsg
        + ", errMsg="
        + errMsg
        + '}';
  }

  /**
   * Masks a social security number for safe inclusion in log or debug output, revealing at most the
   * final four characters. The input is returned unchanged when it is {@code null}.
   *
   * @param ssn the raw SSN value (may be {@code null})
   * @return the masked SSN, or {@code null} when the input is {@code null}
   */
  private static String maskSsn(String ssn) {
    if (ssn == null) {
      return null;
    }
    int length = ssn.length();
    if (length <= 4) {
      return "*".repeat(length);
    }
    return "*".repeat(length - 4) + ssn.substring(length - 4);
  }
}
