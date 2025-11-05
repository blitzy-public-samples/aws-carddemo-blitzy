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
package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Login Request DTO for user authentication.
 * 
 * This Data Transfer Object captures username and password credentials from the COSGN00 
 * sign-on BMS screen for authentication processing. Maps to COBOL BMS copybook fields:
 * - USERIDI PIC X(8) -> userId field
 * - PASSWDI PIC X(8) -> password field
 * 
 * Implements security-focused validation preventing injection attacks and enforcing 
 * credential format constraints per section 0.9 authentication requirements. 
 * Supports both regular user (ROLE_USER) and administrative user (ROLE_ADMIN) 
 * authentication per two-tier role model defined in section 0.1.
 * 
 * Security Considerations:
 * - Password field excluded from equals/hashCode/toString to prevent accidental logging
 * - Actual password encryption (BCrypt) handled by Spring Security UserDetailsService
 * - Alphanumeric-only validation on userId prevents injection attacks
 * - Password field allows special characters for enhanced security
 * 
 * Transaction Interface Contract Preservation (Section 0.3):
 * - COBOL CICS Transaction CC00 (Sign-on) maps to POST /api/auth/login
 * - Input COMMAREA structure transformed to JSON authentication request
 * - Field lengths from COBOL PIC X(8) enforced via @Size constraints
 * 
 * @see com.carddemo.entity.UserSecurity
 * @see com.carddemo.service.AuthenticationService
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(exclude = "password")
@ToString(exclude = "password")
public class LoginRequest {

    /**
     * User identifier for authentication.
     * 
     * Maps to COBOL field USERIDI PIC X(8) from COSGN00 BMS copybook.
     * Validated to contain only alphanumeric characters (A-Z, a-z, 0-9) to prevent
     * injection attacks and special character abuse per security requirements.
     * 
     * Validation Rules:
     * - Must not be blank or null
     * - Length must be between 1 and 8 characters (COBOL PIC X(8) constraint)
     * - Pattern: [A-Za-z0-9]+ (alphanumeric only, no special characters)
     * 
     * Examples of valid userIds: "USER001", "admin", "TestUser", "12345678"
     * Examples of invalid userIds: "user@123", "test user", "user$name"
     */
    @NotBlank(message = "User ID must not be blank")
    @Size(min = 1, max = 8, message = "User ID must be between 1 and 8 characters")
    @Pattern(regexp = "[A-Za-z0-9]+", message = "User ID must contain only alphanumeric characters")
    @JsonProperty("userId")
    private String userId;

    /**
     * User password for authentication.
     * 
     * Maps to COBOL field PASSWDI PIC X(8) from COSGN00 BMS copybook.
     * Intentionally does NOT include @Pattern validation to allow special characters
     * for enhanced password security (e.g., !@#$%^&*).
     * 
     * Validation Rules:
     * - Must not be blank or null
     * - Length must be between 1 and 8 characters (COBOL PIC X(8) constraint)
     * - No character type restrictions (allows alphanumeric and special characters)
     * 
     * Security Notes:
     * - Plain text password received via HTTPS/TLS encrypted connection
     * - BCrypt hashing performed by Spring Security UserDetailsService
     * - Excluded from equals(), hashCode(), and toString() methods for security
     * - Never logged in plain text per section 0.9 security requirements
     * 
     * Examples of valid passwords: "Pass123!", "admin$01", "!Secure8", "12345678"
     */
    @NotBlank(message = "Password must not be blank")
    @Size(min = 1, max = 8, message = "Password must be between 1 and 8 characters")
    @JsonProperty("password")
    private String password;

    /**
     * Constructs a LoginRequest with specified user credentials.
     * 
     * @param userId the user identifier (1-8 alphanumeric characters)
     * @param password the user password (1-8 characters, allows special characters)
     */
    public LoginRequest(String userId, String password) {
        this.userId = userId;
        this.password = password;
    }
}
