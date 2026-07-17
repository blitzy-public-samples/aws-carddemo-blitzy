/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

/**
 * Menu-option routing service &mdash; the Java re-platform of the two CardDemo
 * COBOL <em>menu</em> programs {@code COMEN01C} (Main Menu, CICS transaction
 * {@code CM00}) and {@code COADM01C} (Admin Menu). It reproduces, without any
 * feature expansion, the option catalogs and the option-selection routing logic
 * those programs implemented on the 3270/CICS platform.
 *
 * <h2>Source of truth</h2>
 * <ul>
 *   <li><strong>Main-menu catalog</strong> &rarr; copybook {@code COMEN02Y}
 *       ({@code CDEMO-MENU-OPT-COUNT = 10}); ten options, every one flagged
 *       user-type {@code 'U'} (regular user) in {@code COMEN02Y}, hence
 *       {@code adminOnly = false} for all ten. Modeled by {@link #getMainMenu()}.</li>
 *   <li><strong>Admin-menu catalog</strong> &rarr; copybook {@code COADM02Y}
 *       ({@code CDEMO-ADMIN-OPT-COUNT = 4}); four security/user-administration
 *       options, all admin-only. Modeled by {@link #getAdminMenu()}.</li>
 *   <li><strong>User-type condition names</strong> &rarr; copybook
 *       {@code COCOM01Y} 88-levels {@code CDEMO-USRTYP-ADMIN VALUE 'A'} and
 *       {@code CDEMO-USRTYP-USER VALUE 'U'}.</li>
 * </ul>
 *
 * <h2>Pseudo-conversational translation</h2>
 * The legacy programs are pseudo-conversational: paragraph {@code PROCESS-ENTER-KEY}
 * reads the option the operator typed, validates it, and either
 * {@code EXEC CICS XCTL PROGRAM(...)} to the target program or re-displays the
 * menu with a message. There is no terminal in the re-platformed application, so
 * the {@code XCTL} navigation and the "re-display with a screen message" outcomes
 * are surfaced as a plain {@link MenuRouting} value object rather than a screen
 * send: a successful selection yields the {@link MenuRouting#route(String) route}
 * to the target program name, while every non-navigating outcome (invalid option,
 * admin-only denial, or a not-yet-implemented target) yields a
 * {@link MenuRouting#error(String) message} carrying the exact caller-visible text
 * the COBOL placed in {@code WS-MESSAGE}. Parse failures are <em>never</em> thrown
 * as exceptions &mdash; the COBOL sets a message and re-displays, so this service
 * returns the message via {@link MenuRouting} to preserve that behavior.
 *
 * <h2>Routing rules (evaluation order preserved verbatim)</h2>
 * Reproduced from {@code COMEN01C} {@code PROCESS-ENTER-KEY} (L127-L165) and
 * {@code COADM01C} {@code PROCESS-ENTER-KEY} (L127-L155):
 * <ol>
 *   <li><strong>Numeric + range.</strong> The option must be numeric and within
 *       {@code 1..count} (COBOL {@code IF WS-OPTION IS NOT NUMERIC OR
 *       WS-OPTION > CDEMO-*-OPT-COUNT OR WS-OPTION = ZEROS}); otherwise the
 *       message is {@link #INVALID_OPTION_MESSAGE}.</li>
 *   <li><strong>Admin-only gate</strong> ({@code COMEN01C} L136-L143 only). On the
 *       main (user) menu, if the signed-in user is a regular user ({@code 'U'})
 *       and the selected option is flagged admin-only ({@code 'A'}), the message is
 *       {@link #NO_ACCESS_ADMIN_ONLY_MESSAGE}. {@code COADM01C} has no such gate.</li>
 *   <li><strong>Navigate.</strong> If the option is valid and its target program is
 *       real (COBOL {@code IF CDEMO-*-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'}),
 *       the result is a navigation to that program name (the legacy
 *       {@code XCTL PROGRAM(...)}).</li>
 *   <li><strong>Coming soon.</strong> If the option is valid but maps to a
 *       placeholder/unimplemented target (program name beginning {@code 'DUMMY'}),
 *       the message is {@code "This option <name> is coming soon ..."} with the
 *       option's display name substituted.</li>
 * </ol>
 *
 * <h2>Parity notes</h2>
 * With the real catalogs the admin-only gate (rule&nbsp;2) and the coming-soon
 * branch (rule&nbsp;4) are never reached &mdash; {@code COMEN02Y} flags all ten
 * options {@code 'U'} and no catalog target is a {@code 'DUMMY'} placeholder &mdash;
 * yet both branches are implemented in full to faithfully mirror the COBOL control
 * flow. The numeric/range check short-circuits before the admin-only gate so an
 * out-of-range option can never index the catalog (the legacy code continued into
 * the subsequent {@code IF} statements, which would have indexed the option table
 * with an out-of-range subscript; the caller-visible outcome &mdash; the
 * "Please enter a valid option number..." message &mdash; is preserved exactly).
 *
 * <h2>Design constraints</h2>
 * The catalogs are immutable in-memory constants (no database, no repository, no
 * injected dependency); the caller-visible message strings are behavioral-parity
 * contracts and must not be paraphrased. All monetary handling is out of scope for
 * this service (menus carry no money).
 */
@Service
public class MenuService {

    /**
     * Exact caller-visible message for a non-numeric or out-of-range option.
     * COBOL literal in {@code COMEN01C} L131 and {@code COADM01C} L131:
     * {@code 'Please enter a valid option number...'} (three trailing dots).
     */
    public static final String INVALID_OPTION_MESSAGE = "Please enter a valid option number...";

    /**
     * Exact caller-visible message shown when a regular user selects an admin-only
     * option on the main menu. COBOL literal in {@code COMEN01C} L140-L141:
     * {@code 'No access - Admin Only option... '} (three dots and a single trailing
     * space, both significant).
     */
    public static final String NO_ACCESS_ADMIN_ONLY_MESSAGE = "No access - Admin Only option... ";

    /**
     * Leading fragment of the "coming soon" message assembled for a placeholder
     * target. Mirrors the COBOL {@code STRING 'This option ' ...} literal
     * ({@code COMEN01C} L159).
     */
    private static final String COMING_SOON_PREFIX = "This option ";

    /**
     * Trailing fragment of the "coming soon" message. Mirrors the COBOL
     * {@code ... 'is coming soon ...'} literal ({@code COMEN01C} L162), rendered
     * with a single separating space and three trailing dots.
     */
    private static final String COMING_SOON_SUFFIX = " is coming soon ...";

    /**
     * Program-name prefix that marks a placeholder/unimplemented target. COBOL
     * tests the first five characters: {@code IF CDEMO-*-OPT-PGMNAME(...)(1:5)
     * NOT = 'DUMMY'} ({@code COMEN01C} L146, {@code COADM01C} L138).
     */
    private static final String PLACEHOLDER_PROGRAM_PREFIX = "DUMMY";

    /**
     * User-type code for an administrator. COBOL {@code COCOM01Y} 88-level
     * {@code CDEMO-USRTYP-ADMIN VALUE 'A'}.
     */
    private static final char USER_TYPE_ADMIN = 'A';

    /**
     * User-type code for a regular user. COBOL {@code COCOM01Y} 88-level
     * {@code CDEMO-USRTYP-USER VALUE 'U'}.
     */
    private static final char USER_TYPE_USER = 'U';

    /**
     * Immutable main-menu catalog derived one-for-one from copybook
     * {@code COMEN02Y}. Ten options in the exact order, with the exact option
     * numbers, display names, and target program names of the copybook; every
     * option carries user-type {@code 'U'} in {@code COMEN02Y} and is therefore
     * {@code adminOnly = false}.
     */
    private static final List<MenuOption> MAIN_MENU = List.of(
            new MenuOption(1, "Account View", "COACTVWC", false),
            new MenuOption(2, "Account Update", "COACTUPC", false),
            new MenuOption(3, "Credit Card List", "COCRDLIC", false),
            new MenuOption(4, "Credit Card View", "COCRDSLC", false),
            new MenuOption(5, "Credit Card Update", "COCRDUPC", false),
            new MenuOption(6, "Transaction List", "COTRN00C", false),
            new MenuOption(7, "Transaction View", "COTRN01C", false),
            new MenuOption(8, "Transaction Add", "COTRN02C", false),
            new MenuOption(9, "Transaction Reports", "CORPT00C", false),
            new MenuOption(10, "Bill Payment", "COBIL00C", false));

    /**
     * Immutable admin-menu catalog derived one-for-one from copybook
     * {@code COADM02Y}. Four security/user-administration options in the exact
     * order, with the exact option numbers, display names, and target program
     * names of the copybook; all are admin-only ({@code adminOnly = true}).
     */
    private static final List<MenuOption> ADMIN_MENU = List.of(
            new MenuOption(1, "User List (Security)", "COUSR00C", true),
            new MenuOption(2, "User Add (Security)", "COUSR01C", true),
            new MenuOption(3, "User Update (Security)", "COUSR02C", true),
            new MenuOption(4, "User Delete (Security)", "COUSR03C", true));

    /**
     * A single immutable menu entry &mdash; the Java re-platform of one occurrence
     * of the COBOL option table ({@code CDEMO-MENU-OPT} in {@code COMEN02Y} /
     * {@code CDEMO-ADMIN-OPT} in {@code COADM02Y}).
     *
     * @param number        the 1-based option number ({@code PIC 9(02)})
     * @param name          the display name ({@code PIC X(35)}, trailing blanks trimmed)
     * @param targetProgram the target program the option navigates to
     *                      ({@code PIC X(08)}; the legacy {@code XCTL} target)
     * @param adminOnly     {@code true} when the option is restricted to
     *                      administrators (COBOL user-type flag {@code 'A'});
     *                      {@code false} for a regular-user option ({@code 'U'})
     */
    public record MenuOption(int number, String name, String targetProgram, boolean adminOnly) {

        /**
         * Canonical constructor with defensive validation. {@code name} and
         * {@code targetProgram} are required (the COBOL table fields are
         * fixed-length and never absent).
         */
        public MenuOption {
            Objects.requireNonNull(name, "menu option name must not be null");
            Objects.requireNonNull(targetProgram, "menu option targetProgram must not be null");
        }
    }

    /**
     * Outcome of an option selection &mdash; the transport-neutral replacement for
     * the COBOL {@code XCTL}-or-redisplay decision.
     *
     * <p>Exactly one of the two shapes is produced: a successful navigation carries
     * a non-null {@code targetProgram} and a {@code null} {@code message}; a
     * non-navigating outcome carries a {@code null} {@code targetProgram} and a
     * non-null {@code message} holding the exact caller-visible text the COBOL
     * placed in {@code WS-MESSAGE}.
     *
     * @param success       {@code true} for a navigation, {@code false} for a
     *                      message-only outcome
     * @param targetProgram the target program name to navigate to when
     *                      {@code success} is {@code true}; otherwise {@code null}
     * @param message       the caller-visible message when {@code success} is
     *                      {@code false}; otherwise {@code null}
     */
    public record MenuRouting(boolean success, String targetProgram, String message) {

        /**
         * Builds a successful navigation outcome (the legacy
         * {@code XCTL PROGRAM(targetProgram)}).
         *
         * @param targetProgram the non-null target program name
         * @return a {@code MenuRouting} with {@code success == true}
         */
        public static MenuRouting route(String targetProgram) {
            return new MenuRouting(true,
                    Objects.requireNonNull(targetProgram, "targetProgram must not be null"), null);
        }

        /**
         * Builds a message-only (non-navigating) outcome, mirroring the COBOL
         * "set {@code WS-MESSAGE} and re-display the menu" behavior.
         *
         * @param message the non-null caller-visible message
         * @return a {@code MenuRouting} with {@code success == false}
         */
        public static MenuRouting error(String message) {
            return new MenuRouting(false, null,
                    Objects.requireNonNull(message, "message must not be null"));
        }
    }

    /**
     * Returns the immutable main-menu catalog (copybook {@code COMEN02Y}, driven by
     * {@code COMEN01C}). The returned list is unmodifiable; mutation attempts throw
     * {@link UnsupportedOperationException}.
     *
     * @return the ten main-menu options in copybook order
     */
    public List<MenuOption> getMainMenu() {
        return MAIN_MENU;
    }

    /**
     * Returns the immutable admin-menu catalog (copybook {@code COADM02Y}, driven by
     * {@code COADM01C}). The returned list is unmodifiable; mutation attempts throw
     * {@link UnsupportedOperationException}.
     *
     * @return the four admin-menu options in copybook order
     */
    public List<MenuOption> getAdminMenu() {
        return ADMIN_MENU;
    }

    /**
     * Formats an option as the {@code "NN. Name"} label the COBOL built in
     * {@code BUILD-MENU-OPTIONS} ({@code COMEN01C} L243-L246, {@code COADM01C}
     * L233-L236) using {@code STRING opt-num '. ' opt-name}. The option number is
     * zero-padded to two digits to match the {@code PIC 9(02)} display.
     *
     * @param option the option to format (must not be {@code null})
     * @return the display label, e.g. {@code "01. Account View"}
     */
    public String formatLabel(MenuOption option) {
        Objects.requireNonNull(option, "option must not be null");
        return String.format("%02d. %s", option.number(), option.name());
    }

    /**
     * Reproduces the main-menu option routing of {@code COMEN01C}
     * ({@code PROCESS-ENTER-KEY}, L115-L165). Applies, in order, the numeric/range
     * check (rule&nbsp;1), the admin-only gate for regular users (rule&nbsp;2), and
     * then either navigation to a real target program (rule&nbsp;3) or the
     * "coming soon" message for a placeholder target (rule&nbsp;4).
     *
     * @param rawOption the raw option text the operator entered (the legacy
     *                  {@code OPTIONI} map field); {@code null}, blank, or
     *                  non-numeric input yields {@link #INVALID_OPTION_MESSAGE}
     *                  rather than an exception
     * @param userType  the signed-in user's type code ({@code 'A'} administrator or
     *                  {@code 'U'} regular user; COBOL {@code CDEMO-USER-TYPE})
     * @return the routing outcome
     */
    public MenuRouting selectMainMenuOption(String rawOption, char userType) {
        return evaluateSelection(rawOption, MAIN_MENU, userType);
    }

    /**
     * Reproduces the admin-menu option routing of {@code COADM01C}
     * ({@code PROCESS-ENTER-KEY}, L115-L155). Applies the numeric/range check
     * (rule&nbsp;1) and then either navigation to a real target program
     * (rule&nbsp;3) or the "coming soon" message for a placeholder target
     * (rule&nbsp;4). {@code COADM01C} has no admin-only gate, so the selection is
     * evaluated in an administrator context and rule&nbsp;2 never fires.
     *
     * @param rawOption the raw option text the operator entered (the legacy
     *                  {@code OPTIONI} map field); {@code null}, blank, or
     *                  non-numeric input yields {@link #INVALID_OPTION_MESSAGE}
     *                  rather than an exception
     * @return the routing outcome
     */
    public MenuRouting selectAdminMenuOption(String rawOption) {
        return evaluateSelection(rawOption, ADMIN_MENU, USER_TYPE_ADMIN);
    }

    /**
     * Shared selection engine for both menus, preserving the COBOL evaluation
     * order. Extracted so {@link #selectMainMenuOption(String, char)} and
     * {@link #selectAdminMenuOption(String)} apply identical rules; the admin menu
     * passes {@link #USER_TYPE_ADMIN} so the admin-only gate (rule&nbsp;2) is a
     * no-op there, exactly as {@code COADM01C} omits the gate.
     *
     * @param rawOption the raw option text
     * @param catalog   the catalog to select from ({@link #MAIN_MENU} or
     *                  {@link #ADMIN_MENU})
     * @param userType  the effective user-type code driving the admin-only gate
     * @return the routing outcome
     */
    private MenuRouting evaluateSelection(String rawOption, List<MenuOption> catalog, char userType) {
        // Rule 1 - numeric and within 1..count, else "Please enter a valid option number...".
        // Short-circuits so an out-of-range value can never index the catalog.
        Integer parsed = parseOptionNumber(rawOption);
        if (parsed == null || parsed < 1 || parsed > catalog.size()) {
            return MenuRouting.error(INVALID_OPTION_MESSAGE);
        }

        MenuOption option = catalog.get(parsed - 1);

        // Rule 2 - admin-only gate: a regular user ('U') may not select an admin-only option.
        if (userType == USER_TYPE_USER && option.adminOnly()) {
            return MenuRouting.error(NO_ACCESS_ADMIN_ONLY_MESSAGE);
        }

        // Rules 3 & 4 - navigate to a real target, or report a placeholder as "coming soon".
        if (isPlaceholderTarget(option.targetProgram())) {
            return MenuRouting.error(comingSoonMessage(option.name()));
        }
        return MenuRouting.route(option.targetProgram());
    }

    /**
     * Parses the raw option text the way COBOL's {@code IF WS-OPTION IS NOT NUMERIC}
     * test does: a value is accepted only when it is non-blank and composed solely
     * of ASCII digits {@code '0'..'9'}. {@code null}, blank, non-numeric, and
     * numeric-but-overflowing input all return {@code null} (treated as invalid by
     * the caller) instead of raising an exception, mirroring the legacy behavior of
     * setting an error message rather than abending on bad operator input.
     *
     * @param rawOption the raw option text
     * @return the parsed option number, or {@code null} when the input is not a
     *         valid non-negative integer
     */
    private static Integer parseOptionNumber(String rawOption) {
        if (rawOption == null) {
            return null;
        }
        String trimmed = rawOption.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException ex) {
            // All-digit but larger than Integer.MAX_VALUE: definitely out of any menu range.
            return null;
        }
    }

    /**
     * Tests whether a target program name is a placeholder/unimplemented target,
     * reproducing the COBOL {@code IF ...-OPT-PGMNAME(...)(1:5) = 'DUMMY'} check
     * (first five characters equal {@code DUMMY}).
     *
     * @param targetProgram the target program name
     * @return {@code true} when the name begins with {@code DUMMY}
     */
    private static boolean isPlaceholderTarget(String targetProgram) {
        return targetProgram != null && targetProgram.startsWith(PLACEHOLDER_PROGRAM_PREFIX);
    }

    /**
     * Assembles the "coming soon" message for a placeholder option, substituting the
     * option's display name: {@code "This option <name> is coming soon ..."}.
     *
     * @param optionName the option's display name
     * @return the assembled caller-visible message
     */
    private static String comingSoonMessage(String optionName) {
        return COMING_SOON_PREFIX + optionName + COMING_SOON_SUFFIX;
    }
}
