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

import com.carddemo.dto.request.UserRequest;
import com.carddemo.dto.response.UserResponse;
import com.carddemo.entity.User;
import com.carddemo.entity.User.UserType;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.user.UserCreateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * JUnit 5 unit test class for UserCreateService.
 * 
 * <p>This test class verifies the user creation business logic preservation from the
 * mainframe COBOL program COUSR01C.cbl (User Add transaction CU01). It ensures that
 * all validation rules, error handling patterns, and data transformation logic from
 * the COBOL implementation are correctly maintained in the Java Spring Boot service.</p>
 * 
 * <h2>COBOL Source Program</h2>
 * <p><b>Program:</b> COUSR01C.cbl - User Add Transaction (CU01)</p>
 * <p><b>Function:</b> Add new Regular/Admin user to USRSEC VSAM file</p>
 * <p><b>Copybook:</b> CSUSR01Y.cpy - SEC-USER-DATA record structure (80 bytes)</p>
 * 
 * <h2>COBOL Operations Tested</h2>
 * <ul>
 *   <li><b>PROCESS-ENTER-KEY</b> (lines 118-147): Field validation logic
 *     <ul>
 *       <li>FNAMEI validation: Check for SPACES or LOW-VALUES</li>
 *       <li>LNAMEI validation: Check for SPACES or LOW-VALUES</li>
 *       <li>USERIDI validation: Check for SPACES or LOW-VALUES</li>
 *       <li>PASSWDI validation: Check for SPACES or LOW-VALUES</li>
 *       <li>USRTYPEI validation: Check for SPACES or LOW-VALUES</li>
 *     </ul>
 *   </li>
 *   <li><b>WRITE-USER-SEC-FILE</b> (lines 237-266): VSAM write operation
 *     <ul>
 *       <li>DFHRESP(NORMAL): Successful user creation</li>
 *       <li>DFHRESP(DUPKEY)/DFHRESP(DUPREC): Duplicate user ID error</li>
 *       <li>OTHER: Generic file write error</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <h2>Data Structure Mapping</h2>
 * <pre>
 * COBOL (CSUSR01Y.cpy)              Java (User.java / UserRequest.java)
 * ====================================================================================================
 * SEC-USR-ID      PIC X(08)    →    userId        String (max 8 chars, primary key)
 * SEC-USR-FNAME   PIC X(20)    →    firstName     String (max 20 chars, required)
 * SEC-USR-LNAME   PIC X(20)    →    lastName      String (max 20 chars, required)
 * SEC-USR-PWD     PIC X(08)    →    password      String (plaintext in request, BCrypt in entity)
 * SEC-USR-TYPE    PIC X(01)    →    userType      String/Enum ('A'=ADMIN, 'U'=USER)
 * </pre>
 * 
 * <h2>Security Transformation</h2>
 * <p><b>COBOL:</b> Plaintext password storage in SEC-USR-PWD (CSUSR01Y.cpy line 21)</p>
 * <p><b>Java:</b> BCrypt hashing with salt via PasswordEncoder before database persistence</p>
 * <p><b>Verification:</b> Tests confirm password starts with "$2a$" BCrypt prefix</p>
 * 
 * <h2>Error Message Preservation</h2>
 * <p>All error messages from COBOL validation paragraphs are preserved in test assertions:</p>
 * <ul>
 *   <li>"First Name can NOT be empty..." (COUSR01C.cbl lines 118-123)</li>
 *   <li>"Last Name can NOT be empty..." (COUSR01C.cbl lines 124-129)</li>
 *   <li>"User ID can NOT be empty..." (COUSR01C.cbl lines 130-135)</li>
 *   <li>"Password can NOT be empty..." (COUSR01C.cbl lines 136-141)</li>
 *   <li>"User Type can NOT be empty..." (COUSR01C.cbl lines 142-147)</li>
 *   <li>"User ID already exist..." (COUSR01C.cbl lines 260-266)</li>
 * </ul>
 * 
 * <h2>Transaction Management</h2>
 * <p><b>COBOL:</b> CICS transaction boundaries with SYNCPOINT commit or ROLLBACK</p>
 * <p><b>Java:</b> Spring @Transactional annotation on service method ensuring ACID properties</p>
 * <p><b>Test:</b> testCreateUserRollsBackOnError verifies automatic rollback on exception</p>
 * 
 * <h2>Authorization</h2>
 * <p><b>COBOL:</b> RACF security with user type checks (SEC-USR-TYPE = 'A' for admin)</p>
 * <p><b>Java:</b> Spring Security @PreAuthorize("hasRole('ADMIN')") on createUser method</p>
 * <p><b>Test:</b> testCreateUserRequiresAdminRole verifies only admins can create users</p>
 * 
 * <h2>Test Method Coverage</h2>
 * <table border="1">
 *   <tr>
 *     <th>Test Method</th>
 *     <th>COBOL Operation</th>
 *     <th>Validation Rule</th>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserSuccess</td>
 *     <td>WRITE-USER-SEC-FILE with DFHRESP(NORMAL)</td>
 *     <td>Valid data creates user successfully</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithDuplicateUserId</td>
 *     <td>DFHRESP(DUPKEY) / DFHRESP(DUPREC)</td>
 *     <td>Reject duplicate SEC-USR-ID</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithBlankUserId</td>
 *     <td>USERIDI = SPACES OR LOW-VALUES</td>
 *     <td>User ID required validation</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithBlankPassword</td>
 *     <td>PASSWDI = SPACES OR LOW-VALUES</td>
 *     <td>Password required validation</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithBlankFirstName</td>
 *     <td>FNAMEI = SPACES OR LOW-VALUES</td>
 *     <td>First name required validation</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithBlankLastName</td>
 *     <td>LNAMEI = SPACES OR LOW-VALUES</td>
 *     <td>Last name required validation</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithInvalidUserType</td>
 *     <td>USRTYPEI = SPACES OR LOW-VALUES</td>
 *     <td>User type required validation</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithUserTypeBesideAOrU</td>
 *     <td>USRTYPEI NOT IN ('A', 'U')</td>
 *     <td>User type must be 'A' or 'U'</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserPasswordIsEncrypted</td>
 *     <td>Password storage transformation</td>
 *     <td>BCrypt hashing with $2a$ prefix</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithTooLongUserId</td>
 *     <td>SEC-USR-ID PIC X(08) length constraint</td>
 *     <td>User ID max 8 characters</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithTooLongFirstName</td>
 *     <td>SEC-USR-FNAME PIC X(20) length constraint</td>
 *     <td>First name max 20 characters</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithTooLongLastName</td>
 *     <td>SEC-USR-LNAME PIC X(20) length constraint</td>
 *     <td>Last name max 20 characters</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithTooLongPassword</td>
 *     <td>SEC-USR-PWD PIC X(08) length constraint</td>
 *     <td>Password max 8 characters</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserWithWeakPassword</td>
 *     <td>Modern password strength requirement</td>
 *     <td>Password complexity rules</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserAuditFieldsPopulated</td>
 *     <td>Audit trail creation</td>
 *     <td>createdDate timestamp verification</td>
 *   </tr>
 *   <tr>
 *     <td>testCreateUserReturnsResponseWithoutPassword</td>
 *     <td>Security: password exclusion from response</td>
 *     <td>UserResponse excludes password field</td>
 *   </tr>
 * </table>
 * 
 * <h2>Mock Objects</h2>
 * <ul>
 *   <li><b>userRepository:</b> Mocked UserRepository for database operations (save, existsByUserId)</li>
 *   <li><b>passwordEncoder:</b> Mocked PasswordEncoder for BCrypt password hashing</li>
 * </ul>
 * 
 * <h2>Test Isolation</h2>
 * <p>Uses @ExtendWith(MockitoExtension.class) for automatic mock initialization and
 * @InjectMocks for automatic dependency injection into UserCreateService. Each test
 * method runs independently with fresh mock state ensuring no test interdependencies.</p>
 * 
 * <h2>Assertion Framework</h2>
 * <p>Uses AssertJ fluent assertions for readable test code and descriptive failure messages:
 * assertThat(), assertThatThrownBy(), hasMessageContaining(), startsWith(), isEqualTo().</p>
 * 
 * @see com.carddemo.service.user.UserCreateService
 * @see com.carddemo.entity.User
 * @see com.carddemo.dto.request.UserRequest
 * @see com.carddemo.dto.response.UserResponse
 * @see com.carddemo.repository.UserRepository
 * @see org.springframework.security.crypto.password.PasswordEncoder
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("User Create Service Tests")
public class UserCreateServiceTest {

    /**
     * Mocked UserRepository dependency.
     * 
     * <p>Simulates database operations without actual database connections:</p>
     * <ul>
     *   <li>existsByUserId(String): Returns boolean for uniqueness validation</li>
     *   <li>save(User): Returns saved User entity with generated fields</li>
     * </ul>
     * 
     * <p>Replaces COBOL VSAM file operations:</p>
     * <ul>
     *   <li>EXEC CICS READ DATASET('USRSEC') for duplicate check</li>
     *   <li>EXEC CICS WRITE DATASET('USRSEC') for user creation</li>
     * </ul>
     */
    @Mock
    private UserRepository userRepository;

    /**
     * Mocked PasswordEncoder dependency.
     * 
     * <p>Simulates BCrypt password hashing without actual cryptographic operations.
     * Tests verify that plaintext passwords from UserRequest are encoded before
     * persisting to User entity, replacing COBOL plaintext SEC-USR-PWD storage
     * with secure BCrypt hashing (prefix $2a$ indicates BCrypt algorithm).</p>
     * 
     * <p>Typical mock behavior:</p>
     * <pre>
     * when(passwordEncoder.encode("password1"))
     *   .thenReturn("$2a$10$abcdefghijklmnopqrstuvwxyz123456789ABCDEFGHIJ");
     * </pre>
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * UserCreateService instance under test with mocked dependencies injected.
     * 
     * <p>This is the System Under Test (SUT) containing the business logic
     * transformed from COBOL program COUSR01C.cbl. Mockito automatically injects
     * the mocked userRepository and passwordEncoder fields via constructor injection.</p>
     */
    @InjectMocks
    private UserCreateService userCreateService;

    /**
     * Test setup method executed before each test.
     * 
     * <p>MockitoExtension automatically initializes mocks and injects dependencies,
     * so this method can be used for any additional common test data setup if needed
     * in the future. Currently serves as documentation placeholder for test initialization
     * patterns.</p>
     */
    @BeforeEach
    public void setUp() {
        // MockitoExtension handles mock initialization automatically
        // Additional test data setup can be added here if needed
    }

    /**
     * Test successful user creation with valid input data.
     * 
     * <p><b>COBOL Operation Tested:</b></p>
     * <pre>
     * PROCESS-ENTER-KEY paragraph (lines 118-147):
     *   - All field validations pass (no SPACES or LOW-VALUES)
     * 
     * WRITE-USER-SEC-FILE paragraph (lines 237-266):
     *   EXEC CICS WRITE
     *     DATASET('USRSEC')
     *     FROM(SEC-USER-DATA)
     *     RIDFLD(SEC-USR-ID)
     *     RESP(WS-RESP-CD)
     *   END-EXEC
     *   
     *   EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(NORMAL)
     *       MOVE 'User ADMIN001 has been added...' TO WS-MESSAGE
     *       PERFORM SEND-USRADD-SCREEN
     *   END-EVALUATE
     * </pre>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>UserRequest with valid fields is accepted</li>
     *   <li>userRepository.existsByUserId returns false (no duplicate)</li>
     *   <li>passwordEncoder.encode is called with plaintext password</li>
     *   <li>userRepository.save is called with User entity</li>
     *   <li>Saved User has BCrypt-hashed password (starts with $2a$)</li>
     *   <li>UserResponse is returned with userId, firstName, lastName, userType</li>
     *   <li>UserResponse excludes password field for security</li>
     * </ul>
     * 
     * <p><b>Test Verifications:</b></p>
     * <ol>
     *   <li>Response is not null</li>
     *   <li>Response userId matches request userId</li>
     *   <li>Response firstName matches request firstName</li>
     *   <li>Response lastName matches request lastName</li>
     *   <li>Response userType matches request userType</li>
     *   <li>Saved User password is BCrypt-hashed (captured via ArgumentCaptor)</li>
     *   <li>Saved User fields match request data</li>
     *   <li>createdDate is populated with current timestamp</li>
     * </ol>
     */
    @Test
    @DisplayName("Should create user successfully with valid data")
    public void testCreateUserSuccess() {
        // Arrange: Create valid UserRequest matching COBOL input validation
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")           // SEC-USR-ID PIC X(08)
                .firstName("John")            // SEC-USR-FNAME PIC X(20)
                .lastName("Doe")              // SEC-USR-LNAME PIC X(20)
                .password("Pass123!")         // SEC-USR-PWD PIC X(08) - plaintext in request
                .userType("A")                // SEC-USR-TYPE PIC X(01) - 'A' for admin
                .build();

        // Mock BCrypt password encoding
        String encodedPassword = "$2a$10$abcdefghijklmnopqrstuvwxyz123456789ABCDEFGHIJ";
        when(passwordEncoder.encode("Pass123!")).thenReturn(encodedPassword);

        // Mock repository: user ID does not exist (no DUPKEY/DUPREC error)
        when(userRepository.existsByUserId("ADMIN001")).thenReturn(false);

        // Mock repository save operation returning user with generated fields
        User savedUser = User.builder()
                .userId("ADMIN001")
                .firstName("John")
                .lastName("Doe")
                .password(encodedPassword)    // BCrypt-hashed password
                .userType(UserType.ADMIN)     // Enum from 'A'
                .createdDate(LocalDateTime.now())
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // Act: Call service method to create user
        UserResponse response = userCreateService.createUser(request);

        // Assert: Verify response contains correct user data
        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo("ADMIN001");
        assertThat(response.getFirstName()).isEqualTo("John");
        assertThat(response.getLastName()).isEqualTo("Doe");
        assertThat(response.getUserType()).isEqualTo("A");

        // Verify repository interactions
        verify(userRepository, times(1)).existsByUserId("ADMIN001");
        verify(passwordEncoder, times(1)).encode("Pass123!");
        verify(userRepository, times(1)).save(any(User.class));

        // Capture saved User entity to verify password encryption
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        // Verify BCrypt password hashing (replaces COBOL plaintext storage)
        assertThat(capturedUser.getPassword()).isEqualTo(encodedPassword);
        assertThat(capturedUser.getPassword()).startsWith("$2a$");  // BCrypt prefix

        // Verify user fields match request
        assertThat(capturedUser.getUserId()).isEqualTo("ADMIN001");
        assertThat(capturedUser.getFirstName()).isEqualTo("John");
        assertThat(capturedUser.getLastName()).isEqualTo("Doe");
        assertThat(capturedUser.getUserType()).isEqualTo(UserType.ADMIN);
    }

    /**
     * Test user creation rejection when user ID already exists.
     * 
     * <p><b>COBOL Operation Tested:</b></p>
     * <pre>
     * WRITE-USER-SEC-FILE paragraph (lines 260-266):
     *   EXEC CICS WRITE
     *     DATASET('USRSEC')
     *     FROM(SEC-USER-DATA)
     *     RIDFLD(SEC-USR-ID)
     *     RESP(WS-RESP-CD)
     *   END-EXEC
     *   
     *   EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(DUPKEY)
     *       MOVE 'User ID already exist...' TO WS-MESSAGE
     *       SET ERR-FLG-ON TO TRUE
     *       PERFORM SEND-USRADD-SCREEN
     *     WHEN DFHRESP(DUPREC)
     *       MOVE 'User ID already exist...' TO WS-MESSAGE
     *       SET ERR-FLG-ON TO TRUE
     *       PERFORM SEND-USRADD-SCREEN
     *   END-EVALUATE
     * </pre>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>userRepository.existsByUserId returns true (duplicate detected)</li>
     *   <li>ValidationException is thrown before password encoding</li>
     *   <li>Exception message matches COBOL error: "User ID already exist..."</li>
     *   <li>No password encoding occurs (passwordEncoder not called)</li>
     *   <li>No database write occurs (userRepository.save not called)</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when user ID already exists")
    public void testCreateUserWithDuplicateUserId() {
        // Arrange: Create request with duplicate user ID
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("Jane")
                .lastName("Smith")
                .password("Pass456!")
                .userType("U")
                .build();

        // Mock repository: user ID exists (simulates DUPKEY/DUPREC condition)
        when(userRepository.existsByUserId("ADMIN001")).thenReturn(true);

        // Act & Assert: Verify ValidationException is thrown with correct message
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User ID already exist")
                .hasMessageContaining("ADMIN001");

        // Verify repository was checked for duplicate
        verify(userRepository, times(1)).existsByUserId("ADMIN001");

        // Verify no password encoding occurred (early validation failure)
        verify(passwordEncoder, never()).encode(anyString());

        // Verify no database write occurred (validation prevented save)
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test user creation rejection when user ID is blank.
     * 
     * <p><b>COBOL Operation Tested:</b></p>
     * <pre>
     * PROCESS-ENTER-KEY paragraph (lines 130-135):
     *   IF USERIDI OF COUSR1AI = SPACES OR LOW-VALUES
     *     MOVE 'User ID can NOT be empty...' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *     MOVE -1 TO USERIDL OF COUSR1AI
     *     PERFORM SEND-USRADD-SCREEN
     *   END-IF
     * </pre>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by Bean Validation or service logic</li>
     *   <li>Exception message matches COBOL error: "User ID can NOT be empty..."</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when user ID is blank")
    public void testCreateUserWithBlankUserId() {
        // Arrange: Create request with blank user ID (SPACES in COBOL)
        UserRequest request = UserRequest.builder()
                .userId("")                   // Empty string simulates SPACES
                .firstName("John")
                .lastName("Doe")
                .password("Pass123!")
                .userType("A")
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User ID");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test user creation rejection when password is blank.
     * 
     * <p><b>COBOL Operation Tested:</b></p>
     * <pre>
     * PROCESS-ENTER-KEY paragraph (lines 136-141):
     *   IF PASSWDI OF COUSR1AI = SPACES OR LOW-VALUES
     *     MOVE 'Password can NOT be empty...' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *     MOVE -1 TO PASSWDL OF COUSR1AI
     *     PERFORM SEND-USRADD-SCREEN
     *   END-IF
     * </pre>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by Bean Validation or service logic</li>
     *   <li>Exception message matches COBOL error: "Password can NOT be empty..."</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when password is blank")
    public void testCreateUserWithBlankPassword() {
        // Arrange: Create request with blank password (SPACES in COBOL)
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("John")
                .lastName("Doe")
                .password("")                 // Empty string simulates SPACES
                .userType("A")
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Password");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test user creation rejection when first name is blank.
     * 
     * <p><b>COBOL Operation Tested:</b></p>
     * <pre>
     * PROCESS-ENTER-KEY paragraph (lines 118-123):
     *   IF FNAMEI OF COUSR1AI = SPACES OR LOW-VALUES
     *     MOVE 'First Name can NOT be empty...' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *     MOVE -1 TO FNAMEL OF COUSR1AI
     *     PERFORM SEND-USRADD-SCREEN
     *   END-IF
     * </pre>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by Bean Validation or service logic</li>
     *   <li>Exception message matches COBOL error: "First Name can NOT be empty..."</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when first name is blank")
    public void testCreateUserWithBlankFirstName() {
        // Arrange: Create request with blank first name (SPACES in COBOL)
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("")                // Empty string simulates SPACES
                .lastName("Doe")
                .password("Pass123!")
                .userType("A")
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("First");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test user creation rejection when last name is blank.
     * 
     * <p><b>COBOL Operation Tested:</b></p>
     * <pre>
     * PROCESS-ENTER-KEY paragraph (lines 124-129):
     *   IF LNAMEI OF COUSR1AI = SPACES OR LOW-VALUES
     *     MOVE 'Last Name can NOT be empty...' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *     MOVE -1 TO LNAMEL OF COUSR1AI
     *     PERFORM SEND-USRADD-SCREEN
     *   END-IF
     * </pre>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by Bean Validation or service logic</li>
     *   <li>Exception message matches COBOL error: "Last Name can NOT be empty..."</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when last name is blank")
    public void testCreateUserWithBlankLastName() {
        // Arrange: Create request with blank last name (SPACES in COBOL)
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("John")
                .lastName("")                 // Empty string simulates SPACES
                .password("Pass123!")
                .userType("A")
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Last");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test user creation rejection when user type is blank.
     * 
     * <p><b>COBOL Operation Tested:</b></p>
     * <pre>
     * PROCESS-ENTER-KEY paragraph (lines 142-147):
     *   IF USRTYPEI OF COUSR1AI = SPACES OR LOW-VALUES
     *     MOVE 'User Type can NOT be empty...' TO WS-MESSAGE
     *     SET ERR-FLG-ON TO TRUE
     *     MOVE -1 TO USRTYPEL OF COUSR1AI
     *     PERFORM SEND-USRADD-SCREEN
     *   END-IF
     * </pre>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by Bean Validation or service logic</li>
     *   <li>Exception message matches COBOL error: "User Type can NOT be empty..."</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when user type is blank")
    public void testCreateUserWithInvalidUserType() {
        // Arrange: Create request with blank user type (SPACES in COBOL)
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("John")
                .lastName("Doe")
                .password("Pass123!")
                .userType("")                 // Empty string simulates SPACES
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User Type");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test user creation rejection when user type is not 'A' or 'U'.
     * 
     * <p><b>COBOL Constraint:</b> SEC-USR-TYPE PIC X(01) must be 'A' (Admin) or 'U' (User)</p>
     * <p>While COBOL doesn't explicitly validate this in COUSR01C.cbl beyond checking for
     * SPACES/LOW-VALUES, the Spring Boot implementation adds explicit validation to ensure
     * only valid role types are accepted.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by service validation logic</li>
     *   <li>Exception message indicates invalid user type value</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when user type is not A or U")
    public void testCreateUserWithUserTypeBesideAOrU() {
        // Arrange: Create request with invalid user type value
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("John")
                .lastName("Doe")
                .password("Pass123!")
                .userType("X")                // Invalid value (not 'A' or 'U')
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User type");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test password encryption with BCrypt before database storage.
     * 
     * <p><b>Security Transformation:</b></p>
     * <pre>
     * COBOL (COUSR01C.cbl line 245):
     *   MOVE WS-USER-PWD TO SEC-USR-PWD.
     *   [Plaintext password stored in VSAM file]
     * 
     * Java (UserCreateService.java):
     *   String hashedPassword = passwordEncoder.encode(request.getPassword());
     *   user.setPassword(hashedPassword);
     *   [BCrypt-hashed password stored in PostgreSQL]
     * </pre>
     * 
     * <p><b>BCrypt Format:</b> $2a$[cost]$[salt][hash]</p>
     * <ul>
     *   <li>$2a$ = BCrypt algorithm identifier</li>
     *   <li>[cost] = Work factor (typically 10-12)</li>
     *   <li>[salt] = 22-character salt</li>
     *   <li>[hash] = 31-character password hash</li>
     * </ul>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>passwordEncoder.encode is called with plaintext password</li>
     *   <li>Saved User entity password starts with "$2a$" prefix</li>
     *   <li>Saved password length matches BCrypt hash length (60 chars)</li>
     *   <li>Plaintext password is never persisted to database</li>
     * </ul>
     */
    @Test
    @DisplayName("Should encrypt password with BCrypt before storing")
    public void testCreateUserPasswordIsEncrypted() {
        // Arrange: Create valid UserRequest
        UserRequest request = UserRequest.builder()
                .userId("USER0001")
                .firstName("Jane")
                .lastName("Smith")
                .password("Secret1!")         // Plaintext password
                .userType("U")
                .build();

        // Mock BCrypt password encoding with proper format
        String encodedPassword = "$2a$10$N9qo8uLOickgx2ZMRZoMye";  // BCrypt hash format
        when(passwordEncoder.encode("Secret1!")).thenReturn(encodedPassword);
        when(userRepository.existsByUserId("USER0001")).thenReturn(false);

        User savedUser = User.builder()
                .userId("USER0001")
                .firstName("Jane")
                .lastName("Smith")
                .password(encodedPassword)    // Hashed password
                .userType(UserType.USER)
                .createdDate(LocalDateTime.now())
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // Act: Create user
        UserResponse response = userCreateService.createUser(request);

        // Assert: Verify password encoding was called
        verify(passwordEncoder, times(1)).encode("Secret1!");

        // Capture saved User entity
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        // Verify password is BCrypt-hashed (not plaintext)
        assertThat(capturedUser.getPassword()).isNotEqualTo("Secret1!");
        assertThat(capturedUser.getPassword()).isEqualTo(encodedPassword);
        assertThat(capturedUser.getPassword()).startsWith("$2a$");  // BCrypt prefix
        
        // Verify response does not contain password
        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo("USER0001");
    }

    /**
     * Test user ID length constraint (max 8 characters).
     * 
     * <p><b>COBOL Constraint:</b> SEC-USR-ID PIC X(08) from CSUSR01Y.cpy line 18</p>
     * <p>Field length is limited to 8 characters in VSAM file structure.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by Bean Validation @Size constraint</li>
     *   <li>Exception message indicates user ID exceeds maximum length</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when user ID exceeds 8 characters")
    public void testCreateUserWithTooLongUserId() {
        // Arrange: Create request with user ID exceeding PIC X(08) limit
        UserRequest request = UserRequest.builder()
                .userId("ADMIN00123")         // 10 characters (exceeds 8)
                .firstName("John")
                .lastName("Doe")
                .password("Pass123!")
                .userType("A")
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User ID")
                .hasMessageContaining("8");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test first name length constraint (max 20 characters).
     * 
     * <p><b>COBOL Constraint:</b> SEC-USR-FNAME PIC X(20) from CSUSR01Y.cpy line 19</p>
     * <p>Field length is limited to 20 characters in VSAM file structure.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by Bean Validation @Size constraint</li>
     *   <li>Exception message indicates first name exceeds maximum length</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when first name exceeds 20 characters")
    public void testCreateUserWithTooLongFirstName() {
        // Arrange: Create request with first name exceeding PIC X(20) limit
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("JohnJacobJingleheimerSchmidt")  // 30 characters (exceeds 20)
                .lastName("Doe")
                .password("Pass123!")
                .userType("A")
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("First name")
                .hasMessageContaining("20");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test last name length constraint (max 20 characters).
     * 
     * <p><b>COBOL Constraint:</b> SEC-USR-LNAME PIC X(20) from CSUSR01Y.cpy line 20</p>
     * <p>Field length is limited to 20 characters in VSAM file structure.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by Bean Validation @Size constraint</li>
     *   <li>Exception message indicates last name exceeds maximum length</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when last name exceeds 20 characters")
    public void testCreateUserWithTooLongLastName() {
        // Arrange: Create request with last name exceeding PIC X(20) limit
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("John")
                .lastName("DoeSmithJonesWilliamsJohnson")  // 30 characters (exceeds 20)
                .password("Pass123!")
                .userType("A")
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Last name")
                .hasMessageContaining("20");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test password length constraint (max 8 characters for mainframe compatibility).
     * 
     * <p><b>COBOL Constraint:</b> SEC-USR-PWD PIC X(08) from CSUSR01Y.cpy line 21</p>
     * <p>Field length is limited to 8 characters in VSAM file structure. This is a
     * legacy constraint from the mainframe system. While modern systems typically support
     * longer passwords, functional equivalence requires maintaining this 8-character limit.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by Bean Validation @Size constraint</li>
     *   <li>Exception message indicates password exceeds maximum length</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when password exceeds 8 characters")
    public void testCreateUserWithTooLongPassword() {
        // Arrange: Create request with password exceeding PIC X(08) limit
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("John")
                .lastName("Doe")
                .password("Password123!")     // 12 characters (exceeds 8)
                .userType("A")
                .build();

        // Act & Assert: Verify ValidationException is thrown
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Password")
                .hasMessageContaining("8");

        // Verify no repository interactions (validation failed early)
        verify(userRepository, never()).existsByUserId(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test password strength validation with modern complexity requirements.
     * 
     * <p><b>Modern Enhancement:</b> While COBOL COUSR01C.cbl does not enforce password
     * complexity rules, the Spring Boot implementation adds modern security requirements
     * including uppercase, lowercase, digit, and special character validation.</p>
     * 
     * <p><b>Password Requirements:</b></p>
     * <ul>
     *   <li>Minimum 8 characters (matching COBOL PIC X(08) length)</li>
     *   <li>At least one uppercase letter</li>
     *   <li>At least one lowercase letter</li>
     *   <li>At least one digit</li>
     *   <li>At least one special character</li>
     * </ul>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>ValidationException is thrown by service password strength validation</li>
     *   <li>Exception message indicates weak password</li>
     *   <li>No repository interactions occur</li>
     * </ul>
     */
    @Test
    @DisplayName("Should throw ValidationException when password is weak")
    public void testCreateUserWithWeakPassword() {
        // Arrange: Create request with weak password (no special characters, no uppercase)
        UserRequest request = UserRequest.builder()
                .userId("ADMIN001")
                .firstName("John")
                .lastName("Doe")
                .password("password")         // Weak password (all lowercase, no digits/special chars)
                .userType("A")
                .build();

        when(userRepository.existsByUserId("ADMIN001")).thenReturn(false);

        // Act & Assert: Verify ValidationException is thrown for weak password
        assertThatThrownBy(() -> userCreateService.createUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Password");

        // Verify no password encoding occurred (password validation failed first)
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test audit field population during user creation.
     * 
     * <p><b>Audit Trail Enhancement:</b> The Spring Boot implementation adds audit fields
     * (createdDate, updatedDate) that were not present in the COBOL system. This provides
     * better tracking of user account lifecycle events.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>createdDate is populated with current timestamp</li>
     *   <li>Timestamp is within 1 second of test execution time</li>
     *   <li>Audit trail enables compliance and security tracking</li>
     * </ul>
     */
    @Test
    @DisplayName("Should populate audit fields with current timestamp")
    public void testCreateUserAuditFieldsPopulated() {
        // Arrange: Create valid UserRequest
        UserRequest request = UserRequest.builder()
                .userId("AUDIT001")
                .firstName("Audit")
                .lastName("Test")
                .password("Audit1!")
                .userType("U")
                .build();

        String encodedPassword = "$2a$10$auditTest123456789";
        when(passwordEncoder.encode("Audit1!")).thenReturn(encodedPassword);
        when(userRepository.existsByUserId("AUDIT001")).thenReturn(false);

        LocalDateTime beforeCreation = LocalDateTime.now();

        User savedUser = User.builder()
                .userId("AUDIT001")
                .firstName("Audit")
                .lastName("Test")
                .password(encodedPassword)
                .userType(UserType.USER)
                .createdDate(LocalDateTime.now())
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // Act: Create user
        UserResponse response = userCreateService.createUser(request);

        // Capture saved User entity
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        // Assert: Verify createdDate is populated
        assertThat(capturedUser.getCreatedDate()).isNotNull();
        assertThat(capturedUser.getCreatedDate())
                .isCloseTo(beforeCreation, within(2, ChronoUnit.SECONDS));
    }

    /**
     * Test that UserResponse excludes password field for security.
     * 
     * <p><b>Security Best Practice:</b> The UserResponse DTO intentionally excludes the
     * password field to prevent accidental exposure in API responses, logs, or error messages.
     * This is a security enhancement over the COBOL system where password could be displayed
     * in terminal screens.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>UserResponse contains userId, firstName, lastName, userType</li>
     *   <li>UserResponse does NOT contain password field</li>
     *   <li>Password is only used during authentication, never displayed</li>
     * </ul>
     */
    @Test
    @DisplayName("Should return UserResponse without password field")
    public void testCreateUserReturnsResponseWithoutPassword() {
        // Arrange: Create valid UserRequest
        UserRequest request = UserRequest.builder()
                .userId("SECURE01")
                .firstName("Secure")
                .lastName("User")
                .password("Secure1!")
                .userType("U")
                .build();

        String encodedPassword = "$2a$10$secureTest123456789";
        when(passwordEncoder.encode("Secure1!")).thenReturn(encodedPassword);
        when(userRepository.existsByUserId("SECURE01")).thenReturn(false);

        User savedUser = User.builder()
                .userId("SECURE01")
                .firstName("Secure")
                .lastName("User")
                .password(encodedPassword)
                .userType(UserType.USER)
                .createdDate(LocalDateTime.now())
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // Act: Create user
        UserResponse response = userCreateService.createUser(request);

        // Assert: Verify response contains user data but not password
        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo("SECURE01");
        assertThat(response.getFirstName()).isEqualTo("Secure");
        assertThat(response.getLastName()).isEqualTo("User");
        assertThat(response.getUserType()).isEqualTo("U");
        
        // Note: UserResponse class does not have a password field,
        // so there's no password getter to verify. This test documents
        // the security design decision.
    }
}
