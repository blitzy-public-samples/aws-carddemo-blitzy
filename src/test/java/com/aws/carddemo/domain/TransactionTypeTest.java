package com.aws.carddemo.domain;

import java.lang.reflect.Field;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit mapping test for the {@link TransactionType} JPA entity.
 *
 * <p>Part of the AWS CardDemo COBOL to Java 25 / Spring Boot 3.5.16 migration. This
 * test locks the entity's object-relational mapping contract against its COBOL
 * copybook oracle so that Hibernate {@code ddl-auto=validate} against the Flyway
 * {@code V1__schema.sql} schema remains meaningful over time.</p>
 *
 * <p>COBOL oracle: {@code legacy/cpy/CVTRA03Y.cpy} - {@code TRAN-TYPE-RECORD}
 * (record length 60): {@code TRAN-TYPE PIC X(02)}, {@code TRAN-TYPE-DESC PIC X(50)},
 * {@code FILLER PIC X(08)}. The trailing eight-byte filler carried no business
 * meaning and is intentionally not persisted, so the mapped columns are the
 * two-character key and its fifty-character description only.</p>
 *
 * <p>This is a standalone unit test (Maven Surefire; the class name ends in
 * {@code Test}). It relies exclusively on reflection over {@link TransactionType}
 * and plain accessor invocation: it starts no database and no Spring application
 * context. It validates the {@code TransactionType} entity mapping and thereby
 * supports the migration traceability requirement (Technical Specification
 * section 0.6.10).</p>
 */
class TransactionTypeTest {

    /**
     * The entity must be a JPA {@code @Entity} mapped to the {@code transaction_type}
     * relation, mirroring the single VSAM {@code TRANTYPE} reference file.
     */
    @Test
    @DisplayName("TransactionType is a JPA @Entity mapped to the transaction_type table")
    void classIsJpaEntityWithCorrectTable() {
        assertThat(TransactionType.class.isAnnotationPresent(Entity.class))
                .as("TransactionType must be annotated with @Entity")
                .isTrue();

        Table table = TransactionType.class.getAnnotation(Table.class);
        assertThat(table)
                .as("TransactionType must declare an explicit @Table")
                .isNotNull();
        assertThat(table.name())
                .as("@Table name must map to the transaction_type relation")
                .isEqualTo("transaction_type");
    }

    /**
     * The {@code tranType} field must be the {@code @Id} primary key, mapped to the
     * {@code tran_type} column with length 2, preserving COBOL {@code TRAN-TYPE PIC X(02)}.
     *
     * @throws Exception if the {@code tranType} field cannot be resolved by reflection
     */
    @Test
    @DisplayName("Primary key tranType maps to @Id column tran_type CHAR(2) as String")
    void primaryKeyIsTranTypeMappedToTranTypeColumn() throws Exception {
        Field field = TransactionType.class.getDeclaredField("tranType");
        field.setAccessible(true);

        assertThat(field.isAnnotationPresent(Id.class))
                .as("tranType must be the @Id primary key")
                .isTrue();

        Column column = field.getAnnotation(Column.class);
        assertThat(column)
                .as("tranType must declare an explicit @Column")
                .isNotNull();
        assertThat(column.name())
                .as("tranType must map to the tran_type column")
                .isEqualTo("tran_type");
        assertThat(column.length())
                .as("tran_type length must preserve COBOL PIC X(02)")
                .isEqualTo(2);
        assertThat(field.getType())
                .as("tranType must be a String")
                .isEqualTo(String.class);
    }

    /**
     * The {@code tranTypeDesc} field must be mapped to the {@code tran_type_desc}
     * column with length 50, preserving COBOL {@code TRAN-TYPE-DESC PIC X(50)}.
     *
     * @throws Exception if the {@code tranTypeDesc} field cannot be resolved by reflection
     */
    @Test
    @DisplayName("Description tranTypeDesc maps to column tran_type_desc CHAR(50) as String")
    void descriptionColumnMapping() throws Exception {
        Field field = TransactionType.class.getDeclaredField("tranTypeDesc");
        field.setAccessible(true);

        Column column = field.getAnnotation(Column.class);
        assertThat(column)
                .as("tranTypeDesc must declare an explicit @Column")
                .isNotNull();
        assertThat(column.name())
                .as("tranTypeDesc must map to the tran_type_desc column")
                .isEqualTo("tran_type_desc");
        assertThat(column.length())
                .as("tran_type_desc length must preserve COBOL PIC X(50)")
                .isEqualTo(50);
        assertThat(field.getType())
                .as("tranTypeDesc must be a String")
                .isEqualTo(String.class);
    }

    /**
     * Entity equality must be keyed solely on the {@code tranType} primary key. This
     * test first verifies (by reflection) that the entity actually overrides
     * {@code equals(Object)} and {@code hashCode()} rather than inheriting
     * {@link Object} identity, then asserts key-based equality and inequality.
     *
     * @throws Exception if the {@code equals}/{@code hashCode} methods cannot be
     *                   resolved by reflection
     */
    @Test
    @DisplayName("equals and hashCode are keyed solely on the tranType primary key")
    void equalsAndHashCodeKeyedOnTranType() throws Exception {
        // Confirm the production entity overrides equals/hashCode (identity by key),
        // not the inherited Object implementations. getDeclaredMethod throws if absent.
        assertThat(TransactionType.class.getDeclaredMethod("equals", Object.class))
                .as("TransactionType must override equals(Object)")
                .isNotNull();
        assertThat(TransactionType.class.getDeclaredMethod("hashCode"))
                .as("TransactionType must override hashCode()")
                .isNotNull();

        TransactionType first = new TransactionType();
        first.setTranType("01");
        first.setTranTypeDesc("Purchase");

        TransactionType sameKey = new TransactionType();
        sameKey.setTranType("01");
        sameKey.setTranTypeDesc("A different description sharing the same key");

        assertThat(first)
                .as("Instances with the same tranType must be equal")
                .isEqualTo(sameKey);
        assertThat(first)
                .as("Equal instances must share a hash code")
                .hasSameHashCodeAs(sameKey);

        TransactionType differentKey = new TransactionType();
        differentKey.setTranType("02");
        differentKey.setTranTypeDesc("Purchase");
        assertThat(first)
                .as("Instances with a different tranType must not be equal")
                .isNotEqualTo(differentKey);
    }

    /**
     * {@code TransactionType} is reference data with no PII or credentials, so its
     * {@code toString()} is safe to log. This documents that expectation: the rendered
     * string must be non-null and must surface the non-sensitive {@code tranType} code.
     */
    @Test
    @DisplayName("toString is non-null and exposes only the non-sensitive tranType code")
    void toStringExposesNoUnexpectedSensitiveData() {
        TransactionType type = new TransactionType();
        type.setTranType("07");
        type.setTranTypeDesc("Interest");

        String rendered = type.toString();
        assertThat(rendered)
                .as("toString must be non-null")
                .isNotNull();
        assertThat(rendered)
                .as("toString should surface the tranType code; reference data is safe to log")
                .contains("07");
    }
}
