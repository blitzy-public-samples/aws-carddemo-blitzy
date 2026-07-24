package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reflection-based structural contract test for the {@link Account} JPA entity.
 *
 * :purpose: Lock the migration-critical structure of the legacy COBOL
 *     ``ACCOUNT-RECORD`` layout (copybook ``CVACT01Y``) as re-expressed by the
 *     {@code Account} entity: the five monetary fields must stay
 *     ``java.math.BigDecimal`` backed by ``NUMERIC(12,2)`` (``@Column`` precision
 *     12, scale 2) so financial output remains byte-identical; the entity must
 *     carry exactly one JPA ``@Version`` optimistic-lock field (``version`` of
 *     type ``Long``) that has no legacy copybook analogue; and the legacy field
 *     misspelling ``acctExpiraionDate`` / column ``acct_expiraion_date`` (missing
 *     the second ``T``) must be preserved verbatim as a frozen identifier
 *     contract.
 * :output: JUnit 5 / AssertJ assertions only. The class holds no state and
 *     inspects {@code Account} purely through {@code java.lang.reflect}; it opens
 *     no database, Spring context, Testcontainers instance or other external
 *     resource. {@code Account} lives in the same package and is referenced
 *     without an import.
 */
final class AccountTest {

    /**
     * Legacy monetary fields (COBOL ``PIC S9(10)V99``) that must map to
     * ``BigDecimal`` on ``NUMERIC(12,2)`` columns.
     */
    private static final List<String> MONETARY_FIELDS = List.of(
            "acctCurrBal",
            "acctCreditLimit",
            "acctCashCreditLimit",
            "acctCurrCycCredit",
            "acctCurrCycDebit");

    /**
     * Look up a declared field of {@link Account} by name.
     *
     * :param name: the exact declared-field name to resolve.
     * :return: the reflected {@link Field}.
     */
    private static Field field(String name) {
        try {
            return Account.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Account is missing expected field '" + name + "'", e);
        }
    }

    /**
     * Read the {@code @Column} annotation declared on a named field.
     *
     * :param name: the declared-field name.
     * :return: the field's {@link Column} annotation, or ``null`` if absent.
     */
    private static Column column(String name) {
        return field(name).getAnnotation(Column.class);
    }

    /**
     * Report whether {@link Account} declares a field with the given name.
     *
     * :param name: the declared-field name to probe.
     * :return: ``true`` when the field exists, ``false`` otherwise.
     */
    private static boolean fieldExists(String name) {
        try {
            Account.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    /**
     * Collect the non-static, non-synthetic instance fields of {@link Account}.
     *
     * :return: the persistent instance fields, excluding compiler/coverage
     *     synthetics and any static members.
     */
    private static List<Field> instanceFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : Account.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                fields.add(f);
            }
        }
        return fields;
    }

    @Test
    @DisplayName("Account is a JPA @Entity")
    void isJpaEntity() {
        assertThat(Account.class.isAnnotationPresent(Entity.class))
                .as("Account must be annotated with @Entity")
                .isTrue();
    }

    @Test
    @DisplayName("Account maps to the \"accounts\" table")
    void mapsToAccountsTable() {
        Table table = Account.class.getAnnotation(Table.class);
        assertThat(table).as("Account must be annotated with @Table").isNotNull();
        assertThat(table.name()).isEqualTo("accounts");
    }

    @Test
    @DisplayName("acctId is the @Id primary key of type Long")
    void acctIdIsPrimaryKey() {
        Field acctId = field("acctId");
        assertThat(acctId.isAnnotationPresent(Id.class))
                .as("acctId must be annotated with @Id")
                .isTrue();
        assertThat(acctId.getType())
                .as("acctId (COBOL ACCT-ID PIC 9(11)) must be java.lang.Long")
                .isEqualTo(Long.class);
    }

    @Test
    @DisplayName("All five monetary fields are java.math.BigDecimal")
    void monetaryFieldsAreBigDecimal() {
        for (String name : MONETARY_FIELDS) {
            assertThat(field(name).getType())
                    .as("monetary field '%s' must be java.math.BigDecimal", name)
                    .isEqualTo(BigDecimal.class);
        }
    }

    @Test
    @DisplayName("All five monetary fields declare @Column(precision = 12, scale = 2)")
    void monetaryFieldsHaveNumeric12Scale2() {
        for (String name : MONETARY_FIELDS) {
            Column col = column(name);
            assertThat(col)
                    .as("monetary field '%s' must declare @Column", name)
                    .isNotNull();
            assertThat(col.precision())
                    .as("monetary field '%s' must have precision 12 (COBOL S9(10)V99 -> NUMERIC(12,2))", name)
                    .isEqualTo(12);
            assertThat(col.scale())
                    .as("monetary field '%s' must have scale 2 (COBOL S9(10)V99 -> NUMERIC(12,2))", name)
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("No floating-point fields exist anywhere on Account")
    void noFloatingPointFieldsAnywhere() {
        for (Field f : instanceFields()) {
            assertThat(f.getType())
                    .as("field '%s' must not use a lossy floating-point type", f.getName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    @Test
    @DisplayName("Account has exactly one @Version field named \"version\" of type Long")
    void hasExactlyOneVersionField() {
        List<Field> versionFields = new ArrayList<>();
        for (Field f : instanceFields()) {
            if (f.isAnnotationPresent(Version.class)) {
                versionFields.add(f);
            }
        }
        assertThat(versionFields)
                .as("Account must declare exactly one @Version optimistic-lock field")
                .hasSize(1);
        Field version = versionFields.get(0);
        assertThat(version.getName()).isEqualTo("version");
        assertThat(version.getType())
                .as("the @Version field must be java.lang.Long")
                .isEqualTo(Long.class);
    }

    @Test
    @DisplayName("Preserves the misspelled acctExpiraionDate identifier and column")
    void preservesAcctExpiraionDateMisspelling() {
        assertThat(fieldExists("acctExpiraionDate"))
                .as("the misspelled field 'acctExpiraionDate' must exist verbatim")
                .isTrue();
        assertThat(field("acctExpiraionDate").getType())
                .as("acctExpiraionDate (COBOL ACCT-EXPIRAION-DATE PIC X(10)) must be java.lang.String")
                .isEqualTo(String.class);
        Column col = column("acctExpiraionDate");
        assertThat(col).as("acctExpiraionDate must declare @Column").isNotNull();
        assertThat(col.name()).isEqualTo("acct_expiraion_date");
    }

    @Test
    @DisplayName("Does not \"correct\" the expiration-date spelling")
    void doesNotCorrectExpirationSpelling() {
        assertThat(fieldExists("acctExpirationDate"))
                .as("the corrected spelling 'acctExpirationDate' must NOT exist")
                .isFalse();
        assertThat(column("acctExpiraionDate").name())
                .as("the column must not be corrected to 'acct_expiration_date'")
                .isNotEqualTo("acct_expiration_date");
    }
}
