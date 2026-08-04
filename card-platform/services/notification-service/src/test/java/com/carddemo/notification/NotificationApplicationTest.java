package com.carddemo.notification;

import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.HtmlRenderer;
import com.carddemo.notification.domain.NotificationRenderer;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.PlainTextRenderer;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bootstrap and start-up tests for the notification service. The first group reads class metadata
 * off {@link NotificationApplication} and starts nothing. The second group starts a Spring context
 * over the production component scan, so a broken scan root, a bean that cannot be constructed or a
 * duplicate renderer fails here rather than at deployment. Neither group loads auto-configuration,
 * so {@code mvn test} passes on a clean machine with no database and no message broker running.
 *
 * <p>The module replaces the customer-facing tail of app/cbl/CBSTM03A.CBL, the 924-line statement
 * program that app/jcl/CREASTMT.JCL runs at L79 as {@code EXEC PGM=CBSTM03A}. The sort at
 * app/jcl/CREASTMT.JCL:L53 keys that program's output by card number and transaction identifier.</p>
 */
@DisplayName("NotificationApplication, the bootstrap and start-up contract of the notification "
        + "service")
class NotificationApplicationTest {

    /** Package that declares {@link NotificationApplication} and roots component scanning. */
    private static final String ROOT_PACKAGE = "com.carddemo.notification";

    /** Name of the entry point located by reflection. No test here calls the method it names. */
    private static final String ENTRY_POINT_NAME = "main";

    /**
     * Package names the six sibling packages of the notification service occupy. Every entry is a
     * string constant, so no assertion here loads a class from any of those packages.
     */
    private static final List<String> EXPECTED_SIBLING_PACKAGES = List.of(
            "com.carddemo.notification.api",
            "com.carddemo.notification.config",
            "com.carddemo.notification.domain",
            "com.carddemo.notification.entity",
            "com.carddemo.notification.messaging",
            "com.carddemo.notification.repository");

    /** Count of renderers the service declares, one per output format. */
    private static final int RENDERER_COUNT = 2;

    /**
     * The credentials the shipped configuration deliberately leaves without a default, supplied here
     * so a context can start.
     *
     * <p>{@code config/SecurityConfig} refuses to start when any of them is unset, which is the
     * point of shipping them undefaulted: an unset password must stop start-up rather than sign on
     * under a password this repository publishes. {@code SecurityConfigTest} asserts that refusal.
     * The values below are generated-looking and distinct from every example the repository carries,
     * so none of them trips the published-value guard.
     */
    private static final String[] CREDENTIALS = {
        "POSTGRES_PASSWORD=a-generated-value-for-this-test",
        "KAFKA_SASL_PASSWORD=a-generated-broker-value-for-this-test",
        "ADMIN_PASSWORD_HASH={noop}a-generated-admin-value",
        "USER_PASSWORD_HASH={noop}a-generated-user-value",
        "MONITORING_PASSWORD_HASH={noop}a-generated-monitoring-value",
    };

    /**
     * Starts a context over the production component scan with a meter registry in place of the
     * auto-configured one.
     *
     * <p>No auto-configuration loads here, so Spring Data builds neither repository. Each one
     * arrives as a mock instead: the read model behind {@code api/NotificationHistoryController},
     * and the delivery-attempt table behind {@code domain/NotificationService}.</p>
     */
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(CREDENTIALS)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(StatementTransactionRepository.class,
                    () -> Mockito.mock(StatementTransactionRepository.class))
            .withBean(NotificationLogRepository.class,
                    () -> Mockito.mock(NotificationLogRepository.class))
            .withUserConfiguration(ProductionComponentScan.class);

    /**
     * Asserts the class carries exactly the two class-level annotations this module needs,
     * {@link SpringBootApplication} and {@link ConfigurationPropertiesScan}, in that order. The
     * count catches a third annotation whether or not a test here names it.
     *
     * <p>{@link ConfigurationPropertiesScan} registers
     * {@code com.carddemo.notification.config.NotificationProperties}, which binds and validates the
     * {@code carddemo} block of {@code application.yml}. Without it the three consumer groups, the
     * three consumed topic names, the dead-letter suffix and both retry settings would sit in that
     * file with nothing reading them.
     *
     * <p>{@code @EnableScheduling} is absent, and stays absent. This module runs no outbox relay.</p>
     */
    @Test
    void carriesTwoAnnotationsAndTheyAreSpringBootApplicationAndConfigurationPropertiesScan() {
        Annotation[] declared = NotificationApplication.class.getDeclaredAnnotations();

        assertEquals(2, declared.length,
                "NotificationApplication must carry exactly two class-level annotations, found "
                        + annotationTypeNames(declared));
        assertSame(SpringBootApplication.class, declared[0].annotationType(),
                "the first class-level annotation must be @SpringBootApplication");
        assertSame(ConfigurationPropertiesScan.class, declared[1].annotationType(),
                "the second class-level annotation must be @ConfigurationPropertiesScan, which "
                        + "registers NotificationProperties and so makes every carddemo key in "
                        + "application.yml reachable");
        assertEquals(2, NotificationApplication.class.getAnnotations().length,
                "NotificationApplication must present no annotation beyond the two it declares");
    }

    /**
     * Asserts {@link ConfigurationPropertiesScan} scans the declaring package and nothing wider.
     *
     * <p>A populated {@code basePackages} would reach into another module's package and register a
     * properties record this module does not own.</p>
     */
    @Test
    void configurationPropertiesScanKeepsItsDeclaringPackageAsTheScanRoot() {
        ConfigurationPropertiesScan annotation =
                NotificationApplication.class.getAnnotation(ConfigurationPropertiesScan.class);

        assertNotNull(annotation,
                "NotificationApplication must carry @ConfigurationPropertiesScan");
        assertAll("@ConfigurationPropertiesScan attributes",
                () -> assertEquals(0, annotation.value().length,
                        "value must stay empty, which roots the scan at " + ROOT_PACKAGE),
                () -> assertEquals(0, annotation.basePackages().length,
                        "basePackages must stay empty, which roots the scan at " + ROOT_PACKAGE),
                () -> assertEquals(0, annotation.basePackageClasses().length,
                        "basePackageClasses must stay empty, which roots the scan at "
                                + ROOT_PACKAGE));
    }

    @Test
    void springBootApplicationHoldsItsFiveNamedAttributeDefaults() {
        SpringBootApplication annotation =
                NotificationApplication.class.getAnnotation(SpringBootApplication.class);
        assertNotNull(annotation, "NotificationApplication must carry @SpringBootApplication");

        assertAll("@SpringBootApplication attributes",
                () -> assertEquals(0, annotation.scanBasePackages().length,
                        "scanBasePackages must stay empty, holding the component-scan root at "
                                + ROOT_PACKAGE),
                () -> assertEquals(0, annotation.scanBasePackageClasses().length,
                        "scanBasePackageClasses must stay empty, holding the component-scan root at "
                                + ROOT_PACKAGE),
                () -> assertEquals(0, annotation.exclude().length,
                        "exclude must stay empty, switching off no auto-configuration class"),
                () -> assertEquals(0, annotation.excludeName().length,
                        "excludeName must stay empty, switching off no auto-configuration class"),
                () -> assertTrue(annotation.proxyBeanMethods(),
                        "proxyBeanMethods must stay true"));
    }

    @Test
    void everySpringBootApplicationAttributeEqualsItsDeclaredDefault() {
        SpringBootApplication annotation =
                NotificationApplication.class.getAnnotation(SpringBootApplication.class);
        assertNotNull(annotation, "NotificationApplication must carry @SpringBootApplication");

        List<Executable> checks = new ArrayList<>();
        for (Method attribute : SpringBootApplication.class.getDeclaredMethods()) {
            checks.add(() -> assertTrue(
                    Objects.deepEquals(attribute.invoke(annotation), attribute.getDefaultValue()),
                    "@SpringBootApplication attribute " + attribute.getName()
                            + " must hold the default value its annotation type declares"));
        }

        assertFalse(checks.isEmpty(),
                "@SpringBootApplication must declare at least one attribute to compare");
        assertAll("@SpringBootApplication attributes against their declared defaults", checks);
    }

    @Test
    void isAPublicNonFinalConcreteClass() {
        int modifiers = NotificationApplication.class.getModifiers();

        assertAll("NotificationApplication class modifiers",
                () -> assertTrue(Modifier.isPublic(modifiers),
                        "NotificationApplication must be public"),
                () -> assertFalse(Modifier.isFinal(modifiers),
                        "NotificationApplication must not be final. Spring subclasses a "
                                + "configuration class to proxy it, and a final class fails that "
                                + "step at application start-up"),
                () -> assertFalse(Modifier.isAbstract(modifiers),
                        "NotificationApplication must not be abstract"),
                () -> assertFalse(NotificationApplication.class.isInterface(),
                        "NotificationApplication must be a class"));
    }

    /**
     * Asserts the signature of the entry point. The test looks the method up and never calls it, so
     * no application starts.
     *
     * @throws NoSuchMethodException when the class declares no method under the expected name and
     *     parameter type, which fails the test
     */
    @Test
    void entryPointIsPublicStaticVoidTakingOneStringArray() throws NoSuchMethodException {
        Method entryPoint =
                NotificationApplication.class.getDeclaredMethod(ENTRY_POINT_NAME, String[].class);

        assertAll("entry point signature",
                () -> assertTrue(Modifier.isPublic(entryPoint.getModifiers()),
                        ENTRY_POINT_NAME + " must be public"),
                () -> assertTrue(Modifier.isStatic(entryPoint.getModifiers()),
                        ENTRY_POINT_NAME + " must be static"),
                () -> assertSame(void.class, entryPoint.getReturnType(),
                        ENTRY_POINT_NAME + " must return void"),
                () -> assertEquals(1, entryPoint.getParameterCount(),
                        ENTRY_POINT_NAME + " must take exactly one parameter"),
                () -> assertSame(String[].class, entryPoint.getParameterTypes()[0],
                        ENTRY_POINT_NAME + " must take a String array"));
    }

    @Test
    @DisplayName("declaring package is com.carddemo.notification, the component-scan root")
    void packageNameIsTheComponentScanRoot() {
        assertEquals(ROOT_PACKAGE, NotificationApplication.class.getPackageName(),
                "the bare @SpringBootApplication annotation roots component scanning at the "
                        + "declaring package, which must stay " + ROOT_PACKAGE);
    }

    @Test
    @DisplayName("api, config, domain, entity, messaging and repository each extend the root by one"
            + " segment")
    void expectedSiblingPackagesExtendTheRootByOneSegment() {
        String prefix = ROOT_PACKAGE + ".";
        List<Executable> checks = new ArrayList<>();

        for (String siblingPackage : EXPECTED_SIBLING_PACKAGES) {
            checks.add(() -> {
                assertTrue(siblingPackage.startsWith(prefix),
                        siblingPackage + " must sit under " + ROOT_PACKAGE);
                String segment = siblingPackage.substring(prefix.length());
                assertFalse(segment.isEmpty(),
                        siblingPackage + " must add a segment to " + ROOT_PACKAGE);
                assertFalse(segment.contains("."),
                        siblingPackage + " must add exactly one segment to " + ROOT_PACKAGE);
            });
        }

        assertEquals(6, checks.size(),
                "the notification service holds six sibling packages under " + ROOT_PACKAGE);
        assertAll("expected sibling packages of " + ROOT_PACKAGE, checks);
    }

    @Test
    void declaresOneMethodAndNoField() {
        assertAll("declared members of NotificationApplication",
                () -> assertEquals(1, NotificationApplication.class.getDeclaredMethods().length,
                        "NotificationApplication must declare one method, the entry point"),
                () -> assertEquals(0, NotificationApplication.class.getDeclaredFields().length,
                        "NotificationApplication must declare no field"));
    }

        /**
     * Asserts the context starts and resolves every bean the service needs. A component that cannot
     * be constructed, or a missing collaborator, fails the context rather than passing a reflection
     * check.
     */
    @Test
    @DisplayName("the context starts and resolves both renderers and the meter holder")
    void theContextStartsAndResolvesEveryRequiredBean() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PlainTextRenderer.class);
            assertThat(context).hasSingleBean(HtmlRenderer.class);
            assertThat(context).hasSingleBean(NotificationMetrics.class);
            assertThat(context).hasSingleBean(ObservabilityConfig.class);
        });
    }

    /**
     * Asserts the scan discovers both renderers and that each reports its own format. A second
     * renderer for one format would make the choice of renderer ambiguous at run time.
     */
    @Test
    @DisplayName("the scan discovers one renderer per output format")
    void theScanDiscoversOneRendererPerOutputFormat() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();

            List<NotificationRenderer> renderers =
                    new ArrayList<>(context.getBeansOfType(NotificationRenderer.class).values());
            List<RenderedFormat> formats = new ArrayList<>();
            for (NotificationRenderer renderer : renderers) {
                formats.add(renderer.format());
            }

            assertThat(renderers).as("renderer beans the scan discovered").hasSize(RENDERER_COUNT);
            assertThat(formats).as("formats the discovered renderers report")
                    .containsExactlyInAnyOrder(RenderedFormat.PLAIN_TEXT, RenderedFormat.HTML);
        });
    }

    /**
     * Asserts every discovered bean comes from the package the bootstrap class declares. That is
     * what a bare {@code @SpringBootApplication} guarantees, and a moved bootstrap class or a
     * populated {@code scanBasePackages} would break it.
     */
    @Test
    @DisplayName("every discovered bean sits under the package the bootstrap class declares")
    void everyDiscoveredBeanSitsUnderTheDeclaringPackage() {
        assertThat(NotificationApplication.class.getPackageName())
                .as("package that roots component scanning").isEqualTo(ROOT_PACKAGE);

        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();

            for (Object bean : context.getBeansOfType(NotificationRenderer.class).values()) {
                assertThat(bean.getClass().getPackageName())
                        .as("package of the renderer bean %s", bean.getClass().getSimpleName())
                        .startsWith(ROOT_PACKAGE + ".");
            }
            assertThat(context.getBean(NotificationMetrics.class).getClass().getPackageName())
                    .as("package of the meter holder").startsWith(ROOT_PACKAGE + ".");
        });
    }

    /**
     * Asserts the context registers the meters, so a scrape taken immediately after start-up lists
     * every series. The meter holder registers eagerly in its constructor, and that only happens if
     * the context actually built it.
     */
    @Test
    void theStartedContextRegistersTheServiceMeters() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();

            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(registry.getMeters()).as("meters present once the context has started")
                    .isNotEmpty();
            assertThat(registry.getMeters())
                    .as("every meter the started context registered")
                    .allSatisfy(meter -> assertThat(meter.getId().getName())
                            .startsWith("carddemo.notification."));
        });
    }

    /**
     * Runs the production component scan without the bootstrap class, so no auto-configuration
     * loads and the context needs no database and no message broker.
     *
     * <p>The scan excludes this class as well. Test classes share the classpath with production
     * classes, so a scan of the root package would otherwise find this configuration and register
     * it a second time.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = NotificationApplication.class,
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {NotificationApplication.class, ProductionComponentScan.class}))
    static class ProductionComponentScan {
    }

    /**
     * Renders annotation type names into a failure message.
     *
     * @param annotations annotations read from the class under test
     * @return the annotation type names, comma separated, or {@code none} for an empty array
     */
    private static String annotationTypeNames(Annotation[] annotations) {
        if (annotations.length == 0) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (Annotation annotation : annotations) {
            names.add("@" + annotation.annotationType().getSimpleName());
        }
        return String.join(", ", names);
    }
}
