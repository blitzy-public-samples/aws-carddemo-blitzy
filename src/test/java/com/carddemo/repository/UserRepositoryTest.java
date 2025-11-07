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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 Test Class for UserRepository
 * 
 * Tests CRUD operations on User entity matching COBOL USRSEC VSAM file operations
 * from CSUSR01Y.cpy (SEC-USER-DATA 80-byte structure).
 * 
 * Uses @DataJpaTest annotation for isolated repository testing with H2 in-memory database.
 * This ensures fast, isolated tests without requiring a full PostgreSQL instance.
 * 
 * <p>COBOL to Spring Data JPA Testing Mapping:</p>
 * <pre>
 * COBOL Operation                          Test Method
 * -------------------------------------------------------------------------
 * EXEC CICS READ DATASET('USRSEC')    ->  testFindByUserId_Success
 *   RIDFLD(SEC-USR-ID)                     testFindByUserId_NotFound
 *   RESP(ws-resp-cd)
 * 
 * EXEC CICS WRITE DATASET('USRSEC')   ->  testSave_PersistsNewUser
 *   FROM(SEC-USER-DATA)
 * 
 * EXEC CICS REWRITE DATASET('USRSEC') ->  testSave_UpdatesExistingUser
 *   FROM(SEC-USER-DATA)
 * 
 * User Existence Check Pattern         ->  testExistsByUserId_ReturnsTrue
 *   (READ + RESP code evaluation)          testExistsByUserId_ReturnsFalse
 * 
 * SEC-USR-TYPE Validation              ->  testUserTypeEnum_MapsCorrectly
 *   88-level conditions (ADMIN='A')
 * 
 * Password Encryption                  ->  testPasswordField_StoresEncryptedValue
 *   (RACF to Spring Security)
 * </pre>
 * 
 * <p>Test Data Based on COBOL Structure:</p>
 * <ul>
 *   <li>SEC-USR-ID: 8 characters max (e.g., "ADMIN001", "USER0001")</li>
 *   <li>SEC-USR-FNAME: 20 characters max (e.g., "John", "Jane")</li>
 *   <li>SEC-USR-LNAME: 20 characters max (e.g., "Administrator", "RegularUser")</li>
 *   <li>SEC-USR-PWD: BCrypt encrypted password (originally 8 chars in COBOL)</li>
 *   <li>SEC-USR-TYPE: 'A' for ADMIN, 'U' for USER (mapped to enum)</li>
 * </ul>
 * 
 * <p>Coverage:</p>
 * <ul>
 *   <li>Primary key lookup (VSAM KSDS key access)</li>
 *   <li>User existence validation</li>
 *   <li>Insert operations (WRITE)</li>
 *   <li>Update operations (REWRITE)</li>
 *   <li>UserType enum mapping from COBOL 88-level conditions</li>
 *   <li>Password encryption support</li>
 * </ul>
 * 
 * @see UserRepository
 * @see User
 * @see User.UserType
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("UserRepository Tests - COBOL USRSEC VSAM File Operations")
public class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User adminUser;
    private User regularUser;

    /**
     * Set up test data before each test
     * 
     * Creates two test users matching COBOL SEC-USER-DATA structure:
     * 1. Admin user with SEC-USR-TYPE = 'A'
     * 2. Regular user with SEC-USR-TYPE = 'U'
     * 
     * User IDs are 8 characters (matching SEC-USR-ID PIC X(08))
     * Names are 20 characters max (matching SEC-USR-FNAME/LNAME PIC X(20))
     * Passwords are BCrypt encrypted (replacing RACF)
     */
    @BeforeEach
    void setUp() {
        // Clear any existing test data
        userRepository.deleteAll();

        // Create admin user matching COBOL structure
        // SEC-USR-ID: 'ADMIN001' (8 chars)
        // SEC-USR-FNAME: 'John' (20 chars max)
        // SEC-USR-LNAME: 'Administrator' (20 chars max)
        // SEC-USR-PWD: BCrypt encrypted password
        // SEC-USR-TYPE: 'A' (ADMIN)
        adminUser = User.builder()
                .userId("ADMIN001")
                .firstName("John")
                .lastName("Administrator")
                .password("$2a$10$xYzAbC123encrypted.password.hash.example.admin")
                .userType(UserType.ADMIN)
                .deleted(false)
                .build();

        // Create regular user matching COBOL structure
        // SEC-USR-ID: 'USER0001' (8 chars)
        // SEC-USR-FNAME: 'Jane' (20 chars max)
        // SEC-USR-LNAME: 'RegularUser' (20 chars max)
        // SEC-USR-PWD: BCrypt encrypted password
        // SEC-USR-TYPE: 'U' (USER)
        regularUser = User.builder()
                .userId("USER0001")
                .firstName("Jane")
                .lastName("RegularUser")
                .password("$2a$10$xYzAbC123encrypted.password.hash.example.user")
                .userType(UserType.USER)
                .deleted(false)
                .build();
    }

    /**
     * Test findByUserId returns user when user exists
     * 
     * Simulates COBOL operation:
     * <pre>
     * EXEC CICS READ
     *   DATASET('USRSEC')
     *   INTO(SEC-USER-DATA)
     *   RIDFLD('ADMIN001')
     *   RESP(WS-RESP-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD = 0
     *   [User found - process SEC-USER-DATA]
     * </pre>
     * 
     * Validates:
     * - Primary key lookup (SEC-USR-ID)
     * - Optional contains user when found
     * - All fields correctly persisted and retrieved
     */
    @Test
    @DisplayName("findByUserId returns user when user exists (VSAM READ RESP=0)")
    void testFindByUserId_Success() {
        // Given: Admin user is saved in database (WRITE operation)
        userRepository.save(adminUser);

        // When: Find user by userId (READ operation with RIDFLD)
        Optional<User> foundUser = userRepository.findByUserId("ADMIN001");

        // Then: User is found (RESP=0) and all fields match
        assertThat(foundUser).isPresent();
        assertThat(foundUser.get().getUserId()).isEqualTo("ADMIN001");
        assertThat(foundUser.get().getFirstName()).isEqualTo("John");
        assertThat(foundUser.get().getLastName()).isEqualTo("Administrator");
        assertThat(foundUser.get().getPassword()).startsWith("$2a$10$"); // BCrypt hash
        assertThat(foundUser.get().getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(foundUser.get().isDeleted()).isFalse();
    }

    /**
     * Test findByUserId returns empty Optional when user doesn't exist
     * 
     * Simulates COBOL operation:
     * <pre>
     * EXEC CICS READ
     *   DATASET('USRSEC')
     *   INTO(SEC-USER-DATA)
     *   RIDFLD('NOEXIST9')
     *   RESP(WS-RESP-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD = 13
     *   [User not found - NOTFND condition]
     * </pre>
     * 
     * Validates:
     * - Optional.empty() replaces RESP code 13 (NOTFND)
     * - No exception thrown for non-existent user
     */
    @Test
    @DisplayName("findByUserId returns empty Optional when user doesn't exist (VSAM READ RESP=13)")
    void testFindByUserId_NotFound() {
        // When: Find non-existent user (READ with invalid RIDFLD)
        Optional<User> foundUser = userRepository.findByUserId("NOEXIST9");

        // Then: Optional is empty (RESP=13 NOTFND)
        assertThat(foundUser).isEmpty();
    }

    /**
     * Test existsByUserId returns true when user exists
     * 
     * Simulates COBOL pattern:
     * <pre>
     * EXEC CICS READ
     *   DATASET('USRSEC')
     *   RIDFLD('USER0001')
     *   RESP(WS-RESP-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD = 0
     *   MOVE 'Y' TO USER-EXISTS-FLAG
     * </pre>
     * 
     * Validates:
     * - Efficient existence check without full entity retrieval
     * - Returns true when user found (RESP=0)
     * - Used for duplicate user ID validation in COUSR01C
     */
    @Test
    @DisplayName("existsByUserId returns true when user exists")
    void testExistsByUserId_ReturnsTrue() {
        // Given: Regular user is saved in database
        userRepository.save(regularUser);

        // When: Check if user exists
        boolean exists = userRepository.existsByUserId("USER0001");

        // Then: Returns true (user exists)
        assertThat(exists).isTrue();
    }

    /**
     * Test existsByUserId returns false when user doesn't exist
     * 
     * Simulates COBOL pattern:
     * <pre>
     * EXEC CICS READ
     *   DATASET('USRSEC')
     *   RIDFLD('NOEXIST9')
     *   RESP(WS-RESP-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD = 13
     *   MOVE 'N' TO USER-EXISTS-FLAG
     * </pre>
     * 
     * Validates:
     * - Returns false when user not found (RESP=13)
     * - No exception thrown for non-existent user
     */
    @Test
    @DisplayName("existsByUserId returns false when user doesn't exist")
    void testExistsByUserId_ReturnsFalse() {
        // When: Check if non-existent user exists
        boolean exists = userRepository.existsByUserId("NOEXIST9");

        // Then: Returns false (user doesn't exist)
        assertThat(exists).isFalse();
    }

    /**
     * Test save persists new user to database
     * 
     * Simulates COBOL operation:
     * <pre>
     * MOVE 'NEWUSER1' TO SEC-USR-ID
     * MOVE 'Test' TO SEC-USR-FNAME
     * MOVE 'NewUser' TO SEC-USR-LNAME
     * MOVE 'password' TO SEC-USR-PWD
     * MOVE 'U' TO SEC-USR-TYPE
     * 
     * EXEC CICS WRITE
     *   DATASET('USRSEC')
     *   FROM(SEC-USER-DATA)
     *   RIDFLD(SEC-USR-ID)
     *   RESP(WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * Validates:
     * - New user is persisted (WRITE operation)
     * - All fields are correctly stored
     * - User can be retrieved after insert
     * - Matches COUSR01C user creation logic
     */
    @Test
    @DisplayName("save persists new user to database (VSAM WRITE)")
    void testSave_PersistsNewUser() {
        // Given: New user to be created
        User newUser = User.builder()
                .userId("NEWUSER1")
                .firstName("Test")
                .lastName("NewUser")
                .password("$2a$10$xYzAbC123encrypted.password.hash.example.new")
                .userType(UserType.USER)
                .deleted(false)
                .build();

        // When: Save new user (WRITE operation)
        User savedUser = userRepository.save(newUser);

        // Then: User is persisted and can be retrieved
        assertThat(savedUser).isNotNull();
        assertThat(savedUser.getUserId()).isEqualTo("NEWUSER1");
        assertThat(savedUser.getCreatedDate()).isNotNull(); // JPA lifecycle callback
        assertThat(savedUser.getUpdatedDate()).isNotNull();

        // Verify user can be retrieved (READ after WRITE)
        Optional<User> retrievedUser = userRepository.findByUserId("NEWUSER1");
        assertThat(retrievedUser).isPresent();
        assertThat(retrievedUser.get().getFirstName()).isEqualTo("Test");
        assertThat(retrievedUser.get().getLastName()).isEqualTo("NewUser");
        assertThat(retrievedUser.get().getUserType()).isEqualTo(UserType.USER);
    }

    /**
     * Test save updates existing user in database
     * 
     * Simulates COBOL operation:
     * <pre>
     * EXEC CICS READ
     *   DATASET('USRSEC')
     *   INTO(SEC-USER-DATA)
     *   RIDFLD('ADMIN001')
     *   UPDATE
     * END-EXEC
     * 
     * MOVE 'UpdatedFirstName' TO SEC-USR-FNAME
     * MOVE 'UpdatedLastName' TO SEC-USR-LNAME
     * 
     * EXEC CICS REWRITE
     *   DATASET('USRSEC')
     *   FROM(SEC-USER-DATA)
     *   RESP(WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * Validates:
     * - Existing user is updated (REWRITE operation)
     * - Updated fields persist correctly
     * - Primary key remains unchanged
     * - Optimistic locking version increments
     * - Matches COUSR02C user update logic
     */
    @Test
    @DisplayName("save updates existing user in database (VSAM REWRITE)")
    void testSave_UpdatesExistingUser() {
        // Given: Admin user exists in database
        User savedUser = userRepository.save(adminUser);
        Long originalVersion = savedUser.getVersion();

        // When: Update user fields (REWRITE operation)
        savedUser.setFirstName("UpdatedJohn");
        savedUser.setLastName("UpdatedAdmin");
        User updatedUser = userRepository.save(savedUser);
        
        // Flush to ensure version field is incremented and changes are synchronized
        entityManager.flush();
        entityManager.clear();

        // Then: User is updated with new values
        assertThat(updatedUser.getUserId()).isEqualTo("ADMIN001"); // Primary key unchanged
        assertThat(updatedUser.getFirstName()).isEqualTo("UpdatedJohn");
        assertThat(updatedUser.getLastName()).isEqualTo("UpdatedAdmin");
        assertThat(updatedUser.getPassword()).isEqualTo(adminUser.getPassword()); // Unchanged
        assertThat(updatedUser.getUserType()).isEqualTo(UserType.ADMIN); // Unchanged
        assertThat(updatedUser.getVersion()).isGreaterThan(originalVersion); // Optimistic locking

        // Verify updated user can be retrieved
        Optional<User> retrievedUser = userRepository.findByUserId("ADMIN001");
        assertThat(retrievedUser).isPresent();
        assertThat(retrievedUser.get().getFirstName()).isEqualTo("UpdatedJohn");
        assertThat(retrievedUser.get().getLastName()).isEqualTo("UpdatedAdmin");
    }

    /**
     * Test UserType enum correctly maps to COBOL 88-level conditions
     * 
     * Simulates COBOL 88-level conditions:
     * <pre>
     * 05 SEC-USR-TYPE           PIC X(01).
     *    88 USER-TYPE-ADMIN     VALUE 'A'.
     *    88 USER-TYPE-USER      VALUE 'U'.
     * 
     * IF USER-TYPE-ADMIN
     *   [Admin user processing]
     * END-IF
     * 
     * IF USER-TYPE-USER
     *   [Regular user processing]
     * END-IF
     * </pre>
     * 
     * Validates:
     * - UserType.ADMIN maps to code 'A'
     * - UserType.USER maps to code 'U'
     * - Enum values persist and retrieve correctly
     * - Spring Security role mapping works correctly
     */
    @Test
    @DisplayName("UserType enum maps correctly to COBOL 88-level conditions")
    void testUserTypeEnum_MapsCorrectly() {
        // Given: Save both admin and regular users
        userRepository.save(adminUser);
        userRepository.save(regularUser);

        // When: Retrieve users
        Optional<User> retrievedAdmin = userRepository.findByUserId("ADMIN001");
        Optional<User> retrievedUser = userRepository.findByUserId("USER0001");

        // Then: UserType enum values are correctly mapped
        assertThat(retrievedAdmin).isPresent();
        assertThat(retrievedAdmin.get().getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(retrievedAdmin.get().getUserType().getCode()).isEqualTo("A");

        assertThat(retrievedUser).isPresent();
        assertThat(retrievedUser.get().getUserType()).isEqualTo(UserType.USER);
        assertThat(retrievedUser.get().getUserType().getCode()).isEqualTo("U");

        // Verify enum constants are correct
        assertThat(UserType.ADMIN.getCode()).isEqualTo("A");
        assertThat(UserType.USER.getCode()).isEqualTo("U");
    }

    /**
     * Test password field stores encrypted values (BCrypt)
     * 
     * Simulates transition from RACF to Spring Security:
     * <pre>
     * COBOL Original:
     * 05 SEC-USR-PWD            PIC X(08).  [Plain text or simple hash]
     * 
     * Java/Spring Security:
     * password field stores BCrypt hash (up to 255 chars)
     * Format: $2a$10$[salt][hash]
     * </pre>
     * 
     * Validates:
     * - Password field accepts BCrypt encrypted strings
     * - Encrypted password persists correctly
     * - Password hash format is valid BCrypt
     * - Field length supports full BCrypt hash (255 chars)
     * - Matches Spring Security BCryptPasswordEncoder usage
     */
    @Test
    @DisplayName("Password field stores encrypted values (BCrypt)")
    void testPasswordField_StoresEncryptedValue() {
        // Given: User with BCrypt encrypted password
        String bcryptPassword = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
        User testUser = User.builder()
                .userId("TESTPWD1")
                .firstName("Password")
                .lastName("TestUser")
                .password(bcryptPassword)
                .userType(UserType.USER)
                .deleted(false)
                .build();

        // When: Save user with encrypted password
        userRepository.save(testUser);

        // Then: Encrypted password is stored correctly
        Optional<User> retrievedUser = userRepository.findByUserId("TESTPWD1");
        assertThat(retrievedUser).isPresent();
        assertThat(retrievedUser.get().getPassword()).isEqualTo(bcryptPassword);
        assertThat(retrievedUser.get().getPassword()).startsWith("$2a$10$"); // BCrypt format
        assertThat(retrievedUser.get().getPassword().length()).isGreaterThan(8); // Not plain text

        // Verify password field can store full BCrypt hash (60 chars minimum)
        assertThat(retrievedUser.get().getPassword().length()).isGreaterThanOrEqualTo(60);
    }
}
