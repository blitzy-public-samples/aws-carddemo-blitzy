/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.web;

import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.AdminMenuScreen;
import com.aws.carddemo.service.online.AdminMenuService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>administrator menu</strong>, migrated from the
 * legacy CICS COBOL program {@code COADM01C} (CICS transaction {@code CA00}, BMS mapset {@code
 * COADM01}; source {@code legacy/app/cbl/COADM01C.cbl} + {@code legacy/app/bms/COADM01.bms}).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state (Agent Action Plan
 * &sect;0.6.5). All menu business logic &mdash; option-label rendering, selection validation, and
 * the option-number&rarr;program-name dispatch &mdash; is delegated to {@link AdminMenuService},
 * which reproduces the {@code COADM01C} paragraphs with 100% behavioral parity (AAP &sect;0.1.1,
 * &sect;0.7.1). The cross-cutting COMMAREA, AID/PF-key, and next-program routing helpers are
 * inherited from {@link BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} /
 * {@code EXEC CICS XCTL} equivalents).
 *
 * <p><strong>Admin-only gating (AAP &sect;0.6.5).</strong> Unlike its standard-user twin {@code
 * MainMenuController} (program {@code COMEN01C}), the admin menu is gated <em>as a whole</em>: in
 * the legacy application {@code COADM01C} is reachable only by an administrator because {@code
 * COSGN00C} routes a user whose {@code CDEMO-USER-TYPE} is {@code CDEMO-USRTYP-ADMIN} ({@code 'A'})
 * here. That invariant is enforced in three layers of defense in depth:
 *
 * <ol>
 *   <li><b>Primary &mdash; class-level {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")}.</b>
 *       Method security (enabled by {@code @EnableMethodSecurity(prePostEnabled = true)} in {@code
 *       config.SecurityConfig}) gates <em>every</em> handler on this controller, so a non-admin
 *       authenticated principal is denied with HTTP 403 before any handler body runs. This is the
 *       guaranteed gate and is independent of URL-pattern coverage.
 *   <li><b>URL rule.</b> {@code SecurityConfig} additionally maps {@code /admin/**} to {@code
 *       hasRole("ADMIN")}. That pattern covers the {@code GET /admin} display endpoint; it does
 *       <em>not</em> match the sibling {@code POST /admin-menu} path (a distinct path segment),
 *       which is precisely why the class-level {@code @PreAuthorize} above is required to gate the
 *       process endpoint as well.
 *   <li><b>Service entry guard.</b> {@link AdminMenuService#processAdminMenu(AdminMenuScreen,
 *       CardDemoCommarea, CardWorkArea.Aid)} independently rejects a non-admin {@link
 *       CardDemoCommarea} with an {@code AuthorizationException} (also HTTP 403), so the invariant
 *       holds even when the service is exercised directly by unit tests without a security proxy.
 * </ol>
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COADM01C MAIN-PARA} paint-vs-process split,
 * L75-110).</strong> The single COBOL transaction is split across the two idempotent HTTP verbs
 * that the {@code admin-menu} screen submits:
 *
 * <ul>
 *   <li><b>{@code GET /admin}</b> &rarr; {@link #showAdminMenu(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch (L87-90):
 *       the program context is reset to ENTER, the service builds the admin option lines, and the
 *       screen is (re)displayed.
 *   <li><b>{@code POST /admin-menu}</b> &rarr; {@link #doAdminMenu(AdminMenuScreen, String,
 *       HttpSession, Model)} is the re-entry process. It mirrors the COBOL {@code ELSE} branch that
 *       performs {@code RECEIVE-MENU-SCREEN} then {@code EVALUATE EIBAID} (L91-103): the program
 *       context is marked re-entry, the submitted attention identifier is resolved, and the service
 *       either dispatches to the selected user-management program (the {@code XCTL} that never
 *       returns) or signals a menu redisplay.
 * </ul>
 *
 * <p><strong>Template &harr; DTO contract.</strong> The {@code admin-menu} template binds the
 * authoritative {@link AdminMenuScreen} under {@code adminMenuForm} (its {@code th:object}) and
 * iterates the rendered option labels under {@code adminMenuOptions} ({@code th:each="opt :
 * ${adminMenuOptions}"}). The option labels are sourced verbatim from {@link
 * AdminMenuScreen#getOptions()} (the {@code OPTN001I}..{@code OPTN012I} lines built by {@link
 * AdminMenuService}); no adapter type is introduced &mdash; the DTO is the ground-truth contract
 * (AAP &sect;0.4.1). The admin menu's "coming soon" message is name-less (distinct from the main
 * menu); that detail is handled inside {@link AdminMenuService} and the controller simply
 * redisplays on a {@code null} return.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, Spring Security's {@link
 * PreAuthorize}, and the servlet {@link HttpSession}. It references no domain, repository, or
 * persistence types, and it does not catch the {@code CardDemoException} family &mdash;
 * unrecoverable failures (and the admin-gate {@code AuthorizationException}) propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class AdminMenuController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code admin-menu} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COADM01} / {@code COADM1A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_ADMIN_MENU = "admin-menu";

  /**
   * Model attribute / form-backing-object name bound by the {@code admin-menu} template's {@code
   * th:object}. The template is authoritative: it binds the {@link AdminMenuScreen} under {@code
   * adminMenuForm} (carrying the operator {@code option} input and the {@code errMsg} line).
   */
  private static final String ATTR_ADMIN_MENU_FORM = "adminMenuForm";

  /**
   * Model attribute exposing the rendered admin-menu option labels that the template iterates with
   * {@code th:each="opt : ${adminMenuOptions}"}. Sourced verbatim from {@link
   * AdminMenuScreen#getOptions()} (the {@code OPTN001I}..{@code OPTN012I} lines built by {@link
   * AdminMenuService}); no adapter type is introduced &mdash; the DTO is the ground-truth contract
   * (AAP &sect;0.4.1).
   */
  private static final String ATTR_ADMIN_MENU_OPTIONS = "adminMenuOptions";

  /**
   * Admin-menu business-logic service migrated from {@code COADM01C}. Injected by constructor so
   * the dependency is explicit, final, and the controller stays trivially unit-testable with a
   * mocked service.
   */
  private final AdminMenuService adminMenuService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param adminMenuService the admin-menu business-logic service (the migrated {@code COADM01C}
   *     paragraphs); must not be {@code null}
   */
  public AdminMenuController(AdminMenuService adminMenuService) {
    this.adminMenuService = adminMenuService;
  }

  /**
   * Displays the admin menu on first entry, reproducing the COBOL {@code MAIN-PARA} first-entry
   * paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code COADM01C} L87-90).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), its program context is reset to ENTER, and the service is invoked with {@link
   * CardWorkArea.Aid#ENTER} so it builds the option labels onto a fresh {@link AdminMenuScreen} and
   * returns {@code null} (paint, no dispatch). The mutated communication area is stored back to the
   * session, and the screen plus its option labels are placed on the model for the {@code
   * admin-menu} template.
   *
   * <p>Access is gated by the class-level {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")}: a
   * non-admin principal never reaches this method.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code admin-menu} template renders from
   * @return the {@code admin-menu} view name (the BMS {@code SEND-MENU-SCREEN} equivalent)
   */
  @GetMapping("/admin")
  public String showAdminMenu(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    AdminMenuScreen screen = new AdminMenuScreen();
    adminMenuService.processAdminMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_ADMIN_MENU_FORM, screen);
    model.addAttribute(ATTR_ADMIN_MENU_OPTIONS, screen.getOptions());
    return VIEW_ADMIN_MENU;
  }

  /**
   * Processes an admin-menu submission on re-entry, reproducing the COBOL {@code MAIN-PARA}
   * re-entry branch that performs {@code RECEIVE-MENU-SCREEN} then {@code EVALUATE EIBAID} ({@code
   * COADM01C} L91-103).
   *
   * <p>The submitted screen is bound as the {@code adminMenuForm} object (the {@code RECEIVE}), the
   * program context is marked re-entry, and the raw {@code pfKey} request parameter is resolved to
   * a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the {@code EVALUATE
   * EIBAID} logic and returns the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps,
   *       e.g., {@code "COUSR00C"} &rarr; {@code redirect:/user-list}, {@code "COUSR01C"} &rarr;
   *       {@code redirect:/user-add}, {@code "COUSR02C"} &rarr; {@code redirect:/user-update},
   *       {@code "COUSR03C"} &rarr; {@code redirect:/user-delete}, and the PF3 {@code "COSGN00C"}
   *       &rarr; {@code redirect:/login});
   *   <li>{@code null} &rarr; the menu is redisplayed with the screen carrying any {@code errMsg}
   *       (invalid option, "coming soon", or an invalid key).
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state. Access is gated by
   * the class-level {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")}, which is the guaranteed
   * gate for this {@code /admin-menu} endpoint (the {@code SecurityConfig} {@code /admin/**} URL
   * rule does not cover this distinct path segment).
   *
   * @param screen the submitted admin-menu screen bound from the request form ({@code
   *     adminMenuForm}), carrying the operator's raw {@code option} selection
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the menu is redisplayed
   * @return a {@code redirect:} view name for the dispatched program, or the {@code admin-menu}
   *     view name to redisplay the menu
   */
  @PostMapping("/admin-menu")
  public String doAdminMenu(
      @ModelAttribute(ATTR_ADMIN_MENU_FORM) AdminMenuScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = adminMenuService.processAdminMenu(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A selected user-management program (or PF3 -> COSGN00C): the XCTL equivalent. The selection
      // is dispatched to the target screen's GET endpoint via the shared program-name -> URL table.
      return redirectFor(next);
    }

    // Redisplay (invalid option, "coming soon", or invalid key): the screen carries the
    // error/informational message and the rebuilt option labels.
    model.addAttribute(ATTR_ADMIN_MENU_FORM, screen);
    model.addAttribute(ATTR_ADMIN_MENU_OPTIONS, screen.getOptions());
    return VIEW_ADMIN_MENU;
  }
}
