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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COUSR01Form;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * User-add online service, the Java migration of the CICS COBOL program
 * {@code COUSR01C} (the AWS CardDemo "Add a new Regular/Admin user to the USRSEC file" screen).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COUSR01C.cbl} &mdash; program {@code COUSR01C}, CICS
 * transaction id {@code CU01}. This service preserves the original program's control flow
 * one-for-one: each business COBOL paragraph becomes exactly one Java method (AAP &sect;0.3.3,
 * &sect;0.4.1). It is the write ("add") counterpart of the user-administration family
 * ({@code COUSR00C}/{@code COUSR01C}/{@code COUSR02C}/{@code COUSR03C}) and performs the single
 * {@code USRSEC} record insert.</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #mainEntry(AidKey, COUSR01Form)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(COUSR01Form, CardDemoContext)}</li>
 *   <li>{@code WRITE-USER-SEC-FILE} &rarr; {@link #writeUserSecFile(UserSecurity, COUSR01Form)}</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr; {@link #clearCurrentScreen(COUSR01Form)}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr; {@link #initializeAllFields(COUSR01Form)}</li>
 * </ul>
 *
 * <p>The presentation paragraphs {@code SEND-USRADD-SCREEN}, {@code RECEIVE-USRADD-SCREEN} and
 * {@code POPULATE-HEADER-INFO} are intentionally <em>not</em> implemented here: sending and
 * receiving the 3270/BMS map and populating the header (title/date/time) are presentation concerns
 * owned by the paired {@code UserAdminController}. Of {@code RETURN-TO-PREV-SCREEN} only the
 * navigation-state bookkeeping (defaulting the hand-off target and recording this program as the
 * origin in {@link CardDemoContext}) is reproduced &mdash; privately, by
 * {@link #returnToPreviousScreen(String)} &mdash; so that the session context matches the legacy
 * COMMAREA exactly; the physical transfer (COBOL {@code XCTL}) is performed by the controller
 * acting on the returned redirect outcome. This service contains business logic only &mdash; it
 * validates input, maps the form to the entity, writes the record, decides routing, and produces
 * messages &mdash; and never touches screen I/O.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) that carried navigation state across CICS returns
 * becomes the session-scoped {@link CardDemoContext}, injected here. First entry into the
 * transaction (COBOL {@code EIBCALEN = 0}) is reproduced by {@link CardDemoContext#isNew()}; the
 * {@code XCTL}/{@code RETURN TRANSID} hand-off is reproduced through the context's
 * {@code to*} program fields, which the controller consults when redirecting.</p>
 *
 * <h2>Outcome model</h2>
 * <p>Because the controller performs the redirect and the screen rendering, each business method
 * returns an immutable {@link UserAddResult} describing what should happen next: a redirect to a
 * target program (the COBOL {@code XCTL}), or a redisplay of the add screen carrying an optional
 * message. The result distinguishes an error line (COBOL error, styled red) from the green
 * "has been added" confirmation line (COBOL {@code DFHGREEN}), and it names the field that should
 * receive the cursor (COBOL {@code MOVE -1 TO <field>L}), so this service preserves the screen
 * contract without knowing how the message or cursor is rendered.</p>
 *
 * <h2>Transaction boundary (AAP &sect;0.3.3, &sect;0.6.5)</h2>
 * <p>The {@code USRSEC} insert reproduces the CICS {@code EXEC CICS WRITE} unit of work, so the
 * write path is {@link Transactional @Transactional}. Because the controller enters through
 * {@link #mainEntry(AidKey, COUSR01Form)} and the paragraph methods call one another directly
 * (Java self-invocation, which the Spring transaction proxy does not intercept), every public
 * method that can reach the write &mdash; {@link #mainEntry(AidKey, COUSR01Form)},
 * {@link #processEnterKey(COUSR01Form, CardDemoContext)} and
 * {@link #writeUserSecFile(UserSecurity, COUSR01Form)} &mdash; is annotated, so a transaction is
 * active whichever of them is the proxied entry point. A {@link DuplicateKeyException} (or any
 * other unchecked persistence failure) rolls the transaction back, so a rejected add persists
 * nothing.</p>
 *
 * <h2>Security and design constraints</h2>
 * <ul>
 *   <li><b>Admin-only:</b> {@code COUSR01C} is reachable only by administrator users; that gate
 *       is enforced upstream by Spring Security URL/method authorization and the admin-menu
 *       routing, not by this service.</li>
 *   <li><b>Cleartext password parity (AAP &sect;0.6.7 &mdash; intentional):</b> the legacy program
 *       moved the entered password verbatim into {@code SEC-USR-PWD} and the {@code USRSEC} store
 *       held it as cleartext, so {@link #processEnterKey(COUSR01Form, CardDemoContext)} maps the
 *       form password straight onto {@link UserSecurity#setUsrPwd(String)} with no hashing, for
 *       100% functional parity. Introducing password hashing (e.g. BCrypt/PBKDF2) is deliberately
 *       out of scope here and is recorded as a suggested next task in {@code docs/decision-log.md}.
 *       No credential is hardcoded: the value is whatever the administrator typed.</li>
 *   <li><b>Validation parity:</b> the program validates only that the five entry fields are
 *       non-empty; in particular the user type is checked for non-emptiness only, with <em>no</em>
 *       strict {@code A}/{@code U} value enforcement. That exact behaviour (including the quirk) is
 *       preserved &mdash; no extra validation is added.</li>
 *   <li><b>Constructor injection:</b> the service depends only on the session context and the user
 *       repository; there is no field injection and no Lombok.</li>
 *   <li><b>Message literals:</b> the empty-field, duplicate, "unable to add" and success texts are
 *       byte-exact copies of the COBOL literals; the invalid-key text mirrors
 *       {@code CCDA-MSG-INVALID-KEY} from {@code legacy/cpy/CSMSG01Y.cpy}. They are declared as
 *       local constants because the shared message-constants holder is not a declared dependency of
 *       this service.</li>
 * </ul>
 */
@Service
public class UserAddService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'} &mdash; this program's name. */
    private static final String PROGRAM_NAME = "COUSR01C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CU01'} &mdash; this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CU01";

    /**
     * Sign-on program (COBOL literal {@code 'COSGN00C'}) &mdash; the destination of the first-entry
     * bounce ({@code EIBCALEN = 0}, {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}).
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Admin-menu program (COBOL literal {@code 'COADM01C'}) &mdash; the PF3 return destination
     * ({@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM}).
     */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** Success-message prefix; COBOL {@code STRING 'User ' DELIMITED BY SIZE ...}. */
    private static final String ADDED_PREFIX = "User ";

    /** Success-message suffix; COBOL {@code STRING ... ' has been added ...' DELIMITED BY SIZE}. */
    private static final String ADDED_SUFFIX = " has been added ...";

    /**
     * Invalid-key message. Mirrors COBOL {@code CCDA-MSG-INVALID-KEY} (from
     * {@code legacy/cpy/CSMSG01Y.cpy}), moved to {@code WS-MESSAGE} in {@code MAIN-PARA} for any
     * unmapped AID key. Declared locally because the shared message-constants holder is not a
     * declared dependency of this service.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Byte-exact COBOL literal for the empty first-name error ({@code PROCESS-ENTER-KEY}). */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Byte-exact COBOL literal for the empty last-name error ({@code PROCESS-ENTER-KEY}). */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** Byte-exact COBOL literal for the empty user-id error ({@code PROCESS-ENTER-KEY}). */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Byte-exact COBOL literal for the empty password error ({@code PROCESS-ENTER-KEY}). */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** Byte-exact COBOL literal for the empty user-type error ({@code PROCESS-ENTER-KEY}). */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /**
     * Byte-exact COBOL literal for the duplicate-key error, moved to {@code WS-MESSAGE} in the
     * {@code WRITE-USER-SEC-FILE} {@code WHEN DFHRESP(DUPKEY) / WHEN DFHRESP(DUPREC)} branch.
     */
    private static final String MSG_USER_ID_EXISTS = "User ID already exist...";

    /**
     * Byte-exact COBOL literal for the catch-all write failure, moved to {@code WS-MESSAGE} in the
     * {@code WRITE-USER-SEC-FILE} {@code WHEN OTHER} branch.
     */
    private static final String MSG_UNABLE_TO_ADD = "Unable to Add User...";

    /**
     * Session-scoped navigation and selection context, the modern replacement for the COBOL
     * COMMAREA ({@code COCOM01Y}). Injected as a Spring session-scoped proxy.
     */
    private final CardDemoContext context;

    /**
     * Spring Data repository for the {@code USRSEC} user-security store, replacing the CICS
     * {@code EXEC CICS WRITE DATASET('USRSEC')} keyed record I/O.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the user-add service via Spring constructor injection.
     *
     * <p>A single constructor means no {@code @Autowired} annotation is required. Neither argument
     * is dereferenced here, so the constructor introduces no {@code this}-escape.</p>
     *
     * @param context                the session-scoped CardDemo context (COMMAREA replacement);
     *                               must not be {@code null}
     * @param userSecurityRepository the {@code USRSEC} repository used to persist the new user;
     *                               must not be {@code null}
     */
    public UserAddService(CardDemoContext context, UserSecurityRepository userSecurityRepository) {
        this.context = context;
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Attention-identifier (AID) keys handled by {@link #mainEntry(AidKey, COUSR01Form)}, mirroring
     * the COBOL {@code EVALUATE EIBAID} of {@code MAIN-PARA}.
     *
     * <p>The paired {@code UserAdminController} maps the inbound HTTP submission (the pressed button
     * or PF key) to one of these constants before delegating to the service. Only the keys with
     * distinct behaviour in {@code COUSR01C} are modelled: {@link #ENTER} ({@code DFHENTER}),
     * {@link #PF3} ({@code DFHPF3}) and {@link #PF4} ({@code DFHPF4}); every other key collapses to
     * {@link #OTHER}, matching the COBOL {@code WHEN OTHER} default.</p>
     */
    public enum AidKey {

        /** COBOL {@code DFHENTER} &mdash; the ENTER key; validates and adds the entered user. */
        ENTER,

        /** COBOL {@code DFHPF3} &mdash; the PF3 key; returns to the admin menu ({@code COADM01C}). */
        PF3,

        /** COBOL {@code DFHPF4} &mdash; the PF4 key; clears the add screen. */
        PF4,

        /** COBOL {@code WHEN OTHER} &mdash; any other key; yields the invalid-key message. */
        OTHER
    }

    /**
     * Identifies the entry field that should receive the cursor when the add screen is redisplayed,
     * reproducing the COBOL {@code MOVE -1 TO <field>L OF COUSR1AI} cursor-positioning of
     * {@code COUSR01C}.
     *
     * <p>The paired controller translates the selected value into the equivalent client-side focus
     * (for example an {@code autofocus} attribute) so the 3270 cursor behaviour is preserved on the
     * rendered screen. Each constant maps one-for-one to the COBOL length ({@code L}) subfield the
     * program drives {@code -1} into.</p>
     */
    public enum CursorField {

        /** COBOL {@code FNAMEL} &mdash; the first-name entry field. */
        FIRST_NAME,

        /** COBOL {@code LNAMEL} &mdash; the last-name entry field. */
        LAST_NAME,

        /** COBOL {@code USERIDL} &mdash; the user-id entry field. */
        USER_ID,

        /** COBOL {@code PASSWDL} &mdash; the password entry field. */
        PASSWORD,

        /** COBOL {@code USRTYPEL} &mdash; the user-type entry field. */
        USER_TYPE
    }

    /**
     * Immutable outcome of a user-add interaction, describing what the controller should do next:
     * perform a redirect ({@code XCTL}) or redisplay the add screen with an optional message and a
     * cursor position.
     *
     * <p>Two broad shapes are produced:</p>
     * <ul>
     *   <li><b>Redirect</b> &mdash; {@link #targetProgram()} is set (and {@link #isRedirect()} is
     *       {@code true}); the controller redirects to the route mapped from that program name. This
     *       reproduces the COBOL {@code XCTL} performed by {@code RETURN-TO-PREV-SCREEN}.
     *       {@link #cursorField()} is {@code null} for a redirect.</li>
     *   <li><b>Redisplay</b> &mdash; {@link #targetProgram()} is {@code null}; the controller
     *       redisplays the add screen. {@link #message()} carries the line to show (or {@code null}
     *       for a cleared screen with no message, as after PF4), {@link #error()} distinguishes an
     *       error line (COBOL error, styled red) from the green "has been added" confirmation, and
     *       {@link #cursorField()} names the field that should receive the cursor (COBOL
     *       {@code MOVE -1 TO <field>L}).</li>
     * </ul>
     *
     * @param targetProgram the target program name for a redirect, or {@code null} for a redisplay
     * @param message       the message line to redisplay, or {@code null} when none is shown
     * @param error         {@code true} when {@code message} is an error line; {@code false} for the
     *                      green confirmation line, a cleared screen, or a redirect
     * @param cursorField   the field that should receive the cursor on redisplay, or {@code null}
     *                      for a redirect
     */
    public record UserAddResult(String targetProgram, String message, boolean error, CursorField cursorField) {

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
         * Creates a redirect outcome targeting the given program (the COBOL {@code XCTL}
         * destination).
         *
         * @param targetProgram the target program name
         * @return a redirect outcome with no message and no cursor field
         */
        private static UserAddResult ofRedirect(String targetProgram) {
            return new UserAddResult(targetProgram, null, false, null);
        }

        /**
         * Creates an error-message (redisplay) outcome that positions the cursor on the offending
         * field.
         *
         * @param message     the error message to redisplay
         * @param cursorField the field that should receive the cursor
         * @return an error outcome
         */
        private static UserAddResult ofError(String message, CursorField cursorField) {
            return new UserAddResult(null, message, true, cursorField);
        }

        /**
         * Creates a success (redisplay) outcome carrying the green confirmation line.
         *
         * @param message     the confirmation message to redisplay
         * @param cursorField the field that should receive the cursor
         * @return a non-error message outcome
         */
        private static UserAddResult ofSuccess(String message, CursorField cursorField) {
            return new UserAddResult(null, message, false, cursorField);
        }

        /**
         * Creates a redisplay outcome with no message (a cleared screen), positioning the cursor on
         * the given field. Reproduces the COBOL PF4 {@code CLEAR-CURRENT-SCREEN} redisplay.
         *
         * @param cursorField the field that should receive the cursor
         * @return a message-less redisplay outcome
         */
        private static UserAddResult ofRedisplay(CursorField cursorField) {
            return new UserAddResult(null, null, false, cursorField);
        }
    }

    /**
     * Handles a user-add interaction, the Java migration of paragraph {@code MAIN-PARA} in
     * {@code legacy/cbl/COUSR01C.cbl}.
     *
     * <p>Reproduces the COBOL control flow. The paragraph opens by clearing the error flag and the
     * message ({@code SET ERR-FLG-OFF TO TRUE}, {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO}); in this
     * design a clean interaction simply returns a result carrying no error message, so no explicit
     * reset is needed. On first entry into the transaction (COBOL {@code EIBCALEN = 0}, reproduced
     * by {@link CardDemoContext#isNew()}) the hand-off target is set to the sign-on program and a
     * redirect there is returned ({@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}, then
     * {@code RETURN-TO-PREV-SCREEN}). Otherwise the pressed AID key is evaluated: {@code ENTER}
     * validates and adds the user, {@code PF3} returns to the admin menu ({@code COADM01C}),
     * {@code PF4} clears the screen, and any other key yields the invalid-key message with the
     * cursor on the first-name field.</p>
     *
     * <p>The COBOL first-display branch ({@code IF NOT CDEMO-PGM-REENTER ... SEND-USRADD-SCREEN})
     * is intentionally not reproduced here: rendering the blank add screen for the first time is a
     * presentation concern owned by the paired controller (its GET handler), whereas this method
     * handles the submitted interaction (the COBOL {@code RECEIVE} plus {@code EVALUATE EIBAID}).
     * The navigation-state bookkeeping of {@code RETURN-TO-PREV-SCREEN} (defaulting the hand-off
     * target and recording this program as the origin) is reproduced by
     * {@link #returnToPreviousScreen(String)} so that the session context matches the legacy
     * COMMAREA exactly; only the physical screen transfer (COBOL {@code XCTL}/{@code SEND}) is
     * performed by the controller acting on the returned redirect outcome.</p>
     *
     * @param aid  the attention-identifier key pressed; a {@code null} value is treated as
     *             {@link AidKey#OTHER}, matching the COBOL {@code WHEN OTHER} default
     * @param form the submitted user-add screen form (map {@code COUSR1A} of mapset {@code COUSR01})
     * @return the interaction outcome: a redirect target, or a redisplay with an optional message
     *         and cursor position
     */
    @Transactional
    public UserAddResult mainEntry(AidKey aid, COUSR01Form form) {
        // MAIN-PARA: IF EIBCALEN = 0 -> MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, RETURN-TO-PREV-SCREEN.
        if (context.isNew()) {
            return returnToPreviousScreen(SIGNON_PROGRAM);
        }
        // ELSE (re-entry): EVALUATE EIBAID. A null AID collapses to the WHEN OTHER branch.
        AidKey effectiveAid = (aid == null) ? AidKey.OTHER : aid;
        return switch (effectiveAid) {
            case ENTER -> processEnterKey(form, context);
            // DFHPF3: MOVE 'COADM01C' TO CDEMO-TO-PROGRAM, then RETURN-TO-PREV-SCREEN.
            case PF3 -> returnToPreviousScreen(ADMIN_MENU_PROGRAM);
            case PF4 -> clearCurrentScreen(form);
            case OTHER -> UserAddResult.ofError(MSG_INVALID_KEY, CursorField.FIRST_NAME);
        };
    }

    /**
     * Validates the entered fields and, when all are present, maps the form to a {@link UserSecurity}
     * and writes it; the Java migration of paragraph {@code PROCESS-ENTER-KEY} in
     * {@code legacy/cbl/COUSR01C.cbl}.
     *
     * <p>Reproduces the COBOL {@code EVALUATE TRUE} exactly: the five entry fields are checked in
     * order &mdash; first name, last name, user id, password, user type &mdash; and the <em>first</em>
     * empty field wins, yielding its byte-exact error message and positioning the cursor on that
     * field (COBOL {@code WHEN <field> = SPACES OR LOW-VALUES}). Emptiness follows the COBOL
     * {@code SPACES OR LOW-VALUES} test: a {@code null} or all-blank value is empty. Note that the
     * user type is only checked for non-emptiness; there is deliberately <em>no</em>
     * {@code A}/{@code U} value enforcement, matching {@code COUSR01C}.</p>
     *
     * <p>When every field is present (the COBOL {@code WHEN OTHER} fall-through, {@code IF NOT
     * ERR-FLG-ON}) the form values are moved onto a new {@link UserSecurity} exactly as the COBOL
     * moves {@code USERIDI/FNAMEI/LNAMEI/PASSWDI/USRTYPEI} into
     * {@code SEC-USR-ID/-FNAME/-LNAME/-PWD/-TYPE}. The password is copied verbatim (cleartext) for
     * parity (AAP &sect;0.6.7). Control then flows to {@link #writeUserSecFile(UserSecurity, COUSR01Form)}.</p>
     *
     * @param form the submitted screen form supplying the entry fields; a {@code null} form is
     *             treated as an empty first name, matching the first COBOL emptiness branch
     * @param ctx  the session context; accepted for calling-convention consistency with the other
     *             paragraph methods. {@code PROCESS-ENTER-KEY} does not read or mutate the COMMAREA,
     *             so this parameter is not modified here
     * @return an error redisplay for the first empty field, or the outcome of the subsequent write
     */
    @Transactional
    public UserAddResult processEnterKey(COUSR01Form form, CardDemoContext ctx) {
        // EVALUATE TRUE: first empty field wins. A null form has no values -> first-name is empty.
        if (form == null || isEmpty(form.getFname())) {
            return UserAddResult.ofError(MSG_FIRST_NAME_EMPTY, CursorField.FIRST_NAME);
        }
        if (isEmpty(form.getLname())) {
            return UserAddResult.ofError(MSG_LAST_NAME_EMPTY, CursorField.LAST_NAME);
        }
        if (isEmpty(form.getUserid())) {
            return UserAddResult.ofError(MSG_USER_ID_EMPTY, CursorField.USER_ID);
        }
        if (isEmpty(form.getPasswd())) {
            return UserAddResult.ofError(MSG_PASSWORD_EMPTY, CursorField.PASSWORD);
        }
        if (isEmpty(form.getUsrtype())) {
            return UserAddResult.ofError(MSG_USER_TYPE_EMPTY, CursorField.USER_TYPE);
        }

        // WHEN OTHER / IF NOT ERR-FLG-ON: map the form to SEC-USER-DATA and write it.
        // MOVE USERIDI -> SEC-USR-ID, FNAMEI -> SEC-USR-FNAME, LNAMEI -> SEC-USR-LNAME,
        // PASSWDI -> SEC-USR-PWD (cleartext, AAP 0.6.7), USRTYPEI -> SEC-USR-TYPE.
        UserSecurity user = new UserSecurity();
        user.setUsrId(form.getUserid());
        user.setUsrFname(form.getFname());
        user.setUsrLname(form.getLname());
        user.setUsrPwd(form.getPasswd());
        user.setUsrType(form.getUsrtype());
        return writeUserSecFile(user, form);
    }

    /**
     * Writes the new user record to the {@code USRSEC} store and interprets the outcome, the Java
     * migration of paragraph {@code WRITE-USER-SEC-FILE} in {@code legacy/cbl/COUSR01C.cbl}.
     *
     * <p>Reproduces the COBOL {@code EXEC CICS WRITE DATASET('USRSEC') RIDFLD(SEC-USR-ID)} and the
     * {@code EVALUATE WS-RESP-CD} that follows:</p>
     * <ul>
     *   <li><b>{@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)}</b> &mdash; a keyed write fails when
     *       the {@code RIDFLD} key already exists. This is detected deterministically with the
     *       repository's inherited {@code existsById} and surfaced as a
     *       {@link DuplicateKeyException} carrying the byte-exact "User ID already exist..." message
     *       (FILE STATUS {@code "22"}), which the {@code @ControllerAdvice} renders as the BMS
     *       error line (AAP &sect;0.6.5). A concurrent insert that slips past the existence check is
     *       caught as {@link DataIntegrityViolationException} and mapped to the same exception. Being
     *       unchecked, it rolls the {@link Transactional @Transactional} write back, so nothing
     *       persists.</li>
     *   <li><b>{@code WHEN OTHER}</b> &mdash; any other write failure yields the byte-exact "Unable
     *       to Add User..." error redisplay, with the cursor on the first-name field.</li>
     *   <li><b>{@code DFHRESP(NORMAL)}</b> &mdash; on success the screen fields are cleared
     *       ({@code PERFORM INITIALIZE-ALL-FIELDS}) and the green confirmation line is built with the
     *       COBOL {@code STRING 'User ' SEC-USR-ID DELIMITED BY SPACE ' has been added ...'}
     *       concatenation, with the cursor on the first-name field.</li>
     * </ul>
     *
     * @param user the fully populated user-security record to insert (the COBOL {@code SEC-USER-DATA})
     * @param form the screen form, cleared in place on a successful add ({@code INITIALIZE-ALL-FIELDS})
     * @return the green confirmation redisplay on success, or the "Unable to Add User..." error
     *         redisplay for a non-duplicate failure
     * @throws DuplicateKeyException when the user id already exists (COBOL {@code DUPKEY}/{@code DUPREC})
     */
    @Transactional
    public UserAddResult writeUserSecFile(UserSecurity user, COUSR01Form form) {
        String userId = user.getUsrId();

        // WHEN DFHRESP(DUPKEY) / WHEN DFHRESP(DUPREC): the RIDFLD key already exists.
        if (userId != null && userSecurityRepository.existsById(userId)) {
            throw new DuplicateKeyException(MSG_USER_ID_EXISTS);
        }

        try {
            // WHEN DFHRESP(NORMAL): persist the new USRSEC record.
            userSecurityRepository.save(user);
        } catch (DataIntegrityViolationException duplicateAtSave) {
            // Race: a concurrent insert created the same key after the existence check above.
            // This is still the CICS DUPKEY/DUPREC condition, so map it to the same typed exception.
            throw new DuplicateKeyException(MSG_USER_ID_EXISTS, duplicateAtSave);
        } catch (DataAccessException writeFailure) {
            // WHEN OTHER: any other FILE STATUS / RESP -> redisplay "Unable to Add User...".
            return UserAddResult.ofError(MSG_UNABLE_TO_ADD, CursorField.FIRST_NAME);
        }

        // NORMAL post-processing: INITIALIZE-ALL-FIELDS, then the green confirmation message.
        initializeAllFields(form);
        String message = ADDED_PREFIX + delimitBySpace(userId) + ADDED_SUFFIX;
        return UserAddResult.ofSuccess(message, CursorField.FIRST_NAME);
    }

    /**
     * Clears the add screen and redisplays it, the Java migration of paragraph
     * {@code CLEAR-CURRENT-SCREEN} in {@code legacy/cbl/COUSR01C.cbl}.
     *
     * <p>Mirrors the COBOL {@code PERFORM INITIALIZE-ALL-FIELDS} followed by
     * {@code PERFORM SEND-USRADD-SCREEN}: the entry fields are reset in place and a message-less
     * redisplay is returned with the cursor on the first-name field. The actual send is performed by
     * the controller acting on the returned outcome.</p>
     *
     * @param form the screen form whose entry fields are reset in place
     * @return a message-less redisplay outcome with the cursor on the first-name field
     */
    public UserAddResult clearCurrentScreen(COUSR01Form form) {
        initializeAllFields(form);
        return UserAddResult.ofRedisplay(CursorField.FIRST_NAME);
    }

    /**
     * Resets the add-screen entry fields, the Java migration of paragraph
     * {@code INITIALIZE-ALL-FIELDS} in {@code legacy/cbl/COUSR01C.cbl}.
     *
     * <p>Mirrors the COBOL {@code MOVE SPACES TO USERIDI, FNAMEI, LNAMEI, PASSWDI, USRTYPEI}: each of
     * the five entry fields is set to the empty string (the trimmed form of a blanked fixed-width
     * field). The paragraph also drives the cursor to the first-name field
     * ({@code MOVE -1 TO FNAMEL}) and clears {@code WS-MESSAGE}; in this design those two effects are
     * expressed by the caller's {@link UserAddResult} (cursor {@link CursorField#FIRST_NAME} and a
     * {@code null} message), so this method only clears the form's data fields. A {@code null} form
     * is tolerated as a no-op.</p>
     *
     * @param form the screen form whose entry fields are reset in place; ignored when {@code null}
     */
    public void initializeAllFields(COUSR01Form form) {
        if (form == null) {
            return;
        }
        form.setUserid("");
        form.setFname("");
        form.setLname("");
        form.setPasswd("");
        form.setUsrtype("");
    }

    /**
     * Reproduces the navigation-state bookkeeping of paragraph {@code RETURN-TO-PREV-SCREEN} in
     * {@code legacy/cbl/COUSR01C.cbl}, returning a redirect outcome for the paired controller to act
     * on.
     *
     * <p>Mirrors the COBOL default-and-record sequence executed just before the {@code XCTL}: when
     * the hand-off target is unset (COBOL {@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES}) it
     * defaults to the sign-on program {@code COSGN00C}; then this program is recorded as the origin
     * ({@code MOVE WS-TRANID TO CDEMO-FROM-TRANID}, {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM});
     * and the program context is reset to the enter state ({@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}),
     * reproduced by {@link CardDemoContext#markEnter()}. The COBOL commented-out
     * {@code CDEMO-USER-ID}/{@code CDEMO-USER-TYPE} moves are intentionally not reproduced. The
     * physical transfer (COBOL {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)}) is left to the controller,
     * which redirects using the outcome returned here.</p>
     *
     * @param targetProgram the hand-off target set by the caller (COBOL {@code CDEMO-TO-PROGRAM}); a
     *                       {@code null} or blank value defaults to the sign-on program
     * @return a redirect outcome targeting the (possibly defaulted) hand-off program
     */
    private UserAddResult returnToPreviousScreen(String targetProgram) {
        // IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES -> MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.
        String resolvedTarget = isEmpty(targetProgram) ? SIGNON_PROGRAM : targetProgram;
        context.setToProgram(resolvedTarget);
        // MOVE WS-TRANID TO CDEMO-FROM-TRANID; MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM.
        context.setFromTranid(TRANSACTION_ID);
        context.setFromProgram(PROGRAM_NAME);
        // MOVE ZEROS TO CDEMO-PGM-CONTEXT (return to the enter state for the next program).
        context.markEnter();
        return UserAddResult.ofRedirect(resolvedTarget);
    }

    /**
     * Reports whether an entry value is empty by the COBOL {@code SPACES OR LOW-VALUES} test.
     *
     * <p>A {@code null} value reproduces {@code LOW-VALUES} (an unset field) and an all-whitespace
     * value reproduces {@code SPACES} (a blanked fixed-width field); either is treated as empty.</p>
     *
     * @param value the entry value to test (may be {@code null})
     * @return {@code true} when the value is {@code null} or contains only whitespace
     */
    private static boolean isEmpty(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Returns the leading portion of a value up to the first space, reproducing the COBOL
     * {@code STRING ... DELIMITED BY SPACE} applied to {@code SEC-USR-ID} when building the
     * confirmation message.
     *
     * <p>{@code SEC-USR-ID} is a space-padded {@code PIC X(08)} field, and {@code DELIMITED BY SPACE}
     * copies characters only up to (but not including) the first space, dropping the trailing pad.
     * A {@code null} value maps to the empty string; a value that begins with a space yields the
     * empty string, matching the COBOL behaviour exactly.</p>
     *
     * @param value the value to truncate at the first space (may be {@code null})
     * @return the substring before the first space, or the whole value if it contains no space, or
     *         the empty string when {@code value} is {@code null}
     */
    private static String delimitBySpace(String value) {
        if (value == null) {
            return "";
        }
        int firstSpace = value.indexOf(' ');
        return (firstSpace >= 0) ? value.substring(0, firstSpace) : value;
    }
}
