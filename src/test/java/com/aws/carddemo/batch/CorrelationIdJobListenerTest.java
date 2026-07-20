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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Focused, container-free unit tests for {@link CorrelationIdJobListener} that verify the
 * trace-correlation behavior added for QA finding <strong>F-P6-C</strong>: the listener must attach
 * the resolved correlation id to the currently recording batch trace span (as a {@code correlationId}
 * tag) in both {@code beforeJob} and {@code beforeStep}, while degrading to a pure-MDC no-op (never
 * throwing, never affecting flow) when tracing is unavailable or no span is recording.
 *
 * <p>These tests use Mockito doubles for the Micrometer {@link Tracer}/{@link Span} and the injected
 * {@link ObjectProvider}, and real (in-memory) {@link JobExecution}/{@link StepExecution} objects, so
 * they run in milliseconds without a Spring context, a database, or a live tracer &mdash; asserting
 * the exact span interaction the fix introduced. The pre-existing MDC-propagation behavior is
 * exercised by the batch integration tests; here the emphasis is the span tag.</p>
 */
class CorrelationIdJobListenerTest {

    /** The single shared key used for the MDC entry, the execution-context value, and the span tag. */
    private static final String CORRELATION_ID_KEY = "correlationId";

    /**
     * The listener populates the (thread-local) SLF4J MDC in {@code beforeJob}/{@code beforeStep} and
     * only removes it in {@code afterJob}/{@code afterStep}. These unit tests invoke the "before"
     * callbacks in isolation, so the key is cleared here to prevent it leaking onto the shared test
     * thread and contaminating a later test.
     */
    @AfterEach
    void clearMdc() {
        MDC.remove(CORRELATION_ID_KEY);
    }

    @Test
    void beforeJobTagsCurrentSpanWithResolvedCorrelationId() {
        Span span = mock(Span.class);
        CorrelationIdJobListener listener = listenerWithCurrentSpan(span);

        JobParameters parameters = new JobParametersBuilder()
                .addString(CORRELATION_ID_KEY, "pinned-cid-0001")
                .toJobParameters();
        JobExecution jobExecution =
                new JobExecution(new JobInstance(1L, "testJob"), 10L, parameters);

        listener.beforeJob(jobExecution);

        // The job span carries the correlation id (F-P6-C) so a log line can pivot to its trace.
        verify(span).tag(CORRELATION_ID_KEY, "pinned-cid-0001");
        // And the id is persisted so steps/restarts observe the same value (pre-existing contract).
        assertThat(jobExecution.getExecutionContext().getString(CORRELATION_ID_KEY))
                .isEqualTo("pinned-cid-0001");
    }

    @Test
    void beforeStepTagsCurrentSpanWithExecutionContextCorrelationId() {
        Span span = mock(Span.class);
        CorrelationIdJobListener listener = listenerWithCurrentSpan(span);

        JobExecution jobExecution =
                new JobExecution(new JobInstance(2L, "testJob"), 20L, new JobParameters());
        // Simulate beforeJob having already stored the id in the shared job execution context.
        jobExecution.getExecutionContext().putString(CORRELATION_ID_KEY, "ctx-cid-0002");
        StepExecution stepExecution = new StepExecution("testStep", jobExecution);

        listener.beforeStep(stepExecution);

        verify(span).tag(CORRELATION_ID_KEY, "ctx-cid-0002");
    }

    @Test
    void degradesToNoopWhenNoTracerBeanIsPresent() {
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        CorrelationIdJobListener listener = new CorrelationIdJobListener(provider);

        JobExecution jobExecution = new JobExecution(new JobInstance(3L, "testJob"), 30L,
                new JobParametersBuilder().addString(CORRELATION_ID_KEY, "cid-0003").toJobParameters());
        StepExecution stepExecution = new StepExecution("testStep", jobExecution);

        // With no tracer bean the before-callbacks must still succeed (pure MDC behavior).
        assertThatCode(() -> {
            listener.beforeJob(jobExecution);
            listener.beforeStep(stepExecution);
        }).doesNotThrowAnyException();
    }

    @Test
    void doesNotTagWhenNoSpanIsCurrentlyRecording() {
        Span span = mock(Span.class);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        CorrelationIdJobListener listener = listenerWithTracer(tracer);

        JobExecution jobExecution = new JobExecution(new JobInstance(4L, "testJob"), 40L,
                new JobParametersBuilder().addString(CORRELATION_ID_KEY, "cid-0004").toJobParameters());

        assertThatCode(() -> listener.beforeJob(jobExecution)).doesNotThrowAnyException();

        // The current span was consulted, but with none recording no tag is attempted.
        verify(tracer).currentSpan();
        verifyNoInteractions(span);
    }

    /**
     * Builds a listener whose injected tracer reports {@code span} as the current span.
     *
     * @param span the span the tracer should return from {@code currentSpan()}
     * @return a listener wired to a tracer that is present and currently recording {@code span}
     */
    private static CorrelationIdJobListener listenerWithCurrentSpan(Span span) {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);
        return listenerWithTracer(tracer);
    }

    /**
     * Builds a listener whose {@link ObjectProvider} yields the supplied {@code tracer}.
     *
     * @param tracer the tracer the provider should make available
     * @return a listener wired to a provider that returns {@code tracer}
     */
    private static CorrelationIdJobListener listenerWithTracer(Tracer tracer) {
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return new CorrelationIdJobListener(provider);
    }
}
