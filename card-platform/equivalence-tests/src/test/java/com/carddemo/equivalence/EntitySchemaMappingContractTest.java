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
import java.util.Comparator;
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
 * Compares every persistent attribute of all thirty entities against the Flyway migration
 * that creates its column.
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
 * registry would fold the repeated names into one table apiece and compare none of them
 * correctly.</p>
 *
 * <p>Comparisons run against the SQL type Hibernate renders for the column, which carries the type
 * name together with its length or its precision and scale. Reading the annotation attributes
 * instead would miss the entities that supply a whole column definition, whose declared length
 * Hibernate reports as its default. Two further assertions parse the numeric arguments out of both
 * rendered types and compare them as numbers, so a precision or a length that agreed only as text
 * would still be caught.</p>
 *
 * <p>Five tables are created by a migration and mapped by no entity. Each is classified in
 * {@link #TABLES_WITHOUT_AN_ENTITY} with the reason, and a table absent from both that list and the
 * entity set fails. A table that IS mapped and also appears in that list fails too, so the list
 * cannot be used to excuse an entity that exists.</p>
 *
 * <p>Two indexes are created by a migration and declared on no entity. Each is classified in
 * {@link #INDEXES_NOT_DECLARED_ON_AN_ENTITY} with the reason, and an exemption naming an index no
 * migration creates on a mapped table fails, so that list cannot go stale either.</p>
 *
 * <p>No failure message here carries a row value. Every fixture and every seed row is out of reach:
 * this class reads migration text and mapping metadata only, and reports table names, column names,
 * type text and key lists.</p>
 */
class EntitySchemaMappingContractTest {

    /** Entities the six services declare between them. */
    private static final int ENTITY_COUNT = 32;

    /**
     * Persistent attributes those entities map between them.
     *
     * <p>The notification read model accounts for two of these where one source field sits: the card
     * token that keys a row and the masked card number that displays it. A masked value identifies no
     * single card, so it can display one and key none.</p>
     *
     * <p>Two more sit on the ledger's balance projection and have no source field at all. That table
     * is a copy of three fields of the account record, and a copy has to record which change it last
     * replicated so a redelivery arriving behind a newer one discards itself. The source reads the
     * account dataset directly at {@code app/cbl/CBTRN02C.cbl:L545}, so it has no copy and needs no
     * such column.</p>
     *
     * <p>Six sit on an outbox row and have no source field either, two each on the account, the
     * authorization and the fraud service's. A row the relay gives up on owes one terminal diagnostic,
     * and that obligation has to outlive a broker outage, so it is a column rather than one unawaited
     * send. The source answers a write it cannot complete by ending the address space at
     * {@code app/cbl/CBTRN02C.cbl:L707-L711}, which leaves the operator a job log and nothing to
     * record. The fraud pair matters most of the three: that service is ADDITIVE, so a lost
     * assessment has no batch job to re-run and no reject dataset holding what was missed.</p>
     *
     * <p>Three sit on the authorization service's account projection and have no source field
     * either, because the source needed none: {@code app/cbl/CBTRN02C.cbl} rewrote the account at
     * {@code :L545-L560} before it validated the next record, so the credit-limit test at
     * {@code :L403-L405} always read every earlier approval. Here the account service owns those
     * accumulators, so an approval reserves its own exposure in two of the three columns and the third
     * bounds how long the reservation counts.</p>
     *
     * <p>The last one sits on the authorization decision row and carries the processing moment the
     * caller declared. {@code app/cbl/COTRN02C.cbl:L470} moves {@code TPROCDTI} into
     * {@code TRAN-PROC-TS} on the record it captures, and this column is where the synchronous path
     * records the same value.</p>
     *
     * <p>One column is absent from this count. The account service replaced its card-keyed
     * {@code card_xref} replica with {@code account_customer_link}, which holds the account and
     * customer pair of {@code app/cpy/CVACT03Y.cpy:L6-L7} and drops {@code XREF-CARD-NUM} at
     * {@code :L5}: no query in that service reads a card, so the column stored a Primary Account
     * Number with no reader. The two services that do key on a card still map it in full.</p>
     *
     * <p>Five more left it for one reason. The ledger's reject row stores
     * {@code REJECT-TRAN-DATA PIC X(350)} whole rather than field by field, so the twelve fields
     * {@code app/cbl/CBTRN02C.cbl:L446-L465} copies onto the reject record reach one column instead
     * of one column each, beside the transaction identifier, the four-digit reason code, its
     * seventy-six-character text and the moment. That is what the source writes: a
     * four-hundred-and-thirty-byte record of the daily-transaction block followed by an eighty-byte
     * trailer, and a block re-parsed on demand cannot disagree with the bytes the reject dataset
     * held.</p>
     *
     * <p>The six most recent belong to the authorization service's {@code replica_gap}, created by
     * {@code V12__replica_gap.sql}: the account and the stream that make up its key, the first and
     * last moments a change for that account failed to apply, how many times, and the fixed phrase
     * naming the last failure. That table is what lets replica currency be measured as consumer lag
     * without missing the one case lag cannot see, a delivery whose offset advanced after its
     * diagnostic was away.</p>
     *
     * <p>The ten most recent are the two correlation columns each of the five outbox tables gained,
     * {@code correlation_id} and {@code causation_id}. Both are nullable, and a relay reads them
     * back to attach the two record headers a consumer joins a published record on. They are
     * additive and have no COBOL ancestor: the source carries no identifier that spans two
     * programs.</p>
     *
     * <p>Seven columns left the model most recently, and the whole of the authorization service's
     * {@code unresolved_card_attempt} table with them.
     * {@code V20__unresolved_card_attempt_withdrawn.sql} drops it, and
     * {@code V24__unresolved_card_decline_is_decided.sql} keeps it dropped for the reason that
     * outlasted the reversal: every column of it recorded a call {@code authorization_decision}
     * already records. A card that resolves no cross-reference row is decided under
     * {@code transaction-declined-v2}, keyed on the transaction identifier this service minted, and
     * the row that holds it carries a null {@code account_id}. The observation that an unknown card
     * was presented is also kept as a metric, and a metric has no column.</p>
     *
     * <p>Sixteen arrived with the card-token rotation, and none has a COBOL ancestor because the
     * source holds no token: {@code app/bms/COCRDSL.bms:L99} shows a card in full. Three of the
     * sixteen record which key a stored token belongs to and where it came from, two on
     * {@code card} and one on {@code authorization_decision}, and thirteen make up the two tables
     * {@code V10__card_token_version_and_rotation.sql} creates. Eight of those thirteen are the
     * audit record of one rotation run and five are one re-keyed card, which is what the three
     * stores holding a token and no card number are re-keyed from.</p>
     */
    private static final int MAPPED_COLUMN_COUNT = 281;

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

    /**
     * Matches one {@code ALTER TABLE ... ALTER COLUMN ... TYPE ...} statement, capturing the table,
     * the column and the new type.
     *
     * <p>A later migration that widens a column changes the schema a service validates against
     * exactly as the statement that created it. Reading the change here is what keeps this
     * comparison measuring the schema rather than its first version.
     */
    private static final Pattern ALTER_COLUMN_TYPE = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+ALTER\\s+COLUMN\\s+(\\w+)\\s+"
                    + "(?:SET\\s+DATA\\s+)?TYPE\\s+((?:DOUBLE\\s+PRECISION|\\w+)"
                    + "(?:\\s*\\([^)]*\\))?(?:\\s+WITH(?:OUT)?\\s+TIME\\s+ZONE)?)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Matches one later migration renaming a column, capturing the table, the old name and the new.
     *
     * <p>A rename changes the schema a service validates against as completely as a create does:
     * the old name is gone and an entity mapping it would stop start-up. Reading the rename here is
     * what keeps this comparison measuring the schema Flyway leaves behind rather than the one the
     * first migration built. The notification service is the live case: {@code
     * V5__rendered_not_delivered.sql} renames {@code attempted_at} to {@code rendered_at}, because
     * nothing on this platform sends a cardholder alert and the old name claimed one.
     *
     * <p>The rename pass runs before the passes that alter a type, alter a nullability or add a
     * column, so a migration that renames a column and then alters it names the new column in that
     * later statement, which is the natural authoring order. A migration that altered a column and
     * then renamed it would have to name the old column in the alter, and this parser would not
     * find it; no migration in this repository does that, and the pass reports the mismatch by name
     * rather than failing silently.
     */
    private static final Pattern ALTER_RENAME_COLUMN = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+RENAME\\s+COLUMN\\s+(\\w+)\\s+TO\\s+(\\w+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Matches one later migration renaming an index, capturing the old name and the new.
     *
     * <p>PostgreSQL indexes a column by number, so renaming a column leaves an index definition
     * correct and only its name stale. Renaming the index is therefore the companion of renaming
     * the column, and the schema carries the new name.
     */
    private static final Pattern ALTER_RENAME_INDEX = Pattern.compile(
            "ALTER\\s+INDEX\\s+(?:IF\\s+EXISTS\\s+)?(\\w+)\\s+RENAME\\s+TO\\s+(\\w+)",
            Pattern.CASE_INSENSITIVE);

    /** Matches one later migration adding a column to an existing table. */
    private static final Pattern ALTER_ADD_COLUMN = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(\\w+)\\s+([^;]+);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Matches one later migration setting or dropping the {@code NOT NULL} of an existing column,
     * capturing the table, the column and which of the two it did.
     *
     * <p>A column a later migration made mandatory is mandatory in the migrated schema, and an
     * entity mapping that still admitted a null there would be reported by a comparison that read
     * only the {@code CREATE TABLE}. Reading the whole migration set is what makes the comparison
     * describe the schema the service actually runs against.
     */
    private static final Pattern ALTER_COLUMN_NULLABILITY = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+ALTER\\s+COLUMN\\s+(\\w+)\\s+"
                    + "(SET|DROP)\\s+NOT\\s+NULL",
            Pattern.CASE_INSENSITIVE);

    /**
     * Matches one later migration adding a primary key to an existing table, capturing the table and
     * the key columns.
     *
     * <p>A table can carry one primary key, so this replaces whatever the {@code CREATE TABLE}
     * declared. The {@code DROP CONSTRAINT} that has to precede it needs no handler of its own: it
     * removes the key this statement then supplies, and a migration that dropped a key and supplied
     * none would leave a table no entity could be keyed against, which the key comparison reports.
     */
    private static final Pattern ALTER_ADD_PRIMARY_KEY = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+CONSTRAINT\\s+\\w+\\s+"
                    + "PRIMARY\\s+KEY\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Matches one {@code DROP TABLE} statement and captures the table it removes.
     *
     * <p>A migration that has been applied is not edited, so a table a later migration drops is
     * still created, indexed and seeded by the migration that introduced it. The schema a service
     * validates against is the one Flyway leaves behind, which no longer holds that table, and a
     * comparison that read only the creates would demand an entity for a table the database does
     * not have. The account service is the live case: {@code V4} creates its {@code card_xref}
     * replica and {@code V7} drops it, having replaced it with {@code account_customer_link}.
     */
    private static final Pattern DROP_TABLE = Pattern.compile(
            "DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(\\w+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Matches one {@code DROP INDEX} statement and captures the index it removes.
     *
     * <p>The same reasoning as {@link #DROP_TABLE}, for an index whose table stays. An applied
     * migration is not edited, so an index a later migration drops is still created by the migration
     * that introduced it, and a comparison reading only the creates would demand an
     * {@code @Index} declaration for an index the database no longer has.
     *
     * <p>The authorization service is the live case. {@code V5} created
     * {@code ix_authorization_decision_account_decided} and {@code ix_authorization_decision_actor}
     * for four repository read methods, no production caller ever reached any of them, and
     * {@code V18} drops both. Without this pattern that withdrawal cannot be expressed: the entity
     * would have to keep declaring two indexes the schema does not contain.
     */
    private static final Pattern DROP_INDEX = Pattern.compile(
            "DROP\\s+INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+EXISTS\\s+)?(\\w+)",
            Pattern.CASE_INSENSITIVE);

    /** Matches the version a migration file name opens with, {@code V12__thing.sql} giving 12. */
    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__");

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
                    com.carddemo.authorization.entity.AuthorizationDecisionEntity.class,
                    com.carddemo.authorization.entity.CardCrossReferenceEntity.class,
                    com.carddemo.authorization.entity.OutboxEventEntity.class,
                    com.carddemo.authorization.entity.ProcessedEventEntity.class,
                    com.carddemo.authorization.entity.ReplicaGapEntity.class)),
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
                    com.carddemo.notification.entity.CardholderContextEntity.class,
                    com.carddemo.notification.entity.NotificationLogEntity.class,
                    com.carddemo.notification.entity.ProcessedEventEntity.class,
                    com.carddemo.notification.entity.StatementTransactionEntity.class)),
            new ServiceModule("account-service", "account", List.of(
                    com.carddemo.account.entity.AccountCustomerLinkEntity.class,
                    com.carddemo.account.entity.AccountEntity.class,
                    com.carddemo.account.entity.CustomerEntity.class,
                    com.carddemo.account.entity.DisclosureGroupEntity.class,
                    com.carddemo.account.entity.OutboxEventEntity.class,
                    com.carddemo.account.entity.ProcessedEventEntity.class)),
            new ServiceModule("card-service", "card", List.of(
                    com.carddemo.card.entity.CardCrossReferenceEntity.class,
                    com.carddemo.card.entity.CardEntity.class,
                    com.carddemo.card.entity.CardTokenRotationEntity.class,
                    com.carddemo.card.entity.CardTokenRotationMappingEntity.class,
                    com.carddemo.card.entity.OutboxEventEntity.class,
                    com.carddemo.card.entity.ProcessedEventEntity.class)));

    /**
     * Tables a migration creates and no entity maps, each with the reason.
     *
     * <p>The key is the module name and the table name. Every entry is either a table Flyway seeds
     * and the running code reads through something other than persistence, or a table no entity
     * maps yet.</p>
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

    /**
     * Indexes a migration creates on a mapped table and the entity declares none of, each with the
     * reason.
     *
     * <p>The key is the module name and the index name. A {@code jakarta.persistence.Index}
     * declaration feeds schema generation, which no service runs: Flyway owns every schema and
     * {@code ddl-auto} is {@code validate}, which reads columns and never indexes. An entry here
     * changes no runtime behaviour and keeps the divergence visible.</p>
     */
    private static final Map<String, String> INDEXES_NOT_DECLARED_ON_AN_ENTITY =
            indexesNotDeclaredOnAnEntity();

    /** Builds {@link #INDEXES_NOT_DECLARED_ON_AN_ENTITY}. */
    private static Map<String, String> indexesNotDeclaredOnAnEntity() {
        Map<String, String> classified = new LinkedHashMap<>();
        classified.put("notification-service.ix_notification_log_card_token",
                "NotificationLogEntity declares no index. Its specification states the class "
                        + "carries none, and this index orders rendered_at descending, which "
                        + "Hibernate drops from a declared column list, so a declaration would "
                        + "read [card_token, rendered_at] against migration columns "
                        + "[card_token, rendered_at DESC] and fail "
                        + "everyDeclaredIndexIsCreatedByItsMigration");
        classified.put("notification-service.ix_notification_log_rendered_at",
                "NotificationLogEntity declares no index, on the same footing as "
                        + "ix_notification_log_card_token. V5__rendered_not_delivered.sql renamed "
                        + "it from ix_notification_log_attempted_at, because nothing on this "
                        + "platform sends a cardholder alert and the old name claimed one");
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
     * <p>The order is the numeric version, not the file name. A plain name sort is the same thing
     * only while every version has one digit: once a service reaches {@code V10}, {@code V16} sorts
     * ahead of {@code V5}, and every statement that removes what an earlier migration created is then
     * read as standing before the create it names. That is silent — the drop is simply not applied —
     * which is how the two withdrawn indexes of {@code authorization_decision} still looked present
     * after {@code V16} dropped them.</p>
     *
     * @param directory migration directory of one module
     * @return the text of every {@code .sql} below it, in applied version order
     */
    private static String migrationTextOf(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("no migration directory at " + directory);
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(EntitySchemaMappingContractTest::versionOf)
                            .thenComparing(path -> path.getFileName().toString()))
                    .map(EntitySchemaMappingContractTest::readText)
                    .collect(Collectors.joining("\n"));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + directory, unreadable);
        }
    }

    /**
     * Reads the version a migration file name opens with, so files join in the order Flyway applies.
     *
     * @param migration one migration path
     * @return the integer following the leading {@code V}
     * @throws IllegalStateException when the name does not open with a version
     */
    private static int versionOf(Path migration) {
        Matcher version = MIGRATION_VERSION.matcher(migration.getFileName().toString());
        if (!version.find()) {
            throw new IllegalStateException("migration name carries no version: " + migration);
        }
        return Integer.parseInt(version.group(1));
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
        Map<String, Integer> tableCreatedAt = new LinkedHashMap<>();
        Matcher table = CREATE_TABLE.matcher(statements);
        while (table.find()) {
            String name = table.group(1);
            DdlTable parsed = parseTable(name, table.group(2));
            if (tables.put(name, parsed) != null) {
                throw new IllegalStateException(path + " creates the table " + name + " twice");
            }
            tableCreatedAt.put(name, table.start());
        }
        List<DdlIndex> indexes = new ArrayList<>();
        Map<String, Integer> indexCreatedAt = new LinkedHashMap<>();
        Matcher index = CREATE_INDEX.matcher(statements);
        while (index.find()) {
            indexes.add(new DdlIndex(index.group(2), index.group(3), index.group(1) != null,
                    indexColumns(index.group(4))));
            indexCreatedAt.put(index.group(2), index.start());
        }
        Matcher renamedColumn = ALTER_RENAME_COLUMN.matcher(statements);
        while (renamedColumn.find()) {
            String tableName = renamedColumn.group(1);
            String before = renamedColumn.group(2);
            String after = renamedColumn.group(3);
            DdlTable renamed = tables.get(tableName);
            if (renamed == null) {
                throw new IllegalStateException(path + " renames the column " + before
                        + " of the table " + tableName + ", which no migration creates");
            }
            DdlColumn column = renamed.columns().get(before);
            if (column == null) {
                if (renamed.columns().containsKey(after)) {
                    continue;
                }
                throw new IllegalStateException(path + " renames the column " + before
                        + ", which the table " + tableName + " does not declare");
            }
            Map<String, DdlColumn> renamedColumns = new LinkedHashMap<>();
            renamed.columns().forEach((name, existing) -> renamedColumns.put(
                    name.equals(before) ? after : name,
                    name.equals(before)
                            ? new DdlColumn(after, existing.type(), existing.nullable())
                            : existing));
            List<String> key = renamed.primaryKey().stream()
                    .map(part -> part.equals(before) ? after : part)
                    .toList();
            tables.put(tableName, new DdlTable(tableName, Map.copyOf(renamedColumns), key));
            indexes.replaceAll(existing -> existing.table().equals(tableName)
                    ? new DdlIndex(existing.name(), existing.table(), existing.unique(),
                            existing.columns().stream()
                                    .map(part -> part.equals(before) ? after : part)
                                    .toList())
                    : existing);
        }
        Matcher renamedIndex = ALTER_RENAME_INDEX.matcher(statements);
        while (renamedIndex.find()) {
            String before = renamedIndex.group(1);
            String after = renamedIndex.group(2);
            indexes.replaceAll(existing -> existing.name().equals(before)
                    ? new DdlIndex(after, existing.table(), existing.unique(), existing.columns())
                    : existing);
        }
        Matcher alteredColumn = ALTER_COLUMN_TYPE.matcher(statements);
        while (alteredColumn.find()) {
            String tableName = alteredColumn.group(1);
            String columnName = alteredColumn.group(2);
            String alteredType = collapse(alteredColumn.group(3));
            DdlTable altered = tables.get(tableName);
            if (altered == null) {
                throw new IllegalStateException(path + " alters the column " + columnName
                        + " of the table " + tableName + ", which no migration creates");
            }
            DdlColumn before = altered.columns().get(columnName);
            if (before == null) {
                throw new IllegalStateException(path + " alters the column " + columnName
                        + ", which the table " + tableName + " does not declare");
            }
            Map<String, DdlColumn> alteredColumns = new LinkedHashMap<>(altered.columns());
            alteredColumns.put(columnName, new DdlColumn(columnName, alteredType,
                    before.nullable()));
            tables.put(tableName, new DdlTable(tableName, Map.copyOf(alteredColumns),
                    altered.primaryKey()));
        }
        Matcher addedColumn = ALTER_ADD_COLUMN.matcher(statements);
        while (addedColumn.find()) {
            String tableName = addedColumn.group(1);
            String columnName = addedColumn.group(2);
            String definition = collapse(addedColumn.group(3));
            DdlTable altered = tables.get(tableName);
            if (altered == null) {
                throw new IllegalStateException(path + " adds the column " + columnName
                        + " to the table " + tableName + ", which no migration creates");
            }
            Matcher type = COLUMN_TYPE.matcher(definition);
            if (!type.find()) {
                throw new IllegalStateException(path + " adds the column " + columnName
                        + " without a readable type");
            }
            Map<String, DdlColumn> alteredColumns = new LinkedHashMap<>(altered.columns());
            if (alteredColumns.put(columnName, new DdlColumn(columnName, collapse(type.group(1)),
                    !definition.toUpperCase(java.util.Locale.ROOT).contains(NOT_NULL))) != null) {
                throw new IllegalStateException(path + " adds the column " + columnName
                        + ", which the table " + tableName + " already declares");
            }
            tables.put(tableName, new DdlTable(tableName, Map.copyOf(alteredColumns),
                    altered.primaryKey()));
        }
        Matcher nullability = ALTER_COLUMN_NULLABILITY.matcher(statements);
        while (nullability.find()) {
            String tableName = nullability.group(1);
            String columnName = nullability.group(2);
            boolean nowNullable = "DROP".equalsIgnoreCase(nullability.group(3));
            DdlTable altered = tables.get(tableName);
            if (altered == null) {
                throw new IllegalStateException(path + " alters the nullability of " + columnName
                        + " on the table " + tableName + ", which no migration creates");
            }
            DdlColumn before = altered.columns().get(columnName);
            if (before == null) {
                throw new IllegalStateException(path + " alters the nullability of " + columnName
                        + ", which the table " + tableName + " does not declare");
            }
            Map<String, DdlColumn> alteredColumns = new LinkedHashMap<>(altered.columns());
            alteredColumns.put(columnName,
                    new DdlColumn(columnName, before.type(), nowNullable));
            tables.put(tableName, new DdlTable(tableName, Map.copyOf(alteredColumns),
                    altered.primaryKey()));
        }
        Matcher addedKey = ALTER_ADD_PRIMARY_KEY.matcher(statements);
        while (addedKey.find()) {
            String tableName = addedKey.group(1);
            DdlTable altered = tables.get(tableName);
            if (altered == null) {
                throw new IllegalStateException(path + " adds a primary key to the table "
                        + tableName + ", which no migration creates");
            }
            List<String> replacement = splitList(addedKey.group(2));
            for (String column : replacement) {
                if (!altered.columns().containsKey(column)) {
                    throw new IllegalStateException(path + " keys the table " + tableName + " on "
                            + column + ", which it does not declare");
                }
            }
            tables.put(tableName, new DdlTable(tableName, altered.columns(),
                    List.copyOf(replacement)));
        }
        List<String> sequences = new ArrayList<>();
        Matcher sequence = CREATE_SEQUENCE.matcher(statements);
        while (sequence.find()) {
            sequences.add(sequence.group(1));
        }
        // A dropped index leaves the schema while its table stays. Applied with the same guard as
        // the table drop below: only when the drop stands after the create it removes, so a name
        // created again afterwards stays.
        Matcher droppedIndex = DROP_INDEX.matcher(statements);
        while (droppedIndex.find()) {
            String name = droppedIndex.group(1);
            Integer createdIndexAt = indexCreatedAt.get(name);
            if (createdIndexAt == null) {
                // IF EXISTS is how a migration stays safe on a schema where an earlier hand-run
                // already removed the index, so a drop of something no migration creates is not an
                // error here the way a table drop is.
                continue;
            }
            if (createdIndexAt > droppedIndex.start()) {
                continue;
            }
            indexes.removeIf(withdrawn -> withdrawn.name().equals(name));
        }
        // A dropped table leaves the schema, and its indexes leave with it. The drop is applied
        // last and only when it stands after the create it removes, so a name created again
        // afterwards stays. Every other statement kind above is applied in its own pass for the
        // same reason: the migrations are read as one joined text in version order.
        Matcher drop = DROP_TABLE.matcher(statements);
        while (drop.find()) {
            String name = drop.group(1);
            Integer created = tableCreatedAt.get(name);
            if (created == null) {
                throw new IllegalStateException(path + " drops the table " + name
                        + ", which no migration creates");
            }
            if (created > drop.start()) {
                continue;
            }
            tables.remove(name);
            indexes.removeIf(onDroppedTable -> onDroppedTable.table().equals(name));
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

    /**
     * Splits an index column list and drops the sort keyword each element may carry.
     *
     * <p>A migration may name the order a column is indexed in, as {@code assessed_at DESC} does.
     * The mapping model reports column names alone, so the keyword is dropped here and this
     * comparison stays a comparison of columns. The physical order of an index is asserted by the
     * owning service's own integration test against the catalogue.</p>
     *
     * @param list the column list of one {@code CREATE INDEX} statement
     * @return the column names, in the order the statement names them
     */
    private static List<String> indexColumns(String list) {
        List<String> columns = new ArrayList<>();
        for (String element : splitList(list)) {
            columns.add(element.replaceFirst("(?i)\\s+(ASC|DESC)$", ""));
        }
        return List.copyOf(columns);
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
                    index.getSelectables().forEach(selectable -> indexed.add(selectable.getText()));
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
    @DisplayName("Column mapping of all 32 entities")
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
    @DisplayName("Keys and indexes of all 32 entities")
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
            List<String> exemptButDeclared = new ArrayList<>();
            Set<String> exemptionsUsed = new TreeSet<>();

            for (ServiceModule service : SERVICES) {
                MigrationSchema migration = migrationOf(service.moduleName());
                for (MappedTable mapped : mappingOf(service.moduleName())) {
                    for (DdlIndex index : migration.indexesOf(mapped.table())) {
                        String key = service.moduleName() + "." + index.name();
                        boolean exempt = INDEXES_NOT_DECLARED_ON_AN_ENTITY.containsKey(key);
                        boolean declared = mapped.indexes().containsKey(index.name());
                        if (exempt) {
                            exemptionsUsed.add(key);
                        }
                        if (exempt && declared) {
                            exemptButDeclared.add(key);
                        }
                        if (!exempt && !declared) {
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
            assertEquals(List.of(), exemptButDeclared,
                    "these indexes are exempt from declaration and their entity declares them, so "
                            + "the exemption is stale: " + exemptButDeclared);
            assertEquals(new TreeSet<>(INDEXES_NOT_DECLARED_ON_AN_ENTITY.keySet()), exemptionsUsed,
                    "every exemption names an index a migration creates on a mapped table");
        }

        /** Every exempt index states a reason rather than sitting in the list unexplained. */
        @Test
        @DisplayName("every index exempt from declaration carries a stated reason")
        void everyExemptIndexCarriesAStatedReason() {
            for (Map.Entry<String, String> exempt : INDEXES_NOT_DECLARED_ON_AN_ENTITY.entrySet()) {
                assertFalse(exempt.getValue().isBlank(),
                        exempt.getKey() + " is exempt from declaration with no reason");
                assertTrue(exempt.getKey().contains("."),
                        exempt.getKey() + " names its module and its index");
            }
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

        /**
         * The parser reads a later migration that narrows a column and widens a primary key.
         *
         * <p>A comparison that read only the {@code CREATE TABLE} would describe the schema as it
         * stood at the first migration, and would report a correct entity mapping as wrong the
         * moment any service evolved a column or a key. Every statement form the platform's
         * migrations use is read here so that comparison describes what the service runs against.
         */
        @Test
        @DisplayName("the parser applies a later SET NOT NULL and a later composite primary key")
        void theParserAppliesALaterNarrowingAndALaterKey() {
            MigrationSchema parsed = parseMigration("""
                    CREATE TABLE marker (
                        event_id UUID NOT NULL,
                        processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        consumed_topic VARCHAR(128),
                        CONSTRAINT pk_marker PRIMARY KEY (event_id)
                    );
                    ALTER TABLE marker ALTER COLUMN consumed_topic SET NOT NULL;
                    ALTER TABLE marker DROP CONSTRAINT pk_marker;
                    ALTER TABLE marker ADD CONSTRAINT pk_marker PRIMARY KEY (event_id, consumed_topic);
                    """, Path.of("probe.sql"));

            DdlTable marker = parsed.tables().get("marker");
            assertFalse(marker.columns().get("consumed_topic").nullable(),
                    "a column a later migration made mandatory is read as mandatory");
            assertEquals("VARCHAR(128)", marker.columns().get("consumed_topic").type(),
                    "narrowing the nullability leaves the type alone");
            assertEquals(List.of("event_id", "consumed_topic"), marker.primaryKey(),
                    "a primary key a later migration replaced is read in its new form");
            assertTrue(marker.columns().get("event_id").nullable() == false,
                    "the untouched columns keep what the CREATE TABLE declared");
        }

        /**
         * The parser reads a later migration that renames a column and its index.
         *
         * <p>A rename leaves nothing of the old name in the schema, so a comparison that misses it
         * reports the entity's new field as mapping a column no migration declares and the
         * migration's old column as mapped by nothing. The key and the index column list follow the
         * rename, because both name the column rather than copy it.
         */
        @Test
        @DisplayName("the parser applies a later RENAME COLUMN and ALTER INDEX RENAME TO")
        void theParserAppliesALaterRename() {
            MigrationSchema parsed = parseMigration("""
                    CREATE TABLE probe (
                        a VARCHAR(3) NOT NULL,
                        stamped TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_probe PRIMARY KEY (a)
                    );
                    CREATE INDEX ix_probe_stamped ON probe (stamped);
                    ALTER TABLE probe RENAME COLUMN stamped TO produced;
                    ALTER INDEX ix_probe_stamped RENAME TO ix_probe_produced;
                    """, Path.of("probe.sql"));

            DdlTable probe = parsed.tables().get("probe");
            assertTrue(probe.columns().containsKey("produced"),
                    "the renamed column is read under its new name");
            assertFalse(probe.columns().containsKey("stamped"),
                    "the old column name leaves the schema with the rename");
            assertEquals("TIMESTAMP(6) WITH TIME ZONE", probe.columns().get("produced").type(),
                    "a rename changes the name and nothing else");
            assertFalse(probe.columns().get("produced").nullable(),
                    "a rename leaves the nullability alone");
            assertEquals(List.of("ix_probe_produced"),
                    parsed.indexes().stream().map(DdlIndex::name).toList(),
                    "the index is read under its new name");
            assertEquals(List.of("produced"), parsed.indexes().get(0).columns(),
                    "the index column list follows the column rename");
        }

        /**
         * A rename already applied is read as applied rather than as an error.
         *
         * <p>The rename pass runs over the joined migration text, so a schema whose column already
         * carries the new name has nothing left to do. Reporting that as a missing column would
         * fail a service whose migrations are correct.
         */
        @Test
        @DisplayName("the parser tolerates a rename whose target name is already in place")
        void theParserToleratesAnAlreadyAppliedRename() {
            MigrationSchema parsed = parseMigration("""
                    CREATE TABLE probe (
                        produced TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT pk_probe PRIMARY KEY (produced)
                    );
                    ALTER TABLE probe RENAME COLUMN stamped TO produced;
                    """, Path.of("probe.sql"));

            assertEquals(List.of("produced"),
                    List.copyOf(parsed.tables().get("probe").columns().keySet()),
                    "the column stays under the name it already carries");
        }

        /**
         * The parser reads a later migration that makes a mandatory column optional again.
         *
         * <p>The reverse statement is read for the same reason the forward one is: a model that
         * applied only the narrowing would describe a schema the service does not run against.
         */
        @Test
        @DisplayName("the parser applies a later DROP NOT NULL")
        void theParserAppliesALaterWidening() {
            MigrationSchema parsed = parseMigration("""
                    CREATE TABLE probe (
                        a VARCHAR(3) NOT NULL,
                        b VARCHAR(4) NOT NULL,
                        CONSTRAINT pk_probe PRIMARY KEY (a)
                    );
                    ALTER TABLE probe ALTER COLUMN b DROP NOT NULL;
                    """, Path.of("probe.sql"));

            assertTrue(parsed.tables().get("probe").columns().get("b").nullable(),
                    "a column a later migration made optional is read as optional");
            assertEquals(List.of("a"), parsed.tables().get("probe").primaryKey(),
                    "the key is untouched");
        }

        /**
         * A table a later migration drops leaves the schema, and its indexes leave with it.
         *
         * <p>Without this, the comparison would read the schema as the union of every table any
         * migration ever created and demand an entity for one the database no longer holds.</p>
         */
        @Test
        @DisplayName("the parser removes a table a later migration drops")
        void theParserRemovesADroppedTable() {
            MigrationSchema parsed = parseMigration("""
                    CREATE TABLE kept (
                        a VARCHAR(3) NOT NULL,
                        CONSTRAINT pk_kept PRIMARY KEY (a)
                    );
                    CREATE TABLE removed (
                        b VARCHAR(4) NOT NULL,
                        CONSTRAINT pk_removed PRIMARY KEY (b)
                    );
                    CREATE INDEX ix_removed_b ON removed (b);
                    CREATE INDEX ix_kept_a ON kept (a);
                    DROP TABLE removed;
                    """, Path.of("probe.sql"));

            assertEquals(Set.of("kept"), parsed.tables().keySet(),
                    "the dropped table is not part of the schema the service validates against");
            assertEquals(List.of(), parsed.indexesOf("removed"),
                    "an index on a dropped table goes with the table");
            assertEquals(List.of("ix_kept_a"),
                    parsed.indexesOf("kept").stream().map(DdlIndex::name).toList(),
                    "an index on a surviving table is untouched");
        }

        /**
         * An index a later migration drops leaves the schema while its table stays.
         *
         * <p>Without this, the comparison would read the schema as every index any migration ever
         * created and demand an {@code @Index} declaration for one the database no longer holds. The
         * authorization service is the live case: {@code V16} withdraws two composites of
         * {@code authorization_decision} that no read ever used, and the table remains.</p>
         */
        @Test
        @DisplayName("the parser removes an index a later migration drops, keeping its table")
        void theParserRemovesADroppedIndex() {
            MigrationSchema parsed = parseMigration("""
                    CREATE TABLE probe (
                        a VARCHAR(3) NOT NULL,
                        b VARCHAR(4) NOT NULL,
                        CONSTRAINT pk_probe PRIMARY KEY (a)
                    );
                    CREATE INDEX ix_probe_a ON probe (a);
                    CREATE INDEX ix_probe_b ON probe (b);
                    DROP INDEX IF EXISTS ix_probe_b;
                    DROP INDEX IF EXISTS ix_never_created;
                    """, Path.of("probe.sql"));

            assertEquals(Set.of("probe"), parsed.tables().keySet(),
                    "dropping an index does not drop its table");
            assertEquals(List.of("ix_probe_a"),
                    parsed.indexesOf("probe").stream().map(DdlIndex::name).toList(),
                    "the dropped index leaves and the other one stays");
        }

        /**
         * An index dropped and then created again is present, as the schema Flyway leaves behind has it.
         *
         * <p>The guard is the same one the table drop uses: a drop is applied only when it stands
         * after the create it names.</p>
         */
        @Test
        @DisplayName("the parser keeps an index re-created after its drop")
        void theParserKeepsAnIndexRecreatedAfterItsDrop() {
            MigrationSchema parsed = parseMigration("""
                    CREATE TABLE probe (
                        a VARCHAR(3) NOT NULL,
                        CONSTRAINT pk_probe PRIMARY KEY (a)
                    );
                    DROP INDEX IF EXISTS ix_probe_a;
                    CREATE INDEX ix_probe_a ON probe (a);
                    """, Path.of("probe.sql"));

            assertEquals(List.of("ix_probe_a"),
                    parsed.indexesOf("probe").stream().map(DdlIndex::name).toList(),
                    "a name created after the drop that names it survives");
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
