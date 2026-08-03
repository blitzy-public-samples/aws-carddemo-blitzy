package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Table;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reflection-based contract test for the {@link DailyTransaction} daily-transaction feed model.
 *
 * :purpose: Lock the field-level contract of the COBOL ``DALYTRAN-RECORD`` layout (copybook
 *     ``app/cpy/CVTRA06Y.cpy``, fixed record length 350) as re-expressed in Java, with emphasis on
 *     financial-precision fidelity (AAP 0.6.1) and timestamp-width preservation (AAP 0.3.6):
 *     ``DALYTRAN-AMT PIC S9(09)V99`` must be carried by {@link BigDecimal} — never a binary
 *     floating-point type — and the ``X(26)`` origination/processing timestamps must remain
 *     full-width {@link String} values, so the batch posting job reproduces the legacy over-limit,
 *     cross-reference, and expiry results byte-for-byte. ``DailyTransaction`` is the JPA entity for
 *     the staged ``DALYTRAN`` feed: the ``daily_transactions`` table is the relational image of the
 *     sequential input data set that the posting job re-reads, so the mapping conditions are asserted
 *     positively (``@Entity``, ``@Table(name = "daily_transactions")``, ``@Id`` on ``dalytranId`` and
 *     a ``@Column`` on every modeled field). It mirrors the {@code Transaction} field set with the
 *     ``dalytran`` prefix.
 * :output: JUnit 5 / AssertJ assertions only, driven purely by ``java.lang.reflect`` and
 *     ``java.math.BigDecimal``; the class holds no state and touches no database, Spring context, or
 *     other external resource.
 */
final class DailyTransactionTest {

    /** The thirteen modeled feed fields (the trailing 20-byte COBOL ``FILLER`` is not modeled). */
    private static final int MODELED_FIELD_COUNT = 13;

    /** Sample 26-character timestamp in the ``YYYY-MM-DD-HH.MM.SS.mmmmmm`` wire form. */
    private static final String TS_26 = "2024-01-15-08.30.45.123456";

    /**
     * Look up a declared field, failing with a clear message when the contract omits it.
     *
     * :param name: the exact Java field name expected on {@link DailyTransaction}.
     * :return: the reflective {@link Field} handle for the named field.
     */
    private static Field field(String name) {
        try {
            return DailyTransaction.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("DailyTransaction is missing expected field: " + name, e);
        }
    }

    /**
     * Report whether {@link DailyTransaction} declares a field.
     *
     * :param name: the candidate Java field name.
     * :return: ``true`` when the field is declared, ``false`` otherwise.
     */
    private static boolean fieldExists(String name) {
        try {
            DailyTransaction.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    /**
     * Collect the instance (non-static, non-synthetic) declared fields.
     *
     * :return: the modeled state fields, excluding any compiler/coverage synthetics.
     */
    private static List<Field> instanceFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : DailyTransaction.class.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    /**
     * Look up a public method, failing with a clear message when it is absent.
     *
     * :param name: the public method name.
     * :param params: the declared parameter types.
     * :return: the reflective {@link Method} handle (guaranteed public by ``getMethod``).
     */
    private static Method method(String name, Class<?>... params) {
        try {
            return DailyTransaction.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("DailyTransaction is missing expected public method: " + name, e);
        }
    }

    /**
     * Collect the simple names of a set of annotations.
     *
     * :param annotations: the annotations declared on a class or field.
     * :return: the annotation simple names (e.g. ``Entity``, ``Column``).
     */
    private static List<String> annotationSimpleNames(Annotation[] annotations) {
        List<String> names = new ArrayList<>();
        for (Annotation a : annotations) {
            names.add(a.annotationType().getSimpleName());
        }
        return names;
    }

    /**
     * Resolve the mapped table name declared by the entity.
     *
     * :return: the ``@Table(name = ...)`` value declared on {@link DailyTransaction}.
     */
    private static String tableName() {
        Table table = DailyTransaction.class.getAnnotation(Table.class);
        assertThat(table).as("DailyTransaction must be annotated @Table").isNotNull();
        return table.name();
    }

    /**
     * Capitalize the first character of a field name for accessor derivation.
     *
     * :param name: the field name.
     * :return: the name with its first character upper-cased.
     */
    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Test
    @DisplayName("DailyTransaction is a concrete, public, instantiable class")
    void dailyTransactionIsAConcretePublicClass() {
        int modifiers = DailyTransaction.class.getModifiers();

        assertThat(Modifier.isPublic(modifiers)).as("class must be public").isTrue();
        assertThat(Modifier.isAbstract(modifiers)).as("class must not be abstract").isFalse();
        assertThat(DailyTransaction.class.isInterface()).as("must not be an interface").isFalse();
        assertThat(DailyTransaction.class.isEnum()).as("must not be an enum").isFalse();
    }

    @Test
    @DisplayName("DailyTransaction is a JPA @Entity mapped to the \"daily_transactions\" table")
    void isJpaEntityMappedToDailyTransactionsTable() {
        // The staged DALYTRAN feed is persisted so the CBTRN02C posting ItemReader can re-read it
        // across job launches and service instances, exactly as the legacy job re-reads its input
        // data set. Verified by annotation simple-name so the assertion states the mapping contract
        // without depending on the jakarta.persistence types.
        List<String> classAnnotations =
                annotationSimpleNames(DailyTransaction.class.getDeclaredAnnotations());
        assertThat(classAnnotations)
                .as("DailyTransaction must be JPA-mapped (staged daily feed row)")
                .contains("Entity", "Table");
        assertThat(tableName())
                .as("DailyTransaction must map to the daily_transactions table")
                .isEqualTo("daily_transactions");

        for (Field f : instanceFields()) {
            List<String> fieldAnnotations = annotationSimpleNames(f.getDeclaredAnnotations());
            assertThat(fieldAnnotations)
                    .as("field '%s' must carry a @Column mapping", f.getName())
                    .contains("Column");
            assertThat(fieldAnnotations)
                    .as("field '%s' must not be version- or association-mapped", f.getName())
                    .doesNotContain("Version", "JoinColumn", "GeneratedValue");
        }
    }

    @Test
    @DisplayName("Exposes a public no-argument constructor for the batch field-set mapper")
    void hasPublicNoArgConstructor() throws Exception {
        Constructor<DailyTransaction> ctor = DailyTransaction.class.getConstructor();

        assertThat(Modifier.isPublic(ctor.getModifiers())).as("no-arg ctor must be public").isTrue();
        assertThat(ctor.newInstance()).as("no-arg ctor must instantiate").isNotNull();
    }

    @Test
    @DisplayName("dalytranId is the @Id primary key: String (DALYTRAN-ID X(16))")
    void dalytranIdIsTheStringPrimaryKey() {
        Field id = field("dalytranId");

        assertThat(id.getType()).as("dalytranId must be a String").isEqualTo(String.class);
        assertThat(annotationSimpleNames(id.getDeclaredAnnotations()))
                .as("dalytranId must be the JPA primary key of the staged feed row")
                .contains("Id", "Column");
        assertThat(annotationSimpleNames(id.getDeclaredAnnotations()))
                .as("the feed id is the natural DALYTRAN-ID, never database-generated")
                .doesNotContain("GeneratedValue");
    }

    @Test
    @DisplayName("equals()/hashCode() are keyed solely on dalytranId")
    void equalsAndHashCodeAreKeyedOnDalytranId() {
        DailyTransaction a = new DailyTransaction();
        a.setDalytranId("0000000000000001");

        DailyTransaction sameId = new DailyTransaction();
        sameId.setDalytranId("0000000000000001");
        // Divergent non-identifier state must not affect identity semantics.
        sameId.setDalytranAmt(new BigDecimal("9.99"));
        sameId.setDalytranCardNum("4111111111111111");

        DailyTransaction otherId = new DailyTransaction();
        otherId.setDalytranId("0000000000000002");

        assertThat(a).isEqualTo(sameId);
        assertThat(a).hasSameHashCodeAs(sameId);
        assertThat(a).isNotEqualTo(otherId);
    }

    @Test
    @DisplayName("dalytranAmt is a java.math.BigDecimal (DALYTRAN-AMT S9(09)V99)")
    void dalytranAmtIsBigDecimal() {
        assertThat(field("dalytranAmt").getType())
                .as("DALYTRAN-AMT must be BigDecimal to preserve packed-decimal precision")
                .isEqualTo(BigDecimal.class);
    }

    @Test
    @DisplayName("dalytranAmt preserves scale-2 and exact precision through its accessors")
    void dalytranAmtPreservesScaleTwoAndPrecisionThroughAccessors() {
        // AAP 0.6.1: the amount participates in the exact packed-decimal over-limit computation
        // WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT. The NUMERIC(11,2)
        // column contract is asserted separately (MonetaryScaleFidelityTest); here the guarantee is
        // that BigDecimal carries the value with no binary floating-point representation error.
        DailyTransaction dt = new DailyTransaction();

        dt.setDalytranAmt(new BigDecimal("12345.67"));
        assertThat(dt.getDalytranAmt()).isEqualByComparingTo("12345.67");
        assertThat(dt.getDalytranAmt().scale()).as("scale-2 preserved").isEqualTo(2);

        // A value a binary double cannot represent exactly is carried verbatim by BigDecimal.
        dt.setDalytranAmt(new BigDecimal("0.005"));
        assertThat(dt.getDalytranAmt().toPlainString()).isEqualTo("0.005");
    }

    @Test
    @DisplayName("No field anywhere is a binary floating-point type (double/float/Double/Float)")
    void noFloatingPointFieldsAnywhere() {
        for (Field f : instanceFields()) {
            assertThat(f.getType())
                    .as("field '%s' must not be a binary floating-point type", f.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    @Test
    @DisplayName("Timestamp fields are 26-character String values (DALYTRAN-ORIG-TS/PROC-TS X(26))")
    void timestampsAreString26() {
        DailyTransaction dt = new DailyTransaction();

        // dalytranOrigTs is always present; dalytranProcTs is guarded but present in this contract.
        assertThat(fieldExists("dalytranOrigTs")).as("dalytranOrigTs must exist").isTrue();
        assertThat(field("dalytranOrigTs").getType()).isEqualTo(String.class);
        dt.setDalytranOrigTs(TS_26);
        assertThat(dt.getDalytranOrigTs()).isEqualTo(TS_26).hasSize(26);

        if (fieldExists("dalytranProcTs")) {
            assertThat(field("dalytranProcTs").getType()).isEqualTo(String.class);
            dt.setDalytranProcTs(TS_26);
            assertThat(dt.getDalytranProcTs()).isEqualTo(TS_26).hasSize(26);
        }
    }

    @Test
    @DisplayName("All thirteen CVTRA06Y feed fields are present with their expected Java types")
    void allCopybookFieldsPresentWithExpectedTypes() {
        Map<String, Class<?>> expected = new LinkedHashMap<>();
        expected.put("dalytranId", String.class);          // DALYTRAN-ID           X(16)
        expected.put("dalytranTypeCd", String.class);       // DALYTRAN-TYPE-CD      X(02)
        expected.put("dalytranCatCd", Integer.class);       // DALYTRAN-CAT-CD       9(04)
        expected.put("dalytranSource", String.class);       // DALYTRAN-SOURCE       X(10)
        expected.put("dalytranDesc", String.class);         // DALYTRAN-DESC         X(100)
        expected.put("dalytranAmt", BigDecimal.class);      // DALYTRAN-AMT          S9(09)V99
        expected.put("dalytranMerchantId", Long.class);     // DALYTRAN-MERCHANT-ID  9(09)
        expected.put("dalytranMerchantName", String.class); // DALYTRAN-MERCHANT-NAME X(50)
        expected.put("dalytranMerchantCity", String.class); // DALYTRAN-MERCHANT-CITY X(50)
        expected.put("dalytranMerchantZip", String.class);  // DALYTRAN-MERCHANT-ZIP X(10)
        expected.put("dalytranCardNum", String.class);      // DALYTRAN-CARD-NUM     X(16)
        expected.put("dalytranOrigTs", String.class);       // DALYTRAN-ORIG-TS      X(26)
        expected.put("dalytranProcTs", String.class);       // DALYTRAN-PROC-TS      X(26)

        expected.forEach((name, type) ->
                assertThat(field(name).getType())
                        .as("field '%s' type", name)
                        .isEqualTo(type));

        assertThat(instanceFields())
                .as("exactly the thirteen modeled feed fields (COBOL FILLER excluded)")
                .hasSize(MODELED_FIELD_COUNT);
    }

    @Test
    @DisplayName("Every declared field exposes matching public getter and setter accessors")
    void publicAccessorsExistForEveryDeclaredField() {
        for (Field f : instanceFields()) {
            String suffix = capitalize(f.getName());

            Method getter = method("get" + suffix);
            assertThat(getter.getReturnType())
                    .as("getter for '%s' must return the field type", f.getName())
                    .isEqualTo(f.getType());

            Method setter = method("set" + suffix, f.getType());
            assertThat(setter.getReturnType())
                    .as("setter for '%s' must be void", f.getName())
                    .isEqualTo(void.class);
        }
    }
}
