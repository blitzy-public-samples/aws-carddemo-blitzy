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

import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Configuration;

/**
 * :purpose: Make every ``lb://`` route in the gateway's route table resolve its instances
 *     from the container platform's DNS, so that a downstream service scaled to several
 *     replicas is balanced across them and a replica that goes away stops being selected.
 * :output: Registers {@link UpstreamInstanceSupplierConfiguration} as the load-balancer
 *     client configuration for EVERY service id, so a new downstream route needs no addition
 *     here.
 * :note: Only the configuration is registered here; nothing is load balanced until a
 *     route's ``uri`` names the ``lb`` scheme. The workstation topology deliberately keeps
 *     plain ``http://localhost:<port>`` uris - one process per service on distinct ports has
 *     nothing to balance - so the ``lb`` scheme is used by the container profile, where
 *     ``docker compose up --scale`` can produce replicas.
 * :note: Without a registered client configuration, Spring Cloud LoadBalancer has no
 *     instance source at all outside a service registry: every supplier its own
 *     ``LoadBalancerClientConfiguration`` declares is conditional on a ``DiscoveryClient``
 *     bean, and this application has none. A ``lb://`` route would then fail every request
 *     with "no servers available".
 */
@Configuration(proxyBeanMethods = false)
@LoadBalancerClients(defaultConfiguration = UpstreamInstanceSupplierConfiguration.class)
public class UpstreamLoadBalancerConfig {
}
