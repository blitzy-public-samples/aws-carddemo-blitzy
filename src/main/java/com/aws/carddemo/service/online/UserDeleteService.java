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

import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COUSR03Form;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * Administrative user-delete online service, the Java migration of the CICS COBOL program
 * {@code COUSR03C} (the AWS CardDemo "Delete a user from USRSEC file" screen).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COUSR03C.cbl} &mdash; program {@code COUSR03C},
 * CICS transaction id {@code CU03}. This service preserves the original program's control flow
 * one-for-one: each business COBOL paragraph becomes exactly one Java method (AAP &sect;0.3.3,
 * &sect;0.4.1). It is the READ-then-DELETE archetype over the {@code USRSEC} store, so the
 * read-and-delete path is executed inside a database transaction (AAP &sect;0.6.5).</p>
 *
 * <h2>Two-step confirm (the defining behavior)</h2>
 * <p>Deletion is a deliberate two-step interaction, preserved exactly from the COBOL:</p>
 * <ol>
 *   <li><b>ENTER</b> ({@link #processEnterKey(COUSR03Form, CardDemoContext)}) &mdash; looks the
 *       user up and populates the first name, last name and user type for confirmation, then
 *       shows the neutral prompt {@code "Press PF5 key to delete this user ..."}. Nothing is
 *       deleted.</li>
 *   <li><b>PF5</b> ({@link #deleteUserInfo(COUSR03Form, CardDemoContext)}) &mdash; performs the
 *       actual delete and reports success in green.</li>
 * </ol>
 * <p>The screen is reached from the user list ({@code COUSR00C}) via a {@code 'D'} selection,
 * which places the chosen user id in the COBOL {@code CDEMO-CU03-USR-SELECTED} field. That base
 * COMMAREA carries no {@code CU03}-specific slot in the migrated {@link CardDemoContext}, so the
 * selection is carried forward by the paired controller pre-populating the form's
 * {@link COUSR03Form#getUsridin() user-id} field; on first display this service then performs the
 * confirmation lookup automatically (see {@link #mainEntry(AidKey, COUSR03Form)}).</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #mainEntry(AidKey, COUSR03Form)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(COUSR03Form, CardDemoContext)}</li>
 *   <li>{@code DELETE-USER-INFO} &rarr; {@link #deleteUserInfo(COUSR03Form, CardDemoContext)}</li>
 *   <li>{@code READ-USER-SEC-FILE} &rarr; {@link #readUserSecFile(String)}</li>
 *   <li>{@code DELETE-USER-SEC-FILE} &rarr; {@link #deleteUserSecFile(UserSecurity, COUSR03Form)}</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr; {@link #clearCurrentScreen(COUSR03Form)}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr; {@link #initializeAllFields(COUSR03Form)}</li>
 * </ul>
 *
 * <p>The presentation paragraphs {@code SEND-USRDEL-SCREEN}, {@code RECEIVE-USRDEL-SCREEN},
 * {@code POPULATE-HEADER-INFO} and {@code RETURN-TO-PREV-SCREEN} are intentionally <em>not</em>
 * implemented here: sending/receiving the 3270/BMS map ({@code COUSR3A} of mapset {@code COUSR03}),
 * populating the header (title/date/time), and performing the actual {@code XCTL} navigation are
 * presentation concerns owned by the paired {@code UserAdminController}. This service contains
 * business logic only &mdash; it validates input, reads and deletes the record, decides routing,
 * and produces messages &mdash; and never performs screen I/O.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) that carried navigation state across CICS returns
 * becomes the session-scoped {@link CardDemoContext}, injected here. First entry into the
 * transaction (COBOL {@code EIBCALEN = 0}) is reproduced by {@link CardDemoContext#isNew()}; the
 * within-program first-display versus re-entry state machine
 * ({@code CDEMO-PGM-CONTEXT}: {@code 0} = enter, {@code 1} = re-enter) is reproduced by
 * {@link CardDemoContext#isProgramEnter()} / {@link CardDemoContext#markReenter()}. The
 * {@code XCTL} / {@code RETURN TRANSID} hand-off is reproduced through the context's
 * {@code to*}/{@code from*} program fields, which the controller consults when redirecting.</p>
 *
 * <h2>Outcome model</h2>
 * <p>Because the controller performs the redirect and the screen rendering, each business method
 * returns an immutable {@link UserDeleteResult} describing what should happen next: transfer control
 * to another program (the COBOL {@code XCTL}), or re-display the delete screen with a message. The
 * message carries a {@link MessageSeverity} that preserves the COBOL color contract exactly &mdash;
 * an error line (default red, driven by {@code WS-ERR-FLG}), the neutral confirm prompt
 * ({@code MOVE DFHNEUTR TO ERRMSGC}), or the green success line ({@code MOVE DFHGREEN TO ERRMSGC}).
 * The redirect destination is written to {@link CardDemoContext#getToProgram()} and read back by the
 * controller, so {@link UserDeleteResult} itself carries no program name.</p>
 *
 * <h2>Exception parity (AAP &sect;0.6.5)</h2>
 * <p>The COBOL {@code EVALUATE WS-RESP-CD} branches map as follows: a {@code NOTFND} read (CICS
 * {@code RESP} 13, COBOL FILE STATUS 13/23) raises {@link RecordNotFoundException}, which the online
 * {@code @ControllerAdvice} handler surfaces as the {@code "User ID NOT found..."} error line; a
 * genuine {@code WHEN OTHER} I/O failure surfaces as a Spring {@link DataAccessException}, which this
 * service catches to reproduce the exact COBOL {@code "Unable to ..."} message on the re-displayed
 * screen without aborting. Because {@link RecordNotFoundException} is <em>not</em> a
 * {@link DataAccessException}, the {@code OTHER} catch never swallows a not-found signal.</p>
 *
 * <h2>Access and design constraints</h2>
 * <ul>
 *   <li><b>Admin-only:</b> {@code COUSR03C} is reachable only by administrator users; that gate is
 *       enforced upstream by Spring Security URL/method authorization, not by this service.</li>
 *   <li><b>Constructor injection:</b> the service depends only on the session context and the
 *       user-security repository; a single constructor means no {@code @Autowired} is required.</li>
 *   <li><b>Message literals:</b> the user-facing messages are declared as local constants that
 *       mirror the COBOL literals byte-for-byte (the invalid-key text mirrors
 *       {@code CCDA-MSG-INVALID-KEY} from {@code legacy/cpy/CSMSG01Y.cpy}); the shared
 *       message-constants holder is not a declared dependency of this service, so its values are
 *       reproduced locally rather than imported.</li>
 * </ul>
 */
@Service
public class UserDeleteService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'} &mdash; this program's name. */
    private static final String PROGRAM_NAME = "COUSR03C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CU03'} &mdash; this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CU03";

    /**
     * Sign-on program (COBOL literal {@code 'COSGN00C'}) &mdash; the hand-off target when the
     * transaction is entered with no COMMAREA ({@code EIBCALEN = 0}), and the ultimate default of
     * {@code RETURN-TO-PREV-SCREEN}.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Admin-menu program (COBOL literal {@code 'COADM01C'}) &mdash; the PF3 fallback target (when no
     * originating program is recorded) and the fixed PF12 target.
     */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /**
     * Empty-user-id message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code PROCESS-ENTER-KEY} and {@code DELETE-USER-INFO} when {@code USRIDINI} is blank.
     */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * Neutral delete-confirmation prompt. Reproduces the COBOL {@code MOVE '...' TO WS-MESSAGE} with
     * {@code MOVE DFHNEUTR TO ERRMSGC} set on a successful {@code READ-USER-SEC-FILE}; rendered as a
     * neutral line.
     */
    private static final String MSG_PRESS_PF5_TO_DELETE = "Press PF5 key to delete this user ...";

    /**
     * User-not-found message. Byte-exact COBOL literal for the {@code NOTFND} branch of both
     * {@code READ-USER-SEC-FILE} and {@code DELETE-USER-SEC-FILE}.
     */
    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    /**
     * Read-failure message. Byte-exact COBOL literal for the {@code WHEN OTHER} branch of
     * {@code READ-USER-SEC-FILE}.
     */
    private static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    /**
     * Delete-failure message. Byte-exact COBOL literal for the {@code WHEN OTHER} branch of
     * {@code DELETE-USER-SEC-FILE} (note the original's capitalized {@code "Update"}).
     */
    private static final String MSG_UNABLE_TO_UPDATE = "Unable to Update User...";

    /**
     * Invalid-key message. Mirrors COBOL {@code CCDA-MSG-INVALID-KEY} (from
     * {@code legacy/cpy/CSMSG01Y.cpy}), moved to {@code WS-MESSAGE} in {@code MAIN-PARA} for any
     * unmapped AID key. Declared locally because the shared message-constants holder is not a
     * declared dependency of this service.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Leading literal of the delete-success message. Reproduces the first operand of the COBOL
     * {@code STRING 'User ' DELIMITED BY SIZE ...} in {@code DELETE-USER-SEC-FILE}.
     */
    private static final String MSG_DELETED_PREFIX = "User ";

    /**
     * Trailing literal of the delete-success message. Reproduces the third operand of the COBOL
     * {@code STRING ... ' has been deleted ...' DELIMITED BY SIZE} in {@code DELETE-USER-SEC-FILE}.
     */
    private static final String MSG_DELETED_SUFFIX = " has been deleted ...";

    /**
     * Session-scoped navigation and selection context, the modern replacement for the COBOL COMMAREA
     * ({@code COCOM01Y}). Injected as a Spring session-scoped proxy.
     */
    private final CardDemoContext context;

    /**
     * Spring Data repository over the {@code USRSEC} store, replacing the COBOL
     * {@code EXEC CICS READ ... UPDATE} / {@code EXEC CICS DELETE} against the VSAM KSDS.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the user-delete service via Spring constructor injection.
     *
     * <p>A single constructor means no {@code @Autowired} annotation is required. Neither argument is
     * dereferenced here, so the constructor introduces no {@code this}-escape.</p>
     *
     * @param context                the session-scoped CardDemo context (COMMAREA replacement); must
     *                               not be {@code null}
     * @param userSecurityRepository the {@code USRSEC} repository used for the read-then-delete; must
     *                               not be {@code null}
     */
    public UserDeleteService(CardDemoContext context, UserSecurityRepository userSecurityRepository) {
        this.context = context;
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Attention-identifier (AID) keys handled by {@link #mainEntry(AidKey, COUSR03Form)}, mirroring
     * the COBOL {@code EVALUATE EIBAID} of {@code MAIN-PARA}.
     *
     * <p>The paired {@code UserAdminController} maps the inbound HTTP submission (the pressed button
     * or PF key) to one of these constants before delegating to the service. Every key with distinct
     * behavior in {@code COUSR03C} is modeled explicitly; any other key collapses to {@link #OTHER},
     * matching the COBOL {@code WHEN OTHER} default.</p>
     */
    public enum AidKey {

        /** COBOL {@code DFHENTER} &mdash; the ENTER key; looks up and displays the user. */
        ENTER,

        /** COBOL {@code DFHPF3} &mdash; the PF3 key; returns to the originating (or admin) screen. */
        PF3,

        /** COBOL {@code DFHPF4} &mdash; the PF4 key; clears the current screen. */
        PF4,

        /** COBOL {@code DFHPF5} &mdash; the PF5 key; performs the actual delete. */
        PF5,

        /** COBOL {@code DFHPF12} &mdash; the PF12 key; returns to the admin menu. */
        PF12,

        /** COBOL {@code WHEN OTHER} &mdash; any other key; yields the invalid-key message. */
        OTHER
    }

    /**
     * Routing decision returned by the business methods, modeling the two terminal actions of
     * {@code MAIN-PARA}: transfer control ({@code EXEC CICS XCTL}, via {@code RETURN-TO-PREV-SCREEN})
     * or re-display the delete screen ({@code SEND-USRDEL-SCREEN}).
     */
    public enum RoutingAction {

        /**
         * Hand off to the program recorded in {@link CardDemoContext#getToProgram()} (the COBOL
         * {@code XCTL}); the controller performs the redirect.
         */
        REDIRECT,

        /**
         * Re-display the user-delete screen (the COBOL {@code SEND-USRDEL-SCREEN}); the controller
         * renders the screen, applying any message.
         */
        SHOW_SCREEN
    }

    /**
     * Severity of the message accompanying a {@link RoutingAction#SHOW_SCREEN}, preserving the COBOL
     * color contract of {@code COUSR03C} exactly.
     */
    public enum MessageSeverity {

        /** No message to display (COBOL {@code WS-MESSAGE} left as spaces). */
        NONE,

        /** Error line (COBOL {@code WS-ERR-FLG} on); rendered in the default error (red) color. */
        ERROR,

        /**
         * Neutral confirmation line (COBOL {@code MOVE DFHNEUTR TO ERRMSGC}); the
         * {@code "Press PF5 key to delete this user ..."} prompt.
         */
        NEUTRAL,

        /**
         * Success line (COBOL {@code MOVE DFHGREEN TO ERRMSGC}); the {@code "... has been deleted ..."}
         * confirmation, rendered in green.
         */
        SUCCESS
    }

    /**
     * Immutable outcome of a business method: the {@link RoutingAction} to take, the message to
     * display (empty when none), and the message {@link MessageSeverity}.
     *
     * <p>The controller inspects {@link #action()} to choose between issuing a redirect (using
     * {@link CardDemoContext#getToProgram()}) and re-rendering the delete screen; when re-rendering it
     * shows {@link #message()} using the styling implied by {@link #severity()}.</p>
     *
     * @param action   the routing action; must not be {@code null}
     * @param message  the message text; normalized to {@code ""} when {@code null}
     * @param severity the message severity; must not be {@code null}
     */
    public record UserDeleteResult(RoutingAction action, String message, MessageSeverity severity) {

        /**
         * Canonical constructor enforcing non-null {@code action}/{@code severity} and normalizing a
         * {@code null} {@code message} to the empty string.
         *
         * @param action   the routing action; must not be {@code null}
         * @param message  the message text; {@code null} becomes {@code ""}
         * @param severity the message severity; must not be {@code null}
         */
        public UserDeleteResult {
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
         * Reports whether this outcome is a redirect (the COBOL {@code XCTL} equivalent).
         *
         * @return {@code true} when {@link #action()} is {@link RoutingAction#REDIRECT}
         */
        public boolean isRedirect() {
            return action == RoutingAction.REDIRECT;
        }

        /**
         * Reports whether this outcome carries a message to display.
         *
         * @return {@code true} when a non-empty {@link #message()} is present
         */
        public boolean hasMessage() {
            return !message.isEmpty();
        }

        /**
         * Creates a redirect outcome with no message (COBOL {@code XCTL} via
         * {@code RETURN-TO-PREV-SCREEN}). The destination is taken from
         * {@link CardDemoContext#getToProgram()}.
         *
         * @return a {@link RoutingAction#REDIRECT} result with {@link MessageSeverity#NONE}
         */
        public static UserDeleteResult redirect() {
            return new UserDeleteResult(RoutingAction.REDIRECT, "", MessageSeverity.NONE);
        }

        /**
         * Creates a plain screen re-display with no message (COBOL {@code SEND-USRDEL-SCREEN}).
         *
         * @return a {@link RoutingAction#SHOW_SCREEN} result with {@link MessageSeverity#NONE}
         */
        public static UserDeleteResult showScreen() {
            return new UserDeleteResult(RoutingAction.SHOW_SCREEN, "", MessageSeverity.NONE);
        }

        /**
         * Creates a screen re-display carrying an error message (COBOL {@code WS-ERR-FLG} path).
         *
         * @param message the error text to display
         * @return a {@link RoutingAction#SHOW_SCREEN} result with {@link MessageSeverity#ERROR}
         */
        public static UserDeleteResult error(String message) {
            return new UserDeleteResult(RoutingAction.SHOW_SCREEN, message, MessageSeverity.ERROR);
        }

        /**
         * Creates a screen re-display carrying the neutral confirmation prompt (COBOL
         * {@code MOVE DFHNEUTR TO ERRMSGC} path).
         *
         * @param message the neutral text to display
         * @return a {@link RoutingAction#SHOW_SCREEN} result with {@link MessageSeverity#NEUTRAL}
         */
        public static UserDeleteResult neutral(String message) {
            return new UserDeleteResult(RoutingAction.SHOW_SCREEN, message, MessageSeverity.NEUTRAL);
        }

        /**
         * Creates a screen re-display carrying the green success line (COBOL
         * {@code MOVE DFHGREEN TO ERRMSGC} path).
         *
         * @param message the success text to display
         * @return a {@link RoutingAction#SHOW_SCREEN} result with {@link MessageSeverity#SUCCESS}
         */
        public static UserDeleteResult success(String message) {
            return new UserDeleteResult(RoutingAction.SHOW_SCREEN, message, MessageSeverity.SUCCESS);
        }
    }

    /**
     * Handles a user-delete interaction, the Java migration of paragraph {@code MAIN-PARA} in
     * {@code legacy/cbl/COUSR03C.cbl}.
     *
     * <p>Reproduces the COBOL control flow of {@code MAIN-PARA} exactly:</p>
     * <ul>
     *   <li><b>First entry, no COMMAREA</b> (COBOL {@code IF EIBCALEN = 0}, reproduced by
     *       {@link CardDemoContext#isNew()}): record {@code COSGN00C} as the hand-off target and
     *       redirect back to the sign-on screen (COBOL {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} +
     *       {@code RETURN-TO-PREV-SCREEN}).</li>
     *   <li><b>First display</b> (COBOL {@code IF NOT CDEMO-PGM-REENTER}, reproduced by
     *       {@link CardDemoContext#isProgramEnter()}): mark re-entry, then &mdash; if a user was
     *       selected on the list ({@code CDEMO-CU03-USR-SELECTED}, carried into
     *       {@link COUSR03Form#getUsridin()} by the controller) &mdash; auto-perform the confirmation
     *       lookup via {@link #processEnterKey(COUSR03Form, CardDemoContext)}; otherwise show a plain
     *       empty screen.</li>
     *   <li><b>Re-entry</b> (COBOL {@code EVALUATE EIBAID}): ENTER looks the user up for confirmation;
     *       PF3 returns to the originating program (or {@code COADM01C} when none is recorded); PF4
     *       clears the screen; PF5 performs the delete; PF12 returns to {@code COADM01C}; any other key
     *       yields the invalid-key message.</li>
     * </ul>
     *
     * <p>The concluding {@code EXEC CICS RETURN TRANSID(CU03) COMMAREA(...)} and the actual
     * {@code XCTL}/{@code SEND} are the controller's responsibility: it writes the mutated
     * {@link CardDemoContext} back to the HTTP session and either renders the screen or performs the
     * redirect indicated by the returned {@link UserDeleteResult}. This method is annotated
     * {@link Transactional} so that the read-then-delete reached through the internal PF5 dispatch runs
     * within a single database transaction even though it is invoked via same-instance call.</p>
     *
     * @param aid  the attention-identifier key pressed; a {@code null} value is treated as
     *             {@link AidKey#OTHER}, matching the COBOL {@code WHEN OTHER} default
     * @param form the submitted user-delete screen form (map {@code COUSR3A} of mapset
     *             {@code COUSR03}); on first display its {@code usridin} may carry the list selection
     * @return the interaction outcome: a redirect, or a screen re-display with an optional message
     */
    @Transactional
    public UserDeleteResult mainEntry(AidKey aid, COUSR03Form form) {
        // MAIN-PARA prologue: SET ERR-FLG-OFF / USR-MODIFIED-NO and MOVE SPACES TO WS-MESSAGE. These
        // flags are request-local in the migrated design; a fresh (message-free) result is the
        // equivalent starting state.

        // COBOL: IF EIBCALEN = 0 -> no COMMAREA; record COSGN00C as the target and bounce to sign-on.
        if (context.isNew()) {
            context.setToProgram(SIGNON_PROGRAM);
            return UserDeleteResult.redirect();
        }

        // COBOL: IF NOT CDEMO-PGM-REENTER -> first display of this program this conversation.
        if (context.isProgramEnter()) {
            // COBOL: SET CDEMO-PGM-REENTER TO TRUE.
            context.markReenter();
            // COBOL: IF CDEMO-CU03-USR-SELECTED NOT = SPACES AND LOW-VALUES -> MOVE it to USRIDIN and
            // PERFORM PROCESS-ENTER-KEY. The list selection is carried into the form's user-id field
            // by the controller (see the class note on CDEMO-CU03-USR-SELECTED).
            String selectedUserId = (form == null) ? null : form.getUsridin();
            if (selectedUserId != null && !selectedUserId.isBlank()) {
                return processEnterKey(form, context);
            }
            // COBOL: PERFORM SEND-USRDEL-SCREEN (plain first display, no message).
            return UserDeleteResult.showScreen();
        }

        // COBOL ELSE: re-entry -> RECEIVE-USRDEL-SCREEN then EVALUATE EIBAID. A null key collapses to
        // the WHEN OTHER (invalid-key) branch.
        AidKey effectiveAid = (aid == null) ? AidKey.OTHER : aid;
        return switch (effectiveAid) {
            case ENTER -> processEnterKey(form, context);
            case PF3 -> {
                // COBOL: IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES -> TO = COADM01C; ELSE TO = FROM.
                String fromProgram = context.getFromProgram();
                if (fromProgram == null || fromProgram.isBlank()) {
                    context.setToProgram(ADMIN_PROGRAM);
                } else {
                    context.setToProgram(fromProgram);
                }
                yield UserDeleteResult.redirect();
            }
            case PF4 -> clearCurrentScreen(form);
            case PF5 -> deleteUserInfo(form, context);
            case PF12 -> {
                // COBOL: MOVE 'COADM01C' TO CDEMO-TO-PROGRAM.
                context.setToProgram(ADMIN_PROGRAM);
                yield UserDeleteResult.redirect();
            }
            case OTHER -> UserDeleteResult.error(MSG_INVALID_KEY);
        };
    }

    /**
     * Looks up the keyed user and displays it for confirmation, the Java migration of paragraph
     * {@code PROCESS-ENTER-KEY} in {@code legacy/cbl/COUSR03C.cbl}.
     *
     * <p>Reproduces the COBOL exactly. A blank {@code USRIDINI} (COBOL {@code SPACES OR LOW-VALUES})
     * yields the {@link #MSG_USER_ID_EMPTY} error with no lookup. Otherwise the display fields are
     * cleared (COBOL {@code MOVE SPACES TO FNAMEI, LNAMEI, USRTYPEI}), the user is read via
     * {@link #readUserSecFile(String)}, and on success the first name, last name and user type are
     * populated on the form and the neutral {@link #MSG_PRESS_PF5_TO_DELETE} confirmation prompt is
     * returned (COBOL {@code MOVE DFHNEUTR TO ERRMSGC}). A {@code NOTFND} read raises
     * {@link RecordNotFoundException} (surfaced centrally as {@link #MSG_USER_ID_NOT_FOUND}); a genuine
     * {@code WHEN OTHER} I/O failure is caught and reproduced as the {@link #MSG_UNABLE_TO_LOOKUP}
     * error line, exactly as the COBOL re-displays the screen without aborting.</p>
     *
     * <p>This method performs no navigation, so the injected {@link CardDemoContext} hand-off is not
     * mutated here; {@code ctx} is retained as part of the preserved paragraph signature (the COBOL
     * paragraph likewise had COMMAREA access without altering it). It is annotated read-only because
     * the confirmation lookup issues no writes; when reached through {@link #mainEntry(AidKey,
     * COUSR03Form)} it participates in that method's transaction.</p>
     *
     * @param form the submitted user-delete form supplying {@code USRIDIN}; its {@code fname},
     *             {@code lname} and {@code usrtype} display fields are updated on a successful lookup
     * @param ctx  the session context (unused by this lookup-only paragraph; part of the preserved
     *             signature)
     * @return the outcome: an empty-id error, the neutral confirmation prompt with the populated
     *         display fields, or the {@code "Unable to lookup User..."} error on an I/O failure
     */
    @Transactional(readOnly = true)
    public UserDeleteResult processEnterKey(COUSR03Form form, CardDemoContext ctx) {
        String userId = (form == null) ? null : form.getUsridin();

        // COBOL EVALUATE TRUE: WHEN USRIDINI = SPACES OR LOW-VALUES -> empty-id error, no lookup.
        if (userId == null || userId.isBlank()) {
            return UserDeleteResult.error(MSG_USER_ID_EMPTY);
        }

        // COBOL (first IF NOT ERR-FLG-ON): MOVE SPACES TO FNAMEI, LNAMEI, USRTYPEI before the read.
        form.setFname("");
        form.setLname("");
        form.setUsrtype("");

        // COBOL: MOVE USRIDINI TO SEC-USR-ID, PERFORM READ-USER-SEC-FILE. NOTFND raises
        // RecordNotFoundException (handled centrally); a genuine I/O failure (WHEN OTHER) surfaces as a
        // DataAccessException, reproduced as the exact "Unable to lookup User..." line.
        UserSecurity user;
        try {
            user = readUserSecFile(userId);
        } catch (DataAccessException ex) {
            return UserDeleteResult.error(MSG_UNABLE_TO_LOOKUP);
        }

        // COBOL (second IF NOT ERR-FLG-ON): MOVE SEC-USR-FNAME/LNAME/TYPE to the display fields; the
        // neutral "Press PF5 ..." prompt set by READ-USER-SEC-FILE is what remains on the screen.
        form.setFname(user.getUsrFname());
        form.setLname(user.getUsrLname());
        form.setUsrtype(user.getUsrType());
        return UserDeleteResult.neutral(MSG_PRESS_PF5_TO_DELETE);
    }

    /**
     * Reads then deletes the keyed user, the Java migration of paragraph {@code DELETE-USER-INFO} in
     * {@code legacy/cbl/COUSR03C.cbl}.
     *
     * <p>Reproduces the COBOL exactly. A blank {@code USRIDINI} yields the {@link #MSG_USER_ID_EMPTY}
     * error with no I/O. Otherwise the record is read via {@link #readUserSecFile(String)} and then
     * removed via {@link #deleteUserSecFile(UserSecurity, COUSR03Form)} (COBOL
     * {@code PERFORM READ-USER-SEC-FILE} then {@code PERFORM DELETE-USER-SEC-FILE}). A {@code NOTFND}
     * read raises {@link RecordNotFoundException}: this surfaces the {@code "User ID NOT found..."}
     * line and short-circuits the delete, exactly as the failed CICS {@code READ ... UPDATE} would have
     * left nothing to delete. A genuine read I/O failure ({@code WHEN OTHER}) is reproduced as the
     * {@link #MSG_UNABLE_TO_LOOKUP} error line.</p>
     *
     * <p>Annotated {@link Transactional} to bracket the read-then-delete as one unit of work (AAP
     * &sect;0.6.5), mirroring the CICS read-for-update / delete pair; when reached through
     * {@link #mainEntry(AidKey, COUSR03Form)} it participates in that method's transaction.</p>
     *
     * @param form the submitted user-delete form supplying {@code USRIDIN}; cleared on a successful
     *             delete via {@link #deleteUserSecFile(UserSecurity, COUSR03Form)}
     * @param ctx  the session context (unused by this paragraph; part of the preserved signature)
     * @return the outcome: an empty-id error, the {@code "Unable to lookup User..."} error on a read
     *         I/O failure, or the result of the delete step (green success, or a delete-time error)
     */
    @Transactional
    public UserDeleteResult deleteUserInfo(COUSR03Form form, CardDemoContext ctx) {
        String userId = (form == null) ? null : form.getUsridin();

        // COBOL EVALUATE TRUE: WHEN USRIDINI = SPACES OR LOW-VALUES -> empty-id error, no I/O.
        if (userId == null || userId.isBlank()) {
            return UserDeleteResult.error(MSG_USER_ID_EMPTY);
        }

        // COBOL (IF NOT ERR-FLG-ON): PERFORM READ-USER-SEC-FILE then PERFORM DELETE-USER-SEC-FILE.
        // NOTFND raises RecordNotFoundException (surfaced as "User ID NOT found...") and short-circuits
        // the delete; a genuine read I/O failure (WHEN OTHER) is reproduced as "Unable to lookup
        // User...".
        UserSecurity user;
        try {
            user = readUserSecFile(userId);
        } catch (DataAccessException ex) {
            return UserDeleteResult.error(MSG_UNABLE_TO_LOOKUP);
        }
        return deleteUserSecFile(user, form);
    }

    /**
     * Reads a user-security record by id, the Java migration of paragraph {@code READ-USER-SEC-FILE}
     * in {@code legacy/cbl/COUSR03C.cbl}.
     *
     * <p>Replaces the COBOL {@code EXEC CICS READ DATASET('USRSEC') ... RIDFLD(SEC-USR-ID) UPDATE}
     * with a Spring Data lookup. The COBOL {@code EVALUATE WS-RESP-CD} branches map as:</p>
     * <ul>
     *   <li><b>NORMAL</b> &mdash; the record is returned; the caller displays it (with the neutral
     *       {@code "Press PF5 ..."} prompt) or proceeds to delete it. The COBOL {@code NORMAL} branch
     *       also issued a {@code SEND-USRDEL-SCREEN}; that duplicate send collapses to the single
     *       render performed by the controller for the caller's returned outcome.</li>
     *   <li><b>NOTFND</b> &mdash; raises {@link RecordNotFoundException} carrying
     *       {@link #MSG_USER_ID_NOT_FOUND}, surfaced by the online {@code @ControllerAdvice} handler as
     *       the error line (AAP &sect;0.6.5).</li>
     *   <li><b>OTHER</b> &mdash; a genuine data-access failure propagates as a Spring
     *       {@link DataAccessException} for the caller to translate into the
     *       {@code "Unable to lookup User..."} line.</li>
     * </ul>
     *
     * @param userId the user id to read ({@code SEC-USR-ID}); must be non-blank (validated by callers)
     * @return the matching {@link UserSecurity} record (COBOL {@code NORMAL})
     * @throws RecordNotFoundException if no record exists for {@code userId} (COBOL {@code NOTFND})
     */
    UserSecurity readUserSecFile(String userId) {
        Optional<UserSecurity> found = userSecurityRepository.findByUsrId(userId);
        return found.orElseThrow(() -> new RecordNotFoundException(MSG_USER_ID_NOT_FOUND));
    }

    /**
     * Deletes a user-security record, the Java migration of paragraph {@code DELETE-USER-SEC-FILE} in
     * {@code legacy/cbl/COUSR03C.cbl}.
     *
     * <p>Replaces the COBOL {@code EXEC CICS DELETE DATASET('USRSEC')} with
     * {@link UserSecurityRepository#delete(Object)} of the record read under the same transaction. The
     * COBOL {@code EVALUATE WS-RESP-CD} branches map as:</p>
     * <ul>
     *   <li><b>NORMAL</b> &mdash; clear the screen fields (COBOL {@code PERFORM INITIALIZE-ALL-FIELDS})
     *       and return the green success line {@code "User <id> has been deleted ..."} (COBOL
     *       {@code MOVE DFHGREEN TO ERRMSGC} + {@code STRING}).</li>
     *   <li><b>NOTFND</b> &mdash; return the {@link #MSG_USER_ID_NOT_FOUND} error. Preserved for 1:1
     *       fidelity but inert in the migrated flow: the record was just read in this transaction, so
     *       a not-found on delete cannot occur (any not-found is already reported by
     *       {@link #readUserSecFile(String)}).</li>
     *   <li><b>OTHER</b> &mdash; return the {@link #MSG_UNABLE_TO_UPDATE} error (the original's
     *       capitalized {@code "Update"} text).</li>
     * </ul>
     *
     * @param user the record to delete, as read by {@link #readUserSecFile(String)}
     * @param form the user-delete form; its fields are cleared on a successful delete
     * @return the outcome: the green success line, or a delete-time error re-display
     */
    UserDeleteResult deleteUserSecFile(UserSecurity user, COUSR03Form form) {
        try {
            userSecurityRepository.delete(user);
        } catch (EmptyResultDataAccessException ex) {
            // COBOL WHEN NOTFND (defensive; inert after a successful read in the same transaction).
            return UserDeleteResult.error(MSG_USER_ID_NOT_FOUND);
        } catch (DataAccessException ex) {
            // COBOL WHEN OTHER.
            return UserDeleteResult.error(MSG_UNABLE_TO_UPDATE);
        }
        // COBOL WHEN NORMAL: INITIALIZE-ALL-FIELDS, MOVE DFHGREEN TO ERRMSGC, STRING the success line.
        initializeAllFields(form);
        return UserDeleteResult.success(buildDeletedMessage(user.getUsrId()));
    }

    /**
     * Clears the current screen, the Java migration of paragraph {@code CLEAR-CURRENT-SCREEN} in
     * {@code legacy/cbl/COUSR03C.cbl}.
     *
     * <p>Mirrors the COBOL {@code PERFORM INITIALIZE-ALL-FIELDS} followed by
     * {@code PERFORM SEND-USRDEL-SCREEN}: it resets the screen fields via
     * {@link #initializeAllFields(COUSR03Form)} and returns a plain re-display with no message. The
     * actual {@code SEND} is performed by the controller.</p>
     *
     * @param form the user-delete form to reset
     * @return a {@link RoutingAction#SHOW_SCREEN} outcome with no message
     */
    public UserDeleteResult clearCurrentScreen(COUSR03Form form) {
        initializeAllFields(form);
        return UserDeleteResult.showScreen();
    }

    /**
     * Resets the screen fields, the Java migration of paragraph {@code INITIALIZE-ALL-FIELDS} in
     * {@code legacy/cbl/COUSR03C.cbl}.
     *
     * <p>Mirrors the COBOL {@code MOVE SPACES TO USRIDINI, FNAMEI, LNAMEI, USRTYPEI} (and
     * {@code WS-MESSAGE}). The cursor positioning {@code MOVE -1 TO USRIDINL} is a presentation concern
     * owned by the controller and is not reproduced here; clearing {@code WS-MESSAGE} is represented by
     * the caller returning a message-free outcome.</p>
     *
     * @param form the user-delete form to reset; a {@code null} form is tolerated as a no-op
     */
    void initializeAllFields(COUSR03Form form) {
        if (form == null) {
            return;
        }
        form.setUsridin("");
        form.setFname("");
        form.setLname("");
        form.setUsrtype("");
    }

    /**
     * Builds the green delete-success line, reproducing the COBOL {@code STRING 'User ' DELIMITED BY
     * SIZE, SEC-USR-ID DELIMITED BY SPACE, ' has been deleted ...' DELIMITED BY SIZE INTO WS-MESSAGE}
     * of {@code DELETE-USER-SEC-FILE}.
     *
     * @param userId the deleted user's id ({@code SEC-USR-ID}); truncated at its first space to
     *               reproduce {@code DELIMITED BY SPACE}
     * @return the success message, e.g. {@code "User USER0001 has been deleted ..."}
     */
    private static String buildDeletedMessage(String userId) {
        return MSG_DELETED_PREFIX + delimitedBySpace(userId) + MSG_DELETED_SUFFIX;
    }

    /**
     * Returns the portion of {@code value} up to (but excluding) its first space, reproducing the
     * COBOL {@code STRING ... DELIMITED BY SPACE} semantics for the fixed-width {@code SEC-USR-ID} key.
     *
     * @param value the source string (may be {@code null})
     * @return the substring before the first space, the whole string if it contains no space, or
     *         {@code ""} when {@code value} is {@code null}
     */
    private static String delimitedBySpace(String value) {
        if (value == null) {
            return "";
        }
        int firstSpace = value.indexOf(' ');
        return (firstSpace < 0) ? value : value.substring(0, firstSpace);
    }
}
