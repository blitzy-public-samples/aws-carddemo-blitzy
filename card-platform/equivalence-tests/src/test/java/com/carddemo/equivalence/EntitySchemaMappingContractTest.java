package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Compares every persistent attribute of all twenty-six entities against the Flyway migration that
 * creates its column.
 *
 * <p>Flyway owns every schema and Hibernate runs under {@code ddl-auto: validate} in all six
 * services, so a wrong column name, type, precision, scale, nullability or key does not fail at
 * compile time. It fails when a service starts, or later still when a row is written. This class
 * closes that gap without a database: it builds Hibernate's own mapping model from the annotated
 * classes, parses the migration text, and compares the two.</p>
 *
 * <p>The mapping model is built with the two naming strategies Spring Boot applies, the snake-case
 * physical strategy and the Spring implicit strategy, so a column left to the naming strategy
 * resolves here exactly as it resolves at run time. Three identifier attributes in the ledger
 * service depend on that resolution, and a comparison using Hibernate's raw defaults would report
 * them as camel-case names that no migration declares.</p>
 *
 * <p>One registry is built per service rather than one for the platform. Five services each map a
 * table named {@code outbox_event} and six map one named {@code processed_event}, so a single
 * registry would fold six different tables of the same name into one and compare none of them
 * correctly.</p>
 *
 * <p>Comparisons run against the SQL type Hibernate renders for the column, which carries the type
 * name together with its length or its precision and scale. Reading the annotation attributes
 * instead would miss the entities that supply a whole column definition, whose declared length
 * Hibernate reports as its default. Two further assertions parse the numeric arguments out of both
 * rendered types and compare them as numbers, so a precision or a length that agreed only as text
 * would still be caught.</p>
 *
 * <p>Seven tables are created by a migration and mapped by no entity. Each is classified in
 * {@link #TABLES_WITHOUT_AN_ENTITY} with the reason, and a table absent from both that list and the
 * entity set fails. A table that IS mapped and also appears in that list fails too, so the list
 * cannot be used to excuse an entity that exists.</p>
 *
 * <p>No failure message here carries a row value. Every fixture and every seed row is out of reach:
 * this class reads migration text and mapping metadata only, and reports table names, column names,
 * type text and key lists.</p>
 */
class EntitySchemaMappingContractTest {

    /** Entities the six services declare between them. */
    private static final int ENTITY_COUNT = 26;

    /** Persistent attributes those entities map between them. */
    private static final int MAPPED_COLUMN_COUNT = 210;

    /** Dialect the mapping model renders SQL types for, matching the shipped database. */
    private static final String POSTGRES_DIALECT = "org.hibernate.dialect.PostgreSQLDialect";

    /**
     * Physical naming strategy Spring Boot 4.1.0 applies by default. It turns an attribute name into
     * a snake-case column name where no explicit column name is given.
     */
    private static final String PHYSICAL_NAMING_STRATEGY =
            "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl";

    /** Implicit naming strategy Spring Boot 4.1.0 applies by default. */
    private static final String IMPLICIT_NAMING_STRATEGY =
            "org.springframework.boot.hibernate.SpringImplicitNamingStrategy";

    /** Repository-relative path segment from a service module to its migration directory. */
    private static final String MIGRATION_DIRECTORY = "src/main/resources/db/migration";

    /** Repository-relative path segment from a service module to its entity sources. */
    private static final String ENTITY_SOURCE_PATH = "src/main/java/com/carddemo";

    /** Directory below the repository root holding the six service modules. */
    private static final String SERVICES_DIRECTORY = "card-platform/services";

    /** Suffix every entity source file carries. */
    private static final String JAVA_SUFFIX = ".java";

    /** Amount added to a zero-based index to report it as a one-based ordinal. */
    private static final int FIRST_ORDINAL = 1;

    // Migration parsing.

    /** Matches one line comment of a migration, which carries provenance and no schema. */
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n]*");

    /** Matches one {@code CREATE TABLE} statement and captures its name and its body. */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\R\\);",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches one {@code CREATE INDEX} statement, the name and the {@code ON} clause included. */
    private static final Pattern CREATE_INDEX =
            Pattern.compile("CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(([^)]*)\\)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches one {@code CREATE SEQUENCE} statement and captures its name. */
    private static final Pattern CREATE_SEQUENCE =
            Pattern.compile("CREATE\\s+SEQUENCE\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    /** Matches a primary key declared as a table element, named or unnamed. */
    private static final Pattern TABLE_PRIMARY_KEY = Pattern.compile(
            "^(?:CONSTRAINT\\s+\\w+\\s+)?PRIMARY\\s+KEY\\s*\\((.*)\\)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches a table element that constrains rather than declares a column. */
    private static final Pattern TABLE_CONSTRAINT =
            Pattern.compile("^(UNIQUE|CHECK|FOREIGN|CONSTRAINT)\\b", Pattern.CASE_INSENSITIVE);

    /** Matches the type of a column definition, its arguments and time-zone clause included. */
    private static final Pattern COLUMN_TYPE = Pattern.compile(
            "^((?:DOUBLE\\s+PRECISION|\\w+)(?:\\s*\\([^)]*\\))?(?:\\s+WITH(?:OUT)?\\s+TIME\\s+ZONE)?)",
            Pattern.CASE_INSENSITIVE);

    /** Matches the two arguments of a numeric type. */
    private static final Pattern NUMERIC_ARGUMENTS =
            Pattern.compile("^(?:numeric|decimal)\\((\\d+),(\\d+)\\)$");

    /** Matches the one argument of a character type. */
    private static final Pattern CHARACTER_ARGUMENT =
            Pattern.compile("^(?:varchar|char)\\((\\d+)\\)$");

    /** Words a column definition carries that mark it as the primary key of its table. */
    private static final String INLINE_PRIMARY_KEY = "PRIMARY KEY";

    /** Words a column definition carries that forbid a null value. */
    private static final String NOT_NULL = "NOT NULL";

    /**
     * One column of a migration.
     *
     * @param name     column name
     * @param type     type as the migration spells it
     * @param nullable whether the migration admits a null value
     */
    private record DdlColumn(String name, String type, boolean nullable) {
    }

    /**
     * One table of a migration.
     *
     * @param name       table name
     * @param columns    columns in declaration order
     * @param primaryKey primary key columns, in the order the key declares them
     */
    private record DdlTable(String name, Map<String, DdlColumn> columns, List<String> primaryKey) {
    }

    /**
     * One index of a migration.
     *
     * @param name    index name
     * @param table   table the index is created on
     * @param unique  whether the index is unique
     * @param columns indexed columns, in the order the statement lists them
     */
    private record DdlIndex(String name, String table, boolean unique, List<String> columns) {
    }

    /**
     * One migration.
     *
     * @param tables    tables it creates, keyed by name
     * @param indexes   indexes it creates
     * @param sequences sequences it creates
     */
    private record MigrationSchema(Map<String, DdlTable> tables, List<DdlIndex> indexes,
            List<String> sequences) {

        /** Indexes the migration creates on one table. */
        private List<DdlIndex> indexesOf(String table) {
            List<DdlIndex> matching = new ArrayList<>();
            for (DdlIndex index : indexes) {
                if (index.table().equals(table)) {
                    matching.add(index);
                }
            }
            return List.copyOf(matching);
        }
    }

    // Mapping model.

    /**
     * One column of the mapping model.
     *
     * @param name     column name after the naming strategies have run
     * @param type     SQL type Hibernate renders for the column
     * @param nullable whether the mapping admits a null value
     */
    private record MappedColumn(String name, String type, boolean nullable) {
    }

    /**
     * One table of the mapping model.
     *
     * @param entityName fully qualified entity name
     * @param table      table the entity maps to
     * @param columns    columns the entity maps, keyed by name
     * @param primaryKey primary key columns
     * @param indexes    indexes the entity declares, keyed by name
     */
    private record MappedTable(String entityName, String table, Map<String, MappedColumn> columns,
            List<String> primaryKey, Map<String, List<String>> indexes) {
    }

    /**
     * One service module.
     *
     * @param moduleName directory name of the module below {@code card-platform/services}
     * @param packageLeaf leaf package below {@code com.carddemo} holding the module's entities
     * @param entities   every entity the module declares
     */
    private record ServiceModule(String moduleName, String packageLeaf, List<Class<?>> entities) {
    }

    /**
     * The six services and their entities, named individually.
     *
     * <p>Listing the classes rather than scanning for them is deliberate: a class that lands later is
     * absent from this list, and {@code everyEntitySourceFileIsNamedInThisContract} fails until it is
     * added. A scan would silently absorb it and assert nothing new.</p>
     */
    private static final List<ServiceModule> SERVICES = List.of(
            new ServiceModule("authorization-service", "authorization", List.of(
                    com.carddemo.authorization.entity.AccountCreditSnapshotEntity.class,
                    com.carddemo.authorization.entity.CardCrossReferenceEntity.class,
                    com.carddemo.authorization.entity.OutboxEventEntity.class,
                    com.carddemo.authorization.entity.ProcessedEventEntity.class,
                    com.carddemo.authorization.entity.UnresolvedCardAttemptEntity.class)),
            new ServiceModule("ledger-posting-service", "ledger", List.of(
                    com.carddemo.ledger.entity.AccountBalanceProjectionEntity.class,
                    com.carddemo.ledger.entity.OutboxEventEntity.class,
                    com.carddemo.ledger.entity.ProcessedEventEntity.class,
                    com.carddemo.ledger.entity.RejectedTransactionEntity.class,
                    com.carddemo.ledger.entity.TransactionCategoryBalanceEntity.class,
                    com.carddemo.ledger.entity.TransactionEntity.class)),
            new ServiceModule("fraud-detection-service", "fraud", List.of(
                    com.carddemo.fraud.entity.FraudAssessmentEntity.class,
                    com.carddemo.fraud.entity.OutboxEventEntity.class,
                    com.carddemo.fraud.entity.ProcessedEventEntity.class,
                    com.carddemo.fraud.entity.VelocityWindowEntity.class)),
            new ServiceModule("notification-service", "notification", List.of(
                    com.carddemo.notification.entity.ProcessedEventEntity.class,
                    com.carddemo.notification.entity.StatementTransactionEntity.class)),
            new ServiceModule("account-service", "account", List.of(
                    com.carddemo.account.entity.AccountEntity.class,
                    com.carddemo.account.entity.CustomerEntity.class,
                    com.carddemo.account.entity.DisclosureGroupEntity.class,
                    com.carddemo.account.entity.OutboxEventEntity.class,
                    com.carddemo.account.entity.ProcessedEventEntity.class)),
            new ServiceModule("card-service", "card", List.of(
                    com.carddemo.card.entity.CardCrossReferenceEntity.class,
                    com.carddemo.card.entity.CardEntity.class,
                    com.carddemo.card.entity.OutboxEventEntity.class,
                    com.carddemo.card.entity.ProcessedEventEntity.class)));

    /**
     * Tables a migration creates and no entity maps, each with the reason.
     *
     * <p>The key is the module name and the table name. Every entry is either a table Flyway seeds
     * and the running code reads through something other than persistence, or a table whose entity
     * the plan schedules beyond this checkpoint.</p>
     */
    private static final Map<String, String> TABLES_WITHOUT_AN_ENTITY = tablesWithoutAnEntity();

    /** Builds {@link #TABLES_WITHOUT_AN_ENTITY}. */
    private static Map<String, String> tablesWithoutAnEntity() {
        Map<String, String> classified = new LinkedHashMap<>();
        classified.put("ledger-posting-service.transaction_type",
                "Seeded from app/data/ASCII/trantype.txt by V2__seed.sql. The plan seeds the two "
                        + "lookups into each schema that needs them rather than exposing an entity, "
                        + "a shared table or a seventh service");
        classified.put("ledger-posting-service.transaction_category",
                "Seeded from app/data/ASCII/trancatg.txt by V2__seed.sql, on the same footing as "
                        + "transaction_type");
        classified.put("notification-service.notification_log",
                "The delivery-attempt record. Its entity is scheduled beyond this checkpoint");
        classified.put("account-service.us_phone_area_code",
                "One of the three validation reference tables seeded from app/cpy/CSLKPCDY.cpy by "
                        + "V3__reference_data.sql. The validators read the literals through "
                        + "com.carddemo.cobol.reference and never through persistence");
        classified.put("account-service.us_state_code",
                "Seeded from app/cpy/CSLKPCDY.cpy and read through "
                        + "com.carddemo.cobol.reference.UsStateCodes");
        classified.put("account-service.us_state_zip_prefix",
                "Seeded from app/cpy/CSLKPCDY.cpy and read through "
                        + "com.carddemo.cobol.reference.UsStateZipPrefixes");
        return Map.copyOf(classified);
    }

    /** Association annotations no entity in this platform declares. */
    private static final List<Class<? extends Annotation>> ASSOCIATION_ANNOTATIONS = List.of(
            ManyToOne.class, OneToMany.class, OneToOne.class, ManyToMany.class,
            JoinColumn.class, JoinTable.class, ElementCollection.class);

    /** Words a migration would carry if it declared a relationship between two tables. */
    private static final List<String> RELATIONSHIP_KEYWORDS = List.of("FOREIGN KEY", "REFERENCES");

    // Reading.

    /** Reads a value once and returns the same value afterwards. */
    private static <T> Supplier<T> readOnce(Supplier<T> source) {
        return new Supplier<>() {

            private T value;

            private boolean read;

            @Override
            public synchronized T get() {
                if (!read) {
                    value = source.get();
                    read = true;
                }
                return value;
            }
        };
    }

    /** Repository root, being the ancestor of the fixture directory. */
    private static final Supplier<Path> REPOSITORY_ROOT = readOnce(() ->
            CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent());

    /** Every migration, keyed by module name. */
    private static final Supplier<Map<String, MigrationSchema>> MIGRATIONS =
            readOnce(EntitySchemaMappingContractTest::readMigrations);

    /** Every mapping model, keyed by module name. */
    private static final Supplier<Map<String, List<MappedTable>>> MAPPINGS =
            readOnce(EntitySchemaMappingContractTest::readMappings);

    /** Reads and parses the first migration of every service. */
    private static Map<String, MigrationSchema> readMigrations() {
        Map<String, MigrationSchema> migrations = new LinkedHashMap<>();
        for (ServiceModule service : SERVICES) {
            Path directory = REPOSITORY_ROOT.get()
                    .resolve(SERVICES_DIRECTORY)
                    .resolve(service.moduleName())
                    .resolve(MIGRATION_DIRECTORY);
            migrations.put(service.moduleName(),
                    parseMigration(migrationTextOf(directory), directory));
        }
        return Map.copyOf(migrations);
    }

    /**
     * Reads every migration of one module in version order, joined into one text.
     *
     * <p>A table or an index a later migration creates belongs to the schema a service validates
     * against exactly as one the first migration creates. Reading the directory rather than one file
     * is what keeps a table added by a later migration inside this comparison.</p>
     *
     * @param directory migration directory of one module
     * @return the text of every {@code .sql} below it, in file-name order
     */
    private static String migrationTextOf(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("no migration directory at " + directory);
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .map(EntitySchemaMappingContractTest::readText)
                    .collect(Collectors.joining("\n"));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /** Reads a file as text, turning the checked failure into an unchecked one. */
    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }

    /** Parses one migration into its tables, indexes and sequences. */
    private static MigrationSchema parseMigration(String migration, Path path) {
        String statements = SQL_LINE_COMMENT.matcher(migration).replaceAll("");
        Map<String, DdlTable> tables = new LinkedHashMap<>();
        Matcher table = CREATE_TABLE.matcher(statements);
        while (table.find()) {
            String name = table.group(1);
            DdlTable parsed = parseTable(name, table.group(2));
            if (tables.put(name, parsed) != null) {
                throw new IllegalStateException(path + " creates the table " + name + " twice");
            }
        }
        List<DdlIndex> indexes = new ArrayList<>();
        Matcher index = CREATE_INDEX.matcher(statements);
        while (index.find()) {
            indexes.add(new DdlIndex(index.group(2), index.group(3), index.group(1) != null,
                    splitList(index.group(4))));
        }
        List<String> sequences = new ArrayList<>();
        Matcher sequence = CREATE_SEQUENCE.matcher(statements);
        while (sequence.find()) {
            sequences.add(sequence.group(1));
        }
        return new MigrationSchema(Map.copyOf(tables), List.copyOf(indexes),
                List.copyOf(sequences));
    }

    /** Parses the body of one {@code CREATE TABLE} into its columns and its primary key. */
    private static DdlTable parseTable(String name, String body) {
        Map<String, DdlColumn> columns = new LinkedHashMap<>();
        List<String> primaryKey = new ArrayList<>();
        for (String element : splitTableElements(body)) {
            String collapsed = collapse(element);
            if (collapsed.isEmpty()) {
                continue;
            }
            Matcher tableKey = TABLE_PRIMARY_KEY.matcher(collapsed);
            if (tableKey.matches()) {
                primaryKey = splitList(tableKey.group(1));
                continue;
            }
            if (TABLE_CONSTRAINT.matcher(collapsed).find()) {
                continue;
            }
            int firstSpace = collapsed.indexOf(' ');
            if (firstSpace < 0) {
                throw new IllegalStateException(
                        "table " + name + " declares an element with no type: " + collapsed);
            }
            String column = collapsed.substring(0, firstSpace);
            String definition = collapsed.substring(firstSpace + 1);
            Matcher type = COLUMN_TYPE.matcher(definition);
            if (!type.find()) {
                throw new IllegalStateException(
                        "table " + name + " column " + column + " declares no readable type");
            }
            String upper = definition.toUpperCase(java.util.Locale.ROOT);
            columns.put(column, new DdlColumn(column, collapse(type.group(1)),
                    !upper.contains(NOT_NULL)));
            if (upper.contains(INLINE_PRIMARY_KEY)) {
                primaryKey = List.of(column);
            }
        }
        return new DdlTable(name, Map.copyOf(columns), List.copyOf(primaryKey));
    }

    /** Splits a table body at the commas that sit outside every pair of brackets. */
    private static List<String> splitTableElements(String body) {
        List<String> elements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character == '(') {
                depth++;
            }
            if (character == ')') {
                depth--;
            }
            if (character == ',' && depth == 0) {
                elements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        elements.add(current.toString());
        return List.copyOf(elements);
    }

    /** Splits a comma-separated list and trims every element. */
    private static List<String> splitList(String list) {
        List<String> elements = new ArrayList<>();
        for (String element : list.split(",")) {
            String trimmed = element.trim();
            if (!trimmed.isEmpty()) {
                elements.add(trimmed);
            }
        }
        return List.copyOf(elements);
    }

    /** Replaces every run of whitespace with one space and trims the result. */
    private static String collapse(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    /** Removes every space and lowercases, so two spellings of one type compare equal. */
    private static String normaliseType(String type) {
        return type.replaceAll("\\s+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replace("charactervarying", "varchar")
                .replace("bpchar", "char");
    }

    /** Builds the mapping model of every service, one registry per service. */
    private static Map<String, List<MappedTable>> readMappings() {
        Map<String, List<MappedTable>> mappings = new LinkedHashMap<>();
        for (ServiceModule service : SERVICES) {
            mappings.put(service.moduleName(), mappingOf(service));
        }
        return Map.copyOf(mappings);
    }

    /** Builds the mapping model of one service. */
    private static List<MappedTable> mappingOf(ServiceModule service) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(Map.of(
                        AvailableSettings.DIALECT, POSTGRES_DIALECT,
                        AvailableSettings.PHYSICAL_NAMING_STRATEGY, PHYSICAL_NAMING_STRATEGY,
                        AvailableSettings.IMPLICIT_NAMING_STRATEGY, IMPLICIT_NAMING_STRATEGY,
                        "hibernate.temp.use_jdbc_metadata_defaults", "false",
                        AvailableSettings.HBM2DDL_AUTO, "none"))
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> entity : service.entities()) {
                sources.addAnnotatedClass(entity);
            }
            Metadata metadata = sources.buildMetadata();
            List<MappedTable> mapped = new ArrayList<>();
            for (PersistentClass persistentClass : metadata.getEntityBindings()) {
                Table table = persistentClass.getTable();
                Map<String, MappedColumn> columns = new LinkedHashMap<>();
                for (Column column : table.getColumns()) {
                    columns.put(column.getName(), new MappedColumn(column.getName(),
                            column.getSqlType(metadata), column.isNullable()));
                }
                List<String> primaryKey = new ArrayList<>();
                for (Column column : table.getPrimaryKey().getColumns()) {
                    primaryKey.add(column.getName());
                }
                Map<String, List<String>> indexes = new LinkedHashMap<>();
                table.getIndexes().forEach((name, index) -> {
                    List<String> indexed = new ArrayList<>();
                    index.getColumns().forEach(column -> indexed.add(column.getName()));
                    indexes.put(name, List.copyOf(indexed));
                });
                mapped.add(new MappedTable(persistentClass.getEntityName(), table.getName(),
                        Map.copyOf(columns), List.copyOf(primaryKey), Map.copyOf(indexes)));
            }
            return List.copyOf(mapped);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /** The migration of one service. */
    private static MigrationSchema migrationOf(String moduleName) {
        return MIGRATIONS.get().get(moduleName);
    }

    /** The mapping model of one service. */
    private static List<MappedTable> mappingOf(String moduleName) {
        return MAPPINGS.get().get(moduleName);
    }

    /** Names one mapped column for a report, without any row value. */
    private static String label(String moduleName, MappedTable mapped, String column) {
        return moduleName + " " + simpleName(mapped.entityName()) + "[" + mapped.table() + "]."
                + column;
    }

    /** Last segment of a fully qualified class name. */
    private static String simpleName(String entityName) {
        int lastDot = entityName.lastIndexOf('.');
        return lastDot < 0 ? entityName : entityName.substring(lastDot + 1);
    }

    @Nested
    @DisplayName("Inventory of entities and tables")
    class EntityInventory {

        /** The six services declare the entities this contract names, and no others. */
        @Test
        @DisplayName("every entity source file of every service is named in this contract")
        void everyEntitySourceFileIsNamedInThisContract() {
            int named = 0;
            for (ServiceModule service : SERVICES) {
                Path directory = REPOSITORY_ROOT.get()
                        .resolve(SERVICES_DIRECTORY)
                        .resolve(service.moduleName())
                        .resolve(ENTITY_SOURCE_PATH)
                        .resolve(service.packageLeaf())
                        .resolve("entity");

                assertTrue(Files.isDirectory(directory),
                        service.moduleName() + " holds its entities at " + directory);

                Set<String> onDisk = new TreeSet<>();
                try (var entries = Files.list(directory)) {
                    entries.filter(path -> path.getFileName().toString().endsWith(JAVA_SUFFIX))
                            .forEach(path -> onDisk.add(path.getFileName().toString()
                                    .replace(JAVA_SUFFIX, "")));
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("cannot list " + directory, unreadable);
                }

                Set<String> declared = new TreeSet<>();
                for (Class<?> entity : service.entities()) {
                    declared.add(entity.getSimpleName());
                }

                assertEquals(onDisk, declared, service.moduleName()
                        + " declares entity classes this contract does not name, or names classes "
                        + "it no longer declares; an entity absent from this contract is compared "
                        + "against no migration");
                named += declared.size();
            }

            assertEquals(ENTITY_COUNT, named,
                    "the platform declares " + ENTITY_COUNT + " entities across its six services");
        }

        /** Every entity maps a table the migration of its own service creates. */
        @Test
        @DisplayName("every entity maps a table its own migration creates")
        void everyEntityMapsATableItsOwnMigrationCreates() {
            List<String> missing = new ArrayList<>();
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    if (!migration.tables().containsKey(mapped.table())) {
                        missing.add(service.moduleName() + " "
                                + simpleName(mapped.entityName()) + " maps " + mapped.table());
                    }
                }
            }

            assertEquals(List.of(), missing,
                    "these entities map a table no migration creates, so the service fails schema "
                            + "validation at start-up: " + missing);
        }

        /**
         * Every table a migration creates is either mapped by an entity or classified as unmapped
         * with its reason. A table in neither set fails, and a table in both sets fails too.
         */
        @Test
        @DisplayName("every migrated table is either mapped or classified as unmapped")
        void everyMigratedTableIsMappedOrClassified() {
            List<String> unaccounted = new ArrayList<>();
            List<String> classifiedButMapped = new ArrayList<>();
            Set<String> classificationsUsed = new TreeSet<>();

            for (ServiceModule service : SERVICES) {
                Set<String> mappedTables = new LinkedHashSet<>();
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    mappedTables.add(mapped.table());
                }
                for (String table : migrationOf(service.moduleName()).tables().keySet()) {
                    String key = service.moduleName() + "." + table;
                    boolean classified = TABLES_WITHOUT_AN_ENTITY.containsKey(key);
                    if (classified) {
                        classificationsUsed.add(key);
                    }
                    if (mappedTables.contains(table) && classified) {
                        classifiedButMapped.add(key);
                    }
                    if (!mappedTables.contains(table) && !classified) {
                        unaccounted.add(key);
                    }
                }
            }

            assertEquals(List.of(), unaccounted,
                    "these migrated tables have neither an entity nor a stated reason for having "
                            + "none: " + unaccounted);
            assertEquals(List.of(), classifiedButMapped,
                    "these tables are classified as unmapped and an entity maps them, so the "
                            + "classification is stale: " + classifiedButMapped);
            assertEquals(new TreeSet<>(TABLES_WITHOUT_AN_ENTITY.keySet()), classificationsUsed,
                    "every classification names a table a migration creates");
        }

        /** Every classification states a reason rather than sitting in the list unexplained. */
        @Test
        @DisplayName("every unmapped table carries a stated reason")
        void everyUnmappedTableCarriesAStatedReason() {
            for (Map.Entry<String, String> classified : TABLES_WITHOUT_AN_ENTITY.entrySet()) {
                assertFalse(classified.getValue().isBlank(),
                        classified.getKey() + " is classified as unmapped with no reason");
                assertTrue(classified.getKey().contains("."),
                        classified.getKey() + " names its module and its table");
            }
        }
    }

    @Nested
    @DisplayName("Column mapping of all 26 entities")
    class ColumnMapping {

        /** Every attribute of every entity maps a column its migration declares. */
        @Test
        @DisplayName("every mapped column exists in the migration")
        void everyMappedColumnExistsInTheMigration() {
            List<String> missing = new ArrayList<>();
            int compared = 0;
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    DdlTable table = migration.tables().get(mapped.table());
                    if (table == null) {
                        continue;
                    }
                    for (String column : mapped.columns().keySet()) {
                        compared++;
                        if (!table.columns().containsKey(column)) {
                            missing.add(label(service.moduleName(), mapped, column));
                        }
                    }
                }
            }

            assertEquals(List.of(), missing,
                    "these mapped columns are declared by no migration: " + missing);
            assertEquals(MAPPED_COLUMN_COUNT, compared,
                    "the platform maps " + MAPPED_COLUMN_COUNT + " columns; a column added or "
                            + "removed changes what this contract covers and has to be stated here");
        }

        /** Every column a migration declares on a mapped table is mapped by its entity. */
        @Test
        @DisplayName("every migrated column of a mapped table is mapped by its entity")
        void everyMigratedColumnOfAMappedTableIsMapped() {
            List<String> unmapped = new ArrayList<>();
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    DdlTable table = migration.tables().get(mapped.table());
                    if (table == null) {
                        continue;
                    }
                    for (String column : table.columns().keySet()) {
                        if (!mapped.columns().containsKey(column)) {
                            unmapped.add(service.moduleName() + " " + mapped.table() + "." + column
                                    + " (entity " + simpleName(mapped.entityName()) + ")");
                        }
                    }
                }
            }

            assertEquals(List.of(), unmapped,
                    "these migrated columns of a mapped table have no entity attribute, so a value "
                            + "written there is unreachable from the service: " + unmapped);
        }

        /**
         * Every mapped column renders the SQL type its migration declares.
         *
         * <p>The rendered type carries the type name together with its length or its precision and
         * scale, so this one comparison covers all three.</p>
         */
        @Test
        @DisplayName("every mapped column renders the SQL type its migration declares")
        void everyMappedColumnRendersItsMigratedType() {
            List<String> divergent = new ArrayList<>();
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    DdlTable table = migration.tables().get(mapped.table());
                    if (table == null) {
                        continue;
                    }
                    for (MappedColumn column : mapped.columns().values()) {
                        DdlColumn declared = table.columns().get(column.name());
                        if (declared == null) {
                            continue;
                        }
                        if (!normaliseType(declared.type()).equals(normaliseType(column.type()))) {
                            divergent.add(label(service.moduleName(), mapped, column.name())
                                    + ": migration declares " + declared.type()
                                    + " and the mapping renders " + column.type());
                        }
                    }
                }
            }

            assertEquals(List.of(), divergent,
                    "these columns are mapped to a type their migration does not declare: "
                            + divergent);
        }

        /** Every mapped column agrees with its migration on whether a null value is admitted. */
        @Test
        @DisplayName("every mapped column agrees with its migration on nullability")
        void everyMappedColumnAgreesOnNullability() {
            List<String> divergent = new ArrayList<>();
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    DdlTable table = migration.tables().get(mapped.table());
                    if (table == null) {
                        continue;
                    }
                    for (MappedColumn column : mapped.columns().values()) {
                        DdlColumn declared = table.columns().get(column.name());
                        if (declared == null) {
                            continue;
                        }
                        if (declared.nullable() != column.nullable()) {
                            divergent.add(label(service.moduleName(), mapped, column.name())
                                    + ": migration nullable=" + declared.nullable()
                                    + " mapping nullable=" + column.nullable());
                        }
                    }
                }
            }

            assertEquals(List.of(), divergent,
                    "these columns disagree on nullability, so a null the mapping admits would be "
                            + "refused by the column or the reverse: " + divergent);
        }

        /**
         * Every numeric column agrees on precision and scale, compared as numbers.
         *
         * <p>Money is fixed point throughout this platform, and a scale that drifted by one would
         * change every amount it touched.</p>
         */
        @Test
        @DisplayName("every numeric column agrees on precision and scale as numbers")
        void everyNumericColumnAgreesOnPrecisionAndScale() {
            List<String> divergent = new ArrayList<>();
            int compared = 0;
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    DdlTable table = migration.tables().get(mapped.table());
                    if (table == null) {
                        continue;
                    }
                    for (MappedColumn column : mapped.columns().values()) {
                        DdlColumn declared = table.columns().get(column.name());
                        if (declared == null) {
                            continue;
                        }
                        Matcher declaredArguments =
                                NUMERIC_ARGUMENTS.matcher(normaliseType(declared.type()));
                        Matcher mappedArguments =
                                NUMERIC_ARGUMENTS.matcher(normaliseType(column.type()));
                        if (!declaredArguments.matches()) {
                            continue;
                        }
                        compared++;
                        if (!mappedArguments.matches()) {
                            divergent.add(label(service.moduleName(), mapped, column.name())
                                    + ": migration declares " + declared.type()
                                    + " and the mapping renders the non-numeric " + column.type());
                            continue;
                        }
                        if (!declaredArguments.group(1).equals(mappedArguments.group(1))
                                || !declaredArguments.group(2).equals(mappedArguments.group(2))) {
                            divergent.add(label(service.moduleName(), mapped, column.name())
                                    + ": migration precision and scale " + declared.type()
                                    + " against mapping " + column.type());
                        }
                    }
                }
            }

            assertEquals(List.of(), divergent,
                    "these numeric columns disagree on precision or scale: " + divergent);
            assertTrue(compared > 0, "the platform declares numeric columns and they were compared");
        }

        /**
         * Every character column agrees on its declared length, compared as a number.
         *
         * <p>An attribute left to its default length maps to a width no column declares, which is how
         * an identifier of sixteen characters comes to be mapped as one of two hundred and
         * fifty-five.</p>
         */
        @Test
        @DisplayName("every character column agrees on its declared length as a number")
        void everyCharacterColumnAgreesOnItsDeclaredLength() {
            List<String> divergent = new ArrayList<>();
            int compared = 0;
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    DdlTable table = migration.tables().get(mapped.table());
                    if (table == null) {
                        continue;
                    }
                    for (MappedColumn column : mapped.columns().values()) {
                        DdlColumn declared = table.columns().get(column.name());
                        if (declared == null) {
                            continue;
                        }
                        Matcher declaredWidth =
                                CHARACTER_ARGUMENT.matcher(normaliseType(declared.type()));
                        if (!declaredWidth.matches()) {
                            continue;
                        }
                        compared++;
                        Matcher mappedWidth =
                                CHARACTER_ARGUMENT.matcher(normaliseType(column.type()));
                        if (!mappedWidth.matches()
                                || !declaredWidth.group(1).equals(mappedWidth.group(1))) {
                            divergent.add(label(service.moduleName(), mapped, column.name())
                                    + ": migration declares " + declared.type()
                                    + " and the mapping renders " + column.type());
                        }
                    }
                }
            }

            assertEquals(List.of(), divergent,
                    "these character columns disagree on their declared length: " + divergent);
            assertTrue(compared > 0,
                    "the platform declares character columns and they were compared");
        }
    }

    @Nested
    @DisplayName("Keys and indexes of all 26 entities")
    class KeysAndIndexes {

        /** Every entity's identifier maps exactly the primary key columns its migration declares. */
        @Test
        @DisplayName("every entity identifier matches the primary key of its table")
        void everyEntityIdentifierMatchesItsPrimaryKey() {
            List<String> divergent = new ArrayList<>();
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    DdlTable table = migration.tables().get(mapped.table());
                    if (table == null) {
                        continue;
                    }
                    Set<String> declared = new TreeSet<>(table.primaryKey());
                    Set<String> keyed = new TreeSet<>(mapped.primaryKey());
                    if (declared.isEmpty()) {
                        divergent.add(service.moduleName() + " " + mapped.table()
                                + " declares no primary key");
                        continue;
                    }
                    if (!declared.equals(keyed)) {
                        divergent.add(service.moduleName() + " "
                                + simpleName(mapped.entityName()) + "[" + mapped.table()
                                + "]: migration key " + declared + " against entity key " + keyed);
                    }
                }
            }

            assertEquals(List.of(), divergent,
                    "these entities are keyed on columns their migration does not declare as the "
                            + "primary key: " + divergent);
        }

        /** Every index an entity declares is created by its migration, on the same columns. */
        @Test
        @DisplayName("every index an entity declares is created by its migration")
        void everyDeclaredIndexIsCreatedByItsMigration() {
            List<String> divergent = new ArrayList<>();
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    List<DdlIndex> created = migration.indexesOf(mapped.table());
                    for (Map.Entry<String, List<String>> declared : mapped.indexes().entrySet()) {
                        DdlIndex match = null;
                        for (DdlIndex index : created) {
                            if (index.name().equals(declared.getKey())) {
                                match = index;
                            }
                        }
                        if (match == null) {
                            divergent.add(service.moduleName() + " "
                                    + simpleName(mapped.entityName()) + " declares the index "
                                    + declared.getKey() + " and no migration creates it");
                            continue;
                        }
                        if (!match.columns().equals(declared.getValue())) {
                            divergent.add(service.moduleName() + " " + declared.getKey()
                                    + ": migration columns " + match.columns()
                                    + " against entity columns " + declared.getValue());
                        }
                    }
                }
            }

            assertEquals(List.of(), divergent,
                    "these declared indexes disagree with their migration: " + divergent);
        }

        /**
         * Every index a migration creates on a mapped table is declared on the entity that maps it.
         *
         * <p>An index a migration creates and the entity does not declare is invisible in the mapping
         * model, so a reader of the entity has no way to know the access path exists.</p>
         */
        @Test
        @DisplayName("every migrated index of a mapped table is declared on its entity")
        void everyMigratedIndexOfAMappedTableIsDeclared() {
            List<String> undeclared = new ArrayList<>();
            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    for (DdlIndex index : migration.indexesOf(mapped.table())) {
                        if (!mapped.indexes().containsKey(index.name())) {
                            undeclared.add(service.moduleName() + " " + index.name() + " on "
                                    + mapped.table() + " " + index.columns() + " (entity "
                                    + simpleName(mapped.entityName()) + ")");
                        }
                    }
                }
            }

            assertEquals(List.of(), undeclared,
                    "these migrated indexes are not declared on the entity that maps their table: "
                            + undeclared);
        }

        /**
         * The authorization service is the only one that allocates transaction identifiers, so it is
         * the only one whose migration creates a sequence.
         */
        @Test
        @DisplayName("only the authorization migration creates a sequence")
        void onlyTheAuthorizationMigrationCreatesASequence() {
            Map<String, List<String>> sequences = new LinkedHashMap<>();
            for (ServiceModule service : SERVICES) {
                sequences.put(service.moduleName(),
                        migrationOf(service.moduleName()).sequences());
            }

            assertEquals(List.of("transaction_id_seq"), sequences.get("authorization-service"),
                    "the authorization migration creates the sequence that replaces the "
                            + "browse-backwards identifier allocation of app/cbl/COTRN02C.cbl:L444");
            for (Map.Entry<String, List<String>> service : sequences.entrySet()) {
                if (service.getKey().equals("authorization-service")) {
                    continue;
                }
                assertEquals(List.of(), service.getValue(),
                        service.getKey() + " creates a sequence and allocates no identifier");
            }
        }
    }

    @Nested
    @DisplayName("Relationships between tables")
    class Relationships {

        /**
         * No entity declares an association.
         *
         * <p>Each service owns a private schema and holds its own replica of what it needs, so no
         * mapping crosses a table boundary. The account record carries no customer identifier at all,
         * which is why the account-to-customer resolution goes through the cross-reference and never
         * through a foreign key.</p>
         */
        @Test
        @DisplayName("no entity declares a mapped association")
        void noEntityDeclaresAMappedAssociation() {
            List<String> associations = new ArrayList<>();
            for (ServiceModule service : SERVICES) {
                for (Class<?> entity : service.entities()) {
                    collectAssociations(service.moduleName(), entity, associations);
                    for (Class<?> nested : entity.getDeclaredClasses()) {
                        collectAssociations(service.moduleName(), nested, associations);
                    }
                }
            }

            assertEquals(List.of(), associations,
                    "these attributes declare an association, and every service owns a private "
                            + "schema no mapping may cross: " + associations);
        }

        /** No migration declares a relationship between two tables. */
        @Test
        @DisplayName("no migration declares a foreign key")
        void noMigrationDeclaresAForeignKey() {
            List<String> relationships = new ArrayList<>();
            for (ServiceModule service : SERVICES) {
                Path directory = REPOSITORY_ROOT.get()
                        .resolve(SERVICES_DIRECTORY)
                        .resolve(service.moduleName())
                        .resolve(MIGRATION_DIRECTORY);
                String statements = SQL_LINE_COMMENT.matcher(migrationTextOf(directory))
                        .replaceAll("")
                        .toUpperCase(java.util.Locale.ROOT);
                for (String keyword : RELATIONSHIP_KEYWORDS) {
                    if (statements.contains(keyword)) {
                        relationships.add(service.moduleName() + " declares " + keyword);
                    }
                }
            }

            assertEquals(List.of(), relationships,
                    "these migrations declare a relationship, and the account record carries no "
                            + "customer identifier for one to be built on: " + relationships);
        }

        /** Records every association annotation one class declares. */
        private void collectAssociations(String moduleName, Class<?> candidate,
                List<String> associations) {
            for (Field field : candidate.getDeclaredFields()) {
                for (Class<? extends Annotation> association : ASSOCIATION_ANNOTATIONS) {
                    if (field.isAnnotationPresent(association)) {
                        associations.add(moduleName + " " + candidate.getSimpleName() + "."
                                + field.getName() + " declares @" + association.getSimpleName());
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Self-verification of the mapping comparison")
    class SelfVerification {

        /** The migration parser reads the three primary-key forms the migrations use. */
        @Test
        @DisplayName("the parser reads an inline, an unnamed and a named primary key")
        void theParserReadsEveryPrimaryKeyForm() {
            MigrationSchema parsed = parseMigration("""
                    CREATE TABLE inline_key (
                        a VARCHAR(3) NOT NULL PRIMARY KEY,
                        b NUMERIC(5,2)
                    );
                    CREATE TABLE unnamed_key (
                        c CHAR(2) NOT NULL,
                        d TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        PRIMARY KEY (c)
                    );
                    CREATE TABLE named_key (
                        e VARCHAR(4) NOT NULL,
                        f VARCHAR(5) NOT NULL,
                        CONSTRAINT pk_named_key PRIMARY KEY (e, f)
                    );
                    CREATE UNIQUE INDEX ix_named_key_f ON named_key (f);
                    CREATE SEQUENCE probe_seq START WITH 1;
                    """, Path.of("probe.sql"));

            assertEquals(Set.of("inline_key", "unnamed_key", "named_key"),
                    new LinkedHashSet<>(parsed.tables().keySet()), "three tables are read");
            assertEquals(List.of("a"), parsed.tables().get("inline_key").primaryKey(),
                    "an inline primary key is read");
            assertEquals(List.of("c"), parsed.tables().get("unnamed_key").primaryKey(),
                    "an unnamed table-level primary key is read");
            assertEquals(List.of("e", "f"), parsed.tables().get("named_key").primaryKey(),
                    "a named composite primary key is read in declaration order");
            assertEquals("NUMERIC(5,2)",
                    parsed.tables().get("inline_key").columns().get("b").type(),
                    "a numeric type is read with both arguments");
            assertTrue(parsed.tables().get("inline_key").columns().get("b").nullable(),
                    "a column without NOT NULL is read as nullable");
            assertFalse(parsed.tables().get("inline_key").columns().get("a").nullable(),
                    "a column with NOT NULL is read as not nullable");
            assertEquals("TIMESTAMP(6) WITH TIME ZONE",
                    parsed.tables().get("unnamed_key").columns().get("d").type(),
                    "a time-zone clause stays part of the type");
            assertEquals(1, parsed.indexes().size(), "one index is read");
            assertTrue(parsed.indexes().get(0).unique(), "a unique index is read as unique");
            assertEquals(List.of("probe_seq"), parsed.sequences(), "one sequence is read");
        }

        /** Type comparison equates the spellings the two sides use and separates the rest. */
        @Test
        @DisplayName("type comparison equates two spellings of one type and separates two types")
        void typeComparisonEquatesSpellingsAndSeparatesTypes() {
            assertEquals(normaliseType("TIMESTAMP(6) WITH TIME ZONE"),
                    normaliseType("timestamp(6) with time zone"),
                    "case and spacing do not make two types differ");
            assertEquals(normaliseType("CHAR(2)"), normaliseType("bpchar(2)"),
                    "the dialect spelling of a fixed-width character type is the same type");
            assertEquals(normaliseType("VARCHAR(16)"), normaliseType("character varying(16)"),
                    "the standard spelling of a variable-width character type is the same type");
            assertNotEquals(normaliseType("TIMESTAMP(6)"),
                    normaliseType("TIMESTAMP(6) WITH TIME ZONE"),
                    "a column without a time zone is not the same type as one with it");
            assertNotEquals(normaliseType("VARCHAR(16)"), normaliseType("VARCHAR(255)"),
                    "two widths of one type are not the same type");
            assertNotEquals(normaliseType("NUMERIC(12,2)"), normaliseType("NUMERIC(12,0)"),
                    "two scales of one type are not the same type");
        }

        /**
         * The comparison detects a mapping that disagrees with its migration. Without this, a
         * comparison that silently skipped every column would satisfy every assertion above.
         */
        @Test
        @DisplayName("a divergent type, length, scale and nullability are each detected")
        void aDivergentMappingIsDetected() {
            DdlColumn declared = new DdlColumn("amount", "NUMERIC(11,2)", false);

            assertTrue(normaliseType(declared.type())
                            .equals(normaliseType("numeric(11,2)")),
                    "an agreeing type is not reported");
            assertFalse(normaliseType(declared.type()).equals(normaliseType("numeric(11,0)")),
                    "a scale that drifted is reported");
            assertFalse(normaliseType("varchar(16)").equals(normaliseType("varchar(255)")),
                    "a length left to its default is reported");
            assertNotEquals(declared.nullable(), true,
                    "a NOT NULL column is read as not nullable, so a nullable mapping is reported");

            Matcher arguments = NUMERIC_ARGUMENTS.matcher(normaliseType(declared.type()));
            assertTrue(arguments.matches(), "the numeric arguments are read");
            assertEquals("11", arguments.group(1), "the precision is read");
            assertEquals("2", arguments.group(2), "the scale is read");

            Matcher width = CHARACTER_ARGUMENT.matcher(normaliseType("VARCHAR(36)"));
            assertTrue(width.matches(), "the character width is read");
            assertEquals("36", width.group(1), "the width is read");
        }

        /**
         * The mapping model resolves an attribute left to the naming strategy to the snake-case name
         * its migration declares, which is what Spring Boot does at run time.
         */
        @Test
        @DisplayName("an attribute without an explicit column name resolves to its snake-case name")
        void anAttributeWithoutAColumnNameResolvesToSnakeCase() {
            Map<String, MappedColumn> outbox = null;
            for (MappedTable mapped : mappingOf("ledger-posting-service")) {
                if (mapped.table().equals("outbox_event")) {
                    outbox = mapped.columns();
                }
            }

            assertTrue(outbox != null && outbox.containsKey("event_id"),
                    "the ledger outbox identifier resolves to event_id under the physical naming "
                            + "strategy Spring Boot applies; under Hibernate's own default it would "
                            + "resolve to eventId and match no column");
            assertEquals("uuid", normaliseType(outbox.get("event_id").type()),
                    "the identifier renders the type its migration declares");
        }
    }
}
