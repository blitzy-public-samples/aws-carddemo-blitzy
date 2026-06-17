package com.carddemo.controller;

import com.carddemo.dto.MenuOptionDto;
import com.carddemo.dto.MenuResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing the CardDemo navigation menus.
 *
 * <p>This controller is the Spring Boot re-expression of the two legacy CICS menu programs that,
 * on the 3270 terminal, presented an option list and routed the operator to the chosen function
 * by means of an {@code EXEC CICS XCTL}:</p>
 * <ul>
 *   <li>{@code COMEN01C} &mdash; transaction {@code CM00}, the <em>regular-user</em> "Main Menu",
 *       whose option table is the copybook {@code COMEN02Y} ({@code CARDDEMO-MAIN-MENU-OPTIONS},
 *       {@code CDEMO-MENU-OPT-COUNT = 10}).</li>
 *   <li>{@code COADM01C} &mdash; transaction {@code CA00}, the <em>administrator</em> "Admin Menu",
 *       whose option table is the copybook {@code COADM02Y} ({@code CARDDEMO-ADMIN-MENU-OPTIONS},
 *       {@code CDEMO-ADMIN-OPT-COUNT = 4}).</li>
 * </ul>
 *
 * <p>Each legacy program is pure presentation/routing: it validates the entered option number
 * against the option count and then {@code XCTL}s to {@code CDEMO-MENU-OPT-PGMNAME(option)}. There
 * is no business logic to delegate. The BMS screen maps {@code COMEN01}/{@code COADM01} that
 * rendered these menus are <strong>retired</strong>; the terminal is replaced by a REST client
 * that receives the option list and dispatches itself using each option's {@code targetProgram}
 * navigation hint.</p>
 *
 * <h2>Why this controller carries static data (the documented thin-controller exception)</h2>
 * <p>The application service layer intentionally contains <strong>no {@code MenuService}</strong>:
 * because the menu programs hold no business behaviour, there is nothing for a service to compute.
 * This controller is therefore the single, deliberate place where light in-controller data lives.
 * The two option tables are transcribed verbatim from the menu copybooks and exposed as immutable
 * {@code static final} lists ({@link #MAIN_OPTIONS} and {@link #ADMIN_OPTIONS}), built once at
 * class-initialization time rather than rebuilt per request.</p>
 *
 * <h2>Endpoints and role gating</h2>
 * <p>Honoring the "one operation per original transaction id" rule, exactly two endpoints are
 * exposed &mdash; one per source program/transaction:</p>
 * <ul>
 *   <li>{@code GET /menu} &mdash; returns the 10-option main menu. Reachable by <em>any</em>
 *       authenticated user; the {@code SecurityConfig} {@code anyRequest().authenticated()} rule
 *       already requires a valid JWT, so no method-level guard is added here. A missing or invalid
 *       token yields HTTP&nbsp;401.</li>
 *   <li>{@code GET /admin/menu} &mdash; returns the 4-option admin menu. Restricted to
 *       {@code ROLE_ADMIN} via {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")}, mirroring
 *       the legacy admin-only {@code COADM01C}. Because method security is active
 *       ({@code @EnableMethodSecurity}), a non-admin caller's request is denied and surfaces as
 *       HTTP&nbsp;403 through the application's {@code GlobalExceptionHandler}.</li>
 * </ul>
 *
 * <p>No class-level {@code @RequestMapping} is declared because the two paths use different
 * prefixes ({@code /menu} and {@code /admin/menu}); the endpoints carry their absolute paths
 * directly, and there is no {@code /api} prefix.</p>
 *
 * @see MenuResponse
 * @see MenuOptionDto
 */
@RestController
public class MenuController {

    /**
     * The regular-user main-menu option table, transcribed verbatim from copybook
     * {@code COMEN02Y} ({@code CARDDEMO-MAIN-MENU-OPTIONS}, {@code CDEMO-MENU-OPT-COUNT = 10}).
     *
     * <p>Each entry maps one {@code CDEMO-MENU-OPT} occurrence: the {@code CDEMO-MENU-OPT-NUM}
     * ({@code PIC 9(02)}) becomes the option number, the {@code CDEMO-MENU-OPT-NAME}
     * ({@code PIC X(35)}, space-padded) becomes the trimmed display label, and the
     * {@code CDEMO-MENU-OPT-PGMNAME} ({@code PIC X(08)}) becomes the verbatim legacy program id
     * used by the client as a navigation hint. The per-option {@code CDEMO-MENU-OPT-USRTYPE}
     * gate (all {@code 'U'} for the main menu) is not represented; role gating is performed by the
     * endpoint/HTTP layer. The list is immutable ({@link List#of}).</p>
     */
    private static final List<MenuOptionDto> MAIN_OPTIONS = List.of(
            new MenuOptionDto(1, "Account View", "COACTVWC"),
            new MenuOptionDto(2, "Account Update", "COACTUPC"),
            new MenuOptionDto(3, "Credit Card List", "COCRDLIC"),
            new MenuOptionDto(4, "Credit Card View", "COCRDSLC"),
            new MenuOptionDto(5, "Credit Card Update", "COCRDUPC"),
            new MenuOptionDto(6, "Transaction List", "COTRN00C"),
            new MenuOptionDto(7, "Transaction View", "COTRN01C"),
            new MenuOptionDto(8, "Transaction Add", "COTRN02C"),
            new MenuOptionDto(9, "Transaction Reports", "CORPT00C"),
            new MenuOptionDto(10, "Bill Payment", "COBIL00C"));

    /**
     * The administrator admin-menu option table, transcribed verbatim from copybook
     * {@code COADM02Y} ({@code CARDDEMO-ADMIN-MENU-OPTIONS}, {@code CDEMO-ADMIN-OPT-COUNT = 4}).
     *
     * <p>Each entry maps one {@code CDEMO-ADMIN-OPT} occurrence: {@code CDEMO-ADMIN-OPT-NUM}
     * ({@code PIC 9(02)}) &rarr; option number, {@code CDEMO-ADMIN-OPT-NAME} ({@code PIC X(35)},
     * space-padded) &rarr; trimmed display label, {@code CDEMO-ADMIN-OPT-PGMNAME}
     * ({@code PIC X(08)}) &rarr; verbatim legacy program id. The admin copybook has no user-type
     * field. The list is immutable ({@link List#of}).</p>
     */
    private static final List<MenuOptionDto> ADMIN_OPTIONS = List.of(
            new MenuOptionDto(1, "User List (Security)", "COUSR00C"),
            new MenuOptionDto(2, "User Add (Security)", "COUSR01C"),
            new MenuOptionDto(3, "User Update (Security)", "COUSR02C"),
            new MenuOptionDto(4, "User Delete (Security)", "COUSR03C"));

    /**
     * Returns the regular-user main menu &mdash; the REST re-expression of {@code COMEN01C}
     * (transaction {@code CM00}).
     *
     * <p>Accessible to any authenticated caller; authentication is enforced globally by the
     * security filter chain ({@code anyRequest().authenticated()}), so no method-level
     * authorization guard is required here.</p>
     *
     * @return HTTP&nbsp;200 with a {@link MenuResponse} whose {@code menuType} is {@code "MAIN"}
     *         and whose {@code options} are the 10 immutable {@link #MAIN_OPTIONS}
     */
    @GetMapping("/menu")
    public ResponseEntity<MenuResponse> mainMenu() {
        return ResponseEntity.ok(new MenuResponse("MAIN", MAIN_OPTIONS));
    }

    /**
     * Returns the administrator menu &mdash; the REST re-expression of {@code COADM01C}
     * (transaction {@code CA00}).
     *
     * <p>Restricted to {@code ROLE_ADMIN} via {@link PreAuthorize}. A non-admin (or unauthenticated)
     * caller is denied: an unauthenticated request yields HTTP&nbsp;401, while an authenticated
     * non-admin request raises an {@code AccessDeniedException} that the {@code GlobalExceptionHandler}
     * translates into HTTP&nbsp;403.</p>
     *
     * @return HTTP&nbsp;200 with a {@link MenuResponse} whose {@code menuType} is {@code "ADMIN"}
     *         and whose {@code options} are the 4 immutable {@link #ADMIN_OPTIONS}
     */
    @GetMapping("/admin/menu")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MenuResponse> adminMenu() {
        return ResponseEntity.ok(new MenuResponse("ADMIN", ADMIN_OPTIONS));
    }
}
