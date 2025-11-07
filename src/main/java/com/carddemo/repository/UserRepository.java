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

import com.carddemo.entity.User;
import com.carddemo.entity.User.UserType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for User Entity
 * 
 * Replaces COBOL VSAM file I/O operations on USRSEC security file from CSUSR01Y.cpy.
 * Provides CRUD operations and custom query methods for user authentication and management.
 * 
 * <p>COBOL to Spring Data JPA Transformation Mapping:</p>
 * <pre>
 * COBOL Operation                          Spring Data JPA Method
 * -------------------------------------------------------------------------
 * EXEC CICS READ DATASET('USRSEC')    ->  findByUserId(userId)
 *   RIDFLD(SEC-USR-ID)
 *   RESP(ws-resp-cd)
 *   [RESP=0: found, RESP=13: not found]
 * 
 * EXEC CICS WRITE DATASET('USRSEC')   ->  save(user)
 *   FROM(SEC-USER-DATA)
 * 
 * EXEC CICS REWRITE DATASET('USRSEC') ->  save(user) [update]
 *   FROM(SEC-USER-DATA)
 * 
 * EXEC CICS DELETE DATASET('USRSEC')  ->  deleteById(userId) or delete(user)
 *   RIDFLD(SEC-USR-ID)
 * 
 * EXEC CICS STARTBR DATASET('USRSEC') ->  findAll() or findByUserType(userType)
 * EXEC CICS READNEXT ...
 * EXEC CICS ENDBR ...
 * </pre>
 * 
 * <p>Key Features:</p>
 * <ul>
 *   <li>Extends JpaRepository providing automatic CRUD operations</li>
 *   <li>Custom query methods derived from method names (no SQL required)</li>
 *   <li>Optional return types for null-safe lookups (replacing RESP code 13 checks)</li>
 *   <li>Integration with Spring Security for RACF replacement</li>
 *   <li>Support for soft delete pattern with deleted flag filtering</li>
 * </ul>
 * 
 * <p>Usage by COBOL Programs:</p>
 * <ul>
 *   <li>COSGN00C (Authentication): findByUserId for login validation</li>
 *   <li>COUSR00C (User List): findAll, findByUserType for browsing users</li>
 *   <li>COUSR01C (User Create): save for new user insertion</li>
 *   <li>COUSR02C (User Update): save for user modification</li>
 *   <li>COUSR03C (User Delete): deleteById for user removal</li>
 * </ul>
 * 
 * <p>Performance Considerations:</p>
 * <ul>
 *   <li>userId field is indexed as primary key (equivalent to VSAM KSDS key)</li>
 *   <li>Query methods use indexes defined in database migration scripts</li>
 *   <li>Optimistic locking via @Version prevents concurrent update conflicts</li>
 * </ul>
 * 
 * @see User
 * @see com.carddemo.service.auth.AuthenticationService
 * @see com.carddemo.security.CustomUserDetailsService
 */
@Repository
public interface UserRepository extends JpaRepository<User, String> {

    /**
     * Find user by user ID (primary key lookup)
     * 
     * <p>Replaces COBOL operation:</p>
     * <pre>
     * EXEC CICS READ
     *   DATASET('USRSEC')
     *   INTO(SEC-USER-DATA)
     *   RIDFLD(WS-USER-ID)
     *   RESP(WS-RESP-CD)
     * END-EXEC
     * 
     * EVALUATE WS-RESP-CD
     *   WHEN 0        -> Optional.of(user)
     *   WHEN 13       -> Optional.empty()
     * END-EVALUATE
     * </pre>
     * 
     * <p>Primary use case is user authentication in AuthenticationService:</p>
     * <ul>
     *   <li>User enters userId and password on signon screen (COSGN00C)</li>
     *   <li>AuthenticationService calls findByUserId(userId)</li>
     *   <li>If user exists, validate password against BCrypt hash</li>
     *   <li>If user not found, return authentication error</li>
     * </ul>
     * 
     * <p>This method is critical for:</p>
     * <ul>
     *   <li>Login authentication flow (COSGN00C -> AuthenticationService)</li>
     *   <li>Spring Security UserDetailsService integration</li>
     *   <li>JWT token generation after successful authentication</li>
     * </ul>
     * 
     * @param userId User identifier, max 8 characters (matches SEC-USR-ID PIC X(08))
     * @return Optional containing User if found, empty Optional if not found
     *         Replaces RESP code 13 "user not found" with null-safe Optional pattern
     */
    Optional<User> findByUserId(String userId);

    /**
     * Check if user exists by user ID
     * 
     * <p>More efficient than findByUserId when only existence check is needed.
     * Generates SQL: SELECT COUNT(user_id) > 0 FROM app_user WHERE user_id = ?</p>
     * 
     * <p>Replaces COBOL pattern:</p>
     * <pre>
     * EXEC CICS READ
     *   DATASET('USRSEC')
     *   RIDFLD(WS-USER-ID)
     *   RESP(WS-RESP-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD = 0
     *   MOVE 'Y' TO USER-EXISTS-FLAG
     * ELSE
     *   MOVE 'N' TO USER-EXISTS-FLAG
     * END-IF
     * </pre>
     * 
     * <p>Use cases:</p>
     * <ul>
     *   <li>User creation validation - prevent duplicate user IDs (COUSR01C)</li>
     *   <li>Form validation in UserCreateService</li>
     *   <li>Pre-delete existence check in UserDeleteService</li>
     * </ul>
     * 
     * @param userId User identifier to check
     * @return true if user with given userId exists, false otherwise
     */
    boolean existsByUserId(String userId);

    /**
     * Find all users by user type
     * 
     * <p>Replaces COBOL browse operation with type filtering:</p>
     * <pre>
     * EXEC CICS STARTBR
     *   DATASET('USRSEC')
     * END-EXEC
     * 
     * PERFORM UNTIL USER-SEC-EOF
     *   EXEC CICS READNEXT
     *     DATASET('USRSEC')
     *     INTO(SEC-USER-DATA)
     *   END-EXEC
     *   
     *   IF SEC-USR-TYPE = 'A'  [or 'U']
     *     [process admin/user record]
     *   END-IF
     * END-PERFORM
     * 
     * EXEC CICS ENDBR
     *   DATASET('USRSEC')
     * END-EXEC
     * </pre>
     * 
     * <p>Used for:</p>
     * <ul>
     *   <li>Admin user listing in UserListService (COUSR00C)</li>
     *   <li>Regular user listing with role-based filtering</li>
     *   <li>User management screen population</li>
     * </ul>
     * 
     * <p>Performance Note: For large user bases, consider using pagination
     * with Page<User> findByUserType(UserType userType, Pageable pageable)</p>
     * 
     * @param userType User type to filter by (ADMIN or USER)
     * @return List of all users with the specified type
     */
    List<User> findByUserType(UserType userType);

    /**
     * Count users by user type
     * 
     * <p>Provides statistics for admin dashboard and reporting.
     * More efficient than retrieving full user list when only count is needed.</p>
     * 
     * <p>Replaces COBOL counting logic:</p>
     * <pre>
     * MOVE ZERO TO WS-ADMIN-COUNT
     * MOVE ZERO TO WS-USER-COUNT
     * 
     * EXEC CICS STARTBR DATASET('USRSEC') END-EXEC
     * 
     * PERFORM UNTIL USER-SEC-EOF
     *   EXEC CICS READNEXT
     *     DATASET('USRSEC')
     *     INTO(SEC-USER-DATA)
     *   END-EXEC
     *   
     *   IF SEC-USR-TYPE = 'A'
     *     ADD 1 TO WS-ADMIN-COUNT
     *   ELSE
     *     ADD 1 TO WS-USER-COUNT
     *   END-IF
     * END-PERFORM
     * 
     * EXEC CICS ENDBR DATASET('USRSEC') END-EXEC
     * </pre>
     * 
     * @param userType User type to count (ADMIN or USER)
     * @return Count of users with specified type
     */
    long countByUserType(UserType userType);

    /**
     * Count active (non-deleted) users by user type
     * 
     * <p>Supports soft delete pattern where deleted users remain in database
     * but are excluded from normal queries. This method counts only active users
     * of a specific type.</p>
     * 
     * <p>Replaces COBOL counting with delete flag check:</p>
     * <pre>
     * MOVE ZERO TO WS-ACTIVE-ADMIN-COUNT
     * 
     * EXEC CICS STARTBR DATASET('USRSEC') END-EXEC
     * 
     * PERFORM UNTIL USER-SEC-EOF
     *   EXEC CICS READNEXT
     *     DATASET('USRSEC')
     *     INTO(SEC-USER-DATA)
     *   END-EXEC
     *   
     *   IF SEC-USR-TYPE = 'A' AND SEC-USR-DELETED-FLAG = 'N'
     *     ADD 1 TO WS-ACTIVE-ADMIN-COUNT
     *   END-IF
     * END-PERFORM
     * 
     * EXEC CICS ENDBR DATASET('USRSEC') END-EXEC
     * </pre>
     * 
     * <p>Use cases:</p>
     * <ul>
     *   <li>Admin dashboard statistics showing active users only</li>
     *   <li>License compliance reporting (count active licenses)</li>
     *   <li>User management reports excluding deleted users</li>
     * </ul>
     * 
     * <p>Comparison with countByUserType:</p>
     * <ul>
     *   <li>countByUserType(ADMIN) - includes deleted admin users</li>
     *   <li>countByUserTypeAndDeletedFalse(ADMIN) - excludes deleted admin users</li>
     * </ul>
     * 
     * @param userType User type to count (ADMIN or USER)
     * @return Count of active (non-deleted) users with specified type
     */
    long countByUserTypeAndDeletedFalse(UserType userType);

    /**
     * Find all active (non-deleted) users
     * 
     * <p>Returns only users where deleted flag is false.
     * Supports soft delete pattern for audit trail and compliance.</p>
     * 
     * <p>Use cases:</p>
     * <ul>
     *   <li>User listing screens showing only active users (COUSR00C)</li>
     *   <li>User selection dropdowns</li>
     *   <li>Active user reports</li>
     * </ul>
     * 
     * @return List of all active users
     */
    List<User> findByDeletedFalse();

    /**
     * Find all active users by user type
     * 
     * <p>Combines user type filtering with soft delete exclusion.
     * Returns only active users of the specified type.</p>
     * 
     * <p>Most common query for user management screens:</p>
     * <ul>
     *   <li>Admin screen: findByUserTypeAndDeletedFalse(ADMIN)</li>
     *   <li>Regular user screen: findByUserTypeAndDeletedFalse(USER)</li>
     * </ul>
     * 
     * @param userType User type to filter by
     * @return List of active users with specified type
     */
    List<User> findByUserTypeAndDeletedFalse(UserType userType);
}
