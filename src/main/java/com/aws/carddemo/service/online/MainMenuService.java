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
import com.aws.carddemo.dto.screen.MainMenuScreen;
import com.aws.carddemo.util.MenuOptions;
import com.aws.carddemo.util.Messages;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Online <strong>main-menu</strong> business-logic service for standard (non-admin) users, migrated
 * from the legacy CICS COBOL program {@code COMEN01C} (CICS transaction {@code CM00}; source {@code
 * legacy/app/cbl/COMEN01C.cbl}).
 *
 * <p>This service reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.1,
 * &sect;0.7.1) &mdash; the pseudo-conversational main menu: it paints the ten menu options on first
 * entry, then on re-entry validates the operator's selection, enforces per-option role gating, and
 * dispatches (the {@code EXEC CICS XCTL} equivalent) to the chosen program. It performs <em>no</em>
 * file I/O; routing decisions are driven entirely by the shared, data-driven option table {@link
 * MenuOptions#MAIN_MENU_OPTIONS} (the migrated {@code COMEN02Y} copybook), so this class never
 * embeds its own divergent option list.
 *
 * <p>The {@link com.aws.carddemo.web web} controller layer (sibling {@code MainMenuController})
 * owns the HTTP request/response, the {@link CardDemoCommarea} session state, BMS-equivalent screen
 * rendering ({@code EXEC CICS SEND}/{@code RECEIVE}), and the resolution of the raw attention
 * identifier into a {@link CardWorkArea.Aid}. This service is invoked with those already-resolved
 * inputs and returns the next program to route to (or {@code null} to redisplay the menu).
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7):
 *
 * <ul>
 *   <li>{@code MAIN-PARA} (L75-110) &rarr; {@link #processMainMenu(MainMenuScreen,
 *       CardDemoCommarea, CardWorkArea.Aid)}
 *   <li>{@code PROCESS-ENTER-KEY} (L115-165) &rarr; {@link #processEnterKey(MainMenuScreen,
 *       CardDemoCommarea)}
 *   <li>{@code RETURN-TO-SIGNON-SCREEN} (L170-177) &rarr; inlined in the PF3 branch of {@link
 *       #processMainMenu(MainMenuScreen, CardDemoCommarea, CardWorkArea.Aid)}
 *   <li>{@code SEND-MENU-SCREEN} (L182-194) &rarr; {@link #sendMenuScreen(MainMenuScreen, String)}
 *   <li>{@code RECEIVE-MENU-SCREEN} (L199-207) &rarr; controller-owned (HTTP form binding); not
 *       modeled here
 *   <li>{@code POPULATE-HEADER-INFO} (L212-231) &rarr; {@link #populateHeader(MainMenuScreen)}
 *   <li>{@code BUILD-MENU-OPTIONS} (L236-277) &rarr; {@link #buildMenuOptions(MainMenuScreen)}
 * </ul>
 *
 * <p><strong>Parity notes.</strong> The error/informational messages are reproduced from the COBOL
 * literals byte-for-byte ({@link #MSG_INVALID_OPTION}, {@link #MSG_ADMIN_ONLY}, and the {@code
 * "coming soon"} composition built by {@link #composeComingSoon(String)}). The per-option admin
 * gate is a screen redisplay with a message &mdash; <em>not</em> a thrown exception &mdash; because
 * {@code COMEN01C} does not abend there. This class uses {@link java.math.BigDecimal}-free logic
 * only (no decimal arithmetic occurs in the menu).
 */
@Service
public class MainMenuService {

  /** CICS transaction id of this program ({@code WS-TRANID}, {@code COMEN01C} L37). */
  static final String TRAN_ID = "CM00";

  /** Program name of this program ({@code WS-PGMNAME}, {@code COMEN01C} L36). */
  static final String PGM_NAME = "COMEN01C";

  /**
   * Sign-on program routed to when PF3 is pressed and the default sign-on target on a fresh entry
   * ({@code COMEN01C} L96-98, L172-173).
   */
  static final String SIGNON_PROGRAM = "COSGN00C";

  /**
   * Message shown when the operator's selection is blank, non-numeric, zero, or greater than the
   * option count. Reproduced byte-for-byte from {@code COMEN01C} L131.
   */
  static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

  /**
   * Message shown when a standard user selects an admin-only option. Reproduced byte-for-byte from
   * the {@code COMEN01C} L140 literal, <em>including</em> its single trailing space, to guarantee
   * byte parity (AAP &sect;0.7.1). The trailing space is invisible on a rendered 3270/HTML screen,
   * so it is preserved verbatim rather than trimmed.
   */
  static final String MSG_ADMIN_ONLY = "No access - Admin Only option... ";

  /**
   * Five-character sentinel prefix marking a placeholder ("coming soon") program entry in the
   * option table; checked against the first five characters of the target program name ({@code
   * COMEN01C} L146).
   */
  static final String DUMMY_PREFIX = "DUMMY";

  /**
   * Number of repeating menu-option slots on the screen, matching the COBOL {@code OCCURS 12} of
   * the option table ({@code COMEN02Y}) and the twelve {@code OPTN001O}..{@code OPTN012O} BMS
   * fields. Only the first {@link MenuOptions#MAIN_MENU_OPT_COUNT} slots carry data; the remainder
   * stay blank (the COBOL {@code LOW-VALUES} state).
   */
  static final int MENU_SLOT_COUNT = 12;

  /**
   * Processes one main-menu interaction, reproducing the control flow of {@code COMEN01C MAIN-PARA}
   * (L75-110).
   *
   * <p>On the first entry into the program (the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch,
   * L87-90) the menu is painted: the re-enter flag is set, the header and option lines are built,
   * and {@code null} is returned so the caller redisplays the menu. On a re-entry the method
   * branches on the attention identifier exactly as the COBOL {@code EVALUATE EIBAID} (L93-103):
   *
   * <ul>
   *   <li>{@link CardWorkArea.Aid#ENTER} &rarr; delegate to {@link #processEnterKey(MainMenuScreen,
   *       CardDemoCommarea)}.
   *   <li>{@link CardWorkArea.Aid#PFK03} &rarr; set the COMMAREA target program to {@link
   *       #SIGNON_PROGRAM} and return it (the {@code RETURN-TO-SIGNON-SCREEN} XCTL, L96-98,
   *       L170-177).
   *   <li>any other key (including {@code null}/unmapped, {@code CLEAR}, {@code PA1}/{@code PA2},
   *       and the remaining PF keys) &rarr; set the invalid-key message and return {@code null} to
   *       redisplay the menu (the {@code WHEN OTHER} branch, L99-102).
   * </ul>
   *
   * <p>The COBOL {@code EIBCALEN = 0} "no COMMAREA" path (L82-84) that returns straight to sign-on
   * is a controller concern: the web layer always supplies a {@code commarea}, so that path is not
   * modeled here.
   *
   * @param screen the main-menu screen contract carrying the operator's raw {@code option} input
   *     and receiving the rendered header, option lines, and error message; must not be {@code
   *     null}
   * @param commarea the pseudo-conversational session/navigation state; must not be {@code null}
   * @param aid the resolved attention identifier (PF/ENTER key), or {@code null} if the raw key did
   *     not map to a known {@link CardWorkArea.Aid}
   * @return the program name to dispatch to (the {@code EXEC CICS XCTL} target, e.g. {@code
   *     "COACTVWC"}), {@link #SIGNON_PROGRAM} on PF3, or {@code null} to redisplay the menu (first
   *     entry, invalid option, admin-only block, "coming soon", or invalid key)
   * @throws NullPointerException if {@code screen} or {@code commarea} is {@code null}
   */
  public String processMainMenu(
      MainMenuScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

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
      // RETURN-TO-SIGNON-SCREEN (L96-98, L170-177): XCTL to COSGN00C.
      commarea.setToProgram(SIGNON_PROGRAM);
      return SIGNON_PROGRAM;
    }
    // WHEN OTHER (L99-102): invalid key. The shared CCDA-MSG-INVALID-KEY field is a heavily
    // space-padded PIC X(50); it is trimmed for display per the migration convention.
    sendMenuScreen(screen, Messages.MSG_INVALID_KEY.trim());
    return null;
  }

  /**
   * Validates the entered option, applies role gating, and dispatches, reproducing {@code
   * PROCESS-ENTER-KEY} (L115-165).
   *
   * <p>The raw operator input is normalized exactly as the COBOL right-justify + {@code INSPECT ...
   * REPLACING ALL ' ' BY '0'} sequence (L117-124, see {@link #normalizeOption(String)}) and echoed
   * back to the screen ({@code MOVE WS-OPTION TO OPTIONO}, L125). Validation then mirrors L127-134:
   * a non-numeric value, a value greater than {@link MenuOptions#MAIN_MENU_OPT_COUNT}, or a value
   * of zero yields the {@link #MSG_INVALID_OPTION} message and a menu redisplay.
   *
   * <p><strong>Control-flow note.</strong> The COBOL paragraph does not branch out after the
   * invalid-option {@code SEND}; it falls through to the role-gate test, which would index the
   * option table with the rejected (zero or out-of-range) subscript &mdash; an out-of-bounds access
   * whose result is undefined. Because the only externally observable effect of the invalid-option
   * path is the {@link #MSG_INVALID_OPTION} message, this method returns immediately on an invalid
   * option, preserving the observable behavior while avoiding the latent subscript defect.
   *
   * <p>For a valid option the role gate (L136-143) blocks a standard user from an admin-only entry
   * (option {@code userType == 'A'}) with {@link #MSG_ADMIN_ONLY}; every {@code COMEN02Y} entry is
   * {@code "U"}, so this gate is inert for the shipped table but is implemented for parity. Finally
   * the dispatch block (L145-165) routes to the selected program: a real target ({@code pgmName}
   * whose first five characters are not {@link #DUMMY_PREFIX}) sets the COMMAREA routing fields and
   * returns the program name (the XCTL that never returns); a {@code DUMMY} placeholder yields the
   * "coming soon" message and a menu redisplay.
   *
   * @param screen the main-menu screen contract (read for the raw selection, mutated on redisplay)
   * @param commarea the session/navigation state, updated with the from/to routing on dispatch
   * @return the program name to dispatch to, or {@code null} to redisplay the menu
   */
  private String processEnterKey(MainMenuScreen screen, CardDemoCommarea commarea) {
    // L117-124: derive the normalized two-character option string (spaces -> '0').
    String optionStr = normalizeOption(screen.getOption());
    // L125: MOVE WS-OPTION TO OPTIONO OF COMEN1AO — echo the parsed value back to the screen.
    screen.setOption(optionStr);

    boolean numeric = isAllDigits(optionStr);
    int option = numeric ? Integer.parseInt(optionStr) : -1;

    // L127-134: IF WS-OPTION IS NOT NUMERIC OR > COUNT OR = ZEROS -> invalid option.
    if (!numeric || option == 0 || option > MenuOptions.MAIN_MENU_OPT_COUNT) {
      sendMenuScreen(screen, MSG_INVALID_OPTION);
      return null;
    }

    // The subscript is now guaranteed to be in 1..MAIN_MENU_OPT_COUNT.
    MenuOptions.MenuOption selected = MenuOptions.MAIN_MENU_OPTIONS.get(option - 1);

    // L136-143: role gate — standard user selecting an admin-only ('A') option.
    if (commarea.isUser() && CardDemoCommarea.USER_TYPE_ADMIN.equals(selected.userType())) {
      sendMenuScreen(screen, MSG_ADMIN_ONLY);
      return null;
    }

    // L145-165: dispatch.
    String pgmName = selected.programName();
    if (!pgmName.startsWith(DUMMY_PREFIX)) {
      // L147-155: set routing context and XCTL to the selected program.
      commarea.setFromTranId(TRAN_ID);
      commarea.setFromProgram(PGM_NAME);
      commarea.setPgmEnter(); // MOVE ZEROS TO CDEMO-PGM-CONTEXT (L151).
      commarea.setToProgram(pgmName);
      return pgmName;
    }

    // L157-164: placeholder option — display the "coming soon" message and redisplay the menu.
    sendMenuScreen(screen, composeComingSoon(selected.name()));
    return null;
  }

  /**
   * Prepares the menu screen for (re)display, reproducing {@code SEND-MENU-SCREEN} (L182-194):
   * populate the header, build the option lines, then move the message into the error line. The
   * actual transmission ({@code EXEC CICS SEND}) is performed by the controller/view layer.
   *
   * @param screen the screen contract to populate
   * @param message the message for the error line ({@code ERRMSGO}); an empty string clears it
   */
  private void sendMenuScreen(MainMenuScreen screen, String message) {
    populateHeader(screen);
    buildMenuOptions(screen);
    screen.setErrMsg(message);
  }

  /**
   * Populates the screen header fields, reproducing {@code POPULATE-HEADER-INFO} (L212-231): the
   * transaction id, program name, the two shared title lines, and the current date/time formatted
   * with the COBOL date/time masks.
   *
   * @param screen the screen contract to populate
   */
  private void populateHeader(MainMenuScreen screen) {
    LocalDateTime now = LocalDateTime.now();
    screen.setTrnName(TRAN_ID);
    screen.setPgmName(PGM_NAME);
    screen.setTitle01(MenuOptions.TITLE_LINE_1);
    screen.setTitle02(MenuOptions.TITLE_LINE_2);
    screen.setCurDate(now.format(DateTimeFormatter.ofPattern(MenuOptions.DATE_MASK_MM_DD_YY)));
    screen.setCurTime(now.format(DateTimeFormatter.ofPattern(MenuOptions.TIME_MASK_HH_MM_SS)));
  }

  /**
   * Builds the rendered menu-option lines, reproducing {@code BUILD-MENU-OPTIONS} (L236-277).
   *
   * <p>For each of the {@link MenuOptions#MAIN_MENU_OPT_COUNT} active options the display text is
   * composed as {@code STRING opt-num '. ' opt-name} (L243-246) &mdash; the two-digit, zero-padded
   * selector, a {@code ". "} separator, and the 35-character option name &mdash; and placed at the
   * corresponding slot. The screen carries {@link #MENU_SLOT_COUNT} slots (the COBOL {@code OCCURS
   * 12}); the trailing unused slots are left blank, matching the {@code LOW-VALUES} state of {@code
   * OPTN011O}/{@code OPTN012O}.
   *
   * @param screen the screen contract whose option list is replaced
   */
  private void buildMenuOptions(MainMenuScreen screen) {
    List<String> options = new ArrayList<>(MENU_SLOT_COUNT);
    for (int slot = 0; slot < MENU_SLOT_COUNT; slot++) {
      options.add("");
    }
    for (int i = 1; i <= MenuOptions.MAIN_MENU_OPT_COUNT; i++) {
      MenuOptions.MenuOption option = MenuOptions.MAIN_MENU_OPTIONS.get(i - 1);
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
   * '0'}. For example {@code "5"}/{@code "5 "} &rarr; {@code "05"}, {@code " 5"} &rarr; {@code
   * "05"}, {@code "10"} &rarr; {@code "10"}, and blank/{@code null} &rarr; {@code "00"}. Inputs
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

  /**
   * Returns the portion of {@code value} preceding its first space, reproducing the COBOL {@code
   * STRING ... DELIMITED BY SPACE} truncation used when composing the "coming soon" message
   * (L160-161).
   *
   * @param value the source value (a space-padded option name), possibly {@code null}
   * @return the characters up to (but not including) the first space, the whole value if it
   *     contains no space, or an empty string when {@code value} is {@code null}
   */
  static String delimitedBySpace(String value) {
    if (value == null) {
      return "";
    }
    int spaceAt = value.indexOf(' ');
    return (spaceAt >= 0) ? value.substring(0, spaceAt) : value;
  }

  /**
   * Composes the "coming soon" message for a placeholder option, reproducing the COBOL {@code
   * STRING} statement at L159-163 byte-for-byte.
   *
   * <p>The COBOL concatenates the literal {@code 'This option '} (delimited by size, so it retains
   * its single trailing space), the option name delimited by space (see {@link
   * #delimitedBySpace(String)}, which stops at the first embedded space), and the literal {@code
   * 'is coming soon ...'} (delimited by size). Note that &mdash; faithful to the source &mdash;
   * there is no separating space between the truncated name and {@code "is"}: for a name such as
   * {@code "Account View"} the result is {@code "This option Accountis coming soon ..."}. Every
   * shipped {@code COMEN02Y} option targets a real program (none begins with {@link
   * #DUMMY_PREFIX}), so this branch is inert for the current table; it is implemented for
   * completeness and parity.
   *
   * @param optionName the selected option's name ({@code CDEMO-MENU-OPT-NAME}, {@code PIC X(35)})
   * @return the byte-faithful "coming soon" message
   */
  static String composeComingSoon(String optionName) {
    return "This option " + delimitedBySpace(optionName) + "is coming soon ...";
  }
}
