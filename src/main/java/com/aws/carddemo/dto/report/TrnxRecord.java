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
package com.aws.carddemo.dto.report;

import java.math.BigDecimal;

/**
 * Altered transaction record used in statement reporting, migrated from the COBOL {@code 01}-level
 * group item {@code TRNX-RECORD} in copybook {@code legacy/app/cpy/COSTM01.CPY} (source-branch
 * {@code app/cpy/COSTM01.CPY}, lines 20-36). One Java type per copybook {@code 01}-level (AAP
 * §0.4.1).
 *
 * <p>In the legacy z/OS application this is the <em>enriched</em> (altered) transaction layout that
 * carries the card number, transaction classification, full merchant detail, and dual timestamps
 * required to render account statements. It is the record exchanged by the statement-generation
 * flow {@code CBSTM03A} → {@code CBSTM03B} (modernized as the batch statement service and its
 * injected {@code FileIoService}). This is the only report-package DTO sourced from {@code
 * COSTM01.CPY}; the remaining statement/report line types originate from {@code CVTRA07Y.cpy}.
 *
 * <p>The original COBOL record is organized into two sub-groups, preserved here only as
 * documentation on each field (this flattened POJO models a single {@code 01}-level per the folder
 * requirements):
 *
 * <ul>
 *   <li>{@code 05 TRNX-KEY} — the lookup key: {@link #trnxCardNum} + {@link #trnxId}.
 *   <li>{@code 05 TRNX-REST} — the remaining classification, amount, merchant, and timestamp data.
 * </ul>
 *
 * <p>Decimal fidelity (AAP §0.6.1): {@link #trnxAmt} maps the signed fixed-point COBOL field {@code
 * TRNX-AMT PIC S9(09)V99} and is therefore a {@link BigDecimal} with an implied scale of {@code 2}
 * — <strong>never</strong> {@code float}/{@code double}. The raw value is stored as-is at scale 2;
 * this DTO performs no arithmetic and no edited rendering. Statement formatting (the COBOL edited
 * PICs such as {@code -ZZZ,ZZZ,ZZZ.ZZ}) is the responsibility of {@code
 * com.aws.carddemo.util.NumberFormatter} in the service layer, not of this view contract.
 *
 * <p>Fixed-width record contract (golden-file parity): every field preserves its exact COBOL PIC
 * width, including the trailing {@code FILLER PIC X(20)}. The byte offsets below assume the default
 * {@code USAGE IS DISPLAY} (zoned decimal) representation in which a signed {@code S9(09)V99} field
 * occupies 11 digit positions (the implied {@code V} contributes no byte and the sign is
 * overpunched on the trailing digit). The widths are exposed as {@code public static final int}
 * constants so file-I/O services and golden-file fixtures can reference them directly:
 *
 * <pre>
 *   offset  len  field                COBOL PIC
 *   ------  ---  -------------------  ------------
 *        0   16  trnxCardNum          X(16)        [TRNX-KEY]
 *       16   16  trnxId               X(16)        [TRNX-KEY]
 *       32    2  trnxTypeCd           X(02)        [TRNX-REST]
 *       34    4  trnxCatCd            9(04)        [TRNX-REST]
 *       38   10  trnxSource           X(10)        [TRNX-REST]
 *       48  100  trnxDesc             X(100)       [TRNX-REST]
 *      148   11  trnxAmt              S9(09)V99    [TRNX-REST]
 *      159    9  trnxMerchantId       9(09)        [TRNX-REST]
 *      168   50  trnxMerchantName     X(50)        [TRNX-REST]
 *      218   50  trnxMerchantCity     X(50)        [TRNX-REST]
 *      268   10  trnxMerchantZip      X(10)        [TRNX-REST]
 *      278   26  trnxOrigTs           X(26)        [TRNX-REST]
 *      304   26  trnxProcTs           X(26)        [TRNX-REST]
 *      330   20  (FILLER)             X(20)        [TRNX-REST]
 *   ------  ---
 *      350  total record width (USAGE DISPLAY)
 * </pre>
 *
 * <p>This is a plain, framework-light data-transfer object. It is intentionally
 * <strong>not</strong> a JPA entity and has no {@code jakarta.persistence} or {@code
 * com.aws.carddemo.domain} dependency, preserving the layering boundary of the {@code
 * com.aws.carddemo.dto.report} package; entity ↔ DTO mapping is the responsibility of the
 * service/mapper layer.
 */
public class TrnxRecord {

  // -----------------------------------------------------------------------------------------------
  // Fixed-width field widths (the COBOL PIC clause sizes). The width is part of the external,
  // fixed-record contract: file-I/O parsing and golden-file fixtures depend on these exact sizes.
  // For the X(n) and unsigned 9(n) DISPLAY fields the constant is the character / digit count; for
  // the signed S9(09)V99 amount the digit count and scale are exposed separately.
  // -----------------------------------------------------------------------------------------------

  /** Width of {@code TRNX-CARD-NUM} ({@code PIC X(16)}, group {@code TRNX-KEY}). */
  public static final int TRNX_CARD_NUM_LEN = 16;

  /** Width of {@code TRNX-ID} ({@code PIC X(16)}, group {@code TRNX-KEY}). */
  public static final int TRNX_ID_LEN = 16;

  /** Width of {@code TRNX-TYPE-CD} ({@code PIC X(02)}, group {@code TRNX-REST}). */
  public static final int TRNX_TYPE_CD_LEN = 2;

  /** Digit count of {@code TRNX-CAT-CD} ({@code PIC 9(04)}, group {@code TRNX-REST}). */
  public static final int TRNX_CAT_CD_LEN = 4;

  /** Width of {@code TRNX-SOURCE} ({@code PIC X(10)}, group {@code TRNX-REST}). */
  public static final int TRNX_SOURCE_LEN = 10;

  /** Width of {@code TRNX-DESC} ({@code PIC X(100)}, group {@code TRNX-REST}). */
  public static final int TRNX_DESC_LEN = 100;

  /** Integer-digit count of {@code TRNX-AMT} ({@code PIC S9(09)V99}, group {@code TRNX-REST}). */
  public static final int TRNX_AMT_INTEGER_DIGITS = 9;

  /** Implied decimal scale of {@code TRNX-AMT} ({@code PIC S9(09)V99}); fixed at {@code 2}. */
  public static final int TRNX_AMT_SCALE = 2;

  /** Digit count of {@code TRNX-MERCHANT-ID} ({@code PIC 9(09)}, group {@code TRNX-REST}). */
  public static final int TRNX_MERCHANT_ID_LEN = 9;

  /** Width of {@code TRNX-MERCHANT-NAME} ({@code PIC X(50)}, group {@code TRNX-REST}). */
  public static final int TRNX_MERCHANT_NAME_LEN = 50;

  /** Width of {@code TRNX-MERCHANT-CITY} ({@code PIC X(50)}, group {@code TRNX-REST}). */
  public static final int TRNX_MERCHANT_CITY_LEN = 50;

  /** Width of {@code TRNX-MERCHANT-ZIP} ({@code PIC X(10)}, group {@code TRNX-REST}). */
  public static final int TRNX_MERCHANT_ZIP_LEN = 10;

  /** Width of {@code TRNX-ORIG-TS} ({@code PIC X(26)}, group {@code TRNX-REST}). */
  public static final int TRNX_ORIG_TS_LEN = 26;

  /** Width of {@code TRNX-PROC-TS} ({@code PIC X(26)}, group {@code TRNX-REST}). */
  public static final int TRNX_PROC_TS_LEN = 26;

  /**
   * Width of the trailing {@code FILLER} ({@code PIC X(20)}, group {@code TRNX-REST}).
   *
   * <p>The filler carries no business data and is deliberately <em>not</em> exposed as a data
   * field; it is retained here only to preserve the fixed-width record contract (total record
   * width) for golden-file parity fixtures.
   */
  public static final int FILLER_LEN = 20;

  // -----------------------------------------------------------------------------------------------
  // Data fields (13) — the elementary, non-FILLER items of TRNX-RECORD, in copybook order.
  // -----------------------------------------------------------------------------------------------

  /**
   * {@code TRNX-CARD-NUM PIC X(16)} (group {@code TRNX-KEY}). The 16-character card number that,
   * together with {@link #trnxId}, forms the original VSAM {@code TRNX-KEY}.
   */
  private String trnxCardNum;

  /**
   * {@code TRNX-ID PIC X(16)} (group {@code TRNX-KEY}). The 16-character transaction identifier;
   * the second half of the {@code TRNX-KEY}.
   */
  private String trnxId;

  /** {@code TRNX-TYPE-CD PIC X(02)} (group {@code TRNX-REST}). Transaction type code. */
  private String trnxTypeCd;

  /**
   * {@code TRNX-CAT-CD PIC 9(04)} (group {@code TRNX-REST}). Transaction category code, a 4-digit
   * numeric value modeled as {@link Integer}. Zero-padded rendering for statements is performed by
   * {@code com.aws.carddemo.util.NumberFormatter#formatCategoryCode(int)}, not here.
   */
  private Integer trnxCatCd;

  /** {@code TRNX-SOURCE PIC X(10)} (group {@code TRNX-REST}). Originating transaction source. */
  private String trnxSource;

  /** {@code TRNX-DESC PIC X(100)} (group {@code TRNX-REST}). Free-text transaction description. */
  private String trnxDesc;

  /**
   * {@code TRNX-AMT PIC S9(09)V99} (group {@code TRNX-REST}). Signed fixed-point transaction amount
   * with an implied scale of {@value #TRNX_AMT_SCALE}. Stored as a raw {@link BigDecimal} (AAP
   * §0.6.1) — <strong>never</strong> {@code float}/{@code double}. No rounding, scaling, or edited
   * formatting is performed in this DTO; the statement formatter renders the COBOL-faithful edited
   * string from this raw value.
   */
  private BigDecimal trnxAmt;

  /**
   * {@code TRNX-MERCHANT-ID PIC 9(09)} (group {@code TRNX-REST}). Up-to-9-digit numeric merchant
   * identifier modeled as {@link Long} to match the {@code Transaction.tranMerchantId} domain
   * convention.
   */
  private Long trnxMerchantId;

  /** {@code TRNX-MERCHANT-NAME PIC X(50)} (group {@code TRNX-REST}). Merchant name. */
  private String trnxMerchantName;

  /** {@code TRNX-MERCHANT-CITY PIC X(50)} (group {@code TRNX-REST}). Merchant city. */
  private String trnxMerchantCity;

  /** {@code TRNX-MERCHANT-ZIP PIC X(10)} (group {@code TRNX-REST}). Merchant postal/ZIP code. */
  private String trnxMerchantZip;

  /**
   * {@code TRNX-ORIG-TS PIC X(26)} (group {@code TRNX-REST}). Original transaction timestamp, kept
   * as the raw 26-character text exactly as stored on the record (no date parsing in this DTO).
   */
  private String trnxOrigTs;

  /**
   * {@code TRNX-PROC-TS PIC X(26)} (group {@code TRNX-REST}). Processing timestamp, kept as the raw
   * 26-character text exactly as stored on the record (no date parsing in this DTO).
   */
  private String trnxProcTs;

  /** Creates an empty altered-transaction record; all data fields are initially {@code null}. */
  public TrnxRecord() {
    // Intentionally empty: no-args constructor for framework instantiation and record mapping.
  }

  // -----------------------------------------------------------------------------------------------
  // Accessors (one getter / setter pair per data field, in copybook order).
  // -----------------------------------------------------------------------------------------------

  /**
   * Returns {@code TRNX-CARD-NUM} ({@code PIC X(16)}).
   *
   * @return the card number, or {@code null} if unset
   */
  public String getTrnxCardNum() {
    return trnxCardNum;
  }

  /**
   * Sets {@code TRNX-CARD-NUM} ({@code PIC X(16)}).
   *
   * @param trnxCardNum the card number to store
   */
  public void setTrnxCardNum(String trnxCardNum) {
    this.trnxCardNum = trnxCardNum;
  }

  /**
   * Returns {@code TRNX-ID} ({@code PIC X(16)}).
   *
   * @return the transaction id, or {@code null} if unset
   */
  public String getTrnxId() {
    return trnxId;
  }

  /**
   * Sets {@code TRNX-ID} ({@code PIC X(16)}).
   *
   * @param trnxId the transaction id to store
   */
  public void setTrnxId(String trnxId) {
    this.trnxId = trnxId;
  }

  /**
   * Returns {@code TRNX-TYPE-CD} ({@code PIC X(02)}).
   *
   * @return the transaction type code, or {@code null} if unset
   */
  public String getTrnxTypeCd() {
    return trnxTypeCd;
  }

  /**
   * Sets {@code TRNX-TYPE-CD} ({@code PIC X(02)}).
   *
   * @param trnxTypeCd the transaction type code to store
   */
  public void setTrnxTypeCd(String trnxTypeCd) {
    this.trnxTypeCd = trnxTypeCd;
  }

  /**
   * Returns {@code TRNX-CAT-CD} ({@code PIC 9(04)}) as a numeric value.
   *
   * @return the transaction category code, or {@code null} if unset
   */
  public Integer getTrnxCatCd() {
    return trnxCatCd;
  }

  /**
   * Sets {@code TRNX-CAT-CD} ({@code PIC 9(04)}).
   *
   * @param trnxCatCd the transaction category code to store
   */
  public void setTrnxCatCd(Integer trnxCatCd) {
    this.trnxCatCd = trnxCatCd;
  }

  /**
   * Returns {@code TRNX-SOURCE} ({@code PIC X(10)}).
   *
   * @return the transaction source, or {@code null} if unset
   */
  public String getTrnxSource() {
    return trnxSource;
  }

  /**
   * Sets {@code TRNX-SOURCE} ({@code PIC X(10)}).
   *
   * @param trnxSource the transaction source to store
   */
  public void setTrnxSource(String trnxSource) {
    this.trnxSource = trnxSource;
  }

  /**
   * Returns {@code TRNX-DESC} ({@code PIC X(100)}).
   *
   * @return the transaction description, or {@code null} if unset
   */
  public String getTrnxDesc() {
    return trnxDesc;
  }

  /**
   * Sets {@code TRNX-DESC} ({@code PIC X(100)}).
   *
   * @param trnxDesc the transaction description to store
   */
  public void setTrnxDesc(String trnxDesc) {
    this.trnxDesc = trnxDesc;
  }

  /**
   * Returns {@code TRNX-AMT} ({@code PIC S9(09)V99}) as a raw {@link BigDecimal} at scale {@value
   * #TRNX_AMT_SCALE}.
   *
   * @return the signed transaction amount, or {@code null} if unset
   */
  public BigDecimal getTrnxAmt() {
    return trnxAmt;
  }

  /**
   * Sets {@code TRNX-AMT} ({@code PIC S9(09)V99}). The value is stored exactly as supplied (no
   * rounding or rescaling); callers are expected to provide the raw amount at scale {@value
   * #TRNX_AMT_SCALE} (AAP §0.6.1).
   *
   * @param trnxAmt the signed transaction amount to store
   */
  public void setTrnxAmt(BigDecimal trnxAmt) {
    this.trnxAmt = trnxAmt;
  }

  /**
   * Returns {@code TRNX-MERCHANT-ID} ({@code PIC 9(09)}) as a numeric value.
   *
   * @return the merchant id, or {@code null} if unset
   */
  public Long getTrnxMerchantId() {
    return trnxMerchantId;
  }

  /**
   * Sets {@code TRNX-MERCHANT-ID} ({@code PIC 9(09)}).
   *
   * @param trnxMerchantId the merchant id to store
   */
  public void setTrnxMerchantId(Long trnxMerchantId) {
    this.trnxMerchantId = trnxMerchantId;
  }

  /**
   * Returns {@code TRNX-MERCHANT-NAME} ({@code PIC X(50)}).
   *
   * @return the merchant name, or {@code null} if unset
   */
  public String getTrnxMerchantName() {
    return trnxMerchantName;
  }

  /**
   * Sets {@code TRNX-MERCHANT-NAME} ({@code PIC X(50)}).
   *
   * @param trnxMerchantName the merchant name to store
   */
  public void setTrnxMerchantName(String trnxMerchantName) {
    this.trnxMerchantName = trnxMerchantName;
  }

  /**
   * Returns {@code TRNX-MERCHANT-CITY} ({@code PIC X(50)}).
   *
   * @return the merchant city, or {@code null} if unset
   */
  public String getTrnxMerchantCity() {
    return trnxMerchantCity;
  }

  /**
   * Sets {@code TRNX-MERCHANT-CITY} ({@code PIC X(50)}).
   *
   * @param trnxMerchantCity the merchant city to store
   */
  public void setTrnxMerchantCity(String trnxMerchantCity) {
    this.trnxMerchantCity = trnxMerchantCity;
  }

  /**
   * Returns {@code TRNX-MERCHANT-ZIP} ({@code PIC X(10)}).
   *
   * @return the merchant ZIP/postal code, or {@code null} if unset
   */
  public String getTrnxMerchantZip() {
    return trnxMerchantZip;
  }

  /**
   * Sets {@code TRNX-MERCHANT-ZIP} ({@code PIC X(10)}).
   *
   * @param trnxMerchantZip the merchant ZIP/postal code to store
   */
  public void setTrnxMerchantZip(String trnxMerchantZip) {
    this.trnxMerchantZip = trnxMerchantZip;
  }

  /**
   * Returns {@code TRNX-ORIG-TS} ({@code PIC X(26)}) as raw timestamp text.
   *
   * @return the original timestamp text, or {@code null} if unset
   */
  public String getTrnxOrigTs() {
    return trnxOrigTs;
  }

  /**
   * Sets {@code TRNX-ORIG-TS} ({@code PIC X(26)}).
   *
   * @param trnxOrigTs the original timestamp text to store
   */
  public void setTrnxOrigTs(String trnxOrigTs) {
    this.trnxOrigTs = trnxOrigTs;
  }

  /**
   * Returns {@code TRNX-PROC-TS} ({@code PIC X(26)}) as raw timestamp text.
   *
   * @return the processing timestamp text, or {@code null} if unset
   */
  public String getTrnxProcTs() {
    return trnxProcTs;
  }

  /**
   * Sets {@code TRNX-PROC-TS} ({@code PIC X(26)}).
   *
   * @param trnxProcTs the processing timestamp text to store
   */
  public void setTrnxProcTs(String trnxProcTs) {
    this.trnxProcTs = trnxProcTs;
  }

  /**
   * Returns a diagnostic representation of this altered-transaction record. The record carries no
   * credential data, so all fields are included.
   *
   * @return a string describing every data field of the record
   */
  @Override
  public String toString() {
    return "TrnxRecord{"
        + "trnxCardNum="
        + trnxCardNum
        + ", trnxId="
        + trnxId
        + ", trnxTypeCd="
        + trnxTypeCd
        + ", trnxCatCd="
        + trnxCatCd
        + ", trnxSource="
        + trnxSource
        + ", trnxDesc="
        + trnxDesc
        + ", trnxAmt="
        + trnxAmt
        + ", trnxMerchantId="
        + trnxMerchantId
        + ", trnxMerchantName="
        + trnxMerchantName
        + ", trnxMerchantCity="
        + trnxMerchantCity
        + ", trnxMerchantZip="
        + trnxMerchantZip
        + ", trnxOrigTs="
        + trnxOrigTs
        + ", trnxProcTs="
        + trnxProcTs
        + '}';
  }
}
