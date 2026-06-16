package com.carddemo.config;

import java.util.Objects;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;

/**
 * Dedicated, <strong>asynchronous</strong> submission port for online "fire-and-forget" report jobs.
 *
 * <h2>Migration role (AAP &sect;0.3.1 / &sect;0.3.2)</h2>
 * <p>The legacy CICS online report program {@code app/cbl/CORPT00C.cbl} handed a JCL job to the CICS
 * internal reader ({@code SUBMIT-JOB-TO-INTRDR}) and returned to the operator <em>immediately</em>,
 * never blocking on the report's completion. This component reproduces that submit-and-return
 * semantics in the Spring Boot stack: it wraps a Spring Batch {@link TaskExecutorJobLauncher} that is
 * backed by the bounded asynchronous {@code taskExecutor} ({@link AsyncConfig#taskExecutor()}), so
 * {@link #submit(Job, JobParameters)} dispatches the job to a worker thread and returns promptly with a
 * non-terminal ({@code STARTING}/{@code STARTED}) {@link JobExecution} whose id is already persisted.
 * {@code com.carddemo.service.ReportService} injects this port and echoes that {@code JobExecution}
 * identifier back to the caller as the report reference.</p>
 *
 * <h2>Why a wrapper instead of exposing a second {@code JobLauncher} bean</h2>
 * <p>This type is deliberately <strong>not</strong> a {@link JobLauncher} and the asynchronous
 * {@link TaskExecutorJobLauncher} it delegates to is <strong>not</strong> registered as a Spring bean
 * (it is constructed and held privately by the {@code reportJobSubmitter} factory method in
 * {@link BatchConfig}). The application context therefore continues to contain <strong>exactly one</strong>
 * {@link JobLauncher} bean &mdash; Spring Boot's auto-configured, synchronous {@code jobLauncher}.</p>
 *
 * <p>This single-{@code JobLauncher}-bean invariant is load-bearing for the batch <em>tests</em>.
 * {@code spring-batch-test}'s {@code @SpringBatchTest} populates {@code JobLauncherTestUtils} by
 * <strong>type</strong> (its {@code BatchTestContextCustomizer} registers the helper with no explicit
 * autowiring, and the launcher is resolved through a by-type dependency descriptor that performs
 * <em>no</em> bean-name fallback). If a second, non-{@code @Primary} {@link JobLauncher} bean existed,
 * that by-type resolution would become ambiguous and &mdash; because it is lenient rather than
 * fatal &mdash; would silently inject {@code null}, making every {@code @SpringBatchTest} job test
 * (e.g. {@code AccountRefreshJobTest}, {@code CustomerRefreshJobTest}, and the posting/interest/
 * statement job tests mandated by AAP &sect;0.2.1.3) fail with a {@code NullPointerException}. Marking
 * the async launcher {@code @Primary} instead would hand those tests the asynchronous launcher and
 * break their deterministic {@code COMPLETED} assertions. Encapsulating the async launcher behind this
 * non-{@code JobLauncher} port avoids both pitfalls at the root: report submission is asynchronous,
 * yet the only {@code JobLauncher} bean any by-type consumer (including {@code JobLauncherTestUtils})
 * ever sees is the synchronous, auto-configured one.</p>
 *
 * <h2>Threading</h2>
 * <p>Stateless and immutable (its sole field is {@code final}); a single shared instance is safe for
 * concurrent submissions. Thread-safety of the actual launch is provided by the underlying
 * {@link TaskExecutorJobLauncher} and the bounded executor that backs it.</p>
 *
 * @see BatchConfig#reportJobSubmitter(org.springframework.batch.core.repository.JobRepository, org.springframework.core.task.TaskExecutor)
 * @see AsyncConfig#taskExecutor()
 * @see com.carddemo.service.ReportService
 * @see <a href="file:app/cbl/CORPT00C.cbl">CORPT00C.cbl</a>
 */
public class ReportJobSubmitter {

    /**
     * The asynchronous Spring Batch launcher this port delegates to (a {@link TaskExecutorJobLauncher}
     * backed by the bounded {@code taskExecutor}). Intentionally held as a private collaborator rather
     * than published as a {@link JobLauncher} bean &mdash; see the class Javadoc.
     */
    private final JobLauncher delegate;

    /**
     * Creates a submitter over the given asynchronous launcher.
     *
     * @param delegate the asynchronous {@link JobLauncher} (a {@link TaskExecutorJobLauncher} backed by
     *                 the bounded {@code taskExecutor}); must not be {@code null}
     * @throws NullPointerException if {@code delegate} is {@code null} (a fatal wiring error)
     */
    public ReportJobSubmitter(JobLauncher delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate JobLauncher must not be null");
    }

    /**
     * Submits a report job for asynchronous execution and returns immediately.
     *
     * <p>Because the underlying launcher is backed by the asynchronous {@code taskExecutor}, this call
     * schedules the job on a worker thread and returns a non-terminal ({@code STARTING}/{@code STARTED})
     * {@link JobExecution} whose id is already persisted &mdash; it does <em>not</em> wait for the job
     * to finish. This is the faithful Java analog of {@code CORPT00C}'s {@code SUBMIT-JOB-TO-INTRDR}
     * hand-off.</p>
     *
     * @param job        the report {@link Job} to launch; must not be {@code null}
     * @param parameters the {@link JobParameters} for this run; must not be {@code null}
     * @return the {@link JobExecution} produced by the launcher (already persisted, typically
     *         non-terminal at return time)
     * @throws JobExecutionException if Spring Batch cannot start the job (already-running, restart,
     *                               instance-already-complete, or invalid-parameters); the caller
     *                               ({@code ReportService}) translates this into an HTTP&nbsp;500
     */
    public JobExecution submit(Job job, JobParameters parameters) throws JobExecutionException {
        return delegate.run(job, parameters);
    }
}
