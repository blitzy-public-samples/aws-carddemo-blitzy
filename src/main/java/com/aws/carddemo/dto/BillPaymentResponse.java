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
package com.aws.carddemo.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Response DTO for the CardDemo Bill Payment screen.
 *
 * <p>This is the outbound side of the Bill Payment online contract, migrated from the
 * following mainframe artifacts:
 * <ul>
 *   <li>BMS map definition {@code COBIL00} (mapset {@code COBIL00}, map {@code COBIL0A})</li>
 *   <li>Symbolic output map {@code COBIL0AO} (copybook {@code legacy/cpy-bms/COBIL00.CPY})</li>
 *   <li>Online program {@code COBIL00C} (transaction {@code CB00})</li>
 * </ul>
 *
 * <p>It carries the standard CardDemo screen header (transaction / title / date / program /
 * time), the echoed account identifier, the current account balance presented for payment
 * confirmation, and a single status/error message line. The 3270 field contract (field name,
 * maximum length, and PIC-derived type) is preserved exactly per the migration specification
 * (AAP&nbsp;0.5.3 / 0.9.2); only the observable data fields are modelled. The BMS attribute-byte
 * sub-fields (length / flag / colour / highlight fields such as {@code CURBALC} or
 * {@code CURBALH}) are intentionally omitted because the migrated application exposes REST
 * request/response contracts rather than rendering a terminal (AAP&nbsp;0.3.3).
 *
 * <p>The BMS {@code CONFIRM} (Y/N) field is an inbound field evaluated by {@code COBIL00C}
 * and therefore belongs to the request DTO, not to this response.
 *
 * <p><strong>Monetary fidelity.</strong> {@link #currentBalance()} mirrors the COBOL
 * {@code ACCT-CURR-BAL} value (a {@code COMP-3} packed decimal) and is modelled as
 * {@link BigDecimal} at a fixed scale of {@value #MONETARY_SCALE}, never {@code double} or
 * {@code float}, in keeping with the platform-wide decimal-exactness discipline. The canonical
 * constructor normalises a non-{@code null} balance to that scale using
 * {@link RoundingMode#HALF_UP}, reproducing the rounding behaviour of the legacy packed-decimal
 * arithmetic. A {@code null} balance (for example before an account has been looked up, or when
 * a validation error precedes the balance lookup) is preserved unchanged.
 *
 * <p>Status and error text carried in {@link #errorMessage()} traces to the shared message
 * constants in {@code legacy/cpy/CSMSG01Y.cpy} as populated by {@code COBIL00C}.
 *
 * <p>This type is an immutable, side-effect-free data carrier and contains no business logic.
 *
 * @param transactionName the four-character transaction identifier shown in the header;
 *        maps to {@code TRNNAMEO} (PIC X(4))
 * @param title01 the first title line of the screen header;
 *        maps to {@code TITLE01O} (PIC X(40))
 * @param currentDate the formatted current date ({@code mm/dd/yy}) shown in the header;
 *        maps to {@code CURDATEO} (PIC X(8))
 * @param programName the eight-character program identifier shown in the header;
 *        maps to {@code PGMNAMEO} (PIC X(8))
 * @param title02 the second title line of the screen header;
 *        maps to {@code TITLE02O} (PIC X(40))
 * @param currentTime the formatted current time ({@code hh:mm:ss}) shown in the header;
 *        maps to {@code CURTIMEO} (PIC X(8))
 * @param accountId the echoed eleven-character account identifier;
 *        maps to {@code ACTIDINO} (PIC X(11))
 * @param currentBalance the current account balance presented for payment confirmation, held
 *        at scale {@value #MONETARY_SCALE}; maps to {@code CURBALO} (PIC X(14)), sourced from
 *        {@code ACCT-CURR-BAL}
 * @param errorMessage the status/error message line;
 *        maps to {@code ERRMSGO} (PIC X(78))
 */
public record BillPaymentResponse(
        String transactionName,
        String title01,
        String currentDate,
        String programName,
        String title02,
        String currentTime,
        String accountId,
        BigDecimal currentBalance,
        String errorMessage) {

    /**
     * Fixed number of decimal places applied to the monetary {@link #currentBalance()} field,
     * matching the {@code V99} portion of the COBOL {@code ACCT-CURR-BAL} definition.
     */
    private static final int MONETARY_SCALE = 2;

    /**
     * Canonical constructor that normalises the monetary {@code currentBalance} to
     * {@link #MONETARY_SCALE} decimal places using {@link RoundingMode#HALF_UP}, preserving the
     * rounding semantics of the legacy {@code COMP-3} arithmetic. A {@code null} balance is left
     * unchanged so the response can represent screens rendered before a balance is available or
     * when a validation error precedes the balance lookup.
     */
    public BillPaymentResponse {
        if (currentBalance != null) {
            currentBalance = currentBalance.setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
        }
    }
}
