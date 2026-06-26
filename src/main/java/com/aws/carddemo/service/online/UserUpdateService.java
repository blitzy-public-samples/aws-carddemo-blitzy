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
import com.aws.carddemo.dto.screen.UserUpdateScreen;
import com.aws.carddemo.exception.AuthorizationException;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.util.Messages;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Online business-logic service for the <strong>Update User</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COUSR02C} (CICS transaction {@code CU02}; behavioral spec {@code
 * legacy/app/cbl/COUSR02C.cbl}, security record layout {@code legacy/app/cpy/CSUSR01Y.cpy}, the
 * navigation COMMAREA {@code legacy/app/cpy/COCOM01Y.cpy}, and the screen mapset {@code
 * legacy/app/bms/COUSR02.bms}).
 *
 * <p><strong>Admin-only (AAP &sect;0.6.5).</strong> {@code CU02} is reachable only from the admin
 * menu. The gate is enforced both declaratively at the framework layer (class-level {@link
 * PreAuthorize}{@code ("hasRole('ADMIN')")}) and behaviorally in {@link #processUserUpdate} (a
 * non-admin {@link CardDemoCommarea} raises {@link AuthorizationException}), mirroring the COBOL
 * {@code CDEMO-USRTYP-ADMIN} check that protects the user-management transactions.
 *
 * <p><strong>Write program — optimistic-update parity (AAP &sect;0.3.3, &sect;0.6).</strong> The
 * legacy program performs a {@code READ … UPDATE} (read-for-update) followed by a compare and a
 * {@code REWRITE}. Because the {@link UserSecurity} entity carries <em>no</em> {@code @Version}
 * column, that concurrency behavior is reproduced in the service layer: {@link #processUserUpdate}
 * is {@link Transactional}, and the in-transaction re-read of the record ({@link #readUserSecFile})
 * immediately followed by the per-field compare and {@code save} forms a single atomic
 * read-for-update → rewrite unit of work.
 *
 * <p><strong>Per-field modify detection.</strong> The COBOL {@code WS-USR-MODIFIED} flag is
 * reproduced exactly: each editable attribute (first name, last name, password, user type) is
 * compared against the stored record, the flag is raised if <em>any</em> differs, and the record is
 * rewritten only when something changed — otherwise the neutral "Please modify to update ..."
 * message is shown and nothing is written.
 *
 * <p><strong>Credential hygiene (AAP &sect;0.6.6, &sect;0.7.2).</strong> The legacy program copies
 * the clear-text {@code SEC-USR-PWD} into the password field on lookup; the modernized credential
 * is a one-way BCrypt hash that must never be exposed, so the password field is left blank on
 * lookup. On update, a blank password means "keep the existing password"; a non-blank password that
 * does not already match the stored hash is re-encoded with the injected {@link PasswordEncoder}.
 * The password is never logged, echoed, or written in clear text, and no credentials are hardcoded.
 * This is the single intentional deviation from byte-exact behavior; everything else is preserved.
 *
 * <p><strong>Two-SEND net-visible semantics.</strong> The legacy program issues multiple {@code
 * EXEC CICS SEND}s per turn where the last one wins. The observable result is preserved: an {@code
 * ENTER} lookup that finds the user ends on "Press PF5 key to save your updates ...", while a
 * {@code PF5}/{@code PF3} update ends on the success, no-change, or error message.
 *
 * <p><strong>Return contract.</strong> {@link #processUserUpdate(UserUpdateScreen,
 * CardDemoCommarea, CardWorkArea.Aid)} returns the name of the next program to transfer to (the
 * {@code EXEC CICS XCTL} target on PF3/PF12 — the admin menu), or {@code null} to redisplay the
 * current screen with the {@link UserUpdateScreen#getErrMsg() message field} populated (lookup
 * result, validation failures, not-found, no-change, the green success confirmation, and unexpected
 * data-access faults).
 *
 * <p><strong>Service-usage rule (AAP &sect;0.6.4).</strong> Not-found, validation, and no-change
 * conditions are on-screen messages plus a redisplay (return {@code null}), never thrown
 * exceptions. Unexpected data-access faults surface the on-screen fallbacks {@link
 * #MSG_UNABLE_LOOKUP_USER} / {@link #MSG_UNABLE_UPDATE_USER}. Only an admin-authorization violation
 * throws ({@link AuthorizationException}).
 *
 * <p><strong>State.</strong> The service is a stateless singleton holding <em>no</em> mutable
 * instance state; all pseudo-conversational state is carried by the supplied {@link
 * CardDemoCommarea} (user, role, navigation context) and {@link UserUpdateScreen} (operator input
 * and the message line), exactly as the COBOL COMMAREA and BMS symbolic map did.
 *
 * <p><strong>Presentation boundary.</strong> The COBOL {@code POPULATE-HEADER-INFO} (title lines,
 * program name, transaction id, current date/time) and the {@code EIBCALEN = 0} (no-COMMAREA)
 * bounce to sign-on are controller/session concerns; the web layer always supplies a valid
 * navigation COMMAREA, so this service requires a non-{@code null} {@link CardDemoCommarea} and
 * does not render header chrome.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserUpdateService {

  // ===== Program identity (COUSR02C WORKING-STORAGE literals) ===================================

  /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'} — this program's name. */
  static final String PGM_NAME = "COUSR02C";

  /** {@code WS-TRANID PIC X(04) VALUE 'CU02'} — this screen's CICS transaction id. */
  static final String TRAN_ID = "CU02";

  /** Admin-menu program {@code 'COADM01C'} — the PF3 / PF12 return target for this admin screen. */
  static final String LIT_ADMIN_PGM = "COADM01C";

  /** Sign-on program {@code 'COSGN00C'} — the {@code RETURN-TO-PREV-SCREEN} default target. */
  static final String LIT_SIGNON_PGM = "COSGN00C";

  // ===== Byte-exact operator messages (reproduced verbatim from COUSR02C) ========================

  /** {@code 'User ID can NOT be empty...'} (PROCESS-ENTER-KEY L148 / UPDATE-USER-INFO L182). */
  static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";

  /** {@code 'First Name can NOT be empty...'} (UPDATE-USER-INFO L188). */
  static final String MSG_FNAME_EMPTY = "First Name can NOT be empty...";

  /** {@code 'Last Name can NOT be empty...'} (UPDATE-USER-INFO L194). */
  static final String MSG_LNAME_EMPTY = "Last Name can NOT be empty...";

  /**
   * {@code 'Password can NOT be empty...'} — the literal COBOL password empty-check message
   * (UPDATE-USER-INFO L200).
   *
   * <p>Retained verbatim for completeness and byte-exact parity (including the sibling Add-User
   * path), but intentionally <em>not</em> fired on the update path: because the BCrypt hash is
   * never sent back to the screen, a blank password on update means "keep the existing password"
   * and is not an error (AAP &sect;0.6.6). See {@link #updateUserInfo(UserUpdateScreen)}.
   */
  static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

  /** {@code 'User Type can NOT be empty...'} (UPDATE-USER-INFO L206). */
  static final String MSG_USRTYPE_EMPTY = "User Type can NOT be empty...";

  /**
   * {@code 'Press PF5 key to save your updates ...'} (READ-USER-SEC-FILE NORMAL, neutral, L336).
   */
  static final String MSG_PRESS_PF5_TO_SAVE = "Press PF5 key to save your updates ...";

  /** {@code 'User ID NOT found...'} (READ / REWRITE {@code DFHRESP(NOTFND)}, L342 / L379). */
  static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

  /** {@code 'Unable to lookup User...'} (READ-USER-SEC-FILE {@code WHEN OTHER}, L349). */
  static final String MSG_UNABLE_LOOKUP_USER = "Unable to lookup User...";

  /** {@code 'Please modify to update ...'} (UPDATE-USER-INFO, nothing changed, L239). */
  static final String MSG_NO_CHANGES = "Please modify to update ...";

  /** {@code 'Unable to Update User...'} (UPDATE-USER-SEC-FILE {@code WHEN OTHER}, L386). */
  static final String MSG_UNABLE_UPDATE_USER = "Unable to Update User...";

  // ===== Collaborators (constructor-injected; never mutated) =====================================

  /** User-security store ({@code USRSEC} VSAM KSDS) — read-for-update and rewrite. */
  private final UserSecurityRepository userSecurityRepository;

  /**
   * BCrypt password encoder (the {@code config.SecurityConfig} bean). Used one-way only: {@link
   * PasswordEncoder#matches(CharSequence, String)} to detect a genuine password change and {@link
   * PasswordEncoder#encode(CharSequence)} to (re-)hash a new password. The clear-text value is
   * never stored.
   */
  private final PasswordEncoder passwordEncoder;

  /**
   * Creates the service with its required collaborators.
   *
   * @param userSecurityRepository repository for the {@code USRSEC} user-security store; must not
   *     be {@code null}
   * @param passwordEncoder the BCrypt password encoder used to compare and (re-)hash credentials;
   *     must not be {@code null}
   */
  public UserUpdateService(
      UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
    this.userSecurityRepository =
        Objects.requireNonNull(userSecurityRepository, "userSecurityRepository must not be null");
    this.passwordEncoder =
        Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
  }

  // ===== Public entry point (COUSR02C MAIN-PARA, L82-138) =======================================

  /**
   * Processes one pseudo-conversational turn of the Update User screen ({@code COUSR02C} {@code
   * MAIN-PARA}).
   *
   * <p>On the first entry the (admin-supplied) screen is painted; when a user id was handed off
   * from the user-list screen the lookup runs immediately so the populated form appears on first
   * paint. Subsequent invocations dispatch on the operator's attention identifier: {@code ENTER}
   * looks up and populates the form; {@code PF3} attempts the update and then always returns to the
   * caller / admin menu; {@code PF4} clears the screen; {@code PF5} validates and rewrites, staying
   * on the screen; {@code PF12} returns to the admin menu without updating; any other key reports
   * an invalid-key message.
   *
   * <p>The method is {@link Transactional} because the read-for-update and the subsequent rewrite
   * must form a single unit of work, providing optimistic-update parity in the absence of a JPA
   * optimistic-lock version column (AAP &sect;0.3.3, &sect;0.6.4).
   *
   * @param screen the Update User screen DTO carrying operator input and the message line; must not
   *     be {@code null}
   * @param commarea the navigation COMMAREA carrying user, role, and from/to program context; must
   *     not be {@code null}
   * @param aid the resolved attention identifier (ENTER, PF3, PF4, PF5, PF12, …); may be {@code
   *     null}, which is treated as an unsupported key
   * @return the next program to transfer to (PF3/PF12 → the admin menu, defaulting to sign-on), or
   *     {@code null} to redisplay this screen with {@link UserUpdateScreen#getErrMsg()} populated
   * @throws AuthorizationException when {@code commarea} does not denote an administrator
   */
  @Transactional
  public String processUserUpdate(
      UserUpdateScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // Admin gate (AAP §0.6.5): CU02 is reachable only from the admin menu; reject standard users.
    if (!commarea.isAdmin()) {
      throw new AuthorizationException();
    }

    // MAIN-PARA L84-88: SET ERR-FLG-OFF / USR-MODIFIED-NO; MOVE SPACES TO WS-MESSAGE / ERRMSGO.
    screen.setErrMsg("");
    // Clear the green success channel on every entry so a stale confirmation never lingers behind a
    // later validation/error redisplay (the two message channels are mutually exclusive; QA F4-1).
    screen.setSuccessMsg("");

    // First entry — NOT CDEMO-PGM-REENTER (L95-105).
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter();
      // CDEMO-CU02-USR-SELECTED handoff from the user-list screen: when a user id was pre-loaded
      // into the screen, drive the lookup immediately so the populated form appears on first paint
      // (L99-104).
      if (!isBlank(screen.getUsrIdIn())) {
        processEnterKey(screen);
      }
      // SEND-USRUPD-SCREEN: paint the (possibly populated) form.
      return null;
    }

    // Re-entry — EVALUATE EIBAID (L108-131).
    if (aid == CardWorkArea.Aid.ENTER) {
      // ENTER: look up and populate the form for editing.
      processEnterKey(screen);
      return null;
    }
    if (aid == CardWorkArea.Aid.PFK03) {
      // PF3 (L111-119): UPDATE-USER-INFO is performed FIRST, then control ALWAYS returns to the
      // caller (CDEMO-FROM-PROGRAM when set, otherwise the admin menu) regardless of the update
      // result.
      updateUserInfo(screen);
      if (isBlank(commarea.getFromProgram())) {
        commarea.setToProgram(LIT_ADMIN_PGM);
      } else {
        commarea.setToProgram(commarea.getFromProgram());
      }
      return returnToPrevScreen(commarea);
    }
    if (aid == CardWorkArea.Aid.PFK04) {
      // PF4 (L120-121): clear the current screen.
      return clearCurrentScreen(screen);
    }
    if (aid == CardWorkArea.Aid.PFK05) {
      // PF5 (L122-123): validate and rewrite, staying on the screen with the result message.
      updateUserInfo(screen);
      return null;
    }
    if (aid == CardWorkArea.Aid.PFK12) {
      // PF12 (L124-126): return to the admin menu WITHOUT attempting an update.
      commarea.setToProgram(LIT_ADMIN_PGM);
      return returnToPrevScreen(commarea);
    }
    // WHEN OTHER (L127-130): unsupported attention identifier.
    screen.setErrMsg(Messages.MSG_INVALID_KEY.trim());
    return null;
  }

  // ===== PROCESS-ENTER-KEY (L143-172) ===========================================================

  /**
   * Looks up the requested user and populates the editable fields for editing ({@code
   * PROCESS-ENTER-KEY}).
   *
   * <p>An empty user id is rejected ({@link #MSG_USERID_EMPTY}). Otherwise the editable fields are
   * blanked and the record is read: a not-found / lookup error leaves the message set by {@link
   * #readUserSecFile}; a successful read populates the first name, last name, and user type and
   * ends on the neutral {@link #MSG_PRESS_PF5_TO_SAVE} confirmation.
   *
   * <p><strong>Password deviation (AAP &sect;0.6.6).</strong> The COBOL copies the clear-text
   * {@code SEC-USR-PWD} into {@code PASSWDI}; the modernized credential is a one-way BCrypt hash
   * that must never be exposed, so the password field is left blank here. On a subsequent update a
   * blank password means "keep the existing password" (see {@link
   * #updateUserInfo(UserUpdateScreen)}).
   *
   * @param screen the screen DTO supplying the lookup key and receiving the populated fields /
   *     message
   */
  private void processEnterKey(UserUpdateScreen screen) {
    // L146-149: an empty user id is rejected before any read.
    if (isBlank(screen.getUsrIdIn())) {
      screen.setErrMsg(MSG_USERID_EMPTY);
      return;
    }

    // L158-161: blank the editable fields before the read.
    screen.setFName("");
    screen.setLName("");
    screen.setPasswd("");
    screen.setUsrType("");

    // L162-163: MOVE USRIDINI TO SEC-USR-ID; PERFORM READ-USER-SEC-FILE.
    Optional<UserSecurity> found = readUserSecFile(screen, screen.getUsrIdIn());
    if (found.isEmpty()) {
      // DFHRESP(NOTFND) / WHEN OTHER — readUserSecFile has already set the screen message.
      return;
    }

    // L166-171 (DFHRESP NORMAL): populate the editable attributes from the stored record.
    UserSecurity user = found.get();
    screen.setFName(rtrim(user.getSecUsrFname()));
    screen.setLName(rtrim(user.getSecUsrLname()));
    screen.setUsrType(rtrim(user.getSecUsrType()));
    // Security deviation: never expose the BCrypt hash — leave the password field blank.
    screen.setPasswd("");
    // Net-visible neutral confirmation after a successful lookup (READ NORMAL, L336-337).
    screen.setErrMsg(MSG_PRESS_PF5_TO_SAVE);
  }

  // ===== UPDATE-USER-INFO (L177-245) ============================================================

  /**
   * Validates the input fields in the EXACT COBOL order, re-reads the record for update, applies
   * per-field modify detection, and rewrites the record only when something changed ({@code
   * UPDATE-USER-INFO}).
   *
   * <p>The empty-field checks run in the order User ID → First Name → Last Name → (Password) → User
   * Type and short-circuit on the first failure, exactly as the COBOL {@code EVALUATE TRUE} +
   * {@code SEND}/{@code RETURN} sequence does.
   *
   * <p><strong>Password rule (AAP &sect;0.6.6).</strong> The literal COBOL fires {@link
   * #MSG_PASSWORD_EMPTY} on a blank password (L198-203), but that is incompatible with hashed,
   * non-displayable credentials: the form is never seeded with the existing hash. On update a blank
   * password therefore means "keep the existing password" and is NOT an error, so the password
   * empty-check is intentionally skipped here. During modify detection the password is re-encoded
   * only when a non-blank value is supplied that does not already match the stored hash.
   *
   * @param screen the screen DTO supplying the submitted field values and receiving the result
   *     message
   */
  private void updateUserInfo(UserUpdateScreen screen) {
    // Empty-field checks in the EXACT COBOL order, short-circuiting on the first failure
    // (L179-213).
    if (isBlank(screen.getUsrIdIn())) {
      screen.setErrMsg(MSG_USERID_EMPTY);
      return;
    }
    if (isBlank(screen.getFName())) {
      screen.setErrMsg(MSG_FNAME_EMPTY);
      return;
    }
    if (isBlank(screen.getLName())) {
      screen.setErrMsg(MSG_LNAME_EMPTY);
      return;
    }
    // Password empty-check intentionally skipped on update — blank means "keep existing" (see the
    // password rule in this method's Javadoc); MSG_PASSWORD_EMPTY is retained only for parity.
    if (isBlank(screen.getUsrType())) {
      screen.setErrMsg(MSG_USRTYPE_EMPTY);
      return;
    }

    // L216-217: re-read the record inside the @Transactional boundary (the read-for-update). The
    // returned entity is managed; the per-field compare below reproduces the legacy change
    // detection, and the transactional re-read-then-save provides optimistic-update parity in the
    // absence of a @Version column (AAP §0.3.3).
    Optional<UserSecurity> found = readUserSecFile(screen, screen.getUsrIdIn());
    if (found.isEmpty()) {
      // DFHRESP(NOTFND) / WHEN OTHER — readUserSecFile has already set the screen message.
      return;
    }
    UserSecurity user = found.get();

    // Per-field modify detection (WS-USR-MODIFIED, L219-234). Every field is checked; the flag is
    // raised if ANY differs. Text fields are compared trailing-space-insensitively to mirror the
    // COBOL fixed-width (PIC X) comparison against the blank-padded char(n) columns.
    boolean modified = false;

    if (!rtrim(screen.getFName()).equals(rtrim(user.getSecUsrFname()))) {
      user.setSecUsrFname(rtrim(screen.getFName()));
      modified = true;
    }
    if (!rtrim(screen.getLName()).equals(rtrim(user.getSecUsrLname()))) {
      user.setSecUsrLname(rtrim(screen.getLName()));
      modified = true;
    }
    // Password (L227-230): re-encode only when a NON-BLANK password was supplied AND it does not
    // already match the stored hash. Blank (keep existing) or matching (unchanged) leaves it as-is.
    String submittedPassword = screen.getPasswd();
    if (!isBlank(submittedPassword)
        && !passwordEncoder.matches(submittedPassword, user.getSecUsrPwd())) {
      user.setSecUsrPwd(passwordEncoder.encode(submittedPassword));
      modified = true;
    }
    if (!rtrim(screen.getUsrType()).equals(rtrim(user.getSecUsrType()))) {
      user.setSecUsrType(rtrim(screen.getUsrType()));
      modified = true;
    }

    // L236-243: rewrite only when something changed; otherwise report "nothing to update".
    if (modified) {
      updateUserSecFile(screen, user);
    } else {
      screen.setErrMsg(MSG_NO_CHANGES);
    }
  }

  // ===== READ-USER-SEC-FILE (L320-353) ==========================================================

  /**
   * Reads the {@code USRSEC} record by id, translating the COBOL {@code EXEC CICS READ} {@code
   * RESP} handling into screen messages (FILE STATUS parity, AAP &sect;0.6.4): {@code
   * DFHRESP(NORMAL)} (FILE STATUS {@code '00'}) → the record is returned; {@code DFHRESP(NOTFND)}
   * (FILE STATUS {@code '23'}) → {@link Optional#empty()} plus {@link #MSG_USER_NOT_FOUND}; any
   * other data-access fault → {@link Optional#empty()} plus {@link #MSG_UNABLE_LOOKUP_USER}.
   *
   * <p>The {@code NORMAL} "Press PF5 ..." message that the legacy paragraph sets is deliberately
   * <em>not</em> set here; it is owned by the calling lookup path (and is overwritten by the update
   * path), which reproduces the legacy two-SEND last-wins net-visible behavior exactly.
   *
   * @param screen the screen DTO whose message line is set on the not-found / error branches
   * @param userId the user id key; leading/trailing blanks are ignored (the {@code char(8)} key is
   *     compared blank-insensitively)
   * @return the user record on a successful read, or {@link Optional#empty()} when not found or on
   *     a data-access fault
   */
  private Optional<UserSecurity> readUserSecFile(UserUpdateScreen screen, String userId) {
    try {
      Optional<UserSecurity> result = userSecurityRepository.findById(safeTrim(userId));
      if (result.isEmpty()) {
        screen.setErrMsg(MSG_USER_NOT_FOUND); // DFHRESP(NOTFND)
      }
      return result;
    } catch (DataAccessException ex) {
      screen.setErrMsg(MSG_UNABLE_LOOKUP_USER); // WHEN OTHER (unexpected I/O error)
      return Optional.empty();
    }
  }

  // ===== UPDATE-USER-SEC-FILE (L358-390) ========================================================

  /**
   * Rewrites the already-modified, managed user record ({@code EXEC CICS REWRITE}). On success the
   * green confirmation {@code "User <id> has been updated ..."} is shown; on an unexpected
   * data-access fault the on-screen fallback {@link #MSG_UNABLE_UPDATE_USER} is shown rather than
   * thrown (the legacy {@code WHEN OTHER} branch {@code DISPLAY}ed and re-sent the screen).
   *
   * @param screen the screen DTO whose message line receives the result
   * @param user the managed, mutated user record to persist
   */
  private void updateUserSecFile(UserUpdateScreen screen, UserSecurity user) {
    try {
      userSecurityRepository.save(user);
      // DFHRESP(NORMAL), L370-375: STRING 'User ' SEC-USR-ID ' has been updated ...'.
      // COUSR02C L371 MOVEs DFHGREEN to the ERRMSG colour attribute on this success path, so the
      // confirmation renders GREEN (not the red of a validation/error message). Route it through
      // setSuccessMsg so it renders via .bms-success; errMsg was cleared at MAIN-PARA entry and is
      // not touched here (update keeps the record on screen), so the two channels stay mutually
      // exclusive (QA F4-1).
      screen.setSuccessMsg(userUpdatedMessage(user.getSecUsrId()));
    } catch (DataAccessException ex) {
      // WHEN OTHER, L383-389: surface the failure on-screen without aborting the request.
      screen.setErrMsg(MSG_UNABLE_UPDATE_USER);
    }
  }

  // ===== CLEAR-CURRENT-SCREEN (L395-398) / INITIALIZE-ALL-FIELDS (L403-411) ======================

  /**
   * Blanks every editable field and the message line, then redisplays ({@code CLEAR-CURRENT-SCREEN}
   * → {@code INITIALIZE-ALL-FIELDS}). PF4 handler.
   *
   * @param screen the screen DTO to reset
   * @return {@code null} (redisplay the cleared form)
   */
  private String clearCurrentScreen(UserUpdateScreen screen) {
    initializeAllFields(screen);
    return null;
  }

  /**
   * Resets the user-id, first/last name, password, user-type, and message fields to blanks ({@code
   * INITIALIZE-ALL-FIELDS}).
   *
   * @param screen the screen DTO to reset
   */
  private void initializeAllFields(UserUpdateScreen screen) {
    screen.setUsrIdIn("");
    screen.setFName("");
    screen.setLName("");
    screen.setPasswd("");
    screen.setUsrType("");
    screen.setErrMsg("");
    // Clear the green success channel too (single legacy ERRMSG field; modeled here as two mutually
    // exclusive channels) so a PF4 clear never leaves a stale confirmation behind (QA F4-1).
    screen.setSuccessMsg("");
  }

  // ===== RETURN-TO-PREV-SCREEN (L250-261) =======================================================

  /**
   * Resolves the navigation target and stamps the COMMAREA for the transfer ({@code
   * RETURN-TO-PREV-SCREEN}). A blank target defaults to the sign-on program; the from-tranid and
   * from-program are stamped with this transaction's identity and the program context is reset to
   * "enter" (zeroes), mirroring the legacy {@code EXEC CICS XCTL}.
   *
   * @param commarea the navigation COMMAREA to stamp; its {@code toProgram} (set by the caller) is
   *     defaulted to {@link #LIT_SIGNON_PGM} when blank
   * @return the resolved target program name for the controller-layer transfer
   */
  private String returnToPrevScreen(CardDemoCommarea commarea) {
    if (isBlank(commarea.getToProgram())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
    }
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setPgmEnter(); // MOVE ZEROS TO CDEMO-PGM-CONTEXT
    return commarea.getToProgram();
  }

  // ===== Internal helpers =======================================================================

  /**
   * Builds the success message exactly as the COBOL {@code STRING 'User ' SEC-USR-ID ' has been
   * updated ...'} statement does (L372-375): the literal {@code "User "}, then the user id with
   * trailing blanks removed (the {@code DELIMITED BY SPACE} phrase), then the literal {@code " has
   * been updated ..."}.
   *
   * @param secUsrId the stored user id
   * @return the formatted confirmation message
   */
  private static String userUpdatedMessage(String secUsrId) {
    return "User " + safeTrim(secUsrId) + " has been updated ...";
  }

  /**
   * COBOL {@code SPACES}/{@code LOW-VALUES} test: {@code true} when the value is {@code null} or
   * contains only whitespace.
   *
   * @param value the value to test
   * @return {@code true} when the value is blank
   */
  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Null-safe full trim (leading and trailing whitespace), used for blank detection and to derive
   * the trimmed key / id.
   *
   * @param value the value to trim
   * @return the trimmed value, or {@code ""} when {@code null}
   */
  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * Null-safe right trim (trailing ASCII spaces only). Comparing two values after right-trimming
   * reproduces a COBOL fixed-width {@code PIC X(n)} equality test against a blank-padded {@code
   * char(n)} column: trailing-space padding is ignored while leading and internal characters remain
   * significant.
   *
   * @param value the value to right-trim
   * @return the value with trailing spaces removed, or {@code ""} when {@code null}
   */
  private static String rtrim(String value) {
    if (value == null) {
      return "";
    }
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') {
      end--;
    }
    return value.substring(0, end);
  }
}
