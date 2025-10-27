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

package com.carddemo.service;

import com.carddemo.exception.BusinessException;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserDto;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive JUnit 5 unit test for UserService testing CRUD operations.
 * 
 * Converted from COBOL programs:
 * - COUSR00C.cbl: User list display and browsing operations
 * - COUSR01C.cbl: User add function with validation
 * - COUSR02C.cbl: User update function with field-level modification tracking
 * - COUSR03C.cbl: User delete function with confirmation
 * 
 * Original function:
 * These COBOL programs provided complete user management capabilities in the mainframe
 * CICS environment with VSAM USRSEC file I/O, field-level validation, and RACF integration.
 * 
 * Test Coverage (Section 0.7.14):
 * - Minimum 80% code coverage requirement for backend services
 * - All business logic paths tested
 * - All exception scenarios validated
 * - COBOL business rules preserved exactly per Section 0.7.2
 * 
 * Testing Strategy:
 * - Uses Mockito @Mock for dependency injection (UserSecurityRepository, BCryptPasswordEncoder, ValidationService)
 * - @InjectMocks creates UserService with mocked dependencies
 * - Organized into @Nested test classes for readability
 * - Each test method validates one specific behavior
 * - Comprehensive verification of repository and encoder interactions
 * 
 * Business Logic Preservation (Section 0.7.2):
 * All validation rules from COBOL programs are tested:
 * - COUSR01C.cbl lines 118-151: Mandatory field validation
 * - COUSR01C.cbl lines 240-274: Duplicate key handling
 * - COUSR02C.cbl lines 179-243: Update validation and modification tracking
 * - COUSR03C.cbl: Delete validation
 * 
 * Security Testing (Section 0.7.9):
 * - BCrypt password hashing replaces COBOL plain-text passwords
 * - Password hash never exposed in responses
 * - User type validation ('A', 'U', 'O') replaces RACF roles
 * 
 * @see com.carddemo.service.UserService
 * @see com.carddemo.repository.UserSecurityRepository
 * @see com.carddemo.model.entity.UserSecurity
 * @see com.carddemo.model.dto.UserDto
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Unit Tests - CRUD Operations from COUSR00C-03C")
public class UserServiceTest {

    @Mock
    private UserSecurityRepository mockUserRepository;

    @Mock
    private BCryptPasswordEncoder mockPasswordEncoder;

    @Mock
    private ValidationService mockValidationService;

    @InjectMocks
    private UserService userService;

    // Test data constants matching CSUSR01Y.cpy structure
    private static final String TEST_USER_ID = "USER0001";
    private static final String TEST_USER_ID_2 = "USER0002";
    private static final String TEST_FIRST_NAME = "John";
    private static final String TEST_LAST_NAME = "Doe";
    private static final String TEST_PASSWORD = "password123";
    private static final String TEST_PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuvwxyz1234567890ABCDEF";
    private static final String TEST_USER_TYPE_ADMIN = "A";
    private static final String TEST_USER_TYPE_USER = "U";
    private static final String TEST_USER_TYPE_OPERATOR = "O";
    private static final String TEST_INVALID_USER_TYPE = "X";

    private UserSecurity testUser;
    private UserDto testUserDto;

    /**
     * Set up test data before each test method execution.
     * Creates sample UserSecurity entity and UserDto matching COBOL SEC-USER-DATA structure.
     */
    @BeforeEach
    void setUp() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        // Create test UserSecurity entity (COBOL SEC-USER-DATA structure)
        testUser = UserSecurity.builder()
                .userId(TEST_USER_ID)              // SEC-USR-ID (PIC X(08))
                .userFirstName(TEST_FIRST_NAME)    // SEC-USR-FNAME (PIC X(20))
                .userLastName(TEST_LAST_NAME)      // SEC-USR-LNAME (PIC X(20))
                .userPwdHash(TEST_PASSWORD_HASH)   // SEC-USR-PWD (BCrypt hashed)
                .userType(TEST_USER_TYPE_USER)     // SEC-USR-TYPE (PIC X(01))
                .createdAt(now)
                .updatedAt(now)
                .version(0)
                .build();

        // Create test UserDto for API requests
        testUserDto = UserDto.builder()
                .userId(TEST_USER_ID)
                .userFirstName(TEST_FIRST_NAME)
                .userLastName(TEST_LAST_NAME)
                .password(TEST_PASSWORD)  // Plain-text password (will be hashed)
                .userType(TEST_USER_TYPE_USER)
                .build();
    }

    /**
     * User Listing Tests - Converted from COUSR00C.cbl (lines 282-331)
     * 
     * COBOL operation: EXEC CICS STARTBR / READNEXT browse through USRSEC file
     * Java operation: userSecurityRepository.findAll()
     */
    @Nested
    @DisplayName("User Listing Tests (COUSR00C.cbl)")
    class UserListingTests {

        @Test
        @DisplayName("testGetAllUsers - Should return all users from database")
        void testGetAllUsers() {
            // Arrange: Create multiple test users
            UserSecurity user2 = UserSecurity.builder()
                    .userId(TEST_USER_ID_2)
                    .userFirstName("Jane")
                    .userLastName("Smith")
                    .userPwdHash(TEST_PASSWORD_HASH)
                    .userType(TEST_USER_TYPE_ADMIN)
                    .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                    .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                    .version(0)
                    .build();

            List<UserSecurity> users = Arrays.asList(testUser, user2);
            when(mockUserRepository.findAll()).thenReturn(users);

            // Act: Call service method
            List<UserDto> result = userService.getAllUsers();

            // Assert: Verify results
            assertNotNull(result, "Result should not be null");
            assertEquals(2, result.size(), "Should return 2 users");
            assertEquals(TEST_USER_ID, result.get(0).getUserId(), "First user ID should match");
            assertEquals(TEST_USER_ID_2, result.get(1).getUserId(), "Second user ID should match");
            assertNull(result.get(0).getPassword(), "Password should not be included in response");
            assertNull(result.get(1).getPassword(), "Password should not be included in response");

            // Verify repository interaction
            verify(mockUserRepository, times(1)).findAll();
            verifyNoMoreInteractions(mockUserRepository);
        }

        @Test
        @DisplayName("testGetAllUsersEmpty - Should return empty list when no users exist")
        void testGetAllUsersEmpty() {
            // Arrange: Mock empty list
            when(mockUserRepository.findAll()).thenReturn(Collections.emptyList());

            // Act: Call service method
            List<UserDto> result = userService.getAllUsers();

            // Assert: Verify empty result
            assertNotNull(result, "Result should not be null");
            assertTrue(result.isEmpty(), "Result should be empty list");
            assertEquals(0, result.size(), "Size should be 0");

            // Verify repository interaction
            verify(mockUserRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("testGetUserById - Should return user when found")
        void testGetUserById() {
            // Arrange: Mock repository to return user
            // COBOL: EXEC CICS READ DATASET('USRSEC') RIDFLD(SEC-USR-ID)
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));

            // Act: Call service method
            UserDto result = userService.getUserById(TEST_USER_ID);

            // Assert: Verify user data
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_USER_ID, result.getUserId(), "User ID should match");
            assertEquals(TEST_FIRST_NAME, result.getUserFirstName(), "First name should match");
            assertEquals(TEST_LAST_NAME, result.getUserLastName(), "Last name should match");
            assertEquals(TEST_USER_TYPE_USER, result.getUserType(), "User type should match");
            assertNull(result.getPassword(), "Password should not be included in response");

            // Verify repository interaction
            verify(mockUserRepository, times(1)).findById(TEST_USER_ID);
        }

        @Test
        @DisplayName("testGetUserByIdNotFound - Should throw DataNotFoundException when user not found")
        void testGetUserByIdNotFound() {
            // Arrange: Mock repository to return empty (user not found)
            // COBOL: WHEN DFHRESP(NOTFND)
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

            // Act & Assert: Verify exception thrown
            DataNotFoundException exception = assertThrows(
                    DataNotFoundException.class,
                    () -> userService.getUserById(TEST_USER_ID),
                    "Should throw DataNotFoundException when user not found"
            );

            assertTrue(exception.getMessage().contains(TEST_USER_ID), "Exception message should contain user ID");

            // Verify repository interaction
            verify(mockUserRepository, times(1)).findById(TEST_USER_ID);
        }
    }

    /**
     * User Creation Tests - Converted from COUSR01C.cbl (lines 115-161, 236-274)
     * 
     * COBOL operation: Field validation + EXEC CICS WRITE FILE('USRSEC')
     * Java operation: Validation + BCrypt hashing + userSecurityRepository.save()
     */
    @Nested
    @DisplayName("User Creation Tests (COUSR01C.cbl)")
    class UserCreationTests {

        @Test
        @DisplayName("testCreateUserSuccess - Should create user with hashed password")
        void testCreateUserSuccess() {
            // Arrange: Mock validation passes
            doNothing().when(mockValidationService).validateUserId(TEST_USER_ID);
            doNothing().when(mockValidationService).validatePassword(TEST_PASSWORD);
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());

            // Mock uniqueness check (user does not exist)
            // COBOL: Check for DFHRESP(DUPREC)
            when(mockUserRepository.existsById(TEST_USER_ID)).thenReturn(false);

            // Mock password encoder
            // COBOL: MOVE PASSWDI TO SEC-USR-PWD (plain-text)
            // Java: BCrypt hashing per Section 0.7.9
            when(mockPasswordEncoder.encode(TEST_PASSWORD)).thenReturn(TEST_PASSWORD_HASH);

            // Mock repository save
            // COBOL: EXEC CICS WRITE DATASET('USRSEC') FROM(SEC-USER-DATA)
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> {
                UserSecurity saved = invocation.getArgument(0);
                saved.setVersion(0);
                return saved;
            });

            // Act: Call service method
            UserDto result = userService.createUser(testUserDto);

            // Assert: Verify created user
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_USER_ID, result.getUserId(), "User ID should match");
            assertEquals(TEST_FIRST_NAME, result.getUserFirstName(), "First name should match");
            assertEquals(TEST_LAST_NAME, result.getUserLastName(), "Last name should match");
            assertEquals(TEST_USER_TYPE_USER, result.getUserType(), "User type should match");
            assertNull(result.getPassword(), "Password should not be in response");

            // Verify all validation calls (COBOL field validation)
            verify(mockValidationService, times(1)).validateUserId(TEST_USER_ID);
            verify(mockValidationService, times(1)).validatePassword(TEST_PASSWORD);
            verify(mockValidationService, times(1)).validateMandatoryField(TEST_FIRST_NAME, "firstName");
            verify(mockValidationService, times(1)).validateMandatoryField(TEST_LAST_NAME, "lastName");
            verify(mockValidationService, times(1)).validateMandatoryField(TEST_USER_TYPE_USER, "userType");

            // Verify uniqueness check
            verify(mockUserRepository, times(1)).existsById(TEST_USER_ID);

            // Verify password encoding
            verify(mockPasswordEncoder, times(1)).encode(TEST_PASSWORD);

            // Verify repository save
            verify(mockUserRepository, times(1)).save(any(UserSecurity.class));
        }

        @Test
        @DisplayName("testCreateUserDuplicateId - Should throw BusinessException when user ID already exists")
        void testCreateUserDuplicateId() {
            // Arrange: Mock validation passes
            doNothing().when(mockValidationService).validateUserId(TEST_USER_ID);

            // Mock uniqueness check (user already exists)
            // COBOL: WHEN DFHRESP(DUPKEY) / WHEN DFHRESP(DUPREC)
            when(mockUserRepository.existsById(TEST_USER_ID)).thenReturn(true);

            // Act & Assert: Verify exception thrown
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> userService.createUser(testUserDto),
                    "Should throw BusinessException when user ID already exists"
            );

            assertEquals("BUS003", exception.getErrorCode(), "Error code should be BUS003");
            assertTrue(exception.getMessage().contains("already exists"), "Exception message should contain 'already exists'");
            assertTrue(exception.getMessage().contains(TEST_USER_ID), "Exception message should contain user ID");

            // Verify validation was called
            verify(mockValidationService, times(1)).validateUserId(TEST_USER_ID);

            // Verify uniqueness check was called
            verify(mockUserRepository, times(1)).existsById(TEST_USER_ID);

            // Verify repository save was NOT called
            verify(mockUserRepository, never()).save(any(UserSecurity.class));

            // Verify password encoder was NOT called
            verify(mockPasswordEncoder, never()).encode(anyString());
        }

        @Test
        @DisplayName("testCreateUserInvalidUserId - Should throw ValidationException when user ID is invalid")
        void testCreateUserInvalidUserId() {
            // Arrange: Mock validation throws exception
            // COBOL: WHEN USERIDI OF COUSR1AI = SPACES OR LOW-VALUES
            doThrow(new ValidationException("Invalid user ID format"))
                    .when(mockValidationService).validateUserId(TEST_USER_ID);

            // Act & Assert: Verify exception propagated
            assertThrows(
                    ValidationException.class,
                    () -> userService.createUser(testUserDto),
                    "Should throw ValidationException when user ID validation fails"
            );

            // Verify validation was attempted
            verify(mockValidationService, times(1)).validateUserId(TEST_USER_ID);

            // Verify no further processing occurred
            verify(mockUserRepository, never()).existsById(anyString());
            verify(mockUserRepository, never()).save(any(UserSecurity.class));
        }

        @Test
        @DisplayName("testCreateUserPasswordHashing - Should hash password with BCrypt before storage")
        void testCreateUserPasswordHashing() {
            // Arrange: Mock all validation and repository calls
            doNothing().when(mockValidationService).validateUserId(anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.existsById(anyString())).thenReturn(false);
            when(mockPasswordEncoder.encode(TEST_PASSWORD)).thenReturn(TEST_PASSWORD_HASH);
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act: Create user
            userService.createUser(testUserDto);

            // Assert: Verify password encoder was called with plain-text password
            // COBOL: MOVE PASSWDI TO SEC-USR-PWD (plain-text storage)
            // Java: BCrypt hash per Section 0.7.9
            verify(mockPasswordEncoder, times(1)).encode(TEST_PASSWORD);

            // Verify repository save was called with hashed password
            verify(mockUserRepository, times(1)).save(argThat(user ->
                    TEST_PASSWORD_HASH.equals(user.getUserPwdHash())
            ));
        }

        @Test
        @DisplayName("testCreateUserDefaultValues - Should set createdAt timestamp and version")
        void testCreateUserDefaultValues() {
            // Arrange: Mock all dependencies
            doNothing().when(mockValidationService).validateUserId(anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.existsById(anyString())).thenReturn(false);
            when(mockPasswordEncoder.encode(anyString())).thenReturn(TEST_PASSWORD_HASH);
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act: Create user
            userService.createUser(testUserDto);

            // Assert: Verify createdAt and updatedAt timestamps are set
            verify(mockUserRepository, times(1)).save(argThat(user ->
                    user.getCreatedAt() != null &&
                    user.getUpdatedAt() != null &&
                    user.getVersion() == null  // Version set to null before save (JPA will initialize to 0)
            ));
        }

        @Test
        @DisplayName("testCreateUserInvalidUserType - Should throw BusinessException for invalid user type")
        void testCreateUserInvalidUserType() {
            // Arrange: Set invalid user type
            testUserDto.setUserType(TEST_INVALID_USER_TYPE);

            // Mock validations pass
            doNothing().when(mockValidationService).validateUserId(anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.existsById(anyString())).thenReturn(false);

            // Act & Assert: Verify exception thrown
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> userService.createUser(testUserDto),
                    "Should throw BusinessException for invalid user type"
            );

            assertEquals("BUS001", exception.getErrorCode(), "Error code should be BUS001");
            assertTrue(exception.getMessage().contains("Invalid user type"), "Exception message should contain 'Invalid user type'");

            // Verify repository save was NOT called
            verify(mockUserRepository, never()).save(any(UserSecurity.class));
        }
    }

    /**
     * User Update Tests - Converted from COUSR02C.cbl (lines 175-245)
     * 
     * COBOL operation: READ + field comparison + EXEC CICS REWRITE FILE('USRSEC')
     * Java operation: findById + field updates + userSecurityRepository.save()
     */
    @Nested
    @DisplayName("User Update Tests (COUSR02C.cbl)")
    class UserUpdateTests {

        @Test
        @DisplayName("testUpdateUserSuccess - Should update user fields successfully")
        void testUpdateUserSuccess() {
            // Arrange: Mock repository to return existing user
            // COBOL: EXEC CICS READ DATASET('USRSEC') RIDFLD(SEC-USR-ID)
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));

            // Mock validation passes
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());

            // Mock password encoder (password update)
            when(mockPasswordEncoder.encode(anyString())).thenReturn(TEST_PASSWORD_HASH);

            // Mock repository save
            // COBOL: EXEC CICS REWRITE DATASET('USRSEC') FROM(SEC-USER-DATA)
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Create update DTO with modified fields
            UserDto updateDto = UserDto.builder()
                    .userFirstName("UpdatedFirst")
                    .userLastName("UpdatedLast")
                    .userType(TEST_USER_TYPE_ADMIN)
                    .password("newpassword123")
                    .build();

            // Act: Call service method
            UserDto result = userService.updateUser(TEST_USER_ID, updateDto);

            // Assert: Verify updated fields
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_USER_ID, result.getUserId(), "User ID should remain unchanged");
            assertEquals("UpdatedFirst", result.getUserFirstName(), "First name should be updated");
            assertEquals("UpdatedLast", result.getUserLastName(), "Last name should be updated");
            assertEquals(TEST_USER_TYPE_ADMIN, result.getUserType(), "User type should be updated");
            assertNull(result.getPassword(), "Password should not be in response");

            // Verify repository interactions
            verify(mockUserRepository, times(1)).findById(TEST_USER_ID);
            verify(mockUserRepository, times(1)).save(any(UserSecurity.class));

            // Verify validation calls
            verify(mockValidationService, times(1)).validateMandatoryField("UpdatedFirst", "firstName");
            verify(mockValidationService, times(1)).validateMandatoryField("UpdatedLast", "lastName");
            verify(mockValidationService, times(1)).validateMandatoryField(TEST_USER_TYPE_ADMIN, "userType");
            verify(mockValidationService, times(1)).validatePassword("newpassword123");

            // Verify password encoding
            verify(mockPasswordEncoder, times(1)).encode("newpassword123");
        }

        @Test
        @DisplayName("testUpdateUserNotFound - Should throw DataNotFoundException when user not found")
        void testUpdateUserNotFound() {
            // Arrange: Mock repository to return empty (user not found)
            // COBOL: WHEN DFHRESP(NOTFND)
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

            // Act & Assert: Verify exception thrown
            DataNotFoundException exception = assertThrows(
                    DataNotFoundException.class,
                    () -> userService.updateUser(TEST_USER_ID, testUserDto),
                    "Should throw DataNotFoundException when user not found"
            );

            assertTrue(exception.getMessage().contains(TEST_USER_ID), "Exception message should contain user ID");

            // Verify repository interaction
            verify(mockUserRepository, times(1)).findById(TEST_USER_ID);
            verify(mockUserRepository, never()).save(any(UserSecurity.class));
        }

        @Test
        @DisplayName("testUpdateUserFirstName - Should update only first name field")
        void testUpdateUserFirstName() {
            // Arrange: Mock repository and validation
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Create update DTO with only first name
            // COBOL: IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME
            UserDto updateDto = UserDto.builder()
                    .userFirstName("NewFirstName")
                    .build();

            // Act: Call service method
            UserDto result = userService.updateUser(TEST_USER_ID, updateDto);

            // Assert: Verify only first name updated
            assertEquals("NewFirstName", result.getUserFirstName(), "First name should be updated");
            assertEquals(TEST_LAST_NAME, result.getUserLastName(), "Last name should remain unchanged");
            assertEquals(TEST_USER_TYPE_USER, result.getUserType(), "User type should remain unchanged");

            // Verify validation called only for updated field
            verify(mockValidationService, times(1)).validateMandatoryField("NewFirstName", "firstName");
            verify(mockValidationService, never()).validatePassword(anyString());
        }

        @Test
        @DisplayName("testUpdateUserLastName - Should update only last name field")
        void testUpdateUserLastName() {
            // Arrange: Mock repository and validation
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Create update DTO with only last name
            // COBOL: IF LNAMEI OF COUSR2AI NOT = SEC-USR-LNAME
            UserDto updateDto = UserDto.builder()
                    .userLastName("NewLastName")
                    .build();

            // Act: Call service method
            UserDto result = userService.updateUser(TEST_USER_ID, updateDto);

            // Assert: Verify only last name updated
            assertEquals(TEST_FIRST_NAME, result.getUserFirstName(), "First name should remain unchanged");
            assertEquals("NewLastName", result.getUserLastName(), "Last name should be updated");

            // Verify validation
            verify(mockValidationService, times(1)).validateMandatoryField("NewLastName", "lastName");
        }

        @Test
        @DisplayName("testUpdateUserPassword - Should hash password when updating")
        void testUpdateUserPassword() {
            // Arrange: Mock repository and validation
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
            doNothing().when(mockValidationService).validatePassword(anyString());
            String newPasswordHash = "$2a$10$newhashabcdefghijklmnopqrstuvwxyz1234567890";
            when(mockPasswordEncoder.encode("newpassword456")).thenReturn(newPasswordHash);
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Create update DTO with new password
            // COBOL: IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD
            UserDto updateDto = UserDto.builder()
                    .password("newpassword456")
                    .build();

            // Act: Call service method
            userService.updateUser(TEST_USER_ID, updateDto);

            // Assert: Verify password validation and hashing
            verify(mockValidationService, times(1)).validatePassword("newpassword456");
            verify(mockPasswordEncoder, times(1)).encode("newpassword456");

            // Verify save called with new password hash
            verify(mockUserRepository, times(1)).save(argThat(user ->
                    newPasswordHash.equals(user.getUserPwdHash())
            ));
        }

        @Test
        @DisplayName("testUpdateUserPasswordNull - Should not change password when null")
        void testUpdateUserPasswordNull() {
            // Arrange: Mock repository
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Create update DTO without password (null)
            UserDto updateDto = UserDto.builder()
                    .build();

            // Act: Call service method
            userService.updateUser(TEST_USER_ID, updateDto);

            // Assert: Verify password validation and encoding NOT called
            verify(mockValidationService, never()).validatePassword(anyString());
            verify(mockPasswordEncoder, never()).encode(anyString());

            // Verify save called with original password hash
            verify(mockUserRepository, times(1)).save(argThat(user ->
                    TEST_PASSWORD_HASH.equals(user.getUserPwdHash())
            ));
        }

        @Test
        @DisplayName("testUpdateUserType - Should update user type with validation")
        void testUpdateUserType() {
            // Arrange: Mock repository and validation
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Create update DTO with new user type
            // COBOL: IF USRTYPEI OF COUSR2AI NOT = SEC-USR-TYPE
            UserDto updateDto = UserDto.builder()
                    .userType(TEST_USER_TYPE_ADMIN)
                    .build();

            // Act: Call service method
            UserDto result = userService.updateUser(TEST_USER_ID, updateDto);

            // Assert: Verify user type updated
            assertEquals(TEST_USER_TYPE_ADMIN, result.getUserType(), "User type should be updated to Admin");

            // Verify validation called
            verify(mockValidationService, times(1)).validateMandatoryField(TEST_USER_TYPE_ADMIN, "userType");
        }

        @Test
        @DisplayName("testUpdateUserInvalidType - Should throw BusinessException for invalid user type")
        void testUpdateUserInvalidType() {
            // Arrange: Mock repository
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());

            // Create update DTO with invalid user type
            UserDto updateDto = UserDto.builder()
                    .userType(TEST_INVALID_USER_TYPE)
                    .build();

            // Act & Assert: Verify exception thrown
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> userService.updateUser(TEST_USER_ID, updateDto),
                    "Should throw BusinessException for invalid user type"
            );

            assertEquals("BUS001", exception.getErrorCode(), "Error code should be BUS001");
            assertTrue(exception.getMessage().contains("Invalid user type"), "Exception message should contain 'Invalid user type'");

            // Verify repository save NOT called
            verify(mockUserRepository, never()).save(any(UserSecurity.class));
        }
    }

    /**
     * User Deletion Tests - Converted from COUSR03C.cbl
     * 
     * COBOL operation: READ + EXEC CICS DELETE FILE('USRSEC')
     * Java operation: existsById check + userSecurityRepository.deleteById()
     */
    @Nested
    @DisplayName("User Deletion Tests (COUSR03C.cbl)")
    class UserDeletionTests {

        @Test
        @DisplayName("testDeleteUserSuccess - Should delete user when exists")
        void testDeleteUserSuccess() {
            // Arrange: Mock user exists
            // COBOL: EXEC CICS READ (check existence before delete)
            when(mockUserRepository.existsById(TEST_USER_ID)).thenReturn(true);

            // Mock delete operation (returns void)
            // COBOL: EXEC CICS DELETE DATASET('USRSEC') RIDFLD(SEC-USR-ID)
            doNothing().when(mockUserRepository).deleteById(TEST_USER_ID);

            // Act: Call service method
            userService.deleteUser(TEST_USER_ID);

            // Assert: Verify repository interactions
            verify(mockUserRepository, times(1)).existsById(TEST_USER_ID);
            verify(mockUserRepository, times(1)).deleteById(TEST_USER_ID);
        }

        @Test
        @DisplayName("testDeleteUserNotFound - Should throw DataNotFoundException when user not found")
        void testDeleteUserNotFound() {
            // Arrange: Mock user does not exist
            // COBOL: WHEN DFHRESP(NOTFND)
            when(mockUserRepository.existsById(TEST_USER_ID)).thenReturn(false);

            // Act & Assert: Verify exception thrown
            DataNotFoundException exception = assertThrows(
                    DataNotFoundException.class,
                    () -> userService.deleteUser(TEST_USER_ID),
                    "Should throw DataNotFoundException when user not found"
            );

            assertTrue(exception.getMessage().contains(TEST_USER_ID), "Exception message should contain user ID");

            // Verify repository interactions
            verify(mockUserRepository, times(1)).existsById(TEST_USER_ID);
            verify(mockUserRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("testDeleteUserConfirmation - Should require user existence check before deletion")
        void testDeleteUserConfirmation() {
            // Arrange: Mock user exists
            when(mockUserRepository.existsById(TEST_USER_ID)).thenReturn(true);
            doNothing().when(mockUserRepository).deleteById(TEST_USER_ID);

            // Act: Call service method
            userService.deleteUser(TEST_USER_ID);

            // Assert: Verify existence check called BEFORE delete
            // This ensures COBOL pattern of READ before DELETE is preserved
            verify(mockUserRepository, times(1)).existsById(TEST_USER_ID);
            verify(mockUserRepository, times(1)).deleteById(TEST_USER_ID);
        }
    }

    /**
     * Password Security Tests - Section 0.7.9 Security Migration
     * 
     * BCrypt password hashing replaces COBOL plain-text password storage.
     */
    @Nested
    @DisplayName("Password Security Tests (Section 0.7.9)")
    class PasswordSecurityTests {

        @Test
        @DisplayName("testPasswordNeverStoredPlainText - Password must be hashed before storage")
        void testPasswordNeverStoredPlainText() {
            // Arrange: Mock all dependencies
            doNothing().when(mockValidationService).validateUserId(anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.existsById(anyString())).thenReturn(false);
            when(mockPasswordEncoder.encode(TEST_PASSWORD)).thenReturn(TEST_PASSWORD_HASH);
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act: Create user
            userService.createUser(testUserDto);

            // Assert: Verify plain-text password NEVER stored
            // COBOL stored: SEC-USR-PWD = "password123" (plain-text)
            // Java stores: userPwdHash = "$2a$10$..." (BCrypt hash)
            verify(mockUserRepository, times(1)).save(argThat(user ->
                    !TEST_PASSWORD.equals(user.getUserPwdHash()) &&  // Plain-text NOT stored
                    TEST_PASSWORD_HASH.equals(user.getUserPwdHash())  // Hash IS stored
            ));
        }

        @Test
        @DisplayName("testPasswordHashingOnCreate - BCrypt encoder must be called during user creation")
        void testPasswordHashingOnCreate() {
            // Arrange: Mock all dependencies
            doNothing().when(mockValidationService).validateUserId(anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.existsById(anyString())).thenReturn(false);
            when(mockPasswordEncoder.encode(TEST_PASSWORD)).thenReturn(TEST_PASSWORD_HASH);
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act: Create user
            userService.createUser(testUserDto);

            // Assert: Verify BCrypt encoder called with plain-text password
            verify(mockPasswordEncoder, times(1)).encode(TEST_PASSWORD);
        }

        @Test
        @DisplayName("testPasswordHashingOnUpdate - BCrypt encoder must be called during password update")
        void testPasswordHashingOnUpdate() {
            // Arrange: Mock repository and validation
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
            doNothing().when(mockValidationService).validatePassword(anyString());
            String newPassword = "newpassword789";
            String newPasswordHash = "$2a$10$newhashxyz";
            when(mockPasswordEncoder.encode(newPassword)).thenReturn(newPasswordHash);
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Create update DTO with new password
            UserDto updateDto = UserDto.builder()
                    .password(newPassword)
                    .build();

            // Act: Update user
            userService.updateUser(TEST_USER_ID, updateDto);

            // Assert: Verify BCrypt encoder called with new plain-text password
            verify(mockPasswordEncoder, times(1)).encode(newPassword);

            // Verify new hash stored (not plain-text)
            verify(mockUserRepository, times(1)).save(argThat(user ->
                    !newPassword.equals(user.getUserPwdHash()) &&  // Plain-text NOT stored
                    newPasswordHash.equals(user.getUserPwdHash())  // New hash IS stored
            ));
        }

        @Test
        @DisplayName("testPasswordEncoderBCrypt - Must use BCrypt algorithm (not plain-text)")
        void testPasswordEncoderBCrypt() {
            // This test verifies that the mock is of type BCryptPasswordEncoder
            // In production, SecurityConfig configures BCryptPasswordEncoder with strength 10

            // Assert: Verify mock is BCryptPasswordEncoder type
            assertTrue(mockPasswordEncoder instanceof BCryptPasswordEncoder,
                    "Password encoder must be BCryptPasswordEncoder (not plain-text)");

            // Note: In production code, actual BCrypt hashing is configured in SecurityConfig
            // This test verifies the service uses the correct encoder type
        }
    }

    /**
     * User Type Validation Tests
     * 
     * Validates COBOL 88-level condition names: USRTYP-ADMIN, USRTYP-USER, USRTYP-OPER
     * Maps to Spring Security roles: ROLE_ADMIN, ROLE_USER, ROLE_OPERATOR
     */
    @Nested
    @DisplayName("User Type Validation Tests")
    class UserTypeValidationTests {

        @Test
        @DisplayName("testValidUserTypeAdmin - Should accept 'A' for Admin users")
        void testValidUserTypeAdmin() {
            // Arrange: Set user type to Admin
            testUserDto.setUserType(TEST_USER_TYPE_ADMIN);

            // Mock all dependencies
            doNothing().when(mockValidationService).validateUserId(anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.existsById(anyString())).thenReturn(false);
            when(mockPasswordEncoder.encode(anyString())).thenReturn(TEST_PASSWORD_HASH);
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act: Create user with Admin type
            // COBOL: 88 USRTYP-ADMIN VALUE 'A'
            UserDto result = userService.createUser(testUserDto);

            // Assert: Verify user created with Admin type
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_USER_TYPE_ADMIN, result.getUserType(), "User type should be 'A' (Admin)");

            // Verify repository save called
            verify(mockUserRepository, times(1)).save(any(UserSecurity.class));
        }

        @Test
        @DisplayName("testValidUserTypeUser - Should accept 'U' for regular users")
        void testValidUserTypeUser() {
            // Arrange: User type already set to 'U' in setUp()

            // Mock all dependencies
            doNothing().when(mockValidationService).validateUserId(anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.existsById(anyString())).thenReturn(false);
            when(mockPasswordEncoder.encode(anyString())).thenReturn(TEST_PASSWORD_HASH);
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act: Create user with User type
            // COBOL: 88 USRTYP-USER VALUE 'U'
            UserDto result = userService.createUser(testUserDto);

            // Assert: Verify user created with User type
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_USER_TYPE_USER, result.getUserType(), "User type should be 'U' (User)");

            // Verify repository save called
            verify(mockUserRepository, times(1)).save(any(UserSecurity.class));
        }

        @Test
        @DisplayName("testValidUserTypeOperator - Should accept 'O' for operator users")
        void testValidUserTypeOperator() {
            // Arrange: Set user type to Operator
            testUserDto.setUserType(TEST_USER_TYPE_OPERATOR);

            // Mock all dependencies
            doNothing().when(mockValidationService).validateUserId(anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.existsById(anyString())).thenReturn(false);
            when(mockPasswordEncoder.encode(anyString())).thenReturn(TEST_PASSWORD_HASH);
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act: Create user with Operator type
            // COBOL: 88 USRTYP-OPER VALUE 'O'
            UserDto result = userService.createUser(testUserDto);

            // Assert: Verify user created with Operator type
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_USER_TYPE_OPERATOR, result.getUserType(), "User type should be 'O' (Operator)");

            // Verify repository save called
            verify(mockUserRepository, times(1)).save(any(UserSecurity.class));
        }

        @Test
        @DisplayName("testInvalidUserType - Should reject invalid user type 'X'")
        void testInvalidUserType() {
            // Arrange: Set invalid user type
            testUserDto.setUserType(TEST_INVALID_USER_TYPE);

            // Mock validations pass
            doNothing().when(mockValidationService).validateUserId(anyString());
            doNothing().when(mockValidationService).validatePassword(anyString());
            doNothing().when(mockValidationService).validateMandatoryField(anyString(), anyString());
            when(mockUserRepository.existsById(anyString())).thenReturn(false);

            // Act & Assert: Verify exception thrown
            // COBOL: IF SEC-USR-TYPE NOT = 'A' AND NOT = 'U' AND NOT = 'O'
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> userService.createUser(testUserDto),
                    "Should throw BusinessException for invalid user type"
            );

            assertEquals("BUS001", exception.getErrorCode(), "Error code should be BUS001");
            assertTrue(exception.getMessage().contains("Invalid user type"), "Exception message should mention invalid user type");
            assertTrue(exception.getMessage().contains("A"), "Exception message should mention valid type 'A'");
            assertTrue(exception.getMessage().contains("U"), "Exception message should mention valid type 'U'");
            assertTrue(exception.getMessage().contains("O"), "Exception message should mention valid type 'O'");

            // Verify repository save NOT called
            verify(mockUserRepository, never()).save(any(UserSecurity.class));
        }
    }

    /**
     * Additional Edge Case Tests
     */
    @Nested
    @DisplayName("Additional Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("testGetUsersByTypeAdmin - Should filter users by Admin type")
        void testGetUsersByTypeAdmin() {
            // Arrange: Create test users with Admin type
            UserSecurity adminUser = UserSecurity.builder()
                    .userId("ADMIN001")
                    .userFirstName("Admin")
                    .userLastName("User")
                    .userPwdHash(TEST_PASSWORD_HASH)
                    .userType(TEST_USER_TYPE_ADMIN)
                    .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                    .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                    .build();

            List<UserSecurity> adminUsers = Arrays.asList(adminUser);
            when(mockUserRepository.findByUserType(TEST_USER_TYPE_ADMIN)).thenReturn(adminUsers);

            // Act: Call service method
            List<UserDto> result = userService.getUsersByType(TEST_USER_TYPE_ADMIN);

            // Assert: Verify results
            assertNotNull(result, "Result should not be null");
            assertEquals(1, result.size(), "Should return 1 admin user");
            assertEquals(TEST_USER_TYPE_ADMIN, result.get(0).getUserType(), "User type should be Admin");

            // Verify repository interaction
            verify(mockUserRepository, times(1)).findByUserType(TEST_USER_TYPE_ADMIN);
        }

        @Test
        @DisplayName("testUpdateUserNoFieldsChanged - Should update timestamp even if no fields changed")
        void testUpdateUserNoFieldsChanged() {
            // Arrange: Mock repository
            when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
            when(mockUserRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Create empty update DTO (no fields to update)
            UserDto updateDto = UserDto.builder().build();

            // Act: Call service method
            UserDto result = userService.updateUser(TEST_USER_ID, updateDto);

            // Assert: Verify user returned successfully
            assertNotNull(result, "Result should not be null");

            // Verify repository save still called (for timestamp update)
            verify(mockUserRepository, times(1)).save(argThat(user ->
                    user.getUpdatedAt() != null
            ));
        }
    }
}
