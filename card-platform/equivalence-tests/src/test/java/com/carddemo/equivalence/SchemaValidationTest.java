package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies every service's Flyway migrations to a real PostgreSQL instance, then asks Hibernate to
 * validate that service's entity mappings against the schema those migrations built.
 *
 * <p>Every service runs with {@code spring.jpa.hibernate.ddl-auto: validate}, so a disagreement
 * between a migration and an entity stops that service at start-up rather than at the first query.
 * This class turns that start-up check into a build failure. Without it, the only way to find such a
 * disagreement is to launch the service and read the stack trace.
 *
 * <p>Three kinds of disagreement fail {@link #migrationsAndEntitiesAgree}. A column an entity maps
 * and no migration creates fails as a missing column. A column whose Structured Query Language (SQL)
 * type cannot carry the Java type of the field it is mapped to fails as a type mismatch. A table an
 * entity names and no migration creates fails as a missing table.
 *
 * <p>One container serves all six services, each migrating into its own schema, which is how the six
 * share one instance in {@code card-platform/docker-compose.yml}. The image tag matches that file.
 *
 * <p>Two further tests read the migrations as text and need no database. They pin the two
 * platform-wide conventions this work settled: one column type for a moment in time, and one outbox
 * and processed-event shape. A migration that drifts from either fails immediately, without waiting
 * for a container to start.
 *
 * <p>The Hibernate settings below restate Spring Boot 4.1.0's own defaults, which the framework
 * installs through its {@code HibernateProperties} class. A test that left them out would compare
 * entity field names verbatim against snake-case column names and report a false mismatch on every
 * multi-word field.
 */
@Testcontainers
class SchemaValidationTest {

    /**
     * Image tag of the database container, matching the {@code postgres} service in
     * {@code card-platform/docker-compose.yml}.
     */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** One instance for all six services, as the compose file and deployment manifests declare. */
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo-schema-validation");

    /**
     * The physical naming strategy Spring Boot 4.1.0 installs by default, which converts a camel-case
     * field name into a snake-case column name.
     */
    private static final String PHYSICAL_NAMING_STRATEGY =
            "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl";

    /** The implicit naming strategy Spring Boot 4.1.0 installs by default. */
    private static final String IMPLICIT_NAMING_STRATEGY =
            "org.springframework.boot.hibernate.SpringImplicitNamingStrategy";

    /**
     * The one column type every service uses for a moment in time.
     *
     * <p>Hibernate 7.4.1 generates exactly this type for a {@link java.time.Instant} field on
     * PostgreSQL, so it is the type the provider itself treats as canonical for a moment. The
     * alternative this platform does not use is a native time-zone storage strategy, which
     * {@code PostgreSQLDialect} rejects outright.
     */
    private static final String MOMENT_COLUMN_TYPE = "TIMESTAMP(6) WITH TIME ZONE";

    /**
     * Columns typed as a calendar {@code DATE} on purpose rather than as a moment, keyed as
     * {@code module.column}.
     *
     * <p>The card expiry is the only one. Its source slices the field positionally into a
     * four-character year, a two-character month and a two-character day at
     * {@code app/cbl/COCRDUPC.cbl:L1505-L1507}, which is a calendar date and not an instant.
     */
    private static final List<String> CALENDAR_DATE_COLUMNS =
            List.of("card-service.expiration_date");

    /**
     * The two expiry columns the authorization rule compares as text, keyed as
     * {@code module.column}.
     *
     * <p>Reject reason 103 compares the account expiry character by character against the first ten
     * characters of a transaction timestamp at {@code app/cbl/CBTRN02C.cbl:L414-L420}. A date type
     * would change the result at any value that is not a well-formed date, so these stay text. The
     * Agent Action Plan states the rule in section 0.3.1 and transformation rule T2 requires it.
     */
    private static final List<String> TEXT_EXPIRY_COLUMNS = List.of(
            "account-service.expiration_date", "authorization-service.account_expiration_date");

    /** The width of the text expiry columns, matching {@code PIC X(10)} in the source copybooks. */
    private static final String TEXT_EXPIRY_TYPE = "VARCHAR(10)";

    /** Matches one column declaration: exactly four spaces, a lower-case name, then its type. */
    private static final Pattern COLUMN_DECLARATION =
            Pattern.compile("^ {4}([a-z_]+)\\s+(\\S.*)$");

    /**
     * The fourteen canonical {@code outbox_event} columns, in declaration order, with their types.
     *
     * <p>Seven carry the event and its publication state. The other seven carry the relay lease:
     * without a claim two relay instances read the same unpublished row and publish the same event
     * twice, and without an attempt count and a next-attempt time one undeliverable row is retried
     * for ever and every row behind it waits.
     *
     * <p>These are the types the first migration of each service declares. The authorization service
     * later widens {@code aggregate_id} to {@code VARCHAR(16)} in
     * {@code V5__outbox_transaction_key.sql}, because one of its events is keyed by transaction
     * identifier rather than by account identifier: the decline for a card that resolves to no
     * account has no account identifier to key on. This comparison reads {@code CREATE TABLE} only,
     * so it measures the shape every service starts from, which is the shape one relay contract
     * needs. A later widening in one service does not break that contract, because the relay reads
     * the column as text either way.
     */
    private static final Map<String, String> CANONICAL_OUTBOX_COLUMNS = canonicalOutboxColumns();

    /**
     * The {@code aggregate_id} type the authorization service declares.
     *
     * <p>{@code TransactionDeclined} version 2 carries no account identifier, so a decline that
     * resolved no card is keyed on its transaction identifier. {@code TRAN-ID PIC X(16)} at
     * app/cpy/CVTRA05Y.cpy:L5 sets that width, and {@code XREF-ACCT-ID PIC 9(11)} at
     * app/cpy/CVACT03Y.cpy:L7 sets the other. {@code EventEnvelope#AGGREGATE_KEY_PATTERN} admits
     * both forms and the column holds the wider one.
     */
    private static final String AUTHORIZATION_AGGREGATE_KEY_TYPE = "VARCHAR(16) NOT NULL";

    /** The one module whose {@code aggregate_id} holds an unresolved-decline key. */
    private static final String UNRESOLVED_KEY_MODULE = "authorization-service";

    /**
     * The canonical outbox columns as the named module declares them.
     *
     * @param module the Maven module directory name
     * @return the column names in declaration order, each mapped to its declared type
     */
    private static Map<String, String> outboxColumnsFor(String module) {
        if (!UNRESOLVED_KEY_MODULE.equals(module)) {
            return CANONICAL_OUTBOX_COLUMNS;
        }
        Map<String, String> columns = new LinkedHashMap<>(CANONICAL_OUTBOX_COLUMNS);
        columns.put("aggregate_id", AUTHORIZATION_AGGREGATE_KEY_TYPE);
        return columns;
    }

    /**
     * The three canonical {@code processed_event} columns, in declaration order, with their types.
     *
     * <p>{@code consumed_topic} is nullable because a marker written before the column existed
     * carries none. Every marker written since names the topic its delivery arrived on.
     */
    private static final Map<String, String> CANONICAL_MARKER_COLUMNS = canonicalMarkerColumns();

    private static Map<String, String> canonicalOutboxColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("event_id", "UUID NOT NULL");
        columns.put("event_type", "VARCHAR(50) NOT NULL");
        columns.put("aggregate_id", "CHAR(11) NOT NULL");
        columns.put("payload", "TEXT NOT NULL");
        columns.put("published", "BOOLEAN NOT NULL DEFAULT FALSE");
        columns.put("created_at", MOMENT_COLUMN_TYPE + " NOT NULL");
        columns.put("relay_state", "VARCHAR(16) NOT NULL DEFAULT 'PENDING'");
        columns.put("attempt_count", "INTEGER NOT NULL DEFAULT 0");
        columns.put("next_attempt_at", MOMENT_COLUMN_TYPE + " NOT NULL");
        columns.put("last_attempt_at", MOMENT_COLUMN_TYPE);
        columns.put("last_error", "VARCHAR(500)");
        columns.put("claimed_by", "VARCHAR(64)");
        columns.put("claimed_at", MOMENT_COLUMN_TYPE);
        columns.put("published_at", MOMENT_COLUMN_TYPE);
        return columns;
    }

    private static Map<String, String> canonicalMarkerColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("event_id", "UUID NOT NULL");
        columns.put("processed_at", MOMENT_COLUMN_TYPE + " NOT NULL");
        columns.put("consumed_topic", "VARCHAR(128)");
        return columns;
    }

    /**
     * One service, the schema it migrates into, and the entity classes it maps.
     *
     * <p>The entities are named as class literals rather than found by scanning the classpath, so a
     * renamed or deleted entity breaks compilation here instead of quietly narrowing the check.
     *
     * @param module   directory name of the service under {@code card-platform/services}
     * @param schema   schema the service migrates into, from its {@code application.yml}
     * @param entities every entity class the service maps
     */
    private record Service(String module, String schema, List<Class<?>> entities) {

        @Override
        public String toString() {
            return module;
        }
    }

    /**
     * Returns the six services, their schemas and their entity classes.
     *
     * @return one entry per service
     */
    private static List<Service> services() {
        return List.of(
                new Service("authorization-service", "authorization_service", List.of(
                        com.carddemo.authorization.entity.CardCrossReferenceEntity.class,
                        com.carddemo.authorization.entity.AccountCreditSnapshotEntity.class,
                        com.carddemo.authorization.entity.OutboxEventEntity.class,
                        com.carddemo.authorization.entity.ProcessedEventEntity.class)),
                new Service("ledger-posting-service", "ledger_service", List.of(
                        com.carddemo.ledger.entity.TransactionEntity.class,
                        com.carddemo.ledger.entity.TransactionCategoryBalanceEntity.class,
                        com.carddemo.ledger.entity.AccountBalanceProjectionEntity.class,
                        com.carddemo.ledger.entity.RejectedTransactionEntity.class,
                        com.carddemo.ledger.entity.OutboxEventEntity.class,
                        com.carddemo.ledger.entity.ProcessedEventEntity.class)),
                new Service("fraud-detection-service", "fraud_service", List.of(
                        com.carddemo.fraud.entity.FraudAssessmentEntity.class,
                        com.carddemo.fraud.entity.VelocityWindowEntity.class,
                        com.carddemo.fraud.entity.OutboxEventEntity.class,
                        com.carddemo.fraud.entity.ProcessedEventEntity.class)),
                new Service("notification-service", "notification_service", List.of(
                        com.carddemo.notification.entity.ProcessedEventEntity.class)),
                new Service("account-service", "account_service", List.of(
                        com.carddemo.account.entity.AccountEntity.class,
                        com.carddemo.account.entity.CustomerEntity.class,
                        com.carddemo.account.entity.DisclosureGroupEntity.class,
                        com.carddemo.account.entity.OutboxEventEntity.class,
                        com.carddemo.account.entity.ProcessedEventEntity.class)),
                new Service("card-service", "card_service", List.of(
                        com.carddemo.card.entity.CardEntity.class,
                        com.carddemo.card.entity.CardCrossReferenceEntity.class,
                        com.carddemo.card.entity.OutboxEventEntity.class)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    @DisplayName("Flyway migrations and entity mappings agree for every service")
    void migrationsAndEntitiesAgree(Service service) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + migrationDirectory(service.module()))
                .schemas(service.schema())
                .defaultSchema(service.schema())
                .createSchemas(true)
                .load()
                .migrate();

        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.connection.driver_class", "org.postgresql.Driver");
        settings.put("hibernate.connection.url", POSTGRES.getJdbcUrl());
        settings.put("hibernate.connection.username", POSTGRES.getUsername());
        settings.put("hibernate.connection.password", POSTGRES.getPassword());
        settings.put("hibernate.default_schema", service.schema());
        settings.put("hibernate.hbm2ddl.auto", "validate");
        settings.put("hibernate.physical_naming_strategy", PHYSICAL_NAMING_STRATEGY);
        settings.put("hibernate.implicit_naming_strategy", IMPLICIT_NAMING_STRATEGY);

        StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder().applySettings(settings).build();
        MetadataSources sources = new MetadataSources(registry);
        service.entities().forEach(sources::addAnnotatedClass);

        try (SessionFactory factory = sources.buildMetadata().buildSessionFactory()) {
            assertTrue(factory.isOpen(),
                    service.module() + " built a session factory that is not open");
        } catch (RuntimeException failure) {
            fail(service.module() + " maps an entity its own migrations do not support: "
                    + rootMessage(failure), failure);
        }
    }

    @Test
    @DisplayName("one column type carries a moment in time in every service")
    void momentColumnsShareOneType() {
        List<String> divergences = new ArrayList<>();

        for (Service service : services()) {
            columnsOf(service.module()).forEach((name, declaration) -> {
                String qualified = service.module() + "." + name;
                boolean temporal = declaration.startsWith("TIMESTAMP")
                        || declaration.equals("DATE") || declaration.startsWith("DATE ");
                if (!temporal || CALENDAR_DATE_COLUMNS.contains(qualified)) {
                    return;
                }
                if (!declaration.startsWith(MOMENT_COLUMN_TYPE)) {
                    divergences.add(qualified + " declares '" + declaration + "'");
                }
            });
        }

        assertTrue(divergences.isEmpty(),
                "A moment in time uses " + MOMENT_COLUMN_TYPE + " in every service, because "
                        + "Hibernate 7.4.1 generates that type for a java.time.Instant field on "
                        + "PostgreSQL. These columns differ: " + divergences);
    }

    @Test
    @DisplayName("the two expiry columns compared as text stay text")
    void textExpiryColumnsStayText() {
        for (String qualified : TEXT_EXPIRY_COLUMNS) {
            String module = qualified.substring(0, qualified.lastIndexOf('.'));
            String column = qualified.substring(qualified.lastIndexOf('.') + 1);
            String declaration = columnsOf(module).get(column);

            assertTrue(declaration != null && declaration.startsWith(TEXT_EXPIRY_TYPE),
                    qualified + " declares '" + declaration + "' rather than " + TEXT_EXPIRY_TYPE
                            + ". Reject reason 103 compares this value character by character "
                            + "against the first ten characters of a transaction timestamp at "
                            + "app/cbl/CBTRN02C.cbl:L414-L420, so a date type would change the "
                            + "result at any value that is not a well-formed date.");
        }
    }

    @Test
    @DisplayName("each declared event table carries the platform shape")
    void eventTablesShareOneShape() {
        List<String> divergences = new ArrayList<>();

        for (Service service : services()) {
            String migration = migrationText(service.module());

            if (migration.contains("CREATE TABLE processed_event")) {
                compare(service.module(), "processed_event", CANONICAL_MARKER_COLUMNS,
                        tableColumns(migration, "processed_event"), divergences);
            }

            // The notification service publishes nothing, so it declares no outbox table. Every
            // other service publishes at least one event and declares one.
            boolean declaresOutbox = migration.contains("CREATE TABLE outbox_event");
            if ("notification-service".equals(service.module())) {
                if (declaresOutbox) {
                    divergences.add(
                            "notification-service declares outbox_event and publishes no event");
                }
                continue;
            }
            if (!declaresOutbox) {
                divergences.add(service.module() + " publishes events and declares no outbox_event");
                continue;
            }

            compare(service.module(), "outbox_event", outboxColumnsFor(service.module()),
                    tableColumns(migration, "outbox_event"), divergences);
            if (!migration.contains("ck_outbox_event_publication")) {
                divergences.add(service.module() + " omits the publication check constraint that "
                        + "keeps published and published_at in agreement");
            }
            if (!migration.contains("ix_outbox_event_pending")) {
                divergences.add(service.module() + " omits the partial index the relay claim reads");
            }
        }

        assertTrue(divergences.isEmpty(),
                "One outbox and processed-event shape serves every service, so one relay contract "
                        + "covers all of them. Only aggregate_id widens, and only for "
                        + UNRESOLVED_KEY_MODULE + ". These differ: " + divergences);
    }

    /**
     * Compares one table's declared columns against the canonical set.
     *
     * @param module      service the table belongs to
     * @param table       table name, used in a failure message
     * @param expected    the canonical columns and their declared types
     * @param actual      the columns the migration declares
     * @param divergences collects one entry per disagreement
     */
    private static void compare(String module, String table, Map<String, String> expected,
            Map<String, String> actual, List<String> divergences) {
        if (actual.isEmpty()) {
            divergences.add(module + " declares no " + table + " table");
            return;
        }
        assertEquals(expected.keySet(), actual.keySet(),
                module + "." + table + " declares a different column set");
        expected.forEach((name, type) -> {
            String declared = actual.get(name);
            if (declared != null && !declared.equals(type)) {
                divergences.add(module + "." + table + "." + name + " declares '" + declared
                        + "' rather than '" + type + "'");
            }
        });
    }

    /**
     * Reads the column declarations of one table out of a migration.
     *
     * @param migration the migration text
     * @param table     the table to read
     * @return column name to declared type, in declaration order, and empty when the table is absent
     */
    private static Map<String, String> tableColumns(String migration, String table) {
        Map<String, String> columns = new LinkedHashMap<>();
        int start = migration.indexOf("CREATE TABLE " + table + " (");
        if (start < 0) {
            return columns;
        }
        for (String line : migration.substring(start).lines().skip(1).toList()) {
            if (line.startsWith(")") || line.startsWith("    CONSTRAINT")) {
                break;
            }
            declaredColumn(line).ifPresent(
                    column -> columns.put(column.getKey(), column.getValue()));
        }
        return columns;
    }

    /**
     * Reads every column declaration of one service's migrations, whatever table it belongs to.
     *
     * <p>Column names repeat across tables, and the repeats agree because the canonical outbox and
     * marker shape is identical everywhere, so the first declaration of a name is kept.
     *
     * @param module directory name of the service
     * @return column name to declared type
     */
    private static Map<String, String> columnsOf(String module) {
        Map<String, String> columns = new LinkedHashMap<>();
        migrationText(module).lines().forEach(line -> declaredColumn(line).ifPresent(
                column -> columns.putIfAbsent(column.getKey(), column.getValue())));
        return columns;
    }

    /**
     * Reads one column declaration, discarding a trailing comment and the separating comma.
     *
     * <p>The comment is discarded rather than the whole line, so a column that carries a
     * trailing comment is still checked.
     *
     * @param line one line of a migration
     * @return the column name and its declared type, or empty when the line declares no column
     */
    private static Optional<Map.Entry<String, String>> declaredColumn(String line) {
        int comment = line.indexOf("--");
        Matcher matcher =
                COLUMN_DECLARATION.matcher(comment < 0 ? line : line.substring(0, comment));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String type = matcher.group(2).replaceAll("\\s+", " ").trim();
        if (type.endsWith(",")) {
            type = type.substring(0, type.length() - 1).trim();
        }
        return Optional.of(Map.entry(matcher.group(1), type));
    }

    /**
     * Reads the concatenated migration text of one service, in version order.
     *
     * @param module directory name of the service
     * @return every migration of that service, joined
     */
    private static String migrationText(String module) {
        try (var files = Files.list(migrationDirectory(module))) {
            StringBuilder text = new StringBuilder();
            for (Path file : files.sorted().toList()) {
                text.append(Files.readString(file)).append('\n');
            }
            return text.toString();
        } catch (IOException failure) {
            throw new IllegalStateException("could not read the migrations of " + module, failure);
        }
    }

    /**
     * Locates one service's migration directory by walking up from the working directory.
     *
     * <p>{@link CardDemoFixtureLoader#fixtureDirectory()} locates the CardDemo fixtures the same way,
     * so a test runs whether it starts in the module directory or at the repository root. Reading the
     * directory from the filesystem rather than the classpath is necessary because all six services
     * place their migrations at the same classpath location, {@code db/migration}, where only the
     * first would be visible.
     *
     * @param module directory name of the service
     * @return the migration directory
     * @throws IllegalStateException when no ancestor of the working directory holds the directory
     */
    private static Path migrationDirectory(String module) {
        Path relative = Path.of("card-platform", "services", module, "src", "main", "resources",
                "db", "migration");
        for (Path candidate = Path.of("").toAbsolutePath().normalize(); candidate != null;
                candidate = candidate.getParent()) {
            Path migrations = candidate.resolve(relative);
            if (Files.isDirectory(migrations)) {
                return migrations;
            }
        }
        throw new IllegalStateException(
                "no ancestor of '" + Path.of("").toAbsolutePath() + "' holds '" + relative + "'");
    }

    /**
     * Returns the message of the deepest cause of a failure.
     *
     * @param failure the failure to unwrap
     * @return the deepest message, or the failure's own description when none carries one
     */
    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }
}
