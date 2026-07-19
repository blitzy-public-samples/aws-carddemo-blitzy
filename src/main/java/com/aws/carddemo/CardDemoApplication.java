package com.aws.carddemo;

import com.aws.carddemo.batch.PostTransactionJobConfig;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.batch.JobExecutionEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot entry point for the migrated AWS CardDemo credit-card management application.
 *
 * <p>AWS CardDemo was originally an IBM z/OS COBOL/CICS/VSAM/JCL/BMS system; this class is the
 * {@code public static void main} bootstrap that launches its Java 25 + Spring Boot replacement.
 * It carries no business logic and holds no state &mdash; its sole responsibility is to start the
 * Spring application context.</p>
 *
 * <p>Traceability: every domain, service, repository, web and batch class produced by this
 * migration cites its originating {@code legacy/**} COBOL source path so that the COBOL-construct
 * to Java-artifact mapping reaches 100% bidirectional coverage. This launcher is the one exception:
 * it is a net-new bootstrap class with no legacy COBOL counterpart and is therefore
 * infrastructure-only. Migration rationale is recorded in {@code docs/decision-log.md}, not in
 * code comments.</p>
 *
 * <p>Because this type lives at the base package {@code com.aws.carddemo}, the
 * {@link SpringBootApplication} component scan discovers every sub-package and wires the layered
 * architecture: web &rarr; service &rarr; repository &rarr; PostgreSQL, the Spring Batch jobs, and
 * the cross-cutting security, observability and exception-handling concerns.</p>
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Launches the Spring application context for AWS CardDemo.
     *
     * <p><strong>Batch RETURN-CODE parity (AAP &sect;0.6.3 / &sect;0.6.4).</strong> When the
     * application is launched as a Spring Batch job (for example
     * {@code --spring.main.web-application-type=none --spring.batch.job.enabled=true
     * --spring.batch.job.name=postTransactionJob ...}), the job's outcome is translated into the
     * process exit code so an operator or scheduler observes the same RETURN-CODE contract the
     * legacy JCL steps produced: {@code 0} clean, {@code 4} completed with rejected records
     * (DALYREJS), {@code 8} failed / abended (the COBOL {@code 9999-ABEND-PROGRAM} equivalent).
     * The mapping is supplied by {@link BatchReturnCodeExitCodeGenerator}; without it a FAILED or
     * reject-bearing job would still exit {@code 0}, hiding the failure from batch automation.</p>
     *
     * <p>{@link SpringApplication#exit(org.springframework.context.ApplicationContext,
     * ExitCodeGenerator...)} aggregates the {@link ExitCodeGenerator} beans (taking the maximum
     * code) <em>and closes the context</em>. That is correct for a batch launch (the JVM should end
     * with the RETURN-CODE), but it must never run for a web server, whose non-daemon server thread
     * must keep the JVM alive to serve requests. The guard therefore skips the exit translation for
     * a {@link WebServerApplicationContext}; a web startup also runs no job
     * ({@code spring.batch.job.enabled=false} by default), so its exit code would be {@code 0}
     * regardless.</p>
     *
     * @param args command-line arguments forwarded to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        ConfigurableApplicationContext context =
                SpringApplication.run(CardDemoApplication.class, args);
        if (!(context instanceof WebServerApplicationContext)) {
            System.exit(SpringApplication.exit(context));
        }
    }

    /**
     * Registers the {@link BatchReturnCodeExitCodeGenerator} that maps a completed batch job's
     * outcome to the legacy RETURN-CODE ladder. Present in every mode; in web mode it simply
     * receives no {@link JobExecutionEvent} and reports {@code 0}.
     *
     * @return the batch RETURN-CODE exit-code generator
     */
    @Bean
    public BatchReturnCodeExitCodeGenerator batchReturnCodeExitCodeGenerator() {
        return new BatchReturnCodeExitCodeGenerator();
    }

    /**
     * Translates the outcome of the Spring Batch jobs executed in this JVM into the legacy JCL
     * RETURN-CODE ladder, reproducing the mainframe batch-trigger contract (AAP &sect;0.6.3 /
     * &sect;0.6.4).
     *
     * <p>It listens for the {@link JobExecutionEvent} that Spring Boot's
     * {@code JobLauncherApplicationRunner} publishes after each job it launches, and computes the
     * exit code from the accumulated executions:</p>
     * <ul>
     *   <li>{@link #RC_FAILED} ({@value #RC_FAILED}) if any job did not complete normally
     *       (status other than {@link BatchStatus#COMPLETED} &mdash; failed, stopped, abandoned);
     *       this is the {@code 9999-ABEND-PROGRAM} / {@code CEE3ABD} equivalent, including the
     *       duplicate-transaction abend reproduced by {@code PostTransactionJobConfig};</li>
     *   <li>{@link #RC_REJECTS} ({@value #RC_REJECTS}) if a job completed but reported the
     *       {@link PostTransactionJobConfig#EXIT_STATUS_WITH_REJECTS} exit status (records written
     *       to DALYREJS) &mdash; the RETURN-CODE 4 &ldquo;completed with warnings&rdquo; case;</li>
     *   <li>{@link #RC_CLEAN} ({@value #RC_CLEAN}) otherwise.</li>
     * </ul>
     *
     * <p>Failure dominates rejects, which dominate a clean completion. Spring Boot also
     * auto-registers its own {@code JobExecutionExitCodeGenerator} (which returns 0 for a clean or
     * reject-bearing completion and the {@link BatchStatus} ordinal for a failure); because
     * {@code SpringApplication.exit} takes the maximum across all generators, this generator's
     * ladder (0/4/8) is the effective, deterministic result.</p>
     */
    public static final class BatchReturnCodeExitCodeGenerator
            implements ApplicationListener<JobExecutionEvent>, ExitCodeGenerator {

        /** Clean completion &mdash; every record posted, no rejects (COBOL RETURN-CODE 0). */
        static final int RC_CLEAN = 0;

        /** Completed with rejected records written to DALYREJS (COBOL RETURN-CODE 4). */
        static final int RC_REJECTS = 4;

        /**
         * Job did not complete normally &mdash; failed / stopped / abandoned; the COBOL
         * {@code 9999-ABEND-PROGRAM} ({@code CEE3ABD}) equivalent (COBOL RETURN-CODE 8).
         */
        static final int RC_FAILED = 8;

        /** Job executions observed in this JVM (one per job the launcher ran). */
        private final List<JobExecution> executions = new ArrayList<>();

        /**
         * Records the completed job execution published by the batch launcher.
         *
         * @param event the job-execution event
         */
        @Override
        public void onApplicationEvent(JobExecutionEvent event) {
            executions.add(event.getJobExecution());
        }

        /**
         * Computes the process exit code from the observed job executions using the RETURN-CODE
         * ladder described in the class Javadoc.
         *
         * @return {@value #RC_CLEAN}, {@value #RC_REJECTS} or {@value #RC_FAILED}
         */
        @Override
        public int getExitCode() {
            int code = RC_CLEAN;
            for (JobExecution execution : executions) {
                if (execution.getStatus() != BatchStatus.COMPLETED) {
                    return RC_FAILED;
                }
                String exitCode = execution.getExitStatus() == null
                        ? null
                        : execution.getExitStatus().getExitCode();
                if (PostTransactionJobConfig.EXIT_STATUS_WITH_REJECTS.equals(exitCode)) {
                    code = Math.max(code, RC_REJECTS);
                }
            }
            return code;
        }
    }
}
