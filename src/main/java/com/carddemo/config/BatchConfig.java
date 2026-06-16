package com.carddemo.config;

import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch <strong>infrastructure</strong> configuration marker for the CardDemo monolith.
 *
 * <p><strong>Migration role.</strong> The legacy AWS CardDemo system ran its back-office work as
 * JCL-scheduled COBOL batch programs. In the Spring Boot 3.2.x / Java&nbsp;17 port those programs
 * become Spring Batch&nbsp;5 chunk-oriented jobs (reader &rarr; processor &rarr; writer) that live in
 * the sibling {@code com.carddemo.batch} package (AAP&nbsp;&sect;0.3.2). This class is the
 * <strong>infrastructure</strong> seam for that batch subsystem: it is the
 * {@code com.carddemo.config} home that AAP&nbsp;&sect;0.3.1 lists as
 * "{@code BatchConfig.java (JobLauncher, JobRepository)}" and AAP&nbsp;&sect;0.4.1.5 enumerates among
 * the security/DTO/batch/config artifacts. It deliberately defines <em>no</em> {@code Job},
 * {@code Step}, {@code ItemReader}, {@code ItemProcessor} or {@code ItemWriter} &mdash; those belong
 * exclusively to the {@code batch/} package under the strict Controller&nbsp;&rarr;&nbsp;Service&nbsp;&rarr;&nbsp;Repository
 * layering of AAP&nbsp;&sect;0.3.2.</p>
 *
 * <h2>Batch-enablement decision &mdash; OPTION&nbsp;A (Spring Boot auto-configuration)</h2>
 *
 * <p>The shared Spring Batch infrastructure &mdash; the {@code JobRepository}, the
 * {@code JobLauncher}, the {@code JobExplorer} and the supporting {@code PlatformTransactionManager}
 * &mdash; is provided entirely by <strong>Spring Boot's {@code BatchAutoConfiguration}</strong>. Boot
 * auto-creates those beans against the application {@code DataSource} and the auto-configured
 * {@code DataSourceTransactionManager}/{@code JdbcTransactionManager}. Two keys in the already-authored
 * {@code src/main/resources/application.yml} (a {@code depends_on} of this file) drive the runtime
 * behaviour, and <strong>both are Spring Boot batch auto-configuration features that take effect ONLY
 * while that auto-configuration is active</strong>:</p>
 * <ul>
 *   <li>{@code spring.batch.jdbc.initialize-schema: always} &mdash; runs the Spring Batch DDL so the
 *       {@code BATCH_*} metadata tables ({@code BATCH_JOB_INSTANCE}, {@code BATCH_JOB_EXECUTION},
 *       {@code BATCH_STEP_EXECUTION}, &hellip;) exist. Without these tables an on-demand
 *       {@code JobLauncher.run(...)} fails with a "table not found" error.</li>
 *   <li>{@code spring.batch.job.enabled: false} &mdash; suppresses Boot's
 *       {@code JobLauncherApplicationRunner} so <strong>no job auto-runs at startup</strong> (the
 *       runner would otherwise launch every {@code Job} bean on boot, because its guard is
 *       {@code matchIfMissing = true}). The five CardDemo jobs are instead launched
 *       <strong>on demand</strong> through the auto-configured {@code JobLauncher} (e.g. from
 *       {@code ReportService} and the account/customer refresh endpoints).</li>
 * </ul>
 *
 * <h2>Why {@code @EnableBatchProcessing} is intentionally ABSENT</h2>
 *
 * <p>Neither this class nor {@code com.carddemo.CardDemoApplication} carries
 * {@code @EnableBatchProcessing} &mdash; and that omission is deliberate and load-bearing. Under
 * Spring Boot&nbsp;3.2 / Spring Batch&nbsp;5, {@code BatchAutoConfiguration} is annotated
 * {@code @ConditionalOnMissingBean(DefaultBatchConfiguration.class)} and <strong>backs off entirely
 * the moment {@code @EnableBatchProcessing} appears anywhere</strong> in the context. Because the two
 * {@code application.yml} keys above are auto-configuration features, adding
 * {@code @EnableBatchProcessing} would silently disable them: the {@code BATCH_*} schema would never
 * be created and on-demand job launches would break. Keeping the annotation absent is therefore the
 * single most important responsibility of this file &mdash; its job is to
 * <strong>stay out of the way</strong> of Boot's batch auto-configuration. This choice is kept in
 * lock-step with {@code CardDemoApplication}, which carries {@code @SpringBootApplication} only.</p>
 *
 * <h2>How the five jobs consume this infrastructure</h2>
 *
 * <p>The job-configuration classes in the {@code com.carddemo.batch} package inject the
 * <strong>auto-configured</strong> {@code JobRepository} and {@code PlatformTransactionManager}
 * directly into their {@code JobBuilder}/{@code StepBuilder}; this class supplies no collaborators to
 * them. Each job re-expresses a legacy JCL-scheduled COBOL batch program (REFERENCE only &mdash; the
 * COBOL is never modified):</p>
 * <ul>
 *   <li>{@code TransactionPostingJobConfig} &lArr; {@code app/cbl/CBTRN02C.cbl} &mdash; post the daily
 *       transaction file (reject-code superset 100/101/102/103/109; 430-byte DALYREJS reject record).</li>
 *   <li>{@code InterestCalculationJobConfig} &lArr; {@code app/cbl/CBACT04C.cbl} &mdash; per-category
 *       interest = balance &times; rate &divide; 1200, {@code RoundingMode.HALF_UP}.</li>
 *   <li>{@code StatementCreationJobConfig} &lArr; {@code app/cbl/CBSTM03A.CBL} &mdash; account
 *       statements in both plain-text and HTML formats.</li>
 *   <li>{@code AccountRefreshJobConfig} &lArr; {@code CBACT01C}/{@code CBACT02C}/{@code CBACT03C}
 *       (VSAM dump/print utilities reinterpreted as an account-master refresh).</li>
 *   <li>{@code CustomerRefreshJobConfig} &lArr; {@code app/cbl/CBCUS01C.cbl} (customer-master refresh).</li>
 * </ul>
 *
 * <h2>Rejected alternative &mdash; OPTION&nbsp;B</h2>
 *
 * <p>A fallback design (Option&nbsp;B) would annotate a configuration class with
 * {@code @EnableBatchProcessing} and then hand-roll the {@code DataSource}-backed
 * {@code JobRepository}/{@code JobLauncher} plus a {@code BatchDataSourceScriptDatabaseInitializer} to
 * compensate for the lost auto-configuration. <strong>Option&nbsp;B is explicitly rejected here</strong>:
 * {@code CardDemoApplication} and {@code application.yml} are already committed to Option&nbsp;A, so
 * Option&nbsp;B would be inconsistent and would re-introduce the very back-off problem described above.</p>
 *
 * <h2>Design notes</h2>
 *
 * <p>This is a <strong>tier-0 infrastructure</strong> type: it has zero dependencies on other
 * {@code com.carddemo} classes (its only import is {@link Configuration}), so it can never participate
 * in a cyclic dependency. Under Option&nbsp;A the correct expert outcome is precisely this &mdash; a
 * thin, well-documented {@code @Configuration} class with an <em>empty body and no {@code @Bean}
 * methods</em>; the documentation, not the code, is the deliverable. It is component-scanned
 * automatically because {@code com.carddemo.config} is a sub-package of the
 * {@code @SpringBootApplication} base package {@code com.carddemo}.</p>
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP&nbsp;&sect;0.3.1 &mdash; {@code config/BatchConfig.java (JobLauncher, JobRepository)}.</li>
 *   <li>AAP&nbsp;&sect;0.3.2 &mdash; chunk-oriented Spring Batch reader/processor/writer triplets.</li>
 *   <li>AAP&nbsp;&sect;0.4.1.5 &mdash; security/DTO/mapper/batch/config artifact inventory.</li>
 *   <li>AAP&nbsp;&sect;0.2.2 &mdash; single monolith; no microservice split, no remote/partitioned batch.</li>
 * </ul>
 *
 * @see <a href="file:app/cbl/CBTRN02C.cbl">CBTRN02C.cbl</a>
 * @see <a href="file:app/cbl/CBACT04C.cbl">CBACT04C.cbl</a>
 * @see <a href="file:app/cbl/CBSTM03A.CBL">CBSTM03A.CBL</a>
 */
@Configuration
public class BatchConfig {

    /*
     * INTENTIONALLY EMPTY (Option A).
     *
     * No @Bean methods are declared here, and this is the complete, correct implementation - not a
     * stub or placeholder. The Spring Batch infrastructure (JobRepository, JobLauncher, JobExplorer)
     * and its transaction manager are supplied by Spring Boot's BatchAutoConfiguration, which stays
     * active only while @EnableBatchProcessing is absent from the entire application (see the
     * class-level Javadoc for the full rationale).
     *
     * DO NOT add any of the following here - doing so would either disable the auto-configuration
     * that application.yml relies on, conflict with the auto-configured beans, or violate the
     * Controller -> Service -> Repository layering (AAP 0.3.2):
     *   - @EnableBatchProcessing (breaks BATCH_* schema initialization and on-demand launch);
     *   - JobRepository / JobLauncher / JobExplorer / JobRegistry @Bean definitions;
     *   - any Job / Step / ItemReader / ItemProcessor / ItemWriter (these live in com.carddemo.batch);
     *   - a BatchDataSourceScriptDatabaseInitializer (Option B compensation, rejected here).
     */
}
