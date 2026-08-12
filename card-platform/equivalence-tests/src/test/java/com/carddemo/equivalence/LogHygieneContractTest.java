package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves no service writes a throwable or a cardholder identifier into an ordinary log line.
 *
 * <p>Two habits put data into logs, and both are invisible in a code review that reads for logic.
 * The first is passing the throwable to the logger: the appender then renders its message, and a
 * message quotes what caused the failure. A unique-constraint violation quotes the value that
 * collided, a query timeout quotes the statement, and a connection failure quotes the data-source
 * URL with its user. The second is passing an identifier as a placeholder argument, which is how an
 * account identifier reaches whatever collects and retains logs. Both are CWE-532.
 *
 * <p>Every service therefore renders a failure as its type and the types of its causes, through a
 * private {@code failureType} method, and names an event by its event identifier rather than by its
 * subject. A type is code and an event identifier is a value this platform generates, so neither
 * describes a cardholder.
 *
 * <p>This class reads the shipped sources rather than running them. A behavioural test proves one
 * site behaves; reading every site proves the rule holds at all of them, including the site somebody
 * adds next, which is the property that decays first. Comments are stripped before the rules are
 * applied, because a comment that explains why a throwable is withheld is documentation rather than
 * a use of one.
 *
 * <p>Reading sources cannot hold a call this platform does not write, and one such call renders a
 * throwable this platform never passes: Spring Kafka reports a delivery it has given up on through
 * {@code LogAccessor.error(Throwable, Supplier)}. {@link #everyServiceWithholdsTheThrowableMember}
 * holds the configuration that suppresses that rendering in all six services, and
 * {@link FrameworkThrowableRenderingTest} proves the configuration does suppress it by running the
 * call and reading what was written. The two together cover both halves: what is declared, and what
 * that declaration does.
 *
 * <p>The rule is the framework's own. SLF4J attaches the final argument as a throwable when the
 * argument list carries one more value than the message carries placeholders, and substitutes it
 * otherwise. Reading that arity is what tells {@code log.error("failed", fault)} from
 * {@code log.info("published {} and failed {}", published, failed)}, whose final argument is an
 * {@code int}. Deciding by variable name was tried first and reported the counter, which is how a
 * rule teaches a reader to ignore it.
 * {@link #theRuleReadsThePlaceholderCountRatherThanTheName} proves the rule separates the four
 * shapes, so a rule that matched nothing could not pass by finding nothing.
 */
@DisplayName("Log hygiene, every service")
class LogHygieneContractTest {

    /** Directory below the repository root holding the six service modules. */
    private static final String SERVICES_DIRECTORY = "card-platform/services";

    /** Path below a service module to its main Java sources. */
    private static final String MAIN_SOURCES = "src/main/java";

    /** The six service module directory names. */
    private static final List<String> MODULES = List.of(
            "authorization-service", "ledger-posting-service", "fraud-detection-service",
            "notification-service", "account-service", "card-service");

    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** Matches one block comment, which is how every class carries its javadoc. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** Matches one logger call and captures its argument list. */
    private static final Pattern LOGGER_CALL = Pattern.compile(
            "\\b(?:LOG|log|LOGGER)\\s*\\.\\s*(?:error|warn|info|debug|trace)\\s*\\((.*?)\\)\\s*;",
            Pattern.DOTALL);

    /**
     * Matches one string literal, so a message built by concatenation can be read as one.
     *
     * <p>Every message in this codebase is a literal or a run of literals joined with {@code +}, and
     * the placeholders live inside them.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /** The placeholder SLF4J substitutes one argument into. */
    private static final String PLACEHOLDER = "{}";

    /** Path below a service module to its configuration. */
    private static final String APPLICATION_YAML = "src/main/resources/application.yml";

    /**
     * Matches the level one service sets for its own packages, and captures the whole declaration.
     *
     * <p>Three services write the level as one placeholder and three nest a second placeholder
     * inside the first, so the declaration rather than one default is captured and read.
     */
    private static final Pattern CARDDEMO_LOG_LEVEL =
            Pattern.compile("com\\.carddemo:\\s*(\"?\\$\\{[^\\n]*)");

    /** Reads of an identifier that describes a cardholder rather than an event. */
    private static final List<String> CARDHOLDER_READS = List.of(
            ".accountId()", ".customerId()", ".cardNumber()", ".visibleDigitsSuffix()",
            ".socialSecurityNumber()", ".cardVerificationValue()");

    /**
     * Sites permitted to name a cardholder identifier in a log argument, each with its reason.
     *
     * <p>Both entries mask before they log. The ledger listener replaces every digit of the account
     * with an asterisk, and the card update service logs a masked card. A masked value carries no
     * identifier, and rewriting either would be a change with no security effect, so the rule names
     * them rather than pretending they do not read the field.
     */
    private static final List<String> MASKING_SITES = List.of(
            "ledger-posting-service AccountStateChangedConsumer.java",
            "card-service CardUpdateService.java");

    /**
     * The one logstash member that renders a throwable, and the one every service withholds.
     *
     * <p>Every rule above governs a call this platform writes. This one governs a call it does not:
     * Spring Kafka reports a delivery it has given up on through
     * {@code LogAccessor.error(Throwable, Supplier)} from {@code SeekUtils}, whose level no property
     * lowers, and the member renders the exception message ahead of the frames.
     */
    private static final String THROWABLE_MEMBER = "stack_trace";

    /** Path below {@code logging.structured.json} that withholds a member from every record. */
    private static final Pattern WITHHELD_MEMBERS =
            Pattern.compile("^\\s*exclude:\\s*$\\n(?:^\\s*#[^\\n]*$\\n)*((?:^\\s*-\\s*\\S+\\s*$\\n)+)",
                    Pattern.MULTILINE);

    @Test
    @DisplayName("no logger call anywhere passes the throwable itself")
    void noLoggerCallPassesTheThrowableItself() {
        List<String> offending = new ArrayList<>();

        for (String module : MODULES) {
            for (Path source : sourcesOf(module)) {
                String code = withoutComments(readText(source));
                Matcher call = LOGGER_CALL.matcher(code);
                while (call.find()) {
                    String arguments = collapse(call.group(1));
                    if (carriesATrailingThrowable(arguments)) {
                        offending.add(module + " " + source.getFileName() + ": " + arguments);
                    }
                }
            }
        }

        assertEquals(List.of(), offending,
                "a throwable passed to a logger is rendered with its message, and a message quotes "
                        + "the value, the statement or the data source that caused the failure: "
                        + offending);
    }

    @Test
    @DisplayName("every service renders a failure as its type chain")
    void everyServiceRendersAFailureAsItsTypeChain() {
        List<String> withoutRenderer = new ArrayList<>();

        for (String module : MODULES) {
            boolean renders = sourcesOf(module).stream()
                    .map(LogHygieneContractTest::readText)
                    .anyMatch(text -> text.contains("private static String failureType("));
            if (!renders) {
                withoutRenderer.add(module);
            }
        }

        assertEquals(List.of(), withoutRenderer,
                "a service that logs a failure at all has to render it as a type rather than as a "
                        + "throwable, and these declare no renderer: " + withoutRenderer);
    }

    @Test
    @DisplayName("no ordinary log line names a cardholder identifier unmasked")
    void noOrdinaryLogLineNamesACardholderIdentifier() {
        List<String> offending = new ArrayList<>();

        for (String module : MODULES) {
            for (Path source : sourcesOf(module)) {
                String site = module + " " + source.getFileName();
                if (MASKING_SITES.contains(site)) {
                    continue;
                }
                String code = withoutComments(readText(source));
                Matcher call = LOGGER_CALL.matcher(code);
                while (call.find()) {
                    String arguments = collapse(call.group(1));
                    for (String read : CARDHOLDER_READS) {
                        if (arguments.contains(read)) {
                            offending.add(site + " logs " + read);
                        }
                    }
                }
            }
        }

        assertEquals(List.of(), offending,
                "an identifier in a log line is retained by whatever collects logs, and the event "
                        + "identifier already leads a reader to the event that carries it: "
                        + offending);
    }

    @Test
    @DisplayName("each masking site named as permitted still masks")
    void eachPermittedSiteStillMasks() {
        for (String site : MASKING_SITES) {
            String[] parts = site.split(" ", 2);
            Path source = sourcesOf(parts[0]).stream()
                    .filter(path -> path.getFileName().toString().equals(parts[1]))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            site + " is permitted to log an identifier and does not exist, so the "
                                    + "permission outlived the code it was written for"));

            String code = withoutComments(readText(source));
            assertTrue(code.contains("masked") || code.contains("Masked"),
                    site + " is permitted because it masks, and it no longer does");
        }
    }

    @Test
    @DisplayName("every service defaults its own packages to INFO rather than DEBUG")
    void everyServiceDefaultsItsOwnPackagesToInfo() {
        List<String> offending = new ArrayList<>();

        for (String module : MODULES) {
            Path configuration = repositoryRoot().resolve(SERVICES_DIRECTORY).resolve(module)
                    .resolve(APPLICATION_YAML);
            String text = readText(configuration);
            Matcher level = CARDDEMO_LOG_LEVEL.matcher(text);
            assertTrue(level.find(),
                    module + " sets no level for its own packages, so the root level decides");
            String declaration = level.group(1);
            if (declaration.contains("DEBUG") || declaration.contains("TRACE")) {
                offending.add(module + " defaults to " + declaration.trim());
            }
        }

        assertEquals(List.of(), offending,
                "a default is what an operator gets who starts a service directly rather than "
                        + "through compose or Kubernetes, and a DEBUG default writes identifiers "
                        + "into every log a deployment collects: " + offending);
    }

    @Test
    @DisplayName("every service withholds the one member that renders a throwable")
    void everyServiceWithholdsTheThrowableMember() {
        List<String> offending = new ArrayList<>();

        for (String module : MODULES) {
            Path configuration = repositoryRoot().resolve(SERVICES_DIRECTORY).resolve(module)
                    .resolve(APPLICATION_YAML);
            Matcher withheld = WITHHELD_MEMBERS.matcher(readText(configuration));
            if (!withheld.find()) {
                offending.add(module + " withholds no member at all");
                continue;
            }
            if (!withheld.group(1).contains(THROWABLE_MEMBER)) {
                offending.add(module + " withholds " + collapse(withheld.group(1)));
            }
        }

        assertEquals(List.of(), offending,
                "the rules above hold every call this platform writes, and none of them reaches the "
                        + "call Spring Kafka writes when it gives up on a delivery: withholding "
                        + THROWABLE_MEMBER + " is what keeps an exception message quoting a rejected "
                        + "payload out of the log, and these do not withhold it: " + offending);
    }

    @Test
    @DisplayName("the rule reads the placeholder count rather than the argument name")
    void theRuleReadsThePlaceholderCountRatherThanTheName() {
        String rendered = "\"A send to topic {} partition {} failed. The failure was {}.\","
                + " topicOf(record, metadata), partitionOf(record, metadata),"
                + " failureType(failure)";
        String attached = "\"A card request failed inside this service\", fault";
        String counted = "\"Outbox relay published {} rows this tick and failed {}\","
                + " published, failed";
        String withPlaceholdersAndThrowable = "\"The route {} answered {}\", route, status, fault";

        assertEquals(false, carriesATrailingThrowable(rendered),
                "three placeholders and three arguments, so the type chain is a value like any"
                        + " other and nothing is attached");
        assertEquals(true, carriesATrailingThrowable(attached),
                "no placeholder and one argument, so SLF4J treats it as a throwable and the"
                        + " appender renders its message");
        assertEquals(false, carriesATrailingThrowable(counted),
                "two placeholders and two arguments, so a final argument named failed is a counter"
                        + " and not a throwable, which is why the name cannot decide");
        assertEquals(true, carriesATrailingThrowable(withPlaceholdersAndThrowable),
                "two placeholders and three arguments, so the third is attached");
    }

    /**
     * Applies the rule to one argument list.
     *
     * <p>SLF4J attaches the final argument as a throwable when the argument list carries one more
     * value than the message carries placeholders. That is the rule the framework itself applies, so
     * it is the rule read here: it needs no knowledge of a variable's type and cannot be fooled by a
     * counter that happens to share a name with a caught failure.
     *
     * @param arguments the collapsed argument list of one logger call
     * @return whether the final argument is attached rather than substituted
     */
    private static boolean carriesATrailingThrowable(String arguments) {
        List<String> parts = topLevelArguments(arguments);
        if (parts.size() < 2) {
            return false;
        }
        String message = parts.getFirst();
        int placeholders = 0;
        Matcher literal = STRING_LITERAL.matcher(message);
        while (literal.find()) {
            placeholders += occurrences(literal.group(1), PLACEHOLDER);
        }
        return parts.size() - 1 > placeholders;
    }

    /**
     * Splits one argument list at the commas that sit outside quotes and parentheses.
     *
     * @param arguments the collapsed argument list
     * @return the arguments, message first
     */
    private static List<String> topLevelArguments(String arguments) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < arguments.length(); index++) {
            char character = arguments.charAt(index);
            if (character == '"' && (index == 0 || arguments.charAt(index - 1) != '\\')) {
                quoted = !quoted;
            }
            if (!quoted && (character == '(' || character == '[')) {
                depth++;
            }
            if (!quoted && (character == ')' || character == ']')) {
                depth--;
            }
            if (!quoted && depth == 0 && character == ',') {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        return List.copyOf(parts);
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
