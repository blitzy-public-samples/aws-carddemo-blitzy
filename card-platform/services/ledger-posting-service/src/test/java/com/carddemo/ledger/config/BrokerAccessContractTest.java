package com.carddemo.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Holds the broker authorization matrices to the topics this service is configured to reach.
 *
 * <p>The Apache Kafka authorizer refuses any operation no access control entry allows, so a topic
 * this service addresses without an entry fails every attempt. That failure is quiet in the worst
 * possible way for the transactional outbox: {@code outbox/OutboxRelay} sweeps rows oldest first
 * inside one transaction and returns at its first failure, so a single row bound for an ungranted
 * topic is refused on every sweep and every later row of every account stays behind it for ever.
 *
 * <p>Reviewing the two matrices by eye is what let that happen once already: the relay maps
 * {@code TransactionDeclined} onto its own topic, and neither matrix granted it. This test is the
 * replacement for that review. It reads the topic names out of the shipped
 * {@code src/main/resources/application.yml} rather than restating them, so adding a topic to the
 * configuration fails the build until both {@code card-platform/docker-compose.yml} and
 * {@code card-platform/deploy/k8s/10-kafka.yaml} grant it.
 *
 * <p>Direction is derived from the key, not asserted from a list. {@code transaction-authorized} is
 * the one topic this service reads, and it must carry a consumer entry naming this service's own
 * group. Every other topic under {@code carddemo.kafka.topics} is a destination this service
 * writes. {@code dead-letter-suffix} is not itself a topic: the error handler appends it to the
 * consumed topic, so both matrices must grant that composed destination.
 *
 * <p>Both deployment paths read topic names through environment variables, so each matrix is checked
 * against the variable declared by the shipped service configuration. This keeps a ConfigMap topic
 * override aligned with the broker provisioning and access-control commands.
 */
@DisplayName("The broker authorization matrices grant every topic this service is configured to "
        + "reach")
class BrokerAccessContractTest {

    /** The shipped configuration this test reads the topic names out of. */
    private static final String APPLICATION_YAML = "src/main/resources/application.yml";

    /** The compose file carrying one of the two authorization matrices. */
    private static final String COMPOSE_FILE = "docker-compose.yml";

    /** The Kubernetes manifest carrying the other. */
    private static final String KAFKA_MANIFEST = "deploy/k8s/10-kafka.yaml";

    /** The principal placeholder both matrices name this service by. */
    private static final String PRINCIPAL = "LEDGER_KAFKA_USER";

    /** The consumer group placeholder both matrices name this service's group by. */
    private static final String GROUP = "GROUP_LEDGER_POSTING";

    /** The consumer group of the second listener, which keeps the balance projection current. */
    private static final String ACCOUNT_STATE_GROUP = "GROUP_LEDGER_ACCOUNT";

    /**
     * Each key under {@code carddemo.kafka.topics} this service reads, mapped to the group it reads
     * under.
     *
     * <p>Two listeners, two groups. The posting listener reads the authorization event, and the
     * account-state listener keeps {@code account_balance_projection} current so an account opened
     * after deployment gets a row and a closed billing cycle zeroes the two accumulators here.
     *
     * <p>Every other key under that block is a destination this service writes. Each consumed key
     * needs three entries between the two matrices: a consumer entry naming its own group, and a
     * producer entry on its composed dead-letter destination.
     */
    private static final Map<String, String> CONSUMED_KEYS = Map.of(
            "transaction-authorized", GROUP,
            "account-state-changed", ACCOUNT_STATE_GROUP);

    /** A suffix composed with each of {@link #CONSUMED_KEYS}, not an addressable topic itself. */
    private static final String DEAD_LETTER_SUFFIX_KEY = "dead-letter-suffix";

    /** Reads {@code ${NAME:default}}, and also {@code ${OUTER:${NAME:default}}}. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z0-9_]+):([^{}]*)}");

    @Nested
    @DisplayName("docker-compose.yml")
    class Compose {

        @Test
        @DisplayName("grants a producer entry for every destination topic, by variable")
        void grantsEveryDestination() {
            String matrix = read(platformRoot().resolve(COMPOSE_FILE));

            for (Map.Entry<String, Topic> destination : destinations().entrySet()) {
                String expected = "grant_producer \"$${" + PRINCIPAL + "}\" \"$${"
                        + destination.getValue().variable() + "}\"";
                assertThat(matrix)
                        .as("carddemo.kafka.topics.%s is a destination of this service, so "
                                        + "%s must carry %s", destination.getKey(), COMPOSE_FILE,
                                expected)
                        .contains(expected);
            }
        }

        @Test
        @DisplayName("grants the source-specific dead-letter destination")
        void grantsTheSourceSpecificDeadLetterDestination() {
            Map<String, Topic> topics = topics();
            Topic suffix = topics.get(DEAD_LETTER_SUFFIX_KEY);
            String matrix = collapseContinuations(read(platformRoot().resolve(COMPOSE_FILE)));

            for (String consumedKey : CONSUMED_KEYS.keySet()) {
                Topic consumed = topics.get(consumedKey);
                String expected = "grant_producer \"$${" + PRINCIPAL + "}\" \"$${"
                        + consumed.variable() + "}$${" + suffix.variable() + "}\"";
                assertThat(matrix)
                        .as("%s is consumed, so %s must let this service write its dead-letter "
                                + "destination %s", consumedKey, COMPOSE_FILE, expected)
                        .contains(expected);
            }
        }

        @Test
        @DisplayName("grants one consumer entry for the consumed topic under this service's group")
        void grantsTheConsumedTopic() {
            String matrix = collapseContinuations(read(platformRoot().resolve(COMPOSE_FILE)));
            Map<String, Topic> topics = topics();

            for (Map.Entry<String, String> consumed : CONSUMED_KEYS.entrySet()) {
                Topic topic = topics.get(consumed.getKey());
                String expected = "grant_consumer \"$${" + PRINCIPAL + "}\" \"$${"
                        + topic.variable() + "}\" \"$${" + consumed.getValue() + "}\"";
                assertThat(matrix)
                        .as("%s must let this service read %s under %s", COMPOSE_FILE,
                                topic.variable(), consumed.getValue())
                        .contains(expected);
            }
        }
    }

    @Nested
    @DisplayName("deploy/k8s/10-kafka.yaml")
    class Kubernetes {

        @Test
        @DisplayName("grants a producer entry for every destination topic, by ConfigMap variable")
        void grantsEveryDestination() {
            String matrix = collapseContinuations(read(platformRoot().resolve(KAFKA_MANIFEST)));

            for (Map.Entry<String, Topic> destination : destinations().entrySet()) {
                String expected = "grant_producer \"${" + PRINCIPAL + "}\" \"${"
                        + destination.getValue().variable() + "}\"";
                assertThat(matrix)
                        .as("carddemo.kafka.topics.%s is a destination of this service, so "
                                        + "%s must carry %s", destination.getKey(), KAFKA_MANIFEST,
                                expected)
                        .contains(expected);
            }
        }

        @Test
        @DisplayName("grants the source-specific dead-letter destination")
        void grantsTheSourceSpecificDeadLetterDestination() {
            Map<String, Topic> topics = topics();
            Topic suffix = topics.get(DEAD_LETTER_SUFFIX_KEY);
            String matrix = collapseContinuations(read(platformRoot().resolve(KAFKA_MANIFEST)));

            for (String consumedKey : CONSUMED_KEYS.keySet()) {
                Topic consumed = topics.get(consumedKey);
                String expected = "grant_producer \"${" + PRINCIPAL + "}\" \"${"
                        + consumed.variable() + "}${" + suffix.variable() + "}\"";
                assertThat(matrix)
                        .as("%s is consumed, so %s must let this service write its dead-letter "
                                + "destination %s", consumedKey, KAFKA_MANIFEST, expected)
                        .contains(expected);
            }
        }

        @Test
        @DisplayName("grants one consumer entry for the consumed topic under this service's group")
        void grantsTheConsumedTopic() {
            String matrix = collapseContinuations(read(platformRoot().resolve(KAFKA_MANIFEST)));
            Map<String, Topic> topics = topics();

            for (Map.Entry<String, String> consumed : CONSUMED_KEYS.entrySet()) {
                Topic topic = topics.get(consumed.getKey());
                String expected = "grant_consumer \"${" + PRINCIPAL + "}\" \"${"
                        + topic.variable() + "}\" \"${" + consumed.getValue() + "}\"";
                assertThat(matrix)
                        .as("%s must let this service read %s under %s", KAFKA_MANIFEST,
                                topic.variable(), consumed.getValue())
                        .contains(expected);
            }
        }
    }

    @Nested
    @DisplayName("The shipped configuration")
    class ShippedConfiguration {

        @Test
        @DisplayName("names the consumed topic and at least one destination")
        void namesBothDirections() {
            Map<String, Topic> topics = topics();

            assertThat(topics).as("carddemo.kafka.topics must name every consumed topic")
                    .containsKeys(CONSUMED_KEYS.keySet().toArray(String[]::new));
            assertThat(destinations()).as("this service publishes, so it must name a destination")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("names the declined topic, which the relay maps and the matrices missed once")
        void namesTheDeclinedTopic() {
            assertThat(destinations()).containsKey("transaction-declined");
        }
    }

    /** Every topic the shipped configuration names, keyed by its property key. */
    private static Map<String, Topic> topics() {
        Object topics = descend(parseYaml(read(moduleBase().resolve(APPLICATION_YAML))),
                "carddemo", "kafka", "topics");
        if (!(topics instanceof Map<?, ?> mapping)) {
            return fail(APPLICATION_YAML + " carries no carddemo.kafka.topics mapping");
        }

        Map<String, Topic> named = new LinkedHashMap<>();
        mapping.forEach((key, value) -> named.put(String.valueOf(key), topicOf(key, value)));
        return named;
    }

    /** Every independently addressable topic this service writes. */
    private static Map<String, Topic> destinations() {
        Map<String, Topic> destinations = new LinkedHashMap<>(topics());
        CONSUMED_KEYS.keySet().forEach(destinations::remove);
        destinations.remove(DEAD_LETTER_SUFFIX_KEY);
        return destinations;
    }

    /** Reads the innermost variable name and the default out of one configured topic value. */
    private static Topic topicOf(Object key, Object value) {
        String text = String.valueOf(value);
        Matcher placeholders = PLACEHOLDER.matcher(text);
        List<Topic> found = new ArrayList<>();

        while (placeholders.find()) {
            found.add(new Topic(placeholders.group(1), placeholders.group(2).trim()));
        }
        if (found.size() != 1) {
            return fail("carddemo.kafka.topics." + key + " must resolve one ${NAME:default}"
                    + " placeholder, and it carries " + found.size());
        }
        return found.getFirst();
    }

    /**
     * Joins backslash line continuations so a two-line shell call reads as one.
     *
     * <p>The horizontal whitespace classes are spelled out rather than written {@code \s}, because
     * {@code \s} matches the line break itself and would swallow the very break {@code \R} has to
     * see.
     */
    private static String collapseContinuations(String text) {
        return text.replaceAll("[ \\t]*\\\\[ \\t]*\\R[ \\t]*", " ");
    }

    /** Walks a parsed mapping down the named path, and answers null where a step is absent. */
    private static Object descend(Map<String, Object> tree, String... path) {
        Object current = tree;
        for (String step : path) {
            if (!(current instanceof Map<?, ?> mapping)) {
                return null;
            }
            current = mapping.get(step);
        }
        return current;
    }

    private static Map<String, Object> parseYaml(String text) {
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        if (!(loaded instanceof Map<?, ?> mapping)) {
            return fail(APPLICATION_YAML + " does not parse to a mapping");
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        mapping.forEach((key, value) -> tree.put(String.valueOf(key), value));
        return tree;
    }

    private static String read(Path file) {
        if (!Files.isRegularFile(file)) {
            return fail("expected file " + file.toAbsolutePath() + " is missing");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return fail("cannot read " + file.toAbsolutePath(), failure);
        }
    }

    /** This module's directory, from the build property and then from the working directory. */
    private static Path moduleBase() {
        String declared = System.getProperty("basedir");
        String fallback = System.getProperty("user.dir", ".");
        Path base = Path.of(declared == null || declared.isBlank() ? fallback : declared);

        if (!Files.isRegularFile(base.resolve(APPLICATION_YAML))) {
            return fail("resolved module base " + base.toAbsolutePath() + " carries no "
                    + APPLICATION_YAML);
        }
        return base;
    }

    /** The {@code card-platform} directory, two levels above this module. */
    private static Path platformRoot() {
        Path root = moduleBase().toAbsolutePath().normalize().getParent().getParent();

        if (root == null || !Files.isRegularFile(root.resolve(COMPOSE_FILE))) {
            return fail("expected a card-platform root carrying " + COMPOSE_FILE + " two levels "
                    + "above " + moduleBase().toAbsolutePath());
        }
        return root;
    }

    /** Ensures the placeholder set this test reads stays a set rather than a list of duplicates. */
    @Test
    @DisplayName("Every configured topic carries a distinct environment variable")
    void everyTopicCarriesADistinctVariable() {
        Set<String> variables = new LinkedHashSet<>();
        topics().values().forEach(topic -> variables.add(topic.variable()));

        assertThat(variables).hasSameSizeAs(topics().values());
    }

    /**
     * One configured topic: the environment variable compose names it by, and the literal default
     * the Kubernetes manifest writes.
     *
     * @param variable    the environment variable name, for example {@code TOPIC_TRANSACTION_POSTED}
     * @param defaultName the shipped default, for example {@code transaction.posted}
     */
    private record Topic(String variable, String defaultName) {
    }
}
