/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
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

package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User Management Request Data Transfer Object.
 * <p>
 * This DTO represents the inbound contract for user creation and update operations,
 * replacing the CICS COMMAREA structures used by COBOL programs COUSR01C (CU01 transaction)
 * and COUSR02C (CU02 transaction) in the mainframe CardDemo application.
 * </p>
 *
 * <h3>COBOL Source Mapping</h3>
 * <p>
 * Transforms the SEC-USER-DATA structure defined in copybook CSUSR01Y.cpy (lines 17-23):
 * </p>
 * <pre>
 *  01 SEC-USER-DATA.
 *    05 SEC-USR-ID                 PIC X(08).   → userId
 *    05 SEC-USR-FNAME              PIC X(20).   → firstName
 *    05 SEC-USR-LNAME              PIC X(20).   → lastName
 *    05 SEC-USR-PWD                PIC X(08).   → password
 *    05 SEC-USR-TYPE               PIC X(01).   → userType
 * </pre>
 *
 * <h3>BMS Mapset References</h3>
 * <ul>
 *   <li><strong>COUSR01.bms</strong> (Add User screen):
 *       <ul>
 *         <li>FNAME field (line 84): First Name - LENGTH=20</li>
 *         <li>LNAME field (line 97): Last Name - LENGTH=20</li>
 *         <li>USERID field (line 111): User ID - LENGTH=8 with "(8 Char)" hint</li>
 *         <li>PASSWD field (line 126): Password - LENGTH=8 with "(8 Char)" hint, DRK attribute</li>
 *         <li>USRTYPE field (line 141): User Type - LENGTH=1 with "(A=Admin, U=User)" hint</li>
 *       </ul>
 *   </li>
 *   <li><strong>COUSR02.bms</strong> (Update User screen):
 *       <ul>
 *         <li>USRIDIN field (line 85): User ID input for lookup - LENGTH=8</li>
 *         <li>Similar field structure for update operations</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h3>Validation Rules</h3>
 * <p>
 * All validation annotations replace COBOL PIC clause constraints and 88-level condition names:
 * </p>
 * <ul>
 *   <li><strong>userId</strong>: Required, 1-8 characters (COBOL: PIC X(08))</li>
 *   <li><strong>firstName</strong>: Required, 1-20 characters (COBOL: PIC X(20))</li>
 *   <li><strong>lastName</strong>: Required, 1-20 characters (COBOL: PIC X(20))</li>
 *   <li><strong>password</strong>: Required, 1-8 characters (COBOL: PIC X(08))</li>
 *   <li><strong>userType</strong>: Required, must be 'A' (Admin) or 'U' (User) - transforms
 *       88-level conditions from COCOM01Y.cpy:
 *       <pre>
 *       88 CDEMO-USRTYP-ADMIN VALUE 'A'.  (line 27)
 *       88 CDEMO-USRTYP-USER  VALUE 'U'.  (line 28)
 *       </pre>
 *   </li>
 * </ul>
 *
 * <h3>REST API Endpoints</h3>
 * <p>
 * This DTO serves as the request body for the following endpoints in AdminController:
 * </p>
 * <ul>
 *   <li><strong>POST /api/admin/users</strong> - Create new user (replaces CU01 transaction)
 *       <br>Example JSON:
 *       <pre>
 *       {
 *         "userId": "NEWUSER1",
 *         "firstName": "John",
 *         "lastName": "Doe",
 *         "password": "Pass1234",
 *         "userType": "U"
 *       }
 *       </pre>
 *   </li>
 *   <li><strong>PUT /api/admin/users/{id}</strong> - Update existing user (replaces CU02 transaction)
 *       <br>Example JSON:
 *       <pre>
 *       {
 *         "userId": "ADMIN001",
 *         "firstName": "Jane",
 *         "lastName": "Smith",
 *         "password": "NewPass1",
 *         "userType": "A"
 *       }
 *       </pre>
 *   </li>
 * </ul>
 *
 * <h3>Service Layer Integration</h3>
 * <p>
 * Consumed by the following service classes in the stateless REST architecture:
 * </p>
 * <ul>
 *   <li><strong>UserCreateService</strong> - Processes POST requests for user creation</li>
 *   <li><strong>UserUpdateService</strong> - Processes PUT requests for user updates</li>
 * </ul>
 *
 * <h3>Security Considerations</h3>
 * <ul>
 *   <li>Password field should be transmitted over HTTPS only</li>
 *   <li>Password will be hashed using BCrypt before storage (never stored in plain text)</li>
 *   <li>User type validation enforces two-tier role model from RACF security</li>
 *   <li>Admin-only endpoints require ROLE_ADMIN authorization via Spring Security</li>
 * </ul>
 *
 * <h3>Lombok Annotations</h3>
 * <ul>
 *   <li><strong>@Data</strong> - Generates getters, setters, toString, equals, and hashCode</li>
 *   <li><strong>@NoArgsConstructor</strong> - Generates default constructor for JSON deserialization</li>
 *   <li><strong>@AllArgsConstructor</strong> - Generates all-args constructor for testing</li>
 *   <li><strong>@Builder</strong> - Enables fluent builder pattern for object construction</li>
 * </ul>
 *
 * @see com.carddemo.controller.AdminController
 * @see com.carddemo.service.user.UserCreateService
 * @see com.carddemo.service.user.UserUpdateService
 * @see com.carddemo.entity.User
 * @since 1.0
 * @version CardDemo Spring Boot Migration v1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRequest {

    /**
     * User identification code (User ID).
     * <p>
     * Maps to COBOL field: SEC-USR-ID PIC X(08) from CSUSR01Y.cpy line 18.
     * </p>
     * <p>
     * This field uniquely identifies a user in the system and serves as the primary key
     * for user records. In the mainframe system, this was validated against the USRSEC
     * VSAM KSDS file. In the Spring Boot application, it becomes the primary key in the
     * User entity.
     * </p>
     *
     * <h4>Validation Rules:</h4>
     * <ul>
     *   <li>Required: Cannot be null or blank</li>
     *   <li>Minimum length: 1 character</li>
     *   <li>Maximum length: 8 characters (COBOL PIC X(08) constraint)</li>
     *   <li>Typically alphanumeric, uppercase recommended for consistency</li>
     * </ul>
     *
     * <h4>BMS Field Reference:</h4>
     * <p>
     * COUSR01.bms line 111: USERID field with LENGTH=8 and hint "(8 Char)"
     * </p>
     *
     * @see com.carddemo.entity.User#getUserId()
     */
    @NotBlank(message = "User ID is required and cannot be blank")
    @Size(min = 1, max = 8, message = "User ID must be between 1 and 8 characters")
    @JsonProperty("userId")
    private String userId;

    /**
     * User's first name.
     * <p>
     * Maps to COBOL field: SEC-USR-FNAME PIC X(20) from CSUSR01Y.cpy line 19.
     * </p>
     * <p>
     * Represents the given name of the user for display and identification purposes
     * in user management screens and audit logs.
     * </p>
     *
     * <h4>Validation Rules:</h4>
     * <ul>
     *   <li>Required: Cannot be null or blank</li>
     *   <li>Minimum length: 1 character</li>
     *   <li>Maximum length: 20 characters (COBOL PIC X(20) constraint)</li>
     *   <li>Typically alphabetic characters, may include spaces and hyphens</li>
     * </ul>
     *
     * <h4>BMS Field Reference:</h4>
     * <p>
     * COUSR01.bms line 84: FNAME field with LENGTH=20, labeled "First Name:"
     * </p>
     *
     * @see com.carddemo.entity.User#getFirstName()
     */
    @NotBlank(message = "First name is required and cannot be blank")
    @Size(min = 1, max = 20, message = "First name must be between 1 and 20 characters")
    @JsonProperty("firstName")
    private String firstName;

    /**
     * User's last name (surname/family name).
     * <p>
     * Maps to COBOL field: SEC-USR-LNAME PIC X(20) from CSUSR01Y.cpy line 20.
     * </p>
     * <p>
     * Represents the family name of the user for display and identification purposes
     * in user management screens and audit logs.
     * </p>
     *
     * <h4>Validation Rules:</h4>
     * <ul>
     *   <li>Required: Cannot be null or blank</li>
     *   <li>Minimum length: 1 character</li>
     *   <li>Maximum length: 20 characters (COBOL PIC X(20) constraint)</li>
     *   <li>Typically alphabetic characters, may include spaces and hyphens</li>
     * </ul>
     *
     * <h4>BMS Field Reference:</h4>
     * <p>
     * COUSR01.bms line 97: LNAME field with LENGTH=20, labeled "Last Name:"
     * </p>
     *
     * @see com.carddemo.entity.User#getLastName()
     */
    @NotBlank(message = "Last name is required and cannot be blank")
    @Size(min = 1, max = 20, message = "Last name must be between 1 and 20 characters")
    @JsonProperty("lastName")
    private String lastName;

    /**
     * User's password for authentication.
     * <p>
     * Maps to COBOL field: SEC-USR-PWD PIC X(08) from CSUSR01Y.cpy line 21.
     * </p>
     * <p>
     * This field contains the plain text password submitted by the client. It is used only
     * during create and update operations and is NEVER stored in plain text. The service layer
     * will hash this password using BCrypt before persisting to the database.
     * </p>
     *
     * <h4>Validation Rules:</h4>
     * <ul>
     *   <li>Required: Cannot be null or blank</li>
     *   <li>Minimum length: 1 character (should enforce stronger rules at service layer)</li>
     *   <li>Maximum length: 8 characters (COBOL PIC X(08) constraint from mainframe system)</li>
     *   <li>Note: 8-character limit is a mainframe legacy constraint. Modern systems typically
     *       support longer passwords, but functional equivalence requires maintaining this limit.</li>
     * </ul>
     *
     * <h4>BMS Field Reference:</h4>
     * <p>
     * COUSR01.bms line 126: PASSWD field with LENGTH=8, ATTRB=(DRK,...) for hidden input,
     * hint "(8 Char)"
     * </p>
     *
     * <h4>Security Notes:</h4>
     * <ul>
     *   <li>Transmitted over HTTPS only (never HTTP)</li>
     *   <li>Hashed using BCrypt with salt before storage</li>
     *   <li>Original plain text password discarded after hashing</li>
     *   <li>Password never returned in response DTOs</li>
     *   <li>BMS screen uses DRK (dark) attribute to hide password entry</li>
     * </ul>
     *
     * @see com.carddemo.service.user.UserCreateService
     * @see com.carddemo.service.user.UserUpdateService
     * @see org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
     */
    @NotBlank(message = "Password is required and cannot be blank")
    @Size(min = 1, max = 8, message = "Password must be between 1 and 8 characters")
    @JsonProperty("password")
    private String password;

    /**
     * User type indicator defining role and privileges.
     * <p>
     * Maps to COBOL field: SEC-USR-TYPE PIC X(01) from CSUSR01Y.cpy line 22.
     * </p>
     * <p>
     * This field implements the two-tier role model from the mainframe RACF security system.
     * It determines the user's authorization level and controls access to administrative
     * functions.
     * </p>
     *
     * <h4>Valid Values (from COCOM01Y.cpy 88-level conditions):</h4>
     * <ul>
     *   <li><strong>'A'</strong> - Admin User (CDEMO-USRTYP-ADMIN, line 27)
     *       <br>Grants access to:
     *       <ul>
     *         <li>User management functions (create, update, delete users)</li>
     *         <li>All regular user functions</li>
     *         <li>Administrative menu (CA00 transaction / AdminMenuService)</li>
     *         <li>Credit limit updates</li>
     *       </ul>
     *   </li>
     *   <li><strong>'U'</strong> - Regular User (CDEMO-USRTYP-USER, line 28)
     *       <br>Grants access to:
     *       <ul>
     *         <li>Account view and basic operations</li>
     *         <li>Card management (view only)</li>
     *         <li>Transaction viewing and reporting</li>
     *         <li>Bill payment processing</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <h4>Validation Rules:</h4>
     * <ul>
     *   <li>Required: Cannot be null or blank</li>
     *   <li>Must match pattern: ^[AU]$ (exactly 'A' or 'U', case-sensitive)</li>
     *   <li>Any other value will result in validation error</li>
     * </ul>
     *
     * <h4>BMS Field Reference:</h4>
     * <p>
     * COUSR01.bms line 141: USRTYPE field with LENGTH=1 and hint "(A=Admin, U=User)"
     * </p>
     *
     * <h4>Spring Security Integration:</h4>
     * <p>
     * This field maps to Spring Security roles in the User entity:
     * </p>
     * <ul>
     *   <li>'A' → ROLE_ADMIN (hasRole('ADMIN') in @PreAuthorize)</li>
     *   <li>'U' → ROLE_USER (hasRole('USER') in @PreAuthorize)</li>
     * </ul>
     *
     * @see com.carddemo.entity.User#getUserType()
     * @see com.carddemo.security.CustomUserDetailsService
     * @see org.springframework.security.access.prepost.PreAuthorize
     */
    @NotBlank(message = "User type is required and cannot be blank")
    @Pattern(
        regexp = "^[AU]$",
        message = "User type must be 'A' (Admin) or 'U' (User)"
    )
    @JsonProperty("userType")
    private String userType;

}
