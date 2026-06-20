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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.UserUpdateScreen;
import com.aws.carddemo.exception.AuthorizationException;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.util.Messages;
import java.util.Optional;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link UserUpdateService}, the online
 * user-update business-logic service migrated from the legacy CICS COBOL program {@code COUSR02C}
 * (CICS transaction {@code CU02}, admin-only; behavioral spec {@code legacy/app/cbl/COUSR02C.cbl},
 * security record layout {@code legacy/app/cpy/CSUSR01Y.cpy}, the navigation COMMAREA {@code
 * legacy/app/cpy/COCOM01Y.cpy}, and the work area {@code legacy/app/cpy/CVCRD01Y.cpy}).
 *
 * <p>{@code UserUpdateService} has exactly two collaborators &mdash; the {@link
 * UserSecurityRepository} that replaces the COBOL {@code EXEC CICS READ/REWRITE DATASET('USRSEC')}
 * and the {@link PasswordEncoder} that hashes credentials. Both are mocked here so the tests run
 * with no Spring context, no {@code @MockBean}, and no database; the {@code @Transactional}
 * rollback boundary and the BCrypt encoder wiring are integration-tested elsewhere. Every branch of
 * the read-for-update &rarr; per-field modify-detect &rarr; rewrite state machine is exercised in
 * isolation.
 *
 * <p><strong>What is asserted (parity surface).</strong> {@link
 * UserUpdateService#processUserUpdate(UserUpdateScreen, CardDemoCommarea, CardWorkArea.Aid)} is a
 * pseudo-conversational turn; its observable effects are (1) the return value (the {@code EXEC CICS
 * XCTL} target program, or {@code null} to redisplay), (2) the navigation mutations made to the
 * {@link CardDemoCommarea}, (3) the message and field content written to the {@link
 * UserUpdateScreen}, and (4) the {@link UserSecurityRepository} / {@link PasswordEncoder}
 * interactions (lookup, conditional re-encode, and rewrite) including their relative order.
 *
 * <p><strong>Admin role gate (AAP &sect;0.6.5).</strong> {@code CU02} is reachable only from the
 * admin menu; {@link #nonAdminUser_isRejectedByAdminGate()} pins that a standard-user COMMAREA is
 * rejected with {@link AuthorizationException} <em>before</em> any repository or encoder access.
 *
 * <p><strong>Control-flow parity (AAP &sect;0.7.1).</strong> The update is a read-for-update &rarr;
 * compare &rarr; REWRITE cycle. {@link #pf5_fieldsChangedPasswordBlank_savesUser_showsSuccess()}
 * pins the lookup-before-save ordering with an {@link InOrder}; {@link
 * #pf5_passwordChanged_reEncodesAndSaves()} pins the lookup &rarr; encode &rarr; save ordering.
 *
 * <p><strong>Conditional credential re-encode (AAP &sect;0.6.6 &mdash; the crux of this service).
 * </strong> The legacy clear-text {@code SEC-USR-PWD} compare is replaced by a one-way BCrypt
 * check: the password is re-encoded <em>only</em> when a non-blank value is submitted that does not
 * already match the stored hash. The three sub-branches are pinned independently &mdash; blank
 * password ({@link #pf5_fieldsChangedPasswordBlank_savesUser_showsSuccess()}: {@code encode} and
 * {@code matches} never called), resubmitted-and-matching ({@link
 * #pf5_passwordResubmittedMatchingStored_notReEncoded()}: {@code encode} never called), and changed
 * ({@link #pf5_passwordChanged_reEncodesAndSaves()}: {@code encode} called and the new hash saved).
 * The stored hash is never echoed onto the screen ({@link
 * #enter_existingUser_loadsAndPromptsPf5()}) and clear-text is never asserted or stored.
 *
 * <p><strong>Byte-exact message parity (AAP &sect;0.6.5, &sect;0.7.3).</strong> {@link
 * #constants_areByteExact()} pins every operator literal to the verbatim COUSR02C text; the
 * behavioral tests then assert the service output against those same constants, so a single-byte
 * drift fails the suite.
 *
 * <p><strong>FILE STATUS mapping (AAP &sect;0.6.4).</strong> A record-not-found read redisplays
 * with a message and never throws; an unexpected data-access failure is caught and surfaced as the
 * on-screen fallback (lookup &rarr; {@code Unable to lookup User...}; rewrite &rarr; {@code Unable
 * to Update User...}) rather than mapped to an abend exception &mdash; this service's documented
 * contract. The three failure modes (no-change, caught save failure, lookup fault) are
 * distinguished precisely.
 */
@ExtendWith(MockitoExtension.class)
class UserUpdateServiceTest {

  /** A valid 8-character user id ({@code SEC-USR-ID}, the {@code USRSEC} KSDS key). */
  private static final String USER_ID = "USER0001";

  /**
   * The stored BCrypt hash on the canonical sample user. An obvious non-secret placeholder &mdash;
   * never a real credential &mdash; used only to verify that a blank/unchanged password leaves the
   * stored hash untouched.
   */
  private static final String OLD_HASH = "$2a$OLD";

  /**
   * The re-encoded hash returned by the mocked encoder when a genuinely new password is supplied.
   */
  private static final String NEW_HASH = "$2a$NEW";

  /** Mocked collaborator: the migrated {@code USRSEC} read / rewrite path. */
  @Mock private UserSecurityRepository userSecurityRepository;

  /** Mocked collaborator: the BCrypt encoder used to compare and (re-)hash credentials. */
  @Mock private PasswordEncoder passwordEncoder;

  /** Service under test; Mockito injects the two mocks via the constructor. */
  @InjectMocks private UserUpdateService service;

  // ===============================================================================================
  // Fixtures / builders
  // ===============================================================================================

  /**
   * Builds the canonical stored user: first name {@code "John"}, last name {@code "Doe"}, type
   * {@code "U"}, and the placeholder hash {@link #OLD_HASH}.
   *
   * @param id the {@code sec_usr_id} primary key
   * @return a fully populated {@link UserSecurity}
   */
  private static UserSecurity existingUser(String id) {
    UserSecurity user = new UserSecurity();
    user.setSecUsrId(id);
    user.setSecUsrFname("John");
    user.setSecUsrLname("Doe");
    user.setSecUsrPwd(OLD_HASH);
    user.setSecUsrType("U");
    return user;
  }

  /** A brand-new, empty update-user screen contract. */
  private static UserUpdateScreen screen() {
    return new UserUpdateScreen();
  }

  /**
   * Builds an update-user screen carrying the lookup key and the four editable attributes.
   *
   * @param id the user id ({@code USRIDIN})
   * @param fName the first name ({@code FNAME})
   * @param lName the last name ({@code LNAME})
   * @param passwd the password field ({@code PASSWD}); blank means "keep existing"
   * @param usrType the user type ({@code USRTYPE})
   * @return the populated screen
   */
  private static UserUpdateScreen screen(
      String id, String fName, String lName, String passwd, String usrType) {
    UserUpdateScreen screen = new UserUpdateScreen();
    screen.setUsrIdIn(id);
    screen.setFName(fName);
    screen.setLName(lName);
    screen.setPasswd(passwd);
    screen.setUsrType(usrType);
    return screen;
  }

  /** A first-entry administrator COMMAREA ({@code CDEMO-USRTYP-ADMIN}, not yet re-entered). */
  private static CardDemoCommarea adminCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypAdmin();
    return commarea;
  }

  /**
   * A re-entry administrator COMMAREA: {@code CDEMO-PGM-REENTER} is set so dispatch falls through
   * to the {@code EVALUATE EIBAID} branch that processes the operator's attention key.
   *
   * @return a re-entered admin COMMAREA
   */
  private static CardDemoCommarea adminReentered() {
    CardDemoCommarea commarea = adminCommarea();
    commarea.setPgmReenter();
    return commarea;
  }

  /** A standard (non-admin) re-entry COMMAREA ({@code CDEMO-USRTYP-USER}). */
  private static CardDemoCommarea userCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();
    commarea.setPgmReenter();
    return commarea;
  }

  // ===============================================================================================
  // Byte-exact message-literal parity (COUSR02C operator text)
  // ===============================================================================================

  @Test
  @DisplayName("operator message constants are byte-exact to the COUSR02C COBOL literals")
  void constants_areByteExact() {
    assertThat(UserUpdateService.MSG_USERID_EMPTY).isEqualTo("User ID can NOT be empty...");
    assertThat(UserUpdateService.MSG_FNAME_EMPTY).isEqualTo("First Name can NOT be empty...");
    assertThat(UserUpdateService.MSG_LNAME_EMPTY).isEqualTo("Last Name can NOT be empty...");
    assertThat(UserUpdateService.MSG_PASSWORD_EMPTY).isEqualTo("Password can NOT be empty...");
    assertThat(UserUpdateService.MSG_USRTYPE_EMPTY).isEqualTo("User Type can NOT be empty...");
    assertThat(UserUpdateService.MSG_PRESS_PF5_TO_SAVE)
        .isEqualTo("Press PF5 key to save your updates ...");
    assertThat(UserUpdateService.MSG_USER_NOT_FOUND).isEqualTo("User ID NOT found...");
    assertThat(UserUpdateService.MSG_UNABLE_LOOKUP_USER).isEqualTo("Unable to lookup User...");
    assertThat(UserUpdateService.MSG_NO_CHANGES).isEqualTo("Please modify to update ...");
    assertThat(UserUpdateService.MSG_UNABLE_UPDATE_USER).isEqualTo("Unable to Update User...");
  }

  // ===============================================================================================
  // MAIN-PARA guard clauses (defensive Objects.requireNonNull)
  // ===============================================================================================

  @Test
  @DisplayName("null screen is rejected by the defensive guard before any collaborator is touched")
  void nullScreen_throwsNullPointerException() {
    assertThatThrownBy(
            () -> service.processUserUpdate(null, adminReentered(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("screen must not be null");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName(
      "null commarea is rejected by the defensive guard before any collaborator is touched")
  void nullCommarea_throwsNullPointerException() {
    assertThatThrownBy(() -> service.processUserUpdate(screen(), null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("commarea must not be null");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  // ===============================================================================================
  // Admin role gate (AAP §0.6.5): CU02 is admin-only
  // ===============================================================================================

  @Test
  @DisplayName("a standard (non-admin) user is rejected by the admin gate before any I/O")
  void nonAdminUser_isRejectedByAdminGate() {
    assertThatThrownBy(
            () ->
                service.processUserUpdate(
                    screen(USER_ID, "Jane", "Smith", "", "A"),
                    userCommarea(),
                    CardWorkArea.Aid.PFK05))
        .isInstanceOf(AuthorizationException.class)
        .hasMessage(AuthorizationException.ADMIN_ONLY_MESSAGE);
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  // ===============================================================================================
  // First entry (NOT CDEMO-PGM-REENTER): the user-list handoff drives the lookup on first paint
  // ===============================================================================================

  @Test
  @DisplayName("first entry with a pre-loaded user id runs the lookup and prompts for PF5 on paint")
  void firstEntry_withPreloadedUserId_runsLookupOnFirstPaint() {
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser(USER_ID)));
    UserUpdateScreen screen = screen();
    screen.setUsrIdIn(USER_ID);
    CardDemoCommarea commarea = adminCommarea(); // fresh: NOT yet re-entered

    String next = service.processUserUpdate(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(screen.getFName()).isEqualTo("John");
    assertThat(screen.getLName()).isEqualTo("Doe");
    assertThat(screen.getUsrType()).isEqualTo("U");
    assertThat(screen.getErrMsg()).isEqualTo(UserUpdateService.MSG_PRESS_PF5_TO_SAVE);
    verify(userSecurityRepository).findById(USER_ID);
    verify(userSecurityRepository, never()).save(any());
    verify(passwordEncoder, never()).encode(any());
  }

  @Test
  @DisplayName("first entry with a blank user id paints the empty form without any lookup")
  void firstEntry_blankUserId_paintsWithoutLookup() {
    UserUpdateScreen screen = screen();
    CardDemoCommarea commarea = adminCommarea();

    String next = service.processUserUpdate(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  // ===============================================================================================
  // PROCESS-ENTER-KEY: ENTER looks up and populates the form (never echoing the stored hash)
  // ===============================================================================================

  @Test
  @DisplayName(
      "ENTER on an existing user populates the form and prompts PF5, never echoing the hash")
  void enter_existingUser_loadsAndPromptsPf5() {
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser(USER_ID)));
    UserUpdateScreen screen = screen();
    screen.setUsrIdIn(USER_ID);

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getFName()).isEqualTo("John");
    assertThat(screen.getLName()).isEqualTo("Doe");
    assertThat(screen.getUsrType()).isEqualTo("U");
    // Credential hygiene (AAP §0.6.6): the stored BCrypt hash is NEVER surfaced onto the screen.
    assertThat(screen.getPasswd()).isEmpty();
    assertThat(screen.getPasswd()).doesNotContain(OLD_HASH);
    assertThat(screen.getErrMsg()).isEqualTo(UserUpdateService.MSG_PRESS_PF5_TO_SAVE);
    verify(userSecurityRepository, never()).save(any());
    verify(passwordEncoder, never()).encode(any());
  }

  @Test
  @DisplayName("ENTER with a blank user id redisplays the User-ID-empty message without a read")
  void enter_blankUserId_redisplaysUserIdEmpty() {
    UserUpdateScreen screen = screen();
    screen.setUsrIdIn("");

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(UserUpdateService.MSG_USERID_EMPTY);
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("ENTER on a missing user redisplays the not-found message (FILE STATUS 23)")
  void enter_userNotFound_redisplaysNotFound() {
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());
    UserUpdateScreen screen = screen();
    screen.setUsrIdIn(USER_ID);

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(UserUpdateService.MSG_USER_NOT_FOUND);
    verify(userSecurityRepository, never()).save(any());
    verify(passwordEncoder, never()).encode(any());
  }

  @Test
  @DisplayName("ENTER lookup data-access fault is caught and redisplayed as Unable-to-lookup")
  void enter_lookupDataAccessFailure_redisplaysUnableToLookup() {
    when(userSecurityRepository.findById(USER_ID))
        .thenThrow(new DataAccessResourceFailureException("simulated lookup I/O failure"));
    UserUpdateScreen screen = screen();
    screen.setUsrIdIn(USER_ID);

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.ENTER);

    // AAP §0.6.4 for this service: the WHEN OTHER lookup fault is surfaced on-screen (and the
    // turn redisplays), NOT mapped to an abend exception. This is distinct from the save-failure
    // fallback below.
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(UserUpdateService.MSG_UNABLE_LOOKUP_USER);
    verify(userSecurityRepository, never()).save(any());
    verify(passwordEncoder, never()).encode(any());
  }

  // ===============================================================================================
  // UPDATE-USER-INFO via PF5: modify detection, conditional re-encode, and rewrite
  // ===============================================================================================

  @Test
  @DisplayName(
      "PF5 with changed name/type and a blank password saves and confirms, without encoding")
  void pf5_fieldsChangedPasswordBlank_savesUser_showsSuccess() {
    UserSecurity user = existingUser(USER_ID);
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    // Changed first/last name and type; password left blank => "keep existing".
    UserUpdateScreen screen = screen(USER_ID, "Jane", "Smith", "", "A");

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("User " + USER_ID + " has been updated ...");

    // Control-flow parity: the re-read precedes the rewrite (read-for-update).
    ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
    InOrder inOrder = inOrder(userSecurityRepository);
    inOrder.verify(userSecurityRepository).findById(USER_ID);
    inOrder.verify(userSecurityRepository).save(captor.capture());

    UserSecurity saved = captor.getValue();
    assertThat(saved.getSecUsrFname()).isEqualTo("Jane");
    assertThat(saved.getSecUsrLname()).isEqualTo("Smith");
    assertThat(saved.getSecUsrType()).isEqualTo("A");
    // Credential hygiene: a blank password leaves the stored hash untouched and never re-encodes.
    assertThat(saved.getSecUsrPwd()).isEqualTo(OLD_HASH);
    verify(passwordEncoder, never()).matches(any(), any());
    verify(passwordEncoder, never()).encode(any());
  }

  @Test
  @DisplayName("PF5 with a genuinely new password re-encodes it and saves the new hash")
  void pf5_passwordChanged_reEncodesAndSaves() {
    UserSecurity user = existingUser(USER_ID);
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    // The submitted password does not match the stored hash => genuine change.
    when(passwordEncoder.matches("NEWPASS", OLD_HASH)).thenReturn(false);
    when(passwordEncoder.encode("NEWPASS")).thenReturn(NEW_HASH);
    // Name/type equal to stored so ONLY the password change drives the modify flag.
    UserUpdateScreen screen = screen(USER_ID, "John", "Doe", "NEWPASS", "U");

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("User " + USER_ID + " has been updated ...");

    // Control-flow parity for the §0.6.6 crux: lookup -> encode -> save.
    ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
    InOrder inOrder = inOrder(userSecurityRepository, passwordEncoder);
    inOrder.verify(userSecurityRepository).findById(USER_ID);
    inOrder.verify(passwordEncoder).encode("NEWPASS");
    inOrder.verify(userSecurityRepository).save(captor.capture());

    // The persisted credential is the freshly encoded hash, never the clear-text input.
    assertThat(captor.getValue().getSecUsrPwd()).isEqualTo(NEW_HASH);
  }

  @Test
  @DisplayName("PF5 with a password equal to the stored hash is not re-encoded (no change)")
  void pf5_passwordResubmittedMatchingStored_notReEncoded() {
    UserSecurity user = existingUser(USER_ID);
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    // A non-blank password that already matches the stored hash => no genuine change.
    when(passwordEncoder.matches("SAMEPASS", OLD_HASH)).thenReturn(true);
    // Name/type also equal to stored => nothing changed at all.
    UserUpdateScreen screen = screen(USER_ID, "John", "Doe", "SAMEPASS", "U");

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(UserUpdateService.MSG_NO_CHANGES);
    verify(passwordEncoder, never()).encode(any());
    verify(userSecurityRepository, never()).save(any());
  }

  @Test
  @DisplayName("PF5 with no field changes redisplays Please-modify and writes nothing")
  void pf5_noChanges_redisplaysPleaseModify() {
    UserSecurity user = existingUser(USER_ID);
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    // Every field equals the stored record; password blank => keep existing.
    UserUpdateScreen screen = screen(USER_ID, "John", "Doe", "", "U");

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(UserUpdateService.MSG_NO_CHANGES);
    // The §0.6.6 deviation: a blank password is "keep existing", NOT a Password-empty error.
    assertThat(screen.getErrMsg()).isNotEqualTo(UserUpdateService.MSG_PASSWORD_EMPTY);
    verify(userSecurityRepository, never()).save(any());
    verify(passwordEncoder, never()).encode(any());
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("emptyFieldCases")
  @DisplayName("PF5 empty-field checks fire in COBOL order and short-circuit before any read")
  void emptyField_redisplaysInCobolOrder(
      String label, UserUpdateScreen screen, String expectedMessage) {
    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(expectedMessage);
    // The empty-field guard short-circuits before READ-USER-SEC-FILE and before any encoder use.
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  /**
   * Supplies the reachable empty-field cases in the exact COBOL evaluation order. Each case keeps
   * the earlier fields non-blank so the case proves both the message and its precedence. The
   * Password slot is intentionally absent: on the update path the COBOL password-empty check is
   * skipped (AAP &sect;0.6.6), which the "User Type blank" case (with a blank password)
   * demonstrates by reaching the user-type check rather than firing a password-empty error.
   *
   * @return a stream of {@code (label, screen, expectedMessage)} arguments
   */
  private static Stream<Arguments> emptyFieldCases() {
    return Stream.of(
        Arguments.of(
            "User ID blank -> MSG_USERID_EMPTY",
            screen("", "John", "Doe", "", "U"),
            UserUpdateService.MSG_USERID_EMPTY),
        Arguments.of(
            "First Name blank -> MSG_FNAME_EMPTY",
            screen(USER_ID, "", "Doe", "", "U"),
            UserUpdateService.MSG_FNAME_EMPTY),
        Arguments.of(
            "Last Name blank -> MSG_LNAME_EMPTY",
            screen(USER_ID, "John", "", "", "U"),
            UserUpdateService.MSG_LNAME_EMPTY),
        Arguments.of(
            "User Type blank (blank password is NOT an error) -> MSG_USRTYPE_EMPTY",
            screen(USER_ID, "John", "Doe", "", ""),
            UserUpdateService.MSG_USRTYPE_EMPTY));
  }

  @Test
  @DisplayName("PF5 when the record vanished on the re-read redisplays the not-found message")
  void pf5_userNotFoundOnReRead_redisplaysNotFound() {
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());
    UserUpdateScreen screen = screen(USER_ID, "Jane", "Smith", "", "A");

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(UserUpdateService.MSG_USER_NOT_FOUND);
    verify(userSecurityRepository, never()).save(any());
    verify(passwordEncoder, never()).encode(any());
  }

  @Test
  @DisplayName("PF5 save data-access fault is caught and redisplayed as Unable-to-Update")
  void pf5_saveFailure_redisplaysUnableToUpdate() {
    UserSecurity user = existingUser(USER_ID);
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    when(userSecurityRepository.save(any()))
        .thenThrow(new DataAccessResourceFailureException("simulated rewrite I/O failure"));
    // A changed first name makes the record "modified" so the rewrite is attempted.
    UserUpdateScreen screen = screen(USER_ID, "Jane", "Doe", "", "U");

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.PFK05);

    // Distinct from no-change and lookup-fault: a caught save failure shows Unable-to-Update.
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(UserUpdateService.MSG_UNABLE_UPDATE_USER);
    verify(userSecurityRepository).save(any());
    verify(passwordEncoder, never()).encode(any());
  }

  // ===============================================================================================
  // RETURN-TO-PREV-SCREEN navigation (PF3 / PF12), CLEAR (PF4), and the WHEN OTHER invalid key
  // ===============================================================================================

  @Test
  @DisplayName(
      "PF3 performs the update first, then returns to the admin menu when no caller is set")
  void pfk03_performsUpdateThenReturnsToAdminMenu() {
    UserSecurity user = existingUser(USER_ID);
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    UserUpdateScreen screen = screen(USER_ID, "Jane", "Doe", "", "U");
    CardDemoCommarea commarea = adminReentered(); // from-program left blank

    String next = service.processUserUpdate(screen, commarea, CardWorkArea.Aid.PFK03);

    // PF3 always routes to the caller; with no from-program that defaults to the admin menu.
    assertThat(next).isEqualTo("COADM01C");
    assertThat(commarea.getToProgram()).isEqualTo("COADM01C");
    assertThat(commarea.getFromTranId()).isEqualTo("CU02");
    assertThat(commarea.getFromProgram()).isEqualTo("COUSR02C");
    assertThat(commarea.isPgmEnter()).isTrue();
    // The update is performed BEFORE the transfer (a changed name was saved).
    verify(userSecurityRepository).save(any());
  }

  @Test
  @DisplayName("PF3 returns to the calling program when a from-program is present")
  void pfk03_withFromProgram_returnsToCallingProgram() {
    UserSecurity user = existingUser(USER_ID);
    when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    // No-change update (every field equals the stored record) so the routing is isolated.
    UserUpdateScreen screen = screen(USER_ID, "John", "Doe", "", "U");
    CardDemoCommarea commarea = adminReentered();
    commarea.setFromProgram("COUSR00C");

    String next = service.processUserUpdate(screen, commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COUSR00C");
    assertThat(commarea.getToProgram()).isEqualTo("COUSR00C");
    verify(userSecurityRepository, never()).save(any());
  }

  @Test
  @DisplayName("PF12 returns to the admin menu without attempting any update")
  void pfk12_returnsToAdminMenuWithoutUpdate() {
    UserUpdateScreen screen = screen(USER_ID, "Jane", "Smith", "", "A");
    CardDemoCommarea commarea = adminReentered();

    String next = service.processUserUpdate(screen, commarea, CardWorkArea.Aid.PFK12);

    assertThat(next).isEqualTo("COADM01C");
    assertThat(commarea.getToProgram()).isEqualTo("COADM01C");
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("PF4 clears every editable field and the message line, then redisplays")
  void pfk04_clearsScreenFields() {
    UserUpdateScreen screen = screen(USER_ID, "Jane", "Smith", "secret", "A");
    screen.setErrMsg("stale message");

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.PFK04);

    assertThat(next).isNull();
    assertThat(screen.getUsrIdIn()).isEmpty();
    assertThat(screen.getFName()).isEmpty();
    assertThat(screen.getLName()).isEmpty();
    assertThat(screen.getPasswd()).isEmpty();
    assertThat(screen.getUsrType()).isEmpty();
    assertThat(screen.getErrMsg()).isEmpty();
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("an unsupported attention key redisplays the invalid-key message")
  void unsupportedAid_redisplaysInvalidKey() {
    UserUpdateScreen screen = screen(USER_ID, "Jane", "Smith", "", "A");

    String next = service.processUserUpdate(screen, adminReentered(), CardWorkArea.Aid.PFK06);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }
}
