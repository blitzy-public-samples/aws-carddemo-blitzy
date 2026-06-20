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
import com.aws.carddemo.dto.screen.TranListScreen;
import com.aws.carddemo.service.online.TranListService;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the CardDemo <strong>transaction list</strong> screen, migrated from
 * the legacy CICS COBOL program {@code COTRN00C} (CICS transaction {@code CT00}, BMS mapset {@code
 * COTRN00}; source {@code legacy/app/cbl/COTRN00C.cbl} + {@code legacy/app/bms/COTRN00.bms}).
 *
 * <p>The screen browses the {@code TRANSACT} store in ascending transaction-id order and renders it
 * ten rows at a time, each row carrying a one-character selection flag. Entering {@code S} against
 * a row drills into the transaction-view screen ({@code COTRN01C}); {@code PF3} returns to the main
 * menu ({@code COMEN01C}); {@code PF7}/{@code PF8} page backward/forward (Agent Action Plan
 * &sect;0.4.1, &sect;0.6.5).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state. All list/browse business
 * logic &mdash; the keyset paging, the ten-row page build, the row-selection scan, and every
 * operator-visible message &mdash; is delegated to {@link TranListService}, which reproduces the
 * {@code COTRN00C} paragraphs with 100% behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The
 * cross-cutting COMMAREA, AID/PF-key, and next-program routing helpers are inherited from {@link
 * BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL}
 * equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COTRN00C MAIN-PARA} paint-vs-process split,
 * L95-141).</strong> The single COBOL transaction is split across the two HTTP verbs that the
 * {@code transaction-list} screen submits, both bound to {@code /transaction-list} and dispatched
 * by method:
 *
 * <ul>
 *   <li><b>{@code GET /transaction-list}</b> &rarr; {@link #showTranList(HttpSession, Model)} is
 *       the first-entry paint. It mirrors the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch
 *       (L112-116): the program context is reset to ENTER and the service browses the first page of
 *       up to ten rows (the {@code findAllByOrderByTranIdAsc()} ordering) onto a fresh screen.
 *   <li><b>{@code POST /transaction-list}</b> &rarr; {@link #doTranList(TranListScreen, String,
 *       HttpSession, Model, RedirectAttributes)} is the re-entry process. It mirrors the COBOL
 *       {@code ELSE} branch that performs {@code RECEIVE-TRNLST-SCREEN} then {@code EVALUATE
 *       EIBAID} (L117-135): the program context is marked re-entry, the submitted attention
 *       identifier is resolved, and the service either forwards (a row selected with {@code S}, or
 *       {@code PF3}) or signals a redisplay ({@code PF7}/{@code PF8} paging or a validation
 *       message).
 * </ul>
 *
 * <p><strong>Controller-mediated selection handoff (KEY INSIGHT).</strong> When a row is selected
 * with {@code S} the legacy program captures the chosen {@code TRNIDxxI} into {@code
 * CDEMO-CT00-TRN-SELECTED} &mdash; a {@code CT00}-specific COMMAREA sub-area ({@code
 * CDEMO-CT00-INFO}) that is <em>not</em> part of the shared {@link CardDemoCommarea} contract.
 * Rather than widen the shared commarea, this controller re-derives the selected transaction id
 * from the bound rows (see {@link #firstSelectedTranId(TranListScreen)}) and carries it to {@code
 * COTRN01C} as a Spring MVC <em>flash attribute</em> ({@value #ATTR_SELECTED_TRAN_ID}), surviving
 * exactly one redirect so the {@code TranViewController} can pre-fill its input. This contrasts
 * with the card list, which routes via the shared {@code acctId}/{@code cardNum} commarea fields.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, and it does not catch
 * the {@code CardDemoException} family &mdash; unrecoverable failures propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class TranListController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code transaction-list} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COTRN00} / {@code COTRN0A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_TRANSACTION_LIST = "transaction-list";

  /**
   * Model attribute / form-backing-object name bound by the {@code transaction-list} template's
   * {@code th:object}. The authoritative {@link TranListScreen} is bound under {@code
   * transactionListForm}; the template may iterate a friendlier alias for the rows, but the DTO
   * field names ({@code rows}, {@code sel}, {@code trnId}, ...) are the ground-truth contract (AAP
   * &sect;0.4.1) &mdash; no adapter type is introduced.
   */
  private static final String ATTR_TRANSACTION_LIST_FORM = "transactionListForm";

  /**
   * Flash-attribute key under which the selected transaction id is handed to the transaction-view
   * screen across the post/redirect/get boundary. This is the modernized home of the legacy {@code
   * CDEMO-CT00-TRN-SELECTED} value (see the class-level KEY INSIGHT note).
   */
  private static final String ATTR_SELECTED_TRAN_ID = "selectedTranId";

  /**
   * The transaction-view program name returned by {@link TranListService} when a row is selected
   * with {@code S}; matched to trigger the controller-mediated flash handoff. COBOL {@code MOVE
   * 'COTRN01C' TO CDEMO-TO-PROGRAM} (COTRN00C L188).
   */
  private static final String PGM_TRAN_VIEW = "COTRN01C";

  /**
   * The single valid row-selection flag, compared case-insensitively. COBOL {@code EVALUATE
   * CDEMO-CT00-TRN-SEL-FLG WHEN 'S' WHEN 's'} (COTRN00C L185-187).
   */
  private static final String SELECTION_VIEW = "S";

  /**
   * Transaction-list business-logic service migrated from {@code COTRN00C}. Injected by constructor
   * so the dependency is explicit, final, and the controller stays trivially unit-testable with a
   * mocked service.
   */
  private final TranListService tranListService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param tranListService the transaction-list business-logic service (the migrated {@code
   *     COTRN00C} paragraphs); must not be {@code null}
   */
  public TranListController(TranListService tranListService) {
    this.tranListService = tranListService;
  }

  /**
   * Displays the transaction list on first entry, reproducing the COBOL {@code MAIN-PARA}
   * first-entry paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code COTRN00C} L112-116).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), its program context is reset to ENTER, and the service is invoked with {@link
   * CardWorkArea.Aid#ENTER} so it browses the first page of up to ten rows onto a fresh {@link
   * TranListScreen} (the {@code findAllByOrderByTranIdAsc()} ordering) and returns {@code null}
   * (paint, no dispatch). The mutated communication area is stored back to the session, and the
   * screen is placed on the model for the {@code transaction-list} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code transaction-list} template renders from
   * @return the {@code transaction-list} view name (the BMS {@code SEND-TRNLST-SCREEN} equivalent)
   */
  @GetMapping("/transaction-list")
  public String showTranList(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    TranListScreen screen = new TranListScreen();
    tranListService.processTranList(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_TRANSACTION_LIST_FORM, screen);
    return VIEW_TRANSACTION_LIST;
  }

  /**
   * Processes a transaction-list submission on re-entry, reproducing the COBOL {@code MAIN-PARA}
   * re-entry branch that performs {@code RECEIVE-TRNLST-SCREEN} then {@code EVALUATE EIBAID}
   * ({@code COTRN00C} L117-135).
   *
   * <p>The submitted screen is bound as the {@code transactionListForm} object (the {@code
   * RECEIVE}), the program context is marked re-entry, and the raw {@code pfKey} request parameter
   * is resolved to a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the
   * {@code EVALUATE EIBAID} logic and returns the next program to transfer to:
   *
   * <ul>
   *   <li>{@code "COTRN01C"} &rarr; a row was selected with {@code S}: the selected transaction id
   *       is re-derived from the bound rows and stashed as the {@value #ATTR_SELECTED_TRAN_ID}
   *       flash attribute so {@code TranViewController} can pre-fill it, then the controller issues
   *       the Spring redirect that replaces {@code EXEC CICS XCTL PROGRAM('COTRN01C')};
   *   <li>{@code "COMEN01C"} (PF3) &rarr; the redirect to the main menu;
   *   <li>{@code null} &rarr; the list is redisplayed with the screen carrying any error/
   *       informational message (PF7/PF8 paging, an invalid selection, a non-numeric filter, or an
   *       invalid key).
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation state.
   *
   * @param screen the submitted transaction-list screen bound from the request form ({@code
   *     transactionListForm}), carrying the operator's row selections and search input
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / {@code PF7} / {@code PF8} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the list is redisplayed
   * @param redirectAttributes the flash-scope carrier used to hand the selected transaction id to
   *     the transaction-view screen across the redirect
   * @return a {@code redirect:} view name for the dispatched program, or the {@code
   *     transaction-list} view name to redisplay the list
   */
  @PostMapping("/transaction-list")
  public String doTranList(
      @ModelAttribute(ATTR_TRANSACTION_LIST_FORM) TranListScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model,
      RedirectAttributes redirectAttributes) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = tranListService.processTranList(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      if (PGM_TRAN_VIEW.equals(next)) {
        // Row selected with 'S': carry the chosen TRAN-ID to COTRN01C as a flash attribute (it has
        // no field in the shared CardDemoCommarea — see the class-level KEY INSIGHT note).
        String selId = firstSelectedTranId(screen);
        if (selId != null) {
          redirectAttributes.addFlashAttribute(ATTR_SELECTED_TRAN_ID, selId);
        }
      }
      // The XCTL equivalent: map the returned program name to its GET endpoint and redirect.
      return redirectFor(next);
    }

    // Redisplay (PF7/PF8 paging, invalid selection, non-numeric filter, or invalid key): the screen
    // carries the error/informational message line.
    model.addAttribute(ATTR_TRANSACTION_LIST_FORM, screen);
    return VIEW_TRANSACTION_LIST;
  }

  /**
   * Re-derives the transaction id of the first row the operator selected with {@code S}.
   *
   * <p><strong>Parity note.</strong> This mirrors {@code COTRN00C PROCESS-ENTER-KEY} (L148-182)
   * scanning the ten selection inputs {@code SEL0001I}..{@code SEL0010I} for the first non-blank
   * entry and capturing the corresponding {@code TRNID01I}..{@code TRNID10I} into {@code
   * CDEMO-CT00-TRN-SELECTED}. Because {@code CDEMO-CT00-TRN-SELECTED} lives in the {@code
   * CT00}-specific COMMAREA sub-area ({@code CDEMO-CT00-INFO}) and is <em>not</em> part of the
   * shared {@link CardDemoCommarea}, the selected id is carried to the transaction-view screen as a
   * Spring MVC flash attribute rather than via the commarea. This method is invoked only when
   * {@link TranListService} has already returned {@code "COTRN01C"} (a valid {@code S} selection),
   * so the scan deterministically finds that same row.
   *
   * @param screen the bound transaction-list screen whose rows are scanned for the selection flag
   * @return the trimmed transaction id of the first {@code S}-selected row, or {@code null} if the
   *     rows are absent or no row carries an {@code S} selection
   */
  private String firstSelectedTranId(TranListScreen screen) {
    List<TranListScreen.TranListRow> rows = screen.getRows();
    if (rows == null) {
      return null;
    }
    for (TranListScreen.TranListRow row : rows) {
      if (row == null) {
        continue;
      }
      String sel = row.getSel();
      if (sel == null) {
        continue;
      }
      String trimmedSel = sel.trim();
      if (!trimmedSel.isEmpty() && SELECTION_VIEW.equalsIgnoreCase(trimmedSel)) {
        String trnId = row.getTrnId();
        return (trnId == null) ? null : trnId.trim();
      }
    }
    return null;
  }
}
