/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User List Request DTO
 * 
 * <p>Data Transfer Object for user listing and search operations, serving as the inbound 
 * contract for GET /api/admin/users endpoint query parameters. This DTO replaces the 
 * COBOL STARTBR/READNEXT cursor navigation pattern from COUSR00C.cbl with Spring Data 
 * Pageable abstraction.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>This DTO transforms the following COBOL user listing patterns:</p>
 * <ul>
 *   <li><strong>Source Program:</strong> app/cbl/COUSR00C.cbl - User list transaction (CU00)</li>
 *   <li><strong>Data Structure:</strong> app/cpy/CSUSR01Y.cpy - SEC-USER-DATA copybook</li>
 *   <li><strong>VSAM Access:</strong> USRSEC file with STARTBR/READNEXT/READPREV operations</li>
 *   <li><strong>Screen Layout:</strong> WS-USER-DATA array with 10 USER-REC occurrences</li>
 * </ul>
 * 
 * <h2>COBOL Pagination Pattern Transformation</h2>
 * <p>The original COBOL implementation used VSAM cursor positioning with the following patterns:</p>
 * <pre>
 * COBOL Pattern                          | Java Spring Equivalent
 * ---------------------------------------|------------------------------------------
 * MOVE LOW-VALUES TO SEC-USR-ID         | userIdPattern = null (start from beginning)
 * MOVE 'ABC' TO SEC-USR-ID              | userIdPattern = "ABC" (wildcard search)
 * MOVE CDEMO-CU00-USRID-FIRST TO ...    | page parameter with Spring Data pagination
 * MOVE CDEMO-CU00-USRID-LAST TO ...     | Handled by Pageable offset calculation
 * WS-USER-DATA OCCURS 10 TIMES          | size = 10 (default page size)
 * PERFORM PROCESS-PAGE-FORWARD          | page = page + 1
 * PERFORM PROCESS-PAGE-BACKWARD         | page = page - 1
 * DFHPF7 (scroll backward)              | Previous page navigation in frontend
 * DFHPF8 (scroll forward)               | Next page navigation in frontend
 * </pre>
 * 
 * <h2>Field Mappings from COBOL</h2>
 * <ul>
 *   <li><strong>userIdPattern:</strong> Maps to SEC-USR-ID field (PIC X(08)) from CSUSR01Y.cpy.
 *       Supports wildcard search replacing COBOL STARTBR with partial key positioning using 
 *       LOW-VALUES and HIGH-VALUES for range scanning.</li>
 *   <li><strong>userTypeFilter:</strong> Maps to SEC-USR-TYPE field (PIC X(01)) from CSUSR01Y.cpy.
 *       COBOL uses 88-level conditions for type checking. Valid values:
 *       <ul>
 *         <li>'A' - Administrative user (88 SEC-USR-TYPE-ADMIN VALUE 'A')</li>
 *         <li>'U' - Regular user (88 SEC-USR-TYPE-USER VALUE 'U')</li>
 *         <li>null - All users (no filter applied)</li>
 *       </ul>
 *   </li>
 *   <li><strong>sortBy:</strong> Determines sort column. COBOL naturally sorted by SEC-USR-ID 
 *       due to VSAM KSDS primary key. Java allows additional sort options like lastLoginDate.</li>
 *   <li><strong>sortDirection:</strong> Sort order (ASC/DESC). COBOL used READNEXT (ascending) 
 *       or READPREV (descending) for navigation.</li>
 *   <li><strong>page:</strong> Zero-based page number replacing COBOL CDEMO-CU00-PAGE-NUM which 
 *       was 1-based (MOVE 0 TO CDEMO-CU00-PAGE-NUM, then incremented).</li>
 *   <li><strong>size:</strong> Page size matching COBOL WS-USER-DATA array structure with 10 
 *       USER-REC occurrences per screen display.</li>
 * </ul>
 * 
 * <h2>REST API Query Parameter Binding</h2>
 * <p>This DTO is populated from HTTP GET query parameters as follows:</p>
 * <pre>
 * GET /api/admin/users?userIdPattern=ABC&userTypeFilter=A&sortBy=userId&sortDirection=ASC&page=0&size=10
 * </pre>
 * 
 * <h2>Validation Rules</h2>
 * <ul>
 *   <li><strong>page:</strong> Must be >= 0 (zero-based pagination)</li>
 *   <li><strong>size:</strong> Must be between 1 and 100 (prevents excessive data retrieval)</li>
 *   <li><strong>userIdPattern:</strong> Optional, no validation (any partial user ID allowed)</li>
 *   <li><strong>userTypeFilter:</strong> Optional, validated in service layer ('A', 'U', or null)</li>
 *   <li><strong>sortBy:</strong> Optional, validated in service layer ("userId" or "lastLoginDate")</li>
 *   <li><strong>sortDirection:</strong> Optional, defaults to "ASC" if not specified</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * // Controller method signature
 * &#64;GetMapping("/api/admin/users")
 * public ResponseEntity&lt;Page&lt;UserResponse&gt;&gt; listUsers(
 *     &#64;Valid UserListRequest request) {
 *     return ResponseEntity.ok(userListService.listUsers(request));
 * }
 * 
 * // Service layer usage
 * public Page&lt;UserResponse&gt; listUsers(UserListRequest request) {
 *     Pageable pageable = PageRequest.of(
 *         request.getPage(),
 *         request.getSize(),
 *         Sort.by(Sort.Direction.fromString(request.getSortDirection()),
 *                 request.getSortBy())
 *     );
 *     
 *     Specification&lt;User&gt; spec = UserSpecifications.buildSpecification(request);
 *     Page&lt;User&gt; users = userRepository.findAll(spec, pageable);
 *     return users.map(this::mapToResponse);
 * }
 * </pre>
 * 
 * <h2>Design Patterns Applied</h2>
 * <ul>
 *   <li><strong>Data Transfer Object (DTO) Pattern:</strong> Encapsulates request parameters</li>
 *   <li><strong>Builder Pattern:</strong> Lombok @Builder provides fluent object construction</li>
 *   <li><strong>Bean Validation:</strong> Jakarta Validation annotations ensure data integrity</li>
 *   <li><strong>Specification Pattern:</strong> Used in service layer to build dynamic queries</li>
 * </ul>
 * 
 * <h2>Thread Safety</h2>
 * <p>This class is immutable when used with Lombok's default behavior. All fields can be 
 * safely accessed from multiple threads. However, if mutable collections are added in the 
 * future, proper synchronization or immutable collection wrappers should be used.</p>
 * 
 * @see com.carddemo.entity.User
 * @see com.carddemo.service.user.UserListService
 * @see com.carddemo.dto.response.UserResponse
 * @version 1.0
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserListRequest {

    /**
     * User ID pattern for wildcard search.
     * 
     * <p>Supports partial matching of user IDs. When specified, the query filters users 
     * whose user ID starts with this pattern (SQL LIKE 'pattern%').</p>
     * 
     * <p><strong>COBOL Mapping:</strong> SEC-USR-ID (PIC X(08)) from CSUSR01Y.cpy</p>
     * 
     * <p><strong>COBOL Implementation:</strong> The original COUSR00C.cbl program used 
     * VSAM STARTBR with partial key positioning:</p>
     * <pre>
     * IF USRIDINI OF COUSR0AI = SPACES OR LOW-VALUES
     *     MOVE LOW-VALUES TO SEC-USR-ID
     * ELSE
     *     MOVE USRIDINI OF COUSR0AI TO SEC-USR-ID
     * END-IF
     * </pre>
     * 
     * <p>In the Java implementation, this translates to a Spring Data JPA Specification 
     * with a LIKE clause for prefix matching, or no filter if null.</p>
     * 
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>null or empty - Returns all users (equivalent to COBOL LOW-VALUES)</li>
     *   <li>"ABC" - Returns users with IDs starting with "ABC" (ABC00001, ABC00002, etc.)</li>
     *   <li>"ADMIN" - Returns all admin users with IDs starting with "ADMIN"</li>
     * </ul>
     * 
     * <p><strong>Validation:</strong> No validation applied (any string allowed). Maximum 
     * length should match database column constraints (typically 8 characters).</p>
     */
    private String userIdPattern;

    /**
     * User type filter for filtering by administrative or regular user roles.
     * 
     * <p>Filters users based on their role type. This field maps directly to the COBOL 
     * SEC-USR-TYPE field which uses single-character codes.</p>
     * 
     * <p><strong>COBOL Mapping:</strong> SEC-USR-TYPE (PIC X(01)) from CSUSR01Y.cpy</p>
     * 
     * <p><strong>Valid Values:</strong></p>
     * <ul>
     *   <li>'A' - Administrative users only (88 SEC-USR-TYPE-ADMIN VALUE 'A')</li>
     *   <li>'U' - Regular users only (88 SEC-USR-TYPE-USER VALUE 'U')</li>
     *   <li>null - All users regardless of type (no filter applied)</li>
     * </ul>
     * 
     * <p><strong>COBOL 88-Level Condition Mapping:</strong></p>
     * <pre>
     * COBOL: 88 SEC-USR-TYPE-ADMIN VALUE 'A'  →  Java: userTypeFilter.equals("A")
     * COBOL: 88 SEC-USR-TYPE-USER VALUE 'U'   →  Java: userTypeFilter.equals("U")
     * </pre>
     * 
     * <p><strong>Service Layer Validation:</strong> The service layer validates this field 
     * to ensure only 'A', 'U', or null values are accepted, throwing a ValidationException 
     * for invalid values.</p>
     */
    private String userTypeFilter;

    /**
     * Sort column selection for ordering results.
     * 
     * <p>Specifies which field to use for sorting the user list. COBOL implementation 
     * naturally sorted by user ID due to VSAM KSDS primary key structure. Java provides 
     * flexibility to sort by additional fields.</p>
     * 
     * <p><strong>COBOL Behavior:</strong> VSAM KSDS files maintain records in primary 
     * key (SEC-USR-ID) order. READNEXT retrieves records in ascending key order, while 
     * READPREV retrieves in descending order.</p>
     * 
     * <p><strong>Valid Values:</strong></p>
     * <ul>
     *   <li>"userId" - Sort by user ID (default, matches COBOL VSAM key order)</li>
     *   <li>"lastLoginDate" - Sort by last login timestamp (new capability in Java)</li>
     * </ul>
     * 
     * <p><strong>Default Value:</strong> "userId" (preserves COBOL behavior)</p>
     * 
     * <p><strong>Service Layer Validation:</strong> The service layer validates this field 
     * to prevent SQL injection and ensure only allowed column names are used in ORDER BY 
     * clauses.</p>
     */
    @Builder.Default
    private String sortBy = "userId";

    /**
     * Sort direction for ordering results.
     * 
     * <p>Specifies ascending or descending sort order. Maps to COBOL READNEXT (ascending) 
     * and READPREV (descending) navigation patterns.</p>
     * 
     * <p><strong>COBOL Mapping:</strong></p>
     * <pre>
     * COBOL Pattern                     | Java Equivalent
     * ----------------------------------|------------------
     * PERFORM READNEXT-USER-SEC-FILE   | sortDirection = "ASC"
     * PERFORM READPREV-USER-SEC-FILE   | sortDirection = "DESC"
     * DFHPF8 (forward scroll)          | sortDirection = "ASC"
     * DFHPF7 (backward scroll)         | sortDirection = "ASC" with page decrement
     * </pre>
     * 
     * <p><strong>Valid Values:</strong></p>
     * <ul>
     *   <li>"ASC" - Ascending order (A to Z, 0 to 9, oldest to newest)</li>
     *   <li>"DESC" - Descending order (Z to A, 9 to 0, newest to oldest)</li>
     * </ul>
     * 
     * <p><strong>Default Value:</strong> "ASC" (matches COBOL READNEXT default behavior)</p>
     */
    @Builder.Default
    private String sortDirection = "ASC";

    /**
     * Zero-based page number for pagination.
     * 
     * <p>Specifies which page of results to retrieve. Page numbering starts at 0 (first 
     * page), following Spring Data pagination conventions.</p>
     * 
     * <p><strong>COBOL Mapping:</strong> CDEMO-CU00-PAGE-NUM (PIC 9(08)) from COUSR00C.cbl</p>
     * 
     * <p><strong>COBOL vs Java Page Numbering:</strong></p>
     * <pre>
     * COBOL (1-based)  | Java (0-based)  | Description
     * -----------------|-----------------|---------------------------
     * MOVE 0 TO PAGE   | page = 0        | Initial state (page 1)
     * PAGE = 1         | page = 0        | First page of results
     * PAGE = 2         | page = 1        | Second page of results
     * PAGE + 1         | page + 1        | Navigate forward (PF8)
     * PAGE - 1         | page - 1        | Navigate backward (PF7)
     * </pre>
     * 
     * <p><strong>COBOL Page Navigation Logic:</strong></p>
     * <pre>
     * * Initial: MOVE 0 TO CDEMO-CU00-PAGE-NUM
     * * Forward: COMPUTE CDEMO-CU00-PAGE-NUM = CDEMO-CU00-PAGE-NUM + 1
     * * Backward: SUBTRACT 1 FROM CDEMO-CU00-PAGE-NUM
     * * Boundary: IF CDEMO-CU00-PAGE-NUM > 1 THEN allow backward scroll
     * </pre>
     * 
     * <p><strong>Validation:</strong> Must be >= 0 (cannot request negative page numbers)</p>
     * 
     * <p><strong>Default Value:</strong> 0 (first page)</p>
     */
    @Min(value = 0, message = "Page number must be 0 or greater")
    @Builder.Default
    private Integer page = 0;

    /**
     * Page size specifying the number of records per page.
     * 
     * <p>Specifies how many user records to retrieve in a single page. Default value 
     * matches the COBOL screen layout which displays 10 users at a time.</p>
     * 
     * <p><strong>COBOL Mapping:</strong> WS-USER-DATA array structure from COUSR00C.cbl:</p>
     * <pre>
     * 01 WS-USER-DATA.
     *    02 USER-REC OCCURS 10 TIMES.
     *       05 USER-SEL      PIC X(01).
     *       05 FILLER        PIC X(02).
     *       05 USER-ID       PIC X(08).
     *       05 FILLER        PIC X(02).
     *       05 USER-NAME     PIC X(25).
     *       05 FILLER        PIC X(02).
     *       05 USER-TYPE     PIC X(08).
     * </pre>
     * 
     * <p>The COBOL program processes exactly 10 records per screen using:</p>
     * <pre>
     * PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
     *     PERFORM READNEXT-USER-SEC-FILE
     *     PERFORM POPULATE-USER-DATA
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Minimum: 1 record (at least one user must be retrieved)</li>
     *   <li>Maximum: 100 records (prevents excessive data retrieval and memory usage)</li>
     *   <li>Default: 10 records (matches COBOL screen capacity)</li>
     * </ul>
     * 
     * <p><strong>Performance Considerations:</strong> Larger page sizes reduce the number 
     * of database round trips but increase memory usage and response time. The maximum of 
     * 100 balances these concerns while preventing abuse.</p>
     * 
     * <p><strong>Default Value:</strong> 10 (matches COBOL WS-USER-DATA OCCURS 10 TIMES)</p>
     */
    @Min(value = 1, message = "Page size must be at least 1")
    @Max(value = 100, message = "Page size must not exceed 100")
    @Builder.Default
    private Integer size = 10;
}
