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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration test for UserSecurityRepository using Testcontainers PostgreSQL.
 * 
 * Tests JPA repository operations for user_security table replacing VSAM USRSEC file access
 * from CSUSR01Y.cpy COBOL copybook. Validates RACF-to-Spring-Security authentication replacement.
 * 
 * Converted from COBOL VSAM I/O operations used in:
 * - COSGN00C.cbl: User signon and authentication (READ USRSEC)
 * - COUSR00C.cbl: User list display (STARTBR/READNEXT USRSEC)
 * - COUSR01C.cbl: User add function (WRITE USRSEC)
 * - COUSR02C.cbl: User update function (REWRITE USRSEC)
 * - COUSR03C.cbl: User delete function (DELETE USRSEC)
 * 
 * Test Coverage:
 * 1. CRUD operations (save, findById, findAll, update, delete)
 * 2. Custom query methods (findByUserId for authentication, findByUserType for role filtering)
 * 3. Entity field mapping from CSUSR01Y.cpy structure
 * 4. BCrypt password hashing (Section 0.7.9 security requirement)
 * 5. Primary key constraints on user_id (SEC-USR-ID PIC X(08))
 * 6. Data persistence and retrieval validation
 * 7. Optimistic locking with @Version
 * 8. Query performance validation (sub-10ms requirement per Section 0.7.7)
 * 9. User type validation (ADMIN='A', USER='U', OPERATOR='O')
 * 10. Primary key uniqueness enforcement
 * 
 * Uses Testcontainers for isolated PostgreSQL 16.6-alpine database instance
 * ensuring test isolation and accurate integration testing.
 */
@DataJpaTest(excludeAutoConfiguration = FlywayAutoConfiguration.class)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserSecurityRepositoryTest {

    /**
     * Testcontainers PostgreSQL instance for integration testing.
     * Version: 16.6-alpine (matches production PostgreSQL version per Section 0.3.5)
     * Provides isolated database instance with automatic lifecycle management.
     */
    @Container
    private static final PostgreSQLContainer<?> postgresContainer = 
        new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Configure Spring datasource properties from Testcontainers PostgreSQL.
     * Dynamically registers database connection details at runtime.
     * Sets Hibernate DDL auto to create-drop for automatic schema creation in tests.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private UserSecurityRepository userSecurityRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * BCryptPasswordEncoder for testing password hashing per Section 0.7.9.
     * Strength 10 (default) matches production configuration.
     */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // ========== CRUD Test Methods ==========

    /**
     * Test saving a new UserSecurity entity with BCrypt hashed password.
     * 
     * Validates:
     * - Entity persistence with all fields from CSUSR01Y.cpy structure
     * - BCrypt password hash storage (replacing COBOL plain-text SEC-USR-PWD)
     * - Automatic generation of created_at, updated_at timestamps
     * - Version field initialization for optimistic locking
     * 
     * Replaces COBOL operation from COUSR01C.cbl:
     * EXEC CICS WRITE FILE('USRSEC') FROM(SEC-USER-DATA) RIDFLD(SEC-USR-ID) END-EXEC.
     */
    @Test
    void testSaveUserSecurity() {
        // Arrange: Create UserSecurity with BCrypt hashed password
        String plainPassword = "Pass1234";
        String hashedPassword = passwordEncoder.encode(plainPassword);
        
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity user = UserSecurity.builder()
                .userId("USER0001")                    // SEC-USR-ID PIC X(08)
                .userFirstName("John")                  // SEC-USR-FNAME PIC X(20)
                .userLastName("Doe")                    // SEC-USR-LNAME PIC X(20)
                .userPwdHash(hashedPassword)            // SEC-USR-PWD PIC X(08) → BCrypt hash
                .userType("U")                          // SEC-USR-TYPE PIC X(01) = USER
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        // Act: Save user to database
        UserSecurity savedUser = userSecurityRepository.save(user);
        
        // Assert: Verify all fields persisted correctly
        assertNotNull(savedUser);
        assertEquals("USER0001", savedUser.getUserId());
        assertEquals("John", savedUser.getUserFirstName());
        assertEquals("Doe", savedUser.getUserLastName());
        assertEquals(hashedPassword, savedUser.getUserPwdHash());
        assertEquals("U", savedUser.getUserType());
        assertNotNull(savedUser.getCreatedAt());
        assertNotNull(savedUser.getUpdatedAt());
        assertNotNull(savedUser.getVersion());
        assertEquals(0, savedUser.getVersion());  // Initial version is 0
        
        // Verify password hash format (BCrypt starts with $2a$ or $2b$)
        assertTrue(savedUser.getUserPwdHash().startsWith("$2a$") || 
                   savedUser.getUserPwdHash().startsWith("$2b$"),
                   "Password must be BCrypt hashed");
        
        // Verify password can be validated
        assertTrue(passwordEncoder.matches(plainPassword, savedUser.getUserPwdHash()),
                   "Saved password hash must match original password");
    }

    /**
     * Test finding UserSecurity by primary key (user_id).
     * 
     * Validates:
     * - Primary key lookup functionality
     * - Field values match saved data from CSUSR01Y.cpy structure
     * - Optional.isPresent() for found record
     * 
     * Replaces COBOL operation from COSGN00C.cbl authentication:
     * EXEC CICS READ FILE('USRSEC') INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID) END-EXEC.
     * 
     * Performance requirement: Sub-10ms query time per Section 0.7.7.
     */
    @Test
    void testFindByIdUserSecurity() {
        // Arrange: Create and persist user
        String hashedPassword = passwordEncoder.encode("TestPass");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity user = UserSecurity.builder()
                .userId("ADMIN001")
                .userFirstName("Admin")
                .userLastName("User")
                .userPwdHash(hashedPassword)
                .userType("A")                          // SEC-USR-TYPE = ADMIN
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        entityManager.persistAndFlush(user);
        entityManager.clear();  // Clear persistence context to force database query
        
        // Act: Find by primary key user_id
        Optional<UserSecurity> foundUser = userSecurityRepository.findById("ADMIN001");
        
        // Assert: Verify user found and all fields match
        assertTrue(foundUser.isPresent(), "User should be found by user_id");
        assertEquals("ADMIN001", foundUser.get().getUserId());
        assertEquals("Admin", foundUser.get().getUserFirstName());
        assertEquals("User", foundUser.get().getUserLastName());
        assertEquals(hashedPassword, foundUser.get().getUserPwdHash());
        assertEquals("A", foundUser.get().getUserType());
        assertNotNull(foundUser.get().getCreatedAt());
        assertNotNull(foundUser.get().getUpdatedAt());
        assertNotNull(foundUser.get().getVersion());
    }

    /**
     * Test finding all UserSecurity entities.
     * 
     * Validates:
     * - findAll() returns all persisted users
     * - Multiple users with different user types (ADMIN, USER, OPERATOR)
     * - List size matches number of saved entities
     * 
     * Replaces COBOL operation from COUSR00C.cbl user list display:
     * EXEC CICS STARTBR FILE('USRSEC') RIDFLD(WS-USER-ID) END-EXEC.
     * PERFORM UNTIL END-OF-FILE
     *   EXEC CICS READNEXT FILE('USRSEC') INTO(SEC-USER-DATA) END-EXEC
     * END-PERFORM.
     */
    @Test
    void testFindAllUsers() {
        // Arrange: Create multiple users with different types
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity admin = UserSecurity.builder()
                .userId("ADMIN001")
                .userFirstName("Admin")
                .userLastName("User")
                .userPwdHash(passwordEncoder.encode("AdminPass"))
                .userType("A")                          // ADMIN type
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        UserSecurity user = UserSecurity.builder()
                .userId("USER0001")
                .userFirstName("Regular")
                .userLastName("User")
                .userPwdHash(passwordEncoder.encode("UserPass"))
                .userType("U")                          // USER type
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        UserSecurity operator = UserSecurity.builder()
                .userId("OPER0001")
                .userFirstName("System")
                .userLastName("Operator")
                .userPwdHash(passwordEncoder.encode("OperPass"))
                .userType("O")                          // OPERATOR type
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        entityManager.persist(admin);
        entityManager.persist(user);
        entityManager.persist(operator);
        entityManager.flush();
        entityManager.clear();
        
        // Act: Retrieve all users
        List<UserSecurity> allUsers = userSecurityRepository.findAll();
        
        // Assert: Verify all users returned
        assertNotNull(allUsers);
        assertEquals(3, allUsers.size(), "Should return all 3 saved users");
        
        // Verify user types are distinct
        List<String> userTypes = allUsers.stream()
                .map(UserSecurity::getUserType)
                .sorted()
                .toList();
        assertTrue(userTypes.contains("A"), "Should contain ADMIN user type");
        assertTrue(userTypes.contains("U"), "Should contain USER user type");
        assertTrue(userTypes.contains("O"), "Should contain OPERATOR user type");
    }

    /**
     * Test updating an existing UserSecurity entity.
     * 
     * Validates:
     * - Entity update via save() method
     * - Modified fields persisted correctly
     * - Version field incremented for optimistic locking
     * - Updated_at timestamp modified
     * 
     * Replaces COBOL operation from COUSR02C.cbl user update:
     * EXEC CICS REWRITE FILE('USRSEC') FROM(SEC-USER-DATA) END-EXEC.
     */
    @Test
    void testUpdateUserSecurity() {
        // Arrange: Create and persist user
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity user = UserSecurity.builder()
                .userId("USER0002")
                .userFirstName("Jane")
                .userLastName("Smith")
                .userPwdHash(passwordEncoder.encode("OldPass"))
                .userType("U")
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        UserSecurity savedUser = entityManager.persistAndFlush(user);
        Integer originalVersion = savedUser.getVersion();
        entityManager.clear();
        
        // Act: Update last name and password
        UserSecurity userToUpdate = userSecurityRepository.findById("USER0002").orElseThrow();
        userToUpdate.setUserLastName("Johnson");
        userToUpdate.setUserPwdHash(passwordEncoder.encode("NewPass"));
        userToUpdate.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        
        UserSecurity updatedUser = userSecurityRepository.save(userToUpdate);
        entityManager.flush();
        entityManager.clear();
        
        // Assert: Verify updates persisted
        UserSecurity verifyUser = userSecurityRepository.findById("USER0002").orElseThrow();
        assertEquals("USER0002", verifyUser.getUserId());
        assertEquals("Jane", verifyUser.getUserFirstName());
        assertEquals("Johnson", verifyUser.getUserLastName());  // Updated
        assertNotEquals(savedUser.getUserPwdHash(), verifyUser.getUserPwdHash());  // Password changed
        assertTrue(passwordEncoder.matches("NewPass", verifyUser.getUserPwdHash()));
        assertEquals("U", verifyUser.getUserType());
        assertNotNull(verifyUser.getVersion());
        assertTrue(verifyUser.getVersion() > originalVersion, 
                   "Version should be incremented after update for optimistic locking");
    }

    /**
     * Test deleting a UserSecurity entity.
     * 
     * Validates:
     * - Entity deletion via deleteById()
     * - Deleted entity no longer retrievable via findById()
     * - Optional.isEmpty() after deletion
     * 
     * Replaces COBOL operation from COUSR03C.cbl user delete:
     * EXEC CICS DELETE FILE('USRSEC') RIDFLD(WS-USER-ID) END-EXEC.
     */
    @Test
    void testDeleteUserSecurity() {
        // Arrange: Create and persist user
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity user = UserSecurity.builder()
                .userId("USER0003")
                .userFirstName("Delete")
                .userLastName("Test")
                .userPwdHash(passwordEncoder.encode("DeletePass"))
                .userType("U")
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        entityManager.persistAndFlush(user);
        entityManager.clear();
        
        // Verify user exists before deletion
        assertTrue(userSecurityRepository.findById("USER0003").isPresent());
        
        // Act: Delete user
        userSecurityRepository.deleteById("USER0003");
        entityManager.flush();
        entityManager.clear();
        
        // Assert: Verify user no longer exists
        Optional<UserSecurity> deletedUser = userSecurityRepository.findById("USER0003");
        assertFalse(deletedUser.isPresent(), "User should not exist after deletion");
    }

    /**
     * Test finding non-existent user by ID.
     * 
     * Validates:
     * - findById() returns Optional.empty() for non-existent user_id
     * - No exception thrown for not found condition
     * 
     * Replaces COBOL file-status 23 (record not found) from:
     * EXEC CICS READ FILE('USRSEC') ... RESP(WS-RESP-CD) END-EXEC.
     * IF WS-RESP-CD = DFHRESP(NOTFND) ...
     */
    @Test
    void testUserIdNotFound() {
        // Act: Attempt to find non-existent user
        Optional<UserSecurity> notFoundUser = userSecurityRepository.findById("NOTEXIST");
        
        // Assert: Verify Optional.empty() returned
        assertFalse(notFoundUser.isPresent(), 
                    "findById should return empty Optional for non-existent user_id");
    }

    /**
     * Test primary key uniqueness constraint on user_id.
     * 
     * Validates:
     * - Duplicate user_id (SEC-USR-ID) throws ConstraintViolationException
     * - Primary key constraint enforced by PostgreSQL
     * 
     * VSAM equivalent: DUPREC condition on WRITE operation
     * 
     * Note: In test context with TestEntityManager, Hibernate's ConstraintViolationException
     * is thrown directly rather than being wrapped in Spring's DataIntegrityViolationException.
     * Both exceptions indicate the same constraint violation behavior.
     */
    @Test
    void testUserIdUniqueness() {
        // Arrange: Create and persist first user
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity user1 = UserSecurity.builder()
                .userId("DUPTEST1")
                .userFirstName("First")
                .userLastName("User")
                .userPwdHash(passwordEncoder.encode("Pass1"))
                .userType("U")
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        userSecurityRepository.save(user1);
        entityManager.flush();
        entityManager.clear();
        
        // Act & Assert: Attempt to save duplicate user_id should throw exception
        UserSecurity user2 = UserSecurity.builder()
                .userId("DUPTEST1")  // Same user_id as user1
                .userFirstName("Second")
                .userLastName("User")
                .userPwdHash(passwordEncoder.encode("Pass2"))
                .userType("U")
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        // Expect ConstraintViolationException in test context with TestEntityManager
        assertThrows(ConstraintViolationException.class, () -> {
            userSecurityRepository.save(user2);
            entityManager.flush();
        }, "Duplicate user_id should throw ConstraintViolationException");
    }

    // ========== Authentication-Specific Test Methods ==========

    /**
     * Test custom query method findByUserId() for authentication.
     * 
     * Validates:
     * - Custom query method returns correct user
     * - Method functionally equivalent to findById() but explicitly named
     * - Used by CustomUserDetailsService for Spring Security authentication
     * 
     * Replaces COBOL authentication logic from COSGN00C.cbl:
     * EXEC CICS READ FILE('USRSEC') INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID) END-EXEC.
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *    IF SEC-USR-PWD = WS-PASSWORD
     *       authentication successful
     */
    @Test
    void testFindByUserId() {
        // Arrange: Create and persist user for authentication
        String hashedPassword = passwordEncoder.encode("AuthPass");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity user = UserSecurity.builder()
                .userId("AUTH0001")
                .userFirstName("Auth")
                .userLastName("User")
                .userPwdHash(hashedPassword)
                .userType("U")
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        entityManager.persistAndFlush(user);
        entityManager.clear();
        
        // Act: Find user by userId using custom query method
        Optional<UserSecurity> foundUser = userSecurityRepository.findByUserId("AUTH0001");
        
        // Assert: Verify user found for authentication
        assertTrue(foundUser.isPresent(), "findByUserId should find user for authentication");
        assertEquals("AUTH0001", foundUser.get().getUserId());
        assertEquals("Auth", foundUser.get().getUserFirstName());
        assertEquals("User", foundUser.get().getUserLastName());
        assertEquals(hashedPassword, foundUser.get().getUserPwdHash());
        assertEquals("U", foundUser.get().getUserType());
        
        // Verify password can be validated for authentication
        assertTrue(passwordEncoder.matches("AuthPass", foundUser.get().getUserPwdHash()),
                   "Password hash should be valid for authentication");
    }

    /**
     * Test BCrypt password hash storage replacing COBOL plain-text passwords.
     * 
     * Validates:
     * - COBOL SEC-USR-PWD PIC X(08) plain-text converted to BCrypt hash
     * - Password hash stored in userPwdHash field (max length 100)
     * - All passwords stored as BCrypt format (Section 0.7.9)
     * 
     * Security requirement from Section 0.7.9:
     * "COBOL plain-text passwords → BCrypt hashed passwords in PostgreSQL"
     */
    @Test
    void testPasswordHashStorage() {
        // Arrange: Create users with various passwords
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        String[] plainPasswords = {"Pass1234", "Admin999", "Test5678"};
        String[] userIds = {"HASH0001", "HASH0002", "HASH0003"};
        
        for (int i = 0; i < plainPasswords.length; i++) {
            String hashedPassword = passwordEncoder.encode(plainPasswords[i]);
            
            UserSecurity user = UserSecurity.builder()
                    .userId(userIds[i])
                    .userFirstName("User" + (i + 1))
                    .userLastName("Test")
                    .userPwdHash(hashedPassword)
                    .userType("U")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            
            entityManager.persist(user);
        }
        
        entityManager.flush();
        entityManager.clear();
        
        // Act: Retrieve all users and verify password hashes
        List<UserSecurity> users = userSecurityRepository.findAll();
        
        // Assert: Verify all passwords are BCrypt hashed
        for (int i = 0; i < users.size(); i++) {
            final int index = i;  // Make effectively final for lambda expression
            final String expectedUserId = userIds[i];
            final String originalPassword = plainPasswords[i];
            
            UserSecurity user = users.stream()
                    .filter(u -> u.getUserId().equals(expectedUserId))
                    .findFirst()
                    .orElseThrow();
            
            // Verify BCrypt format (starts with $2a$ or $2b$)
            assertTrue(user.getUserPwdHash().startsWith("$2a$") || 
                       user.getUserPwdHash().startsWith("$2b$"),
                       "Password must be stored as BCrypt hash");
            
            // Verify hash length is appropriate (BCrypt hashes are 60 characters)
            assertTrue(user.getUserPwdHash().length() >= 59 && 
                       user.getUserPwdHash().length() <= 61,
                       "BCrypt hash should be approximately 60 characters");
            
            // Verify password cannot be stored as plain text
            assertNotEquals(originalPassword, user.getUserPwdHash(),
                            "Plain-text password must never be stored");
            
            // Verify password can be validated
            assertTrue(passwordEncoder.matches(originalPassword, user.getUserPwdHash()),
                       "Hashed password must validate against original password");
        }
    }

    /**
     * Test BCrypt password hash validation for authentication.
     * 
     * Validates:
     * - Saved BCrypt hash can be validated using BCryptPasswordEncoder.matches()
     * - Correct password validates successfully
     * - Incorrect password fails validation
     * - Replaces COBOL plain-text password comparison: IF SEC-USR-PWD = WS-PASSWORD
     */
    @Test
    void testPasswordHashValidation() {
        // Arrange: Create and persist user with known password
        String correctPassword = "Correct1";
        String incorrectPassword = "Wrong999";
        String hashedPassword = passwordEncoder.encode(correctPassword);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity user = UserSecurity.builder()
                .userId("VALID001")
                .userFirstName("Valid")
                .userLastName("User")
                .userPwdHash(hashedPassword)
                .userType("U")
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        entityManager.persistAndFlush(user);
        entityManager.clear();
        
        // Act: Retrieve user and validate password
        UserSecurity foundUser = userSecurityRepository.findById("VALID001").orElseThrow();
        
        // Assert: Verify password validation
        assertTrue(passwordEncoder.matches(correctPassword, foundUser.getUserPwdHash()),
                   "Correct password should validate successfully");
        
        assertFalse(passwordEncoder.matches(incorrectPassword, foundUser.getUserPwdHash()),
                    "Incorrect password should fail validation");
    }

    /**
     * Test user type validation for RACF role mapping.
     * 
     * Validates:
     * - User type field accepts valid values: 'A' (ADMIN), 'U' (USER), 'O' (OPERATOR)
     * - User types map to Spring Security granted authorities
     * - Replaces COBOL SEC-USR-TYPE field validation
     * 
     * RACF to Spring Security role mapping (Section 0.7.9):
     * - 'A' = ROLE_ADMIN (administrator privileges)
     * - 'U' = ROLE_USER (standard user privileges)
     * - 'O' = ROLE_OPERATOR (operator privileges)
     */
    @Test
    void testUserTypeValidation() {
        // Arrange: Create users with different types
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity adminUser = UserSecurity.builder()
                .userId("TYPE0001")
                .userFirstName("Admin")
                .userLastName("Type")
                .userPwdHash(passwordEncoder.encode("AdminPass"))
                .userType("A")  // ADMIN type
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        UserSecurity regularUser = UserSecurity.builder()
                .userId("TYPE0002")
                .userFirstName("User")
                .userLastName("Type")
                .userPwdHash(passwordEncoder.encode("UserPass"))
                .userType("U")  // USER type
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        UserSecurity operatorUser = UserSecurity.builder()
                .userId("TYPE0003")
                .userFirstName("Operator")
                .userLastName("Type")
                .userPwdHash(passwordEncoder.encode("OperPass"))
                .userType("O")  // OPERATOR type
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        // Act: Save all users
        UserSecurity savedAdmin = userSecurityRepository.save(adminUser);
        UserSecurity savedUser = userSecurityRepository.save(regularUser);
        UserSecurity savedOperator = userSecurityRepository.save(operatorUser);
        
        entityManager.flush();
        entityManager.clear();
        
        // Assert: Verify all user types persisted correctly
        assertEquals("A", savedAdmin.getUserType(), "ADMIN user type should be 'A'");
        assertEquals("U", savedUser.getUserType(), "USER user type should be 'U'");
        assertEquals("O", savedOperator.getUserType(), "OPERATOR user type should be 'O'");
        
        // Verify findByUserType custom query method
        List<UserSecurity> adminUsers = userSecurityRepository.findByUserType("A");
        assertEquals(1, adminUsers.size(), "Should find 1 ADMIN user");
        assertEquals("TYPE0001", adminUsers.get(0).getUserId());
        
        List<UserSecurity> regularUsers = userSecurityRepository.findByUserType("U");
        assertEquals(1, regularUsers.size(), "Should find 1 regular USER");
        assertEquals("TYPE0002", regularUsers.get(0).getUserId());
        
        List<UserSecurity> operatorUsers = userSecurityRepository.findByUserType("O");
        assertEquals(1, operatorUsers.size(), "Should find 1 OPERATOR user");
        assertEquals("TYPE0003", operatorUsers.get(0).getUserId());
    }

    // ========== Performance Validation ==========

    /**
     * Test query performance for primary key lookups.
     * 
     * Validates:
     * - findById() queries complete in sub-10ms (Section 0.7.7 requirement)
     * - Performance matches VSAM primary key access response times
     * - B-tree index on user_id provides optimal query performance
     * 
     * Performance requirement from Section 0.7.7:
     * "Database queries MUST meet or exceed VSAM key access response times (sub-10ms for primary key lookups)"
     */
    @Test
    void testQueryPerformance() {
        // Arrange: Create and persist test user
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity user = UserSecurity.builder()
                .userId("PERF0001")
                .userFirstName("Performance")
                .userLastName("Test")
                .userPwdHash(passwordEncoder.encode("PerfPass"))
                .userType("U")
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        entityManager.persistAndFlush(user);
        entityManager.clear();
        
        // Act: Execute 100 findById queries and measure average time
        int iterations = 100;
        long totalTime = 0;
        
        for (int i = 0; i < iterations; i++) {
            long startTime = System.nanoTime();
            Optional<UserSecurity> foundUser = userSecurityRepository.findById("PERF0001");
            long endTime = System.nanoTime();
            
            assertTrue(foundUser.isPresent(), "User should be found in each iteration");
            totalTime += (endTime - startTime);
        }
        
        // Calculate average query time in milliseconds
        double averageTimeMs = (totalTime / iterations) / 1_000_000.0;
        
        // Assert: Verify query performance meets sub-10ms requirement
        assertTrue(averageTimeMs < 10.0, 
                   String.format("Average query time (%.2fms) must be under 10ms requirement. " +
                                 "Actual: %.2fms", averageTimeMs, averageTimeMs));
        
        // Log performance metrics for visibility
        System.out.printf("UserSecurity findById performance: %.2fms average over %d iterations%n",
                         averageTimeMs, iterations);
    }

    // ========== Security Tests ==========

    /**
     * Test BCrypt password hash format validation.
     * 
     * Validates:
     * - All passwords stored in BCrypt hash format
     * - Hash format starts with $2a$ or $2b$ (BCrypt identifier)
     * - No plain-text passwords stored (Section 0.7.9 security requirement)
     */
    @Test
    void testPasswordHashFormat() {
        // Arrange: Create users with BCrypt hashed passwords
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity user1 = UserSecurity.builder()
                .userId("FORMAT01")
                .userFirstName("Format")
                .userLastName("Test1")
                .userPwdHash(passwordEncoder.encode("Pass1234"))
                .userType("U")
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        UserSecurity user2 = UserSecurity.builder()
                .userId("FORMAT02")
                .userFirstName("Format")
                .userLastName("Test2")
                .userPwdHash(passwordEncoder.encode("Admin999"))
                .userType("A")
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        entityManager.persist(user1);
        entityManager.persist(user2);
        entityManager.flush();
        entityManager.clear();
        
        // Act: Retrieve all users and verify password hash formats
        List<UserSecurity> allUsers = userSecurityRepository.findAll();
        
        // Assert: Verify all passwords are in BCrypt format
        for (UserSecurity user : allUsers) {
            String passwordHash = user.getUserPwdHash();
            assertNotNull(passwordHash, "Password hash must not be null");
            assertFalse(passwordHash.isEmpty(), "Password hash must not be empty");
            
            assertTrue(passwordHash.startsWith("$2a$") || passwordHash.startsWith("$2b$"),
                       String.format("Password hash for user %s must be BCrypt format (starts with $2a$ or $2b$). " +
                                     "Actual: %s", user.getUserId(), passwordHash.substring(0, Math.min(10, passwordHash.length()))));
            
            // Verify hash length is appropriate for BCrypt (typically 60 characters)
            assertTrue(passwordHash.length() >= 59 && passwordHash.length() <= 61,
                       String.format("BCrypt hash length should be approximately 60 characters. " +
                                     "User %s has length %d", user.getUserId(), passwordHash.length()));
        }
    }

    /**
     * Test that no plain-text passwords are stored in database.
     * 
     * Validates:
     * - Plain-text COBOL passwords (SEC-USR-PWD PIC X(08)) are never stored
     * - All passwords converted to BCrypt hashes before persistence
     * - Security requirement from Section 0.7.9
     * 
     * COBOL Legacy: SEC-USR-PWD PIC X(08) stored 8-character plain-text passwords
     * Modern: userPwdHash VARCHAR(100) stores BCrypt hashed passwords only
     */
    @Test
    void testNoPlainTextPasswords() {
        // Arrange: Define plain-text passwords that should NEVER be stored
        String[] plainTextPasswords = {"Pass1234", "Admin999", "Test5678", "Simple01"};
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        // Create users with properly hashed passwords
        for (int i = 0; i < plainTextPasswords.length; i++) {
            String hashedPassword = passwordEncoder.encode(plainTextPasswords[i]);
            
            UserSecurity user = UserSecurity.builder()
                    .userId("PLAIN00" + (i + 1))
                    .userFirstName("PlainText")
                    .userLastName("Test" + (i + 1))
                    .userPwdHash(hashedPassword)
                    .userType("U")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            
            entityManager.persist(user);
        }
        
        entityManager.flush();
        entityManager.clear();
        
        // Act: Retrieve all users and verify no plain-text passwords
        List<UserSecurity> allUsers = userSecurityRepository.findAll();
        
        // Assert: Verify no plain-text passwords are stored
        for (UserSecurity user : allUsers) {
            for (String plainPassword : plainTextPasswords) {
                assertNotEquals(plainPassword, user.getUserPwdHash(),
                                String.format("Plain-text password '%s' must NEVER be stored for user %s. " +
                                              "All passwords must be BCrypt hashed.", 
                                              plainPassword, user.getUserId()));
            }
            
            // Verify password is BCrypt hashed
            assertTrue(user.getUserPwdHash().startsWith("$2a$") || 
                       user.getUserPwdHash().startsWith("$2b$"),
                       String.format("User %s password must be BCrypt hashed", user.getUserId()));
        }
    }

    /**
     * Test RACF user type to Spring Security role mapping.
     * 
     * Validates:
     * - SEC-USR-TYPE field maps correctly to Spring Security granted authorities
     * - Role mapping preserves RACF authorization semantics
     * - Custom query findByUserType supports role-based filtering
     * 
     * RACF to Spring Security Role Mapping (Section 0.7.9):
     * - SEC-USR-TYPE 'A' (ADMIN) → ROLE_ADMIN (Spring Security GrantedAuthority)
     * - SEC-USR-TYPE 'U' (USER) → ROLE_USER (Spring Security GrantedAuthority)
     * - SEC-USR-TYPE 'O' (OPERATOR) → ROLE_OPERATOR (Spring Security GrantedAuthority)
     */
    @Test
    void testUserRoleMapping() {
        // Arrange: Create users representing all RACF role types
        Timestamp now = new Timestamp(System.currentTimeMillis());
        
        UserSecurity adminUser = UserSecurity.builder()
                .userId("ROLE0001")
                .userFirstName("Admin")
                .userLastName("Role")
                .userPwdHash(passwordEncoder.encode("AdminPass"))
                .userType("A")  // Maps to ROLE_ADMIN
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        UserSecurity regularUser = UserSecurity.builder()
                .userId("ROLE0002")
                .userFirstName("User")
                .userLastName("Role")
                .userPwdHash(passwordEncoder.encode("UserPass"))
                .userType("U")  // Maps to ROLE_USER
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        UserSecurity operatorUser = UserSecurity.builder()
                .userId("ROLE0003")
                .userFirstName("Operator")
                .userLastName("Role")
                .userPwdHash(passwordEncoder.encode("OperPass"))
                .userType("O")  // Maps to ROLE_OPERATOR
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        entityManager.persist(adminUser);
        entityManager.persist(regularUser);
        entityManager.persist(operatorUser);
        entityManager.flush();
        entityManager.clear();
        
        // Act & Assert: Verify role mappings via findByUserType
        
        // Test ADMIN role mapping
        List<UserSecurity> admins = userSecurityRepository.findByUserType("A");
        assertEquals(1, admins.size(), "Should find exactly 1 ADMIN user");
        UserSecurity foundAdmin = admins.get(0);
        assertEquals("ROLE0001", foundAdmin.getUserId());
        assertEquals("A", foundAdmin.getUserType());
        // In actual Spring Security usage, this would map to ROLE_ADMIN GrantedAuthority
        
        // Test USER role mapping
        List<UserSecurity> users = userSecurityRepository.findByUserType("U");
        assertEquals(1, users.size(), "Should find exactly 1 regular USER");
        UserSecurity foundUser = users.get(0);
        assertEquals("ROLE0002", foundUser.getUserId());
        assertEquals("U", foundUser.getUserType());
        // In actual Spring Security usage, this would map to ROLE_USER GrantedAuthority
        
        // Test OPERATOR role mapping
        List<UserSecurity> operators = userSecurityRepository.findByUserType("O");
        assertEquals(1, operators.size(), "Should find exactly 1 OPERATOR user");
        UserSecurity foundOperator = operators.get(0);
        assertEquals("ROLE0003", foundOperator.getUserId());
        assertEquals("O", foundOperator.getUserType());
        // In actual Spring Security usage, this would map to ROLE_OPERATOR GrantedAuthority
        
        // Verify invalid user type returns empty list
        List<UserSecurity> invalidType = userSecurityRepository.findByUserType("X");
        assertTrue(invalidType.isEmpty(), "Invalid user type should return empty list");
    }
}
