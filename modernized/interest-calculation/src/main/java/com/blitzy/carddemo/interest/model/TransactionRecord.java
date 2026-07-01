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
package com.blitzy.carddemo.interest.model;

import java.math.BigDecimal;

/**
 * Immutable model of the COBOL {@code 01 TRAN-RECORD} defined in copybook
 * {@code app/cpy/CVTRA05Y.cpy} (RECLN 350).
 *
 * <p>This is the generated interest-transaction output record. In the source
 * program CBACT04C, one instance is built per (account, transaction-category-balance)
 * pair whose disclosure-group rate is non-zero, then written to the 350-byte
 * TRANSACT file. The construction and write happen in paragraph
 * {@code 1300-B-WRITE-TX} (CBACT04C.cbl L473-515); the field values documented
 * against each component below are the {@code MOVE}/{@code STRING} targets from
 * that paragraph (L474-498).
 *
 * <p><b>Pure data carrier.</b> This type only <i>holds</i> the computed values;
 * it performs no formatting, fixed-width framing, or I/O. Assembling the field
 * values (TRAN-ID from PARM-DATE plus a suffix, the {@code '01'}/{@code '05'}/
 * {@code 'System'}/{@code 'Int. for a/c '} literals, space padding, and the DB2
 * timestamp) is the responsibility of the {@code service}/{@code io}/{@code support}
 * layers; emitting the trailing 20-byte FILLER during 350-byte framing is the
 * responsibility of {@code io.TransactionWriter}. Consequently this record models
 * only the 13 significant fields and intentionally omits the copybook's trailing
 * {@code FILLER PIC X(20)}.
 *
 * <p>Field-width reference (documentation only; not enforced here):
 * 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20(FILLER) = 350
 * (matches CVTRA05Y RECLN 350).
 *
 * <p><b>Decimal fidelity.</b> {@code amount} models the signed COBOL money field
 * {@code TRAN-AMT PIC S9(09)V99} as a {@link BigDecimal}. Callers supply it already
 * scaled to 2 (the truncated monthly interest, WS-MONTHLY-INT). Because the value is
 * always carried at scale 2, the record's auto-generated {@code equals}/{@code hashCode}
 * are scale-consistent, which is what the golden-master comparison relies on. No
 * normalization or validation is performed here by design (minimal-change migration).
 *
 * <p><b>BR-15.</b> {@code procTs} always equals {@code origTs}: CBACT04C moves the same
 * DB2 timestamp (DB2-FORMAT-TS) into both TRAN-ORIG-TS and TRAN-PROC-TS (L496-498).
 *
 * <p>Reverse-engineered from {@code app/cpy/CVTRA05Y.cpy} (TRAN-RECORD, RECLN 350) for
 * structure and {@code app/cbl/CBACT04C.cbl} {@code 1300-B-WRITE-TX} (L473-498) for the
 * write-time field values. Strictly additive; no build/runtime dependency on {@code app/}.
 */
public record TransactionRecord(
        // TRAN-ID PIC X(16) = PARM-DATE (10 chars) + 6-digit zero-padded suffix (L474-480)
        String tranId,
        // TRAN-TYPE-CD PIC X(02) = '01' (L482)
        String typeCd,
        // TRAN-CAT-CD PIC 9(04) = '05' stored as the 4-digit field value '0005' (L483)
        String catCd,
        // TRAN-SOURCE PIC X(10) = 'System' (L484)
        String source,
        // TRAN-DESC PIC X(100) = 'Int. for a/c ' + ACCT-ID (L485-489)
        String description,
        // TRAN-AMT PIC S9(09)V99 = WS-MONTHLY-INT, the truncated monthly interest,
        // carried as BigDecimal at scale 2 (never double/float) (L490)
        BigDecimal amount,
        // TRAN-MERCHANT-ID PIC 9(09) = 0 -> '000000000'; unsigned numeric modeled as String (L491)
        String merchantId,
        // TRAN-MERCHANT-NAME PIC X(50) = spaces (L492)
        String merchantName,
        // TRAN-MERCHANT-CITY PIC X(50) = spaces (L493)
        String merchantCity,
        // TRAN-MERCHANT-ZIP PIC X(10) = spaces (L494)
        String merchantZip,
        // TRAN-CARD-NUM PIC X(16) = XREF-CARD-NUM from the matching CardXref (L495)
        String cardNum,
        // TRAN-ORIG-TS PIC X(26) = 26-char DB2 timestamp (DB2-FORMAT-TS) (L497)
        String origTs,
        // TRAN-PROC-TS PIC X(26) = 26-char DB2 timestamp; SAME value as origTs (BR-15, L496-498)
        String procTs) {
}
