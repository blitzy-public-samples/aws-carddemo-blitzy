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

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo Bill Payment screen.
 *
 * <p>This is the inbound side of the Bill Payment online contract, migrated from the
 * following mainframe artifacts:
 * <ul>
 *   <li>BMS map definition {@code COBIL00} (mapset {@code COBIL00}, map {@code COBIL0A})</li>
 *   <li>Symbolic input map {@code COBIL0AI} (copybook {@code legacy/cpy-bms/COBIL00.CPY})</li>
 *   <li>Online program {@code COBIL00C} (transaction {@code CB00}),
 *       surfaced by {@code BillPaymentController} / {@code BillPaymentService}</li>
 * </ul>
 *
 * <p>It carries only the operator-enterable data of the screen: the account identifier and the
 * Y/N confirmation used to post a payment for the full current account balance. On the 3270 map,
 * these are the two {@code UNPROT} fields {@code ACTIDIN} and {@code CONFIRM}; the current-balance
 * field ({@code CURBAL}) is display-only and therefore belongs to {@code BillPaymentResponse},
 * not to this request. The header fields (transaction / title / date / program / time) and the
 * error message line are program-generated output and are likewise excluded. The 3270 field
 * contract (field name, maximum length, and PIC-derived type) is preserved exactly per the
 * migration specification (AAP&nbsp;0.5.3 / 0.9.2); the BMS attribute-byte sub-fields (the
 * length / flag / colour / highlight fields such as {@code ACTIDINL} or {@code CONFIRMA}) are
 * intentionally omitted because the migrated application exposes REST request/response contracts
 * rather than rendering a terminal (AAP&nbsp;0.3.3).
 *
 * <p><strong>Field validation</strong> reproduces the edit rules of {@code COBIL00C}'s
 * {@code PROCESS-ENTER-KEY} paragraph declaratively, via Bean Validation constraints:
 * <ul>
 *   <li>{@link #accountId()} maps to {@code ACTIDINI} ({@code PIC X(11)}); it is the account key
 *       (moved to {@code ACCT-ID} / {@code XREF-ACCT-ID}). At most eleven characters, digits only.
 *       An empty value is permitted here so that the service can reproduce the legacy
 *       &quot;Acct ID can NOT be empty&quot; message rather than failing at the transport layer.</li>
 *   <li>{@link #confirm()} maps to {@code CONFIRMI} ({@code PIC X(1)}); it is the Y/N confirmation.
 *       The legacy {@code EVALUATE CONFIRMI} accepts {@code 'Y'}/{@code 'y'} (pay),
 *       {@code 'N'}/{@code 'n'} (decline), and blank/unset (prompt for confirmation), rejecting any
 *       other value with &quot;Invalid value. Valid values are (Y/N)&quot;. Only the single-character
 *       {@code PIC X(1)} length is enforced declaratively here; that {@code Y}/{@code N} value
 *       {@code EVALUATE} is reproduced by {@link com.aws.carddemo.service.BillPaymentService}, which
 *       echoes &quot;Invalid value. Valid values are (Y/N)&quot; back on-screen (HTTP 200) for any
 *       other one-character value rather than rejecting it at the transport layer.</li>
 * </ul>
 *
 * <p>{@link #action()} carries the terminal Attention Identifier (the key the operator pressed),
 * translated from the shared {@code CSSTRPFY.cpy} contract into the package-shared
 * {@link PfKeyAction} enum. Per the screen footer (&quot;ENTER=Continue&nbsp;&nbsp;F3=Back&nbsp;&nbsp;F4=Clear&quot;)
 * and {@code COBIL00C}'s {@code EVALUATE EIBAID}, the meaningful values are
 * {@link PfKeyAction#ENTER} (continue / submit the payment), {@link PfKeyAction#PF3} (back to the
 * previous screen or menu), and {@link PfKeyAction#PF4} (clear the screen). It is optional: a
 * {@code null} action is treated by the owning controller as the default Enter behaviour, and any
 * other key is handled there as the legacy &quot;invalid key&quot; case. Interpreting the action is
 * the controller's responsibility, so no routing logic is encoded here.
 *
 * <p>This type is an immutable, side-effect-free data carrier and contains no business logic. As a
 * {@code record} it exposes canonical accessors together with compiler-generated
 * {@code equals}/{@code hashCode}/{@code toString}; the default {@code toString} is safe because no
 * field is sensitive (the account identifier and confirmation flag are ordinary screen inputs, and
 * no password or card CVV is present).
 *
 * @param accountId the account identifier to pay, entered on the screen; maps to {@code ACTIDINI}
 *        ({@code PIC X(11)}). At most eleven characters and digits only; may be empty so the
 *        service can surface the legacy empty-account-id message.
 * @param confirm the Y/N confirmation to post the full-balance payment; maps to {@code CONFIRMI}
 *        ({@code PIC X(1)}). At most one character; the {@code Y}/{@code N}/blank value check is a
 *        service-level same-screen edit, so an invalid one-character value is echoed back on-screen
 *        (HTTP 200) rather than rejected at the transport layer.
 * @param action the terminal Attention Identifier for this submission (translated from
 *        {@code EIBAID} via {@code CSSTRPFY.cpy}); {@link PfKeyAction#ENTER},
 *        {@link PfKeyAction#PF3}, or {@link PfKeyAction#PF4} are meaningful on this screen.
 *        Optional; {@code null} defaults to Enter handling in the controller.
 */
public record BillPaymentRequest(

        @Size(max = 11, message = "accountId must be at most 11 characters")
        @Pattern(regexp = "^\\d{0,11}$", message = "accountId must contain digits only")
        String accountId,

        // CONFIRMI PIC X(1): only the single-character length is enforced here; the Y/N value
        // EVALUATE is a service-level same-screen edit (BillPaymentService), so an invalid
        // one-character confirm returns HTTP 200 with "Invalid value. Valid values are (Y/N)...".
        @Size(max = 1, message = "confirm must be a single character")
        String confirm,

        PfKeyAction action) {
}
