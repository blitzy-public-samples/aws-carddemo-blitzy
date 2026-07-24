package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the {@link TranCatBalId} JPA composite-key contract.
 *
 * :purpose: Freeze the ``@IdClass`` contract for the transaction-category-balance
 *     composite key that migrates the COBOL ``TRAN-CAT-KEY`` group of
 *     ``TRAN-CAT-BAL-RECORD`` (copybook ``CVTRA01Y``): the three key components
 *     ``TRANCAT-ACCT-ID PIC 9(11)``, ``TRANCAT-TYPE-CD PIC X(02)`` and
 *     ``TRANCAT-CD PIC 9(04)`` become the ``trancatAcctId``/``trancatTypeCd``/
 *     ``trancatCd`` fields of {@link TranCatBalId}. The assertions lock the
 *     ``Serializable`` marker and its ``serialVersionUID``, the exact three key
 *     fields and their types, the no-arg and all-args constructors, the value-based
 *     ``equals``/``hashCode`` behavior, and the field-for-field consistency between
 *     {@link TranCatBalId} and the ``@Id`` fields of {@link TranCatBal}. This
 *     three-field key is deliberately distinct from the two-field ``TranCatgId``.
 * :output: JUnit 5 / AssertJ assertions only, driven purely by JDK reflection and
 *     value comparisons; the class holds no state and touches no database, Spring
 *     context, Testcontainers, or other external resource.
 */
final class TranCatBalIdTest {

    /**
     * Construct a composite key through the real compiled all-args constructor.
     *
     * :param a: the account identifier component (``trancatAcctId``).
     * :param t: the transaction type code component (``trancatTypeCd``).
     * :param c: the transaction category code component (``trancatCd``).
     * :output: a fully populated {@link TranCatBalId} instance.
     */
    private static TranCatBalId newId(Long a, String t, Integer c) {
        return new TranCatBalId(a, t, c);
    }

    // ---------------------------------------------------------------------
    // Structural contract (reflection)
    // ---------------------------------------------------------------------

    /**
     * Assert that {@link TranCatBalId} is serializable, as JPA composite-key
     * classes are required to be.
     *
     * :output: passes when ``java.io.Serializable`` is assignable from the key class.
     */
    @Test
    @DisplayName("TranCatBalId implements java.io.Serializable")
    void implementsSerializable() {
        assertThat(Serializable.class.isAssignableFrom(TranCatBalId.class)).isTrue();
    }

    /**
     * Assert that the declared ``serialVersionUID`` is the frozen value ``1L``.
     *
     * :output: passes when the effective serial version resolved by
     *     {@link ObjectStreamClass} equals ``1L``.
     */
    @Test
    @DisplayName("serialVersionUID is 1L")
    void serialVersionUidIsOne() {
        assertThat(ObjectStreamClass.lookup(TranCatBalId.class).getSerialVersionUID())
                .isEqualTo(1L);
    }

    /**
     * Assert that the key declares exactly the three composite-key fields with the
     * verbatim names and types mandated by the copybook mapping.
     *
     * :output: passes when the non-static, non-synthetic instance fields are exactly
     *     ``trancatAcctId``:``Long``, ``trancatTypeCd``:``String`` and
     *     ``trancatCd``:``Integer`` -- guarding against extra, renamed or mistyped
     *     fields (the static ``serialVersionUID`` and any synthetic coverage field
     *     are excluded).
     */
    @Test
    @DisplayName("declares exactly the three composite-key fields with the correct types")
    void hasExactlyThreeKeyFields() {
        Map<String, Class<?>> instanceFields = new LinkedHashMap<>();
        for (Field f : TranCatBalId.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                instanceFields.put(f.getName(), f.getType());
            }
        }
        assertThat(instanceFields)
                .hasSize(3)
                .containsOnly(
                        entry("trancatAcctId", Long.class),
                        entry("trancatTypeCd", String.class),
                        entry("trancatCd", Integer.class));
    }

    /**
     * Assert that the JPA-required public no-argument constructor is present.
     *
     * :output: passes when resolving the no-arg constructor does not throw, which is
     *     a hard JPA requirement for ``@IdClass`` instantiation.
     */
    @Test
    @DisplayName("declares the JPA-required no-arg constructor")
    void hasNoArgConstructor() {
        assertThatCode(() -> TranCatBalId.class.getDeclaredConstructor())
                .doesNotThrowAnyException();
    }

    /**
     * Assert that an all-args constructor accepting ``Long``, ``String`` and
     * ``Integer`` is present.
     *
     * :output: passes when some declared constructor has exactly three parameters
     *     whose types, compared as an order-independent multiset, equal
     *     ``{Long, String, Integer}`` -- proving the convenience constructor exists
     *     without depending on parameter ordering.
     */
    @Test
    @DisplayName("declares an all-args constructor (Long, String, Integer)")
    void hasAllArgsConstructor() {
        List<String> expected = Arrays.stream(new Class<?>[] {Long.class, String.class, Integer.class})
                .map(Class::getName)
                .sorted()
                .toList();
        boolean present = Arrays.stream(TranCatBalId.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 3)
                .anyMatch(c -> Arrays.stream(c.getParameterTypes())
                        .map(Class::getName)
                        .sorted()
                        .toList()
                        .equals(expected));
        assertThat(present)
                .as("TranCatBalId must declare an all-args constructor (Long, String, Integer)")
                .isTrue();
    }

    // ---------------------------------------------------------------------
    // equals / hashCode behavioral contract
    // ---------------------------------------------------------------------

    /**
     * Assert that ``equals`` is reflexive.
     *
     * :output: passes when a key equals itself.
     */
    @Test
    @DisplayName("equals is reflexive")
    void equalsIsReflexive() {
        TranCatBalId base = newId(11111111111L, "01", 1001);
        assertThat(base).isEqualTo(base);
    }

    /**
     * Assert that two independently constructed keys with identical components are
     * equal and that the relation is symmetric.
     *
     * :output: passes when ``base.equals(other)`` and ``other.equals(base)`` are both
     *     true while the two objects are distinct references.
     */
    @Test
    @DisplayName("equal objects are equal and symmetric")
    void equalObjectsAreEqualAndSymmetric() {
        TranCatBalId base = newId(11111111111L, "01", 1001);
        TranCatBalId other = newId(11111111111L, "01", 1001);
        assertThat(base.equals(other)).isTrue();
        assertThat(other.equals(base)).isTrue();
        assertThat(base).isNotSameAs(other);
    }

    /**
     * Assert that equal keys produce equal hash codes.
     *
     * :output: passes when two value-equal keys share the same ``hashCode``.
     */
    @Test
    @DisplayName("equal objects have equal hashCode")
    void equalObjectsHaveEqualHashCode() {
        TranCatBalId base = newId(11111111111L, "01", 1001);
        TranCatBalId other = newId(11111111111L, "01", 1001);
        assertThat(base.hashCode()).isEqualTo(other.hashCode());
    }

    /**
     * Assert that ``hashCode`` is stable across repeated invocations.
     *
     * :output: passes when a key returns the same ``hashCode`` each time it is called.
     */
    @Test
    @DisplayName("hashCode is consistent across invocations")
    void hashCodeIsConsistent() {
        TranCatBalId base = newId(11111111111L, "01", 1001);
        assertThat(base.hashCode()).isEqualTo(base.hashCode());
    }

    /**
     * Assert that keys differing only in the account identifier are unequal.
     *
     * :output: passes when a differing ``trancatAcctId`` yields inequality.
     */
    @Test
    @DisplayName("differs when the account id differs")
    void differsWhenAcctIdDiffers() {
        TranCatBalId base = newId(11111111111L, "01", 1001);
        assertThat(base).isNotEqualTo(newId(22222222222L, "01", 1001));
    }

    /**
     * Assert that keys differing only in the transaction type code are unequal.
     *
     * :output: passes when a differing ``trancatTypeCd`` yields inequality.
     */
    @Test
    @DisplayName("differs when the transaction type code differs")
    void differsWhenTypeCdDiffers() {
        TranCatBalId base = newId(11111111111L, "01", 1001);
        assertThat(base).isNotEqualTo(newId(11111111111L, "02", 1001));
    }

    /**
     * Assert that keys differing only in the transaction category code are unequal.
     *
     * :output: passes when a differing ``trancatCd`` yields inequality.
     */
    @Test
    @DisplayName("differs when the transaction category code differs")
    void differsWhenCdDiffers() {
        TranCatBalId base = newId(11111111111L, "01", 1001);
        assertThat(base).isNotEqualTo(newId(11111111111L, "01", 2002));
    }

    /**
     * Assert that a key is unequal to ``null`` and to an instance of an unrelated
     * type.
     *
     * :output: passes when comparison against ``null`` and against a ``String`` both
     *     report inequality, exercising the null and type guards of ``equals``.
     */
    @Test
    @DisplayName("not equal to null or a different type")
    void notEqualToNullOrOtherType() {
        TranCatBalId base = newId(11111111111L, "01", 1001);
        assertThat(base).isNotEqualTo(null);
        assertThat(base.equals("x")).isFalse();
    }

    // ---------------------------------------------------------------------
    // @IdClass <-> entity field consistency
    // ---------------------------------------------------------------------

    /**
     * Assert that {@link TranCatBal} declares ``@IdClass(TranCatBalId.class)`` and that
     * each of its ``@Id`` fields mirrors the corresponding key-class field name, type
     * and ``@Id`` annotation.
     *
     * :output: passes when the entity's ``@IdClass`` references {@link TranCatBalId}
     *     and each of ``trancatAcctId``:``Long``, ``trancatTypeCd``:``String`` and
     *     ``trancatCd``:``Integer`` exists on the entity, has the matching type and is
     *     annotated ``@Id`` -- the precondition for the JPA metamodel to resolve the
     *     composite key.
     * :raises NoSuchFieldException: if the entity is missing a mandated key field,
     *     which itself fails the contract.
     */
    @Test
    @DisplayName("key fields match the entity @Id fields")
    void keyFieldsMatchEntityIdFields() throws NoSuchFieldException {
        assertThat(TranCatBal.class.isAnnotationPresent(IdClass.class))
                .as("TranCatBal must be annotated with @IdClass")
                .isTrue();
        assertThat(TranCatBal.class.getAnnotation(IdClass.class).value())
                .as("TranCatBal @IdClass must reference TranCatBalId")
                .isEqualTo(TranCatBalId.class);

        Map<String, Class<?>> expectedIdTypes = Map.of(
                "trancatAcctId", Long.class,
                "trancatTypeCd", String.class,
                "trancatCd", Integer.class);
        for (Map.Entry<String, Class<?>> idField : expectedIdTypes.entrySet()) {
            Field ef = TranCatBal.class.getDeclaredField(idField.getKey());
            assertThat(ef.getType())
                    .as("entity field '%s' must have type %s", idField.getKey(), idField.getValue())
                    .isEqualTo(idField.getValue());
            assertThat(ef.isAnnotationPresent(Id.class))
                    .as("entity field '%s' must be annotated with @Id", idField.getKey())
                    .isTrue();
        }
    }
}
