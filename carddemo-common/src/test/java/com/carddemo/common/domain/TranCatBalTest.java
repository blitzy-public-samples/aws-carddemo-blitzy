package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the {@link TranCatBal} JPA mapping contract.
 *
 * :purpose: Freeze the object-relational mapping that migrates the legacy COBOL
 *     ``TRAN-CAT-BAL-RECORD`` copybook (``app/cpy/CVTRA01Y.cpy``, RECLN 50) onto the
 *     transaction-category-balance entity. Two invariants are locked. First, the
 *     three-column composite key of the COBOL ``TRAN-CAT-KEY`` group
 *     (``TRANCAT-ACCT-ID`` 9(11), ``TRANCAT-TYPE-CD`` X(02), ``TRANCAT-CD`` 9(04)) is
 *     expressed through ``@IdClass(TranCatBalId.class)`` with exactly three ``@Id``
 *     fields — contrasting with the two-field key of {@code TranCatgId}. Second, the
 *     ``TRAN-CAT-BAL PIC S9(09)V99`` monetary field is carried by
 *     {@link java.math.BigDecimal} on a ``NUMERIC(11,2)`` column, and no field anywhere is
 *     a binary floating-point type, so the monthly-interest multiplicand
 *     ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` reproduces byte-identical financial output
 *     (AAP 0.6.1).
 * :output: JUnit 5 / AssertJ assertions only; the class holds no state and touches no
 *     database, Spring context, Testcontainers, or other external resource. It inspects
 *     the entity purely through ``java.lang.reflect`` against the compiled class metadata.
 */
final class TranCatBalTest {

    /** The three composite-key field names expected on {@link TranCatBal}. */
    private static final Set<String> EXPECTED_ID_FIELDS =
            Set.of("trancatAcctId", "trancatTypeCd", "trancatCd");

    /**
     * Resolve a declared field of {@link TranCatBal} by name.
     *
     * :param name: the declared field name to resolve (for example ``"tranCatBal"``).
     * :output: the reflected {@link Field}; a missing field fails the test with a
     *     descriptive {@link AssertionError} rather than surfacing a checked exception.
     */
    private static Field field(String name) {
        try {
            return TranCatBal.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Expected TranCatBal to declare field '" + name + "'", e);
        }
    }

    /**
     * Resolve the {@link Column} annotation on a declared field of {@link TranCatBal}.
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
     * Collect the instance (non-static, non-synthetic) declared fields of {@link TranCatBal}.
     *
     * :output: the modeled state fields, excluding compiler/coverage synthetics (for
     *     example a JaCoCo ``$jacocoData`` field) and static members, so the field-set
     *     assertions inspect only the real mapped state.
     */
    private static List<Field> instanceFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : TranCatBal.class.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    /**
     * Collect the names of the instance fields annotated with ``@jakarta.persistence.Id``.
     *
     * :output: the set of primary-key field names declared on {@link TranCatBal}; used to
     *     assert the exact three-field composite-key shape.
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

    /**
     * Assert that {@link TranCatBal} is a JPA-managed entity.
     *
     * :output: passes when the class carries the ``@Entity`` stereotype.
     */
    @Test
    @DisplayName("TranCatBal is a JPA @Entity")
    void isJpaEntity() {
        assertThat(TranCatBal.class.isAnnotationPresent(Entity.class))
                .as("TranCatBal must be annotated with @Entity")
                .isTrue();
    }

    /**
     * Assert that {@link TranCatBal} maps to the ``tran_cat_bal`` relational table.
     *
     * :output: passes when ``@Table`` is present and its ``name`` is exactly
     *     ``"tran_cat_bal"``.
     */
    @Test
    @DisplayName("TranCatBal maps to the tran_cat_bal table")
    void mapsToTranCatBalTable() {
        Table table = TranCatBal.class.getAnnotation(Table.class);
        assertThat(table)
                .as("TranCatBal must be annotated with @Table")
                .isNotNull();
        assertThat(table.name()).isEqualTo("tran_cat_bal");
    }

    /**
     * Assert that {@link TranCatBal} declares its composite key through
     * ``@IdClass(TranCatBalId.class)``.
     *
     * :output: passes when ``@IdClass`` is present and its ``value()`` is exactly
     *     {@link TranCatBalId}.
     */
    @Test
    @DisplayName("TranCatBal uses @IdClass(TranCatBalId.class)")
    void usesTranCatBalIdIdClass() {
        assertThat(TranCatBal.class.isAnnotationPresent(IdClass.class))
                .as("TranCatBal must declare a composite key via @IdClass")
                .isTrue();
        assertThat(TranCatBal.class.getAnnotation(IdClass.class).value())
                .as("@IdClass value must be TranCatBalId")
                .isEqualTo(TranCatBalId.class);
    }

    /**
     * Assert the exact three-field composite key of the COBOL ``TRAN-CAT-KEY`` group.
     *
     * :output: passes when exactly the three fields ``trancatAcctId``,
     *     ``trancatTypeCd`` and ``trancatCd`` are annotated ``@Id`` (size 3), each with
     *     its frozen Java type: ``trancatAcctId`` a {@link Long} (``TRANCAT-ACCT-ID``
     *     9(11)), ``trancatTypeCd`` a {@link String} (``TRANCAT-TYPE-CD`` X(02)) and
     *     ``trancatCd`` an {@link Integer} (``TRANCAT-CD`` 9(04)).
     */
    @Test
    @DisplayName("TranCatBal has exactly three @Id fields with the expected key types")
    void hasExactlyThreeIdFields() {
        Set<String> idNames = idFieldNames();
        assertThat(idNames)
                .as("TranCatBal must declare exactly the three-part composite key")
                .hasSize(3)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_ID_FIELDS);

        assertThat(field("trancatAcctId").getType())
                .as("trancatAcctId maps TRANCAT-ACCT-ID PIC 9(11)")
                .isEqualTo(Long.class);
        assertThat(field("trancatTypeCd").getType())
                .as("trancatTypeCd maps TRANCAT-TYPE-CD PIC X(02)")
                .isEqualTo(String.class);
        assertThat(field("trancatCd").getType())
                .as("trancatCd maps TRANCAT-CD PIC 9(04)")
                .isEqualTo(Integer.class);
    }

    /**
     * Assert that the monetary balance is carried by {@link java.math.BigDecimal}.
     *
     * :output: passes when ``tranCatBal`` is typed as {@link BigDecimal}, preserving the
     *     COBOL packed-decimal (``COMP-3``) precision required by AAP 0.6.1.
     */
    @Test
    @DisplayName("tranCatBal is a java.math.BigDecimal (TRAN-CAT-BAL S9(09)V99)")
    void tranCatBalIsBigDecimal() {
        assertThat(field("tranCatBal").getType())
                .as("TRAN-CAT-BAL must be BigDecimal to preserve packed-decimal precision")
                .isEqualTo(BigDecimal.class);
    }

    /**
     * Assert the ``NUMERIC(11,2)`` column mapping for ``TRAN-CAT-BAL PIC S9(09)V99``.
     *
     * :output: passes when the ``@Column`` on ``tranCatBal`` declares ``precision`` 11 and
     *     ``scale`` 2, matching the nine integer plus two fractional COBOL digits.
     */
    @Test
    @DisplayName("tranCatBal maps to NUMERIC(11,2) (precision 11, scale 2)")
    void tranCatBalHasNumeric11Scale2() {
        Column column = column("tranCatBal");
        assertThat(column.precision())
                .as("S9(09)V99 -> precision 11")
                .isEqualTo(11);
        assertThat(column.scale())
                .as("S9(09)V99 -> scale 2")
                .isEqualTo(2);
    }

    /**
     * Assert that no mapped field uses a binary floating-point type.
     *
     * :output: passes when no instance field is ``double``, ``float``, {@link Double} or
     *     {@link Float}; monetary values must never be represented in binary floating
     *     point, which would break byte-identical financial output.
     */
    @Test
    @DisplayName("No field anywhere is a binary floating-point type (double/float/Double/Float)")
    void noFloatingPointFieldsAnywhere() {
        for (Field f : instanceFields()) {
            assertThat(f.getType())
                    .as("field '%s' must not be a binary floating-point type", f.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }
}
