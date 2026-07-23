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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.menu.AdminMenuOptions;
import com.aws.carddemo.dto.screen.COADM01Form;

/**
 * Admin-menu online service, the Java migration of the CICS COBOL program
 * {@code COADM01C} (the AWS CardDemo administrator main menu).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COADM01C.cbl} &mdash; program {@code COADM01C},
 * CICS transaction id {@code CA00}. This service preserves the original program's control
 * flow one-for-one: each business COBOL paragraph becomes exactly one Java method
 * (AAP &sect;0.3.3, &sect;0.4.1). It is the admin analogue of the main-menu service and is
 * structurally identical to it, differing only in that it drives the administration option
 * table ({@link AdminMenuOptions}, from copybook {@code COADM02Y}).</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #mainEntry(AidKey, COADM01Form)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(COADM01Form, CardDemoContext)}</li>
 *   <li>{@code BUILD-MENU-OPTIONS} &rarr; {@link #buildMenuOptions()}</li>
 *   <li>{@code RETURN-TO-SIGNON-SCREEN} &rarr; {@link #returnToSignonScreen(CardDemoContext)}</li>
 * </ul>
 *
 * <p>The presentation paragraphs {@code SEND-MENU-SCREEN}, {@code RECEIVE-MENU-SCREEN} and
 * {@code POPULATE-HEADER-INFO} are intentionally <em>not</em> implemented here: sending and
 * receiving the 3270/BMS map, populating the header (title/date/time), and performing the
 * actual navigation redirect are presentation concerns owned by the paired
 * {@code AdminMenuController}. This service contains business logic only &mdash; it validates
 * input, decides routing, and produces messages &mdash; and never touches the screen I/O.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) that carried navigation state across CICS returns
 * becomes the session-scoped {@link CardDemoContext}, injected here. First entry into the
 * transaction (COBOL {@code EIBCALEN = 0}) is reproduced by {@link CardDemoContext#isNew()};
 * the {@code XCTL}/{@code RETURN TRANSID} hand-off is reproduced through the context's
 * {@code from*}/{@code to*} program fields, which the controller consults when redirecting.</p>
 *
 * <h2>Outcome model</h2>
 * <p>Because the controller performs the redirect and the screen rendering, each business
 * method returns an immutable {@link AdminMenuResult} describing what should happen next: a
 * redirect to a target program, or a message to redisplay (flagged as an error or as a neutral
 * informational message). This preserves the COBOL distinction between the red error line and
 * the green ("coming soon") informational line without this service having to know how the
 * message is styled.</p>
 *
 * <h2>Access and design constraints</h2>
 * <ul>
 *   <li><b>Admin-only:</b> {@code COADM01C} is reachable only by administrator users; that
 *       gate is enforced upstream by the sign-on service and Spring Security routing, not by
 *       this service.</li>
 *   <li><b>Constructor injection, no repositories:</b> the service depends only on the
 *       session context and the option table; it performs no data access.</li>
 *   <li><b>Message literals:</b> the invalid-option, "coming soon" and invalid-key messages
 *       are declared as local constants that mirror the COBOL literals (the invalid-key text
 *       mirrors {@code CCDA-MSG-INVALID-KEY} from {@code legacy/cpy/CSMSG01Y.cpy}); the shared
 *       message-constants holder is not a declared dependency of this service, so its values
 *       are reproduced locally rather than imported.</li>
 * </ul>
 */
@Service
public class AdminMenuService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} &mdash; this program's name. */
    private static final String PROGRAM_NAME = "COADM01C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CA00'} &mdash; this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CA00";

    /**
     * Sign-on program (COBOL literal {@code 'COSGN00C'}) &mdash; the destination for both the
     * first-entry bounce ({@code EIBCALEN = 0}) and the PF3 return-to-sign-on path.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Sentinel prefix (COBOL literal {@code 'DUMMY'}) marking a menu option whose target
     * program is not yet implemented; such an option yields the "coming soon" message rather
     * than a redirect.
     */
    private static final String DUMMY_PGM_PREFIX = "DUMMY";

    /**
     * Length of the {@code 'DUMMY'} sentinel compared against the option's program name, matching
     * the COBOL reference-modification {@code CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5)}.
     */
    private static final int DUMMY_PREFIX_LENGTH = 5;

    /** Width of the BMS {@code OPTION} field (COBOL {@code PIC X(02)}). */
    private static final int OPTION_FIELD_WIDTH = 2;

    /**
     * Invalid-option message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code PROCESS-ENTER-KEY} when the entered option is non-numeric, zero, or out of range.
     */
    private static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /**
     * "Coming soon" message. Reproduces the COBOL {@code STRING 'This option ' 'is coming
     * soon ...'} concatenation used for a valid option whose target program is a
     * {@code 'DUMMY'} placeholder; rendered as a neutral (green) informational line.
     */
    private static final String MSG_COMING_SOON = "This option is coming soon ...";

    /**
     * Invalid-key message. Mirrors COBOL {@code CCDA-MSG-INVALID-KEY} (from
     * {@code legacy/cpy/CSMSG01Y.cpy}), moved to {@code WS-MESSAGE} in {@code MAIN-PARA} for any
     * unmapped AID key. Declared locally because the shared message-constants holder is not a
     * declared dependency of this service.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Session-scoped navigation and selection context, the modern replacement for the COBOL
     * COMMAREA ({@code COCOM01Y}). Injected as a Spring session-scoped proxy.
     */
    private final CardDemoContext context;

    /**
     * Administration menu option table, the migrated COBOL copybook {@code COADM02Y}. Supplies
     * the option count, display names, and per-option target program names.
     */
    private final AdminMenuOptions adminMenuOptions;

    /**
     * Creates the admin-menu service via Spring constructor injection.
     *
     * <p>A single constructor means no {@code @Autowired} annotation is required. Neither
     * argument is dereferenced here, so the constructor introduces no {@code this}-escape.</p>
     *
     * @param context          the session-scoped CardDemo context (COMMAREA replacement); must
     *                         not be {@code null}
     * @param adminMenuOptions the administration menu option table (copybook {@code COADM02Y});
     *                         must not be {@code null}
     */
    public AdminMenuService(CardDemoContext context, AdminMenuOptions adminMenuOptions) {
        this.context = context;
        this.adminMenuOptions = adminMenuOptions;
    }

    /**
     * Attention-identifier (AID) keys handled by {@link #mainEntry(AidKey, COADM01Form)},
     * mirroring the COBOL {@code EVALUATE EIBAID} of {@code MAIN-PARA}.
     *
     * <p>The paired {@code AdminMenuController} maps the inbound HTTP submission (the pressed
     * button or PF key) to one of these constants before delegating to the service. Only the
     * keys with distinct behaviour in {@code COADM01C} are modelled: {@link #ENTER}
     * ({@code DFHENTER}) and {@link #PF3} ({@code DFHPF3}); every other key collapses to
     * {@link #OTHER}, matching the COBOL {@code WHEN OTHER} default.</p>
     */
    public enum AidKey {

        /** COBOL {@code DFHENTER} &mdash; the ENTER key; selects the entered menu option. */
        ENTER,

        /** COBOL {@code DFHPF3} &mdash; the PF3 key; returns to the sign-on screen. */
        PF3,

        /** COBOL {@code WHEN OTHER} &mdash; any other key; yields the invalid-key message. */
        OTHER
    }

    /**
     * Immutable outcome of an admin-menu interaction, describing what the controller should do
     * next: perform a redirect, or redisplay the menu with a message.
     *
     * <p>Exactly one of two shapes is produced:</p>
     * <ul>
     *   <li><b>Redirect</b> &mdash; {@link #targetProgram()} is set (and {@link #isRedirect()}
     *       is {@code true}); the controller redirects to the route mapped from that program
     *       name. This reproduces the COBOL {@code XCTL}.</li>
     *   <li><b>Redisplay</b> &mdash; {@link #message()} is set (and {@link #hasMessage()} is
     *       {@code true}); the controller redisplays the menu with the message. {@link #error()}
     *       distinguishes an error line (COBOL error, styled red) from a neutral informational
     *       line (COBOL {@code DFHGREEN}, the "coming soon" message).</li>
     * </ul>
     *
     * @param targetProgram the target program name for a redirect, or {@code null} for a
     *                      redisplay outcome
     * @param message       the message to redisplay, or {@code null} for a redirect outcome
     * @param error         {@code true} when {@code message} is an error line; {@code false}
     *                      for a neutral informational line or a redirect
     */
    public record AdminMenuResult(String targetProgram, String message, boolean error) {

        /**
         * Reports whether this outcome is a redirect (the COBOL {@code XCTL} equivalent).
         *
         * @return {@code true} when a non-blank {@link #targetProgram()} is present
         */
        public boolean isRedirect() {
            return targetProgram != null && !targetProgram.isBlank();
        }

        /**
         * Reports whether this outcome carries a message to redisplay.
         *
         * @return {@code true} when a non-blank {@link #message()} is present
         */
        public boolean hasMessage() {
            return message != null && !message.isBlank();
        }

        /**
         * Creates a redirect outcome targeting the given program.
         *
         * @param targetProgram the target program name (the COBOL {@code XCTL} destination)
         * @return a redirect outcome
         */
        private static AdminMenuResult ofRedirect(String targetProgram) {
            return new AdminMenuResult(targetProgram, null, false);
        }

        /**
         * Creates an error-message (redisplay) outcome.
         *
         * @param message the error message to redisplay
         * @return an error outcome
         */
        private static AdminMenuResult ofError(String message) {
            return new AdminMenuResult(null, message, true);
        }

        /**
         * Creates a neutral informational-message (redisplay) outcome.
         *
         * @param message the informational message to redisplay
         * @return a non-error message outcome
         */
        private static AdminMenuResult ofInfo(String message) {
            return new AdminMenuResult(null, message, false);
        }
    }

    /**
     * Handles an admin-menu interaction, the Java migration of paragraph {@code MAIN-PARA} in
     * {@code legacy/cbl/COADM01C.cbl}.
     *
     * <p>Reproduces the COBOL control flow. On first entry into the transaction (COBOL
     * {@code EIBCALEN = 0}, reproduced by {@link CardDemoContext#isNew()}) the originating
     * program is recorded as the sign-on program and control returns to the sign-on screen.
     * Otherwise the pressed AID key is evaluated: {@code ENTER} selects the entered option,
     * {@code PF3} returns to the sign-on screen (with the sign-on program as the hand-off
     * target), and any other key yields the invalid-key message.</p>
     *
     * <p>The COBOL first-display branch ({@code IF NOT CDEMO-PGM-REENTER ... SEND-MENU-SCREEN})
     * is intentionally not reproduced here: rendering the menu for the first time is a
     * presentation concern owned by the paired controller (its GET handler), whereas this
     * method handles the submitted interaction (the COBOL {@code RECEIVE} plus
     * {@code EVALUATE EIBAID}). {@code COADM01C} issues {@code SEND ... ERASE} with no explicit
     * cursor; erase/cursor handling is likewise a controller concern.</p>
     *
     * @param aid  the attention-identifier key pressed; a {@code null} value is treated as
     *             {@link AidKey#OTHER}, matching the COBOL {@code WHEN OTHER} default
     * @param form the submitted admin-menu screen form (map {@code COADM1A} of mapset
     *             {@code COADM01})
     * @return the interaction outcome: a redirect target, or a message to redisplay
     */
    public AdminMenuResult mainEntry(AidKey aid, COADM01Form form) {
        // MAIN-PARA: IF EIBCALEN = 0 -> record the sign-on program and bounce back to it.
        if (context.isNew()) {
            context.setFromProgram(SIGNON_PROGRAM);
            return returnToSignonScreen(context);
        }
        // ELSE (re-entry): EVALUATE EIBAID. A null AID collapses to the WHEN OTHER branch.
        AidKey effectiveAid = (aid == null) ? AidKey.OTHER : aid;
        return switch (effectiveAid) {
            case ENTER -> processEnterKey(form, context);
            case PF3 -> {
                // DFHPF3: MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, then RETURN-TO-SIGNON-SCREEN.
                context.setToProgram(SIGNON_PROGRAM);
                yield returnToSignonScreen(context);
            }
            case OTHER -> AdminMenuResult.ofError(MSG_INVALID_KEY);
        };
    }

    /**
     * Validates and routes the selected menu option, the Java migration of paragraph
     * {@code PROCESS-ENTER-KEY} in {@code legacy/cbl/COADM01C.cbl}.
     *
     * <p>Reproduces the COBOL normalization exactly: the entered option is right-trimmed,
     * right-justified into the two-character {@code OPTION} field ({@code PIC X(02) JUST
     * RIGHT}), and its blanks replaced with {@code '0'} (COBOL
     * {@code INSPECT ... REPLACING ALL ' ' BY '0'}). A value that is non-numeric, zero, or
     * greater than {@link AdminMenuOptions#getOptionCount()} yields the invalid-option
     * message (COBOL {@code IF WS-OPTION IS NOT NUMERIC OR WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR
     * WS-OPTION = ZEROS}).</p>
     *
     * <p>For a valid option, when the target program name does not begin with the
     * {@code 'DUMMY'} sentinel the hand-off fields are set &mdash; from-transaction
     * ({@code CDEMO-FROM-TRANID = 'CA00'}), from-program ({@code CDEMO-FROM-PROGRAM =
     * 'COADM01C'}), and program context reset to enter ({@code CDEMO-PGM-CONTEXT = 0}) &mdash;
     * and the target program is returned as the redirect destination (COBOL {@code XCTL}). A
     * {@code 'DUMMY'} option instead yields the neutral "coming soon" message. All four seeded
     * admin options target real programs, so the "coming soon" branch is preserved for fidelity
     * but is not reachable for options {@code 1}-{@code 4}.</p>
     *
     * <p>The COBOL also echoes the normalized option back to the output map
     * ({@code MOVE WS-OPTION TO OPTIONO}); that echo is a presentation concern handled by the
     * paired controller, so this method does not mutate {@code form}.</p>
     *
     * @param form the submitted admin-menu screen form supplying the entered option; a
     *             {@code null} form is treated as a blank (invalid) option
     * @param ctx  the session context whose hand-off fields are set on a valid, non-placeholder
     *             selection
     * @return the routing outcome: a redirect to the selected program, or a message to
     *         redisplay
     */
    public AdminMenuResult processEnterKey(COADM01Form form, CardDemoContext ctx) {
        String normalizedOption = normalizeOptionField(form == null ? null : form.getOption());

        // IF WS-OPTION IS NOT NUMERIC OR WS-OPTION = ZEROS OR WS-OPTION > CDEMO-ADMIN-OPT-COUNT.
        if (!isAllDigits(normalizedOption)) {
            return AdminMenuResult.ofError(MSG_INVALID_OPTION);
        }
        int option = Integer.parseInt(normalizedOption);
        if (option == 0 || option > adminMenuOptions.getOptionCount()) {
            return AdminMenuResult.ofError(MSG_INVALID_OPTION);
        }

        // The validated range already bounds the index; guard the table access defensively so a
        // count/table mismatch degrades to the invalid-option message rather than an exception.
        List<AdminMenuOptions.AdminMenuOption> options = adminMenuOptions.getOptions();
        if (option > options.size()) {
            return AdminMenuResult.ofError(MSG_INVALID_OPTION);
        }
        AdminMenuOptions.AdminMenuOption selected = options.get(option - 1);

        // IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY' -> XCTL; ELSE coming soon.
        if (!isDummyProgram(selected.programName())) {
            ctx.setFromTranid(TRANSACTION_ID);
            ctx.setFromProgram(PROGRAM_NAME);
            ctx.markEnter();
            return AdminMenuResult.ofRedirect(selected.programName());
        }
        return AdminMenuResult.ofInfo(MSG_COMING_SOON);
    }

    /**
     * Returns control to the sign-on program, the Java migration of paragraph
     * {@code RETURN-TO-SIGNON-SCREEN} in {@code legacy/cbl/COADM01C.cbl}.
     *
     * <p>Mirrors the COBOL default-and-transfer: when the hand-off target
     * ({@code CDEMO-TO-PROGRAM}) is unset (COBOL {@code LOW-VALUES OR SPACES}) it defaults to the
     * sign-on program {@code COSGN00C}, then control is transferred there (COBOL {@code XCTL}).
     * The transfer is expressed as a redirect outcome for the controller to act on.</p>
     *
     * @param ctx the session context supplying, and if necessary receiving the default of, the
     *            hand-off target program
     * @return a redirect outcome targeting the (possibly defaulted) sign-on program
     */
    public AdminMenuResult returnToSignonScreen(CardDemoContext ctx) {
        String target = ctx.getToProgram();
        if (target == null || target.isBlank()) {
            ctx.setToProgram(SIGNON_PROGRAM);
        }
        return AdminMenuResult.ofRedirect(ctx.getToProgram());
    }

    /**
     * Builds the numbered admin-menu display lines, the Java migration of paragraph
     * {@code BUILD-MENU-OPTIONS} in {@code legacy/cbl/COADM01C.cbl}.
     *
     * <p>Mirrors the COBOL {@code PERFORM VARYING} that concatenates {@code CDEMO-ADMIN-OPT-NUM}
     * (a two-digit {@code PIC 9(02)}), the literal {@code '. '}, and {@code CDEMO-ADMIN-OPT-NAME}
     * into each {@code OPTNnnnO} field. Each line is formatted as {@code "NN. Name"} &mdash; for
     * example {@code "01. User List (Security)"}. Formatting uses {@link Locale#ROOT} so the
     * two-digit number always renders with ASCII digits, preserving legacy ordering/appearance
     * regardless of the server locale. The paired controller places these lines into the
     * screen's option fields.</p>
     *
     * @return the ordered admin-menu display lines, one per populated option
     */
    public List<String> buildMenuOptions() {
        List<String> lines = new ArrayList<>();
        for (AdminMenuOptions.AdminMenuOption option : adminMenuOptions.getOptions()) {
            lines.add(String.format(Locale.ROOT, "%02d. %s", option.number(), option.name()));
        }
        return lines;
    }

    /**
     * Normalizes the entered option to a fixed two-character field, reproducing the COBOL
     * right-trim, {@code PIC X(02) JUST RIGHT} right-justification, and
     * {@code INSPECT ... REPLACING ALL ' ' BY '0'} of {@code PROCESS-ENTER-KEY}.
     *
     * <p>Examples (two-character field): {@code "1 "} and {@code " 1"} both normalize to
     * {@code "01"}; {@code "12"} to {@code "12"}; a blank or {@code null} value to {@code "00"};
     * and a value containing a non-digit (for example {@code "1A"}) is returned unchanged so the
     * subsequent numeric class test rejects it.</p>
     *
     * @param rawOption the raw entered option (may be {@code null})
     * @return a two-character string with blanks replaced by {@code '0'}
     */
    private static String normalizeOptionField(String rawOption) {
        String raw = (rawOption == null) ? "" : rawOption;

        // Right-trim trailing spaces (COBOL scan from the field end for the last non-space).
        int end = raw.length();
        while (end > 0 && raw.charAt(end - 1) == ' ') {
            end--;
        }

        // Significant characters, bounded by the two-character OPTION field width.
        String significant = raw.substring(0, Math.min(end, OPTION_FIELD_WIDTH));

        // Right-justify into the two-character field (PIC X(02) JUST RIGHT).
        String justified;
        if (significant.length() >= OPTION_FIELD_WIDTH) {
            justified = significant.substring(significant.length() - OPTION_FIELD_WIDTH);
        } else {
            justified = " ".repeat(OPTION_FIELD_WIDTH - significant.length()) + significant;
        }

        // INSPECT ... REPLACING ALL ' ' BY '0'.
        return justified.replace(' ', '0');
    }

    /**
     * Reports whether every character of the supplied value is an ASCII digit, reproducing the
     * COBOL numeric class test ({@code IS NOT NUMERIC}) applied to the normalized option.
     *
     * @param value the normalized option string
     * @return {@code true} when the value is non-empty and every character is {@code '0'}-{@code '9'}
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
     * Reports whether the supplied program name marks a not-yet-implemented option, reproducing
     * the COBOL sentinel test {@code CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) = 'DUMMY'}.
     *
     * @param programName the candidate option program name (may be {@code null})
     * @return {@code true} when the first five characters equal {@code 'DUMMY'}
     */
    private static boolean isDummyProgram(String programName) {
        if (programName == null) {
            return false;
        }
        String prefix = (programName.length() >= DUMMY_PREFIX_LENGTH)
                ? programName.substring(0, DUMMY_PREFIX_LENGTH)
                : programName;
        return DUMMY_PGM_PREFIX.equals(prefix);
    }
}
