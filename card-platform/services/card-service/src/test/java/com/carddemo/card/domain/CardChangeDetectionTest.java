package com.carddemo.card.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.carddemo.card.CardServiceDatabase;
import com.carddemo.card.TestIdentityPasswords;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse.RefreshedCard;
import com.carddemo.card.api.dto.CardUpdateResponse.UpdateOutcome;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.config.ObservabilityConfig;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.repository.CardRepository;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Container-backed tests for the concurrency comparison {@link CardUpdateService} performs.
 *
 * <p>The behaviour comes from the card update program {@code app/cbl/COCRDUPC.cbl}, a Customer
 * Information Control System (CICS) transaction. Three of its paragraphs supply it.
 *
 * <p>{@code 9000-READ-DATA.} at {@code app/cbl/COCRDUPC.cbl:L1343-L1372} takes the snapshot the
 * caller holds. {@code 9200-WRITE-PROCESSING.} at {@code app/cbl/COCRDUPC.cbl:L1420-L1494} locks
 * the row and rewrites it. {@code 9300-CHECK-CHANGE-IN-REC.} at
 * {@code app/cbl/COCRDUPC.cbl:L1498-L1521} compares the locked row against that snapshot.
 *
 * <h2>What this class asserts</h2>
 *
 * <p>Six subjects, and no other:</p>
 *
 * <ul>
 * <li>the compared-field breakdown at {@code app/cbl/COCRDUPC.cbl:L1503-L1508};</li>
 * <li>the refresh at {@code app/cbl/COCRDUPC.cbl:L1512-L1517};</li>
 * <li>the upper-case fold at {@code app/cbl/COCRDUPC.cbl:L1356-L1358} and at
 * {@code app/cbl/COCRDUPC.cbl:L1499-L1501};</li>
 * <li>the absence of a version column;</li>
 * <li>the lock branch at {@code app/cbl/COCRDUPC.cbl:L1441-L1448};</li>
 * <li>the unguarded text at {@code app/cbl/COCRDUPC.cbl:L1490-L1491}.</li>
 * </ul>
 *
 * <p>Paging and the two finders belong to {@code CardQueryServiceTest}. The field edits, their
 * order, the expiry reassembly and the outbox handoff belong to {@code CardUpdateServiceTest}. No
 * method below repeats one of those.
 *
 * <h2>How a stale snapshot reaches the service</h2>
 *
 * <p>{@link CardUpdateService#applyUpdate(String, CardUpdateRequest,
 * CardUpdateResponse.RefreshedCard, LocalDate)} takes the
 * values the caller last saw as its second argument. The source does the same:
 * {@code app/cbl/COCRDUPC.cbl:L1503-L1508} compares the locked record against the
 * {@code CCUP-OLD-} fields {@code 9000-READ-DATA.} filled on the preceding screen turn.
 *
 * <p>Each test builds that snapshot from the values {@code V2__seed.sql} loaded. It then changes
 * the row through {@link JdbcTemplate} on a separate connection that commits at once, and calls
 * the service with the snapshot that has just gone stale.
 *
 * <p>The private {@code snapshotOf} of {@link CardUpdateService} folds the embossed name to upper
 * case and pads each value to the width its Picture clause declares.
 * {@link #snapshotOf(String, LocalDate, String)} below repeats those two conventions, so a
 * snapshot a test builds matches one the service builds.
 *
 * <p>That one folded snapshot is what the comparison reads AND what a refusal answers with, because
 * the source has only one. {@code 9300-CHECK-CHANGE-IN-REC.} runs
 * {@code INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER} at
 * {@code app/cbl/COCRDUPC.cbl:L1499-L1501} over the record field <em>in place</em> rather than over a
 * copy of it, so the field is already upper case when the comparison at
 * {@code app/cbl/COCRDUPC.cbl:L1504} reads it and when the
 * {@code MOVE CARD-EMBOSSED-NAME TO CCUP-OLD-CRDNAME} at {@code app/cbl/COCRDUPC.cbl:L1513}
 * refreshes the operator's field. The assertions on a refused name below therefore expect upper case,
 * whatever letter case the other writer stored, and the stored column keeps that writer's case.
 *
 * <h2>How this class runs</h2>
 *
 * <p>One PostgreSQL 18.4 container serves the whole class, on the image tag
 * {@code card-platform/docker-compose.yml} also names.
 * {@code src/main/resources/application.yml} sets {@code spring.flyway.create-schemas: true}, so
 * Flyway creates schema {@code card_service}, applies {@code V1__schema.sql} and loads the fifty
 * card rows of {@code V2__seed.sql}. Three datasource properties reach the context through
 * {@link DynamicPropertySource}: {@code card-platform/services/card-service/pom.xml} declares no
 * {@code spring-boot-testcontainers}, so {@code ServiceConnection} is absent from the test
 * classpath.
 *
 * <p>No method below carries {@code Transactional}. A test-managed transaction would enclose the
 * one {@code applyUpdate} opens, and a change staged from a separate transaction could then not be
 * staged at all. Each test owns one seeded card number instead, so a row one test changes is a row
 * no other test reads.
 *
 * <p>Column {@code embossed_name} is {@code CHAR(50)}, so a value read back over the Java Database
 * Connectivity (JDBC) driver arrives padded with spaces to that width. Every comparison of a name
 * below trims first.
 *
 * <p>Run this class from {@code card-platform/} with
 * {@code mvn -o -B -pl services/card-service test}. The class name ends in {@code Test}, so
 * Surefire runs it and supplies the two card-token system properties the parent build declares.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "carddemo.outbox.relay.fixed-delay-ms=3600000"
        })
@DisplayName("the card update concurrency comparison, over the migrated card schema")
class CardChangeDetectionTest {

    /**
     * The schema Flyway creates, from {@code spring.flyway.schemas} and
     * {@code spring.jpa.properties.hibernate.default_schema} in
     * {@code src/main/resources/application.yml}.
     */
    private static final String MIGRATED_SCHEMA = "card_service";

    /** The table {@code V1__schema.sql} creates for the card record. */
    private static final String CARD_TABLE = "card";

    /**
     * Characters the embossed name holds, from {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}. Column {@code embossed_name} is {@code CHAR(50)}.
     */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link CardServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    static final PostgreSQLContainer POSTGRES = CardServiceDatabase.container();

    /**
     * Points the Spring datasource at the running container.
     *
     * <p>Three properties leave here and no more. {@code src/main/resources/application.yml} sits
     * on the test classpath and carries every other datasource, Flyway and persistence setting.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CardChangeDetectionTest::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container connection string with {@code currentSchema} appended.
     *
     * <p>The facility builds the locator, so no separator is decided here.
     *
     * @return the connection string whose search path holds {@value #MIGRATED_SCHEMA}
     */
    private static String migratedSchemaUrl() {
        return CardServiceDatabase.urlFor(CardChangeDetectionTest.class);
    }

    /** The service under test, injected through its transactional proxy. */
    @Autowired
    private CardUpdateService cardUpdateService;

    /** Stages a change from a separate transaction, and reads committed values back. */
    @Autowired
    private JdbcTemplate jdbc;

    /** Holds the counters of this service, read to assert what a refusal did and did not count. */
    @Autowired
    private MeterRegistry meters;

    /** Opens the transaction the telemetry group commits or discards on purpose. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * The real repository, wrapped so a method call can be verified and, in two methods, replaced.
     *
     * <p>{@link MockitoSpyBean} delegates to the real bean unless a method is stubbed, so every
     * query below runs against the container. Spring resets the recorded calls after each method.
     */
    @MockitoSpyBean
    private CardRepository cards;

    /**
     * Builds the snapshot a caller holds, folded and padded the way the private
     * {@code snapshotOf} of {@link CardUpdateService} folds and pads.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1356-L1358} folds the fetched name to upper case, ahead of
     * the move at {@code app/cbl/COCRDUPC.cbl:L1360} that stores it.
     * {@code app/cbl/COCRDUPC.cbl:L1361-L1366} slices the expiry into a four-character year, a
     * two-character month and a two-character day.
     *
     * @param embossedName the embossed cardholder name as the row held it
     * @param expiration   the expiry date as the row held it
     * @param activeStatus the active status flag as the row held it
     * @return the five values, folded and padded
     */
    private static RefreshedCard snapshotOf(String embossedName, LocalDate expiration,
            String activeStatus) {
        return new RefreshedCard(
                paddedName(embossedName).toUpperCase(Locale.ROOT),
                String.format(Locale.ROOT, "%04d", expiration.getYear()),
                String.format(Locale.ROOT, "%02d", expiration.getMonthValue()),
                String.format(Locale.ROOT, "%02d", expiration.getDayOfMonth()),
                activeStatus);
    }

    /**
     * Pads a name on the right with spaces to the width its Picture clause declares.
     *
     * @param embossedName the name to pad
     * @return the name at exactly {@value #EMBOSSED_NAME_WIDTH} characters
     */
    private static String paddedName(String embossedName) {
        int missing = EMBOSSED_NAME_WIDTH - embossedName.length();
        return missing <= 0 ? embossedName : embossedName + " ".repeat(missing);
    }

    /**
     * Builds one submitted update for the named card.
     *
     * @param cardNumber   the sixteen-digit card number the update names
     * @param embossedName the submitted embossed cardholder name
     * @param expiration   the submitted expiry date, all three slices of which are submitted
     * @param activeStatus the submitted active status flag
     * @return the request
     */
    private static CardUpdateRequest requestFor(String cardNumber, String embossedName,
            LocalDate expiration, String activeStatus) {
        return new CardUpdateRequest(embossedName,
                       String.format(Locale.ROOT, "%04d", expiration.getYear()),
                       String.format(Locale.ROOT, "%02d", expiration.getMonthValue()),
                       String.format(Locale.ROOT, "%02d", expiration.getDayOfMonth()), activeStatus);
    }

    /**
     * Changes one column of one card row from a separate transaction that commits at once.
     *
     * <p>{@link JdbcTemplate} takes a connection of its own here, because no method in this class
     * runs inside a transaction. The change is therefore visible to the locking read the service
     * performs next, which is the concurrent writer
     * {@code app/cbl/COCRDUPC.cbl:L1451} names in its comment.
     *
     * @param column     the column to change, one of the seven {@code V1__schema.sql} declares
     * @param value      the value to store
     * @param cardNumber the sixteen-digit card number of the row to change
     */
    private void anotherWriterChanges(String column, Object value, String cardNumber) {
        int rows = jdbc.update("UPDATE " + CARD_TABLE + " SET " + column + " = ?"
                + " WHERE card_number = ?", value, cardNumber);
        assertEquals(1, rows, "the seeded row " + column + " was to be changed on was not found");
    }

    /**
     * Removes one card row from a separate transaction that commits at once.
     *
     * @param cardNumber the sixteen-digit card number of the row to remove
     */
    private void anotherWriterRemoves(String cardNumber) {
        int rows = jdbc.update("DELETE FROM " + CARD_TABLE + " WHERE card_number = ?", cardNumber);
        assertEquals(1, rows, "the seeded row to remove was not found");
    }

    /**
     * Reads one committed column value back as text.
     *
     * @param column     the column to read
     * @param cardNumber the sixteen-digit card number of the row to read
     * @return the stored value, trimmed of the padding a fixed-width column adds
     */
    private String storedText(String column, String cardNumber) {
        String value = jdbc.queryForObject("SELECT " + column + " FROM " + CARD_TABLE
                + " WHERE card_number = ?", String.class, cardNumber);
        return value == null ? null : value.strip();
    }

    /**
     * Reads the committed expiry date back.
     *
     * @param cardNumber the sixteen-digit card number of the row to read
     * @return the stored date from column {@code expiration_date}
     */
    private LocalDate storedExpiration(String cardNumber) {
        return jdbc.queryForObject("SELECT expiration_date FROM " + CARD_TABLE
                + " WHERE card_number = ?", LocalDate.class, cardNumber);
    }

    /**
     * The six values {@code 9300-CHECK-CHANGE-IN-REC.} compares, one method for each.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1503-L1508} joins six comparisons with {@code AND}, in this
     * order:</p>
     *
     * <ol>
     * <li>the card verification value, on {@code app/cbl/COCRDUPC.cbl:L1503};</li>
     * <li>the embossed name, on {@code app/cbl/COCRDUPC.cbl:L1504};</li>
     * <li>the expiry year slice {@code (1:4)}, on {@code app/cbl/COCRDUPC.cbl:L1505};</li>
     * <li>the month slice {@code (6:2)}, on {@code app/cbl/COCRDUPC.cbl:L1506};</li>
     * <li>the day slice {@code (9:2)}, on {@code app/cbl/COCRDUPC.cbl:L1507};</li>
     * <li>the active status, on {@code app/cbl/COCRDUPC.cbl:L1508}.</li>
     * </ol>
     *
     * <p>A match continues at {@code app/cbl/COCRDUPC.cbl:L1509}. A mismatch takes the
     * {@code ELSE} at {@code app/cbl/COCRDUPC.cbl:L1510}, which flags the change on
     * {@code app/cbl/COCRDUPC.cbl:L1511} and leaves the write paragraph from
     * {@code app/cbl/COCRDUPC.cbl:L1518}.
     *
     * <p>Each method changes exactly one of those values and asserts the outcome, so the field a
     * method names is the field its assertion depends on. The three expiry methods change the
     * year, the month and the day independently, because the source slices the ten-character value
     * into three and compares the slices rather than the whole.
     */
    @Nested
    @DisplayName("the six values the change check compares")
    class TheComparedFields {

        /**
         * A change of letters in the embossed name is refused.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1504} compares the name. The change below replaces
         * letters and not their case: the fold at {@code app/cbl/COCRDUPC.cbl:L1499-L1501}
         * absorbs a change of case alone.
         *
         * <p>The fold serves that comparison and reaches no answer. The name the refusal carries is
         * the name column {@code embossed_name} holds, in the letter case the other writer stored,
         * because {@code card-platform/services/card-service/src/main/resources/openapi.yaml}
         * publishes the refreshed name as the value a caller would resubmit.
         */
        @Test
        @DisplayName("a changed embossed name is refused with the L208 text")
        void aChangedEmbossedNameIsRefused() {
            String cardNumber = "0500024453765740";
            LocalDate seededExpiration = LocalDate.of(2023, 3, 9);
            RefreshedCard staleSnapshot = snapshotOf("Aniya Von", seededExpiration, "Y");

            anotherWriterChanges("embossed_name", "Marlene Kuhn", cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Aniya Vaughn", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);

            assertAll(
                    () -> assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, response.outcome()),
                    () -> assertEquals(CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE,
                            response.message()),
                    () -> assertEquals("MARLENE KUHN",
                            response.refreshedCard().embossedName().strip(),
                            "the refreshed name is folded: the INSPECT at app/cbl/COCRDUPC.cbl:L1499-L1501 converts the record field in place, so the MOVE at :L1513 refreshes from the folded value"));
        }

        /**
         * A change of the expiry year slice is refused.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1505} compares {@code CARD-EXPIRAION-DATE(1:4)}. The
         * change below moves the year and leaves the month and the day alone.
         */
        @Test
        @DisplayName("a changed expiry year is refused with the L208 text")
        void aChangedExpiryYearIsRefused() {
            String cardNumber = "0683586198171516";
            LocalDate seededExpiration = LocalDate.of(2025, 7, 13);
            RefreshedCard staleSnapshot = snapshotOf("Ward Jones", seededExpiration, "Y");

            anotherWriterChanges("expiration_date", LocalDate.of(2026, 7, 13), cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Ward Jones", LocalDate.of(2027, 7, 13), "Y"),
                    staleSnapshot, LocalDate.of(2027, 7, 13));

            assertAll(
                    () -> assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, response.outcome()),
                    () -> assertEquals(CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE,
                            response.message()),
                    () -> assertEquals("2026", response.refreshedCard().expiryYear()),
                    () -> assertEquals("07", response.refreshedCard().expiryMonth()),
                    () -> assertEquals("13", response.refreshedCard().expiryDay()));
        }

        /**
         * A change of the expiry month slice is refused.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1506} compares {@code CARD-EXPIRAION-DATE(6:2)}. The
         * change below moves the month and leaves the year and the day alone.
         */
        @Test
        @DisplayName("a changed expiry month is refused with the L208 text")
        void aChangedExpiryMonthIsRefused() {
            String cardNumber = "0923877193247330";
            LocalDate seededExpiration = LocalDate.of(2024, 8, 11);
            RefreshedCard staleSnapshot = snapshotOf("Enrico Rosenbaum", seededExpiration, "Y");

            anotherWriterChanges("expiration_date", LocalDate.of(2024, 9, 11), cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Enrico Rosenbaum", LocalDate.of(2024, 10, 11), "Y"),
                    staleSnapshot, LocalDate.of(2024, 10, 11));

            assertAll(
                    () -> assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, response.outcome()),
                    () -> assertEquals(CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE,
                            response.message()),
                    () -> assertEquals("2024", response.refreshedCard().expiryYear()),
                    () -> assertEquals("09", response.refreshedCard().expiryMonth()),
                    () -> assertEquals("11", response.refreshedCard().expiryDay()));
        }

        /**
         * A change of the expiry day slice is refused.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1507} compares {@code CARD-EXPIRAION-DATE(9:2)}. The day
         * takes part in the comparison and no paragraph range-checks it: the edit chain runs 1230,
         * 1240, 1250 and 1260, and {@code 2000-DECIDE-ACTION.} follows at
         * {@code app/cbl/COCRDUPC.cbl:L948}.
         */
        @Test
        @DisplayName("a changed expiry day is refused with the L208 text")
        void aChangedExpiryDayIsRefused() {
            String cardNumber = "0927987108636232";
            LocalDate seededExpiration = LocalDate.of(2024, 3, 13);
            RefreshedCard staleSnapshot = snapshotOf("Carter Veum", seededExpiration, "Y");

            anotherWriterChanges("expiration_date", LocalDate.of(2024, 3, 14), cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Carter Vaughn", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);

            assertAll(
                    () -> assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, response.outcome()),
                    () -> assertEquals(CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE,
                            response.message()),
                    () -> assertEquals("2024", response.refreshedCard().expiryYear()),
                    () -> assertEquals("03", response.refreshedCard().expiryMonth()),
                    () -> assertEquals("14", response.refreshedCard().expiryDay()));
        }

        /**
         * A change of the active status is refused.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1508} compares {@code CARD-ACTIVE-STATUS}, the last of
         * the six.
         */
        @Test
        @DisplayName("a changed active status is refused with the L208 text")
        void aChangedActiveStatusIsRefused() {
            String cardNumber = "0982496213629795";
            LocalDate seededExpiration = LocalDate.of(2023, 7, 7);
            RefreshedCard staleSnapshot = snapshotOf("Maci Robel", seededExpiration, "Y");

            anotherWriterChanges("active_status", "N", cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Maci Robelle", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);

            assertAll(
                    () -> assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, response.outcome()),
                    () -> assertEquals(CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE,
                            response.message()),
                    () -> assertEquals("N", response.refreshedCard().activeStatus()));
        }

        /**
         * A change of the card verification value is not refused.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1503} opens the source chain on {@code CARD-CVV-CD}, and
         * {@link RefreshedCard} carries five values rather than six.
         * {@link CardEntity} publishes no accessor for the card verification value and
         * {@link CardEntity#applyUpdate} never writes it, so the comparison has no such value to
         * read. This test asserts the update is applied and the row keeps the value the other
         * writer stored.
         */
        @Test
        @DisplayName("a changed card verification value is not refused, and the row keeps it")
        void aChangedCardVerificationValueIsNotRefused() {
            String cardNumber = "1014086565224350";
            LocalDate seededExpiration = LocalDate.of(2024, 1, 17);
            RefreshedCard staleSnapshot = snapshotOf("Irving Emard", seededExpiration, "Y");

            anotherWriterChanges("card_verification_value", "641", cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Irving Emmard", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, response.outcome()),
                    () -> assertFalse(response.hasMessage()),
                    () -> assertFalse(response.hasRefreshedCard()),
                    () -> assertEquals("641", storedText("card_verification_value", cardNumber)),
                    () -> assertEquals("Irving Emmard", storedText("embossed_name", cardNumber)));
        }

        /**
         * A change of the account identifier is not refused.
         *
         * <p>The comparison at {@code app/cbl/COCRDUPC.cbl:L1503-L1508} names no account
         * identifier, and {@code app/cbl/COCRDUPC.cbl:L1424} reads the account move into the record
         * identifier commented out. This test asserts the update is applied, which is what a
         * whole-record comparison would not do.
         */
        @Test
        @DisplayName("a changed account identifier is not refused, so the check is field level")
        void aChangedAccountIdentifierIsNotRefused() {
            String cardNumber = "1142167692878931";
            LocalDate seededExpiration = LocalDate.of(2023, 10, 24);
            RefreshedCard staleSnapshot = snapshotOf("Shany Walker", seededExpiration, "Y");

            anotherWriterChanges("account_id", "00000000099", cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Shany Walcker", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, response.outcome()),
                    () -> assertFalse(response.hasRefreshedCard()),
                    () -> assertEquals("00000000099", storedText("account_id", cardNumber)),
                    () -> assertEquals("Shany Walcker", storedText("embossed_name", cardNumber)));
        }

        /**
         * A refused update writes nothing.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1518} leaves for the write-paragraph exit before the
         * record is prepared at {@code app/cbl/COCRDUPC.cbl:L1461-L1475} and rewritten at
         * {@code app/cbl/COCRDUPC.cbl:L1477-L1483}. This test asserts the row still carries the
         * values the other writer stored and none of the submitted values.
         */
        @Test
        @DisplayName("a refused update leaves the other writer's values in the row")
        void aRefusedUpdateLeavesTheOtherWritersValues() {
            String cardNumber = "1561409106491600";
            LocalDate seededExpiration = LocalDate.of(2025, 9, 23);
            RefreshedCard staleSnapshot = snapshotOf("Angelica Dach", seededExpiration, "Y");

            anotherWriterChanges("embossed_name", "Nadia Fenwick", cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Priscilla Reece", LocalDate.of(2026, 11, 23), "N"),
                    staleSnapshot, LocalDate.of(2026, 11, 23));

            assertAll(
                    () -> assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, response.outcome()),
                    () -> assertEquals("Nadia Fenwick", storedText("embossed_name", cardNumber)),
                    () -> assertEquals(seededExpiration, storedExpiration(cardNumber)),
                    () -> assertEquals("Y", storedText("active_status", cardNumber)));
        }
    }

    /**
     * The snapshot a refusal returns, and the count of values it carries.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1512-L1517} refreshes six saved values once the comparison
     * fails, and {@link RefreshedCard} declares five components. The two methods below assert the
     * two counts separately. The value left out takes no part in the response.
     */
    @Nested
    @DisplayName("the refreshed snapshot a refusal returns")
    class TheRefreshedSnapshot {

        /**
         * The snapshot declares five components and exposes no card verification value.
         *
         * <p>The refresh at {@code app/cbl/COCRDUPC.cbl:L1513-L1517} stores the embossed name, the
         * three expiry slices and the active status. {@code app/cbl/COCRDUPC.cbl:L1512} stores the
         * card verification value as well, and no component below carries it.
         */
        @Test
        @DisplayName("the snapshot declares five components, none of them a verification value")
        void theSnapshotDeclaresFiveComponents() {
            RecordComponent[] declared = RefreshedCard.class.getRecordComponents();
            List<String> names = Arrays.stream(declared).map(RecordComponent::getName).toList();

            assertAll(
                    () -> assertEquals(5, declared.length,
                            "the snapshot carries five of the six values"
                                    + " app/cbl/COCRDUPC.cbl:L1512-L1517 refreshes"),
                    () -> assertEquals(List.of("embossedName", "expiryYear", "expiryMonth",
                            "expiryDay", "activeStatus"), names),
                    () -> assertFalse(
                            names.stream().anyMatch(name -> name.toLowerCase(Locale.ROOT)
                                    .contains("verification")),
                            "no component exposes the card verification value"),
                    () -> assertFalse(
                            names.stream().anyMatch(name -> name.toLowerCase(Locale.ROOT)
                                    .contains("cvv")),
                            "no component exposes the card verification value"));
        }

        /**
         * A refusal returns every one of the five values as the row now holds them.
         *
         * <p>The other writer below changes the name, the expiry date and the active status
         * together, so all five components of the returned snapshot carry a refreshed value. Each
         * arrives as the column holds it, the name included. The fold at
         * {@code app/cbl/COCRDUPC.cbl:L1499-L1501} runs ahead of the comparison at
         * {@code app/cbl/COCRDUPC.cbl:L1504} and serves it alone; a caller reading this answer to
         * resubmit needs the stored value rather than a raised copy of it, which is the value
         * {@code card-platform/services/card-service/src/main/resources/openapi.yaml} publishes.
         */
        @Test
        @DisplayName("a refusal returns the five values the row now holds")
        void aRefusalReturnsTheValuesTheRowNowHolds() {
            String cardNumber = "2745303720002090";
            LocalDate seededExpiration = LocalDate.of(2025, 9, 8);
            RefreshedCard staleSnapshot = snapshotOf("Aliyah Berge", seededExpiration, "Y");

            anotherWriterChanges("embossed_name", "Odette Kilback", cardNumber);
            anotherWriterChanges("expiration_date", LocalDate.of(2027, 4, 2), cardNumber);
            anotherWriterChanges("active_status", "N", cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Aliyah Bergeron", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);
            RefreshedCard refreshed = response.refreshedCard();

            assertAll(
                    () -> assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, response.outcome()),
                    () -> assertTrue(response.hasRefreshedCard()),
                    () -> assertNotNull(refreshed),
                    () -> assertEquals("ODETTE KILBACK", refreshed.embossedName().strip(),
                            "the refreshed name is folded: the INSPECT at app/cbl/COCRDUPC.cbl:L1499-L1501 converts the record field in place, so the MOVE at :L1513 refreshes from the folded value"),
                    () -> assertEquals("2027", refreshed.expiryYear()),
                    () -> assertEquals("04", refreshed.expiryMonth()),
                    () -> assertEquals("02", refreshed.expiryDay()),
                    () -> assertEquals("N", refreshed.activeStatus()));
        }
    }

    /**
     * The upper-case fold, which stands on both sides of the name comparison.
     *
     * <p>The program carries exactly three {@code INSPECT} statements, at
     * {@code app/cbl/COCRDUPC.cbl:L824}, {@code app/cbl/COCRDUPC.cbl:L1356} and
     * {@code app/cbl/COCRDUPC.cbl:L1499}. The first strips the alphabet in the name edit. The
     * second folds the fetched name before the move at {@code app/cbl/COCRDUPC.cbl:L1360} stores
     * it. The third folds the re-read name before the comparison at
     * {@code app/cbl/COCRDUPC.cbl:L1504}.
     *
     * <p>Both sides of that comparison are therefore folded, so a change of letter case alone is
     * not a change. The two methods below assert no refusal in either direction.
     */
    @Nested
    @DisplayName("the upper-case fold on both sides of the name comparison")
    class TheUpperCaseFold {

        /**
         * A row another writer folded up is not refused.
         *
         * <p>The seeded name is mixed case and the snapshot holds it folded, from
         * {@code app/cbl/COCRDUPC.cbl:L1356-L1358}. The other writer stores the same letters in
         * upper case, so the comparison at {@code app/cbl/COCRDUPC.cbl:L1504} finds no difference.
         */
        @Test
        @DisplayName("a name another writer folded to upper case is no change")
        void aNameFoldedToUpperCaseIsNoChange() {
            String cardNumber = "2760836797107565";
            LocalDate seededExpiration = LocalDate.of(2025, 2, 11);
            RefreshedCard staleSnapshot = snapshotOf("Stefanie Dickinson", seededExpiration, "Y");

            anotherWriterChanges("embossed_name", "STEFANIE DICKINSON", cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Stefanie Dickenson", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, response.outcome()),
                    () -> assertFalse(response.hasRefreshedCard()),
                    () -> assertEquals("Stefanie Dickenson",
                            storedText("embossed_name", cardNumber)));
        }

        /**
         * A row another writer folded down is not refused.
         *
         * <p>The other writer stores the same letters in lower case, and the fold at
         * {@code app/cbl/COCRDUPC.cbl:L1499-L1501} raises them again before the comparison at
         * {@code app/cbl/COCRDUPC.cbl:L1504}. Without that fold the comparison would report a
         * change here.
         */
        @Test
        @DisplayName("a name another writer folded to lower case is no change")
        void aNameFoldedToLowerCaseIsNoChange() {
            String cardNumber = "2871968252812490";
            LocalDate seededExpiration = LocalDate.of(2025, 10, 8);
            RefreshedCard staleSnapshot = snapshotOf("Ignacio Douglas", seededExpiration, "Y");

            anotherWriterChanges("embossed_name", "ignacio douglas", cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Ignacio Doughlas", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, response.outcome()),
                    () -> assertFalse(response.hasRefreshedCard()),
                    () -> assertEquals("Ignacio Doughlas",
                            storedText("embossed_name", cardNumber)));
        }

        /**
         * The column keeps the submitted letter case and the answer reports that same case.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1466} moves the unfolded {@code CCUP-NEW-CRDNAME} into
         * {@code CARD-UPDATE-EMBOSSED-NAME}. That field is declared at
         * {@code app/cbl/COCRDUPC.cbl:L318}, inside the record {@code app/cbl/COCRDUPC.cbl:L314}
         * opens, so the rewritten row carries the letter case the caller submitted.
         *
         * <p>The fold at {@code app/cbl/COCRDUPC.cbl:L1499-L1501} mutates the record area in place
         * ahead of the comparison at {@code app/cbl/COCRDUPC.cbl:L1504}, so the move at
         * {@code app/cbl/COCRDUPC.cbl:L1513} carried a raised copy onto the screen. The two methods
         * of {@code TheUpperCaseFold} assert what that fold is for, which is that a difference of
         * letter case alone is no change. It reaches no answer of this service:
         * {@code card-platform/services/card-service/src/main/resources/openapi.yaml} publishes the
         * refreshed name as the value a caller would resubmit, and a raised copy is a value the
         * caller never stored.
         *
         * <p>This test submits a lower-case name, reads the column back, then stages a change and
         * asserts the returned snapshot carries the very characters it just stored.
         */
        @Test
        @DisplayName("the column keeps the submitted case and the answer reports that same case")
        void theColumnKeepsTheSubmittedCaseAndSoDoesTheAnswer() {
            String cardNumber = "2940139362300449";
            LocalDate seededExpiration = LocalDate.of(2025, 12, 28);
            RefreshedCard seededSnapshot = snapshotOf("Allene Brown", seededExpiration, "Y");

            CardUpdateResponse applied = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "allene brown", seededExpiration, "Y"),
                    seededSnapshot, seededExpiration);
            String storedAfterUpdate = storedText("embossed_name", cardNumber);

            RefreshedCard snapshotOfLowerCaseRow =
                    snapshotOf("allene brown", seededExpiration, "Y");
            anotherWriterChanges("active_status", "N", cardNumber);
            CardUpdateResponse refused = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "allene brown", seededExpiration, "Y"),
                    snapshotOfLowerCaseRow, seededExpiration);

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, applied.outcome()),
                    () -> assertEquals("allene brown", storedAfterUpdate),
                    () -> assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, refused.outcome()),
                    () -> assertEquals("ALLENE BROWN",
                            refused.refreshedCard().embossedName().strip(),
                            "the column keeps the submitted case and the refreshed answer folds "
                                    + "it, which is what the source does: the INSPECT at "
                                    + "app/cbl/COCRDUPC.cbl:L1499-L1501 converts the record field "
                                    + "in place before the MOVE at :L1513 reads it"));
        }
    }

    /**
     * The absence of a version column, asserted over the migration and over the mapping.
     *
     * <p>The mechanism that detects a concurrent change is the field comparison at
     * {@code app/cbl/COCRDUPC.cbl:L1503-L1508}, followed by the exit at
     * {@code app/cbl/COCRDUPC.cbl:L1455-L1457}. No column and no mapped field carries a row
     * version.
     */
    @Nested
    @DisplayName("the absence of a version column")
    class TheAbsenceOfAVersionColumn {

        /**
         * The migrated table declares the record columns, the derived token, and no version.
         *
         * <p>{@code app/cpy/CVACT02Y.cpy:L5-L10} declares six fields, and
         * {@code V1__schema.sql} adds {@code card_token} for the platform identity of a card. The
         * catalogue query below reads the column names of schema {@value #MIGRATED_SCHEMA}.
         *
         * <p>{@code V10__card_token_version_and_rotation.sql} adds two more, and neither is a row
         * version. {@code card_token_version} names the card-token key a stored token was taken
         * under and {@code card_token_provenance} says whether this deployment derived it, which is
         * what separates correcting a seeded literal from moving an identity three other stores
         * hold. Both describe the token rather than the row, so a concurrent change to an embossed
         * name still moves neither, and the comparison at
         * {@code app/cbl/COCRDUPC.cbl:L1503-L1508} remains the whole of the check.
         */
        @Test
        @DisplayName("the card table declares nine columns and none named version")
        void theCardTableDeclaresNoVersionColumn() {
            List<String> columns = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns"
                            + " WHERE table_schema = ? AND table_name = ?"
                            + " ORDER BY column_name",
                    String.class, MIGRATED_SCHEMA, CARD_TABLE);

            assertAll(
                    () -> assertEquals(List.of("account_id", "active_status", "card_number",
                            "card_token", "card_token_provenance", "card_token_version",
                            "card_verification_value", "embossed_name",
                            "expiration_date"), columns),
                    () -> assertFalse(columns.contains("version"),
                            "the change check is the field comparison at"
                                    + " app/cbl/COCRDUPC.cbl:L1503-L1508"),
                    () -> assertFalse(columns.contains("row_version")),
                    () -> assertFalse(columns.contains("opt_lock")));
        }

        /**
         * No mapped field of the card entity carries the version annotation.
         *
         * <p>This test reads every declared field of {@link CardEntity} and reports the ones
         * carrying {@link Version}. The check that detects a concurrent change is the field
         * comparison at {@code app/cbl/COCRDUPC.cbl:L1503-L1508}.
         */
        @Test
        @DisplayName("no mapped field of the card entity carries the version annotation")
        void theCardEntityDeclaresNoVersionField() {
            List<String> versioned = Arrays.stream(CardEntity.class.getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(Version.class))
                    .map(Field::getName)
                    .toList();

            assertEquals(List.of(), versioned,
                    "the change check is the field comparison at"
                            + " app/cbl/COCRDUPC.cbl:L1503-L1508");
        }
    }

    /**
     * The order of the lock, the change comparison and the rewrite.
     *
     * <p>{@code 9200-WRITE-PROCESSING.} tests the lock at {@code app/cbl/COCRDUPC.cbl:L1441}. A
     * failure sets {@code INPUT-ERROR} at {@code app/cbl/COCRDUPC.cbl:L1444}, passes the guard at
     * {@code app/cbl/COCRDUPC.cbl:L1445}, sets {@code COULD-NOT-LOCK-FOR-UPDATE} at
     * {@code app/cbl/COCRDUPC.cbl:L1446} and leaves at {@code app/cbl/COCRDUPC.cbl:L1448}. The
     * change comparison runs afterwards, at {@code app/cbl/COCRDUPC.cbl:L1453-L1454}, and its own
     * exit sits at {@code app/cbl/COCRDUPC.cbl:L1455-L1457}.
     */
    @Nested
    @DisplayName("the order of the lock, the change comparison and the rewrite")
    class TheOrderOfTheWriteSteps {

        /**
         * A lock the service could not take is reported before the change comparison runs.
         *
         * <p>The row below is changed by another writer first, so the comparison would refuse the
         * update if it ran. The locking read is then made to miss, which is the outcome
         * {@code app/cbl/COCRDUPC.cbl:L1441} reads as anything other than
         * {@code DFHRESP(NORMAL)}. The answer carries the lock text and no snapshot, so the exit
         * at {@code app/cbl/COCRDUPC.cbl:L1448} was taken ahead of
         * {@code app/cbl/COCRDUPC.cbl:L1453}.
         */
        @Test
        @DisplayName("a lock not taken answers with the L206 text and never compares or saves")
        void aLockNotTakenIsReportedBeforeTheChangeComparison() {
            String cardNumber = "2988091353094312";
            LocalDate seededExpiration = LocalDate.of(2023, 12, 16);
            RefreshedCard staleSnapshot = snapshotOf("Delbert Parisian", seededExpiration, "Y");

            anotherWriterChanges("embossed_name", "Verona Lubowitz", cardNumber);
            Mockito.doReturn(Optional.empty())
                    .when(cards).findForUpdateByCardNumber(cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Delbert Parrish", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);

            verify(cards).findForUpdateByCardNumber(cardNumber);
            verify(cards, never()).save(any(CardEntity.class));
            assertAll(
                    () -> assertEquals(UpdateOutcome.LOCK_NOT_ACQUIRED, response.outcome()),
                    () -> assertEquals(CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE,
                            response.message()),
                    () -> assertFalse(response.hasRefreshedCard(),
                            "the refresh at app/cbl/COCRDUPC.cbl:L1512-L1517 was not reached"),
                    () -> assertEquals("Verona Lubowitz",
                            storedText("embossed_name", cardNumber)));
        }

        /**
         * A lock the database will not grant answers the lock text, not a fault.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1441} reports on a {@code READ UPDATE} that came back
         * with anything other than a normal response, and it draws no distinction between a row
         * that has gone and a row the dataset would not hand over. Both are a lock not taken.
         *
         * <p>Two families say that here, and both are caught. A lock the database will not grant
         * inside the wait it was given arrives as {@link CannotAcquireLockException}, and a
         * deadlock it breaks as a sibling of it, both {@link PessimisticLockingFailureException}.
         * A statement whose own timeout expires arrives as {@link QueryTimeoutException}.
         * {@code repository/CardRepositoryIT} contends two real transactions and asserts the first
         * of those types, so this mapping is measured rather than assumed.
         *
         * <p>Without the catch at the locking read each left
         * {@link CardUpdateService#applyUpdate} unhandled, and a caller read the fault body of
         * {@code api/CardApiExceptionHandler} in place of the outcome
         * {@code src/main/resources/openapi.yaml} documents for exactly this condition.
         */
        @Test
        @DisplayName("a lock PostgreSQL refuses answers the L206 text through both families")
        void aLockTheDatabaseRefusesAnswersTheDocumentedOutcome() {
            String cardNumber = "4385271476627819";
            LocalDate seededExpiration = LocalDate.of(2025, 10, 6);
            CardUpdateRequest submitted =
                    requestFor(cardNumber, "Faustino Schmidty", seededExpiration, "Y");

            Mockito.doThrow(new CannotAcquireLockException("canceling statement due to lock timeout"))
                    .when(cards).findForUpdateByCardNumber(cardNumber);
            CardUpdateResponse afterLockTimeout =
                    cardUpdateService.updateCard(cardNumber, submitted);

            Mockito.doThrow(
                            new QueryTimeoutException("canceling statement due to statement "
                                    + "timeout"))
                    .when(cards).findForUpdateByCardNumber(cardNumber);
            CardUpdateResponse afterStatementTimeout =
                    cardUpdateService.updateCard(cardNumber, submitted);

            verify(cards, never()).save(any(CardEntity.class));
            assertAll(
                    () -> assertEquals(UpdateOutcome.LOCK_NOT_ACQUIRED,
                            afterLockTimeout.outcome(),
                            "a lock the wait ran out on is a lock not taken"),
                    () -> assertEquals(CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE,
                            afterLockTimeout.message(), "the text L209 sets"),
                    () -> assertFalse(afterLockTimeout.hasRefreshedCard()),
                    () -> assertEquals(UpdateOutcome.LOCK_NOT_ACQUIRED,
                            afterStatementTimeout.outcome(),
                            "a statement whose own timeout expired is a lock not taken"),
                    () -> assertEquals(CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE,
                            afterStatementTimeout.message(), "the text L209 sets"),
                    () -> assertFalse(afterStatementTimeout.hasRefreshedCard()),
                    () -> assertEquals("Faustino Schmidt",
                            storedText("embossed_name", cardNumber),
                            "neither attempt wrote anything"));
        }

        /**
         * A fault that is neither a lock nor a timeout is not answered as a conflict.
         *
         * <p>This is the distinction the outcome above used to lose. Every
         * {@link JpaSystemException} was grouped with the lock families, and that type is where the
         * persistence layer puts every Hibernate error it has no specific translation for: a
         * revoked privilege, a driver fault, a mapping error. A caller reading
         * {@code 409 Conflict} for one of those is told another writer holds the row and to try
         * again, when the truth is that no attempt will ever succeed, and the conflict meter counts
         * a broken database as a user conflict.
         *
         * <p>Such a fault now leaves {@link CardUpdateService#updateCard} unhandled and
         * {@code api/CardApiExceptionHandler} answers {@code 500}. The assertion is on both halves:
         * the fault escapes, and the conflict meter does not move.
         */
        @Test
        @DisplayName("a permission fault is not reported as a conflict and moves no conflict count")
        void aFaultThatIsNeitherALockNorATimeoutIsNotAConflict() {
            String cardNumber = "4385271476627819";
            LocalDate seededExpiration = LocalDate.of(2025, 10, 6);
            CardUpdateRequest submitted =
                    requestFor(cardNumber, "Faustino Schmidty", seededExpiration, "Y");
            JpaSystemException permissionDenied = new JpaSystemException(
                    new RuntimeException("ERROR: permission denied for table card"));
            double conflictsBefore = updateConflictCount();

            Mockito.doThrow(permissionDenied)
                    .when(cards).findForUpdateByCardNumber(cardNumber);

            JpaSystemException escaped = assertThrows(JpaSystemException.class,
                    () -> cardUpdateService.updateCard(cardNumber, submitted),
                    "a fault that is neither a lock nor a timeout stays a fault, so the caller "
                            + "reads 500 rather than being told to retry a request that cannot "
                            + "succeed");

            verify(cards, never()).save(any(CardEntity.class));
            assertAll(
                    () -> assertSame(permissionDenied, escaped,
                            "the fault reaches the handler unchanged"),
                    () -> assertEquals(conflictsBefore, updateConflictCount(),
                            "a broken dependency is not counted as a user conflict"),
                    () -> assertEquals("Faustino Schmidt",
                            storedText("embossed_name", cardNumber),
                            "the attempt wrote nothing"));
        }

        /**
         * A row another writer removed cannot be locked, and no stub takes part.
         *
         * <p>The row is gone, so the locking read misses. That is the same condition
         * {@code app/cbl/COCRDUPC.cbl:L1441} tests, and this test asserts the lock text reaches
         * the caller from a real miss.
         */
        @Test
        @DisplayName("a row another writer removed answers with the L206 text")
        void aRowAnotherWriterRemovedCannotBeLocked() {
            String cardNumber = "3999169246375885";
            LocalDate seededExpiration = LocalDate.of(2024, 1, 10);
            RefreshedCard staleSnapshot = snapshotOf("Larry Homenick", seededExpiration, "Y");

            anotherWriterRemoves(cardNumber);

            CardUpdateResponse response = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Larry Homenicke", seededExpiration, "Y"),
                    staleSnapshot, seededExpiration);

            verify(cards, never()).save(any(CardEntity.class));
            assertAll(
                    () -> assertEquals(UpdateOutcome.LOCK_NOT_ACQUIRED, response.outcome()),
                    () -> assertEquals(CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE,
                            response.message()),
                    () -> assertFalse(response.hasRefreshedCard()));
        }

        /**
         * A rewrite that failed after the lock reports its own text over a text already set.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1488} tests the rewrite and the plain {@code ELSE} at
         * {@code app/cbl/COCRDUPC.cbl:L1490} sets {@code LOCKED-BUT-UPDATE-FAILED} at
         * {@code app/cbl/COCRDUPC.cbl:L1491}. That assignment is the one text-setting site of the
         * paragraph with no {@code IF WS-RETURN-MSG-OFF} guard in front of it, so it replaces a
         * text already held.
         *
         * <p>The failure below carries the concurrent-change text in its own message. The answer
         * carries {@code 'Update of record failed'} instead, and the row rolls back to the value it
         * held.
         */
        @Test
        @DisplayName("a failed rewrite answers with the L210 text over the text the cause held")
        void aFailedRewriteReportsItsOwnTextOverATextAlreadySet() {
            String cardNumber = "3260763612337560";
            Mockito.doThrow(new DataIntegrityViolationException(
                            CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE))
                    .when(cards).save(any(CardEntity.class));

            CardUpdateResponse response = cardUpdateService.updateCard(cardNumber, requestFor(cardNumber, "Maybell Manning", LocalDate.of(2024, 1, 27), "Y"));

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATE_FAILED_AFTER_LOCK, response.outcome()),
                    () -> assertEquals(CardValidationMessages.LOCKED_BUT_UPDATE_FAILED,
                            response.message()),
                    () -> assertFalse(CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE
                            .equals(response.message())),
                    () -> assertFalse(response.hasRefreshedCard()),
                    () -> assertEquals("Maybell Mann", storedText("embossed_name", cardNumber)));
        }

        /**
         * A refusal the database raises only when the statements run answers the same text.
         *
         * <p>The method above stubs the repository, so its failure arrives while the guarded block
         * is still open. A real database refusal does not: {@code save} on a row already under
         * management records a state change and the outbox write records an insert, and both reach
         * PostgreSQL when the persistence context is flushed. Left to the transaction boundary that
         * flush ran after the guarded block had been left, so the refusal reached no {@code catch}
         * here, {@code api/CardApiExceptionHandler} answered with its fault body, and
         * {@code UPDATE_FAILED_AFTER_LOCK} was unreachable through every real cause it names.
         * {@link CardUpdateService#applyUpdate} now flushes as the last statement inside that block.
         *
         * <p>The check constraint below stands for any refusal the database raises on the rewrite:
         * a constraint, a trigger, or a privilege the service no longer holds.
         * {@code app/cbl/COCRDUPC.cbl:L1488} tests the rewrite and
         * {@code app/cbl/COCRDUPC.cbl:L1491} sets {@code LOCKED-BUT-UPDATE-FAILED}, so a rewrite
         * PostgreSQL refuses takes the same answer as one the repository refuses.
         *
         * <p>The transaction rolls back either way. This asserts what the caller is told, and that
         * the row still holds {@code Mariane Fadel}.
         */
        @Test
        @DisplayName("a rewrite PostgreSQL refuses at flush answers the L210 text, not a fault")
        void aRewriteTheDatabaseRefusesAtFlushAnswersTheDocumentedOutcome() {
            String cardNumber = "4011500891777367";
            jdbc.execute("ALTER TABLE " + CARD_TABLE + " ADD CONSTRAINT card_name_not_refused"
                    + " CHECK (embossed_name NOT LIKE 'Refused%')");
            try {
                CardUpdateResponse response = cardUpdateService.updateCard(cardNumber, requestFor(cardNumber, "Refused Writer", LocalDate.of(2024, 8, 4), "Y"));

                assertAll(
                        () -> assertEquals(UpdateOutcome.UPDATE_FAILED_AFTER_LOCK,
                                response.outcome(),
                                "the refusal reached the catch inside the guarded block"),
                        () -> assertEquals(CardValidationMessages.LOCKED_BUT_UPDATE_FAILED,
                                response.message(), "the text L1491 sets"),
                        () -> assertFalse(response.hasRefreshedCard()),
                        () -> assertEquals("Mariane Fadel",
                                storedText("embossed_name", cardNumber),
                                "the rolled-back row holds the value V2__seed.sql loaded"));
            } finally {
                jdbc.execute("ALTER TABLE " + CARD_TABLE
                        + " DROP CONSTRAINT IF EXISTS card_name_not_refused");
            }
        }
    }

    /**
     * The checks the source does not perform, and which this service does not add.
     *
     * <p>The batch posting program selects six files, at {@code app/cbl/CBTRN02C.cbl:L29},
     * {@code :L34}, {@code :L40}, {@code :L46}, {@code :L51} and {@code :L57}. No card file is
     * among them, so no posting decision reads the active status this service stores.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L194} carries the one card-number rule of the update program
     * and names sixteen digits.
     */
    @Nested
    @DisplayName("the checks the update path does not add")
    class TheDeliberateNonAdditions {

        /**
         * An inactive card updates, and a change to it is still detected.
         *
         * <p>{@code CARD-ACTIVE-STATUS} appears at four places in the update program:
         * {@code app/cbl/COCRDUPC.cbl:L674}, {@code :L1367}, {@code :L1508} and {@code :L1517}.
         * Each one moves the value or compares it, and none gates the rewrite at
         * {@code app/cbl/COCRDUPC.cbl:L1477-L1483}.
         *
         * <p>This test asserts an update to a card holding {@code N} is applied, and that a change
         * another writer makes to the same row is then refused.
         */
        @Test
        @DisplayName("a card holding N updates, and a change to it is still refused")
        void anInactiveCardUpdatesAndStillDetectsAChange() {
            String cardNumber = "3766281984155154";
            LocalDate seededExpiration = LocalDate.of(2023, 4, 24);
            anotherWriterChanges("active_status", "N", cardNumber);
            RefreshedCard snapshotOfInactiveRow =
                    snapshotOf("Lucinda Dach", seededExpiration, "N");

            CardUpdateResponse applied = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Lucinda Dachs", seededExpiration, "N"),
                    snapshotOfInactiveRow, seededExpiration);

            RefreshedCard snapshotAfterTheUpdate =
                    snapshotOf("Lucinda Dachs", seededExpiration, "N");
            anotherWriterChanges("embossed_name", "Roselyn Boyer", cardNumber);
            CardUpdateResponse refused = cardUpdateService.applyUpdate(cardNumber, requestFor(cardNumber, "Lucinda Dachson", seededExpiration, "N"),
                    snapshotAfterTheUpdate, seededExpiration);

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, applied.outcome()),
                    () -> assertEquals(UpdateOutcome.CHANGED_BEFORE_UPDATE, refused.outcome()),
                    () -> assertEquals(CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE,
                            refused.message()),
                    () -> assertEquals("ROSELYN BOYER",
                            refused.refreshedCard().embossedName().strip(),
                            "the refreshed name is folded: the INSPECT at app/cbl/COCRDUPC.cbl:L1499-L1501 converts the record field in place, so the MOVE at :L1513 refreshes from the folded value"),
                    () -> assertEquals("N", refused.refreshedCard().activeStatus()));
        }
    }

    @Nested
    @DisplayName("when the success count and the success line are taken")
    class SuccessTelemetry {

        /**
         * The applied count follows the commit rather than predicting it.
         *
         * <p>{@code applyUpdate} carries {@code @Transactional}, so the commit happens after it
         * returns. Taking the count and the log line on the last lines of the method would take them
         * before that commit: a deferred constraint, a lost connection or a rollback-only marker
         * would discard the update while the counter had already moved and the log already said the
         * update committed. {@code entityManager.flush()} does not close that gap, because it sends
         * the statements and leaves the commit where it was.
         *
         * <p>This test opens the transaction itself and marks it rollback-only, so the inner call
         * joins a transaction that is going to be discarded. The count must not move.
         */
        @Test
        @DisplayName("a rolled-back update moves no applied count")
        void aRolledBackUpdateMovesNoAppliedCount() {
            String cardNumber = "4534784102713951";
            LocalDate seededExpiration = storedExpiration(cardNumber);
            String seededName = storedText("embossed_name", cardNumber).strip();
            String seededStatus = storedText("active_status", cardNumber).strip();
            RefreshedCard fetched = snapshotOf(seededName, seededExpiration, seededStatus);
            double appliedBefore = updateAppliedCount();

            CardUpdateResponse response = new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        CardUpdateResponse inner = cardUpdateService.applyUpdate(
                                cardNumber,
                                requestFor(cardNumber, seededName + "x", seededExpiration,
                                        seededStatus),
                                fetched, seededExpiration);
                        status.setRollbackOnly();
                        return inner;
                    });

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, response.outcome(),
                            "the update itself succeeded, so the only thing under test is when "
                                    + "the count is taken"),
                    () -> assertEquals(appliedBefore, updateAppliedCount(),
                            "the applied count moved for an update the transaction discarded"),
                    () -> assertEquals(seededName, storedText("embossed_name", cardNumber).strip(),
                            "the rollback left the row as it was"));
        }

        /**
         * A committed update does move the applied count.
         *
         * <p>The assertion above would also pass if the count had simply stopped being taken at
         * all, so this one is what keeps it honest.
         */
        @Test
        @DisplayName("a committed update moves the applied count by one")
        void aCommittedUpdateMovesTheAppliedCount() {
            String cardNumber = "5407099850479866";
            LocalDate seededExpiration = storedExpiration(cardNumber);
            String seededName = storedText("embossed_name", cardNumber).strip();
            String seededStatus = storedText("active_status", cardNumber).strip();
            RefreshedCard fetched = snapshotOf(seededName, seededExpiration, seededStatus);
            double appliedBefore = updateAppliedCount();

            CardUpdateResponse response = new TransactionTemplate(transactionManager)
                    .execute(status -> cardUpdateService.applyUpdate(
                            cardNumber,
                            requestFor(cardNumber, seededName + "y", seededExpiration,
                                    seededStatus),
                            fetched, seededExpiration));

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, response.outcome()),
                    () -> assertEquals(appliedBefore + 1.0d, updateAppliedCount(),
                            "one committed update counts once"));
        }
    }

    /**
     * Reads the running total of {@code carddemo.card.update.conflicts}.
     *
     * <p>Read rather than reset, because the registry is shared across the class and a reset would
     * make one test depend on the order the others ran in. Each assertion compares a before and an
     * after.
     *
     * @return the count so far, or zero when nothing has been counted yet
     */
    private double updateConflictCount() {
        Counter counter = meters.find(ObservabilityConfig.METRIC_CARD_UPDATE_CONFLICTS).counter();
        return counter == null ? 0.0d : counter.count();
    }

    /**
     * Reads the running total of {@code carddemo.card.update.applied}.
     *
     * @return the count so far, or zero when nothing has been counted yet
     */
    private double updateAppliedCount() {
        Counter counter = meters.find(ObservabilityConfig.METRIC_CARD_UPDATE_APPLIED).counter();
        return counter == null ? 0.0d : counter.count();
    }

}
