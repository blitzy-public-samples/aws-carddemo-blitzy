package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit mapping tests for the JPA entity {@link TransactionCategoryBalance}.
 *
 * <p>These tests run entirely in-memory using reflection and AssertJ; they start
 * no Spring context and touch no database. The class name ends in {@code Test},
 * so Surefire executes it as a fast unit test (the Testcontainers/PostgreSQL
 * integration and parity suites end in {@code IT} and run under Failsafe).</p>
 *
 * <p><strong>COBOL oracle / provenance:</strong> the entity under test was
 * migrated one-for-one from the AWS CardDemo copybook
 * {@code TRAN-CAT-BAL-RECORD} (record length 50 bytes) defined at
 * {@code legacy/cpy/CVTRA01Y.cpy}, which described the VSAM KSDS file
 * {@code TCATBAL}. That layout is: a composite key group {@code TRAN-CAT-KEY}
 * = { {@code TRANCAT-ACCT-ID PIC 9(11)}, {@code TRANCAT-TYPE-CD PIC X(02)},
 * {@code TRANCAT-CD PIC 9(04)} }, a monetary balance
 * {@code TRAN-CAT-BAL PIC S9(09)V99}, and trailing {@code FILLER PIC X(22)}.</p>
 *
 * <p><strong>What is guarded here:</strong></p>
 * <ul>
 *   <li><em>Decimal fidelity</em> (AAP &sect;0.6.1): the running balance must be
 *       a {@link java.math.BigDecimal} mapped as {@code NUMERIC(11,2)}
 *       ({@code precision = 11, scale = 2}) and no field on the entity or its
 *       identifier class may be a binary floating-point type
 *       ({@code float}, {@code double}, {@link Float}, or {@link Double}).</li>
 *   <li><em>Composite-key preservation and traceability</em> (AAP &sect;0.6.10):
 *       the legacy three-part VSAM key is reproduced as a JPA {@code @IdClass}
 *       with column names, types, and {@code equals}/{@code hashCode} semantics
 *       preserved over all three key parts.</li>
 * </ul>
 *
 * <p>Origin: {@code legacy/cpy/CVTRA01Y.cpy} &mdash;
 * {@code TRAN-CAT-BAL-RECORD}, RECLN 50; VSAM file {@code TCATBAL}.</p>
 */
@DisplayName("TransactionCategoryBalance JPA mapping (COBOL TRAN-CAT-BAL-RECORD parity)")
class TransactionCategoryBalanceTest {

    /**
     * Binary floating-point types that are forbidden for any field of a money
     * entity, enforcing the decimal-fidelity rule (AAP &sect;0.6.1). COBOL
     * fixed-scale packed decimals ({@code COMP-3} / {@code S9(n)V99}) must be
     * represented with {@link java.math.BigDecimal}, never {@code float} or
     * {@code double}.
     */
    private static final Set<Class<?>> FORBIDDEN_MONETARY_TYPES =
            Set.of(float.class, double.class, Float.class, Double.class);

    /**
     * The entity must be a JPA {@code @Entity} bound to the
     * {@code transaction_category_balance} table and declare the nested
     * composite-key class through {@code @IdClass}.
     */
    @Test
    @DisplayName("is an @Entity mapped to transaction_category_balance with the nested @IdClass")
    void classIsJpaEntityWithTableAndIdClass() {
        assertThat(TransactionCategoryBalance.class.isAnnotationPresent(Entity.class))
                .as("@Entity must be present on TransactionCategoryBalance")
                .isTrue();

        Table table = TransactionCategoryBalance.class.getAnnotation(Table.class);
        assertThat(table).as("@Table must be present").isNotNull();
        assertThat(table.name())
                .as("@Table.name() must preserve the VSAM TCATBAL mapping")
                .isEqualTo("transaction_category_balance");

        IdClass idClass = TransactionCategoryBalance.class.getAnnotation(IdClass.class);
        assertThat(idClass).as("@IdClass must be present").isNotNull();
        assertThat(idClass.value())
                .as("@IdClass.value() must reference the nested TransactionCategoryBalanceId")
                .isEqualTo(TransactionCategoryBalanceId.class);
    }

    /**
     * The three legacy key components must be mapped as {@code @Id} columns with
     * the correct Java types and column names. Precision is asserted only where
     * the production {@code @Column} declares it (numeric key parts
     * {@code acctId} and {@code catCd}); the character key part {@code typeCd}
     * declares {@code length} instead.
     */
    @Test
    @DisplayName("maps the three-part composite key (acctId, typeCd, catCd) as @Id columns")
    void compositeKeyColumnsMapped() throws NoSuchFieldException {
        Field acctId = declaredField(TransactionCategoryBalance.class, "acctId");
        assertThat(acctId.getType())
                .as("TRANCAT-ACCT-ID PIC 9(11) -> Long")
                .isEqualTo(Long.class);
        assertThat(acctId.isAnnotationPresent(Id.class)).as("acctId must be @Id").isTrue();
        Column acctColumn = acctId.getAnnotation(Column.class);
        assertThat(acctColumn).as("acctId @Column must be present").isNotNull();
        assertThat(acctColumn.name()).isEqualTo("trancat_acct_id");
        assertThat(acctColumn.precision()).as("acctId precision (9(11))").isEqualTo(11);

        Field typeCd = declaredField(TransactionCategoryBalance.class, "typeCd");
        assertThat(typeCd.getType())
                .as("TRANCAT-TYPE-CD PIC X(02) -> String")
                .isEqualTo(String.class);
        assertThat(typeCd.isAnnotationPresent(Id.class)).as("typeCd must be @Id").isTrue();
        Column typeColumn = typeCd.getAnnotation(Column.class);
        assertThat(typeColumn).as("typeCd @Column must be present").isNotNull();
        assertThat(typeColumn.name()).isEqualTo("trancat_type_cd");
        assertThat(typeColumn.length()).as("typeCd length (X(02))").isEqualTo(2);

        Field catCd = declaredField(TransactionCategoryBalance.class, "catCd");
        assertThat(catCd.getType())
                .as("TRANCAT-CD PIC 9(04) -> Integer")
                .isEqualTo(Integer.class);
        assertThat(catCd.isAnnotationPresent(Id.class)).as("catCd must be @Id").isTrue();
        Column catColumn = catCd.getAnnotation(Column.class);
        assertThat(catColumn).as("catCd @Column must be present").isNotNull();
        assertThat(catColumn.name()).isEqualTo("trancat_cd");
        assertThat(catColumn.precision()).as("catCd precision (9(04))").isEqualTo(4);
    }

    /**
     * The monetary balance must be a {@link java.math.BigDecimal} mapped to
     * {@code NUMERIC(11,2)} and must not be part of the primary key. This is the
     * per-field expression of the decimal-fidelity rule (AAP &sect;0.6.1) for
     * {@code TRAN-CAT-BAL PIC S9(09)V99}.
     */
    @Test
    @DisplayName("maps balance as BigDecimal NUMERIC(11,2) (TRAN-CAT-BAL PIC S9(09)V99)")
    void balanceIsBigDecimalWithPrecision11Scale2() throws NoSuchFieldException {
        Field balance = declaredField(TransactionCategoryBalance.class, "balance");
        assertThat(balance.getType())
                .as("balance must be exactly java.math.BigDecimal")
                .isEqualTo(BigDecimal.class);
        assertThat(balance.isAnnotationPresent(Id.class))
                .as("balance must not be part of the composite key")
                .isFalse();

        Column column = balance.getAnnotation(Column.class);
        assertThat(column).as("balance @Column must be present").isNotNull();
        assertThat(column.name()).isEqualTo("tran_cat_bal");
        assertThat(column.precision()).as("balance precision (9 integer digits + carry)").isEqualTo(11);
        assertThat(column.scale()).as("balance scale (V99)").isEqualTo(2);
    }

    /**
     * Decimal-fidelity guard (AAP &sect;0.6.1): neither the entity nor its
     * composite-key class may declare any binary floating-point field. Synthetic
     * fields (for example the coverage agent's {@code $jacocoData}) are skipped
     * because they are injected by instrumentation and are not part of the model.
     */
    @Test
    @DisplayName("declares no floating-point (float/double/Float/Double) monetary fields")
    void noFloatingPointMonetaryFields() {
        List<String> offenders = new ArrayList<>();
        offenders.addAll(floatingPointFields(TransactionCategoryBalance.class));
        offenders.addAll(floatingPointFields(TransactionCategoryBalanceId.class));

        assertThat(offenders)
                .as("Decimal fidelity (AAP 0.6.1): money must be BigDecimal, "
                        + "no float/double/Float/Double fields allowed but found %s", offenders)
                .isEmpty();
    }

    /**
     * The composite-key class must define value-based equality and a consistent
     * hash code over all three key parts, mirroring the uniqueness of the legacy
     * VSAM {@code TRAN-CAT-KEY}. Equality must hold for identical triples, and
     * must break when any single part differs; the reflexive, {@code null}, and
     * different-type branches are exercised directly.
     */
    @Test
    @DisplayName("id class equals/hashCode cover all three key parts")
    void idClassEqualsAndHashCodeOverAllThreeParts() {
        TransactionCategoryBalanceId base = new TransactionCategoryBalanceId(1L, "01", 1);
        TransactionCategoryBalanceId identical = new TransactionCategoryBalanceId(1L, "01", 1);

        assertThat(base)
                .as("identical composite keys must be equal")
                .isEqualTo(identical);
        assertThat(base)
                .as("equal keys must share a hash code")
                .hasSameHashCodeAs(identical);

        // Cover the equals() short-circuit branches directly.
        assertThat(base.equals(base)).as("reflexive equality").isTrue();
        assertThat(base.equals(null)).as("never equal to null").isFalse();
        assertThat(base.equals(new Object())).as("never equal to a different type").isFalse();

        // Vary each key part independently; each must break equality.
        assertThat(base)
                .as("differing acctId must break equality")
                .isNotEqualTo(new TransactionCategoryBalanceId(2L, "01", 1));
        assertThat(base)
                .as("differing typeCd must break equality")
                .isNotEqualTo(new TransactionCategoryBalanceId(1L, "02", 1));
        assertThat(base)
                .as("differing catCd must break equality")
                .isNotEqualTo(new TransactionCategoryBalanceId(1L, "01", 2));
    }

    /**
     * The balance must round-trip through its public accessors without any loss
     * of scale or precision. BigDecimal is compared with
     * {@code isEqualByComparingTo} so that numeric value (not scale-sensitive
     * {@code equals}) is asserted.
     */
    @Test
    @DisplayName("balance round-trips through accessors preserving BigDecimal value")
    void balanceRoundTripViaAccessors() {
        TransactionCategoryBalance entity = new TransactionCategoryBalance();
        entity.setBalance(new BigDecimal("123.45"));

        BigDecimal result = entity.getBalance();
        assertThat(result).as("getter must return the value set").isNotNull();
        assertThat(result).isInstanceOf(BigDecimal.class);
        assertThat(result)
                .as("balance value must be preserved exactly")
                .isEqualByComparingTo(new BigDecimal("123.45"));
    }

    /**
     * Resolves a declared field by name and makes it reflectively accessible so
     * that its declared type and mapping annotations can be inspected.
     *
     * @param type the class declaring the field
     * @param name the field name
     * @return the resolved, accessible {@link Field}
     * @throws NoSuchFieldException if the named field is not declared on {@code type}
     */
    private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * Collects the names of any non-synthetic declared field on {@code type}
     * whose type is a forbidden binary floating-point type.
     *
     * @param type the class whose declared fields are inspected
     * @return a list of human-readable offender descriptions; empty when none exist
     */
    private static List<String> floatingPointFields(Class<?> type) {
        List<String> offenders = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            if (FORBIDDEN_MONETARY_TYPES.contains(field.getType())) {
                offenders.add(type.getSimpleName() + "." + field.getName()
                        + " (" + field.getType().getName() + ")");
            }
        }
        return offenders;
    }
}
