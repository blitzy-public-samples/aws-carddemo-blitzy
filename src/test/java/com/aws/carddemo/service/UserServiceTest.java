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
package com.aws.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link UserService}, the Java
 * re-platform of the four CardDemo COBOL user-maintenance programs
 * {@code COUSR00C} (List Users, CICS {@code CU00}), {@code COUSR01C} (Add User,
 * {@code CU01}), {@code COUSR02C} (Update User, {@code CU02}) and {@code COUSR03C}
 * (Delete User, {@code CU03}).
 *
 * <p>The service has two collaborators &mdash; a {@link UserSecurityRepository} and a
 * {@link PasswordEncoder} &mdash; both mocked here so the tests exercise the service's
 * own behavior in isolation. There is <em>no</em> Spring context, <em>no</em> database
 * and <em>no</em> Testcontainers: the system under test is constructed directly through
 * its constructor in {@link #setUp()} (AAP &sect;0.9.2 unit-test constraint,
 * &sect;0.4.2 constructor injection).</p>
 *
 * <h2>Behavioral-parity contract locked down here</h2>
 * <ul>
 *   <li><strong>ADD mandatory-field order</strong> ({@code COUSR01C PROCESS-ENTER-KEY})
 *       &mdash; the five edits short-circuit on the first empty field in the exact
 *       order first name &rarr; last name &rarr; user id &rarr; password &rarr; user
 *       type, each yielding the verbatim COBOL {@code WS-MESSAGE} literal, and no
 *       record is written when an edit fails.</li>
 *   <li><strong>UPDATE mandatory-field order</strong> ({@code COUSR02C UPDATE-USER-INFO})
 *       &mdash; the same edits but with the user id checked <em>first</em>.</li>
 *   <li><strong>Credential hardening</strong> (documented deviation, AAP &sect;0.7 L1)
 *       &mdash; the raw password is upper-cased (3270 parity) and one-way hashed by the
 *       injected {@link PasswordEncoder} before it reaches the entity; the raw value is
 *       never persisted and never placed in a caller-visible message.</li>
 *   <li><strong>Typed data-layer outcomes</strong> &mdash; a duplicate id on add raises
 *       the CardDemo {@link DuplicateKeyException} (never Spring Data's same-named type)
 *       and a missing id on update/delete raises {@link RecordNotFoundException}, both
 *       carrying the verbatim COBOL messages.</li>
 *   <li><strong>Ascending browse</strong> ({@code COUSR00C STARTBR}/{@code READNEXT})
 *       &mdash; listing returns a {@link Page} ordered by {@code secUsrId} ascending,
 *       optionally positioned at an inclusive start key.</li>
 * </ul>
 *
 * <p>The asserted message literals mirror the private constants of the authored
 * {@link UserService} verbatim (they are not visible for import) so any drift in the
 * production strings fails these tests.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — COUSR00C/01C/02C/03C user-maintenance parity")
public class UserServiceTest {

    // ------------------------------------------------------------------
    // Verbatim caller-visible message literals (COBOL WS-MESSAGE strings),
    // mirroring the private constants of the authored UserService.
    // ------------------------------------------------------------------

    /** Empty first-name edit message ({@code COUSR01C} / {@code COUSR02C}). */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Empty last-name edit message. */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** Empty user-id edit message. */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Empty password edit message. */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** Empty user-type edit message. */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** Duplicate-key message for add ({@code COUSR01C WRITE-USER-SEC-FILE}). */
    private static final String MSG_USER_ID_ALREADY_EXISTS = "User ID already exist...";

    /** Generic add-failure message ({@code COUSR01C} {@code WHEN OTHER}). */
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    /** Record-not-found message for update and delete. */
    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    /** "Nothing changed" message for update ({@code COUSR02C UPDATE-USER-INFO}). */
    private static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    /** Success message for a completed add ({@code "User <id> has been added ..."}). */
    private static final String MSG_ADDED = "User USER0001 has been added ...";

    /** Success message for a completed update. */
    private static final String MSG_UPDATED = "User USER0001 has been updated ...";

    /** Success message for a completed delete. */
    private static final String MSG_DELETED = "User USER0001 has been deleted ...";

    // ------------------------------------------------------------------
    // Test fixtures. All password-like values are obvious NON-real
    // placeholders (never a real hash or secret) per AAP &sect;0.9.3.
    // ------------------------------------------------------------------

    /** A representative user id, already upper-cased as it is stored. */
    private static final String USER_ID = "USER0001";

    /** A raw (unhashed) password supplied by a caller. */
    private static final String RAW_PASSWORD = "plainpw";

    /**
     * The raw password after the service's 3270 upper-case normalization; this is the
     * exact argument the {@link PasswordEncoder} receives.
     */
    private static final String RAW_PASSWORD_UPPER = "PLAINPW";

    /** A placeholder BCrypt-shaped hash returned by the mocked encoder on add. */
    private static final String ENCODED_PASSWORD = "$2a$10$PLACEHOLDERplaceholderPLACEHOm";

    /** A raw replacement password supplied to update. */
    private static final String NEW_RAW_PASSWORD = "newpw";

    /** The update raw password after upper-case normalization. */
    private static final String NEW_RAW_PASSWORD_UPPER = "NEWPW";

    /** A placeholder hash standing in for a previously stored credential. */
    private static final String STORED_HASH = "$2a$10$STOREDstoredSTOREDstoredSTOREDe";

    /** A placeholder hash returned by the mocked encoder on update. */
    private static final String NEW_ENCODED_PASSWORD = "$2a$10$UPDATEDupdatedUPDATEDupdatedUe";

    /** Mocked data-access collaborator standing in for the {@code user_security} table. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** Mocked password encoder standing in for the security layer's BCrypt encoder. */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** System under test, wired via explicit constructor injection (no Spring context). */
    private UserService service;

    /**
     * Constructs a fresh system under test before each test using the exact
     * constructor argument order of the authored {@link UserService}.
     */
    @BeforeEach
    void setUp() {
        service = new UserService(userSecurityRepository, passwordEncoder);
    }

    /**
     * Builds a {@link UserSecurity} fixture through the entity's business-field
     * constructor.
     *
     * @param id        the primary-key user id (already upper-cased, as stored)
     * @param firstName the stored first name
     * @param lastName  the stored last name
     * @param pwdHash   the stored (hashed) password placeholder
     * @param type      the single-character role code ({@code "A"} or {@code "U"})
     * @return a populated, unmanaged {@link UserSecurity} instance
     */
    private static UserSecurity user(String id,
                                     String firstName,
                                     String lastName,
                                     String pwdHash,
                                     String type) {
        return new UserSecurity(id, firstName, lastName, pwdHash, type);
    }

    // ==================================================================
    // Construction — constructor injection contract (no field injection).
    // ==================================================================

    @Test
    @DisplayName("constructor rejects a null repository")
    void constructorRejectsNullRepository() {
        assertThatThrownBy(() -> new UserService(null, passwordEncoder))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userSecurityRepository");
    }

    @Test
    @DisplayName("constructor rejects a null password encoder")
    void constructorRejectsNullPasswordEncoder() {
        assertThatThrownBy(() -> new UserService(userSecurityRepository, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("passwordEncoder");
    }

    // ==================================================================
    // ADD (COUSR01C) — mandatory-field edits, short-circuit order.
    // ==================================================================

    /**
     * Scenarios for the {@code COUSR01C PROCESS-ENTER-KEY} mandatory-field edits. The
     * first five rows are <em>cascading</em>: each keeps the earlier fields valid and
     * empties the field under test (plus the ones after it), proving the exact
     * short-circuit order first name &rarr; last name &rarr; user id &rarr; password
     * &rarr; user type. The remaining rows prove that {@code null}, an empty string and
     * an all-whitespace string are each treated as empty, and that a space or a
     * low-value ({@code '\u0000'}) empties the single-character user type.
     *
     * @return the argument rows {@code (userId, firstName, lastName, rawPassword,
     *         userType, expectedMessage)}
     */
    static Stream<Arguments> addEmptyFieldScenarios() {
        return Stream.of(
                // Cascading order: first empty field wins.
                Arguments.of(null, null, null, null, ' ', MSG_FIRST_NAME_EMPTY),
                Arguments.of(null, "Jane", null, null, ' ', MSG_LAST_NAME_EMPTY),
                Arguments.of(null, "Jane", "Doe", null, ' ', MSG_USER_ID_EMPTY),
                Arguments.of(USER_ID, "Jane", "Doe", null, ' ', MSG_PASSWORD_EMPTY),
                Arguments.of(USER_ID, "Jane", "Doe", RAW_PASSWORD, ' ', MSG_USER_TYPE_EMPTY),
                // Whitespace is empty (COBOL = SPACES).
                Arguments.of(USER_ID, "   ", "Doe", RAW_PASSWORD, 'U', MSG_FIRST_NAME_EMPTY),
                Arguments.of(USER_ID, "Jane", "   ", RAW_PASSWORD, 'U', MSG_LAST_NAME_EMPTY),
                Arguments.of("   ", "Jane", "Doe", RAW_PASSWORD, 'U', MSG_USER_ID_EMPTY),
                Arguments.of(USER_ID, "Jane", "Doe", "   ", 'U', MSG_PASSWORD_EMPTY),
                // Empty string is empty.
                Arguments.of(USER_ID, "", "Doe", RAW_PASSWORD, 'U', MSG_FIRST_NAME_EMPTY),
                // Low-value empties the single-character type (COBOL = LOW-VALUES).
                Arguments.of(USER_ID, "Jane", "Doe", RAW_PASSWORD, '\u0000', MSG_USER_TYPE_EMPTY));
    }

    @ParameterizedTest(name = "[{index}] first-empty field yields \"{5}\"")
    @MethodSource("addEmptyFieldScenarios")
    @DisplayName("addUser short-circuits on the first empty mandatory field and persists nothing")
    void addUserShortCircuitsOnFirstEmptyField(String userId,
                                               String firstName,
                                               String lastName,
                                               String rawPassword,
                                               char userType,
                                               String expectedMessage) {
        UserService.UserResult result =
                service.addUser(userId, firstName, lastName, rawPassword, userType);

        assertThat(result.success()).isFalse();
        assertThat(result.user()).isNull();
        assertThat(result.message()).isEqualTo(expectedMessage);
        // The edit short-circuits before any collaborator is reached.
        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("addUser persists the BCrypt-encoded password (never the raw value) and returns the success message")
    void addUserPersistsEncodedPasswordAndSucceeds() {
        when(userSecurityRepository.existsById(USER_ID)).thenReturn(false);
        // The service upper-cases the raw password (3270 parity) BEFORE encoding.
        when(passwordEncoder.encode(RAW_PASSWORD_UPPER)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, UserSecurity.class));

        UserService.UserResult result =
                service.addUser(USER_ID, "Jane", "Doe", RAW_PASSWORD, 'U');

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).saveAndFlush(captor.capture());
        UserSecurity persisted = captor.getValue();

        assertThat(persisted.getSecUsrId()).isEqualTo(USER_ID);
        assertThat(persisted.getSecUsrFname()).isEqualTo("Jane");
        assertThat(persisted.getSecUsrLname()).isEqualTo("Doe");
        assertThat(persisted.getSecUsrType()).isEqualTo("U");
        // The stored credential is the encoder output, never the raw or upper-cased raw.
        assertThat(persisted.getSecUsrPwd()).isEqualTo(ENCODED_PASSWORD);

        assertThat(result.success()).isTrue();
        assertThat(result.user()).isSameAs(persisted);
        assertThat(result.message()).isEqualTo(MSG_ADDED);
    }

    @Test
    @DisplayName("addUser never persists or echoes the raw password in cleartext")
    void addUserNeverLeaksRawPassword() {
        when(userSecurityRepository.existsById(USER_ID)).thenReturn(false);
        when(passwordEncoder.encode(RAW_PASSWORD_UPPER)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, UserSecurity.class));

        UserService.UserResult result =
                service.addUser(USER_ID, "Jane", "Doe", RAW_PASSWORD, 'U');

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getSecUsrPwd())
                .isNotEqualTo(RAW_PASSWORD)
                .isNotEqualTo(RAW_PASSWORD_UPPER);
        assertThat(result.message())
                .doesNotContain(RAW_PASSWORD)
                .doesNotContain(RAW_PASSWORD_UPPER);
    }

    @Test
    @DisplayName("addUser throws the project DuplicateKeyException when the id already exists (pre-check)")
    void addUserRejectsDuplicateIdOnPreCheck() {
        when(userSecurityRepository.existsById(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.addUser(USER_ID, "Jane", "Doe", RAW_PASSWORD, 'U'))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessage(MSG_USER_ID_ALREADY_EXISTS);

        // No write and no hashing occur once the duplicate is detected.
        verify(userSecurityRepository, never()).saveAndFlush(any(UserSecurity.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("addUser maps a persistence DataIntegrityViolationException to the project DuplicateKeyException")
    void addUserMapsIntegrityViolationToDuplicate() {
        when(userSecurityRepository.existsById(USER_ID)).thenReturn(false);
        when(passwordEncoder.encode(RAW_PASSWORD_UPPER)).thenReturn(ENCODED_PASSWORD);
        DataIntegrityViolationException cause =
                new DataIntegrityViolationException("duplicate key value violates unique constraint");
        when(userSecurityRepository.saveAndFlush(any(UserSecurity.class))).thenThrow(cause);

        assertThatThrownBy(() -> service.addUser(USER_ID, "Jane", "Doe", RAW_PASSWORD, 'U'))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessage(MSG_USER_ID_ALREADY_EXISTS)
                .hasCause(cause);
    }

    @Test
    @DisplayName("addUser reproduces the COBOL WHEN OTHER branch as an unsuccessful \"Unable to Add User...\" result")
    void addUserReturnsUnableToAddOnGenericDataAccessFailure() {
        when(userSecurityRepository.existsById(USER_ID)).thenReturn(false);
        when(passwordEncoder.encode(RAW_PASSWORD_UPPER)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.saveAndFlush(any(UserSecurity.class)))
                .thenThrow(new DataAccessResourceFailureException("database is unavailable"));

        UserService.UserResult result =
                service.addUser(USER_ID, "Jane", "Doe", RAW_PASSWORD, 'U');

        assertThat(result.success()).isFalse();
        assertThat(result.user()).isNull();
        assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_ADD);
    }

    // ==================================================================
    // UPDATE (COUSR02C) — user id checked FIRST, then the other edits.
    // ==================================================================

    /**
     * Scenarios for the {@code COUSR02C UPDATE-USER-INFO} mandatory-field edits, whose
     * order differs from add: user id &rarr; first name &rarr; last name &rarr;
     * password &rarr; user type. The cascading rows prove that order.
     *
     * @return the argument rows {@code (userId, firstName, lastName, rawPassword,
     *         userType, expectedMessage)}
     */
    static Stream<Arguments> updateEmptyFieldScenarios() {
        return Stream.of(
                Arguments.of(null, null, null, null, ' ', MSG_USER_ID_EMPTY),
                Arguments.of(USER_ID, null, null, null, ' ', MSG_FIRST_NAME_EMPTY),
                Arguments.of(USER_ID, "Jane", null, null, ' ', MSG_LAST_NAME_EMPTY),
                Arguments.of(USER_ID, "Jane", "Doe", null, ' ', MSG_PASSWORD_EMPTY),
                Arguments.of(USER_ID, "Jane", "Doe", NEW_RAW_PASSWORD, ' ', MSG_USER_TYPE_EMPTY),
                // Whitespace treated as empty; user id still checked first.
                Arguments.of("   ", "Jane", "Doe", NEW_RAW_PASSWORD, 'A', MSG_USER_ID_EMPTY));
    }

    @ParameterizedTest(name = "[{index}] first-empty field yields \"{5}\"")
    @MethodSource("updateEmptyFieldScenarios")
    @DisplayName("updateUser short-circuits on the first empty mandatory field (user id first) and reads nothing")
    void updateUserShortCircuitsOnFirstEmptyField(String userId,
                                                  String firstName,
                                                  String lastName,
                                                  String rawPassword,
                                                  char userType,
                                                  String expectedMessage) {
        UserService.UserResult result =
                service.updateUser(userId, firstName, lastName, rawPassword, userType);

        assertThat(result.success()).isFalse();
        assertThat(result.user()).isNull();
        assertThat(result.message()).isEqualTo(expectedMessage);
        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser applies changed fields, re-encodes a changed password and returns the success message")
    void updateUserAppliesChangesAndReEncodesPassword() {
        UserSecurity existing = user(USER_ID, "John", "Doe", STORED_HASH, "U");
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
        // The new password differs from the stored hash, so it is re-encoded.
        when(passwordEncoder.matches(NEW_RAW_PASSWORD_UPPER, STORED_HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW_RAW_PASSWORD_UPPER)).thenReturn(NEW_ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, UserSecurity.class));

        UserService.UserResult result =
                service.updateUser(USER_ID, "Jane", "Smith", NEW_RAW_PASSWORD, 'A');

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity saved = captor.getValue();

        assertThat(saved.getSecUsrFname()).isEqualTo("Jane");
        assertThat(saved.getSecUsrLname()).isEqualTo("Smith");
        assertThat(saved.getSecUsrType()).isEqualTo("A");
        assertThat(saved.getSecUsrPwd()).isEqualTo(NEW_ENCODED_PASSWORD);

        assertThat(result.success()).isTrue();
        assertThat(result.user()).isSameAs(existing);
        assertThat(result.message()).isEqualTo(MSG_UPDATED);
    }

    @Test
    @DisplayName("updateUser re-encodes the password without a match check when no credential is stored")
    void updateUserReEncodesWhenStoredHashAbsent() {
        // A legacy record migrated without a hash: the password is treated as changed
        // and re-encoded without consulting the encoder's matches(...) comparison.
        UserSecurity existing = user(USER_ID, "Jane", "Doe", null, "U");
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(passwordEncoder.encode(NEW_RAW_PASSWORD_UPPER)).thenReturn(NEW_ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, UserSecurity.class));

        UserService.UserResult result =
                service.updateUser(USER_ID, "Jane", "Doe", NEW_RAW_PASSWORD, 'U');

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        assertThat(captor.getValue().getSecUsrPwd()).isEqualTo(NEW_ENCODED_PASSWORD);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_UPDATED);
    }

    @Test
    @DisplayName("updateUser returns \"Please modify to update ...\" and writes nothing when no field differs")
    void updateUserReturnsNothingChangedWhenIdentical() {
        UserSecurity existing = user(USER_ID, "Jane", "Doe", STORED_HASH, "U");
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
        // The supplied password matches the stored hash, so nothing changed.
        when(passwordEncoder.matches(RAW_PASSWORD_UPPER, STORED_HASH)).thenReturn(true);

        UserService.UserResult result =
                service.updateUser(USER_ID, "Jane", "Doe", RAW_PASSWORD, 'U');

        assertThat(result.success()).isFalse();
        assertThat(result.user()).isSameAs(existing);
        assertThat(result.message()).isEqualTo(MSG_PLEASE_MODIFY);
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
    }

    @Test
    @DisplayName("updateUser throws RecordNotFoundException when the id does not exist")
    void updateUserThrowsWhenNotFound() {
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateUser(USER_ID, "Jane", "Doe", NEW_RAW_PASSWORD, 'A'))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_USER_ID_NOT_FOUND);

        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
    }

    @Test
    @DisplayName("updateUser propagates an OptimisticLockingFailureException raised by the persistence provider")
    void updateUserPropagatesOptimisticLockFailure() {
        UserSecurity existing = user(USER_ID, "John", "Doe", STORED_HASH, "U");
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
        // Keep the password unchanged so only the first-name change drives the write.
        when(passwordEncoder.matches(RAW_PASSWORD_UPPER, STORED_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenThrow(new OptimisticLockingFailureException("stale user_security row"));

        assertThatThrownBy(() -> service.updateUser(USER_ID, "Jane", "Doe", RAW_PASSWORD, 'U'))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // ==================================================================
    // DELETE (COUSR03C) — empty-id edit, read-then-delete.
    // ==================================================================

    @Test
    @DisplayName("deleteUser removes the resolved record and returns the delete success message")
    void deleteUserRemovesResolvedRecord() {
        UserSecurity existing = user(USER_ID, "Jane", "Doe", STORED_HASH, "U");
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

        UserService.UserResult result = service.deleteUser(USER_ID);

        verify(userSecurityRepository).delete(existing);
        assertThat(result.success()).isTrue();
        assertThat(result.user()).isSameAs(existing);
        assertThat(result.message()).isEqualTo(MSG_DELETED);
    }

    @Test
    @DisplayName("deleteUser returns the empty-id edit message and touches nothing when the id is blank")
    void deleteUserRejectsBlankId() {
        UserService.UserResult result = service.deleteUser("");

        assertThat(result.success()).isFalse();
        assertThat(result.user()).isNull();
        assertThat(result.message()).isEqualTo(MSG_USER_ID_EMPTY);
        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("deleteUser throws RecordNotFoundException and deletes nothing when the id does not exist")
    void deleteUserThrowsWhenNotFound() {
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUser(USER_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_USER_ID_NOT_FOUND);

        verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
    }

    // ==================================================================
    // LIST (COUSR00C) — ascending key browse with optional STARTBR key.
    // ==================================================================

    @Test
    @DisplayName("listUsers returns every user in ascending id order when no start key is supplied")
    void listUsersReturnsAllInAscendingOrder() {
        UserSecurity u1 = user("USER0001", "Ann", "Alpha", STORED_HASH, "U");
        UserSecurity u2 = user("USER0002", "Bob", "Bravo", STORED_HASH, "U");
        UserSecurity u3 = user("USER0003", "Cy", "Charlie", STORED_HASH, "A");
        when(userSecurityRepository.findAllByOrderBySecUsrIdAsc())
                .thenReturn(List.of(u1, u2, u3));

        Pageable pageable = PageRequest.of(0, 10);
        Page<UserSecurity> page = service.listUsers(null, pageable);

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent())
                .extracting(UserSecurity::getSecUsrId)
                .containsExactly("USER0001", "USER0002", "USER0003");
    }

    @Test
    @DisplayName("listUsers positions at the first id >= the (normalized) start key, reproducing STARTBR")
    void listUsersFiltersFromStartKey() {
        UserSecurity u1 = user("USER0001", "Ann", "Alpha", STORED_HASH, "U");
        UserSecurity u2 = user("USER0002", "Bob", "Bravo", STORED_HASH, "U");
        UserSecurity u3 = user("USER0003", "Cy", "Charlie", STORED_HASH, "A");
        when(userSecurityRepository.findAllByOrderBySecUsrIdAsc())
                .thenReturn(List.of(u1, u2, u3));

        Pageable pageable = PageRequest.of(0, 10);
        // Lower-case start key exercises the service's trim + upper-case normalization.
        Page<UserSecurity> page = service.listUsers("user0002", pageable);

        assertThat(page.getContent())
                .extracting(UserSecurity::getSecUsrId)
                .containsExactly("USER0002", "USER0003");
    }

    @Test
    @DisplayName("listUsers returns only the requested offset/size window while reporting the full total")
    void listUsersHonorsPageWindow() {
        UserSecurity u1 = user("USER0001", "Ann", "Alpha", STORED_HASH, "U");
        UserSecurity u2 = user("USER0002", "Bob", "Bravo", STORED_HASH, "U");
        UserSecurity u3 = user("USER0003", "Cy", "Charlie", STORED_HASH, "A");
        UserSecurity u4 = user("USER0004", "Di", "Delta", STORED_HASH, "U");
        when(userSecurityRepository.findAllByOrderBySecUsrIdAsc())
                .thenReturn(List.of(u1, u2, u3, u4));

        // Second page of size two -> offset 2 -> the third and fourth records.
        Pageable pageable = PageRequest.of(1, 2);
        Page<UserSecurity> page = service.listUsers(null, pageable);

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent())
                .extracting(UserSecurity::getSecUsrId)
                .containsExactly("USER0003", "USER0004");
    }
}
