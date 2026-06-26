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
import com.aws.carddemo.dto.screen.ReportScreen;
import com.aws.carddemo.service.online.ReportService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Spring MVC controller for the CardDemo <strong>Transaction Report</strong> submission screen,
 * migrated from the legacy CICS COBOL program {@code CORPT00C} (CICS transaction {@code CR00}, BMS
 * mapset {@code CORPT00} / map {@code CORPT0A}; source {@code legacy/app/cbl/CORPT00C.cbl} + {@code
 * legacy/app/bms/CORPT00.bms}).
 *
 * <p>This is the one online transaction whose function is to <em>submit a batch job</em>: the
 * operator chooses a report scope &mdash; current-month (Monthly), current-year (Yearly), or a
 * Custom start/end date range &mdash; and confirms, which launches the transaction-detail report
 * job (Agent Action Plan &sect;0.4.1 Report row, &sect;0.3.3 "online submit triggers batch"). In
 * the legacy system {@code CORPT00C} built an in-line JCL deck and wrote it to the {@code JOBS}
 * extra-partition transient-data queue (the z/OS internal reader) to submit the {@code TRANREPT}
 * job; in the modernized stack that submission is replaced by a Spring Batch {@code
 * JobLauncher.run(...)} of the {@code transactionReportJob}.
 *
 * <p><strong>The batch trigger lives entirely in the service, not here.</strong> This controller is
 * a thin presentation-layer adapter that owns only the HTTP request/response and the
 * pseudo-conversational {@link CardDemoCommarea} session state (AAP &sect;0.6.5). All business
 * logic &mdash; report-type resolution, date-range validation (via the shared {@code DateValidation
 * Service}), the {@code CONFIRM} ({@code Y}/{@code N}) handling, and the actual launch of the
 * Spring Batch job &mdash; is delegated to {@link ReportService}, which reproduces the {@code
 * CORPT00C} paragraphs with 100% behavioral parity (AAP &sect;0.1.1, &sect;0.7.1). Critically, the
 * controller does <em>not</em> import or reference any batch type ({@code JobLauncher} / {@code
 * Job}); the batch dependency ({@code batch/config/TransactionReportJobConfig}) is encapsulated as
 * a service-layer concern, preserving strict {@code web -> service} layering. The cross-cutting
 * COMMAREA, AID/PF-key, and next-program routing helpers are inherited from {@link
 * BaseScreenController} (the modernized {@code RECEIVE} / {@code SEND} / {@code EXEC CICS XCTL}
 * equivalents).
 *
 * <p><strong>Pseudo-conversational mapping (the {@code CORPT00C MAIN-PARA} paint-vs-process split,
 * L163-202).</strong> The single COBOL transaction is split across the two HTTP verbs that the
 * {@code report} screen submits:
 *
 * <ul>
 *   <li><b>{@code GET /report}</b> &rarr; {@link #showReport(HttpSession, Model)} is the
 *       first-entry paint. It mirrors the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch (L177-181):
 *       the program context is reset to ENTER and the service paints a fresh, empty {@link
 *       ReportScreen} (the COBOL {@code MOVE LOW-VALUES TO CORPT0AO} blank output map). Unlike the
 *       record-bearing screens, the report transaction has <em>no selected-record hand-off</em>, so
 *       a plain {@code new ReportScreen()} is built with no pre-filled fields.
 *   <li><b>{@code POST /report}</b> &rarr; {@link #doReport(ReportScreen, String, HttpSession,
 *       Model)} is the re-entry process. It mirrors the COBOL {@code ELSE} branch that performs
 *       {@code RECEIVE-TRNRPT-SCREEN} then {@code EVALUATE EIBAID} (L182-197): the program context
 *       is marked re-entry, the submitted attention identifier is resolved, and the service
 *       performs the ENTER (validate + submit job) / PF3 (return to {@code COMEN01C}) / "other key"
 *       dispatch, returning either the next program to transfer to or {@code null} to redisplay.
 * </ul>
 *
 * <p><strong>No PF-key branching in the controller.</strong> The COBOL {@code EVALUATE EIBAID}
 * actions &mdash; ENTER (resolve report type, validate dates, submit the batch job), PF3 (transfer
 * to {@code COMEN01C}), and the "other key" invalid-key guard &mdash; all live inside {@link
 * ReportService} and are selected from the resolved {@link CardWorkArea.Aid}. This controller
 * deliberately does not branch on PF keys; it only resolves the key and forwards it.
 *
 * <p><strong>Form binding.</strong> The authoritative {@link ReportScreen} DTO (AAP &sect;0.4.1) is
 * the ground-truth field contract bound under the {@code reportForm} model attribute: the
 * mutually-exclusive {@code monthly} / {@code yearly} / {@code custom} selectors, the split
 * start-date ({@code sdtMm} / {@code sdtDd} / {@code sdtYyyy}) and end-date ({@code edtMm} / {@code
 * edtDd} / {@code edtYyyy}) components, the {@code confirm} flag, the {@code errMsg} message line,
 * and the screen header fields. No field adapters are introduced &mdash; the DTO names are used
 * verbatim.
 *
 * <p><strong>Strict layering.</strong> This type depends only on the {@code dto} package, the
 * {@code service.online} service, the Spring web stereotypes/annotations, and the servlet {@link
 * HttpSession}. It references no domain, repository, persistence, or batch types, and it does not
 * catch the {@code CardDemoException} family &mdash; unrecoverable failures propagate to the
 * application-wide exception handler (the modernized abend path, AAP &sect;0.6.6).
 */
@Controller
public class ReportController extends BaseScreenController {

  /**
   * Logical view name resolved to the {@code report} Thymeleaf template &mdash; the web equivalent
   * of the BMS {@code CORPT00} / {@code CORPT0A} map. Used for both the first-entry paint and the
   * post-process redisplay.
   */
  private static final String VIEW_REPORT = "report";

  /**
   * Model attribute / form-backing-object name bound by the {@code report} template's {@code
   * th:object}. The {@link ReportScreen} DTO is the ground-truth contract (AAP &sect;0.4.1): it
   * carries the report-type selectors ({@code monthly}, {@code yearly}, {@code custom}), the split
   * start/end date components, the {@code confirm} flag, the {@code errMsg} message line, and the
   * screen header fields.
   */
  private static final String ATTR_REPORT_FORM = "reportForm";

  /**
   * Transaction-report business-logic service migrated from {@code CORPT00C}. Injected by
   * constructor so the dependency is explicit, final, and the controller stays trivially
   * unit-testable with a mocked service. The Spring Batch wiring ({@code JobLauncher} + {@code
   * transactionReportJob}) is encapsulated inside this service and is intentionally not visible to
   * the controller.
   */
  private final ReportService reportService;

  /**
   * Creates the controller with its collaborating service.
   *
   * @param reportService the transaction-report business-logic service (the migrated {@code
   *     CORPT00C} paragraphs, including the Spring Batch job launch); must not be {@code null}
   */
  public ReportController(ReportService reportService) {
    this.reportService = reportService;
  }

  /**
   * Displays the Transaction Report screen on first entry, reproducing the COBOL {@code MAIN-PARA}
   * first-entry paint ({@code IF NOT CDEMO-PGM-REENTER}, {@code CORPT00C} L177-181).
   *
   * <p>The communication area is fetched (created on first request, mirroring the empty-COMMAREA
   * first entry) and its program context is reset to ENTER. A fresh {@link ReportScreen} is
   * allocated with no pre-filled fields &mdash; the report transaction has no selected-record
   * hand-off &mdash; and the service is invoked with {@link CardWorkArea.Aid#ENTER}, which paints
   * the empty report-options form (the COBOL {@code MOVE LOW-VALUES TO CORPT0AO}). The mutated
   * communication area is stored back to the session and the screen is placed on the model for the
   * {@code report} template.
   *
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model the {@code report} template renders from
   * @return the {@code report} view name (the BMS {@code SEND-TRNRPT-SCREEN} equivalent)
   */
  @GetMapping("/report")
  public String showReport(HttpSession session, Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmEnter();

    ReportScreen screen = new ReportScreen();
    reportService.processReport(screen, commarea, CardWorkArea.Aid.ENTER);

    storeCommarea(session, commarea);

    model.addAttribute(ATTR_REPORT_FORM, screen);
    return VIEW_REPORT;
  }

  /**
   * Processes a Transaction Report submission on re-entry, reproducing the COBOL {@code MAIN-PARA}
   * re-entry branch that performs {@code RECEIVE-TRNRPT-SCREEN} then {@code EVALUATE EIBAID}
   * ({@code CORPT00C} L182-197).
   *
   * <p>The submitted screen is bound as the {@code reportForm} object (the {@code RECEIVE}), the
   * program context is marked re-entry, and the raw {@code pfKey} request parameter is resolved to
   * a {@link CardWorkArea.Aid} (ENTER by default). The service then performs the {@code EVALUATE
   * EIBAID} logic &mdash; ENTER (resolve the report type, validate the date range, and on
   * confirmation launch the report batch job), PF3 transfer to the main menu, and the "other key"
   * invalid-key guard &mdash; and returns the next program to transfer to:
   *
   * <ul>
   *   <li>a non-{@code null} program name &rarr; the controller issues the Spring redirect that
   *       replaces {@code EXEC CICS XCTL} ({@link BaseScreenController#redirectFor(String)} maps
   *       the PF3 {@code "COMEN01C"} &rarr; {@code redirect:/menu});
   *   <li>{@code null} &rarr; the screen is redisplayed carrying a validation error, an invalid-key
   *       message, the confirmation prompt, or the "report submitted for printing" confirmation
   *       produced by the service.
   * </ul>
   *
   * <p>The mutated communication area is always stored back to the session before the response is
   * produced, so the next request observes the updated navigation/role state.
   *
   * @param screen the submitted Transaction Report screen bound from the request form ({@code
   *     reportForm}), carrying the report-type selectors, date components, confirmation flag, and
   *     the message line
   * @param pfKey the raw attention-identifier token submitted by the screen ({@code ENTER} / {@code
   *     PF3} / ...); {@code null} or blank is treated as ENTER
   * @param session the HTTP session standing in for the pseudo-conversational COMMAREA
   * @param model the Spring MVC model used when the screen is redisplayed
   * @return a {@code redirect:} view name for the dispatched program, or the {@code report} view
   *     name to redisplay the screen
   */
  @PostMapping("/report")
  public String doReport(
      @ModelAttribute(ATTR_REPORT_FORM) ReportScreen screen,
      @RequestParam(value = "pfKey", required = false) String pfKey,
      HttpSession session,
      Model model) {
    CardDemoCommarea commarea = getCommarea(session);
    commarea.setPgmReenter();

    CardWorkArea.Aid aid = resolveAid(pfKey);
    String next = reportService.processReport(screen, commarea, aid);

    storeCommarea(session, commarea);

    if (next != null) {
      // A PF3 return target (COMEN01C): the XCTL equivalent, dispatched to the target screen's GET
      // endpoint via the shared program-name -> URL routing table.
      return redirectFor(next);
    }

    // Redisplay: the screen carries a validation error, an invalid-key message, the confirmation
    // prompt, or the "report submitted for printing" confirmation produced by the service.
    model.addAttribute(ATTR_REPORT_FORM, screen);
    return VIEW_REPORT;
  }
}
