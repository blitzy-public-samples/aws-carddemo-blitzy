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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.screen.ReportScreen;
import com.aws.carddemo.util.Messages;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;

/**
 * Pure JUnit&nbsp;5 + Mockito + AssertJ unit tests for {@link ReportService}, the online
 * transaction-report submission business-logic service migrated from the legacy CICS COBOL program
 * {@code CORPT00C} (CICS transaction {@code CR00}; behavioral spec {@code
 * legacy/app/cbl/CORPT00C.cbl}, date routine {@code legacy/app/cbl/CSUTLDTC.cbl}, COMMAREA {@code
 * legacy/app/cpy/COCOM01Y.cpy}, work area {@code legacy/app/cpy/CVCRD01Y.cpy}).
 *
 * <p>{@code ReportService} is the only online service that drives Spring Batch: the operator
 * selects a report type (Monthly / Yearly / Custom date range), the custom date range is validated,
 * and on confirmation the transaction-detail report job is launched. The legacy program built an
 * in-line JCL deck and wrote it to the {@code JOBS} transient-data queue (the z/OS internal
 * reader); the modernized service replaces that {@code WRITEQ TD} with {@link JobLauncher#run(Job,
 * JobParameters)} against the {@code transactionReportJob} bean (Agent Action Plan &sect;0.4.1
 * Report row).
 *
 * <p><strong>Collaborators.</strong> The two constructor-injected collaborators &mdash; the {@link
 * JobLauncher} and the {@code @Qualifier("transactionReportJob")} {@link Job} &mdash; are mocked so
 * every branch runs with no Spring context and no real batch infrastructure. The third
 * collaborator, {@code com.aws.carddemo.util.DateValidationService}, is a stateless <em>static</em>
 * utility (the {@code CSUTLDTC}/{@code CEEDAYS} date routine) and is therefore exercised for real
 * with genuine date strings rather than mocked, consistent with the sibling {@code
 * AccountUpdateServiceTest} / {@code TranAddServiceTest} and the agent prompt's "adapt if static"
 * guidance.
 *
 * <p><strong>Defining parity features.</strong>
 *
 * <ul>
 *   <li><b>Job-submission parity</b> (AAP &sect;0.4.1): a confirmed submission launches the
 *       <em>qualified</em> {@code transactionReportJob} with the correct date-range {@link
 *       JobParameters}; asserted with {@link ArgumentCaptor}.
 *   <li><b>Control-flow parity</b> (AAP &sect;0.7.1): selection / date validation precede the
 *       confirm gate, which precedes the launch; pinned with Mockito {@link InOrder} over a {@link
 *       org.mockito.Mockito#spy(Object) spied} {@link ReportScreen}. No launch occurs on any
 *       validation or confirmation failure ({@link
 *       org.mockito.Mockito#verifyNoInteractions(Object...)}).
 *   <li><b>Byte-exact message parity</b>: every operator message is asserted against the literal
 *       text transcribed verbatim from {@code CORPT00C} (cross-checked at the cited COBOL line
 *       numbers), so a single-byte drift fails the test. The six custom empty-date checks are
 *       proven to fire in the strict COBOL order (Start M/D/Y then End M/D/Y).
 *   <li><b>Launch-failure mapping</b> (AAP &sect;0.6.4/&sect;0.6.6): a {@link
 *       org.springframework.batch.core.JobExecutionException} from the launcher maps to the legacy
 *       {@code WRITEQ TD} failure message {@code "Unable to Write TDQ (JOBS)..."} (preserved
 *       verbatim) and a redisplay &mdash; the service never propagates the exception.
 *   <li><b>Navigation/state parity</b> (AAP &sect;0.6.5): PF3 routes to the main menu and stamps
 *       the from-routing; an uninitialized COMMAREA routes to sign-on; the first entry marks
 *       re-enter.
 * </ul>
 *
 * <p>The {@code Year}-invalid validity branches ({@code MSG_START_YEAR_INVALID} / {@code
 * MSG_END_YEAR_INVALID}) are parity-preserved but unreachable through the public API because the
 * COBOL {@code NUMVAL-C} normalization always yields four digits; their byte-exact literals are
 * therefore asserted directly in {@link #messageConstants_matchCobolByteExact()}.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

  // ===== Byte-exact COBOL literals (independent transcription from legacy/app/cbl/CORPT00C.cbl) ==

  /** {@code CORPT00C} L261. */
  private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

  /** {@code CORPT00C} L268. */
  private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

  /** {@code CORPT00C} L275. */
  private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

  /** {@code CORPT00C} L282. */
  private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

  /** {@code CORPT00C} L289. */
  private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

  /** {@code CORPT00C} L296. */
  private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

  /** {@code CORPT00C} L331. */
  private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

  /** {@code CORPT00C} L340. */
  private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

  /** {@code CORPT00C} L348 (parity-preserved, unreachable after NUMVAL-C normalization). */
  private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

  /** {@code CORPT00C} L357. */
  private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

  /** {@code CORPT00C} L366. */
  private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

  /** {@code CORPT00C} L374 (parity-preserved, unreachable after NUMVAL-C normalization). */
  private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

  /** {@code CORPT00C} L400. */
  private static final String MSG_START_NOT_VALID_DATE = "Start Date - Not a valid date...";

  /** {@code CORPT00C} L420. */
  private static final String MSG_END_NOT_VALID_DATE = "End Date - Not a valid date...";

  /** {@code CORPT00C} L438. */
  private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

  /** {@code CORPT00C} L450 (leading space; {@code DELIMITED BY SIZE}). */
  private static final String SUCCESS_SUFFIX = " report submitted for printing ...";

  /** {@code CORPT00C} L466 (trailing space; {@code DELIMITED BY SIZE}). */
  private static final String CONFIRM_PREFIX = "Please confirm to print the ";

  /** {@code CORPT00C} L469 (leading space; {@code DELIMITED BY SIZE}). */
  private static final String CONFIRM_SUFFIX = " report...";

  /** {@code CORPT00C} L488 (the suffix appended after {@code '"' + confirm}). */
  private static final String INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

  /** {@code CORPT00C} L531. */
  private static final String MSG_UNABLE_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

  // ===== Mock collaborators + system under test =================================================

  /**
   * Mocked Spring Batch launcher; replaces the legacy {@code WRITEQ TD QUEUE('JOBS')} submission.
   */
  @Mock private JobLauncher jobLauncher;

  /** Mocked {@code @Qualifier("transactionReportJob")} job bean; the launch target. */
  @Mock private Job transactionReportJob;

  /** System under test; collaborators injected through the single constructor. */
  @InjectMocks private ReportService service;

  // ===== Builders ===============================================================================

  /**
   * Builds a present, re-entered COMMAREA so {@code processReport} reaches the {@code EVALUATE
   * EIBAID} branch (a non-blank user id passes the {@code EIBCALEN = 0} guard; re-enter passes the
   * first-entry guard).
   */
  private static CardDemoCommarea reentryCommarea() {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUserId("USER0001");
    commarea.setPgmReenter();
    return commarea;
  }

  /** Builds a Monthly-selected report screen with the supplied confirmation value. */
  private static ReportScreen monthlyScreen(String confirm) {
    ReportScreen screen = new ReportScreen();
    screen.setMonthly("Y");
    screen.setConfirm(confirm);
    return screen;
  }

  /** Builds a Yearly-selected report screen with the supplied confirmation value. */
  private static ReportScreen yearlyScreen(String confirm) {
    ReportScreen screen = new ReportScreen();
    screen.setYearly("Y");
    screen.setConfirm(confirm);
    return screen;
  }

  /** Builds a Custom-selected report screen with the supplied date components and confirmation. */
  private static ReportScreen customScreen(
      String sdtMm,
      String sdtDd,
      String sdtYyyy,
      String edtMm,
      String edtDd,
      String edtYyyy,
      String confirm) {
    ReportScreen screen = new ReportScreen();
    screen.setCustom("Y");
    screen.setSdtMm(sdtMm);
    screen.setSdtDd(sdtDd);
    screen.setSdtYyyy(sdtYyyy);
    screen.setEdtMm(edtMm);
    screen.setEdtDd(edtDd);
    screen.setEdtYyyy(edtYyyy);
    screen.setConfirm(confirm);
    return screen;
  }

  /** Asserts the captured job parameters carry the expected date range, report name, and stamp. */
  private static void assertReportParams(
      JobParameters params, String expectedStart, String expectedEnd, String expectedName) {
    assertThat(params.getString(ReportService.PARAM_START_DATE)).isEqualTo(expectedStart);
    assertThat(params.getString(ReportService.PARAM_END_DATE)).isEqualTo(expectedEnd);
    assertThat(params.getString(ReportService.PARAM_REPORT_NAME)).isEqualTo(expectedName);
    // requestedAt makes the JobInstance unique so the same range can be re-submitted (parity with
    // the legacy program's ability to re-submit the deck).
    assertThat(params.getLong(ReportService.PARAM_REQUESTED_AT)).isNotNull();
  }

  // ===== Launch-success branches ================================================================

  @Test
  @DisplayName("Monthly + confirm Y launches the report job for the full current month")
  void monthly_confirmed_launchesReportJob() throws Exception {
    LocalDate today = LocalDate.now();
    ReportScreen screen = spy(monthlyScreen("Y"));
    CardDemoCommarea commarea = reentryCommarea();

    String next = service.processReport(screen, commarea, CardWorkArea.Aid.ENTER);

    // PROCESS-ENTER-KEY always redisplays (it performs no XCTL).
    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Monthly" + SUCCESS_SUFFIX);

    ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
    ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
    verify(jobLauncher).run(jobCaptor.capture(), paramsCaptor.capture());

    // The launched job is the qualified transactionReportJob bean.
    assertThat(jobCaptor.getValue()).isSameAs(transactionReportJob);

    LocalDate firstOfMonth = today.withDayOfMonth(1);
    LocalDate lastOfMonth = firstOfMonth.plusMonths(1).minusDays(1);
    assertReportParams(
        paramsCaptor.getValue(), firstOfMonth.toString(), lastOfMonth.toString(), "Monthly");

    // Control-flow parity: the report-type selection precedes the job launch.
    InOrder inOrder = inOrder(screen, jobLauncher);
    inOrder.verify(screen).getMonthly();
    inOrder.verify(jobLauncher).run(any(), any());
  }

  @Test
  @DisplayName("Yearly + confirm Y launches the report job for the full current year")
  void yearly_confirmed_launchesReportJob() throws Exception {
    LocalDate today = LocalDate.now();
    ReportScreen screen = spy(yearlyScreen("Y"));

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Yearly" + SUCCESS_SUFFIX);

    ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
    verify(jobLauncher).run(any(), paramsCaptor.capture());

    int year = today.getYear();
    assertReportParams(
        paramsCaptor.getValue(),
        String.format("%04d-01-01", year),
        String.format("%04d-12-31", year),
        "Yearly");

    InOrder inOrder = inOrder(screen, jobLauncher);
    inOrder.verify(screen).getYearly();
    inOrder.verify(jobLauncher).run(any(), any());
  }

  @Test
  @DisplayName("Custom valid dates + confirm Y launches the report job with the custom range")
  void custom_validDates_confirmed_launches() throws Exception {
    ReportScreen screen = spy(customScreen("01", "15", "2024", "02", "20", "2024", "Y"));

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("Custom" + SUCCESS_SUFFIX);

    ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
    ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
    verify(jobLauncher).run(jobCaptor.capture(), paramsCaptor.capture());

    assertThat(jobCaptor.getValue()).isSameAs(transactionReportJob);
    assertReportParams(paramsCaptor.getValue(), "2024-01-15", "2024-02-20", "Custom");

    // Control-flow parity: date validation (date-field read) precedes the confirm gate, which
    // precedes the job launch.
    InOrder inOrder = inOrder(screen, jobLauncher);
    inOrder.verify(screen).getSdtMm();
    inOrder.verify(screen).getConfirm();
    inOrder.verify(jobLauncher).run(any(), any());
  }

  // ===== Custom date-range validation branches ==================================================

  /**
   * The six empty-date checks, each with the field under test (and every <em>later</em> field)
   * blank while the earlier fields are valid, so the asserted message proves the precise stopping
   * point and the strict COBOL order (Start Month &rarr; Day &rarr; Year &rarr; End Month &rarr;
   * Day &rarr; Year).
   */
  static Stream<Arguments> emptyDateFieldCases() {
    return Stream.of(
        arguments(
            "start month empty (L261)",
            customScreen("", "", "", "", "", "", "Y"),
            MSG_START_MONTH_EMPTY),
        arguments(
            "start day empty (L268)",
            customScreen("01", "", "", "", "", "", "Y"),
            MSG_START_DAY_EMPTY),
        arguments(
            "start year empty (L275)",
            customScreen("01", "15", "", "", "", "", "Y"),
            MSG_START_YEAR_EMPTY),
        arguments(
            "end month empty (L282)",
            customScreen("01", "15", "2024", "", "", "", "Y"),
            MSG_END_MONTH_EMPTY),
        arguments(
            "end day empty (L289)",
            customScreen("01", "15", "2024", "02", "", "", "Y"),
            MSG_END_DAY_EMPTY),
        arguments(
            "end year empty (L296)",
            customScreen("01", "15", "2024", "02", "20", "", "Y"),
            MSG_END_YEAR_EMPTY));
  }

  @ParameterizedTest(name = "[{index}] {0} -> redisplay, no launch")
  @MethodSource("emptyDateFieldCases")
  @DisplayName("Custom: the six empty-date checks fire in strict COBOL order")
  void custom_emptyDateField_redisplays_inCobolOrder(
      String label, ReportScreen screen, String expectedMessage) {
    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(expectedMessage);
    verifyNoInteractions(jobLauncher);
  }

  /**
   * The reachable validity checks (month {@literal >} 12, day {@literal >} 31) for the start and
   * end components. The field under test and every <em>later</em> reachable field are out of range
   * while the earlier fields are valid, proving the strict COBOL order.
   */
  static Stream<Arguments> outOfRangeCases() {
    return Stream.of(
        arguments(
            "start month > 12 (L331)",
            customScreen("13", "32", "2024", "13", "32", "2024", "Y"),
            MSG_START_MONTH_INVALID),
        arguments(
            "start day > 31 (L340)",
            customScreen("12", "32", "2024", "13", "32", "2024", "Y"),
            MSG_START_DAY_INVALID),
        arguments(
            "end month > 12 (L357)",
            customScreen("12", "31", "2024", "13", "32", "2024", "Y"),
            MSG_END_MONTH_INVALID),
        arguments(
            "end day > 31 (L366)",
            customScreen("12", "31", "2024", "12", "32", "2024", "Y"),
            MSG_END_DAY_INVALID));
  }

  @ParameterizedTest(name = "[{index}] {0} -> redisplay, no launch")
  @MethodSource("outOfRangeCases")
  @DisplayName("Custom: month>12 / day>31 validity checks fire in strict COBOL order")
  void custom_outOfRangeComponent_redisplays(
      String label, ReportScreen screen, String expectedMessage) {
    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(expectedMessage);
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("Custom: an impossible start calendar date (Feb 30) redisplays, no launch")
  void custom_invalidStartCalendarDate_redisplays() {
    // 02/30 passes the month<=12 / day<=31 range checks but is not a real calendar date, so the
    // real CSUTLDTC date validation (CEEDAYS approximation) rejects it.
    ReportScreen screen = customScreen("02", "30", "2024", "02", "20", "2024", "Y");

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(MSG_START_NOT_VALID_DATE);
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("Custom: an impossible end calendar date (Feb 30) redisplays, no launch")
  void custom_invalidEndCalendarDate_redisplays() {
    // A valid start date passes its CSUTLDTC check; the impossible end date (Feb 30) then fails.
    ReportScreen screen = customScreen("01", "15", "2024", "02", "30", "2024", "Y");

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(MSG_END_NOT_VALID_DATE);
    verifyNoInteractions(jobLauncher);
  }

  // ===== Confirmation gate ======================================================================

  @Test
  @DisplayName("Blank confirm prompts 'Please confirm to print the <name> report...'")
  void monthly_noConfirm_promptsPleaseConfirm() {
    // CONFIRMI = LOW-VALUES/SPACES is modeled by a null confirm value.
    ReportScreen screen = monthlyScreen(null);

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(CONFIRM_PREFIX + "Monthly" + CONFIRM_SUFFIX);
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("Custom valid dates without confirm prompt with the Custom report name")
  void custom_validDates_noConfirm_promptsWithCustomName() {
    // Proves the custom date range validates (real CSUTLDTC) before the confirm gate is reached.
    ReportScreen screen = customScreen("01", "15", "2024", "02", "20", "2024", null);

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(CONFIRM_PREFIX + "Custom" + CONFIRM_SUFFIX);
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("Invalid confirm value redisplays '\"<value>\" is not a valid value to confirm...'")
  void monthly_invalidConfirmValue_redisplays() {
    ReportScreen screen = monthlyScreen("X");

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo("\"X" + INVALID_CONFIRM_SUFFIX);
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("Confirm 'N' clears all fields and does not launch")
  void confirmNo_clearsFields_noLaunch() {
    ReportScreen screen = monthlyScreen("N");

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    // INITIALIZE-ALL-FIELDS clears the selectors, the date components, the confirm flag, and the
    // message; the success tail is skipped because the error flag is set.
    assertThat(screen.getMonthly()).isEmpty();
    assertThat(screen.getConfirm()).isEmpty();
    assertThat(screen.getErrMsg()).isEmpty();
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("No report type selected prompts 'Select a report type to print report...'")
  void noReportTypeSelected_promptsSelectReportType() {
    // monthly / yearly / custom all blank -> EVALUATE WHEN OTHER.
    ReportScreen screen = new ReportScreen();
    screen.setConfirm("Y");

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(MSG_SELECT_REPORT_TYPE);
    verifyNoInteractions(jobLauncher);
  }

  // ===== Job-launch failure mapping =============================================================

  @Test
  @DisplayName("A launcher failure maps to 'Unable to Write TDQ (JOBS)...' and redisplays")
  void launchFailure_redisplaysUnableToWriteTdq() throws Exception {
    // A JobExecutionException (here the already-running subclass) models the legacy non-NORMAL
    // WRITEQ TD response; the service must swallow it, show the TDQ-write message, and redisplay
    // rather than propagate the exception (AAP 0.6.4 / 0.6.6).
    when(jobLauncher.run(any(), any()))
        .thenThrow(new JobExecutionAlreadyRunningException("job already running"));
    ReportScreen screen = monthlyScreen("Y");

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(MSG_UNABLE_WRITE_TDQ);
    // The launch was attempted exactly once before failing (the success tail is skipped).
    verify(jobLauncher).run(any(), any());
  }

  // ===== Navigation / state machine =============================================================

  @Test
  @DisplayName("PF3 transfers to the main menu (COMEN01C) and stamps the from-routing")
  void pfk03_returnsToMainMenu() {
    CardDemoCommarea commarea = reentryCommarea();

    String next = service.processReport(new ReportScreen(), commarea, CardWorkArea.Aid.PFK03);

    assertThat(next).isEqualTo("COMEN01C");
    assertThat(commarea.getToProgram()).isEqualTo("COMEN01C");
    assertThat(commarea.getFromTranId()).isEqualTo("CR00");
    assertThat(commarea.getFromProgram()).isEqualTo("CORPT00C");
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("First entry marks re-enter and redisplays the blank screen without launching")
  void firstEntry_marksReenter_redisplays() {
    // A present user id but no re-enter flag models EIBCALEN > 0 on the first pass.
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setUserId("USER0001");

    String next = service.processReport(new ReportScreen(), commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isNull();
    assertThat(commarea.isPgmReenter()).isTrue();
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("An uninitialized COMMAREA (no user id) routes to sign-on (COSGN00C)")
  void noCommarea_routesToSignon() {
    CardDemoCommarea commarea = new CardDemoCommarea();

    String next = service.processReport(new ReportScreen(), commarea, CardWorkArea.Aid.ENTER);

    assertThat(next).isEqualTo("COSGN00C");
    assertThat(commarea.getToProgram()).isEqualTo("COSGN00C");
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("Any non-ENTER / non-PF3 key redisplays the invalid-key message")
  void invalidKey_redisplaysInvalidKey() {
    ReportScreen screen = new ReportScreen();

    String next = service.processReport(screen, reentryCommarea(), CardWorkArea.Aid.CLEAR);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY);
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("A null AID is treated as an invalid key (EVALUATE WHEN OTHER)")
  void nullAid_redisplaysInvalidKey() {
    ReportScreen screen = new ReportScreen();

    String next = service.processReport(screen, reentryCommarea(), null);

    assertThat(next).isNull();
    assertThat(screen.getErrMsg()).isEqualTo(Messages.MSG_INVALID_KEY);
    verifyNoInteractions(jobLauncher);
  }

  // ===== Null-argument guards ===================================================================

  @Test
  @DisplayName("A null screen is rejected with NullPointerException, no launch")
  void nullScreen_throwsNullPointerException() {
    assertThatThrownBy(() -> service.processReport(null, reentryCommarea(), CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("screen");
    verifyNoInteractions(jobLauncher);
  }

  @Test
  @DisplayName("A null COMMAREA is rejected with NullPointerException, no launch")
  void nullCommarea_throwsNullPointerException() {
    assertThatThrownBy(
            () -> service.processReport(new ReportScreen(), null, CardWorkArea.Aid.ENTER))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("commarea");
    verifyNoInteractions(jobLauncher);
  }

  // ===== Byte-exact message / routing constant parity ===========================================

  /**
   * Pins every {@code ReportService} literal to its independent byte-exact transcription from
   * {@code CORPT00C}. This is the only place the parity-preserved but API-unreachable {@code
   * Year}-invalid validity messages are asserted (see the class javadoc).
   */
  @Test
  @DisplayName("Every ReportService literal matches its byte-exact CORPT00C transcription")
  void messageConstants_matchCobolByteExact() {
    // Empty-date messages (CORPT00C L261-296), in COBOL order.
    assertThat(ReportService.MSG_START_MONTH_EMPTY).isEqualTo(MSG_START_MONTH_EMPTY);
    assertThat(ReportService.MSG_START_DAY_EMPTY).isEqualTo(MSG_START_DAY_EMPTY);
    assertThat(ReportService.MSG_START_YEAR_EMPTY).isEqualTo(MSG_START_YEAR_EMPTY);
    assertThat(ReportService.MSG_END_MONTH_EMPTY).isEqualTo(MSG_END_MONTH_EMPTY);
    assertThat(ReportService.MSG_END_DAY_EMPTY).isEqualTo(MSG_END_DAY_EMPTY);
    assertThat(ReportService.MSG_END_YEAR_EMPTY).isEqualTo(MSG_END_YEAR_EMPTY);

    // Validity messages (CORPT00C L331-374). The Year-invalid pair is parity-preserved but
    // unreachable through the public API, so it is asserted here directly.
    assertThat(ReportService.MSG_START_MONTH_INVALID).isEqualTo(MSG_START_MONTH_INVALID);
    assertThat(ReportService.MSG_START_DAY_INVALID).isEqualTo(MSG_START_DAY_INVALID);
    assertThat(ReportService.MSG_START_YEAR_INVALID).isEqualTo(MSG_START_YEAR_INVALID);
    assertThat(ReportService.MSG_END_MONTH_INVALID).isEqualTo(MSG_END_MONTH_INVALID);
    assertThat(ReportService.MSG_END_DAY_INVALID).isEqualTo(MSG_END_DAY_INVALID);
    assertThat(ReportService.MSG_END_YEAR_INVALID).isEqualTo(MSG_END_YEAR_INVALID);

    // Date-validity + report-type messages (CORPT00C L400 / L420 / L438).
    assertThat(ReportService.MSG_START_NOT_VALID_DATE).isEqualTo(MSG_START_NOT_VALID_DATE);
    assertThat(ReportService.MSG_END_NOT_VALID_DATE).isEqualTo(MSG_END_NOT_VALID_DATE);
    assertThat(ReportService.MSG_SELECT_REPORT_TYPE).isEqualTo(MSG_SELECT_REPORT_TYPE);

    // Composed-message fragments (CORPT00C L450 / L465-470 / L485-489 / L531).
    assertThat(ReportService.MSG_SUBMIT_SUCCESS_SUFFIX).isEqualTo(SUCCESS_SUFFIX);
    assertThat(ReportService.MSG_CONFIRM_PRINT_PREFIX).isEqualTo(CONFIRM_PREFIX);
    assertThat(ReportService.MSG_CONFIRM_PRINT_SUFFIX).isEqualTo(CONFIRM_SUFFIX);
    assertThat(ReportService.MSG_INVALID_CONFIRM_SUFFIX).isEqualTo(INVALID_CONFIRM_SUFFIX);
    assertThat(ReportService.MSG_UNABLE_WRITE_TDQ).isEqualTo(MSG_UNABLE_WRITE_TDQ);

    // Report names (CORPT00C L214 / L240 / L433) and routing identities.
    assertThat(ReportService.REPORT_NAME_MONTHLY).isEqualTo("Monthly");
    assertThat(ReportService.REPORT_NAME_YEARLY).isEqualTo("Yearly");
    assertThat(ReportService.REPORT_NAME_CUSTOM).isEqualTo("Custom");
    assertThat(ReportService.TRAN_ID).isEqualTo("CR00");
    assertThat(ReportService.PGM_NAME).isEqualTo("CORPT00C");
    assertThat(ReportService.LIT_SIGNON_PGM).isEqualTo("COSGN00C");
    assertThat(ReportService.LIT_MENU_PGM).isEqualTo("COMEN01C");

    // Job-parameter keys and the CSUTLDTC date mask.
    assertThat(ReportService.PARAM_START_DATE).isEqualTo("startDate");
    assertThat(ReportService.PARAM_END_DATE).isEqualTo("endDate");
    assertThat(ReportService.PARAM_REPORT_NAME).isEqualTo("reportName");
    assertThat(ReportService.PARAM_REQUESTED_AT).isEqualTo("requestedAt");
    assertThat(ReportService.DATE_FORMAT_MASK).isEqualTo("YYYY-MM-DD");
  }
}
