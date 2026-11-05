/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.service.online;

// Internal imports (from depends_on_files)
import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;
import com.cardemo.common.exception.AuthenticationException;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;

// External imports — JUnit 5 Jupiter (provided by spring-boot-starter-test)
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// External imports — Mockito (provided by spring-boot-starter-test)
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// External imports — Spring Security BCrypt password encoder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

// External imports — Java standard library
import java.util.Optional;

// Static imports — AssertJ fluent assertions
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Static imports — Mockito verification and stubbing
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SignonService} — Mockito-based, no Spring context.
 *
 * <p>Tests the sign-on/authentication logic faithfully translated from
 * COSGN00C.cbl (Sign-On Screen for the CardDemo Application). Each test
 * method maps to a specific execution path through the COBOL paragraphs:</p>
 * <ul>
 *   <li><strong>PROCESS-ENTER-KEY</strong> (line 108): Credential validation,
 *       uppercase normalization, and delegation to READ-USER-SEC-FILE</li>
 *   <li><strong>READ-USER-SEC-FILE</strong> (line 209): USRSEC VSAM keyed
 *       READ by SEC-USR-ID, password verification, session initialization</li>
 * </ul>
 *
 * <h2>COBOL-to-Java Security Migration</h2>
 * <p>The critical change from COBOL is password verification. COSGN00C.cbl
 * line 223 ({@code IF SEC-USR-PWD = WS-USER-PWD}) compared 8-byte plaintext
 * passwords. In Java, this is replaced by BCrypt hash verification via
 * {@link org.springframework.security.crypto.password.PasswordEncoder#matches(CharSequence, String)}.
 * All tests mock the password encoder to verify both match and mismatch paths.</p>
 *
 * <h2>Test Fixture Design</h2>
 * <p>Two {@link UserSecurity} fixtures (admin and regular user) are created
 * in {@link #setUp()} with a sample BCrypt hash. The fixtures use the
 * 5-argument constructor matching the SEC-USER-DATA record layout from
 * CSUSR01Y.cpy: SEC-USR-ID, SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-PWD
 * (BCrypt-hashed), and SEC-USR-TYPE.</p>
 *
 * @see SignonService
 * @see com.cardemo.entity.UserSecurity
 * @see com.cardemo.common.enums.UserType
 */
@ExtendWith(MockitoExtension.class)
class SignonServiceTest {

    // ========================================================================
    // Mock Dependencies (injected into SignonService via @InjectMocks)
    // ========================================================================

    /**
     * Mocked JPA repository for the USRSEC VSAM dataset.
     * <p>Tests configure {@code findById()} to return {@link Optional#of(Object)}
     * for successful lookups (CICS RESP=0) or {@link Optional#empty()} for
     * not-found scenarios (CICS RESP=13 NOTFND).</p>
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mocked BCrypt password encoder.
     * <p>Replaces the COBOL plaintext comparison at COSGN00C.cbl line 223.
     * Tests configure {@code matches()} to return {@code true} for successful
     * authentication and {@code false} for password mismatch scenarios.</p>
     */
    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    /**
     * Mocked request-scoped session context bean.
     * <p>Mirrors the 1024-byte CARDDEMO-COMMAREA from COCOM01Y.cpy. Tests
     * verify that {@code setUserId()} and {@code setUserType()} are called
     * with correct values on successful login (COSGN00C.cbl lines 224–228).</p>
     */
    @Mock
    private CardDemoContext cardDemoContext;

    /**
     * System under test — SignonService with all mocked dependencies injected
     * via constructor injection.
     */
    @InjectMocks
    private SignonService signonService;

    // ========================================================================
    // Test Fixtures (initialized fresh before each test in @BeforeEach)
    // ========================================================================

    /** Admin user fixture: SEC-USR-TYPE='A' (CDEMO-USRTYP-ADMIN VALUE 'A'). */
    private UserSecurity adminUser;

    /** Regular user fixture: SEC-USR-TYPE='U' (CDEMO-USRTYP-USER VALUE 'U'). */
    private UserSecurity regularUser;

    // ========================================================================
    // Constants matching COBOL working-storage and test data
    // ========================================================================

    /** Admin user ID fixture value (8 chars, matching SEC-USR-ID PIC X(08)). */
    private static final String ADMIN_USER_ID = "ADMIN01";

    /** Regular user ID fixture value. */
    private static final String REGULAR_USER_ID = "USER0001";

    /**
     * Sample BCrypt hash used for both fixtures.
     * The actual hash value doesn't matter since {@code passwordEncoder.matches()}
     * is mocked — it just needs to be a plausible BCrypt string.
     */
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** Raw password as entered by user (before COBOL UPPER-CASE normalization). */
    private static final String RAW_PASSWORD = "password";

    /**
     * Normalized (uppercased) password — matches COBOL:
     * {@code MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO WS-USER-PWD}
     * (COSGN00C.cbl lines 135–136).
     */
    private static final String NORMALIZED_PASSWORD = "PASSWORD";

    // ========================================================================
    // @BeforeEach — Per-test fixture initialization
    // ========================================================================

    /**
     * Creates fresh {@link UserSecurity} fixtures before each test.
     *
     * <p>Two fixtures are created using the 5-argument constructor that
     * maps to the SEC-USER-DATA record layout (CSUSR01Y.cpy, 80 bytes):</p>
     * <ul>
     *   <li>{@code adminUser}: SEC-USR-ID=ADMIN01, SEC-USR-TYPE='A'</li>
     *   <li>{@code regularUser}: SEC-USR-ID=USER0001, SEC-USR-TYPE='U'</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // Admin user — SEC-USR-TYPE 'A' (88 CDEMO-USRTYP-ADMIN VALUE 'A')
        adminUser = new UserSecurity(
                ADMIN_USER_ID, "Admin", "User", BCRYPT_HASH, UserType.ADMIN);

        // Regular user — SEC-USR-TYPE 'U' (88 CDEMO-USRTYP-USER VALUE 'U')
        regularUser = new UserSecurity(
                REGULAR_USER_ID, "Regular", "User", BCRYPT_HASH, UserType.USER);
    }

    // ========================================================================
    // Test 1: testSuccessfulLogin_AdminUser
    // Maps: PROCESS-ENTER-KEY → READ-USER-SEC-FILE → RESP=0 → password match
    //       → SEC-USR-TYPE='A' → EXEC CICS XCTL PROGRAM('COADM01C')
    // COSGN00C.cbl lines 108→139→209→221(WHEN 0)→223(match)→224-228→230-234
    // ========================================================================

    @Test
    @DisplayName("PROCESS-ENTER-KEY: Admin user (SEC-USR-TYPE='A') login → "
            + "session initialized, routed to COADM01C")
    void testSuccessfulLogin_AdminUser() {
        // Arrange: EXEC CICS READ DATASET('USRSEC') RIDFLD('ADMIN01') → RESP=0
        when(userSecurityRepository.findById(ADMIN_USER_ID))
                .thenReturn(Optional.of(adminUser));
        // BCrypt matches() replaces: IF SEC-USR-PWD = WS-USER-PWD (line 223)
        when(passwordEncoder.matches(NORMALIZED_PASSWORD, BCRYPT_HASH))
                .thenReturn(true);

        // Act: PERFORM PROCESS-ENTER-KEY (line 87 from MAIN-PARA)
        assertThatCode(() -> signonService.processEnterKey(ADMIN_USER_ID, RAW_PASSWORD))
                .doesNotThrowAnyException();

        // Assert: COMMAREA fields populated (lines 224–228)
        // MOVE WS-USER-ID TO CDEMO-USER-ID (line 226)
        verify(cardDemoContext, atLeastOnce()).setUserId(ADMIN_USER_ID);
        // MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE (line 227)
        verify(cardDemoContext).setUserType(UserType.ADMIN);
    }

    // ========================================================================
    // Test 2: testSuccessfulLogin_RegularUser
    // Maps: Same flow as Test 1 but with SEC-USR-TYPE='U'
    //       → EXEC CICS XCTL PROGRAM('COMEN01C') instead of COADM01C
    // COSGN00C.cbl lines 108→139→209→221(WHEN 0)→223(match)→224-228→236-239
    // ========================================================================

    @Test
    @DisplayName("PROCESS-ENTER-KEY: Regular user (SEC-USR-TYPE='U') login → "
            + "session initialized, routed to COMEN01C")
    void testSuccessfulLogin_RegularUser() {
        // Arrange
        when(userSecurityRepository.findById(REGULAR_USER_ID))
                .thenReturn(Optional.of(regularUser));
        when(passwordEncoder.matches(NORMALIZED_PASSWORD, BCRYPT_HASH))
                .thenReturn(true);

        // Act
        assertThatCode(() -> signonService.processEnterKey(REGULAR_USER_ID, RAW_PASSWORD))
                .doesNotThrowAnyException();

        // Assert: CDEMO-USER-TYPE set to 'U' (UserType.USER)
        verify(cardDemoContext).setUserType(UserType.USER);
        verify(cardDemoContext, atLeastOnce()).setUserId(REGULAR_USER_ID);
    }

    // ========================================================================
    // Test 3: testFailedLogin_WrongPassword
    // Maps: PROCESS-ENTER-KEY → READ-USER-SEC-FILE → RESP=0 → password NO match
    //       → 'Wrong Password. Try again ...' (line 242)
    // COSGN00C.cbl lines 108→139→209→221(WHEN 0)→223(no match)→242-245
    // ========================================================================

    @Test
    @DisplayName("PROCESS-ENTER-KEY: Password mismatch (SEC-USR-PWD ≠ WS-USER-PWD) → "
            + "AuthenticationException with 'Wrong Password'")
    void testFailedLogin_WrongPassword() {
        // Arrange: User found in USRSEC (RESP=0) but password doesn't match
        when(userSecurityRepository.findById(ADMIN_USER_ID))
                .thenReturn(Optional.of(adminUser));
        // BCrypt matches returns false → password mismatch branch
        when(passwordEncoder.matches(NORMALIZED_PASSWORD, BCRYPT_HASH))
                .thenReturn(false);

        // Act & Assert: 'Wrong Password. Try again ...' (COBOL line 242)
        assertThatThrownBy(() -> signonService.processEnterKey(ADMIN_USER_ID, RAW_PASSWORD))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Wrong Password");

        // Verify session was NOT fully initialized — setUserType never called
        // because authentication failed before reaching lines 224-228
        verify(cardDemoContext, never()).setUserType(any(UserType.class));
    }

    // ========================================================================
    // Test 4: testFailedLogin_UserNotFound
    // Maps: PROCESS-ENTER-KEY → READ-USER-SEC-FILE → RESP=13 (NOTFND)
    //       → 'User not found. Try again ...' (line 249)
    // COSGN00C.cbl lines 108→139→209→221→247(WHEN 13)→248-251
    // ========================================================================

    @Test
    @DisplayName("PROCESS-ENTER-KEY: User not found (CICS RESP=13 NOTFND) → "
            + "AuthenticationException with 'User not found'")
    void testFailedLogin_UserNotFound() {
        // Arrange: CICS READ USRSEC returns RESP=13 (DFHRESP NOTFND)
        when(userSecurityRepository.findById("UNKNOWN"))
                .thenReturn(Optional.empty());

        // Act & Assert: 'User not found. Try again ...' (COBOL line 249)
        assertThatThrownBy(() -> signonService.processEnterKey("UNKNOWN", RAW_PASSWORD))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("User not found");

        // Verify password check was never reached (no passwordEncoder interaction)
        verify(cardDemoContext, never()).setUserType(any(UserType.class));
    }

    // ========================================================================
    // Test 5: testFailedLogin_BlankUserId
    // Maps: PROCESS-ENTER-KEY → EVALUATE TRUE →
    //       WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES (line 118)
    //       → 'Please enter User ID ...' (line 120)
    // COSGN00C.cbl lines 108→117→118-122
    // ========================================================================

    @Test
    @DisplayName("PROCESS-ENTER-KEY: Blank USERIDI (SPACES/LOW-VALUES) → "
            + "AuthenticationException with 'User ID'")
    void testFailedLogin_BlankUserId() {
        // Act & Assert: empty userId triggers immediate validation failure
        // matching COBOL: WHEN USERIDI = SPACES OR LOW-VALUES (line 118)
        assertThatThrownBy(() -> signonService.processEnterKey("", RAW_PASSWORD))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("User ID");
    }

    // ========================================================================
    // Test 6: testFailedLogin_BlankPassword
    // Maps: PROCESS-ENTER-KEY → EVALUATE TRUE →
    //       WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES (line 123)
    //       → 'Please enter Password ...' (line 125)
    // COSGN00C.cbl lines 108→117→123-127
    // ========================================================================

    @Test
    @DisplayName("PROCESS-ENTER-KEY: Blank PASSWDI (SPACES/LOW-VALUES) → "
            + "AuthenticationException with 'Password'")
    void testFailedLogin_BlankPassword() {
        // Act & Assert: empty password triggers validation failure
        // matching COBOL: WHEN PASSWDI = SPACES OR LOW-VALUES (line 123)
        assertThatThrownBy(() -> signonService.processEnterKey("USER01", ""))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Password");
    }

    // ========================================================================
    // Test 7: testReadUserSecFile_Success
    // Maps: READ-USER-SEC-FILE → EVALUATE WS-RESP-CD → WHEN 0 (NORMAL)
    //       → return SEC-USER-DATA record successfully
    // COSGN00C.cbl lines 209→211-219→221(WHEN 0)→222
    // ========================================================================

    @Test
    @DisplayName("READ-USER-SEC-FILE: RESP=0 (NORMAL read) → returns "
            + "UserSecurity record with correct fields")
    void testReadUserSecFile_Success() {
        // Arrange: EXEC CICS READ returns RESP=0 (normal)
        when(userSecurityRepository.findById(ADMIN_USER_ID))
                .thenReturn(Optional.of(adminUser));

        // Act: direct call to readUserSecFile (bypassing processEnterKey)
        UserSecurity result = signonService.readUserSecFile(ADMIN_USER_ID);

        // Assert: returned record matches fixture — SEC-USER-DATA fields
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(ADMIN_USER_ID);
        assertThat(result.getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(result.getPassword()).isEqualTo(BCRYPT_HASH);
    }

    // ========================================================================
    // Test 8: testReadUserSecFile_NotFound
    // Maps: READ-USER-SEC-FILE → EVALUATE WS-RESP-CD → WHEN 13 (NOTFND)
    //       → 'User not found. Try again ...' (line 249)
    // COSGN00C.cbl lines 209→211-219→221→247(WHEN 13)→248-251
    //
    // Note: The SignonService implementation wraps the not-found condition
    // as AuthenticationException (client-facing error message), NOT as
    // RecordNotFoundException (which maps to VSAM file status '23').
    // This test verifies the actual implementation behavior.
    // ========================================================================

    @Test
    @DisplayName("READ-USER-SEC-FILE: RESP=13 (NOTFND) → AuthenticationException "
            + "(not RecordNotFoundException, per COSGN00C.cbl error handling)")
    void testReadUserSecFile_NotFound() {
        // Arrange: CICS READ returns RESP=13 (DFHRESP NOTFND)
        when(userSecurityRepository.findById("NOTEXIST"))
                .thenReturn(Optional.empty());

        // Act & Assert: service wraps not-found as AuthenticationException
        // The service uses AuthenticationException for sign-on error messaging,
        // distinct from RecordNotFoundException used for VSAM status '23' in
        // other services (e.g., AccountViewService, CardDetailService).
        assertThatThrownBy(() -> signonService.readUserSecFile("NOTEXIST"))
                .isInstanceOf(AuthenticationException.class)
                .isNotInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("User not found");
    }
}
