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
package com.aws.carddemo.web.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.DateStruct;
import com.aws.carddemo.dto.screen.COADM01Form;
import com.aws.carddemo.service.online.AdminMenuService;
import com.aws.carddemo.service.online.AdminMenuService.AdminMenuResult;
import com.aws.carddemo.util.PfKeyHandler;
import com.aws.carddemo.util.constants.Messages;
import com.aws.carddemo.util.constants.ScreenTitles;

/**
 * Spring MVC {@link Controller} for the AWS CardDemo administrator main menu, the web-tier
 * migration of the CICS pseudo-conversational COBOL program {@code COADM01C}.
 *
 * <p><b>Origin: legacy/cbl/COADM01C.cbl (CICS tran CA00, mapset COADM01). Admin menu;
 * option -&gt; redirect; PF3 -&gt; signon.</b> The CICS transaction id {@code CA00} maps to
 * program {@code COADM01C} (verified {@code legacy/csd/CARDDEMO.CSD}:
 * {@code DEFINE TRANSACTION(CA00) ... PROGRAM(COADM01C)}). The 24&times;80 screen contract is
 * preserved by the server-rendered Thymeleaf view {@code COADM01}
 * ({@code src/main/resources/templates/COADM01.html}); its symbolic map is
 * {@code legacy/cpy-bms/COADM01.CPY} (mapset {@code COADM01}, map {@code COADM1A}).</p>
 *
 * <h2>Responsibility (presentation + navigation only)</h2>
 * <p>This controller displays the four-option administration menu, accepts a numeric option,
 * and &mdash; on a valid selection &mdash; redirects to the corresponding user-administration
 * route (the modern equivalent of the COBOL {@code XCTL}). The {@code PF3} key returns to the
 * sign-on screen. All option validation, routing decisions, and pseudo-conversational hand-off
 * bookkeeping are delegated to {@link AdminMenuService}; the numbered option catalog is owned by
 * that service ({@code dto.menu.AdminMenuOptions}, the migration of copybook {@code COADM02Y}).
 * This class contains no business logic: it maps HTTP to the service and renders / redirects.</p>
 *
 * <h2>Option &rarr; route map (menu order preserved; traceable to CARDDEMO.CSD)</h2>
 * <ul>
 *   <li>1. User List (Security) &rarr; program {@code COUSR00C} (tran {@code CU00}) &rarr;
 *       {@code /admin/users}</li>
 *   <li>2. User Add (Security) &rarr; program {@code COUSR01C} (tran {@code CU01}) &rarr;
 *       {@code /admin/users/add}</li>
 *   <li>3. User Update (Security) &rarr; program {@code COUSR02C} (tran {@code CU02}) &rarr;
 *       {@code /admin/users/update}</li>
 *   <li>4. User Delete (Security) &rarr; program {@code COUSR03C} (tran {@code CU03}) &rarr;
 *       {@code /admin/users/delete}</li>
 * </ul>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) that carried state across CICS returns becomes the
 * session-scoped {@link CardDemoContext}, injected here and handed to the service so it can set
 * the {@code from*}/{@code to*} program hand-off fields before a redirect (the COBOL
 * {@code CDEMO-FROM-PROGRAM}/{@code CDEMO-TO-PROGRAM} moves preceding {@code XCTL}).</p>
 *
 * <h2>Admin-only access (parity for RACF transaction protection)</h2>
 * <p>Both routes live under {@code /admin/**}, which {@code config.SecurityConfig} restricts to
 * {@code ROLE_ADMIN}. That URL/role contract is the parity replacement for the RACF
 * transaction-level protection of {@code CA00}. This controller therefore does <em>not</em>
 * re-implement any role or RACF check &mdash; it relies on Spring Security exactly as mandated by
 * the folder specification.</p>
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li>{@code @Controller} only &mdash; server-rendered screens, never REST/JSON.</li>
 *   <li>Constructor injection of {@code private final} collaborators (no field injection, no
 *       Lombok).</li>
 *   <li>Typed exceptions raised by the service are never swallowed; they propagate to the global
 *       exception handler.</li>
 *   <li>No floating-point types and no monetary arithmetic occur in this presentation tier.</li>
 * </ul>
 */
@Controller
public class AdminMenuController {

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CA00'} &mdash; this screen's CICS transaction id. */
    private static final String TRANSACTION_ID = "CA00";

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} &mdash; this program's name. */
    private static final String PROGRAM_NAME = "COADM01C";

    /** Logical Thymeleaf view name for the admin-menu screen ({@code templates/COADM01.html}). */
    private static final String VIEW_NAME = "COADM01";

    /** Model attribute name the {@code COADM01} template binds via {@code th:object="${form}"}. */
    private static final String MODEL_ATTR_FORM = "form";

    /** Shared request path for the admin menu (GET displays, POST submits). */
    private static final String PATH_ADMIN_MENU = "/admin/menu";

    /** Request-parameter name carrying the activated PF-key ({@code ENTER} / {@code PF3}). */
    private static final String PF_KEY_PARAM = "pfkey";

    /** Spring MVC redirect view-name prefix. */
    private static final String REDIRECT_PREFIX = "redirect:";

    /** Route for option 1 &mdash; user list ({@code COUSR00C}, tran {@code CU00}). */
    private static final String ROUTE_USERS = "/admin/users";

    /** Route for option 2 &mdash; user add ({@code COUSR01C}, tran {@code CU01}). */
    private static final String ROUTE_USERS_ADD = "/admin/users/add";

    /** Route for option 3 &mdash; user update ({@code COUSR02C}, tran {@code CU02}). */
    private static final String ROUTE_USERS_UPDATE = "/admin/users/update";

    /** Route for option 4 &mdash; user delete ({@code COUSR03C}, tran {@code CU03}). */
    private static final String ROUTE_USERS_DELETE = "/admin/users/delete";

    /** Route back to the sign-on screen ({@code COSGN00C}); the PF3 / first-entry destination. */
    private static final String ROUTE_SIGNON = "/signon";

    /** Target program for option 1 (COBOL {@code CDEMO-ADMIN-OPT-PGMNAME}, {@code CARDDEMO.CSD}). */
    private static final String PGM_USER_LIST = "COUSR00C";

    /** Target program for option 2. */
    private static final String PGM_USER_ADD = "COUSR01C";

    /** Target program for option 3. */
    private static final String PGM_USER_UPDATE = "COUSR02C";

    /** Target program for option 4. */
    private static final String PGM_USER_DELETE = "COUSR03C";

    /** Sign-on program (COBOL literal {@code 'COSGN00C'}); the PF3 return-to-sign-on target. */
    private static final String PGM_SIGNON = "COSGN00C";

    /** Web PF-key token for the ENTER action (see {@code COADM01.html} submit button). */
    private static final String WEB_KEY_ENTER = "ENTER";

    /** Web PF-key token prefix for program-function keys (for example {@code "PF3"}). */
    private static final String WEB_KEY_PF_PREFIX = "PF";

    /** Web PF-key token prefix for program-attention keys (for example {@code "PA1"}). */
    private static final String WEB_KEY_PA_PREFIX = "PA";

    /** CICS AID mnemonic for the ENTER key, understood by {@link PfKeyHandler#fromAid(String)}. */
    private static final String AID_ENTER = "DFHENTER";

    /** CICS AID mnemonic prefix ({@code DFH}) prepended to a {@code PFnn}/{@code PAn} web token. */
    private static final String AID_DFH_PREFIX = "DFH";

    /** Number of BMS option display fields on the map ({@code OPTN001}..{@code OPTN012}). */
    private static final int MAX_OPTION_SLOTS = 12;

    /**
     * Admin-menu business/navigation service (migration of {@code COADM01C}); supplies option
     * validation, routing decisions, the sign-on return, and the numbered option display lines.
     */
    private final AdminMenuService adminMenuService;

    /**
     * Session-scoped navigation context (COMMAREA {@code COCOM01Y} replacement) handed to the
     * service so the {@code from*}/{@code to*} program hand-off is preserved across a redirect.
     */
    private final CardDemoContext context;

    /**
     * Creates the admin-menu controller via Spring constructor injection.
     *
     * <p>A single constructor needs no {@code @Autowired}. Neither argument is dereferenced here,
     * so the constructor performs no work beyond storing its collaborators.</p>
     *
     * @param adminMenuService the admin-menu service (migration of {@code COADM01C}); must not be
     *                         {@code null}
     * @param context          the session-scoped CardDemo context (COMMAREA replacement); must not
     *                         be {@code null}
     */
    public AdminMenuController(AdminMenuService adminMenuService, CardDemoContext context) {
        this.adminMenuService = adminMenuService;
        this.context = context;
    }

    /**
     * Displays the administration menu (HTTP {@code GET /admin/menu}).
     *
     * <p>This is the presentation counterpart of the COBOL {@code SEND-MENU-SCREEN} first-display
     * path: it builds a fresh screen form, populates the header
     * ({@code POPULATE-HEADER-INFO}) and the numbered option lines ({@code BUILD-MENU-OPTIONS},
     * delegated to {@link AdminMenuService#buildMenuOptions()}), and renders the {@code COADM01}
     * view. Access is already constrained to administrators by the {@code /admin/**} Spring
     * Security rule, so no first-entry/role bounce is re-implemented here.</p>
     *
     * @param model the Spring MVC model that receives the screen form under {@link #MODEL_ATTR_FORM}
     * @return the logical view name {@link #VIEW_NAME}
     */
    @GetMapping(PATH_ADMIN_MENU)
    public String showMenu(Model model) {
        COADM01Form form = new COADM01Form();
        populateHeader(form);
        populateMenuOptions(form);
        model.addAttribute(MODEL_ATTR_FORM, form);
        return VIEW_NAME;
    }

    /**
     * Handles an administration-menu submission (HTTP {@code POST /admin/menu}), the web migration
     * of the COBOL {@code RECEIVE-MENU-SCREEN} plus {@code EVALUATE EIBAID} of {@code MAIN-PARA}
     * and paragraph {@code PROCESS-ENTER-KEY} in {@code legacy/cbl/COADM01C.cbl}.
     *
     * <p>The activated PF-key is resolved through {@link PfKeyHandler} (mirroring the COBOL
     * {@code CSSTRPFY}/{@code EVALUATE EIBAID}) and dispatched exactly as the COBOL did:</p>
     * <ul>
     *   <li><b>ENTER</b> ({@code DFHENTER}) &rarr; {@link AdminMenuService#processEnterKey}
     *       validates and routes the entered option. A valid option yields a redirect to the
     *       mapped user-administration route (the {@code XCTL}); an invalid/zero/out-of-range
     *       option re-renders the menu with the COBOL message
     *       {@code "Please enter a valid option number..."}.</li>
     *   <li><b>PF3</b> ({@code DFHPF3}) &rarr; {@link AdminMenuService#returnToSignonScreen}
     *       transfers control back to the sign-on program {@code COSGN00C} (redirect to
     *       {@code /signon}).</li>
     *   <li><b>any other key</b> &rarr; the menu is re-rendered with
     *       {@link Messages#CCDA_MSG_INVALID_KEY} (COBOL {@code WHEN OTHER}).</li>
     * </ul>
     *
     * @param form  the submitted admin-menu screen form (map {@code COADM1A}); bound under
     *              {@link #MODEL_ATTR_FORM} so it is available again on a re-render
     * @param pfkey the activated PF-key token ({@code ENTER} or {@code PF3}); {@code null} or
     *              unrecognized values collapse to the COBOL {@code WHEN OTHER} branch
     * @return a {@code redirect:} view name for a valid selection or the PF3 return, otherwise the
     *         re-rendered {@link #VIEW_NAME}
     */
    @PostMapping(PATH_ADMIN_MENU)
    public String handleMenuSelection(
            @ModelAttribute(MODEL_ATTR_FORM) COADM01Form form,
            @RequestParam(name = PF_KEY_PARAM, required = false) String pfkey) {

        PfKey key = resolvePfKey(pfkey);

        // WHEN DFHENTER PERFORM PROCESS-ENTER-KEY.
        if (PfKeyHandler.isEnter(key)) {
            AdminMenuResult result = adminMenuService.processEnterKey(form, context);
            if (result.isRedirect()) {
                // IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY' -> XCTL PROGRAM(...).
                return REDIRECT_PREFIX + routeForProgram(result.targetProgram());
            }
            // Invalid option (or the preserved "coming soon" branch): redisplay with the message.
            return renderMenu(form, result.message());
        }

        // WHEN DFHPF3 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, PERFORM RETURN-TO-SIGNON-SCREEN.
        if (PfKeyHandler.isPf3(key)) {
            AdminMenuResult result = adminMenuService.returnToSignonScreen(context);
            return REDIRECT_PREFIX + routeForProgram(result.targetProgram());
        }

        // WHEN OTHER MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE, PERFORM SEND-MENU-SCREEN.
        return renderMenu(form, Messages.CCDA_MSG_INVALID_KEY);
    }

    /**
     * Re-renders the admin menu with an error / informational message, the presentation
     * counterpart of the COBOL {@code SEND-MENU-SCREEN} redisplay path.
     *
     * <p>The submitted form only carries the entered {@code option}; the header and option-line
     * display fields are not part of the HTTP submission, so they are rebuilt here before the
     * supplied message is placed on the screen's message line ({@code ERRMSG}).</p>
     *
     * @param form   the screen form to redisplay (already bound in the model as
     *               {@link #MODEL_ATTR_FORM})
     * @param errmsg the message to show on the {@code ERRMSG} line
     * @return the logical view name {@link #VIEW_NAME}
     */
    private String renderMenu(COADM01Form form, String errmsg) {
        populateHeader(form);
        populateMenuOptions(form);
        form.setErrmsg(errmsg);
        return VIEW_NAME;
    }

    /**
     * Populates the screen header, the migration of COBOL {@code POPULATE-HEADER-INFO}.
     *
     * <p>Sets the transaction id ({@code CA00}), program name ({@code COADM01C}), the two banner
     * title lines from {@link ScreenTitles}, and the current date/time formatted through
     * {@link DateStruct} as {@code MM/DD/YY} ({@code WS-CURDATE-MM-DD-YY}) and {@code HH:MM:SS}
     * ({@code WS-CURTIME-HH-MM-SS}).</p>
     *
     * @param form the screen form whose header fields are set
     */
    private void populateHeader(COADM01Form form) {
        form.setTrnname(TRANSACTION_ID);
        form.setPgmname(PROGRAM_NAME);
        form.setTitle01(ScreenTitles.CCDA_TITLE01);
        form.setTitle02(ScreenTitles.CCDA_TITLE02);
        DateStruct now = DateStruct.from(LocalDateTime.now());
        form.setCurdate(now.getFormattedDateMmDdYy());
        form.setCurtime(now.getFormattedTimeHhMmSs());
    }

    /**
     * Populates the numbered option display lines, the migration of COBOL
     * {@code BUILD-MENU-OPTIONS}.
     *
     * <p>The formatted option lines are produced by {@link AdminMenuService#buildMenuOptions()}
     * (for example {@code "01. User List (Security)"}) and placed into the map's
     * {@code OPTN001}..{@code OPTN012} fields in order. The loop is bounded by
     * {@link #MAX_OPTION_SLOTS} so a line count exceeding the available display fields degrades
     * gracefully, matching the COBOL {@code EVALUATE WS-IDX ... WHEN OTHER CONTINUE}.</p>
     *
     * @param form the screen form whose option-line fields are set
     */
    private void populateMenuOptions(COADM01Form form) {
        List<String> optionLines = adminMenuService.buildMenuOptions();
        for (int index = 0; index < optionLines.size() && index < MAX_OPTION_SLOTS; index++) {
            assignOptionLine(form, index + 1, optionLines.get(index));
        }
    }

    /**
     * Assigns a single formatted option line to its BMS display field, reproducing the COBOL
     * {@code EVALUATE WS-IDX} that fans a computed line out to the corresponding {@code OPTNnnnO}
     * field.
     *
     * @param form the screen form whose option field is set
     * @param slot the 1-based option slot ({@code 1}..{@link #MAX_OPTION_SLOTS})
     * @param text the formatted option line to store
     */
    private static void assignOptionLine(COADM01Form form, int slot, String text) {
        switch (slot) {
            case 1 -> form.setOptn001(text);
            case 2 -> form.setOptn002(text);
            case 3 -> form.setOptn003(text);
            case 4 -> form.setOptn004(text);
            case 5 -> form.setOptn005(text);
            case 6 -> form.setOptn006(text);
            case 7 -> form.setOptn007(text);
            case 8 -> form.setOptn008(text);
            case 9 -> form.setOptn009(text);
            case 10 -> form.setOptn010(text);
            case 11 -> form.setOptn011(text);
            case 12 -> form.setOptn012(text);
            default -> {
                // COBOL BUILD-MENU-OPTIONS "WHEN OTHER CONTINUE": there is no BMS field beyond
                // OPTN012, so any additional lines are intentionally ignored.
            }
        }
    }

    /**
     * Resolves the web PF-key token to a {@link PfKey} through {@link PfKeyHandler}, mirroring the
     * COBOL {@code EVALUATE EIBAID}.
     *
     * <p>The browser submits a compact token ({@code "ENTER"} or {@code "PF3"}); this is translated
     * to the CICS AID mnemonic that {@link PfKeyHandler#fromAid(String)} understands
     * ({@code "DFHENTER"}, {@code "DFHPF3"}, &hellip;) so PF-key resolution stays centralized in the
     * migrated {@code CSSTRPFY} utility. A {@code null}, blank, or unrecognized token yields
     * {@link PfKey#OTHER} (the COBOL {@code WHEN OTHER} default); this method never throws.</p>
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
     * Maps a target program name to its web route, reproducing the COBOL {@code XCTL PROGRAM(...)}
     * destination as a Spring MVC redirect path.
     *
     * <p>The mapping is authoritative and traceable to {@code dto.menu.AdminMenuOptions} (copybook
     * {@code COADM02Y}) and {@code legacy/csd/CARDDEMO.CSD}. An unmapped or {@code null} program
     * degrades safely to the admin menu itself rather than throwing.</p>
     *
     * @param programName the COBOL target program name (for example {@code COUSR00C})
     * @return the redirect route for the program, or {@link #PATH_ADMIN_MENU} when unmapped
     */
    private static String routeForProgram(String programName) {
        if (programName == null) {
            return PATH_ADMIN_MENU;
        }
        return switch (programName.trim().toUpperCase(Locale.ROOT)) {
            case PGM_USER_LIST -> ROUTE_USERS;
            case PGM_USER_ADD -> ROUTE_USERS_ADD;
            case PGM_USER_UPDATE -> ROUTE_USERS_UPDATE;
            case PGM_USER_DELETE -> ROUTE_USERS_DELETE;
            case PGM_SIGNON -> ROUTE_SIGNON;
            default -> PATH_ADMIN_MENU;
        };
    }
}
