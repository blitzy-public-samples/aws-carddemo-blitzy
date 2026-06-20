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
import com.aws.carddemo.dto.screen.AccountViewScreen;
import com.aws.carddemo.service.online.AccountViewService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>account-view</strong> screen, migrated from the
 * legacy CICS COBOL program {@code COACTVWC} (CICS transaction {@code CAVW}, BMS mapset {@code
 * COACTVW}; source {@code legacy/app/cbl/COACTVWC.cbl} + {@code legacy/app/bms/COACTVW.bms}).
 *
 * <p>The controller is a thin presentation-layer adapter that owns only the HTTP request/response
 * and the pseudo-conversational {@link CardDemoCommarea} session state (Agent Action Plan
 * &sect;0.6.5). It is a strictly <strong>read-only</strong> transaction: the operator types an
 * account number and the screen is painted with that account's detail. All business logic &mdash;
 * the account-filter validation and the keyed card-cross-reference &rarr; account-master &rarr;
 * customer-master read chain, together with the {@link java.math.BigDecimal} rendering of the
 * monetary fields &mdash; is delegated to {@link AccountViewService}, which reproduces the {@code
 * COACTVWC} numbered paragraphs with 100% behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). The
 * cross-cutting COMMAREA, AID/PF-key, and next-program routing helpers are inherited from {@link
 * BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL}
 * equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code COACTVWC 0000-MAIN} paint-vs-process split,
 * L262-410).</strong> The single COBOL transaction is split across the two HTTP verbs that the
 * {@code account-view} screen submits, mirroring the COBOL {@code CDEMO-PGM-ENTER} vs {@code
 * CDEMO-PGM-REENTER} branches:
 *
 * <ul>
 *   <li><b>{@code GET /account-view}</b> &rarr; {@link #showAccountView(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL {@code WHEN CDEMO-PGM-ENTER} branch (L353-360): the
 *       program context is reset to ENTER and the service is invoked with {@link
 *       CardWorkArea.Aid#ENTER}, so it clears the filter, shows the input prompt, and returns
 *       {@code null} (paint, no navigation).
 *   <li><b>{@code POST /account-view}</b> &rarr; {@link #doAccountView(AccountViewScreen, String,
 *       HttpSession, Model)} is the re-entry process. It mirrors the COBOL {@code WHEN
 *       CDEMO-PGM-REENTER} branch (L361-374) and the PF3 exit ({@code WHEN CCARD-AID-PFK03},
 *       L324-352): the program context is marked re-entry, the submitted attention identifier is
 *       resolved, and the service either returns the next program to transfer to (PF3 &rarr; {@code
 *       COMEN01C}, the {@code EXEC CICS XCTL} that never returns) or {@code null} to redisplay the
 *       screen carrying the painted detail or an error / not-found message.
 * </ul>
 *
 * <p><strong>Template &harr; DTO contract.</strong> The authoritative {@link AccountViewScreen} DTO
 * is bound as {@code accountViewForm}; it is the ground-truth contract (AAP &sect;0.4.1) and is
 * never wrapped in an adapter. Its field names follow the COBOL abbreviations of {@code COACTVW}
 * (for example {@code acstTus}, {@code acrdLim}, {@code acurBal}); the {@code account-view}
 * Thymeleaf template may expose friendlier labels, but the DTO names are canonical. The five
 * monetary balance fields &mdash; {@code acrdLim}, {@code acshLim}, {@code acurBal}, {@code
 * acrCycr}, and {@code acrCydb} &mdash; are {@link java.math.BigDecimal} to preserve COBOL
 * fixed-point decimal fidelity end-to-end (AAP &sect;0.6.1); no {@code float} / {@code double} is
 * used anywhere in the chain.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, or persistence types, and it does not catch
 * the {@code CardDemoException} family. In this read-only view the ordinary record-not-found case
 * is a screen redisplay (the service paints the not-found message and returns {@code null}); only
 * an unexpected failure propagates &mdash; as an {@code IoStatusException} / {@code
 * RecordNotFoundException} &mdash; to the application-wide {@code GlobalExceptionHandler} (the
 * modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class AccountViewController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code account-view} Thymeleaf template &mdash; the web
   * equivalent of the BMS {@code COACTVW} / {@code CACTVWA} map. Used for both the first-entry
   * paint and the post-process redisplay.
   */
  private static final String VIEW_ACCOUNT_VIEW = "account-view";

  /**
   * Model attribute / form-backing-object name bound by the {@code account-view} template's {@code
   * th:object}. The template is authoritative: it binds the {@link AccountViewScreen} under {@code
   * accountViewForm} (carrying the operator {@code acctSid} filter, the painted account/customer
   * detail, and the {@code infoMsg} / {@code errMsg} lines).
   */
  private static final String ATTR_ACCOUNT_VIEW_FORM = "accountViewForm";

  /**
   * Account-view business-logic service migrated from {@code COACTVWC}. Injected by constructor so
   * the dependency is explicit, final, and the controller stays trivially unit-testable with a
   * mocked service.
   */
  private final AccountViewService accountViewService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param accountViewService the account-view business-logic service (the migrated {@code
   *     COACTVWC} paragraphs); must not be {@code null}
   */
  public AccountViewController(AccountViewService accountViewService) {
    this.accountViewService = accountViewService;
  }

  /**
   * Displays the account-view screen on first entry, reproducing the COBOL {@code 0000-MAIN}
   * first-entry paint ({@code WHEN CDEMO-PGM-ENTER}, {@code COACTVWC} L353-360 &rarr; {@code
   * 1000-SEND-MAP}).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry), its program context is reset to ENTER, and the service is invoked with {@link
   * CardWorkArea.Aid#ENTER} so it prepares a fresh {@link AccountViewScreen} with a blank account
   * filter and the input prompt, returning {@code null} (paint, no navigation). When the inbound
   * communication area already carries an account id from a prior context, the service may
   * pre-display it &mdash; that decision is the service's, per the COBOL. The mutated communication
   * area is stored back to the session, and the screen is placed on the model for the {@code
   * account-view} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code account-view} template renders from
   * @return the {@code account-view} view name (the BMS {@code SEND-MAP} equivalent)
   */
  @GetMapping("/account-view")
  public String showAccountView(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    AccountViewScreen screen = new AccountViewScreen();
    accountViewService.processAccountView(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_ACCOUNT_VIEW_FORM, screen);
    return VIEW_ACCOUNT_VIEW;
  }

  /**
   * Processes an account-view submission on re-entry, reproducing the COBOL {@code 0000-MAIN}
   * re-entry branch ({@code WHEN CDEMO-PGM-REENTER}, {@code COACTVWC} L361-374) and the PF3 exit
   * ({@code WHEN CCARD-AID-PFK03}, L324-352).
   *
   * <p>The submitted screen is bound as the {@code accountViewForm} object (the {@code RECEIVE}),
   * the program context is marked re-entry, and the raw {@code pfKey} request parameter is resolved
   * to a {@link CardWorkArea.Aid} (ENTER by default). The service then validates the account filter
   * and, when valid, performs the cross-reference &rarr; account &rarr; customer read chain,
   * returning the next program to route to:
   *
   * <ul>
   *   <li>a non-{@code null} program name (PF3 &rarr; {@code COMEN01C}) &rarr; the controller
   *       issues the Spring redirect that replaces {@code EXEC CICS XCTL} ({@link
   *       BaseScreenController#redirectFor(String)} maps {@code "COMEN01C"} &rarr; {@code
   *       redirect:/menu});
   *   <li>{@code null} &rarr; the screen is redisplayed carrying the painted account/customer
   *       detail or a validation / not-found message in {@code errMsg}.
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state.
   *
   * @param screen the submitted account-view screen bound from the request form ({@code
   *     accountViewForm}), carrying the operator's raw {@code acctSid} account filter
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the next program on PF3, or the {@code account-view}
   *     view name to redisplay the screen
   */
  @PostMapping("/account-view")
  public String doAccountView(
      @ModelAttribute(ATTR_ACCOUNT_VIEW_FORM) AccountViewScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = accountViewService.processAccountView(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // PF3 exit -> COMEN01C: the XCTL equivalent, dispatched to the target screen's GET endpoint
      // via the shared program-name -> URL routing table (here COMEN01C -> redirect:/menu).
      return redirectFor(next);
    }

    // Redisplay: the screen carries the painted account/customer detail (valid read) or a
    // validation / not-found message in errMsg (the COBOL 1000-SEND-MAP redisplay path).
    model.addAttribute(ATTR_ACCOUNT_VIEW_FORM, screen);
    return VIEW_ACCOUNT_VIEW;
  }
}
