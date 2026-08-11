package com.carddemo.authorization.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.AuthorizationServiceDatabase;
import com.carddemo.authorization.TestIdentityPasswords;
import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.authorization.domain.ReplicaSynchronization;
import com.carddemo.authorization.domain.RequestCaller;
import com.carddemo.events.DeclineReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Carries one replicated state change from the broker into a replica row and then into the next
 * authorization decision.
 *
 * <p>The two replica listeners are covered elsewhere by unit tests over mocked repositories, and the
 * two producing services by their own publication tests. This class joins the halves those tests leave
 * apart: it delivers an event, lets the listener write the replica, and then asks the decision path
 * what it now decides. The authorization service holds no account and no card of its own — it decides
 * from {@code account_credit_snapshot} and {@code card_xref}, which exist only because these listeners
 * maintain them.
 *
 * <p>What is joined here. A real broker, the shipped listeners, a real PostgreSQL schema built by the
 * shipped migrations, and {@link AuthorizationService#authorize} reading the rows the listeners wrote.
 * The headline test moves one account through three decisions without touching the database directly:
 * declined as expired, then declined over its limit, then approved, each transition caused by one
 * event published to {@code account.state-changed}.
 *
 * <p>Four further properties the finding named are covered: ordering, in that an older event does not
 * overwrite a newer one; duplicate delivery, in that a second delivery of one event identifier
 * changes nothing even when its payload differs; the card path, in that a card update refreshes the
 * observation columns of the matching cross-reference row and leaves resolution intact; and rollback,
 * in that an apply the database refuses leaves no marker behind, so the redelivery that would apply it
 * is not suppressed.
 *
 * <p>Fixtures come from the shipped seed. {@code src/main/resources/db/migration/V2__seed.sql} loads
 * account {@value #SEEDED_ACCOUNT} with a credit limit of 6169.00 and an expiration of 2023-03-09,
 * from {@code app/data/ASCII/acctdata.txt}, and cross-references card {@value #SEEDED_CARD} to it,
 * from {@code app/data/ASCII/cardxref.txt}. Each test restores what it changed.
 *
 * <p>The four credential values in the annotation stand in for variables the shipped configuration
 * leaves undefined and are inert. The relay and the retention sweep are pushed an hour out, so no
 * scheduled work runs while a test is reading rows.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. The publish and consume paths are
 * drawn in {@code card-platform/docs/event-flow.md}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "ACQUIRER_PASSWORD_HASH=" + TestIdentityPasswords.ACQUIRER_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("One replicated state change, from the broker to the replica to the next decision")
class ReplicaRefreshToDecisionIT {

    /** Image tag of the broker container, matching the pinned client library. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    /** Transport the container broker offers, in place of the shipped authenticated one. */
    private static final String BROKER_SECURITY_PROTOCOL = "PLAINTEXT";

    /** Topic the account-state listener subscribes to, from {@code application.yml}. */
    static final String ACCOUNT_STATE_TOPIC = "account.state-changed";

    /** Topic the card-update listener subscribes to, from {@code application.yml}. */
    static final String CARD_UPDATED_TOPIC = "card.updated";

    /** Partitions each topic carries, as the shipped compose file sets. */
    private static final int TOPIC_PARTITIONS = 3;

    /** Replicas each topic carries on a single-broker container. */
    private static final short TOPIC_REPLICATION_FACTOR = 1;

    /** The seeded account every test below moves, eleven digits as its column holds them. */
    static final String SEEDED_ACCOUNT = "00000000050";

    /** The seeded card cross-referenced to that account, sixteen digits. */
    static final String SEEDED_CARD = "0500024453765740";

    /** That card in the masked form the event contract carries, from {@code PanMasker}. */
    private static final String MASKED_SEEDED_CARD = "************5740";

    /** Credit limit the seed loads on that account. */
    private static final BigDecimal SEEDED_CREDIT_LIMIT = new BigDecimal("6169.00");

    /** Expiration the seed loads on that account, which every current timestamp is after. */
    private static final String SEEDED_EXPIRATION = "2023-03-09";

    /** An expiration no request will reach, so the expiration rule stops refusing. */
    private static final String OPEN_EXPIRATION = "2999-12-31";

    /** A limit below the request amount, so the credit-limit rule is the only one that refuses. */
    private static final String TIGHT_CREDIT_LIMIT = "5.00";

    /** A limit above the request amount, so no rule refuses. */
    private static final String AMPLE_CREDIT_LIMIT = "50000.00";

    /** Amount every request carries, above the tight limit and below the ample one. */
    private static final String REQUEST_AMOUNT = "10.00";

    /** Cycle accumulators every event below sets, so the working balance is the amount alone. */
    private static final String NO_CYCLE_MOVEMENT = "0.00";

    /**
     * Current balance every event below carries.
     *
     * <p>Required by {@code schemas/account-state-changed-v1.json} and stored by no replica column.
     * {@code app/cbl/CBTRN02C.cbl:L403-L407} computes the working balance from the two cycle
     * accumulators and never from this field, so it reaches no decision here and is carried only
     * because the contract requires it.
     */
    private static final String REPLICATED_BALANCE = "1200.00";

    /** The authenticated identity each decision records, at {@code SEC-USR-ID PIC X(08)} width. */
    private static final String ACTOR = "OPERATR1";

    /** Longest wait for one event to travel from the broker into a replica row. */
    private static final Duration REFRESH_ARRIVES_WITHIN = Duration.ofSeconds(30);

    /** Interval the replica row is re-read on while a refresh is awaited. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    /** Longest one publish waits for the broker to take the record. */
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(20);

    /** Markers one delivery writes. */
    private static final int ONE_MARKER = 1;

    /** Markers a delivery that rolled back leaves behind. */
    private static final int NO_MARKER = 0;

    /** Cross-reference rows the seeded card occupies. */
    private static final int ONE_ROW = 1;

    /**
     * A credit limit the replica column refuses.
     *
     * <p>{@code account_credit_snapshot.credit_limit} is {@code NUMERIC(12,2)} in
     * {@code src/main/resources/db/migration/V1__schema.sql}, so it holds ten integer digits. This
     * value carries eleven. The event record checks scale and not precision, so it builds, and the
     * database refuses the write. That is the failure the rollback test needs: one raised inside the
     * listener's transaction, after the marker has been read and before it is written.
     */
    private static final String CREDIT_LIMIT_THE_COLUMN_REFUSES = "99999999999.99";

    /** Format the origin timestamp is supplied in, from {@code ORIGIN_TIMESTAMP_PATTERN}. */
    private static final DateTimeFormatter ORIGIN_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    /** Format the processing timestamp is supplied in, from {@code PROCESSING_TIMESTAMP_PATTERN}. */
    private static final DateTimeFormatter PROCESSING_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS").withZone(ZoneOffset.UTC);

    /** Format the event {@code occurredAt} is supplied in, from the schema document's pattern. */
    private static final DateTimeFormatter OCCURRED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Reads the two fixture documents the direct-invocation test hands the listener. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link AuthorizationServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    private static final PostgreSQLContainer POSTGRES = AuthorizationServiceDatabase.container();

    /** The broker container this class starts, which no other class in the module shares. */
    private static final KafkaContainer KAFKA;

    static {
        KAFKA = new KafkaContainer(KAFKA_IMAGE);
        KAFKA.start();
    }

    /** Reads and writes replica rows outside every transaction the listeners open. */
    @Autowired
    private JdbcTemplate jdbc;

    /** The decision path under test, which reads only the two replica tables. */
    @Autowired
    private AuthorizationService authorizations;

    /** The account-state listener, invoked directly by the rollback test alone. */
    @Autowired
    private AccountStateChangedConsumer accountStateListener;

    /**
     * The replica verdict every decision here waits for.
     *
     * <p>This is the real {@code messaging/KafkaReplicaSynchronization} over the real listener
     * containers and the real broker, not a substitute. Nothing about the rule is relaxed for these
     * comparisons; {@link #decideOnTheSeededCard()} simply waits for the same condition
     * {@code /actuator/health/readiness} publishes, which is what an orchestrator waits for before
     * routing a request to a fresh instance.
     */
    @Autowired
    private ReplicaSynchronization replicaStreams;

    /**
     * Points the datasource and the broker client at the two containers.
     *
     * @param registry the registry the test context reads these values from
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ReplicaRefreshToDecisionIT::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> BROKER_SECURITY_PROTOCOL);
    }

    /**
     * Returns the container connection string with the migrated schema on its search path.
     *
     * @return the connection string
     */
    private static String migratedSchemaUrl() {
        return AuthorizationServiceDatabase.urlFor(ReplicaRefreshToDecisionIT.class);
    }

    /**
     * Creates both topics before the listeners subscribe.
     *
     * <p>A rerun against a warm broker finds them present and carries on.
     */
    @BeforeAll
    static void createTopics() {
        Map<String, Object> settings =
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(settings)) {
            admin.createTopics(java.util.List.of(
                            new NewTopic(ACCOUNT_STATE_TOPIC, TOPIC_PARTITIONS,
                                    TOPIC_REPLICATION_FACTOR),
                            new NewTopic(CARD_UPDATED_TOPIC, TOPIC_PARTITIONS,
                                    TOPIC_REPLICATION_FACTOR)))
                    .all().get(PUBLISH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Creating the two topics was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            if (!(failed.getCause() instanceof TopicExistsException)) {
                throw new AssertionError("The broker refused a topic.", failed);
            }
        } catch (TimeoutException timedOut) {
            throw new AssertionError("The broker did not create the topics within "
                    + PUBLISH_TIMEOUT + ".", timedOut);
        }
    }

    /**
     * Returns the seeded account and the seeded cross-reference row to the state the seed left, and
     * clears every marker this class wrote.
     */
    @AfterEach
    void restoreTheSeededRows() {
        jdbc.update("""
                UPDATE account_credit_snapshot
                   SET credit_limit = ?, account_expiration_date = ?,
                       current_cycle_credit = 0.00, current_cycle_debit = 0.00,
                       source_event_id = NULL, source_occurred_at = NULL,
                       observed_at = CURRENT_TIMESTAMP
                 WHERE account_id = ?
                """, SEEDED_CREDIT_LIMIT, SEEDED_EXPIRATION, SEEDED_ACCOUNT);
        jdbc.update("""
                UPDATE card_xref
                   SET source_event_id = NULL, source_occurred_at = NULL,
                       observed_at = CURRENT_TIMESTAMP
                 WHERE card_number = ?
                """, SEEDED_CARD);
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM authorization_decision");
    }

    @Nested
    @DisplayName("An account-state event reaches the next decision")
    class AnAccountStateEventReachesTheNextDecision {

        /**
         * Moves one account through three decisions, each transition caused by one published event.
         *
         * <p>Nothing here writes a replica row. The seeded state is read first, then each event is
         * published to the real topic and the shipped listener writes the row, and the decision is
         * asked again.
         *
         * <p>The three stages are chosen so exactly one rule refuses at each, which is what makes each
         * outcome unambiguous evidence about one replicated field. The seed carries an expiration of
         * {@value #SEEDED_EXPIRATION}, which every current origin timestamp is after, so the seeded
         * decision is refused as expired at {@code app/cbl/CBTRN02C.cbl:L414-L420} while the amount
         * sits well inside the seeded limit. The first event opens the expiration and tightens the
         * limit below the amount, so the refusal moves to the credit-limit rule at
         * {@code app/cbl/CBTRN02C.cbl:L403-L413}. The second event raises the limit above the amount,
         * and no rule refuses.
         *
         * <p>Both events name the same account and carry increasing {@code occurredAt} values, so the
         * newer-wins guard on the upsert admits each in turn.
         */
        @Test
        @DisplayName("two published events move one account from expired to over-limit to approved")
        void twoPublishedEventsMoveOneAccountFromExpiredToOverLimitToApproved() {
            AuthorizationService.Outcome onSeededState = decideOnTheSeededCard();

            UUID opensTheExpiration = UUID.randomUUID();
            Instant firstChange = Instant.now().minusSeconds(120);
            publishAccountState(opensTheExpiration, firstChange, OPEN_EXPIRATION,
                    TIGHT_CREDIT_LIMIT);
            awaitSnapshotSourcedFrom(opensTheExpiration);
            AuthorizationService.Outcome onTightLimit = decideOnTheSeededCard();

            UUID raisesTheLimit = UUID.randomUUID();
            publishAccountState(raisesTheLimit, firstChange.plusSeconds(60), OPEN_EXPIRATION,
                    AMPLE_CREDIT_LIMIT);
            awaitSnapshotSourcedFrom(raisesTheLimit);
            AuthorizationService.Outcome onAmpleLimit = decideOnTheSeededCard();

            assertAll("three decisions over one account, two events apart",
                    () -> assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED),
                            onSeededState.declineReason(),
                            "the seeded expiration of " + SEEDED_EXPIRATION + " refuses a request"
                                    + " captured today"),
                    () -> assertEquals(Optional.of(DeclineReason.OVER_CREDIT_LIMIT),
                            onTightLimit.declineReason(),
                            "the first event opened the expiration and tightened the limit below "
                                    + REQUEST_AMOUNT + ", so the refusal moved to the limit"),
                    () -> assertTrue(onAmpleLimit.approved(),
                            "the second event raised the limit above " + REQUEST_AMOUNT
                                    + ", so nothing refuses"),
                    () -> assertEquals(new BigDecimal(SEEDED_ACCOUNT), onAmpleLimit.accountId(),
                            "the approval names the account the card resolved to"),
                    () -> assertEquals(new BigDecimal(AMPLE_CREDIT_LIMIT), storedCreditLimit(),
                            "the replica carries the limit the second event set"),
                    () -> assertEquals(OPEN_EXPIRATION, storedExpiration(),
                            "and the expiration the first event set, which the second repeated"),
                    () -> assertEquals(2, markerCount(),
                            "one marker per delivered event"));
        }
    }

    @Nested
    @DisplayName("Ordering, staleness and duplicate delivery")
    class OrderingStalenessAndDuplicates {

        /**
         * Asserts an event that occurred earlier does not overwrite the state of a later one.
         *
         * <p>The newer event is delivered first and the older second, which is the reordering a
         * partitioned topic permits across a producer restart. The upsert at
         * {@code repository/AccountCreditSnapshotRepository#applyStateChange} admits a change only
         * when the stored {@code source_occurred_at} is null or older, so the second delivery writes
         * nothing.
         *
         * <p>The stale delivery still records its marker. That is deliberate in the listener: a
         * delivery that correctly applied nothing is complete, and leaving it unmarked would have it
         * redelivered for ever. The decision after both deliveries is the one the newer event
         * warrants, which is what makes the discarded write observable through the decision rather
         * than only through the row.
         */
        @Test
        @DisplayName("an older event applies nothing, still marks, and leaves the decision on the"
                + " newer state")
        void anOlderEventAppliesNothingStillMarksAndLeavesTheDecisionOnTheNewerState() {
            Instant newerMoment = Instant.now().minusSeconds(60);
            Instant olderMoment = newerMoment.minusSeconds(600);
            UUID newer = UUID.randomUUID();
            UUID older = UUID.randomUUID();

            publishAccountState(newer, newerMoment, OPEN_EXPIRATION, AMPLE_CREDIT_LIMIT);
            awaitSnapshotSourcedFrom(newer);
            publishAccountState(older, olderMoment, SEEDED_EXPIRATION, TIGHT_CREDIT_LIMIT);
            awaitMarkerFor(older, ACCOUNT_STATE_TOPIC);

            AuthorizationService.Outcome afterBoth = decideOnTheSeededCard();

            assertAll("the newer state survived the older delivery",
                    () -> assertEquals(new BigDecimal(AMPLE_CREDIT_LIMIT), storedCreditLimit(),
                            "the older event did not lower the limit"),
                    () -> assertEquals(OPEN_EXPIRATION, storedExpiration(),
                            "nor restore the expiration it carried"),
                    () -> assertEquals(newer, storedSourceEventId(),
                            "the row still names the newer event as its source"),
                    () -> assertTrue(afterBoth.approved(),
                            "so the decision is the one the newer state warrants"),
                    () -> assertEquals(2, markerCount(),
                            "both deliveries are marked, including the one that applied nothing"));
        }

        /**
         * Asserts a second delivery of one event identifier changes nothing.
         *
         * <p>The redelivery carries a different payload under the same identifier, which no correct
         * producer emits. That is the point: if the marker were not what gated the work, the differing
         * values would reach the row and be visible. The marker is read before the upsert runs, so the
         * second delivery returns having applied nothing, and the row still carries the first
         * delivery's values.
         *
         * <p>The stored {@code observed_at} is compared too. A second apply would advance it even
         * where the two payloads agreed, so it is the reading that would catch a duplicate the value
         * comparison could not.
         */
        @Test
        @DisplayName("a second delivery of one identifier applies nothing, even carrying other values")
        void aSecondDeliveryOfOneIdentifierAppliesNothingEvenCarryingOtherValues() {
            UUID onlyIdentifier = UUID.randomUUID();
            Instant moment = Instant.now().minusSeconds(60);

            publishAccountState(onlyIdentifier, moment, OPEN_EXPIRATION, AMPLE_CREDIT_LIMIT);
            awaitSnapshotSourcedFrom(onlyIdentifier);
            Instant firstObservation = storedObservedAt();

            publishAccountState(onlyIdentifier, moment.plusSeconds(120), SEEDED_EXPIRATION,
                    TIGHT_CREDIT_LIMIT);
            awaitStableMarkerCount(ONE_MARKER);

            assertAll("the redelivery changed nothing",
                    () -> assertEquals(new BigDecimal(AMPLE_CREDIT_LIMIT), storedCreditLimit(),
                            "the limit of the first delivery stands"),
                    () -> assertEquals(OPEN_EXPIRATION, storedExpiration(),
                            "and its expiration"),
                    () -> assertEquals(firstObservation, storedObservedAt(),
                            "the observation stamp did not advance, so no second apply ran"),
                    () -> assertEquals(ONE_MARKER, markerCount(),
                            "one identifier carries one marker on one topic"),
                    () -> assertTrue(decideOnTheSeededCard().approved(),
                            "and the decision still reads the first delivery's state"));
        }
    }

    @Nested
    @DisplayName("The card-update listener and the resolution the decision depends on")
    class TheCardUpdateListener {

        /**
         * Asserts a card update refreshes the observation columns and leaves resolution intact.
         *
         * <p>{@code repository/CardCrossReferenceRepository#refreshObservation} sets the three
         * observation columns and nothing else, matching on the account and on a {@code LIKE} pattern
         * built from the four digits the masked number leaves visible. The card number, the customer
         * and the account are untouched by design: this replica learns that a card changed, and the
         * mapping it resolves decisions through is owned elsewhere.
         *
         * <p>The decision is asked afterwards to prove resolution survived the refresh. A refresh that
         * had disturbed the mapping would surface as
         * {@link DeclineReason#INVALID_CARD_NUMBER} at {@code app/cbl/CBTRN02C.cbl:L385-L387}, which
         * is asserted against explicitly rather than inferred from the outcome.
         */
        @Test
        @DisplayName("a card update refreshes the observation and the card still resolves")
        void aCardUpdateRefreshesTheObservationAndTheCardStillResolves() {
            UUID cardChange = UUID.randomUUID();
            Instant moment = Instant.now().minusSeconds(60);

            publishCardUpdate(cardChange, moment, SEEDED_ACCOUNT, MASKED_SEEDED_CARD);
            awaitCrossReferenceSourcedFrom(cardChange);

            AuthorizationService.Outcome afterRefresh = decideOnTheSeededCard();

            assertAll("the cross-reference learned of the change and still resolves",
                    () -> assertEquals(cardChange, storedCardSourceEventId(),
                            "the row names the card event as its source"),
                    () -> assertEquals(SEEDED_ACCOUNT.trim(), storedCardAccount().trim(),
                            "the account the card resolves to is unchanged"),
                    () -> assertEquals(ONE_ROW, seededCardRowCount(),
                            "the refresh inserted no second row for the same card"),
                    () -> assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED),
                            afterRefresh.declineReason(),
                            "the decision reached the account rules, so the card resolved: an"
                                    + " unresolved card would answer "
                                    + DeclineReason.INVALID_CARD_NUMBER.code()),
                    () -> assertEquals(ONE_MARKER, markerCount(), "one delivery, one marker"));
        }

        /**
         * Asserts a card update naming another account refreshes no row and still records its marker.
         *
         * <p>The match is on the account and the visible digits together, so an event for an account
         * this replica holds no matching card of refreshes nothing. The listener treats that as a
         * complete delivery and marks it, because redelivering it would never find a row either.
         */
        @Test
        @DisplayName("a card update for another account refreshes no row and still marks")
        void aCardUpdateForAnotherAccountRefreshesNoRowAndStillMarks() {
            UUID unmatched = UUID.randomUUID();
            String anAccountHoldingNoSuchCard = "00000000001";

            publishCardUpdate(unmatched, Instant.now().minusSeconds(60), anAccountHoldingNoSuchCard,
                    MASKED_SEEDED_CARD);
            awaitMarkerFor(unmatched, CARD_UPDATED_TOPIC);

            assertAll("nothing was refreshed and the delivery is closed",
                    () -> assertEquals(null, storedCardSourceEventId(),
                            "the seeded card's row was not touched by an event for another account"),
                    () -> assertEquals(ONE_MARKER, markerCount(),
                            "the delivery is marked, so it is not redelivered for ever"));
        }
    }

    @Nested
    @DisplayName("Rollback: a refused apply leaves no marker")
    class RollbackLeavesNoMarker {

        /**
         * Asserts an apply the database refuses leaves neither a marker nor a changed row.
         *
         * <p>The listener reads the marker, applies the change, then writes the marker, all in one
         * transaction. What protects the redelivery is the transaction and not the order of the two
         * writes inside it: moving the marker write ahead of the apply was tried against this test and
         * changed nothing, because a failing apply rolls the marker back with it either way. Take the
         * transaction away, or commit the offset before the work, and the event is recorded as handled
         * while the row stays stale for ever. That second case is what the last assertion below
         * refuses.
         *
         * <p>The failure is raised by the column rather than by a stub, so the transaction that rolls
         * back is a real one. {@value #CREDIT_LIMIT_THE_COLUMN_REFUSES} carries eleven integer digits
         * against a {@code NUMERIC(12,2)} column. It is delivered by calling the listener directly
         * rather than through the broker, for two reasons: the schema document bounds the same value to
         * ten integer digits, so the boundary would refuse it before any listener saw it, and a
         * delivery that fails on the broker path is redelivered until it is dead-lettered, which is a
         * different property with its own coverage.
         *
         * <p>The second half is what makes the first half matter. The same identifier is delivered
         * again carrying a value the column accepts, and it applies and marks, which is only possible
         * because the refused attempt left no marker behind.
         */
        @Test
        @DisplayName("a refused apply leaves no marker, and the redelivery it allows applies")
        void aRefusedApplyLeavesNoMarkerAndTheRedeliveryItAllowsApplies() {
            UUID identifier = UUID.randomUUID();
            Instant moment = Instant.now().minusSeconds(60);
            CountingAcknowledgment refused = new CountingAcknowledgment();
            CountingAcknowledgment accepted = new CountingAcknowledgment();

            assertThrows(DataIntegrityViolationException.class,
                    () -> accountStateListener.onAccountStateChanged(
                            accountStateTree(identifier, moment, OPEN_EXPIRATION,
                                    CREDIT_LIMIT_THE_COLUMN_REFUSES),
                            SEEDED_ACCOUNT, refused, ACCOUNT_STATE_TOPIC),
                    "the column refused the value and the listener let the failure out");

            assertAll("the refused delivery left nothing behind",
                    () -> assertEquals(NO_MARKER, markerCount(),
                            "no marker survived the rollback, so the redelivery is not suppressed"),
                    () -> assertEquals(SEEDED_CREDIT_LIMIT, storedCreditLimit(),
                            "the seeded limit is unchanged"),
                    () -> assertEquals(SEEDED_EXPIRATION, storedExpiration(),
                            "and the seeded expiration, so no partial write committed"),
                    () -> assertEquals(null, storedSourceEventId(),
                            "the row names no source event"),
                    () -> assertEquals(0, refused.count(),
                            "the offset was not committed for a delivery that failed"));

            accountStateListener.onAccountStateChanged(
                    accountStateTree(identifier, moment, OPEN_EXPIRATION, AMPLE_CREDIT_LIMIT),
                    SEEDED_ACCOUNT, accepted, ACCOUNT_STATE_TOPIC);

            assertAll("the redelivery the rollback allowed",
                    () -> assertEquals(new BigDecimal(AMPLE_CREDIT_LIMIT), storedCreditLimit(),
                            "the same identifier applied on its second delivery"),
                    () -> assertEquals(identifier, storedSourceEventId(),
                            "and the row now names it"),
                    () -> assertEquals(ONE_MARKER, markerCount(), "and it is marked once"),
                    () -> assertEquals(1, accepted.count(), "and its offset is committed"),
                    () -> assertTrue(decideOnTheSeededCard().approved(),
                            "so the next decision reads the applied state"));
        }
    }

    /**
     * Asks the decision path to authorize the seeded card for {@value #REQUEST_AMOUNT}.
     *
     * <p>The request names the card and no account, so the cross-reference replica resolves it, which
     * is what puts both replica tables on the path of one call.
     *
     * @return the decision
     */
    private AuthorizationService.Outcome decideOnTheSeededCard() {
        awaitUsableReplicaStreams();
        Instant capturedAt = Instant.now();
        AuthorizationRequest request = new AuthorizationRequest(null, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", REQUEST_AMOUNT, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", SEEDED_CARD,
                ORIGIN_TIMESTAMP_FORMAT.format(capturedAt),
                PROCESSING_TIMESTAMP_FORMAT.format(capturedAt) + "0000", null);
        return authorizations.authorize(request, RequestCaller.administrator(ACTOR));
    }

    /**
     * Publishes one account-state change to the real topic.
     *
     * @param eventId    identifier the marker is keyed on
     * @param occurredAt moment the newer-wins guard compares
     * @param expiration expiration the replica is to carry
     * @param limit      credit limit the replica is to carry
     */
    private void publishAccountState(UUID eventId, Instant occurredAt, String expiration,
            String limit) {
        publish(ACCOUNT_STATE_TOPIC, SEEDED_ACCOUNT,
                accountStateJson(eventId, occurredAt, expiration, limit));
    }

    /**
     * Publishes one card update to the real topic.
     *
     * @param eventId    identifier the marker is keyed on
     * @param occurredAt moment the newer-wins guard compares
     * @param accountId  account the refresh matches on
     * @param maskedCard masked card number the visible-digit pattern is built from
     */
    private void publishCardUpdate(UUID eventId, Instant occurredAt, String accountId,
            String maskedCard) {
        publish(CARD_UPDATED_TOPIC, accountId,
                cardUpdateJson(eventId, occurredAt, accountId, maskedCard));
    }

    /**
     * Sends one record and waits for the broker to acknowledge it.
     *
     * @param topic    topic to publish on
     * @param key      message key, always the account identifier
     * @param document the event as the wire carries it
     */
    private static void publish(String topic, String key, String document) {
        Properties settings = new Properties();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(settings)) {
            Future<RecordMetadata> pending =
                    producer.send(new ProducerRecord<>(topic, key, document));
            producer.flush();
            RecordMetadata metadata =
                    pending.get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(metadata, "the broker returned no coordinates for the record");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Publishing to " + topic + " was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("The broker refused the record on " + topic + ".", failed);
        } catch (TimeoutException timedOut) {
            throw new AssertionError("The broker did not accept the record on " + topic
                    + " within " + PUBLISH_TIMEOUT + ".", timedOut);
        }
    }

    /**
     * Waits until both replica listeners hold their partitions, so a decision may read their tables.
     *
     * <p>A container that has started and has not yet been assigned anything reads nothing, and a
     * decision against its table cannot be shown to hold what the owner published, so
     * {@code domain/AuthorizationService} refuses with {@code replica-partitions-unassigned}. That is
     * the intended answer rather than a defect: a fresh instance is not ready, and readiness carries
     * the same verdict so an orchestrator withholds traffic until assignment completes. Without this
     * wait, whichever test decided first raced the group's first assignment and refused, which is what
     * happened to the first decision of
     * {@code twoPublishedEventsMoveOneAccountFromExpiredToOverLimitToApproved}.
     *
     * <p>The wait is on the real verdict, so a stream that is genuinely behind still refuses and every
     * comparison below still measures the decision rules rather than the replica rule.
     */
    private void awaitUsableReplicaStreams() {
        Awaitility.await("both replica listeners to hold their partitions")
                .atMost(REFRESH_ARRIVES_WITHIN)
                .pollInterval(POLL_INTERVAL)
                .until(() -> replicaStreams.verdict().usable());
    }

    /** Waits until the snapshot row names the given event as its source. */
    private void awaitSnapshotSourcedFrom(UUID eventId) {
        Awaitility.await("the snapshot recording event " + eventId)
                .atMost(REFRESH_ARRIVES_WITHIN)
                .pollInterval(POLL_INTERVAL)
                .until(() -> eventId.equals(storedSourceEventId()));
    }

    /** Waits until the cross-reference row names the given event as its source. */
    private void awaitCrossReferenceSourcedFrom(UUID eventId) {
        Awaitility.await("the cross-reference recording event " + eventId)
                .atMost(REFRESH_ARRIVES_WITHIN)
                .pollInterval(POLL_INTERVAL)
                .until(() -> eventId.equals(storedCardSourceEventId()));
    }

    /** Waits until a marker exists for the given event on the given topic. */
    private void awaitMarkerFor(UUID eventId, String topic) {
        Awaitility.await("a marker for event " + eventId + " on " + topic)
                .atMost(REFRESH_ARRIVES_WITHIN)
                .pollInterval(POLL_INTERVAL)
                .until(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM processed_event WHERE event_id = ?"
                                + " AND consumed_topic = ?", Integer.class, eventId, topic) == 1);
    }

    /**
     * Waits until the marker count has held at the expected value for the whole quiet window.
     *
     * <p>A redelivery that wrongly applied something would write no second marker, so the marker
     * count alone cannot say the redelivery has been handled yet. Holding the count for a window
     * establishes that the second delivery has been consumed and settled.
     *
     * @param expected markers expected throughout the window
     */
    private void awaitStableMarkerCount(int expected) {
        Awaitility.await("the marker count holding at " + expected)
                .atMost(REFRESH_ARRIVES_WITHIN)
                .pollInterval(POLL_INTERVAL)
                .during(Duration.ofSeconds(3))
                .until(() -> markerCount() == expected);
    }

    /** Reads the credit limit the snapshot replica carries. */
    private BigDecimal storedCreditLimit() {
        return jdbc.queryForObject("SELECT credit_limit FROM account_credit_snapshot"
                + " WHERE account_id = ?", BigDecimal.class, SEEDED_ACCOUNT);
    }

    /** Reads the expiration the snapshot replica carries. */
    private String storedExpiration() {
        return jdbc.queryForObject("SELECT account_expiration_date FROM account_credit_snapshot"
                + " WHERE account_id = ?", String.class, SEEDED_ACCOUNT);
    }

    /** Reads the event the snapshot replica names as its source, or null. */
    private UUID storedSourceEventId() {
        return jdbc.queryForObject("SELECT source_event_id FROM account_credit_snapshot"
                + " WHERE account_id = ?", UUID.class, SEEDED_ACCOUNT);
    }

    /** Reads the moment the snapshot replica was last written. */
    private Instant storedObservedAt() {
        return jdbc.queryForObject("SELECT observed_at FROM account_credit_snapshot"
                + " WHERE account_id = ?", Instant.class, SEEDED_ACCOUNT);
    }

    /** Reads the event the seeded card's cross-reference row names as its source, or null. */
    private UUID storedCardSourceEventId() {
        return jdbc.queryForObject("SELECT source_event_id FROM card_xref WHERE card_number = ?",
                UUID.class, SEEDED_CARD);
    }

    /** Reads the account the seeded card resolves to. */
    private String storedCardAccount() {
        return jdbc.queryForObject("SELECT account_id FROM card_xref WHERE card_number = ?",
                String.class, SEEDED_CARD);
    }

    /** Counts the cross-reference rows the seeded card occupies. */
    private int seededCardRowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM card_xref WHERE card_number = ?",
                Integer.class, SEEDED_CARD);
    }

    /** Counts every marker in the schema. Each test starts from none. */
    private int markerCount() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class);
    }

    /** Builds one account state change in the shape the schema document governs. */
    private static String accountStateJson(UUID eventId, Instant occurredAt, String expiration,
            String limit) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "AccountStateChanged",
                  "schemaVersion": 1,
                  "occurredAt": "%s",
                  "aggregateId": "%s",
                  "accountId": "%s",
                  "currentBalance": "%s",
                  "creditLimit": "%s",
                  "currentCycleCredit": "%s",
                  "currentCycleDebit": "%s",
                  "expirationDate": "%s",
                  "changeKind": "ACCOUNT_UPDATED"
                }
                """.formatted(eventId, OCCURRED_AT_FORMAT.format(occurredAt), SEEDED_ACCOUNT,
                SEEDED_ACCOUNT, REPLICATED_BALANCE, limit, NO_CYCLE_MOVEMENT, NO_CYCLE_MOVEMENT,
                expiration);
    }

    /**
     * Builds one card update in the shape the schema document governs.
     *
     * <p>Version 2 is the version the card service publishes, declared by its
     * {@code messaging/CardUpdated#SCHEMA_VERSION}. Version 1 remains on the classpath and stays
     * readable, and it requires {@code embossedName}, which version 2 withdrew: a document omitting
     * that property while declaring version 1 satisfies no schema and reaches the dead-letter topic
     * instead of the listener.
     */
    private static String cardUpdateJson(UUID eventId, Instant occurredAt, String accountId,
            String maskedCard) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "CardUpdated",
                  "schemaVersion": 2,
                  "occurredAt": "%s",
                  "aggregateId": "%s",
                  "accountId": "%s",
                  "maskedCardNumber": "%s",
                  "expirationDate": "2026-12-31",
                  "activeStatus": "Y"
                }
                """.formatted(eventId, OCCURRED_AT_FORMAT.format(occurredAt), accountId, accountId,
                maskedCard);
    }

    /** Builds the checked tree the account-state listener receives, bypassing the broker. */
    private static JsonNode accountStateTree(UUID eventId, Instant occurredAt, String expiration,
            String limit) {
        return MAPPER.readTree(accountStateJson(eventId, occurredAt, expiration, limit));
    }

    /** An offset commit that counts the times it was taken. */
    private static final class CountingAcknowledgment implements Acknowledgment {

        private final AtomicInteger taken = new AtomicInteger();

        @Override
        public void acknowledge() {
            taken.incrementAndGet();
        }

        /**
         * Answers how many times the offset was committed.
         *
         * @return the count
         */
        private int count() {
            return taken.get();
        }
    }
}
