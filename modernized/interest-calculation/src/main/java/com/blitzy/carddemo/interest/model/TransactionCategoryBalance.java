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
 * Immutable model of the COBOL {@code 01 TRAN-CAT-BAL-RECORD} defined in copybook
 * {@code app/cpy/CVTRA01Y.cpy} (RECLN 50).
 *
 * <p>This is the <strong>driver input record</strong> of the interest-calculation job. The legacy
 * program {@code CBACT04C} reads the TCATBAL file sequentially as its primary driver in paragraph
 * {@code 1000-TCATBALF-GET-NEXT} (CBACT04C L325-L348) and, for each record, computes the monthly
 * interest on {@link #balance()}.
 *
 * <p>The record is a pure data carrier: it performs <em>no</em> parsing, fixed-width framing,
 * validation, or I/O. Overpunch zoned-decimal decoding and the implied {@code V99} decimal point are
 * handled by {@code com.blitzy.carddemo.interest.support.ZonedDecimal}; fixed-width record framing
 * lives in the {@code io} package; business logic lives in the {@code service} package. COBOL performs
 * no record-level validation, so none is added here (minimal-change / behavior preservation, AAP 0.7).
 *
 * <p>The first three components correspond to the COBOL group {@code TRAN-CAT-KEY}. The trailing
 * copybook {@code FILLER PIC X(22)} is intentionally not modeled; those 22 padding bytes are accounted
 * for by the fixed-width reader in the {@code io} package. For reference the byte layout is
 * 11 + 2 + 4 + 11 + 22 = 50 (RECLN 50); no width constants are stored on this record.
 *
 * <p>Because the {@code balance} money field is carried at scale 2 by the codec, the record's
 * auto-generated {@code equals}, {@code hashCode}, and {@code toString} are well-defined and
 * scale-consistent.
 *
 * <p>Field mapping (source: {@code app/cpy/CVTRA01Y.cpy} L4-L10; AAP 0.6.2):
 * <ul>
 *   <li>{@code acctId}  &lt;- TRANCAT-ACCT-ID PIC 9(11)</li>
 *   <li>{@code typeCd}  &lt;- TRANCAT-TYPE-CD PIC X(02)</li>
 *   <li>{@code catCd}   &lt;- TRANCAT-CD      PIC 9(04)</li>
 *   <li>{@code balance} &lt;- TRAN-CAT-BAL    PIC S9(09)V99 (BigDecimal, scale 2)</li>
 * </ul>
 *
 * @param acctId  TRANCAT-ACCT-ID PIC 9(11) - 11-byte unsigned account id and lookup key (COBOL group
 *                TRAN-CAT-KEY). Modeled as {@link String} to preserve leading zeros and fixed width;
 *                it is a key, not an arithmetic operand.
 * @param typeCd  TRANCAT-TYPE-CD PIC X(02) - 2-byte category type code (COBOL group TRAN-CAT-KEY).
 * @param catCd   TRANCAT-CD PIC 9(04) - 4-byte unsigned category code (COBOL group TRAN-CAT-KEY).
 *                Modeled as {@link String} to preserve leading zeros (e.g. "0005").
 * @param balance TRAN-CAT-BAL PIC S9(09)V99 - signed balance the interest is computed on. Modeled as
 *                {@link BigDecimal} carried at scale 2 (the overpunch sign and implied {@code V99} are
 *                decoded by {@code support.ZonedDecimal}); never {@code double}/{@code float}.
 */
public record TransactionCategoryBalance(
        // TRANCAT-ACCT-ID PIC 9(11) - 11-byte unsigned account id (group TRAN-CAT-KEY);
        // String preserves leading zeros and fixed width (lookup key, not an arithmetic operand).
        String acctId,
        // TRANCAT-TYPE-CD PIC X(02) - 2-byte category type code (group TRAN-CAT-KEY).
        String typeCd,
        // TRANCAT-CD PIC 9(04) - 4-byte unsigned category code (group TRAN-CAT-KEY);
        // String preserves leading zeros (e.g. "0005").
        String catCd,
        // TRAN-CAT-BAL PIC S9(09)V99 - signed money the interest is computed on; BigDecimal at scale 2
        // (overpunch sign + implied V99 decoded by support.ZonedDecimal). Never double/float.
        BigDecimal balance) {
}
