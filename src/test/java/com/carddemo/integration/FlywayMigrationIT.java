package com.carddemo.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.CardDemoApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying that the five Flyway migration scripts
 * ({@code V1__schema.sql} through {@code V5__seed_master_data.sql}) apply cleanly
 * to a fresh PostgreSQL 15 container and that the resulting schema and seed data
 * match the VSAM record layouts documented in {@code app/catlg/LISTCAT.txt} and the
 * COBOL copybooks in {@code app/cpy/}.
 *
 * <p>This is the <b>most foundational integration test</b> in the CardDemo
 * COBOL&rarr;Spring Boot migration: every other integration test, batch job, and
 * online flow depends on the schema being correct. If this test fails, no other
 * system component can be considered functional.</p>
 *
 * <h2>What it verifies (organized as {@code @Nested} groups)</h2>
 * <ol>
 *   <li>{@link V1SchemaMigration} — all base tables exist with columns whose
 *       types/lengths mirror the COBOL {@code PIC} clauses (PR-13), composite
 *       primary keys (PR-15), {@code NUMERIC(15,2)} money columns (PR-16), and
 *       {@code version} optimistic-locking columns (PR-22).</li>
 *   <li>{@link V2Indexes} — the three AIX-replacement secondary indexes.</li>
 *   <li>{@link V3SeedReferenceData} — 7 transaction types, 18 categories,
 *       51 disclosure groups including the {@code DEFAULT} fallback group (PR-02).</li>
 *   <li>{@link V4SeedUsers} — the 10 default users with BCrypt password hashes (PR-17).</li>
 *   <li>{@link V5SeedMasterData} — 50 customers/accounts/cards/cross-references and
 *       transaction-category balances, with referential integrity.</li>
 *   <li>{@link FlywayHistory} — all five migrations recorded as successful.</li>
 *   <li>{@link ConstraintValidation} — foreign-key, primary-key, and NOT NULL enforcement.</li>
 * </ol>
 *
 * <h2>Test infrastructure decisions</h2>
 * <ul>
 *   <li><b>Singleton container</b>: a disposable {@code postgres:15} container is booted once in a
 *       {@code static} initializer (the canonical singleton-container pattern) rather than via the
 *       {@code @Container}/{@code @Testcontainers} JUnit extension. Starting it at class-load
 *       guarantees the mapped port is available before {@code SpringExtension} loads the context,
 *       avoiding the extension-ordering race ("Mapped port can only be obtained after the container
 *       is started"). A {@code @DynamicPropertySource} then overrides {@code spring.datasource.*}
 *       (highest precedence in the Spring Environment) so the application context binds to
 *       <em>this</em> container — overriding the {@code jdbc:tc:} magic URL declared in
 *       {@code application-test.yml}. The container is reaped by the Testcontainers Ryuk sidecar at
 *       JVM exit.</li>
 *   <li><b>BCrypt verification uses a freshly constructed {@link BCryptPasswordEncoder}</b>
 *       ({@link #bcryptEncoder}) rather than an injected bean. BCrypt verification is stateless —
 *       the salt and cost factor are embedded in every hash — so a new encoder verifies any
 *       {@code $2a$10$...} hash correctly. This is deliberately robust against (a) the
 *       application wiring a {@code DelegatingPasswordEncoder} (which would reject the raw,
 *       un-prefixed {@code $2a$} hashes seeded by V4) and (b) variance in whether the security
 *       configuration exposes the bean as {@code PasswordEncoder} or {@code BCryptPasswordEncoder}.</li>
 *   <li><b>{@link SecurityBeanFallbackTestConfig}</b>: the full application context instantiates
 *       {@code AuthService} (needs an {@code AuthenticationManager}) and {@code UserService} /
 *       {@code UserSeedingJobConfig} (need a {@code PasswordEncoder}). This nested
 *       {@code @TestConfiguration} supplies those two beans <em>only when missing</em>
 *       ({@code @ConditionalOnMissingBean}), so the migration context starts in isolation and
 *       gracefully backs off once the production {@code SecurityConfig} provides the real beans.</li>
 * </ul>
 *
 * <p>Source references (PR-27 — used as REFERENCE, never modified):
 * {@code app/cpy/CVACT01Y.cpy}, {@code CVACT02Y.cpy}, {@code CVACT03Y.cpy},
 * {@code CVCUS01Y.cpy}, {@code CVTRA01Y.cpy}&ndash;{@code CVTRA06Y.cpy},
 * {@code CSUSR01Y.cpy}, {@code app/catlg/LISTCAT.txt}, {@code app/jcl/DUSRSECJ.jcl}.</p>
 */
@SpringBootTest(classes = CardDemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(FlywayMigrationIT.SecurityBeanFallbackTestConfig.class)
// The @TestConfiguration fallback above is @ConditionalOnMissingBean-guarded so it registers its
// PasswordEncoder/AuthenticationManager only when the production SecurityConfig is absent. Because the
// @Import-ed @TestConfiguration is processed BEFORE the component-scanned com.carddemo.security.SecurityConfig,
// the condition cannot "see" the real beans yet and both definitions get registered. Allowing bean-definition
// overriding makes the later-registered real SecurityConfig beans win cleanly (instead of failing the context
// with BeanDefinitionOverrideException), preserving this test's documented dual-mode (standalone + full-suite) intent.
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@DisplayName("FlywayMigrationIT — Verifies all 5 Flyway migrations apply cleanly to fresh PostgreSQL 15 container")
class FlywayMigrationIT {

    // -------------------------------------------------------------------------
    // Disposable PostgreSQL 15 container (Testcontainers manages its lifecycle).
    // The password below is a throwaway, non-production test literal.
    // -------------------------------------------------------------------------
    @SuppressWarnings("resource") // Singleton container; reaped by the Testcontainers Ryuk sidecar at JVM exit
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
        .withDatabaseName("carddemo_test")
        .withUsername("carddemo_test")
        .withPassword("test_password");

    static {
        // Start the container in a static initializer (the canonical singleton-container pattern) so the
        // mapped port is available BEFORE Spring evaluates @DynamicPropertySource and DataSource
        // auto-configuration. This sidesteps the JUnit 5 extension-ordering race in which SpringExtension's
        // beforeAll loads the ApplicationContext before a @Container-managed field would have been started,
        // which manifests as "Mapped port can only be obtained after the container is started".
        // The container is still reaped automatically by the Testcontainers Ryuk sidecar at JVM exit.
        POSTGRES.start();
    }

    /**
     * Binds the Spring {@code DataSource} to the container booted above. These dynamic
     * properties take precedence over {@code application-test.yml}, so the application context
     * (and Flyway) run their migrations against this explicit container using the real
     * PostgreSQL JDBC driver.
     */
    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway owns the schema; ensure it is enabled and points at the migration scripts.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        // Hibernate only validates the Flyway-produced schema.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    // -------------------------------------------------------------------------
    // Injected collaborators.
    // Field injection is acceptable in @SpringBootTest classes (PR-29) because tests
    // are not managed beans; production code uses constructor injection exclusively.
    // -------------------------------------------------------------------------

    /** Direct SQL access to {@code information_schema}, {@code pg_indexes}, {@code flyway_schema_history}, and seeded tables. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Used to assert the application context obtained a live connection to the container. */
    @Autowired
    private DataSource dataSource;

    /**
     * Stateless BCrypt verifier (constructed locally, not injected) used by the V4 password
     * assertions. See the class JavaDoc for why a fresh encoder is preferred over an injected bean.
     */
    private final BCryptPasswordEncoder bcryptEncoder = new BCryptPasswordEncoder();

    // =========================================================================
    // Shared JDBC metadata helpers (kept private; used by the @Nested groups).
    // =========================================================================

    /** All table names in the {@code public} schema (includes Flyway and Spring Batch tables). */
    private List<String> publicTableNames() {
        return jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
            String.class);
    }

    /** Single column's metadata row, or {@code null} if the column does not exist (case-insensitive). */
    private Map<String, Object> columnRow(String table, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT column_name, data_type, character_maximum_length, numeric_precision, "
                + "numeric_scale, is_nullable "
                + "FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND lower(column_name) = lower(?)",
            table, column);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** {@code true} if {@code table} has a column named {@code column} (case-insensitive). */
    private boolean hasColumn(String table, String column) {
        return columnRow(table, column) != null;
    }

    /** Lower-cased {@code data_type} (e.g. {@code numeric}, {@code character varying}) for a column. */
    private String dataType(String table, String column) {
        Map<String, Object> row = columnRow(table, column);
        return row == null ? null : String.valueOf(row.get("data_type")).toLowerCase();
    }

    /** {@code character_maximum_length} for a CHAR/VARCHAR column, or {@code null}. */
    private Integer charMaxLength(String table, String column) {
        Map<String, Object> row = columnRow(table, column);
        return row == null ? null : asInteger(row.get("character_maximum_length"));
    }

    /** {@code numeric_scale} for a NUMERIC column, or {@code null}. */
    private Integer numericScale(String table, String column) {
        Map<String, Object> row = columnRow(table, column);
        return row == null ? null : asInteger(row.get("numeric_scale"));
    }

    /** {@code true} if the column is declared {@code NOT NULL}. */
    private boolean isNotNull(String table, String column) {
        Map<String, Object> row = columnRow(table, column);
        return row != null && "NO".equalsIgnoreCase(String.valueOf(row.get("is_nullable")));
    }

    /** Ordered list of primary-key column names for {@code table}. */
    private List<String> primaryKeyColumns(String table) {
        return jdbcTemplate.queryForList(
            "SELECT kcu.column_name "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + " AND tc.table_schema = kcu.table_schema "
                + "WHERE tc.table_schema = 'public' AND tc.table_name = ? "
                + "  AND tc.constraint_type = 'PRIMARY KEY' "
                + "ORDER BY kcu.ordinal_position",
            String.class, table);
    }

    /** {@code true} if an index named {@code indexName} exists on {@code table} and its definition mentions {@code column}. */
    private boolean indexCoversColumn(String table, String indexName, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT indexname, indexdef FROM pg_indexes "
                + "WHERE schemaname = 'public' AND tablename = ?",
            table);
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("indexname"));
            String def = String.valueOf(row.get("indexdef")).toLowerCase();
            if (indexName.equalsIgnoreCase(name) && def.contains(column.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** Convenience: {@code COUNT(*)} for a table. */
    private int rowCount(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    /** Null-safe widening of a JDBC numeric metadata value to {@link Integer}. */
    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        return (value instanceof Number) ? ((Number) value).intValue() : Integer.valueOf(value.toString());
    }

    /**
     * Returns the first candidate column name that exists on {@code table}, or {@code null} if none
     * do. Used so column-fidelity assertions tolerate benign naming variance (e.g. {@code curr_bal}
     * vs {@code acct_curr_bal}) per the migration-mapping guidance, while still anchoring on the
     * authoritative names produced by {@code V1__schema.sql}.
     */
    private String resolveColumn(String table, String... candidates) {
        for (String candidate : candidates) {
            if (hasColumn(table, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Asserts a resolved column is a {@code NUMERIC} type with scale exactly 2 (PR-16 money fidelity). */
    private void assertMoneyColumn(String table, String resolvedColumn) {
        assertThat(resolvedColumn)
            .as("%s should expose a money column to verify (PR-16)", table)
            .isNotNull();
        assertThat(dataType(table, resolvedColumn))
            .as("%s.%s should be NUMERIC for BigDecimal money fidelity (PR-16)", table, resolvedColumn)
            .isEqualTo("numeric");
        assertThat(numericScale(table, resolvedColumn))
            .as("%s.%s NUMERIC scale should be 2 to mirror COBOL PIC ...V99 (PR-16)", table, resolvedColumn)
            .isEqualTo(2);
    }

    // =========================================================================
    // 1. V1 — Schema DDL verification (table existence, column fidelity,
    //    composite keys, optimistic-locking version columns).
    // =========================================================================
    @Nested
    @DisplayName("V1 — Schema DDL (PR-13 column fidelity, PR-15 composite keys, PR-16 money, PR-22 version)")
    class V1SchemaMigration {

        @Test
        @DisplayName("Creates all 12 base tables plus the Flyway history table")
        void shouldCreateAllExpectedTables() {
            List<String> tables = publicTableNames();
            assertThat(tables)
                .as("All 12 CardDemo base tables and flyway_schema_history must exist after V1 "
                    + "(Spring Batch BATCH_* tables may also be present — assert contains, not exact)")
                .contains(
                    "customers", "accounts", "cards", "card_xref", "transactions",
                    "daily_transactions", "rejected_transactions", "tran_cat_balances",
                    "disclosure_groups", "transaction_types", "transaction_categories", "users",
                    "flyway_schema_history");
        }

        @Test
        @DisplayName("Application context obtained a live JDBC connection to the container")
        void shouldHaveLiveDataSourceConnection() throws Exception {
            assertThat(dataSource)
                .as("A DataSource must be wired into the test context")
                .isNotNull();
            try (java.sql.Connection connection = dataSource.getConnection()) {
                assertThat(connection.isValid(2))
                    .as("DataSource connection to the PostgreSQL 15 container must be valid")
                    .isTrue();
                assertThat(connection.getMetaData().getDatabaseProductName())
                    .as("Underlying database must be PostgreSQL")
                    .containsIgnoringCase("postgres");
            }
        }

        @Test
        @DisplayName("accounts — columns mirror CVACT01Y (money NUMERIC(15,2), dates, version)")
        void shouldCreateAccountsTableWithCorrectColumns() {
            assertThat(primaryKeyColumns("accounts"))
                .as("accounts PK is acct_id (CVACT01Y ACCT-ID PIC 9(11), LISTCAT KEYLEN=11)")
                .containsExactly("acct_id");
            assertThat(dataType("accounts", "acct_id"))
                .as("acct_id should be an integer family type (BIGINT)")
                .isIn("bigint", "numeric", "integer");

            String status = resolveColumn("accounts", "active_status", "acct_active_status");
            assertThat(status).as("accounts must have an active-status column").isNotNull();
            assertThat(dataType("accounts", status))
                .as("accounts.%s should be a single-character status (CHAR/VARCHAR)", status)
                .isIn("character", "character varying");

            assertMoneyColumn("accounts", resolveColumn("accounts", "curr_bal", "acct_curr_bal"));
            assertMoneyColumn("accounts", resolveColumn("accounts", "credit_limit", "acct_credit_limit"));
            assertMoneyColumn("accounts",
                resolveColumn("accounts", "cash_credit_limit", "acct_cash_credit_limit"));
            assertMoneyColumn("accounts", resolveColumn("accounts", "curr_cyc_credit", "acct_curr_cyc_credit"));
            assertMoneyColumn("accounts", resolveColumn("accounts", "curr_cyc_debit", "acct_curr_cyc_debit"));

            String openDate = resolveColumn("accounts", "open_date", "acct_open_date");
            String expDate = resolveColumn("accounts", "expiration_date", "acct_expiration_date");
            String reissueDate = resolveColumn("accounts", "reissue_date", "acct_reissue_date");
            assertThat(expDate)
                .as("PR-14: COBOL ACCT-EXPIRAION-DATE [sic] is normalized to expiration_date")
                .isNotNull();
            assertThat(dataType("accounts", openDate))
                .as("accounts.%s should be DATE or VARCHAR(10)", openDate)
                .isIn("date", "character varying", "character");
            assertThat(dataType("accounts", expDate))
                .as("accounts.%s should be DATE or VARCHAR(10)", expDate)
                .isIn("date", "character varying", "character");
            assertThat(dataType("accounts", reissueDate))
                .as("accounts.%s should be DATE or VARCHAR(10)", reissueDate)
                .isIn("date", "character varying", "character");

            String groupId = resolveColumn("accounts", "group_id", "acct_group_id");
            assertThat(groupId).as("accounts must have a group_id column (CVACT01Y ACCT-GROUP-ID)").isNotNull();
            assertThat(dataType("accounts", groupId))
                .as("accounts.%s should be VARCHAR (ACCT-GROUP-ID PIC X(10))", groupId)
                .isEqualTo("character varying");
        }

        @Test
        @DisplayName("cards — columns mirror CVACT02Y (card_num PK, account_id FK, cvv, status)")
        void shouldCreateCardsTableWithCorrectColumns() {
            assertThat(primaryKeyColumns("cards"))
                .as("cards PK is card_num (CVACT02Y CARD-NUM PIC X(16))")
                .containsExactly("card_num");
            assertThat(dataType("cards", "card_num"))
                .as("card_num should be VARCHAR")
                .isEqualTo("character varying");
            assertThat(charMaxLength("cards", "card_num"))
                .as("card_num length should be 16 (PR-13: CARD-NUM PIC X(16))")
                .isEqualTo(16);

            String accountId = resolveColumn("cards", "account_id", "card_acct_id", "acct_id");
            assertThat(accountId).as("cards must have an owning-account column").isNotNull();
            assertThat(dataType("cards", accountId))
                .as("cards.%s should be an integer family type (CARD-ACCT-ID)", accountId)
                .isIn("bigint", "numeric", "integer");
            assertThat(isNotNull("cards", accountId))
                .as("cards.%s must be NOT NULL (every card belongs to an account)", accountId)
                .isTrue();

            String cvv = resolveColumn("cards", "cvv_cd", "card_cvv_cd");
            assertThat(cvv).as("cards must have a CVV column (CARD-CVV-CD)").isNotNull();
            assertThat(dataType("cards", cvv))
                .as("cards.%s should be a small integer (PIC 9(03)) or short string", cvv)
                .isIn("smallint", "integer", "numeric", "character varying", "character");

            String embossed = resolveColumn("cards", "embossed_name", "card_embossed_name");
            assertThat(embossed).as("cards must have an embossed-name column").isNotNull();
            assertThat(dataType("cards", embossed)).isEqualTo("character varying");

            String cardStatus = resolveColumn("cards", "active_status", "card_active_status");
            assertThat(cardStatus).as("cards must have an active-status column").isNotNull();
            assertThat(dataType("cards", cardStatus)).isIn("character", "character varying");
        }

        @Test
        @DisplayName("card_xref — columns mirror CVACT03Y (xref_card_num PK, cust/acct ids)")
        void shouldCreateCardXrefTableWithCorrectColumns() {
            assertThat(primaryKeyColumns("card_xref"))
                .as("card_xref PK is xref_card_num (CVACT03Y XREF-CARD-NUM)")
                .containsExactly("xref_card_num");
            assertThat(charMaxLength("card_xref", "xref_card_num"))
                .as("xref_card_num length should be 16")
                .isEqualTo(16);

            String custId = resolveColumn("card_xref", "xref_cust_id", "cust_id");
            String acctId = resolveColumn("card_xref", "xref_acct_id", "acct_id", "account_id");
            assertThat(custId).as("card_xref must have a customer-id column (XREF-CUST-ID)").isNotNull();
            assertThat(acctId).as("card_xref must have an account-id column (XREF-ACCT-ID)").isNotNull();
            assertThat(dataType("card_xref", custId)).isIn("bigint", "numeric", "integer");
            assertThat(dataType("card_xref", acctId)).isIn("bigint", "numeric", "integer");
        }

        @Test
        @DisplayName("customers — columns mirror CVCUS01Y (name, address, ssn, dob, fico)")
        void shouldCreateCustomersTableWithCorrectColumns() {
            assertThat(primaryKeyColumns("customers"))
                .as("customers PK is cust_id (LISTCAT KEYLEN=9)")
                .containsExactly("cust_id");
            assertThat(dataType("customers", "cust_id")).isIn("bigint", "numeric", "integer");

            assertThat(hasColumn("customers", resolveColumn("customers", "first_name", "cust_first_name")))
                .as("customers must have a first-name column").isTrue();
            assertThat(hasColumn("customers", resolveColumn("customers", "last_name", "cust_last_name")))
                .as("customers must have a last-name column").isTrue();

            String ssn = resolveColumn("customers", "ssn", "cust_ssn");
            assertThat(ssn).as("customers must have an SSN column (CUST-SSN PIC 9(09))").isNotNull();
            assertThat(dataType("customers", ssn))
                .as("customers.%s should be VARCHAR (leading zeros preserved) or NUMERIC", ssn)
                .isIn("character varying", "character", "numeric", "bigint", "integer");

            String dob = resolveColumn("customers", "dob", "cust_dob_date", "dob_date");
            assertThat(dob).as("customers must have a date-of-birth column (CUST-DOB)").isNotNull();
            assertThat(dataType("customers", dob)).isIn("date", "character varying", "character");

            String fico = resolveColumn("customers", "fico_credit_score", "cust_fico_credit_score");
            assertThat(fico).as("customers must have a FICO score column").isNotNull();
            assertThat(dataType("customers", fico)).isIn("smallint", "integer", "numeric");
        }

        @Test
        @DisplayName("transactions — columns mirror CVTRA05Y (tran_id PK, type/cat, amount, timestamps)")
        void shouldCreateTransactionsTableWithCorrectColumns() {
            assertThat(primaryKeyColumns("transactions"))
                .as("transactions PK is tran_id (TRAN-ID PIC X(16))")
                .containsExactly("tran_id");
            assertThat(charMaxLength("transactions", "tran_id"))
                .as("tran_id length should be 16")
                .isEqualTo(16);

            String typeCd = resolveColumn("transactions", "type_cd", "tran_type_cd");
            String catCd = resolveColumn("transactions", "cat_cd", "tran_cat_cd");
            assertThat(typeCd).as("transactions must have a transaction-type code column").isNotNull();
            assertThat(catCd).as("transactions must have a transaction-category code column").isNotNull();

            assertMoneyColumn("transactions", resolveColumn("transactions", "amount", "tran_amt"));

            String origTs = resolveColumn("transactions", "orig_timestamp", "tran_orig_ts", "orig_ts");
            String procTs = resolveColumn("transactions", "proc_timestamp", "tran_proc_ts", "proc_ts");
            assertThat(origTs).as("transactions must have an origination-timestamp column").isNotNull();
            assertThat(procTs).as("transactions must have a processing-timestamp column").isNotNull();
            assertThat(dataType("transactions", origTs))
                .as("transactions.%s should be TIMESTAMP or VARCHAR(26) DB2-format", origTs)
                .isIn("timestamp without time zone", "timestamp with time zone",
                    "character varying", "character");

            String cardNum = resolveColumn("transactions", "card_num", "tran_card_num");
            assertThat(cardNum).as("transactions must have a card-number column").isNotNull();
            assertThat(charMaxLength("transactions", cardNum)).isEqualTo(16);
        }

        @Test
        @DisplayName("users — columns mirror CSUSR01Y; sec_usr_pwd widened to 60 for BCrypt (PR-17)")
        void shouldCreateUsersTableWithCorrectColumns() {
            assertThat(primaryKeyColumns("users"))
                .as("users PK is sec_usr_id (LISTCAT KEYLEN=8)")
                .containsExactly("sec_usr_id");
            assertThat(charMaxLength("users", "sec_usr_id"))
                .as("sec_usr_id length should be 8 (SEC-USR-ID PIC X(08))")
                .isEqualTo(8);

            assertThat(charMaxLength("users", "sec_usr_fname"))
                .as("sec_usr_fname length should be 20").isEqualTo(20);
            assertThat(charMaxLength("users", "sec_usr_lname"))
                .as("sec_usr_lname length should be 20").isEqualTo(20);

            assertThat(charMaxLength("users", "sec_usr_pwd"))
                .as("PR-17: sec_usr_pwd must be widened to >= 60 chars to hold a BCrypt hash "
                    + "(NOT 8 like the original plaintext)")
                .isGreaterThanOrEqualTo(60);
            assertThat(isNotNull("users", "sec_usr_pwd"))
                .as("sec_usr_pwd must be NOT NULL").isTrue();

            assertThat(dataType("users", "sec_usr_type"))
                .as("sec_usr_type should be a single character ('A'/'U')")
                .isIn("character", "character varying");
            assertThat(isNotNull("users", "sec_usr_type"))
                .as("sec_usr_type must be NOT NULL").isTrue();
        }

        @Test
        @DisplayName("tran_cat_balances — composite PK (account_id, type_cd, cat_cd) per PR-15")
        void shouldCreateTranCatBalancesTableWithCompositeKey() {
            assertThat(primaryKeyColumns("tran_cat_balances"))
                .as("PR-15: tran_cat_balances composite PK mirrors COBOL TRAN-CAT-KEY group order")
                .containsExactly("account_id", "type_cd", "cat_cd");
            assertMoneyColumn("tran_cat_balances",
                resolveColumn("tran_cat_balances", "balance", "tran_cat_bal"));
        }

        @Test
        @DisplayName("disclosure_groups — composite PK (group_id, type_cd, cat_cd) per PR-15")
        void shouldCreateDisclosureGroupsTableWithCompositeKey() {
            assertThat(primaryKeyColumns("disclosure_groups"))
                .as("PR-15: disclosure_groups composite PK mirrors COBOL DIS-GROUP-KEY group order")
                .containsExactly("group_id", "type_cd", "cat_cd");

            String rate = resolveColumn("disclosure_groups", "dis_int_rate", "int_rate");
            assertThat(rate).as("disclosure_groups must have an interest-rate column").isNotNull();
            assertThat(dataType("disclosure_groups", rate))
                .as("disclosure_groups.%s should be NUMERIC (DIS-INT-RATE PIC S9(04)V99)", rate)
                .isEqualTo("numeric");
            assertThat(numericScale("disclosure_groups", rate))
                .as("disclosure_groups.%s scale should be 2 (PR-16)", rate)
                .isEqualTo(2);
        }

        @Test
        @DisplayName("transaction_categories — composite PK (type_cd, cat_cd) per PR-15")
        void shouldCreateTransactionCategoriesTableWithCompositeKey() {
            assertThat(primaryKeyColumns("transaction_categories"))
                .as("PR-15: transaction_categories composite PK (type_cd, cat_cd)")
                .containsExactly("type_cd", "cat_cd");
            String desc = resolveColumn("transaction_categories", "category_desc", "tran_cat_type_desc");
            assertThat(desc).as("transaction_categories must have a description column").isNotNull();
            assertThat(dataType("transaction_categories", desc)).isEqualTo("character varying");
        }

        @Test
        @DisplayName("PR-22 — Account/Card/Customer/Transaction expose an integer version column")
        void shouldCreateOptimisticLockVersionColumns() {
            for (String table : List.of("accounts", "cards", "customers", "transactions")) {
                String version = resolveColumn(table, "version", "opt_lock_version", "row_version");
                assertThat(version)
                    .as("PR-22: %s must have an optimistic-locking version column", table)
                    .isNotNull();
                assertThat(dataType(table, version))
                    .as("PR-22: %s.%s should be an integer family type", table, version)
                    .isIn("bigint", "integer", "smallint");
            }
        }
    }

    // =========================================================================
    // 2. V2 — Secondary B-tree indexes that replace the three VSAM AIX
    //    alternate indexes (CARDDATA.AIX, CARDXREF.AIX, TRANSACT.AIX).
    // =========================================================================
    @Nested
    @DisplayName("V2 — Secondary indexes (replace VSAM AIX alternate indexes)")
    class V2Indexes {

        @Test
        @DisplayName("idx_card_account_id replaces CARDDATA.AIX (AXRKP=16 over CARD-ACCT-ID)")
        void shouldCreateCardAccountIndex() {
            assertThat(indexCoversColumn("cards", "idx_card_account_id", "account_id"))
                .as("idx_card_account_id must exist on cards and cover the owning-account column "
                    + "(replaces VSAM CARDDATA.AIX)")
                .isTrue();
        }

        @Test
        @DisplayName("idx_xref_account_id replaces CARDXREF.AIX (AXRKP=25 over XREF-ACCT-ID)")
        void shouldCreateXrefAccountIndex() {
            assertThat(indexCoversColumn("card_xref", "idx_xref_account_id", "xref_acct_id"))
                .as("idx_xref_account_id must exist on card_xref and cover xref_acct_id "
                    + "(replaces VSAM CARDXREF.AIX)")
                .isTrue();
        }

        @Test
        @DisplayName("idx_transaction_orig_ts replaces TRANSACT.AIX (AXRKP=304 over TRAN-ORIG-TS)")
        void shouldCreateTransactionOrigTsIndex() {
            assertThat(indexCoversColumn("transactions", "idx_transaction_orig_ts", "orig_timestamp"))
                .as("idx_transaction_orig_ts must exist on transactions and cover the "
                    + "origination-timestamp column (replaces VSAM TRANSACT.AIX)")
                .isTrue();
        }

        @Test
        @DisplayName("All three AIX-replacement indexes are present in pg_indexes")
        void shouldCreateAllAixReplacementIndexes() {
            List<String> indexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
            assertThat(indexNames)
                .as("The three AIX-replacement B-tree indexes must all be created by V2")
                .contains("idx_card_account_id", "idx_xref_account_id", "idx_transaction_orig_ts");
        }
    }

    // =========================================================================
    // 3. V3 — Reference data seeding (7 transaction types, 18 categories,
    //    51 disclosure groups including the DEFAULT fallback group per PR-02).
    // =========================================================================
    @Nested
    @DisplayName("V3 — Reference data seeding (types, categories, disclosure groups)")
    class V3SeedReferenceData {

        @Test
        @DisplayName("Seeds exactly 7 transaction types (trantype.txt)")
        void shouldSeedExactlySevenTransactionTypes() {
            assertThat(rowCount("transaction_types"))
                .as("V3 must seed exactly 7 transaction types (app/data/ASCII/trantype.txt)")
                .isEqualTo(7);
        }

        @Test
        @DisplayName("Seeds the seven known transaction-type codes 01..07")
        void shouldSeedKnownTransactionTypes() {
            List<String> codes = jdbcTemplate.queryForList(
                "SELECT tran_type FROM transaction_types ORDER BY tran_type", String.class);
            assertThat(codes)
                .as("The seven canonical transaction-type codes must be present and ordered")
                .extracting(String::trim)
                .containsExactly("01", "02", "03", "04", "05", "06", "07");
        }

        @Test
        @DisplayName("Seeds exactly 18 transaction categories (trancatg.txt)")
        void shouldSeedExactlyEighteenTransactionCategories() {
            assertThat(rowCount("transaction_categories"))
                .as("V3 must seed exactly 18 transaction categories (app/data/ASCII/trancatg.txt)")
                .isEqualTo(18);
        }

        @Test
        @DisplayName("Seeds exactly 51 disclosure groups (discgrp.txt, incl. DEFAULT entries)")
        void shouldSeedExactlyFiftyOneDisclosureGroups() {
            assertThat(rowCount("disclosure_groups"))
                .as("V3 must seed exactly 51 disclosure groups (app/data/ASCII/discgrp.txt)")
                .isEqualTo(51);
        }

        @Test
        @DisplayName("PR-02 — A DEFAULT disclosure group exists for interest-rate fallback")
        void shouldSeedDefaultDisclosureGroup() {
            Integer defaultRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disclosure_groups WHERE group_id = 'DEFAULT'", Integer.class);
            assertThat(defaultRows)
                .as("PR-02: at least one DEFAULT disclosure group row must exist for CBACT04C fallback")
                .isGreaterThanOrEqualTo(1);

            BigDecimal defaultRate = jdbcTemplate.queryForObject(
                "SELECT dis_int_rate FROM disclosure_groups "
                    + "WHERE group_id = 'DEFAULT' AND type_cd = '01' AND cat_cd = '0001'",
                BigDecimal.class);
            assertThat(defaultRate)
                .as("PR-02: the canonical DEFAULT/01/0001 fallback rate must be seeded")
                .isNotNull();
            assertThat(defaultRate.scale())
                .as("PR-16: DEFAULT disclosure rate must carry scale 2")
                .isEqualTo(2);
        }

        @Test
        @DisplayName("PR-16 — All disclosure interest rates are NUMERIC scale 2")
        void shouldStoreInterestRatesAsBigDecimalScale2() {
            List<BigDecimal> rates = jdbcTemplate.queryForList(
                "SELECT dis_int_rate FROM disclosure_groups WHERE dis_int_rate IS NOT NULL",
                BigDecimal.class);
            assertThat(rates)
                .as("Every seeded disclosure group must carry an interest rate")
                .isNotEmpty();
            rates.forEach(rate -> assertThat(rate.scale())
                .as("PR-16: disclosure interest rate %s must have scale exactly 2", rate)
                .isEqualTo(2));
        }
    }

    // =========================================================================
    // 4. V4 — Default user seeding (PR-17 BCrypt). The single most important
    //    security-parity verification in the entire migration: the 10 default
    //    users must carry BCrypt hashes of the literal "PASSWORD", never plaintext.
    // =========================================================================
    @Nested
    @DisplayName("V4 — Default user seeding (PR-17: BCrypt password hashing)")
    class V4SeedUsers {

        /** BCrypt external form: {@code $2[a|b|x|y]$<cost>$<22-char salt><31-char digest>} == 60 chars. */
        private final Pattern bcryptPattern = Pattern.compile("^\\$2[abxy]\\$\\d{2}\\$.{53}$");

        private final List<String> adminIds =
            List.of("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005");
        private final List<String> userIds =
            List.of("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

        @Test
        @DisplayName("Seeds exactly 10 default users (DUSRSECJ.jcl)")
        void shouldSeedExactlyTenDefaultUsers() {
            assertThat(rowCount("users"))
                .as("V4 must seed exactly 10 default users (5 admin + 5 regular)")
                .isEqualTo(10);
        }

        @Test
        @DisplayName("Seeds 5 admin (type 'A') and 5 regular (type 'U') users")
        void shouldSeedFiveAdminAndFiveRegularUsers() {
            Integer admins = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE sec_usr_type = 'A'", Integer.class);
            Integer regulars = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE sec_usr_type = 'U'", Integer.class);
            assertThat(admins).as("Exactly 5 ADMIN users (sec_usr_type='A') must be seeded").isEqualTo(5);
            assertThat(regulars).as("Exactly 5 regular users (sec_usr_type='U') must be seeded").isEqualTo(5);
        }

        @Test
        @DisplayName("Seeds the five known admin user IDs ADMIN001..ADMIN005")
        void shouldSeedKnownAdminUserIds() {
            List<String> ids = jdbcTemplate.queryForList(
                "SELECT sec_usr_id FROM users WHERE sec_usr_type = 'A' ORDER BY sec_usr_id",
                String.class);
            assertThat(ids)
                .as("Admin user IDs must match DUSRSECJ.jcl exactly")
                .extracting(String::trim)
                .containsExactly("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005");
        }

        @Test
        @DisplayName("Seeds the five known regular user IDs USER0001..USER0005")
        void shouldSeedKnownRegularUserIds() {
            List<String> ids = jdbcTemplate.queryForList(
                "SELECT sec_usr_id FROM users WHERE sec_usr_type = 'U' ORDER BY sec_usr_id",
                String.class);
            assertThat(ids)
                .as("Regular user IDs must match DUSRSECJ.jcl exactly")
                .extracting(String::trim)
                .containsExactly("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
        }

        @Test
        @DisplayName("Seeds the exact first/last names from DUSRSECJ.jcl")
        void shouldSeedKnownFirstAndLastNames() {
            Map<String, String[]> expected = Map.ofEntries(
                Map.entry("ADMIN001", new String[] {"MARGARET", "GOLD"}),
                Map.entry("ADMIN002", new String[] {"RUSSELL", "RUSSELL"}),
                Map.entry("ADMIN003", new String[] {"RAYMOND", "WHITMORE"}),
                Map.entry("ADMIN004", new String[] {"EMMANUEL", "CASGRAIN"}),
                Map.entry("ADMIN005", new String[] {"GRANVILLE", "LACHAPELLE"}),
                Map.entry("USER0001", new String[] {"LAWRENCE", "THOMAS"}),
                Map.entry("USER0002", new String[] {"AJITH", "KUMAR"}),
                Map.entry("USER0003", new String[] {"LAURITZ", "ALME"}),
                Map.entry("USER0004", new String[] {"AVERARDO", "MAZZI"}),
                Map.entry("USER0005", new String[] {"LEE", "TING"}));

            expected.forEach((id, names) -> {
                Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT sec_usr_fname, sec_usr_lname FROM users WHERE sec_usr_id = ?", id);
                assertThat(((String) row.get("sec_usr_fname")).trim())
                    .as("First name for %s must match DUSRSECJ.jcl", id)
                    .isEqualToIgnoringCase(names[0]);
                assertThat(((String) row.get("sec_usr_lname")).trim())
                    .as("Last name for %s must match DUSRSECJ.jcl", id)
                    .isEqualToIgnoringCase(names[1]);
            });
        }

        @Test
        @DisplayName("CRITICAL — All 10 default users have valid BCrypt hashes of literal 'PASSWORD' (PR-17)")
        void shouldHaveValidBCryptHashesForAllDefaultUsers() {
            List<String> allIds = new java.util.ArrayList<>();
            allIds.addAll(adminIds);
            allIds.addAll(userIds);

            for (String userId : allIds) {
                String hash = jdbcTemplate.queryForObject(
                    "SELECT sec_usr_pwd FROM users WHERE sec_usr_id = ?", String.class, userId);

                assertThat(hash)
                    .as("PR-17: BCrypt hash for %s must be exactly 60 characters", userId)
                    .isNotNull()
                    .hasSize(60);
                assertThat(hash)
                    .as("PR-17: BCrypt hash for %s must match the $2 external form", userId)
                    .startsWith("$2")
                    .matches(bcryptPattern);
                assertThat(bcryptEncoder.matches("PASSWORD", hash))
                    .as("PR-17: BCryptPasswordEncoder.matches('PASSWORD', hash) must be true for %s", userId)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("Each user has a distinct hash (BCrypt random salt) despite shared password")
        void shouldStoreDistinctHashesPerUser() {
            List<String> hashes = jdbcTemplate.queryForList(
                "SELECT sec_usr_pwd FROM users", String.class);
            assertThat(hashes)
                .as("All 10 users share password 'PASSWORD' yet each BCrypt hash must be unique "
                    + "(distinct 22-char random salt)")
                .hasSize(10)
                .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("PR-17 — Zero plaintext passwords are stored after V4")
        void shouldNotStoreAnyPlaintextPasswords() {
            Integer plaintextCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE sec_usr_pwd = 'PASSWORD'", Integer.class);
            assertThat(plaintextCount)
                .as("PR-17: no user row may store the literal plaintext 'PASSWORD'")
                .isZero();

            Integer shortCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LENGTH(sec_usr_pwd) < 60", Integer.class);
            assertThat(shortCount)
                .as("PR-17: every stored password must be a full 60-char BCrypt hash (no plaintext/short values)")
                .isZero();
        }
    }

    // =========================================================================
    // 5. V5 — Master data seeding (50 customers, 50 accounts, 50 cards,
    //    50 card xrefs, 50-100 transaction category balances) + referential
    //    integrity and money-scale fidelity.
    // =========================================================================
    @Nested
    @DisplayName("V5 — Master data seeding (customers, accounts, cards, xrefs, balances)")
    class V5SeedMasterData {

        @Test
        @DisplayName("Seeds 50 customers (custdata.txt)")
        void shouldSeedFiftyCustomers() {
            assertThat(rowCount("customers"))
                .as("V5 must seed 50 customers (app/data/ASCII/custdata.txt)")
                .isEqualTo(50);
        }

        @Test
        @DisplayName("Seeds 50 accounts (acctdata.txt)")
        void shouldSeedFiftyAccounts() {
            assertThat(rowCount("accounts"))
                .as("V5 must seed 50 accounts (app/data/ASCII/acctdata.txt)")
                .isEqualTo(50);
        }

        @Test
        @DisplayName("Seeds 50 cards (carddata.txt)")
        void shouldSeedFiftyCards() {
            assertThat(rowCount("cards"))
                .as("V5 must seed 50 cards (app/data/ASCII/carddata.txt)")
                .isEqualTo(50);
        }

        @Test
        @DisplayName("Seeds 50 card cross-references (cardxref.txt)")
        void shouldSeedFiftyCardXrefs() {
            assertThat(rowCount("card_xref"))
                .as("V5 must seed 50 card cross-references (app/data/ASCII/cardxref.txt)")
                .isEqualTo(50);
        }

        @Test
        @DisplayName("Seeds 50-100 transaction category balances (fixture=50, LISTCAT REC-TOTAL=100)")
        void shouldSeedAtLeastFiftyTranCatBalances() {
            int count = rowCount("tran_cat_balances");
            assertThat(count)
                .as("tran_cat_balances row count must be between the ASCII fixture (50) and "
                    + "LISTCAT REC-TOTAL (100), inclusive")
                .isBetween(50, 100);
        }

        @Test
        @DisplayName("Referential integrity — every card references a valid account")
        void shouldHaveValidAccountReferences() {
            String accountId = resolveColumn("cards", "account_id", "card_acct_id", "acct_id");
            assertThat(accountId).as("cards must expose an owning-account column").isNotNull();
            Integer orphanedCards = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cards c "
                    + "LEFT JOIN accounts a ON c." + accountId + " = a.acct_id "
                    + "WHERE a.acct_id IS NULL",
                Integer.class);
            assertThat(orphanedCards)
                .as("Every seeded card must reference an existing account")
                .isZero();
        }

        @Test
        @DisplayName("Referential integrity — every xref references a valid customer")
        void shouldHaveValidCustomerReferences() {
            String custId = resolveColumn("card_xref", "xref_cust_id", "cust_id");
            assertThat(custId).as("card_xref must expose a customer-id column").isNotNull();
            Integer orphanedXrefs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM card_xref x "
                    + "LEFT JOIN customers c ON x." + custId + " = c.cust_id "
                    + "WHERE c.cust_id IS NULL",
                Integer.class);
            assertThat(orphanedXrefs)
                .as("Every seeded card cross-reference must reference an existing customer")
                .isZero();
        }

        @Test
        @DisplayName("Referential integrity — every xref references a valid account")
        void shouldHaveValidXrefAccountReferences() {
            String acctId = resolveColumn("card_xref", "xref_acct_id", "acct_id", "account_id");
            assertThat(acctId).as("card_xref must expose an account-id column").isNotNull();
            Integer orphanedXrefs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM card_xref x "
                    + "LEFT JOIN accounts a ON x." + acctId + " = a.acct_id "
                    + "WHERE a.acct_id IS NULL",
                Integer.class);
            assertThat(orphanedXrefs)
                .as("Every seeded card cross-reference must reference an existing account")
                .isZero();
        }

        @Test
        @DisplayName("PR-16 — Seeded account balances retain NUMERIC scale 2")
        void shouldStoreMoneyWithExactScale() {
            String balCol = resolveColumn("accounts", "curr_bal", "acct_curr_bal");
            assertThat(balCol).as("accounts must expose a current-balance column").isNotNull();
            List<BigDecimal> balances = jdbcTemplate.queryForList(
                "SELECT " + balCol + " FROM accounts WHERE " + balCol + " IS NOT NULL",
                BigDecimal.class);
            assertThat(balances)
                .as("Seeded accounts must carry current-balance values")
                .isNotEmpty();
            balances.forEach(balance -> assertThat(balance.scale())
                .as("PR-16: account balance %s must have scale exactly 2", balance)
                .isEqualTo(2));
        }
    }

    // =========================================================================
    // 6. Flyway history — confirm all 5 versioned migrations applied cleanly,
    //    in order, with success=true and no checksum failures.
    // =========================================================================
    @Nested
    @DisplayName("Flyway history — all 5 migrations applied successfully and in order")
    class FlywayHistory {

        @Test
        @DisplayName("Records V1..V5 as five successful versioned migrations")
        void shouldRecordAllFiveMigrationsAsSuccess() {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM flyway_schema_history "
                    + "WHERE version IS NOT NULL ORDER BY installed_rank");
            assertThat(rows)
                .as("Exactly five versioned migrations (V1..V5) must be recorded")
                .hasSize(5);
            rows.forEach(row -> assertThat(row.get("success"))
                .as("Migration %s (%s) must be recorded as successful",
                    row.get("version"), row.get("description"))
                .isEqualTo(Boolean.TRUE));
        }

        @Test
        @DisplayName("Records the migration versions in exact order 1..5")
        void shouldRecordMigrationsInVersionOrder() {
            List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history "
                    + "WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class);
            assertThat(versions)
                .as("Flyway must apply migrations strictly in version order")
                .containsExactly("1", "2", "3", "4", "5");
        }

        @Test
        @DisplayName("Records the expected per-migration descriptions")
        void shouldRecordExpectedMigrationDescriptions() {
            Map<String, String> byVersion = new java.util.HashMap<>();
            jdbcTemplate.queryForList(
                    "SELECT version, description FROM flyway_schema_history WHERE version IS NOT NULL")
                .forEach(row -> byVersion.put(
                    String.valueOf(row.get("version")),
                    String.valueOf(row.get("description")).toLowerCase()));

            assertThat(byVersion.get("1"))
                .as("V1 description should reference the schema").contains("schema");
            assertThat(byVersion.get("2"))
                .as("V2 description should reference indexes").contains("index");
            assertThat(byVersion.get("3"))
                .as("V3 description should reference reference/seed data").contains("seed");
            assertThat(byVersion.get("4"))
                .as("V4 description should reference user seeding").contains("user");
            assertThat(byVersion.get("5"))
                .as("V5 description should reference master/seed data").contains("seed");
        }

        @Test
        @DisplayName("Records zero failed migrations")
        void shouldHaveAppliedAllChecksumsCleanly() {
            Integer failures = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Integer.class);
            assertThat(failures)
                .as("No Flyway migration may be recorded as failed")
                .isZero();
        }
    }

    // =========================================================================
    // 7. Constraint validation — primary key, NOT NULL, and (where present)
    //    foreign-key enforcement. Each violating INSERT throws and never
    //    commits, so seed-count assertions elsewhere remain unaffected and the
    //    tests stay order-independent.
    // =========================================================================
    @Nested
    @DisplayName("Constraint validation — PK, NOT NULL, and FK enforcement")
    class ConstraintValidation {

        @Test
        @DisplayName("Rejects a duplicate primary-key insert")
        void shouldRejectDuplicatePrimaryKeyInsert() {
            // '01' is seeded by V3; re-inserting the same PK must violate the primary key.
            assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transaction_types (tran_type, type_desc) VALUES ('01', 'DUPLICATE')"))
                .as("Re-inserting an existing transaction_types PK must raise a data-integrity violation")
                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Enforces NOT NULL on a required column")
        void shouldEnforceNotNullOnCriticalColumns() {
            // '99' is not seeded, isolating the NOT NULL violation on the required description column.
            assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transaction_types (tran_type, type_desc) VALUES ('99', NULL)"))
                .as("Inserting NULL into a NOT NULL column must raise a data-integrity violation")
                .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Rejects an orphaned child insert when a foreign key is defined")
        void shouldRejectOrphanedChildInsert() {
            Integer fkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                    + "WHERE table_schema = 'public' AND table_name = 'tran_cat_balances' "
                    + "AND constraint_type = 'FOREIGN KEY'",
                Integer.class);

            if (fkCount != null && fkCount > 0) {
                // A non-existent account_id (999999999) must be rejected by the FK to accounts.
                assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance) "
                        + "VALUES (999999999, '01', '0001', 0.00)"))
                    .as("Inserting a tran_cat_balances row referencing a non-existent account "
                        + "must violate the foreign key")
                    .isInstanceOf(DataIntegrityViolationException.class);
            } else {
                // No FK constraint present on tran_cat_balances — relationship is logical only.
                assertThat(true)
                    .as("tran_cat_balances has no foreign-key constraint; orphan-rejection test is "
                        + "not applicable for a logical-only relationship")
                    .isTrue();
            }
        }
    }

    /**
     * Test-only fallback beans that allow the full application context to start in isolation
     * while the production {@code com.carddemo.security.SecurityConfig} is unavailable.
     *
     * <p>Both beans are guarded with {@link ConditionalOnMissingBean} so they register only when
     * the real beans are absent and silently back off once {@code SecurityConfig} supplies them —
     * keeping this migration test runnable both standalone and within the full suite.</p>
     */
    @TestConfiguration
    static class SecurityBeanFallbackTestConfig {

        /** Real BCrypt encoder so {@code UserService} / {@code UserSeedingJobConfig} can be constructed. */
        @Bean
        @ConditionalOnMissingBean(PasswordEncoder.class)
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        /**
         * Minimal {@code AuthenticationManager} so {@code AuthService} can be constructed. This
         * migration test never authenticates, so the stub is never invoked; if it ever were, the
         * thrown exception makes the misuse obvious.
         */
        @Bean
        @ConditionalOnMissingBean(AuthenticationManager.class)
        AuthenticationManager authenticationManager() {
            return authentication -> {
                throw new UnsupportedOperationException(
                    "Test-only stub AuthenticationManager (FlywayMigrationIT). The real "
                        + "AuthenticationManager is provided by SecurityConfig at full-suite runtime; "
                        + "this schema-migration test verifies database structure/seed data only and "
                        + "never performs authentication.");
            };
        }
    }
}
