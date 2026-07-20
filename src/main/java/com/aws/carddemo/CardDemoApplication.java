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
package com.aws.carddemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.aws.carddemo.config.BatchExitCodeGenerator;

/**
 * Spring Boot entry point for the AWS CardDemo re-platform.
 *
 * <p>CardDemo is the functionally-equivalent Java 25 and Spring Boot 3 migration of the
 * AWS CardDemo mainframe credit-card account management system, originally implemented in
 * COBOL, CICS, VSAM, and JCL. This class is the single application bootstrap: it is the
 * JVM and Spring analog of the CICS and Language Environment runtime that hosted the
 * online transactions, and of the JCL scheduler that drove the batch programs. The
 * catalog of those legacy transactions and programs is retained for reference in
 * {@code app/csd/CARDDEMO.CSD} (relocated under {@code legacy/}).
 *
 * <p>The {@link SpringBootApplication @SpringBootApplication} meta-annotation combines
 * {@code @Configuration}, {@code @EnableAutoConfiguration}, and {@code @ComponentScan}
 * rooted at this class's package, {@code com.aws.carddemo}. Because every layer package
 * ({@code config}, {@code domain}, {@code repository}, {@code dto}, {@code mapper},
 * {@code web}, {@code service}, {@code batch}, {@code exception}, {@code observability},
 * {@code security}, and {@code common}) is nested beneath this root package, the default
 * component scan, JPA entity scan, and Spring Data repository scan discover them all with
 * no narrowed, hand-maintained base-package configuration.
 *
 * <p>Cross-cutting configuration is intentionally kept out of this class so that each
 * concern remains independently reviewable. Spring Batch is left to Spring Boot
 * auto-configuration (declaring {@code @EnableBatchProcessing} here would suppress it);
 * security, web, OpenAPI, distributed tracing, and the correlation-id servlet filter are
 * configured by their dedicated classes in the {@code config} and {@code observability}
 * packages. Batch jobs are not launched at startup because
 * {@code spring.batch.job.enabled} is {@code false} in the application configuration.
 *
 * <h2>Batch return-code propagation (JCL condition-code parity)</h2>
 *
 * <p>When the application runs as a batch process rather than the online web server &mdash;
 * that is, with {@code --spring.main.web-application-type=none} and a job selected via
 * {@code --spring.batch.job.name} &mdash; the mainframe {@code RETURN-CODE} of the executed
 * job must reach the operating system as the JVM process exit code so the CI/CD scheduler
 * (the JCL replacement) can gate on it exactly as the legacy {@code COND}/{@code MAXCC}
 * chains did (AAP &sect;0.4.4, &sect;0.8.1, &sect;0.9.6). {@link #main(String[])} therefore
 * calls {@link SpringApplication#exit(org.springframework.context.ConfigurableApplicationContext,
 * org.springframework.boot.ExitCodeGenerator...)} and forwards the result to
 * {@link System#exit(int)} &mdash; but <strong>only in batch mode</strong>. The
 * {@link BatchExitCodeGenerator} bean maps the job's {@link org.springframework.batch.core.ExitStatus}
 * to the {@code 0}/{@code 4}/{@code 8} contract. In web mode the started context is a
 * {@link WebServerApplicationContext}; the guard skips the exit call so the embedded server
 * keeps serving requests indefinitely.
 */
@SpringBootApplication
public class CardDemoApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(CardDemoApplication.class);

    /**
     * Boots the CardDemo Spring Boot application, initializing the Spring application
     * context, applying Flyway schema migrations, and starting the embedded web server.
     *
     * <p>In <strong>batch mode</strong> (a non-web context, selected with
     * {@code --spring.main.web-application-type=none}), after the launcher has run the
     * requested job this method propagates the batch return code to the operating system:
     * {@link SpringApplication#exit(org.springframework.context.ConfigurableApplicationContext,
     * org.springframework.boot.ExitCodeGenerator...)} closes the context and returns the code
     * resolved by {@link BatchExitCodeGenerator} ({@code 0}/{@code 4}/{@code 8}), which is
     * forwarded to {@link System#exit(int)}. In <strong>web mode</strong> the context is a
     * {@link WebServerApplicationContext}; the guard below skips the exit call so the embedded
     * server keeps running. This preserves the mainframe JCL condition-code contract for batch
     * without affecting the online application.
     *
     * <p><strong>Launch/startup failure.</strong> A batch launch that cannot even run its job
     * &mdash; missing or invalid job parameters, an already-complete job instance, or a restart
     * error &mdash; is reported by Spring Boot as an exception propagating out of
     * {@link SpringApplication#run(Class, String...)}, which would otherwise bypass the exit-code
     * propagation and let the JVM die with an uncaught-exception status. This method therefore
     * catches such a failure and exits with the abend return code
     * {@link BatchExitCodeGenerator#RC_FAILED} ({@code 8}), so the CI/CD scheduler still observes
     * a non-zero condition code (JCL {@code COND}/{@code MAXCC} parity). A <em>job</em> that
     * launches and then fails at run time does not throw here: the launcher returns a
     * {@link org.springframework.batch.core.BatchStatus#FAILED FAILED} execution, {@code run()}
     * returns normally, and {@link BatchExitCodeGenerator} maps it to {@code 8} through the exit
     * path below.
     *
     * @param args command-line arguments forwarded to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        final ConfigurableApplicationContext context;
        try {
            context = SpringApplication.run(CardDemoApplication.class, args);
        } catch (Exception ex) {
            // A launch/startup failure propagated out of run() (for example a
            // JobParametersInvalidException or JobInstanceAlreadyCompleteException wrapped by the
            // ApplicationRunner). Spring Boot has already reported it; surface it to the OS as the
            // mainframe abend return code 8 rather than an uncaught-exception exit, preserving the
            // JCL condition-code contract for the CI/CD scheduler.
            LOGGER.error("Application startup or batch launch failed; exiting with return code {}.",
                    BatchExitCodeGenerator.RC_FAILED, ex);
            System.exit(BatchExitCodeGenerator.RC_FAILED);
            return; // unreachable after System.exit; documents that context is unused on failure
        }
        if (!(context instanceof WebServerApplicationContext)) {
            // Batch/none mode: surface the Spring Batch RETURN-CODE (0/4/8) as the process
            // exit code so the CI/CD scheduler can gate on it (JCL COND/MAXCC parity).
            System.exit(SpringApplication.exit(context));
        }
        // Web mode: leave the embedded server running; do not close the context or exit.
    }
}
