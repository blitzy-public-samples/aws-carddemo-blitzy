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
import com.aws.carddemo.dto.screen.CardListScreen;
import com.aws.carddemo.service.online.CardListService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>card list / browse</strong> screen, migrated from
 * the legacy CICS COBOL program {@code COCRDLIC} (CICS transaction {@code CCLI}, BMS mapset {@code
 * COCRDLI} / map {@code CCRDLIA}; source {@code legacy/app/cbl/COCRDLIC.cbl} + {@code
 * legacy/app/bms/COCRDLI.bms}).
 *
 * <p>The screen renders a paged list of up to <strong>seven</strong> cards with optional
 * account-number and card-number filters; the operator may mark a single row with {@code 'S'} to
 * view it or {@code 'U'} to update it (Agent Action Plan &sect;0.4.1, &sect;0.6.5).
 *
 * <p>This controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state. All business logic &mdash;
 * filter validation, the VSAM-equivalent forward/backward browse, paging bookkeeping, single-select
 * enforcement, and the {@code 'S'}/{@code 'U'} action-to-program decision &mdash; is delegated to
 * {@link CardListService}, which reproduces the {@code COCRDLIC} numbered paragraphs with 100%
 * behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The cross-cutting COMMAREA, AID/PF-key, and
 * next-program routing helpers are inherited from {@link BaseScreenController} (the modernized
 * {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL} equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COCRDLIC} {@code 0000-MAIN} paint-vs-process
 * split, COBOL L298-L621).</strong> The single CICS transaction is split across the two HTTP verbs
 * that the {@code card-list} screen submits to the same {@code /card-list} path:
 *
 * <ul>
 *   <li><b>{@code GET /card-list}</b> &rarr; {@link #showCardList(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL first-entry branch ({@code IF EIBCALEN = 0} /
 *       {@code SET CDEMO-PGM-ENTER}, L315-L323) followed by {@code 1000-SEND-MAP} (L624): the
 *       program context is reset to ENTER and the service loads the first page of rows for display.
 *   <li><b>{@code POST /card-list}</b> &rarr; {@link #doCardList(CardListScreen, String,
 *       HttpSession, Model)} is the re-entry process. It mirrors the COBOL re-entry path that
 *       restores the COMMAREA, performs {@code 2000-RECEIVE-MAP}, and runs the {@code EVALUATE
 *       TRUE} key/selection dispatch (L418-L583): the program context is marked re-entry, the
 *       submitted attention identifier is resolved, and the service either navigates away (the
 *       {@code XCTL} that never returns) or signals a list redisplay.
 * </ul>
 *
 * <p><strong>Navigation hand-off uses the shared COMMAREA, not a flash attribute.</strong> When a
 * row is selected, {@link CardListService} moves the selected row's account id and card number into
 * the general {@link CardDemoCommarea#setAcctId(Long)} / {@link CardDemoCommarea#setCardNum(Long)}
 * fields and sets the navigation context ({@link CardDemoCommarea#setFromProgram(String)}),
 * reproducing the COBOL {@code MOVE WS-ROW-ACCTNO/WS-ROW-CARD-NUM TO CDEMO-ACCT-ID/CDEMO-CARD-NUM}
 * (L517-L569). Because that selected context is persisted by {@link
 * BaseScreenController#storeCommarea(HttpSession, CardDemoCommarea)}, the downstream {@code
 * CardViewController} ({@code COCRDSLC}) and {@code CardUpdateController} ({@code COCRDUPC}) read
 * it from the session on their own GET. This controller therefore simply {@link
 * BaseScreenController#redirectFor(String) redirects} to the target program and adds <em>no</em>
 * flash attribute &mdash; the card list deliberately differs from the transaction/user lists, whose
 * selected ids live in program-specific COMMAREA sub-areas (not modeled on {@link
 * CardDemoCommarea}) and so require controller-mediated flash.
 *
 * <p><strong>Paging and selection.</strong> PF7 (page backward) and PF8 (page forward) update the
 * rows and page number entirely inside {@link CardListService} and return {@code null}, so this
 * controller merely re-renders the list. Likewise an invalid action code, a multi-row selection, or
 * a "no records found" outcome is surfaced as a message on the redisplayed screen ({@code null}
 * return), preserving the legacy list-screen error behavior.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, and it does not catch
 * the {@code CardDemoException} family &mdash; an unrecoverable data-access failure (the legacy
 * {@code WHEN OTHER} VSAM abend) propagates to the application-wide exception handler (the
 * modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class CardListController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code card-list} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COCRDLI} / {@code CCRDLIA} map. Used for both the first-entry
   * paint and the post-process redisplay (the {@code 1000-SEND-MAP} equivalent).
   */
  private static final String VIEW_CARD_LIST = "card-list";

  /**
   * Model attribute / form-backing-object name bound by the {@code card-list} template's {@code
   * th:object}. The template is authoritative: it binds the {@link CardListScreen} under {@code
   * cardListForm} (carrying the filters, the page number, and the seven selectable rows iterated
   * via {@code *{rows}}), so no separate row/options attribute is added here (AAP &sect;0.4.1).
   */
  private static final String ATTR_CARD_LIST_FORM = "cardListForm";

  /**
   * Card list business-logic service migrated from {@code COCRDLIC}. Injected by constructor so the
   * dependency is explicit, final, and the controller stays trivially unit-testable with a mocked
   * service.
   */
  private final CardListService cardListService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param cardListService the card list business-logic service (the migrated {@code COCRDLIC}
   *     paragraphs); must not be {@code null}
   */
  public CardListController(CardListService cardListService) {
    this.cardListService = cardListService;
  }

  /**
   * Displays the card list on first entry, reproducing the COBOL {@code 0000-MAIN} first-entry
   * paint ({@code IF EIBCALEN = 0} / {@code SET CDEMO-PGM-ENTER}, {@code COCRDLIC} L315-L323)
   * followed by {@code 1000-SEND-MAP} (L624).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), its program context is reset to ENTER, and the service is invoked with {@link
   * CardWorkArea.Aid#ENTER} so it loads the first page of up to seven rows onto a fresh {@link
   * CardListScreen} and returns {@code null} (paint, no dispatch). The mutated communication area
   * is stored back to the session, and the screen is placed on the model for the {@code card-list}
   * template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code card-list} template renders from
   * @return the {@code card-list} view name (the BMS {@code SEND MAP} equivalent)
   */
  @GetMapping("/card-list")
  public String showCardList(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    CardListScreen screen = new CardListScreen();
    cardListService.processCardList(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_CARD_LIST_FORM, screen);
    return VIEW_CARD_LIST;
  }

  /**
   * Processes a card list submission on re-entry, reproducing the COBOL {@code 0000-MAIN} re-entry
   * path that restores the COMMAREA, performs {@code 2000-RECEIVE-MAP}, and runs the {@code
   * EVALUATE TRUE} key/selection dispatch ({@code COCRDLIC} L418-L583).
   *
   * <p>The submitted screen is bound as the {@code cardListForm} object (the {@code RECEIVE}), the
   * program context is marked re-entry, and the raw {@code pfKey} request parameter is resolved to
   * a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the key/selection
   * logic and returns the next program to transfer to:
   *
   * <ul>
   *   <li>{@code "COCRDSLC"} &mdash; a single row was marked {@code 'S'}; the service has already
   *       written the selected account id and card number into the shared {@link CardDemoCommarea},
   *       so the controller issues the Spring redirect that replaces {@code EXEC CICS XCTL
   *       PROGRAM('COCRDSLC')} ({@link BaseScreenController#redirectFor(String)} &rarr; {@code
   *       redirect:/card-view});
   *   <li>{@code "COCRDUPC"} &mdash; a single row was marked {@code 'U'}; redirect to {@code
   *       redirect:/card-update} (the selected context likewise travels on the commarea);
   *   <li>{@code "COMEN01C"} &mdash; PF3 exit; redirect to {@code redirect:/menu};
   *   <li>{@code null} &mdash; the list is redisplayed: a PF7/PF8 paging refresh, an invalid action
   *       code, a multi-row selection, or a "no records found" outcome, each carrying its message
   *       on the screen.
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request (including a redirected card view/update GET) observes the
   * updated navigation/selection state. No flash attribute is used: the selected account/card
   * context is carried by the persisted commarea (see the class javadoc).
   *
   * @param screen the submitted card list screen bound from the request form ({@code
   *     cardListForm}), carrying the operator's filters, page number, and per-row selection
   *     indicators
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / {@code PF7} / {@code PF8}); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the list is redisplayed
   * @return a {@code redirect:} view name for the dispatched program (card view, card update, or
   *     the main menu), or the {@code card-list} view name to redisplay the list
   */
  @PostMapping("/card-list")
  public String doCardList(
      @ModelAttribute(ATTR_CARD_LIST_FORM) CardListScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = cardListService.processCardList(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A row selection (S -> COCRDSLC, U -> COCRDUPC) or PF3 (-> COMEN01C): the XCTL equivalent.
      // The selected account/card already live on the persisted commarea, so the downstream screen
      // reads them on its GET — no flash attribute is needed for the card list (see class javadoc).
      return redirectFor(next);
    }

    // Redisplay (PF7/PF8 paging, invalid action code, multi-row selection, or no records found):
    // the screen carries the updated rows and any error/informational message.
    model.addAttribute(ATTR_CARD_LIST_FORM, screen);
    return VIEW_CARD_LIST;
  }
}
