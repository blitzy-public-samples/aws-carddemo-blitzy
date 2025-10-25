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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Authentication request DTO for user login credentials.
 * 
 * <p>Converted from COBOL copybook: CSUSR01Y.cpy (SEC-USER-DATA structure)
 * 
 * <p>This DTO replaces the COBOL EXEC CICS RECEIVE MAP functionality from the 
 * COSGN00C.cbl signon program. Instead of receiving data through BMS (Basic 
 * Mapping Support) map fields, this DTO accepts JSON request body data from 
 * the REST API POST /api/auth/login endpoint.
 * 
 * <p>Field mappings from COBOL to Java:
 * <ul>
 *   <li>SEC-USR-ID (PIC X(08)) → userId (String, max 8 chars)</li>
 *   <li>SEC-USR-PWD (PIC X(08)) → password (String, max 8 chars)</li>
 * </ul>
 * 
 * <p>The COBOL copybook CSUSR01Y.cpy contains additional fields (first name, 
 * last name, user type) that are NOT included in this authentication request DTO 
 * as they are not required for the login operation. Those fields are part of the 
 * UserSecurity entity and UserDto response objects.
 * 
 * <p>Validation constraints ensure field lengths match original COBOL PIC X(08) 
 * declarations to maintain data compatibility and prevent buffer overflow issues 
 * when interfacing with migrated database schema.
 * 
 * <p>Usage example:
 * <pre>
 * AuthRequest request = AuthRequest.builder()
 *     .userId("ADMIN001")
 *     .password("Pass1234")
 *     .build();
 * </pre>
 * 
 * @see com.carddemo.controller.AuthController
 * @see com.carddemo.model.entity.UserSecurity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthRequest {
    
    /**
     * User identifier for authentication.
     * 
     * <p>Maps to COBOL field: SEC-USR-ID PIC X(08) from CSUSR01Y.cpy
     * 
     * <p>This field corresponds to the user ID entered on the COSGN00 BMS signon 
     * screen in the original CICS application. The 8-character maximum length 
     * constraint preserves the exact COBOL field definition to ensure compatibility 
     * with the UserSecurity entity's userId column.
     * 
     * <p>Validation rules:
     * <ul>
     *   <li>Required field - cannot be null, empty, or whitespace only</li>
     *   <li>Maximum length: 8 characters (COBOL PIC X(08) constraint)</li>
     *   <li>Typically uppercase alphanumeric in original COBOL system</li>
     * </ul>
     */
    @NotBlank(message = "User ID is required")
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    private String userId;
    
    /**
     * User password for authentication.
     * 
     * <p>Maps to COBOL field: SEC-USR-PWD PIC X(08) from CSUSR01Y.cpy
     * 
     * <p>This field corresponds to the password entered on the COSGN00 BMS signon 
     * screen. In the original COBOL system, passwords were stored in plain text 
     * (COBOL limitation). The migrated Java system uses BCrypt hashing in the 
     * UserSecurity entity, so this plain-text password is only held temporarily 
     * during the authentication request and is hashed before comparison.
     * 
     * <p>The 8-character maximum constraint matches the original COBOL PIC X(08) 
     * definition. While this is a weak password policy by modern standards, it is 
     * preserved during initial migration to maintain functional equivalence with 
     * the COBOL system. Password policy enhancements are out of scope for the 
     * technology migration phase per the minimal change directive.
     * 
     * <p>Validation rules:
     * <ul>
     *   <li>Required field - cannot be null, empty, or whitespace only</li>
     *   <li>Maximum length: 8 characters (COBOL PIC X(08) constraint)</li>
     * </ul>
     * 
     * <p>Security note: This field is never logged or persisted in plain text. 
     * The password is immediately hashed using BCrypt during authentication 
     * processing in the AuthService layer.
     */
    @NotBlank(message = "Password is required")
    @Size(max = 8, message = "Password must not exceed 8 characters")
    private String password;
}
