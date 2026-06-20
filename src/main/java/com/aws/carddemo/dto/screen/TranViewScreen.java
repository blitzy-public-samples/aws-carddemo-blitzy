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
 * Screen view contract for the legacy CICS/BMS <strong>Transaction View</strong> screen.
 *
 * <p>This Plain-Old-Java-Object migrates the field contract of BMS mapset {@code COTRN01} (map
 * {@code COTRN1A}, CICS transaction {@code CT01}, online program {@code COTRN01C}) into the
 * modernized Spring Boot application. It is a read-only, single-transaction detail view used to
 * search for a transaction by id and render its resolved detail.
 *
 * <p>Per the Agent Action Plan this type is a framework-light screen DTO and is deliberately
 * <em>not</em> a JPA entity: it carries no persistence mapping and depends on no domain entity (AAP
 * &sect;0.4.1 &mdash; {@code dto/screen/*.java} &larr; {@code app/cpy-bms/*.CPY} + {@code
 * app/bms/*.bms}, preserving field lengths; &sect;0.3.4 &mdash; BMS field contract is the UI
 * fidelity reference). Each property maps one-to-one to a meaningful {@code <NAME>I} data-value
 * field of the {@code COTRN1AI} symbolic map; the BMS plumbing sub-fields ({@code L/F/A/C/P/H/V})
 * and {@code FILLER} pads carry no business data and are intentionally omitted.
 *
 * <p>Field widths are preserved exactly as declared by the copybook ({@code PIC X(n)} &rarr; {@code
 * String} of the same maximum length, enforced declaratively via {@link Size}). The single monetary
 * field {@code trnAmt} is modeled as {@link BigDecimal} (conceptual scale 2) rather than a binary
 * floating-point type, in keeping with the decimal-fidelity rule that prohibits {@code
 * float}/{@code double} for monetary data (AAP &sect;0.6.1). This DTO performs no formatting or
 * parsing of its own; edited-numeric rendering belongs to the dedicated number-formatter utility in
 * the service/util layer.
 *
 * <p>Navigation note: {@code trnIdIn} holds the operator's transaction-id search entry ({@code
 * TRNIDIN}), whereas {@code trnId} holds the resolved transaction id ({@code TRNID}) of the record
 * located by the view service. The merchant attributes ({@code mid}, {@code mName}, {@code mCity},
 * {@code mZip}) originate from the merchant sub-fields of the transaction record.
 */
public class TranViewScreen {

  /** Transaction name / screen tag &mdash; {@code TRNNAMEI PIC X(4)}. */
  @Size(max = 4)
  private String trnName;

  /** Screen title line 1 &mdash; {@code TITLE01I PIC X(40)}. */
  @Size(max = 40)
  private String title01;

  /** Current date (header) &mdash; {@code CURDATEI PIC X(8)}. */
  @Size(max = 8)
  private String curDate;

  /** Current program name (header) &mdash; {@code PGMNAMEI PIC X(8)}. */
  @Size(max = 8)
  private String pgmName;

  /** Screen title line 2 &mdash; {@code TITLE02I PIC X(40)}. */
  @Size(max = 40)
  private String title02;

  /** Current time (header) &mdash; {@code CURTIMEI PIC X(8)}. */
  @Size(max = 8)
  private String curTime;

  /** Transaction-id search input entered by the operator &mdash; {@code TRNIDINI PIC X(16)}. */
  @Size(max = 16)
  private String trnIdIn;

  /** Resolved transaction id of the located record &mdash; {@code TRNIDI PIC X(16)}. */
  @Size(max = 16)
  private String trnId;

  /** Card number associated with the transaction &mdash; {@code CARDNUMI PIC X(16)}. */
  @Size(max = 16)
  private String cardNum;

  /** Transaction type code &mdash; {@code TTYPCDI PIC X(2)}. */
  @Size(max = 2)
  private String ttypCd;

  /** Transaction category code &mdash; {@code TCATCDI PIC X(4)}. */
  @Size(max = 4)
  private String tcatCd;

  /** Transaction source &mdash; {@code TRNSRCI PIC X(10)}. */
  @Size(max = 10)
  private String trnSrc;

  /** Transaction description &mdash; {@code TDESCI PIC X(60)}. */
  @Size(max = 60)
  private String tDesc;

  /**
   * Transaction amount &mdash; {@code TRNAMTI} (BMS raw {@code PIC X(12)}). Modeled as {@link
   * BigDecimal} with a conceptual scale of 2; never {@code float}/{@code double} (AAP &sect;0.6.1).
   * Edited-numeric rendering is performed by the number-formatter utility, not here.
   */
  private BigDecimal trnAmt;

  /** Original transaction date &mdash; {@code TORIGDTI PIC X(10)}. */
  @Size(max = 10)
  private String tOrigDt;

  /** Processing date &mdash; {@code TPROCDTI PIC X(10)}. */
  @Size(max = 10)
  private String tProcDt;

  /** Merchant id &mdash; {@code MIDI PIC X(9)}. */
  @Size(max = 9)
  private String mid;

  /** Merchant name &mdash; {@code MNAMEI PIC X(30)}. */
  @Size(max = 30)
  private String mName;

  /** Merchant city &mdash; {@code MCITYI PIC X(25)}. */
  @Size(max = 25)
  private String mCity;

  /** Merchant zip &mdash; {@code MZIPI PIC X(10)}. */
  @Size(max = 10)
  private String mZip;

  /** Error / informational message line &mdash; {@code ERRMSGI PIC X(78)}. */
  @Size(max = 78)
  private String errMsg;

  /** Creates an empty screen contract with all fields unset. */
  public TranViewScreen() {
    // No-args constructor for framework instantiation and incremental field population.
  }

  /**
   * Returns the transaction name / screen tag ({@code TRNNAME}).
   *
   * @return the transaction name, maximum length 4
   */
  public String getTrnName() {
    return trnName;
  }

  /**
   * Sets the transaction name / screen tag ({@code TRNNAME}).
   *
   * @param trnName the transaction name, maximum length 4
   */
  public void setTrnName(String trnName) {
    this.trnName = trnName;
  }

  /**
   * Returns screen title line 1 ({@code TITLE01}).
   *
   * @return title line 1, maximum length 40
   */
  public String getTitle01() {
    return title01;
  }

  /**
   * Sets screen title line 1 ({@code TITLE01}).
   *
   * @param title01 title line 1, maximum length 40
   */
  public void setTitle01(String title01) {
    this.title01 = title01;
  }

  /**
   * Returns the current date header value ({@code CURDATE}).
   *
   * @return the current date, maximum length 8
   */
  public String getCurDate() {
    return curDate;
  }

  /**
   * Sets the current date header value ({@code CURDATE}).
   *
   * @param curDate the current date, maximum length 8
   */
  public void setCurDate(String curDate) {
    this.curDate = curDate;
  }

  /**
   * Returns the current program name header value ({@code PGMNAME}).
   *
   * @return the program name, maximum length 8
   */
  public String getPgmName() {
    return pgmName;
  }

  /**
   * Sets the current program name header value ({@code PGMNAME}).
   *
   * @param pgmName the program name, maximum length 8
   */
  public void setPgmName(String pgmName) {
    this.pgmName = pgmName;
  }

  /**
   * Returns screen title line 2 ({@code TITLE02}).
   *
   * @return title line 2, maximum length 40
   */
  public String getTitle02() {
    return title02;
  }

  /**
   * Sets screen title line 2 ({@code TITLE02}).
   *
   * @param title02 title line 2, maximum length 40
   */
  public void setTitle02(String title02) {
    this.title02 = title02;
  }

  /**
   * Returns the current time header value ({@code CURTIME}).
   *
   * @return the current time, maximum length 8
   */
  public String getCurTime() {
    return curTime;
  }

  /**
   * Sets the current time header value ({@code CURTIME}).
   *
   * @param curTime the current time, maximum length 8
   */
  public void setCurTime(String curTime) {
    this.curTime = curTime;
  }

  /**
   * Returns the transaction-id search input ({@code TRNIDIN}).
   *
   * @return the search-entry transaction id, maximum length 16
   */
  public String getTrnIdIn() {
    return trnIdIn;
  }

  /**
   * Sets the transaction-id search input ({@code TRNIDIN}).
   *
   * @param trnIdIn the search-entry transaction id, maximum length 16
   */
  public void setTrnIdIn(String trnIdIn) {
    this.trnIdIn = trnIdIn;
  }

  /**
   * Returns the resolved transaction id ({@code TRNID}).
   *
   * @return the resolved transaction id, maximum length 16
   */
  public String getTrnId() {
    return trnId;
  }

  /**
   * Sets the resolved transaction id ({@code TRNID}).
   *
   * @param trnId the resolved transaction id, maximum length 16
   */
  public void setTrnId(String trnId) {
    this.trnId = trnId;
  }

  /**
   * Returns the card number ({@code CARDNUM}).
   *
   * @return the card number, maximum length 16
   */
  public String getCardNum() {
    return cardNum;
  }

  /**
   * Sets the card number ({@code CARDNUM}).
   *
   * @param cardNum the card number, maximum length 16
   */
  public void setCardNum(String cardNum) {
    this.cardNum = cardNum;
  }

  /**
   * Returns the transaction type code ({@code TTYPCD}).
   *
   * @return the transaction type code, maximum length 2
   */
  public String getTtypCd() {
    return ttypCd;
  }

  /**
   * Sets the transaction type code ({@code TTYPCD}).
   *
   * @param ttypCd the transaction type code, maximum length 2
   */
  public void setTtypCd(String ttypCd) {
    this.ttypCd = ttypCd;
  }

  /**
   * Returns the transaction category code ({@code TCATCD}).
   *
   * @return the transaction category code, maximum length 4
   */
  public String getTcatCd() {
    return tcatCd;
  }

  /**
   * Sets the transaction category code ({@code TCATCD}).
   *
   * @param tcatCd the transaction category code, maximum length 4
   */
  public void setTcatCd(String tcatCd) {
    this.tcatCd = tcatCd;
  }

  /**
   * Returns the transaction source ({@code TRNSRC}).
   *
   * @return the transaction source, maximum length 10
   */
  public String getTrnSrc() {
    return trnSrc;
  }

  /**
   * Sets the transaction source ({@code TRNSRC}).
   *
   * @param trnSrc the transaction source, maximum length 10
   */
  public void setTrnSrc(String trnSrc) {
    this.trnSrc = trnSrc;
  }

  /**
   * Returns the transaction description ({@code TDESC}).
   *
   * @return the transaction description, maximum length 60
   */
  public String getTDesc() {
    return tDesc;
  }

  /**
   * Sets the transaction description ({@code TDESC}).
   *
   * @param tDesc the transaction description, maximum length 60
   */
  public void setTDesc(String tDesc) {
    this.tDesc = tDesc;
  }

  /**
   * Returns the transaction amount ({@code TRNAMT}).
   *
   * @return the transaction amount as a {@link BigDecimal} (conceptual scale 2)
   */
  public BigDecimal getTrnAmt() {
    return trnAmt;
  }

  /**
   * Sets the transaction amount ({@code TRNAMT}).
   *
   * @param trnAmt the transaction amount as a {@link BigDecimal} (conceptual scale 2); never a
   *     binary floating-point value
   */
  public void setTrnAmt(BigDecimal trnAmt) {
    this.trnAmt = trnAmt;
  }

  /**
   * Returns the original transaction date ({@code TORIGDT}).
   *
   * @return the original date, maximum length 10
   */
  public String getTOrigDt() {
    return tOrigDt;
  }

  /**
   * Sets the original transaction date ({@code TORIGDT}).
   *
   * @param tOrigDt the original date, maximum length 10
   */
  public void setTOrigDt(String tOrigDt) {
    this.tOrigDt = tOrigDt;
  }

  /**
   * Returns the processing date ({@code TPROCDT}).
   *
   * @return the processing date, maximum length 10
   */
  public String getTProcDt() {
    return tProcDt;
  }

  /**
   * Sets the processing date ({@code TPROCDT}).
   *
   * @param tProcDt the processing date, maximum length 10
   */
  public void setTProcDt(String tProcDt) {
    this.tProcDt = tProcDt;
  }

  /**
   * Returns the merchant id ({@code MID}).
   *
   * @return the merchant id, maximum length 9
   */
  public String getMid() {
    return mid;
  }

  /**
   * Sets the merchant id ({@code MID}).
   *
   * @param mid the merchant id, maximum length 9
   */
  public void setMid(String mid) {
    this.mid = mid;
  }

  /**
   * Returns the merchant name ({@code MNAME}).
   *
   * @return the merchant name, maximum length 30
   */
  public String getMName() {
    return mName;
  }

  /**
   * Sets the merchant name ({@code MNAME}).
   *
   * @param mName the merchant name, maximum length 30
   */
  public void setMName(String mName) {
    this.mName = mName;
  }

  /**
   * Returns the merchant city ({@code MCITY}).
   *
   * @return the merchant city, maximum length 25
   */
  public String getMCity() {
    return mCity;
  }

  /**
   * Sets the merchant city ({@code MCITY}).
   *
   * @param mCity the merchant city, maximum length 25
   */
  public void setMCity(String mCity) {
    this.mCity = mCity;
  }

  /**
   * Returns the merchant zip ({@code MZIP}).
   *
   * @return the merchant zip, maximum length 10
   */
  public String getMZip() {
    return mZip;
  }

  /**
   * Sets the merchant zip ({@code MZIP}).
   *
   * @param mZip the merchant zip, maximum length 10
   */
  public void setMZip(String mZip) {
    this.mZip = mZip;
  }

  /**
   * Returns the error / informational message line ({@code ERRMSG}).
   *
   * @return the message text, maximum length 78
   */
  public String getErrMsg() {
    return errMsg;
  }

  /**
   * Sets the error / informational message line ({@code ERRMSG}).
   *
   * @param errMsg the message text, maximum length 78
   */
  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }
}
