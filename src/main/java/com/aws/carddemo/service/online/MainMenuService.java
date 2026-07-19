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

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.menu.MainMenuOptions;
import com.aws.carddemo.dto.menu.MainMenuOptions.MainMenuOption;
import com.aws.carddemo.dto.screen.COMEN01Form;
import com.aws.carddemo.util.constants.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Regular-user main-menu service.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COMEN01C.cbl} (CICS COBOL program
 * {@code COMEN01C}, transaction id {@code CM00}). This class is the faithful Java
 * migration of the online main menu for regular (non-admin) users, preserving the
 * COBOL control flow one-for-one: each business {@code PARAGRAPH} becomes exactly
 * one method (AAP &sect;0.3.3, service layer; AAP &sect;0.4.1, online-programs
 * table {@code COMEN01C.cbl -> MainMenuService + MenuController}).</p>
 *
 * <h2>Menu archetype and the presentation split</h2>
 * <p>{@code COMEN01C} is the <em>menu</em> archetype of the AWS CardDemo online
 * tier. Its seven paragraphs divide cleanly into business logic (kept here) and
 * 3270/BMS presentation (owned by the paired {@code MenuController} and the
 * Thymeleaf view). This service therefore implements only the four business
 * paragraphs and deliberately contains no {@code SEND}/{@code RECEIVE}/header
 * logic:</p>
 * <table border="1">
 *   <caption>COMEN01C paragraph &rarr; Java mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Owner</th><th>Java member</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>service</td><td>{@link #mainEntry(COMEN01Form, PfKey)}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>service</td>
 *       <td>{@link #processEnterKey(COMEN01Form, CardDemoContext)}</td></tr>
 *   <tr><td>{@code RETURN-TO-SIGNON-SCREEN}</td><td>service</td>
 *       <td>{@link #returnToSignonScreen(CardDemoContext)}</td></tr>
 *   <tr><td>{@code BUILD-MENU-OPTIONS}</td><td>service</td><td>{@link #buildMenuOptions()}</td></tr>
 *   <tr><td>{@code SEND-MENU-SCREEN}</td><td>controller</td><td>&mdash; (view render)</td></tr>
 *   <tr><td>{@code RECEIVE-MENU-SCREEN}</td><td>controller</td><td>&mdash; (form binding)</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>controller</td><td>&mdash; (header fields)</td></tr>
 * </table>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>{@code COMEN01C} is pseudo-conversational: every interaction ends with
 * {@code EXEC CICS RETURN TRANSID(CM00) COMMAREA(...)}, and re-entry is driven by
 * the {@code COMMAREA} plus {@code EIBCALEN} and {@code EIBAID}. That state carrier
 * is the session-scoped {@link CardDemoContext}. {@code EIBCALEN = 0} (no COMMAREA)
 * maps to {@link CardDemoContext#isNew()}; the within-program enter/re-enter flag
 * {@code CDEMO-PGM-CONTEXT} maps to {@link CardDemoContext#isProgramEnter()} /
 * {@link CardDemoContext#markReenter()}. The controller performs the
 * {@code RECEIVE} (form binding) and the final {@code RETURN}/redirect; this
 * service only decides <em>what</em> should happen and records the routing target
 * in the context.</p>
 *
 * <h2>Result contract</h2>
 * <p>Because presentation is externalized, each business method returns a
 * {@link MainMenuResult} describing the outcome: either
 * {@link RoutingAction#REDIRECT} (the COBOL {@code XCTL}, with the target program
 * recorded in {@link CardDemoContext#getToProgram()}) or
 * {@link RoutingAction#SHOW_MENU} (the COBOL {@code SEND-MENU-SCREEN}), together
 * with an optional message and its {@link MessageSeverity}
 * ({@link MessageSeverity#ERROR} = the red {@code WS-ERR-FLG} path,
 * {@link MessageSeverity#INFORMATION} = the green {@code DFHGREEN} "coming soon"
 * path). The controller renders the menu (calling {@link #buildMenuOptions()}) or
 * issues the redirect accordingly.</p>
 *
 * <h2>Collaborators and dependencies</h2>
 * <p>Per the migration plan this service constructor-injects two {@code private
 * final} collaborators and uses <b>no repositories</b> (the menu program performs
 * no file I/O): the session-scoped {@link CardDemoContext} (the {@code COMMAREA}
 * replacement) and {@link MainMenuOptions} (the migrated {@code COMEN02Y} option
 * table). Beyond its declared dependencies it also references the shared
 * {@link PfKey} enumeration (the canonical {@code EIBAID} representation used
 * across the online tier) and the {@link Messages} constants holder for the
 * {@code CCDA-MSG-INVALID-KEY} literal, exactly as directed by the file
 * specification.</p>
 *
 * <p>Plain Java, explicit accessors, no Lombok, no emoji; compiles warning-free
 * under {@code --release 25} with {@code -Xlint:all}.</p>
 *
 * @see CardDemoContext
 * @see MainMenuOptions
 * @see COMEN01Form
 */
@Service
public class MainMenuService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} - this program's name. */
    private static final String PROGRAM_NAME = "COMEN01C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CM00'} - this program's transaction id. */
    private static final String TRANSACTION_ID = "CM00";

    /** Sign-on program name ({@code COSGN00C}); COBOL {@code XCTL} target on first entry and PF3. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Sign-on transaction id ({@code CC00}); recorded for the controller redirect to sign-on. */
    private static final String SIGNON_TRANSACTION_ID = "CC00";

    /**
     * Placeholder program-name prefix. COBOL guards routing with
     * {@code IF CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'}; an option
     * whose target program name begins with {@code DUMMY} is not yet implemented
     * and yields the "coming soon" message instead of an {@code XCTL}.
     */
    private static final String DUMMY_PROGRAM_PREFIX = "DUMMY";

    /**
     * Width of the BMS {@code OPTION} field ({@code PIC X(02)}), matching
     * {@code COMEN01Form.option}. The COBOL option parse works against this fixed
     * two-character field ({@code WS-OPTION-X PIC X(02) JUST RIGHT}).
     */
    private static final int OPTION_FIELD_WIDTH = 2;

    /**
     * COBOL {@code CDEMO-MENU-OPT-USRTYPE} admin marker ({@code 'A'}). An option
     * carrying this required user type is admin-only and is blocked for regular
     * users ({@code CDEMO-USRTYP-USER}).
     */
    private static final String ADMIN_OPTION_USER_TYPE = "A";

    /**
     * Exact COBOL literal from {@code PROCESS-ENTER-KEY}
     * ({@code legacy/cbl/COMEN01C.cbl} line 131) shown when the entered option is
     * non-numeric, zero, or above the option count.
     */
    private static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /**
     * Exact COBOL literal from {@code PROCESS-ENTER-KEY}
     * ({@code legacy/cbl/COMEN01C.cbl} line 140, including its trailing space)
     * shown when a regular user selects an admin-only option.
     */
    private static final String MSG_ADMIN_ONLY = "No access - Admin Only option... ";

    /**
     * Leading fragment of the COBOL {@code STRING}-built "coming soon" message
     * ({@code legacy/cbl/COMEN01C.cbl} line 159), including its trailing space.
     */
    private static final String COMING_SOON_PREFIX = "This option ";

    /**
     * Trailing fragment of the COBOL {@code STRING}-built "coming soon" message
     * ({@code legacy/cbl/COMEN01C.cbl} line 162).
     */
    private static final String COMING_SOON_SUFFIX = "is coming soon ...";

    /**
     * Session-scoped {@code COMMAREA} replacement ({@code COPY COCOM01Y}). Holds
     * the navigation hand-off, authenticated identity, and enter/re-enter flags
     * across pseudo-conversational interactions.
     */
    private final CardDemoContext context;

    /**
     * Migrated {@code COMEN02Y} main-menu option table ({@code COPY COMEN02Y}):
     * ten regular-user options seeded as compile-time values, read (never
     * hard-coded here) to validate the selection and build the display list.
     */
    private final MainMenuOptions mainMenuOptions;

    /**
     * Creates the main-menu service with its injected collaborators.
     *
     * <p>The constructor only stores the two references (no overridable method is
     * invoked), so it is free of the {@code this-escape} lint category under the
     * zero-warning build.</p>
     *
     * @param context         the session-scoped {@link CardDemoContext} (COMMAREA
     *                        replacement); must not be {@code null} in production
     * @param mainMenuOptions the migrated {@link MainMenuOptions} option table;
     *                        must not be {@code null} in production
     */
    public MainMenuService(CardDemoContext context, MainMenuOptions mainMenuOptions) {
        this.context = context;
        this.mainMenuOptions = mainMenuOptions;
    }

    /**
     * Main entry point - migration of paragraph {@code MAIN-PARA}
     * ({@code legacy/cbl/COMEN01C.cbl} lines 75-110).
     *
     * <p>Reproduces the pseudo-conversational state machine exactly. Each call
     * starts with the error flag off and no message (COBOL {@code SET ERR-FLG-OFF}
     * / {@code MOVE SPACES TO WS-MESSAGE}); that pristine state is represented by
     * returning a fresh {@link MainMenuResult} rather than mutating persistent
     * fields.</p>
     *
     * <ul>
     *   <li><b>{@code EIBCALEN = 0}</b> ({@link CardDemoContext#isNew()}): no
     *       COMMAREA - record the origin program as sign-on and hand off to the
     *       sign-on screen (COBOL lines 82-84).</li>
     *   <li><b>First program entry</b> ({@link CardDemoContext#isProgramEnter()},
     *       i.e. {@code NOT CDEMO-PGM-REENTER}): flip to re-enter
     *       ({@link CardDemoContext#markReenter()}) and display a fresh menu
     *       (COBOL lines 87-90; the {@code MOVE LOW-VALUES} + {@code SEND} is the
     *       controller's render).</li>
     *   <li><b>Re-entry</b>: evaluate the attention id - {@code ENTER} drives
     *       {@link #processEnterKey(COMEN01Form, CardDemoContext)}; {@code PF3}
     *       ({@link PfKey#PFK03}) sets the sign-on target and hands off via
     *       {@link #returnToSignonScreen(CardDemoContext)}; any other key yields
     *       the invalid-key message {@link Messages#CCDA_MSG_INVALID_KEY} (COBOL
     *       lines 91-103). A {@code null} key is treated as the {@code WHEN OTHER}
     *       branch.</li>
     * </ul>
     *
     * <p>The concluding {@code EXEC CICS RETURN TRANSID(CM00) COMMAREA(...)} (COBOL
     * lines 107-110) is the controller's responsibility: it writes the mutated
     * {@link CardDemoContext} back to the HTTP session and either renders the menu
     * or performs the redirect indicated by the returned {@link MainMenuResult}.</p>
     *
     * @param form the bound main-menu screen form ({@code COMEN1AI}); supplies the
     *             entered option for the {@code ENTER} path
     * @param aid  the resolved attention id (COBOL {@code EIBAID}); {@code null} is
     *             treated as {@link PfKey#OTHER}
     * @return the outcome describing whether to redirect or re-display the menu,
     *         plus any message and its severity
     */
    public MainMenuResult mainEntry(COMEN01Form form, PfKey aid) {
        // COBOL lines 82-84: IF EIBCALEN = 0 -> no COMMAREA; bounce to sign-on.
        // The controller must have marked the context initialized on a successful
        // sign-on, so a legitimately signed-on user is never bounced here.
        if (context.isNew()) {
            context.setFromProgram(SIGNON_PROGRAM);
            return returnToSignonScreen(context);
        }

        // COBOL lines 87-90: IF NOT CDEMO-PGM-REENTER -> first display of the menu
        // this conversation; mark re-enter and show a fresh screen.
        if (context.isProgramEnter()) {
            context.markReenter();
            return MainMenuResult.showMenu();
        }

        // COBOL lines 91-103: re-entry -> EVALUATE EIBAID. A null key falls through
        // to the WHEN OTHER (invalid-key) branch.
        PfKey pressedKey = (aid == null) ? PfKey.OTHER : aid;
        return switch (pressedKey) {
            case ENTER -> processEnterKey(form, context);
            case PFK03 -> {
                // COBOL line 97: MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
                context.setToProgram(SIGNON_PROGRAM);
                yield returnToSignonScreen(context);
            }
            default -> MainMenuResult.error(Messages.CCDA_MSG_INVALID_KEY);
        };
    }

    /**
     * Processes an {@code ENTER} on the menu - migration of paragraph
     * {@code PROCESS-ENTER-KEY} ({@code legacy/cbl/COMEN01C.cbl} lines 115-165).
     *
     * <p>Parsing (COBOL lines 117-125) reproduces the original exactly: the
     * two-character {@code OPTIONI} field is right-trimmed, right-justified into
     * {@code WS-OPTION-X PIC X(02) JUST RIGHT}, and its spaces replaced by
     * {@code '0'} ({@code INSPECT ... REPLACING ALL ' ' BY '0'}) before the numeric
     * test - see {@link #normalizeOptionField(String)}. The normalized value is
     * echoed back onto the form ({@code MOVE WS-OPTION TO OPTIONO}, line 125).</p>
     *
     * <p>Validation (COBOL lines 127-134): a non-numeric value, zero, or a value
     * greater than {@link MainMenuOptions#getOptionCount()} yields the error
     * {@link #MSG_INVALID_OPTION}. This method returns immediately on that error.
     * The COBOL instead falls through and, before its {@code IF NOT ERR-FLG-ON}
     * guard, evaluates the admin table with the (invalid) subscript - reading
     * out-of-range storage that compares unequal to {@code 'A'} and is therefore
     * observationally a no-op. Returning early yields the identical observable
     * behavior (the invalid-option message is displayed) while avoiding an
     * {@link IndexOutOfBoundsException}; this is a documented parity decision, not
     * a behavioral change (AAP &sect;0.2.2).</p>
     *
     * <p>Admin guard (COBOL lines 136-143): for a valid option, a regular user
     * ({@link CardDemoContext#isUser()}) selecting an option whose required user
     * type is {@code 'A'} is blocked with {@link #MSG_ADMIN_ONLY}. (All ten seeded
     * options are type {@code "U"}, so this path is inert for the standard table
     * but is preserved for faithfulness.)</p>
     *
     * <p>Routing (COBOL lines 145-165): when the selected option's target program
     * name does not begin with {@code DUMMY}, the origin transaction/program are
     * recorded, the program context is reset to enter
     * ({@link CardDemoContext#markEnter()}, the {@code MOVE ZEROS TO
     * CDEMO-PGM-CONTEXT} of line 151), the target program is stored on the context,
     * and {@link RoutingAction#REDIRECT} is returned (the COBOL {@code XCTL}).
     * Otherwise the option is a not-yet-implemented placeholder and the green
     * "coming soon" message ({@link #buildComingSoonMessage(String)}) is returned
     * as {@link MessageSeverity#INFORMATION}.</p>
     *
     * @param form the bound menu form supplying {@code OPTIONI}; its {@code option}
     *             property is updated with the normalized value ({@code OPTIONO})
     * @param ctx  the session context to update with the routing target
     * @return the outcome: an error/informational re-display, a "coming soon"
     *         re-display, or a redirect to the selected program
     */
    public MainMenuResult processEnterKey(COMEN01Form form, CardDemoContext ctx) {
        // COBOL lines 117-125: parse OPTIONI into the normalized two-character field.
        String normalizedOption = normalizeOptionField(form.getOption());
        boolean numeric = isAllDigits(normalizedOption);
        int optionNumber = numeric ? Integer.parseInt(normalizedOption) : -1;
        // COBOL line 125: MOVE WS-OPTION TO OPTIONO - echo the normalized value back.
        form.setOption(normalizedOption);

        // COBOL lines 127-134: NOT NUMERIC OR = ZEROS OR > CDEMO-MENU-OPT-COUNT.
        if (!numeric || optionNumber == 0 || optionNumber > mainMenuOptions.getOptionCount()) {
            return MainMenuResult.error(MSG_INVALID_OPTION);
        }

        // Valid option in 1..count -> safe zero-based lookup (see parity note above).
        MainMenuOption selected = mainMenuOptions.getOptions().get(optionNumber - 1);

        // COBOL lines 136-143: block admin-only options for a regular user.
        if (ctx.isUser() && ADMIN_OPTION_USER_TYPE.equals(selected.requiredUserType())) {
            return MainMenuResult.error(MSG_ADMIN_ONLY);
        }

        // COBOL lines 145-165: route to the program, or report "coming soon".
        if (!startsWithDummy(selected.programName())) {
            ctx.setFromTranid(TRANSACTION_ID);       // MOVE WS-TRANID TO CDEMO-FROM-TRANID (line 147)
            ctx.setFromProgram(PROGRAM_NAME);         // MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM (line 148)
            ctx.markEnter();                          // MOVE ZEROS TO CDEMO-PGM-CONTEXT (line 151)
            ctx.setToProgram(selected.programName()); // XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME) (lines 152-155)
            return MainMenuResult.redirect();
        }
        return MainMenuResult.information(buildComingSoonMessage(selected.name()));
    }

    /**
     * Hands control back to the sign-on screen - migration of paragraph
     * {@code RETURN-TO-SIGNON-SCREEN} ({@code legacy/cbl/COMEN01C.cbl} lines
     * 170-177).
     *
     * <p>When the target program is unset (COBOL {@code LOW-VALUES OR SPACES},
     * lines 172-174) it defaults to the sign-on program {@code COSGN00C}. The
     * sign-on transaction id {@code CC00} is also recorded so the paired controller
     * can redirect to the correct route (the AAP hand-off model). The COBOL
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} (lines 175-177) becomes the
     * returned {@link RoutingAction#REDIRECT}, executed by the controller using
     * {@link CardDemoContext#getToProgram()}.</p>
     *
     * @param ctx the session context whose {@code toProgram}/{@code toTranid} are
     *            set to the sign-on target
     * @return a {@link RoutingAction#REDIRECT} outcome (no message)
     */
    public MainMenuResult returnToSignonScreen(CardDemoContext ctx) {
        if (isBlankOrLowValues(ctx.getToProgram())) {
            ctx.setToProgram(SIGNON_PROGRAM);
        }
        ctx.setToTranid(SIGNON_TRANSACTION_ID);
        return MainMenuResult.redirect();
    }

    /**
     * Builds the menu option display lines - migration of paragraph
     * {@code BUILD-MENU-OPTIONS} ({@code legacy/cbl/COMEN01C.cbl} lines 236-277).
     *
     * <p>Iterates the first {@link MainMenuOptions#getOptionCount()} options and,
     * for each, formats {@code CDEMO-MENU-OPT-NUM} + {@code '. '} +
     * {@code CDEMO-MENU-OPT-NAME} (COBOL {@code STRING}, lines 243-246). Because
     * {@code CDEMO-MENU-OPT-NUM} is {@code PIC 9(02)} the number is rendered
     * zero-padded to two digits, so the lines read {@code "01. Account View"} ...
     * {@code "10. Bill Payment"}. The COBOL then distributes the lines into the
     * {@code OPTN001O}..{@code OPTN012O} map fields; that placement is presentation
     * and belongs to the controller, so this method returns the ordered display
     * list for the controller to render.</p>
     *
     * @return an ordered, size-{@link MainMenuOptions#getOptionCount()} list of
     *         formatted {@code "NN. Name"} menu lines
     */
    public List<String> buildMenuOptions() {
        List<MainMenuOption> options = mainMenuOptions.getOptions();
        int count = mainMenuOptions.getOptionCount();
        List<String> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            MainMenuOption option = options.get(index);
            lines.add(String.format(Locale.ROOT, "%02d. %s", option.number(), option.name()));
        }
        return lines;
    }

    /**
     * Normalizes the raw {@code OPTIONI} field to the two-character
     * {@code WS-OPTION-X} value, reproducing COBOL lines 117-124.
     *
     * <p>The fixed {@code PIC X(02)} field is right-trimmed (the descending
     * {@code PERFORM VARYING} that locates the last non-space), the remainder is
     * right-justified into two characters ({@code PIC X(02) JUST RIGHT}, truncating
     * to the rightmost two characters if longer), and every remaining space is
     * replaced with {@code '0'} ({@code INSPECT ... REPLACING ALL ' ' BY '0'}). A
     * {@code null} input is treated as all-spaces, yielding {@code "00"}.</p>
     *
     * @param rawOption the raw option text from the form (may be {@code null})
     * @return the normalized two-character option value (digits and/or the
     *         original non-space, non-digit characters)
     */
    private static String normalizeOptionField(String rawOption) {
        String field = (rawOption == null) ? "" : rawOption;
        int end = field.length();
        while (end > 0 && field.charAt(end - 1) == ' ') {
            end--;
        }
        String prefix = field.substring(0, end);
        String justified;
        if (prefix.length() >= OPTION_FIELD_WIDTH) {
            // PIC X(02) JUST RIGHT retains the rightmost two characters.
            justified = prefix.substring(prefix.length() - OPTION_FIELD_WIDTH);
        } else {
            justified = " ".repeat(OPTION_FIELD_WIDTH - prefix.length()) + prefix;
        }
        return justified.replace(' ', '0');
    }

    /**
     * Reproduces the COBOL {@code IS NUMERIC} class test for the normalized option
     * field (COBOL line 127).
     *
     * @param value the normalized option value
     * @return {@code true} only when {@code value} is non-empty and every character
     *         is an ASCII digit {@code '0'}-{@code '9'}
     */
    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
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
     * Reproduces the COBOL placeholder test
     * {@code CDEMO-MENU-OPT-PGMNAME(WS-OPTION)(1:5) = 'DUMMY'} (COBOL line 146).
     *
     * @param programName the selected option's target program name (may be
     *                    {@code null})
     * @return {@code true} when {@code programName} has at least five characters and
     *         its first five characters equal {@code DUMMY} (case-sensitive, as in
     *         COBOL)
     */
    private static boolean startsWithDummy(String programName) {
        if (programName == null) {
            return false;
        }
        int prefixLength = DUMMY_PROGRAM_PREFIX.length();
        return programName.length() >= prefixLength
                && programName.regionMatches(0, DUMMY_PROGRAM_PREFIX, 0, prefixLength);
    }

    /**
     * Builds the "coming soon" message, reproducing the COBOL {@code STRING}
     * (COBOL lines 159-163).
     *
     * <p>The COBOL statement concatenates {@code 'This option '} (delimited by
     * size), {@code CDEMO-MENU-OPT-NAME} <em>delimited by space</em> - i.e. only up
     * to its first embedded space - and {@code 'is coming soon ...'}. The
     * delimit-by-space behavior (and the resulting absence of a separating space
     * before {@code "is"}) is preserved verbatim as a behavioral quirk (AAP
     * &sect;0.2.2). This path is only reachable for {@code DUMMY} placeholder
     * options, of which the standard table has none.</p>
     *
     * @param optionName the selected option's display name (may be {@code null})
     * @return the assembled "coming soon" message
     */
    private static String buildComingSoonMessage(String optionName) {
        String name = (optionName == null) ? "" : optionName;
        int space = name.indexOf(' ');
        String firstWord = (space >= 0) ? name.substring(0, space) : name;
        return COMING_SOON_PREFIX + firstWord + COMING_SOON_SUFFIX;
    }

    /**
     * Reproduces the COBOL {@code = LOW-VALUES OR SPACES} test for the target
     * program name (COBOL line 172).
     *
     * @param value the candidate program name (may be {@code null})
     * @return {@code true} when {@code value} is {@code null}, empty, or consists
     *         solely of spaces and/or low-value ({@code NUL}) characters
     */
    private static boolean isBlankOrLowValues(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Routing decision returned by the business methods, modeling the two terminal
     * actions of {@code MAIN-PARA}: transfer control ({@code EXEC CICS XCTL}) or
     * re-display the menu ({@code SEND-MENU-SCREEN}).
     */
    public enum RoutingAction {

        /**
         * Hand off to the program recorded in {@link CardDemoContext#getToProgram()}
         * (the COBOL {@code XCTL}); the controller performs the redirect.
         */
        REDIRECT,

        /**
         * Re-display the main menu (the COBOL {@code SEND-MENU-SCREEN}); the
         * controller renders the screen, applying any message.
         */
        SHOW_MENU
    }

    /**
     * Severity of the message accompanying a {@link RoutingAction#SHOW_MENU},
     * preserving the COBOL color contract: errors use the default (red) attribute
     * driven by {@code WS-ERR-FLG}, while the "coming soon" note is explicitly
     * green ({@code MOVE DFHGREEN TO ERRMSGC}, COBOL line 158).
     */
    public enum MessageSeverity {

        /** No message to display. */
        NONE,

        /** Error message (COBOL {@code WS-ERR-FLG} on); rendered in the error color. */
        ERROR,

        /** Informational message (COBOL {@code DFHGREEN}); rendered in green. */
        INFORMATION
    }

    /**
     * Immutable outcome of a business method: the {@link RoutingAction} to take,
     * the message to display (empty when none), and the message
     * {@link MessageSeverity}.
     *
     * <p>The controller inspects {@link #action()} to choose between issuing a
     * redirect (using {@link CardDemoContext#getToProgram()}) and re-rendering the
     * menu; when re-rendering it shows {@link #message()} using the styling implied
     * by {@link #severity()}.</p>
     *
     * @param action   the routing action; must not be {@code null}
     * @param message  the message text; normalized to {@code ""} when {@code null}
     * @param severity the message severity; must not be {@code null}
     */
    public record MainMenuResult(RoutingAction action, String message, MessageSeverity severity) {

        /**
         * Canonical constructor enforcing non-null {@code action}/{@code severity}
         * and normalizing a {@code null} {@code message} to the empty string.
         *
         * @param action   the routing action; must not be {@code null}
         * @param message  the message text; {@code null} becomes {@code ""}
         * @param severity the message severity; must not be {@code null}
         */
        public MainMenuResult {
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            if (severity == null) {
                throw new IllegalArgumentException("severity must not be null");
            }
            if (message == null) {
                message = "";
            }
        }

        /**
         * Creates a redirect outcome with no message (COBOL {@code XCTL}).
         *
         * @return a {@link RoutingAction#REDIRECT} result with {@link MessageSeverity#NONE}
         */
        public static MainMenuResult redirect() {
            return new MainMenuResult(RoutingAction.REDIRECT, "", MessageSeverity.NONE);
        }

        /**
         * Creates a plain menu re-display with no message (COBOL {@code SEND-MENU-SCREEN}).
         *
         * @return a {@link RoutingAction#SHOW_MENU} result with {@link MessageSeverity#NONE}
         */
        public static MainMenuResult showMenu() {
            return new MainMenuResult(RoutingAction.SHOW_MENU, "", MessageSeverity.NONE);
        }

        /**
         * Creates a menu re-display carrying an error message (COBOL
         * {@code WS-ERR-FLG} path).
         *
         * @param message the error text to display
         * @return a {@link RoutingAction#SHOW_MENU} result with {@link MessageSeverity#ERROR}
         */
        public static MainMenuResult error(String message) {
            return new MainMenuResult(RoutingAction.SHOW_MENU, message, MessageSeverity.ERROR);
        }

        /**
         * Creates a menu re-display carrying an informational message (COBOL
         * {@code DFHGREEN} path).
         *
         * @param message the informational text to display
         * @return a {@link RoutingAction#SHOW_MENU} result with {@link MessageSeverity#INFORMATION}
         */
        public static MainMenuResult information(String message) {
            return new MainMenuResult(RoutingAction.SHOW_MENU, message, MessageSeverity.INFORMATION);
        }
    }
}
