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
package com.aws.carddemo.dto;

import java.util.Objects;

/**
 * Online card / navigation work area, migrated from the COBOL copybook {@code CVCRD01Y.cpy} (the
 * single {@code 01 CC-WORK-AREAS} group item).
 *
 * <p>In the legacy z/OS application this work area is the pseudo-conversational scratch pad that
 * the CICS online programs use to carry the attention identifier (AID / PF-key), the next
 * program/mapset/map to route to, the error and return messages, and a small set of identifier
 * fields. In the modernized Spring Boot application it is a plain, framework-light POJO consumed by
 * the web / controller layer as the key-action and navigation context. It is <strong>not</strong> a
 * JPA entity and intentionally has no persistence, web, or domain dependencies (preserving the
 * layering boundary of the {@code com.aws.carddemo.dto} package).
 *
 * <p>Behavioral parity notes:
 *
 * <ul>
 *   <li><b>AID / PF-key routing</b> (AAP §0.6.5): the {@link Aid} enum mirrors the {@code
 *       CCARD-AID} 88-levels and preserves the EXACT 5-character codes (including the trailing two
 *       spaces on {@code PA1}/{@code PA2}) so SEND/RECEIVE behavior to the raw field value is
 *       faithful. The {@code CCARD-NEXT-PROG}, {@code CCARD-NEXT-MAPSET}, and {@code
 *       CCARD-NEXT-MAP} fields drive the controller-layer transfer that replaces {@code EXEC CICS
 *       XCTL}.
 *   <li><b>REDEFINES handling</b> (AAP §0.6.6): the three identifier fields each expose a paired
 *       "string view" (the {@code X(n)} primary definition, default {@code SPACES}) and a "numeric
 *       view" (the {@code 9(n)} REDEFINES overlay) over a single backing field. The SPACES (unset)
 *       versus numeric distinction is preserved: the numeric getter returns {@code null} when the
 *       backing field is still SPACES rather than silently collapsing it to {@code 0}, because the
 *       COBOL programs branch on whether an id is still SPACES before treating it as numeric.
 * </ul>
 *
 * <p>Only the <em>active</em> (non-commented) copybook fields are implemented. The commented-out
 * legacy items — {@code CCARD-LAST-PROG}, {@code CCARD-RETURN-TO-PROG}, {@code CCARD-RETURN-FLAG},
 * and {@code CCARD-FUNCTION} — are intentionally omitted.
 */
public class CardWorkArea {

  // ---------------------------------------------------------------------------------------------
  // Fixed-width field lengths (the COBOL PIC clause widths). The width is part of the external
  // contract: 3270 screen fields and fixed-width record layouts depend on these exact sizes.
  // ---------------------------------------------------------------------------------------------

  /** Length of {@code CCARD-AID} ({@code PIC X(5)}). */
  public static final int CCARD_AID_LEN = 5;

  /** Length of {@code CCARD-NEXT-PROG} ({@code PIC X(8)}). */
  public static final int CCARD_NEXT_PROG_LEN = 8;

  /** Length of {@code CCARD-NEXT-MAPSET} ({@code PIC X(7)}). */
  public static final int CCARD_NEXT_MAPSET_LEN = 7;

  /** Length of {@code CCARD-NEXT-MAP} ({@code PIC X(7)}). */
  public static final int CCARD_NEXT_MAP_LEN = 7;

  /** Length of {@code CCARD-ERROR-MSG} ({@code PIC X(75)}). */
  public static final int CCARD_ERROR_MSG_LEN = 75;

  /** Length of {@code CCARD-RETURN-MSG} ({@code PIC X(75)}). */
  public static final int CCARD_RETURN_MSG_LEN = 75;

  /** Length of {@code CC-ACCT-ID} ({@code PIC X(11)} / {@code CC-ACCT-ID-N PIC 9(11)}). */
  public static final int CC_ACCT_ID_LEN = 11;

  /** Length of {@code CC-CARD-NUM} ({@code PIC X(16)} / {@code CC-CARD-NUM-N PIC 9(16)}). */
  public static final int CC_CARD_NUM_LEN = 16;

  /** Length of {@code CC-CUST-ID} ({@code PIC X(09)} / {@code CC-CUST-ID-N PIC 9(9)}). */
  public static final int CC_CUST_ID_LEN = 9;

  // ---------------------------------------------------------------------------------------------
  // Active scalar fields (6) — exactly the non-commented elementary items of CC-WORK-AREA.
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code CCARD-AID PIC X(5)} — the raw 5-character attention identifier. This is the canonical
   * storage for the AID; the {@link Aid} enum is a derived view (see {@link #getAid()}). Defaults
   * to five spaces (the "no key pressed" state, for which no {@code CCARD-AID} 88-level is true).
   */
  private String ccardAid = " ".repeat(CCARD_AID_LEN);

  /**
   * {@code CCARD-NEXT-PROG PIC X(8)} — next program to route to (replaces {@code XCTL PROGRAM}).
   */
  private String ccardNextProg;

  /** {@code CCARD-NEXT-MAPSET PIC X(7)} — next BMS mapset (screen group) to send. */
  private String ccardNextMapset;

  /** {@code CCARD-NEXT-MAP PIC X(7)} — next BMS map (screen) to send. */
  private String ccardNextMap;

  /** {@code CCARD-ERROR-MSG PIC X(75)} — error message line presented on the screen. */
  private String ccardErrorMsg;

  /**
   * {@code CCARD-RETURN-MSG PIC X(75)} — return/status message line; see {@link #isReturnMsgOff()}.
   */
  private String ccardReturnMsg;

  // ---------------------------------------------------------------------------------------------
  // REDEFINES trio (3 pairs) — single backing String per id, defaulting to SPACES (VALUE SPACES).
  // Each is exposed as a String view (X(n)) and a numeric view (9(n)) over the same storage.
  // ---------------------------------------------------------------------------------------------

  /**
   * Backing storage for {@code CC-ACCT-ID} / {@code CC-ACCT-ID-N}. Default {@code VALUE SPACES}.
   */
  private String ccAcctId = " ".repeat(CC_ACCT_ID_LEN);

  /**
   * Backing storage for {@code CC-CARD-NUM} / {@code CC-CARD-NUM-N}. Default {@code VALUE SPACES}.
   */
  private String ccCardNum = " ".repeat(CC_CARD_NUM_LEN);

  /**
   * Backing storage for {@code CC-CUST-ID} / {@code CC-CUST-ID-N}. Default {@code VALUE SPACES}.
   */
  private String ccCustId = " ".repeat(CC_CUST_ID_LEN);

  // ---------------------------------------------------------------------------------------------
  // AID / PF-key model (nested enum) — mirrors the CCARD-AID 88-level condition names.
  // ---------------------------------------------------------------------------------------------

  /**
   * Attention identifiers (AID) / PF-keys, mirroring the {@code CCARD-AID} 88-levels of the
   * copybook.
   *
   * <p>Each constant carries the EXACT 5-character COBOL code as stored in the {@code CCARD-AID}
   * field. Note that {@link #PA1} and {@link #PA2} are padded with two trailing spaces ({@code "PA1
   * "}, {@code "PA2 "}) so the raw value round-trips byte-for-byte with the legacy field.
   */
  public enum Aid {
    /** {@code 88 CCARD-AID-ENTER VALUE 'ENTER'}. */
    ENTER("ENTER"),
    /** {@code 88 CCARD-AID-CLEAR VALUE 'CLEAR'}. */
    CLEAR("CLEAR"),
    /** {@code 88 CCARD-AID-PA1 VALUE 'PA1 '} (3 chars + 2 trailing spaces). */
    PA1("PA1  "),
    /** {@code 88 CCARD-AID-PA2 VALUE 'PA2 '} (3 chars + 2 trailing spaces). */
    PA2("PA2  "),
    /** {@code 88 CCARD-AID-PFK01 VALUE 'PFK01'}. */
    PFK01("PFK01"),
    /** {@code 88 CCARD-AID-PFK02 VALUE 'PFK02'}. */
    PFK02("PFK02"),
    /** {@code 88 CCARD-AID-PFK03 VALUE 'PFK03'}. */
    PFK03("PFK03"),
    /** {@code 88 CCARD-AID-PFK04 VALUE 'PFK04'}. */
    PFK04("PFK04"),
    /** {@code 88 CCARD-AID-PFK05 VALUE 'PFK05'}. */
    PFK05("PFK05"),
    /** {@code 88 CCARD-AID-PFK06 VALUE 'PFK06'}. */
    PFK06("PFK06"),
    /** {@code 88 CCARD-AID-PFK07 VALUE 'PFK07'}. */
    PFK07("PFK07"),
    /** {@code 88 CCARD-AID-PFK08 VALUE 'PFK08'}. */
    PFK08("PFK08"),
    /** {@code 88 CCARD-AID-PFK09 VALUE 'PFK09'}. */
    PFK09("PFK09"),
    /** {@code 88 CCARD-AID-PFK10 VALUE 'PFK10'}. */
    PFK10("PFK10"),
    /** {@code 88 CCARD-AID-PFK11 VALUE 'PFK11'}. */
    PFK11("PFK11"),
    /** {@code 88 CCARD-AID-PFK12 VALUE 'PFK12'}. */
    PFK12("PFK12");

    private final String code;

    Aid(String code) {
      this.code = code;
    }

    /**
     * Returns the exact 5-character COBOL AID code for this constant (e.g. {@code "ENTER"}, {@code
     * "PA1 "}). Preserves the trailing-space padding so the raw {@code CCARD-AID} value is
     * faithful.
     *
     * @return the 5-character AID code
     */
    public String getCode() {
      return code;
    }

    /**
     * Resolves a raw 5-character AID code to its {@link Aid} constant.
     *
     * <p>The comparison is exact (the caller should pass the full 5-character code, e.g. {@code
     * "PA1 "} for {@link #PA1}). Unknown or unmatched codes — including {@code null} and the
     * default all-spaces value — return {@code null}, mirroring COBOL's fall-through when no {@code
     * CCARD-AID} 88-level matches. This lets callers retain an unrecognized raw value via {@link
     * CardWorkArea#getCcardAid()} without losing information.
     *
     * @param code the raw 5-character AID code, possibly {@code null}
     * @return the matching {@link Aid}, or {@code null} if none matches
     */
    public static Aid fromCode(String code) {
      if (code == null) {
        return null;
      }
      for (Aid aid : values()) {
        if (aid.code.equals(code)) {
          return aid;
        }
      }
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Internal helpers (fixed-width / numeric conversion mirroring COBOL MOVE semantics).
  // ---------------------------------------------------------------------------------------------

  private static String spaces(int width) {
    return " ".repeat(width);
  }

  /**
   * Normalizes an alphanumeric value to exactly {@code width} characters, mirroring a COBOL
   * alphanumeric {@code MOVE} into a {@code PIC X(width)} field: {@code null} becomes all spaces,
   * shorter values are left-justified and space-padded on the right, and longer values are
   * truncated on the right.
   */
  private static String normalizeFixed(String value, int width) {
    if (value == null) {
      return spaces(width);
    }
    int len = value.length();
    if (len == width) {
      return value;
    }
    if (len > width) {
      return value.substring(0, width);
    }
    return value + spaces(width - len);
  }

  /**
   * Interprets a fixed-width backing string as its {@code PIC 9(n)} numeric overlay. Returns {@code
   * null} when the field is still SPACES (unset) — preserving the COBOL SPACES-vs-numeric
   * distinction rather than returning {@code 0}.
   *
   * @throws NumberFormatException if the field is non-blank but not a valid unsigned integer
   */
  private static Long parseNumeric(String backing) {
    if (backing == null) {
      return null;
    }
    String trimmed = backing.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return Long.parseLong(trimmed);
  }

  /**
   * Formats an unsigned numeric value into its fixed-width {@code PIC 9(width)} representation:
   * right-justified and zero-padded, mirroring a COBOL numeric {@code MOVE}.
   *
   * @throws IllegalArgumentException if {@code value} is negative (the COBOL field is unsigned) or
   *     has more than {@code width} digits (it would not fit the fixed-width field)
   */
  private static String formatNumeric(long value, int width) {
    if (value < 0) {
      throw new IllegalArgumentException(
          "COBOL PIC 9(" + width + ") is unsigned; negative value not allowed: " + value);
    }
    String digits = Long.toString(value);
    if (digits.length() > width) {
      throw new IllegalArgumentException(
          "value " + value + " exceeds " + width + " digit(s) for the fixed-width field");
    }
    return "0".repeat(width - digits.length()) + digits;
  }

  // ---------------------------------------------------------------------------------------------
  // CCARD-AID accessors + derived Aid enum view.
  // ---------------------------------------------------------------------------------------------

  /**
   * Returns the raw 5-character {@code CCARD-AID} value (never {@code null}; defaults to spaces).
   *
   * @return the raw AID string
   */
  public String getCcardAid() {
    return ccardAid;
  }

  /**
   * Sets the raw 5-character {@code CCARD-AID} value. A {@code null} argument resets the field to
   * its SPACES default. Callers should supply the exact 5-character code (or use {@link
   * #setAid(Aid)}).
   *
   * @param ccardAid the raw AID string, or {@code null} to reset to spaces
   */
  public void setCcardAid(String ccardAid) {
    this.ccardAid = (ccardAid == null) ? spaces(CCARD_AID_LEN) : ccardAid;
  }

  /**
   * Returns the {@code CCARD-AID} value as an {@link Aid} enum, or {@code null} if the current raw
   * value matches no known AID (mirroring COBOL's 88-level fall-through). The raw value remains
   * available via {@link #getCcardAid()}.
   *
   * @return the matching {@link Aid}, or {@code null}
   */
  public Aid getAid() {
    return Aid.fromCode(ccardAid);
  }

  /**
   * Sets {@code CCARD-AID} from an {@link Aid} enum, storing its exact 5-character code. A {@code
   * null} argument resets the field to its SPACES default.
   *
   * @param aid the AID to store, or {@code null} to reset to spaces
   */
  public void setAid(Aid aid) {
    this.ccardAid = (aid == null) ? spaces(CCARD_AID_LEN) : aid.getCode();
  }

  // ---- AID 88-level predicates (mirror each CCARD-AID-* condition name) ----

  /**
   * @return {@code true} when the AID equals {@code 'ENTER'} ({@code 88 CCARD-AID-ENTER}).
   */
  public boolean isAidEnter() {
    return Aid.ENTER.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'CLEAR'} ({@code 88 CCARD-AID-CLEAR}).
   */
  public boolean isAidClear() {
    return Aid.CLEAR.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PA1 '} ({@code 88 CCARD-AID-PA1}).
   */
  public boolean isAidPa1() {
    return Aid.PA1.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PA2 '} ({@code 88 CCARD-AID-PA2}).
   */
  public boolean isAidPa2() {
    return Aid.PA2.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK01'} ({@code 88 CCARD-AID-PFK01}).
   */
  public boolean isAidPfk01() {
    return Aid.PFK01.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK02'} ({@code 88 CCARD-AID-PFK02}).
   */
  public boolean isAidPfk02() {
    return Aid.PFK02.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK03'} ({@code 88 CCARD-AID-PFK03}).
   */
  public boolean isAidPfk03() {
    return Aid.PFK03.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK04'} ({@code 88 CCARD-AID-PFK04}).
   */
  public boolean isAidPfk04() {
    return Aid.PFK04.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK05'} ({@code 88 CCARD-AID-PFK05}).
   */
  public boolean isAidPfk05() {
    return Aid.PFK05.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK06'} ({@code 88 CCARD-AID-PFK06}).
   */
  public boolean isAidPfk06() {
    return Aid.PFK06.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK07'} ({@code 88 CCARD-AID-PFK07}).
   */
  public boolean isAidPfk07() {
    return Aid.PFK07.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK08'} ({@code 88 CCARD-AID-PFK08}).
   */
  public boolean isAidPfk08() {
    return Aid.PFK08.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK09'} ({@code 88 CCARD-AID-PFK09}).
   */
  public boolean isAidPfk09() {
    return Aid.PFK09.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK10'} ({@code 88 CCARD-AID-PFK10}).
   */
  public boolean isAidPfk10() {
    return Aid.PFK10.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK11'} ({@code 88 CCARD-AID-PFK11}).
   */
  public boolean isAidPfk11() {
    return Aid.PFK11.getCode().equals(ccardAid);
  }

  /**
   * @return {@code true} when the AID equals {@code 'PFK12'} ({@code 88 CCARD-AID-PFK12}).
   */
  public boolean isAidPfk12() {
    return Aid.PFK12.getCode().equals(ccardAid);
  }

  // ---------------------------------------------------------------------------------------------
  // Scalar navigation / message accessors.
  // ---------------------------------------------------------------------------------------------

  /**
   * @return {@code CCARD-NEXT-PROG} (max {@value #CCARD_NEXT_PROG_LEN} chars), or {@code null}.
   */
  public String getCcardNextProg() {
    return ccardNextProg;
  }

  /**
   * Sets {@code CCARD-NEXT-PROG} ({@code PIC X(8)}).
   *
   * @param ccardNextProg the next program name (up to {@value #CCARD_NEXT_PROG_LEN} chars)
   */
  public void setCcardNextProg(String ccardNextProg) {
    this.ccardNextProg = ccardNextProg;
  }

  /**
   * @return {@code CCARD-NEXT-MAPSET} (max {@value #CCARD_NEXT_MAPSET_LEN} chars), or {@code null}.
   */
  public String getCcardNextMapset() {
    return ccardNextMapset;
  }

  /**
   * Sets {@code CCARD-NEXT-MAPSET} ({@code PIC X(7)}).
   *
   * @param ccardNextMapset the next mapset name (up to {@value #CCARD_NEXT_MAPSET_LEN} chars)
   */
  public void setCcardNextMapset(String ccardNextMapset) {
    this.ccardNextMapset = ccardNextMapset;
  }

  /**
   * @return {@code CCARD-NEXT-MAP} (max {@value #CCARD_NEXT_MAP_LEN} chars), or {@code null}.
   */
  public String getCcardNextMap() {
    return ccardNextMap;
  }

  /**
   * Sets {@code CCARD-NEXT-MAP} ({@code PIC X(7)}).
   *
   * @param ccardNextMap the next map name (up to {@value #CCARD_NEXT_MAP_LEN} chars)
   */
  public void setCcardNextMap(String ccardNextMap) {
    this.ccardNextMap = ccardNextMap;
  }

  /**
   * @return {@code CCARD-ERROR-MSG} (max {@value #CCARD_ERROR_MSG_LEN} chars), or {@code null}.
   */
  public String getCcardErrorMsg() {
    return ccardErrorMsg;
  }

  /**
   * Sets {@code CCARD-ERROR-MSG} ({@code PIC X(75)}).
   *
   * @param ccardErrorMsg the error message (up to {@value #CCARD_ERROR_MSG_LEN} chars)
   */
  public void setCcardErrorMsg(String ccardErrorMsg) {
    this.ccardErrorMsg = ccardErrorMsg;
  }

  /**
   * @return {@code CCARD-RETURN-MSG} (max {@value #CCARD_RETURN_MSG_LEN} chars), or {@code null}.
   */
  public String getCcardReturnMsg() {
    return ccardReturnMsg;
  }

  /**
   * Sets {@code CCARD-RETURN-MSG} ({@code PIC X(75)}).
   *
   * @param ccardReturnMsg the return message (up to {@value #CCARD_RETURN_MSG_LEN} chars)
   */
  public void setCcardReturnMsg(String ccardReturnMsg) {
    this.ccardReturnMsg = ccardReturnMsg;
  }

  /**
   * Mirrors the {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} condition: the return message is
   * considered "off" (unset) when it is {@code null} or blank.
   *
   * @return {@code true} when no return message is set
   */
  public boolean isReturnMsgOff() {
    return ccardReturnMsg == null || ccardReturnMsg.isBlank();
  }

  // ---------------------------------------------------------------------------------------------
  // CC-ACCT-ID  /  CC-ACCT-ID-N  (REDEFINES pair, X(11) / 9(11)).
  // ---------------------------------------------------------------------------------------------

  /**
   * String view of {@code CC-ACCT-ID} ({@code PIC X(11)}). Never {@code null}; defaults to spaces.
   *
   * @return the fixed-width account id string
   */
  public String getCcAcctId() {
    return ccAcctId;
  }

  /**
   * Sets the {@code CC-ACCT-ID} string view, normalized to {@value #CC_ACCT_ID_LEN} characters
   * (left-justified, space-padded, truncated on the right; {@code null} resets to spaces).
   *
   * @param ccAcctId the account id string, or {@code null} to reset to spaces
   */
  public void setCcAcctId(String ccAcctId) {
    this.ccAcctId = normalizeFixed(ccAcctId, CC_ACCT_ID_LEN);
  }

  /**
   * Numeric view of {@code CC-ACCT-ID-N} ({@code PIC 9(11)}).
   *
   * @return the numeric account id, or {@code null} if the field is still SPACES (unset)
   * @throws NumberFormatException if the field is non-blank but not a valid unsigned integer
   */
  public Long getCcAcctIdN() {
    return parseNumeric(ccAcctId);
  }

  /**
   * Sets {@code CC-ACCT-ID-N} ({@code PIC 9(11)}), storing the value right-justified and
   * zero-padded to {@value #CC_ACCT_ID_LEN} digits.
   *
   * @param value the unsigned account id
   * @throws IllegalArgumentException if {@code value} is negative or has more than {@value
   *     #CC_ACCT_ID_LEN} digits
   */
  public void setCcAcctIdN(long value) {
    this.ccAcctId = formatNumeric(value, CC_ACCT_ID_LEN);
  }

  /**
   * @return {@code true} when {@code CC-ACCT-ID} is still SPACES (unset), as several COBOL
   *     paragraphs test before treating the id as numeric
   */
  public boolean isCcAcctIdSpaces() {
    return ccAcctId == null || ccAcctId.isBlank();
  }

  // ---------------------------------------------------------------------------------------------
  // CC-CARD-NUM  /  CC-CARD-NUM-N  (REDEFINES pair, X(16) / 9(16)).
  // ---------------------------------------------------------------------------------------------

  /**
   * String view of {@code CC-CARD-NUM} ({@code PIC X(16)}). Never {@code null}; defaults to spaces.
   *
   * @return the fixed-width card number string
   */
  public String getCcCardNum() {
    return ccCardNum;
  }

  /**
   * Sets the {@code CC-CARD-NUM} string view, normalized to {@value #CC_CARD_NUM_LEN} characters
   * (left-justified, space-padded, truncated on the right; {@code null} resets to spaces).
   *
   * @param ccCardNum the card number string, or {@code null} to reset to spaces
   */
  public void setCcCardNum(String ccCardNum) {
    this.ccCardNum = normalizeFixed(ccCardNum, CC_CARD_NUM_LEN);
  }

  /**
   * Numeric view of {@code CC-CARD-NUM-N} ({@code PIC 9(16)}).
   *
   * @return the numeric card number, or {@code null} if the field is still SPACES (unset)
   * @throws NumberFormatException if the field is non-blank but not a valid unsigned integer
   */
  public Long getCcCardNumN() {
    return parseNumeric(ccCardNum);
  }

  /**
   * Sets {@code CC-CARD-NUM-N} ({@code PIC 9(16)}), storing the value right-justified and
   * zero-padded to {@value #CC_CARD_NUM_LEN} digits.
   *
   * @param value the unsigned card number
   * @throws IllegalArgumentException if {@code value} is negative or has more than {@value
   *     #CC_CARD_NUM_LEN} digits
   */
  public void setCcCardNumN(long value) {
    this.ccCardNum = formatNumeric(value, CC_CARD_NUM_LEN);
  }

  /**
   * @return {@code true} when {@code CC-CARD-NUM} is still SPACES (unset), as several COBOL
   *     paragraphs test before treating the number as numeric
   */
  public boolean isCcCardNumSpaces() {
    return ccCardNum == null || ccCardNum.isBlank();
  }

  // ---------------------------------------------------------------------------------------------
  // CC-CUST-ID  /  CC-CUST-ID-N  (REDEFINES pair, X(9) / 9(9)).
  // ---------------------------------------------------------------------------------------------

  /**
   * String view of {@code CC-CUST-ID} ({@code PIC X(09)}). Never {@code null}; defaults to spaces.
   *
   * @return the fixed-width customer id string
   */
  public String getCcCustId() {
    return ccCustId;
  }

  /**
   * Sets the {@code CC-CUST-ID} string view, normalized to {@value #CC_CUST_ID_LEN} characters
   * (left-justified, space-padded, truncated on the right; {@code null} resets to spaces).
   *
   * @param ccCustId the customer id string, or {@code null} to reset to spaces
   */
  public void setCcCustId(String ccCustId) {
    this.ccCustId = normalizeFixed(ccCustId, CC_CUST_ID_LEN);
  }

  /**
   * Numeric view of {@code CC-CUST-ID-N} ({@code PIC 9(9)}).
   *
   * @return the numeric customer id, or {@code null} if the field is still SPACES (unset)
   * @throws NumberFormatException if the field is non-blank but not a valid unsigned integer
   */
  public Long getCcCustIdN() {
    return parseNumeric(ccCustId);
  }

  /**
   * Sets {@code CC-CUST-ID-N} ({@code PIC 9(9)}), storing the value right-justified and zero-padded
   * to {@value #CC_CUST_ID_LEN} digits.
   *
   * @param value the unsigned customer id
   * @throws IllegalArgumentException if {@code value} is negative or has more than {@value
   *     #CC_CUST_ID_LEN} digits
   */
  public void setCcCustIdN(long value) {
    this.ccCustId = formatNumeric(value, CC_CUST_ID_LEN);
  }

  /**
   * @return {@code true} when {@code CC-CUST-ID} is still SPACES (unset), as several COBOL
   *     paragraphs test before treating the id as numeric
   */
  public boolean isCcCustIdSpaces() {
    return ccCustId == null || ccCustId.isBlank();
  }

  // ---------------------------------------------------------------------------------------------
  // Value semantics.
  // ---------------------------------------------------------------------------------------------

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CardWorkArea that)) {
      return false;
    }
    return Objects.equals(ccardAid, that.ccardAid)
        && Objects.equals(ccardNextProg, that.ccardNextProg)
        && Objects.equals(ccardNextMapset, that.ccardNextMapset)
        && Objects.equals(ccardNextMap, that.ccardNextMap)
        && Objects.equals(ccardErrorMsg, that.ccardErrorMsg)
        && Objects.equals(ccardReturnMsg, that.ccardReturnMsg)
        && Objects.equals(ccAcctId, that.ccAcctId)
        && Objects.equals(ccCardNum, that.ccCardNum)
        && Objects.equals(ccCustId, that.ccCustId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        ccardAid,
        ccardNextProg,
        ccardNextMapset,
        ccardNextMap,
        ccardErrorMsg,
        ccardReturnMsg,
        ccAcctId,
        ccCardNum,
        ccCustId);
  }

  @Override
  public String toString() {
    return "CardWorkArea{ccardAid='"
        + ccardAid
        + "', ccardNextProg='"
        + ccardNextProg
        + "', ccardNextMapset='"
        + ccardNextMapset
        + "', ccardNextMap='"
        + ccardNextMap
        + "', ccardErrorMsg='"
        + ccardErrorMsg
        + "', ccardReturnMsg='"
        + ccardReturnMsg
        + "', ccAcctId='"
        + ccAcctId
        + "', ccCardNum='"
        + ccCardNum
        + "', ccCustId='"
        + ccCustId
        + "'}";
  }
}
