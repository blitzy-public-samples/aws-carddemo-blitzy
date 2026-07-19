/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.online;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;

import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.CORPT00Form;
import com.aws.carddemo.service.online.ReportSubmitService.AidKey;
import com.aws.carddemo.service.online.ReportSubmitService.MessageSeverity;
import com.aws.carddemo.service.online.ReportSubmitService.ReportSubmitResult;
import com.aws.carddemo.util.DateConversionService;
import com.aws.carddemo.util.DateConversionService.DateValidationResult;
import com.aws.carddemo.util.DateConversionService.ValidationOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link ReportSubmitService}, the Java migration of the CICS COBOL
 * program {@code CORPT00C} (the AWS CardDemo online "Print Transaction reports" transaction).
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/CORPT00C.cbl} &mdash; program
 * {@code CORPT00C}, CICS transaction id {@code CR00}. The COBOL program builds a JCL job stream and
 * submits it to the JES2 internal reader through an extra-partition transient-data queue
 * ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}); it performs <em>no</em> VSAM/file I/O. These tests
 * assert control-flow parity with the program's numbered paragraphs:</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link ReportSubmitService#mainEntry(AidKey, CORPT00Form)} (the
 *       {@code EIBCALEN = 0} first-entry bounce, the {@code CDEMO-PGM-REENTER} first-display branch,
 *       and the {@code EVALUATE EIBAID} dispatch including the {@code WHEN OTHER} invalid-key
 *       branch)</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link ReportSubmitService#processEnterKey(CORPT00Form)}
 *       (the {@code EVALUATE TRUE} report-type selection: Monthly / Yearly / Custom, and the
 *       {@code WHEN OTHER} "select a report type" branch)</li>
 *   <li>{@code SUBMIT-JOB-TO-INTRDR} &rarr; the confirm gate ({@code CONFIRMI} = blank / {@code Y} /
 *       {@code N} / other) that guards the submission</li>
 *   <li>{@code WIRTE-JOBSUB-TDQ} (the COBOL preserves this original misspelling of "write") &rarr;
 *       the {@link JobLauncher#run(Job, JobParameters)} launch that replaces the
 *       {@code WRITEQ TD QUEUE('JOBS')} enqueue, and its {@code EVALUATE WS-RESP-CD} failure
 *       branch</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr; the field-clearing performed on a successful submit and
 *       on the confirm {@code 'N'} path</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr; the {@code XCTL} hand-off produced on first entry and on
 *       PF3</li>
 * </ul>
 *
 * <p><b>Test tier.</b> This is a strict-stubs pure-Mockito unit test: {@link MockitoExtension} with
 * {@link Mock} collaborators and an {@link InjectMocks} service. It starts no Spring context, opens
 * no database connection, launches no real Spring Batch job, and loads no persistence types &mdash;
 * it verifies the service's business logic in complete isolation.</p>
 *
 * <p><b>No new external interface (AAP &sect;0.6.4).</b> The modernized submission is a
 * {@link JobLauncher} launch of a pre-defined batch job; it introduces no MQ, REST, or file
 * interface. {@link #service_hasNoRepositoryOrDataStoreCollaborators()} asserts this design
 * guarantee structurally: the service holds no repository / persistence collaborator.</p>
 *
 * <p><b>Deterministic dates.</b> The Monthly and Yearly windows derive from
 * {@link ReportSubmitService#currentDate()} (the {@code FUNCTION CURRENT-DATE} seam). Those tests use
 * a {@link org.mockito.Mockito#spy(Object) spy} of the injected service to pin "today", so the
 * asserted windows are exact and never wall-clock-dependent; the confirm-gate tests exercise the
 * real {@code currentDate()}.</p>
 */
@ExtendWith(MockitoExtension.class)
class ReportSubmitServiceTest {

    // ------------------------------------------------------------------------------------------
    // Byte-exact COBOL screen messages (WS-MESSAGE literals of CORPT00C)
    // ------------------------------------------------------------------------------------------

    /** COBOL {@code CCDA-MSG-INVALID-KEY} - the {@code WHEN OTHER} EIBAID branch of {@code MAIN-PARA}. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** COBOL {@code 'Select a report type to print report...'} - the {@code WHEN OTHER} branch. */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** COBOL {@code 'Start Date - Month can NOT be empty...'}. */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** COBOL {@code 'Start Date - Not a valid Month...'}. */
    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** COBOL {@code 'Start Date - Not a valid date...'} (the {@code CSUTLDTC} start-date failure). */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** COBOL {@code 'Unable to Write TDQ (JOBS)...'} (the job-submission failure line). */
    private static final String MSG_UNABLE_TO_WRITE_TDQ = "Unable to Write TDQ (JOBS)...";

    // ------------------------------------------------------------------------------------------
    // Program identity (COBOL WS-PGMNAME / WS-TRANID) and hand-off targets
    // ------------------------------------------------------------------------------------------

    /** COBOL {@code WS-PGMNAME = 'CORPT00C'} - written as the hand-off origin program. */
    private static final String PROGRAM_NAME = "CORPT00C";

    /** COBOL {@code WS-TRANID = 'CR00'} - written as the hand-off origin transaction id. */
    private static final String TRANSACTION_ID = "CR00";

    /** Sign-on program ({@code COSGN00C}) - the {@code EIBCALEN = 0} first-entry bounce target. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Main-menu program ({@code COMEN01C}) - the hard-coded PF3 ("back") target of {@code MAIN-PARA}. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Report names moved to {@code WS-REPORT-NAME} for the three report types. */
    private static final String REPORT_MONTHLY = "Monthly";
    private static final String REPORT_YEARLY = "Yearly";
    private static final String REPORT_CUSTOM = "Custom";

    /** The date picture mask ({@code WS-DATE-FORMAT = 'YYYY-MM-DD'}) passed to the date service. */
    private static final String DATE_FORMAT_MASK = "YYYY-MM-DD";

    /** The green "submitted for printing" suffix appended to {@code WS-REPORT-NAME} on success. */
    private static final String SUCCESS_MSG_SUFFIX = " report submitted for printing ...";

    /** Spring Batch job-parameter keys the service records for the transaction-report job. */
    private static final String PARAM_REPORT_TYPE = "reportType";
    private static final String PARAM_START_DATE = "startDate";
    private static final String PARAM_END_DATE = "endDate";
    private static final String PARAM_SUBMIT_TIMESTAMP = "submitTimestamp";

    /** The {@code CEEDAYS} message number ({@code 2513}) that {@code CORPT00C} tolerates as valid. */
    private static final int CEE_MSG_UNSUPP_RANGE = 2513;

    // ------------------------------------------------------------------------------------------
    // Collaborators (mocked) and service under test
    // ------------------------------------------------------------------------------------------

    /** Session context (COMMAREA {@code COCOM01Y} replacement); mocked so hand-off writes verify. */
    @Mock
    private CardDemoContext context;

    /** {@code CSUTLDTC} date-validation service; stubbed for the custom-range calendar check. */
    @Mock
    private DateConversionService dateConversionService;

    /** Spring Batch launcher (the {@code WRITEQ TD QUEUE('JOBS')} replacement). */
    @Mock
    private JobLauncher jobLauncher;

    /**
     * The transaction-report {@link Job} (bean {@code transactionReportJob} from
     * {@code TransactionReportJobConfig}); the field name mirrors the production
     * {@code @Qualifier("transactionReportJob")} injection so {@link InjectMocks} wires it.
     */
    @Mock
    private Job transactionReportJob;

    /** Service under test, wired by constructor injection with the four mocks above. */
    @InjectMocks
    private ReportSubmitService service;

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a real {@link CORPT00Form} carrying the given report-type flag and confirmation value,
     * mirroring the 3270 map {@code CORPT0A} the COBOL program receives.
     *
     * @param monthly the {@code MONTHLYI} flag value (may be {@code null})
     * @param yearly  the {@code YEARLYI} flag value (may be {@code null})
     * @param custom  the {@code CUSTOMI} flag value (may be {@code null})
     * @param confirm the {@code CONFIRMI} value (may be {@code null})
     * @return a populated report form
     */
    private static CORPT00Form reportForm(String monthly, String yearly, String custom, String confirm) {
        CORPT00Form form = new CORPT00Form();
        form.setMonthly(monthly);
        form.setYearly(yearly);
        form.setCustom(custom);
        form.setConfirm(confirm);
        return form;
    }

    /**
     * Builds a real {@link CORPT00Form} for the Custom report type with the six MM/DD/YYYY date
     * parts and the confirmation value populated.
     *
     * @param sdtmm   start-date month part
     * @param sdtdd   start-date day part
     * @param sdtyyyy start-date year part
     * @param edtmm   end-date month part
     * @param edtdd   end-date day part
     * @param edtyyyy end-date year part
     * @param confirm the {@code CONFIRMI} value
     * @return a populated custom-report form
     */
    private static CORPT00Form customForm(String sdtmm, String sdtdd, String sdtyyyy,
            String edtmm, String edtdd, String edtyyyy, String confirm) {
        CORPT00Form form = reportForm(null, null, "Y", confirm);
        form.setSdtmm(sdtmm);
        form.setSdtdd(sdtdd);
        form.setSdtyyyy(sdtyyyy);
        form.setEdtmm(edtmm);
        form.setEdtdd(edtdd);
        form.setEdtyyyy(edtyyyy);
        return form;
    }

    /**
     * A valid {@link DateValidationResult} (COBOL {@code WS-SEVERITY = 0}, {@code WS-MSG-NO = 0}),
     * the {@code CSUTLDTC} success outcome.
     *
     * @return a severity-0 validation result
     */
    private static DateValidationResult validDate() {
        return new DateValidationResult(true, 0, 0, "Date is valid", "",
                OptionalLong.of(0L), ValidationOutcome.FC_INVALID_DATE);
    }

    /**
     * An invalid-calendar {@link DateValidationResult} (severity {@code 3}, message {@code 2508},
     * COBOL {@code FC-BAD-DATE-VALUE}) - a non-existent date such as 30-February.
     *
     * @return a severity-3 / msg-2508 validation result
     */
    private static DateValidationResult badDateValueResult() {
        return new DateValidationResult(false, 3, 2508, "Datevalue error", "",
                OptionalLong.empty(), ValidationOutcome.FC_BAD_DATE_VALUE);
    }

    /**
     * An "unsupported range" {@link DateValidationResult} (severity {@code 3}, message
     * {@link #CEE_MSG_UNSUPP_RANGE 2513}, COBOL {@code FC-UNSUPP-RANGE}) - a well-formed date before
     * {@code 1582-10-15} that {@code CORPT00C} explicitly tolerates and proceeds to submit.
     *
     * @return a severity-3 / msg-2513 validation result
     */
    private static DateValidationResult unsupportedRangeResult() {
        return new DateValidationResult(false, 3, CEE_MSG_UNSUPP_RANGE, "Unsupp. Range", "",
                OptionalLong.empty(), ValidationOutcome.FC_UNSUPP_RANGE);
    }

    // ==========================================================================================
    // MAIN-PARA - mainEntry: first-entry / first-display / EVALUATE EIBAID dispatch
    // ==========================================================================================

    /**
     * {@code MAIN-PARA}, {@code IF EIBCALEN = 0}: entering the transaction with no COMMAREA
     * ({@link CardDemoContext#isNew()}) moves {@code 'COSGN00C'} to {@code CDEMO-TO-PROGRAM} and
     * performs {@code RETURN-TO-PREV-SCREEN}, producing an {@code XCTL} redirect to the sign-on
     * screen. The hand-off origin fields ({@code CDEMO-FROM-TRANID = 'CR00'},
     * {@code CDEMO-FROM-PROGRAM = 'CORPT00C'}, {@code CDEMO-PGM-CONTEXT = 0}) are written and no job
     * is submitted.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void mainEntry_firstEntryWithoutCommarea_redirectsToSignon() throws Exception {
        when(context.isNew()).thenReturn(true);
        when(context.getToProgram()).thenReturn(SIGNON_PROGRAM);

        ReportSubmitResult result = service.mainEntry(AidKey.ENTER, new CORPT00Form());

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        assertThat(result.hasMessage()).isFalse();
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);

        verify(context).setToProgram(SIGNON_PROGRAM);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).markEnter();
        verify(jobLauncher, never()).run(any(), any());
    }

    /**
     * {@code MAIN-PARA}, {@code IF NOT CDEMO-PGM-REENTER}: the first display of the transaction
     * within the conversation marks the program re-entered ({@link CardDemoContext#markReenter()})
     * and shows the empty report screen. Because {@code CORPT00C} does no pre-processing here, the
     * result is a message-free redisplay (not a redirect) and no job is submitted.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void mainEntry_firstDisplayWithinConversation_showsEmptyScreen() throws Exception {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);

        ReportSubmitResult result = service.mainEntry(AidKey.ENTER, new CORPT00Form());

        verify(context).markReenter();
        assertThat(result.isRedirect()).isFalse();
        assertThat(result.targetProgram()).isNull();
        assertThat(result.hasMessage()).isFalse();
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        verify(jobLauncher, never()).run(any(), any());
    }

    /**
     * {@code MAIN-PARA}, {@code EVALUATE EIBAID WHEN DFHPF3}: PF3 hard-codes {@code 'COMEN01C'} into
     * {@code CDEMO-TO-PROGRAM} (the program does not consult {@code CDEMO-FROM-PROGRAM}) and performs
     * {@code RETURN-TO-PREV-SCREEN}, redirecting to the main menu. The origin hand-off fields are
     * written and no job is submitted.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void mainEntry_pf3_redirectsToMainMenu() throws Exception {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(context.getToProgram()).thenReturn(MENU_PROGRAM);

        ReportSubmitResult result = service.mainEntry(AidKey.PF3, new CORPT00Form());

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(MENU_PROGRAM);
        verify(context).setToProgram(MENU_PROGRAM);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).markEnter();
        verify(jobLauncher, never()).run(any(), any());
    }

    /**
     * {@code MAIN-PARA}, {@code EVALUATE EIBAID WHEN OTHER}: any key other than ENTER or PF3 yields
     * the {@code CCDA-MSG-INVALID-KEY} error line and submits nothing.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void mainEntry_otherKey_returnsInvalidKeyMessage() throws Exception {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        ReportSubmitResult result = service.mainEntry(AidKey.OTHER, new CORPT00Form());

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verify(jobLauncher, never()).run(any(), any());
    }

    /**
     * A {@code null} AID collapses to the COBOL {@code WHEN OTHER} default, so it yields the same
     * invalid-key error as any unrecognized key.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void mainEntry_nullAid_treatedAsOtherKey() throws Exception {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        ReportSubmitResult result = service.mainEntry(null, new CORPT00Form());

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verify(jobLauncher, never()).run(any(), any());
    }

    /**
     * {@code MAIN-PARA}, {@code EVALUATE EIBAID WHEN DFHENTER}: ENTER on re-entry delegates to
     * {@code PROCESS-ENTER-KEY}. With no report-type flag set, that paragraph's {@code WHEN OTHER}
     * branch returns {@code 'Select a report type to print report...'} and submits nothing. This
     * exercises the {@code mainEntry} &rarr; {@code processEnterKey} dispatch end-to-end.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void mainEntry_enterWithNoReportType_delegatesToProcessEnterKey() throws Exception {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        ReportSubmitResult result = service.mainEntry(AidKey.ENTER, reportForm(null, null, null, null));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_SELECT_REPORT_TYPE);
        verify(jobLauncher, never()).run(any(), any());
    }

    // ==========================================================================================
    // PROCESS-ENTER-KEY - report-type selection (Monthly / Yearly / Custom) and job submission
    // ==========================================================================================

    /**
     * {@code PROCESS-ENTER-KEY}, {@code WHEN MONTHLYI ...}: selecting Monthly builds the current
     * calendar month window (first day &hellip; last day of the month, the COBOL "first-of-month,
     * add a month, subtract a day") and, once confirmed, submits the transaction-report job. The
     * captured {@link JobParameters} carry {@code reportType=Monthly}, the exact
     * {@code startDate}/{@code endDate}, and a unique {@code submitTimestamp} run token; the launched
     * {@link Job} is the injected {@code transactionReportJob}; and the result is the green success
     * line. "Today" is pinned to 2024-02-15 (a leap year) so the last-day computation is exact.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced
     */
    @Test
    void processEnterKey_monthly_submitsCurrentMonthWindow() throws Exception {
        ReportSubmitService spied = spy(service);
        doReturn(LocalDate.of(2024, 2, 15)).when(spied).currentDate();
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(new JobExecution(1L));

        ReportSubmitResult result = spied.processEnterKey(reportForm("Y", null, null, "Y"));

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionReportJob), captor.capture());
        JobParameters params = captor.getValue();
        assertThat(params.getString(PARAM_REPORT_TYPE)).isEqualTo(REPORT_MONTHLY);
        assertThat(params.getString(PARAM_START_DATE)).isEqualTo("2024-02-01");
        assertThat(params.getString(PARAM_END_DATE)).isEqualTo("2024-02-29");
        assertThat(params.getLong(PARAM_SUBMIT_TIMESTAMP)).isNotNull().isPositive();

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo(REPORT_MONTHLY + SUCCESS_MSG_SUFFIX);
    }

    /**
     * {@code PROCESS-ENTER-KEY}, {@code WHEN YEARLYI ...}: selecting Yearly builds the current
     * calendar year window ({@code YYYY-01-01} &hellip; {@code YYYY-12-31}) and, once confirmed,
     * submits the job exactly once. "Today" is pinned to 2024-07-19 so the year window is exact.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced
     */
    @Test
    void processEnterKey_yearly_submitsCurrentYearWindow() throws Exception {
        ReportSubmitService spied = spy(service);
        doReturn(LocalDate.of(2024, 7, 19)).when(spied).currentDate();
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(new JobExecution(2L));

        ReportSubmitResult result = spied.processEnterKey(reportForm(null, "Y", null, "Y"));

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionReportJob), captor.capture());
        JobParameters params = captor.getValue();
        assertThat(params.getString(PARAM_REPORT_TYPE)).isEqualTo(REPORT_YEARLY);
        assertThat(params.getString(PARAM_START_DATE)).isEqualTo("2024-01-01");
        assertThat(params.getString(PARAM_END_DATE)).isEqualTo("2024-12-31");
        assertThat(params.getLong(PARAM_SUBMIT_TIMESTAMP)).isNotNull().isPositive();

        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo(REPORT_YEARLY + SUCCESS_MSG_SUFFIX);
    }

    /**
     * {@code PROCESS-ENTER-KEY}, {@code WHEN CUSTOMI ...}: a Custom report validates each MM/DD/YYYY
     * part via {@link DateConversionService#validateDate(String, String)} (the {@code CALL
     * 'CSUTLDTC'} equivalent, mask {@code 'YYYY-MM-DD'}). With both dates valid and the submission
     * confirmed, the assembled {@code YYYY-MM-DD} window is submitted; the launched job is the
     * injected {@code transactionReportJob}.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced
     */
    @Test
    void processEnterKey_customValidDates_submitsAssembledWindow() throws Exception {
        when(dateConversionService.validateDate(anyString(), eq(DATE_FORMAT_MASK)))
                .thenReturn(validDate());
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(new JobExecution(3L));

        ReportSubmitResult result =
                service.processEnterKey(customForm("01", "15", "2023", "03", "20", "2023", "Y"));

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionReportJob), captor.capture());
        JobParameters params = captor.getValue();
        assertThat(params.getString(PARAM_REPORT_TYPE)).isEqualTo(REPORT_CUSTOM);
        assertThat(params.getString(PARAM_START_DATE)).isEqualTo("2023-01-15");
        assertThat(params.getString(PARAM_END_DATE)).isEqualTo("2023-03-20");
        assertThat(params.getLong(PARAM_SUBMIT_TIMESTAMP)).isNotNull().isPositive();

        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo(REPORT_CUSTOM + SUCCESS_MSG_SUFFIX);
    }

    /**
     * {@code PROCESS-ENTER-KEY}, {@code WHEN CUSTOMI ...} NUMVAL-C normalization: single-digit month
     * and day parts are normalized to zero-padded {@code PIC 99} fields (the COBOL
     * {@code COMPUTE ... = FUNCTION NUMVAL-C(...)} then {@code MOVE} into the fixed-width field)
     * before the window is assembled, so {@code 1}/{@code 5} become {@code 01}/{@code 05}.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced
     */
    @Test
    void processEnterKey_customNormalizesSingleDigitParts_submitsPaddedWindow() throws Exception {
        when(dateConversionService.validateDate(anyString(), eq(DATE_FORMAT_MASK)))
                .thenReturn(validDate());
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(new JobExecution(4L));

        ReportSubmitResult result =
                service.processEnterKey(customForm("1", "5", "2023", "2", "9", "2023", "Y"));

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionReportJob), captor.capture());
        JobParameters params = captor.getValue();
        assertThat(params.getString(PARAM_START_DATE)).isEqualTo("2023-01-05");
        assertThat(params.getString(PARAM_END_DATE)).isEqualTo("2023-02-09");

        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
    }

    // ==========================================================================================
    // PROCESS-ENTER-KEY - Custom validation failures (each terminal; NO job submission)
    // ==========================================================================================

    /**
     * {@code WHEN CUSTOMI}, first empty check: an empty start-date month ({@code SDTMMI = SPACES})
     * yields {@code 'Start Date - Month can NOT be empty...'} and stops before any calendar check,
     * so {@link DateConversionService} is never consulted and no job is submitted.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void processEnterKey_customEmptyStartMonth_reportsErrorAndDoesNotSubmit() throws Exception {
        ReportSubmitResult result =
                service.processEnterKey(customForm("", "15", "2023", "03", "20", "2023", "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_START_MONTH_EMPTY);
        verify(jobLauncher, never()).run(any(), any());
        verifyNoInteractions(dateConversionService);
    }

    /**
     * {@code WHEN CUSTOMI}, month range check: a start-date month greater than {@code '12'} (after
     * NUMVAL-C normalization the field is numeric, so the COBOL reduces to {@code SDTMMI > '12'})
     * yields {@code 'Start Date - Not a valid Month...'}. The failure precedes the calendar check,
     * so {@link DateConversionService} is never consulted and no job is submitted.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void processEnterKey_customInvalidStartMonthRange_reportsErrorAndDoesNotSubmit() throws Exception {
        ReportSubmitResult result =
                service.processEnterKey(customForm("13", "15", "2023", "03", "20", "2023", "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_START_MONTH_INVALID);
        verify(jobLauncher, never()).run(any(), any());
        verifyNoInteractions(dateConversionService);
    }

    /**
     * {@code WHEN CUSTOMI}, calendar check: a well-formed but non-existent start date (30-February)
     * passes the numeric/range checks and reaches the {@code CSUTLDTC} step, where
     * {@link DateConversionService#validateDate(String, String)} reports a non-zero severity with a
     * message number other than {@link #CEE_MSG_UNSUPP_RANGE 2513}. The service returns
     * {@code 'Start Date - Not a valid date...'} and submits nothing. Only the start date is checked
     * because that failure is terminal.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void processEnterKey_customInvalidCalendarDate_reportsErrorAndDoesNotSubmit() throws Exception {
        when(dateConversionService.validateDate(anyString(), eq(DATE_FORMAT_MASK)))
                .thenReturn(badDateValueResult());

        ReportSubmitResult result =
                service.processEnterKey(customForm("02", "30", "2023", "03", "20", "2023", "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_START_DATE_INVALID);
        verify(jobLauncher, never()).run(any(), any());
    }

    // ==========================================================================================
    // PROCESS-ENTER-KEY - preserved CSUTLDTC quirk (message 2513 tolerated) (AAP 0.6.9)
    // ==========================================================================================

    /**
     * {@code WHEN CUSTOMI}, preserved quirk (AAP &sect;0.6.9): a well-formed date before
     * {@code 1582-10-15} makes {@code CSUTLDTC} report a non-zero severity with message
     * {@link #CEE_MSG_UNSUPP_RANGE 2513}. {@code CORPT00C} explicitly tolerates that specific
     * message and proceeds to submit, so both the start and end dates validate as acceptable and the
     * confirmed job is launched. Both dates are checked (the stub matches each call).
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced
     */
    @Test
    void processEnterKey_customUnsupportedRangeMessage2513_toleratedAndSubmits() throws Exception {
        when(dateConversionService.validateDate(anyString(), eq(DATE_FORMAT_MASK)))
                .thenReturn(unsupportedRangeResult());
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(new JobExecution(5L));

        ReportSubmitResult result =
                service.processEnterKey(customForm("01", "01", "1500", "12", "31", "1500", "Y"));

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionReportJob), captor.capture());
        assertThat(captor.getValue().getString(PARAM_REPORT_TYPE)).isEqualTo(REPORT_CUSTOM);
        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo(REPORT_CUSTOM + SUCCESS_MSG_SUFFIX);
    }

    // ==========================================================================================
    // SUBMIT-JOB-TO-INTRDR - confirm gate (CONFIRMI = blank / Y / N / other)
    // ==========================================================================================

    /**
     * {@code SUBMIT-JOB-TO-INTRDR}, {@code IF CONFIRMI = SPACES OR LOW-VALUES}: a resolved report
     * type with a blank confirmation is not submitted; instead the confirm prompt
     * {@code 'Please confirm to print the <name> report...'} is returned so the user can confirm.
     * This uses the real (un-pinned) {@code currentDate()} because the Monthly window value is
     * irrelevant to the assertion.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void processEnterKey_blankConfirmation_promptsAndDoesNotSubmit() throws Exception {
        ReportSubmitResult result = service.processEnterKey(reportForm("Y", null, null, ""));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message())
                .isEqualTo("Please confirm to print the " + REPORT_MONTHLY + " report...");
        verify(jobLauncher, never()).run(any(), any());
    }

    /**
     * {@code SUBMIT-JOB-TO-INTRDR}, {@code WHEN CONFIRMI = 'N' OR 'n'}: declining the confirmation
     * performs {@code INITIALIZE-ALL-FIELDS} (clearing every report-type and date field, and the
     * confirmation) and redisplays a message-free screen. No job is submitted.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void processEnterKey_confirmationN_clearsFieldsAndDoesNotSubmit() throws Exception {
        CORPT00Form form = reportForm("Y", null, null, "N");

        ReportSubmitResult result = service.processEnterKey(form);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.hasMessage()).isFalse();
        // INITIALIZE-ALL-FIELDS cleared the editable inputs to the empty string.
        assertThat(form.getMonthly()).isEmpty();
        assertThat(form.getConfirm()).isEmpty();
        verify(jobLauncher, never()).run(any(), any());
    }

    /**
     * {@code SUBMIT-JOB-TO-INTRDR}, {@code WHEN OTHER}: a confirmation value that is neither
     * {@code Y}/{@code y} nor {@code N}/{@code n} yields
     * {@code '"<value>" is not a valid value to confirm...'} and submits nothing.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced in a verify
     */
    @Test
    void processEnterKey_invalidConfirmationValue_reportsErrorAndDoesNotSubmit() throws Exception {
        ReportSubmitResult result = service.processEnterKey(reportForm("Y", null, null, "X"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo("\"X\" is not a valid value to confirm...");
        verify(jobLauncher, never()).run(any(), any());
    }

    /**
     * {@code SUBMIT-JOB-TO-INTRDR}, {@code WHEN CONFIRMI = 'Y' OR 'y'}: the lowercase {@code 'y'}
     * confirmation is accepted exactly like uppercase {@code 'Y'} and submits the job once. "Today"
     * is pinned so the Monthly branch is deterministic.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced
     */
    @Test
    void processEnterKey_lowercaseYConfirmation_submitsJob() throws Exception {
        ReportSubmitService spied = spy(service);
        doReturn(LocalDate.of(2024, 3, 10)).when(spied).currentDate();
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(new JobExecution(6L));

        ReportSubmitResult result = spied.processEnterKey(reportForm("Y", null, null, "y"));

        verify(jobLauncher).run(eq(transactionReportJob), any(JobParameters.class));
        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo(REPORT_MONTHLY + SUCCESS_MSG_SUFFIX);
    }

    // ==========================================================================================
    // WIRTE-JOBSUB-TDQ - launch failure maps to the COBOL WHEN OTHER response branch
    // ==========================================================================================

    /**
     * {@code WIRTE-JOBSUB-TDQ}, {@code EVALUATE WS-RESP-CD WHEN OTHER}: when the launch fails (the
     * checked {@link org.springframework.batch.core.JobExecutionException} family - here
     * {@link JobExecutionAlreadyRunningException} - thrown by {@link JobLauncher#run}), the service
     * reproduces the COBOL {@code 'Unable to Write TDQ (JOBS)...'} error line. That terminal outcome
     * is returned as-is (the success line is <em>not</em> produced), and the launch was attempted
     * exactly once.
     *
     * @throws Exception never; declared because {@link JobLauncher#run} is referenced
     */
    @Test
    void processEnterKey_jobLaunchFailure_reportsUnableToWriteTdq() throws Exception {
        when(jobLauncher.run(eq(transactionReportJob), any(JobParameters.class)))
                .thenThrow(new JobExecutionAlreadyRunningException("test-induced launch failure"));

        ReportSubmitResult result = service.processEnterKey(reportForm("Y", null, null, "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_UNABLE_TO_WRITE_TDQ);
        verify(jobLauncher).run(eq(transactionReportJob), any(JobParameters.class));
    }

    // ==========================================================================================
    // Design guarantee (AAP 0.6.4) and ReportSubmitResult contract
    // ==========================================================================================

    /**
     * Parity item 1 / AAP &sect;0.6.4 (no new external interface): {@code CORPT00C} performs no
     * VSAM/file I/O - it only submits a batch job. This asserts that design guarantee structurally:
     * the service declares no repository or persistence collaborator (no field whose type is a Spring
     * Data repository, JPA {@code EntityManager}, {@code JdbcTemplate}, or {@code DataSource}). Its
     * only collaborators are the session context, the date service, the job launcher, and the report
     * job.
     */
    @Test
    void service_hasNoRepositoryOrDataStoreCollaborators() {
        for (Field field : ReportSubmitService.class.getDeclaredFields()) {
            String typeName = field.getType().getName().toLowerCase(java.util.Locale.ROOT);
            assertThat(typeName)
                    .as("field '%s' (%s) must not be a data-store collaborator", field.getName(),
                            field.getType().getName())
                    .doesNotContain("repository")
                    .doesNotContain("entitymanager")
                    .doesNotContain("jdbctemplate")
                    .doesNotContain("datasource")
                    .doesNotContain("crudrepository")
                    .doesNotContain("jparepository");
        }
    }

    /**
     * {@link ReportSubmitResult} contract: the canonical constructor normalizes a {@code null}
     * message to the empty string and a {@code null} severity to {@link MessageSeverity#NONE}; a
     * blank or {@code null} target program is not a redirect; a non-blank target program is a
     * redirect; and {@link ReportSubmitResult#hasMessage()} reflects a non-empty message.
     */
    @Test
    void reportSubmitResult_normalizesNullsAndReportsPredicates() {
        ReportSubmitResult empty = new ReportSubmitResult(null, null, null);
        assertThat(empty.message()).isEmpty();
        assertThat(empty.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(empty.isRedirect()).isFalse();
        assertThat(empty.hasMessage()).isFalse();

        ReportSubmitResult blankTarget = new ReportSubmitResult("   ", "x", MessageSeverity.ERROR);
        assertThat(blankTarget.isRedirect()).isFalse();

        ReportSubmitResult redirect = new ReportSubmitResult(MENU_PROGRAM, "", MessageSeverity.NONE);
        assertThat(redirect.isRedirect()).isTrue();
        assertThat(redirect.targetProgram()).isEqualTo(MENU_PROGRAM);
        assertThat(redirect.hasMessage()).isFalse();

        ReportSubmitResult error = new ReportSubmitResult(null, MSG_INVALID_KEY, MessageSeverity.ERROR);
        assertThat(error.isRedirect()).isFalse();
        assertThat(error.hasMessage()).isTrue();
        assertThat(error.message()).isEqualTo(MSG_INVALID_KEY);
        assertThat(error.severity()).isEqualTo(MessageSeverity.ERROR);
    }
}
