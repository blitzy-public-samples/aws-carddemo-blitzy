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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.SignonScreen;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.util.Messages;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link SignonService}, the online sign-on
 * business-logic service migrated from the legacy CICS COBOL program {@code COSGN00C} (CICS
 * transaction {@code CC00}; behavioral spec {@code legacy/app/cbl/COSGN00C.cbl} with copybooks
 * {@code COCOM01Y} (COMMAREA navigation), {@code CSUSR01Y} (security record), and {@code CSMSG01Y}
 * (shared messages)).
 *
 * <p>The two collaborators are mocked &mdash; the {@link UserSecurityRepository} that replaces the
 * legacy VSAM {@code USRSEC} KSDS read and the {@link PasswordEncoder} that replaces the legacy
 * clear-text password comparison (Agent Action Plan &sect;0.6.6). There is <strong>no</strong>
 * Spring context, {@code @SpringBootTest}, {@code @MockBean}, or Testcontainers; the service is
 * constructor-injected with the two mocks via {@link InjectMocks} and exercised directly so each
 * control-flow branch is asserted in isolation.
 *
 * <p>Parity points pinned by these tests (Agent Action Plan):
 *
 * <ul>
 *   <li><strong>Control flow / PERFORM order</strong> (&sect;0.7.1): a Mockito {@link InOrder}
 *       proves the security-store read ({@code READ-USER-SEC-FILE}) happens <em>before</em> the
 *       password verification, and {@link #enterKey_userNotFound_redisplaysUserNotFoundMessage()}
 *       proves the verification is skipped entirely when the user is absent.
 *   <li><strong>Navigation / state parity</strong> (&sect;0.6.5): an administrator routes to {@link
 *       SignonService#ADMIN_MENU_PROGRAM} ({@code COADM01C}) and a standard user to {@link
 *       SignonService#MAIN_MENU_PROGRAM} ({@code COMEN01C}); the {@link CardDemoCommarea} captures
 *       the upper-cased user id, the role, and the {@code from}-program/transaction context.
 *   <li><strong>Message-literal parity</strong>: every redisplay branch carries the byte-exact
 *       COBOL message literal (pinned against the production constants).
 *   <li><strong>Credential hygiene</strong> (&sect;0.6.6): no test stores or asserts a clear-text
 *       password; all password verification goes through the mocked {@link PasswordEncoder} and the
 *       stored value is the obviously-fake placeholder hash {@link #HASH}.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SignonServiceTest {

  /** Upper-cased administrator user id (the seed {@code ADMIN001}, type {@code 'A'}). */
  private static final String ADMIN_ID = "ADMIN001";

  /** Upper-cased standard user id (the seed {@code USER0001}, type {@code 'U'}). */
  private static final String USER_ID = "USER0001";

  /**
   * Obviously-fake stored "hash" placeholder. It is never a real BCrypt hash and never a real
   * credential; the {@link PasswordEncoder} is mocked, so no hashing actually occurs (AAP
   * &sect;0.6.6).
   */
  private static final String HASH = "$2a$HASH";

  /**
   * Fake raw password placeholder typed into the screen. Its value is irrelevant because the
   * encoder is mocked with an {@code any()} matcher on the raw argument; it is intentionally
   * <em>not</em> the documented default credential.
   */
  private static final String RAW_PASSWORD = "raw-pw";

  @Mock private UserSecurityRepository userSecurityRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @InjectMocks private SignonService service;

  // ===== Fixtures / helpers
  // =======================================================================

  /**
   * Builds a sign-on screen carrying the supplied raw operator input, leaving {@code errMsg} unset.
   */
  private static SignonScreen screenWith(String userId, String passwd) {
    SignonScreen screen = new SignonScreen();
    screen.setUserId(userId);
    screen.setPasswd(passwd);
    return screen;
  }

  /**
   * Builds a security record with the supplied id, role, and stored hash. The names are arbitrary
   * (never asserted) and no clear-text password is ever stored (AAP &sect;0.6.6).
   */
  private static UserSecurity user(String id, String type, String hash) {
    UserSecurity entity = new UserSecurity();
    entity.setSecUsrId(id);
    entity.setSecUsrType(type);
    entity.setSecUsrPwd(hash);
    entity.setSecUsrFname("Test");
    entity.setSecUsrLname("User");
    return entity;
  }

  // ===== Message + routing constant parity
  // ========================================================

  @Test
  @DisplayName("Message and routing constants are byte-exact with the COSGN00C literals")
  void messageAndRoutingConstants_areByteExactWithCobolLiterals() {
    // Inline MOVE literals from COSGN00C (PROCESS-ENTER-KEY / READ-USER-SEC-FILE). A single-byte
    // drift fails the test, pinning byte-exact message parity (AAP §0.6 golden-file parity).
    assertThat(SignonService.MSG_ENTER_USERID).isEqualTo("Please enter User ID ...");
    assertThat(SignonService.MSG_ENTER_PASSWORD).isEqualTo("Please enter Password ...");
    assertThat(SignonService.MSG_WRONG_PASSWORD).isEqualTo("Wrong Password. Try again ...");
    assertThat(SignonService.MSG_USER_NOT_FOUND).isEqualTo("User not found. Try again ...");
    assertThat(SignonService.MSG_UNABLE_TO_VERIFY).isEqualTo("Unable to verify the User ...");

    // The two XCTL routing targets are the navigation-parity crux (AAP §0.6.5).
    assertThat(SignonService.ADMIN_MENU_PROGRAM).isEqualTo("COADM01C");
    assertThat(SignonService.MAIN_MENU_PROGRAM).isEqualTo("COMEN01C");

    // Shared CSMSG01Y messages used by the PF3 / invalid-key branches, trimmed of their PIC X(50)
    // padding exactly as the service renders them.
    assertThat(Messages.MSG_THANK_YOU.trim())
        .isEqualTo("Thank you for using CardDemo application...");
    assertThat(Messages.MSG_INVALID_KEY.trim())
        .isEqualTo("Invalid key pressed. Please see below...");
  }

  // ===== ENTER: field validation (no I/O)
  // =========================================================

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " "})
  @DisplayName(
      "ENTER + blank user id (null/empty/spaces): redisplays 'enter User ID', no collaborator I/O")
  void enterKey_withBlankUserId_redisplaysEnterUserIdMessage_andDoesNotTouchRepository(
      String blankUserId) {
    CardDemoCommarea commarea = new CardDemoCommarea();
    // A blank user id short-circuits before the password check, so the password value is
    // irrelevant. null is the COBOL LOW-VALUES analogue; "" / " " are the SPACES analogue.
    SignonScreen screen = screenWith(blankUserId, RAW_PASSWORD);

    String next = service.processSignon(screen, commarea, CardWorkArea.Aid.ENTER);

    // PROCESS-ENTER-KEY L118-122: WHEN USERIDI = SPACES OR LOW-VALUES -> redisplay, no read.
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(SignonService.MSG_ENTER_USERID);
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " "})
  @DisplayName(
      "ENTER + blank password (null/empty/spaces): redisplays 'enter Password', no collaborator I/O")
  void enterKey_withBlankPassword_redisplaysEnterPasswordMessage_andDoesNotTouchRepository(
      String blankPassword) {
    CardDemoCommarea commarea = new CardDemoCommarea();
    // The user id is present, so control reaches the password check; null/SPACES both redisplay.
    SignonScreen screen = screenWith(ADMIN_ID, blankPassword);

    String next = service.processSignon(screen, commarea, CardWorkArea.Aid.ENTER);

    // PROCESS-ENTER-KEY L123-127: WHEN PASSWDI = SPACES OR LOW-VALUES -> redisplay, no read.
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(SignonService.MSG_ENTER_PASSWORD);
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  // ===== ENTER: read -> verify -> route (the success archetype)
  // ===================================

  @Test
  @DisplayName("ENTER + valid admin credentials: routes to COADM01C (read precedes password match)")
  void enterKey_adminCredentialsValid_routesToAdminMenu() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    SignonScreen screen = screenWith(ADMIN_ID, RAW_PASSWORD);
    when(userSecurityRepository.findById(ADMIN_ID))
        .thenReturn(Optional.of(user(ADMIN_ID, "A", HASH)));
    when(passwordEncoder.matches(any(), eq(HASH))).thenReturn(true);

    String next = service.processSignon(screen, commarea, CardWorkArea.Aid.ENTER);

    // READ-USER-SEC-FILE L231-233: CDEMO-USRTYP-ADMIN ('A') -> XCTL COADM01C.
    assertThat(next).isEqualTo(SignonService.ADMIN_MENU_PROGRAM);
    // L224-228: the navigation/role context is captured into the COMMAREA.
    assertThat(commarea.getUserId()).isEqualTo(ADMIN_ID);
    assertThat(commarea.getUserType()).isEqualTo("A");
    assertThat(commarea.isAdmin()).isTrue();
    assertThat(commarea.isUser()).isFalse();
    assertThat(commarea.getFromProgram()).isEqualTo(SignonService.PGM_NAME);
    assertThat(commarea.getFromTranId()).isEqualTo(SignonService.TRAN_ID);
    assertThat(commarea.isPgmEnter()).isTrue();
    // The success path never sets an error message.
    assertThat(screen.getErrMsg()).isEmpty();

    // Control-flow parity (AAP §0.7.1): READ-USER-SEC-FILE then the password compare, in that
    // order.
    InOrder inOrder = inOrder(userSecurityRepository, passwordEncoder);
    inOrder.verify(userSecurityRepository).findById(ADMIN_ID);
    inOrder.verify(passwordEncoder).matches(any(), eq(HASH));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  @DisplayName("ENTER + valid standard-user credentials: routes to COMEN01C (read precedes match)")
  void enterKey_userCredentialsValid_routesToMainMenu() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    SignonScreen screen = screenWith(USER_ID, RAW_PASSWORD);
    when(userSecurityRepository.findById(USER_ID))
        .thenReturn(Optional.of(user(USER_ID, "U", HASH)));
    when(passwordEncoder.matches(any(), eq(HASH))).thenReturn(true);

    String next = service.processSignon(screen, commarea, CardWorkArea.Aid.ENTER);

    // READ-USER-SEC-FILE L236-238: a non-admin type -> XCTL COMEN01C.
    assertThat(next).isEqualTo(SignonService.MAIN_MENU_PROGRAM);
    assertThat(commarea.getUserId()).isEqualTo(USER_ID);
    assertThat(commarea.getUserType()).isEqualTo("U");
    assertThat(commarea.isUser()).isTrue();
    assertThat(commarea.isAdmin()).isFalse();
    assertThat(commarea.getFromProgram()).isEqualTo(SignonService.PGM_NAME);
    assertThat(commarea.getFromTranId()).isEqualTo(SignonService.TRAN_ID);
    assertThat(commarea.isPgmEnter()).isTrue();
    assertThat(screen.getErrMsg()).isEmpty();

    InOrder inOrder = inOrder(userSecurityRepository, passwordEncoder);
    inOrder.verify(userSecurityRepository).findById(USER_ID);
    inOrder.verify(passwordEncoder).matches(any(), eq(HASH));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  @DisplayName("ENTER + wrong password: redisplays 'Wrong Password' and routes nowhere")
  void enterKey_wrongPassword_redisplaysWrongPasswordMessage() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    SignonScreen screen = screenWith(ADMIN_ID, RAW_PASSWORD);
    when(userSecurityRepository.findById(ADMIN_ID))
        .thenReturn(Optional.of(user(ADMIN_ID, "A", HASH)));
    when(passwordEncoder.matches(any(), eq(HASH))).thenReturn(false);

    String next = service.processSignon(screen, commarea, CardWorkArea.Aid.ENTER);

    // READ-USER-SEC-FILE L241-245: SEC-USR-PWD mismatch -> redisplay, no routing.
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(SignonService.MSG_WRONG_PASSWORD);
    // No role/routing context is established on a failed authentication.
    assertThat(commarea.getUserType()).isNull();
    assertThat(commarea.isAdmin()).isFalse();
    assertThat(commarea.isUser()).isFalse();
    assertThat(commarea.getFromProgram()).isNull();
    assertThat(commarea.getFromTranId()).isNull();

    // The read still precedes the (failed) password compare.
    InOrder inOrder = inOrder(userSecurityRepository, passwordEncoder);
    inOrder.verify(userSecurityRepository).findById(ADMIN_ID);
    inOrder.verify(passwordEncoder).matches(any(), eq(HASH));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  @DisplayName("ENTER + unknown user: redisplays 'User not found' and never checks the password")
  void enterKey_userNotFound_redisplaysUserNotFoundMessage() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    SignonScreen screen = screenWith(ADMIN_ID, RAW_PASSWORD);
    // READ-USER-SEC-FILE WHEN 13 / NOTFND (FILE STATUS '23'): Optional.empty().
    when(userSecurityRepository.findById(ADMIN_ID)).thenReturn(Optional.empty());

    String next = service.processSignon(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(SignonService.MSG_USER_NOT_FOUND);
    // Control-flow parity (AAP §0.7.1): when the record is absent the password compare is skipped.
    verify(passwordEncoder, never()).matches(any(), any());
  }

  @Test
  @DisplayName("ENTER + data-access failure: redisplays 'Unable to verify' and does not propagate")
  void enterKey_repositoryThrows_redisplaysUnableToVerify_andDoesNotThrow() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    SignonScreen screen = screenWith(ADMIN_ID, RAW_PASSWORD);
    // READ-USER-SEC-FILE WHEN OTHER: an unexpected store failure. COSGN00C displays the status and
    // does NOT abend the online transaction, so the service must handle it gracefully.
    when(userSecurityRepository.findById(any())).thenThrow(new DataRetrievalFailureException("io"));

    String next = service.processSignon(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(SignonService.MSG_UNABLE_TO_VERIFY);
    // The exception is swallowed (redisplay), so the password compare never runs.
    verify(passwordEncoder, never()).matches(any(), any());
  }

  // ===== AID dispatch (PF3, invalid keys)
  // =========================================================

  @Test
  @DisplayName(
      "PF3: renders the thank-you message and exits (returns null), touching no collaborator")
  void pfk03_rendersThankYouMessage_andExits() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    SignonScreen screen = screenWith(ADMIN_ID, RAW_PASSWORD);

    String next = service.processSignon(screen, commarea, CardWorkArea.Aid.PFK03);

    // MAIN-PARA WHEN DFHPF3: SEND-PLAIN-TEXT thank-you then RETURN with no TRANSID (exit).
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_THANK_YOU.trim());
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @ParameterizedTest
  @EnumSource(
      value = CardWorkArea.Aid.class,
      names = {"ENTER", "PFK03"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Any unsupported AID (incl. PFK10): redisplays the invalid-key message")
  void unsupportedAid_redisplaysInvalidKeyMessage(CardWorkArea.Aid aid) {
    CardDemoCommarea commarea = new CardDemoCommarea();
    SignonScreen screen = screenWith(ADMIN_ID, RAW_PASSWORD);

    String next = service.processSignon(screen, commarea, aid);

    // MAIN-PARA WHEN OTHER: CLEAR / PA1 / PA2 / all PF keys except PF3 -> invalid-key redisplay.
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("Null AID (unmapped raw key): redisplays the invalid-key message")
  void nullAid_redisplaysInvalidKeyMessage() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    SignonScreen screen = screenWith(ADMIN_ID, RAW_PASSWORD);

    // A raw key that did not resolve to a known Aid falls through to the WHEN OTHER branch.
    String next = service.processSignon(screen, commarea, null);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  // ===== Upper-casing + initial paint + null-guards
  // ===============================================

  @Test
  @DisplayName(
      "User id is upper-cased (COBOL FUNCTION UPPER-CASE) before the security-store lookup")
  void userId_isUpperCased_beforeLookup() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    // Lower-case input; the read is keyed by the upper-cased value, mirroring COSGN00C L132-136.
    SignonScreen screen = screenWith("admin001", RAW_PASSWORD);
    when(userSecurityRepository.findById(any())).thenReturn(Optional.empty());

    service.processSignon(screen, commarea, CardWorkArea.Aid.ENTER);

    ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
    verify(userSecurityRepository).findById(idCaptor.capture());
    assertThat(idCaptor.getValue()).isEqualTo(ADMIN_ID);
    // The upper-cased id is also captured into the COMMAREA before the read (L134).
    assertThat(commarea.getUserId()).isEqualTo(ADMIN_ID);
  }

  @Test
  @DisplayName(
      "prepareInitialScreen clears the input fields, clears the error line, and paints the header")
  void prepareInitialScreen_clearsInputAndPopulatesHeader() {
    SignonScreen screen = screenWith("stale-user", "stale-pass");
    screen.setErrMsg("stale error");

    service.prepareInitialScreen(screen);

    // MAIN-PARA EIBCALEN = 0 (L80-83): MOVE LOW-VALUES then SEND-SIGNON-SCREEN with no message.
    assertThat(screen.getUserId()).isEmpty();
    assertThat(screen.getPasswd()).isEmpty();
    assertThat(screen.getErrMsg()).isEmpty();
    // POPULATE-HEADER-INFO: transaction id, program name, titles, and the current date/time.
    assertThat(screen.getTrnName()).isEqualTo(SignonService.TRAN_ID);
    assertThat(screen.getPgmName()).isEqualTo(SignonService.PGM_NAME);
    assertThat(screen.getTitle01()).isNotEmpty();
    assertThat(screen.getTitle02()).isNotEmpty();
    assertThat(screen.getCurDate()).isNotBlank();
    assertThat(screen.getCurTime()).isNotBlank();
    // The initial paint performs no security-store read.
    verifyNoInteractions(userSecurityRepository, passwordEncoder);
  }

  @Test
  @DisplayName("processSignon rejects a null screen with NullPointerException")
  void processSignon_nullScreen_throwsNullPointerException() {
    CardDemoCommarea commarea = new CardDemoCommarea();

    assertThatThrownBy(() -> service.processSignon(null, commarea, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("screen must not be null");
  }

  @Test
  @DisplayName("processSignon rejects a null commarea with NullPointerException")
  void processSignon_nullCommarea_throwsNullPointerException() {
    SignonScreen screen = screenWith(ADMIN_ID, RAW_PASSWORD);

    assertThatThrownBy(() -> service.processSignon(screen, null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("commarea must not be null");
  }
}
