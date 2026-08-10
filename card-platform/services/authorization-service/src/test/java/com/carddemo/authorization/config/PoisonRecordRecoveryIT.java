package com.carddemo.authorization.config;

import com.carddemo.authorization.AuthorizationServiceDatabase;
import com.carddemo.authorization.TestIdentityPasswords;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves one record this service can never apply produces one diagnostic and then leaves the
 * partition, against a real broker.
 *
 * <p>The defect this closes is a loop rather than a loss. Both replica listeners acknowledge with
 * {@code MANUAL_IMMEDIATE}, and a record that exhausts its attempts is never acknowledged by the
 * listener method, because the method threw. Without
 * {@code DefaultErrorHandler.setCommitRecovered(true)} the offset of a dead-lettered record therefore
 * stayed uncommitted: the diagnostic was published, the offset did not move, and the next start-up or
 * partition assignment read the same record again. Nothing about the record changes between passes,
 * so the same diagnostic was published again, indefinitely, and every record behind it waited.
 *
 * <p>Three properties are measured here, and none of them can be measured without a broker. The
 * committed offset is broker state. The count of diagnostics is topic state. And the reassignment
 * that used to reread the record only happens inside a real consumer group.
 *
 * <p>The poison record is a document whose envelope members are nested under one object, so the
 * required members are absent from the top level. {@code JsonSchemaValidatingDeserializer} refuses it
 * during polling, and {@code SerializationException} is registered as not retryable, so recovery
 * happens on the first pass rather than after the retry budget.
 *
 * <p>The abend contract this replaces is {@code app/cbl/CBTRN02C.cbl:L707-L711}: display one message,
 * move 999 into an abend code, end the address space. A record the source could not apply stopped the
 * job; a record this service cannot apply stops nothing and is named on a topic.
 *
 * <p>Traceability: {@code card-platform/docs/traceability-matrix.md}. Decision log:
 * {@code card-platform/docs/decision-log.md}.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "KAFKA_SECURITY_PROTOCOL=PLAINTEXT",
                // config/SecurityConfig refuses to start on an identity password carrying no
                // encoding prefix, and application.yml gives these four no default. The hashes are
                // the module's own adaptively encoded test values, so this context starts under the
                // same rule a deployment starts under.
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "ACQUIRER_PASSWORD_HASH=" + TestIdentityPasswords.ACQUIRER_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "carddemo.outbox.relay.fixed-delay-ms=3600000"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("One poison record reaches the dead-letter topic once and the offset moves past it")
class PoisonRecordRecoveryIT {

    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    /** The replica stream the poison record arrives on. */
    private static final String SOURCE_TOPIC = "account.state-changed";

    /** The one topic every unconsumable record of this service reaches. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The consumer group of the account-state listener, as {@code application.yml} names it. */
    private static final String LISTENER_GROUP = "authorization-account-state";

    /** Partitions each created topic carries. One keeps the offset arithmetic unambiguous. */
    private static final int ONE_PARTITION = 1;

    /** Replicas each created topic carries, against a single-node broker. */
    private static final short ONE_REPLICA = (short) 1;

    /** Longest an assertion waits for broker assignment or record arrival. */
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(60L);

    /** How often an awaiting assertion re-reads the broker. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200L);

    /** How long one fetch call blocks. */
    private static final Duration FETCH_TIMEOUT = Duration.ofMillis(200L);

    /** How long a silence assertion listens for a record it expects never to arrive. */
    private static final Duration SILENCE_WINDOW = Duration.ofSeconds(3L);

    /**
     * The key every diagnostic of the shared dead-letter topic carries.
     *
     * <p>{@code config/KafkaConsumerConfig} keys on the aggregate identifier the envelope itself
     * declares, and a record it could not read carries no account it can be trusted to name, so the
     * sentinel says exactly that: eleven zeros are not an account this platform seeds or issues. The
     * coordinates of the refused record are declared fields of the payload instead, which is where
     * this test reads them.
     */
    private static final String UNRESOLVED_ACCOUNT_KEY = "00000000000";

    /**
     * A document whose envelope members sit under one nested object.
     *
     * <p>Every member {@code schemas/account-state-changed-v1.json} requires is therefore absent from
     * the top level, and the deserializer refuses the record before any listener method runs. No
     * member here carries a card number.
     */
    private static final String POISON_PAYLOAD = """
            {"envelope":{"eventId":"1b0d5f6c-8d21-4a55-9f3a-2c2f9a0e7b41",\
            "eventType":"AccountStateChanged","schemaVersion":1,\
            "occurredAt":"2026-08-06T12:00:00Z","aggregateId":"00000000007"},\
            "accountId":"00000000007","creditLimit":"5000.00"}""";

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link AuthorizationServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    private static final PostgreSQLContainer POSTGRES = AuthorizationServiceDatabase.container();

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    /** The registry the two replica listener containers are registered with. */
    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    /** The delivery-attempt policy and dead-letter route both replica listeners install. */
    @Autowired
    private DefaultErrorHandler replicaErrorHandler;

    /** Binds the production clients to the disposable database and broker. */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PoisonRecordRecoveryIT::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** Creates the source topic and the dead-letter topic before the application reads either. */
    @BeforeAll
    static void createTopics() {
        try (Admin admin = Admin.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            createTopic(admin, SOURCE_TOPIC);
            createTopic(admin, DEAD_LETTER_TOPIC);
        }
    }

    /**
     * Asserts the diagnostic is published once, the offset advances past the record, and a
     * reassignment reads nothing again.
     *
     * <p>The three assertions are one test because the third depends on the second: the offset has to
     * be committed before a reassignment can be shown to skip the record, and it is the reassignment
     * that reproduced the defect.
     */
    @Test
    @DisplayName("the diagnostic is published once and the committed offset advances past it")
    void theDiagnosticIsPublishedOnceAndTheOffsetAdvances() {
        awaitListenerAssignment();

        try (KafkaConsumer<String, byte[]> diagnostics = deadLetterReader()) {
            awaitReaderAssignment(diagnostics);
            RecordMetadata poison = publishPoison();
            TopicPartition partition = new TopicPartition(poison.topic(), poison.partition());
            String expectedCoordinates = coordinatesOf(poison);

            ConsumerRecord<String, byte[]> diagnostic =
                    awaitDiagnostic(diagnostics, expectedCoordinates);

            assertAll("the first and only pass over one poison record",
                    () -> assertEquals(DEAD_LETTER_TOPIC, diagnostic.topic(),
                            "the diagnostic reached the configured dead-letter topic"),
                    () -> assertEquals(UNRESOLVED_ACCOUNT_KEY, diagnostic.key(),
                            "and it is keyed by the aggregate identifier its own payload declares, "
                                    + "which is the sentinel a record naming no readable account "
                                    + "carries and which no producer controls"),
                    () -> assertTrue(diagnostic.value().length > 0,
                            "the diagnostic carries the abend record"),
                    () -> assertTrue(namesTheCoordinates(diagnostic, poison),
                            "the coordinates of the refused record are declared fields of the "
                                    + "payload rather than the key: " + payloadOf(diagnostic)),
                    () -> assertFalse(payloadOf(diagnostic).contains("creditLimit"),
                            "and no member of the refused document is republished: "
                                    + payloadOf(diagnostic)));

            Awaitility.await("the offset of the recovered record")
                    .atMost(ARRIVAL_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() -> assertEquals(poison.offset() + 1L,
                            committedOffset(partition),
                            "a recovered record has its offset committed, so the next assignment "
                                    + "starts after it rather than on it"));

            reassignTheListener();

            assertNoFurtherDiagnostic(diagnostics, poison);
            assertEquals(poison.offset() + 1L, committedOffset(partition),
                    "and the reassignment left the committed offset where recovery put it");
        }
    }

    /**
     * Asserts the error handler this service installs commits a recovered offset.
     *
     * <p>The behaviour above is what matters, and this reads the setting that produces it, so a
     * failure names the cause rather than only the symptom. The field is not public, so it is read
     * reflectively: the alternative is a test that fails a minute later against a broker and does not
     * say which setting was missing.
     *
     * @throws ReflectiveOperationException when the framework renames the field this reads
     */
    @Test
    @DisplayName("the replica error handler commits the offset of a record it recovered")
    void theReplicaErrorHandlerCommitsRecoveredOffsets() throws ReflectiveOperationException {
        Field commitRecovered = Class
                .forName("org.springframework.kafka.listener.FailedRecordProcessor")
                .getDeclaredField("commitRecovered");
        commitRecovered.setAccessible(true);

        assertEquals(Boolean.TRUE, commitRecovered.get(replicaErrorHandler),
                "MANUAL_IMMEDIATE plus a listener method that threw means the offset only moves "
                        + "when the handler commits it");
    }

    /** Waits until the account-state listener holds its partition. */
    private void awaitListenerAssignment() {
        Awaitility.await("the account-state listener assignment")
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> listenerRegistry.getListenerContainers().stream()
                        .anyMatch(container -> container.isRunning()
                                && container.getAssignedPartitions() != null
                                && !container.getAssignedPartitions().isEmpty()));
    }

    /**
     * Stops and restarts every replica listener, which is the reassignment that reread the record.
     *
     * <p>A stop and a start is the same event a rolling restart or a rebalance produces: the group
     * reads its committed offsets again and resumes from them.
     */
    private void reassignTheListener() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            container.stop();
        }
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            container.start();
        }
        awaitListenerAssignment();
    }

    /**
     * Publishes the poison record and waits for the broker to acknowledge it.
     *
     * @return where the broker stored it
     */
    private static RecordMetadata publishPoison() {
        Map<String, Object> settings = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(settings)) {
            return producer.send(new ProducerRecord<>(SOURCE_TOPIC, "00000000007",
                    POISON_PAYLOAD.getBytes(StandardCharsets.UTF_8)))
                    .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("publishing the poison record was interrupted",
                    interrupted);
        } catch (ExecutionException | TimeoutException refused) {
            throw new IllegalStateException("the broker did not accept the poison record", refused);
        }
    }

    /**
     * Opens a reader over the dead-letter topic, in a group of its own.
     *
     * @return the reader, which the caller closes
     */
    private static KafkaConsumer<String, byte[]> deadLetterReader() {
        Map<String, Object> settings = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "poison-record-recovery-it",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(settings);
        reader.subscribe(List.of(DEAD_LETTER_TOPIC));
        return reader;
    }

    /**
     * Waits until the reader holds the dead-letter partition, so no record is published unobserved.
     *
     * @param reader the reader to wait on
     */
    private static void awaitReaderAssignment(KafkaConsumer<String, byte[]> reader) {
        Awaitility.await("the dead-letter reader assignment")
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> {
                    reader.poll(FETCH_TIMEOUT);
                    return !reader.assignment().isEmpty();
                });
    }

    /**
     * Waits for the one diagnostic naming the published record.
     *
     * <p>Matching is on the coordinates the payload declares rather than on the message key. Every
     * diagnostic this service publishes to the shared dead-letter topic is keyed on the aggregate
     * identifier its own payload declares, so the key does not distinguish one refused record from
     * another; {@code sourceTopic}, {@code sourcePartition} and {@code sourceOffset} do.
     *
     * @param reader      the dead-letter reader
     * @param coordinates the source coordinates the payload declares, for the alias alone
     * @return the diagnostic record
     */
    private static ConsumerRecord<String, byte[]> awaitDiagnostic(
            KafkaConsumer<String, byte[]> reader, String coordinates) {
        return Awaitility.await("the diagnostic naming " + coordinates)
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> firstMatching(reader, coordinates), record -> record != null);
    }

    /**
     * Asserts no second diagnostic for the same record arrives inside the silence window.
     *
     * @param reader the dead-letter reader
     * @param poison where the broker stored the record a duplicate would name
     */
    private static void assertNoFurtherDiagnostic(KafkaConsumer<String, byte[]> reader,
            RecordMetadata poison) {
        long deadline = System.nanoTime() + SILENCE_WINDOW.toNanos();
        while (System.nanoTime() - deadline < 0L) {
            ConsumerRecord<String, byte[]> repeated = firstMatching(reader, coordinatesOf(poison));
            assertNull(repeated,
                    "one poison record produces one diagnostic, however often its partition is "
                            + "assigned");
        }
    }

    /**
     * Fetches once and returns the first record whose payload declares one set of coordinates, or
     * null.
     *
     * @param reader      the reader to fetch through
     * @param coordinates the rendered coordinates to match inside the payload
     * @return the matching record, or null when this fetch held none
     */
    private static ConsumerRecord<String, byte[]> firstMatching(
            KafkaConsumer<String, byte[]> reader, String coordinates) {
        ConsumerRecords<String, byte[]> fetched = reader.poll(FETCH_TIMEOUT);
        for (ConsumerRecord<String, byte[]> record : fetched) {
            if (payloadOf(record).contains(coordinates)) {
                return record;
            }
        }
        return null;
    }

    /**
     * Renders the source-topic field of a diagnostic payload, which is the part of the coordinates a
     * substring match can rely on whatever order the writer emits members in.
     *
     * @param published where the broker stored the refused record
     * @return the rendered {@code sourceTopic} member
     */
    private static String coordinatesOf(RecordMetadata published) {
        return "\"sourceTopic\":\"" + published.topic() + "\"";
    }

    /**
     * Reports whether one diagnostic payload declares every coordinate of one refused record.
     *
     * @param diagnostic the diagnostic
     * @param published  where the broker stored the refused record
     * @return {@code true} when the payload names the topic, the partition and the offset
     */
    private static boolean namesTheCoordinates(ConsumerRecord<String, byte[]> diagnostic,
            RecordMetadata published) {
        String payload = payloadOf(diagnostic);
        return payload.contains("\"sourceTopic\":\"" + published.topic() + "\"")
                && payload.contains("\"sourcePartition\":" + published.partition())
                && payload.contains("\"sourceOffset\":" + published.offset());
    }

    /**
     * Reads one diagnostic payload as text.
     *
     * @param record the diagnostic
     * @return the payload, read as UTF-8
     */
    private static String payloadOf(ConsumerRecord<String, byte[]> record) {
        return new String(record.value(), StandardCharsets.UTF_8);
    }

    /**
     * Reads the offset the listener group has committed for one partition.
     *
     * @param partition the partition to read
     * @return the committed offset, or minus one when the group has committed none
     */
    private static long committedOffset(TopicPartition partition) {
        try (Admin admin = Admin.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(LISTENER_GROUP)
                            .partitionsToOffsetAndMetadata()
                            .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            OffsetAndMetadata offset = committed.get(partition);
            return offset == null ? -1L : offset.offset();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("reading the committed offset was interrupted",
                    interrupted);
        } catch (ExecutionException | TimeoutException unreadable) {
            throw new IllegalStateException("the committed offset could not be read", unreadable);
        }
    }

    /**
     * Creates one topic, treating an existing topic as success.
     *
     * @param admin the administration client
     * @param name  the topic to create
     */
    private static void createTopic(Admin admin, String name) {
        try {
            admin.createTopics(Set.of(new NewTopic(name, ONE_PARTITION, ONE_REPLICA)))
                    .all()
                    .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("creating " + name + " was interrupted", interrupted);
        } catch (ExecutionException failure) {
            if (!(failure.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("creating " + name + " failed", failure);
            }
        } catch (TimeoutException timedOut) {
            throw new IllegalStateException("creating " + name + " timed out", timedOut);
        }
    }

    /**
     * Returns the container connection string with {@code currentSchema} appended.
     *
     * @return the connection string every statement of this service resolves its tables through
     */
    private static String migratedSchemaUrl() {
        return AuthorizationServiceDatabase.urlFor(PoisonRecordRecoveryIT.class);
    }
}
