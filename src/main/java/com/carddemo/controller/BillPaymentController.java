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
 * account read, nothing-to-pay guard, confirmation guard, card resolution, transaction write, and
 * the atomic balance reduction &mdash; lives in {@link BillPaymentService}.</p>
 *
 * <h2>Endpoint</h2>
 * <ul>
 *   <li>{@code POST /api/accounts/{acctId}/payments} &mdash; process a bill payment for the account;
 *       {@code 200 OK} with a {@link BillPaymentResponse} describing the posted transaction id, the
 *       processed amount, the previous and new balances, the resulting available credit, the
 *       26-character DB2 {@code processedAt} timestamp, and the verbatim COBIL00C success message
 *       {@code "Payment successful.  Your Transaction ID is <id>."}.</li>
 * </ul>
 *
 * <h2>Request-body / path consistency</h2>
 * <p>The {@code acctId} <em>path variable is authoritative</em>; it is the account the payment is
 * applied to. The {@link BillPaymentRequest} also carries an {@code accountId} field whose own
 * {@code @Schema} states "This value should match the {acctId} URL path parameter &mdash;
 * BillPaymentController will validate consistency." Accordingly, when the body supplies a non-null
 * {@code accountId} that differs from the path value, this controller rejects the request with
 * {@code IllegalArgumentException} &rarr; {@code 400 Bad Request} (via
 * {@code GlobalExceptionHandler}) rather than silently trusting one over the other.</p>
 *
 * <h2>Exception &rarr; HTTP status mapping (via {@code GlobalExceptionHandler})</h2>
 * <ul>
 *   <li>{@code AccountNotFoundException} (account or card cross-reference absent, COBIL00C
 *       {@code "Account ID NOT found..."}) &rarr; {@code 404 Not Found};</li>
 *   <li>{@code IllegalStateException} (balance not positive, COBIL00C {@code "You have nothing to
 *       pay..."}; or unconfirmed, COBIL00C {@code "Confirm to make a bill payment..."}) &rarr;
 *       {@code 422 Unprocessable Entity};</li>
 *   <li>{@code IllegalArgumentException} (body/path account-id mismatch) and bean-validation
 *       failures on the body &rarr; {@code 400 Bad Request};</li>
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
 * additions).</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; {@code acctId} validated as an 11-digit non-zero numeric
 *       ({@code @Min(1)}/{@code @Max(99999999999L)}) mirroring {@code ACCT-ID PIC 9(11)}.</li>
 *   <li><b>PR-16</b> &mdash; the controller forwards {@link BillPaymentRequest}/
 *       {@link BillPaymentResponse} verbatim; all monetary fields are {@link java.math.BigDecimal}.</li>
 *   <li><b>PR-22</b> / <b>PR-24</b> &mdash; the service performs the transaction write and balance
 *       update in one optimistically-locked {@code @Transactional} unit of work.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 namespace only ({@code jakarta.validation.*}).</li>
 *   <li><b>PR-29</b> &mdash; constructor injection via Lombok {@link RequiredArgsConstructor} over
 *       the {@code final} {@link BillPaymentService} field; no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (COBIL00C bill-payment program).
 *
 * @see BillPaymentService the service that performs the COBIL00C logic
 * @see BillPaymentRequest the request payload
 * @see BillPaymentResponse the response payload
 * @see com.carddemo.controller.advice.GlobalExceptionHandler exception-to-HTTP-status mapping
 * @since 1.0
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Bill Payment", description = "Online bill payment endpoint (replaces COBIL00C)")
@SecurityRequirement(name = "bearerAuth")
public class BillPaymentController {

    /**
     * Bill-payment business service replacing the COBIL00C online program. Injected by type through
     * the Lombok-generated constructor (PR-29). It returns a {@link BillPaymentResponse}, so this
     * controller never accesses the JPA entities directly.
     */
    private final BillPaymentService billPaymentService;

    /**
     * Processes a bill payment for the given account and returns the resulting
     * {@link BillPaymentResponse}.
     *
     * <p>This is the REST replacement for {@code app/cbl/COBIL00C.cbl} (TRANID {@code CB00}). The
     * full COBIL00C flow runs inside {@link BillPaymentService#processPayment(Long, BillPaymentRequest)}
     * within a single {@code @Transactional} unit of work (PR-24); the account balance update uses
     * {@code @Version} optimistic locking (PR-22).</p>
     *
     * <p>{@code @Valid} triggers Jakarta Bean Validation on the {@link BillPaymentRequest} body
     * before the service runs &mdash; rejecting (with {@code 400}) a missing amount, a non-positive
     * amount, a {@code paymentMethod} outside {FULL, PARTIAL, MINIMUM}, or a confirmation flag other
     * than {@code Y}/{@code N} (the latter mirrors the COBIL00C {@code WHEN OTHER} "Invalid value"
     * edit). The {@code acctId} path variable is authoritative; a mismatching non-null body
     * {@code accountId} is rejected with {@code 400} (see the class JavaDoc).</p>
     *
     * @param acctId  the account primary key from the path, mirroring COBOL {@code ACCT-ID PIC
     *                9(11)}; must be a non-zero 11-digit number ({@code 1 .. 99999999999})
     * @param request the validated bill-payment request (amount, optional method hint, and the
     *                {@code Y}/{@code N} confirmation flag)
     * @return {@code 200 OK} with the {@link BillPaymentResponse}
     */
    @PostMapping("/{acctId}/payments")
    @Operation(
            summary = "Process a bill payment",
            description = "Pays down an account's current balance: posts a payment transaction and "
                    + "reduces the balance atomically. Default/FULL method pays the full balance "
                    + "(exact COBIL00C behavior); PARTIAL/MINIMUM pays the request amount. "
                    + "Requires confirmation='Y'. Replaces COBIL00C (TRANID=CB00).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment processed successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error or body/path account ID mismatch"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Account not found / no card cross-reference"),
            @ApiResponse(responseCode = "409", description = "Optimistic lock conflict: account updated concurrently"),
            @ApiResponse(responseCode = "422", description = "Nothing to pay (balance <= 0) or payment not confirmed"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<BillPaymentResponse> processPayment(
            @Parameter(description = "11-digit non-zero numeric account ID", example = "00000000001")
            @PathVariable("acctId")
            @Min(value = 1L, message = "Account number must be a non zero 11 digit number")
            @Max(value = 99999999999L, message = "Account number must be a non zero 11 digit number")
            Long acctId,
            @Valid @RequestBody BillPaymentRequest request) {

        log.info("POST /api/accounts/{}/payments processing bill payment", acctId);

        // The path acctId is authoritative. The BillPaymentRequest.accountId @Schema explicitly
        // states the controller validates consistency; reject a non-null mismatching body id with
        // 400 (IllegalArgumentException -> GlobalExceptionHandler) rather than silently choosing one.
        if (request.getAccountId() != null && !request.getAccountId().equals(acctId)) {
            throw new IllegalArgumentException(
                    "Account ID in request body does not match the account ID in the URL path");
        }

        // Delegate to the service, which performs the full COBIL00C flow (read account -> balance
        // guard -> confirmation guard -> resolve card -> write transaction -> reduce balance) inside
        // one optimistically-locked @Transactional unit of work, and returns the BillPaymentResponse.
        BillPaymentResponse response = billPaymentService.processPayment(acctId, request);

        log.info("POST /api/accounts/{}/payments completed; tranId={}", acctId, response.tranId());
        return ResponseEntity.ok(response);
    }
}
