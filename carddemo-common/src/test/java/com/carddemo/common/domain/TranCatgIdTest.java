package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the {@link TranCatgId} JPA composite-key contract.
 *
 * :purpose: Freeze the two-part ``@IdClass`` that migrates the COBOL ``TRAN-CAT-KEY``
 *     group of the ``CVTRA04Y`` copybook (``TRAN-TYPE-CD`` PIC X(02) paired with
 *     ``TRAN-CAT-CD`` PIC 9(04)) onto the identifier for entity {@link TranCatg}. The
 *     key has EXACTLY two fields and must stay DISTINCT from the three-field
 *     {@link TranCatBalId}; the ``isDistinctFromTranCatBalId`` assertion guards against
 *     accidentally duplicating the wrong key shape. The assertions also lock the
 *     ``Serializable`` contract, the ``serialVersionUID``, the constructors, the
 *     ``equals``/``hashCode`` behaviour, and the ``@IdClass`` ↔ entity field alignment.
 * :output: JUnit 5 / AssertJ assertions only; the class holds no state and touches no
 *     database, Spring context, or Testcontainers resource. It inspects the compiled
 *     class metadata purely through ``java.lang.reflect`` and exercises value behaviour
 *     through the real public API.
 */
final class TranCatgIdTest {

    /**
     * Build a {@link TranCatgId} through its real compiled all-arguments constructor.
     *
     * :param tranTypeCd: transaction-type code component (``TRAN-TYPE-CD``).
     * :param tranCatCd: transaction-category code component (``TRAN-CAT-CD``).
     * :output: a populated composite-key instance.
     */
    private static TranCatgId newId(String tranTypeCd, Integer tranCatCd) {
        return new TranCatgId(tranTypeCd, tranCatCd);
    }

    /**
     * Collect the declared instance fields of a type, excluding static fields (such as
     * ``serialVersionUID``) and compiler/agent synthetic fields (such as ``$jacocoData``).
     *
     * :param type: the class to inspect.
     * :output: the list of non-static, non-synthetic declared {@link Field} objects.
     */
    private static List<Field> instanceFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();
    }

    /**
     * Sort the names of the supplied types so constructor parameter lists can be compared
     * as an order-independent multiset.
     *
     * :param types: the parameter types to normalise.
     * :output: the type names in natural (ascending) order.
     */
    private static List<String> sortedTypeNames(Class<?>... types) {
        List<String> names = new ArrayList<>();
        for (Class<?> type : types) {
            names.add(type.getName());
        }
        names.sort(null);
        return names;
    }

    // ---------------------------------------------------------------------
    // Phase 2 — structural assertions (including distinctness)
    // ---------------------------------------------------------------------

    /**
     * Assert the identifier is serializable, as JPA requires of ``@IdClass`` keys.
     *
     * :output: passes when {@link TranCatgId} implements {@link Serializable}.
     */
    @Test
    @DisplayName("TranCatgId implements Serializable")
    void implementsSerializable() {
        assertThat(Serializable.class.isAssignableFrom(TranCatgId.class))
                .as("TranCatgId must implement Serializable")
                .isTrue();
    }

    /**
     * Assert the frozen serialization identity.
     *
     * :output: passes when the effective ``serialVersionUID`` is exactly ``1L``.
     */
    @Test
    @DisplayName("serialVersionUID is 1L")
    void serialVersionUidIsOne() {
        ObjectStreamClass descriptor = ObjectStreamClass.lookup(TranCatgId.class);
        assertThat(descriptor)
                .as("TranCatgId must be a serializable class with a stream descriptor")
                .isNotNull();
        assertThat(descriptor.getSerialVersionUID())
                .as("TranCatgId.serialVersionUID must be 1L")
                .isEqualTo(1L);
    }

    /**
     * Assert the key carries EXACTLY the two expected components and no others.
     *
     * :output: passes when the non-static, non-synthetic fields are exactly
     *     ``{tranTypeCd=String, tranCatCd=Integer}``.
     */
    @Test
    @DisplayName("TranCatgId has exactly two key fields: tranTypeCd:String, tranCatCd:Integer")
    void hasExactlyTwoKeyFields() {
        List<Field> fields = instanceFields(TranCatgId.class);
        assertThat(fields)
                .as("TranCatgId must declare exactly two persistent key fields")
                .hasSize(2);

        Map<String, Class<?>> actual = new LinkedHashMap<>();
        for (Field field : fields) {
            actual.put(field.getName(), field.getType());
        }

        Map<String, Class<?>> expected = new LinkedHashMap<>();
        expected.put("tranTypeCd", String.class);
        expected.put("tranCatCd", Integer.class);

        assertThat(actual)
                .as("TranCatgId key field name→type map must match the frozen contract")
                .isEqualTo(expected);
    }

    /**
     * Assert {@link TranCatgId} is a different key shape from {@link TranCatBalId}.
     *
     * :output: passes when TranCatgId declares no ``trancatAcctId`` field and its
     *     two-field shape differs from TranCatBalId's three-field shape.
     */
    @Test
    @DisplayName("TranCatgId is distinct from the 3-field TranCatBalId")
    void isDistinctFromTranCatBalId() {
        assertThatThrownBy(() -> TranCatgId.class.getDeclaredField("trancatAcctId"))
                .as("TranCatgId must NOT declare the TranCatBalId-only field 'trancatAcctId'")
                .isInstanceOf(NoSuchFieldException.class);

        int tranCatgFieldCount = instanceFields(TranCatgId.class).size();
        int tranCatBalFieldCount = instanceFields(TranCatBalId.class).size();

        assertThat(tranCatgFieldCount)
                .as("TranCatgId must have two key fields")
                .isEqualTo(2);
        assertThat(tranCatBalFieldCount)
                .as("TranCatBalId must have three key fields")
                .isEqualTo(3);
        assertThat(tranCatgFieldCount)
                .as("TranCatgId and TranCatBalId must be different @IdClass shapes")
                .isNotEqualTo(tranCatBalFieldCount);
    }

    /**
     * Assert the no-argument constructor JPA needs to reflectively instantiate the key.
     *
     * :output: passes when a public no-arg constructor is declared.
     */
    @Test
    @DisplayName("TranCatgId declares a public no-arg constructor")
    void hasNoArgConstructor() throws NoSuchMethodException {
        Constructor<TranCatgId> constructor = TranCatgId.class.getDeclaredConstructor();
        assertThat(constructor)
                .as("TranCatgId must declare a no-arg constructor")
                .isNotNull();
        assertThat(Modifier.isPublic(constructor.getModifiers()))
                .as("The no-arg constructor must be public for the JPA provider")
                .isTrue();
    }

    /**
     * Assert the all-arguments ``(String, Integer)`` constructor exists.
     *
     * :output: passes when a public two-parameter constructor whose parameter-type
     *     multiset equals ``{String, Integer}`` is declared (order-independent).
     */
    @Test
    @DisplayName("TranCatgId declares a public all-args (String, Integer) constructor")
    void hasAllArgsConstructor() {
        List<String> expectedParamTypes = sortedTypeNames(String.class, Integer.class);

        Constructor<?> allArgs = null;
        for (Constructor<?> candidate : TranCatgId.class.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == 2
                    && sortedTypeNames(candidate.getParameterTypes()).equals(expectedParamTypes)) {
                allArgs = candidate;
                break;
            }
        }

        assertThat(allArgs)
                .as("TranCatgId must declare an all-args (String, Integer) constructor")
                .isNotNull();
        assertThat(Modifier.isPublic(allArgs.getModifiers()))
                .as("The all-args constructor must be public")
                .isTrue();
    }

    // ---------------------------------------------------------------------
    // Phase 3 — equals / hashCode behavioural contract
    // ---------------------------------------------------------------------

    /**
     * Assert equality is reflexive.
     *
     * :output: passes when an identifier equals itself.
     */
    @Test
    @DisplayName("equals is reflexive")
    void equalsIsReflexive() {
        TranCatgId base = newId("01", 1001);
        assertThat(base).isEqualTo(base);
        assertThat(base.equals(base)).isTrue();
    }

    /**
     * Assert equality is symmetric for two distinct instances holding equal components.
     *
     * :output: passes when two separate instances with the same components are equal in
     *     both directions yet are not the same reference.
     */
    @Test
    @DisplayName("equals is symmetric for equal key components")
    void equalsIsSymmetricForEqualKeys() {
        TranCatgId base = newId("01", 1001);
        TranCatgId other = newId("01", 1001);

        assertThat(other).isNotSameAs(base);
        assertThat(base).isEqualTo(other);
        assertThat(other).isEqualTo(base);
    }

    /**
     * Assert equal identifiers expose equal hash codes.
     *
     * :output: passes when two equal instances share the same ``hashCode``.
     */
    @Test
    @DisplayName("equal keys share the same hashCode")
    void equalKeysShareHashCode() {
        TranCatgId base = newId("01", 1001);
        TranCatgId other = newId("01", 1001);

        assertThat(base.hashCode()).isEqualTo(other.hashCode());
    }

    /**
     * Assert ``hashCode`` is stable across repeated invocations on one instance.
     *
     * :output: passes when successive ``hashCode`` calls return the same value.
     */
    @Test
    @DisplayName("hashCode is consistent across invocations")
    void hashCodeIsConsistentAcrossInvocations() {
        TranCatgId base = newId("01", 1001);

        int first = base.hashCode();
        assertThat(base.hashCode()).isEqualTo(first);
        assertThat(base.hashCode()).isEqualTo(first);
    }

    /**
     * Assert the transaction-type component participates in equality.
     *
     * :output: passes when changing only ``tranTypeCd`` yields an unequal key.
     */
    @Test
    @DisplayName("keys differ when tranTypeCd differs")
    void differsWhenTranTypeCdDiffers() {
        TranCatgId base = newId("01", 1001);
        assertThat(newId("02", 1001)).isNotEqualTo(base);
    }

    /**
     * Assert the transaction-category component participates in equality.
     *
     * :output: passes when changing only ``tranCatCd`` yields an unequal key.
     */
    @Test
    @DisplayName("keys differ when tranCatCd differs")
    void differsWhenTranCatCdDiffers() {
        TranCatgId base = newId("01", 1001);
        assertThat(newId("01", 2002)).isNotEqualTo(base);
    }

    /**
     * Assert equality rejects ``null`` and instances of unrelated types.
     *
     * :output: passes when ``equals`` returns ``false`` for ``null``, a ``String``, a bare
     *     ``Object``, and a different composite-key type ({@link TranCatBalId}).
     */
    @Test
    @DisplayName("equals rejects null and other types")
    void notEqualToNullOrOtherType() {
        TranCatgId base = newId("01", 1001);

        assertThat(base.equals(null)).as("equals(null) must be false").isFalse();
        assertThat(base.equals("01")).as("equals(String) must be false").isFalse();
        assertThat(base.equals(new Object())).as("equals(Object) must be false").isFalse();
        assertThat(base.equals(new TranCatBalId()))
                .as("equals(TranCatBalId) must be false — different @IdClass type")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // Phase 4 — @IdClass ↔ entity consistency
    // ---------------------------------------------------------------------

    /**
     * Assert the entity binds this key class and mirrors its fields as ``@Id`` members.
     *
     * :output: passes when {@link TranCatg} is annotated ``@IdClass(TranCatgId.class)`` and
     *     declares matching ``tranTypeCd``/``tranCatCd`` fields, each ``@Id`` with the
     *     expected type.
     */
    @Test
    @DisplayName("TranCatg @IdClass binds TranCatgId and mirrors its @Id fields")
    void keyFieldsMatchEntityIdFields() throws NoSuchFieldException {
        IdClass idClass = TranCatg.class.getAnnotation(IdClass.class);
        assertThat(idClass)
                .as("TranCatg must be annotated with @IdClass")
                .isNotNull();
        assertThat(idClass.value())
                .as("TranCatg @IdClass must reference TranCatgId")
                .isEqualTo(TranCatgId.class);

        Map<String, Class<?>> keyFields = new LinkedHashMap<>();
        keyFields.put("tranTypeCd", String.class);
        keyFields.put("tranCatCd", Integer.class);

        for (Map.Entry<String, Class<?>> entry : keyFields.entrySet()) {
            Field field = TranCatg.class.getDeclaredField(entry.getKey());
            assertThat(field.getType())
                    .as("TranCatg field '%s' must have type %s", entry.getKey(), entry.getValue())
                    .isEqualTo(entry.getValue());
            assertThat(field.isAnnotationPresent(Id.class))
                    .as("TranCatg field '%s' must be annotated @Id", entry.getKey())
                    .isTrue();
        }
    }

    /**
     * Assert the entity declares exactly two ``@Id`` fields, matching the key arity.
     *
     * :output: passes when {@link TranCatg} has exactly two ``@Id``-annotated fields.
     */
    @Test
    @DisplayName("TranCatg declares exactly two @Id fields")
    void entityDeclaresExactlyTwoIdFields() {
        long idFieldCount = Arrays.stream(TranCatg.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Id.class))
                .count();

        assertThat(idFieldCount)
                .as("TranCatg must declare exactly two @Id fields to match TranCatgId's arity")
                .isEqualTo(2L);
    }
}
