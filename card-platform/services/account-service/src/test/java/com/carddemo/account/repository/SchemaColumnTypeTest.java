package com.carddemo.account.repository;

import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.entity.OutboxEventEntity;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Reads back the type, precision, scale and length of every migrated column of the account schema
 * and compares each one with the Picture clause it comes from.
 *
 * <p>Every test method runs two read-only Structured Query Language (SQL) queries, one over
 * {@code information_schema.columns} and one over {@code information_schema.key_column_usage}. No
 * test inserts, updates or deletes a row. The three entity-side test methods read a declared
 * field through reflection and store nothing.</p>
 *
 * <p><b>What each assertion cites.</b> A column that carries a record layout names its Picture
 * clause and the copybook line holding it. A key column also names the {@code KEYS} parameter of
 * its Job Control Language (JCL) member. The three key columns of {@code disclosure_group} hold
 * ten, two and four characters, and their sum matches {@code KEYS(16 0)} at
 * {@code app/jcl/DISCGRP.jcl:L40}.</p>
 *
 * <p><b>The three account calendar columns and the customer date of birth hold ten characters of
 * text and carry no date type.</b> The comparison at {@code app/cbl/CBTRN02C.cbl:L414} reads
 * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}.</p>
 *
 * <p><b>Two renames.</b> {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:L11} becomes
 * column {@code expiration_date}, and {@code CUST-ADDR-LINE-3} at
 * {@code app/cpy/CVCUS01Y.cpy:L11} becomes column {@code address_city}.
 * {@code card-platform/docs/traceability-matrix.md} records both.</p>
 *
 * <p><b>What this class covers.</b> Five monetary account columns, two further decimal columns and
 * the two sensitive customer identifier columns. The two business keys, the one composite key, the
 * remaining character columns and the two additive tables. Column counts, seed row counts and
 * stored values belong to other test classes of this module.
 * {@code card-platform/docs/decision-log.md} holds the reasoning behind the storage forms the
 * assertions read back, and {@code card-platform/docs/data-model.md} draws the tables.</p>
 */
@DisplayName("Account schema column types against the copybook Picture clauses")
class SchemaColumnTypeTest extends AbstractAccountPostgresTest {

    /** The schema the migrations create, and the schema every query below filters on. */
    private static final String ACCOUNT_SCHEMA = "account_service";

    /** Type name PostgreSQL reports for a fixed-length character column, declared {@code CHAR}. */
    private static final String FIXED_TEXT = "character";

    /** Type name PostgreSQL reports for a varying character column, declared {@code VARCHAR}. */
    private static final String VARYING_TEXT = "character varying";

    /** Type name PostgreSQL reports for a fixed-point decimal column, declared {@code NUMERIC}. */
    private static final String DECIMAL = "numeric";

    /** Type name PostgreSQL reports for the timestamp columns of the two additive tables. */
    private static final String TIMESTAMP_WITH_ZONE = "timestamp with time zone";

    /** The three type names a ten-character calendar column may not report. */
    private static final List<String> DATE_AND_TIMESTAMP_TYPES =
            List.of("date", "timestamp without time zone", TIMESTAMP_WITH_ZONE);

    /** Declared length of a calendar column, from the four {@code PIC X(10)} clauses below. */
    private static final int CALENDAR_LENGTH = 10;

    /**
     * The migration declaring {@code outbox_event} and {@code processed_event}.
     *
     * <p>Neither table comes from a record layout, so neither carries a Picture clause. Every
     * assertion over the two tables cites the migration.</p>
     */
    private static final String ADDITIVE_SOURCE = "src/main/resources/db/migration/V1__schema.sql";

    /**
     * Supplies the four calendar columns as table name, column name and source locator.
     *
     * <p>Two test methods read these four rows, one for the declared length and one for the
     * absence of a date type.</p>
     *
     * @return one row per calendar column
     */
    private static Stream<Arguments> calendarColumns() {
        return Stream.of(
                arguments("account", "open_date",
                        "ACCT-OPEN-DATE PIC X(10) at app/cpy/CVACT01Y.cpy:L10"),
                arguments("account", "expiration_date",
                        "ACCT-EXPIRAION-DATE PIC X(10) at app/cpy/CVACT01Y.cpy:L11"),
                arguments("account", "reissue_date",
                        "ACCT-REISSUE-DATE PIC X(10) at app/cpy/CVACT01Y.cpy:L12"),
                arguments("customer", "date_of_birth",
                        "CUST-DOB-YYYY-MM-DD PIC X(10) at app/cpy/CVCUS01Y.cpy:L19"));
    }

    /**
     * One row of {@code information_schema.columns}, holding the five fields the assertions read.
     *
     * @param dataType type name PostgreSQL reports for the column
     * @param textLength declared character length, null for a column declaring none
     * @param precision declared total digit count, null for a column declaring none
     * @param scale declared digit count after the decimal point, null for a column declaring none
     * @param nullable whether the column accepts a null
     */
    private record ColumnShape(String dataType, Integer textLength, Integer precision,
                               Integer scale, boolean nullable) {
    }

    /** Connection source for the two catalogue queries, bound to the container the harness runs. */
    @Autowired
    private DataSource dataSource;

    /** Every column of the schema, keyed by table name and column name. */
    private final Map<String, ColumnShape> columns = new HashMap<>();

    /** Primary key columns of every table of the schema, in key order. */
    private final Map<String, List<String>> primaryKeys = new HashMap<>();

    /**
     * Reads the two catalogue queries once for the test method about to run.
     *
     * @throws SQLException when either catalogue query fails
     */
    @BeforeEach
    void readCatalogue() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            readColumns(connection);
            readPrimaryKeys(connection);
        }
    }

    private void readColumns(Connection connection) throws SQLException {
        String query = """
                SELECT table_name, column_name, data_type, character_maximum_length,
                       numeric_precision, numeric_scale, is_nullable
                  FROM information_schema.columns
                 WHERE table_schema = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, ACCOUNT_SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.put(key(rows.getString("table_name"), rows.getString("column_name")),
                            new ColumnShape(
                                    rows.getString("data_type"),
                                    boxedInt(rows, "character_maximum_length"),
                                    boxedInt(rows, "numeric_precision"),
                                    boxedInt(rows, "numeric_scale"),
                                    "YES".equals(rows.getString("is_nullable"))));
                }
            }
        }
    }

    private void readPrimaryKeys(Connection connection) throws SQLException {
        String query = """
                SELECT constraints.table_name, usage.column_name
                  FROM information_schema.table_constraints constraints
                  JOIN information_schema.key_column_usage usage
                    ON usage.constraint_schema = constraints.constraint_schema
                   AND usage.constraint_name = constraints.constraint_name
                 WHERE constraints.constraint_type = 'PRIMARY KEY'
                   AND constraints.table_schema = ?
                 ORDER BY constraints.table_name, usage.ordinal_position
                """;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, ACCOUNT_SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    primaryKeys.computeIfAbsent(rows.getString("table_name"),
                            table -> new ArrayList<>()).add(rows.getString("column_name"));
                }
            }
        }
    }

    /** Returns the numeric field of a catalogue row, or null where the catalogue reports none. */
    private static Integer boxedInt(ResultSet rows, String label) throws SQLException {
        int value = rows.getInt(label);
        return rows.wasNull() ? null : value;
    }

    private static String key(String table, String column) {
        return table + "." + column;
    }

    /** Returns the catalogue row of one column, failing the test when the schema holds none. */
    private ColumnShape shape(String table, String column) {
        ColumnShape shape = columns.get(key(table, column));
        assertThat(shape).as("catalogue row for %s.%s.%s", ACCOUNT_SCHEMA, table, column)
                .isNotNull();
        return shape;
    }

    /** Asserts the type name and the declared length of one character column. */
    private void assertText(String table, String column, String dataType, int length,
                            String source) {
        ColumnShape shape = shape(table, column);
        assertThat(shape.dataType()).as("type of %s.%s, from %s", table, column, source)
                .isEqualTo(dataType);
        assertThat(shape.textLength()).as("length of %s.%s, from %s", table, column, source)
                .isEqualTo(length);
    }

    /** Asserts the type name, the precision and the scale of one decimal column. */
    private void assertDecimal(String table, String column, int precision, int scale,
                               String source) {
        ColumnShape shape = shape(table, column);
        assertThat(shape.dataType()).as("type of %s.%s, from %s", table, column, source)
                .isEqualTo(DECIMAL);
        assertThat(shape.precision()).as("precision of %s.%s, from %s", table, column, source)
                .isEqualTo(precision);
        assertThat(shape.scale()).as("scale of %s.%s, from %s", table, column, source)
                .isEqualTo(scale);
    }

    /** Asserts the declared type of one field of one entity class. */
    private static void assertFieldType(Class<?> owner, String field, Class<?> expected,
                                        String source) throws NoSuchFieldException {
        assertThat(owner.getDeclaredField(field).getType())
                .as("declared type of %s.%s, from %s", owner.getSimpleName(), field, source)
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}.{1} is ten characters of varying text, from {2}")
    @MethodSource("calendarColumns")
    void calendarColumnsHoldTenCharactersOfText(String table, String column, String source) {
        assertText(table, column, VARYING_TEXT, CALENDAR_LENGTH, source);
    }

    @ParameterizedTest(name = "{0}.{1} reports no date and no timestamp type, from {2}")
    @MethodSource("calendarColumns")
    void calendarColumnsReportNoDateType(String table, String column, String source) {
        assertThat(shape(table, column).dataType())
                .as("type of %s.%s, from %s", table, column, source)
                .isNotIn(DATE_AND_TIMESTAMP_TYPES);
    }

    @ParameterizedTest(name = "account.{0} is a decimal of twelve digits and scale two, from {1}")
    @CsvSource({
        "current_balance,      ACCT-CURR-BAL PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L7",
        "credit_limit,         ACCT-CREDIT-LIMIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L8",
        "cash_credit_limit,    ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L9",
        "current_cycle_credit, ACCT-CURR-CYC-CREDIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L13",
        "current_cycle_debit,  ACCT-CURR-CYC-DEBIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L14"
    })
    void accountMoneyColumnsCarryTwelveDigitsAndScaleTwo(String column, String source) {
        assertDecimal("account", column, 12, 2, source);
    }

    @Test
    @DisplayName("disclosure_group.interest_rate is a decimal of six digits and scale two")
    void interestRateCarriesSixDigitsAndScaleTwo() {
        assertDecimal("disclosure_group", "interest_rate", 6, 2,
                "DIS-INT-RATE PIC S9(04)V99 at app/cpy/CVTRA02Y.cpy:L9");
    }

    @Test
    @DisplayName("customer.fico_credit_score is a decimal of three digits and scale zero")
    void creditScoreCarriesThreeDigitsAndScaleZero() {
        assertDecimal("customer", "fico_credit_score", 3, 0,
                "CUST-FICO-CREDIT-SCORE PIC 9(03) at app/cpy/CVCUS01Y.cpy:L22");
    }

    @Test
    @DisplayName("customer.social_security_number is nine characters of fixed-length text")
    void socialSecurityNumberColumnHoldsNineCharacters() {
        assertText("customer", "social_security_number", FIXED_TEXT, 9,
                "CUST-SSN PIC 9(09) at app/cpy/CVCUS01Y.cpy:L17");
    }

    @Test
    @DisplayName("customer.government_issued_id is twenty characters of varying text")
    void governmentIssuedIdColumnHoldsTwentyCharacters() {
        assertText("customer", "government_issued_id", VARYING_TEXT, 20,
                "CUST-GOVT-ISSUED-ID PIC X(20) at app/cpy/CVCUS01Y.cpy:L18");
    }

    @Test
    @DisplayName("account.account_id is eleven characters of fixed-length text, matching KEYS(11 0)")
    void accountIdentifierColumnHoldsElevenCharacters() {
        assertText("account", "account_id", FIXED_TEXT, 11,
                "ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5 with KEYS(11 0) at "
                        + "app/jcl/ACCTFILE.jcl:L40");
    }

    @Test
    @DisplayName("customer.customer_id is nine characters of fixed-length text, matching KEYS(9 0)")
    void customerIdentifierColumnHoldsNineCharacters() {
        assertText("customer", "customer_id", FIXED_TEXT, 9,
                "CUST-ID PIC 9(09) at app/cpy/CVCUS01Y.cpy:L5 with KEYS(9 0) at "
                        + "app/jcl/CUSTFILE.jcl:L50");
    }

    @Test
    @DisplayName("the three disclosure_group key columns hold sixteen characters together")
    void disclosureGroupKeyColumnsHoldSixteenCharacters() {
        assertText("disclosure_group", "account_group_id", VARYING_TEXT, 10,
                "DIS-ACCT-GROUP-ID PIC X(10) at app/cpy/CVTRA02Y.cpy:L6");
        assertText("disclosure_group", "transaction_type_code", FIXED_TEXT, 2,
                "DIS-TRAN-TYPE-CD PIC X(02) at app/cpy/CVTRA02Y.cpy:L7");
        assertText("disclosure_group", "transaction_category_code", FIXED_TEXT, 4,
                "DIS-TRAN-CAT-CD PIC 9(04) at app/cpy/CVTRA02Y.cpy:L8");

        int keyWidth = shape("disclosure_group", "account_group_id").textLength()
                + shape("disclosure_group", "transaction_type_code").textLength()
                + shape("disclosure_group", "transaction_category_code").textLength();
        assertThat(keyWidth)
                .as("key width of disclosure_group, from KEYS(16 0) at app/jcl/DISCGRP.jcl:L40")
                .isEqualTo(16);
    }

    @ParameterizedTest(name = "the primary key of {0} is composed of {1}, from {2}")
    @CsvSource({
        "account,          account_id,  ACCT-ID at app/cpy/CVACT01Y.cpy:L5",
        "customer,         customer_id, CUST-ID at app/cpy/CVCUS01Y.cpy:L5",
        "disclosure_group, 'account_group_id transaction_type_code transaction_category_code', "
                + "DIS-GROUP-KEY at app/cpy/CVTRA02Y.cpy:L5-L8",
        "outbox_event,     event_id,    " + ADDITIVE_SOURCE,
        "processed_event,  event_id,    " + ADDITIVE_SOURCE
    })
    void primaryKeyIsComposedOfTheKeyColumns(String table, String keyColumns, String source) {
        assertThat(primaryKeys.get(table))
                .as("primary key columns of %s.%s in key order, from %s", ACCOUNT_SCHEMA, table,
                        source)
                .containsExactly(keyColumns.split(" "));
    }

    @ParameterizedTest(name = "account.{0} is {1} of length {2}, from {3}")
    @CsvSource({
        "active_status, character,         1,  ACCT-ACTIVE-STATUS PIC X(01) at app/cpy/CVACT01Y.cpy:L6",
        "address_zip,   character varying, 10, ACCT-ADDR-ZIP PIC X(10) at app/cpy/CVACT01Y.cpy:L15",
        "group_id,      character varying, 10, ACCT-GROUP-ID PIC X(10) at app/cpy/CVACT01Y.cpy:L16"
    })
    void accountTextColumnsMatchTheirPictureClauses(String column, String dataType, int length,
                                                    String source) {
        assertText("account", column, dataType, length, source);
    }

    @ParameterizedTest(name = "customer.{0} is {1} of length {2}, from {3}")
    @CsvSource({
        "first_name,           character varying, 25, CUST-FIRST-NAME PIC X(25) at app/cpy/CVCUS01Y.cpy:L6",
        "middle_name,          character varying, 25, CUST-MIDDLE-NAME PIC X(25) at app/cpy/CVCUS01Y.cpy:L7",
        "last_name,            character varying, 25, CUST-LAST-NAME PIC X(25) at app/cpy/CVCUS01Y.cpy:L8",
        "address_line_1,       character varying, 50, CUST-ADDR-LINE-1 PIC X(50) at app/cpy/CVCUS01Y.cpy:L9",
        "address_line_2,       character varying, 50, CUST-ADDR-LINE-2 PIC X(50) at app/cpy/CVCUS01Y.cpy:L10",
        "address_city,         character varying, 50, CUST-ADDR-LINE-3 PIC X(50) at app/cpy/CVCUS01Y.cpy:L11",
        "address_state_code,   character,          2, CUST-ADDR-STATE-CD PIC X(02) at app/cpy/CVCUS01Y.cpy:L12",
        "address_country_code, character,          3, CUST-ADDR-COUNTRY-CD PIC X(03) at app/cpy/CVCUS01Y.cpy:L13",
        "address_zip,          character varying, 10, CUST-ADDR-ZIP PIC X(10) at app/cpy/CVCUS01Y.cpy:L14",
        "phone_number_1,       character varying, 15, CUST-PHONE-NUM-1 PIC X(15) at app/cpy/CVCUS01Y.cpy:L15",
        "phone_number_2,       character varying, 15, CUST-PHONE-NUM-2 PIC X(15) at app/cpy/CVCUS01Y.cpy:L16",
        "eft_account_id,       character varying, 10, CUST-EFT-ACCOUNT-ID PIC X(10) at app/cpy/CVCUS01Y.cpy:L20",
        "primary_card_holder_indicator, character, 1, CUST-PRI-CARD-HOLDER-IND PIC X(01) at app/cpy/CVCUS01Y.cpy:L21"
    })
    void customerTextColumnsMatchTheirPictureClauses(String column, String dataType, int length,
                                                     String source) {
        assertText("customer", column, dataType, length, source);
    }

    @ParameterizedTest(name = "outbox_event.{0} is {1}")
    @CsvSource({
        "event_type,   character varying",
        "aggregate_id, character",
        "payload,      text",
        "published,    boolean",
        "created_at,   timestamp with time zone",
        "published_at, timestamp with time zone"
    })
    void outboxEventColumnsCarryTheirDeclaredTypes(String column, String dataType) {
        assertThat(shape("outbox_event", column).dataType())
                .as("type of outbox_event.%s, from %s", column, ADDITIVE_SOURCE)
                .isEqualTo(dataType);
    }

    @Test
    @DisplayName("outbox_event.aggregate_id is as wide as account.account_id")
    void outboxAggregateIdentifierIsAsWideAsTheAccountKey() {
        assertThat(shape("outbox_event", "aggregate_id").textLength())
                .as("length of outbox_event.aggregate_id against account.account_id")
                .isEqualTo(shape("account", "account_id").textLength());
    }

    @Test
    @DisplayName("outbox_event.event_type is as long as OutboxEventEntity declares")
    void outboxEventTypeLengthMatchesTheEntityBound() {
        assertThat(shape("outbox_event", "event_type").textLength())
                .as("length of outbox_event.event_type against "
                        + "OutboxEventEntity.EVENT_TYPE_MAX_LENGTH")
                .isEqualTo(OutboxEventEntity.EVENT_TYPE_MAX_LENGTH);
    }

    @Test
    @DisplayName("outbox_event.published rejects a null and outbox_event.published_at accepts one")
    void publicationColumnsDeclareTheirNullability() {
        assertThat(shape("outbox_event", "published").nullable())
                .as("nullability of outbox_event.published, from %s", ADDITIVE_SOURCE).isFalse();
        assertThat(shape("outbox_event", "published_at").nullable())
                .as("nullability of outbox_event.published_at, from %s", ADDITIVE_SOURCE).isTrue();
    }

    @Test
    @DisplayName("processed_event.processed_at is a timestamp that rejects a null")
    void processedTimestampIsATimestampThatRejectsANull() {
        ColumnShape shape = shape("processed_event", "processed_at");
        assertThat(shape.dataType())
                .as("type of processed_event.processed_at, from %s", ADDITIVE_SOURCE)
                .isEqualTo(TIMESTAMP_WITH_ZONE);
        assertThat(shape.nullable())
                .as("nullability of processed_event.processed_at, from %s", ADDITIVE_SOURCE)
                .isFalse();
    }

    @Test
    @DisplayName("AccountEntity.accountId is a String field, holding all eleven key characters")
    void accountIdentifierFieldHoldsText() throws NoSuchFieldException {
        assertFieldType(AccountEntity.class, "accountId", String.class,
                "ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5");
    }

    @Test
    @DisplayName("CustomerEntity.customerId is a String field, holding all nine key characters")
    void customerIdentifierFieldHoldsText() throws NoSuchFieldException {
        assertFieldType(CustomerEntity.class, "customerId", String.class,
                "CUST-ID PIC 9(09) at app/cpy/CVCUS01Y.cpy:L5");
    }

    @ParameterizedTest(name = "AccountEntity.{0} is a fixed-point decimal field, from {1}")
    @CsvSource({
        "currentBalance,      ACCT-CURR-BAL PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L7",
        "creditLimit,         ACCT-CREDIT-LIMIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L8",
        "cashCreditLimit,     ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L9",
        "currentCycleCredit,  ACCT-CURR-CYC-CREDIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L13",
        "currentCycleDebit,   ACCT-CURR-CYC-DEBIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L14"
    })
    void accountMoneyFieldsAreFixedPointDecimals(String field, String source)
            throws NoSuchFieldException {
        assertFieldType(AccountEntity.class, field, BigDecimal.class, source);
    }
}
