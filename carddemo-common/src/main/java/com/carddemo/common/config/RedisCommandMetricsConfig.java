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
package com.carddemo.common.config;

import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * :purpose: Bind Lettuce's Redis command-latency instrumentation to the
 *           Micrometer registry so the session store (the Spring Session Redis
 *           replacement for the CICS COMMAREA) is measurable at
 *           ``/actuator/prometheus``. Lettuce keeps its command-latency
 *           recorder disabled unless a recorder is supplied through
 *           ``ClientResources``; with the recorder installed each service
 *           publishes the ``lettuce.command.completion`` and
 *           ``lettuce.command.firstresponse`` timers, which Prometheus exposes
 *           as ``lettuce_command_completion_seconds_{count,sum,max}`` and
 *           ``lettuce_command_firstresponse_seconds_{count,sum,max}`` — the
 *           Redis series the Grafana dashboard template in
 *           ``observability/grafana-dashboard.json`` queries.
 * :output: A ``ClientResourcesBuilderCustomizer`` bean that Spring Boot's
 *          Lettuce connection configuration applies to the
 *          ``DefaultClientResources`` builder backing the shared
 *          ``LettuceConnectionFactory``.
 * :note: Following the ``ObservabilityConfig`` and ``SessionRedisConfig``
 *        convention, this class ships no ``META-INF`` auto-configuration import
 *        entry; a service activates it with
 *        ``@Import(RedisCommandMetricsConfig.class)`` or by broadening component
 *        scanning to ``com.carddemo.common``. The ``@ConditionalOnClass`` guard
 *        keeps the class inert in modules without Lettuce and Spring Boot's
 *        Redis auto-configuration on the classpath, such as the non-web batch
 *        service. Design rationale lives in docs/decision-log.md.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {
    "io.lettuce.core.metrics.MicrometerCommandLatencyRecorder",
    "org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer"
})
public class RedisCommandMetricsConfig {

    /**
     * :purpose: Install a {@link MicrometerCommandLatencyRecorder} on the Lettuce
     *           client resources so every Redis command records its completion
     *           and first-response latency against the application's meter
     *           registry.
     * :param meterRegistryProvider: provider for the application's
     *           {@link MeterRegistry}; resolved when the customizer runs so no
     *           bean-ordering constraint is imposed on the registry.
     * :returns: a customizer that sets the Micrometer command-latency recorder,
     *           and leaves the builder untouched when no meter registry is
     *           present (for example in a web slice test that excludes metrics
     *           auto-configuration).
     */
    @Bean
    ClientResourcesBuilderCustomizer lettuceCommandLatencyMetrics(
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return builder -> {
            MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
            if (meterRegistry == null) {
                return;
            }
            // MicrometerOptions.create() enables recording with the library
            // defaults: no latency histogram (count/sum/max only) and no
            // local-socket distinction, keeping the emitted tag set to the
            // command name and the remote endpoint.
            builder.commandLatencyRecorder(
                    new MicrometerCommandLatencyRecorder(meterRegistry, MicrometerOptions.create()));
        };
    }
}
