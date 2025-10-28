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

package com.carddemo.repository;

import com.carddemo.model.entity.UserSecurity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for UserSecurity entity.
 * 
 * Converted from VSAM USRSEC file I/O operations used in COBOL programs.
 * Original function: User security file access for RACF authentication and user management
 * 
 * COBOL Programs Replaced:
 * - COSGN00C.cbl: User signon and authentication (EXEC CICS READ FILE('USRSEC'))
 * - COUSR00C.cbl: User list display (EXEC CICS STARTBR/READNEXT FILE('USRSEC'))
 * - COUSR01C.cbl: User add function (EXEC CICS WRITE FILE('USRSEC'))
 * - COUSR02C.cbl: User update function (EXEC CICS REWRITE FILE('USRSEC'))
 * - COUSR03C.cbl: User delete function (EXEC CICS DELETE FILE('USRSEC'))
 * 
 * COBOL Copybook: CSUSR01Y.cpy (SEC-USER-DATA structure)
 * - SEC-USR-ID PIC X(08) → String userId (primary key)
 * - SEC-USR-FNAME PIC X(20) → String userFirstName
 * - SEC-USR-LNAME PIC X(20) → String userLastName
 * - SEC-USR-PWD PIC X(08) → String userPwdHash (BCrypt hashed)
 * - SEC-USR-TYPE PIC X(01) → String userType (A=Admin, U=User, O=Operator)
 * 
 * VSAM to PostgreSQL Migration:
 * - VSAM KSDS file "USRSEC" → PostgreSQL table "user_security"
 * - Primary key: SEC-USR-ID → user_id column with B-tree index
 * - Alternate index on user_type for role-based queries
 * 
 * COBOL VSAM I/O Operation Mappings:
 * - EXEC CICS READ FILE('USRSEC') RIDFLD(user-id) → findById(String userId)
 * - EXEC CICS WRITE FILE('USRSEC') FROM(sec-user-data) → save(UserSecurity)
 * - EXEC CICS REWRITE FILE('USRSEC') FROM(sec-user-data) → save(UserSecurity)
 * - EXEC CICS DELETE FILE('USRSEC') RIDFLD(user-id) → deleteById(String userId)
 * - EXEC CICS STARTBR/READNEXT FILE('USRSEC') → findAll() or findByUserType()
 * 
 * Security Migration (Section 0.7.9):
 * - RACF security profiles → Spring Security UserDetails loaded via CustomUserDetailsService
 * - RACF user authentication → Spring Security JWT-based authentication
 * - RACF roles → Spring Security granted authorities (ROLE_ADMIN, ROLE_USER, ROLE_OPERATOR)
 * - COBOL plain-text passwords → BCrypt hashed passwords with strength 10
 * - RACF audit trail → Spring Security audit logging (createdAt, updatedAt, lastLoginTs)
 * 
 * Performance Requirements (Section 0.7.7):
 * - All findById queries MUST complete in sub-10ms (matching VSAM primary key access)
 * - Leverages PostgreSQL B-tree index on user_id column for optimal query performance
 * - User authentication queries (findByUserId) MUST support sub-200ms transaction response times
 * 
 * Critical Note:
 * This repository is called by CustomUserDetailsService during Spring Security authentication.
 * The findByUserId method is essential for user login operations, replacing COBOL COSGN00C
 * authentication logic that read USRSEC file with user ID as the primary key.
 * 
 * All passwords stored in userPwdHash field are BCrypt-hashed (never plain-text).
 * Password validation is performed by Spring Security PasswordEncoder, not in repository layer.
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {

    /**
     * Find user security record by user ID (primary key lookup).
     * 
     * Replaces COBOL operation from COSGN00C.cbl:
     * <pre>
     * EXEC CICS READ
     *      DATASET   ('USRSEC')
     *      INTO      (SEC-USER-DATA)
     *      RIDFLD    (WS-USER-ID)
     *      KEYLENGTH (LENGTH OF WS-USER-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * </pre>
     * 
     * This method is functionally equivalent to the inherited findById method,
     * but is explicitly named for clarity in authentication service contexts.
     * 
     * Critical Usage:
     * - Called by CustomUserDetailsService.loadUserByUsername() for Spring Security authentication
     * - Called by AuthService for user signon validation
     * - Replaces RACF security profile lookup with PostgreSQL query
     * 
     * Performance:
     * - Executes in sub-10ms via B-tree index on user_id column
     * - Single SELECT query: SELECT * FROM user_security WHERE user_id = ?
     * 
     * @param userId User ID (8 characters max, uppercase) - primary key from SEC-USR-ID
     * @return Optional containing UserSecurity if found, empty Optional if not found
     *         Replaces COBOL RESP=0 (found) or RESP=13 (not found) response codes
     */
    Optional<UserSecurity> findByUserId(String userId);

    /**
     * Find all user security records by user type for role-based filtering.
     * 
     * Replaces COBOL browse operation from COUSR00C.cbl user list display:
     * <pre>
     * EXEC CICS STARTBR
     *      DATASET   ('USRSEC')
     *      RIDFLD    (WS-USER-ID)
     * END-EXEC.
     * 
     * PERFORM UNTIL END-OF-FILE
     *   EXEC CICS READNEXT
     *        DATASET   ('USRSEC')
     *        INTO      (SEC-USER-DATA)
     *        RIDFLD    (WS-USER-ID)
     *   END-EXEC
     *   
     *   IF SEC-USR-TYPE = requested-type
     *      display user record
     *   END-IF
     * END-PERFORM.
     * </pre>
     * 
     * Critical Usage:
     * - Called by UserController.getUsersByType() for user management screens
     * - Filters users by role: 'A' (Admin), 'U' (User), 'O' (Operator)
     * - Replaces VSAM browse-and-filter pattern with direct SQL WHERE clause
     * 
     * Performance:
     * - Leverages B-tree index on user_type column (idx_user_type)
     * - Query: SELECT * FROM user_security WHERE user_type = ?
     * - Returns all matching records in single query (no pagination needed for small dataset)
     * 
     * Example user type values from COBOL:
     * - 'A' = Administrator users (CDEMO-USRTYP-ADMIN in COCOM01Y.cpy)
     * - 'U' = Regular users (CDEMO-USRTYP-USER in COCOM01Y.cpy)
     * - 'O' = Operator users (system operators)
     * 
     * @param userType Single character user type code ('A', 'U', or 'O')
     * @return List of UserSecurity entities matching the specified user type
     *         Empty list if no users found for the type
     */
    List<UserSecurity> findByUserType(String userType);
}
