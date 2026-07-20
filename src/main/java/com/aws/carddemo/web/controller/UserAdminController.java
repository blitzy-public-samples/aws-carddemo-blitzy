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
package com.aws.carddemo.web.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.DateStruct;
import com.aws.carddemo.dto.screen.COUSR00Form;
import com.aws.carddemo.dto.screen.COUSR01Form;
import com.aws.carddemo.dto.screen.COUSR02Form;
import com.aws.carddemo.dto.screen.COUSR03Form;
import com.aws.carddemo.service.online.UserAddService;
import com.aws.carddemo.service.online.UserDeleteService;
import com.aws.carddemo.service.online.UserListService;
import com.aws.carddemo.service.online.UserListService.UserListResult;
import com.aws.carddemo.service.online.UserListService.UserListState;
import com.aws.carddemo.service.online.UserListService.UserRow;
import com.aws.carddemo.service.online.UserUpdateService;
import com.aws.carddemo.util.PfKeyHandler;
import com.aws.carddemo.util.constants.ScreenTitles;
import com.aws.carddemo.web.support.ConfirmationTokenService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * Spring MVC {@link Controller} for the AWS CardDemo user-administration screens, the web-tier
 * migration of FOUR CICS pseudo-conversational COBOL programs that make up the security/user
 * maintenance suite. This controller is presentation and navigation only; every business rule,
 * validation, VSAM ({@code USRSEC}) access, and pseudo-conversational hand-off decision lives in
 * the four injected {@code service.online} classes.
 *
 * <h2>Origins (verified in {@code legacy/csd/CARDDEMO.CSD})</h2>
 * <ul>
 *   <li><b>CU00 (list)</b> &rarr; {@code COUSR00C} &mdash; origin {@code legacy/cbl/COUSR00C.cbl};
 *       screen {@code legacy/cpy-bms/COUSR00.CPY} (mapset {@code COUSR00}, map {@code COUSR0A});
 *       view {@code COUSR00}. Routes {@code /admin/users}.</li>
 *   <li><b>CU01 (add)</b> &rarr; {@code COUSR01C} &mdash; origin {@code legacy/cbl/COUSR01C.cbl};
 *       screen {@code legacy/cpy-bms/COUSR01.CPY} (mapset {@code COUSR01}, map {@code COUSR1A});
 *       view {@code COUSR01}. Routes {@code /admin/users/add}.</li>
 *   <li><b>CU02 (update)</b> &rarr; {@code COUSR02C} &mdash; origin {@code legacy/cbl/COUSR02C.cbl};
 *       screen {@code legacy/cpy-bms/COUSR02.CPY} (mapset {@code COUSR02}, map {@code COUSR2A});
 *       view {@code COUSR02}. Routes {@code /admin/users/update}.</li>
 *   <li><b>CU03 (delete)</b> &rarr; {@code COUSR03C} &mdash; origin {@code legacy/cbl/COUSR03C.cbl};
 *       screen {@code legacy/cpy-bms/COUSR03.CPY} (mapset {@code COUSR03}, map {@code COUSR3A});
 *       view {@code COUSR03}. Routes {@code /admin/users/delete}.</li>
 * </ul>
 *
 * <h2>Admin-only access (parity for RACF transaction protection)</h2>
 * <p>All four routes live under {@code /admin/**}, which {@code config.SecurityConfig} restricts to
 * {@code ROLE_ADMIN}. That URL/role contract is the parity replacement for RACF transaction-level
 * protection of {@code CU00}&ndash;{@code CU03}; this controller therefore does <em>not</em>
 * re-implement any role or RACF check (AAP &sect;0.6.7).</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) is replaced by the session-scoped {@link CardDemoContext}
 * injected here and handed to the services, which set the {@code from*}/{@code to*} program hand-off
 * fields and the {@code CDEMO-PGM-CONTEXT} enter/re-enter flag exactly as the COBOL
 * {@code RETURN-TO-PREV-SCREEN}/{@code XCTL} sequence did. Two pieces of per-screen state that the
 * legacy COMMAREA carried but {@link CardDemoContext} does not model are round-tripped through the
 * {@link HttpSession} by this controller:</p>
 * <ul>
 *   <li>the user-list paging cursor ({@code CDEMO-CU00-*}) as a {@link UserListState}, and the ten
 *       currently displayed rows &mdash; because the {@code COUSR00} view renders the row user-ids
 *       as read-only cells that are not re-submitted, the rows are restored from the session before
 *       delegating so a row selection resolves to the correct user id (the CICS full-map
 *       round-trip);</li>
 *   <li>the selected user id ({@code CDEMO-CU02-USR-SELECTED} / {@code CDEMO-CU03-USR-SELECTED})
 *       carried from the list to the update/delete screen, used to pre-fill the target screen on
 *       first display.</li>
 * </ul>
 *
 * <h2>Navigation quirk preserved</h2>
 * <p>{@code PF3} on the user list returns to the <em>admin</em> menu ({@code COADM01C}, tran
 * {@code CA00}, route {@code /admin/menu}) &mdash; not the general user main menu ({@code COMEN01C})
 * &mdash; because the user-administration transactions are admin-only functions reached from the
 * admin menu. The delete screen deliberately keeps its two-step gesture (ENTER to look up and
 * display the user, PF5 to confirm the delete); neither is collapsed.</p>
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li>{@code @Controller} only &mdash; server-rendered screens, never REST/JSON/MQ.</li>
 *   <li>Constructor injection of {@code private final} collaborators (no field injection, no
 *       Lombok).</li>
 *   <li>Typed exceptions raised by the services ({@code RecordNotFoundException},
 *       {@code DuplicateKeyException}, {@code LogicError}) are never swallowed; they propagate to
 *       {@code exception.GlobalExceptionHandler}.</li>
 *   <li>The cleartext {@code USRSEC} password is passed straight through to the service for parity
 *       (AAP &sect;0.6.7); it is never logged, hardcoded, or hashed here.</li>
 *   <li>No floating-point types and no monetary arithmetic occur in this presentation tier.</li>
 * </ul>
 */
@Controller
public class UserAdminController {

    /** Route for the user list (COBOL {@code COUSR00C}, tran {@code CU00}). */
    private static final String PATH_USERS = "/admin/users";

    /** Route for the user-add screen (COBOL {@code COUSR01C}, tran {@code CU01}). */
    private static final String PATH_USERS_ADD = "/admin/users/add";

    /** Route for the user-update screen (COBOL {@code COUSR02C}, tran {@code CU02}). */
    private static final String PATH_USERS_UPDATE = "/admin/users/update";

    /** Route for the user-delete screen (COBOL {@code COUSR03C}, tran {@code CU03}). */
    private static final String PATH_USERS_DELETE = "/admin/users/delete";

    /** Spring MVC {@code redirect:} view-name prefix used for every {@code XCTL} equivalent. */
    private static final String REDIRECT_PREFIX = "redirect:";

    /** Logical view name for the user-list screen ({@code COUSR00.html}). */
    private static final String VIEW_USER_LIST = "COUSR00";

    /** Logical view name for the user-add screen ({@code COUSR01.html}). */
    private static final String VIEW_USER_ADD = "COUSR01";

    /** Logical view name for the user-update screen ({@code COUSR02.html}). */
    private static final String VIEW_USER_UPDATE = "COUSR02";

    /** Logical view name for the user-delete screen ({@code COUSR03.html}). */
    private static final String VIEW_USER_DELETE = "COUSR03";

    /** Model attribute (and Thymeleaf {@code th:object}) name for the bound screen form. */
    private static final String MODEL_ATTR_FORM = "form";

    /**
     * Neutral banner shown when a submitted field exceeds its physical BMS width (review finding
     * #11). Every {@code COUSRxx} input carries a {@code maxlength} matching its {@code @Size}
     * constraint, so a well-behaved 3270/Thymeleaf client can never trigger this; it is reachable
     * only by a crafted request that bypasses the screen. It therefore never displaces a COBOL
     * business-edit message, preserving the {@code COUSR00C}/{@code COUSR01C}/{@code COUSR02C}/
     * {@code COUSR03C} message ordering.
     */
    private static final String MSG_FIELD_LENGTH =
            "Input exceeds the maximum length for a field.";

    /** Request-parameter name carrying the activated PF-key token (for example {@code "PF3"}). */
    private static final String PF_KEY_PARAM = "pfkey";

    /**
     * Confirmation-integrity operation label for the CU02 user-update save (review finding F12).
     * Binds a save nonce to this gesture so a nonce armed for one screen cannot be spent on another.
     */
    private static final String OP_USER_UPDATE = "USER_UPDATE";

    /**
     * Confirmation-integrity operation label for the CU03 user-delete confirm (review finding F12).
     */
    private static final String OP_USER_DELETE = "USER_DELETE";

    /**
     * Web-tier confirmation-integrity banner (review finding F12). Shown when a save/delete confirm
     * cannot be validated against the server-owned pending confirmation (missing/forged nonce,
     * replay, or a swapped target); no write is performed and the screen is re-armed for a retry.
     * This has no COBOL origin - it restores, over HTTP, the BMS protected-confirmation-field
     * contract the 3270 terminal enforced implicitly.
     */
    private static final String MSG_CONFIRM_INTEGRITY =
            "Confirmation could not be validated. Please review and press F5 again.";

    /** Session attribute holding the {@link UserListState} paging cursor between requests. */
    private static final String SESSION_LIST_STATE = "userAdminListState";

    /** Session attribute holding the currently displayed list rows (for the selection round-trip). */
    private static final String SESSION_LIST_ROWS = "userAdminListRows";

    /**
     * Session attribute holding the user id selected on the list ({@code CDEMO-CU02-USR-SELECTED} /
     * {@code CDEMO-CU03-USR-SELECTED}), used to pre-fill the update/delete screen on first display.
     */
    private static final String SESSION_SELECTED_USER_ID = "userAdminSelectedUserId";

    /** COBOL {@code WS-TRANID} of the list program {@code COUSR00C}. */
    private static final String TRAN_LIST = "CU00";

    /** COBOL {@code WS-TRANID} of the add program {@code COUSR01C}. */
    private static final String TRAN_ADD = "CU01";

    /** COBOL {@code WS-TRANID} of the update program {@code COUSR02C}. */
    private static final String TRAN_UPDATE = "CU02";

    /** COBOL {@code WS-TRANID} of the delete program {@code COUSR03C}. */
    private static final String TRAN_DELETE = "CU03";

    /** COBOL {@code WS-PGMNAME} of the list program. */
    private static final String PGM_USER_LIST = "COUSR00C";

    /** COBOL {@code WS-PGMNAME} of the add program. */
    private static final String PGM_USER_ADD = "COUSR01C";

    /** COBOL {@code WS-PGMNAME} of the update program. */
    private static final String PGM_USER_UPDATE = "COUSR02C";

    /** COBOL {@code WS-PGMNAME} of the delete program. */
    private static final String PGM_USER_DELETE = "COUSR03C";

    /** Admin-menu program (COBOL {@code 'COADM01C'}, tran {@code CA00}); PF3/PF12 return target. */
    private static final String PGM_ADMIN_MENU = "COADM01C";

    /** Sign-on program (COBOL {@code 'COSGN00C'}); the first-entry ({@code EIBCALEN = 0}) target. */
    private static final String PGM_SIGNON = "COSGN00C";

    /** Route to the admin menu; PF3 on the list and PF3/PF12 on update/delete resolve here. */
    private static final String ROUTE_ADMIN_MENU = "/admin/menu";

    /** Route to the sign-on screen; the first-entry bounce target. */
    private static final String ROUTE_SIGNON = "/signon";

    /** Web PF-key token for the ENTER action (see the {@code pfkey} submit buttons in the views). */
    private static final String WEB_KEY_ENTER = "ENTER";

    /** Web PF-key token prefix for program-function keys (for example {@code "PF3"}). */
    private static final String WEB_KEY_PF_PREFIX = "PF";

    /** Web PF-key token prefix for program-attention keys (for example {@code "PA1"}). */
    private static final String WEB_KEY_PA_PREFIX = "PA";

    /** CICS AID mnemonic for the ENTER key, understood by {@link PfKeyHandler#fromAid(String)}. */
    private static final String AID_ENTER = "DFHENTER";

    /** CICS AID mnemonic prefix ({@code DFH}) prepended to a {@code PFnn}/{@code PAn} web token. */
    private static final String AID_DFH_PREFIX = "DFH";

    /** Rows displayed per user-list page &mdash; the COBOL {@code USER-REC OCCURS 10 TIMES} table. */
    private static final int ROWS_PER_PAGE = 10;

    /** Fixed width of the displayed page number ({@code CDEMO-CU00-PAGE-NUM PIC 9(08)}). */
    private static final int PAGE_NUM_WIDTH = 8;

    /**
     * User-list business/navigation service (migration of {@code COUSR00C}); owns the paged browse
     * over {@code USRSEC}, the row-selection routing, and the PF3 return to the admin menu.
     */
    private final UserListService userListService;

    /**
     * User-add business service (migration of {@code COUSR01C}); owns field validation and the
     * {@code USRSEC} insert (which raises {@code DuplicateKeyException} on a duplicate id).
     */
    private final UserAddService userAddService;

    /**
     * User-update business service (migration of {@code COUSR02C}); owns the read-update-rewrite of
     * a {@code USRSEC} record.
     */
    private final UserUpdateService userUpdateService;

    /**
     * User-delete business service (migration of {@code COUSR03C}); owns the two-step
     * read-then-delete of a {@code USRSEC} record.
     */
    private final UserDeleteService userDeleteService;

    /**
     * Session-scoped navigation context (COMMAREA {@code COCOM01Y} replacement) shared with the
     * services so the {@code from*}/{@code to*} hand-off and the enter/re-enter flag survive a
     * redirect. Used here to resolve the update/delete redirect target
     * ({@link CardDemoContext#getToProgram()}).
     */
    private final CardDemoContext context;

    /**
     * Session-scoped confirmation-integrity store (review finding F12). Arms a single-use nonce
     * bound to the server-confirmed target when a save/delete confirm prompt is rendered, and
     * validates + consumes it on the committing turn so a tampered re-post cannot re-aim or replay
     * the write. This is the web-tier equivalent of the BMS protected confirmation field.
     */
    private final ConfirmationTokenService confirmationTokenService;

    /**
     * Creates the user-administration controller via Spring constructor injection.
     *
     * <p>A single constructor needs no {@code @Autowired}. No argument is dereferenced here, so the
     * constructor only stores its collaborators.</p>
     *
     * @param userListService   the user-list service (migration of {@code COUSR00C}); must not be
     *                          {@code null}
     * @param userAddService    the user-add service (migration of {@code COUSR01C}); must not be
     *                          {@code null}
     * @param userUpdateService the user-update service (migration of {@code COUSR02C}); must not be
     *                          {@code null}
     * @param userDeleteService the user-delete service (migration of {@code COUSR03C}); must not be
     *                          {@code null}
     * @param context           the session-scoped CardDemo context (COMMAREA replacement); must not
     *                          be {@code null}
     * @param confirmationTokenService the session-scoped confirmation-integrity store (review
     *                          finding F12) used to arm/validate the save/delete confirmation nonce;
     *                          must not be {@code null}
     */
    public UserAdminController(UserListService userListService,
                               UserAddService userAddService,
                               UserUpdateService userUpdateService,
                               UserDeleteService userDeleteService,
                               CardDemoContext context,
                               ConfirmationTokenService confirmationTokenService) {
        this.userListService = userListService;
        this.userAddService = userAddService;
        this.userUpdateService = userUpdateService;
        this.userDeleteService = userDeleteService;
        this.context = context;
        this.confirmationTokenService = confirmationTokenService;
    }

    // ================================================================================
    // CU00 - user list (COUSR00C)
    // ================================================================================

    /**
     * Displays the first page of the user list (HTTP {@code GET /admin/users}), the presentation
     * counterpart of the COBOL {@code COUSR00C} first-display path. Tran {@code CU00}, program
     * {@code COUSR00C}.
     *
     * <p>A fresh {@link COUSR00Form} and the {@link UserListState#initial() initial} paging cursor
     * are handed to {@link UserListService#mainEntry(UserListService.AidKey, COUSR00Form,
     * UserListState)}. When the context reports first display ({@code CDEMO-PGM-CONTEXT} enter) the
     * service browses the first ten users from the top ({@code PROCESS-ENTER-KEY} with an empty
     * map); the {@code ENTER} key passed here is otherwise the harmless re-entry equivalent, which
     * likewise browses the first page for an empty form. The resulting page (or a first-entry
     * redirect) is rendered.</p>
     *
     * @param model   the Spring MVC model that receives the screen form under
     *                {@link #MODEL_ATTR_FORM}
     * @param session the HTTP session used to persist the paging cursor and displayed rows
     * @return the {@code COUSR00} view, or a {@code redirect:} when the service returns a
     *         first-entry bounce
     */
    @GetMapping(PATH_USERS)
    public String showUserList(Model model, HttpSession session) {
        COUSR00Form form = new COUSR00Form();
        UserListResult result =
                userListService.mainEntry(UserListService.AidKey.ENTER, form, UserListState.initial());
        return renderOrRedirectList(result, form, model, session);
    }

    /**
     * Handles a user-list submission (HTTP {@code POST /admin/users}), the web migration of the
     * COBOL {@code COUSR00C} {@code MAIN-PARA} {@code EVALUATE EIBAID} plus {@code PROCESS-ENTER-KEY}
     * row-selection logic. Tran {@code CU00}, program {@code COUSR00C}.
     *
     * <p>Because the {@code COUSR00} view renders the row user-ids as read-only cells that are not
     * re-submitted, the ten currently displayed rows are first restored from the session into the
     * form so that a selection resolves to the correct user id (the CICS full-map round-trip). The
     * paging cursor is likewise restored. The activated PF-key is resolved and mapped to the
     * service {@link UserListService.AidKey}, then delegated to
     * {@link UserListService#mainEntry(UserListService.AidKey, COUSR00Form, UserListState)}:</p>
     * <ul>
     *   <li><b>ENTER</b> &mdash; a row flagged {@code 'U'} routes to the update program
     *       ({@code COUSR02C}, {@code /admin/users/update}) and {@code 'D'} to the delete program
     *       ({@code COUSR03C}, {@code /admin/users/delete}), carrying the selected user id; with no
     *       selection the first page is (re)browsed.</li>
     *   <li><b>PF7</b>/<b>PF8</b> &mdash; page backward/forward.</li>
     *   <li><b>PF3</b> &mdash; return to the admin menu ({@code COADM01C}, {@code /admin/menu}).</li>
     *   <li><b>any other key</b> &mdash; the invalid-key message from the service.</li>
     * </ul>
     *
     * @param form    the submitted list form (map {@code COUSR0A}); carries the ten selection flags
     *                and the {@code usridin} filter
     * @param pfkey   the activated PF-key token; {@code null}/unknown collapses to the service
     *                {@code WHEN OTHER} branch
     * @param model   the Spring MVC model that receives the screen form under
     *                {@link #MODEL_ATTR_FORM}
     * @param session the HTTP session supplying the restored rows/cursor and receiving the selected
     *                user id on a row selection
     * @return the {@code COUSR00} view, or a {@code redirect:} for a selection/PF3 navigation
     */
    @PostMapping(PATH_USERS)
    public String handleUserList(
            @Valid @ModelAttribute(MODEL_ATTR_FORM) COUSR00Form form,
            BindingResult bindingResult,
            @RequestParam(name = PF_KEY_PARAM, required = false) String pfkey,
            Model model,
            HttpSession session) {

        // Finding #11: an over-width selection/id can only arrive from a crafted request; restore
        // the display rows, clear the offending selections and re-render the list with a neutral
        // banner and no service call, so COUSR00C's own edit messages keep their COBOL ordering.
        if (bindingResult.hasErrors()) {
            applyRowsToForm(form, readListRows(session));
            clearSelections(form);
            populateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            model.addAttribute(MODEL_ATTR_FORM, form);
            return VIEW_USER_LIST;
        }

        // Restore the displayed rows (read-only cells not re-submitted) so a selection resolves to
        // the correct user id, then restore the paging cursor.
        applyRowsToForm(form, readListRows(session));
        UserListState state = readListState(session);

        UserListService.AidKey aid = toListAid(resolvePfKey(pfkey));
        UserListResult result = userListService.mainEntry(aid, form, state);

        if (result.isRedirect()) {
            // Carry the selected user id (CDEMO-CU00-USR-SELECTED) to the update/delete screen.
            if (result.selectedUserId() != null && !result.selectedUserId().isBlank()) {
                session.setAttribute(SESSION_SELECTED_USER_ID, result.selectedUserId());
            }
            return REDIRECT_PREFIX + routeForProgram(result.targetProgram());
        }
        return renderList(result, form, model, session);
    }

    // ================================================================================
    // CU01 - user add (COUSR01C)
    // ================================================================================

    /**
     * Displays the empty user-add screen (HTTP {@code GET /admin/users/add}), the presentation
     * counterpart of the COBOL {@code COUSR01C} {@code SEND-USRADD-SCREEN} first display. Tran
     * {@code CU01}, program {@code COUSR01C}.
     *
     * <p>The COBOL first-display branch of {@code COUSR01C} simply renders a blank add map; the
     * add service therefore has no first-display method and this GET builds an empty
     * {@link COUSR01Form} with only the header populated (mirroring {@code POPULATE-HEADER-INFO}).</p>
     *
     * @param model the Spring MVC model that receives the empty add form under
     *              {@link #MODEL_ATTR_FORM}
     * @return the {@code COUSR01} view
     */
    @GetMapping(PATH_USERS_ADD)
    public String showUserAdd(Model model) {
        COUSR01Form form = new COUSR01Form();
        populateHeader(form);
        model.addAttribute(MODEL_ATTR_FORM, form);
        return VIEW_USER_ADD;
    }

    /**
     * Handles a user-add submission (HTTP {@code POST /admin/users/add}), the web migration of the
     * COBOL {@code COUSR01C} {@code MAIN-PARA} {@code EVALUATE EIBAID} and {@code PROCESS-ENTER-KEY}.
     * Tran {@code CU01}, program {@code COUSR01C}.
     *
     * <p>The activated PF-key is resolved and mapped to {@link UserAddService.AidKey}, then
     * delegated to {@link UserAddService#mainEntry(UserAddService.AidKey, COUSR01Form)}:</p>
     * <ul>
     *   <li><b>ENTER</b> &mdash; validate the five entry fields and insert the user
     *       ({@code PROCESS-ENTER-KEY} &rarr; {@code WRITE-USER-SEC-FILE}); the cleartext password
     *       is passed straight through for parity.</li>
     *   <li><b>PF3</b> &mdash; return to the admin menu ({@code COADM01C}).</li>
     *   <li><b>PF4</b> &mdash; clear the screen ({@code CLEAR-CURRENT-SCREEN}).</li>
     *   <li><b>any other key</b> &mdash; the invalid-key message.</li>
     * </ul>
     *
     * <p>A duplicate user id causes the service to raise {@code DuplicateKeyException}; it is
     * intentionally not caught here and propagates to {@code exception.GlobalExceptionHandler}
     * (AAP &sect;0.6.5).</p>
     *
     * @param form  the submitted add form (map {@code COUSR1A}) with the five entry fields
     * @param pfkey the activated PF-key token; {@code null}/unknown collapses to {@code WHEN OTHER}
     * @param model the Spring MVC model that receives the redisplayed form under
     *              {@link #MODEL_ATTR_FORM}
     * @return the {@code COUSR01} view, or a {@code redirect:} to the admin menu on PF3
     */
    @PostMapping(PATH_USERS_ADD)
    public String handleUserAdd(
            @Valid @ModelAttribute(MODEL_ATTR_FORM) COUSR01Form form,
            BindingResult bindingResult,
            @RequestParam(name = PF_KEY_PARAM, required = false) String pfkey,
            Model model) {

        // Finding #11: reject an over-width field (only reachable by a crafted request) with a
        // neutral banner and no service call, preserving COUSR01C's own edit-message ordering.
        if (bindingResult.hasErrors()) {
            populateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            model.addAttribute(MODEL_ATTR_FORM, form);
            return VIEW_USER_ADD;
        }

        UserAddService.AidKey aid = toAddAid(resolvePfKey(pfkey));
        UserAddService.UserAddResult result = userAddService.mainEntry(aid, form);

        if (result.isRedirect()) {
            return REDIRECT_PREFIX + routeForProgram(result.targetProgram());
        }
        form.setErrmsg(result.hasMessage() ? result.message() : "");
        populateHeader(form);
        model.addAttribute(MODEL_ATTR_FORM, form);
        return VIEW_USER_ADD;
    }

    // ================================================================================
    // CU02 - user update (COUSR02C)
    // ================================================================================

    /**
     * Displays the user-update screen (HTTP {@code GET /admin/users/update}), the presentation
     * counterpart of the COBOL {@code COUSR02C} first-display path. Tran {@code CU02}, program
     * {@code COUSR02C}.
     *
     * <p>The user id selected on the list ({@code CDEMO-CU02-USR-SELECTED}) is read from the session
     * and pre-loaded into the form's {@code usridin}. The form is then handed to
     * {@link UserUpdateService#mainEntry(COUSR02Form, UserUpdateService.AidKey, String)} with the
     * {@code ENTER} key: on first display ({@code CDEMO-PGM-CONTEXT} enter) the service, when a user
     * was selected, auto-runs {@code PROCESS-ENTER-KEY} to look the record up and populate the
     * editable fields with the neutral &quot;Press PF5 ...&quot; prompt; with no selection it shows
     * a blank screen. The populated screen (or a redirect) is rendered.</p>
     *
     * @param model   the Spring MVC model that receives the screen form under
     *                {@link #MODEL_ATTR_FORM}
     * @param session the HTTP session supplying the selected user id
     * @return the {@code COUSR02} view, or a {@code redirect:} on a first-entry bounce
     */
    @GetMapping(PATH_USERS_UPDATE)
    public String showUserUpdate(Model model, HttpSession session) {
        String selectedUserId = readSelectedUserId(session);
        COUSR02Form form = new COUSR02Form();
        form.setUsridin(selectedUserId);
        UserUpdateService.UserUpdateResult result =
                userUpdateService.mainEntry(form, UserUpdateService.AidKey.ENTER, selectedUserId);
        // Finding F12: when the fetch renders the "Press PF5 to save" prompt, arm a single-use nonce
        // bound to the loaded user and echo it onto the form's hidden field for the saving submit.
        armUpdateConfirmation(result, form, session);
        return renderOrRedirectUpdate(result, form, model);
    }

    /**
     * Handles a user-update submission (HTTP {@code POST /admin/users/update}), the web migration of
     * the COBOL {@code COUSR02C} {@code MAIN-PARA} {@code EVALUATE EIBAID}. Tran {@code CU02},
     * program {@code COUSR02C}.
     *
     * <p>The activated PF-key is resolved and mapped to {@link UserUpdateService.AidKey}, then
     * delegated to {@link UserUpdateService#mainEntry(COUSR02Form, UserUpdateService.AidKey,
     * String)}:</p>
     * <ul>
     *   <li><b>ENTER</b> &mdash; look up the entered user id and load the editable fields
     *       ({@code PROCESS-ENTER-KEY}).</li>
     *   <li><b>PF5</b> &mdash; save the edits ({@code UPDATE-USER-INFO}); the cleartext password is
     *       passed straight through for parity.</li>
     *   <li><b>PF4</b> &mdash; clear the screen ({@code CLEAR-CURRENT-SCREEN}).</li>
     *   <li><b>PF3</b> &mdash; save then return to the originating program (or the admin menu when
     *       none is recorded).</li>
     *   <li><b>PF12</b> &mdash; return to the admin menu ({@code COADM01C}).</li>
     *   <li><b>any other key</b> &mdash; the invalid-key message.</li>
     * </ul>
     * <p>The selected user id is passed for symmetry with the first-display path; on re-entry the
     * service reads the round-tripped {@code usridin} field. The redirect target is taken from
     * {@link CardDemoContext#getToProgram()} (the service's {@code XCTL} destination).</p>
     *
     * @param form    the submitted update form (map {@code COUSR2A})
     * @param pfkey   the activated PF-key token; {@code null}/unknown collapses to {@code WHEN OTHER}
     * @param model   the Spring MVC model that receives the redisplayed form under
     *                {@link #MODEL_ATTR_FORM}
     * @param session the HTTP session supplying the selected user id
     * @return the {@code COUSR02} view, or a {@code redirect:} for PF3/PF12 navigation
     */
    @PostMapping(PATH_USERS_UPDATE)
    public String handleUserUpdate(
            @Valid @ModelAttribute(MODEL_ATTR_FORM) COUSR02Form form,
            BindingResult bindingResult,
            @RequestParam(name = PF_KEY_PARAM, required = false) String pfkey,
            Model model,
            HttpSession session) {

        // Finding #11: an over-width field is only reachable by a crafted request. Bounce it with a
        // neutral banner and no service call before the confirmation-integrity gate runs, so the
        // armed nonce is neither consumed nor re-armed and COUSR02C's edit ordering is preserved.
        if (bindingResult.hasErrors()) {
            populateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            model.addAttribute(MODEL_ATTR_FORM, form);
            return VIEW_USER_UPDATE;
        }

        String selectedUserId = readSelectedUserId(session);
        UserUpdateService.AidKey aid = toUpdateAid(resolvePfKey(pfkey));
        // Confirmation-integrity gate (finding F12): PF5 (save) and PF3 (save & exit) both rewrite
        // the USRSEC record using the round-tripped usridin, so both are bound to the server-
        // confirmed target by the single-use nonce armed on the fetch/prompt turn. A missing,
        // forged, replayed, or target-swapped confirmation is rejected with no write.
        if (aid == UserUpdateService.AidKey.PF5 || aid == UserUpdateService.AidKey.PF3) {
            String armedTarget = confirmationTokenService.armedTarget(session, OP_USER_UPDATE);
            boolean confirmed = armedTarget != null
                    && confirmationTokenService.validate(session, OP_USER_UPDATE,
                            form.getUsridin(), form.getConfirmToken());
            confirmationTokenService.consume(session);
            if (!confirmed) {
                return rejectUpdateConfirmation(form, model, session, armedTarget, selectedUserId);
            }
            // Force the server-confirmed identity onto the save; the re-post cannot re-aim it.
            form.setUsridin(armedTarget);
        }
        UserUpdateService.UserUpdateResult result =
                userUpdateService.mainEntry(form, aid, selectedUserId);
        armUpdateConfirmation(result, form, session);
        return renderOrRedirectUpdate(result, form, model);
    }

    // ================================================================================
    // CU03 - user delete (COUSR03C) - two-step: ENTER to display, PF5 to confirm delete
    // ================================================================================

    /**
     * Displays the user-delete screen (HTTP {@code GET /admin/users/delete}), the presentation
     * counterpart of the COBOL {@code COUSR03C} first-display path. Tran {@code CU03}, program
     * {@code COUSR03C}.
     *
     * <p>The user id selected on the list ({@code CDEMO-CU03-USR-SELECTED}) is read from the session
     * and pre-loaded into the form's {@code usridin}. The form is handed to
     * {@link UserDeleteService#mainEntry(UserDeleteService.AidKey, COUSR03Form)} with the
     * {@code ENTER} key: on first display, when a user was selected, the service auto-runs
     * {@code PROCESS-ENTER-KEY} to look the record up and display it with the neutral
     * &quot;Press PF5 to delete ...&quot; confirmation prompt (step one of the two-step gesture);
     * with no selection it shows a blank screen.</p>
     *
     * @param model   the Spring MVC model that receives the screen form under
     *                {@link #MODEL_ATTR_FORM}
     * @param session the HTTP session supplying the selected user id
     * @return the {@code COUSR03} view, or a {@code redirect:} on a first-entry bounce
     */
    @GetMapping(PATH_USERS_DELETE)
    public String showUserDelete(Model model, HttpSession session) {
        String selectedUserId = readSelectedUserId(session);
        COUSR03Form form = new COUSR03Form();
        form.setUsridin(selectedUserId);
        UserDeleteService.UserDeleteResult result =
                userDeleteService.mainEntry(UserDeleteService.AidKey.ENTER, form);
        // Finding F12: when the lookup renders the "Press PF5 to delete" prompt (step one of the
        // two-step delete), arm a single-use nonce bound to the looked-up user for the PF5 confirm.
        armDeleteConfirmation(result, form, session);
        return renderOrRedirectDelete(result, form, model);
    }

    /**
     * Handles a user-delete submission (HTTP {@code POST /admin/users/delete}), the web migration of
     * the COBOL {@code COUSR03C} {@code MAIN-PARA} {@code EVALUATE EIBAID}. Tran {@code CU03},
     * program {@code COUSR03C}.
     *
     * <p>The activated PF-key is resolved and mapped to {@link UserDeleteService.AidKey}, then
     * delegated to {@link UserDeleteService#mainEntry(UserDeleteService.AidKey, COUSR03Form)}. The
     * delete is deliberately two-step and neither step is collapsed:</p>
     * <ul>
     *   <li><b>ENTER</b> &mdash; look up the entered user id and display it for confirmation
     *       ({@code PROCESS-ENTER-KEY}), step one.</li>
     *   <li><b>PF5</b> &mdash; perform the delete ({@code DELETE-USER-INFO}), step two.</li>
     *   <li><b>PF4</b> &mdash; clear the screen ({@code CLEAR-CURRENT-SCREEN}).</li>
     *   <li><b>PF3</b> &mdash; return to the originating program (or the admin menu when none is
     *       recorded).</li>
     *   <li><b>PF12</b> &mdash; return to the admin menu ({@code COADM01C}).</li>
     *   <li><b>any other key</b> &mdash; the invalid-key message.</li>
     * </ul>
     * <p>The delete form carries no password field (AAP note). The redirect target is taken from
     * {@link CardDemoContext#getToProgram()} (the service's {@code XCTL} destination).</p>
     *
     * @param form    the submitted delete form (map {@code COUSR3A}); its {@code usridin} round-trips
     * @param pfkey   the activated PF-key token; {@code null}/unknown collapses to {@code WHEN OTHER}
     * @param model   the Spring MVC model that receives the redisplayed form under
     *                {@link #MODEL_ATTR_FORM}
     * @param session the HTTP session (part of the handler contract; the delete form supplies the
     *                user id directly)
     * @return the {@code COUSR03} view, or a {@code redirect:} for PF3/PF12 navigation
     */
    @PostMapping(PATH_USERS_DELETE)
    public String handleUserDelete(
            @Valid @ModelAttribute(MODEL_ATTR_FORM) COUSR03Form form,
            BindingResult bindingResult,
            @RequestParam(name = PF_KEY_PARAM, required = false) String pfkey,
            Model model,
            HttpSession session) {

        // Finding #11: an over-width field is only reachable by a crafted request. Bounce it with a
        // neutral banner and no service call before the confirmation-integrity gate runs, so the
        // armed nonce is neither consumed nor re-armed and COUSR03C's edit ordering is preserved.
        if (bindingResult.hasErrors()) {
            populateHeader(form);
            form.setErrmsg(MSG_FIELD_LENGTH);
            model.addAttribute(MODEL_ATTR_FORM, form);
            return VIEW_USER_DELETE;
        }

        UserDeleteService.AidKey aid = toDeleteAid(resolvePfKey(pfkey));
        // Confirmation-integrity gate (finding F12): PF5 performs the destructive delete using the
        // round-tripped usridin. Bind it to the server-confirmed target (armed on the ENTER lookup)
        // via the single-use nonce; reject a missing/forged/replayed/target-swapped confirmation
        // with no delete.
        if (aid == UserDeleteService.AidKey.PF5) {
            String armedTarget = confirmationTokenService.armedTarget(session, OP_USER_DELETE);
            boolean confirmed = armedTarget != null
                    && confirmationTokenService.validate(session, OP_USER_DELETE,
                            form.getUsridin(), form.getConfirmToken());
            confirmationTokenService.consume(session);
            if (!confirmed) {
                return rejectDeleteConfirmation(form, model, session, armedTarget);
            }
            form.setUsridin(armedTarget);
        }
        UserDeleteService.UserDeleteResult result = userDeleteService.mainEntry(aid, form);
        armDeleteConfirmation(result, form, session);
        return renderOrRedirectDelete(result, form, model);
    }

    // ================================================================================
    // Render / redirect helpers
    // ================================================================================

    /**
     * Renders the user-list page or performs the redirect requested by a {@link UserListResult}.
     *
     * @param result  the outcome from {@link UserListService}
     * @param form    the list form to render
     * @param model   the Spring MVC model
     * @param session the HTTP session (receives the paging cursor / rows on a render)
     * @return the {@code COUSR00} view name, or a {@code redirect:} view name
     */
    private String renderOrRedirectList(UserListResult result, COUSR00Form form, Model model,
                                        HttpSession session) {
        if (result.isRedirect()) {
            return REDIRECT_PREFIX + routeForProgram(result.targetProgram());
        }
        return renderList(result, form, model, session);
    }

    /**
     * Renders a user-list show-screen outcome (COBOL {@code SEND-USRLST-SCREEN}).
     *
     * <p>The ten display rows are refreshed only when the service actually browsed (a non-empty row
     * list); on the guard and invalid-key paths the row list is empty and the currently displayed
     * rows &mdash; already present on the form &mdash; are retained, matching the COBOL
     * {@code SEND} that does not repopulate the map. The selection inputs are always cleared so a
     * stale flag is not re-submitted, and the paging cursor plus displayed rows are stored in the
     * session for the next request.</p>
     *
     * @param result  the show-screen outcome
     * @param form    the list form to render
     * @param model   the Spring MVC model
     * @param session the HTTP session receiving the paging cursor and displayed rows
     * @return the {@code COUSR00} view name
     */
    private String renderList(UserListResult result, COUSR00Form form, Model model,
                              HttpSession session) {
        if (!result.rows().isEmpty()) {
            applyRowsToForm(form, result.rows());
        }
        clearSelections(form);
        form.setPagenum(formatPageNumber(result.pageNumber()));
        form.setErrmsg(result.hasMessage() ? result.message() : "");
        populateHeader(form);
        saveListState(session, result);
        model.addAttribute(MODEL_ATTR_FORM, form);
        return VIEW_USER_LIST;
    }

    /**
     * Renders the user-update screen or performs the redirect requested by a
     * {@link UserUpdateService.UserUpdateResult}. The redirect destination is the service's
     * {@code XCTL} target recorded on the context ({@link CardDemoContext#getToProgram()}).
     *
     * @param result the outcome from {@link UserUpdateService}
     * @param form   the update form to render (already populated by the service)
     * @param model  the Spring MVC model
     * @return the {@code COUSR02} view name, or a {@code redirect:} view name
     */
    private String renderOrRedirectUpdate(UserUpdateService.UserUpdateResult result,
                                          COUSR02Form form, Model model) {
        if (result.isRedirect()) {
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        form.setErrmsg(result.hasMessage() ? result.message() : "");
        populateHeader(form);
        model.addAttribute(MODEL_ATTR_FORM, form);
        return VIEW_USER_UPDATE;
    }

    /**
     * Renders the user-delete screen or performs the redirect requested by a
     * {@link UserDeleteService.UserDeleteResult}. The redirect destination is the service's
     * {@code XCTL} target recorded on the context ({@link CardDemoContext#getToProgram()}).
     *
     * @param result the outcome from {@link UserDeleteService}
     * @param form   the delete form to render (already populated by the service)
     * @param model  the Spring MVC model
     * @return the {@code COUSR03} view name, or a {@code redirect:} view name
     */
    private String renderOrRedirectDelete(UserDeleteService.UserDeleteResult result,
                                          COUSR03Form form, Model model) {
        if (result.isRedirect()) {
            return REDIRECT_PREFIX + routeForProgram(context.getToProgram());
        }
        form.setErrmsg(result.hasMessage() ? result.message() : "");
        populateHeader(form);
        model.addAttribute(MODEL_ATTR_FORM, form);
        return VIEW_USER_DELETE;
    }

    // ================================================================================
    // Confirmation-integrity helpers (review finding F12)
    //
    // The 3270/CICS presentation implicitly protected the confirmation gesture: the operator could
    // only confirm the record the terminal had just displayed. Over stateless HTTP that becomes a
    // re-postable form, so the CU02 save (PF5/PF3) and the CU03 delete (PF5) are bound to a server-
    // owned single-use nonce armed when the confirm prompt is rendered. See ConfirmationTokenService.
    // ================================================================================

    /**
     * Arms or clears the CU02 save confirmation nonce after a {@code mainEntry} outcome (finding F12).
     *
     * <p>A re-displayed screen that is not a completed save - the neutral &quot;Press PF5 to save&quot;
     * prompt, or an error re-prompt such as &quot;Please modify to update ...&quot; - arms a fresh
     * single-use nonce bound to the loaded {@code usridin} and echoes it onto the form so the next
     * PF5/PF3 can be validated. A redirect (PF3/PF12 hand-off) or a completed save
     * ({@link UserUpdateService.MessageSeverity#SUCCESS}) clears the nonce so it cannot be replayed.
     * Arming is skipped when no user is loaded (a blank {@code usridin}), since there is nothing to
     * confirm.</p>
     *
     * @param result  the outcome of the {@code mainEntry} call being rendered
     * @param form    the update form being redisplayed (receives the echoed nonce)
     * @param session the HTTP session holding the pending confirmation
     */
    private void armUpdateConfirmation(UserUpdateService.UserUpdateResult result,
                                       COUSR02Form form, HttpSession session) {
        boolean savedOrLeaving = result.isRedirect()
                || result.severity() == UserUpdateService.MessageSeverity.SUCCESS;
        if (!savedOrLeaving && form.getUsridin() != null && !form.getUsridin().isBlank()) {
            form.setConfirmToken(
                    confirmationTokenService.arm(session, OP_USER_UPDATE, form.getUsridin()));
        } else {
            confirmationTokenService.consume(session);
            form.setConfirmToken(null);
        }
    }

    /**
     * Rejects an unvalidated CU02 save confirmation (finding F12): performs no write, re-fetches and
     * re-displays the server-confirmed user for a fresh confirmation, and overlays the integrity
     * banner.
     *
     * <p>The re-fetch (ENTER) uses the server-owned {@code armedTarget} (falling back to the posted
     * id only when nothing was armed - e.g. a client that tried to one-shot the save) and thereby
     * discards any edits carried on the tampered re-post; the true record is shown so the operator
     * confirms deliberately. A fresh nonce is armed and echoed. When the re-fetch itself routes away
     * (a redirect), that redirect is honored and the pending confirmation is cleared.</p>
     *
     * @param form           the submitted update form
     * @param model          the Spring MVC model receiving the redisplayed form
     * @param session        the HTTP session holding the pending confirmation
     * @param armedTarget    the server-owned target that was armed, or {@code null} if none
     * @param selectedUserId the selected user id passed to {@code mainEntry} for symmetry
     * @return the {@code COUSR02} view with the integrity banner, or a {@code redirect:} view name
     */
    private String rejectUpdateConfirmation(COUSR02Form form, Model model, HttpSession session,
                                            String armedTarget, String selectedUserId) {
        String target = (armedTarget != null && !armedTarget.isBlank())
                ? armedTarget : form.getUsridin();
        form.setUsridin(target);
        UserUpdateService.UserUpdateResult display =
                userUpdateService.mainEntry(form, UserUpdateService.AidKey.ENTER, selectedUserId);
        String view = renderOrRedirectUpdate(display, form, model);
        if (view.startsWith(REDIRECT_PREFIX)) {
            confirmationTokenService.consume(session);
            return view;
        }
        // The model holds this same form reference: re-arm a fresh nonce and overlay the banner.
        form.setConfirmToken(confirmationTokenService.arm(session, OP_USER_UPDATE, form.getUsridin()));
        form.setErrmsg(MSG_CONFIRM_INTEGRITY);
        return view;
    }

    /**
     * Arms or clears the CU03 delete confirmation nonce after a {@code mainEntry} outcome
     * (finding F12).
     *
     * <p>A re-displayed screen that is not a completed delete - the neutral &quot;Press PF5 to
     * delete&quot; prompt, or an error re-prompt - arms a fresh single-use nonce bound to the
     * looked-up {@code usridin} and echoes it onto the form for the PF5 confirm. A redirect
     * (PF3/PF12 hand-off) or a completed delete
     * ({@link UserDeleteService.MessageSeverity#SUCCESS}) clears the nonce. Arming is skipped when
     * no user is loaded (a blank {@code usridin}).</p>
     *
     * @param result  the outcome of the {@code mainEntry} call being rendered
     * @param form    the delete form being redisplayed (receives the echoed nonce)
     * @param session the HTTP session holding the pending confirmation
     */
    private void armDeleteConfirmation(UserDeleteService.UserDeleteResult result,
                                       COUSR03Form form, HttpSession session) {
        boolean deletedOrLeaving = result.isRedirect()
                || result.severity() == UserDeleteService.MessageSeverity.SUCCESS;
        if (!deletedOrLeaving && form.getUsridin() != null && !form.getUsridin().isBlank()) {
            form.setConfirmToken(
                    confirmationTokenService.arm(session, OP_USER_DELETE, form.getUsridin()));
        } else {
            confirmationTokenService.consume(session);
            form.setConfirmToken(null);
        }
    }

    /**
     * Rejects an unvalidated CU03 delete confirmation (finding F12): performs no delete, re-looks-up
     * and re-displays the server-confirmed user with a fresh nonce, and overlays the integrity
     * banner.
     *
     * @param form        the submitted delete form
     * @param model       the Spring MVC model receiving the redisplayed form
     * @param session     the HTTP session holding the pending confirmation
     * @param armedTarget the server-owned target that was armed, or {@code null} if none
     * @return the {@code COUSR03} view with the integrity banner, or a {@code redirect:} view name
     */
    private String rejectDeleteConfirmation(COUSR03Form form, Model model, HttpSession session,
                                            String armedTarget) {
        String target = (armedTarget != null && !armedTarget.isBlank())
                ? armedTarget : form.getUsridin();
        form.setUsridin(target);
        UserDeleteService.UserDeleteResult display =
                userDeleteService.mainEntry(UserDeleteService.AidKey.ENTER, form);
        String view = renderOrRedirectDelete(display, form, model);
        if (view.startsWith(REDIRECT_PREFIX)) {
            confirmationTokenService.consume(session);
            return view;
        }
        form.setConfirmToken(confirmationTokenService.arm(session, OP_USER_DELETE, form.getUsridin()));
        form.setErrmsg(MSG_CONFIRM_INTEGRITY);
        return view;
    }

    // ================================================================================
    // PF-key resolution and program -> route mapping
    // ================================================================================

    /**
     * Resolves the web PF-key token to a {@link PfKey} through {@link PfKeyHandler}, mirroring the
     * COBOL {@code EVALUATE EIBAID} (migrated {@code CSSTRPFY}).
     *
     * <p>The browser submits a compact token ({@code "ENTER"} or {@code "PFnn"}); it is translated
     * to the CICS AID mnemonic that {@link PfKeyHandler#fromAid(String)} understands
     * ({@code "DFHENTER"}, {@code "DFHPF3"}, &hellip;). A {@code null}, blank, or unrecognized token
     * yields {@link PfKey#OTHER} (the COBOL {@code WHEN OTHER} default); this method never throws.</p>
     *
     * @param pfkeyParam the web PF-key token (may be {@code null})
     * @return the resolved {@link PfKey}, or {@link PfKey#OTHER} for {@code null}/blank/unknown input
     */
    private static PfKey resolvePfKey(String pfkeyParam) {
        if (pfkeyParam == null) {
            return PfKey.OTHER;
        }
        String token = pfkeyParam.trim().toUpperCase(Locale.ROOT);
        if (token.isEmpty()) {
            return PfKey.OTHER;
        }
        final String aidMnemonic;
        if (WEB_KEY_ENTER.equals(token)) {
            aidMnemonic = AID_ENTER;
        } else if (token.startsWith(WEB_KEY_PF_PREFIX) || token.startsWith(WEB_KEY_PA_PREFIX)) {
            // "PF3" -> "DFHPF3"; "PA1" -> "DFHPA1".
            aidMnemonic = AID_DFH_PREFIX + token;
        } else {
            // Unrecognized token; PfKeyHandler will map it to PfKey.OTHER.
            aidMnemonic = token;
        }
        return PfKeyHandler.fromAid(aidMnemonic);
    }

    /**
     * Maps a resolved {@link PfKey} to the {@link UserListService.AidKey} the list service handles.
     * {@code COUSR00C} distinguishes ENTER, PF3, PF7 and PF8; every other key is {@code OTHER}.
     *
     * @param key the resolved PF-key
     * @return the matching list-service AID key
     */
    private static UserListService.AidKey toListAid(PfKey key) {
        return switch (key) {
            case ENTER -> UserListService.AidKey.ENTER;
            case PFK03 -> UserListService.AidKey.PF3;
            case PFK07 -> UserListService.AidKey.PF7;
            case PFK08 -> UserListService.AidKey.PF8;
            default -> UserListService.AidKey.OTHER;
        };
    }

    /**
     * Maps a resolved {@link PfKey} to the {@link UserAddService.AidKey} the add service handles.
     * {@code COUSR01C} distinguishes ENTER, PF3 and PF4; every other key is {@code OTHER}.
     *
     * @param key the resolved PF-key
     * @return the matching add-service AID key
     */
    private static UserAddService.AidKey toAddAid(PfKey key) {
        return switch (key) {
            case ENTER -> UserAddService.AidKey.ENTER;
            case PFK03 -> UserAddService.AidKey.PF3;
            case PFK04 -> UserAddService.AidKey.PF4;
            default -> UserAddService.AidKey.OTHER;
        };
    }

    /**
     * Maps a resolved {@link PfKey} to the {@link UserUpdateService.AidKey} the update service
     * handles. {@code COUSR02C} distinguishes ENTER, PF3, PF4, PF5 and PF12; every other key is
     * {@code OTHER}.
     *
     * @param key the resolved PF-key
     * @return the matching update-service AID key
     */
    private static UserUpdateService.AidKey toUpdateAid(PfKey key) {
        return switch (key) {
            case ENTER -> UserUpdateService.AidKey.ENTER;
            case PFK03 -> UserUpdateService.AidKey.PF3;
            case PFK04 -> UserUpdateService.AidKey.PF4;
            case PFK05 -> UserUpdateService.AidKey.PF5;
            case PFK12 -> UserUpdateService.AidKey.PF12;
            default -> UserUpdateService.AidKey.OTHER;
        };
    }

    /**
     * Maps a resolved {@link PfKey} to the {@link UserDeleteService.AidKey} the delete service
     * handles. {@code COUSR03C} distinguishes ENTER, PF3, PF4, PF5 and PF12; every other key is
     * {@code OTHER}.
     *
     * @param key the resolved PF-key
     * @return the matching delete-service AID key
     */
    private static UserDeleteService.AidKey toDeleteAid(PfKey key) {
        return switch (key) {
            case ENTER -> UserDeleteService.AidKey.ENTER;
            case PFK03 -> UserDeleteService.AidKey.PF3;
            case PFK04 -> UserDeleteService.AidKey.PF4;
            case PFK05 -> UserDeleteService.AidKey.PF5;
            case PFK12 -> UserDeleteService.AidKey.PF12;
            default -> UserDeleteService.AidKey.OTHER;
        };
    }

    /**
     * Maps a COBOL target program name to its web route, reproducing the {@code XCTL PROGRAM(...)}
     * destination as a Spring MVC path. The mapping is traceable to {@code legacy/csd/CARDDEMO.CSD};
     * an unmapped or {@code null} program degrades safely to the admin menu rather than throwing.
     *
     * @param programName the COBOL target program name (for example {@code COUSR02C})
     * @return the route for the program, or {@link #ROUTE_ADMIN_MENU} when unmapped
     */
    private static String routeForProgram(String programName) {
        if (programName == null) {
            return ROUTE_ADMIN_MENU;
        }
        return switch (programName.trim().toUpperCase(Locale.ROOT)) {
            case PGM_USER_LIST -> PATH_USERS;
            case PGM_USER_ADD -> PATH_USERS_ADD;
            case PGM_USER_UPDATE -> PATH_USERS_UPDATE;
            case PGM_USER_DELETE -> PATH_USERS_DELETE;
            case PGM_ADMIN_MENU -> ROUTE_ADMIN_MENU;
            case PGM_SIGNON -> ROUTE_SIGNON;
            default -> ROUTE_ADMIN_MENU;
        };
    }

    // ================================================================================
    // Header population (migration of POPULATE-HEADER-INFO per screen)
    // ================================================================================

    /**
     * Populates the list-screen header (COBOL {@code POPULATE-HEADER-INFO} of {@code COUSR00C}).
     *
     * @param form the list form whose header fields are set
     */
    private static void populateHeader(COUSR00Form form) {
        DateStruct now = now();
        form.setTrnname(TRAN_LIST);
        form.setPgmname(PGM_USER_LIST);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the add-screen header (COBOL {@code POPULATE-HEADER-INFO} of {@code COUSR01C}).
     *
     * @param form the add form whose header fields are set
     */
    private static void populateHeader(COUSR01Form form) {
        DateStruct now = now();
        form.setTrnname(TRAN_ADD);
        form.setPgmname(PGM_USER_ADD);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the update-screen header (COBOL {@code POPULATE-HEADER-INFO} of {@code COUSR02C}).
     *
     * @param form the update form whose header fields are set
     */
    private static void populateHeader(COUSR02Form form) {
        DateStruct now = now();
        form.setTrnname(TRAN_UPDATE);
        form.setPgmname(PGM_USER_UPDATE);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the delete-screen header (COBOL {@code POPULATE-HEADER-INFO} of {@code COUSR03C}).
     *
     * @param form the delete form whose header fields are set
     */
    private static void populateHeader(COUSR03Form form) {
        DateStruct now = now();
        form.setTrnname(TRAN_DELETE);
        form.setPgmname(PGM_USER_DELETE);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Supplies the current date/time as a {@link DateStruct} for header formatting
     * ({@code MM/DD/YY} and {@code HH:MM:SS}).
     *
     * @return a {@link DateStruct} for the current local date-time
     */
    private static DateStruct now() {
        return DateStruct.from(LocalDateTime.now());
    }

    /**
     * Restricts request-parameter binding on every user-admin screen to the fields that screen
     * actually submits (review finding #11), switching on the bound form type. Display-only
     * columns (the ten list rows, page number, header/title/date and the {@code errmsg} line) are
     * excluded so they can no longer be over-posted; {@code pfkey} arrives as a
     * {@code @RequestParam} and is not bound through the form. The single-use confirmation token
     * ({@code confirmToken}, finding #12) is explicitly allowed on the update and delete screens so
     * the arm&rarr;confirm flow keeps working.
     *
     * @param binder the per-request data binder for the bound form
     */
    @InitBinder
    protected void restrictBinding(WebDataBinder binder) {
        // Spring MVC instantiates the @ModelAttribute command lazily, so binder.getTarget() is null
        // when @InitBinder runs; the resolved binder.getTargetType() is the reliable discriminator
        // (it is null for simple @RequestParam binders such as pfkey).
        Class<?> targetType = binder.getTargetType() != null ? binder.getTargetType().resolve() : null;
        if (COUSR00Form.class.equals(targetType)) {
            binder.setAllowedFields("usridin",
                    "sel0001", "sel0002", "sel0003", "sel0004", "sel0005",
                    "sel0006", "sel0007", "sel0008", "sel0009", "sel0010");
        } else if (COUSR01Form.class.equals(targetType)) {
            binder.setAllowedFields("fname", "lname", "userid", "passwd", "usrtype");
        } else if (COUSR02Form.class.equals(targetType)) {
            binder.setAllowedFields("usridin", "fname", "lname", "passwd", "usrtype",
                    "confirmToken");
        } else if (COUSR03Form.class.equals(targetType)) {
            binder.setAllowedFields("usridin", "confirmToken");
        }
    }

    // ================================================================================
    // List row handling (POPULATE-USER-DATA / INITIALIZE-USER-DATA) and page number
    // ================================================================================

    /**
     * Copies up to ten {@link UserRow} values onto the list form's display cells, blanking any
     * unused slot &mdash; the migration of COBOL {@code POPULATE-USER-DATA} plus the
     * {@code INITIALIZE-USER-DATA} clearing of unused table occurrences.
     *
     * @param form the list form whose row cells are set
     * @param rows the rows to display (at most {@link #ROWS_PER_PAGE}); shorter lists blank the rest
     */
    private static void applyRowsToForm(COUSR00Form form, List<UserRow> rows) {
        for (int slot = 1; slot <= ROWS_PER_PAGE; slot++) {
            UserRow row = (slot <= rows.size()) ? rows.get(slot - 1) : UserRow.blank();
            setRow(form, slot, row);
        }
    }

    /**
     * Assigns a single row's four display cells ({@code USRIDnn}, {@code FNAMEnn}, {@code LNAMEnn},
     * {@code UTYPEnn}) to their BMS fields, reproducing the COBOL per-occurrence move.
     *
     * @param form the list form whose row cells are set
     * @param slot the 1-based row slot ({@code 1}..{@link #ROWS_PER_PAGE})
     * @param row  the row value to store
     */
    private static void setRow(COUSR00Form form, int slot, UserRow row) {
        String userId = nz(row.userId());
        String firstName = nz(row.firstName());
        String lastName = nz(row.lastName());
        String userType = nz(row.userType());
        switch (slot) {
            case 1 -> {
                form.setUsrid01(userId);
                form.setFname01(firstName);
                form.setLname01(lastName);
                form.setUtype01(userType);
            }
            case 2 -> {
                form.setUsrid02(userId);
                form.setFname02(firstName);
                form.setLname02(lastName);
                form.setUtype02(userType);
            }
            case 3 -> {
                form.setUsrid03(userId);
                form.setFname03(firstName);
                form.setLname03(lastName);
                form.setUtype03(userType);
            }
            case 4 -> {
                form.setUsrid04(userId);
                form.setFname04(firstName);
                form.setLname04(lastName);
                form.setUtype04(userType);
            }
            case 5 -> {
                form.setUsrid05(userId);
                form.setFname05(firstName);
                form.setLname05(lastName);
                form.setUtype05(userType);
            }
            case 6 -> {
                form.setUsrid06(userId);
                form.setFname06(firstName);
                form.setLname06(lastName);
                form.setUtype06(userType);
            }
            case 7 -> {
                form.setUsrid07(userId);
                form.setFname07(firstName);
                form.setLname07(lastName);
                form.setUtype07(userType);
            }
            case 8 -> {
                form.setUsrid08(userId);
                form.setFname08(firstName);
                form.setLname08(lastName);
                form.setUtype08(userType);
            }
            case 9 -> {
                form.setUsrid09(userId);
                form.setFname09(firstName);
                form.setLname09(lastName);
                form.setUtype09(userType);
            }
            case 10 -> {
                form.setUsrid10(userId);
                form.setFname10(firstName);
                form.setLname10(lastName);
                form.setUtype10(userType);
            }
            default -> {
                // No BMS field beyond row ten; extra rows are intentionally ignored.
            }
        }
    }

    /**
     * Clears the ten selection-flag inputs so a stale flag is not re-submitted on the next request.
     *
     * @param form the list form whose selection inputs are cleared
     */
    private static void clearSelections(COUSR00Form form) {
        form.setSel0001("");
        form.setSel0002("");
        form.setSel0003("");
        form.setSel0004("");
        form.setSel0005("");
        form.setSel0006("");
        form.setSel0007("");
        form.setSel0008("");
        form.setSel0009("");
        form.setSel0010("");
    }

    /**
     * Formats the page number as the COBOL {@code MOVE CDEMO-CU00-PAGE-NUM (PIC 9(08)) TO PAGENUMI
     * (PIC X(8))} did &mdash; zero-padded to eight digits.
     *
     * @param pageNumber the current page number
     * @return the eight-digit, zero-padded page-number text
     */
    private static String formatPageNumber(int pageNumber) {
        return String.format(Locale.ROOT, "%0" + PAGE_NUM_WIDTH + "d", pageNumber);
    }

    // ================================================================================
    // Session-backed COMMAREA extensions (paging cursor, displayed rows, selected user id)
    // ================================================================================

    /**
     * Reads the paging cursor from the session, returning {@link UserListState#initial()} when none
     * is present (first entry).
     *
     * @param session the HTTP session
     * @return the stored {@link UserListState}, or the initial cursor
     */
    private static UserListState readListState(HttpSession session) {
        Object stored = session.getAttribute(SESSION_LIST_STATE);
        if (stored instanceof UserListState state) {
            return state;
        }
        return UserListState.initial();
    }

    /**
     * Reads the currently displayed rows from the session, returning an empty list when none are
     * present. Each element is type-checked so the read is free of unchecked casts.
     *
     * @param session the HTTP session
     * @return the stored rows, or an empty list
     */
    private static List<UserRow> readListRows(HttpSession session) {
        Object stored = session.getAttribute(SESSION_LIST_ROWS);
        if (stored instanceof List<?> list) {
            List<UserRow> rows = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof UserRow row) {
                    rows.add(row);
                }
            }
            return rows;
        }
        return List.of();
    }

    /**
     * Reads the selected user id ({@code CDEMO-CU02-USR-SELECTED} / {@code CDEMO-CU03-USR-SELECTED})
     * from the session, returning an empty string when none is present.
     *
     * @param session the HTTP session
     * @return the stored selected user id, or an empty string
     */
    private static String readSelectedUserId(HttpSession session) {
        Object stored = session.getAttribute(SESSION_SELECTED_USER_ID);
        return (stored instanceof String value) ? value : "";
    }

    /**
     * Stores the paging cursor for the next request, and the displayed rows when the service
     * actually browsed. On a guard / invalid-key redisplay (no rows) the previously stored rows are
     * left intact so the retained page stays consistent with what is shown.
     *
     * @param session the HTTP session
     * @param result  the show-screen outcome whose paging fields and rows are persisted
     */
    private static void saveListState(HttpSession session, UserListResult result) {
        session.setAttribute(SESSION_LIST_STATE, new UserListState(result.pageNumber(),
                result.firstUserId(), result.lastUserId(), result.nextPageAvailable()));
        if (!result.rows().isEmpty()) {
            session.setAttribute(SESSION_LIST_ROWS, new ArrayList<>(result.rows()));
        }
    }

    /**
     * Null-safe string helper: returns the empty string for a {@code null} input, otherwise the
     * value unchanged. Keeps blanked BMS cells rendering as empty rather than {@code "null"}.
     *
     * @param value the value to normalize (may be {@code null})
     * @return {@code value}, or {@code ""} when {@code value} is {@code null}
     */
    private static String nz(String value) {
        return (value == null) ? "" : value;
    }
}
