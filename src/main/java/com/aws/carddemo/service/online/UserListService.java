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
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COUSR00Form;
import com.aws.carddemo.exception.EndOfFileException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * User-list online service, the Java migration of the CICS COBOL program
 * {@code COUSR00C} (the AWS CardDemo administrator "List Users" screen).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COUSR00C.cbl} &mdash; program {@code COUSR00C},
 * CICS transaction id {@code CU00}. This service preserves the original program's control
 * flow one-for-one: each business COBOL paragraph becomes exactly one Java method
 * (AAP &sect;0.3.3, &sect;0.4.1). It is an <em>admin-only</em>, <em>read-only</em>
 * paged-browse of the {@code USRSEC} security file: ten user rows are displayed per page and
 * PF7/PF8 page backward/forward, mirroring the COBOL {@code STARTBR}/{@code READNEXT}/
 * {@code READPREV}/{@code ENDBR} browse.</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #mainEntry(AidKey, COUSR00Form, UserListState)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(COUSR00Form, CardDemoContext)}</li>
 *   <li>{@code PROCESS-PF7-KEY} &rarr; {@link #processPf7Key(UserListState)}</li>
 *   <li>{@code PROCESS-PF8-KEY} &rarr; {@link #processPf8Key(UserListState)}</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} &rarr; {@link #processPageForward(BrowseWork, String, AidKey)}</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} &rarr; {@link #processPageBackward(BrowseWork, String, AidKey)}</li>
 *   <li>{@code POPULATE-USER-DATA} &rarr; {@link #populateUserData(BrowseWork, int, UserSecurity)}</li>
 *   <li>{@code INITIALIZE-USER-DATA} &rarr; {@link #initializeUserData(BrowseWork)}</li>
 *   <li>{@code STARTBR-USER-SEC-FILE} &rarr; {@link #startBrowse(UserSecBrowse, String)}</li>
 *   <li>{@code READNEXT-USER-SEC-FILE} &rarr; {@link #readNext(UserSecBrowse)}</li>
 *   <li>{@code READPREV-USER-SEC-FILE} &rarr; {@link #readPrev(UserSecBrowse)}</li>
 *   <li>{@code ENDBR-USER-SEC-FILE} &rarr; {@link #endBrowse(UserSecBrowse)}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr; {@link #returnToPrevScreen(CardDemoContext)}</li>
 * </ul>
 *
 * <p>The presentation paragraphs {@code SEND-USRLST-SCREEN}, {@code RECEIVE-USRLST-SCREEN} and
 * {@code POPULATE-HEADER-INFO} are intentionally <em>not</em> implemented here: sending and
 * receiving the 3270/BMS map ({@code COUSR0A} of mapset {@code COUSR00}), populating the header
 * (title/date/time/page number), and performing the actual navigation redirect are presentation
 * concerns owned by the paired {@code UserAdminController}. This service contains business logic
 * only &mdash; it browses the security file, decides routing, and produces messages &mdash; and
 * never touches the screen I/O.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) that carried navigation state across CICS returns
 * becomes the session-scoped {@link CardDemoContext}, injected here. First entry into the
 * transaction (COBOL {@code EIBCALEN = 0}) is reproduced by {@link CardDemoContext#isNew()}, and
 * the enter/re-enter distinction (COBOL {@code CDEMO-PGM-REENTER}) by
 * {@link CardDemoContext#isProgramEnter()}. The {@code XCTL}/{@code RETURN TRANSID} hand-off is
 * reproduced through the context's {@code from*}/{@code to*} program fields, which the controller
 * consults when redirecting.</p>
 *
 * <p>The program-specific COMMAREA extension of {@code COUSR00C}
 * ({@code CDEMO-CU00-USRID-FIRST}, {@code CDEMO-CU00-USRID-LAST}, {@code CDEMO-CU00-PAGE-NUM},
 * {@code CDEMO-CU00-NEXT-PAGE-FLG}, {@code CDEMO-CU00-USR-SELECTED}) is <em>not</em> present on
 * the shared {@link CardDemoContext}. That per-screen paging cursor is instead round-tripped
 * through the {@link UserListState} input and the {@link UserListResult} output: the controller
 * holds the state in the HTTP session between requests (the COMMAREA equivalent) and carries the
 * selected user id forward to the target maintenance program. This keeps the shared context
 * unchanged while faithfully preserving the paging state machine.</p>
 *
 * <h2>Outcome model</h2>
 * <p>Because the controller performs the redirect and the screen rendering, each interaction
 * returns an immutable {@link UserListResult} describing what should happen next: a redirect to a
 * target program (the COBOL {@code XCTL}), or a page of user rows to display (optionally with a
 * message). The {@link UserListResult#error()} flag reproduces the COBOL {@code WS-ERR-FLG} so
 * the controller can style an error line distinctly from an informational paging message.</p>
 *
 * <h2>Access and design constraints</h2>
 * <ul>
 *   <li><b>Admin-only:</b> {@code COUSR00C} is reachable only by administrator users; that gate
 *       is enforced upstream by the sign-on service and Spring Security routing, not by this
 *       service.</li>
 *   <li><b>Read-only:</b> the browse never modifies {@code USRSEC}; selecting a row only routes
 *       to the update ({@code COUSR02C}) or delete ({@code COUSR03C}) program.</li>
 *   <li><b>Page size is exactly ten</b> ({@link #ROWS_PER_PAGE}), matching the COBOL
 *       {@code USER-REC OCCURS 10 TIMES} table.</li>
 *   <li><b>Message literals:</b> the paging, invalid-selection and invalid-key messages are
 *       declared as local constants that mirror the COBOL literals (the invalid-key text mirrors
 *       {@code CCDA-MSG-INVALID-KEY} from {@code legacy/cpy/CSMSG01Y.cpy}); the shared
 *       message-constants holder is not a declared dependency of this service, so its values are
 *       reproduced locally rather than imported.</li>
 * </ul>
 */
@Service
public class UserListService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COUSR00C'} &mdash; this program's name. */
    private static final String PROGRAM_NAME = "COUSR00C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CU00'} &mdash; this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CU00";

    /**
     * Sign-on program (COBOL literal {@code 'COSGN00C'}) &mdash; the first-entry bounce target
     * ({@code EIBCALEN = 0}) and the default {@code RETURN-TO-PREV-SCREEN} destination.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** CICS transaction id of the sign-on program, used when redirecting to {@link #SIGNON_PROGRAM}. */
    private static final String SIGNON_TRANID = "CC00";

    /**
     * Administrator main-menu program (COBOL literal {@code 'COADM01C'}) &mdash; the PF3
     * return target. Note this is the <em>admin</em> menu, not the general user main menu
     * ({@code COMEN01C}); {@code COUSR00C} is an admin-only transaction reached from the admin
     * menu, so PF3 returns there.
     */
    private static final String ADMIN_MENU_PROGRAM = "COADM01C";

    /** CICS transaction id of the admin menu, used when redirecting to {@link #ADMIN_MENU_PROGRAM}. */
    private static final String ADMIN_MENU_TRANID = "CA00";

    /**
     * User-update program (COBOL literal {@code 'COUSR02C'}) &mdash; the {@code XCTL} target when
     * a row is selected with {@code 'U'}/{@code 'u'}.
     */
    private static final String USER_UPDATE_PROGRAM = "COUSR02C";

    /** CICS transaction id of the user-update program. */
    private static final String USER_UPDATE_TRANID = "CU02";

    /**
     * User-delete program (COBOL literal {@code 'COUSR03C'}) &mdash; the {@code XCTL} target when
     * a row is selected with {@code 'D'}/{@code 'd'}.
     */
    private static final String USER_DELETE_PROGRAM = "COUSR03C";

    /** CICS transaction id of the user-delete program. */
    private static final String USER_DELETE_TRANID = "CU03";

    /**
     * Rows displayed per page &mdash; the COBOL {@code USER-REC OCCURS 10 TIMES} table size. The
     * browse fills at most this many rows before checking whether a further page exists.
     */
    private static final int ROWS_PER_PAGE = 10;

    /**
     * One past the last row index, used as the COBOL fill-loop guard ({@code WS-IDX > 10}, i.e.
     * {@code WS-IDX >= 11}).
     */
    private static final int ROW_LOOP_LIMIT = ROWS_PER_PAGE + 1;

    /**
     * Number of records fetched into one browse window (review finding #21).
     *
     * <p>The forward browse issues at most a skip-one {@code READNEXT} (the PF8 case), then up to
     * {@link #ROWS_PER_PAGE} row {@code READNEXT}s, then one look-ahead {@code READNEXT}; the
     * backward browse issues the symmetric {@code READPREV} sequence. The most records the browse
     * can consume from the positioned start key is therefore {@code ROWS_PER_PAGE + 2}. Fetching
     * exactly this many rows in the bounded keyset window guarantees the window always contains
     * every record the legacy browse would have read for the page, so the paged output is
     * byte-identical to the full-table scan it replaces while never materialising the whole
     * {@code USRSEC} table.</p>
     */
    private static final int BROWSE_WINDOW = ROWS_PER_PAGE + 2;

    /**
     * Width of the {@code SEC-USR-ID} key ({@code PIC X(08)}). Browse keys are right-padded to
     * this fixed width before comparison so that ordering matches the VSAM KSDS key collation
     * (reproduced by the PostgreSQL {@code C} collation).
     */
    private static final int USER_ID_LENGTH = 8;

    /**
     * High-values sentinel key. Reproduces the COBOL {@code MOVE HIGH-VALUES TO SEC-USR-ID} of
     * {@code PROCESS-PF8-KEY} when {@code CDEMO-CU00-USRID-LAST} is blank: a start key that sorts
     * after every real (ASCII/{@code C}-collation) user id, so the browse reports "not found"
     * (top of page). Composed of the maximum {@code char} value repeated for the key width.
     */
    private static final String HIGH_VALUES =
            String.valueOf(Character.MAX_VALUE).repeat(USER_ID_LENGTH);

    /**
     * Invalid-selection message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code PROCESS-ENTER-KEY} when a row is flagged with anything other than {@code U}/{@code D}.
     */
    private static final String MSG_INVALID_SELECTION =
            "Invalid selection. Valid values are U and D";

    /**
     * Already-at-top guard message. COBOL literal moved to {@code WS-MESSAGE} in
     * {@code PROCESS-PF7-KEY} when PF7 is pressed on page one.
     */
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";

    /**
     * Already-at-bottom guard message. COBOL literal moved to {@code WS-MESSAGE} in
     * {@code PROCESS-PF8-KEY} when PF8 is pressed with no further page available.
     */
    private static final String MSG_ALREADY_BOTTOM =
            "You are already at the bottom of the page...";

    /**
     * Start-of-browse "not found" message. COBOL literal moved to {@code WS-MESSAGE} in
     * {@code STARTBR-USER-SEC-FILE} on the {@code NOTFND} response (no key greater than or equal
     * to the start key), reproduced here as {@link RecordNotFoundException}.
     */
    private static final String MSG_TOP_OF_PAGE = "You are at the top of the page...";

    /**
     * End-of-file (forward) message. COBOL literal moved to {@code WS-MESSAGE} in
     * {@code READNEXT-USER-SEC-FILE} on the {@code ENDFILE} response, reproduced here as
     * {@link EndOfFileException}.
     */
    private static final String MSG_BOTTOM_REACHED = "You have reached the bottom of the page...";

    /**
     * End-of-file (backward) message. COBOL literal moved to {@code WS-MESSAGE} in
     * {@code READPREV-USER-SEC-FILE} on the {@code ENDFILE} response, reproduced here as
     * {@link EndOfFileException}.
     */
    private static final String MSG_TOP_REACHED = "You have reached the top of the page...";

    /**
     * Invalid-key message. Mirrors COBOL {@code CCDA-MSG-INVALID-KEY} (from
     * {@code legacy/cpy/CSMSG01Y.cpy}), moved to {@code WS-MESSAGE} with {@code WS-ERR-FLG = 'Y'}
     * in {@code MAIN-PARA} for any unmapped AID key. Declared locally because the shared
     * message-constants holder is not a declared dependency of this service.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Session-scoped navigation context, the modern replacement for the COBOL COMMAREA
     * ({@code COCOM01Y}). Injected as a Spring session-scoped proxy and used to record the
     * {@code XCTL}/{@code RETURN} hand-off (from/to program and transaction) when routing.
     */
    private final CardDemoContext context;

    /**
     * Repository over the {@code USRSEC} security file (migrated VSAM KSDS). The browse loads the
     * users ordered by user id and pages through them in memory, reproducing the COBOL
     * {@code STARTBR}/{@code READNEXT}/{@code READPREV} cursor.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the user-list service via Spring constructor injection.
     *
     * <p>A single constructor means no {@code @Autowired} annotation is required. Neither
     * argument is dereferenced here, so the constructor introduces no {@code this}-escape.</p>
     *
     * @param context                the session-scoped CardDemo context (COMMAREA replacement);
     *                               must not be {@code null}
     * @param userSecurityRepository the {@code USRSEC} repository backing the browse; must not be
     *                               {@code null}
     */
    public UserListService(CardDemoContext context, UserSecurityRepository userSecurityRepository) {
        this.context = context;
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Attention-identifier (AID) keys handled by
     * {@link #mainEntry(AidKey, COUSR00Form, UserListState)}, mirroring the COBOL
     * {@code EVALUATE EIBAID} of {@code MAIN-PARA}.
     *
     * <p>The paired {@code UserAdminController} maps the inbound HTTP submission (the pressed
     * button or PF key) to one of these constants before delegating to the service. Only the
     * keys with distinct behaviour in {@code COUSR00C} are modelled: {@link #ENTER}
     * ({@code DFHENTER}), {@link #PF3} ({@code DFHPF3}), {@link #PF7} ({@code DFHPF7}) and
     * {@link #PF8} ({@code DFHPF8}); every other key collapses to {@link #OTHER}, matching the
     * COBOL {@code WHEN OTHER} default.</p>
     */
    public enum AidKey {

        /** COBOL {@code DFHENTER} &mdash; the ENTER key; selects a row or refreshes the list. */
        ENTER,

        /** COBOL {@code DFHPF3} &mdash; the PF3 key; returns to the admin menu. */
        PF3,

        /** COBOL {@code DFHPF7} &mdash; the PF7 key; pages backward (toward the top). */
        PF7,

        /** COBOL {@code DFHPF8} &mdash; the PF8 key; pages forward (toward the bottom). */
        PF8,

        /** COBOL {@code WHEN OTHER} &mdash; any other key; yields the invalid-key message. */
        OTHER
    }

    /**
     * The kind of action a {@link UserListResult} represents, letting the controller decide
     * between navigating away and re-rendering the list screen.
     */
    public enum RoutingAction {

        /**
         * Navigate to another program (the COBOL {@code XCTL} equivalent): either a maintenance
         * program for a selected row, or the return target for PF3 / first entry.
         */
        REDIRECT,

        /**
         * Re-render the user-list screen with the supplied page of rows and any message (the
         * COBOL {@code SEND-USRLST-SCREEN} equivalent).
         */
        SHOW_SCREEN
    }

    /**
     * A single displayed user row, mirroring one occurrence of the COBOL {@code USER-REC} table
     * populated by {@code POPULATE-USER-DATA}. The four fields correspond to the map fields
     * {@code USRIDnn}, {@code FNAMEnn}, {@code LNAMEnn} and {@code UTYPEnn}, sourced from the
     * {@code SEC-USR-ID}, {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME} and {@code SEC-USR-TYPE}
     * fields of the {@code USRSEC} record.
     *
     * @param userId    the user id ({@code SEC-USR-ID}, {@code PIC X(08)})
     * @param firstName the first name ({@code SEC-USR-FNAME})
     * @param lastName  the last name ({@code SEC-USR-LNAME})
     * @param userType  the user type ({@code SEC-USR-TYPE}: {@code A}=admin, {@code U}=user)
     */
    public record UserRow(String userId, String firstName, String lastName, String userType) {

        /**
         * Creates an all-blank row, reproducing the COBOL {@code INITIALIZE-USER-DATA}
         * {@code MOVE SPACES} that clears an unused table occurrence.
         *
         * @return a row whose four fields are all empty strings
         */
        public static UserRow blank() {
            return new UserRow("", "", "", "");
        }
    }

    /**
     * The per-screen paging cursor carried between requests, the round-tripped replacement for
     * the COBOL {@code COUSR00C} COMMAREA extension ({@code CDEMO-CU00-*}). The controller stores
     * the {@link UserListResult}'s paging fields in the HTTP session and supplies them back as a
     * {@code UserListState} on the next PF7/PF8/ENTER submission.
     *
     * @param pageNumber        the current page number ({@code CDEMO-CU00-PAGE-NUM})
     * @param firstUserId       the user id shown in the first row ({@code CDEMO-CU00-USRID-FIRST});
     *                          the PF7 backward start key
     * @param lastUserId        the user id shown in the last row ({@code CDEMO-CU00-USRID-LAST});
     *                          the PF8 forward start key
     * @param nextPageAvailable whether a further (forward) page exists
     *                          ({@code CDEMO-CU00-NEXT-PAGE-FLG}), gating PF8
     */
    public record UserListState(int pageNumber, String firstUserId, String lastUserId,
                                boolean nextPageAvailable) {

        /**
         * Returns the initial paging state used before any page has been browsed: page zero, no
         * first/last key, and no forward page yet known. This mirrors the COBOL COMMAREA extension
         * being low-values on first entry.
         *
         * @return a fresh, empty paging state
         */
        public static UserListState initial() {
            return new UserListState(0, "", "", false);
        }
    }

    /**
     * Immutable outcome of a user-list interaction, describing what the controller should do
     * next: perform a redirect, or re-render the list screen with a page of rows.
     *
     * <p>Exactly one of two shapes is produced:</p>
     * <ul>
     *   <li><b>Redirect</b> &mdash; {@link #action()} is {@link RoutingAction#REDIRECT} and
     *       {@link #targetProgram()} is set; the controller redirects to the route mapped from
     *       that program name, carrying {@link #selectedUserId()} when a row was selected. This
     *       reproduces the COBOL {@code XCTL}.</li>
     *   <li><b>Show screen</b> &mdash; {@link #action()} is {@link RoutingAction#SHOW_SCREEN} and
     *       {@link #rows()} plus the paging fields describe the page to render; an optional
     *       {@link #message()} (flagged by {@link #error()}) is displayed. This reproduces the
     *       COBOL {@code SEND-USRLST-SCREEN}.</li>
     * </ul>
     *
     * <p>For a guard or invalid-key show-screen outcome the row list is empty: the COBOL
     * {@code SEND} in those paths does not repopulate the map, so the controller retains the
     * currently displayed rows and only refreshes the message.</p>
     *
     * @param action              whether to redirect or re-render the screen
     * @param targetProgram       the redirect target program name (COBOL {@code XCTL} destination),
     *                            or {@code null} for a show-screen outcome
     * @param targetTransactionId the CICS transaction id of {@code targetProgram}, or {@code null}
     *                            for a show-screen outcome
     * @param selectedUserId      the selected user id carried to a maintenance program
     *                            ({@code CDEMO-CU00-USR-SELECTED}), or {@code null} when none
     * @param rows                the page of user rows to display (empty for a redirect or a
     *                            guard/invalid-key redisplay)
     * @param pageNumber          the resulting page number ({@code CDEMO-CU00-PAGE-NUM})
     * @param firstUserId         the first displayed user id ({@code CDEMO-CU00-USRID-FIRST})
     * @param lastUserId          the last displayed user id ({@code CDEMO-CU00-USRID-LAST})
     * @param nextPageAvailable   whether a further forward page exists
     *                            ({@code CDEMO-CU00-NEXT-PAGE-FLG})
     * @param message             the message to display ({@code WS-MESSAGE}), or {@code null}
     * @param error               {@code true} when {@code message} is an error line
     *                            ({@code WS-ERR-FLG = 'Y'}); {@code false} for an informational
     *                            paging message or a redirect
     */
    public record UserListResult(RoutingAction action, String targetProgram,
                                 String targetTransactionId, String selectedUserId,
                                 List<UserRow> rows, int pageNumber, String firstUserId,
                                 String lastUserId, boolean nextPageAvailable, String message,
                                 boolean error) {

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
         * @return {@code true} when a non-blank {@link #message()} is present
         */
        public boolean hasMessage() {
            return message != null && !message.isBlank();
        }

        /**
         * Creates a redirect outcome (COBOL {@code XCTL}) to the given program, optionally
         * carrying the selected user id to a maintenance program.
         *
         * @param targetProgram       the target program name
         * @param targetTransactionId the target program's CICS transaction id
         * @param selectedUserId      the selected user id to carry forward, or {@code null}
         * @return a redirect outcome
         */
        private static UserListResult ofRedirect(String targetProgram, String targetTransactionId,
                                                 String selectedUserId) {
            return new UserListResult(RoutingAction.REDIRECT, targetProgram, targetTransactionId,
                    selectedUserId, List.of(), 0, "", "", false, null, false);
        }

        /**
         * Creates a show-screen outcome (COBOL {@code SEND-USRLST-SCREEN}) carrying a page of rows,
         * the resulting paging state, and any message.
         *
         * @param rows              the page of user rows
         * @param pageNumber        the resulting page number
         * @param firstUserId       the first displayed user id
         * @param lastUserId        the last displayed user id
         * @param nextPageAvailable whether a further forward page exists
         * @param message           the message to display, or {@code null}
         * @param error             whether {@code message} is an error line
         * @return a show-screen outcome
         */
        private static UserListResult ofScreen(List<UserRow> rows, int pageNumber,
                                               String firstUserId, String lastUserId,
                                               boolean nextPageAvailable, String message,
                                               boolean error) {
            return new UserListResult(RoutingAction.SHOW_SCREEN, null, null, null, rows, pageNumber,
                    firstUserId, lastUserId, nextPageAvailable, message, error);
        }
    }

    /**
     * Handles a user-list interaction, the Java migration of paragraph {@code MAIN-PARA} in
     * {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the COBOL control flow exactly:</p>
     * <ul>
     *   <li>On first entry into the transaction (COBOL {@code EIBCALEN = 0}, reproduced by
     *       {@link CardDemoContext#isNew()}) the return target is set to the sign-on program and
     *       control returns there (COBOL moves {@code 'COSGN00C'} to {@code CDEMO-TO-PROGRAM} then
     *       performs {@code RETURN-TO-PREV-SCREEN}).</li>
     *   <li>On the first display of the screen (COBOL {@code IF NOT CDEMO-PGM-REENTER}, reproduced
     *       by {@link CardDemoContext#isProgramEnter()}) the program marks itself re-entrant,
     *       clears the map, and performs {@code PROCESS-ENTER-KEY} with an empty form &mdash;
     *       browsing the first page from the top.</li>
     *   <li>Otherwise the pressed AID key is evaluated: {@code ENTER} selects a row or refreshes
     *       the list; {@code PF3} returns to the <em>admin</em> menu ({@code COADM01C}, not the
     *       user main menu); {@code PF7}/{@code PF8} page backward/forward; any other key yields
     *       the invalid-key message with the error flag set.</li>
     * </ul>
     *
     * <p>The COBOL {@code RECEIVE-USRLST-SCREEN} and {@code SEND-USRLST-SCREEN} presentation steps
     * are owned by the paired controller; this method consumes the already-received form and
     * paging state and returns the outcome to render.</p>
     *
     * @param aid   the attention-identifier key pressed; a {@code null} value is treated as
     *              {@link AidKey#OTHER}, matching the COBOL {@code WHEN OTHER} default
     * @param form  the submitted user-list screen form (map {@code COUSR0A} of mapset
     *              {@code COUSR00}); used for the ENTER selection
     * @param state the paging cursor carried from the previous request (the COMMAREA extension
     *              round-trip); used for PF7/PF8 and for redisplay on the invalid-key path
     * @return the interaction outcome: a redirect target, or a page to display
     */
    public UserListResult mainEntry(AidKey aid, COUSR00Form form, UserListState state) {
        // MAIN-PARA sets WS-ERR-FLG/WS-USER-SEC-EOF off and MOVE -1 TO USRIDINL (cursor); the
        // cursor position is a presentation concern owned by the controller.

        // IF EIBCALEN = 0: MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, PERFORM RETURN-TO-PREV-SCREEN.
        if (context.isNew()) {
            context.setToProgram(SIGNON_PROGRAM);
            return returnToPrevScreen(context);
        }

        // ELSE IF NOT CDEMO-PGM-REENTER (first display): SET re-enter, clear map (LOW-VALUES),
        // PERFORM PROCESS-ENTER-KEY with the cleared map, then SEND. An empty form carries no
        // selection and a blank filter, so the enter-key logic browses the first page.
        if (context.isProgramEnter()) {
            context.markReenter();
            return processEnterKey(new COUSR00Form(), context);
        }

        // ELSE (re-entry): RECEIVE-USRLST-SCREEN then EVALUATE EIBAID. A null AID collapses to the
        // WHEN OTHER branch.
        AidKey effectiveAid = (aid == null) ? AidKey.OTHER : aid;
        return switch (effectiveAid) {
            case ENTER -> processEnterKey(form, context);
            case PF3 -> {
                // DFHPF3: MOVE 'COADM01C' TO CDEMO-TO-PROGRAM, then RETURN-TO-PREV-SCREEN. This is
                // the admin menu (COUSR00C is an admin-only transaction), not COMEN01C.
                context.setToProgram(ADMIN_MENU_PROGRAM);
                yield returnToPrevScreen(context);
            }
            case PF7 -> processPf7Key(state);
            case PF8 -> processPf8Key(state);
            case OTHER -> {
                // WHEN OTHER: MOVE 'Y' TO WS-ERR-FLG, MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE,
                // PERFORM SEND. No re-browse: the map keeps its current rows, so the show-screen
                // outcome carries no rows and the controller retains what is displayed.
                BrowseWork work = newWork(state);
                work.errFlag = true;
                work.message = MSG_INVALID_KEY;
                yield showScreenResult(work);
            }
        };
    }

    /**
     * Detects a row selection and either routes to a maintenance program or browses the first
     * page, the Java migration of paragraph {@code PROCESS-ENTER-KEY} in
     * {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the COBOL logic exactly. First an {@code EVALUATE TRUE} scans the ten
     * selection fields ({@code SEL0001I}..{@code SEL0010I}) and captures the first non-blank
     * flag together with that row's user id ({@code CDEMO-CU00-USR-SEL-FLG} and
     * {@code CDEMO-CU00-USR-SELECTED}); when none is set, both become spaces. When both are
     * non-blank the flag is evaluated: {@code 'U'}/{@code 'u'} transfers to the user-update
     * program ({@code COUSR02C}, tran {@code CU02}) and {@code 'D'}/{@code 'd'} to the
     * user-delete program ({@code COUSR03C}, tran {@code CU03}), each after recording the
     * hand-off fields ({@code CDEMO-FROM-TRANID = 'CU00'}, {@code CDEMO-FROM-PROGRAM =
     * 'COUSR00C'}, {@code CDEMO-PGM-CONTEXT = 0}) &mdash; the COBOL {@code XCTL}, reproduced as a
     * redirect that carries the selected user id forward. Any other flag yields the
     * invalid-selection message.</p>
     *
     * <p>Control then falls through (as in COBOL, the {@code XCTL} paths having already returned):
     * the {@code USRIDINI} filter becomes the start key ({@code SEC-USR-ID}, or low-values when
     * blank), the page number is reset to zero, and {@code PROCESS-PAGE-FORWARD} browses the first
     * page. Because {@code WS-MESSAGE} is a single shared field, an invalid-selection message set
     * here can be overwritten by the forward browse when the list is short enough to hit
     * end-of-file (the {@code READNEXT ENDFILE} message); this last-write-wins quirk is preserved
     * intentionally for behavioural parity.</p>
     *
     * @param form the submitted (or cleared) user-list form supplying the selection fields and
     *             the {@code USRIDINI} filter
     * @param ctx  the session context receiving the {@code XCTL} hand-off fields on a selection
     * @return a redirect to a maintenance program, or the first page of users to display
     */
    public UserListResult processEnterKey(COUSR00Form form, CardDemoContext ctx) {
        BrowseWork work = new BrowseWork();

        // EVALUATE TRUE over the ten selection fields: first non-blank wins (else spaces).
        String selectionFlag;
        String selectedUserId;
        if (!isBlankOrLowValues(form.getSel0001())) {
            selectionFlag = form.getSel0001();
            selectedUserId = form.getUsrid01();
        } else if (!isBlankOrLowValues(form.getSel0002())) {
            selectionFlag = form.getSel0002();
            selectedUserId = form.getUsrid02();
        } else if (!isBlankOrLowValues(form.getSel0003())) {
            selectionFlag = form.getSel0003();
            selectedUserId = form.getUsrid03();
        } else if (!isBlankOrLowValues(form.getSel0004())) {
            selectionFlag = form.getSel0004();
            selectedUserId = form.getUsrid04();
        } else if (!isBlankOrLowValues(form.getSel0005())) {
            selectionFlag = form.getSel0005();
            selectedUserId = form.getUsrid05();
        } else if (!isBlankOrLowValues(form.getSel0006())) {
            selectionFlag = form.getSel0006();
            selectedUserId = form.getUsrid06();
        } else if (!isBlankOrLowValues(form.getSel0007())) {
            selectionFlag = form.getSel0007();
            selectedUserId = form.getUsrid07();
        } else if (!isBlankOrLowValues(form.getSel0008())) {
            selectionFlag = form.getSel0008();
            selectedUserId = form.getUsrid08();
        } else if (!isBlankOrLowValues(form.getSel0009())) {
            selectionFlag = form.getSel0009();
            selectedUserId = form.getUsrid09();
        } else if (!isBlankOrLowValues(form.getSel0010())) {
            selectionFlag = form.getSel0010();
            selectedUserId = form.getUsrid10();
        } else {
            // WHEN OTHER: MOVE SPACES TO both.
            selectionFlag = "";
            selectedUserId = "";
        }

        // IF (flag not blank) AND (selected id not blank): EVALUATE the flag and XCTL.
        if (!isBlankOrLowValues(selectionFlag) && !isBlankOrLowValues(selectedUserId)) {
            String flag = selectionFlag.trim();
            String selected = selectedUserId.trim();
            if (flag.equalsIgnoreCase("U")) {
                // 'U'/'u': hand off to the user-update program (COBOL XCTL PROGRAM('COUSR02C')).
                ctx.setToProgram(USER_UPDATE_PROGRAM);
                ctx.setFromTranid(TRANSACTION_ID);
                ctx.setFromProgram(PROGRAM_NAME);
                ctx.markEnter();
                return UserListResult.ofRedirect(USER_UPDATE_PROGRAM, USER_UPDATE_TRANID, selected);
            }
            if (flag.equalsIgnoreCase("D")) {
                // 'D'/'d': hand off to the user-delete program (COBOL XCTL PROGRAM('COUSR03C')).
                ctx.setToProgram(USER_DELETE_PROGRAM);
                ctx.setFromTranid(TRANSACTION_ID);
                ctx.setFromProgram(PROGRAM_NAME);
                ctx.markEnter();
                return UserListResult.ofRedirect(USER_DELETE_PROGRAM, USER_DELETE_TRANID, selected);
            }
            // WHEN OTHER: invalid selection flag. Set the message and fall through to browse.
            work.message = MSG_INVALID_SELECTION;
        }

        // IF USRIDINI = SPACES OR LOW-VALUES -> low-values start key, ELSE the entered filter.
        String startKey = isBlankOrLowValues(form.getUsridin()) ? null : form.getUsridin();

        // MOVE 0 TO CDEMO-CU00-PAGE-NUM, PERFORM PROCESS-PAGE-FORWARD.
        work.pageNumber = 0;
        processPageForward(work, startKey, AidKey.ENTER);
        return showScreenResult(work);
    }

    /**
     * Pages the list backward (toward the top), the Java migration of paragraph
     * {@code PROCESS-PF7-KEY} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the COBOL logic: the start key is the first user id currently displayed
     * ({@code CDEMO-CU00-USRID-FIRST}, or low-values when blank), the forward-page flag is set to
     * yes ({@code SET NEXT-PAGE-YES}), and if the current page is beyond the first
     * ({@code CDEMO-CU00-PAGE-NUM > 1}) the backward browse runs. When already on the first page
     * the guard message "already at the top" is shown without re-browsing &mdash; the COBOL
     * {@code SEND} in that path keeps the current rows, so the outcome carries no rows and the
     * controller retains what is displayed.</p>
     *
     * @param state the paging cursor from the previous request; supplies the current page and the
     *              first displayed user id
     * @return the previous page to display, or a guard message when already at the top
     */
    public UserListResult processPf7Key(UserListState state) {
        BrowseWork work = newWork(state);

        // IF CDEMO-CU00-USRID-FIRST = SPACES OR LOW-VALUES -> low-values, ELSE the first user id.
        String startKey = isBlankOrLowValues(work.firstUserId) ? null : work.firstUserId;

        // SET NEXT-PAGE-YES TO TRUE.
        work.nextPageAvailable = true;

        if (work.pageNumber > 1) {
            // PERFORM PROCESS-PAGE-BACKWARD.
            processPageBackward(work, startKey, AidKey.PF7);
        } else {
            // Already at the top: message only, no browse (COBOL SEND-ERASE-NO + SEND).
            work.message = MSG_ALREADY_TOP;
        }
        return showScreenResult(work);
    }

    /**
     * Pages the list forward (toward the bottom), the Java migration of paragraph
     * {@code PROCESS-PF8-KEY} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the COBOL logic: the start key is the last user id currently displayed
     * ({@code CDEMO-CU00-USRID-LAST}, or high-values when blank), and if a further page is known
     * to exist ({@code IF NEXT-PAGE-YES}) the forward browse runs. When no further page exists the
     * guard message "already at the bottom" is shown without re-browsing &mdash; the COBOL
     * {@code SEND} in that path keeps the current rows, so the outcome carries no rows and the
     * controller retains what is displayed.</p>
     *
     * @param state the paging cursor from the previous request; supplies the last displayed user
     *              id and the forward-page flag
     * @return the next page to display, or a guard message when already at the bottom
     */
    public UserListResult processPf8Key(UserListState state) {
        BrowseWork work = newWork(state);

        // IF CDEMO-CU00-USRID-LAST = SPACES OR LOW-VALUES -> high-values, ELSE the last user id.
        String startKey = isBlankOrLowValues(work.lastUserId) ? HIGH_VALUES : work.lastUserId;

        if (work.nextPageAvailable) {
            // PERFORM PROCESS-PAGE-FORWARD.
            processPageForward(work, startKey, AidKey.PF8);
        } else {
            // Already at the bottom: message only, no browse (COBOL SEND-ERASE-NO + SEND).
            work.message = MSG_ALREADY_BOTTOM;
        }
        return showScreenResult(work);
    }

    /**
     * Browses forward and fills up to ten rows, the Java migration of paragraph
     * {@code PROCESS-PAGE-FORWARD} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the COBOL sequence: start the browse; unless the request is ENTER, PF7 or PF3
     * skip one record forward (the PF8 case, where the start key is the last row already
     * displayed); clear the ten rows; then read forward populating rows one through ten until the
     * table is full or end-of-file. If the table filled without hitting end-of-file the page
     * number is incremented and one further record is peeked to set the "next page exists" flag;
     * otherwise the flag is cleared and, if at least one row was read, the page number is still
     * incremented (a partial final page).</p>
     *
     * <p>A {@code STARTBR} "not found" (no key at or beyond the start key) is the COBOL
     * {@code NOTFND} path, which is not an error: it sets end-of-file and the "top of page"
     * message and leaves the rows blank. It is reproduced here by catching
     * {@link RecordNotFoundException}. End-of-file during a read is the COBOL {@code ENDFILE} path,
     * reproduced by catching {@link EndOfFileException}; its message overwrites {@code WS-MESSAGE},
     * preserving the shared-message quirk.</p>
     *
     * @param work     the working state receiving the rows, page number, flags and message
     * @param startKey the browse start key ({@code SEC-USR-ID}); {@code null} means low-values
     * @param aid      the AID key that triggered the browse, controlling the skip-one behaviour
     */
    private void processPageForward(BrowseWork work, String startKey, AidKey aid) {
        // A browse runs, so the display rows are (re)populated (even to an empty page).
        work.browsed = true;
        UserSecBrowse browse = new UserSecBrowse(loadForwardWindow(startKey));

        // PERFORM STARTBR-USER-SEC-FILE. NOTFND is not an error: set EOF + message and stop.
        try {
            startBrowse(browse, startKey);
        } catch (RecordNotFoundException notFound) {
            work.eof = true;
            work.message = notFound.getMessage();
            work.nextPageAvailable = false;
            return;
        }

        // IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3: skip one record (the PF8 forward case).
        if (aid != AidKey.ENTER && aid != AidKey.PF7 && aid != AidKey.PF3) {
            try {
                readNext(browse);
            } catch (EndOfFileException endOfFile) {
                work.eof = true;
                work.message = endOfFile.getMessage();
            }
        }

        // Clear the ten display rows (only when not already at end-of-file).
        if (!work.eof && !work.errFlag) {
            initializeUserData(work);
        }

        // MOVE 1 TO WS-IDX; PERFORM UNTIL WS-IDX >= 11 OR USER-SEC-EOF OR ERR-FLG-ON.
        int idx = 1;
        while (idx < ROW_LOOP_LIMIT && !work.eof && !work.errFlag) {
            UserSecurity record;
            try {
                record = readNext(browse);
            } catch (EndOfFileException endOfFile) {
                // READNEXT ENDFILE: set EOF + message, then the loop condition ends the loop.
                work.eof = true;
                work.message = endOfFile.getMessage();
                break;
            }
            populateUserData(work, idx, record);
            idx++;
        }

        // Decide the page number and the next-page flag.
        if (!work.eof && !work.errFlag) {
            // Filled the table with more data possibly remaining: advance the page and peek.
            work.pageNumber = work.pageNumber + 1;
            try {
                readNext(browse);
                work.nextPageAvailable = true;
            } catch (EndOfFileException endOfFile) {
                work.nextPageAvailable = false;
                work.message = endOfFile.getMessage();
            }
        } else {
            // Hit end-of-file while filling: no further page; a partial page still counts.
            work.nextPageAvailable = false;
            if (idx > 1) {
                work.pageNumber = work.pageNumber + 1;
            }
        }

        // PERFORM ENDBR-USER-SEC-FILE.
        endBrowse(browse);
    }

    /**
     * Browses backward and fills up to ten rows, the Java migration of paragraph
     * {@code PROCESS-PAGE-BACKWARD} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the COBOL sequence: start the browse; unless the request is ENTER or PF8 skip
     * one record backward (the PF7 case, where the start key is the first row already displayed);
     * clear the ten rows; then read backward populating rows ten down to one until the table is
     * full or end-of-file. When the table filled without hitting end-of-file one further record is
     * peeked; then, because the caller (PF7) always sets the forward-page flag, the page number is
     * decremented when a prior record remains and the page is beyond the first, and otherwise
     * reset to one.</p>
     *
     * <p>As with the forward browse, {@code STARTBR} "not found" is reproduced by catching
     * {@link RecordNotFoundException} and end-of-file by catching {@link EndOfFileException}; the
     * backward end-of-file message is "top of page".</p>
     *
     * @param work     the working state receiving the rows, page number, flags and message
     * @param startKey the browse start key ({@code SEC-USR-ID}); {@code null} means low-values
     * @param aid      the AID key that triggered the browse, controlling the skip-one behaviour
     */
    private void processPageBackward(BrowseWork work, String startKey, AidKey aid) {
        // A browse runs, so the display rows are (re)populated (even to an empty page).
        work.browsed = true;
        UserSecBrowse browse = new UserSecBrowse(loadBackwardWindow(startKey));

        // PERFORM STARTBR-USER-SEC-FILE. NOTFND is not an error: set EOF + message and stop.
        try {
            startBrowse(browse, startKey);
        } catch (RecordNotFoundException notFound) {
            work.eof = true;
            work.message = notFound.getMessage();
            return;
        }

        // IF EIBAID NOT = DFHENTER AND DFHPF8: skip one record (the PF7 backward case).
        if (aid != AidKey.ENTER && aid != AidKey.PF8) {
            try {
                readPrev(browse);
            } catch (EndOfFileException endOfFile) {
                work.eof = true;
                work.message = endOfFile.getMessage();
            }
        }

        // Clear the ten display rows (only when not already at end-of-file).
        if (!work.eof && !work.errFlag) {
            initializeUserData(work);
        }

        // MOVE 10 TO WS-IDX; PERFORM UNTIL WS-IDX <= 0 OR USER-SEC-EOF OR ERR-FLG-ON.
        int idx = ROWS_PER_PAGE;
        while (idx > 0 && !work.eof && !work.errFlag) {
            UserSecurity record;
            try {
                record = readPrev(browse);
            } catch (EndOfFileException endOfFile) {
                // READPREV ENDFILE: set EOF + message, then the loop condition ends the loop.
                work.eof = true;
                work.message = endOfFile.getMessage();
                break;
            }
            populateUserData(work, idx, record);
            idx--;
        }

        // Peek one more record and settle the page number (COBOL performs the peek then, because
        // NEXT-PAGE-YES is always set by PF7, applies the page arithmetic).
        if (!work.eof && !work.errFlag) {
            boolean peekEndOfFile = false;
            try {
                readPrev(browse);
            } catch (EndOfFileException endOfFile) {
                peekEndOfFile = true;
                work.eof = true;
                work.message = endOfFile.getMessage();
            }
            if (work.nextPageAvailable) {
                if (!peekEndOfFile && work.pageNumber > 1) {
                    work.pageNumber = work.pageNumber - 1;
                } else {
                    work.pageNumber = 1;
                }
            }
        }

        // PERFORM ENDBR-USER-SEC-FILE.
        endBrowse(browse);
    }

    /**
     * Stores one browsed record into the given display row, the Java migration of paragraph
     * {@code POPULATE-USER-DATA} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the COBOL {@code EVALUATE WS-IDX} that moves {@code SEC-USR-ID},
     * {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME} and {@code SEC-USR-TYPE} into the
     * {@code USRIDnn}/{@code FNAMEnn}/{@code LNAMEnn}/{@code UTYPEnn} map fields for the given
     * one-based row. The first row additionally records the page's first user id
     * ({@code CDEMO-CU00-USRID-FIRST}) and the tenth row the page's last user id
     * ({@code CDEMO-CU00-USRID-LAST}); these become the PF7/PF8 start keys.</p>
     *
     * @param work   the working state whose row table and first/last keys are updated
     * @param rowIdx the one-based row index (1..10); values outside this range are ignored, as in
     *               the COBOL {@code WHEN OTHER CONTINUE}
     * @param record the browsed {@code USRSEC} record to display
     */
    private void populateUserData(BrowseWork work, int rowIdx, UserSecurity record) {
        if (rowIdx < 1 || rowIdx > ROWS_PER_PAGE) {
            // WHEN OTHER: CONTINUE (no-op for an out-of-range index).
            return;
        }
        work.rows.set(rowIdx - 1, new UserRow(record.getUsrId(), record.getUsrFname(),
                record.getUsrLname(), record.getUsrType()));
        if (rowIdx == 1) {
            work.firstUserId = record.getUsrId();
        }
        if (rowIdx == ROWS_PER_PAGE) {
            work.lastUserId = record.getUsrId();
        }
    }

    /**
     * Clears all ten display rows to blanks, the Java migration of paragraph
     * {@code INITIALIZE-USER-DATA} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the COBOL {@code EVALUATE WS-IDX} that moves {@code SPACES} into every
     * {@code USRIDnn}/{@code FNAMEnn}/{@code LNAMEnn}/{@code UTYPEnn} field. The original clears
     * one indexed occurrence per call inside a {@code PERFORM VARYING}; because the browse always
     * clears the whole table before refilling it, all ten rows are reset here in one pass.</p>
     *
     * @param work the working state whose row table is reset to blank rows
     */
    private void initializeUserData(BrowseWork work) {
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            work.rows.set(i, UserRow.blank());
        }
    }

    /**
     * Positions the browse at the first record at or after the start key, the Java migration of
     * paragraph {@code STARTBR-USER-SEC-FILE} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the CICS {@code STARTBR} with the default greater-than-or-equal positioning:
     * a {@code null} start key (COBOL low-values) positions before the first record, while any
     * other key positions at the first record whose fixed-width user id is greater than or equal
     * to it. Keys are compared after right-padding to the {@link #USER_ID_LENGTH}-byte width so
     * the ordering matches the VSAM KSDS key collation (the PostgreSQL {@code C} collation).</p>
     *
     * <p>When no record satisfies the positioning (the CICS {@code NOTFND} response) a
     * {@link RecordNotFoundException} carrying the "top of page" message is thrown; the callers
     * treat this as a non-error end-of-browse, matching the COBOL {@code NOTFND} branch that sets
     * end-of-file rather than the error flag. The CICS "other response" branch (which moves the
     * "Unable to lookup User..." message and sets {@code WS-ERR-FLG}) models an unexpected VSAM
     * I/O failure; it has no analogue over the JPA-backed in-memory browse, where such a failure
     * would surface as a Spring {@code DataAccessException} handled by the global exception
     * handler, so it is intentionally not reproduced as a distinct branch here.</p>
     *
     * @param browse   the browse cursor to position
     * @param startKey the start key ({@code SEC-USR-ID}); {@code null} means low-values (start of
     *                 file)
     * @throws RecordNotFoundException when no record is at or after the start key
     *                                 (CICS {@code NOTFND})
     */
    private void startBrowse(UserSecBrowse browse, String startKey) {
        int size = browse.size();
        int lowerBound;
        if (startKey == null) {
            // Low-values: position before the first record.
            lowerBound = 0;
        } else {
            // Greater-than-or-equal: first index whose padded key is >= the padded start key.
            String key = pad8(startKey);
            lowerBound = 0;
            while (lowerBound < size && pad8(browse.userIdAt(lowerBound)).compareTo(key) < 0) {
                lowerBound++;
            }
        }
        if (lowerBound >= size) {
            // NOTFND: no key at or beyond the start key.
            throw new RecordNotFoundException(MSG_TOP_OF_PAGE);
        }
        browse.position = lowerBound;
    }

    /**
     * Reads the record at the cursor and advances forward, the Java migration of paragraph
     * {@code READNEXT-USER-SEC-FILE} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the CICS {@code READNEXT}: it returns the record at the current position and
     * moves the cursor forward. When the cursor is past the last record (the CICS {@code ENDFILE}
     * response) an {@link EndOfFileException} carrying the "bottom of page" message is thrown; the
     * callers treat this as the normal end-of-page signal, matching the COBOL {@code ENDFILE}
     * branch that sets end-of-file rather than the error flag.</p>
     *
     * @param browse the browse cursor to read from
     * @return the {@code USRSEC} record at the cursor
     * @throws EndOfFileException when the cursor is past the last record (CICS {@code ENDFILE})
     */
    private UserSecurity readNext(UserSecBrowse browse) {
        if (browse.position >= browse.size()) {
            throw new EndOfFileException(MSG_BOTTOM_REACHED);
        }
        UserSecurity record = browse.recordAt(browse.position);
        browse.position = browse.position + 1;
        return record;
    }

    /**
     * Reads the record at the cursor and moves backward, the Java migration of paragraph
     * {@code READPREV-USER-SEC-FILE} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the CICS {@code READPREV}: it returns the record at the current position and
     * moves the cursor backward. When the cursor is before the first record (the CICS
     * {@code ENDFILE} response) an {@link EndOfFileException} carrying the "top of page" message is
     * thrown; the callers treat this as the normal end-of-page signal, matching the COBOL
     * {@code ENDFILE} branch that sets end-of-file rather than the error flag.</p>
     *
     * @param browse the browse cursor to read from
     * @return the {@code USRSEC} record at the cursor
     * @throws EndOfFileException when the cursor is before the first record (CICS {@code ENDFILE})
     */
    private UserSecurity readPrev(UserSecBrowse browse) {
        if (browse.position < 0) {
            throw new EndOfFileException(MSG_TOP_REACHED);
        }
        UserSecurity record = browse.recordAt(browse.position);
        browse.position = browse.position - 1;
        return record;
    }

    /**
     * Ends the browse, the Java migration of paragraph {@code ENDBR-USER-SEC-FILE} in
     * {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the CICS {@code ENDBR} that releases the browse cursor. The migrated browse is
     * an in-memory view of records already fetched by the repository, so there is no external
     * cursor or lock to release; the position is reset to a defined post-browse state to make the
     * cursor's end-of-life explicit. The method is retained for one-for-one paragraph fidelity.</p>
     *
     * @param browse the browse cursor to end
     */
    private void endBrowse(UserSecBrowse browse) {
        browse.position = 0;
    }

    /**
     * Returns control to the previous screen, the Java migration of paragraph
     * {@code RETURN-TO-PREV-SCREEN} in {@code legacy/cbl/COUSR00C.cbl}.
     *
     * <p>Reproduces the COBOL logic: when the return target is blank it defaults to the sign-on
     * program ({@code 'COSGN00C'}); the hand-off fields are then set &mdash; from-transaction
     * ({@code CDEMO-FROM-TRANID = 'CU00'}), from-program ({@code CDEMO-FROM-PROGRAM = 'COUSR00C'}),
     * and program context reset to enter ({@code CDEMO-PGM-CONTEXT = 0}) &mdash; and the target is
     * returned as the redirect destination (COBOL {@code XCTL}). Used both for first entry
     * (target already set to the sign-on program) and for the PF3 return to the admin menu.</p>
     *
     * @param ctx the session context supplying and receiving the hand-off fields
     * @return a redirect to the resolved target program
     */
    public UserListResult returnToPrevScreen(CardDemoContext ctx) {
        // IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES -> default to the sign-on program.
        String target = isBlankOrLowValues(ctx.getToProgram()) ? SIGNON_PROGRAM : ctx.getToProgram();
        ctx.setToProgram(target);
        ctx.setFromTranid(TRANSACTION_ID);
        ctx.setFromProgram(PROGRAM_NAME);
        ctx.markEnter();
        return UserListResult.ofRedirect(target, transactionIdFor(target), null);
    }

    /**
     * Creates a working state seeded from a carried-over paging cursor.
     *
     * <p>The page number and first/last keys and forward-page flag are copied from the supplied
     * {@link UserListState} so that PF7/PF8 and the invalid-key redisplay operate against the
     * currently displayed page. A {@code null} state yields a fresh working state (page zero, no
     * keys), which is defensive; the controller supplies a real state on re-entry.</p>
     *
     * @param state the carried-over paging cursor, or {@code null} for a fresh state
     * @return a working state initialised from {@code state}
     */
    private BrowseWork newWork(UserListState state) {
        BrowseWork work = new BrowseWork();
        if (state != null) {
            work.pageNumber = state.pageNumber();
            work.firstUserId = (state.firstUserId() == null) ? "" : state.firstUserId();
            work.lastUserId = (state.lastUserId() == null) ? "" : state.lastUserId();
            work.nextPageAvailable = state.nextPageAvailable();
        }
        return work;
    }

    /**
     * Builds a show-screen outcome (COBOL {@code SEND-USRLST-SCREEN}) from the working state.
     *
     * <p>When a browse ran, the ten display rows are copied into an independent list so the
     * immutable result does not alias the mutable working state. When no browse ran (the guard and
     * invalid-key paths, where the COBOL {@code SEND} does not repopulate the map) the row list is
     * empty, signalling the controller to retain the currently displayed page and refresh only the
     * message. The {@code WS-ERR-FLG} flag becomes the result's error flag, letting the controller
     * style an error line distinctly from an informational paging message.</p>
     *
     * @param work the working state to render
     * @return a show-screen result carrying the rows (or none), paging state and message
     */
    private UserListResult showScreenResult(BrowseWork work) {
        List<UserRow> rows = work.browsed ? new ArrayList<>(work.rows) : List.of();
        return UserListResult.ofScreen(rows, work.pageNumber, work.firstUserId, work.lastUserId,
                work.nextPageAvailable, work.message, work.errFlag);
    }

    /**
     * Loads the forward browse window: the bounded, ascending slice of {@code USRSEC} users at or
     * after {@code startKey} (review finding #21).
     *
     * <p>Reproduces the VSAM {@code STARTBR}-at-key plus forward {@code READNEXT} sequence of the
     * COBOL user-list browse without materialising the whole {@code USRSEC} table. The forward
     * browse reads at most {@link #BROWSE_WINDOW} records from the positioned start key (a possible
     * skip-one, a page of rows, and a look-ahead), so fetching exactly that many rows &ge;
     * {@code startKey} in ascending key order yields a window that contains every record the legacy
     * browse would have read for the page. The window is handed to the in-memory
     * {@link UserSecBrowse} cursor, whose {@link #startBrowse(UserSecBrowse, String)} re-derives the
     * greater-than-or-equal position with the identical {@code pad8} comparison, so the emitted rows
     * are byte-identical to the previous full-table scan.</p>
     *
     * <p>A {@code null} {@code startKey} is the COBOL low-values start (first page): it is passed to
     * the repository as the empty string, which the {@code C}-collation {@code >=} predicate treats
     * as a lower bound below every real user id, selecting the first {@link #BROWSE_WINDOW} rows. A
     * {@code startKey} of {@link #HIGH_VALUES} (the PF8-with-blank-last-key case) is above every real
     * user id, so the window is empty and the cursor reports "top of page" &mdash; exactly the legacy
     * {@code STARTBR} "not found" outcome.</p>
     *
     * @param startKey the inclusive lower-bound start key ({@code SEC-USR-ID}); {@code null} means
     *                 low-values (first page)
     * @return the ascending forward window, at most {@link #BROWSE_WINDOW} records (possibly empty)
     */
    private List<UserSecurity> loadForwardWindow(String startKey) {
        String lowerBound = (startKey == null) ? "" : pad8(startKey);
        return userSecurityRepository.findByUsrIdGreaterThanEqualOrderByUsrIdAsc(
                lowerBound, Limit.of(BROWSE_WINDOW));
    }

    /**
     * Loads the backward browse window: the bounded slice of {@code USRSEC} users at or before
     * {@code startKey}, returned ascending for the browse cursor (review finding #21).
     *
     * <p>Reproduces the VSAM {@code STARTBR} plus backward {@code READPREV} sequence of paragraph
     * {@code PROCESS-PAGE-BACKWARD} without materialising the whole table. The backward browse reads
     * at most {@link #BROWSE_WINDOW} records at or before the start key, so the repository fetches
     * that many rows &le; {@code startKey} in <em>descending</em> key order and this method reverses
     * them to ascending &mdash; the order the shared {@link UserSecBrowse} cursor always expects. The
     * cursor positions at the greater-than-or-equal index (the start key, which is the last element
     * of the reversed window) and reads backward from it, so the paged output is byte-identical to
     * the full-table scan it replaces.</p>
     *
     * <p>A {@code null} {@code startKey} is the low-values start: the backward browse then positions
     * before the first record and reads backward, so the first record must be present. The same
     * ascending head window as the forward low-values case is returned, which places the first user
     * at index zero exactly as the full-table scan did.</p>
     *
     * @param startKey the inclusive upper-bound start key ({@code SEC-USR-ID}); {@code null} means
     *                 low-values
     * @return the ascending backward window, at most {@link #BROWSE_WINDOW} records (possibly empty)
     */
    private List<UserSecurity> loadBackwardWindow(String startKey) {
        if (startKey == null) {
            return userSecurityRepository.findByUsrIdGreaterThanEqualOrderByUsrIdAsc(
                    "", Limit.of(BROWSE_WINDOW));
        }
        List<UserSecurity> descending = userSecurityRepository
                .findByUsrIdLessThanEqualOrderByUsrIdDesc(pad8(startKey), Limit.of(BROWSE_WINDOW));
        Collections.reverse(descending);
        return descending;
    }

    /**
     * Maps a target program name to its CICS transaction id for the redirect outcome.
     *
     * <p>Covers the destinations reachable from {@code COUSR00C}: the sign-on program, the admin
     * menu, and the user update/delete maintenance programs. An unrecognised program yields an
     * empty transaction id, leaving the controller to resolve routing by program name.</p>
     *
     * @param program the target program name
     * @return the matching CICS transaction id, or an empty string when unknown
     */
    private String transactionIdFor(String program) {
        if (SIGNON_PROGRAM.equals(program)) {
            return SIGNON_TRANID;
        }
        if (ADMIN_MENU_PROGRAM.equals(program)) {
            return ADMIN_MENU_TRANID;
        }
        if (USER_UPDATE_PROGRAM.equals(program)) {
            return USER_UPDATE_TRANID;
        }
        if (USER_DELETE_PROGRAM.equals(program)) {
            return USER_DELETE_TRANID;
        }
        return "";
    }

    /**
     * Right-pads or truncates a key to the fixed {@link #USER_ID_LENGTH}-byte user-id width.
     *
     * <p>Fixed-width padding reproduces the COBOL {@code PIC X(08)} comparison semantics: keys are
     * compared as eight-character, space-padded values so that, for example, {@code "USER"} and
     * {@code "USER    "} compare equal and shorter keys order before longer keys sharing a prefix.
     * A {@code null} key is treated as all spaces.</p>
     *
     * @param value the raw key value, possibly {@code null} or of any length
     * @return the value padded with spaces (or truncated) to exactly {@link #USER_ID_LENGTH}
     *         characters
     */
    private static String pad8(String value) {
        String safe = (value == null) ? "" : value;
        if (safe.length() == USER_ID_LENGTH) {
            return safe;
        }
        if (safe.length() > USER_ID_LENGTH) {
            return safe.substring(0, USER_ID_LENGTH);
        }
        StringBuilder padded = new StringBuilder(USER_ID_LENGTH);
        padded.append(safe);
        while (padded.length() < USER_ID_LENGTH) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Reports whether a value is COBOL "spaces or low-values", i.e. logically empty.
     *
     * <p>Reproduces the COBOL test {@code = SPACES OR LOW-VALUES}: a {@code null} value (the
     * web-tier equivalent of an unset low-values field) or a value that is empty or all whitespace
     * is treated as blank. Used for selection detection, the {@code USRIDINI} filter, and the
     * return-target default.</p>
     *
     * @param value the value to test, possibly {@code null}
     * @return {@code true} when {@code value} is {@code null}, empty, or all whitespace
     */
    private static boolean isBlankOrLowValues(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Mutable working state for one browse interaction, aggregating the COBOL working-storage
     * fields that {@code PROCESS-PAGE-FORWARD}/{@code PROCESS-PAGE-BACKWARD} manipulate:
     * {@code CDEMO-CU00-PAGE-NUM}, {@code CDEMO-CU00-USRID-FIRST}/{@code -LAST},
     * {@code CDEMO-CU00-NEXT-PAGE-FLG}, the {@code WS-USER-SEC-EOF} and {@code WS-ERR-FLG} flags,
     * {@code WS-MESSAGE}, and the ten-row {@code USER-REC} display table.
     *
     * <p>This is a private, single-threaded, per-call scratch object (never shared across
     * requests), so its fields are accessed directly by the enclosing service methods rather than
     * through accessors.</p>
     */
    private static final class BrowseWork {

        /** COBOL {@code CDEMO-CU00-PAGE-NUM} &mdash; the current page number. */
        private int pageNumber;

        /** COBOL {@code CDEMO-CU00-USRID-FIRST} &mdash; user id of the first displayed row. */
        private String firstUserId;

        /** COBOL {@code CDEMO-CU00-USRID-LAST} &mdash; user id of the last displayed row. */
        private String lastUserId;

        /** COBOL {@code CDEMO-CU00-NEXT-PAGE-FLG} (88 {@code NEXT-PAGE-YES}) &mdash; forward page exists. */
        private boolean nextPageAvailable;

        /** COBOL {@code WS-USER-SEC-EOF} (88 {@code USER-SEC-EOF}) &mdash; browse end reached. */
        private boolean eof;

        /** COBOL {@code WS-ERR-FLG} (88 {@code ERR-FLG-ON}) &mdash; error encountered. */
        private boolean errFlag;

        /**
         * Whether a browse ran during this interaction and therefore (re)populated the display
         * rows. When {@code false} (the guard and invalid-key paths, which mirror the COBOL
         * {@code SEND} that does not repopulate the map) the outcome carries no rows so the
         * controller retains the currently displayed page rather than blanking it.
         */
        private boolean browsed;

        /** COBOL {@code WS-MESSAGE} &mdash; the single, last-write-wins screen message. */
        private String message;

        /** COBOL {@code USER-REC OCCURS 10 TIMES} &mdash; the ten display rows (initially blank). */
        private final List<UserRow> rows;

        /**
         * Creates a working state with page zero, no keys, no known forward page, and ten blank
         * display rows.
         */
        private BrowseWork() {
            this.pageNumber = 0;
            this.firstUserId = "";
            this.lastUserId = "";
            this.nextPageAvailable = false;
            this.eof = false;
            this.errFlag = false;
            this.browsed = false;
            this.message = null;
            this.rows = new ArrayList<>(ROWS_PER_PAGE);
            for (int i = 0; i < ROWS_PER_PAGE; i++) {
                this.rows.add(UserRow.blank());
            }
        }
    }

    /**
     * An in-memory browse cursor over the {@code USRSEC} users, reproducing the CICS sequential
     * browse established by {@code STARTBR} and advanced by {@code READNEXT}/{@code READPREV}.
     *
     * <p>The records are held sorted ascending by (fixed-width) user id, matching the VSAM KSDS
     * key order. {@link #position} is the index that the next read will return; a read in either
     * direction returns the record at {@link #position} and then advances the index forward
     * ({@code READNEXT}) or backward ({@code READPREV}), so the first read after positioning
     * returns the record the browse was positioned on.</p>
     */
    private static final class UserSecBrowse {

        /** The users to browse, sorted ascending by fixed-width user id. */
        private final List<UserSecurity> records;

        /** Index of the record the next read will return ({@code -1} .. {@code size}). */
        private int position;

        /**
         * Creates a browse over the given pre-sorted user list, positioned before the first read.
         *
         * @param records the users to browse, sorted ascending by user id
         */
        private UserSecBrowse(List<UserSecurity> records) {
            this.records = records;
            this.position = 0;
        }

        /**
         * Returns the number of records available to browse.
         *
         * @return the record count
         */
        private int size() {
            return records.size();
        }

        /**
         * Returns the record at the given index.
         *
         * @param index a valid record index ({@code 0} .. {@code size - 1})
         * @return the {@link UserSecurity} record at {@code index}
         */
        private UserSecurity recordAt(int index) {
            return records.get(index);
        }

        /**
         * Returns the user id of the record at the given index.
         *
         * @param index a valid record index ({@code 0} .. {@code size - 1})
         * @return the record's {@code SEC-USR-ID}
         */
        private String userIdAt(int index) {
            return records.get(index).getUsrId();
        }
    }
}
