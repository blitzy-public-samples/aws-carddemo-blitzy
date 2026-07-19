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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.menu.AdminMenuOptions;
import com.aws.carddemo.dto.menu.AdminMenuOptions.AdminMenuOption;
import com.aws.carddemo.dto.screen.COADM01Form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link AdminMenuService}, the Java migration of the CICS COBOL
 * program {@code COADM01C} (the AWS CardDemo administrator main menu).
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COADM01C.cbl} &mdash; program
 * {@code COADM01C}, CICS transaction id {@code CA00}. These tests assert control-flow parity with
 * the program's numbered paragraphs:</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link AdminMenuService#mainEntry(AdminMenuService.AidKey, COADM01Form)}
 *       (the {@code EVALUATE EIBAID} dispatch, the {@code EIBCALEN = 0} first-entry bounce, and the
 *       {@code WHEN OTHER} invalid-key branch)</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link AdminMenuService#processEnterKey(COADM01Form, CardDemoContext)} (option normalization,
 *       numeric/range validation, and the {@code XCTL} hand-off)</li>
 *   <li>{@code RETURN-TO-SIGNON-SCREEN} &rarr;
 *       {@link AdminMenuService#returnToSignonScreen(CardDemoContext)} (the
 *       {@code CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES} default to {@code COSGN00C})</li>
 *   <li>{@code BUILD-MENU-OPTIONS} &rarr; {@link AdminMenuService#buildMenuOptions()} (the
 *       {@code PERFORM VARYING} that renders each numbered {@code "NN. Name"} line)</li>
 * </ul>
 *
 * <p><b>Test tier.</b> This is a strict-stubs pure-Mockito unit test:
 * {@link MockitoExtension} with {@link Mock} collaborators and an {@link InjectMocks} service. It
 * starts no Spring context, opens no database connection, and loads no persistence types &mdash; it
 * verifies the service's business logic in complete isolation. The presentation paragraphs
 * ({@code SEND-MENU-SCREEN}, {@code RECEIVE-MENU-SCREEN}, {@code POPULATE-HEADER-INFO}) are owned by
 * the paired controller and are therefore not exercised here.</p>
 *
 * <p><b>How routing parity is asserted.</b> {@code COADM01C}'s {@code XCTL} to a selected admin
 * program is reproduced by the service returning an {@link AdminMenuService.AdminMenuResult} whose
 * {@link AdminMenuService.AdminMenuResult#targetProgram() targetProgram} is the destination program
 * (the option table {@link AdminMenuOptions} carries a program name per option but no transaction
 * id). The COBOL hand-off moves ({@code CDEMO-FROM-TRANID = 'CA00'},
 * {@code CDEMO-FROM-PROGRAM = 'COADM01C'}, {@code CDEMO-PGM-CONTEXT = 0}) are asserted directly on
 * the mocked {@link CardDemoContext}. Accordingly, per-option routing is verified through the
 * returned redirect target plus these context hand-off writes, rather than a per-option
 * {@code setToTranid}, which the program does not perform.</p>
 */
@ExtendWith(MockitoExtension.class)
class AdminMenuServiceTest {

    /** Byte-exact invalid-option literal moved to {@code WS-MESSAGE} in {@code PROCESS-ENTER-KEY}. */
    private static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /** Neutral "coming soon" literal produced by the {@code STRING} for a {@code 'DUMMY'} option. */
    private static final String MSG_COMING_SOON = "This option is coming soon ...";

    /** Invalid-key literal (COBOL {@code CCDA-MSG-INVALID-KEY}) for the {@code WHEN OTHER} branch. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Sign-on program ({@code COSGN00C}) &mdash; first-entry bounce and PF3 return destination. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** This program's name (COBOL {@code WS-PGMNAME = 'COADM01C'}), set as the hand-off origin. */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /** This program's transaction id (COBOL {@code WS-TRANID = 'CA00'}), set as the hand-off origin. */
    private static final String ADMIN_TRANID = "CA00";

    /** Session context (COMMAREA replacement); mocked so hand-off writes can be verified. */
    @Mock
    private CardDemoContext context;

    /** Admin option table (copybook {@code COADM02Y}); stubbed with the four seeded options. */
    @Mock
    private AdminMenuOptions adminMenuOptions;

    /** Service under test, wired by constructor injection with the two mocks above. */
    @InjectMocks
    private AdminMenuService service;

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /**
     * Returns the four seeded admin-menu options exactly as declared in {@link AdminMenuOptions}
     * (copybook {@code COADM02Y}): the User List/Add/Update/Delete security programs, in order.
     *
     * @return the ordered list of the four populated admin options
     */
    private static List<AdminMenuOption> seededAdminOptions() {
        return List.of(
                new AdminMenuOption(1, "User List (Security)", "COUSR00C"),
                new AdminMenuOption(2, "User Add (Security)", "COUSR01C"),
                new AdminMenuOption(3, "User Update (Security)", "COUSR02C"),
                new AdminMenuOption(4, "User Delete (Security)", "COUSR03C"));
    }

    /**
     * Builds a real {@link COADM01Form} fixture carrying the supplied {@code OPTION} field value,
     * mirroring the 3270 map {@code COADM1A} the COBOL program receives.
     *
     * @param option the raw option-input value (may be {@code null})
     * @return a form whose {@code OPTION} field is set to {@code option}
     */
    private static COADM01Form formWithOption(String option) {
        COADM01Form form = new COADM01Form();
        form.setOption(option);
        return form;
    }

    // ------------------------------------------------------------------------------------------
    // buildMenuOptions - BUILD-MENU-OPTIONS (parity item 1: exactly four options)
    // ------------------------------------------------------------------------------------------

    /**
     * {@code BUILD-MENU-OPTIONS} renders exactly four numbered {@code "NN. Name"} lines, one per
     * populated option, preserving the COBOL two-digit numbering and display labels.
     */
    @Test
    void buildMenuOptions_rendersFourNumberedOptionLines() {
        when(adminMenuOptions.getOptions()).thenReturn(seededAdminOptions());

        List<String> lines = service.buildMenuOptions();

        assertThat(lines)
                .hasSize(4)
                .containsExactly(
                        "01. User List (Security)",
                        "02. User Add (Security)",
                        "03. User Update (Security)",
                        "04. User Delete (Security)");
    }

    // ------------------------------------------------------------------------------------------
    // processEnterKey - PROCESS-ENTER-KEY (parity item 2: valid-option routing)
    // ------------------------------------------------------------------------------------------

    /**
     * Each of the four valid options routes to its user-administration program: option 1 &rarr;
     * {@code COUSR00C}, 2 &rarr; {@code COUSR01C}, 3 &rarr; {@code COUSR02C}, 4 &rarr;
     * {@code COUSR03C}. The result is a redirect (COBOL {@code XCTL}) to that program, and the
     * hand-off origin fields ({@code CDEMO-FROM-TRANID = 'CA00'},
     * {@code CDEMO-FROM-PROGRAM = 'COADM01C'}, {@code CDEMO-PGM-CONTEXT = 0}) are written to the
     * context.
     *
     * @param optionNumber    the entered option number (1-4)
     * @param expectedProgram the program the option must route to
     */
    @ParameterizedTest(name = "option {0} routes to {1}")
    @CsvSource({
        "1,COUSR00C",
        "2,COUSR01C",
        "3,COUSR02C",
        "4,COUSR03C"
    })
    void processEnterKey_withValidOption_routesToUserAdminProgram(int optionNumber, String expectedProgram) {
        when(adminMenuOptions.getOptionCount()).thenReturn(4);
        when(adminMenuOptions.getOptions()).thenReturn(seededAdminOptions());

        AdminMenuService.AdminMenuResult result =
                service.processEnterKey(formWithOption(Integer.toString(optionNumber)), context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(expectedProgram);
        assertThat(result.hasMessage()).isFalse();
        assertThat(result.error()).isFalse();

        verify(context).setFromTranid(ADMIN_TRANID);
        verify(context).setFromProgram(ADMIN_PROGRAM);
        verify(context).markEnter();
        verifyNoMoreInteractions(context);
    }

    /**
     * A valid option whose target program is a {@code 'DUMMY'} placeholder yields the neutral
     * "coming soon" message (COBOL green {@code DFHGREEN} line) rather than a redirect, and performs
     * no context hand-off.
     */
    @Test
    void processEnterKey_withPlaceholderProgram_returnsComingSoonMessage() {
        when(adminMenuOptions.getOptionCount()).thenReturn(1);
        when(adminMenuOptions.getOptions())
                .thenReturn(List.of(new AdminMenuOption(1, "Coming Soon", "DUMMY001")));

        AdminMenuService.AdminMenuResult result =
                service.processEnterKey(formWithOption("1"), context);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.targetProgram()).isNull();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_COMING_SOON);
        assertThat(result.error()).isFalse();
        verifyNoInteractions(context);
    }

    // ------------------------------------------------------------------------------------------
    // processEnterKey - PROCESS-ENTER-KEY (parity item 3: invalid option -> message, no routing)
    // ------------------------------------------------------------------------------------------

    /**
     * A zero option (COBOL {@code WS-OPTION = ZEROS}) is rejected with the invalid-option message
     * and performs no routing. The zero test short-circuits before the option table is consulted,
     * so neither the context nor the option table is touched.
     */
    @Test
    void processEnterKey_withZeroOption_returnsInvalidOptionMessage() {
        AdminMenuService.AdminMenuResult result =
                service.processEnterKey(formWithOption("0"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_OPTION);
        assertThat(result.isRedirect()).isFalse();
        assertThat(result.targetProgram()).isNull();
        verifyNoInteractions(context);
        verifyNoInteractions(adminMenuOptions);
    }

    /**
     * A non-numeric option (COBOL {@code WS-OPTION IS NOT NUMERIC}) is rejected with the
     * invalid-option message. The numeric class test fails before the option table is consulted.
     *
     * <p>Both halves of the class test are exercised: {@code "A"} normalizes to {@code "0A"} (a
     * character above {@code '9'}), and {@code "1."} stays {@code "1."} (a character below
     * {@code '0'}); either makes the option non-numeric.</p>
     *
     * @param rawOption a raw option-input value that normalizes to a non-numeric field
     */
    @ParameterizedTest(name = "non-numeric option \"{0}\" is rejected")
    @ValueSource(strings = {"A", "1."})
    void processEnterKey_withNonNumericOption_returnsInvalidOptionMessage(String rawOption) {
        AdminMenuService.AdminMenuResult result =
                service.processEnterKey(formWithOption(rawOption), context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_OPTION);
        assertThat(result.isRedirect()).isFalse();
        verifyNoInteractions(context);
        verifyNoInteractions(adminMenuOptions);
    }

    /**
     * An option greater than the populated count (COBOL {@code WS-OPTION > CDEMO-ADMIN-OPT-COUNT})
     * is rejected with the invalid-option message; the range check consults only the option count,
     * never the option table itself, and performs no routing.
     */
    @Test
    void processEnterKey_withOptionGreaterThanCount_returnsInvalidOptionMessage() {
        when(adminMenuOptions.getOptionCount()).thenReturn(4);

        AdminMenuService.AdminMenuResult result =
                service.processEnterKey(formWithOption("5"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_OPTION);
        assertThat(result.isRedirect()).isFalse();
        verify(adminMenuOptions).getOptionCount();
        verifyNoMoreInteractions(adminMenuOptions);
        verifyNoInteractions(context);
    }

    /**
     * Defensive parity for a count/table mismatch: when the declared option count
     * ({@code CDEMO-ADMIN-OPT-COUNT}) exceeds the number of populated option rows, an option that
     * passes the count check but exceeds the table's bounds degrades to the invalid-option message
     * rather than throwing an index error &mdash; matching the COBOL bounds-safe table access. Here
     * the count is stubbed to 5 while the table holds only the four seeded rows, and option 5 is
     * entered.
     */
    @Test
    void processEnterKey_whenOptionCountExceedsTableSize_returnsInvalidOptionMessage() {
        when(adminMenuOptions.getOptionCount()).thenReturn(5);
        when(adminMenuOptions.getOptions()).thenReturn(seededAdminOptions());

        AdminMenuService.AdminMenuResult result =
                service.processEnterKey(formWithOption("5"), context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_OPTION);
        assertThat(result.isRedirect()).isFalse();
        verifyNoInteractions(context);
    }

    // ------------------------------------------------------------------------------------------
    // processEnterKey - PROCESS-ENTER-KEY (parity item 4: space->zero normalization)
    // ------------------------------------------------------------------------------------------

    /**
     * A blank option normalizes to {@code "00"} via the COBOL
     * {@code INSPECT ... REPLACING ALL ' ' BY '0'}, which then fails the {@code WS-OPTION = ZEROS}
     * test and yields the invalid-option message. This exercises the space&rarr;zero normalization
     * feeding the invalid-option branch.
     */
    @Test
    void processEnterKey_withBlankOption_normalizesToZeroAndReturnsInvalidOption() {
        AdminMenuService.AdminMenuResult result =
                service.processEnterKey(formWithOption("  "), context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_OPTION);
        assertThat(result.isRedirect()).isFalse();
        verifyNoInteractions(context);
        verifyNoInteractions(adminMenuOptions);
    }

    /**
     * A space-padded option ({@code " 1"}) is right-justified and space&rarr;zero normalized to
     * {@code "01"} (COBOL {@code PIC X(02) JUST RIGHT} plus {@code INSPECT}), then routes to
     * {@code COUSR00C} exactly like the un-padded {@code "1"}. This proves the normalization
     * produces a valid selection rather than being rejected.
     */
    @Test
    void processEnterKey_withSpacePaddedOption_normalizesAndRoutes() {
        when(adminMenuOptions.getOptionCount()).thenReturn(4);
        when(adminMenuOptions.getOptions()).thenReturn(seededAdminOptions());

        AdminMenuService.AdminMenuResult result =
                service.processEnterKey(formWithOption(" 1"), context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo("COUSR00C");
        verify(context).setFromTranid(ADMIN_TRANID);
        verify(context).setFromProgram(ADMIN_PROGRAM);
        verify(context).markEnter();
        verifyNoMoreInteractions(context);
    }

    /**
     * A {@code null} form is treated as a blank option (normalized to {@code "00"}) and yields the
     * invalid-option message, guarding the receive-map-absent edge without touching the option
     * table or context.
     */
    @Test
    void processEnterKey_withNullForm_returnsInvalidOptionMessage() {
        AdminMenuService.AdminMenuResult result = service.processEnterKey(null, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_OPTION);
        assertThat(result.isRedirect()).isFalse();
        verifyNoInteractions(context);
        verifyNoInteractions(adminMenuOptions);
    }

    // ------------------------------------------------------------------------------------------
    // mainEntry - MAIN-PARA (EVALUATE EIBAID dispatch)
    // ------------------------------------------------------------------------------------------

    /**
     * On re-entry with {@code DFHENTER}, {@code MAIN-PARA} performs {@code PROCESS-ENTER-KEY}: a
     * valid option (here option 1) routes to {@code COUSR00C} and writes the hand-off origin fields.
     * This confirms the {@code EVALUATE EIBAID WHEN DFHENTER} dispatch delegates to the enter-key
     * handler.
     */
    @Test
    void mainEntry_withEnterKey_selectsOptionAndRoutes() {
        when(context.isNew()).thenReturn(false);
        when(adminMenuOptions.getOptionCount()).thenReturn(4);
        when(adminMenuOptions.getOptions()).thenReturn(seededAdminOptions());

        AdminMenuService.AdminMenuResult result =
                service.mainEntry(AdminMenuService.AidKey.ENTER, formWithOption("1"));

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo("COUSR00C");
        verify(context).isNew();
        verify(context).setFromTranid(ADMIN_TRANID);
        verify(context).setFromProgram(ADMIN_PROGRAM);
        verify(context).markEnter();
        verifyNoMoreInteractions(context);
    }

    /**
     * On re-entry with {@code DFHPF3}, {@code MAIN-PARA} moves {@code 'COSGN00C'} to
     * {@code CDEMO-TO-PROGRAM} and performs {@code RETURN-TO-SIGNON-SCREEN}, producing a redirect
     * back to the sign-on program (parity item 5).
     */
    @Test
    void mainEntry_withPf3Key_returnsToSignonScreen() {
        when(context.isNew()).thenReturn(false);
        when(context.getToProgram()).thenReturn(SIGNON_PROGRAM);

        AdminMenuService.AdminMenuResult result =
                service.mainEntry(AdminMenuService.AidKey.PF3, formWithOption(null));

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        verify(context).isNew();
        verify(context).setToProgram(SIGNON_PROGRAM);
    }

    /**
     * On re-entry with an unmapped AID key, {@code MAIN-PARA}'s {@code WHEN OTHER} branch yields the
     * invalid-key message ({@code CCDA-MSG-INVALID-KEY}) and performs no routing.
     */
    @Test
    void mainEntry_withUnmappedKey_returnsInvalidKeyMessage() {
        when(context.isNew()).thenReturn(false);

        AdminMenuService.AdminMenuResult result =
                service.mainEntry(AdminMenuService.AidKey.OTHER, formWithOption("1"));

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        assertThat(result.isRedirect()).isFalse();
        verify(context).isNew();
        verifyNoMoreInteractions(context);
    }

    /**
     * A {@code null} AID key collapses to the {@code WHEN OTHER} default, matching the COBOL
     * fall-through, and likewise yields the invalid-key message with no routing.
     */
    @Test
    void mainEntry_withNullKey_treatedAsOtherReturnsInvalidKey() {
        when(context.isNew()).thenReturn(false);

        AdminMenuService.AdminMenuResult result = service.mainEntry(null, formWithOption("1"));

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        assertThat(result.isRedirect()).isFalse();
        verify(context).isNew();
        verifyNoMoreInteractions(context);
    }

    /**
     * On first entry into the transaction (COBOL {@code EIBCALEN = 0}, reproduced by
     * {@link CardDemoContext#isNew()}), {@code MAIN-PARA} records the sign-on program as the origin
     * and performs {@code RETURN-TO-SIGNON-SCREEN}, which defaults the unset hand-off target to
     * {@code COSGN00C} and redirects there (parity item 6).
     */
    @Test
    void mainEntry_firstEntry_returnsToSignonScreen() {
        when(context.isNew()).thenReturn(true);
        // Reproduce the write-then-read of a fresh context: CDEMO-TO-PROGRAM starts unset (null),
        // is defaulted to COSGN00C by RETURN-TO-SIGNON-SCREEN, then read back for the redirect.
        when(context.getToProgram()).thenReturn(null, SIGNON_PROGRAM);

        AdminMenuService.AdminMenuResult result =
                service.mainEntry(AdminMenuService.AidKey.ENTER, formWithOption("1"));

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        verify(context).setFromProgram(SIGNON_PROGRAM);
        verify(context).setToProgram(SIGNON_PROGRAM);
    }

    // ------------------------------------------------------------------------------------------
    // returnToSignonScreen - RETURN-TO-SIGNON-SCREEN
    // ------------------------------------------------------------------------------------------

    /**
     * When {@code CDEMO-TO-PROGRAM} is already set, {@code RETURN-TO-SIGNON-SCREEN} honors it and
     * redirects there without applying the sign-on default &mdash; the COBOL
     * {@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES} guard is false.
     */
    @Test
    void returnToSignonScreen_whenTargetAlreadySet_redirectsToThatProgram() {
        when(context.getToProgram()).thenReturn("COMEN01C");

        AdminMenuService.AdminMenuResult result = service.returnToSignonScreen(context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo("COMEN01C");
    }

    /**
     * When {@code CDEMO-TO-PROGRAM} is blank, {@code RETURN-TO-SIGNON-SCREEN} defaults it to
     * {@code COSGN00C} (COBOL {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}) and redirects there.
     */
    @Test
    void returnToSignonScreen_whenTargetBlank_defaultsToSignonProgram() {
        // First read returns a blank (whitespace) target to exercise the isBlank() default branch;
        // the second read returns the value the service just wrote back.
        when(context.getToProgram()).thenReturn("   ", SIGNON_PROGRAM);

        AdminMenuService.AdminMenuResult result = service.returnToSignonScreen(context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        verify(context).setToProgram(SIGNON_PROGRAM);
    }
}
