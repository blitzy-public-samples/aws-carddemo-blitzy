/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.carddemo.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

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
 *     Spring Session's Redis configuration consumes as its default serializer.
 * :notes: Following the ``ObservabilityConfig`` convention, this class is NOT
 *     listed in a ``META-INF`` auto-configuration import file; a service
 *     activates it with ``@Import(SessionRedisConfig.class)`` or by broadening
 *     component scanning to ``com.carddemo.common``. The ``@ConditionalOnClass``
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
     *     information restricted to the CardDemo and core JDK type families.
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        // Allowlist the only type families that legitimately appear in the
        // session (the CardDemo SessionContext and the JDK value types it and
        // Spring Session use). Any other type is rejected during deserialization,
        // closing the native-deserialization gadget surface (CWE-502).
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.carddemo.")
            .allowIfSubType("java.util.")
            .allowIfSubType("java.time.")
            .allowIfSubType("java.lang.")
            .build();

        return GenericJacksonJsonRedisSerializer.builder()
            .enableDefaultTyping(typeValidator)
            .enableSpringCacheNullValueSupport()
            .build();
    }
}
