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

package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * JPA Entity representing User Security Data
 * 
 * Transformed from COBOL copybook CSUSR01Y.cpy (SEC-USER-DATA)
 * Original VSAM KSDS user security file mapped to PostgreSQL app_user table
 * 
 * This entity replaces RACF security controls with Spring Security authentication.
 * Supports two-tier role model: Admin users (type 'A') and Regular users (type 'U').
 * 
 * COBOL Structure Mapping:
 * - SEC-USR-ID (PIC X(08))    -> userId (String, max 8 chars, primary key)
 * - SEC-USR-FNAME (PIC X(20)) -> firstName (String, max 20 chars)
 * - SEC-USR-LNAME (PIC X(20)) -> lastName (String, max 20 chars)
 * - SEC-USR-PWD (PIC X(08))   -> password (String, BCrypt encrypted, max 255 chars)
 * - SEC-USR-TYPE (PIC X(01))  -> userType (UserType enum: ADMIN='A', USER='U')
 * 
 * Additional fields for audit trail and soft delete functionality:
 * - createdDate: Timestamp of user creation
 * - updatedDate: Timestamp of last modification
 * - lastLoginDate: Timestamp of last successful login
 * - deleted: Soft delete flag
 * - deletedDate: Timestamp of deletion
 * - deletedBy: User ID who performed the deletion
 * - version: Optimistic locking version for concurrent update prevention
 * 
 * Table name "app_user" avoids SQL reserved keyword 'user'.
 * Implements Serializable for Redis session storage (COMMAREA replacement).
 */
@Entity
@Table(name = "app_user")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * User ID - Primary Key
     * Maps to SEC-USR-ID from COBOL copybook
     * Maximum length: 8 characters (matching COBOL PIC X(08))
     */
    @Id
    @Column(name = "user_id", length = 8, nullable = false)
    private String userId;

    /**
     * User First Name
     * Maps to SEC-USR-FNAME from COBOL copybook
     * Maximum length: 20 characters (matching COBOL PIC X(20))
     */
    @Column(name = "first_name", length = 20, nullable = false)
    private String firstName;

    /**
     * User Last Name
     * Maps to SEC-USR-LNAME from COBOL copybook
     * Maximum length: 20 characters (matching COBOL PIC X(20))
     */
    @Column(name = "last_name", length = 20, nullable = false)
    private String lastName;

    /**
     * Encrypted Password
     * Maps to SEC-USR-PWD from COBOL copybook (originally PIC X(08))
     * Stored as BCrypt hash (up to 255 characters)
     * Original COBOL field was 8 chars but encrypted hash requires more space
     */
    @Column(name = "password", length = 255, nullable = false)
    private String password;

    /**
     * User Type - Admin or Regular User
     * Maps to SEC-USR-TYPE from COBOL copybook (PIC X(01))
     * Stored as String in database: 'A' for ADMIN, 'U' for USER
     * Preserves two-tier role model from mainframe RACF security
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", length = 10, nullable = false)
    private UserType userType;

    /**
     * Created Date - Audit field
     * Timestamp when user record was created
     * Automatically set on insert
     */
    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    /**
     * Updated Date - Audit field
     * Timestamp when user record was last modified
     * Automatically updated on each update operation
     */
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    /**
     * Last Login Date
     * Timestamp of user's last successful authentication
     * Updated by AuthenticationService on successful login
     */
    @Column(name = "last_login_date")
    private LocalDateTime lastLoginDate;

    /**
     * Soft Delete Flag
     * Indicates whether user is logically deleted
     * Default: false (active user)
     */
    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    /**
     * Deleted Date
     * Timestamp when user was soft deleted
     * Null for active users
     */
    @Column(name = "deleted_date")
    private LocalDateTime deletedDate;

    /**
     * Deleted By
     * User ID of the administrator who deleted this user
     * Null for active users
     */
    @Column(name = "deleted_by", length = 8)
    private String deletedBy;

    /**
     * Optimistic Locking Version
     * Prevents concurrent update conflicts
     * Automatically incremented by JPA on each update
     * Matches mainframe record locking behavior
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * JPA lifecycle callback - executed before persist
     * Sets createdDate to current timestamp
     */
    @PrePersist
    protected void onCreate() {
        this.createdDate = LocalDateTime.now();
        if (this.updatedDate == null) {
            this.updatedDate = LocalDateTime.now();
        }
    }

    /**
     * JPA lifecycle callback - executed before update
     * Sets updatedDate to current timestamp
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedDate = LocalDateTime.now();
    }

    /**
     * User Type Enumeration
     * 
     * Represents the two-tier role model from mainframe RACF security:
     * - ADMIN ('A'): Administrative users with full system access
     * - USER ('U'): Regular users with restricted access
     * 
     * Maps to SEC-USR-TYPE field in COBOL copybook (PIC X(01))
     * Used by Spring Security for role-based access control
     */
    public enum UserType {
        /**
         * Administrative User
         * Code: 'A'
         * Full access to all system functions including user management
         */
        ADMIN("A", "Administrative User"),

        /**
         * Regular User
         * Code: 'U'
         * Standard access to customer-facing functions
         */
        USER("U", "Regular User");

        private final String code;
        private final String description;

        /**
         * Constructor for UserType enum
         * 
         * @param code Single character code matching COBOL value ('A' or 'U')
         * @param description Human-readable description of the user type
         */
        UserType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        /**
         * Gets the single character code for this user type
         * Matches COBOL SEC-USR-TYPE field value
         * 
         * @return 'A' for ADMIN, 'U' for USER
         */
        public String getCode() {
            return code;
        }

        /**
         * Gets the human-readable description of this user type
         * 
         * @return Description string for display purposes
         */
        public String getDescription() {
            return description;
        }

        /**
         * Gets the display name for this user type matching COBOL screen display format.
         * 
         * <p>This method provides the short display label used in the COBOL COUSR00C.cbl
         * list screen, matching the COBOL pattern:</p>
         * <pre>
         * IF SEC-USR-TYPE = 'A'
         *   MOVE 'Admin   ' TO USER-TYPE
         * ELSE IF SEC-USR-TYPE = 'U'
         *   MOVE 'User    ' TO USER-TYPE
         * END-IF.
         * </pre>
         * 
         * @return "Admin" for ADMIN type, "User" for USER type
         */
        public String getDisplayName() {
            return this == ADMIN ? "Admin" : "User";
        }

        /**
         * Finds UserType enum by code value
         * Useful for converting COBOL character codes to enum
         * 
         * @param code Single character code ('A' or 'U')
         * @return Matching UserType enum value
         * @throws IllegalArgumentException if code is not valid
         */
        public static UserType fromCode(String code) {
            for (UserType type : UserType.values()) {
                if (type.code.equals(code)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid user type code: " + code);
        }
    }
}
