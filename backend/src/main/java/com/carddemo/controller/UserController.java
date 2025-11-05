/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
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

package com.carddemo.controller;

import com.carddemo.dto.request.UserManagementRequest;
import com.carddemo.dto.request.UserProfileUpdateRequest;
import com.carddemo.dto.response.UserProfileResponse;
import com.carddemo.exception.UserNotFoundException;
import com.carddemo.service.UserManagementService;
import com.carddemo.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * REST controller for comprehensive user management operations.
 * 
 * <p>Transforms COBOL CICS user management transaction programs to Spring Boot REST endpoints:</p>
 * <ul>
 *   <li>COUSR00C.cbl (Transaction CU00) - User list with pagination → GET /api/users</li>
 *   <li>COUSR01C.cbl (Transaction CU01) - User profile view/edit → GET/PUT /api/users/{userId}</li>
 *   <li>User creation operations → POST /api/users</li>
 *   <li>User deletion operations → DELETE /api/users/{userId}</li>
 * </ul>
 * 
 * <p><strong>COBOL Source Mapping:</strong></p>
 * <pre>
 * COUSR00C.cbl (User List):
 * - Transaction: CU00
 * - File: USRSEC (User Security File)
 * - Operations: EXEC CICS STARTBR/READNEXT (sequential browse with pagination)
 * - Display: 10 users per screen page
 * - Selection: 'U' for update, 'D' for delete
 * - PF3: Return to admin menu
 * - PF7: Backward page navigation
 * - PF8: Forward page navigation
 * 
 * COUSR01C.cbl (User Add/Edit):
 * - Transaction: CU01
 * - Operations: EXEC CICS WRITE (new user), EXEC CICS REWRITE (update user)
 * - Validation: firstName, lastName, userId, password, userType all required
 * - PF3: Return to admin menu
 * - PF4: Clear form
 * - Error handling: Duplicate user ID check, field validation
 * </pre>
 * 
 * <p><strong>Security Model (Section 0.9):</strong></p>
 * <ul>
 *   <li>GET /api/users: ROLE_ADMIN only (admin user list)</li>
 *   <li>GET /api/users/{userId}: ROLE_USER (own profile) or ROLE_ADMIN (any profile)</li>
 *   <li>PUT /api/users/{userId}: ROLE_USER (own profile) or ROLE_ADMIN (any profile)</li>
 *   <li>POST /api/users: ROLE_ADMIN only (create new user)</li>
 *   <li>DELETE /api/users/{userId}: ROLE_ADMIN only (delete user)</li>
 *   <li>Password encryption: BCrypt with strength 12</li>
 *   <li>Two-tier role model: 'R' = ROLE_USER, 'A' = ROLE_ADMIN</li>
 * </ul>
 * 
 * <p><strong>Response Codes:</strong></p>
 * <ul>
 *   <li>200 OK: Successful GET/PUT operations</li>
 *   <li>201 Created: Successful POST operation (new user created)</li>
 *   <li>204 No Content: Successful DELETE operation</li>
 *   <li>400 Bad Request: Validation errors, invalid data</li>
 *   <li>401 Unauthorized: Missing or invalid authentication</li>
 *   <li>403 Forbidden: Insufficient permissions for operation</li>
 *   <li>404 Not Found: User ID does not exist</li>
 *   <li>500 Internal Server Error: Unexpected server error</li>
 * </ul>
 * 
 * <p><strong>Performance Targets (Section 0.9):</strong></p>
 * <ul>
 *   <li>User lookup: < 100ms average response time</li>
 *   <li>User list with pagination: < 200ms for 10 records per page</li>
 *   <li>User create/update/delete: < 200ms including BCrypt password encryption</li>
 *   <li>Concurrent user support: 150+ users minimum</li>
 * </ul>
 * 
 * <p><strong>Transaction Management:</strong></p>
 * <ul>
 *   <li>All data modifications delegated to service layer with @Transactional</li>
 *   <li>Controller layer remains stateless and transaction-free</li>
 *   <li>CICS SYNCPOINT equivalent handled by service @Transactional boundaries</li>
 * </ul>
 * 
 * <p><strong>Audit Trail Requirements:</strong></p>
 * <ul>
 *   <li>All user management operations logged with: operation type, target userId, executor, timestamp</li>
 *   <li>Password changes logged without exposing actual passwords</li>
 *   <li>Failed operations logged with reason for audit compliance</li>
 *   <li>Maintains mainframe audit trail completeness per Section 0.9</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see UserManagementService
 * @see UserProfileService
 * @see UserProfileResponse
 * @see UserManagementRequest
 * @see UserProfileUpdateRequest
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserManagementService userManagementService;
    private final UserProfileService userProfileService;

    /**
     * Constructor-based dependency injection for UserController.
     * 
     * <p>Replaces COBOL CALL statements with Spring IoC container managed dependencies.
     * Constructor injection ensures immutable dependencies and facilitates unit testing.</p>
     * 
     * @param userManagementService service for administrative user CRUD operations
     * @param userProfileService service for user profile view and update operations
     */
    @Autowired
    public UserController(
            UserManagementService userManagementService,
            UserProfileService userProfileService) {
        this.userManagementService = userManagementService;
        this.userProfileService = userProfileService;
        log.info("UserController initialized with UserManagementService and UserProfileService");
    }

    /**
     * Retrieves paginated list of all users.
     * 
     * <p>Transforms COUSR00C.cbl user list screen to REST endpoint with pagination support.</p>
     * 
     * <p><strong>COBOL Equivalent (COUSR00C.cbl):</strong></p>
     * <pre>
     * Transaction: CU00
     * Screen: COUSR0A (User List Screen)
     * Operations:
     * - EXEC CICS STARTBR DATASET('USRSEC') (lines 588-595)
     * - PERFORM READNEXT-USER-SEC-FILE 10 times for page display
     * - PF7/PF8 for pagination navigation
     * - Display: 10 users per screen (rows 01-10)
     * </pre>
     * 
     * <p><strong>Authorization:</strong> ROLE_ADMIN only. Regular users cannot list all users.</p>
     * 
     * <p><strong>Pagination Defaults:</strong></p>
     * <ul>
     *   <li>Default page: 0 (first page, 0-indexed)</li>
     *   <li>Default size: 10 (matches COBOL 10 users per screen)</li>
     *   <li>Sort: userId ascending (matches VSAM key-sequenced file order)</li>
     * </ul>
     * 
     * @param page page number (0-indexed), defaults to 0 if not specified
     * @param size page size (number of users per page), defaults to 10 if not specified
     * @return ResponseEntity containing Page of UserProfileResponse objects
     *         HTTP 200 OK: User list retrieved successfully
     *         HTTP 401 Unauthorized: Missing or invalid authentication
     *         HTTP 403 Forbidden: User does not have ROLE_ADMIN
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<UserProfileResponse>> getUserList(
            @RequestParam(name = "page", defaultValue = "0") Integer page,
            @RequestParam(name = "size", defaultValue = "10") Integer size) {
        
        log.info("GET /api/users - Retrieving user list: page={}, size={}", page, size);
        
        try {
            // Validate pagination parameters
            if (page < 0) {
                log.warn("Invalid page number: {}. Setting to 0", page);
                page = 0;
            }
            if (size < 1 || size > 100) {
                log.warn("Invalid page size: {}. Setting to default 10", size);
                size = 10;
            }
            
            // Create pageable with sort by userId ascending (matches VSAM KSDS order)
            Pageable pageable = PageRequest.of(page, size, Sort.by("userId").ascending());
            
            // Retrieve user list from service
            Page<UserProfileResponse> userPage = userManagementService.listUsers(pageable);
            
            log.info("Successfully retrieved {} users from page {} of {}",
                userPage.getNumberOfElements(),
                userPage.getNumber() + 1,
                userPage.getTotalPages());
            
            // Audit log for compliance
            log.info("AUDIT: User list accessed - page={}, size={}, totalUsers={}, executor={}",
                page, size, userPage.getTotalElements(), getCurrentUserId());
            
            return ResponseEntity.ok(userPage);
            
        } catch (Exception e) {
            log.error("Error retrieving user list: page={}, size={}", page, size, e);
            throw e;
        }
    }

    /**
     * Retrieves user profile details for the specified user ID.
     * 
     * <p>Transforms COUSR01C.cbl user profile view to REST endpoint.</p>
     * 
     * <p><strong>COBOL Equivalent (COUSR01C.cbl - conceptual READ):</strong></p>
     * <pre>
     * Transaction: CU01
     * Screen: COUSR1A (User Profile Screen)
     * Operation: Conceptual EXEC CICS READ DATASET('USRSEC') RIDFLD(userId)
     * Display fields: FNAMEO, LNAMEO, USERIDO, USRTYPEO (password excluded for security)
     * </pre>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>ROLE_USER: Can view own profile (userId matches authenticated user)</li>
     *   <li>ROLE_ADMIN: Can view any user profile</li>
     * </ul>
     * 
     * @param userId the unique identifier of the user to retrieve (max 8 characters)
     * @param authentication Spring Security authentication object containing current user context
     * @return ResponseEntity containing UserProfileResponse
     *         HTTP 200 OK: User profile retrieved successfully
     *         HTTP 401 Unauthorized: Missing or invalid authentication
     *         HTTP 403 Forbidden: User attempting to access another user's profile (non-admin)
     *         HTTP 404 Not Found: User ID does not exist
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserProfileResponse> getUserById(
            @PathVariable("userId") String userId,
            Authentication authentication) {
        
        log.info("GET /api/users/{} - Retrieving user profile", userId);
        
        try {
            // Validate userId parameter
            if (userId == null || userId.trim().isEmpty()) {
                log.error("User ID cannot be null or empty");
                return ResponseEntity.badRequest().build();
            }
            
            // Extract authenticated user ID and roles
            String authenticatedUserId = authentication.getName();
            boolean isAdmin = hasRole(authentication, "ROLE_ADMIN");
            
            // Authorization check: users can only view own profile unless admin
            if (!isAdmin && !userId.trim().equalsIgnoreCase(authenticatedUserId)) {
                log.warn("SECURITY: User {} attempted to access profile of user {} without ROLE_ADMIN",
                    authenticatedUserId, userId);
                log.info("AUDIT: Unauthorized profile access attempt - targetUser={}, executor={}",
                    userId, authenticatedUserId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Retrieve user profile from service
            UserProfileResponse userProfile = userProfileService.viewUserProfile(userId.trim());
            
            log.info("Successfully retrieved user profile: userId={}", userId);
            
            // Audit log for compliance
            log.info("AUDIT: User profile viewed - targetUser={}, executor={}", userId, authenticatedUserId);
            
            return ResponseEntity.ok(userProfile);
            
        } catch (UserNotFoundException e) {
            log.warn("User not found: userId={}", userId);
            log.info("AUDIT: Profile view failed - targetUser={}, reason=NOT_FOUND, executor={}",
                userId, getCurrentUserId());
            return ResponseEntity.notFound().build();
            
        } catch (Exception e) {
            log.error("Error retrieving user profile: userId={}", userId, e);
            throw e;
        }
    }

    /**
     * Updates user profile information including name, user type, and optionally password.
     * 
     * <p>Transforms COUSR01C.cbl user profile update to REST endpoint with transaction management.</p>
     * 
     * <p><strong>COBOL Equivalent (COUSR01C.cbl WRITE-USER-SEC-FILE, lines 238-274):</strong></p>
     * <pre>
     * MOVE FNAMEI   OF COUSR1AI TO SEC-USR-FNAME
     * MOVE LNAMEI   OF COUSR1AI TO SEC-USR-LNAME
     * MOVE PASSWDI  OF COUSR1AI TO SEC-USR-PWD
     * MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE
     * EXEC CICS WRITE
     *      DATASET   (WS-USRSEC-FILE)
     *      FROM      (SEC-USER-DATA)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * WHEN DFHRESP(NORMAL) - success
     * WHEN DFHRESP(DUPKEY) - duplicate user error
     * </pre>
     * 
     * <p><strong>Authorization:</strong></p>
     * <ul>
     *   <li>ROLE_USER: Can update own profile (userId matches authenticated user)</li>
     *   <li>ROLE_ADMIN: Can update any user profile including user type changes</li>
     *   <li>User type changes restricted to ROLE_ADMIN only</li>
     * </ul>
     * 
     * <p><strong>Validation Rules (from COUSR01C.cbl PROCESS-ENTER-KEY, lines 117-151):</strong></p>
     * <ul>
     *   <li>First name cannot be empty (COBOL lines 118-123)</li>
     *   <li>Last name cannot be empty (COBOL lines 124-129)</li>
     *   <li>User ID cannot be empty (COBOL lines 130-135)</li>
     *   <li>User type cannot be empty (COBOL lines 142-147)</li>
     *   <li>Password validated if password change requested</li>
     * </ul>
     * 
     * @param userId the unique identifier of the user to update (max 8 characters)
     * @param request the profile update request containing modified fields
     * @param authentication Spring Security authentication object containing current user context
     * @return ResponseEntity containing updated UserProfileResponse
     *         HTTP 200 OK: Profile updated successfully
     *         HTTP 400 Bad Request: Validation errors or invalid data
     *         HTTP 401 Unauthorized: Missing or invalid authentication
     *         HTTP 403 Forbidden: User attempting to update another user's profile (non-admin)
     *         HTTP 404 Not Found: User ID does not exist
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserProfileResponse> updateUserProfile(
            @PathVariable("userId") String userId,
            @Valid @RequestBody UserProfileUpdateRequest request,
            Authentication authentication) {
        
        log.info("PUT /api/users/{} - Updating user profile", userId);
        
        try {
            // Validate userId parameter
            if (userId == null || userId.trim().isEmpty()) {
                log.error("User ID cannot be null or empty");
                return ResponseEntity.badRequest().build();
            }
            
            // Extract authenticated user ID and roles
            String authenticatedUserId = authentication.getName();
            boolean isAdmin = hasRole(authentication, "ROLE_ADMIN");
            
            // Authorization check: users can only update own profile unless admin
            if (!isAdmin && !userId.trim().equalsIgnoreCase(authenticatedUserId)) {
                log.warn("SECURITY: User {} attempted to update profile of user {} without ROLE_ADMIN",
                    authenticatedUserId, userId);
                log.info("AUDIT: Unauthorized profile update attempt - targetUser={}, executor={}",
                    userId, authenticatedUserId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Ensure request userId matches path userId
            if (request.getUserId() != null && 
                !request.getUserId().trim().equalsIgnoreCase(userId.trim())) {
                log.error("User ID mismatch: path={}, body={}", userId, request.getUserId());
                return ResponseEntity.badRequest().build();
            }
            
            // Set userId in request if not present
            if (request.getUserId() == null || request.getUserId().trim().isEmpty()) {
                request.setUserId(userId.trim());
            }
            
            // Check for user type change attempt by non-admin
            if (!isAdmin && request.getUserType() != null) {
                log.warn("SECURITY: Non-admin user {} attempted to change user type", authenticatedUserId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            // Update user profile via service
            UserProfileResponse updatedProfile = 
                userProfileService.updateUserProfile(userId.trim(), request);
            
            log.info("Successfully updated user profile: userId={}", userId);
            
            // Audit log for compliance
            boolean passwordChanged = request.getNewPassword() != null && 
                                     !request.getNewPassword().trim().isEmpty();
            log.info("AUDIT: User profile updated - targetUser={}, passwordChanged={}, executor={}",
                userId, passwordChanged, authenticatedUserId);
            
            return ResponseEntity.ok(updatedProfile);
            
        } catch (UserNotFoundException e) {
            log.warn("User not found for update: userId={}", userId);
            log.info("AUDIT: Profile update failed - targetUser={}, reason=NOT_FOUND, executor={}",
                userId, getCurrentUserId());
            return ResponseEntity.notFound().build();
            
        } catch (IllegalArgumentException e) {
            log.error("Validation error during profile update: userId={}, error={}", userId, e.getMessage());
            return ResponseEntity.badRequest().build();
            
        } catch (Exception e) {
            log.error("Error updating user profile: userId={}", userId, e);
            throw e;
        }
    }

    /**
     * Creates a new user with BCrypt encrypted password.
     * 
     * <p>Transforms COUSR01C.cbl user creation to REST endpoint with enhanced security.</p>
     * 
     * <p><strong>COBOL Equivalent (COUSR01C.cbl WRITE-USER-SEC-FILE, lines 238-274):</strong></p>
     * <pre>
     * MOVE USERIDI  OF COUSR1AI TO SEC-USR-ID
     * MOVE FNAMEI   OF COUSR1AI TO SEC-USR-FNAME
     * MOVE LNAMEI   OF COUSR1AI TO SEC-USR-LNAME
     * MOVE PASSWDI  OF COUSR1AI TO SEC-USR-PWD (plain text in COBOL)
     * MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE
     * EXEC CICS WRITE DATASET('USRSEC') FROM(SEC-USER-DATA) END-EXEC.
     * 
     * WHEN DFHRESP(NORMAL)
     *     STRING 'User ' SEC-USR-ID ' has been added ...' INTO WS-MESSAGE
     * WHEN DFHRESP(DUPKEY) OR DFHRESP(DUPREC)
     *     MOVE 'User ID already exist...' TO WS-MESSAGE
     * </pre>
     * 
     * <p><strong>Authorization:</strong> ROLE_ADMIN only. Regular users cannot create new users.</p>
     * 
     * <p><strong>Security Enhancements:</strong></p>
     * <ul>
     *   <li>Password encrypted using BCrypt with strength 12 (not plain text like COBOL)</li>
     *   <li>User type validated: must be 'R' (Regular) or 'A' (Admin)</li>
     *   <li>Duplicate user ID check before creation</li>
     * </ul>
     * 
     * @param request the user creation request containing userId, firstName, lastName, password, userType
     * @return ResponseEntity containing created UserProfileResponse
     *         HTTP 201 Created: User created successfully
     *         HTTP 400 Bad Request: Validation errors or duplicate user ID
     *         HTTP 401 Unauthorized: Missing or invalid authentication
     *         HTTP 403 Forbidden: User does not have ROLE_ADMIN
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserProfileResponse> createUser(
            @Valid @RequestBody UserManagementRequest request) {
        
        log.info("POST /api/users - Creating new user: userId={}", request.getUserId());
        
        try {
            // Validate required fields for user creation
            if (request.getUserId() == null || request.getUserId().trim().isEmpty()) {
                log.error("User ID is required for user creation");
                return ResponseEntity.badRequest().build();
            }
            if (request.getFirstName() == null || request.getFirstName().trim().isEmpty()) {
                log.error("First name is required for user creation");
                return ResponseEntity.badRequest().build();
            }
            if (request.getLastName() == null || request.getLastName().trim().isEmpty()) {
                log.error("Last name is required for user creation");
                return ResponseEntity.badRequest().build();
            }
            if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
                log.error("Password is required for user creation");
                return ResponseEntity.badRequest().build();
            }
            if (request.getUserType() == null || request.getUserType().trim().isEmpty()) {
                log.error("User type is required for user creation");
                return ResponseEntity.badRequest().build();
            }
            
            // Validate user type is 'R' or 'A'
            String userType = request.getUserType().toUpperCase();
            if (!userType.equals("R") && !userType.equals("A")) {
                log.error("Invalid user type: {}. Must be 'R' or 'A'", request.getUserType());
                return ResponseEntity.badRequest().build();
            }
            
            // Create new user via service (includes BCrypt password encryption)
            UserProfileResponse createdUser = userManagementService.createUser(request);
            
            log.info("Successfully created new user: userId={}", request.getUserId());
            
            // Audit log for compliance
            log.info("AUDIT: User created - newUser={}, userType={}, executor={}",
                request.getUserId(), request.getUserType(), getCurrentUserId());
            
            // Return 201 Created with created user profile
            return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
            
        } catch (IllegalArgumentException e) {
            log.error("Validation error during user creation: userId={}, error={}",
                request.getUserId(), e.getMessage());
            log.info("AUDIT: User creation failed - userId={}, reason=VALIDATION_ERROR, executor={}",
                request.getUserId(), getCurrentUserId());
            return ResponseEntity.badRequest().build();
            
        } catch (Exception e) {
            // Handle duplicate user ID error
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                log.warn("User creation failed: User ID '{}' already exists", request.getUserId());
                log.info("AUDIT: User creation failed - userId={}, reason=DUPLICATE_USER_ID, executor={}",
                    request.getUserId(), getCurrentUserId());
                return ResponseEntity.badRequest().build();
            }
            
            log.error("Error creating user: userId={}", request.getUserId(), e);
            throw e;
        }
    }

    /**
     * Deletes a user by user ID.
     * 
     * <p>Transforms COBOL user deletion operations to REST endpoint.</p>
     * 
     * <p><strong>COBOL Equivalent (COUSR03C.cbl - conceptual):</strong></p>
     * <pre>
     * EXEC CICS READ UPDATE DATASET('USRSEC') RIDFLD(SEC-USR-ID) END-EXEC.
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *     EXEC CICS DELETE DATASET('USRSEC') END-EXEC
     *     MOVE 'User deleted successfully' TO WS-MESSAGE
     * ELSE WHEN WS-RESP-CD = 13
     *     MOVE 'User not found...' TO WS-MESSAGE
     * END-IF.
     * </pre>
     * 
     * <p><strong>Authorization:</strong> ROLE_ADMIN only. Regular users cannot delete users.</p>
     * 
     * <p><strong>Important Note:</strong> This is a permanent deletion operation. Consider implementing
     * soft delete (deactivation) in production systems for audit trail preservation.</p>
     * 
     * @param userId the unique identifier of the user to delete (max 8 characters)
     * @return ResponseEntity with no content
     *         HTTP 204 No Content: User deleted successfully
     *         HTTP 401 Unauthorized: Missing or invalid authentication
     *         HTTP 403 Forbidden: User does not have ROLE_ADMIN
     *         HTTP 404 Not Found: User ID does not exist
     */
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable("userId") String userId) {
        
        log.info("DELETE /api/users/{} - Deleting user", userId);
        
        try {
            // Validate userId parameter
            if (userId == null || userId.trim().isEmpty()) {
                log.error("User ID cannot be null or empty");
                return ResponseEntity.badRequest().build();
            }
            
            // Delete user via service
            userManagementService.deleteUser(userId.trim());
            
            log.info("Successfully deleted user: userId={}", userId);
            
            // Audit log for compliance
            log.info("AUDIT: User deleted - deletedUser={}, executor={}", userId, getCurrentUserId());
            
            // Return 204 No Content on successful deletion
            return ResponseEntity.noContent().build();
            
        } catch (UserNotFoundException e) {
            log.warn("User not found for deletion: userId={}", userId);
            log.info("AUDIT: User deletion failed - targetUser={}, reason=NOT_FOUND, executor={}",
                userId, getCurrentUserId());
            return ResponseEntity.notFound().build();
            
        } catch (Exception e) {
            log.error("Error deleting user: userId={}", userId, e);
            throw e;
        }
    }

    /**
     * Helper method to check if the authenticated user has a specific role.
     * 
     * @param authentication Spring Security authentication object
     * @param roleName the role name to check (e.g., "ROLE_ADMIN")
     * @return true if user has the specified role, false otherwise
     */
    private boolean hasRole(Authentication authentication, String roleName) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(authority -> authority.equals(roleName));
    }

    /**
     * Helper method to get the current authenticated user ID for audit logging.
     * 
     * @return current user ID or "SYSTEM" if not authenticated
     */
    private String getCurrentUserId() {
        try {
            org.springframework.security.core.context.SecurityContext context = 
                org.springframework.security.core.context.SecurityContextHolder.getContext();
            Authentication authentication = context.getAuthentication();
            
            if (authentication != null && authentication.isAuthenticated()) {
                return authentication.getName();
            }
        } catch (Exception e) {
            log.debug("Unable to retrieve current user ID", e);
        }
        
        return "SYSTEM";
    }
}
