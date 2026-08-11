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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.List;

/**
 * :purpose: Exercise each service's own critical paths once, at start-up, BEFORE it
 *     reports readiness, so that "readiness is UP" means "able to serve at the latency the
 *     deployment commits to" (AAP 0.7.1 — 95th percentile under 200 ms at 150 concurrent
 *     users) rather than merely "the process started". Measured on this stack: with the nine
 *     containers restarted and every healthcheck reporting healthy after twenty seconds, an
 *     immediate 150-user run saw 95th-percentile response times of 573 ms on the menu, 2810 ms
 *     on account view, 2099 ms on the card list and 2621 ms on the transaction list. The SAME
 *     traffic against the same containers minutes later measured 11 ms, 21 ms, 52 ms and 47
 *     ms. Nothing about the deployment changed in between: the first requests were paying for
 *     class loading, JIT compilation, Hibernate's per-entity persister and query-plan
 *     construction, and the growth of a connection pool that idles at two connections. This
 *     runner moves that cost off the first request and in front of the readiness signal.
 * :output: Every registered {@link WarmUpTask} is run once, in registration order. Each
 *     receives an equal share of whatever remains of the total budget, so a task that finishes
 *     early donates its time to those behind it and a slow dependency cannot starve them or
 *     extend start-up without bound. One INFO line per task records what it did, and a final
 *     line records the total. No task failure propagates.
 * :note: Implemented as an {@link ApplicationRunner} deliberately: Spring Boot runs
 *     application runners as the LAST step of ``SpringApplication.run`` and only then
 *     publishes ``ApplicationReadyEvent``, which is what flips the readiness state to
 *     ACCEPTING_TRAFFIC. Warming here therefore delays the readiness signal — and the
 *     container healthcheck that reads it — without delaying the web server, so a Kubernetes
 *     readiness probe keeps a cold instance out of rotation for exactly as long as it is cold.
 *     {@link Ordered#LOWEST_PRECEDENCE} places this runner after every other one, notably
 *     after the at-rest encryption sweep whose columns this runner then reads through the
 *     entity converters.
 */
public class ReadinessWarmUp implements ApplicationRunner, Ordered {

    /** :purpose: Reports what was warmed, once per service start. */
    private static final Logger log = LoggerFactory.getLogger(ReadinessWarmUp.class);

    /** :purpose: The tasks to run, in the order they were registered. */
    private final List<WarmUpTask> tasks;

    /** :purpose: Total wall-clock budget shared by every task. */
    private final Duration budget;

    /**
     * :purpose: Construct the runner.
     * :param tasks: the warm-up tasks contributed by this service's configuration;
     *     an empty list makes the runner a no-op.
     * :param budget: the total time warm-up may add to start-up. Each task receives
     *     what remains of it, so a single slow dependency cannot extend start-up
     *     without bound.
     */
    public ReadinessWarmUp(List<WarmUpTask> tasks, Duration budget) {
        this.tasks = List.copyOf(tasks);
        this.budget = budget;
    }

    /**
     * :purpose: Run every task within the shared budget, absorbing failures.
     * :param args: the application arguments; unused.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (tasks.isEmpty()) {
            log.debug("Readiness warm-up: no tasks registered");
            return;
        }
        long startedAt = System.nanoTime();
        long budgetNanos = budget.toNanos();
        int warmed = 0;
        int remainingTasks = tasks.size();
        for (WarmUpTask task : tasks) {
            long remainingNanos = budgetNanos - (System.nanoTime() - startedAt);
            if (remainingNanos <= 0) {
                // The budget is spent. Report the tasks that were skipped rather than
                // silently reporting readiness on a partially warmed service.
                log.warn("Readiness warm-up budget of {} exhausted; task '{}' was skipped",
                        budget, task.name());
                remainingTasks--;
                continue;
            }
            // An equal share of what is LEFT, so a task that returns early hands its
            // unused time to the tasks behind it and one slow task cannot starve them.
            long shareNanos = remainingNanos / remainingTasks;
            remainingTasks--;
            long taskStartedAt = System.nanoTime();
            try {
                int operations = task.warmUp(Duration.ofNanos(shareNanos));
                warmed += operations;
                log.info("Readiness warm-up '{}': {} operations in {} ms",
                        task.name(), operations, elapsedMillis(taskStartedAt));
            } catch (Exception e) {
                // A warm-up is an optimisation, never a precondition: a service that
                // cannot warm up must still start and serve.
                log.warn("Readiness warm-up '{}' did not complete after {} ms: {}",
                        task.name(), elapsedMillis(taskStartedAt), e.toString());
            }
        }
        log.info("Readiness warm-up complete: {} operations across {} tasks in {} ms",
                warmed, tasks.size(), elapsedMillis(startedAt));
    }

    /**
     * :purpose: Order this runner after every other application runner.
     * :returns: {@link Ordered#LOWEST_PRECEDENCE}.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * :purpose: Elapsed milliseconds since a nanosecond mark, for the log lines.
     * :param sinceNanos: the mark returned by {@link System#nanoTime()}.
     * :returns: whole milliseconds elapsed.
     */
    private static long elapsedMillis(long sinceNanos) {
        return (System.nanoTime() - sinceNanos) / 1_000_000L;
    }
}
