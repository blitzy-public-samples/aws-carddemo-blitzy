package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.account.AccountServiceDatabase;
import com.carddemo.account.TestIdentityPasswords;
import com.carddemo.account.config.CrossSiteRequestFilter;
import com.carddemo.cobol.PicClause;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full-stack test of the account service over a PostgreSQL container. Nothing is stubbed. The
 * Flyway migrations, the seeded rows, the security chain, the controllers, the domain services, the
 * persistence layer and the outbox writer are all the real ones, and every request travels over a
 * real port through the Java Development Kit client.
 *
 * <p><b>What reaches this class and no other in the module.</b> The container-backed classes under
 * {@code com.carddemo.account.repository}, {@code .domain} and {@code .outbox} extend
 * {@code AbstractAccountPostgresTest}, which starts the context with no web environment: they call
 * beans directly. The classes under {@code com.carddemo.account.api} drive the controllers with
 * stubbed collaborators and no server. Neither shape exercises what a caller actually meets — the
 * filter chain that answers 401 and 403, the serialized body, and the row the request leaves
 * committed in the database. That is what this class covers, and it is the module's Failsafe suite:
 * {@code .github/workflows/ci.yml} names this module in its integration stage, and without an
 * {@code *IT} class the stage produced no {@code failsafe-reports} directory for it.
 *
 * <p><b>The three answers asserted here.</b>
 *
 * <p>The account read reproduces {@code app/cbl/COACTVWC.cbl}, which resolves an account and
 * answers its fields. {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} is numeric
 * display, so row 1 of {@code app/data/ASCII/acctdata.txt} holds {@code 00000000001} and the
 * response carries text that keeps those ten leading zeros. Every monetary component is
 * {@code PIC S9(10)V99} — the balance at {@code app/cpy/CVACT01Y.cpy:L7}, the two limits at
 * {@code :L8} and {@code :L9}, the two cycle accumulators at {@code :L13} and {@code :L14} — and
 * each serializes as a string carrying exactly two fractional digits, never as a JSON number.
 *
 * <p>The cycle close reproduces two statements of {@code 1050-UPDATE-ACCOUNT} at
 * {@code app/cbl/CBACT04C.cbl:L350}: {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at
 * {@code app/cbl/CBACT04C.cbl:L353} and {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at
 * {@code app/cbl/CBACT04C.cbl:L354}. It reproduces neither {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}
 * at {@code app/cbl/CBACT04C.cbl:L352} nor any interest computation. The operation exists because
 * the credit-limit rule of the authorization service reads both accumulators:
 * {@code app/cbl/CBTRN02C.cbl:L403-L405} computes a working balance from them and
 * {@code app/cbl/CBTRN02C.cbl:L407} compares it with {@code ACCT-CREDIT-LIMIT}, so without a reset
 * owner the available credit shrinks until every request meets reject reason 102,
 * {@code OVERLIMIT TRANSACTION}, at {@code app/cbl/CBTRN02C.cbl:L410-L412}.
 *
 * <p>The account update reproduces {@code app/cbl/COACTUPC.cbl}, whose paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC} at {@code app/cbl/COACTUPC.cbl:L4109-L4193} compares the stored
 * pair field by field before rewriting, and whose two rewrites at
 * {@code app/cbl/COACTUPC.cbl:L4066} and {@code app/cbl/COACTUPC.cbl:L4086} ran with recovery
 * disabled on every file definition at {@code app/csd/CARDDEMO.CSD:L3-L9}. Here both rows and the
 * event row commit in one database transaction.
 *
 * <p><b>The atomicity this class reads back.</b> A mutation writes one {@code outbox_event} row
 * inside the transaction that wrote the account row, and the relay publishes it afterwards. The
 * relay sweep is pushed an hour out by a property below, so a row this class asserts on is still
 * unpublished when it reads it, and no test here needs a broker. The broker address points where
 * nothing listens for the same reason.
 *
 * <p><b>State.</b> No method and no nested class carries {@code Transactional}: a test transaction
 * would roll back the writes these tests prove. {@link #restoreSeededState()} returns row 1 of
 * {@code account} and {@code customer} to their seeded values and empties {@code outbox_event}, so
 * each test starts from the migrated seed. Nothing under {@code app/} is read or written by this
 * class; the seeded values below are the ones {@code V2__seed.sql} carries.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "spring.kafka.bootstrap-servers=" + AccountControllerIT.UNREACHABLE_BROKER,
                "spring.kafka.listener.auto-startup=false",
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000"
        })
@DisplayName("The account read, customer read, update and cycle-close surface over the migrated schema")
class AccountControllerIT {

    /** The header every state-changing call of this service has to carry. */
    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    /**
     * The only media type a state-changing route of this service accepts.
     *
     * <p>Required on the cycle close even though it sends no body. That is the cross-site request
     * forgery control: {@code application/json} is not one of the three content types a browser can
     * send cross-origin without a preflight, so requiring it forces one, and this service answers no
     * preflight.
     */
    private static final String JSON_MEDIA_TYPE = "application/json";

    /** A content type a browser can send cross-origin with no preflight, which is the forged shape. */
    private static final String FORM_MEDIA_TYPE = "application/x-www-form-urlencoded";

    /** Host and port the broker client is pointed at, where nothing listens. */
    static final String UNREACHABLE_BROKER = "localhost:1";

    /** Login name of the administrator identity, from {@code ADMIN_USERNAME} in the same file. */
    private static final String ADMIN_USERNAME = "admin001";

    /**
     * Password of the administrator identity, plainly synthetic and matching no live credential.
     * The property above configures its bcrypt hash, and this value is what a request presents.
     */
    static final String ADMIN_PASSWORD = TestIdentityPasswords.ADMIN_PASSWORD;

    /** Login name of the ordinary identity, from {@code USER_USERNAME} in the same file. */
    private static final String USER_USERNAME = "user0001";

    /** Password of the ordinary identity, equally synthetic. */
    static final String USER_PASSWORD = TestIdentityPasswords.USER_PASSWORD;

    /**
     * Migrations under {@code src/main/resources/db/migration}, V1 through V9.
     *
     * <p>{@code V8__subject_request_posture.sql} carries no data-definition statement. It re-issues
     * the {@code customer.social_security_number} comment, which used to say an erasure request
     * cleared the value with the rest of the row while no export or erasure workflow exists anywhere
     * on this platform to do that.</p>
     *
     * <p>{@code V9__outbox_correlation.sql} adds the two nullable correlation columns
     * {@code outbox_event} records, so a published record can carry the unit of work behind it.</p>
     * <p>{@code V10__outbox_aggregate_head_index.sql} adds one index to {@code outbox_event} and no
     * table. The relay claims the due head row of each account rather than the oldest due rows
     * outright, and that claim reads the table by aggregate and arrival order.</p>
     */
    private static final int MIGRATION_COUNT = 10;

    /** Rows {@code V2__seed.sql} loads into {@code account}, from {@code app/data/ASCII/acctdata.txt}. */
    private static final int SEEDED_ACCOUNT_COUNT = 50;

    /** Rows {@code V2__seed.sql} loads into {@code customer}, from {@code app/data/ASCII/custdata.txt}. */
    private static final int SEEDED_CUSTOMER_COUNT = 50;

    /** Rows {@code V2__seed.sql} loads into {@code disclosure_group}, from {@code app/data/ASCII/discgrp.txt}. */
    private static final int SEEDED_DISCLOSURE_GROUP_COUNT = 51;

    /**
     * Rows {@code V3__reference_data.sql} loads into {@code us_phone_area_code}: one per distinct
     * area code, carrying the band that declares it. {@code VALID-PHONE-AREA-CODE} at
     * {@code app/cpy/CSLKPCDY.cpy:L30-L520} declares 490 codes across two disjoint bands.
     */
    private static final int SEEDED_AREA_CODE_COUNT = 490;

    /** Rows the same migration loads into {@code us_state_code}. */
    private static final int SEEDED_STATE_CODE_COUNT = 56;

    /** Rows the same migration loads into {@code us_state_zip_prefix}. */
    private static final int SEEDED_STATE_ZIP_PREFIX_COUNT = 240;

    /** Identifier of seeded account row 1, eleven characters with its leading zeros. */
    private static final String SEEDED_ACCOUNT_ID = "00000000001";

    /**
     * Identifier of the customer {@code account_customer_link} names for that account. Row 49 of
     * {@code app/data/ASCII/cardxref.txt} carries card {@code 9680294154603697}, customer
     * {@code 000000001} and account {@code 00000000001}, and {@code V4} replicates it. The update
     * path reads that row, as {@code app/cbl/COACTUPC.cbl} derives the customer from the same
     * cross-reference.
     */
    private static final String SEEDED_CUSTOMER_ID = "000000001";

    /** Balance of seeded account row 1, at the two fractional digits the column holds. */
    private static final String SEEDED_BALANCE = "194.00";

    /** Credit limit of seeded account row 1. */
    private static final String SEEDED_CREDIT_LIMIT = "2020.00";

    /** Cash credit limit of seeded account row 1. */
    private static final String SEEDED_CASH_CREDIT_LIMIT = "1020.00";

    /** Open date of seeded account row 1, ten characters of text and no date type. */
    private static final String SEEDED_OPEN_DATE = "2014-11-20";

    /**
     * Open date of seeded account row 1 in the eight-character form a request carries. The screen
     * sent a year, a month and a day as separate map fields, so {@code openapi.yaml} bounds each
     * submitted date at eight characters while the read answers the stored ten.
     */
    private static final String SUBMITTED_OPEN_DATE = "20141120";

    /** Expiry date of seeded account row 1 in the eight-character submitted form. */
    private static final String SUBMITTED_EXPIRATION_DATE = "20250520";

    /** Reissue date of seeded account row 1 in the eight-character submitted form. */
    private static final String SUBMITTED_REISSUE_DATE = "20250520";

    /** Active status of seeded account row 1. */
    private static final String SEEDED_ACTIVE_STATUS = "Y";

    /**
     * Group identifier of seeded account row 1, ten spaces in the column
     * {@code app/cpy/CVACT01Y.cpy} declares. It is the one component of the account block that
     * carries no mandatory edit, so an update may omit it and it then keeps this value.
     */
    private static final String SEEDED_GROUP_ID = "          ";

    /** First address line of seeded customer row 1, without the padding the column carries. */
    private static final String SEEDED_ADDRESS_LINE_1 = "618 Deshaun Route";

    /** Given name of seeded customer row 1, without the padding the column carries. */
    private static final String SEEDED_FIRST_NAME = "Immanuel";

    /** Family name of seeded customer row 1, without the padding the column carries. */
    private static final String SEEDED_LAST_NAME = "Kessler";

    /** Middle name of seeded customer row 1, without the padding the column carries. */
    private static final String SEEDED_MIDDLE_NAME = "Madeline";

    /**
     * A given name shorter than the field, submitted to show what the stored form holds.
     *
     * <p>{@code CUST-FIRST-NAME PIC X(25)} at {@code app/cpy/CVCUS01Y.cpy:L6} is five times this
     * length, so the stored value has to carry twenty trailing spaces.
     */
    private static final String SHORTENED_FIRST_NAME = "Aniya";

    /** Town of seeded customer row 1, without the padding the column carries. */
    private static final String SEEDED_ADDRESS_CITY = "Altenwerthshire";

    /** Country code of seeded customer row 1. */
    private static final String SEEDED_ADDRESS_COUNTRY_CODE = "USA";

    /** First three digits of the Social Security Number of seeded customer row 1. */
    private static final String SEEDED_SOCIAL_SECURITY_PART_1 = "020";

    /** Fourth and fifth digits of the Social Security Number of seeded customer row 1. */
    private static final String SEEDED_SOCIAL_SECURITY_PART_2 = "97";

    /** Last four digits of the Social Security Number of seeded customer row 1. */
    private static final String SEEDED_SOCIAL_SECURITY_PART_3 = "3888";

    /** Electronic funds transfer account of seeded customer row 1. */
    private static final String SEEDED_EFT_ACCOUNT_ID = "0053581756";

    /** Primary cardholder flag of seeded customer row 1. */
    private static final String SEEDED_PRIMARY_CARD_HOLDER_INDICATOR = "Y";

    /**
     * Credit score of seeded customer row 1, which {@code app/data/ASCII/custdata.txt} carries and
     * {@code V2__seed.sql} loads. It sits below the range the update edit admits, which one test
     * below reads back: a submitted pair carrying this value is refused.
     */
    private static final int SEEDED_CREDIT_SCORE = 274;

    /** The same score as the text a request body carries. */
    private static final String SEEDED_CREDIT_SCORE_TEXT = "274";

    /** A credit score inside the range {@code app/cbl/COACTUPC.cbl:L2517-L2523} admits. */
    private static final String PASSING_CREDIT_SCORE = "700";

    /**
     * Second telephone number of seeded customer row 1, in the fifteen-character mask
     * {@code ACUP-NEW-CUST-PHONE-NUM-2 PIC X(15)} at {@code app/cbl/COACTUPC.cbl:L820} declares.
     * Its area code 373 is absent from {@code VALID-GENERAL-PURP-CODE} at
     * {@code app/cpy/CSLKPCDY.cpy:L521-L930}, the band {@code app/cbl/COACTUPC.cbl:L2298} tests, so
     * an update that carries it forward is refused.
     */
    private static final String SEEDED_PHONE_NUMBER_2 = "(373)693-8684";

    /** A number whose area code {@code V3__reference_data.sql} seeds as general purpose. */
    private static final String PASSING_PHONE_NUMBER_2 = "(202)555-0100";

    /** State code of seeded customer row 1, which {@code app/data/ASCII/custdata.txt} carries. */
    private static final String SEEDED_ADDRESS_STATE_CODE = "NC";

    /**
     * Postal code of seeded customer row 1. Its first two digits do not combine with that state
     * code in {@code VALID-US-STATE-ZIP-CD2-COMBO} at {@code app/cpy/CSLKPCDY.cpy:L1071-L1073}, the
     * combination {@code app/cbl/COACTUPC.cbl:L2536} tests, so an update carrying the pair forward
     * is refused.
     */
    private static final String SEEDED_ADDRESS_ZIP = "12546";

    /** A postal code whose first two digits combine with that state code in the seeded table. */
    private static final String PASSING_ADDRESS_ZIP = "27601";

    /**
     * The same passing postal code in the ZIP+4 form thirty of the fifty seeded customer rows hold.
     *
     * <p>{@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L14} holds ten characters
     * and {@code app/cbl/COACTUPC.cbl:L1607} edits five of them, so the source accepts this and the
     * combination test still pairs the state with {@code 27}.
     */
    private static final String PASSING_ADDRESS_ZIP_PLUS_FOUR = PASSING_ADDRESS_ZIP + "-6716";

    /**
     * A credit limit of eleven integer digits, which {@code AccountDataRequest.MONEY_MAX_LENGTH}
     * admits at fourteen characters and {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} cannot hold.
     *
     * <p>{@code credit_limit} is {@code NUMERIC(12,2)}, so this value used to reach the database and
     * raise SQLSTATE 22003.
     */
    private static final String ELEVEN_INTEGER_DIGIT_LIMIT = "12345678901.99";

    /**
     * What the column holds after the store above: the low-order ten integer digits and the sign,
     * which is the outcome of a COBOL {@code MOVE} into the narrower field. No program under
     * {@code app/cbl/} carries an {@code ON SIZE ERROR} phrase to do anything else.
     */
    private static final String ELEVEN_DIGITS_AS_STORED = "2345678901.99";

    /**
     * The text the credit-score edit answers with, from {@code 'FICO Score'} and
     * {@code ': should be between 300 and 850'} at {@code app/cbl/COACTUPC.cbl:L2522-L2523}. The
     * literal is typed here, so no production constant is compared against itself.
     */
    private static final String CREDIT_SCORE_RANGE_MESSAGE =
            "FICO Score: should be between 300 and 850";

    /**
     * The text the update answers when no customer is named. An account record carries no customer
     * identifier of its own, so the caller supplies one. The literal is typed here, so no production
     * constant is compared against itself.
     */
    private static final String CUSTOMER_ID_MANDATORY_MESSAGE =
            "Customer Id must be supplied. An account record carries none";

    /** The limit the update test writes, above the seeded one so the change is observable. */
    private static final String RAISED_CREDIT_LIMIT = "12000.00";

    /** Zero at the scale {@code PIC S9(10)V99} holds, and what a closed cycle answers. */
    private static final String ZERO_AT_SCALE_TWO = "0.00";

    /** A non-zero accumulator pair a cycle close has to clear. */
    private static final String CYCLE_CREDIT_BEFORE_CLOSE = "150.25";

    /** The other accumulator of that pair. */
    private static final String CYCLE_DEBIT_BEFORE_CLOSE = "75.50";

    /** An eleven-digit account identifier no seeded row holds. */
    private static final String ABSENT_ACCOUNT_ID = "00000099999";

    /**
     * The header a first-party client sets on every state-changing request.
     *
     * <p>Read from the production constant rather than repeated, so a change to the shipped default
     * moves this client with it. {@code src/main/resources/application.yml} carries the same value
     * under {@code carddemo.api.cross-site.required-header}.
     */
    private static final String CROSS_SITE_HEADER =
            CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER;

    /** The event type a change to an account field stores, published to one of two topics. */
    private static final String ACCOUNT_STATE_CHANGED = "AccountStateChanged";

    /** The event type a change to a cardholder field stores, published to the other topic. */
    private static final String CUSTOMER_CONTEXT_CHANGED = "CustomerContextChanged";

    /** The shape every monetary component serializes as: at most ten digits, then exactly two. */
    private static final String MONEY_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /** Longest one request waits for an answer. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** Reads and writes rows outside every request this class makes. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link AccountServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    static final PostgreSQLContainer POSTGRES = AccountServiceDatabase.container();

    /**
     * Points the Spring datasource at the running container.
     *
     * <p>Three properties leave here. {@code src/main/resources/application.yml} sits on the test
     * classpath and carries every other datasource, Flyway and persistence setting, so no line
     * below repeats one. The locator carries {@code currentSchema}, because a native statement
     * resolves an unqualified table name against the connection search path, which
     * {@code hibernate.default_schema} does not set.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AccountControllerIT::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container locator with the migrated schema named on it.
     *
     * @return the locator the context connects with
     */
    private static String migratedSchemaUrl() {
        return AccountServiceDatabase.urlFor(AccountControllerIT.class);
    }

    /** Port the embedded server took, which the random-port web environment settles at refresh. */
    @LocalServerPort
    private int port;

    /** Reads and writes rows directly, outside every request this class makes. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Sends every request. Built once per test instance and closed by the runtime. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    /**
     * Returns the two seeded rows this class may change to their migrated values and empties the
     * event table.
     *
     * <p>Three statements cover every column any test here writes: the account accumulators and
     * limit, the customer address line, and the outbox rows a mutation left behind.
     */
    @AfterEach
    void restoreSeededState() {
        jdbcTemplate.update("UPDATE account SET credit_limit = ?, cash_credit_limit = ?,"
                        + " current_balance = ?, current_cycle_credit = 0.00,"
                        + " current_cycle_debit = 0.00, active_status = ? WHERE account_id = ?",
                new BigDecimal(SEEDED_CREDIT_LIMIT), new BigDecimal(SEEDED_CASH_CREDIT_LIMIT),
                new BigDecimal(SEEDED_BALANCE), SEEDED_ACTIVE_STATUS, SEEDED_ACCOUNT_ID);
        jdbcTemplate.update("UPDATE customer SET address_line_1 = ?, fico_credit_score = ?,"
                        + " phone_number_2 = ? WHERE customer_id = ?",
                SEEDED_ADDRESS_LINE_1, SEEDED_CREDIT_SCORE, SEEDED_PHONE_NUMBER_2,
                SEEDED_CUSTOMER_ID);
        jdbcTemplate.update("UPDATE customer SET address_state_code = ?, address_zip = ?"
                        + " WHERE customer_id = ?",
                SEEDED_ADDRESS_STATE_CODE, SEEDED_ADDRESS_ZIP, SEEDED_CUSTOMER_ID);
        jdbcTemplate.update("UPDATE customer SET first_name = ?, middle_name = ?"
                        + " WHERE customer_id = ?",
                paddedTo(SEEDED_FIRST_NAME, PicClause.CUST_FIRST_NAME_WIDTH),
                paddedTo(SEEDED_MIDDLE_NAME, PicClause.CUST_MIDDLE_NAME_WIDTH),
                SEEDED_CUSTOMER_ID);
        jdbcTemplate.update("DELETE FROM outbox_event");
    }

    @Nested
    @DisplayName("The migrated schema, applied by Flyway against a real PostgreSQL server")
    class MigratedSchema {

        @Test
        @DisplayName("every shipped migration applied, each reported successful")
        void everyShippedMigrationApplied() {
            List<Map<String, Object>> applied = jdbcTemplate.queryForList(
                    "SELECT version, success FROM flyway_schema_history"
                            + " WHERE version IS NOT NULL ORDER BY installed_rank");

            assertEquals(MIGRATION_COUNT, applied.size(),
                    "db/migration carries V1 through V10 and Flyway applied every one");
            assertTrue(applied.stream().allMatch(row -> Boolean.TRUE.equals(row.get("success"))),
                    "a migration that failed would leave a row reporting failure: " + applied);
            assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
                    applied.stream().map(row -> String.valueOf(row.get("version"))).toList(),
                    "the versions applied, in the order Flyway applied them");
        }

        @Test
        @DisplayName("the seed migrations loaded every fixture row")
        void theSeedMigrationsLoadedEveryFixtureRow() {
            assertAll(
                    () -> assertEquals(SEEDED_ACCOUNT_COUNT, rowCount("account"),
                            "50 rows from app/data/ASCII/acctdata.txt"),
                    () -> assertEquals(SEEDED_CUSTOMER_COUNT, rowCount("customer"),
                            "50 rows from app/data/ASCII/custdata.txt"),
                    () -> assertEquals(SEEDED_DISCLOSURE_GROUP_COUNT, rowCount("disclosure_group"),
                            "51 rows from app/data/ASCII/discgrp.txt"),
                    () -> assertEquals(SEEDED_AREA_CODE_COUNT, rowCount("us_phone_area_code"),
                            "one row per distinct area code of VALID-PHONE-AREA-CODE at"
                                    + " app/cpy/CSLKPCDY.cpy:L30-L520"),
                    () -> assertEquals(SEEDED_STATE_CODE_COUNT, rowCount("us_state_code"),
                            "56 state codes from app/cpy/CSLKPCDY.cpy"),
                    () -> assertEquals(SEEDED_STATE_ZIP_PREFIX_COUNT,
                            rowCount("us_state_zip_prefix"),
                            "240 state and ZIP prefix combinations from app/cpy/CSLKPCDY.cpy"));
        }

        @Test
        @DisplayName("the persistence layer validated all six entity classes against that schema")
        void thePersistenceLayerValidatedTheEntityMapping() {
            assertNotNull(jdbcTemplate.queryForObject(
                            "SELECT account_id FROM account WHERE account_id = ?",
                            String.class, SEEDED_ACCOUNT_ID),
                    "the context started, which means ddl-auto validate matched every mapped"
                            + " attribute against the migrated column it names");
        }
    }

    @Nested
    @DisplayName("GET /accounts/{accountId}, the read path of app/cbl/COACTVWC.cbl")
    class AccountRead {

        @Test
        @DisplayName("the seeded account is served with its leading zeros and two-place amounts")
        void theSeededAccountIsServed() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID).GET().build());

            assertEquals(200, response.statusCode(), response.body());
            JsonNode account = JSON.readTree(response.body());
            assertAll(
                    () -> assertEquals(SEEDED_ACCOUNT_ID, account.get("accountId").asString(),
                            "ACCT-ID PIC 9(11) keeps ten leading zeros through serialization"),
                    () -> assertEquals(SEEDED_ACTIVE_STATUS, account.get("activeStatus").asString()),
                    () -> assertEquals(SEEDED_BALANCE, account.get("currentBalance").asString()),
                    () -> assertEquals(SEEDED_CREDIT_LIMIT, account.get("creditLimit").asString()),
                    () -> assertEquals(SEEDED_CASH_CREDIT_LIMIT,
                            account.get("cashCreditLimit").asString()),
                    () -> assertEquals(ZERO_AT_SCALE_TWO, account.get("currentCycleCredit").asString()),
                    () -> assertEquals(ZERO_AT_SCALE_TWO, account.get("currentCycleDebit").asString()),
                    () -> assertEquals(SEEDED_OPEN_DATE, account.get("openDate").asString(),
                            "the open date is ten characters of text, compared as text"),
                    () -> assertTrue(account.get("currentBalance").asString().matches(MONEY_PATTERN),
                            "money travels as a decimal string, never as a JSON number"));
        }

        @Test
        @DisplayName("an account no row holds answers 404")
        void anAbsentAccountAnswersNotFound() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + ABSENT_ACCOUNT_ID).GET().build());

            assertEquals(404, response.statusCode(), response.body());
        }

        @Test
        @DisplayName("a request carrying no credential is refused before the controller")
        void anUnauthenticatedReadIsRefused() {
            HttpResponse<String> response = send(HttpRequest
                    .newBuilder(URI.create(baseUri() + "/accounts/" + SEEDED_ACCOUNT_ID))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build());

            assertEquals(401, response.statusCode(), response.body());
        }

        @Test
        @DisplayName("an identity holding no entitlement for the account is refused with 403")
        void anUnentitledReadIsRefused() {
            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID).GET().build());

            assertEquals(403, response.statusCode(),
                    "the ordinary identity holds no SCOPE_ACCOUNT authority for this account,"
                            + " and the chain ends in denyAll: " + response.body());
        }
    }

    @Nested
    @DisplayName("GET /customers/{customerId}, resolved as app/cbl/COACTVWC.cbl resolves it")
    class CustomerRead {

        @Test
        @DisplayName("the seeded customer is served with its leading zeros")
        void theSeededCustomerIsServed() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/customers/" + SEEDED_CUSTOMER_ID).GET().build());

            assertEquals(200, response.statusCode(), response.body());
            JsonNode customer = JSON.readTree(response.body());
            assertAll(
                    () -> assertEquals(SEEDED_CUSTOMER_ID, customer.get("customerId").asString(),
                            "CUST-ID PIC 9(09) keeps its leading zeros"),
                    () -> assertEquals(SEEDED_ADDRESS_LINE_1,
                            customer.get("addressLine1").asString().trim(),
                            "the seeded address line of row 1 of app/data/ASCII/custdata.txt"),
                    () -> assertNotNull(customer.get("ficoCreditScore"),
                            "the credit score the 300 to 850 edit of app/cbl/COACTUPC.cbl bounds"));
        }
    }

    @Nested
    @DisplayName("POST /accounts/{accountId}/cycle-close, from app/cbl/CBACT04C.cbl:L353-L354")
    class CycleClose {

        @Test
        @DisplayName("both accumulators are zeroed, answered at scale two, and one event is stored")
        void bothAccumulatorsAreZeroedAndOneEventIsStored() {
            givenCycleAccumulators(CYCLE_CREDIT_BEFORE_CLOSE, CYCLE_DEBIT_BEFORE_CLOSE);

            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID + "/cycle-close")
                    .header(CONTENT_TYPE_HEADER, JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertEquals(200, response.statusCode(), response.body());
            JsonNode body = JSON.readTree(response.body());
            assertAll(
                    () -> assertEquals(SEEDED_ACCOUNT_ID, body.get("accountId").asString()),
                    () -> assertEquals(ZERO_AT_SCALE_TWO, body.get("currentCycleCredit").asString(),
                            "MOVE 0 TO ACCT-CURR-CYC-CREDIT at app/cbl/CBACT04C.cbl:L353"),
                    () -> assertEquals(ZERO_AT_SCALE_TWO, body.get("currentCycleDebit").asString(),
                            "MOVE 0 TO ACCT-CURR-CYC-DEBIT at app/cbl/CBACT04C.cbl:L354"),
                    () -> assertEquals(new BigDecimal(ZERO_AT_SCALE_TWO), storedCycleCredit(),
                            "the stored credit accumulator is zero, not only the answer"),
                    () -> assertEquals(new BigDecimal(ZERO_AT_SCALE_TWO), storedCycleDebit(),
                            "the stored debit accumulator is zero as well"),
                    () -> assertEquals(new BigDecimal(SEEDED_BALANCE), storedBalance(),
                            "ADD WS-TOTAL-INT TO ACCT-CURR-BAL at app/cbl/CBACT04C.cbl:L352 has no"
                                    + " counterpart here, so the balance is untouched"));

            List<Map<String, Object>> events = storedEvents();
            assertEquals(1, events.size(), "one mutation stores exactly one event row");
            assertAll(
                    () -> assertEquals(ACCOUNT_STATE_CHANGED, events.get(0).get("event_type")),
                    () -> assertEquals(SEEDED_ACCOUNT_ID,
                            String.valueOf(events.get(0).get("aggregate_id")),
                            "the account identifier is the Kafka message key"),
                    () -> assertEquals(Boolean.FALSE, events.get(0).get("published"),
                            "the row committed with the account row and the relay publishes it"
                                    + " afterwards, which is why it is still unpublished here"),
                    () -> assertTrue(String.valueOf(events.get(0).get("payload"))
                                    .contains("BILLING_CYCLE_CLOSED"),
                            "the payload names the change a consumer routes on"));
        }

        @Test
        @DisplayName("a close against an account no row holds answers 404 and stores no event")
        void anAbsentAccountAnswersNotFoundAndStoresNothing() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + ABSENT_ACCOUNT_ID + "/cycle-close")
                    .header(CONTENT_TYPE_HEADER, JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertEquals(404, response.statusCode(), response.body());
            assertEquals(0, storedEvents().size(), "a close that found no row publishes nothing");
        }

        @Test
        @DisplayName("the ordinary identity cannot close a cycle, and no accumulator moves")
        void theOrdinaryIdentityCannotCloseACycle() {
            givenCycleAccumulators(CYCLE_CREDIT_BEFORE_CLOSE, CYCLE_DEBIT_BEFORE_CLOSE);

            HttpResponse<String> response = send(authorized(USER_USERNAME, USER_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID + "/cycle-close")
                    .header(CONTENT_TYPE_HEADER, JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertEquals(403, response.statusCode(),
                    "the route is scoped to ROLE_ADMIN: " + response.body());
            assertEquals(new BigDecimal(CYCLE_CREDIT_BEFORE_CLOSE), storedCycleCredit(),
                    "a refused request reaches no domain service");
            assertEquals(0, storedEvents().size(), "and stores no event");
        }

        @Test
        @DisplayName("a form-encoded close is refused by the chain, and no accumulator moves")
        void aFormEncodedCloseIsRefusedByTheChain() {
            givenCycleAccumulators(CYCLE_CREDIT_BEFORE_CLOSE, CYCLE_DEBIT_BEFORE_CLOSE);

            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID + "/cycle-close")
                    .header(CONTENT_TYPE_HEADER, FORM_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            // The credential here is a valid administrator one, which is the point: this is the
            // shape a browser driven from another origin would send, carrying the credential it
            // already holds. The refusal comes from the request's shape and not from who sent it.
            assertEquals(403, response.statusCode(),
                    "a content type a browser can send cross-origin is refused ahead of every"
                            + " route rule: " + response.body());
            assertEquals(new BigDecimal(CYCLE_CREDIT_BEFORE_CLOSE), storedCycleCredit(),
                    "the accumulators the credit-limit rule tests must not move");
            assertEquals(0, storedEvents().size(), "and no event is stored");
        }

        @Test
        @DisplayName("a close naming no media type reads 415, and no accumulator moves")
        void aCloseNamingNoMediaTypeReadsUnsupportedMediaType() {
            givenCycleAccumulators(CYCLE_CREDIT_BEFORE_CLOSE, CYCLE_DEBIT_BEFORE_CLOSE);

            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID + "/cycle-close")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            // A missing header is a caller's mistake, so it reads 415 from the route rather than
            // 403 from the chain. The two answers are different on purpose.
            assertEquals(415, response.statusCode(),
                    "a bodyless POST still names the media type it requires: " + response.body());
            assertEquals(new BigDecimal(CYCLE_CREDIT_BEFORE_CLOSE), storedCycleCredit());
            assertEquals(0, storedEvents().size());
        }

        @Test
        @DisplayName("a close carrying no credential is refused with 401")
        void anUnauthenticatedCloseIsRefused() {
            HttpResponse<String> response = send(HttpRequest
                    .newBuilder(URI.create(
                            baseUri() + "/accounts/" + SEEDED_ACCOUNT_ID + "/cycle-close"))
                    .timeout(REQUEST_TIMEOUT)
                    .header(CONTENT_TYPE_HEADER, JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertEquals(401, response.statusCode(), response.body());
        }
    }

    /**
     * The forged cycle close, measured against the running service.
     *
     * <p>{@code POST /accounts/{accountId}/cycle-close} carries no body, so an HTML form can submit
     * it, and a browser attaches a cached HTTP Basic credential to that submission without asking
     * the person reading the page. The call is therefore authenticated and authorized: every test
     * below presents the administrator credential the route requires, and is refused anyway.
     *
     * <p>What the accumulators hold matters as much as the status code. The credit-limit rule at
     * {@code app/cbl/CBTRN02C.cbl:L403-L413} authorizes against them, so a forged close reaching
     * {@code domain/BillingCycleService} would clear an overlimit condition on somebody else's
     * instruction.
     */
    @Nested
    @DisplayName("a forged state-changing request, refused although it authenticates")
    class ForgedStateChangingRequests {

        @Test
        @DisplayName("a form submission carrying no cross-site header is refused with 403")
        void aFormSubmissionCarryingNoCrossSiteHeaderIsRefused() {
            givenCycleAccumulators(CYCLE_CREDIT_BEFORE_CLOSE, CYCLE_DEBIT_BEFORE_CLOSE);
            String credential = Base64.getEncoder().encodeToString(
                    (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));

            HttpResponse<String> response = send(HttpRequest
                    .newBuilder(URI.create(
                            baseUri() + "/accounts/" + SEEDED_ACCOUNT_ID + "/cycle-close"))
                    .header("Authorization", "Basic " + credential)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertEquals(403, response.statusCode(),
                    "an authenticated administrator credential is not enough: a state-changing "
                            + "request has to come from a first-party client: " + response.body());
            assertEquals(new BigDecimal(CYCLE_CREDIT_BEFORE_CLOSE), storedCycleCredit(),
                    "a refused request must reach no domain service");
            assertEquals(new BigDecimal(CYCLE_DEBIT_BEFORE_CLOSE), storedCycleDebit(),
                    "and must leave the debit accumulator alone as well");
            assertEquals(0, storedEvents().size(), "and must publish nothing");
        }

        @Test
        @DisplayName("a request a foreign page caused is refused although it carries the header")
        void aRequestAForeignPageCausedIsRefused() {
            givenCycleAccumulators(CYCLE_CREDIT_BEFORE_CLOSE, CYCLE_DEBIT_BEFORE_CLOSE);

            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID + "/cycle-close")
                    .header("Sec-Fetch-Site", "cross-site")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertEquals(403, response.statusCode(),
                    "a browser states where a request came from, and page script cannot write that "
                            + "header: " + response.body());
            assertEquals(new BigDecimal(CYCLE_CREDIT_BEFORE_CLOSE), storedCycleCredit(),
                    "a refused request must reach no domain service");
            assertEquals(0, storedEvents().size(), "and must publish nothing");
        }

        @Test
        @DisplayName("a request naming a foreign Origin is refused although it carries the header")
        void aRequestNamingAForeignOriginIsRefused() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://attacker.example")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            "{\"creditLimit\":\"99999.00\"}", StandardCharsets.UTF_8))
                    .build());

            assertEquals(403, response.statusCode(), response.body());
            assertEquals(new BigDecimal(SEEDED_CREDIT_LIMIT), storedCreditLimit(),
                    "a refused update must leave the stored limit as the seed loaded it");
            assertEquals(0, storedEvents().size(), "and must publish nothing");
        }

        @Test
        @DisplayName("a read from a foreign page is not refused, because it changes nothing")
        void aReadFromAForeignPageIsNotRefused() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID)
                    .header("Sec-Fetch-Site", "cross-site")
                    .header("Origin", "https://attacker.example")
                    .GET()
                    .build());

            assertEquals(200, response.statusCode(),
                    "no chain here grants a cross-origin policy, so a browser withholds this "
                            + "answer from the page that asked for it: " + response.body());
        }
    }

    @Nested
    @DisplayName("PUT /accounts/{accountId}, the update path of app/cbl/COACTUPC.cbl")
    class AccountUpdate {

        @Test
        @DisplayName("a raised credit limit is stored with one event, and omitted optionals stand")
        void aRaisedCreditLimitIsStoredWithOneEvent() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            updateBody(RAISED_CREDIT_LIMIT, PASSING_CREDIT_SCORE),
                            StandardCharsets.UTF_8))
                    .build());

            assertEquals(200, response.statusCode(), response.body());
            assertAll(
                    () -> assertEquals(new BigDecimal(RAISED_CREDIT_LIMIT), storedCreditLimit(),
                            "the submitted limit reached the row"),
                    () -> assertEquals(new BigDecimal(SEEDED_CASH_CREDIT_LIMIT),
                            storedCashCreditLimit(),
                            "a mandatory component submitted at its stored value leaves the column"
                                    + " as it was, so only the limit moved"),
                    () -> assertEquals(SEEDED_GROUP_ID, storedGroupId(),
                            "groupId carries no mandatory edit, so the body may omit it and"
                                    + " app/cbl/COACTUPC.cbl carries ACUP-OLD-ACCT-DATA forward"),
                    () -> assertEquals(List.of(ACCOUNT_STATE_CHANGED, CUSTOMER_CONTEXT_CHANGED),
                            storedEvents().stream()
                                    .map(row -> String.valueOf(row.get("event_type")))
                                    .sorted()
                                    .toList(),
                            "both rows and both event rows commit in one transaction: this update"
                                    + " changed an account field and a cardholder field, and"
                                    + " src/main/resources/application.yml declares one topic for"
                                    + " each"),
                    () -> assertTrue(storedEvents().stream()
                                    .noneMatch(row -> Boolean.TRUE.equals(row.get("published"))),
                            "the relay publishes after the commit, so both rows are unpublished"));
        }

        /**
         * A postal code the read model returns has to be accepted back by this route.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L1606} moves the whole {@code PIC X(10)} field into the edit
         * area and {@code app/cbl/COACTUPC.cbl:L1607} then moves 5 into
         * {@code WS-EDIT-ALPHANUM-LENGTH}, so every test of {@code 1245-EDIT-NUM-REQD} reads
         * {@code WS-EDIT-ALPHANUM-ONLY(1:5)} and positions six to ten are carried without being
         * inspected. Thirty of the fifty seeded customer rows hold a ZIP+4 and the other twenty hold
         * five digits padded to ten, and editing the full width refused all fifty: a value this
         * service had just returned from {@code GET} answered 422 on {@code PUT}.
         */
        @Test
        @DisplayName("a ZIP+4 postal code is accepted, stored whole, and served back unchanged")
        void aZipPlusFourPostalCodeRoundTrips() {
            String body = updateBody(RAISED_CREDIT_LIMIT, PASSING_CREDIT_SCORE)
                    .replace("\"addressZip\":\"" + PASSING_ADDRESS_ZIP + "\"",
                            "\"addressZip\":\"" + PASSING_ADDRESS_ZIP_PLUS_FOUR + "\"");

            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID)
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build());

            assertEquals(200, response.statusCode(), response.body());

            String stored = storedAddressZip();
            HttpResponse<String> read = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/customers/" + SEEDED_CUSTOMER_ID).GET().build());
            assertEquals(200, read.statusCode(), read.body());
            String served = JSON.readTree(read.body()).get("addressZip").asString();

            assertAll(
                    () -> assertEquals(PASSING_ADDRESS_ZIP_PLUS_FOUR, stored,
                            "every character reaches address_zip VARCHAR(10), so the four digits"
                                    + " past the edited width are not discarded"),
                    () -> assertEquals(PicClause.CUST_ADDR_ZIP_WIDTH, stored.length(),
                            "and the column holds the whole ten-character field"),
                    () -> assertEquals(PASSING_ADDRESS_ZIP_PLUS_FOUR, served,
                            "the read serves what was stored"),
                    () -> assertEquals(200, send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                                    "/accounts/" + SEEDED_ACCOUNT_ID)
                            .header("Content-Type", JSON_MEDIA_TYPE)
                            .PUT(HttpRequest.BodyPublishers.ofString(
                                    updateBody(RAISED_CREDIT_LIMIT, PASSING_CREDIT_SCORE)
                                            .replace("\"addressZip\":\"" + PASSING_ADDRESS_ZIP
                                                            + "\"",
                                                    "\"addressZip\":\"" + served + "\""),
                                    StandardCharsets.UTF_8))
                            .build()).statusCode(),
                            "and sending that served value straight back is accepted, which is the"
                                    + " round trip that used to answer 422"));
        }

        /**
         * A figure the request grammar accepts but the field cannot hold takes the store semantics of
         * a COBOL {@code MOVE}, not a database overflow.
         *
         * <p>{@code AccountDataRequest.MONEY_MAX_LENGTH} is fifteen, the width
         * {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at {@code app/cbl/COACTUPC.cbl:L55}
         * receives, and the currency-tolerant grammar accepts eleven integer digits inside it. Every
         * money column is {@code NUMERIC(12,2)} because {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} holds
         * ten integer digits, so such a value used to reach the database, raise SQLSTATE 22003 and
         * answer 500. A database overflow is not validation.
         *
         * <p>The delivered behaviour drops the high-order digits and keeps the sign, which is what
         * {@code app/cbl/COBIL00C.cbl:L224} does carrying {@code ACCT-CURR-BAL} into the narrower
         * {@code TRAN-AMT}, and no program under {@code app/cbl/} carries an {@code ON SIZE ERROR}
         * phrase to do anything else. The dropped digit is reported once at warning level, naming the
         * capacity and withholding the figure.
         */
        @Test
        @DisplayName("an eleven-integer-digit limit stores its low-order ten digits rather than "
                + "answering 500")
        void anElevenIntegerDigitLimitTakesTheCobolMoveOutcome() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID)
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            updateBody(ELEVEN_INTEGER_DIGIT_LIMIT, PASSING_CREDIT_SCORE),
                            StandardCharsets.UTF_8))
                    .build());

            assertAll(
                    () -> assertNotEquals(500, response.statusCode(),
                            "a valid submitted figure must not reach the database as an overflow: "
                                    + response.body()),
                    () -> assertEquals(200, response.statusCode(), response.body()),
                    () -> assertEquals(new BigDecimal(ELEVEN_DIGITS_AS_STORED),
                            storedCreditLimit(),
                            "the column holds the low-order ten integer digits and the two"
                                    + " fractional ones, which is what a MOVE into PIC S9(10)V99"
                                    + " leaves"),
                    () -> assertEquals(PicClause.ACCT_CREDIT_LIMIT_PRECISION
                                    - PicClause.ACCT_CREDIT_LIMIT_SCALE,
                            storedCreditLimit().precision() - storedCreditLimit().scale(),
                            "and it holds exactly the ten integer digits the field declares"));
        }

        @Test
        @DisplayName("a shortened name is stored and served at the width CUST-FIRST-NAME declares")
        void aShortenedNameIsStoredAtItsDeclaredWidth() {
            String submitted = SHORTENED_FIRST_NAME;
            String body = updateBody(RAISED_CREDIT_LIMIT, PASSING_CREDIT_SCORE)
                    .replace("\"firstName\":\"" + SEEDED_FIRST_NAME + "\"",
                            "\"firstName\":\"" + submitted + "\"");

            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID)
                    .header("Content-Type", JSON_MEDIA_TYPE)
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build());
            assertEquals(200, response.statusCode(), response.body());

            HttpResponse<String> read = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/customers/" + SEEDED_CUSTOMER_ID).GET().build());
            JsonNode customer = JSON.readTree(read.body());
            String stored = storedFirstName();
            String expected = submitted
                    + " ".repeat(PicClause.CUST_FIRST_NAME_WIDTH - submitted.length());
            assertAll(
                    () -> assertEquals(200, read.statusCode(),
                            "the follow-up read has to answer before its body means anything: "
                                    + read.body()),
                    () -> assertEquals(expected, stored,
                            "app/cbl/COACTUPC.cbl:L4010-L4011 moves the screen field into"
                                    + " CUST-FIRST-NAME PIC X(25) and :L4086 rewrites the 500-byte"
                                    + " record, so the column holds the submitted characters"
                                    + " followed by padding to the whole field"),
                    () -> assertEquals(expected, customer.get("firstName").asString(),
                            "and the read answers that field character for character. Asserting"
                                    + " only its length would pass on any wrong value 25 characters"
                                    + " wide"),
                    () -> assertEquals(SEEDED_MIDDLE_NAME, storedMiddleName().trim(),
                            "an optional component the body omits keeps the value the row held"));
        }

        @Test
        @DisplayName("the seeded credit score of 274 is refused, and a refused update writes nothing")
        void theSeededOutOfRangeCreditScoreIsRefused() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            updateBody(RAISED_CREDIT_LIMIT, SEEDED_CREDIT_SCORE_TEXT),
                            StandardCharsets.UTF_8))
                    .build());

            assertEquals(422, response.statusCode(), response.body());
            assertTrue(response.body().contains(CREDIT_SCORE_RANGE_MESSAGE),
                    "the edit of app/cbl/COACTUPC.cbl:L2517-L2523 answers on the seeded score of"
                            + " 274, which the fixture carries and the range does not admit: "
                            + response.body());
            assertAll(
                    () -> assertNotEquals(new BigDecimal(RAISED_CREDIT_LIMIT), storedCreditLimit(),
                            "a refused update writes neither row"),
                    () -> assertEquals(0, storedEvents().size(), "and stores no event"));
        }

        @Test
        @DisplayName("an update naming no customer is refused with 422 and changes nothing")
        void anUpdateNamingNoCustomerIsRefused() {
            HttpResponse<String> response = send(authorized(ADMIN_USERNAME, ADMIN_PASSWORD,
                    "/accounts/" + SEEDED_ACCOUNT_ID)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            "{\"accountData\":" + accountBlock(RAISED_CREDIT_LIMIT) + "}",
                            StandardCharsets.UTF_8))
                    .build());

            assertEquals(422, response.statusCode(), response.body());
            assertTrue(response.body().contains(CUSTOMER_ID_MANDATORY_MESSAGE),
                    "an account record carries no customer identifier, so the caller has to name"
                            + " one even when the account block is complete: " + response.body());
            assertNotEquals(new BigDecimal(RAISED_CREDIT_LIMIT), storedCreditLimit(),
                    "a refused update writes nothing");
            assertEquals(0, storedEvents().size(), "and stores no event");
        }

        @Test
        @DisplayName("an update carrying no credential is refused with 401")
        void anUnauthenticatedUpdateIsRefused() {
            HttpResponse<String> response = send(HttpRequest
                    .newBuilder(URI.create(baseUri() + "/accounts/" + SEEDED_ACCOUNT_ID))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            updateBody(RAISED_CREDIT_LIMIT, PASSING_CREDIT_SCORE),
                            StandardCharsets.UTF_8))
                    .build());

            assertEquals(401, response.statusCode(), response.body());
            assertEquals(0, storedEvents().size(), "a refused request stores no event");
        }
    }

    /**
     * Builds one complete update body: one new limit, one credit score, and every other component
     * the two blocks declare mandatory carrying the value the seeded row already holds.
     *
     * <p><b>A present block travels complete.</b> Nine components of the account block and
     * fourteen of the customer block carry a mandatory-field edit in {@code 1200-EDIT-MAP-INPUTS}
     * at {@code app/cbl/COACTUPC.cbl:L1470-L1676}, and those edits refuse an absent value exactly
     * as they refuse a blank one — the map area a 3270 screen sends is fixed width, so an operator
     * who cleared a field sent spaces and the edit read spaces. A body that drops one of them is
     * answered {@code 422} naming that field, so a body meant to be applied carries all twenty-three.
     *
     * <p><b>What is deliberately absent.</b> {@code groupId} on the account block and the middle
     * name, both telephone numbers, the date of birth, the second address line and the
     * government-issued identifier on the customer block carry no mandatory edit, so they are
     * omitted here and keep their stored value. One exception travels anyway: the second telephone
     * number is supplied with {@link #PASSING_PHONE_NUMBER_2} because the stored value carries an
     * area code the band at {@code app/cbl/COACTUPC.cbl:L2298} does not declare, and a value carried
     * forward still reaches that edit.
     *
     * <p><b>Two further seeded values are overridden for the same reason.</b> The state and postal
     * code of the seeded row do not form a combination {@code app/cbl/COACTUPC.cbl:L2536} accepts,
     * so the state travels beside a postal code that does. The fixture rows were never screen input,
     * which is why they meet edits the source only ever applied to typed values.
     *
     * @param creditLimit the limit to submit
     * @param creditScore the credit score to submit
     * @return the request body
     */
    private static String updateBody(String creditLimit, String creditScore) {
        return "{\"accountData\":" + accountBlock(creditLimit)
                + ",\"customerData\":{\"customerId\":\"" + SEEDED_CUSTOMER_ID + "\""
                + ",\"firstName\":\"" + SEEDED_FIRST_NAME + "\""
                + ",\"lastName\":\"" + SEEDED_LAST_NAME + "\""
                + ",\"addressLine1\":\"" + SEEDED_ADDRESS_LINE_1 + "\""
                + ",\"addressCity\":\"" + SEEDED_ADDRESS_CITY + "\""
                + ",\"addressStateCode\":\"" + SEEDED_ADDRESS_STATE_CODE + "\""
                + ",\"addressCountryCode\":\"" + SEEDED_ADDRESS_COUNTRY_CODE + "\""
                + ",\"addressZip\":\"" + PASSING_ADDRESS_ZIP + "\""
                + ",\"phoneNumber2\":\"" + PASSING_PHONE_NUMBER_2 + "\""
                + ",\"socialSecurityPart1\":\"" + SEEDED_SOCIAL_SECURITY_PART_1 + "\""
                + ",\"socialSecurityPart2\":\"" + SEEDED_SOCIAL_SECURITY_PART_2 + "\""
                + ",\"socialSecurityPart3\":\"" + SEEDED_SOCIAL_SECURITY_PART_3 + "\""
                + ",\"eftAccountId\":\"" + SEEDED_EFT_ACCOUNT_ID + "\""
                + ",\"primaryCardHolderIndicator\":\""
                + SEEDED_PRIMARY_CARD_HOLDER_INDICATOR + "\""
                + ",\"ficoCreditScore\":\"" + creditScore + "\"}}";
    }

    /**
     * Builds the account block carrying all nine mandatory components, only the limit changed.
     *
     * <p>{@code groupId} is omitted on purpose: no edit of {@code app/cbl/COACTUPC.cbl} reads it,
     * so it is the one component of this block that keeps its stored value when absent.
     *
     * @param creditLimit the limit to submit
     * @return the account block
     */
    private static String accountBlock(String creditLimit) {
        return "{\"activeStatus\":\"" + SEEDED_ACTIVE_STATUS + "\""
                + ",\"currentBalance\":\"" + SEEDED_BALANCE + "\""
                + ",\"creditLimit\":\"" + creditLimit + "\""
                + ",\"cashCreditLimit\":\"" + SEEDED_CASH_CREDIT_LIMIT + "\""
                + ",\"openDate\":\"" + SUBMITTED_OPEN_DATE + "\""
                + ",\"expirationDate\":\"" + SUBMITTED_EXPIRATION_DATE + "\""
                + ",\"reissueDate\":\"" + SUBMITTED_REISSUE_DATE + "\""
                + ",\"currentCycleCredit\":\"" + ZERO_AT_SCALE_TWO + "\""
                + ",\"currentCycleDebit\":\"" + ZERO_AT_SCALE_TWO + "\"}";
    }

    /**
     * Sets both cycle accumulators on the seeded account, outside every request.
     *
     * @param credit the credit accumulator to store
     * @param debit  the debit accumulator to store
     */
    private void givenCycleAccumulators(String credit, String debit) {
        jdbcTemplate.update("UPDATE account SET current_cycle_credit = ?, current_cycle_debit = ?"
                        + " WHERE account_id = ?",
                new BigDecimal(credit), new BigDecimal(debit), SEEDED_ACCOUNT_ID);
    }

    /**
     * Counts the rows of one migrated table.
     *
     * @param table the table to count
     * @return the row count
     */
    private int rowCount(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? -1 : count;
    }

    /** @return the stored credit accumulator of the seeded account */
    private BigDecimal storedCycleCredit() {
        return storedAmount("current_cycle_credit");
    }

    /** @return the stored debit accumulator of the seeded account */
    private BigDecimal storedCycleDebit() {
        return storedAmount("current_cycle_debit");
    }

    /** @return the stored balance of the seeded account */
    private BigDecimal storedBalance() {
        return storedAmount("current_balance");
    }

    /** @return the stored credit limit of the seeded account */
    private BigDecimal storedCreditLimit() {
        return storedAmount("credit_limit");
    }

    /** @return the stored cash credit limit of the seeded account */
    private BigDecimal storedCashCreditLimit() {
        return storedAmount("cash_credit_limit");
    }

    /** @return the stored group identifier of the seeded account, padding included */
    private String storedGroupId() {
        return jdbcTemplate.queryForObject(
                "SELECT group_id FROM account WHERE account_id = ?",
                String.class, SEEDED_ACCOUNT_ID);
    }

    /**
     * Brings one value to a declared width with trailing spaces, as a stored field carries it.
     *
     * @param value         the value without padding
     * @param declaredWidth the width the Picture clause declares
     * @return the value at that width
     */
    private static String paddedTo(String value, int declaredWidth) {
        return value + " ".repeat(declaredWidth - value.length());
    }

    /**
     * Reads the stored postal code, padding included.
     *
     * @return the whole {@code address_zip} column of the seeded customer row
     */
    private String storedAddressZip() {
        return jdbcTemplate.queryForObject(
                "SELECT address_zip FROM customer WHERE customer_id = ?",
                String.class, SEEDED_CUSTOMER_ID);
    }

    /**
     * Reads the stored given name, padding included.
     *
     * @return the whole {@code first_name} column of the seeded customer row
     */
    private String storedFirstName() {
        return jdbcTemplate.queryForObject(
                "SELECT first_name FROM customer WHERE customer_id = ?",
                String.class, SEEDED_CUSTOMER_ID);
    }

    /**
     * Reads the stored middle name, padding included.
     *
     * @return the whole {@code middle_name} column of the seeded customer row
     */
    private String storedMiddleName() {
        return jdbcTemplate.queryForObject(
                "SELECT middle_name FROM customer WHERE customer_id = ?",
                String.class, SEEDED_CUSTOMER_ID);
    }

    /**
     * Reads one monetary column of the seeded account row.
     *
     * @param column the column to read
     * @return the stored amount
     */
    private BigDecimal storedAmount(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM account WHERE account_id = ?",
                BigDecimal.class, SEEDED_ACCOUNT_ID);
    }

    /** @return every stored event row, oldest first */
    private List<Map<String, Object>> storedEvents() {
        return jdbcTemplate.queryForList("SELECT event_type, aggregate_id, payload, published"
                + " FROM outbox_event ORDER BY created_at");
    }

    /** @return the origin every request in this class is sent to */
    private String baseUri() {
        return "http://localhost:" + port;
    }

    /**
     * Starts a request builder carrying one identity's credential and the timeout.
     *
     * <p>The cross-site header is set here rather than per call, because
     * {@code config/CrossSiteRequestFilter} requires it on every state-changing request and a
     * first-party client sets it on all of them. A browser form cannot set a header at all, which
     * is exactly what separates this client from the forged submission
     * {@link ForgedStateChangingRequests} measures.
     *
     * @param username     the login name to present
     * @param password     the password to present
     * @param pathAndQuery the path, and the query string when one applies
     * @return the builder
     */
    private HttpRequest.Builder authorized(String username, String password, String pathAndQuery) {
        String encoded = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(baseUri() + pathAndQuery))
                .header("Authorization", "Basic " + encoded)
                .header("Accept", "application/json")
                .header(CROSS_SITE_HEADER, "1")
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
}
