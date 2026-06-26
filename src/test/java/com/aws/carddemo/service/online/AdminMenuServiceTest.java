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
import com.aws.carddemo.dto.screen.AdminMenuScreen;
import com.aws.carddemo.exception.AuthorizationException;
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
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link AdminMenuService}, the online admin-menu
 * business-logic service migrated from the legacy CICS COBOL program {@code COADM01C} (CICS
 * transaction {@code CA00}; behavioral spec {@code legacy/app/cbl/COADM01C.cbl} and admin option
 * table {@code legacy/app/cpy/COADM02Y.cpy}).
 *
 * <p>{@code AdminMenuService} has <strong>no collaborators</strong> &mdash; it performs no file I/O
 * and is driven entirely by the static, data-driven option table {@link
 * MenuOptions#ADMIN_MENU_OPTIONS}. Control-flow parity (Agent Action Plan &sect;0.6.5, &sect;0.7.1)
 * is therefore asserted through the externally observable effects of {@link
 * AdminMenuService#processAdminMenu(AdminMenuScreen, CardDemoCommarea, CardWorkArea.Aid)}: its
 * return value (the {@code EXEC CICS XCTL} target program, the sign-on program on PF3, or {@code
 * null} to redisplay), the mutations it makes to the {@link CardDemoCommarea} navigation state, the
 * {@link AuthorizationException} it raises for the whole-screen admin gate, and the message /
 * option-line content it writes to the {@link AdminMenuScreen}. No Spring context, mocks, or
 * database is required; the service is instantiated directly.
 *
 * <p><strong>The defining contrast with {@code MainMenuServiceTest}.</strong> {@code COADM01C} is
 * structurally a twin of {@code COMEN01C}, but three behaviors differ and are pinned here exactly:
 *
 * <ol>
 *   <li><b>Whole-screen admin gate.</b> A non-admin reaching {@code COADM01C} is a true
 *       authorization violation: the service rejects the entire screen by throwing {@link
 *       AuthorizationException} at method entry, <em>before</em> any first-entry paint or AID
 *       handling. The main menu has no such gate. This is THE defining assertion for this test (see
 *       {@link #nonAdminUser_isRejectedByWholeScreenGate()} and friends).
 *   <li><b>No per-option role check.</b> The admin option table ({@code COADM02Y}) has no user-type
 *       column, so every shipped admin option has a {@code null} {@code userType} and dispatches
 *       unconditionally &mdash; there is no per-option {@code 'A'}/{@code 'U'} gate.
 *   <li><b>Name-less "coming soon" message.</b> In {@code COADM01C} the option-name segment of the
 *       "coming soon" {@code STRING} is commented out (L150-151), so {@link
 *       AdminMenuService#MSG_COMING_SOON} embeds <em>no</em> option name (the opposite of the main
 *       menu, which includes it).
 * </ol>
 *
 * <p><strong>Data-driven, drift-proof design.</strong> Dispatch and "coming soon" expectations are
 * <em>derived from</em> {@link MenuOptions#ADMIN_MENU_OPTIONS} rather than hard-coded. The shipped
 * {@code COADM02Y} table ships four real ({@code COUSR00C}..{@code COUSR03C}) rows and no {@code
 * DUMMY} placeholder, so the "coming soon" runtime branch is unreachable through the public API
 * with the live table; it is therefore exercised directly through the package-private {@link
 * AdminMenuService#dispatchSelectedOption} seam with a synthetic placeholder ({@link
 * #dispatchSelectedOption_placeholder_redisplaysComingSoon_withoutOptionName()}), and its name-less
 * parity is also verified statically ({@link
 * #comingSoonMessage_isByteExact_andOmitsEveryOptionName()}).
 *
 * <p><strong>Byte-exact message parity.</strong> The invalid-option, invalid-key, "coming soon",
 * and admin-only denial messages are compared against the production constants exactly as declared,
 * so a single-byte drift fails the test.
 */
class AdminMenuServiceTest {

  /** Service under test. It is stateless, so a fresh instance per test is sufficient. */
  private AdminMenuService service;

  @BeforeEach
  void setUp() {
    service = new AdminMenuService();
  }

  // ===== Fixtures / helpers =====================================================================

  /** Builds an admin-menu screen carrying the supplied raw operator selection ({@code OPTIONI}). */
  private static AdminMenuScreen screenWithOption(String option) {
    AdminMenuScreen screen = new AdminMenuScreen();
    screen.setOption(option);
    return screen;
  }

  /**
   * An administrator COMMAREA on first entry (program context still {@code ENTER}, not re-enter).
   */
  private static CardDemoCommarea firstEntryAdmin() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypAdmin();
    return commarea;
  }

  /** An administrator COMMAREA already in the re-enter (post-paint) state. */
  private static CardDemoCommarea reenteredAdmin() {
    CardDemoCommarea commarea = firstEntryAdmin();
    commarea.setPgmReenter();
    return commarea;
  }

  /** A standard-user COMMAREA on first entry (program context still {@code ENTER}). */
  private static CardDemoCommarea firstEntryUser() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUsrTypUser();
    return commarea;
  }

  /** A standard-user COMMAREA already in the re-enter (post-paint) state. */
  private static CardDemoCommarea reenteredUser() {
    CardDemoCommarea commarea = firstEntryUser();
    commarea.setPgmReenter();
    return commarea;
  }

  /**
   * Argument source for the per-option sweep: the shipped admin-menu option table. Deriving the
   * cases from production (rather than literals) keeps the test in lock-step with {@code COADM02Y}.
   */
  static List<MenuOptions.MenuOption> adminMenuOptions() {
    return MenuOptions.ADMIN_MENU_OPTIONS;
  }

  // ===== First entry (paint then redisplay) =====================================================

  @Test
  @DisplayName("First entry (admin, not re-enter): paints the four admin options and redisplays")
  void firstEntry_buildsAdminMenuOptions_andRedisplays() {
    CardDemoCommarea commarea = firstEntryAdmin();
    AdminMenuScreen screen = screenWithOption(null);

    // COBOL MAIN-PARA "IF NOT CDEMO-PGM-REENTER" (L87-90): paint the screen, then the
    // pseudo-conversational RETURN re-displays it. No program is dispatched and the AID is ignored.
    String next = service.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // SET CDEMO-PGM-REENTER TO TRUE — the next interaction is processed as a re-entry.
    assertThat(commarea.isPgmReenter()).isTrue();

    // BUILD-MENU-OPTIONS (L226-263) fills OPTN001O..OPTN012O: twelve slots, the first four
    // populated.
    assertThat(screen.getOptions()).hasSize(AdminMenuService.MENU_SLOT_COUNT);
    for (MenuOptions.MenuOption option : MenuOptions.ADMIN_MENU_OPTIONS) {
      // STRING opt-num '. ' opt-name -> the two-digit selector, ". ", then the 35-char name.
      String expected = String.format("%02d", option.number()) + ". " + option.name();
      assertThat(screen.getOptions().get(option.number() - 1)).isEqualTo(expected);
    }
    // The trailing OCCURS-9 slots beyond the active count stay blank (the COBOL LOW-VALUES state).
    for (int slot = MenuOptions.ADMIN_MENU_OPT_COUNT;
        slot < AdminMenuService.MENU_SLOT_COUNT;
        slot++) {
      assertThat(screen.getOptions().get(slot)).isEmpty();
    }

    // POPULATE-HEADER-INFO (L202-221): transaction id, program name, and the two title lines.
    assertThat(screen.getTrnName()).isEqualTo(AdminMenuService.TRAN_ID);
    assertThat(screen.getPgmName()).isEqualTo(AdminMenuService.PGM_NAME);
    assertThat(screen.getTitle01()).isEqualTo(MenuOptions.TITLE_LINE_1);
    assertThat(screen.getTitle02()).isEqualTo(MenuOptions.TITLE_LINE_2);
    // The date/time masks render fixed-width MM/dd/yy and HH:mm:ss values (8 characters each).
    assertThat(screen.getCurDate()).hasSize(8);
    assertThat(screen.getCurTime()).hasSize(8);
    // MOVE SPACES TO WS-MESSAGE / ERRMSGO — a clean paint carries no error message.
    assertThat(screen.getErrMsg()).isEmpty();
  }

  @Test
  @DisplayName("First entry ignores the operator selection (returns before option processing)")
  void firstEntry_ignoresOptionInput() {
    CardDemoCommarea commarea = firstEntryAdmin();
    // A would-be valid selection is supplied, but first entry paints and returns before reading it.
    AdminMenuScreen screen = screenWithOption("1");

    String next = service.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    // No dispatch occurred: the routing target is untouched.
    assertThat(commarea.getToProgram()).isNull();
  }

  // ===== Re-entry + ENTER (option processing / dispatch) ========================================

  @ParameterizedTest
  @MethodSource("adminMenuOptions")
  @DisplayName(
      "Re-entry + ENTER: each shipped admin option dispatches (XCTL) to its target program")
  void enter_eachShippedAdminOption_dispatchesToTargetProgram(MenuOptions.MenuOption option) {
    CardDemoCommarea commarea = reenteredAdmin();
    AdminMenuScreen screen = screenWithOption(Integer.toString(option.number()));

    String next = service.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    // COADM02Y has no user-type column and ships no DUMMY row, so every option is a real XCTL
    // target with no per-option gate (deliberate difference (2) from the COMEN01C twin).
    assertThat(option.userType()).isNull();
    assertThat(option.programName()).doesNotStartWith(AdminMenuService.DUMMY_PREFIX);

    // EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)) — route to the selected program.
    assertThat(next).isEqualTo(option.programName());
    assertThat(commarea.getToProgram()).isEqualTo(option.programName());
    // MOVE WS-TRANID / WS-PGMNAME TO CDEMO-FROM-* (L139-140).
    assertThat(commarea.getFromTranId()).isEqualTo(AdminMenuService.TRAN_ID);
    assertThat(commarea.getFromProgram()).isEqualTo(AdminMenuService.PGM_NAME);
    // MOVE ZEROS TO CDEMO-PGM-CONTEXT (L141) — the next program begins in first-entry state.
    assertThat(commarea.isPgmEnter()).isTrue();
    // MOVE WS-OPTION TO OPTIONO (L125) — the normalized two-digit value is echoed back.
    assertThat(screen.getOption()).isEqualTo(String.format("%02d", option.number()));
    // The dispatch path sets no error message.
    assertThat(screen.getErrMsg()).isEmpty();
  }

  @Test
  @DisplayName("Re-entry + ENTER + first admin option (User List): dispatches to COUSR00C")
  void enter_validAdminOption_dispatchesToTargetProgram() {
    MenuOptions.MenuOption first = MenuOptions.ADMIN_MENU_OPTIONS.get(0);
    CardDemoCommarea commarea = reenteredAdmin();
    AdminMenuScreen screen = screenWithOption(Integer.toString(first.number()));

    String next = service.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    // The first admin option is User List (Security) -> COUSR00C (derived from MenuOptions).
    assertThat(next).isEqualTo(first.programName());
    assertThat(commarea.getToProgram()).isEqualTo(first.programName());
    assertThat(commarea.getFromTranId()).isEqualTo(AdminMenuService.TRAN_ID);
    assertThat(commarea.getFromProgram()).isEqualTo(AdminMenuService.PGM_NAME);
    assertThat(commarea.isPgmEnter()).isTrue();
    assertThat(commarea.getPgmContext()).isEqualTo(CardDemoCommarea.PGM_CONTEXT_ENTER);
    assertThat(screen.getOption()).isEqualTo("01");
    assertThat(screen.getErrMsg()).isEmpty();
  }

  // ===== Re-entry + ENTER + invalid selection ===================================================

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "0", "00", "5", "9", "99", "10", "AB", "1A"})
  @DisplayName("Re-entry + ENTER + invalid selection: redisplays with the invalid-option message")
  void enter_invalidOption_redisplaysInvalidOption(String rawOption) {
    CardDemoCommarea commarea = reenteredAdmin();
    AdminMenuScreen screen = screenWithOption(rawOption);

    String next = service.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    // IF WS-OPTION IS NOT NUMERIC OR > CDEMO-ADMIN-OPT-COUNT OR = ZEROS (L127-134): redisplay with
    // the message. Spaces-to-zero parity means blank / null / " " all normalize to "00" and fail
    // the zero test; "5".."99"/"10" exceed the four-option count; "AB"/"1A" fail the numeric test.
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(AdminMenuService.MSG_INVALID_OPTION);
    // No program is dispatched on an invalid selection.
    assertThat(commarea.getToProgram()).isNull();
  }

  @Test
  @DisplayName("Boundary: option 4 (the last admin option) is valid and dispatches to COUSR03C")
  void enter_lastValidOption_dispatches() {
    MenuOptions.MenuOption last =
        MenuOptions.ADMIN_MENU_OPTIONS.get(MenuOptions.ADMIN_MENU_OPT_COUNT - 1);
    CardDemoCommarea commarea = reenteredAdmin();
    AdminMenuScreen screen = screenWithOption(Integer.toString(last.number()));

    String next = service.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo(last.programName());
    assertThat(commarea.getToProgram()).isEqualTo(last.programName());
    assertThat(screen.getOption()).isEqualTo(String.format("%02d", last.number()));
  }

  // ===== ENTER + "coming soon" placeholder branch — exercised directly via the seam (no skip) ====

  @Test
  @DisplayName("Placeholder (DUMMY) option dispatch redisplays the name-less 'coming soon' message")
  void dispatchSelectedOption_placeholder_redisplaysComingSoon_withoutOptionName() {
    // The shipped COADM02Y table ships four real (COUSR00C..COUSR03C) options and no DUMMY
    // placeholder, so the "coming soon" branch is unreachable through the public processAdminMenu
    // API with production data (the prior end-to-end test was @Disabled for exactly that reason).
    // Exercise the production branch directly through the package-private dispatchSelectedOption
    // seam with a synthetic placeholder whose program name carries the DUMMY_PREFIX — production
    // data stays untouched while the runtime behavior is proven, and no test is skipped.
    MenuOptions.MenuOption placeholder =
        new MenuOptions.MenuOption(
            9, "Coming Soon Option", AdminMenuService.DUMMY_PREFIX + "001", null);
    CardDemoCommarea commarea = reenteredAdmin();
    AdminMenuScreen screen = screenWithOption("9");

    String next = service.dispatchSelectedOption(placeholder, screen, commarea);

    assertThat(next).isNull();
    // Deliberate difference (3) from the COMEN01C twin: COADM01C comments out the option-name
    // segment of the STRING (L150-151), so the message embeds NO option name.
    assertThat(screen.getErrMsg()).isEqualTo(AdminMenuService.MSG_COMING_SOON);
    assertThat(screen.getErrMsg()).contains("is coming soon ...");
    assertThat(screen.getErrMsg()).doesNotContain(placeholder.name().trim());
    assertThat(commarea.getToProgram()).isNull();
  }

  // ===== Whole-screen admin gate (THE defining behavior, AAP §0.6.5) ============================

  @Test
  @DisplayName("Whole-screen gate: a standard user is rejected with AuthorizationException")
  void nonAdminUser_isRejectedByWholeScreenGate() {
    CardDemoCommarea commarea = reenteredUser();
    AdminMenuScreen screen = screenWithOption("1");

    // COADM01C is reachable only by an administrator (COSGN00C routes admins here); a non-admin
    // reaching the admin menu is a true authorization violation, so the service rejects the entire
    // screen rather than redisplaying. This is deliberate difference (1) from the main-menu twin.
    assertThatThrownBy(() -> service.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER))
        .isInstanceOf(AuthorizationException.class)
        .hasMessage(AuthorizationException.ADMIN_ONLY_MESSAGE);

    // No dispatch and no screen paint: the gate threw before any menu logic ran.
    assertThat(commarea.getToProgram()).isNull();
    assertThat(screen.getOptions()).isEmpty();
    assertThat(screen.getErrMsg()).isNull();
  }

  @Test
  @DisplayName("Whole-screen gate precedes the first-entry paint for a non-admin")
  void nonAdminUser_gatePrecedesFirstEntryPaint() {
    // A standard user on FIRST entry (not re-enter): the gate must fire before the paint branch,
    // so the re-enter flag is never set and the screen is never painted.
    CardDemoCommarea commarea = firstEntryUser();
    AdminMenuScreen screen = screenWithOption(null);

    assertThatThrownBy(() -> service.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER))
        .isInstanceOf(AuthorizationException.class)
        .hasMessage(AuthorizationException.ADMIN_ONLY_MESSAGE);

    assertThat(commarea.isPgmReenter()).isFalse();
    assertThat(screen.getOptions()).isEmpty();
    assertThat(screen.getErrMsg()).isNull();
  }

  @ParameterizedTest
  @NullSource
  @EnumSource(
      value = CardWorkArea.Aid.class,
      names = {"ENTER", "PFK03", "PFK10"})
  @DisplayName("Whole-screen gate is AID-agnostic: a non-admin is rejected regardless of the key")
  void nonAdminUser_gateIsAidAgnostic(CardWorkArea.Aid aid) {
    CardDemoCommarea commarea = reenteredUser();
    AdminMenuScreen screen = screenWithOption("1");

    // The gate is evaluated before the EVALUATE EIBAID branch, so ENTER, PF3, an arbitrary PF key,
    // and an unmapped (null) AID are all rejected identically.
    assertThatThrownBy(() -> service.processAdminMenu(screen, commarea, aid))
        .isInstanceOf(AuthorizationException.class)
        .hasMessage(AuthorizationException.ADMIN_ONLY_MESSAGE);
    assertThat(commarea.getToProgram()).isNull();
  }

  @Test
  @DisplayName("Whole-screen gate: a COMMAREA with no user type set is treated as non-admin")
  void noUserType_isRejectedByWholeScreenGate() {
    // A fresh COMMAREA has a null user type (neither 'A' nor 'U'); isAdmin() is false, so the gate
    // rejects it. This mirrors the COBOL invariant that only an administrator ever reaches here.
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setPgmReenter();
    AdminMenuScreen screen = screenWithOption("1");

    assertThatThrownBy(() -> service.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER))
        .isInstanceOf(AuthorizationException.class)
        .hasMessage(AuthorizationException.ADMIN_ONLY_MESSAGE);
    assertThat(commarea.getToProgram()).isNull();
  }

  // ===== Re-entry + PF3 (return to sign-on) =====================================================

  @Test
  @DisplayName("Re-entry + PF3 (admin): returns to the sign-on program (COSGN00C)")
  void pfk03_returnsToSignon() {
    CardDemoCommarea commarea = reenteredAdmin();
    AdminMenuScreen screen = screenWithOption(null);

    String next = service.processAdminMenu(screen, commarea, CardWorkArea.Aid.PFK03);

    // RETURN-TO-SIGNON-SCREEN (L96-98, L160-167): MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, then XCTL.
    assertThat(next).isEqualTo(AdminMenuService.SIGNON_PROGRAM);
    assertThat(next).isEqualTo("COSGN00C");
    assertThat(commarea.getToProgram()).isEqualTo(AdminMenuService.SIGNON_PROGRAM);
  }

  // ===== Re-entry + any other key (invalid key) =================================================

  @ParameterizedTest
  @EnumSource(
      value = CardWorkArea.Aid.class,
      names = {"ENTER", "PFK03"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName(
      "Re-entry + any non-ENTER / non-PF3 key (admin): redisplays with the invalid-key msg")
  void invalidAid_redisplaysInvalidKey(CardWorkArea.Aid aid) {
    CardDemoCommarea commarea = reenteredAdmin();
    AdminMenuScreen screen = screenWithOption("1");

    String next = service.processAdminMenu(screen, commarea, aid);

    // EVALUATE EIBAID WHEN OTHER (L99-102): MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE, then SEND. The
    // service trims the heavily space-padded PIC X(50) field for display, so compare against
    // trim().
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    assertThat(commarea.getToProgram()).isNull();
  }

  @Test
  @DisplayName(
      "Re-entry + unmapped (null) AID (admin): treated as WHEN OTHER — invalid-key message")
  void nullAid_redisplaysInvalidKey() {
    CardDemoCommarea commarea = reenteredAdmin();
    AdminMenuScreen screen = screenWithOption("1");

    String next = service.processAdminMenu(screen, commarea, null);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY.trim());
    assertThat(commarea.getToProgram()).isNull();
  }

  // ===== Argument guards ========================================================================

  @Test
  @DisplayName("Null screen argument fails fast with NullPointerException")
  void processAdminMenu_nullScreen_throwsNpe() {
    CardDemoCommarea commarea = reenteredAdmin();

    assertThatThrownBy(() -> service.processAdminMenu(null, commarea, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("screen must not be null");
  }

  @Test
  @DisplayName("Null commarea argument fails fast with NullPointerException")
  void processAdminMenu_nullCommarea_throwsNpe() {
    AdminMenuScreen screen = screenWithOption("1");

    assertThatThrownBy(() -> service.processAdminMenu(screen, null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("commarea must not be null");
  }

  // ===== Shipped-table parity facts (document the inert per-option gate / "coming soon") ========

  @Test
  @DisplayName("Parity fact: every shipped admin option has no user type (no per-option gate)")
  void everyShippedAdminOption_hasNoUserType() {
    // COADM02Y has no user-type column, so — unlike COMEN02Y — there is no per-option 'A'/'U' gate;
    // the whole screen is already admin-only. Every shipped option therefore carries a null type.
    assertThat(MenuOptions.ADMIN_MENU_OPTIONS)
        .allSatisfy(option -> assertThat(option.userType()).isNull());
  }

  @Test
  @DisplayName("Parity fact: every shipped admin option targets a real program (no DUMMY)")
  void everyShippedAdminOption_targetsRealProgram() {
    // No COADM02Y row is a placeholder, so the "coming soon" branch is inert for the live table.
    assertThat(MenuOptions.ADMIN_MENU_OPTIONS)
        .allSatisfy(
            option ->
                assertThat(option.programName()).doesNotStartWith(AdminMenuService.DUMMY_PREFIX));
  }

  @Test
  @DisplayName("Boundary anchor: the active admin option count equals the shipped table size")
  void adminMenuOptionCount_matchesShippedTableSize() {
    assertThat(MenuOptions.ADMIN_MENU_OPT_COUNT).isEqualTo(MenuOptions.ADMIN_MENU_OPTIONS.size());
    assertThat(MenuOptions.ADMIN_MENU_OPT_COUNT).isEqualTo(4);
  }

  // ===== Byte-exact message-literal parity ======================================================

  @Test
  @DisplayName("Message parity: the invalid-option literal is byte-exact")
  void invalidOptionMessage_isByteExact() {
    // COADM01C L131.
    assertThat(AdminMenuService.MSG_INVALID_OPTION)
        .isEqualTo("Please enter a valid option number...");
  }

  @Test
  @DisplayName(
      "Message parity: the 'coming soon' literal is byte-exact AND omits every option name")
  void comingSoonMessage_isByteExact_andOmitsEveryOptionName() {
    // COADM01C L149-153: STRING 'This option ' + 'is coming soon ...' with the option-name segment
    // commented out, so the rendered text has a single space between the two literals and embeds no
    // option name. This is the crux of the COADM01C / COMEN01C divergence.
    assertThat(AdminMenuService.MSG_COMING_SOON).isEqualTo("This option is coming soon ...");
    for (MenuOptions.MenuOption option : MenuOptions.ADMIN_MENU_OPTIONS) {
      assertThat(AdminMenuService.MSG_COMING_SOON).doesNotContain(option.name().trim());
    }
  }

  @Test
  @DisplayName("Message parity: the admin-only denial literal is byte-exact")
  void adminOnlyMessage_isByteExact() {
    // The whole-screen gate surfaces AuthorizationException's no-arg message, preserved verbatim
    // from the legacy COBOL denial text (three trailing dots, no trailing pad space).
    assertThat(AuthorizationException.ADMIN_ONLY_MESSAGE)
        .isEqualTo("No access - Admin Only option...");
  }

  // ===== Package-private helper parity (normalize / numeric class test) =========================

  @Test
  @DisplayName("normalizeOption mirrors the COBOL right-justify + INSPECT REPLACING ' ' BY '0'")
  void normalizeOption_mirrorsCobolRightJustifyAndZeroFill() {
    // Blank / null collapse to "00" (every space is replaced by '0').
    assertThat(AdminMenuService.normalizeOption(null)).isEqualTo("00");
    assertThat(AdminMenuService.normalizeOption("")).isEqualTo("00");
    assertThat(AdminMenuService.normalizeOption(" ")).isEqualTo("00");
    assertThat(AdminMenuService.normalizeOption("  ")).isEqualTo("00");
    // A single significant digit is right-justified, then zero-filled.
    assertThat(AdminMenuService.normalizeOption("1")).isEqualTo("01");
    assertThat(AdminMenuService.normalizeOption("4")).isEqualTo("04");
    assertThat(AdminMenuService.normalizeOption(" 4")).isEqualTo("04");
    assertThat(AdminMenuService.normalizeOption("4 ")).isEqualTo("04");
    // Two-character inputs are preserved.
    assertThat(AdminMenuService.normalizeOption("10")).isEqualTo("10");
    assertThat(AdminMenuService.normalizeOption("00")).isEqualTo("00");
    assertThat(AdminMenuService.normalizeOption("99")).isEqualTo("99");
    // Non-numeric characters are retained verbatim (the numeric class test rejects them later).
    assertThat(AdminMenuService.normalizeOption("AB")).isEqualTo("AB");
    // Inputs longer than the PIC X(2) field are truncated to two characters.
    assertThat(AdminMenuService.normalizeOption("123")).isEqualTo("12");
  }

  @Test
  @DisplayName("isAllDigits mirrors the COBOL IS NUMERIC class test on a PIC 9 field")
  void isAllDigits_mirrorsCobolNumericClassTest() {
    assertThat(AdminMenuService.isAllDigits("04")).isTrue();
    assertThat(AdminMenuService.isAllDigits("00")).isTrue();
    assertThat(AdminMenuService.isAllDigits("0")).isTrue();
    assertThat(AdminMenuService.isAllDigits("12")).isTrue();
    assertThat(AdminMenuService.isAllDigits("")).isFalse();
    assertThat(AdminMenuService.isAllDigits(null)).isFalse();
    assertThat(AdminMenuService.isAllDigits("AB")).isFalse();
    assertThat(AdminMenuService.isAllDigits("1A")).isFalse();
    assertThat(AdminMenuService.isAllDigits("1 ")).isFalse();
  }
}
