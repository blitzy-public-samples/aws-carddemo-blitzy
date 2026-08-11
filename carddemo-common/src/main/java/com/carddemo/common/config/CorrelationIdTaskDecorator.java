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
package com.carddemo.common.config;

import org.springframework.core.task.TaskDecorator;

/**
 * :purpose: Carry the submitting thread's correlation id onto the worker thread that
 *     actually runs an asynchronous task, so a batch job or ``@Async`` method logs
 *     under the same correlation id as the request that launched it. Without this the
 *     MDC is thread-local, so every log line produced on a worker thread rendered an
 *     empty ``correlationId`` and an asynchronous unit of work could not be tied back
 *     to its caller.
 * :output: A {@link TaskDecorator} that wraps a task so the captured correlation id is
 *     in scope for its whole execution and the worker thread's previous MDC state is
 *     restored afterwards, leaving no id behind on a pooled thread.
 * :note: The id is captured at submission time, on the submitting thread, because by
 *     the time the task runs the submitting thread may already have moved on and
 *     cleared its MDC.
 */
public class CorrelationIdTaskDecorator implements TaskDecorator {

    /**
     * :purpose: Capture the correlation id currently in scope and return a task that
     *     re-establishes it on whichever thread ends up running the work.
     * :param runnable: the task about to be submitted to an executor.
     * :returns: the task wrapped so it executes with the captured correlation id in
     *     scope, restoring the worker thread's prior MDC state on completion.
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        // Captured on the SUBMITTING thread: this is the only point at which the
        // caller's correlation id is reliably still in scope.
        String correlationId = CorrelationIdContext.getCorrelationId();
        return () -> CorrelationIdContext.runWithCorrelationId(correlationId, runnable);
    }
}
