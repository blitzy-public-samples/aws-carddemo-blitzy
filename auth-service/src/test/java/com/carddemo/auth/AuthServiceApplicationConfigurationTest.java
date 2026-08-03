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
package com.carddemo.auth;

import com.carddemo.common.config.ObservabilityConfig;
import com.carddemo.common.config.RedisCommandMetricsConfig;
import com.carddemo.common.config.WebObservabilityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Guard the observability wiring of the sign-on service. The service
 *     declares no correlation filter of its own, so the shared
 *     {@link WebObservabilityConfig} registration is the only thing that puts a
 *     correlation id in the MDC and in the ``traceId`` of an error envelope; and
 *     {@link RedisCommandMetricsConfig} is the only thing that publishes the
 *     Redis command-latency series the Grafana dashboard queries. Dropping either
 *     import silently returns the service to emitting no correlation id and no
 *     Redis metrics, which is not detectable from a unit test of any other class.
 */
class AuthServiceApplicationConfigurationTest {

    @Test
    @DisplayName("The application imports the shared observability and Redis-metrics configuration")
    void applicationImportsSharedObservabilityConfiguration() {
        Import imports = AuthServiceApplication.class.getAnnotation(Import.class);

        assertThat(imports).isNotNull();
        assertThat(imports.value()).contains(
                ObservabilityConfig.class,
                WebObservabilityConfig.class,
                RedisCommandMetricsConfig.class);
    }
}
