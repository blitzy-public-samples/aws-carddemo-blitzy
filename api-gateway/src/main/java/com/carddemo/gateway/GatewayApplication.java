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
package com.carddemo.gateway;

import com.carddemo.common.config.CardDemoErrorController;
import com.carddemo.common.config.ContainerErrorReportConfig;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.config.ObservabilityConfig;
import com.carddemo.common.config.SecurityExceptionHandler;
import com.carddemo.common.config.SessionRedisConfig;
import com.carddemo.common.config.WebHardeningConfig;
import com.carddemo.common.config.WebObservabilityConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;



/**
 * :purpose: Executable entry point for the CardDemo API Gateway, a Spring Cloud
 *     Gateway Server WebMVC (servlet) edge router that also serves the
 *     menu-navigation endpoints re-platforming legacy CICS transactions
 *     CM00 (COMEN01C) and CA00 (COADM01C).
 * :note: Explicitly imports the shared carddemo-common configurations it needs rather
 *     than broadening the component scan into that library, which ships no
 *     auto-configuration imports file: ObservabilityConfig, WebObservabilityConfig (the
 *     shared correlation-id filter and the datastore-outage filter), GlobalExceptionHandler,
 *     and -- as every other service already does -- CardDemoErrorController with
 *     ContainerErrorReportConfig. Those last two matter most here, because the gateway is
 *     the only member of the estate a browser talks to: without them a container-level
 *     error dispatch at the edge fell through to Boot's BasicErrorController and answered
 *     an abbreviated {timestamp,status,error,path} document, so one request could be
 *     refused in two different shapes depending on which component refused it.
 * :note: SecurityExceptionHandler covers an authorization failure raised inside the
 *     application rather than by the filter chain.
 */
@SpringBootApplication
@Import({
        ObservabilityConfig.class,
        WebObservabilityConfig.class,
        WebHardeningConfig.class,
        GlobalExceptionHandler.class,
        CardDemoErrorController.class,
        ContainerErrorReportConfig.class,
        SecurityExceptionHandler.class,
        SessionRedisConfig.class
})
public class GatewayApplication {

    /**
     * :param args: standard JVM command-line arguments.
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
