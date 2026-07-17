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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aws.carddemo.dto.MainMenuRequest;
import com.aws.carddemo.dto.MainMenuResponse;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.MenuMapper;
import com.aws.carddemo.security.CardDemoUserDetails;
import com.aws.carddemo.service.MenuService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

/**
 * REST controller for the CardDemo <strong>Main Menu</strong> screen &mdash; the Java
 * re-platform of the CICS online COBOL program {@code COMEN01C} (transaction
 * {@code CM00}, relocated to {@code legacy/cbl/COMEN01C.cbl}). Regular users (and
 * administrators) land on this screen after a successful sign-on; it lists the ten
 * main-menu options and routes the selected option to its target program, exactly as
 * the legacy program's {@code EXEC CICS XCTL PROGRAM(...)} did.
 *
 * <h2>Pseudo-conversational translation</h2>
 * {@code COMEN01C} is pseudo-conversational: its first invocation with no re-enter flag
 * <em>SENDs</em> the menu map ({@code SEND-MENU-SCREEN}), and every subsequent
 * invocation <em>RECEIVEs</em> the map and dispatches on the attention identifier
 * ({@code EVALUATE EIBAID}). With no 3270 terminal in the target, that split is
 * expressed as two stateless HTTP endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/menu} reproduces the first-entry SEND: it returns the menu
 *       with the option list populated and no error message (the legacy
 *       {@code MOVE LOW-VALUES ... PERFORM SEND-MENU-SCREEN} branch).</li>
 *   <li>{@code POST /api/v1/menu} reproduces the re-entry RECEIVE + {@code EVALUATE
 *       EIBAID}: it dispatches on the request's {@link PfKeyAction} &mdash;
 *       {@link PfKeyAction#ENTER} runs the legacy {@code PROCESS-ENTER-KEY} paragraph,
 *       {@link PfKeyAction#PF3} returns to the sign-on program, and any other key
 *       reproduces the legacy {@code WHEN OTHER} invalid-key redisplay.</li>
 * </ul>
 *
 * <h2>Navigation (the {@code XCTL} replacement)</h2>
 * A legacy {@code XCTL PROGRAM(...)} transferred control to another program without a
 * screen render. Because this controller returns a DTO rather than transferring
 * control, a successful navigation is signalled with two response headers &mdash;
 * {@value #HEADER_NEXT_PROGRAM} (the target program name) and
 * {@value #HEADER_NEXT_TRANSACTION} (the target's CICS transaction id, resolved from
 * the {@code CARDDEMO.CSD} registry) &mdash; while the body still carries the fully
 * populated menu (the legacy program always rebuilds the option list before every
 * SEND). A non-navigating outcome (invalid option, admin-only denial, coming-soon, or
 * an invalid key) returns {@code 200 OK} with the caller-visible message in
 * {@link MainMenuResponse#errorMessage()} and no navigation header, mirroring the
 * "set {@code WS-MESSAGE} and re-display the menu" behavior.
 *
 * <h2>User type comes from the security context</h2>
 * The admin-only gate in {@code PROCESS-ENTER-KEY} keys off {@code CDEMO-USER-TYPE},
 * which the legacy program carried in the CICS COMMAREA rather than reading from the
 * screen. To preserve that trust boundary, the user type is taken from the
 * authenticated {@link CardDemoUserDetails} principal
 * ({@code principal.getRole().getCode()} yields {@code 'A'} or {@code 'U'}) and is
 * <em>never</em> accepted from the request body.
 *
 * <h2>Thin-controller contract</h2>
 * All option parsing, range checking, the admin-only gate, and the exact caller-visible
 * message literals live in {@link MenuService}; response assembly (header fields, date
 * and time, option labels) lives in {@link MenuMapper}. This controller only supplies
 * the user type from the principal, dispatches on the attention key, and maps the
 * result to an HTTP response &mdash; it holds no business logic and expects no
 * exceptions, so it contains no {@code try}/{@code catch}.
 *
 * @see MenuService
 * @see MenuMapper
 * @see MainMenuRequest
 * @see MainMenuResponse
 */
@RestController
@RequestMapping("/api/v1/menu")
@Tag(name = "Main Menu",
        description = "Main Menu screen (COBOL COMEN01C / CICS transaction CM00): "
                + "lists the ten main-menu options and routes the selected option to its target program.")
public class MainMenuController {

    /**
     * This program's identifier, shown in the screen header ({@code PGMNAMEO}). COBOL
     * {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} ({@code COMEN01C} L36).
     */
    private static final String THIS_PROGRAM = "COMEN01C";

    /**
     * This program's CICS transaction id, shown in the screen header ({@code TRNNAMEO}).
     * COBOL {@code WS-TRANID PIC X(04) VALUE 'CM00'} ({@code COMEN01C} L37).
     */
    private static final String THIS_TRANSACTION = "CM00";

    /**
     * Back-target program: the sign-on program the legacy {@code WHEN DFHPF3} branch
     * transferred to via {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} followed by
     * {@code XCTL} ({@code COMEN01C} L97, L176).
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * CICS transaction id for the sign-on program ({@link #SIGNON_PROGRAM}), per the
     * {@code CARDDEMO.CSD} registry ({@code DEFINE TRANSACTION(CC00) PROGRAM(COSGN00C)}).
     */
    private static final String SIGNON_TRANSACTION = "CC00";

    /**
     * Response header carrying the next program name for a navigation outcome &mdash;
     * the transport-neutral replacement for the legacy {@code EXEC CICS XCTL
     * PROGRAM(...)} target.
     */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /**
     * Response header carrying the next CICS transaction id for a navigation outcome,
     * derived from the target program name via the {@code CARDDEMO.CSD} registry.
     */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * First application title line shown in the header ({@code TITLE01O}). COBOL
     * {@code CCDA-TITLE01} from copybook {@code COTTL01Y} ({@code PIC X(40)}), preserved
     * verbatim including its embedded spacing.
     */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * Second application title line shown in the header ({@code TITLE02O}). COBOL
     * {@code CCDA-TITLE02} from copybook {@code COTTL01Y} ({@code PIC X(40)}), preserved
     * verbatim including its embedded spacing.
     */
    private static final String TITLE02 = "              CardDemo                  ";

    /**
     * Caller-visible message for the {@code WHEN OTHER} (unrecognized attention key)
     * branch. COBOL moves {@code CCDA-MSG-INVALID-KEY} (copybook {@code CSMSG01Y}) into
     * {@code WS-MESSAGE} ({@code COMEN01C} L100-L101); the significant text is preserved
     * verbatim.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * Registry mapping each main-menu target program name to its CICS transaction id,
     * sourced verbatim from the CICS resource-definition file {@code CARDDEMO.CSD}
     * ({@code DEFINE TRANSACTION(...) PROGRAM(...)}). It contains exactly the ten
     * programs the {@link MenuService} main-menu catalog can navigate to, so a
     * successful {@link PfKeyAction#ENTER} selection can populate the
     * {@value #HEADER_NEXT_TRANSACTION} header. The back-target ({@link #SIGNON_PROGRAM}
     * /{@link #SIGNON_TRANSACTION}) is handled by dedicated constants and is
     * intentionally not part of this catalog.
     */
    private static final Map<String, String> PROGRAM_TO_TRANSACTION = Map.ofEntries(
            Map.entry("COACTVWC", "CAVW"),
            Map.entry("COACTUPC", "CAUP"),
            Map.entry("COCRDLIC", "CCLI"),
            Map.entry("COCRDSLC", "CCDL"),
            Map.entry("COCRDUPC", "CCUP"),
            Map.entry("COTRN00C", "CT00"),
            Map.entry("COTRN01C", "CT01"),
            Map.entry("COTRN02C", "CT02"),
            Map.entry("CORPT00C", "CR00"),
            Map.entry("COBIL00C", "CB00"));

    /**
     * Menu option catalog and option-selection routing (the {@code PROCESS-ENTER-KEY}
     * business logic). Injected via the constructor.
     */
    private final MenuService menuService;

    /**
     * Outbound response assembler for the menu screen (header, date/time, option
     * labels, error line). Injected via the constructor.
     */
    private final MenuMapper menuMapper;

    /**
     * Creates the controller with its collaborators. Constructor injection is used
     * throughout (no field injection, no Lombok) so the dependencies are final and the
     * controller is trivially unit-testable.
     *
     * @param menuService the menu catalog and routing service; must not be {@code null}
     * @param menuMapper  the response-assembly mapper; must not be {@code null}
     */
    public MainMenuController(MenuService menuService, MenuMapper menuMapper) {
        this.menuService = menuService;
        this.menuMapper = menuMapper;
    }

    /**
     * Displays the main menu &mdash; the Java re-platform of the first-entry
     * {@code SEND-MENU-SCREEN} branch of {@code COMEN01C} (the {@code NOT
     * CDEMO-PGM-REENTER} path). The full option list is rebuilt on every call, and no
     * error message is set. {@code COMEN01C}'s {@code BUILD-MENU-OPTIONS} lists every
     * option unconditionally (the admin-only restriction is enforced only at selection
     * time), so the display is independent of the caller's role; the authenticated
     * principal is nonetheless bound to make the endpoint's security context explicit.
     *
     * @param principal the authenticated user (bound from the security context); its
     *                  role is not needed to render the option list but is present for
     *                  the endpoint's security contract
     * @return {@code 200 OK} with the fully populated {@link MainMenuResponse}
     */
    @GetMapping
    @Operation(summary = "Display the main menu",
            description = "Reproduces the first-entry SEND of COMEN01C: returns the menu header, "
                    + "the current date/time, and the ten formatted option labels with no error message.")
    public ResponseEntity<MainMenuResponse> menu(
            @AuthenticationPrincipal CardDemoUserDetails principal) {
        return ResponseEntity.ok(buildMenuResponse(null));
    }

    /**
     * Processes a menu-screen submission &mdash; the Java re-platform of the re-entry
     * {@code RECEIVE-MENU-SCREEN} + {@code EVALUATE EIBAID} logic of {@code COMEN01C}.
     * Dispatch on {@link MainMenuRequest#action()} reproduces the legacy attention-key
     * evaluation, and the option list is rebuilt in every response (the legacy program
     * always re-displays the menu):
     * <ul>
     *   <li><strong>{@link PfKeyAction#ENTER}</strong> &rarr; runs {@code PROCESS-ENTER-KEY}
     *       via {@link MenuService#selectMainMenuOption(String, char)}, passing the user
     *       type taken from the {@code principal} (the COMMAREA-carried
     *       {@code CDEMO-USER-TYPE}, never the request body). A successful routing
     *       navigates to the option's target program (headers set); an unsuccessful
     *       routing (invalid option, admin-only denial, or coming-soon) returns the
     *       exact caller-visible message with no navigation header.</li>
     *   <li><strong>{@link PfKeyAction#PF3}</strong> &rarr; navigates back to the sign-on
     *       program ({@link #SIGNON_PROGRAM}/{@link #SIGNON_TRANSACTION}), reproducing the
     *       legacy {@code WHEN DFHPF3} {@code XCTL} to {@code COSGN00C}.</li>
     *   <li><strong>any other key</strong> (including a {@code null} action) &rarr; returns
     *       the {@value #INVALID_KEY_MESSAGE} message and re-lists the menu, reproducing
     *       the legacy {@code WHEN OTHER} branch.</li>
     * </ul>
     * The request body is bean-validated ({@link Valid}) for the structural constraints
     * on the option field (at most two digits); the business "invalid option number"
     * outcome is still produced by {@link MenuService} and returned with {@code 200 OK}.
     *
     * @param request   the submitted menu request (validated); carries the typed option
     *                  and the attention key
     * @param principal the authenticated user, the source of the user type for the
     *                  admin-only gate; must be present
     * @return {@code 200 OK} with the menu response; a navigation outcome additionally
     *         carries the {@value #HEADER_NEXT_PROGRAM} and
     *         {@value #HEADER_NEXT_TRANSACTION} headers
     */
    @PostMapping
    @Operation(summary = "Submit a main-menu selection",
            description = "Reproduces the re-entry RECEIVE + EVALUATE EIBAID of COMEN01C. ENTER routes the "
                    + "selected option (navigation headers on success); PF3 returns to sign-on; any other "
                    + "key re-displays the menu with the invalid-key message.")
    public ResponseEntity<MainMenuResponse> select(
            @Valid @RequestBody MainMenuRequest request,
            @AuthenticationPrincipal CardDemoUserDetails principal) {

        PfKeyAction action = request.action();

        // WHEN DFHENTER -> PROCESS-ENTER-KEY (COMEN01C L94-L95, L115-L165).
        if (action == PfKeyAction.ENTER) {
            char userType = principal.getRole().getCode().charAt(0);
            MenuService.MenuRouting routing =
                    menuService.selectMainMenuOption(request.option(), userType);
            if (routing.success()) {
                // Legacy XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION)) (COMEN01C L152-L155).
                return navigate(routing.targetProgram(),
                        resolveTransaction(routing.targetProgram()),
                        buildMenuResponse(null));
            }
            // Invalid option / admin-only denial / coming-soon: set WS-MESSAGE, re-display.
            return ResponseEntity.ok(buildMenuResponse(routing.message()));
        }

        // WHEN DFHPF3 -> XCTL back to the sign-on program (COMEN01C L96-L98, L170-L177).
        if (action == PfKeyAction.PF3) {
            return navigate(SIGNON_PROGRAM, SIGNON_TRANSACTION, buildMenuResponse(null));
        }

        // WHEN OTHER (unrecognized attention key, or none) -> invalid-key message + re-display
        // (COMEN01C L99-L102).
        return ResponseEntity.ok(buildMenuResponse(INVALID_KEY_MESSAGE));
    }

    /**
     * Builds a {@code 200 OK} navigation response: the fully populated menu body plus
     * the two navigation headers that replace the legacy {@code XCTL} transfer of
     * control.
     *
     * @param nextProgram     the target program name ({@value #HEADER_NEXT_PROGRAM})
     * @param nextTransaction the target CICS transaction id ({@value #HEADER_NEXT_TRANSACTION})
     * @param body            the menu response body to return
     * @return the {@link ResponseEntity} carrying the navigation headers and body
     */
    private ResponseEntity<MainMenuResponse> navigate(
            String nextProgram, String nextTransaction, MainMenuResponse body) {
        return ResponseEntity.ok()
                .header(HEADER_NEXT_PROGRAM, nextProgram)
                .header(HEADER_NEXT_TRANSACTION, nextTransaction)
                .body(body);
    }

    /**
     * Assembles the outbound menu response, rebuilding the option labels on every call
     * to mirror {@code COMEN01C}, which repopulates the option list before each
     * {@code SEND}. The header transaction/program identifiers and the two title lines
     * are the legacy screen constants; the date and time derive from the current
     * instant (the legacy {@code FUNCTION CURRENT-DATE}).
     *
     * @param errorMessage the caller-visible message for the error/status line, or
     *                     {@code null} when there is no message to show
     * @return the assembled {@link MainMenuResponse}
     */
    private MainMenuResponse buildMenuResponse(String errorMessage) {
        return menuMapper.toMainMenuResponse(
                buildMenuLabels(),
                errorMessage,
                LocalDateTime.now(),
                THIS_TRANSACTION,
                TITLE01,
                TITLE02,
                THIS_PROGRAM);
    }

    /**
     * Builds the ordered list of formatted option labels ({@code "NN. Name"}) from the
     * {@link MenuService} main-menu catalog, reproducing {@code COMEN01C}'s
     * {@code BUILD-MENU-OPTIONS} paragraph. Element order is preserved so index
     * {@code 0} corresponds to the legacy {@code OPTN001O} line.
     *
     * @return the formatted main-menu option labels in catalog order
     */
    private List<String> buildMenuLabels() {
        return menuService.getMainMenu().stream()
                .map(menuService::formatLabel)
                .toList();
    }

    /**
     * Resolves a target program name to its CICS transaction id using the
     * {@link #PROGRAM_TO_TRANSACTION} registry. Every program the main-menu catalog can
     * navigate to is present in the registry; an unknown name (not reachable with the
     * authored catalog) resolves to the empty string rather than raising an exception,
     * keeping the controller exception-free.
     *
     * @param program the target program name
     * @return the CICS transaction id, or the empty string if the program is unknown
     */
    private String resolveTransaction(String program) {
        return PROGRAM_TO_TRANSACTION.getOrDefault(program, "");
    }
}
