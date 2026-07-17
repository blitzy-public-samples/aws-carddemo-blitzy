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

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * Cross-cutting Spring Batch listener that publishes a stable <em>correlation ID</em> into the
 * SLF4J {@link MDC} for the entire lifetime of every batch job (and step) execution, so that all
 * batch log output can be followed end-to-end and correlated with the rest of the platform.
 *
 * <p>This is the batch-side counterpart of the web-side correlation filter. Together they satisfy
 * the Observability requirement that "structured logs include a correlation ID that propagates
 * across service <strong>and</strong> batch boundaries" (Technical Specification &sect;0.9.5). The
 * companion {@code logback-spring.xml} renders the value through the pattern token
 * {@code %X{correlationId:-}} (human-readable console profile) and emits it as a first-class field
 * of the structured (ECS JSON) profile, so no appender change is needed for batch: simply having
 * this listener populate the MDC key is sufficient for every batch log line to carry the id.</p>
 *
 * <p><strong>Registration.</strong> This class is a Spring {@link Component} whose default bean
 * name is {@code correlationIdJobListener}. Every batch {@code @Configuration} registers it on its
 * {@code JobBuilder} via {@code .listener(correlationIdJobListener)} (and, where steps may run on a
 * different thread than the job, on the {@code StepBuilder} as well). It has no injected
 * collaborators; a default constructor is therefore sufficient and no field injection is used.</p>
 *
 * <p><strong>Correlation-ID resolution (in {@link #beforeJob(JobExecution)}).</strong> The id is
 * resolved with the following precedence:</p>
 * <ol>
 *   <li>a job parameter named {@code correlationId}, when present and non-blank &mdash; this lets a
 *       caller (an upstream trigger, a CI/CD pipeline, or a test) pin a specific id so a single
 *       logical operation keeps one identifier across service and batch surfaces;</li>
 *   <li>otherwise the {@code correlationId} value already stored in the job
 *       {@link ExecutionContext} &mdash; this preserves the original id across a job
 *       <em>restart</em>, which is why {@code beforeJob} also writes the resolved id back into the
 *       execution context;</li>
 *   <li>otherwise a freshly generated {@link UUID}.</li>
 * </ol>
 *
 * <p><strong>Thread-context hygiene.</strong> The MDC entry is always removed in a
 * {@code finally} block on {@link #afterJob(JobExecution)} and {@link #afterStep(StepExecution)}
 * (both of which the framework invokes regardless of success or failure), preventing the id from
 * leaking onto a pooled worker thread and contaminating an unrelated, later execution. Only the
 * correlation-ID key is touched; {@link MDC#clear()} is deliberately not used, so the
 * {@code traceId}/{@code spanId} entries that Micrometer Tracing manages on the same thread are
 * left undisturbed.</p>
 *
 * <p><strong>Security.</strong> The listener handles only an opaque correlation-ID string. It never
 * reads, copies, or logs job payloads, credentials, card data, or any sensitive field.</p>
 */
@Component
public class CorrelationIdJobListener implements JobExecutionListener, StepExecutionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdJobListener.class);

    /**
     * SLF4J {@link MDC} key under which the correlation ID is published.
     *
     * <p>This literal MUST remain exactly {@code "correlationId"}. It is intentionally duplicated
     * here as a plain string constant rather than imported from
     * {@code com.aws.carddemo.observability.CorrelationIdFilter} (which declares the identical
     * {@code CORRELATION_ID_MDC_KEY}): the batch layer must not take a compile-time dependency on
     * the {@code observability} package. The value is also referenced by
     * {@code logback-spring.xml} as {@code %X{correlationId:-}}. All three locations must stay in
     * sync; changing this literal would silently break the documented logging-correlation
     * contract.</p>
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * Name of the job parameter consulted first when resolving the correlation ID, and the key
     * under which the resolved id is stored in (and re-read from) the job/step
     * {@link ExecutionContext}. It shares the {@code "correlationId"} value with the MDC key so the
     * same logical identifier name is used consistently across parameters, the execution context,
     * and the logging MDC.
     */
    private static final String CORRELATION_ID_PARAM = "correlationId";

    /**
     * Establishes the correlation ID for the whole job execution.
     *
     * <p>Resolves the id (job parameter &rarr; persisted execution-context value &rarr; new
     * {@link UUID}), publishes it to the {@link MDC}, writes it back into the job
     * {@link ExecutionContext} so restarts and steps can re-read it, and emits an informational
     * start line tagged with the job name.</p>
     *
     * @param jobExecution the current job execution; never {@code null}
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        final ExecutionContext executionContext = jobExecution.getExecutionContext();

        String correlationId = jobExecution.getJobParameters().getString(CORRELATION_ID_PARAM);
        if (isBlank(correlationId) && executionContext.containsKey(CORRELATION_ID_PARAM)) {
            correlationId = executionContext.getString(CORRELATION_ID_PARAM);
        }
        if (isBlank(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        // Persist the resolved id so subsequent steps (which may run on other threads) and job
        // restarts observe the same value.
        executionContext.putString(CORRELATION_ID_PARAM, correlationId);

        LOGGER.info("Batch job {} started with correlationId={}",
                jobExecution.getJobInstance().getJobName(), correlationId);
    }

    /**
     * Emits the job-completion line and guarantees the correlation ID is cleared from the
     * {@link MDC}.
     *
     * <p>The id is re-established on the current thread before logging (reading it back from the
     * job {@link ExecutionContext}, falling back to whatever is already on the thread) so the
     * completion line remains correlated even if the framework invoked {@code afterJob} on a
     * different thread than {@link #beforeJob(JobExecution)}. The MDC key is always removed in a
     * {@code finally} block because {@code afterJob} runs on both success and failure.</p>
     *
     * @param jobExecution the current job execution; never {@code null}
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        final String correlationId = jobExecution.getExecutionContext()
                .getString(CORRELATION_ID_PARAM, MDC.get(CORRELATION_ID_MDC_KEY));
        if (!isBlank(correlationId)) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }
        try {
            LOGGER.info("Batch job {} completed with status={} and correlationId={}",
                    jobExecution.getJobInstance().getJobName(), jobExecution.getStatus(), correlationId);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * Re-establishes the correlation ID on the step's execution thread.
     *
     * <p>Spring Batch may run a step on a different thread than the one that ran
     * {@link #beforeJob(JobExecution)}, so the MDC (which is thread-local) is repopulated here from
     * the job {@link ExecutionContext}. The lookup is fully defensive: a missing job execution,
     * a missing execution context, or a missing/blank stored value all degrade gracefully to a
     * freshly generated {@link UUID} rather than leaving the step's logs uncorrelated.</p>
     *
     * @param stepExecution the current step execution; never {@code null}
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        String correlationId = null;
        final JobExecution jobExecution = stepExecution.getJobExecution();
        if (jobExecution != null) {
            final ExecutionContext executionContext = jobExecution.getExecutionContext();
            if (executionContext != null && executionContext.containsKey(CORRELATION_ID_PARAM)) {
                correlationId = executionContext.getString(CORRELATION_ID_PARAM);
            }
        }
        if (isBlank(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        LOGGER.debug("Batch step {} started with correlationId={}",
                stepExecution.getStepName(), correlationId);
    }

    /**
     * Clears the correlation ID from the step's execution thread and returns the step's exit
     * status unchanged.
     *
     * <p>The MDC key is removed in a {@code finally} block so the correlation ID never leaks onto a
     * pooled worker thread, and the step's own {@link ExitStatus} is propagated verbatim so this
     * listener has no effect on batch flow control or return codes.</p>
     *
     * @param stepExecution the current step execution; never {@code null}
     * @return the unchanged {@link ExitStatus} of the step
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        try {
            LOGGER.debug("Batch step {} completed with status={}",
                    stepExecution.getStepName(), stepExecution.getStatus());
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
        return stepExecution.getExitStatus();
    }

    /**
     * Returns {@code true} when the supplied value is {@code null} or contains only whitespace.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} if {@code value} is {@code null} or blank, otherwise {@code false}
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
