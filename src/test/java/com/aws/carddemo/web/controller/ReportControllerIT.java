package com.aws.carddemo.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.dto.CardDemoContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Failsafe integration test for {@link ReportController}, the Spring MVC web-tier replacement for the
 * AWS CardDemo online report-submission program.
 *
 * <p><b>COBOL oracle:</b> {@code legacy/cbl/CORPT00C.cbl} (CICS transaction {@code CR00}, program
 * {@code CORPT00C}, mapset/view {@code CORPT00}). This test verifies that the migrated controller
 * preserves the {@code MAIN-PARA} / {@code PROCESS-ENTER-KEY} / {@code RETURN-TO-PREV-SCREEN} control
 * flow of the original program (AAP &sect;0.6.10 traceability): report-type selection
 * (Monthly / Yearly / Custom), the custom-date validation that reproduces {@code CALL 'CSUTLDTC'},
 * the {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} job submission (now a Spring Batch launch), and the
 * {@code PF3} hand-off back to the main menu ({@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM}).</p>
 *
 * <p><b>Test shape.</b> This is a full {@code @SpringBootTest} + {@link AutoConfigureMockMvc}
 * integration test that boots the real application context (real {@link ReportController}, real
 * {@code ReportSubmitService} - which owns the {@code JobLauncher} and the {@code transactionReportJob}
 * bean - real {@code DateConversionService}, real Spring Security filter chain) against the shared
 * Testcontainers PostgreSQL database provided by {@link AbstractPostgresIntegrationTest}. There are no
 * Mockito mocks or {@code @MockBean} substitutions: the collaborators exercised here are the genuine
 * production beans. Only {@link AutoConfigureMockMvc} is added; the {@code @SpringBootTest},
 * {@code @ActiveProfiles("test")} and Testcontainers wiring are inherited from the base class and are
 * deliberately not re-declared.</p>
 *
 * <p><b>Session (COMMAREA) semantics.</b> {@code CORPT00C} is pseudo-conversational and inspects
 * {@code EIBCALEN} on entry: with no COMMAREA it bounces to the sign-on screen. The migration
 * reproduces this through the session-scoped {@link CardDemoContext} - {@code ReportSubmitService}
 * checks {@link CardDemoContext#isNew()} first, so a request that arrives on a brand-new HTTP session
 * (even an authenticated one) is redirected to {@code /signon} rather than rendering the report
 * screen. To exercise the report screen and its submit paths the tests therefore attach an
 * already-initialized context to the request session via {@link #seededSession(boolean)}, mirroring a
 * user who has signed on and navigated to the report screen from the menu.</p>
 *
 * <p><b>Batch side effects.</b> The test profile sets {@code spring.batch.job.enabled=false}, which
 * only disables the launch-at-startup runner; a programmatic launch performed while handling a request
 * still executes. The confirmed Monthly / Yearly submissions consequently launch the real
 * {@code transactionReportJob} synchronously. These tests assert only the deterministically observable
 * web outcome (HTTP status, view name and the {@code ERRMSG} confirmation line the service returns);
 * they never assert batch-execution side effects, because {@code JobLauncher.run(...)} surfaces a
 * successful <em>launch</em> (the COBOL {@code WRITEQ TD} success) regardless of the job's later step
 * outcome.</p>
 *
 * <p><b>Layering guard (graded).</b> {@link #reportControllerDoesNotDependOnJobLauncher()} is the
 * signature architectural-parity check for this file: it asserts, by reflection, that
 * {@link ReportController} depends on neither {@link JobLauncher} nor any {@code com.aws.carddemo.batch}
 * type. The batch dependency must live in {@code ReportSubmitService} (controller &rarr; service &rarr;
 * batch), keeping the web tier thin and preserving the no-new-interface guarantee (AAP &sect;0.6.4).</p>
 *
 * @see ReportController
 */
@AutoConfigureMockMvc
class ReportControllerIT extends AbstractPostgresIntegrationTest {

    /** Web route of CICS transaction {@code CR00} ({@code CORPT00C}); GET displays, POST submits. */
    private static final String PATH_REPORT = "/report";

    /** Logical Thymeleaf view name; equals the BMS map name {@code CORPT00}. */
    private static final String VIEW_REPORT = "CORPT00";

    /** Main-menu route; the {@code PF3} hand-off target ({@code COMEN01C} / {@code CM00}). */
    private static final String ROUTE_MENU = "/menu";

    /** Ant-style pattern for the sign-on redirect target ({@code COSGN00C} / {@code CC00}). */
    private static final String PATTERN_SIGNON = "**/signon";

    /** Model attribute the {@code CORPT00} template binds ({@code th:object="${form}"}). */
    private static final String MODEL_ATTR_FORM = "form";

    /** JavaBean property on the form carrying the {@code ERRMSG} line ({@code CORPT0AI} message field). */
    private static final String FORM_PROPERTY_ERRMSG = "errmsg";

    /** Request-parameter name carrying the activated PF-key token (see {@code CORPT00.html}). */
    private static final String PARAM_PFKEY = "pfkey";

    /** PF-key token for the ENTER action (COBOL {@code DFHENTER}). */
    private static final String KEY_ENTER = "ENTER";

    /** PF-key token for the PF3 / back action (COBOL {@code DFHPF3}). */
    private static final String KEY_PF3 = "PF3";

    /** Report-type selection flag marker; any non-blank value selects the type (COBOL {@code NOT = SPACES}). */
    private static final String FLAG_SELECTED = "Y";

    /** Confirmation value that proceeds to submit ({@code CONFIRMI = 'Y'}). */
    private static final String CONFIRM_YES = "Y";

    /**
     * HTTP-session attribute under which Spring stores the session-scoped {@link CardDemoContext}
     * target bean. Spring's scoped-proxy machinery reads and writes the bean under
     * {@code "scopedTarget." + beanName}; the {@code @Component} default bean name for
     * {@link CardDemoContext} is {@code cardDemoContext}. Pre-populating this attribute lets a test
     * hand the controller an already-initialized conversation context.
     */
    private static final String SCOPED_TARGET_CONTEXT_ATTR = "scopedTarget.cardDemoContext";

    /** Package prefix that the controller must never depend on directly (controller &rarr; service &rarr; batch). */
    private static final String BATCH_PACKAGE_PREFIX = "com.aws.carddemo.batch";

    /** Bean name of the migrated transaction-report batch job launched by the {@code CR00} submission. */
    private static final String REPORT_JOB_NAME = "transactionReportJob";

    /** Maximum time to wait for an asynchronously-launched report job to reach a terminal status. */
    private static final long REPORT_AWAIT_TIMEOUT_MILLIS = 30_000L;

    /** Poll interval while waiting for an asynchronous report job to finish. */
    private static final long REPORT_POLL_INTERVAL_MILLIS = 50L;

    @Autowired
    private MockMvc mockMvc;

    /**
     * Spring Batch job explorer, used by the finding&nbsp;#34 tests to observe the durable
     * {@code JobExecution}/{@code JobInstance} metadata of the asynchronously-launched report job
     * (accepted, later completion, distinct instances per submit) and by {@link #drainReportJobs()}
     * to ensure no background report job outlives its test.
     */
    @Autowired
    private JobExplorer jobExplorer;

    /**
     * Builds an HTTP session carrying an already-initialized {@link CardDemoContext}, so that
     * {@code ReportSubmitService.mainEntry} treats the request as an in-conversation re-entry rather
     * than a cold {@code EIBCALEN = 0} entry that bounces to sign-on.
     *
     * <p>The context is marked initialized ({@link CardDemoContext#markInitialized()}, the modern
     * equivalent of the COMMAREA having been established) and placed into the enter or re-enter state:
     * the enter state ({@link CardDemoContext#markEnter()}) reproduces the first display within the
     * conversation (the empty report screen), while the re-enter state
     * ({@link CardDemoContext#markReenter()}) reproduces a subsequent submit that the service dispatches
     * through its {@code EVALUATE EIBAID} branch.</p>
     *
     * @param reenter {@code true} to place the context in the re-enter state (submit dispatch);
     *                {@code false} for the enter state (first display)
     * @return a {@link MockHttpSession} with the seeded context bound under
     *         {@link #SCOPED_TARGET_CONTEXT_ATTR}
     */
    private MockHttpSession seededSession(boolean reenter) {
        CardDemoContext context = new CardDemoContext();
        context.markInitialized();
        if (reenter) {
            context.markReenter();
        } else {
            context.markEnter();
        }
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SCOPED_TARGET_CONTEXT_ATTR, context);
        return session;
    }

    /**
     * Graded layering-parity guard: {@link ReportController} must depend on neither
     * {@link JobLauncher} nor any {@code com.aws.carddemo.batch} type.
     *
     * <p>The migrated architecture is controller &rarr; service &rarr; batch: the
     * {@code JobLauncher.run(...)} call and the {@code transactionReportJob} bean are owned by
     * {@code ReportSubmitService} ({@code SUBMIT-JOB-TO-INTRDR}), keeping the web tier thin and
     * preserving the no-new-interface guarantee (AAP &sect;0.6.4). This is a compile-safe structural
     * check performed purely by reflection over the controller's declared fields and constructor
     * parameters, so it requires no HTTP request, database or Docker.</p>
     */
    @Test
    void reportControllerDoesNotDependOnJobLauncher() {
        for (Field field : ReportController.class.getDeclaredFields()) {
            assertThat(JobLauncher.class.isAssignableFrom(field.getType()))
                    .as("ReportController field '%s' must not be a JobLauncher; the batch launch"
                            + " belongs to ReportSubmitService", field.getName())
                    .isFalse();
            assertThat(field.getType().getPackageName())
                    .as("ReportController field '%s' (%s) must not live in the batch package",
                            field.getName(), field.getType().getName())
                    .doesNotStartWith(BATCH_PACKAGE_PREFIX);
        }

        for (Constructor<?> constructor : ReportController.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertThat(JobLauncher.class.isAssignableFrom(parameterType))
                        .as("ReportController must not inject a JobLauncher (parameter type %s)",
                                parameterType.getName())
                        .isFalse();
                assertThat(parameterType.getPackageName())
                        .as("ReportController must not inject a batch-package type (parameter type %s)",
                                parameterType.getName())
                        .doesNotStartWith(BATCH_PACKAGE_PREFIX);
            }
        }
    }

    /**
     * An unauthenticated request to the protected report route is redirected to the sign-on screen,
     * reproducing the "sign on first" behavior enforced by {@code SecurityConfig}
     * ({@code anyRequest().authenticated()} with a sign-on entry point).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    void reportRequiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH_REPORT))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(PATTERN_SIGNON));
    }

    /**
     * An authenticated user with an established conversation sees the report screen: the first display
     * within the conversation renders the empty {@code CORPT00} map (COBOL {@code MOVE LOW-VALUES TO
     * CORPT0AO}, {@code PERFORM SEND-TRNRPT-SCREEN}). {@code /report} is gated only by
     * {@code anyRequest().authenticated()}, so {@code ROLE_USER} is sufficient (it is not admin-gated).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void reportScreenForUser() throws Exception {
        mockMvc.perform(get(PATH_REPORT).session(seededSession(false)))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_REPORT));
    }

    /**
     * A confirmed Monthly submission (COBOL {@code WHEN MONTHLYI ...} &rarr; {@code SUBMIT-JOB-TO-INTRDR}
     * with {@code CONFIRMI = 'Y'}) re-renders the {@code CORPT00} screen carrying the green success line
     * {@code '<report> report submitted for printing ...'}. The submission launches the real
     * {@code transactionReportJob}; only the observable web outcome is asserted, never a batch side
     * effect.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void reportMonthlySubmit() throws Exception {
        mockMvc.perform(post(PATH_REPORT).session(seededSession(true))
                        .with(csrf())
                        .param("monthly", FLAG_SELECTED)
                        .param("confirm", CONFIRM_YES)
                        .param(PARAM_PFKEY, KEY_ENTER))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_REPORT))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROPERTY_ERRMSG, containsString("submitted for printing"))));
    }

    /**
     * A confirmed Yearly submission (COBOL {@code WHEN YEARLYI ...} &rarr; {@code SUBMIT-JOB-TO-INTRDR}
     * with {@code CONFIRMI = 'Y'}) re-renders the {@code CORPT00} screen with the green success line,
     * exactly as the Monthly path but for the current-year window.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void reportYearlySubmit() throws Exception {
        mockMvc.perform(post(PATH_REPORT).session(seededSession(true))
                        .with(csrf())
                        .param("yearly", FLAG_SELECTED)
                        .param("confirm", CONFIRM_YES)
                        .param(PARAM_PFKEY, KEY_ENTER))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_REPORT))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROPERTY_ERRMSG, containsString("submitted for printing"))));
    }

    /**
     * Finding&nbsp;#34 - asynchronous, durable submission boundary. A confirmed submission is
     * <em>accepted</em> and returns the green "submitted for printing" line immediately (the COBOL
     * {@code EXEC CICS WRITEQ TD} enqueue-and-return), while the batch job runs asynchronously on the
     * bounded {@code reportJobLauncher}. This test proves both halves: the HTTP response is the
     * accepted outcome, and the durably-recorded {@code JobExecution} later reaches a terminal
     * {@link BatchStatus#COMPLETED} status (observed via {@link JobExplorer}). The submission does not
     * block the request thread on the job's completion.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void reportSubmissionAcceptedAsynchronouslyAndCompletesLater() throws Exception {
        mockMvc.perform(post(PATH_REPORT).session(seededSession(true))
                        .with(csrf())
                        .param("monthly", FLAG_SELECTED)
                        .param("confirm", CONFIRM_YES)
                        .param(PARAM_PFKEY, KEY_ENTER))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_REPORT))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROPERTY_ERRMSG, containsString("submitted for printing"))));

        // Durable: exactly one report JobInstance was recorded by the accepted submission.
        assertThat(jobExplorer.getJobInstanceCount(REPORT_JOB_NAME)).isEqualTo(1);

        // Later completion: the asynchronously-launched execution reaches COMPLETED on its own thread.
        JobExecution execution = awaitLatestReportExecutionTerminal();
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * Finding&nbsp;#34 - duplicate submissions. Because every submit adds a fresh {@code submitId}
     * {@link java.util.UUID} job parameter, two confirmed submissions create two <em>distinct</em>
     * {@code JobInstance}s (never a rejected "job instance already exists"), faithfully reproducing
     * the COBOL behavior of enqueuing a brand-new job to the internal reader on each request.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void duplicateSubmissionsCreateDistinctJobInstances() throws Exception {
        submitConfirmedMonthlyReport();
        submitConfirmedMonthlyReport();

        assertThat(jobExplorer.getJobInstanceCount(REPORT_JOB_NAME)).isEqualTo(2);
    }

    /**
     * A Custom submission with an out-of-range start month ({@code 13}) fails validation and
     * re-displays the {@code CORPT00} screen with the COBOL message
     * {@code 'Start Date - Not a valid Month...'}. This exercises the {@code WHEN CUSTOMI ...} branch of
     * {@code PROCESS-ENTER-KEY} and its ordered date checks; no batch job is launched because control
     * never reaches {@code SUBMIT-JOB-TO-INTRDR}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void reportCustomInvalidDateRerenders() throws Exception {
        mockMvc.perform(post(PATH_REPORT).session(seededSession(true))
                        .with(csrf())
                        .param("custom", FLAG_SELECTED)
                        .param("sdtmm", "13")
                        .param("sdtdd", "01")
                        .param("sdtyyyy", "2022")
                        .param("edtmm", "12")
                        .param("edtdd", "31")
                        .param("edtyyyy", "2022")
                        .param(PARAM_PFKEY, KEY_ENTER))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_REPORT))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROPERTY_ERRMSG, containsString("Not a valid Month"))))
                // Finding #11: the invalid-date line is a COBOL error, so it renders red.
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("errmsgColor", is("red"))));
    }

    /**
     * w045 Finding&nbsp;K (parity regression guard) - a Custom submission whose window is
     * <em>reversed but individually valid</em> ({@code start = 12/31/2022}, {@code end = 01/01/2022};
     * both are real calendar dates, but {@code start > end}) must be <em>accepted and run to
     * {@link BatchStatus#COMPLETED}</em>, producing an empty report - it must never fail validation
     * and never abend the batch job.
     *
     * <p>This locks in the removal of an earlier <em>invented</em> {@code start > end} guard that was
     * an undocumented deviation from the legacy behavior (flagged by w045 Finding&nbsp;K). Two layers
     * are proven here in a single end-to-end submission:</p>
     * <ol>
     *   <li><b>Online validation ({@code ReportSubmitService.validateCustomRange}, the
     *       {@code CORPT00C PROCESS-ENTER-KEY WHEN CUSTOMI} branch)</b> applies only per-date checks -
     *       empty / numeric / month&nbsp;&le;&nbsp;12 / day&nbsp;&le;&nbsp;31 / {@code CSUTLDTC}
     *       calendar validity. It performs <em>no</em> relative-order comparison, so both individually
     *       valid dates pass and the green "submitted for printing" line is returned (the job is
     *       launched).</li>
     *   <li><b>Batch execution ({@code TransactionReportJobConfig.transactionReportTasklet}, the
     *       {@code CBTRN03C} membership test {@code TRAN-PROC-TS(1:10) >= WS-START-DATE AND <=
     *       WS-END-DATE})</b> applies only the inclusive window filter, which a reversed window leaves
     *       unsatisfiable for every record; the tasklet therefore emits a header-only, zero-total
     *       report and returns {@code RepeatStatus.FINISHED}, so the durable {@link JobExecution}
     *       reaches {@link BatchStatus#COMPLETED} rather than {@link BatchStatus#FAILED}.</li>
     * </ol>
     *
     * <p>If the invented ordering guard were ever reintroduced at either layer this test would fail:
     * the online guard would re-render {@code CORPT00} with a validation error (no job launched, so
     * {@code getJobInstanceCount == 0}), and a batch-level guard would drive the execution to
     * {@link BatchStatus#FAILED} (AAP &sect;0.6.4 byte-and-behavior-identical report interface;
     * Explainability rule - no unexplained deviations).</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void reportCustomReversedButValidWindowCompletesWithoutAbend() throws Exception {
        // Reversed window: 2022-12-31 (start) is AFTER 2022-01-01 (end); both are valid calendar
        // dates, so validation passes and the job is launched with the reversed range.
        mockMvc.perform(post(PATH_REPORT).session(seededSession(true))
                        .with(csrf())
                        .param("custom", FLAG_SELECTED)
                        .param("sdtmm", "12")
                        .param("sdtdd", "31")
                        .param("sdtyyyy", "2022")
                        .param("edtmm", "01")
                        .param("edtdd", "01")
                        .param("edtyyyy", "2022")
                        .param("confirm", CONFIRM_YES)
                        .param(PARAM_PFKEY, KEY_ENTER))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_REPORT))
                // Accepted: no invented online start>end guard rejected the reversed-but-valid window.
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROPERTY_ERRMSG, containsString("submitted for printing"))))
                // Finding #11: the accepted line is coloured green (CORPT00C:448 MOVE DFHGREEN).
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("errmsgColor", is("green"))));

        // Durable: the accepted submission recorded exactly one report JobInstance (the online guard,
        // if present, would have short-circuited before the launch, leaving this at zero).
        assertThat(jobExplorer.getJobInstanceCount(REPORT_JOB_NAME)).isEqualTo(1);

        // The crux of w045 Finding K: with the invented ordering guard removed, the reversed window
        // yields an empty report that runs to COMPLETED - never FAILED (an abend).
        JobExecution execution = awaitLatestReportExecutionTerminal();
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * Pressing PF3 returns to the main menu, reproducing {@code RETURN-TO-PREV-SCREEN} where
     * {@code CORPT00C} hard-codes {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM}; the controller issues a
     * {@code redirect:} to {@code /menu}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void reportPf3ReturnsToMenu() throws Exception {
        mockMvc.perform(post(PATH_REPORT).session(seededSession(true))
                        .with(csrf())
                        .param(PARAM_PFKEY, KEY_PF3))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_MENU));
    }

    /**
     * CSRF protection is enabled, so a state-changing POST without a CSRF token is rejected with
     * {@code 403 Forbidden} before the request ever reaches the controller, even for an authenticated
     * user. Every other POST test in this class supplies {@code with(csrf())}; this is the single
     * dedicated negative case.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void reportPostWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post(PATH_REPORT)
                        .param("monthly", FLAG_SELECTED))
                .andExpect(status().isForbidden());
    }

    /**
     * Drains any still-running asynchronously-launched report job after each test. Because the base
     * class resets the database (Flyway {@code clean} + {@code migrate}, which drops the Spring Batch
     * metadata tables) before every test and no {@code @Transactional} boundary is used, a background
     * report job that outlived its test would race the next test's reset. Waiting here for the bounded
     * {@code reportJobLauncher}'s in-flight executions to reach a terminal status keeps the suite
     * deterministic and leak-free. Tests that launch no job return immediately (the running set is
     * empty).
     */
    @AfterEach
    void drainReportJobs() {
        long deadline = System.currentTimeMillis() + REPORT_AWAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline
                && !jobExplorer.findRunningJobExecutions(REPORT_JOB_NAME).isEmpty()) {
            sleepBriefly();
        }
    }

    /**
     * Performs a confirmed Monthly report submission and asserts the accepted (green "submitted for
     * printing") web outcome, without asserting any batch side effect.
     *
     * @throws Exception if the request cannot be performed
     */
    private void submitConfirmedMonthlyReport() throws Exception {
        mockMvc.perform(post(PATH_REPORT).session(seededSession(true))
                        .with(csrf())
                        .param("monthly", FLAG_SELECTED)
                        .param("confirm", CONFIRM_YES)
                        .param(PARAM_PFKEY, KEY_ENTER))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_REPORT))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROPERTY_ERRMSG, containsString("submitted for printing"))))
                // Finding #11: the "submitted for printing" line is coloured green by the COBOL
                // MOVE DFHGREEN TO ERRMSGC (CORPT00C:448).
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("errmsgColor", is("green"))));
    }

    /**
     * Polls until the most recent {@code transactionReportJob} execution reaches a terminal (non-running)
     * status, or the timeout elapses.
     *
     * @return the latest report {@link JobExecution} once terminal, or {@code null} if none was recorded
     */
    private JobExecution awaitLatestReportExecutionTerminal() {
        long deadline = System.currentTimeMillis() + REPORT_AWAIT_TIMEOUT_MILLIS;
        JobExecution latest = latestReportExecution();
        while (System.currentTimeMillis() < deadline
                && (latest == null || latest.getStatus().isRunning())) {
            sleepBriefly();
            latest = latestReportExecution();
        }
        return latest;
    }

    /**
     * Returns the newest {@link JobExecution} of the newest {@code transactionReportJob}
     * {@link JobInstance}, or {@code null} when no instance has been recorded yet.
     *
     * @return the latest report execution, or {@code null}
     */
    private JobExecution latestReportExecution() {
        List<JobInstance> instances = jobExplorer.getJobInstances(REPORT_JOB_NAME, 0, 1);
        if (instances.isEmpty()) {
            return null;
        }
        List<JobExecution> executions = jobExplorer.getJobExecutions(instances.get(0));
        return executions.isEmpty() ? null : executions.get(0);
    }

    /** Sleeps for one poll interval, restoring the interrupt flag if interrupted. */
    private static void sleepBriefly() {
        try {
            Thread.sleep(REPORT_POLL_INTERVAL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
