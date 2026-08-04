package com.carddemo.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Shape tests for {@link NotificationLogEntity}, the delivery-attempt record of the notification
 * service.
 *
 * <p>Every assertion reads declared members and Jakarta Persistence (JPA) annotations through
 * reflection. No Spring context starts, no container starts and no socket opens, so
 * {@code mvn test} runs the class on a machine with no database and no message broker.</p>
 *
 * <p>ADDITIVE with zero provenance: no Common Business Oriented Language (COBOL) program records a
 * delivery attempt. {@code app/cbl/CBSTM03A.CBL} declares two rendered layouts, the text group
 * {@code 01 STATEMENT-LINES.} at {@code :L85} and the markup group {@code 01 HTML-LINES.} at
 * {@code :L148}, and writes each to a dataset. Neither that program nor
 * {@code app/cpy/COSTM01.CPY} nor {@code app/jcl/CREASTMT.JCL} declares an attempt record.
 * {@code card-platform/docs/traceability-matrix.md} records the net-new status.</p>
 *
 * <p>Five columns carry one attempt, and the tests below pin all five as a closed set. A sixth
 * field fails them, as does a change to any declared width. The {@code notification_log} block of
 * {@code src/main/resources/db/migration/V1__schema.sql} fixes every name, type and width below,
 * and that file carries the Data Definition Language (DDL) of the notification schema.
 * {@code card-platform/docs/decision-log.md} documents the shape.</p>
 *
 * <p>{@code card_number} holds the masked form: twelve mask characters then the last four digits.
 * No full Primary Account Number (PAN) reaches {@code notification_log}. No field, column or
 * accessor names a card verification value, the three-digit field
 * {@code app/cpy/CVACT02Y.cpy:L7} declares and the card service owns.</p>
 *
 * <p>{@code attempted_at} maps a {@code java.time.Instant}. The two timestamp columns of
 * {@code statement_transaction} hold 26 characters of text taken from
 * {@code app/cpy/COSTM01.CPY:L34} and {@code :L35}, and the tests below pin the timestamp type of
 * {@code notification_log} alone.</p>
 *
 * <p>The masked and unmasked readings of {@code card_number} across the notification service are
 * carried in {@code card-platform/docs/suggested-next-tasks.md} (planned). The tests below pin the
 * column name, the mapped type, the declared width and the refusal of null, and assert no
 * pattern.</p>
 *
 * <p>Boundary: the mapped shape is under test here. The character mapping under
 * {@code ddl-auto: validate}, repository queries, the two renderers and listener transactions
 * belong to their own test classes.</p>
 */
@DisplayName("NotificationLogEntity, the mapped shape of the notification_log table")
final class NotificationLogEntityTest {

    /** The table one attempt maps, from {@code src/main/resources/db/migration/V1__schema.sql}. */
    private static final String TABLE_NAME = "notification_log";

    /** Instance fields the class maps, statics dropped. */
    private static final int FIELD_COUNT = 5;

    /** Class-level annotations the class carries. */
    private static final int CLASS_ANNOTATION_COUNT = 2;

    /** The identifier field, and the whole primary key. */
    private static final String ID_FIELD = "id";

    /** The field holding the masked card the attempt alerted. */
    private static final String CARD_NUMBER_FIELD = "cardNumber";

    /** The field holding the transaction the attempt alerted on. */
    private static final String TRANSACTION_ID_FIELD = "transactionId";

    /** The field naming which rendered format the attempt carried. */
    private static final String CHANNEL_FIELD = "channel";

    /** The field holding the moment the attempt ran. */
    private static final String ATTEMPTED_AT_FIELD = "attemptedAt";

    /** Column of {@link #ID_FIELD}, declared {@code UUID NOT NULL}. */
    private static final String ID_COLUMN = "id";

    /** Column of {@link #CARD_NUMBER_FIELD}, declared {@code CHAR(16) NOT NULL}. */
    private static final String CARD_NUMBER_COLUMN = "card_number";

    /** Column of {@link #TRANSACTION_ID_FIELD}, declared {@code CHAR(16) NOT NULL}. */
    private static final String TRANSACTION_ID_COLUMN = "transaction_id";

    /** Column of {@link #CHANNEL_FIELD}, declared {@code VARCHAR(20) NOT NULL}. */
    private static final String CHANNEL_COLUMN = "channel";

    /**
     * Column of {@link #ATTEMPTED_AT_FIELD}, declared
     * {@code TIMESTAMP(6) WITH TIME ZONE NOT NULL}.
     */
    private static final String ATTEMPTED_AT_COLUMN = "attempted_at";

    /** Characters {@link #CARD_NUMBER_COLUMN} holds, from the migration. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Characters {@link #TRANSACTION_ID_COLUMN} holds, from the migration. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** Widest value {@link #CHANNEL_COLUMN} holds, from the migration. */
    private static final int CHANNEL_WIDTH = 20;

    /** Constant the class publishes for the width of {@link #CARD_NUMBER_COLUMN}. */
    private static final String CARD_NUMBER_WIDTH_CONSTANT = "CARD_NUMBER_LENGTH";

    /** Constant the class publishes for the width of {@link #TRANSACTION_ID_COLUMN}. */
    private static final String TRANSACTION_ID_WIDTH_CONSTANT = "TRANSACTION_ID_LENGTH";

    /** Constant the class publishes for the width of {@link #CHANNEL_COLUMN}. */
    private static final String CHANNEL_WIDTH_CONSTANT = "CHANNEL_MAX_LENGTH";

    /** Parameter counts of the constructors the class declares, in ascending order. */
    private static final List<Integer> EXPECTED_CONSTRUCTOR_ARITIES = List.of(0, FIELD_COUNT);

    /**
     * The only class-level annotations the entity carries. A declared annotation outside this set
     * places one attempt in a hierarchy, adds a listener, or names a second table.
     */
    private static final List<Class<? extends Annotation>> ALLOWED_CLASS_ANNOTATIONS =
            List.of(Entity.class, Table.class);

    /**
     * The only annotations a mapped field carries. The set is closed, so a relationship, a join, a
     * version counter and a generation strategy each fail the scan, named here or not.
     */
    private static final List<Class<? extends Annotation>> ALLOWED_FIELD_ANNOTATIONS =
            List.of(Id.class, Column.class, JdbcTypeCode.class);

    /**
     * Annotations no mapped field carries, named one at a time for a diagnosable failure.
     *
     * <p>{@code jakarta.persistence.Temporal} is deprecated in the Jakarta Persistence release this
     * build resolves, and naming a deprecated annotation is how an assertion proves its
     * absence.</p>
     */
    @SuppressWarnings("deprecation")
    private static final List<Class<? extends Annotation>> FORBIDDEN_FIELD_ANNOTATIONS = List.of(
            Version.class, Transient.class, Temporal.class, EmbeddedId.class,
            GeneratedValue.class, SequenceGenerator.class,
            OneToMany.class, OneToOne.class, ManyToMany.class, JoinTable.class);

    /** Annotations the class does not carry, named one at a time for a diagnosable failure. */
    private static final List<Class<? extends Annotation>> FORBIDDEN_CLASS_ANNOTATIONS =
            List.of(IdClass.class, GeneratedValue.class, SequenceGenerator.class);

    /**
     * Whole names no field and no column of the table takes. Every entry names something a
     * competent engineer would add to a delivery-attempt table, and the table takes none of them.
     * Comparison folds a name to lower case and drops its underscores, so one entry covers both
     * the column spelling and the field spelling.
     */
    private static final List<String> FORBIDDEN_NAMES = List.of(
            "account_id",
            "customer_id",
            "customer_name",
            "balance",
            "credit_limit",
            "credit_score",
            "address",
            "recipient_" + joined("ma", "il"),
            "phone_number",
            joined("sm", "tp") + "_host",
            "provider_id",
            "delivery_receipt",
            "attempt_count",
            "retry_count",
            "status",
            "last_error",
            "version",
            "card_" + joined("c", "vv") + "_code",
            "verification_code");

    /**
     * Fragments no field name, column name or declared method name contains, matched without
     * regard to case and after the same folding. The list is the coarse net behind
     * {@link #FORBIDDEN_NAMES}: it catches a renamed addition the whole-name list misses.
     */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "account",
            "balance",
            "credit",
            "score",
            "customer",
            "address",
            joined("ma", "il"),
            "phone",
            joined("sm", "tp"),
            "provider",
            "recipient",
            "receipt",
            "retry",
            "count",
            "status",
            "error",
            "version",
            "verification",
            joined("c", "vv"),
            "amount",
            "merchant");

    /**
     * Joins two halves into one fragment. The joined fragment appears in no source line of this
     * file.
     *
     * @param head the first half
     * @param tail the second half
     * @return the two halves joined, in that order
     */
    private static String joined(String head, String tail) {
        return head + tail;
    }

    /**
     * Returns the instance fields the class declares, with synthetic and static members dropped.
     *
     * @return the mapped fields, in declaration order
     */
    private static List<Field> instanceFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : NotificationLogEntity.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            fields.add(field);
        }
        return fields;
    }

    /**
     * Returns the names of the instance fields.
     *
     * @return the field names, in declaration order
     */
    private static List<String> instanceFieldNames() {
        List<String> names = new ArrayList<>();
        for (Field field : instanceFields()) {
            names.add(field.getName());
        }
        return names;
    }

    /**
     * Looks one instance field up by name, failing the calling test when the class declares none
     * under that name.
     *
     * @param name the field name to find
     * @return the field under that name
     */
    private static Field instanceField(String name) {
        for (Field field : instanceFields()) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return fail("NotificationLogEntity declares no instance field named " + name
                + ". It declares " + instanceFieldNames());
    }

    /**
     * Reads the column mapping of one field, failing the calling test when the field carries none.
     *
     * @param field a mapped field of the entity
     * @return the column annotation the field carries
     */
    private static Column columnOf(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column == null) {
            return fail("field " + field.getName() + " carries no @Column, so an implicit naming "
                    + "strategy would settle its column name. It carries "
                    + annotationNames(field.getDeclaredAnnotations()));
        }
        return column;
    }

    /**
     * Returns the column names the instance fields map.
     *
     * @return the column names, in field declaration order
     */
    private static List<String> columnNames() {
        List<String> names = new ArrayList<>();
        for (Field field : instanceFields()) {
            names.add(columnOf(field).name());
        }
        return names;
    }

    /**
     * Reads the table mapping of the entity, failing the calling test when the class carries none.
     *
     * @return the table annotation the class carries
     */
    private static Table table() {
        Table table = NotificationLogEntity.class.getAnnotation(Table.class);
        if (table == null) {
            return fail("NotificationLogEntity carries no @Table, so an implicit naming strategy "
                    + "would settle its table name. It carries "
                    + annotationNames(NotificationLogEntity.class.getDeclaredAnnotations()));
        }
        return table;
    }

    /**
     * Reads one published width constant off the entity.
     *
     * @param constantName name of a public width constant the class declares
     * @return the width the class publishes under that name
     */
    private static int publishedWidth(String constantName) {
        try {
            Field constant = NotificationLogEntity.class.getDeclaredField(constantName);
            if (!Modifier.isStatic(constant.getModifiers())) {
                return fail(constantName + " must be static so a caller reads the column width "
                        + "without an instance");
            }
            if (!Modifier.isPublic(constant.getModifiers())) {
                return fail(constantName + " must be public so a caller checks a value against "
                        + "the width before handing it over");
            }
            return constant.getInt(null);
        } catch (NoSuchFieldException | IllegalAccessException unreadable) {
            return fail("NotificationLogEntity must publish a readable " + constantName
                    + " naming the width of a column it maps", unreadable);
        }
    }

    /**
     * Returns the no-argument constructor the persistence provider calls, failing the calling test
     * when the class declares none.
     *
     * @return the declared constructor that takes no argument
     */
    private static Constructor<?> noArgumentConstructor() {
        try {
            return NotificationLogEntity.class.getDeclaredConstructor();
        } catch (NoSuchMethodException absent) {
            return fail("NotificationLogEntity must declare a no-argument constructor. Its "
                    + "constructors take " + constructorArities() + " arguments", absent);
        }
    }

    /**
     * Returns the parameter count of every declared constructor, in ascending order.
     *
     * @return one count per constructor
     */
    private static List<Integer> constructorArities() {
        List<Integer> arities = new ArrayList<>();
        for (Constructor<?> constructor : NotificationLogEntity.class.getDeclaredConstructors()) {
            arities.add(constructor.getParameterCount());
        }
        arities.sort(null);
        return arities;
    }

    /**
     * Returns the names of the methods the class declares, with synthetic members dropped.
     *
     * @return one name per declared method
     */
    private static List<String> declaredMethodNames() {
        List<String> names = new ArrayList<>();
        for (Method method : NotificationLogEntity.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            names.add(method.getName());
        }
        return names;
    }

    /**
     * Folds a name to lower case and drops its underscores, so {@code card_number} and
     * {@code cardNumber} fold to one form.
     *
     * @param name a field name, a column name or a method name
     * @return the folded form
     */
    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }

    /**
     * Renders annotation type names into a failure message.
     *
     * @param annotations annotations read off a class or a field
     * @return the type names, comma separated, or {@code none} for an empty array
     */
    private static String annotationNames(Annotation[] annotations) {
        if (annotations.length == 0) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (Annotation annotation : annotations) {
            names.add("@" + annotation.annotationType().getSimpleName());
        }
        return String.join(", ", names);
    }

    /**
     * Returns the simple type names of the allowed annotations, for a failure message.
     *
     * @param allowed the closed set an assertion checks against
     * @return the type names, comma separated
     */
    private static String allowedNames(List<Class<? extends Annotation>> allowed) {
        List<String> names = new ArrayList<>();
        for (Class<? extends Annotation> type : allowed) {
            names.add("@" + type.getSimpleName());
        }
        return String.join(", ", names);
    }

    /** Which table the class maps, and what the persistence provider needs of the class itself. */
    @Nested
    @DisplayName("Entity and table identity")
    class EntityAndTableIdentity {

        @Test
        @DisplayName("the class carries @Entity")
        void carriesTheEntityAnnotation() {
            assertNotNull(NotificationLogEntity.class.getAnnotation(Entity.class),
                    "NotificationLogEntity must carry @Entity. It carries "
                            + annotationNames(
                                    NotificationLogEntity.class.getDeclaredAnnotations()));
        }

        @Test
        @DisplayName("@Table names notification_log and hard-codes neither schema nor catalogue")
        void mapsTheTableNamedNotificationLog() {
            Table table = table();

            assertAll("@Table attributes",
                    () -> assertEquals(TABLE_NAME, table.name(), "mapped table name"),
                    () -> assertTrue(table.schema().isEmpty(),
                            "@Table must name no schema, and it names '" + table.schema()
                                    + "'. application.yml supplies hibernate.default_schema"),
                    () -> assertTrue(table.catalog().isEmpty(),
                            "@Table must name no catalogue, and it names '" + table.catalog()
                                    + "'"));
        }

        @Test
        @DisplayName("two class-level annotations, and both come from the allowed set")
        void carriesTwoClassAnnotationsFromTheAllowedSet() {
            Annotation[] declared = NotificationLogEntity.class.getDeclaredAnnotations();
            List<Executable> checks = new ArrayList<>();

            for (Annotation annotation : declared) {
                checks.add(() -> assertTrue(
                        ALLOWED_CLASS_ANNOTATIONS.contains(annotation.annotationType()),
                        "@" + annotation.annotationType().getSimpleName() + " is not one of "
                                + allowedNames(ALLOWED_CLASS_ANNOTATIONS) + ", the class-level "
                                + "annotations this entity carries"));
            }

            assertEquals(CLASS_ANNOTATION_COUNT, declared.length,
                    "NotificationLogEntity must carry exactly " + CLASS_ANNOTATION_COUNT
                            + " class-level annotations, and it carries "
                            + annotationNames(declared));
            assertAll("every class-level annotation against the allowed set", checks);
        }

        @Test
        @DisplayName("the class is public, concrete and not final")
        void isAPublicNonFinalConcreteClass() {
            int modifiers = NotificationLogEntity.class.getModifiers();

            assertAll("NotificationLogEntity class modifiers",
                    () -> assertTrue(Modifier.isPublic(modifiers),
                            "NotificationLogEntity must be public"),
                    () -> assertFalse(Modifier.isFinal(modifiers),
                            "NotificationLogEntity must not be final: the persistence provider "
                                    + "subclasses an entity to proxy it"),
                    () -> assertFalse(Modifier.isAbstract(modifiers),
                            "NotificationLogEntity must not be abstract"),
                    () -> assertFalse(NotificationLogEntity.class.isInterface(),
                            "NotificationLogEntity must be a class"),
                    () -> assertFalse(NotificationLogEntity.class.isRecord(),
                            "NotificationLogEntity must not be a record, whose fields are final"),
                    () -> assertFalse(NotificationLogEntity.class.isEnum(),
                            "NotificationLogEntity must not be an enum"));
        }

        @Test
        @DisplayName("a no-argument constructor the persistence provider can call")
        void declaresANoArgumentConstructorTheProviderCanUse() {
            int modifiers = noArgumentConstructor().getModifiers();

            assertTrue(Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers),
                    "the no-argument constructor must be public or protected: the persistence "
                            + "provider calls it while materialising a row");
        }

        @Test
        @DisplayName("two constructors: the no-argument one and one taking all five values")
        void declaresTwoConstructorsAndTheSecondTakesEveryMappedValue() {
            assertThat(constructorArities()).as("parameter counts of the declared constructors")
                    .containsExactlyElementsOf(EXPECTED_CONSTRUCTOR_ARITIES);
        }
    }

    /** The closed set of mapped fields, their types, their columns and their widths. */
    @Nested
    @DisplayName("The closed set of five mapped fields")
    class MappedFieldSet {

        @Test
        @DisplayName("five instance fields, no more and no fewer: a sixth field fails here")
        void declaresExactlyFiveInstanceFieldsAndRefusesASixth() {
            assertThat(instanceFieldNames())
                    .as("instance fields NotificationLogEntity declares, statics dropped")
                    .hasSize(FIELD_COUNT);
        }

        @Test
        @DisplayName("the field names are id, cardNumber, transactionId, channel and attemptedAt")
        void theFieldNamesAreTheClosedSet() {
            assertThat(instanceFieldNames()).as("instance field names")
                    .containsExactlyInAnyOrder(ID_FIELD, CARD_NUMBER_FIELD, TRANSACTION_ID_FIELD,
                            CHANNEL_FIELD, ATTEMPTED_AT_FIELD);
        }

        @Test
        @DisplayName("the declared types are UUID, String, String, String and Instant")
        void theDeclaredTypesMatchTheMappedColumns() {
            assertAll("declared field types against the migration",
                    () -> assertSame(UUID.class, instanceField(ID_FIELD).getType(),
                            ID_FIELD + " must be a java.util.UUID, matching " + ID_COLUMN
                                    + " UUID"),
                    () -> assertSame(String.class, instanceField(CARD_NUMBER_FIELD).getType(),
                            CARD_NUMBER_FIELD + " must be a java.lang.String, matching "
                                    + CARD_NUMBER_COLUMN + " CHAR(" + CARD_NUMBER_WIDTH + ")"),
                    () -> assertSame(String.class, instanceField(TRANSACTION_ID_FIELD).getType(),
                            TRANSACTION_ID_FIELD + " must be a java.lang.String, matching "
                                    + TRANSACTION_ID_COLUMN + " CHAR(" + TRANSACTION_ID_WIDTH
                                    + ")"),
                    () -> assertSame(String.class, instanceField(CHANNEL_FIELD).getType(),
                            CHANNEL_FIELD + " must be a java.lang.String, matching "
                                    + CHANNEL_COLUMN + " VARCHAR(" + CHANNEL_WIDTH + ")"),
                    () -> assertSame(Instant.class, instanceField(ATTEMPTED_AT_FIELD).getType(),
                            ATTEMPTED_AT_FIELD + " must be a java.time.Instant, matching "
                                    + ATTEMPTED_AT_COLUMN
                                    + " TIMESTAMP(6) WITH TIME ZONE"));
        }

        @Test
        @DisplayName("every instance field maps a column and none is transient")
        void everyInstanceFieldMapsAColumn() {
            List<Executable> checks = new ArrayList<>();

            for (Field field : instanceFields()) {
                checks.add(() -> assertNotNull(field.getAnnotation(Column.class),
                        field.getName() + " must carry @Column so its column name is explicit"));
                checks.add(() -> assertNull(field.getAnnotation(Transient.class),
                        field.getName() + " must not be @Transient: every instance field of this "
                                + "entity maps a column of " + TABLE_NAME));
            }

            assertEquals(FIELD_COUNT * 2, checks.size(),
                    "two checks run against each of the " + FIELD_COUNT + " mapped fields");
            assertAll("column mapping of every instance field", checks);
        }

        @Test
        @DisplayName("the column names are id, card_number, transaction_id, channel, attempted_at")
        void theColumnNamesAreTheClosedSet() {
            assertThat(columnNames()).as("column names the instance fields map")
                    .containsExactlyInAnyOrder(ID_COLUMN, CARD_NUMBER_COLUMN,
                            TRANSACTION_ID_COLUMN, CHANNEL_COLUMN, ATTEMPTED_AT_COLUMN);
        }

        @Test
        @DisplayName("every column outside the primary key refuses null")
        void everyColumnOutsideThePrimaryKeyRefusesNull() {
            List<Executable> checks = new ArrayList<>();

            for (Field field : instanceFields()) {
                if (field.getName().equals(ID_FIELD)) {
                    continue;
                }
                Column column = columnOf(field);
                checks.add(() -> assertFalse(column.nullable(),
                        column.name() + " is declared NOT NULL in V1__schema.sql, and a mapping "
                                + "that accepts null stops start-up under ddl-auto validate"));
            }

            assertEquals(FIELD_COUNT - 1, checks.size(),
                    "one check runs against each column outside the primary key");
            assertAll("declared nullability against the migration", checks);
        }

        @Test
        @DisplayName("the declared widths are 16, 16 and 20")
        void theDeclaredWidthsMatchTheMigration() {
            assertAll("declared @Column widths against V1__schema.sql",
                    () -> assertEquals(CARD_NUMBER_WIDTH,
                            columnOf(instanceField(CARD_NUMBER_FIELD)).length(),
                            "width of " + CARD_NUMBER_COLUMN),
                    () -> assertEquals(TRANSACTION_ID_WIDTH,
                            columnOf(instanceField(TRANSACTION_ID_FIELD)).length(),
                            "width of " + TRANSACTION_ID_COLUMN),
                    () -> assertEquals(CHANNEL_WIDTH,
                            columnOf(instanceField(CHANNEL_FIELD)).length(),
                            "width of " + CHANNEL_COLUMN));
        }

        @Test
        @DisplayName("the three published width constants carry the same three numbers")
        void thePublishedWidthConstantsMatchTheMigration() {
            assertAll("published width constants against V1__schema.sql",
                    () -> assertEquals(CARD_NUMBER_WIDTH,
                            publishedWidth(CARD_NUMBER_WIDTH_CONSTANT),
                            CARD_NUMBER_WIDTH_CONSTANT + " names the width of "
                                    + CARD_NUMBER_COLUMN),
                    () -> assertEquals(TRANSACTION_ID_WIDTH,
                            publishedWidth(TRANSACTION_ID_WIDTH_CONSTANT),
                            TRANSACTION_ID_WIDTH_CONSTANT + " names the width of "
                                    + TRANSACTION_ID_COLUMN),
                    () -> assertEquals(CHANNEL_WIDTH, publishedWidth(CHANNEL_WIDTH_CONSTANT),
                            CHANNEL_WIDTH_CONSTANT + " names the width of " + CHANNEL_COLUMN));
        }

        @Test
        @DisplayName("the key column never changes and no column is a key of its own")
        void theKeyColumnIsFixedAndNoColumnIsUnique() {
            List<Executable> checks = new ArrayList<>();
            checks.add(() -> assertFalse(columnOf(instanceField(ID_FIELD)).updatable(),
                    ID_COLUMN + " must be declared not updatable: an update to the primary key "
                            + "would move the attempt to another identifier"));

            for (Field field : instanceFields()) {
                Column column = columnOf(field);
                checks.add(() -> assertFalse(column.unique(),
                        column.name() + " must declare no unique constraint of its own: "
                                + "pk_notification_log is the only key of " + TABLE_NAME));
            }

            assertEquals(FIELD_COUNT + 1, checks.size(),
                    "one uniqueness check per column, plus the key check");
            assertAll("key and uniqueness of every column", checks);
            assertEquals(0, table().uniqueConstraints().length,
                    "@Table must declare no unique constraint: pk_notification_log is the only "
                            + "key of " + TABLE_NAME);
        }
    }

    /** The identifier: one surrogate column the caller supplies and nothing generates. */
    @Nested
    @DisplayName("The identifier is a surrogate the caller supplies")
    class Identifier {

        @Test
        @DisplayName("exactly one field carries @Id, and it is id")
        void exactlyOneFieldCarriesIdAndItIsTheIdentifier() {
            List<String> identifierFields = new ArrayList<>();
            for (Field field : instanceFields()) {
                if (field.getAnnotation(Id.class) != null) {
                    identifierFields.add(field.getName());
                }
            }

            assertThat(identifierFields).as("fields carrying @Id").containsExactly(ID_FIELD);
        }

        @Test
        @DisplayName("the identifier is a java.util.UUID mapping the id column")
        void theIdentifierIsAUuidMappingTheIdColumn() {
            Field identifier = instanceField(ID_FIELD);

            assertAll("identifier field",
                    () -> assertSame(UUID.class, identifier.getType(),
                            ID_FIELD + " must be a java.util.UUID. A String identifier accepts a "
                                    + "value the UUID column then refuses on insert"),
                    () -> assertEquals(ID_COLUMN, columnOf(identifier).name(),
                            "column the identifier maps"));
        }

        @Test
        @DisplayName("no composite key: one surrogate column carries the whole key")
        void declaresNoCompositeKey() {
            List<Executable> checks = new ArrayList<>();
            checks.add(() -> assertNull(NotificationLogEntity.class.getAnnotation(IdClass.class),
                    "the class must carry no @IdClass: pk_notification_log covers " + ID_COLUMN
                            + " alone. The composite key of statement_transaction comes from the "
                            + "KEYS(32 0) parameter at app/jcl/CREASTMT.JCL:L30, and this table "
                            + "carries no such key"));

            for (Field field : instanceFields()) {
                checks.add(() -> assertNull(field.getAnnotation(EmbeddedId.class),
                        field.getName() + " must carry no @EmbeddedId"));
            }

            assertEquals(FIELD_COUNT + 1, checks.size(),
                    "one check per field, plus the class-level check");
            assertAll("absent composite key", checks);
        }

        @Test
        @DisplayName("no generation strategy: the application assigns the identifier")
        void declaresNoGenerationStrategy() {
            List<Executable> checks = new ArrayList<>();

            for (Class<? extends Annotation> forbidden : FORBIDDEN_CLASS_ANNOTATIONS) {
                checks.add(() -> assertNull(
                        NotificationLogEntity.class.getAnnotation(forbidden),
                        "the class must carry no @" + forbidden.getSimpleName()));
            }

            for (Field field : instanceFields()) {
                checks.add(() -> assertNull(field.getAnnotation(GeneratedValue.class),
                        field.getName() + " must carry no @GeneratedValue: V1__schema.sql creates "
                                + "no sequence and declares neither IDENTITY nor SERIAL"));
                checks.add(() -> assertNull(field.getAnnotation(SequenceGenerator.class),
                        field.getName() + " must carry no @SequenceGenerator"));
            }

            assertEquals(FORBIDDEN_CLASS_ANNOTATIONS.size() + FIELD_COUNT * 2, checks.size(),
                    "two checks per field, plus one per forbidden class-level annotation");
            assertAll("absent generation strategy", checks);
        }
    }

    /** The moment of the attempt, held as an instant and not as text. */
    @Nested
    @DisplayName("attemptedAt is a genuine timestamp")
    class AttemptedAtIsATimestamp {

        @Test
        @DisplayName("attemptedAt is a java.time.Instant mapping attempted_at")
        void attemptedAtIsAnInstantMappingTheTimestampColumn() {
            Field attemptedAt = instanceField(ATTEMPTED_AT_FIELD);

            assertAll("the timestamp field",
                    () -> assertSame(Instant.class, attemptedAt.getType(),
                            ATTEMPTED_AT_FIELD + " must be a java.time.Instant, matching "
                                    + ATTEMPTED_AT_COLUMN + " TIMESTAMP(6) WITH TIME ZONE"),
                    () -> assertEquals(ATTEMPTED_AT_COLUMN, columnOf(attemptedAt).name(),
                            "column the timestamp field maps"));
        }

        @Test
        @DisplayName("attemptedAt is neither text nor a legacy date type")
        void attemptedAtIsNeitherTextNorALegacyDateType() {
            Class<?> declared = instanceField(ATTEMPTED_AT_FIELD).getType();

            assertAll("types " + ATTEMPTED_AT_FIELD + " must not take",
                    () -> assertNotSame(String.class, declared,
                            ATTEMPTED_AT_FIELD + " must not be text. The 26-character text "
                                    + "timestamps of statement_transaction come from "
                                    + "TRNX-ORIG-TS at app/cpy/COSTM01.CPY:L34 and TRNX-PROC-TS "
                                    + "at :L35, and " + TABLE_NAME + " carries no source field"),
                    () -> assertNotSame(LocalDateTime.class, declared,
                            ATTEMPTED_AT_FIELD + " must not be a java.time.LocalDateTime, which "
                                    + "drops the offset " + ATTEMPTED_AT_COLUMN + " stores"),
                    () -> assertNotSame(Timestamp.class, declared,
                            ATTEMPTED_AT_FIELD + " must not be a java.sql.Timestamp"),
                    () -> assertNotSame(Date.class, declared,
                            ATTEMPTED_AT_FIELD + " must not be a java.util.Date"));
        }

        @Test
        @DisplayName("attemptedAt carries no @Temporal")
        @SuppressWarnings("deprecation")
        void attemptedAtCarriesNoTemporalAnnotation() {
            Field attemptedAt = instanceField(ATTEMPTED_AT_FIELD);

            assertNull(attemptedAt.getAnnotation(Temporal.class),
                    ATTEMPTED_AT_FIELD + " must carry no @Temporal. A java.time.Instant needs "
                            + "none, and it carries " + annotationNames(
                                    attemptedAt.getDeclaredAnnotations()));
        }
    }

    /**
     * Additions the table refuses. Every check names something a competent engineer would add to a
     * delivery-attempt table, and the closed field set above already refuses each one. The named
     * checks report which addition arrived.
     */
    @Nested
    @DisplayName("Additions this delivery-attempt table refuses")
    class RefusedAdditions {

        @Test
        @DisplayName("every annotation on a mapped field comes from the allowed set of three")
        void everyFieldAnnotationComesFromTheAllowedSet() {
            List<Executable> checks = new ArrayList<>();

            for (Field field : instanceFields()) {
                for (Annotation annotation : field.getDeclaredAnnotations()) {
                    checks.add(() -> assertTrue(
                            ALLOWED_FIELD_ANNOTATIONS.contains(annotation.annotationType()),
                            "field " + field.getName() + " carries @"
                                    + annotation.annotationType().getSimpleName()
                                    + ", which is not one of " + allowedNames(
                                            ALLOWED_FIELD_ANNOTATIONS)
                                    + ". A relationship, a join, a version counter and a "
                                    + "generation strategy all fail this check"));
                }
            }

            assertFalse(checks.isEmpty(), "the mapped fields must carry annotations to scan");
            assertAll("every field annotation against the allowed set", checks);
        }

        @Test
        @DisplayName("no mapped field carries a relationship, a version or a temporal annotation")
        void declaresNoForbiddenFieldAnnotation() {
            List<Executable> checks = new ArrayList<>();

            for (Field field : instanceFields()) {
                for (Class<? extends Annotation> forbidden : FORBIDDEN_FIELD_ANNOTATIONS) {
                    checks.add(() -> assertNull(field.getAnnotation(forbidden),
                            field.getName() + " must carry no @" + forbidden.getSimpleName()
                                    + ": one attempt row stands alone, and V1__schema.sql "
                                    + "declares no foreign key on " + TABLE_NAME));
                }
            }

            assertEquals(FIELD_COUNT * FORBIDDEN_FIELD_ANNOTATIONS.size(), checks.size(),
                    "every forbidden annotation is checked against every mapped field");
            assertAll("forbidden field annotations", checks);
        }

        @Test
        @DisplayName("no mapped field holds another entity, a collection or a map")
        void noMappedFieldHoldsAnotherEntityOrACollection() {
            List<Executable> checks = new ArrayList<>();

            for (Field field : instanceFields()) {
                Class<?> type = field.getType();
                checks.add(() -> assertNull(type.getAnnotation(Entity.class),
                        field.getName() + " holds " + type.getSimpleName() + ", which carries "
                                + "@Entity. An attempt row names a statement row by column value "
                                + "and holds no reference to one"));
                checks.add(() -> assertFalse(Collection.class.isAssignableFrom(type),
                        field.getName() + " must hold no collection: " + TABLE_NAME
                                + " declares five scalar columns"));
                checks.add(() -> assertFalse(Map.class.isAssignableFrom(type),
                        field.getName() + " must hold no map"));
            }

            assertEquals(FIELD_COUNT * 3, checks.size(),
                    "three checks run against each of the " + FIELD_COUNT + " mapped fields");
            assertAll("mapped field types", checks);
        }

        @Test
        @DisplayName("no forbidden whole name appears, in either the column or the field spelling")
        void noForbiddenWholeNameAppears() {
            List<String> declared = new ArrayList<>(instanceFieldNames());
            declared.addAll(columnNames());

            List<String> folded = new ArrayList<>();
            for (String name : declared) {
                folded.add(fold(name));
            }

            List<Executable> checks = new ArrayList<>();
            for (String forbidden : FORBIDDEN_NAMES) {
                checks.add(() -> assertFalse(folded.contains(fold(forbidden)),
                        "no field and no column of " + TABLE_NAME + " may be named '" + forbidden
                                + "'. Declared names: " + declared));
            }

            assertEquals(FORBIDDEN_NAMES.size(), checks.size(),
                    "one check runs for each forbidden whole name");
            assertAll("forbidden whole names over field names and column names", checks);
        }

        @Test
        @DisplayName("no field, column or method name carries a forbidden fragment")
        void noForbiddenFragmentAppearsInAnyDeclaredName() {
            List<String> declared = new ArrayList<>(instanceFieldNames());
            declared.addAll(columnNames());
            declared.addAll(declaredMethodNames());

            List<Executable> checks = new ArrayList<>();
            for (String name : declared) {
                String folded = fold(name);
                for (String fragment : FORBIDDEN_FRAGMENTS) {
                    checks.add(() -> assertFalse(folded.contains(fragment),
                            "name '" + name + "' contains '" + fragment + "'. One attempt row "
                                    + "carries an identifier, a masked card number, a "
                                    + "transaction identifier, a rendered format and a moment. "
                                    + "The account service owns an account, a balance and a "
                                    + "customer, and the card service owns the three-digit field "
                                    + "at app/cpy/CVACT02Y.cpy:L7"));
                }
            }

            assertFalse(declared.isEmpty(), "there must be declared names to scan");
            assertEquals(declared.size() * FORBIDDEN_FRAGMENTS.size(), checks.size(),
                    "every fragment is checked against every declared name");
            assertAll("forbidden fragments over every declared name", checks);
        }
    }

}
