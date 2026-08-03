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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * :purpose: Unit tests for :class:`CorrelationIdTaskDecorator`, the mechanism that carries
 *     a request's correlation id onto the worker thread running an asynchronous task so
 *     ``@Async`` methods and asynchronously launched batch jobs log under the id of the
 *     request that triggered them.
 * :note: Plain JUnit 5; the decorated task is executed on a genuinely different thread so
 *     the thread-local nature of the MDC is actually exercised rather than assumed.
 */
@DisplayName("CorrelationIdTaskDecorator")
class CorrelationIdTaskDecoratorTest {

    /** :purpose: Bound on how long a test waits for its worker thread, in seconds. */
    private static final int WORKER_TIMEOUT_SECONDS = 5;

    private final CorrelationIdTaskDecorator decorator = new CorrelationIdTaskDecorator();

    /**
     * :purpose: Start every case from a clean MDC so no case inherits an id.
     */
    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    /**
     * :purpose: Leave no id behind for the next case or a pooled thread.
     */
    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /**
     * :purpose: The correlation id in scope on the submitting thread is visible inside the
     *     decorated task even though the task runs on a different thread.
     * :raises InterruptedException: if the wait for the worker thread is interrupted.
     */
    @Test
    @DisplayName("submitting thread's correlation id is in scope on the worker thread")
    void submitterCorrelationIdVisibleOnWorkerThread() throws InterruptedException {
        CorrelationIdContext.setCorrelationId("submitter-id-42");
        AtomicReference<String> seenInTask = new AtomicReference<>();
        AtomicReference<String> workerThreadName = new AtomicReference<>();

        Runnable decorated = decorator.decorate(() -> {
            seenInTask.set(CorrelationIdContext.getCorrelationId());
            workerThreadName.set(Thread.currentThread().getName());
        });

        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                decorated.run();
            } finally {
                done.countDown();
            }
        }, "decorator-test-worker");
        worker.start();
        done.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("submitter-id-42", seenInTask.get());
        assertEquals("decorator-test-worker", workerThreadName.get());
    }

    /**
     * :purpose: The worker thread's MDC is restored after the task finishes, so a pooled
     *     thread never carries one task's correlation id into the next task.
     * :raises InterruptedException: if the wait for the worker thread is interrupted.
     */
    @Test
    @DisplayName("worker thread's MDC is left clean after the task completes")
    void workerThreadMdcRestoredAfterTask() throws InterruptedException {
        CorrelationIdContext.setCorrelationId("submitter-id-99");
        Runnable decorated = decorator.decorate(() -> { });
        AtomicReference<String> afterOnWorker = new AtomicReference<>("sentinel");

        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                decorated.run();
                afterOnWorker.set(CorrelationIdContext.getCorrelationId());
            } finally {
                done.countDown();
            }
        }, "decorator-test-worker");
        worker.start();
        done.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertNull(afterOnWorker.get(), "the worker thread must not retain the task's correlation id");
    }

    /**
     * :purpose: A worker thread that already carries its own correlation id has it restored
     *     rather than removed, so decorating is safe on threads that are themselves
     *     already correlated.
     * :raises InterruptedException: if the wait for the worker thread is interrupted.
     */
    @Test
    @DisplayName("a pre-existing correlation id on the worker thread is restored, not dropped")
    void preExistingWorkerCorrelationIdIsRestored() throws InterruptedException {
        CorrelationIdContext.setCorrelationId("submitter-id-7");
        Runnable decorated = decorator.decorate(() -> { });
        AtomicReference<String> afterOnWorker = new AtomicReference<>();

        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                CorrelationIdContext.setCorrelationId("worker-own-id");
                decorated.run();
                afterOnWorker.set(CorrelationIdContext.getCorrelationId());
            } finally {
                done.countDown();
            }
        }, "decorator-test-worker");
        worker.start();
        done.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("worker-own-id", afterOnWorker.get());
    }

    /**
     * :purpose: When the submitting thread carries no correlation id, the task still runs
     *     under a generated one so its log lines are never uncorrelated.
     * :raises InterruptedException: if the wait for the worker thread is interrupted.
     */
    @Test
    @DisplayName("a task submitted without a correlation id still runs under a generated one")
    void taskWithoutSubmitterIdGetsGeneratedId() throws InterruptedException {
        Runnable decorated = decorator.decorate(() -> { });
        AtomicReference<String> seenInTask = new AtomicReference<>();
        Runnable capturing = decorator.decorate(() -> seenInTask.set(CorrelationIdContext.getCorrelationId()));

        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                decorated.run();
                capturing.run();
            } finally {
                done.countDown();
            }
        }, "decorator-test-worker");
        worker.start();
        done.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertNotNull(seenInTask.get(), "a correlation id must always be in scope inside the task");
    }

    /**
     * :purpose: A task that throws still leaves the worker thread's MDC clean, because the
     *     restore happens in a ``finally`` block.
     */
    @Test
    @DisplayName("a throwing task still leaves the MDC clean")
    void throwingTaskStillRestoresMdc() {
        CorrelationIdContext.setCorrelationId("submitter-id-boom");
        Runnable decorated = decorator.decorate(() -> {
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, decorated::run);
        assertEquals("submitter-id-boom", CorrelationIdContext.getCorrelationId(),
                "the submitting thread's own id must be intact after the task fails");
    }
}
