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
import com.aws.carddemo.dto.screen.SignonScreen;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.util.MenuOptions;
import com.aws.carddemo.util.Messages;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Online <strong>sign-on</strong> business-logic service, migrated with 100% behavioral parity from
 * the legacy CICS COBOL program {@code COSGN00C} (CICS transaction {@code CC00}; source {@code
 * legacy/app/cbl/COSGN00C.cbl}, 261 lines).
 *
 * <p>This service reproduces &mdash; per Agent Action Plan &sect;0.1.1 / &sect;0.7.1, zero
 * functional regression &mdash; the pseudo-conversational sign-on screen: it validates the
 * operator-supplied credentials against the {@code user_security} store, captures the resolved user
 * id and role into the {@link CardDemoCommarea}, and routes (the {@code EXEC CICS XCTL} equivalent)
 * to the admin menu ({@code COADM01C}) or the main menu ({@code COMEN01C}). Routing by {@code
 * SEC-USR-TYPE} ({@code 'A'} &rarr; admin, otherwise &rarr; standard user) is the single most
 * important behavioral output of the program and is reproduced exactly.
 *
 * <p>The {@link com.aws.carddemo.web web} controller layer (sibling {@code SignonController}) owns
 * the HTTP request/response, the {@link CardDemoCommarea} session state, BMS-equivalent screen
 * rendering ({@code EXEC CICS SEND}/{@code RECEIVE}), and the resolution of the raw 3270 attention
 * identifier into a {@link CardWorkArea.Aid}. This service is invoked with those already-resolved
 * inputs; it returns the next program to route to (the {@code EXEC CICS XCTL} target) on a
 * successful authentication, or {@code null} to redisplay the sign-on screen (a validation/auth
 * failure, an unexpected read failure, the {@code PF3} exit, or an invalid key), in which case the
 * supplied {@link SignonScreen} carries the populated {@code errMsg}.
 *
 * <p><strong>Security hardening (AAP &sect;0.6.6, &sect;0.7.2).</strong> The legacy clear-text
 * comparison {@code SEC-USR-PWD = WS-USER-PWD} is replaced by a one-way BCrypt verification through
 * the injected {@link PasswordEncoder} (the {@link com.aws.carddemo.config.SecurityConfig} bean);
 * this service never stores or compares a clear-text password and contains no hardcoded
 * credentials. The legacy {@code FUNCTION UPPER-CASE} of <em>both</em> the entered user id and
 * password is preserved, so the upper-cased raw password is matched against the stored hash; the
 * bootstrap credential seeder hashes the seed passwords with this same encoder, keeping the
 * comparison consistent.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7, feeds
 * {@code docs/traceability-matrix.md}):
 *
 * <ul>
 *   <li>{@code MAIN-PARA} (L73-102) &rarr; {@link #processSignon(SignonScreen, CardDemoCommarea,
 *       CardWorkArea.Aid)}
 *   <li>{@code MAIN-PARA} {@code EIBCALEN = 0} first-paint branch (L80-83) &rarr; {@link
 *       #prepareInitialScreen(SignonScreen)}
 *   <li>{@code PROCESS-ENTER-KEY} (L108-140) &rarr; {@link #processEnterKey(SignonScreen,
 *       CardDemoCommarea)}
 *   <li>{@code READ-USER-SEC-FILE} (L209-257) &rarr; {@link #readUserSecFile(String, String,
 *       SignonScreen, CardDemoCommarea)}
 *   <li>{@code SEND-SIGNON-SCREEN} (L145-159) &rarr; {@link #sendSignonScreen(SignonScreen,
 *       String)}
 *   <li>{@code SEND-PLAIN-TEXT} (L162-172) &rarr; {@link #sendPlainText(SignonScreen, String)}
 *   <li>{@code POPULATE-HEADER-INFO} (L177-204) &rarr; {@link #populateHeader(SignonScreen)}
 *   <li>{@code EXEC CICS RECEIVE} / {@code SEND} transmission &rarr; controller-owned (HTTP/BMS
 *       rendering and AID capture); not modeled here
 * </ul>
 *
 * <p>This is a stateless Spring singleton: it keeps <strong>no</strong> mutable instance fields
 * beyond its injected collaborators, so it is safe to share across request threads. All
 * per-interaction state lives in the {@link SignonScreen} and {@link CardDemoCommarea} arguments
 * (mirroring the COBOL working storage and {@code DFHCOMMAREA}).
 */
@Service
public class SignonService {

  // ---------------------------------------------------------------------------------------------
  // Program identity constants (mirror the COSGN00C WORKING-STORAGE literals).
  // ---------------------------------------------------------------------------------------------

  /** CICS transaction id of this program ({@code WS-TRANID}, {@code COSGN00C} L37). */
  static final String TRAN_ID = "CC00";

  /** Program name of this program ({@code WS-PGMNAME}, {@code COSGN00C} L36). */
  static final String PGM_NAME = "COSGN00C";

  /** Admin-menu {@code XCTL} target for an administrator sign-on ({@code COSGN00C} L231-233). */
  static final String ADMIN_MENU_PROGRAM = "COADM01C";

  /** Main-menu {@code XCTL} target for a standard-user sign-on ({@code COSGN00C} L236-238). */
  static final String MAIN_MENU_PROGRAM = "COMEN01C";

  // ---------------------------------------------------------------------------------------------
  // User-facing message literals (byte-exact from COSGN00C inline MOVEs). These are placed on the
  // screen errMsg field for a redisplay; they are never thrown as exceptions.
  // ---------------------------------------------------------------------------------------------

  /** Shown when the user id field is blank ({@code COSGN00C} L120). */
  static final String MSG_ENTER_USERID = "Please enter User ID ...";

  /** Shown when the password field is blank ({@code COSGN00C} L125). */
  static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

  /**
   * Shown when the supplied password does not match the stored credential ({@code COSGN00C} L242).
   */
  static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

  /** Shown when no security record exists for the supplied user id ({@code COSGN00C} L249). */
  static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

  /** Shown on an unexpected security-store read failure ({@code COSGN00C} L254). */
  static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

  // ---------------------------------------------------------------------------------------------
  // Injected collaborators (constructor injection; no field injection).
  // ---------------------------------------------------------------------------------------------

  /** Security-record store; replaces the legacy VSAM {@code USRSEC} KSDS read. */
  private final UserSecurityRepository userSecurityRepository;

  /**
   * One-way BCrypt password verifier; replaces the legacy clear-text comparison (AAP &sect;0.6.6).
   */
  private final PasswordEncoder passwordEncoder;

  /**
   * Creates the sign-on service. Spring auto-wires the single constructor, so no {@code @Autowired}
   * annotation is required.
   *
   * @param userSecurityRepository the {@code user_security} repository backing the sign-on read
   * @param passwordEncoder the BCrypt encoder used to verify the supplied password against the
   *     stored hash (the {@link com.aws.carddemo.config.SecurityConfig} bean)
   */
  public SignonService(
      UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
    this.userSecurityRepository = userSecurityRepository;
    this.passwordEncoder = passwordEncoder;
  }

  /**
   * Processes one sign-on interaction, reproducing the attention-identifier dispatch of {@code
   * COSGN00C MAIN-PARA} (L73-102).
   *
   * <p>The method first clears any prior error message ({@code MOVE SPACES TO WS-MESSAGE, ERRMSGO},
   * L76-78), then branches on the resolved attention identifier exactly as the COBOL {@code
   * EVALUATE EIBAID} (L85-99):
   *
   * <ul>
   *   <li>{@link CardWorkArea.Aid#ENTER} (L86-87) &rarr; delegate to {@link
   *       #processEnterKey(SignonScreen, CardDemoCommarea)}, returning its result.
   *   <li>{@link CardWorkArea.Aid#PFK03} (L88-90) &rarr; render the thank-you message and return
   *       {@code null}; the caller treats this as the logout/exit (the COBOL {@code
   *       SEND-PLAIN-TEXT} followed by a {@code RETURN} with no {@code TRANSID}).
   *   <li>any other key, including {@code null}/unmapped, {@code CLEAR}, {@code PA1}/{@code PA2},
   *       and the remaining PF keys (L91-94) &rarr; render the shared invalid-key message and
   *       return {@code null} to redisplay the sign-on screen.
   * </ul>
   *
   * <p>The {@code EIBCALEN = 0} first-entry branch (L80-83) that paints a blank screen is the
   * controller's initial GET concern; see {@link #prepareInitialScreen(SignonScreen)}.
   *
   * @param screen the sign-on screen contract carrying the operator's {@code userId}/{@code passwd}
   *     input and receiving the rendered header and error message; must not be {@code null}
   * @param commarea the pseudo-conversational session/navigation state, updated with the resolved
   *     user id and role on a successful authentication; must not be {@code null}
   * @param aid the resolved attention identifier (PF/ENTER key), or {@code null} if the raw key did
   *     not map to a known {@link CardWorkArea.Aid}
   * @return the program name to route to (the {@code EXEC CICS XCTL} target — {@link
   *     #ADMIN_MENU_PROGRAM} or {@link #MAIN_MENU_PROGRAM}) on a successful authentication, or
   *     {@code null} to redisplay the sign-on screen (validation/auth failure, unexpected read
   *     failure, PF3 exit, or invalid key)
   * @throws NullPointerException if {@code screen} or {@code commarea} is {@code null}
   */
  public String processSignon(
      SignonScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // MAIN-PARA L75-78: SET ERR-FLG-OFF TO TRUE; MOVE SPACES TO WS-MESSAGE, ERRMSGO. A fresh
    // interaction starts with no error message; redisplay paths below set it explicitly.
    screen.setErrMsg("");

    // MAIN-PARA L85-99: EVALUATE EIBAID. The EIBCALEN = 0 first-paint branch (L80-83) is owned by
    // the controller's initial GET (see prepareInitialScreen); this service handles keyed actions.
    if (aid == CardWorkArea.Aid.ENTER) {
      // WHEN DFHENTER (L86-87): perform the credential validation and routing.
      return processEnterKey(screen, commarea);
    }
    if (aid == CardWorkArea.Aid.PFK03) {
      // WHEN DFHPF3 (L88-90): thank-you plain-text send, then RETURN with no TRANSID (exit). The
      // shared CCDA-MSG-THANK-YOU field is a heavily space-padded PIC X(50); it is trimmed for
      // display since trailing blanks are not semantically significant on the rendered screen.
      sendPlainText(screen, Messages.MSG_THANK_YOU.trim());
      return null;
    }
    // WHEN OTHER (L91-94): any other AID is an invalid key — redisplay the sign-on screen with the
    // shared CCDA-MSG-INVALID-KEY message (likewise trimmed of its PIC X(50) padding).
    sendSignonScreen(screen, Messages.MSG_INVALID_KEY.trim());
    return null;
  }

  /**
   * Prepares a blank sign-on screen for the initial paint, reproducing the {@code MAIN-PARA} {@code
   * EIBCALEN = 0} branch (L80-83): {@code MOVE LOW-VALUES TO COSGN0AO}, position the cursor on the
   * user id field, and {@code PERFORM SEND-SIGNON-SCREEN}.
   *
   * <p>This is a convenience entry point for the controller's initial GET (when there is no prior
   * COMMAREA). It clears the input fields and message and populates the header; the physical render
   * and cursor placement ({@code MOVE -1 TO USERIDL}) are controller/view concerns.
   *
   * @param screen the sign-on screen contract to initialize; must not be {@code null}
   * @throws NullPointerException if {@code screen} is {@code null}
   */
  public void prepareInitialScreen(SignonScreen screen) {
    Objects.requireNonNull(screen, "screen must not be null");
    // MOVE LOW-VALUES TO COSGN0AO (L81): clear the input fields for a fresh paint.
    screen.setUserId("");
    screen.setPasswd("");
    // PERFORM SEND-SIGNON-SCREEN (L83): populate the header and clear the error line.
    sendSignonScreen(screen, "");
  }

  /**
   * Validates the entered credentials and, when both are present, performs the security-store read,
   * reproducing {@code PROCESS-ENTER-KEY} (L108-140).
   *
   * <p>Validation mirrors the COBOL {@code EVALUATE TRUE} (L116-129): a blank user id yields {@link
   * #MSG_ENTER_USERID} (L118-122) and a blank password yields {@link #MSG_ENTER_PASSWORD}
   * (L123-127), each redisplaying the screen. A blank field is the Java analogue of the COBOL
   * {@code = SPACES OR LOW-VALUES} test (see {@link #isBlank(String)}). When both fields are
   * present, the user id and password are upper-cased with {@link Locale#ROOT} ({@code MOVE
   * FUNCTION UPPER-CASE(...)}, L132-136), the upper-cased id is captured into the COMMAREA ({@code
   * MOVE ... TO CDEMO-USER-ID}, L134), and the read is performed ({@code IF NOT ERR-FLG-ON PERFORM
   * READ-USER-SEC-FILE}, L138-139).
   *
   * @param screen the sign-on screen contract (read for the credentials, mutated on redisplay)
   * @param commarea the session/navigation state; receives the upper-cased user id
   * @return the routing target program name on a successful authentication, or {@code null} to
   *     redisplay the sign-on screen
   */
  private String processEnterKey(SignonScreen screen, CardDemoCommarea commarea) {
    String userId = screen.getUserId();
    String password = screen.getPasswd();

    // PROCESS-ENTER-KEY L116-122: WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES.
    if (isBlank(userId)) {
      sendSignonScreen(screen, MSG_ENTER_USERID);
      return null;
    }
    // L123-127: WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES.
    if (isBlank(password)) {
      sendSignonScreen(screen, MSG_ENTER_PASSWORD);
      return null;
    }

    // L132-136: MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID, CDEMO-USER-ID; and
    // MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD. Locale.ROOT yields a locale-independent
    // upper-casing equivalent to the COBOL intrinsic (no locale-specific casing surprises).
    String upperUserId = userId.toUpperCase(Locale.ROOT);
    String upperPassword = password.toUpperCase(Locale.ROOT);
    // L134: MOVE ... TO CDEMO-USER-ID — capture the upper-cased id into the COMMAREA.
    commarea.setUserId(upperUserId);

    // L138-139: IF NOT ERR-FLG-ON PERFORM READ-USER-SEC-FILE. Reached only when both fields are
    // present (the blank-field branches returned above), so the read always runs here.
    return readUserSecFile(upperUserId, upperPassword, screen, commarea);
  }

  /**
   * Reads the security record, verifies the password, and routes by user type, reproducing {@code
   * READ-USER-SEC-FILE} (L209-257).
   *
   * <p>The read ({@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)}, L211-219) becomes a
   * {@link UserSecurityRepository#findById(Object)} keyed by the upper-cased id. The COBOL {@code
   * EVALUATE WS-RESP-CD} (L221-256) is reproduced branch for branch:
   *
   * <ul>
   *   <li>{@code WHEN 0} (record found, L221-246): if {@code passwordEncoder.matches(...)} succeeds
   *       (the BCrypt replacement for {@code SEC-USR-PWD = WS-USER-PWD}, L223), the navigation/role
   *       context is captured into the COMMAREA (L224-228) and the routing target is returned
   *       &mdash; {@link #ADMIN_MENU_PROGRAM} for {@code CDEMO-USRTYP-ADMIN} ({@code 'A'},
   *       L231-233) or {@link #MAIN_MENU_PROGRAM} otherwise (L236-238); on a password mismatch
   *       (L241-245) the screen is redisplayed with {@link #MSG_WRONG_PASSWORD}.
   *   <li>{@code WHEN 13} (not found / FILE STATUS {@code '23'}, L247-251): {@link
   *       Optional#empty()} redisplays the screen with {@link #MSG_USER_NOT_FOUND}. Per the
   *       service-usage rule a not-found at sign-on is a user-facing message, never a thrown {@code
   *       RecordNotFoundException}.
   *   <li>{@code WHEN OTHER} (unexpected failure, L252-256): a {@link DataAccessException} from the
   *       read redisplays the screen with {@link #MSG_UNABLE_TO_VERIFY}. COSGN00C handles {@code
   *       WHEN OTHER} gracefully (it does not abend on the sign-on read), so the failure is
   *       <em>not</em> rethrown as an {@code IoStatusException} here.
   * </ul>
   *
   * @param userId the upper-cased user id used as the security-store key
   * @param password the upper-cased raw password to verify against the stored BCrypt hash
   * @param screen the sign-on screen contract, mutated on every redisplay path
   * @param commarea the session/navigation state, populated with the from/user/type context on a
   *     successful authentication
   * @return the routing target program name on a successful authentication, or {@code null} to
   *     redisplay the sign-on screen
   */
  private String readUserSecFile(
      String userId, String password, SignonScreen screen, CardDemoCommarea commarea) {
    Optional<UserSecurity> found;
    try {
      // READ-USER-SEC-FILE L211-219: EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID). The
      // upper-cased id is the key; the fixed-width char(8) PK comparison preserves the original
      // VSAM KSDS key semantics.
      found = userSecurityRepository.findById(userId);
    } catch (DataAccessException ex) {
      // WHEN OTHER (L252-256): an unexpected data-access failure. COSGN00C redisplays "Unable to
      // verify the User ..." and does NOT abend on the sign-on read, so parity requires a graceful
      // redisplay here rather than rethrowing as an IoStatusException.
      sendSignonScreen(screen, MSG_UNABLE_TO_VERIFY);
      return null;
    }

    if (found.isPresent()) {
      // WHEN 0 (L221-246): the security record exists.
      UserSecurity user = found.get();
      // L223: IF SEC-USR-PWD = WS-USER-PWD. The legacy clear-text compare is replaced by a one-way
      // BCrypt verification (AAP §0.6.6): the already-upper-cased raw password is matched against
      // the stored 60-char hash. The bootstrap seeder hashes seed passwords with this same encoder,
      // so matching the upper-cased input stays consistent with the legacy upper-casing of the
      // entered password. Clear-text is NEVER stored or compared.
      if (passwordEncoder.matches(password, user.getSecUsrPwd())) {
        // L224-228: capture the navigation/role context into the COMMAREA.
        commarea.setFromTranId(TRAN_ID); // MOVE WS-TRANID TO CDEMO-FROM-TRANID.
        commarea.setFromProgram(PGM_NAME); // MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM.
        commarea.setUserId(userId); // MOVE WS-USER-ID TO CDEMO-USER-ID.
        commarea.setUserType(user.getSecUsrType()); // MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE.
        commarea.setPgmEnter(); // MOVE ZEROS TO CDEMO-PGM-CONTEXT (88 CDEMO-PGM-ENTER).

        // L230-238: route by user type. CDEMO-USRTYP-ADMIN ('A') -> COADM01C, else -> COMEN01C.
        // COSGN00C transfers control directly via XCTL (it does not MOVE to CDEMO-TO-PROGRAM), so
        // this service returns the target program name and intentionally leaves CDEMO-TO-PROGRAM
        // untouched, faithfully mirroring the source.
        if (commarea.isAdmin()) {
          return ADMIN_MENU_PROGRAM;
        }
        return MAIN_MENU_PROGRAM;
      }
      // L241-245: SEC-USR-PWD mismatch -> "Wrong Password. Try again ...".
      sendSignonScreen(screen, MSG_WRONG_PASSWORD);
      return null;
    }

    // WHEN 13 / NOTFND (L247-251): no record for the key. findById returns Optional.empty() (FILE
    // STATUS '23'); a not-found at sign-on is a user-facing redisplay, not a thrown exception.
    sendSignonScreen(screen, MSG_USER_NOT_FOUND);
    return null;
  }

  /**
   * Prepares the sign-on screen for (re)display, reproducing {@code SEND-SIGNON-SCREEN} (L145-159):
   * {@code PERFORM POPULATE-HEADER-INFO} then {@code MOVE WS-MESSAGE TO ERRMSGO}. The physical
   * transmission ({@code EXEC CICS SEND MAP}) is performed by the controller/view layer.
   *
   * @param screen the screen contract to populate
   * @param message the message for the error line ({@code ERRMSGO}); an empty string clears it
   */
  private void sendSignonScreen(SignonScreen screen, String message) {
    populateHeader(screen);
    screen.setErrMsg(message);
  }

  /**
   * Renders the plain-text exit message, reproducing {@code SEND-PLAIN-TEXT} (L162-172): {@code
   * EXEC CICS SEND TEXT FROM(WS-MESSAGE)} followed by {@code EXEC CICS RETURN} with no {@code
   * TRANSID}, which ends the pseudo-conversation (logout/exit).
   *
   * <p>The physical plain-text transmission is a controller concern; this method surfaces the
   * message on the screen contract, and the caller treats the {@code null} return of {@link
   * #processSignon(SignonScreen, CardDemoCommarea, CardWorkArea.Aid)} as the exit.
   *
   * @param screen the screen contract to populate
   * @param message the exit message to display
   */
  private void sendPlainText(SignonScreen screen, String message) {
    screen.setErrMsg(message);
  }

  /**
   * Populates the screen header fields, reproducing {@code POPULATE-HEADER-INFO} (L177-204): the
   * transaction id, program name, the two shared title lines, and the current date/time formatted
   * with the COBOL date/time masks ({@code MOVE FUNCTION CURRENT-DATE} supplies "now").
   *
   * <p>The {@code EXEC CICS ASSIGN APPLID}/{@code SYSID} moves (L198-203) have no JPA/Spring
   * equivalent, so {@code applId} and {@code sysId} are intentionally left unset (no invented
   * business data).
   *
   * @param screen the screen contract to populate
   */
  private void populateHeader(SignonScreen screen) {
    LocalDateTime now = LocalDateTime.now();
    screen.setTrnName(TRAN_ID); // MOVE WS-TRANID TO TRNNAMEO.
    screen.setPgmName(PGM_NAME); // MOVE WS-PGMNAME TO PGMNAMEO.
    screen.setTitle01(MenuOptions.TITLE_LINE_1); // MOVE CCDA-TITLE01 TO TITLE01O.
    screen.setTitle02(MenuOptions.TITLE_LINE_2); // MOVE CCDA-TITLE02 TO TITLE02O.
    // MOVE WS-CURDATE-MM-DD-YY TO CURDATEO (L189) and WS-CURTIME-HH-MM-SS TO CURTIMEO (L196).
    screen.setCurDate(now.format(DateTimeFormatter.ofPattern(MenuOptions.DATE_MASK_MM_DD_YY)));
    screen.setCurTime(now.format(DateTimeFormatter.ofPattern(MenuOptions.TIME_MASK_HH_MM_SS)));
  }

  /**
   * Returns whether a screen field is the Java analogue of the COBOL {@code = SPACES OR LOW-VALUES}
   * test on a {@code PIC X(8)} field (L116-122, L123-127).
   *
   * <p>An unset field is {@code null} (the {@code LOW-VALUES} analogue) and an all-blank field is
   * spaces; both count as empty input. {@link String#isBlank()} additionally absorbs any incidental
   * whitespace introduced by HTTP form binding.
   *
   * @param value the candidate field value, possibly {@code null}
   * @return {@code true} if {@code value} is {@code null}, empty, or whitespace-only
   */
  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
