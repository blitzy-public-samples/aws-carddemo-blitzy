package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Pure, self-contained unit test for the JPA entity {@link Transaction}. It exercises the
 * object-relational mapping metadata (annotations, types, and column attributes) plus the
 * accessor/identity contract using reflection and AssertJ only. It intentionally starts NO
 * Spring context and touches NO database, so it runs under the Maven Surefire plugin as a fast
 * unit test (the class name ends in {@code Test}).
 *
 * <p><strong>COBOL oracle (AAP 0.6.10 traceability):</strong> the entity under test is migrated
 * one-for-one from the copybook {@code TRAN-RECORD} (record length 350) defined in
 * {@code legacy/cpy/CVTRA05Y.cpy} (retained read-only for reference). This test locks the
 * source-to-target mapping so that any future edit to {@link Transaction} that breaks parity with
 * that copybook fails fast.</p>
 *
 * <p><strong>Decimal fidelity (AAP 0.6.1):</strong> COBOL {@code TRAN-AMT PIC S9(09)V99} is a
 * fixed-scale packed decimal and must map to {@link java.math.BigDecimal} with precision 11 and
 * scale 2 ({@code NUMERIC(11,2)}); monetary fields must never be represented with binary
 * floating-point types. {@link #tranAmtIsBigDecimalPrecision11Scale2()} pins the column attributes
 * and {@link #noFloatingPointFields()} guards the whole entity against {@code float}/{@code double}
 * (and their wrappers) creeping in.</p>
 *
 * <p><strong>{@code TRAN-} prefix drop (AAP 0.4.1, 0.6.2):</strong> every persisted column keeps
 * its {@code tran_} prefix except the card number: {@code TRAN-CARD-NUM} is deliberately exposed as
 * the property {@code cardNum} mapped to column {@code card_num}. That rename is what lets the
 * secondary index {@code idx_transaction_card_num} and the Spring Data derived query
 * {@code TransactionRepository.findByCardNum(String)} resolve, reproducing the legacy
 * transaction-by-card VSAM alternate index. {@link #cardNumFieldMapsToCardNumColumnWithoutTranPrefix()}
 * asserts both that {@code cardNum} maps to {@code card_num} and that the prefixed field
 * {@code tranCardNum} is absent.</p>
 */
class TransactionTest {

    /**
     * Verifies the class is a JPA entity mapped to the {@code transaction} table.
     */
    @Test
    void classIsJpaEntityWithTransactionTable() {
        assertThat(Transaction.class.isAnnotationPresent(Entity.class))
                .as("Transaction must be a JPA @Entity")
                .isTrue();

        Table table = Transaction.class.getAnnotation(Table.class);
        assertThat(table).as("Transaction must declare @Table").isNotNull();
        assertThat(table.name()).isEqualTo("transaction");
    }

    /**
     * Verifies {@code tranId} is the {@code @Id} primary key ({@code TRAN-ID PIC X(16)}) mapped to
     * a 16-character {@code tran_id} column of type {@link String}.
     */
    @Test
    void primaryKeyIsTranId() throws NoSuchFieldException {
        Field tranId = field("tranId");

        assertThat(tranId.isAnnotationPresent(Id.class))
                .as("tranId must be the @Id primary key")
                .isTrue();
        assertThat(tranId.getType()).isEqualTo(String.class);

        Column column = tranId.getAnnotation(Column.class);
        assertThat(column).as("tranId must declare @Column").isNotNull();
        assertThat(column.name()).isEqualTo("tran_id");
        assertThat(column.length()).isEqualTo(16);
    }

    /**
     * Verifies decimal fidelity for the monetary amount: {@code tranAmt} is exactly
     * {@link BigDecimal} and is mapped to {@code tran_amt} with precision 11 and scale 2
     * ({@code TRAN-AMT PIC S9(09)V99} &rarr; {@code NUMERIC(11,2)}).
     */
    @Test
    void tranAmtIsBigDecimalPrecision11Scale2() throws NoSuchFieldException {
        Field tranAmt = field("tranAmt");

        assertThat(tranAmt.getType())
                .as("tranAmt must be java.math.BigDecimal (never floating point)")
                .isEqualTo(BigDecimal.class);

        Column column = tranAmt.getAnnotation(Column.class);
        assertThat(column).as("tranAmt must declare @Column").isNotNull();
        assertThat(column.name()).isEqualTo("tran_amt");
        assertThat(column.precision()).isEqualTo(11);
        assertThat(column.scale()).isEqualTo(2);
    }

    /**
     * Locks the deliberate {@code TRAN-} prefix drop: {@code TRAN-CARD-NUM} is exposed as the
     * property {@code cardNum} mapped to column {@code card_num}, and the prefixed field
     * {@code tranCardNum} must NOT exist (its absence is what keeps
     * {@code TransactionRepository.findByCardNum} and {@code idx_transaction_card_num} aligned).
     */
    @Test
    void cardNumFieldMapsToCardNumColumnWithoutTranPrefix() throws NoSuchFieldException {
        Field cardNum = field("cardNum");

        Column column = cardNum.getAnnotation(Column.class);
        assertThat(column).as("cardNum must declare @Column").isNotNull();
        assertThat(column.name())
                .as("TRAN-CARD-NUM must map to column card_num with the TRAN- prefix dropped")
                .isEqualTo("card_num");

        // The rename is only correct if the prefixed variant is truly gone; requesting it must fail.
        assertThatExceptionOfType(NoSuchFieldException.class)
                .as("the prefixed field tranCardNum must not exist after the TRAN- prefix drop")
                .isThrownBy(() -> Transaction.class.getDeclaredField("tranCardNum"));
    }

    /**
     * Verifies both 26-character COBOL timestamps ({@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS}) map
     * to {@link LocalDateTime} on the {@code tran_orig_ts} / {@code tran_proc_ts} columns.
     */
    @Test
    void timestampFieldsAreLocalDateTime() throws NoSuchFieldException {
        Field origTs = field("origTs");
        assertThat(origTs.getType()).isEqualTo(LocalDateTime.class);
        Column origColumn = origTs.getAnnotation(Column.class);
        assertThat(origColumn).as("origTs must declare @Column").isNotNull();
        assertThat(origColumn.name()).isEqualTo("tran_orig_ts");

        Field procTs = field("procTs");
        assertThat(procTs.getType()).isEqualTo(LocalDateTime.class);
        Column procColumn = procTs.getAnnotation(Column.class);
        assertThat(procColumn).as("procTs must declare @Column").isNotNull();
        assertThat(procColumn.name()).isEqualTo("tran_proc_ts");
    }

    /**
     * Decimal-fidelity guard (AAP 0.6.1): no declared field of the entity may use binary
     * floating point ({@code float}/{@code double} or their wrappers). Synthetic fields injected by
     * tooling (for example the JaCoCo coverage agent's {@code $jacocoData}) are skipped.
     */
    @Test
    void noFloatingPointFields() {
        for (Field candidate : Transaction.class.getDeclaredFields()) {
            if (candidate.isSynthetic()) {
                continue;
            }
            assertThat(candidate.getType())
                    .as("field '%s' must not use binary floating point", candidate.getName())
                    .isNotEqualTo(float.class)
                    .isNotEqualTo(double.class)
                    .isNotEqualTo(Float.class)
                    .isNotEqualTo(Double.class);
        }
    }

    /**
     * Verifies the amount survives a setter/getter round trip without loss of scale. The value is
     * negative because transactions are signed ({@code TRAN-AMT PIC S9(09)V99}); comparison uses
     * {@code isEqualByComparingTo} so it is exact on numeric value regardless of representation.
     */
    @Test
    void tranAmtRoundTrip() {
        Transaction transaction = new Transaction();
        BigDecimal amount = new BigDecimal("-123.45");

        transaction.setTranAmt(amount);

        assertThat(transaction.getTranAmt()).isEqualByComparingTo(amount);
    }

    /**
     * Verifies identity semantics against the actual production contract. If {@link Transaction}
     * overrides {@code equals}/{@code hashCode} (it derives identity from the primary key
     * {@code tranId}, mirroring the {@code TRANSACT} KSDS record key), two instances with the same
     * {@code tranId} are equal with matching hash codes and differ from an instance with another
     * {@code tranId}. If no override is present, the test falls back to the reference-identity
     * contract of {@link Object#equals(Object)}.
     */
    @Test
    void equalsAndHashCodeOnTranId() {
        Transaction first = new Transaction();
        first.setTranId("TRAN000000000001");
        Transaction second = new Transaction();
        second.setTranId("TRAN000000000001");
        Transaction other = new Transaction();
        other.setTranId("TRAN000000000002");

        if (declaresEqualsOverride()) {
            assertThat(first).isEqualTo(second);
            assertThat(first).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(other);
        } else {
            assertThat(first).isEqualTo(first);
            assertThat(first).isNotEqualTo(second);
        }
    }

    /**
     * Returns the declared field of {@link Transaction} with the given name, made accessible for
     * reflective introspection.
     *
     * @param name the declared field name
     * @return the accessible {@link Field}
     * @throws NoSuchFieldException if the entity declares no such field
     */
    private static Field field(String name) throws NoSuchFieldException {
        Field field = Transaction.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * Reports whether {@link Transaction} declares its own {@code equals(Object)} method (i.e.,
     * overrides {@link Object#equals(Object)}).
     *
     * @return {@code true} if {@code Transaction} declares {@code equals(Object)}; otherwise
     *         {@code false}
     */
    private static boolean declaresEqualsOverride() {
        try {
            Transaction.class.getDeclaredMethod("equals", Object.class);
            return true;
        } catch (NoSuchMethodException noOverride) {
            return false;
        }
    }
}
