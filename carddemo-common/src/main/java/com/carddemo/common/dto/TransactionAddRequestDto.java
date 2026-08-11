/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.dto;

import com.carddemo.common.validation.SingleByteText;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * :purpose: Inbound request DTO for the COTRN02 add-transaction screen (CICS
 *  transaction ``CT02``, legacy program ``COTRN02C``). Carries the editable
 *  transaction fields entered on the 3270 map (``ADD-TRANSACTION``) that populate a
 *  new ``TRAN-RECORD`` (copybook ``CVTRA05Y``). The transaction id is intentionally
 *  absent: it is assigned by the persistence layer (identity/sequence), not by the
 *  client. Field widths preserve the legacy contract and the amount is a scale-2
 *  {@link BigDecimal}; numeric parsing and business validation belong to the
 *  transaction service, not this carrier.
 * :output: A mutable request carrier with the type/category codes, source,
 *  description, amount, card number, merchant id/name/city/zip, and the
 *  origination/processing timestamps.
 */
/*
 * Boundary encoding guard. The fields of this request are persisted and then
 * rendered into the 350-byte TRANSACT record (CVTRA05Y), the combined transaction file and the statement files,
 * every one of which is a BYTE-width contract a downstream consumer parses by
 * offset. Text that needs more than one byte per character therefore cannot
 * survive that rendering intact - it either loses the character or shifts every
 * following field - so it is refused here, at the only point where the operator
 * can still be told which field to correct.
 */
@SingleByteText
public class TransactionAddRequestDto {

    /** :purpose: Transaction type code (COTRN02 ``TTYPCDI`` / ``TRAN-TYPE-CD`` PIC X(02)). */
    @Size(max = 2, message = "Type CD must be at most 2 characters")
    private String tranTypeCd;

    /** :purpose: Transaction category code (COTRN02 ``TCATCDI`` / ``TRAN-CAT-CD`` PIC 9(04)). */
    private Integer tranCatCd;

    /** :purpose: Origination source (COTRN02 ``TRNSRCI`` / ``TRAN-SOURCE`` PIC X(10)). */
    @Size(max = 10, message = "Source must be at most 10 characters")
    private String tranSource;

    /** :purpose: Transaction description (COTRN02 ``TDESCI`` / ``TRAN-DESC`` PIC X(100)). */
    @Size(max = 100, message = "Description must be at most 100 characters")
    private String tranDesc;

    /** :purpose: Monetary amount (COTRN02 ``TRNAMTI`` / ``TRAN-AMT`` PIC S9(09)V99 -> NUMERIC(11,2)). */
    private BigDecimal tranAmt;

    /**
     * :purpose: Card number (COTRN02 ``CARDNINI`` / ``TRAN-CARD-NUM`` PIC X(16)).
     * :note: Carries NO bean-validation constraint, for the reason given on ``acctId``.
     *  The constraint that stood here answered an over-width value with ``'Card number if
     *  supplied must be a 16 digit number'`` — the 88-level ``SEARCHED-CARD-NOT-NUMERIC``,
     *  which ``COCRDSLC`` L149 and ``COCRDUPC`` L194 declare and which appears nowhere
     *  else in either program, so neither ever ``SET``s it. ``COTRN02C``'s reachable card
     *  edit publishes ``'Card Number must be Numeric...'``, and the service applies it to
     *  every value of the wrong width.
     */
    private String tranCardNum;

    /** :purpose: Merchant id (COTRN02 ``MIDI`` / ``TRAN-MERCHANT-ID`` PIC 9(09)). */
    private Long tranMerchantId;

    /** :purpose: Merchant name (COTRN02 ``MNAMEI`` / ``TRAN-MERCHANT-NAME`` PIC X(50)). */
    @Size(max = 50, message = "Merchant Name must be at most 50 characters")
    private String tranMerchantName;

    /** :purpose: Merchant city (COTRN02 ``MCITYI`` / ``TRAN-MERCHANT-CITY`` PIC X(50)). */
    @Size(max = 50, message = "Merchant City must be at most 50 characters")
    private String tranMerchantCity;

    /** :purpose: Merchant postal code (COTRN02 ``MZIPI`` / ``TRAN-MERCHANT-ZIP`` PIC X(10)). */
    @Size(max = 10, message = "Merchant Zip must be at most 10 characters")
    private String tranMerchantZip;

    /**
     * :purpose: Origination date as the ``COTRN02`` map field carries it: ten characters
     *  ``YYYY-MM-DD`` (``TORIGDT`` is ``DFHMDF LENGTH=10``), optionally space-filled to the
     *  stored width because ``COTRN02C`` L487-L488 redisplays the stored value in that same
     *  ten-character field. The service normalizes it to the canonical stored 26-character
     *  form (``com.carddemo.common.util.LegacyTimestamp``); a value carrying anything else
     *  after the date is refused.
     */
    @Size(max = 26, message = "Orig Date should be in format YYYY-MM-DD")
    private String tranOrigTs;

    /**
     * :purpose: Processing date as the ``COTRN02`` map field carries it: ten characters
     *  ``YYYY-MM-DD`` (``TPROCDT`` is ``DFHMDF LENGTH=10``), optionally space-filled to the
     *  stored width. Normalized to the canonical stored 26-character form exactly as
     *  {@link #getTranOrigTs()} is.
     */
    @Size(max = 26, message = "Proc Date should be in format YYYY-MM-DD")
    private String tranProcTs;

    /**
     * :purpose: Account id key input (COTRN02 ``ACTIDINI``). When supplied it takes
     *  priority over ``tranCardNum`` and drives the account-to-card cross-reference
     *  lookup that resolves the card number; it is validated but not persisted.
     * :note: Carries NO bean-validation constraint on purpose. The width rule belongs to
     *  ``COTRN02C`` L197-L201, which tests ``IF ACTIDINI IS NOT NUMERIC`` on an
     *  eleven-column field and publishes ``'Account ID must be Numeric...'`` — one
     *  line-23 message with the cursor on the field. A ``@Size`` here answered instead
     *  with a ``fieldErrors`` entry carrying ``'Account number must be a non zero 11
     *  digit number'``, an 88-level that ``COACTVWC`` and ``COACTUPC`` declare and
     *  neither ever ``SET``s, so no legacy screen can emit it. The service applies the
     *  reachable edit for every caller, including one that supplies an over-width value.
     */
    private String acctId;

    /**
     * :purpose: Add confirmation flag (COTRN02 ``CONFIRMI``). ``Y``/``y`` confirms the
     *  add; ``N``/``n``/blank/absent requests confirmation; any other value is invalid.
     */
    @Size(max = 1, message = "Invalid value. Valid values are (Y/N)...")
    private String confirm;

    /**
     * :purpose: Create an empty request. Required for JSON (Jackson) deserialization.
     */
    public TransactionAddRequestDto() {
    }

    /**
     * :purpose: Return the transaction type code.
     * :output: the ``tranTypeCd`` value.
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * :purpose: Set the transaction type code.
     * :param tranTypeCd: the ``tranTypeCd`` value.
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * :purpose: Return the transaction category code.
     * :output: the ``tranCatCd`` value.
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * :purpose: Set the transaction category code.
     * :param tranCatCd: the ``tranCatCd`` value.
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * :purpose: Return the origination source.
     * :output: the ``tranSource`` value.
     */
    public String getTranSource() {
        return tranSource;
    }

    /**
     * :purpose: Set the origination source.
     * :param tranSource: the ``tranSource`` value.
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * :purpose: Return the transaction description.
     * :output: the ``tranDesc`` value.
     */
    public String getTranDesc() {
        return tranDesc;
    }

    /**
     * :purpose: Set the transaction description.
     * :param tranDesc: the ``tranDesc`` value.
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * :purpose: Return the monetary amount.
     * :output: the ``tranAmt`` value as a {@link BigDecimal}.
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * :purpose: Set the monetary amount.
     * :param tranAmt: the ``tranAmt`` value as a {@link BigDecimal}.
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * :purpose: Return the card number.
     * :output: the ``tranCardNum`` value.
     */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /**
     * :purpose: Set the card number.
     * :param tranCardNum: the ``tranCardNum`` value.
     */
    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    /**
     * :purpose: Return the merchant id.
     * :output: the ``tranMerchantId`` value.
     */
    public Long getTranMerchantId() {
        return tranMerchantId;
    }

    /**
     * :purpose: Set the merchant id.
     * :param tranMerchantId: the ``tranMerchantId`` value.
     */
    public void setTranMerchantId(Long tranMerchantId) {
        this.tranMerchantId = tranMerchantId;
    }

    /**
     * :purpose: Return the merchant name.
     * :output: the ``tranMerchantName`` value.
     */
    public String getTranMerchantName() {
        return tranMerchantName;
    }

    /**
     * :purpose: Set the merchant name.
     * :param tranMerchantName: the ``tranMerchantName`` value.
     */
    public void setTranMerchantName(String tranMerchantName) {
        this.tranMerchantName = tranMerchantName;
    }

    /**
     * :purpose: Return the merchant city.
     * :output: the ``tranMerchantCity`` value.
     */
    public String getTranMerchantCity() {
        return tranMerchantCity;
    }

    /**
     * :purpose: Set the merchant city.
     * :param tranMerchantCity: the ``tranMerchantCity`` value.
     */
    public void setTranMerchantCity(String tranMerchantCity) {
        this.tranMerchantCity = tranMerchantCity;
    }

    /**
     * :purpose: Return the merchant postal code.
     * :output: the ``tranMerchantZip`` value.
     */
    public String getTranMerchantZip() {
        return tranMerchantZip;
    }

    /**
     * :purpose: Set the merchant postal code.
     * :param tranMerchantZip: the ``tranMerchantZip`` value.
     */
    public void setTranMerchantZip(String tranMerchantZip) {
        this.tranMerchantZip = tranMerchantZip;
    }

    /**
     * :purpose: Return the origination date.
     * :output: the ten-character ``YYYY-MM-DD`` map field value, optionally space-filled.
     */
    public String getTranOrigTs() {
        return tranOrigTs;
    }

    /**
     * :purpose: Set the origination date.
     * :param tranOrigTs: the ten-character ``YYYY-MM-DD`` map field value, optionally
     *  space-filled to the stored width.
     */
    public void setTranOrigTs(String tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    /**
     * :purpose: Return the processing date.
     * :output: the ten-character ``YYYY-MM-DD`` map field value, optionally space-filled.
     */
    public String getTranProcTs() {
        return tranProcTs;
    }

    /**
     * :purpose: Set the processing date.
     * :param tranProcTs: the ten-character ``YYYY-MM-DD`` map field value, optionally
     *  space-filled to the stored width.
     */
    public void setTranProcTs(String tranProcTs) {
        this.tranProcTs = tranProcTs;
    }

    /**
     * :purpose: Return the account id key input.
     * :output: the ``acctId`` value.
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * :purpose: Set the account id key input.
     * :param acctId: the ``acctId`` value.
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * :purpose: Return the add confirmation flag.
     * :output: the ``confirm`` value.
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * :purpose: Set the add confirmation flag.
     * :param confirm: the ``confirm`` value.
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }
}
