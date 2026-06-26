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
import com.aws.carddemo.dto.screen.UserAddScreen;
import com.aws.carddemo.service.online.UserAddService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>Add User</strong> screen, migrated from the legacy
 * CICS COBOL program {@code COUSR01C} (CICS transaction {@code CU01}, BMS mapset {@code COUSR01};
 * source {@code legacy/app/cbl/COUSR01C.cbl} + {@code legacy/app/bms/COUSR01.bms}).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state (Agent Action Plan
 * &sect;0.6.5). All business logic &mdash; validating the five new-user fields in the exact COBOL
 * order (First&rarr;Last&rarr;Id&rarr;Password&rarr;Type), pre-checking existence ({@code
 * existsById}) to reproduce the legacy duplicate-key branch (AAP &sect;0.6.4), and inserting the
 * user with a BCrypt-hashed password (the deliberate credential-hygiene deviation, AAP &sect;0.6.6)
 * &mdash; is delegated to the {@code @Transactional} {@link UserAddService}, which reproduces the
 * {@code COUSR01C} paragraphs with 100% behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The
 * cross-cutting COMMAREA, AID/PF-key, and next-program routing helpers are inherited from {@link
 * BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL}
 * equivalents).
 *
 * <p><strong>Admin-only gate (AAP &sect;0.6.5).</strong> In the legacy application {@code COUSR01C}
 * is reachable only from the administrator menu ({@code COADM01C}). The class-level {@link
 * PreAuthorize @PreAuthorize("hasRole('ADMIN')")} enforces that gate at the Spring method-security
 * proxy boundary for both the GET paint and the POST process, so a standard user can never reach
 * the add-user screen. The {@link UserAddService} additionally re-checks {@link
 * CardDemoCommarea#isAdmin()} as defense-in-depth.
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COUSR01C MAIN-PARA} paint-vs-process split,
 * L71-110).</strong> The single COBOL transaction is split across the two HTTP verbs that the
 * {@code user-add} screen submits:
 *
 * <ul>
 *   <li><b>{@code GET /user-add}</b> &rarr; {@link #showUserAdd(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch (L84-89):
 *       the program context is reset to ENTER and the service paints a fresh, empty {@link
 *       UserAddScreen}.
 *   <li><b>{@code POST /user-add}</b> &rarr; {@link #doUserAdd(UserAddScreen, String, HttpSession,
 *       Model)} is the re-entry process. It mirrors the COBOL {@code ELSE} branch that performs
 *       {@code RECEIVE-USRADD-SCREEN} then {@code EVALUATE EIBAID} (L90-104): the program context
 *       is marked re-entry, the submitted attention identifier is resolved, and the service
 *       performs the ENTER (validate-and-add) / PF3 (return to {@code COADM01C}) / PF4 ({@code
 *       CLEAR-CURRENT-SCREEN}) / "other key" dispatch, returning either the next program to
 *       transfer to or {@code null} to redisplay.
 * </ul>
 *
 * <p><strong>No PF-key branching in the controller.</strong> The COBOL {@code EVALUATE EIBAID}
 * actions all live inside {@link UserAddService} and are selected from the resolved {@link
 * CardWorkArea.Aid}. This controller deliberately does not branch on PF keys; it only resolves the
 * key and forwards it, then either issues the redirect for the returned program (PF3 &rarr; {@code
 * COADM01C} &rarr; {@code /admin}) or redisplays the screen.
 *
 * <p><strong>Credential hygiene (AAP &sect;0.6.6).</strong> The controller never hashes, logs,
 * echoes, or otherwise inspects the operator's password; it merely transports the {@link
 * UserAddScreen#getPasswd() passwd} field, which the {@code user-add} template renders masked. The
 * one-way BCrypt hashing and persistence are owned entirely by {@link UserAddService}.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, the Spring Security
 * {@link PreAuthorize} annotation, and the servlet {@link HttpSession}. It references no domain,
 * repository, or persistence types, and it does not catch the {@code CardDemoException} family
 * &mdash; unrecoverable failures propagate to the application-wide exception handler (the
 * modernized abend path, AAP &sect;0.6.6). There is no {@code RedirectAttributes} hand-off: the
 * only forward transfer is the PF3 redirect to the admin menu.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class UserAddController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code user-add} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COUSR01} / {@code COUSR1A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_USER_ADD = "user-add";

  /**
   * Model attribute / form-backing-object name bound by the {@code user-add} template's {@code
   * th:object}. The {@link UserAddScreen} DTO is the ground-truth contract (AAP &sect;0.4.1): it
   * carries the operator-input fields ({@code fName}, {@code lName}, {@code userId}, {@code
   * passwd}, {@code usrType}) plus the {@code errMsg} message line and the screen header fields.
   * Templates may bind a friendlier {@code *{password}} alias, but the DTO field name {@code
   * passwd} is authoritative; no adapter layer is introduced.
   */
  private static final String ATTR_USER_ADD_FORM = "userAddForm";

  /**
   * Add-user business-logic service migrated from {@code COUSR01C}. Injected by constructor so the
   * dependency is explicit, final, and the controller stays trivially unit-testable with a mocked
   * service.
   */
  private final UserAddService userAddService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param userAddService the add-user business-logic service (the migrated {@code COUSR01C}
   *     paragraphs); must not be {@code null}
   */
  public UserAddController(UserAddService userAddService) {
    this.userAddService = userAddService;
  }

  /**
   * Displays the Add User screen on first entry, reproducing the COBOL {@code MAIN-PARA}
   * first-entry paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code COUSR01C} L84-89).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry) and its program context is reset to ENTER. A fresh, empty {@link UserAddScreen} is
   * allocated and the service is invoked with {@link CardWorkArea.Aid#ENTER} to paint the blank
   * add-user form (the modernized {@code SEND-USRADD-SCREEN} with {@code LOW-VALUES} and the cursor
   * on the first-name field). The mutated communication area is stored back to the session and the
   * screen is placed on the model for the {@code user-add} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code user-add} template renders from
   * @return the {@code user-add} view name (the BMS {@code SEND-USRADD-SCREEN} equivalent)
   */
  @GetMapping("/user-add")
  public String showUserAdd(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    UserAddScreen screen = new UserAddScreen();
    userAddService.processUserAdd(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_USER_ADD_FORM, screen);
    return VIEW_USER_ADD;
  }

  /**
   * Processes an Add User submission on re-entry, reproducing the COBOL {@code MAIN-PARA} re-entry
   * branch that performs {@code RECEIVE-USRADD-SCREEN} then {@code EVALUATE EIBAID} ({@code
   * COUSR01C} L90-104).
   *
   * <p>The submitted screen is bound as the {@code userAddForm} object (the {@code RECEIVE}), the
   * program context is marked re-entry, and the raw {@code pfKey} request parameter is resolved to
   * a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the {@code EVALUATE
   * EIBAID} logic &mdash; ENTER validate-and-add, PF3 return-to-admin-menu, PF4 clear, and the
   * "other key" guard &mdash; and returns the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps
   *       the PF3 {@code "COADM01C"} &rarr; {@code redirect:/admin});
   *   <li>{@code null} &rarr; the screen is redisplayed carrying any {@code errMsg} (a field
   *       validation failure, the duplicate-user-id error, an invalid-key message, the PF4 cleared
   *       form, or the green success confirmation produced by the service).
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state.
   *
   * @param screen the submitted Add User screen bound from the request form ({@code userAddForm}),
   *     carrying the operator's input fields and the message line
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / {@code PF4} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the dispatched program, or the {@code user-add} view
   *     name to redisplay the screen
   */
  @PostMapping("/user-add")
  public String doUserAdd(
      @ModelAttribute(ATTR_USER_ADD_FORM) UserAddScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = userAddService.processUserAdd(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A PF3 return target (COADM01C): the XCTL equivalent, dispatched to the admin menu's GET
      // endpoint via the shared program-name -> URL routing table (redirect:/admin).
      return redirectFor(next);
    }

    // Redisplay: the screen carries a field-validation error, the duplicate-user-id error, an
    // invalid-key message, the PF4 cleared form, or the green success confirmation.
    model.addAttribute(ATTR_USER_ADD_FORM, screen);
    return VIEW_USER_ADD;
  }
}
