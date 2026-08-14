package com.carddemo.fraud;

import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Stops the background work of one Spring context before a test class lets its containers go.
 *
 * <p>No COBOL ancestor. This exists because of an ordering fact about the test harness rather than
 * anything about the domain.
 *
 * <h2>The ordering this closes</h2>
 *
 * <p>{@code FraudApplication} carries {@code @EnableScheduling}, so a started context ticks
 * {@code outbox/OutboxRelay.publishPendingEvents} every half second and sweeps
 * {@code domain/RetentionSweep.purgeExpiredRows} on its own interval. Neither has any relationship
 * to the database container, and JUnit stops the two in the wrong order for them: a test class's
 * {@code @AfterAll} methods run first, then extension callbacks stop the {@code @Container} fields,
 * and the Spring context is cached beyond the class and closed later. The relay therefore keeps
 * ticking into a database that has gone.
 *
 * <p>What that produced was not a test failure but noise that reads like one: a closed-connection
 * stack trace under {@code Unexpected error occurred in scheduled task} in the build log, on a run
 * where every assertion passed. Noise of that shape is worse than a failure, because the next person
 * to read the log has to establish for themselves that it meant nothing, and the time after that
 * they will not bother.
 *
 * <p>Calling {@link #stopBefore(ApplicationContext)} from an {@code @AfterAll} closes the window.
 * Destroying the scheduling post-processor cancels every scheduled task the context registered, and
 * stopping the listener registry stops every {@code @KafkaListener} container. Both happen while the
 * database and the broker are still up, so nothing is left running against a torn-down resource.
 *
 * <p>This is deliberately not the same as disabling scheduling in the integration profile. The
 * relay's own tick is what several of these classes are asserting on, and a test that drove the
 * relay by hand would stop proving that the schedule reaches it at all.
 *
 * <p>Public because the classes that need it sit in three different packages of this service's test
 * sources. It is test scope throughout and no production class references it.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public final class ScheduledWorkShutdown {

    /** Holds no state; every member is static. */
    private ScheduledWorkShutdown() {
        throw new AssertionError("ScheduledWorkShutdown is not instantiated");
    }

    /**
     * Cancels every scheduled task and stops every listener container of {@code context}.
     *
     * <p>Safe to call on a context that holds neither, and safe to call twice: a missing bean is
     * treated as nothing to stop, and both operations are idempotent.
     *
     * @param context the started test context whose background work must stop
     */
    public static void stopBefore(ApplicationContext context) {
        if (context == null) {
            return;
        }
        stopScheduledTasks(context);
        stopListenerContainers(context);
    }

    /**
     * Cancels every task {@code @Scheduled} registered on this context.
     *
     * <p>{@code destroy()} is the documented way to cancel them, and it is what the context itself
     * calls on shutdown. Calling it early moves that cancellation ahead of container teardown.
     *
     * @param context the started test context
     */
    private static void stopScheduledTasks(ApplicationContext context) {
        ScheduledAnnotationBeanPostProcessor scheduling =
                beanOrNull(context, ScheduledAnnotationBeanPostProcessor.class);
        if (scheduling != null) {
            scheduling.destroy();
        }
    }

    /**
     * Stops every {@code @KafkaListener} container of this context.
     *
     * <p>A listener mid-poll would otherwise commit an offset, or fail to, against a broker that has
     * already stopped.
     *
     * @param context the started test context
     */
    private static void stopListenerContainers(ApplicationContext context) {
        KafkaListenerEndpointRegistry listeners =
                beanOrNull(context, KafkaListenerEndpointRegistry.class);
        if (listeners != null) {
            listeners.stop();
        }
    }

    /**
     * Returns one bean of {@code type}, or null when the context holds none.
     *
     * <p>Null rather than a failure, because a class that starts a narrower context should not have
     * to know which of these two beans it happens to hold.
     *
     * @param context the started test context
     * @param type    the bean type to look for
     * @param <T>     the bean type
     * @return the bean, or null when the context holds none of that type
     */
    private static <T> T beanOrNull(ApplicationContext context, Class<T> type) {
        return context.getBeanNamesForType(type).length == 0 ? null : context.getBean(type);
    }
}
