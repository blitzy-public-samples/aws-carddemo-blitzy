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
import com.aws.carddemo.dto.screen.AccountUpdateScreen;
import com.aws.carddemo.service.online.AccountUpdateService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>Account Update</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COACTUPC} (CICS transaction {@code CAUP}, BMS mapset {@code
 * COACTUP} / map {@code CACTUPA}; source {@code legacy/app/cbl/COACTUPC.cbl} + {@code
 * legacy/app/bms/COACTUP.bms}). It is the editable counterpart of the Account View screen: the user
 * looks up an account, edits the account and customer fields, and commits an optimistic update
 * (Agent Action Plan &sect;0.4.1, &sect;0.3.3 optimistic-locking pattern).
 *
 * <p>The controller is a deliberately thin presentation-layer adapter. It owns only the HTTP
 * request/response and the pseudo-conversational {@link CardDemoCommarea} session state (AAP
 * &sect;0.6.5). <strong>All</strong> business logic &mdash; the 24-field validation order, the
 * read-compare-rewrite optimistic update, the PF-key state machine, and every user-facing message
 * &mdash; is delegated to {@link AccountUpdateService}, which reproduces the {@code COACTUPC}
 * paragraphs with 100% behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The service method is
 * {@code @Transactional}, supplying the account+customer all-or-nothing rollback that the COBOL
 * achieved with {@code EXEC CICS SYNCPOINT ROLLBACK}. The cross-cutting COMMAREA, AID/PF-key, and
 * next-program routing helpers are inherited from {@link BaseScreenController} (the modernized
 * {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL} equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COACTUPC MAIN-PARA} paint-vs-process split,
 * L875-985).</strong> The single COBOL transaction is split across the two idempotent HTTP verbs
 * that the {@code account-update} screen submits:
 *
 * <ul>
 *   <li><b>{@code GET /account-update}</b> &rarr; {@link #showAccountUpdate(HttpSession, Model)} is
 *       the first-entry paint. It mirrors the COBOL fresh-entry branch ({@code IF EIBCALEN = 0 OR
 *       (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)}): the program context is
 *       reset to ENTER and the service is invoked with {@link CardWorkArea.Aid#ENTER} so it paints
 *       the blank "enter the account id" prompt and returns {@code null} (no transfer).
 *   <li><b>{@code POST /account-update}</b> &rarr; {@link #doAccountUpdate(AccountUpdateScreen,
 *       String, HttpSession, Model)} is the re-entry process. It mirrors the COBOL re-entry path
 *       that performs {@code RECEIVE MAP} then {@code PERFORM YYYY-STORE-PFKEY} and {@code EVALUATE
 *       TRUE} on the resolved AID: the program context is marked re-entry, the submitted attention
 *       identifier is resolved, and the service runs the full state machine.
 * </ul>
 *
 * <p><strong>PF-key semantics live in the service.</strong> ENTER performs the lookup/validation,
 * PF5 commits the update (and stays on the screen), and PF12/PF3 cancel/return. The controller does
 * <em>not</em> special-case those keys: it resolves the raw {@code pfKey} request parameter to a
 * {@link CardWorkArea.Aid} and passes it through, then either translates the returned next-program
 * name into a redirect (the {@code EXEC CICS XCTL} equivalent &mdash; e.g. PF3 returns {@code
 * "COMEN01C"} &rarr; {@code redirect:/menu}) or re-renders the screen when the service returns
 * {@code null} (validation errors, the "Press F5 to save" confirmation prompt, or the post-update
 * info message all flow through this redisplay path).
 *
 * <p><strong>Optimistic update parity.</strong> Because the JPA entities intentionally carry no
 * {@code @Version} column, the concurrency check is reproduced inside the service: it re-reads the
 * account/customer records on the write path and compares them, field by field, against the
 * snapshot originally shown to the user. A detected concurrent change surfaces as an on-screen
 * message and a {@code null} redisplay; the controller implements no locking of its own.
 *
 * <p><strong>Screen contract.</strong> The {@code account-update} template binds the authoritative
 * {@link AccountUpdateScreen} (the migrated {@code COACTUP}/{@code COACTUP.CPY} field set: split
 * date / SSN / phone groups, the five {@code BigDecimal} monetary balances, the {@code fkeys} /
 * {@code fkey05} / {@code fkey12} legend buffers, and the {@code infoMsg} / {@code errMsg} lines)
 * as the form-backing object {@value #ATTR_ACCOUNT_UPDATE_FORM}. The DTO is the ground-truth
 * contract (AAP &sect;0.4.1); no adapter type is introduced.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, performs no arithmetic
 * (BigDecimal scale/truncation fidelity is a service/entity concern, AAP &sect;0.6.1), and does not
 * catch the {@code CardDemoException} family &mdash; unrecoverable failures propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 *
 * @see com.aws.carddemo.service.online.AccountUpdateService
 * @see com.aws.carddemo.dto.screen.AccountUpdateScreen
 * @see BaseScreenController
 */
@Controller
public class AccountUpdateController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code account-update} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COACTUP} / {@code CACTUPA} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_ACCOUNT_UPDATE = "account-update";

  /**
   * Model attribute / form-backing-object name bound by the {@code account-update} template's
   * {@code th:object}. The template is authoritative: it binds the {@link AccountUpdateScreen}
   * under {@code accountUpdateForm}, carrying the editable account/customer inputs along with the
   * {@code infoMsg} and {@code errMsg} message lines.
   */
  private static final String ATTR_ACCOUNT_UPDATE_FORM = "accountUpdateForm";

  /**
   * Account-update business-logic service migrated from {@code COACTUPC}. Injected by constructor
   * so the dependency is explicit, {@code final}, and the controller stays trivially unit-testable
   * with a mocked service.
   */
  private final AccountUpdateService accountUpdateService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param accountUpdateService the account-update business-logic service (the migrated {@code
   *     COACTUPC} paragraphs, including the 24-field validation and read-compare-rewrite optimistic
   *     update); must not be {@code null}
   */
  public AccountUpdateController(AccountUpdateService accountUpdateService) {
    this.accountUpdateService = accountUpdateService;
  }

  /**
   * Displays the Account Update screen on first entry, reproducing the COBOL {@code MAIN-PARA}
   * fresh-entry paint ({@code COACTUPC} L880-984).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), its program context is reset to ENTER, and the service is invoked with {@link
   * CardWorkArea.Aid#ENTER} so it paints a fresh {@link AccountUpdateScreen} (the blank
   * "enter/update id of account" prompt) and returns {@code null} (paint, no transfer). The mutated
   * communication area is stored back to the session, and the screen is placed on the model for the
   * {@code account-update} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code account-update} template renders from
   * @return the {@code account-update} view name (the BMS {@code SEND MAP} equivalent)
   */
  @GetMapping("/account-update")
  public String showAccountUpdate(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    AccountUpdateScreen screen = new AccountUpdateScreen();
    accountUpdateService.processAccountUpdate(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_ACCOUNT_UPDATE_FORM, screen);
    return VIEW_ACCOUNT_UPDATE;
  }

  /**
   * Processes an Account Update submission on re-entry, reproducing the COBOL {@code MAIN-PARA}
   * re-entry path that performs {@code RECEIVE MAP}, {@code PERFORM YYYY-STORE-PFKEY}, and {@code
   * EVALUATE TRUE} on the resolved AID ({@code COACTUPC} L885-985).
   *
   * <p>The submitted screen is bound as the {@value #ATTR_ACCOUNT_UPDATE_FORM} object (the {@code
   * RECEIVE}), the program context is marked re-entry, and the raw {@code pfKey} request parameter
   * is resolved to a {@link CardWorkArea.Aid} (ENTER by default). The service then runs the full
   * state machine &mdash; lookup/validate on ENTER, commit on PF5, cancel/return on PF12/PF3
   * &mdash; and returns the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps,
   *       e.g., the PF3 {@code "COMEN01C"} &rarr; {@code redirect:/menu});
   *   <li>{@code null} &rarr; the screen is redisplayed carrying any validation {@code errMsg}, the
   *       "Changes validated.Press F5 to save" confirmation prompt, the post-update success info
   *       message, or a concurrency-conflict message.
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state. The controller passes
   * the resolved AID straight through and does not special-case PF5/PF12 beyond translating the
   * returned program name.
   *
   * @param screen the submitted account-update screen bound from the request form ({@value
   *     #ATTR_ACCOUNT_UPDATE_FORM}), carrying the operator's account id and edited field values
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF5} / {@code PF12} / {@code PF3} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the transferred program, or the {@code
   *     account-update} view name to redisplay the screen
   */
  @PostMapping("/account-update")
  public String doAccountUpdate(
      @ModelAttribute(ATTR_ACCOUNT_UPDATE_FORM) AccountUpdateScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = accountUpdateService.processAccountUpdate(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A returned program name (e.g. PF3 -> COMEN01C): the XCTL equivalent, dispatched to the
      // target screen's GET endpoint via the shared program-name -> URL routing table.
      return redirectFor(next);
    }

    // Redisplay: validation errors, the "Press F5 to save" confirmation prompt, the post-update
    // info message, or a concurrency-conflict message all travel on the bound screen.
    model.addAttribute(ATTR_ACCOUNT_UPDATE_FORM, screen);
    return VIEW_ACCOUNT_UPDATE;
  }
}
