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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.common.config.DeployedConfigurationAutoConfiguration.DeployedConfigurationValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * :purpose: Unit tests for the deployed-configuration guard. They pin the two behaviours QA found
 *     missing: a service running a DEPLOYED profile must refuse to start on the connection defaults
 *     packaged in ``application.yml``, and an active profile outside the supported set must be
 *     reported rather than accepted in silence.
 * :note: The property source that stands in for the packaged configuration is named exactly as Spring
 *     Boot names it ("Config resource 'class path resource [application.yml]' ..."), because that name
 *     is how the validator tells a packaged default from an operator-supplied value.
 */
@DisplayName("DeployedConfigurationValidator")
class DeployedConfigurationValidatorTest {

    private static final String PACKAGED_SOURCE_NAME =
            "Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'";

    private static final Map<String, Object> DATASOURCE_PROPERTIES = Map.of(
            "spring.datasource.url", "jdbc:postgresql://postgres:5432/carddemo",
            "spring.datasource.username", "carddemo",
            "spring.datasource.password", "carddemo");

    private final DeployedConfigurationValidator validator = new DeployedConfigurationValidator();

    private ListAppender<ILoggingEvent> appender;

    private ch.qos.logback.classic.Logger validatorLogger;

    /**
     * :purpose: Capture the validator's log events.
     */
    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        validatorLogger = context.getLogger(DeployedConfigurationValidator.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        validatorLogger.addAppender(appender);
        validatorLogger.setLevel(Level.INFO);
    }

    /**
     * :purpose: Detach the capturing appender.
     */
    @AfterEach
    void tearDown() {
        validatorLogger.detachAppender(appender);
        appender.stop();
    }

    /**
     * :purpose: A deployed profile with only packaged defaults is rejected, and the message names
     *     every missing setting.
     */
    @Test
    @DisplayName("deployed profile refuses to start on packaged connection defaults")
    void deployedProfileRejectsPackagedDefaults() {
        DefaultListableBeanFactory beanFactory = beanFactoryWithDataSource(
                environment("docker", PACKAGED_SOURCE_NAME, DATASOURCE_PROPERTIES));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.postProcessBeanFactory(beanFactory));

        assertTrue(ex.getMessage().contains("SPRING_DATASOURCE_URL"));
        assertTrue(ex.getMessage().contains("SPRING_DATASOURCE_USERNAME"));
        assertTrue(ex.getMessage().contains("SPRING_DATASOURCE_PASSWORD"));
        assertTrue(ex.getMessage().contains("docker"), "the active profile helps the operator");
    }

    /**
     * :purpose: A deployed profile whose connection settings come from the environment starts.
     */
    @Test
    @DisplayName("deployed profile accepts environment-supplied connection settings")
    void deployedProfileAcceptsEnvironmentSuppliedSettings() {
        StandardEnvironment environment = environment("docker", PACKAGED_SOURCE_NAME, DATASOURCE_PROPERTIES);
        environment.getPropertySources()
                .addFirst(new MapPropertySource("systemEnvironment", new LinkedHashMap<>(DATASOURCE_PROPERTIES)));
        DefaultListableBeanFactory beanFactory = beanFactoryWithDataSource(environment);

        assertDoesNotThrow(() -> validator.postProcessBeanFactory(beanFactory));
    }

    /**
     * :purpose: A non-deployed profile keeps the packaged developer defaults, so local runs and tests
     *     are unaffected.
     */
    @Test
    @DisplayName("non-deployed profile keeps the packaged developer defaults")
    void testProfileKeepsPackagedDefaults() {
        DefaultListableBeanFactory beanFactory = beanFactoryWithDataSource(
                environment("test", PACKAGED_SOURCE_NAME, DATASOURCE_PROPERTIES));

        assertDoesNotThrow(() -> validator.postProcessBeanFactory(beanFactory));
    }

    /**
     * :purpose: A service without a persistence layer (the api-gateway shape) is exempt from the
     *     datasource requirement.
     */
    @Test
    @DisplayName("a service with no DataSource is exempt")
    void serviceWithoutDataSourceIsExempt() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("environment",
                environment("docker", PACKAGED_SOURCE_NAME, DATASOURCE_PROPERTIES));

        assertDoesNotThrow(() -> validator.postProcessBeanFactory(beanFactory));
    }

    /**
     * :purpose: An unrecognised active profile is reported at WARN and does not stop startup.
     */
    @Test
    @DisplayName("an unsupported profile is reported at WARN")
    void unsupportedProfileIsReported() {
        StandardEnvironment environment = environment("nosuchprofile", PACKAGED_SOURCE_NAME, Map.of());
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("environment", environment);

        assertDoesNotThrow(() -> validator.postProcessBeanFactory(beanFactory));

        assertEquals(1, appender.list.size(), "exactly one profile warning");
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("nosuchprofile"));
        assertTrue(event.getFormattedMessage().contains("docker"),
                "the supported set must be shown so the operator can correct the value");
    }

    /**
     * :purpose: A supported profile produces no warning at all.
     */
    @Test
    @DisplayName("a supported profile produces no warning")
    void supportedProfileIsSilent() {
        StandardEnvironment environment = environment("k8s", PACKAGED_SOURCE_NAME, Map.of());
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("environment", environment);

        validator.postProcessBeanFactory(beanFactory);

        assertTrue(appender.list.isEmpty(), "a supported profile must not warn");
    }

    /**
     * :purpose: Build an environment with one active profile and one packaged-configuration source.
     * :param profile: the active profile name.
     * :param sourceName: the property-source name to use for the packaged configuration.
     * :param properties: the packaged property values.
     * :returns: the prepared environment.
     */
    private static StandardEnvironment environment(String profile, String sourceName,
                                                   Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profile);
        environment.getPropertySources()
                .addLast(new MapPropertySource(sourceName, new LinkedHashMap<>(properties)));
        return environment;
    }

    /**
     * :purpose: Build a bean factory that declares a ``DataSource`` (never instantiated) and exposes
     *     the environment, mirroring the shape of a JPA service context.
     * :param environment: the environment to expose.
     * :returns: the prepared bean factory.
     */
    private static DefaultListableBeanFactory beanFactoryWithDataSource(StandardEnvironment environment) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("environment", environment);
        beanFactory.registerBeanDefinition("dataSource", new RootBeanDefinition(DataSource.class));
        return beanFactory;
    }
}
