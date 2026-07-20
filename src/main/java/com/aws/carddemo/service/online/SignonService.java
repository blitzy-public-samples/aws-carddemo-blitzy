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

import java.util.Locale;
import java.util.Objects;

import io.micrometer.observation.annotation.Observed;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COSGN00Form;
import com.aws.carddemo.security.CardDemoUserDetailsService;

/**
 * Sign-on online service, the Java migration of the CICS COBOL program
 * {@code COSGN00C} (the AWS CardDemo sign-on screen).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COSGN00C.cbl} &mdash; program {@code COSGN00C},
 * CICS transaction id {@code CC00}. This service is the faithful Java migration of the
 * online sign-on business logic, preserving the original program's control flow
 * one-for-one: each business COBOL paragraph becomes exactly one Java method
 * (AAP &sect;0.3.3, service layer; AAP &sect;0.4.1, online-programs table
 * {@code COSGN00C.cbl -> SignonService + SignonController}, tran {@code CC00}). It is the
 * <em>sign-on archetype</em> for the {@code service.online} package.</p>
 *
 * <h2>Sign-on archetype and the presentation split</h2>
 * <p>{@code COSGN00C} has six paragraphs that divide cleanly into business logic (kept
 * here) and 3270/BMS presentation (owned by the paired {@code SignonController} and the
 * {@code COSGN00} Thymeleaf view). This service therefore implements only the business
 * paragraphs and deliberately contains no {@code SEND}/{@code RECEIVE}/header logic:</p>
 * <table border="1">
 *   <caption>COSGN00C paragraph &rarr; Java mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Owner</th><th>Java member</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>service</td>
 *       <td>{@link #mainEntry(AidKey, COSGN00Form)}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>service</td>
 *       <td>{@link #processEnterKey(COSGN00Form, CardDemoContext)}</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE} (success)</td><td>service</td>
 *       <td>{@link #readUserSecFile(String, CardDemoContext)}</td></tr>
 *   <tr><td>{@code READ-USER-SEC-FILE} (failure branches)</td><td>service</td>
 *       <td>{@link #mapAuthenticationFailure(AuthenticationException)}</td></tr>
 *   <tr><td>{@code SEND-SIGNON-SCREEN}</td><td>controller</td><td>&mdash; (view render)</td></tr>
 *   <tr><td>{@code SEND-PLAIN-TEXT}</td><td>controller</td><td>&mdash; (plain-text send)</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>controller</td><td>&mdash; (header fields)</td></tr>
 * </table>
 *
 * <p>The single COBOL paragraph {@code READ-USER-SEC-FILE} maps to two Java methods because
 * Spring Security splits its two outcomes across a principal/exception boundary: a
 * successful lookup-and-compare yields an authenticated principal (handled by
 * {@link #readUserSecFile(String, CardDemoContext)}), while every failure surfaces as an
 * {@link AuthenticationException} (translated by
 * {@link #mapAuthenticationFailure(AuthenticationException)}). Both methods carry an origin
 * tag naming the paragraph, so traceability is strengthened rather than diluted.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>{@code COSGN00C} is pseudo-conversational: it ends each interaction with
 * {@code EXEC CICS RETURN TRANSID(CC00) COMMAREA(...)}, preserving state in the COMMAREA
 * ({@code COCOM01Y}). That state carrier becomes the session-scoped
 * {@link CardDemoContext} bean, which is <b>injected, never statically referenced</b>
 * (AAP &sect;0.6.8). First entry into the transaction (COBOL {@code EIBCALEN = 0}) is
 * reproduced by {@link CardDemoContext#isNew()}.</p>
 *
 * <p><b>Sign-on differs from the menu programs.</b> The menu programs bounce back to
 * sign-on when {@code EIBCALEN = 0} (a menu can never legitimately be first-entered without
 * a COMMAREA). Sign-on is the application entry point, so {@code EIBCALEN = 0} is instead
 * the <em>normal</em> initial display of the empty screen. The paired
 * {@code SignonController} latches first entry by calling
 * {@link CardDemoContext#markInitialized()} after that first display (mirroring the
 * {@code RETURN TRANSID} that establishes the COMMAREA), so the subsequent submit is
 * dispatched as re-entry. Consistent with the sibling services, this service never calls
 * {@code markInitialized()} itself. {@code COSGN00C} also never inspects or sets
 * {@code CDEMO-PGM-REENTER} for its own display, so this service performs no
 * {@link CardDemoContext#markReenter()}; the only program-context mutation it makes is the
 * {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} ({@link CardDemoContext#markEnter()}) on a
 * successful sign-on, which puts the <em>target</em> menu program into its enter state.</p>
 *
 * <h2>Security delegation and role-based routing (AAP &sect;0.6.7)</h2>
 * <p>In COBOL, {@code READ-USER-SEC-FILE} reads {@code USRSEC} by user id, compares
 * {@code SEC-USR-PWD = WS-USER-PWD} as <em>cleartext</em>, moves {@code SEC-USR-TYPE} to
 * {@code CDEMO-USER-TYPE}, zeroes {@code CDEMO-PGM-CONTEXT}, and {@code XCTL}s to
 * {@code COADM01C} (admin) or {@code COMEN01C} (user). In the modernized architecture the
 * lookup and the <b>cleartext password comparison behavior is preserved for functional
 * parity but lives in the security layer</b> ({@link CardDemoUserDetailsService} loads the
 * user; {@code CardDemoAuthenticationProvider} performs the compare) &mdash; it is not
 * re-implemented here (AAP &sect;0.6.7). There are <b>no hardcoded credentials</b> anywhere
 * in this class.</p>
 *
 * <p>This service computes only the post-authentication <em>routing decision</em> (the
 * COBOL {@code XCTL} target) and writes it into the {@link CardDemoContext}; the actual
 * HTTP redirect is performed by {@code SignonController} / {@code SecurityConfig}. The user
 * type is resolved from the authenticated principal's authority using the role constants
 * {@link CardDemoUserDetailsService#ROLE_ADMIN} / {@link CardDemoUserDetailsService#ROLE_USER}:
 * an administrator ({@code ROLE_ADMIN}, COBOL {@code SEC-USR-TYPE = 'A'}) routes to the admin
 * menu program {@code COADM01C} / tran {@code CA00}; every other user ({@code ROLE_USER},
 * COBOL {@code 'U'}) routes to the main menu program {@code COMEN01C} / tran {@code CM00}.</p>
 *
 * <p><b>No feature expansion.</b> This migration introduces no new screens, fields, or
 * interfaces; it reproduces the {@code COSGN00C} behavior, messages, and quirks exactly.</p>
 */
@Service
public class SignonService {

    // --- Program / transaction identity (COSGN00C WORKING-STORAGE) ----------

    /** COBOL {@code WS-PGMNAME VALUE 'COSGN00C'} - this program's name. */
    private static final String PROGRAM_NAME = "COSGN00C";

    /** COBOL {@code WS-TRANID VALUE 'CC00'} - this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CC00";

    /** Admin {@code XCTL} target program (COBOL {@code XCTL PROGRAM('COADM01C')}). */
    private static final String ADMIN_PROGRAM = "COADM01C";

    /** Admin menu transaction id (tran of {@code COADM01C}, AAP &sect;0.4.1). */
    private static final String ADMIN_TRANSACTION_ID = "CA00";

    /** Regular-user {@code XCTL} target program (COBOL {@code XCTL PROGRAM('COMEN01C')}). */
    private static final String USER_PROGRAM = "COMEN01C";

    /** Main menu transaction id (tran of {@code COMEN01C}, AAP &sect;0.4.1). */
    private static final String USER_TRANSACTION_ID = "CM00";

    // --- Screen messages (verbatim from COSGN00C / CSMSG01Y) ----------------

    /**
     * COBOL {@code 'Please enter User ID ...'} ({@code COSGN00C} PROCESS-ENTER-KEY,
     * empty-user-id branch).
     */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /**
     * COBOL {@code 'Please enter Password ...'} ({@code COSGN00C} PROCESS-ENTER-KEY,
     * empty-password branch).
     */
    private static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /**
     * COBOL {@code 'Wrong Password. Try again ...'} ({@code COSGN00C} READ-USER-SEC-FILE,
     * {@code WHEN 0} with {@code SEC-USR-PWD} mismatch). Surfaces from a
     * {@link BadCredentialsException}.
     */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /**
     * COBOL {@code 'User not found. Try again ...'} ({@code COSGN00C} READ-USER-SEC-FILE,
     * {@code WHEN 13}). Surfaces from a {@link UsernameNotFoundException}.
     */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * COBOL {@code 'Unable to verify the User ...'} ({@code COSGN00C} READ-USER-SEC-FILE,
     * {@code WHEN OTHER}). Surfaces from any other {@link AuthenticationException}.
     */
    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * COBOL {@code CCDA-MSG-THANK-YOU} ({@code legacy/cpy/CSMSG01Y.cpy}), displayed on PF3.
     * The copybook stores this as {@code PIC X(50)} space-padded; the visible text is held
     * here (trailing storage padding is a non-visible artifact and is not reproduced).
     */
    private static final String MSG_THANK_YOU = "Thank you for using CardDemo application...";

    /**
     * COBOL {@code CCDA-MSG-INVALID-KEY} ({@code legacy/cpy/CSMSG01Y.cpy}), displayed for an
     * unrecognized attention key. As above, the visible (trimmed) text is held here.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    // --- Injected collaborators (constructor injection, no field injection) -

    /**
     * Session-scoped COMMAREA replacement ({@code COPY COCOM01Y}). Injected as a
     * session-scoped Spring proxy, never referenced statically (AAP &sect;0.6.8); it carries
     * the sign-on navigation and identity state across the pseudo-conversational cycle.
     */
    private final CardDemoContext context;

    /**
     * The user store / authority producer that replaces the COBOL {@code EXEC CICS READ} of
     * {@code USRSEC}. Used on the post-authentication success path to obtain the resolved
     * role authority for routing; the cleartext password comparison itself is performed by
     * {@code CardDemoAuthenticationProvider}, not here (AAP &sect;0.6.7).
     */
    private final CardDemoUserDetailsService userDetailsService;

    /**
     * Creates the sign-on service with its collaborators via constructor injection. Because
     * there is a single constructor, Spring performs constructor injection without an
     * explicit {@code @Autowired} annotation.
     *
     * @param context            the session-scoped {@link CardDemoContext} (COMMAREA
     *                           replacement); must not be {@code null}
     * @param userDetailsService the {@link CardDemoUserDetailsService} user store; must not
     *                           be {@code null}
     */
    public SignonService(CardDemoContext context, CardDemoUserDetailsService userDetailsService) {
        this.context = Objects.requireNonNull(context, "context");
        this.userDetailsService = Objects.requireNonNull(userDetailsService, "userDetailsService");
    }

    // --- Nested types -------------------------------------------------------

    /**
     * Attention-identifier (AID) keys handled by {@link #mainEntry(AidKey, COSGN00Form)},
     * the Java model of the COBOL {@code EVALUATE EIBAID} in {@code MAIN-PARA}.
     *
     * <p>Only the keys with distinct behavior in {@code COSGN00C} are modeled:
     * {@link #ENTER} ({@code DFHENTER}) and {@link #PF3} ({@code DFHPF3}); every other key
     * collapses to {@link #OTHER}, matching the COBOL {@code WHEN OTHER} default. The paired
     * {@code SignonController} maps the submitted request to one of these values.</p>
     */
    public enum AidKey {

        /** COBOL {@code DFHENTER} - the ENTER key; validates inputs and signs on. */
        ENTER,

        /** COBOL {@code DFHPF3} - the PF3 key; ends the session with the thank-you message. */
        PF3,

        /** COBOL {@code WHEN OTHER} - any other key; yields the invalid-key message. */
        OTHER
    }

    /**
     * The controller action implied by a {@link SignonResult}, modeling the mutually
     * exclusive continuations of the COBOL {@code MAIN-PARA} / {@code READ-USER-SEC-FILE}
     * control flow.
     */
    public enum RoutingAction {

        /**
         * Redisplay the sign-on screen (COBOL {@code PERFORM SEND-SIGNON-SCREEN}); used for
         * the first-entry empty screen and for every validation/authentication error line.
         */
        SHOW_SIGNON,

        /**
         * Inputs are valid; the controller must invoke the {@code AuthenticationManager}.
         * This is the COBOL {@code IF NOT ERR-FLG-ON PERFORM READ-USER-SEC-FILE} hand-off
         * point, expressed honestly because authentication is delegated to Spring Security.
         */
        AUTHENTICATE,

        /**
         * Sign-on succeeded; the controller redirects to the routing target written into the
         * {@link CardDemoContext}. This is the COBOL {@code XCTL} to {@code COADM01C} /
         * {@code COMEN01C}.
         */
        REDIRECT,

        /**
         * End the conversation with a plain-text message (COBOL {@code PERFORM
         * SEND-PLAIN-TEXT} followed by {@code EXEC CICS RETURN}); used for the PF3 thank-you.
         */
        EXIT
    }

    /**
     * Severity of the message carried by a {@link SignonResult}, distinguishing the COBOL
     * error line ({@code WS-ERR-FLG = 'Y'}, styled red) from a neutral informational line and
     * from the no-message case.
     */
    public enum MessageSeverity {

        /** No message to display (first-entry empty screen; redirect; authenticate). */
        NONE,

        /** An error line (COBOL {@code MOVE 'Y' TO WS-ERR-FLG}); the screen shows it in red. */
        ERROR,

        /** A neutral informational line (COBOL thank-you message on PF3). */
        INFORMATION
    }

    /**
     * The screen field the cursor should be positioned on, modeling the COBOL
     * {@code MOVE -1 TO USERIDL} / {@code MOVE -1 TO PASSWDL} attribute-length cursor moves.
     */
    public enum CursorField {

        /** No explicit cursor request (COBOL leaves the cursor unset for this outcome). */
        NONE,

        /** Position the cursor on the User ID field (COBOL {@code MOVE -1 TO USERIDL}). */
        USER_ID,

        /** Position the cursor on the Password field (COBOL {@code MOVE -1 TO PASSWDL}). */
        PASSWORD
    }

    /**
     * Immutable outcome of a sign-on interaction, telling the controller what to do next:
     * redisplay the sign-on screen (optionally with a message and a cursor position),
     * authenticate, redirect after a successful sign-on, or end with a plain-text message.
     *
     * <p>The routing decision on a successful sign-on (the target program / transaction id,
     * user id, and user type) is not carried in this result; it is written directly into the
     * {@link CardDemoContext}, exactly as the COBOL moved those values into the COMMAREA
     * before its {@code XCTL}. This record only conveys the presentation/continuation
     * decision that the paired {@code SignonController} needs.</p>
     *
     * @param action      the controller action to perform; never {@code null}
     * @param message     the message to display, or {@code null} when {@link #severity} is
     *                    {@link MessageSeverity#NONE}
     * @param severity    the message severity; never {@code null}
     * @param cursorField the field to position the cursor on; never {@code null}
     */
    public record SignonResult(RoutingAction action, String message, MessageSeverity severity,
            CursorField cursorField) {

        /**
         * Canonical constructor enforcing the non-null invariants of the result. {@code null}
         * enum arguments are rejected so that consumers can switch on {@link #action},
         * {@link #severity}, and {@link #cursorField} without null checks; {@code message}
         * remains nullable (it is absent for the no-message outcomes).
         *
         * @param action      the controller action to perform; must not be {@code null}
         * @param message     the message to display, or {@code null} if none
         * @param severity    the message severity; must not be {@code null}
         * @param cursorField the field to position the cursor on; must not be {@code null}
         */
        public SignonResult {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(cursorField, "cursorField");
        }

        /**
         * Creates the first-entry outcome: display the empty sign-on screen with the cursor
         * on the User ID field and no message. Mirrors the COBOL {@code EIBCALEN = 0} branch
         * ({@code MOVE LOW-VALUES TO COSGN0AO}, {@code MOVE -1 TO USERIDL},
         * {@code PERFORM SEND-SIGNON-SCREEN}).
         *
         * @return a {@link RoutingAction#SHOW_SIGNON} result with no message and the cursor
         *         on {@link CursorField#USER_ID}
         */
        public static SignonResult showSignon() {
            return new SignonResult(RoutingAction.SHOW_SIGNON, null, MessageSeverity.NONE,
                    CursorField.USER_ID);
        }

        /**
         * Creates an error outcome: redisplay the sign-on screen with an error message and a
         * cursor position. Mirrors every COBOL {@code MOVE 'Y' TO WS-ERR-FLG} +
         * {@code MOVE <msg> TO WS-MESSAGE} + {@code PERFORM SEND-SIGNON-SCREEN} branch.
         *
         * @param message the error message to display; must not be {@code null}
         * @param cursor  the field to position the cursor on; must not be {@code null}
         * @return a {@link RoutingAction#SHOW_SIGNON} result of severity
         *         {@link MessageSeverity#ERROR}
         */
        public static SignonResult error(String message, CursorField cursor) {
            return new SignonResult(RoutingAction.SHOW_SIGNON,
                    Objects.requireNonNull(message, "message"), MessageSeverity.ERROR,
                    Objects.requireNonNull(cursor, "cursor"));
        }

        /**
         * Creates the authenticate outcome: inputs are valid, so the controller must invoke
         * authentication. Mirrors the COBOL {@code IF NOT ERR-FLG-ON PERFORM
         * READ-USER-SEC-FILE} hand-off.
         *
         * @return a {@link RoutingAction#AUTHENTICATE} result with no message
         */
        public static SignonResult authenticate() {
            return new SignonResult(RoutingAction.AUTHENTICATE, null, MessageSeverity.NONE,
                    CursorField.NONE);
        }

        /**
         * Creates the redirect outcome for a successful sign-on. The routing target has
         * already been written into the {@link CardDemoContext}. Mirrors the COBOL
         * {@code XCTL} to {@code COADM01C} / {@code COMEN01C}.
         *
         * @return a {@link RoutingAction#REDIRECT} result with no message
         */
        public static SignonResult redirect() {
            return new SignonResult(RoutingAction.REDIRECT, null, MessageSeverity.NONE,
                    CursorField.NONE);
        }

        /**
         * Creates the exit outcome carrying a neutral informational message. Mirrors the
         * COBOL PF3 branch ({@code MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE},
         * {@code PERFORM SEND-PLAIN-TEXT}, {@code EXEC CICS RETURN}).
         *
         * @param message the plain-text message to display; must not be {@code null}
         * @return a {@link RoutingAction#EXIT} result of severity
         *         {@link MessageSeverity#INFORMATION}
         */
        public static SignonResult exit(String message) {
            return new SignonResult(RoutingAction.EXIT,
                    Objects.requireNonNull(message, "message"), MessageSeverity.INFORMATION,
                    CursorField.NONE);
        }

        /**
         * Reports whether the controller should redisplay the sign-on screen.
         *
         * @return {@code true} when {@link #action} is {@link RoutingAction#SHOW_SIGNON}
         */
        public boolean isShowSignon() {
            return action == RoutingAction.SHOW_SIGNON;
        }

        /**
         * Reports whether the controller should invoke authentication.
         *
         * @return {@code true} when {@link #action} is {@link RoutingAction#AUTHENTICATE}
         */
        public boolean isAuthenticate() {
            return action == RoutingAction.AUTHENTICATE;
        }

        /**
         * Reports whether the controller should redirect after a successful sign-on.
         *
         * @return {@code true} when {@link #action} is {@link RoutingAction#REDIRECT}
         */
        public boolean isRedirect() {
            return action == RoutingAction.REDIRECT;
        }

        /**
         * Reports whether the controller should end the conversation with a plain-text
         * message.
         *
         * @return {@code true} when {@link #action} is {@link RoutingAction#EXIT}
         */
        public boolean isExit() {
            return action == RoutingAction.EXIT;
        }

        /**
         * Reports whether this outcome carries a message to display.
         *
         * @return {@code true} when a non-blank {@link #message()} is present
         */
        public boolean hasMessage() {
            return message != null && !message.isBlank();
        }

        /**
         * Reports whether this outcome carries an error message.
         *
         * @return {@code true} when {@link #severity()} is {@link MessageSeverity#ERROR}
         */
        public boolean isError() {
            return severity == MessageSeverity.ERROR;
        }
    }

    // --- Business paragraphs ------------------------------------------------

    /**
     * Handles a sign-on interaction, the Java migration of paragraph {@code MAIN-PARA} in
     * {@code legacy/cbl/COSGN00C.cbl}.
     *
     * <p>Reproduces the COBOL control flow. On first entry into the transaction (COBOL
     * {@code IF EIBCALEN = 0}, reproduced by {@link CardDemoContext#isNew()}) the empty
     * sign-on screen is presented with the cursor on the User ID field &mdash; this is the
     * normal initial display, not a bounce, because sign-on is the application entry point.
     * The paired controller latches first entry via {@link CardDemoContext#markInitialized()}
     * after this display, so the next submit is dispatched as re-entry.</p>
     *
     * <p>On re-entry the pressed attention key is evaluated (COBOL {@code EVALUATE EIBAID}):
     * {@link AidKey#ENTER} ({@code DFHENTER}) validates and signs on via
     * {@link #processEnterKey(COSGN00Form, CardDemoContext)}; {@link AidKey#PF3}
     * ({@code DFHPF3}) ends the conversation with the thank-you message; {@link AidKey#OTHER}
     * ({@code WHEN OTHER}) yields the invalid-key error. A {@code null} key collapses to
     * {@link AidKey#OTHER}, matching the COBOL default.</p>
     *
     * <p>The COBOL {@code MAIN-PARA} preamble ({@code SET ERR-FLG-OFF TO TRUE};
     * {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO}) needs no explicit reproduction: this method
     * is stateless and returns a fresh {@link SignonResult} on every invocation, so no
     * residual error flag or message can persist between interactions.</p>
     *
     * @param aid  the attention key submitted by the controller; {@code null} is treated as
     *             {@link AidKey#OTHER}
     * @param form the sign-on screen form carrying the entered User ID and password; must not
     *             be {@code null}
     * @return the {@link SignonResult} describing the controller's next action
     */
    @Observed(name = "carddemo.signon", contextualName = "signon")
    public SignonResult mainEntry(AidKey aid, COSGN00Form form) {
        Objects.requireNonNull(form, "form");

        // COBOL lines 80-83: IF EIBCALEN = 0 -> first entry; MOVE LOW-VALUES TO COSGN0AO,
        // MOVE -1 TO USERIDL, PERFORM SEND-SIGNON-SCREEN. isNew() is the EIBCALEN = 0 test.
        if (context.isNew()) {
            return SignonResult.showSignon();
        }

        // COBOL lines 84-95: ELSE EVALUATE EIBAID. A null AID collapses to WHEN OTHER.
        AidKey effectiveAid = (aid == null) ? AidKey.OTHER : aid;
        return switch (effectiveAid) {
            // COBOL lines 86-87: WHEN DFHENTER -> PERFORM PROCESS-ENTER-KEY.
            case ENTER -> processEnterKey(form, context);
            // COBOL lines 88-90: WHEN DFHPF3 -> MOVE CCDA-MSG-THANK-YOU, PERFORM SEND-PLAIN-TEXT.
            case PF3 -> SignonResult.exit(MSG_THANK_YOU);
            // COBOL lines 91-94: WHEN OTHER -> MOVE 'Y' TO WS-ERR-FLG, MOVE CCDA-MSG-INVALID-KEY,
            // PERFORM SEND-SIGNON-SCREEN. No cursor move in this branch.
            case OTHER -> SignonResult.error(MSG_INVALID_KEY, CursorField.NONE);
        };
    }

    /**
     * Validates the entered credentials and hands off to authentication, the Java migration
     * of paragraph {@code PROCESS-ENTER-KEY} in {@code legacy/cbl/COSGN00C.cbl}.
     *
     * <p>The COBOL {@code EXEC CICS RECEIVE MAP('COSGN0A')} is performed by the controller
     * (Spring MVC request binding into {@link COSGN00Form}); this method implements the
     * subsequent {@code EVALUATE TRUE} validation and the hand-off to
     * {@code READ-USER-SEC-FILE}:</p>
     * <ul>
     *   <li>{@code WHEN USERIDI = SPACES OR LOW-VALUES} -&gt; error "Please enter User ID ..."
     *       with the cursor on the User ID field (COBOL {@code MOVE -1 TO USERIDL});</li>
     *   <li>{@code WHEN PASSWDI = SPACES OR LOW-VALUES} -&gt; error "Please enter Password ..."
     *       with the cursor on the Password field (COBOL {@code MOVE -1 TO PASSWDL});</li>
     *   <li>otherwise the User ID is upper-cased into the context (COBOL
     *       {@code MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID, CDEMO-USER-ID}) and the
     *       result signals {@link RoutingAction#AUTHENTICATE} (COBOL
     *       {@code IF NOT ERR-FLG-ON PERFORM READ-USER-SEC-FILE}).</li>
     * </ul>
     *
     * <p>The entered screen field {@link COSGN00Form#getUserid()} is intentionally left in its
     * original case (as the COBOL leaves {@code USERIDI} untouched, upper-casing only the
     * working-storage / COMMAREA copies), so a subsequent error redisplay shows exactly what
     * the user typed. The password is <b>not</b> upper-cased or compared here: the COBOL
     * {@code MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD} fold and the cleartext
     * {@code SEC-USR-PWD = WS-USER-PWD} comparison are preserved in the security layer
     * ({@code CardDemoAuthenticationProvider}) per AAP &sect;0.6.7.</p>
     *
     * @param form the sign-on screen form carrying the entered User ID and password; must not
     *             be {@code null}
     * @param ctx  the session context to receive the upper-cased User ID; must not be
     *             {@code null}
     * @return an {@link RoutingAction#SHOW_SIGNON} error result when a field is blank, else an
     *         {@link RoutingAction#AUTHENTICATE} result
     */
    public SignonResult processEnterKey(COSGN00Form form, CardDemoContext ctx) {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(ctx, "ctx");

        // COBOL lines 117-122: WHEN USERIDI = SPACES OR LOW-VALUES.
        if (isBlankOrLowValues(form.getUserid())) {
            return SignonResult.error(MSG_ENTER_USER_ID, CursorField.USER_ID);
        }
        // COBOL lines 123-127: WHEN PASSWDI = SPACES OR LOW-VALUES.
        if (isBlankOrLowValues(form.getPasswd())) {
            return SignonResult.error(MSG_ENTER_PASSWORD, CursorField.PASSWORD);
        }

        // COBOL lines 132-134: MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID, CDEMO-USER-ID.
        // Locale.ROOT gives a deterministic, locale-independent fold, matching the security
        // layer's own upper-casing so the lookup keys agree. The form field is left unchanged.
        ctx.setUserId(form.getUserid().toUpperCase(Locale.ROOT));

        // COBOL lines 138-140: IF NOT ERR-FLG-ON PERFORM READ-USER-SEC-FILE. Authentication is
        // delegated to Spring Security, so the honest signal is AUTHENTICATE; the controller
        // invokes the AuthenticationManager and then calls readUserSecFile / mapAuthenticationFailure.
        return SignonResult.authenticate();
    }

    /**
     * Applies the successful-sign-on routing, the Java migration of the {@code WHEN 0} /
     * password-match branch of paragraph {@code READ-USER-SEC-FILE} in
     * {@code legacy/cbl/COSGN00C.cbl}.
     *
     * <p><b>Security delegation (AAP &sect;0.6.7).</b> The COBOL
     * {@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)} and the cleartext
     * {@code IF SEC-USR-PWD = WS-USER-PWD} comparison are preserved in the security layer:
     * {@link CardDemoUserDetailsService} performs the lookup and
     * {@code CardDemoAuthenticationProvider} performs the compare. This method is invoked by
     * the controller only <em>after</em> that authentication has already succeeded; it
     * re-loads the authenticated principal through
     * {@link CardDemoUserDetailsService#loadUserByUsername(String)} (the delegated
     * {@code USRSEC} read) purely to obtain the resolved {@code SEC-USR-TYPE} authority for
     * routing. Because the user was just authenticated, the record is present; the modest
     * cost of this second read buys a self-contained, independently testable routing method.</p>
     *
     * <p>Reproducing COBOL lines 222-240, on the matched user this method writes the
     * hand-off and identity into the context and puts the target program into its enter
     * state:</p>
     * <ul>
     *   <li>{@code MOVE WS-TRANID TO CDEMO-FROM-TRANID} -&gt;
     *       {@link CardDemoContext#setFromTranid(String)} = {@code CC00};</li>
     *   <li>{@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM} -&gt;
     *       {@link CardDemoContext#setFromProgram(String)} = {@code COSGN00C};</li>
     *   <li>{@code MOVE WS-USER-ID TO CDEMO-USER-ID} -&gt;
     *       {@link CardDemoContext#setUserId(String)};</li>
     *   <li>{@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} -&gt;
     *       {@link CardDemoContext#setAdmin()} / {@link CardDemoContext#setUser()};</li>
     *   <li>{@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} -&gt; {@link CardDemoContext#markEnter()};</li>
     *   <li>{@code XCTL PROGRAM('COADM01C')} / {@code PROGRAM('COMEN01C')} -&gt; the routing
     *       target {@link CardDemoContext#setToProgram(String)} /
     *       {@link CardDemoContext#setToTranid(String)} ({@code COADM01C}/{@code CA00} for an
     *       administrator, {@code COMEN01C}/{@code CM00} otherwise).</li>
     * </ul>
     *
     * <p>The user type is resolved from the principal's granted authority
     * ({@link CardDemoUserDetailsService#ROLE_ADMIN}) rather than by re-reading a raw type
     * flag, mirroring the COBOL {@code IF CDEMO-USRTYP-ADMIN} decision. The actual HTTP
     * redirect is performed by {@code SignonController} / {@code SecurityConfig}, not here.</p>
     *
     * @param userId the authenticated (already upper-cased) user id used for the delegated
     *               {@code USRSEC} read; must not be {@code null}
     * @param ctx    the session context to receive the routing decision; must not be
     *               {@code null}
     * @return a {@link RoutingAction#REDIRECT} result; the target is set on {@code ctx}
     */
    public SignonResult readUserSecFile(String userId, CardDemoContext ctx) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(ctx, "ctx");

        // COBOL lines 211-219: EXEC CICS READ USRSEC RIDFLD(WS-USER-ID). Delegated to the
        // security layer's user store; the compare already happened in the provider. Invoked
        // only on the post-authentication success path, so the record is present.
        UserDetails principal = userDetailsService.loadUserByUsername(userId);

        // COBOL line 230: IF CDEMO-USRTYP-ADMIN (88-level VALUE 'A'). Resolve admin from the
        // single granted authority produced by the user-type mapping.
        boolean admin = principal.getAuthorities().stream()
                .anyMatch(authority ->
                        CardDemoUserDetailsService.ROLE_ADMIN.equals(authority.getAuthority()));

        // COBOL lines 224-228: hand-off + identity + program-context reset (MOVE ZEROS).
        ctx.setFromTranid(TRANSACTION_ID);
        ctx.setFromProgram(PROGRAM_NAME);
        ctx.setUserId(userId);
        ctx.markEnter();

        if (admin) {
            // COBOL lines 230-234: MOVE SEC-USR-TYPE('A'); XCTL PROGRAM('COADM01C').
            ctx.setAdmin();
            ctx.setToProgram(ADMIN_PROGRAM);
            ctx.setToTranid(ADMIN_TRANSACTION_ID);
        } else {
            // COBOL lines 235-239: ELSE -> MOVE SEC-USR-TYPE('U'); XCTL PROGRAM('COMEN01C').
            ctx.setUser();
            ctx.setToProgram(USER_PROGRAM);
            ctx.setToTranid(USER_TRANSACTION_ID);
        }

        return SignonResult.redirect();
    }

    /**
     * Translates an authentication failure into the matching sign-on error, the Java
     * migration of the failure branches of paragraph {@code READ-USER-SEC-FILE} in
     * {@code legacy/cbl/COSGN00C.cbl}. The controller calls this from its authentication
     * failure handler, preserving the COBOL distinction between the "User not found",
     * "Wrong Password", and "Unable to verify" messages.
     *
     * <p>Mapping of the COBOL {@code EVALUATE WS-RESP-CD} failure paths:</p>
     * <ul>
     *   <li>{@link UsernameNotFoundException} (thrown by {@link CardDemoUserDetailsService})
     *       -&gt; COBOL {@code WHEN 13}: "User not found. Try again ..." with the cursor on
     *       the User ID field ({@code MOVE -1 TO USERIDL});</li>
     *   <li>{@link BadCredentialsException} (thrown by {@code CardDemoAuthenticationProvider}
     *       on a cleartext {@code SEC-USR-PWD} mismatch) -&gt; COBOL {@code WHEN 0} /
     *       password-mismatch: "Wrong Password. Try again ..." with the cursor on the
     *       Password field ({@code MOVE -1 TO PASSWDL});</li>
     *   <li>any other {@link AuthenticationException} (including {@code null}) -&gt; COBOL
     *       {@code WHEN OTHER}: "Unable to verify the User ..." with the cursor on the User ID
     *       field.</li>
     * </ul>
     *
     * @param failure the authentication failure raised by Spring Security; a {@code null} or
     *                unrecognized failure maps to the {@code WHEN OTHER} default
     * @return an {@link RoutingAction#SHOW_SIGNON} error {@link SignonResult} carrying the
     *         COBOL message and cursor position for the failure
     */
    public SignonResult mapAuthenticationFailure(AuthenticationException failure) {
        // COBOL WHEN 13: user id had no USRSEC record.
        if (failure instanceof UsernameNotFoundException) {
            return SignonResult.error(MSG_USER_NOT_FOUND, CursorField.USER_ID);
        }
        // COBOL WHEN 0 with SEC-USR-PWD mismatch: cleartext password compare failed.
        if (failure instanceof BadCredentialsException) {
            return SignonResult.error(MSG_WRONG_PASSWORD, CursorField.PASSWORD);
        }
        // COBOL WHEN OTHER: any other response code (or an unexpected failure).
        return SignonResult.error(MSG_UNABLE_TO_VERIFY, CursorField.USER_ID);
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Reports whether a screen field is empty in the COBOL sense, reproducing the
     * {@code = SPACES OR LOW-VALUES} test of {@code PROCESS-ENTER-KEY}. A field is treated as
     * empty when it is {@code null} (never submitted), all spaces (COBOL {@code SPACES}), or
     * all NUL characters (COBOL {@code LOW-VALUES}). A single scan classifies all three cases.
     *
     * @param value the field value to test; may be {@code null}
     * @return {@code true} when the value is {@code null}, empty, all spaces, or all NULs
     */
    private static boolean isBlankOrLowValues(String value) {
        if (value == null) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ' && character != '\0') {
                return false;
            }
        }
        return true;
    }
}
