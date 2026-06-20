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

import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.MainMenuScreen;
import com.aws.carddemo.util.MenuOptions;
import com.aws.carddemo.util.Messages;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link MainMenuService}, the online main-menu
 * business-logic service migrated from the legacy CICS COBOL program {@code COMEN01C} (CICS
 * transaction {@code CM00}; behavioral spec {@code legacy/app/cbl/COMEN01C.cbl} and menu table
 * {@code legacy/app/cpy/COMEN02Y.cpy}).
 *
 * <p>{@code MainMenuService} has <strong>no collaborators</strong> &mdash; it performs no file I/O
 * and is driven entirely by the static, data-driven option table {@link
 * MenuOptions#MAIN_MENU_OPTIONS}. Control-flow parity (Agent Action Plan &sect;0.6.5, &sect;0.7.1)
 * is therefore asserted through the externally observable effects of {@link
 * MainMenuService#processMainMenu(MainMenuScreen, CardDemoCommarea, CardWorkArea.Aid)}: its return
 * value (the {@code EXEC CICS XCTL} target program, the sign-on program on PF3, or {@code null} to
 * redisplay), the mutations it makes to the {@link CardDemoCommarea} navigation state, and the
 * message / option-line content it writes to the {@link MainMenuScreen}. No Spring context, mocks,
 * or database is required; the service is instantiated directly.
 *
 * <p><strong>Data-driven, drift-proof design.</strong> Per the migration constraint, the admin-only
 * and "coming soon" expectations are <em>derived from</em> {@link MenuOptions#MAIN_MENU_OPTIONS}
 * rather than hard-coded. In the shipped {@code COMEN02Y} table every row is a standard-user
 * ({@code "U"}) row that targets a real program, so the per-option admin gate and the {@code DUMMY}
 * "coming soon" branch are <em>inert for the live table</em> (the service documents them as
 * implemented "for parity"). The parameterized {@link
 * #enter_eachShippedOption_behavesPerTableDefinition(MenuOptions.MenuOption)} sweep adapts to each
 * row's definition, so it exercises the dispatch path for all ten shipped options today and would
 * automatically cover the gate / "coming soon" branches if an {@code "A"} or {@code DUMMY} row were
 * ever added. The byte-exact message literals behind those inert branches are additionally pinned
 * directly ({@link #adminOnlyMessage_isByteExactWithTrailingSpace()}) and through the
 * package-private composition helpers ({@link
 * #composeComingSoon_isByteExactAndIncludesOptionName()}).
 *
 * <p><strong>Byte-exact message parity.</strong> The invalid-option, admin-only (note the COBOL
 * trailing space), invalid-key, and "coming soon" messages are compared against the production
 * constants exactly as declared, so a single-byte drift fails the test.
 */
class MainMenuServiceTest {

  /** Service under test. It is stateless, so a fresh instance per test is sufficient. */
  private MainMenuService service;

  @BeforeEach
  void setUp() {
    service = new MainMenuService();
  }

  // ===== Fixtures / helpers =====================================================================

  /** Builds a main-menu screen carrying the supplied raw operator selection ({@code OPTIONI}). */
  private static MainMenuScreen screenWithOption(String option) {
    MainMenuScreen screen = new MainMenuScreen();
    screen.setOption(option);
    return screen;
  }

  /**
   * A standard-user COMMAREA on first entry (program context still {@code ENTER}, not re-enter).
   */
  private static CardDemoCommarea firstEntryUser() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();
    return commarea;
  }

  /** An administrator COMMAREA on first entry (program context still {@code ENTER}). */
  private static CardDemoCommarea firstEntryAdmin() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypAdmin();
    return commarea;
  }

  /** A standard-user COMMAREA already in the re-enter (post-paint) state. */
  private static CardDemoCommarea reenteredUser() {
    CardDemoCommarea commarea = firstEntryUser();
    commarea.setPgmReenter();
    return commarea;
  }

  /** An administrator COMMAREA already in the re-enter (post-paint) state. */
  private static CardDemoCommarea reenteredAdmin() {
    CardDemoCommarea commarea = firstEntryAdmin();
    commarea.setPgmReenter();
    return commarea;
  }

  /**
   * Argument source for the per-option sweep: the shipped main-menu option table. Deriving the
   * cases from production (rather than literals) keeps the test in lock-step with {@code COMEN02Y}.
   */
  static List<MenuOptions.MenuOption> mainMenuOptions() {
    return MenuOptions.MAIN_MENU_OPTIONS;
  }

  // ===== First entry (paint then redisplay) =====================================================

  @Test
  @DisplayName("First entry (not re-enter): paints all menu options and redisplays (returns null)")
  void firstEntry_paintsMenuAndRedisplays() {
    CardDemoCommarea commarea = firstEntryUser();
    MainMenuScreen screen = screenWithOption(null);

    // COBOL MAIN-PARA "IF NOT CDEMO-PGM-REENTER" (L87-90): paint the screen, then the
    // pseudo-conversational RETURN re-displays it. No program is dispatched and the AID is ignored.
    String next = service.processMainMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // SET CDEMO-PGM-REENTER TO TRUE — the next interaction is processed as a re-entry.
    assertThat(commarea.isPgmReenter()).isTrue();

    // BUILD-MENU-OPTIONS (L236-277) fills OPTN001O..OPTN012O: twelve slots, the first ten
    // populated.
    assertThat(screen.getOptions()).hasSize(MainMenuService.MENU_SLOT_COUNT);
    for (MenuOptions.MenuOption option : MenuOptions.MAIN_MENU_OPTIONS) {
      // STRING opt-num '. ' opt-name -> the two-digit selector, ". ", then the 35-char name.
      String expected = String.format("%02d", option.number()) + ". " + option.name();
      assertThat(screen.getOptions().get(option.number() - 1)).isEqualTo(expected);
    }
    // The trailing OCCURS-12 slots beyond the active count stay blank (the COBOL LOW-VALUES state).
    for (int slot = MenuOptions.MAIN_MENU_OPT_COUNT;
        slot < MainMenuService.MENU_SLOT_COUNT;
        slot++) {
      assertThat(screen.getOptions().get(slot)).isEmpty();
    }

    // POPULATE-HEADER-INFO (L212-231): transaction id, program name, and the two title lines.
    assertThat(screen.getTrnName()).isEqualTo(MainMenuService.TRAN_ID);
    assertThat(screen.getPgmName()).isEqualTo(MainMenuService.PGM_NAME);
    assertThat(screen.getTitle01()).isEqualTo(MenuOptions.TITLE_LINE_1);
    assertThat(screen.getTitle02()).isEqualTo(MenuOptions.TITLE_LINE_2);
    // MOVE SPACES TO WS-MESSAGE / ERRMSGO — a clean paint carries no error message.
    assertThat(screen.getErrMsg()).isEmpty();
  }

  @Test
  @DisplayName("First entry is role-agnostic: an administrator also just gets the painted menu")
  void firstEntry_isRoleAgnostic_forAdministrator() {
    CardDemoCommarea commarea = firstEntryAdmin();

    // The first-entry branch precedes any role logic, so an admin sees the same
    // painted-and-redisplay
    // behavior. The supplied option is ignored because the method returns before processing input.
    String next = service.processMainMenu(screenWithOption("1"), commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
  }

  // ===== Re-entry + ENTER (option processing) ===================================================

  @ParameterizedTest
  @MethodSource("mainMenuOptions")
  @DisplayName("Re-entry + ENTER: each shipped option behaves exactly per its COMEN02Y table row")
  void enter_eachShippedOption_behavesPerTableDefinition(MenuOptions.MenuOption option) {
    CardDemoCommarea commarea = reenteredUser();
    MainMenuScreen screen = screenWithOption(Integer.toString(option.number()));

    String next = service.processMainMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    boolean adminOnly = CardDemoCommarea.USER_TYPE_ADMIN.equals(option.userType());
    boolean comingSoon = option.programName().startsWith(MainMenuService.DUMMY_PREFIX);

    if (adminOnly) {
      // Role gate (PROCESS-ENTER-KEY L136-143): a standard user is blocked, never dispatched.
      assertThat(next).isNull();
      assertThat(screen.getErrMsg()).isEqualTo(MainMenuService.MSG_ADMIN_ONLY);
      assertThat(commarea.getToProgram()).isNull();
    } else if (comingSoon) {
      // Placeholder target (L157-164): "coming soon" message that includes the option name.
      assertThat(next).isNull();
      assertThat(screen.getErrMsg()).contains("is coming soon ...");
      assertThat(screen.getErrMsg()).contains(MainMenuService.delimitedBySpace(option.name()));
      assertThat(commarea.getToProgram()).isNull();
    } else {
      // Real target (L145-155): XCTL — return the program name and set the routing context.
      assertThat(next).isEqualTo(option.programName());
      assertThat(commarea.getToProgram()).isEqualTo(option.programName());
      assertThat(commarea.getFromTranId()).isEqualTo(MainMenuService.TRAN_ID);
      assertThat(commarea.getFromProgram()).isEqualTo(MainMenuService.PGM_NAME);
      assertThat(commarea.isPgmEnter()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Re-entry + ENTER + valid user option: dispatches (XCTL) and resets context to ENTER")
  void enter_validUserOption_dispatchesToTargetProgram() {
    MenuOptions.MenuOption first = MenuOptions.MAIN_MENU_OPTIONS.get(0);
    CardDemoCommarea commarea = reenteredUser();
    MainMenuScreen screen = screenWithOption(Integer.toString(first.number()));

    String next = service.processMainMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    // EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION)) — route to the selected program.
    assertThat(next).isEqualTo(first.programName());
    assertThat(commarea.getToProgram()).isEqualTo(first.programName());
    // MOVE WS-TRANID / WS-PGMNAME TO CDEMO-FROM-* (L147-148).
    assertThat(commarea.getFromTranId()).isEqualTo(MainMenuService.TRAN_ID);
    assertThat(commarea.getFromProgram()).isEqualTo(MainMenuService.PGM_NAME);
    // MOVE ZEROS TO CDEMO-PGM-CONTEXT (L151) — the next program begins in first-entry state.
    assertThat(commarea.isPgmEnter()).isTrue();
    // MOVE WS-OPTION TO OPTIONO (L125) — the normalized two-digit value is echoed back.
    assertThat(screen.getOption()).isEqualTo(String.format("%02d", first.number()));
    // The dispatch path sets no error message.
    assertThat(screen.getErrMsg()).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "0", "00", "11", "99", "AB", "1A"})
  @DisplayName("Re-entry + ENTER + invalid selection: redisplays with the invalid-option message")
  void enter_invalidOption_redisplaysInvalidOption(String rawOption) {
    CardDemoCommarea commarea = reenteredUser();
    MainMenuScreen screen = screenWithOption(rawOption);

    String next = service.processMainMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    // IF WS-OPTION IS NOT NUMERIC OR > COUNT OR = ZEROS (L127-134): redisplay with the message.
    // Spaces-to-zero parity means blank / null / " " all normalize to "00" and fail the zero test.
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(MainMenuService.MSG_INVALID_OPTION);
    // No program is dispatched on an invalid selection.
    assertThat(commarea.getToProgram()).isNull();
  }

  // ===== Re-entry + PF3 (return to sign-on) =====================================================

  @Test
  @DisplayName("Re-entry + PF3: a standard user returns to the sign-on program (COSGN00C)")
  void pfk03_returnsToSignon_forUser() {
    CardDemoCommarea commarea = reenteredUser();
    MainMenuScreen screen = screenWithOption(null);

    String next = service.processMainMenu(screen, commarea, CardWorkArea.Aid.PFK03);

    // RETURN-TO-SIGNON-SCREEN (L96-98, L170-177): MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, then XCTL.
    assertThat(next).isEqualTo(MainMenuService.SIGNON_PROGRAM);
    assertThat(commarea.getToProgram()).isEqualTo(MainMenuService.SIGNON_PROGRAM);
  }

  @Test
  @DisplayName("Re-entry + PF3 is role-agnostic: an administrator also returns to COSGN00C")
  void pfk03_returnsToSignon_forAdministrator() {
    CardDemoCommarea commarea = reenteredAdmin();

    String next = service.processMainMenu(screenWithOption(null), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo(MainMenuService.SIGNON_PROGRAM);
    assertThat(commarea.getToProgram()).isEqualTo(MainMenuService.SIGNON_PROGRAM);
  }

  // ===== Re-entry + any other key (invalid key) =================================================

  @ParameterizedTest
  @EnumSource(
      value = CardWorkArea.Aid.class,
      names = {"ENTER", "PFK03"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Re-entry + any non-ENTER / non-PF3 key: redisplays with the invalid-key message")
  void invalidAid_redisplaysInvalidKey(CardWorkArea.Aid aid) {
    CardDemoCommarea commarea = reenteredUser();
    MainMenuScreen screen = screenWithOption("1");

    String next = service.processMainMenu(screen, commarea, aid);

    // EVALUATE EIBAID WHEN OTHER (L99-102): MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE, then SEND. The
    // service trims the heavily space-padded PIC X(50) field for display, so compare against
    // trim().
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    assertThat(commarea.getToProgram()).isNull();
  }

  @Test
  @DisplayName("Re-entry + unmapped (null) AID: treated as WHEN OTHER — the invalid-key message")
  void nullAid_redisplaysInvalidKey() {
    CardDemoCommarea commarea = reenteredUser();
    MainMenuScreen screen = screenWithOption("1");

    String next = service.processMainMenu(screen, commarea, null);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    assertThat(commarea.getToProgram()).isNull();
  }

  // ===== Argument guards ========================================================================

  @Test
  @DisplayName("Null screen argument fails fast with NullPointerException")
  void processMainMenu_nullScreen_throwsNpe() {
    CardDemoCommarea commarea = reenteredUser();

    assertThatThrownBy(() -> service.processMainMenu(null, commarea, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("screen must not be null");
  }

  @Test
  @DisplayName("Null commarea argument fails fast with NullPointerException")
  void processMainMenu_nullCommarea_throwsNpe() {
    MainMenuScreen screen = screenWithOption("1");

    assertThatThrownBy(() -> service.processMainMenu(screen, null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("commarea must not be null");
  }

  // ===== Shipped-table parity facts (document the inert gate / "coming soon" branches) ==========

  @Test
  @DisplayName("Parity fact: every shipped main-menu option is standard-user ('U') — gate inert")
  void everyShippedMainMenuOption_isStandardUserType() {
    // COMEN02Y ships only 'U' rows, so the per-option admin gate never fires for the live table; it
    // is implemented for parity and would activate only if an 'A' row were added (covered by the
    // per-option sweep, which adapts to the table definition).
    assertThat(MenuOptions.MAIN_MENU_OPTIONS)
        .allSatisfy(
            option -> assertThat(option.userType()).isEqualTo(CardDemoCommarea.USER_TYPE_USER));
  }

  @Test
  @DisplayName("Parity fact: every shipped main-menu option targets a real program (no DUMMY)")
  void everyShippedMainMenuOption_targetsRealProgram() {
    // No COMEN02Y row is a placeholder, so the "coming soon" branch is inert for the live table.
    assertThat(MenuOptions.MAIN_MENU_OPTIONS)
        .allSatisfy(
            option ->
                assertThat(option.programName()).doesNotStartWith(MainMenuService.DUMMY_PREFIX));
  }

  @Test
  @DisplayName("Boundary anchor: the active option count equals the shipped table size")
  void mainMenuOptionCount_matchesShippedTableSize() {
    assertThat(MenuOptions.MAIN_MENU_OPT_COUNT).isEqualTo(MenuOptions.MAIN_MENU_OPTIONS.size());
  }

  // ===== Byte-exact message-literal parity ======================================================

  @Test
  @DisplayName("Message parity: the admin-only literal is byte-exact, including its trailing space")
  void adminOnlyMessage_isByteExactWithTrailingSpace() {
    // COMEN01C L140: 'No access - Admin Only option... ' — the single trailing space is part of the
    // fixed-width field value and must be preserved verbatim (AAP §0.7.1).
    assertThat(MainMenuService.MSG_ADMIN_ONLY).isEqualTo("No access - Admin Only option... ");
    assertThat(MainMenuService.MSG_ADMIN_ONLY).endsWith(" ");
  }

  @Test
  @DisplayName("Message parity: the invalid-option literal is byte-exact")
  void invalidOptionMessage_isByteExact() {
    // COMEN01C L131.
    assertThat(MainMenuService.MSG_INVALID_OPTION)
        .isEqualTo("Please enter a valid option number...");
  }

  // ===== Package-private helper parity (normalize / numeric test / "coming soon" composition) ===

  @Test
  @DisplayName("normalizeOption mirrors the COBOL right-justify + INSPECT REPLACING ' ' BY '0'")
  void normalizeOption_mirrorsCobolRightJustifyAndZeroFill() {
    // Blank / null collapse to "00" (every space is replaced by '0').
    assertThat(MainMenuService.normalizeOption(null)).isEqualTo("00");
    assertThat(MainMenuService.normalizeOption("")).isEqualTo("00");
    assertThat(MainMenuService.normalizeOption(" ")).isEqualTo("00");
    assertThat(MainMenuService.normalizeOption("  ")).isEqualTo("00");
    // A single significant digit is right-justified, then zero-filled.
    assertThat(MainMenuService.normalizeOption("1")).isEqualTo("01");
    assertThat(MainMenuService.normalizeOption("5")).isEqualTo("05");
    assertThat(MainMenuService.normalizeOption(" 5")).isEqualTo("05");
    assertThat(MainMenuService.normalizeOption("5 ")).isEqualTo("05");
    // Two-character inputs are preserved.
    assertThat(MainMenuService.normalizeOption("10")).isEqualTo("10");
    assertThat(MainMenuService.normalizeOption("00")).isEqualTo("00");
    assertThat(MainMenuService.normalizeOption("99")).isEqualTo("99");
    // Non-numeric characters are retained verbatim (the numeric class test rejects them later).
    assertThat(MainMenuService.normalizeOption("AB")).isEqualTo("AB");
    // Inputs longer than the PIC X(2) field are truncated to two characters.
    assertThat(MainMenuService.normalizeOption("123")).isEqualTo("12");
  }

  @Test
  @DisplayName("isAllDigits mirrors the COBOL IS NUMERIC class test on a PIC 9 field")
  void isAllDigits_mirrorsCobolNumericClassTest() {
    assertThat(MainMenuService.isAllDigits("05")).isTrue();
    assertThat(MainMenuService.isAllDigits("00")).isTrue();
    assertThat(MainMenuService.isAllDigits("0")).isTrue();
    assertThat(MainMenuService.isAllDigits("12")).isTrue();
    assertThat(MainMenuService.isAllDigits("")).isFalse();
    assertThat(MainMenuService.isAllDigits(null)).isFalse();
    assertThat(MainMenuService.isAllDigits("AB")).isFalse();
    assertThat(MainMenuService.isAllDigits("1A")).isFalse();
    assertThat(MainMenuService.isAllDigits("1 ")).isFalse();
  }

  @Test
  @DisplayName(
      "delimitedBySpace truncates at the first space (COBOL STRING ... DELIMITED BY SPACE)")
  void delimitedBySpace_truncatesAtFirstSpace() {
    assertThat(MainMenuService.delimitedBySpace("Account View                       "))
        .isEqualTo("Account");
    assertThat(MainMenuService.delimitedBySpace("Bill Payment                       "))
        .isEqualTo("Bill");
    assertThat(MainMenuService.delimitedBySpace("Solo")).isEqualTo("Solo");
    assertThat(MainMenuService.delimitedBySpace("")).isEmpty();
    assertThat(MainMenuService.delimitedBySpace(null)).isEmpty();
  }

  @Test
  @DisplayName("composeComingSoon is byte-exact and includes the (space-delimited) option name")
  void composeComingSoon_isByteExactAndIncludesOptionName() {
    String firstName = MenuOptions.MAIN_MENU_OPTIONS.get(0).name(); // "Account View" (space-padded)
    String message = MainMenuService.composeComingSoon(firstName);

    // COBOL L159-163: 'This option ' + name DELIMITED BY SPACE + 'is coming soon ...'. Faithful to
    // the source, there is NO separating space before "is", so "Account" abuts "is".
    assertThat(message).isEqualTo("This option Accountis coming soon ...");
    assertThat(message).contains("is coming soon ...");
    assertThat(message).contains(MainMenuService.delimitedBySpace(firstName));
    // A name with no embedded space is kept whole.
    assertThat(MainMenuService.composeComingSoon("Solo"))
        .isEqualTo("This option Solois coming soon ...");
    // A null / blank name yields just the two literal fragments.
    assertThat(MainMenuService.composeComingSoon(null)).isEqualTo("This option is coming soon ...");
  }
}
