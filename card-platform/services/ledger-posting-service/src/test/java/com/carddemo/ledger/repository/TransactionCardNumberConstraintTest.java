package com.carddemo.ledger.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.ledger.LedgerServiceDatabase;
import com.carddemo.ledger.TestIdentityPasswords;
import com.carddemo.ledger.entity.TransactionEntity;
import com.carddemo.ledger.outbox.OutboxRelay;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs {@code ck_transaction_card_number} against a real database, from outside the entity.
 *
 * <p>This class exists because the check it asserts is the only enforcement that survives a writer
 * other than {@link TransactionEntity}. That entity has refused an unmasked card number since
 * {@code V1__schema.sql} through its own guard, and a review of the delivered platform found the
 * column itself unconstrained: {@code ck_rejected_transaction_masked_card_number} covered the reject
 * block of the same schema, {@code ck_notification_log_card_number} the notification read model and
 * {@code ck_authorization_decision_masked_card} the decision row, and this column had nothing.
 * {@code V12__transaction_card_number_is_masked.sql} closes that, and every statement below writes in
 * raw Structured Query Language so the entity guard takes no part in the result.
 *
 * <p>Three shapes are put to the database. The masked form of
 * {@link TransactionEntity#MASKED_CARD_NUMBER_PATTERN} is accepted, which is the form every writer of
 * this table produces. A full sixteen-digit Primary Account Number is refused, which is the value
 * {@code TRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L15} holds in the source and no row
 * of this table may hold. Sixteen mask characters are refused too, and that case is the reason the
 * check is not the wider one its sibling uses: {@code cobol.PanMasker} answers a card it cannot read
 * with sixteen mask characters, a refusal record may carry that value, and a posted transaction
 * exists only for a card that was read.
 *
 * <p>Flyway owns the schema, so the constraint under test is the shipped one. No broker is reached: a
 * stand-in replaces the producer template and the relay.
 */
@SpringBootTest(properties = {
    // No listener of this service may retry an absent broker.
    "spring.kafka.listener.auto-startup=false",
    "TOPIC_DEAD_LETTER_SUFFIX=.DLT",
    // One of the four credentials application.yml leaves without a default. The value below is a
    // generated fake this repository states nowhere else, and config/SecurityConfig refuses a blank
    // or published one at start-up. The three identity hashes arrive from card-platform/pom.xml,
    // because a password that is not adaptively encoded is refused.
    "KAFKA_SASL_PASSWORD=a-generated-broker-value-for-the-card-number-check",
    "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
    "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
    "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
})
@DisplayName("ck_transaction_card_number, on a real database and outside the entity")
class TransactionCardNumberConstraintTest {

    /** The constraint {@code V12__transaction_card_number_is_masked.sql} adds. */
    private static final String CONSTRAINT = "ck_transaction_card_number";

    /** Identifier these statements write, which no seed of this service carries. */
    private static final String TRANSACTION_ID = "0000009000000001";

    /** The accepted form: twelve mask characters and the last four digits. */
    private static final String MASKED = "************7065";

    /** A full sixteen-digit card number, which no row of this table may carry. */
    private static final String UNMASKED = "4111111111117065";

    /** Sixteen mask characters, the form a card that could not be read produces. */
    private static final String FULLY_MASKED = "****************";

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link LedgerServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    static final PostgreSQLContainer POSTGRES = LedgerServiceDatabase.container();

    /** Replaces the producer template, so the context starts with no broker reachable. */
    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> producerTemplate;

    /** Prevents the scheduled publisher from running beside these assertions. */
    @MockitoBean
    private OutboxRelay relay;

    private JdbcTemplate database;

    /**
     * Points the context at the container, selecting the schema the shipped URL selects.
     *
     * @param registry the registry the test context resolves properties from
     */
    @DynamicPropertySource
    static void containerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> LedgerServiceDatabase.urlFor(TransactionCardNumberConstraintTest.class));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Resolves the template and clears the one row these statements write. */
    @BeforeEach
    void resolveTemplateAndClearRow(ApplicationContext context) {
        database = context.getBean(JdbcTemplate.class);
        database.update("DELETE FROM ledger_service.transaction WHERE transaction_id = ?",
                TRANSACTION_ID);
    }

    @Test
    @DisplayName("the catalogue carries the check, and its definition is the narrow masked form")
    void theCatalogueCarriesTheNarrowCheck() {
        String definition = database.queryForObject(
                "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c"
                        + " JOIN pg_class t ON t.oid = c.conrelid"
                        + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                        + " WHERE n.nspname = 'ledger_service' AND t.relname = 'transaction'"
                        + " AND c.conname = ?",
                String.class, CONSTRAINT);
        assertTrue(definition.contains("*{12}[0-9]{4}"),
                CONSTRAINT + " must hold the column to twelve mask characters and four digits, and"
                        + " the catalogue reads: " + definition);
        assertTrue(definition.contains("card_number"),
                CONSTRAINT + " must read the card_number column, and the catalogue reads: "
                        + definition);
    }

    @Test
    @DisplayName("a masked card number is accepted, which is what every writer produces")
    void aMaskedCardNumberIsAccepted() {
        assertEquals(1, insert(MASKED),
                "the masked form is the one shape this table holds, so the insert must succeed");
        assertEquals(MASKED, storedCardNumber(),
                "the row must hold the value the statement wrote, unaltered");
    }

    @Test
    @DisplayName("a full card number is refused by the database, not merely by the entity")
    void aFullCardNumberIsRefused() {
        DataIntegrityViolationException refusal =
                assertThrows(DataIntegrityViolationException.class, () -> insert(UNMASKED));
        assertTrue(refusal.getMessage().contains(CONSTRAINT),
                "the refusal must name " + CONSTRAINT + ", so an operator reads which rule stopped"
                        + " the write. It read: " + refusal.getMessage());
        assertEquals(0, rowCount(), "a refused insert must leave no row behind");
    }

    @Test
    @DisplayName("sixteen mask characters are refused, because a posted transaction read its card")
    void sixteenMaskCharactersAreRefused() {
        DataIntegrityViolationException refusal =
                assertThrows(DataIntegrityViolationException.class, () -> insert(FULLY_MASKED));
        assertTrue(refusal.getMessage().contains(CONSTRAINT),
                "the wider form its sibling check admits belongs to a refusal record rather than to"
                        + " a posted transaction. It read: " + refusal.getMessage());
        assertEquals(0, rowCount(), "a refused insert must leave no row behind");
    }

    /**
     * Writes one row in raw Structured Query Language, so the entity guard takes no part.
     *
     * @param cardNumber the value to put to the column under test
     * @return the row count the statement answered
     */
    private int insert(String cardNumber) {
        return database.update("INSERT INTO ledger_service.transaction (transaction_id, type_code,"
                + " category_code, source, description, amount, merchant_id, merchant_name,"
                + " merchant_city, merchant_zip, card_number, origin_timestamp, processed_timestamp)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TRANSACTION_ID, "01", "5411", "POS", "A row written to reach the check",
                new BigDecimal("125.00"), "000000123", "A MERCHANT", "A CITY", "00000", cardNumber,
                "2026-01-01 00:00:00.000000", "2026-01-01 00:00:00.000000");
    }

    /** Returns how many rows carry the identifier under test. */
    private int rowCount() {
        return database.queryForObject("SELECT COUNT(*) FROM ledger_service.transaction"
                + " WHERE transaction_id = ?", Integer.class, TRANSACTION_ID);
    }

    /** Returns the stored card number of the row under test. */
    private String storedCardNumber() {
        return database.queryForObject("SELECT card_number FROM ledger_service.transaction"
                + " WHERE transaction_id = ?", String.class, TRANSACTION_ID);
    }
}
