package com.carddemo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Immutable JSON request body for the online bill-payment operation
 * (HTTP {@code POST /accounts/{id}/bill-payment}).
 *
 * <p><strong>Legacy lineage.</strong> This DTO is the REST re-expression of the input
 * surface of the CICS online program {@code COBIL00C} and its BMS screen {@code COBIL00}.
 * In the mainframe implementation the operator keyed an account id and a single-character
 * {@code CONFIRM} flag (field {@code CONFIRMI PIC X(1)}) into the 3270 map; the program
 * paid the account's <em>full current balance</em> and recorded a transaction described
 * {@code "BILL PAYMENT - ONLINE"}. The bill-payment amount was never operator-supplied —
 * {@code COBIL00C} always posts {@code ACCT-CURR-BAL} (see
 * {@code MOVE ACCT-CURR-BAL TO TRAN-AMT}).</p>
 *
 * <p><strong>Request-shape rationale.</strong> Because the legacy program derives the
 * payable amount from the account record (not from user input), and because the account
 * identifier is conveyed by the REST URL path ({@code /accounts/{id}/bill-payment}) rather
 * than the request body, the body collapses to a single mandatory confirmation flag:</p>
 * <ul>
 *   <li>{@code confirm == true} &rarr; proceed with the full-balance payment (the
 *       REST-idiomatic equivalent of the legacy {@code CONFIRM = 'Y'} path).</li>
 *   <li>{@code confirm == false} &rarr; do not pay (equivalent to the legacy
 *       {@code CONFIRM = 'N'} path that cleared the screen without posting).</li>
 *   <li>{@code confirm} absent ({@code null}) &rarr; rejected by Bean Validation with
 *       HTTP&nbsp;400, mirroring the legacy {@code "Invalid value. Valid values are (Y/N)..."}
 *       guard that refused to post without an explicit decision.</li>
 * </ul>
 *
 * <p><strong>Why {@link Boolean} (boxed) and not {@code boolean}.</strong> The boxed
 * reference type is required so that {@link NotNull} can distinguish an
 * <em>absent</em> {@code confirm} field (deserialized as {@code null}) from an explicit
 * {@code false}. A primitive {@code boolean} would silently default to {@code false},
 * defeating the validation and allowing a body with no confirmation to be treated as a
 * deliberate "do not pay" instead of a client error.</p>
 *
 * <p><strong>Business rules deliberately excluded.</strong> Per the layered architecture,
 * the nothing-to-pay rule ({@code "You have nothing to pay..."} when the balance is
 * {@code <= 0}), the full-balance computation, the {@code "BILL PAYMENT - ONLINE"}
 * transaction creation, and the account-balance update all reside in
 * {@code BillPaymentService}. This DTO carries data only; it intentionally contains no
 * business logic, no entity references, and no user-supplied payment amount.</p>
 *
 * @param confirm explicit confirmation of the full-balance bill payment; {@code true}
 *                proceeds with the payment, {@code false} declines it, and {@code null}
 *                (absent in the request body) is rejected as a validation error.
 *                Maps to the legacy {@code COBIL00C} {@code CONFIRM} (Y/N) flag.
 */
public record BillPaymentRequest(

        @NotNull(message = "Confirmation is required")
        Boolean confirm

) {
}
