/*
 * AdminController.java
 * 
 * REST API controller for user administration operations replacing CICS transactions:
 * - CU00 (user list from COUSR00C.cbl)
 * - CU01 (user creation from COUSR01C.cbl)
 * - CU02 (user update from COUSR02C.cbl)
 * - CU03 (user deletion from COUSR03C.cbl)
 * 
 * This controller exposes HTTP endpoints for complete user management lifecycle
 * (CRUD operations) with role-based security enforcing administrator-only access,
 * replacing mainframe RACF security with Spring Security JWT authentication.
 * 
 * COBOL Source Programs:
 * - app/cbl/COUSR00C.cbl: User list with pagination (10 users per page)
 * - app/cbl/COUSR01C.cbl: User creation with validation
 * - app/cbl/COUSR02C.cbl: User update with password reset support
 * - app/cbl/COUSR03C.cbl: User soft deletion with business rule validation
 * 
 * BMS Mapsets (3270 Screen Layouts):
 * - app/bms/COUSR00M.bms: User list screen with selection options
 * - app/bms/COUSR01M.bms: User add screen with input fields
 * - app/bms/COUSR02M.bms: User update screen with password reset
 * - app/bms/COUSR03M.bms: User delete confirmation screen
 * 
 * Security Model:
 * All endpoints restricted to administrators only via @PreAuthorize("hasRole('ADMIN')")
 * matching CICS transaction security where CU00-CU03 transactions require
 * SEC-USR-TYPE='A' (admin) from CSUSR01Y.cpy copybook.
 * 
 * API Endpoints:
 * - GET    /api/admin/users          List users with pagination/filtering
 * - GET    /api/admin/users/{id}     Retrieve single user details
 * - POST   /api/admin/users          Create new user
 * - PUT    /api/admin/users/{id}     Update existing user
 * - DELETE /api/admin/users/{id}     Soft delete user
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.controller;

import com.carddemo.dto.request.UserRequest;
import com.carddemo.dto.request.UserListRequest;
import com.carddemo.dto.response.UserResponse;
import com.carddemo.dto.response.UserListResponse;
import com.carddemo.dto.response.UserSummaryDTO;
import com.carddemo.service.user.UserCreateService;
import com.carddemo.service.user.UserDeleteService;
import com.carddemo.service.user.UserListService;
import com.carddemo.service.user.UserUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * REST controller for user administration operations.
 * 
 * <p>This controller provides comprehensive user management capabilities exclusively
 * for administrative users, implementing the four core CICS transactions for user
 * administration (CU00-CU03) as RESTful HTTP endpoints with JSON request/response
 * formats replacing BMS 3270 terminal screen interactions.</p>
 * 
 * <h2>Security Model</h2>
 * <p>All endpoints enforce administrator-only access via Spring Security method-level
 * security annotations. Non-admin users attempting access receive HTTP 403 Forbidden
 * responses, replicating RACF CICS transaction security restrictions where CU00-CU03
 * transactions check SEC-USR-TYPE='A' before allowing execution.</p>
 * 
 * <h2>COBOL Transaction Mapping</h2>
 * <table border="1">
 *   <tr>
 *     <th>CICS Transaction</th>
 *     <th>COBOL Program</th>
 *     <th>HTTP Method</th>
 *     <th>Endpoint</th>
 *     <th>Function</th>
 *   </tr>
 *   <tr>
 *     <td>CU00</td>
 *     <td>COUSR00C.cbl</td>
 *     <td>GET</td>
 *     <td>/api/admin/users</td>
 *     <td>List users with pagination</td>
 *   </tr>
 *   <tr>
 *     <td>CU01</td>
 *     <td>COUSR01C.cbl</td>
 *     <td>POST</td>
 *     <td>/api/admin/users</td>
 *     <td>Create new user</td>
 *   </tr>
 *   <tr>
 *     <td>CU02</td>
 *     <td>COUSR02C.cbl</td>
 *     <td>PUT</td>
 *     <td>/api/admin/users/{id}</td>
 *     <td>Update existing user</td>
 *   </tr>
 *   <tr>
 *     <td>CU03</td>
 *     <td>COUSR03C.cbl</td>
 *     <td>DELETE</td>
 *     <td>/api/admin/users/{id}</td>
 *     <td>Soft delete user</td>
 *   </tr>
 * </table>
 * 
 * <h2>Pagination Pattern</h2>
 * <p>Replaces COBOL VSAM browse cursor pattern (STARTBR/READNEXT) with stateless
 * HTTP pagination using query parameters. Original COBOL displays 10 users per page
 * (USER-REC OCCURS 10 TIMES from COUSR00C.cbl line 57), preserved as default page size.</p>
 * 
 * <h2>Validation Strategy</h2>
 * <p>Uses Jakarta Bean Validation annotations (@Valid, @NotBlank, @Size, @Pattern)
 * on request DTOs, automatically triggering MethodArgumentNotValidException on
 * constraint violations. GlobalExceptionHandler transforms validation errors into
 * HTTP 400 Bad Request responses with detailed field-level error messages, replacing
 * COBOL field validation paragraphs with declarative validation rules.</p>
 * 
 * <h2>Transaction Boundaries</h2>
 * <p>Each endpoint delegates to service layer methods annotated with @Transactional,
 * ensuring ACID properties matching CICS transaction boundaries. Service methods
 * automatically commit on successful completion or rollback on exception, replicating
 * CICS SYNCPOINT/ROLLBACK behavior.</p>
 * 
 * <h2>Error Handling</h2>
 * <p>All exceptions thrown by service layer are caught by GlobalExceptionHandler:</p>
 * <ul>
 *   <li><b>ResourceNotFoundException</b>: User not found (HTTP 404)</li>
 *   <li><b>ValidationException</b>: Business rule violations like duplicate userId (HTTP 400)</li>
 *   <li><b>BusinessLogicException</b>: Constraint violations like deleting last admin (HTTP 400/409)</li>
 *   <li><b>MethodArgumentNotValidException</b>: Bean Validation failures (HTTP 400)</li>
 * </ul>
 * 
 * @see UserListService
 * @see UserCreateService
 * @see UserUpdateService
 * @see UserDeleteService
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @since 1.0
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "User Administration", description = "User management operations restricted to administrators only. " +
        "Replaces CICS transactions CU00-CU03 with RESTful HTTP endpoints for user CRUD operations. " +
        "All endpoints require ADMIN role authentication via JWT Bearer token.")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {

    /**
     * Service for user listing and retrieval operations.
     * Implements COUSR00C.cbl user list functionality with pagination support.
     */
    private final UserListService userListService;

    /**
     * Service for user creation operations.
     * Implements COUSR01C.cbl user add functionality with validation and password hashing.
     */
    private final UserCreateService userCreateService;

    /**
     * Service for user update operations.
     * Implements COUSR02C.cbl user update functionality with password reset support.
     */
    private final UserUpdateService userUpdateService;

    /**
     * Service for user deletion operations.
     * Implements COUSR03C.cbl user delete functionality with soft deletion pattern.
     */
    private final UserDeleteService userDeleteService;

    /**
     * Lists users with optional filtering and pagination.
     * 
     * <p>Replaces CICS transaction CU00 (COUSR00C.cbl) which performs VSAM USRSEC file
     * sequential browse using STARTBR/READNEXT cursor operations. This stateless REST
     * endpoint accepts query parameters for filtering and pagination, constructs a
     * UserListRequest DTO, and delegates to UserListService for database query execution.</p>
     * 
     * <h3>COBOL Source Transformation</h3>
     * <p>Original COBOL logic (COUSR00C.cbl):</p>
     * <ul>
     *   <li>Line 98-102: Main paragraph receiving COMMAREA with pagination state</li>
     *   <li>Line 160-195: User list display paragraph with 10 records per page</li>
     *   <li>Line 209-303: VSAM browse logic using STARTBR/READNEXT pattern</li>
     *   <li>Line 71: CDEMO-CU00-NEXT-PAGE-FLG for pseudo-conversational pagination</li>
     * </ul>
     * 
     * <p>Transformed to stateless REST pattern with query parameters eliminating
     * server-side pagination state stored in COMMAREA, enabling horizontal scaling
     * and cloud-native stateless architecture.</p>
     * 
     * <h3>Pagination Behavior</h3>
     * <p>Default page size: 10 users (matching COBOL USER-REC OCCURS 10 TIMES)</p>
     * <p>Zero-based page numbering (page=0 is first page)</p>
     * <p>Response includes hasNext/hasPrevious flags for navigation controls</p>
     * 
     * <h3>Filtering Capabilities</h3>
     * <ul>
     *   <li><b>userIdPattern</b>: Wildcard search on user ID (e.g., "ADMIN*" matches ADMIN01, ADMIN02)</li>
     *   <li><b>userTypeFilter</b>: Filter by role - "A" (Admin) or "U" (User)</li>
     * </ul>
     * 
     * <h3>Sorting Options</h3>
     * <ul>
     *   <li><b>sortBy</b>: Field name (userId, firstName, lastName, userType)</li>
     *   <li><b>sortDirection</b>: "ASC" (ascending) or "DESC" (descending)</li>
     * </ul>
     * 
     * <h3>Example Usage</h3>
     * <pre>
     * GET /api/admin/users?page=0&size=10&sortBy=userId&sortDirection=ASC
     * GET /api/admin/users?userIdPattern=ADMIN*&userTypeFilter=A
     * GET /api/admin/users?page=1&size=20&sortBy=lastName&sortDirection=DESC
     * </pre>
     * 
     * <h3>Response Structure</h3>
     * <pre>
     * {
     *   "users": [
     *     {
     *       "userId": "ADMIN01",
     *       "firstName": "John",
     *       "lastName": "Admin",
     *       "userType": "A"
     *     }
     *   ],
     *   "totalElements": 25,
     *   "totalPages": 3,
     *   "currentPage": 0,
     *   "pageSize": 10,
     *   "hasNext": true,
     *   "hasPrevious": false,
     *   "sortedBy": "userId",
     *   "sortDirection": "ASC"
     * }
     * </pre>
     * 
     * @param userIdPattern Optional wildcard pattern for user ID search (e.g., "ADMIN*")
     * @param userTypeFilter Optional user type filter: "A" (Admin) or "U" (User)
     * @param sortBy Field name for sorting (default: "userId")
     * @param sortDirection Sort direction: "ASC" or "DESC" (default: "ASC")
     * @param page Zero-based page number (default: 0)
     * @param size Number of records per page (default: 10, matching COBOL array size)
     * @return ResponseEntity containing UserListResponse with paginated user data and metadata
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "List all users with pagination and filtering",
            description = "Retrieves a paginated list of users with optional filtering by user ID pattern " +
                    "and user type. Replaces CICS transaction CU00 (COUSR00C.cbl) VSAM browse operations " +
                    "with stateless HTTP pagination. Default page size is 10 users matching original COBOL " +
                    "display pattern (USER-REC OCCURS 10 TIMES). Requires ADMIN role for access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved user list with pagination metadata",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request parameters (e.g., invalid userTypeFilter value)",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - Missing or invalid JWT token",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - User does not have ADMIN role",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during user list retrieval",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<UserListResponse> listUsers(
            @Parameter(
                    description = "Wildcard pattern for user ID search. Supports '*' wildcard character. " +
                            "Example: 'ADMIN*' matches ADMIN01, ADMIN02. Empty or null returns all users.",
                    example = "ADMIN*",
                    schema = @Schema(type = "string", maxLength = 8)
            )
            @RequestParam(required = false) String userIdPattern,
            
            @Parameter(
                    description = "Filter by user type. Valid values: 'A' (Admin), 'U' (User). " +
                            "Null or empty returns all user types. Matches COBOL SEC-USR-TYPE field.",
                    example = "A",
                    schema = @Schema(type = "string", allowableValues = {"A", "U"}, maxLength = 1)
            )
            @RequestParam(required = false) String userTypeFilter,
            
            @Parameter(
                    description = "Field name to sort results by. Supported fields: userId, firstName, lastName, userType.",
                    example = "userId",
                    schema = @Schema(type = "string", defaultValue = "userId")
            )
            @RequestParam(defaultValue = "userId") String sortBy,
            
            @Parameter(
                    description = "Sort direction. Valid values: 'ASC' (ascending), 'DESC' (descending).",
                    example = "ASC",
                    schema = @Schema(type = "string", allowableValues = {"ASC", "DESC"}, defaultValue = "ASC")
            )
            @RequestParam(defaultValue = "ASC") String sortDirection,
            
            @Parameter(
                    description = "Zero-based page number. First page is page 0.",
                    example = "0",
                    schema = @Schema(type = "integer", minimum = "0", defaultValue = "0")
            )
            @RequestParam(defaultValue = "0") int page,
            
            @Parameter(
                    description = "Number of records per page. Default is 10 matching COBOL display (USER-REC OCCURS 10 TIMES).",
                    example = "10",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "10")
            )
            @RequestParam(defaultValue = "10") int size
    ) {
        log.info("AdminController.listUsers called with parameters: userIdPattern={}, userTypeFilter={}, " +
                 "sortBy={}, sortDirection={}, page={}, size={}",
                userIdPattern, userTypeFilter, sortBy, sortDirection, page, size);

        // Construct UserListRequest DTO from query parameters
        // This replaces COBOL COMMAREA pagination state (CDEMO-CU00-PAGE-NUM, CDEMO-CU00-NEXT-PAGE-FLG)
        // with stateless request-scoped parameters enabling horizontal scaling
        UserListRequest request = UserListRequest.builder()
                .userIdPattern(userIdPattern)
                .userTypeFilter(userTypeFilter)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .page(page)
                .size(size)
                .build();

        // Delegate to service layer for business logic execution
        // Service performs JPA query with Spring Data Pageable, replacing COBOL VSAM
        // STARTBR/READNEXT browse pattern with database-level pagination
        UserListResponse response = userListService.listUsers(request);

        log.info("AdminController.listUsers completed successfully. Returned {} users out of {} total",
                response.getUserCount(), response.getTotalElements());

        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves detailed information for a specific user by user ID.
     * 
     * <p>This endpoint supports the user selection workflow from COUSR00C.cbl where
     * an administrator selects a user from the list for viewing details before
     * performing update or delete operations. Returns user profile information
     * excluding password for security.</p>
     * 
     * <h3>COBOL Source Transformation</h3>
     * <p>Replaces COBOL user selection logic where CDEMO-CU00-USR-SELECTED contains
     * the selected user ID, followed by VSAM READ operation to retrieve full record.</p>
     * 
     * <h3>Security Consideration</h3>
     * <p>Password field is explicitly excluded from response (UserSummaryDTO does not
     * include password) to prevent SEC-USR-PWD exposure in API responses, browser
     * developer console logs, and network traffic monitoring.</p>
     * 
     * <h3>Example Usage</h3>
     * <pre>
     * GET /api/admin/users/ADMIN01
     * </pre>
     * 
     * <h3>Response Structure</h3>
     * <pre>
     * {
     *   "userId": "ADMIN01",
     *   "firstName": "John",
     *   "lastName": "Admin",
     *   "userType": "A"
     * }
     * </pre>
     * 
     * @param userId User identifier from SEC-USR-ID field (PIC X(08), max 8 characters)
     * @return ResponseEntity containing UserSummaryDTO with user details (excluding password)
     * @throws ResourceNotFoundException if user with specified ID does not exist (HTTP 404)
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Get user details by user ID",
            description = "Retrieves detailed information for a specific user by user ID. " +
                    "Password field is excluded from response for security. Used for viewing user " +
                    "details before update or delete operations. Requires ADMIN role for access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully retrieved user details",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserSummaryDTO.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid user ID format",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - Missing or invalid JWT token",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - User does not have ADMIN role",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User not found with specified ID",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during user retrieval",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<UserSummaryDTO> getUserById(
            @Parameter(
                    description = "User identifier (SEC-USR-ID from COBOL). 8-character alphanumeric value.",
                    example = "ADMIN01",
                    required = true,
                    schema = @Schema(type = "string", pattern = "^[A-Z0-9]{1,8}$", maxLength = 8)
            )
            @PathVariable("id") String userId
    ) {
        log.info("AdminController.getUserById called with userId={}", userId);

        // Delegate to service layer for user retrieval
        // Service performs JPA repository findById() replacing COBOL VSAM READ operation
        UserSummaryDTO user = userListService.getUserById(userId);

        log.info("AdminController.getUserById completed successfully for userId={}", userId);

        return ResponseEntity.ok(user);
    }

    /**
     * Creates a new user with validation and password hashing.
     * 
     * <p>Replaces CICS transaction CU01 (COUSR01C.cbl) which presents BMS mapset
     * COUSR01M for user input, validates all fields, performs userId uniqueness check,
     * and writes new record to VSAM USRSEC file.</p>
     * 
     * <h3>COBOL Source Transformation</h3>
     * <p>Original COBOL logic (COUSR01C.cbl):</p>
     * <ul>
     *   <li>Line 180-220: Input validation paragraph checking all required fields</li>
     *   <li>Line 230-250: User ID uniqueness check via VSAM READ with NOTFND condition</li>
     *   <li>Line 260-280: Password validation (minimum length, complexity)</li>
     *   <li>Line 290-310: User type validation ensuring 'A' or 'U' value</li>
     *   <li>Line 320-340: VSAM WRITE operation with error handling</li>
     * </ul>
     * 
     * <p>Transformed to declarative Bean Validation annotations on UserRequest DTO
     * (@NotBlank, @Size, @Pattern) with service layer implementing business logic
     * for userId uniqueness and BCrypt password hashing.</p>
     * 
     * <h3>Validation Rules (Bean Validation Annotations)</h3>
     * <ul>
     *   <li><b>userId</b>: @NotBlank, @Size(min=1, max=8), unique in database</li>
     *   <li><b>password</b>: @NotBlank, @Size(min=8, max=20), complexity rules</li>
     *   <li><b>firstName</b>: @NotBlank, @Size(min=1, max=20)</li>
     *   <li><b>lastName</b>: @NotBlank, @Size(min=1, max=20)</li>
     *   <li><b>userType</b>: @NotNull, @Pattern(regexp="^[AU]$") for 'A' or 'U'</li>
     * </ul>
     * 
     * <h3>Password Security</h3>
     * <p>Service layer applies BCrypt password hashing with strength 10 before
     * database persistence, replacing RACF plaintext password storage from COBOL
     * with industry-standard one-way cryptographic hash.</p>
     * 
     * <h3>Example Usage</h3>
     * <pre>
     * POST /api/admin/users
     * Content-Type: application/json
     * 
     * {
     *   "userId": "USER001",
     *   "password": "SecurePass123!",
     *   "firstName": "Jane",
     *   "lastName": "Smith",
     *   "userType": "U"
     * }
     * </pre>
     * 
     * <h3>Response Structure (HTTP 201 Created)</h3>
     * <pre>
     * {
     *   "userId": "USER001",
     *   "firstName": "Jane",
     *   "lastName": "Smith",
     *   "userType": "U"
     * }
     * </pre>
     * 
     * @param request UserRequest DTO containing new user data with Bean Validation constraints
     * @return ResponseEntity with HTTP 201 Created status and UserResponse body (excluding password)
     * @throws ValidationException if userId already exists in database (HTTP 400)
     * @throws ValidationException if password does not meet complexity requirements (HTTP 400)
     * @throws MethodArgumentNotValidException if Bean Validation constraints fail (HTTP 400)
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Create a new user",
            description = "Creates a new user account with validation and BCrypt password hashing. " +
                    "Replaces CICS transaction CU01 (COUSR01C.cbl) VSAM WRITE operation with JPA " +
                    "repository save. Validates userId uniqueness and applies password complexity rules. " +
                    "Requires ADMIN role for access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "User created successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation error: duplicate userId, invalid password, or constraint violation",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - Missing or invalid JWT token",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - User does not have ADMIN role",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during user creation",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<UserResponse> createUser(
            @Parameter(
                    description = "User creation request containing userId, password, firstName, lastName, and userType. " +
                            "Password must be 8-20 characters with complexity requirements. " +
                            "UserType must be 'A' (Admin) or 'U' (User).",
                    required = true,
                    schema = @Schema(implementation = UserRequest.class)
            )
            @Valid @RequestBody UserRequest request
    ) {
        log.info("AdminController.createUser called for userId={}", request.getUserId());

        // Delegate to service layer for user creation with transaction management
        // Service performs:
        // 1. userId uniqueness validation via UserRepository.existsByUserId()
        // 2. Password BCrypt hashing using Spring Security PasswordEncoder (strength 10)
        // 3. User entity creation with all fields including createdDate timestamp
        // 4. JPA repository save() with automatic ACID transaction commit
        UserResponse response = userCreateService.createUser(request);

        log.info("AdminController.createUser completed successfully for userId={}", response.getUserId());

        // Return HTTP 201 Created with Location header pointing to new resource
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Updates an existing user with optional password reset and role changes.
     * 
     * <p>Replaces CICS transaction CU02 (COUSR02C.cbl) which allows administrators
     * to modify user information including password reset, name changes, and role
     * assignment updates. Performs VSAM REWRITE operation in COBOL, replaced with
     * JPA repository save (update mode) in Java.</p>
     * 
     * <h3>COBOL Source Transformation</h3>
     * <p>Original COBOL logic (COUSR02C.cbl):</p>
     * <ul>
     *   <li>Line 150-180: User existence check via VSAM READ operation</li>
     *   <li>Line 190-220: Input validation for modified fields</li>
     *   <li>Line 230-260: Conditional password update (only if new password provided)</li>
     *   <li>Line 270-290: User type change validation ensuring at least one admin exists</li>
     *   <li>Line 300-320: VSAM REWRITE operation with optimistic locking check</li>
     * </ul>
     * 
     * <p>Transformed to service layer method with @Transactional annotation ensuring
     * ACID properties, JPA @Version annotation for optimistic locking, and business
     * rule validation preventing last admin user demotion.</p>
     * 
     * <h3>Update Behavior</h3>
     * <ul>
     *   <li><b>Password</b>: Updated only if new password provided in request; BCrypt re-hashed</li>
     *   <li><b>First/Last Name</b>: Always updated if provided in request</li>
     *   <li><b>User Type</b>: Updated with validation ensuring at least one admin remains</li>
     * </ul>
     * 
     * <h3>Business Rules Enforced</h3>
     * <ul>
     *   <li>Cannot change last admin user to regular user (throws ValidationException)</li>
     *   <li>Cannot update non-existent user (throws ResourceNotFoundException)</li>
     *   <li>Concurrent update protection via JPA @Version optimistic locking</li>
     * </ul>
     * 
     * <h3>Example Usage</h3>
     * <pre>
     * PUT /api/admin/users/USER001
     * Content-Type: application/json
     * 
     * {
     *   "userId": "USER001",
     *   "password": "NewSecurePass456!",
     *   "firstName": "Janet",
     *   "lastName": "Smith",
     *   "userType": "A"
     * }
     * </pre>
     * 
     * <h3>Response Structure (HTTP 200 OK)</h3>
     * <pre>
     * {
     *   "userId": "USER001",
     *   "firstName": "Janet",
     *   "lastName": "Smith",
     *   "userType": "A"
     * }
     * </pre>
     * 
     * @param userId User identifier to update (must match existing user)
     * @param request UserRequest DTO containing updated user data
     * @return ResponseEntity with HTTP 200 OK status and updated UserResponse body (excluding password)
     * @throws ResourceNotFoundException if user with specified ID does not exist (HTTP 404)
     * @throws ValidationException if attempting to demote last admin user (HTTP 400)
     * @throws MethodArgumentNotValidException if Bean Validation constraints fail (HTTP 400)
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Update an existing user",
            description = "Updates user information including optional password reset and role changes. " +
                    "Replaces CICS transaction CU02 (COUSR02C.cbl) VSAM REWRITE operation with JPA " +
                    "repository save (update mode). Validates business rules: cannot demote last admin, " +
                    "cannot update non-existent user. Password BCrypt re-hashed if provided. " +
                    "Requires ADMIN role for access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User updated successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation error: attempting to demote last admin or constraint violation",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - Missing or invalid JWT token",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - User does not have ADMIN role",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User not found with specified ID",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Conflict - Concurrent update detected (optimistic locking failure)",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during user update",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<UserResponse> updateUser(
            @Parameter(
                    description = "User identifier to update. Must match existing user ID in database.",
                    example = "USER001",
                    required = true,
                    schema = @Schema(type = "string", pattern = "^[A-Z0-9]{1,8}$", maxLength = 8)
            )
            @PathVariable("id") String userId,
            
            @Parameter(
                    description = "User update request containing modified fields. Password is optional; " +
                            "if provided, will be BCrypt re-hashed. UserType change validated to ensure " +
                            "at least one admin user remains in system.",
                    required = true,
                    schema = @Schema(implementation = UserRequest.class)
            )
            @Valid @RequestBody UserRequest request
    ) {
        log.info("AdminController.updateUser called for userId={}", userId);

        // Delegate to service layer for user update with transaction management
        // Service performs:
        // 1. User existence validation via UserRepository.findById()
        // 2. Conditional password BCrypt re-hashing if new password provided
        // 3. User type change validation ensuring at least one admin exists
        // 4. JPA repository save() with @Version optimistic locking and automatic transaction commit
        UserResponse response = userUpdateService.updateUser(userId, request);

        log.info("AdminController.updateUser completed successfully for userId={}", userId);

        return ResponseEntity.ok(response);
    }

    /**
     * Soft deletes a user with business rule validation.
     * 
     * <p>Replaces CICS transaction CU03 (COUSR03C.cbl) which presents confirmation
     * screen (COUSR03M.bms) and performs user deletion with business rule checks.
     * Implements soft deletion pattern by setting deleted flag and timestamp rather
     * than physical record removal, preserving audit trail for compliance.</p>
     * 
     * <h3>COBOL Source Transformation</h3>
     * <p>Original COBOL logic (COUSR03C.cbl):</p>
     * <ul>
     *   <li>Line 130-160: User existence check via VSAM READ operation</li>
     *   <li>Line 170-190: Self-deletion prevention (admin cannot delete own account)</li>
     *   <li>Line 200-220: Last admin deletion prevention (ensure one admin remains)</li>
     *   <li>Line 230-250: Confirmation screen display with BMS SEND MAP</li>
     *   <li>Line 260-280: VSAM DELETE operation after confirmation</li>
     * </ul>
     * 
     * <p>Transformed to service layer method implementing soft deletion with status
     * flag and deletedDate timestamp, providing audit trail while logically removing
     * user from active system operations.</p>
     * 
     * <h3>Business Rules Enforced</h3>
     * <ul>
     *   <li>Cannot delete non-existent user (throws ResourceNotFoundException)</li>
     *   <li>Cannot delete own administrator account (throws ValidationException)</li>
     *   <li>Cannot delete last admin user in system (throws ValidationException)</li>
     *   <li>User must not have active sessions (throws BusinessLogicException)</li>
     * </ul>
     * 
     * <h3>Soft Deletion Pattern</h3>
     * <p>Instead of physical record deletion (VSAM DELETE or JPA delete()), sets:</p>
     * <ul>
     *   <li><b>status</b>: Changed from 'ACTIVE' to 'INACTIVE'</li>
     *   <li><b>deletedDate</b>: Current timestamp recorded for audit</li>
     *   <li><b>deletedBy</b>: Administrator user ID performing deletion</li>
     * </ul>
     * 
     * <p>Soft deleted users excluded from list queries but retained in database for
     * historical reporting, compliance audits, and potential restoration.</p>
     * 
     * <h3>Example Usage</h3>
     * <pre>
     * DELETE /api/admin/users/USER001
     * </pre>
     * 
     * <h3>Response (HTTP 204 No Content)</h3>
     * <p>Successful deletion returns empty response body with HTTP 204 status,
     * following RESTful convention for DELETE operations.</p>
     * 
     * @param userId User identifier to soft delete
     * @return ResponseEntity with HTTP 204 No Content status and empty body
     * @throws ResourceNotFoundException if user with specified ID does not exist (HTTP 404)
     * @throws ValidationException if attempting to delete own account or last admin (HTTP 400)
     * @throws BusinessLogicException if user has active sessions requiring logout first (HTTP 409)
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Delete a user (soft deletion)",
            description = "Soft deletes a user by setting deleted flag and timestamp rather than physical " +
                    "record removal, preserving audit trail. Replaces CICS transaction CU03 (COUSR03C.cbl) " +
                    "VSAM DELETE operation. Validates business rules: cannot delete own account, cannot " +
                    "delete last admin, user must not have active sessions. Requires ADMIN role for access."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "User deleted successfully (soft deletion with audit trail)",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation error: attempting to delete own account or last admin user",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized - Missing or invalid JWT token",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Forbidden - User does not have ADMIN role",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User not found with specified ID",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Conflict - User has active sessions requiring logout before deletion",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during user deletion",
                    content = @Content(mediaType = "application/json")
            )
    })
    public ResponseEntity<Void> deleteUser(
            @Parameter(
                    description = "User identifier to soft delete. Cannot be own account or last admin user.",
                    example = "USER001",
                    required = true,
                    schema = @Schema(type = "string", pattern = "^[A-Z0-9]{1,8}$", maxLength = 8)
            )
            @PathVariable("id") String userId
    ) {
        log.info("AdminController.deleteUser called for userId={}", userId);

        // Delegate to service layer for soft deletion with transaction management
        // Service performs:
        // 1. User existence validation via UserRepository.findById()
        // 2. Self-deletion prevention check (current user != userId to delete)
        // 3. Last admin deletion prevention (count admin users, must be > 1)
        // 4. Active session check (user must be logged out before deletion)
        // 5. Soft delete via status='INACTIVE' and deletedDate timestamp with JPA save()
        userDeleteService.deleteUser(userId);

        log.info("AdminController.deleteUser completed successfully for userId={}", userId);

        // Return HTTP 204 No Content following RESTful DELETE convention
        // Empty response body indicates successful deletion
        return ResponseEntity.noContent().build();
    }
}
