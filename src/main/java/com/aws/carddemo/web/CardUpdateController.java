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
import com.aws.carddemo.dto.screen.CardUpdateScreen;
import com.aws.carddemo.service.online.CardUpdateService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>card update</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COCRDUPC} (CICS transaction {@code CCUP}, BMS mapset {@code
 * COCRDUP} / map {@code CCRDUPA}; source {@code legacy/app/cbl/COCRDUPC.cbl} + {@code
 * legacy/app/bms/COCRDUP.bms}). Per the Agent Action Plan this is the {@code web/} half of the
 * {@code CardUpdateController + CardUpdateService} pair (AAP &sect;0.4.1, &sect;0.3.3).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state (AAP &sect;0.6.5). All card
 * business logic &mdash; the <strong>optimistic read &rarr; confirm &rarr; rewrite state
 * machine</strong>, every field-level edit, the PF5 confirm-then-save handshake, and the
 * field-by-field change detection &mdash; is delegated to the {@code @Transactional} {@link
 * CardUpdateService}, which reproduces the {@code COCRDUPC} numbered paragraphs with 100%
 * behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The cross-cutting COMMAREA, AID/PF-key, and
 * next-program routing helpers are inherited from {@link BaseScreenController} (the modernized
 * {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL} equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COCRDUPC} paint-vs-process split).</strong>
 * The single COBOL transaction is split across the two HTTP verbs that the {@code card-update}
 * screen submits, both bound to {@code /card-update}:
 *
 * <ul>
 *   <li><b>{@code GET /card-update}</b> &rarr; {@link #showCardUpdate(HttpSession, Model)} is the
 *       first-entry paint. The program context is reset to ENTER and the service is invoked with
 *       {@link CardWorkArea.Aid#ENTER}; when the screen was reached from the card-list screen the
 *       service auto-reads the selected card from the COMMAREA keys and shows its details for
 *       editing, exactly as the legacy program does when {@code CDEMO-FROM-PROGRAM} is the card
 *       list.
 *   <li><b>{@code POST /card-update}</b> &rarr; {@link #doCardUpdate(CardUpdateScreen, String,
 *       HttpSession, Model)} is the re-entry process. The submitted screen is received, the program
 *       context is marked re-entry, the attention identifier is resolved, and the service runs the
 *       confirm-then-save state machine and decides whether to transfer out or redisplay.
 * </ul>
 *
 * <p><strong>PF-key parity is the service's responsibility, not the controller's.</strong> The
 * controller never branches on PF keys. It resolves the raw {@code pfKey} request parameter to a
 * {@link CardWorkArea.Aid} (via {@link BaseScreenController#resolveAid(String)}) and hands it to
 * the service, which owns the legacy {@code EVALUATE TRUE} dispatch: ENTER loads/validates, PF5
 * commits (after the confirm prompt), and PF3/PF12 cancel or return. The service then returns
 * either the next COBOL program name to transfer to or {@code null} to redisplay; this controller
 * only translates that decision:
 *
 * <ul>
 *   <li>a non-{@code null} program name &rarr; a Spring redirect that replaces {@code EXEC CICS
 *       XCTL PROGRAM(CDEMO-TO-PROGRAM)} (for example PF3 returns {@code "COCRDLIC"} &rarr; {@code
 *       redirect:/card-list} back to the card list, or {@code "COMEN01C"} &rarr; {@code
 *       redirect:/menu}), mapped by {@link BaseScreenController#redirectFor(String)};
 *   <li>{@code null} &rarr; the {@code card-update} screen is redisplayed carrying the validation
 *       error(s), the PF5 confirm prompt, or the success message produced by the service.
 * </ul>
 *
 * <p><strong>Role parity.</strong> The card-update transaction {@code CCUP} is reachable by any
 * authenticated operator in the legacy application (it is a card function, not an admin-only user
 * management screen), so &mdash; like the standard {@link MainMenuController} and unlike {@link
 * AdminMenuController} &mdash; this controller deliberately carries no {@code @PreAuthorize} (AAP
 * &sect;0.3.4).
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, and it does not catch
 * the {@code CardDemoException} family &mdash; unrecoverable failures propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class CardUpdateController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code card-update} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COCRDUP} / {@code CCRDUPA} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_CARD_UPDATE = "card-update";

  /**
   * Model attribute / form-backing-object name bound by the {@code card-update} template's {@code
   * th:object}. The template binds the authoritative {@link CardUpdateScreen} (carrying {@code
   * acctSid}, {@code cardSid}, {@code crdName}, {@code crdStcd}, {@code expMon}, {@code expYear},
   * {@code expDay}, {@code infoMsg}, {@code errMsg}, {@code fkeys}, {@code fkeysc}) under this
   * name; the DTO is the ground-truth contract and no adapter type is introduced (AAP &sect;0.4.1).
   */
  private static final String ATTR_CARD_UPDATE_FORM = "cardUpdateForm";

  /**
   * Name of the request parameter that carries the attention identifier token ({@code ENTER} /
   * {@code PF3} / {@code PF5} / {@code PF12} / &hellip;) submitted by the {@code card-update}
   * screen. It is resolved to a {@link CardWorkArea.Aid} by {@link
   * BaseScreenController#resolveAid(String)}.
   */
  private static final String PARAM_PF_KEY = "pfKey";

  /**
   * Card-update business-logic service migrated from {@code COCRDUPC}. Injected by constructor so
   * the dependency is explicit, final, and the controller stays trivially unit-testable with a
   * mocked service.
   */
  private final CardUpdateService cardUpdateService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param cardUpdateService the card-update business-logic service (the migrated {@code COCRDUPC}
   *     paragraphs and confirm-then-save state machine); must not be {@code null}
   */
  public CardUpdateController(CardUpdateService cardUpdateService) {
    this.cardUpdateService = cardUpdateService;
  }

  /**
   * Displays the card-update screen on first entry, reproducing the COBOL {@code COCRDUPC}
   * first-entry paint.
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), its program context is reset to ENTER, and the service is invoked with {@link
   * CardWorkArea.Aid#ENTER} on a fresh {@link CardUpdateScreen}. When the operator arrived from the
   * card-list screen the service auto-reads the card selected there (using the account/card keys
   * carried in the COMMAREA) and populates the screen for editing; otherwise the service paints the
   * "enter account and card number" prompt. The mutated communication area is stored back to the
   * session and the screen is placed on the model for the {@code card-update} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code card-update} template renders from
   * @return the {@code card-update} view name (the BMS {@code SEND MAP} equivalent)
   */
  @GetMapping("/card-update")
  public String showCardUpdate(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    CardUpdateScreen screen = new CardUpdateScreen();
    cardUpdateService.processCardUpdate(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_CARD_UPDATE_FORM, screen);
    return VIEW_CARD_UPDATE;
  }

  /**
   * Processes a card-update submission on re-entry, reproducing the COBOL {@code COCRDUPC} re-entry
   * branch that performs the map {@code RECEIVE} then the {@code EVALUATE TRUE}
   * attention-identifier dispatch.
   *
   * <p>The submitted screen is bound as the {@code cardUpdateForm} object (the {@code RECEIVE}),
   * the program context is marked re-entry, and the raw {@code pfKey} request parameter is resolved
   * to a {@link CardWorkArea.Aid} (ENTER by default). The service then runs the confirm-then-save
   * state machine &mdash; loading/validating on ENTER, committing on PF5 after confirmation,
   * cancelling or returning on PF3/PF12 &mdash; and returns the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps,
   *       e.g., the PF3 {@code "COCRDLIC"} &rarr; {@code redirect:/card-list} and {@code
   *       "COMEN01C"} &rarr; {@code redirect:/menu});
   *   <li>{@code null} &rarr; the screen is redisplayed carrying the validation error(s), the PF5
   *       confirm prompt, or the success message.
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role and state-machine state.
   *
   * @param screen the submitted card-update screen bound from the request form ({@code
   *     cardUpdateForm}), carrying the operator's edits and the cross-turn state-machine flag
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / {@code PF5} / {@code PF12} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the program to transfer to, or the {@code
   *     card-update} view name to redisplay the screen
   */
  @PostMapping("/card-update")
  public String doCardUpdate(
      @ModelAttribute(ATTR_CARD_UPDATE_FORM) CardUpdateScreen screen,
      @RequestParam(value = PARAM_PF_KEY, required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = cardUpdateService.processCardUpdate(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A returned program name (e.g. PF3 -> COCRDLIC, or exit -> COMEN01C): the XCTL equivalent.
      // The transfer is routed to the target screen's GET endpoint via the shared program-name ->
      // URL routing table inherited from BaseScreenController.
      return redirectFor(next);
    }

    // Redisplay: the screen carries the field-level validation error(s), the PF5 confirm prompt, or
    // the post-commit success message that the service set on this turn.
    model.addAttribute(ATTR_CARD_UPDATE_FORM, screen);
    return VIEW_CARD_UPDATE;
  }
}
