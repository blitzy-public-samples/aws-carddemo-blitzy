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
import com.aws.carddemo.dto.screen.MainMenuScreen;
import com.aws.carddemo.service.online.MainMenuService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>standard-user main menu</strong>, migrated from
 * the legacy CICS COBOL program {@code COMEN01C} (CICS transaction {@code CM00}, BMS mapset {@code
 * COMEN01}; source {@code legacy/app/cbl/COMEN01C.cbl} + {@code legacy/app/bms/COMEN01.bms}).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state (Agent Action Plan
 * &sect;0.6.5). All menu business logic &mdash; option-label rendering, selection validation,
 * per-option role gating, and the option-number&rarr;program-name dispatch &mdash; is delegated to
 * {@link MainMenuService}, which reproduces the {@code COMEN01C} paragraphs with 100% behavioral
 * parity (AAP &sect;0.1.1, &sect;0.7.1). The cross-cutting COMMAREA, AID/PF-key, and next-program
 * routing helpers are inherited from {@link BaseScreenController} (the modernized {@code RECEIVE} /
 * {@code SEND} / {@code EXEC CICS XCTL} equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COMEN01C MAIN-PARA} paint-vs-process split,
 * L75-110).</strong> The single COBOL transaction is split across the two idempotent HTTP verbs
 * that the {@code main-menu} screen submits:
 *
 * <ul>
 *   <li><b>{@code GET /menu}</b> &rarr; {@link #showMenu(HttpSession, Model)} is the first-entry
 *       paint. It mirrors the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch (L87-90): the program
 *       context is reset to ENTER, the service builds the menu option lines, and the screen is
 *       (re)displayed.
 *   <li><b>{@code POST /main-menu}</b> &rarr; {@link #doMenu(MainMenuScreen, String, HttpSession,
 *       Model)} is the re-entry process. It mirrors the COBOL {@code ELSE} branch that performs
 *       {@code RECEIVE-MENU-SCREEN} then {@code EVALUATE EIBAID} (L91-103): the program context is
 *       marked re-entry, the submitted attention identifier is resolved, and the service either
 *       dispatches to the selected program (the {@code XCTL} that never returns) or signals a menu
 *       redisplay.
 * </ul>
 *
 * <p><strong>Role parity.</strong> {@code COMEN01C} does not abend when a standard user selects an
 * admin-only option; it redisplays the menu with the {@code "No access - Admin Only option..."}
 * message. That guard therefore lives inside {@link MainMenuService} (it returns {@code null} to
 * redisplay), and this controller deliberately carries no {@code @PreAuthorize}: the main menu is
 * reachable by any authenticated user, exactly as the legacy {@code CM00} transaction was.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, and it does not catch
 * the {@code CardDemoException} family &mdash; unrecoverable failures propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class MainMenuController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code main-menu} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COMEN01} / {@code COMEN1A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_MAIN_MENU = "main-menu";

  /**
   * Model attribute / form-backing-object name bound by the {@code main-menu} template's {@code
   * th:object}. The template is authoritative: it binds the {@link MainMenuScreen} under {@code
   * mainMenuForm} (carrying the operator {@code option} input and the {@code errMsg} line).
   */
  private static final String ATTR_MAIN_MENU_FORM = "mainMenuForm";

  /**
   * Model attribute exposing the rendered menu-option labels that the template iterates with {@code
   * th:each="opt : ${menuOptions}"}. Sourced verbatim from {@link MainMenuScreen#getOptions()} (the
   * {@code OPTN001I}..{@code OPTN012I} lines built by {@link MainMenuService}); no adapter type is
   * introduced &mdash; the DTO is the ground-truth contract (AAP &sect;0.4.1).
   */
  private static final String ATTR_MENU_OPTIONS = "menuOptions";

  /**
   * Main-menu business-logic service migrated from {@code COMEN01C}. Injected by constructor so the
   * dependency is explicit, final, and the controller stays trivially unit-testable with a mocked
   * service.
   */
  private final MainMenuService mainMenuService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param mainMenuService the main-menu business-logic service (the migrated {@code COMEN01C}
   *     paragraphs); must not be {@code null}
   */
  public MainMenuController(MainMenuService mainMenuService) {
    this.mainMenuService = mainMenuService;
  }

  /**
   * Displays the main menu on first entry, reproducing the COBOL {@code MAIN-PARA} first-entry
   * paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code COMEN01C} L87-90).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), its program context is reset to ENTER, and the service is invoked with {@link
   * CardWorkArea.Aid#ENTER} so it builds the option labels onto a fresh {@link MainMenuScreen} and
   * returns {@code null} (paint, no dispatch). The mutated communication area is stored back to the
   * session, and the screen plus its option labels are placed on the model for the {@code
   * main-menu} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code main-menu} template renders from
   * @return the {@code main-menu} view name (the BMS {@code SEND-MENU-SCREEN} equivalent)
   */
  @GetMapping("/menu")
  public String showMenu(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    MainMenuScreen screen = new MainMenuScreen();
    mainMenuService.processMainMenu(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_MAIN_MENU_FORM, screen);
    model.addAttribute(ATTR_MENU_OPTIONS, screen.getOptions());
    return VIEW_MAIN_MENU;
  }

  /**
   * Processes a main-menu submission on re-entry, reproducing the COBOL {@code MAIN-PARA} re-entry
   * branch that performs {@code RECEIVE-MENU-SCREEN} then {@code EVALUATE EIBAID} ({@code COMEN01C}
   * L91-103).
   *
   * <p>The submitted screen is bound as the {@code mainMenuForm} object (the {@code RECEIVE}), the
   * program context is marked re-entry, and the raw {@code pfKey} request parameter is resolved to
   * a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the {@code EVALUATE
   * EIBAID} logic and returns the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps,
   *       e.g., {@code "COACTVWC"} &rarr; {@code redirect:/account-view} and the PF3 {@code
   *       "COSGN00C"} &rarr; {@code redirect:/login});
   *   <li>{@code null} &rarr; the menu is redisplayed with the screen carrying any {@code errMsg}
   *       (invalid option, admin-only block, "coming soon", or an invalid key).
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state.
   *
   * @param screen the submitted main-menu screen bound from the request form ({@code
   *     mainMenuForm}), carrying the operator's raw {@code option} selection
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the menu is redisplayed
   * @return a {@code redirect:} view name for the dispatched program, or the {@code main-menu} view
   *     name to redisplay the menu
   */
  @PostMapping("/main-menu")
  public String doMenu(
      @ModelAttribute(ATTR_MAIN_MENU_FORM) MainMenuScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = mainMenuService.processMainMenu(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A selected program (or PF3 -> COSGN00C): the XCTL equivalent. The selection is dispatched
      // to the target screen's GET endpoint via the shared program-name -> URL routing table.
      return redirectFor(next);
    }

    // Redisplay (first-entry paint already handled by GET, invalid option, admin-only block,
    // "coming soon", or invalid key): the screen carries the error/informational message.
    model.addAttribute(ATTR_MAIN_MENU_FORM, screen);
    model.addAttribute(ATTR_MENU_OPTIONS, screen.getOptions());
    return VIEW_MAIN_MENU;
  }
}
