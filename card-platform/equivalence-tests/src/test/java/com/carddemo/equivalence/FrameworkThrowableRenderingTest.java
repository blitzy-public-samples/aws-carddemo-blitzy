package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.support.KafkaUtils;

/**
 * Proves the shipped configuration keeps an exception message out of the log.
 *
 * <p>{@link LogHygieneContractTest} holds every logger call this platform writes, and holds the
 * declaration that withholds {@code stack_trace} from all six services. Neither can prove what that
 * declaration does, because the call it defends against is one the framework writes. Spring Kafka
 * reports a delivery it has given up on from {@code SeekUtils} through
 * {@code LogAccessor.error(Throwable, Supplier)}. That is a fixed call at a fixed level, so no
 * {@code logging.level} property reaches it, and the logstash format renders the throwable it carries
 * as the {@code stack_trace} member with the exception message ahead of the frames. A message quoting
 * a rejected payload, a violated constraint or a statement would therefore reach the log past every
 * rule the sibling class applies.
 *
 * <p>Each service's own {@code application.yml} is loaded here, by path, and made to serve that exact
 * call. So the subject is the shipped file rather than a copy of its settings, and a change to any of
 * the six is reported by the service it belongs to.
 *
 * <p>{@link #omittingTheDeclarationRendersTheExceptionMessage} loads no configuration at all and
 * asserts the leak does occur. That arm is the one that matters most: it proves these tests can fail,
 * so deleting a declaration is reported rather than passing quietly. The same discipline sits in
 * {@code DecimalTruncationEquivalenceTest}, which asserts that the wrong rounding mode gives a
 * different answer.
 *
 * <p>Every arm runs in a separate process, because installing a logging configuration replaces it for
 * the whole virtual machine. Reconfiguring logging inside the test process would change what every
 * later test in the same fork writes, and the result would depend on the order tests happened to run
 * in. The child writes structured records to a file, so the assertion reads the same bytes an operator
 * would collect.
 */
@DisplayName("The shipped configuration suppresses framework throwable rendering")
class FrameworkThrowableRenderingTest {

    /** Text no record may carry. It stands for a payload value quoted by an exception message. */
    private static final String SENSITIVE = "PAN-4111111111111111-CVV-737";

    /** The logstash member that renders a throwable. */
    private static final String THROWABLE_MEMBER = "stack_trace";

    /** The message the framework call carries, holding record coordinates and nothing else. */
    private static final String FRAMEWORK_MESSAGE =
            "Backoff exhausted for transaction.authorized-3@4711";

    /** The six service module directory names, each holding one shipped configuration. */
    private static final List<String> MODULES = List.of(
            "authorization-service", "ledger-posting-service", "fraud-detection-service",
            "notification-service", "account-service", "card-service");

    /** Path below a service module to its configuration. */
    private static final String APPLICATION_YAML = "src/main/resources/application.yml";

    /** How long a child is allowed, generously, since it starts a context. */
    private static final int CHILD_TIMEOUT_SECONDS = 180;

    @Test
    @DisplayName("every shipped configuration removes the exception message and the frames")
    void everyShippedConfigurationRemovesTheExceptionMessage(@TempDir Path directory) {
        List<String> leaking = new ArrayList<>();
        List<String> silenced = new ArrayList<>();

        for (String module : MODULES) {
            String written = runChild(directory.resolve(module + ".json"), configurationOf(module));

            if (written.contains(SENSITIVE) || written.contains(THROWABLE_MEMBER)) {
                leaking.add(module);
            }
            if (!written.contains(FRAMEWORK_MESSAGE)) {
                silenced.add(module);
            }
        }

        assertEquals(List.of(), leaking,
                "the exception message reached the log under the configuration these services ship, "
                        + "so the framework renders a throwable by a route that configuration does "
                        + "not close: " + leaking);
        assertEquals(List.of(), silenced,
                "the record itself has to survive. Withholding the member must drop the rendering "
                        + "and nothing else, or an operator loses the report that a delivery was "
                        + "given up on: " + silenced);
    }

    @Test
    @DisplayName("omitting the declaration renders the exception message, so the rule can fail")
    void omittingTheDeclarationRendersTheExceptionMessage(@TempDir Path directory) {
        String written = runChild(directory.resolve("undeclared.json"), null);

        assertTrue(written.contains(SENSITIVE),
                "with no configuration loaded the framework has to leak, or the arm above proves "
                        + "nothing and would keep passing after every declaration was deleted: "
                        + written);
        assertTrue(written.contains(THROWABLE_MEMBER),
                "the leak arrives as the " + THROWABLE_MEMBER + " member, and a leak by another "
                        + "route would need a different remedy: " + written);
    }

    @Test
    @DisplayName("the framework names a record by its coordinates rather than by its contents")
    void theFrameworkNamesARecordByItsCoordinates(@TempDir Path directory) {
        String written = runChild(directory.resolve("coordinates.json"),
                configurationOf("ledger-posting-service"));

        assertTrue(written.contains("The framework names this delivery"
                        + " transaction.authorized-3@4711"),
                "the framework formats a record as topic-partition@offset, and this platform reports "
                        + "coordinates the same way, so the two read alike: " + written);
        assertFalse(written.contains("cardNumber") || written.contains("4111111111111111"),
                "the record the harness formatted carries a card number in its value, and the "
                        + "framework rendered it. Withholding " + THROWABLE_MEMBER + " covers the "
                        + "throwable and not the message, so a formatter that rendered the value "
                        + "would need replacing through KafkaUtils as well: " + written);
        assertFalse(written.contains("00000000011"),
                "the formatter rendered the record key, which names an account: " + written);
    }

    /**
     * Returns the shipped configuration of one service module.
     *
     * @param module the service module directory name
     * @return the path of its {@code application.yml}
     */
    private static Path configurationOf(String module) {
        Path configuration = repositoryRoot().resolve("card-platform/services").resolve(module)
                .resolve(APPLICATION_YAML);
        assertTrue(Files.isRegularFile(configuration), "no configuration sits at " + configuration);
        return configuration;
    }

    /**
     * Runs the harness in a separate process and returns everything it wrote.
     *
     * @param output        the file the child writes structured records to
     * @param configuration the shipped configuration to load, or {@code null} to load none
     * @return the file contents
     */
    private static String runChild(Path output, Path configuration) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath", System.getProperty("java.class.path"),
                "-D" + ThrowableRenderingHarness.OUTPUT_PROPERTY + "=" + output,
                "-D" + ThrowableRenderingHarness.CONFIGURATION_PROPERTY + "="
                        + (configuration == null ? "" : configuration),
                ThrowableRenderingHarness.class.getName()));

        Process child;
        try {
            child = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException unstartable) {
            throw new UncheckedIOException("cannot start " + command, unstartable);
        }

        String console;
        boolean finished;
        try {
            console = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            finished = child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (IOException unreadable) {
            child.destroyForcibly();
            throw new UncheckedIOException("cannot read the harness output", unreadable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
            throw new IllegalStateException("interrupted waiting for the harness", interrupted);
        }

        assertTrue(finished, "the harness did not finish within " + CHILD_TIMEOUT_SECONDS
                + " seconds. Its console was: " + console);
        assertEquals(0, child.exitValue(),
                "the harness failed rather than writing records. Its console was: " + console);

        try {
            return Files.readString(output, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the harness wrote no readable file at " + output
                    + ". Its console was: " + console, unreadable);
        }
    }

    /**
     * Returns the repository root, being the ancestor of the fixture directory.
     *
     * @return that directory
     */
    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent();
    }
}

/**
 * Writes structured records the way a deployed service does, then makes the framework render one.
 *
 * <p>This runs as its own process, started by {@link FrameworkThrowableRenderingTest}. It exists as a
 * class with a {@code main} rather than as test code because installing a logging configuration
 * replaces it for the whole virtual machine, and the test process has to keep the one it started with.
 *
 * <p>It carries no {@code @EnableAutoConfiguration}, so a shipped file naming a database and a broker
 * can be loaded for its logging block alone without anything trying to reach either.
 *
 * <p>Records go to a file rather than to the console so the parent reads only what the encoder wrote,
 * with no start-up output mixed in. The declaration under test governs both destinations, and shipped
 * services write to the console.
 */
@Configuration
class ThrowableRenderingHarness {

    /** System property naming the file to write. */
    static final String OUTPUT_PROPERTY = "harness.output";

    /** System property naming the configuration to load, empty to load none. */
    static final String CONFIGURATION_PROPERTY = "harness.configuration";

    /**
     * Writes the Logback configuration the child runs with, beside the file it writes records to.
     *
     * <p>It includes Boot's own defaults and Boot's own structured file appender, so the encoder,
     * the format property and the threshold are the ones a deployed service uses. {@code LOG_FILE}
     * and {@code FILE_LOG_STRUCTURED_FORMAT} are system properties Boot sets from
     * {@code logging.file.name} and {@code logging.structured.format.file} before it configures
     * Logback, so this file names no path and no format of its own.
     *
     * @param output the file records are written to, whose directory holds this configuration
     * @return the path of the configuration written
     */
    private static Path structuredFileLogging(Path output) {
        Path configuration = output.resolveSibling(output.getFileName() + "-logback.xml");
        String document = """
                <configuration>
                  <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
                  <include resource="org/springframework/boot/logging/logback/\
                structured-file-appender.xml"/>
                  <root level="INFO">
                    <appender-ref ref="FILE"/>
                  </root>
                </configuration>
                """;
        try {
            Files.writeString(configuration, document, StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            throw new UncheckedIOException("cannot write " + configuration, unwritable);
        }
        return configuration;
    }

    /**
     * Starts a context, makes the framework log a throwable, and stops.
     *
     * @param arguments ignored; configuration arrives as system properties
     */
    public static void main(String[] arguments) {
        Path output = Path.of(System.getProperty(OUTPUT_PROPERTY));
        String configuration = System.getProperty(CONFIGURATION_PROPERTY, "");

        SpringApplicationBuilder builder =
                new SpringApplicationBuilder(ThrowableRenderingHarness.class)
                        .web(WebApplicationType.NONE);
        builder.properties(
                "spring.main.banner-mode=off",
                "spring.application.name=harness-service",
                // The child inherits this module's class path, and that class path carries
                // src/test/resources/logback-test.xml. Logback treats it as a self-initializing
                // configuration, and Spring Boot then leaves it in place: it applies the level
                // properties over it and never builds the file appender logging.file.name names,
                // so the child wrote no file at all and the parent read nothing. Naming a
                // configuration takes that decision back. The file below is what Boot builds
                // programmatically for a structured file destination, so what the encoder renders
                // is unchanged, and every exclusion still arrives as a property from the shipped
                // configuration rather than from this file.
                "logging.config=file:" + structuredFileLogging(output),
                // Naming a location replaces the default set rather than adding to it, so a service
                // configuration on the class path cannot be picked up by accident. Without this the
                // arm that loads nothing loaded whichever service jar came first and read its
                // declaration, which is the one thing that arm has to be free of.
                "spring.config.location=" + (configuration.isEmpty()
                        ? "optional:file:./harness-loads-no-configuration/"
                        : "file:" + configuration),
                "logging.structured.format.file=logstash",
                "logging.file.name=" + output,
                "logging.structured.json.add.service=${spring.application.name}");

        try (ConfigurableApplicationContext context = builder.build().run()) {
            // The exact call SeekUtils makes once the backoff is spent. A recoverer that rethrows
            // reaches it, and every recoverer in this platform rethrows so the container is told the
            // record was not recovered and at-least-once delivery survives.
            IllegalStateException failure =
                    new IllegalStateException("PAN-4111111111111111-CVV-737");
            new LogAccessor("org.springframework.kafka.listener.KafkaMessageListenerContainer")
                    .error(failure, () -> "Backoff exhausted for transaction.authorized-3@4711");

            // The shape a service writes for the same delivery: a type chain, coordinates, no
            // throwable. It shares the file so the parent can see the sanitized report survives.
            LoggerFactory.getLogger("com.carddemo.ledger.messaging.Harness")
                    .error("Routing one record to the dead-letter topic. topic={} partition={}"
                                    + " offset={} reason={}",
                            "transaction.authorized", 3, 4711L, "IllegalStateException");

            // How the framework names the record in the message of the line above it. Withholding
            // the throwable member is sufficient only while this stays coordinates: were it to
            // render the value, the payload would reach the message, which no exclusion covers.
            ConsumerRecord<String, String> delivery = new ConsumerRecord<>(
                    "transaction.authorized", 3, 4711L, "00000000011",
                    "{\"cardNumber\":\"4111111111111111\",\"amount\":\"94.33\"}");
            LoggerFactory.getLogger("com.carddemo.ledger.messaging.Harness")
                    .warn("The framework names this delivery {}", KafkaUtils.format(delivery));
        }
    }
}
