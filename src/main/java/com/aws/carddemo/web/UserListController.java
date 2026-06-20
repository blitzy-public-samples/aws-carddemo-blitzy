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
import com.aws.carddemo.dto.screen.UserListScreen;
import com.aws.carddemo.service.online.UserListService;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the CardDemo <strong>list users</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COUSR00C} (CICS transaction {@code CU00}, BMS mapset {@code
 * COUSR00}; source {@code legacy/app/cbl/COUSR00C.cbl} + {@code legacy/app/bms/COUSR00.bms}).
 *
 * <p>The screen is the administrative entry point for user maintenance. It browses the {@code
 * USRSEC} store in ascending user-id order and renders it ten rows at a time, each row carrying a
 * one-character selection flag. Marking a row with {@code U} drills into the update-user screen
 * ({@code COUSR02C}) and marking it with {@code D} drills into the delete-user screen ({@code
 * COUSR03C}); {@code PF3} returns to the admin menu ({@code COADM01C}); {@code PF7}/{@code PF8}
 * page backward/forward (Agent Action Plan &sect;0.4.1, &sect;0.6.5).
 *
 * <p><strong>Admin-only gating (AAP &sect;0.6.5, &sect;0.7.2).</strong> Transaction {@code CU00} is
 * reachable only from the admin menu because {@code COSGN00C} routes an administrator there. That
 * invariant is enforced defense-in-depth: the class-level {@link PreAuthorize} {@code
 * hasRole('ADMIN')} mirrors both the SecurityConfig URL gating and the COBOL admin restriction, and
 * the collaborating {@link UserListService} carries the same annotation plus an explicit {@link
 * CardDemoCommarea#isAdmin()} check (belt-and-suspenders).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state. All list/browse business
 * logic &mdash; the keyset paging, the ten-row page build, the row-selection scan, every
 * operator-visible message, and the {@code findAll(Sort.by(ASC, "secUsrId"))} ordering &mdash; is
 * delegated to {@link UserListService}, which reproduces the {@code COUSR00C} paragraphs with 100%
 * behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The cross-cutting COMMAREA, AID/PF-key, and
 * next-program routing helpers are inherited from {@link BaseScreenController} (the modernized
 * {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL} equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COUSR00C MAIN-PARA} paint-vs-process split,
 * L98-144).</strong> The single COBOL transaction is split across the two HTTP verbs that the
 * {@code user-list} screen submits, both bound to {@code /user-list} and dispatched by method:
 *
 * <ul>
 *   <li><b>{@code GET /user-list}</b> &rarr; {@link #showUserList(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch (L115-119):
 *       the program context is reset to ENTER and the service browses the first page of up to ten
 *       rows onto a fresh screen.
 *   <li><b>{@code POST /user-list}</b> &rarr; {@link #doUserList(UserListScreen, String,
 *       HttpSession, Model, RedirectAttributes)} is the re-entry process. It mirrors the COBOL
 *       {@code ELSE} branch that performs {@code RECEIVE-USRLST-SCREEN} then {@code EVALUATE
 *       EIBAID} (L120-138): the program context is marked re-entry, the submitted attention
 *       identifier is resolved, and the service either forwards (a row selected with {@code
 *       U}/{@code D}, or {@code PF3}) or signals a redisplay ({@code PF7}/{@code PF8} paging or a
 *       validation message).
 * </ul>
 *
 * <p><strong>Controller-mediated selection handoff (KEY INSIGHT).</strong> When a row is selected
 * the legacy program captures the chosen {@code USRIDxxI} into {@code CDEMO-CU00-USR-SELECTED}
 * &mdash; a {@code CU00}-specific COMMAREA sub-area ({@code CDEMO-CU00-INFO}) that is <em>not</em>
 * part of the shared {@link CardDemoCommarea} contract. Rather than widen the shared commarea, this
 * controller re-derives the selected user id from the bound rows (see {@link
 * #firstSelectedUserId(UserListScreen)}) and carries it to the update/delete screen as a Spring MVC
 * <em>flash attribute</em> ({@value #ATTR_SELECTED_USER_ID}), surviving exactly one redirect so
 * {@code UserUpdateController}/{@code UserDeleteController} can pre-fill their input. The
 * destination differs per selection value, but the service already encodes that in its return value
 * ({@code COUSR02C} for {@code U} versus {@code COUSR03C} for {@code D}); this controller simply
 * supplies the selected id for either target.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web/security stereotypes/annotations, and the servlet
 * {@link HttpSession}. It references no domain, repository, or persistence types, and it does not
 * catch the {@code CardDemoException} family &mdash; unrecoverable failures propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class UserListController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code user-list} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COUSR00} / {@code COUSR0A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_USER_LIST = "user-list";

  /**
   * Model attribute / form-backing-object name bound by the {@code user-list} template's {@code
   * th:object}. The authoritative {@link UserListScreen} is bound under {@code userListForm}; the
   * template may iterate a friendlier alias for the rows, but the DTO field names ({@code rows},
   * {@code sel}, {@code usrId}, {@code fName}, {@code lName}, {@code uType}) are the ground-truth
   * contract (AAP &sect;0.4.1) &mdash; no adapter type is introduced.
   */
  private static final String ATTR_USER_LIST_FORM = "userListForm";

  /**
   * Flash-attribute key under which the selected user id is handed to the update/delete user screen
   * across the post/redirect/get boundary. This is the modernized home of the legacy {@code
   * CDEMO-CU00-USR-SELECTED} value (see the class-level KEY INSIGHT note).
   */
  private static final String ATTR_SELECTED_USER_ID = "selectedUserId";

  /**
   * Update-user program name returned by {@link UserListService} when a row is selected with {@code
   * U}/{@code u}; matched to trigger the controller-mediated flash handoff. COBOL {@code MOVE
   * 'COUSR02C' TO CDEMO-TO-PROGRAM} (COUSR00C L192).
   */
  private static final String PGM_USER_UPDATE = "COUSR02C";

  /**
   * Delete-user program name returned by {@link UserListService} when a row is selected with {@code
   * D}/{@code d}; matched to trigger the controller-mediated flash handoff. COBOL {@code MOVE
   * 'COUSR03C' TO CDEMO-TO-PROGRAM} (COUSR00C L202).
   */
  private static final String PGM_USER_DELETE = "COUSR03C";

  /**
   * List-users business-logic service migrated from {@code COUSR00C}. Injected by constructor so
   * the dependency is explicit, final, and the controller stays trivially unit-testable with a
   * mocked service.
   */
  private final UserListService userListService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param userListService the list-users business-logic service (the migrated {@code COUSR00C}
   *     paragraphs); must not be {@code null}
   */
  public UserListController(UserListService userListService) {
    this.userListService = userListService;
  }

  /**
   * Displays the user list on first entry, reproducing the COBOL {@code MAIN-PARA} first-entry
   * paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code COUSR00C} L115-119).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), its program context is reset to ENTER, and the service is invoked with {@link
   * CardWorkArea.Aid#ENTER} so it browses the first page of up to ten users onto a fresh {@link
   * UserListScreen} (the ascending-by-user-id ordering) and returns {@code null} (paint, no
   * dispatch). The mutated communication area is stored back to the session, and the screen is
   * placed on the model for the {@code user-list} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code user-list} template renders from
   * @return the {@code user-list} view name (the BMS {@code SEND-USRLST-SCREEN} equivalent)
   */
  @GetMapping("/user-list")
  public String showUserList(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    UserListScreen screen = new UserListScreen();
    userListService.processUserList(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_USER_LIST_FORM, screen);
    return VIEW_USER_LIST;
  }

  /**
   * Processes a user-list submission on re-entry, reproducing the COBOL {@code MAIN-PARA} re-entry
   * branch that performs {@code RECEIVE-USRLST-SCREEN} then {@code EVALUATE EIBAID} ({@code
   * COUSR00C} L120-138).
   *
   * <p>The submitted screen is bound as the {@code userListForm} object (the {@code RECEIVE}), the
   * program context is marked re-entry, and the raw {@code pfKey} request parameter is resolved to
   * a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the {@code EVALUATE
   * EIBAID} logic and returns the next program to transfer to:
   *
   * <ul>
   *   <li>{@code "COUSR02C"} &rarr; a row was selected with {@code U}: the selected user id is
   *       re-derived from the bound rows and stashed as the {@value #ATTR_SELECTED_USER_ID} flash
   *       attribute so {@code UserUpdateController} can pre-fill it, then the controller issues the
   *       Spring redirect that replaces {@code EXEC CICS XCTL PROGRAM('COUSR02C')};
   *   <li>{@code "COUSR03C"} &rarr; a row was selected with {@code D}: the selected user id is
   *       likewise flashed and the controller redirects to {@code UserDeleteController}, replacing
   *       {@code EXEC CICS XCTL PROGRAM('COUSR03C')};
   *   <li>{@code "COADM01C"} (PF3) &rarr; the redirect to the admin menu;
   *   <li>{@code null} &rarr; the list is redisplayed with the screen carrying any error/
   *       informational message (PF7/PF8 paging, an invalid selection, or an invalid key).
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation state.
   *
   * @param screen the submitted user-list screen bound from the request form ({@code
   *     userListForm}), carrying the operator's row selections and search input
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / {@code PF7} / {@code PF8} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the list is redisplayed
   * @param redirectAttributes the flash-scope carrier used to hand the selected user id to the
   *     update/delete user screen across the redirect
   * @return a {@code redirect:} view name for the dispatched program, or the {@code user-list} view
   *     name to redisplay the list
   */
  @PostMapping("/user-list")
  public String doUserList(
      @ModelAttribute(ATTR_USER_LIST_FORM) UserListScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model,
      RedirectAttributes redirectAttributes) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = userListService.processUserList(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      if (PGM_USER_UPDATE.equals(next) || PGM_USER_DELETE.equals(next)) {
        // Row selected with 'U'/'D': carry the chosen USR-ID to COUSR02C/COUSR03C as a flash
        // attribute (it has no field in the shared CardDemoCommarea — see the KEY INSIGHT note).
        // The service's return value already chose the destination; this only supplies the id.
        String selId = firstSelectedUserId(screen);
        if (selId != null) {
          redirectAttributes.addFlashAttribute(ATTR_SELECTED_USER_ID, selId);
        }
      }
      // The XCTL equivalent: map the returned program name to its GET endpoint and redirect.
      return redirectFor(next);
    }

    // Redisplay (PF7/PF8 paging, invalid selection, or invalid key): the screen carries the
    // error/informational message line.
    model.addAttribute(ATTR_USER_LIST_FORM, screen);
    return VIEW_USER_LIST;
  }

  /**
   * Re-derives the user id of the first row the operator marked with a selection flag.
   *
   * <p><strong>Parity note.</strong> This mirrors {@code COUSR00C PROCESS-ENTER-KEY} (L149-185)
   * scanning the ten selection inputs {@code SEL0001I}..{@code SEL0010I} for the first entry that
   * is not blank (and not low-values) and capturing the corresponding {@code USRID01I}..{@code
   * USRID10I} into {@code CDEMO-CU00-USR-SELECTED}. The legacy scan captures that first non-blank
   * row <em>regardless</em> of the flag's character &mdash; it is a separate {@code EVALUATE} that
   * then maps {@code U}/{@code u} to {@code COUSR02C}, {@code D}/{@code d} to {@code COUSR03C}, and
   * anything else to the "Invalid selection. Valid values are U and D" message. Because {@code
   * CDEMO-CU00-USR-SELECTED} lives in the {@code CU00}-specific COMMAREA sub-area ({@code
   * CDEMO-CU00-INFO}) and is <em>not</em> part of the shared {@link CardDemoCommarea}, the selected
   * id is carried to the update/delete screen as a Spring MVC flash attribute rather than via the
   * commarea.
   *
   * <p>This method is invoked only when {@link UserListService} has already returned {@code
   * "COUSR02C"} or {@code "COUSR03C"} (a valid {@code U}/{@code D} selection), so the first
   * non-blank selection found here is deterministically that same marked row; the helper therefore
   * does not re-validate the flag character.
   *
   * @param screen the bound user-list screen whose rows are scanned for the selection flag
   * @return the trimmed user id of the first row carrying a non-blank selection flag, or {@code
   *     null} if the rows are absent or no row carries a selection
   */
  private String firstSelectedUserId(UserListScreen screen) {
    List<UserListScreen.UserListRow> rows = screen.getRows();
    if (rows == null) {
      return null;
    }
    for (UserListScreen.UserListRow row : rows) {
      if (row == null) {
        continue;
      }
      String sel = row.getSel();
      if (sel == null) {
        continue;
      }
      if (!sel.trim().isEmpty()) {
        String usrId = row.getUsrId();
        return (usrId == null) ? null : usrId.trim();
      }
    }
    return null;
  }
}
