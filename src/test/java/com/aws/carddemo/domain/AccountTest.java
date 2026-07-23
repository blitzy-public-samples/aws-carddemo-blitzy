package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pure unit mapping tests for the JPA entity {@link Account}.
 *
 * <p>These tests contain NO database and NO Spring context: they inspect the entity's structure
 * purely by reflection and exercise its value semantics ({@code equals}/{@code hashCode}/
 * {@code toString}) through the public API. The class name ends in {@code Test}, so it is picked up
 * by the Maven Surefire (unit-test) plugin, never by Failsafe.</p>
 *
 * <p><strong>COBOL oracle:</strong> {@code Account} is migrated one-for-one from the legacy COBOL
 * copybook {@code ACCOUNT-RECORD} (record length 300) defined at {@code legacy/cpy/CVACT01Y.cpy},
 * which backed the VSAM {@code ACCTDAT} KSDS. This test locks the source-to-target mapping so the
 * migration cannot silently drift from the mainframe contract (traceability, spec section 0.6.10).</p>
 *
 * <p><strong>Decimal fidelity (spec section 0.6.1):</strong> {@code Account} carries the most
 * monetary fields of any entity in the application, so this class is the primary guard for the
 * decimal-fidelity rule. It proves that all five balance/limit fields are
 * {@link java.math.BigDecimal} declared as {@code precision = 12, scale = 2} (COBOL
 * {@code S9(10)V99}) and that NO floating-point ({@code float}/{@code double}/{@code Float}/
 * {@code Double}) field exists anywhere on the entity.</p>
 *
 * <p><strong>Preserved misspelling (spec section 0.4.1):</strong> {@link #expiraionMisspellingIsPreserved()}
 * intentionally asserts the field {@code expiraionDate} and its column {@code acct_expiraion_date}.
 * The misspelling "EXPIRAION" originates in the COBOL field {@code ACCT-EXPIRAION-DATE} and is
 * retained verbatim for complete traceability. This test exists specifically so that a future
 * maintainer does NOT "correct" the spelling and thereby break parity with the legacy record.</p>
 */
class AccountTest {

    /**
     * The five monetary fields of {@code Account}, mapped from their Java field name to the exact
     * snake_case database column name expected on the {@code @Column} annotation. Every one of
     * these must be a {@code BigDecimal(precision = 12, scale = 2)} per the decimal-fidelity rule.
     */
    private static final Map<String, String> MONEY_FIELDS = Map.of(
            "currBal", "acct_curr_bal",
            "creditLimit", "acct_credit_limit",
            "cashCreditLimit", "acct_cash_credit_limit",
            "currCycCredit", "acct_curr_cyc_credit",
            "currCycDebit", "acct_curr_cyc_debit");

    /**
     * Fetches a declared field by name and makes it reflectively accessible.
     *
     * @param name the declared field name on {@link Account}
     * @return the accessible {@link Field}
     * @throws NoSuchFieldException if {@code Account} declares no such field (a mapping regression)
     */
    private static Field declaredField(String name) throws NoSuchFieldException {
        Field field = Account.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Test 1: the class is a JPA entity mapped to the {@code account} table. */
    @Test
    void classIsJpaEntityWithAccountTable() {
        assertThat(Account.class.isAnnotationPresent(Entity.class))
                .as("Account must be annotated with @Entity")
                .isTrue();

        Table table = Account.class.getAnnotation(Table.class);
        assertThat(table).as("Account must declare @Table").isNotNull();
        assertThat(table.name()).as("@Table.name").isEqualTo("account");
    }

    /**
     * Test 2: the primary key is {@code acctId}, a {@code Long} mapped to column {@code acct_id}
     * with {@code precision = 11} (COBOL {@code ACCT-ID PIC 9(11)}).
     */
    @Test
    void primaryKeyIsAcctId() throws Exception {
        Field acctId = declaredField("acctId");

        assertThat(acctId.isAnnotationPresent(Id.class))
                .as("acctId must be the @Id primary key")
                .isTrue();
        assertThat(acctId.getType()).as("acctId type").isEqualTo(Long.class);

        Column column = acctId.getAnnotation(Column.class);
        assertThat(column).as("acctId must declare @Column").isNotNull();
        assertThat(column.name()).as("acctId column name").isEqualTo("acct_id");
        // precision is explicitly declared on the production entity, so it is asserted here.
        assertThat(column.precision()).as("acctId column precision").isEqualTo(11);
    }

    /**
     * Test 3 (decimal-fidelity guard): every one of the five money fields is a
     * {@link java.math.BigDecimal} declared as {@code precision = 12, scale = 2} and mapped to the
     * expected snake_case column. Driven by {@link #MONEY_FIELDS} so all five are checked uniformly.
     */
    @Test
    void allFiveMoneyFieldsAreBigDecimal12Scale2() throws Exception {
        for (Map.Entry<String, String> entry : MONEY_FIELDS.entrySet()) {
            String fieldName = entry.getKey();
            String expectedColumn = entry.getValue();

            Field field = declaredField(fieldName);

            assertThat(field.getType())
                    .as("money field %s must be BigDecimal (never floating point)", fieldName)
                    .isEqualTo(BigDecimal.class);

            Column column = field.getAnnotation(Column.class);
            assertThat(column).as("money field %s must declare @Column", fieldName).isNotNull();
            assertThat(column.precision())
                    .as("money field %s precision", fieldName)
                    .isEqualTo(12);
            assertThat(column.scale())
                    .as("money field %s scale", fieldName)
                    .isEqualTo(2);
            assertThat(column.name())
                    .as("money field %s column name", fieldName)
                    .isEqualTo(expectedColumn);
        }
    }

    /**
     * Test 4 (decimal-fidelity guard): NO field on {@code Account} is a floating-point type.
     * Synthetic fields (for example the JaCoCo {@code $jacocoData} field injected during coverage
     * runs) are skipped so the assertion is stable under instrumentation.
     */
    @Test
    void noFloatingPointFieldsAnywhere() {
        List<String> floatingPointFields = new ArrayList<>();
        for (Field field : Account.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            Class<?> type = field.getType();
            if (type == float.class || type == double.class
                    || type == Float.class || type == Double.class) {
                floatingPointFields.add(field.getName());
            }
        }
        assertThat(floatingPointFields)
                .as("no floating-point fields are permitted on a monetary entity (decimal fidelity)")
                .isEmpty();
    }

    /**
     * Test 5: the three account date fields ({@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE},
     * {@code ACCT-REISSUE-DATE}, all {@code PIC X(10)}) are modelled as {@link java.time.LocalDate}.
     */
    @Test
    void threeDateFieldsAreLocalDate() throws Exception {
        assertThat(declaredField("openDate").getType())
                .as("openDate type").isEqualTo(LocalDate.class);
        assertThat(declaredField("expiraionDate").getType())
                .as("expiraionDate type").isEqualTo(LocalDate.class);
        assertThat(declaredField("reissueDate").getType())
                .as("reissueDate type").isEqualTo(LocalDate.class);
    }

    /**
     * Test 6 (intentional misspelling lock): the entity declares a field literally named
     * {@code expiraionDate} mapped to column {@code acct_expiraion_date}.
     *
     * <p>The misspelling "EXPIRAION" is preserved verbatim from the COBOL field
     * {@code ACCT-EXPIRAION-DATE} in {@code legacy/cpy/CVACT01Y.cpy}. This is DELIBERATE and MUST NOT
     * be "fixed": correcting it to {@code expirationDate} / {@code acct_expiration_date} would break
     * one-for-one traceability with the legacy record. This test fails loudly if anyone renames it.</p>
     */
    @Test
    void expiraionMisspellingIsPreserved() throws Exception {
        assertThatCode(() -> Account.class.getDeclaredField("expiraionDate"))
                .as("entity must preserve the intentional COBOL misspelling field 'expiraionDate'")
                .doesNotThrowAnyException();

        Field expiraion = declaredField("expiraionDate");
        Column column = expiraion.getAnnotation(Column.class);
        assertThat(column).as("expiraionDate must declare @Column").isNotNull();
        assertThat(column.name())
                .as("the preserved-misspelling column name")
                .isEqualTo("acct_expiraion_date");
    }

    /**
     * Test 7: {@code Account} overrides {@code equals}/{@code hashCode} using the primary key
     * {@code acctId} only. Two instances with the same {@code acctId} are equal with equal hash
     * codes; instances with different keys are not equal.
     *
     * <p>Note the null-key subtlety: because equality is key-based, two brand-new instances (both
     * with a {@code null} {@code acctId}) would compare equal. This test therefore only asserts
     * inequality between a keyed instance and a fresh (null-key) instance, never between two fresh
     * instances.</p>
     */
    @Test
    void equalsAndHashCodeOnAcctId() {
        Account a1 = new Account();
        a1.setAcctId(100L);
        Account a2 = new Account();
        a2.setAcctId(100L);
        Account a3 = new Account();
        a3.setAcctId(200L);

        // Reflexive.
        assertThat(a1).isEqualTo(a1);

        // Same key on two distinct instances => equal, with equal hash codes (proves override).
        assertThat(a1).isEqualTo(a2);
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode());

        // Different key => not equal.
        assertThat(a1).isNotEqualTo(a3);

        // Robust equals contract: not equal to null nor to an unrelated type.
        assertThat(a1.equals(null)).as("equals(null) must be false").isFalse();
        assertThat(a1.equals("not-an-account")).as("equals(other type) must be false").isFalse();

        // A keyed instance is not equal to a fresh (null-key) instance.
        Account fresh = new Account();
        assertThat(a1).isNotEqualTo(fresh);
    }

    /**
     * Test 8: {@code toString} is safe for diagnostics. {@code Account} holds no PII or credential
     * fields; the hardened representation is the safe identity form (class name + identity hash) that
     * exposes no field content (keep field values out of logs, CWE-532). Money formatting is
     * intentionally NOT asserted to avoid brittleness.
     */
    @Test
    void toStringIsSafe() {
        Account account = new Account();
        account.setAcctId(100L);

        String rendered = account.toString();
        assertThat(rendered).as("toString must be non-null").isNotNull();
        assertThat(rendered)
                .as("toString uses the safe identity form and exposes no field content")
                .startsWith("Account@");
    }
}
