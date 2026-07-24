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

package com.carddemo.batch.batch;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * :purpose: Immutable carrier of one fully-resolved transaction-detail report row,
 *  conveyed from {@code TransactionReportItemProcessor} to
 *  {@code TransactionDetailReportWriter}. It is the Java analogue of the
 *  ``TRANSACTION-DETAIL-REPORT`` structure assembled in ``1120-WRITE-DETAIL`` of
 *  ``CBTRN03C`` after the card cross-reference, transaction-type, and
 *  transaction-category lookups; field widths and semantics derive from the
 *  ``CVTRA07Y`` report layout.
 * :output: A read-only value object holding the resolved detail values. The writer
 *  performs the fixed-width truncation/padding, applies the amount edit mask, and
 *  drives the per-account (card-number) control break; the card number is retained
 *  only for that control-break comparison and is never rendered or logged.
 */
public final class TransactionReportItem {

    /** ``TRAN-ID`` PIC X(16) — 16-character transaction identifier (rendered as ``TRAN-REPORT-TRANS-ID`` X(16)). */
    private final String tranId;

    /** ``XREF-ACCT-ID`` — account id resolved from the card cross-reference as an 11-digit zero-padded string (rendered as ``TRAN-REPORT-ACCOUNT-ID`` X(11)). */
    private final String accountId;

    /** ``TRAN-TYPE-CD`` PIC X(02) — 2-character transaction type code (rendered as ``TRAN-REPORT-TYPE-CD`` X(02)). */
    private final String tranTypeCd;

    /** ``TRAN-TYPE-DESC`` — transaction-type description from the type lookup (rendered as ``TRAN-REPORT-TYPE-DESC`` X(15)). */
    private final String tranTypeDesc;

    /** ``TRAN-CAT-CD`` PIC 9(04) — transaction category code (rendered as ``TRAN-REPORT-CAT-CD`` 9(04)). */
    private final Integer tranCatCd;

    /** ``TRAN-CAT-TYPE-DESC`` — transaction-category description from the category lookup (rendered as ``TRAN-REPORT-CAT-DESC`` X(29)). */
    private final String tranCatDesc;

    /** ``TRAN-SOURCE`` PIC X(10) — 10-character transaction origination source (rendered as ``TRAN-REPORT-SOURCE`` X(10)). */
    private final String tranSource;

    /** ``TRAN-AMT`` PIC S9(09)V99 — signed transaction amount (rendered via the ``-ZZZ,ZZZ,ZZZ.ZZ`` edit mask and accumulated into page/account/grand totals). */
    private final BigDecimal tranAmt;

    /** ``TRAN-CARD-NUM`` PIC X(16) — 16-character card number used solely as the writer control-break key (``WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM``); not rendered in the detail line and never logged. */
    private final String tranCardNum;

    /**
     * :purpose: Construct an immutable, fully-resolved transaction-detail report row.
     * :param tranId: 16-character transaction identifier (``TRAN-ID``).
     * :param accountId: 11-digit zero-padded account id resolved from the cross-reference (``XREF-ACCT-ID``).
     * :param tranTypeCd: 2-character transaction type code (``TRAN-TYPE-CD``).
     * :param tranTypeDesc: transaction-type description from the type lookup (``TRAN-TYPE-DESC``).
     * :param tranCatCd: transaction category code (``TRAN-CAT-CD``).
     * :param tranCatDesc: transaction-category description from the category lookup (``TRAN-CAT-TYPE-DESC``).
     * :param tranSource: 10-character transaction origination source (``TRAN-SOURCE``).
     * :param tranAmt: signed transaction amount (``TRAN-AMT``).
     * :param tranCardNum: 16-character card number used as the control-break key (``TRAN-CARD-NUM``).
     */
    public TransactionReportItem(String tranId,
                                 String accountId,
                                 String tranTypeCd,
                                 String tranTypeDesc,
                                 Integer tranCatCd,
                                 String tranCatDesc,
                                 String tranSource,
                                 BigDecimal tranAmt,
                                 String tranCardNum) {
        // A report row is a FULLY-resolved value object: every field is supplied
        // by the processor after the cross-reference/type/category lookups, so a
        // null here is a wiring defect that would corrupt the fixed-width layout
        // or the card-number control break. Reject it at construction.
        this.tranId = Objects.requireNonNull(tranId, "tranId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.tranTypeCd = Objects.requireNonNull(tranTypeCd, "tranTypeCd");
        this.tranTypeDesc = Objects.requireNonNull(tranTypeDesc, "tranTypeDesc");
        this.tranCatCd = Objects.requireNonNull(tranCatCd, "tranCatCd");
        this.tranCatDesc = Objects.requireNonNull(tranCatDesc, "tranCatDesc");
        this.tranSource = Objects.requireNonNull(tranSource, "tranSource");
        this.tranAmt = Objects.requireNonNull(tranAmt, "tranAmt");
        this.tranCardNum = Objects.requireNonNull(tranCardNum, "tranCardNum");
    }

    /** :purpose: Return the 16-character transaction id (``TRAN-ID``). */
    public String getTranId() {
        return tranId;
    }

    /** :purpose: Return the 11-digit zero-padded account id (``XREF-ACCT-ID``). */
    public String getAccountId() {
        return accountId;
    }

    /** :purpose: Return the 2-character transaction type code (``TRAN-TYPE-CD``). */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /** :purpose: Return the transaction-type description (``TRAN-TYPE-DESC``). */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /** :purpose: Return the transaction category code (``TRAN-CAT-CD``). */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /** :purpose: Return the transaction-category description (``TRAN-CAT-TYPE-DESC``). */
    public String getTranCatDesc() {
        return tranCatDesc;
    }

    /** :purpose: Return the 10-character transaction source (``TRAN-SOURCE``). */
    public String getTranSource() {
        return tranSource;
    }

    /** :purpose: Return the signed transaction amount (``TRAN-AMT``). */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /** :purpose: Return the 16-character control-break card number (``TRAN-CARD-NUM``). */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /**
     * :purpose: Provide a diagnostic string representation of this report row.
     * :output: Every field except the card number (``tranCardNum``), which is
     *  excluded so the PAN is never written to logs.
     */
    @Override
    public String toString() {
        return "TransactionReportItem{"
                + "tranId='" + tranId + '\''
                + ", accountId='" + accountId + '\''
                + ", tranTypeCd='" + tranTypeCd + '\''
                + ", tranTypeDesc='" + tranTypeDesc + '\''
                + ", tranCatCd=" + tranCatCd
                + ", tranCatDesc='" + tranCatDesc + '\''
                + ", tranSource='" + tranSource + '\''
                + ", tranAmt=" + tranAmt
                + '}';
    }
}
