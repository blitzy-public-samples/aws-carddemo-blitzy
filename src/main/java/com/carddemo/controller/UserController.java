package com.carddemo.controller;

import com.carddemo.dto.user.UserCreateRequest;
import com.carddemo.dto.user.UserDto;
import com.carddemo.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Collections;
import java.util.Set;

/**
 * User-administration REST endpoints &mdash; the stateless, ADMIN-only replacement for the four
 * legacy CICS user-administration online programs:
 * <ul>
 *   <li>{@code app/cbl/COUSR00C.cbl} (TRANID {@code CU00}) &mdash; list users ten at a time,
 *       paged with PF7 (previous) / PF8 (next) over the {@code USRSEC} file;</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} (TRANID {@code CU01}) &mdash; add a user after validating
 *       all five fields (First Name, Last Name, User ID, Password, User Type);</li>
 *   <li>{@code app/cbl/COUSR02C.cbl} (TRANID {@code CU02}) &mdash; read a user for update, compare
 *       field-by-field, and rewrite only when something actually changed;</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} (TRANID {@code CU03}) &mdash; read a user to confirm it exists
 *       and then delete it.</li>
 * </ul>
 *
 * <p>The persisted shape is the 80-byte {@code SEC-USER-DATA} record from
 * {@code app/cpy/CSUSR01Y.cpy} ({@code SEC-USR-ID X(08)}, {@code SEC-USR-FNAME X(20)},
 * {@code SEC-USR-LNAME X(20)}, {@code SEC-USR-PWD X(08)} &mdash; widened to a 60-character BCrypt
 * hash in Java, {@code SEC-USR-TYPE X(01)}, {@code SEC-USR-FILLER X(23)}).</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET    /api/admin/users}            &mdash; list users (paginated; default page size
 *       10, matching the BMS {@code COUSR0A} 10-row display); {@code 200 OK} with a
 *       {@code Page<UserDto>} body.</li>
 *   <li>{@code GET    /api/admin/users/{userId}}   &mdash; retrieve one user; {@code 200 OK} with a
 *       {@link UserDto} body (never the password hash).</li>
 *   <li>{@code POST   /api/admin/users}            &mdash; create a user; {@code 201 Created} with a
 *       {@code Location} header pointing at the new resource.</li>
 *   <li>{@code PUT    /api/admin/users/{userId}}   &mdash; update a user; {@code 200 OK} with the
 *       refreshed {@link UserDto}. A {@code null} password preserves the existing hash.</li>
 *   <li>{@code DELETE /api/admin/users/{userId}}   &mdash; delete a user; {@code 204 No Content}.</li>
 * </ul>
 *
 * <h2>CRITICAL security enhancement &mdash; PR-18 / AAP &sect;0.6.9</h2>
 * <p>The original {@code COUSR00C}&ndash;{@code COUSR03C} programs contained <strong>zero</strong>
 * programmatic authorization checks; access was gated solely by menu routing through the
 * admin-only {@code COADM01C} menu. A caller who invoked the {@code CU00}&ndash;{@code CU03}
 * transaction ids directly &mdash; bypassing the menu &mdash; obtained unrestricted access to
 * user administration. In a stateless REST world the exposure is greater still, because any client
 * (curl, Postman, a hostile script) can target any URL directly. This controller closes that gap by
 * declaring {@link PreAuthorize @PreAuthorize}{@code ("hasRole('ADMIN')")} at the
 * <strong>class level</strong>, so every one of the five endpoints requires {@code ROLE_ADMIN}.
 * Enforcement is activated application-wide by {@code @EnableMethodSecurity(prePostEnabled = true)}
 * in {@code com.carddemo.security.MethodSecurityConfig}; without that configuration the annotation
 * would be silently ignored. A non-admin principal is rejected with {@code 403 Forbidden}, and an
 * anonymous request with {@code 401 Unauthorized}, both produced as JSON by
 * {@code com.carddemo.security.SecurityConfig} (the {@code jsonAccessDeniedHandler} /
 * {@code jsonAuthenticationEntryPoint}, plus the URL rule {@code /api/admin/** -> hasRole('ADMIN')}
 * that provides defense in depth at the filter-chain boundary).</p>
 *
 * <h2>Thin HTTP boundary (business logic lives in {@link UserService})</h2>
 * <p>This controller is intentionally a thin, stateless HTTP adapter. It performs only
 * path-variable shape validation (the {@code {userId}} convention: 1&ndash;8 characters, uppercase
 * alphanumeric &mdash; PR-13) and the {@code @Valid} request-body trigger; all user-administration
 * semantics &mdash; the {@code COUSR01C} five-field validation order, the {@code COUSR02C}
 * compare-and-update change detection, the duplicate and not-found handling, BCrypt password
 * encoding, and every preserved COBOL message literal &mdash; live in {@link UserService}. The exact
 * COBOL messages are surfaced to clients by
 * {@code com.carddemo.controller.advice.GlobalExceptionHandler}, which maps each application and
 * framework exception onto the uniform error contract:</p>
 * <ul>
 *   <li>{@code "User ID NOT found..."} (COUSR02C/COUSR03C) is carried by the service's not-found
 *       exception &rarr; {@code 404 Not Found};</li>
 *   <li>{@code "User ID already exist..."} (COUSR01C {@code DUPKEY}/{@code DUPREC}) is carried by
 *       {@code DataIntegrityViolationException} &rarr; {@code 409 Conflict};</li>
 *   <li>{@code "Please modify to update ..."} (COUSR02C, no field changed) is carried by
 *       {@code IllegalStateException} &rarr; {@code 422 Unprocessable Entity};</li>
 *   <li>the field-level {@code "... can NOT be empty..."} messages are produced by the Bean
 *       Validation constraints on {@link UserCreateRequest} / the {@code {userId}} path variable
 *       &rarr; {@code 400 Bad Request} (and reproduced as defense-in-depth in {@link UserService}
 *       for direct invocation);</li>
 *   <li>a concurrent modification surfaces as
 *       {@code ObjectOptimisticLockingFailureException} &rarr; {@code 409 Conflict} (PR-22).</li>
 * </ul>
 *
 * <h2>Password handling (PR-17)</h2>
 * <p>The raw password lives in memory only for the brief span between request deserialization and
 * the {@link UserService} call. The controller never inspects, transforms, or logs it:
 * {@link UserCreateRequest#getPassword()} is read only by the service, which BCrypt-encodes it
 * before persistence; the outbound {@link UserDto} has no password field at all. Log statements
 * emit only {@code userId} / {@code userType}; the password is additionally suppressed from
 * {@code UserCreateRequest.toString()} via Lombok {@code @ToString.Exclude} and from any JSON
 * response via {@code @JsonProperty(access = WRITE_ONLY)} (defense in depth).</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; {@code userId} validated as 1&ndash;8 uppercase-alphanumeric
 *       characters ({@code @NotBlank} / {@code @Size(min=1,max=8)} / {@code @Pattern("[A-Z0-9]+")});
 *       firstName/lastName/userType lengths are enforced on {@link UserCreateRequest}.</li>
 *   <li><b>PR-17</b> &mdash; the controller never sees the password beyond forwarding the request
 *       DTO to the service; the password value is never logged.</li>
 *   <li><b>PR-18</b> &mdash; class-level {@code @PreAuthorize("hasRole('ADMIN')")} on every
 *       endpoint (closes the documented {@code COUSR00C}&ndash;{@code COUSR03C} auth gap).</li>
 *   <li><b>PR-19</b> &mdash; the {@code 'A'} &rarr; {@code ROLE_ADMIN} / {@code 'U'} &rarr;
 *       {@code ROLE_USER} mapping is owned by the entity / authority mapper, not this controller.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 namespace only ({@code jakarta.validation.*}); no
 *       {@code javax.*} imports.</li>
 *   <li><b>PR-29</b> &mdash; constructor injection only, via Lombok {@link RequiredArgsConstructor}
 *       over the {@code final} {@link UserService} field; no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>Why class-level (not method-level) {@code @PreAuthorize}:</b> all five endpoints share
 *       the same rule, so a class-level annotation is cleaner and cannot be forgotten on a future
 *       method.</li>
 *   <li><b>Stateless pagination:</b> the COBOL PF7/PF8 browse cursor over {@code USRSEC} is replaced
 *       by a stateless Spring Data {@link Pageable} recomputed per request; the default page size 10
 *       mirrors the legacy 10-row list screen.</li>
 *   <li><b>Single GET for update/delete prep:</b> COBOL had separate "read for update" and "read for
 *       delete" flows; REST consolidates them into one {@code GET /{userId}} that the client follows
 *       with a {@code PUT} or {@code DELETE} (REST-convention consolidation, not a feature addition
 *       &mdash; AAP &sect;0.7.2).</li>
 *   <li><b>DELETE needs no confirmation field:</b> the HTTP verb is the intent; the COBOL PF5
 *       confirmation was a 3270 UI affordance with no REST equivalent.</li>
 *   <li><b>Path is authoritative on PUT:</b> the {@code {userId}} path variable identifies the user
 *       to update; the service treats the id as immutable.</li>
 * </ul>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (CSUSR01Y SEC-USER-DATA record layout).</p>
 *
 * @see UserService the service that performs all user-administration business logic
 * @see UserDto the outbound user payload (never carries the password hash, PR-17)
 * @see UserCreateRequest the inbound create/update payload (raw password, BCrypt-encoded downstream)
 * @see com.carddemo.security.MethodSecurityConfig enables the {@code @PreAuthorize} enforcement (PR-18)
 * @see com.carddemo.controller.advice.GlobalExceptionHandler exception-to-HTTP-status mapping
 * @since 1.0
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Validated
@Slf4j
@PreAuthorize("hasRole('ADMIN')") // PR-18: CRITICAL — closes the COUSR00C-03C programmatic auth gap (AAP §0.6.9)
@Tag(name = "User Administration",
        description = "User CRUD endpoints (ADMIN ONLY). Replaces CICS COUSR00C-COUSR03C (CU00-CU03).")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    /**
     * User-administration business service replacing the {@code COUSR00C}&ndash;{@code COUSR03C}
     * online programs. Injected by type through the Lombok-generated constructor (PR-29). Every
     * endpoint delegates to it: {@link UserService#listUsers(Pageable)},
     * {@link UserService#getUser(String)}, {@link UserService#createUser(UserCreateRequest)},
     * {@link UserService#updateUser(String, UserCreateRequest)}, and
     * {@link UserService#deleteUser(String)}. BCrypt password encoding (PR-17) happens inside the
     * service, so this controller never handles the password directly.
     */
    private final UserService userService;

    /**
     * Allowlist of properties a client may sort the user list by &mdash; the bound set of valid
     * {@code sort} parameter values for {@code GET /api/admin/users} (QA finding F4-PAG-01).
     *
     * <p>These are exactly the four fields the {@link UserDto} exposes ({@code userId},
     * {@code firstName}, {@code lastName}, {@code userType}), each of which is also a real,
     * sortable JPA attribute of the {@code User} entity. An explicit allowlist is required here
     * (unlike the transaction/card endpoints, where an unknown sort property already surfaces as a
     * clean {@code PropertyReference} &rarr; 400) because the {@code User} entity implements
     * {@code org.springframework.security.core.userdetails.UserDetails} and therefore carries bean
     * getters such as {@code getPassword()}/{@code getUsername()} that Spring Data's property
     * introspection happily resolves. Without this guard a request like {@code ?sort=password}
     * passes property resolution but then fails deep in Hibernate (the mapped column is
     * {@code sec_usr_pwd}/field {@code secUsrPwd}, not {@code password}) as an unmapped
     * {@code InvalidDataAccessApiUsageException} &rarr; HTTP 500. Bounding the sort here yields the
     * same {@code 400 INVALID_SORT} contract as the other list endpoints and never leaks the
     * password-hash column as a sortable key.</p>
     */
    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("userId", "firstName", "lastName", "userType");

    /**
     * Lists users one page at a time &mdash; the REST replacement for {@code COUSR00C}
     * (TRANID {@code CU00}). The COBOL {@code STARTBR} / {@code READNEXT} / {@code READPREV} browse
     * cursor over {@code USRSEC}, driven by PF7 (previous) and PF8 (next) ten rows at a time, is
     * replaced by a stateless Spring Data {@link Pageable} that is recomputed from the request on
     * every call. The default page size 10 matches the legacy 10-row list screen, and the default
     * sort is {@code userId} ascending.
     *
     * <p>Each entity is converted to a {@link UserDto} by the service, which omits the BCrypt
     * password hash (PR-17), so the hash never crosses the REST boundary. Only the page coordinates
     * are logged, at {@code DEBUG}.</p>
     *
     * @param pageable the page request (0-indexed page number, page size, optional sort); defaults
     *                 to {@code size=10, sort=userId} via {@link PageableDefault}
     * @return {@code 200 OK} with a {@link Page} of {@link UserDto} (empty page when none match)
     */
    @GetMapping
    @Operation(
            summary = "List all users (paginated, ADMIN only)",
            description = "Returns a paginated user list. Default page size 10 matches the BMS COUSR0A "
                    + "10-row display; sorted by userId ascending. Replaces COUSR00C (TRANID=CU00) "
                    + "PF7/PF8 pagination. Password hashes are NEVER returned (UserDto excludes the "
                    + "password field).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Users returned"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "403", description = "User does not have ADMIN role")
    })
    public ResponseEntity<Page<UserDto>> listUsers(
            @Parameter(description = "Pagination parameters (page=0-indexed, size=10 default, sort=userId asc)")
            @PageableDefault(size = 10, sort = "userId") Pageable pageable) {

        log.debug("GET /api/admin/users page={} size={}", pageable.getPageNumber(), pageable.getPageSize());
        validateSort(pageable.getSort());
        Page<UserDto> users = userService.listUsers(pageable);
        return ResponseEntity.ok(users);
    }

    /**
     * Bounds the requested sort to {@link #SORTABLE_PROPERTIES}, rejecting any other property with a
     * clean {@code 400 Bad Request} instead of a deep {@code 500} (QA finding F4-PAG-01).
     *
     * <p>For each {@link Sort.Order} whose property is not in the allowlist, this throws a
     * {@link PropertyReferenceException} &mdash; the very exception Spring Data raises for an
     * unknown sort property on the other list endpoints &mdash; so {@code GlobalExceptionHandler}
     * maps it to the identical {@code 400} response with code {@code "INVALID_SORT"} and message
     * {@code "Invalid sort property: '<name>'"}. The exception is constructed against
     * {@link UserDto} (the client-facing shape) with an empty already-resolved path; only
     * {@link PropertyReferenceException#getPropertyName()} is consumed by the handler. The first
     * offending property is reported (matching the natural single-property failure behavior).</p>
     *
     * @param sort the requested sort (never {@code null}; {@link Sort#unsorted()} when absent)
     * @throws PropertyReferenceException if any ordered property is outside {@link #SORTABLE_PROPERTIES}
     */
    private void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            String property = order.getProperty();
            if (!SORTABLE_PROPERTIES.contains(property)) {
                log.debug("Rejecting invalid user sort property '{}' (allowed: {})",
                        property, SORTABLE_PROPERTIES);
                throw new PropertyReferenceException(
                        property, TypeInformation.of(UserDto.class), Collections.emptyList());
            }
        }
    }

    /**
     * Retrieves a single user by id &mdash; the REST replacement for the keyed {@code USRSEC} read
     * performed by the {@code COUSR02C} "read for update" and {@code COUSR03C} "read for delete"
     * lookup paragraphs, consolidated here into one endpoint. A missing record &mdash; the COBOL
     * {@code DFHRESP(NOTFND)} branch with message {@code "User ID NOT found..."} &mdash; is raised by
     * the service and mapped to {@code 404 Not Found} by {@code GlobalExceptionHandler}. The
     * password hash is never returned.
     *
     * <p>Path-variable validation runs during argument binding (the class-level {@code @Validated}):
     * a blank, over-length, or non-uppercase-alphanumeric {@code userId} is rejected with
     * {@code 400 Bad Request} before the service is invoked, preserving the exact COBOL message
     * {@code "User ID can NOT be empty..."} (COUSR02C/COUSR03C).</p>
     *
     * @param userId the user id (1&ndash;8 uppercase-alphanumeric characters); mirrors COBOL
     *               {@code SEC-USR-ID PIC X(08)} (PR-13)
     * @return {@code 200 OK} with the matching {@link UserDto} (without the password hash)
     */
    @GetMapping("/{userId}")
    @Operation(
            summary = "Get user by ID (ADMIN only)",
            description = "Retrieves a single user. Consolidates the COUSR02C 'read for update' and "
                    + "COUSR03C 'read for delete' lookups into one REST endpoint. Returns 404 with "
                    + "message 'User ID NOT found...' on a miss. The password hash is NEVER returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User found"),
            @ApiResponse(responseCode = "400", description = "'User ID can NOT be empty...'"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "403", description = "User does not have ADMIN role"),
            @ApiResponse(responseCode = "404", description = "'User ID NOT found...'")
    })
    public ResponseEntity<UserDto> getUser(
            @Parameter(description = "1-8 character uppercase user ID", example = "ADMIN001")
            @PathVariable("userId")
            @NotBlank(message = "User ID can NOT be empty...")
            @Size(min = 1, max = 8, message = "User ID can NOT be empty...")
            @Pattern(regexp = "[A-Z0-9]+", message = "User ID must be uppercase alphanumeric")
            String userId) {

        log.debug("GET /api/admin/users/{}", userId);
        UserDto user = userService.getUser(userId);
        return ResponseEntity.ok(user);
    }

    /**
     * Creates a new user &mdash; the REST replacement for {@code COUSR01C} (TRANID {@code CU01}).
     *
     * <p>All five fields are required (userId, firstName, lastName, password, userType); the
     * {@code @Valid} trigger runs Bean Validation on {@link UserCreateRequest} (PR-13), and the
     * service additionally reproduces the exact {@code COUSR01C} five-field validation order
     * (First Name &rarr; Last Name &rarr; User ID &rarr; Password &rarr; User Type) as
     * defense-in-depth so the original {@code "... can NOT be empty..."} strings are guaranteed.
     * The service rejects a duplicate id with {@code "User ID already exist..."}
     * (COUSR01C {@code DUPKEY}/{@code DUPREC}) &rarr; {@code 409 Conflict}, BCrypt-encodes the raw
     * password before persistence (PR-17), and stores {@code userType} as {@code 'A'} / {@code 'U'}
     * (PR-19).</p>
     *
     * <p>On success this returns {@code 201 Created} with a {@code Location} header pointing at the
     * new resource &mdash; the REST-idiomatic equivalent of the COBOL {@code "... has been added ..."}
     * success message, but more useful because the client can immediately {@code GET} the new user.
     * Only {@code userId} and {@code userType} are logged; the password is never logged (PR-17).</p>
     *
     * @param request the create request (raw password; forwarded to the service and never logged
     *                or persisted in clear text)
     * @return {@code 201 Created} with the created {@link UserDto} body and a {@code Location} header
     *         of {@code /api/admin/users/{userId}}
     */
    @PostMapping
    @Operation(
            summary = "Create new user (ADMIN only)",
            description = "Creates a new user account. All 5 fields are required (userId, firstName, "
                    + "lastName, password, userType). The password is BCrypt-encoded by UserService "
                    + "(PR-17). Replaces COUSR01C (TRANID=CU01). Returns 409 Conflict if the userId "
                    + "already exists ('User ID already exist...'). Returns 201 Created with a Location "
                    + "header on success ('User has been added ...').")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created; Location header points to /api/admin/users/{userId}"),
            @ApiResponse(responseCode = "400", description = "Validation error (e.g., 'First Name can NOT be empty...')"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "403", description = "User does not have ADMIN role"),
            @ApiResponse(responseCode = "409", description = "'User ID already exist...'")
    })
    public ResponseEntity<UserDto> createUser(@Valid @RequestBody UserCreateRequest request) {
        // PR-17: log only the non-sensitive identifiers — NEVER the password (and never its presence).
        log.info("POST /api/admin/users userId={} userType={}", request.getUserId(), request.getUserType());

        UserDto created = userService.createUser(request);

        URI location = URI.create("/api/admin/users/" + created.getUserId());
        log.info("POST /api/admin/users created userId={}", created.getUserId());
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Updates an existing user &mdash; the REST replacement for {@code COUSR02C} (TRANID {@code CU02}).
     *
     * <p>The {@code {userId}} path variable is the source of truth for which user to update (the id
     * is immutable). The service reproduces the {@code COUSR02C} compare-and-update logic: each
     * supplied field is compared to the stored value and the record is saved only when at least one
     * field actually changed; when nothing changed it raises {@code "Please modify to update ..."}
     * &rarr; {@code 422 Unprocessable Entity}. A missing user yields {@code "User ID NOT found..."}
     * &rarr; {@code 404 Not Found}.</p>
     *
     * <p><strong>Password (PR-17).</strong> The {@code password} field is nullable on update: a
     * {@code null}/blank value leaves the existing BCrypt hash untouched (administrators may edit
     * other fields without forcing a password rotation), while a non-blank value is BCrypt-re-encoded
     * (a raw password can never be diffed against a stored hash, so a supplied password always counts
     * as a change). The single {@link UserCreateRequest} DTO is reused for both create and update;
     * its {@code password} constraint omits {@code @NotBlank} precisely to permit this. Only
     * {@code userId} and {@code userType} are logged; the password is never logged.</p>
     *
     * @param userId  the id of the user to update (1&ndash;8 uppercase-alphanumeric characters)
     * @param request the update request ({@code null} password preserves the existing hash)
     * @return {@code 200 OK} with the updated {@link UserDto} (without the password hash)
     */
    @PutMapping("/{userId}")
    @Operation(
            summary = "Update user (ADMIN only)",
            description = "Updates user fields. The password is NULLABLE on PUT — if null, the existing "
                    + "BCrypt hash is preserved unchanged; if non-null, the password is BCrypt-re-encoded. "
                    + "Replaces COUSR02C (TRANID=CU02). If no field changed, the service signals "
                    + "'Please modify to update ...' (422). Returns 200 OK with the updated UserDto on "
                    + "success ('User has been updated ...').")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error (e.g., 'First Name can NOT be empty...')"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "403", description = "User does not have ADMIN role"),
            @ApiResponse(responseCode = "404", description = "'User ID NOT found...'"),
            @ApiResponse(responseCode = "409", description = "Optimistic lock conflict ('Record changed by some one else. Please review')")
    })
    public ResponseEntity<UserDto> updateUser(
            @Parameter(description = "1-8 character uppercase user ID", example = "ADMIN001")
            @PathVariable("userId")
            @NotBlank(message = "User ID can NOT be empty...")
            @Size(min = 1, max = 8, message = "User ID can NOT be empty...")
            @Pattern(regexp = "[A-Z0-9]+", message = "User ID must be uppercase alphanumeric")
            String userId,
            @Valid @RequestBody UserCreateRequest request) {

        // PR-17: log only the non-sensitive identifiers — NEVER the password.
        log.info("PUT /api/admin/users/{} userType={}", userId, request.getUserType());

        UserDto updated = userService.updateUser(userId, request);

        log.info("PUT /api/admin/users/{} completed", userId);
        return ResponseEntity.ok(updated);
    }

    /**
     * Deletes a user &mdash; the REST replacement for {@code COUSR03C} (TRANID {@code CU03}).
     *
     * <p>The service mirrors the COBOL read-then-delete flow: it first loads the record to confirm
     * it exists (the {@code DFHRESP(NOTFND)} branch becomes {@code "User ID NOT found..."} &rarr;
     * {@code 404 Not Found}) and then deletes it. The original program required a PF5 confirmation
     * keystroke; in REST the {@code DELETE} verb itself is the confirmation, so no extra confirmation
     * field is modeled. On success this returns {@code 204 No Content} (declared via
     * {@link ResponseStatus} with a {@code void} return type) &mdash; the equivalent of the COBOL
     * {@code "... has been deleted ..."} message.</p>
     *
     * <p>Any "an administrator may not delete themselves" rule is a business policy not present in the
     * COBOL source (AAP &sect;0.7.2 &mdash; no feature additions); if required it belongs in the
     * service, not at this HTTP boundary.</p>
     *
     * @param userId the id of the user to delete (1&ndash;8 uppercase-alphanumeric characters)
     */
    @DeleteMapping("/{userId}")
    @Operation(
            summary = "Delete user (ADMIN only)",
            description = "Deletes a user account. Replaces COUSR03C (TRANID=CU03). In REST, deletion is "
                    + "intentional by the verb — no explicit confirmation field is needed (unlike the "
                    + "COBOL PF5 confirmation). Returns 204 No Content on success "
                    + "('User ___ has been deleted ...').")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User deleted successfully (no body)"),
            @ApiResponse(responseCode = "400", description = "'User ID can NOT be empty...'"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "403", description = "User does not have ADMIN role"),
            @ApiResponse(responseCode = "404", description = "'User ID NOT found...'")
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(
            @Parameter(description = "1-8 character uppercase user ID", example = "USER0001")
            @PathVariable("userId")
            @NotBlank(message = "User ID can NOT be empty...")
            @Size(min = 1, max = 8, message = "User ID can NOT be empty...")
            @Pattern(regexp = "[A-Z0-9]+", message = "User ID must be uppercase alphanumeric")
            String userId) {

        log.info("DELETE /api/admin/users/{}", userId);
        userService.deleteUser(userId);
        log.info("DELETE /api/admin/users/{} completed", userId);
    }
}
