/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.carddemo.common.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.SessionRepository;

import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

/**
 * Spring Session Redis serialization configuration.
 *
 * :purpose: Replace Spring Session's default JDK (native Java) serialization of
 *     the pseudo-conversational ``SessionContext`` in Redis with a strict,
 *     allowlisted JSON serializer. Native Java serialization exposes a
 *     deserialization gadget attack surface (CWE-502); JSON with an allowlisted
 *     polymorphic-type validator removes it while preserving the ability to
 *     round-trip the session attribute types the application actually uses.
 * :output: A ``springSessionDefaultRedisSerializer`` bean — the exact bean name
 *     Spring Session's Redis configuration consumes as its default serializer — plus
 *     the {@link RemovedSessionTolerantSessionRepository} wrapping that keeps a
 *     session rotated or revoked by a PEER service from failing the request that was
 *     holding it.
 * :notes: Following the ``ObservabilityConfig`` convention, this class is NOT
 *     listed in a ``META-INF`` auto-configuration import file; a service
 *     activates it with ``@Import(SessionRedisConfig.class)`` or by broadening
 *     component scanning to ``com.carddemo.common``. Every session-carrying service
 *     (the eight web modules; the non-web batch service has no session) imports it
 *     from its application class — omitting the import silently falls back to native
 *     Java serialization of the session attributes, reopening the gadget surface this
 *     class exists to close, so the import is mandatory. The ``@ConditionalOnClass``
 *     guard keeps the class inert (never loaded) in modules that do not have
 *     Spring Data Redis and Spring Session Redis on the classpath, such as the
 *     non-web batch service. Design rationale lives in docs/decision-log.md.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {
    "org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer",
    "org.springframework.session.data.redis.config.annotation.web.http.RedisHttpSessionConfiguration"
})
public class SessionRedisConfig {

    /**
     * :purpose: Build the allowlisted JSON serializer Spring Session uses to
     *     store session state in Redis, replacing the insecure JDK serializer.
     * :output: A ``RedisSerializer`` that writes JSON with polymorphic type
     *     information restricted to the CardDemo session-DTO package.
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        // Allowlist ONE package: the CardDemo session DTOs. Exactly two attribute values
        // are ever written to a session -- the SessionContext
        // (com.carddemo.common.dto.SessionContext, the only polymorphically typed value in
        // the payload) and the revoked-reason String -- and Spring Session writes the
        // session metadata (creationTime, lastAccessedTime, maxInactiveInterval) as bare
        // numbers. Every one of those, String included, is a FINAL type, which default
        // typing writes without a type id and therefore never resolves through this
        // validator. Nothing else needs to be admitted, so the JDK families this list used
        // to carry (java.lang., java.util., java.time.) are deliberately gone: they widened
        // the deserialization surface (CWE-502) that this validator exists to close without
        // admitting a single type the session actually stores. A new session attribute of a
        // non-final type must be added here explicitly, and fails loudly until it is.
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.carddemo.common.dto.")
            .build();

        return GenericJacksonJsonRedisSerializer.builder()
            .enableDefaultTyping(typeValidator)
            .enableSpringCacheNullValueSupport()
            .build();
    }

    /**
     * :purpose: Wrap the auto-configured Spring Session repository in
     *     {@link RemovedSessionTolerantSessionRepository}, so a request whose session was
     *     removed from the shared store by a PEER service (sign-on rotation, sign-out, or
     *     an administrator revoking sessions) completes with the response it already
     *     produced instead of failing on the write-back.
     * :note: Spring Session writes a session back when the response commits for EVERY hop
     *     that touched it, which is how ``lastAccessedTime`` is refreshed. Sign-on rotates
     *     the session id in ``auth-service`` — the session-fixation protection — and that
     *     rotation DELETES the old store entry. The gateway hop, which had read the
     *     caller's session while serving the same request, then tried to write the entry
     *     that no longer existed, and the refusal surfaced while the proxied response body
     *     was being written — outside any handler that could recover it — so a sign-on that
     *     had ALREADY SUCCEEDED was returned to the browser as HTTP 500 for every caller
     *     who signed on while holding a session.
     * :output: A {@link BeanPostProcessor} that returns the wrapping repository in place
     *     of the underlying one.
     * :note: A post-processor rather than a replacement ``@Bean``: Spring Session's Redis
     *     session type is package-private, so the repository interface cannot be
     *     re-declared with the same type argument, and re-declaring the bean would collide
     *     with the auto-configured definition. Declared ``static`` so the container can
     *     create it before the beans it post-processes.
     */
    @Bean
    static BeanPostProcessor removedSessionTolerantSessionRepository() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SessionRepository<?> repository
                        && !(bean instanceof RemovedSessionTolerantSessionRepository)) {
                    return RemovedSessionTolerantSessionRepository.wrap(repository);
                }
                return bean;
            }
        };
    }
}
