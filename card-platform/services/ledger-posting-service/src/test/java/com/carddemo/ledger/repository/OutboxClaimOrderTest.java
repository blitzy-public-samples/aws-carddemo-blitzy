package com.carddemo.ledger.repository;

import com.carddemo.ledger.LedgerServiceDatabase;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.carddemo.ledger.TestIdentityPasswords;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.outbox.OutboxRelay;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the claim statement of {@link OutboxEventRepository} against a real database and reads the
 * order it admits rows in.
 *
 * <p>ADDITIVE. No CardDemo program relays an event. The source performs its one asynchronous handoff
 * in paragraph {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl:L517-L518}, which writes a
 * single record and submits a job that reads it, so nothing in {@code app/cbl/} orders a stream of
 * pending events at all.
 *
 * <p>This class exists because the ordering property the statement carries is invisible to every
 * other check. Spring Data validates the query text at start-up and Hibernate validates each mapped
 * column against the migrated schema, but neither runs the correlated absence check that admits one
 * row per account, and a batch of one row satisfies every other test in this module. The defect this
 * holds against is specific: an older event of one account enters retry backoff, a newer event of the
 * same account stays due, the relay publishes the newer one first, and Kafka then preserves that
 * reversal for every consumer of the partition the account identifier keys on.
 *
 * <p>The three properties below are the three the clause exists to hold. A newer row waits behind an
 * older row of its account. A row the relay gave up on releases the account it was holding, so one
 * abandoned event cannot stop every later event of that account. And two rows written in the same
 * instant still resolve to one head, because {@code created_at} alone cannot order them.
 *
 * <p>Flyway owns the schema, so the migration under test is the shipped one. No broker is reached: a
 * stand-in replaces the producer template, and a second replaces the relay so its scheduled sweep
 * does not claim the rows these assertions read. Each test runs in a transaction the framework rolls
 * back, which is also what the row lock the statement takes needs.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(properties = {
    // No listener of this service may retry an absent broker.
    "spring.kafka.listener.auto-startup=false",
    "TOPIC_DEAD_LETTER_SUFFIX=.DLT",
    // One of the four credentials application.yml leaves without a default. The value below is a
    // generated fake this repository states nowhere else, and config/SecurityConfig refuses a blank
    // or published one at start-up. The three identity hashes arrive from card-platform/pom.xml,
    // because a password that is not adaptively encoded is refused.
    "KAFKA_SASL_PASSWORD=a-generated-broker-value-for-the-claim-order-test",
    "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
    "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
    "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
})
@Transactional
@DisplayName("The claim statement admits one head per account, on a real database")
class OutboxClaimOrderTest {

    /** The one event type this service publishes, from {@code com.carddemo.events}. */
    private static final String EVENT_TYPE = "TransactionPosted";

    /** One short payload. No assertion below reads this text. */
    private static final String PAYLOAD = "{\"eventType\":\"TransactionPosted\"}";

    /**
     * The account every row of the paused partition carries, eleven digits with its leading zeros
     * kept, from {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}.
     */
    private static final String ACCOUNT_ID = "00000000011";

    /** A second account, so a test can prove two accounts are claimed independently. */
    private static final String OTHER_ACCOUNT_ID = "00000000012";

    /** A third account, so due-time ordering can be read across three heads. */
    private static final String THIRD_ACCOUNT_ID = "00000000013";

    /** The instance name a claim records, from {@code carddemo.outbox.relay.instance-id}. */
    private static final String INSTANCE = "ledger-relay-under-test";

    /** The reason a failed attempt records. It names a refusal and quotes no event value. */
    private static final String REFUSED = "refused";

    /** The instant every test measures from, beyond any clock reading the seed carries. */
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    /**
     * The lower of the two identifiers the same-instant test writes, and the head it expects.
     *
     * <p>Both identifiers are fixed rather than drawn at random, and the two orderings that could
     * apply agree on which is lower. PostgreSQL orders the {@code uuid} type as an unsigned sequence
     * of octets, while {@link UUID#compareTo(UUID)} compares each half as a signed long, so a test
     * that drew two random identifiers and predicted the head with {@code compareTo} would agree with
     * the database on roughly half of its runs. The head the statement admits is the database's
     * choice, and these two values name it unambiguously.
     */
    private static final UUID LOWER_EVENT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** The higher of the two identifiers the same-instant test writes, under either ordering. */
    private static final UUID HIGHER_EVENT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000002");

    /**
     * The one container this module's test fork runs.
     *
     * <p>{@link LedgerServiceDatabase} owns it and hands this class a database of its own inside
     * it. Nothing here starts or stops a container, and nothing here reads the container's own
     * database name: that database carries no migrated schema.
     */
    static final PostgreSQLContainer POSTGRES = LedgerServiceDatabase.container();

    /** Replaces the producer template, so the context starts with no broker reachable. */
    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> producerTemplate;

    /** Prevents the scheduled publisher from claiming the rows these tests write. */
    @MockitoBean
    private OutboxRelay relay;

    /** The interface under test. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** The flush path. The interface declares no flush method. */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Points the context at the container, selecting the schema the shipped URL selects.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void containerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OutboxClaimOrderTest::databaseUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the URL of this class's own database inside the shared container.
     *
     * <p>The facility creates the database on the first call and puts {@code ledger_service} on
     * the search path, which is what the shipped URL selects.
     *
     * @return the locator this class connects with
     */
    private static String databaseUrl() {
        return LedgerServiceDatabase.urlFor(OutboxClaimOrderTest.class);
    }

    @Test
    @DisplayName("the statement takes the longest-waiting head of each account first")
    void theStatementTakesTheLongestWaitingHeadFirst() {
        OutboxEventEntity oldest = store(NOW.minusSeconds(300), ACCOUNT_ID);
        OutboxEventEntity middle = store(NOW.minusSeconds(200), OTHER_ACCOUNT_ID);
        store(NOW.minusSeconds(100), THIRD_ACCOUNT_ID);

        assertEquals(List.of(oldest.getEventId(), middle.getEventId()), claimed(2),
                "three accounts each offer a head, and the order is next_attempt_at ascending");
    }

    /**
     * A newer row of one account never overtakes an older row of that account.
     *
     * <p>The relay publishes each claimed row and records each outcome on its own, so a batch
     * holding two rows of one account can publish the newer one and leave the older one to a retry.
     * A row in backoff produces the same reversal without a batch at all: its
     * {@code next_attempt_at} lies in the future while the newer row of that account is due now.
     *
     * <p>The third row proves the clause is per account rather than per table. It is the head of a
     * different account, so the paused account does not hold it back and one refusal cannot stop the
     * whole relay.
     */
    @Test
    @DisplayName("a newer row of one account waits behind its older row during backoff")
    void aNewerRowWaitsBehindAnOlderRowOfTheSameAccount() {
        OutboxEventEntity older = store(NOW.minusSeconds(300), ACCOUNT_ID);
        older.claim(INSTANCE, NOW.minusSeconds(290));
        older.recordFailure(REFUSED, NOW.minusSeconds(280), NOW.plusSeconds(30));
        OutboxEventEntity newer = store(NOW.minusSeconds(100), ACCOUNT_ID);
        OutboxEventEntity otherAccount = store(NOW.minusSeconds(50), OTHER_ACCOUNT_ID);
        entityManager.flush();

        List<UUID> claimed = claimed(10);

        assertAll("the claim admits one head per account",
                () -> assertEquals(List.of(otherAccount.getEventId()), claimed,
                        "the newer row of the paused account stays behind it, and the head of the "
                                + "other account is unaffected"),
                () -> assertFalse(claimed.contains(newer.getEventId()),
                        "publishing the newer row first would reverse the order on the partition "
                                + "that account keys on, and Kafka would keep it reversed"),
                () -> assertEquals(OutboxEventEntity.RelayState.PENDING, older.getRelayState(),
                        "the older row is pending and waiting on its backoff, not terminal"));
    }

    /**
     * A row the relay gave up on releases the account it was holding.
     *
     * <p>The absence check names {@code PENDING} and {@code CLAIMED} alone. Were it to name every
     * state, one abandoned row would stop every later row of that account for as long as retention
     * kept it, which is a worse failure than the reversal the check prevents.
     */
    @Test
    @DisplayName("an abandoned head no longer blocks the next row of that account")
    void anAbandonedHeadReleasesTheAccount() {
        OutboxEventEntity abandoned = store(NOW.minusSeconds(600), ACCOUNT_ID);
        for (int attempt = 0; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
            Instant attemptedAt = NOW.minusSeconds(590L - attempt);
            abandoned.claim(INSTANCE, attemptedAt);
            abandoned.recordFailure(REFUSED, attemptedAt, attemptedAt);
        }
        OutboxEventEntity next = store(NOW.minusSeconds(100), ACCOUNT_ID);
        entityManager.flush();

        assertAll("a terminal head holds nothing back",
                () -> assertEquals(OutboxEventEntity.RelayState.ABANDONED,
                        abandoned.getRelayState(),
                        "ten refusals reach the ceiling and abandon the row"),
                () -> assertEquals(List.of(next.getEventId()), claimed(10),
                        "the next row of that account becomes the head"));
    }

    /**
     * Two rows written in the same instant still resolve to one head, and to the same one every
     * time.
     *
     * <p>{@code created_at} alone cannot order them, so the absence check falls through to
     * {@code event_id}. Without that fall-through neither row would precede the other, both would be
     * heads, and one batch could hold the pair.
     *
     * <p>The higher identifier is written first, so passing cannot come from insert order.
     */
    @Test
    @DisplayName("two rows of one account written in the same instant order on the event key")
    void rowsWrittenInTheSameInstantOrderOnTheEventKey() {
        Instant sameInstant = NOW.minusSeconds(120);
        store(sameInstant, ACCOUNT_ID, HIGHER_EVENT_ID);
        store(sameInstant, ACCOUNT_ID, LOWER_EVENT_ID);
        entityManager.flush();

        assertEquals(List.of(LOWER_EVENT_ID), claimed(10),
                "the lower event identifier is the head, so the pair has one deterministic order "
                        + "rather than none");
    }

    /**
     * Writes one row of the named account, due at {@code dueAt}, under a fresh identifier.
     *
     * @param dueAt     when the row becomes claimable, which the constructor also uses as
     *                  {@code created_at}
     * @param accountId the eleven-digit account identifier the row keys on
     * @return the managed row
     */
    private OutboxEventEntity store(Instant dueAt, String accountId) {
        return store(dueAt, accountId, UUID.randomUUID());
    }

    /**
     * Writes one row of the named account, due at {@code dueAt}, and forces it to the database.
     *
     * @param dueAt     when the row becomes claimable, which the constructor also uses as
     *                  {@code created_at}
     * @param accountId the eleven-digit account identifier the row keys on
     * @param eventId   the primary key the row takes
     * @return the managed row
     */
    private OutboxEventEntity store(Instant dueAt, String accountId, UUID eventId) {
        OutboxEventEntity stored = outboxEvents.save(new OutboxEventEntity(
                eventId, EVENT_TYPE, accountId, PAYLOAD, dueAt));
        entityManager.flush();
        return stored;
    }

    /**
     * Claims what is due at {@link #NOW} and answers with the identifiers, in the order returned.
     *
     * @param rows the greatest number of rows to claim
     * @return the identifiers of the claimed heads
     */
    private List<UUID> claimed(int rows) {
        return outboxEvents.claimDueRows(NOW, Limit.of(rows)).stream()
                .map(OutboxEventEntity::getEventId)
                .toList();
    }
}
