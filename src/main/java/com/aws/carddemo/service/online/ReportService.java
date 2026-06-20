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
package com.aws.carddemo.service.online;

import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.ReportScreen;
import com.aws.carddemo.util.DateValidationService;
import com.aws.carddemo.util.Messages;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Online <strong>transaction-report submission</strong> business-logic service, migrated from the
 * legacy CICS COBOL program {@code CORPT00C} (CICS transaction {@code CR00}; source {@code
 * legacy/app/cbl/CORPT00C.cbl}).
 *
 * <p>This service reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.1,
 * &sect;0.7.1) &mdash; the pseudo-conversational "Print Transaction reports by submitting a batch
 * job from online" flow: the operator selects a report type (Monthly / Yearly / Custom date range),
 * the date range is validated, and on confirmation ({@code Y}) the transaction-detail report job is
 * launched. The translation is paragraph-faithful: each COBOL {@code PROCEDURE DIVISION} paragraph
 * becomes one private method invoked in the original perform/branch order (AAP &sect;0.6.7).
 *
 * <p><strong>TDQ submission &rarr; Spring Batch launch.</strong> In the legacy system {@code
 * CORPT00C} builds an in-line JCL deck ({@code JOB-DATA}) embedding {@code PARM-START-DATE}/{@code
 * PARM-END-DATE} and writes it line-by-line to the {@code JOBS} extra-partition transient-data
 * queue (the z/OS internal reader) to submit the {@code TRANREPT} batch job ({@code legacy/app/jcl
 * /TRANREPT.jcl}). In the modernized stack the TDQ write is replaced by a Spring Batch job launch
 * via {@link JobLauncher#run(Job, JobParameters)} against the {@code transactionReportJob} bean
 * defined by {@code com.aws.carddemo.batch.config.TransactionReportJobConfig} (AAP &sect;0.4.1
 * Report row, &sect;0.3.3). The two JCL date constants become the {@code startDate}/{@code endDate}
 * job parameters (AAP &sect;0.1.1: JCL {@code PARM} &rarr; {@code JobParameter}).
 *
 * <p><strong>Batch coordination.</strong> The application sets {@code spring.batch.job.enabled
 * =false} so batch jobs do <em>not</em> auto-run on context startup; this service triggers the
 * report job on demand. The service does <em>not</em> consume the {@code dto/report} DTOs and does
 * <em>not</em> render any report lines &mdash; the report content is produced entirely by the batch
 * layer (mirroring {@code CBTRN03C} / {@code TRANREPT}). This service only validates the on-screen
 * input and launches the job.
 *
 * <p>The {@link com.aws.carddemo.web web} controller layer (sibling {@code ReportController}) owns
 * the HTTP request/response, the {@link CardDemoCommarea} session state, BMS-equivalent screen
 * rendering ({@code EXEC CICS SEND}/{@code RECEIVE}), header population, cursor placement, screen
 * colouring, and the resolution of the raw attention identifier into a {@link CardWorkArea.Aid}.
 * This service is invoked with those already-resolved inputs and returns the next program to route
 * to (the {@code EXEC CICS XCTL} target) or {@code null} to redisplay the report screen.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7):
 *
 * <ul>
 *   <li>{@code MAIN-PARA} (L163-202) &rarr; {@link #processReport(ReportScreen, CardDemoCommarea,
 *       CardWorkArea.Aid)}
 *   <li>{@code PROCESS-ENTER-KEY} (L208-456) &rarr; {@link #processEnterKey(ReportScreen,
 *       CardDemoCommarea)}
 *   <li>{@code SUBMIT-JOB-TO-INTRDR} (L462-510) &rarr; {@link #submitJobToIntrdr(ReportScreen,
 *       String, String, String)}
 *   <li>{@code WIRTE-JOBSUB-TDQ} + the JCL-build loop (L498-535) &rarr; {@link
 *       #launchTransactionReportJob(ReportScreen, String, String, String)}
 *   <li>{@code RETURN-TO-PREV-SCREEN} (L540-551) &rarr; {@link
 *       #returnToPrevScreen(CardDemoCommarea)}
 *   <li>{@code INITIALIZE-ALL-FIELDS} (L633-646) &rarr; {@link #initializeAllFields(ReportScreen)}
 *   <li>{@code SEND-TRNRPT-SCREEN} / {@code RECEIVE-TRNRPT-SCREEN} / {@code POPULATE-HEADER-INFO}
 *       (L556-628) &rarr; controller/view-owned (HTTP form binding + BMS render); not modeled here
 * </ul>
 *
 * <p><strong>Parity notes.</strong>
 *
 * <ul>
 *   <li><b>Short-circuit on first error.</b> In {@code CORPT00C} {@code SEND-TRNRPT-SCREEN}
 *       performs {@code GO TO RETURN-TO-CICS}, so the <em>first</em> failing validation ends the
 *       pass. That is reproduced by returning {@code null} immediately after the first error
 *       message is set, preserving the exact COBOL check order.
 *   <li><b>No abend path.</b> {@code CORPT00C} has no {@code 9999-ABEND-PROGRAM}; the only I/O
 *       failure (the TDQ write) sets an on-screen message and redisplays. The migrated job-launch
 *       helper therefore catches {@link JobExecutionException}, sets the byte-faithful {@link
 *       #MSG_UNABLE_WRITE_TDQ} message, and signals the caller to redisplay &mdash; it never
 *       throws.
 *   <li><b>Cursor and colour.</b> The COBOL {@code MOVE -1 TO ...L} cursor moves and the {@code
 *       MOVE DFHGREEN TO ERRMSGC} colour change are 3270/BMS rendering concerns. The shared {@link
 *       ReportScreen} DTO exposes no cursor or colour attribute, so both are owned by the
 *       controller and are intentionally not modeled here.
 *   <li><b>No mutable state.</b> All collaborators are {@code private final} and the service holds
 *       no other instance state (state is carried by {@link CardDemoCommarea} and {@link
 *       ReportScreen}), so it is safe as a Spring singleton.
 * </ul>
 */
@Service
public class ReportService {

  /** CICS transaction id of this program ({@code WS-TRANID}, {@code CORPT00C} L38). */
  static final String TRAN_ID = "CR00";

  /** Program name of this program ({@code WS-PGMNAME}, {@code CORPT00C} L37). */
  static final String PGM_NAME = "CORPT00C";

  /**
   * Sign-on program routed to when the COMMAREA is uninitialized (the {@code EIBCALEN = 0} guard,
   * L172-174) and the default {@code CDEMO-TO-PROGRAM} in {@code RETURN-TO-PREV-SCREEN} (L542-544).
   */
  static final String LIT_SIGNON_PGM = "COSGN00C";

  /** Main-menu program routed to on PF3 ({@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM}, L188). */
  static final String LIT_MENU_PGM = "COMEN01C";

  /** Report-name literal for the current-month report ({@code MOVE 'Monthly' ...}, L214). */
  static final String REPORT_NAME_MONTHLY = "Monthly";

  /** Report-name literal for the current-year report ({@code MOVE 'Yearly' ...}, L240). */
  static final String REPORT_NAME_YEARLY = "Yearly";

  /** Report-name literal for the custom date-range report ({@code MOVE 'Custom' ...}, L433). */
  static final String REPORT_NAME_CUSTOM = "Custom";

  /**
   * COBOL date picture mask passed to the date validator ({@code WS-DATE-FORMAT VALUE
   * 'YYYY-MM-DD'}, L72). This is the COBOL mask (not a {@link DateTimeFormatter} pattern); {@link
   * DateValidationService} translates it internally.
   */
  static final String DATE_FORMAT_MASK = "YYYY-MM-DD";

  /** {@code 'Start Date - Month can NOT be empty...'} (L261). */
  static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

  /** {@code 'Start Date - Day can NOT be empty...'} (L268). */
  static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

  /** {@code 'Start Date - Year can NOT be empty...'} (L275). */
  static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

  /** {@code 'End Date - Month can NOT be empty...'} (L282). */
  static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

  /** {@code 'End Date - Day can NOT be empty...'} (L289). */
  static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

  /** {@code 'End Date - Year can NOT be empty...'} (L296). */
  static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

  /** {@code 'Start Date - Not a valid Month...'} (L331). */
  static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

  /** {@code 'Start Date - Not a valid Day...'} (L340). */
  static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

  /** {@code 'Start Date - Not a valid Year...'} (L348). */
  static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

  /** {@code 'End Date - Not a valid Month...'} (L357). */
  static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

  /** {@code 'End Date - Not a valid Day...'} (L366). */
  static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

  /** {@code 'End Date - Not a valid Year...'} (L374). */
  static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

  /** {@code 'Start Date - Not a valid date...'} (L400). */
  static final String MSG_START_NOT_VALID_DATE = "Start Date - Not a valid date...";

  /** {@code 'End Date - Not a valid date...'} (L420). */
  static final String MSG_END_NOT_VALID_DATE = "End Date - Not a valid date...";

  /** {@code 'Select a report type to print report...'} (L438). */
  static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

  /**
   * Prefix of the confirmation prompt built by the COBOL {@code STRING} statement (L465-470):
   * {@code 'Please confirm to print the '} (trailing space, {@code DELIMITED BY SIZE}). The trimmed
   * report name and {@link #MSG_CONFIRM_PRINT_SUFFIX} are appended at run time.
   */
  static final String MSG_CONFIRM_PRINT_PREFIX = "Please confirm to print the ";

  /** Suffix of the confirmation prompt ({@code ' report...'}, leading space, L469). */
  static final String MSG_CONFIRM_PRINT_SUFFIX = " report...";

  /**
   * Suffix of the invalid-confirmation message built by the COBOL {@code STRING} statement
   * (L485-489): {@code '" is not a valid value to confirm...'}. The run-time message is {@code '"'}
   * + the confirm value ({@code DELIMITED BY SPACE}) + this suffix.
   */
  static final String MSG_INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

  /**
   * Suffix of the success message built by the COBOL {@code STRING} statement (L449-451): {@code '
   * report submitted for printing ...'} (leading space; {@code DELIMITED BY SIZE}). The run-time
   * message is the trimmed report name + this suffix.
   */
  static final String MSG_SUBMIT_SUCCESS_SUFFIX = " report submitted for printing ...";

  /**
   * Submission-failure message ({@code 'Unable to Write TDQ (JOBS)...'}, L531). In the legacy
   * program this is shown when the {@code EXEC CICS WRITEQ TD} to the {@code JOBS} queue fails; in
   * the modernized stack it is shown when the Spring Batch job launch fails (see {@link
   * #launchTransactionReportJob(ReportScreen, String, String, String)}).
   */
  static final String MSG_UNABLE_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

  /**
   * Spring Batch job-parameter key for the inclusive report start date ({@code YYYY-MM-DD}); the
   * faithful counterpart of the JCL {@code PARM-START-DATE} deck value.
   */
  static final String PARAM_START_DATE = "startDate";

  /**
   * Spring Batch job-parameter key for the inclusive report end date ({@code YYYY-MM-DD}); the
   * faithful counterpart of the JCL {@code PARM-END-DATE} deck value.
   */
  static final String PARAM_END_DATE = "endDate";

  /**
   * Spring Batch job-parameter key carrying the selected report name (Monthly / Yearly / Custom).
   */
  static final String PARAM_REPORT_NAME = "reportName";

  /**
   * Spring Batch identifying job-parameter key carrying the submission instant. It guarantees a
   * unique {@code JobInstance} per submission so the same date range can be re-submitted (the
   * legacy program could re-submit the deck at will).
   */
  static final String PARAM_REQUESTED_AT = "requestedAt";

  /** Highest valid month value ({@code SDTMMI > '12'} / {@code EDTMMI > '12'}, L330/L356). */
  private static final int MAX_MONTH = 12;

  /** Highest valid day value ({@code SDTDDI > '31'} / {@code EDTDDI > '31'}, L339/L365). */
  private static final int MAX_DAY = 31;

  /**
   * Formatter producing the {@code YYYY-MM-DD} string for the computed Monthly/Yearly ranges,
   * matching the COBOL {@code WS-START-DATE}/{@code WS-END-DATE} layout (year(4)-month(2)-day(2)).
   */
  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  /**
   * Spring Batch launcher used to submit the transaction-report job, replacing the legacy {@code
   * EXEC CICS WRITEQ TD QUEUE('JOBS')} internal-reader submission.
   */
  private final JobLauncher jobLauncher;

  /**
   * The transaction-detail-report job ({@code transactionReportJob} bean defined by {@code
   * TransactionReportJobConfig}); the modernized counterpart of the {@code TRANREPT} JCL job that
   * {@code CORPT00C} submits.
   */
  private final Job transactionReportJob;

  /**
   * Creates the report service with its Spring Batch collaborators.
   *
   * <p>Constructor injection is used (a single constructor, so {@code @Autowired} is not required);
   * both collaborators are stored in {@code private final} fields and the service holds no other
   * mutable state, so it is safe as a Spring singleton. {@link DateValidationService} is a static,
   * stateless utility and is therefore <em>not</em> injected.
   *
   * @param jobLauncher the Spring Batch job launcher (Boot auto-configured); must not be {@code
   *     null}
   * @param transactionReportJob the transaction-report job bean, qualified by name {@code
   *     transactionReportJob}; must not be {@code null}
   */
  public ReportService(
      JobLauncher jobLauncher, @Qualifier("transactionReportJob") Job transactionReportJob) {
    this.jobLauncher = jobLauncher;
    this.transactionReportJob = transactionReportJob;
  }

  /**
   * Processes one report-screen interaction, reproducing {@code CORPT00C MAIN-PARA} (L163-202).
   *
   * <p>The error flag and message are reset first (L165-170). The COBOL {@code EIBCALEN = 0} guard
   * (L172-174) &mdash; a program reached with no COMMAREA &mdash; is modeled by treating a blank
   * {@link CardDemoCommarea#getUserId() user id} as an uninitialized state (the established
   * convention across the online services): the target program is set to {@link #LIT_SIGNON_PGM}
   * and control returns to the previous screen.
   *
   * <p>On the first entry (the COBOL {@code IF NOT CDEMO-PGM-REENTER} branch, L177-181) the
   * re-enter flag is set and the screen is (re)displayed blank. Blanking the BMS output map ({@code
   * MOVE LOW-VALUES TO CORPT0AO}) and the cursor placement ({@code MOVE -1 TO MONTHLYL}) are
   * controller/rendering concerns; there is no selected-record handoff for {@code CR00}, so the
   * service simply marks the re-enter state and redisplays (returns {@code null}).
   *
   * <p>On a re-entry the method branches on the attention identifier exactly as the COBOL {@code
   * EVALUATE EIBAID} (L184-195): {@code ENTER} delegates to {@link #processEnterKey(ReportScreen,
   * CardDemoCommarea)}; {@code PF3} transfers to the main menu; any other key sets the shared
   * invalid-key message and redisplays.
   *
   * @param screen the report screen contract carrying the operator's input (report type, date
   *     components, confirmation) and receiving any error/confirmation message; must not be {@code
   *     null}
   * @param commarea the pseudo-conversational session/navigation state; must not be {@code null}
   * @param aid the resolved attention identifier (PF/ENTER key), or {@code null} if the raw key did
   *     not map to a known {@link CardWorkArea.Aid}
   * @return the program name to dispatch to (the {@code EXEC CICS XCTL} target, e.g. {@code
   *     "COMEN01C"} or {@code "COSGN00C"}), or {@code null} to redisplay the report screen
   * @throws NullPointerException if {@code screen} or {@code commarea} is {@code null}
   */
  public String processReport(
      ReportScreen screen, CardDemoCommarea commarea, CardWorkArea.Aid aid) {
    Objects.requireNonNull(screen, "screen must not be null");
    Objects.requireNonNull(commarea, "commarea must not be null");

    // L165-170: SET ERR-FLG-OFF; MOVE SPACES TO WS-MESSAGE, ERRMSGO.
    screen.setErrMsg("");

    // L172-174: EIBCALEN = 0 — no COMMAREA — return to sign-on.
    if (isBlankOrLowValues(commarea.getUserId())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
      return returnToPrevScreen(commarea);
    }

    // L177-181: first entry — mark re-enter and (re)display the blank screen.
    if (!commarea.isPgmReenter()) {
      commarea.setPgmReenter();
      // MOVE LOW-VALUES TO CORPT0AO (blank output map) + MOVE -1 TO MONTHLYL (cursor) are
      // controller/rendering concerns; no selected-record handoff exists for CR00.
      return null;
    }

    // L184-195: re-entry — EVALUATE EIBAID.
    if (aid == CardWorkArea.Aid.ENTER) {
      return processEnterKey(screen, commarea);
    }
    if (aid == CardWorkArea.Aid.PFK03) {
      // L187-189: PF3 — transfer to the main menu.
      commarea.setToProgram(LIT_MENU_PGM);
      return returnToPrevScreen(commarea);
    }
    // L190-194: WHEN OTHER — invalid key.
    screen.setErrMsg(Messages.MSG_INVALID_KEY);
    return null;
  }

  /**
   * Resolves the report type and date range, then submits the report job, reproducing {@code
   * PROCESS-ENTER-KEY} (L208-456).
   *
   * <p>The COBOL {@code EVALUATE TRUE} selects the report type by the first non-blank selector
   * field in the order Monthly &rarr; Yearly &rarr; Custom &rarr; (none); that order is preserved
   * exactly. The program uses a shared {@code WS-ERR-FLG}: once set (by a validation failure or a
   * failed submit), every subsequent {@code IF NOT ERR-FLG-ON} block is skipped and control falls
   * through to {@code EXEC CICS RETURN}. That is reproduced by returning {@code null} immediately
   * after the first error message is set, and by the {@code submitError} flag that gates the
   * success tail.
   *
   * <ul>
   *   <li><b>Monthly</b> (L213-238): the range is the first through the last day of the current
   *       month. The COBOL computes the last day as "first day of next month minus one day"; {@link
   *       LocalDate#plusMonths(long)} + {@link LocalDate#minusDays(long)} reproduces that exactly.
   *   <li><b>Yearly</b> (L239-255): the range is {@code <year>-01-01} through {@code <year>-12-31}
   *       for the current year.
   *   <li><b>Custom</b> (L256-436): six empty checks, NUMVAL-C normalization of all six date
   *       components, six validity checks (month {@literal >} 12, day {@literal >} 31, year
   *       numeric), then the {@code CSUTLDTC} start-date and end-date validations &mdash; all
   *       short-circuiting on the first failure in the exact COBOL order.
   *   <li><b>None</b> (L437-442): {@link #MSG_SELECT_REPORT_TYPE} and a redisplay.
   * </ul>
   *
   * <p><b>Success tail</b> (L445-456): when no error occurred, the screen fields are cleared, the
   * success message {@code "<Name> report submitted for printing ..."} is shown (in green &mdash; a
   * controller concern), and the screen is redisplayed.
   *
   * @param screen the report screen contract (read for input, mutated with normalized fields and
   *     the message)
   * @param commarea the session state (unused for routing here; {@code PROCESS-ENTER-KEY} performs
   *     no {@code XCTL} &mdash; it always redisplays)
   * @return always {@code null} (redisplay)
   */
  private String processEnterKey(ReportScreen screen, CardDemoCommarea commarea) {
    String reportName;
    boolean submitError;

    if (!isBlankOrLowValues(screen.getMonthly())) {
      // L213-238: Monthly — first..last day of the current month. The COBOL derives the last
      // day as "first day of next month minus one day"; plusMonths(1).minusDays(1) matches it.
      reportName = REPORT_NAME_MONTHLY;
      LocalDate start = LocalDate.now().withDayOfMonth(1);
      LocalDate end = start.plusMonths(1).minusDays(1);
      submitError =
          submitJobToIntrdr(screen, reportName, ISO_DATE.format(start), ISO_DATE.format(end));
    } else if (!isBlankOrLowValues(screen.getYearly())) {
      // L239-255: Yearly — Jan 1..Dec 31 of the current year.
      reportName = REPORT_NAME_YEARLY;
      int year = LocalDate.now().getYear();
      String startStr = String.format("%04d-01-01", year);
      String endStr = String.format("%04d-12-31", year);
      submitError = submitJobToIntrdr(screen, reportName, startStr, endStr);
    } else if (!isBlankOrLowValues(screen.getCustom())) {
      // L256-436: Custom date range. Each check below ends the pass on the FIRST failure (the COBOL
      // SEND-TRNRPT-SCREEN performs GO TO RETURN-TO-CICS), so a failing check returns null at once.

      // L258-303: empty checks, in the exact COBOL order.
      if (isBlankOrLowValues(screen.getSdtMm())) {
        screen.setErrMsg(MSG_START_MONTH_EMPTY);
        return null;
      }
      if (isBlankOrLowValues(screen.getSdtDd())) {
        screen.setErrMsg(MSG_START_DAY_EMPTY);
        return null;
      }
      if (isBlankOrLowValues(screen.getSdtYyyy())) {
        screen.setErrMsg(MSG_START_YEAR_EMPTY);
        return null;
      }
      if (isBlankOrLowValues(screen.getEdtMm())) {
        screen.setErrMsg(MSG_END_MONTH_EMPTY);
        return null;
      }
      if (isBlankOrLowValues(screen.getEdtDd())) {
        screen.setErrMsg(MSG_END_DAY_EMPTY);
        return null;
      }
      if (isBlankOrLowValues(screen.getEdtYyyy())) {
        screen.setErrMsg(MSG_END_YEAR_EMPTY);
        return null;
      }

      // L305-327: NUMVAL-C normalization (COMPUTE WS-NUM-* = FUNCTION NUMVAL-C(...); MOVE back).
      // MM/DD become zero-padded 2 digits, YYYY zero-padded 4 digits; re-stored on the screen.
      String sMm = normalizeTwoDigit(screen.getSdtMm());
      String sDd = normalizeTwoDigit(screen.getSdtDd());
      String sYyyy = normalizeFourDigit(screen.getSdtYyyy());
      String eMm = normalizeTwoDigit(screen.getEdtMm());
      String eDd = normalizeTwoDigit(screen.getEdtDd());
      String eYyyy = normalizeFourDigit(screen.getEdtYyyy());
      screen.setSdtMm(sMm);
      screen.setSdtDd(sDd);
      screen.setSdtYyyy(sYyyy);
      screen.setEdtMm(eMm);
      screen.setEdtDd(eDd);
      screen.setEdtYyyy(eYyyy);

      // L329-379: validity checks, in the exact COBOL order. After NUMVAL-C normalization the
      // fields
      // are always numeric, so the "not numeric" sub-condition is unreachable (kept for parity);
      // the
      // operative checks are month > 12 and day > 31. Month 00 / day 00 pass here (the date
      // validator catches them).
      if (!isAllDigits(sMm) || toInt(sMm) > MAX_MONTH) {
        screen.setErrMsg(MSG_START_MONTH_INVALID);
        return null;
      }
      if (!isAllDigits(sDd) || toInt(sDd) > MAX_DAY) {
        screen.setErrMsg(MSG_START_DAY_INVALID);
        return null;
      }
      if (!isAllDigits(sYyyy)) {
        screen.setErrMsg(MSG_START_YEAR_INVALID);
        return null;
      }
      if (!isAllDigits(eMm) || toInt(eMm) > MAX_MONTH) {
        screen.setErrMsg(MSG_END_MONTH_INVALID);
        return null;
      }
      if (!isAllDigits(eDd) || toInt(eDd) > MAX_DAY) {
        screen.setErrMsg(MSG_END_DAY_INVALID);
        return null;
      }
      if (!isAllDigits(eYyyy)) {
        screen.setErrMsg(MSG_END_YEAR_INVALID);
        return null;
      }

      // L381-386: build WS-START-DATE / WS-END-DATE (YYYY-MM-DD) from the normalized components.
      String startStr = sYyyy + "-" + sMm + "-" + sDd;
      String endStr = eYyyy + "-" + eMm + "-" + eDd;

      // L388-406: CSUTLDTC start-date validation. COBOL accepts severity '0000', and also tolerates
      // message number '2513' (Unsupp. Range) even when severity is non-zero.
      DateValidationService.DateValidationResult startResult =
          DateValidationService.validateDate(startStr, DATE_FORMAT_MASK);
      if (!(startResult.isValid() || startResult.isToleratedUnsupportedRange())) {
        screen.setErrMsg(MSG_START_NOT_VALID_DATE);
        return null;
      }

      // L408-426: CSUTLDTC end-date validation (same tolerance as the start date).
      DateValidationService.DateValidationResult endResult =
          DateValidationService.validateDate(endStr, DATE_FORMAT_MASK);
      if (!(endResult.isValid() || endResult.isToleratedUnsupportedRange())) {
        screen.setErrMsg(MSG_END_NOT_VALID_DATE);
        return null;
      }

      // L429-435: MOVE 'Custom' TO WS-REPORT-NAME; IF NOT ERR-FLG-ON PERFORM SUBMIT-JOB-TO-INTRDR.
      reportName = REPORT_NAME_CUSTOM;
      submitError = submitJobToIntrdr(screen, reportName, startStr, endStr);
    } else {
      // L437-442: WHEN OTHER — no report type selected.
      screen.setErrMsg(MSG_SELECT_REPORT_TYPE);
      return null;
    }

    // L445-456: success tail — runs only when no error was raised by the submit.
    if (!submitError) {
      initializeAllFields(screen);
      // MOVE DFHGREEN TO ERRMSGC (green colour) is a controller/rendering concern.
      screen.setErrMsg(delimitedBySpace(reportName) + MSG_SUBMIT_SUCCESS_SUFFIX);
    }
    return null;
  }

  /**
   * Validates the confirmation flag and launches the report job, reproducing {@code
   * SUBMIT-JOB-TO-INTRDR} (L462-510).
   *
   * <p>The control flow is preserved exactly:
   *
   * <ol>
   *   <li><b>Confirmation gate</b> (L464-474): a blank/low-values {@code CONFIRMI} yields the
   *       {@link #MSG_CONFIRM_PRINT_PREFIX} + {@code <name>} + {@link #MSG_CONFIRM_PRINT_SUFFIX}
   *       prompt and signals an error.
   *   <li><b>Confirmation evaluation</b> (L476-494): {@code 'Y'}/{@code 'y'} proceeds to launch;
   *       {@code 'N'}/{@code 'n'} clears every field (so the success tail is skipped); any other
   *       value yields the {@code '"<value>" is not a valid value to confirm...'} message.
   *   <li><b>Job launch</b> (L496-535): when confirmed, {@link
   *       #launchTransactionReportJob(ReportScreen, String, String, String)} replaces the legacy
   *       JCL-build loop + per-line {@code WIRTE-JOBSUB-TDQ}.
   * </ol>
   *
   * @param screen the screen contract, mutated with the error/confirmation message or cleared
   *     fields
   * @param reportName the resolved report name (Monthly / Yearly / Custom)
   * @param startDate the inclusive report start date ({@code YYYY-MM-DD})
   * @param endDate the inclusive report end date ({@code YYYY-MM-DD})
   * @return {@code true} when an error was raised (the caller skips the success tail and
   *     redisplays); {@code false} when the job was launched successfully
   */
  private boolean submitJobToIntrdr(
      ReportScreen screen, String reportName, String startDate, String endDate) {
    String confirm = screen.getConfirm();

    // L464-474: confirmation gate — must confirm before submitting.
    if (isBlankOrLowValues(confirm)) {
      screen.setErrMsg(
          MSG_CONFIRM_PRINT_PREFIX + delimitedBySpace(reportName) + MSG_CONFIRM_PRINT_SUFFIX);
      return true;
    }

    // L476-494: EVALUATE CONFIRMI.
    if ("Y".equals(confirm) || "y".equals(confirm)) {
      // L478-479: confirmed — fall through to the launch.
      return launchTransactionReportJob(screen, reportName, startDate, endDate);
    }
    if ("N".equals(confirm) || "n".equals(confirm)) {
      // L480-483: declined — clear the screen and stop (error flag on -> skip the success tail).
      initializeAllFields(screen);
      return true;
    }
    // L484-493: invalid confirmation value.
    screen.setErrMsg("\"" + delimitedBySpace(confirm) + MSG_INVALID_CONFIRM_SUFFIX);
    return true;
  }

  /**
   * Launches the transaction-report Spring Batch job, replacing the legacy JCL-build loop and the
   * per-line {@code WIRTE-JOBSUB-TDQ} writes to the {@code JOBS} transient-data queue (L498-535).
   *
   * <p>In {@code CORPT00C} the {@code JOB-DATA} deck (carrying {@code PARM-START-DATE}/{@code
   * PARM-END-DATE}) is written line-by-line to the internal reader via {@code EXEC CICS WRITEQ TD
   * QUEUE('JOBS')}; a non-{@code NORMAL} response sets {@link #MSG_UNABLE_WRITE_TDQ} and
   * redisplays. Here the deck is replaced by {@link JobLauncher#run(Job, JobParameters)} against
   * {@link #transactionReportJob}. The two date constants map to the {@link
   * #PARAM_START_DATE}/{@link #PARAM_END_DATE} job parameters (AAP &sect;0.1.1), the report name is
   * carried as {@link #PARAM_REPORT_NAME}, and {@link #PARAM_REQUESTED_AT} (the current epoch
   * millisecond) makes the {@code JobInstance} unique so the same range can be re-submitted &mdash;
   * mirroring the legacy program's ability to re-submit the deck.
   *
   * <p>The launch is wrapped in a {@code try}/{@code catch} for {@link JobExecutionException} (the
   * superclass of every checked exception that {@link JobLauncher#run(Job, JobParameters)}
   * declares: {@code JobInstanceAlreadyCompleteException}, {@code
   * JobExecutionAlreadyRunningException}, {@code JobRestartException}, and {@code
   * JobParametersInvalidException}). A launch failure maps to the legacy TDQ-write failure: {@link
   * #MSG_UNABLE_WRITE_TDQ} is shown and the caller redisplays.
   *
   * @param screen the screen contract, mutated with {@link #MSG_UNABLE_WRITE_TDQ} on a launch
   *     failure
   * @param reportName the resolved report name carried as a job parameter
   * @param startDate the inclusive report start date ({@code YYYY-MM-DD})
   * @param endDate the inclusive report end date ({@code YYYY-MM-DD})
   * @return {@code true} when the launch failed (an error message was set); {@code false} on
   *     success
   */
  private boolean launchTransactionReportJob(
      ReportScreen screen, String reportName, String startDate, String endDate) {
    try {
      JobParameters parameters =
          new JobParametersBuilder()
              .addString(PARAM_START_DATE, startDate)
              .addString(PARAM_END_DATE, endDate)
              .addString(PARAM_REPORT_NAME, reportName)
              .addLong(PARAM_REQUESTED_AT, System.currentTimeMillis())
              .toJobParameters();
      jobLauncher.run(transactionReportJob, parameters);
      return false;
    } catch (JobExecutionException e) {
      // L528-534: the legacy non-NORMAL WRITEQ TD response — show the TDQ-write failure message.
      screen.setErrMsg(MSG_UNABLE_WRITE_TDQ);
      return true;
    }
  }

  /**
   * Resolves the program to transfer control to, reproducing {@code RETURN-TO-PREV-SCREEN}
   * (L540-551).
   *
   * <p>When {@code CDEMO-TO-PROGRAM} is blank/low-values it defaults to {@link #LIT_SIGNON_PGM}
   * (L542-544). The from-transaction and from-program are stamped with this program's identity
   * (L545-546) and the program context is reset to the "enter" state ({@code MOVE ZEROS TO
   * CDEMO-PGM-CONTEXT}, L547). The actual {@code EXEC CICS XCTL} (L548-551) is performed by the
   * controller using the returned program name.
   *
   * @param commarea the session/navigation state, mutated with the routing fields
   * @return the target program name (the {@code XCTL} target)
   */
  private String returnToPrevScreen(CardDemoCommarea commarea) {
    // L542-544: default the target to sign-on when unset.
    if (isBlankOrLowValues(commarea.getToProgram())) {
      commarea.setToProgram(LIT_SIGNON_PGM);
    }
    // L545-547: stamp the from-routing and reset the program context.
    commarea.setFromTranId(TRAN_ID);
    commarea.setFromProgram(PGM_NAME);
    commarea.setPgmContext(CardDemoCommarea.PGM_CONTEXT_ENTER);
    return commarea.getToProgram();
  }

  /**
   * Clears every editable screen field, reproducing {@code INITIALIZE-ALL-FIELDS} (L633-646).
   *
   * <p>The COBOL moves {@code -1} to {@code MONTHLYL} (cursor — a controller concern, not modeled)
   * and {@code INITIALIZE}s the report selectors, the start/end date components, the confirmation
   * flag, and {@code WS-MESSAGE}. Each text field is cleared to the empty string.
   *
   * @param screen the screen contract to clear
   */
  private void initializeAllFields(ReportScreen screen) {
    // MOVE -1 TO MONTHLYL (cursor) is a controller/rendering concern and is not modeled here.
    screen.setMonthly("");
    screen.setYearly("");
    screen.setCustom("");
    screen.setSdtMm("");
    screen.setSdtDd("");
    screen.setSdtYyyy("");
    screen.setEdtMm("");
    screen.setEdtDd("");
    screen.setEdtYyyy("");
    screen.setConfirm("");
    screen.setErrMsg("");
  }

  /**
   * Returns whether the value is COBOL {@code SPACES} or {@code LOW-VALUES} (the {@code = SPACES OR
   * LOW-VALUES} test used throughout {@code CORPT00C}).
   *
   * <p>{@code null}, the empty string, an all-spaces string, and an all-{@code NUL} ({@code
   * LOW-VALUES}) string are all treated as blank; any other content is not.
   *
   * @param value the value to test, possibly {@code null}
   * @return {@code true} when the value is {@code null}, empty, all spaces, or all low-values
   */
  private static boolean isBlankOrLowValues(String value) {
    if (value == null) {
      return true;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != ' ' && c != '\0') {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the portion of {@code value} preceding its first space, reproducing the COBOL {@code
   * STRING ... DELIMITED BY SPACE} used when composing the confirmation, invalid-confirmation, and
   * success messages (L449, L468, L487).
   *
   * @param value the source value, possibly {@code null}
   * @return the characters up to (but not including) the first space, the whole value when it has
   *     no space, or an empty string when {@code value} is {@code null}
   */
  private static String delimitedBySpace(String value) {
    if (value == null) {
      return "";
    }
    int spaceAt = value.indexOf(' ');
    return (spaceAt >= 0) ? value.substring(0, spaceAt) : value;
  }

  /**
   * Reproduces {@code COMPUTE WS-NUM-99 = FUNCTION NUMVAL-C(field)} followed by {@code MOVE
   * WS-NUM-99 TO field} for a two-digit ({@code PIC 99}) month or day component (L305-323).
   *
   * <p>{@code NUMVAL-C} extracts the numeric content of the field (non-digit characters are
   * dropped); the result is then stored into a {@code PIC 99} item, which keeps only the two
   * low-order digits. The value is returned zero-padded to two characters, matching a COBOL {@code
   * MOVE} of a {@code PIC 99} value into a {@code PIC X(2)} field.
   *
   * @param raw the raw screen field value, possibly {@code null}
   * @return the normalized two-character, zero-padded numeric string (e.g. {@code "05"})
   */
  private static String normalizeTwoDigit(String raw) {
    return String.format("%02d", extractDigits(raw) % 100);
  }

  /**
   * Reproduces {@code COMPUTE WS-NUM-9999 = FUNCTION NUMVAL-C(field)} followed by {@code MOVE
   * WS-NUM-9999 TO field} for a four-digit ({@code PIC 9999}) year component (L313-327).
   *
   * <p>{@code NUMVAL-C} extracts the numeric content of the field; the result is stored into a
   * {@code PIC 9999} item, which keeps only the four low-order digits. The value is returned
   * zero-padded to four characters, matching a COBOL {@code MOVE} of a {@code PIC 9999} value into
   * a {@code PIC X(4)} field.
   *
   * @param raw the raw screen field value, possibly {@code null}
   * @return the normalized four-character, zero-padded numeric string (e.g. {@code "2022"})
   */
  private static String normalizeFourDigit(String raw) {
    return String.format("%04d", extractDigits(raw) % 10000);
  }

  /**
   * Extracts the digit characters of {@code raw} and returns their numeric value, reproducing the
   * digit-extraction behavior of COBOL {@code FUNCTION NUMVAL-C} for the date component fields.
   *
   * <p>Non-digit characters (spaces, the {@code LOW-VALUES} pad, separators) are ignored; a value
   * with no digits yields {@code 0} (the COBOL result when {@code NUMVAL-C} is applied to spaces).
   * The running total is kept bounded (modulo one million) so an unexpectedly long input cannot
   * overflow; the callers further reduce it modulo 100 or 10000 to match the {@code PIC 99}/{@code
   * PIC 9999} target widths.
   *
   * @param raw the source text, possibly {@code null}
   * @return the extracted non-negative numeric value (bounded), or {@code 0} when there are no
   *     digits
   */
  private static int extractDigits(String raw) {
    if (raw == null) {
      return 0;
    }
    long value = 0;
    boolean sawDigit = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c >= '0' && c <= '9') {
        sawDigit = true;
        value = (value * 10 + (c - '0')) % 1_000_000L;
      }
    }
    return sawDigit ? (int) value : 0;
  }

  /**
   * Returns whether every character of {@code value} is an ASCII digit, reproducing the COBOL
   * {@code IS NOT NUMERIC} class test (negated) used in the validity checks (L329-373).
   *
   * @param value the value to test, possibly {@code null}
   * @return {@code true} when {@code value} is non-empty and all characters are digits
   */
  private static boolean isAllDigits(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * Parses a normalized, all-digit date component into an {@code int}. Callers invoke this only
   * after {@link #isAllDigits(String)} confirms the value is numeric and on the two- or four-digit
   * normalized values, so the result always fits in an {@code int}.
   *
   * @param value the normalized all-digit component (two or four characters)
   * @return the integer value of {@code value}
   */
  private static int toInt(String value) {
    return Integer.parseInt(value);
  }
}
