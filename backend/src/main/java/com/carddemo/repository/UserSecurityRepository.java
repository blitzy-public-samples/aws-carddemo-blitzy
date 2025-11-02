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

import com.carddemo.entity.UserSecurity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository interface for UserSecurity entity.
 * 
 * Replaces VSAM USRSEC file operations with JPA repository pattern providing
 * CRUD operations and custom queries for user authentication and authorization.
 * 
 * COBOL File Replacement Mapping:
 * - VSAM KSDS File: USRSEC (WS-USRSEC-FILE = 'USRSEC  ')
 * - Primary Key: SEC-USR-ID PIC X(08) → userId String(8)
 * - COBOL Programs Using USRSEC:
 *   * COSGN00C.cbl (lines 211-219) - User authentication via EXEC CICS READ
 *   * COUSR00C.cbl - User management list operations
 *   * COUSR01C.cbl - User add/update operations
 * 
 * Spring Data JPA Integration:
 * - Extends JpaRepository<UserSecurity, String> providing standard CRUD methods
 * - Primary key type is String (userId) matching COBOL SEC-USR-ID PIC X(08)
 * - Custom query methods use Spring Data JPA derived query naming conventions
 * - All methods are automatically transactional with READ_COMMITTED isolation
 * 
 * Spring Security Integration:
 * - Used by CustomUserDetailsService.loadUserByUsername() for authentication
 * - findByUserId() replaces COBOL EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID)
 * - Supports JWT token-based authentication replacing CICS session management
 * 
 * Critical VSAM to JPA Transformation:
 * - VSAM Key-Sequenced Access → JPA indexed queries on userId primary key
 * - EXEC CICS READ → findByUserId(String userId)
 * - EXEC CICS WRITE → save(UserSecurity entity)
 * - EXEC CICS REWRITE → save(UserSecurity entity) with existing ID
 * - EXEC CICS DELETE → deleteById(String userId)
 * - EXEC CICS STARTBR/READNEXT → findAll() or findByUserType()
 * 
 * Service Layer Usage:
 * - AuthenticationService: User sign-on processing (COSGN00C replacement)
 * - UserManagementService: User CRUD operations (COUSR00C replacement)
 * - UserProfileService: User profile view/update (COUSR01C replacement)
 * - CustomUserDetailsService: Spring Security authentication provider
 * 
 * Security Requirements (per Section 0.9):
 * - Password field stores BCrypt hash (60 chars), not COBOL plain text (8 chars)
 * - User type 'R' maps to ROLE_USER, 'A' maps to ROLE_ADMIN + ROLE_USER
 * - All queries participate in Spring Security context for audit logging
 * 
 * Performance Considerations:
 * - Primary key lookups use B-tree index matching VSAM KSDS performance
 * - findByUserId() must return within 100ms average per Section 0.9 SLA
 * - Connection pooling via HikariCP with 20-50 connections (application.yml)
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see UserSecurity
 * @see org.springframework.data.jpa.repository.JpaRepository
 * @see org.springframework.security.core.userdetails.UserDetailsService
 */
@Repository
public interface UserSecurityRepository extends JpaRepository<UserSecurity, String> {
    
    /**
     * Finds a user by their user ID.
     * 
     * Primary authentication method replacing COBOL VSAM READ operation:
     * <pre>
     * COBOL (COSGN00C.cbl lines 211-219):
     * EXEC CICS READ
     *      DATASET   (WS-USRSEC-FILE)
     *      INTO      (SEC-USER-DATA)
     *      LENGTH    (LENGTH OF SEC-USER-DATA)
     *      RIDFLD    (WS-USER-ID)
     *      KEYLENGTH (LENGTH OF WS-USER-ID)
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC.
     * </pre>
     * 
     * CICS RESP Code Mapping:
     * - RESP=0: Record found → Optional.of(UserSecurity)
     * - RESP=13: Record not found → Optional.empty()
     * - RESP=other: Error condition → DataAccessException thrown by Spring Data JPA
     * 
     * Usage Pattern:
     * <pre>
     * Optional<UserSecurity> user = userSecurityRepository.findByUserId("USER0001");
     * if (user.isPresent()) {
     *     // COBOL: IF WS-RESP-CD = 0
     *     // Verify password using BCryptPasswordEncoder.matches()
     * } else {
     *     // COBOL: WHEN 13 (not found)
     *     throw new UserNotFoundException("User not found");
     * }
     * </pre>
     * 
     * Database Query Generated:
     * <code>SELECT * FROM user_security WHERE user_id = ?</code>
     * 
     * Performance:
     * - Uses primary key index for O(log n) lookup time
     * - Average response time: < 50ms per Section 0.9 requirements
     * - Index: CREATE UNIQUE INDEX user_security_pkey ON user_security(user_id)
     * 
     * @param userId user ID (8 characters max), maps to COBOL SEC-USR-ID PIC X(08)
     * @return Optional containing UserSecurity if found, empty otherwise
     * @see com.carddemo.service.AuthenticationService#authenticate(String, String)
     * @see com.carddemo.security.CustomUserDetailsService#loadUserByUsername(String)
     */
    Optional<UserSecurity> findByUserId(String userId);
    
    /**
     * Checks if a user exists with the given user ID.
     * 
     * Optimized existence check for user registration and validation.
     * Replaces COBOL pattern of READ followed by status check without retrieving full record.
     * 
     * Usage Pattern:
     * <pre>
     * if (userSecurityRepository.existsByUserId("USER0001")) {
     *     // User already exists - prevent duplicate registration
     *     throw new UserAlreadyExistsException("User ID already in use");
     * }
     * // Proceed with user creation
     * </pre>
     * 
     * Database Query Generated:
     * <code>SELECT COUNT(*) > 0 FROM user_security WHERE user_id = ?</code>
     * 
     * Performance Advantage:
     * - More efficient than findByUserId() when only existence check is needed
     * - Returns boolean without deserializing full UserSecurity entity
     * - Uses covering index scan without table access
     * 
     * @param userId user ID to check (8 characters max)
     * @return true if user exists, false otherwise
     * @see com.carddemo.service.UserManagementService#createUser(UserSecurity)
     */
    boolean existsByUserId(String userId);
    
    /**
     * Finds all users by user type (role).
     * 
     * Retrieves all users matching a specific role type for administrative operations.
     * Replaces COBOL sequential browse (STARTBR/READNEXT) with filtered query.
     * 
     * COBOL User Type Mapping (per Section 0.2):
     * - 'R': Regular User → Spring Security ROLE_USER
     * - 'A': Administrative User → Spring Security ROLE_USER + ROLE_ADMIN
     * 
     * Usage Pattern:
     * <pre>
     * // Get all administrative users
     * List<UserSecurity> adminUsers = userSecurityRepository.findByUserType("A");
     * 
     * // Get all regular users
     * List<UserSecurity> regularUsers = userSecurityRepository.findByUserType("R");
     * </pre>
     * 
     * Database Query Generated:
     * <code>SELECT * FROM user_security WHERE user_type = ? ORDER BY user_id</code>
     * 
     * Performance:
     * - Secondary index on user_type column recommended for production
     * - CREATE INDEX idx_user_security_user_type ON user_security(user_type)
     * - Expected result set size: < 100 users per type in typical deployment
     * 
     * @param userType user type filter: 'R' (Regular) or 'A' (Admin)
     * @return List of UserSecurity entities matching the specified type
     * @see com.carddemo.service.UserManagementService#listUsersByType(String)
     * @see com.carddemo.service.AdminService#listAdministrators()
     */
    List<UserSecurity> findByUserType(String userType);
    
    /**
     * Finds a user by user ID (overridden for clarity).
     * 
     * This method overrides JpaRepository.findById() to explicitly document
     * that the ID type is String (userId), not a generated Long identifier.
     * 
     * Functionally identical to findByUserId() but uses JPA standard method name.
     * Most code should use findByUserId() for clarity and COBOL mapping consistency.
     * 
     * @param userId user ID (8 characters max)
     * @return Optional containing UserSecurity if found, empty otherwise
     */
    @Override
    Optional<UserSecurity> findById(String userId);
    
    /**
     * Saves a user entity (insert or update).
     * 
     * Replaces COBOL EXEC CICS WRITE (new record) and EXEC CICS REWRITE (update).
     * JPA automatically determines insert vs. update based on primary key existence.
     * 
     * COBOL Operation Mapping:
     * <pre>
     * COBOL WRITE (COUSR01C.cbl - new user):
     * EXEC CICS WRITE
     *      DATASET   (WS-USRSEC-FILE)
     *      FROM      (SEC-USER-DATA)
     *      LENGTH    (LENGTH OF SEC-USER-DATA)
     *      RIDFLD    (SEC-USR-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * 
     * COBOL REWRITE (COUSR01C.cbl - update user):
     * EXEC CICS REWRITE
     *      DATASET   (WS-USRSEC-FILE)
     *      FROM      (SEC-USER-DATA)
     *      LENGTH    (LENGTH OF SEC-USER-DATA)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * </pre>
     * 
     * Usage Pattern:
     * <pre>
     * // Create new user
     * UserSecurity newUser = new UserSecurity();
     * newUser.setUserId("USER0001");
     * newUser.setFirstName("John");
     * newUser.setLastName("Doe");
     * newUser.setPassword(passwordEncoder.encode("password123")); // BCrypt hash
     * newUser.setUserType("R");
     * userSecurityRepository.save(newUser); // INSERT
     * 
     * // Update existing user
     * UserSecurity existingUser = userSecurityRepository.findByUserId("USER0001").get();
     * existingUser.setFirstName("Jane");
     * userSecurityRepository.save(existingUser); // UPDATE
     * </pre>
     * 
     * Transaction Management:
     * - Automatically wrapped in @Transactional by Spring Data JPA
     * - Isolation level: READ_COMMITTED (CICS default equivalent)
     * - Rollback on any RuntimeException or DataAccessException
     * 
     * Critical Security Note:
     * - MUST hash password using BCryptPasswordEncoder BEFORE calling save()
     * - Never pass plain text password to setPassword() method
     * - Use: user.setPassword(passwordEncoder.encode(plainPassword))
     * 
     * @param entity UserSecurity entity to save (insert or update)
     * @return saved UserSecurity entity with any generated values
     * @see com.carddemo.service.UserManagementService#createUser(UserSecurity)
     * @see com.carddemo.service.UserProfileService#updateUserProfile(UserSecurity)
     */
    @Override
    <S extends UserSecurity> S save(S entity);
    
    /**
     * Deletes a user by user ID.
     * 
     * Replaces COBOL EXEC CICS DELETE operation.
     * 
     * COBOL Operation Mapping:
     * <pre>
     * COBOL DELETE (COUSR00C.cbl):
     * EXEC CICS DELETE
     *      DATASET   (WS-USRSEC-FILE)
     *      RIDFLD    (WS-USER-ID)
     *      KEYLENGTH (LENGTH OF WS-USER-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * </pre>
     * 
     * Usage Pattern:
     * <pre>
     * // Check if user exists before deleting
     * if (userSecurityRepository.existsByUserId("USER0001")) {
     *     userSecurityRepository.deleteById("USER0001");
     * } else {
     *     throw new UserNotFoundException("User not found");
     * }
     * </pre>
     * 
     * Exception Handling:
     * - Throws EmptyResultDataAccessException if user not found
     * - Wrapped in @Transactional - rollback on exception
     * 
     * @param userId user ID of the user to delete (8 characters max)
     * @see com.carddemo.service.UserManagementService#deleteUser(String)
     */
    @Override
    void deleteById(String userId);
    
    /**
     * Checks if a user exists by user ID.
     * 
     * Overridden from JpaRepository to explicitly document String ID type.
     * Functionally identical to existsByUserId().
     * 
     * @param userId user ID to check (8 characters max)
     * @return true if user exists, false otherwise
     */
    @Override
    boolean existsById(String userId);
    
    /**
     * Retrieves all users from the database.
     * 
     * Replaces COBOL sequential browse operation (STARTBR/READNEXT loop).
     * Use with caution in production - consider pagination for large datasets.
     * 
     * COBOL Operation Mapping:
     * <pre>
     * COBOL Browse (COUSR00C.cbl):
     * EXEC CICS STARTBR
     *      DATASET   (WS-USRSEC-FILE)
     *      RIDFLD    (WS-USER-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * 
     * PERFORM UNTIL USER-SEC-EOF
     *      EXEC CICS READNEXT
     *           DATASET (WS-USRSEC-FILE)
     *           INTO    (SEC-USER-DATA)
     *           RESP    (WS-RESP-CD)
     *      END-EXEC
     *      ... process record ...
     * END-PERFORM.
     * </pre>
     * 
     * Usage Pattern:
     * <pre>
     * List<UserSecurity> allUsers = userSecurityRepository.findAll();
     * // Process all users
     * </pre>
     * 
     * Performance Warning:
     * - Loads all records into memory - not suitable for large user tables
     * - For paginated access, use: findAll(Pageable pageable)
     * - Expected maximum: < 500 users in typical CardDemo deployment
     * 
     * @return List of all UserSecurity entities in the database
     * @see com.carddemo.service.UserManagementService#listAllUsers()
     */
    @Override
    List<UserSecurity> findAll();
    
    /**
     * Counts the total number of users in the database.
     * 
     * Utility method for administrative dashboards and reporting.
     * 
     * Database Query Generated:
     * <code>SELECT COUNT(*) FROM user_security</code>
     * 
     * @return total number of user records
     * @see com.carddemo.service.AdminService#getUserStatistics()
     */
    @Override
    long count();
}
