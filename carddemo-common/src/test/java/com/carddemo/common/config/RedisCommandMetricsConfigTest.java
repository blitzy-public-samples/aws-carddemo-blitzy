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

import io.lettuce.core.metrics.CommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * :purpose: Guard the Redis command-latency instrumentation the Grafana dashboard
 *     template depends on. ``observability/grafana-dashboard.json`` queries
 *     ``lettuce_command_completion_seconds_count``,
 *     ``lettuce_command_completion_seconds_sum`` and
 *     ``lettuce_command_firstresponse_seconds_max``; those series exist only when
 *     a {@link MicrometerCommandLatencyRecorder} is installed on the Lettuce
 *     client resources, because Lettuce keeps command-latency recording disabled
 *     by default. The Micrometer meter names asserted here
 *     (``lettuce.command.completion`` / ``lettuce.command.firstresponse``) are the
 *     names Prometheus renders as those ``_seconds_*`` series.
 */
class RedisCommandMetricsConfigTest {

    /** :purpose: Micrometer name of the command-completion timer. */
    private static final String COMPLETION_METER = "lettuce.command.completion";

    /** :purpose: Micrometer name of the first-response timer. */
    private static final String FIRST_RESPONSE_METER = "lettuce.command.firstresponse";

    /** :purpose: Stand-in local socket for a recorded command. */
    private static final SocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 45678);

    /** :purpose: Stand-in Redis endpoint for a recorded command. */
    private static final SocketAddress REMOTE = new InetSocketAddress("redis", 6379);

    /**
     * :purpose: Build the customizer under test against a bean factory that holds
     *     the supplied meter registry (or nothing when ``registry`` is null).
     * :param registry: the meter registry to publish, or ``null`` for none.
     * :returns: the configured ``ClientResourcesBuilderCustomizer``.
     */
    private ClientResourcesBuilderCustomizer customizerFor(MeterRegistry registry) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        if (registry != null) {
            beanFactory.registerSingleton("meterRegistry", registry);
        }
        ObjectProvider<MeterRegistry> provider = beanFactory.getBeanProvider(MeterRegistry.class);
        return new RedisCommandMetricsConfig().lettuceCommandLatencyMetrics(provider);
    }

    @Test
    @DisplayName("The customizer installs an enabled Micrometer command-latency recorder")
    void customizerInstallsEnabledMicrometerRecorder() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ClientResources.Builder builder = mock(ClientResources.Builder.class);

        customizerFor(registry).customize(builder);

        ArgumentCaptor<CommandLatencyRecorder> captor = ArgumentCaptor.forClass(CommandLatencyRecorder.class);
        verify(builder).commandLatencyRecorder(captor.capture());
        CommandLatencyRecorder recorder = captor.getValue();

        assertThat(recorder).isInstanceOf(MicrometerCommandLatencyRecorder.class);
        assertThat(recorder.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("A recorded command publishes the two dashboard timers to the registry")
    void recordedCommandPublishesDashboardMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ClientResources.Builder builder = mock(ClientResources.Builder.class);
        customizerFor(registry).customize(builder);

        ArgumentCaptor<CommandLatencyRecorder> captor = ArgumentCaptor.forClass(CommandLatencyRecorder.class);
        verify(builder).commandLatencyRecorder(captor.capture());

        captor.getValue().recordCommandLatency(LOCAL, REMOTE, CommandType.HMSET,
                TimeUnit.MILLISECONDS.toNanos(1), TimeUnit.MILLISECONDS.toNanos(3));

        List<String> meterNames = registry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .toList();
        assertThat(meterNames).contains(COMPLETION_METER, FIRST_RESPONSE_METER);

        assertThat(registry.get(COMPLETION_METER).timer().count()).isEqualTo(1L);
        assertThat(registry.get(COMPLETION_METER).timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(3.0d);
        assertThat(registry.get(FIRST_RESPONSE_METER).timer().count()).isEqualTo(1L);
        assertThat(registry.get(FIRST_RESPONSE_METER).timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("The recorder is tagged with the command so per-command panels resolve")
    void recorderTagsTheCommandName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ClientResources.Builder builder = mock(ClientResources.Builder.class);
        customizerFor(registry).customize(builder);

        ArgumentCaptor<CommandLatencyRecorder> captor = ArgumentCaptor.forClass(CommandLatencyRecorder.class);
        verify(builder).commandLatencyRecorder(captor.capture());
        captor.getValue().recordCommandLatency(LOCAL, REMOTE, CommandType.HGETALL,
                TimeUnit.MILLISECONDS.toNanos(1), TimeUnit.MILLISECONDS.toNanos(2));

        assertThat(registry.get(COMPLETION_METER).timer().getId().getTag("command"))
                .isEqualTo(CommandType.HGETALL.name());
    }

    @Test
    @DisplayName("With no meter registry present the client-resources builder is left untouched")
    void noMeterRegistryLeavesBuilderUntouched() {
        ClientResources.Builder builder = mock(ClientResources.Builder.class);

        customizerFor(null).customize(builder);

        verify(builder, never()).commandLatencyRecorder(any());
    }
}
