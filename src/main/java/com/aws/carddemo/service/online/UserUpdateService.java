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

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COUSR02Form;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * User-update (admin) online service, the Java migration of the CICS COBOL program
 * {@code COUSR02C} (the AWS CardDemo "Update a user in USRSEC file" transaction).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COUSR02C.cbl} &mdash; program {@code COUSR02C},
 * CICS transaction id {@code CU02}. This service preserves the original program's control
 * flow one-for-one: each business COBOL paragraph becomes exactly one Java method
 * (AAP &sect;0.3.3, &sect;0.4.1). It is the canonical READ-UPDATE-REWRITE archetype over the
 * {@code USRSEC} store: a keyed read of the user-security record, an in-place edit, and a
 * rewrite, executed as a single unit of work (AAP &sect;0.6.5).</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #mainEntry(COUSR02Form, AidKey, String)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(COUSR02Form, CardDemoContext)}</li>
 *   <li>{@code UPDATE-USER-INFO} &rarr; {@link #updateUserInfo(COUSR02Form, CardDemoContext)}</li>
 *   <li>{@code READ-USER-SEC-FILE} &rarr; {@link #readUserSecFile(String)}</li>
 *   <li>{@code UPDATE-USER-SEC-FILE} &rarr; {@link #updateUserSecFile(UserSecurity)}</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr; {@link #clearCurrentScreen(COUSR02Form)}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr; {@link #initializeAllFields(COUSR02Form)}</li>
 * </ul>
 *
 * <p>The presentation paragraphs {@code SEND-USRUPD-SCREEN}, {@code RECEIVE-USRUPD-SCREEN},
 * {@code POPULATE-HEADER-INFO} and the physical screen transfer of {@code RETURN-TO-PREV-SCREEN}
 * (the {@code EXEC CICS XCTL}) are intentionally <em>not</em> implemented here: sending and
 * receiving the 3270/BMS map, populating the header (title/date/time), and performing the actual
 * navigation redirect are presentation concerns owned by the paired {@code UserAdminController}
 * (AAP &sect;0.4.1). This service contains business logic only &mdash; it validates input, reads and
 * rewrites the record, decides routing, and produces messages &mdash; and never touches screen I/O.
 * The COMMAREA hand-off <em>state</em> that {@code RETURN-TO-PREV-SCREEN} sets (the from/to program
 * and the program-context reset) is reproduced by the private {@link #returnToPrevScreen(CardDemoContext)}
 * helper, which signals the redirect through the returned result for the controller to act on.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) that carried navigation state across CICS returns
 * becomes the session-scoped {@link CardDemoContext}, injected here. First entry into the
 * transaction (COBOL {@code EIBCALEN = 0}) is reproduced by {@link CardDemoContext#isNew()}; the
 * within-program enter/re-enter state machine ({@code CDEMO-PGM-CONTEXT}) is reproduced by
 * {@link CardDemoContext#isProgramEnter()} / {@link CardDemoContext#markReenter()} /
 * {@link CardDemoContext#markEnter()}; and the {@code XCTL}/{@code RETURN TRANSID} hand-off is
 * reproduced through the context's {@code from*}/{@code to*} program fields, which the controller
 * consults when redirecting.</p>
 *
 * <p><b>Selected user id ({@code CDEMO-CU02-USR-SELECTED}):</b> {@code COUSR02C} is reached from the
 * user-list program {@code COUSR00C} via a {@code 'U'} (update) line selection, which places the
 * chosen user id in the program-specific COMMAREA extension {@code CDEMO-CU02-USR-SELECTED}. That
 * field is <em>not</em> part of the shared {@link CardDemoContext} (which mirrors only the common
 * {@code COCOM01Y} layout), so it is supplied to {@link #mainEntry(COUSR02Form, AidKey, String)
 * mainEntry} as the {@code selectedUserId} argument by the paired controller; on first entry a
 * non-blank value pre-loads {@code USRIDIN} and drives an immediate look-up
 * ({@link #processEnterKey(COUSR02Form, CardDemoContext)}), exactly as in the COBOL.</p>
 *
 * <h2>Outcome model</h2>
 * <p>Because the controller performs the redirect and the screen rendering, each business method
 * returns an immutable {@link UserUpdateResult} describing what should happen next: a redirect to
 * the recorded target program (the COBOL {@code XCTL}), or a re-display of the user-update screen
 * carrying a message. The message {@link MessageSeverity} preserves the COBOL colour contract of
 * {@code COUSR02C} exactly: red for errors and the "please modify" nudge ({@code DFHRED}), neutral
 * for the "press PF5 to save" prompt ({@code DFHNEUTR}), and green for the update-success line
 * ({@code DFHGREEN}), so the styling can be applied by the view without this service knowing how a
 * message is rendered.</p>
 *
 * <h2>Transaction boundary (AAP &sect;0.6.5)</h2>
 * <p>The COBOL {@code READ ... UPDATE} followed by {@code REWRITE} is a single unit of work.
 * {@link #updateUserInfo(COUSR02Form, CardDemoContext) updateUserInfo} &mdash; which reads, compares,
 * and (when modified) rewrites &mdash; is therefore annotated {@link Transactional}. Because the
 * controller drives an interaction through {@link #mainEntry(COUSR02Form, AidKey, String) mainEntry}
 * and Spring's declarative transactions apply only at proxied entry points (an in-class call such as
 * {@code mainEntry}&rarr;{@code updateUserInfo} does not re-enter the proxy), {@code mainEntry} is
 * <em>also</em> annotated {@link Transactional} so that an active transaction spans the internally
 * dispatched read-update-rewrite path; read-only paths simply commit an empty transaction. The CICS
 * {@code READ ... UPDATE} record lock is approximated by this transactional unit of work, as the
 * declared {@link UserSecurityRepository} contract does not expose a pessimistic-lock finder.</p>
 *
 * <h2>Access and design constraints</h2>
 * <ul>
 *   <li><b>Admin-only:</b> {@code COUSR02C} is reachable only by administrator users; that gate is
 *       enforced upstream by the admin-menu routing and Spring Security, not by this service.</li>
 *   <li><b>Cleartext password parity (AAP &sect;0.6.7 &mdash; intentional):</b> the edited password is
 *       carried and stored as cleartext to preserve the legacy {@code USRSEC} behaviour for 100%
 *       functional parity; no hashing is introduced here (recorded as a suggested next task in
 *       {@code docs/decision-log.md}). The password is never logged.</li>
 *   <li><b>Stateless singleton:</b> the COBOL {@code WORKING-STORAGE} flags ({@code WS-ERR-FLG},
 *       {@code WS-USR-MODIFIED}, {@code WS-MESSAGE}) become method-local state, so the service holds
 *       no mutable instance fields and is safe as a Spring singleton; all conversation state lives in
 *       the session-scoped {@link CardDemoContext}.</li>
 *   <li><b>Constructor injection:</b> the service depends only on the session context and the
 *       user-security repository, both {@code private final} and injected through the single
 *       constructor (no field injection, no {@code @Autowired}).</li>
 *   <li><b>Message literals:</b> the screen messages are declared as local constants that mirror the
 *       COBOL literals (the invalid-key text mirrors {@code CCDA-MSG-INVALID-KEY} from
 *       {@code legacy/cpy/CSMSG01Y.cpy}); the shared message-constants holder is not a declared
 *       dependency of this service, so its values are reproduced locally rather than imported.</li>
 * </ul>
 */
@Service
public class UserUpdateService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'} &mdash; this program's name. */
    private static final String PROGRAM_NAME = "COUSR02C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CU02'} &mdash; this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CU02";

    /**
     * Sign-on program (COBOL literal {@code 'COSGN00C'}) &mdash; the default hand-off target used by
     * {@code RETURN-TO-PREV-SCREEN} when no target is set, and the destination of the first-entry
     * ({@code EIBCALEN = 0}) bounce in {@code MAIN-PARA}.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Admin-menu program (COBOL literal {@code 'COADM01C'}) &mdash; the PF3 fallback target (when no
     * originating program is recorded) and the explicit PF12 return target of {@code MAIN-PARA}.
     */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /**
     * Empty-user-id message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code PROCESS-ENTER-KEY} and {@code UPDATE-USER-INFO} when {@code USRIDIN} is blank.
     */
    private static final String MSG_USERID_EMPTY = "User ID can NOT be empty...";

    /**
     * Empty-first-name message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code UPDATE-USER-INFO} when {@code FNAME} is blank.
     */
    private static final String MSG_FNAME_EMPTY = "First Name can NOT be empty...";

    /**
     * Empty-last-name message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code UPDATE-USER-INFO} when {@code LNAME} is blank.
     */
    private static final String MSG_LNAME_EMPTY = "Last Name can NOT be empty...";

    /**
     * Empty-password message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code UPDATE-USER-INFO} when {@code PASSWD} is blank.
     */
    private static final String MSG_PASSWD_EMPTY = "Password can NOT be empty...";

    /**
     * Empty-user-type message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code UPDATE-USER-INFO} when {@code USRTYPE} is blank.
     */
    private static final String MSG_USRTYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * "Press PF5 to save" prompt. Byte-exact COBOL literal moved to {@code WS-MESSAGE} with the
     * neutral attribute ({@code MOVE DFHNEUTR TO ERRMSGC}) after a successful look-up in
     * {@code READ-USER-SEC-FILE}, prompting the admin to confirm the edits.
     */
    private static final String MSG_PRESS_PF5 = "Press PF5 key to save your updates ...";

    /**
     * "Please modify" nudge. Byte-exact COBOL literal moved to {@code WS-MESSAGE} with the red
     * attribute ({@code MOVE DFHRED TO ERRMSGC}) in {@code UPDATE-USER-INFO} when the confirm action
     * changed nothing, so there is no update to apply.
     */
    private static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    /**
     * User-not-found message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} on the CICS
     * {@code NOTFND} response in {@code READ-USER-SEC-FILE} and {@code UPDATE-USER-SEC-FILE}.
     */
    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /**
     * Read-failure message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} on any non-normal,
     * non-not-found CICS response in {@code READ-USER-SEC-FILE} (the {@code WHEN OTHER} branch).
     */
    private static final String MSG_UNABLE_LOOKUP = "Unable to lookup User...";

    /**
     * Rewrite-failure message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} on any non-normal,
     * non-not-found CICS response in {@code UPDATE-USER-SEC-FILE} (the {@code WHEN OTHER} branch).
     */
    private static final String MSG_UNABLE_UPDATE = "Unable to Update User...";

    /**
     * Invalid-key message. Mirrors COBOL {@code CCDA-MSG-INVALID-KEY} (from
     * {@code legacy/cpy/CSMSG01Y.cpy}), moved to {@code WS-MESSAGE} in {@code MAIN-PARA} for any
     * unmapped AID key. Declared locally because the shared message-constants holder is not a declared
     * dependency of this service.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Fixed prefix of the update-success line (COBOL {@code STRING 'User ' ...}). */
    private static final String MSG_UPDATED_PREFIX = "User ";

    /** Fixed suffix of the update-success line (COBOL {@code STRING ... ' has been updated ...'}). */
    private static final String MSG_UPDATED_SUFFIX = " has been updated ...";

    /**
     * Session-scoped navigation and selection context, the modern replacement for the COBOL COMMAREA
     * ({@code COCOM01Y}). Injected as a Spring session-scoped proxy.
     */
    private final CardDemoContext context;

    /**
     * Spring Data repository over the {@code user_security} table, replacing the VSAM {@code USRSEC}
     * KSDS I/O of {@code READ-USER-SEC-FILE} and {@code UPDATE-USER-SEC-FILE}.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the user-update service via Spring constructor injection.
     *
     * <p>A single constructor means no {@code @Autowired} annotation is required. Neither argument is
     * dereferenced here, so the constructor introduces no {@code this}-escape.</p>
     *
     * @param context                the session-scoped CardDemo context (COMMAREA replacement); must
     *                               not be {@code null}
     * @param userSecurityRepository the user-security repository ({@code USRSEC} replacement); must
     *                               not be {@code null}
     */
    public UserUpdateService(CardDemoContext context, UserSecurityRepository userSecurityRepository) {
        this.context = context;
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * The subset of 3270 attention-identifier (AID) keys that {@code COUSR02C}'s {@code MAIN-PARA}
     * {@code EVALUATE EIBAID} distinguishes. The physical key press is decoded from the HTTP request
     * by the paired controller and passed to {@link #mainEntry(COUSR02Form, AidKey, String)} as one of
     * these values; every key the COBOL does not name individually collapses to {@link #OTHER},
     * mirroring the COBOL {@code WHEN OTHER} branch.
     */
    public enum AidKey {
        /** COBOL {@code DFHENTER} &mdash; process the entered user id / confirmation. */
        ENTER,
        /** COBOL {@code DFHPF3} &mdash; save any edits, then return to the previous screen. */
        PF3,
        /** COBOL {@code DFHPF4} &mdash; clear the current screen. */
        PF4,
        /** COBOL {@code DFHPF5} &mdash; save the edits (the explicit update key). */
        PF5,
        /** COBOL {@code DFHPF12} &mdash; return to the admin menu. */
        PF12,
        /** Any other key &mdash; the COBOL {@code WHEN OTHER} invalid-key branch. */
        OTHER
    }

    /**
     * The two mutually exclusive routing outcomes a business method can request, mirroring the COBOL
     * choice between transferring control to another program ({@code EXEC CICS XCTL}) and re-displaying
     * the current map ({@code SEND-USRUPD-SCREEN}).
     */
    public enum RoutingAction {
        /**
         * Transfer control to the program recorded on the context ({@link CardDemoContext#getToProgram()}).
         * Corresponds to the COBOL {@code RETURN-TO-PREV-SCREEN} / {@code XCTL} path; the controller
         * performs the physical redirect.
         */
        REDIRECT,
        /**
         * Re-display the user-update screen ({@code CU02}), typically carrying a message. Corresponds to
         * the COBOL {@code SEND-USRUPD-SCREEN} path; the controller renders the map.
         */
        SHOW_SCREEN
    }

    /**
     * The colour contract carried by a screen message, preserving the COBOL {@code ERRMSGC} attribute
     * assignments of {@code COUSR02C} so the view can style the message exactly as the 3270 map did.
     */
    public enum MessageSeverity {
        /** No message is present (COBOL {@code WS-MESSAGE} is spaces). */
        NONE,
        /** Error / validation text, rendered red (COBOL {@code DFHRED}). */
        ERROR,
        /** Informational prompt, rendered in the neutral attribute (COBOL {@code DFHNEUTR}). */
        NEUTRAL,
        /** Success confirmation, rendered green (COBOL {@code DFHGREEN}). */
        SUCCESS
    }

    /**
     * Immutable outcome of a {@code COUSR02C} business method: the routing action the controller must
     * take, the message to display, and the message's severity/colour. Modelled as a record because it
     * is a transparent, value-based carrier.
     *
     * <p>The canonical constructor rejects a {@code null} {@code action} or {@code severity} and
     * normalizes a {@code null} {@code message} to the empty string, so callers never observe a
     * {@code null} message (matching COBOL {@code WS-MESSAGE}, which is spaces when unset).</p>
     *
     * @param action   the routing action to take next; never {@code null}
     * @param message  the message to display; never {@code null} after construction (empty when absent)
     * @param severity the message colour/severity contract; never {@code null}
     */
    public record UserUpdateResult(RoutingAction action, String message, MessageSeverity severity) {

        /**
         * Canonical constructor enforcing the non-null invariants and normalizing the message.
         *
         * @param action   the routing action; must not be {@code null}
         * @param message  the message text; {@code null} is normalized to {@code ""}
         * @param severity the message severity; must not be {@code null}
         */
        public UserUpdateResult {
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
         * Creates a redirect outcome carrying no message (the COBOL {@code XCTL} path).
         *
         * @return a {@link RoutingAction#REDIRECT} result with an empty, {@link MessageSeverity#NONE}
         *         message
         */
        static UserUpdateResult redirect() {
            return new UserUpdateResult(RoutingAction.REDIRECT, "", MessageSeverity.NONE);
        }

        /**
         * Creates a plain re-display outcome carrying no message (the COBOL {@code SEND-USRUPD-SCREEN}
         * path with {@code WS-MESSAGE} spaces).
         *
         * @return a {@link RoutingAction#SHOW_SCREEN} result with an empty, {@link MessageSeverity#NONE}
         *         message
         */
        static UserUpdateResult showScreen() {
            return new UserUpdateResult(RoutingAction.SHOW_SCREEN, "", MessageSeverity.NONE);
        }

        /**
         * Creates a re-display outcome carrying a red error/validation message (COBOL {@code DFHRED}).
         *
         * @param message the error text to display
         * @return a {@link RoutingAction#SHOW_SCREEN} result with an {@link MessageSeverity#ERROR} message
         */
        static UserUpdateResult error(String message) {
            return new UserUpdateResult(RoutingAction.SHOW_SCREEN, message, MessageSeverity.ERROR);
        }

        /**
         * Creates a re-display outcome carrying a neutral informational message (COBOL {@code DFHNEUTR}).
         *
         * @param message the informational text to display
         * @return a {@link RoutingAction#SHOW_SCREEN} result with a {@link MessageSeverity#NEUTRAL} message
         */
        static UserUpdateResult neutral(String message) {
            return new UserUpdateResult(RoutingAction.SHOW_SCREEN, message, MessageSeverity.NEUTRAL);
        }

        /**
         * Creates a re-display outcome carrying a green success message (COBOL {@code DFHGREEN}).
         *
         * @param message the success text to display
         * @return a {@link RoutingAction#SHOW_SCREEN} result with a {@link MessageSeverity#SUCCESS} message
         */
        static UserUpdateResult success(String message) {
            return new UserUpdateResult(RoutingAction.SHOW_SCREEN, message, MessageSeverity.SUCCESS);
        }

        /**
         * @return {@code true} when the controller should transfer control to
         *         {@link CardDemoContext#getToProgram()} (COBOL {@code XCTL})
         */
        public boolean isRedirect() {
            return action == RoutingAction.REDIRECT;
        }

        /**
         * @return {@code true} when a non-empty message should be displayed on the re-shown screen
         */
        public boolean hasMessage() {
            return !message.isEmpty();
        }

        /**
         * @return {@code true} when the carried message is an error/validation message
         *         (COBOL {@code DFHRED})
         */
        public boolean isError() {
            return severity == MessageSeverity.ERROR;
        }
    }

    /**
     * Transaction entry point &mdash; the Java migration of COBOL paragraph {@code MAIN-PARA}
     * ({@code legacy/cbl/COUSR02C.cbl}). Reproduces the {@code EIBCALEN}/{@code CDEMO-PGM-CONTEXT}
     * pseudo-conversational state machine and the {@code EVALUATE EIBAID} key dispatch one-for-one.
     *
     * <p>The COBOL working-storage flags reset at the top of the paragraph ({@code SET ERR-FLG-OFF},
     * {@code SET USR-MODIFIED-NO}) become method-local behaviour: no error flag is retained because a
     * message is carried out through the returned {@link UserUpdateResult}, and the modified flag is a
     * local of {@link #updateUserInfo(COUSR02Form, CardDemoContext) updateUserInfo}. Control flow:</p>
     * <ol>
     *   <li><b>First entry</b> ({@code EIBCALEN = 0} &rarr; {@link CardDemoContext#isNew()}): set the
     *       hand-off target to {@code COSGN00C} and return to the previous screen &mdash; the COBOL
     *       {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} / {@code PERFORM RETURN-TO-PREV-SCREEN}.</li>
     *   <li><b>First display of this program</b> ({@code NOT CDEMO-PGM-REENTER} &rarr;
     *       {@link CardDemoContext#isProgramEnter()}): mark re-enter; if a user id was selected on the
     *       list ({@code CDEMO-CU02-USR-SELECTED}, supplied as {@code selectedUserId}) pre-load it into
     *       {@code USRIDIN} and run {@link #processEnterKey(COUSR02Form, CardDemoContext) processEnterKey}
     *       to look the user up; then show the screen.</li>
     *   <li><b>Re-entry:</b> the controller has already received the map, so dispatch on the AID key:
     *       {@code ENTER}&rarr;{@code processEnterKey}; {@code PF3}&rarr;attempt the update then route
     *       back to the originating program (or {@code COADM01C} when none is recorded);
     *       {@code PF4}&rarr;{@link #clearCurrentScreen(COUSR02Form) clearCurrentScreen};
     *       {@code PF5}&rarr;{@link #updateUserInfo(COUSR02Form, CardDemoContext) updateUserInfo};
     *       {@code PF12}&rarr;route back to {@code COADM01C}; any other key&rarr;invalid-key error.</li>
     * </ol>
     *
     * <p>This method is {@link Transactional} so that the read-update-rewrite path it dispatches to
     * internally (an in-class call that does not re-enter the transactional proxy) executes inside an
     * active transaction (see the class Javadoc). On the {@code PF3} branch the result of the update is
     * intentionally discarded: the COBOL performs {@code UPDATE-USER-INFO} and then unconditionally
     * {@code XCTL}s away, so the redirect supersedes whatever the update would have displayed.</p>
     *
     * @param form           the user-update screen model ({@code COUSR2AI}/{@code COUSR2AO}); the
     *                       controller has populated it from the request on re-entry. Must not be
     *                       {@code null}.
     * @param aid            the decoded attention key for this invocation; ignored on first display.
     *                       Must not be {@code null}.
     * @param selectedUserId the user id chosen on the list screen ({@code CDEMO-CU02-USR-SELECTED});
     *                       may be {@code null}/blank when the screen is entered directly.
     * @return the routing-and-message outcome for the controller to act on; never {@code null}
     */
    @Transactional
    public UserUpdateResult mainEntry(COUSR02Form form, AidKey aid, String selectedUserId) {
        // IF EIBCALEN = 0 -> no COMMAREA: bounce to the sign-on program.
        if (context.isNew()) {
            context.setToProgram(SIGNON_PROGRAM);
            return returnToPrevScreen(context);
        }

        // IF NOT CDEMO-PGM-REENTER -> first display of COUSR02C within this conversation.
        if (context.isProgramEnter()) {
            // SET CDEMO-PGM-REENTER TO TRUE.
            context.markReenter();
            // IF CDEMO-CU02-USR-SELECTED NOT = SPACES AND NOT = LOW-VALUES
            //     MOVE CDEMO-CU02-USR-SELECTED TO USRIDINI, PERFORM PROCESS-ENTER-KEY.
            if (!isBlankOrLowValues(selectedUserId)) {
                form.setUsridin(rtrim(selectedUserId));
                return processEnterKey(form, context);
            }
            // PERFORM SEND-USRUPD-SCREEN (blank screen).
            return UserUpdateResult.showScreen();
        }

        // ELSE re-entry: RECEIVE-USRUPD-SCREEN already done by the controller; EVALUATE EIBAID.
        return switch (aid) {
            case ENTER -> processEnterKey(form, context);
            case PF3 -> {
                // PERFORM UPDATE-USER-INFO, then route back regardless (XCTL follows the SEND).
                updateUserInfo(form, context);
                String target = isBlankOrLowValues(context.getFromProgram())
                        ? ADMIN_MENU_PROGRAM
                        : context.getFromProgram();
                context.setToProgram(target);
                yield returnToPrevScreen(context);
            }
            case PF4 -> clearCurrentScreen(form);
            case PF5 -> updateUserInfo(form, context);
            case PF12 -> {
                context.setToProgram(ADMIN_MENU_PROGRAM);
                yield returnToPrevScreen(context);
            }
            case OTHER -> UserUpdateResult.error(MSG_INVALID_KEY);
        };
    }

    /**
     * State portion of COBOL paragraph {@code RETURN-TO-PREV-SCREEN} ({@code legacy/cbl/COUSR02C.cbl}).
     *
     * <p>Reproduces the COMMAREA hand-off the COBOL sets immediately before {@code EXEC CICS XCTL}:
     * defaulting an unset target to the sign-on program, stamping this program/transaction as the
     * "from" origin, and resetting the program context to the enter state ({@code MOVE ZEROS TO
     * CDEMO-PGM-CONTEXT}) so the target program treats its next invocation as a first entry. The
     * physical {@code XCTL} transfer is performed by the controller when it observes a redirect
     * outcome; this helper only prepares the context and signals the redirect.</p>
     *
     * @param ctx the session context to update; must not be {@code null}
     * @return a {@link RoutingAction#REDIRECT} result targeting {@link CardDemoContext#getToProgram()}
     */
    private UserUpdateResult returnToPrevScreen(CardDemoContext ctx) {
        // IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES -> MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
        if (isBlankOrLowValues(ctx.getToProgram())) {
            ctx.setToProgram(SIGNON_PROGRAM);
        }
        // MOVE WS-TRANID TO CDEMO-FROM-TRANID.
        ctx.setFromTranid(TRANSACTION_ID);
        // MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM.
        ctx.setFromProgram(PROGRAM_NAME);
        // MOVE ZEROS TO CDEMO-PGM-CONTEXT (reset to enter state for the target program).
        ctx.markEnter();
        // EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) -> controller performs the physical redirect.
        return UserUpdateResult.redirect();
    }

    /**
     * Java migration of COBOL paragraph {@code PROCESS-ENTER-KEY} ({@code legacy/cbl/COUSR02C.cbl}):
     * validate the entered user id and, when present, look the user up and load the editable fields.
     *
     * <p>Reproduces the paragraph's {@code EVALUATE TRUE} guard and the two {@code IF NOT ERR-FLG-ON}
     * blocks: a blank {@code USRIDIN} yields the "User ID can NOT be empty..." error; otherwise the
     * editable fields ({@code FNAME}, {@code LNAME}, {@code PASSWD}, {@code USRTYPE}) are cleared, the
     * record is read ({@link #readUserSecFile(String)}), and on a successful read those fields are
     * populated from the record and the neutral "Press PF5 key to save your updates ..." prompt is
     * shown &mdash; the COBOL {@code DFHNEUTR} message set by the read's {@code NORMAL} branch. A
     * not-found read yields "User ID NOT found..." and any other read failure yields "Unable to lookup
     * User...", matching the read paragraph's {@code NOTFND}/{@code WHEN OTHER} branches; in both error
     * cases the editable fields remain cleared, exactly as the COBOL leaves them.</p>
     *
     * <p>The password is copied verbatim (cleartext) into the form for editing, preserving the legacy
     * {@code USRSEC} behaviour (AAP &sect;0.6.7).</p>
     *
     * @param form the user-update screen model; must not be {@code null}
     * @param ctx  the session context (unused by this paragraph but passed for control-flow symmetry
     *             with the COBOL, which reads the shared COMMAREA); must not be {@code null}
     * @return the outcome to display: an error, or the neutral save prompt with the fields loaded
     */
    UserUpdateResult processEnterKey(COUSR02Form form, CardDemoContext ctx) {
        // WHEN USRIDINI = SPACES OR LOW-VALUES -> "User ID can NOT be empty...".
        if (isBlankOrLowValues(form.getUsridin())) {
            return UserUpdateResult.error(MSG_USERID_EMPTY);
        }

        // IF NOT ERR-FLG-ON: clear the editable fields, then read the record.
        form.setFname("");
        form.setLname("");
        form.setPasswd("");
        form.setUsrtype("");

        try {
            UserSecurity user = readUserSecFile(rtrim(form.getUsridin()));
            // READ NORMAL: populate the editable fields and prompt to save (DFHNEUTR).
            form.setFname(user.getUsrFname());
            form.setLname(user.getUsrLname());
            form.setPasswd(user.getUsrPwd());
            form.setUsrtype(user.getUsrType());
            return UserUpdateResult.neutral(MSG_PRESS_PF5);
        } catch (RecordNotFoundException notFound) {
            // READ NOTFND: "User ID NOT found..."; editable fields stay cleared.
            return UserUpdateResult.error(MSG_USER_NOT_FOUND);
        } catch (DataAccessException dataError) {
            // READ WHEN OTHER: "Unable to lookup User..."; editable fields stay cleared.
            return UserUpdateResult.error(MSG_UNABLE_LOOKUP);
        }
    }

    /**
     * Java migration of COBOL paragraph {@code UPDATE-USER-INFO} ({@code legacy/cbl/COUSR02C.cbl}):
     * validate every edited field, read the current record, apply only the changed fields, and rewrite
     * the record when something actually changed.
     *
     * <p>The paragraph's {@code EVALUATE TRUE} validation ladder is preserved in order &mdash;
     * {@code USRIDIN}, {@code FNAME}, {@code LNAME}, {@code PASSWD}, {@code USRTYPE} &mdash; each blank
     * field short-circuiting with its own "... can NOT be empty..." error. When all fields are present
     * the record is read; a not-found read yields "User ID NOT found..." and any other read failure
     * yields "Unable to lookup User..." (the observable outcome of the read paragraph's error branches).
     * Each field is then compared against the stored value with fixed-width ({@code PIC X(n)})
     * trailing-space-insensitive semantics ({@link #fieldsEqual(String, String)}); a difference copies
     * the new value onto the entity and sets the modified flag (the COBOL {@code SET USR-MODIFIED-YES}).
     * If anything changed the record is rewritten ({@link #updateUserSecFile(UserSecurity)}); otherwise
     * the red "Please modify to update ..." nudge is shown ({@code DFHRED}).</p>
     *
     * <p>This is the {@link Transactional} read-update-rewrite unit of work &mdash; the COBOL
     * {@code READ ... UPDATE} plus {@code REWRITE} (AAP &sect;0.6.5). The read returns a managed entity
     * and the rewrite occurs within the same transaction, so the CICS record lock is approximated by
     * the transaction boundary. The password is compared and copied as cleartext (AAP &sect;0.6.7).</p>
     *
     * @param form the user-update screen model carrying the edited fields; must not be {@code null}
     * @param ctx  the session context (passed for symmetry with the COBOL COMMAREA usage); must not be
     *             {@code null}
     * @return the outcome to display: a validation error, a lookup error, the green success line, or
     *         the "please modify" nudge
     */
    @Transactional
    UserUpdateResult updateUserInfo(COUSR02Form form, CardDemoContext ctx) {
        // EVALUATE TRUE validation ladder (short-circuits on the first blank field, in COBOL order).
        if (isBlankOrLowValues(form.getUsridin())) {
            return UserUpdateResult.error(MSG_USERID_EMPTY);
        }
        if (isBlankOrLowValues(form.getFname())) {
            return UserUpdateResult.error(MSG_FNAME_EMPTY);
        }
        if (isBlankOrLowValues(form.getLname())) {
            return UserUpdateResult.error(MSG_LNAME_EMPTY);
        }
        if (isBlankOrLowValues(form.getPasswd())) {
            return UserUpdateResult.error(MSG_PASSWD_EMPTY);
        }
        if (isBlankOrLowValues(form.getUsrtype())) {
            return UserUpdateResult.error(MSG_USRTYPE_EMPTY);
        }

        // IF NOT ERR-FLG-ON: read the record for update.
        final UserSecurity user;
        try {
            user = readUserSecFile(rtrim(form.getUsridin()));
        } catch (RecordNotFoundException notFound) {
            return UserUpdateResult.error(MSG_USER_NOT_FOUND);
        } catch (DataAccessException dataError) {
            return UserUpdateResult.error(MSG_UNABLE_LOOKUP);
        }

        // Compare each field; on a difference apply the edit and flag the record modified.
        boolean modified = false;
        // IF FNAMEI NOT = SEC-USR-FNAME.
        if (!fieldsEqual(form.getFname(), user.getUsrFname())) {
            user.setUsrFname(form.getFname());
            modified = true;
        }
        // IF LNAMEI NOT = SEC-USR-LNAME.
        if (!fieldsEqual(form.getLname(), user.getUsrLname())) {
            user.setUsrLname(form.getLname());
            modified = true;
        }
        // IF PASSWDI NOT = SEC-USR-PWD (cleartext parity).
        if (!fieldsEqual(form.getPasswd(), user.getUsrPwd())) {
            user.setUsrPwd(form.getPasswd());
            modified = true;
        }
        // IF USRTYPEI NOT = SEC-USR-TYPE.
        if (!fieldsEqual(form.getUsrtype(), user.getUsrType())) {
            user.setUsrType(form.getUsrtype());
            modified = true;
        }

        // IF USR-MODIFIED-YES PERFORM UPDATE-USER-SEC-FILE ELSE 'Please modify to update ...'.
        if (modified) {
            return updateUserSecFile(user);
        }
        return UserUpdateResult.error(MSG_PLEASE_MODIFY);
    }

    /**
     * Java migration of COBOL paragraph {@code READ-USER-SEC-FILE} ({@code legacy/cbl/COUSR02C.cbl}):
     * the keyed {@code EXEC CICS READ ... UPDATE} against the {@code USRSEC} store.
     *
     * <p>Maps the COBOL {@code EVALUATE WS-RESP-CD} to typed outcomes (AAP &sect;0.6.5): the
     * {@code NORMAL} branch returns the record; the {@code NOTFND} branch (CICS {@code NOTFND} /
     * {@code FILE STATUS} 13/23) throws {@link RecordNotFoundException} carrying the "User ID NOT
     * found..." text; and any {@code WHEN OTHER} failure surfaces as the repository's
     * {@link DataAccessException}, which callers translate to the "Unable to lookup User..." message.
     * Callers ({@link #processEnterKey(COUSR02Form, CardDemoContext) processEnterKey},
     * {@link #updateUserInfo(COUSR02Form, CardDemoContext) updateUserInfo}) catch these to keep the
     * conversation on the {@code CU02} screen, faithful to the COBOL, which sends the error inline and
     * does not transfer control.</p>
     *
     * @param userId the user id to read ({@code SEC-USR-ID} / {@code RIDFLD}); must not be {@code null}
     * @return the located {@link UserSecurity} record (never {@code null})
     * @throws RecordNotFoundException when no user with the given id exists (CICS {@code NOTFND})
     */
    UserSecurity readUserSecFile(String userId) {
        // EXEC CICS READ DATASET(USRSEC) RIDFLD(SEC-USR-ID) UPDATE; NOTFND -> RecordNotFoundException.
        return userSecurityRepository.findByUsrId(userId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_USER_NOT_FOUND));
    }

    /**
     * Java migration of COBOL paragraph {@code UPDATE-USER-SEC-FILE} ({@code legacy/cbl/COUSR02C.cbl}):
     * the {@code EXEC CICS REWRITE} of the edited {@code USRSEC} record.
     *
     * <p>Maps the COBOL {@code EVALUATE WS-RESP-CD}: {@code NORMAL} produces the green ({@code DFHGREEN})
     * success line built by {@link #buildUpdatedMessage(String)} (the COBOL {@code STRING} of
     * {@code 'User ' SEC-USR-ID ' has been updated ...'}); any {@code WHEN OTHER} failure yields the
     * "Unable to Update User..." error. The COBOL {@code NOTFND} branch on the rewrite is unreachable
     * in this JPA model: the entity was just read within the same transaction
     * ({@link #updateUserInfo(COUSR02Form, CardDemoContext) updateUserInfo}) and is managed, so
     * {@code save} performs an update on an existing row rather than a keyed rewrite that could miss.
     * That intentional, behaviour-preserving simplification is recorded in {@code docs/decision-log.md}.</p>
     *
     * @param user the edited, managed user-security entity to persist; must not be {@code null}
     * @return a green success outcome, or an "Unable to Update User..." error on a persistence failure
     */
    UserUpdateResult updateUserSecFile(UserSecurity user) {
        try {
            // EXEC CICS REWRITE DATASET(USRSEC) FROM(SEC-USER-DATA).
            userSecurityRepository.save(user);
            // WHEN NORMAL: green success line.
            return UserUpdateResult.success(buildUpdatedMessage(user.getUsrId()));
        } catch (DataAccessException dataError) {
            // WHEN OTHER: "Unable to Update User...".
            return UserUpdateResult.error(MSG_UNABLE_UPDATE);
        }
    }

    /**
     * Java migration of COBOL paragraph {@code CLEAR-CURRENT-SCREEN} ({@code legacy/cbl/COUSR02C.cbl}):
     * reset every field and re-display the empty user-update screen (the COBOL {@code PF4} action).
     *
     * <p>Performs {@link #initializeAllFields(COUSR02Form)} then requests a plain re-display &mdash; the
     * COBOL {@code PERFORM INITIALIZE-ALL-FIELDS} / {@code PERFORM SEND-USRUPD-SCREEN}. No message is
     * carried because the initialize step clears {@code WS-MESSAGE}.</p>
     *
     * @param form the user-update screen model to reset; must not be {@code null}
     * @return a plain {@link RoutingAction#SHOW_SCREEN} outcome with no message
     */
    UserUpdateResult clearCurrentScreen(COUSR02Form form) {
        // PERFORM INITIALIZE-ALL-FIELDS.
        initializeAllFields(form);
        // PERFORM SEND-USRUPD-SCREEN (blank screen, no message).
        return UserUpdateResult.showScreen();
    }

    /**
     * Java migration of COBOL paragraph {@code INITIALIZE-ALL-FIELDS} ({@code legacy/cbl/COUSR02C.cbl}):
     * blank the user id, the four editable fields, and the message line.
     *
     * <p>Mirrors {@code MOVE SPACES TO USRIDINI FNAMEI LNAMEI PASSWDI USRTYPEI WS-MESSAGE}. The COBOL
     * {@code MOVE -1 TO USRIDINL} is cursor positioning &mdash; a 3270/BMS presentation concern owned by
     * the controller &mdash; and so is deliberately not reproduced here.</p>
     *
     * @param form the user-update screen model to clear; must not be {@code null}
     */
    void initializeAllFields(COUSR02Form form) {
        // MOVE SPACES TO USRIDINI FNAMEI LNAMEI PASSWDI USRTYPEI WS-MESSAGE.
        form.setUsridin("");
        form.setFname("");
        form.setLname("");
        form.setPasswd("");
        form.setUsrtype("");
        form.setErrmsg("");
    }

    /**
     * Tests whether a screen field is the COBOL equivalent of {@code SPACES OR LOW-VALUES}.
     *
     * <p>A {@code null} reference stands in for an absent / {@code LOW-VALUES} field; otherwise the
     * value is blank when every character is a space ({@code 0x20}) or a NUL ({@code LOW-VALUES},
     * {@code 0x00}). This is the exact predicate the COBOL {@code EVALUATE}/{@code IF} guards apply to
     * the input fields.</p>
     *
     * @param value the field value to test; may be {@code null}
     * @return {@code true} when the value is absent, empty, or consists solely of spaces/low-values
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
     * Returns the significant characters of a fixed-width COBOL {@code PIC X(n)} field by stripping
     * trailing spaces and low-values ({@code 0x00}).
     *
     * <p>Used to key on and compare fixed-width fields the way COBOL does, where trailing padding is
     * not semantically significant. A {@code null} input yields the empty string.</p>
     *
     * @param value the raw field value; may be {@code null}
     * @return the value with trailing spaces/low-values removed (never {@code null})
     */
    private static String rtrim(String value) {
        if (value == null) {
            return "";
        }
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c != ' ' && c != '\0') {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Compares two fixed-width COBOL {@code PIC X(n)} fields for equality.
     *
     * <p>COBOL compares two same-length {@code PIC X(n)} items byte-for-byte, both space-padded to the
     * declared length, so trailing spaces never make two otherwise-equal values differ. This reproduces
     * that by comparing the values after {@link #rtrim(String) trimming} their trailing padding, which
     * is the correct semantics for the {@code IF fieldI NOT = SEC-USR-field} tests in
     * {@code UPDATE-USER-INFO}.</p>
     *
     * @param left  the first field value; may be {@code null}
     * @param right the second field value; may be {@code null}
     * @return {@code true} when the two fields are equal ignoring trailing padding
     */
    private static boolean fieldsEqual(String left, String right) {
        return rtrim(left).equals(rtrim(right));
    }

    /**
     * Builds the update-success line for {@code UPDATE-USER-SEC-FILE}'s {@code NORMAL} branch,
     * reproducing the COBOL {@code STRING} statement exactly.
     *
     * <p>The COBOL is {@code STRING 'User ' DELIMITED BY SIZE, SEC-USR-ID DELIMITED BY SPACE,
     * ' has been updated ...' DELIMITED BY SIZE INTO WS-MESSAGE}. {@code DELIMITED BY SPACE} on the id
     * means only the characters up to (but not including) the first space are appended &mdash; i.e. the
     * user id with its fixed-width trailing padding removed &mdash; producing, for id {@code "USER0001"},
     * the message {@code "User USER0001 has been updated ..."}.</p>
     *
     * @param userId the user id whose record was updated ({@code SEC-USR-ID}); may be {@code null}
     * @return the composed success message
     */
    private static String buildUpdatedMessage(String userId) {
        String id = userId == null ? "" : userId;
        int firstSpace = id.indexOf(' ');
        String token = firstSpace >= 0 ? id.substring(0, firstSpace) : id;
        return MSG_UPDATED_PREFIX + token + MSG_UPDATED_SUFFIX;
    }
}
