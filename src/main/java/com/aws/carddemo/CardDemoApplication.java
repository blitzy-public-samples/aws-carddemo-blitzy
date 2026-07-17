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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
 */
@SpringBootApplication
public class CardDemoApplication {

    /**
     * Boots the CardDemo Spring Boot application, initializing the Spring application
     * context, applying Flyway schema migrations, and starting the embedded web server.
     *
     * @param args command-line arguments forwarded to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
