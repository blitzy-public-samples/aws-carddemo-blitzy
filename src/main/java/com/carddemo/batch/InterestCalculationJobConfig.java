package com.carddemo.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.service.InterestCalculationService;

/**
 * Spring Batch&nbsp;5 job/step assembly for the <strong>interest calculation</strong> job &mdash; the
 * Java port of the legacy COBOL batch program {@code app/cbl/CBACT04C.cbl} (the JCL-scheduled
 * {@code INTCALC} batch step). It is the interest-calculation analogue of the sibling refresh-job
 * configurations in this package (AAP &sect;0.3.2, &sect;0.4.1.5, &sect;0.6.3; folder requirements
 * job&nbsp;#2).
 *
 * <h2>Thin orchestration wrapper &mdash; all interest semantics live in the service</h2>
 * <p>This class is deliberately a <strong>thin orchestration wrapper</strong>: it declares a single
 * tasklet {@link Step} that delegates the <em>entire</em> per-category interest algorithm to
 * {@link InterestCalculationService#calculateInterest(String)}. It contains
 * <strong>no interest arithmetic of any kind</strong>. Specifically, every one of the following lives
 * in {@link InterestCalculationService} and <em>not</em> here:</p>
 * <ul>
 *   <li>the per-{@code TCATBAL}-category iteration over transaction-category balances;</li>
 *   <li>the {@code DISCGRP} disclosure-group interest-rate resolution keyed by
 *       {@code (acctGroupId, tranTypeCd, tranCatCd)} with the {@code 'DEFAULT'} group fallback;</li>
 *   <li>the parity-critical monthly-interest computation
 *       {@code balance.multiply(rate).divide(1200, 2, RoundingMode.HALF_UP)} using {@link java.math.BigDecimal};</li>
 *   <li>writing one interest {@code TRANSACT} row per category;</li>
 *   <li>adding the accumulated interest to {@code ACCT-CURR-BAL} ({@code curr_bal}); and</li>
 *   <li>zeroing the cycle totals ({@code ACCT-CURR-CYC-CREDIT} / {@code ACCT-CURR-CYC-DEBIT}).</li>
 * </ul>
 * <p>The {@code CBACT04C} {@code 1400-COMPUTE-FEES} paragraph is an explicit {@code 'To be implemented'}
 * stub (CBACT04C.cbl L518-520); there is therefore <strong>no fee logic to port</strong> in either the
 * service or this job.</p>
 *
 * <h2>The {@code runDate} parameter (mirrors the COBOL {@code PARM-DATE})</h2>
 * <p>The legacy program receives a parameter date through its {@code PROCEDURE DIVISION USING
 * EXTERNAL-PARMS} linkage &mdash; {@code PARM-DATE PIC X(10)} (CBACT04C.cbl L176-180) &mdash; which it
 * uses as the date component when {@code STRING}-building each interest transaction id (CBACT04C.cbl
 * L476). That single value is reproduced here as the {@code runDate} <em>job parameter</em>: an ISO
 * {@code yyyy-MM-dd} string forwarded verbatim to the service, which uses it both as the
 * interest-transaction id date component and as the processing-timestamp basis. When the job is
 * launched without an explicit {@code runDate}, the tasklet defaults to today's ISO date so an
 * unparameterised run behaves like a same-day batch.</p>
 *
 * <h2>Batch-enablement model &mdash; OPTION&nbsp;A (Spring Boot auto-configuration)</h2>
 * <p>Consistent with {@code CardDemoApplication}, {@code config/BatchConfig}, and the sibling
 * {@code *RefreshJobConfig} classes, this configuration carries <strong>no</strong>
 * {@code @EnableBatchProcessing}. Under Spring Boot&nbsp;3.2 / Spring Batch&nbsp;5 the shared
 * infrastructure ({@link JobRepository}, {@link PlatformTransactionManager}, {@code JobLauncher}, and
 * the {@code stepScope}/{@code jobScope} post-processors) is supplied by Boot's
 * {@code BatchAutoConfiguration}; introducing {@code @EnableBatchProcessing} would cause that
 * auto-configuration to back off and break both the {@code BATCH_*} schema initialization and the
 * on-demand launch model. Because the scope infrastructure is present, the {@link StepScope} SpEL
 * late-binding used by {@link #interestCalculationTasklet(String)} resolves correctly at step-execution
 * time. The auto-configured {@link JobRepository} and {@link PlatformTransactionManager} are
 * constructor-injected and handed directly to the {@link JobBuilder} / {@link StepBuilder}. Jobs run on
 * demand ({@code spring.batch.job.enabled=false}); nothing launches at startup.</p>
 *
 * <h2>Layering &mdash; allowed {@code batch &rarr; service} direction only</h2>
 * <p>This configuration imports a single {@code com.carddemo} collaborator,
 * {@link InterestCalculationService} from the {@code service} layer. This is the allowed dependency
 * direction within the strict Controller&nbsp;&rarr;&nbsp;Service&nbsp;&rarr;&nbsp;Repository layering
 * (AAP &sect;0.3.2). The service deliberately imports no Spring Batch types and nothing from
 * {@code com.carddemo.batch}, so there is no service&nbsp;&rarr;&nbsp;batch back-edge and hence no
 * circular dependency. The surrounding step transaction is provided by the injected transaction
 * manager; the service method is additionally {@code @Transactional} in its own right.</p>
 *
 * @see InterestCalculationService
 * @see <a href="file:app/cbl/CBACT04C.cbl">app/cbl/CBACT04C.cbl (authoritative interest algorithm)</a>
 * @see com.carddemo.config.BatchConfig
 * @see AccountRefreshJobConfig
 * @see CustomerRefreshJobConfig
 */
@Configuration
public class InterestCalculationJobConfig {

    /**
     * SLF4J logger emitting INFO start/completion markers around the delegated interest run. It never
     * logs monetary amounts, account identifiers, or any PII &mdash; only the non-sensitive
     * {@code runDate} job parameter.
     */
    private static final Logger log = LoggerFactory.getLogger(InterestCalculationJobConfig.class);

    /**
     * Auto-configured Spring Batch metadata repository (Boot {@code BatchAutoConfiguration}, OPTION&nbsp;A),
     * used to construct the {@link Step} and {@link Job} builders.
     */
    private final JobRepository jobRepository;

    /**
     * Auto-configured transaction manager bounding the tasklet step's transaction. The delegated
     * service method is itself {@code @Transactional}, so this provides the surrounding step
     * transaction boundary.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * The interest-calculation service that owns the full {@code CBACT04C} algorithm. This job calls
     * only {@link InterestCalculationService#calculateInterest(String)}.
     */
    private final InterestCalculationService interestCalculationService;

    /**
     * Single all-arguments constructor &mdash; because it is the sole constructor, Spring performs
     * constructor injection without an explicit {@code @Autowired} annotation, and all collaborators are
     * stored in {@code final} fields (immutable after construction).
     *
     * @param jobRepository              the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager         the auto-configured {@link PlatformTransactionManager} for the step
     * @param interestCalculationService the service that performs the entire per-category interest run
     */
    public InterestCalculationJobConfig(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager,
                                        InterestCalculationService interestCalculationService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.interestCalculationService = interestCalculationService;
    }

    /**
     * The single tasklet of the interest-calculation job. It binds the {@code runDate} job parameter via
     * {@link StepScope} SpEL late binding &mdash; using the supplied {@code runDate} when present and
     * otherwise defaulting to today's ISO date ({@code T(java.time.LocalDate).now().toString()} yields
     * {@code yyyy-MM-dd}) &mdash; then delegates the entire interest run to
     * {@link InterestCalculationService#calculateInterest(String)} and returns
     * {@link RepeatStatus#FINISHED} so the step executes exactly once.
     *
     * <p>{@code @StepScope} is mandatory here: the {@code jobParameters} expression can only be resolved
     * once a {@code StepExecution} (and therefore the launch-time parameters) exists, which is precisely
     * when a step-scoped bean is instantiated. The bean is intentionally a {@link Tasklet} (a functional
     * interface), expressed as the lambda {@code (contribution, chunkContext) -> ...}; no chunk
     * reader/processor/writer is used because the whole unit of work is a single transactional service
     * call rather than an item stream.</p>
     *
     * <p>This method performs <strong>no</strong> interest arithmetic, balance iteration, rate lookup,
     * or transaction writing &mdash; that is entirely the service's responsibility (see the class
     * Javadoc).</p>
     *
     * @param runDate the run/parameter date (ISO {@code yyyy-MM-dd}) bound from the {@code runDate} job
     *                parameter, defaulting to today when the parameter is absent
     * @return a {@link Tasklet} that delegates one full interest run to the service and finishes
     */
    @Bean
    @StepScope
    public Tasklet interestCalculationTasklet(
            @Value("#{jobParameters['runDate'] ?: T(java.time.LocalDate).now().toString()}") String runDate) {
        return (contribution, chunkContext) -> {
            log.info("Starting interest calculation for runDate={}", runDate);
            interestCalculationService.calculateInterest(runDate);
            log.info("Interest calculation completed for runDate={}", runDate);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The single step of the interest-calculation job, wrapping {@link #interestCalculationTasklet(String)}.
     *
     * <p>Built with the Spring Batch&nbsp;5 {@code StepBuilder.tasklet(Tasklet, PlatformTransactionManager)}
     * overload, always passing the injected {@link #transactionManager} so the tasklet executes within a
     * managed transaction (the delegated service method is additionally {@code @Transactional}, so this
     * establishes the surrounding step transaction boundary). The {@code interestCalculationTasklet}
     * argument is injected by name; because that bean is {@link StepScope}-scoped, Spring supplies a
     * scoped proxy that is resolved per step execution.</p>
     *
     * @param interestCalculationTasklet the step-scoped tasklet bean (injected by name)
     * @return the {@code interestCalculationStep} {@link Step}
     */
    @Bean
    public Step interestCalculationStep(Tasklet interestCalculationTasklet) {
        return new StepBuilder("interestCalculationStep", jobRepository)
                .tasklet(interestCalculationTasklet, transactionManager)
                .build();
    }

    /**
     * The {@code interestCalculationJob} composed of the single {@link #interestCalculationStep(Tasklet)}.
     *
     * <p>The bean is named <strong>exactly</strong> {@code interestCalculationJob}; this is the
     * identifier under which it is launched on demand through the auto-configured {@code JobLauncher},
     * typically with an optional {@code runDate} job parameter and a unique {@code run.id} parameter to
     * permit re-runs of the same logical date. It runs the single tasklet step to completion, mirroring
     * the linear top-to-bottom flow of {@code CBACT04C}.</p>
     *
     * @param interestCalculationStep the interest-calculation step bean (injected by name)
     * @return the configured {@link Job} named {@code interestCalculationJob}
     */
    @Bean
    public Job interestCalculationJob(Step interestCalculationStep) {
        return new JobBuilder("interestCalculationJob", jobRepository)
                .start(interestCalculationStep)
                .build();
    }
}
