package com.carddemo.config;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Asynchronous-execution configuration for the CardDemo Spring Boot monolith.
 *
 * <p><strong>Migration role.</strong> This class is the small but essential enabler of the
 * CardDemo "submit a report job and return immediately" model. It reproduces, in Spring terms,
 * the fire-and-forget behavior of the legacy online report program
 * {@code app/cbl/CORPT00C.cbl}. After validating the report type (Monthly / Yearly / Custom) and
 * the date range (via {@code CSUTLDTC}), {@code CORPT00C} performs {@code SUBMIT-JOB-TO-INTRDR}
 * (CORPT00C lines 462-510), which writes a JCL job to the CICS internal reader (extra-partition
 * transient data queue {@code 'JOBS'} via {@code EXEC CICS WRITEQ TD}, lines 515-523) and then
 * <em>immediately</em> returns the confirmation message
 * {@code "<report> report submitted for printing ..."} (lines 449-454). The online transaction
 * never blocks on report completion. See AAP &sect;0.3.1 ({@code AsyncConfig.java} - async report
 * job submission) and &sect;0.3.2 (Asynchronous job submission).</p>
 *
 * <p><strong>What this class provides.</strong> It supplies <em>only</em> the asynchronous
 * infrastructure:</p>
 * <ul>
 *   <li>A single, bounded {@link ThreadPoolTaskExecutor} registered under the canonical bean name
 *       {@value #TASK_EXECUTOR_BEAN_NAME}. This is the load-bearing deliverable: it backs the
 *       {@code TaskExecutorJobLauncher} encapsulated by the {@code reportJobSubmitter} port declared
 *       in {@code com.carddemo.config.BatchConfig}, which {@code com.carddemo.service.ReportService}
 *       uses to submit the transaction-report job. Because that launcher runs the job on this pool,
 *       {@code JobLauncher.run(...)} returns immediately with a non-terminal {@code JobExecution}
 *       &mdash; the fire-and-forget analog of {@code CORPT00C}. {@code ReportService} is therefore
 *       <em>not</em> itself annotated {@code @Async} (it returns a plain value, for which
 *       {@code @Async} would be an anti-pattern); the asynchrony is provided by the launcher this
 *       executor backs.</li>
 *   <li>{@link EnableAsync @EnableAsync} together with the {@link AsyncConfigurer} contract below -
 *       this designates the {@value #TASK_EXECUTOR_BEAN_NAME} pool as the application's default
 *       {@code @Async} executor and installs a PII-safe uncaught-exception handler, so that any
 *       {@code void}-returning {@code @Async} method added in the future runs on the same bounded
 *       pool with observable failures (rather than the unbounded default executor and silently
 *       swallowed exceptions).</li>
 *   <li>A centralized {@link AsyncUncaughtExceptionHandler} (via {@link AsyncConfigurer}) that logs
 *       exceptions thrown from {@code void}-returning {@code @Async} methods, which would otherwise be
 *       silently swallowed.</li>
 * </ul>
 *
 * <p><strong>Strict layering.</strong> This class deliberately contains <em>no</em> report logic,
 * {@code JobLauncher} bean definitions, or batch job definitions - those live in
 * {@code com.carddemo.service.ReportService}, {@code com.carddemo.config.BatchConfig} (the
 * {@code reportJobSubmitter} that consumes this executor), and the {@code com.carddemo.batch} package
 * respectively. It also defines exactly one {@code Executor} bean: because Spring Boot's
 * {@code TaskExecutionAutoConfiguration} is {@code @ConditionalOnMissingBean(Executor.class)},
 * declaring this {@code taskExecutor} suppresses the auto-configured {@code applicationTaskExecutor},
 * guaranteeing a single, unambiguous {@link TaskExecutor} in the context (no
 * {@code NoUniqueBeanDefinitionException}).</p>
 *
 * <p><strong>Pool sizing.</strong> The pool is intentionally bounded (no unbounded,
 * {@code newCachedThreadPool}-style growth) and tuned for the low-volume report/batch submission
 * workload: {@value #CORE_POOL_SIZE} core threads, {@value #MAX_POOL_SIZE} maximum threads, and a
 * bounded queue of {@value #QUEUE_CAPACITY}. Under saturation a
 * {@link ThreadPoolExecutor.CallerRunsPolicy} provides natural backpressure so a submission is
 * never silently discarded, and a graceful shutdown lets already-accepted submissions drain before
 * the context closes.</p>
 *
 * @see org.springframework.scheduling.annotation.Async
 * @see AsyncConfigurer
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /** Logger for executor lifecycle and configuration events. */
    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Canonical bean name for the asynchronous {@link TaskExecutor}. Named exactly
     * {@code "taskExecutor"} so Spring's {@code @Async} support resolves it as the default
     * executor for bare {@code @Async} annotations.
     */
    public static final String TASK_EXECUTOR_BEAN_NAME = "taskExecutor";

    /** Prefix applied to every worker thread name for traceable logs/diagnostics. */
    public static final String THREAD_NAME_PREFIX = "carddemo-async-";

    /** Number of threads kept alive even when idle. */
    private static final int CORE_POOL_SIZE = 2;

    /** Hard ceiling on concurrently running worker threads. */
    private static final int MAX_POOL_SIZE = 5;

    /** Bounded backlog of tasks awaiting an available worker thread. */
    private static final int QUEUE_CAPACITY = 25;

    /**
     * Maximum time (seconds) the context will wait for in-flight async tasks to finish during a
     * graceful shutdown before forcibly terminating the pool.
     */
    private static final int AWAIT_TERMINATION_SECONDS = 30;

    /**
     * Builds the bounded {@link ThreadPoolTaskExecutor} that backs asynchronous execution in the
     * application: principally the {@code TaskExecutorJobLauncher} inside the {@code reportJobSubmitter}
     * port in {@code com.carddemo.config.BatchConfig} that submits the report job without blocking the
     * caller, and additionally any {@code @Async} method (for which it is the
     * {@link AsyncConfigurer}-designated default executor).
     *
     * <p>The bean is registered under {@value #TASK_EXECUTOR_BEAN_NAME}.
     * {@link ThreadPoolTaskExecutor#initialize() initialize()} is invoked explicitly so the
     * underlying {@link ThreadPoolExecutor} is fully
     * constructed before the bean is handed out; this is safe and idempotent with the bean
     * lifecycle. The concrete {@link ThreadPoolTaskExecutor} is created locally (so its configurer
     * methods are reachable) and returned through the {@link TaskExecutor} interface to keep the
     * public contract minimal. Spring still detects that the instance implements
     * {@code DisposableBean} and invokes its {@code destroy()} (pool shutdown) on context close
     * regardless of the declared return type.</p>
     *
     * @return a fully initialized, bounded {@link TaskExecutor} for asynchronous execution
     */
    @Bean(name = TASK_EXECUTOR_BEAN_NAME)
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);

        // Bounded backpressure: when both the pool and the queue are saturated, execute the task on
        // the submitting thread instead of discarding it. For the fire-and-forget report-submission
        // workload this guarantees no submission is ever silently lost (mirrors CORPT00C, which
        // hands the job to the internal reader and never drops it). Saturation is highly unlikely
        // at this pool sizing, so the common path remains genuinely asynchronous.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Graceful shutdown: allow already-accepted asynchronous submissions to drain so a report
        // job that has begun is not abandoned mid-flight when the application context closes.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);

        executor.initialize();

        log.info("Initialized '{}' ThreadPoolTaskExecutor (corePoolSize={}, maxPoolSize={}, "
                        + "queueCapacity={}, threadNamePrefix='{}')",
                TASK_EXECUTOR_BEAN_NAME, CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY,
                THREAD_NAME_PREFIX);

        return executor;
    }

    /**
     * Designates the application's default {@link Executor} for {@code @Async} methods.
     *
     * <p>Returns the very same singleton produced by {@link #taskExecutor()}: because this
     * {@code @Configuration} class is CGLIB-proxied ({@code proxyBeanMethods=true} by default),
     * the call is intercepted and resolves to the already-registered {@value #TASK_EXECUTOR_BEAN_NAME}
     * bean rather than constructing a second executor. This avoids any duplicate thread pool while
     * still centralizing the default executor through {@link AsyncConfigurer}.</p>
     *
     * @return the shared, bounded asynchronous executor
     */
    @Override
    public Executor getAsyncExecutor() {
        return taskExecutor();
    }

    /**
     * Provides the handler invoked when a {@code void}-returning {@code @Async} method throws.
     *
     * <p>Methods that return {@link java.util.concurrent.Future Future} (or
     * {@link java.util.concurrent.CompletableFuture}) propagate their exception through the returned
     * handle, but truly fire-and-forget {@code void} methods - the natural Java analogue of
     * {@code CORPT00C}'s submit-and-return - have nowhere to surface a failure. Without a handler
     * such exceptions are silently lost; this implementation logs them so report/batch submission
     * failures remain observable.</p>
     *
     * @return a logging {@link AsyncUncaughtExceptionHandler}
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new LoggingAsyncUncaughtExceptionHandler();
    }

    /**
     * {@link AsyncUncaughtExceptionHandler} that records uncaught exceptions from {@code void}
     * {@code @Async} methods via SLF4J, including the offending method signature to aid diagnosis
     * of failed asynchronous submissions.
     *
     * <p><strong>Log-safety:</strong> the raw argument values are deliberately <em>never</em>
     * logged. Asynchronous methods (e.g. report/batch submission) can receive request DTOs, JWTs,
     * passwords, SSNs, or account/card identifiers; emitting their {@code toString()} would violate
     * the AAP PII-suppression / log-safety rules (AAP &sect;0.6.8, &sect;0.7.1). Only the declaring
     * class, the method name, and the (non-sensitive) argument <em>count</em> are recorded.</p>
     */
    static final class LoggingAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {

        /** Dedicated logger so async failures are easy to filter in aggregated logs. */
        private static final Logger asyncLog =
                LoggerFactory.getLogger(LoggingAsyncUncaughtExceptionHandler.class);

        /**
         * Logs the uncaught exception together with the fully-qualified method signature and the
         * number of arguments passed to the asynchronous invocation.
         *
         * <p>The argument <em>values</em> are intentionally omitted from the log to prevent leaking
         * request DTOs, credentials, tokens, or other PII; only the argument count is recorded so
         * the failure remains diagnosable without disclosing sensitive data.</p>
         *
         * @param ex     the exception thrown by the {@code @Async} method (never {@code null})
         * @param method the {@code @Async} method that raised the exception
         * @param params the arguments supplied to the invocation (possibly empty); only the count
         *               is logged, never the values
         */
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            asyncLog.error("Uncaught exception in @Async method '{}.{}' (paramCount={}): {}",
                    method.getDeclaringClass().getName(),
                    method.getName(),
                    params.length,
                    ex.getMessage(),
                    ex);
        }
    }
}
