package com.carddemo.controller;

import com.carddemo.dto.billpayment.BillPaymentRequest;
import com.carddemo.dto.billpayment.BillPaymentResponse;
import com.carddemo.service.BillPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Online bill-payment REST endpoint &mdash; the stateless replacement for the CICS online program
 * {@code app/cbl/COBIL00C.cbl} (TRANID {@code CB00}, "Bill Payment").
 *
 * <p>COBIL00C lets a cardholder pay down an account's current balance: it posts a single payment
 * {@code Transaction} against the account's card and reduces {@code ACCT-CURR-BAL} by the paid
 * amount. This controller is a thin, stateless HTTP adapter; the entire COBIL00C flow &mdash;
 * account read ({@code READ-ACCTDAT-FILE}), the nothing-to-pay guard
 * ({@code IF ACCT-CURR-BAL <= ZEROS}, COBIL00C L198-201), the confirmation guard
 * ({@code IF CONF-PAY-YES}, L210/L237), card resolution ({@code READ-CXACAIX-FILE}, L211), the
 * transaction write ({@code WRITE-TRANSACT-FILE}, L218-233), and the atomic balance reduction
 * ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}, L234) &mdash; lives in
 * {@link BillPaymentService}.</p>
 *
 * <h2>Endpoint</h2>
 * <ul>
 *   <li>{@code POST /api/accounts/{acctId}/payments} &mdash; process a bill payment for the account;
 *       {@code 200 OK} with a {@link BillPaymentResponse} describing the posted transaction id, the
 *       processed amount, the previous and new balances, the resulting available credit, the
 *       26-character DB2 {@code processedAt} timestamp, and the verbatim COBIL00C success message
 *       {@code "Payment successful.  Your Transaction ID is <id>."} (COBIL00C L527-530).</li>
 * </ul>
 *
 * <h2>Request-body / path consistency</h2>
 * <p>The {@code acctId} <em>path variable is authoritative</em>; it is the account the payment is
 * applied to. The {@link BillPaymentRequest} also carries an {@code accountId} field whose own
 * {@code @Schema} states "This value should match the {acctId} URL path parameter &mdash;
 * BillPaymentController will validate consistency." Accordingly, when the body supplies a non-null
 * {@code accountId} that differs from the path value, this controller rejects the request with
 * {@link IllegalArgumentException} &rarr; {@code 400 Bad Request} (via {@code GlobalExceptionHandler})
 * rather than silently trusting one over the other. {@link BillPaymentService} documents and relies
 * on this controller-side consistency check.</p>
 *
 * <h2>Exception &rarr; HTTP status mapping (via {@code GlobalExceptionHandler})</h2>
 * <ul>
 *   <li>{@code AccountNotFoundException} (account or card cross-reference absent, COBIL00C
 *       {@code "Account ID NOT found..."}, L361/L392/L425) &rarr; {@code 404 Not Found};</li>
 *   <li>{@code IllegalStateException} (balance not positive, COBIL00C {@code "You have nothing to
 *       pay..."}, L201; or unconfirmed, COBIL00C {@code "Confirm to make a bill payment..."}, L237)
 *       &rarr; {@code 422 Unprocessable Entity};</li>
 *   <li>{@link IllegalArgumentException} (body/path account-id mismatch) and bean-validation
 *       failures on the {@code @Valid} body (e.g. the COBIL00C {@code WHEN OTHER} "Invalid value.
 *       Valid values are (Y/N)..." edit, L187) &rarr; {@code 400 Bad Request};</li>
 *   <li>{@code ObjectOptimisticLockingFailureException} (concurrent account update) &rarr;
 *       {@code 409 Conflict} (PR-22);</li>
 *   <li>a non-numeric {@code acctId} path segment &rarr; {@code 400} via
 *       {@code MethodArgumentTypeMismatchException}.</li>
 * </ul>
 *
 * <h2>Authorization</h2>
 * <p>Intentionally <strong>no</strong> class-level {@code @PreAuthorize}: any <em>authenticated</em>
 * caller (USER or ADMIN) may make a bill payment, exactly as COBIL00C was reachable from the regular
 * user menu ({@code COMEN01C}). Authentication is enforced by the application
 * {@code SecurityFilterChain} ({@code com.carddemo.security.SecurityConfig}); anonymous requests are
 * rejected with {@code 401} before reaching this method (AAP &sect;0.7.2 &mdash; no feature
 * additions). The {@code @SecurityRequirement(name = "bearerAuth")} documents the bearer-token
 * requirement in the generated OpenAPI contract.</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; {@code acctId} validated as an 11-digit non-zero numeric
 *       ({@code @Min(1)}/{@code @Max(99999999999L)}) mirroring {@code ACCT-ID PIC 9(11)}
 *       ({@code app/cpy/CVACT01Y.cpy} L5).</li>
 *   <li><b>PR-16</b> &mdash; the controller forwards {@link BillPaymentRequest}/
 *       {@link BillPaymentResponse} verbatim; every monetary field is {@link java.math.BigDecimal}.</li>
 *   <li><b>PR-22</b> / <b>PR-24</b> &mdash; the service performs the transaction write and balance
 *       update in one optimistically-locked {@code @Transactional} unit of work (the CICS
 *       {@code SYNCPOINT} boundary).</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 namespace only ({@code jakarta.validation.*}).</li>
 *   <li><b>PR-29</b> &mdash; constructor injection via Lombok {@link RequiredArgsConstructor} over
 *       the {@code final} {@link BillPaymentService} field; no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (COBIL00C bill-payment program).
 *
 * @see BillPaymentService the service that performs the COBIL00C logic
 * @see BillPaymentRequest the request payload (account id and Y/N confirmation only)
 * @see BillPaymentResponse the response payload (tran id, balances, available credit, message)
 * @see com.carddemo.controller.advice.GlobalExceptionHandler exception-to-HTTP-status mapping
 * @since 1.0
 */
@RestController
@RequestMapping("/api/accounts/{acctId}/payments")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Bill Payment", description = "Online bill payment endpoint (replaces COBIL00C, TRANID=CB00)")
@SecurityRequirement(name = "bearerAuth")
public class BillPaymentController {

    /**
     * Bill-payment business service replacing the COBIL00C online program. Injected by type through
     * the Lombok-generated constructor (PR-29). It returns a {@link BillPaymentResponse}, so this
     * controller never accesses the JPA entities directly &mdash; the {@code @Transactional} unit of
     * work (transaction write + balance update) is owned entirely by the service.
     */
    private final BillPaymentService billPaymentService;

    /**
     * Processes a bill payment for the given account and returns the resulting
     * {@link BillPaymentResponse}.
     *
     * <p>This is the REST replacement for {@code app/cbl/COBIL00C.cbl} (TRANID {@code CB00}). The
     * full COBIL00C flow runs inside
     * {@link BillPaymentService#processBillPayment(Long, BillPaymentRequest)} within a single
     * {@code @Transactional} unit of work (PR-24); the account balance update uses {@code @Version}
     * optimistic locking (PR-22), so a concurrent modification surfaces as
     * {@code ObjectOptimisticLockingFailureException} &rarr; {@code 409 Conflict}.</p>
     *
     * <p>{@code @Valid} triggers Jakarta Bean Validation on the {@link BillPaymentRequest} body
     * before the service runs &mdash; rejecting (with {@code 400}) a confirmation flag other than
     * {@code Y}/{@code N} (mirroring the COBIL00C {@code WHEN OTHER} "Invalid value. Valid values are
     * (Y/N)..." edit at L187). There is no client-supplied amount or payment method: COBIL00C always
     * paid the full balance, so the request carries only the account id and the confirmation flag. The
     * {@code acctId} path variable is authoritative; a mismatching non-null body {@code accountId} is
     * rejected with {@code 400} (see the class JavaDoc).</p>
     *
     * @param acctId  the account primary key from the path, mirroring COBOL {@code ACCT-ID PIC
     *                9(11)} ({@code app/cpy/CVACT01Y.cpy} L5); must be a non-zero 11-digit number
     *                ({@code 1 .. 99999999999}) per PR-13
     * @param request the validated bill-payment request carrying only the {@code Y}/{@code N}
     *                confirmation flag (and an optional body {@code accountId} that must match the
     *                path); there is no client-supplied amount &mdash; the full balance is always paid
     * @return {@code 200 OK} with the {@link BillPaymentResponse} summarizing the posted payment
     */
    @PostMapping
    @Operation(
            summary = "Process a bill payment for an account",
            description = "Posts a payment against the account's current balance. Validates that the "
                    + "account exists, the balance is positive (otherwise: 'You have nothing to "
                    + "pay...' -> 422), and the confirmation flag is 'Y' (otherwise: 'Confirm to make "
                    + "a bill payment...' -> 422). Creates a new payment transaction record and reduces "
                    + "the balance atomically within a single @Transactional scope (PR-24). Mirroring "
                    + "COBIL00C exactly, the payment always pays the FULL current balance (there is no "
                    + "client-supplied amount and no partial/minimum mode), so the balance is reduced to "
                    + "zero. Replaces COBIL00C (TRANID=CB00).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment processed successfully"),
            @ApiResponse(responseCode = "400",
                    description = "Validation error (e.g., 'Invalid value. Valid values are (Y/N)...') "
                            + "or body/path account ID mismatch"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404",
                    description = "Account not found / no card cross-reference ('Account ID NOT found...')"),
            @ApiResponse(responseCode = "409",
                    description = "Optimistic lock conflict: account updated concurrently (PR-22)"),
            @ApiResponse(responseCode = "422",
                    description = "Business rule violation: 'You have nothing to pay...' (balance <= 0) "
                            + "or payment not confirmed ('Confirm to make a bill payment...')"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<BillPaymentResponse> processBillPayment(
            @Parameter(description = "11-digit non-zero numeric account ID", example = "00000000001")
            @PathVariable("acctId")
            @Min(value = 1L, message = "Account number must be a non zero 11 digit number")
            @Max(value = 99999999999L, message = "Account number must be a non zero 11 digit number")
            Long acctId,
            @Valid @RequestBody BillPaymentRequest request) {

        // INFO log of the inbound request. CWE-532: only the non-sensitive accountId surrogate and
        // the Y/N confirmation flag are logged — never any monetary amount, and never the card number
        // (which is resolved inside the service and never logged).
        log.info("POST /api/accounts/{}/payments confirmation={}",
                acctId, request.getConfirmation());

        // The path acctId is authoritative. The BillPaymentRequest.accountId @Schema explicitly
        // states the controller validates consistency, and BillPaymentService's contract relies on
        // it; reject a non-null mismatching body id with 400 (IllegalArgumentException ->
        // GlobalExceptionHandler) rather than silently choosing one over the other.
        if (request.getAccountId() != null && !request.getAccountId().equals(acctId)) {
            throw new IllegalArgumentException(
                    "Account ID in request body does not match the account ID in the URL path");
        }

        // Delegate to the service, which performs the full COBIL00C flow (read account -> nothing-to-
        // pay guard -> confirmation guard -> resolve card -> write transaction -> reduce balance)
        // inside one optimistically-locked @Transactional unit of work and returns the response.
        BillPaymentResponse response = billPaymentService.processBillPayment(acctId, request);

        // INFO log of the outcome: only non-sensitive operational identifiers (accountId surrogate +
        // generated tranId). CWE-532: monetary values (balances, amounts) are never logged.
        log.info("POST /api/accounts/{}/payments completed tranId={}",
                acctId, response.tranId());

        return ResponseEntity.ok(response);
    }
}
