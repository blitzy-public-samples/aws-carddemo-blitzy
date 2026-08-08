package com.carddemo.account.repository;

import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.AccountCustomerLinkEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.entity.DisclosureGroupEntity;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.entity.ProcessedEventEntity;

import jakarta.persistence.LockModeType;
import jakarta.persistence.Version;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.Lock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the migrated catalogue of this service's schema and asserts six constraints it does not
 * declare.
 *
 * <p>Three of the six carry a violating row count taken from the seeded rows. Declaring any of
 * those three would stop the seed migration, so each of the three assertions records its count.
 * Every query here is read-only, over
 * {@code information_schema.table_constraints}, {@code information_schema.check_constraints},
 * {@code information_schema.key_column_usage}, {@code information_schema.referential_constraints}
 * and {@code information_schema.columns}. No test inserts, updates or deletes a row.</p>
 *
 * <p><b>Absence one, the credit-score range.</b> No check constraint restricts
 * {@code customer.fico_credit_score}, and 21 seeded rows carry a score outside 300 through 850.
 * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22} occupies one-based
 * offsets 330 to 332 of {@code app/data/ASCII/custdata.txt}, where the observed values include
 * {@code 001}, {@code 044}, {@code 051} and {@code 274}. The range lives in the input validator:
 * {@code 1275-EDIT-FICO-SCORE.} at {@code app/cbl/COACTUPC.cbl:L2514} reports
 * {@code ': should be between 300 and 850'} at {@code L2523}.</p>
 *
 * <p><b>Absence two, the state code.</b> No check constraint and no foreign key relate
 * {@code customer.address_state_code} to {@code us_state_code}, and 5 seeded rows carry a code the
 * band omits. The state code occupies offsets 235 to 236 of the same fixture, from
 * {@code CUST-ADDR-STATE-CD PIC X(02)} at {@code app/cpy/CVCUS01Y.cpy:L12}. The band is
 * {@code 88 VALID-US-STATE-CODE} at {@code app/cpy/CSLKPCDY.cpy:L1013}, and it holds {@code AS},
 * {@code GU} and {@code VI}, so five seeded rows violate and not eight.</p>
 *
 * <p><b>Absence three, the state and zip combination.</b> No check constraint and no foreign key
 * relate the pair of {@code customer.address_state_code} and the first two characters of
 * {@code customer.address_zip} to {@code us_state_zip_prefix}. 48 seeded rows fail that pair, and
 * the only passing keys are {@code AP96} and {@code ME49}. The zip field is
 * {@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L14} of the same fixture, and the
 * combination band is {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} at
 * {@code app/cpy/CSLKPCDY.cpy:L1073}.</p>
 *
 * <p><b>A corollary that reaches the validation tests.</b> Seeded record one of
 * {@code app/data/ASCII/custdata.txt} carries state {@code NC} at offsets 235 to 236 and zip
 * {@code 12546} in the field above, giving key {@code NC12}. The combination band omits
 * {@code NC12} and holds {@code NC27} and {@code NC28} alone. A validation test therefore
 * constructs its own inputs, and no validation test drives a validator from a seeded row.</p>
 *
 * <p><b>Absence four, a row version.</b> No migrated table carries a row-version or
 * optimistic-lock column, and none of the six Jakarta Persistence (JPA) entity classes declares a
 * version attribute. Neither {@link AccountRepository} nor {@link CustomerRepository} requests an
 * optimistic lock mode. The source mechanism is {@code 9700-CHECK-CHANGE-IN-REC.} at
 * {@code app/cbl/COACTUPC.cbl:L4109}, whose account block opens at {@code L4114}. A search for
 * {@code VERSION} across that program returns nothing.</p>
 *
 * <p><b>Absence five, two joins the schema leaves undeclared.</b> No foreign key on
 * {@code account} names {@code customer} as its parent, and no foreign key covers
 * {@code account.group_id}. {@code app/cpy/CVACT01Y.cpy:L5-L16} declares twelve mapped fields and
 * none of the twelve is a customer identifier. An alternate-index census over the Job Control
 * Language members carries the same reading: {@code app/jcl/ACCTFILE.jcl},
 * {@code app/jcl/CUSTFILE.jcl} and {@code app/jcl/DISCGRP.jcl} declare zero alternate indexes,
 * while {@code app/jcl/XREFFILE.jcl} declares two. The group identifier occupies offsets 113 to
 * 122 of {@code app/data/ASCII/acctdata.txt}.</p>
 *
 * <p><b>Absence six, the three dropped trailing fillers.</b> The three record tables carry an
 * exact column count, which is what catches an accidental extra column, and no column on the three
 * carries a filler-like name.</p>
 *
 * <p>| Table | Mapped columns | Dropped filler | Locator |<br>
 * |---|---|---|---|<br>
 * | {@code account} | 12 | 178 bytes | {@code app/cpy/CVACT01Y.cpy:L17} |<br>
 * | {@code customer} | 18 | 168 bytes | {@code app/cpy/CVCUS01Y.cpy:L23} |<br>
 * | {@code disclosure_group} | 4 | 28 bytes | {@code app/cpy/CVTRA02Y.cpy:L10} |</p>
 *
 * <p>The arithmetic closes against each declared record width. The account record is 122 mapped
 * bytes plus 178 filler bytes, and {@code RECORDSIZE(300 300)} at {@code app/jcl/ACCTFILE.jcl:L41}
 * declares 300. The customer record is 332 plus 168, and {@code RECORDSIZE(500 500)} at
 * {@code app/jcl/CUSTFILE.jcl:L51} declares 500. The disclosure-group record is 22 plus 28, and
 * {@code RECORDSIZE(50 50)} at {@code app/jcl/DISCGRP.jcl:L41} declares 50.</p>
 */
@DisplayName("Six constraints the migrated account schema does not declare")
class SchemaConstraintAbsenceTest extends AbstractAccountPostgresTest {

    /**
     * The nine tables the four migrations create. The Flyway history table is not one of them,
     * and it carries a column named {@code version}. A scan for a row version therefore reads this
     * list and never the whole schema.
     */
    private static final List<String> MIGRATED_TABLES = List.of(
            "account",
            "customer",
            "disclosure_group",
            "us_phone_area_code",
            "us_state_code",
            "us_state_zip_prefix",
            "outbox_event",
            "processed_event",
            "account_customer_link");

    /** The three tables whose source record ends in a trailing filler field. */
    private static final List<String> RECORD_TABLES =
            List.of("account", "customer", "disclosure_group");

    /** Names a column takes when it holds a row version. */
    private static final List<String> ROW_VERSION_NAMES = List.of(
            "version",
            "rowversion",
            "row_version",
            "record_version",
            "entity_version",
            "optlock",
            "opt_lock",
            "optimistic_lock",
            "lock_version");

    /** The two lock modes a version column serves. */
    private static final Set<LockModeType> OPTIMISTIC_LOCK_MODES =
            EnumSet.of(LockModeType.OPTIMISTIC, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

    /** The six JPA entity classes of this module. */
    private static final List<Class<?>> ENTITY_CLASSES = List.of(
            AccountEntity.class,
            AccountCustomerLinkEntity.class,
            CustomerEntity.class,
            DisclosureGroupEntity.class,
            OutboxEventEntity.class,
            ProcessedEventEntity.class,
            AccountCustomerLinkEntity.class);

    /** The two repository interfaces the account update path reads and writes through. */
    private static final List<Class<?>> REPOSITORY_INTERFACES =
            List.of(AccountRepository.class, CustomerRepository.class);

    /**
     * A not-null declaration reaches {@code information_schema.check_constraints} as a clause
     * ending in {@code IS NOT NULL}. The pattern below matches such a clause, and the catalogue
     * read leaves every clause it matches out.
     */
    private static final String NOT_NULL_CLAUSE_PATTERN = "% IS NOT NULL";

    /** The datasource the harness points at the one container of this module. */
    @Autowired
    private DataSource dataSource;

    /** The schema Flyway migrated, read from configuration and never written here. */
    @Value("${spring.flyway.default-schema}")
    private String schema;

    /**
     * Absence one. {@code customer.fico_credit_score} carries no check constraint, and 21 seeded
     * rows sit outside the 300 through 850 range that {@code 1275-EDIT-FICO-SCORE.} at
     * {@code app/cbl/COACTUPC.cbl:L2514} applies to input.
     *
     * @throws SQLException when a catalogue or seed read fails
     */
    @Test
    @DisplayName("No check constraint restricts customer.fico_credit_score, and 21 seeded rows "
            + "sit outside 300 through 850")
    void creditScoreRangeReachesNoCheckConstraint() throws SQLException {
        assertThat(checkConstraintsMentioning("customer", "fico_credit_score"))
                .as("check constraints on customer whose clause names fico_credit_score")
                .isEmpty();

        assertThat(countRows("""
                SELECT count(*)
                  FROM customer
                 WHERE fico_credit_score < 300
                    OR fico_credit_score > 850"""))
                .as("seeded customer rows carrying a credit score outside 300 through 850")
                .isEqualTo(21L);
    }

    /**
     * Absence two. Neither a check constraint nor a foreign key relates
     * {@code customer.address_state_code} to {@code us_state_code}, and 5 seeded rows carry a code
     * the band at {@code app/cpy/CSLKPCDY.cpy:L1013} omits.
     *
     * @throws SQLException when a catalogue or seed read fails
     */
    @Test
    @DisplayName("No check and no foreign key relate customer.address_state_code to "
            + "us_state_code, and 5 seeded rows carry a code the band omits")
    void stateCodeMembershipReachesNoConstraint() throws SQLException {
        assertThat(checkConstraintsMentioning("customer", "address_state_code"))
                .as("check constraints on customer whose clause names address_state_code")
                .isEmpty();

        assertThat(foreignKeysCovering("customer", "address_state_code"))
                .as("foreign keys on customer covering address_state_code")
                .isEmpty();

        assertThat(countRows("""
                SELECT count(*)
                  FROM customer held
                 WHERE NOT EXISTS (
                       SELECT 1
                         FROM us_state_code band
                        WHERE band.state_code = held.address_state_code)"""))
                .as("seeded customer rows carrying a state code us_state_code omits")
                .isEqualTo(5L);
    }

    /**
     * Absence three. Neither a check constraint nor a foreign key relates the pair of
     * {@code customer.address_state_code} and the first two characters of
     * {@code customer.address_zip} to {@code us_state_zip_prefix}. 48 seeded rows fail that pair,
     * and {@code AP96} and {@code ME49} are the only keys the band at
     * {@code app/cpy/CSLKPCDY.cpy:L1073} holds.
     *
     * @throws SQLException when a catalogue or seed read fails
     */
    @Test
    @DisplayName("No check and no foreign key relate the state and zip pair to "
            + "us_state_zip_prefix, and 48 seeded rows fail that pair")
    void stateAndZipCombinationReachesNoConstraint() throws SQLException {
        assertThat(checkConstraintsMentioning("customer", "address_zip"))
                .as("check constraints on customer whose clause names address_zip")
                .isEmpty();

        assertThat(foreignKeysCovering("customer", "address_zip"))
                .as("foreign keys on customer covering address_zip")
                .isEmpty();

        assertThat(countRows("""
                SELECT count(*)
                  FROM customer held
                 WHERE NOT EXISTS (
                       SELECT 1
                         FROM us_state_zip_prefix band
                        WHERE band.state_zip_prefix
                              = held.address_state_code || substring(held.address_zip, 1, 2))"""))
                .as("seeded customer rows whose state and zip pair us_state_zip_prefix omits")
                .isEqualTo(48L);

        assertThat(textRows("""
                SELECT DISTINCT held.address_state_code || substring(held.address_zip, 1, 2)
                  FROM customer held
                  JOIN us_state_zip_prefix band
                    ON band.state_zip_prefix
                       = held.address_state_code || substring(held.address_zip, 1, 2)
                 ORDER BY 1"""))
                .as("seeded state and zip keys us_state_zip_prefix holds")
                .containsExactly("AP96", "ME49");
    }

    /**
     * Absence four. No migrated table carries a row-version column, no entity class declares a
     * version attribute, and no repository method requests an optimistic lock mode. The source
     * mechanism is {@code 9700-CHECK-CHANGE-IN-REC.} at {@code app/cbl/COACTUPC.cbl:L4109}, whose
     * account block opens at {@code L4114}.
     *
     * @throws SQLException when a catalogue read fails
     */
    @Test
    @DisplayName("No migrated table carries a row-version column, no entity declares a version "
            + "attribute, and no repository requests an optimistic lock")
    void rowVersionReachesNeitherSchemaNorMapping() throws SQLException {
        List<String> versionColumns = new ArrayList<>();
        for (String table : MIGRATED_TABLES) {
            List<String> columns = columnNamesOf(table);
            assertThat(columns).as("columns read from %s", table).isNotEmpty();
            columns.stream()
                    .filter(SchemaConstraintAbsenceTest::namesARowVersion)
                    .forEach(column -> versionColumns.add(table + "." + column));
        }
        assertThat(versionColumns)
                .as("row-version columns across the migrated tables of this schema")
                .isEmpty();

        List<String> versionAttributes = new ArrayList<>();
        for (Class<?> entityClass : ENTITY_CLASSES) {
            versionAttributes.addAll(versionAttributesOf(entityClass));
        }
        assertThat(versionAttributes)
                .as("version attributes across the six entity classes")
                .isEmpty();

        List<String> optimisticLockMethods = new ArrayList<>();
        for (Class<?> repositoryInterface : REPOSITORY_INTERFACES) {
            optimisticLockMethods.addAll(optimisticLockMethodsOf(repositoryInterface));
        }
        assertThat(optimisticLockMethods)
                .as("repository methods requesting an optimistic lock mode")
                .isEmpty();
    }

    /**
     * Absence five. No foreign key on {@code account} names {@code customer} as its parent, and no
     * foreign key covers {@code account.group_id}. {@code app/cpy/CVACT01Y.cpy:L5-L16} declares
     * twelve mapped fields and none of the twelve is a customer identifier.
     *
     * @throws SQLException when a catalogue read fails
     */
    @Test
    @DisplayName("No foreign key runs from account to customer, and none covers account.group_id")
    void accountDeclaresNeitherJoin() throws SQLException {
        assertThat(foreignKeysBetween("account", "customer"))
                .as("foreign keys on account naming customer as their parent")
                .isEmpty();

        assertThat(foreignKeysCovering("account", "group_id"))
                .as("foreign keys on account covering group_id")
                .isEmpty();
    }

    /**
     * Absence six. The three record tables carry twelve, eighteen and four columns, and no column
     * on any of the three carries a filler-like name. The dropped fields are
     * {@code FILLER PIC X(178)} at {@code app/cpy/CVACT01Y.cpy:L17},
     * {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy:L23} and
     * {@code FILLER PIC X(28)} at {@code app/cpy/CVTRA02Y.cpy:L10}.
     *
     * @throws SQLException when a catalogue read fails
     */
    @Test
    @DisplayName("account carries 12 columns, customer 18 and disclosure_group 4, and no column "
            + "on the three is a filler")
    void droppedTrailingFillersReachNoColumn() throws SQLException {
        assertThat(columnNamesOf("account")).as("columns of account").hasSize(12);
        assertThat(columnNamesOf("customer")).as("columns of customer").hasSize(18);
        assertThat(columnNamesOf("disclosure_group")).as("columns of disclosure_group").hasSize(4);

        List<String> fillerColumns = new ArrayList<>();
        for (String table : RECORD_TABLES) {
            columnNamesOf(table).stream()
                    .filter(column -> lower(column).contains("filler"))
                    .forEach(column -> fillerColumns.add(table + "." + column));
        }
        assertThat(fillerColumns)
                .as("filler-named columns across the three record tables")
                .isEmpty();
    }

    /**
     * Names the check constraints on one table of this schema whose clause mentions one column.
     * Clauses matching {@link #NOT_NULL_CLAUSE_PATTERN} are left out, since a not-null declaration
     * reaches {@code information_schema.check_constraints} in that form.
     *
     * @param table  the unqualified table name
     * @param column the column name to look for inside each clause
     * @return the constraint names, in name order, or an empty list when the catalogue holds none
     * @throws SQLException when the catalogue read fails
     */
    private List<String> checkConstraintsMentioning(String table, String column)
            throws SQLException {
        return textRows("""
                SELECT declared.constraint_name
                  FROM information_schema.table_constraints declared
                  JOIN information_schema.check_constraints clause
                    ON clause.constraint_schema = declared.constraint_schema
                   AND clause.constraint_name = declared.constraint_name
                 WHERE declared.constraint_type = 'CHECK'
                   AND declared.table_schema = ?
                   AND declared.table_name = ?
                   AND clause.check_clause LIKE ?
                   AND clause.check_clause NOT LIKE ?
                 ORDER BY declared.constraint_name""",
                schema, table, "%" + column + "%", NOT_NULL_CLAUSE_PATTERN);
    }

    /**
     * Names the foreign keys on one table of this schema that cover one column.
     *
     * @param table  the unqualified table name
     * @param column the column the key would cover
     * @return the constraint names, in name order, or an empty list when the catalogue holds none
     * @throws SQLException when the catalogue read fails
     */
    private List<String> foreignKeysCovering(String table, String column) throws SQLException {
        return textRows("""
                SELECT DISTINCT declared.constraint_name
                  FROM information_schema.table_constraints declared
                  JOIN information_schema.key_column_usage covered
                    ON covered.constraint_schema = declared.constraint_schema
                   AND covered.constraint_name = declared.constraint_name
                 WHERE declared.constraint_type = 'FOREIGN KEY'
                   AND declared.table_schema = ?
                   AND declared.table_name = ?
                   AND covered.column_name = ?
                 ORDER BY 1""",
                schema, table, column);
    }

    /**
     * Names the foreign keys that run from one table of this schema to another. The parent table
     * is read through {@code information_schema.referential_constraints}, which carries the unique
     * constraint each key points at.
     *
     * @param childTable  the table that would carry the key
     * @param parentTable the table the key would point at
     * @return the constraint names, in name order, or an empty list when the catalogue holds none
     * @throws SQLException when the catalogue read fails
     */
    private List<String> foreignKeysBetween(String childTable, String parentTable)
            throws SQLException {
        return textRows("""
                SELECT DISTINCT child.constraint_name
                  FROM information_schema.table_constraints child
                  JOIN information_schema.referential_constraints reference
                    ON reference.constraint_schema = child.constraint_schema
                   AND reference.constraint_name = child.constraint_name
                  JOIN information_schema.table_constraints parent
                    ON parent.constraint_schema = reference.unique_constraint_schema
                   AND parent.constraint_name = reference.unique_constraint_name
                 WHERE child.constraint_type = 'FOREIGN KEY'
                   AND child.table_schema = ?
                   AND child.table_name = ?
                   AND parent.table_name = ?
                 ORDER BY 1""",
                schema, childTable, parentTable);
    }

    /**
     * Lists the column names of one table of this schema, in declaration order.
     *
     * @param table the unqualified table name
     * @return the column names, or an empty list when the schema holds no such table
     * @throws SQLException when the catalogue read fails
     */
    private List<String> columnNamesOf(String table) throws SQLException {
        return textRows("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = ?
                   AND table_name = ?
                 ORDER BY ordinal_position""",
                schema, table);
    }

    /**
     * Runs one read and returns the single numeric value it produces.
     *
     * @param sql a query returning one row of one numeric column
     * @return that value
     * @throws SQLException when the read fails
     */
    private long countRows(String sql) throws SQLException {
        List<String> values = textRows(sql);
        assertThat(values).as("rows returned by a count query").hasSize(1);
        return Long.parseLong(values.getFirst());
    }

    /**
     * Runs one read against the migrated schema and returns the first column of every row as text.
     * The connection is pointed at the schema first, so an unqualified table name in the query
     * resolves inside it.
     *
     * @param sql        the query, carrying one placeholder per parameter
     * @param parameters the parameter values, bound in order as text
     * @return the first column of every row, in the order the query produced them
     * @throws SQLException when the read fails
     */
    private List<String> textRows(String sql, String... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setSchema(schema);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int position = 0; position < parameters.length; position++) {
                    statement.setString(position + 1, parameters[position]);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    List<String> values = new ArrayList<>();
                    while (rows.next()) {
                        values.add(rows.getString(1));
                    }
                    return values;
                }
            }
        }
    }

    /**
     * Reports whether a column name holds a row version.
     *
     * @param columnName the column name as the catalogue holds it
     * @return true when the name is one of the known row-version names or carries one of their
     *     word parts
     */
    private static boolean namesARowVersion(String columnName) {
        String name = lower(columnName);
        return ROW_VERSION_NAMES.contains(name)
                || name.endsWith("_version")
                || name.startsWith("version_")
                || name.contains("optimistic")
                || name.contains("_lock")
                || name.startsWith("lock_");
    }

    /**
     * Names the fields and methods of one class that carry {@link Version}.
     *
     * @param mappedClass the entity class to read
     * @return one label per version attribute, or an empty list when the class declares none
     */
    private static List<String> versionAttributesOf(Class<?> mappedClass) {
        List<String> found = new ArrayList<>();
        for (Field field : mappedClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Version.class)) {
                found.add(mappedClass.getSimpleName() + "." + field.getName());
            }
        }
        for (Method method : mappedClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Version.class)) {
                found.add(mappedClass.getSimpleName() + "." + method.getName() + "()");
            }
        }
        return found;
    }

    /**
     * Names the methods of one repository interface that request an optimistic lock mode.
     *
     * @param repositoryInterface the repository interface to read
     * @return one label per method, or an empty list when the interface requests no such mode
     */
    private static List<String> optimisticLockMethodsOf(Class<?> repositoryInterface) {
        List<String> found = new ArrayList<>();
        for (Method method : repositoryInterface.getDeclaredMethods()) {
            Lock requested = method.getAnnotation(Lock.class);
            if (requested != null && OPTIMISTIC_LOCK_MODES.contains(requested.value())) {
                found.add(repositoryInterface.getSimpleName() + "." + method.getName() + "()");
            }
        }
        return found;
    }

    /**
     * Folds one name to lower case under a fixed locale, so a name comparison reads the same on
     * every machine.
     *
     * @param name the name to fold
     * @return the folded name
     */
    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
