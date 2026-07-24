package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the {@code TranCatg} JPA mapping contract.
 *
 * :purpose: Freeze the object-relational mapping that migrates the legacy COBOL
 *     ``TRAN-CAT-RECORD`` copybook (``CVTRA04Y``, RECLN 60) onto the transaction-category
 *     reference entity. The record declares the two-part ``TRAN-CAT-KEY`` group --
 *     ``TRAN-TYPE-CD PIC X(02)`` and ``TRAN-CAT-CD PIC 9(04)`` -- followed by
 *     ``TRAN-CAT-TYPE-DESC PIC X(50)``; the trailing ``FILLER PIC X(04)`` is intentionally
 *     not persisted. These assertions lock the ``@Entity`` stereotype, the ``tran_category``
 *     table name, the two-field ``@IdClass(TranCatgId.class)`` composite key, and the
 *     length-50 description column. The decisive guard proves the key contains exactly the
 *     two components ``tranTypeCd`` and ``tranCatCd`` and no account identifier -- the
 *     feature that distinguishes this two-field key from the three-field ``TranCatBal`` /
 *     ``TranCatBalId`` shape.
 * :output: JUnit 5 / AssertJ assertions only, driven purely by JDK reflection over
 *     ``TranCatg`` and its RUNTIME-retained ``jakarta.persistence`` annotations; the class
 *     holds no state and touches no database, Spring context, Testcontainers, or other
 *     external resource.
 */
final class TranCatgTest {

    /**
     * Resolve a declared field of {@code TranCatg} by name.
     *
     * :param name: the declared field name to resolve (for example ``"tranTypeCd"``).
     * :returns: the reflected {@link java.lang.reflect.Field}; a missing field fails the test
     *     with a descriptive {@link AssertionError} rather than surfacing a checked exception.
     */
    private static Field field(String name) {
        try {
            return TranCatg.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("TranCatg must declare a field named '" + name + "'", e);
        }
    }

    /**
     * Resolve the {@code @Column} mapping of a declared field of {@code TranCatg}.
     *
     * :param name: the declared field name whose column mapping is required.
     * :returns: the non-null {@link jakarta.persistence.Column} annotation on that field.
     */
    private static Column column(String name) {
        Column mapping = field(name).getAnnotation(Column.class);
        assertThat(mapping)
                .as("field '%s' must carry a @Column mapping", name)
                .isNotNull();
        return mapping;
    }

    /**
     * Report whether {@code TranCatg} declares a field with the given name.
     *
     * :param name: the candidate declared field name.
     * :returns: ``true`` when the field exists, ``false`` otherwise; used to prove the
     *     absence of the account-identifier key component.
     */
    private static boolean fieldExists(String name) {
        try {
            TranCatg.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    /**
     * Collect the names of every {@code @Id}-annotated instance field of {@code TranCatg}.
     *
     * :returns: the set of declared field names carrying {@link jakarta.persistence.Id};
     *     synthetic and static fields are ignored so only the real key components remain.
     */
    private static Set<String> idFieldNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Field declared : TranCatg.class.getDeclaredFields()) {
            if (declared.isSynthetic() || Modifier.isStatic(declared.getModifiers())) {
                continue;
            }
            if (declared.isAnnotationPresent(Id.class)) {
                names.add(declared.getName());
            }
        }
        return names;
    }

    /**
     * Assert that {@code TranCatg} is a JPA-managed entity.
     */
    @Test
    @DisplayName("TranCatg is a JPA @Entity")
    void isJpaEntity() {
        assertThat(TranCatg.class.isAnnotationPresent(Entity.class))
                .as("TranCatg must be annotated with @Entity")
                .isTrue();
    }

    /**
     * Assert that {@code TranCatg} maps onto the ``tran_category`` relational table.
     */
    @Test
    @DisplayName("TranCatg maps to the tran_category table")
    void mapsToTranCategoryTable() {
        Table table = TranCatg.class.getAnnotation(Table.class);
        assertThat(table)
                .as("TranCatg must declare a @Table mapping")
                .isNotNull();
        assertThat(table.name())
                .as("TranCatg must map to the tran_category table")
                .isEqualTo("tran_category");
    }

    /**
     * Assert that the compound identifier is bound through ``@IdClass(TranCatgId.class)``,
     * mirroring the COBOL ``TRAN-CAT-KEY`` group.
     */
    @Test
    @DisplayName("TranCatg binds the composite key through @IdClass(TranCatgId.class)")
    void usesTranCatgIdIdClass() {
        IdClass idClass = TranCatg.class.getAnnotation(IdClass.class);
        assertThat(idClass)
                .as("TranCatg must declare an @IdClass for its composite key")
                .isNotNull();
        assertThat(idClass.value())
                .as("TranCatg @IdClass must reference TranCatgId")
                .isEqualTo(TranCatgId.class);
    }

    /**
     * Assert the composite key comprises exactly the two components ``tranTypeCd``
     * (``String``, ``TRAN-TYPE-CD PIC X(02)``) and ``tranCatCd`` (``Integer``,
     * ``TRAN-CAT-CD PIC 9(04)``).
     */
    @Test
    @DisplayName("composite key is exactly {tranTypeCd:String, tranCatCd:Integer}")
    void hasExactlyTwoIdFields() {
        assertThat(idFieldNames())
                .as("TranCatg key must contain exactly the two TRAN-CAT-KEY components")
                .containsExactlyInAnyOrder("tranTypeCd", "tranCatCd")
                .hasSize(2);
        assertThat(field("tranTypeCd").getType())
                .as("tranTypeCd must map TRAN-TYPE-CD PIC X(02) to String")
                .isEqualTo(String.class);
        assertThat(field("tranCatCd").getType())
                .as("tranCatCd must map TRAN-CAT-CD PIC 9(04) to Integer")
                .isEqualTo(Integer.class);
    }

    /**
     * Assert the key does not carry an account identifier. This is the guard that
     * distinguishes the two-field ``TranCatg`` key from the three-field ``TranCatBal`` /
     * ``TranCatBalId`` shape, whose key adds ``trancatAcctId``.
     */
    @Test
    @DisplayName("key has no account-id component (distinct from 3-field TranCatBal)")
    void hasNoAccountIdInKey() {
        assertThat(fieldExists("trancatAcctId"))
                .as("TranCatg key must not include an account identifier; that is the TranCatBal shape")
                .isFalse();
    }

    /**
     * Assert the description mapping for ``TRAN-CAT-TYPE-DESC PIC X(50)``.
     */
    @Test
    @DisplayName("tranCatTypeDesc is a length-50 String column")
    void descriptionIsString50() {
        assertThat(field("tranCatTypeDesc").getType())
                .as("tranCatTypeDesc must map TRAN-CAT-TYPE-DESC PIC X(50) to String")
                .isEqualTo(String.class);
        assertThat(column("tranCatTypeDesc").length())
                .as("tranCatTypeDesc column length must equal the 50-character COBOL width")
                .isEqualTo(50);
    }

    /**
     * Assert that {@code TranCatg} exposes the public no-argument constructor the JPA provider
     * requires (on a non-final entity class) and that its accessors round-trip both key
     * components and the description.
     *
     * :raises ReflectiveOperationException: if the no-argument constructor cannot be resolved
     *     or invoked, which itself fails the contract.
     */
    @Test
    @DisplayName("public no-arg constructor and accessors round-trip the mapped fields")
    void hasPublicNoArgConstructor() throws ReflectiveOperationException {
        Constructor<TranCatg> constructor = TranCatg.class.getDeclaredConstructor();
        assertThat(Modifier.isPublic(constructor.getModifiers()))
                .as("TranCatg must expose a public no-arg constructor for the JPA provider")
                .isTrue();
        assertThat(Modifier.isFinal(TranCatg.class.getModifiers()))
                .as("a JPA @Entity class must not be final")
                .isFalse();

        TranCatg category = constructor.newInstance();
        category.setTranTypeCd("01");
        category.setTranCatCd(1);
        category.setTranCatTypeDesc("Purchase");

        assertThat(category.getTranTypeCd()).isEqualTo("01");
        assertThat(category.getTranCatCd()).isEqualTo(1);
        assertThat(category.getTranCatTypeDesc()).isEqualTo("Purchase");
    }
}
