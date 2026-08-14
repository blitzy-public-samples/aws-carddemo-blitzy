package com.carddemo.account.config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the shipped configuration gives this service's two scheduled tasks a thread each.
 *
 * <p>This class has no COBOL ancestor. Its subject is the {@code spring.task.scheduling} block of
 * the shipped {@code application.yml} together with {@code @EnableScheduling} on
 * {@code AccountApplication}, and the two methods that block schedules:
 * {@code outbox/OutboxRelay#publishPendingEvents}, due every 500 milliseconds, and
 * {@code domain/RetentionSweep#purgeExpiredRows}, which drains three tables and may spend thirty
 * seconds on each.
 *
 * <p>Those two shared one thread, because the framework's scheduling pool holds one and this file
 * declared nothing. A drain therefore delayed every relay pass that fell inside it, which delayed
 * publication of every account's events — the opposite of what the drain's own batch bound exists to
 * achieve. The bound keeps the sweep out of the relay's locks; nothing kept it out of the relay's
 * thread.
 *
 * <p>Two assertions are made rather than one, because a property name is easy to get wrong and a
 * misplaced key binds nothing and reports nothing. The first reads the value the framework bound
 * from the shipped file. The second occupies a thread and then asserts a second task still runs,
 * which is the property the finding is about and the only one a reader should have to trust.
 */
@DisplayName("The shipped scheduler runs the relay and the retention drain at the same time")
class MaintenanceSchedulingTest {

    /** Threads the shipped file gives the scheduler, one for each {@code @Scheduled} method. */
    private static final int SHIPPED_POOL_SIZE = 2;

    /** Prefix the shipped file gives every scheduler thread, so a log line names the pool. */
    private static final String SHIPPED_THREAD_PREFIX = "carddemo-scheduling-";

    /** Longest a latch is waited on. Reached only when a task never ran, which is a failure. */
    private static final int WAIT_SECONDS = 10;

    /**
     * Registers the scheduling annotation processor.
     *
     * <p>The auto-configuration builds its scheduler only for a context that has one, so a context
     * without this class holds no {@code TaskScheduler} at all and would pass any assertion by
     * being empty.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabled {
    }

    /** A context whose only property source is the shipped {@code application.yml}. */
    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingEnabled.class);

    @Test
    @DisplayName("the shipped file binds two threads and the documented thread-name prefix")
    void theShippedFileBindsTwoThreadsAndTheDocumentedPrefix() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();

            TaskSchedulingProperties bound = context.getBean(TaskSchedulingProperties.class);
            assertThat(bound.getPool().getSize())
                    .as("spring.task.scheduling.pool.size, one thread for each scheduled method")
                    .isEqualTo(SHIPPED_POOL_SIZE);
            assertThat(bound.getThreadNamePrefix())
                    .as("spring.task.scheduling.thread-name-prefix")
                    .isEqualTo(SHIPPED_THREAD_PREFIX);
            assertThat(context.getBean(ThreadPoolTaskScheduler.class).getThreadNamePrefix())
                    .as("the prefix the scheduler the framework built actually uses")
                    .isEqualTo(SHIPPED_THREAD_PREFIX);
        });
    }

    @Test
    @DisplayName("a task holding a thread does not delay the next task the scheduler is given")
    void aTaskHoldingAThreadDoesNotDelayTheNext() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);

            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch second = new CountDownLatch(1);
            AtomicReference<String> secondThread = new AtomicReference<>();

            // Stands in for the retention drain: a task that holds its thread for a long time.
            scheduler.execute(() -> {
                occupied.countDown();
                awaitOrFail(release, "the occupying task was never released");
            });
            assertThat(occupied.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the first task must reach the scheduler before the second is offered")
                    .isTrue();

            // Stands in for a relay pass falling due while that drain runs.
            scheduler.execute(() -> {
                secondThread.set(Thread.currentThread().getName());
                second.countDown();
            });

            try {
                assertThat(second.await(WAIT_SECONDS, TimeUnit.SECONDS))
                        .as("a relay pass has to run while a retention drain holds its thread")
                        .isTrue();
                assertThat(secondThread.get())
                        .as("the second task ran on a scheduler thread of this pool")
                        .startsWith(SHIPPED_THREAD_PREFIX);
            } finally {
                release.countDown();
            }
        });
    }

    /**
     * Waits for a latch and fails the calling task rather than returning early.
     *
     * @param latch  the latch to wait on
     * @param reason what to report when the wait runs out
     */
    private static void awaitOrFail(CountDownLatch latch, String reason) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(reason);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(reason, interrupted);
        }
    }
}
