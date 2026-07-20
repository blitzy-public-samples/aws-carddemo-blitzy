/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.batch;

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
 * Maps the outcome of a batch {@link JobExecution} to the mainframe batch <em>return code</em>
 * (RC) contract {@code 0 / 4 / 8}, so that a CardDemo batch launch terminates the JVM with the
 * process exit code its COBOL/JCL predecessor produced.
 *
 * <p><strong>Why this exists (parity contract).</strong> On the mainframe, a JCL-triggered batch
 * step ends with a numeric condition code that downstream JCL and operators branch on:
 * {@code 0} = clean success, {@code 4} = completed with warnings (here: some input records were
 * rejected), {@code 8} = failure/abend. The Technical Specification lists the batch return code as
 * a <em>preserved observable contract</em> (&sect;0.8.3) and requires typed batch outcomes to map to
 * "batch return codes 0/4/8" (&sect;0.2.2, &sect;0.4.2 exception-translation, &sect;0.9.6). Plain
 * {@code SpringApplication.run(...)} always exits {@code 0}, which silently breaks that contract:
 * a job whose {@link BatchStatus} is {@code FAILED} would still exit the process {@code 0}. This
 * generator restores the contract.
 *
 * <p><strong>How the outcome is observed.</strong> Spring Boot's
 * {@code JobLauncherApplicationRunner} publishes a {@link JobExecutionEvent} for every job it runs
 * at startup. This component listens for those events and records each {@link JobExecution}. At
 * shutdown, {@link org.springframework.boot.SpringApplication#exit(org.springframework.context.ConfigurableApplicationContext, ExitCodeGenerator...)
 * SpringApplication.exit(...)} collects every {@link ExitCodeGenerator} bean and takes the
 * <em>maximum</em> reported code; because {@code 0 < 4 < 8} is both the numeric and the
 * severity ordering, "worst outcome wins" across one or more executions in a single launch.
 *
 * <p><strong>Per-execution mapping.</strong> Single-step CardDemo jobs propagate their step's
 * {@link ExitStatus} to the {@link JobExecution}. The writer of the posting job sets one of three
 * terminal exit codes in its {@code afterStep} (see
 * {@code DailyTransactionPostingWriter}); every other job ends {@code COMPLETED} or {@code FAILED}:
 * <ul>
 *   <li>{@code "COMPLETED"} &rarr; {@code 0};</li>
 *   <li>{@code "COMPLETED_WITH_REJECTS"} &rarr; {@code 4} (the literal is a coordination contract
 *       with the posting writer);</li>
 *   <li>any other exit code, or a {@link BatchStatus} of {@code FAILED} / {@code ABANDONED} /
 *       {@code STOPPED} / {@code UNKNOWN} &rarr; {@code 8}.</li>
 * </ul>
 * The {@link BatchStatus} escalation is defensive: it guarantees an abnormal termination maps to
 * {@code 8} even if the exit-code string were unexpectedly benign.
 *
 * <p><strong>Coexistence with Spring Boot's own generator.</strong> Boot's
 * {@code BatchAutoConfiguration.jobExecutionExitCodeGenerator()} is annotated
 * {@code @ConditionalOnMissingBean(ExitCodeGenerator.class)}, so registering this
 * {@link ExitCodeGenerator} bean makes Boot's generator back off and this becomes the single
 * authority for the launch's exit code. Even if both were ever present, {@code SpringApplication.exit}
 * takes the maximum, and this generator's {@code 0/4/8} dominates Boot's coarser
 * {@code BatchStatus}-ordinal mapping for every CardDemo outcome, so the result is identical.
 *
 * <p><strong>Web launches are unaffected.</strong> The default (web) launch never invokes
 * {@code SpringApplication.exit(...)} (see {@code CardDemoApplication}), so this bean is inert there;
 * it only influences the process exit code of a dedicated batch launch
 * ({@code --spring.batch.job.enabled=true}, non-web application context). No job events means no
 * recorded executions, and {@link #getExitCode()} returns {@code 0}.
 *
 * <p>This class is intentionally a standalone {@code @Component} rather than a {@code @Bean} in
 * {@code BatchConfig}: {@code BatchConfig} deliberately declares no beans (so Spring Boot batch
 * auto-configuration is not suppressed), and component-scanning keeps this cross-cutting concern
 * independently reviewable.
 */
@Component
public class BatchReturnCodeExitCodeGenerator
        implements ApplicationListener<JobExecutionEvent>, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(BatchReturnCodeExitCodeGenerator.class);

    /** Mainframe return code for a clean, successful run. */
    static final int RC_SUCCESS = 0;

    /** Mainframe return code for a run that completed but rejected one or more input records. */
    static final int RC_COMPLETED_WITH_REJECTS = 4;

    /** Mainframe return code for a failed/abended run. */
    static final int RC_FAILURE = 8;

    /**
     * The exit-code string the posting writer sets when at least one record was rejected. Kept in
     * sync with {@code DailyTransactionPostingWriter}; it is a coordination contract, not a
     * Spring-defined constant, so it is matched literally here.
     */
    static final String EXIT_CODE_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /**
     * Job executions observed during this launch. Thread-safe because job events may be published
     * from a launcher/worker thread while {@link #getExitCode()} is read on the main thread during
     * shutdown.
     */
    private final List<JobExecution> jobExecutions = new CopyOnWriteArrayList<>();

    /**
     * Records the {@link JobExecution} carried by a Spring Boot {@link JobExecutionEvent} so its
     * outcome can be mapped to a return code at shutdown.
     *
     * @param event the job-execution event published by {@code JobLauncherApplicationRunner}
     */
    @Override
    public void onApplicationEvent(JobExecutionEvent event) {
        JobExecution execution = event.getJobExecution();
        if (execution != null) {
            jobExecutions.add(execution);
        }
    }

    /**
     * Computes the process exit code for this launch as the worst (maximum) return code across all
     * observed job executions, honoring the {@code 0 / 4 / 8} mainframe contract.
     *
     * @return {@code 0} if no job ran or every job completed cleanly, {@code 4} if any job completed
     *         with rejects (and none failed), or {@code 8} if any job failed/abended
     */
    @Override
    public int getExitCode() {
        int exitCode = RC_SUCCESS;
        for (JobExecution execution : jobExecutions) {
            exitCode = Math.max(exitCode, toReturnCode(execution));
        }
        if (!jobExecutions.isEmpty()) {
            log.info("Batch launch exit code resolved to {} across {} job execution(s).",
                    exitCode, jobExecutions.size());
        }
        return exitCode;
    }

    /**
     * Maps a single {@link JobExecution} to its mainframe return code, escalating any abnormal
     * {@link BatchStatus} to {@link #RC_FAILURE} regardless of the exit-code string.
     *
     * @param execution the completed job execution
     * @return {@code 0}, {@code 4}, or {@code 8}
     */
    private int toReturnCode(JobExecution execution) {
        BatchStatus status = execution.getStatus();
        if (status != null && status.isUnsuccessful()) {
            // FAILED / ABANDONED / STOPPED / UNKNOWN -> abend -> RC 8.
            return RC_FAILURE;
        }
        ExitStatus exitStatus = execution.getExitStatus();
        String code = (exitStatus != null) ? exitStatus.getExitCode() : null;
        if (ExitStatus.COMPLETED.getExitCode().equals(code)) {
            return RC_SUCCESS;
        }
        if (EXIT_CODE_COMPLETED_WITH_REJECTS.equals(code)) {
            return RC_COMPLETED_WITH_REJECTS;
        }
        // Any other terminal exit code (e.g. FAILED, STOPPED, NOOP, or an unrecognized custom code)
        // is treated as a failure to preserve the fail-safe 0/4/8 contract.
        return RC_FAILURE;
    }
}
