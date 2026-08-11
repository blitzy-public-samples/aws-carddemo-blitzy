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
package com.carddemo.common.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * :purpose: Run one service's own read operation concurrently and repeatedly UNTIL IT IS
 *     MEASURABLY FAST, so a service reports readiness with its real request path already
 *     compiled rather than interpreted. This exists because warming the shared layers is not
 *     enough, which was measured rather than assumed. With the connection pool pre-filled,
 *     every mapped entity read thousands of times and the servlet path exercised, a freshly
 *     ready account-service still reported 2700 ms at the 95th percentile SERVER-SIDE under
 *     150 concurrent users, while single sequential requests to the same instance answered in
 *     12-20 ms. The cost was neither queueing nor the query: it was the
 *     controller-to-mapper-to-DTO path running in the interpreter, whose per-request CPU cost
 *     only falls once HotSpot's optimising compiler has seen it thousands of times. Nothing
 *     but invoking that exact path can produce those invocations. The exit condition is
 *     therefore a MEASUREMENT, not a fixed amount of work: the task stops once its own
 *     measured throughput has STOPPED IMPROVING, which is what makes this "keep the instance
 *     out of rotation until its critical path performs" rather than "sleep for a while".
 *     Convergence is used in preference to an absolute millisecond target because the number
 *     that would have to be hit depends on the host, the CPU quota and the warm-up
 *     concurrency, and any figure chosen for one of those is wrong for the others; "no longer
 *     getting faster" is the same condition everywhere. A fast host converges in a fraction of
 *     the budget; a slow or contended one spends all of it and the log says so.
 * :output: The prepared operation is invoked on ``concurrency`` threads until either its
 *     mean duration converges or the budget expires, and the number of completed invocations
 *     is returned. The operation must be a read: this class is only ever handed the read half
 *     of a service, so start-up mutates nothing.
 * :note: Preparation is separated from invocation so a task can resolve what to read
 *     (typically one identifier obtained from the service's own repository) once, and report
 *     that there is nothing to warm — an empty table, for instance — by returning ``null``.
 *     Invocation failures are counted but not propagated, so a service still starts if its
 *     warm-up read cannot run.
 */
public class LoopingWarmUpTask implements WarmUpTask {

    /** :purpose: Reports what the warm-up achieved, and any problem, once per start. */
    private static final Logger log = LoggerFactory.getLogger(LoopingWarmUpTask.class);

    /**
     * :purpose: Size of the rolling window of invocation durations. A power of two so
     *     the ring index is a mask, and large enough that the mean is not dominated by a
     *     single outlier from a garbage collection pause.
     */
    private static final int WINDOW = 256;

    /**
     * :purpose: Windows that must elapse before convergence can be declared, so the
     *     decision rests on at least {@code MINIMUM_WINDOWS * WINDOW} invocations —
     *     5120 here. The floor is not cosmetic: HotSpot needs thousands of invocations
     *     before it promotes a method to the optimising compiler, and on a busy host the
     *     measured wall time per invocation stops improving for a DIFFERENT reason —
     *     scheduling delay — which would otherwise let the flattening be believed far too
     *     early. Measured: convergence declared at 3328 invocations left the request path
     *     materially slower than convergence declared at 15360.
     */
    private static final int MINIMUM_WINDOWS = 20;

    /**
     * :purpose: The read operation to repeat.
     */
    public interface Operation {

        /**
         * :purpose: Invoke the read once.
         * :raises Exception: any failure, which the task counts and does not propagate.
         */
        void invoke() throws Exception;
    }

    /**
     * :purpose: Resolve, once per service start, what the task should read.
     */
    public interface OperationFactory {

        /**
         * :purpose: Prepare the read.
         * :returns: the operation to repeat, or ``null`` when this service has nothing
         *     to warm (for example because the table it reads is empty).
         * :raises Exception: any failure, which the task reports as "not warmed".
         */
        Operation prepare() throws Exception;
    }

    /** :purpose: The task name reported in the start-up log. */
    private final String name;

    /** :purpose: How many threads invoke the operation at once. */
    private final int concurrency;

    /** :purpose: Resolves the operation when the task runs. */
    private final OperationFactory factory;

    /**
     * :purpose: Construct the task.
     * :param name: the name reported in the start-up log.
     * :param concurrency: threads invoking the operation at once. Parallelism matters as
     *     much as repetition: the path a burst of arrivals takes is the contended one,
     *     through the pool and the shared caches.
     * :param factory: resolves what to read, once, when the task runs.
     */
    public LoopingWarmUpTask(String name, int concurrency, OperationFactory factory) {
        this.name = name;
        this.concurrency = Math.max(1, concurrency);
        this.factory = factory;
    }

    /**
     * :purpose: Identify the task in the start-up log.
     * :returns: the configured name.
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * :purpose: Repeat the prepared read until its measured duration converges or the
     *     budget is spent.
     * :param budget: the maximum time this task may take.
     * :returns: the number of completed invocations.
     * :raises InterruptedException: if the warm-up is interrupted while waiting.
     */
    @Override
    public int warmUp(Duration budget) throws InterruptedException {
        Operation operation;
        try {
            operation = factory.prepare();
        } catch (Exception e) {
            log.debug("Warm-up '{}' could not be prepared: {}", name, e.toString());
            return 0;
        }
        if (operation == null) {
            log.debug("Warm-up '{}' has nothing to read", name);
            return 0;
        }

        long deadline = System.nanoTime() + budget.toNanos();
        AtomicLongArray durations = new AtomicLongArray(WINDOW);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        // Convergence state, all written by whichever worker crosses a window boundary.
        AtomicLong previousWindowMean = new AtomicLong(0L);
        AtomicInteger windowsSeen = new AtomicInteger();
        AtomicInteger nonImprovingWindows = new AtomicInteger();
        // Written by whichever worker first observes convergence, read by all.
        AtomicInteger warmAt = new AtomicInteger(-1);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "carddemo-warmup-" + name);
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int worker = 0; worker < concurrency; worker++) {
                executor.execute(() -> {
                    // One invocation always happens, so even a minimal budget compiles
                    // the path once; after that the measurement and the deadline decide.
                    do {
                        long startedAt = System.nanoTime();
                        try {
                            operation.invoke();
                        } catch (Exception e) {
                            // Counted rather than logged per occurrence: a failing read
                            // would otherwise log thousands of identical lines.
                            failed.incrementAndGet();
                            continue;
                        }
                        int index = completed.getAndIncrement();
                        durations.set(index & (WINDOW - 1), System.nanoTime() - startedAt);
                        // Assess only on window boundaries, and only once the ring is
                        // full, so each mean covers a whole window of recent invocations.
                        if (index >= WINDOW && (index & (WINDOW - 1)) == WINDOW - 1) {
                            assessConvergence(meanNanos(durations), previousWindowMean,
                                    windowsSeen, nonImprovingWindows, warmAt, index + 1);
                        }
                    } while (warmAt.get() < 0 && System.nanoTime() < deadline);
                });
            }
        } finally {
            executor.shutdown();
            // Every worker observes the same stop conditions, so this only waits out the
            // invocation each is on when one of them is met.
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }

        int invocations = completed.get();
        long meanNanos = meanNanos(durations);
        if (warmAt.get() >= 0) {
            log.info("Warm-up '{}' converged at {} us per read (concurrency {}) after {} invocations",
                    name, meanNanos / 1_000L, concurrency, warmAt.get());
        } else {
            log.warn("Warm-up '{}' spent its whole budget of {} without converging; {} us per read "
                            + "at concurrency {} after {} invocations. The first requests after "
                            + "readiness may be slower than the steady state.",
                    name, budget, meanNanos / 1_000L, concurrency, invocations);
        }
        if (failed.get() > 0) {
            log.debug("Warm-up '{}' had {} failed invocations", name, failed.get());
        }
        return invocations;
    }

    /**
     * :purpose: Decide, at a window boundary, whether the read has stopped getting faster.
     * :param currentMean: mean nanoseconds over the window just completed.
     * :param windowsSeen: how many boundaries have been assessed.
     * :param nonImprovingWindows: consecutive windows that did not improve materially.
     * :param warmAt: set to the invocation count at which convergence was declared.
     * :param invocations: invocations completed at this boundary.
     * :note: A window must be at least a tenth faster than the one before it to count as
     *     improving. Three consecutive windows without that, and no fewer than {@code
     *     MINIMUM_WINDOWS} windows in total, is convergence: the run-to-run noise of a single
     *     window is not enough to end the warm-up early, and the minimum guarantees the optimising
     *     compiler has had a few thousand invocations to work with before the flattening can be
     *     believed.
     */
    private static void assessConvergence(long currentMean, AtomicLong previousWindowMean,
                                          AtomicInteger windowsSeen, AtomicInteger nonImprovingWindows,
                                          AtomicInteger warmAt, int invocations) {
        long previousMean = previousWindowMean.getAndSet(currentMean);
        int windows = windowsSeen.incrementAndGet();
        if (previousMean > 0L && currentMean > previousMean / 10L * 9L) {
            if (nonImprovingWindows.incrementAndGet() >= 3 && windows >= MINIMUM_WINDOWS) {
                warmAt.compareAndSet(-1, invocations);
            }
        } else {
            nonImprovingWindows.set(0);
        }
    }

    /**
     * :purpose: Mean of the rolling window of invocation durations.
     * :param durations: the ring buffer of nanosecond durations.
     * :returns: the mean in nanoseconds, ignoring slots not yet written.
     */
    private static long meanNanos(AtomicLongArray durations) {
        long total = 0L;
        int counted = 0;
        for (int i = 0; i < durations.length(); i++) {
            long value = durations.get(i);
            if (value > 0L) {
                total += value;
                counted++;
            }
        }
        return counted == 0 ? Long.MAX_VALUE : total / counted;
    }
}
