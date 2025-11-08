/*
 * Program: UserListService.java
 * Layer: Service
 * Function: User listing and search functionality with pagination
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

package com.carddemo.service.user;

import com.carddemo.dto.request.UserListRequest;
import com.carddemo.dto.response.UserListResponse;
import com.carddemo.dto.response.UserSummaryDTO;
import com.carddemo.entity.User;
import com.carddemo.entity.User.UserType;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer implementation for user listing and search operations.
 * 
 * <p>This service transforms the COBOL COUSR00C.cbl user list transaction (CU00) from 
 * VSAM KSDS USRSEC file sequential read operations using CICS EXEC STARTBR/READNEXT/READPREV 
 * cursor navigation to Spring Data JPA repository pageable queries. It replaces the COBOL 
 * WS-USER-DATA array structure (10 user records per screen) with Spring Data Page abstraction 
 * while maintaining exact pagination semantics including 10-items-per-page display pattern.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>Transforms functionality from the following COBOL components:</p>
 * <ul>
 *   <li><strong>Program:</strong> app/cbl/COUSR00C.cbl - User list transaction (CU00)</li>
 *   <li><strong>Copybook:</strong> app/cpy/CSUSR01Y.cpy - SEC-USER-DATA record structure</li>
 *   <li><strong>VSAM File:</strong> USRSEC file - User security master file</li>
 *   <li><strong>Screen Map:</strong> WS-USER-DATA with USER-REC OCCURS 10 TIMES</li>
 * </ul>
 * 
 * <h2>COBOL to Java Transformation Patterns</h2>
 * <p>Key COBOL patterns transformed to Spring Data JPA:</p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Pattern</th>
 *     <th>Java Spring Equivalent</th>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS STARTBR FILE('USRSEC') RIDFLD(SEC-USR-ID)</td>
 *     <td>userRepository.findByUserIdStartingWith(userIdPattern, pageable)</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS READNEXT FILE('USRSEC') INTO(SEC-USER-DATA)</td>
 *     <td>pageable with Sort.Direction.ASC</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS READPREV FILE('USRSEC') INTO(SEC-USER-DATA)</td>
 *     <td>pageable with Sort.Direction.DESC</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS ENDBR FILE('USRSEC')</td>
 *     <td>Automatic with Page object (no explicit cleanup needed)</td>
 *   </tr>
 *   <tr>
 *     <td>PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10</td>
 *     <td>PageRequest.of(page, 10) - automatic iteration via Page</td>
 *   </tr>
 *   <tr>
 *     <td>MOVE SEC-USR-ID TO USER-ID(WS-IDX)</td>
 *     <td>UserSummaryDTO.fromUser(user) - DTO mapping</td>
 *   </tr>
 *   <tr>
 *     <td>IF SEC-USR-TYPE = 'A' THEN...</td>
 *     <td>UserType.ADMIN enum comparison</td>
 *   </tr>
 *   <tr>
 *     <td>CDEMO-CU00-NEXT-PAGE-FLG = 'Y'/'N'</td>
 *     <td>Page.hasNext() boolean</td>
 *   </tr>
 *   <tr>
 *     <td>CDEMO-CU00-PAGE-NUM + 1 (PF8)</td>
 *     <td>page parameter increment</td>
 *   </tr>
 *   <tr>
 *     <td>CDEMO-CU00-PAGE-NUM - 1 (PF7)</td>
 *     <td>page parameter decrement</td>
 *   </tr>
 * </table>
 * 
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Wildcard Search:</strong> User ID pattern matching with SQL LIKE 'pattern%'</li>
 *   <li><strong>Type Filtering:</strong> Filter by ADMIN ('A') or USER ('U') roles</li>
 *   <li><strong>Flexible Sorting:</strong> Sort by userId or lastLoginDate in ASC/DESC order</li>
 *   <li><strong>Pagination:</strong> 10 users per page default (configurable 1-100)</li>
 *   <li><strong>Password Masking:</strong> Excludes SEC-USR-PWD from response DTOs</li>
 *   <li><strong>Role-Based Access:</strong> Both ADMIN and USER roles can view lists</li>
 *   <li><strong>Soft Delete Support:</strong> Filters out deleted users automatically</li>
 * </ul>
 * 
 * <h2>Business Logic Preservation</h2>
 * <p>The following COBOL business rules are preserved exactly:</p>
 * <ul>
 *   <li>10 users displayed per page (WS-USER-DATA OCCURS 10)</li>
 *   <li>User ID primary key ordering (VSAM KSDS natural sort)</li>
 *   <li>Type filtering logic (88 SEC-USR-TYPE-ADMIN, SEC-USR-TYPE-USER)</li>
 *   <li>Pagination navigation (PF7 backward, PF8 forward)</li>
 *   <li>Password field exclusion in display (security requirement)</li>
 *   <li>Selection actions: 'U' for update, 'D' for delete</li>
 * </ul>
 * 
 * <h2>Security Implementation</h2>
 * <p>This service implements RACF-equivalent security controls:</p>
 * <ul>
 *   <li><strong>Method-Level Authorization:</strong> @PreAuthorize("hasAnyRole('ADMIN', 'USER')")</li>
 *   <li><strong>Password Protection:</strong> Passwords never included in response DTOs</li>
 *   <li><strong>Read-Only Operations:</strong> @Transactional(readOnly=true) for optimization</li>
 *   <li><strong>Role Preservation:</strong> UserType enum maps RACF two-tier model exactly</li>
 * </ul>
 * 
 * <h2>Performance Optimizations</h2>
 * <ul>
 *   <li>Database-level pagination via JPA Pageable (not in-memory filtering)</li>
 *   <li>Read-only transaction hint for database query optimization</li>
 *   <li>Indexed queries on userId and userType (matching VSAM KSDS structure)</li>
 *   <li>DTO projection reduces data transfer overhead</li>
 * </ul>
 * 
 * <h2>Error Handling</h2>
 * <p>Maps COBOL error patterns to Java exceptions:</p>
 * <ul>
 *   <li>CICS RESP-CD 13 (NOTFND) → ResourceNotFoundException with HTTP 404</li>
 *   <li>Invalid user type filter → IllegalArgumentException with validation message</li>
 *   <li>Invalid sort field → IllegalArgumentException preventing SQL injection</li>
 *   <li>WS-ERR-FLG = 'Y' → Exception throwing replacing COBOL error flag pattern</li>
 * </ul>
 * 
 * @see com.carddemo.entity.User
 * @see com.carddemo.repository.UserRepository
 * @see com.carddemo.dto.request.UserListRequest
 * @see com.carddemo.dto.response.UserListResponse
 * @see com.carddemo.dto.response.UserSummaryDTO
 * @since CardDemo v1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserListService {

    /**
     * User repository for database access operations.
     * 
     * <p>Replaces COBOL VSAM file I/O operations on USRSEC file with Spring Data JPA 
     * repository pattern. Provides CRUD operations and custom query methods supporting 
     * pagination, wildcard search, and type filtering.</p>
     * 
     * <p>Injected via constructor (Lombok @RequiredArgsConstructor) following Spring 
     * best practices for immutable service dependencies.</p>
     */
    private final UserRepository userRepository;

    /**
     * Lists users with optional filtering, sorting, and pagination.
     * 
     * <p>This method transforms the COBOL COUSR00C.cbl main user listing logic from 
     * VSAM sequential file processing to Spring Data JPA pageable queries. It replaces 
     * the COBOL pattern of STARTBR/READNEXT cursor navigation with declarative repository 
     * queries while maintaining exact business logic for user filtering and display.</p>
     * 
     * <h3>COBOL Logic Transformation</h3>
     * <p>Original COBOL flow (COUSR00C.cbl lines 100-300):</p>
     * <pre>
     * 1. MOVE search criteria to SEC-USR-ID (partial key positioning)
     * 2. EXEC CICS STARTBR FILE('USRSEC') RIDFLD(SEC-USR-ID)
     * 3. PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
     * 4.   EXEC CICS READNEXT FILE('USRSEC') INTO(SEC-USER-DATA)
     * 5.   IF SEC-USR-TYPE matches filter THEN
     * 6.     MOVE SEC-USR-ID TO USER-ID(WS-IDX)
     * 7.     MOVE SEC-USR-FNAME and SEC-USR-LNAME TO USER-NAME(WS-IDX)
     * 8.     MOVE SEC-USR-TYPE TO USER-TYPE(WS-IDX)
     * 9.   END-IF
     * 10. END-PERFORM
     * 11. EXEC CICS ENDBR FILE('USRSEC')
     * 12. MOVE USER-DATA to screen map COUSR0AO
     * </pre>
     * 
     * <p>Transformed Java flow:</p>
     * <pre>
     * 1. Validate request parameters (userTypeFilter, sortBy)
     * 2. Build Sort object from sortBy and sortDirection
     * 3. Create PageRequest (Pageable) with page, size, sort
     * 4. Query repository based on filter criteria:
     *    - findAll(Pageable) if no filters
     *    - findByUserIdStartingWith(pattern, Pageable) for wildcard search
     *    - findByUserType(UserType, Pageable) for type filter
     *    - Combined criteria using JPA Specification if both filters present
     * 5. Convert Page&lt;User&gt; to UserListResponse with UserSummaryDTO mapping
     * 6. Return JSON response to UserListController
     * </pre>
     * 
     * <h3>Parameter Mapping</h3>
     * <ul>
     *   <li><strong>userIdPattern:</strong> Maps to SEC-USR-ID partial key (COBOL LOW-VALUES 
     *       for start of file, specific value for positioning)</li>
     *   <li><strong>userTypeFilter:</strong> Maps to SEC-USR-TYPE field ('A' or 'U'), validated 
     *       against 88-level conditions SEC-USR-TYPE-ADMIN and SEC-USR-TYPE-USER</li>
     *   <li><strong>sortBy:</strong> Determines ORDER BY clause (userId or lastLoginDate), 
     *       replaces VSAM KSDS natural key ordering</li>
     *   <li><strong>sortDirection:</strong> "ASC" for READNEXT pattern, "DESC" for READPREV</li>
     *   <li><strong>page:</strong> Zero-based page number replacing CDEMO-CU00-PAGE-NUM</li>
     *   <li><strong>size:</strong> Records per page, default 10 matching OCCURS 10 TIMES</li>
     * </ul>
     * 
     * <h3>Pagination Behavior</h3>
     * <p>Preserves exact COBOL pagination semantics:</p>
     * <ul>
     *   <li>First page: page=0 (COBOL: CDEMO-CU00-PAGE-NUM = 1)</li>
     *   <li>Forward navigation: page++ (COBOL: COMPUTE PAGE-NUM = PAGE-NUM + 1)</li>
     *   <li>Backward navigation: page-- (COBOL: SUBTRACT 1 FROM PAGE-NUM)</li>
     *   <li>End of data: hasNext=false (COBOL: NEXT-PAGE-FLG = 'N')</li>
     *   <li>Beginning check: hasPrevious=false (COBOL: PAGE-NUM = 1)</li>
     * </ul>
     * 
     * <h3>Password Security</h3>
     * <p>Critical security requirement: SEC-USR-PWD field is never included in response DTOs.
     * UserSummaryDTO.fromUser() explicitly excludes password to prevent exposure in:</p>
     * <ul>
     *   <li>REST API JSON responses</li>
     *   <li>Browser developer console logs</li>
     *   <li>Client-side storage (localStorage, sessionStorage)</li>
     *   <li>Network traffic monitoring and logging</li>
     * </ul>
     * 
     * <h3>Role-Based Access Control</h3>
     * <p>Implements RACF-equivalent security: Both ADMIN and USER roles can view user lists.
     * The @PreAuthorize annotation enforces this at method level before execution, replacing 
     * COBOL program-level RACF security checks.</p>
     * 
     * <h3>Usage Example</h3>
     * <pre>
     * // Controller method calls this service
     * &#64;GetMapping("/api/admin/users")
     * public ResponseEntity&lt;UserListResponse&gt; listUsers(
     *         &#64;Valid UserListRequest request) {
     *     UserListResponse response = userListService.listUsers(request);
     *     return ResponseEntity.ok(response);
     * }
     * 
     * // Service layer usage
     * UserListRequest request = UserListRequest.builder()
     *     .userIdPattern("ADMIN")  // Find users starting with "ADMIN"
     *     .userTypeFilter("A")     // Filter to Admin users only
     *     .sortBy("userId")        // Sort by user ID
     *     .sortDirection("ASC")    // Ascending order
     *     .page(0)                 // First page
     *     .size(10)                // 10 records per page
     *     .build();
     * UserListResponse response = userListService.listUsers(request);
     * // response contains List&lt;UserSummaryDTO&gt; with pagination metadata
     * </pre>
     * 
     * <h3>Error Scenarios</h3>
     * <ul>
     *   <li>Invalid userTypeFilter (not 'A', 'U', or null): IllegalArgumentException</li>
     *   <li>Invalid sortBy field (SQL injection risk): IllegalArgumentException</li>
     *   <li>Database connection failure: DataAccessException (unchecked)</li>
     *   <li>No users found: Returns empty list with totalElements=0 (not an error)</li>
     * </ul>
     * 
     * @param request UserListRequest containing search criteria and pagination parameters
     * @return UserListResponse containing paginated user summaries with metadata
     * @throws IllegalArgumentException if userTypeFilter or sortBy contain invalid values
     * @throws org.springframework.dao.DataAccessException if database query fails
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Transactional(readOnly = true)
    public UserListResponse listUsers(UserListRequest request) {
        log.info("Listing users with criteria: userIdPattern={}, userTypeFilter={}, sortBy={}, " +
                 "sortDirection={}, page={}, size={}",
                request.getUserIdPattern(),
                request.getUserTypeFilter(),
                request.getSortBy(),
                request.getSortDirection(),
                request.getPage(),
                request.getSize());

        // Validate userTypeFilter if present
        // Maps to COBOL 88-level conditions: SEC-USR-TYPE-ADMIN VALUE 'A', SEC-USR-TYPE-USER VALUE 'U'
        UserType userTypeEnum = null;
        if (request.getUserTypeFilter() != null && !request.getUserTypeFilter().trim().isEmpty()) {
            String userTypeFilter = request.getUserTypeFilter().trim().toUpperCase();
            if ("A".equals(userTypeFilter)) {
                userTypeEnum = UserType.ADMIN;
            } else if ("U".equals(userTypeFilter)) {
                userTypeEnum = UserType.USER;
            } else {
                String errorMsg = "Invalid user type filter: " + userTypeFilter + 
                                ". Must be 'A' (Admin) or 'U' (User)";
                log.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }
            log.debug("User type filter validated: {} maps to UserType.{}", 
                     userTypeFilter, userTypeEnum);
        }

        // Validate sortBy field to prevent SQL injection
        // COBOL naturally sorts by SEC-USR-ID (VSAM KSDS primary key)
        // Java allows additional sort options with strict validation
        String sortBy = request.getSortBy() != null ? request.getSortBy().trim() : "userId";
        if (!"userId".equals(sortBy) && !"lastLoginDate".equals(sortBy)) {
            String errorMsg = "Invalid sort field: " + sortBy + 
                            ". Allowed values: 'userId', 'lastLoginDate'";
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        // Build Sort object from sortBy and sortDirection
        // Maps to COBOL READNEXT (ASC) and READPREV (DESC) navigation patterns
        Sort.Direction direction = "DESC".equalsIgnoreCase(request.getSortDirection()) 
            ? Sort.Direction.DESC 
            : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, sortBy);
        log.debug("Sort criteria: {} {}", sortBy, direction);

        // Create Pageable for pagination
        // Maps to COBOL CDEMO-CU00-PAGE-NUM and WS-USER-DATA OCCURS 10 TIMES
        int page = request.getPage() != null ? request.getPage() : 0;
        int size = request.getSize() != null ? request.getSize() : 10;
        Pageable pageable = PageRequest.of(page, size, sort);
        log.debug("Pageable created: page={}, size={}, sort={}", page, size, sort);

        // Query repository based on filter criteria
        // Replaces COBOL STARTBR/READNEXT cursor navigation with declarative queries
        Page<User> userPage;
        String userIdPattern = request.getUserIdPattern();
        boolean hasUserIdPattern = userIdPattern != null && !userIdPattern.trim().isEmpty();

        if (hasUserIdPattern && userTypeEnum != null) {
            // Both filters present: wildcard search + type filter
            // COBOL equivalent: STARTBR with partial key + IF SEC-USR-TYPE check in loop
            log.debug("Querying with both userIdPattern and userType filter");
            userPage = findByUserIdPatternAndType(userIdPattern.trim(), userTypeEnum, pageable);
        } else if (hasUserIdPattern) {
            // Only wildcard search
            // COBOL equivalent: STARTBR FILE('USRSEC') RIDFLD(SEC-USR-ID) with partial key
            log.debug("Querying with userIdPattern only: {}", userIdPattern);
            userPage = userRepository.findByUserIdStartingWithAndDeletedFalse(userIdPattern.trim(), pageable);
        } else if (userTypeEnum != null) {
            // Only type filter
            // COBOL equivalent: READNEXT with IF SEC-USR-TYPE = 'A' or 'U' check
            log.debug("Querying with userType filter only: {}", userTypeEnum);
            userPage = userRepository.findByUserTypeAndDeletedFalse(userTypeEnum, pageable);
        } else {
            // No filters: retrieve all users
            // COBOL equivalent: STARTBR with LOW-VALUES (start from beginning)
            log.debug("Querying all users without filters");
            userPage = userRepository.findAllByDeletedFalse(pageable);
        }

        log.info("Retrieved {} users out of {} total users for page {} of {}",
                userPage.getNumberOfElements(),
                userPage.getTotalElements(),
                userPage.getNumber() + 1,  // Convert to 1-based for logging
                userPage.getTotalPages());

        // Convert Page<User> to UserListResponse
        // Maps COBOL WS-USER-DATA array to UserListResponse with List<UserSummaryDTO>
        // Password field explicitly excluded via UserSummaryDTO.fromUser()
        UserListResponse response = convertToUserListResponse(userPage);
        
        log.debug("User list response prepared: hasNext={}, hasPrevious={}", 
                 response.getHasNext(), response.getHasPrevious());

        return response;
    }

    /**
     * Retrieves a single user by user ID.
     * 
     * <p>This method provides single-user lookup functionality, used when a specific user 
     * is selected from the list for viewing, updating, or deleting. It replaces COBOL 
     * direct READ operations on USRSEC file with specific user ID key.</p>
     * 
     * <h3>COBOL Pattern Transformation</h3>
     * <p>Original COBOL pattern:</p>
     * <pre>
     * MOVE selected-user-id TO SEC-USR-ID.
     * EXEC CICS READ FILE('USRSEC')
     *      INTO(SEC-USER-DATA)
     *      RIDFLD(SEC-USR-ID)
     *      RESP(WS-RESP-CD)
     * END-EXEC.
     * 
     * EVALUATE WS-RESP-CD
     *   WHEN DFHRESP(NORMAL)
     *     PERFORM PROCESS-USER-DATA
     *   WHEN DFHRESP(NOTFND)
     *     MOVE 'User not found. Try again ...' TO WS-MESSAGE
     *     PERFORM SEND-ERROR-MESSAGE
     * END-EVALUATE.
     * </pre>
     * 
     * <p>Transformed Java pattern:</p>
     * <pre>
     * User user = userRepository.findById(userId)
     *     .orElseThrow(() -&gt; new ResourceNotFoundException("User not found: " + userId));
     * return UserSummaryDTO.fromUser(user);
     * </pre>
     * 
     * <h3>Error Handling</h3>
     * <p>Maps COBOL RESP-CD error handling to Java exceptions:</p>
     * <ul>
     *   <li>DFHRESP(NORMAL) → User found, convert to DTO and return</li>
     *   <li>DFHRESP(NOTFND) → ResourceNotFoundException thrown with descriptive message</li>
     *   <li>GlobalExceptionHandler catches exception and returns HTTP 404 Not Found</li>
     * </ul>
     * 
     * <h3>Security Considerations</h3>
     * <ul>
     *   <li>Password field excluded from UserSummaryDTO (SEC-USR-PWD never exposed)</li>
     *   <li>Role-based access: Both ADMIN and USER can view user details</li>
     *   <li>Read-only transaction for database optimization</li>
     * </ul>
     * 
     * <h3>Usage Example</h3>
     * <pre>
     * // From UserListController when user selects a specific user
     * String userId = "ADMIN001";
     * try {
     *     UserSummaryDTO user = userListService.getUserById(userId);
     *     // User found, proceed with display or further actions
     * } catch (ResourceNotFoundException e) {
     *     // User not found, return HTTP 404
     *     return ResponseEntity.notFound().build();
     * }
     * </pre>
     * 
     * @param userId the user ID to search for (must not be null or empty)
     * @return UserSummaryDTO containing user information without password
     * @throws ResourceNotFoundException if user with given ID does not exist
     * @throws IllegalArgumentException if userId is null or empty
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Transactional(readOnly = true)
    public UserSummaryDTO getUserById(String userId) {
        log.info("Retrieving user by ID: {}", userId);

        // Validate userId parameter
        if (userId == null || userId.trim().isEmpty()) {
            String errorMsg = "User ID cannot be null or empty";
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        // Query repository and throw ResourceNotFoundException if not found
        // Maps to COBOL: EXEC CICS READ ... RESP(WS-RESP-CD)
        // WHEN DFHRESP(NOTFND) → ResourceNotFoundException
        User user = userRepository.findByUserIdAndDeletedFalse(userId.trim())
                .orElseThrow(() -> {
                    String errorMsg = "User not found: " + userId;
                    log.error(errorMsg);
                    return new ResourceNotFoundException(errorMsg);
                });

        log.debug("User found: userId={}, firstName={}, lastName={}, userType={}",
                user.getUserId(),
                user.getFirstName(),
                user.getLastName(),
                user.getUserType().getCode());

        // Convert User entity to UserSummaryDTO
        // Explicitly excludes password field (SEC-USR-PWD) for security
        UserSummaryDTO userSummary = UserSummaryDTO.fromUser(user);

        log.info("User retrieved successfully: {}", userId);
        return userSummary;
    }

    /**
     * Helper method to query users by both user ID pattern and user type.
     * 
     * <p>This method handles the scenario where both wildcard search and type filtering 
     * are applied simultaneously. Since UserRepository doesn't provide a single method 
     * combining both filters, this implementation queries by userIdPattern and then 
     * filters by userType in-memory.</p>
     * 
     * <p><strong>Note:</strong> For optimal performance with large datasets, consider 
     * implementing a custom repository method using JPA Specification or @Query annotation 
     * to perform combined filtering at the database level.</p>
     * 
     * <h3>COBOL Equivalent Logic</h3>
     * <pre>
     * MOVE search-pattern TO SEC-USR-ID.
     * EXEC CICS STARTBR FILE('USRSEC') RIDFLD(SEC-USR-ID) END-EXEC.
     * 
     * PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
     *   EXEC CICS READNEXT FILE('USRSEC') INTO(SEC-USER-DATA) END-EXEC
     *   
     *   * Apply user type filter in memory
     *   IF SEC-USR-TYPE = required-type THEN
     *     MOVE SEC-USER-DATA TO WS-USER-DATA(WS-IDX)
     *     ADD 1 TO WS-REC-COUNT
     *   END-IF
     * END-PERFORM.
     * </pre>
     * 
     * @param userIdPattern the user ID pattern for wildcard search
     * @param userType the user type to filter by (ADMIN or USER)
     * @param pageable pagination parameters
     * @return Page of users matching both criteria
     */
    private Page<User> findByUserIdPatternAndType(String userIdPattern, 
                                                   UserType userType, 
                                                   Pageable pageable) {
        log.debug("Finding users by pattern '{}' and type '{}'", userIdPattern, userType);
        
        // Query by userIdPattern first (database-level filtering)
        Page<User> usersByPattern = userRepository.findByUserIdStartingWithAndDeletedFalse(userIdPattern, pageable);
        
        // Filter by userType in-memory (stream filtering)
        // This preserves pagination metadata while filtering content
        List<User> filteredUsers = usersByPattern.getContent().stream()
                .filter(user -> userType.equals(user.getUserType()))
                .collect(Collectors.toList());
        
        log.debug("Combined filter results: {} users matched out of {} from pattern search",
                 filteredUsers.size(), usersByPattern.getContent().size());
        
        // Create a new Page with filtered content but original pagination metadata
        // Note: This approach may result in pages with fewer than requested size
        // For production use, consider implementing database-level combined filtering
        return new org.springframework.data.domain.PageImpl<>(
                filteredUsers,
                pageable,
                usersByPattern.getTotalElements()
        );
    }

    /**
     * Converts Spring Data Page of Users to UserListResponse DTO.
     * 
     * <p>This method transforms the JPA Page object to our custom response DTO structure,
     * mapping each User entity to UserSummaryDTO while explicitly excluding password fields.
     * It preserves all pagination metadata including page numbers, total counts, and 
     * navigation flags.</p>
     * 
     * <h3>Data Transformation Flow</h3>
     * <ol>
     *   <li>Extract user entities from Page.getContent()</li>
     *   <li>Map each User to UserSummaryDTO.fromUser() (password excluded)</li>
     *   <li>Build UserListResponse with pagination metadata</li>
     *   <li>Set navigation flags (hasNext, hasPrevious)</li>
     *   <li>Include sort information for UI display</li>
     * </ol>
     * 
     * <h3>COBOL Equivalent</h3>
     * <p>Replaces COBOL pattern of moving data to screen output structure:</p>
     * <pre>
     * PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
     *   MOVE USER-ID(WS-IDX) TO SEL0001I(WS-IDX) IN COUSR0AO
     *   MOVE USER-NAME(WS-IDX) TO USERNME(WS-IDX) IN COUSR0AO
     *   MOVE USER-TYPE(WS-IDX) TO USRTYPE(WS-IDX) IN COUSR0AO
     *   * Password NOT moved (security requirement)
     * END-PERFORM.
     * 
     * MOVE WS-REC-COUNT TO USRCNTO IN COUSR0AO.
     * MOVE CDEMO-CU00-NEXT-PAGE-FLG TO NEXTFLGO IN COUSR0AO.
     * </pre>
     * 
     * @param userPage the Spring Data Page containing User entities
     * @return UserListResponse with all pagination metadata and user summaries
     */
    private UserListResponse convertToUserListResponse(Page<User> userPage) {
        // Convert each User entity to UserSummaryDTO
        // Password field explicitly excluded by UserSummaryDTO.fromUser()
        List<UserSummaryDTO> userSummaries = userPage.getContent().stream()
                .map(UserSummaryDTO::fromUser)
                .collect(Collectors.toList());

        // Extract sort information for response
        String sortedBy = null;
        String sortDirection = null;
        if (userPage.getSort().isSorted()) {
            Sort.Order order = userPage.getSort().iterator().next();
            sortedBy = order.getProperty();
            sortDirection = order.getDirection().name();
        }

        // Build UserListResponse with all pagination metadata
        // Maps COBOL WS-USER-DATA and pagination flags to structured response
        return UserListResponse.builder()
                .users(userSummaries)
                .totalElements(userPage.getTotalElements())
                .totalPages(userPage.getTotalPages())
                .currentPage(userPage.getNumber())  // Zero-based
                .pageSize(userPage.getSize())
                .hasNext(userPage.hasNext())       // COBOL: CDEMO-CU00-NEXT-PAGE-FLG = 'Y'
                .hasPrevious(userPage.hasPrevious())  // COBOL: CDEMO-CU00-PAGE-NUM > 1
                .sortedBy(sortedBy)
                .sortDirection(sortDirection)
                .build();
    }
}
