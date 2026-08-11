package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the {@link DiscGroupId} JPA composite-key contract.
 *
 * :purpose: Freeze the three-part disclosure-group identity migrated from the COBOL
 *     ``DIS-GROUP-KEY`` group of ``DIS-GROUP-RECORD`` (copybook ``CVTRA02Y``, RECLN 50):
 *     ``DIS-ACCT-GROUP-ID PIC X(10)`` -> {@code disAcctGroupId} (String),
 *     ``DIS-TRAN-TYPE-CD PIC X(02)`` -> {@code disTranTypeCd} (String), and
 *     ``DIS-TRAN-CAT-CD PIC 9(04)`` -> {@code disTranCatCd} (Integer). The assertions
 *     lock the ``@IdClass`` requirements (serializable identity, serialVersionUID 1,
 *     exactly the three verbatim key fields, the public no-arg and all-args
 *     constructors, and a value-based ``equals``/``hashCode``) together with the
 *     ``@IdClass(DiscGroupId.class)`` binding on the {@link DiscGroup} entity, so the
 *     frozen identifier contract of AAP 0.6.8 fails the build on any regression.
 * :output: JUnit 5 / AssertJ assertions only, driven purely by JDK reflection and the
 *     compiled public API of {@link DiscGroupId} and {@link DiscGroup}; the class holds
 *     no state and touches no database, Spring context, or Testcontainers resource.
 */
final class DiscGroupIdTest {

    /**
     * Build a fully populated identity through the compiled all-args constructor.
     *
     * :param g: account group id key component (``DIS-ACCT-GROUP-ID``).
     * :param t: transaction type code key component (``DIS-TRAN-TYPE-CD``).
     * :param c: transaction category code key component (``DIS-TRAN-CAT-CD``).
     * :returns: a new {@link DiscGroupId} populated with the supplied key components.
     */
    private static DiscGroupId newId(String g, String t, Integer c) {
        return new DiscGroupId(g, t, c);
    }

    /**
     * Reduce a list of parameter types to an order-independent frequency multiset.
     *
     * :param types: the declared parameter types of a constructor.
     * :returns: a map from each type to its occurrence count, so two ``String``
     *     parameters are distinguished from one without depending on declaration order.
     */
    private static Map<Class<?>, Long> typeMultiset(Class<?>[] types) {
        Map<Class<?>, Long> counts = new HashMap<>();
        for (Class<?> type : types) {
            counts.merge(type, 1L, Long::sum);
        }
        return counts;
    }

    /**
     * Assert that a {@link DiscGroup} key field exists, has the expected type, and is
     * annotated {@code @Id}.
     *
     * :param name: the declared field name on {@link DiscGroup}.
     * :param expectedType: the type the field must declare.
     */
    private static void assertEntityIdField(String name, Class<?> expectedType) {
        Field entityField;
        try {
            entityField = DiscGroup.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("DiscGroup must declare an @Id field named '" + name + "'", e);
        }
        assertThat(entityField.getType())
                .as("DiscGroup.%s must have type %s", name, expectedType.getSimpleName())
                .isEqualTo(expectedType);
        assertThat(entityField.isAnnotationPresent(Id.class))
                .as("DiscGroup.%s must be annotated with @Id", name)
                .isTrue();
    }

    /**
     * Assert that {@link DiscGroupId} is serializable, as JPA requires of an
     * {@code @IdClass}.
     */
    @Test
    @DisplayName("DiscGroupId implements java.io.Serializable")
    void implementsSerializable() {
        assertThat(Serializable.class.isAssignableFrom(DiscGroupId.class))
                .as("DiscGroupId must implement java.io.Serializable")
                .isTrue();
    }

    /**
     * Assert that the declared serial version UID is exactly {@code 1L}.
     */
    @Test
    @DisplayName("serialVersionUID is 1L")
    void serialVersionUidIsOne() {
        ObjectStreamClass descriptor = ObjectStreamClass.lookup(DiscGroupId.class);
        assertThat(descriptor)
                .as("DiscGroupId must be a serializable class")
                .isNotNull();
        assertThat(descriptor.getSerialVersionUID())
                .as("DiscGroupId.serialVersionUID must be 1L")
                .isEqualTo(1L);
    }

    /**
     * Assert that the identity declares exactly the three verbatim key fields with the
     * required types and no additional instance state.
     */
    @Test
    @DisplayName("declares exactly the three key fields with verbatim names and types")
    void hasExactlyThreeKeyFields() {
        List<Field> instanceFields = new ArrayList<>();
        Map<String, Class<?>> byNameToType = new HashMap<>();
        for (Field declared : DiscGroupId.class.getDeclaredFields()) {
            if (declared.isSynthetic() || Modifier.isStatic(declared.getModifiers())) {
                continue;
            }
            instanceFields.add(declared);
            byNameToType.put(declared.getName(), declared.getType());
        }

        assertThat(instanceFields)
                .as("DiscGroupId must declare exactly three non-static, non-synthetic key fields")
                .hasSize(3);

        Map<String, Class<?>> expected = new HashMap<>();
        expected.put("disAcctGroupId", String.class);
        expected.put("disTranTypeCd", String.class);
        expected.put("disTranCatCd", Integer.class);
        assertThat(byNameToType)
                .as("key field names and types must match the frozen DIS-GROUP-KEY contract")
                .isEqualTo(expected);
    }

    /**
     * Assert the public no-argument constructor the JPA provider requires is present.
     *
     * :raises NoSuchMethodException: if the no-argument constructor is absent, failing
     *     the {@code @IdClass} contract.
     */
    @Test
    @DisplayName("declares a public no-arg constructor for the JPA provider")
    void hasNoArgConstructor() throws NoSuchMethodException {
        Constructor<DiscGroupId> noArg = DiscGroupId.class.getDeclaredConstructor();
        assertThat(Modifier.isPublic(noArg.getModifiers()))
                .as("DiscGroupId must expose a public no-arg constructor")
                .isTrue();
    }

    /**
     * Assert an all-args constructor accepting {@code (String, String, Integer)} exists,
     * matching on the parameter-type multiset to stay resilient to declaration order.
     */
    @Test
    @DisplayName("declares an all-args (String, String, Integer) constructor")
    void hasAllArgsConstructor() {
        Map<Class<?>, Long> expected =
                typeMultiset(new Class<?>[] {String.class, String.class, Integer.class});

        boolean found = false;
        for (Constructor<?> constructor : DiscGroupId.class.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 3
                    && typeMultiset(constructor.getParameterTypes()).equals(expected)) {
                found = true;
                break;
            }
        }

        assertThat(found)
                .as("DiscGroupId must declare a 3-arg constructor of {String, String, Integer}")
                .isTrue();
    }

    /**
     * Assert that {@code equals} is reflexive.
     */
    @Test
    @DisplayName("equals is reflexive")
    void equalsIsReflexive() {
        DiscGroupId base = newId("GRP0000001", "01", 1001);
        assertThat(base).isEqualTo(base);
    }

    /**
     * Assert that two independently constructed identities with the same key components
     * are equal, symmetric, and distinct instances.
     */
    @Test
    @DisplayName("equal identities are symmetric and not the same instance")
    void equalObjectsAreSymmetricButNotSame() {
        DiscGroupId base = newId("GRP0000001", "01", 1001);
        DiscGroupId other = newId("GRP0000001", "01", 1001);

        assertThat(other).isNotSameAs(base);
        assertThat(base).isEqualTo(other);
        assertThat(other).isEqualTo(base);
    }

    /**
     * Assert that equal identities produce equal hash codes.
     */
    @Test
    @DisplayName("equal identities have equal hash codes")
    void equalObjectsHaveEqualHashCode() {
        DiscGroupId base = newId("GRP0000001", "01", 1001);
        DiscGroupId other = newId("GRP0000001", "01", 1001);

        assertThat(other.hashCode())
                .as("equal DiscGroupId instances must share a hash code")
                .isEqualTo(base.hashCode());
    }

    /**
     * Assert that {@code hashCode} is stable across repeated invocations.
     */
    @Test
    @DisplayName("hashCode is consistent across invocations")
    void hashCodeIsConsistent() {
        DiscGroupId base = newId("GRP0000001", "01", 1001);
        assertThat(base.hashCode()).isEqualTo(base.hashCode());
    }

    /**
     * Assert that identities differing only in the account group id are unequal.
     */
    @Test
    @DisplayName("differs when disAcctGroupId differs")
    void differsWhenGroupIdDiffers() {
        DiscGroupId base = newId("GRP0000001", "01", 1001);
        DiscGroupId different = newId("GRP0000002", "01", 1001);
        assertThat(different).isNotEqualTo(base);
    }

    /**
     * Assert that identities differing only in the transaction type code are unequal.
     */
    @Test
    @DisplayName("differs when disTranTypeCd differs")
    void differsWhenTranTypeCdDiffers() {
        DiscGroupId base = newId("GRP0000001", "01", 1001);
        DiscGroupId different = newId("GRP0000001", "02", 1001);
        assertThat(different).isNotEqualTo(base);
    }

    /**
     * Assert that identities differing only in the transaction category code are unequal.
     */
    @Test
    @DisplayName("differs when disTranCatCd differs")
    void differsWhenTranCatCdDiffers() {
        DiscGroupId base = newId("GRP0000001", "01", 1001);
        DiscGroupId different = newId("GRP0000001", "01", 2002);
        assertThat(different).isNotEqualTo(base);
    }

    /**
     * Assert that an identity is unequal to {@code null} and to instances of other types.
     */
    @Test
    @DisplayName("is not equal to null or to other types")
    void notEqualToNullOrOtherType() {
        DiscGroupId base = newId("GRP0000001", "01", 1001);
        assertThat(base.equals(null))
                .as("DiscGroupId must not equal null")
                .isFalse();
        assertThat(base.equals("GRP0000001"))
                .as("DiscGroupId must not equal a String")
                .isFalse();
        assertThat(base.equals(new Object()))
                .as("DiscGroupId must not equal an unrelated Object")
                .isFalse();
    }

    /**
     * Assert that the {@link DiscGroup} entity binds this key class and that each key
     * field mirrors an {@code @Id}-annotated field of the matching type on the entity.
     */
    @Test
    @DisplayName("@IdClass and entity @Id fields match the composite key")
    void keyFieldsMatchEntityIdFields() {
        IdClass idClass = DiscGroup.class.getAnnotation(IdClass.class);
        assertThat(idClass)
                .as("DiscGroup must be annotated with @IdClass")
                .isNotNull();
        assertThat(idClass.value())
                .as("DiscGroup @IdClass value must be DiscGroupId")
                .isEqualTo(DiscGroupId.class);

        assertEntityIdField("disAcctGroupId", String.class);
        assertEntityIdField("disTranTypeCd", String.class);
        assertEntityIdField("disTranCatCd", Integer.class);
    }
}
