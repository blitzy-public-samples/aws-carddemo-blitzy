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

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Authentication Response DTO
 * 
 * Data Transfer Object for successful user authentication response.
 * Returns JWT token, expiration time, user identifier, and user type/role
 * to the REST API client after successful login.
 * 
 * Converted from COBOL copybook: CSUSR01Y.cpy (SEC-USER-DATA structure)
 * Original function: User security record layout used in CICS COMMAREA
 * 
 * Conversion notes:
 * - Replaces COBOL EXEC CICS RETURN with COMMAREA containing user session data
 * - Implements stateless JWT authentication instead of CICS session management
 * - SEC-USR-ID (PIC X(08)) → userId (String)
 * - SEC-USR-TYPE (PIC X(01)) → userType (String - role code)
 * - Maps userType to Spring Security granted authorities:
 *   'A' → ROLE_ADMIN
 *   'U' → ROLE_USER
 *   'O' → ROLE_OPERATOR
 * 
 * This DTO enables stateless JWT-based authentication replacing RACF security
 * and CICS session state management. Used by AuthController POST /api/auth/login
 * response.
 * 
 * @param token JWT token string for bearer authentication
 * @param expiresIn Token expiration time in seconds from issuance
 * @param userId User identifier from SEC-USR-ID (8-character user ID)
 * @param userType User role code from SEC-USR-TYPE (single character: A/U/O)
 */
@Schema(description = "Authentication response containing JWT token and user information")
public record AuthResponse(
    
    @Schema(
        description = "JWT bearer token for authenticated API requests",
        example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
        required = true
    )
    @JsonProperty("token")
    String token,
    
    @Schema(
        description = "Token expiration time in seconds from issuance",
        example = "3600",
        required = true
    )
    @JsonProperty("expiresIn")
    long expiresIn,
    
    @Schema(
        description = "User identifier (8-character user ID from SEC-USR-ID)",
        example = "B0001",
        required = true,
        maxLength = 8
    )
    @JsonProperty("userId")
    String userId,
    
    @Schema(
        description = "User role type code (A=Admin, U=User, O=Operator from SEC-USR-TYPE)",
        example = "U",
        required = true,
        maxLength = 1,
        allowableValues = {"A", "U", "O"}
    )
    @JsonProperty("userType")
    String userType
) {
    
    /**
     * Canonical constructor for validation and normalization.
     * 
     * Trims userId and userType to match COBOL field behavior (trailing spaces removed),
     * then validates all required fields and ensures data integrity:
     * - Token must not be null or empty
     * - ExpiresIn must be positive
     * - UserId must not be null or empty and must be 8 characters or less (after trimming)
     * - UserType must be a valid single character (A, U, or O) (after trimming)
     * 
     * This constructor explicitly trims COBOL-style padded fields before validation,
     * preserving mainframe data handling semantics while ensuring data validation.
     * 
     * @throws IllegalArgumentException if any validation fails
     */
    public AuthResponse(String token, long expiresIn, String userId, String userType) {
        // Validate token
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Token must not be null or empty");
        }
        
        // Validate expiresIn
        if (expiresIn <= 0) {
            throw new IllegalArgumentException("ExpiresIn must be positive");
        }
        
        // Trim userId and userType to match COBOL field behavior (trailing spaces removed)
        // This must be done BEFORE validation to handle COBOL PIC X fields correctly
        String trimmedUserId = (userId != null) ? userId.trim() : null;
        String trimmedUserType = (userType != null) ? userType.trim() : null;
        
        // Validate userId (after trimming)
        if (trimmedUserId == null || trimmedUserId.isEmpty()) {
            throw new IllegalArgumentException("UserId must not be null or empty");
        }
        if (trimmedUserId.length() > 8) {
            throw new IllegalArgumentException("UserId must not exceed 8 characters");
        }
        
        // Validate userType (after trimming)
        if (trimmedUserType == null || trimmedUserType.isEmpty()) {
            throw new IllegalArgumentException("UserType must not be null or empty");
        }
        if (trimmedUserType.length() != 1) {
            throw new IllegalArgumentException("UserType must be a single character");
        }
        if (!trimmedUserType.matches("[AUO]")) {
            throw new IllegalArgumentException(
                "UserType must be 'A' (Admin), 'U' (User), or 'O' (Operator)"
            );
        }
        
        // Assign trimmed values to record fields
        this.token = token;
        this.expiresIn = expiresIn;
        this.userId = trimmedUserId;
        this.userType = trimmedUserType;
    }
    
    /**
     * Gets the Spring Security role name corresponding to the userType.
     * 
     * Maps COBOL SEC-USR-TYPE values to Spring Security granted authorities:
     * - 'A' → ROLE_ADMIN (Administrator with full system access)
     * - 'U' → ROLE_USER (Regular user with standard access)
     * - 'O' → ROLE_OPERATOR (Operator with operational access)
     * 
     * This mapping preserves the RACF security model from the mainframe
     * implementation while adapting to Spring Security conventions.
     * 
     * @return Spring Security role name (e.g., "ROLE_ADMIN", "ROLE_USER", "ROLE_OPERATOR")
     */
    public String getSpringSecurityRole() {
        return switch (userType) {
            case "A" -> "ROLE_ADMIN";
            case "U" -> "ROLE_USER";
            case "O" -> "ROLE_OPERATOR";
            default -> throw new IllegalStateException(
                "Invalid userType: " + userType + " (should have been validated in constructor)"
            );
        };
    }
    
    /**
     * Gets the human-readable role description.
     * 
     * Provides descriptive text for the user role type suitable for
     * display in UI components or logging.
     * 
     * @return Human-readable role description
     */
    public String getRoleDescription() {
        return switch (userType) {
            case "A" -> "Administrator";
            case "U" -> "User";
            case "O" -> "Operator";
            default -> "Unknown";
        };
    }
    
    /**
     * Checks if the user has administrator privileges.
     * 
     * Convenience method for authorization checks in service layer
     * and business logic.
     * 
     * @return true if userType is 'A' (Admin), false otherwise
     */
    public boolean isAdmin() {
        return "A".equals(userType);
    }
    
    /**
     * Checks if the user has operator privileges.
     * 
     * Convenience method for authorization checks related to
     * operational tasks and system management.
     * 
     * @return true if userType is 'O' (Operator), false otherwise
     */
    public boolean isOperator() {
        return "O".equals(userType);
    }
    
    /**
     * Checks if the user is a regular user (not admin or operator).
     * 
     * Convenience method for authorization checks for standard
     * user operations.
     * 
     * @return true if userType is 'U' (User), false otherwise
     */
    public boolean isRegularUser() {
        return "U".equals(userType);
    }
}
