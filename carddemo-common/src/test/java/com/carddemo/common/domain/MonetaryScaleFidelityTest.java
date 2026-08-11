package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.List;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-entity monetary-precision audit for the CardDemo domain model.
 *
 * :purpose: Guard the highest-risk migration property (AAP 0.6.1, financial
 *     precision) globally, by reflection only. Every monetary field across the
 *     domain must be a ``java.math.BigDecimal`` and, where it is persisted by a
 *     JPA ``@Entity``, must carry the exact ``@Column(precision, scale)`` derived
 *     from its COBOL ``PIC`` clause (``S9(n)V99`` -> precision ``n+2``, scale
 *     ``2``) so the generated ``NUMERIC(p,2)`` DDL cannot silently drift. No
 *     domain type anywhere may use a lossy ``double``/``float``. Pure reflection:
 *     no database, no Spring context, no Testcontainers.
 * :output: JUnit 5 / AssertJ assertions only; the class holds no state and
 *     touches no external resource. ``SoftAssertions`` collects every mismatch in
 *     a single run so the whole money-integrity report is produced together.
 */
final class MonetaryScaleFidelityTest {

    /**
     * One monetary-field expectation.
     *
     * :param entity: the declaring domain class.
     * :param name: the exact Java field name.
     * :param precision: the required ``NUMERIC`` precision (COBOL digits + 2).
     * :param scale: the required ``NUMERIC`` scale (always 2 for CardDemo money).
     */
    private record MoneyField(Class<?> entity, String name, int precision, int scale) {
    }

    /**
     * The complete set of monetary fields, byte-verified against copybooks
     * CVACT01Y (account), CVTRA05Y (transaction), CVTRA06Y (daily transaction),
     * CVTRA01Y (transaction-category balance) and CVTRA02Y (disclosure group).
     */
    private static final List<MoneyField> MONEY_FIELDS = List.of(
            new MoneyField(Account.class, "acctCurrBal", 12, 2),
            new MoneyField(Account.class, "acctCreditLimit", 12, 2),
            new MoneyField(Account.class, "acctCashCreditLimit", 12, 2),
            new MoneyField(Account.class, "acctCurrCycCredit", 12, 2),
            new MoneyField(Account.class, "acctCurrCycDebit", 12, 2),
            new MoneyField(Transaction.class, "tranAmt", 11, 2),
            new MoneyField(DailyTransaction.class, "dalytranAmt", 11, 2),
            new MoneyField(TranCatBal.class, "tranCatBal", 11, 2),
            new MoneyField(DiscGroup.class, "disIntRate", 6, 2));

    /** Every domain type audited by the global no-floating-point guard. */
    private static final List<Class<?>> ALL_ENTITIES = List.of(
            Account.class, Card.class, CardXref.class, Customer.class,
            Transaction.class, DailyTransaction.class, TranCatBal.class,
            DiscGroup.class, TranType.class, TranCatg.class, SecurityUser.class);

    /**
     * Resolve a declared field, failing clearly when the contract omits it.
     *
     * :param c: the declaring domain class.
     * :param name: the exact Java field name expected on that class.
     * :return: the reflective ``Field`` handle for the named field.
     */
    private static Field field(Class<?> c, String name) {
        try {
            return c.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(
                    c.getSimpleName() + " is missing expected monetary field: " + name, e);
        }
    }

    @Test
    @DisplayName("Every monetary field is BigDecimal with the exact NUMERIC(precision, scale)")
    void everyMonetaryFieldIsBigDecimalWithExactScale() {
        SoftAssertions.assertSoftly(softly -> {
            for (MoneyField mf : MONEY_FIELDS) {
                Field f = field(mf.entity(), mf.name());
                String label = mf.entity().getSimpleName() + "." + mf.name();

                // Core AAP 0.6.1 guard: money is always fixed-point BigDecimal,
                // never a binary floating-point type.
                softly.assertThat(f.getType())
                        .as(label + " type")
                        .isEqualTo(BigDecimal.class);

                // Every monetary field belongs to a persistent JPA entity (the staged
                // DALYTRAN feed included), and the NUMERIC(precision, scale) column is
                // derived from @Column, so both numbers must match the COBOL-derived
                // contract exactly - a wrong precision or scale would silently corrupt
                // money.
                softly.assertThat(mf.entity().isAnnotationPresent(Entity.class))
                        .as(label + " declaring type must be a JPA @Entity")
                        .isTrue();

                Column col = f.getAnnotation(Column.class);
                softly.assertThat(col).as(label + " @Column present").isNotNull();
                if (col != null) {
                    softly.assertThat(col.precision())
                            .as(label + " precision").isEqualTo(mf.precision());
                    softly.assertThat(col.scale())
                            .as(label + " scale").isEqualTo(mf.scale());
                }
            }
        });
    }

    @Test
    @DisplayName("No domain type uses a binary floating-point field anywhere")
    void noEntityUsesFloatingPointForAnyField() {
        SoftAssertions.assertSoftly(softly -> {
            for (Class<?> c : ALL_ENTITIES) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                        continue;
                    }
                    softly.assertThat(f.getType())
                            .as(c.getSimpleName() + "." + f.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class);
                }
            }
        });
    }

    @Test
    @DisplayName("The money-coverage audit set has not silently shrunk")
    void moneyFieldListCoversNineFields() {
        assertThat(MONEY_FIELDS).hasSize(9);
        assertThat(ALL_ENTITIES).hasSize(11);
    }
}
