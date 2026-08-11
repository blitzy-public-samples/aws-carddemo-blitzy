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
package com.carddemo.gateway.config;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * :purpose: Supply the per-service load-balancer child context with the instance source
 *     for that service. Spring Cloud LoadBalancer builds one child application context per
 *     load-balanced service id, and this class is the configuration applied to every one of
 *     them (see {@link UpstreamLoadBalancerConfig}).
 * :output: One {@link DnsServiceInstanceListSupplier} per load-balanced service id.
 * :note: This class is deliberately NOT annotated with ``@Configuration`` and carries no
 *     stereotype annotation, so component scanning never registers it in the gateway's own
 *     context - only the load-balancer factory instantiates it, once per child context. A
 *     ``@Configuration`` here (or a nested class inside one) would additionally register the
 *     bean below in the MAIN context, where the service-id property does not exist and the
 *     supplier would have no service to resolve.
 */
public class UpstreamInstanceSupplierConfiguration {

    /** :purpose: Property prefix for per-service overrides of the resolved authority. */
    private static final String UPSTREAM_PREFIX = "carddemo.gateway.upstream.";

    /**
     * :purpose: Interval between DNS resolutions, in milliseconds. Two seconds keeps a
     *     departed replica out of the pool promptly while letting a burst of requests share
     *     one lookup; a request arriving between refreshes pays nothing.
     */
    private static final String REFRESH_INTERVAL_PROPERTY = UPSTREAM_PREFIX + "dns-refresh-millis";

    /** :purpose: Default refresh interval in milliseconds. */
    private static final int DEFAULT_REFRESH_MILLIS = 2000;

    /**
     * :purpose: Default port every downstream service listens on. Both shipped topologies
     *     address a service on its INTERNAL port, and neither publishes a per-service host
     *     port (see the route table in ``application.yml``).
     */
    private static final int DEFAULT_PORT = 8080;

    /**
     * :purpose: Build the instance source for the service this child context serves.
     * :param environment: the child context's environment, which carries the service id
     *     under {@code LoadBalancerClientFactory.PROPERTY_NAME} as well as every property
     *     of the gateway's own environment.
     * :returns: the DNS-backed supplier for that service.
     */
    @Bean
    public ServiceInstanceListSupplier carddemoDnsServiceInstanceListSupplier(Environment environment) {
        String serviceId = LoadBalancerClientFactory.getName(environment);
        String host = environment.getProperty(UPSTREAM_PREFIX + serviceId + ".host", serviceId);
        int port = environment.getProperty(UPSTREAM_PREFIX + serviceId + ".port", Integer.class, DEFAULT_PORT);
        int refreshMillis = environment.getProperty(REFRESH_INTERVAL_PROPERTY, Integer.class,
                DEFAULT_REFRESH_MILLIS);
        return new DnsServiceInstanceListSupplier(serviceId, host, port, Duration.ofMillis(refreshMillis));
    }
}
