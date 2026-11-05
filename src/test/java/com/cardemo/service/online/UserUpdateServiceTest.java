/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
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

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.online.UserUpdateService.UserUpdateRequest;

import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserUpdateService} — verifying COBOL-to-Java parity
 * with the COUSR02C.cbl user update CICS transaction (CU02).
 *
 * <p>Covers all COBOL paragraph-mapped methods: updateUser() (MAIN-PARA line 82),
 * processEnterKey() (PROCESS-ENTER-KEY line 143), updateUserInfo()
 * (UPDATE-USER-INFO line 177), readUserSecFile() (READ-USER-SEC-FILE line 320),
 * and updateUserSecFile() (UPDATE-USER-SEC-FILE line 358).</p>
 *
 * <h2>Test Coverage Summary (12 tests)</h2>
 * <ul>
 *   <li>Tests 1–3: Success paths — name/type update, password change, password validation</li>
 *   <li>Test 4: Optimistic locking — @Version concurrent modification</li>
 *   <li>Test 5: Admin-only authorization gate</li>
 *   <li>Test 6: VSAM NOTFND (status '23') → RecordNotFoundException</li>
 *   <li>Tests 7–9: UserType validation — 88-level CDEMO-USER-TYPE conditions</li>
 *   <li>Tests 10–11: Required field validation (firstName, lastName)</li>
 *   <li>Test 12: Immutable primary key (SEC-USR-ID RIDFLD)</li>
 * </ul>
 *
 * <h2>CRITICAL Migration Change</h2>
 * <p>COBOL stored plaintext SEC-USR-PWD PIC X(08). Java stores BCrypt-hashed
 * passwords (60-72 chars). Multiple tests verify that passwordEncoder.encode()
 * is called when password is provided and never invoked on the error path.</p>
 *
 * @see UserUpdateService
 * @see com.cardemo.entity.UserSecurity
 * @see com.cardemo.repository.UserSecurityRepository
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserUpdateService — COUSR02C.cbl User Update Parity Tests")
class UserUpdateServiceTest {

    /** Mock USRSEC VSAM repository — stubs findById() and save(). */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Mock BCrypt encoder — replaces COBOL plaintext SEC-USR-PWD storage. */
    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    /** Mock COMMAREA session context — provides getUserType() for CU02 authorization. */
    @Mock
    private CardDemoContext cardDemoContext;

    /** Service under test — constructor-injected with all three mocks. */
    @InjectMocks
    private UserUpdateService userUpdateService;

    // ========================================================================
    // Constants for test data — realistic BCrypt hashes and user identifiers
    // ========================================================================

    /** Realistic BCrypt hash representing the existing password on the entity. */
    private static final String BCRYPT_HASH_OLD =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** BCrypt hash returned by mocked encoder for new password values. */
    private static final String BCRYPT_HASH_NEW =
            "$2a$10$dXJ3SW6G7P50lGmMQgel6uVktDQd2BZwZP90AVOMbIkqCnSKiRnKa";

    /** Test admin user ID — maps SEC-USR-ID PIC X(08). */
    private static final String ADMIN_USER_ID = "ADMIN01";

    /** Test target user ID — the user being updated. */
    private static final String TARGET_USER_ID = "TESTUSR1";

    // ========================================================================
    // Test 1: Success — Update Name and Type
    // Maps: MAIN-PARA → PROCESS-ENTER-KEY → UPDATE-USER-INFO → UPDATE-USER-SEC-FILE
    // ========================================================================

    /**
     * Verifies the full happy path: an admin updates a user's firstName, lastName,
     * and userType. Maps to COUSR02C.cbl: MAIN-PARA (line 82) → PROCESS-ENTER-KEY
     * (line 143) → UPDATE-USER-INFO (line 177) → UPDATE-USER-SEC-FILE (line 358).
     *
     * <p>Asserts all modified fields are saved, BCrypt encoding is applied to the
     * password, and the userId (immutable VSAM RIDFLD) remains unchanged.</p>
     */
    @Test
    @DisplayName("Test 1: Admin updates name and type — success path")
    void testUpdateUser_Success_UpdateNameAndType() {
        // Arrange — mock admin context (COMMAREA CDEMO-USRTYP-ADMIN = 'A')
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        // Create existing user fixture — pre-loaded from USRSEC VSAM dataset
        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.ADMIN);

        // Mock repository — READ-USER-SEC-FILE returns existing user
        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));

        // Mock BCrypt encoder — password always re-encoded on update
        when(passwordEncoder.encode("newpass1")).thenReturn(BCRYPT_HASH_NEW);

        // Mock repository save — UPDATE-USER-SEC-FILE REWRITE success
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act — create request with updated fields
        UserUpdateRequest request = new UserUpdateRequest(
                "Jane", "Smith", "newpass1", "U");
        UserSecurity result = userUpdateService.updateUser(TARGET_USER_ID, request);

        // Assert — verify encode() was called (not plaintext storage)
        verify(passwordEncoder, times(1)).encode("newpass1");

        // Assert — capture and verify the entity passed to save()
        ArgumentCaptor<UserSecurity> captor =
                ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository, times(1)).save(captor.capture());
        UserSecurity savedUser = captor.getValue();

        assertThat(savedUser.getFirstName()).isEqualTo("Jane");
        assertThat(savedUser.getLastName()).isEqualTo("Smith");
        assertThat(savedUser.getUserType()).isEqualTo(UserType.USER);
        assertThat(savedUser.getPassword()).isEqualTo(BCRYPT_HASH_NEW);
        assertThat(savedUser.getUserId()).isEqualTo(TARGET_USER_ID);

        // Assert — return value is non-null with correct fields
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(TARGET_USER_ID);
        assertThat(result.getUserType()).isEqualTo(UserType.USER);
    }

    // ========================================================================
    // Test 2: Password Change — BCrypt Encoding
    // Maps: UPDATE-USER-INFO → password change branch
    // CRITICAL: COBOL plaintext SEC-USR-PWD → BCrypt hash
    // ========================================================================

    /**
     * Verifies that when a new password is provided, {@code passwordEncoder.encode()}
     * is called and the saved entity contains the BCrypt hash — not plaintext.
     *
     * <p>Maps COUSR02C.cbl UPDATE-USER-INFO (lines 227-230): the original COBOL
     * compared plaintext {@code PASSWDI} with {@code SEC-USR-PWD}. In Java,
     * BCrypt encoding means the raw password is always re-encoded since the same
     * plaintext produces different hashes each time.</p>
     */
    @Test
    @DisplayName("Test 2: Password provided → BCrypt encode() called, hash persisted")
    void testUpdateUser_WithPasswordChange() {
        // Arrange — admin context
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        // Existing user with OLD password hash
        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.USER);

        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));

        // CRITICAL — encoder must be called with the new password
        when(passwordEncoder.encode("secureP@ss")).thenReturn(BCRYPT_HASH_NEW);

        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act — provide new password (different from existing)
        UserUpdateRequest request = new UserUpdateRequest(
                "John", "Doe", "secureP@ss", "U");
        UserSecurity result = userUpdateService.updateUser(TARGET_USER_ID, request);

        // Assert — verify encode() was called with the new plaintext password
        verify(passwordEncoder).encode("secureP@ss");

        // Assert — saved entity has the NEW BCrypt hash, NOT the old one
        ArgumentCaptor<UserSecurity> captor =
                ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity saved = captor.getValue();

        assertThat(saved.getPassword()).isEqualTo(BCRYPT_HASH_NEW);
        assertThat(saved.getPassword()).isNotEqualTo(BCRYPT_HASH_OLD);

        // Assert — update completed successfully
        assertThat(result).isNotNull();
        assertThat(result.getPassword()).isEqualTo(BCRYPT_HASH_NEW);
    }

    // ========================================================================
    // Test 3: Without Password Change — Blank Password Rejection
    // Maps: UPDATE-USER-INFO → password validation (PASSWDI = SPACES)
    // COBOL line 198: WHEN PASSWDI = SPACES OR LOW-VALUES
    // ========================================================================

    /**
     * Verifies that when a blank password is provided, the service throws
     * {@link ValidationException} before reaching the BCrypt encoder.
     *
     * <p>Maps COUSR02C.cbl UPDATE-USER-INFO (lines 198-203): blank PASSWDI
     * triggers the error path "Password can NOT be empty...". Since the
     * validation fails before the encode step, {@code passwordEncoder.encode()}
     * is NOT called, and the existing password on the entity is preserved
     * (the entity is never modified or saved).</p>
     */
    @Test
    @DisplayName("Test 3: Blank password → ValidationException, encode() NOT called")
    void testUpdateUser_WithoutPasswordChange() {
        // Arrange — admin context + existing user
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.USER);

        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));

        // Act & Assert — blank newPassword triggers validation failure
        // before reaching the password encoding step
        UserUpdateRequest request = new UserUpdateRequest(
                "John", "Doe", "", "U");

        assertThatThrownBy(
                () -> userUpdateService.updateUser(TARGET_USER_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Password can NOT be empty");

        // Verify — encode() was NEVER called (validation failed first)
        verify(passwordEncoder, never()).encode(any());

        // Verify — save() was NEVER called (no update persisted)
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));

        // Verify — existing password on entity is preserved (entity unchanged)
        assertThat(existingUser.getPassword()).isEqualTo(BCRYPT_HASH_OLD);
    }

    // ========================================================================
    // Test 4: Optimistic Lock Exception
    // Maps: UPDATE-USER-SEC-FILE → REWRITE fails (concurrent modification)
    // JPA @Version detects concurrent update → OptimisticLockException
    // ========================================================================

    /**
     * Verifies that when {@code userSecurityRepository.save()} throws an
     * {@link OptimisticLockException} due to concurrent modification, the
     * exception propagates to the caller.
     *
     * <p>Maps COUSR02C.cbl UPDATE-USER-SEC-FILE (line 358): the CICS
     * {@code READ UPDATE → REWRITE} pattern uses record-level locking.
     * In Java, JPA {@code @Version} detects concurrent modifications and
     * throws {@code OptimisticLockException}, matching the CICS "Unable to
     * Update User..." error path (lines 383-389).</p>
     */
    @Test
    @DisplayName("Test 4: Concurrent modification → OptimisticLockException propagated")
    void testUpdateUser_OptimisticLockException() {
        // Arrange — admin context + existing user
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.USER);

        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));

        // Password encoding still occurs before save
        when(passwordEncoder.encode("password")).thenReturn(BCRYPT_HASH_NEW);

        // Mock save() to throw OptimisticLockException — concurrent modification
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenThrow(new OptimisticLockException(
                        "Row was updated or deleted by another transaction"));

        // Act & Assert — exception propagates from updateUserSecFile
        UserUpdateRequest request = new UserUpdateRequest(
                "John", "Doe", "password", "U");

        assertThatThrownBy(
                () -> userUpdateService.updateUser(TARGET_USER_ID, request))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("Row was updated or deleted");

        // Verify — save() was attempted exactly once
        verify(userSecurityRepository, times(1)).save(any(UserSecurity.class));
    }

    // ========================================================================
    // Test 5: Non-Admin Rejected
    // Maps: CU02 is admin-only; CDEMO-USRTYP-USER cannot update users
    // ========================================================================

    /**
     * Verifies that non-admin users (UserType.USER) are rejected from the
     * user update function. COUSR02C.cbl is invoked via CICS transaction CU02
     * which is restricted to admin users (CDEMO-USRTYP-ADMIN in COMMAREA).
     *
     * <p>The service checks {@code cardDemoContext.getUserType() != UserType.ADMIN}
     * and throws {@code ValidationException} with field "authorization" before
     * any data access occurs.</p>
     */
    @Test
    @DisplayName("Test 5: Non-admin user rejected — admin-only transaction CU02")
    void testUpdateUser_NonAdminRejected() {
        // Arrange — regular user context (not admin)
        when(cardDemoContext.getUserType()).thenReturn(UserType.USER);
        when(cardDemoContext.getUserId()).thenReturn("REGUSR01");

        UserUpdateRequest request = new UserUpdateRequest(
                "Jane", "Smith", "password", "U");

        // Act & Assert — authorization failure before any data processing
        assertThatThrownBy(
                () -> userUpdateService.updateUser(TARGET_USER_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Admin access required");

        // Verify — no repository or encoder interaction occurred
        verify(userSecurityRepository, never()).findById(any());
        verify(userSecurityRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    // ========================================================================
    // Test 6: User Not Found
    // Maps: READ-USER-SEC-FILE → DFHRESP(NOTFND) / VSAM status '23'
    // ========================================================================

    /**
     * Verifies that when {@code findById()} returns {@code Optional.empty()},
     * a {@link RecordNotFoundException} is thrown matching COUSR02C.cbl
     * READ-USER-SEC-FILE paragraph (line 320) EVALUATE WHEN DFHRESP(NOTFND)
     * which sets "User ID NOT found..." message.
     */
    @Test
    @DisplayName("Test 6: User ID not found → RecordNotFoundException (VSAM status '23')")
    void testUpdateUser_UserNotFound() {
        // Arrange — admin context
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        // Mock findById returns empty — RESP=13 (NOTFND)
        when(userSecurityRepository.findById("UNKNOWN1"))
                .thenReturn(Optional.empty());

        UserUpdateRequest request = new UserUpdateRequest(
                "Jane", "Smith", "password", "U");

        // Act & Assert — RecordNotFoundException with user ID in message
        assertThatThrownBy(
                () -> userUpdateService.updateUser("UNKNOWN1", request))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("User not found: UNKNOWN1");

        // Verify — save() and encode() were never reached
        verify(userSecurityRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    // ========================================================================
    // Test 7: Valid UserType — Admin ('A')
    // Maps: USRTYPEI validation — 88-level CDEMO-USRTYP-ADMIN VALUE 'A'
    // ========================================================================

    /**
     * Verifies that userType code {@code "A"} is correctly resolved to
     * {@link UserType#ADMIN} via the {@code resolveUserType()} method.
     *
     * <p>Maps COBOL 88-level condition: {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}
     * from COCOM01Y.cpy. The user's type is updated from USER to ADMIN.</p>
     */
    @Test
    @DisplayName("Test 7: UserType 'A' → maps to UserType.ADMIN (88-level condition)")
    void testUpdateUser_ValidUserType_Admin() {
        // Arrange — admin context + existing user with type USER
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.USER);

        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));
        when(passwordEncoder.encode("password")).thenReturn(BCRYPT_HASH_NEW);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act — request with userType 'A' (ADMIN)
        UserUpdateRequest request = new UserUpdateRequest(
                "John", "Doe", "password", "A");
        UserSecurity result = userUpdateService.updateUser(TARGET_USER_ID, request);

        // Assert — entity userType was changed to ADMIN
        ArgumentCaptor<UserSecurity> captor =
                ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());

        assertThat(captor.getValue().getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(result.getUserType()).isEqualTo(UserType.ADMIN);
    }

    // ========================================================================
    // Test 8: Valid UserType — User ('U')
    // Maps: USRTYPEI validation — 88-level CDEMO-USRTYP-USER VALUE 'U'
    // ========================================================================

    /**
     * Verifies that userType code {@code "U"} is correctly resolved to
     * {@link UserType#USER} via the {@code resolveUserType()} method.
     *
     * <p>Maps COBOL 88-level condition: {@code 88 CDEMO-USRTYP-USER VALUE 'U'}
     * from COCOM01Y.cpy. The user's type is updated from ADMIN to USER.</p>
     */
    @Test
    @DisplayName("Test 8: UserType 'U' → maps to UserType.USER (88-level condition)")
    void testUpdateUser_ValidUserType_User() {
        // Arrange — admin context + existing user with type ADMIN
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.ADMIN);

        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));
        when(passwordEncoder.encode("password")).thenReturn(BCRYPT_HASH_NEW);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act — request with userType 'U' (USER)
        UserUpdateRequest request = new UserUpdateRequest(
                "John", "Doe", "password", "U");
        UserSecurity result = userUpdateService.updateUser(TARGET_USER_ID, request);

        // Assert — entity userType was changed to USER
        ArgumentCaptor<UserSecurity> captor =
                ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());

        assertThat(captor.getValue().getUserType()).isEqualTo(UserType.USER);
        assertThat(result.getUserType()).isEqualTo(UserType.USER);
    }

    // ========================================================================
    // Test 9: Invalid UserType
    // Maps: USRTYPEI validation — only 'A' or 'U' per 88-level conditions
    // ========================================================================

    /**
     * Verifies that an invalid userType code (not {@code 'A'} or {@code 'U'})
     * throws {@link ValidationException}. Maps COUSR02C.cbl UPDATE-USER-INFO
     * paragraph where the 88-level conditions on CDEMO-USER-TYPE restrict
     * values to 'A' (ADMIN) and 'U' (USER) only.
     */
    @Test
    @DisplayName("Test 9: Invalid userType 'X' → ValidationException (88-level reject)")
    void testUpdateUser_InvalidUserType() {
        // Arrange — admin context + existing user
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.USER);

        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));

        // Act & Assert — invalid userType 'X' triggers validation failure
        UserUpdateRequest request = new UserUpdateRequest(
                "John", "Doe", "password", "X");

        assertThatThrownBy(
                () -> userUpdateService.updateUser(TARGET_USER_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User Type must be");

        // Verify — save() was never called (validation failed)
        verify(userSecurityRepository, never()).save(any());
    }

    // ========================================================================
    // Test 10: Blank First Name
    // Maps: UPDATE-USER-INFO → FNAMEI validation
    // COBOL line 186: WHEN FNAMEI = SPACES OR LOW-VALUES
    // ========================================================================

    /**
     * Verifies that a blank firstName triggers {@link ValidationException}
     * matching COUSR02C.cbl UPDATE-USER-INFO paragraph (line 186):
     * {@code WHEN FNAMEI OF COUSR2AI = SPACES OR LOW-VALUES} →
     * "First Name can NOT be empty..." error.
     */
    @Test
    @DisplayName("Test 10: Blank firstName → ValidationException (FNAMEI = SPACES)")
    void testUpdateUser_BlankFirstName() {
        // Arrange — admin context + existing user
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.USER);

        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));

        // Act & Assert — blank firstName triggers first validation check
        UserUpdateRequest request = new UserUpdateRequest(
                "", "Smith", "password", "U");

        assertThatThrownBy(
                () -> userUpdateService.updateUser(TARGET_USER_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("First Name can NOT be empty");

        // Verify — no encode or save attempted
        verify(passwordEncoder, never()).encode(any());
        verify(userSecurityRepository, never()).save(any());
    }

    // ========================================================================
    // Test 11: Blank Last Name
    // Maps: UPDATE-USER-INFO → LNAMEI validation
    // COBOL line 192: WHEN LNAMEI = SPACES OR LOW-VALUES
    // ========================================================================

    /**
     * Verifies that a blank lastName triggers {@link ValidationException}
     * matching COUSR02C.cbl UPDATE-USER-INFO paragraph (line 192):
     * {@code WHEN LNAMEI OF COUSR2AI = SPACES OR LOW-VALUES} →
     * "Last Name can NOT be empty..." error.
     */
    @Test
    @DisplayName("Test 11: Blank lastName → ValidationException (LNAMEI = SPACES)")
    void testUpdateUser_BlankLastName() {
        // Arrange — admin context + existing user
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.USER);

        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));

        // Act & Assert — blank lastName triggers second validation check
        UserUpdateRequest request = new UserUpdateRequest(
                "Jane", "", "password", "U");

        assertThatThrownBy(
                () -> userUpdateService.updateUser(TARGET_USER_ID, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Last Name can NOT be empty");

        // Verify — no encode or save attempted
        verify(passwordEncoder, never()).encode(any());
        verify(userSecurityRepository, never()).save(any());
    }

    // ========================================================================
    // Test 12: User ID Immutable
    // Maps: SEC-USR-ID is the VSAM RIDFLD (primary key) — cannot be changed
    // ========================================================================

    /**
     * Verifies that the userId (SEC-USR-ID, the VSAM KSDS primary key) is
     * preserved unchanged through the entire update flow. The RIDFLD is
     * immutable and must match the original value when the entity is saved.
     *
     * <p>Uses {@code assertThatNoException()} to verify the update completes
     * successfully, then captures the entity passed to {@code save()} to
     * confirm the userId was not modified.</p>
     */
    @Test
    @DisplayName("Test 12: User ID (SEC-USR-ID RIDFLD) immutable through update")
    void testUpdateUser_UserIdImmutable() {
        // Arrange — admin context + existing user with specific userId
        when(cardDemoContext.getUserType()).thenReturn(UserType.ADMIN);
        when(cardDemoContext.getUserId()).thenReturn(ADMIN_USER_ID);

        UserSecurity existingUser = new UserSecurity(
                TARGET_USER_ID, "John", "Doe", BCRYPT_HASH_OLD, UserType.USER);

        when(userSecurityRepository.findById(TARGET_USER_ID))
                .thenReturn(Optional.of(existingUser));
        when(passwordEncoder.encode("password")).thenReturn(BCRYPT_HASH_NEW);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act — update all modifiable fields; userId should remain immutable
        UserUpdateRequest request = new UserUpdateRequest(
                "NewFirst", "NewLast", "password", "A");

        // Assert — operation completes without exception
        assertThatNoException().isThrownBy(
                () -> userUpdateService.updateUser(TARGET_USER_ID, request));

        // Assert — userId in saved entity equals original RIDFLD value
        ArgumentCaptor<UserSecurity> captor =
                ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity savedUser = captor.getValue();

        assertThat(savedUser.getUserId()).isEqualTo(TARGET_USER_ID);
        assertThat(savedUser.getFirstName()).isEqualTo("NewFirst");
        assertThat(savedUser.getLastName()).isEqualTo("NewLast");
        assertThat(savedUser.getUserType()).isEqualTo(UserType.ADMIN);
    }
}
