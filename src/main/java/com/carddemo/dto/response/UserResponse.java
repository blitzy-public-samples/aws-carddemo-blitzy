/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User Response Data Transfer Object.
 * 
 * <p>This DTO represents user profile information returned from the CardDemo user management
 * API endpoints, specifically excluding the password field for security purposes. It transforms
 * the VSAM user security file record structure (CSUSR01Y.cpy SEC-USER-DATA) into a secure
 * JSON response format suitable for modern REST APIs.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>This class maps to the following COBOL copybook structure from CSUSR01Y.cpy:</p>
 * <pre>
 *    01 SEC-USER-DATA.
 *       05 SEC-USR-ID                 PIC X(08).   → userId
 *       05 SEC-USR-FNAME              PIC X(20).   → firstName
 *       05 SEC-USR-LNAME              PIC X(20).   → lastName
 *       05 SEC-USR-PWD                PIC X(08).   → EXCLUDED (security)
 *       05 SEC-USR-TYPE               PIC X(01).   → userType
 * </pre>
 * 
 * <h2>Security Design</h2>
 * <p>The password field (SEC-USR-PWD) from the COBOL copybook is intentionally excluded from
 * this response DTO to prevent accidental password exposure in API responses. This follows
 * the security best practice of separating authentication credentials from user display data.
 * The password field is only used during authentication and user creation/update operations,
 * but never returned to clients.</p>
 * 
 * <h2>User Type Values</h2>
 * <p>The userType field contains single-character codes representing role-based access levels:</p>
 * <ul>
 *   <li><b>"A"</b> - Administrator user with full system access including user management,
 *                   account/card updates, and transaction posting</li>
 *   <li><b>"U"</b> - Regular user with read-only access to accounts, cards, and transactions</li>
 * </ul>
 * 
 * <p>These values map to COBOL 88-level conditions and are enforced by Spring Security
 * @PreAuthorize annotations using hasRole('ADMIN') or hasRole('USER') expressions.</p>
 * 
 * <h2>BMS Screen Mapping</h2>
 * <p>This DTO corresponds to the following BMS mapset field layouts:</p>
 * <ul>
 *   <li><b>COUSR00M.bms</b> - User list screen (CU00 transaction) displaying userId, full name,
 *                            and userType in tabular format</li>
 *   <li><b>COUSR01M.bms</b> - User add screen (CU01 transaction) showing input fields for
 *                            all user attributes including password (not exposed in response)</li>
 *   <li><b>COUSR02M.bms</b> - User update screen (CU02 transaction) displaying editable user
 *                            profile fields excluding password for security</li>
 * </ul>
 * 
 * <h2>COBOL Program Transformation</h2>
 * <p>This response DTO replaces the user data display logic from the following COBOL programs:</p>
 * <ul>
 *   <li><b>COUSR00C.cbl</b> (CU00) - List users transaction with VSAM USRSEC file browse
 *                                   using STARTBR/READNEXT pattern</li>
 *   <li><b>COUSR01C.cbl</b> (CU01) - Create user transaction with VSAM WRITE operation</li>
 *   <li><b>COUSR02C.cbl</b> (CU02) - Update user transaction with VSAM REWRITE operation</li>
 *   <li><b>COUSR03C.cbl</b> (CU03) - Delete user transaction with VSAM DELETE operation</li>
 * </ul>
 * 
 * <p>These CICS pseudo-conversational programs with COMMAREA state management are transformed
 * to stateless REST API endpoints with JSON request/response patterns using Spring Boot and
 * PostgreSQL with Spring Data JPA repositories.</p>
 * 
 * <h2>API Usage Patterns</h2>
 * <p>This DTO is used as the response type for the following REST API endpoints:</p>
 * <ul>
 *   <li><b>GET /api/admin/users</b> - Returns List&lt;UserResponse&gt; containing all users
 *                                    (UserListService.getAllUsers())</li>
 *   <li><b>GET /api/admin/users/{id}</b> - Returns single UserResponse for specified user
 *                                         (UserListService.getUserById())</li>
 *   <li><b>POST /api/admin/users</b> - Returns UserResponse of newly created user without
 *                                     password (UserCreateService.createUser())</li>
 *   <li><b>PUT /api/admin/users/{id}</b> - Returns UserResponse of updated user profile
 *                                         (UserUpdateService.updateUser())</li>
 * </ul>
 * 
 * <h2>Authorization Requirements</h2>
 * <p>All endpoints using this response DTO require ADMIN role authorization enforced by
 * Spring Security @PreAuthorize("hasRole('ADMIN')") annotations. This preserves the mainframe
 * RACF security model where only administrators can manage user accounts.</p>
 * 
 * <h2>Client Integration</h2>
 * <p>This response format is consumed by the React frontend components:</p>
 * <ul>
 *   <li><b>UserListComponent.jsx</b> - Displays paginated user list in administrative interface</li>
 *   <li><b>UserAddComponent.jsx</b> - Shows confirmation after successful user creation</li>
 *   <li><b>UserUpdateComponent.jsx</b> - Displays updated user profile after save operation</li>
 * </ul>
 * 
 * <h2>JSON Serialization</h2>
 * <p>The @JsonPropertyOrder annotation ensures consistent field ordering in JSON output
 * matching the BMS screen layout sequence (userId, firstName, lastName, userType). The
 * @JsonInclude(NON_NULL) annotation excludes null fields from JSON serialization, reducing
 * payload size and improving API response clarity.</p>
 * 
 * @see com.carddemo.entity.User
 * @see com.carddemo.service.user.UserListService
 * @see com.carddemo.service.user.UserCreateService
 * @see com.carddemo.service.user.UserUpdateService
 * @see com.carddemo.controller.AdminController
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"userId", "firstName", "lastName", "userType"})
public class UserResponse {

    /**
     * Unique user identifier used as primary key and login credential.
     * 
     * <p>Maps to CSUSR01Y.cpy SEC-USR-ID (PIC X(08)) - 8-character alphanumeric user ID
     * originally stored in VSAM USRSEC file with KSDS primary key access pattern. In the
     * modernized system, this becomes the primary key in the PostgreSQL user table with
     * UNIQUE constraint and indexed access for authentication queries.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Length: 1-8 characters (matches COBOL PIC X(08))</li>
     *   <li>Character set: Uppercase alphanumeric (A-Z, 0-9) for mainframe compatibility</li>
     *   <li>Uniqueness: Must be unique across all user records</li>
     *   <li>Immutability: Cannot be changed after user creation (primary key)</li>
     * </ul>
     * 
     * <p><b>Example values:</b> "ADMIN001", "USER0001", "JOHNDOE", "MANAGER1"</p>
     */
    @JsonProperty("userId")
    private String userId;

    /**
     * User's first name for display in UI components and reports.
     * 
     * <p>Maps to CSUSR01Y.cpy SEC-USR-FNAME (PIC X(20)) - 20-character alphanumeric field
     * containing the user's given name. Combined with lastName to form full name display
     * in user lists, menu headers, and audit logs.</p>
     * 
     * <p><b>Display Format:</b> Typically displayed as "firstName lastName" in UI components
     * such as UserListComponent.jsx table rows, welcome messages, and transaction audit trails.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Length: 1-20 characters (matches COBOL PIC X(20))</li>
     *   <li>Character set: Alphabetic and spaces allowed</li>
     *   <li>Required: Must not be empty or null during user creation</li>
     * </ul>
     * 
     * <p><b>Example values:</b> "John", "Mary Ann", "Robert", "Li"</p>
     */
    @JsonProperty("firstName")
    private String firstName;

    /**
     * User's last name for full name display and sorting operations.
     * 
     * <p>Maps to CSUSR01Y.cpy SEC-USR-LNAME (PIC X(20)) - 20-character alphanumeric field
     * containing the user's surname. Used in combination with firstName for complete name
     * display and alphabetical sorting in user management screens.</p>
     * 
     * <p><b>Sorting Behavior:</b> User lists are typically sorted by lastName + firstName
     * to match COBOL program COUSR00C.cbl browse pattern using VSAM STARTBR with alternate
     * index on user name fields.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Length: 1-20 characters (matches COBOL PIC X(20))</li>
     *   <li>Character set: Alphabetic and spaces allowed</li>
     *   <li>Required: Must not be empty or null during user creation</li>
     * </ul>
     * 
     * <p><b>Example values:</b> "Smith", "O'Brien", "Van Der Berg", "Zhang"</p>
     */
    @JsonProperty("lastName")
    private String lastName;

    /**
     * User role type determining authorization level and system access privileges.
     * 
     * <p>Maps to CSUSR01Y.cpy SEC-USR-TYPE (PIC X(01)) - Single-character code representing
     * the user's role in the CardDemo system. This field directly controls authorization
     * decisions through Spring Security role-based access control.</p>
     * 
     * <p><b>Valid Values:</b></p>
     * <ul>
     *   <li><b>"A"</b> - Administrator role (ROLE_ADMIN in Spring Security)
     *     <ul>
     *       <li>Full user management: create, update, delete users</li>
     *       <li>Account management: update credit limits, close accounts</li>
     *       <li>Card management: issue new cards, update card status</li>
     *       <li>Transaction operations: post transactions, process payments</li>
     *       <li>Report generation: access all system reports</li>
     *     </ul>
     *   </li>
     *   <li><b>"U"</b> - Regular User role (ROLE_USER in Spring Security)
     *     <ul>
     *       <li>View accounts: read-only access to account details</li>
     *       <li>View cards: read-only access to card information</li>
     *       <li>View transactions: read-only access to transaction history</li>
     *       <li>Generate reports: view and export transaction reports</li>
     *       <li>No user management or data modification capabilities</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * <p><b>COBOL 88-Level Condition Transformation:</b></p>
     * <pre>
     *   COBOL: 88 CDEMO-USRTYP-ADMIN VALUE 'A'
     *          88 CDEMO-USRTYP-USER  VALUE 'U'
     *   
     *   Java:  @PreAuthorize("hasRole('ADMIN')")  // for 'A'
     *          @PreAuthorize("hasRole('USER')")   // for 'U'
     * </pre>
     * 
     * <p><b>Authorization Enforcement:</b> This field value is mapped to Spring Security
     * GrantedAuthority objects during authentication, enabling method-level security with
     * @PreAuthorize annotations on service layer methods. The JwtAuthenticationFilter extracts
     * the role from JWT token claims and populates the SecurityContext.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Length: Exactly 1 character (matches COBOL PIC X(01))</li>
     *   <li>Allowed values: "A" or "U" only (case-sensitive)</li>
     *   <li>Required: Must not be null during user creation</li>
     *   <li>Immutability: Role changes require explicit update operation with admin privileges</li>
     * </ul>
     */
    @JsonProperty("userType")
    private String userType;

    /**
     * Note: Password field (SEC-USR-PWD) is intentionally excluded from this response DTO.
     * 
     * <p>The COBOL copybook CSUSR01Y.cpy defines SEC-USR-PWD (PIC X(08)) as part of the
     * SEC-USER-DATA record structure. However, this field is deliberately omitted from the
     * UserResponse to prevent password exposure in API responses.</p>
     * 
     * <p><b>Security Rationale:</b></p>
     * <ul>
     *   <li>Passwords should never be returned in API responses, even in hashed form</li>
     *   <li>Read operations (GET endpoints) should not expose authentication credentials</li>
     *   <li>Separation of authentication data from profile display data follows security
     *       best practices and prevents accidental password leakage in logs, caches, or
     *       browser developer tools</li>
     *   <li>Client applications should never receive password values, preventing potential
     *       XSS or session hijacking attacks that could expose credentials</li>
     * </ul>
     * 
     * <p><b>Password Handling:</b> Passwords are only accepted during user creation (POST)
     * and password reset (PUT) operations through dedicated request DTOs (UserRequest).
     * The password is immediately hashed using BCrypt (Spring Security PasswordEncoder)
     * before storage and never returned to clients.</p>
     */
}
