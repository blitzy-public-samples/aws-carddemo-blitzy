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
package com.aws.carddemo.config;

import org.springframework.context.annotation.Configuration;

/**
 * Documented anchor for <em>cross-job</em> Spring Batch infrastructure customization in the
 * migrated AWS CardDemo application.
 *
 * <p>The legacy z/OS batch workload was expressed as JCL job streams ({@code legacy/app/jcl/*.jcl})
 * driving batch COBOL programs (for example {@code CBTRN02C}, {@code CBACT04C}, {@code CBSTM03A});
 * those streams are reproduced as Spring Batch chunk-oriented jobs. The CICS/CSD resource registry
 * {@code legacy/app/csd/CARDDEMO.CSD} is the conceptual source for the application's resource and
 * infrastructure wiring (Agent Action Plan &sect;0.4.1).
 *
 * <p><strong>This class is intentionally empty.</strong> Under Spring Boot 3.x the entire Spring
 * Batch infrastructure — the {@code JobRepository}, {@code JobLauncher}, {@code JobExplorer},
 * {@code JobRegistry}, and the batch {@code PlatformTransactionManager} — is supplied by Boot's
 * batch <em>auto-configuration</em>. None of that plumbing needs to be (or should be) re-declared
 * here. This type exists so that there is a single, obvious home for any future
 * <em>cross-cutting</em> batch customization that applies to <em>all</em> jobs — for example a
 * shared {@code JobExecutionListener}, a custom async {@code JobLauncher} (a {@code
 * TaskExecutor}-backed launcher), or a tuned {@code JobRepository} serializer. No such
 * customization is required today, so the class declares no fields and no {@code @Bean} methods.
 *
 * <p><strong>CRITICAL — do not add {@code @EnableBatchProcessing} (here or anywhere).</strong> In
 * Spring Boot 3.x, annotating any configuration class with {@code @EnableBatchProcessing} — or
 * defining a {@code DefaultBatchConfiguration} bean — <em>switches off</em> Boot's batch
 * auto-configuration. When that happens the {@code spring.batch.*} properties are no longer
 * honored, the auto-configured {@code JobRepository} / {@code JobLauncher} are no longer
 * contributed, and the application would have to wire all of that batch infrastructure by hand. The
 * CardDemo build relies on Boot's auto-configuration, so this annotation is forbidden in this class
 * and throughout the codebase. The application entry point {@code CardDemoApplication} likewise
 * carries no enable-annotation for exactly this reason.
 *
 * <p><strong>Per-job definitions live elsewhere.</strong> The individual {@code @Bean Job} and
 * {@code @Bean Step} definitions are <em>not</em> declared here; each belongs to its own
 * configuration class under the {@code com.aws.carddemo.batch.config} package — for example {@code
 * TransactionPostingJobConfig}, {@code InterestCalculationJobConfig}, {@code
 * StatementGenerationJobConfig}, and {@code TransactionReportJobConfig}. Those classes inject the
 * Boot-provided {@code JobRepository} and {@code PlatformTransactionManager} as {@code @Bean}
 * method parameters and assemble their jobs with the Spring Batch 5 {@code JobBuilder} / {@code
 * StepBuilder} fluent API.
 *
 * <p><strong>Relevant runtime properties are owned by {@code application.yml}, not by this
 * class.</strong> Two settings govern batch behaviour and must not be overridden by adding
 * configuration here:
 *
 * <ul>
 *   <li>{@code spring.batch.job.enabled=false} — migrated jobs are <em>not</em> auto-run on
 *       startup; each is launched explicitly (mirroring its JCL driver), with the JCL run-date
 *       parameter (for example {@code PARM='2022071800'} in {@code legacy/app/jcl/INTCALC.jcl})
 *       supplied as a Spring Batch {@code JobParameter} rather than hardcoded.
 *   <li>{@code spring.batch.jdbc.initialize-schema=always} — Boot creates the Spring Batch metadata
 *       tables ({@code BATCH_*}) at startup. Flyway owns only the business schema, so the batch
 *       metadata schema is intentionally managed by Boot. Both properties are honored precisely
 *       <em>because</em> this class does not disable batch auto-configuration.
 * </ul>
 *
 * @see Configuration
 */
@Configuration
public class BatchConfig {}
