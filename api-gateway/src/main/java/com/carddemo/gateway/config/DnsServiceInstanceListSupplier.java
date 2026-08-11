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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;

import reactor.core.publisher.Flux;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * :purpose: Supply the instances of one downstream service to Spring Cloud LoadBalancer by
 *     resolving the service's DNS name, so a service scaled to several replicas is load balanced
 *     per request instead of pinned to whichever replica the gateway resolved first.
 * :output: One {@link ServiceInstance} per address currently published for the service's DNS
 *     name, in a stable order, refreshed no more often than the configured interval.
 * :note: Spring Cloud LoadBalancer ships instance suppliers only for a service registry
 *     (Eureka, Consul, Kubernetes). Both topologies this application ships have a registry
 *     already, in the form of the container platform's own DNS: Docker's embedded DNS publishes
 *     one A record per running container of a Compose service, and a Kubernetes Service publishes
 *     its cluster IP. So DNS is the instance source here, and it is authoritative in a second
 *     sense as well - it is also the LIVENESS source. Docker removes a stopped container's address
 *     from the record set immediately, so a replica that goes away stops being selected within one
 *     refresh interval without any health check of our own.
 * :note: In Kubernetes a ClusterIP Service resolves to exactly ONE address, so this supplier
 *     returns a single instance and kube-proxy keeps doing the balancing across pods. That is
 *     intended: the supplier is correct in both topologies and special-cases neither.
 * :note: Resolution happens on the calling thread, bounded by the refresh interval and by the
 *     JVM's own address cache (``networkaddress.cache.ttl``, which the gateway image lowers from
 *     its 30 s default - a longer JVM cache would silently override a shorter interval here).
 */
public class DnsServiceInstanceListSupplier implements ServiceInstanceListSupplier {

    /** :purpose: Reports address-set changes and resolution failures. */
    private static final Logger log = LoggerFactory.getLogger(DnsServiceInstanceListSupplier.class);

    /** :purpose: The load-balancer service id these instances belong to. */
    private final String serviceId;

    /** :purpose: The DNS name resolved for the instance addresses. */
    private final String host;

    /** :purpose: The port every instance of this service listens on. */
    private final int port;

    /** :purpose: Minimum interval between two resolutions, in nanoseconds. */
    private final long refreshIntervalNanos;

    /**
     * :purpose: The last successful resolution, or ``null`` before the first one. Written
     *     as a whole so a reader never sees a half-updated address set.
     */
    private volatile Resolution resolution;

    /**
     * :purpose: One resolution of the service's DNS name.
     * :param resolvedAtNanos: {@link System#nanoTime()} reading when it was taken.
     * :param instances: the instances it produced.
     */
    private record Resolution(long resolvedAtNanos, List<ServiceInstance> instances) {
    }

    /**
     * :purpose: Construct the supplier for one service.
     * :param serviceId: the load-balancer service id (the authority of the ``lb://`` route
     *     uri).
     * :param host: the DNS name to resolve; the service id itself under Docker Compose and
     *     Kubernetes, where a service is reachable under its own name.
     * :param port: the port every instance listens on.
     * :param refreshInterval: minimum interval between resolutions. Short enough that a
     *     departed replica stops being selected promptly, long enough that a burst of
     *     requests shares one lookup.
     */
    public DnsServiceInstanceListSupplier(String serviceId, String host, int port, Duration refreshInterval) {
        this.serviceId = serviceId;
        this.host = host;
        this.port = port;
        this.refreshIntervalNanos = Math.max(Duration.ofMillis(100).toNanos(), refreshInterval.toNanos());
    }

    /**
     * :purpose: Identify the service these instances belong to.
     * :returns: the load-balancer service id.
     */
    @Override
    public String getServiceId() {
        return serviceId;
    }

    /**
     * :purpose: Supply the current instances to the load balancer.
     * :returns: a single-element flux carrying the current instance list, which is what a
     *     poll-based supplier emits; the load balancer takes the first element per choice.
     */
    @Override
    public Flux<List<ServiceInstance>> get() {
        return Flux.just(currentInstances());
    }

    /**
     * :purpose: Return the cached instance list, resolving again once the refresh interval
     *     has elapsed.
     * :returns: the instances currently published for the service, possibly empty when the
     *     very first resolution failed.
     */
    private List<ServiceInstance> currentInstances() {
        Resolution current = this.resolution;
        if (current != null && System.nanoTime() - current.resolvedAtNanos() < refreshIntervalNanos) {
            return current.instances();
        }
        return refresh();
    }

    /**
     * :purpose: Resolve the DNS name once on behalf of every thread that found the cached
     *     resolution stale or absent.
     * :returns: the current instances.
     * :note: Synchronized, and the staleness test is repeated inside the lock, because the
     *     first request burst after start-up arrives on many threads at once and every one
     *     of them finds no resolution: without this, each performed its own lookup and each
     *     reported the address set it found, which put ten identical "Upstream ... instances"
     *     lines in the start-up log. Contention is bounded by the refresh interval - a
     *     thread that waits here waits for a lookup the JVM's own address cache usually
     *     answers - so at most one resolution per interval is performed for the service.
     */
    private synchronized List<ServiceInstance> refresh() {
        long now = System.nanoTime();
        Resolution current = this.resolution;
        if (current != null && now - current.resolvedAtNanos() < refreshIntervalNanos) {
            return current.instances();
        }
        return resolve(now, current);
    }

    /**
     * :purpose: Resolve the DNS name and replace the cached instance list.
     * :param now: the {@link System#nanoTime()} reading to record with the resolution.
     * :param previous: the resolution being replaced, or ``null`` if this is the first.
     */
    private List<ServiceInstance> resolve(long now, Resolution previous) {
        try {
            List<ServiceInstance> instances = toInstances(InetAddress.getAllByName(host));
            Resolution resolved = new Resolution(now, instances);
            this.resolution = resolved;
            if (previous == null || !addresses(previous.instances()).equals(addresses(instances))) {
                log.info("Upstream {} instances: {}", serviceId, addresses(instances));
            }
            return instances;
        } catch (UnknownHostException resolutionFailure) {
            if (previous != null) {
                log.warn("Cannot resolve upstream {} at {}; keeping the {} address(es) last known good",
                        serviceId, host, previous.instances().size(), resolutionFailure);
                this.resolution = new Resolution(now, previous.instances());
                return previous.instances();
            }
            log.warn("Cannot resolve upstream {} at {}; no instance is available", serviceId, host,
                    resolutionFailure);
            return List.of();
        }
    }

    /**
     * :purpose: Convert resolved addresses into instances in a stable order.
     * :param resolved: the addresses DNS returned, in whatever order it rotated them into.
     * :returns: one instance per address, ordered by address so that the round-robin
     *     position the load balancer keeps advances over the same sequence between
     *     refreshes instead of jumping when DNS rotates its answer.
     */
    private List<ServiceInstance> toInstances(InetAddress[] resolved) {
        List<ServiceInstance> instances = new ArrayList<>(resolved.length);
        Arrays.stream(resolved)
                .map(InetAddress::getHostAddress)
                .sorted(Comparator.naturalOrder())
                .forEach(address -> instances.add(new DefaultServiceInstance(
                        serviceId + "@" + address + ":" + port, serviceId, address, port, false)));
        return List.copyOf(instances);
    }

    /**
     * :purpose: Render an instance list as its addresses, for change detection and logging.
     * :param instances: the instances to render.
     * :returns: the host addresses in list order.
     */
    private static List<String> addresses(List<ServiceInstance> instances) {
        return instances.stream().map(ServiceInstance::getHost).toList();
    }
}
