/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.entity.UserSecurity;
import com.cardemo.service.online.UserAddService;
import com.cardemo.service.online.UserDeleteService;
import com.cardemo.service.online.UserListService;
import com.cardemo.service.online.UserUpdateService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for admin-only user CRUD operations — translates CICS
 * transactions CU00–CU03 (COUSR00C–COUSR03C.cbl) into stateless REST
 * endpoints under {@code /api/admin/users}.
 *
 * <p>This controller combines four COBOL programs into a single REST
 * resource:</p>
 *
 * <h2>Endpoint Mapping (← COBOL Source)</h2>
 * <table>
 *   <tr><th>Endpoint</th><th>COBOL Program</th><th>Transaction</th>
 *       <th>Description</th></tr>
 *   <tr>
 *     <td>{@code GET /api/admin/users}</td>
 *     <td>COUSR00C.cbl</td><td>CU00</td>
 *     <td>Paginated user list — STARTBR/READNEXT on USRSEC</td>
 *   </tr>
 *   <tr>
 *     <td>{@code GET /api/admin/users/{userId}}</td>
 *     <td>COUSR02C.cbl</td><td>CU02</td>
 *     <td>Single-user read for edit preparation — READ-USER-SEC-FILE</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST /api/admin/users}</td>
 *     <td>COUSR01C.cbl</td><td>CU01</td>
 *     <td>User creation with BCrypt password hashing — WRITE-USER-SEC-FILE</td>
 *   </tr>
 *   <tr>
 *     <td>{@code PUT /api/admin/users/{userId}}</td>
 *     <td>COUSR02C.cbl</td><td>CU02</td>
 *     <td>User update with optimistic locking — UPDATE-USER-SEC-FILE</td>
 *   </tr>
 *   <tr>
 *     <td>{@code DELETE /api/admin/users/{userId}}</td>
 *     <td>COUSR03C.cbl</td><td>CU03</td>
 *     <td>User deletion — DELETE-USER-SEC-FILE</td>
 *   </tr>
 * </table>
 *
 * <h2>Security</h2>
 * <p>All endpoints require the {@code ADMIN} role, enforced by
 * {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")} at the class level.
 * This maps the COBOL 88-level condition
 * {@code CDEMO-USRTYP-ADMIN VALUE 'A'} from COCOM01Y.cpy, which every
 * COUSR program checks before allowing operations.</p>
 *
 * <h2>Password Security</h2>
 * <ul>
 *   <li>Passwords are <strong>NEVER</strong> included in any response body.</li>
 *   <li>Passwords are <strong>NEVER</strong> logged.</li>
 *   <li>BCrypt hashing is performed by the service layer, not this
 *       controller.</li>
 *   <li>The original COBOL stored plaintext SEC-USR-PWD PIC X(08); this
 *       migration uses BCrypt (60–72 char hashes).</li>
 * </ul>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><strong>Delegation only</strong> — All business logic resides in the
 *       injected services. This controller handles HTTP mapping exclusively.</li>
 *   <li><strong>Stateless</strong> — CICS pseudo-conversational model mapped to
 *       stateless REST. No session state in the controller.</li>
 *   <li><strong>No feature expansion</strong> — Only CRUD operations per the
 *       original COBOL programs. No password reset, bulk operations, or other
 *       additions.</li>
 * </ul>
 *
 * @see UserListService
 * @see UserAddService
 * @see UserUpdateService
 * @see UserDeleteService
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private static final Logger LOG =
            LoggerFactory.getLogger(UserAdminController.class);

    private final UserListService userListService;
    private final UserAddService userAddService;
    private final UserUpdateService userUpdateService;
    private final UserDeleteService userDeleteService;

    /**
     * Constructs the controller with all required service dependencies.
     *
     * <p>Each service maps to one COBOL program:</p>
     * <ul>
     *   <li>{@code userListService} → COUSR00C.cbl (CU00)</li>
     *   <li>{@code userAddService} → COUSR01C.cbl (CU01)</li>
     *   <li>{@code userUpdateService} → COUSR02C.cbl (CU02)</li>
     *   <li>{@code userDeleteService} → COUSR03C.cbl (CU03)</li>
     * </ul>
     *
     * @param userListService   paginated user list operations
     * @param userAddService    new user creation with BCrypt hashing
     * @param userUpdateService user update with optimistic locking
     * @param userDeleteService user deletion with existence verification
     */
    public UserAdminController(UserListService userListService,
                               UserAddService userAddService,
                               UserUpdateService userUpdateService,
                               UserDeleteService userDeleteService) {
        this.userListService = userListService;
        this.userAddService = userAddService;
        this.userUpdateService = userUpdateService;
        this.userDeleteService = userDeleteService;
    }

    // =========================================================================
    // GET /api/admin/users — List Users (← COUSR00C.cbl, CU00)
    // =========================================================================

    /**
     * Lists users with optional filtering and pagination.
     *
     * <p>Maps to COUSR00C.cbl MAIN-PARA → PROCESS-PAGE-FORWARD /
     * PROCESS-PAGE-BACKWARD with STARTBR/READNEXT/READPREV on the USRSEC
     * dataset. PF7/PF8 page navigation is replaced by {@code page} query
     * parameter. The COBOL WS-MAX-SCREEN-LINES constant (10 records per
     * page) is preserved in the service layer.</p>
     *
     * <p><strong>Password exclusion</strong>: Each user record in the
     * returned page is sanitized to exclude the password hash.</p>
     *
     * @param userIdFilter optional user ID prefix filter; maps to USRIDIN
     *                     search field in COUSR00.bms
     * @param page         zero-based page index; maps to PF7/PF8 navigation
     * @param size         requested page size (service enforces 10 per COBOL
     *                     WS-MAX-SCREEN-LINES)
     * @return paginated list of sanitized user records (200 OK)
     */
    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> listUsers(
            @RequestParam(required = false) String userIdFilter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        LOG.info("GET /api/admin/users — filter: '{}', page: {}, size: {}",
                userIdFilter != null ? userIdFilter : "*", page, size);

        Page<UserSecurity> result = userListService.listUsers(userIdFilter, page);

        // Transform each entity to a password-free response map.
        // Password MUST NEVER appear in any API response.
        Page<Map<String, Object>> sanitized = result.map(this::toUserResponse);

        LOG.debug("Returning {} users (page {} of {})",
                result.getNumberOfElements(), result.getNumber(),
                result.getTotalPages());

        return ResponseEntity.ok(sanitized);
    }

    // =========================================================================
    // GET /api/admin/users/{userId} — Get Single User (← COUSR02C.cbl, CU02)
    // =========================================================================

    /**
     * Retrieves a single user by ID for viewing or edit preparation.
     *
     * <p>Maps to COUSR02C.cbl READ-USER-SEC-FILE (line 320) — a keyed read
     * on USRSEC by SEC-USR-ID. The CICS {@code UPDATE} option (which
     * obtains an exclusive lock) is replaced by JPA {@code @Version}-based
     * optimistic locking on the entity.</p>
     *
     * <p><strong>Password exclusion</strong>: The returned user record is
     * sanitized to exclude the password hash.</p>
     *
     * @param userId the user ID to look up; maps to SEC-USR-ID PIC X(08)
     * @return user details without password (200 OK), or 404 if not found
     */
    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> getUser(
            @PathVariable String userId) {

        LOG.info("GET /api/admin/users/{} — retrieving user details", userId);

        try {
            UserSecurity user = userUpdateService.readUserSecFile(userId);
            LOG.debug("User '{}' found — type: {}", user.getUserId(),
                    user.getUserType());
            return ResponseEntity.ok(toUserResponse(user));
        } catch (RecordNotFoundException ex) {
            // Maps COBOL DFHRESP(NOTFND) — VSAM status '23'
            LOG.warn("User not found: '{}' — returning 404", userId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(errorBody(ex.getMessage()));
        }
    }

    // =========================================================================
    // POST /api/admin/users — Create User (← COUSR01C.cbl, CU01)
    // =========================================================================

    /**
     * Creates a new user with BCrypt-hashed password.
     *
     * <p>Maps to COUSR01C.cbl MAIN-PARA (line 71) → PROCESS-ENTER-KEY
     * (line 115) → WRITE-USER-SEC-FILE (line 238). The COBOL flow validates
     * all fields (FNAME, LNAME, USERID, PASSWD, USRTYPE) as non-blank,
     * checks for duplicate SEC-USR-ID, then writes to USRSEC.</p>
     *
     * <h3>Password Handling</h3>
     * <p>COBOL stored plaintext {@code SEC-USR-PWD PIC X(08)}. This
     * migration uses BCrypt hashing (60–72 char hashes). The service layer
     * performs the hashing; the controller never touches the raw password
     * beyond passing the request DTO.</p>
     *
     * <p><strong>Password exclusion</strong>: The response body contains
     * only userId, firstName, lastName, and userType — never the password
     * or its hash.</p>
     *
     * @param request user creation data including userId, password,
     *                firstName, lastName, and userType
     * @return created user without password (201 Created), or error status
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createUser(
            @RequestBody UserAddService.UserAddRequest request) {

        // Log userId only — NEVER log password
        LOG.info("POST /api/admin/users — creating user '{}'",
                request.getUserId());

        try {
            UserSecurity created = userAddService.addUser(request);
            LOG.info("User '{}' created successfully — type: {}",
                    created.getUserId(), created.getUserType());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(toUserResponse(created));

        } catch (DuplicateRecordException ex) {
            // Maps COBOL RESP=22 DUPREC — VSAM status '22'
            LOG.warn("Duplicate user ID on create: '{}' — returning 409",
                    request.getUserId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorBody(ex.getMessage()));

        } catch (ValidationException ex) {
            // Maps COBOL field validation in PROCESS-ENTER-KEY
            LOG.warn("Validation failed for user creation '{}': {}",
                    request.getUserId(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorBody(ex.getMessage()));
        }
    }

    // =========================================================================
    // PUT /api/admin/users/{userId} — Update User (← COUSR02C.cbl, CU02)
    // =========================================================================

    /**
     * Updates an existing user's information.
     *
     * <p>Maps to COUSR02C.cbl MAIN-PARA (line 82) → PROCESS-ENTER-KEY →
     * UPDATE-USER-INFO → UPDATE-USER-SEC-FILE (line 358). The COBOL flow
     * performs a READ UPDATE (exclusive lock), compares fields for
     * modifications, and if changed, performs a REWRITE.</p>
     *
     * <h3>Optimistic Locking</h3>
     * <p>The CICS {@code READ UPDATE → REWRITE} pattern (pessimistic lock)
     * is replaced by JPA {@code @Version}-based optimistic locking. If the
     * user record was modified concurrently, an
     * {@link OptimisticLockingFailureException} is caught and mapped to
     * HTTP 409 Conflict.</p>
     *
     * <h3>Password Handling</h3>
     * <p>If {@code newPassword} is provided in the request, the service
     * layer BCrypt-hashes it before storage. The response never includes
     * the password or its hash.</p>
     *
     * <p>Note: {@code userId} is immutable — it is the SEC-USR-ID primary
     * key (RIDFLD) and cannot be changed.</p>
     *
     * @param userId  the user ID to update; maps to SEC-USR-ID PIC X(08)
     * @param request update data including firstName, lastName, optional
     *                newPassword, and userType
     * @return updated user without password (200 OK), or error status
     */
    @PutMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable String userId,
            @RequestBody UserUpdateService.UserUpdateRequest request) {

        // Log userId only — NEVER log password
        LOG.info("PUT /api/admin/users/{} — updating user", userId);

        try {
            UserSecurity updated = userUpdateService.updateUser(userId, request);
            LOG.info("User '{}' updated successfully", updated.getUserId());
            return ResponseEntity.ok(toUserResponse(updated));

        } catch (RecordNotFoundException ex) {
            // Maps COBOL DFHRESP(NOTFND) — user not in USRSEC
            LOG.warn("User not found for update: '{}' — returning 404", userId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(errorBody(ex.getMessage()));

        } catch (ValidationException ex) {
            // Maps COBOL UPDATE-USER-INFO field validation / "Please modify
            // to update" check
            LOG.warn("Validation failed for user update '{}': {}",
                    userId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorBody(ex.getMessage()));

        } catch (OptimisticLockingFailureException ex) {
            // Maps COBOL concurrent access failure on REWRITE —
            // "Unable to Update User..." error path
            LOG.warn("Optimistic lock conflict updating user '{}' — "
                    + "returning 409", userId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorBody("User was modified by another session. "
                            + "Please refresh and try again."));
        }
    }

    // =========================================================================
    // DELETE /api/admin/users/{userId} — Delete User (← COUSR03C.cbl, CU03)
    // =========================================================================

    /**
     * Deletes a user by ID.
     *
     * <p>Maps to COUSR03C.cbl MAIN-PARA (line 82) → PROCESS-ENTER-KEY →
     * DELETE-USER-INFO → DELETE-USER-SEC-FILE (line 305). The COBOL
     * two-step confirmation pattern (display details → confirm with 'Y')
     * is mapped to REST semantics: the client retrieves user details via
     * GET first (for confirmation display), then issues DELETE to perform
     * the action.</p>
     *
     * <h3>COBOL Delete Pattern Translated</h3>
     * <ol>
     *   <li>READ to verify existence:
     *       {@code EXEC CICS READ DATASET(WS-USRSEC-FILE)} →
     *       {@code userSecurityRepository.findById()}</li>
     *   <li>DELETE:
     *       {@code EXEC CICS DELETE DATASET(WS-USRSEC-FILE) RIDFLD(SEC-USR-ID)}
     *       → {@code userSecurityRepository.deleteById()}</li>
     * </ol>
     *
     * @param userId the user ID to delete; maps to SEC-USR-ID PIC X(08)
     * @return 204 No Content on success, or 404 if user not found
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable String userId) {

        LOG.info("DELETE /api/admin/users/{} — deleting user", userId);

        try {
            // REST DELETE is inherently confirmed — pass confirmed=true
            userDeleteService.deleteUser(userId, true);
            LOG.info("User '{}' deleted successfully", userId);
            return ResponseEntity.noContent().build();

        } catch (RecordNotFoundException ex) {
            // Maps COBOL DFHRESP(NOTFND) — user not in USRSEC
            LOG.warn("User not found for deletion: '{}' — returning 404",
                    userId);
            return ResponseEntity.notFound().build();
        }
    }

    // =========================================================================
    // Private Helper Methods
    // =========================================================================

    /**
     * Converts a {@link UserSecurity} entity to a password-free response map.
     *
     * <p><strong>CRITICAL SECURITY</strong>: This method deliberately excludes
     * the password field. The COBOL stored plaintext SEC-USR-PWD PIC X(08);
     * Java stores a BCrypt hash. Neither the plaintext nor the hash may
     * appear in any API response.</p>
     *
     * <p>The returned map preserves insertion order (via {@link LinkedHashMap})
     * for consistent JSON serialization:</p>
     * <ul>
     *   <li>{@code userId} — SEC-USR-ID PIC X(08)</li>
     *   <li>{@code firstName} — SEC-USR-FNAME PIC X(20)</li>
     *   <li>{@code lastName} — SEC-USR-LNAME PIC X(20)</li>
     *   <li>{@code userType} — SEC-USR-TYPE PIC X(01), mapped to
     *       {@code UserType} enum (ADMIN/USER)</li>
     * </ul>
     *
     * @param user the entity to sanitize; must not be null
     * @return ordered map with userId, firstName, lastName, userType
     */
    private Map<String, Object> toUserResponse(UserSecurity user) {
        Map<String, Object> response = new LinkedHashMap<>(4);
        response.put("userId", user.getUserId());
        response.put("firstName", user.getFirstName());
        response.put("lastName", user.getLastName());
        response.put("userType", user.getUserType());
        return response;
    }

    /**
     * Creates a standardized error response body.
     *
     * @param message the error message to include
     * @return map with a single "error" key
     */
    private static Map<String, Object> errorBody(String message) {
        return Map.of("error", message != null ? message : "Unknown error");
    }
}
