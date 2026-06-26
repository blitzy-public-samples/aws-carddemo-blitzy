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
import com.aws.carddemo.dto.screen.UserAddScreen;
import com.aws.carddemo.exception.AuthorizationException;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.util.Messages;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Online business-logic service for the <strong>Add User</strong> screen, migrated from the legacy
 * CICS COBOL program {@code COUSR01C} (CICS transaction {@code CU01}; behavioral spec {@code
 * legacy/app/cbl/COUSR01C.cbl}, with the security record layout {@code
 * legacy/app/cpy/CSUSR01Y.cpy}, the navigation COMMAREA {@code legacy/app/cpy/COCOM01Y.cpy}, and
 * the screen mapset {@code legacy/app/bms/COUSR01.bms}). It is invoked by the sibling {@code
 * com.aws.carddemo.web.UserAddController}.
 *
 * <p><strong>Function.</strong> The program validates the five new-user form fields in the exact
 * COBOL order and, when they all pass, adds a new row to the {@code USRSEC} store (now the {@code
 * user_security} table) keyed on the user id. It is an <em>admin-only</em> function (Agent Action
 * Plan &sect;0.6.5): in the legacy application {@code COUSR01C} is reachable only from the admin
 * menu ({@code COADM01C}).
 *
 * <p><strong>Behavioral parity (AAP &sect;0.1.1, &sect;0.7.1).</strong> Each numbered COBOL
 * paragraph is reproduced as a dedicated method (1:1 traceability, AAP &sect;0.6.7) and both the
 * field-validation order and the literal operator messages are preserved byte-for-byte, because
 * both are externally observable and therefore load-bearing for parity:
 *
 * <ul>
 *   <li>{@code MAIN-PARA} (L71-110) &rarr; {@link #processUserAdd(UserAddScreen, CardDemoCommarea,
 *       CardWorkArea.Aid)}
 *   <li>{@code PROCESS-ENTER-KEY} (L115-160) &rarr; {@link #processEnterKey(UserAddScreen)}
 *   <li>{@code WRITE-USER-SEC-FILE} (L238-274) &rarr; {@link #writeUserSecFile(UserAddScreen)}
 *   <li>{@code CLEAR-CURRENT-SCREEN} (L279-282) &rarr; {@link #clearCurrentScreen(UserAddScreen)}
 *   <li>{@code INITIALIZE-ALL-FIELDS} (L287-295) &rarr; {@link #initializeAllFields(UserAddScreen)}
 *   <li>{@code RETURN-TO-PREV-SCREEN} (L165-178) &rarr; {@link
 *       #returnToPrevScreen(CardDemoCommarea)}
 * </ul>
 *
 * <p>The COBOL {@code SEND-USRADD-SCREEN}, {@code RECEIVE-USRADD-SCREEN}, and {@code
 * POPULATE-HEADER-INFO} paragraphs are presentation concerns owned by the web/controller layer
 * (screen rendering, HTTP form binding, header/date-time population) and are deliberately not
 * modeled here. Likewise the COBOL {@code EIBCALEN = 0} (no-COMMAREA) bounce to sign-on is a
 * controller/session concern: the web layer always supplies a valid navigation COMMAREA, so this
 * service requires a non-{@code null} {@link CardDemoCommarea}.
 *
 * <p><strong>Return contract.</strong> {@link #processUserAdd(UserAddScreen, CardDemoCommarea,
 * CardWorkArea.Aid)} returns the name of the next program to transfer to (the {@code EXEC CICS
 * XCTL} target &mdash; {@code COADM01C} on PF3, or the {@code COSGN00C} default), or {@code null}
 * to redisplay the current screen with the {@link UserAddScreen#getErrMsg() message field}
 * populated (first entry, validation failures, duplicate user, add failures, invalid key, and the
 * green success confirmation after a successful add).
 *
 * <p><strong>Admin-only gating (AAP &sect;0.6.5).</strong> The whole screen is gated two ways as
 * defense-in-depth: the class-level {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")}
 * (enforced by Spring method security at the proxy boundary) and an explicit {@link
 * CardDemoCommarea#isAdmin()} guard at method entry that throws {@link AuthorizationException}
 * (mapped to HTTP 403 by {@code GlobalExceptionHandler}). The explicit guard ensures the
 * COBOL-parity denial is exercised directly by plain unit tests without a security proxy. A
 * non-admin reaching this service is a true authorization violation &mdash; the single place this
 * service throws rather than redisplays.
 *
 * <p><strong>Security hardening (AAP &sect;0.6.6, &sect;0.7.2).</strong> The legacy program stores
 * the clear-text password into {@code SEC-USR-PWD PIC X(08)}. This service performs the one
 * <em>deliberate</em> behavioral deviation mandated by the migration: the password is hashed with
 * the injected BCrypt {@link PasswordEncoder} before persistence (the widened {@code varchar(60)}
 * column). The clear-text password is never logged, echoed, persisted, or placed in any message,
 * and no credentials are hardcoded anywhere. The two seed identities ({@code ADMIN001}/{@code
 * USER0001}) are created by the {@code config} bootstrap seeder, not here; this service only adds
 * users entered through the screen.
 *
 * <p><strong>Duplicate-key parity (AAP &sect;0.6.4).</strong> A VSAM {@code WRITE} fails on a
 * duplicate key ({@code DUPKEY}/{@code DUPREC}), but JPA {@code save()} performs an upsert and
 * would silently overwrite an existing row. To preserve the legacy "User ID already exist..."
 * branch this service first calls {@link UserSecurityRepository#existsById(Object)} and, as a
 * race-safe backstop, also maps a {@link DataIntegrityViolationException} from the insert to the
 * same message. Any other data-access failure is shown on-screen as "Unable to Add User..." (the
 * COBOL {@code WHEN OTHER} branch), not thrown &mdash; matching the legacy {@code
 * DISPLAY}-then-{@code SEND} behavior (SERVICE USAGE RULE).
 *
 * <p><strong>State.</strong> The service is a stateless singleton holding no mutable instance
 * state. All pseudo-conversational state is carried by the supplied {@link CardDemoCommarea} (user,
 * role, navigation context) and {@link UserAddScreen} (operator input and the message line),
 * exactly as the COBOL COMMAREA and BMS symbolic map do.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserAddService {

  // ===== Program identity (COUSR01C WORKING-STORAGE literals) ===================================

  /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'} (L36) — this program's name. */
  static final String PGM_NAME = "COUSR01C";

  /** {@code WS-TRANID PIC X(04) VALUE 'CU01'} (L37) — this screen's CICS transaction id. */
  static final String TRAN_ID = "CU01";

  /** Admin-menu program {@code 'COADM01C'} — the PF3 return target ({@code COUSR01C} L94). */
  static final String LIT_ADMIN_PGM = "COADM01C";

  /**
   * Sign-on program {@code 'COSGN00C'} — the {@code RETURN-TO-PREV-SCREEN} default target when no
   * {@code CDEMO-TO-PROGRAM} was set ({@code COUSR01C} L167-168).
   */
  static final String LIT_SIGNON_PGM = "COSGN00C";

  // ===== Byte-exact operator messages (reproduced verbatim from COUSR01C) ========================

  /** {@code 'First Name can NOT be empty...'} (PROCESS-ENTER-KEY, L120). */
  static final String MSG_FNAME_EMPTY = "First Name can NOT be empty...";

  /** {@code 'Last Name can NOT be empty...'} (PROCESS-ENTER-KEY, L126). */
  static final String MSG_LNAME_EMPTY = "Last Name can NOT be empty...";

  /** {@code 'User ID can NOT be empty...'} (PROCESS-ENTER-KEY, L132). */
  static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";

  /** {@code 'Password can NOT be empty...'} (PROCESS-ENTER-KEY, L138). */
  static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

  /** {@code 'User Type can NOT be empty...'} (PROCESS-ENTER-KEY, L144). */
  static final String MSG_USRTYPE_EMPTY = "User Type can NOT be empty...";

  // ===== Fixed-width length-violation messages ===================================================
  //
  // COUSR01C has NO length-validation literals because every SEC-USER-DATA field is fed by a BMS
  // map
  // field whose physical 3270 column width (FNAMEL=20, LNAMEL=20, USRIDINL=8, USRTYPEL=1) made an
  // over-length value impossible to enter. On the web those fields arrive as unbounded request
  // parameters, so the physical bound is restored server-side. The maxima come straight from the
  // CSUSR01Y copybook (SEC-USR-FNAME PIC X(20), SEC-USR-LNAME PIC X(20), SEC-USR-ID PIC X(08),
  // SEC-USR-TYPE PIC X(01)) and from the matching user_security columns (char(20)/char(20)/char(8)/
  // char(1)). The messages follow the legacy "... can NOT ..." line style with the trailing
  // ellipsis
  // so they redisplay on the same ERRMSG line as the empty-field messages. SEC-USR-PWD (PIC X(08))
  // is
  // deliberately not length-capped here: it is BCrypt-hashed into varchar(60) (AAP §0.6.6), so its
  // input length neither overflows the column nor carries a fixed-width parity contract.

  /** First Name exceeds the {@code SEC-USR-FNAME PIC X(20)} fixed width. */
  static final String MSG_FNAME_TOO_LONG = "First Name can NOT exceed 20 characters...";

  /** Last Name exceeds the {@code SEC-USR-LNAME PIC X(20)} fixed width. */
  static final String MSG_LNAME_TOO_LONG = "Last Name can NOT exceed 20 characters...";

  /** User ID exceeds the {@code SEC-USR-ID PIC X(08)} fixed width. */
  static final String MSG_USERID_TOO_LONG = "User ID can NOT exceed 8 characters...";

  /** User Type exceeds the {@code SEC-USR-TYPE PIC X(01)} fixed width. */
  static final String MSG_USRTYPE_TOO_LONG = "User Type can NOT exceed 1 character...";

  /** {@code SEC-USR-FNAME} fixed width (CSUSR01Y / user_security.sec_usr_fname char(20)). */
  static final int MAX_FNAME_LEN = 20;

  /** {@code SEC-USR-LNAME} fixed width (CSUSR01Y / user_security.sec_usr_lname char(20)). */
  static final int MAX_LNAME_LEN = 20;

  /** {@code SEC-USR-ID} fixed width (CSUSR01Y / user_security.sec_usr_id char(8)). */
  static final int MAX_USERID_LEN = 8;

  /** {@code SEC-USR-TYPE} fixed width (CSUSR01Y / user_security.sec_usr_type char(1)). */
  static final int MAX_USRTYPE_LEN = 1;

  /** {@code 'User ID already exist...'} (WRITE-USER-SEC-FILE DUPKEY/DUPREC branch, L263). */
  static final String MSG_USER_ALREADY_EXISTS = "User ID already exist...";

  /** {@code 'Unable to Add User...'} (WRITE-USER-SEC-FILE WHEN OTHER branch, L270). */
  static final String MSG_UNABLE_ADD_USER = "Unable to Add User...";

  // ===== Collaborators (constructor-injected, immutable) =========================================

  /** Repository for the {@code user_security} store (legacy VSAM {@code USRSEC}). */
  private final UserSecurityRepository userSecurityRepository;

  /**
   * BCrypt password encoder (the bean defined in {@code com.aws.carddemo.config.SecurityConfig}),
   * used to hash the new user's password before persistence (AAP &sect;0.6.6).
   */
  private final PasswordEncoder passwordEncoder;

  /**
   * Creates the Add User service with its required collaborators (sole constructor &mdash; no
   * {@code @Autowired} annotation is needed for single-constructor injection).
   *
   * @param userSecurityRepository the {@code user_security} repository; must not be {@code null}
   * @param passwordEncoder the BCrypt password encoder; must not be {@code null}
   * @throws NullPointerException if any argument is {@code null}
   */
  public UserAddService(
      UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
    this.userSecurityRepository =
        Objects.requireNonNull(userSecurityRepository, "userSecurityRepository must not be null");
    this.passwordEncoder =
        Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
  }

  // ===== Public entry point (COUSR01C MAIN-PARA, L71-110) ========================================

  /**
   * Processes one pseudo-conversational turn of the Add User screen ({@code COUSR01C MAIN-PARA}).
   *
   * <p>On the first entry into the program (the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch,
   * L83-87) the re-enter flag is set and {@code null} is returned so the caller paints an empty
   * form. On a re-entry the method branches on the attention identifier exactly as the COBOL {@code
   * EVALUATE EIBAID} (L90-103):
   *
   * <ul>
   *   <li>{@link CardWorkArea.Aid#ENTER} &rarr; validate and add via {@link
   *       #processEnterKey(UserAddScreen)}.
   *   <li>{@link CardWorkArea.Aid#PFK03} &rarr; set the COMMAREA target to {@link #LIT_ADMIN_PGM}
   *       and return it (the {@code RETURN-TO-PREV-SCREEN} XCTL to the admin menu, L93-95).
   *   <li>{@link CardWorkArea.Aid#PFK04} &rarr; clear the screen via {@link
   *       #clearCurrentScreen(UserAddScreen)} (L96-97).
   *   <li>any other key (including {@code null}/unmapped, {@code CLEAR}, {@code PA1}/{@code PA2},
   *       and the remaining PF keys) &rarr; set the invalid-key message and return {@code null} to
   *       redisplay (the {@code WHEN OTHER} branch, L98-102).
   * </ul>
   *
   * @param screen the Add User screen DTO carrying operator input and the message line; must not be
   *     {@code null}
   * @param commarea the navigation COMMAREA carrying user, role, and from/to program context; must
   *     not be {@code null}
   * @param aid the resolved attention identifier (ENTER, PF3, PF4, …); may be {@code null}, which
   *     is treated as an unsupported key
   * @return the next program to transfer to (PF3 &rarr; {@link #LIT_ADMIN_PGM}, or the {@link
   *     #LIT_SIGNON_PGM} default), or {@code null} to redisplay this screen with {@link
   *     UserAddScreen#getErrMsg()} populated
   * @throws AuthorizationException if {@code commarea} does not denote an administrator (the
   *     admin-only gate)
   * @throws NullPointerException if {@code screen} or {@code commarea} is {@code null}
   */
  public String processUserAdd(
      UserAddScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // Admin-only gate (AAP §0.6.5) — defense-in-depth alongside the class-level
    // @PreAuthorize("hasRole('ADMIN')"). COUSR01C (CU01) is reachable only from the admin menu in
    // the
    // legacy application; a non-admin invocation is a true authorization violation, so we reject
    // rather than redisplay. This is the single place this service throws.
    if (!commarea.isAdmin()) {
      throw new AuthorizationException();
    }

    // MAIN-PARA L73-76: SET ERR-FLG-OFF; MOVE SPACES TO WS-MESSAGE, ERRMSGO.
    screen.setErrMsg("");
    // Clear the green success channel on every entry so a stale confirmation never lingers behind a
    // later validation/error redisplay (the two message channels are mutually exclusive; QA F4-1).
    screen.setSuccessMsg("");

    // MAIN-PARA L83-87: first entry into the program (IF NOT CDEMO-PGM-REENTER) — set the re-enter
    // flag and send an empty form. The EIBCALEN = 0 (no-COMMAREA) bounce to COSGN00C (L78-80) is a
    // controller/session concern and is not modeled here (the web layer always supplies a
    // COMMAREA).
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter();
      return null;
    }

    // MAIN-PARA L90-103: re-entry — EVALUATE EIBAID.
    if (aid == CardWorkArea.Aid.ENTER) {
      return processEnterKey(screen);
    }
    if (aid == CardWorkArea.Aid.PFK03) {
      // PF3 (L93-95): MOVE 'COADM01C' TO CDEMO-TO-PROGRAM; PERFORM RETURN-TO-PREV-SCREEN.
      commarea.setToProgram(LIT_ADMIN_PGM);
      return returnToPrevScreen(commarea);
    }
    if (aid == CardWorkArea.Aid.PFK04) {
      // PF4 (L96-97): PERFORM CLEAR-CURRENT-SCREEN.
      return clearCurrentScreen(screen);
    }
    // WHEN OTHER (L98-102): unsupported attention identifier. CCDA-MSG-INVALID-KEY is a heavily
    // space-padded PIC X(50); it is trimmed for display per the migration convention.
    screen.setErrMsg(Messages.MSG_INVALID_KEY.trim());
    return null;
  }

  // ===== PROCESS-ENTER-KEY (L115-160) ===========================================================

  /**
   * Validates the five input fields in the exact COBOL order and, when they all pass, writes the
   * new user ({@code PROCESS-ENTER-KEY}).
   *
   * <p>The COBOL {@code EVALUATE TRUE} (L117-151) tests each field for {@code SPACES OR LOW-VALUES}
   * and, on the first empty field, sets the corresponding message and performs {@code
   * SEND-USRADD-SCREEN} + {@code RETURN} &mdash; terminating immediately. This method mirrors that
   * short-circuit exactly: it returns {@code null} (redisplay) the moment a check fails, so a later
   * check can never overwrite an earlier message. The validation order &mdash; First Name, Last
   * Name, User ID, Password, User Type &mdash; is a parity requirement.
   *
   * @param screen the screen DTO read for the operator's input and mutated with any error message
   * @return always {@code null} (this path never transfers control; it either redisplays a
   *     validation/result message or redisplays the green success confirmation)
   */
  private String processEnterKey(UserAddScreen screen) {
    // EVALUATE TRUE (L117-151): empty-field checks in the EXACT COBOL order, short-circuiting at
    // the
    // first failure. The COBOL "= SPACES OR LOW-VALUES" test is modeled by isBlank (null or
    // spaces).
    if (isBlank(screen.getFName())) {
      screen.setErrMsg(MSG_FNAME_EMPTY);
      return null;
    }
    if (isBlank(screen.getLName())) {
      screen.setErrMsg(MSG_LNAME_EMPTY);
      return null;
    }
    if (isBlank(screen.getUserId())) {
      screen.setErrMsg(MSG_USERID_EMPTY);
      return null;
    }
    if (isBlank(screen.getPasswd())) {
      screen.setErrMsg(MSG_PASSWORD_EMPTY);
      return null;
    }
    if (isBlank(screen.getUsrType())) {
      screen.setErrMsg(MSG_USRTYPE_EMPTY);
      return null;
    }

    // Fixed-width length guards — restore the physical BMS field-width bound the 3270 hardware
    // enforced (see the MSG_*_TOO_LONG constants). These run STRICTLY AFTER every empty check so
    // the
    // byte-exact COBOL empty-field order (First Name, Last Name, User ID, Password, User Type) is
    // preserved verbatim; a length message can therefore never pre-empt an empty message. They run
    // BEFORE writeUserSecFile so an over-length value is rejected with a clear, field-specific
    // message instead of overflowing a fixed-width char column and surfacing as the misleading
    // duplicate-key / "Unable to Add User" branch. The checks follow the same field order as the
    // empty checks; password is excluded (BCrypt-hashed into varchar(60), no fixed-width contract).
    if (exceedsFixedWidth(screen.getFName(), MAX_FNAME_LEN)) {
      screen.setErrMsg(MSG_FNAME_TOO_LONG);
      return null;
    }
    if (exceedsFixedWidth(screen.getLName(), MAX_LNAME_LEN)) {
      screen.setErrMsg(MSG_LNAME_TOO_LONG);
      return null;
    }
    if (exceedsFixedWidth(screen.getUserId(), MAX_USERID_LEN)) {
      screen.setErrMsg(MSG_USERID_TOO_LONG);
      return null;
    }
    if (exceedsFixedWidth(screen.getUsrType(), MAX_USRTYPE_LEN)) {
      screen.setErrMsg(MSG_USRTYPE_TOO_LONG);
      return null;
    }

    // L153-160: IF NOT ERR-FLG-ON — MOVE the input fields into SEC-USER-DATA and write the record.
    return writeUserSecFile(screen);
  }

  // ===== WRITE-USER-SEC-FILE (L238-274) =========================================================

  /**
   * Persists the new user record ({@code WRITE-USER-SEC-FILE}).
   *
   * <p>Mirrors the COBOL {@code EVALUATE WS-RESP-CD} (L250-274) across its three branches: on a
   * successful write ({@code NORMAL}, L251-259) every input field is blanked and the green "User
   * &lt;id&gt; has been added ..." confirmation is shown; a duplicate key ({@code DUPKEY}/{@code
   * DUPREC}, L260-266) shows {@link #MSG_USER_ALREADY_EXISTS}; any other failure ({@code WHEN
   * OTHER}, L267-273) shows {@link #MSG_UNABLE_ADD_USER}. All three are on-screen redisplays
   * (return {@code null}); none throws (SERVICE USAGE RULE, AAP &sect;0.6.4).
   *
   * @param screen the screen DTO supplying the new user's fields and receiving the result message
   * @return always {@code null} (a write never transfers control; the screen is always redisplayed)
   */
  private String writeUserSecFile(UserAddScreen screen) {
    // L154-158: MOVE USERIDI/FNAMEI/LNAMEI/PASSWDI/USRTYPEI TO SEC-USR-*. The user id is stored
    // as-is (a COBOL MOVE into PIC X(08) does not change case); the fixed-width char(8) primary-key
    // column reproduces the blank-padded VSAM key semantics for both the existence check and write.
    String userId = screen.getUserId();

    // DUPREC parity (AAP §0.6.4): a VSAM WRITE fails on a duplicate key (DUPKEY/DUPREC), but JPA
    // save() upserts and would silently overwrite. Pre-check existence to reproduce the COBOL "User
    // ID already exist..." branch (L260-266) without overwriting an existing row.
    if (userSecurityRepository.existsById(userId)) {
      screen.setErrMsg(MSG_USER_ALREADY_EXISTS);
      return null;
    }

    UserSecurity user = new UserSecurity();
    user.setSecUsrId(userId);
    user.setSecUsrFname(screen.getFName());
    user.setSecUsrLname(screen.getLName());
    // SECURITY DEVIATION (AAP §0.6.6, §0.7.2) — the ONLY intentional behavioral difference from
    // COUSR01C: the COBOL stores the clear-text password into SEC-USR-PWD PIC X(08). Here the
    // password is BCrypt-hashed before persistence (into the widened varchar(60) column). The
    // clear-text value is never logged, echoed, or persisted.
    user.setSecUsrPwd(passwordEncoder.encode(screen.getPasswd()));
    user.setSecUsrType(screen.getUsrType());

    try {
      userSecurityRepository.save(user);
    } catch (DataIntegrityViolationException duplicateKey) {
      // DUPKEY / DUPREC (L260-266): a concurrent insert raced past the existsById pre-check; the
      // unique primary-key constraint is the authoritative guard. Same observable message.
      screen.setErrMsg(MSG_USER_ALREADY_EXISTS);
      return null;
    } catch (DataAccessException ex) {
      // WHEN OTHER (L267-273): any other non-normal datastore response is shown on-screen rather
      // than thrown (SERVICE USAGE RULE), matching the legacy DISPLAY-then-SEND behavior.
      screen.setErrMsg(MSG_UNABLE_ADD_USER);
      return null;
    }

    // NORMAL (L251-259): PERFORM INITIALIZE-ALL-FIELDS, then build the green success line.
    // COUSR01C L254 MOVEs DFHGREEN to the ERRMSG colour attribute on this success path, so the
    // confirmation renders GREEN (not the red of a validation/duplicate error). The modernized UI
    // carries the green channel as a separate field; route the confirmation through setSuccessMsg
    // (errMsg was just cleared by initializeAllFields, keeping the two mutually exclusive) so it
    // renders via .bms-success instead of .bms-error (QA F4-1).
    initializeAllFields(screen);
    screen.setSuccessMsg(userAddedMessage(userId));
    return null;
  }

  // ===== Screen / navigation helpers (CLEAR / INITIALIZE / RETURN-TO-PREV)
  // ========================

  /**
   * PF4 handler ({@code CLEAR-CURRENT-SCREEN}, L279-282): blank every field and redisplay.
   *
   * @param screen the screen DTO whose fields and message are cleared
   * @return always {@code null} (clearing only redisplays the screen)
   */
  private String clearCurrentScreen(UserAddScreen screen) {
    initializeAllFields(screen);
    return null;
  }

  /**
   * Blanks every operator input field and the message line ({@code INITIALIZE-ALL-FIELDS},
   * L287-295): {@code MOVE SPACES TO USERIDI, FNAMEI, LNAMEI, PASSWDI, USRTYPEI, WS-MESSAGE}.
   *
   * <p>The COBOL {@code MOVE -1 TO FNAMEL} (cursor positioning) is a presentation concern owned by
   * the controller/view. Cleared input fields are represented as {@code null} (unset) in the DTO;
   * the message line is reset to an empty string.
   *
   * @param screen the screen DTO to reset
   */
  private void initializeAllFields(UserAddScreen screen) {
    screen.setFName(null);
    screen.setLName(null);
    screen.setUserId(null);
    screen.setPasswd(null);
    screen.setUsrType(null);
    screen.setErrMsg("");
    // Clear the green success channel too (single legacy ERRMSG field; modeled here as two mutually
    // exclusive channels). A subsequent setSuccessMsg on the success path repopulates it (QA F4-1).
    screen.setSuccessMsg("");
  }

  /**
   * Prepares the navigation COMMAREA for an {@code EXEC CICS XCTL} back to the previous screen
   * ({@code RETURN-TO-PREV-SCREEN}, L165-178) and returns the target program name.
   *
   * <p>Defaults the target to {@link #LIT_SIGNON_PGM} when none was set (L167-169), records this
   * transaction/program as the caller, and resets the program context to first-entry ({@code MOVE
   * ZEROS TO CDEMO-PGM-CONTEXT}).
   *
   * @param commarea the navigation COMMAREA, mutated with the from/to routing and reset context
   * @return the target program name to transfer to (the COBOL {@code CDEMO-TO-PROGRAM})
   */
  private String returnToPrevScreen(CardDemoCommarea commarea) {
    if (isBlank(commarea.getToProgram())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
    }
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setPgmEnter();
    return commarea.getToProgram();
  }

  // ===== Field-shape helpers =====================================================================

  /**
   * Builds the green success line ({@code WRITE-USER-SEC-FILE} {@code NORMAL} branch, L255-258).
   *
   * <p>The COBOL {@code STRING 'User ' DELIMITED BY SIZE, SEC-USR-ID DELIMITED BY SPACE, ' has been
   * added ...' DELIMITED BY SIZE} concatenates the fixed literals with the user id trimmed of its
   * trailing spaces ({@code DELIMITED BY SPACE}).
   *
   * @param userId the (non-blank) new user's id, exactly as stored into {@code SEC-USR-ID}
   * @return the byte-exact confirmation message {@code "User <id> has been added ..."}
   */
  private static String userAddedMessage(String userId) {
    return "User " + userId.trim() + " has been added ...";
  }

  /**
   * Returns {@code true} when {@code value} is {@code null} or contains only spaces, modeling the
   * COBOL {@code = SPACES OR LOW-VALUES} test against a fixed-width field.
   *
   * @param value the candidate field value, possibly {@code null}
   * @return {@code true} if the value is unset or blank
   */
  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Returns {@code true} when {@code value} carries more meaningful characters than a fixed-width
   * {@code PIC X(max)} field can hold.
   *
   * <p>Only <em>trailing</em> spaces are ignored ({@link String#stripTrailing()}), exactly matching
   * PostgreSQL {@code char(n)} semantics: an over-length string is rejected unless the excess
   * characters are all spaces, in which case the value is silently truncated to fit. This
   * guarantees the guard never rejects a value the column would have accepted, while still catching
   * the genuinely over-length input that would otherwise raise {@code value too long for type
   * character(n)} on write.
   *
   * @param value the candidate field value, possibly {@code null}
   * @param max the fixed-width maximum from the {@code CSUSR01Y} copybook
   * @return {@code true} if the trailing-space-stripped value is longer than {@code max}
   */
  private static boolean exceedsFixedWidth(String value, int max) {
    return value != null && value.stripTrailing().length() > max;
  }
}
