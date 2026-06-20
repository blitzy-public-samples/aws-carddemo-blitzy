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

import java.math.BigDecimal;

/**
 * Screen view contract for the <strong>Transaction Add</strong> screen, migrated from the legacy
 * CICS/BMS mapset {@code COTRN02} (CICS transaction {@code CT02}).
 *
 * <p>This is a plain, framework-light data-transfer object (DTO) — <em>not</em> a JPA entity. It
 * mirrors, one Java property per logical field, the input data values of the BMS map. The field
 * names and fixed widths are taken verbatim from the authoritative symbolic copybook {@code
 * legacy/app/cpy-bms/COTRN02.CPY} (input {@code 01 COTRN2AI} level), and the rendering hints (the
 * amount mask {@code (-99999999.99)} and the date masks {@code (YYYY-MM-DD)}) come from {@code
 * legacy/app/bms/COTRN02.bms}. Only the meaningful {@code <NAME>I} value fields are modeled; all
 * BMS plumbing (length, flag, attribute and {@code FILLER} items, and the entire {@code COTRN2AO}
 * output redefine) is intentionally omitted.
 *
 * <p>The original COBOL {@code PIC X(n)} width of every field is preserved exactly and documented
 * on each property so the downstream web and service layers can enforce the identical fixed-width
 * contract. Width enforcement, screen parsing and number/date formatting are deliberately
 * <em>not</em> performed here; they belong to the web/service layer and {@code
 * util.NumberFormatter} so this DTO stays a pure transport contract.
 *
 * <p>The transaction amount {@link #getTrnAmt() trnAmt} is held as a {@link BigDecimal} (monetary,
 * scale 2) and never as {@code float}/{@code double}, in keeping with the decimal-fidelity rule.
 *
 * <p>Unlike the transaction-view screen {@code COTRN01}, this add screen accepts the account id
 * ({@code ACTIDIN}) or card number ({@code CARDNIN}) the new transaction will be attached to and
 * adds a {@code CONFIRM} (Y/N) flag for the add-confirmation step. The actual validation and
 * persistence (insert via the transaction repository) are handled by {@code
 * com.aws.carddemo.service.online.TranAddService}.
 *
 * <p>Authority: AAP §0.4.1 (screen DTOs ← {@code app/cpy-bms/*.CPY} + {@code app/bms/*.bms},
 * preserving field lengths), §0.3.4 (screen view contract / navigation parity) and §0.6.1 (monetary
 * fields → {@link BigDecimal}).
 */
public class TranAddScreen {

  /** Transaction/screen name label; legacy {@code TRNNAMEI}, {@code PIC X(4)}. */
  private String trnName;

  /** Primary title line (application title); legacy {@code TITLE01I}, {@code PIC X(40)}. */
  private String title01;

  /** Current date label as rendered on the screen; legacy {@code CURDATEI}, {@code PIC X(8)}. */
  private String curDate;

  /** Current program name label; legacy {@code PGMNAMEI}, {@code PIC X(8)}. */
  private String pgmName;

  /** Secondary title line (screen title); legacy {@code TITLE02I}, {@code PIC X(40)}. */
  private String title02;

  /** Current time label as rendered on the screen; legacy {@code CURTIMEI}, {@code PIC X(8)}. */
  private String curTime;

  /** Account id the new transaction is attached to; legacy {@code ACTIDINI}, {@code PIC X(11)}. */
  private String actIdIn;

  /** Card number the new transaction is attached to; legacy {@code CARDNINI}, {@code PIC X(16)}. */
  private String cardNin;

  /** Transaction type code; legacy {@code TTYPCDI}, {@code PIC X(2)}. */
  private String ttypCd;

  /** Transaction category code; legacy {@code TCATCDI}, {@code PIC X(4)}. */
  private String tcatCd;

  /** Transaction source; legacy {@code TRNSRCI}, {@code PIC X(10)}. */
  private String trnSrc;

  /** Transaction description; legacy {@code TDESCI}, {@code PIC X(60)}. */
  private String tDesc;

  /**
   * Transaction amount entered on the screen. Legacy {@code TRNAMTI}; BMS raw {@code PIC X(12)}
   * with entry hint {@code (-99999999.99)}.
   *
   * <p>Held as a {@link BigDecimal} (monetary value, scale 2) per AAP §0.6.1 — never {@code float}
   * or {@code double}. Parsing and formatting of the raw screen text belong to the web/service
   * layer and {@code util.NumberFormatter}, not to this view DTO.
   */
  private BigDecimal trnAmt;

  /**
   * Original transaction date, hint {@code (YYYY-MM-DD)}; legacy {@code TORIGDTI}, {@code PIC
   * X(10)}.
   */
  private String tOrigDt;

  /** Processing date, hint {@code (YYYY-MM-DD)}; legacy {@code TPROCDTI}, {@code PIC X(10)}. */
  private String tProcDt;

  /** Merchant id; legacy {@code MIDI}, {@code PIC X(9)}. */
  private String mid;

  /** Merchant name; legacy {@code MNAMEI}, {@code PIC X(30)}. */
  private String mName;

  /** Merchant city; legacy {@code MCITYI}, {@code PIC X(25)}. */
  private String mCity;

  /** Merchant zip; legacy {@code MZIPI}, {@code PIC X(10)}. */
  private String mZip;

  /** Add-confirmation flag (Y/N); legacy {@code CONFIRMI}, {@code PIC X(1)}. */
  private String confirm;

  /** Error/feedback message line; legacy {@code ERRMSGI}, {@code PIC X(78)}. */
  private String errMsg;

  /** Creates an empty screen DTO; all fields default to {@code null} until populated. */
  public TranAddScreen() {
    // No-args constructor for framework instantiation and form binding.
  }

  /** Returns the transaction/screen name label ({@code TRNNAMEI}). */
  public String getTrnName() {
    return trnName;
  }

  /** Sets the transaction/screen name label ({@code TRNNAMEI}). */
  public void setTrnName(String trnName) {
    this.trnName = trnName;
  }

  /** Returns the primary title line ({@code TITLE01I}). */
  public String getTitle01() {
    return title01;
  }

  /** Sets the primary title line ({@code TITLE01I}). */
  public void setTitle01(String title01) {
    this.title01 = title01;
  }

  /** Returns the current date label ({@code CURDATEI}). */
  public String getCurDate() {
    return curDate;
  }

  /** Sets the current date label ({@code CURDATEI}). */
  public void setCurDate(String curDate) {
    this.curDate = curDate;
  }

  /** Returns the current program name label ({@code PGMNAMEI}). */
  public String getPgmName() {
    return pgmName;
  }

  /** Sets the current program name label ({@code PGMNAMEI}). */
  public void setPgmName(String pgmName) {
    this.pgmName = pgmName;
  }

  /** Returns the secondary title line ({@code TITLE02I}). */
  public String getTitle02() {
    return title02;
  }

  /** Sets the secondary title line ({@code TITLE02I}). */
  public void setTitle02(String title02) {
    this.title02 = title02;
  }

  /** Returns the current time label ({@code CURTIMEI}). */
  public String getCurTime() {
    return curTime;
  }

  /** Sets the current time label ({@code CURTIMEI}). */
  public void setCurTime(String curTime) {
    this.curTime = curTime;
  }

  /** Returns the account id input ({@code ACTIDINI}). */
  public String getActIdIn() {
    return actIdIn;
  }

  /** Sets the account id input ({@code ACTIDINI}). */
  public void setActIdIn(String actIdIn) {
    this.actIdIn = actIdIn;
  }

  /** Returns the card number input ({@code CARDNINI}). */
  public String getCardNin() {
    return cardNin;
  }

  /** Sets the card number input ({@code CARDNINI}). */
  public void setCardNin(String cardNin) {
    this.cardNin = cardNin;
  }

  /** Returns the transaction type code ({@code TTYPCDI}). */
  public String getTtypCd() {
    return ttypCd;
  }

  /** Sets the transaction type code ({@code TTYPCDI}). */
  public void setTtypCd(String ttypCd) {
    this.ttypCd = ttypCd;
  }

  /** Returns the transaction category code ({@code TCATCDI}). */
  public String getTcatCd() {
    return tcatCd;
  }

  /** Sets the transaction category code ({@code TCATCDI}). */
  public void setTcatCd(String tcatCd) {
    this.tcatCd = tcatCd;
  }

  /** Returns the transaction source ({@code TRNSRCI}). */
  public String getTrnSrc() {
    return trnSrc;
  }

  /** Sets the transaction source ({@code TRNSRCI}). */
  public void setTrnSrc(String trnSrc) {
    this.trnSrc = trnSrc;
  }

  /** Returns the transaction description ({@code TDESCI}). */
  public String getTDesc() {
    return tDesc;
  }

  /** Sets the transaction description ({@code TDESCI}). */
  public void setTDesc(String tDesc) {
    this.tDesc = tDesc;
  }

  /** Returns the transaction amount as a scale-2 {@link BigDecimal} ({@code TRNAMTI}). */
  public BigDecimal getTrnAmt() {
    return trnAmt;
  }

  /** Sets the transaction amount; callers supply a scale-2 {@link BigDecimal} ({@code TRNAMTI}). */
  public void setTrnAmt(BigDecimal trnAmt) {
    this.trnAmt = trnAmt;
  }

  /** Returns the original transaction date ({@code TORIGDTI}). */
  public String getTOrigDt() {
    return tOrigDt;
  }

  /** Sets the original transaction date ({@code TORIGDTI}). */
  public void setTOrigDt(String tOrigDt) {
    this.tOrigDt = tOrigDt;
  }

  /** Returns the processing date ({@code TPROCDTI}). */
  public String getTProcDt() {
    return tProcDt;
  }

  /** Sets the processing date ({@code TPROCDTI}). */
  public void setTProcDt(String tProcDt) {
    this.tProcDt = tProcDt;
  }

  /** Returns the merchant id ({@code MIDI}). */
  public String getMid() {
    return mid;
  }

  /** Sets the merchant id ({@code MIDI}). */
  public void setMid(String mid) {
    this.mid = mid;
  }

  /** Returns the merchant name ({@code MNAMEI}). */
  public String getMName() {
    return mName;
  }

  /** Sets the merchant name ({@code MNAMEI}). */
  public void setMName(String mName) {
    this.mName = mName;
  }

  /** Returns the merchant city ({@code MCITYI}). */
  public String getMCity() {
    return mCity;
  }

  /** Sets the merchant city ({@code MCITYI}). */
  public void setMCity(String mCity) {
    this.mCity = mCity;
  }

  /** Returns the merchant zip ({@code MZIPI}). */
  public String getMZip() {
    return mZip;
  }

  /** Sets the merchant zip ({@code MZIPI}). */
  public void setMZip(String mZip) {
    this.mZip = mZip;
  }

  /** Returns the add-confirmation flag (Y/N) ({@code CONFIRMI}). */
  public String getConfirm() {
    return confirm;
  }

  /** Sets the add-confirmation flag (Y/N) ({@code CONFIRMI}). */
  public void setConfirm(String confirm) {
    this.confirm = confirm;
  }

  /** Returns the error/feedback message line ({@code ERRMSGI}). */
  public String getErrMsg() {
    return errMsg;
  }

  /** Sets the error/feedback message line ({@code ERRMSGI}). */
  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }
}
