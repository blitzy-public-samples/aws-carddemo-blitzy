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

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.UserDeleteScreen;
import com.aws.carddemo.exception.AuthorizationException;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.util.Messages;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Online <strong>Delete User</strong> business-logic service for administrator users, migrated from
 * the legacy CICS COBOL program {@code COUSR03C} (CICS transaction {@code CU03}; source {@code
 * legacy/app/cbl/COUSR03C.cbl}).
 *
 * <p>This service reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.1,
 * &sect;0.7.1) &mdash; the pseudo-conversational delete-user flow: it looks up an existing {@code
 * USRSEC} record by user id (populating the read-only first-name, last-name, and user-type fields
 * for confirmation), then on {@code PF5} performs a read-for-update followed by a delete of that
 * {@code user_security} row. Because it mutates the data store it is a <em>write</em> program, so
 * the public entry point is {@link Transactional}: the {@code findById} re-read and the {@code
 * delete} execute atomically, reproducing the CICS {@code READ ... UPDATE} &rarr; {@code DELETE}
 * sequence (the held-for-update lock).
 *
 * <p><strong>Admin-only.</strong> {@code CU03} is reachable only by an administrator ({@code
 * CDEMO-USRTYP-ADMIN}, AAP &sect;0.6.5, &sect;0.7.2). The gate is enforced two ways: the
 * class-level {@link PreAuthorize} annotation (framework method security) and an explicit {@link
 * CardDemoCommarea#isAdmin()} guard that throws {@link AuthorizationException} &mdash; the migrated
 * equivalent of the COBOL user-type check &mdash; mirroring the COBOL branch exactly where it would
 * have rejected an unauthorized navigation attempt.
 *
 * <p><strong>Two precise differences from the sibling Update-User service ({@code
 * COUSR02C}).</strong>
 *
 * <ol>
 *   <li><b>{@code PF3} cancels without acting.</b> Here {@code PF3} returns to the admin menu
 *       <em>without</em> deleting the record (COUSR03C L111-118), unlike {@code COUSR02C} whose
 *       {@code PF3} performs the update first. This difference is preserved exactly.
 *   <li><b>Delete-failure message reuse.</b> The COBOL delete-failure (abnormal {@code RESP})
 *       branch reuses the literal {@code "Unable to Update User..."} verbatim (COUSR03C L332)
 *       rather than a delete-specific message; this byte-for-byte reuse is preserved (see {@link
 *       #MSG_UNABLE_DELETE_USER}).
 * </ol>
 *
 * <p><strong>No password involvement.</strong> The Delete-User screen carries no password field and
 * no {@code PasswordEncoder} is injected; deletion never touches the credential. {@code
 * INITIALIZE-ALL-FIELDS} clears only the user id and the three display attributes.
 *
 * <p><strong>Service-usage rule (AAP &sect;0.6.4).</strong> Validation failures and not-found / I/O
 * conditions are surfaced as on-screen messages with a redisplay (the public method returns {@code
 * null}); they are <em>not</em> thrown. The only thrown condition is the admin-authorization
 * violation ({@link AuthorizationException}). {@link #MSG_UNABLE_LOOKUP_USER} and {@link
 * #MSG_UNABLE_DELETE_USER} are the on-screen fallbacks for unexpected data-store faults (the COBOL
 * {@code WHEN OTHER} abnormal {@code RESP} branches).
 *
 * <p>This class holds no mutable instance state; all pseudo-conversational state is carried by the
 * {@link CardDemoCommarea} and the {@link UserDeleteScreen} passed on each call. Header population
 * ({@code POPULATE-HEADER-INFO}) and the physical screen {@code SEND}/{@code RECEIVE} are
 * controller/view concerns and are intentionally not modeled here. The {@link com.aws.carddemo.web
 * web} controller layer (sibling {@code UserDeleteController}) is the sole caller; this service
 * never references the {@code web} package.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserDeleteService {

  /**
   * Migrated COBOL program identifier ({@code WS-PGMNAME}), stamped into the navigation routing.
   */
  static final String PGM_NAME = "COUSR03C";

  /** CICS transaction identifier ({@code WS-TRANID}) for the delete-user screen. */
  static final String TRAN_ID = "CU03";

  /** Admin-menu program ({@code COADM01C}) — the {@code PF3}/{@code PF12} return target. */
  static final String LIT_ADMIN_PGM = "COADM01C";

  /** Sign-on program ({@code COSGN00C}) — the {@code RETURN-TO-PREV-SCREEN} default target. */
  static final String LIT_SIGNON_PGM = "COSGN00C";

  /** {@code 'User ID can NOT be empty...'} (COUSR03C L147, L179). Empty user-id validation. */
  static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";

  /**
   * {@code 'Press PF5 key to delete this user ...'} (COUSR03C L283). The neutral confirmation
   * prompt shown after a successful {@code ENTER} lookup populates the read-only fields. Note the
   * single space before the ellipsis, preserved verbatim.
   */
  static final String MSG_PRESS_PF5_TO_DELETE = "Press PF5 key to delete this user ...";

  /** {@code 'User ID NOT found...'} (COUSR03C L289, L325). The {@code DFHRESP(NOTFND)} branch. */
  static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

  /** {@code 'Unable to lookup User...'} (COUSR03C L296). The lookup {@code WHEN OTHER} branch. */
  static final String MSG_UNABLE_LOOKUP_USER = "Unable to lookup User...";

  /**
   * {@code 'Unable to Update User...'} (COUSR03C L332).
   *
   * <p><strong>Intentional legacy text reuse.</strong> The delete-failure ({@code WHEN OTHER})
   * branch of {@code DELETE-USER-SEC-FILE} reuses the <em>update</em> message literal verbatim
   * &mdash; it says &quot;Update&quot;, not &quot;Delete&quot;. This is reproduced byte-for-byte
   * for behavioral parity; do not &quot;correct&quot; it to a delete-specific wording.
   */
  static final String MSG_UNABLE_DELETE_USER = "Unable to Update User...";

  /**
   * Prefix of the green success message (COUSR03C L318) built by {@code STRING 'User ' SEC-USR-ID
   * DELIMITED BY SPACE ' has been deleted ...'}. Combined with the trimmed user id and {@link
   * #MSG_USER_DELETED_SUFFIX} to yield {@code "User <id> has been deleted ..."}.
   */
  static final String MSG_USER_DELETED_PREFIX = "User ";

  /**
   * Suffix of the green success message (COUSR03C L320); note the leading space before the id slot.
   */
  static final String MSG_USER_DELETED_SUFFIX = " has been deleted ...";

  /**
   * The single collaborator: the {@code USRSEC} repository (migrated VSAM {@code USRSEC} KSDS).
   *
   * <p>No {@code PasswordEncoder} is required &mdash; the delete flow never reads or writes the
   * password.
   */
  private final UserSecurityRepository userSecurityRepository;

  /**
   * Creates the delete-user service with its sole collaborator.
   *
   * <p>This is the single constructor, so Spring performs constructor injection without an explicit
   * {@code @Autowired}. The collaborator is stored in a {@code private final} field and the service
   * holds no other (mutable) state, making it inherently thread-safe.
   *
   * @param userSecurityRepository the {@code user_security} repository used to look up and delete
   *     the target user record; must not be {@code null}
   */
  public UserDeleteService(UserSecurityRepository userSecurityRepository) {
    this.userSecurityRepository = userSecurityRepository;
  }

  /**
   * Processes one pseudo-conversational interaction with the delete-user screen, reproducing {@code
   * MAIN-PARA} (COUSR03C L82-137).
   *
   * <p>The control flow and perform order are preserved exactly:
   *
   * <ol>
   *   <li><b>Admin gate</b> (AAP &sect;0.6.5, &sect;0.7.2): a non-administrator caller is rejected
   *       with {@link AuthorizationException} (in addition to the class-level {@link
   *       PreAuthorize}).
   *   <li><b>Clear the message line</b> (L84-88: {@code SET ERR-FLG-OFF}; {@code MOVE SPACES TO
   *       WS-MESSAGE, ERRMSGO}).
   *   <li><b>First entry</b> (L95-105: {@code IF NOT CDEMO-PGM-REENTER}): set the re-enter flag;
   *       when the controller handed in a pre-selected user id (from the list screen, the {@code
   *       CDEMO-CU03-USR-SELECTED} hand-off L99-104) run the lookup; then redisplay (return {@code
   *       null}).
   *   <li><b>Re-entry</b> (L106-130: {@code EVALUATE EIBAID}): {@code ENTER} looks up; {@code PF3}
   *       returns to the previous screen <em>without</em> deleting; {@code PF4} clears the screen;
   *       {@code PF5} deletes; {@code PF12} returns to the admin menu; any other key shows the
   *       invalid-key message.
   * </ol>
   *
   * @param screen the delete-user screen contract (read for the user id, mutated with the fetched
   *     fields and the message); must not be {@code null}
   * @param commarea the pseudo-conversational session/navigation state; must not be {@code null}
   * @param aid the resolved attention identifier for this interaction (the controller maps the raw
   *     3270 AID to a {@link CardWorkArea.Aid}); ignored on first entry
   * @return the program name to transfer control to (the {@code EXEC CICS XCTL} target, e.g. {@link
   *     #LIT_ADMIN_PGM} or {@link #LIT_SIGNON_PGM}), or {@code null} to redisplay the delete-user
   *     screen
   * @throws AuthorizationException if {@code commarea} does not denote an administrator (the
   *     admin-only gate)
   * @throws NullPointerException if {@code screen} or {@code commarea} is {@code null}
   */
  @Transactional
  public String processUserDelete(
      UserDeleteScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // Admin-only whole-screen gate (AAP §0.6.5, §0.7.2). CU03 is reachable only by an
    // administrator;
    // a non-admin reaching it is a true authorization violation, so we reject rather than
    // redisplay.
    if (!commarea.isAdmin()) {
      throw new AuthorizationException();
    }

    // L84-88: SET ERR-FLG-OFF; MOVE SPACES TO WS-MESSAGE, ERRMSGO.
    screen.setErrMsg("");
    // Clear the green success channel on every entry so a stale confirmation never lingers behind a
    // later not-found/error redisplay (the two message channels are mutually exclusive; QA F4-1).
    screen.setSuccessMsg("");

    // L95-105: first entry — paint the screen (and process a pre-selected user id from the list).
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter();
      // L99-104: CDEMO-CU03-USR-SELECTED hand-off — the controller pre-populates usrIdIn from the
      // user-list selection. MOVE LOW-VALUES / MOVE -1 (blanking + cursor) are controller concerns.
      if (!isBlankOrLowValues(screen.getUsrIdIn())) {
        processEnterKey(screen);
      }
      return null;
    }

    // L106-130: re-entry — EVALUATE EIBAID.
    if (aid == CardWorkArea.Aid.ENTER) {
      // L109-110: ENTER — look up and populate the read-only display for confirmation.
      processEnterKey(screen);
      return null;
    }
    if (aid == CardWorkArea.Aid.PFK03) {
      // L111-118: PF3 — return to the from-program (or the admin menu when none is set) WITHOUT
      // deleting. This is the deliberate difference from COUSR02C, whose PF3 performs the action.
      if (isBlankOrLowValues(commarea.getFromProgram())) {
        commarea.setToProgram(LIT_ADMIN_PGM);
      } else {
        commarea.setToProgram(commarea.getFromProgram());
      }
      return returnToPrevScreen(commarea);
    }
    if (aid == CardWorkArea.Aid.PFK04) {
      // L119-120: PF4 — clear the current screen and redisplay.
      clearCurrentScreen(screen);
      return null;
    }
    if (aid == CardWorkArea.Aid.PFK05) {
      // L121-122: PF5 — delete the user; stay on the screen with the result message.
      deleteUserInfo(screen);
      return null;
    }
    if (aid == CardWorkArea.Aid.PFK12) {
      // L123-125: PF12 — return to the admin menu.
      commarea.setToProgram(LIT_ADMIN_PGM);
      return returnToPrevScreen(commarea);
    }

    // L126-129: WHEN OTHER — invalid key.
    screen.setErrMsg(Messages.MSG_INVALID_KEY);
    return null;
  }

  /**
   * Validates the entered user id and looks up the target record, reproducing {@code
   * PROCESS-ENTER-KEY} (COUSR03C L142-169).
   *
   * <p>The COBOL uses a shared {@code WS-ERR-FLG}: once an error is flagged, every subsequent
   * {@code IF NOT ERR-FLG-ON} block is skipped and control falls through to redisplay. That is
   * reproduced by returning immediately after the first error condition is handled (an observably
   * equivalent short-circuit). The flow is:
   *
   * <ol>
   *   <li><b>Empty user id</b> (L144-150): a blank/low-values {@code USRIDINI} yields {@link
   *       #MSG_USERID_EMPTY} and a redisplay.
   *   <li><b>Blank the display fields</b> (L157-159): {@code MOVE SPACES TO FNAMEI, LNAMEI,
   *       USRTYPEI} clears any previously fetched attributes before the read.
   *   <li><b>Read</b> (L160-161): {@code MOVE USRIDINI TO SEC-USR-ID; PERFORM READ-USER-SEC-FILE}.
   *       On not-found / abnormal {@code RESP} the message is set by {@link
   *       #readUserSecFile(UserDeleteScreen, String)} and this method returns.
   *   <li><b>Populate + prompt</b> (L164-168): on a successful read the read-only first name, last
   *       name, and user type are placed on the screen and the neutral {@link
   *       #MSG_PRESS_PF5_TO_DELETE} prompt is shown.
   * </ol>
   *
   * @param screen the delete-user screen contract (read for the user id, mutated with the fetched
   *     fields and the message)
   */
  private void processEnterKey(UserDeleteScreen screen) {
    // L144-150: empty user-id check.
    if (isBlankOrLowValues(screen.getUsrIdIn())) {
      screen.setErrMsg(MSG_USERID_EMPTY);
      return;
    }

    // L157-159: MOVE SPACES TO FNAMEI/LNAMEI/USRTYPEI — blank the display fields before the read.
    screen.setFName("");
    screen.setLName("");
    screen.setUsrType("");

    // L160-161: MOVE USRIDINI TO SEC-USR-ID; PERFORM READ-USER-SEC-FILE.
    Optional<UserSecurity> result = readUserSecFile(screen, screen.getUsrIdIn());
    if (result.isEmpty()) {
      // NOTFND / abnormal RESP — the message was already set by readUserSecFile; redisplay.
      return;
    }

    // L164-168: IF NOT ERR-FLG-ON — populate the read-only display fields and prompt for PF5.
    UserSecurity user = result.get();
    screen.setFName(user.getSecUsrFname());
    screen.setLName(user.getSecUsrLname());
    screen.setUsrType(user.getSecUsrType());
    // L283: the neutral 'Press PF5 key to delete this user ...' prompt (the COBOL sets this in
    // READ-USER-SEC-FILE's NORMAL branch; deferred here to the found-path for the same net
    // display).
    screen.setErrMsg(MSG_PRESS_PF5_TO_DELETE);
  }

  /**
   * Validates the user id and performs the delete, reproducing {@code DELETE-USER-INFO} (COUSR03C
   * L174-192).
   *
   * <p>After the empty-id guard (L176-182) the COBOL performs {@code READ-USER-SEC-FILE} (the
   * read-for-update) and then {@code DELETE-USER-SEC-FILE}. This method re-reads the record inside
   * the {@link Transactional} boundary established by {@link #processUserDelete(UserDeleteScreen,
   * CardDemoCommarea, CardWorkArea.Aid)} (the {@code READ ... UPDATE} held-for-update lock) and,
   * only when the record is found, deletes it. When the record is not found the message has already
   * been set by {@link #readUserSecFile(UserDeleteScreen, String)} and the delete is skipped
   * &mdash; this preserves the observable {@code 'User ID NOT found...'} of the COBOL {@code
   * DELETE-USER-SEC-FILE} {@code NOTFND} branch (L323-328) while avoiding a delete against a
   * non-existent key.
   *
   * @param screen the delete-user screen contract (read for the user id, mutated with the result
   *     message and, on success, the cleared fields)
   */
  private void deleteUserInfo(UserDeleteScreen screen) {
    // L176-182: empty user-id check.
    if (isBlankOrLowValues(screen.getUsrIdIn())) {
      screen.setErrMsg(MSG_USERID_EMPTY);
      return;
    }

    // L188-191: PERFORM READ-USER-SEC-FILE (read-for-update) then PERFORM DELETE-USER-SEC-FILE.
    Optional<UserSecurity> result = readUserSecFile(screen, screen.getUsrIdIn());
    if (result.isEmpty()) {
      // Not found (or lookup error): the message is already set; do not attempt the delete.
      return;
    }
    deleteUserSecFile(screen, result.get());
  }

  /**
   * Reads the {@code USRSEC} record, reproducing {@code READ-USER-SEC-FILE} (COUSR03C L267-300).
   *
   * <p>The COBOL {@code EXEC CICS READ ... UPDATE} maps to {@code userSecurityRepository.findById};
   * within the caller's transaction this is the read-for-update. The {@code RESP} evaluation maps
   * to the established FILE-STATUS strategy (AAP &sect;0.6.4):
   *
   * <ul>
   *   <li>{@code DFHRESP(NORMAL)} (FILE STATUS {@code '00'}) &rarr; a present {@link Optional}. The
   *       COBOL also sets the {@code 'Press PF5 ...'} prompt here; that message is deferred to the
   *       caller ({@link #processEnterKey(UserDeleteScreen)}) so the delete path is not given a
   *       prompt it would immediately overwrite.
   *   <li>{@code DFHRESP(NOTFND)} (FILE STATUS {@code '23'}, L287-292) &rarr; {@link
   *       Optional#empty()} with {@link #MSG_USER_NOT_FOUND} set on the screen.
   *   <li>{@code WHEN OTHER} (any other I/O error, L293-299) &rarr; {@link Optional#empty()} with
   *       {@link #MSG_UNABLE_LOOKUP_USER} set on the screen (the abnormal branch is shown, not
   *       thrown, per the service-usage rule).
   * </ul>
   *
   * @param screen the screen contract, mutated with the error message on a not-found or I/O failure
   * @param userId the user id (the {@code USRSEC} key) to read
   * @return the user record when found, otherwise {@link Optional#empty()} (message already set)
   */
  private Optional<UserSecurity> readUserSecFile(UserDeleteScreen screen, String userId) {
    try {
      Optional<UserSecurity> result = userSecurityRepository.findById(userId);
      if (result.isEmpty()) {
        // L287-292: DFHRESP(NOTFND).
        screen.setErrMsg(MSG_USER_NOT_FOUND);
      }
      return result;
    } catch (DataAccessException ex) {
      // L293-299: WHEN OTHER — abnormal RESP (any non-NORMAL, non-NOTFND I/O error).
      screen.setErrMsg(MSG_UNABLE_LOOKUP_USER);
      return Optional.empty();
    }
  }

  /**
   * Deletes the {@code USRSEC} record, reproducing {@code DELETE-USER-SEC-FILE} (COUSR03C
   * L305-336).
   *
   * <p>The COBOL {@code EXEC CICS DELETE} maps to {@code userSecurityRepository.delete}; the
   * managed entity read for update in the same transaction is removed (deleting by the read entity
   * avoids any ambiguity in the fixed-width {@code char(8)} key representation). The {@code RESP}
   * evaluation maps as follows:
   *
   * <ul>
   *   <li>{@code DFHRESP(NORMAL)} (L313-322) &rarr; the COBOL performs {@code
   *       INITIALIZE-ALL-FIELDS} <em>before</em> building the green success message, so this method
   *       clears the fields via {@link #initializeAllFields(UserDeleteScreen)} and then sets the
   *       success text. The message is composed exactly as the COBOL {@code STRING} (L318-321):
   *       {@link #MSG_USER_DELETED_PREFIX} + the user id (the COBOL {@code DELIMITED BY SPACE}
   *       keeps the id up to the first space, equivalent to {@link String#trim()} for a
   *       space-padded key with no embedded spaces) + {@link #MSG_USER_DELETED_SUFFIX}.
   *   <li>{@code WHEN OTHER} (L329-335) &rarr; {@link #MSG_UNABLE_DELETE_USER} is shown.
   *       <strong>Note the intentional legacy text reuse</strong>: the COBOL reuses the {@code
   *       'Unable to Update User...'} literal here verbatim (it says &quot;Update&quot;, not
   *       &quot;Delete&quot;); this is preserved byte-for-byte.
   * </ul>
   *
   * <p>The COBOL {@code DELETE-USER-SEC-FILE} {@code NOTFND} branch (L323-328) is unreachable on
   * this path because the record was just read for update; its observable {@code 'User ID NOT
   * found...'} is preserved upstream by the not-found guard in {@link
   * #deleteUserInfo(UserDeleteScreen)}. The failure is shown on screen, not thrown (the program has
   * no abend path for this flow).
   *
   * @param screen the screen contract, mutated with the cleared fields + the success/error message
   * @param user the managed user-security entity to delete (read for update in this transaction)
   */
  private void deleteUserSecFile(UserDeleteScreen screen, UserSecurity user) {
    // Capture the key before clearing the fields, mirroring SEC-USR-ID (which still holds the read
    // key) used by the COBOL success STRING after INITIALIZE-ALL-FIELDS (L315-321).
    String userId = user.getSecUsrId();
    try {
      // L307-311: EXEC CICS DELETE — remove the managed entity read for update in this transaction.
      userSecurityRepository.delete(user);
      // L313-322: DFHRESP(NORMAL) — clear the fields, then build the green success message.
      // COUSR03C L317 MOVEs DFHGREEN to the ERRMSG colour attribute on this success path, so the
      // confirmation renders GREEN (not the red of a not-found/error message). Route it through
      // setSuccessMsg so it renders via .bms-success; errMsg was just cleared by
      // initializeAllFields,
      // keeping the two channels mutually exclusive (QA F4-1).
      initializeAllFields(screen);
      screen.setSuccessMsg(MSG_USER_DELETED_PREFIX + userId.trim() + MSG_USER_DELETED_SUFFIX);
    } catch (DataAccessException ex) {
      // L329-335: WHEN OTHER — unexpected I/O error. The COBOL reuses the 'Unable to Update
      // User...'
      // literal here verbatim (see MSG_UNABLE_DELETE_USER); preserved byte-for-byte for parity.
      screen.setErrMsg(MSG_UNABLE_DELETE_USER);
    }
  }

  /**
   * Clears the screen for redisplay, reproducing {@code CLEAR-CURRENT-SCREEN} (COUSR03C L341-344).
   *
   * <p>The COBOL performs {@code INITIALIZE-ALL-FIELDS} and then {@code SEND-USRDEL-SCREEN}; the
   * send (redisplay) is handled by the caller returning {@code null}, so this method only clears
   * the fields.
   *
   * @param screen the screen contract to clear
   */
  private void clearCurrentScreen(UserDeleteScreen screen) {
    initializeAllFields(screen);
  }

  /**
   * Clears the editable and display fields, reproducing {@code INITIALIZE-ALL-FIELDS} (COUSR03C
   * L349-356).
   *
   * <p>The COBOL moves {@code -1} to {@code USRIDINL} (cursor positioning — a controller concern,
   * not modeled here) and {@code SPACES} to {@code USRIDINI}, {@code FNAMEI}, {@code LNAMEI},
   * {@code USRTYPEI}, and {@code WS-MESSAGE}. There is no password field on the delete screen, so
   * none is cleared. Each text field is cleared to the empty string.
   *
   * @param screen the screen contract to clear
   */
  private void initializeAllFields(UserDeleteScreen screen) {
    // L351: MOVE -1 TO USRIDINL (cursor) is a controller/rendering concern and is not modeled here.
    // L352-356: MOVE SPACES TO USRIDINI, FNAMEI, LNAMEI, USRTYPEI, WS-MESSAGE.
    screen.setUsrIdIn("");
    screen.setFName("");
    screen.setLName("");
    screen.setUsrType("");
    screen.setErrMsg("");
    // Clear the green success channel too (single legacy ERRMSG field; modeled here as two mutually
    // exclusive channels) so a PF4 clear never leaves a stale confirmation behind (QA F4-1).
    screen.setSuccessMsg("");
  }

  /**
   * Resolves the program to transfer control to, reproducing {@code RETURN-TO-PREV-SCREEN}
   * (COUSR03C L197-208).
   *
   * <p>When {@code CDEMO-TO-PROGRAM} is blank/low-values it defaults to {@link #LIT_SIGNON_PGM}
   * (L199-201). The from-transaction and from-program are then stamped with this program's identity
   * (L202-203) and the program context is reset to the "enter" state ({@code MOVE ZEROS TO
   * CDEMO-PGM-CONTEXT}, L204, i.e. {@link CardDemoCommarea#PGM_CONTEXT_ENTER}). The actual {@code
   * EXEC CICS XCTL} (L205-208) is performed by the controller using the returned program name.
   *
   * @param commarea the session/navigation state, mutated with the routing fields
   * @return the target program name (the {@code XCTL} target)
   */
  private String returnToPrevScreen(CardDemoCommarea commarea) {
    // L199-201: default the target to sign-on when unset.
    if (isBlankOrLowValues(commarea.getToProgram())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
    }
    // L202-204: stamp the from-routing and reset the program context to the 'enter' state.
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setPgmContext(CardDemoCommarea.PGM_CONTEXT_ENTER);
    // L205-208: the EXEC CICS XCTL is performed by the controller using the returned program name.
    return commarea.getToProgram();
  }

  /**
   * Returns whether the value is COBOL {@code SPACES} or {@code LOW-VALUES} (the {@code = SPACES OR
   * LOW-VALUES} test used throughout {@code COUSR03C}).
   *
   * <p>{@code null}, the empty string, an all-spaces string, and an all-{@code NUL} ({@code
   * LOW-VALUES}) string are all treated as blank; any other content is not.
   *
   * @param value the value to test, possibly {@code null}
   * @return {@code true} when the value is {@code null}, empty, all spaces, or all low-values
   */
  private static boolean isBlankOrLowValues(String value) {
    if (value == null) {
      return true;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != ' ' && c != '\0') {
        return false;
      }
    }
    return true;
  }
}
