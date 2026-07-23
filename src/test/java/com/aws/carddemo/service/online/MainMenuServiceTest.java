/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.menu.MainMenuOptions;
import com.aws.carddemo.dto.menu.MainMenuOptions.MainMenuOption;
import com.aws.carddemo.dto.screen.COMEN01Form;
import com.aws.carddemo.service.online.MainMenuService.MainMenuResult;
import com.aws.carddemo.service.online.MainMenuService.MessageSeverity;
import com.aws.carddemo.service.online.MainMenuService.RoutingAction;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-Mockito unit tests for {@link MainMenuService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COMEN01C.cbl}
 * (CICS COBOL program {@code COMEN01C}, transaction id {@code CM00}) &mdash; the
 * regular-user main menu. These tests assert one-for-one control-flow parity with
 * the numbered paragraphs of the oracle that the service migrates (AAP
 * &sect;0.1.2 / &sect;0.4.1 / &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link MainMenuService#mainEntry} &mdash; the
 *       pseudo-conversational state machine ({@code EIBCALEN = 0} first entry,
 *       first program entry, and the {@code EVALUATE EIBAID} re-entry branches
 *       for {@code DFHENTER}, {@code DFHPF3}, and {@code WHEN OTHER}).</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link MainMenuService#processEnterKey} &mdash; option-field
 *       normalization ({@code INSPECT ... REPLACING ALL ' ' BY '0'}), the invalid
 *       / admin-only / route / "coming soon" branches, and the exact message
 *       literals.</li>
 *   <li>{@code RETURN-TO-SIGNON-SCREEN} &rarr;
 *       {@link MainMenuService#returnToSignonScreen} &mdash; default-to-sign-on
 *       versus preserve-existing-target ({@code = LOW-VALUES OR SPACES}).</li>
 *   <li>{@code BUILD-MENU-OPTIONS} &rarr;
 *       {@link MainMenuService#buildMenuOptions} &mdash; the ten regular-user
 *       option display lines.</li>
 * </ul>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test
 * ({@link MockitoExtension}); it uses no database, no Spring context, and no
 * Testcontainers. Collaborators are mocked and the service is exercised in
 * isolation. Routing outcomes are asserted on the mocked {@link CardDemoContext}
 * (the {@code COMMAREA} replacement) rather than on any HTTP redirect, because
 * this service records the routing target in the context and defers the actual
 * {@code XCTL}/redirect to the controller.</p>
 *
 * <p><b>Faithfulness note:</b> the migrated {@link MainMenuOption} carries no
 * transaction id (the COBOL {@code XCTL PROGRAM(...)} sets only
 * {@code CDEMO-FROM-TRANID}/{@code CDEMO-FROM-PROGRAM}/{@code CDEMO-PGM-CONTEXT}
 * and {@code CDEMO-TO-PROGRAM}), so the route assertions verify
 * {@code setToProgram}/{@code setFromProgram}/{@code setFromTranid}/
 * {@code markEnter} and never a per-option {@code toTranid}.</p>
 */
@ExtendWith(MockitoExtension.class)
class MainMenuServiceTest {

    /** COBOL {@code WS-TRANID} of {@code COMEN01C}; recorded as the origin tran id on routing. */
    private static final String MENU_TRANSACTION_ID = "CM00";

    /** COBOL {@code WS-PGMNAME} of {@code COMEN01C}; recorded as the origin program on routing. */
    private static final String MENU_PROGRAM_NAME = "COMEN01C";

    /** Sign-on program ({@code COSGN00C}); the {@code XCTL} target on first entry and PF3. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Sign-on transaction id ({@code CC00}); recorded for the controller redirect to sign-on. */
    private static final String SIGNON_TRANSACTION_ID = "CC00";

    /** Exact oracle literal from {@code PROCESS-ENTER-KEY} for a bad option number. */
    private static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /** Exact oracle literal (with trailing space) for a regular user picking an admin-only option. */
    private static final String MSG_ADMIN_ONLY = "No access - Admin Only option... ";

    /** Leading fragment of the oracle {@code CCDA-MSG-INVALID-KEY} literal (WHEN OTHER branch). */
    private static final String MSG_INVALID_KEY_PREFIX = "Invalid key pressed";

    /** Session-scoped {@code COMMAREA} replacement, mocked so routing writes are observable. */
    @Mock
    private CardDemoContext context;

    /**
     * Migrated {@code COMEN02Y} option table, mocked so a controlled option set
     * (including a {@code DUMMY} placeholder, absent from the real ten-option
     * table) can be injected per test.
     */
    @Mock
    private MainMenuOptions mainMenuOptions;

    /** Service under test; Mockito constructor-injects the two mocked collaborators. */
    @InjectMocks
    private MainMenuService service;

    /**
     * Builds a real {@link COMEN01Form} fixture with the option-input field set.
     *
     * @param optionInput the raw {@code OPTIONI} value to place on the form
     * @return a populated form
     */
    private static COMEN01Form formWithOption(String optionInput) {
        COMEN01Form form = new COMEN01Form();
        form.setOption(optionInput);
        return form;
    }

    // ------------------------------------------------------------------
    // BUILD-MENU-OPTIONS (checklist item 1)
    // ------------------------------------------------------------------

    /**
     * The main-menu option table is the ten-entry regular-user ("U") menu, not the
     * admin menu (COBOL {@code CDEMO-MENU-OPT-COUNT VALUE 10} with array capacity
     * {@code OCCURS 12}). Asserted against the real seeded table.
     */
    @Test
    void optionTable_isRegularUserMenuOfTenOptions() {
        MainMenuOptions realTable = new MainMenuOptions();

        assertThat(realTable.getOptions()).hasSize(10);
        assertThat(realTable.getOptionCount()).isEqualTo(10);
        assertThat(realTable.getMaxOptions()).isEqualTo(12);
        assertThat(realTable.getOptions())
                .allMatch(option -> "U".equals(option.requiredUserType()));
        assertThat(realTable.getOptions())
                .noneMatch(option -> "A".equals(option.requiredUserType()));
    }

    /**
     * {@link MainMenuService#buildMenuOptions} formats exactly ten
     * {@code "NN. Name"} lines (COBOL {@code STRING CDEMO-MENU-OPT-NUM '. '
     * CDEMO-MENU-OPT-NAME}), zero-padded to two digits because the number picture
     * is {@code PIC 9(02)}.
     */
    @Test
    void buildMenuOptions_yieldsTenNumberedUserMenuLines() {
        List<MainMenuOption> realOptions = new MainMenuOptions().getOptions();
        when(mainMenuOptions.getOptions()).thenReturn(realOptions);
        when(mainMenuOptions.getOptionCount()).thenReturn(10);

        List<String> lines = service.buildMenuOptions();

        assertThat(lines).hasSize(10);
        assertThat(lines.get(0)).isEqualTo("01. Account View");
        assertThat(lines.get(9)).isEqualTo("10. Bill Payment");
    }

    // ------------------------------------------------------------------
    // PROCESS-ENTER-KEY (checklist items 2, 3, 4, 5 + admin-only branch)
    // ------------------------------------------------------------------

    /**
     * Option-field normalization: a blank {@code OPTIONI} is converted space-to-zero
     * (COBOL {@code INSPECT ... REPLACING ALL ' ' BY '0'}) to {@code "00"}, which
     * parses to option {@code 0} and takes the invalid-option branch. The normalized
     * value is echoed back onto the form ({@code MOVE WS-OPTION TO OPTIONO}) and no
     * routing occurs (checklist items 2 and 3).
     */
    @Test
    void processEnterKey_blankOptionNormalizesToZeroAndIsRejected() {
        COMEN01Form form = formWithOption("  ");

        MainMenuResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_MENU);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_OPTION);
        assertThat(form.getOption()).isEqualTo("00");
        verifyNoInteractions(context);
    }

    /**
     * An option number greater than {@code CDEMO-MENU-OPT-COUNT} is rejected with
     * the invalid-option message and performs no routing (checklist item 3).
     */
    @Test
    void processEnterKey_optionAboveCountIsRejected() {
        when(mainMenuOptions.getOptionCount()).thenReturn(10);
        COMEN01Form form = formWithOption("99");

        MainMenuResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_MENU);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_OPTION);
        assertThat(form.getOption()).isEqualTo("99");
        verifyNoInteractions(context);
    }

    /**
     * A non-numeric option fails the COBOL {@code IS NUMERIC} class test and is
     * rejected with the invalid-option message. Normalization right-justifies into
     * {@code PIC X(02)} and replaces the leading space with {@code '0'}, so
     * {@code "A"} becomes {@code "0A"} (checklist item 3).
     */
    @Test
    void processEnterKey_nonNumericOptionIsRejected() {
        COMEN01Form form = formWithOption("A");

        MainMenuResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_MENU);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_OPTION);
        assertThat(form.getOption()).isEqualTo("0A");
        verifyNoInteractions(context);
    }

    /**
     * A valid, non-{@code DUMMY} option routes to its target program: the origin
     * transaction/program are recorded, the program context is reset to enter
     * ({@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}), and the target program is stored on
     * the context ({@code XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME)}). The normalized
     * option is echoed back onto the form (checklist item 4).
     */
    @Test
    void processEnterKey_validOptionRoutesToTargetProgram() {
        List<MainMenuOption> options = List.of(
                new MainMenuOption(1, "Account View", "COACTVWC", "U"),
                new MainMenuOption(2, "Reports Feature", "DUMMY01C", "U"));
        when(mainMenuOptions.getOptionCount()).thenReturn(options.size());
        when(mainMenuOptions.getOptions()).thenReturn(options);
        COMEN01Form form = formWithOption("1");

        MainMenuResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(form.getOption()).isEqualTo("01");
        verify(context).setFromTranid(MENU_TRANSACTION_ID);
        verify(context).setFromProgram(MENU_PROGRAM_NAME);
        verify(context).markEnter();
        verify(context).setToProgram("COACTVWC");
    }

    /**
     * A {@code DUMMY}-prefixed option is a not-yet-implemented placeholder: the
     * service returns the green {@code DFHGREEN} "coming soon" message and performs
     * no routing. The message reproduces the COBOL {@code STRING ... DELIMITED BY
     * SPACE} quirk exactly &mdash; the option name is truncated at its first space
     * and no separating space precedes {@code "is"} (checklist item 5).
     */
    @Test
    void processEnterKey_dummyOptionReportsComingSoon() {
        List<MainMenuOption> options = List.of(
                new MainMenuOption(1, "Account View", "COACTVWC", "U"),
                new MainMenuOption(2, "Reports Feature", "DUMMY01C", "U"));
        when(mainMenuOptions.getOptionCount()).thenReturn(options.size());
        when(mainMenuOptions.getOptions()).thenReturn(options);
        COMEN01Form form = formWithOption("2");

        MainMenuResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_MENU);
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
        assertThat(result.message()).isEqualTo("This option Reportsis coming soon ...");
        verify(context, never()).setToProgram(anyString());
    }

    /**
     * The admin guard (COBOL {@code IF CDEMO-USRTYP-USER AND
     * CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'}) blocks a regular user from selecting
     * an admin-only option, returning the exact "No access" message (with its
     * trailing space) and performing no routing. Preserved for faithfulness even
     * though the standard ten-option table contains no admin-only entries.
     */
    @Test
    void processEnterKey_adminOnlyOptionBlockedForRegularUser() {
        List<MainMenuOption> options = List.of(
                new MainMenuOption(1, "Admin Only Menu", "COADM01C", "A"));
        when(mainMenuOptions.getOptionCount()).thenReturn(options.size());
        when(mainMenuOptions.getOptions()).thenReturn(options);
        when(context.isUser()).thenReturn(true);
        COMEN01Form form = formWithOption("1");

        MainMenuResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_MENU);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_ADMIN_ONLY);
        verify(context, never()).setToProgram(anyString());
    }

    // ------------------------------------------------------------------
    // RETURN-TO-SIGNON-SCREEN
    // ------------------------------------------------------------------

    /**
     * When the target program is unset ({@code = LOW-VALUES OR SPACES}),
     * {@link MainMenuService#returnToSignonScreen} defaults it to {@code COSGN00C}
     * and records the sign-on transaction id {@code CC00}.
     */
    @Test
    void returnToSignonScreen_defaultsToSignonWhenTargetBlank() {
        when(context.getToProgram()).thenReturn(null);

        MainMenuResult result = service.returnToSignonScreen(context);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        verify(context).setToProgram(SIGNON_PROGRAM);
        verify(context).setToTranid(SIGNON_TRANSACTION_ID);
    }

    /**
     * When the target program is already set,
     * {@link MainMenuService#returnToSignonScreen} preserves it (it does not
     * overwrite with {@code COSGN00C}) while still recording the sign-on
     * transaction id.
     */
    @Test
    void returnToSignonScreen_preservesExistingTarget() {
        when(context.getToProgram()).thenReturn(MENU_PROGRAM_NAME);

        MainMenuResult result = service.returnToSignonScreen(context);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        verify(context, never()).setToProgram(anyString());
        verify(context).setToTranid(SIGNON_TRANSACTION_ID);
    }

    // ------------------------------------------------------------------
    // MAIN-PARA (state machine + EVALUATE EIBAID)
    // ------------------------------------------------------------------

    /**
     * On re-entry, {@code PF3} ({@code DFHPF3}) hands control back to the sign-on
     * screen: the target program is {@code COSGN00C} and the sign-on transaction id
     * {@code CC00} is recorded (checklist item 6). {@code setToProgram("COSGN00C")}
     * is asserted at-least-once because {@code MAIN-PARA} sets it and
     * {@code RETURN-TO-SIGNON-SCREEN} then confirms it.
     */
    @Test
    void mainEntry_pf3ReturnsToSignon() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        MainMenuResult result = service.mainEntry(new COMEN01Form(), PfKey.PFK03);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        verify(context, atLeastOnce()).setToProgram(SIGNON_PROGRAM);
        verify(context).setToTranid(SIGNON_TRANSACTION_ID);
    }

    /**
     * On re-entry, any key other than {@code ENTER}/{@code PF3} takes the COBOL
     * {@code WHEN OTHER} branch and shows the invalid-key error
     * ({@code CCDA-MSG-INVALID-KEY}); no routing is performed.
     */
    @Test
    void mainEntry_unrecognizedKeyShowsInvalidKeyError() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        MainMenuResult result = service.mainEntry(new COMEN01Form(), PfKey.PFK07);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_MENU);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).startsWith(MSG_INVALID_KEY_PREFIX);
        verify(context, never()).setToProgram(anyString());
    }

    /**
     * A {@code null} attention id is treated as the {@code WHEN OTHER} branch (the
     * service maps a missing key to {@link PfKey#OTHER}), yielding the invalid-key
     * error rather than throwing.
     */
    @Test
    void mainEntry_nullKeyTreatedAsInvalidKey() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        MainMenuResult result = service.mainEntry(new COMEN01Form(), null);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_MENU);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).startsWith(MSG_INVALID_KEY_PREFIX);
    }

    /**
     * First program entry (COBOL {@code IF NOT CDEMO-PGM-REENTER}): the service
     * flips the program context to re-enter and re-displays a fresh menu with no
     * message, performing no routing.
     */
    @Test
    void mainEntry_firstProgramEntryShowsFreshMenu() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);

        MainMenuResult result = service.mainEntry(new COMEN01Form(), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_MENU);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.message()).isEmpty();
        verify(context).markReenter();
        verify(context, never()).setToProgram(anyString());
    }

    /**
     * First entry with no COMMAREA (COBOL {@code IF EIBCALEN = 0},
     * {@link CardDemoContext#isNew()}): the menu cannot run without a signed-on
     * session, so it records the sign-on program as the origin and hands control
     * back to the sign-on screen ({@code COSGN00C} / {@code CC00}) (checklist
     * item 7).
     */
    @Test
    void mainEntry_firstEntryNoCommareaReturnsToSignon() {
        when(context.isNew()).thenReturn(true);

        MainMenuResult result = service.mainEntry(new COMEN01Form(), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        verify(context).setFromProgram(SIGNON_PROGRAM);
        verify(context).setToProgram(SIGNON_PROGRAM);
        verify(context).setToTranid(SIGNON_TRANSACTION_ID);
    }

    /**
     * On re-entry, {@code ENTER} ({@code DFHENTER}) delegates to
     * {@code PROCESS-ENTER-KEY}; a valid selection routes to the chosen program.
     * This exercises the {@code MAIN-PARA} {@code EVALUATE EIBAID} ENTER branch
     * end-to-end (checklist item 4 via {@link MainMenuService#mainEntry}).
     */
    @Test
    void mainEntry_reentryEnterRoutesSelectedOption() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        List<MainMenuOption> options = List.of(
                new MainMenuOption(1, "Account View", "COACTVWC", "U"));
        when(mainMenuOptions.getOptionCount()).thenReturn(options.size());
        when(mainMenuOptions.getOptions()).thenReturn(options);
        COMEN01Form form = formWithOption("1");

        MainMenuResult result = service.mainEntry(form, PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        verify(context).setToProgram("COACTVWC");
        verify(context).markEnter();
    }
}
