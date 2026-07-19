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
package com.aws.carddemo.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.batch.JobExecutionEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Translates a batch {@link JobExecution}'s Spring Batch {@link ExitStatus} into the
 * mainframe {@code RETURN-CODE} the JCL scheduler observed, and exposes it to the JVM as
 * the operating-system process exit code.
 *
 * <h2>Why this bean exists (COBOL/JCL parity)</h2>
 *
 * <p>On the mainframe every batch program set a numeric {@code RETURN-CODE} that the JCL
 * job step surfaced as its condition code, and downstream steps gated on it with
 * {@code COND}/{@code IF MAXCC} logic. The CardDemo batch programs use the canonical
 * three-value convention: {@code 0} = clean completion, {@code 4} = completed with
 * rejects (a warning &mdash; e.g. {@code CBTRN02C} sets {@code RETURN-CODE = 4} when
 * {@code WS-REJECT-COUNT > 0}), and {@code 8} = an abend / I/O failure. Preserving that
 * {@code 0}/{@code 4}/{@code 8} contract at the process boundary is mandated by the
 * migration specification (AAP &sect;0.4.4, &sect;0.8.1, &sect;0.9.6) so that the CI/CD
 * scheduler &mdash; the JCL replacement &mdash; can gate on a non-zero exit exactly as
 * the legacy {@code COND} chains did.
 *
 * <h2>Why the framework default is insufficient</h2>
 *
 * <p>Spring Boot's own {@code JobExecutionExitCodeGenerator} derives the exit code from the
 * {@link BatchStatus} ordinal. That is inadequate for this contract for two reasons: a
 * completed-with-rejects run carries {@code BatchStatus.COMPLETED} (its {@code 4} lives only in
 * the {@link ExitStatus} <em>code string</em> {@code "COMPLETED_WITH_REJECTS"}), so the framework
 * would emit {@code 0} and lose the warning; and a failed run maps to the
 * {@code BatchStatus.FAILED} ordinal ({@code 5}), not the {@code 8} the JCL model expects. This
 * generator therefore combines both signals: it uses the authoritative {@link BatchStatus} to
 * decide success versus failure, and consults the {@link ExitStatus} <em>code string</em> only to
 * detect the rejects warning that the batch writers encode there.
 *
 * <h2>How it works</h2>
 *
 * <p>Spring Boot's {@code JobLauncherApplicationRunner} publishes one
 * {@link JobExecutionEvent} per job it executes at startup. This bean listens for those
 * events, retains each {@link JobExecution}, and &mdash; when the application is shutting
 * down through {@link org.springframework.boot.SpringApplication#exit} (invoked from
 * {@link com.aws.carddemo.CardDemoApplication#main} in non-web/batch mode) &mdash; reports
 * the <em>worst</em> (highest) mapped return code across every job that ran in the JVM.
 * Taking the maximum mirrors the mainframe {@code MAXCC}, the highest condition code seen
 * across the job's steps.
 *
 * <p>The mapping is:
 * <ul>
 *   <li>any non-{@link BatchStatus#COMPLETED} status ({@code FAILED}, {@code STOPPED},
 *       {@code ABANDONED}, {@code UNKNOWN}, &hellip;) &rarr; {@value #RC_FAILED};</li>
 *   <li>{@code BatchStatus.COMPLETED} whose {@link ExitStatus} code is
 *       {@code "COMPLETED_WITH_REJECTS"} &rarr; {@value #RC_COMPLETED_WITH_REJECTS};</li>
 *   <li>{@code BatchStatus.COMPLETED} with any other exit-status code &mdash; {@code "COMPLETED"}
 *       <em>or</em> {@code "NOOP"} (the code a restart with no remaining work completes with)
 *       &rarr; {@value #RC_COMPLETED}.</li>
 * </ul>
 *
 * <p>Deciding success on the {@link BatchStatus} rather than on the {@link ExitStatus} code string
 * is deliberate: a genuinely successful run can carry a non-{@code "COMPLETED"} exit code (most
 * notably {@code "NOOP"} on a no-op restart), and such a run must still map to {@value #RC_COMPLETED},
 * not be mistaken for a failure.
 *
 * <p>The {@code "COMPLETED_WITH_REJECTS"} string is the frozen coordination contract shared
 * with the batch writers (for example {@code DailyTransactionPostingWriter} and
 * {@code TransactionJpaItemWriter}); it must match theirs exactly and must not be changed in
 * isolation.
 *
 * <p>When no job ran in the JVM (for example the online web application, where
 * {@code spring.batch.job.enabled} is {@code false}) the retained-executions list is empty
 * and {@link #getExitCode()} returns {@value #RC_COMPLETED}. In web mode
 * {@link com.aws.carddemo.CardDemoApplication#main} never calls {@code SpringApplication.exit},
 * so this generator is inert and the server keeps running.
 */
@Component
public class BatchExitCodeGenerator implements ApplicationListener<JobExecutionEvent>, ExitCodeGenerator {

    /** Clean completion &mdash; mainframe {@code RETURN-CODE = 0}. */
    public static final int RC_COMPLETED = 0;

    /** Completed with rejects (warning) &mdash; mainframe {@code RETURN-CODE = 4}. */
    public static final int RC_COMPLETED_WITH_REJECTS = 4;

    /** Abend / failure &mdash; mainframe {@code RETURN-CODE = 8}. */
    public static final int RC_FAILED = 8;

    /**
     * The {@link ExitStatus} code published by the batch writers when a run completes but at
     * least one record was rejected. Frozen coordination contract; must match the writers.
     */
    static final String COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchExitCodeGenerator.class);

    /**
     * Every {@link JobExecution} that ran in this JVM, captured from the
     * {@link JobExecutionEvent}s the launcher publishes. A {@link CopyOnWriteArrayList} is used
     * because event publication and the shutdown-time {@link #getExitCode()} read may occur on
     * different threads.
     */
    private final List<JobExecution> executions = new CopyOnWriteArrayList<>();

    /**
     * Records the {@link JobExecution} carried by a launcher-published event so its outcome can
     * be folded into the process exit code at shutdown.
     *
     * @param event the job-execution event published by
     *              {@code JobLauncherApplicationRunner}; never {@code null}
     */
    @Override
    public void onApplicationEvent(JobExecutionEvent event) {
        executions.add(event.getJobExecution());
    }

    /**
     * Returns the process exit code: the worst (highest) mapped return code across every job
     * that ran in this JVM, or {@value #RC_COMPLETED} if none ran. Consulted by
     * {@link org.springframework.boot.SpringApplication#exit} during shutdown.
     *
     * @return {@value #RC_COMPLETED}, {@value #RC_COMPLETED_WITH_REJECTS}, or
     *         {@value #RC_FAILED}
     */
    @Override
    public int getExitCode() {
        int code = RC_COMPLETED;
        for (JobExecution execution : executions) {
            code = Math.max(code, mapReturnCode(execution));
        }
        if (code != RC_COMPLETED) {
            LOGGER.info("Batch process exit code resolved to {} (mainframe RETURN-CODE parity).", code);
        }
        return code;
    }

    /**
     * Maps a single {@link JobExecution}'s {@link ExitStatus} code to the {@code 0}/{@code 4}/
     * {@code 8} return-code contract.
     *
     * @param execution the completed job execution; never {@code null}
     * @return the mapped return code
     */
    private int mapReturnCode(JobExecution execution) {
        // The authoritative success/failure signal is the BatchStatus, not the ExitStatus code
        // string. Any non-COMPLETED status (FAILED, STOPPED, ABANDONED, UNKNOWN, ...) is the
        // mainframe abend return code 8.
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            return RC_FAILED;
        }
        // Within a completed run, the "completed with rejects" warning lives only in the
        // ExitStatus code string the batch writers publish; it bumps the clean 0 to the warning
        // code 4. Every other completed exit code -- "COMPLETED" and, importantly, "NOOP" (a
        // restart that had no remaining work) -- is a clean RC 0.
        ExitStatus exitStatus = execution.getExitStatus();
        String code = exitStatus == null ? null : exitStatus.getExitCode();
        if (COMPLETED_WITH_REJECTS.equals(code)) {
            return RC_COMPLETED_WITH_REJECTS;
        }
        return RC_COMPLETED;
    }
}
