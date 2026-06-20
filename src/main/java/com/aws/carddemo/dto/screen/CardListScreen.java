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
import java.util.ArrayList;
import java.util.List;

/**
 * Screen view contract for the Card List screen, migrated from the legacy CICS/BMS map {@code
 * COCRDLI} (CICS transaction {@code CCLI}).
 *
 * <p>This is a framework-light data-transfer object ("screen view contract") that mirrors the field
 * layout of the original 3270 map. It is intentionally <strong>not</strong> a JPA entity and holds
 * no persistence, web, or domain dependencies, in keeping with the layered Spring Boot target
 * architecture (AAP &sect;0.4.1 "dto/screen/*.java &larr; app/cpy-bms/*.CPY + app/bms/*.bms &mdash;
 * preserving field lengths", &sect;0.3.4).
 *
 * <p>The Card List screen is a paged list with a fixed geometry of <strong>seven</strong> repeating
 * card rows (mirroring the 3270 fixed-row layout). Each repeating row is modelled by the nested
 * {@link CardListRow} type, and the seven rows are exposed through {@link #getRows()} (index 0 maps
 * to legacy row 1 &hellip; index 6 maps to legacy row 7). Paging is driven by {@link #getPageNo()}
 * and the underlying list query is filtered by {@link #getAcctSid()} and {@link #getCardSid()}.
 *
 * <p>Field names and lengths are taken verbatim from the authoritative symbolic copybook {@code
 * legacy/app/cpy-bms/COCRDLI.CPY} (input map {@code CCRDLIAI}), with rendering lengths
 * cross-verified against {@code legacy/app/bms/COCRDLI.bms}. Every {@code PIC X(n)} value field
 * maps to a {@link String} carrying a {@code @Size(max = n)} constraint that preserves the fixed
 * legacy width.
 *
 * <p>The legacy BMS protection-control fields {@code CRDSTP2}&hellip;{@code CRDSTP7} are
 * deliberately omitted: they are dark, auto-skip, non-display attribute fields ({@code
 * ATTRB=(ASKIP,DRK,FSET)}) that carry no business data and exist only so the original map could
 * toggle per-row selection protection.
 */
public class CardListScreen {

  /**
   * A single repeating card row on the Card List screen, mirroring the legacy {@code CRDSEL<n>},
   * {@code ACCTNO<n>}, {@code CRDNUM<n>}, and {@code CRDSTS<n>} symbolic fields (n = 1..7).
   *
   * <p>The row is a mutable POJO so that the {@code crdSel} selection indicator can participate in
   * form binding when the operator marks a row for a follow-on action.
   */
  public static class CardListRow {

    /** Row selection indicator. COBOL: CRDSEL&lt;n&gt; PIC X(1). */
    @Size(max = 1)
    private String crdSel;

    /** Account number for this row. COBOL: ACCTNO&lt;n&gt; PIC X(11). */
    @Size(max = 11)
    private String acctNo;

    /** Card number for this row. COBOL: CRDNUM&lt;n&gt; PIC X(16). */
    @Size(max = 16)
    private String crdNum;

    /** Card active status for this row. COBOL: CRDSTS&lt;n&gt; PIC X(1). */
    @Size(max = 1)
    private String crdSts;

    /** Creates an empty row with all fields unset. */
    public CardListRow() {
      // No-args constructor for framework instantiation and form binding.
    }

    public String getCrdSel() {
      return crdSel;
    }

    public void setCrdSel(String crdSel) {
      this.crdSel = crdSel;
    }

    public String getAcctNo() {
      return acctNo;
    }

    public void setAcctNo(String acctNo) {
      this.acctNo = acctNo;
    }

    public String getCrdNum() {
      return crdNum;
    }

    public void setCrdNum(String crdNum) {
      this.crdNum = crdNum;
    }

    public String getCrdSts() {
      return crdSts;
    }

    public void setCrdSts(String crdSts) {
      this.crdSts = crdSts;
    }
  }

  /** Transaction identifier displayed in the screen header. COBOL: TRNNAME PIC X(4). */
  @Size(max = 4)
  private String trnName;

  /** First title line displayed in the screen header. COBOL: TITLE01 PIC X(40). */
  @Size(max = 40)
  private String title01;

  /** Current date displayed in the screen header. COBOL: CURDATE PIC X(8). */
  @Size(max = 8)
  private String curDate;

  /** Program name displayed in the screen header. COBOL: PGMNAME PIC X(8). */
  @Size(max = 8)
  private String pgmName;

  /** Second title line displayed in the screen header. COBOL: TITLE02 PIC X(40). */
  @Size(max = 40)
  private String title02;

  /** Current time displayed in the screen header. COBOL: CURTIME PIC X(8). */
  @Size(max = 8)
  private String curTime;

  /** Current page number for the paged list. COBOL: PAGENO PIC X(3). */
  @Size(max = 3)
  private String pageNo;

  /** Account-id search filter. COBOL: ACCTSID PIC X(11). */
  @Size(max = 11)
  private String acctSid;

  /** Card-number search filter. COBOL: CARDSID PIC X(16). */
  @Size(max = 16)
  private String cardSid;

  /**
   * The seven repeating card rows of the fixed-geometry list (index 0 = legacy row 1 &hellip; index
   * 6 = legacy row 7). Initialized to an empty, mutable list so callers can populate rows without a
   * null check.
   */
  private List<CardListRow> rows = new ArrayList<>();

  /** Informational message line. COBOL: INFOMSG PIC X(45). */
  @Size(max = 45)
  private String infoMsg;

  /** Error message line. COBOL: ERRMSG PIC X(78). */
  @Size(max = 78)
  private String errMsg;

  /** Creates an empty Card List screen with an initialized, empty {@code rows} list. */
  public CardListScreen() {
    // No-args constructor for framework instantiation and form binding.
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

  public String getPageNo() {
    return pageNo;
  }

  public void setPageNo(String pageNo) {
    this.pageNo = pageNo;
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

  public List<CardListRow> getRows() {
    return rows;
  }

  public void setRows(List<CardListRow> rows) {
    this.rows = rows;
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
}
