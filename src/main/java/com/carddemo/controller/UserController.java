package com.carddemo.controller;

import com.carddemo.dto.PageResponse;
import com.carddemo.dto.UserCreateRequest;
import com.carddemo.dto.UserResponse;
import com.carddemo.dto.UserUpdateRequest;
import com.carddemo.service.UserService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the CardDemo administrative user-management operations.
 *
 * <p>This controller is the Spring Boot re-expression of the four legacy CICS online programs that
 * maintained the {@code USRSEC} security-user file on the 3270 terminal and were reachable only from
 * the administrator menu ({@code COADM01C}). Honoring the migration's "one operation per original
 * transaction id" rule, each source program maps to exactly one HTTP endpoint:</p>
 * <ul>
 *   <li>{@code COUSR00C} &mdash; transaction {@code CU00}, the <em>List Users</em> browse. The legacy
 *       program sequentially browsed {@code USRSEC} ({@code STARTBR}/{@code READNEXT} for PF8 forward,
 *       {@code READPREV} for PF7 backward) and rendered a fixed window of rows per screen, also
 *       offering {@code 'U'} (update) / {@code 'D'} (delete) row selection. This becomes
 *       {@code GET /users}.</li>
 *   <li>{@code COUSR01C} &mdash; transaction {@code CU01}, the <em>Add User</em> screen. The program
 *       required all five fields (First Name, Last Name, User ID, Password, User Type), rejecting any
 *       empty field with a "... can NOT be empty..." message and a duplicate key with
 *       "User ID already exist...". This becomes {@code POST /users}.</li>
 *   <li>{@code COUSR02C} &mdash; transaction {@code CU02}, the <em>Update User</em> screen. The program
 *       read the record by user id (rejecting a missing record with "User ID NOT found..."), then
 *       rewrote the editable name / password / type fields. This becomes {@code PUT /users/{userId}}.</li>
 *   <li>{@code COUSR03C} &mdash; transaction {@code CU03}, the <em>Delete User</em> screen. The program
 *       read the record by user id ("User ID NOT found..." when absent) and, after a PF5 confirmation,
 *       deleted it. This becomes {@code DELETE /users/{userId}}.</li>
 * </ul>
 *
 * <p>The BMS screen maps {@code COUSR00}&ndash;{@code COUSR03} that rendered these functions are
 * <strong>retired</strong>; their field layouts informed the request/response DTOs
 * ({@link UserResponse}, {@link UserCreateRequest}, {@link UserUpdateRequest}) rather than any
 * rendered UI. The deliverable is a stateless JSON REST contract.</p>
 *
 * <h2>Thin-controller contract</h2>
 * <p>This class holds <strong>no business logic</strong>. Every endpoint simply binds the request
 * parameters / path variables / body and delegates to {@link UserService}, which owns all behaviour
 * ported from the COBOL paragraphs &mdash; the fixed legacy page size of <strong>7</strong> (applied
 * service-side as {@code PageRequest.of(page, 7)}), the upper-casing normalization of the user id, the
 * BCrypt (strength 12) password hashing, and the duplicate-user / not-found detection. The controller
 * deliberately forwards only the zero-based {@code page} index for the list operation; it never
 * supplies a page size, so the size-7 window can never be overridden from the API surface.</p>
 *
 * <h2>Status codes</h2>
 * <ul>
 *   <li>{@code GET /users} &mdash; HTTP&nbsp;200 with a {@link PageResponse} of {@link UserResponse}.</li>
 *   <li>{@code GET /users/{userId}} &mdash; HTTP&nbsp;200 with the {@link UserResponse}.</li>
 *   <li>{@code POST /users} &mdash; HTTP&nbsp;<strong>201&nbsp;Created</strong> with the new
 *       {@link UserResponse}.</li>
 *   <li>{@code PUT /users/{userId}} &mdash; HTTP&nbsp;200 with the updated {@link UserResponse}.</li>
 *   <li>{@code DELETE /users/{userId}} &mdash; HTTP&nbsp;<strong>204&nbsp;No&nbsp;Content</strong>.</li>
 * </ul>
 *
 * <h2>Error mapping</h2>
 * <p>Error translation is centralized in the application's {@code @RestControllerAdvice}
 * ({@code GlobalExceptionHandler}); the controller adds no per-endpoint error handling:</p>
 * <ul>
 *   <li>a view / update / delete of an unknown user raises {@code ResourceNotFoundException} &rarr;
 *       HTTP&nbsp;404 (the legacy "User ID NOT found..." path);</li>
 *   <li>creating a user whose id already exists raises {@code BusinessRuleException} &rarr;
 *       HTTP&nbsp;400 (the legacy "User ID already exist..." path);</li>
 *   <li>a request body that violates the {@link UserCreateRequest} / {@link UserUpdateRequest} Bean
 *       Validation constraints raises {@code MethodArgumentNotValidException} &rarr; HTTP&nbsp;400,
 *       carrying the preserved "... can NOT be empty..." field messages.</li>
 *   <li>a negative {@code page} index on the list endpoint violates the {@link Min @Min(0)} parameter
 *       constraint (activated by the class-level {@link Validated @Validated}) and raises
 *       {@code ConstraintViolationException} &rarr; HTTP&nbsp;400 &mdash; an invalid client parameter
 *       is reported as a client error, never an HTTP&nbsp;500.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>Every endpoint is <strong>admin-only</strong>. This is enforced declaratively by the
 * <strong>class-level</strong> {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")} annotation
 * (activated by {@code @EnableMethodSecurity} in {@code SecurityConfig}), which covers all five
 * handlers &mdash; method-level annotations are therefore intentionally omitted as redundant. As
 * defense-in-depth, {@code SecurityConfig} additionally matches {@code /users/**} to
 * {@code hasRole("ADMIN")} in the filter chain. An authenticated non-admin caller is rejected with
 * HTTP&nbsp;403 (the {@code AccessDeniedException} translated by {@code GlobalExceptionHandler}); an
 * unauthenticated caller is rejected with HTTP&nbsp;401 by the stateless JWT filter chain. The paths
 * are absolute ({@code /users}, {@code /users/{userId}}); there is no {@code /api} prefix.</p>
 *
 * <h2>Sensitive-data guarantees</h2>
 * <p>A user's password is <strong>never</strong> returned: {@link UserResponse} structurally omits
 * any credential field, so neither the plaintext nor the BCrypt hash can ever reach a client. This
 * controller must never log request bodies either &mdash; {@link UserCreateRequest} and
 * {@link UserUpdateRequest} carry the plaintext password before it is hashed downstream (their
 * {@code toString()} masks it, but logging is avoided here regardless). These guarantees are enforced
 * by the DTO / mapper / service layers; the controller simply does not bypass them.</p>
 *
 * @see UserService
 * @see UserResponse
 * @see UserCreateRequest
 * @see UserUpdateRequest
 * @see PageResponse
 */
@RestController
@RequestMapping("/users")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class UserController {

    /**
     * Application service that encapsulates all user business logic ported from {@code COUSR00C},
     * {@code COUSR01C}, {@code COUSR02C} and {@code COUSR03C} &mdash; pagination (size 7), user-id
     * upper-casing, BCrypt password hashing, and duplicate / not-found detection. Injected through
     * the constructor and held {@code final} so the collaborator is immutable for the lifetime of
     * this singleton bean.
     */
    private final UserService userService;

    /**
     * Creates the controller with its required {@link UserService} collaborator.
     *
     * <p>Constructor injection (replacing the COBOL {@code CALL} / {@code XCTL} static linkage) makes
     * the dependency explicit, mandatory and immutable, and keeps the controller trivially testable
     * with a mocked service.</p>
     *
     * @param userService the user service to delegate every request to; never {@code null}
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Lists users &mdash; the REST re-expression of {@code COUSR00C} (transaction {@code CU00}).
     *
     * <p>The controller forwards only the zero-based {@code page} index; the service owns the fixed
     * legacy window of seven rows per page ({@code PageRequest.of(page, 7)}), so the size-7 contract
     * cannot be overridden here. The returned {@link PageResponse} carries the same 7-row window plus
     * the {@code first}/{@code last}/{@code totalElements} metadata that reproduces the COBOL
     * PF7/PF8 forward/backward paging indicators. Each row is a credential-free {@link UserResponse}.</p>
     *
     * <p>The {@code page} index is constrained to be non-negative ({@link Min @Min(0)}, enforced by
     * the class-level {@link Validated @Validated}). A client-supplied negative value (e.g.
     * {@code ?page=-1}) is therefore rejected at the controller boundary with a
     * {@code ConstraintViolationException}, which {@code GlobalExceptionHandler} translates into
     * <strong>HTTP&nbsp;400&nbsp;Bad&nbsp;Request</strong> &mdash; a client error &mdash; rather than
     * allowing {@code PageRequest.of(page, 7)} to raise an {@code IllegalArgumentException} that would
     * surface as HTTP&nbsp;500. This matches how a non-numeric {@code page} is already rejected with
     * 400, keeping invalid pagination input a consistent client error.</p>
     *
     * @param page the zero-based page index to retrieve; must be {@code >= 0} (a negative value is a
     *             {@code 400 Bad Request}); defaults to {@code 0} (the first page) when the parameter
     *             is absent
     * @return HTTP&nbsp;200 with a {@link PageResponse} of {@link UserResponse} rows whose {@code size}
     *         is the legacy 7, never carrying a password
     */
    @GetMapping
    public ResponseEntity<PageResponse<UserResponse>> listUsers(
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return ResponseEntity.ok(userService.listUsers(page));
    }

    /**
     * Views a single user's detail &mdash; the read that backs the legacy maintenance screens'
     * keyed lookup ({@code COUSR02C} / {@code COUSR03C}).
     *
     * <p>Looks up the user by the id supplied in the path and returns the credential-free detail
     * projection. A user id that does not exist causes the service to raise
     * {@code ResourceNotFoundException} (the legacy "User ID NOT found..." path), which the global
     * exception handler translates into HTTP&nbsp;404.</p>
     *
     * @param userId the user identifier to look up ({@code SEC-USR-ID})
     * @return HTTP&nbsp;200 with the matching {@link UserResponse} (password-free); HTTP&nbsp;404 if
     *         no user has the given id
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUser(@PathVariable String userId) {
        return ResponseEntity.ok(userService.getUser(userId));
    }

    /**
     * Creates a new user &mdash; the REST re-expression of {@code COUSR01C} (transaction {@code CU01}).
     *
     * <p>The request body ({@link UserCreateRequest}) carries the user id, profile fields, plaintext
     * password and user type, all validated with {@code @Valid}: an empty field reproduces the legacy
     * "... can NOT be empty..." rejection ({@code MethodArgumentNotValidException} &rarr;
     * HTTP&nbsp;400). The service stores the upper-cased id and the BCrypt (strength 12) hash of the
     * upper-cased password; a duplicate id raises {@code BusinessRuleException} (the legacy
     * "User ID already exist..." path) &rarr; HTTP&nbsp;400. On success the new user is returned with
     * HTTP&nbsp;<strong>201&nbsp;Created</strong>; the {@link UserResponse} never carries the
     * password.</p>
     *
     * @param request the validated create request; the plaintext password it carries is hashed
     *                downstream by the service and is never logged or echoed
     * @return HTTP&nbsp;201 with the {@link UserResponse} for the newly created user (password-free)
     */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(request));
    }

    /**
     * Updates an existing user &mdash; the REST re-expression of {@code COUSR02C} (transaction
     * {@code CU02}).
     *
     * <p>The user to update is identified solely by the id in the URL path; the request body
     * ({@link UserUpdateRequest}) carries only the editable fields (first name, last name, password,
     * user type) and deliberately omits the user id, which is an immutable key &mdash; consistent with
     * {@code COUSR02C}, which never permits the id to change. The body is validated with {@code @Valid}:
     * a constraint violation raises {@code MethodArgumentNotValidException} &rarr; HTTP&nbsp;400. The
     * service applies the editable fields and re-hashes the password with BCrypt (strength 12); an
     * unknown user id raises {@code ResourceNotFoundException} (the legacy "User ID NOT found..."
     * path) &rarr; HTTP&nbsp;404.</p>
     *
     * @param userId  the identifier of the user to update ({@code SEC-USR-ID}); immutable, taken only
     *                from the path
     * @param request the validated set of editable fields to apply; carries no user id, and its
     *                plaintext password is hashed downstream and never logged or echoed
     * @return HTTP&nbsp;200 with the {@link UserResponse} reflecting the persisted post-update state
     *         (password-free)
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable String userId,
            @Valid @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(userService.updateUser(userId, request));
    }

    /**
     * Deletes a user &mdash; the REST re-expression of {@code COUSR03C} (transaction {@code CU03}).
     *
     * <p>The user to delete is identified by the id in the URL path. The service checks existence
     * first so that deleting an unknown id surfaces as {@code ResourceNotFoundException} (the legacy
     * "User ID NOT found..." path) &rarr; HTTP&nbsp;404 rather than a silent no-op. On success no body
     * is returned and the response is HTTP&nbsp;<strong>204&nbsp;No&nbsp;Content</strong>.</p>
     *
     * @param userId the identifier of the user to delete ({@code SEC-USR-ID})
     * @return HTTP&nbsp;204 with no body; HTTP&nbsp;404 if no user has the given id
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable String userId) {
        userService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }
}
