package com.carddemo.card.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.card.CardServiceDatabase;
import com.carddemo.card.TestIdentityPasswords;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Asserts that the three Jakarta Persistence (JPA) entity classes of the card service map field for
 * field onto the COBOL (Common Business Oriented Language) record layouts. Both trailing fillers
 * are dropped, and the migrated schema carries the derived column types.
 *
 * <p>Two copybooks supply the mapped fields. {@code app/cpy/CVACT02Y.cpy:L5-L10} declares the six
 * card fields, and {@code app/cpy/CVACT02Y.cpy:L11} declares a trailing {@code FILLER PIC X(59)}.
 * {@code app/cpy/CVACT03Y.cpy:L5-L7} declares the three cross-reference fields, and
 * {@code app/cpy/CVACT03Y.cpy:L8} declares a trailing {@code FILLER PIC X(14)}. Neither filler
 * carries a column. The tests below count the mapped attributes and scan every name, so a seventh
 * card column or a fourth copybook-derived cross-reference column fails the count.
 *
 * <p>Two Job Control Language (JCL) members supply the keys and the record widths of the two
 * Virtual Storage Access Method (VSAM) datasets. {@code app/jcl/CARDFILE.jcl:L54} declares
 * {@code KEYS(16 0)} and {@code app/jcl/CARDFILE.jcl:L55} declares {@code RECORDSIZE(150 150)}.
 * {@code app/jcl/CARDFILE.jcl:L83-L88} defines an alternate index carrying {@code KEYS(11 16)} at
 * L85 and {@code NONUNIQUEKEY} at L86: an eleven-byte key at offset 16, where
 * {@code CARD-ACCT-ID} starts. {@code app/jcl/XREFFILE.jcl:L43} declares {@code KEYS(16 0)},
 * {@code app/jcl/XREFFILE.jcl:L44} declares {@code RECORDSIZE(50 50)}, and
 * {@code app/jcl/XREFFILE.jcl:L74} declares {@code KEYS(11,25)}, where offset 25 is 16 plus 9.
 *
 * <p>{@code app/cpy/CVACT03Y.cpy:L7} is the width authority for the account identifier across this
 * platform. {@code XREF-ACCT-ID PIC 9(11)} fixes the eleven digits that
 * {@code outbox_event.aggregate_id} and the aggregate identifier of the shared event envelope both
 * carry. The eleven-character Kafka message key belongs to the tests under
 * {@code com.carddemo.card.messaging}, and no test here repeats it.
 *
 * <p>Column {@code expiration_date} carries a {@code DATE} type, and
 * {@link PicClause#CARD_EXPIRATION_DATE_COLUMN_TYPE} names it. Three readings of
 * {@code app/cbl/COCRDUPC.cbl} establish the card expiry as a separator-delimited calendar date.
 * L115 to L123 redefine the ten characters as a four-character year, a one-character separator, a
 * two-character month, a second separator and a two-character day. L1361 to L1366 slice the field
 * at {@code (1:4)}, {@code (6:2)} and {@code (9:2)}, skipping positions 5 and 8. L1467 to L1474
 * assemble the value with {@code STRING} around two literal hyphens.
 *
 * <p>The fixture agrees. All 50 records of {@code app/data/ASCII/carddata.txt} hold
 * {@code YYYY-MM-DD} at columns 81 through 90, with no malformed value. The account service holds
 * {@code ACCT-EXPIRAION-DATE PIC X(10)} as
 * {@link PicClause#ACCT_EXPIRATION_DATE_COLUMN_TYPE}, and
 * {@code app/cbl/CBTRN02C.cbl:L414} compares that field with the first ten characters of a
 * 26-character origin timestamp. No test here reads an account-service class.
 *
 * <p>The two fixtures disagree on the cross-reference record width.
 * {@code app/data/ASCII/carddata.txt} holds 50 records of exactly 150 characters, matching
 * {@code RECORDSIZE(150 150)}. {@code app/data/ASCII/cardxref.txt} holds 50 records of exactly 36
 * characters against the 50 that {@code RECORDSIZE(50 50)} declares, and the 14-byte filler
 * reaches neither the fixture nor the table, so a fixture parser has to tolerate both widths.
 *
 * <p>No test here opens a file under {@code app/}. Flyway loads the fixture values from
 * {@code src/main/resources/db/migration/V2__seed.sql}, which inserts 50 card rows and 50
 * cross-reference rows.
 *
 * <p>The persistence cases below insert generated synthetic identifiers and card data. The tests
 * compare field shapes and values without committing fixture card numbers or verification values.
 *
 * <p>Column 91 holds {@code Y} on all 50 records, so an inactive card is constructed here and
 * never loaded. The fixture field positions are card number 1 to 16, account identifier 17 to 27
 * and card verification value 28 to 30. The remaining positions are embossed name 31 to 80,
 * expiration date 81 to 90, active status 91 and filler 92 to 150. The filler measures 59
 * characters.
 *
 * <p>Three subjects belong to sibling test classes and appear in no assertion here.
 * {@code CardholderDataExposureTest} owns the card verification value: its absent accessor, its
 * {@code JsonIgnore} annotation, and every rendering that withholds it. The tests under
 * {@code com.carddemo.card.domain} own the presented form of the embossed name, which
 * {@code app/cbl/COCRDUPC.cbl:L1356-L1358} folds to upper case with
 * {@code INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER}. The tests under
 * {@code com.carddemo.card.messaging} own the message key and the non-emission of cardholder data.
 * This class asserts the persisted form of the embossed name, which
 * {@code app/cbl/COCRDUPC.cbl:L1466} writes with no fold.
 *
 * <ul>
 *   <li>The {@code DATE} column type here, where the account service holds ten characters of
 *       text.</li>
 *   <li>The corrected spelling of {@code CARD-EXPIRAION-DATE} at
 *       {@code app/cpy/CVACT02Y.cpy:L9}.</li>
 *   <li>The dropped 59-byte and 14-byte trailing fillers.</li>
 *   <li>The split between the persisted and the presented casing of the embossed name.</li>
 *   <li>The third working-storage redefine pair at {@code app/cpy/CVCRD01Y.cpy:L40-L42}.</li>
 * </ul>
 *
 * <p>{@code app/cpy/CVCRD01Y.cpy} declares three redefine pairs whose primary picture clause is
 * alphanumeric. The pairs are {@code CC-ACCT-ID PIC X(11)} with {@code CC-ACCT-ID-N PIC 9(11)} at
 * L34 to L36, {@code CC-CARD-NUM PIC X(16)} with {@code CC-CARD-NUM-N PIC 9(16)} at L37 to L39,
 * and {@code CC-CUST-ID PIC X(09)} with {@code CC-CUST-ID-N PIC 9(9)} at L40 to L42. Every
 * identifier column asserted below holds text and keeps its leading zeros. A numeric column
 * returns {@code 50} for a stored {@code 00000000050}.
 *
 * <p>The same copybook declares three screen-navigation fields: {@code CCARD-NEXT-PROG PIC X(8)} at
 * L21, {@code CCARD-NEXT-MAPSET PIC X(7)} at L23 and {@code CCARD-NEXT-MAP PIC X(7)} at L24. A test
 * below scans for a column matching any of them.
 *
 * <p>No entity declares a {@link Version} field. The concurrency mechanism is a field-level
 * compare-and-swap, and
 * {@code app/cbl/COCRDUPC.cbl:L1455-L1457} reads
 * {@code IF DATA-WAS-CHANGED-BEFORE-UPDATE GO TO 9200-WRITE-PROCESSING-EXIT}, which follows the
 * field comparison at {@code app/cbl/COCRDUPC.cbl:L1503-L1508}. The comparison itself belongs to
 * the {@code com.carddemo.card.domain} tests, and this class asserts the absence alone.
 *
 * <p>No test adds a card-number checksum. {@code app/cbl/COCRDUPC.cbl:L194} carries the one
 * card-number rule in the source, and its text names sixteen digits. The absence of a checksum
 * rule is therefore a deliberate non-addition.
 *
 * <p><b>How this class runs.</b> One PostgreSQL 18.4 container serves the whole class, and the
 * image tag matches {@code card-platform/docker-compose.yml}.
 * {@code src/main/resources/application.yml} sets {@code spring.flyway.create-schemas: true}, so
 * Flyway creates the schema, applies {@code V1__schema.sql} and loads {@code V2__seed.sql}. The
 * same file sets {@code spring.jpa.hibernate.ddl-auto: validate}, so Hibernate compares all four
 * entity mappings with the migrated schema at start-up. A column name, type, precision, scale or
 * nullability that drifts from the migration stops the context, and every test in this class
 * carries that check by starting.
 *
 * <p>{@link DynamicPropertySource} points three datasource properties at the container, and the
 * annotation {@code ServiceConnection} appears nowhere here: the artifact that declares it,
 * {@code org.springframework.boot:spring-boot-testcontainers}, is absent from
 * {@code card-platform/services/card-service/pom.xml}. The class annotation supplies one inert
 * value for each of the four variables that {@code application.yml} leaves without a default. The
 * broker password reaches no broker, and the identity hashes in {@code TestIdentityPasswords}
 * encode passwords that authenticate nothing outside this build.
 *
 * <p>The two interval properties stand the relay and the retention sweep down to an hour, so
 * neither repeats inside the lifetime of this class. This is a full application context, so both
 * scheduled components are live and the relay's shipped delay is 500 milliseconds; a sweep still
 * repeating after the context is evicted reaches a closed pool and logs a failure shaped exactly
 * like a real one. {@code config/ScheduledWorkStandDownTest} holds every class here to that rule.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH
        })
@DisplayName("Card entities map onto CVACT02Y.cpy and CVACT03Y.cpy, filler dropped")
class CardEntityMappingTest {

    /** Generated card number used by persistence cases. */
    private static final String SYNTHETIC_CARD_NUMBER = syntheticCardNumber(700_001L);

    /** Generated account identifier, eleven characters wide. */
    private static final String SYNTHETIC_ACCOUNT_ID = syntheticDigits(11, 700_001L);

    /** Generated customer identifier, nine characters wide. */
    private static final String SYNTHETIC_CUSTOMER_ID = syntheticDigits(9, 700_001L);

    /** Synthetic embossed name, trimmed of the padding {@code PIC X(50)} carries. */
    private static final String SYNTHETIC_EMBOSSED_NAME = "Mapping Test Card";

    private static final LocalDate SYNTHETIC_EXPIRATION_DATE = LocalDate.of(2027, 3, 9);

    private static final String SYNTHETIC_ACTIVE_STATUS = "Y";

    /** Generated card verification value, held as text to preserve its width. */
    private static final String SYNTHETIC_CARD_VERIFICATION_VALUE = syntheticDigits(3, 731L);

    /** Row count {@code V2__seed.sql} loads into each of the two seeded tables. */
    private static final int SEEDED_ROW_COUNT = 50;

    /**
     * The four entity classes of this service, in the order {@code V1__schema.sql} creates their
     * tables. Every scan below walks this list, so another entity added to the package reaches the
     * scans with no change here.
     */
    private static final List<Class<?>> ENTITY_CLASSES = List.of(
            CardEntity.class,
            CardCrossReferenceEntity.class,
            OutboxEventEntity.class,
            ProcessedEventEntity.class);

    /** The table each entity class maps onto, keyed by the class. */
    private static final Map<Class<?>, String> TABLE_NAMES = Map.of(
            CardEntity.class, "card",
            CardCrossReferenceEntity.class, "card_xref",
            OutboxEventEntity.class, "outbox_event",
            ProcessedEventEntity.class, "processed_event");

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link CardServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    private static final PostgreSQLContainer POSTGRES = CardServiceDatabase.container();

    /**
     * Points the Spring datasource at the running container.
     *
     * <p>Three properties leave here. {@code application.yml} sits on the test classpath and
     * carries every other datasource, Flyway and persistence setting, and no line below repeats
     * one.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> CardServiceDatabase.urlFor(CardEntityMappingTest.class));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Reads column and index metadata straight from the migrated schema. */
    @Autowired
    private DataSource dataSource;

    /** Reads and writes rows through the same mappings the running service uses. */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The schema Flyway created and Hibernate qualifies with, from
     * {@code spring.jpa.properties.hibernate.default_schema} in {@code application.yml}.
     */
    @Value("${spring.jpa.properties.hibernate.default_schema}")
    private String schema;

    // ---------------------------------------------------------------------------------------
    // Helpers. Each one answers a single question about a mapping, and none asserts.
    // ---------------------------------------------------------------------------------------

    /** One column of the migrated schema, as Java Database Connectivity (JDBC) reports it. */
    private record ColumnFact(String typeName, int size, int decimalDigits, boolean nullable) {
    }

    /** One index of the migrated schema, with its key columns in key order. */
    private record IndexFact(List<String> columns, boolean unique) {
    }

    /**
     * Returns the persistent fields an entity class declares, in declaration order, with an
     * {@link EmbeddedId} replaced by the fields of the embeddable it holds.
     *
     * <p>A static field carries no column and a synthetic field is one the compiler adds, so
     * neither is a mapped attribute.
     *
     * <p>An {@code @EmbeddedId} field carries no {@link Column} of its own: the columns belong to
     * the embeddable, one per key part. Flattening it here keeps every scan below reading columns
     * rather than attributes, which is what each of them asserts about. {@code ProcessedEventEntity}
     * is the one entity of this service that holds a composite key, since
     * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} made the consumed
     * topic half of it.
     *
     * @param entity the entity class to read
     * @return the mapped fields, in the order the class declares them, embedded key parts inlined
     */
    private static List<Field> mappedFields(Class<?> entity) {
        List<Field> fields = new ArrayList<>();
        for (Field field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            if (field.isAnnotationPresent(EmbeddedId.class)) {
                fields.addAll(mappedFields(field.getType()));
                continue;
            }
            fields.add(field);
        }
        return fields;
    }

    /**
     * Returns the columns that carry an entity's identity, in key order.
     *
     * <p>A single {@link Id} field answers one column. An {@link EmbeddedId} answers one column per
     * field of the embeddable it holds, in the order that class declares them, which is the order the
     * migration declares the key in.
     *
     * @param entity the entity class to read
     * @return the identifier columns, and an empty list when the class declares no identity
     */
    private static List<String> identifierColumns(Class<?> entity) {
        List<String> columns = new ArrayList<>();
        for (Field field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            if (field.isAnnotationPresent(EmbeddedId.class)) {
                for (Field part : mappedFields(field.getType())) {
                    columns.add(columnName(part));
                }
            } else if (field.isAnnotationPresent(Id.class)) {
                columns.add(columnName(field));
            }
        }
        return columns;
    }

    /**
     * Returns the column name an entity field maps onto.
     *
     * @param field the mapped field to read
     * @return the value of {@link Column#name()}
     * @throws AssertionError if the field carries no {@link Column} annotation, or if that
     *                        annotation names no column
     */
    private static String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column, field.getDeclaringClass().getSimpleName() + "." + field.getName()
                + " carries no Column annotation");
        assertFalse(column.name().isBlank(), field.getDeclaringClass().getSimpleName() + "."
                + field.getName() + " names no column");
        return column.name();
    }

    /**
     * Returns the column names an entity class maps onto, in declaration order.
     *
     * @param entity the entity class to read
     * @return the column names, in the order the class declares its fields
     */
    private static List<String> columnNames(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Field field : mappedFields(entity)) {
            names.add(columnName(field));
        }
        return names;
    }

    /**
     * Returns the field names an entity class maps, in declaration order.
     *
     * @param entity the entity class to read
     * @return the mapped field names
     */
    private static List<String> fieldNames(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Field field : mappedFields(entity)) {
            names.add(field.getName());
        }
        return names;
    }

    /**
     * Returns a name in one comparable form: lower case with every underscore removed.
     *
     * <p>{@code card_number} and {@code cardNumber} both reduce to {@code cardnumber}, so one scan
     * covers a column name and the field name beside it.
     *
     * @param name the column name or field name to fold
     * @return the folded form
     */
    private static String folded(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }

    /**
     * Returns the public methods an entity class declares, excluding the three
     * {@link Object} overrides every entity here carries.
     *
     * @param entity the entity class to read
     * @return the declared public method names, without {@code equals}, {@code hashCode} and
     *         {@code toString}
     */
    private static List<String> declaredPublicMethodNames(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Method method : entity.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            String name = method.getName();
            if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
                continue;
            }
            names.add(name);
        }
        return names;
    }

    /**
     * Returns the declared public methods of an entity class whose name opens with {@code set}.
     *
     * <p>A mutator carrying another name is named in the assertion that reads this list.
     *
     * @param entity the entity class to read
     * @return the setter names the class declares
     */
    private static List<String> declaredSetterNames(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (String name : declaredPublicMethodNames(entity)) {
            if (name.startsWith("set")) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Reads every column of one table of the migrated schema.
     *
     * @param connection an open connection to the container
     * @param schemaName the schema Flyway created
     * @param table      the table to read
     * @return one entry per column, keyed by column name, in schema order
     * @throws SQLException if the metadata read fails
     */
    private static Map<String, ColumnFact> columnFacts(Connection connection, String schemaName,
            String table) throws SQLException {
        Map<String, ColumnFact> facts = new LinkedHashMap<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(null, schemaName, table, null)) {
            while (columns.next()) {
                facts.put(columns.getString("COLUMN_NAME"),
                        new ColumnFact(columns.getString("TYPE_NAME"),
                                columns.getInt("COLUMN_SIZE"),
                                columns.getInt("DECIMAL_DIGITS"),
                                "YES".equals(columns.getString("IS_NULLABLE"))));
            }
        }
        return facts;
    }

    /**
     * Reads every index of one table of the migrated schema, key columns in key order.
     *
     * @param connection an open connection to the container
     * @param schemaName the schema Flyway created
     * @param table      the table to read
     * @return one entry per index, keyed by index name
     * @throws SQLException if the metadata read fails
     */
    private static Map<String, IndexFact> indexFacts(Connection connection, String schemaName,
            String table) throws SQLException {
        Map<String, Map<Short, String>> columnsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet indexes = metaData.getIndexInfo(null, schemaName, table, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name == null) {
                    continue;
                }
                columnsByIndex.computeIfAbsent(name, key -> new TreeMap<>())
                        .put(indexes.getShort("ORDINAL_POSITION"),
                                indexes.getString("COLUMN_NAME"));
                uniqueByIndex.put(name, !indexes.getBoolean("NON_UNIQUE"));
            }
        }
        Map<String, IndexFact> facts = new LinkedHashMap<>();
        columnsByIndex.forEach((name, positions) -> facts.put(name,
                new IndexFact(List.copyOf(positions.values()), uniqueByIndex.get(name))));
        return facts;
    }

    /**
     * Returns the index names an entity class declares on its {@link Table} annotation, with the
     * key columns of each one in the order the annotation lists them.
     *
     * <p>Flyway owns every Data Definition Language (DDL) statement, and Hibernate compares column
     * mappings alone under {@code ddl-auto: validate}. The annotation states which indexes the
     * migration creates, and the tests below compare the two lists.
     *
     * @param entity the entity class to read
     * @return one entry per declared index, keyed by index name
     */
    private static Map<String, List<String>> declaredIndexes(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        assertNotNull(table, entity.getSimpleName() + " carries no Table annotation");
        Map<String, List<String>> declared = new LinkedHashMap<>();
        for (Index index : table.indexes()) {
            List<String> columns = new ArrayList<>();
            for (String column : index.columnList().split(",")) {
                columns.add(column.trim());
            }
            declared.put(index.name(), List.copyOf(columns));
        }
        return declared;
    }

    /**
     * Returns the table name of this class's assertion target, qualified with the migrated schema.
     *
     * <p>Hibernate qualifies a mapped query with {@code hibernate.default_schema}, and a native
     * query carries the qualification the caller writes. The schema name is checked against the
     * form an unquoted PostgreSQL identifier takes before it reaches a statement.
     *
     * @param table the unqualified table name
     * @return the qualified name a native query takes
     */
    private String qualified(String table) {
        assertNotNull(schema, "spring.jpa.properties.hibernate.default_schema resolved to null");
        assertTrue(schema.matches("^[a-z_][a-z0-9_]{0,62}$"),
                "schema name is not a plain lower-case identifier: " + schema);
        return schema + "." + table;
    }

    /**
     * Returns the row count of one table of the migrated schema.
     *
     * @param table the unqualified table name
     * @return the number of rows the table holds
     */
    private long rowCount(String table) {
        Object count = entityManager
                .createNativeQuery("SELECT count(*) FROM " + qualified(table))
                .getSingleResult();
        return ((Number) count).longValue();
    }

    /** Builds a clearly synthetic sixteen-digit card value. */
    private static String syntheticCardNumber(long serial) {
        return "9999" + syntheticDigits(12, serial);
    }

    /** Builds a zero-padded synthetic digit string. */
    private static String syntheticDigits(int width, long serial) {
        return String.format(Locale.ROOT, "%0" + width + "d", serial);
    }

    /** Compares sensitive text without adding either value to a failed assertion. */
    private static void assertSameSensitiveValue(
            String expected, String actual, String message) {
        assertTrue(expected.equals(actual), message);
    }

    /**
     * Reads the record widths out of {@link PicClause} and adds them up.
     *
     * <p>Every width below reaches the entity mapping, the migration and the fixture parser from
     * one constant, so a width correction moves all three at once.
     */
    @Nested
    @DisplayName("Record widths from CVACT02Y.cpy, CVACT03Y.cpy and the two JCL members")
    class RecordWidths {

        @Test
        @DisplayName("the seven card field widths sum to 150, matching CARDFILE.jcl:L55")
        void cardFieldWidthsSumToTheDeclaredRecordLength() {
            assertAll(
                    () -> assertEquals(16, PicClause.CARD_NUM_WIDTH,
                            "CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5"),
                    () -> assertEquals(11, PicClause.CARD_ACCT_ID_WIDTH,
                            "CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6"),
                    () -> assertEquals(3, PicClause.CARD_CVV_CD_WIDTH,
                            "CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7"),
                    () -> assertEquals(50, PicClause.CARD_EMBOSSED_NAME_WIDTH,
                            "CARD-EMBOSSED-NAME PIC X(50) at app/cpy/CVACT02Y.cpy:L8"),
                    () -> assertEquals(10, PicClause.CARD_EXPIRATION_DATE_WIDTH,
                            "CARD-EXPIRAION-DATE PIC X(10) at app/cpy/CVACT02Y.cpy:L9"),
                    () -> assertEquals(1, PicClause.CARD_ACTIVE_STATUS_WIDTH,
                            "CARD-ACTIVE-STATUS PIC X(01) at app/cpy/CVACT02Y.cpy:L10"),
                    () -> assertEquals(59, PicClause.CARD_RECORD_FILLER_WIDTH,
                            "FILLER PIC X(59) at app/cpy/CVACT02Y.cpy:L11"),
                    () -> assertEquals(150, PicClause.CARD_RECORD_LENGTH,
                            "RECORDSIZE(150 150) at app/jcl/CARDFILE.jcl:L55"),
                    () -> assertEquals(PicClause.CARD_RECORD_LENGTH,
                            PicClause.CARD_NUM_WIDTH
                                    + PicClause.CARD_ACCT_ID_WIDTH
                                    + PicClause.CARD_CVV_CD_WIDTH
                                    + PicClause.CARD_EMBOSSED_NAME_WIDTH
                                    + PicClause.CARD_EXPIRATION_DATE_WIDTH
                                    + PicClause.CARD_ACTIVE_STATUS_WIDTH
                                    + PicClause.CARD_RECORD_FILLER_WIDTH,
                            "16 plus 11 plus 3 plus 50 plus 10 plus 1 plus 59"));
        }

        @Test
        @DisplayName("the four cross-reference field widths sum to 50, matching XREFFILE.jcl:L44")
        void crossReferenceFieldWidthsSumToTheDeclaredRecordLength() {
            assertAll(
                    () -> assertEquals(16, PicClause.XREF_CARD_NUM_WIDTH,
                            "XREF-CARD-NUM PIC X(16) at app/cpy/CVACT03Y.cpy:L5"),
                    () -> assertEquals(9, PicClause.XREF_CUST_ID_WIDTH,
                            "XREF-CUST-ID PIC 9(09) at app/cpy/CVACT03Y.cpy:L6"),
                    () -> assertEquals(11, PicClause.XREF_ACCT_ID_WIDTH,
                            "XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7"),
                    () -> assertEquals(14, PicClause.CARD_XREF_RECORD_FILLER_WIDTH,
                            "FILLER PIC X(14) at app/cpy/CVACT03Y.cpy:L8"),
                    () -> assertEquals(50, PicClause.CARD_XREF_RECORD_LENGTH,
                            "RECORDSIZE(50 50) at app/jcl/XREFFILE.jcl:L44"),
                    () -> assertEquals(PicClause.CARD_XREF_RECORD_LENGTH,
                            PicClause.XREF_CARD_NUM_WIDTH
                                    + PicClause.XREF_CUST_ID_WIDTH
                                    + PicClause.XREF_ACCT_ID_WIDTH
                                    + PicClause.CARD_XREF_RECORD_FILLER_WIDTH,
                            "16 plus 9 plus 11 plus 14"));
        }

        @Test
        @DisplayName("the alternate-index key widths match CARDFILE.jcl:L85 and XREFFILE.jcl:L74")
        void alternateIndexKeyWidthsMatchTheAccountIdentifierWidths() {
            assertAll(
                    () -> assertEquals(11, PicClause.CARD_ACCT_ID_WIDTH,
                            "KEYS(11 16) at app/jcl/CARDFILE.jcl:L85"),
                    () -> assertEquals(11, PicClause.XREF_ACCT_ID_WIDTH,
                            "KEYS(11,25) at app/jcl/XREFFILE.jcl:L74"),
                    () -> assertEquals(16, PicClause.CARD_NUM_WIDTH,
                            "offset 16 of KEYS(11 16) at app/jcl/CARDFILE.jcl:L85"),
                    () -> assertEquals(25,
                            PicClause.XREF_CARD_NUM_WIDTH + PicClause.XREF_CUST_ID_WIDTH,
                            "offset 25 of KEYS(11,25) at app/jcl/XREFFILE.jcl:L74 is 16 plus 9"));
        }

        @Test
        @DisplayName("the expiry slices at COCRDUPC.cbl:L117-L121 sum to the ten-character field")
        void expiryDateSlicesSumToTheFieldWidth() {
            assertAll(
                    () -> assertEquals(4, PicClause.CARD_EXPIRATION_DATE_YEAR_WIDTH,
                            "CARD-EXPIRY-YEAR PIC X(4) at app/cbl/COCRDUPC.cbl:L117"),
                    () -> assertEquals(2, PicClause.CARD_EXPIRATION_DATE_MONTH_WIDTH,
                            "CARD-EXPIRY-MONTH PIC X(2) at app/cbl/COCRDUPC.cbl:L119"),
                    () -> assertEquals(2, PicClause.CARD_EXPIRATION_DATE_DAY_WIDTH,
                            "CARD-EXPIRY-DAY PIC X(2) at app/cbl/COCRDUPC.cbl:L121"),
                    () -> assertEquals(1, PicClause.CARD_EXPIRATION_DATE_SEPARATOR_WIDTH,
                            "FILLER PIC X(1) at app/cbl/COCRDUPC.cbl:L118 and L120"),
                    () -> assertEquals(PicClause.CARD_EXPIRATION_DATE_WIDTH,
                            PicClause.CARD_EXPIRATION_DATE_YEAR_WIDTH
                                    + PicClause.CARD_EXPIRATION_DATE_SEPARATOR_WIDTH
                                    + PicClause.CARD_EXPIRATION_DATE_MONTH_WIDTH
                                    + PicClause.CARD_EXPIRATION_DATE_SEPARATOR_WIDTH
                                    + PicClause.CARD_EXPIRATION_DATE_DAY_WIDTH,
                            "4 plus 1 plus 2 plus 1 plus 2"),
                    () -> assertEquals(0, PicClause.CARD_EXPIRATION_DATE_YEAR_OFFSET,
                            "slice (1:4) at app/cbl/COCRDUPC.cbl:L1361"),
                    () -> assertEquals(5, PicClause.CARD_EXPIRATION_DATE_MONTH_OFFSET,
                            "slice (6:2) at app/cbl/COCRDUPC.cbl:L1363"),
                    () -> assertEquals(8, PicClause.CARD_EXPIRATION_DATE_DAY_OFFSET,
                            "slice (9:2) at app/cbl/COCRDUPC.cbl:L1365"));
        }

        @Test
        @DisplayName("the two fixtures carry 50 records each, at 150 and at 36 characters")
        void fixtureRecordCountsAndWidthsMatchTheMeasuredFiles() {
            assertAll(
                    () -> assertEquals(SEEDED_ROW_COUNT, PicClause.CARDDATA_FIXTURE_RECORD_COUNT,
                            "record count of app/data/ASCII/carddata.txt"),
                    () -> assertEquals(SEEDED_ROW_COUNT, PicClause.CARDXREF_FIXTURE_RECORD_COUNT,
                            "record count of app/data/ASCII/cardxref.txt"),
                    () -> assertEquals(PicClause.CARD_RECORD_LENGTH,
                            PicClause.CARDDATA_FIXTURE_RECORD_WIDTH,
                            "carddata.txt holds the full 150-character record"),
                    () -> assertEquals(36, PicClause.CARDXREF_FIXTURE_RECORD_WIDTH,
                            "cardxref.txt holds 36 characters against the declared 50"),
                    () -> assertEquals(PicClause.CARD_XREF_RECORD_LENGTH
                                    - PicClause.CARD_XREF_RECORD_FILLER_WIDTH,
                            PicClause.CARDXREF_FIXTURE_RECORD_WIDTH,
                            "the 14-byte filler reaches neither the fixture nor the table"),
                    () -> assertNotEquals(PicClause.CARD_XREF_RECORD_LENGTH,
                            PicClause.CARDXREF_FIXTURE_RECORD_WIDTH,
                            "the declared length and the fixture width differ, so the parser "
                                    + "is width-tolerant"));
        }
    }

    /**
     * Counts the mapped attributes of each entity class and scans every mapped name.
     *
     * <p>A count is what catches a column the copybook does not declare. A test naming only the
     * columns it expects passes with a seventh column present.
     */
    @Nested
    @DisplayName("Mapped attributes, with both trailing fillers dropped")
    class MappedAttributes {

        @Test
        @DisplayName("CardEntity maps CVACT02Y.cpy:L5-L10 and three additive columns")
        void cardEntityMapsTheSixCopybookFieldsAndOneAdditiveColumn() {
            List<String> columns = columnNames(CardEntity.class);
            assertAll(
                    () -> assertEquals(
                            List.of("card_number", "account_id", "card_verification_value",
                                    "embossed_name", "expiration_date", "active_status"),
                            columns.subList(0, 6),
                            "the six mapped columns of card, in copybook order"),
                    () -> assertEquals(
                            List.of("card_token", "card_token_version", "card_token_provenance"),
                            columns.subList(6, columns.size()),
                            "the three additive card-token columns, no COBOL ancestor. The first "
                                    + "carries the paging cursor of the card list, which AAP "
                                    + "section 0.6.4 forbids carrying a card number. The other two "
                                    + "V10__card_token_version_and_rotation.sql adds say which key "
                                    + "the stored token belongs to and whether this deployment "
                                    + "derived it, which is what makes moving one an audited act "
                                    + "rather than a start-up rewrite"),
                    () -> assertEquals(9, columns.size(),
                            "six copybook columns plus three additive columns"));
        }

        @Test
        @DisplayName("CardCrossReferenceEntity maps CVACT03Y.cpy:L5-L7 and three additive columns")
        void crossReferenceEntityMapsThreeCopybookFieldsAndThreeAdditiveColumns() {
            List<String> columns = columnNames(CardCrossReferenceEntity.class);
            assertAll(
                    () -> assertEquals(List.of("card_number", "customer_id", "account_id"),
                            columns.subList(0, 3),
                            "the three mapped columns of card_xref, in copybook order"),
                    () -> assertEquals(
                            List.of("source_event_id", "source_occurred_at", "observed_at"),
                            columns.subList(3, columns.size()),
                            "the three additive replica-freshness columns, no COBOL ancestor"),
                    () -> assertEquals(6, columns.size(),
                            "three copybook columns plus three additive columns"));
        }

        @Test
        @DisplayName("OutboxEventEntity maps six event columns, eight relay columns and two "
                + "correlation columns")
        void outboxEventEntityMapsSixteenColumns() {
            List<String> columns = columnNames(OutboxEventEntity.class);
            assertAll(
                    () -> assertTrue(columns.containsAll(List.of("event_id", "event_type",
                                    "aggregate_id", "payload", "published", "created_at")),
                            "the six event columns of outbox_event"),
                    () -> assertTrue(columns.containsAll(List.of("relay_state", "attempt_count",
                                    "next_attempt_at", "last_attempt_at", "last_error",
                                    "claimed_by", "claimed_at", "published_at")),
                            "the eight additive relay columns, no COBOL ancestor"),
                    () -> assertTrue(
                            columns.containsAll(List.of("correlation_id", "causation_id")),
                            "the two additive correlation columns V6__outbox_correlation.sql adds,"
                                    + " no COBOL ancestor"),
                    () -> assertEquals(16, columns.size(),
                            "six event columns, eight relay columns and two correlation columns"));
        }

        @Test
        @DisplayName("the card service keeps the shared processed-event marker contract ready")
        void cardServiceDeclaresTheSharedProcessedEventMarker() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                assertAll(
                        () -> assertEquals("processed_event",
                                TABLE_NAMES.get(ProcessedEventEntity.class),
                                "the entity inventory includes the shared marker"),
                        () -> assertEquals(
                                List.of("event_id", "consumed_topic", "processed_at"),
                                columnNames(ProcessedEventEntity.class),
                                "the marker maps its two key parts and its processing time; the"
                                        + " identifier and the consumed topic are the key since"
                                        + " V3__processed_event_topic_key.sql"),
                        () -> assertFalse(
                                columnFacts(connection, schema, "processed_event").isEmpty(),
                                "the migration creates the marker table"));
            }
        }

        @Test
        @DisplayName("no entity maps a filler column, from CVACT02Y.cpy:L11 or CVACT03Y.cpy:L8")
        void noEntityMapsAFillerColumn() {
            List<String> offenders = new ArrayList<>();
            for (Class<?> entity : ENTITY_CLASSES) {
                for (Field field : mappedFields(entity)) {
                    if (folded(field.getName()).contains("filler")
                            || folded(columnName(field)).contains("filler")) {
                        offenders.add(entity.getSimpleName() + "." + field.getName());
                    }
                }
            }
            assertEquals(List.of(), offenders,
                    "the 59-byte and the 14-byte trailing filler carry no column");
        }

        @Test
        @DisplayName("the corrected expiry identifier is mapped and CARD-EXPIRAION is absent")
        void theExpiryIdentifierCarriesTheCorrectedSpelling() {
            List<String> misspelt = new ArrayList<>();
            for (Class<?> entity : ENTITY_CLASSES) {
                for (Field field : mappedFields(entity)) {
                    if (folded(field.getName()).contains("expiraion")
                            || folded(columnName(field)).contains("expiraion")) {
                        misspelt.add(entity.getSimpleName() + "." + field.getName());
                    }
                }
            }
            assertAll(
                    () -> assertTrue(fieldNames(CardEntity.class).contains("expirationDate"),
                            "field expirationDate"),
                    () -> assertTrue(columnNames(CardEntity.class).contains("expiration_date"),
                            "column expiration_date"),
                    () -> assertEquals(List.of(), misspelt,
                            "app/cpy/CVACT02Y.cpy:L9 spells the field CARD-EXPIRAION-DATE, and the"
                                    + " rename is recorded in the traceability matrix"));
        }

        @Test
        @DisplayName("no entity maps a screen-navigation field from CVCRD01Y.cpy:L21-L24")
        void noEntityMapsAScreenNavigationField() {
            List<String> dropped = List.of("nextprog", "nextmapset", "nextmap");
            List<String> offenders = new ArrayList<>();
            for (Class<?> entity : ENTITY_CLASSES) {
                for (Field field : mappedFields(entity)) {
                    for (String navigation : dropped) {
                        if (folded(field.getName()).contains(navigation)
                                || folded(columnName(field)).contains(navigation)) {
                            offenders.add(entity.getSimpleName() + "." + field.getName());
                        }
                    }
                }
            }
            assertEquals(List.of(), offenders,
                    "CCARD-NEXT-PROG, CCARD-NEXT-MAPSET and CCARD-NEXT-MAP are dropped under"
                            + " transformation rule T6");
        }

        @Test
        @DisplayName("no entity declares a version field")
        void noEntityDeclaresAVersionField() {
            List<String> versioned = new ArrayList<>();
            for (Class<?> entity : ENTITY_CLASSES) {
                for (Field field : mappedFields(entity)) {
                    if (field.isAnnotationPresent(Version.class)) {
                        versioned.add(entity.getSimpleName() + "." + field.getName());
                    }
                }
            }
            assertEquals(List.of(), versioned,
                    "the concurrency mechanism is the field comparison at"
                            + " app/cbl/COCRDUPC.cbl:L1503-L1508");
        }

        @Test
        @DisplayName("each entity declares the identifier columns of its KEYS parameter")
        void eachEntityDeclaresOneIdentifierField() {
            Map<Class<?>, List<String>> identifiers = new LinkedHashMap<>();
            for (Class<?> entity : ENTITY_CLASSES) {
                assertNull(identifiers.put(entity, identifierColumns(entity)),
                        entity.getSimpleName() + " is listed twice");
            }
            assertAll(
                    () -> assertEquals(List.of("card_number"), identifiers.get(CardEntity.class),
                            "KEYS(16 0) at app/jcl/CARDFILE.jcl:L54"),
                    () -> assertEquals(List.of("card_number"),
                            identifiers.get(CardCrossReferenceEntity.class),
                            "KEYS(16 0) at app/jcl/XREFFILE.jcl:L43"),
                    () -> assertEquals(List.of("event_id"),
                            identifiers.get(OutboxEventEntity.class),
                            "the event identifier is the outbox key"),
                    () -> assertEquals(List.of("event_id", "consumed_topic"),
                            identifiers.get(ProcessedEventEntity.class),
                            "the duplicate-delivery guard keys on the event and the topic the"
                                    + " delivery arrived on, from"
                                    + " V3__processed_event_topic_key.sql"));
        }
    }

    /**
     * Reads the column types out of the migrated schema and compares them with the derived types.
     *
     * <p>PostgreSQL reports a fixed-width character column as {@code bpchar} through Java Database
     * Connectivity (JDBC) metadata. Every identifier column below holds characters and keeps its
     * leading zeros.
     */
    @Nested
    @DisplayName("Column types derived from the copybook Picture clauses")
    class ColumnTypes {

        @Test
        @DisplayName("each entity maps exactly the columns its table declares")
        void eachEntityMapsExactlyTheColumnsItsTableDeclares() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                for (Class<?> entity : ENTITY_CLASSES) {
                    String table = TABLE_NAMES.get(entity);
                    Map<String, ColumnFact> facts = columnFacts(connection, schema, table);
                    assertEquals(List.copyOf(facts.keySet()).stream().sorted().toList(),
                            columnNames(entity).stream().sorted().toList(),
                            "mapped columns of " + entity.getSimpleName()
                                    + " against the columns of " + table);
                }
            }
        }

        @Test
        @DisplayName("card holds four character columns, one date and one flag, none nullable")
        void cardColumnsCarryTheDerivedTypes() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                Map<String, ColumnFact> facts = columnFacts(connection, schema, "card");
                assertAll(
                        () -> assertEquals(new ColumnFact("bpchar", PicClause.CARD_NUM_WIDTH, 0,
                                        false), facts.get("card_number"),
                                "CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5"),
                        () -> assertEquals(new ColumnFact("bpchar", PicClause.CARD_ACCT_ID_WIDTH, 0,
                                        false), facts.get("account_id"),
                                "CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6"),
                        () -> assertEquals(new ColumnFact("bpchar", PicClause.CARD_CVV_CD_WIDTH, 0,
                                        false), facts.get("card_verification_value"),
                                "CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7"),
                        () -> assertEquals(new ColumnFact("bpchar",
                                        PicClause.CARD_EMBOSSED_NAME_WIDTH, 0, false),
                                facts.get("embossed_name"),
                                "CARD-EMBOSSED-NAME PIC X(50) at app/cpy/CVACT02Y.cpy:L8"),
                        () -> assertEquals("date", facts.get("expiration_date").typeName(),
                                "CARD-EXPIRAION-DATE PIC X(10) at app/cpy/CVACT02Y.cpy:L9"),
                        () -> assertFalse(facts.get("expiration_date").nullable(),
                                "expiration_date NOT NULL"),
                        () -> assertEquals(new ColumnFact("bpchar",
                                        PicClause.CARD_ACTIVE_STATUS_WIDTH, 0, false),
                                facts.get("active_status"),
                                "CARD-ACTIVE-STATUS PIC X(01) at app/cpy/CVACT02Y.cpy:L10"));
            }
        }

        @Test
        @DisplayName("card_xref holds a nine-character customer identifier and an eleven-character"
                + " account identifier")
        void crossReferenceColumnsCarryTheDerivedTypes() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                Map<String, ColumnFact> facts = columnFacts(connection, schema, "card_xref");
                assertAll(
                        () -> assertEquals(new ColumnFact("bpchar", PicClause.XREF_CARD_NUM_WIDTH,
                                        0, false), facts.get("card_number"),
                                "XREF-CARD-NUM PIC X(16) at app/cpy/CVACT03Y.cpy:L5"),
                        () -> assertEquals(new ColumnFact("bpchar", PicClause.XREF_CUST_ID_WIDTH, 0,
                                        false), facts.get("customer_id"),
                                "XREF-CUST-ID PIC 9(09) at app/cpy/CVACT03Y.cpy:L6"),
                        () -> assertEquals(new ColumnFact("bpchar", PicClause.XREF_ACCT_ID_WIDTH, 0,
                                        false), facts.get("account_id"),
                                "XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7"),
                        () -> assertEquals("uuid", facts.get("source_event_id").typeName(),
                                "the additive provenance column"),
                        () -> assertTrue(facts.get("source_event_id").nullable(),
                                "a seeded row names no event"),
                        () -> assertEquals("timestamptz",
                                facts.get("source_occurred_at").typeName(),
                                "the additive ordering column"),
                        () -> assertFalse(facts.get("observed_at").nullable(),
                                "observed_at NOT NULL, the freshness column"));
            }
        }

        @Test
        @DisplayName("outbox_event.aggregate_id holds the eleven characters CVACT03Y.cpy:L7 fixes")
        void outboxEventColumnsCarryTheDerivedTypes() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                Map<String, ColumnFact> facts = columnFacts(connection, schema, "outbox_event");
                assertAll(
                        () -> assertEquals(new ColumnFact("bpchar", PicClause.XREF_ACCT_ID_WIDTH, 0,
                                        false), facts.get("aggregate_id"),
                                "the account identifier and the message key, leading zeros kept"),
                        () -> assertEquals(PicClause.XREF_ACCT_ID_WIDTH,
                                OutboxEventEntity.AGGREGATE_ID_LENGTH,
                                "the entity reads its width from the same constant"),
                        () -> assertEquals("uuid", facts.get("event_id").typeName(),
                                "the event identifier is a Universally Unique Identifier (UUID)"),
                        () -> assertEquals(new ColumnFact("varchar",
                                        OutboxEventEntity.EVENT_TYPE_MAX_LENGTH, 0, false),
                                facts.get("event_type"), "the routing discriminator"),
                        () -> assertEquals("text", facts.get("payload").typeName(),
                                "one serialized event as text"),
                        () -> assertEquals("bool", facts.get("published").typeName(),
                                "the publication flag"),
                        () -> assertEquals("timestamptz", facts.get("created_at").typeName(),
                                "the arrival order of the relay"),
                        () -> assertEquals(6, facts.get("created_at").decimalDigits(),
                                "TIMESTAMP(6) WITH TIME ZONE"));
            }
        }

        @Test
        @DisplayName("the card expiry column type differs from the account expiry column type")
        void theCardExpiryCarriesACalendarDateAndTheAccountExpiryCarriesText() throws Exception {
            Field expiry = CardEntity.class.getDeclaredField("expirationDate");
            assertAll(
                    () -> assertEquals("DATE", PicClause.CARD_EXPIRATION_DATE_COLUMN_TYPE,
                            "the card expiry is decomposed and displayed, never compared as text"),
                    () -> assertEquals("VARCHAR(10)", PicClause.ACCT_EXPIRATION_DATE_COLUMN_TYPE,
                            "app/cbl/CBTRN02C.cbl:L414 compares the account expiry as text"),
                    () -> assertNotEquals(PicClause.ACCT_EXPIRATION_DATE_COLUMN_TYPE,
                            PicClause.CARD_EXPIRATION_DATE_COLUMN_TYPE,
                            "one Picture clause, two column types"),
                    () -> assertEquals(LocalDate.class, expiry.getType(),
                            "the Java attribute of expiration_date"));
        }
    }

    /**
     * Compares the indexes the entity classes declare with the indexes the migration creates.
     *
     * <p>{@code NONUNIQUEKEY} at {@code app/jcl/CARDFILE.jcl:L86} and at
     * {@code app/jcl/XREFFILE.jcl:L75} admits many rows per account, and both account indexes below
     * carry no unique constraint. {@code app/cbl/COCRDLIC.cbl:L1157-L1158} treats
     * {@code DFHRESP(NORMAL)} and {@code DFHRESP(DUPREC)} as one outcome.
     */
    @Nested
    @DisplayName("Table names and indexes, against the JCL key definitions")
    class Indexes {

        @Test
        @DisplayName("each entity names the table V1__schema.sql creates")
        void eachEntityNamesItsTable() {
            for (Class<?> entity : ENTITY_CLASSES) {
                Table table = entity.getAnnotation(Table.class);
                assertNotNull(table, entity.getSimpleName() + " carries no Table annotation");
                assertEquals(TABLE_NAMES.get(entity), table.name(),
                        "table of " + entity.getSimpleName());
                assertTrue(table.schema().isEmpty(),
                        entity.getSimpleName() + " names no schema, and application.yml supplies"
                                + " one at run time");
            }
        }

        @Test
        @DisplayName("each entity declares exactly the indexes the migration creates")
        void eachEntityDeclaresExactlyTheIndexesTheMigrationCreates() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                for (Class<?> entity : ENTITY_CLASSES) {
                    String table = TABLE_NAMES.get(entity);
                    Map<String, IndexFact> created = indexFacts(connection, schema, table);
                    List<String> secondary = created.keySet().stream()
                            .filter(name -> !created.get(name).unique())
                            .sorted()
                            .toList();
                    assertEquals(secondary,
                            declaredIndexes(entity).keySet().stream().sorted().toList(),
                            "declared indexes of " + entity.getSimpleName()
                                    + " against the non-unique indexes of " + table);
                }
            }
        }

        @Test
        @DisplayName("card carries pk_card, uq_card_card_token and the non-unique"
                + " idx_card_account_id")
        void cardCarriesItsPrimaryKeyAndTheAccountIndex() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                Map<String, IndexFact> facts = indexFacts(connection, schema, "card");
                assertAll(
                        () -> assertEquals(new IndexFact(List.of("card_number"), true),
                                facts.get("pk_card"),
                                "KEYS(16 0) at app/jcl/CARDFILE.jcl:L54"),
                        () -> assertEquals(
                                new IndexFact(List.of("account_id", "card_number"), false),
                                facts.get("idx_card_account_id"),
                                "KEYS(11 16) and NONUNIQUEKEY at app/jcl/CARDFILE.jcl:L85-L86"),
                        () -> assertEquals(new IndexFact(List.of("card_token"), true),
                                facts.get("uq_card_card_token"),
                                "the additive card-token identity, unique so that one paging "
                                        + "cursor reaches one browse position"),
                        () -> assertEquals(3, facts.size(), "no fourth index on card"));
            }
        }

        @Test
        @DisplayName("card_xref carries pk_card_xref, idx_card_xref_account_id and the freshness"
                + " index")
        void crossReferenceCarriesItsPrimaryKeyAndTheAccountIndex() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                Map<String, IndexFact> facts = indexFacts(connection, schema, "card_xref");
                assertAll(
                        () -> assertEquals(new IndexFact(List.of("card_number"), true),
                                facts.get("pk_card_xref"),
                                "KEYS(16 0) at app/jcl/XREFFILE.jcl:L43"),
                        () -> assertEquals(new IndexFact(List.of("account_id"), false),
                                facts.get("idx_card_xref_account_id"),
                                "KEYS(11,25) and NONUNIQUEKEY at app/jcl/XREFFILE.jcl:L74-L75"),
                        () -> assertEquals(new IndexFact(List.of("observed_at"), false),
                                facts.get("ix_card_xref_observed_at"),
                                "the additive replica-freshness index"),
                        () -> assertEquals(3, facts.size(), "no fourth index on card_xref"));
            }
        }

        @Test
        @DisplayName("the outbox indexes key on arrival order, claimability and the account head")
        void theUnpublishedOutboxIndexKeysOnArrivalOrder() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                Map<String, IndexFact> facts = indexFacts(connection, schema, "outbox_event");
                assertAll(
                        () -> assertEquals(new IndexFact(List.of("created_at", "event_id"), false),
                                facts.get("ix_outbox_event_pending"),
                                "the relay reads unpublished rows in arrival order, event_id"
                                        + " breaking a tie"),
                        () -> assertEquals(
                                new IndexFact(List.of("relay_state", "next_attempt_at"), false),
                                facts.get("ix_outbox_event_claimable"),
                                "the claim query filters on relay_state and orders on"
                                        + " next_attempt_at"),
                        () -> assertEquals(new IndexFact(List.of("published_at"), false),
                                facts.get("ix_outbox_event_published_at"),
                                "the retention path over published rows"),
                        () -> assertEquals(
                                new IndexFact(
                                        List.of("aggregate_id", "created_at", "event_id"), false),
                                facts.get("ix_outbox_event_aggregate_head"),
                                "the claim takes the due head row of each account, so it reads by"
                                        + " aggregate and then arrival order"),
                        () -> assertEquals(new IndexFact(List.of("event_id"), true),
                                facts.get("pk_outbox_event"), "the event identifier is the key"),
                        () -> assertEquals(5, facts.size(), "no sixth index on outbox_event"));
            }
        }

    }

    /**
     * Reads the constructor and method surface of each entity class.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1466}, L1467 to L1474 and L1475 move an embossed name, an
     * assembled expiration date and an active status onto the record. Those three fields are the
     * whole update surface of {@link CardEntity#applyUpdate}.
     */
    @Nested
    @DisplayName("Constructor and mutator surface")
    class ApiShape {

        @Test
        @DisplayName("each entity declares a protected no-argument constructor for the provider")
        void eachEntityDeclaresAProtectedNoArgumentConstructor() throws Exception {
            for (Class<?> entity : ENTITY_CLASSES) {
                Constructor<?> constructor = entity.getDeclaredConstructor();
                assertTrue(Modifier.isProtected(constructor.getModifiers()),
                        entity.getSimpleName() + " declares a protected no-argument constructor");
            }
        }

        @Test
        @DisplayName("CardEntity derives its card token from its card number")
        void cardEntityDerivesItsCardTokenFromItsCardNumber() {
            CardEntity card = new CardEntity("0500024453765740", "00000000050", "747",
                    "Aniya Von", LocalDate.of(2023, 3, 9), "Y");
            assertAll(
                    () -> assertEquals(PanMasker.cardToken("0500024453765740"),
                            card.getCardToken(),
                            "the constructor derives card_token through PanMasker.cardToken, "
                                    + "which is the derivation V2__seed.sql repeats in SQL"),
                    () -> assertTrue(card.getCardToken().matches(PanMasker.CARD_TOKEN_PATTERN),
                            "the derived value matches the shape ck_card_card_token_hex declares"),
                    () -> assertFalse(card.getCardToken().contains("5740"),
                            "no digit run of the card number survives the digest, so the token "
                                    + "carries no part of the Primary Account Number"));
        }

        @Test
        @DisplayName("CardEntity declares one public constructor taking the six copybook fields")
        void cardEntityDeclaresOneAllArgumentsConstructor() {
            List<Constructor<?>> publicConstructors = new ArrayList<>();
            for (Constructor<?> constructor : CardEntity.class.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    publicConstructors.add(constructor);
                }
            }
            assertAll(
                    () -> assertEquals(1, publicConstructors.size(),
                            "one public constructor on CardEntity"),
                    () -> assertEquals(
                            List.of(String.class, String.class, String.class, String.class,
                                    LocalDate.class, String.class),
                            List.of(publicConstructors.get(0).getParameterTypes()),
                            "the six copybook fields in copybook order. The three mapped "
                                    + "card-token columns are derived or defaulted and none is "
                                    + "accepted, because a caller naming a token version would be "
                                    + "naming a key it does not hold"));
        }

        @Test
        @DisplayName("applyUpdate(String, LocalDate, String) is the only mutator of CardEntity")
        void applyUpdateIsTheOnlyMutatorOfCardEntity() throws Exception {
            Method applyUpdate = CardEntity.class.getDeclaredMethod("applyUpdate", String.class,
                    LocalDate.class, String.class);
            assertAll(
                    () -> assertTrue(Modifier.isPublic(applyUpdate.getModifiers()),
                            "applyUpdate is public"),
                    () -> assertEquals(void.class, applyUpdate.getReturnType(),
                            "applyUpdate returns nothing"),
                    () -> assertEquals(List.of(), declaredSetterNames(CardEntity.class),
                            "CardEntity declares no setter"),
                    () -> assertTrue(declaredPublicMethodNames(CardEntity.class)
                                    .containsAll(List.of("applyUpdate", "getCardNumber",
                                            "getAccountId", "getEmbossedName",
                                            "getExpirationDate", "getActiveStatus",
                                            "getCardToken", "getCardTokenVersion",
                                            "getCardTokenProvenance")),
                            "one mutator beside the eight accessors"),
                    () -> assertEquals(9, declaredPublicMethodNames(CardEntity.class).size(),
                            "no tenth public method on CardEntity. The two accessors "
                                    + "V10__card_token_version_and_rotation.sql added report which "
                                    + "key a stored token belongs to and whether this deployment "
                                    + "derived it, and card_verification_value still has none"));
        }

        @Test
        @DisplayName("markObserved is the only mutator of the cross-reference replica")
        void markObservedIsTheOnlyMutatorOfTheCrossReferenceReplica() throws Exception {
            Method markObserved = CardCrossReferenceEntity.class.getDeclaredMethod("markObserved",
                    UUID.class, Instant.class, Instant.class);
            assertAll(
                    () -> assertTrue(Modifier.isPublic(markObserved.getModifiers()),
                            "markObserved is public"),
                    () -> assertEquals(List.of(),
                            declaredSetterNames(CardCrossReferenceEntity.class),
                            "the replica declares no setter, and an event replaces a row whole"),
                    () -> assertEquals(1, declaredPublicMethodNames(CardCrossReferenceEntity.class)
                                    .stream().filter(name -> name.startsWith("mark")).count(),
                            "one mark method on the replica"));
        }

        @Test
        @DisplayName("OutboxEventEntity mutates through markPublished, claim and recordFailure")
        void outboxEventEntityDeclaresThreeNamedMutators() throws Exception {
            assertAll(
                    () -> assertNotNull(OutboxEventEntity.class.getDeclaredMethod("markPublished",
                            Instant.class), "markPublished(Instant)"),
                    () -> assertNotNull(OutboxEventEntity.class.getDeclaredMethod("claim",
                            String.class, Instant.class), "claim(String, Instant)"),
                    () -> assertNotNull(OutboxEventEntity.class.getDeclaredMethod("recordFailure",
                                    String.class, Instant.class, Instant.class),
                            "recordFailure(String, Instant, Instant)"),
                    () -> assertEquals(List.of(), declaredSetterNames(OutboxEventEntity.class),
                            "the outbox row declares no setter"));
        }

    }

    /**
     * Compares two rows that differ in every mapped field except the card number.
     *
     * <p>A caller supplies the primary key at construction, and no database sequence assigns one.
     * The comparison reads {@code card_number} alone on both entities that carry it.
     */
    @Nested
    @DisplayName("Identity on the card number alone, the key from KEYS(16 0)")
    class Identity {

        private static final String FIRST_CARD_NUMBER = syntheticCardNumber(710_001L);

        private static final String SECOND_CARD_NUMBER = syntheticCardNumber(710_002L);

        @Test
        @DisplayName("two card rows sharing a card number are equal and hash alike")
        void twoCardRowsSharingACardNumberAreEqual() {
            CardEntity first = new CardEntity(FIRST_CARD_NUMBER, syntheticDigits(11, 1L),
                    syntheticDigits(3, 1L), "First Holder", LocalDate.of(2027, 1, 1),
                    SYNTHETIC_ACTIVE_STATUS);
            CardEntity second = new CardEntity(FIRST_CARD_NUMBER, syntheticDigits(11, 2L),
                    syntheticDigits(3, 2L), "Second Holder", LocalDate.of(2028, 2, 2), "N");
            assertAll(
                    () -> assertEquals(first, second, "equal on the card number alone"),
                    () -> assertEquals(first.hashCode(), second.hashCode(),
                            "hashCode reads the card number alone"));
        }

        @Test
        @DisplayName("two card rows differing only in the card number are not equal")
        void twoCardRowsDifferingOnlyInTheCardNumberAreNotEqual() {
            CardEntity first = new CardEntity(FIRST_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID,
                    SYNTHETIC_CARD_VERIFICATION_VALUE, SYNTHETIC_EMBOSSED_NAME,
                    SYNTHETIC_EXPIRATION_DATE, SYNTHETIC_ACTIVE_STATUS);
            CardEntity second = new CardEntity(SECOND_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID,
                    SYNTHETIC_CARD_VERIFICATION_VALUE, SYNTHETIC_EMBOSSED_NAME,
                    SYNTHETIC_EXPIRATION_DATE, SYNTHETIC_ACTIVE_STATUS);
            assertNotEquals(first, second, "the card number is the only field the test reads");
        }

        @Test
        @DisplayName("two cross-reference rows sharing a card number are equal and hash alike")
        void twoCrossReferenceRowsSharingACardNumberAreEqual() {
            Instant firstObservation = Instant.parse("2026-01-02T03:04:05.123456Z");
            Instant secondObservation = Instant.parse("2026-02-03T04:05:06.654321Z");
            CardCrossReferenceEntity first = new CardCrossReferenceEntity(FIRST_CARD_NUMBER,
                    syntheticDigits(9, 1L), syntheticDigits(11, 1L), firstObservation);
            CardCrossReferenceEntity second = new CardCrossReferenceEntity(FIRST_CARD_NUMBER,
                    syntheticDigits(9, 2L), syntheticDigits(11, 2L), secondObservation);
            assertAll(
                    () -> assertEquals(first, second, "equal on the card number alone"),
                    () -> assertEquals(first.hashCode(), second.hashCode(),
                            "hashCode reads the card number alone"));
        }

        @Test
        @DisplayName("two cross-reference rows differing only in the card number are not equal")
        void twoCrossReferenceRowsDifferingOnlyInTheCardNumberAreNotEqual() {
            Instant observation = Instant.parse("2026-01-02T03:04:05.123456Z");
            CardCrossReferenceEntity first = new CardCrossReferenceEntity(FIRST_CARD_NUMBER,
                    SYNTHETIC_CUSTOMER_ID, SYNTHETIC_ACCOUNT_ID, observation);
            CardCrossReferenceEntity second = new CardCrossReferenceEntity(SECOND_CARD_NUMBER,
                    SYNTHETIC_CUSTOMER_ID, SYNTHETIC_ACCOUNT_ID, observation);
            assertNotEquals(first, second, "the card number is the only field the test reads");
        }
    }

    /**
     * Checks Flyway seed counts and reads generated synthetic rows through the mappings.
     *
     * <p>No test below opens a file under {@code app/}. A fixed-width character column returns its
     * padding, so a value comparison trims and a width comparison reads the length.
     */
    @Nested
    @Transactional
    @DisplayName("Flyway seed counts and synthetic mapping rows")
    class PersistenceRows {

        @Test
        @DisplayName("Flyway loaded 50 card rows and 50 cross-reference rows")
        void flywayLoadedFiftyRowsIntoEachSeededTable() {
            assertAll(
                    () -> assertEquals(SEEDED_ROW_COUNT, rowCount("card"),
                            "50 records of app/data/ASCII/carddata.txt"),
                    () -> assertEquals(SEEDED_ROW_COUNT, rowCount("card_xref"),
                            "50 records of app/data/ASCII/cardxref.txt"));
        }

        @Test
        @DisplayName("a synthetic card row reads back field for field")
        void aSyntheticCardRowReadsBackFieldForField() {
            entityManager.persist(new CardEntity(
                    SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_ACCOUNT_ID,
                    SYNTHETIC_CARD_VERIFICATION_VALUE,
                    SYNTHETIC_EMBOSSED_NAME,
                    SYNTHETIC_EXPIRATION_DATE,
                    SYNTHETIC_ACTIVE_STATUS));
            entityManager.flush();
            entityManager.clear();

            CardEntity card = entityManager.find(CardEntity.class, SYNTHETIC_CARD_NUMBER);
            assertNotNull(card, "the synthetic card row");
            assertAll(
                    () -> assertSameSensitiveValue(SYNTHETIC_CARD_NUMBER, card.getCardNumber(),
                            "columns 1 through 16"),
                    () -> assertSameSensitiveValue(SYNTHETIC_ACCOUNT_ID, card.getAccountId(),
                            "columns 17 through 27, leading zeros intact"),
                    () -> assertEquals(PicClause.CARD_ACCT_ID_WIDTH,
                            card.getAccountId().length(),
                            "the eleven characters CARD-ACCT-ID PIC 9(11) declares"),
                    () -> assertEquals(SYNTHETIC_EMBOSSED_NAME, card.getEmbossedName().trim(),
                            "columns 31 through 80, trimmed of the padding"),
                    () -> assertEquals(PicClause.CARD_EMBOSSED_NAME_WIDTH,
                            card.getEmbossedName().length(),
                            "the fifty characters CARD-EMBOSSED-NAME PIC X(50) declares"),
                    () -> assertEquals(SYNTHETIC_EXPIRATION_DATE, card.getExpirationDate(),
                            "columns 81 through 90, read as a calendar date"),
                    () -> assertEquals(SYNTHETIC_ACTIVE_STATUS, card.getActiveStatus(),
                            "column 91"));
        }

        @Test
        @DisplayName("a synthetic card verification value keeps its three characters")
        void aSyntheticCardVerificationValueKeepsItsWidth() {
            entityManager.persist(new CardEntity(
                    SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_ACCOUNT_ID,
                    SYNTHETIC_CARD_VERIFICATION_VALUE,
                    SYNTHETIC_EMBOSSED_NAME,
                    SYNTHETIC_EXPIRATION_DATE,
                    SYNTHETIC_ACTIVE_STATUS));
            entityManager.flush();

            Object stored = entityManager
                    .createNativeQuery("SELECT card_verification_value FROM " + qualified("card")
                            + " WHERE card_number = :cardNumber")
                    .setParameter("cardNumber", SYNTHETIC_CARD_NUMBER)
                    .getSingleResult();
            String text = String.valueOf(stored).trim();
            assertAll(
                    () -> assertEquals(PicClause.CARD_CVV_CD_WIDTH, text.length(),
                            "the three characters CARD-CVV-CD PIC 9(03) declares"),
                    () -> assertTrue(text.matches("^[0-9]{3}$"), "three decimal digits"),
                    () -> assertSameSensitiveValue(
                            SYNTHETIC_CARD_VERIFICATION_VALUE, text,
                            "the stored verification value differs"));
        }

        @Test
        @DisplayName("a synthetic cross-reference row reads back field for field")
        void aSyntheticCrossReferenceRowReadsBackFieldForField() {
            Instant observedAt = Instant.parse("2026-08-04T12:00:00Z");
            entityManager.persist(new CardCrossReferenceEntity(
                    SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_CUSTOMER_ID,
                    SYNTHETIC_ACCOUNT_ID,
                    observedAt));
            entityManager.flush();
            entityManager.clear();

            CardCrossReferenceEntity xref =
                    entityManager.find(CardCrossReferenceEntity.class, SYNTHETIC_CARD_NUMBER);
            assertNotNull(xref, "the synthetic cross-reference row");
            assertAll(
                    () -> assertSameSensitiveValue(SYNTHETIC_CARD_NUMBER, xref.getCardNumber(),
                            "offset 0, the key from KEYS(16 0)"),
                    () -> assertSameSensitiveValue(SYNTHETIC_CUSTOMER_ID, xref.getCustomerId(),
                            "offset 16, nine characters, leading zeros intact"),
                    () -> assertEquals(PicClause.XREF_CUST_ID_WIDTH,
                            xref.getCustomerId().length(),
                            "the nine characters XREF-CUST-ID PIC 9(09) declares"),
                    () -> assertSameSensitiveValue(SYNTHETIC_ACCOUNT_ID, xref.getAccountId(),
                            "offset 25, the alternate-index key from KEYS(11,25)"),
                    () -> assertEquals(PicClause.XREF_ACCT_ID_WIDTH,
                            xref.getAccountId().length(),
                            "the eleven characters XREF-ACCT-ID PIC 9(11) declares"));
        }

        @Test
        @DisplayName("a synthetic cross-reference row names no event and carries an "
                + "observation time")
        void aSyntheticCrossReferenceRowNamesNoEvent() {
            Instant observedAt = Instant.parse("2026-08-04T12:00:00Z");
            entityManager.persist(new CardCrossReferenceEntity(
                    SYNTHETIC_CARD_NUMBER,
                    SYNTHETIC_CUSTOMER_ID,
                    SYNTHETIC_ACCOUNT_ID,
                    observedAt));
            entityManager.flush();
            entityManager.clear();

            CardCrossReferenceEntity xref =
                    entityManager.find(CardCrossReferenceEntity.class, SYNTHETIC_CARD_NUMBER);
            assertNotNull(xref, "the synthetic cross-reference row");
            assertAll(
                    () -> assertNull(xref.getSourceEventId(),
                            "the constructor names no source event"),
                    () -> assertNull(xref.getSourceOccurredAt(),
                            "both halves of the provenance are absent together"),
                    () -> assertEquals(observedAt, xref.getObservedAt(),
                            "observed_at carries the supplied observation time"));
        }
    }

    /**
     * Writes a row through each mapping and reads it back inside the same transaction.
     *
     * <p>Spring rolls each test below back, so the seeded row counts hold for every other test in
     * this class.
     *
     * <p>Column 91 of all 50 records of {@code app/data/ASCII/carddata.txt} holds {@code Y}. The
     * inactive card below is constructed here, and no fixture row supplies one.
     */
    @Nested
    @Transactional
    @DisplayName("Round trip through the migrated schema")
    class RoundTrip {

        /** Generated card number used by round-trip cases. */
        private static final String UNSEEDED_CARD_NUMBER = syntheticCardNumber(720_001L);

        @Test
        @DisplayName("a mixed-case embossed name persists with its casing preserved")
        void aMixedCaseEmbossedNamePersistsWithItsCasingPreserved() {
            String submitted = "MiXeD cAsE nAmE";
            entityManager.persist(new CardEntity(
                    UNSEEDED_CARD_NUMBER,
                    SYNTHETIC_ACCOUNT_ID,
                    syntheticDigits(3, 7L),
                    submitted,
                    LocalDate.of(2027, 12, 31),
                    "N"));
            entityManager.flush();
            entityManager.clear();

            CardEntity reread = entityManager.find(CardEntity.class, UNSEEDED_CARD_NUMBER);
            assertNotNull(reread, "the row just written");
            assertAll(
                    () -> assertEquals(submitted, reread.getEmbossedName().trim(),
                            "app/cbl/COCRDUPC.cbl:L1466 writes the submitted casing with no fold"),
                    () -> assertEquals(PicClause.CARD_EMBOSSED_NAME_WIDTH,
                            reread.getEmbossedName().length(), "the column pads to fifty"),
                    () -> assertEquals("N", reread.getActiveStatus(),
                            "the flag domain of app/cbl/COCRDUPC.cbl:L91 holds Y and N"));
        }

        @Test
        @DisplayName("an expiration date persists and reads back as a LocalDate")
        void anExpirationDatePersistsAndReadsBackAsACalendarDate() {
            LocalDate submitted = LocalDate.of(2027, 12, 31);
            entityManager.persist(new CardEntity(
                    UNSEEDED_CARD_NUMBER,
                    SYNTHETIC_ACCOUNT_ID,
                    syntheticDigits(3, 7L),
                    "Round Trip",
                    submitted,
                    SYNTHETIC_ACTIVE_STATUS));
            entityManager.flush();
            entityManager.clear();

            CardEntity reread = entityManager.find(CardEntity.class, UNSEEDED_CARD_NUMBER);
            assertNotNull(reread, "the row just written");
            assertAll(
                    () -> assertEquals(submitted, reread.getExpirationDate(),
                            "the DATE column returns the calendar date it stored"),
                    () -> assertEquals(LocalDate.class, reread.getExpirationDate().getClass(),
                            "the attribute type of expiration_date"));
        }

        @Test
        @DisplayName("an eleven-digit aggregate identifier keeps its leading zeros")
        void anElevenDigitAggregateIdentifierKeepsItsLeadingZeros() {
            UUID eventId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            Instant createdAt = Instant.parse("2026-01-02T03:04:05.123456Z");
            entityManager.persist(new OutboxEventEntity(
                    eventId, "CardUpdated", SYNTHETIC_ACCOUNT_ID, "{}", createdAt));
            entityManager.flush();
            entityManager.clear();

            OutboxEventEntity reread = entityManager.find(OutboxEventEntity.class, eventId);
            assertNotNull(reread, "the row just written");
            assertAll(
                    () -> assertSameSensitiveValue(
                            SYNTHETIC_ACCOUNT_ID, reread.getAggregateId(),
                            "the aggregate identifier differs"),
                    () -> assertEquals(PicClause.XREF_ACCT_ID_WIDTH,
                            reread.getAggregateId().length(),
                            "the width XREF-ACCT-ID PIC 9(11) fixes"),
                    () -> assertTrue(reread.getAggregateId().matches("^[0-9]{11}$"),
                            "eleven decimal digits"),
                    () -> assertEquals(createdAt, reread.getCreatedAt(),
                            "TIMESTAMP(6) WITH TIME ZONE holds microsecond precision"),
                    () -> assertFalse(reread.isPublished(), "a new row waits for the relay"));
        }

    }
}
