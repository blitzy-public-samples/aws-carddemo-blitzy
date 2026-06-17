package com.carddemo.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.config.ReportJobSubmitter;
import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Spring Boot integration test that pins the <strong>asynchronous, fire-and-forget</strong> contract
 * of report submission &mdash; the behaviour the legacy CICS program {@code app/cbl/CORPT00C.cbl}
 * exhibited when it handed a JCL job to the internal reader ({@code SUBMIT-JOB-TO-INTRDR}) and
 * returned to the operator immediately, never blocking on report completion
 * (AAP&nbsp;&sect;0.3.1 / &sect;0.3.2).
 *
 * <h2>What this test guards against (the C3 review's Major finding)</h2>
 * <p>The pure-Mockito {@link ReportServiceTest} mocks the submission port, so it cannot observe
 * whether a launch actually blocks. The C3 code review found that, with Spring Boot's
 * <em>auto-configured</em> {@code jobLauncher} (a {@code SyncTaskExecutor} under Spring Batch&nbsp;5's
 * {@code DefaultBatchConfiguration}), launching the report job executed it inline and only
 * returned once it had {@code COMPLETED} &mdash; violating the fire-and-forget requirement. The fix
 * routes submission through an <em>asynchronous</em> {@link ReportJobSubmitter} &mdash; a port that
 * wraps a {@code TaskExecutorJobLauncher} backed by the bounded {@code taskExecutor}, built in
 * {@code com.carddemo.config.BatchConfig} &mdash; which {@link ReportService} consumes. This test
 * proves, against the <strong>real {@code reportJobSubmitter} bean</strong>, that submission now
 * returns <em>before</em> the job finishes.</p>
 *
 * <h2>How it proves "returns before completion" deterministically</h2>
 * <p>A throwaway {@link Job} is built whose single tasklet <strong>blocks on a latch</strong> until the
 * test releases it. The job therefore cannot reach a terminal status on its own, which removes the race
 * a trivial job would introduce. The job is supplied to a {@link ReportService} under the map key
 * {@code "transactionReportJob"} (exactly what {@code ReportService.resolveReportJob()} looks up), while
 * its internal job <em>name</em> is deliberately distinct so this test's {@code BATCH_*} metadata stays
 * isolated from the real {@code transactionReportJob} bean.</p>
 * <ul>
 *   <li>The launch is wrapped in {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively
 *       assertTimeoutPreemptively}: an asynchronous launcher returns promptly; a synchronous one would
 *       block inside the tasklet and trip the timeout, failing the test fast (a clear regression
 *       signal).</li>
 *   <li>The returned {@link ReportResponse} must carry a persisted {@code jobExecutionId} and a
 *       <em>non-terminal</em> status ({@code STARTING}/{@code STARTED}); a synchronous launcher would
 *       instead have returned {@code COMPLETED}.</li>
 *   <li>A {@code jobStarted} latch confirms the job genuinely ran on a background worker thread.</li>
 *   <li>After release, the job is observed to run to completion out of band &mdash; the end-to-end
 *       fire-and-forget loop.</li>
 * </ul>
 *
 * <p>The mere fact that the {@code @SpringBootTest} context starts also proves that the
 * {@code reportJobSubmitter} bean exists and that {@link ReportService} wires it &mdash; a
 * missing submitter bean would fail context startup.</p>
 *
 * <p>Runs under the {@code test} profile (in-memory H2 in PostgreSQL mode); the {@code BATCH_*} metadata
 * tables are created by {@code spring.batch.jdbc.initialize-schema=always} and no job auto-runs
 * ({@code spring.batch.job.enabled=false}). No PII is used (AAP&nbsp;&sect;0.6.8, &sect;0.7.1).</p>
 *
 * @see ReportService
 * @see ReportJobSubmitter
 * @see com.carddemo.config.BatchConfig#reportJobSubmitter(JobRepository, org.springframework.core.task.TaskExecutor)
 * @see com.carddemo.config.AsyncConfig#taskExecutor()
 * @see <a href="file:app/cbl/CORPT00C.cbl">CORPT00C.cbl</a>
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportServiceAsyncSubmissionTest {

    /** Map key under which {@code ReportService} resolves the report job (its {@code JOB_NAME}). */
    private static final String REPORT_JOB_MAP_KEY = "transactionReportJob";

    /**
     * The <strong>asynchronous</strong> submission port under test: the {@code reportJobSubmitter} bean
     * from {@code BatchConfig}, autowired by type exactly as {@code ReportService} consumes it.
     */
    @Autowired
    private ReportJobSubmitter reportJobSubmitter;

    /** Auto-configured Spring Batch repository used to build the throwaway blocking job/step. */
    @Autowired
    private JobRepository jobRepository;

    /** Auto-configured transaction manager required by the tasklet step. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Real date validator; unused by the MONTHLY path but supplied to construct the service faithfully. */
    @Autowired
    private DateValidationService dateValidationService;

    @Test
    @DisplayName("submitReport returns a non-terminal (STARTING/STARTED) JobExecution before the report "
            + "job completes — the CORPT00C fire-and-forget contract")
    void submitReport_returnsBeforeJobCompletes() throws InterruptedException {
        // Latches coordinate the worker thread with the test: the tasklet signals it has started, then
        // blocks until the test releases it, then signals it has finished.
        final CountDownLatch jobStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch jobFinished = new CountDownLatch(1);

        Step blockingStep = new StepBuilder("asyncTestBlockingStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    jobStarted.countDown();
                    // Block so the job CANNOT reach a terminal status until the test releases it; a
                    // bounded await keeps a worker thread from hanging indefinitely if a test fails.
                    release.await(30, TimeUnit.SECONDS);
                    jobFinished.countDown();
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();

        // Distinct internal job NAME isolates this test's BATCH_* metadata from the real
        // transactionReportJob bean; the MAP KEY must be "transactionReportJob" because that is the
        // name ReportService.resolveReportJob() looks up.
        Job blockingJob = new JobBuilder("asyncTestBlockingReportJob", jobRepository)
                .start(blockingStep)
                .build();

        ReportService service = new ReportService(
                reportJobSubmitter,
                Map.of(REPORT_JOB_MAP_KEY, blockingJob),
                dateValidationService);

        try {
            // With the asynchronous reportJobSubmitter this returns promptly. With a synchronous launcher
            // (the regression) the call would block inside the tasklet's await(...) and exceed the
            // timeout, failing the test fast.
            ReportResponse resp = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> service.submitReport(new ReportRequest("MONTHLY", null, null)));

            // The execution id is persisted at submission time and surfaced immediately.
            assertThat(resp.jobExecutionId())
                    .as("a persisted JobExecution id must be returned at submission time")
                    .isNotNull();

            // The status is non-terminal because the job is still blocked on the latch; a synchronous
            // launcher would instead have returned COMPLETED.
            assertThat(resp.status())
                    .as("report job must be submitted asynchronously (non-terminal status at return)")
                    .isIn("STARTING", "STARTED");

            // The job genuinely began on a background worker thread — further proof of async dispatch.
            assertThat(jobStarted.await(5, TimeUnit.SECONDS))
                    .as("the report job should have started on a background thread")
                    .isTrue();
        } finally {
            // Unblock the worker so the job completes and the async pool drains cleanly.
            release.countDown();
        }

        // The released job runs to completion out of band — the end-to-end fire-and-forget loop.
        assertThat(jobFinished.await(10, TimeUnit.SECONDS))
                .as("the report job should run to completion asynchronously after release")
                .isTrue();
    }
}
