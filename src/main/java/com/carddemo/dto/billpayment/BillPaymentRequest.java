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

package com.carddemo.dto.billpayment;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bill payment request DTO — inbound payload for
 * {@code POST /api/accounts/{acctId}/payments}.
 *
 * <p>Models the BMS screen input fields from {@code app/cpy-bms/COBIL00.CPY}
 * that the original COBOL program {@code COBIL00C}
 * ({@code app/cbl/COBIL00C.cbl}) accepted from the 3270 user terminal.
 * COBIL00C took <em>no</em> amount input: it always paid the full current
 * balance ({@code MOVE ACCT-CURR-BAL TO TRAN-AMT}, COBIL00C L224). The only
 * user-supplied fields are the account id and the {@code Y}/{@code N}
 * confirmation flag, so this DTO carries exactly those two fields (AAP
 * &sect;0.7.2 / PR-25 — no feature additions; the paid amount and the resulting
 * balance are returned via {@link BillPaymentResponse}).
 *
 * <p>Field provenance:
 * <pre>
 *   Java Field      COBOL Source                                  Constraint Rationale
 *   ──────────      ────────────                                  ────────────────────
 *   accountId       COBIL00.CPY ACTIDINI PIC X(11) line 60        Long accommodates 11-digit
 *                   (also CVACT01Y ACCT-ID PIC 9(11))             account IDs. @NotNull required.
 *   confirmation    COBIL00.CPY CONFIRMI PIC X(1) line 72         Exactly 1 char, uppercase
 *                   (matches COBOL CONF-PAY-YES = 'Y' check)      Y or N only per folder spec.
 * </pre>
 *
 * <p><b>CRITICAL — PR-25 (no feature additions):</b> the payment amount is
 * <em>not</em> a client input. COBIL00C always paid the full pre-payment
 * balance; {@code BillPaymentService} reproduces that exactly. There is no
 * partial/minimum/overpayment path — overpayment is structurally impossible
 * because no amount can be supplied.
 *
 * <p><b>CRITICAL — PR-28 (Jakarta EE namespace):</b> All validation
 * annotations come from {@code jakarta.validation.constraints.*}, NOT
 * {@code javax.validation.*}.
 *
 * <p><b>CRITICAL — Atomicity:</b> The bill payment flow MUST occur within a
 * single {@code @Transactional} method scope. The transactional boundary lives
 * in {@code BillPaymentService}, NOT in this DTO. This DTO is purely a data
 * carrier — no business logic, no calculations, no service-layer side effects.
 *
 * <p><b>CRITICAL — Confirmation pattern:</b> The {@code confirmation} field
 * accepts ONLY uppercase {@code Y} or {@code N} (regex {@code [YN]}), matching
 * the COBOL {@code COBIL00C} program's check for {@code CONF-PAY-YES = 'Y'}.
 * Lowercase variants ({@code y}/{@code n}) are rejected by Bean Validation.
 * Only when {@code confirmation = "Y"} does {@code BillPaymentService} actually
 * process the payment.
 *
 * <p><b>Available-credit relationship:</b> The DTO does NOT carry available
 * credit — that is computed by {@code BillPaymentService} per AAP §0.4.1.1 as
 * {@code ACCT-CREDIT-LIMIT - ACCT-CURR-BAL} and returned via
 * {@link BillPaymentResponse#availableCredit()}.
 *
 * <p>Account ID path-vs-body redundancy: the URL path parameter
 * {@code {acctId}} is the primary account identifier. This DTO also carries
 * {@code accountId} for validation consistency (controller asserts path matches
 * body) — protects against accidental client bugs where body and URL diverge.
 *
 * @see com.carddemo.dto.billpayment.BillPaymentResponse
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Bill payment request — submitted to POST /api/accounts/{acctId}/payments. " +
        "Mirrors the COBOL COBIL00C input screen (BMS map COBIL00) with fields for account ID and a " +
        "Y/N confirmation flag. COBIL00C took no amount input — it always paid the full current " +
        "balance (MOVE ACCT-CURR-BAL TO TRAN-AMT) — so this request carries no amount or payment " +
        "method (AAP §0.7.2 / PR-25 — no feature additions). Per AAP §0.4.1.1, the bill payment flow " +
        "atomically creates a payment Transaction and zeroes the Account balance within a single " +
        "@Transactional boundary in BillPaymentService. The confirmation field must equal 'Y' for " +
        "the payment to actually be processed (matches COBOL CONF-PAY-YES check).")
public class BillPaymentRequest {

    @NotNull(message = "accountId is required")
    @Positive(message = "accountId must be a positive value")
    @Schema(description = "Account ID for which payment is being made. 11-digit numeric identifier " +
            "matching COBIL00.CPY ACTIDINI PIC X(11) at line 60 (also CVACT01Y ACCT-ID PIC 9(11)). " +
            "This value should match the {acctId} URL path parameter — BillPaymentController will " +
            "validate consistency. PR-13: max 11 digits.",
            example = "10000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Long accountId;

    @NotBlank(message = "confirmation is required")
    @Size(min = 1, max = 1, message = "confirmation must be exactly 1 character")
    @Pattern(regexp = "[YN]", message = "confirmation must be exactly 'Y' or 'N' (uppercase)")
    @Schema(description = "Y/N confirmation flag. Mirrors COBIL00.CPY CONFIRMI PIC X(1) at line 72 " +
            "and the COBOL COBIL00C check for CONF-PAY-YES = 'Y'. Must be UPPERCASE 'Y' or 'N' " +
            "(lowercase rejected — matches COBOL byte-exact comparison). Only when 'Y' is submitted " +
            "does BillPaymentService process the payment; 'N' rejects the request as " +
            "user-cancelled.",
            example = "Y",
            allowableValues = {"Y", "N"},
            minLength = 1, maxLength = 1,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String confirmation;
}
