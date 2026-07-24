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
 * :purpose: Outbound single-row DTO for the COTRN00 transaction-list screen (CICS
 *  transaction ``CT00``, legacy program ``COTRN00C``). Carries the four columns the
 *  3270 map surfaces per row: the transaction id, the short origination date
 *  rendered as ``MM/DD/YY`` (COBOL ``WS-TRAN-DATE``), the description, and the
 *  amount as a scale-2 {@link BigDecimal} preserving the COBOL ``S9(09)V99``
 *  precision. Page size and paging flags are decided by the transaction service,
 *  not this carrier.
 * :output: A mutable row carrier with the transaction id, formatted short date,
 *  description, and amount.
 */
public class TransactionListItemDto {

    /** :purpose: Transaction id (COTRN00 ``TRNIDnnI`` / ``TRAN-ID`` PIC X(16)). */
    @Size(max = 16)
    private String tranId;

    /** :purpose: Short origination date rendered ``MM/DD/YY`` (COTRN00 ``TDATEnnI`` / ``WS-TRAN-DATE`` PIC X(08)). */
    @Size(max = 8)
    private String tranDate;

    /** :purpose: Transaction description (COTRN00 ``TDESCnnI`` / ``TRAN-DESC`` PIC X(100)). */
    @Size(max = 100)
    private String tranDesc;

    /** :purpose: Monetary amount (COTRN00 ``TRAN-AMT`` PIC S9(09)V99 -> NUMERIC(11,2)). */
    private BigDecimal tranAmt;

    /**
     * :purpose: Create an empty row. Required for JSON (Jackson) serialization.
     */
    public TransactionListItemDto() {
    }

    /**
     * :purpose: Return the transaction id.
     * :output: the ``tranId`` value.
     */
    public String getTranId() {
        return tranId;
    }

    /**
     * :purpose: Set the transaction id.
     * :param tranId: the ``tranId`` value.
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * :purpose: Return the short origination date.
     * :output: the ``MM/DD/YY`` ``tranDate`` value.
     */
    public String getTranDate() {
        return tranDate;
    }

    /**
     * :purpose: Set the short origination date.
     * :param tranDate: the ``MM/DD/YY`` ``tranDate`` value.
     */
    public void setTranDate(String tranDate) {
        this.tranDate = tranDate;
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
     * :output: the ``tranAmt`` value as a scale-2 {@link BigDecimal}.
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * :purpose: Set the monetary amount.
     * :param tranAmt: the ``tranAmt`` value as a scale-2 {@link BigDecimal}.
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }
}
