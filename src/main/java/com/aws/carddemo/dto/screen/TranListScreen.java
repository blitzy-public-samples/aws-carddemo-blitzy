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
import java.util.ArrayList;
import java.util.List;

/**
 * Screen view contract for the legacy CICS/BMS <em>Transaction List</em> screen ({@code COTRN00},
 * CICS transaction {@code CT00}).
 *
 * <p>This is a framework-light POJO that migrates the field contract of the 3270 transaction-list
 * map into the modernized AWS CardDemo Spring Boot application. It is <strong>not</strong> a JPA
 * entity and deliberately holds no persistence, presentation, or formatting logic — it simply
 * carries the screen's data values between the web/controller layer and the rendering layer,
 * preserving the original fixed-width field layout for behavioral parity.
 *
 * <p>The screen is a paged list: it renders exactly ten repeating transaction rows mirroring the
 * fixed 3270 geometry (see {@link TranListRow} and {@link #getRows()}), together with a header
 * (transaction/program names, titles, current date/time), a page indicator, a transaction-id search
 * input, and an error/informational message line.
 *
 * <p>Field names, lengths, and semantics are derived authoritatively from the legacy symbolic map
 * {@code legacy/app/cpy-bms/COTRN00.CPY} (the {@code COTRN0AI} input structure) and cross-checked
 * against the rendering reference {@code legacy/app/bms/COTRN00.bms}. Monetary amounts are modeled
 * as {@link java.math.BigDecimal} rather than any floating-point type.
 *
 * <p>Authority: Agent Action Plan §0.4.1 (dto/screen/*.java ← app/cpy-bms/*.CPY + app/bms/*.bms,
 * preserving field lengths), §0.3.4 (BMS screen field contract as the UI fidelity reference), and
 * §0.6.1 (COBOL fixed-point monetary fields → {@code BigDecimal}).
 */
public class TranListScreen {

  /**
   * Transaction (program) name shown in the screen header. COBOL field {@code TRNNAME PIC X(4)}.
   */
  @Size(max = 4)
  private String trnName;

  /** First header title line. COBOL field {@code TITLE01 PIC X(40)}. */
  @Size(max = 40)
  private String title01;

  /**
   * Current date as displayed in the header (e.g. {@code mm/dd/yy}). COBOL field {@code CURDATE PIC
   * X(8)}.
   */
  @Size(max = 8)
  private String curDate;

  /** Program name shown in the header. COBOL field {@code PGMNAME PIC X(8)}. */
  @Size(max = 8)
  private String pgmName;

  /** Second header title line. COBOL field {@code TITLE02 PIC X(40)}. */
  @Size(max = 40)
  private String title02;

  /**
   * Current time as displayed in the header (e.g. {@code hh:mm:ss}). COBOL field {@code CURTIME PIC
   * X(8)}.
   */
  @Size(max = 8)
  private String curTime;

  /** Current page number indicator. COBOL field {@code PAGENUM PIC X(8)}. */
  @Size(max = 8)
  private String pageNum;

  /** Transaction-id search input. COBOL field {@code TRNIDIN PIC X(16)}. */
  @Size(max = 16)
  private String trnIdIn;

  /**
   * Repeating transaction rows rendered by the fixed 3270 list geometry.
   *
   * <p>The legacy screen displays exactly ten rows (index {@code 0} corresponds to row 1 ... index
   * {@code 9} corresponds to row 10). This is initialized to an empty, mutable list so callers
   * never encounter {@code null}; the online service ({@code service.online.TranListService})
   * populates it with the transactions for the current page.
   */
  private List<TranListRow> rows = new ArrayList<>();

  /** Error or informational message line. COBOL field {@code ERRMSG PIC X(78)}. */
  @Size(max = 78)
  private String errMsg;

  // ---------------------------------------------------------------------------------------------
  // Hidden paging/selection cursors (not rendered BMS fields).
  //
  // The legacy program COTRN00C extends the shared COMMAREA copybook COCOM01Y with a private
  // CDEMO-CT00-INFO group that carries the keyset-paging cursors and the row-selection state
  // across pseudo-conversational RECEIVE/SEND cycles:
  //
  //     05 CDEMO-CT00-INFO.
  //        10 CDEMO-CT00-TRNID-FIRST    PIC X(16).
  //        10 CDEMO-CT00-TRNID-LAST     PIC X(16).
  //        10 CDEMO-CT00-PAGE-NUM       PIC 9(08).
  //        10 CDEMO-CT00-NEXT-PAGE-FLG  PIC X(01).   88 NEXT-PAGE-YES VALUE 'Y'.
  //        10 CDEMO-CT00-TRN-SEL-FLG    PIC X(01).
  //        10 CDEMO-CT00-TRN-SELECTED   PIC X(16).
  //
  // Because these are screen-specific to CT00 (not part of the shared CardDemoCommarea contract),
  // they are carried here as hidden round-tripped fields per the agent specification. The transient
  // CDEMO-CT00-TRN-SEL-FLG is NOT carried — it is recomputed locally within the service's
  // enter-key scan each interaction — so only the five persistent cursors are modeled here.
  // ---------------------------------------------------------------------------------------------

  /**
   * First transaction id displayed on the current page (keyset upper boundary used by page-up).
   * COBOL field {@code CDEMO-CT00-TRNID-FIRST PIC X(16)}.
   */
  @Size(max = 16)
  private String trnIdFirst;

  /**
   * Last transaction id displayed on the current page (keyset lower boundary used by page-down).
   * COBOL field {@code CDEMO-CT00-TRNID-LAST PIC X(16)}.
   */
  @Size(max = 16)
  private String trnIdLast;

  /**
   * Numeric current-page counter that backs the displayed {@link #getPageNum() page indicator}.
   * COBOL field {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}.
   */
  private int pageNumValue;

  /**
   * Whether a further page of transactions exists after the current page (the {@code STARTBR}
   * read-ahead peek result). COBOL 88-level {@code NEXT-PAGE-YES} on field {@code
   * CDEMO-CT00-NEXT-PAGE-FLG PIC X(01)}.
   */
  private boolean nextPageYes;

  /**
   * Transaction id of the row the operator selected with {@code S}; carried to the transaction-view
   * screen ({@code COTRN01C}) when a selection forwards. COBOL field {@code CDEMO-CT00-TRN-SELECTED
   * PIC X(16)}.
   */
  @Size(max = 16)
  private String trnSelected;

  /**
   * Creates an empty transaction-list screen.
   *
   * <p>The {@link #getRows() rows} list is initialized to an empty, mutable list; all scalar fields
   * are left {@code null} until populated by the service/controller layer.
   */
  public TranListScreen() {
    // No-args constructor for framework binding and incremental population.
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

  public String getPageNum() {
    return pageNum;
  }

  public void setPageNum(String pageNum) {
    this.pageNum = pageNum;
  }

  public String getTrnIdIn() {
    return trnIdIn;
  }

  public void setTrnIdIn(String trnIdIn) {
    this.trnIdIn = trnIdIn;
  }

  public List<TranListRow> getRows() {
    return rows;
  }

  public void setRows(List<TranListRow> rows) {
    this.rows = rows;
  }

  public String getErrMsg() {
    return errMsg;
  }

  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }

  public String getTrnIdFirst() {
    return trnIdFirst;
  }

  public void setTrnIdFirst(String trnIdFirst) {
    this.trnIdFirst = trnIdFirst;
  }

  public String getTrnIdLast() {
    return trnIdLast;
  }

  public void setTrnIdLast(String trnIdLast) {
    this.trnIdLast = trnIdLast;
  }

  public int getPageNumValue() {
    return pageNumValue;
  }

  public void setPageNumValue(int pageNumValue) {
    this.pageNumValue = pageNumValue;
  }

  public boolean isNextPageYes() {
    return nextPageYes;
  }

  public void setNextPageYes(boolean nextPageYes) {
    this.nextPageYes = nextPageYes;
  }

  public String getTrnSelected() {
    return trnSelected;
  }

  public void setTrnSelected(String trnSelected) {
    this.trnSelected = trnSelected;
  }

  /**
   * A single transaction row within the list view.
   *
   * <p>Mirrors one repeating BMS row group of {@code COTRN00} — selection flag ({@code SELxxxx}),
   * transaction id ({@code TRNIDxx}), date ({@code TDATExx}), description ({@code TDESCxx}), and
   * amount ({@code TAMTxxx}). The description here is the <em>truncated</em> list-view form (COBOL
   * {@code PIC X(26)}); the transaction detail screens carry the full {@code X(60)} description.
   *
   * <p>The amount is monetary and is represented as a {@link BigDecimal} (scale 2) per Agent Action
   * Plan §0.6.1 — never a floating-point type. Numeric formatting/parsing is intentionally excluded
   * from this view contract; that concern belongs to the dedicated number-formatting utility.
   */
  public static class TranListRow {

    /**
     * Selection input flag (e.g. {@code S} to view detail). COBOL field {@code SELxxxx PIC X(1)}.
     */
    @Size(max = 1)
    private String sel;

    /** Transaction identifier. COBOL field {@code TRNIDxx PIC X(16)}. */
    @Size(max = 16)
    private String trnId;

    /** Transaction date in display form. COBOL field {@code TDATExx PIC X(8)}. */
    @Size(max = 8)
    private String tDate;

    /** Truncated transaction description. COBOL field {@code TDESCxx PIC X(26)}. */
    @Size(max = 26)
    private String tDesc;

    /**
     * Transaction amount; monetary {@link BigDecimal} at scale 2. COBOL field {@code TAMTxxx PIC
     * X(12)}.
     */
    private BigDecimal tAmt;

    /** Creates an empty row, suitable for incremental population by the service layer. */
    public TranListRow() {
      // No-args constructor for framework binding and incremental population.
    }

    /**
     * Creates a fully populated transaction row.
     *
     * @param sel selection input flag
     * @param trnId transaction identifier
     * @param tDate transaction date in display form
     * @param tDesc truncated transaction description
     * @param tAmt transaction amount (monetary, scale 2)
     */
    public TranListRow(String sel, String trnId, String tDate, String tDesc, BigDecimal tAmt) {
      this.sel = sel;
      this.trnId = trnId;
      this.tDate = tDate;
      this.tDesc = tDesc;
      this.tAmt = tAmt;
    }

    public String getSel() {
      return sel;
    }

    public void setSel(String sel) {
      this.sel = sel;
    }

    public String getTrnId() {
      return trnId;
    }

    public void setTrnId(String trnId) {
      this.trnId = trnId;
    }

    public String getTDate() {
      return tDate;
    }

    public void setTDate(String tDate) {
      this.tDate = tDate;
    }

    public String getTDesc() {
      return tDesc;
    }

    public void setTDesc(String tDesc) {
      this.tDesc = tDesc;
    }

    public BigDecimal getTAmt() {
      return tAmt;
    }

    public void setTAmt(BigDecimal tAmt) {
      this.tAmt = tAmt;
    }
  }
}
