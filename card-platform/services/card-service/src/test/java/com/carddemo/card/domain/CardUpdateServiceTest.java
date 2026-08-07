package com.carddemo.card.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.carddemo.card.CardApplication;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardUpdateResponse.UpdateOutcome;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.outbox.OutboxWriter;
import com.carddemo.cobol.PanMasker;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Field edits, edit order, expiry handling and the write path of {@link CardUpdateService}.
 *
 * <p>The behaviour comes from the card update program {@code app/cbl/COCRDUPC.cbl}, which runs as a
 * Customer Information Control System (CICS) transaction over a Virtual Storage Access Method
 * (VSAM) dataset. Every locator below was read in that member.
 *
 * <p>What this class asserts: the three ranges the edits admit and the order those edits run in.
 * Then the embossed-name character class, the expiry date in both directions, and the row the write
 * leaves behind. Then the card-not-found answer, the handoff to the outbox, and the checks the
 * source does not perform.
 *
 * <p>Five subjects belong to {@code CardChangeDetectionTest} instead. The six-field comparison at
 * {@code app/cbl/COCRDUPC.cbl:L1503-L1508} and the refreshed snapshot at
 * {@code app/cbl/COCRDUPC.cbl:L1512-L1517}. The fold of the stored name at
 * {@code app/cbl/COCRDUPC.cbl:L1499-L1501}, the absent version column, and the lock the read for
 * update could not take at {@code app/cbl/COCRDUPC.cbl:L1441}.
 *
 * <p>Every expected value is written out, not computed, and each was measured in
 * {@code app/data/ASCII/carddata.txt}: fifty records, every record exactly 150 characters, field
 * offsets from {@code app/cpy/CVACT02Y.cpy:L5-L11}. Flyway loads those records from
 * {@code src/main/resources/db/migration/V2__seed.sql}, and no test here opens a file under
 * {@code app/}.
 *
 * <p>Every test that writes owns one seeded card number, so no test depends on another and none
 * needs a rollback. No method carries {@code Transactional}: {@link CardUpdateService} owns the
 * transaction boundary, {@link OutboxWriter} joins it, and a test-managed transaction would hide
 * the committed row. Committed state is read back through {@link JdbcTemplate}.
 *
 * <p><b>How this class runs.</b> {@link CardApplication} supplies the context and one PostgreSQL
 * 18.4 container serves the whole class, on the image tag
 * {@code card-platform/docker-compose.yml} also names. Flyway creates schema
 * {@value #MIGRATED_SCHEMA}, applies {@code V1__schema.sql} and loads the fifty rows of
 * {@code V2__seed.sql}. {@code spring.jpa.hibernate.ddl-auto} is {@code validate}, so every test
 * here carries the mapping check by starting.
 *
 * <p>{@link DynamicPropertySource} points three datasource properties at the container. No
 * {@code ServiceConnection} annotation appears here:
 * {@code card-platform/services/card-service/pom.xml} declares no
 * {@code spring-boot-testcontainers} artifact.
 *
 * <p>Four class properties supply one inert value for each variable
 * {@code src/main/resources/application.yml} leaves without a default. Two more push the outbox
 * relay and the retention sweep one hour out. The one sweep at start-up therefore meets an empty
 * table, and no later sweep reaches a row asserted below.
 *
 * <p>{@link MockitoSpyBean} wraps {@link OutboxWriter} so that one test reads the card handed to
 * it. The spy delegates every call to the real bean, so each stored row below is a real row.
 *
 * <p>Run this class from {@code card-platform/} with
 * {@code mvn -o -B -pl services/card-service -am test}.
 *
 * <p>Design decisions, including the year range at {@code app/cbl/COCRDUPC.cbl:L99} and the
 * {@code DATE} column: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        classes = CardApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
                "USER_PASSWORD_HASH={noop}not-a-real-user-password",
                "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password",
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@Testcontainers
@DisplayName("CardUpdateService over the fifty seeded cards: the edits, the expiry and the write")
class CardUpdateServiceTest {

    /** The image tag {@code card-platform/docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** The database name, the login name and the password of the container, one value for all. */
    private static final String POSTGRES_CREDENTIAL = "carddemo";

    /**
     * The schema Flyway creates, from {@code spring.flyway.schemas} and
     * {@code spring.jpa.properties.hibernate.default_schema} in
     * {@code src/main/resources/application.yml}.
     */
    private static final String MIGRATED_SCHEMA = "card_service";

    /**
     * The one container every test in this class shares.
     *
     * <p>The class name comes from {@code org.testcontainers.postgresql}, the package
     * Testcontainers 2.0.5 ships it in. {@link Container} on a static field gives one container per
     * class, and {@link Testcontainers} starts it before the Spring context reads a property below.
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(POSTGRES_CREDENTIAL)
            .withUsername(POSTGRES_CREDENTIAL)
            .withPassword(POSTGRES_CREDENTIAL);

    /**
     * Points the Spring datasource at the running container.
     *
     * <p>Three properties leave here, each as a supplier the context resolves at refresh.
     * {@code src/main/resources/application.yml} sits on the test classpath and carries every other
     * datasource, Flyway and persistence setting. No line below repeats one, and no line creates
     * the schema: {@code spring.flyway.create-schemas} does that.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CardUpdateServiceTest::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container uniform resource locator with {@code currentSchema} appended.
     *
     * <p>Testcontainers appends one query parameter of its own, so the separator is {@code &}
     * whenever a {@code ?} is present and {@code ?} otherwise.
     *
     * @return the connection uniform resource locator whose search path holds
     *         {@value #MIGRATED_SCHEMA}
     */
    private static String migratedSchemaUrl() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema=" + MIGRATED_SCHEMA;
    }

    // Seeded rows. Each value below was measured in app/data/ASCII/carddata.txt at the offsets
    // app/cpy/CVACT02Y.cpy:L5-L10 declares, and V2__seed.sql loads all fifty records.

    /** Card number of seeded record one, read by the tests that assert a refusal. */
    private static final String WITNESS_CARD = "0500024453765740";

    /** Account identifier of {@link #WITNESS_CARD}, eleven characters. */
    private static final String WITNESS_ACCOUNT = "00000000050";

    /** Embossed name of {@link #WITNESS_CARD}, before the padding {@code CHAR(50)} carries. */
    private static final String WITNESS_NAME = "Aniya Von";

    /** Expiry date of {@link #WITNESS_CARD}, the value column {@code expiration_date} holds. */
    private static final LocalDate WITNESS_EXPIRY = LocalDate.of(2023, 3, 9);

    /** Four-character year slice of {@link #WITNESS_EXPIRY}. */
    private static final String WITNESS_YEAR = "2023";

    /** Two-character month slice of {@link #WITNESS_EXPIRY}. */
    private static final String WITNESS_MONTH = "03";

    /** Two-character day slice of {@link #WITNESS_EXPIRY}. */
    private static final String WITNESS_DAY = "09";

    /** Card number of seeded record two, written by the success-path test. */
    private static final String SUCCESS_PATH_CARD = "0683586198171516";

    /** Card number of seeded record three, written by the outbox-handoff test. */
    private static final String OUTBOX_HANDOFF_CARD = "0923877193247330";

    /** Card number of seeded record four, written by the stored-payload test. */
    private static final String OUTBOX_PAYLOAD_CARD = "0927987108636232";

    /** Card verification value of {@link #OUTBOX_PAYLOAD_CARD}, three characters. */
    private static final String OUTBOX_PAYLOAD_CARD_VERIFICATION_VALUE = "003";

    /** Account identifier of {@link #OUTBOX_PAYLOAD_CARD}, eleven characters. */
    private static final String OUTBOX_PAYLOAD_ACCOUNT = "00000000020";

    /** Card number of seeded record five, written by the expiry-day test. Stored day {@code 07}. */
    private static final String EXPIRY_DAY_CARD = "0982496213629795";

    /** Card number of seeded record six, written by the reassembly test. Expiry 2024-01-17. */
    private static final String REASSEMBLY_CARD = "1014086565224350";

    /**
     * Card number of seeded record nineteen, written by the day-only test. Expiry 2025-07-23, name
     * {@code Hadley Hamill}, status {@code Y}. No other test reads or writes this row.
     */
    private static final String DAY_ONLY_CARD = "3940246016141489";

    /** Expiry date of {@link #REASSEMBLY_CARD}. */
    private static final LocalDate REASSEMBLY_EXPIRY = LocalDate.of(2024, 1, 17);

    /** Card number of seeded record seven, written by the affirmative-flag test. */
    private static final String STATUS_YES_CARD = "1142167692878931";

    /** Card number of seeded record eight, written by the negative-flag test. */
    private static final String STATUS_NO_CARD = "1561409106491600";

    /** Card number of seeded record nine, the lowest-month test. Stored day {@code 08}. */
    private static final String MONTH_LOWER_CARD = "2745303720002090";

    /** Card number of seeded record ten, the highest-month test. Stored day {@code 11}. */
    private static final String MONTH_UPPER_CARD = "2760836797107565";

    /** Card number of seeded record eleven, the earliest-year test. Stored day {@code 08}. */
    private static final String YEAR_LOWER_CARD = "2871968252812490";

    /** Card number of seeded record twelve, the latest-year test. Stored day {@code 28}. */
    private static final String YEAR_UPPER_CARD = "2940139362300449";

    /** Card number of seeded record thirteen, written by the one-space-name test. */
    private static final String NAME_ONE_SPACE_CARD = "2988091353094312";

    /** Card number of seeded record fourteen, written by the two-space-name test. */
    private static final String NAME_TWO_SPACE_CARD = "3260763612337560";

    /**
     * Card number of seeded record 47, written twice by the active-status test. Stored name
     * {@code Sigrid Mann}, stored expiry 2025-03-01.
     */
    private static final String NEGATIVE_STATUS_CARD = "9349107475869214";

    /** Embossed name of {@link #NEGATIVE_STATUS_CARD} as the fixture holds it. */
    private static final String NEGATIVE_STATUS_CARD_NAME = "Sigrid Mann";

    /**
     * A sixteen-digit card number no seeded record holds, and one that fails a card-number
     * checksum.
     *
     * <p>Every one of the fifty seeded card numbers satisfies that checksum, so a stored row cannot
     * carry a failing one.
     */
    private static final String UNSEEDED_CARD = "9999999999999999";

    /**
     * The affirmative flag, from {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at
     * {@code app/cbl/COCRDUPC.cbl:L91}.
     */
    private static final String STATUS_YES = "Y";

    /** The negative flag, from the same condition name at {@code app/cbl/COCRDUPC.cbl:L91}. */
    private static final String STATUS_NO = "N";

    /**
     * A name of letters and one space, which the edit at {@code app/cbl/COCRDUPC.cbl:L824} admits.
     */
    private static final String RENAMED_CARDHOLDER = "Renamed Cardholder";

    /** Reads a stored payload back into properties. One instance serves the whole class. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The service under test, built by the context over the migrated schema. */
    @Autowired
    private CardUpdateService cardUpdateService;

    /** Reads committed rows straight from the migrated schema, outside any test transaction. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The outbox writer the update path hands the card to, wrapped so a test can read that card.
     *
     * <p>The spy delegates to the real bean, so a captured call has also stored its row.
     */
    @MockitoSpyBean
    private OutboxWriter outboxWriter;

    /**
     * Builds one request over the six components {@link CardUpdateRequest} declares.
     *
     * @param cardNumber   the sixteen-digit card number the update names
     * @param embossedName the cardholder name
     * @param expiryYear   the four-character year slice
     * @param expiryMonth  the two-character month slice
     * @param expiryDay    the two-character day slice
     * @param activeStatus the one-character active status
     * @return the request
     */
    private static CardUpdateRequest request(String cardNumber, String embossedName,
            String expiryYear, String expiryMonth, String expiryDay, String activeStatus) {
        return new CardUpdateRequest(cardNumber, embossedName, expiryYear, expiryMonth, expiryDay,
                activeStatus);
    }

    /**
     * Builds a request against {@link #WITNESS_CARD} that changes the name and nothing else.
     *
     * <p>The name differs from the stored one, so the comparison at
     * {@code app/cbl/COCRDUPC.cbl:L680-L681} reports a change and the four field edits run.
     *
     * @param activeStatus the active status to submit
     * @return the request
     */
    private static CardUpdateRequest witnessRequestWithStatus(String activeStatus) {
        return request(WITNESS_CARD, RENAMED_CARDHOLDER, WITNESS_YEAR, WITNESS_MONTH, WITNESS_DAY,
                activeStatus);
    }

    /**
     * Builds a request against {@link #WITNESS_CARD} carrying one submitted expiry slice.
     *
     * @param expiryYear  the four-character year slice
     * @param expiryMonth the two-character month slice
     * @return the request
     */
    private static CardUpdateRequest witnessRequestWithExpiry(String expiryYear,
            String expiryMonth) {
        return request(WITNESS_CARD, RENAMED_CARDHOLDER, expiryYear, expiryMonth, WITNESS_DAY,
                STATUS_YES);
    }

    /**
     * Builds a request against {@link #WITNESS_CARD} carrying one submitted embossed name.
     *
     * @param embossedName the name to submit
     * @return the request
     */
    private static CardUpdateRequest witnessRequestWithName(String embossedName) {
        return request(WITNESS_CARD, embossedName, WITNESS_YEAR, WITNESS_MONTH, WITNESS_DAY,
                STATUS_YES);
    }

    /**
     * Reads one column of one card row as text, with the padding of a fixed-width column removed.
     *
     * @param cardNumber the sixteen-digit key of the row
     * @param column     the column to read
     * @return the stored value, trimmed
     */
    private String storedText(String cardNumber, String column) {
        String value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM card WHERE card_number = ?", String.class, cardNumber);
        return value == null ? null : value.strip();
    }

    /**
     * Reads {@code expiration_date} of one card row as a date.
     *
     * @param cardNumber the sixteen-digit key of the row
     * @return the stored expiry date
     */
    private LocalDate storedExpiry(String cardNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT expiration_date FROM card WHERE card_number = ?", LocalDate.class,
                cardNumber);
    }

    /**
     * Reads the seven columns of one card row in the order {@code app/cpy/CVACT02Y.cpy} declares
     * the six it describes, with the derived token last.
     *
     * @param cardNumber the sixteen-digit key of the row
     * @return the seven stored values, each trimmed
     */
    private List<String> storedRow(String cardNumber) {
        return jdbcTemplate.queryForObject("""
                SELECT card_number, account_id, card_verification_value, embossed_name,
                       to_char(expiration_date, 'YYYY-MM-DD'), active_status, card_token
                  FROM card
                 WHERE card_number = ?
                """,
                (row, number) -> List.of(row.getString(1).strip(), row.getString(2).strip(),
                        row.getString(3).strip(), row.getString(4).strip(),
                        row.getString(5).strip(), row.getString(6).strip(),
                        row.getString(7).strip()),
                cardNumber);
    }

    /**
     * Reads the stored payload text of the one event row an account holds.
     *
     * @param accountId the eleven-character account identifier the row carries
     * @return the payload as stored
     */
    private String storedPayloadText(String accountId) {
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE aggregate_id = ?", String.class, accountId);
        assertNotNull(payload, "the update stored one event row for account " + accountId);
        return payload;
    }

    /** The three ranges the field edits admit, each declared as a condition name. */
    @Nested
    @DisplayName("the three ranges the edits admit")
    class TheThreeRanges {

        /**
         * Asserts both active-status flags reach the row.
         *
         * <p>{@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at
         * {@code app/cbl/COCRDUPC.cbl:L91} declares the two literals, and
         * {@code app/cbl/COCRDUPC.cbl:L863} tests that condition name after the move at
         * {@code app/cbl/COCRDUPC.cbl:L861}.
         */
        @Test
        @DisplayName("Y and N both reach the stored row")
        void theTwoActiveStatusFlagsAreAdmitted() {
            CardUpdateResponse keptAffirmative = cardUpdateService.updateCard(
                    request(STATUS_YES_CARD, RENAMED_CARDHOLDER, "2023", "10", "24", STATUS_YES));
            CardUpdateResponse turnedNegative = cardUpdateService.updateCard(
                    request(STATUS_NO_CARD, RENAMED_CARDHOLDER, "2025", "09", "23", STATUS_NO));

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, keptAffirmative.outcome(),
                            "Y passes the edit at app/cbl/COCRDUPC.cbl:L863"),
                    () -> assertEquals(STATUS_YES, storedText(STATUS_YES_CARD, "active_status"),
                            "the stored flag stays Y"),
                    () -> assertEquals(UpdateOutcome.UPDATED, turnedNegative.outcome(),
                            "N passes the same edit"),
                    () -> assertEquals(STATUS_NO, storedText(STATUS_NO_CARD, "active_status"),
                            "the stored flag is now N"));
        }

        /**
         * Asserts a lower-case flag, another letter and a missing flag all answer one text.
         *
         * <p>The condition name at {@code app/cbl/COCRDUPC.cbl:L91} tests two upper-case literals
         * and no edit of the program folds the case of the field. The missing-value branch sets the
         * text at {@code app/cbl/COCRDUPC.cbl:L856} and the range branch sets the same text at
         * {@code app/cbl/COCRDUPC.cbl:L869}.
         */
        @Test
        @DisplayName("a lower-case y, the letter A and a missing flag all answer the status text")
        void anyOtherActiveStatusIsRefused() {
            CardUpdateResponse lowerCase =
                    cardUpdateService.updateCard(witnessRequestWithStatus("y"));
            CardUpdateResponse otherLetter =
                    cardUpdateService.updateCard(witnessRequestWithStatus("A"));
            CardUpdateResponse missing = cardUpdateService.updateCard(witnessRequestWithStatus(""));

            assertAll(
                    () -> assertEquals(CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                            lowerCase.message(), "the edit folds no case"),
                    () -> assertEquals(CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                            otherLetter.message(), "the condition name admits Y and N alone"),
                    () -> assertEquals(CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                            missing.message(), "the missing-value branch sets the same text"),
                    () -> assertEquals(WITNESS_NAME, storedText(WITNESS_CARD, "embossed_name"),
                            "a refused update writes nothing"));
        }

        /**
         * Asserts the lowest and the highest month reach the row.
         *
         * <p>{@code 88 VALID-MONTH VALUES 1 THRU 12.} at {@code app/cbl/COCRDUPC.cbl:L95} declares
         * the range on the numeric redefine at {@code app/cbl/COCRDUPC.cbl:L93-L94}, and
         * {@code app/cbl/COCRDUPC.cbl:L898} tests it.
         *
         * <p>The stored day joins the submitted month, so the dates below carry the seeded days
         * {@code 08} and {@code 11}.
         */
        @Test
        @DisplayName("month 01 and month 12 both reach the stored row")
        void theTwoMonthBoundsAreAdmitted() {
            CardUpdateResponse lowest = cardUpdateService.updateCard(
                    request(MONTH_LOWER_CARD, RENAMED_CARDHOLDER, "2025", "01", "08", STATUS_YES));
            CardUpdateResponse highest = cardUpdateService.updateCard(
                    request(MONTH_UPPER_CARD, RENAMED_CARDHOLDER, "2025", "12", "11", STATUS_YES));

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, lowest.outcome(),
                            "month 01 passes the edit at app/cbl/COCRDUPC.cbl:L898"),
                    () -> assertEquals(LocalDate.of(2025, 1, 8), storedExpiry(MONTH_LOWER_CARD),
                            "the stored expiry carries month 01"),
                    () -> assertEquals(UpdateOutcome.UPDATED, highest.outcome(),
                            "month 12 passes the same edit"),
                    () -> assertEquals(LocalDate.of(2025, 12, 11), storedExpiry(MONTH_UPPER_CARD),
                            "the stored expiry carries month 12"));
        }

        /**
         * Asserts a month below one and a month above twelve answer the month text.
         *
         * <p>The range at {@code app/cbl/COCRDUPC.cbl:L95} admits neither. A month of
         * {@code 00} equals {@code ZEROS} and the not-supplied branch at
         * {@code app/cbl/COCRDUPC.cbl:L885} catches it, so the text comes from
         * {@code app/cbl/COCRDUPC.cbl:L889} and not from
         * {@code app/cbl/COCRDUPC.cbl:L904}. Both texts hold the same characters.
         */
        @Test
        @DisplayName("month 00 and month 13 both answer the month text")
        void aMonthOutsideOneThroughTwelveIsRefused() {
            CardUpdateResponse belowRange =
                    cardUpdateService.updateCard(witnessRequestWithExpiry(WITNESS_YEAR, "00"));
            CardUpdateResponse aboveRange =
                    cardUpdateService.updateCard(witnessRequestWithExpiry(WITNESS_YEAR, "13"));

            assertAll(
                    () -> assertEquals(CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                            belowRange.message(), "00 sits below the range"),
                    () -> assertEquals(CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                            aboveRange.message(), "13 sits above the range"),
                    () -> assertEquals(WITNESS_EXPIRY, storedExpiry(WITNESS_CARD),
                            "a refused update writes nothing"));
        }

        /**
         * Asserts the earliest and the latest expiry year reach the row.
         *
         * <p>{@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at {@code app/cbl/COCRDUPC.cbl:L99}
         * declares the range on the numeric redefine at {@code app/cbl/COCRDUPC.cbl:L97-L98}, and
         * {@code app/cbl/COCRDUPC.cbl:L934} tests it. That condition name is the only statement of
         * the range in the source.
         */
        @Test
        @DisplayName("year 1950 and year 2099 both reach the stored row")
        void theTwoYearBoundsAreAdmitted() {
            CardUpdateResponse earliest = cardUpdateService.updateCard(
                    request(YEAR_LOWER_CARD, RENAMED_CARDHOLDER, "1950", "10", "08", STATUS_YES));
            CardUpdateResponse latest = cardUpdateService.updateCard(
                    request(YEAR_UPPER_CARD, RENAMED_CARDHOLDER, "2099", "12", "28", STATUS_YES));

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, earliest.outcome(),
                            "1950 passes the edit at app/cbl/COCRDUPC.cbl:L934"),
                    () -> assertEquals(LocalDate.of(1950, 10, 8), storedExpiry(YEAR_LOWER_CARD),
                            "the stored expiry carries 1950"),
                    () -> assertEquals(UpdateOutcome.UPDATED, latest.outcome(),
                            "2099 passes the same edit"),
                    () -> assertEquals(LocalDate.of(2099, 12, 28), storedExpiry(YEAR_UPPER_CARD),
                            "the stored expiry carries 2099"));
        }

        /**
         * Asserts the year one below the range and the year one above it answer the year text.
         *
         * <p>The range at {@code app/cbl/COCRDUPC.cbl:L99} admits 1950 through 2099, so 1949 and
         * 2100 are the two adjacent values it refuses. The text sits at
         * {@code app/cbl/COCRDUPC.cbl:L200} and {@code app/cbl/COCRDUPC.cbl:L940} sets it.
         */
        @Test
        @DisplayName("year 1949 and year 2100 both answer the year text")
        void aYearOutsideNineteenFiftyThroughTwentyNinetyNineIsRefused() {
            CardUpdateResponse belowRange =
                    cardUpdateService.updateCard(witnessRequestWithExpiry("1949", WITNESS_MONTH));
            CardUpdateResponse aboveRange =
                    cardUpdateService.updateCard(witnessRequestWithExpiry("2100", WITNESS_MONTH));

            assertAll(
                    () -> assertEquals(CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
                            belowRange.message(), "1949 sits one year below the range"),
                    () -> assertEquals(CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
                            aboveRange.message(), "2100 sits one year above the range"),
                    () -> assertEquals(WITNESS_EXPIRY, storedExpiry(WITNESS_CARD),
                            "a refused update writes nothing"));
        }

        /**
         * Asserts each of the three edits answers one text for a missing value and for a value
         * outside its range.
         *
         * <p>The status edit sets its text at {@code app/cbl/COCRDUPC.cbl:L856} and at
         * {@code app/cbl/COCRDUPC.cbl:L869}, the month edit at {@code app/cbl/COCRDUPC.cbl:L889}
         * and at {@code app/cbl/COCRDUPC.cbl:L904}, the year edit at
         * {@code app/cbl/COCRDUPC.cbl:L922} and at {@code app/cbl/COCRDUPC.cbl:L940}. Each pair
         * sets one condition name, so the answer names the rule and not the branch.
         */
        @Test
        @DisplayName("a missing value and an out-of-range value share one text per rule")
        void aBlankValueAndAnOutOfRangeValueShareOneTextPerRule() {
            String missingStatus = cardUpdateService.updateCard(witnessRequestWithStatus(""))
                    .message();
            String refusedStatus = cardUpdateService.updateCard(witnessRequestWithStatus("A"))
                    .message();
            String missingMonth = cardUpdateService
                    .updateCard(witnessRequestWithExpiry(WITNESS_YEAR, "")).message();
            String refusedMonth = cardUpdateService
                    .updateCard(witnessRequestWithExpiry(WITNESS_YEAR, "13")).message();
            String missingYear = cardUpdateService
                    .updateCard(witnessRequestWithExpiry("", WITNESS_MONTH)).message();
            String refusedYear = cardUpdateService
                    .updateCard(witnessRequestWithExpiry("2100", WITNESS_MONTH)).message();

            assertAll(
                    () -> assertEquals(missingStatus, refusedStatus,
                            "L856 and L869 set one condition name"),
                    () -> assertEquals(missingMonth, refusedMonth,
                            "L889 and L904 set one condition name"),
                    () -> assertEquals(missingYear, refusedYear,
                            "L922 and L940 set one condition name"));
        }
    }

    /** The order the edits run in, which decides which text a caller reads. */
    @Nested
    @DisplayName("the order of the edits")
    class TheOrderOfTheEdits {

        /**
         * Asserts the status text arrives alone when the status, the month and the year all fail.
         *
         * <p>{@code 1200-EDIT-MAP-INPUTS.} performs the four field edits unconditionally and in one
         * order: the name at {@code app/cbl/COCRDUPC.cbl:L698}, the status at
         * {@code app/cbl/COCRDUPC.cbl:L701}, the month at {@code app/cbl/COCRDUPC.cbl:L704} and the
         * year at {@code app/cbl/COCRDUPC.cbl:L707}. Each text sits behind
         * {@code IF WS-RETURN-MSG-OFF}, so the first edit to fail owns the answer. The status guard
         * at {@code app/cbl/COCRDUPC.cbl:L868} closes the field against the month guard at
         * {@code app/cbl/COCRDUPC.cbl:L903} and the year guard at
         * {@code app/cbl/COCRDUPC.cbl:L939}.
         *
         * <p>Three rules break in one request, and that is what pins the order: one broken rule
         * shows a text and not a sequence. A reordered implementation answers the month text or the
         * year text here. Every other test in this class drives one behaviour.
         */
        @Test
        @DisplayName("a request breaking the status, month and year rules answers the status text")
        void theStatusTextArrivesAloneWhenStatusMonthAndYearAllFail() {
            CardUpdateResponse answer = cardUpdateService.updateCard(
                    request(WITNESS_CARD, RENAMED_CARDHOLDER, "2100", "13", WITNESS_DAY, "A"));

            assertAll(
                    () -> assertEquals(UpdateOutcome.VALIDATION_REJECTED, answer.outcome(),
                            "an edit refused the request"),
                    () -> assertEquals(CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                            answer.message(), "the status edit runs first, at L701"),
                    () -> assertNotEquals(CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                            answer.message(), "the month edit runs second, at L704"),
                    () -> assertNotEquals(CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
                            answer.message(), "the year edit runs third, at L707"));
        }

        /**
         * Asserts a missing card number answers the card-number text and never the both-blank text.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L656-L657} reads
         * {@code IF FLG-ACCTFILTER-BLANK AND FLG-CARDFILTER-BLANK}, sets
         * {@code NO-SEARCH-CRITERIA-RECEIVED} at {@code app/cbl/COCRDUPC.cbl:L658} and leaves the
         * paragraph at {@code app/cbl/COCRDUPC.cbl:L661}. The test is an {@code AND}, so one blank
         * search key takes its own text: the card edit sets that text at
         * {@code app/cbl/COCRDUPC.cbl:L774}. {@link CardUpdateRequest} carries one search key, so
         * the both-blank condition has no second filter to reach and no answer of this service
         * carries its text.
         */
        @Test
        @DisplayName("a missing card number answers the card-number text, not the both-blank text")
        void aMissingCardNumberAnswersItsOwnTextAndNeverTheBothBlankText() {
            CardUpdateResponse answer = cardUpdateService.updateCard(
                    request("", RENAMED_CARDHOLDER, WITNESS_YEAR, WITNESS_MONTH, WITNESS_DAY,
                            STATUS_YES));

            assertAll(
                    () -> assertEquals(UpdateOutcome.VALIDATION_REJECTED, answer.outcome(),
                            "the search-key edit refused the request"),
                    () -> assertEquals(CardValidationMessages.PROMPT_FOR_CARD, answer.message(),
                            "the text L774 sets"),
                    () -> assertNotEquals(CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED,
                            answer.message(), "the condition at L656 needs both filters blank"));
        }

        /**
         * Asserts an unchanged resubmission answers the no-change text and no field text.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L680-L681} compares the submitted card group against the
         * stored one and {@code app/cbl/COCRDUPC.cbl:L682} sets the text. The paragraph then sets
         * all four field-valid flags, the name at {@code app/cbl/COCRDUPC.cbl:L688}, the status at
         * {@code app/cbl/COCRDUPC.cbl:L689}, the month at {@code app/cbl/COCRDUPC.cbl:L690} and the
         * year at {@code app/cbl/COCRDUPC.cbl:L691}, and leaves at
         * {@code app/cbl/COCRDUPC.cbl:L692}. No field edit runs after that exit, so the no-change
         * text cannot arrive beside a status, month or year text.
         *
         * <p>The request below resubmits all five values this card holds and differs from the stored
         * ones in letter case alone, which both sides of the comparison fold away. The status it
         * carries is a lower-case {@code y}, a value {@code 1240-EDIT-CARDSTATUS.} at
         * {@code app/cbl/COCRDUPC.cbl:L845-L873} refuses at
         * {@code app/cbl/COCRDUPC.cbl:L863}, because
         * {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91} admits
         * those two characters alone. The no-change exit reaches that refusal never, so the answer
         * carries the no-change text and not the status text.
         */
        @Test
        @DisplayName("an unchanged resubmission answers the no-change text and skips every edit")
        void anUnchangedResubmissionAnswersTheNoChangeTextAlone() {
            CardUpdateResponse answer = cardUpdateService.updateCard(
                    request(WITNESS_CARD, WITNESS_NAME.toUpperCase(Locale.ROOT), WITNESS_YEAR,
                            WITNESS_MONTH, WITNESS_DAY, STATUS_YES.toLowerCase(Locale.ROOT)));

            assertAll(
                    () -> assertEquals(UpdateOutcome.NO_CHANGE_DETECTED, answer.outcome(),
                            "the comparison at L680 reported no change"),
                    () -> assertEquals(CardValidationMessages.NO_CHANGES_DETECTED,
                            answer.message(), "the text L682 sets"),
                    () -> assertNotEquals(CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                            answer.message(), "the exit at L692 skipped every field edit"),
                    () -> assertEquals(WITNESS_NAME, storedText(WITNESS_CARD, "embossed_name"),
                            "an unchanged resubmission writes nothing"));
        }
    }

    /** The character class the embossed-name edit admits. */
    @Nested
    @DisplayName("the embossed-name rule")
    class TheEmbossedNameRule {

        /**
         * Asserts a name of letters and spaces reaches the row, including one holding two spaces.
         *
         * <p>{@code 1230-EDIT-NAME.} moves the submitted name into
         * {@code CARD-NAME-CHECK PIC X(50)} at {@code app/cbl/COCRDUPC.cbl:L823}, converts every
         * letter to a space at {@code app/cbl/COCRDUPC.cbl:L824-L826} against
         * {@code LIT-ALL-ALPHA-FROM PIC X(52)} at {@code app/cbl/COCRDUPC.cbl:L255-L257}, and then
         * requires the field to hold nothing but spaces at {@code app/cbl/COCRDUPC.cbl:L828}. The
         * literal carries both letter cases, so both are stripped.
         *
         * <p>The equivalent predicate is exactly this: every character is a letter or a space. The
         * length-zero test of the source depends on how the runtime trims a field of spaces, so the
         * predicate stands in for it.
         */
        @Test
        @DisplayName("a name of letters and spaces reaches the stored row, two spaces included")
        void aNameOfLettersAndSpacesIsAdmitted() {
            CardUpdateResponse oneSpace = cardUpdateService.updateCard(
                    request(NAME_ONE_SPACE_CARD, "Aniya Von", "2023", "12", "16", STATUS_YES));
            CardUpdateResponse twoSpaces = cardUpdateService.updateCard(
                    request(NAME_TWO_SPACE_CARD, "Aniya  Von", "2023", "01", "27", STATUS_YES));

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, oneSpace.outcome(),
                            "letters and one space pass the edit at L828"),
                    () -> assertEquals("Aniya Von",
                            storedText(NAME_ONE_SPACE_CARD, "embossed_name"),
                            "the stored name carries the submitted characters"),
                    () -> assertEquals(UpdateOutcome.UPDATED, twoSpaces.outcome(),
                            "a second space is a space like the first"),
                    () -> assertEquals("Aniya  Von",
                            storedText(NAME_TWO_SPACE_CARD, "embossed_name"),
                            "both spaces survive to the row"));
        }

        /**
         * Asserts a hyphen, a digit and an apostrophe each answer the name text.
         *
         * <p>None of the three appears in {@code LIT-ALL-ALPHA-FROM} at
         * {@code app/cbl/COCRDUPC.cbl:L255-L257}, so each survives the conversion at
         * {@code app/cbl/COCRDUPC.cbl:L824} and fails the test at
         * {@code app/cbl/COCRDUPC.cbl:L828}. The text sits at {@code app/cbl/COCRDUPC.cbl:L184} and
         * {@code app/cbl/COCRDUPC.cbl:L834} sets it.
         *
         * <p>The edit governs a submitted name and not a stored one. Record 34 of
         * {@code app/data/ASCII/carddata.txt} holds {@code Lucious O'Connell}, a stored name this
         * edit refuses.
         */
        @Test
        @DisplayName("a hyphen, a digit and an apostrophe each answer the name text")
        void aNameHoldingAnyOtherCharacterIsRefused() {
            CardUpdateResponse hyphen = cardUpdateService.updateCard(witnessRequestWithName(
                    "Aniya-Von"));
            CardUpdateResponse digit = cardUpdateService.updateCard(witnessRequestWithName(
                    "Aniya1"));
            CardUpdateResponse apostrophe = cardUpdateService.updateCard(witnessRequestWithName(
                    "O'Brien"));

            assertAll(
                    () -> assertEquals(CardValidationMessages.NAME_MUST_BE_ALPHA, hyphen.message(),
                            "a hyphen is neither a letter nor a space"),
                    () -> assertEquals(CardValidationMessages.NAME_MUST_BE_ALPHA, digit.message(),
                            "a digit is neither a letter nor a space"),
                    () -> assertEquals(CardValidationMessages.NAME_MUST_BE_ALPHA,
                            apostrophe.message(), "an apostrophe is neither a letter nor a space"),
                    () -> assertEquals(WITNESS_NAME, storedText(WITNESS_CARD, "embossed_name"),
                            "a refused update writes nothing"));
        }
    }

    /** The expiry date, taken apart on the way in and put back together on the way out. */
    @Nested
    @DisplayName("the expiry date in both directions")
    class TheExpiryInBothDirections {

        /**
         * Asserts {@code expiration_date} is a {@code DATE} holding the seeded value.
         *
         * <p>The source field is positionally a calendar date and the program decomposes it.
         * {@code CARD-EXPIRAION-DATE-X PIC X(10)} at {@code app/cbl/COCRDUPC.cbl:L115} is redefined
         * at {@code app/cbl/COCRDUPC.cbl:L116} into a four-character year at
         * {@code app/cbl/COCRDUPC.cbl:L117}, a separator, a two-character month at
         * {@code app/cbl/COCRDUPC.cbl:L119}, a separator and a two-character day at
         * {@code app/cbl/COCRDUPC.cbl:L121}. The four, one, two, one and two characters total ten.
         *
         * <p>The account service holds its own expiry as ten characters of text.
         */
        @Test
        @DisplayName("expiration_date is a DATE column and reads back as 2023-03-09")
        void theStoredExpiryIsADateColumnHoldingTheSeededValue() {
            String columnType = jdbcTemplate.queryForObject("""
                    SELECT data_type
                      FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = 'card'
                       AND column_name = 'expiration_date'
                    """, String.class, MIGRATED_SCHEMA);

            assertAll(
                    () -> assertEquals("date", columnType,
                            "the migration declares the column as a DATE"),
                    () -> assertEquals(WITNESS_EXPIRY, storedExpiry(WITNESS_CARD),
                            "record one of app/data/ASCII/carddata.txt carries 2023-03-09"));
        }

        /**
         * Asserts the three slices reassemble into the date they were taken from.
         *
         * <p>The read path slices the stored date at {@code app/cbl/COCRDUPC.cbl:L1361-L1366}, and
         * the write path joins the three parts with hyphens at
         * {@code app/cbl/COCRDUPC.cbl:L1467-L1474}, {@code DELIMITED BY SIZE}, into
         * {@code CARD-UPDATE-EXPIRAION-DATE PIC X(10)} at {@code app/cbl/COCRDUPC.cbl:L319}. Year,
         * month and day in that order make the ten characters {@code YYYY-MM-DD}.
         *
         * <p>The update below changes the active status and resubmits the year and the month it
         * read, so the stored date after the write is the stored date before it.
         */
        @Test
        @DisplayName("resubmitting the year and month a card holds leaves its stored date alone")
        void theThreeSlicesReassembleIntoTheStoredDate() {
            CardUpdateResponse answer = cardUpdateService.updateCard(
                    request(REASSEMBLY_CARD, "Irving Emard", "2024", "01", "17", STATUS_NO));

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, answer.outcome(),
                            "the status change reached the row"),
                    () -> assertEquals(REASSEMBLY_EXPIRY, storedExpiry(REASSEMBLY_CARD),
                            "the reassembled date equals the date the read decomposed"),
                    () -> assertEquals("2024-01-17",
                            storedRow(REASSEMBLY_CARD).get(4),
                            "the ten characters read YYYY-MM-DD"));
        }

        /**
         * Asserts a submitted triple naming no day of the calendar is refused, row untouched.
         *
         * <p>The source performs no day edit. The edit chain runs 1230, 1240, 1250 and 1260,
         * closing at {@code app/cbl/COCRDUPC.cbl:L945}, and {@code 2000-DECIDE-ACTION.} opens at
         * {@code app/cbl/COCRDUPC.cbl:L948}, so no paragraph between them reaches the day. The
         * program then joins the submitted year, month and day at
         * {@code app/cbl/COCRDUPC.cbl:L1467-L1474} into {@code CARD-UPDATE-EXPIRAION-DATE PIC
         * X(10)} at {@code app/cbl/COCRDUPC.cbl:L319}, which is ten characters of text and holds
         * {@code 2023-02-31} as readily as a real date.
         *
         * <p>Column {@code expiration_date} is a {@code DATE} and cannot, so the triple has no
         * value to store and {@link CardValidationMessages#ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE}
         * carries that outcome as ADDITIVE. The divergence belongs to the column type and
         * {@code card-platform/docs/business-rule-flags.md} carries it as departure D3.
         *
         * <p>The request below submits day {@code 31} with a February expiry. The rule reads the
         * triple the caller sent, so the refusal names the combination the caller actually
         * submitted, and the row keeps the {@code 2023-07-07} the seed loaded.
         */
        @Test
        @DisplayName("day 31 with a February expiry is refused and the stored date is left alone")
        void aSubmittedDayTheCalendarDoesNotHoldIsRefused() {
            CardUpdateResponse answer = cardUpdateService.updateCard(
                    request(EXPIRY_DAY_CARD, "Maci Robel", "2023", "02", "31", STATUS_YES));

            assertAll(
                    () -> assertEquals(UpdateOutcome.VALIDATION_REJECTED, answer.outcome(),
                            "a DATE column holds no thirtieth of February"),
                    () -> assertEquals(
                            CardValidationMessages.ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE,
                            answer.message(), "the ADDITIVE calendar text"),
                    () -> assertEquals(LocalDate.of(2023, 7, 7), storedExpiry(EXPIRY_DAY_CARD),
                            "the refusal wrote nothing, so the seeded date stands"));
        }

        /**
         * Asserts a change to the day alone reaches the column.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L621} moves the submitted day into
         * {@code CCUP-NEW-EXPDAY} and {@code app/cbl/COCRDUPC.cbl:L1471} writes that same item into
         * the record, so the day the program stores is the day it was handed. The comparison at
         * {@code app/cbl/COCRDUPC.cbl:L680-L681} reads the group opening at
         * {@code app/cbl/COCRDUPC.cbl:L307}, which holds two characters of that same day, so a day
         * that differs from the stored one is a change.
         *
         * <p>On a 3270 the day handed back was always the stored day, and that is a property of the
         * map rather than of the program: {@code app/bms/COCRDUP.bms:L142} declares
         * {@code EXPDAY DFHMDF ATTRB=(DRK,FSET,PROT)} where the four editable fields at L107, L117,
         * L127 and L135 declare {@code UNPROT}. A request body has no protected field, so a caller
         * can name a day, and the day it names is the day that lands.
         *
         * <p>The request below resubmits the name, year, month and status this card holds and moves
         * the day from {@code 23} to {@code 24}. Nothing but the day differs, so an outcome of
         * {@code NO_CHANGE_DETECTED} here would leave a day-only change unreachable for ever.
         */
        @Test
        @DisplayName("moving the day from 23 to 24 and nothing else is a change and lands")
        void aChangeToTheDayAloneReachesTheColumn() {
            CardUpdateResponse answer = cardUpdateService.updateCard(
                    request(DAY_ONLY_CARD, "Hadley Hamill", "2025", "07", "24", STATUS_YES));

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, answer.outcome(),
                            "the day is inside the group the comparison reads"),
                    () -> assertNull(answer.message(), "an applied update carries no text"),
                    () -> assertEquals(LocalDate.of(2025, 7, 24), storedExpiry(DAY_ONLY_CARD),
                            "the submitted day reached expiration_date"),
                    () -> assertEquals("2025-07-24", storedRow(DAY_ONLY_CARD).get(4),
                            "the ten characters read YYYY-MM-DD"));
        }
    }

    /** The row the write leaves behind, and the answer a card number no row holds takes. */
    @Nested
    @DisplayName("the write")
    class TheWrite {

        /**
         * Asserts an applied update moves three values and leaves the other four alone.
         *
         * <p>{@code 9200-WRITE-PROCESSING.} opens at {@code app/cbl/COCRDUPC.cbl:L1420} and closes
         * at {@code app/cbl/COCRDUPC.cbl:L1494}. The paragraph moves the card number into the
         * record key at {@code app/cbl/COCRDUPC.cbl:L1425} and takes the row for update at
         * {@code app/cbl/COCRDUPC.cbl:L1427-L1436}.
         *
         * <p>Assembly of the record spans {@code app/cbl/COCRDUPC.cbl:L1461-L1475}. The card
         * identifier lands at {@code app/cbl/COCRDUPC.cbl:L1462}, the account identifier at
         * {@code app/cbl/COCRDUPC.cbl:L1463} and the card verification value at
         * {@code app/cbl/COCRDUPC.cbl:L1464-L1465}. The embossed name lands at
         * {@code app/cbl/COCRDUPC.cbl:L1466}, the reassembled expiry at
         * {@code app/cbl/COCRDUPC.cbl:L1467-L1474} and the active status at
         * {@code app/cbl/COCRDUPC.cbl:L1475}. The rewrite follows at
         * {@code app/cbl/COCRDUPC.cbl:L1477-L1483}, and
         * {@code app/cbl/COCRDUPC.cbl:L1488} tests it.
         *
         * <p>{@link CardEntity#applyUpdate(String, LocalDate, String)} is the one mutator, and it
         * takes the embossed name, the expiry date and the active status. The four values it does
         * not take are the card number, the account identifier, the card verification value and the
         * card token.
         */
        @Test
        @DisplayName("an applied update moves the name, the expiry and the status only")
        void anAppliedUpdateMovesThreeValuesAndLeavesTheOtherFour() {
            List<String> before = storedRow(SUCCESS_PATH_CARD);

            CardUpdateResponse answer = cardUpdateService.updateCard(
                    request(SUCCESS_PATH_CARD, RENAMED_CARDHOLDER, "2030", "06", "13", STATUS_NO));

            List<String> after = storedRow(SUCCESS_PATH_CARD);
            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, answer.outcome(),
                            "the rewrite at L1477 succeeded"),
                    () -> assertNull(answer.message(), "an applied update carries no text"),
                    () -> assertEquals(RENAMED_CARDHOLDER, after.get(3),
                            "the embossed name moved, as L1466 moves it"),
                    () -> assertEquals("2030-06-13", after.get(4),
                            "the expiry moved, as L1467 through L1474 assemble it"),
                    () -> assertEquals(STATUS_NO, after.get(5),
                            "the active status moved, as L1475 moves it"),
                    () -> assertEquals(before.get(0), after.get(0), "the card number stands"),
                    () -> assertEquals(before.get(1), after.get(1),
                            "the account identifier stands"),
                    () -> assertEquals(before.get(2), after.get(2),
                            "the card verification value stands"),
                    () -> assertEquals(before.get(6), after.get(6), "the card token stands"));
        }

        /**
         * Asserts a card number no row holds answers the not-found text and writes nothing.
         *
         * <p>The read runs ahead of the field edits. {@code 9100-GETCARD-BYACCTCARD.} at
         * {@code app/cbl/COCRDUPC.cbl:L1376} sets the text at
         * {@code app/cbl/COCRDUPC.cbl:L1400}, under the guard at
         * {@code app/cbl/COCRDUPC.cbl:L1399}, in the branch a key naming no record takes.
         */
        @Test
        @DisplayName("a card number no row holds answers the not-found text and stores nothing")
        void aCardNumberNoRowHoldsAnswersNotFoundAndWritesNothing() {
            CardUpdateResponse answer = cardUpdateService.updateCard(
                    request(UNSEEDED_CARD, RENAMED_CARDHOLDER, "2030", "06", "13", STATUS_NO));

            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM card WHERE card_number = ?", Integer.class,
                    UNSEEDED_CARD);
            assertAll(
                    () -> assertEquals(UpdateOutcome.CARD_NOT_FOUND, answer.outcome(),
                            "the read found no row"),
                    () -> assertEquals(CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO,
                            answer.message(), "the text L1400 sets"),
                    () -> assertEquals(0, rows, "no row carries that card number"),
                    () -> verify(outboxWriter, never()).writeCardUpdated(any()));
        }
    }

    /** The handoff to the outbox, and where the card number is masked. */
    @Nested
    @DisplayName("the outbox handoff")
    class TheOutboxHandoff {

        /**
         * Asserts the writer receives the card carrying the full sixteen-digit card number.
         *
         * <p>The decision and the read key on all sixteen characters, as
         * {@code app/cbl/COCRDUPC.cbl:L1425} keys the record identifier. Masking belongs to the
         * payload the writer produces and not to the value the writer receives.
         */
        @Test
        @DisplayName("the writer receives the card carrying the full sixteen digits")
        void theWriterReceivesTheCardCarryingTheFullCardNumber() {
            cardUpdateService.updateCard(request(OUTBOX_HANDOFF_CARD, RENAMED_CARDHOLDER, "2024",
                    "08", "11", STATUS_NO));

            ArgumentCaptor<CardEntity> handedOver = ArgumentCaptor.forClass(CardEntity.class);
            verify(outboxWriter).writeCardUpdated(handedOver.capture());

            assertEquals(OUTBOX_HANDOFF_CARD, handedOver.getValue().getCardNumber(),
                    "the writer receives the stored card number unmasked");
        }

        /**
         * Asserts the stored payload carries the masked card number and no cardholder secret.
         *
         * <p>The source masks nothing: {@code app/bms/COCRDSL.bms:L99} gives the card detail field
         * all sixteen characters. Masking is ADDITIVE and
         * {@link PanMasker#maskCardNumber(String)} performs it at the serialization boundary,
         * so the payload carries twelve mask characters and the last four digits.
         *
         * <p>The card verification value reaches no payload property.
         * {@code app/cbl/COCRDUPC.cbl:L1503} compares it and {@link CardEntity} publishes no
         * accessor for it. Property values carry that check, not the payload text: an event
         * identifier or a timestamp can hold the same three digits by chance.
         */
        @Test
        @DisplayName("the stored payload carries the masked number, the event type and the key")
        void theStoredPayloadCarriesTheMaskedCardNumberAndNoCardholderSecret() {
            cardUpdateService.updateCard(request(OUTBOX_PAYLOAD_CARD, RENAMED_CARDHOLDER, "2027",
                    "03", "13", STATUS_NO));

            String payload = storedPayloadText(OUTBOX_PAYLOAD_ACCOUNT);
            var stored = MAPPER.readTree(payload);
            String expectedMask = String.valueOf(PanMasker.MASK_CHARACTER)
                    .repeat(PanMasker.CARD_NUMBER_LENGTH - PanMasker.VISIBLE_DIGIT_COUNT)
                    + OUTBOX_PAYLOAD_CARD.substring(
                            PanMasker.CARD_NUMBER_LENGTH - PanMasker.VISIBLE_DIGIT_COUNT);

            assertAll(
                    () -> assertEquals(expectedMask, stored.get("maskedCardNumber").asString(""),
                            "twelve mask characters then the last four digits"),
                    () -> assertEquals(CardUpdated.EVENT_TYPE, stored.get("eventType").asString(""),
                            "the routing discriminator"),
                    () -> assertEquals(OUTBOX_PAYLOAD_ACCOUNT, stored.get("aggregateId")
                            .asString(""), "the eleven-character account key"),
                    () -> assertEquals("2027-03-13", stored.get("expirationDate").asString(""),
                            "the expiry travels as ten characters, hyphen separated"),
                    () -> assertFalse(payload.contains(OUTBOX_PAYLOAD_CARD),
                            "no property carries the full Primary Account Number"),
                    () -> stored.properties().forEach(property -> assertNotEquals(
                            OUTBOX_PAYLOAD_CARD_VERIFICATION_VALUE,
                            property.getValue().asString(""),
                            "property " + property.getKey()
                                    + " carries the card verification value")));
        }
    }

    /** The checks the source does not perform, and which this platform does not add. */
    @Nested
    @DisplayName("the checks the source does not perform")
    class TheChecksTheSourceDoesNotPerform {

        /**
         * Asserts a card number failing a card-number checksum passes the search-key edit.
         *
         * <p>{@code 1220-EDIT-CARD.} tests {@code IF CC-CARD-NUM IS NOT NUMERIC} at
         * {@code app/cbl/COCRDUPC.cbl:L784} and nothing further. The comments above it, at
         * {@code app/cbl/COCRDUPC.cbl:L782-L783}, announce a length test the paragraph does not
         * carry, and no paragraph of the program computes a check digit.
         *
         * <p>The number below fails such a checksum and the edit admits it, so the answer comes
         * from the read that follows. The answer carries the not-found text and not the
         * character-class text at {@code app/cbl/COCRDUPC.cbl:L789}. All fifty seeded card numbers
         * satisfy that checksum, so the assertion lands on the edit and not on a stored row.
         */
        @Test
        @DisplayName("a card number failing a checksum passes the edit and reaches the read")
        void aCardNumberFailingAChecksumPassesTheEdit() {
            CardUpdateResponse answer = cardUpdateService.updateCard(
                    request(UNSEEDED_CARD, RENAMED_CARDHOLDER, "2030", "06", "13", STATUS_NO));

            assertAll(
                    () -> assertEquals(UpdateOutcome.CARD_NOT_FOUND, answer.outcome(),
                            "the edit admitted the number, so the read answered and no edit did"),
                    () -> assertNotEquals(CardValidationMessages.CARD_FILTER_NOT_NUMERIC,
                            answer.message(), "the character-class text L789 sets stays unset"));
        }

        /**
         * Asserts a card carrying either active status can be updated.
         *
         * <p>The posting program opens six files and the card file is not among them, and
         * {@code app/jcl/POSTTRAN.jcl} allocates no card dataset, so no posting decision reads the
         * status. The update path reads no status of its own either: the card this test turns
         * negative is updated again immediately afterwards.
         */
        @Test
        @DisplayName("a card whose active status is N can still be updated")
        void aNegativeActiveStatusDoesNotBlockAnUpdate() {
            cardUpdateService.updateCard(request(NEGATIVE_STATUS_CARD, RENAMED_CARDHOLDER, "2025",
                    "03", "01", STATUS_NO));

            CardUpdateResponse second = cardUpdateService.updateCard(request(NEGATIVE_STATUS_CARD,
                    NEGATIVE_STATUS_CARD_NAME, "2025", "03", "01", STATUS_NO));

            assertAll(
                    () -> assertEquals(UpdateOutcome.UPDATED, second.outcome(),
                            "an inactive card takes an update"),
                    () -> assertEquals(NEGATIVE_STATUS_CARD_NAME,
                            storedText(NEGATIVE_STATUS_CARD, "embossed_name"),
                            "the second update reached the row"),
                    () -> assertEquals(STATUS_NO,
                            storedText(NEGATIVE_STATUS_CARD, "active_status"),
                            "the status stayed negative through both updates"));
        }
    }

    /**
     * Asserts every message text this class relies on holds the characters the source declares.
     *
     * <p>Each literal below was read in {@code app/cbl/COCRDUPC.cbl} at the line named beside it,
     * inside the condition names on {@code WS-RETURN-MSG PIC X(75)} at
     * {@code app/cbl/COCRDUPC.cbl:L173}. Two condition names carry identical characters, at
     * {@code app/cbl/COCRDUPC.cbl:L190} and at {@code app/cbl/COCRDUPC.cbl:L192}, and both are
     * asserted.
     *
     * <p>The trailing full stop at {@code app/cbl/COCRDUPC.cbl:L188} and the two words of
     * {@code some one} at {@code app/cbl/COCRDUPC.cbl:L208} belong to the source literals. Outside
     * this method every assertion above names a constant.
     */
    @Test
    @DisplayName("every message text this class asserts matches the source character for character")
    void everyMessageTextThisClassAssertsMatchesTheSource() {
        assertAll(
                () -> assertEquals("Card name can only contain alphabets and spaces",
                        CardValidationMessages.NAME_MUST_BE_ALPHA, "L184"),
                () -> assertEquals("No input received",
                        CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED, "L186"),
                () -> assertEquals("No change detected with respect to values fetched.",
                        CardValidationMessages.NO_CHANGES_DETECTED, "L188"),
                () -> assertEquals("Account number must be a non zero 11 digit number",
                        CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_ZEROES, "L190"),
                () -> assertEquals("Account number must be a non zero 11 digit number",
                        CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_NOT_NUMERIC, "L192"),
                () -> assertEquals("Card number if supplied must be a 16 digit number",
                        CardValidationMessages.NEVER_EMITTED_SEARCHED_CARD_NOT_NUMERIC, "L194"),
                () -> assertEquals("Card Active Status must be Y or N",
                        CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO, "L196"),
                () -> assertEquals("Card expiry month must be between 1 and 12",
                        CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID, "L198"),
                () -> assertEquals("Invalid card expiry year",
                        CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID, "L200"),
                () -> assertEquals("Did not find this account in cards database",
                        CardValidationMessages.NEVER_EMITTED_DID_NOT_FIND_ACCT_IN_CARDXREF, "L202"),
                () -> assertEquals("Did not find cards for this search condition",
                        CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO, "L204"),
                () -> assertEquals("Could not lock record for update",
                        CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE, "L206"),
                () -> assertEquals("Record changed by some one else. Please review",
                        CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE, "L208"),
                () -> assertEquals("Update of record failed",
                        CardValidationMessages.LOCKED_BUT_UPDATE_FAILED, "L210"),
                () -> assertEquals("Error reading Card Data File",
                        CardValidationMessages.NEVER_EMITTED_XREF_READ_ERROR, "L212"),
                () -> assertEquals("Card number not provided",
                        CardValidationMessages.PROMPT_FOR_CARD, "L180"));
    }
}
