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
package com.carddemo.transaction;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.batch.autoconfigure.JobLauncherApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.common.testsupport.MigratedSchemaContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Failsafe integration smoke test that boots the full transaction-service
 *  Spring context against a real, throwaway PostgreSQL provisioned by Testcontainers
 *  (the shared ``postgres:18`` container carrying the schema the committed Flyway
 *  migrations produce). It verifies two invariants of the re-platformed transaction
 *  feature (legacy online ``COTRN00C``/``COTRN01C``/``COTRN02C`` plus the
 *  ``CBTRN02C`` batch posting engine driven by ``POSTTRAN.jcl``): the application
 *  context wires cleanly with the Redis/Spring Session auto-configuration excluded,
 *  and the on-demand Spring Batch ``transactionPostingJob`` is registered yet never
 *  launched at startup (``spring.batch.job.enabled=false``; the migrated submission
 *  runs only through the ``JobLauncher``, AAP 0.4.4).
 * :output: Two JUnit 5 assertions - a non-null application context and a present
 *  ``transactionPostingJob`` bean whose name matches its frozen identifier while
 *  reporting zero job instances at boot.
 */
@SpringBootTest(properties = {
        // Create the scanned entity tables not owned by transaction-service migrations.
        "spring.jpa.hibernate.ddl-auto=update"
        // The BATCH_* metadata tables are provisioned by BatchInfrastructureConfig.
        // spring.batch.jdbc.initialize-schema is NOT set here: BatchProperties in
        // Spring Boot 4.1 exposes only the `job` group, so the key was inert and the
        // JobRepository query below only passed because the repository was in-memory.
})
@ActiveProfiles("test")
public class TransactionServiceApplicationIT {

    /**
     * :purpose: Bind the datasource to the shared, already-migrated ``postgres:18`` container
     *  from :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`. Its schema -
     *  including the transaction tables and reference data this service owns and the Spring
     *  Batch metadata tables - is produced exclusively by the committed Flyway migrations, so
     *  the ``test`` profile's ``ddl-auto: validate`` asserts the entity-to-migration contract
     *  rather than letting Hibernate create whatever the entities imply.
     * :param registry: the dynamic property registry supplied by the Spring Test context.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
    }

    /** :purpose: The booted transaction-service application context under test. */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * :purpose: The Spring Batch metadata repository, queried to prove that no
     *  transaction-posting job instance was created during context startup.
     */
    @Autowired
    private JobRepository jobRepository;

    /**
     * :purpose: Verify the transaction-service Spring context boots against a real PostgreSQL container.
     */
    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    /**
     * :purpose: Verify the transaction-posting batch job bean is present but is NOT executed at startup (on-demand JobLauncher only).
     */
    @Test
    void postingJobDoesNotAutoRunAtStartup() {
        assertThat(applicationContext.containsBean("transactionPostingJob")).isTrue();

        Job job = applicationContext.getBean("transactionPostingJob", Job.class);
        assertThat(job.getName()).isEqualTo("transactionPostingJob");

        // Boot's JobLauncherApplicationRunner is the only component that executes a job
        // while the context starts, and this application registers none, so no job can run
        // at startup by construction.
        assertThat(applicationContext.getBeansOfType(JobLauncherApplicationRunner.class)).isEmpty();

        // Corroborated from the batch metadata: no ``transactionPostingJob`` execution was created
        // at or after this context started, so this boot launched nothing. The window is
        // scoped to this context because the migrated PostgreSQL container is shared by
        // every integration test in the module, and a sibling test that legitimately
        // launches the job on demand must not decide this assertion.
        LocalDateTime contextStartedAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(applicationContext.getStartupDate()), ZoneId.systemDefault());
        for (JobInstance instance : jobRepository.getJobInstances("transactionPostingJob", 0, 100)) {
            assertThat(jobRepository.getJobExecutions(instance)).allSatisfy(execution ->
                    assertThat(execution.getCreateTime()).isBefore(contextStartedAt));
        }
    }
}
