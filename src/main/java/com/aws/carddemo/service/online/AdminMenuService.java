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

import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.AdminMenuScreen;
import com.aws.carddemo.exception.AuthorizationException;
import com.aws.carddemo.util.MenuOptions;
import com.aws.carddemo.util.Messages;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Online <strong>admin-menu</strong> business-logic service for administrator users, migrated from
 * the legacy CICS COBOL program {@code COADM01C} (CICS transaction {@code CA00}; source {@code
 * legacy/app/cbl/COADM01C.cbl}).
 *
 * <p>This service reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.1,
 * &sect;0.7.1) &mdash; the pseudo-conversational admin menu: it paints the four security/user
 * management options on first entry, then on re-entry validates the operator's selection and
 * dispatches (the {@code EXEC CICS XCTL} equivalent) to the chosen admin program. It performs
 * <em>no</em> file I/O; routing decisions are driven entirely by the shared, data-driven option
 * table {@link MenuOptions#ADMIN_MENU_OPTIONS} (the migrated {@code COADM02Y} copybook), so this
 * class never embeds its own divergent option list.
 *
 * <p>The {@link com.aws.carddemo.web web} controller layer (sibling {@code AdminMenuController})
 * owns the HTTP request/response, the {@link CardDemoCommarea} session state, BMS-equivalent screen
 * rendering ({@code EXEC CICS SEND}/{@code RECEIVE}), and the resolution of the raw attention
 * identifier into a {@link CardWorkArea.Aid}. This service is invoked with those already-resolved
 * inputs and returns the next program to route to (or {@code null} to redisplay the menu).
 *
 * <p><strong>Admin-only gating (AAP &sect;0.6.5).</strong> The admin menu is the one screen that is
 * gated <em>as a whole</em>: in the legacy application {@code COADM01C} is reachable only by an
 * administrator because {@code COSGN00C} routes a user with {@code CDEMO-USRTYP-ADMIN} ({@code
 * 'A'}) here. This service enforces that invariant as defense-in-depth at method entry: a non-admin
 * COMMAREA causes an {@link AuthorizationException} (mapped to HTTP 403 by {@code
 * GlobalExceptionHandler}). An explicit {@link CardDemoCommarea#isAdmin()} check is used rather
 * than a Spring Security {@code @PreAuthorize} annotation so the gate is exercised directly by
 * plain unit tests without a security proxy; the controller additionally maps this transaction
 * under {@code /admin/**} with {@code hasRole("ADMIN")} in {@code SecurityConfig}. This is the
 * single place a thrown exception is correct &mdash; a non-admin reaching the admin menu is a true
 * authorization violation, not a normal redisplay.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7):
 *
 * <ul>
 *   <li>{@code MAIN-PARA} (L75-110) &rarr; {@link #processAdminMenu(AdminMenuScreen,
 *       CardDemoCommarea, CardWorkArea.Aid)}
 *   <li>{@code PROCESS-ENTER-KEY} (L115-155) &rarr; {@link #processEnterKey(AdminMenuScreen,
 *       CardDemoCommarea)}
 *   <li>{@code RETURN-TO-SIGNON-SCREEN} (L160-167) &rarr; inlined in the PF3 branch of {@link
 *       #processAdminMenu(AdminMenuScreen, CardDemoCommarea, CardWorkArea.Aid)}
 *   <li>{@code SEND-MENU-SCREEN} (L172-184) &rarr; {@link #sendMenuScreen(AdminMenuScreen, String)}
 *   <li>{@code RECEIVE-MENU-SCREEN} (L189-197) &rarr; controller-owned (HTTP form binding); not
 *       modeled here
 *   <li>{@code POPULATE-HEADER-INFO} (L202-221) &rarr; {@link #populateHeader(AdminMenuScreen)}
 *   <li>{@code BUILD-MENU-OPTIONS} (L226-263) &rarr; {@link #buildMenuOptions(AdminMenuScreen)}
 * </ul>
 *
 * <p><strong>Parity notes &mdash; deliberate differences from the {@code COMEN01C} twin</strong>
 * (sibling {@code MainMenuService}). {@code COADM01C} is structurally a twin of {@code COMEN01C},
 * but three behaviors differ and are preserved exactly here:
 *
 * <ol>
 *   <li><b>Whole-screen admin gate.</b> {@code COMEN01C} has no such gate; {@code COADM01C} is
 *       admin-only and so this service throws {@link AuthorizationException} at entry for a
 *       non-admin (see above).
 *   <li><b>No per-option role check.</b> The admin option table ({@code COADM02Y}) has no
 *       per-option user-type column, so &mdash; unlike {@code COMEN01C} &mdash; there is no
 *       per-option {@code 'A'}/{@code 'U'} gate inside {@link #processEnterKey(AdminMenuScreen,
 *       CardDemoCommarea)}.
 *   <li><b>Name-less "coming soon" message.</b> In {@code COADM01C} the option-name segment of the
 *       {@code STRING} that builds the "coming soon" text is commented out (L150-151), so the
 *       message is the fixed literal {@link #MSG_COMING_SOON} with no embedded option name. The
 *       {@code COMEN01C} twin composes the name into its variant; this service does not.
 * </ol>
 *
 * <p>The error/informational messages are reproduced from the COBOL literals byte-for-byte ({@link
 * #MSG_INVALID_OPTION}, {@link #MSG_COMING_SOON}; the invalid-key text comes from the shared {@link
 * Messages#MSG_INVALID_KEY}). This class uses {@link java.math.BigDecimal}-free logic only (no
 * decimal arithmetic occurs in the menu).
 */
@Service
public class AdminMenuService {

  /** CICS transaction id of this program ({@code WS-TRANID}, {@code COADM01C} L37). */
  static final String TRAN_ID = "CA00";

  /** Program name of this program ({@code WS-PGMNAME}, {@code COADM01C} L36). */
  static final String PGM_NAME = "COADM01C";

  /**
   * Sign-on program routed to when PF3 is pressed and the default sign-on target on a fresh entry
   * ({@code COADM01C} L96-98, L162-163).
   */
  static final String SIGNON_PROGRAM = "COSGN00C";

  /**
   * Message shown when the operator's selection is blank, non-numeric, zero, or greater than the
   * admin option count. Reproduced byte-for-byte from {@code COADM01C} L131.
   */
  static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

  /**
   * Message shown when a selected admin option targets a placeholder ({@code DUMMY}) program.
   *
   * <p>Reproduced byte-for-byte from the {@code COADM01C} {@code STRING} statement at L149-153. The
   * COBOL concatenates the literal {@code 'This option '} (delimited by size, so it keeps its
   * single trailing space) with {@code 'is coming soon ...'} (delimited by size); the option-name
   * segment (L150-151) is <em>commented out</em>, so the rendered text contains no option name.
   * This is a deliberate difference from the {@code COMEN01C} twin, whose variant embeds the name.
   */
  static final String MSG_COMING_SOON = "This option is coming soon ...";

  /**
   * Five-character sentinel prefix marking a placeholder ("coming soon") program entry in the
   * option table; checked against the first five characters of the target program name ({@code
   * COADM01C} L138).
   */
  static final String DUMMY_PREFIX = "DUMMY";

  /**
   * Number of repeating menu-option slots on the screen, matching the twelve {@code
   * OPTN001O}..{@code OPTN012O} BMS fields of mapset {@code COADM01}. Only the first {@link
   * MenuOptions#ADMIN_MENU_OPT_COUNT} slots carry data; the remainder stay blank (the COBOL {@code
   * LOW-VALUES} state).
   */
  static final int MENU_SLOT_COUNT = 12;

  /**
   * Processes one admin-menu interaction, reproducing the control flow of {@code COADM01C
   * MAIN-PARA} (L75-110).
   *
   * <p><strong>Admin-only gate (AAP &sect;0.6.5).</strong> Before any menu logic runs, a non-admin
   * COMMAREA is rejected with an {@link AuthorizationException}. This reproduces the legacy
   * invariant that only an administrator ever reaches {@code COADM01C} (sign-on routes admins here)
   * and provides defense-in-depth alongside the controller's {@code hasRole("ADMIN")} mapping.
   *
   * <p>On the first entry into the program (the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch,
   * L87-90) the menu is painted: the re-enter flag is set, the header and option lines are built,
   * and {@code null} is returned so the caller redisplays the menu. On a re-entry the method
   * branches on the attention identifier exactly as the COBOL {@code EVALUATE EIBAID} (L93-103):
   *
   * <ul>
   *   <li>{@link CardWorkArea.Aid#ENTER} &rarr; delegate to {@link
   *       #processEnterKey(AdminMenuScreen, CardDemoCommarea)}.
   *   <li>{@link CardWorkArea.Aid#PFK03} &rarr; set the COMMAREA target program to {@link
   *       #SIGNON_PROGRAM} and return it (the {@code RETURN-TO-SIGNON-SCREEN} XCTL, L96-98,
   *       L160-167).
   *   <li>any other key (including {@code null}/unmapped, {@code CLEAR}, {@code PA1}/{@code PA2},
   *       and the remaining PF keys) &rarr; set the invalid-key message and return {@code null} to
   *       redisplay the menu (the {@code WHEN OTHER} branch, L99-102).
   * </ul>
   *
   * <p>The COBOL {@code EIBCALEN = 0} "no COMMAREA" path (L82-84) that returns straight to sign-on
   * is a controller concern: the web layer always supplies a {@code commarea}, so that path is not
   * modeled here.
   *
   * @param screen the admin-menu screen contract carrying the operator's raw {@code option} input
   *     and receiving the rendered header, option lines, and error message; must not be {@code
   *     null}
   * @param commarea the pseudo-conversational session/navigation state; must not be {@code null}
   * @param aid the resolved attention identifier (PF/ENTER key), or {@code null} if the raw key did
   *     not map to a known {@link CardWorkArea.Aid}
   * @return the program name to dispatch to (the {@code EXEC CICS XCTL} target, e.g. {@code
   *     "COUSR00C"}), {@link #SIGNON_PROGRAM} on PF3, or {@code null} to redisplay the menu (first
   *     entry, invalid option, "coming soon", or invalid key)
   * @throws AuthorizationException if {@code commarea} does not denote an administrator (the
   *     admin-only gate)
   * @throws NullPointerException if {@code screen} or {@code commarea} is {@code null}
   */
  public String processAdminMenu(
      AdminMenuScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // Admin-only whole-screen gate (defense-in-depth, AAP §0.6.5). COADM01C is reachable only by
    // admins in the legacy (COSGN00C routes admins here); a non-admin reaching the admin menu is a
    // true authorization violation, so we reject rather than redisplay. This is the deliberate
    // difference (1) from the COMEN01C/MainMenuService twin, which has no whole-screen gate.
    if (!commarea.isAdmin()) {
      throw new AuthorizationException();
    }

    // MAIN-PARA L77-80: SET ERR-FLG-OFF; MOVE SPACES TO WS-MESSAGE, ERRMSGO.
    screen.setErrMsg("");

    // MAIN-PARA L87-90: first entry paints the menu (IF NOT CDEMO-PGM-REENTER).
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter();
      sendMenuScreen(screen, "");
      return null;
    }

    // MAIN-PARA L93-103: re-entry — EVALUATE EIBAID.
    if (aid == CardWorkArea.Aid.ENTER) {
      return processEnterKey(screen, commarea);
    }
    if (aid == CardWorkArea.Aid.PFK03) {
      // RETURN-TO-SIGNON-SCREEN (L96-98, L160-167): XCTL to COSGN00C.
      commarea.setToProgram(SIGNON_PROGRAM);
      return SIGNON_PROGRAM;
    }
    // WHEN OTHER (L99-102): invalid key. The shared CCDA-MSG-INVALID-KEY field is a heavily
    // space-padded PIC X(50); it is trimmed for display per the migration convention.
    sendMenuScreen(screen, Messages.MSG_INVALID_KEY.trim());
    return null;
  }

  /**
   * Validates the entered option and dispatches, reproducing {@code PROCESS-ENTER-KEY} (L115-155).
   *
   * <p>The raw operator input is normalized exactly as the COBOL right-justify + {@code INSPECT ...
   * REPLACING ALL ' ' BY '0'} sequence (L117-124, see {@link #normalizeOption(String)}) and echoed
   * back to the screen ({@code MOVE WS-OPTION TO OPTIONO}, L125). Validation then mirrors L127-134:
   * a non-numeric value, a value greater than {@link MenuOptions#ADMIN_MENU_OPT_COUNT}, or a value
   * of zero yields the {@link #MSG_INVALID_OPTION} message and a menu redisplay.
   *
   * <p><strong>No per-option role check.</strong> Unlike the {@code COMEN01C} twin, the admin
   * option table ({@code COADM02Y}) has no per-option user-type column, so there is intentionally
   * no per-option {@code 'A'}/{@code 'U'} gate here &mdash; the whole screen is already admin-only
   * (see the gate in {@link #processAdminMenu(AdminMenuScreen, CardDemoCommarea,
   * CardWorkArea.Aid)}).
   *
   * <p><strong>Control-flow note.</strong> The COBOL paragraph does not branch out after the
   * invalid-option {@code SEND}; it guards the dispatch block with {@code IF NOT ERR-FLG-ON}
   * (L137), which would otherwise index the option table with the rejected (zero or out-of-range)
   * subscript &mdash; an out-of-bounds access whose result is undefined. Because the only
   * externally observable effect of the invalid-option path is the {@link #MSG_INVALID_OPTION}
   * message, this method returns immediately on an invalid option, preserving the observable
   * behavior while avoiding the latent subscript defect.
   *
   * <p>For a valid option the dispatch block (L137-155) routes to the selected program: a real
   * target ({@code pgmName} whose first five characters are not {@link #DUMMY_PREFIX}) sets the
   * COMMAREA routing fields and returns the program name (the XCTL that never returns); a {@code
   * DUMMY} placeholder yields the {@link #MSG_COMING_SOON} message and a menu redisplay.
   *
   * @param screen the admin-menu screen contract (read for the raw selection, mutated on redisplay)
   * @param commarea the session/navigation state, updated with the from/to routing on dispatch
   * @return the program name to dispatch to, or {@code null} to redisplay the menu
   */
  private String processEnterKey(AdminMenuScreen screen, CardDemoCommarea commarea) {
    // L117-124: derive the normalized two-character option string (spaces -> '0').
    String optionStr = normalizeOption(screen.getOption());
    // L125: MOVE WS-OPTION TO OPTIONO OF COADM1AO — echo the parsed value back to the screen.
    screen.setOption(optionStr);

    boolean numeric = isAllDigits(optionStr);
    int option = numeric ? Integer.parseInt(optionStr) : -1;

    // L127-134: IF WS-OPTION IS NOT NUMERIC OR > CDEMO-ADMIN-OPT-COUNT OR = ZEROS -> invalid
    // option.
    if (!numeric || option == 0 || option > MenuOptions.ADMIN_MENU_OPT_COUNT) {
      sendMenuScreen(screen, MSG_INVALID_OPTION);
      return null;
    }

    // The subscript is now guaranteed to be in 1..ADMIN_MENU_OPT_COUNT. No per-option role check is
    // applied here — deliberate difference (2) from the COMEN01C/MainMenuService twin.
    MenuOptions.MenuOption selected = MenuOptions.ADMIN_MENU_OPTIONS.get(option - 1);
    return dispatchSelectedOption(selected, screen, commarea);
  }

  /**
   * Dispatches a validated, in-range admin-menu option — the COADM01C dispatch tail (L137-155) of
   * {@code PROCESS-ENTER-KEY}. A <em>real</em> target (a {@code programName} not prefixed with the
   * {@link #DUMMY_PREFIX} placeholder marker) stamps the routing context ({@code CDEMO-FROM-*},
   * {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}, {@code CDEMO-TO-PROGRAM}) and returns the program id
   * so the caller can {@code XCTL} to it; a {@code DUMMY} placeholder instead repaints the menu
   * with the name-less {@link #MSG_COMING_SOON} message and returns {@code null}.
   *
   * <p>This is a package-private extraction of the dispatch tail of {@link #processEnterKey} with
   * no change to the public flow ({@code processEnterKey} simply delegates to it after
   * range-checking the subscript, so the {@code PROCESS-ENTER-KEY} traceability mapping is
   * unchanged). It exists as a test seam: the shipped {@code COADM02Y} table ({@link
   * MenuOptions#ADMIN_MENU_OPTIONS}) contains no {@code DUMMY} row (all four options target real
   * {@code COUSR00C}..{@code COUSR03C} programs), so the placeholder / "coming soon" branch is
   * otherwise unreachable through the public API without modifying production data. Extracting it
   * lets {@code AdminMenuServiceTest} exercise that branch directly with a synthetic placeholder.
   *
   * @param selected the validated menu option whose subscript the caller has already range-checked
   * @param screen the screen contract repainted on the placeholder ("coming soon") path
   * @param commarea the navigation state updated with the routing context on the dispatch path
   * @return the target program name to {@code XCTL} to, or {@code null} when {@code selected} is a
   *     {@code DUMMY} placeholder (the menu is redisplayed with the "coming soon" message)
   */
  String dispatchSelectedOption(
      MenuOptions.MenuOption selected, AdminMenuScreen screen, CardDemoCommarea commarea) {
    // L137-155: dispatch.
    String pgmName = selected.programName();
    if (!pgmName.startsWith(DUMMY_PREFIX)) {
      // L138-145: set routing context and XCTL to the selected program.
      commarea.setFromTranId(TRAN_ID);
      commarea.setFromProgram(PGM_NAME);
      commarea.setPgmEnter(); // MOVE ZEROS TO CDEMO-PGM-CONTEXT (L141).
      commarea.setToProgram(pgmName);
      return pgmName;
    }

    // L147-154: placeholder option — display the (name-less) "coming soon" message and redisplay
    // the menu. Deliberate difference (3) from the twin: the option-name segment is commented out
    // in COADM01C (L150-151), so no name is embedded in MSG_COMING_SOON.
    sendMenuScreen(screen, MSG_COMING_SOON);
    return null;
  }

  /**
   * Prepares the menu screen for (re)display, reproducing {@code SEND-MENU-SCREEN} (L172-184):
   * populate the header, build the option lines, then move the message into the error line. The
   * actual transmission ({@code EXEC CICS SEND}) is performed by the controller/view layer.
   *
   * @param screen the screen contract to populate
   * @param message the message for the error line ({@code ERRMSGO}); an empty string clears it
   */
  private void sendMenuScreen(AdminMenuScreen screen, String message) {
    populateHeader(screen);
    buildMenuOptions(screen);
    screen.setErrMsg(message);
  }

  /**
   * Populates the screen header fields, reproducing {@code POPULATE-HEADER-INFO} (L202-221): the
   * transaction id, program name, the two shared title lines, and the current date/time formatted
   * with the COBOL date/time masks.
   *
   * @param screen the screen contract to populate
   */
  private void populateHeader(AdminMenuScreen screen) {
    LocalDateTime now = LocalDateTime.now();
    screen.setTrnName(TRAN_ID);
    screen.setPgmName(PGM_NAME);
    screen.setTitle01(MenuOptions.TITLE_LINE_1);
    screen.setTitle02(MenuOptions.TITLE_LINE_2);
    screen.setCurDate(now.format(DateTimeFormatter.ofPattern(MenuOptions.DATE_MASK_MM_DD_YY)));
    screen.setCurTime(now.format(DateTimeFormatter.ofPattern(MenuOptions.TIME_MASK_HH_MM_SS)));
  }

  /**
   * Builds the rendered menu-option lines, reproducing {@code BUILD-MENU-OPTIONS} (L226-263).
   *
   * <p>For each of the {@link MenuOptions#ADMIN_MENU_OPT_COUNT} active options the display text is
   * composed as {@code STRING opt-num '. ' opt-name} (L233-236) &mdash; the two-digit, zero-padded
   * selector, a {@code ". "} separator, and the 35-character option name &mdash; and placed at the
   * corresponding slot. The screen carries {@link #MENU_SLOT_COUNT} slots; the trailing unused
   * slots are left blank, matching the {@code LOW-VALUES} state of the higher {@code OPTNnnnO}
   * fields (the COBOL admin table is {@code OCCURS 9} and only the first four occurrences carry
   * data).
   *
   * @param screen the screen contract whose option list is replaced
   */
  private void buildMenuOptions(AdminMenuScreen screen) {
    List<String> options = new ArrayList<>(MENU_SLOT_COUNT);
    for (int slot = 0; slot < MENU_SLOT_COUNT; slot++) {
      options.add("");
    }
    for (int i = 1; i <= MenuOptions.ADMIN_MENU_OPT_COUNT; i++) {
      MenuOptions.MenuOption option = MenuOptions.ADMIN_MENU_OPTIONS.get(i - 1);
      String text = String.format("%02d", option.number()) + ". " + option.name();
      options.set(i - 1, text);
    }
    screen.setOptions(options);
  }

  /**
   * Normalizes the raw two-character option input into a two-digit string, reproducing the COBOL
   * right-justify and {@code INSPECT ... REPLACING ALL ' ' BY '0'} sequence (L117-124).
   *
   * <p>The COBOL {@code PERFORM VARYING} scan keeps both characters when the second position is
   * non-blank, otherwise keeps only the first character right-justified into the two-position
   * {@code WS-OPTION-X} ({@code JUST RIGHT}); every remaining space is then replaced by {@code
   * '0'}. For example {@code "4"}/{@code "4 "} &rarr; {@code "04"}, {@code " 4"} &rarr; {@code
   * "04"}, {@code "10"} &rarr; {@code "10"}, and blank/{@code null} &rarr; {@code "00"}. Inputs
   * longer than two characters are truncated to the {@code PIC X(2)} field width.
   *
   * @param optionIn the raw operator input ({@code OPTIONI}, {@code PIC X(2)}), possibly {@code
   *     null}
   * @return the normalized two-character string (spaces replaced by {@code '0'})
   */
  static String normalizeOption(String optionIn) {
    // OPTIONI is PIC X(2): null -> spaces; pad/truncate to exactly two positions.
    char[] field = {' ', ' '};
    if (optionIn != null) {
      for (int i = 0; i < optionIn.length() && i < 2; i++) {
        field[i] = optionIn.charAt(i);
      }
    }

    // PERFORM VARYING WS-IDX ... determines the rightmost significant position (2) or 1.
    int wsIdx = (field[1] != ' ') ? 2 : 1;

    // MOVE OPTIONI(1:WS-IDX) TO WS-OPTION-X (PIC X(2) JUST RIGHT).
    char[] optionX = {' ', ' '};
    if (wsIdx == 2) {
      optionX[0] = field[0];
      optionX[1] = field[1];
    } else {
      optionX[1] = field[0];
    }

    // INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'.
    for (int i = 0; i < optionX.length; i++) {
      if (optionX[i] == ' ') {
        optionX[i] = '0';
      }
    }
    return new String(optionX);
  }

  /**
   * Returns whether the supplied value is a non-empty run of ASCII digits, mirroring the COBOL
   * {@code IS NUMERIC} class test on a {@code PIC 9} field (L127).
   *
   * @param value the candidate string
   * @return {@code true} if {@code value} is non-empty and every character is {@code '0'}..{@code
   *     '9'}
   */
  static boolean isAllDigits(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }
}
