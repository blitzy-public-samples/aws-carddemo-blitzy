package com.carddemo.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable JSON response payload for the online bill-payment / balance-inquiry contract
 * ({@code POST /accounts/{id}/bill-payment} and the balance view) exposed by
 * {@code BillPaymentController}.
 *
 * <p><strong>Legacy lineage.</strong> This DTO is the Spring/REST re-expression of the
 * output surface of the CICS online program {@code COBIL00C} ("Bill Payment") and its BMS
 * 3270 map {@code COBIL00}. The numeric values it carries originate from the VSAM account
 * record {@code ACCOUNT-RECORD} defined in copybook {@code CVACT01Y} (record length 300).
 * Where the legacy program rendered the balance into the fixed-width screen field
 * {@code CURBAL PIC X(14)} for display, this contract surfaces the underlying
 * {@link java.math.BigDecimal} so that no precision is lost across the service boundary.</p>
 *
 * <p><strong>One contract, two outcomes.</strong> A single instance of this record serves
 * both legacy paths that {@code COBIL00C} drives from one screen:</p>
 * <ul>
 *   <li><em>Balance inquiry / nothing-to-pay.</em> When the account is merely queried, or
 *       when the current balance is {@code <= 0} (the legacy
 *       {@code "You have nothing to pay..."} guard at {@code COBIL00C} lines&nbsp;198&ndash;205),
 *       or when the operator has not confirmed the payment, <em>no</em> transaction is
 *       written. {@link #transactionId()} is then {@code null} and {@link #newBalance()}
 *       equals {@link #currentBalance()}.</li>
 *   <li><em>Completed payment.</em> When the operator confirmed payment (legacy
 *       {@code CONFIRM = 'Y'}), {@code COBIL00C} posts the account's <em>full</em> current
 *       balance as a transaction described {@code "BILL PAYMENT - ONLINE"}
 *       ({@code MOVE ACCT-CURR-BAL TO TRAN-AMT} at line&nbsp;224) and reduces the balance by
 *       that amount ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} at
 *       line&nbsp;234, which drives the balance to zero). {@link #transactionId()} then holds
 *       the id of the written transaction and {@link #newBalance()} reflects the post-payment
 *       balance.</li>
 * </ul>
 * <p>Thus {@link #transactionId()} is the discriminator a client uses to tell a completed
 * payment apart from a pure inquiry or a nothing-to-pay result.</p>
 *
 * <p><strong>Available credit.</strong> {@link #availableCredit()} is computed by
 * {@code BillPaymentService} as {@code ACCT-CREDIT-LIMIT - ACCT-CURR-BAL} (credit limit
 * minus current balance), per the AAP {@code dto} folder requirement. The value is supplied
 * by the service; this DTO carries it without recomputation, in keeping with the layered
 * architecture in which the response object holds data only and contains no business logic.</p>
 *
 * <p><strong>Type mapping.</strong> The 11-digit account identifier
 * ({@code ACCT-ID PIC 9(11)}) maps to {@link Long}; the fixed-point money fields
 * ({@code ACCT-CURR-BAL} / {@code ACCT-CREDIT-LIMIT}, both {@code PIC S9(10)V99}) map to
 * {@link java.math.BigDecimal} to reproduce COBOL decimal semantics exactly; and the
 * 16-character transaction identifier maps to {@link String} because it is rendered and
 * compared but never used in arithmetic.</p>
 *
 * <p><strong>Serialization.</strong> Instances are serialized by the application's central
 * Jackson configuration, which writes the monetary {@link java.math.BigDecimal} fields as
 * JSON numbers scaled to two decimal places. The type is annotated with
 * {@link JsonInclude}({@link JsonInclude.Include#NON_NULL NON_NULL}) so that the optional
 * {@code transactionId} is omitted entirely from the JSON when {@code null} (the inquiry /
 * nothing-to-pay outcome), keeping the contract clean for clients that branch on its
 * presence. This record contains no sensitive cardholder data &mdash; no CVV, no SSN, no
 * password &mdash; so no field-level suppression applies.</p>
 *
 * <p>Being a Java {@code record}, this type is immutable and inherently thread-safe; the
 * compiler supplies the canonical constructor, the component accessors, and value-based
 * {@code equals}, {@code hashCode}, and {@code toString} implementations.</p>
 *
 * @param accountId       the 11-digit account identifier whose balance was inquired or paid
 *                        &mdash; COBOL {@code ACCT-ID PIC 9(11)}
 * @param currentBalance  the account balance <em>before</em> any payment (the value the
 *                        legacy screen showed in {@code CURBAL}) &mdash; COBOL
 *                        {@code ACCT-CURR-BAL PIC S9(10)V99}
 * @param availableCredit the available credit, computed as
 *                        {@code creditLimit - currentBalance} &mdash; COBOL
 *                        {@code ACCT-CREDIT-LIMIT - ACCT-CURR-BAL PIC S9(10)V99}
 * @param transactionId   the identifier of the {@code "BILL PAYMENT - ONLINE"} transaction
 *                        that was written, or {@code null} for a pure balance inquiry or a
 *                        nothing-to-pay outcome &mdash; COBOL {@code TRAN-ID PIC X(16)}
 * @param newBalance      the account balance <em>after</em> the payment; equals
 *                        {@code currentBalance} when no payment occurred &mdash; COBOL
 *                        {@code ACCT-CURR-BAL} after
 *                        {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillPaymentResponse(
        Long accountId,             // ACCT-ID                          PIC 9(11)
        BigDecimal currentBalance,  // ACCT-CURR-BAL                    PIC S9(10)V99 (balance before payment)
        BigDecimal availableCredit, // ACCT-CREDIT-LIMIT - ACCT-CURR-BAL  (credit limit minus current balance)
        String transactionId,       // TRAN-ID of "BILL PAYMENT - ONLINE" txn; null if none written
        BigDecimal newBalance       // ACCT-CURR-BAL after payment; == currentBalance when no payment occurred
) {
}
