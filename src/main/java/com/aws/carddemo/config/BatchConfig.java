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
package com.aws.carddemo.config;

import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch infrastructure configuration home for the AWS CardDemo re-platform.
 *
 * <p>In the legacy mainframe system, batch work was driven by JCL: jobs such as
 * {@code POSTTRAN} (transaction posting, program {@code CBTRN02C}), {@code INTCALC}
 * (interest calculation, {@code CBACT04C}), {@code CREASTMT} (statement generation,
 * {@code CBSTM03A}), {@code COMBTRAN} (a {@code SORT} combine) and {@code TRANBKP}
 * (an {@code IDCAMS REPRO} backup) were submitted and scheduled by the JCL scheduler.
 * In the Java target those jobs are re-expressed as eleven Spring Batch {@code Job}
 * beans in the sibling {@code com.aws.carddemo.batch} package, and this class is their
 * infrastructure anchor (AAP sections 0.2.1, 0.4.4 and 0.5.4).
 *
 * <p>The primary value of this class is explainability: it documents the batch
 * infrastructure contract and, critically, the customizations it deliberately does
 * <em>not</em> apply. The following decisions were verified against Spring Batch 5.2
 * and Spring Boot 3.5.x and must not be changed without revisiting the decision log:
 *
 * <ol>
 *   <li><b>(a) {@code @EnableBatchProcessing} is intentionally absent.</b> In Spring Boot
 *       3.x, declaring {@code @EnableBatchProcessing} anywhere in the application causes
 *       Boot's batch auto-configuration to back off, including the automatic creation and
 *       initialization of the {@code BATCH_*} metadata schema. Because CardDemo relies
 *       entirely on that auto-configuration, the annotation appears neither here, nor on
 *       {@code com.aws.carddemo.CardDemoApplication}, nor anywhere else in the code base.</li>
 *
 *   <li><b>(b) No batch infrastructure beans are redeclared.</b> Spring Boot
 *       auto-configuration already supplies the {@code JobRepository}, {@code JobLauncher},
 *       {@code JobExplorer}, {@code JobOperator}, {@code JobRegistry} and the batch
 *       {@code PlatformTransactionManager}. Redefining any of them here would shadow the
 *       auto-configured bean or trigger a conflicting-definition failure, so none are
 *       declared. The {@code BATCH_*} metadata tables are created because
 *       {@code spring.batch.jdbc.initialize-schema=always} in {@code application.yml}.</li>
 *
 *   <li><b>(c) No {@code JobRegistry} populator is declared.</b> As of Spring Batch 5.2
 *       (change #4855) the {@code JobRegistry} populates itself with the {@code Job} beans
 *       found in the application context, and Boot's {@code SpringBootBatchConfiguration}
 *       (which extends {@code DefaultBatchConfiguration}) already contributes the
 *       auto-populating {@code JobRegistrySmartInitializingSingleton}. The framework
 *       requires <em>at most one</em> such populator; adding another here - even the
 *       deprecated {@code JobRegistryBeanPostProcessor}, and even when guarded with
 *       {@code @ConditionalOnMissingBean} - would risk a duplicate-populator conflict or be
 *       dead code. Launch-by-name through {@code JobOperator} / {@code JobRegistry}
 *       therefore already works out of the box.</li>
 *
 *   <li><b>(d) No job runs at startup.</b> {@code application.yml} sets
 *       {@code spring.batch.job.enabled=false}, which disables Boot's startup
 *       {@code JobLauncherApplicationRunner}. With eleven {@code Job} beans on the
 *       classpath, this flag is the explicit guarantee that none of them executes when the
 *       context starts. No {@code JobLauncherApplicationRunner} or {@code CommandLineRunner}
 *       that would launch a job on boot is defined here or elsewhere.</li>
 *
 *   <li><b>(e) Jobs are launched explicitly.</b> A job is run either by injecting its
 *       specific {@code Job} bean together with the auto-configured {@code JobLauncher} (for
 *       example, {@code ReportService} injects the job qualified {@code transactionReportJob}),
 *       or by name through {@code JobOperator} from the CI/CD workflow
 *       ({@code .github/workflows/ci.yml}). That CI/CD trigger is the modern equivalent of
 *       the mainframe JCL scheduler (AAP section 0.4.4).</li>
 *
 *   <li><b>(f) Job definitions live elsewhere.</b> The eleven Spring Batch {@code Job} beans
 *       and their chunk-oriented {@code ItemReader} / {@code ItemProcessor} /
 *       {@code ItemWriter} components - together with step ordering,
 *       {@code SORT}-to-{@code Comparator} translation, and the generation-data-group backup
 *       re-expressed as a scheduled database backup - are defined in
 *       {@code com.aws.carddemo.batch}, not here. This class only guarantees the
 *       infrastructure and launch contract described above.</li>
 * </ol>
 *
 * <p>Consequently this configuration intentionally declares no {@code @Bean} methods. Should
 * a genuinely required, non-duplicative batch customization ever be needed (for instance a
 * custom {@code JobParametersConverter}), it may be added here as a {@code @Bean}, but the
 * four prohibitions above - no {@code @EnableBatchProcessing}, no redeclared infrastructure
 * bean, no second {@code JobRegistry} populator, and no startup job runner - must always
 * hold, and the build must remain warning free.
 *
 * @see com.aws.carddemo.CardDemoApplication
 */
@Configuration
public class BatchConfig {

    /*
     * This configuration is intentionally empty of bean definitions.
     *
     * Spring Boot auto-configuration provides every batch infrastructure bean
     * (JobRepository, JobLauncher, JobExplorer, JobOperator, the self-populating
     * JobRegistry, and the batch PlatformTransactionManager). Declaring
     * @EnableBatchProcessing would make that auto-configuration back off, and declaring
     * a second JobRegistry populator would conflict with the one Boot already contributes
     * (Spring Batch 5.2, change #4855) - so neither is present.
     *
     * Jobs never run on startup because spring.batch.job.enabled=false; the eleven Job
     * beans in com.aws.carddemo.batch are launched explicitly via bean injection or, for
     * CI/CD scheduling (the JCL-scheduling equivalent), by name through JobOperator. See
     * the class-level Javadoc for the full rationale.
     */
}
