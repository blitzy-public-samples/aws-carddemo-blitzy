package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reflection-based structural contract test for the {@link DiscGroup} JPA entity.
 *
 * :purpose: Lock the migration-critical structure of the legacy COBOL
 *     ``DIS-GROUP-RECORD`` layout (copybook ``CVTRA02Y``, RECLN 50) as re-expressed
 *     by the {@code DiscGroup} entity. The three-part ``DIS-GROUP-KEY``
 *     (``DIS-ACCT-GROUP-ID``, ``DIS-TRAN-TYPE-CD``, ``DIS-TRAN-CAT-CD``) must form a
 *     composite primary key bound through {@code @IdClass(DiscGroupId.class)} with
 *     exactly three ``@Id`` fields, and the disclosure interest rate
 *     ``DIS-INT-RATE`` (``PIC S9(04)V99``) must stay ``java.math.BigDecimal`` backed
 *     by ``NUMERIC(6,2)`` (``@Column`` precision 6, scale 2). That scale fidelity is
 *     essential because ``disIntRate`` is the ``DIS-INT-RATE`` operand of the batch
 *     monthly-interest formula ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200``; any drift in
 *     precision or scale would break byte-identical financial output.
 * :output: JUnit 5 / AssertJ assertions only. The class holds no state and inspects
 *     {@code DiscGroup} purely through {@code java.lang.reflect} against the compiled
 *     class metadata; it opens no database, Spring context, Testcontainers instance,
 *     or other external resource. {@code DiscGroup} and {@code DiscGroupId} live in
 *     the same package and are referenced without an import.
 */
final class DiscGroupTest {

    /**
     * Resolve a declared field of {@link DiscGroup} by name.
     *
     * :param name: the exact declared-field name to resolve (for example
     *     ``"disIntRate"``).
     * :return: the reflected {@link Field}; a missing field fails the test with a
     *     descriptive {@link AssertionError} rather than surfacing a checked exception.
     */
    private static Field field(String name) {
        try {
            return DiscGroup.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("DiscGroup is missing expected field '" + name + "'", e);
        }
    }

    /**
     * Resolve the {@link Column} annotation declared on a named field of
     * {@link DiscGroup}.
     *
     * :param name: the declared-field name whose ``@Column`` is required.
     * :return: the non-null {@link Column} annotation; the test fails if the field is
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
     * Collect the non-static, non-synthetic instance fields of {@link DiscGroup}.
     *
     * :return: the persistent instance fields, excluding compiler/coverage synthetics
     *     and any static members.
     */
    private static List<Field> instanceFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : DiscGroup.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                fields.add(f);
            }
        }
        return fields;
    }

    /**
     * Collect the names of the {@link DiscGroup} instance fields annotated with
     * {@code @Id}.
     *
     * :return: the ``@Id`` field names in declaration order (an insertion-ordered set).
     */
    private static Set<String> idFieldNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Field f : instanceFields()) {
            if (f.isAnnotationPresent(Id.class)) {
                names.add(f.getName());
            }
        }
        return names;
    }

    @Test
    @DisplayName("DiscGroup is a JPA @Entity")
    void isJpaEntity() {
        assertThat(DiscGroup.class.isAnnotationPresent(Entity.class))
                .as("DiscGroup must be annotated with @Entity")
                .isTrue();
    }

    @Test
    @DisplayName("DiscGroup maps to the \"disclosure_group\" table")
    void mapsToDisclosureGroupTable() {
        Table table = DiscGroup.class.getAnnotation(Table.class);
        assertThat(table).as("DiscGroup must be annotated with @Table").isNotNull();
        assertThat(table.name())
                .as("DiscGroup must map to the \"disclosure_group\" table")
                .isEqualTo("disclosure_group");
    }

    @Test
    @DisplayName("DiscGroup uses @IdClass(DiscGroupId.class)")
    void usesDiscGroupIdIdClass() {
        IdClass idClass = DiscGroup.class.getAnnotation(IdClass.class);
        assertThat(idClass).as("DiscGroup must be annotated with @IdClass").isNotNull();
        assertThat(idClass.value())
                .as("the @IdClass must reference the DiscGroupId composite-key class")
                .isEqualTo(DiscGroupId.class);
    }

    @Test
    @DisplayName("DiscGroup declares exactly three @Id fields with the frozen names and types")
    void hasExactlyThreeIdFields() {
        assertThat(idFieldNames())
                .as("DiscGroup must declare exactly the three DIS-GROUP-KEY components as @Id")
                .containsExactlyInAnyOrder("disAcctGroupId", "disTranTypeCd", "disTranCatCd")
                .hasSize(3);
        assertThat(field("disAcctGroupId").getType())
                .as("disAcctGroupId (COBOL DIS-ACCT-GROUP-ID PIC X(10)) must be java.lang.String")
                .isEqualTo(String.class);
        assertThat(field("disTranTypeCd").getType())
                .as("disTranTypeCd (COBOL DIS-TRAN-TYPE-CD PIC X(02)) must be java.lang.String")
                .isEqualTo(String.class);
        assertThat(field("disTranCatCd").getType())
                .as("disTranCatCd (COBOL DIS-TRAN-CAT-CD PIC 9(04)) must be java.lang.Integer")
                .isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("disIntRate is java.math.BigDecimal")
    void disIntRateIsBigDecimal() {
        assertThat(field("disIntRate").getType())
                .as("disIntRate (COBOL DIS-INT-RATE PIC S9(04)V99) must be java.math.BigDecimal")
                .isEqualTo(BigDecimal.class);
    }

    @Test
    @DisplayName("disIntRate declares @Column(precision = 6, scale = 2)")
    void disIntRateHasNumeric6Scale2() {
        Column col = column("disIntRate");
        assertThat(col.precision())
                .as("disIntRate must have precision 6 (COBOL S9(04)V99 -> NUMERIC(6,2))")
                .isEqualTo(6);
        assertThat(col.scale())
                .as("disIntRate must have scale 2 (COBOL S9(04)V99 -> NUMERIC(6,2))")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("No floating-point fields exist anywhere on DiscGroup")
    void noFloatingPointFieldsAnywhere() {
        for (Field f : instanceFields()) {
            assertThat(f.getType())
                    .as("field '%s' must not use a lossy floating-point type", f.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }
}
