package com.carddemo.controller;

import com.carddemo.dto.account.AccountDto;
import com.carddemo.service.AccountService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account view and update REST endpoints &mdash; the stateless replacement for the two
 * legacy CICS online account programs:
 * <ul>
 *   <li>{@code app/cbl/COACTVWC.cbl} (account view, TRANID {@code CAVW}) &mdash; reads
 *       {@code ACCTDAT} via the card cross-reference ({@code CXACAIX}) existence check, then
 *       the keyed account-master read;</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} (account update, TRANID {@code CAUP}) &mdash; rewrites
 *       {@code ACCTDAT} after extensive field editing with a {@code WS-DATACHANGED-FLAG}
 *       change-detection pass and a VSAM {@code READ UPDATE}/{@code REWRITE} race check.</li>
 * </ul>
 *
 * <p>The COBOL record layout is the 300-byte {@code ACCOUNT-RECORD} from
 * {@code app/cpy/CVACT01Y.cpy} (12 user fields + a 178-byte FILLER that is not exposed).</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/accounts/{acctId}} &mdash; retrieve one account by its 11-digit
 *       primary key; {@code 200 OK} with the {@link AccountDto} body on success.</li>
 *   <li>{@code PUT /api/accounts/{acctId}} &mdash; update an existing account; {@code 200 OK}
 *       with the refreshed {@link AccountDto} on success. Optimistic locking via JPA
 *       {@code @Version} surfaces concurrent modifications as {@code 409 Conflict}.</li>
 * </ul>
 *
 * <h2>Thin HTTP boundary (business logic lives in {@link AccountService})</h2>
 * <p>This controller is intentionally a thin, stateless HTTP adapter. It performs only the
 * path-variable shape validation ({@code @Min}/{@code @Max} mirroring {@code ACCT-ID PIC
 * 9(11)}) and request-body trigger ({@code @Valid}); all account business semantics &mdash;
 * the COACTVWC two-step lookup, the COACTUPC change-detection and optimistic-lock handling,
 * and every preserved COBOL diagnostic message literal &mdash; live in {@link AccountService}.
 * The exact COBOL messages are surfaced to clients by
 * {@code com.carddemo.controller.advice.GlobalExceptionHandler}, which maps each application
 * and framework exception onto the uniform error contract:</p>
 * <ul>
 *   <li>{@code "Account number must be a non zero 11 digit number"} &mdash; the
 *       {@code @Min(1)}/{@code @Max(99999999999L)} violation message (COACTVWC L126/L128,
 *       COACTUPC L494/L496) raises {@code ConstraintViolationException} &rarr; {@code 400}.</li>
 *   <li>{@code "Did not find this account in account master file"} /
 *       {@code "Did not find this account in account card xref file"} (COACTVWC L132/L130)
 *       are carried by {@code AccountNotFoundException} (COBOL validation code 101) &rarr;
 *       {@code 404}.</li>
 *   <li>{@code "Record changed by some one else. Please review"} (COACTUPC L522) is carried
 *       by {@code ObjectOptimisticLockingFailureException} &rarr; {@code 409} (PR-22).</li>
 *   <li>A non-numeric path segment fails {@code Long} conversion and is mapped to
 *       {@code 400} via {@code MethodArgumentTypeMismatchException}; an unexpected data-access
 *       failure ({@code "Error reading account file"}) is mapped to {@code 500}.</li>
 * </ul>
 *
 * <h2>Authorization</h2>
 * <p>Intentionally <strong>no</strong> class-level {@code @PreAuthorize}: any
 * <em>authenticated</em> caller (USER or ADMIN) may read and update account data, exactly as
 * the legacy account-view and account-update transactions were reachable from the regular
 * user menu. The requirement that the caller be authenticated at all is enforced by the
 * application {@code SecurityFilterChain} (see {@code com.carddemo.security.SecurityConfig});
 * anonymous requests are rejected with {@code 401} before reaching these methods. Any
 * finer-grained, per-record authorization (e.g. account ownership) is a service-layer concern
 * and is not part of the legacy behavior, so it is not introduced here (AAP &sect;0.7.2
 * &mdash; no feature additions).</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; {@code acctId} validated as an 11-digit non-zero numeric
 *       ({@code @Min(1)}/{@code @Max(99999999999L)}) mirroring {@code ACCT-ID PIC 9(11)};
 *       the value fits in a signed 64-bit {@link Long}.</li>
 *   <li><b>PR-14</b> &mdash; the misspelled COBOL field {@code ACCT-EXPIRAION-DATE} [sic] is
 *       the correctly spelled {@code expirationDate} on {@link AccountDto}; the typo lives
 *       only in the original {@code .cbl} source and never propagates to the API.</li>
 *   <li><b>PR-16</b> &mdash; all monetary fields on {@link AccountDto} are
 *       {@link java.math.BigDecimal} (never {@code float}/{@code double}); the controller
 *       forwards the DTO verbatim.</li>
 *   <li><b>PR-22</b> &mdash; the {@code Account} entity carries {@code @Version}; a concurrent
 *       update is surfaced as {@code ObjectOptimisticLockingFailureException} and mapped to
 *       {@code 409 Conflict} by {@code GlobalExceptionHandler}.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 namespace only ({@code jakarta.validation.*}); no
 *       {@code javax.*} imports.</li>
 *   <li><b>PR-29</b> &mdash; constructor injection only, via Lombok
 *       {@link RequiredArgsConstructor} over the {@code final} {@link AccountService} field;
 *       no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>Why only {@code GET}/{@code PUT}:</b> per AAP &sect;0.4.1.1 only account view and
 *       update are in scope. The legacy COBOL had no account-deletion flow, so no
 *       {@code DELETE} endpoint is added (no feature additions).</li>
 *   <li><b>Why no {@code AccountMapper} here:</b> {@link AccountService} returns
 *       {@link AccountDto} directly (it uses the mapper internally), so this controller works
 *       only with DTOs and never touches the JPA entity. Mapping is the service's
 *       responsibility; the controller is presentation/HTTP only (AAP &sect;0.4.2).</li>
 *   <li><b>Customer info separation:</b> COACTVWC bundled account and customer fields on one
 *       BMS screen. The REST surface separates them: a client calls
 *       {@code GET /api/accounts/{acctId}} and then {@code GET /api/customers/{custId}}
 *       independently, so each REST resource stays cohesive.</li>
 *   <li><b>Cross-resource navigation removed:</b> COACTVWC used {@code EXEC CICS XCTL} to
 *       chain to COCRDLIC/COCRDSLC/COCRDUPC based on PF-key input; REST is stateless and the
 *       client controls navigation, so no server-side program chaining exists here.</li>
 *   <li><b>Path is authoritative:</b> for {@code PUT}, the {@code acctId} path variable
 *       identifies the account to update; any {@code acctId} present on the request body is
 *       ignored by {@link AccountService} (the primary key is immutable).</li>
 * </ul>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (CVACT01Y account record layout).
 *
 * @see AccountService the service that performs the COACTVWC lookup and COACTUPC update
 * @see AccountDto the account view/update payload (response body and update request body)
 * @see com.carddemo.controller.advice.GlobalExceptionHandler exception-to-HTTP-status mapping
 * @since 1.0
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Account", description = "Account view and update endpoints (replaces COACTVWC, COACTUPC)")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    /**
     * Account business service replacing the COACTVWC (view) and COACTUPC (update) online
     * programs. Injected by type through the Lombok-generated constructor (PR-29).
     * {@link AccountService#getAccount(Long)} performs the cross-reference existence check and
     * keyed account-master read; {@link AccountService#updateAccount(Long, AccountDto)}
     * performs the change-detected, optimistically-locked rewrite. Both return an
     * {@link AccountDto}, so this controller never accesses the JPA entity directly.
     */
    private final AccountService accountService;

    /**
     * Retrieves a single account by its 11-digit primary key and returns the
     * {@link AccountDto}.
     *
     * <p>This is the REST replacement for the account-view flow of
     * {@code app/cbl/COACTVWC.cbl} (TRANID {@code CAVW}). The lookup order, not-found handling,
     * and all preserved COBOL message literals are delegated entirely to
     * {@link AccountService#getAccount(Long)}:</p>
     * <ul>
     *   <li>account found ({@code DFHRESP(NORMAL)} on the {@code ACCTDAT} read) &rarr;
     *       {@code 200 OK} with the {@link AccountDto} body;</li>
     *   <li>account absent from the card cross-reference &rarr; the service throws
     *       {@code AccountNotFoundException} with the exact COBOL message
     *       {@code "Did not find this account in account card xref file"} (COACTVWC L130),
     *       mapped to {@code 404 Not Found};</li>
     *   <li>account absent from the master file ({@code DFHRESP(NOTFND)}) &rarr; the service
     *       throws {@code AccountNotFoundException} (COBOL validation code 101) with the exact
     *       message {@code "Did not find this account in account master file"} (COACTVWC L132),
     *       mapped to {@code 404 Not Found};</li>
     *   <li>an account-file read error &rarr; data-access failure mapped to {@code 500
     *       Internal Server Error}.</li>
     * </ul>
     *
     * <p>Validation runs during argument binding (PR-13): the class-level {@code @Validated}
     * causes the {@link Min @Min(1)} / {@link Max @Max(99999999999L)} constraints to be
     * enforced, so a zero, negative, or greater-than-11-digit {@code acctId} is rejected with
     * {@code 400 Bad Request} (via {@code ConstraintViolationException}) and the exact COBOL
     * message {@code "Account number must be a non zero 11 digit number"} (COACTVWC L126); a
     * non-numeric path segment is rejected with {@code 400} (via
     * {@code MethodArgumentTypeMismatchException}) &mdash; both before the service is invoked.</p>
     *
     * <p>Only the {@code acctId} is logged, at {@code DEBUG}. The {@link AccountDto} body
     * carries no PII (customer data is exposed separately by {@code CustomerController}), so
     * the account log statements are safe.</p>
     *
     * @param acctId the account primary key, mirroring COBOL {@code ACCT-ID PIC 9(11)}; must
     *               be a non-zero 11-digit number ({@code 1 .. 99999999999})
     * @return {@code 200 OK} with the account as an {@link AccountDto} (monetary fields scale
     *         2, dates ISO {@code yyyy-MM-dd})
     */
    @GetMapping("/{acctId}")
    @Operation(
            summary = "Get account by ID",
            description = "Retrieves an account record by 11-digit account ID. "
                    + "Returns account details including credit limit, current balance, "
                    + "open/expiration dates, and cycle credit/debit totals. "
                    + "Replaces COACTVWC CICS account view (TRANID=CAVW).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "400", description = "Invalid account ID format: 'Account number must be a non zero 11 digit number'"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Account not found: 'Did not find this account in account master file'"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<AccountDto> getAccount(
            @Parameter(description = "11-digit non-zero numeric account ID", example = "00000000001")
            @PathVariable("acctId")
            @Min(value = 1L, message = "Account number must be a non zero 11 digit number")
            @Max(value = 99999999999L, message = "Account number must be a non zero 11 digit number")
            Long acctId) {

        // Log the path variable only (DEBUG). The AccountDto body carries no PII, so the
        // statement is safe; customer PII is served separately by CustomerController.
        log.debug("GET /api/accounts/{}", acctId);

        // Delegate to the service, which performs the COACTVWC cross-reference existence check
        // and keyed ACCTDAT read, throws AccountNotFoundException on a not-found account
        // (-> 404), and returns an AccountDto. The controller forwards it unchanged.
        AccountDto account = accountService.getAccount(acctId);
        return ResponseEntity.ok(account);
    }

    /**
     * Updates an existing account identified by its 11-digit primary key and returns the
     * refreshed {@link AccountDto}.
     *
     * <p>This is the REST replacement for the account-update flow of
     * {@code app/cbl/COACTUPC.cbl} (TRANID {@code CAUP}). The change-detection,
     * field-level editing, and VSAM {@code READ UPDATE}/{@code REWRITE} race-condition
     * handling are delegated entirely to
     * {@link AccountService#updateAccount(Long, AccountDto)}, which reproduces the COACTUPC
     * {@code WS-DATACHANGED-FLAG} semantics:</p>
     * <ul>
     *   <li>each supplied field is compared against the persisted value and applied only when
     *       it differs (monetary fields compared via {@code BigDecimal.compareTo}, PR-16);</li>
     *   <li>active status restricted to {@code Y}/{@code N} (COACTUPC L504) &mdash; an invalid
     *       value raises {@code IllegalArgumentException}
     *       {@code "Account Active Status must be Y or N"}, mapped to {@code 400};</li>
     *   <li>a malformed date raises {@code IllegalArgumentException}, mapped to {@code 400};</li>
     *   <li>when no field changed, {@code IllegalStateException}
     *       {@code "No change detected with respect to values fetched."} (COACTUPC L492) is
     *       raised and mapped to {@code 422 Unprocessable Entity};</li>
     *   <li>the account is rewritten with JPA {@code @Version} optimistic locking; a concurrent
     *       modification surfaces as {@code ObjectOptimisticLockingFailureException} carrying
     *       the exact message {@code "Record changed by some one else. Please review"}
     *       (COACTUPC L522), mapped to {@code 409 Conflict} (PR-22).</li>
     * </ul>
     *
     * <p>The {@code acctId} path variable is the source of truth for which account to update;
     * any {@code acctId} present on the request body is ignored by the service (the primary
     * key is immutable). If the account does not exist, the service throws
     * {@code AccountNotFoundException} (COBOL validation code 101), mapped to {@code 404}.</p>
     *
     * <p>Validation runs in two stages: the class-level {@code @Validated} enforces the
     * {@link Min @Min(1)} / {@link Max @Max(99999999999L)} path-variable constraints (PR-13),
     * and {@code @Valid} triggers Jakarta Bean Validation on the {@link AccountDto} body
     * (the {@code @NotNull}/{@code @Size}/{@code @Pattern}/{@code @Digits}/{@code @DecimalMin}
     * constraints declared on the DTO). A body-level violation raises
     * {@code MethodArgumentNotValidException}, mapped to {@code 400 Bad Request} by
     * {@code GlobalExceptionHandler}.</p>
     *
     * <p>Only the {@code acctId} is logged (at {@code INFO}, before and after the update). The
     * request/response bodies are never logged; in particular no password or PII field is
     * present on {@link AccountDto}, so the account log statements are safe.</p>
     *
     * @param acctId  the account primary key to update, mirroring COBOL {@code ACCT-ID PIC
     *                9(11)}; must be a non-zero 11-digit number ({@code 1 .. 99999999999})
     * @param request the desired account field values; {@code null} fields are left unchanged
     *                by the service, mirroring the COACTUPC change-by-change edit
     * @return {@code 200 OK} with the updated account as an {@link AccountDto}
     */
    @PutMapping("/{acctId}")
    @Operation(
            summary = "Update an existing account",
            description = "Updates account fields. Uses JPA @Version optimistic locking; "
                    + "concurrent updates return 409 Conflict with message: "
                    + "'Record changed by some one else. Please review'. "
                    + "Replaces COACTUPC CICS account update (TRANID=CAUP).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error (phone format, SSN parts, Y/N flags, date format)"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Account not found"),
            @ApiResponse(responseCode = "409", description = "Optimistic lock conflict: another user updated this account"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<AccountDto> updateAccount(
            @Parameter(description = "11-digit non-zero numeric account ID", example = "00000000001")
            @PathVariable("acctId")
            @Min(value = 1L, message = "Account number must be a non zero 11 digit number")
            @Max(value = 99999999999L, message = "Account number must be a non zero 11 digit number")
            Long acctId,
            @Valid @RequestBody AccountDto request) {

        // Log the path variable only (INFO). Request/response bodies are never logged; the
        // path acctId is authoritative and the body's acctId (if any) is ignored by the service.
        log.info("PUT /api/accounts/{} updating account", acctId);

        // Delegate to the service, which applies the COACTUPC change-detection edit, performs
        // the optimistically-locked REWRITE (-> 409 on conflict), throws AccountNotFoundException
        // when the account is absent (-> 404), and returns the refreshed AccountDto.
        AccountDto updated = accountService.updateAccount(acctId, request);
        log.info("PUT /api/accounts/{} update successful", acctId);
        return ResponseEntity.ok(updated);
    }
}
