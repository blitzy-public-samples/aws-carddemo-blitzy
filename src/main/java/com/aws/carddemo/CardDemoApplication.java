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
 * Bootstrap entry point for the modernized AWS CardDemo application.
 *
 * <p>This class replaces the legacy z/OS CICS/JCL runtime that previously hosted the COBOL
 * credit-card management system. Annotated with {@link SpringBootApplication}, it serves as the
 * component-scan root for the {@code com.aws.carddemo} package tree, allowing Spring Boot to
 * auto-assemble every layer of the migrated application:
 *
 * <ul>
 *   <li><b>web</b> — online controllers migrated from the CICS transaction programs;
 *   <li><b>batch</b> — Spring Batch jobs migrated from the JCL job streams;
 *   <li><b>persistence</b> — Spring Data JPA repositories and entities migrated from the VSAM files
 *       and copybook record layouts;
 *   <li><b>security</b> — Spring Security configuration replacing the RACF / clear-text credential
 *       model.
 * </ul>
 *
 * <p>All cross-cutting concerns (data source, Spring Batch metadata, security, JSON serialization,
 * and the credential seeder) are auto-configured by Spring Boot or declared under {@code
 * com.aws.carddemo.config}. This class intentionally carries no business logic and no explicit
 * enable-annotations so that Boot's default auto-configuration — notably Spring Batch's {@code
 * DefaultBatchConfiguration} — remains active and the JobRepository / JobLauncher are wired
 * automatically.
 */
@SpringBootApplication
public class CardDemoApplication {

  /**
   * Launches the Spring application context for AWS CardDemo.
   *
   * @param args command-line arguments forwarded to {@link SpringApplication#run(Class, String...)}
   */
  public static void main(String[] args) {
    SpringApplication.run(CardDemoApplication.class, args);
  }
}
