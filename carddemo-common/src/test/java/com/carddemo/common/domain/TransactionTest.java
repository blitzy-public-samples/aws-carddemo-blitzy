package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reflection contract test for the {@link Transaction} JPA entity.
 *
 * :purpose: Freeze the object-relational mapping that keeps the modern
 *     ``transactions`` table byte-compatible with the legacy COBOL
 *     ``TRAN-RECORD`` layout (copybook ``CVTRA05Y``). It verifies that the
 *     monetary ``tranAmt`` field is a {@link java.math.BigDecimal} mapped to
 *     ``NUMERIC(11,2)`` — COBOL ``TRAN-AMT PIC S9(09)V99`` has 9 integer plus 2
 *     fractional digits — that no field uses a binary floating-point type, and
 *     that the 26-character ``X(26)`` timestamp width of ``tranOrigTs`` and
 *     ``tranProcTs`` is preserved exactly. Design rationale for these choices is
 *     recorded in ``docs/decision-log.md``.
 * :output: JUnit 5 / AssertJ assertions only. The test reads the RUNTIME-retained
 *     ``jakarta.persistence`` mapping annotations through reflection and touches
 *     no database, Spring context, or other external resource (pure JVM).
 */
final class TransactionTest {

    /**
     * Resolve a declared field of {@link Transaction} by name.
     *
     * :param name: the Java field name to look up (for example ``tranAmt``).
     * :return: the reflected {@link Field}; fails the test if it is absent.
     */
    private static Field field(String name) {
        try {
            return Transaction.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Transaction is missing expected field '" + name + "'", e);
        }
    }

    /**
     * Resolve the ``jakarta.persistence`` {@link Column} mapping of a named field.
     *
     * :param name: the Java field name whose ``@Column`` mapping is required.
     * :return: the non-null {@link Column} annotation declared on the field.
     */
    private static Column column(String name) {
        Column mapping = field(name).getAnnotation(Column.class);
        assertThat(mapping)
                .as("field '%s' must declare a @Column mapping", name)
                .isNotNull();
        return mapping;
    }

    /**
     * Enumerate the mapped instance fields of {@link Transaction}.
     *
     * :return: every declared field that is neither ``static`` nor compiler
     *     synthetic (for example coverage-agent instrumentation), i.e. the
     *     persistent state the entity maps onto table columns.
     */
    private static List<Field> instanceFields() {
        return Arrays.stream(Transaction.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> !f.isSynthetic())
                .toList();
    }

    @Test
    @DisplayName("Transaction is a JPA @Entity")
    void isJpaEntity() {
        assertThat(Transaction.class.isAnnotationPresent(Entity.class))
                .as("Transaction must be annotated @Entity")
                .isTrue();
    }

    @Test
    @DisplayName("Transaction maps to the \"transactions\" table")
    void mapsToTransactionsTable() {
        Table table = Transaction.class.getAnnotation(Table.class);
        assertThat(table)
                .as("Transaction must be annotated @Table")
                .isNotNull();
        assertThat(table.name()).isEqualTo("transactions");
    }

    @Test
    @DisplayName("tranId is the @Id primary key: String, @Column(length = 16)")
    void tranIdIsPrimaryKeyLength16() {
        Field tranId = field("tranId");
        assertThat(tranId.isAnnotationPresent(Id.class))
                .as("tranId must be annotated @Id")
                .isTrue();
        assertThat(tranId.getType())
                .as("TRAN-ID PIC X(16) maps to a String")
                .isEqualTo(String.class);
        assertThat(column("tranId").length())
                .as("TRAN-ID PIC X(16) fixes the column width at 16")
                .isEqualTo(16);
    }

    @Test
    @DisplayName("tranAmt is a BigDecimal (packed decimal, never floating point)")
    void tranAmtIsBigDecimal() {
        assertThat(field("tranAmt").getType())
                .as("TRAN-AMT packed-decimal money must map to BigDecimal")
                .isEqualTo(BigDecimal.class);
    }

    @Test
    @DisplayName("tranAmt maps to NUMERIC(11,2): @Column(precision = 11, scale = 2)")
    void tranAmtHasNumeric11Scale2() {
        Column amt = column("tranAmt");
        assertThat(amt.precision())
                .as("TRAN-AMT S9(09)V99 => 9 integer + 2 fractional = precision 11")
                .isEqualTo(11);
        assertThat(amt.scale())
                .as("TRAN-AMT S9(09)V99 => scale 2")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("No entity field uses a binary floating-point type")
    void noFloatingPointFieldsAnywhere() {
        for (Field f : instanceFields()) {
            assertThat(f.getType())
                    .as("field '%s' must not use a binary floating-point type", f.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    @Test
    @DisplayName("tranOrigTs and tranProcTs preserve the 26-char X(26) timestamp width as String")
    void timestampsAreString26() {
        for (String name : List.of("tranOrigTs", "tranProcTs")) {
            assertThat(field(name).getType())
                    .as("%s (PIC X(26)) maps to a String", name)
                    .isEqualTo(String.class);
            assertThat(column(name).length())
                    .as("%s must preserve the 26-character wire-format width", name)
                    .isEqualTo(26);
        }
    }
}
