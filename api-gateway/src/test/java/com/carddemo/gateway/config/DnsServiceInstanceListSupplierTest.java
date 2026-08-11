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
package com.carddemo.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Verifies the DNS-backed load-balancer instance source that makes a scaled
 *     downstream service reachable on all of its replicas: that a resolvable name yields
 *     one instance per address on the configured port, that the address order is stable so
 *     the round-robin position advances over the same sequence between refreshes, that an
 *     unresolvable name yields no instance rather than a failure, and that
 *     {@link UpstreamInstanceSupplierConfiguration} reads the service id and the
 *     per-service overrides from the load-balancer child context's environment.
 * :note: ``localhost`` is the resolvable name under test - it is the one name guaranteed to
 *     resolve with no network - so these cases assert the supplier's contract without
 *     depending on a container platform's DNS. The multi-address and replica-departure
 *     behaviour is proven at runtime against the real Compose DNS.
 */
@DisplayName("DnsServiceInstanceListSupplier")
class DnsServiceInstanceListSupplierTest {

    /** :purpose: The load-balancer service id used across these cases. */
    private static final String SERVICE_ID = "account-service";

    /** :purpose: The internal port every downstream service listens on. */
    private static final int PORT = 8080;

    /**
     * :purpose: Resolve the instances the supplier currently publishes.
     * :param supplier: the supplier under test.
     * :returns: the instance list carried by the supplier's flux.
     */
    private List<ServiceInstance> instancesOf(DnsServiceInstanceListSupplier supplier) {
        return supplier.get().blockFirst();
    }

    /**
     * :purpose: Confirm a resolvable name yields an instance per address, each carrying the
     *     service id, the configured port and the plain-HTTP scheme the internal hop uses.
     */
    @Test
    @DisplayName("a resolvable name yields instances on the configured port over plain HTTP")
    void resolvableNameYieldsInstancesOnConfiguredPort() {
        DnsServiceInstanceListSupplier supplier = new DnsServiceInstanceListSupplier(
                SERVICE_ID, "localhost", PORT, Duration.ofSeconds(2));

        List<ServiceInstance> instances = instancesOf(supplier);

        assertThat(instances).isNotEmpty();
        assertThat(instances).allSatisfy(instance -> {
            assertThat(instance.getServiceId()).isEqualTo(SERVICE_ID);
            assertThat(instance.getPort()).isEqualTo(PORT);
            assertThat(instance.isSecure()).isFalse();
            assertThat(instance.getUri().toString()).isEqualTo("http://" + instance.getHost() + ":" + PORT);
            assertThat(instance.getInstanceId()).isEqualTo(SERVICE_ID + "@" + instance.getHost() + ":" + PORT);
        });
        assertThat(supplier.getServiceId()).isEqualTo(SERVICE_ID);
    }

    /**
     * :purpose: Confirm the address order is stable across resolutions, so the round-robin
     *     position the load balancer keeps advances over the same sequence rather than
     *     jumping when DNS rotates its answer.
     */
    @Test
    @DisplayName("the address order is stable across resolutions")
    void addressOrderIsStableAcrossResolutions() {
        DnsServiceInstanceListSupplier supplier = new DnsServiceInstanceListSupplier(
                SERVICE_ID, "localhost", PORT, Duration.ZERO);

        List<String> first = instancesOf(supplier).stream().map(ServiceInstance::getHost).toList();
        List<String> second = instancesOf(supplier).stream().map(ServiceInstance::getHost).toList();

        assertThat(second).containsExactlyElementsOf(first);
        assertThat(first).isSorted();
    }

    /**
     * :purpose: Confirm a repeated call inside the refresh interval is served from the cached
     *     resolution, which is what keeps a burst of requests to one lookup.
     */
    @Test
    @DisplayName("a call inside the refresh interval reuses the cached instance list")
    void callInsideRefreshIntervalReusesCachedList() {
        DnsServiceInstanceListSupplier supplier = new DnsServiceInstanceListSupplier(
                SERVICE_ID, "localhost", PORT, Duration.ofMinutes(5));

        List<ServiceInstance> first = instancesOf(supplier);
        List<ServiceInstance> second = instancesOf(supplier);

        assertThat(second).isSameAs(first);
    }

    /**
     * :purpose: Confirm a name that does not resolve yields no instance instead of raising,
     *     so an unreachable downstream fails as a routed 503 rather than as a start-up or
     *     request-thread error.
     */
    @Test
    @DisplayName("an unresolvable name yields no instance rather than raising")
    void unresolvableNameYieldsNoInstance() {
        DnsServiceInstanceListSupplier supplier = new DnsServiceInstanceListSupplier(
                SERVICE_ID, "carddemo-no-such-host.invalid", PORT, Duration.ofSeconds(2));

        assertThat(instancesOf(supplier)).isEmpty();
    }

    /**
     * :purpose: Confirm the child-context configuration takes the service id from the
     *     load-balancer property the factory sets, and resolves that name by default.
     */
    @Test
    @DisplayName("the child-context configuration resolves the service id as the DNS name")
    void childContextConfigurationUsesServiceIdAsDnsName() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(LoadBalancerClientFactory.PROPERTY_NAME, SERVICE_ID);

        ServiceInstanceListSupplier supplier =
                new UpstreamInstanceSupplierConfiguration().carddemoDnsServiceInstanceListSupplier(environment);

        assertThat(supplier).isInstanceOf(DnsServiceInstanceListSupplier.class);
        assertThat(supplier.getServiceId()).isEqualTo(SERVICE_ID);
    }

    /**
     * :purpose: Confirm a per-service host and port override is honoured, which is how a
     *     deployment points one route at an authority that is not the service's own name.
     */
    @Test
    @DisplayName("per-service host and port overrides are honoured")
    void perServiceHostAndPortOverridesAreHonoured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(LoadBalancerClientFactory.PROPERTY_NAME, SERVICE_ID);
        environment.setProperty("carddemo.gateway.upstream." + SERVICE_ID + ".host", "localhost");
        environment.setProperty("carddemo.gateway.upstream." + SERVICE_ID + ".port", "9443");

        ServiceInstanceListSupplier supplier =
                new UpstreamInstanceSupplierConfiguration().carddemoDnsServiceInstanceListSupplier(environment);

        assertThat(supplier.get().blockFirst())
                .isNotEmpty()
                .allSatisfy(instance -> assertThat(instance.getPort()).isEqualTo(9443));
    }
}
