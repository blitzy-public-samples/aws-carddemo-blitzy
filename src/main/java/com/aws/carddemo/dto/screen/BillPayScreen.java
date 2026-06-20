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
 * Screen view contract for the legacy CICS/BMS <strong>Bill Payment</strong> screen ({@code
 * COBIL00}, CICS transaction {@code CB00}).
 *
 * <p>This is a plain, framework-light data-transfer object that carries the field values of the
 * 3270 bill-payment map across a request/response cycle. It is <em>not</em> a JPA entity and holds
 * no business logic: it mirrors, one Java property per logical field, the input map {@code
 * COBIL0AI} defined in the symbolic copybook {@code app/cpy-bms/COBIL00.CPY}. Field widths are
 * taken verbatim from that copybook (and corroborated by the mapset {@code app/bms/COBIL00.bms}),
 * preserving the fixed-width {@code PIC X(n)} contract via {@link Size} upper bounds so the
 * modernized screen behaves identically to the mainframe map.
 *
 * <p>The bill-pay flow displays the {@linkplain #getCurBal() current balance} for the entered
 * {@linkplain #getActIdIn() account id} and asks the operator to {@linkplain #getConfirm() confirm}
 * (Y/N) a full-balance payment. The transaction posting, balance zeroing, and repository updates
 * are the responsibility of {@code com.aws.carddemo.service.online.BillPayService}; this DTO only
 * transports the screen field values.
 *
 * <p>Monetary fidelity: {@code CURBAL} is exposed as a {@link BigDecimal} (scale 2) per Agent
 * Action Plan §0.6.1 — floating-point types are prohibited for decimal money fields. Formatting and
 * parsing of edited numeric values are intentionally <em>not</em> performed in this DTO; that
 * behavior belongs to {@code com.aws.carddemo.util.NumberFormatter}.
 *
 * <p>Source / authority: legacy {@code app/cpy-bms/COBIL00.CPY} (authoritative field names and
 * lengths) and {@code app/bms/COBIL00.bms} (rendering reference); Agent Action Plan §0.4.1, §0.3.4,
 * and §0.6.1.
 */
public class BillPayScreen {

  /** Transaction-id literal shown on the map header. COBOL {@code TRNNAMEI PIC X(4)}. */
  @Size(max = 4)
  private String trnName;

  /** First title line of the screen header. COBOL {@code TITLE01I PIC X(40)}. */
  @Size(max = 40)
  private String title01;

  /** Current-date display (mm/dd/yy). COBOL {@code CURDATEI PIC X(8)}. */
  @Size(max = 8)
  private String curDate;

  /** Program name shown on the map header. COBOL {@code PGMNAMEI PIC X(8)}. */
  @Size(max = 8)
  private String pgmName;

  /** Second title line of the screen header. COBOL {@code TITLE02I PIC X(40)}. */
  @Size(max = 40)
  private String title02;

  /** Current-time display (hh:mm:ss). COBOL {@code CURTIMEI PIC X(8)}. */
  @Size(max = 8)
  private String curTime;

  /** Account id entered by the operator. COBOL {@code ACTIDINI PIC X(11)}. */
  @Size(max = 11)
  private String actIdIn;

  /**
   * Current account balance presented for full-balance payment. Rendered as {@code CURBALI PIC
   * X(14)} on the BMS map; modeled here as a scale-2 {@link BigDecimal} monetary value (never
   * {@code float}/{@code double}) per Agent Action Plan §0.6.1.
   */
  private BigDecimal curBal;

  /** Payment confirmation flag, {@code Y} or {@code N}. COBOL {@code CONFIRMI PIC X(1)}. */
  @Size(max = 1)
  private String confirm;

  /** Error / informational message line. COBOL {@code ERRMSGI PIC X(78)}. */
  @Size(max = 78)
  private String errMsg;

  /** Creates an empty bill-payment screen DTO; all fields are initially unset ({@code null}). */
  public BillPayScreen() {
    // No-args constructor for framework binding and manual population.
  }

  /**
   * Returns the transaction-id literal ({@code TRNNAMEI}).
   *
   * @return the transaction-id text, or {@code null} if unset
   */
  public String getTrnName() {
    return trnName;
  }

  /**
   * Sets the transaction-id literal ({@code TRNNAMEI}).
   *
   * @param trnName the transaction-id text (maximum length 4)
   */
  public void setTrnName(String trnName) {
    this.trnName = trnName;
  }

  /**
   * Returns the first title line ({@code TITLE01I}).
   *
   * @return the first title line, or {@code null} if unset
   */
  public String getTitle01() {
    return title01;
  }

  /**
   * Sets the first title line ({@code TITLE01I}).
   *
   * @param title01 the first title line (maximum length 40)
   */
  public void setTitle01(String title01) {
    this.title01 = title01;
  }

  /**
   * Returns the current-date display ({@code CURDATEI}).
   *
   * @return the current-date text (mm/dd/yy), or {@code null} if unset
   */
  public String getCurDate() {
    return curDate;
  }

  /**
   * Sets the current-date display ({@code CURDATEI}).
   *
   * @param curDate the current-date text (maximum length 8)
   */
  public void setCurDate(String curDate) {
    this.curDate = curDate;
  }

  /**
   * Returns the program name ({@code PGMNAMEI}).
   *
   * @return the program name, or {@code null} if unset
   */
  public String getPgmName() {
    return pgmName;
  }

  /**
   * Sets the program name ({@code PGMNAMEI}).
   *
   * @param pgmName the program name (maximum length 8)
   */
  public void setPgmName(String pgmName) {
    this.pgmName = pgmName;
  }

  /**
   * Returns the second title line ({@code TITLE02I}).
   *
   * @return the second title line, or {@code null} if unset
   */
  public String getTitle02() {
    return title02;
  }

  /**
   * Sets the second title line ({@code TITLE02I}).
   *
   * @param title02 the second title line (maximum length 40)
   */
  public void setTitle02(String title02) {
    this.title02 = title02;
  }

  /**
   * Returns the current-time display ({@code CURTIMEI}).
   *
   * @return the current-time text (hh:mm:ss), or {@code null} if unset
   */
  public String getCurTime() {
    return curTime;
  }

  /**
   * Sets the current-time display ({@code CURTIMEI}).
   *
   * @param curTime the current-time text (maximum length 8)
   */
  public void setCurTime(String curTime) {
    this.curTime = curTime;
  }

  /**
   * Returns the entered account id ({@code ACTIDINI}).
   *
   * @return the account id text, or {@code null} if unset
   */
  public String getActIdIn() {
    return actIdIn;
  }

  /**
   * Sets the entered account id ({@code ACTIDINI}).
   *
   * @param actIdIn the account id text (maximum length 11)
   */
  public void setActIdIn(String actIdIn) {
    this.actIdIn = actIdIn;
  }

  /**
   * Returns the current account balance ({@code CURBALI}) as a scale-2 monetary amount.
   *
   * @return the current balance, or {@code null} if unset
   */
  public BigDecimal getCurBal() {
    return curBal;
  }

  /**
   * Sets the current account balance ({@code CURBALI}).
   *
   * <p>The supplied value is stored as-is; callers are responsible for providing a {@link
   * BigDecimal} at the intended monetary scale of 2. No scaling, rounding, or formatting is applied
   * by this DTO.
   *
   * @param curBal the current balance as a {@link BigDecimal}
   */
  public void setCurBal(BigDecimal curBal) {
    this.curBal = curBal;
  }

  /**
   * Returns the payment confirmation flag ({@code CONFIRMI}).
   *
   * @return the confirmation flag ({@code Y}/{@code N}), or {@code null} if unset
   */
  public String getConfirm() {
    return confirm;
  }

  /**
   * Sets the payment confirmation flag ({@code CONFIRMI}).
   *
   * @param confirm the confirmation flag, {@code Y} or {@code N} (maximum length 1)
   */
  public void setConfirm(String confirm) {
    this.confirm = confirm;
  }

  /**
   * Returns the error / informational message line ({@code ERRMSGI}).
   *
   * @return the message text, or {@code null} if unset
   */
  public String getErrMsg() {
    return errMsg;
  }

  /**
   * Sets the error / informational message line ({@code ERRMSGI}).
   *
   * @param errMsg the message text (maximum length 78)
   */
  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }
}
