package com.cardemo.service.online;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;
import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.online.UserAddService.UserAddRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserAddService} — verifying COBOL-to-Java parity
 * with the COUSR01C.cbl user add CICS transaction (CU01).
 *
 * <p>Covers all COBOL paragraph-mapped methods: addUser() (MAIN-PARA),
 * processEnterKey() (PROCESS-ENTER-KEY validation), and writeUserSecFile()
 * (WRITE-USER-SEC-FILE with RESP=22 DUPREC detection).</p>
 *
 * <p>CRITICAL migration change: COBOL stored plaintext SEC-USR-PWD PIC X(08).
 * Java stores BCrypt-hashed passwords (60-72 chars). Multiple tests verify
 * that passwordEncoder.encode() is called and plaintext is never persisted.</p>
 *
 * @see UserAddService
 * @see com.cardemo.entity.UserSecurity
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAddService — COUSR01C.cbl User Add Parity Tests")
class UserAddServiceTest {

    /** Mock USRSEC VSAM repository — stubs existsById() and save(). */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Mock BCrypt encoder — replaces COBOL plaintext SEC-USR-PWD storage. */
    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    /** Mock COMMAREA session context — provides isAdmin() for CU01 authorization. */
    @Mock
    private CardDemoContext cardDemoContext;

    /** Service under test — constructor-injected with all three mocks. */
    @InjectMocks
    private UserAddService userAddService;

    // ========================================================================
    // Constants for test data
    // ========================================================================

    /** Realistic BCrypt hash for mock return values (60-char standard format). */
    private static final String BCRYPT_HASH_1 =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** Second BCrypt hash for admin-type test. */
    private static final String BCRYPT_HASH_2 =
            "$2a$10$dXJ3SW6G7P50lGmMQgel6uVktDQd2BZwZP90AVOMbIkqCnSKiRnKa";

    /** Third BCrypt hash for password-hashed test. */
    private static final String BCRYPT_HASH_3 =
            "$2a$10$EblZqNptyYvcLm8UoLOb2ePJ07UlThzLyGk07TrxwZFOzQhzIjWCm";

    // ========================================================================
    // Test 1: Success — Admin Creates Regular User (UserType.USER)
    // Maps: MAIN-PARA → PROCESS-ENTER-KEY → WRITE-USER-SEC-FILE → RESP=NORMAL
    // ========================================================================

    /**
     * Verifies the full happy path: an admin user creates a new regular user.
     * Maps to COUSR01C.cbl: MAIN-PARA → PROCESS-ENTER-KEY (all fields valid)
     * → WRITE-USER-SEC-FILE → DFHRESP(NORMAL) success.
     *
     * <p>Asserts BCrypt hashing replaces plaintext, UserType mapping from 'U',
     * and correct entity field population.</p>
     */
    @Test
    @DisplayName("Test 1: Admin creates regular user (UserType.USER) — success path")
    void testAddUser_Success_AdminCreatesUser() {
        // Arrange — mock admin context (COMMAREA CDEMO-USRTYP-ADMIN = 'A')
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");

        // Mock BCrypt encoder — CRITICAL change from COBOL plaintext SEC-USR-PWD
        when(passwordEncoder.encode("password")).thenReturn(BCRYPT_HASH_1);

        // Mock repository — no duplicate, save returns persisted entity
        when(userSecurityRepository.existsById("NEWUSR01")).thenReturn(false);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act — create user add request matching BMS map fields
        UserAddRequest request = new UserAddRequest(
                "NEWUSR01", "Jane", "Doe", "password", "U");
        UserSecurity result = userAddService.addUser(request);

        // Assert — BCrypt encode() was called (not plaintext storage)
        verify(passwordEncoder).encode("password");

        // Assert — save() was called with correctly hashed and typed entity
        ArgumentCaptor<UserSecurity> captor =
                ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity savedUser = captor.getValue();

        assertThat(savedUser.getUserId()).isEqualTo("NEWUSR01");
        assertThat(savedUser.getFirstName()).isEqualTo("Jane");
        assertThat(savedUser.getLastName()).isEqualTo("Doe");
        assertThat(savedUser.getPassword()).isEqualTo(BCRYPT_HASH_1);
        assertThat(savedUser.getUserType()).isEqualTo(UserType.USER);

        // Assert — return value is non-null and correctly typed
        assertThat(result).isNotNull();
        assertThat(result.getUserType()).isEqualTo(UserType.USER);
        assertThat(result.getUserId()).isEqualTo("NEWUSR01");
    }

    // ========================================================================
    // Test 2: Success — Admin Creates Admin User (UserType.ADMIN)
    // Maps: PROCESS-ENTER-KEY → USRTYPEI = 'A' → UserType.ADMIN
    // ========================================================================

    /**
     * Verifies admin can create another admin user with userType='A'.
     * Ensures 88-level CDEMO-USRTYP-ADMIN condition maps to UserType.ADMIN.
     */
    @Test
    @DisplayName("Test 2: Admin creates admin user (UserType.ADMIN) — success path")
    void testAddUser_Success_AdminType() {
        // Arrange
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");
        when(passwordEncoder.encode("secpass1")).thenReturn(BCRYPT_HASH_2);
        when(userSecurityRepository.existsById("ADMIN02")).thenReturn(false);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        UserAddRequest request = new UserAddRequest(
                "ADMIN02", "John", "Smith", "secpass1", "A");
        UserSecurity result = userAddService.addUser(request);

        // Assert — UserType correctly resolved from 'A' code
        assertThat(result).isNotNull();
        assertThat(result.getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(result.getUserId()).isEqualTo("ADMIN02");
        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getLastName()).isEqualTo("Smith");

        // Verify repository interaction
        verify(userSecurityRepository).existsById("ADMIN02");
        verify(userSecurityRepository).save(any(UserSecurity.class));
    }

    // ========================================================================
    // Test 3: Duplicate User Detection
    // Maps: WRITE-USER-SEC-FILE → RESP=22 (DUPKEY/DUPREC)
    //       → "User ID already exist..." error message
    // ========================================================================

    /**
     * Verifies duplicate user detection matches COUSR01C.cbl WRITE-USER-SEC-FILE
     * paragraph: when EXEC CICS WRITE returns RESP=22 (DUPKEY or DUPREC),
     * the error message "User ID already exist..." is displayed.
     *
     * <p>In Java, this maps to existsById() returning true before save(),
     * throwing DuplicateRecordException (VSAM file status '22').</p>
     */
    @Test
    @DisplayName("Test 3: Duplicate user ID → DuplicateRecordException (RESP=22 DUPREC)")
    void testAddUser_DuplicateUser() {
        // Arrange — admin context, valid fields, but user already exists
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");

        // Encode is called before duplicate check (in processEnterKey)
        when(passwordEncoder.encode("password")).thenReturn(BCRYPT_HASH_1);

        // existsById returns true — triggers RESP=22 DUPREC path
        when(userSecurityRepository.existsById("EXISTUSR")).thenReturn(true);

        UserAddRequest request = new UserAddRequest(
                "EXISTUSR", "Existing", "User", "password", "U");

        // Act & Assert — DuplicateRecordException with COBOL error message
        assertThatThrownBy(() -> userAddService.addUser(request))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessageContaining("User ID already exist");

        // Verify save() was NEVER called — duplicate detected before WRITE
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
    }

    // ========================================================================
    // Test 4: Non-Admin Rejected
    // Maps: CU01 is an admin-only CICS transaction; CDEMO-USRTYP-USER
    //       should not access user management functions
    // ========================================================================

    /**
     * Verifies that non-admin users (UserType.USER) are rejected from the
     * user add function. COUSR01C.cbl is invoked via CICS transaction CU01
     * which is restricted to admin users (CDEMO-USRTYP-ADMIN in COMMAREA).
     */
    @Test
    @DisplayName("Test 4: Non-admin user rejected — admin-only transaction CU01")
    void testAddUser_NonAdminRejected() {
        // Arrange — regular user context (not admin)
        when(cardDemoContext.isAdmin()).thenReturn(false);
        when(cardDemoContext.getUserId()).thenReturn("REGUSR01");
        when(cardDemoContext.getUserType()).thenReturn(UserType.USER);

        UserAddRequest request = new UserAddRequest(
                "NEWUSR01", "Jane", "Doe", "password", "U");

        // Act & Assert — authorization failure before any processing
        assertThatThrownBy(() -> userAddService.addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Only administrators can add users");

        // Verify no repository or encoder interaction occurred
        verify(userSecurityRepository, never()).existsById(any());
        verify(userSecurityRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    // ========================================================================
    // Test 5: Blank User ID
    // Maps: PROCESS-ENTER-KEY → USERIDI validation
    //       "User ID can NOT be empty..."
    // ========================================================================

    /**
     * Verifies blank userId triggers ValidationException matching
     * COUSR01C.cbl PROCESS-ENTER-KEY paragraph USERIDI empty check.
     */
    @Test
    @DisplayName("Test 5: Blank User ID → ValidationException (USERIDI empty)")
    void testAddUser_BlankUserId() {
        // Arrange — admin context passes, but userId is blank
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");

        UserAddRequest request = new UserAddRequest(
                "", "Jane", "Doe", "password", "U");

        // Act & Assert
        assertThatThrownBy(() -> userAddService.addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User ID");
    }

    // ========================================================================
    // Test 6: Blank Password
    // Maps: PROCESS-ENTER-KEY → PASSWDI validation
    //       "Password can NOT be empty..."
    // ========================================================================

    /**
     * Verifies blank password triggers ValidationException matching
     * COUSR01C.cbl PROCESS-ENTER-KEY paragraph PASSWDI empty check.
     */
    @Test
    @DisplayName("Test 6: Blank Password → ValidationException (PASSWDI empty)")
    void testAddUser_BlankPassword() {
        // Arrange — admin context passes, valid name/userId, blank password
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");

        UserAddRequest request = new UserAddRequest(
                "NEWUSR01", "Jane", "Doe", "", "U");

        // Act & Assert
        assertThatThrownBy(() -> userAddService.addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Password");
    }

    // ========================================================================
    // Test 7: Blank First Name
    // Maps: PROCESS-ENTER-KEY → FNAMEI validation (checked FIRST in COBOL)
    //       "First Name can NOT be empty..."
    // ========================================================================

    /**
     * Verifies blank firstName triggers ValidationException matching
     * COUSR01C.cbl PROCESS-ENTER-KEY paragraph FNAMEI empty check.
     * Note: firstName is validated FIRST in COBOL EVALUATE order.
     */
    @Test
    @DisplayName("Test 7: Blank First Name → ValidationException (FNAMEI empty)")
    void testAddUser_BlankFirstName() {
        // Arrange — admin context passes, but firstName is blank
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");

        UserAddRequest request = new UserAddRequest(
                "NEWUSR01", "", "Doe", "password", "U");

        // Act & Assert
        assertThatThrownBy(() -> userAddService.addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("First Name");
    }

    // ========================================================================
    // Test 8: Blank Last Name
    // Maps: PROCESS-ENTER-KEY → LNAMEI validation
    //       "Last Name can NOT be empty..."
    // ========================================================================

    /**
     * Verifies blank lastName triggers ValidationException matching
     * COUSR01C.cbl PROCESS-ENTER-KEY paragraph LNAMEI empty check.
     */
    @Test
    @DisplayName("Test 8: Blank Last Name → ValidationException (LNAMEI empty)")
    void testAddUser_BlankLastName() {
        // Arrange — admin context passes, valid firstName, blank lastName
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");

        UserAddRequest request = new UserAddRequest(
                "NEWUSR01", "Jane", "", "password", "U");

        // Act & Assert
        assertThatThrownBy(() -> userAddService.addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Last Name");
    }

    // ========================================================================
    // Test 9: Invalid User Type
    // Maps: PROCESS-ENTER-KEY → USRTYPEI validation
    //       Only 'A' (Admin) or 'U' (User) are valid — from 88-level conditions
    // ========================================================================

    /**
     * Verifies invalid userType code 'X' triggers ValidationException.
     * Maps to COUSR01C.cbl EVALUATE TRUE for USRTYPEI where only
     * 88-level condition values 'A' (CDEMO-USRTYP-ADMIN) and 'U'
     * (CDEMO-USRTYP-USER) are accepted.
     */
    @Test
    @DisplayName("Test 9: Invalid User Type 'X' → ValidationException")
    void testAddUser_InvalidUserType() {
        // Arrange — admin context, valid fields except userType
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");

        UserAddRequest request = new UserAddRequest(
                "NEWUSR01", "Jane", "Doe", "password", "X");

        // Act & Assert — 'X' is not 'A' or 'U'
        assertThatThrownBy(() -> userAddService.addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User Type");
    }

    // ========================================================================
    // Test 10: User ID Max Length Exceeded
    // Maps: SEC-USR-ID PIC X(08) — maximum 8 characters
    // ========================================================================

    /**
     * Verifies userId exceeding 8 characters (SEC-USR-ID PIC X(08) limit)
     * triggers ValidationException. The COBOL field definition constrains
     * the user ID to exactly 8 bytes; Java enforces this via length check.
     */
    @Test
    @DisplayName("Test 10: User ID exceeds 8 chars → ValidationException (PIC X(08))")
    void testAddUser_UserIdMaxLength() {
        // Arrange — admin context, userId is 9 chars (exceeds PIC X(08))
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");

        UserAddRequest request = new UserAddRequest(
                "TOOLONGID", "Jane", "Doe", "password", "U");

        // Act & Assert — 9-char userId exceeds 8-char max
        assertThatThrownBy(() -> userAddService.addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User ID");
    }

    // ========================================================================
    // Test 11: Password Is BCrypt-Hashed (NOT Plaintext)
    // CRITICAL MIGRATION CHANGE: COBOL stored plaintext SEC-USR-PWD PIC X(08)
    // Java stores BCrypt hash (60-72 chars) via BCryptPasswordEncoder
    // ========================================================================

    /**
     * CRITICAL test verifying the most important security improvement in this
     * migration: passwords are BCrypt-hashed before persistence.
     *
     * <p>COBOL stored passwords in plaintext: SEC-USR-PWD PIC X(08).
     * Java uses BCryptPasswordEncoder.encode() to produce a 60-char hash
     * that is stored in the database instead of the plaintext input.</p>
     *
     * <p>This test explicitly verifies:
     * <ol>
     *   <li>encode() is called exactly once with the plaintext password</li>
     *   <li>The saved entity's password is the BCrypt hash</li>
     *   <li>The saved entity's password is NOT the plaintext input</li>
     * </ol>
     * </p>
     */
    @Test
    @DisplayName("Test 11: Password is BCrypt-hashed, not stored as plaintext")
    void testAddUser_PasswordIsHashed() {
        // Arrange — standard admin context
        when(cardDemoContext.isAdmin()).thenReturn(true);
        when(cardDemoContext.getUserId()).thenReturn("ADMIN01");

        String plaintextPassword = "clearPwd";
        when(passwordEncoder.encode(plaintextPassword)).thenReturn(BCRYPT_HASH_3);
        when(userSecurityRepository.existsById("HASHTEST")).thenReturn(false);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserAddRequest request = new UserAddRequest(
                "HASHTEST", "Hash", "Test", plaintextPassword, "U");

        // Act
        UserSecurity result = userAddService.addUser(request);

        // Assert — encode() called exactly once with the plaintext password
        verify(passwordEncoder, times(1)).encode(plaintextPassword);

        // Assert — returned entity's password is the BCrypt hash, NOT plaintext
        assertThat(result.getPassword()).isEqualTo(BCRYPT_HASH_3);
        assertThat(result.getPassword()).isNotEqualTo(plaintextPassword);

        // Assert — captured entity passed to save() also has hashed password
        ArgumentCaptor<UserSecurity> captor =
                ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity persistedEntity = captor.getValue();

        assertThat(persistedEntity.getPassword()).isEqualTo(BCRYPT_HASH_3);
        assertThat(persistedEntity.getPassword()).isNotEqualTo(plaintextPassword);
    }
}
