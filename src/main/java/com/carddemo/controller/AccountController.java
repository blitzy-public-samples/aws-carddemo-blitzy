package com.carddemo.controller;

import com.carddemo.dto.AccountResponse;
import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.service.AccountService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * REST controller exposing the CardDemo <strong>account view</strong> and
 * <strong>account maintenance</strong> contract &mdash; the Spring Boot re-expression of two legacy
 * CICS online programs whose 3270 presentation tier has been retired in favour of a stateless
 * REST/JSON surface:
 * <ul>
 *   <li>{@code app/cbl/COACTVWC.cbl} ("Account View", transaction {@code CAVW}) &mdash; given an
 *       11-digit account id it reads the {@code ACCTDAT} record, resolves the owning customer through
 *       the card cross-reference ({@code CXACAIX} alternate index by account), reads {@code CUSTDAT},
 *       and renders the combined account&nbsp;+&nbsp;customer detail on a single panel (BMS map
 *       {@code COACTVW}).</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} ("Account Update", transaction {@code CAUP}) &mdash; a large
 *       field-validation program that edits account status, credit/cash limits, cycle credit/debit,
 *       current balance, FICO, dates, SSN and phone, performs change-detection, then executes
 *       {@code READ ... UPDATE} + {@code REWRITE} with file-status checking; a record altered
 *       underneath the editor trips the lost-update guard
 *       ("Record changed by some one else. Please review", COACTUPC&nbsp;L522) (BMS map
 *       {@code COACTUP}).</li>
 * </ul>
 *
 * <h2>Resource model</h2>
 * <p>The account is the addressable resource: its 11-digit identifier is a path variable, giving the
 * two operations the base path {@code /accounts/{accountId}}. No {@code /api} prefix is used, matching
 * the application-wide routing convention. The sibling {@code BillPaymentController} maps the distinct
 * sub-resource {@code /accounts/{accountId}/bill-payment}; the differing suffix means the two
 * controllers never collide.</p>
 *
 * <h2>Endpoints (one operation per legacy program)</h2>
 * <ul>
 *   <li>{@code GET /accounts/{accountId}} &mdash; the read-only <em>account view</em> ({@code COACTVWC}
 *       / {@code CAVW}): returns the flattened account&nbsp;+&nbsp;customer detail with HTTP&nbsp;200.</li>
 *   <li>{@code PUT /accounts/{accountId}} &mdash; the state-changing <em>account update</em>
 *       ({@code COACTUPC} / {@code CAUP}): applies the validated, optimistically-locked edit and returns
 *       the new state with HTTP&nbsp;200.</li>
 * </ul>
 *
 * <h2>Thin-controller contract</h2>
 * <p>This controller is intentionally a pure boundary adapter: it binds the request, delegates to
 * {@link AccountService}, and returns the service's {@link AccountResponse} with HTTP&nbsp;200. It holds
 * <strong>no</strong> business logic. All field validation, the customer aggregation (the
 * cross-reference&nbsp;&rarr;&nbsp;customer read chain), the change-detection, the optimistic-locking
 * guard, and all persistence live in {@link AccountService} together with the JPA {@code @Version}
 * column on the {@code Account} entity (AAP&nbsp;&sect;0.6.6). Declarative input validation is supplied
 * by the Bean Validation constraints on {@link AccountUpdateRequest}, triggered here by {@code @Valid}.
 * The account identifier is taken exclusively from the URL path &mdash; {@link AccountUpdateRequest}
 * deliberately carries no account id &mdash; mirroring the immutable VSAM primary key {@code ACCT-ID}.</p>
 *
 * <h2>Status codes and error handling</h2>
 * <p>Both endpoints return HTTP&nbsp;200 on success. Error outcomes are raised as typed domain
 * exceptions by the service (or by the framework during request binding) and translated to HTTP status
 * codes by the application's {@code GlobalExceptionHandler @RestControllerAdvice}, so this class performs
 * <strong>no</strong> exception handling of its own:</p>
 * <ul>
 *   <li>Unknown account, missing cross-reference, or missing customer &rarr;
 *       {@code ResourceNotFoundException} &rarr; HTTP&nbsp;404 (reproducing
 *       "Did not find this account in account master file").</li>
 *   <li>Concurrent modification / stale optimistic-lock version on update &rarr; the CardDemo domain
 *       {@code ConcurrentModificationException} (or Spring's
 *       {@code ObjectOptimisticLockingFailureException} from the {@code @Version} flush-race backstop)
 *       &rarr; HTTP&nbsp;409 (reproducing the legacy lost-update guard). This is owned by the central
 *       handler and is <strong>not</strong> handled here.</li>
 *   <li>Bean Validation failure on the update body ({@code @Valid}) &rarr;
 *       {@code MethodArgumentNotValidException} &rarr; HTTP&nbsp;400 with field-level error detail.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>No method-level {@code @PreAuthorize} is declared: per the legacy behaviour, any authenticated user
 * may view or update an account (admin gating applies only to user-management). Authentication is
 * enforced globally by the security filter chain ({@code SecurityConfig.anyRequest().authenticated()}),
 * so a missing or invalid JWT yields HTTP&nbsp;401 before this controller is reached.</p>
 *
 * <h2>PII</h2>
 * <p>This controller never logs the request or the response. The returned {@link AccountResponse}
 * already restricts the Social Security Number to its last four digits ({@code ssnLastFour}) and never
 * carries a full SSN; the inbound full SSN on {@link AccountUpdateRequest} is input-only (its
 * {@code toString()} is redacted) and is never echoed (AAP&nbsp;&sect;0.6.8). No SSN is reconstructed,
 * read, or logged here.</p>
 *
 * <p>The single collaborator is supplied by constructor injection into a {@code final} field (the
 * idiomatic replacement for the COBOL {@code CALL}/{@code XCTL} static linkage), keeping the controller
 * immutable, thread-safe, and trivially testable with a mocked {@link AccountService}.</p>
 *
 * @see AccountService
 * @see AccountResponse
 * @see AccountUpdateRequest
 * @see <a href="file:app/cbl/COACTVWC.cbl">app/cbl/COACTVWC.cbl (Account View, tran CAVW)</a>
 * @see <a href="file:app/cbl/COACTUPC.cbl">app/cbl/COACTUPC.cbl (Account Update, tran CAUP)</a>
 * @see <a href="file:app/bms/COACTVW.bms">app/bms/COACTVW.bms (retired 3270 view screen)</a>
 * @see <a href="file:app/bms/COACTUP.bms">app/bms/COACTUP.bms (retired 3270 update screen)</a>
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    /**
     * The account business service that holds all behaviour ported from {@code COACTVWC} (view) and
     * {@code COACTUPC} (update) &mdash; lookups, customer aggregation, field edits, optimistic locking,
     * and persistence. Injected via the constructor and held {@code final} so this controller is
     * immutable and thread-safe.
     */
    private final AccountService accountService;

    /**
     * Creates the controller with its single collaborator.
     *
     * <p>Spring autowires the sole constructor without an explicit {@code @Autowired} annotation,
     * supplying the singleton {@link AccountService} bean.</p>
     *
     * @param accountService the account service to which both endpoints delegate; must not be
     *                       {@code null}
     */
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Account <strong>view</strong> &mdash; the Spring re-expression of {@code COACTVWC} (transaction
     * {@code CAVW}).
     *
     * <p>Returns the flattened account&nbsp;+&nbsp;customer detail for the supplied account id. The
     * service reads the account, resolves the owning customer through the card cross-reference, reads the
     * customer, and flattens all three into a single {@link AccountResponse} (with the SSN masked to its
     * last four digits and the optimistic-lock {@code version} echoed for a subsequent update). A missing
     * account &mdash; or a missing cross-reference / customer along the read chain &mdash; causes the
     * service to raise {@code ResourceNotFoundException}, surfaced as HTTP&nbsp;404 by the
     * {@code GlobalExceptionHandler}.</p>
     *
     * @param accountId the account identifier ({@code ACCT-ID}) from the request path
     * @return HTTP&nbsp;200 with the flattened, PII-suppressed {@link AccountResponse}
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable Long accountId) {
        return ResponseEntity.ok(accountService.getAccount(accountId));
    }

    /**
     * Account <strong>update</strong> &mdash; the Spring re-expression of {@code COACTUPC} (transaction
     * {@code CAUP}).
     *
     * <p>Applies the validated, editable account&nbsp;+&nbsp;customer fields of the request body under
     * optimistic locking and returns the new state. The account id is bound from the URL path (the body
     * carries none); the body is validated by Bean Validation via {@code @Valid} before delegation, so a
     * constraint violation is rejected with HTTP&nbsp;400. The service performs the change-detection and
     * the optimistic-locked update: a stale {@code version} (or a {@code @Version} flush race) raises the
     * concurrency signal that the {@code GlobalExceptionHandler} maps to HTTP&nbsp;409 (the lost-update
     * guard), and an unknown account raises {@code ResourceNotFoundException} &rarr; HTTP&nbsp;404. None
     * of these outcomes is handled here.</p>
     *
     * @param accountId the account identifier ({@code ACCT-ID}) from the request path
     * @param request   the validated combined account&nbsp;+&nbsp;customer edit ({@link AccountUpdateRequest}),
     *                  including the optimistic-lock {@code version}; carries no account id
     * @return HTTP&nbsp;200 with the updated, PII-suppressed {@link AccountResponse} reflecting the new
     *         state and the incremented {@code version}
     */
    @PutMapping("/{accountId}")
    public ResponseEntity<AccountResponse> updateAccount(@PathVariable Long accountId,
                                                         @Valid @RequestBody AccountUpdateRequest request) {
        return ResponseEntity.ok(accountService.updateAccount(accountId, request));
    }
}
