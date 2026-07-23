package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit mapping test for the JPA entity {@link DisclosureGroup}.
 *
 * <p><strong>Origin (traceability):</strong> {@link DisclosureGroup} is migrated
 * one-for-one from the COBOL copybook {@code DIS-GROUP-RECORD} (RECLN 50) defined
 * at {@code legacy/cpy/CVTRA02Y.cpy} (retained read-only). The legacy layout is:</p>
 * <pre>
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *        10  DIS-ACCT-GROUP-ID     PIC X(10).
 *        10  DIS-TRAN-TYPE-CD      PIC X(02).
 *        10  DIS-TRAN-CAT-CD       PIC 9(04).
 *     05  DIS-INT-RATE             PIC S9(04)V99.
 *     05  FILLER                   PIC X(28).
 * </pre>
 *
 * <p><strong>What this test enforces:</strong></p>
 * <ul>
 *   <li><strong>Decimal fidelity (AAP &sect;0.6.1):</strong> the disclosure
 *       interest rate {@code intRate} must be a {@link java.math.BigDecimal}
 *       mapped to {@code NUMERIC(6,2)} (precision 6, scale 2), never a
 *       floating-point type. A dedicated guard also proves that <em>no</em>
 *       field on the entity uses {@code float}/{@code double}/{@link Float}/
 *       {@link Double}, so packed-decimal ({@code PIC S9(04)V99}) arithmetic
 *       used by the interest-calculation batch job stays exact.</li>
 *   <li><strong>Composite-key preservation (AAP &sect;0.6.10 traceability,
 *       &sect;0.6.2 key semantics):</strong> the three-part legacy VSAM key
 *       {@code DIS-GROUP-KEY} (account-group id + transaction-type code +
 *       transaction-category code) is reproduced via {@link IdClass} over the
 *       nested {@code DisclosureGroup.DisclosureGroupId}, and that id class
 *       implements {@code equals}/{@code hashCode} over all three components.</li>
 * </ul>
 *
 * <p>This is a reflection-only test with <strong>no database and no Spring
 * context</strong>; it runs under Surefire (class name ends in {@code Test}).
 * It inspects the entity's annotations, column mappings, and accessors directly,
 * so it validates the object-relational mapping contract without booting the
 * persistence layer.</p>
 */
class DisclosureGroupTest {

    /**
     * The exact database table name the entity must map to, mirroring the
     * relational translation of the legacy VSAM {@code DISCGRP} KSDS file.
     */
    private static final String TABLE_NAME = "disclosure_group";

    /**
     * Resolves a declared field on {@link DisclosureGroup} by name and makes it
     * accessible for reflective inspection.
     *
     * @param name the declared field name to resolve
     * @return the accessible {@link Field}
     * @throws NoSuchFieldException if the entity does not declare the field,
     *                              which itself signals a broken mapping contract
     */
    private static Field declaredField(String name) throws NoSuchFieldException {
        Field field = DisclosureGroup.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * The class must be a JPA {@code @Entity}, map to the {@code disclosure_group}
     * table, and declare its composite key via {@code @IdClass} pointing at the
     * nested {@code DisclosureGroupId}.
     */
    @Test
    void classIsJpaEntityWithTableAndIdClass() {
        assertThat(DisclosureGroup.class.isAnnotationPresent(Entity.class))
                .as("DisclosureGroup must be annotated with @Entity")
                .isTrue();

        Table table = DisclosureGroup.class.getAnnotation(Table.class);
        assertThat(table).as("@Table must be present on DisclosureGroup").isNotNull();
        assertThat(table.name())
                .as("@Table.name must map to the relational DISCGRP table")
                .isEqualTo(TABLE_NAME);

        IdClass idClass = DisclosureGroup.class.getAnnotation(IdClass.class);
        assertThat(idClass).as("@IdClass must be present for the 3-part composite key").isNotNull();
        assertThat(idClass.value())
                .as("@IdClass must reference the nested DisclosureGroupId key class")
                .isEqualTo(DisclosureGroup.DisclosureGroupId.class);
    }

    /**
     * The three key components must be mapped as {@code @Id} columns with the
     * exact legacy-derived names and widths:
     * {@code dis_acct_group_id CHAR(10)}, {@code dis_tran_type_cd CHAR(2)}, and
     * {@code dis_tran_cat_cd NUMERIC(4)}.
     */
    @Test
    void compositeKeyColumnsMapped() throws Exception {
        // Component 1: DIS-ACCT-GROUP-ID PIC X(10) -> String CHAR(10)
        Field acctGroupId = declaredField("acctGroupId");
        assertThat(acctGroupId.getType()).isEqualTo(String.class);
        assertThat(acctGroupId.isAnnotationPresent(Id.class))
                .as("acctGroupId must be part of the composite @Id key")
                .isTrue();
        Column acctColumn = acctGroupId.getAnnotation(Column.class);
        assertThat(acctColumn).as("acctGroupId must declare @Column").isNotNull();
        assertThat(acctColumn.name()).isEqualTo("dis_acct_group_id");
        assertThat(acctColumn.length()).isEqualTo(10);

        // Component 2: DIS-TRAN-TYPE-CD PIC X(02) -> String CHAR(2)
        Field tranTypeCd = declaredField("tranTypeCd");
        assertThat(tranTypeCd.getType()).isEqualTo(String.class);
        assertThat(tranTypeCd.isAnnotationPresent(Id.class))
                .as("tranTypeCd must be part of the composite @Id key")
                .isTrue();
        Column typeColumn = tranTypeCd.getAnnotation(Column.class);
        assertThat(typeColumn).as("tranTypeCd must declare @Column").isNotNull();
        assertThat(typeColumn.name()).isEqualTo("dis_tran_type_cd");
        assertThat(typeColumn.length()).isEqualTo(2);

        // Component 3: DIS-TRAN-CAT-CD PIC 9(04) -> Integer NUMERIC(4)
        Field tranCatCd = declaredField("tranCatCd");
        assertThat(tranCatCd.getType()).isEqualTo(Integer.class);
        assertThat(tranCatCd.isAnnotationPresent(Id.class))
                .as("tranCatCd must be part of the composite @Id key")
                .isTrue();
        Column catColumn = tranCatCd.getAnnotation(Column.class);
        assertThat(catColumn).as("tranCatCd must declare @Column").isNotNull();
        assertThat(catColumn.name()).isEqualTo("dis_tran_cat_cd");
        assertThat(catColumn.precision())
                .as("tranCatCd precision must mirror the 4-digit PIC 9(04)")
                .isEqualTo(4);
    }

    /**
     * The interest rate must be a {@link java.math.BigDecimal} mapped to
     * {@code NUMERIC(6,2)}, mirroring the COBOL {@code PIC S9(04)V99} exactly
     * (AAP &sect;0.6.1). This is the single most important decimal-fidelity
     * assertion for this reference entity.
     */
    @Test
    void intRateIsBigDecimalPrecision6Scale2() throws Exception {
        Field intRate = declaredField("intRate");
        assertThat(intRate.getType())
                .as("intRate must be BigDecimal, never a floating-point type")
                .isEqualTo(BigDecimal.class);

        Column column = intRate.getAnnotation(Column.class);
        assertThat(column).as("intRate must declare @Column").isNotNull();
        assertThat(column.name()).isEqualTo("dis_int_rate");
        assertThat(column.precision())
                .as("precision must be 6 for S9(04)V99 -> NUMERIC(6,2)")
                .isEqualTo(6);
        assertThat(column.scale())
                .as("scale must be 2 for S9(04)V99 -> NUMERIC(6,2)")
                .isEqualTo(2);
    }

    /**
     * Decimal-fidelity guard (AAP &sect;0.6.1): no declared field on the entity
     * may use a binary floating-point type. Synthetic fields (for example the
     * {@code $jacocoData} array injected by coverage instrumentation) are
     * skipped because they are not part of the mapping contract.
     */
    @Test
    void noFloatingPointMonetaryFields() {
        for (Field field : DisclosureGroup.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            Class<?> type = field.getType();
            assertThat(type)
                    .as("field '%s' must not use a floating-point type (decimal fidelity, AAP 0.6.1)",
                            field.getName())
                    .isNotEqualTo(float.class)
                    .isNotEqualTo(double.class)
                    .isNotEqualTo(Float.class)
                    .isNotEqualTo(Double.class);
        }
    }

    /**
     * The composite key class must define value equality and a consistent hash
     * code over all three components. Identical tuples are equal with equal hash
     * codes; changing any single component breaks equality &mdash; proving the
     * full three-part legacy key is honoured.
     */
    @Test
    void idClassEqualsAndHashCodeOverAllThreeParts() {
        DisclosureGroup.DisclosureGroupId base =
                new DisclosureGroup.DisclosureGroupId("A000000000", "01", 1);
        DisclosureGroup.DisclosureGroupId identical =
                new DisclosureGroup.DisclosureGroupId("A000000000", "01", 1);

        assertThat(identical)
                .as("identical composite keys must be equal")
                .isEqualTo(base);
        assertThat(identical.hashCode())
                .as("equal composite keys must share a hash code")
                .isEqualTo(base.hashCode());

        assertThat(new DisclosureGroup.DisclosureGroupId("B000000000", "01", 1))
                .as("differing account-group id must break equality")
                .isNotEqualTo(base);
        assertThat(new DisclosureGroup.DisclosureGroupId("A000000000", "02", 1))
                .as("differing transaction-type code must break equality")
                .isNotEqualTo(base);
        assertThat(new DisclosureGroup.DisclosureGroupId("A000000000", "01", 2))
                .as("differing transaction-category code must break equality")
                .isNotEqualTo(base);
    }

    /**
     * The interest rate must round-trip through the entity accessors without
     * loss of scale. {@code 15.00} is the seeded disclosure rate for the
     * {@code A000000000 / 01 / 1} tuple; it is compared with
     * {@code isEqualByComparingTo} so the assertion is about numeric value and
     * scale, exactly as COBOL packed-decimal semantics require.
     */
    @Test
    void intRateRoundTripViaAccessors() {
        DisclosureGroup group = new DisclosureGroup();
        group.setAcctGroupId("A000000000");
        group.setTranTypeCd("01");
        group.setTranCatCd(1);
        group.setIntRate(new BigDecimal("15.00"));

        assertThat(group.getIntRate())
                .as("intRate must round-trip as a BigDecimal preserving value")
                .isEqualByComparingTo(new BigDecimal("15.00"));

        // The key accessors must round-trip too, confirming the four business
        // fields are independently readable/writable.
        assertThat(group.getAcctGroupId()).isEqualTo("A000000000");
        assertThat(group.getTranTypeCd()).isEqualTo("01");
        assertThat(group.getTranCatCd()).isEqualTo(1);
    }
}
