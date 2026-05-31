package com.carddemo.controller;

import com.carddemo.dto.menu.MenuOption;
import com.carddemo.dto.menu.MenuResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Role-filtered navigation menu REST endpoint ({@code GET /api/menu}).
 *
 * <p>This controller consolidates and replaces the two legacy CardDemo CICS menu programs:
 * <ul>
 *   <li>{@code app/cbl/COMEN01C.cbl} &mdash; the <em>regular user</em> main menu
 *       (TRANID {@code CM00}), whose 10 options are defined by copybook
 *       {@code app/cpy/COMEN02Y.cpy} ({@code CARDDEMO-MAIN-MENU-OPTIONS}, every entry
 *       carrying {@code CDEMO-MENU-OPT-USRTYPE = 'U'}).</li>
 *   <li>{@code app/cbl/COADM01C.cbl} &mdash; the <em>admin</em> menu
 *       (TRANID {@code CA00}), whose 4 options are defined by copybook
 *       {@code app/cpy/COADM02Y.cpy} ({@code CARDDEMO-ADMIN-MENU-OPTIONS}; the copybook has no
 *       usr-type field, so these entries are implicitly {@code 'A'}).</li>
 * </ul>
 *
 * <h2>Endpoint</h2>
 * <ul>
 *   <li>{@code GET /api/menu} &mdash; returns the {@link MenuResponse} appropriate for the
 *       authenticated caller's role; {@code 200 OK} on success, {@code 401 Unauthorized} if the
 *       request is not authenticated.</li>
 * </ul>
 *
 * <h2>COMMAREA decomposition (AAP &sect;0.6.1)</h2>
 * <p>The legacy programs received the 1024-byte {@code COCOM01Y} COMMAREA and branched on
 * {@code CDEMO-USER-TYPE} (88-levels {@code CDEMO-USRTYP-ADMIN = 'A'} /
 * {@code CDEMO-USRTYP-USER = 'U'}). In the stateless REST model that session field is decomposed
 * into the Spring Security {@link Authentication} held by {@link SecurityContextHolder}: the
 * authenticated principal's authorities are scanned for {@code ROLE_ADMIN} to decide which menu to
 * return, and {@link Authentication#getName()} supplies the user id that the COMMAREA carried in
 * {@code CDEMO-USER-ID}.</p>
 *
 * <h2>Menu selection policy</h2>
 * <p><strong>Strict COBOL parity:</strong> an {@code ADMIN} caller receives <em>only</em> the
 * admin menu (the 4 {@code COUSR*} security-administration options from {@code COADM02Y}); every
 * other authenticated caller receives <em>only</em> the user menu (the 10 transactional options
 * from {@code COMEN02Y}). This mirrors the original deployment where {@code COADM01C} displayed
 * exclusively the admin options and {@code COMEN01C} exclusively the user options; the menus are
 * <em>not</em> merged. A client that needs the other menu simply re-invokes this endpoint after the
 * role context changes (REST is stateless &mdash; the client controls navigation, replacing the
 * legacy {@code EXEC CICS XCTL} program chaining and PF3/PF12 navigation keys).</p>
 *
 * <h2>Authorization</h2>
 * <p>Intentionally <strong>no</strong> class-level {@code @PreAuthorize}: any <em>authenticated</em>
 * user (USER or ADMIN) may call {@code GET /api/menu}; the result is merely role-filtered. The
 * requirement that the caller be authenticated at all is enforced by the application
 * {@code SecurityFilterChain} (see {@code com.carddemo.security.SecurityConfig}) &mdash; anonymous
 * requests are rejected with {@code 401} before reaching this method. The defensive guard in
 * {@link #getMenu()} additionally returns {@code 401} should the security context be empty or
 * unauthenticated, which is the REST analogue of {@code COMEN01C}'s {@code IF EIBCALEN = 0 ...
 * RETURN-TO-SIGNON-SCREEN} guard for an absent COMMAREA.</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-19</strong> (role-mapping fidelity): {@code userType = 'A'} &rarr;
 *       {@code ROLE_ADMIN} &rarr; admin menu; {@code userType = 'U'} &rarr; {@code ROLE_USER}
 *       &rarr; user menu.</li>
 *   <li><strong>PR-28</strong> (Jakarta EE 10 baseline): no {@code javax.*} imports.</li>
 *   <li><strong>PR-29</strong> (constructor injection only): the class is annotated with Lombok
 *       {@link RequiredArgsConstructor}; it holds no injected collaborators (the menus are static
 *       configuration and the caller identity is read from the security context), but the policy is
 *       honoured uniformly &mdash; no {@code @Autowired} field injection is used.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>No service layer:</strong> this is a purely presentational transformation of the
 *       authenticated principal into a fixed, role-selected option list; reading the security
 *       context is a controller-layer concern, so no {@code @Service} collaborator is required.</li>
 *   <li><strong>Immutable static menus:</strong> {@link #USER_MENU} and {@link #ADMIN_MENU} are
 *       built once via {@link List#of(Object...)} (deeply immutable), replacing the COBOL
 *       {@code OCCURS} tables {@code CDEMO-MENU-OPT} / {@code CDEMO-ADMIN-OPT}. Option ordering and
 *       wording match {@code COMEN02Y}/{@code COADM02Y} exactly.</li>
 *   <li><strong>Program-name traceability:</strong> each {@link MenuOption} preserves the original
 *       COBOL program name in {@code pgmName} (e.g. {@code "COACTVWC"}) for audit/traceability while
 *       {@code route} supplies the modern REST URI the frontend should call.</li>
 *   <li><strong>No legacy option validation:</strong> the COBOL {@code PROCESS-ENTER-KEY} numeric
 *       option parsing ("option must be numeric" / "Invalid option number") and {@code DUMMY}
 *       "coming soon" handling are obsolete &mdash; REST clients invoke a {@code route} directly, so
 *       every advertised option is a live endpoint.</li>
 * </ul>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (COMEN02Y), CardDemo_v1.0-26-g42273c1-79
 * (COADM02Y).
 *
 * @see com.carddemo.dto.menu.MenuOption the immutable per-option transport record
 * @see com.carddemo.dto.menu.MenuResponse the outbound {@code GET /api/menu} payload
 */
@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Menu", description = "Role-filtered menu endpoints for navigation")
@SecurityRequirement(name = "bearerAuth")
public class MenuController {

    /**
     * Spring Security authority that designates an administrator. The presence of this authority
     * among the caller's {@link GrantedAuthority granted authorities} selects the admin menu
     * (PR-19: COBOL {@code CDEMO-USRTYP-ADMIN = 'A'} &rarr; {@code ROLE_ADMIN}).
     */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** COBOL {@code CDEMO-USER-TYPE} code for an administrator ({@code 'A'}). */
    private static final String USER_TYPE_ADMIN = "A";

    /** COBOL {@code CDEMO-USER-TYPE} code for a regular user ({@code 'U'}). */
    private static final String USER_TYPE_USER = "U";

    /**
     * The 10 regular-user menu options, in the exact order and wording of copybook
     * {@code app/cpy/COMEN02Y.cpy} ({@code CARDDEMO-MAIN-MENU-OPTIONS}). Every entry carries
     * {@code requiredRole = 'U'} (COBOL {@code CDEMO-MENU-OPT-USRTYPE = 'U'}). The {@code pgmName}
     * preserves the original CICS program; the {@code route} maps it to the modern REST endpoint
     * defined by the corresponding controller (AAP &sect;0.4.1.1).
     */
    private static final List<MenuOption> USER_MENU = List.of(
            new MenuOption(1,  "Account View",        "COACTVWC", USER_TYPE_USER, "/api/accounts/{acctId}"),
            new MenuOption(2,  "Account Update",      "COACTUPC", USER_TYPE_USER, "/api/accounts/{acctId}"),
            new MenuOption(3,  "Credit Card List",    "COCRDLIC", USER_TYPE_USER, "/api/accounts/{acctId}/cards"),
            new MenuOption(4,  "Credit Card View",    "COCRDSLC", USER_TYPE_USER, "/api/cards/{cardNum}"),
            new MenuOption(5,  "Credit Card Update",  "COCRDUPC", USER_TYPE_USER, "/api/cards/{cardNum}"),
            new MenuOption(6,  "Transaction List",    "COTRN00C", USER_TYPE_USER, "/api/transactions"),
            new MenuOption(7,  "Transaction View",    "COTRN01C", USER_TYPE_USER, "/api/transactions/{tranId}"),
            new MenuOption(8,  "Transaction Add",     "COTRN02C", USER_TYPE_USER, "/api/transactions"),
            new MenuOption(9,  "Transaction Reports", "CORPT00C", USER_TYPE_USER, "/api/reports"),
            new MenuOption(10, "Bill Payment",        "COBIL00C", USER_TYPE_USER, "/api/accounts/{acctId}/payments")
    );

    /**
     * The 4 administrator menu options, in the exact order and wording of copybook
     * {@code app/cpy/COADM02Y.cpy} ({@code CARDDEMO-ADMIN-MENU-OPTIONS}). The copybook has no
     * usr-type field, so every entry is implicitly an admin option ({@code requiredRole = 'A'}).
     * These are the user-administration ("Security") functions whose REST equivalents live under
     * {@code /api/admin/users} and are themselves guarded by {@code @PreAuthorize("hasRole('ADMIN')")}
     * in {@code UserController} (PR-18).
     */
    private static final List<MenuOption> ADMIN_MENU = List.of(
            new MenuOption(1, "User List (Security)",   "COUSR00C", USER_TYPE_ADMIN, "/api/admin/users"),
            new MenuOption(2, "User Add (Security)",    "COUSR01C", USER_TYPE_ADMIN, "/api/admin/users"),
            new MenuOption(3, "User Update (Security)", "COUSR02C", USER_TYPE_ADMIN, "/api/admin/users/{userId}"),
            new MenuOption(4, "User Delete (Security)", "COUSR03C", USER_TYPE_ADMIN, "/api/admin/users/{userId}")
    );

    /**
     * Returns the role-filtered navigation menu for the authenticated caller.
     *
     * <p>Reads the current {@link Authentication} from the {@link SecurityContextHolder}; if the
     * security context is empty or the principal is not authenticated, responds {@code 401}
     * (the REST analogue of {@code COMEN01C}'s {@code IF EIBCALEN = 0} not-signed-on guard).
     * Otherwise it inspects the caller's authorities for {@link #ROLE_ADMIN}: an administrator
     * receives the {@link #ADMIN_MENU} (4 {@code COUSR*} options); any other authenticated user
     * receives the {@link #USER_MENU} (10 transactional options). The selected options, the
     * caller's {@code userType} ({@code 'A'}/{@code 'U'}), the {@code userId}, and a client-friendly
     * welcome message are wrapped in a {@link MenuResponse}.</p>
     *
     * @return {@code 200 OK} with the role-appropriate {@link MenuResponse}; {@code 401} (empty
     *         body) if the request is not authenticated
     */
    @GetMapping
    @Operation(
            summary = "Get role-filtered menu options",
            description = "Returns the menu options available to the authenticated user. "
                    + "ADMIN role gets the admin menu (user-administration); USER role gets the "
                    + "transactional menu. Replaces the COMEN01C (CM00) and COADM01C (CA00) CICS programs.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Menu options returned successfully"),
            @ApiResponse(responseCode = "401", description = "User not authenticated")
    })
    public ResponseEntity<MenuResponse> getMenu() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // Defensive guard: the SecurityFilterChain normally rejects anonymous requests with 401
        // before this method runs, but if the security context is empty or unauthenticated we
        // cannot build a meaningful menu. This is the REST analogue of COMEN01C's
        // "IF EIBCALEN = 0 ... RETURN-TO-SIGNON-SCREEN" guard for a missing COMMAREA.
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("GET /api/menu invoked without an authenticated principal; returning 401");
            return ResponseEntity.status(401).build();
        }

        String userId = auth.getName();

        // PR-19: an ADMIN authority selects the admin menu; everything else is treated as a user.
        boolean isAdmin = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_ADMIN::equals);

        String userType = isAdmin ? USER_TYPE_ADMIN : USER_TYPE_USER;
        List<MenuOption> options = isAdmin ? ADMIN_MENU : USER_MENU;
        String welcomeMessage = isAdmin
                ? "Welcome to the Admin Menu, " + userId
                : "Welcome to the Main Menu, " + userId;

        log.debug("Returning {} menu ({} option(s)) for user: {}",
                isAdmin ? "ADMIN" : "USER", options.size(), userId);

        MenuResponse response = new MenuResponse(options, userType, userId, welcomeMessage);
        return ResponseEntity.ok(response);
    }
}
