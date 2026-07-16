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
package com.aws.carddemo.account.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fail-fast preflight for the container datasource configuration of the Account Management service
 * (Feature F-003).
 *
 * <p>When the {@code docker} profile is active, {@code application-docker.yml} binds the datasource
 * URL, username, and password strictly to environment variables with <em>no</em> default fallback
 * ({@code ${SPRING_DATASOURCE_URL}}, etc.). If one of those variables is missing at runtime, Spring
 * does not surface the problem until the {@code DataSource}/Hikari bean is created, at which point it
 * fails with an unresolved-placeholder error buried in a long {@code BeanCreationException} stack
 * trace &mdash; well after Flyway and JPA auto-configuration have begun. That is an operator-hostile
 * failure mode for a misconfigured container.</p>
 *
 * <p>This {@link EnvironmentPostProcessor} runs while the {@link ConfigurableEnvironment} is being
 * prepared, before the application context (and therefore before any datasource bean) is created. It
 * verifies that all required datasource environment variables are present and non-blank and, if any is
 * missing, throws immediately with a clear, actionable message that names <strong>only the missing
 * variable(s)</strong>. Consistent with the service's no-sensitive-data-in-logs contract
 * (AAP &sect;0.6.6), the diagnostic never echoes any variable's value &mdash; not the URL, not the
 * username, and certainly not the password.</p>
 *
 * <p>The check is scoped to the {@code docker} profile: under the default (local-dev) profile the base
 * {@code application.yml} supplies obviously-local fallbacks, and under the {@code test} profile the
 * datasource comes from Testcontainers, so in both cases this processor is a no-op.</p>
 *
 * <p><strong>Registration and ordering.</strong> Environment post-processors are discovered from
 * {@code META-INF/spring.factories} under the {@code org.springframework.boot.env.EnvironmentPostProcessor}
 * key (the {@code .imports} resource mechanism applies only to auto-configuration, not to this SPI).
 * This processor implements {@link Ordered} with {@link Ordered#LOWEST_PRECEDENCE} so that it executes
 * <em>after</em> Spring Boot's {@code ConfigDataEnvironmentPostProcessor} has activated profiles from
 * {@code SPRING_PROFILES_ACTIVE}/{@code spring.profiles.active} and loaded the profile-specific
 * configuration. Running last guarantees that {@link ConfigurableEnvironment#getActiveProfiles()} is
 * fully populated when {@link #isDockerProfileActive(ConfigurableEnvironment)} is evaluated.</p>
 */
public class DockerDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Profile under which the container datasource environment variables are mandatory. */
    static final String DOCKER_PROFILE = "docker";

    /**
     * Datasource environment variables that {@code application-docker.yml} requires with no fallback.
     * These are variable <em>names</em> only; their values are intentionally never read into any
     * diagnostic.
     */
    static final List<String> REQUIRED_VARIABLES = List.of(
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD");

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
                                       final SpringApplication application) {
        if (!isDockerProfileActive(environment)) {
            return;
        }

        final List<String> missing = new ArrayList<>();
        for (final String variable : REQUIRED_VARIABLES) {
            final String value = environment.getProperty(variable);
            if (value == null || value.isBlank()) {
                missing.add(variable);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot start account-service with the '" + DOCKER_PROFILE + "' profile: the "
                            + "following required datasource environment variable(s) are missing or "
                            + "blank: " + String.join(", ", missing) + ". Set each to the target "
                            + "database connection detail before starting the container (values are "
                            + "never logged). See application-docker.yml.");
        }
    }

    /**
     * Runs with {@link Ordered#LOWEST_PRECEDENCE} so this processor executes after Spring Boot's
     * {@code ConfigDataEnvironmentPostProcessor} (which activates profiles and loads profile-specific
     * configuration). Executing last guarantees that {@link ConfigurableEnvironment#getActiveProfiles()}
     * reflects {@code SPRING_PROFILES_ACTIVE}/{@code spring.profiles.active} by the time the docker
     * profile check runs.
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Determines whether the {@code docker} profile is active, tolerating the ordering of
     * environment post-processors by consulting both the resolved active profiles and the raw
     * {@code spring.profiles.active} property (which relaxed binding populates from the
     * {@code SPRING_PROFILES_ACTIVE} environment variable used by the module Dockerfile).
     *
     * @param environment the environment under preparation
     * @return {@code true} if the {@code docker} profile is active
     */
    private boolean isDockerProfileActive(final ConfigurableEnvironment environment) {
        for (final String profile : environment.getActiveProfiles()) {
            if (DOCKER_PROFILE.equalsIgnoreCase(profile)) {
                return true;
            }
        }
        final String raw = environment.getProperty("spring.profiles.active");
        if (raw != null) {
            for (final String profile : raw.split(",")) {
                if (DOCKER_PROFILE.equalsIgnoreCase(profile.trim())) {
                    return true;
                }
            }
        }
        return false;
    }
}
