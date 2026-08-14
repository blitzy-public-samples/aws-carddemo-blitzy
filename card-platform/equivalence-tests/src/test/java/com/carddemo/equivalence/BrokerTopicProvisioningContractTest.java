package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requires every topic either deployment path authorizes to also exist there.
 *
 * <p>Both provisioning programs do two separate things to a topic. {@code create_topics} brings it
 * into being, and {@code grant_producer} or {@code grant_consumer} installs an access control entry
 * naming it. Nothing connects the two lists, and both broker configurations set
 * {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE} to {@code false}, so a name that is granted and never
 * created is a destination no publish can reach.
 *
 * <p>That gap is not hypothetical and it is why this test exists. When the ledger service gained the
 * listener that writes the 430-byte reject row of {@code app/cbl/CBTRN02C.cbl:L446-L465}, both
 * matrices correctly granted it {@code WRITE} on the dead-letter topic of the declined stream, and
 * neither creation list gained that name. The service's own
 * {@code config/BrokerAccessContractTest} asserts the grant and says nothing about creation, so the
 * build stayed green. The failure would have surfaced only at the moment a poison declined record
 * needed somewhere to go, which is the moment a diagnostic matters most.
 *
 * <p>Direction is not inspected. A topic named in any entry, as a producer destination or as a
 * consumer source, must exist, because an entry naming a topic that does not exist is either a
 * destination that fails or an entry with no subject.
 *
 * <p>Names are compared as the variable expressions the programs write rather than as resolved
 * strings. Both files read every topic name from the environment, so
 * {@code ${TOPIC_TRANSACTION_DECLINED}${TOPIC_DEAD_LETTER_SUFFIX}} is the identity of the composed
 * destination in both lists, and comparing expressions keeps this test independent of the values
 * {@code .env.example} and {@code deploy/k8s/30-configmap.yaml} supply.
 *
 * <p>The two paths are also held to the same inventory. A topic added to one deployment and
 * forgotten in the other is a platform that behaves differently depending on how it was started,
 * which is the harder class of defect to find.
 */
@DisplayName("Both broker matrices create every topic they authorize")
class BrokerTopicProvisioningContractTest {

    /** The compose provisioning program, which writes each expansion as {@code $${NAME}}. */
    private static final String COMPOSE = "card-platform/docker-compose.yml";

    /** The Kubernetes provisioning program, which writes each expansion as {@code ${NAME}}. */
    private static final String KAFKA_MANIFEST = "card-platform/deploy/k8s/10-kafka.yaml";

    /** Every topic expression inside a {@code create_topics} body, in declaration order. */
    private static final Pattern TOPIC_EXPRESSION =
            Pattern.compile("\"((?:\\$+\\{TOPIC_[A-Z_]+})+)\"");

    /** A topic argument of an entry-installing call. The topic is the second argument. */
    private static final Pattern GRANT = Pattern.compile(
            "grant_(?:producer|consumer)\\s+\"[^\"]+\"\\s*(?:\\\\\\s*\\n\\s*)?"
                    + "\"((?:\\$+\\{TOPIC_[A-Z_]+})+)\"");

    /** Number words this platform's inventory prose uses, so a count change fails the build. */
    private static final Map<Integer, String> NUMBER_WORDS = Map.of(
            11, "eleven", 12, "twelve", 13, "thirteen", 14, "fourteen", 15, "fifteen",
            16, "sixteen", 17, "seventeen", 18, "eighteen", 19, "nineteen", 20, "twenty");

    @Test
    @DisplayName("every granted topic is created, in both deployment paths")
    void neitherMatrixAuthorizesATopicItDoesNotCreate() {
        for (String file : List.of(COMPOSE, KAFKA_MANIFEST)) {
            String program = read(repositoryRoot().resolve(file));
            Set<String> created = createdTopics(program);
            Set<String> granted = grantedTopics(program);

            assertTrue(created.size() >= 13,
                    file + " creates only " + created.size() + " topics, so the search for the"
                            + " creation list has drifted rather than the platform having shrunk");
            assertTrue(granted.size() >= 13,
                    file + " grants only " + granted.size() + " topics, so the search for the"
                            + " entry list has drifted");

            Set<String> ungranted = new LinkedHashSet<>(granted);
            ungranted.removeAll(created);
            assertThat(ungranted)
                    .as(file + " installs an access control entry on a topic it never creates."
                            + " KAFKA_AUTO_CREATE_TOPICS_ENABLE is false, so a publish to one of"
                            + " these fails and a diagnostic bound for it is lost")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("both deployment paths create the same topics")
    void theTwoMatricesAgreeOnTheInventory() {
        Set<String> compose = normalize(createdTopics(read(repositoryRoot().resolve(COMPOSE))));
        Set<String> manifest =
                normalize(createdTopics(read(repositoryRoot().resolve(KAFKA_MANIFEST))));

        Set<String> composeOnly = new LinkedHashSet<>(compose);
        composeOnly.removeAll(manifest);
        Set<String> manifestOnly = new LinkedHashSet<>(manifest);
        manifestOnly.removeAll(compose);

        assertThat(composeOnly).as("created by compose and not by the manifest").isEmpty();
        assertThat(manifestOnly).as("created by the manifest and not by compose").isEmpty();
        assertEquals(compose, manifest, "the two deployment paths must provision one inventory");
    }

    @Test
    @DisplayName("the inventory prose names the number of topics each program creates")
    void theProseCountsWhatTheProgramCreates() {
        for (String file : List.of(COMPOSE, KAFKA_MANIFEST)) {
            String program = read(repositoryRoot().resolve(file));
            int created = createdTopics(program).size();
            String word = NUMBER_WORDS.get(created);
            assertTrue(word != null,
                    file + " creates " + created + " topics and this test knows no word for that"
                            + " number, so the prose cannot be checked");
            assertTrue(program.contains("the " + word + "\n"),
                    file + " creates " + created + " topics, so its prose must say \"the " + word
                            + "\" and it does not. A creation list grows without its comment"
                            + " otherwise, which is how the counts drifted before");
            assertTrue(program.contains("These " + word + " names are the platform's topic"
                            + " inventory"),
                    file + " must state the inventory as " + word + " names");
        }
    }

    /**
     * Returns the topic expressions one program creates.
     *
     * <p>Only the {@code for} list is read, not the whole function body. The
     * {@code kafka-topics.sh} call inside the loop names {@code TOPIC_PARTITIONS} and
     * {@code TOPIC_REPLICATION_FACTOR}, which match the shape of a topic expression and are
     * settings rather than topics.
     *
     * @param program the provisioning program text
     * @return each expression of the creation list, in declaration order
     */
    private static Set<String> createdTopics(String program) {
        String body = bodyOf(program, "create_topics() {");
        int start = body.indexOf("for topic in");
        assertTrue(start >= 0, "create_topics declares no creation list");
        int end = body.indexOf("; do", start);
        assertTrue(end > start, "the creation list is never closed");
        return expressionsIn(body.substring(start, end), TOPIC_EXPRESSION);
    }

    /**
     * Returns the topic expressions one program installs an entry on.
     *
     * @param program the provisioning program text
     * @return each expression named by a {@code grant_producer} or {@code grant_consumer} call
     */
    private static Set<String> grantedTopics(String program) {
        return expressionsIn(bodyOf(program, "create_acls() {"), GRANT);
    }

    /**
     * Reads the body of one shell function, ending at the line that closes it.
     *
     * <p>Both programs indent a function body and close it with a brace at the indentation of the
     * declaration, so the body ends at the first following line whose only content is that brace.
     *
     * @param program the provisioning program text
     * @param opening the function's opening line, brace included
     * @return the text between the opening line and the closing brace
     */
    private static String bodyOf(String program, String opening) {
        int start = program.indexOf(opening);
        assertTrue(start >= 0, "the program declares no " + opening);
        int indent = 0;
        while (start - indent - 1 >= 0 && program.charAt(start - indent - 1) == ' ') {
            indent = indent + 1;
        }
        String closing = "\n" + " ".repeat(indent) + "}\n";
        int end = program.indexOf(closing, start);
        assertTrue(end > start, opening + " is never closed at its own indentation");
        return program.substring(start + opening.length(), end);
    }

    /**
     * Collects the first capturing group of every match, preserving order.
     *
     * @param body    the text to search
     * @param pattern a pattern whose first group is a topic expression
     * @return the distinct expressions found
     */
    private static Set<String> expressionsIn(String body, Pattern pattern) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * Reduces a compose expression to the manifest's spelling of the same name.
     *
     * <p>Compose doubles the dollar so the value survives its own interpolation and reaches the
     * shell. The manifest needs no such escape. Both mean one variable.
     *
     * @param expressions expressions in either spelling
     * @return the same expressions with every run of dollars reduced to one
     */
    private static Set<String> normalize(Set<String> expressions) {
        Set<String> reduced = new LinkedHashSet<>();
        for (String expression : expressions) {
            reduced.add(expression.replaceAll("\\$+\\{", "\\${"));
        }
        return reduced;
    }

    /**
     * Finds the repository root from wherever the build was started.
     *
     * @return the directory holding {@code card-platform/docker-compose.yml}
     */
    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null
                && !Files.isRegularFile(current.resolve("card-platform/docker-compose.yml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new AssertionError("repository root was not found");
        }
        return current;
    }

    /**
     * Reads one file as text.
     *
     * @param path the file to read
     * @return its content
     */
    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
