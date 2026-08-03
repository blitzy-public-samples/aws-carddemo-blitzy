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
package com.carddemo.common.security;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * :purpose: Publish the {@link SessionPrincipalIndex} used by sign-on (to record the
 *     session it creates) and by administrator user maintenance (to revoke every live
 *     session of a user whose role changed or who was deleted).
 * :output: A single {@link SessionPrincipalIndex} bean bound to the configured Spring
 *     Session Redis namespace and session timeout.
 * :note: Activated with ``@Import(SessionRegistryConfig.class)`` by the services that
 *     participate in session revocation (``auth-service`` and ``user-service``).
 * :note: Both collaborators are resolved through {@link ObjectProvider}: in a full
 *     application context they are the auto-configured Redis beans, while a web-layer
 *     slice test that configures neither still starts and receives an index whose
 *     operations are inert. That keeps the security configuration importable from a
 *     slice test without forcing a Redis dependency into it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.session.SessionRepository")
public class SessionRegistryConfig {

    /**
     * :purpose: Build the principal-to-session index.
     * :param redisTemplateProvider: provider for the auto-configured string template.
     * :param sessionRepositoryProvider: provider for the auto-configured Spring Session
     *     repository used to delete revoked sessions with the correct namespace and
     *     serializer.
     * :param namespace: configured Spring Session Redis namespace.
     * :param sessionTimeout: configured session timeout, applied as the index TTL.
     * :returns: the shared {@link SessionPrincipalIndex}.
     */
    @Bean
    SessionPrincipalIndex sessionPrincipalIndex(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectProvider<SessionRepository<? extends Session>> sessionRepositoryProvider,
            @Value("${spring.session.data.redis.namespace:carddemo:session}") String namespace,
            @Value("${spring.session.timeout:30m}") Duration sessionTimeout) {
        return new SessionPrincipalIndex(
                redisTemplateProvider.getIfAvailable(),
                sessionRepositoryProvider.getIfAvailable(),
                namespace,
                sessionTimeout);
    }
}
