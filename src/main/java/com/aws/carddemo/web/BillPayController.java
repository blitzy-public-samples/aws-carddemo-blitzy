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
import com.aws.carddemo.dto.screen.BillPayScreen;
import com.aws.carddemo.service.online.BillPayService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>Bill Payment</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COBIL00C} (CICS transaction {@code CB00}, BMS mapset {@code
 * COBIL00}; source {@code legacy/app/cbl/COBIL00C.cbl} + {@code legacy/app/bms/COBIL00.bms}).
 *
 * <p>The bill-payment transaction lets an operator enter an account id, see that account's current
 * balance, and confirm a <em>full-balance</em> payment that posts a {@code BILL PAYMENT - ONLINE}
 * transaction and decrements the account balance by the paid amount (Agent Action Plan &sect;0.4.1,
 * &sect;0.6.1).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state (AAP &sect;0.6.5). All
 * business logic &mdash; resolving the account and card cross-reference, reading the current
 * balance, the {@code CONFIRM} (Y/N) handling, deriving the next transaction id, writing the
 * transaction, and updating the account balance with {@link java.math.BigDecimal} decimal fidelity
 * (AAP &sect;0.6.1) &mdash; is delegated to the {@code @Transactional} {@link BillPayService},
 * which reproduces the {@code COBIL00C} paragraphs with 100% behavioral parity (AAP &sect;0.1.1,
 * &sect;0.7.1). The cross-cutting COMMAREA, AID/PF-key, and next-program routing helpers are
 * inherited from {@link BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} /
 * {@code EXEC CICS XCTL} equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COBIL00C MAIN-PARA} paint-vs-process split,
 * L107-144).</strong> The single COBOL transaction is split across the two HTTP verbs that the
 * {@code bill-pay} screen submits:
 *
 * <ul>
 *   <li><b>{@code GET /bill-pay}</b> &rarr; {@link #showBillPay(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch (L112-122):
 *       the program context is reset to ENTER and the service builds a fresh {@link BillPayScreen}.
 *       Mirroring the COBOL {@code CDEMO-CB00-TRN-SELECTED} hand-off (L116-121), when the upstream
 *       navigation COMMAREA already carries an account id the controller pre-fills the account-id
 *       field so the service immediately drives the ENTER path and displays that account's balance.
 *   <li><b>{@code POST /bill-pay}</b> &rarr; {@link #doBillPay(BillPayScreen, String, HttpSession,
 *       Model)} is the re-entry process. It mirrors the COBOL {@code ELSE} branch that performs
 *       {@code RECEIVE-BILLPAY-SCREEN} then {@code EVALUATE EIBAID} (L123-142): the program context
 *       is marked re-entry, the submitted attention identifier is resolved, and the service
 *       performs the ENTER / PF3 / PF4 / "other key" dispatch, returning either the next program to
 *       transfer to or {@code null} to redisplay.
 * </ul>
 *
 * <p><strong>No PF-key branching in the controller.</strong> The COBOL {@code EVALUATE EIBAID}
 * actions &mdash; ENTER (read balance / confirm-and-pay), PF3 (return to the caller or {@code
 * COMEN01C}), and PF4 ({@code CLEAR-CURRENT-SCREEN}) &mdash; all live inside {@link BillPayService}
 * and are selected from the resolved {@link CardWorkArea.Aid}. This controller deliberately does
 * not branch on PF keys; it only resolves the key and forwards it.
 *
 * <p><strong>Decimal fidelity.</strong> The current balance is bound as a {@link
 * java.math.BigDecimal} on {@link BillPayScreen}; the controller never performs any arithmetic. The
 * balance read, the payment-transaction posting, and the account-balance update are all owned by
 * the {@code @Transactional} {@link BillPayService} and commit atomically (AAP &sect;0.6.1); no
 * {@code float}/{@code double} is used anywhere on this path.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, and it does not catch
 * the {@code CardDemoException} family &mdash; unrecoverable failures propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class BillPayController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code bill-pay} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COBIL00} / {@code COBIL0A} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_BILL_PAY = "bill-pay";

  /**
   * Model attribute / form-backing-object name bound by the {@code bill-pay} template's {@code
   * th:object}. The {@link BillPayScreen} DTO is the ground-truth contract (AAP &sect;0.4.1): it
   * carries the operator's input fields ({@code actIdIn}, {@code confirm}), the {@code curBal}
   * {@link java.math.BigDecimal} balance, the {@code errMsg} message line, and the screen header
   * fields.
   */
  private static final String ATTR_BILL_PAY_FORM = "billPayForm";

  /**
   * Bill-payment business-logic service migrated from {@code COBIL00C}. Injected by constructor so
   * the dependency is explicit, final, and the controller stays trivially unit-testable with a
   * mocked service.
   */
  private final BillPayService billPayService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param billPayService the bill-payment business-logic service (the migrated {@code COBIL00C}
   *     paragraphs); must not be {@code null}
   */
  public BillPayController(BillPayService billPayService) {
    this.billPayService = billPayService;
  }

  /**
   * Displays the Bill Payment screen on first entry, reproducing the COBOL {@code MAIN-PARA}
   * first-entry paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code COBIL00C} L112-122).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry) and its program context is reset to ENTER. A fresh {@link BillPayScreen} is
   * allocated; when the navigation COMMAREA already carries a selected account id (the upstream
   * hand-off, COBOL {@code CDEMO-CB00-TRN-SELECTED}), the account-id field is pre-filled
   * zero-padded to 11 digits to mirror the COBOL {@code PIC 9(11)} work-area value
   * (normalization/validation remain the service's responsibility). The service is then invoked
   * with {@link CardWorkArea.Aid#ENTER}; on first entry with a pre-filled account id it immediately
   * drives the ENTER path and reads + displays the current balance, otherwise it paints an empty
   * form. The mutated communication area is stored back to the session and the screen is placed on
   * the model for the {@code bill-pay} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code bill-pay} template renders from
   * @return the {@code bill-pay} view name (the BMS {@code SEND-BILLPAY-SCREEN} equivalent)
   */
  @GetMapping("/bill-pay")
  public String showBillPay(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    BillPayScreen screen = new BillPayScreen();
    if (commarea.getAcctId() != null) {
      // CDEMO-CB00-TRN-SELECTED hand-off (COBIL00C L116-121): pre-fill the account id from the
      // upstream selection, zero-padded to 11 to mirror the COBOL PIC 9(11) work-area value. The
      // service normalizes and validates it from here, then reads and displays the balance.
      screen.setActIdIn(String.format("%011d", commarea.getAcctId()));
    }

    billPayService.processBillPay(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_BILL_PAY_FORM, screen);
    return VIEW_BILL_PAY;
  }

  /**
   * Processes a Bill Payment submission on re-entry, reproducing the COBOL {@code MAIN-PARA}
   * re-entry branch that performs {@code RECEIVE-BILLPAY-SCREEN} then {@code EVALUATE EIBAID}
   * ({@code COBIL00C} L123-142).
   *
   * <p>The submitted screen is bound as the {@code billPayForm} object (the {@code RECEIVE}), the
   * program context is marked re-entry, and the raw {@code pfKey} request parameter is resolved to
   * a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the {@code EVALUATE
   * EIBAID} logic &mdash; ENTER (read balance, or confirm-and-pay when {@code confirm = 'Y'}), PF3
   * return-to-caller, PF4 clear, and the "other key" guard &mdash; and returns the next program to
   * transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps,
   *       e.g., the PF3 {@code "COMEN01C"} &rarr; {@code redirect:/menu} or the recorded caller);
   *   <li>{@code null} &rarr; the screen is redisplayed carrying the current balance, the confirm
   *       prompt, an invalid-key message, or a validation/informational message.
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state.
   *
   * @param screen the submitted Bill Payment screen bound from the request form ({@code
   *     billPayForm}), carrying the operator's account id, confirmation flag, and the message line
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / {@code PF4} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the dispatched program, or the {@code bill-pay} view
   *     name to redisplay the screen
   */
  @PostMapping("/bill-pay")
  public String doBillPay(
      @ModelAttribute(ATTR_BILL_PAY_FORM) BillPayScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = billPayService.processBillPay(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A PF3 return target (caller or COMEN01C): the XCTL equivalent, dispatched to the target
      // screen's GET endpoint via the shared program-name -> URL routing table.
      return redirectFor(next);
    }

    // Redisplay: the screen carries the current balance, the confirm prompt, an invalid-key
    // message, or a validation/informational message produced by the service.
    model.addAttribute(ATTR_BILL_PAY_FORM, screen);
    return VIEW_BILL_PAY;
  }
}
