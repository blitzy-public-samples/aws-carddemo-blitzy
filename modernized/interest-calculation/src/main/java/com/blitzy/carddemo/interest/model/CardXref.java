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
package com.blitzy.carddemo.interest.model;

/**
 * Immutable model of the COBOL {@code 01 CARD-XREF-RECORD} defined in copybook
 * {@code app/cpy/CVACT03Y.cpy} (RECLN 50).
 *
 * <p>This is the card-to-account cross-reference. In CBACT04C the XREF file is
 * read by its alternate-index key (account id) in paragraph
 * {@code 1110-GET-XREF-DATA} (CBACT04C L393-413) to obtain the card number,
 * which is then copied into each generated interest transaction
 * ({@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM}, CBACT04C L495).
 *
 * <p>Layout note: the copybook defines four fields totalling 50 bytes
 * (16 + 9 + 11 + 14). The trailing {@code FILLER X(14)} is intentionally NOT
 * modeled here; the in-repo ASCII fixture {@code app/data/ASCII/cardxref.txt}
 * stores only the 36 significant bytes (16 + 9 + 11) and omits the FILLER.
 * Fixed-width framing of those significant bytes (and the missing-xref fatal
 * abend, CBACT04C L405-412) is the responsibility of
 * {@code com.blitzy.carddemo.interest.io.CardXrefRepository}, not of this pure
 * data carrier.
 *
 * <p>Design (minimal-change, AAP 0.7): a pure, immutable data carrier with no
 * parsing, no framing, no validation, and no business/convenience methods. The
 * unsigned COBOL numeric identifiers are modeled as {@link String} to preserve
 * leading zeros and fixed width exactly (no numeric conversion is performed).
 *
 * @param cardNum card number - COBOL XREF-CARD-NUM, PIC X(16) (16 bytes);
 *                copied to TRAN-CARD-NUM in CBACT04C L495
 * @param custId  customer id - COBOL XREF-CUST-ID, PIC 9(09) (9 bytes);
 *                unsigned numeric preserved as a fixed-width String
 * @param acctId  account id - COBOL XREF-ACCT-ID, PIC 9(11) (11 bytes); the
 *                alternate-index key read in CBACT04C 1110-GET-XREF-DATA
 *                (L393-413); unsigned numeric preserved as a fixed-width String
 */
public record CardXref(
        // XREF-CARD-NUM  PIC X(16)  - 16 bytes; card number copied to TRAN-CARD-NUM (CBACT04C L495)
        String cardNum,
        // XREF-CUST-ID   PIC 9(09)  -  9 bytes; unsigned numeric preserved as String (leading zeros / fixed width)
        String custId,
        // XREF-ACCT-ID   PIC 9(11)  - 11 bytes; alternate-index key (CBACT04C 1110-GET-XREF-DATA, L393-413)
        String acctId) {
}
