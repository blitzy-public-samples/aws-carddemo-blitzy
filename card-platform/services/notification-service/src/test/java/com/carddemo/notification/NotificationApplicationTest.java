package com.carddemo.notification;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflection tests over the bootstrap class {@link NotificationApplication}. Every test below reads
 * class metadata and starts no application, so {@code mvn test} passes on a clean machine with no
 * database and no message broker running.
 *
 * <p>The module replaces the customer-facing tail of app/cbl/CBSTM03A.CBL, the 924-line statement
 * program that app/jcl/CREASTMT.JCL runs at L79 as {@code EXEC PGM=CBSTM03A}. The sort at
 * app/jcl/CREASTMT.JCL:L53 keys that program's output by card number and transaction identifier.</p>
 */
@DisplayName("NotificationApplication, the bare bootstrap contract of the notification service")
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

    /**
     * Asserts the class carries exactly one class-level annotation, {@link SpringBootApplication}.
     * The count catches a second annotation whether or not a test here names it.
     */
    @Test
    void carriesOneAnnotationAndItIsSpringBootApplication() {
        Annotation[] declared = NotificationApplication.class.getDeclaredAnnotations();

        assertEquals(1, declared.length,
                "NotificationApplication must carry exactly one class-level annotation, found "
                        + annotationTypeNames(declared));
        assertSame(SpringBootApplication.class, declared[0].annotationType(),
                "the one class-level annotation must be @SpringBootApplication");
        assertEquals(1, NotificationApplication.class.getAnnotations().length,
                "NotificationApplication must present no annotation beyond the one it declares");
    }

    /**
     * Asserts the five named attributes of {@link SpringBootApplication} keep their declared values.
     * A populated {@code scanBasePackages} moves the component-scan root off the declaring package.
     */
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

    /**
     * Compares every attribute {@link SpringBootApplication} declares against the default value its
     * annotation type declares for that attribute. The loop reaches attributes no assertion above
     * names.
     */
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

    /**
     * Asserts the class is public, not final, not abstract and not an interface. The framework
     * proxies a configuration class by subclassing it, and that step needs a non-final class.
     */
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

    /**
     * Asserts the package that declares the class. That package roots component scanning, which is
     * what makes the sibling packages api, config, domain, entity, messaging and repository
     * discoverable.
     */
    @Test
    @DisplayName("declaring package is com.carddemo.notification, the component-scan root")
    void packageNameIsTheComponentScanRoot() {
        assertEquals(ROOT_PACKAGE, NotificationApplication.class.getPackageName(),
                "the bare @SpringBootApplication annotation roots component scanning at the "
                        + "declaring package, which must stay " + ROOT_PACKAGE);
    }

    /**
     * Asserts each expected sibling package name extends the root by one segment. The comparison
     * reads string constants and loads no class.
     */
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

    /**
     * Asserts the class declares one method and no field. A bean factory method or a field added
     * here fails the count.
     */
    @Test
    void declaresOneMethodAndNoField() {
        assertAll("declared members of NotificationApplication",
                () -> assertEquals(1, NotificationApplication.class.getDeclaredMethods().length,
                        "NotificationApplication must declare one method, the entry point"),
                () -> assertEquals(0, NotificationApplication.class.getDeclaredFields().length,
                        "NotificationApplication must declare no field"));
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
