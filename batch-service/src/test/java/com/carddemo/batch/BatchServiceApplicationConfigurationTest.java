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
package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.carddemo.batch.config.JobSchedulingConfig;
import com.carddemo.batch.controller.BatchController;
import com.carddemo.common.config.ObservabilityConfig;
import com.carddemo.common.config.RedisCommandMetricsConfig;
import com.carddemo.common.config.WebObservabilityConfig;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * :purpose: Guard the COMPOSITION of the batch service, which no test of a single
 *     class can observe. Every ``@Controller`` under
 *     ``com.carddemo.batch.controller`` is discovered by component scan and handed
 *     to a real {@link RequestMappingHandlerMapping}, so two beans claiming one
 *     route fail this test with ``IllegalStateException: Ambiguous mapping`` exactly
 *     as they fail the service during context refresh. A controller test built with
 *     ``MockMvcBuilders.standaloneSetup`` registers one bean in isolation and is
 *     structurally incapable of seeing that collision — which is how a duplicate
 *     launch controller reached a delivered commit. The shared observability
 *     wiring the service declares no local substitute for is asserted here too.
 * :output: Assertions that every route on the launch surface has exactly one
 *     handler, that the ``/batch`` surface has a single owning controller, that the
 *     three launch-surface patterns are the ones registered, and that the
 *     application imports the shared observability configuration.
 * :note: The mapping is built over a hermetic web context holding only the scanned
 *     controllers and mocked collaborators, so the check needs no database, no
 *     Redis, no Docker and no Spring Boot auto-configuration, and cannot be
 *     weakened by an unrelated configuration change.
 */
class BatchServiceApplicationConfigurationTest {

    /**
     * :purpose: Configuration source for the hermetic mapping check. It scans the
     *     controller package for stereotyped controllers ONLY, so every controller
     *     the module ships is registered and none is silently omitted, while no
     *     persistence or batch infrastructure is pulled in.
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = BatchController.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION, classes = Controller.class))
    static class ControllerScanConfig {
    }

    /** Hermetic web context holding the scanned controllers and their mocked collaborators. */
    private static GenericWebApplicationContext context;

    /** The refreshed mapping registry under inspection. */
    private static RequestMappingHandlerMapping mapping;

    /**
     * :purpose: Refresh the controller-only web context and register its request
     *     mappings. ``afterPropertiesSet`` performs the registration that rejects a
     *     duplicate mapping, so a collision aborts every test in this class.
     */
    @BeforeAll
    static void registerControllerMappings() {
        context = new GenericWebApplicationContext(new MockServletContext());
        context.registerBean(JobSchedulingConfig.class, () -> mock(JobSchedulingConfig.class));
        context.registerBean(JobRepository.class, () -> mock(JobRepository.class));
        new AnnotatedBeanDefinitionReader(context).register(ControllerScanConfig.class);
        context.refresh();

        mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
    }

    @AfterAll
    static void closeContext() {
        context.close();
    }

    @Test
    @DisplayName("every registered route resolves to exactly one handler method")
    void everyRouteResolvesToExactlyOneHandler() {
        Map<String, Set<String>> handlersByRoute = new LinkedHashMap<>();
        mapping.getHandlerMethods().forEach((RequestMappingInfo info, HandlerMethod method) -> {
            String route = info.getMethodsCondition() + " " + new TreeSet<>(info.getPatternValues());
            handlersByRoute
                    .computeIfAbsent(route, key -> new TreeSet<>())
                    .add(method.getBeanType().getName() + "#" + method.getMethod().getName());
        });

        assertThat(handlersByRoute).isNotEmpty();
        handlersByRoute.forEach((route, handlers) ->
                assertThat(handlers).as("handlers registered for %s", route).hasSize(1));
    }

    @Test
    @DisplayName("one controller owns the whole /batch launch surface")
    void batchControllerIsTheSoleOwnerOfTheLaunchSurface() {
        List<String> batchRouteOwners = mapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().stream()
                        .anyMatch(pattern -> pattern.startsWith("/batch")))
                .map(entry -> entry.getValue().getBeanType().getName())
                .distinct()
                .toList();

        assertThat(batchRouteOwners).containsExactly(BatchController.class.getName());
    }

    @Test
    @DisplayName("the launch surface registers exactly the three documented patterns")
    void launchSurfaceRegistersTheDocumentedPatterns() {
        Set<String> patterns = new TreeSet<>();
        mapping.getHandlerMethods().keySet()
                .forEach(info -> patterns.addAll(info.getPatternValues()));

        assertThat(patterns).containsExactly(
                "/batch/jobs",
                "/batch/jobs/executions/{jobExecutionId}",
                "/batch/jobs/{jobName}");
    }

    @Test
    @DisplayName("the application imports the shared observability and Redis-metrics configuration")
    void applicationImportsSharedObservabilityConfiguration() {
        Import imports = BatchServiceApplication.class.getAnnotation(Import.class);

        assertThat(imports).isNotNull();
        assertThat(imports.value()).contains(
                ObservabilityConfig.class,
                WebObservabilityConfig.class,
                RedisCommandMetricsConfig.class);
    }

    /**
     * :purpose: Prove no ``@Configuration`` class in the service declares two ``@Bean``
     *     methods under one bean name. Spring rejects such a class outright during
     *     context refresh — ``BeanDefinitionParsingException: ... contains overloaded
     *     @Bean methods with name '...'`` — so a single overloaded pair takes the whole
     *     service down, and no test of one ``@Bean`` method in isolation can see it.
     *     The check is reflective and needs no datasource, so it runs in the unit phase
     *     rather than waiting for a Testcontainers integration test.
     * :note: Bean NAME, not method signature, is what must be unique: two overloads
     *     differing only in parameter order are two definitions of one name.
     */
    @Test
    @DisplayName("no @Configuration class declares two @Bean methods under one name")
    void noConfigurationClassOverloadsABeanName() {
        Map<String, List<String>> offenders = new LinkedHashMap<>();

        for (Class<?> configuration : SERVICE_CONFIGURATION_CLASSES) {
            Map<String, Integer> countsByName = new LinkedHashMap<>();
            for (Method method : configuration.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class)) {
                    String[] declared = method.getAnnotation(Bean.class).name();
                    String beanName = declared.length > 0 ? declared[0] : method.getName();
                    countsByName.merge(beanName, 1, Integer::sum);
                }
            }
            List<String> duplicated = countsByName.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!duplicated.isEmpty()) {
                offenders.put(configuration.getSimpleName(), duplicated);
            }
        }

        assertThat(SERVICE_CONFIGURATION_CLASSES)
                .as("the reflective sweep must actually find the service's configuration classes")
                .isNotEmpty();
        assertThat(offenders)
                .as("@Configuration classes declaring a duplicated @Bean name")
                .isEmpty();
    }

    /**
     * :purpose: Every ``@Configuration`` class the batch service declares, discovered by
     *     scanning its own package tree so a configuration class added later is covered
     *     without editing this test.
     */
    private static final List<Class<?>> SERVICE_CONFIGURATION_CLASSES = serviceConfigurationClasses();

    /**
     * :purpose: Collect the service's own ``@Configuration`` classes through a
     *     component-scanning provider restricted to ``@Configuration``.
     * :returns: the discovered configuration classes.
     * :raises IllegalStateException: when a discovered class cannot be loaded.
     */
    private static List<Class<?>> serviceConfigurationClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        List<Class<?>> found = new java.util.ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.carddemo.batch")) {
            String className = candidate.getBeanClassName();
            try {
                found.add(Class.forName(className));
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("Unable to load " + className, ex);
            }
        }
        return List.copyOf(found);
    }
}
