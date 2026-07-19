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
package com.aws.carddemo.web;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import com.aws.carddemo.dto.AdminMenuRequest;
import com.aws.carddemo.dto.AdminMenuResponse;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.MenuMapper;
import com.aws.carddemo.security.CardDemoUserDetails;
import com.aws.carddemo.service.MenuService;
import com.aws.carddemo.service.MenuService.MenuRouting;

/**
 * REST controller for the CardDemo <strong>Admin Menu</strong> screen &mdash; the Java
 * re-platform of the online COBOL program <b>COADM01C</b> (CICS transaction {@code CA00}),
 * whose source is retained for reference under {@code legacy/cbl/COADM01C.cbl}.
 *
 * <h2>Legacy behavior reproduced</h2>
 * On the mainframe, an administrator lands on this screen immediately after sign-on
 * ({@code COSGN00C} {@code XCTL}s here when the authenticated user is of type {@code 'A'}).
 * The screen lists the security/user-administration options (from copybook
 * {@code COADM02Y}) and, when the operator selects one and presses <em>Enter</em>,
 * {@code EXEC CICS XCTL PROGRAM(...)} transfers control to the chosen admin program
 * ({@code COUSR00C}..{@code COUSR03C}). <em>PF3</em> returns to the sign-on screen
 * ({@code COSGN00C}); any other attention key re-displays the menu with an
 * "invalid key" message. This controller preserves those exact caller-visible outcomes.
 *
 * <h2>Pseudo-conversational translation (AAP &sect;0.7.1 H1)</h2>
 * The CICS pseudo-conversational model has no HTTP analog, so the legacy
 * {@code EXEC CICS XCTL} navigation is surfaced as stateless HTTP:
 * <ul>
 *   <li>a first display of the menu ({@code SEND-MENU-SCREEN}) becomes {@link #menu()}
 *       (HTTP {@code GET});</li>
 *   <li>a screen submit ({@code RECEIVE-MENU-SCREEN} followed by the {@code EVALUATE EIBAID}
 *       in {@code MAIN-PARA}) becomes {@link #select(AdminMenuRequest, CardDemoUserDetails)}
 *       (HTTP {@code POST});</li>
 *   <li>every {@code XCTL PROGRAM(target)} becomes a {@code 200 OK} response carrying the
 *       navigation intent in the {@value #HEADER_NEXT_PROGRAM} and
 *       {@value #HEADER_NEXT_TRANSACTION} response headers, so the client (or an upstream
 *       gateway) performs the actual screen transition. No server-side session state is
 *       retained between requests.</li>
 * </ul>
 *
 * <h2>Authorization (ADMIN-ONLY)</h2>
 * The legacy admin menu is reachable only by administrators. That restriction is enforced
 * primarily by the URL rules in {@code config/SecurityConfig} on {@code /api/v1/admin/**};
 * the class-level {@link PreAuthorize} here is defense-in-depth. The expression
 * {@code hasRole('ADMIN')} matches the authority {@code ROLE_ADMIN} produced by
 * {@code security/UserRole} (Spring Security prepends the {@code ROLE_} prefix), which itself
 * maps the legacy {@code SEC-USR-TYPE = 'A'} code.
 *
 * <h2>Design (thin controller)</h2>
 * All option-catalog and option-selection logic lives in {@link MenuService} (the Java
 * re-platform of the {@code PROCESS-ENTER-KEY} paragraph); response assembly lives in
 * {@link MenuMapper}. This controller only translates HTTP to those collaborators and maps
 * each outcome onto the navigation-header / error-message contract &mdash; it holds no
 * business logic, keeps no mutable state, and never wraps calls in {@code try}/{@code catch}
 * (validation and error translation are handled by Bean Validation and the global exception
 * handler respectively). Dependencies are supplied by constructor injection only.
 */
@RestController
@RequestMapping("/api/v1/admin/menu")
@Tag(name = "Admin Menu",
        description = "Admin Menu screen (COBOL COADM01C / CICS transaction CA00): lists the administrative options and routes the selected option to its target program. Requires the ADMIN role.")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMenuController {

    /**
     * This program's identifier, shown in the screen header ({@code PGMNAMEO}). COBOL
     * {@code WS-PGMNAME VALUE 'COADM01C'} (COADM01C L36).
     */
    private static final String PROGRAM_NAME = "COADM01C";

    /**
     * This program's CICS transaction identifier, shown in the screen header
     * ({@code TRNNAMEO}). COBOL {@code WS-TRANID VALUE 'CA00'} (COADM01C L37).
     */
    private static final String TRANSACTION_ID = "CA00";

    /**
     * Target program of the PF3 "back" navigation &mdash; the sign-on screen. Reproduces
     * {@code RETURN-TO-SIGNON-SCREEN}, which {@code XCTL}s to {@code COSGN00C}
     * (COADM01C L83, L97, L163, L166).
     */
    private static final String BACK_PROGRAM = "COSGN00C";

    /**
     * CICS transaction identifier under which {@link #BACK_PROGRAM} runs ({@code CC00}),
     * per the {@code CARDDEMO.CSD} registry and the README transaction inventory.
     */
    private static final String BACK_TRANSACTION = "CC00";

    /**
     * First screen title line ({@code TITLE01O}). Byte-for-byte reproduction of the COBOL
     * {@code CCDA-TITLE01} literal (copybook {@code COTTL01Y}), a 40-character
     * {@code PIC X(40)} value with its original centering spaces preserved so the screen
     * field contract is retained exactly (AAP &sect;0.7.1 H2).
     */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * Second screen title line ({@code TITLE02O}). Byte-for-byte reproduction of the COBOL
     * {@code CCDA-TITLE02} literal (copybook {@code COTTL01Y}), a 40-character
     * {@code PIC X(40)} value with its original centering spaces preserved.
     */
    private static final String TITLE02 = "              CardDemo                  ";

    /**
     * Caller-visible message shown when the operator presses an unsupported attention key.
     * The meaningful text of the COBOL {@code CCDA-MSG-INVALID-KEY} literal (copybook
     * {@code CSMSG01Y}, {@code PIC X(50)}), used by {@code MAIN-PARA}'s
     * {@code WHEN OTHER} branch (COADM01C L100-L101). Trailing field padding is dropped to
     * match the trimmed error-message style used elsewhere in the migration.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * Response header carrying the target program name of a navigation &mdash; the
     * transport-neutral surfacing of the legacy {@code EXEC CICS XCTL PROGRAM(...)}.
     */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /**
     * Response header carrying the CICS transaction identifier under which the target
     * program of a navigation runs.
     */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * Response header echoing the authenticated administrator's login id (the non-secret
     * {@code SEC-USR-ID}). Reproduces, in a transport-neutral way, the user identity the
     * COBOL carried forward in {@code CARDDEMO-COMMAREA} across the {@code XCTL} chain.
     */
    private static final String HEADER_USER_ID = "X-CardDemo-User-Id";

    /**
     * Immutable lookup from an admin target program name to its CICS transaction identifier,
     * sourced from the {@code CARDDEMO.CSD} resource registry (the reference catalog of
     * transactions and programs). It covers exactly the four programs reachable from the
     * admin-menu catalog ({@code COADM02Y}): user list, add, update, and delete.
     */
    private static final Map<String, String> ADMIN_PROGRAM_TRANSACTIONS = Map.of(
            "COUSR00C", "CU00",
            "COUSR01C", "CU01",
            "COUSR02C", "CU02",
            "COUSR03C", "CU03");

    /**
     * Menu-option catalog and option-selection routing service &mdash; the Java re-platform
     * of the {@code COADM01C} {@code PROCESS-ENTER-KEY} logic. Never {@code null}.
     */
    private final MenuService menuService;

    /**
     * Hand-written response-assembly mapper for the admin-menu screen, populating the
     * {@code COADM1AO} output fields. Never {@code null}.
     */
    private final MenuMapper menuMapper;

    /**
     * Creates the controller with its required collaborators. Constructor injection is used
     * exclusively (no field injection, no Lombok), so the dependencies can be {@code final}
     * and the controller is trivially unit-testable.
     *
     * @param menuService the menu catalog / selection-routing service; must not be {@code null}
     * @param menuMapper  the admin-menu response-assembly mapper; must not be {@code null}
     */
    public AdminMenuController(MenuService menuService, MenuMapper menuMapper) {
        this.menuService = menuService;
        this.menuMapper = menuMapper;
    }

    /**
     * Returns the admin menu for its initial display &mdash; the Java re-platform of the
     * first-entry {@code SEND-MENU-SCREEN} path of {@code COADM01C} (the {@code MAIN-PARA}
     * branch taken when the program has not yet been re-entered, COADM01C L87-L90).
     *
     * <p>The response carries the header (transaction name, titles, program name, current
     * date and time) and the four admin option labels built from the {@code COADM02Y}
     * catalog; no error message is present on this path.</p>
     *
     * @return the populated {@link AdminMenuResponse} (HTTP {@code 200 OK})
     */
    @GetMapping
    @Operation(summary = "Display the admin menu",
            description = "First-entry admin menu listing the administrative options (COBOL COADM01C initial SEND MAP). Requires the ADMIN role.")
    public AdminMenuResponse menu() {
        return buildMenuResponse(null);
    }

    /**
     * Processes an admin-menu submit &mdash; the Java re-platform of the
     * {@code RECEIVE-MENU-SCREEN} + {@code EVALUATE EIBAID} logic of {@code COADM01C}
     * {@code MAIN-PARA} (COADM01C L91-L104). The branch is selected from
     * {@link AdminMenuRequest#action()} (the transport-neutral translation of the CICS
     * {@code EIBAID}); a {@code null} or otherwise unrecognized action is treated as the
     * legacy {@code WHEN OTHER} case.
     *
     * <ul>
     *   <li><strong>{@link PfKeyAction#ENTER}</strong> &rarr; {@code PROCESS-ENTER-KEY}. The
     *       raw option ({@link AdminMenuRequest#option()}) is routed by
     *       {@link MenuService#selectAdminMenuOption(String)}:
     *       <ul>
     *         <li>a successful routing reproduces {@code XCTL PROGRAM(target)} &mdash;
     *             {@code 200 OK} with {@value #HEADER_NEXT_PROGRAM} set to the target program
     *             and {@value #HEADER_NEXT_TRANSACTION} set to its derived transaction id;</li>
     *         <li>a non-navigating routing (invalid option number, or a "coming soon"
     *             placeholder target) reproduces the "set message and re-display" behavior
     *             &mdash; {@code 200 OK} with the exact caller-visible text in
     *             {@link AdminMenuResponse#errorMessage()} and no navigation headers.</li>
     *       </ul>
     *   </li>
     *   <li><strong>{@link PfKeyAction#PF3}</strong> &rarr; {@code RETURN-TO-SIGNON-SCREEN}:
     *       {@code 200 OK} with the navigation headers pointing at {@value #BACK_PROGRAM} /
     *       {@value #BACK_TRANSACTION}.</li>
     *   <li><strong>Any other key (or {@code null})</strong> &rarr; the {@code WHEN OTHER}
     *       branch: {@code 200 OK} with {@link #INVALID_KEY_MESSAGE} in the error line.</li>
     * </ul>
     *
     * <p>In every case the admin option labels are rebuilt into the response, exactly as the
     * COBOL always re-ran {@code BUILD-MENU-OPTIONS} before sending the map.</p>
     *
     * @param request the submitted menu selection (validated); its {@code option} carries the
     *                operator-typed selector and its {@code action} the attention key
     * @param user    the authenticated administrator principal, injected by Spring Security;
     *                may be {@code null} when no principal is bound (its login id, when
     *                present, is echoed in the {@value #HEADER_USER_ID} header)
     * @return the {@link AdminMenuResponse} with any applicable navigation headers
     *         (HTTP {@code 200 OK})
     */
    @PostMapping
    @Operation(summary = "Submit an admin-menu selection",
            description = "Reproduces the COADM01C EVALUATE EIBAID routing of the selected administrative option.")
    public ResponseEntity<AdminMenuResponse> select(
            @Valid @RequestBody AdminMenuRequest request,
            @AuthenticationPrincipal CardDemoUserDetails user) {

        PfKeyAction action = request.action();

        // WHEN DFHENTER PERFORM PROCESS-ENTER-KEY (COADM01C L94-L95).
        if (action == PfKeyAction.ENTER) {
            MenuRouting routing = menuService.selectAdminMenuOption(request.option());
            if (routing.success()) {
                // IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
                //     EXEC CICS XCTL PROGRAM(...) (COADM01C L138-L145).
                String targetProgram = routing.targetProgram();
                ResponseEntity.BodyBuilder builder = okBuilder(user)
                        .header(HEADER_NEXT_PROGRAM, targetProgram);
                String targetTransaction = deriveTransactionId(targetProgram);
                if (targetTransaction != null) {
                    builder = builder.header(HEADER_NEXT_TRANSACTION, targetTransaction);
                }
                return builder.body(buildMenuResponse(null));
            }
            // Invalid option number, or a "coming soon" placeholder target: set the message
            // and re-display the menu (COADM01C L127-L134, L147-L154).
            return okBuilder(user).body(buildMenuResponse(routing.message()));
        }

        // WHEN DFHPF3: MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, RETURN-TO-SIGNON-SCREEN
        // (COADM01C L96-L98).
        if (action == PfKeyAction.PF3) {
            return okBuilder(user)
                    .header(HEADER_NEXT_PROGRAM, BACK_PROGRAM)
                    .header(HEADER_NEXT_TRANSACTION, BACK_TRANSACTION)
                    .body(buildMenuResponse(null));
        }

        // WHEN OTHER: invalid key pressed, re-display the menu (COADM01C L99-L102).
        return okBuilder(user).body(buildMenuResponse(INVALID_KEY_MESSAGE));
    }

    /**
     * Assembles a fully populated {@link AdminMenuResponse}, rebuilding the admin option
     * labels on every call &mdash; the Java re-platform of {@code POPULATE-HEADER-INFO}
     * (COADM01C L202-L221) followed by {@code BUILD-MENU-OPTIONS} (COADM01C L226-L263).
     *
     * <p>The labels are produced from the {@code COADM02Y} catalog via
     * {@link MenuService#getAdminMenu()} and {@link MenuService#formatLabel(MenuService.MenuOption)}
     * (the {@code "NN. Name"} form the COBOL built with
     * {@code STRING opt-num '. ' opt-name}). The header date and time are rendered by the
     * mapper from a single {@code now} instant so they remain mutually consistent.</p>
     *
     * @param errorMessage the status/error line for {@code ERRMSGO}, or {@code null} when no
     *                     message is shown
     * @return the assembled response; never {@code null}
     */
    private AdminMenuResponse buildMenuResponse(String errorMessage) {
        List<String> optionLabels = menuService.getAdminMenu().stream()
                .map(menuService::formatLabel)
                .toList();
        return menuMapper.toAdminMenuResponse(
                optionLabels,
                errorMessage,
                LocalDateTime.now(),
                TRANSACTION_ID,
                TITLE01,
                TITLE02,
                PROGRAM_NAME);
    }

    /**
     * Starts a {@code 200 OK} response builder, pre-populating the {@value #HEADER_USER_ID}
     * header with the authenticated administrator's login id when a principal is bound.
     * Centralizing this keeps every {@link #select(AdminMenuRequest, CardDemoUserDetails)}
     * branch null-safe and consistent.
     *
     * @param user the authenticated principal, or {@code null} when none is bound
     * @return a mutable {@link ResponseEntity.BodyBuilder} for further header/body chaining
     */
    private ResponseEntity.BodyBuilder okBuilder(CardDemoUserDetails user) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (user != null) {
            builder = builder.header(HEADER_USER_ID, user.getUsername());
        }
        return builder;
    }

    /**
     * Resolves the CICS transaction identifier for an admin target program from the
     * {@link #ADMIN_PROGRAM_TRANSACTIONS} registry.
     *
     * @param targetProgram the target program name (a valid admin selection is always one of
     *                      the four {@code COADM02Y} catalog programs)
     * @return the matching transaction id, or {@code null} if the program is not a known admin
     *         target (in which case the navigation omits the transaction header defensively)
     */
    private static String deriveTransactionId(String targetProgram) {
        return ADMIN_PROGRAM_TRANSACTIONS.get(targetProgram);
    }
}
