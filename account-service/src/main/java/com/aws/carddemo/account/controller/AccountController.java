/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.account.controller;

import com.aws.carddemo.account.dto.AccountResponse;
import com.aws.carddemo.account.dto.AccountUpdateRequest;
import com.aws.carddemo.account.exception.ApiError;
import com.aws.carddemo.account.service.AccountService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST boundary for the CardDemo Account Management slice (Feature&nbsp;F-003).
 *
 * <p>This thin controller replaces the retired CICS pseudo-conversational 3270 flow with a
 * stateless HTTP/JSON contract. It migrates the two legacy CICS transactions of the account
 * slice to REST endpoints under the shared base path {@code /api/v1/accounts}:</p>
 * <ul>
 *   <li><strong>{@code GET /api/v1/accounts/{accountId}}</strong> &mdash; account inquiry,
 *       replacing transaction <strong>CAVW</strong> (program {@code COACTVWC}, view). Reproduces
 *       the keyed read of {@code 9300-GETACCTDATA-BYACCT} in {@code app/cbl/COACTVWC.cbl}
 *       (L774&ndash;L807): a record found on the {@code DFHRESP(NORMAL)} path yields HTTP&nbsp;200,
 *       while the {@code DFHRESP(NOTFND)} path (L789&ndash;L807) yields HTTP&nbsp;404.</li>
 *   <li><strong>{@code PUT /api/v1/accounts/{accountId}}</strong> &mdash; account update,
 *       replacing transaction <strong>CAUP</strong> (program {@code COACTUPC}, update). Reproduces
 *       the {@code 9600-WRITE-PROCESSING} ({@code READ ... UPDATE} lock plus {@code REWRITE}) and
 *       {@code 9700-CHECK-CHANGE-IN-REC} (before-image conflict, L4109&ndash;L4193) behavior.</li>
 * </ul>
 *
 * <h2>Thin-controller contract</h2>
 * <p>The controller owns <strong>no</strong> business logic. Its sole responsibilities are the
 * coarse structural path check on {@code accountId} (11 numeric digits, via {@link Pattern} on the
 * {@code @Validated} class), triggering Bean Validation of the request body ({@link Valid}), invoking
 * {@link AccountService}, and wrapping the returned {@link AccountResponse} in an HTTP&nbsp;200
 * {@link ResponseEntity}. It performs no persistence, mapping, formatting, business validation, or
 * hand-rolled error handling.</p>
 *
 * <h2>Error semantics (owned centrally, never emitted here)</h2>
 * <p>The only status code this controller emits directly is <strong>200</strong>. Every error
 * response is produced by the central {@code GlobalExceptionHandler} ({@code @RestControllerAdvice}),
 * which maps propagated exceptions to their HTTP status:</p>
 * <ul>
 *   <li><strong>400 Bad Request</strong> &mdash; a malformed path id fails the {@link Pattern}
 *       constraint and raises {@code jakarta.validation.ConstraintViolationException}; a structurally
 *       invalid request body raises {@code MethodArgumentNotValidException}; a business-rule violation
 *       (active-status domain, monetary range/scale, strict date/year) raises {@code ValidationException}
 *       from the service's {@code AccountValidator}.</li>
 *   <li><strong>404 Not Found</strong> &mdash; the service raises {@code AccountNotFoundException} when
 *       no account exists for the key.</li>
 *   <li><strong>409 Conflict</strong> &mdash; a stale {@code version} raises
 *       {@code ObjectOptimisticLockingFailureException}; the modernized conflict text is owned by the
 *       handler, not by this controller.</li>
 * </ul>
 *
 * <h2>Identifier immutability</h2>
 * <p>{@link AccountUpdateRequest} structurally omits {@code accountId} and {@code groupId}, so there is
 * no body id to reconcile against the path; the URL path variable is the authoritative key and is
 * validated by the service. Consequently the controller performs no path/body id-mismatch check.</p>
 *
 * <h2>Security (Technical Specification &sect;0.6.6)</h2>
 * <p>This controller performs <strong>no logging</strong>, so the full account number and monetary
 * values are never written to logs in plaintext.</p>
 *
 * <h2>Wiring</h2>
 * <p>The single {@link AccountService} collaborator is supplied by <strong>constructor injection</strong>
 * (no field {@code @Autowired}) and retained as a {@code final} field. As a {@code @RestController} in a
 * sub-package of {@code com.aws.carddemo.account}, this class is auto-discovered by the default component
 * scan rooted at {@code AccountServiceApplication}; no manual registration is required.</p>
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Validated
@Tag(name = "Accounts",
        description = "Account inquiry and update (CardDemo F-003; migrates CICS CAVW/CAUP)")
public class AccountController {

    /**
     * Structural pattern for the account key: exactly 11 numeric digits.
     *
     * <p>This is the coarse controller-boundary guard permitted for a thin controller. It reproduces
     * the 11-digit numeric domain of the legacy {@code 1210-EDIT-ACCOUNT} edit at the HTTP boundary
     * (allowing all-zeros); the finer non-zero rule is applied by {@code AccountValidator} inside the
     * service. Both resolve to HTTP&nbsp;400 &mdash; defense-in-depth, behaviorally consistent.</p>
     */
    private static final String ACCOUNT_ID_REGEX = "\\d{11}";

    /**
     * Validation message surfaced when {@code accountId} is not an 11-digit number.
     */
    private static final String ACCOUNT_ID_MESSAGE = "accountId must be an 11-digit number";

    // ------------------------------------------------------------------------------------------------
    // OpenAPI example payloads (F-12). springdoc renders one example per response media type; without
    // an explicit per-status @ExampleObject it reuses the ApiError schema's single (400-flavored)
    // sample for the 404 and 409 responses too. These constants supply an accurate, status-specific
    // ApiError example for each documented error code so the generated /v3/api-docs and Swagger UI show
    // the correct shape and message under 400, 404, and 409. They are compile-time constant Strings so
    // they can be referenced from the annotation attributes below.
    // ------------------------------------------------------------------------------------------------

    /** Example ApiError body for a 400 caused by a malformed path id (GET). */
    private static final String EXAMPLE_400_ID = "{\n"
            + "  \"timestamp\": \"2026-07-15T20:09:30.123456Z\",\n"
            + "  \"status\": 400,\n"
            + "  \"error\": \"Bad Request\",\n"
            + "  \"message\": \"Validation failed\",\n"
            + "  \"path\": \"/api/v1/accounts/{accountId}\",\n"
            + "  \"fieldErrors\": { \"accountId\": \"accountId must be an 11-digit number\" }\n"
            + "}";

    /** Example ApiError body for a 400 caused by an invalid update body (PUT). */
    private static final String EXAMPLE_400_BODY = "{\n"
            + "  \"timestamp\": \"2026-07-15T20:09:30.123456Z\",\n"
            + "  \"status\": 400,\n"
            + "  \"error\": \"Bad Request\",\n"
            + "  \"message\": \"Validation failed\",\n"
            + "  \"path\": \"/api/v1/accounts/{accountId}\",\n"
            + "  \"fieldErrors\": { \"activeStatus\": \"must be 'Y' or 'N'\" }\n"
            + "}";

    /** Example ApiError body for a 404 (account absent); message reproduces the legacy COACTVWC text. */
    private static final String EXAMPLE_404 = "{\n"
            + "  \"timestamp\": \"2026-07-15T20:09:30.123456Z\",\n"
            + "  \"status\": 404,\n"
            + "  \"error\": \"Not Found\",\n"
            + "  \"message\": \"Account: 00000000099 not found in Acct Master file.\",\n"
            + "  \"path\": \"/api/v1/accounts/{accountId}\"\n"
            + "}";

    /** Example ApiError body for a 409 (stale optimistic-lock version). */
    private static final String EXAMPLE_409 = "{\n"
            + "  \"timestamp\": \"2026-07-15T20:09:30.123456Z\",\n"
            + "  \"status\": 409,\n"
            + "  \"error\": \"Conflict\",\n"
            + "  \"message\": \"Record updated by another user - please retry\",\n"
            + "  \"path\": \"/api/v1/accounts/{accountId}\"\n"
            + "}";

    /**
     * The single application-service collaborator. The controller knows only the service; it never
     * references the repository, mapper, validator, or any exception type for control flow.
     */
    private final AccountService accountService;

    /**
     * Creates the controller with its single collaborator.
     *
     * <p>Because this is the only constructor, the Spring container auto-wires the managed
     * {@link AccountService} bean without an explicit {@code @Autowired} annotation.</p>
     *
     * @param accountService the application service that owns all account inquiry/update logic
     */
    public AccountController(final AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Retrieves a single account by its 11-digit key.
     *
     * <p>Delegates to {@link AccountService#getAccount(String)} and wraps the result in an
     * HTTP&nbsp;200 response. Reproduces the {@code DFHRESP(NORMAL)} read path of {@code COACTVWC}
     * {@code 9300-GETACCTDATA-BYACCT}. A missing account (service {@code AccountNotFoundException})
     * becomes HTTP&nbsp;404 and a malformed id ({@link Pattern} failure) becomes HTTP&nbsp;400 &mdash;
     * both via the central exception handler.</p>
     *
     * @param accountId the zero-padded 11-digit account key from the request path
     * @return HTTP&nbsp;200 with the {@link AccountResponse} read projection
     */
    @Operation(summary = "Get an account by id",
            description = "Retrieves a single account by its 11-digit numeric key. Migrates CICS "
                    + "transaction CAVW (COACTVWC).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AccountResponse.class))),
            @ApiResponse(responseCode = "400", description = "Malformed account id (not 11 digits)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "malformedId", value = EXAMPLE_400_ID))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "notFound", value = EXAMPLE_404)))
    })
    @GetMapping(value = "/{accountId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountResponse> getAccount(
            @Parameter(description = "Zero-padded 11-digit account identifier", example = "00000000001")
            @PathVariable
            @Pattern(regexp = ACCOUNT_ID_REGEX, message = ACCOUNT_ID_MESSAGE) final String accountId) {
        return ResponseEntity.ok(accountService.getAccount(accountId));
    }

    /**
     * Applies an edited set of account fields identified by the 11-digit path key.
     *
     * <p>Delegates to {@link AccountService#updateAccount(String, AccountUpdateRequest)} and wraps the
     * updated resource (with its incremented {@code version}) in an HTTP&nbsp;200 response. Reproduces
     * the {@code COACTUPC} {@code 9600}/{@code 9700} update flow. Structural body failures
     * ({@link Valid} &rarr; {@code MethodArgumentNotValidException}), business-rule failures
     * ({@code ValidationException}), and malformed ids ({@link Pattern} failure) all become
     * HTTP&nbsp;400; a missing account becomes HTTP&nbsp;404; and a stale {@code version} becomes
     * HTTP&nbsp;409 &mdash; all via the central exception handler.</p>
     *
     * <p><strong>Content negotiation before the transaction (F-02).</strong> The declared
     * {@code consumes}/{@code produces = application/json} make the {@code Content-Type} and
     * {@code Accept} checks part of handler <em>mapping</em>: an unacceptable {@code Accept}
     * (&rarr; {@code HttpMediaTypeNotAcceptableException}, 406) or an unsupported {@code Content-Type}
     * (&rarr; {@code HttpMediaTypeNotSupportedException}, 415) is raised <em>before</em> this method is
     * invoked, so the {@code @Transactional} service &mdash; and any state/version mutation &mdash; is
     * never reached. This closes the defect where a 406 was returned only after the update had already
     * been committed.</p>
     *
     * @param accountId the zero-padded 11-digit account key from the request path
     * @param request   the editable account fields plus the client's last-seen {@code version}
     * @return HTTP&nbsp;200 with the updated {@link AccountResponse}
     */
    @Operation(summary = "Update an account",
            description = "Updates the editable fields of an account identified by its 11-digit key, "
                    + "using the submitted optimistic-lock version for conflict detection. Migrates "
                    + "CICS transaction CAUP (COACTUPC).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account updated",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AccountResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid account id or request body",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "invalidBody", value = EXAMPLE_400_BODY))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "notFound", value = EXAMPLE_404))),
            @ApiResponse(responseCode = "409", description = "Concurrent modification (stale version)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "staleVersion", value = EXAMPLE_409)))
    })
    @PutMapping(value = "/{accountId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountResponse> updateAccount(
            @Parameter(description = "Zero-padded 11-digit account identifier", example = "00000000001")
            @PathVariable
            @Pattern(regexp = ACCOUNT_ID_REGEX, message = ACCOUNT_ID_MESSAGE) final String accountId,
            @Valid @RequestBody final AccountUpdateRequest request) {
        return ResponseEntity.ok(accountService.updateAccount(accountId, request));
    }
}
