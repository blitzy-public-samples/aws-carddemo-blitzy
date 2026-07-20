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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Reusable Spring Batch {@link JobExecutionListener} that reproduces the SYSOUT
 * <em>execution-boundary banners</em> printed by the legacy COBOL batch master-print programs.
 *
 * <h2>Legacy behavior reproduced</h2>
 * The four read-only master-print programs each bracket their {@code PROCEDURE DIVISION} with a
 * matching pair of {@code DISPLAY} statements:
 * <ul>
 *   <li>{@code DISPLAY 'START OF EXECUTION OF PROGRAM <name>'} &mdash; the <strong>first</strong>
 *       statement of the {@code PROCEDURE DIVISION} (for example {@code legacy/cbl/CBACT01C.cbl}
 *       L71), so it is always reached;</li>
 *   <li>{@code DISPLAY 'END OF EXECUTION OF PROGRAM <name>'} &mdash; the statement immediately
 *       before {@code GOBACK}, after the input file has been closed (for example CBACT01C L85).</li>
 * </ul>
 * The banners carry no business data; they are the SYSOUT header/trailer that delimits the run.
 * The programs and their exact banner literals are {@code CBACT01C}, {@code CBACT02C},
 * {@code CBACT03C} (account / card / cross-reference master prints), and {@code CBCUS01C}
 * (customer master print).
 *
 * <h2>Faithful control-flow parity (why END is guarded)</h2>
 * In the legacy programs the START banner is the very first {@code PROCEDURE DIVISION} statement,
 * so it is emitted unconditionally &mdash; reproduced here in {@link #beforeJob(JobExecution)},
 * which Spring Batch always invokes before the step runs. The END banner, however, is only reached
 * on a <strong>normal</strong> pass: any file-I/O failure routes through the program's
 * {@code Z-ABEND-PROGRAM} paragraph and the Language Environment {@code CEE3ABD} service, which
 * terminates the program (COBOL return code {@code 8}) <em>before</em> control can fall through to
 * the {@code DISPLAY 'END OF EXECUTION...'} line. Spring Batch, by contrast, invokes
 * {@link #afterJob(JobExecution)} on both success and failure, so this listener emits the END
 * banner <strong>only</strong> when the job finished {@link BatchStatus#COMPLETED} &mdash; the exact
 * analog of the abend bypassing the trailer. A {@link BatchStatus#FAILED} job (the Spring Batch
 * equivalent of the {@code CEE3ABD} abend) prints the START banner but no END banner, preserving the
 * observable SYSOUT contract.
 *
 * <h2>Registration and design</h2>
 * <p>Unlike {@link CorrelationIdJobListener}, this listener is <strong>not</strong> a singleton
 * Spring {@code @Component}: it carries per-job state (the program name), so each master-print job
 * configuration instantiates its own instance inline with the program's banner name and registers
 * it on the {@code JobBuilder} via {@code .listener(new ExecutionBannerJobListener("CBACT01C"))}.
 * The program name is supplied through the constructor (constructor injection; no field injection),
 * validated as non-blank at construction time.</p>
 *
 * <p><strong>Listener ordering.</strong> Each job registers the {@link CorrelationIdJobListener}
 * first and this banner listener second. Spring Batch invokes {@code beforeJob} in registration
 * order and {@code afterJob} in reverse order, so the correlation id is established on the MDC
 * before the START banner is logged and is still present when the END banner is logged &mdash; both
 * banners therefore carry the run's {@code correlationId} in structured log output.</p>
 *
 * <p><strong>Output channel.</strong> The banners are emitted through SLF4J at {@code INFO} (the
 * idiomatic Java analog of COBOL {@code DISPLAY} to SYSOUT); the message text is byte-identical to
 * the legacy literal so it remains greppable for local validation and golden comparison.</p>
 *
 * @see CorrelationIdJobListener
 * @see AccountMasterPrintJob
 * @see CardMasterPrintJob
 * @see XrefPrintJob
 * @see CustomerMasterPrintJob
 */
public class ExecutionBannerJobListener implements JobExecutionListener {

    /** SLF4J logger through which both execution-boundary banners are emitted at {@code INFO}. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionBannerJobListener.class);

    /**
     * Parameterized message for the start-of-execution banner. Rendering it with the program name
     * yields exactly {@code "START OF EXECUTION OF PROGRAM <name>"}, byte-identical to the legacy
     * {@code DISPLAY} literal.
     */
    private static final String START_BANNER_FORMAT = "START OF EXECUTION OF PROGRAM {}";

    /**
     * Parameterized message for the end-of-execution banner. Rendering it with the program name
     * yields exactly {@code "END OF EXECUTION OF PROGRAM <name>"}, byte-identical to the legacy
     * {@code DISPLAY} literal.
     */
    private static final String END_BANNER_FORMAT = "END OF EXECUTION OF PROGRAM {}";

    /**
     * The legacy program name printed in both banners (for example {@code "CBACT01C"}). Immutable
     * and supplied at construction time.
     */
    private final String programName;

    /**
     * Creates a banner listener for a specific legacy program.
     *
     * @param programName the legacy program name to print in the START/END banners (for example
     *                    {@code "CBACT01C"}); must not be {@code null} or blank
     * @throws IllegalArgumentException if {@code programName} is {@code null} or blank
     */
    public ExecutionBannerJobListener(String programName) {
        if (programName == null || programName.isBlank()) {
            throw new IllegalArgumentException("programName must not be null or blank");
        }
        this.programName = programName;
    }

    /**
     * Emits the start-of-execution banner. Invoked by Spring Batch before the job's step runs, this
     * is the analog of the legacy {@code DISPLAY 'START OF EXECUTION OF PROGRAM <name>'} being the
     * first {@code PROCEDURE DIVISION} statement, so it is always emitted.
     *
     * @param jobExecution the current job execution; never {@code null}
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        LOGGER.info(START_BANNER_FORMAT, programName);
    }

    /**
     * Emits the end-of-execution banner &mdash; but only on a normal (COMPLETED) pass.
     *
     * <p>Spring Batch invokes {@code afterJob} on both success and failure, whereas the legacy
     * {@code DISPLAY 'END OF EXECUTION...'} is reached only when no abend occurred. This method
     * therefore logs the END banner solely when {@code jobExecution.getStatus()} is
     * {@link BatchStatus#COMPLETED}; a {@link BatchStatus#FAILED} job (the analog of a
     * {@code CEE3ABD} abend) prints no END banner, faithfully preserving the SYSOUT contract.</p>
     *
     * @param jobExecution the current job execution; never {@code null}
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            LOGGER.info(END_BANNER_FORMAT, programName);
        }
    }
}
