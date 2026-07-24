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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Failsafe integration smoke test that boots the full transaction-service
 *  Spring context against a real, throwaway PostgreSQL provisioned by Testcontainers
 *  (the ``jdbc:tc:postgresql:18:///carddemo`` datasource URL in the ``test`` profile
 *  makes ``ContainerDatabaseDriver`` start and stop the ``postgres:18`` container
 *  automatically). It verifies two invariants of the re-platformed transaction
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
        "spring.jpa.hibernate.ddl-auto=update",
        // Provision the Spring Batch metadata tables for the JobRepository query.
        "spring.batch.jdbc.initialize-schema=always"
})
@ActiveProfiles("test")
public class TransactionServiceApplicationIT {

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
