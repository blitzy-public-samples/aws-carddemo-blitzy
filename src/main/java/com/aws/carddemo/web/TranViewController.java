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
import com.aws.carddemo.dto.screen.TranViewScreen;
import com.aws.carddemo.service.online.TranViewService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>transaction view / detail</strong> screen,
 * migrated from the legacy CICS COBOL program {@code COTRN01C} (CICS transaction {@code CT01}, BMS
 * mapset {@code COTRN01}; source {@code legacy/app/cbl/COTRN01C.cbl} + {@code
 * legacy/app/bms/COTRN01.bms}).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state (Agent Action Plan
 * &sect;0.6.5). All transaction-view business logic &mdash; search-id validation, the keyed {@code
 * TRANSACT} read, detail-field population, the not-found / lookup-error messages, and the PF-key
 * navigation decisions &mdash; is delegated to {@link TranViewService}, which reproduces the {@code
 * COTRN01C} paragraphs with 100% behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The
 * cross-cutting COMMAREA, AID/PF-key, and next-program routing helpers are inherited from {@link
 * BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL}
 * equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COTRN01C MAIN-PARA} paint-vs-process split,
 * L86-139).</strong> The single COBOL transaction is split across the two idempotent HTTP verbs
 * that the {@code transaction-view} screen submits, both bound to {@code /transaction-view}:
 *
 * <ul>
 *   <li><b>{@code GET /transaction-view}</b> &rarr; {@link #showTranView(HttpSession, Model)} is
 *       the first-entry paint. It mirrors the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch
 *       (L99-109): the program context is reset to ENTER, the service paints a fresh {@link
 *       TranViewScreen}, and the screen is (re)displayed. When the operator arrived by selecting a
 *       row on the transaction-list screen, the chosen id is pre-filled so the service
 *       auto-displays that transaction (see the flash-handoff note below).
 *   <li><b>{@code POST /transaction-view}</b> &rarr; {@link #doTranView(TranViewScreen, String,
 *       HttpSession, Model)} is the re-entry process. It mirrors the COBOL {@code ELSE} branch that
 *       performs {@code RECEIVE-TRNVIEW-SCREEN} then {@code EVALUATE EIBAID} (L110-132): the
 *       program context is marked re-entry, the submitted attention identifier is resolved, and the
 *       service either looks up the transaction (ENTER), transfers control (the {@code XCTL} that
 *       never returns &mdash; PF3 back to the caller/menu, PF5 to the transaction list), clears the
 *       screen (PF4), or reports an invalid key.
 * </ul>
 *
 * <p><strong>Selected-transaction flash handoff (CRITICAL parity, AAP &sect;0.6.5).</strong> On the
 * mainframe, the transaction-list program {@code COTRN00C} moves the operator's chosen {@code
 * TRNIDxxI} row id into {@code CDEMO-CT01-TRN-SELECTED} of the COMMAREA before {@code EXEC CICS
 * XCTL PROGRAM('COTRN01C')}; {@code COTRN01C} then detects the non-blank selection on first entry
 * and auto-displays that transaction (L103-108). That selected id is <em>not</em> part of the
 * shared {@link CardDemoCommarea} contract, so the modernized list&rarr;view handoff is carried as
 * a Spring MVC flash attribute named {@value #FLASH_SELECTED_TRAN_ID}. A flash attribute set by the
 * list controller on its {@code redirect:/transaction-view} survives the redirect and is merged
 * into this GET handler's {@link Model}; {@link #showTranView(HttpSession, Model)} copies it into
 * {@link TranViewScreen#setTrnIdIn(String) trnIdIn} so the service performs the automatic lookup.
 * When no selection was flashed (the operator navigated here directly), an empty search form is
 * painted for the operator to type a transaction id.
 *
 * <p><strong>PF3 return-to-caller parity.</strong> {@code COTRN01C} routes PF3 to the {@code
 * CDEMO-FROM-PROGRAM} caller when one was recorded, else to the main menu {@code COMEN01C}
 * (L115-122); PF5 always routes to the transaction list {@code COTRN00C} (L125-127). The service
 * makes that decision and returns the next program name; this controller merely maps the returned
 * name to its GET endpoint via {@link BaseScreenController#redirectFor(String)} (the {@code XCTL}
 * equivalent).
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, and it does not catch
 * the {@code CardDemoException} family &mdash; unrecoverable failures propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class TranViewController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code transaction-view} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COTRN01} / {@code COTRN1A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_TRANSACTION_VIEW = "transaction-view";

  /**
   * Model attribute / form-backing-object name bound by the {@code transaction-view} template's
   * {@code th:object}. The template is authoritative: it binds the {@link TranViewScreen} under
   * {@code transactionViewForm} (carrying the operator's {@code trnIdIn} search input, the resolved
   * detail fields, and the {@code errMsg} line); the DTO is the ground-truth contract with no
   * adapter type (AAP &sect;0.4.1).
   */
  private static final String ATTR_TRANSACTION_VIEW_FORM = "transactionViewForm";

  /**
   * Flash-attribute key carrying the transaction id selected on the transaction-list screen. This
   * is the web equivalent of {@code COTRN00C} moving the chosen {@code TRNIDxxI} into {@code
   * CDEMO-CT01-TRN-SELECTED} before {@code XCTL} to {@code COTRN01C} (AAP &sect;0.6.5). When
   * present on first entry it is copied into {@link TranViewScreen#setTrnIdIn(String) trnIdIn} so
   * the service auto-displays the selected transaction.
   */
  private static final String FLASH_SELECTED_TRAN_ID = "selectedTranId";

  /**
   * Transaction-view business-logic service migrated from {@code COTRN01C}. Injected by constructor
   * so the dependency is explicit, {@code final}, and the controller stays trivially unit-testable
   * with a mocked service.
   */
  private final TranViewService tranViewService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param tranViewService the transaction-view business-logic service (the migrated {@code
   *     COTRN01C} paragraphs); must not be {@code null}
   */
  public TranViewController(TranViewService tranViewService) {
    this.tranViewService = tranViewService;
  }

  /**
   * Displays the transaction-view screen on first entry, reproducing the COBOL {@code MAIN-PARA}
   * first-entry paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code COTRN01C} L99-109).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), and its program context is reset to ENTER. A fresh {@link TranViewScreen} is
   * allocated; if the transaction-list screen flashed a {@value #FLASH_SELECTED_TRAN_ID} on its
   * redirect into this handler, that id is copied into {@link TranViewScreen#setTrnIdIn(String)
   * trnIdIn} &mdash; the web equivalent of {@code COTRN00C} populating {@code
   * CDEMO-CT01-TRN-SELECTED} before the {@code XCTL}. The service is then invoked with {@link
   * CardWorkArea.Aid#ENTER}: a non-blank {@code trnIdIn} triggers the automatic detail lookup
   * (L103-108), otherwise an empty search form is painted. The mutated communication area is stored
   * back to the session, and the screen is placed on the model for the {@code transaction-view}
   * template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code transaction-view} template renders from; on a
   *     list&rarr;view handoff it carries the flashed {@value #FLASH_SELECTED_TRAN_ID}
   * @return the {@code transaction-view} view name (the BMS {@code SEND-TRNVIEW-SCREEN} equivalent)
   */
  @GetMapping("/transaction-view")
  public String showTranView(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    TranViewScreen screen = new TranViewScreen();
    // Consume the list -> view flash handoff: a flash attribute survives the redirect and is merged
    // into this handler's model. Pre-filling trnIdIn reproduces COTRN00C moving the selected row id
    // into CDEMO-CT01-TRN-SELECTED, which COTRN01C auto-displays on first entry (COTRN01C
    // L103-108).
    if (model.containsAttribute(FLASH_SELECTED_TRAN_ID)) {
      screen.setTrnIdIn((String) model.getAttribute(FLASH_SELECTED_TRAN_ID));
    }

    tranViewService.processTranView(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_TRANSACTION_VIEW_FORM, screen);
    return VIEW_TRANSACTION_VIEW;
  }

  /**
   * Processes a transaction-view submission on re-entry, reproducing the COBOL {@code MAIN-PARA}
   * re-entry branch that performs {@code RECEIVE-TRNVIEW-SCREEN} then {@code EVALUATE EIBAID}
   * ({@code COTRN01C} L110-132).
   *
   * <p>The submitted screen is bound as the {@code transactionViewForm} object (the {@code
   * RECEIVE}), the program context is marked re-entry, and the raw {@code pfKey} request parameter
   * is resolved to a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the
   * {@code EVALUATE EIBAID} logic and returns the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps,
   *       e.g., the PF5 {@code "COTRN00C"} &rarr; {@code redirect:/transaction-list} and the PF3
   *       caller / {@code "COMEN01C"} &rarr; {@code redirect:/menu});
   *   <li>{@code null} &rarr; the screen is redisplayed (the ENTER lookup result &mdash; populated
   *       detail or a not-found / lookup-error message, the PF4 clear, or an invalid key).
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state.
   *
   * @param screen the submitted transaction-view screen bound from the request form ({@code
   *     transactionViewForm}), carrying the operator's {@code trnIdIn} search entry
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / {@code PF4} / {@code PF5}); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the transferred program, or the {@code
   *     transaction-view} view name to redisplay the screen
   */
  @PostMapping("/transaction-view")
  public String doTranView(
      @ModelAttribute(ATTR_TRANSACTION_VIEW_FORM) TranViewScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = tranViewService.processTranView(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // The XCTL equivalent: PF5 -> COTRN00C (/transaction-list); PF3 -> the recorded caller or the
      // main menu COMEN01C (/menu). The shared routing table maps the returned program name to its
      // GET endpoint.
      return redirectFor(next);
    }

    // Redisplay: the ENTER lookup result (populated detail or a not-found / lookup-error message),
    // the PF4 clear, or an invalid key — the screen carries the resolved detail and/or message
    // line.
    model.addAttribute(ATTR_TRANSACTION_VIEW_FORM, screen);
    return VIEW_TRANSACTION_VIEW;
  }
}
