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

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
// The shared migration set in carddemo-common (enabled for the ``test`` profile) provisions the
// whole schema - business tables AND the Spring Batch metadata the JobRepository query needs - so
// the context boots on the production settings: Hibernate ``ddl-auto: validate`` with the JDBC
// batch-schema initializer left off.
@SpringBootTest
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

        assertThat(jobRepository.getJobInstances("transactionPostingJob", 0, 100)).isEmpty();
    }
}
