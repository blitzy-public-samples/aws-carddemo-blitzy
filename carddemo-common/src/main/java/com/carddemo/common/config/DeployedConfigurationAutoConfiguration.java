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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * :purpose: Refuse to start a DEPLOYED CardDemo service on packaged developer defaults,
 *     and make an unrecognised active profile visible instead of silent.
 * :output: Either a started context whose critical connection settings genuinely came from
 *     the environment, or an {@link IllegalStateException} naming exactly which settings are
 *     missing; plus a WARN for every active profile outside the supported set.
 * :note: Two defects motivate this. A service launched with
 *     ``SPRING_PROFILES_ACTIVE=docker`` but WITHOUT ``SPRING_DATASOURCE_URL`` started happily
 *     against the in-file ``jdbc:postgresql://postgres:5432/carddemo`` default and reported
 *     healthy, hiding a broken deployment; and a service launched with an unknown profile
 *     started with no warning at all while its configuration and its logging format silently
 *     changed. Validating here covers docker, k8s and prod with ONE implementation instead of
 *     duplicating a profile document per service.
 * :note: The datasource requirement applies only to services that declare a
 *     ``DataSource``; the api-gateway is therefore exempt. "Supplied by the environment" means
 *     the property is contributed by a property source OTHER than the packaged
 *     ``application.yml`` — an environment variable, a system property, a command-line
 *     argument or an external config file all satisfy it.
 * :note: Implemented as a ``BeanFactoryPostProcessor`` so it runs before ANY singleton is
 *     created. Ordinary beans are created after the datasource and Flyway beans, so a missing
 *     ``SPRING_DATASOURCE_URL`` was reported by HikariCP as "Driver org.postgresql.Driver
 *     claims to not accept jdbcUrl, ${SPRING_DATASOURCE_URL}" - technically a fail-fast, but
 *     not a message that names the missing setting.
 */
@AutoConfiguration
public class DeployedConfigurationAutoConfiguration {

    /**
     * :purpose: Contribute the startup validator.
     * :returns: the validator bean; declared ``static`` so the post-processor is available without
     *     instantiating this configuration class early.
     */
    @Bean
    static DeployedConfigurationValidator deployedConfigurationValidator() {
        return new DeployedConfigurationValidator();
    }

    /**
     * :purpose: Validate profile names and deployed-environment configuration during context refresh.
     */
    static class DeployedConfigurationValidator implements BeanFactoryPostProcessor {

        private static final Logger log = LoggerFactory.getLogger(DeployedConfigurationValidator.class);

        /**
         * :purpose: Profiles CardDemo ships support for: the three deployment targets plus the
         *     automated-test profile.
         */
        static final Set<String> SUPPORTED_PROFILES = Set.of("docker", "k8s", "prod", "test");

        /**
         * :purpose: Profiles that denote a real deployment, where packaged developer defaults must
         *     never be used for connection settings.
         */
        static final Set<String> DEPLOYED_PROFILES = Set.of("docker", "k8s", "prod");

        /**
         * :purpose: Name of the aggregating property source Spring Boot attaches at the FRONT of the
         *     environment for relaxed binding. It delegates to every other source, including the
         *     packaged ``application.yml``, so it must be skipped when deciding where a value came
         *     from - consulting it made every packaged default look environment-supplied.
         */
        static final String ATTACHED_CONFIGURATION_PROPERTIES_SOURCE = "configurationProperties";

        /**
         * :purpose: Connection settings a deployed service must receive from its environment, with
         *     the equivalent environment-variable name shown in the failure message.
         */
        private static final String[][] REQUIRED_DATASOURCE_PROPERTIES = {
            {"spring.datasource.url", "SPRING_DATASOURCE_URL"},
            {"spring.datasource.username", "SPRING_DATASOURCE_USERNAME"},
            {"spring.datasource.password", "SPRING_DATASOURCE_PASSWORD"},
        };

        /**
         * :purpose: Warn about unsupported profiles and enforce environment-supplied connection
         *     settings for deployed profiles, before any bean is created.
         * :param beanFactory: the bean factory, read for a ``DataSource`` definition and for the
         *     Environment whose profiles and property sources are inspected.
         * :raises IllegalStateException: when a deployed profile is active, the service persists
         *     data, and one or more critical connection settings would fall back to the packaged
         *     developer default.
         */
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            Environment environment = beanFactory.getBean(Environment.class);
            List<String> active = Arrays.asList(environment.getActiveProfiles());
            warnAboutUnsupportedProfiles(active);
            boolean persistsData =
                    beanFactory.getBeanNamesForType(DataSource.class, true, false).length > 0;
            if (active.stream().noneMatch(DEPLOYED_PROFILES::contains) || !persistsData) {
                return;
            }
            List<String> missing = new ArrayList<>();
            for (String[] property : REQUIRED_DATASOURCE_PROPERTIES) {
                if (!suppliedByEnvironment(environment, property[0])) {
                    missing.add(property[1] + " (" + property[0] + ")");
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "Deployed profile " + active + " is active but these critical connection "
                                + "settings were not supplied by the environment: " + missing
                                + ". A deployed CardDemo service must never fall back to the "
                                + "developer defaults packaged in application.yml; set the listed "
                                + "environment variables (see docker-compose.yml / k8s/configmap.yaml "
                                + "and k8s/secret.yaml).");
            }
        }

        /**
         * :purpose: Log a WARN for every active profile CardDemo does not ship support for, so an
         *     accidental or misspelt profile is visible in the very first seconds of the log.
         * :param active: the active profile names.
         */
        private void warnAboutUnsupportedProfiles(List<String> active) {
            Set<String> unsupported = new LinkedHashSet<>(active);
            unsupported.removeAll(SUPPORTED_PROFILES);
            if (!unsupported.isEmpty()) {
                log.warn("Active profile(s) {} are not part of the supported CardDemo profile set {};"
                                + " profile-specific configuration will NOT be applied. Structured JSON"
                                + " logging is still enabled (only the 'default' and 'test' profiles log"
                                + " human-readable text).",
                        unsupported, SUPPORTED_PROFILES);
            }
        }

        /**
         * :purpose: Decide whether a property is contributed by something other than the packaged
         *     application configuration.
         * :param environment: the Spring Environment whose property sources are inspected.
         * :param name: the canonical property name (relaxed binding resolves the environment-variable
         *     form automatically).
         * :returns: ``true`` when an environment variable, system property, command-line argument or
         *     external configuration file supplies the property.
         */
        private boolean suppliedByEnvironment(Environment environment, String name) {
            if (!(environment instanceof ConfigurableEnvironment configurable)) {
                return true;
            }
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (ATTACHED_CONFIGURATION_PROPERTIES_SOURCE.equals(source.getName())
                        || isPackagedApplicationConfig(source)) {
                    continue;
                }
                if (source.containsProperty(name)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * :purpose: Identify the property sources that come from the packaged ``application.yml``
         *     inside the service jar (including its profile documents), whose values are the
         *     developer defaults this validator exists to reject.
         * :param source: the property source under test.
         * :returns: ``true`` when the source is a packaged application-configuration document.
         */
        private boolean isPackagedApplicationConfig(PropertySource<?> source) {
            String name = source.getName();
            return name != null && name.contains("class path resource [application");
        }
    }
}
