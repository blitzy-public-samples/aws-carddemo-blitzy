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
public class TransactionAddRequestDto {

    /** :purpose: Transaction type code (COTRN02 ``TTYPCDI`` / ``TRAN-TYPE-CD`` PIC X(02)). */
    @Size(max = 2)
    private String tranTypeCd;

    /** :purpose: Transaction category code (COTRN02 ``TCATCDI`` / ``TRAN-CAT-CD`` PIC 9(04)). */
    private Integer tranCatCd;

    /** :purpose: Origination source (COTRN02 ``TRNSRCI`` / ``TRAN-SOURCE`` PIC X(10)). */
    @Size(max = 10)
    private String tranSource;

    /** :purpose: Transaction description (COTRN02 ``TDESCI`` / ``TRAN-DESC`` PIC X(100)). */
    @Size(max = 100)
    private String tranDesc;

    /** :purpose: Monetary amount (COTRN02 ``TRNAMTI`` / ``TRAN-AMT`` PIC S9(09)V99 -> NUMERIC(11,2)). */
    private BigDecimal tranAmt;

    /** :purpose: Card number (COTRN02 ``CARDNINI`` / ``TRAN-CARD-NUM`` PIC X(16)). */
    @Size(max = 16)
    private String tranCardNum;

    /** :purpose: Merchant id (COTRN02 ``MIDI`` / ``TRAN-MERCHANT-ID`` PIC 9(09)). */
    private Long tranMerchantId;

    /** :purpose: Merchant name (COTRN02 ``MNAMEI`` / ``TRAN-MERCHANT-NAME`` PIC X(50)). */
    @Size(max = 50)
    private String tranMerchantName;

    /** :purpose: Merchant city (COTRN02 ``MCITYI`` / ``TRAN-MERCHANT-CITY`` PIC X(50)). */
    @Size(max = 50)
    private String tranMerchantCity;

    /** :purpose: Merchant postal code (COTRN02 ``MZIPI`` / ``TRAN-MERCHANT-ZIP`` PIC X(10)). */
    @Size(max = 10)
    private String tranMerchantZip;

    /** :purpose: Origination timestamp (COTRN02 ``TORIGDTI`` / ``TRAN-ORIG-TS`` PIC X(26)). */
    @Size(max = 26)
    private String tranOrigTs;

    /** :purpose: Processing timestamp (COTRN02 ``TPROCDTI`` / ``TRAN-PROC-TS`` PIC X(26)). */
    @Size(max = 26)
    private String tranProcTs;

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
     * :purpose: Return the origination timestamp.
     * :output: the 26-character ``tranOrigTs`` value.
     */
    public String getTranOrigTs() {
        return tranOrigTs;
    }

    /**
     * :purpose: Set the origination timestamp.
     * :param tranOrigTs: the 26-character ``tranOrigTs`` value.
     */
    public void setTranOrigTs(String tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    /**
     * :purpose: Return the processing timestamp.
     * :output: the 26-character ``tranProcTs`` value.
     */
    public String getTranProcTs() {
        return tranProcTs;
    }

    /**
     * :purpose: Set the processing timestamp.
     * :param tranProcTs: the 26-character ``tranProcTs`` value.
     */
    public void setTranProcTs(String tranProcTs) {
        this.tranProcTs = tranProcTs;
    }
}
