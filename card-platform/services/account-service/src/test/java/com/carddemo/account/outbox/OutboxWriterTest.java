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
 * <p><b>An event a record accepted can still break its document.</b> Each event record checks
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
        new OutboxWriter(rows).write(stateChange(TEN_CHARACTER_EXPIRY));

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
        new OutboxWriter(rows).writeCustomerContext(contextChange(OCCURRED_AT));

        verify(rows).save(rowWith(row ->
                assertThat(row.getEventType()).isEqualTo(CustomerContextChanged.EVENT_TYPE)));
    }

    @Test
    @DisplayName("an expiry one character short of the document stores nothing")
    void anExpiryOneCharacterShortOfTheDocumentStoresNothing() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        OutboxWriter writer = new OutboxWriter(rows);
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
        OutboxWriter writer = new OutboxWriter(rows);
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
        OutboxWriter writer = new OutboxWriter(mock(OutboxEventRepository.class));
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
        OutboxWriter writer = new OutboxWriter(rows);
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
     * What the publish gate refuses inside a customer free-text field, before a row exists.
     *
     * <p>{@link CustomerContextChanged} is the one payload of this platform whose fields a person
     * filled in. {@code CUST-FIRST-NAME}, {@code CUST-LAST-NAME} and the three address lines of
     * {@code app/cpy/CVCUS01Y.cpy:L6-L16} are free text at the source, and
     * {@code app/cbl/COACTUPC.cbl:L1205-L1280} checks their width and their character class and
     * nothing about what they say. A cardholder number typed into an address line is therefore a
     * shape the source admits, which is why the gate screens it on the way into the outbox.
     *
     * <p>Each test asserts the repository was never asked to save. A row that reached the repository
     * would commit with the customer rewrite of {@code app/cbl/COACTUPC.cbl:L4086} and publish
     * afterwards, so the screen has to run before the row, not before the send.
     */
    @Nested
    @DisplayName("Customer free-text fields are screened before a row is saved")
    class ScreenedCustomerNarrative {

        /** Asserts a cardholder number in an address line stops the write. */
        @Test
        @DisplayName("a card number in an address line stores nothing")
        void aCardNumberInAnAddressLineStoresNothing() {
            assertRefused(context -> context.addressLine1("4111111111111111"), "digit run");
        }

        /** Asserts the punctuated form of the same number stops the write. */
        @Test
        @DisplayName("a punctuated card number in an address line stores nothing")
        void aPunctuatedCardNumberInAnAddressLineStoresNothing() {
            assertRefused(context -> context.addressLine2("4111-1111-1111-1111"),
                    "separated digit run");
        }

        /** Asserts a formatted social security number in a name stops the write. */
        @Test
        @DisplayName("a formatted social security number in a last name stores nothing")
        void aFormattedSocialSecurityNumberInALastNameStoresNothing() {
            assertRefused(context -> context.lastName("Lovelace 123-45-6789"), "social security");
        }

        /** Asserts the unseparated form of the same number stops the write. */
        @Test
        @DisplayName("an unseparated social security number in an address line stores nothing")
        void anUnseparatedSocialSecurityNumberInAnAddressLineStoresNothing() {
            assertRefused(context -> context.addressLine3("Ref 123456789"), "nine digits");
        }

        /** Asserts the middle name is screened on the same terms as the other two. */
        @Test
        @DisplayName("a card number in a middle name stores nothing")
        void aCardNumberInAMiddleNameStoresNothing() {
            assertRefused(context -> context.middleName("4111111111111111"), "middle name");
        }

        /** Asserts a labelled verification code in a name stops the write. */
        @Test
        @DisplayName("a labelled verification code in a first name stores nothing")
        void aLabelledVerificationCodeInAFirstNameStoresNothing() {
            assertRefused(context -> context.firstName("Ada cvv 123"), "verification");
        }

        /**
         * Asserts the postal code keeps both forms its source field carries.
         *
         * <p>{@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L16} holds ten
         * characters, and {@code app/data/ASCII/custdata.txt} carries both {@code 12546} and
         * {@code 19852-6716}. The second is nine digits with a separator, which is the shape a social
         * security number takes, so the postal code is screened for a value that looks like a
         * cardholder number and not for that shape. A screen that refused it would refuse a seeded
         * customer.
         */
        @Test
        @DisplayName("a nine-digit postal code still reaches the table")
        void aNineDigitPostalCodeStillReachesTheTable() {
            OutboxEventRepository rows = mock(OutboxEventRepository.class);

            new OutboxWriter(rows).writeCustomerContext(context().zipCode("19852-6716").build());

            verify(rows).save(any(OutboxEventEntity.class));
        }

        /** Asserts an ordinary address still reaches a row, so the screen has not closed the field. */
        @Test
        @DisplayName("an ordinary address still reaches the table")
        void anOrdinaryAddressStillReachesTheTable() {
            OutboxEventRepository rows = mock(OutboxEventRepository.class);

            new OutboxWriter(rows).writeCustomerContext(context().build());

            verify(rows).save(any(OutboxEventEntity.class));
        }

        /**
         * Applies one change to an otherwise acceptable context event and asserts nothing was saved.
         *
         * @param change what to put into which free-text field
         * @param what   the shape under test, named in the assertion message
         */
        private void assertRefused(java.util.function.Consumer<ContextBuilder> change, String what) {
            OutboxEventRepository rows = mock(OutboxEventRepository.class);
            ContextBuilder builder = context();
            change.accept(builder);
            CustomerContextChanged event = builder.build();

            assertThatThrownBy(() -> new OutboxWriter(rows).writeCustomerContext(event))
                    .as("the gate admitted a " + what + " into an outbox row")
                    .isInstanceOf(IllegalArgumentException.class);
            verify(rows, never()).save(any(OutboxEventEntity.class));
        }
    }

    /**
     * Proves a refused customer field takes the rewrite with it, against a real database.
     *
     * <p>The in-memory tests above prove no row is offered to the repository. This one proves the
     * business mutation beside it does not survive either, which is the property
     * {@code app/csd/CARDDEMO.CSD} could not offer: every file definition there carries
     * {@code RECOVERY(NONE)}, so the source's two rewrites at {@code app/cbl/COACTUPC.cbl:L4066} and
     * {@code app/cbl/COACTUPC.cbl:L4086} could commit one and lose the other.
     */
    @Nested
    @DisplayName("A screened customer field takes the business mutation with it")
    class ScreenedCustomerTransactionBoundary extends AbstractAccountPostgresTest {

        /** The seeded account this test mutates and then expects to find unchanged. */
        private static final String SEEDED_ACCOUNT = "00000000001";

        /** A credit limit no seeded row carries, so a leaked commit is unmistakable. */
        private static final BigDecimal LEAKED_LIMIT = new BigDecimal("88888888.88");

        @Autowired
        private AccountRepository accounts;

        @Autowired
        private OutboxEventRepository outboxEvents;

        @Autowired
        private PlatformTransactionManager transactionManager;

        @Test
        @DisplayName("neither the rewrite nor the event row survives a screened address line")
        void neitherTheRewriteNorTheEventRowSurvivesAScreenedAddressLine() {
            OutboxWriter writer = new OutboxWriter(outboxEvents);
            BigDecimal before = inNewTransaction(() ->
                    accounts.findById(SEEDED_ACCOUNT).orElseThrow().getCreditLimit());
            long rowsBefore = inNewTransaction(outboxEvents::count);

            assertThatThrownBy(() -> inNewTransaction(() -> {
                AccountEntity account = accounts.findById(SEEDED_ACCOUNT).orElseThrow();
                account.setCreditLimit(LEAKED_LIMIT);
                accounts.save(account);
                writer.writeCustomerContext(
                        context().addressLine1("4111111111111111").build());
                return null;
            })).isInstanceOf(IllegalArgumentException.class);

            assertThat(inNewTransaction(() ->
                    accounts.findById(SEEDED_ACCOUNT).orElseThrow().getCreditLimit()))
                    .as("the rewrite rolled back with the screened event")
                    .isEqualByComparingTo(before);
            assertThat(inNewTransaction(outboxEvents::count))
                    .as("no event row committed beside the rewrite")
                    .isEqualTo(rowsBefore);
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

    /** @return a builder holding the components the document accepts, one change away from a test */
    private static ContextBuilder context() {
        return new ContextBuilder();
    }

    /**
     * Collects the eleven payload components of one context event so a test can change exactly one.
     *
     * <p>A record has no setters, and repeating an eleven-argument constructor in every test above
     * would hide which value the test is about. This builder starts from the accepted values
     * {@link #contextChange(Instant)} uses and lets one test name one field.
     */
    private static final class ContextBuilder {

        /** Given name, from {@code CUST-FIRST-NAME PIC X(25)}. */
        private String firstName = "Ada";

        /** Middle name, from {@code CUST-MIDDLE-NAME PIC X(25)}. */
        private String middleName = "B";

        /** Family name, from {@code CUST-LAST-NAME PIC X(25)}. */
        private String lastName = "Lovelace";

        /** First address line, from {@code CUST-ADDR-LINE-1 PIC X(50)}. */
        private String addressLine1 = "1 Analytical Way";

        /** Second address line, from {@code CUST-ADDR-LINE-2 PIC X(50)}. */
        private String addressLine2 = "Flat 2";

        /** Third address line, from {@code CUST-ADDR-LINE-3 PIC X(50)}. */
        private String addressLine3 = "London";

        /** Postal code, from {@code CUST-ADDR-ZIP PIC X(10)}. */
        private String zipCode = "10001";

        /**
         * @param value the given name this event carries
         * @return this builder
         */
        private ContextBuilder firstName(String value) {
            this.firstName = value;
            return this;
        }

        /**
         * @param value the middle name this event carries
         * @return this builder
         */
        private ContextBuilder middleName(String value) {
            this.middleName = value;
            return this;
        }

        /**
         * @param value the family name this event carries
         * @return this builder
         */
        private ContextBuilder lastName(String value) {
            this.lastName = value;
            return this;
        }

        /**
         * @param value the first address line this event carries
         * @return this builder
         */
        private ContextBuilder addressLine1(String value) {
            this.addressLine1 = value;
            return this;
        }

        /**
         * @param value the second address line this event carries
         * @return this builder
         */
        private ContextBuilder addressLine2(String value) {
            this.addressLine2 = value;
            return this;
        }

        /**
         * @param value the third address line this event carries
         * @return this builder
         */
        private ContextBuilder addressLine3(String value) {
            this.addressLine3 = value;
            return this;
        }

        /**
         * @param value the postal code this event carries
         * @return this builder
         */
        private ContextBuilder zipCode(String value) {
            this.zipCode = value;
            return this;
        }

        /** @return the context event these components describe */
        private CustomerContextChanged build() {
            return new CustomerContextChanged(EVENT_ID, CustomerContextChanged.EVENT_TYPE,
                    CustomerContextChanged.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID, ACCOUNT_ID,
                    firstName, middleName, lastName, addressLine1, addressLine2, addressLine3,
                    "NY", "USA", zipCode, "701");
        }
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
            OutboxWriter writer = new OutboxWriter(outboxEvents);
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
            OutboxWriter writer = new OutboxWriter(outboxEvents);
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
     * @param asserts what the captured row must satisfy
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
                                "customer.context-changed", "transaction.posted",
                                "carddemo.dead-letter"),
                        new AccountProperties.Kafka.Groups("account-posted")),
                new AccountProperties.Consumer(new AccountProperties.Consumer.Retry(3, 1_000L)),
                new AccountProperties.Outbox(
                        new AccountProperties.Outbox.Relay(500L, 1, "writer-test",
                                Duration.ofSeconds(30L), 1_000L, Duration.ofSeconds(10L)), 168L),
                new AccountProperties.Retention(3_600_000L),
                new AccountProperties.Write(3_000L));
    }
}
