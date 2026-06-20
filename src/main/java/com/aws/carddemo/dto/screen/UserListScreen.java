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
 * Screen view contract for the legacy CICS/BMS <em>List Users</em> screen ({@code COUSR00}, CICS
 * transaction {@code CU00}, admin-only).
 *
 * <p>This is a framework-light data-transfer object ("screen view contract") that mirrors the field
 * layout of the original 3270 map. It is intentionally <strong>not</strong> a JPA entity and holds
 * no persistence, web, or domain dependencies, in keeping with the layered Spring Boot target
 * architecture (AAP &sect;0.4.1 "dto/screen/*.java &larr; app/cpy-bms/*.CPY + app/bms/*.bms &mdash;
 * preserving field lengths", &sect;0.3.4).
 *
 * <p>The List Users screen is the administrative entry point for user maintenance: it presents a
 * paged list of security users and lets the operator mark a row for a follow-on add/update/delete
 * action. It has a fixed geometry of <strong>ten</strong> repeating user rows (mirroring the 3270
 * fixed-row layout). Each repeating row is modelled by the nested {@link UserListRow} type, and the
 * ten rows are exposed through {@link #getRows()} (index 0 maps to legacy row 1 &hellip; index 9
 * maps to legacy row 10). Paging is driven by {@link #getPageNum()} and the underlying list query
 * is filtered by the user-id search input {@link #getUsrIdIn()}; row selection ({@link
 * UserListRow#getSel()}) drives navigation to the add/update/delete user screens performed by
 * {@code service.online.UserListService}.
 *
 * <p>Field names and lengths are taken verbatim from the authoritative symbolic copybook {@code
 * legacy/app/cpy-bms/COUSR00.CPY} (input map {@code COUSR0AI}), with rendering lengths
 * cross-verified against {@code legacy/app/bms/COUSR00.bms}. Every {@code PIC X(n)} value field
 * maps to a {@link String} carrying a {@code @Size(max = n)} constraint that preserves the fixed
 * legacy width. The BMS plumbing sub-fields (the {@code <name>L} length, {@code <name>F}/{@code
 * <name>A} attribute, and {@code FILLER} items) are deliberately omitted: they carry no business
 * data.
 */
public class UserListScreen {

  /**
   * A single repeating user row on the List Users screen, mirroring the legacy {@code SEL000<n>},
   * {@code USRID0<n>}, {@code FNAME0<n>}, {@code LNAME0<n>}, and {@code UTYPE0<n>} symbolic fields
   * (n = 1..10).
   *
   * <p>The row is a mutable POJO so that the {@code sel} selection indicator can participate in
   * form binding when the operator marks a row for a follow-on add/update/delete action.
   */
  public static class UserListRow {

    /** Row selection indicator. COBOL: SEL000&lt;n&gt; PIC X(1). */
    @Size(max = 1)
    private String sel;

    /** User id for this row. COBOL: USRID0&lt;n&gt; PIC X(8). */
    @Size(max = 8)
    private String usrId;

    /** First name for this row. COBOL: FNAME0&lt;n&gt; PIC X(20). */
    @Size(max = 20)
    private String fName;

    /** Last name for this row. COBOL: LNAME0&lt;n&gt; PIC X(20). */
    @Size(max = 20)
    private String lName;

    /**
     * User type for this row (e.g. {@code A}=admin, {@code U}=user). COBOL: UTYPE0&lt;n&gt; PIC
     * X(1).
     */
    @Size(max = 1)
    private String uType;

    /** Creates an empty row with all fields unset. */
    public UserListRow() {
      // No-args constructor for framework instantiation and form binding.
    }

    public String getSel() {
      return sel;
    }

    public void setSel(String sel) {
      this.sel = sel;
    }

    public String getUsrId() {
      return usrId;
    }

    public void setUsrId(String usrId) {
      this.usrId = usrId;
    }

    public String getFName() {
      return fName;
    }

    public void setFName(String fName) {
      this.fName = fName;
    }

    public String getLName() {
      return lName;
    }

    public void setLName(String lName) {
      this.lName = lName;
    }

    public String getUType() {
      return uType;
    }

    public void setUType(String uType) {
      this.uType = uType;
    }
  }

  /** Transaction identifier displayed in the screen header. COBOL: TRNNAME PIC X(4). */
  @Size(max = 4)
  private String trnName;

  /** First title line displayed in the screen header. COBOL: TITLE01 PIC X(40). */
  @Size(max = 40)
  private String title01;

  /**
   * Current date displayed in the screen header (e.g. {@code mm/dd/yy}). COBOL: CURDATE PIC X(8).
   */
  @Size(max = 8)
  private String curDate;

  /** Program name displayed in the screen header. COBOL: PGMNAME PIC X(8). */
  @Size(max = 8)
  private String pgmName;

  /** Second title line displayed in the screen header. COBOL: TITLE02 PIC X(40). */
  @Size(max = 40)
  private String title02;

  /**
   * Current time displayed in the screen header (e.g. {@code hh:mm:ss}). COBOL: CURTIME PIC X(8).
   */
  @Size(max = 8)
  private String curTime;

  /** Current page number for the paged list. COBOL: PAGENUM PIC X(8). */
  @Size(max = 8)
  private String pageNum;

  /** User-id search filter. COBOL: USRIDIN PIC X(8). */
  @Size(max = 8)
  private String usrIdIn;

  /**
   * The ten repeating user rows of the fixed-geometry list (index 0 = legacy row 1 &hellip; index 9
   * = legacy row 10). Initialized to an empty, mutable list so callers can populate rows without a
   * null check; {@code service.online.UserListService} fills it with the users for the current
   * page.
   */
  private List<UserListRow> rows = new ArrayList<>();

  /** Error message line. COBOL: ERRMSG PIC X(78). */
  @Size(max = 78)
  private String errMsg;

  /** Creates an empty List Users screen with an initialized, empty {@code rows} list. */
  public UserListScreen() {
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

  public String getPageNum() {
    return pageNum;
  }

  public void setPageNum(String pageNum) {
    this.pageNum = pageNum;
  }

  public String getUsrIdIn() {
    return usrIdIn;
  }

  public void setUsrIdIn(String usrIdIn) {
    this.usrIdIn = usrIdIn;
  }

  public List<UserListRow> getRows() {
    return rows;
  }

  public void setRows(List<UserListRow> rows) {
    this.rows = rows;
  }

  public String getErrMsg() {
    return errMsg;
  }

  public void setErrMsg(String errMsg) {
    this.errMsg = errMsg;
  }
}
