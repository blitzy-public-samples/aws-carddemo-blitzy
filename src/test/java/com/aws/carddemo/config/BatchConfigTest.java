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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pure unit tests for {@link BatchConfig}, the documented anchor for cross-job Spring Batch
 * infrastructure in the migrated AWS CardDemo application.
 *
 * <p>The legacy z/OS batch workload — JCL job streams (for example {@code
 * legacy/app/jcl/POSTTRAN.jcl}) driving batch COBOL such as {@code CBTRN02C}, the daily
 * transaction-posting archetype — is reproduced as Spring Batch chunk-oriented jobs whose per-job
 * {@code @Bean Job} / {@code @Bean Step} definitions live in the separate {@code
 * com.aws.carddemo.batch.config} package (Agent Action Plan §0.4.1). {@link BatchConfig} itself
 * stays intentionally minimal: it is only the single, obvious home for any future cross-cutting
 * batch customization and therefore declares no {@code @Bean} methods.
 *
 * <p>The assertions below are reflection / annotation introspection only — there is deliberately no
 * {@code @SpringBootTest}, no Spring {@code ApplicationContext}, no Testcontainers, and no Docker —
 * so the suite is fast, hermetic, and runnable with no PostgreSQL or mainframe present.
 *
 * <p>The middle test is the single most important guard in this class. Under Spring Boot 3.x,
 * annotating <em>any</em> configuration class with {@code @EnableBatchProcessing} (or defining a
 * {@code DefaultBatchConfiguration} bean) <strong>switches off</strong> Boot's batch
 * auto-configuration: the auto-configured {@code JobRepository}, {@code JobLauncher}, and {@code
 * JobExplorer} are no longer contributed and the {@code spring.batch.*} properties are no longer
 * honored. Because the migration relies on Boot's auto-configuration to launch every reproduced job
 * (mirroring its JCL driver), the absence of that annotation on {@link BatchConfig} is a hard
 * parity / regression guard rather than a stylistic preference.
 *
 * @see BatchConfig
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BatchConfigTest {

  /**
   * {@link BatchConfig} must be a Spring {@link Configuration} class so that the container detects
   * it during component scanning and it can serve as the home for any future cross-job batch
   * customization.
   */
  @Test
  void is_annotated_as_a_spring_configuration() {
    assertThat(BatchConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
  }

  /**
   * Guards the classic Spring Boot 3.x batch footgun: a stray {@link EnableBatchProcessing} would
   * silently disable Boot's batch auto-configuration (the auto-configured {@code JobRepository},
   * {@code JobLauncher}, and {@code JobExplorer}, plus {@code spring.batch.*} property handling).
   * The annotation has {@code RUNTIME} retention, so {@link Class#isAnnotationPresent} is an
   * authoritative check. If this assertion ever fails, fix {@link BatchConfig} by removing the
   * annotation — never weaken the assertion.
   */
  @Test
  void does_not_enable_batch_processing_so_boot_autoconfiguration_stays_active() {
    // CRITICAL: @EnableBatchProcessing would DISABLE Spring Boot 3.x batch auto-config.
    // BatchConfig must stay minimal; per-job beans belong to com.aws.carddemo.batch.config.
    assertThat(BatchConfig.class.isAnnotationPresent(EnableBatchProcessing.class))
        .as(
            "BatchConfig must NOT declare @EnableBatchProcessing (Boot 3.x batch auto-config guard)")
        .isFalse();
  }

  /**
   * {@link BatchConfig} must declare no {@code @Bean} methods: the individual {@code Job} and
   * {@code Step} definitions belong to their own configuration classes under {@code
   * com.aws.carddemo.batch.config}, keeping this type a minimal cross-cutting anchor. Synthetic
   * methods (for example those injected by JaCoCo during a coverage run) are skipped so the
   * assertion reflects only author-declared methods.
   */
  @Test
  void declares_no_bean_methods() {
    for (Method method : BatchConfig.class.getDeclaredMethods()) {
      if (method.isSynthetic()) {
        continue; // ignore tooling-injected synthetic methods (e.g. JaCoCo)
      }
      assertThat(method.isAnnotationPresent(Bean.class))
          .as("Per-job @Bean methods belong in batch.config, not BatchConfig: %s", method)
          .isFalse();
    }
  }
}
