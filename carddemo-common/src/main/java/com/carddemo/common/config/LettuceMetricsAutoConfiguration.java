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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * :purpose: Publish Redis (Lettuce) command metrics to the service's Micrometer
 *  registry, so the Redis row of the CardDemo Grafana dashboard has data. Spring
 *  Boot wires a Lettuce ``ClientResources`` with Lettuce's default *no-op*
 *  command-latency recorder, so ``lettuce_command_completion_seconds_*`` and
 *  ``lettuce_command_firstresponse_seconds_*`` were never produced and the three
 *  Redis panels could only ever read "No data" (QA Issue 14). This
 *  auto-configuration installs Lettuce's Micrometer recorder on that same
 *  ``ClientResources`` instead of replacing the bean, so every other Boot-managed
 *  Lettuce setting is preserved.
 * :output: One {@link ClientResourcesBuilderCustomizer} bean that attaches a
 *  {@link MicrometerCommandLatencyRecorder} to the Lettuce client resources. The
 *  recorder emits two timers per (command, remote endpoint) pair -
 *  ``lettuce.command.completion`` and ``lettuce.command.firstresponse`` - which
 *  Micrometer exports as the ``_count``, ``_sum`` and ``_max`` series the
 *  dashboard queries.
 * :note: Applies only where Lettuce and Boot's Redis auto-configuration are both
 *  on the classpath, so ``batch-service`` (which has no Redis dependency at all)
 *  is unaffected. The {@link MeterRegistry} is resolved lazily through an
 *  {@link ObjectProvider} inside the customizer: the customizer runs when Boot
 *  builds the client resources, which is strictly after the registry bean is
 *  available, and resolving it lazily avoids forcing an auto-configuration
 *  ordering constraint on the metrics infrastructure. Latency percentiles and
 *  histogram buckets are deliberately left off ({@link MicrometerOptions#create()}
 *  defaults) so the added cardinality stays negligible.
 */
@AutoConfiguration
@ConditionalOnClass({MicrometerCommandLatencyRecorder.class, ClientResourcesBuilderCustomizer.class})
public class LettuceMetricsAutoConfiguration {

    /** :purpose: Reports whether the recorder was attached, once per service start. */
    private static final Logger log = LoggerFactory.getLogger(LettuceMetricsAutoConfiguration.class);

    /**
     * :purpose: Attach Lettuce's Micrometer command-latency recorder to the Lettuce
     *  client resources Spring Boot builds.
     * :param meterRegistries: provider for the application's meter registry;
     *  resolved when the customizer runs rather than at bean-definition time.
     * :returns: the customizer that installs the recorder.
     */
    @Bean
    public ClientResourcesBuilderCustomizer carddemoLettuceCommandLatencyRecorder(
            ObjectProvider<MeterRegistry> meterRegistries) {
        return builder -> {
            MeterRegistry meterRegistry = meterRegistries.getIfAvailable();
            if (meterRegistry == null) {
                // No metrics infrastructure in this service (for example a slice test
                // context): leave Lettuce's default recorder in place rather than fail.
                log.debug("No MeterRegistry available; Lettuce command metrics are not published");
                return;
            }
            builder.commandLatencyRecorder(
                    new MicrometerCommandLatencyRecorder(meterRegistry, MicrometerOptions.create()));
            log.debug("Lettuce command metrics published to {}", meterRegistry.getClass().getSimpleName());
        };
    }
}
