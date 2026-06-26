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
package com.aws.carddemo.service.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.UserAddScreen;
import com.aws.carddemo.exception.AuthorizationException;
import com.aws.carddemo.repository.UserSecurityRepository;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link UserAddService}, the online
 * <strong>Add User</strong> business-logic service migrated from the legacy CICS COBOL program
 * {@code COUSR01C} (CICS transaction {@code CU01}; behavioral spec {@code
 * legacy/app/cbl/COUSR01C.cbl}, security record layout {@code legacy/app/cpy/CSUSR01Y.cpy}, work
 * area {@code legacy/app/cpy/CVCRD01Y.cpy}, COMMAREA {@code legacy/app/cpy/COCOM01Y.cpy}).
 *
 * <p>{@code UserAddService} has exactly two collaborators — the {@link UserSecurityRepository} that
 * replaces the COBOL {@code EXEC CICS WRITE DATASET('USRSEC')}, and the {@link PasswordEncoder}
 * (BCrypt) that hashes the new user's password before persistence. Both are mocked here so the
 * tests run with no Spring context, no {@code @MockBean}, and no database (the
 * {@code @PreAuthorize} proxy and Testcontainers integration are exercised elsewhere). Every branch
 * of the validate-then-write state machine is verified in isolation.
 *
 * <p><strong>Admin-only gate (AAP &sect;0.6.5).</strong> {@code COUSR01C} (CU01) is reachable only
 * from the admin menu, so the service throws {@link AuthorizationException} at method entry for a
 * non-administrator. Because this is a plain unit test the class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} is a no-op; the explicit {@link
 * CardDemoCommarea#isAdmin()} guard is what is asserted, and it must reject <em>before</em> any
 * repository or encoder access.
 *
 * <p><strong>Control-flow parity (AAP &sect;0.7.1).</strong> The write path is a DUPREC pre-check
 * ({@code existsById}) &rarr; hash ({@code encode}) &rarr; persist ({@code save}). {@link
 * #validNewUser_hashesPassword_savesUser_andShowsSuccess()} pins that ordering with a Mockito
 * {@link InOrder}; the five empty-field checks are asserted byte-exact and in COBOL order, with the
 * short-circuit (first blank wins) proven separately.
 *
 * <p><strong>Credential hygiene (AAP &sect;0.6.6).</strong> The single deliberate deviation from
 * {@code COUSR01C} is that the clear-text password is BCrypt-hashed before persistence. The tests
 * use a placeholder plaintext only as transient input and prove, via an {@link ArgumentCaptor},
 * that the persisted {@code SEC-USR-PWD} is the encoder output and never the plaintext, and that
 * the plaintext never leaks into the success message.
 *
 * <p><strong>FILE STATUS mapping (AAP &sect;0.6.4).</strong> A duplicate user id — detected either
 * by the {@code existsById} pre-check or by a racing {@link DataIntegrityViolationException} on the
 * insert — redisplays {@code "User ID already exist..."} (it never throws). Any other data-access
 * failure on the insert redisplays {@code "Unable to Add User..."} (the COBOL {@code WHEN OTHER}
 * branch) rather than mapping to an exception, matching the legacy {@code DISPLAY}-then-{@code
 * SEND} behavior. The byte-exact operator literals are asserted directly (an independent oracle) so
 * a single-byte drift fails the test.
 */
@ExtendWith(MockitoExtension.class)
class UserAddServiceTest {

  /** New user's first name input ({@code FNAME PIC X(20)}). */
  private static final String FNAME = "John";

  /** New user's last name input ({@code LNAME PIC X(20)}). */
  private static final String LNAME = "Doe";

  /** New user's id input ({@code USERID PIC X(8)}); used as the {@code USRSEC} primary key. */
  private static final String USER_ID = "NEWUSER1";

  /**
   * Placeholder clear-text password used <strong>only</strong> as transient test input. It is never
   * a real credential and must never be the value asserted on the persisted record.
   */
  private static final String PWD_PLAINTEXT = "P@ss";

  /**
   * The (fake) BCrypt hash the mocked {@link PasswordEncoder} returns for {@link #PWD_PLAINTEXT}.
   */
  private static final String PWD_HASH = "$2a$HASH";

  /** New user's type flag ({@code USRTYPE PIC X(1)}): {@code 'U'} = standard user. */
  private static final String USR_TYPE = "U";

  @Mock private UserSecurityRepository userSecurityRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @InjectMocks private UserAddService service;

  // ===== ENTER — valid add (success path) ========================================================

  @Test
  @DisplayName("ENTER valid user: existsById -> encode -> save in order; stores the hash; success")
  void validNewUser_hashesPassword_savesUser_andShowsSuccess() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);
    when(userSecurityRepository.existsById(USER_ID)).thenReturn(false);
    when(passwordEncoder.encode(PWD_PLAINTEXT)).thenReturn(PWD_HASH);

    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    // PROCESS-ENTER-KEY never transfers control; the success path redisplays the confirmation.
    assertThat(next).isNull();

    // Control-flow parity (AAP 0.7.1): DUPREC pre-check, then hash, then persist — in that order.
    ArgumentCaptor<UserSecurity> saved = ArgumentCaptor.forClass(UserSecurity.class);
    InOrder inOrder = inOrder(userSecurityRepository, passwordEncoder);
    inOrder.verify(userSecurityRepository).existsById(USER_ID);
    inOrder.verify(passwordEncoder).encode(PWD_PLAINTEXT);
    inOrder.verify(userSecurityRepository).save(saved.capture());
    verifyNoMoreInteractions(userSecurityRepository, passwordEncoder);

    // Credential hygiene (AAP 0.6.6): the persisted password is the encoder output, never
    // plaintext.
    UserSecurity persisted = saved.getValue();
    assertThat(persisted.getSecUsrPwd()).isEqualTo(PWD_HASH);
    assertThat(persisted.getSecUsrPwd()).isNotEqualTo(PWD_PLAINTEXT);
    assertThat(persisted.getSecUsrId()).isEqualTo(USER_ID);
    assertThat(persisted.getSecUsrFname()).isEqualTo(FNAME);
    assertThat(persisted.getSecUsrLname()).isEqualTo(LNAME);
    assertThat(persisted.getSecUsrType()).isEqualTo(USR_TYPE);

    // NORMAL branch: STRING-built confirmation; INITIALIZE-ALL-FIELDS blanks the input fields.
    // QA F4-1: COUSR01C L254 renders this confirmation in DFHGREEN, so it rides the dedicated
    // GREEN success channel; the RED error channel must be blank (the two are mutually exclusive).
    assertThat(screen.getSuccessMsg()).isEqualTo("User " + USER_ID + " has been added ...");
    assertThat(screen.getSuccessMsg()).contains(USER_ID).doesNotContain(PWD_PLAINTEXT);
    assertThat(screen.getErrMsg()).isEmpty();
    assertThat(screen.getFName()).isNull();
    assertThat(screen.getLName()).isNull();
    assertThat(screen.getUserId()).isNull();
    assertThat(screen.getPasswd()).isNull();
    assertThat(screen.getUsrType()).isNull();
  }

  // ===== ENTER — empty-field validation (byte-exact, in COBOL order) =============================

  @ParameterizedTest(name = "{0}")
  @MethodSource("emptyFieldCases")
  @DisplayName("PROCESS-ENTER-KEY: blank fields rejected byte-exact, in COBOL order")
  void emptyField_redisplaysByteExactMessage_inCobolOrder(
      String label, UserAddScreen screen, String expectedMessage) {
    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(expectedMessage);
    // No persistence or hashing may occur before every field passes validation.
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("Validation short-circuits on the first blank field (First Name wins)")
  void firstBlankFieldWins_validationShortCircuits() {
    // Both first and last name are blank; the COBOL EVALUATE TRUE stops at the FIRST match, so only
    // the First Name message is produced — proving the validation order is honored, not
    // overwritten.
    UserAddScreen screen = screen("", "", USER_ID, PWD_PLAINTEXT, USR_TYPE);

    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("First Name can NOT be empty...");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  // ===== ENTER — fixed-width length validation (QA FINAL_ALT Issue 6)
  // =============================

  @ParameterizedTest(name = "{0}")
  @MethodSource("overlongFieldCases")
  @DisplayName(
      "PROCESS-ENTER-KEY: over-length fields rejected with a field-specific length message")
  void overlongField_redisplaysLengthMessage_beforeAnyWrite(
      String label, UserAddScreen screen, String expectedMessage) {
    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(expectedMessage);
    // The length guards sit before WRITE-USER-SEC-FILE, so no existence check, hash, or save runs —
    // the over-length value never reaches the fixed-width column to raise a DB length error.
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName(
      "Empty check still precedes length check (blank First Name wins over over-long Last)")
  void emptyCheckPrecedesLengthCheck_cobolOrderPreserved() {
    // First Name is blank AND Last Name is over-length. The empty checks run as a complete block in
    // byte-exact COBOL order BEFORE any length guard, so the blank First Name message must win —
    // proving the new length validation did not perturb the pinned empty-field ordering.
    UserAddScreen screen = screen("", "X".repeat(21), USER_ID, PWD_PLAINTEXT, USR_TYPE);

    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("First Name can NOT be empty...");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("QA Issue 6: over-long first/last name shows a length message, NOT a DB error")
  void overlongName_qaScenario_showsLengthMessage_notMisleadingDuplicate() {
    // Reproduces the QA FINAL_ALT Issue 6 payload exactly: a valid id/password/type with first and
    // last names longer than the 20-char fixed width. Before the guard this slipped through the
    // empty checks, hit the user_security char(20) column, raised
    // "value too long for type character(20)", and surfaced as the misleading
    // "User ID already exist..." duplicate branch. It must now redisplay the First Name length
    // message and perform NO persistence at all.
    UserAddScreen screen =
        screen(
            "Reallyreallyreallylongfirstname",
            "Reallyreallyreallylonglastname",
            "QAEDGE2",
            "PASS1234",
            "U");

    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("First Name can NOT exceed 20 characters...");
    assertThat(screen.getErrMsg()).isNotEqualTo("User ID already exist...");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("Boundary: exactly-20-char names pass the length guard and persist normally")
  void exactlyMaxLengthName_passesLengthGuard_persists() {
    // A name of exactly 20 characters is the largest the SEC-USR-FNAME PIC X(20) column accepts, so
    // it must NOT be rejected by the length guard (the guard fires strictly above the maximum).
    String twentyCharName = "ABCDEFGHIJKLMNOPQRST"; // exactly 20 characters
    assertThat(twentyCharName).hasSize(20);
    UserAddScreen screen = screen(twentyCharName, twentyCharName, USER_ID, PWD_PLAINTEXT, USR_TYPE);
    when(userSecurityRepository.existsById(USER_ID)).thenReturn(false);
    when(passwordEncoder.encode(PWD_PLAINTEXT)).thenReturn(PWD_HASH);

    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // No length error; the success path runs and persists the record.
    assertThat(screen.getSuccessMsg()).isEqualTo("User " + USER_ID + " has been added ...");
    verify(userSecurityRepository).save(any(UserSecurity.class));
  }

  // ===== ENTER — duplicate / write failures (FILE STATUS mapping) ================================

  @Test
  @DisplayName("DUPREC pre-check: existing id redisplays 'already exist' without encode/save")
  void duplicateUserId_existsByIdTrue_redisplaysAlreadyExist() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);
    when(userSecurityRepository.existsById(USER_ID)).thenReturn(true);

    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("User ID already exist...");
    verify(userSecurityRepository).existsById(USER_ID);
    verify(passwordEncoder, never()).encode(any());
    verify(userSecurityRepository, never()).save(any());
  }

  @Test
  @DisplayName(
      "WRITE WHEN OTHER: an unexpected data-access failure redisplays 'Unable to Add User'")
  void saveThrowsDataAccess_redisplaysUnableToAdd_doesNotThrow() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);
    when(userSecurityRepository.existsById(USER_ID)).thenReturn(false);
    when(passwordEncoder.encode(PWD_PLAINTEXT)).thenReturn(PWD_HASH);
    when(userSecurityRepository.save(any(UserSecurity.class)))
        .thenThrow(new DataAccessResourceFailureException("datastore unavailable"));

    // FILE STATUS parity (AAP 0.6.4): a non-duplicate datastore failure is shown on-screen, not
    // thrown — the service catches DataAccessException and redisplays the COBOL WHEN OTHER literal.
    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Unable to Add User...");
    verify(userSecurityRepository).save(any(UserSecurity.class));
  }

  @Test
  @DisplayName("WRITE DUPREC race: a unique-key violation on save redisplays 'already exist'")
  void concurrentInsert_dataIntegrityViolation_redisplaysAlreadyExist() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);
    when(userSecurityRepository.existsById(USER_ID)).thenReturn(false);
    when(passwordEncoder.encode(PWD_PLAINTEXT)).thenReturn(PWD_HASH);
    when(userSecurityRepository.save(any(UserSecurity.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("User ID already exist...");
  }

  // ===== Admin-only gate (AAP 0.6.5) =============================================================

  @Test
  @DisplayName("Admin-only gate: a standard user is rejected before any I/O")
  void nonAdminUser_rejectedByAuthorizationGate_beforeAnyIo() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);

    assertThatThrownBy(() -> service.processUserAdd(screen, userCommarea(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(AuthorizationException.class)
        .hasMessage(AuthorizationException.ADMIN_ONLY_MESSAGE);

    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  // ===== AID routing: PF3 / PF4 / first-entry / unsupported keys =================================

  @Test
  @DisplayName("PF3: returns to the admin menu (COADM01C) and records the from-context")
  void pfk03_setsAdminTarget_returnsToCallingProgram() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);
    CardDemoCommarea commarea = adminCommarea();

    String next = service.processUserAdd(screen, commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COADM01C");
    assertThat(commarea.getToProgram()).isEqualTo("COADM01C");
    assertThat(commarea.getFromProgram()).isEqualTo("COUSR01C");
    assertThat(commarea.getFromTranId()).isEqualTo("CU01");
    assertThat(commarea.isPgmEnter()).isTrue();
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("PF4: clears all input fields and redisplays")
  void pfk04_clearsScreen_redisplays() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);

    String next = service.processUserAdd(screen, adminCommarea(), CardWorkArea.Aid.PFK04);

    assertThat(next).isNull();
    assertThat(screen.getFName()).isNull();
    assertThat(screen.getLName()).isNull();
    assertThat(screen.getUserId()).isNull();
    assertThat(screen.getPasswd()).isNull();
    assertThat(screen.getUsrType()).isNull();
    assertThat(screen.getErrMsg()).isEmpty();
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("First entry (not re-enter): sets the re-enter flag and paints an empty form")
  void firstEntry_setsReenterFlag_redisplaysEmptyForm() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);
    CardDemoCommarea commarea = firstEntryAdminCommarea();

    String next = service.processUserAdd(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(screen.getErrMsg()).isEmpty();
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @ParameterizedTest(name = "AID {0} -> invalid-key redisplay")
  @MethodSource("unsupportedKeys")
  @DisplayName("Unsupported AID: redisplays the invalid-key message")
  void unsupportedKey_redisplaysInvalidKeyMessage(CardWorkArea.Aid aid) {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);

    String next = service.processUserAdd(screen, adminCommarea(), aid);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Invalid key pressed. Please see below...");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("Null AID: treated as an unsupported key (invalid-key redisplay)")
  void nullAid_redisplaysInvalidKeyMessage() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);

    String next = service.processUserAdd(screen, adminCommarea(), null);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Invalid key pressed. Please see below...");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  // ===== Null-argument guards ====================================================================

  @Test
  @DisplayName("Null screen: rejected with NullPointerException")
  void nullScreen_throwsNullPointerException() {
    assertThatThrownBy(() -> service.processUserAdd(null, adminCommarea(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("screen must not be null");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("Null commarea: rejected with NullPointerException")
  void nullCommarea_throwsNullPointerException() {
    UserAddScreen screen = screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE);

    assertThatThrownBy(() -> service.processUserAdd(screen, null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("commarea must not be null");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  // ===== Parameterized-source providers ==========================================================

  /**
   * The five empty-field cases in the exact COBOL {@code EVALUATE TRUE} order, mixing {@code null},
   * empty, and all-spaces blanks to also model the COBOL {@code = SPACES OR LOW-VALUES} test. Each
   * case fills every earlier field with a valid value so the targeted field is the first blank.
   *
   * @return the labelled (screen, expected-message) cases
   */
  private static Stream<Arguments> emptyFieldCases() {
    return Stream.of(
        arguments(
            "first name (null) blank",
            screen(null, LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE),
            "First Name can NOT be empty..."),
        arguments(
            "last name (empty) blank",
            screen(FNAME, "", USER_ID, PWD_PLAINTEXT, USR_TYPE),
            "Last Name can NOT be empty..."),
        arguments(
            "user id (spaces) blank",
            screen(FNAME, LNAME, "   ", PWD_PLAINTEXT, USR_TYPE),
            "User ID can NOT be empty..."),
        arguments(
            "password (empty) blank",
            screen(FNAME, LNAME, USER_ID, "", USR_TYPE),
            "Password can NOT be empty..."),
        arguments(
            "user type (spaces) blank",
            screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, "  "),
            "User Type can NOT be empty..."));
  }

  /**
   * The four fixed-width over-length cases, one per length-bounded field, each just one character
   * past its {@code CSUSR01Y} maximum (FNAME/LNAME {@code X(20)}, USERID {@code X(8)}, USRTYPE
   * {@code X(1)}). Each case keeps every other field valid so the targeted field is the sole
   * violation, mirroring the structure of {@link #emptyFieldCases()}.
   *
   * @return the labelled (screen, expected-message) cases
   */
  private static Stream<Arguments> overlongFieldCases() {
    return Stream.of(
        arguments(
            "first name (21 chars) too long",
            screen("X".repeat(21), LNAME, USER_ID, PWD_PLAINTEXT, USR_TYPE),
            "First Name can NOT exceed 20 characters..."),
        arguments(
            "last name (21 chars) too long",
            screen(FNAME, "X".repeat(21), USER_ID, PWD_PLAINTEXT, USR_TYPE),
            "Last Name can NOT exceed 20 characters..."),
        arguments(
            "user id (9 chars) too long",
            screen(FNAME, LNAME, "X".repeat(9), PWD_PLAINTEXT, USR_TYPE),
            "User ID can NOT exceed 8 characters..."),
        arguments(
            "user type (2 chars) too long",
            screen(FNAME, LNAME, USER_ID, PWD_PLAINTEXT, "UU"),
            "User Type can NOT exceed 1 character..."));
  }

  /**
   * A representative set of attention identifiers that are not handled by {@code COUSR01C} (only
   * ENTER, PF3 and PF4 are), each of which must fall to the COBOL {@code WHEN OTHER} invalid-key
   * branch.
   *
   * @return the unsupported {@link CardWorkArea.Aid} values
   */
  private static Stream<Arguments> unsupportedKeys() {
    return Stream.of(
        arguments(CardWorkArea.Aid.CLEAR),
        arguments(CardWorkArea.Aid.PA1),
        arguments(CardWorkArea.Aid.PA2),
        arguments(CardWorkArea.Aid.PFK05),
        arguments(CardWorkArea.Aid.PFK12));
  }

  // ===== Fixtures ================================================================================

  /**
   * Builds an Add User screen with the five operator-input fields set.
   *
   * @param firstName the first-name input ({@code FNAME})
   * @param lastName the last-name input ({@code LNAME})
   * @param userId the user-id input ({@code USERID})
   * @param password the clear-text password input ({@code PASSWD})
   * @param userType the user-type flag input ({@code USRTYPE})
   * @return the populated screen DTO
   */
  private static UserAddScreen screen(
      String firstName, String lastName, String userId, String password, String userType) {
    UserAddScreen screen = new UserAddScreen();
    screen.setFName(firstName);
    screen.setLName(lastName);
    screen.setUserId(userId);
    screen.setPasswd(password);
    screen.setUsrType(userType);
    return screen;
  }

  /**
   * An administrator COMMAREA already in the re-enter state, so {@code processUserAdd} reaches the
   * {@code EVALUATE EIBAID} branch rather than painting the first-entry empty form.
   *
   * @return an admin, re-entered navigation COMMAREA
   */
  private static CardDemoCommarea adminCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypAdmin();
    commarea.setPgmReenter();
    return commarea;
  }

  /**
   * A standard-user COMMAREA in the re-enter state, used to drive the admin-only gate denial.
   *
   * @return a non-admin, re-entered navigation COMMAREA
   */
  private static CardDemoCommarea userCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();
    commarea.setPgmReenter();
    return commarea;
  }

  /**
   * An administrator COMMAREA left in the first-entry (not re-entered) state to exercise the
   * empty-form bounce.
   *
   * @return an admin COMMAREA whose program context denotes a first entry
   */
  private static CardDemoCommarea firstEntryAdminCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypAdmin();
    return commarea;
  }
}
