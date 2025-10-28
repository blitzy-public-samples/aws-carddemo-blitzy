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

package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

/**
 * JPA Entity representing user security and authentication data.
 * 
 * Converted from COBOL copybook: CSUSR01Y.cpy (SEC-USER-DATA)
 * Original function: User security record layout for RACF authentication
 * 
 * Conversion notes:
 * - PIC X(08) SEC-USR-ID converted to String userId (primary key, max length 8)
 * - PIC X(20) SEC-USR-FNAME converted to String userFirstName (max length 25 per database schema)
 * - PIC X(20) SEC-USR-LNAME converted to String userLastName (max length 25 per database schema)
 * - PIC X(08) SEC-USR-PWD converted to String userPwdHash (max length 100 for BCrypt hash storage)
 *   Per Section 0.7.9: COBOL plain-text passwords → BCrypt hashed passwords in PostgreSQL
 * - PIC X(01) SEC-USR-TYPE converted to String userType (max length 1 for role identification)
 *   Maps to Spring Security granted authorities: ROLE_ADMIN, ROLE_USER, ROLE_OPERATOR
 * - SEC-USR-FILLER removed (not needed in relational model)
 * - Added audit fields: createdAt, updatedAt, lastLoginTs for Spring Security audit logging
 * - Added version field for optimistic locking (JPA @Version)
 * 
 * Database table: user_security
 * Primary key: user_id
 * Index: idx_user_type on user_type column for role-based query optimization
 * 
 * Used by Spring Security authentication system to replace RACF mainframe security.
 * Contains sensitive authentication data requiring secure handling.
 */
@Entity
@Table(name = "user_security", indexes = {
    @Index(name = "idx_user_type", columnList = "user_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSecurity {

    /**
     * User ID - Primary key.
     * Converted from COBOL: PIC X(08) SEC-USR-ID
     * Max length: 8 characters
     */
    @Id
    @Column(name = "user_id", length = 8, nullable = false)
    private String userId;

    /**
     * BCrypt hashed password.
     * Converted from COBOL: PIC X(08) SEC-USR-PWD
     * Original: Plain-text 8-character password
     * Modernized: BCrypt hash with max length 100 to store encrypted password
     * Per Section 0.7.9 Security Migration: COBOL plain-text passwords → BCrypt hashed passwords
     */
    @Column(name = "user_pwd_hash", length = 100, nullable = false)
    private String userPwdHash;

    /**
     * User first name.
     * Converted from COBOL: PIC X(20) SEC-USR-FNAME
     * Database schema defines max length as 25 per Section 0.3.4
     */
    @Column(name = "user_first_name", length = 25)
    private String userFirstName;

    /**
     * User last name.
     * Converted from COBOL: PIC X(20) SEC-USR-LNAME
     * Database schema defines max length as 25 per Section 0.3.4
     */
    @Column(name = "user_last_name", length = 25)
    private String userLastName;

    /**
     * User type code for role-based access control.
     * Converted from COBOL: PIC X(01) SEC-USR-TYPE
     * Maps to Spring Security granted authorities:
     * - 'A' = ROLE_ADMIN (administrator privileges)
     * - 'U' = ROLE_USER (standard user privileges)
     * - 'O' = ROLE_OPERATOR (operator privileges)
     * Max length: 1 character
     * 
     * Column Definition: CHAR(1) to preserve COBOL fixed-length character semantics.
     * Per Section 0.7.2: Must maintain exact COBOL PIC X(01) behavior.
     */
    @Column(name = "user_type", nullable = false, columnDefinition = "CHAR(1)")
    private String userType;

    /**
     * Timestamp when user record was created.
     * Audit field for tracking user account creation.
     * Database default: CURRENT_TIMESTAMP
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /**
     * Timestamp when user record was last updated.
     * Audit field for tracking user account modifications.
     * Database default: CURRENT_TIMESTAMP
     */
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    /**
     * Timestamp of last successful login.
     * Audit field for tracking user authentication activity.
     * Replaces RACF audit trail functionality with Spring Security audit logging per Section 0.7.9.
     */
    @Column(name = "last_login_ts")
    private Timestamp lastLoginTs;

    /**
     * Version field for optimistic locking.
     * Automatically incremented by JPA on each update.
     * Prevents concurrent update conflicts.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
