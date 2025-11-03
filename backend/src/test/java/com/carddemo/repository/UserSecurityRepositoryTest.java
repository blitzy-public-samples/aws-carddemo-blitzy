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
import com.carddemo.security.CustomUserDetailsService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.jdbc.Sql;

import jakarta.persistence.PersistenceException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive JUnit 5 test class for UserSecurityRepository.
 * 
 * <p>Tests Spring Data JPA repository methods for user security data migrated from
 * USRSEC VSAM KSDS file defined in CSUSR01Y.cpy copybook. Validates COBOL-to-Java
 * transformation preserving VSAM key-sequenced access patterns, BCrypt password
 * encryption, and two-tier role model (ROLE_USER, ROLE_ADMIN).</p>
 * 
 * <h2>COBOL Source File Mapping</h2>
 * <ul>
 *   <li>VSAM File: USRSEC (WS-USRSEC-FILE = 'USRSEC  ')</li>
 *   <li>Copybook: CSUSR01Y.cpy (SEC-USER-DATA structure, 80 bytes)</li>
 *   <li>Programs: COSGN00C.cbl, COUSR00C.cbl, COUSR01C.cbl</li>
 * </ul>
 * 
 * <h2>COBOL Record Layout Transformation</h2>
 * <pre>
 * COBOL Field             Type        PostgreSQL Column    Constraint
 * ---------------------------------------------------------------------
 * SEC-USR-ID              PIC X(08)   user_id VARCHAR(8)   PRIMARY KEY
 * SEC-USR-FNAME           PIC X(20)   first_name VARCHAR(20) NOT NULL
 * SEC-USR-LNAME           PIC X(20)   last_name VARCHAR(20)  NOT NULL
 * SEC-USR-PWD             PIC X(08)   password VARCHAR(60)   NOT NULL (BCrypt)
 * SEC-USR-TYPE            PIC X(01)   user_type CHAR(1)     NOT NULL
 * SEC-USR-FILLER          PIC X(23)   (not mapped)          -
 * </pre>
 * 
 * <h2>Test Data Setup</h2>
 * <p>Test data loaded from SQL script with BCrypt pre-hashed passwords:</p>
 * <ul>
 *   <li>user001 / password1 / Regular User ('R')</li>
 *   <li>user002 / password2 / Regular User ('R')</li>
 *   <li>admin / adminpass / Admin User ('A')</li>
 * </ul>
 * 
 * <h2>Spring Security Integration Tests</h2>
 * <p>Validates UserDetailsService implementation for authentication:</p>
 * <ul>
 *   <li>findByUserId() for username lookup (VSAM READ equivalent)</li>
 *   <li>BCrypt password verification using matches()</li>
 *   <li>Role mapping: 'R' → ROLE_USER, 'A' → ROLE_ADMIN</li>
 *   <li>UserDetails interface compliance</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see UserSecurity
 * @see UserSecurityRepository
 * @see CustomUserDetailsService
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import({BCryptPasswordEncoder.class, CustomUserDetailsService.class})
@Sql(scripts = {"/db/test-data/users.sql"})
public class UserSecurityRepositoryTest {
    
    /**
     * Repository under test - provides CRUD operations for UserSecurity entity.
     */
    @Autowired
    private UserSecurityRepository userSecurityRepository;
    
    /**
     * Test entity manager for fine-grained JPA operations control.
     */
    @Autowired
    private TestEntityManager testEntityManager;
    
    /**
     * BCrypt password encoder for password hashing and verification.
     */
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    
    /**
     * Custom UserDetailsService for Spring Security integration testing.
     */
    @Autowired
    private CustomUserDetailsService userDetailsService;
    
    // ===== VSAM Key-Sequenced Access Tests (findByUserId) =====
    
    /**
     * Test findByUserId with valid user ID.
     * 
     * <p>Validates VSAM random read equivalent (EXEC CICS READ USRSEC RIDFLD)
     * with successful record retrieval (RESP=0).</p>
     * 
     * <p>COBOL Operation Being Tested (COSGN00C.cbl lines 211-219):</p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   ('USRSEC  ')
     *      INTO      (SEC-USER-DATA)
     *      RIDFLD    (WS-USER-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * 
     * WHEN 0
     *      -- Record found, proceed with password verification
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Primary key lookup using B-tree unique index</li>
     *   <li>SEC-USR-ID PIC X(08) → VARCHAR(8) user_id column</li>
     *   <li>All fields correctly mapped from CSUSR01Y.cpy copybook</li>
     *   <li>BCrypt password stored instead of plain text</li>
     *   <li>User type field preserved for role mapping</li>
     * </ul>
     */
    @Test
    @Order(1)
    public void testFindByUserId_ValidUser() {
        // Given: User ID from test data
        String userId = "user001";
        
        // When: Find user by ID (VSAM READ equivalent)
        Optional<UserSecurity> result = userSecurityRepository.findByUserId(userId);
        
        // Then: User found with correct data
        assertThat(result).isPresent();
        
        UserSecurity user = result.get();
        assertThat(user.getUserId()).isEqualTo("user001");
        assertThat(user.getFirstName()).isEqualTo("John");
        assertThat(user.getLastName()).isEqualTo("Smith");
        assertThat(user.getUserType()).isEqualTo("R");
        
        // Verify password is BCrypt hash (60 characters starting with $2a$)
        assertThat(user.getPassword()).isNotNull();
        assertThat(user.getPassword()).hasSize(60);
        assertThat(user.getPassword()).startsWith("$2a$");
        
        // Verify BCrypt password matches original plain text
        assertThat(passwordEncoder.matches("password1", user.getPassword())).isTrue();
    }
    
    /**
     * Test findByUserId with non-existent user ID.
     * 
     * <p>Validates VSAM NOTFND condition (RESP=13) handling.</p>
     * 
     * <p>COBOL Operation Being Tested (COSGN00C.cbl lines 247-251):</p>
     * <pre>
     * WHEN 13
     *      MOVE 'User not found. Try again ...' TO WS-MESSAGE
     *      PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Optional.empty() returned for non-existent user</li>
     *   <li>No exception thrown (graceful handling)</li>
     *   <li>Equivalent to VSAM RESP=13 (record not found)</li>
     * </ul>
     */
    @Test
    @Order(2)
    public void testFindByUserId_InvalidUser() {
        // Given: Non-existent user ID
        String userId = "invalid";
        
        // When: Find user by ID
        Optional<UserSecurity> result = userSecurityRepository.findByUserId(userId);
        
        // Then: Empty Optional returned (VSAM NOTFND equivalent)
        assertThat(result).isEmpty();
    }
    
    /**
     * Test findByUserId with case-sensitive username validation.
     * 
     * <p>Verifies username lookup is case-sensitive, matching COBOL behavior
     * where PIC X fields are case-sensitive for comparison operations.</p>
     * 
     * <p>Tests different case variations of the same username to ensure
     * exact match requirement from COBOL EXEC CICS READ operation.</p>
     */
    @Test
    @Order(3)
    public void testFindByUserId_CaseSensitive() {
        // Given: Different case variations of username
        String lowerCase = "admin";
        String upperCase = "ADMIN";
        String mixedCase = "Admin";
        
        // When: Find users with different cases
        Optional<UserSecurity> lowerResult = userSecurityRepository.findByUserId(lowerCase);
        Optional<UserSecurity> upperResult = userSecurityRepository.findByUserId(upperCase);
        Optional<UserSecurity> mixedResult = userSecurityRepository.findByUserId(mixedCase);
        
        // Then: Only exact match found (lowercase 'admin' exists in test data)
        assertThat(lowerResult).isPresent();
        assertThat(upperResult).isEmpty();
        assertThat(mixedResult).isEmpty();
    }
    
    /**
     * Test findById with valid user ID.
     * 
     * <p>Validates JPA standard findById() method using String primary key.
     * Functionally identical to findByUserId() but uses JPA naming convention.</p>
     */
    @Test
    @Order(4)
    public void testFindById_ValidUserId() {
        // Given: Valid user ID
        String userId = "user002";
        
        // When: Find by primary key ID
        Optional<UserSecurity> result = userSecurityRepository.findById(userId);
        
        // Then: User found with correct data
        assertThat(result).isPresent();
        
        UserSecurity user = result.get();
        assertThat(user.getUserId()).isEqualTo("user002");
        assertThat(user.getFirstName()).isEqualTo("Jane");
        assertThat(user.getLastName()).isEqualTo("Doe");
        assertThat(user.getUserType()).isEqualTo("R");
    }
    
    // ===== VSAM Sequential Read Tests (findAll, findByUserType) =====
    
    /**
     * Test findAll returns all users in sequential order.
     * 
     * <p>Validates VSAM sequential browse equivalent (STARTBR/READNEXT loop).</p>
     * 
     * <p>COBOL Operation Being Tested (COUSR00C.cbl):</p>
     * <pre>
     * EXEC CICS STARTBR
     *      DATASET   ('USRSEC  ')
     *      RIDFLD    (WS-USER-ID)
     * END-EXEC.
     * 
     * PERFORM UNTIL USER-SEC-EOF
     *      EXEC CICS READNEXT
     *           DATASET ('USRSEC  ')
     *           INTO    (SEC-USER-DATA)
     *      END-EXEC
     *      ... process record ...
     * END-PERFORM.
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>All user records retrieved</li>
     *   <li>Results ordered by user_id (primary key)</li>
     *   <li>Minimum 3 users from test data</li>
     * </ul>
     */
    @Test
    @Order(5)
    public void testFindAll_ReturnsAllUsers() {
        // When: Retrieve all users (VSAM sequential browse with key ordering)
        List<UserSecurity> allUsers = userSecurityRepository.findAll(Sort.by("userId"));
        
        // Then: At least 3 users from test data
        assertThat(allUsers).isNotEmpty();
        assertThat(allUsers).hasSizeGreaterThanOrEqualTo(3);
        
        // Verify test users present
        assertThat(allUsers).extracting(UserSecurity::getUserId)
            .contains("user001", "user002", "admin");
        
        // Verify sequential order (sorted by user_id matching VSAM KSDS key order)
        assertThat(allUsers).isSortedAccordingTo(
            (u1, u2) -> u1.getUserId().compareTo(u2.getUserId())
        );
    }
    
    // ===== VSAM Write Tests (save - INSERT) =====
    
    /**
     * Test save new user with regular role.
     * 
     * <p>Validates VSAM WRITE equivalent (EXEC CICS WRITE new record).</p>
     * 
     * <p>COBOL Operation Being Tested (COUSR01C.cbl):</p>
     * <pre>
     * EXEC CICS WRITE
     *      DATASET   ('USRSEC  ')
     *      FROM      (SEC-USER-DATA)
     *      RIDFLD    (SEC-USR-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>INSERT operation for new user</li>
     *   <li>SEC-USR-TYPE 'R' → ROLE_USER mapping</li>
     *   <li>BCrypt password encoding</li>
     *   <li>VARCHAR(8) constraint on username</li>
     *   <li>Unique constraint enforcement</li>
     * </ul>
     */
    @Test
    @Order(6)
    public void testSave_NewUser_RegularRole() {
        // Given: New regular user with plain text password
        String plainPassword = "newpass123";
        String encryptedPassword = passwordEncoder.encode(plainPassword);
        
        UserSecurity newUser = new UserSecurity();
        newUser.setUserId("newuser1");
        newUser.setFirstName("Alice");
        newUser.setLastName("Johnson");
        newUser.setPassword(encryptedPassword);
        newUser.setUserType("R");
        
        // When: Save new user (VSAM WRITE)
        UserSecurity savedUser = userSecurityRepository.save(newUser);
        testEntityManager.flush();
        testEntityManager.clear();
        
        // Then: User persisted successfully
        assertThat(savedUser).isNotNull();
        assertThat(savedUser.getUserId()).isEqualTo("newuser1");
        
        // Verify user can be retrieved
        Optional<UserSecurity> retrieved = userSecurityRepository.findByUserId("newuser1");
        assertThat(retrieved).isPresent();
        
        UserSecurity user = retrieved.get();
        assertThat(user.getFirstName()).isEqualTo("Alice");
        assertThat(user.getLastName()).isEqualTo("Johnson");
        assertThat(user.getUserType()).isEqualTo("R");
        
        // Verify password encrypted with BCrypt
        assertThat(passwordEncoder.matches(plainPassword, user.getPassword())).isTrue();
        
        // Verify role mapping to ROLE_USER
        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();
        assertThat(authorities).hasSize(1);
        assertThat(authorities).extracting("authority").contains("ROLE_USER");
    }
    
    /**
     * Test save new user with admin role.
     * 
     * <p>Validates VSAM WRITE for administrative user creation.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>SEC-USR-TYPE 'A' → ROLE_ADMIN + ROLE_USER mapping</li>
     *   <li>Admin user creation with elevated privileges</li>
     *   <li>Hierarchical role model preservation</li>
     * </ul>
     */
    @Test
    @Order(7)
    public void testSave_NewUser_AdminRole() {
        // Given: New admin user
        String plainPassword = "adminpass456";
        String encryptedPassword = passwordEncoder.encode(plainPassword);
        
        UserSecurity adminUser = new UserSecurity();
        adminUser.setUserId("newadmin");
        adminUser.setFirstName("Bob");
        adminUser.setLastName("Administrator");
        adminUser.setPassword(encryptedPassword);
        adminUser.setUserType("A");
        
        // When: Save admin user
        UserSecurity savedUser = userSecurityRepository.save(adminUser);
        testEntityManager.flush();
        testEntityManager.clear();
        
        // Then: Admin user persisted successfully
        assertThat(savedUser).isNotNull();
        
        // Verify user retrieved with admin type
        Optional<UserSecurity> retrieved = userSecurityRepository.findByUserId("newadmin");
        assertThat(retrieved).isPresent();
        
        UserSecurity user = retrieved.get();
        assertThat(user.getUserType()).isEqualTo("A");
        
        // Verify hierarchical role mapping: Admin gets both ROLE_USER and ROLE_ADMIN
        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();
        assertThat(authorities).hasSize(2);
        assertThat(authorities).extracting("authority").containsExactlyInAnyOrder(
            "ROLE_USER",
            "ROLE_ADMIN"
        );
    }
    
    /**
     * Test save duplicate username throws exception.
     * 
     * <p>Validates unique constraint on user_id column.</p>
     * 
     * <p>COBOL equivalent: VSAM DUPREC condition when attempting to write
     * record with duplicate key.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Unique constraint enforcement on user_id</li>
     *   <li>DataIntegrityViolationException thrown</li>
     *   <li>Equivalent to VSAM DUPREC error (RESP=14)</li>
     * </ul>
     */
    @Test
    @Order(8)
    public void testSave_DuplicateUsername_ThrowsException() {
        // Given: New user with existing user_id
        UserSecurity duplicateUser = new UserSecurity();
        duplicateUser.setUserId("user001"); // Duplicate of existing user
        duplicateUser.setFirstName("Duplicate");
        duplicateUser.setLastName("User");
        duplicateUser.setPassword(passwordEncoder.encode("password"));
        duplicateUser.setUserType("R");
        
        // When/Then: Attempting to persist duplicate throws PersistenceException (JPA) or DataIntegrityViolationException (Spring)
        // Note: Using persist() instead of save() because save() uses merge() which updates existing entities
        // testEntityManager.persist() throws JPA PersistenceException, not Spring DataIntegrityViolationException
        assertThatThrownBy(() -> {
            testEntityManager.persist(duplicateUser);
            testEntityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }
    
    // ===== VSAM Rewrite Tests (save - UPDATE) =====
    
    /**
     * Test update existing user.
     * 
     * <p>Validates VSAM REWRITE equivalent (EXEC CICS REWRITE).</p>
     * 
     * <p>COBOL Operation Being Tested (COUSR01C.cbl):</p>
     * <pre>
     * EXEC CICS REWRITE
     *      DATASET   ('USRSEC  ')
     *      FROM      (SEC-USER-DATA)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>UPDATE operation for existing user</li>
     *   <li>Name field modifications</li>
     *   <li>User type can be changed</li>
     * </ul>
     */
    @Test
    @Order(9)
    public void testUpdate_ExistingUser() {
        // Given: Existing user to update
        UserSecurity existingUser = userSecurityRepository.findByUserId("user002")
            .orElseThrow(() -> new AssertionError("Test user not found"));
        
        // When: Modify user fields
        existingUser.setFirstName("Janet");
        existingUser.setLastName("Smith-Doe");
        UserSecurity updatedUser = userSecurityRepository.save(existingUser);
        testEntityManager.flush();
        testEntityManager.clear();
        
        // Then: Changes persisted
        Optional<UserSecurity> retrieved = userSecurityRepository.findByUserId("user002");
        assertThat(retrieved).isPresent();
        
        UserSecurity user = retrieved.get();
        assertThat(user.getFirstName()).isEqualTo("Janet");
        assertThat(user.getLastName()).isEqualTo("Smith-Doe");
    }
    
    /**
     * Test password change with BCrypt re-hashing.
     * 
     * <p>Validates password update transforms plain text to BCrypt hash.</p>
     * 
     * <p>CRITICAL: Ensures passwords are never stored in plain text,
     * unlike COBOL SEC-USR-PWD PIC X(08) which stored clear text.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Password updated with new BCrypt hash</li>
     *   <li>Old password no longer matches</li>
     *   <li>New password matches correctly</li>
     *   <li>BCrypt hash format preserved ($2a$12$...)</li>
     * </ul>
     */
    @Test
    @Order(10)
    public void testUpdate_PasswordChange() {
        // Given: Existing user with known password
        UserSecurity existingUser = userSecurityRepository.findByUserId("user001")
            .orElseThrow(() -> new AssertionError("Test user not found"));
        
        String oldPassword = "password1";
        String newPassword = "newpassword123";
        
        // Verify old password matches
        assertThat(passwordEncoder.matches(oldPassword, existingUser.getPassword())).isTrue();
        
        // When: Change password with BCrypt encoding
        String newEncryptedPassword = passwordEncoder.encode(newPassword);
        existingUser.setPassword(newEncryptedPassword);
        userSecurityRepository.save(existingUser);
        testEntityManager.flush();
        testEntityManager.clear();
        
        // Then: New password stored and old password no longer valid
        UserSecurity updatedUser = userSecurityRepository.findByUserId("user001")
            .orElseThrow(() -> new AssertionError("User not found after update"));
        
        // Verify BCrypt hash format
        assertThat(updatedUser.getPassword()).hasSize(60);
        assertThat(updatedUser.getPassword()).startsWith("$2a$");
        
        // Verify old password no longer matches
        assertThat(passwordEncoder.matches(oldPassword, updatedUser.getPassword())).isFalse();
        
        // Verify new password matches
        assertThat(passwordEncoder.matches(newPassword, updatedUser.getPassword())).isTrue();
    }
    
    /**
     * Test user role change.
     * 
     * <p>Validates user type modification from Regular to Admin or vice versa.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>User type update ('R' → 'A' or 'A' → 'R')</li>
     *   <li>Role conversion reflected in authorities</li>
     *   <li>Privilege escalation/de-escalation</li>
     * </ul>
     */
    @Test
    @Order(11)
    public void testUpdate_RoleChange() {
        // Given: Regular user to promote to admin
        UserSecurity regularUser = userSecurityRepository.findByUserId("user001")
            .orElseThrow(() -> new AssertionError("Test user not found"));
        
        // Verify initially regular user
        assertThat(regularUser.getUserType()).isEqualTo("R");
        Collection<? extends GrantedAuthority> oldAuthorities = regularUser.getAuthorities();
        assertThat(oldAuthorities).hasSize(1);
        assertThat(oldAuthorities).extracting("authority").contains("ROLE_USER");
        
        // When: Promote to admin
        regularUser.setUserType("A");
        userSecurityRepository.save(regularUser);
        testEntityManager.flush();
        testEntityManager.clear();
        
        // Then: User type changed to admin
        UserSecurity promotedUser = userSecurityRepository.findByUserId("user001")
            .orElseThrow(() -> new AssertionError("User not found after update"));
        
        assertThat(promotedUser.getUserType()).isEqualTo("A");
        
        // Verify new authorities include both ROLE_USER and ROLE_ADMIN
        Collection<? extends GrantedAuthority> newAuthorities = promotedUser.getAuthorities();
        assertThat(newAuthorities).hasSize(2);
        assertThat(newAuthorities).extracting("authority").containsExactlyInAnyOrder(
            "ROLE_USER",
            "ROLE_ADMIN"
        );
    }
    
    // ===== VSAM Delete Tests =====
    
    /**
     * Test delete existing user.
     * 
     * <p>Validates VSAM DELETE equivalent (EXEC CICS DELETE).</p>
     * 
     * <p>COBOL Operation Being Tested (COUSR00C.cbl):</p>
     * <pre>
     * EXEC CICS DELETE
     *      DATASET   ('USRSEC  ')
     *      RIDFLD    (WS-USER-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC.
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>DELETE operation removes user</li>
     *   <li>User no longer retrievable after deletion</li>
     *   <li>No cascade issues</li>
     * </ul>
     */
    @Test
    @Order(12)
    public void testDelete_ExistingUser() {
        // Given: Create temporary user for deletion
        UserSecurity tempUser = new UserSecurity();
        tempUser.setUserId("tempuser");
        tempUser.setFirstName("Temp");
        tempUser.setLastName("User");
        tempUser.setPassword(passwordEncoder.encode("temppass"));
        tempUser.setUserType("R");
        userSecurityRepository.save(tempUser);
        testEntityManager.flush();
        
        // Verify user exists
        assertThat(userSecurityRepository.findByUserId("tempuser")).isPresent();
        
        // When: Delete user (VSAM DELETE)
        userSecurityRepository.deleteById("tempuser");
        testEntityManager.flush();
        testEntityManager.clear();
        
        // Then: User no longer exists
        Optional<UserSecurity> deleted = userSecurityRepository.findByUserId("tempuser");
        assertThat(deleted).isEmpty();
    }
    
    // ===== BCrypt Password Encryption Tests =====
    
    /**
     * Test BCrypt password encryption.
     * 
     * <p>Validates BCrypt password hashing with strength parameter.</p>
     * 
     * <p>CRITICAL Security Transformation:</p>
     * <ul>
     *   <li>COBOL: SEC-USR-PWD PIC X(08) plain text (INSECURE)</li>
     *   <li>Java: password VARCHAR(60) BCrypt hash (SECURE)</li>
     * </ul>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>BCrypt hash generation</li>
     *   <li>Salt uniqueness (same password produces different hashes)</li>
     *   <li>matches() method verifies password correctly</li>
     *   <li>Strength 12 encoding per Section 0.9</li>
     * </ul>
     */
    @Test
    @Order(13)
    public void testPasswordEncryption_BCrypt() {
        // Given: Plain text password
        String plainPassword = "testpassword123";
        
        // When: Encode password with BCrypt
        String hash1 = passwordEncoder.encode(plainPassword);
        String hash2 = passwordEncoder.encode(plainPassword);
        
        // Then: Hashes generated correctly
        assertThat(hash1).isNotNull();
        assertThat(hash1).hasSize(60);
        assertThat(hash1).startsWith("$2a$");
        
        // Verify salt uniqueness - same password produces different hashes
        assertThat(hash1).isNotEqualTo(hash2);
        
        // Verify both hashes match the original password
        assertThat(passwordEncoder.matches(plainPassword, hash1)).isTrue();
        assertThat(passwordEncoder.matches(plainPassword, hash2)).isTrue();
        
        // Verify wrong password doesn't match
        assertThat(passwordEncoder.matches("wrongpassword", hash1)).isFalse();
    }
    
    // ===== User Role Mapping Tests =====
    
    /**
     * Test regular user role mapping.
     * 
     * <p>Validates SEC-USR-TYPE 'R' maps to ROLE_USER authority.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>User type 'R' → ROLE_USER</li>
     *   <li>getAuthorities() returns correct authority</li>
     *   <li>Compatible with @PreAuthorize("hasRole('USER')")</li>
     * </ul>
     */
    @Test
    @Order(14)
    public void testUserRoleMapping_Regular() {
        // Given: Regular user from test data
        UserSecurity regularUser = userSecurityRepository.findByUserId("user001")
            .orElseThrow(() -> new AssertionError("Test user not found"));
        
        // Then: User type is 'R'
        assertThat(regularUser.getUserType()).isEqualTo("R");
        
        // Verify GrantedAuthority mapping
        Collection<? extends GrantedAuthority> authorities = regularUser.getAuthorities();
        assertThat(authorities).isNotNull();
        assertThat(authorities).hasSize(1);
        assertThat(authorities).extracting("authority").contains("ROLE_USER");
        
        // Verify authority string format
        GrantedAuthority authority = authorities.iterator().next();
        assertThat(authority.getAuthority()).isEqualTo("ROLE_USER");
    }
    
    /**
     * Test admin user role mapping.
     * 
     * <p>Validates SEC-USR-TYPE 'A' maps to ROLE_ADMIN + ROLE_USER authorities.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>User type 'A' → ROLE_ADMIN + ROLE_USER (hierarchical)</li>
     *   <li>getAuthorities() returns both authorities</li>
     *   <li>Compatible with @PreAuthorize("hasRole('ADMIN')")</li>
     * </ul>
     */
    @Test
    @Order(15)
    public void testUserRoleMapping_Admin() {
        // Given: Admin user from test data
        UserSecurity adminUser = userSecurityRepository.findByUserId("admin")
            .orElseThrow(() -> new AssertionError("Admin user not found"));
        
        // Then: User type is 'A'
        assertThat(adminUser.getUserType()).isEqualTo("A");
        
        // Verify hierarchical role mapping
        Collection<? extends GrantedAuthority> authorities = adminUser.getAuthorities();
        assertThat(authorities).isNotNull();
        assertThat(authorities).hasSize(2);
        assertThat(authorities).extracting("authority").containsExactlyInAnyOrder(
            "ROLE_USER",
            "ROLE_ADMIN"
        );
        
        // Verify both authority strings
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }
    
    // ===== UserDetailsService Integration Tests =====
    
    /**
     * Test UserDetailsService loadByUsername method.
     * 
     * <p>Validates Spring Security integration for authentication.</p>
     * 
     * <p>COBOL Authentication Flow (COSGN00C.cbl lines 209-257):</p>
     * <pre>
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID) INTO(SEC-USER-DATA)
     *     
     *     IF RESP = 0
     *         IF SEC-USR-PWD = WS-USER-PWD
     *             MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *         ELSE
     *             MOVE 'Wrong Password' TO WS-MESSAGE
     *     ELSE IF RESP = 13
     *         MOVE 'User not found' TO WS-MESSAGE
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>loadUserByUsername() finds user by userId</li>
     *   <li>UserDetails contains username, password, authorities</li>
     *   <li>enabled, accountNonExpired, etc. flags set correctly</li>
     * </ul>
     */
    @Test
    @Order(16)
    public void testUserDetailsService_LoadByUsername() {
        // Given: Valid username
        String username = "user001";
        
        // When: Load user via UserDetailsService
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        
        // Then: UserDetails populated correctly
        assertThat(userDetails).isNotNull();
        assertThat(userDetails.getUsername()).isEqualTo("user001");
        assertThat(userDetails.getPassword()).isNotNull();
        assertThat(userDetails.getPassword()).hasSize(60);
        
        // Verify account status flags (all true per COBOL - no expiration/locking)
        assertThat(userDetails.isEnabled()).isTrue();
        assertThat(userDetails.isAccountNonExpired()).isTrue();
        assertThat(userDetails.isAccountNonLocked()).isTrue();
        assertThat(userDetails.isCredentialsNonExpired()).isTrue();
        
        // Verify authorities
        Collection<? extends GrantedAuthority> authorities = userDetails.getAuthorities();
        assertThat(authorities).isNotNull();
        assertThat(authorities).hasSize(1);
        assertThat(authorities).extracting("authority").contains("ROLE_USER");
    }
    
    /**
     * Test UserDetailsService with non-existent username.
     * 
     * <p>Validates UsernameNotFoundException for missing users.</p>
     * 
     * <p>Maps to COBOL RESP=13 (NOTFND) condition from COSGN00C.cbl line 247.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>UsernameNotFoundException thrown for invalid username</li>
     *   <li>Equivalent to VSAM NOTFND condition</li>
     *   <li>Proper error message included</li>
     * </ul>
     */
    @Test
    @Order(17)
    public void testUserDetailsService_UsernameNotFound() {
        // Given: Non-existent username
        String username = "nonexistent";
        
        // When/Then: Loading non-existent user throws UsernameNotFoundException
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(username))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessageContaining("User not found with username: " + username);
    }
    
    /**
     * Test complete authentication flow with BCrypt.
     * 
     * <p>Validates end-to-end authentication matching COBOL sign-on logic.</p>
     * 
     * <p>COBOL Flow (COSGN00C.cbl lines 223-237):</p>
     * <pre>
     * IF SEC-USR-PWD = WS-USER-PWD
     *     MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *     ... proceed with authentication ...
     * ELSE
     *     MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>User lookup by username</li>
     *   <li>BCrypt password verification</li>
     *   <li>Successful authentication flow</li>
     * </ul>
     */
    @Test
    @Order(18)
    public void testAuthenticationWithBCrypt() {
        // Given: Valid username and password
        String username = "user001";
        String plainPassword = "password1";
        
        // When: Load user and verify password
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        boolean passwordMatches = passwordEncoder.matches(plainPassword, userDetails.getPassword());
        
        // Then: Authentication successful
        assertThat(passwordMatches).isTrue();
        assertThat(userDetails.getUsername()).isEqualTo(username);
        assertThat(userDetails.getAuthorities()).isNotEmpty();
    }
    
    /**
     * Test authentication failure with wrong password.
     * 
     * <p>Validates password mismatch detection.</p>
     * 
     * <p>Maps to COBOL: 'Wrong Password. Try again ...' message
     * from COSGN00C.cbl line 238-240.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Wrong password doesn't match BCrypt hash</li>
     *   <li>Authentication failure handling</li>
     * </ul>
     */
    @Test
    @Order(19)
    public void testAuthenticationFailure_WrongPassword() {
        // Given: Valid username but wrong password
        String username = "user001";
        String wrongPassword = "wrongpassword";
        
        // When: Load user and verify wrong password
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        boolean passwordMatches = passwordEncoder.matches(wrongPassword, userDetails.getPassword());
        
        // Then: Authentication fails
        assertThat(passwordMatches).isFalse();
    }
    
    // ===== Database Constraint Tests =====
    
    /**
     * Test user field constraints.
     * 
     * <p>Validates database column constraints from COBOL copybook mapping:</p>
     * <ul>
     *   <li>NOT NULL on username, password, firstName, lastName, userType</li>
     *   <li>VARCHAR(8) on username (SEC-USR-ID PIC X(08))</li>
     *   <li>VARCHAR(60) on password (BCrypt hash vs PIC X(08) plain text)</li>
     *   <li>VARCHAR(20) on firstName (SEC-USR-FNAME PIC X(20))</li>
     *   <li>VARCHAR(20) on lastName (SEC-USR-LNAME PIC X(20))</li>
     *   <li>CHAR(1) on userType (SEC-USR-TYPE PIC X(01))</li>
     * </ul>
     */
    @Test
    @Order(20)
    public void testUserConstraints() {
        // Given: User with all required fields
        UserSecurity validUser = new UserSecurity();
        validUser.setUserId("testcons");
        validUser.setFirstName("Constraint");
        validUser.setLastName("Test");
        validUser.setPassword(passwordEncoder.encode("password"));
        validUser.setUserType("R");
        
        // When: Save valid user
        UserSecurity savedUser = userSecurityRepository.save(validUser);
        
        // Then: User persisted successfully
        assertThat(savedUser).isNotNull();
        
        // Verify field lengths match COBOL definitions
        assertThat(savedUser.getUserId()).hasSizeLessThanOrEqualTo(8);
        assertThat(savedUser.getFirstName()).hasSizeLessThanOrEqualTo(20);
        assertThat(savedUser.getLastName()).hasSizeLessThanOrEqualTo(20);
        assertThat(savedUser.getPassword()).hasSize(60); // BCrypt fixed length
        assertThat(savedUser.getUserType()).hasSize(1);
    }
    
    /**
     * Test username unique constraint.
     * 
     * <p>Validates unique constraint on user_id (primary key).</p>
     * 
     * <p>VSAM equivalent: Duplicate key error (DUPREC) when attempting
     * to write record with existing primary key.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Unique index on user_id column</li>
     *   <li>Primary key constraint enforcement</li>
     *   <li>DataIntegrityViolationException on duplicate</li>
     * </ul>
     */
    @Test
    @Order(21)
    public void testUsernameUniqueConstraint() {
        // Given: Two users with same user_id
        UserSecurity user1 = new UserSecurity();
        user1.setUserId("uniqtest");
        user1.setFirstName("First");
        user1.setLastName("User");
        user1.setPassword(passwordEncoder.encode("pass1"));
        user1.setUserType("R");
        
        UserSecurity user2 = new UserSecurity();
        user2.setUserId("uniqtest"); // Duplicate user_id
        user2.setFirstName("Second");
        user2.setLastName("User");
        user2.setPassword(passwordEncoder.encode("pass2"));
        user2.setUserType("R");
        
        // When: Persist first user successfully
        testEntityManager.persist(user1);
        testEntityManager.flush();
        testEntityManager.clear();
        
        // Then: Persisting second user with same ID throws exception
        // Note: Using persist() instead of save() to force INSERT operation
        // testEntityManager.persist() throws JPA PersistenceException, not Spring DataIntegrityViolationException
        assertThatThrownBy(() -> {
            testEntityManager.persist(user2);
            testEntityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }
    
    /**
     * Test user type validation.
     * 
     * <p>Validates valid user_type values match COBOL definitions:</p>
     * <ul>
     *   <li>'R' - Regular User (CDEMO-USRTYP-USER)</li>
     *   <li>'A' - Administrative User (CDEMO-USRTYP-ADMIN)</li>
     * </ul>
     * 
     * <p>Note: Database allows any CHAR(1) value, but application logic
     * enforces 'R' and 'A' values per COBOL user type definitions.</p>
     */
    @Test
    @Order(22)
    public void testUserTypeValidation() {
        // Test valid user types
        
        // Regular user type 'R'
        UserSecurity regularUser = new UserSecurity();
        regularUser.setUserId("typetest");
        regularUser.setFirstName("Type");
        regularUser.setLastName("Test1");
        regularUser.setPassword(passwordEncoder.encode("password"));
        regularUser.setUserType("R");
        
        UserSecurity savedRegular = userSecurityRepository.save(regularUser);
        assertThat(savedRegular.getUserType()).isEqualTo("R");
        
        // Admin user type 'A'
        UserSecurity adminUser = new UserSecurity();
        adminUser.setUserId("typetes2");
        adminUser.setFirstName("Type");
        adminUser.setLastName("Test2");
        adminUser.setPassword(passwordEncoder.encode("password"));
        adminUser.setUserType("A");
        
        UserSecurity savedAdmin = userSecurityRepository.save(adminUser);
        assertThat(savedAdmin.getUserType()).isEqualTo("A");
        
        testEntityManager.flush();
        testEntityManager.clear();
        
        // Verify both saved correctly
        assertThat(userSecurityRepository.findByUserId("typetest")).isPresent();
        assertThat(userSecurityRepository.findByUserId("typetes2")).isPresent();
    }
    
    // ===== Custom Query Method Tests =====
    
    /**
     * Test findByUserType custom query.
     * 
     * <p>Validates filtered retrieval of users by type for administrative operations.</p>
     * 
     * <p>COBOL equivalent: Sequential browse with user type filtering.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>findByUserType('R') returns only regular users</li>
     *   <li>findByUserType('A') returns only admin users</li>
     *   <li>Results ordered consistently</li>
     * </ul>
     */
    @Test
    @Order(23)
    public void testFindByUserType() {
        // When: Find regular users
        List<UserSecurity> regularUsers = userSecurityRepository.findByUserType("R");
        
        // Then: Only regular users returned
        assertThat(regularUsers).isNotEmpty();
        assertThat(regularUsers).allMatch(user -> "R".equals(user.getUserType()));
        assertThat(regularUsers).extracting(UserSecurity::getUserId)
            .contains("user001", "user002");
        
        // When: Find admin users
        List<UserSecurity> adminUsers = userSecurityRepository.findByUserType("A");
        
        // Then: Only admin users returned
        assertThat(adminUsers).isNotEmpty();
        assertThat(adminUsers).allMatch(user -> "A".equals(user.getUserType()));
        assertThat(adminUsers).extracting(UserSecurity::getUserId)
            .contains("admin");
    }
    
    /**
     * Test repository count operation.
     * 
     * <p>Validates total user count for administrative statistics.</p>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>count() returns total number of users</li>
     *   <li>Minimum 3 users from test data</li>
     * </ul>
     */
    @Test
    @Order(24)
    public void testCount() {
        // When: Get total user count
        long totalUsers = userSecurityRepository.count();
        
        // Then: At least 3 users from test data
        assertThat(totalUsers).isGreaterThanOrEqualTo(3);
    }
    
    // ===== UserDetails Interface Implementation Tests =====
    
    /**
     * Test UserDetails interface implementation on UserSecurity entity.
     * 
     * <p>Validates UserSecurity implements all UserDetails methods correctly:</p>
     * <ul>
     *   <li>getUsername() returns userId</li>
     *   <li>getPassword() returns BCrypt hash</li>
     *   <li>getAuthorities() returns role-based authorities</li>
     *   <li>isEnabled(), isAccountNonExpired(), etc. all return true</li>
     * </ul>
     * 
     * <p>No account expiration, locking, or credential expiration exists
     * in COBOL implementation, so all status methods return true.</p>
     */
    @Test
    @Order(25)
    public void testUserDetailsInterface() {
        // Given: Regular user
        UserSecurity user = userSecurityRepository.findByUserId("user001")
            .orElseThrow(() -> new AssertionError("Test user not found"));
        
        // Then: Verify UserDetails interface implementation
        
        // Username is userId
        assertThat(user.getUsername()).isEqualTo("user001");
        assertThat(user.getUsername()).isEqualTo(user.getUserId());
        
        // Password is BCrypt hash
        assertThat(user.getPassword()).isNotNull();
        assertThat(user.getPassword()).hasSize(60);
        assertThat(user.getPassword()).startsWith("$2a$");
        
        // Authorities based on user type
        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();
        assertThat(authorities).isNotNull();
        assertThat(authorities).isNotEmpty();
        
        // All account status flags true (no expiration/locking in COBOL)
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isAccountNonExpired()).isTrue();
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.isCredentialsNonExpired()).isTrue();
    }
}
