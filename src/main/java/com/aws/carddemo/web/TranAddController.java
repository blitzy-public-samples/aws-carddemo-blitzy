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
import com.aws.carddemo.dto.screen.TranAddScreen;
import com.aws.carddemo.service.online.TranAddService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>Add Transaction</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COTRN02C} (CICS transaction {@code CT02}, BMS mapset {@code
 * COTRN02}; source {@code legacy/app/cbl/COTRN02C.cbl} + {@code legacy/app/bms/COTRN02.bms}).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state (Agent Action Plan
 * &sect;0.6.5). All business logic &mdash; resolving the account/card via the card cross-reference,
 * validating every input field in the exact COBOL order, the {@code CONFIRM} (Y/N) handling, and
 * the transaction insert with {@link java.math.BigDecimal} decimal fidelity (AAP &sect;0.6.1)
 * &mdash; is delegated to the {@code @Transactional} {@link TranAddService}, which reproduces the
 * {@code COTRN02C} paragraphs with 100% behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The
 * cross-cutting COMMAREA, AID/PF-key, and next-program routing helpers are inherited from {@link
 * BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL}
 * equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COTRN02C MAIN-PARA} paint-vs-process split,
 * L107-159).</strong> The single COBOL transaction is split across the two HTTP verbs that the
 * {@code transaction-add} screen submits:
 *
 * <ul>
 *   <li><b>{@code GET /transaction-add}</b> &rarr; {@link #showTranAdd(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch (L120-130):
 *       the program context is reset to ENTER and the service builds a fresh {@link TranAddScreen}.
 *       Mirroring the COBOL {@code CDEMO-CT02-TRN-SELECTED} hand-off (L124-129), when the upstream
 *       navigation COMMAREA already carries a selected card number the controller pre-fills the
 *       card-number field so the service immediately drives the ENTER path for it.
 *   <li><b>{@code POST /transaction-add}</b> &rarr; {@link #doTranAdd(TranAddScreen, String,
 *       HttpSession, Model)} is the re-entry process. It mirrors the COBOL {@code ELSE} branch that
 *       performs {@code RECEIVE-TRNADD-SCREEN} then {@code EVALUATE EIBAID} (L131-153): the program
 *       context is marked re-entry, the submitted attention identifier is resolved, and the service
 *       performs the ENTER / PF3 / PF4 / PF5 / "other key" dispatch, returning either the next
 *       program to transfer to or {@code null} to redisplay.
 * </ul>
 *
 * <p><strong>No PF-key branching in the controller.</strong> The COBOL {@code EVALUATE EIBAID}
 * actions &mdash; ENTER (validate-and-add), PF3 (return to caller or {@code COMEN01C}), PF4 ({@code
 * CLEAR-CURRENT-SCREEN}), and PF5 ({@code COPY-LAST-TRAN-DATA}) &mdash; all live inside {@link
 * TranAddService} and are selected from the resolved {@link CardWorkArea.Aid}. This controller
 * deliberately does not branch on PF keys; it only resolves the key and forwards it.
 *
 * <p><strong>Decimal fidelity.</strong> The transaction amount is bound as a {@link
 * java.math.BigDecimal} on {@link TranAddScreen}; the controller merely transports the field. All
 * parsing, scale (2) and truncation are owned by {@link TranAddService} (AAP &sect;0.6.1); no
 * {@code float}/{@code double} is used anywhere on this path.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, and it does not catch
 * the {@code CardDemoException} family &mdash; unrecoverable failures propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class TranAddController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code transaction-add} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COTRN02} / {@code COTRN2A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_TRANSACTION_ADD = "transaction-add";

  /**
   * Model attribute / form-backing-object name bound by the {@code transaction-add} template's
   * {@code th:object}. The {@link TranAddScreen} DTO is the ground-truth contract (AAP
   * &sect;0.4.1): it carries every operator input field ({@code actIdIn}, {@code cardNin}, {@code
   * ttypCd}, {@code tcatCd}, {@code trnSrc}, {@code tDesc}, {@code trnAmt}, {@code tOrigDt}, {@code
   * tProcDt}, the merchant fields, and {@code confirm}) plus the {@code errMsg} message line.
   */
  private static final String ATTR_TRANSACTION_ADD_FORM = "transactionAddForm";

  /**
   * Add-transaction business-logic service migrated from {@code COTRN02C}. Injected by constructor
   * so the dependency is explicit, final, and the controller stays trivially unit-testable with a
   * mocked service.
   */
  private final TranAddService tranAddService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param tranAddService the add-transaction business-logic service (the migrated {@code COTRN02C}
   *     paragraphs); must not be {@code null}
   */
  public TranAddController(TranAddService tranAddService) {
    this.tranAddService = tranAddService;
  }

  /**
   * Displays the Add Transaction screen on first entry, reproducing the COBOL {@code MAIN-PARA}
   * first-entry paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code COTRN02C} L120-130).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry) and its program context is reset to ENTER. A fresh {@link TranAddScreen} is
   * allocated; when the navigation COMMAREA already carries a selected card number (the upstream
   * card-list/card-view hand-off, COBOL {@code CDEMO-CT02-TRN-SELECTED}), the card-number field is
   * pre-filled zero-padded to 16 digits to mirror the COBOL {@code PIC 9(16)} work-area value
   * (normalization/validation remain the service's responsibility). The service is then invoked
   * with {@link CardWorkArea.Aid#ENTER}; on first entry with a pre-filled card it immediately
   * drives the ENTER validation path, otherwise it returns a clean paint. The mutated communication
   * area is stored back to the session and the screen is placed on the model for the {@code
   * transaction-add} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code transaction-add} template renders from
   * @return the {@code transaction-add} view name (the BMS {@code SEND-TRNADD-SCREEN} equivalent)
   */
  @GetMapping("/transaction-add")
  public String showTranAdd(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    TranAddScreen screen = new TranAddScreen();
    if (commarea.getCardNum() != null) {
      // CDEMO-CT02-TRN-SELECTED hand-off (COTRN02C L124-129): pre-fill the card number from the
      // upstream selection, zero-padded to 16 to mirror the COBOL PIC 9(16) work-area value. The
      // service normalizes and validates it from here.
      screen.setCardNin(String.format("%016d", commarea.getCardNum()));
    }

    tranAddService.processTranAdd(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_TRANSACTION_ADD_FORM, screen);
    return VIEW_TRANSACTION_ADD;
  }

  /**
   * Processes an Add Transaction submission on re-entry, reproducing the COBOL {@code MAIN-PARA}
   * re-entry branch that performs {@code RECEIVE-TRNADD-SCREEN} then {@code EVALUATE EIBAID}
   * ({@code COTRN02C} L131-153).
   *
   * <p>The submitted screen is bound as the {@code transactionAddForm} object (the {@code
   * RECEIVE}), the program context is marked re-entry, and the raw {@code pfKey} request parameter
   * is resolved to a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the
   * {@code EVALUATE EIBAID} logic &mdash; ENTER validate-and-add, PF3 return-to-caller, PF4 clear,
   * PF5 copy-last-transaction, and the "other key" guard &mdash; and returns the next program to
   * transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps,
   *       e.g., the PF3 {@code "COMEN01C"} &rarr; {@code redirect:/menu} or the recorded caller);
   *   <li>{@code null} &rarr; the screen is redisplayed carrying any {@code errMsg} (a validation
   *       failure, the confirm prompt, an invalid-key message, or the success confirmation).
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state.
   *
   * @param screen the submitted Add Transaction screen bound from the request form ({@code
   *     transactionAddForm}), carrying the operator's input fields and the message line
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / {@code PF4} / {@code PF5} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the dispatched program, or the {@code
   *     transaction-add} view name to redisplay the screen
   */
  @PostMapping("/transaction-add")
  public String doTranAdd(
      @ModelAttribute(ATTR_TRANSACTION_ADD_FORM) TranAddScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = tranAddService.processTranAdd(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A PF3 return target (caller or COMEN01C): the XCTL equivalent, dispatched to the target
      // screen's GET endpoint via the shared program-name -> URL routing table.
      return redirectFor(next);
    }

    // Redisplay: the screen carries validation errors, the confirm prompt, an invalid-key message,
    // or the green success confirmation produced by the service.
    model.addAttribute(ATTR_TRANSACTION_ADD_FORM, screen);
    return VIEW_TRANSACTION_ADD;
  }
}
