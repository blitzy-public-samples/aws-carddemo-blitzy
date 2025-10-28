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

import com.carddemo.model.entity.UserSecurity;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for user security and authentication data exposed via REST API.
 * 
 * Mapped from entity: UserSecurity.java (originally from COBOL copybook CSUSR01Y.cpy)
 * Original COBOL structure: SEC-USER-DATA
 * 
 * Purpose:
 * - Provides clean API boundary for user management REST responses
 * - Used by UserController for GET /api/users and user profile endpoints
 * - Implements DTO pattern separating external API representation from internal persistence entity
 * 
 * Security:
 * - Explicitly EXCLUDES userPwdHash field (SEC-USR-PWD) per Section 0.4.10 requirement
 * - Sensitive password data is never exposed through REST API responses
 * - Also excludes internal JPA version field (optimistic locking implementation detail)
 * 
 * Field Mapping from COBOL:
 * - SEC-USR-ID (PIC X(08)) → userId (String, max 8 chars)
 * - SEC-USR-FNAME (PIC X(20)) → userFirstName (String, max 25 chars per DB schema)
 * - SEC-USR-LNAME (PIC X(20)) → userLastName (String, max 25 chars per DB schema)
 * - SEC-USR-TYPE (PIC X(01)) → userType (String, 1 char: 'A'=Admin, 'U'=User, 'O'=Operator)
 * - Audit timestamps added: createdAt, updatedAt, lastLoginTs (converted from java.sql.Timestamp to LocalDateTime)
 * 
 * JSON Serialization:
 * - Uses Jackson for automatic JSON conversion in REST responses
 * - Timestamp fields formatted as ISO 8601 (yyyy-MM-dd'T'HH:mm:ss)
 * - Lombok @Data provides getters/setters for JavaBean pattern
 * 
 * Usage Example:
 * <pre>
 * UserSecurity entity = userRepository.findById(userId).orElseThrow();
 * UserDto dto = UserDto.fromEntity(entity); // Excludes password hash
 * return ResponseEntity.ok(dto);
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    /**
     * User ID - Unique identifier for user authentication.
     * Mapped from COBOL: SEC-USR-ID (PIC X(08))
     * Max length: 8 characters
     * 
     * Validation: Max 8 characters (COBOL field length)
     * Note: @NotBlank removed to support both POST (userId in body) and PUT (userId in path) requests
     * For POST, userId is validated manually in UserService.createUser()
     * For PUT, userId comes from @PathVariable and is not in request body
     */
    @Size(max = 8, message = "User ID must not exceed 8 characters")
    private String userId;

    /**
     * User first name.
     * Mapped from COBOL: SEC-USR-FNAME (PIC X(20))
     * Max length: 25 characters (database schema)
     */
    @Size(max = 25, message = "User first name must not exceed 25 characters")
    private String userFirstName;

    /**
     * User last name.
     * Mapped from COBOL: SEC-USR-LNAME (PIC X(20))
     * Max length: 25 characters (database schema)
     */
    @Size(max = 25, message = "User last name must not exceed 25 characters")
    private String userLastName;

    /**
     * User type code for role-based access control.
     * Mapped from COBOL: SEC-USR-TYPE (PIC X(01))
     * 
     * Valid values:
     * - 'A' = ROLE_ADMIN (administrator privileges)
     * - 'U' = ROLE_USER (standard user privileges)
     * - 'O' = ROLE_OPERATOR (operator privileges)
     * 
     * Used by Spring Security for authorization decisions.
     * 
     * Validation: Must be exactly one character matching 'A', 'U', or 'O' if present
     * (COBOL validation from COUSR01C.cbl VALIDATE-USER-TYPE paragraph)
     * Note: @NotBlank removed to support partial updates (PUT requests)
     * For POST, userType is validated manually in UserService.createUser()
     */
    @Pattern(regexp = "[AUO]", message = "User type must be A (Admin), U (User), or O (Operator)")
    private String userType;

    /**
     * Plain-text password for user creation/update (INPUT ONLY).
     * 
     * SECURITY: This field is only used for INPUT (POST/PUT requests).
     * It is NEVER included in output DTOs (fromEntity() method does not set this field).
     * 
     * The password is hashed using BCrypt before storage in database.
     * Per Section 0.7.9: COBOL plain-text passwords → BCrypt hashed passwords
     * 
     * Password strength requirements: Minimum 8 characters, complexity rules
     * 
     * Validation: Minimum 8 characters (per Section 0.7.9 Security Migration)
     */
    @Size(min = 8, message = "Password must be at least 8 characters long")
    private String password;

    /**
     * Timestamp when user account was created.
     * Audit field for tracking user account creation.
     * 
     * Formatted as ISO 8601 in JSON responses: yyyy-MM-dd'T'HH:mm:ss
     * Converted from java.sql.Timestamp to java.time.LocalDateTime for modern date-time API.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * Timestamp when user account was last updated.
     * Audit field for tracking user account modifications.
     * 
     * Formatted as ISO 8601 in JSON responses: yyyy-MM-dd'T'HH:mm:ss
     * Converted from java.sql.Timestamp to java.time.LocalDateTime for modern date-time API.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * Timestamp of last successful login.
     * Audit field for tracking user authentication activity.
     * 
     * Replaces RACF audit trail functionality with Spring Security audit logging per Section 0.7.9.
     * Formatted as ISO 8601 in JSON responses: yyyy-MM-dd'T'HH:mm:ss
     * Converted from java.sql.Timestamp to java.time.LocalDateTime for modern date-time API.
     * 
     * May be null if user has never logged in.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastLoginTs;

    /**
     * Static factory method to convert UserSecurity entity to UserDto.
     * 
     * This method creates a clean DTO from the JPA entity while:
     * - EXCLUDING sensitive userPwdHash field (never exposed via REST API)
     * - EXCLUDING internal version field (JPA optimistic locking implementation detail)
     * - Converting java.sql.Timestamp to java.time.LocalDateTime for better JSON serialization
     * - Handling null entity gracefully by returning null DTO
     * 
     * Implements DTO pattern per Section 0.3.3 Design Pattern Applications:
     * "Create Data Transfer Objects for REST API request/response.
     *  Separate entity persistence concerns from API contracts."
     * 
     * Security Design Decision:
     * The password hash is intentionally excluded to prevent accidental exposure
     * of sensitive authentication credentials through REST API responses.
     * Even hashed passwords should never be transmitted to clients.
     * 
     * @param entity UserSecurity entity to convert (may be null)
     * @return UserDto with all non-sensitive fields mapped, or null if entity is null
     * 
     * @see UserSecurity for full entity definition including password hash
     */
    public static UserDto fromEntity(UserSecurity entity) {
        // Handle null entity gracefully
        if (entity == null) {
            return null;
        }

        // Build DTO using Lombok builder pattern
        // Explicitly access only non-sensitive fields from entity
        return UserDto.builder()
                .userId(entity.getUserId())
                .userFirstName(entity.getUserFirstName())
                .userLastName(entity.getUserLastName())
                .userType(entity.getUserType())
                // Convert java.sql.Timestamp to java.time.LocalDateTime
                // Timestamp.toLocalDateTime() provides direct conversion without timezone complications
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toLocalDateTime() : null)
                .lastLoginTs(entity.getLastLoginTs() != null ? entity.getLastLoginTs().toLocalDateTime() : null)
                .build();
        
        // Note: Intentionally NOT including:
        // - entity.getUserPwdHash() - SECURITY: Never expose password hashes via REST API
        // - entity.getVersion() - Internal JPA optimistic locking field, not relevant for API consumers
    }
}
