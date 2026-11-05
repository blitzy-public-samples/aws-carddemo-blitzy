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
package com.cardemo.controller;

import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.service.online.BillPaymentService;
import com.cardemo.service.online.BillPaymentService.BillPaymentRequest;
import com.cardemo.service.online.BillPaymentService.BillPaymentResult;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for bill payment processing — translates CICS transaction
 * CB00 (COBIL00C.cbl) into a stateless REST endpoint.
 *
 * <p>This controller is the HTTP entry point for the CardDemo bill payment
 * subsystem. It exposes a single endpoint that maps directly to the COBOL
 * bill payment program's user interactions:</p>
 *
 * <h2>Endpoint Mapping (← COBIL00C.cbl)</h2>
 * <table>
 *   <caption>COBIL00C.cbl Endpoint Mapping</caption>
 *   <tr><th>Endpoint</th><th>COBOL Source</th><th>Description</th></tr>
 *   <tr>
 *     <td>{@code POST /api/billing/pay}</td>
 *     <td>MAIN-PARA (line 99) → PROCESS-ENTER-KEY (line 154)</td>
 *     <td>Bill payment processing — validates account, creates payment
 *         transaction, updates account balance</td>
 *   </tr>
 * </table>
 *
 * <h2>BMS Map Reference (COBIL00.bms)</h2>
 * <p>The request body maps to the BMS bill payment screen fields:</p>
 * <ul>
 *   <li>{@code accountId} → ACTIDIN field: {@code PIC X(11)}, account ID
 *       input</li>
 *   <li>{@code confirm} → CONFIRM field: {@code PIC X(1)}, 'Y'/'N'
 *       confirmation flag</li>
 * </ul>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><strong>Delegation only</strong> — All business logic resides in
 *       {@link BillPaymentService}. This controller contains zero business
 *       logic; it only handles HTTP request/response mapping, input
 *       extraction, exception-to-HTTP-status mapping, and response
 *       construction.</li>
 *   <li><strong>Stateless REST</strong> — The CICS pseudo-conversational
 *       model is mapped to stateless HTTP. No session state is stored
 *       in the controller.</li>
 *   <li><strong>BigDecimal for monetary</strong> — All monetary amounts
 *       in request/response use {@code BigDecimal}, never {@code double}
 *       or {@code float}, preserving COBOL COMP-3 decimal precision.</li>
 *   <li><strong>No feature expansion</strong> — Only the bill payment
 *       flow from COBIL00C.cbl is exposed. No endpoints beyond
 *       {@code POST /api/billing/pay}.</li>
 *   <li><strong>Structured logging</strong> — Logs every request with
 *       masked account ID (PII protection) per AAP observability
 *       requirements.</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <ul>
 *   <li>{@link RecordNotFoundException} → HTTP 404 Not Found (maps to
 *       COBOL DFHRESP(NOTFND) on ACCTDAT/CXACAIX reads)</li>
 *   <li>{@link ValidationException} → HTTP 400 Bad Request (invalid
 *       account ID, unconfirmed payment, invalid confirm value)</li>
 *   <li>Unexpected exceptions → HTTP 500 Internal Server Error</li>
 * </ul>
 *
 * <h2>Transaction Semantics</h2>
 * <p>The service layer ({@link BillPaymentService}) handles
 * {@code @Transactional} semantics — CICS SYNCPOINT atomicity equivalent.
 * The controller does not participate in transaction management.</p>
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see BillPaymentResult
 * @see RecordNotFoundException
 * @see ValidationException
 */
@RestController
@RequestMapping("/api/billing")
public class BillPaymentController {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs bill payment requests (with masked account ID for PII
     * protection), processing results, validation failures, and
     * unexpected errors per AAP observability requirements.
     *
     * <p>Maps COBOL {@code DISPLAY} diagnostic statements in
     * COBIL00C.cbl (e.g., {@code DISPLAY 'RESP:' WS-RESP-CD})
     * to structured Java logging.</p>
     */
    private static final Logger logger =
            LoggerFactory.getLogger(BillPaymentController.class);

    /**
     * Number of trailing characters visible when masking account IDs
     * in log output. All leading characters are replaced with '*' to
     * protect PII while preserving traceability.
     */
    private static final int MASK_VISIBLE_CHARS = 4;

    /**
     * Bill payment service encapsulating all business logic translated
     * from COBIL00C.cbl. Injected via constructor — no field injection.
     *
     * <p>The controller delegates all processing to this service's
     * {@link BillPaymentService#processBillPayment(BillPaymentRequest)}
     * method, which handles account lookup, cross-reference validation,
     * transaction ID generation (browse-last), payment transaction
     * creation, and account balance update with BigDecimal arithmetic.</p>
     */
    private final BillPaymentService billPaymentService;

    /**
     * Constructs the {@code BillPaymentController} with the required
     * bill payment service dependency.
     *
     * <p>Uses constructor injection (not {@code @Autowired} field
     * injection) for testability and immutability. The dependency is
     * final and set once at construction time.</p>
     *
     * @param billPaymentService the bill payment service
     *                           (← COBIL00C.cbl business logic)
     */
    public BillPaymentController(BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    /**
     * Processes a bill payment — maps to COBIL00C.cbl MAIN-PARA (line 99)
     * → PROCESS-ENTER-KEY (line 154) flow.
     *
     * <p>This endpoint translates the complete bill payment lifecycle from
     * the COBOL program:</p>
     * <ol>
     *   <li>MAIN-PARA receives control and resolves account ID</li>
     *   <li>PROCESS-ENTER-KEY validates account ID is not empty
     *       (lines 155–157)</li>
     *   <li>READ-ACCTDAT-FILE reads account record (line 343)</li>
     *   <li>Checks balance is positive (lines 163–165)</li>
     *   <li>If confirmed ('Y'): READ-CXACAIX-FILE → STARTBR/READPREV
     *       for ID generation → WRITE-TRANSACT-FILE → UPDATE-ACCTDAT-FILE
     *       (lines 170–237)</li>
     * </ol>
     *
     * <p><strong>BMS Map Input Fields</strong>:</p>
     * <ul>
     *   <li>{@code accountId} → ACTIDIN: {@code PIC X(11)}, the
     *       account identifier</li>
     *   <li>{@code confirm} → CONFIRM: {@code PIC X(1)}, 'Y'/'N'
     *       confirmation flag</li>
     *   <li>{@code action} → EIBAID mapping: action/key identifier
     *       (optional, defaults to ENTER)</li>
     * </ul>
     *
     * <p><strong>Response</strong>: On success, returns HTTP 200 with the
     * {@link BillPaymentResult} containing updated balance, transaction ID,
     * payment amount, and metadata. On error, returns the appropriate HTTP
     * status with an error message.</p>
     *
     * @param request the bill payment request body containing
     *                {@code accountId}, {@code confirm}, and optional
     *                {@code action}
     * @return HTTP 200 with {@link BillPaymentResult} on success;
     *         HTTP 400 with {@code {error: message}} for validation
     *         failures; HTTP 404 for record not found; HTTP 500 for
     *         unexpected errors
     */
    @PostMapping("/pay")
    public ResponseEntity<?> processBillPayment(
            @Valid @RequestBody BillPaymentRequest request) {

        String accountId = request.getAccountId();
        logger.info("Bill payment request received for account: {}",
                maskAccountId(accountId));

        try {
            // Delegate all business logic to BillPaymentService
            // Maps COBIL00C.cbl MAIN-PARA → PROCESS-ENTER-KEY flow
            // The service handles: account lookup, XREF validation,
            // transaction ID generation, payment creation, balance update
            BillPaymentResult result =
                    billPaymentService.processBillPayment(request);

            logger.info("Bill payment processed for account: {}, "
                    + "success: {}", maskAccountId(accountId),
                    result.isSuccess());

            return ResponseEntity.ok(result);

        } catch (RecordNotFoundException e) {
            // Maps to COBOL DFHRESP(NOTFND) (RESP=13) on ACCTDAT or
            // CXACAIX reads → VSAM file status '23'
            // COBIL00C.cbl line 359: 'Account ID NOT found...'
            // COBIL00C.cbl line 424: 'Account ID NOT found...' (XREF)
            logger.warn("Record not found during bill payment for "
                    + "account '{}': {}",
                    maskAccountId(accountId), e.getMessage());
            return ResponseEntity.notFound().build();

        } catch (ValidationException e) {
            // Maps to COBOL input validation failures:
            //   - Empty account ID (line 161): 'Acct ID can NOT be empty'
            //   - Invalid confirm value (line 187): 'Invalid value...'
            //   - Zero balance (line 201): 'You have nothing to pay...'
            logger.warn("Validation failed for bill payment on "
                    + "account '{}': {}",
                    maskAccountId(accountId), e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            // Catch-all for unexpected errors not covered by business
            // exceptions. Maps to unhandled RESP codes in the COBOL
            // EVALUATE blocks (WHEN OTHER paths).
            logger.error("Unexpected error during bill payment for "
                    + "account '{}'",
                    maskAccountId(accountId), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * Masks an account ID for secure logging (PII protection).
     *
     * <p>Replaces all but the last {@value #MASK_VISIBLE_CHARS} characters
     * with asterisks to prevent account ID exposure in log files while
     * preserving enough information for debugging and traceability.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code "12345678901"} → {@code "*******8901"}</li>
     *   <li>{@code "1234"} → {@code "****"} (too short to mask)</li>
     *   <li>{@code null} → {@code "****"}</li>
     * </ul>
     *
     * @param accountId the account ID to mask (may be {@code null})
     * @return the masked account ID string
     */
    private static String maskAccountId(String accountId) {
        if (accountId == null || accountId.length() <= MASK_VISIBLE_CHARS) {
            return "****";
        }
        int maskLength = accountId.length() - MASK_VISIBLE_CHARS;
        return "*".repeat(maskLength)
                + accountId.substring(maskLength);
    }
}
