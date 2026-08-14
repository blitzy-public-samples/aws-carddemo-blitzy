package com.carddemo.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.fail;

import com.carddemo.cobol.PanMasker;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Shape tests for {@link StatementTransactionEntity}, the card-keyed read model of the notification
 * service.
 *
 * <p>Every assertion reads declared members and Jakarta Persistence (JPA) annotations through
 * reflection. No Spring context starts, no container starts and no socket opens. {@code mvn test}
 * therefore runs the class on a machine with no database and no message broker.</p>
 *
 * <p>Fourteen columns map the thirteen fields of {@code 01 TRNX-RECORD.} at {@code
 * app/cpy/COSTM01.CPY:L20}, a copybook of the Common Business Oriented Language (COBOL) source. The
 * count differs by one because {@code TRNX-CARD-NUM PIC X(16)} becomes two columns: a card token of
 * {@code PanMasker.CARD_TOKEN_LENGTH} characters that keys a row, and a masked display value at the
 * source width. Two source constructs carry no column: the group {@code 05 TRNX-REST.} at {@code
 * app/cpy/COSTM01.CPY:L24}, and the trailing {@code FILLER PIC X(20)} at {@code
 * app/cpy/COSTM01.CPY:L36}. The 350-byte record width comes from {@code RECORDSIZE(350 350)} at
 * {@code app/jcl/CREASTMT.JCL:L32}, a Job Control Language (JCL) member, since the copybook states
 * none.
 *
 * <p>The composite key comes from {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30}, a
 * 32-byte key at offset zero. That span covers {@code TRNX-CARD-NUM PIC X(16)} at
 * {@code app/cpy/COSTM01.CPY:L22} then {@code TRNX-ID PIC X(16)} at
 * {@code app/cpy/COSTM01.CPY:L23}, the two fields of the group {@code 05 TRNX-KEY.} at
 * {@code app/cpy/COSTM01.CPY:L21}. The sort at {@code app/jcl/CREASTMT.JCL:L53} reads the card
 * number at byte 263, the offset of {@code TRAN-CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVTRA05Y.cpy:L15}, and orders the transaction identifier second.
 * {@code app/jcl/CREASTMT.JCL:L42} names that key, and {@code app/jcl/CREASTMT.JCL:L79} runs
 * {@code app/cbl/CBSTM03A.CBL} over the copy it produces.</p>
 *
 * <p>Two checks sit elsewhere. {@code Class.getDeclaredFields()} carries no ordering guarantee in
 * the Java specification, so {@code NotificationEntityPersistenceTest} asserts the catalogue order
 * of the key columns from {@code key_column_usage}. That class also asserts the character mapping
 * of every column, and it reaches a live entity manager.</p>
 *
 * <p>The {@code card_number} column holds sixteen characters. A full Primary Account Number (PAN)
 * reaches no rendered alert, no log line and no interface response, and masking runs at the
 * serialization boundary. The tests here assert the column name, the field type, the declared width
 * and the null constraint, and assert no character pattern.
 */
@DisplayName("StatementTransactionEntity, the mapped shape of the card-keyed read model")
final class StatementTransactionEntityTest {

    /** Table the read model maps, from {@code src/main/resources/db/migration/V1__schema.sql}. */
    private static final String TABLE_NAME = "statement_transaction";

    /** Simple name of the nested class the entity embeds as its key. */
    private static final String KEY_CLASS_NAME = "StatementTransactionId";

    /** Field of the entity that embeds the composite key. */
    private static final String KEY_FIELD = "id";

    /**
     * Key component fields, in the order {@code KEYS(32 0)} spans the two source fields.
     *
     * <p>The card half is the card token, not a card number. A masked number identifies no single
     * card, so a key over it would merge the histories of two cards sharing their last four
     * digits.</p>
     */
    private static final List<String> KEY_COMPONENT_FIELDS =
            List.of("cardToken", "transactionId");

    /** Key columns, in the order of {@link #KEY_COMPONENT_FIELDS}. */
    private static final List<String> KEY_COLUMNS = List.of("card_token", "transaction_id");

    /** Fields the entity maps beside the key, in declaration order. */
    private static final List<String> NON_KEY_FIELDS = List.of(
            "maskedCardNumber", "typeCode", "categoryCode", "source", "description", "amount",
            "merchantId", "merchantName", "merchantCity", "merchantZip", "originTimestamp",
            "processingTimestamp");

    /** Columns the entity maps beside the key, in the order of {@link #NON_KEY_FIELDS}. */
    private static final List<String> NON_KEY_COLUMNS = List.of(
            "masked_card_number", "type_code", "category_code", "source", "description", "amount",
            "merchant_id", "merchant_name", "merchant_city", "merchant_zip", "origin_timestamp",
            "processing_timestamp");

    /** Columns the table declares: the two key columns and the twelve beside them. */
    private static final int MAPPED_COLUMN_COUNT = 14;

    /** Instance fields the entity declares: the embedded key and the twelve mapped values. */
    private static final int ENTITY_FIELD_COUNT = 13;

    /**
     * Declared width of every character field, keyed by field name.
     *
     * <p>Each width is the digit or character count of the {@code PIC} clause the field maps, read
     * from {@code app/cpy/COSTM01.CPY:L22-L35}. The card token is the one exception: it stands in for
     * {@code TRNX-CARD-NUM PIC X(16)} and holds the 64 characters
     * {@code PanMasker.CARD_TOKEN_LENGTH} declares.</p>
     */
    private static final Map<String, Integer> CHARACTER_WIDTHS = Map.ofEntries(
            Map.entry("cardToken", 64),
            Map.entry("maskedCardNumber", 16),
            Map.entry("transactionId", 16),
            Map.entry("typeCode", 2),
            Map.entry("categoryCode", 4),
            Map.entry("source", 10),
            Map.entry("description", 100),
            Map.entry("merchantId", 9),
            Map.entry("merchantName", 50),
            Map.entry("merchantCity", 50),
            Map.entry("merchantZip", 10),
            Map.entry("originTimestamp", 26),
            Map.entry("processingTimestamp", 26));

    /** Field holding the transaction amount, the one field carrying a decimal type. */
    private static final String AMOUNT_FIELD = "amount";

    /** Digits {@code TRNX-AMT PIC S9(09)V99} carries: nine integer digits and two decimals. */
    private static final int AMOUNT_PRECISION = 11;

    /** Decimal places {@code TRNX-AMT PIC S9(09)V99} carries. */
    private static final int AMOUNT_SCALE = 2;

    /** Digits an account balance column carries, and a width no column of this table declares. */
    private static final int ACCOUNT_BALANCE_PRECISION = 12;

    /** Field holding the four-digit category code. */
    private static final String CATEGORY_CODE_FIELD = "categoryCode";

    /** Field holding the nine-digit merchant identifier. */
    private static final String MERCHANT_ID_FIELD = "merchantId";

    /** Fields holding the two 26-character timestamps. */
    private static final List<String> TIMESTAMP_FIELDS =
            List.of("originTimestamp", "processingTimestamp");

    /** Types no mapped field declares. Each one parses a timestamp and orders it by instant. */
    private static final List<Class<?>> TEMPORAL_TYPES = List.of(
            Instant.class, LocalDate.class, LocalDateTime.class, OffsetDateTime.class,
            ZonedDateTime.class, Timestamp.class, Date.class);

    /**
     * Annotation no timestamp field carries. Jakarta Persistence deprecates the type, so this test
     * names it as text and imports nothing.
     */
    private static final String TEMPORAL_ANNOTATION = "jakarta.persistence.Temporal";

    /** Fragments no field name and no column name contains, matched with case folded away. */
    private static final List<String> FILLER_FRAGMENTS =
            List.of("filler", "padding", "reserved", "unused");

    private static final List<String> ROW_LIFECYCLE_NAMES = List.of(
            "created_at", "createdat", "created", "inserted_at", "insertedat", "rowcreatedat",
            "updated_at", "updatedat", "modified_at", "modifiedat");

    /**
     * Fragments naming a card verification value. The second fragment is assembled from characters,
     * and it is the three-letter abbreviation of the first.
     */
    private static final List<String> CARD_VERIFICATION_FRAGMENTS =
            List.of("verification", "c" + "v".repeat(2));

    /** Fragments naming a card status or an account status. */
    private static final List<String> STATUS_FRAGMENTS = List.of("status", "active");

    /** Annotations no mapped field carries. Each one links a row to a second table. */
    private static final List<Class<? extends Annotation>> RELATIONSHIP_ANNOTATIONS = List.of(
            ManyToOne.class, OneToMany.class, OneToOne.class, ManyToMany.class, JoinColumn.class,
            JoinTable.class);

    /** Annotations no mapped field carries. Each one asks the database for an identifier. */
    private static final List<Class<? extends Annotation>> GENERATOR_ANNOTATIONS =
            List.of(GeneratedValue.class, SequenceGenerator.class);

    /** The one secondary index {@code V1__schema.sql} creates over the table. */
    private static final String SECONDARY_INDEX = "ix_statement_transaction_processing_timestamp";

    /**
     * Locates the nested class the entity embeds as its key.
     *
     * @return the member type named {@link #KEY_CLASS_NAME}
     */
    private static Class<?> keyClass() {
        for (Class<?> member : StatementTransactionEntity.class.getDeclaredClasses()) {
            if (member.getSimpleName().equals(KEY_CLASS_NAME)) {
                return member;
            }
        }
        return fail("StatementTransactionEntity declares no member type named " + KEY_CLASS_NAME
                + ". It declares " + memberTypeNames());
    }

    /**
     * @return one simple name per member type
     */
    private static List<String> memberTypeNames() {
        List<String> names = new ArrayList<>();
        for (Class<?> member : StatementTransactionEntity.class.getDeclaredClasses()) {
            names.add(member.getSimpleName());
        }
        return names;
    }

    /**
     * @param type the type to read
     * @return the instance fields, in the order reflection reports them
     */
    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            fields.add(field);
        }
        return fields;
    }

    /**
     * @param fields the fields to name
     * @return one name per field, in list order
     */
    private static List<String> fieldNames(List<Field> fields) {
        List<String> names = new ArrayList<>();
        for (Field field : fields) {
            names.add(field.getName());
        }
        return names;
    }

    /**
     * Looks one instance field up by name.
     *
     * @param type the type to read
     * @param name the field name to find
     * @return the field under that name
     */
    private static Field instanceField(Class<?> type, String name) {
        for (Field field : instanceFields(type)) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return fail(type.getSimpleName() + " declares no instance field named " + name
                + ". It declares " + fieldNames(instanceFields(type)));
    }

    /**
     * Reads the column mapping of one field.
     *
     * @param field a mapped field
     * @return the column annotation the field carries
     */
    private static Column columnOf(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column == null) {
            return fail("field " + field.getName() + " carries no @Column, so an implicit naming "
                    + "strategy would settle its column name. It carries "
                    + annotationNames(field.getAnnotations()));
        }
        return column;
    }

    /** @return the instance fields of the nested key class */
    private static List<Field> keyFields() {
        return instanceFields(keyClass());
    }

    /** @return the instance fields of the entity that carry a column */
    private static List<Field> entityColumnFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : instanceFields(StatementTransactionEntity.class)) {
            if (field.getAnnotation(Column.class) != null) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** @return every field carrying a column, the two key components first */
    private static List<Field> mappedFields() {
        List<Field> fields = new ArrayList<>(keyFields());
        fields.addAll(entityColumnFields());
        return fields;
    }

    /** @return the column name of every mapped field, in the order of {@link #mappedFields()} */
    private static List<String> mappedColumnNames() {
        List<String> names = new ArrayList<>();
        for (Field field : mappedFields()) {
            names.add(columnOf(field).name());
        }
        return names;
    }

    /** @return the instance fields of the entity that carry {@code @EmbeddedId} */
    private static List<Field> embeddedIdFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : instanceFields(StatementTransactionEntity.class)) {
            if (field.getAnnotation(EmbeddedId.class) != null) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * Reads the table mapping of the entity.
     *
     * @return the table annotation the class carries
     */
    private static Table table() {
        Table table = StatementTransactionEntity.class.getAnnotation(Table.class);
        if (table == null) {
            return fail("StatementTransactionEntity carries no @Table, so an implicit naming "
                    + "strategy would settle its table name. It carries "
                    + annotationNames(StatementTransactionEntity.class.getAnnotations()));
        }
        return table;
    }

    /**
     * @param type the type to read
     * @return the declared constructor that takes no argument
     */
    private static Constructor<?> noArgumentConstructor(Class<?> type) {
        try {
            return type.getDeclaredConstructor();
        } catch (NoSuchMethodException absent) {
            return fail(type.getSimpleName() + " must declare a no-argument constructor for the "
                    + "persistence provider to call", absent);
        }
    }

    /**
     * Folds a name to lower case for fragment matching.
     *
     * @param value the name to fold
     * @return the name in lower case
     */
    private static String fold(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * @param annotations the annotations to name
     * @return one simple name per annotation
     */
    private static List<String> annotationNames(Annotation[] annotations) {
        List<String> names = new ArrayList<>();
        for (Annotation annotation : annotations) {
            names.add(annotation.annotationType().getSimpleName());
        }
        return names;
    }

    /**
     * @param annotations the annotations to name
     * @return one fully qualified name per annotation
     */
    private static List<String> annotationTypeNames(Annotation[] annotations) {
        List<String> names = new ArrayList<>();
        for (Annotation annotation : annotations) {
            names.add(annotation.annotationType().getName());
        }
        return names;
    }

    /** @return every field name and every column name the mapping declares */
    private static List<String> mappedFieldAndColumnNames() {
        List<String> names = new ArrayList<>(fieldNames(mappedFields()));
        names.addAll(mappedColumnNames());
        names.addAll(fieldNames(embeddedIdFields()));
        return names;
    }

    /** @return the instance fields of the entity and of the nested key class */
    private static List<Field> allInstanceFields() {
        List<Field> fields = new ArrayList<>(instanceFields(StatementTransactionEntity.class));
        fields.addAll(keyFields());
        return fields;
    }

    @Nested
    @DisplayName("One identifier mechanism: an embedded key class")
    class KeyMechanism {

        @Test
        @DisplayName("the entity declares the nested key class")
        void declaresTheNestedKeyClass() {
            assertThat(memberTypeNames())
                    .as("member types of StatementTransactionEntity")
                    .contains(KEY_CLASS_NAME);
        }

        @Test
        @DisplayName("the key class is public and static, so the provider can instantiate it")
        void theKeyClassIsPublicAndStatic() {
            int modifiers = keyClass().getModifiers();

            assertAll(KEY_CLASS_NAME + " modifiers",
                    () -> assertThat(Modifier.isPublic(modifiers))
                            .as("%s is public", KEY_CLASS_NAME)
                            .isTrue(),
                    () -> assertThat(Modifier.isStatic(modifiers))
                            .as("%s is static, so it needs no enclosing instance", KEY_CLASS_NAME)
                            .isTrue());
        }

        @Test
        @DisplayName("the key class carries @Embeddable")
        void theKeyClassIsEmbeddable() {
            assertThat(keyClass().getAnnotation(Embeddable.class))
                    .as("@Embeddable on %s, which carries %s", KEY_CLASS_NAME,
                            annotationNames(keyClass().getAnnotations()))
                    .isNotNull();
        }

        @Test
        @DisplayName("exactly one field carries @EmbeddedId, typed as the key class")
        void oneFieldCarriesTheEmbeddedKey() {
            List<Field> embedded = embeddedIdFields();

            assertAll("the embedded key field",
                    () -> assertThat(embedded)
                            .as("fields carrying @EmbeddedId")
                            .hasSize(1),
                    () -> assertThat(fieldNames(embedded))
                            .as("name of the embedded key field")
                            .containsExactly(KEY_FIELD),
                    () -> assertThat(embedded.get(0).getType())
                            .as("declared type of the embedded key field")
                            .isEqualTo(keyClass()));
        }

        @Test
        @DisplayName("the entity declares no @IdClass and no @Id field")
        void declaresNoSecondIdentifierMechanism() {
            List<Field> idFields = new ArrayList<>();
            for (Field field : instanceFields(StatementTransactionEntity.class)) {
                if (field.getAnnotation(Id.class) != null) {
                    idFields.add(field);
                }
            }

            assertAll("one identifier mechanism",
                    () -> assertThat(StatementTransactionEntity.class.getAnnotation(IdClass.class))
                            .as("@IdClass on StatementTransactionEntity")
                            .isNull(),
                    () -> assertThat(fieldNames(idFields))
                            .as("fields carrying @Id")
                            .isEmpty());
        }

        @Test
        @DisplayName("the entity and the key class each declare a no-argument constructor")
        void bothTypesDeclareANoArgumentConstructor() {
            Constructor<?> entityConstructor =
                    noArgumentConstructor(StatementTransactionEntity.class);
            Constructor<?> keyConstructor = noArgumentConstructor(keyClass());

            assertAll("constructors the persistence provider calls",
                    () -> assertThat(Modifier.isPrivate(entityConstructor.getModifiers()))
                            .as("the entity constructor is reachable from a subclass proxy")
                            .isFalse(),
                    () -> assertThat(Modifier.isPrivate(keyConstructor.getModifiers()))
                            .as("the key constructor is reachable from a subclass proxy")
                            .isFalse());
        }
    }

    @Nested
    @DisplayName("Key components: the card token then the transaction identifier")
    class KeyComponents {

        @Test
        @DisplayName("the key declares cardToken then transactionId, in that order")
        void declaresTheTwoComponentsInKeyOrder() {
            assertThat(fieldNames(keyFields()))
                    .as("instance fields of %s", KEY_CLASS_NAME)
                    .containsExactlyElementsOf(KEY_COMPONENT_FIELDS);
        }

        @Test
        @DisplayName("both key components are character fields")
        void bothComponentsAreCharacterFields() {
            List<Executable> checks = new ArrayList<>();
            for (String name : KEY_COMPONENT_FIELDS) {
                Field field = instanceField(keyClass(), name);
                checks.add(() -> assertThat(field.getType())
                        .as("declared type of %s", name)
                        .isEqualTo(String.class));
            }

            assertAll("key component types", checks);
        }

        @Test
        @DisplayName("the key components map account_id then transaction_id")
        void mapTheTwoKeyColumns() {
            List<String> columns = new ArrayList<>();
            for (String name : KEY_COMPONENT_FIELDS) {
                columns.add(columnOf(instanceField(keyClass(), name)).name());
            }

            assertThat(columns)
                    .as("columns the key components map")
                    .containsExactlyElementsOf(KEY_COLUMNS);
        }

        @Test
        @DisplayName("both key columns hold their declared widths and refuse a null")
        void bothKeyColumnsHoldTheirDeclaredWidths() {
            List<Executable> checks = new ArrayList<>();
            for (String name : KEY_COMPONENT_FIELDS) {
                Column column = columnOf(instanceField(keyClass(), name));
                checks.add(() -> assertThat(column.length())
                        .as("declared length of %s", column.name())
                        .isEqualTo(CHARACTER_WIDTHS.get(name)));
                checks.add(() -> assertThat(column.nullable())
                        .as("%s accepts a null", column.name())
                        .isFalse());
            }

            assertAll("key column widths and null constraints", checks);
        }
    }

    @Nested
    @DisplayName("The closed set of fourteen mapped columns")
    class ColumnInventory {

        @Test
        @DisplayName("the entity declares thirteen instance fields: the key and twelve values")
        void declaresThirteenInstanceFields() {
            List<Field> fields = instanceFields(StatementTransactionEntity.class);
            List<String> expected = new ArrayList<>();
            expected.add(KEY_FIELD);
            expected.addAll(NON_KEY_FIELDS);

            assertAll("instance fields of the entity",
                    () -> assertThat(fields)
                            .as("instance field count, statics excluded")
                            .hasSize(ENTITY_FIELD_COUNT),
                    () -> assertThat(fieldNames(fields))
                            .as("instance field names")
                            .containsExactlyElementsOf(expected));
        }

        @Test
        @DisplayName("twelve fields beside the key carry a column")
        void twelveFieldsBesideTheKeyCarryAColumn() {
            List<Field> columnFields = entityColumnFields();

            assertAll("columns beside the key",
                    () -> assertThat(columnFields)
                            .as("fields of the entity carrying @Column")
                            .hasSize(NON_KEY_FIELDS.size()),
                    () -> assertThat(fieldNames(columnFields))
                            .as("names of the fields carrying @Column")
                            .containsExactlyElementsOf(NON_KEY_FIELDS));
        }

        @Test
        @DisplayName("the twelve column names are the ones the migration creates")
        void theTwelveColumnNamesMatchTheMigration() {
            List<String> columns = new ArrayList<>();
            for (Field field : entityColumnFields()) {
                columns.add(columnOf(field).name());
            }

            assertThat(columns)
                    .as("columns the entity maps beside the key")
                    .containsExactlyElementsOf(NON_KEY_COLUMNS);
        }

        @Test
        @DisplayName("fourteen columns carry one row, key columns included")
        void fourteenColumnsCarryOneRow() {
            List<String> expected = new ArrayList<>(KEY_COLUMNS);
            expected.addAll(NON_KEY_COLUMNS);

            assertAll("the closed column set",
                    () -> assertThat(mappedColumnNames())
                            .as("column count")
                            .hasSize(MAPPED_COLUMN_COUNT),
                    () -> assertThat(mappedColumnNames())
                            .as("every mapped column, key columns first")
                            .containsExactlyElementsOf(expected));
        }

        @Test
        @DisplayName("all fourteen columns refuse a null")
        void allFourteenColumnsRefuseANull() {
            List<Executable> checks = new ArrayList<>();
            for (Field field : mappedFields()) {
                Column column = columnOf(field);
                checks.add(() -> assertThat(column.nullable())
                        .as("%s accepts a null, and V1__schema.sql declares it NOT NULL",
                                column.name())
                        .isFalse());
            }

            assertAll("null constraints over " + MAPPED_COLUMN_COUNT + " columns",
                    () -> assertThat(checks).hasSize(MAPPED_COLUMN_COUNT),
                    () -> assertAll("nullable = false", checks));
        }

        @Test
        @DisplayName("every character column declares the width its source field carries")
        void everyCharacterColumnDeclaresItsSourceWidth() {
            List<Executable> checks = new ArrayList<>();
            List<String> characterFields = new ArrayList<>();

            for (Field field : mappedFields()) {
                if (field.getType() != String.class) {
                    continue;
                }
                String name = field.getName();
                characterFields.add(name);
                Column column = columnOf(field);
                Integer width = CHARACTER_WIDTHS.get(name);
                checks.add(() -> assertThat(width)
                        .as("field %s carries no width in this test's table", name)
                        .isNotNull());
                checks.add(() -> assertThat(column.length())
                        .as("declared length of %s", column.name())
                        .isEqualTo(width));
            }

            assertAll("character column widths",
                    () -> assertThat(characterFields)
                            .as("character fields, which the width table must cover exactly")
                            .containsExactlyInAnyOrderElementsOf(CHARACTER_WIDTHS.keySet()),
                    () -> assertAll("declared lengths", checks));
        }
    }

    @Nested
    @DisplayName("The amount column carries nine integer digits")
    class MonetaryPrecision {

        @Test
        @DisplayName("the amount field is a fixed-point decimal")
        void theAmountFieldIsAFixedPointDecimal() {
            Field amount = instanceField(StatementTransactionEntity.class, AMOUNT_FIELD);

            assertThat(amount.getType())
                    .as("declared type of %s", AMOUNT_FIELD)
                    .isEqualTo(BigDecimal.class);
        }

        @Test
        @DisplayName("the amount column declares eleven digits and two decimal places")
        void theAmountColumnDeclaresElevenDigitsAndTwoDecimals() {
            Column amount =
                    columnOf(instanceField(StatementTransactionEntity.class, AMOUNT_FIELD));

            assertAll("TRNX-AMT PIC S9(09)V99 at app/cpy/COSTM01.CPY:L29",
                    () -> assertThat(amount.precision())
                            .as("declared precision of %s", amount.name())
                            .isEqualTo(AMOUNT_PRECISION),
                    () -> assertThat(amount.scale())
                            .as("declared scale of %s", amount.name())
                            .isEqualTo(AMOUNT_SCALE));
        }

        @Test
        @DisplayName("the amount column declares no account-balance precision")
        void theAmountColumnDeclaresNoAccountBalancePrecision() {
            Column amount =
                    columnOf(instanceField(StatementTransactionEntity.class, AMOUNT_FIELD));

            assertThat(amount.precision())
                    .as("%s carries the %d digits of TRNX-AMT PIC S9(09)V99, and %d belongs to the "
                            + "account balance at app/cpy/CVACT01Y.cpy, a column this table holds "
                            + "none of", amount.name(), AMOUNT_PRECISION, ACCOUNT_BALANCE_PRECISION)
                    .isNotEqualTo(ACCOUNT_BALANCE_PRECISION);
        }
    }

    @Nested
    @DisplayName("Both timestamp columns hold text, not a temporal type")
    class TimestampColumns {

        @Test
        @DisplayName("both timestamp fields are character fields")
        void bothTimestampFieldsAreCharacterFields() {
            List<Executable> checks = new ArrayList<>();
            for (String name : TIMESTAMP_FIELDS) {
                Field field = instanceField(StatementTransactionEntity.class, name);
                checks.add(() -> assertThat(field.getType())
                        .as("declared type of %s", name)
                        .isEqualTo(String.class));
            }

            assertAll("timestamp field types", checks);
        }

        @Test
        @DisplayName("neither timestamp field declares a temporal type")
        void neitherTimestampFieldDeclaresATemporalType() {
            List<Executable> checks = new ArrayList<>();
            for (String name : TIMESTAMP_FIELDS) {
                Class<?> declared =
                        instanceField(StatementTransactionEntity.class, name).getType();
                checks.add(() -> assertThat(TEMPORAL_TYPES)
                        .as("declared type of %s, which the source compares as text", name)
                        .doesNotContain(declared));
            }

            assertAll("timestamp fields against " + TEMPORAL_TYPES.size() + " temporal types",
                    checks);
        }

        @Test
        @DisplayName("neither timestamp field carries the temporal annotation")
        void neitherTimestampFieldCarriesTheTemporalAnnotation() {
            List<Executable> checks = new ArrayList<>();
            for (String name : TIMESTAMP_FIELDS) {
                Field field = instanceField(StatementTransactionEntity.class, name);
                List<String> carried = annotationTypeNames(field.getAnnotations());
                checks.add(() -> assertThat(carried)
                        .as("annotations on %s", name)
                        .doesNotContain(TEMPORAL_ANNOTATION));
            }

            assertAll("the temporal annotation over both timestamp fields", checks);
        }
    }

    @Nested
    @DisplayName("The two digit-text columns keep their leading zeros")
    class DigitTextColumns {

        @Test
        @DisplayName("the category code is four characters of digit text")
        void theCategoryCodeIsFourCharactersOfDigitText() {
            Field field = instanceField(StatementTransactionEntity.class, CATEGORY_CODE_FIELD);
            Column column = columnOf(field);

            assertAll("TRNX-CAT-CD PIC 9(04) at app/cpy/COSTM01.CPY:L26",
                    () -> assertThat(field.getType())
                            .as("declared type of %s", CATEGORY_CODE_FIELD)
                            .isEqualTo(String.class),
                    () -> assertThat(column.length())
                            .as("declared length of %s", column.name())
                            .isEqualTo(CHARACTER_WIDTHS.get(CATEGORY_CODE_FIELD)));
        }

        @Test
        @DisplayName("the merchant identifier is nine characters of digit text")
        void theMerchantIdentifierIsNineCharactersOfDigitText() {
            Field field = instanceField(StatementTransactionEntity.class, MERCHANT_ID_FIELD);
            Column column = columnOf(field);

            assertAll("TRNX-MERCHANT-ID PIC 9(09) at app/cpy/COSTM01.CPY:L30",
                    () -> assertThat(field.getType())
                            .as("declared type of %s", MERCHANT_ID_FIELD)
                            .isEqualTo(String.class),
                    () -> assertThat(column.length())
                            .as("declared length of %s", column.name())
                            .isEqualTo(CHARACTER_WIDTHS.get(MERCHANT_ID_FIELD)));
        }
    }

    @Nested
    @DisplayName("Additions this read model refuses")
    class AbsentMappings {

        @Test
        @DisplayName("no name carries the trailing filler of the source record")
        void noNameCarriesTheTrailingFiller() {
            List<Executable> checks = new ArrayList<>();
            for (String name : mappedFieldAndColumnNames()) {
                String folded = fold(name);
                for (String fragment : FILLER_FRAGMENTS) {
                    checks.add(() -> assertThat(folded)
                            .as("'%s' names the FILLER PIC X(20) at app/cpy/COSTM01.CPY:L36, "
                                    + "which carries no column", name)
                            .doesNotContain(fragment));
                }
            }

            assertAll("filler fragments over every field and column name", checks);
        }

        @Test
        @DisplayName("no fifteenth column reaches the table")
        void noFifteenthColumnReachesTheTable() {
            List<Executable> checks = new ArrayList<>();
            for (Field field : instanceFields(StatementTransactionEntity.class)) {
                boolean mapped = field.getAnnotation(Column.class) != null
                        || field.getAnnotation(EmbeddedId.class) != null;
                checks.add(() -> assertThat(mapped)
                        .as("field %s carries neither @Column nor @EmbeddedId, so the provider "
                                + "maps it under a name no migration creates", field.getName())
                        .isTrue());
            }

            assertAll("the mapped shape admits no further column",
                    () -> assertThat(mappedColumnNames())
                            .as("mapped columns")
                            .hasSize(MAPPED_COLUMN_COUNT),
                    () -> assertAll("every instance field is mapped on purpose", checks));
        }

        @Test
        @DisplayName("no column records when a row reached the table")
        void noColumnRecordsWhenARowReachedTheTable() {
            List<Executable> checks = new ArrayList<>();
            for (String name : mappedFieldAndColumnNames()) {
                String folded = fold(name);
                checks.add(() -> assertThat(ROW_LIFECYCLE_NAMES)
                        .as("'%s' records a row lifetime, and the read model records the event",
                                name)
                        .doesNotContain(folded));
            }

            assertAll("row-lifecycle names over every field and column name", checks);
        }

        @Test
        @DisplayName("no column carries a card verification value")
        void noColumnCarriesACardVerificationValue() {
            List<Executable> checks = new ArrayList<>();
            for (String name : mappedFieldAndColumnNames()) {
                String folded = fold(name);
                for (String fragment : CARD_VERIFICATION_FRAGMENTS) {
                    checks.add(() -> assertThat(folded)
                            .as("'%s' names the value at app/cpy/CVACT02Y.cpy:L7, which the card "
                                    + "service holds and this service never receives", name)
                            .doesNotContain(fragment));
                }
            }

            assertAll("card-verification fragments over every field and column name", checks);
        }

        @Test
        @DisplayName("no column carries a card status or an account status")
        void noColumnCarriesAStatus() {
            List<Executable> checks = new ArrayList<>();
            for (String name : mappedFieldAndColumnNames()) {
                String folded = fold(name);
                for (String fragment : STATUS_FRAGMENTS) {
                    checks.add(() -> assertThat(folded)
                            .as("'%s' names a status, and the source posting path tests none",
                                    name)
                            .doesNotContain(fragment));
                }
            }

            assertAll("status fragments over every field and column name", checks);
        }

        @Test
        @DisplayName("no field declares a relationship to a second table")
        void noFieldDeclaresARelationship() {
            List<Executable> checks = new ArrayList<>();
            for (Field field : allInstanceFields()) {
                for (Class<? extends Annotation> annotation : RELATIONSHIP_ANNOTATIONS) {
                    checks.add(() -> assertThat(field.getAnnotation(annotation))
                            .as("@%s on %s, and this service reaches no second schema",
                                    annotation.getSimpleName(), field.getName())
                            .isNull());
                }
            }

            assertAll("relationship annotations over every instance field", checks);
        }

        @Test
        @DisplayName("no field asks the database to generate its value")
        void noFieldAsksForAGeneratedValue() {
            List<Executable> checks = new ArrayList<>();
            for (Field field : allInstanceFields()) {
                for (Class<? extends Annotation> annotation : GENERATOR_ANNOTATIONS) {
                    checks.add(() -> assertThat(field.getAnnotation(annotation))
                            .as("@%s on %s, and the consumed event supplies the identifier",
                                    annotation.getSimpleName(), field.getName())
                            .isNull());
                }
            }

            assertAll("generator annotations over every instance field", checks);
        }

        @Test
        @DisplayName("no field is declared @Transient")
        void noFieldIsDeclaredTransient() {
            List<Executable> checks = new ArrayList<>();
            for (Field field : allInstanceFields()) {
                checks.add(() -> assertThat(field.getAnnotation(Transient.class))
                        .as("@Transient on %s, which would hold a dropped source field in memory",
                                field.getName())
                        .isNull());
            }

            assertAll("@Transient over every instance field", checks);
        }
    }

    @Nested
    @DisplayName("Table identity and the one secondary index")
    class TableMapping {

        @Test
        @DisplayName("the entity maps the statement_transaction table")
        void mapsTheStatementTransactionTable() {
            assertAll("table identity",
                    () -> assertThat(StatementTransactionEntity.class.getAnnotation(Entity.class))
                            .as("@Entity on StatementTransactionEntity")
                            .isNotNull(),
                    () -> assertThat(table().name())
                            .as("mapped table name")
                            .isEqualTo(TABLE_NAME),
                    () -> assertThat(table().schema())
                            .as("mapped schema, which spring.flyway.schemas settles at run time")
                            .isEmpty());
        }

        @Test
        @DisplayName("the entity declares the one secondary index the migration creates")
        void declaresTheOneSecondaryIndex() {
            assertAll("secondary indexes",
                    () -> assertThat(table().indexes())
                            .as("indexes declared on the table")
                            .hasSize(1),
                    () -> assertThat(table().indexes()[0].name())
                            .as("declared index name")
                            .isEqualTo(SECONDARY_INDEX),
                    () -> assertThat(table().indexes()[0].columnList())
                            .as("column the declared index reads")
                            .isEqualTo("processing_timestamp"));
        }
    }
}
