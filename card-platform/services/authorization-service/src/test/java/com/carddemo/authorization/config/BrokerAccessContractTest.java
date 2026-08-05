package com.carddemo.authorization.config;

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
 * this service addresses without an entry fails every attempt. On the consumer side the failure is
 * silent in the way that matters most here: {@code card_xref} and {@code account_credit_snapshot} are
 * replicas kept current by the two state-change streams, and a listener the broker refuses simply
 * never receives anything. The tables keep answering with whatever they last knew, every decline rule
 * keeps reading them, and nothing raises until the freshness window in
 * {@code domain/AuthorizationService} passes and the service starts refusing traffic for a reason that
 * looks nothing like a missing broker entry.
 *
 * <p>The topic names and the group names are read out of the shipped
 * {@code src/main/resources/application.yml} rather than restated here, so adding a topic or a group
 * to the configuration fails the build until both {@code card-platform/docker-compose.yml} and
 * {@code card-platform/deploy/k8s/10-kafka.yaml} grant it.
 *
 * <p>Direction is derived from the configuration rather than asserted from a list. A key that also
 * appears under {@code carddemo.kafka.groups} is a stream this service reads, and it must carry a
 * consumer entry naming that group. Every other key is a destination this service writes, whether from
 * {@code outbox/OutboxRelay} or from the dead-letter route of {@code config/KafkaConsumerConfig}, and
 * each must carry a producer entry.
 *
 * <p>Both deployment paths read topic names through environment variables, so each matrix is checked
 * against the variable declared by the shipped service configuration. This keeps a ConfigMap topic
 * override aligned with the broker provisioning and access-control commands.
 */
@DisplayName("The broker authorization matrices grant every topic this service is configured to "
        + "reach")
class BrokerAccessContractTest {

    /** The shipped configuration this test reads the topic and group names out of. */
    private static final String APPLICATION_YAML = "src/main/resources/application.yml";

    /** The compose file carrying one of the two authorization matrices. */
    private static final String COMPOSE_FILE = "docker-compose.yml";

    /** The Kubernetes manifest carrying the other. */
    private static final String KAFKA_MANIFEST = "deploy/k8s/10-kafka.yaml";

    /** The principal placeholder both matrices name this service by. */
    private static final String PRINCIPAL = "AUTHORIZATION_KAFKA_USER";

    /** Reads {@code ${NAME:default}}, and also {@code ${OUTER:${NAME:default}}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+):([^{}]*)}");

    @Nested
    @DisplayName("docker-compose.yml")
    class Compose {

        @Test
        @DisplayName("grants a producer entry for every destination topic, by variable")
        void grantsEveryDestination() {
            String matrix = read(platformRoot().resolve(COMPOSE_FILE));

            destinations().forEach((key, topic) -> {
                String expected = "grant_producer \"$${" + PRINCIPAL + "}\" \"$${"
                        + topic.variable() + "}\"";
                assertThat(matrix)
                        .as("carddemo.kafka.topics.%s is a destination of this service, so %s must "
                                + "carry %s", key, COMPOSE_FILE, expected)
                        .contains(expected);
            });
        }

        @Test
        @DisplayName("grants a consumer entry for every consumed topic under its own group")
        void grantsEveryConsumedTopic() {
            String matrix = collapseContinuations(read(platformRoot().resolve(COMPOSE_FILE)));

            consumed().forEach((key, stream) -> {
                String expected = "grant_consumer \"$${" + PRINCIPAL + "}\" \"$${"
                        + stream.topic().variable() + "}\" \"$${" + stream.group() + "}\"";
                assertThat(matrix)
                        .as("carddemo.kafka.topics.%s is consumed under carddemo.kafka.groups.%s, "
                                + "so %s must carry %s", key, key, COMPOSE_FILE, expected)
                        .contains(expected);
            });
        }
    }

    @Nested
    @DisplayName("deploy/k8s/10-kafka.yaml")
    class Kubernetes {

        @Test
        @DisplayName("grants a producer entry for every destination topic, by ConfigMap variable")
        void grantsEveryDestination() {
            String matrix = collapseContinuations(read(platformRoot().resolve(KAFKA_MANIFEST)));

            destinations().forEach((key, topic) -> {
                String expected = "grant_producer \"${" + PRINCIPAL + "}\" \"${"
                        + topic.variable() + "}\"";
                assertThat(matrix)
                        .as("carddemo.kafka.topics.%s is a destination of this service, so %s must "
                                + "carry %s", key, KAFKA_MANIFEST, expected)
                        .contains(expected);
            });
        }

        @Test
        @DisplayName("grants a consumer entry for every consumed topic under its own group")
        void grantsEveryConsumedTopic() {
            String matrix = collapseContinuations(read(platformRoot().resolve(KAFKA_MANIFEST)));

            consumed().forEach((key, stream) -> {
                String expected = "grant_consumer \"${" + PRINCIPAL + "}\" \"${"
                        + stream.topic().variable() + "}\" \"${" + stream.group() + "}\"";
                assertThat(matrix)
                        .as("carddemo.kafka.topics.%s is consumed under carddemo.kafka.groups.%s, "
                                + "so %s must carry %s", key, key, KAFKA_MANIFEST, expected)
                        .contains(expected);
            });
        }
    }

    @Nested
    @DisplayName("The shipped configuration")
    class ShippedConfiguration {

        /**
         * The two consumed streams are what make the replica model work at all, so their presence is
         * asserted by name. Losing either one returns this service to reading a copy that nothing
         * updates.
         */
        @Test
        @DisplayName("names both replica streams and a group for each")
        void namesBothReplicaStreamsAndAGroupForEach() {
            assertThat(consumed().keySet())
                    .as("the streams this service reads to keep its replicas current")
                    .containsExactlyInAnyOrder("account-state-changed", "card-updated");
        }

        @Test
        @DisplayName("names both decision topics and the dead-letter topic as destinations")
        void namesBothDecisionTopicsAndTheDeadLetterTopic() {
            assertThat(destinations().keySet())
                    .containsExactlyInAnyOrder("transaction-authorized", "transaction-declined",
                            "dead-letter");
        }

        @Test
        @DisplayName("gives every configured topic a distinct environment variable")
        void givesEveryTopicADistinctVariable() {
            Set<String> variables = new LinkedHashSet<>();
            topics().values().forEach(topic -> variables.add(topic.variable()));

            assertThat(variables).hasSameSizeAs(topics().values());
        }

        /**
         * Two listeners sharing one group would split the stream between them, so each would see part
         * of it and each replica row would be refreshed by whichever instance happened to receive it.
         */
        @Test
        @DisplayName("gives every consumed stream a group of its own")
        void givesEveryConsumedStreamAGroupOfItsOwn() {
            Set<String> groups = new LinkedHashSet<>();
            consumed().values().forEach(stream -> groups.add(stream.group()));

            assertThat(groups).hasSameSizeAs(consumed().values());
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

    /** Every consumed stream: a topic key that also names a group, paired with that group. */
    private static Map<String, Stream> consumed() {
        Object groups = descend(parseYaml(read(moduleBase().resolve(APPLICATION_YAML))),
                "carddemo", "kafka", "groups");
        if (!(groups instanceof Map<?, ?> mapping)) {
            return fail(APPLICATION_YAML + " carries no carddemo.kafka.groups mapping");
        }

        Map<String, Topic> topics = topics();
        Map<String, Stream> streams = new LinkedHashMap<>();
        mapping.forEach((key, value) -> {
            String name = String.valueOf(key);
            Topic topic = topics.get(name);
            if (topic == null) {
                fail("carddemo.kafka.groups." + name + " names a group for a stream, and "
                        + "carddemo.kafka.topics names no " + name + " to read");
            }
            streams.put(name, new Stream(topic, topicOf(key, value).variable()));
        });
        return streams;
    }

    /** Every topic this service writes: all of them but the ones it reads. */
    private static Map<String, Topic> destinations() {
        Map<String, Topic> destinations = new LinkedHashMap<>(topics());
        consumed().keySet().forEach(destinations::remove);
        return destinations;
    }

    /** Reads the innermost variable name and the default out of one configured value. */
    private static Topic topicOf(Object key, Object value) {
        String text = String.valueOf(value);
        Matcher placeholders = PLACEHOLDER.matcher(text);
        List<Topic> found = new ArrayList<>();

        while (placeholders.find()) {
            found.add(new Topic(placeholders.group(1), placeholders.group(2).trim()));
        }
        if (found.size() != 1) {
            return fail(key + " must resolve one ${NAME:default} placeholder, and it carries "
                    + found.size());
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

    /**
     * One configured topic: the environment variable compose names it by, and the literal default the
     * Kubernetes manifest writes.
     *
     * @param variable    the environment variable name, for example
     *                    {@code TOPIC_ACCOUNT_STATE_CHANGED}
     * @param defaultName the shipped default, for example {@code account.state-changed}
     */
    private record Topic(String variable, String defaultName) {
    }

    /**
     * One consumed stream: the topic and the environment variable naming the group it is read under.
     *
     * @param topic the topic this service reads
     * @param group the environment variable naming its consumer group, for example
     *              {@code GROUP_AUTHORIZATION_ACCOUNT}
     */
    private record Stream(Topic topic, String group) {
    }
}
