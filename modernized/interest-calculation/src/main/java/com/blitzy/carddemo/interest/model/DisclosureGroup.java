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
 * Immutable model of the COBOL {@code 01 DIS-GROUP-RECORD} defined in copybook
 * {@code app/cpy/CVTRA02Y.cpy} (RECLN 50) — the disclosure-group interest-rate
 * lookup record.
 *
 * <p>CBACT04C reads the DISCGRP file by the composite {@code DIS-GROUP-KEY}
 * ({@code DIS-ACCT-GROUP-ID} + {@code DIS-TRAN-TYPE-CD} + {@code DIS-TRAN-CAT-CD})
 * to obtain the annual interest rate applied during interest calculation, with a
 * DEFAULT-group fallback when the specific group is not found. See CBACT04C
 * paragraph {@code 1200-GET-INTEREST-RATE} (L415-440) and the fallback path
 * {@code 1200-A-GET-DEFAULT-INT-RATE} (DEFAULT selection L436-439, re-read
 * L443-460).</p>
 *
 * <p>This type is a pure, immutable data carrier. It performs NO fixed-width
 * framing, NO overpunch/zoned-decimal parsing, NO validation, and NO I/O — those
 * responsibilities belong to {@code io.DisclosureGroupRepository} (record framing
 * plus the DEFAULT-group rate-selection logic) and {@code support.ZonedDecimal}
 * (overpunch zoned-decimal &lt;-&gt; {@code BigDecimal} decoding). The trailing
 * COBOL {@code FILLER PIC X(28)} is intentionally NOT modeled here; the I/O layer
 * accounts for those 28 padding bytes (10 + 2 + 4 + 6 + 28 = 50 = RECLN 50).</p>
 *
 * <p>The {@code intRate} component is carried as a {@link BigDecimal} at scale 2
 * (decoded from the zoned-decimal {@code S9(04)V99} field). Modeling rate/money
 * fields with {@code BigDecimal} — never {@code double}/{@code float} — preserves
 * COBOL fixed-decimal semantics exactly, and keeping a consistent scale makes the
 * record's auto-generated {@link #equals(Object)} / {@link #hashCode()}
 * scale-consistent.</p>
 *
 * @param groupId    DIS-ACCT-GROUP-ID — COBOL {@code PIC X(10)}; disclosure-group
 *                   id and first part of the composite key. 10-char fixed width.
 * @param tranTypeCd DIS-TRAN-TYPE-CD — COBOL {@code PIC X(02)}; transaction type
 *                   code and second part of the composite key. 2-char fixed width.
 * @param tranCatCd  DIS-TRAN-CAT-CD — COBOL {@code PIC 9(04)}; transaction
 *                   category code and third part of the composite key. Unsigned
 *                   numeric retained as {@code String} to preserve leading zeros
 *                   (e.g. {@code "0005"}).
 * @param intRate    DIS-INT-RATE — COBOL {@code PIC S9(04)V99}; signed annual
 *                   percentage interest rate (e.g. {@code 15.00}, {@code 25.00},
 *                   {@code 0.00}) carried as {@link BigDecimal} at scale 2.
 */
public record DisclosureGroup(
        // DIS-ACCT-GROUP-ID — PIC X(10) — disclosure-group id (composite key #1)
        String groupId,
        // DIS-TRAN-TYPE-CD — PIC X(02) — transaction type code (composite key #2)
        String tranTypeCd,
        // DIS-TRAN-CAT-CD — PIC 9(04) — transaction category code (composite key #3)
        String tranCatCd,
        // DIS-INT-RATE — PIC S9(04)V99 — signed annual % rate, BigDecimal scale 2
        BigDecimal intRate) {
}
