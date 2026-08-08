package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves every Kafka template of every service records a failed send safely.
 *
 * <p>A {@code KafkaTemplate} installs {@code LoggingProducerListener} unless it is told otherwise,
 * and that listener writes the message key and the first hundred characters of the payload into the
 * log line. On this platform the key is the account identifier, because
 * {@code app/cbl/CBTRN02C.cbl:L385-L387} resolves a card to an account and the account is what keeps
 * one account's events in one partition, and the payload is a domain event. A broker outage is
 * exactly when a send fails and every retry writes another line, so the default turns one outage
 * into a durable copy of production traffic wherever logs are retained.
 *
 * <p>Each service therefore declares a {@code SafeProducerListener} and installs it on every
 * template it builds. The class is package-private in each service, so this class cannot call it and
 * does not try to: the per-service {@code SafeProducerListenerTest} exercises the behaviour, and this
 * class reads the shipped configuration sources and asserts the wiring. Reading the text is what
 * makes the assertion cover a template added later, which no test of today's beans could do.
 *
 * <p>Three properties are asserted. Every template construction is matched by an installation in the
 * same file, so a new template cannot be left on the default. No source names the default listener or
 * the switch that only trims its payload, because trimming the payload still logs the key and the
 * throwable. And every service ships the listener, so none of the six can satisfy the count by
 * building no template at all.
 */
@DisplayName("Producer failure logging, every service")
class ProducerFailureLoggingContractTest {

    /** Directory below the repository root holding the six service modules. */
    private static final String SERVICES_DIRECTORY = "card-platform/services";

    /** Path below a service module to its main Java sources. */
    private static final String MAIN_SOURCES = "src/main/java";

    /** The six service module directory names. */
    private static final List<String> MODULES = List.of(
            "authorization-service", "ledger-posting-service", "fraud-detection-service",
            "notification-service", "account-service", "card-service");

    /** Matches one template construction. */
    private static final String TEMPLATE_CONSTRUCTION = "new KafkaTemplate<>(";

    /** Matches the installation that displaces the default listener. */
    private static final String SAFE_LISTENER_INSTALLATION =
            "setProducerListener(new SafeProducerListener<>())";

    /**
     * Uses of the default listener, as code rather than as prose.
     *
     * <p>The tokens are the construction, a declared type and the switch that only trims the
     * payload. Naming the class in a comment is how the shipped code explains what it replaced, so
     * comments are stripped before this list is applied and a mention is not a use.
     */
    private static final List<String> FORBIDDEN_USES = List.of(
            "new LoggingProducerListener", "LoggingProducerListener<", "LoggingProducerListener(",
            "setIncludeContents");

    /** Matches one line comment. */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** Matches one block comment, which is how every class here carries its javadoc. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** Matches the log call of a safe listener, so its arguments can be read. */
    private static final Pattern LISTENER_LOG_CALL = Pattern.compile(
            "LOG\\.error\\((.*?)\\);", Pattern.DOTALL);

    /**
     * Matches a bare throwable passed as the final argument of the log call.
     *
     * <p>A throwable in that position is what attaches a stack trace to the event, and a stack trace
     * renders the exception message. {@code failureType(failure)} is the sanctioned form and does not
     * match, because the name is followed by a closing parenthesis of its own call rather than by the
     * end of the argument list.
     */
    private static final Pattern BARE_THROWABLE_ARGUMENT = Pattern.compile(
            ",\\s*(failure|fault|exception|cause|throwable)\\s*$");

    /** Reads of the record's key or value, either of which is producer-supplied content. */
    private static final List<String> FORBIDDEN_LOG_READS = List.of(".key()", ".value()");

    @Test
    @DisplayName("every template a service builds installs the safe listener")
    void everyTemplateInstallsTheSafeListener() {
        Map<String, String> mismatched = new LinkedHashMap<>();
        int templates = 0;

        for (String module : MODULES) {
            for (Path source : sourcesOf(module)) {
                String text = readText(source);
                int constructions = occurrences(text, TEMPLATE_CONSTRUCTION);
                if (constructions == 0) {
                    continue;
                }
                templates += constructions;
                int installations = occurrences(text, SAFE_LISTENER_INSTALLATION);
                if (installations != constructions) {
                    mismatched.put(module + " " + source.getFileName(),
                            constructions + " templates against " + installations
                                    + " installations");
                }
            }
        }

        assertEquals(Map.of(), mismatched,
                "a template left on the default listener logs the message key and the first "
                        + "hundred characters of the payload on every failed send: " + mismatched);
        assertTrue(templates >= MODULES.size(),
                "each of the six services builds at least one template, and only " + templates
                        + " were found, so this comparison read the wrong sources");
    }

    @Test
    @DisplayName("no service names the default listener or trims it instead of replacing it")
    void noServiceNamesTheDefaultListener() {
        List<String> naming = new ArrayList<>();

        for (String module : MODULES) {
            for (Path source : sourcesOf(module)) {
                String code = withoutComments(readText(source));
                for (String forbidden : FORBIDDEN_USES) {
                    if (code.contains(forbidden)) {
                        naming.add(module + " " + source.getFileName() + " uses " + forbidden);
                    }
                }
            }
        }

        assertEquals(List.of(), naming,
                "trimming the default listener's payload still logs the key and the throwable, so "
                        + "the default is replaced rather than configured: " + naming);
    }

    @Test
    @DisplayName("every service ships a safe listener that logs no key, no value and no throwable")
    void everyServiceShipsAListenerThatLogsNothingSensitive() {
        List<String> offending = new ArrayList<>();

        for (String module : MODULES) {
            Path listener = sourcesOf(module).stream()
                    .filter(path -> path.getFileName().toString()
                            .equals("SafeProducerListener.java"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            module + " ships no SafeProducerListener, so its templates name a "
                                    + "listener that does not exist"));

            String code = withoutComments(readText(listener));
            Matcher call = LISTENER_LOG_CALL.matcher(code);
            assertTrue(call.find(), module + " listener logs nothing on a failed send");
            String arguments = collapse(call.group(1));

            if (BARE_THROWABLE_ARGUMENT.matcher(arguments).find()) {
                offending.add(module + " passes the throwable itself to its logger");
            }
            for (String forbidden : FORBIDDEN_LOG_READS) {
                if (arguments.contains(forbidden)) {
                    offending.add(module + " reads " + forbidden + " of the failed record");
                }
            }
            if (!arguments.contains("failureType(")) {
                offending.add(module + " records no failure type, so a reader learns nothing");
            }
        }

        assertEquals(List.of(), offending,
                "a listener that passes the key, the value or the throwable itself defeats the "
                        + "purpose of replacing the default: " + offending);
    }

    /**
     * Lists every main Java source of one module.
     *
     * @param module the service module directory name
     * @return every {@code .java} file below its main source root
     */
    private static List<Path> sourcesOf(String module) {
        Path root = repositoryRoot().resolve(SERVICES_DIRECTORY).resolve(module)
                .resolve(MAIN_SOURCES);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("no main sources sit at " + root);
        }
        try (Stream<Path> entries = Files.walk(root)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + root, unreadable);
        }
    }

    /**
     * Returns one source with its comments removed.
     *
     * <p>Every class here explains in prose what it replaced, and naming the default listener in a
     * javadoc paragraph is documentation rather than a use of it. Stripping comments is what lets
     * this class assert on the code and leave the prose alone.
     *
     * @param source the source text
     * @return the same text with block and line comments removed
     */
    private static String withoutComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }

    /**
     * Collapses whitespace runs to one space, so an argument list wrapped across lines reads as one.
     *
     * @param text the text to collapse
     * @return the collapsed and trimmed text
     */
    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Counts non-overlapping occurrences of one token.
     *
     * @param text  the text to read
     * @param token the token to count
     * @return how many times it occurs
     */
    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    /**
     * Reads a file as text, turning the checked failure into an unchecked one.
     *
     * @param path the file to read
     * @return its contents
     */
    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
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
