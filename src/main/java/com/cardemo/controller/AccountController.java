/*
 * AccountController.java — Spring REST Controller
 * Source: COACTVWC.cbl (CAVW transaction) + COACTUPC.cbl (CAUP transaction)
 *
 * Combines two CICS transactions into a single REST resource:
 *   CAVW (Account View)   → GET  /api/accounts/{id}
 *   CAUP (Account Update)  → PUT  /api/accounts/{id}
 *
 * COBOL program → Java endpoint mapping:
 *   COACTVWC.cbl 0000-MAIN → getAccount()    — three-file join (XREF→Account→Customer)
 *   COACTUPC.cbl 0000-MAIN → updateAccount() — 25-field validation + optimistic locking
 *
 * This controller is a thin HTTP mapping layer with ZERO business logic.
 * All validation, cross-file reads, and update processing are delegated
 * to the injected service classes.
 *
 * CardDemo v1.0 — Migrated from COBOL/CICS to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.controller;

import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.entity.Account;
import com.cardemo.service.online.AccountUpdateService;
import com.cardemo.service.online.AccountUpdateService.AccountUpdateRequest;
import com.cardemo.service.online.AccountViewService;
import com.cardemo.service.online.AccountViewService.AccountViewResult;

import jakarta.persistence.OptimisticLockException;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for account view and update operations.
 *
 * <p>Combines CICS transactions CAVW (Account View) and CAUP (Account Update)
 * into a single REST resource at {@code /api/accounts}. This controller serves
 * as a thin HTTP mapping layer — all business logic is delegated to:</p>
 * <ul>
 *   <li>{@link AccountViewService} for read-only account view
 *       (← COACTVWC.cbl)</li>
 *   <li>{@link AccountUpdateService} for account updates with validation
 *       (← COACTUPC.cbl)</li>
 * </ul>
 *
 * <h3>Endpoint Summary</h3>
 * <table>
 *   <caption>Available Account Endpoints</caption>
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/accounts/{id}</td><td>View account details</td></tr>
 *   <tr><td>PUT</td><td>/api/accounts/{id}</td><td>Update account</td></tr>
 * </table>
 *
 * <h3>Error Mapping (COBOL → HTTP)</h3>
 * <table>
 *   <caption>Exception to HTTP Status Mapping</caption>
 *   <tr><th>Exception</th><th>HTTP Status</th><th>COBOL Origin</th></tr>
 *   <tr><td>RecordNotFoundException</td><td>404 Not Found</td>
 *       <td>VSAM STATUS '23' / DFHRESP(NOTFND)</td></tr>
 *   <tr><td>ValidationException</td><td>400 Bad Request</td>
 *       <td>1200-EDIT-MAP-INPUTS validation failure</td></tr>
 *   <tr><td>OptimisticLockException</td><td>409 Conflict</td>
 *       <td>READ UPDATE → REWRITE collision</td></tr>
 * </table>
 *
 * @see AccountViewService
 * @see AccountUpdateService
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    /** Read-only account view service (← COACTVWC.cbl, transaction CAVW). */
    private final AccountViewService accountViewService;

    /** Read-write account update service (← COACTUPC.cbl, transaction CAUP). */
    private final AccountUpdateService accountUpdateService;

    /**
     * Constructs the controller with required service dependencies.
     *
     * <p>Uses constructor injection (Spring recommended pattern). Maps to
     * the COBOL architecture where COACTVWC and COACTUPC are separate
     * programs — here combined into a single REST resource with two
     * dedicated service delegates.</p>
     *
     * @param accountViewService   read-only account view service
     *                             (← COACTVWC.cbl)
     * @param accountUpdateService account update service with validation
     *                             (← COACTUPC.cbl)
     */
    public AccountController(AccountViewService accountViewService,
                             AccountUpdateService accountUpdateService) {
        this.accountViewService = accountViewService;
        this.accountUpdateService = accountUpdateService;
    }

    // =========================================================================
    // Endpoint 1: GET /api/accounts/{id}  (← COACTVWC.cbl, CAVW transaction)
    // =========================================================================

    /**
     * Views account details by account ID.
     *
     * <p>Maps to CICS transaction CAVW (COACTVWC.cbl paragraph 0000-MAIN,
     * line 262). Performs a three-file join across Card Cross-Reference
     * (CXACAIX), Account Master (ACCTDAT), and Customer Master (CUSTDAT)
     * to return a consolidated account view.</p>
     *
     * <h3>COBOL Flow Translated</h3>
     * <ol>
     *   <li>9200-GETCARDXREF-BYACCT: Read XREF by account ID
     *       (EXEC CICS READ DATASET('CXACAIX'))</li>
     *   <li>9300-GETACCTDATA-BYACCT: Read account master record
     *       (EXEC CICS READ DATASET('ACCTDAT'))</li>
     *   <li>9400-GETCUSTDATA-BYCUST: Read customer master record
     *       (EXEC CICS READ DATASET('CUSTDAT'))</li>
     * </ol>
     *
     * @param id the account identifier (11-character string, maps to
     *           BMS field ACCTSID PIC X(11) in COACTVW.bms)
     * @return ResponseEntity with {@link AccountViewResult} on success (200),
     *         or error details with 404 (not found) or 400 (bad request)
     */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getAccount(@PathVariable("id") String id) {
        log.debug("GET /api/accounts/{} — request received for account view", id);
        log.info("Initiating account view (CAVW) for accountId={}", id);

        try {
            AccountViewResult result = accountViewService.viewAccount(id);
            log.info("Account view completed successfully for accountId={}", id);
            return ResponseEntity.ok(result);

        } catch (RecordNotFoundException ex) {
            log.warn("Account not found for accountId={}: {}", id, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(buildErrorResponse("Not Found", ex.getMessage()));

        } catch (ValidationException ex) {
            log.warn("Validation failed for account view, accountId={}: field={}, reason={}",
                    id, ex.getFieldName(), ex.getValidationMessage());
            return ResponseEntity.badRequest()
                    .body(buildValidationErrorResponse(ex));
        }
    }

    // =========================================================================
    // Endpoint 2: PUT /api/accounts/{id}  (← COACTUPC.cbl, CAUP transaction)
    // =========================================================================

    /**
     * Updates an account by account ID.
     *
     * <p>Maps to CICS transaction CAUP (COACTUPC.cbl paragraph 0000-MAIN,
     * line 859). Performs comprehensive field validation
     * (1200-EDIT-MAP-INPUTS with sub-paragraphs 1210–1280), change detection
     * (1205-COMPARE-OLD-NEW / 9700-CHECK-CHANGE-IN-REC), and
     * optimistic-locked persistence (9600-WRITE-PROCESSING).</p>
     *
     * <h3>COBOL Flow Translated</h3>
     * <ol>
     *   <li>9000-READ-ACCT: Read current account with lock
     *       (EXEC CICS READ UPDATE → JPA @Version)</li>
     *   <li>1200-EDIT-MAP-INPUTS: Validate 25+ fields (status, dates,
     *       currency amounts, phone, SSN, state, ZIP, FICO)</li>
     *   <li>9700-CHECK-CHANGE-IN-REC: Detect actual data changes</li>
     *   <li>9600-WRITE-PROCESSING: Persist via accountRepository.save()
     *       with @Version optimistic locking check</li>
     * </ol>
     *
     * <h3>Request Body Fields (from COACTUP.bms)</h3>
     * <p>The request maps to BMS screen fields: ACSTTUS (status),
     * OPNYEAR/OPNMON/OPNDAY (open date), ACRDLIM (credit limit),
     * EXPMON/EXPYEAR (expiry), ACURBAL (balance), and customer detail
     * fields (name, address, SSN, phone, FICO).</p>
     *
     * @param id      the account identifier (11-character string, maps to
     *                BMS field ACCTSID PIC X(11) in COACTUP.bms)
     * @param request DTO with all updatable fields from the account update
     *                screen — see {@link AccountUpdateRequest}
     * @return ResponseEntity with updated {@link Account} on success (200),
     *         or error details with 404 (not found), 400 (validation), or
     *         409 (concurrent modification)
     */
    @PutMapping("/{id}")
    public ResponseEntity<Object> updateAccount(
            @PathVariable("id") String id,
            @RequestBody AccountUpdateRequest request) {
        log.debug("PUT /api/accounts/{} — request received for account update", id);
        log.info("Initiating account update (CAUP) for accountId={}", id);

        try {
            Account updatedAccount = accountUpdateService.updateAccount(id, request);
            log.info("Account update completed successfully for accountId={}", id);
            return ResponseEntity.ok(updatedAccount);

        } catch (RecordNotFoundException ex) {
            log.warn("Account not found during update for accountId={}: {}",
                    id, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(buildErrorResponse("Not Found", ex.getMessage()));

        } catch (ValidationException ex) {
            /*
             * Validation failures from 1200-EDIT-MAP-INPUTS — maps to COBOL
             * per-field flags like FLG-ACCT-STATUS-NOT-OK, FLG-YEAR-NOT-OK,
             * FLG-CREDIT-LIMIT-NOT-OK, etc.  Note: PII fields (SSN, govt ID)
             * are NOT logged per security policy.
             */
            log.warn("Validation failed during account update for accountId={}: field={}, reason={}",
                    id, ex.getFieldName(), ex.getValidationMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(buildValidationErrorResponse(ex));

        } catch (OptimisticLockException ex) {
            /*
             * Concurrent modification detected — maps to the COBOL pattern
             * where EXEC CICS READ UPDATE acquires a record lock and
             * EXEC CICS REWRITE commits the change.  In the Java layer,
             * JPA @Version detects concurrent modifications and throws
             * OptimisticLockException when two users update the same
             * account simultaneously.
             */
            log.error("Concurrent modification detected for accountId={}: {}",
                    id, ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(buildErrorResponse("Conflict",
                            "Account was modified by another user. "
                            + "Please refresh and try again."));
        }
    }

    // =========================================================================
    // Private Helpers — Error Response Builders
    // =========================================================================

    /**
     * Builds a standard error response body with error category and message.
     *
     * <p>Returns a deterministic field-order map suitable for JSON
     * serialization. The consistent format allows API consumers to parse
     * error responses uniformly across all endpoints.</p>
     *
     * @param error   the error category (e.g., "Not Found", "Conflict")
     * @param message detailed error description
     * @return map with {@code error} and {@code message} fields
     */
    private Map<String, String> buildErrorResponse(String error, String message) {
        Map<String, String> errorBody = new LinkedHashMap<>();
        errorBody.put("error", error);
        errorBody.put("message", message);
        return errorBody;
    }

    /**
     * Builds a validation error response with field-level detail.
     *
     * <p>Includes the specific field name and validation message when
     * available, mapping to the COBOL per-field validation flags defined
     * in COACTUPC.cbl (e.g., FLG-ACCT-STATUS-NOT-OK, FLG-YEAR-NOT-OK,
     * FLG-CREDIT-LIMIT-NOT-OK, FLG-SSN-NOT-OK). The {@code field} and
     * {@code validationMessage} keys are only present when the exception
     * carries field-specific information.</p>
     *
     * @param ex the validation exception containing field-level details
     * @return map with {@code error}, {@code message}, and optionally
     *         {@code field} and {@code validationMessage} fields
     */
    private Map<String, String> buildValidationErrorResponse(ValidationException ex) {
        Map<String, String> errorBody = new LinkedHashMap<>();
        errorBody.put("error", "Bad Request");
        errorBody.put("message", ex.getMessage());
        String fieldName = ex.getFieldName();
        if (fieldName != null) {
            errorBody.put("field", fieldName);
        }
        String validationMsg = ex.getValidationMessage();
        if (validationMsg != null) {
            errorBody.put("validationMessage", validationMsg);
        }
        return errorBody;
    }
}
