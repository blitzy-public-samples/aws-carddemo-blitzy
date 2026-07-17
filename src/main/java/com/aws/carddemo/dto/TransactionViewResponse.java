/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto;

import java.math.BigDecimal;

/**
 * Response DTO for the CardDemo Transaction View / Detail screen.
 *
 * <p>This type is the Java re-expression of the 3270 BMS output map
 * {@code COTRN1AO} defined in {@code COTRN01.CPY} and rendered by BMS map
 * {@code COTRN01} (see {@code legacy/bms/COTRN01.bms}). It carries the full,
 * read-only projection of a single transaction as produced by the legacy
 * online program {@code COTRN01C}, and is surfaced by the
 * {@code TransactionViewController} to callers of the modernized REST API.
 *
 * <p>Only the display (output, {@code -O}-suffixed) fields of the symbolic map
 * are modeled here. The 3270 attribute bytes (colour, protection, highlight
 * and cursor control &mdash; the {@code -C}/{@code -P}/{@code -H}/{@code -V}
 * suffixes), the field-length halfwords ({@code -L}), and the screen-entry
 * transaction-id input field ({@code TRNIDINO}) are intentionally omitted: the
 * migration re-expresses screens as request/response contracts rather than a
 * rendered terminal, so terminal-rendering metadata carries no meaning in the
 * REST layer.
 *
 * <p>Every field preserves the name, maximum length and type of its legacy
 * counterpart to maintain the field-level contract required for behavioral
 * parity. Character fields ({@code PIC X(n)}) map to {@link String}; the single
 * monetary field, {@code amount} ({@code TRNAMTO}), maps to
 * {@link java.math.BigDecimal} carried at scale 2 to preserve
 * packed-decimal fidelity &mdash; floating-point types are never used for
 * monetary values. The population of {@code amount} at the correct scale and
 * the derivation of the display strings are the responsibility of the mapper
 * that projects the {@code Transaction} entity onto this DTO; this type holds
 * no business logic.
 *
 * <p>The {@code errorMessage} field surfaces informational or validation
 * messages to the caller; the legacy message constants that populate it trace
 * to {@code CSMSG01Y.cpy} (for example the "invalid key" advisory).
 *
 * <p>Implemented as an immutable {@link Record}; the compiler-generated
 * {@code toString()} is safe to use because this projection contains no
 * sensitive data (no card verification value and no password).
 *
 * @param transactionName the transaction screen name / header label
 *                         (map field {@code TRNNAMEO}, {@code PIC X(4)})
 * @param title01          the first application title line
 *                         (map field {@code TITLE01O}, {@code PIC X(40)})
 * @param title02          the second application title line
 *                         (map field {@code TITLE02O}, {@code PIC X(40)})
 * @param currentDate      the current date shown in the header, formatted
 *                         {@code mm/dd/yy}
 *                         (map field {@code CURDATEO}, {@code PIC X(8)})
 * @param programName      the owning program name shown in the header
 *                         (map field {@code PGMNAMEO}, {@code PIC X(8)})
 * @param currentTime      the current time shown in the header, formatted
 *                         {@code hh:mm:ss}
 *                         (map field {@code CURTIMEO}, {@code PIC X(8)})
 * @param transactionId    the 16-character transaction identifier
 *                         (map field {@code TRNIDO}, {@code PIC X(16)})
 * @param cardNumber       the 16-character card number associated with the
 *                         transaction
 *                         (map field {@code CARDNUMO}, {@code PIC X(16)})
 * @param typeCode         the transaction type code
 *                         (map field {@code TTYPCDO}, {@code PIC X(2)})
 * @param categoryCode     the transaction category code
 *                         (map field {@code TCATCDO}, {@code PIC X(4)})
 * @param source           the transaction source
 *                         (map field {@code TRNSRCO}, {@code PIC X(10)})
 * @param description      the transaction description
 *                         (map field {@code TDESCO}, {@code PIC X(60)})
 * @param amount           the transaction amount as a scale-2 monetary value
 *                         (map field {@code TRNAMTO}, {@code PIC X(12)})
 * @param originDate       the origin date / timestamp for display
 *                         (map field {@code TORIGDTO}, {@code PIC X(10)})
 * @param processDate      the process date / timestamp for display
 *                         (map field {@code TPROCDTO}, {@code PIC X(10)})
 * @param merchantId       the merchant identifier
 *                         (map field {@code MIDO}, {@code PIC X(9)})
 * @param merchantName     the merchant name
 *                         (map field {@code MNAMEO}, {@code PIC X(30)})
 * @param merchantCity     the merchant city
 *                         (map field {@code MCITYO}, {@code PIC X(25)})
 * @param merchantZip      the merchant ZIP / postal code
 *                         (map field {@code MZIPO}, {@code PIC X(10)})
 * @param errorMessage     the error or informational message surfaced to the
 *                         caller; constants trace to {@code CSMSG01Y.cpy}
 *                         (map field {@code ERRMSGO}, {@code PIC X(78)})
 */
public record TransactionViewResponse(
        String transactionName,
        String title01,
        String title02,
        String currentDate,
        String programName,
        String currentTime,
        String transactionId,
        String cardNumber,
        String typeCode,
        String categoryCode,
        String source,
        String description,
        BigDecimal amount,
        String originDate,
        String processDate,
        String merchantId,
        String merchantName,
        String merchantCity,
        String merchantZip,
        String errorMessage) {
}
