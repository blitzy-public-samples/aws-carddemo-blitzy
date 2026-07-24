package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the {@link TranType} JPA mapping contract.
 *
 * :purpose: Freeze the object-relational mapping that migrates the legacy COBOL
 *     ``TRAN-TYPE-RECORD`` copybook (``CVTRA03Y``, RECLN 60) onto the transaction-type
 *     reference entity. The record declares ``TRAN-TYPE PIC X(02)`` (the single-column
 *     primary key) and ``TRAN-TYPE-DESC PIC X(50)`` (the description); the trailing
 *     ``FILLER PIC X(08)`` is intentionally not persisted. These assertions lock the
 *     ``@Entity`` stereotype, the ``tran_type`` table name, the ``@Id`` on ``tranType``,
 *     and the exact ``@Column`` widths (2 and 50) so that any regression in the frozen
 *     column contract fails the build.
 * :output: JUnit 5 / AssertJ assertions only; the class holds no state and touches no
 *     database, Spring context, Testcontainers, or other external resource. It inspects
 *     the entity purely through ``java.lang.reflect`` against the compiled class metadata.
 */
final class TranTypeTest {

    /**
     * Resolve a declared field of {@link TranType} by name.
     *
     * :param name: the declared field name to resolve (for example ``"tranType"``).
     * :output: the reflected {@link Field}; a missing field fails the test with a
     *     descriptive {@link AssertionError} rather than surfacing a checked exception.
     */
    private static Field field(String name) {
        try {
            return TranType.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Expected TranType to declare field '" + name + "'", e);
        }
    }

    /**
     * Resolve the {@link Column} annotation on a declared field of {@link TranType}.
     *
     * :param name: the declared field name whose ``@Column`` is required.
     * :output: the non-null {@link Column} annotation; the test fails if the field is
     *     not mapped with ``@Column``.
     */
    private static Column column(String name) {
        Column column = field(name).getAnnotation(Column.class);
        assertThat(column)
                .as("Field '%s' must be mapped with @Column", name)
                .isNotNull();
        return column;
    }

    /**
     * Assert that {@link TranType} is a JPA-managed entity.
     *
     * :output: passes when the class carries the ``@Entity`` stereotype.
     */
    @Test
    @DisplayName("TranType is a JPA @Entity")
    void isJpaEntity() {
        assertThat(TranType.class.isAnnotationPresent(Entity.class))
                .as("TranType must be annotated with @Entity")
                .isTrue();
    }

    /**
     * Assert that {@link TranType} maps to the ``tran_type`` relational table.
     *
     * :output: passes when ``@Table`` is present and its ``name`` is exactly
     *     ``"tran_type"``.
     */
    @Test
    @DisplayName("TranType maps to the tran_type table")
    void mapsToTranTypeTable() {
        Table table = TranType.class.getAnnotation(Table.class);
        assertThat(table)
                .as("TranType must be annotated with @Table")
                .isNotNull();
        assertThat(table.name()).isEqualTo("tran_type");
    }

    /**
     * Assert the single-column primary key mapping for ``TRAN-TYPE PIC X(02)``.
     *
     * :output: passes when ``tranType`` is annotated ``@Id``, is a {@link String}, and its
     *     ``@Column`` length is exactly 2.
     */
    @Test
    @DisplayName("tranType is the @Id primary key mapped as a length-2 column")
    void tranTypeIsPrimaryKeyLength2() {
        Field tranType = field("tranType");
        assertThat(tranType.isAnnotationPresent(Id.class))
                .as("tranType must be annotated with @Id")
                .isTrue();
        assertThat(tranType.getType()).isEqualTo(String.class);
        assertThat(column("tranType").length()).isEqualTo(2);
    }

    /**
     * Assert the description mapping for ``TRAN-TYPE-DESC PIC X(50)``.
     *
     * :output: passes when ``tranTypeDesc`` is a {@link String} and its ``@Column`` length
     *     is exactly 50.
     */
    @Test
    @DisplayName("tranTypeDesc is a length-50 String column")
    void descriptionIsString50() {
        assertThat(field("tranTypeDesc").getType()).isEqualTo(String.class);
        assertThat(column("tranTypeDesc").length()).isEqualTo(50);
    }

    /**
     * Assert that {@link TranType} exposes the public no-argument constructor JPA requires.
     *
     * :output: passes when a no-arg constructor is declared with ``public`` visibility, so
     *     the persistence provider can instantiate the entity via reflection.
     */
    @Test
    @DisplayName("TranType declares a public no-arg constructor for the JPA provider")
    void hasPublicNoArgConstructor() throws NoSuchMethodException {
        var noArgConstructor = TranType.class.getDeclaredConstructor();
        assertThat(Modifier.isPublic(noArgConstructor.getModifiers()))
                .as("TranType must expose a public no-arg constructor")
                .isTrue();
    }
}
