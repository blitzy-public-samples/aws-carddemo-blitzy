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
import com.aws.carddemo.dto.screen.CardViewScreen;
import com.aws.carddemo.service.online.CardViewService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>card detail / view</strong> screen, migrated from
 * the legacy CICS COBOL program {@code COCRDSLC} (CICS transaction {@code CCDL}, BMS mapset {@code
 * COCRDSL}; source {@code legacy/app/cbl/COCRDSLC.cbl} + {@code legacy/app/bms/COCRDSL.bms}).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state (Agent Action Plan
 * &sect;0.6.5). All card-view business logic &mdash; the attention-key dispatch, the account/card
 * filter validation, the single-card read, the found-card field population, and the on-screen
 * info/error messages &mdash; is delegated to {@link CardViewService}, which reproduces the {@code
 * COCRDSLC} numbered paragraphs with 100% behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The
 * cross-cutting COMMAREA, AID/PF-key, and next-program routing helpers are inherited from {@link
 * BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL}
 * equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COCRDSLC 0000-MAIN} paint-vs-process split,
 * L248-408).</strong> The single COBOL transaction is split across the two idempotent HTTP verbs
 * that the {@code card-view} screen submits, both bound to {@code /card-view}:
 *
 * <ul>
 *   <li><b>{@code GET /card-view}</b> &rarr; {@link #showCardView(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL {@code CDEMO-PGM-ENTER} arms of the {@code EVALUATE
 *       TRUE} (L339-356): the program context is reset to ENTER and the service is invoked with
 *       {@link CardWorkArea.Aid#ENTER}. The service then decides &mdash; entirely on its own
 *       &mdash; whether this is the card-list auto-read path or the empty-search-form paint (see
 *       below).
 *   <li><b>{@code POST /card-view}</b> &rarr; {@link #doCardView(CardViewScreen, String,
 *       HttpSession, Model)} is the re-entry process. It mirrors the COBOL {@code
 *       CDEMO-PGM-REENTER} arm that performs {@code 2000-PROCESS-INPUTS} then conditionally {@code
 *       9000-READ-DATA} (L357-371), as well as the {@code CCARD-AID-PFK03} exit arm (L305-334): the
 *       program context is marked re-entry, the submitted attention identifier is resolved, and the
 *       service either returns the next program to transfer to (the {@code XCTL} that never
 *       returns) or signals a screen redisplay.
 * </ul>
 *
 * <p><strong>Uniform controller / service owns the auto-read decision.</strong> Card detail is
 * reached two ways: by typing an account/card number from the menu path, or by selecting a row in
 * the card list ({@code COCRDLIC}). The legacy program distinguishes these by testing {@code
 * CDEMO-FROM-PROGRAM = LIT-CCLISTPGM} (L340); when it came from the card list the criteria were
 * already validated there and the card is read immediately on first entry (L339-348). That decision
 * lives <em>entirely inside</em> {@link CardViewService}: this controller does <strong>not</strong>
 * inspect {@code fromProgram}. It performs the same uniform {@link CardWorkArea.Aid#ENTER} call on
 * {@code GET} regardless of caller, so arriving from {@code /card-list} (with the commarea {@code
 * fromProgram == "COCRDLIC"} and the {@code cardNum}/{@code acctId} set) displays the card on the
 * GET itself, with no controller-side branch and no intermediate flash.
 *
 * <p><strong>PF3 exit routing ({@code EXEC CICS XCTL}, L305-334).</strong> On PF3 the service
 * returns the next program name &mdash; the original caller when present ({@code COCRDLIC} back to
 * the card list), otherwise the main menu default ({@code COMEN01C}). This controller forwards that
 * to {@link BaseScreenController#redirectFor(String)}, which maps the program name to its GET
 * endpoint ({@code "COCRDLIC"} &rarr; {@code redirect:/card-list}, {@code "COMEN01C"} &rarr; {@code
 * redirect:/menu}). A {@code null} return means "redisplay this screen".
 *
 * <p><strong>Template &harr; DTO contract.</strong> The {@code card-view} template binds the
 * authoritative {@link CardViewScreen} under {@code cardViewForm} (its {@code th:object}) and POSTs
 * to {@code /card-view}. The screen's fields ({@code acctSid} X11, {@code cardSid} X16, {@code
 * crdName} X50, {@code crdStcd} X1, {@code expMon} X2, {@code expYear} X4, plus the {@code infoMsg}
 * / {@code errMsg} / {@code fkeys} lines) are the ground-truth contract migrated field-for-field
 * from the BMS mapset; no adapter type is introduced (AAP &sect;0.4.1). The info/error message
 * lines are carried on the screen DTO itself, so binding only {@code cardViewForm} onto the model
 * is sufficient for redisplay.
 *
 * <p><strong>Role parity.</strong> The card-view transaction {@code CCDL} is reachable by any
 * authenticated user (it is not one of the admin-only user-management screens), so &mdash; like the
 * account/card siblings and unlike {@code AdminMenuController} &mdash; this controller carries no
 * {@code @PreAuthorize} gate.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, and it does not catch
 * the {@code CardDemoException} family &mdash; a genuine record-not-found surfaces as a user-facing
 * message inside the service (it never throws for not-found), while a truly unrecoverable I/O
 * failure propagates to the application-wide exception handler (the modernized abend path, AAP
 * &sect;0.6.6).
 */
@Controller
public class CardViewController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code card-view} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COCRDSL} / {@code CCRDSLA} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_CARD_VIEW = "card-view";

  /**
   * Model attribute / form-backing-object name bound by the {@code card-view} template's {@code
   * th:object}. The template is authoritative: it binds the {@link CardViewScreen} under {@code
   * cardViewForm} (carrying the operator {@code acctSid}/{@code cardSid} filters, the looked-up
   * card detail fields, and the {@code infoMsg}/{@code errMsg} lines).
   */
  private static final String ATTR_CARD_VIEW_FORM = "cardViewForm";

  /**
   * Card-view business-logic service migrated from {@code COCRDSLC}. Injected by constructor so the
   * dependency is explicit, final, and the controller stays trivially unit-testable with a mocked
   * service.
   */
  private final CardViewService cardViewService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param cardViewService the card-view business-logic service (the migrated {@code COCRDSLC}
   *     paragraphs); must not be {@code null}
   */
  public CardViewController(CardViewService cardViewService) {
    this.cardViewService = cardViewService;
  }

  /**
   * Displays the card-detail screen on first entry, reproducing the COBOL {@code 0000-MAIN} {@code
   * CDEMO-PGM-ENTER} arms ({@code COCRDSLC} L339-356).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), its program context is reset to ENTER, and the service is invoked with {@link
   * CardWorkArea.Aid#ENTER} against a fresh {@link CardViewScreen}. The service decides the rest:
   *
   * <ul>
   *   <li>when {@code commarea.getFromProgram()} is {@code "COCRDLIC"} (arrived from the card
   *       list), the already-validated {@code cardNum}/{@code acctId} carried in the commarea are
   *       read and the card detail is painted immediately (the {@code 9000-READ-DATA} auto-read,
   *       L339-348);
   *   <li>otherwise an empty search form is painted so the operator can type an account id and card
   *       number (L349-356).
   * </ul>
   *
   * <p>Because the service owns that decision, this method makes the same uniform call for every
   * caller and never inspects {@code fromProgram} itself. The mutated communication area is stored
   * back to the session, and the screen is placed on the model under {@code cardViewForm} for the
   * {@code card-view} template (the BMS {@code 1000-SEND-MAP} equivalent).
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code card-view} template renders from
   * @return the {@code card-view} view name
   */
  @GetMapping("/card-view")
  public String showCardView(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    CardViewScreen screen = new CardViewScreen();
    cardViewService.processCardView(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_CARD_VIEW_FORM, screen);
    return VIEW_CARD_VIEW;
  }

  /**
   * Processes a card-view submission on re-entry, reproducing the COBOL {@code 0000-MAIN} {@code
   * CDEMO-PGM-REENTER} and {@code CCARD-AID-PFK03} arms ({@code COCRDSLC} L305-334, L357-371).
   *
   * <p>The submitted screen is bound as the {@code cardViewForm} object (the {@code
   * 2100-RECEIVE-MAP} equivalent), the program context is marked re-entry, and the raw {@code
   * pfKey} request parameter is resolved to a {@link CardWorkArea.Aid} (ENTER by default; every key
   * other than ENTER and PF3 is coerced to ENTER, exactly as the COBOL remapped unmapped keys). The
   * service then runs the dispatch and returns the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps
   *       the PF3 target {@code "COCRDLIC"} &rarr; {@code redirect:/card-list} when the screen was
   *       reached from the card list, or {@code "COMEN01C"} &rarr; {@code redirect:/menu} when it
   *       was reached from the menu);
   *   <li>{@code null} &rarr; the screen is redisplayed carrying any {@code errMsg} (a
   *       blank/invalid account or card filter, or "not found") or the populated card detail with
   *       its {@code infoMsg}.
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state.
   *
   * @param screen the submitted card-view screen bound from the request form ({@code
   *     cardViewForm}), carrying the operator's raw {@code acctSid}/{@code cardSid} filters
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the program transferred to on PF3, or the {@code
   *     card-view} view name to redisplay the screen
   */
  @PostMapping("/card-view")
  public String doCardView(
      @ModelAttribute(ATTR_CARD_VIEW_FORM) CardViewScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = cardViewService.processCardView(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // PF3 exit: the XCTL equivalent. Route to the caller's GET endpoint (the card list when this
      // screen was reached from COCRDLIC, otherwise the main menu) via the shared program-name ->
      // URL routing table.
      return redirectFor(next);
    }

    // Redisplay (empty/invalid filter, not-found, or a successful read): the screen carries the
    // looked-up card detail and the info/error message lines.
    model.addAttribute(ATTR_CARD_VIEW_FORM, screen);
    return VIEW_CARD_VIEW;
  }
}
