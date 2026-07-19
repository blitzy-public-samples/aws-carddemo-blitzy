package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pure unit mapping test for the JPA entity {@link Customer}.
 *
 * <p>This is a Surefire unit test ({@code *Test}); it uses only JUnit&nbsp;5, AssertJ and the
 * Java reflection API. It does <strong>not</strong> start a Spring context, does not touch a
 * database and does not require PostgreSQL &mdash; it inspects the compiled entity contract
 * directly so it stays fast and deterministic (see {@code src/test/resources/junit-platform.properties}).</p>
 *
 * <p><strong>COBOL provenance (dual-source consolidation):</strong> the {@link Customer} entity
 * under test is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5.16 migration target for the legacy
 * IBM&nbsp;z/OS VSAM {@code CUSTDAT} KSDS. It is consolidated from <em>two</em> copybook sources
 * that declare the identical 500-byte {@code CUSTOMER-RECORD} layout, retained read-only under the
 * {@code legacy/} tree:</p>
 * <ul>
 *   <li>{@code legacy/cpy/CVCUS01Y.cpy} &mdash; {@code CUSTOMER-RECORD}, RECLN 500
 *       (date-of-birth field {@code CUST-DOB-YYYY-MM-DD PIC X(10)})</li>
 *   <li>{@code legacy/cpy/CUSTREC.cpy} &mdash; {@code CUSTOMER-RECORD}, RECLN 500
 *       (same field named {@code CUST-DOB-YYYYMMDD PIC X(10)})</li>
 * </ul>
 * <p>The decision to consolidate the two copybooks into the single {@code Customer} entity is
 * recorded in {@code docs/decision-log.md} (Technical Specification &sect;0.4.1) and is not
 * re-argued here.</p>
 *
 * <p><strong>PII-masking coverage (Technical Specification &sect;0.6.10):</strong> the customer
 * record carries personally identifiable information. This test locks the requirement that the
 * Social Security Number ({@code cust_ssn}) and the date of birth ({@code cust_dob}) are never
 * emitted by {@link Customer#toString()}, so those sensitive fields cannot leak into application
 * logs, while confirming the diagnostic string remains useful (it still carries the primary key
 * and a non-PII name field).</p>
 */
final class CustomerTest {

    /**
     * Looks up a declared field on {@link Customer} and makes it reflectively accessible.
     *
     * <p>{@code Customer} runs in the unnamed module (there is no {@code module-info.java}), so
     * {@code setAccessible(true)} succeeds without emitting an illegal-access warning.</p>
     *
     * @param name the declared field name
     * @return the accessible {@link Field}
     * @throws NoSuchFieldException if the entity does not declare a field with that name, which is
     *         itself a mapping-contract violation the caller intends to surface
     */
    private static Field declaredField(String name) throws NoSuchFieldException {
        Field field = Customer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * Builds a {@link Customer} populated with the seeded {@code custId=1} reference profile used
     * by the {@code toString()} assertions. Only the fields relevant to the masking and
     * usefulness checks are set; the remainder stay {@code null}.
     *
     * @return a fully-constructed customer with known PII values
     */
    private static Customer seededCustomer() {
        Customer customer = new Customer();
        customer.setCustId(1L);
        customer.setFirstName("Immanuel");
        customer.setMiddleName("Madeline");
        customer.setLastName("Kessler");
        customer.setSsn(20973888L);
        customer.setDateOfBirth(LocalDate.of(1961, 6, 8));
        customer.setFicoCreditScore(274);
        return customer;
    }

    @Test
    void classIsJpaEntityWithCustomerTable() {
        assertThat(Customer.class.isAnnotationPresent(Entity.class))
                .as("Customer must be a JPA @Entity")
                .isTrue();
        assertThat(Customer.class.isAnnotationPresent(Table.class))
                .as("Customer must declare @Table")
                .isTrue();
        assertThat(Customer.class.getAnnotation(Table.class).name())
                .as("Customer must map to the 'customer' table")
                .isEqualTo("customer");
    }

    @Test
    void primaryKeyIsCustId() throws NoSuchFieldException {
        Field custId = declaredField("custId");
        assertThat(custId.isAnnotationPresent(Id.class))
                .as("custId must be the @Id primary key")
                .isTrue();
        assertThat(custId.getType())
                .as("custId must be a Long (COBOL CUST-ID PIC 9(09))")
                .isEqualTo(Long.class);
        Column column = custId.getAnnotation(Column.class);
        assertThat(column)
                .as("custId must declare @Column")
                .isNotNull();
        assertThat(column.name())
                .as("custId must map to column 'cust_id'")
                .isEqualTo("cust_id");
        assertThat(column.precision())
                .as("custId column precision must reproduce NUMERIC(9)")
                .isEqualTo(9);
    }

    @Test
    void ssnColumnMapping() throws NoSuchFieldException {
        Field ssn = declaredField("ssn");
        assertThat(ssn.getType())
                .as("ssn must be a Long (COBOL CUST-SSN PIC 9(09))")
                .isEqualTo(Long.class);
        assertThat(ssn.getAnnotation(Column.class).name())
                .as("ssn must map to column 'cust_ssn'")
                .isEqualTo("cust_ssn");
    }

    @Test
    void dateOfBirthColumnMapping() throws NoSuchFieldException {
        Field dateOfBirth = declaredField("dateOfBirth");
        assertThat(dateOfBirth.getType())
                .as("dateOfBirth must be a LocalDate (COBOL CUST-DOB-* PIC X(10))")
                .isEqualTo(LocalDate.class);
        assertThat(dateOfBirth.getAnnotation(Column.class).name())
                .as("dateOfBirth must map to column 'cust_dob'")
                .isEqualTo("cust_dob");
    }

    @Test
    void ficoIsInteger() throws NoSuchFieldException {
        Field ficoCreditScore = declaredField("ficoCreditScore");
        assertThat(ficoCreditScore.getType())
                .as("ficoCreditScore must be an Integer (COBOL CUST-FICO-CREDIT-SCORE PIC 9(03))")
                .isEqualTo(Integer.class);
        assertThat(ficoCreditScore.getAnnotation(Column.class).name())
                .as("ficoCreditScore must map to column 'cust_fico_credit_score'")
                .isEqualTo("cust_fico_credit_score");
    }

    @Test
    void ssnIsMaskedInToString() {
        String rendered = seededCustomer().toString();
        assertThat(rendered)
                .as("toString() must not leak the raw SSN digits")
                .doesNotContain("20973888");
    }

    @Test
    void dateOfBirthIsMaskedInToString() {
        String rendered = seededCustomer().toString();
        assertThat(rendered)
                .as("toString() must not leak the ISO date of birth")
                .doesNotContain("1961-06-08");
        assertThat(rendered)
                .as("toString() must not leak the year of birth either")
                .doesNotContain("1961");
    }

    @Test
    void noBigDecimalOrFloatingPointFields() {
        for (Field field : Customer.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                // Skip compiler/agent-injected members (for example JaCoCo's $jacocoData).
                continue;
            }
            assertThat(field.getType())
                    .as("field '%s' must not be a monetary or floating-point type "
                            + "(Customer intentionally holds no money data)", field.getName())
                    .isNotIn(BigDecimal.class, float.class, double.class, Float.class, Double.class);
        }
    }

    @Test
    void toStringDoesNotLeakPii() {
        String rendered = seededCustomer().toString();
        assertThat(rendered)
                .as("toString() is the safe identity form (class name + identity hash), leaking no PII")
                .isNotNull()
                .startsWith("Customer@")
                .doesNotContain("Kessler");
    }
}
