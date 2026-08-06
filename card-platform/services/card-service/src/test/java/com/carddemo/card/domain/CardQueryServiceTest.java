package com.carddemo.card.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import com.carddemo.card.CardApplication;
import com.carddemo.card.api.dto.CardListResponse;
import com.carddemo.card.api.dto.CardSummary;
import com.carddemo.card.domain.CardQueryService.CardListRow;
import com.carddemo.card.domain.CardQueryService.CardPage;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Asserts that {@link CardQueryService} pages, filters and finds the fifty seeded cards exactly as
 * the source programs do.
 *
 * <p>Two Customer Information Control System (CICS) programs supply the behaviour under test.
 * {@code app/cbl/COCRDLIC.cbl} browses the card file and fills a seven-row screen table.
 * {@code app/cbl/COCRDSLC.cbl} reads one card by card number and one card by account identifier.
 *
 * <p>The page holds seven rows. {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
 * {@code app/cbl/COCRDLIC.cbl:L177-L178} declares that count inside {@code 01 WS-CONSTANTS} at
 * L176, and the row table at {@code app/cbl/COCRDLIC.cbl:L253-L260} carries seven rows of 28
 * characters.
 *
 * <p>The next-page flag comes from one row read past the page.
 * {@code app/cbl/COCRDLIC.cbl:L1191-L1216} issues that read at L1197-L1205, sets the flag at
 * L1207-L1211 on {@code DFHRESP(NORMAL)} or {@code DFHRESP(DUPREC)}, and clears it at L1215-L1216
 * on {@code DFHRESP(ENDFILE)}.
 *
 * <p>Fifty rows at seven a page make eight pages: seven pages of seven, then one page of one. Every
 * expected value below is written out rather than computed, and each was measured in
 * {@code app/data/ASCII/carddata.txt}. Flyway loads those rows from
 * {@code src/main/resources/db/migration/V2__seed.sql}, and no test here opens a file under
 * {@code app/}.
 *
 * <p>The card verification value carries no assertion here.
 * {@code com.carddemo.card.entity.CardEntity} declares no accessor for the field, and
 * {@code CardholderDataExposureTest} owns its non-emission.
 *
 * <p><b>How this class runs.</b> {@link CardApplication} supplies the context, and one PostgreSQL
 * 18.4 container serves the whole class, on the image tag
 * {@code card-platform/docker-compose.yml} also names. Flyway creates schema
 * {@value #MIGRATED_SCHEMA}, applies {@code V1__schema.sql} and loads the fifty rows of
 * {@code V2__seed.sql}. {@code spring.jpa.hibernate.ddl-auto} is {@code validate}, so a mapping
 * that drifts from the migration stops the context, and every test here carries that check by
 * starting.
 *
 * <p>{@link DynamicPropertySource} points three datasource properties at the container. No
 * {@code ServiceConnection} annotation appears here:
 * {@code card-platform/services/card-service/pom.xml} declares no
 * {@code spring-boot-testcontainers} artifact. The four class properties below supply one inert
 * value for each variable {@code src/main/resources/application.yml} leaves without a default.
 *
 * <p>{@link MockitoSpyBean} wraps the repository so that two tests can read the row limit the
 * service asks for. The spy delegates every call to the real repository, so every assertion below
 * runs against the migrated schema.
 *
 * <p>Run this class from {@code card-platform/} with
 * {@code mvn -o -B -pl services/card-service -am test}.
 */
@SpringBootTest(
        classes = CardApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
                "USER_PASSWORD_HASH={noop}not-a-real-user-password",
                "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password"
        })
@Testcontainers
@DisplayName("CardQueryService over the fifty seeded cards: paging, filters and the two finders")
class CardQueryServiceTest {

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

    /** Card rows {@code V2__seed.sql} loads, each 150 characters wide in the fixture. */
    private static final int SEEDED_ROW_COUNT = 50;

    /**
     * Rows one page holds when a caller names no page size, from
     * {@code app/cbl/COCRDLIC.cbl:L177-L178}.
     */
    private static final int DEFAULT_PAGE_SIZE = 7;

    /**
     * Rows the service asks the database for while displaying {@value #DEFAULT_PAGE_SIZE}.
     *
     * <p>The extra row is the lookahead {@code READNEXT} at
     * {@code app/cbl/COCRDLIC.cbl:L1197-L1205}.
     */
    private static final int DEFAULT_PAGE_FETCH_COUNT = 8;

    /** Pages the walk of every seeded row visits: seven full pages, then one row. */
    private static final int SEEDED_PAGE_COUNT = 8;

    /** Card number of the first row in ascending card-number order. */
    private static final String FIRST_CARD_NUMBER = "0500024453765740";

    /** Card number of the seventh row, which closes the first page. */
    private static final String SEVENTH_CARD_NUMBER = "1142167692878931";

    /** Card number of the eighth row, which the lookahead read of the first page reaches. */
    private static final String EIGHTH_CARD_NUMBER = "1561409106491600";

    /** Card number of the forty-second row, after which eight rows remain. */
    private static final String FORTY_SECOND_CARD_NUMBER = "8112545834239735";

    /** Card number of the forty-third row, after which seven rows remain. */
    private static final String FORTY_THIRD_CARD_NUMBER = "8262593602473076";

    /** Card number of the forty-fourth row, which opens the final full page. */
    private static final String FORTY_FOURTH_CARD_NUMBER = "8517866958206008";

    /** Card number of the forty-ninth row, the last row a full page can display. */
    private static final String FORTY_NINTH_CARD_NUMBER = "9680294154603697";

    /** Card number of the fiftieth row, the highest key in the table. */
    private static final String FIFTIETH_CARD_NUMBER = "9805583408996588";

    /** Account identifier of the first row, eleven characters with its leading zeros. */
    private static final String FIRST_CARD_ACCOUNT_ID = "00000000050";

    /** Account identifier of the fiftieth row, which no first page reaches. */
    private static final String FIFTIETH_CARD_ACCOUNT_ID = "00000000040";

    /** Embossed name of the first row, trimmed of the padding {@code CHAR(50)} carries. */
    private static final String FIRST_CARD_EMBOSSED_NAME = "Aniya Von";

    /** Expiration date of the first row, from fixture columns 81 through 90. */
    private static final LocalDate FIRST_CARD_EXPIRATION_DATE = LocalDate.of(2023, 3, 9);

    /** Active status all fifty seeded rows carry, at fixture column 91. */
    private static final String ACTIVE_STATUS_YES = "Y";

    /** Active status of the one row a test inserts, which no seeded row carries. */
    private static final String ACTIVE_STATUS_NO = "N";

    /**
     * A sixteen-digit card number the table does not hold, and which fails the checksum no source
     * path applies.
     */
    private static final String UNSEEDED_CARD_NUMBER = "1234567812345678";

    /** An eleven-digit account identifier the table does not hold. */
    private static final String UNSEEDED_ACCOUNT_ID = "99999999999";

    /** The first card number with its leading zero dropped, as a short caller would send it. */
    private static final String FIRST_CARD_NUMBER_MISSING_ITS_LEADING_ZERO = "500024453765740";

    /** Sixteen characters holding a letter, which every source input path refuses. */
    private static final String CARD_NUMBER_HOLDING_A_LETTER = "12345678X2345678";

    /** Card number of the second card one test puts on {@value #FIRST_CARD_ACCOUNT_ID}. */
    private static final String INSERTED_CARD_NUMBER = "7000000000000001";

    /** Card verification value of that inserted row, three digits and plainly not a real one. */
    private static final String INSERTED_VERIFICATION_VALUE = "000";

    /** Embossed name of that inserted row. */
    private static final String INSERTED_EMBOSSED_NAME = "Second Card On One Account";

    /** Expiration date of that inserted row. */
    private static final LocalDate INSERTED_EXPIRATION_DATE = LocalDate.of(2028, 5, 4);

    /** Statement reading the browse key of every seeded row in ascending order. */
    private static final String ORDERED_CARD_NUMBERS_SQL =
            "SELECT card_number FROM " + MIGRATED_SCHEMA + ".card ORDER BY card_number ASC";

    /**
     * The one container every test in this class shares.
     *
     * <p>The class name comes from {@code org.testcontainers.postgresql}, the package
     * Testcontainers 2.0.5 ships it in.
     * {@code org.testcontainers.containers.PostgreSQLContainer} carries a deprecation on the same
     * artifact. {@link Container} on a static field gives one container per class, and
     * {@link Testcontainers} starts it before the Spring context reads a property below.
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
        registry.add("spring.datasource.url", CardQueryServiceTest::migratedSchemaUrl);
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

    /** The service under test, built by the context over the repository below. */
    @Autowired
    private CardQueryService cardQueryService;

    /**
     * The repository the service reads through, wrapped so that a test can capture the row limit.
     *
     * <p>The spy delegates every call to the real repository bean, so a captured call has also run.
     */
    @MockitoSpyBean
    private CardRepository cardRepository;

    /** Reads the seeded browse keys straight from the migrated schema. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Returns the fifty seeded card numbers in the order the browse walks them.
     *
     * <p>The three guards below hold the derived cursors honest while the counts above stay
     * written out. The statement reads {@code card_number} alone, so no other column leaves the
     * database here.
     *
     * @return the fifty card numbers, ascending
     */
    private List<String> orderedCardNumbers() {
        List<String> ordered = jdbcTemplate.queryForList(ORDERED_CARD_NUMBERS_SQL, String.class);

        assertAll(
                () -> assertEquals(SEEDED_ROW_COUNT, ordered.size(),
                        "V2__seed.sql loads fifty card rows"),
                () -> assertEquals(FIRST_CARD_NUMBER, ordered.getFirst(),
                        "the lowest key opens the browse"),
                () -> assertEquals(FIFTIETH_CARD_NUMBER, ordered.getLast(),
                        "the highest key closes it"));

        return ordered;
    }

    /**
     * Returns the card token of one card number, which is the cursor form the service accepts.
     *
     * @param cardNumber the full card number to tokenize
     * @return sixty-four lower-case hexadecimal characters
     */
    private static String cursorAt(String cardNumber) {
        return PanMasker.cardToken(cardNumber);
    }

    /**
     * Returns the card numbers a page carries, in page order.
     *
     * @param page the page to read
     * @return the card numbers of its rows
     */
    private static List<String> cardNumbersOf(CardPage page) {
        List<String> cardNumbers = new ArrayList<>(page.rows().size());
        for (CardListRow row : page.rows()) {
            cardNumbers.add(row.cardNumber());
        }
        return cardNumbers;
    }

    /**
     * Reports whether one card number fails the checksum that no source path applies.
     *
     * <p>{@code IF CC-CARD-NUM IS NOT NUMERIC} at {@code app/cbl/COCRDUPC.cbl:L784} is the one
     * card-number rule in the source, and the condition
     * {@code SEARCHED-CARD-NOT-NUMERIC} at {@code app/cbl/COCRDUPC.cbl:L193-L194} names sixteen
     * digits and nothing further. The computation below exists so that one test can prove its
     * argument would fail such a check.
     *
     * @param cardNumber the sixteen-digit value to weigh
     * @return {@code true} when the weighted digit sum is not a multiple of ten
     */
    private static boolean failsTheChecksumNoSourcePathApplies(String cardNumber) {
        int total = 0;
        for (int position = 0; position < cardNumber.length(); position++) {
            int digit = cardNumber.charAt(cardNumber.length() - 1 - position) - '0';
            if (position % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            total += digit;
        }
        return total % 10 != 0;
    }

    /**
     * Returns the component names one record declares, in declaration order.
     *
     * @param recordType the record class to read
     * @return its component names
     */
    private static List<String> componentNamesOf(Class<?> recordType) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /** The page size, the row read past the page, and the flag derived from that row. */
    @Nested
    @DisplayName("A page of seven rows, served by a read of eight")
    class PageSizeAndLookahead {

        /**
         * Asserts that a request naming no page size comes back with seven rows.
         *
         * <p>{@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
         * {@code app/cbl/COCRDLIC.cbl:L177-L178} is the count. The service owns the default, and
         * {@code com.carddemo.card.repository.CardRepository} declares neither the default nor the
         * lookahead count.
         */
        @Test
        @DisplayName("no page size supplied fills the page with seven rows")
        void noPageSizeSuppliedFillsSevenRows() {
            CardPage page = cardQueryService.listForward(null, null, null, null);

            assertAll(
                    () -> assertEquals(DEFAULT_PAGE_SIZE, page.rows().size(),
                            "a request naming no page size displays seven rows"),
                    () -> assertEquals(FIRST_CARD_NUMBER, cardNumbersOf(page).getFirst(),
                            "the page opens on the lowest browse key"),
                    () -> assertEquals(SEVENTH_CARD_NUMBER, cardNumbersOf(page).getLast(),
                            "the seventh seeded card closes the page"));
        }

        /**
         * Asserts that a page of seven rows asks the database for eight.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L1191-L1216} tests the page as full at L1191 and issues
         * one further {@code READNEXT} at L1197-L1205. This test captures the row limit the service
         * passes, so a later substitution of a {@code COUNT(*)} for that eighth row fails here
         * rather than passing.
         */
        @Test
        @DisplayName("a page of seven rows is served by a fetch of eight")
        void aPageOfSevenIsServedByAFetchOfEight() {
            ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);

            CardPage page = cardQueryService.listForward(null, null, null, null);
            verify(cardRepository).findFirstPage(limit.capture());

            assertAll(
                    () -> assertEquals(DEFAULT_PAGE_FETCH_COUNT, limit.getValue().max(),
                            "the service reads one row past the page it displays"),
                    () -> assertEquals(DEFAULT_PAGE_SIZE, page.rows().size(),
                            "the extra row is trimmed and never displayed"),
                    () -> assertTrue(page.nextPageExists(),
                            "the eighth row arrived, so a further page exists"),
                    () -> assertFalse(cardNumbersOf(page).contains(EIGHTH_CARD_NUMBER),
                            "the eighth seeded card sets the flag and stays off the page"));
        }

        /**
         * Asserts that a caller-supplied page size of three asks the database for four.
         *
         * <p>The lookahead is the page size plus one row, not a fixed eight. The extra read at
         * {@code app/cbl/COCRDLIC.cbl:L1197-L1205} fires once the counter reaches
         * {@code WS-MAX-SCREEN-LINES}, whatever that count holds.
         */
        @Test
        @DisplayName("a caller-supplied page size of three is served by a fetch of four")
        void aPageSizeOfThreeIsServedByAFetchOfFour() {
            ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);

            CardPage page = cardQueryService.listForward(null, 3, null, null);
            verify(cardRepository).findFirstPage(limit.capture());

            assertAll(
                    () -> assertEquals(4, limit.getValue().max(),
                            "three displayed rows are read as four"),
                    () -> assertEquals(3, page.rows().size(), "the page displays three rows"),
                    () -> assertTrue(page.nextPageExists(), "forty-seven rows still follow"));
        }

        /**
         * Asserts that a page size of one is served and that zero and one hundred and one are
         * refused.
         *
         * <p>{@link CardQueryService} bounds the size at one through one hundred. A page of one row
         * still reads two, so the flag it reports comes from the same lookahead as a page of seven.
         */
        @Test
        @DisplayName("a page size of one is served, and zero and one hundred and one are refused")
        void aPageSizeOutsideItsBoundsIsRefused() {
            CardPage single = cardQueryService.listForward(null, 1, null, null);

            assertAll(
                    () -> assertEquals(1, single.rows().size(), "a page of one row displays one"),
                    () -> assertEquals(FIRST_CARD_NUMBER, cardNumbersOf(single).getFirst(),
                            "that row is the lowest browse key"),
                    () -> assertTrue(single.nextPageExists(), "forty-nine rows follow it"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> cardQueryService.listForward(null, 0, null, null),
                            "a page of no rows is refused"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> cardQueryService.listForward(null, 101, null, null),
                            "a page above the ceiling is refused"));
        }
    }

    /** The next-page flag, read from whether the row past the page arrived. */
    @Nested
    @DisplayName("The next-page flag comes from the row read past the page")
    class NextPageFlag {

        /**
         * Asserts that exactly seven remaining rows fill one page and report no further page.
         *
         * <p>The cursor names the forty-third seeded card and excludes it, so seven rows remain.
         * The lookahead read then reaches end of file, and
         * {@code app/cbl/COCRDLIC.cbl:L1215-L1216} clears the flag on {@code DFHRESP(ENDFILE)}.
         */
        @Test
        @DisplayName("seven rows remain: seven come back and the flag clears")
        void sevenRemainingRowsClearTheFlag() {
            List<String> ordered = orderedCardNumbers();
            assertEquals(FORTY_THIRD_CARD_NUMBER, ordered.get(42),
                    "the cursor names the forty-third card, so seven rows remain");

            CardPage page = cardQueryService.listForward(cursorAt(FORTY_THIRD_CARD_NUMBER), null,
                    null, null);

            assertAll(
                    () -> assertEquals(DEFAULT_PAGE_SIZE, page.rows().size(),
                            "the seven remaining rows fill the page"),
                    () -> assertFalse(page.nextPageExists(),
                            "no eighth row arrived, so no further page exists"),
                    () -> assertEquals(FORTY_FOURTH_CARD_NUMBER, cardNumbersOf(page).getFirst(),
                            "the exclusive cursor opens the page on the next card"),
                    () -> assertEquals(FIFTIETH_CARD_NUMBER, cardNumbersOf(page).getLast(),
                            "the highest browse key closes the table"));
        }

        /**
         * Asserts that exactly eight remaining rows fill one page and report a further page.
         *
         * <p>The cursor names the forty-second seeded card, so eight rows remain. Seven show and
         * the eighth sets the flag, which {@code app/cbl/COCRDLIC.cbl:L1191-L1216} does at
         * L1207-L1211 on {@code DFHRESP(NORMAL)} or {@code DFHRESP(DUPREC)}.
         */
        @Test
        @DisplayName("eight rows remain: seven show and the flag sets")
        void eightRemainingRowsSetTheFlag() {
            List<String> ordered = orderedCardNumbers();
            assertEquals(FORTY_SECOND_CARD_NUMBER, ordered.get(41),
                    "the cursor names the forty-second card, so eight rows remain");

            CardPage page = cardQueryService.listForward(cursorAt(FORTY_SECOND_CARD_NUMBER), null,
                    null, null);

            assertAll(
                    () -> assertEquals(DEFAULT_PAGE_SIZE, page.rows().size(),
                            "seven of the eight remaining rows are displayed"),
                    () -> assertTrue(page.nextPageExists(),
                            "the eighth row arrived, so one more page follows"),
                    () -> assertEquals(FORTY_THIRD_CARD_NUMBER, cardNumbersOf(page).getFirst(),
                            "the page opens on the card after the cursor"),
                    () -> assertEquals(FORTY_NINTH_CARD_NUMBER, cardNumbersOf(page).getLast(),
                            "the forty-ninth card closes the page and the fiftieth sets the flag"));
        }

        /**
         * Asserts that a walk of every page returns all fifty seeded cards once, over eight pages.
         *
         * <p>Each request carries the cursor of the previous page, which is the card token of its
         * <em>last displayed</em> row. The walk asserts eight pages: seven pages of seven rows,
         * then a page of one. The flag sets on pages one through seven and clears on page eight.
         * The union of the eight pages equals every seeded card, with none repeated and none
         * missing.
         *
         * <p>The source stores a different value in the same place.
         * {@code app/cbl/COCRDLIC.cbl:L1194-L1195} saves the keys of the last displayed row, and
         * {@code app/cbl/COCRDLIC.cbl:L1212-L1214} then overwrites them with the keys of the
         * lookahead row. That browse is inclusive, and the cursor here is exclusive. A cursor taken
         * from the lookahead row would therefore skip one card a page, and the union assertion
         * below is what catches it.
         *
         * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
         */
        @Test
        @DisplayName("a walk of every page returns all fifty cards once, over eight pages")
        void theWalkOfEveryPageReturnsAllFiftyCardsOnce() {
            List<String> ordered = orderedCardNumbers();
            List<Integer> pageSizes = new ArrayList<>();
            List<Boolean> flags = new ArrayList<>();
            List<String> visited = new ArrayList<>();

            String cursor = null;
            for (int page = 1; page <= SEEDED_PAGE_COUNT; page++) {
                CardPage current = cardQueryService.listForward(cursor, null, null, null);
                pageSizes.add(current.rows().size());
                flags.add(current.nextPageExists());
                visited.addAll(cardNumbersOf(current));
                cursor = current.lastCardToken();
            }

            Set<String> distinct = new LinkedHashSet<>(visited);

            assertAll(
                    () -> assertEquals(List.of(7, 7, 7, 7, 7, 7, 7, 1), pageSizes,
                            "seven full pages, then a page of one"),
                    () -> assertEquals(List.of(true, true, true, true, true, true, true, false),
                            flags, "the flag sets on pages one through seven and clears on eight"),
                    () -> assertEquals(SEEDED_ROW_COUNT, visited.size(),
                            "the eight pages carry fifty rows between them"),
                    () -> assertEquals(SEEDED_ROW_COUNT, distinct.size(),
                            "no card is returned twice"),
                    () -> assertEquals(ordered, visited,
                            "the walk returns every seeded card, in browse order, none missing"),
                    () -> assertEquals(Set.copyOf(ordered), distinct,
                            "the union of the eight pages is every seeded card"));
        }

        /**
         * Asserts that the last page reaches a caller in ascending order and reports a further
         * page.
         *
         * <p>The backward browse fills its row table from the high index down at
         * {@code app/cbl/COCRDLIC.cbl:L1338-L1344} and steps the index down at
         * {@code app/cbl/COCRDLIC.cbl:L1346}, so the screen shows ascending rows. The database
         * returns them descending and the service reverses them.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L1284-L1287} presets the counter and the flag before that
         * browse reads anything, and both statements are unconditional. The flag asserted here
         * comes from the same extra row a forward page uses.
         */
        @Test
        @DisplayName("the last page reaches a caller ascending and reports a further page")
        void theLastPageReachesACallerAscending() {
            List<String> ordered = orderedCardNumbers();

            CardPage page = cardQueryService.listBackward(null, null, null, null);

            assertAll(
                    () -> assertEquals(DEFAULT_PAGE_SIZE, page.rows().size(),
                            "the last page displays seven rows"),
                    () -> assertEquals(ordered.subList(43, 50), cardNumbersOf(page),
                            "rows forty-four through fifty arrive in ascending order"),
                    () -> assertEquals(FORTY_FOURTH_CARD_NUMBER, cardNumbersOf(page).getFirst(),
                            "the lowest card of the page arrives first"),
                    () -> assertTrue(page.nextPageExists(),
                            "forty-three rows precede the page, so one more page exists"));
        }
    }

    /** The form a cursor takes, and what happens to a value of the wrong form. */
    @Nested
    @DisplayName("The paging cursor carries a card token")
    class PagingCursor {

        /**
         * Asserts that both cursors of a page are the card tokens of its first and last row.
         *
         * <p>The source holds the same pair. {@code WS-CA-FIRST-CARDKEY} takes the first row and
         * {@code WS-CA-LAST-CARDKEY} takes the last displayed row at
         * {@code app/cbl/COCRDLIC.cbl:L1194-L1195}. Both cursors here carry a card token, and no
         * Primary Account Number (PAN) travels as paging state.
         */
        @Test
        @DisplayName("both cursors of a page are the card tokens of its first and last row")
        void bothCursorsAreTheCardTokensOfTheFirstAndLastRow() {
            CardPage page = cardQueryService.listForward(null, null, null, null);

            assertAll(
                    () -> assertEquals(cursorAt(FIRST_CARD_NUMBER), page.firstCardToken(),
                            "the backward cursor is the token of the first row"),
                    () -> assertEquals(cursorAt(SEVENTH_CARD_NUMBER), page.lastCardToken(),
                            "the forward cursor is the token of the last displayed row"),
                    () -> assertTrue(page.lastCardToken().matches(PanMasker.CARD_TOKEN_PATTERN),
                            "a cursor holds the one card-token shape this platform declares"),
                    () -> assertFalse(page.lastCardToken().contains(SEVENTH_CARD_NUMBER),
                            "no cursor carries the card number of the row it names"));
        }

        /**
         * Asserts that a well-shaped cursor naming no card is refused.
         *
         * <p>A cursor resolves through column {@code card_token}, so a token this service never
         * issued reaches no row. Answering such a request with page one would hand a caller rows it
         * already holds.
         */
        @Test
        @DisplayName("a cursor naming no card is refused rather than restarting the browse")
        void aCursorNamingNoCardIsRefused() {
            String cursor = cursorAt(UNSEEDED_CARD_NUMBER);

            assertAll(
                    () -> assertTrue(cursor.matches(PanMasker.CARD_TOKEN_PATTERN),
                            "the value carries the shape of a card token"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> cardQueryService.listForward(cursor, null, null, null),
                            "a token naming no seeded card is refused"));
        }

        /**
         * Asserts that a card number is refused where a card token belongs.
         *
         * <p>Sixteen digits fail the card-token shape on width and again on character class, so the
         * refusal happens before any lookup runs.
         */
        @Test
        @DisplayName("a card number is refused where a card token belongs")
        void aCardNumberIsRefusedWhereACardTokenBelongs() {
            assertThrows(IllegalArgumentException.class,
                    () -> cardQueryService.listForward(FIRST_CARD_NUMBER, null, null, null),
                    "a card number is not a cursor this service issued");
        }
    }

    /** The account filter, the card-number filter, and where in the read they apply. */
    @Nested
    @DisplayName("Both filters, applied inside the query predicate")
    class Filters {

        /**
         * Asserts that a page carrying no filter is scoped to no account.
         *
         * <p>{@code 9500-FILTER-RECORDS} at {@code app/cbl/COCRDLIC.cbl:L1382-L1409} applies the
         * account test at L1385-L1386 only while {@code FLG-ACCTFILTER-ISVALID} holds, and applies
         * nothing while it does not. All fifty seeded accounts hold one card each, so an unfiltered
         * page of seven rows names seven accounts.
         */
        @Test
        @DisplayName("an unfiltered first page holds seven rows across seven accounts")
        void anUnfilteredFirstPageSpansSevenAccounts() {
            CardPage page = cardQueryService.listForward(null, null, null, null);

            Set<String> accountIds = new LinkedHashSet<>();
            for (CardListRow row : page.rows()) {
                accountIds.add(row.accountId());
            }

            assertAll(
                    () -> assertEquals(DEFAULT_PAGE_SIZE, page.rows().size(),
                            "an unfiltered page displays seven rows"),
                    () -> assertEquals(DEFAULT_PAGE_SIZE, accountIds.size(),
                            "each of the seven rows belongs to a different account"),
                    () -> assertFalse(accountIds.contains(FIFTIETH_CARD_ACCOUNT_ID),
                            "the account of the fiftieth card lies beyond the first page"));
        }

        /**
         * Asserts that an account filter returns the one card that account holds.
         *
         * <p>The test is {@code CARD-ACCT-ID = CC-ACCT-ID} at
         * {@code app/cbl/COCRDLIC.cbl:L1386}. Every one of the fifty seeded account identifiers is
         * distinct, so the filter selects exactly one row here.
         */
        @Test
        @DisplayName("an account filter returns that account's single card")
        void anAccountFilterReturnsThatAccountsSingleCard() {
            CardPage page = cardQueryService.listForward(null, null, FIRST_CARD_ACCOUNT_ID, null);

            assertAll(
                    () -> assertEquals(1, page.rows().size(),
                            "the account holds one card, so the page holds one row"),
                    () -> assertEquals(FIRST_CARD_ACCOUNT_ID, page.rows().getFirst().accountId(),
                            "the row belongs to the account the caller named"),
                    () -> assertEquals(FIRST_CARD_NUMBER, page.rows().getFirst().cardNumber(),
                            "the row is the card the fixture puts on that account"),
                    () -> assertFalse(page.nextPageExists(),
                            "one matching row leaves no further page"));
        }

        /**
         * Asserts that a card-number filter matching a seeded row returns that one row.
         *
         * <p>The test is {@code CARD-NUM = CC-CARD-NUM-N} at
         * {@code app/cbl/COCRDLIC.cbl:L1397}. {@code KEYS(16 0)} at
         * {@code app/jcl/CARDFILE.jcl:L54} makes the card number the primary key of the Virtual
         * Storage Access Method (VSAM) dataset, so the filter reaches at most one row.
         */
        @Test
        @DisplayName("a card-number filter returns the one row it names")
        void aCardNumberFilterReturnsTheOneRowItNames() {
            CardPage page = cardQueryService.listForward(null, null, null, SEVENTH_CARD_NUMBER);

            assertAll(
                    () -> assertEquals(1, page.rows().size(), "a keyed filter reaches one row"),
                    () -> assertEquals(SEVENTH_CARD_NUMBER, page.rows().getFirst().cardNumber(),
                            "the row is the card the caller named"),
                    () -> assertFalse(page.nextPageExists(),
                            "one matching row leaves no further page"));
        }

        /**
         * Asserts that a card-number filter naming no seeded card returns no row.
         *
         * <p>An unmatched filter yields an empty page rather than an exception, and
         * {@code app/cbl/COCRDLIC.cbl:L1219} answers the same condition with the screen message
         * {@code 'NO MORE RECORDS TO SHOW'}.
         */
        @Test
        @DisplayName("a card-number filter naming no seeded card returns no row")
        void aCardNumberFilterNamingNoSeededCardReturnsNoRow() {
            CardPage page = cardQueryService.listForward(null, null, null, UNSEEDED_CARD_NUMBER);

            assertAll(
                    () -> assertTrue(page.rows().isEmpty(), "no seeded card carries that number"),
                    () -> assertFalse(page.nextPageExists(),
                            "a page holding no row reports no further page"));
        }

        /**
         * Asserts that both filters naming one card return that card.
         *
         * <p>{@code 9500-FILTER-RECORDS} runs the account test at
         * {@code app/cbl/COCRDLIC.cbl:L1385-L1394} and the card test at
         * {@code app/cbl/COCRDLIC.cbl:L1396-L1405} over the same record, so two filters narrow the
         * result rather than widening it.
         */
        @Test
        @DisplayName("both filters naming one card return that card")
        void bothFiltersNamingOneCardReturnThatCard() {
            CardPage page = cardQueryService.listForward(null, null, FIRST_CARD_ACCOUNT_ID,
                    FIRST_CARD_NUMBER);

            assertAll(
                    () -> assertEquals(1, page.rows().size(), "both tests hold on one row"),
                    () -> assertEquals(FIRST_CARD_NUMBER, page.rows().getFirst().cardNumber(),
                            "the row is the card both filters name"),
                    () -> assertEquals(FIRST_CARD_ACCOUNT_ID, page.rows().getFirst().accountId(),
                            "the row belongs to the account both filters name"));
        }

        /**
         * Asserts that two filters naming different cards return no row.
         *
         * <p>Each seeded account carries one card, so the account of the fiftieth seeded card and
         * the card number of the first cannot hold together.
         */
        @Test
        @DisplayName("two filters naming different cards return no row")
        void twoFiltersNamingDifferentCardsReturnNoRow() {
            CardPage page = cardQueryService.listForward(null, null, FIFTIETH_CARD_ACCOUNT_ID,
                    FIRST_CARD_NUMBER);

            assertAll(
                    () -> assertTrue(page.rows().isEmpty(), "no row satisfies both tests"),
                    () -> assertFalse(page.nextPageExists(),
                            "a page holding no row reports no further page"));
        }

        /**
         * Asserts that a filter applies before the row limit, not after it.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L1156-L1163} calls the filter at L1159-L1160 and advances
         * the screen counter at L1163 only when the record survives it, so an excluded record
         * consumes no slot on the page.
         *
         * <p>The account below belongs to the fiftieth card in browse order, and a first page reads
         * eight rows. A read that fetched those eight rows and filtered afterwards would return
         * nothing.
         */
        @Test
        @DisplayName("a filter applies before the row limit, so a late row still comes back")
        void aFilterAppliesBeforeTheRowLimit() {
            List<String> ordered = orderedCardNumbers();
            assertEquals(FIFTIETH_CARD_NUMBER, ordered.get(49),
                    "the account named below belongs to the fiftieth card in browse order");

            CardPage page = cardQueryService.listForward(null, null, FIFTIETH_CARD_ACCOUNT_ID,
                    null);

            assertAll(
                    () -> assertEquals(1, page.rows().size(),
                            "the predicate reached the database, so the fiftieth row came back"),
                    () -> assertEquals(FIFTIETH_CARD_NUMBER, page.rows().getFirst().cardNumber(),
                            "the row is the card that account holds"),
                    () -> assertEquals(FIFTIETH_CARD_ACCOUNT_ID, page.rows().getFirst().accountId(),
                            "the row belongs to the account the caller named"));
        }

        /**
         * Asserts that an all-zero filter is absent rather than invalid.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L1042-L1045} treats low values, spaces and zeros in the
         * card filter as no filter at all and sets {@code FLG-CARDFILTER-BLANK}, which
         * {@code app/cbl/COCRDLIC.cbl:L68} declares. The account filter carries the same blank
         * condition at {@code app/cbl/COCRDLIC.cbl:L64}, set at
         * {@code app/cbl/COCRDLIC.cbl:L1010}.
         */
        @Test
        @DisplayName("an all-zero filter is absent, so the full first page comes back")
        void anAllZeroFilterIsAbsentRatherThanInvalid() {
            CardPage page = cardQueryService.listForward(null, null, "00000000000",
                    "0000000000000000");

            assertAll(
                    () -> assertEquals(DEFAULT_PAGE_SIZE, page.rows().size(),
                            "neither zero filter narrows the page"),
                    () -> assertEquals(FIRST_CARD_NUMBER, cardNumbersOf(page).getFirst(),
                            "the page opens on the lowest browse key"),
                    () -> assertTrue(page.nextPageExists(), "forty-three rows follow the page"));
        }
    }


    /** The two single-card finders, taken from the card detail program. */
    @Nested
    @DisplayName("The two finders of app/cbl/COCRDSLC.cbl")
    class Finders {

        /**
         * Asserts that the finder returns the seeded values of the first fixture row.
         *
         * <p>{@code 9100-GETCARD-BYACCTCARD} at {@code app/cbl/COCRDSLC.cbl:L736-L775} keys the
         * read on the card number alone. L740 moves the supplied card number into the key, and the
         * account move above it at L739 is commented out.
         *
         * <p>Column {@code embossed_name} holds {@code CHAR(50)}, so a read returns the name
         * padded to fifty characters and the assertion below strips that padding.
         */
        @Test
        @DisplayName("findByCardNumber returns the seeded values of the first row")
        void findByCardNumberReturnsTheSeededValuesOfTheFirstRow() {
            Optional<CardEntity> found = cardQueryService.findByCardNumber(FIRST_CARD_NUMBER);
            assertTrue(found.isPresent(), "the seeded card number reaches its row");
            CardEntity card = found.orElseThrow();

            assertAll(
                    () -> assertEquals(FIRST_CARD_NUMBER, card.getCardNumber(),
                            "the row carries the card number the caller named"),
                    () -> assertEquals(FIRST_CARD_ACCOUNT_ID, card.getAccountId(),
                            "the account identifier keeps its eight leading zeros"),
                    () -> assertEquals(FIRST_CARD_EMBOSSED_NAME, card.getEmbossedName().strip(),
                            "the embossed name arrives as the fixture holds it"),
                    () -> assertEquals(FIRST_CARD_EXPIRATION_DATE, card.getExpirationDate(),
                            "the expiry date arrives as a calendar date"),
                    () -> assertEquals(ACTIVE_STATUS_YES, card.getActiveStatus(),
                            "the active status is carried through with no interpretation"));
        }

        /**
         * Asserts that a card number narrower than the stored key is left-padded with zeros.
         *
         * <p>Column {@code card_number} holds {@code CHAR(16)} and matches on text, so a fifteen
         * character argument would otherwise reach no row. {@code KEYS(16 0)} at
         * {@code app/jcl/CARDFILE.jcl:L54} fixes that width.
         */
        @Test
        @DisplayName("findByCardNumber pads a short value to its declared width")
        void findByCardNumberPadsAShortValueToItsDeclaredWidth() {
            Optional<CardEntity> found =
                    cardQueryService.findByCardNumber(FIRST_CARD_NUMBER_MISSING_ITS_LEADING_ZERO);
            assertTrue(found.isPresent(), "the padded key reaches the first seeded row");
            CardEntity card = found.orElseThrow();

            assertAll(
                    () -> assertEquals(FIRST_CARD_NUMBER, card.getCardNumber(),
                            "the padded value names the first seeded card"),
                    () -> assertEquals(PicClause.CARD_NUM_WIDTH, card.getCardNumber().length(),
                            "the stored key holds sixteen characters"));
        }

        /**
         * Asserts that a card number no seeded row carries yields an empty result.
         *
         * <p>{@code app/cbl/COCRDSLC.cbl:L755} takes its {@code DFHRESP(NOTFND)} branch for that
         * condition, and {@code app/cbl/COCRDSLC.cbl:L760} sets
         * {@code DID-NOT-FIND-ACCTCARD-COMBO} behind the guard at L759. That condition carries the
         * text {@code Did not find cards for this search condition} at
         * {@code app/cbl/COCRDSLC.cbl:L153-L154}. The finder here answers with an empty
         * {@link Optional} and builds no message.
         */
        @Test
        @DisplayName("findByCardNumber returns nothing for an unseeded number")
        void findByCardNumberReturnsNothingForAnUnseededNumber() {
            Optional<CardEntity> found = cardQueryService.findByCardNumber(UNSEEDED_CARD_NUMBER);

            assertTrue(found.isEmpty(), "no seeded row carries that card number");
        }

        /**
         * Asserts that a card number holding a letter is refused.
         *
         * <p>{@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is alphanumeric where it
         * is stored. Every input path in the source narrows it to digits:
         * {@code IF CC-CARD-NUM IS NOT NUMERIC} at {@code app/cbl/COCRDLIC.cbl:L1052} on the list
         * filter, and the same test at {@code app/cbl/COCRDUPC.cbl:L784} on the update screen.
         */
        @Test
        @DisplayName("findByCardNumber refuses a character outside 0 through 9")
        void findByCardNumberRefusesACharacterOutsideZeroThroughNine() {
            assertThrows(IllegalArgumentException.class,
                    () -> cardQueryService.findByCardNumber(CARD_NUMBER_HOLDING_A_LETTER),
                    "the source narrows a card number to sixteen digits");
        }

        /**
         * Asserts that the by-account finder returns the one card a seeded account holds.
         *
         * <p>{@code 9150-GETCARD-BYACCT} at {@code app/cbl/COCRDSLC.cbl:L779-L810} reads the
         * alternate index path at L783-L791. No {@code PERFORM} statement names that paragraph. A
         * search for {@code 9150} returns its label at L779 and its exit at L810 and nothing else,
         * so the source declares the capability and calls it never.
         *
         * <p>{@code NONUNIQUEKEY} at {@code app/jcl/CARDFILE.jcl:L86} admits many rows under one
         * key, which is why the return type is a {@link List}. All fifty seeded accounts hold one
         * card each.
         */
        @Test
        @DisplayName("findByAccountId returns the one card a seeded account holds")
        void findByAccountIdReturnsTheOneCardASeededAccountHolds() {
            List<CardEntity> cards = cardQueryService.findByAccountId(FIRST_CARD_ACCOUNT_ID);

            assertAll(
                    () -> assertEquals(1, cards.size(), "the seeded account holds one card"),
                    () -> assertEquals(FIRST_CARD_NUMBER, cards.getFirst().getCardNumber(),
                            "the row is the card the fixture puts on that account"),
                    () -> assertEquals(FIRST_CARD_ACCOUNT_ID, cards.getFirst().getAccountId(),
                            "the row belongs to the account the caller named"));
        }

        /**
         * Asserts that an account no seeded row carries yields an empty list.
         *
         * <p>{@code app/cbl/COCRDSLC.cbl:L799} answers the same condition by setting
         * {@code DID-NOT-FIND-ACCT-IN-CARDXREF}, whose text
         * {@code Did not find this account in cards database} is declared at
         * {@code app/cbl/COCRDSLC.cbl:L151-L152}. The finder here returns an empty {@link List} and
         * throws nothing.
         */
        @Test
        @DisplayName("findByAccountId returns an empty list for an unseeded account")
        void findByAccountIdReturnsAnEmptyListForAnUnseededAccount() {
            List<CardEntity> cards = cardQueryService.findByAccountId(UNSEEDED_ACCOUNT_ID);

            assertTrue(cards.isEmpty(), "no seeded row belongs to that account");
        }
    }

    /** The three fields a list row projects, and the two fields a page carries beside them. */
    @Nested
    @DisplayName("The list row projects three fields and the page counts nothing")
    class Projection {

        /**
         * Asserts that a list row carries the card number, the account identifier and the active
         * status of its seeded row.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L1165-L1171} moves exactly those three fields into the
         * screen row table, and {@code app/cbl/COCRDLIC.cbl:L1338-L1344} repeats the three on the
         * backward path.
         */
        @Test
        @DisplayName("a list row carries the card number, account identifier and active status")
        void aListRowCarriesTheThreeProjectedValues() {
            CardPage page = cardQueryService.listForward(null, null, null, null);
            CardListRow row = page.rows().getFirst();

            assertAll(
                    () -> assertEquals(FIRST_CARD_NUMBER, row.cardNumber(),
                            "the row carries the card number of the first seeded card"),
                    () -> assertEquals(FIRST_CARD_ACCOUNT_ID, row.accountId(),
                            "the row carries its eleven-character account identifier"),
                    () -> assertEquals(ACTIVE_STATUS_YES, row.activeStatus(),
                            "the row carries its one-character active status"));
        }

        /**
         * Asserts that the list row and the list summary each declare those three fields and no
         * more.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L253-L260} sizes the screen table: {@code WS-ALL-ROWS PIC
         * X(196)} at L253 holds {@code WS-SCREEN-ROWS OCCURS 7 TIMES} at L255, whose three members
         * at L258 through L260 measure 11 plus 16 plus 1 characters. Seven rows of 28 characters
         * fill 196 exactly, and the author's own comment at L250 reads
         * {@code File Data Array 28 CHARS X 7 ROWS = 196}.
         *
         * <p>No component carries the embossed name, the expiry date or the card verification
         * value.
         */
        @Test
        @DisplayName("the list row and the list summary declare exactly three components")
        void theListRowAndTheListSummaryDeclareExactlyThreeComponents() {
            List<String> rowComponents = componentNamesOf(CardListRow.class);
            List<String> summaryComponents = componentNamesOf(CardSummary.class);
            List<String> projected = List.of("cardNumber", "accountId", "activeStatus");

            assertAll(
                    () -> assertEquals(projected, rowComponents,
                            "the list row declares the three fields the screen table holds"),
                    () -> assertEquals(projected, summaryComponents,
                            "the list summary declares the same three"),
                    () -> assertFalse(namesAnyWithheldField(rowComponents),
                            "the list row names no embossed name, expiry date or verification "
                                    + "value"),
                    () -> assertFalse(namesAnyWithheldField(summaryComponents),
                            "the list summary names none of the three either"));
        }

        /**
         * Asserts that no paging type declares a total count or a page number.
         *
         * <p>The source pages by browse key and not by ordinal. It keeps
         * {@code WS-CA-FIRST-CARDKEY} and {@code WS-CA-LAST-CARDKEY} at
         * {@code app/cbl/COCRDLIC.cbl:L230-L235} and the flag {@code WS-CA-NEXT-PAGE-IND} at
         * {@code app/cbl/COCRDLIC.cbl:L242-L244}, and it counts no rows it has not read.
         */
        @Test
        @DisplayName("no paging type declares a total count or a page number")
        void noPagingTypeDeclaresATotalCountOrAPageNumber() {
            List<String> pageComponents = componentNamesOf(CardPage.class);
            List<String> responseComponents = componentNamesOf(CardListResponse.class);

            assertAll(
                    () -> assertEquals(
                            List.of("rows", "nextPageExists", "firstCardToken", "lastCardToken"),
                            pageComponents, "a page carries its rows, the flag and two cursors"),
                    () -> assertEquals(List.of("cards", "nextPageExists", "nextCursor"),
                            responseComponents,
                            "a response carries its rows, the flag and one cursor"),
                    () -> assertFalse(namesACountOrAnOrdinal(pageComponents),
                            "no page component counts rows or numbers pages"),
                    () -> assertFalse(namesACountOrAnOrdinal(responseComponents),
                            "no response component counts rows or numbers pages"));
        }
    }

    /** Behaviour a competent engineer would add, and which the source does not have. */
    @Nested
    @DisplayName("Deliberate non-additions: no checksum, and no status gate")
    class DeliberateNonAdditions {

        /**
         * Asserts that a card number failing the checksum is accepted and simply matches no row.
         *
         * <p>{@code IF CC-CARD-NUM IS NOT NUMERIC} at {@code app/cbl/COCRDUPC.cbl:L784} is the one
         * card-number rule in the source, and the condition {@code SEARCHED-CARD-NOT-NUMERIC} at
         * {@code app/cbl/COCRDUPC.cbl:L193-L194} names sixteen digits and nothing further. All
         * fifty seeded card numbers satisfy the checksum, so the value below is constructed rather
         * than loaded.
         *
         * <p>Both paths answer with no row. Neither refuses the argument, which is what a checksum
         * rule would do.
         */
        @Test
        @DisplayName("a card number failing the checksum is accepted and matches no row")
        void aCardNumberFailingTheChecksumIsAcceptedAndMatchesNoRow() {
            assertTrue(failsTheChecksumNoSourcePathApplies(UNSEEDED_CARD_NUMBER),
                    "the value below would fail a checksum rule, were one applied");

            Optional<CardEntity> found = cardQueryService.findByCardNumber(UNSEEDED_CARD_NUMBER);
            CardPage page = cardQueryService.listForward(null, null, null, UNSEEDED_CARD_NUMBER);

            assertAll(
                    () -> assertTrue(found.isEmpty(),
                            "the finder answers with no row rather than a refusal"),
                    () -> assertTrue(page.rows().isEmpty(),
                            "the filter answers with no row rather than a refusal"),
                    () -> assertFalse(page.nextPageExists(),
                            "a page holding no row reports no further page"));
        }

        /**
         * Asserts that a second, inactive card on one account reaches both the finder and the page.
         *
         * <p>The source treats a duplicate-key read as a successful one. It accepts
         * {@code DFHRESP(NORMAL)} and {@code DFHRESP(DUPREC)} together at
         * {@code app/cbl/COCRDLIC.cbl:L1156-L1158}, again for the lookahead row at
         * {@code app/cbl/COCRDLIC.cbl:L1207-L1211}, and again on the backward path at
         * {@code app/cbl/COCRDLIC.cbl:L1332-L1334}. Here that condition is the non-unique index
         * {@code idx_card_account_id} returning more than one row, which
         * {@code NONUNIQUEKEY} at {@code app/jcl/CARDFILE.jcl:L86} admits.
         *
         * <p>The inserted row carries active status {@code N}, which no seeded row holds, and it
         * comes back from both reads. No query gates on the active status of a card or of an
         * account.
         *
         * <p>{@link Transactional} appears on this method alone, so the inserted row rolls back and
         * the fifty seeded rows stand for every other test. No fixture file is written.
         */
        @Test
        @Transactional
        @DisplayName("a second, inactive card on one account reaches the finder and the page")
        void aSecondInactiveCardOnOneAccountReachesTheFinderAndThePage() {
            cardRepository.save(new CardEntity(INSERTED_CARD_NUMBER, FIRST_CARD_ACCOUNT_ID,
                    INSERTED_VERIFICATION_VALUE, INSERTED_EMBOSSED_NAME, INSERTED_EXPIRATION_DATE,
                    ACTIVE_STATUS_NO));

            List<CardEntity> cards = cardQueryService.findByAccountId(FIRST_CARD_ACCOUNT_ID);
            CardPage scoped = cardQueryService.listForward(null, null, FIRST_CARD_ACCOUNT_ID, null);
            CardPage unfiltered = cardQueryService.listForward(null, null, null, null);

            Set<String> statuses = new LinkedHashSet<>();
            for (CardEntity card : cards) {
                statuses.add(card.getActiveStatus());
            }

            assertAll(
                    () -> assertEquals(2, cards.size(),
                            "the non-unique account index carries both cards"),
                    () -> assertEquals(Set.of(ACTIVE_STATUS_YES, ACTIVE_STATUS_NO), statuses,
                            "the inactive card comes back beside the active one"),
                    () -> assertEquals(List.of(FIRST_CARD_NUMBER, INSERTED_CARD_NUMBER),
                            cardNumbersOf(scoped),
                            "the scoped page carries both cards in ascending key order"),
                    () -> assertFalse(scoped.nextPageExists(),
                            "two matching rows leave no further page"),
                    () -> assertEquals(DEFAULT_PAGE_SIZE, unfiltered.rows().size(),
                            "an unfiltered page still displays seven rows"),
                    () -> assertTrue(unfiltered.nextPageExists(),
                            "and still reports a further page"));
        }
    }

    /**
     * Reports whether any component name names a field the list row withholds.
     *
     * @param componentNames the component names to scan
     * @return {@code true} when one names the embossed name, the expiry date or the card
     *         verification value
     */
    private static boolean namesAnyWithheldField(List<String> componentNames) {
        for (String name : componentNames) {
            String lowered = name.toLowerCase(Locale.ROOT);
            if (lowered.contains("emboss") || lowered.contains("expir")
                    || lowered.contains("verification") || lowered.contains("cvv")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether any component name counts rows or numbers pages.
     *
     * @param componentNames the component names to scan
     * @return {@code true} when one names a count, a total, an offset or an ordinal
     */
    private static boolean namesACountOrAnOrdinal(List<String> componentNames) {
        for (String name : componentNames) {
            String lowered = name.toLowerCase(Locale.ROOT);
            if (lowered.contains("count") || lowered.contains("total")
                    || lowered.contains("offset") || lowered.contains("pagenumber")
                    || lowered.contains("ordinal")) {
                return true;
            }
        }
        return false;
    }

}
