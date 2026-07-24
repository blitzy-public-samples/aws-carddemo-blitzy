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
package com.carddemo.billpay;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.config.ObservabilityConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Executable entry point for the CardDemo ``billpay-service`` microservice.
 *
 * :purpose: Bootstraps the Spring Boot application context for the online
 *     bill-payment operation (legacy CICS ``COBIL00C``, transaction ``CB00``),
 *     wiring the shared ``carddemo-common`` entities, observability, and
 *     exception handling that live outside this service's component-scan root.
 * :output: A running stateless REST service exposing the bill-payment endpoint.
 */
@SpringBootApplication
@EntityScan("com.carddemo.common.domain")
@EnableJpaRepositories("com.carddemo.billpay.repository")
@Import({ ObservabilityConfig.class, GlobalExceptionHandler.class })
public class BillPaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillPaymentServiceApplication.class, args);
    }
}
