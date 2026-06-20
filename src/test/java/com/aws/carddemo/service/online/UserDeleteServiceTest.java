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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.UserDeleteScreen;
import com.aws.carddemo.exception.AuthorizationException;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.util.Messages;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link UserDeleteService}, the online
 * <strong>Delete User</strong> business-logic service migrated from the legacy CICS COBOL program
 * {@code COUSR03C} (CICS transaction {@code CU03}, <strong>admin-only</strong>; behavioral spec
 * {@code legacy/app/cbl/COUSR03C.cbl} and security copybook {@code legacy/app/cpy/CSUSR01Y.cpy}).
 *
 * <p>These tests run with <strong>no Spring context</strong>, no {@code @MockBean} and no database
 * (Testcontainers and the {@code @Transactional}/{@code @PreAuthorize} framework behaviors are
 * exercised by integration tests elsewhere). The service is driven directly with its sole
 * collaborator {@link UserSecurityRepository} replaced by a Mockito mock; no {@code
 * PasswordEncoder} is involved because the delete flow never reads or writes the credential and the
 * delete screen carries <strong>no password field</strong>. Control-flow parity (Agent Action Plan
 * &sect;0.6.5, &sect;0.7.1) is asserted through the externally observable effects of {@link
 * UserDeleteService#processUserDelete(UserDeleteScreen, CardDemoCommarea, CardWorkArea.Aid)}: its
 * return value (the {@code EXEC CICS XCTL} target program, or {@code null} to redisplay), the
 * mutations it makes to the {@link CardDemoCommarea} navigation state and the {@link
 * UserDeleteScreen}, the {@link AuthorizationException} it raises for the whole-screen admin gate,
 * and &mdash; via an {@link InOrder} &mdash; the order of the repository calls.
 *
 * <p>Three behaviors are pinned exactly because they are the signature characteristics of {@code
 * COUSR03C}:
 *
 * <ol>
 *   <li><b>Confirm-then-delete state machine.</b> {@code ENTER} looks the user up and prompts with
 *       {@code 'Press PF5 key to delete this user ...'} (COUSR03C L283); the row is only removed on
 *       {@code PF5}.
 *   <li><b>{@code PF3} cancels without deleting.</b> Unlike the sibling Update-User service, {@code
 *       PF3} here returns to the calling program <em>without</em> performing the delete (asserted
 *       with {@code never()}).
 *   <li><b>Reused failure literal (the key quirk).</b> When the delete itself fails, {@code
 *       COUSR03C} (L332) reuses the literal {@code 'Unable to Update User...'} verbatim &mdash; it
 *       says &quot;Update&quot;, not &quot;Delete&quot;. {@link
 *       #pf5_deleteFailure_reusesUnableToUpdateLiteral()} asserts this byte-for-byte.
 * </ol>
 *
 * <p><strong>FILE STATUS mapping (AAP &sect;0.6.4).</strong> Unlike the read/rewrite services that
 * map an unexpected data-access fault to {@code IoStatusException}, {@code UserDeleteService}
 * <em>catches</em> the {@code DataAccessException} on both the lookup and the delete and surfaces
 * it as an on-screen message with a redisplay (the public method returns {@code null}); it never
 * throws {@code IoStatusException}. The only thrown condition is the admin-authorization violation.
 * {@link #enter_lookupThrowsDataAccess_redisplaysUnableToLookup()} pins this redisplay contract.
 *
 * <p><strong>Credential hygiene.</strong> The fixtures populate a deliberately fake, non-secret
 * password hash on the {@link UserSecurity} entity purely so the entity is well-formed; no test
 * reads or asserts a password value, and the delete screen has no password field.
 */
@ExtendWith(MockitoExtension.class)
class UserDeleteServiceTest {

  /** A valid 8-character {@code USRSEC} key ({@code SEC-USR-ID}) used as the delete target. */
  private static final String TARGET_USER_ID = "USER0007";

  /** The signed-on administrator's id, carried on the {@link CardDemoCommarea}. */
  private static final String ADMIN_USER_ID = "ADMIN001";

  /** First name fetched and displayed read-only for confirmation ({@code SEC-USR-FNAME}). */
  private static final String SAMPLE_FIRST_NAME = "TESTFIRST";

  /** Last name fetched and displayed read-only for confirmation ({@code SEC-USR-LNAME}). */
  private static final String SAMPLE_LAST_NAME = "TESTLAST";

  /**
   * A deliberately fake, non-secret stand-in for the BCrypt password hash. It is never asserted and
   * never read by the delete flow (the delete screen has no password field); it exists only so the
   * {@link UserSecurity} fixture is well formed.
   */
  private static final String FAKE_PWD_HASH = "$2a$10$notARealHashValueForUnitTestsOnly0000000";

  /** Mocked collaborator: the migrated {@code USRSEC} read-for-update / delete path. */
  @Mock private UserSecurityRepository userSecurityRepository;

  /**
   * Service under test; Mockito injects {@link #userSecurityRepository} via the sole constructor.
   */
  @InjectMocks private UserDeleteService service;

  // ===============================================================================================
  // Fixture builders
  // ===============================================================================================

  /**
   * Builds a well-formed {@code user_security} record for the given id, with display attributes and
   * a fake (never-asserted) password hash.
   *
   * @param id the {@code SEC-USR-ID} primary key
   * @return a populated {@link UserSecurity} entity
   */
  private static UserSecurity user(String id) {
    UserSecurity entity = new UserSecurity();
    entity.setSecUsrId(id);
    entity.setSecUsrFname(SAMPLE_FIRST_NAME);
    entity.setSecUsrLname(SAMPLE_LAST_NAME);
    entity.setSecUsrType(CardDemoCommarea.USER_TYPE_USER);
    entity.setSecUsrPwd(FAKE_PWD_HASH); // fake; never asserted, never read by the delete flow
    return entity;
  }

  /**
   * Builds a delete-user screen carrying the supplied lookup key.
   *
   * @param usrIdIn the operator-entered user id ({@code USRIDIN}); may be {@code null} or blank
   * @return a {@link UserDeleteScreen} with only {@code usrIdIn} populated
   */
  private static UserDeleteScreen screen(String usrIdIn) {
    UserDeleteScreen screen = new UserDeleteScreen();
    screen.setUsrIdIn(usrIdIn);
    return screen;
  }

  /**
   * @return a first-entry administrator commarea ({@code pgmContext = ENTER}).
   */
  private static CardDemoCommarea adminCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypAdmin();
    commarea.setUserId(ADMIN_USER_ID);
    return commarea;
  }

  /**
   * @return a re-entered administrator commarea so the {@code EVALUATE EIBAID} branch is reached.
   */
  private static CardDemoCommarea reenteredAdmin() {
    CardDemoCommarea commarea = adminCommarea();
    commarea.setPgmReenter();
    return commarea;
  }

  /**
   * @return a standard (non-admin) user commarea used to exercise the admin gate.
   */
  private static CardDemoCommarea userCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();
    commarea.setUserId("USER0001");
    return commarea;
  }

  // ===============================================================================================
  // Null-argument guards (Objects.requireNonNull at method entry)
  // ===============================================================================================

  @Test
  @DisplayName("a null screen is rejected at method entry with the documented NPE message")
  void nullScreen_throwsNpe() {
    assertThatThrownBy(
            () -> service.processUserDelete(null, adminCommarea(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("screen must not be null");
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("a null commarea is rejected at method entry with the documented NPE message")
  void nullCommarea_throwsNpe() {
    assertThatThrownBy(
            () -> service.processUserDelete(screen(TARGET_USER_ID), null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("commarea must not be null");
    verifyNoInteractions(userSecurityRepository);
  }

  // ===============================================================================================
  // Whole-screen admin gate (AAP §0.6.5, §0.7.2) — non-admin rejected BEFORE any repository access
  // ===============================================================================================

  @Test
  @DisplayName("a non-admin caller is rejected by the admin gate before any repository access")
  void nonAdminUser_isRejectedByGate() {
    assertThatThrownBy(
            () ->
                service.processUserDelete(
                    screen(TARGET_USER_ID), userCommarea(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(AuthorizationException.class)
        .hasMessage(AuthorizationException.ADMIN_ONLY_MESSAGE)
        .hasMessage("No access - Admin Only option...");
    // The gate fires ahead of the state machine, so the repository is never touched.
    verifyNoInteractions(userSecurityRepository);
  }

  // ===============================================================================================
  // First entry (NOT CDEMO-PGM-REENTER) — paint the screen; set the re-enter flag (COUSR03C
  // L95-105)
  // ===============================================================================================

  @Test
  @DisplayName(
      "first entry with no pre-selected id paints a blank screen and sets the re-enter flag")
  void firstEntry_noPreselectedId_paintsScreenAndSetsReenter() {
    UserDeleteScreen screen = screen(null);
    CardDemoCommarea commarea = adminCommarea(); // first entry: pgmContext = ENTER

    String next = service.processUserDelete(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(screen.getErrMsg()).isEmpty();
    // No user id was handed in, so the lookup is skipped entirely.
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("first entry with a pre-selected id looks the user up and prompts for PF5")
  void firstEntry_preselectedId_looksUpAndPromptsPf5() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    CardDemoCommarea commarea = adminCommarea(); // first entry with a list-screen hand-off
    when(userSecurityRepository.findById(TARGET_USER_ID))
        .thenReturn(Optional.of(user(TARGET_USER_ID)));

    String next = service.processUserDelete(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    assertThat(screen.getFName()).isEqualTo(SAMPLE_FIRST_NAME);
    assertThat(screen.getLName()).isEqualTo(SAMPLE_LAST_NAME);
    assertThat(screen.getUsrType()).isEqualTo(CardDemoCommarea.USER_TYPE_USER);
    assertThat(screen.getErrMsg())
        .isEqualTo(UserDeleteService.MSG_PRESS_PF5_TO_DELETE)
        .isEqualTo("Press PF5 key to delete this user ...");
    verify(userSecurityRepository).findById(TARGET_USER_ID);
    verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
    verify(userSecurityRepository, never()).deleteById(anyString());
  }

  // ===============================================================================================
  // ENTER (PROCESS-ENTER-KEY) — lookup + display, no mutation (COUSR03C L142-169, L267-300)
  // ===============================================================================================

  @Test
  @DisplayName("ENTER on an existing user populates the read-only fields and prompts for PF5")
  void enter_existingUser_displaysAndPromptsPf5() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    when(userSecurityRepository.findById(TARGET_USER_ID))
        .thenReturn(Optional.of(user(TARGET_USER_ID)));

    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.ENTER);

    // Redisplay (no XCTL); the read-only confirmation fields are populated from the record.
    assertThat(next).isNull();
    assertThat(screen.getFName()).isEqualTo(SAMPLE_FIRST_NAME);
    assertThat(screen.getLName()).isEqualTo(SAMPLE_LAST_NAME);
    assertThat(screen.getUsrType()).isEqualTo(CardDemoCommarea.USER_TYPE_USER);
    // Byte-exact neutral prompt (COUSR03C L283) — note the single space before the ellipsis.
    assertThat(screen.getErrMsg())
        .isEqualTo(UserDeleteService.MSG_PRESS_PF5_TO_DELETE)
        .isEqualTo("Press PF5 key to delete this user ...");
    // The confirmation step must NOT delete anything yet.
    verify(userSecurityRepository).findById(TARGET_USER_ID);
    verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
    verify(userSecurityRepository, never()).deleteById(anyString());
  }

  @Test
  @DisplayName("ENTER on a missing user redisplays the byte-exact not-found message")
  void enter_userNotFound_redisplaysNotFound() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    when(userSecurityRepository.findById(TARGET_USER_ID)).thenReturn(Optional.empty());

    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(UserDeleteService.MSG_USER_NOT_FOUND)
        .isEqualTo("User ID NOT found...");
    // Display fields were blanked before the read and never repopulated.
    assertThat(screen.getFName()).isEmpty();
    assertThat(screen.getLName()).isEmpty();
    assertThat(screen.getUsrType()).isEmpty();
    verify(userSecurityRepository).findById(TARGET_USER_ID);
    verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
  }

  @Test
  @DisplayName("ENTER with an empty user id redisplays the empty-id message and skips the lookup")
  void enter_emptyUserId_redisplaysEmptyMessage() {
    UserDeleteScreen screen = screen("   "); // blank USRIDIN

    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(UserDeleteService.MSG_USERID_EMPTY)
        .isEqualTo("User ID can NOT be empty...");
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName(
      "an unexpected data-access fault on the lookup is caught and redisplayed, not thrown")
  void enter_lookupThrowsDataAccess_redisplaysUnableToLookup() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    when(userSecurityRepository.findById(TARGET_USER_ID))
        .thenThrow(new DataAccessResourceFailureException("simulated USRSEC outage"));

    // Per the service-usage rule (AAP §0.6.4) UserDeleteService shows the abnormal-RESP branch
    // on screen and redisplays (returns null); it does NOT map to IoStatusException.
    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(UserDeleteService.MSG_UNABLE_LOOKUP_USER)
        .isEqualTo("Unable to lookup User...");
    verify(userSecurityRepository).findById(TARGET_USER_ID);
    verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
  }

  // ===============================================================================================
  // PF5 (DELETE-USER-INFO / DELETE-USER-SEC-FILE) — the confirm step that mutates (COUSR03C
  // L174-336)
  // ===============================================================================================

  @Test
  @DisplayName(
      "PF5 confirms the delete: the row is removed (lookup before delete) and success shown")
  void pf5_confirmsDelete_removesUser_showsSuccess() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    UserSecurity found = user(TARGET_USER_ID);
    when(userSecurityRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(found));

    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    // Byte-exact green success message (COUSR03C L318-321): "User <id> has been deleted ...".
    assertThat(screen.getErrMsg())
        .isEqualTo(
            UserDeleteService.MSG_USER_DELETED_PREFIX
                + TARGET_USER_ID
                + UserDeleteService.MSG_USER_DELETED_SUFFIX)
        .isEqualTo("User USER0007 has been deleted ...")
        .contains(" has been deleted ...")
        .startsWith("User " + TARGET_USER_ID);
    // Control-flow parity (AAP §0.7.1): the read-for-update precedes the delete of that same
    // record.
    InOrder inOrder = inOrder(userSecurityRepository);
    inOrder.verify(userSecurityRepository).findById(TARGET_USER_ID);
    inOrder.verify(userSecurityRepository).delete(found);
    inOrder.verifyNoMoreInteractions();
    // The legacy DELETE never routes through deleteById on this path.
    verify(userSecurityRepository, never()).deleteById(anyString());
  }

  @Test
  @DisplayName(
      "PF5 delete failure reuses the verbatim 'Unable to Update User...' literal (key quirk)")
  void pf5_deleteFailure_reusesUnableToUpdateLiteral() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    UserSecurity found = user(TARGET_USER_ID);
    when(userSecurityRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(found));
    doThrow(new DataAccessResourceFailureException("simulated USRSEC delete failure"))
        .when(userSecurityRepository)
        .delete(found);

    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    // KEY QUIRK (COUSR03C L332): the delete-failure branch reuses the *update* literal verbatim —
    // it says "Update", not "Delete". This byte-for-byte reuse is mandatory and must not be
    // "fixed".
    assertThat(screen.getErrMsg())
        .isEqualTo(UserDeleteService.MSG_UNABLE_DELETE_USER)
        .isEqualTo("Unable to Update User...")
        .contains("Update")
        .doesNotContain("Delete")
        .doesNotContain("deleted");
    // The delete was attempted (after the read-for-update) but failed.
    InOrder inOrder = inOrder(userSecurityRepository);
    inOrder.verify(userSecurityRepository).findById(TARGET_USER_ID);
    inOrder.verify(userSecurityRepository).delete(found);
  }

  @Test
  @DisplayName("PF5 on a missing user shows not-found and never attempts a delete")
  void pf5_userNotFound_skipsDelete() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    when(userSecurityRepository.findById(TARGET_USER_ID)).thenReturn(Optional.empty());

    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(UserDeleteService.MSG_USER_NOT_FOUND)
        .isEqualTo("User ID NOT found...");
    verify(userSecurityRepository).findById(TARGET_USER_ID);
    verify(userSecurityRepository, never()).delete(any(UserSecurity.class));
    verify(userSecurityRepository, never()).deleteById(anyString());
  }

  @Test
  @DisplayName("PF5 with an empty user id shows the empty-id message and never touches the store")
  void pf5_emptyUserId_redisplaysEmptyMessage_noDelete() {
    UserDeleteScreen screen = screen(""); // blank USRIDIN

    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.PFK05);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg())
        .isEqualTo(UserDeleteService.MSG_USERID_EMPTY)
        .isEqualTo("User ID can NOT be empty...");
    verifyNoInteractions(userSecurityRepository);
  }

  // ===============================================================================================
  // PF3 / PF4 / PF12 navigation — PF3 is the deliberate "cancel WITHOUT deleting" (COUSR03C
  // L111-125)
  // ===============================================================================================

  @Test
  @DisplayName("PF3 cancels without deleting and routes back to the calling program")
  void pf3_withFromProgram_cancelsWithoutDelete_routesBackToCaller() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    CardDemoCommarea commarea = reenteredAdmin();
    commarea.setFromProgram("COUSR00C"); // the user-list screen that navigated here

    String next = service.processUserDelete(screen, commarea, CardWorkArea.Aid.PFK03);

    // Routes back to the caller; this program stamps itself as the new "from" context.
    assertThat(next).isEqualTo("COUSR00C");
    assertThat(commarea.getToProgram()).isEqualTo("COUSR00C");
    assertThat(commarea.getFromProgram())
        .isEqualTo(UserDeleteService.PGM_NAME)
        .isEqualTo("COUSR03C");
    assertThat(commarea.getFromTranId()).isEqualTo(UserDeleteService.TRAN_ID).isEqualTo("CU03");
    assertThat(commarea.getPgmContext()).isEqualTo(CardDemoCommarea.PGM_CONTEXT_ENTER);
    // The signature COUSR03C behavior: PF3 never deletes, and shows no success message.
    assertThat(screen.getErrMsg()).isEmpty();
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName(
      "PF3 with no calling program defaults the return target to the admin menu, no delete")
  void pf3_blankFromProgram_defaultsToAdminMenu_withoutDelete() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    CardDemoCommarea commarea = reenteredAdmin(); // fromProgram left blank

    String next = service.processUserDelete(screen, commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo(UserDeleteService.LIT_ADMIN_PGM).isEqualTo("COADM01C");
    assertThat(commarea.getToProgram()).isEqualTo("COADM01C");
    assertThat(commarea.getFromProgram()).isEqualTo("COUSR03C");
    assertThat(commarea.getFromTranId()).isEqualTo("CU03");
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("PF4 clears every screen field for a fresh redisplay and never touches the store")
  void pf4_clearsScreen_noDelete() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    screen.setFName(SAMPLE_FIRST_NAME);
    screen.setLName(SAMPLE_LAST_NAME);
    screen.setUsrType(CardDemoCommarea.USER_TYPE_USER);
    screen.setErrMsg("leftover message");

    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.PFK04);

    assertThat(next).isNull();
    assertThat(screen.getUsrIdIn()).isEmpty();
    assertThat(screen.getFName()).isEmpty();
    assertThat(screen.getLName()).isEmpty();
    assertThat(screen.getUsrType()).isEmpty();
    assertThat(screen.getErrMsg()).isEmpty();
    verifyNoInteractions(userSecurityRepository);
  }

  @Test
  @DisplayName("PF12 returns to the admin menu and never touches the store")
  void pf12_returnsToAdminMenu_noDelete() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);
    CardDemoCommarea commarea = reenteredAdmin();

    String next = service.processUserDelete(screen, commarea, CardWorkArea.Aid.PFK12);

    assertThat(next).isEqualTo(UserDeleteService.LIT_ADMIN_PGM).isEqualTo("COADM01C");
    assertThat(commarea.getToProgram()).isEqualTo("COADM01C");
    assertThat(commarea.getFromProgram()).isEqualTo("COUSR03C");
    assertThat(commarea.getFromTranId()).isEqualTo("CU03");
    assertThat(commarea.getPgmContext()).isEqualTo(CardDemoCommarea.PGM_CONTEXT_ENTER);
    verifyNoInteractions(userSecurityRepository);
  }

  // ===============================================================================================
  // WHEN OTHER — any unmapped AID shows the invalid-key message (COUSR03C L126-129)
  // ===============================================================================================

  @Test
  @DisplayName("an unmapped AID shows the invalid-key message verbatim and never touches the store")
  void invalidKey_showsInvalidKeyMessage_noDelete() {
    UserDeleteScreen screen = screen(TARGET_USER_ID);

    String next = service.processUserDelete(screen, reenteredAdmin(), CardWorkArea.Aid.PFK06);

    assertThat(next).isNull();
    // The service sets the padded Messages constant verbatim (no trim on this screen).
    assertThat(screen.getErrMsg())
        .isEqualTo(Messages.MSG_INVALID_KEY)
        .isEqualTo("Invalid key pressed. Please see below...          ");
    verifyNoInteractions(userSecurityRepository);
  }
}
