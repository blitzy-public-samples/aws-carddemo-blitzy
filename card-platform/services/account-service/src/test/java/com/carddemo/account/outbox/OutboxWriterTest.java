package com.carddemo.account.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.KafkaProducerConfig;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.messaging.CustomerContextChanged;
import com.carddemo.account.repository.AbstractAccountPostgresTest;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks that the earliest boundary an account event crosses is also the first one that measures it
 * against its governed contract document.
 *
 * <p>No COBOL ancestor for the check itself. The unit of work it protects is the source's:
 * {@code app/cbl/COACTUPC.cbl:L4066} rewrites the account record and
 * {@code app/cbl/COACTUPC.cbl:L4086} rewrites the customer record, and all eight file definitions in
 * {@code app/csd/CARDDEMO.CSD} carry {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}. The target
 * commits both rewrites and the event row together, so a refused event has to take the rewrites with
 * it.
 *
 * <p><b>Why an event a record accepted can still break its document.</b> Each event record checks
 * its own components, and each governed document checks more than the record does. Two live examples
 * drive the refusal tests below rather than a contrived payload or a stubbed mapper.
 * {@link AccountStateChanged} accepts an {@code expirationDate} of at most ten characters, and
 * {@code account-state-changed-v1.json} requires exactly ten. {@link CustomerContextChanged} accepts
 * any {@link Instant}, and {@code customer-context-changed-v1.json} bounds the year to four digits.
 * Either payload once committed would have sat in {@code outbox_event} beside a committed rewrite,
 * and the relay would have met it on every sweep until it abandoned the row.
 */
@DisplayName("OutboxWriter measures an event against its document before it stores it")
class OutboxWriterTest {

    /** Account identifier every event here carries, eleven digits as the column holds them. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Event identifier every event here carries, named in a refusal and nowhere else. */
    private static final UUID EVENT_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    /** A moment the document accepts: four-digit year, seconds present, zone {@code Z}. */
    private static final Instant OCCURRED_AT = Instant.parse("2024-05-01T00:00:00Z");

    /** The expiry text the document accepts, ten characters exactly. */
    private static final String TEN_CHARACTER_EXPIRY = "2025-12-31";

    /** One character short of what the document requires, and one the record accepts. */
    private static final String NINE_CHARACTER_EXPIRY = "2025-12-3";

    /** The mapper the shipped configuration builds, so these tests read the production wire form. */
    private static final ObjectMapper MAPPER =
            new KafkaProducerConfig(properties()).accountEventObjectMapper();

    @Test
    @DisplayName("a state event the document accepts reaches the table as the checked text")
    void aStateEventTheDocumentAcceptsReachesTheTable() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        new OutboxWriter(rows, MAPPER).write(stateChange(TEN_CHARACTER_EXPIRY));

        verify(rows).save(rowWith(payload -> {
            assertThat(payload.getEventType()).isEqualTo(AccountStateChanged.EVENT_TYPE);
            assertThat(payload.getAggregateId()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.getPayload())
                    .as("the stored text is the text the check measured")
                    .contains("\"expirationDate\":\"" + TEN_CHARACTER_EXPIRY + "\"");
        }));
    }

    @Test
    @DisplayName("a context event the document accepts reaches the table")
    void aContextEventTheDocumentAcceptsReachesTheTable() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        new OutboxWriter(rows, MAPPER).writeCustomerContext(contextChange(OCCURRED_AT));

        verify(rows).save(rowWith(row ->
                assertThat(row.getEventType()).isEqualTo(CustomerContextChanged.EVENT_TYPE)));
    }

    @Test
    @DisplayName("an expiry one character short of the document stores nothing")
    void anExpiryOneCharacterShortOfTheDocumentStoresNothing() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        OutboxWriter writer = new OutboxWriter(rows, MAPPER);
        AccountStateChanged refused = stateChange(NINE_CHARACTER_EXPIRY);

        assertThatThrownBy(() -> writer.write(refused))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(EVENT_ID.toString())
                .hasMessageContaining("/expirationDate");

        verify(rows, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    @DisplayName("a moment outside the document's year bound stores nothing")
    void aMomentOutsideTheDocumentsYearBoundStoresNothing() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        OutboxWriter writer = new OutboxWriter(rows, MAPPER);
        CustomerContextChanged refused = contextChange(Instant.parse("+12024-05-01T00:00:00Z"));

        assertThatThrownBy(() -> writer.writeCustomerContext(refused))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(EVENT_ID.toString())
                .hasMessageContaining("/occurredAt");

        verify(rows, never()).save(any(OutboxEventEntity.class));
    }

    @Test
    @DisplayName("a refusal names the broken property and no value the payload carries")
    void aRefusalNamesTheBrokenPropertyAndNoPayloadValue() {
        OutboxWriter writer = new OutboxWriter(mock(OutboxEventRepository.class), MAPPER);
        CustomerContextChanged refused = new CustomerContextChanged(EVENT_ID,
                CustomerContextChanged.EVENT_TYPE, CustomerContextChanged.SCHEMA_VERSION,
                Instant.parse("+12024-05-01T00:00:00Z"), ACCOUNT_ID, ACCOUNT_ID,
                "Wilhelmina", "Q", "Ostrowski", "412 Sanderling Way", "Suite 9",
                "Fort Collins", "CO", "USA", "80521", "782");

        assertThatThrownBy(() -> writer.writeCustomerContext(refused))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .as("a refusal reaches a log, so it carries no cardholder value")
                        .doesNotContain("Wilhelmina")
                        .doesNotContain("Ostrowski")
                        .doesNotContain("Sanderling")
                        .doesNotContain("80521")
                        .doesNotContain("782"));
    }

    @Test
    @DisplayName("the column ceiling still refuses an oversized payload")
    void theColumnCeilingStillRefusesAnOversizedPayload() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        OutboxWriter writer = new OutboxWriter(rows, MAPPER);
        String widest = "X".repeat(OutboxWriter.PAYLOAD_MAX_BYTES);
        ObjectMapper inflating = MAPPER;
        AccountStateChanged accepted = stateChange(TEN_CHARACTER_EXPIRY);

        assertThat(inflating.writeValueAsString(accepted).length())
                .as("a well-formed state event fits the column many times over, so the ceiling is "
                        + "reached only by a payload the document would also refuse")
                .isLessThan(widest.length());
        writer.write(accepted);

        verify(rows).save(any(OutboxEventEntity.class));
    }

    /**
     * Proves the refusal and the business rewrite roll back as one, against a real database.
     *
     * <p>A mock repository cannot show this. The claim is about a transaction boundary, so the test
     * opens one, mutates a seeded account inside it, refuses an event inside it, and then reads the
     * account back in a fresh transaction.
     */
    @Nested
    @DisplayName("A refused event takes the business mutation with it")
    class TransactionBoundary extends AbstractAccountPostgresTest {

        /** The seeded account this test mutates and then expects to find unchanged. */
        private static final String SEEDED_ACCOUNT = "00000000001";

        /** A credit limit no seeded row carries, so a leaked commit is unmistakable. */
        private static final BigDecimal LEAKED_LIMIT = new BigDecimal("99999999.99");

        @Autowired
        private AccountRepository accounts;

        @Autowired
        private OutboxEventRepository outboxEvents;

        @Autowired
        private PlatformTransactionManager transactionManager;

        @Test
        @DisplayName("neither the account rewrite nor the event row survives a refused payload")
        void neitherTheRewriteNorTheEventRowSurvives() {
            OutboxWriter writer = new OutboxWriter(outboxEvents, MAPPER);
            BigDecimal before = inNewTransaction(() ->
                    accounts.findById(SEEDED_ACCOUNT).orElseThrow().getCreditLimit());
            long rowsBefore = inNewTransaction(outboxEvents::count);

            assertThatThrownBy(() -> inNewTransaction(() -> {
                AccountEntity account = accounts.findById(SEEDED_ACCOUNT).orElseThrow();
                account.setCreditLimit(LEAKED_LIMIT);
                accounts.save(account);
                writer.write(stateChange(NINE_CHARACTER_EXPIRY));
                return null;
            })).isInstanceOf(IllegalArgumentException.class);

            assertThat(inNewTransaction(() ->
                    accounts.findById(SEEDED_ACCOUNT).orElseThrow().getCreditLimit()))
                    .as("the rewrite rolled back with the refused event")
                    .isEqualByComparingTo(before);
            assertThat(inNewTransaction(outboxEvents::count))
                    .as("no event row committed beside the rewrite")
                    .isEqualTo(rowsBefore);
        }

        @Test
        @DisplayName("the same mutation commits once the payload matches the document")
        void theSameMutationCommitsOnceThePayloadMatches() {
            OutboxWriter writer = new OutboxWriter(outboxEvents, MAPPER);
            BigDecimal before = inNewTransaction(() ->
                    accounts.findById(SEEDED_ACCOUNT).orElseThrow().getCreditLimit());

            try {
                inNewTransaction(() -> {
                    AccountEntity account = accounts.findById(SEEDED_ACCOUNT).orElseThrow();
                    account.setCreditLimit(LEAKED_LIMIT);
                    accounts.save(account);
                    writer.write(stateChange(TEN_CHARACTER_EXPIRY));
                    return null;
                });

                assertThat(inNewTransaction(() -> outboxEvents.findById(EVENT_ID)))
                        .as("the accepted event committed with the rewrite, so the refusal above is "
                                + "what rolled the other one back")
                        .isPresent();
                assertThat(inNewTransaction(() ->
                        accounts.findById(SEEDED_ACCOUNT).orElseThrow().getCreditLimit()))
                        .as("the rewrite committed too")
                        .isEqualByComparingTo(LEAKED_LIMIT);
            } finally {
                // This test commits, unlike every other class here, which rolls back. The restore
                // runs even after a failed assertion, because the seeded credit limit is what four
                // other test classes read.
                inNewTransaction(() -> {
                    AccountEntity account = accounts.findById(SEEDED_ACCOUNT).orElseThrow();
                    account.setCreditLimit(before);
                    accounts.save(account);
                    outboxEvents.deleteAllById(List.of(EVENT_ID));
                    return null;
                });
            }

            assertThat(inNewTransaction(() ->
                    accounts.findById(SEEDED_ACCOUNT).orElseThrow().getCreditLimit()))
                    .as("the seeded credit limit is restored for every other test class")
                    .isEqualByComparingTo(before);
            assertThat(inNewTransaction(() -> outboxEvents.findById(EVENT_ID)))
                    .as("and the committed event row is removed again")
                    .isEmpty();
        }

        /**
         * Runs one callback in a transaction of its own and returns its result.
         *
         * @param <T>      the result type
         * @param callback the work to run
         * @return whatever the callback answered
         */
        private <T> T inNewTransaction(java.util.function.Supplier<T> callback) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return template.execute(status -> callback.get());
        }
    }

    /**
     * Builds one account state change carrying the supplied expiry text.
     *
     * @param expirationDate the expiry text the event carries
     * @return an event whose components the record accepts
     */
    private static AccountStateChanged stateChange(String expirationDate) {
        return new AccountStateChanged(EVENT_ID, AccountStateChanged.EVENT_TYPE,
                AccountStateChanged.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID, ACCOUNT_ID,
                AccountStateChanged.ChangeKind.ACCOUNT_UPDATED, new BigDecimal("120.45"),
                new BigDecimal("5000.00"), new BigDecimal("10.00"), new BigDecimal("20.00"),
                expirationDate);
    }

    /**
     * Builds one customer context change occurring at the supplied moment.
     *
     * @param occurredAt the moment the event records
     * @return an event whose components the record accepts
     */
    private static CustomerContextChanged contextChange(Instant occurredAt) {
        return new CustomerContextChanged(EVENT_ID, CustomerContextChanged.EVENT_TYPE,
                CustomerContextChanged.SCHEMA_VERSION, occurredAt, ACCOUNT_ID, ACCOUNT_ID,
                "Ada", "B", "Lovelace", "1 Analytical Way", "Flat 2", "London", "NY", "USA",
                "10001", "701");
    }

    /**
     * Builds the argument matcher {@code save} is verified with, running assertions on the row.
     *
     * @param assertions what the captured row must satisfy
     * @return a matcher that always matches and asserts on the way through
     */
    private static OutboxEventEntity rowWith(java.util.function.Consumer<OutboxEventEntity> asserts) {
        return org.mockito.ArgumentMatchers.argThat(row -> {
            asserts.accept(row);
            return true;
        });
    }

    /** Returns the bound settings block, with the three topic names the shipped file carries. */
    private static AccountProperties properties() {
        return new AccountProperties(
                new AccountProperties.Api(65536L),
                new AccountProperties.Kafka(
                        new AccountProperties.Kafka.Topics("account.state-changed",
                                "customer.context-changed", "carddemo.dead-letter")),
                new AccountProperties.Outbox(
                        new AccountProperties.Outbox.Relay(500L, 1, "writer-test",
                                Duration.ofSeconds(30L), 1_000L, Duration.ofSeconds(10L)), 168L),
                new AccountProperties.ProcessedEvent(168L),
                new AccountProperties.Retention(3_600_000L));
    }
}
