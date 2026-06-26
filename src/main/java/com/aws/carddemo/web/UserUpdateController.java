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
import com.aws.carddemo.dto.screen.UserUpdateScreen;
import com.aws.carddemo.service.online.UserUpdateService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>Update User</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COUSR02C} (CICS transaction {@code CU02}, BMS mapset {@code
 * COUSR02} / map {@code COUSR2A}; source {@code legacy/app/cbl/COUSR02C.cbl} + {@code
 * legacy/app/bms/COUSR02.bms}). An administrator looks up a user by id, edits the name, password,
 * and user type, and commits the change (Agent Action Plan &sect;0.4.1).
 *
 * <p><strong>Admin-only (AAP &sect;0.6.5).</strong> {@code CU02} is one of the user-management
 * transactions reachable only from the admin menu. The gate is enforced declaratively by the
 * class-level {@link PreAuthorize}{@code ("hasRole('ADMIN')")} (the Spring Security equivalent of
 * the legacy {@code CDEMO-USRTYP-ADMIN} check) and again behaviorally in the service, which raises
 * {@code AuthorizationException} for a non-admin COMMAREA. A standard user can never reach this
 * screen.
 *
 * <p>The controller is a deliberately thin presentation-layer adapter. It owns only the HTTP
 * request/response and the pseudo-conversational {@link CardDemoCommarea} session state (AAP
 * &sect;0.6.5). <strong>All</strong> business logic &mdash; the user lookup, the field validation,
 * the per-field modify detection, the optimistic read-compare-rewrite update, the PF-key state
 * machine, and every user-facing message &mdash; is delegated to {@link UserUpdateService}, which
 * reproduces the {@code COUSR02C} paragraphs with 100% behavioral parity (AAP &sect;0.1.1,
 * &sect;0.7.1). The service method is {@code @Transactional}, supplying the read-for-update &rarr;
 * rewrite atomicity that the COBOL achieved with its {@code READ … UPDATE} / {@code REWRITE} pair.
 * The cross-cutting COMMAREA, AID/PF-key, and next-program routing helpers are inherited from
 * {@link BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} / {@code EXEC CICS
 * XCTL} equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COUSR02C MAIN-PARA} paint-vs-process split,
 * L82-138).</strong> The single COBOL transaction is split across the two idempotent HTTP verbs
 * that the {@code user-update} screen submits:
 *
 * <ul>
 *   <li><b>{@code GET /user-update}</b> &rarr; {@link #showUserUpdate(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL fresh-entry branch ({@code IF NOT
 *       CDEMO-PGM-REENTER}): the program context is reset to ENTER and the service is invoked with
 *       {@link CardWorkArea.Aid#ENTER}. When a user id was handed off from the user-list screen
 *       (see below) it is pre-filled first, so the service immediately reads and displays that user
 *       on the very first paint; otherwise an empty form is painted to type a user id.
 *   <li><b>{@code POST /user-update}</b> &rarr; {@link #doUserUpdate(UserUpdateScreen, String,
 *       HttpSession, Model)} is the re-entry process. It mirrors the COBOL re-entry path that
 *       performs {@code RECEIVE} then {@code EVALUATE EIBAID}: the program context is marked
 *       re-entry, the submitted attention identifier is resolved, and the service runs the full
 *       state machine.
 * </ul>
 *
 * <p><strong>User-list handoff (CDEMO-CU02-USR-SELECTED).</strong> The legacy program, on first
 * entry, copies {@code CDEMO-CU02-USR-SELECTED} (set by the user-list screen {@code COUSR00C} when
 * the operator selects a row) into the user-id field and performs the lookup. The modernized
 * user-list controller flashes that id as the {@value #FLASH_SELECTED_USER_ID} attribute across the
 * redirect; {@link #showUserUpdate(HttpSession, Model)} consumes it from the {@link Model} and
 * pre-fills {@code usrIdIn} so the populated form appears immediately &mdash; reproducing the COBOL
 * pre-fill-and-lookup behavior.
 *
 * <p><strong>PF-key semantics live in the service.</strong> ENTER performs the lookup, PF5 commits
 * the update and stays on the screen, PF4 clears the screen, PF3 commits the update and then
 * returns to the admin menu, and PF12 returns to the admin menu without updating. The controller
 * does <em>not</em> special-case those keys: it resolves the raw {@code pfKey} request parameter to
 * a {@link CardWorkArea.Aid} and passes it through, then either translates a returned next-program
 * name into a redirect (the {@code EXEC CICS XCTL} equivalent &mdash; PF3/PF12 return {@code
 * "COADM01C"} &rarr; {@code redirect:/admin}) or re-renders the screen when the service returns
 * {@code null} (the ENTER lookup result, validation errors, the PF5 update confirmation, or the PF4
 * cleared form all flow through this redisplay path).
 *
 * <p><strong>Credential hygiene (AAP &sect;0.6.6).</strong> The password is sensitive and is never
 * logged or echoed by this controller. A blank password on update means "keep the existing
 * password"; a non-blank password is BCrypt-hashed by the service before persistence. The bound
 * {@link UserUpdateScreen#getPasswd() passwd} field is rendered masked in the view.
 *
 * <p><strong>Screen contract.</strong> The {@code user-update} template binds the authoritative
 * {@link UserUpdateScreen} (the migrated {@code COUSR02}/{@code COUSR02.CPY} field set: {@code
 * usrIdIn} X8, {@code fName} X20, {@code lName} X20, {@code passwd} X8, {@code usrType} X1, the
 * {@code errMsg} X78 message line, and the header chrome) as the form-backing object {@value
 * #ATTR_USER_UPDATE_FORM}. The DTO is the ground-truth contract (AAP &sect;0.4.1); no adapter type
 * is introduced.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, Spring Security's {@link PreAuthorize}, the Spring web
 * stereotypes/annotations, and the servlet {@link HttpSession}. It references no domain,
 * repository, or persistence types, performs no arithmetic, and does not catch the {@code
 * CardDemoException} family &mdash; unrecoverable failures (and the admin-gate {@code
 * AuthorizationException}) propagate to the application-wide exception handler (the modernized
 * abend path, AAP &sect;0.6.6).
 *
 * @see com.aws.carddemo.service.online.UserUpdateService
 * @see com.aws.carddemo.dto.screen.UserUpdateScreen
 * @see BaseScreenController
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class UserUpdateController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code user-update} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COUSR02} / {@code COUSR2A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_USER_UPDATE = "user-update";

  /**
   * Model attribute / form-backing-object name bound by the {@code user-update} template's {@code
   * th:object}. The template is authoritative: it binds the {@link UserUpdateScreen} under {@code
   * userUpdateForm}, carrying the user-id lookup key, the editable name/password/type fields, and
   * the {@code errMsg} message line.
   */
  private static final String ATTR_USER_UPDATE_FORM = "userUpdateForm";

  /**
   * Flash-attribute key carrying the user id selected on the user-list screen ({@code COUSR00C}).
   * It is the modernized form of the legacy {@code CDEMO-CU02-USR-SELECTED} handoff: when present
   * in the {@link Model} on first paint, {@code usrIdIn} is pre-filled and the service displays
   * that user immediately.
   */
  private static final String FLASH_SELECTED_USER_ID = "selectedUserId";

  /**
   * Update-user business-logic service migrated from {@code COUSR02C}. Injected by constructor so
   * the dependency is explicit, final, and the controller stays trivially unit-testable with a
   * mocked service.
   */
  private final UserUpdateService userUpdateService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param userUpdateService the update-user business-logic service (the migrated {@code COUSR02C}
   *     paragraphs, including the lookup, validation, and optimistic read-compare-rewrite update);
   *     must not be {@code null}
   */
  public UserUpdateController(UserUpdateService userUpdateService) {
    this.userUpdateService = userUpdateService;
  }

  /**
   * Displays the Update User screen on first entry, reproducing the COBOL {@code MAIN-PARA}
   * fresh-entry paint ({@code COUSR02C} L94-106).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry) and its program context is reset to ENTER. A fresh {@link UserUpdateScreen} is
   * built, and the user-list handoff is consumed: when the {@value #FLASH_SELECTED_USER_ID} flash
   * attribute is present, its value pre-fills {@code usrIdIn} (the legacy {@code
   * CDEMO-CU02-USR-SELECTED} copy). The service is then invoked with {@link
   * CardWorkArea.Aid#ENTER}: a populated user id drives an immediate lookup so the filled form is
   * painted, while an empty id paints the blank prompt to type a user id. Either way the service
   * returns {@code null} (paint, no transfer). The mutated communication area is stored back to the
   * session, and the screen is placed on the model for the {@code user-update} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code user-update} template renders from, and the
   *     carrier of the user-list flash handoff
   * @return the {@code user-update} view name (the BMS {@code SEND MAP} equivalent)
   */
  @GetMapping("/user-update")
  public String showUserUpdate(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    UserUpdateScreen screen = new UserUpdateScreen();
    // CDEMO-CU02-USR-SELECTED handoff from the user-list screen: pre-fill the lookup key so the
    // service paints the populated form on first entry.
    if (model.containsAttribute(FLASH_SELECTED_USER_ID)) {
      screen.setUsrIdIn((String) model.getAttribute(FLASH_SELECTED_USER_ID));
    }
    userUpdateService.processUserUpdate(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_USER_UPDATE_FORM, screen);
    return VIEW_USER_UPDATE;
  }

  /**
   * Processes an Update User submission on re-entry, reproducing the COBOL {@code MAIN-PARA}
   * re-entry path that performs {@code RECEIVE-USRUPD-SCREEN} then {@code EVALUATE EIBAID} ({@code
   * COUSR02C} L107-131).
   *
   * <p>The submitted screen is bound as the {@value #ATTR_USER_UPDATE_FORM} object (the {@code
   * RECEIVE}), the program context is marked re-entry, and the raw {@code pfKey} request parameter
   * is resolved to a {@link CardWorkArea.Aid} (ENTER by default). The service then runs the full
   * state machine &mdash; ENTER looks up the user, PF5 commits the update and stays, PF4 clears the
   * screen, PF3 commits the update and returns, PF12 returns without updating &mdash; and returns
   * the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name (PF3 / PF12 &rarr; {@code "COADM01C"}) &rarr; the
   *       controller issues the Spring redirect that replaces {@code EXEC CICS XCTL} ({@link
   *       BaseScreenController#redirectFor(String)} maps {@code "COADM01C"} &rarr; {@code
   *       redirect:/admin});
   *   <li>{@code null} &rarr; the screen is redisplayed carrying the ENTER lookup result, any
   *       validation {@code errMsg}, the PF5 update confirmation / no-change message, or the PF4
   *       cleared form.
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state. The controller passes
   * the resolved AID straight through and does not special-case any key beyond translating the
   * returned program name; the password is never logged.
   *
   * @param screen the submitted update-user screen bound from the request form ({@value
   *     #ATTR_USER_UPDATE_FORM}), carrying the operator's user id and edited field values
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / {@code PF4} / {@code PF5} / {@code PF12} / ...); {@code null} or blank is treated as
   *     ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the transferred program, or the {@code user-update}
   *     view name to redisplay the screen
   */
  @PostMapping("/user-update")
  public String doUserUpdate(
      @ModelAttribute(ATTR_USER_UPDATE_FORM) UserUpdateScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = userUpdateService.processUserUpdate(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A returned program name (PF3 / PF12 -> COADM01C): the XCTL equivalent, dispatched to the
      // admin menu's GET endpoint via the shared program-name -> URL routing table.
      return redirectFor(next);
    }

    // Redisplay: the ENTER lookup result, validation errors, the PF5 update confirmation or
    // no-change message, or the PF4 cleared form all travel on the bound screen.
    model.addAttribute(ATTR_USER_UPDATE_FORM, screen);
    return VIEW_USER_UPDATE;
  }
}
