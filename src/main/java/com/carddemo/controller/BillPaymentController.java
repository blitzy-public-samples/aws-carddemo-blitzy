package com.carddemo.controller;

import com.carddemo.dto.BillPaymentRequest;
import com.carddemo.dto.BillPaymentResponse;
import com.carddemo.service.BillPaymentService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * REST controller exposing the CardDemo <strong>bill-payment</strong> contract &mdash; the Spring Boot
 * re-expression of the legacy CICS online program {@code app/cbl/COBIL00C.cbl}
 * ("Bill Payment - Pay account balance in full", transaction {@code CB00}).
 *
 * <p>On the 3270 terminal, {@code COBIL00C} drove the single BMS map {@code COBIL00}: the operator keyed
 * an account id ({@code ACTIDIN}) and the program displayed that account's current balance
 * ({@code CURBAL}); on an explicit {@code CONFIRM = 'Y'} it then paid the balance <em>in full</em> by
 * writing a {@code "BILL PAYMENT - ONLINE"} transaction and reducing the account balance to zero. That
 * one screen therefore expresses two outcomes &mdash; a read-only <em>balance inquiry</em> and a
 * state-changing <em>payment</em> &mdash; which this controller surfaces as two HTTP operations on a
 * single account sub-resource.</p>
 *
 * <h2>Resource model</h2>
 * <p>Bill payment is modeled as a sub-resource of an account: the account identifier is a class-level
 * path variable and the bill-payment verb is the trailing path segment, giving the base path
 * {@code /accounts/{accountId}/bill-payment}. This is a distinct resource from
 * {@code /accounts/{accountId}} (handled by {@code AccountController}); the differing suffix means the two
 * mappings never collide. No {@code /api} prefix is used, matching the application-wide routing
 * convention.</p>
 *
 * <h2>Endpoints (one operation per legacy screen behavior)</h2>
 * <ul>
 *   <li>{@code GET /accounts/{accountId}/bill-payment} &mdash; the balance <em>inquiry</em>: returns the
 *       account's current balance and available credit without posting anything (the
 *       display-only half of {@code COBIL00}).</li>
 *   <li>{@code POST /accounts/{accountId}/bill-payment} &mdash; the <em>payment</em>: with
 *       {@code {"confirm": true}} the account's full balance is paid (the legacy {@code CONFIRM = 'Y'}
 *       path), returning the written transaction id and the post-payment balance.</li>
 * </ul>
 *
 * <h2>Thin-controller contract</h2>
 * <p>This controller is intentionally a pure boundary adapter: it binds the request, delegates to
 * {@link BillPaymentService}, and returns the service's response with HTTP&nbsp;200. It holds
 * <strong>no</strong> business logic &mdash; the balance lookup, available-credit computation,
 * full-balance payment-transaction creation, transaction-id generation, balance decrement, the
 * "nothing to pay" rule (balance&nbsp;&le;&nbsp;0), and the {@code CONFIRM} handling all live in
 * {@link BillPaymentService} (which ports {@code COBIL00C}'s {@code PROCESS-ENTER-KEY} paragraph). Money
 * values flow through as {@link java.math.BigDecimal} and are never rounded or formatted here, preserving
 * the exact COBOL fixed-point semantics across the API boundary.</p>
 *
 * <h2>Status codes and error handling</h2>
 * <p>Both endpoints return HTTP&nbsp;200 on success. Error outcomes are raised as typed domain exceptions
 * by the service and translated to HTTP status codes by the application's
 * {@code GlobalExceptionHandler @RestControllerAdvice}, so no error handling is performed in this class:</p>
 * <ul>
 *   <li>Unknown account &rarr; {@code ResourceNotFoundException} &rarr; HTTP&nbsp;404.</li>
 *   <li>Nothing to pay (current balance&nbsp;&le;&nbsp;0) &rarr; {@code BusinessRuleException} &rarr;
 *       HTTP&nbsp;400 (reproducing "You have nothing to pay...").</li>
 *   <li>Missing/invalid confirmation &rarr; Bean Validation on {@link BillPaymentRequest} (the
 *       {@code @NotNull} {@code confirm} flag) / {@code ValidationException} &rarr; HTTP&nbsp;400
 *       (reproducing "Invalid value. Valid values are (Y/N)...").</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>No method-level {@code @PreAuthorize} is declared: bill payment is available to any authenticated
 * user, mirroring the legacy non-admin transaction. Authentication is enforced globally by the security
 * filter chain ({@code anyRequest().authenticated()}), so a missing or invalid JWT yields HTTP&nbsp;401
 * before the handler is reached.</p>
 *
 * <p>The single collaborator is supplied by constructor injection into a {@code final} field, keeping the
 * controller immutable and trivially testable (a mocked {@link BillPaymentService} can be passed
 * directly).</p>
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see BillPaymentResponse
 * @see <a href="file:app/cbl/COBIL00C.cbl">app/cbl/COBIL00C.cbl (Bill Payment, tran CB00)</a>
 * @see <a href="file:app/bms/COBIL00.bms">app/bms/COBIL00.bms (retired 3270 screen)</a>
 */
@RestController
@RequestMapping("/accounts/{accountId}/bill-payment")
public class BillPaymentController {

    /**
     * The bill-payment business service that holds all behavior ported from {@code COBIL00C}. Injected
     * via the constructor and held {@code final} so this controller is immutable and thread-safe.
     */
    private final BillPaymentService billPaymentService;

    /**
     * Creates the controller with its single collaborator.
     *
     * @param billPaymentService the bill-payment service to which both endpoints delegate; must not be
     *                           {@code null}
     */
    public BillPaymentController(BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    /**
     * Bill-payment <strong>balance inquiry</strong> &mdash; the read-only half of the legacy
     * {@code COBIL00} screen, which displayed {@code ACCT-CURR-BAL}.
     *
     * <p>Returns the account's current balance and available credit; no transaction is written, so the
     * response's {@code transactionId} and {@code newBalance} are {@code null} and (being a
     * {@code @JsonInclude(NON_NULL)} record) {@code transactionId} is omitted from the JSON entirely. An
     * unknown account causes the service to raise {@code ResourceNotFoundException}, surfaced as
     * HTTP&nbsp;404.</p>
     *
     * @param accountId the account identifier from the request path ({@code ACCT-ID}); bound from the
     *                  class-level {@code {accountId}} path variable
     * @return HTTP&nbsp;200 with a {@link BillPaymentResponse} carrying the current balance and available
     *         credit
     */
    @GetMapping
    public ResponseEntity<BillPaymentResponse> getBillPaymentInfo(@PathVariable Long accountId) {
        return ResponseEntity.ok(billPaymentService.getBillPaymentInfo(accountId));
    }

    /**
     * Processes an online bill <strong>payment</strong> &mdash; the state-changing half of the legacy
     * {@code COBIL00} screen, driven by {@code COBIL00C}'s {@code CONFIRM} (Y/N) flow.
     *
     * <p>With {@code {"confirm": true}} the service pays the account's full current balance: it writes the
     * {@code "BILL PAYMENT - ONLINE"} transaction, decrements the balance, and returns the written
     * {@code transactionId} together with the post-payment {@code newBalance}. With {@code confirm == false}
     * no payment is posted (the legacy clear-screen path). The body is validated by Bean Validation: a
     * missing {@code confirm} flag is rejected with HTTP&nbsp;400. The service raises
     * {@code BusinessRuleException} (HTTP&nbsp;400) when the balance is&nbsp;&le;&nbsp;0
     * ("nothing to pay") and {@code ResourceNotFoundException} (HTTP&nbsp;404) when the account is
     * unknown.</p>
     *
     * @param accountId the account identifier from the request path ({@code ACCT-ID}); bound from the
     *                  class-level {@code {accountId}} path variable
     * @param request   the validated confirmation body ({@link BillPaymentRequest}); its {@code confirm}
     *                  flag is mandatory
     * @return HTTP&nbsp;200 with a {@link BillPaymentResponse}; on a confirmed payment it carries the
     *         {@code transactionId} and {@code newBalance}, otherwise those fields are {@code null}
     */
    @PostMapping
    public ResponseEntity<BillPaymentResponse> processBillPayment(@PathVariable Long accountId,
                                                                  @Valid @RequestBody BillPaymentRequest request) {
        return ResponseEntity.ok(billPaymentService.processBillPayment(accountId, request));
    }
}
