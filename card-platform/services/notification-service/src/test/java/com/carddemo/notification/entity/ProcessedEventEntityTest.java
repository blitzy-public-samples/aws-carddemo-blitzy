package com.carddemo.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Inheritance;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Shape tests for {@link ProcessedEventEntity}, the duplicate-delivery marker of the notification
 * service.
 *
 * <p>Every assertion reads annotations and declared members through reflection. No Spring context
 * starts, no container starts and no socket opens. {@code mvn test} therefore runs the class on a
 * machine with no database and no message broker.</p>
 *
 * <p>No COBOL provenance: no Common Business Oriented Language (COBOL) program and no copybook
 * declares an equivalent record. CardDemo detects no duplicate delivery at all. A second write of
 * one transaction reaches {@code PERFORM 9999-ABEND-PROGRAM} at {@code
 * app/cbl/CBTRN02C.cbl:L562-L579}, and every Customer Information Control System (CICS) file
 * definition at {@code app/csd/CARDDEMO.CSD:L3-L9} specifies {@code RECOVERY(NONE)} and {@code
 * JOURNAL(NO)}.
 *
 * <p>Three columns carry one marker, and the tests pin all three as a closed set. A fourth field
 * fails them, as does a change to either mapped type.
 *
 * <p>Three consumer groups share one table. The notification service reads
 * {@code transaction.posted}, {@code fraud.assessed}, and {@code customer.context-changed} under
 * separate groups, while each marker names the event and its consumed topic.</p>
 *
 * <p>Boundary: the mapped shape is under test here. Catalogue reads, repository queries and
 * listener transactions belong to their own test packages.</p>
 */
final class ProcessedEventEntityTest {

    /** The table one marker maps, from {@code src/main/resources/db/migration/V1__schema.sql}. */
    private static final String TABLE_NAME = "processed_event";

    /** Instance fields the class maps, statics excluded. */
    private static final int FIELD_COUNT = 3;

    /** The identifier field, holding the value the published event carried. */
    private static final String EVENT_ID_FIELD = "eventId";

    /** The field holding the moment processing finished. */
    private static final String PROCESSED_AT_FIELD = "processedAt";

    /** The field naming the topic the first delivery arrived on. */
    private static final String TOPIC_FIELD = "consumedTopic";

    /** Column of {@link #EVENT_ID_FIELD}, and the whole primary key. */
    private static final String EVENT_ID_COLUMN = "event_id";

    /** Column of {@link #PROCESSED_AT_FIELD}. */
    private static final String PROCESSED_AT_COLUMN = "processed_at";

    /** Column of {@link #TOPIC_FIELD}. */
    private static final String TOPIC_COLUMN = "consumed_topic";

    /** Constant the class publishes for the width of {@link #TOPIC_COLUMN}. */
    private static final String TOPIC_WIDTH_CONSTANT = "CONSUMED_TOPIC_MAX_LENGTH";

    /** The one index the migration creates, which a retention purge ranges over. */
    private static final String RETENTION_INDEX = "ix_processed_event_processed_at";

    /**
     * Names no field and no column of this table may take, in both spellings a Java author reaches
     * for. Each names a column a competent engineer might add to a marker table, and this table
     * takes none of them.
     */
    private static final List<String> FORBIDDEN_NAMES = List.of(
            "event_type", "eventType",
            "consumer_group", "consumerGroup",
            "aggregate_id", "aggregateId",
            "payload",
            "attempt_count", "attemptCount",
            "last_error", "lastError",
            "status",
            "created_at", "createdAt");

    /**
     * Fragments no field name and no column name of this table may contain, matched without regard
     * to case. They cover a card number, a card verification value and an account identifier, none
     * of which a marker carries.
     */
    private static final List<String> FORBIDDEN_FRAGMENTS =
            List.of("card", "verification", "account");

    /**
     * Lower-case fragments no declared method name may contain. A duplicate lookup is a repository
     * call the listener makes, and the entity answers no such question.
     */
    private static final List<String> DUPLICATE_LOOKUP_FRAGMENTS = List.of(
            "exists", "isprocessed", "wasprocessed", "hasprocessed", "alreadyprocessed",
            "isduplicate", "contains", "find");

    /** Annotations the class must not carry. Each one would place a marker in a hierarchy. */
    private static final List<Class<? extends Annotation>> FORBIDDEN_CLASS_ANNOTATIONS =
            List.of(Inheritance.class, DiscriminatorColumn.class, DiscriminatorValue.class);

    /** Annotations no instance field must carry. Each one would add a link or a version. */
    private static final List<Class<? extends Annotation>> FORBIDDEN_FIELD_ANNOTATIONS = List.of(
            Version.class, ManyToOne.class, OneToMany.class, OneToOne.class, ManyToMany.class,
            JoinColumn.class, JoinTable.class, Index.class);

    /**
     * Returns the instance fields the class declares, with synthetic and static members dropped.
     *
     * @return the mapped fields, in declaration order
     */
    private static List<Field> instanceFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : ProcessedEventEntity.class.getDeclaredFields()) {
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
     * Looks one instance field up by name.
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
        return fail("ProcessedEventEntity declares no instance field named " + name
                + ". It declares " + instanceFieldNames());
    }

    /**
     * Reads the column mapping of one field.
     *
     * @param field a mapped field of the entity
     * @return the column annotation the field carries
     */
    private static Column columnOf(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column == null) {
            return fail("field " + field.getName() + " carries no @Column, so an implicit naming "
                    + "strategy would decide its column name. It carries "
                    + annotationNames(field.getAnnotations()));
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
     * Reads the table mapping of the entity.
     *
     * @return the table annotation the class carries
     */
    private static Table table() {
        Table table = ProcessedEventEntity.class.getAnnotation(Table.class);
        if (table == null) {
            return fail("ProcessedEventEntity carries no @Table, so an implicit naming strategy "
                    + "would decide its table name. It carries "
                    + annotationNames(ProcessedEventEntity.class.getAnnotations()));
        }
        return table;
    }

    /**
     * Reads the published width constant of {@link #TOPIC_COLUMN}.
     *
     * @return the width the class publishes
     */
    private static int publishedTopicWidth() {
        try {
            Field constant = ProcessedEventEntity.class.getDeclaredField(TOPIC_WIDTH_CONSTANT);
            if (!Modifier.isStatic(constant.getModifiers())) {
                return fail(TOPIC_WIDTH_CONSTANT + " must be static so a caller can read the "
                        + "column width without an instance");
            }
            return constant.getInt(null);
        } catch (NoSuchFieldException | IllegalAccessException unreadable) {
            return fail("ProcessedEventEntity must publish a readable " + TOPIC_WIDTH_CONSTANT
                    + " naming the width of " + TOPIC_COLUMN, unreadable);
        }
    }

    /**
     * Returns the no-argument constructor the persistence provider calls.
     *
     * @return the declared constructor that takes no argument
     */
    private static Constructor<?> noArgumentConstructor() {
        try {
            return ProcessedEventEntity.class.getDeclaredConstructor();
        } catch (NoSuchMethodException absent) {
            return fail("ProcessedEventEntity must declare a no-argument constructor. Its "
                    + "constructors take " + constructorArities() + " arguments", absent);
        }
    }

    /**
     * Returns the parameter count of every declared constructor.
     *
     * @return one count per constructor
     */
    private static List<Integer> constructorArities() {
        List<Integer> arities = new ArrayList<>();
        for (Constructor<?> constructor : ProcessedEventEntity.class.getDeclaredConstructors()) {
            arities.add(constructor.getParameterCount());
        }
        return arities;
    }

    /**
     * Returns the names of the methods the class declares, with synthetic members dropped.
     *
     * @return one name per declared method
     */
    private static List<String> declaredMethodNames() {
        List<String> names = new ArrayList<>();
        for (Method method : ProcessedEventEntity.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            names.add(method.getName());
        }
        return names;
    }

    /**
     * Folds a name to lower case and drops its underscores, so {@code event_type} and
     * {@code eventType} fold to one form.
     *
     * @param name a field name or a column name
     * @return the folded form
     */
    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }

    /**
     * Renders the indexes a table declares into a failure message.
     *
     * @param table the table mapping of the entity
     * @return one entry per declared index, or {@code none} when the table declares no index
     */
    private static String indexDescriptions(Table table) {
        if (table.indexes().length == 0) {
            return "none";
        }
        List<String> descriptions = new ArrayList<>();
        for (Index index : table.indexes()) {
            descriptions.add(index.name() + " over (" + index.columnList() + ")");
        }
        return String.join(", ", descriptions);
    }

    /**
     * Renders annotation type names into a failure message.
     *
     * @param annotations annotations read from a class or a field
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

    /** Which table the class maps, and what the persistence provider needs of the class itself. */
    @Nested
    @DisplayName("Entity and table identity")
    class EntityIdentity {

        @Test
        @DisplayName("the class carries @Entity")
        void carriesTheEntityAnnotation() {
            assertNotNull(ProcessedEventEntity.class.getAnnotation(Entity.class),
                    "ProcessedEventEntity must carry @Entity. It carries "
                            + annotationNames(ProcessedEventEntity.class.getAnnotations()));
        }

        @Test
        @DisplayName("@Table names processed_event and hard-codes neither schema nor catalogue")
        void mapsTheTableNamedProcessedEvent() {
            Table table = table();

            assertAll("@Table attributes",
                    () -> assertEquals(TABLE_NAME, table.name(), "mapped table name"),
                    () -> assertTrue(table.schema().isEmpty(),
                            "@Table must name no schema, and it names '" + table.schema()
                                    + "'. Configuration supplies hibernate.default_schema"),
                    () -> assertTrue(table.catalog().isEmpty(),
                            "@Table must name no catalogue, and it names '" + table.catalog()
                                    + "'"));
        }

        @Test
        @DisplayName("the class is public, concrete and not final")
        void isAPublicNonFinalConcreteClass() {
            int modifiers = ProcessedEventEntity.class.getModifiers();

            assertAll("ProcessedEventEntity class modifiers",
                    () -> assertTrue(Modifier.isPublic(modifiers),
                            "ProcessedEventEntity must be public"),
                    () -> assertFalse(Modifier.isFinal(modifiers),
                            "ProcessedEventEntity must not be final: the persistence provider "
                                    + "subclasses an entity to proxy it"),
                    () -> assertFalse(Modifier.isAbstract(modifiers),
                            "ProcessedEventEntity must not be abstract"),
                    () -> assertFalse(ProcessedEventEntity.class.isInterface(),
                            "ProcessedEventEntity must be a class"),
                    () -> assertFalse(ProcessedEventEntity.class.isRecord(),
                            "ProcessedEventEntity must not be a record, whose fields are final"),
                    () -> assertFalse(ProcessedEventEntity.class.isEnum(),
                            "ProcessedEventEntity must not be an enum"));
        }

        @Test
        @DisplayName("a no-argument constructor the persistence provider can call")
        void declaresANoArgumentConstructorTheProviderCanUse() {
            Constructor<?> noArgument = noArgumentConstructor();

            assertFalse(Modifier.isPrivate(noArgument.getModifiers()),
                    "the no-argument constructor must not be private: the persistence provider "
                            + "calls it while materialising a row");
        }
    }

    /** The closed set of mapped fields, their types, their columns and their widths. */
    @Nested
    @DisplayName("The closed set of mapped fields")
    class MappedFields {

        @Test
        @DisplayName("three instance fields, no more and no fewer")
        void declaresExactlyThreeInstanceFields() {
            assertThat(instanceFieldNames())
                    .as("instance fields ProcessedEventEntity declares, statics dropped")
                    .hasSize(FIELD_COUNT);
        }

        @Test
        @DisplayName("the field names are eventId, processedAt and consumedTopic")
        void theFieldNamesAreTheClosedSet() {
            assertThat(instanceFieldNames()).as("instance field names")
                    .containsExactlyInAnyOrder(EVENT_ID_FIELD, PROCESSED_AT_FIELD, TOPIC_FIELD);
        }

        @Test
        @DisplayName("the declared types are UUID, Instant and String")
        void theDeclaredTypesMatchTheMappedColumns() {
            assertAll("declared field types",
                    () -> assertSame(UUID.class, instanceField(EVENT_ID_FIELD).getType(),
                            EVENT_ID_FIELD + " must be a java.util.UUID, the type "
                                    + "EventEnvelope.eventId carries"),
                    () -> assertSame(Instant.class, instanceField(PROCESSED_AT_FIELD).getType(),
                            PROCESSED_AT_FIELD + " must be a java.time.Instant, which maps to "
                                    + "TIMESTAMP(6) WITH TIME ZONE"),
                    () -> assertSame(String.class, instanceField(TOPIC_FIELD).getType(),
                            TOPIC_FIELD + " must be a java.lang.String, which maps to VARCHAR"));
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
                                + "entity maps a column"));
            }

            assertEquals(FIELD_COUNT * 2, checks.size(),
                    "two checks run against each of the " + FIELD_COUNT + " mapped fields");
            assertAll("column mapping of every instance field", checks);
        }

        @Test
        @DisplayName("the column names are event_id, processed_at and consumed_topic")
        void theColumnNamesAreTheClosedSet() {
            assertThat(columnNames()).as("column names the instance fields map")
                    .containsExactlyInAnyOrder(EVENT_ID_COLUMN, PROCESSED_AT_COLUMN, TOPIC_COLUMN);
        }

        @Test
        @DisplayName("event_id and processed_at refuse null, consumed_topic accepts it")
        void nullabilityMatchesTheMigration() {
            assertAll("declared nullability against V1__schema.sql",
                    () -> assertFalse(columnOf(instanceField(EVENT_ID_FIELD)).nullable(),
                            EVENT_ID_COLUMN + " is declared NOT NULL and carries the primary key"),
                    () -> assertFalse(columnOf(instanceField(PROCESSED_AT_FIELD)).nullable(),
                            PROCESSED_AT_COLUMN + " is declared NOT NULL"),
                    () -> assertTrue(columnOf(instanceField(TOPIC_FIELD)).nullable(),
                            TOPIC_COLUMN + " is declared without NOT NULL, and a mapping that "
                                    + "refuses null stops start-up under ddl-auto validate"));
        }

        @Test
        @DisplayName("the key column never changes and no column is a key of its own")
        void theKeyColumnIsFixedAndNoColumnIsUnique() {
            List<Executable> checks = new ArrayList<>();
            checks.add(() -> assertFalse(columnOf(instanceField(EVENT_ID_FIELD)).updatable(),
                    EVENT_ID_COLUMN + " must be declared not updatable: an update to the primary "
                            + "key would move the marker to another event"));

            for (Field field : instanceFields()) {
                checks.add(() -> assertFalse(columnOf(field).unique(),
                        columnOf(field).name() + " must declare no unique constraint of its own: "
                                + "the primary key is the only key of this table"));
            }

            assertAll("key and uniqueness of every column", checks);
        }

        @Test
        @DisplayName("consumed_topic is as wide as the constant the class publishes")
        void theTopicColumnWidthMatchesItsPublishedConstant() {
            int declared = columnOf(instanceField(TOPIC_FIELD)).length();

            assertEquals(publishedTopicWidth(), declared,
                    TOPIC_COLUMN + " maps a column whose width must equal "
                            + TOPIC_WIDTH_CONSTANT + ", the constant callers read before they "
                            + "hand a topic name over");
        }
    }

    /** The identifier: one column, supplied by the consumed event and generated by nothing. */
    @Nested
    @DisplayName("The identifier is the event identifier")
    class Identifier {

        @Test
        @DisplayName("exactly one field carries @Id, and it is eventId")
        void exactlyOneFieldCarriesIdAndItIsTheEventIdentifier() {
            List<String> identifierFields = new ArrayList<>();
            for (Field field : instanceFields()) {
                if (field.getAnnotation(Id.class) != null) {
                    identifierFields.add(field.getName());
                }
            }

            assertThat(identifierFields).as("fields carrying @Id")
                    .containsExactly(EVENT_ID_FIELD);
        }

        @Test
        @DisplayName("the identifier is a java.util.UUID mapping event_id")
        void theIdentifierIsAUuidMappingTheEventIdColumn() {
            Field identifier = instanceField(EVENT_ID_FIELD);

            assertAll("identifier field",
                    () -> assertSame(UUID.class, identifier.getType(),
                            EVENT_ID_FIELD + " must be a java.util.UUID. A String identifier "
                                    + "accepts a value the UUID column then refuses on insert"),
                    () -> assertEquals(EVENT_ID_COLUMN, columnOf(identifier).name(),
                            "column the identifier maps"));
        }

        @Test
        @DisplayName("no generation strategy: the value arrives with the event")
        void declaresNoGenerationStrategy() {
            List<Executable> checks = new ArrayList<>();
            checks.add(() -> assertNull(
                    ProcessedEventEntity.class.getAnnotation(GeneratedValue.class),
                    "the class must carry no @GeneratedValue"));
            checks.add(() -> assertNull(
                    ProcessedEventEntity.class.getAnnotation(SequenceGenerator.class),
                    "the class must carry no @SequenceGenerator"));

            for (Field field : instanceFields()) {
                checks.add(() -> assertNull(field.getAnnotation(GeneratedValue.class),
                        field.getName() + " must carry no @GeneratedValue: the publisher of the "
                                + "event assigns the identifier"));
                checks.add(() -> assertNull(field.getAnnotation(SequenceGenerator.class),
                        field.getName() + " must carry no @SequenceGenerator"));
            }

            assertAll("absent generation strategy", checks);
        }

        @Test
        @DisplayName("no composite key: one column carries the whole key")
        void declaresNoCompositeKey() {
            List<Executable> checks = new ArrayList<>();
            checks.add(() -> assertNull(ProcessedEventEntity.class.getAnnotation(IdClass.class),
                    "the class must carry no @IdClass: one column carries the whole key"));

            for (Field field : instanceFields()) {
                checks.add(() -> assertNull(field.getAnnotation(EmbeddedId.class),
                        field.getName() + " must carry no @EmbeddedId"));
            }

            assertAll("absent composite key", checks);
        }
    }

    /**
     * Additions this table refuses. Every entry names something a competent engineer would
     * reasonably add to a marker table, and the closed field set above already refuses each one.
     * The named checks report which addition arrived.
     */
    @Nested
    @DisplayName("Additions this marker table refuses")
    class RefusedAdditions {

        @Test
        @DisplayName("no forbidden column or field name appears, in either spelling")
        void noForbiddenNameAppears() {
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
                    "one check runs for each forbidden name");
            assertAll("forbidden names over field names and column names", checks);
        }

        @Test
        @DisplayName("no field name and no column name mentions a card or an account")
        void namesNoCardOrAccountAnywhere() {
            List<String> declared = new ArrayList<>(instanceFieldNames());
            declared.addAll(columnNames());

            List<Executable> checks = new ArrayList<>();
            for (String name : declared) {
                String folded = fold(name);
                for (String fragment : FORBIDDEN_FRAGMENTS) {
                    checks.add(() -> assertFalse(folded.contains(fragment),
                            "name '" + name + "' contains '" + fragment + "'. A marker carries an "
                                    + "event identifier, a moment and a topic name, and no "
                                    + "cardholder value"));
                }
            }

            assertEquals(declared.size() * FORBIDDEN_FRAGMENTS.size(), checks.size(),
                    "every fragment is checked against every declared name");
            assertAll("cardholder and account fragments over every declared name", checks);
        }

        @Test
        @DisplayName("one non-unique index over processed_at, and no second lookup key")
        void declaresNoSecondLookupKey() {
            Table table = table();

            assertEquals(0, table.uniqueConstraints().length,
                    "@Table must declare no unique constraint: the primary key is the only key");
            assertEquals(1, table.indexes().length,
                    "@Table must declare exactly the one index the migration creates, and it "
                            + "declares " + indexDescriptions(table));

            Index only = table.indexes()[0];

            assertAll("the one declared index",
                    () -> assertEquals(RETENTION_INDEX, only.name(),
                            "name of the one declared index"),
                    () -> assertEquals(PROCESSED_AT_COLUMN, only.columnList(),
                            "the one declared index covers " + PROCESSED_AT_COLUMN + " alone, "
                                    + "which a retention purge ranges over"),
                    () -> assertFalse(only.unique(),
                            "the one declared index must not be unique: a unique index over any "
                                    + "column would be a second key"));
        }

        @Test
        @DisplayName("no discriminator and no inheritance: every consumer group shares one table")
        void declaresNoDiscriminatorOrInheritance() {
            List<Executable> checks = new ArrayList<>();
            for (Class<? extends Annotation> forbidden : FORBIDDEN_CLASS_ANNOTATIONS) {
                checks.add(() -> assertNull(ProcessedEventEntity.class.getAnnotation(forbidden),
                        "the class must carry no @" + forbidden.getSimpleName()));
            }

            assertEquals(FORBIDDEN_CLASS_ANNOTATIONS.size(), checks.size(),
                    "one check runs for each forbidden class annotation");
            assertAll("forbidden class annotations", checks);
        }

        @Test
        @DisplayName("no relationship, no version and no field-level index")
        void declaresNoRelationshipVersionOrFieldIndex() {
            List<Executable> checks = new ArrayList<>();
            for (Field field : instanceFields()) {
                for (Class<? extends Annotation> forbidden : FORBIDDEN_FIELD_ANNOTATIONS) {
                    checks.add(() -> assertNull(field.getAnnotation(forbidden),
                            field.getName() + " must carry no @" + forbidden.getSimpleName()
                                    + ": one marker row stands alone and joins nothing"));
                }
            }

            assertEquals(FIELD_COUNT * FORBIDDEN_FIELD_ANNOTATIONS.size(), checks.size(),
                    "every forbidden annotation is checked against every mapped field");
            assertAll("forbidden field annotations", checks);
        }
    }

    /** What the entity exposes. A duplicate lookup is a repository call the listener makes. */
    @Nested
    @DisplayName("The entity answers no duplicate question")
    class MethodSurface {

        @Test
        @DisplayName("no declared method name suggests a duplicate lookup")
        void declaresNoDuplicateLookupMethod() {
            List<String> methodNames = declaredMethodNames();
            List<Executable> checks = new ArrayList<>();

            for (String methodName : methodNames) {
                String folded = fold(methodName);
                for (String fragment : DUPLICATE_LOOKUP_FRAGMENTS) {
                    checks.add(() -> assertFalse(folded.contains(fragment),
                            "method '" + methodName + "' contains '" + fragment + "'. "
                                    + "ProcessedEventRepository answers whether a marker exists, "
                                    + "and the entity holds the row"));
                }
            }

            assertFalse(methodNames.isEmpty(),
                    "ProcessedEventEntity must declare at least one method to scan");
            assertEquals(methodNames.size() * DUPLICATE_LOOKUP_FRAGMENTS.size(), checks.size(),
                    "every fragment is checked against every declared method name");
            assertAll("duplicate-lookup fragments over every declared method name", checks);
        }
    }
}
