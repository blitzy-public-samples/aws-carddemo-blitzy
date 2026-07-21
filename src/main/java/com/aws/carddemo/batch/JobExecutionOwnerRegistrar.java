package com.aws.carddemo.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * {@link BeanPostProcessor} that attaches the shared {@link BatchExecutionOwnerListener} to every
 * batch {@link org.springframework.batch.core.Job Job} in the application context, centrally and
 * without any per-job wiring (review finding&nbsp;F-02).
 *
 * <p><strong>Why a {@code BeanPostProcessor}.</strong> The owner stamp that makes abnormal-termination
 * recovery safe is only useful if it is present on <em>every</em> job execution; a job that forgot to
 * register the listener would, after a crash, be left unrecoverable exactly like before the fix. The
 * 12 migrated jobs in this package each build their {@code Job} bean independently
 * ({@code new JobBuilder(name, jobRepository)...}), so rather than edit all 12 (and risk missing a
 * future one), this post-processor intercepts each fully-initialized {@code Job} bean and, when it is
 * an {@link AbstractJob} (which every {@code SimpleJob}/{@code FlowJob} produced by {@code JobBuilder}
 * is), calls {@link AbstractJob#registerJobExecutionListener} to attach the listener. New jobs are
 * covered automatically.</p>
 *
 * <p><strong>Lazy dependency (avoids premature initialization).</strong> A {@code BeanPostProcessor}
 * is instantiated very early, before most singletons; injecting the listener directly could force the
 * listener &mdash; and transitively the {@code JobRepository} it needs &mdash; to be created before it
 * is ready. The listener is therefore obtained lazily through an {@link ObjectProvider} and resolved
 * only inside {@link #postProcessAfterInitialization}, by which time a {@code Job} bean is being
 * initialized and the {@code JobRepository} it depends on already exists.</p>
 *
 * <p>The processor is a no-op for every non-{@code Job} bean and returns the bean unchanged, so it is
 * safe and warning-free in all contexts.</p>
 *
 * <p><strong>Origin (lineage):</strong> net-new operational infrastructure with no COBOL ancestor;
 * it supports the abnormal-termination recovery that restores the legacy JES2 resubmit contract.
 * Rationale is recorded in {@code docs/decision-log.md} (Explainability rule).</p>
 *
 * @see BatchExecutionOwnerListener
 * @see BatchExecutionRecovery
 */
@Component
public class JobExecutionOwnerRegistrar implements BeanPostProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobExecutionOwnerRegistrar.class);

    /** Lazy handle to the shared owner listener; resolved only when a {@code Job} bean is seen. */
    private final ObjectProvider<BatchExecutionOwnerListener> ownerListenerProvider;

    /**
     * Creates the registrar with a lazy provider for the owner listener.
     *
     * @param ownerListenerProvider provider for the singleton {@link BatchExecutionOwnerListener};
     *                              never {@code null}
     */
    public JobExecutionOwnerRegistrar(ObjectProvider<BatchExecutionOwnerListener> ownerListenerProvider) {
        this.ownerListenerProvider = ownerListenerProvider;
    }

    /**
     * Attaches the owner listener to each {@link AbstractJob} bean after it is initialized.
     *
     * @param bean     the freshly initialized bean
     * @param beanName the bean name (used only for trace logging)
     * @return the same bean instance, unchanged
     * @throws BeansException never thrown by this implementation
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof AbstractJob job) {
            job.registerJobExecutionListener(ownerListenerProvider.getObject());
            LOGGER.debug("Attached BatchExecutionOwnerListener to job bean '{}'", beanName);
        }
        return bean;
    }
}
