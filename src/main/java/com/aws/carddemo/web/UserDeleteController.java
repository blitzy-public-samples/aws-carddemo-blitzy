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
import com.aws.carddemo.dto.screen.UserDeleteScreen;
import com.aws.carddemo.service.online.UserDeleteService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>Delete User</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COUSR03C} (CICS transaction {@code CU03}, BMS mapset {@code
 * COUSR03} / map {@code COUSR3A}; source {@code legacy/app/cbl/COUSR03C.cbl} + {@code
 * legacy/app/bms/COUSR03.bms}). The administrator looks up an existing user by id, reviews the
 * read-only first-name / last-name / user-type fetched from the {@code user_security} record, and
 * deletes that record (Agent Action Plan &sect;0.4.1).
 *
 * <p>The controller is a deliberately thin presentation-layer adapter. It owns only the HTTP
 * request/response and the pseudo-conversational {@link CardDemoCommarea} session state (AAP
 * &sect;0.6.5). <strong>All</strong> business logic &mdash; the empty-user-id validation, the
 * {@code USRSEC} lookup, the read-for-update then delete, the PF-key state machine, and every
 * user-facing message &mdash; is delegated to {@link UserDeleteService}, which reproduces the
 * {@code COUSR03C} paragraphs with 100% behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). That
 * service method is {@code @Transactional}, supplying the all-or-nothing re-read-then-delete that
 * the COBOL achieved with the CICS held-for-update lock. The cross-cutting COMMAREA, AID/PF-key,
 * and next-program routing helpers are inherited from {@link BaseScreenController} (the modernized
 * {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL} equivalents).
 *
 * <p><strong>Admin-only gating (AAP &sect;0.6.5, &sect;0.7.2).</strong> {@code CU03} is reachable
 * only by an administrator ({@code CDEMO-USRTYP-ADMIN}). The class-level {@link
 * PreAuthorize @PreAuthorize("hasRole('ADMIN')")} gates <em>every</em> handler on this controller
 * (method security is enabled by {@code @EnableMethodSecurity} in {@code config.SecurityConfig}),
 * so a non-admin principal is denied with HTTP 403 before any handler body runs. This is the
 * guaranteed gate and is independent of URL-pattern coverage; {@code SecurityConfig} additionally
 * maps {@code /user-delete} to {@code hasRole("ADMIN")} (URL rule) and {@link UserDeleteService}
 * carries its own {@code @PreAuthorize} plus an explicit {@code CardDemoCommarea.isAdmin()} guard
 * &mdash; three layers of defense in depth.
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COUSR03C MAIN-PARA} paint-vs-process split,
 * L82-137).</strong> The single COBOL transaction is split across the two idempotent HTTP verbs
 * that the {@code user-delete} screen submits, both bound to the same {@code /user-delete} path:
 *
 * <ul>
 *   <li><b>{@code GET /user-delete}</b> &rarr; {@link #showUserDelete(HttpSession, Model)} is the
 *       first-entry paint ({@code IF NOT CDEMO-PGM-REENTER}, L95-105). The program context is reset
 *       to ENTER and the service is invoked with {@link CardWorkArea.Aid#ENTER}. When the operator
 *       arrived from the user-list screen, the selected user id is handed off as a flash attribute
 *       (the {@code CDEMO-CU03-USR-SELECTED} hand-off): it is consumed onto {@code usrIdIn} so the
 *       service reads and displays that user read-only for confirmation; otherwise the service
 *       paints an empty form prompting for a user id.
 *   <li><b>{@code POST /user-delete}</b> &rarr; {@link #doUserDelete(UserDeleteScreen, String,
 *       HttpSession, Model)} is the re-entry process ({@code EVALUATE EIBAID}, L106-130). The
 *       program context is marked re-entry, the submitted attention identifier is resolved, and the
 *       service runs the full state machine.
 * </ul>
 *
 * <p><strong>PF-key semantics live in the service.</strong> The controller does <em>not</em>
 * special-case any key: it resolves the raw {@code pfKey} request parameter to a {@link
 * CardWorkArea.Aid} and passes it straight through, then either translates a returned next-program
 * name into a redirect (the {@code EXEC CICS XCTL} equivalent) or re-renders the screen when the
 * service returns {@code null}. The service maps the keys as follows: {@code ENTER} looks up and
 * populates the read-only confirmation fields; {@code PF5} deletes the user and stays on the screen
 * (returns {@code null}); {@code PF4} clears the screen (returns {@code null}); {@code PF3} and
 * {@code PF12} return to the admin menu ({@code "COADM01C"} &rarr; {@code redirect:/admin}).
 *
 * <p><strong>Key difference from Update User ({@code COUSR02C}).</strong> Here {@code PF3} returns
 * to the admin menu <em>without</em> deleting the record &mdash; only {@code PF5} deletes &mdash;
 * whereas the sibling update screen's {@code PF3} performs the update first. This difference, and
 * the absence of any password handling (the delete flow never reads or writes a credential, so
 * {@link UserDeleteService} injects {@code UserSecurityRepository} only and no {@code
 * PasswordEncoder}), are preserved exactly for behavioral parity.
 *
 * <p><strong>Screen contract.</strong> The {@code user-delete} template binds the authoritative
 * {@link UserDeleteScreen} (the migrated {@code COUSR03}/{@code COUSR03.CPY} field set: the {@code
 * usrIdIn} lookup key plus the read-only {@code fName} / {@code lName} / {@code usrType} display
 * fields, the screen-header chrome, and the {@code errMsg} message line &mdash; <em>no</em>
 * password field) as the form-backing object {@value #ATTR_USER_DELETE_FORM}. The DTO is the
 * ground-truth contract (AAP &sect;0.4.1); no adapter type is introduced.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, Spring Security's {@link
 * PreAuthorize}, and the servlet {@link HttpSession}. It references no domain, repository, or
 * persistence types, and it does not catch the {@code CardDemoException} family &mdash;
 * unrecoverable failures (and the admin-gate {@code AuthorizationException}) propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 *
 * @see com.aws.carddemo.service.online.UserDeleteService
 * @see com.aws.carddemo.dto.screen.UserDeleteScreen
 * @see BaseScreenController
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class UserDeleteController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code user-delete} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COUSR03} / {@code COUSR3A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_USER_DELETE = "user-delete";

  /**
   * Model attribute / form-backing-object name bound by the {@code user-delete} template's {@code
   * th:object}. The template is authoritative: it binds the {@link UserDeleteScreen} under {@code
   * userDeleteForm}, carrying the {@code usrIdIn} lookup key, the read-only {@code fName} / {@code
   * lName} / {@code usrType} confirmation fields, and the {@code errMsg} message line.
   */
  private static final String ATTR_USER_DELETE_FORM = "userDeleteForm";

  /**
   * Flash-attribute key under which the user-list screen ({@code COUSR00C}) hands off the row the
   * operator selected, reproducing the legacy {@code CDEMO-CU03-USR-SELECTED} hand-off (COUSR03C
   * L99-104). When present on the first-entry {@code GET}, its value pre-fills {@code usrIdIn} so
   * the service immediately reads and displays that user for delete confirmation.
   */
  private static final String ATTR_SELECTED_USER_ID = "selectedUserId";

  /**
   * Delete-user business-logic service migrated from {@code COUSR03C}. Injected by constructor so
   * the dependency is explicit, {@code final}, and the controller stays trivially unit-testable
   * with a mocked service.
   */
  private final UserDeleteService userDeleteService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param userDeleteService the delete-user business-logic service (the migrated {@code COUSR03C}
   *     paragraphs, including the {@code USRSEC} lookup and the read-for-update then delete); must
   *     not be {@code null}
   */
  public UserDeleteController(UserDeleteService userDeleteService) {
    this.userDeleteService = userDeleteService;
  }

  /**
   * Displays the Delete User screen on first entry, reproducing the COBOL {@code MAIN-PARA}
   * first-entry paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code COUSR03C} L95-105).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry) and its program context is reset to ENTER. A fresh {@link UserDeleteScreen} is
   * built; if the operator arrived from the user-list screen, the selected user id was handed off
   * as the {@value #ATTR_SELECTED_USER_ID} flash attribute (the {@code CDEMO-CU03-USR-SELECTED}
   * hand-off, {@code COUSR03C} L99-104) and is consumed onto {@code usrIdIn}. The service is then
   * invoked with {@link CardWorkArea.Aid#ENTER}: when {@code usrIdIn} is populated it reads and
   * displays that user read-only for confirmation, otherwise it paints the blank "enter user id"
   * prompt; either way it returns {@code null} (paint, no transfer). The mutated communication area
   * is stored back to the session, and the screen is placed on the model for the {@code
   * user-delete} template.
   *
   * <p>Access is gated by the class-level {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")}: a
   * non-admin principal never reaches this method.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code user-delete} template renders from; also the
   *     carrier of the {@value #ATTR_SELECTED_USER_ID} flash hand-off from the user-list screen
   * @return the {@code user-delete} view name (the BMS {@code SEND MAP} equivalent)
   */
  @GetMapping("/user-delete")
  public String showUserDelete(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    UserDeleteScreen screen = new UserDeleteScreen();
    // Consume the flash hand-off from the user-list selection (CDEMO-CU03-USR-SELECTED, L99-104):
    // when present, pre-fill the lookup key so the service displays that user read-only on entry.
    if (model.containsAttribute(ATTR_SELECTED_USER_ID)) {
      screen.setUsrIdIn((String) model.getAttribute(ATTR_SELECTED_USER_ID));
    }

    userDeleteService.processUserDelete(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_USER_DELETE_FORM, screen);
    return VIEW_USER_DELETE;
  }

  /**
   * Processes a Delete User submission on re-entry, reproducing the COBOL {@code MAIN-PARA}
   * re-entry path that performs {@code RECEIVE} then {@code EVALUATE EIBAID} ({@code COUSR03C}
   * L106-130).
   *
   * <p>The submitted screen is bound as the {@value #ATTR_USER_DELETE_FORM} object (the {@code
   * RECEIVE}), the program context is marked re-entry, and the raw {@code pfKey} request parameter
   * is resolved to a {@link CardWorkArea.Aid} (ENTER by default). The service then runs the full
   * state machine &mdash; ENTER looks up and populates the read-only confirmation fields, PF5
   * deletes the user and stays, PF4 clears the screen, and PF3/PF12 return to the admin menu
   * &mdash; and returns the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps
   *       the PF3/PF12 {@code "COADM01C"} &rarr; {@code redirect:/admin});
   *   <li>{@code null} &rarr; the screen is redisplayed carrying the populated confirmation fields
   *       and any message (the empty-user-id error, the {@code USER ID NOT found} message, the
   *       neutral "Press PF5 to delete" confirmation prompt, the green delete-success message, or
   *       the cleared screen after PF4).
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state. The controller passes
   * the resolved AID straight through and does not special-case PF5/PF3/PF12 beyond translating the
   * returned program name. Access is gated by the class-level {@link
   * PreAuthorize @PreAuthorize("hasRole('ADMIN')")}.
   *
   * @param screen the submitted delete-user screen bound from the request form ({@value
   *     #ATTR_USER_DELETE_FORM}), carrying the operator's {@code usrIdIn} lookup key
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF5} / {@code PF4} / {@code PF3} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the transferred program, or the {@code user-delete}
   *     view name to redisplay the screen
   */
  @PostMapping("/user-delete")
  public String doUserDelete(
      @ModelAttribute(ATTR_USER_DELETE_FORM) UserDeleteScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = userDeleteService.processUserDelete(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A returned program name (PF3/PF12 -> COADM01C): the XCTL equivalent, dispatched to the
      // target screen's GET endpoint via the shared program-name -> URL routing table.
      return redirectFor(next);
    }

    // Redisplay: the ENTER lookup result, the PF5 delete-success confirmation, the empty-id or
    // not-found validation message, or the PF4-cleared screen all travel on the bound screen.
    model.addAttribute(ATTR_USER_DELETE_FORM, screen);
    return VIEW_USER_DELETE;
  }
}
