package com.carddemo.card.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.carddemo.card.TestIdentityPasswords;
import com.carddemo.card.config.CrossSiteRequestFilter;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.repository.CardRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.server.PathContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full-stack test of {@link CardController} over a PostgreSQL container. Nothing is stubbed. The
 * controller, the domain services, the persistence layer, the masker, the Flyway migrations and the
 * fifty seeded rows are all the real ones.
 *
 * <p>Three answers reach this class and no other. {@code GET /cards} with no size parameter returns
 * the seven rows {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} declares at
 * {@code app/cbl/COCRDLIC.cbl:L177-L178}, inside {@code 01 WS-CONSTANTS.} at
 * {@code app/cbl/COCRDLIC.cbl:L176}. The lookahead read at
 * {@code app/cbl/COCRDLIC.cbl:L1197-L1205} drives a next-page flag, true on a first page and false
 * on a last one. {@code app/cbl/COCRDLIC.cbl:L1207-L1216} evaluates it: a normal or duplicate
 * condition sets next-page-exists at {@code app/cbl/COCRDLIC.cbl:L1210}, and an end-of-file
 * condition sets next-page-not-exists at {@code app/cbl/COCRDLIC.cbl:L1216}. A read keys on the
 * full sixteen-digit Primary Account Number and answers the masked form.
 *
 * <p>Row shape. A list row carries three values, declared at
 * {@code app/cbl/COCRDLIC.cbl:L250-L260}, where {@code WS-ALL-ROWS PIC X(196)} is redefined as
 * seven occurrences of an eleven-character account number, a sixteen-character card number and a
 * one-character status. The population site at {@code app/cbl/COCRDLIC.cbl:L1165-L1171} moves those
 * three and nothing else.
 *
 * <p>Filtering precedes counting:
 * {@code app/cbl/COCRDLIC.cbl:L1162} tests the do-not-exclude flag before
 * {@code app/cbl/COCRDLIC.cbl:L1163} increments the row counter.
 * {@code 9500-FILTER-RECORDS} at {@code app/cbl/COCRDLIC.cbl:L1382-L1405} decides that flag. Its
 * default include sits at {@code app/cbl/COCRDLIC.cbl:L1383} and its exact account match at
 * {@code app/cbl/COCRDLIC.cbl:L1385-L1391}.
 *
 * <p>Update answers. {@code app/cbl/COCRDUPC.cbl} declares each text on
 * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}: the absent-row text at
 * {@code app/cbl/COCRDUPC.cbl:L203/L204}, the lock text at
 * {@code app/cbl/COCRDUPC.cbl:L205/L206}, the concurrent-change text at
 * {@code app/cbl/COCRDUPC.cbl:L207/L208} and the failed-write text at
 * {@code app/cbl/COCRDUPC.cbl:L209/L210}. Every text asserted below is a literal typed in this
 * file, so a production constant is never compared against itself. The absent-row text has a
 * byte-identical twin at {@code app/cbl/COCRDSLC.cbl:L153/L154}, set at
 * {@code app/cbl/COCRDSLC.cbl:L760} on the detail path this service replaces.
 *
 * <p>Edit levers. Three condition names fix the ranges:
 * {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91},
 * {@code 88 VALID-MONTH VALUES 1 THRU 12.} at {@code app/cbl/COCRDUPC.cbl:L95} and
 * {@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at {@code app/cbl/COCRDUPC.cbl:L99}. The reset at
 * {@code app/cbl/COCRDUPC.cbl:L384} clears the text field. Fifteen {@code IF WS-RETURN-MSG-OFF}
 * guards then admit only the first setter, at {@code app/cbl/COCRDUPC.cbl:L730}, {@code L743},
 * {@code L773}, {@code L787}, {@code L816}, {@code L833}, {@code L855}, {@code L868}, {@code L888},
 * {@code L903}, {@code L921}, {@code L939}, {@code L1399}, {@code L1404} and {@code L1445}. No
 * expiry-day rule exists.
 *
 * <p>The expiry travels in three parts. {@code app/cbl/COCRDUPC.cbl:L115-L123} redefines the
 * ten-character expiry into a four-character year at {@code app/cbl/COCRDUPC.cbl:L117}, a
 * two-character month at {@code app/cbl/COCRDUPC.cbl:L119} and a two-character day at
 * {@code app/cbl/COCRDUPC.cbl:L121}. The write path reassembles them at
 * {@code app/cbl/COCRDUPC.cbl:L1467-L1474} and the conflict comparison slices positions 1-4, 6-7
 * and 9-10 at {@code app/cbl/COCRDUPC.cbl:L1505-L1507}.
 *
 * <p>What never leaves. {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} is stored
 * in the clear, and {@code app/cbl/COCRDUPC.cbl:L1464-L1465} carries it into the source update
 * record. {@code 9300-CHECK-CHANGE-IN-REC} at {@code app/cbl/COCRDUPC.cbl:L1498} compares six
 * fields at {@code app/cbl/COCRDUPC.cbl:L1503-L1508} with the card verification value first at
 * {@code app/cbl/COCRDUPC.cbl:L1503}, then refreshes all six at
 * {@code app/cbl/COCRDUPC.cbl:L1512-L1517}. The conflict body here carries five components and no
 * card verification value under any name.
 *
 * <p>Masking is additive. {@code app/bms/COCRDSL.bms} runs to 157 lines. It gives the card-number
 * field {@code ATTRB=(FSET,NORM,UNPROT)} at {@code app/bms/COCRDSL.bms:L96},
 * {@code HILIGHT=UNDERLINE} at {@code app/bms/COCRDSL.bms:L98}, {@code LENGTH=16} at
 * {@code app/bms/COCRDSL.bms:L99} and {@code POS=(8,45)} at {@code app/bms/COCRDSL.bms:L100}.
 * All sixteen digits therefore render in the clear. A read that succeeds against the seeded row
 * proves the lookup ran on the full value, and the answer proves the mask applies at the
 * serialization boundary alone.
 *
 * <p>Absences this class asserts. {@code app/cbl/COCRDUPC.cbl:L784} tests
 * {@code IF CC-CARD-NUM IS NOT NUMERIC} and nothing further. The comments at
 * {@code app/cbl/COCRDUPC.cbl:L782-L783} claim a numeric check and a sixteen-character check, the
 * field is {@code PIC X(16)}, and no checksum rule and no length rule exist.
 *
 * <p>{@code app/jcl/POSTTRAN.jcl} runs to 45 lines and runs the posting program in {@code STEP15}
 * at {@code app/jcl/POSTTRAN.jcl:L23}. It allocates six datasets at
 * {@code app/jcl/POSTTRAN.jcl:L28}, {@code L30}, {@code L32} (the card cross-reference),
 * {@code L34}, {@code L39} and {@code L41}. The card file is absent, so no card-status resource
 * exists for an authorization path to call.
 *
 * <p>How this class runs. One PostgreSQL 18.4 container serves the whole class, on the image tag
 * {@code card-platform/docker-compose.yml} also names. {@code src/main/resources/application.yml}
 * sits on the test classpath and supplies every setting other than the three the container decides.
 * {@code spring.flyway.create-schemas: true} creates schema {@code card_service}, then
 * {@code V1__schema.sql} and {@code V2__seed.sql} run.
 * {@code spring.jpa.hibernate.ddl-auto: validate} stops the context when a mapping drifts from the
 * migration.
 *
 * <p>Requests travel over a real port through the Java Development Kit client, so every assertion
 * below reads the bytes a caller reads. No method and no nested class carries
 * {@code Transactional}: a test transaction would roll back the writes these tests prove.
 *
 * <p>DEVIATION, wiring: {@code DynamicPropertySource} points the datasource at the container.
 * {@code ServiceConnection} needs {@code org.springframework.boot:spring-boot-testcontainers},
 * which {@code card-platform/services/card-service/pom.xml} does not declare. The same pom declares
 * no client-side test artifact carrying {@code TestRestTemplate} either, and Spring Boot 4.1.0
 * ships that type outside {@code spring-boot-test}.
 *
 * <p>The read route is {@code GET /cards/{cardNumber}} and the update route is
 * {@code PUT /cards/{cardNumber}}, each reproducing one Customer Information Control System (CICS)
 * transaction that addresses one card: {@code CCDL} at {@code app/csd/CARDDEMO.CSD:L347-L348} and
 * {@code CCUP} at {@code app/csd/CARDDEMO.CSD:L367-L369}. The list route requires the account
 * identifier and takes its paging position in header {@code X-Card-Cursor}.
 *
 * <p>DEVIATION, failed write after the lock: {@code CardController.statusOf} answers 503, and
 * {@code src/main/resources/openapi.yaml} documents 503 carrying the same text in the failure body.
 *
 * <p>DEVIATION, error body: the third component of a plain error body is named {@code route} and
 * holds a route template.
 *
 * <p>DEVIATION, a seventh answer: submitting the stored values answers 422 with the text
 * {@code app/cbl/COCRDUPC.cbl:L188} declares, from the comparison at
 * {@code app/cbl/COCRDUPC.cbl:L680-L682}.
 *
 * <p>DEVIATION, the inactive-card read: all fifty rows of {@code app/data/ASCII/carddata.txt} carry
 * status {@code Y}, so this class sets one row inactive inside the container and restores it. No
 * file under {@code app/} is touched.
 *
 * <p>DEVIATION, the card verification value assertion covers single-record bodies alone.
 * {@code V2__seed.sql} loads all fifty rows and the fixture is read-only. The string {@code 747}
 * appears outside the card verification value of row one only inside card number
 * {@code 9349107475869214}, which masks to {@code ************9214}.
 *
 * <p>DEVIATION, the dead declarations this class does not exercise. The three
 * {@code SEARCHED-*} texts at {@code app/cbl/COCRDUPC.cbl:L189}, {@code L191} and {@code L193} are
 * declared and never set. The six information texts at {@code app/cbl/COCRDUPC.cbl:L160-L171} and
 * the exit text at {@code app/cbl/COCRDUPC.cbl:L175/L176} belong to the screen. The failed-write
 * setter at {@code app/cbl/COCRDUPC.cbl:L1491} sits in the plain {@code ELSE} at
 * {@code app/cbl/COCRDUPC.cbl:L1490} under no guard, so the guard count is fifteen and not
 * sixteen. {@code 9150-GETCARD-BYACCT} at {@code app/cbl/COCRDSLC.cbl:L779} carries no caller
 * anywhere, and its condition set sits at {@code app/cbl/COCRDSLC.cbl:L799} with its exit label at
 * {@code app/cbl/COCRDSLC.cbl:L810}.
 *
 * <p>DEVIATION, locator corrections: the next-page derivation cites
 * {@code app/cbl/COCRDLIC.cbl:L1191-L1216}. {@code app/cbl/COCRDLIC.cbl:L1284-L1287} presets the
 * backward counter and the backward flag instead. The no-change branch leaves its paragraph at
 * {@code app/cbl/COCRDUPC.cbl:L692} and the exit label sits at
 * {@code app/cbl/COCRDUPC.cbl:L717}. The administrator fork spans
 * {@code app/cbl/COSGN00C.cbl:L230-L240}.
 *
 * <p>Run this class from {@code card-platform/} with
 * {@code mvn -o -B -pl services/card-service -am verify}. The module activates Failsafe, and its
 * report under {@code target/failsafe-reports} names this class.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("The card list, read and update surface over the migrated schema and the seeded rows")
class CardControllerIT {

    /** The image tag {@code card-platform/docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /**
     * The database name, the login name and the password of the container, one value for all three.
     * {@code card-platform/.env.example} declares the same value.
     */
    private static final String POSTGRES_CREDENTIAL = "carddemo";

    /**
     * The schema Flyway creates, from {@code spring.flyway.schemas} and
     * {@code spring.jpa.properties.hibernate.default_schema} in
     * {@code src/main/resources/application.yml}.
     */
    private static final String MIGRATED_SCHEMA = "card_service";

    /** Login name of the administrator identity, from {@code ADMIN_USERNAME} in the same file. */
    private static final String ADMIN_USERNAME = "admin001";

    /**
     * Password of the administrator identity, plainly synthetic and matching no live credential.
     * The property above configures its bcrypt hash, and this value is what a request presents.
     */
    static final String ADMIN_PASSWORD = TestIdentityPasswords.ADMIN_PASSWORD;

    /**
     * Rows one screen of the card list holds, from
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
     * {@code app/cbl/COCRDLIC.cbl:L177-L178}.
     */
    private static final int SCREEN_PAGE_SIZE = 7;

    /** Rows {@code V2__seed.sql} loads into {@code card} and into {@code card_xref}. */
    private static final int SEEDED_ROW_COUNT = 50;

    /**
     * Card number of seeded row one, from {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:L5}.
     */
    private static final String ROW_1_CARD_NUMBER = "0500024453765740";

    /**
     * Account identifier of seeded row one, from {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6}. The account owns exactly one seeded card.
     */
    private static final String ROW_1_ACCOUNT_ID = "00000000050";

    /**
     * Card verification value of seeded row one, from {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}. No response body and no log line carries it.
     */
    private static final String ROW_1_CARD_VERIFICATION_VALUE = "747";

    /**
     * Embossed name of seeded row one, from {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8}, without the padding the column carries.
     */
    private static final String ROW_1_EMBOSSED_NAME = "Aniya Von";

    /**
     * Expiry of seeded row one, from {@code CARD-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:L9}. The target corrects that spelling, so the response component
     * reads {@code expirationDate}.
     */
    private static final LocalDate ROW_1_EXPIRATION_DATE = LocalDate.of(2023, 3, 9);

    /** The ten characters the expiry of seeded row one serializes as. */
    private static final String ROW_1_EXPIRATION_TEXT = "2023-03-09";

    /** The masked form of seeded row one: twelve mask characters then the last four digits. */
    private static final String ROW_1_MASKED_CARD_NUMBER = "************5740";

    /** The one shape a serialized card number takes. The only regular expression in this file. */
    private static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * Active status of seeded row one, from {@code CARD-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10} and {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at
     * {@code app/cbl/COCRDUPC.cbl:L91}.
     */
    private static final String ACTIVE_STATUS_YES = "Y";

    /** The other value the same condition name admits. */
    private static final String ACTIVE_STATUS_NO = "N";

    /** Year of seeded row one, as the update body carries it. */
    private static final String ROW_1_EXPIRY_YEAR = "2023";

    /** Month of seeded row one, as the update body carries it. */
    private static final String ROW_1_EXPIRY_MONTH = "03";

    /** Day of seeded row one, as the update body carries it. */
    private static final String ROW_1_EXPIRY_DAY = "09";

    /** A sixteen-digit card number no seeded row holds. */
    private static final String ABSENT_CARD_NUMBER = "9999999999999999";

    /**
     * A sixteen-digit card number whose last digit fails the Luhn checksum. No seeded row holds it,
     * and no rule in {@code app/cbl/COCRDUPC.cbl} tests a checksum.
     */
    private static final String WRONG_CHECK_DIGIT_CARD_NUMBER = "4111111111111112";

    /**
     * Card numbers of the eight rows a list test adds to {@code ROW_1_ACCOUNT_ID}, so that one
     * account holds nine cards and a page of seven becomes observable. Every value sorts above
     * every seeded card number.
     */
    private static final List<String> FILLER_CARD_NUMBERS = List.of(
            "9990000000000001",
            "9990000000000002",
            "9990000000000003",
            "9990000000000004",
            "9990000000000005",
            "9990000000000006",
            "9990000000000007",
            "9990000000000008");

    /** Card number of the row the lock test adds, holds and removes. */
    private static final String VANISHING_CARD_NUMBER = "9990000000000009";

    /** Prefix every row this class inserts carries, and the one its cleanup removes. */
    private static final String INSERTED_CARD_NUMBER_PREFIX = "999";

    /** Card verification value every inserted row carries, three digits and plainly synthetic. */
    private static final String SYNTHETIC_CARD_VERIFICATION_VALUE = "000";

    /** Embossed name every inserted row carries, letters and spaces alone. */
    private static final String SYNTHETIC_EMBOSSED_NAME = "Integration Test Row";

    /** Expiry every inserted row carries. */
    private static final LocalDate SYNTHETIC_EXPIRATION_DATE = LocalDate.of(2027, 12, 31);

    /** Package name every log line this class inspects was emitted under. */
    private static final String APPLICATION_LOGGER_PREFIX = "com.carddemo";

    /** Longest one request waits for an answer, including a request that waits on a row lock. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** Longest this class waits for a request to reach the row lock a second connection holds. */
    private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(30);

    /** Reads how many lock requests the server is holding unmet. */
    private static final String UNMET_LOCK_REQUEST_COUNT_SQL =
            "SELECT count(*) FROM pg_locks WHERE locktype = 'transactionid' AND NOT granted";

    /** Takes the row lock a request then waits on. */
    private static final String LOCK_ONE_CARD_SQL =
            "SELECT card_number FROM card WHERE card_number = ? FOR UPDATE";

    /** Restores the three columns one card update may change. */
    private static final String RESTORE_ROW_1_SQL = """
            UPDATE card
               SET embossed_name = ?, expiration_date = ?, active_status = ?
             WHERE card_number = ?
            """;

    /** Reads bodies as text and never as an object, so every assertion sees the served bytes. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The container every test in this class shares.
     *
     * <p>{@code org.testcontainers.containers.PostgreSQLContainer} carries a deprecation in
     * Testcontainers 2.0.5, and {@code org.testcontainers.postgresql.PostgreSQLContainer} is the
     * replacement. The replacement takes no type argument.
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(POSTGRES_CREDENTIAL)
            .withUsername(POSTGRES_CREDENTIAL)
            .withPassword(POSTGRES_CREDENTIAL);

    /**
     * Points the Spring datasource at the running container.
     *
     * <p>Three properties leave here. {@code src/main/resources/application.yml} carries every
     * other datasource, Flyway and persistence setting, and no line below repeats one. The uniform
     * resource locator carries {@code currentSchema}, so an unqualified table name in a native
     * statement resolves against the migrated schema.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CardControllerIT::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container locator with the migrated schema named on it.
     *
     * @return the uniform resource locator the context connects with
     */
    private static String migratedSchemaUrl() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema=" + MIGRATED_SCHEMA;
    }

    /** Port the embedded server took, which the random-port web environment settles at refresh. */
    @LocalServerPort
    private int port;

    /** Reads and writes rows directly, outside every request this class makes. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Supplies the second connection the two conflict tests hold a row lock on. */
    @Autowired
    private DataSource dataSource;

    /** Inserts the rows a page of seven and a vanishing row need. */
    @Autowired
    private CardRepository cardRepository;

    /**
     * The route table the running application built, read to establish which paths carry a handler.
     *
     * <p>Named rather than taken by type. The actuator contributes handler mappings of its own, and
     * this is the one holding the annotated business routes.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMappings;

    /** Sends every request. Built once per test instance and closed by the runtime. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    /**
     * Returns the fifty seeded rows and an empty event table to the state each test found.
     *
     * <p>One statement restores the three columns a card update may change on seeded row one. No
     * route can change the card verification value: {@code CardUpdateRequest} carries no such
     * component and {@code CardEntity} declares no accessor for it. The value {@code 747}
     * therefore stands for the whole class.
     *
     * <p>{@code app/data/ASCII/carddata.txt} and every other file under {@code app/} stay
     * untouched. Rows change inside the container alone.
     */
    @AfterEach
    void restoreTheSeededState() {
        jdbcTemplate.update(RESTORE_ROW_1_SQL, ROW_1_EMBOSSED_NAME,
                java.sql.Date.valueOf(ROW_1_EXPIRATION_DATE), ACTIVE_STATUS_YES, ROW_1_CARD_NUMBER);
        jdbcTemplate.update("DELETE FROM card WHERE card_number LIKE ?",
                INSERTED_CARD_NUMBER_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM outbox_event");
    }

    /**
     * The container, the two migrations and the fifty rows they load.
     *
     * <p>The context starting at all carries the mapping check.
     * {@code src/main/resources/application.yml} sets {@code spring.jpa.hibernate.ddl-auto} to
     * {@code validate}, so a mapping that disagrees with the migration stops start-up.
     */
    @Nested
    @DisplayName("the container, the migrations and the seeded rows")
    class ContainerAndMigration {

        /**
         * Both migrations ran and loaded the row counts {@code V2__seed.sql} carries.
         *
         * <p>{@code app/data/ASCII/carddata.txt} holds fifty records of 150 bytes and
         * {@code app/data/ASCII/cardxref.txt} holds fifty of 36 bytes. The second is narrower than
         * {@code app/cpy/CVACT03Y.cpy} declares because the fourteen-byte {@code FILLER} at
         * {@code app/cpy/CVACT03Y.cpy:L8} is absent from the text fixture.
         */
        @Test
        @DisplayName("Flyway loads fifty cards and fifty cross-reference rows")
        void theSeedLoadsFiftyRowsIntoBothTables() {
            Integer cards = jdbcTemplate.queryForObject("SELECT count(*) FROM card", Integer.class);
            Integer crossReferences =
                    jdbcTemplate.queryForObject("SELECT count(*) FROM card_xref", Integer.class);

            assertAll("the seeded row counts",
                    () -> assertEquals(SEEDED_ROW_COUNT, cards, "table card holds fifty rows"),
                    () -> assertEquals(SEEDED_ROW_COUNT, crossReferences,
                            "table card_xref holds fifty rows"));
        }

        /**
         * The card verification value column holds the fixture value for row one.
         *
         * <p>Every absence assertion in this class rests on the value being present in the column.
         * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} declares it and
         * {@code V1__schema.sql} carries the column.
         */
        @Test
        @DisplayName("the card verification value column holds the fixture value for row one")
        void theCardVerificationValueColumnHoldsTheFixtureValue() {
            assertEquals(ROW_1_CARD_VERIFICATION_VALUE,
                    storedValueOf("card_verification_value", ROW_1_CARD_NUMBER),
                    "the stored value is the one the fixture carries");
        }
    }

    /**
     * {@code GET /cards}, where the page size of the source screen becomes observable.
     *
     * <p>Every test here reads the fifty seeded rows, and one adds eight more so that a single
     * account holds nine cards.
     */
    @Nested
    @DisplayName("listing one account's cards")
    class ListingOneAccountsCards {

        /**
         * A request naming no page size answers seven rows and reports a further page.
         *
         * <p>{@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
         * {@code app/cbl/COCRDLIC.cbl:L177-L178} fixes the count, and
         * {@code CardQueryService} owns the default. The value asserted is the one served, not a
         * constant this class reads back from the controller.
         */
        @Test
        @DisplayName("a request with no page size answers seven rows and a further page")
        void anAccountOfNineCardsAnswersSevenRows() {
            FILLER_CARD_NUMBERS.forEach(cardNumber -> insertCard(cardNumber, ROW_1_ACCOUNT_ID));

            HttpResponse<String> answer = listCards(ROW_1_ACCOUNT_ID, null);
            JsonNode body = bodyOf(answer);

            assertAll("the first page of nine cards",
                    () -> assertEquals(200, answer.statusCode(), "the list answers 200"),
                    () -> assertEquals(SCREEN_PAGE_SIZE, body.get("cards").size(),
                            "the page holds the seven rows the source screen holds"),
                    () -> assertTrue(body.get("nextPageExists").asBoolean(),
                            "nine cards leave a further page after seven"),
                    () -> assertFalse(body.get("nextCursor").isNull(),
                            "a further page carries the position to continue from"));
        }

        /**
         * The next-page flag is true on a first page of nine cards and false on the last.
         *
         * <p>The lookahead read at {@code app/cbl/COCRDLIC.cbl:L1197-L1205} decides the flag, and
         * {@code app/cbl/COCRDLIC.cbl:L1207-L1216} evaluates its outcome: next-page-exists at
         * {@code app/cbl/COCRDLIC.cbl:L1210} and next-page-not-exists at
         * {@code app/cbl/COCRDLIC.cbl:L1216}.
         */
        @Test
        @DisplayName("the next-page flag turns false on the last page and the cursor is dropped")
        void theFlagTurnsFalseOnTheLastPage() {
            FILLER_CARD_NUMBERS.forEach(cardNumber -> insertCard(cardNumber, ROW_1_ACCOUNT_ID));

            JsonNode first = bodyOf(listCards(ROW_1_ACCOUNT_ID, null));
            String cursor = first.get("nextCursor").asString();
            HttpResponse<String> answer = listCards(ROW_1_ACCOUNT_ID, cursor);
            JsonNode last = bodyOf(answer);

            assertAll("the walk to the last page",
                    () -> assertEquals(200, answer.statusCode(), "the second page answers 200"),
                    () -> assertEquals(2, last.get("cards").size(),
                            "two of the nine rows remain after a page of seven"),
                    () -> assertFalse(last.get("nextPageExists").asBoolean(),
                            "no page follows the last one"),
                    () -> assertTrue(last.get("nextCursor").isNull(),
                            "a last page carries no position to continue from"));
        }

        /**
         * A list row carries three values and no cardholder value.
         *
         * <p>{@code WS-ALL-ROWS PIC X(196)} at {@code app/cbl/COCRDLIC.cbl:L253} is redefined as
         * seven occurrences of the three fields at {@code app/cbl/COCRDLIC.cbl:L258-L260}, and the
         * comment at {@code app/cbl/COCRDLIC.cbl:L250} states the arithmetic. The population site
         * at {@code app/cbl/COCRDLIC.cbl:L1165-L1171} moves those three.
         */
        @Test
        @DisplayName("a list row carries the masked number, the account and the status alone")
        void aListRowCarriesThreeValues() {
            JsonNode row = bodyOf(listCards(ROW_1_ACCOUNT_ID, null)).get("cards").get(0);
            Set<String> names = propertyNamesOf(row);

            assertAll("one list row",
                    () -> assertEquals(Set.of("cardNumber", "accountId", "activeStatus"), names,
                            "a row carries three properties"),
                    () -> assertEquals(ROW_1_MASKED_CARD_NUMBER, row.get("cardNumber").asString(),
                            "the row carries the masked form"),
                    () -> assertTrue(row.get("cardNumber").asString()
                                    .matches(MASKED_CARD_NUMBER_PATTERN),
                            "the masked form is twelve mask characters then four digits"),
                    () -> assertEquals(ROW_1_ACCOUNT_ID, row.get("accountId").asString(),
                            "the row carries the account identifier"),
                    () -> assertEquals(ACTIVE_STATUS_YES, row.get("activeStatus").asString(),
                            "the row carries the status flag"),
                    () -> assertFalse(names.contains("embossedName"),
                            "a row carries no cardholder name"),
                    () -> assertFalse(names.contains("expirationDate"),
                            "a row carries no expiry"),
                    () -> assertFalse(names.contains("cardVerificationValue"),
                            "a row carries no card verification value"));
        }

        /**
         * An exact account filter answers the matching rows and not a page that contains them.
         *
         * <p>{@code app/cbl/COCRDLIC.cbl:L1162} tests the do-not-exclude flag before
         * {@code app/cbl/COCRDLIC.cbl:L1163} increments the counter, so filtering precedes
         * counting. {@code 9500-FILTER-RECORDS} sets the flag with a default include at
         * {@code app/cbl/COCRDLIC.cbl:L1383} and an exact account match at
         * {@code app/cbl/COCRDLIC.cbl:L1385-L1391}. Account {@code 00000000050} owns one seeded
         * card among fifty.
         */
        @Test
        @DisplayName("an exact account filter answers one row and not a page of seven")
        void filteringPrecedesCounting() {
            HttpResponse<String> answer = listCards(ROW_1_ACCOUNT_ID, null);
            JsonNode body = bodyOf(answer);

            assertAll("the filtered page",
                    () -> assertEquals(200, answer.statusCode(), "the list answers 200"),
                    () -> assertEquals(1, body.get("cards").size(),
                            "the page holds the one matching row"),
                    () -> assertFalse(body.get("nextPageExists").asBoolean(),
                            "one matching row leaves no further page"),
                    () -> assertTrue(body.get("nextCursor").isNull(),
                            "a last page carries no position to continue from"));
        }

        /**
         * The list body carries three properties, and neither a total nor a page number.
         *
         * <p>The source screen holds no total and no page number. It holds seven rows and a flag.
         */
        @Test
        @DisplayName("the list body carries three properties and no total or page number")
        void theListBodyCarriesThreeProperties() {
            Set<String> names = propertyNamesOf(bodyOf(listCards(ROW_1_ACCOUNT_ID, null)));

            assertAll("the list body",
                    () -> assertEquals(Set.of("cards", "nextPageExists", "nextCursor"), names,
                            "the body carries three properties"),
                    () -> assertFalse(names.contains("total"), "no total property"),
                    () -> assertFalse(names.contains("totalElements"),
                            "no total-elements property"),
                    () -> assertFalse(names.contains("page"), "no page property"),
                    () -> assertFalse(names.contains("pageNumber"), "no page-number property"),
                    () -> assertFalse(names.contains("size"), "no size property"));
        }
    }

    /**
     * {@code GET /cards/{cardNumber}}, where the order of the lookup and the mask becomes observable.
     *
     * <p>The route replaces the Customer Information Control System (CICS) transaction
     * {@code CCDL}, which dispatches into {@code COCRDSLC}.
     */
    @Nested
    @DisplayName("reading one card")
    class ReadingOneCard {

        /**
         * A read keyed on the full sixteen-digit Primary Account Number succeeds and answers the
         * masked form.
         *
         * <p>The lookup keys on the full value, as {@code app/cbl/COCRDSLC.cbl:L740} moves it into
         * the read key. A masked key would match no row, so a successful read is what proves the
         * order. {@code app/bms/COCRDSL.bms:L99} gives the screen field {@code LENGTH=16} and
         * {@code app/bms/COCRDSL.bms:L96} gives it {@code ATTRB=(FSET,NORM,UNPROT)}, so the source
         * renders every digit and the mask is additive.
         */
        @Test
        @DisplayName("the lookup runs on the full card number and the answer carries the mask")
        void theLookupRunsOnTheFullCardNumber() {
            HttpResponse<String> answer = readCard(ROW_1_CARD_NUMBER);
            JsonNode body = bodyOf(answer);

            assertAll("the masked answer to a full-number read",
                    () -> assertEquals(200, answer.statusCode(),
                            "the full card number reaches the seeded row"),
                    () -> assertEquals(ROW_1_MASKED_CARD_NUMBER,
                            body.get("maskedCardNumber").asString(),
                            "the answer carries the masked form"),
                    () -> assertTrue(body.get("maskedCardNumber").asString()
                                    .matches(MASKED_CARD_NUMBER_PATTERN),
                            "the masked form is twelve mask characters then four digits"),
                    () -> assertFalse(answer.body().contains(ROW_1_CARD_NUMBER),
                            "the served text carries no full card number"));
        }

        /**
         * The detail body carries five components.
         *
         * <p>{@code app/cpy/CVACT02Y.cpy} declares six fields and a trailing filler. The body drops
         * the card verification value at {@code app/cpy/CVACT02Y.cpy:L7} and the 59-byte
         * {@code FILLER} at {@code app/cpy/CVACT02Y.cpy:L11}. The source spells the expiry field
         * {@code CARD-EXPIRAION-DATE} at {@code app/cpy/CVACT02Y.cpy:L9}, and the target corrects
         * that spelling, so the component reads {@code expirationDate}.
         *
         * <p>The embossed name arrives as column {@code embossed_name} holds it, padded to the
         * fifty characters {@code CARD-EMBOSSED-NAME PIC X(50)} declares.
         */
        @Test
        @DisplayName("the detail body carries five components and no card verification value")
        void theDetailBodyCarriesFiveComponents() {
            JsonNode body = bodyOf(readCard(ROW_1_CARD_NUMBER));
            Set<String> names = propertyNamesOf(body);

            assertAll("the detail body",
                    () -> assertEquals(Set.of("maskedCardNumber", "accountId", "embossedName",
                            "expirationDate", "activeStatus"), names,
                            "the body carries five properties"),
                    () -> assertEquals(ROW_1_ACCOUNT_ID, body.get("accountId").asString(),
                            "the body carries the account identifier"),
                    () -> assertEquals(ROW_1_EMBOSSED_NAME,
                            body.get("embossedName").asString().strip(),
                            "the body carries the embossed name the fixture holds"),
                    () -> assertEquals(ROW_1_EXPIRATION_TEXT,
                            body.get("expirationDate").asString(),
                            "the body carries the expiry the fixture holds"),
                    () -> assertEquals(ACTIVE_STATUS_YES, body.get("activeStatus").asString(),
                            "the body carries the status flag"),
                    () -> assertFalse(names.contains("cardVerificationValue"),
                            "the body carries no card verification value under that name"));
        }

        /**
         * A card number no row holds answers 404 with the text the source declares.
         *
         * <p>{@code 88 DID-NOT-FIND-ACCTCARD-COMBO} at {@code app/cbl/COCRDUPC.cbl:L203/L204}
         * declares the text. The byte-identical twin sits at
         * {@code app/cbl/COCRDSLC.cbl:L153/L154} and is set at {@code app/cbl/COCRDSLC.cbl:L760} on
         * the detail path this route replaces.
         */
        @Test
        @DisplayName("an absent card answers 404 with the text the source declares")
        void anAbsentCardAnswersNotFound() {
            HttpResponse<String> answer = readCard(ABSENT_CARD_NUMBER);

            assertAll("the absent-row answer",
                    () -> assertEquals(404, answer.statusCode(), "an absent row answers 404"),
                    () -> assertEquals("Did not find cards for this search condition",
                            messageOf(answer),
                            "the answer carries the text of the condition name"));
        }

        /**
         * A card number whose Luhn check digit is wrong reaches the lookup and is not refused at
         * the boundary.
         *
         * <p>The 404 is the proof: the value passed every edit and was looked up.
         * {@code app/cbl/COCRDUPC.cbl:L784} tests {@code IF CC-CARD-NUM IS NOT NUMERIC} and nothing
         * else, while the comments at {@code app/cbl/COCRDUPC.cbl:L782-L783} claim a numeric check
         * and a sixteen-character check. No checksum rule exists to add without changing outcomes.
         */
        @Test
        @DisplayName("a wrong check digit reaches the lookup and answers the absent-row arm")
        void aWrongCheckDigitReachesTheLookup() {
            HttpResponse<String> answer = readCard(WRONG_CHECK_DIGIT_CARD_NUMBER);

            assertAll("the answer to a value no checksum admits",
                    () -> assertEquals(404, answer.statusCode(),
                            "the value reached the lookup and matched no row"),
                    () -> assertEquals("Did not find cards for this search condition",
                            messageOf(answer), "the answer carries the absent-row text"));
        }

        /**
         * A card whose status flag reads {@code N} is still returned.
         *
         * <p>No status gate exists anywhere in the migrated path. The posting program never opens
         * the card file: {@code app/jcl/POSTTRAN.jcl} allocates the card cross-reference at
         * {@code app/jcl/POSTTRAN.jcl:L32} and no card file at all. All fifty rows of
         * {@code app/data/ASCII/carddata.txt} carry {@code Y}, so this test sets one row inactive
         * inside the container and the restore returns it.
         */
        @Test
        @DisplayName("an inactive card is still returned by the read route")
        void anInactiveCardIsStillReturned() {
            jdbcTemplate.update("UPDATE card SET active_status = ? WHERE card_number = ?",
                    ACTIVE_STATUS_NO, ROW_1_CARD_NUMBER);

            HttpResponse<String> answer = readCard(ROW_1_CARD_NUMBER);
            JsonNode body = bodyOf(answer);

            assertAll("the read of an inactive card",
                    () -> assertEquals(200, answer.statusCode(), "an inactive card still reads"),
                    () -> assertEquals(ACTIVE_STATUS_NO, body.get("activeStatus").asString(),
                            "the answer carries the inactive flag"));
        }

        /**
         * A log line carries the masked form, and no application log line carries the full card
         * number or the card verification value.
         *
         * <p>{@code CardUpdateService} writes the masked value, and the update path is the one path
         * that logs a card number in any form.
         *
         * @param output the captured console output the extension supplies
         */
        @Test
        @DisplayName("a log line carries the masked form and neither withheld value")
        void theLogLineCarriesTheMaskedFormAlone(CapturedOutput output) {
            updateCard(ROW_1_CARD_NUMBER, "Marisol Reyes", "2028", "11", ROW_1_EXPIRY_DAY,
                    ACTIVE_STATUS_NO);

            List<String> messages = applicationLogMessages(output);

            assertAll("what the log carries",
                    () -> assertFalse(messages.isEmpty(),
                            "the update wrote at least one application log record"),
                    () -> assertTrue(messages.stream().anyMatch(
                                    message -> message.contains(ROW_1_MASKED_CARD_NUMBER)),
                            "one record carries the masked form"),
                    () -> assertTrue(messages.stream()
                                    .noneMatch(message -> message.contains(ROW_1_CARD_NUMBER)),
                            "no record carries the full card number"),
                    () -> assertTrue(messages.stream()
                                    .noneMatch(message -> carriesStandaloneValue(message,
                                            ROW_1_CARD_VERIFICATION_VALUE)),
                            "no record carries the card verification value"));
        }
    }

    /**
     * {@code PUT /cards}, where each of the seven answers of {@code app/cbl/COCRDUPC.cbl} becomes
     * observable.
     *
     * <p>Every text below is a literal typed in this file and every comparison is exact equality. A
     * substring comparison would pass a text missing its final full stop.
     */
    @Nested
    @DisplayName("updating one card")
    class UpdatingOneCard {

        /**
         * A valid update answers 200 and the new values survive a re-read.
         *
         * <p>The expiry travels in three parts, from the redefinition at
         * {@code app/cbl/COCRDUPC.cbl:L115-L123}, and the write path reassembles them at
         * {@code app/cbl/COCRDUPC.cbl:L1467-L1474}. The submitted day is edited and the stored day
         * is written, so this test submits the stored day.
         */
        @Test
        @DisplayName("a valid update answers 200 and the values persist")
        void aValidUpdatePersists() {
            HttpResponse<String> answer = updateCard(ROW_1_CARD_NUMBER, "Marisol Reyes", "2028",
                    "11", ROW_1_EXPIRY_DAY, ACTIVE_STATUS_NO);
            JsonNode body = bodyOf(answer);
            JsonNode reread = bodyOf(readCard(ROW_1_CARD_NUMBER));

            assertAll("the applied update",
                    () -> assertEquals(200, answer.statusCode(), "a rewritten row answers 200"),
                    () -> assertEquals("UPDATED", body.get("outcome").asString(),
                            "the outcome names the rewrite"),
                    () -> assertTrue(body.get("message").isNull(),
                            "a rewritten row carries no text"),
                    () -> assertTrue(body.get("refreshedCard").isNull(),
                            "a rewritten row carries no snapshot"),
                    () -> assertEquals("Marisol Reyes",
                            reread.get("embossedName").asString().strip(),
                            "the re-read carries the new name"),
                    () -> assertEquals("2028-11-09", reread.get("expirationDate").asString(),
                            "the re-read carries the new expiry"),
                    () -> assertEquals(ACTIVE_STATUS_NO, reread.get("activeStatus").asString(),
                            "the re-read carries the new status"));
        }

        /**
         * A month outside one through twelve answers the text of its condition name.
         *
         * <p>{@code 88 VALID-MONTH VALUES 1 THRU 12.} at {@code app/cbl/COCRDUPC.cbl:L95} fixes the
         * range and {@code app/cbl/COCRDUPC.cbl:L197/L198} declares the text.
         */
        @Test
        @DisplayName("a month outside one through twelve answers 422 with the source text")
        void aMonthOutsideTheRangeAnswersItsText() {
            HttpResponse<String> answer = updateCard(ROW_1_CARD_NUMBER, ROW_1_EMBOSSED_NAME,
                    ROW_1_EXPIRY_YEAR, "13", ROW_1_EXPIRY_DAY, ACTIVE_STATUS_YES);

            assertAll("the refused month",
                    () -> assertEquals(422, answer.statusCode(), "a failing edit answers 422"),
                    () -> assertEquals("Card expiry month must be between 1 and 12",
                            messageOf(answer), "the answer carries the month text"));
        }

        /**
         * A year outside nineteen fifty through twenty ninety-nine answers the text of its
         * condition name.
         *
         * <p>{@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at {@code app/cbl/COCRDUPC.cbl:L99} fixes
         * the range and {@code app/cbl/COCRDUPC.cbl:L199/L200} declares the text.
         */
        @Test
        @DisplayName("a year outside the range answers 422 with the source text")
        void aYearOutsideTheRangeAnswersItsText() {
            HttpResponse<String> answer = updateCard(ROW_1_CARD_NUMBER, ROW_1_EMBOSSED_NAME, "1949",
                    ROW_1_EXPIRY_MONTH, ROW_1_EXPIRY_DAY, ACTIVE_STATUS_YES);

            assertAll("the refused year",
                    () -> assertEquals(422, answer.statusCode(), "a failing edit answers 422"),
                    () -> assertEquals("Invalid card expiry year", messageOf(answer),
                            "the answer carries the year text"));
        }

        /**
         * A status flag holding neither value answers the text of its condition name.
         *
         * <p>{@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91}
         * admits two values and {@code app/cbl/COCRDUPC.cbl:L195/L196} declares the text.
         */
        @Test
        @DisplayName("a status other than Y or N answers 422 with the source text")
        void aStatusOutsideTheTwoValuesAnswersItsText() {
            HttpResponse<String> answer = updateCard(ROW_1_CARD_NUMBER, ROW_1_EMBOSSED_NAME,
                    ROW_1_EXPIRY_YEAR, ROW_1_EXPIRY_MONTH, ROW_1_EXPIRY_DAY, "X");

            assertAll("the refused status",
                    () -> assertEquals(422, answer.statusCode(), "a failing edit answers 422"),
                    () -> assertEquals("Card Active Status must be Y or N", messageOf(answer),
                            "the answer carries the status text"));
        }

        /**
         * A name carrying a digit answers the text of its condition name.
         *
         * <p>{@code 88 WS-NAME-MUST-BE-ALPHA} at {@code app/cbl/COCRDUPC.cbl:L183/L184} declares
         * the text, and the source sets it at {@code app/cbl/COCRDUPC.cbl:L834}.
         */
        @Test
        @DisplayName("a name carrying a digit answers 422 with the source text")
        void aNameCarryingADigitAnswersItsText() {
            HttpResponse<String> answer = updateCard(ROW_1_CARD_NUMBER, "Aniya V0n",
                    ROW_1_EXPIRY_YEAR, ROW_1_EXPIRY_MONTH, ROW_1_EXPIRY_DAY, ACTIVE_STATUS_YES);

            assertAll("the refused name",
                    () -> assertEquals(422, answer.statusCode(), "a failing edit answers 422"),
                    () -> assertEquals("Card name can only contain alphabets and spaces",
                            messageOf(answer), "the answer carries the name text"));
        }

        /**
         * Two failing edits answer the first one alone.
         *
         * <p>The reset at {@code app/cbl/COCRDUPC.cbl:L384} clears the text field, and fifteen
         * {@code IF WS-RETURN-MSG-OFF} guards then admit only the first setter. The name edit
         * precedes the month edit, so the name text is the answer.
         */
        @Test
        @DisplayName("two failing edits answer the first text alone")
        void theFirstFailingEditOwnsTheAnswer() {
            HttpResponse<String> answer = updateCard(ROW_1_CARD_NUMBER, "Aniya V0n",
                    ROW_1_EXPIRY_YEAR, "13", ROW_1_EXPIRY_DAY, ACTIVE_STATUS_YES);

            assertAll("the first failing edit",
                    () -> assertEquals(422, answer.statusCode(), "a failing edit answers 422"),
                    () -> assertEquals("Card name can only contain alphabets and spaces",
                            messageOf(answer), "the name text wins over the month text"));
        }

        /**
         * Submitting the stored values answers the no-change text.
         *
         * <p>The comparison at {@code app/cbl/COCRDUPC.cbl:L680-L682} writes nothing, and
         * {@code app/cbl/COCRDUPC.cbl:L692} leaves the paragraph for the exit label at
         * {@code app/cbl/COCRDUPC.cbl:L717}, carrying the text
         * {@code app/cbl/COCRDUPC.cbl:L188} declares. The trailing full stop belongs to the text.
         */
        @Test
        @DisplayName("submitting the stored values answers 422 with the no-change text")
        void submittingTheStoredValuesAnswersTheNoChangeText() {
            HttpResponse<String> answer = updateCard(ROW_1_CARD_NUMBER, ROW_1_EMBOSSED_NAME,
                    ROW_1_EXPIRY_YEAR, ROW_1_EXPIRY_MONTH, ROW_1_EXPIRY_DAY, ACTIVE_STATUS_YES);

            assertAll("the unchanged submission",
                    () -> assertEquals(422, answer.statusCode(), "no change answers 422"),
                    () -> assertEquals("No change detected with respect to values fetched.",
                            messageOf(answer), "the answer carries the no-change text"));
        }

        /**
         * A card number no row holds answers 404 with the absent-row text.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L203/L204} declares the text, and the source sets it at
         * {@code app/cbl/COCRDUPC.cbl:L1400}.
         */
        @Test
        @DisplayName("an absent card answers 404 with the source text")
        void anAbsentCardAnswersNotFound() {
            HttpResponse<String> answer = updateCard(ABSENT_CARD_NUMBER, "Marisol Reyes", "2028",
                    "11", "09", ACTIVE_STATUS_YES);

            assertAll("the absent-row answer",
                    () -> assertEquals(404, answer.statusCode(), "an absent row answers 404"),
                    () -> assertEquals("Did not find cards for this search condition",
                            messageOf(answer), "the answer carries the absent-row text"));
        }

        /**
         * A row another writer changed first answers 409 with a five-component snapshot.
         *
         * <p>{@code 9300-CHECK-CHANGE-IN-REC} at {@code app/cbl/COCRDUPC.cbl:L1498} compares six
         * fields at {@code app/cbl/COCRDUPC.cbl:L1503-L1508}, the card verification value first at
         * {@code app/cbl/COCRDUPC.cbl:L1503}, then sets the condition at
         * {@code app/cbl/COCRDUPC.cbl:L1511} and refreshes all six at
         * {@code app/cbl/COCRDUPC.cbl:L1512-L1517}. The body here carries five components. The
         * holder changes the status flag rather than the letter case of the name, because
         * {@code app/cbl/COCRDUPC.cbl:L1499-L1501} folds the name to upper case before comparing.
         *
         * @throws Exception when the holding connection or the request fails
         */
        @Test
        @DisplayName("a concurrent change answers 409 with a five-component snapshot")
        void aConcurrentChangeAnswersConflict() throws Exception {
            HttpResponse<String> answer = answerAfterTheHolderCommits(ROW_1_CARD_NUMBER,
                    "UPDATE card SET active_status = 'N' WHERE card_number = ?",
                    () -> updateCard(ROW_1_CARD_NUMBER, "Marisol Reyes", ROW_1_EXPIRY_YEAR,
                            ROW_1_EXPIRY_MONTH, ROW_1_EXPIRY_DAY, ACTIVE_STATUS_YES));
            JsonNode body = bodyOf(answer);
            JsonNode snapshot = body.get("refreshedCard");

            assertAll("the concurrent-change answer",
                    () -> assertEquals(409, answer.statusCode(), "a lost race answers 409"),
                    () -> assertEquals("Record changed by some one else. Please review",
                            messageOf(answer), "the answer carries the concurrent-change text"),
                    () -> assertEquals("CHANGED_BEFORE_UPDATE", body.get("outcome").asString(),
                            "the outcome names the lost race"),
                    () -> assertFalse(snapshot.isNull(), "the answer carries a snapshot"),
                    () -> assertEquals(Set.of("embossedName", "expiryYear", "expiryMonth",
                            "expiryDay", "activeStatus"), propertyNamesOf(snapshot),
                            "the snapshot carries five components"),
                    () -> assertEquals(ACTIVE_STATUS_NO, snapshot.get("activeStatus").asString(),
                            "the snapshot carries the status the other writer left"),
                    () -> assertFalse(answer.body().contains(ROW_1_CARD_VERIFICATION_VALUE),
                            "the served text carries no card verification value"));
        }

        /**
         * A row that vanished before the lock answers 409 with the lock text and no snapshot.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1441} tests the response of the locking read and
         * {@code app/cbl/COCRDUPC.cbl:L1443} takes the other branch. The guard at
         * {@code app/cbl/COCRDUPC.cbl:L1445} admits the setter at
         * {@code app/cbl/COCRDUPC.cbl:L1446}, and {@code app/cbl/COCRDUPC.cbl:L1448} leaves the
         * paragraph before the comparison the {@code PERFORM} at
         * {@code app/cbl/COCRDUPC.cbl:L1453} would run. The row this test holds is one it inserted.
         *
         * @throws Exception when the holding connection or the request fails
         */
        @Test
        @DisplayName("a failed lock answers 409 with the lock text and no snapshot")
        void aFailedLockAnswersConflictWithoutASnapshot() throws Exception {
            insertCard(VANISHING_CARD_NUMBER, ROW_1_ACCOUNT_ID);

            HttpResponse<String> answer = answerAfterTheHolderCommits(VANISHING_CARD_NUMBER,
                    "DELETE FROM card WHERE card_number = ?",
                    () -> updateCard(VANISHING_CARD_NUMBER, "Second Name", "2028", "12", "31",
                            ACTIVE_STATUS_YES));
            JsonNode body = bodyOf(answer);

            assertAll("the failed-lock answer",
                    () -> assertEquals(409, answer.statusCode(), "a failed lock answers 409"),
                    () -> assertEquals("Could not lock record for update", messageOf(answer),
                            "the answer carries the lock text"),
                    () -> assertEquals("LOCK_NOT_ACQUIRED", body.get("outcome").asString(),
                            "the outcome names the failed lock"),
                    () -> assertTrue(body.get("refreshedCard").isNull(),
                            "a failed lock reaches no comparison and carries no snapshot"));
        }

        /**
         * A write that fails once the row is held answers 500 with the failed-write text, and the
         * row is left as it stood.
         *
         * <p>{@code app/cbl/COCRDUPC.cbl:L1490} takes the branch that sets
         * {@code LOCKED-BUT-UPDATE-FAILED} at {@code app/cbl/COCRDUPC.cbl:L1491}, under no guard,
         * and {@code app/cbl/COCRDUPC.cbl:L209/L210} declares the text. This test withholds the
         * event table for one request, so the write inside the held transaction fails.
         *
         * <p>The answer carries the failure body rather than the outcome body, so one status on one
         * operation carries one schema. {@code src/main/resources/openapi.yaml} documents the same
         * status, the same shape and the same text.
         */
        @Test
        @DisplayName("a failed write after the lock answers 503 and rolls the row back")
        void aFailedWriteAfterTheLockAnswersServiceUnavailable() {
            HttpResponse<String> answer;
            jdbcTemplate.execute("ALTER TABLE outbox_event RENAME TO outbox_event_withheld");
            try {
                answer = updateCard(ROW_1_CARD_NUMBER, "Marisol Reyes", "2028", "11",
                        ROW_1_EXPIRY_DAY, ACTIVE_STATUS_NO);
            } finally {
                jdbcTemplate.execute("ALTER TABLE outbox_event_withheld RENAME TO outbox_event");
            }
            JsonNode body = bodyOf(answer);

            assertAll("the failed-write answer",
                    () -> assertEquals(503, answer.statusCode(),
                            "a write the datastore refused is retryable rather than a defect"),
                    () -> assertEquals("Update of record failed", messageOf(answer),
                            "the answer carries the failed-write text"),
                    () -> assertEquals(503, body.get("status").asInt(),
                            "the failure body reports the status it was sent with"),
                    () -> assertEquals("/cards/{cardNumber}", body.get("route").asString(),
                            "the failure body carries the route template and no resolved path"),
                    () -> assertEquals(ROW_1_EMBOSSED_NAME,
                            storedValueOf("embossed_name", ROW_1_CARD_NUMBER),
                            "the row still carries the name it had"));
        }

        /**
         * An error body carries one text and no collection of field errors.
         *
         * <p>The whole reporting device of the source is one fixed-width field,
         * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}, corroborated by
         * {@code CCARD-RETURN-MSG PIC X(75)} at {@code app/cpy/CVCRD01Y.cpy:L29}. Every text this
         * class asserts fits inside seventy-five characters.
         */
        @Test
        @DisplayName("an error body carries one text and no field-error collection")
        void anErrorBodyCarriesOneText() {
            Set<String> updateNames =
                    propertyNamesOf(bodyOf(updateCard(ABSENT_CARD_NUMBER, "Marisol Reyes", "2028",
                            "11", "09", ACTIVE_STATUS_YES)));
            Set<String> readNames =
                    propertyNamesOf(bodyOf(readCard(ABSENT_CARD_NUMBER)));

            assertAll("the two failing bodies",
                    () -> assertEquals(Set.of("outcome", "message", "refreshedCard"), updateNames,
                            "the update body carries three properties, all three serialized"),
                    () -> assertEquals(Set.of("status", "message", "route"), readNames,
                            "the read body carries three properties"),
                    () -> assertFalse(readNames.contains("errors"), "no error collection"),
                    () -> assertFalse(readNames.contains("fieldErrors"),
                            "no per-field error collection"),
                    () -> assertFalse(readNames.contains("violations"), "no violation list"));
        }
    }

    /**
     * The card verification value, which the column holds and no answer carries.
     *
     * <p>Each assertion reads the served text rather than one component, so a component added later
     * cannot carry the value past this class. Each body holds one record: the read answer, the
     * update answer, and the list answer for an account owning one card.
     */
    @Nested
    @DisplayName("what the card verification value never reaches")
    class WhatNeverLeaves {

        /**
         * No single-record body carries the card verification value.
         *
         * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} declares the field,
         * and {@code app/cbl/COCRDUPC.cbl:L1464-L1465} carries it into the source update record.
         * The column holds the value, which the first assertion states, so the three that follow
         * cannot pass against an empty column.
         */
        @Test
        @DisplayName("neither the read, the update nor a one-row list carries the value")
        void noSingleRecordBodyCarriesTheValue() {
            String detail = readCard(ROW_1_CARD_NUMBER).body();
            String update = updateCard(ROW_1_CARD_NUMBER, "Marisol Reyes", "2028", "11",
                    ROW_1_EXPIRY_DAY, ACTIVE_STATUS_NO).body();
            String list = listCards(ROW_1_ACCOUNT_ID, null).body();

            assertAll("the three served bodies",
                    () -> assertEquals(ROW_1_CARD_VERIFICATION_VALUE,
                            storedValueOf("card_verification_value", ROW_1_CARD_NUMBER),
                            "the column holds the value these assertions look for"),
                    () -> assertFalse(detail.contains(ROW_1_CARD_VERIFICATION_VALUE),
                            "the read answer carries no card verification value"),
                    () -> assertFalse(update.contains(ROW_1_CARD_VERIFICATION_VALUE),
                            "the update answer carries no card verification value"),
                    () -> assertFalse(list.contains(ROW_1_CARD_VERIFICATION_VALUE),
                            "the one-row list answer carries no card verification value"));
        }
    }

    /**
     * The event row a state change writes, and the reads that write none.
     *
     * <p>The row is the observable this class owns. The transactional outbox is the target form of
     * the one asynchronous handoff the source has, the queue write at
     * {@code app/cbl/CORPT00C.cbl:L517-L518}.
     */
    @Nested
    @DisplayName("the event row a state change writes")
    class ThePublishedEvent {

        /**
         * A rewritten row produces one event row, keyed on the account identifier.
         *
         * <p>Column {@code aggregate_id} records the eleven digits
         * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} declares, so all events
         * of one account keep one order.
         */
        @Test
        @DisplayName("a rewritten row produces exactly one event row")
        void aRewrittenRowProducesOneEventRow() {
            HttpResponse<String> answer = updateCard(ROW_1_CARD_NUMBER, "Marisol Reyes", "2028",
                    "11", ROW_1_EXPIRY_DAY, ACTIVE_STATUS_NO);

            Integer total =
                    jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
            String eventType = jdbcTemplate.queryForObject(
                    "SELECT event_type FROM outbox_event WHERE aggregate_id = ?", String.class,
                    ROW_1_ACCOUNT_ID);

            assertAll("the one event row",
                    () -> assertEquals(200, answer.statusCode(), "the update applied"),
                    () -> assertEquals(1, outboxRowCountFor(ROW_1_ACCOUNT_ID),
                            "the account carries one event row"),
                    () -> assertEquals(1, total, "the table carries one row in total"),
                    () -> assertEquals("CardUpdated", eventType,
                            "the row names the card-update event"));
        }

        /**
         * A list and a read produce no event row.
         *
         * <p>This service publishes on a state change and on nothing else.
         */
        @Test
        @DisplayName("a list and a read produce no event row")
        void readsProduceNoEventRow() {
            HttpResponse<String> list = listCards(ROW_1_ACCOUNT_ID, null);
            HttpResponse<String> read = readCard(ROW_1_CARD_NUMBER);

            Integer total =
                    jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);

            assertAll("the two reads",
                    () -> assertEquals(200, list.statusCode(), "the list answered"),
                    () -> assertEquals(200, read.statusCode(), "the read answered"),
                    () -> assertEquals(0, total, "no read wrote an event row"),
                    () -> assertEquals(0, outboxRowCountFor(ROW_1_ACCOUNT_ID),
                            "the account carries no event row"));
        }
    }

    /**
     * The resources this service deliberately does not expose.
     *
     * <p>An absence nothing asserts is an absence somebody adds back.
     */
    @Nested
    @DisplayName("the resources this service does not expose")
    class AbsencesTheSourceEstablishes {

        /**
         * The one route in the table this service did not declare.
         *
         * <p>The framework's error controller registers it, under no named method, so it answers
         * every method. It is named here so the inventory comparison stays exact.
         */
        private static final String FRAMEWORK_ERROR_ROUTE = "ANY /error";

        /**
         * No handler is mapped to a card-status path, under any method.
         *
         * <p>The posting program never opens the card file. {@code app/jcl/POSTTRAN.jcl} runs to 45
         * lines and runs the program in {@code STEP15} at {@code app/jcl/POSTTRAN.jcl:L23}. It
         * allocates six datasets at {@code app/jcl/POSTTRAN.jcl:L28}, {@code L30}, {@code L32},
         * {@code L34}, {@code L39} and {@code L41}, where {@code L32} names the card
         * cross-reference and none of them is the card master file. No source program reads the
         * active status before it posts, so no route here may offer it.
         *
         * <p>The claim is read from the route table the running application built. It was read from
         * a refusal instead, and a refusal cannot carry it: the default-deny rule of
         * {@code config/SecurityConfig} answers 403 before a route is matched, so every unmapped
         * path answers exactly as this one did and the assertion held whether or not a handler
         * existed. Adding a card-status handler would have left it passing.
         *
         * <p>Every registered pattern is matched against the concrete path with the same parser the
         * dispatcher uses, so a handler mapped as a literal, as a template or under a wildcard is
         * caught alike.
         */
        @Test
        @DisplayName("no handler is mapped to a card-status path, under any method")
        void noHandlerIsMappedToACardStatusPath() {
            String statusPath = CardController.BASE_PATH + "/" + ROW_1_CARD_NUMBER + "/status";
            PathPatternParser parser = new PathPatternParser();
            PathContainer requested = PathContainer.parsePath(statusPath);

            Set<String> matching = declaredPatterns().stream()
                    .filter(pattern -> parser.parse(pattern).matches(requested))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

            assertAll("the route table",
                    () -> assertTrue(matching.isEmpty(),
                            statusPath + " is carried by " + matching),
                    () -> assertFalse(declaredPatterns().isEmpty(),
                            "the route table was read as empty, so nothing was actually inspected:"
                                    + " the absence above would hold for every path"),
                    () -> assertTrue(declaredPatterns().stream()
                                    .noneMatch(pattern -> pattern.toLowerCase(Locale.ROOT)
                                            .contains("status")),
                            "a route names status: " + declaredPatterns()));
        }

        /**
         * The whole route inventory is the three operations this service declares, and the one route
         * the framework contributes.
         *
         * <p>This is what makes the absence above complete rather than one spot check. A fourth
         * business route of any shape fails here, so no resource can be added to this service
         * without a decision being recorded against this assertion, whatever it is named.
         *
         * <p>{@code ANY /error} is the container's error dispatch, registered by the framework's own
         * error controller rather than by this service. It is listed instead of filtered out, so the
         * comparison stays a comparison of the complete table: a filter wide enough to drop it would
         * be wide enough to drop a route somebody added. {@code config/SecurityConfig} permits the
         * error dispatch for the same reason, which is what lets an unmatched path answer 404 rather
         * than 403.
         */
        @Test
        @DisplayName("the route inventory is the three declared operations and the error dispatch")
        void theRouteInventoryIsTheThreeDeclaredOperationsAndTheErrorDispatch() {
            Set<String> expected = new TreeSet<>(Set.of(
                    "GET " + CardController.COLLECTION_ROUTE,
                    "GET " + CardController.CARD_ROUTE,
                    "PUT " + CardController.CARD_ROUTE,
                    FRAMEWORK_ERROR_ROUTE));

            assertEquals(expected, declaredRoutes(), "the declared route inventory");
        }

        /**
         * No business route beyond the three carries a handler, whatever the framework contributes.
         *
         * <p>Stated separately from the inventory above so that the claim survives a framework
         * upgrade that adds or renames an infrastructure route: the inventory would then need
         * updating, and this would still refuse a fourth route under this service's own base path.
         */
        @Test
        @DisplayName("no fourth route sits under this service's base path")
        void noFourthRouteSitsUnderThisServicesBasePath() {
            Set<String> underBasePath = declaredRoutes().stream()
                    .filter(route -> route.contains(" " + CardController.BASE_PATH))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

            assertEquals(3, underBasePath.size(),
                    "routes under " + CardController.BASE_PATH + ": " + underBasePath);
        }

        /**
         * The chain also refuses the absent path, which corroborates the absence without proving it.
         *
         * <p>Kept as a separate statement about the chain rather than about the route table. On its
         * own it says only that nothing reachable answers there, which is true of every path this
         * service does not declare.
         */
        @Test
        @DisplayName("the chain also refuses the absent path")
        void theChainAlsoRefusesTheAbsentPath() {
            HttpResponse<String> answer =
                    send(authorized("/cards/" + ROW_1_CARD_NUMBER + "/status").GET().build());

            assertEquals(403, answer.statusCode(),
                    "an authenticated caller reaches nothing on an undeclared route");
        }

        /**
         * Reads every path pattern the running application registered for an annotated handler.
         *
         * @return the patterns, ordered
         */
        private Set<String> declaredPatterns() {
            return handlerMappings.getHandlerMethods().keySet().stream()
                    .map(RequestMappingInfo::getPathPatternsCondition)
                    .filter(Objects::nonNull)
                    .flatMap(condition -> condition.getPatternValues().stream())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }

        /**
         * Reads every method-and-pattern pair the running application registered.
         *
         * <p>A mapping that names no method would answer every method, so it is reported as
         * {@code ANY} rather than dropped.
         *
         * @return the pairs, ordered
         */
        private Set<String> declaredRoutes() {
            Set<String> routes = new TreeSet<>();
            for (RequestMappingInfo info : handlerMappings.getHandlerMethods().keySet()) {
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                Set<String> names = methods.isEmpty() ? Set.of("ANY")
                        : methods.stream().map(RequestMethod::name)
                                .collect(java.util.stream.Collectors.toSet());
                if (info.getPathPatternsCondition() == null) {
                    continue;
                }
                for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                    for (String name : names) {
                        routes.add(name + " " + pattern);
                    }
                }
            }
            return routes;
        }
    }

    /**
     * Lists one account's cards, optionally continuing from a paging position.
     *
     * @param accountId the eleven-digit account to list
     * @param cursor    the position a previous answer handed back, or {@code null} for a first page
     * @return the answer, body included as served text
     */
    private HttpResponse<String> listCards(String accountId, String cursor) {
        HttpRequest.Builder request = authorized("/cards?accountId=" + accountId).GET();
        if (cursor != null) {
            request.header("X-Card-Cursor", cursor);
        }
        return send(request.build());
    }

    /**
     * Reads one card by its full card number, which the path carries.
     *
     * @param cardNumber the full sixteen-digit card number
     * @return the answer, body included as served text
     */
    private HttpResponse<String> readCard(String cardNumber) {
        return send(authorized("/cards/" + cardNumber).GET().build());
    }

    /**
     * Submits one card update, with the expiry in the three parts
     * {@code app/cbl/COCRDUPC.cbl:L115-L123} declares.
     *
     * @param cardNumber   the full sixteen-digit card number
     * @param embossedName the cardholder name to write
     * @param expiryYear   four characters
     * @param expiryMonth  two characters
     * @param expiryDay    two characters
     * @param activeStatus one character
     * @return the answer, body included as served text
     */
    private HttpResponse<String> updateCard(String cardNumber, String embossedName,
            String expiryYear, String expiryMonth, String expiryDay, String activeStatus) {
        String body = "{\"embossedName\":\"" + embossedName + "\""
                + ",\"expiryYear\":\"" + expiryYear + "\""
                + ",\"expiryMonth\":\"" + expiryMonth + "\""
                + ",\"expiryDay\":\"" + expiryDay + "\""
                + ",\"activeStatus\":\"" + activeStatus + "\"}";
        return send(authorized("/cards/" + cardNumber)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    /**
     * Starts a request builder carrying the administrator credential and the timeout.
     *
     * <p>The administrator identity passes every ownership check by role, from the fork at
     * {@code app/cbl/COSGN00C.cbl:L230-L240}.
     *
     * @param pathAndQuery the path, and the query string when one applies
     * @return the builder
     */
    private HttpRequest.Builder authorized(String pathAndQuery) {
        String credential = ADMIN_USERNAME + ":" + ADMIN_PASSWORD;
        String encoded = Base64.getEncoder()
                .encodeToString(credential.getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .header("Authorization", "Basic " + encoded)
                .header("Accept", "application/json")
                // config/CrossSiteRequestFilter requires this on every state-changing request. A
                // first-party client sets it on all of them, and an HTML form can set no header at
                // all, which is what separates the two.
                .header(CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER, "1")
                .timeout(REQUEST_TIMEOUT);
    }

    /**
     * Sends one request and returns the answer with its body as text.
     *
     * @param request the request to send
     * @return the answer
     * @throws IllegalStateException when the client cannot complete the exchange
     */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the request was interrupted", interrupted);
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("the request did not complete", failed);
        }
    }

    /**
     * Parses one served body.
     *
     * @param response the answer whose body to read
     * @return the parsed body
     */
    private static JsonNode bodyOf(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    /**
     * Returns the property names one object carries, in the order it carries them.
     *
     * @param node the object to inspect
     * @return the property names
     */
    private static Set<String> propertyNamesOf(JsonNode node) {
        return new LinkedHashSet<>(node.propertyNames());
    }

    /**
     * Reads the one text a failing body carries.
     *
     * @param response the answer to read
     * @return the text of the {@code message} property
     */
    private static String messageOf(HttpResponse<String> response) {
        return bodyOf(response).get("message").asString();
    }

    /**
     * Inserts one card row on one account, deriving the card token the column requires.
     *
     * @param cardNumber the full sixteen-digit card number
     * @param accountId  the eleven-digit account identifier
     */
    private void insertCard(String cardNumber, String accountId) {
        cardRepository.save(new CardEntity(cardNumber, accountId,
                SYNTHETIC_CARD_VERIFICATION_VALUE, SYNTHETIC_EMBOSSED_NAME,
                SYNTHETIC_EXPIRATION_DATE, ACTIVE_STATUS_YES));
    }

    /**
     * Reads one column of one card row directly, outside every request.
     *
     * @param column     the column to read
     * @param cardNumber the row to read it from
     * @return the stored value as text, with the padding of a fixed-width column removed
     */
    private String storedValueOf(String column, String cardNumber) {
        String value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM card WHERE card_number = ?", String.class, cardNumber);
        return value == null ? null : value.strip();
    }

    /**
     * Counts the event rows one account carries.
     *
     * @param accountId the eleven-digit account, which column {@code aggregate_id} records
     * @return the row count
     */
    private int outboxRowCountFor(String accountId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class,
                accountId);
        return count == null ? 0 : count;
    }

    /**
     * Counts the lock requests the server is holding unmet.
     *
     * <p>A request whose locked read waits on a row another transaction holds appears here as one
     * ungranted lock. The count is what tells this class that the unlocked read has already run and
     * the locked read has not returned.
     *
     * @return the number of unmet lock requests
     */
    private int unmetLockRequestCount() {
        Integer count = jdbcTemplate.queryForObject(UNMET_LOCK_REQUEST_COUNT_SQL, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Runs one request against a row a second connection holds locked, changes the row from that
     * connection once the request is waiting, then returns the answer.
     *
     * <p>The sequence reaches the two answers of {@code 9200-WRITE-PROCESSING} without a timing
     * guess. A second connection takes the row lock the source takes with {@code READ} carrying
     * {@code UPDATE} at {@code app/cbl/COCRDUPC.cbl:L1427-L1436}. Every one of the eight
     * {@code DEFINE FILE} blocks in {@code app/csd/CARDDEMO.CSD} carries
     * {@code UPDATEMODEL(LOCKING)} and {@code READINTEG(UNCOMMITTED)}. The waiting read then
     * returns the row as the holder left it, so a committed change reaches
     * {@code 9300-CHECK-CHANGE-IN-REC} at {@code app/cbl/COCRDUPC.cbl:L1498} and a committed delete
     * reaches the guard at {@code app/cbl/COCRDUPC.cbl:L1445}.
     *
     * @param cardNumber the row to hold, bound into both statements
     * @param statement  the statement the holder runs before it commits, taking the card number as
     *                   its one parameter
     * @param request    the request to run while the holder holds the row
     * @return the answer the request produced
     * @throws Exception when the connection, the statement or the request fails
     */
    private HttpResponse<String> answerAfterTheHolderCommits(String cardNumber, String statement,
            java.util.concurrent.Callable<HttpResponse<String>> request) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (PreparedStatement lock = holder.prepareStatement(LOCK_ONE_CARD_SQL)) {
                    lock.setString(1, cardNumber);
                    lock.executeQuery().close();
                }
                int unmetBefore = unmetLockRequestCount();
                Future<HttpResponse<String>> pending = worker.submit(request);
                Awaitility.await("the request waits on the row this connection holds")
                        .atMost(LOCK_WAIT_TIMEOUT)
                        .until(() -> pending.isDone()
                                || unmetLockRequestCount() > unmetBefore);
                try (PreparedStatement change = holder.prepareStatement(statement)) {
                    change.setString(1, cardNumber);
                    change.executeUpdate();
                }
                holder.commit();
                return pending.get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } finally {
                holder.rollback();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * Returns the message text of every captured log record one application logger emitted.
     *
     * <p>{@code logging.structured.format.console: logstash} in
     * {@code src/main/resources/application.yml} makes each line one object carrying
     * {@code logger_name} and {@code message}. Reading the message alone keeps a timestamp and a
     * thread name out of every assertion below.
     *
     * @param output the captured console output
     * @return the message texts, in the order they were written
     */
    private static List<String> applicationLogMessages(CapturedOutput output) {
        List<String> messages = new ArrayList<>();
        for (String line : output.getAll().split("\n")) {
            String candidate = line.strip();
            if (candidate.isEmpty() || candidate.charAt(0) != '{') {
                continue;
            }
            JsonNode record;
            try {
                record = JSON.readTree(candidate);
            } catch (RuntimeException notOneRecord) {
                continue;
            }
            JsonNode logger = record.get("logger_name");
            JsonNode message = record.get("message");
            if (logger == null || message == null) {
                continue;
            }
            if (logger.asString().startsWith(APPLICATION_LOGGER_PREFIX)) {
                messages.add(message.asString());
            }
        }
        return messages;
    }

    /**
     * Reports whether one text carries a value that is not part of a longer hexadecimal run.
     *
     * <p>A three-digit card verification value inside an event identifier is a coincidence of
     * hexadecimal digits. A three-digit value standing on its own is the value itself. Excluding a
     * hexadecimal neighbour on either side separates the two.
     *
     * @param text  the text to search
     * @param value the value to look for
     * @return {@code true} when the value stands on its own somewhere in the text
     */
    private static boolean carriesStandaloneValue(String text, String value) {
        int at = text.indexOf(value);
        while (at >= 0) {
            boolean hexadecimalBefore = at > 0 && Character.digit(text.charAt(at - 1), 16) >= 0;
            int after = at + value.length();
            boolean hexadecimalAfter =
                    after < text.length() && Character.digit(text.charAt(after), 16) >= 0;
            if (!hexadecimalBefore && !hexadecimalAfter) {
                return true;
            }
            at = text.indexOf(value, at + 1);
        }
        return false;
    }
}
