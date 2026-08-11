package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reflection-based unit specification for the {@link Customer} JPA entity.
 *
 * :purpose: Lock the frozen entity contract of the re-platformed COBOL
 *     ``CUSTOMER-RECORD`` (copybook ``CVCUS01Y``) and, above all, guarantee the
 *     sensitive-field masking mandated by AAP 0.6.7: the social-security number
 *     (``custSsn``) and the government-issued id (``custGovtIssuedId``) must never
 *     appear verbatim in ``Customer.toString()`` diagnostic output. Structural
 *     checks confirm the ``@Entity`` / ``@Table(name = "customers")`` mapping and
 *     the ``@Id custId`` ``Long`` primary key. Fields are exercised through
 *     reflection so the test never depends on setter naming.
 * :output: JUnit 5 / AssertJ assertions only. The class holds no state and touches
 *     no database, Spring context, or other external resource (pure-JVM reflection
 *     plus a behavioral ``toString()`` check).
 */
final class CustomerTest {

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    /**
     * Resolves a declared field of {@link Customer} by name.
     *
     * :param name: the declared field name to resolve.
     * :return: the reflected ``java.lang.reflect.Field``.
     */
    private static Field field(String name) {
        try {
            return Customer.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Customer must declare field '" + name + "'", e);
        }
    }

    /**
     * Reports whether {@link Customer} declares a field with the given name.
     *
     * :param name: the declared field name to probe.
     * :return: ``true`` when the field exists; ``false`` otherwise.
     */
    private static boolean fieldExists(String name) {
        try {
            Customer.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    /**
     * Assigns a value to a declared field of a {@link Customer} instance.
     *
     * :param c: the target customer instance.
     * :param name: the declared field name to assign.
     * :param value: the value to store into the field.
     */
    private static void setField(Customer c, String name, Object value) {
        Field f = field(name);
        f.setAccessible(true);
        try {
            f.set(c, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Could not set field '" + name + "' on Customer", e);
        }
    }

    /**
     * Instantiates a {@link Customer} through its public no-argument constructor.
     *
     * :return: a fresh, empty ``Customer`` instance.
     */
    private static Customer newCustomer() {
        try {
            return Customer.class.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Customer must provide a public no-arg constructor", e);
        }
    }

    // ------------------------------------------------------------------
    // Entity structure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Customer is annotated as a JPA @Entity")
    void isJpaEntity() {
        assertThat(Customer.class.isAnnotationPresent(Entity.class)).isTrue();
    }

    @Test
    @DisplayName("Customer maps to the \"customers\" table")
    void mapsToCustomersTable() {
        Table table = Customer.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("customers");
    }

    @Test
    @DisplayName("custId is the @Id primary key of type Long")
    void custIdIsPrimaryKey() {
        Field custId = field("custId");
        assertThat(custId.isAnnotationPresent(Id.class)).isTrue();
        assertThat(custId.getType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("Sensitive custSsn and custGovtIssuedId exist as private String fields")
    void sensitiveFieldsExistAsString() {
        assertThat(fieldExists("custSsn")).isTrue();
        assertThat(fieldExists("custGovtIssuedId")).isTrue();

        Field ssn = field("custSsn");
        Field govtId = field("custGovtIssuedId");

        assertThat(ssn.getType()).isEqualTo(String.class);
        assertThat(govtId.getType()).isEqualTo(String.class);

        // Encapsulation: PII must be private so the only diagnostic rendering
        // path is the masked toString(), never a public field read.
        assertThat(Modifier.isPrivate(ssn.getModifiers())).isTrue();
        assertThat(Modifier.isPrivate(govtId.getModifiers())).isTrue();
    }

    // ------------------------------------------------------------------
    // Sensitive-field masking (AAP 0.6.7 — CORE)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toString() excludes custSsn and custGovtIssuedId (AAP 0.6.7)")
    void toStringExcludesSsnAndGovtId() {
        Customer c = newCustomer();
        setField(c, "custSsn", "SSNSENTINEL999");
        setField(c, "custGovtIssuedId", "GOVTIDSENTINELXYZ");
        setField(c, "custId", 987654321L);
        setField(c, "custFirstName", "FIRSTNAMESENTINEL");

        String ts = c.toString();

        // Core guarantee: raw PII sentinels must never surface verbatim.
        assertThat(ts).doesNotContain("SSNSENTINEL999");
        assertThat(ts).doesNotContain("GOVTIDSENTINELXYZ");

        // Non-vacuous sanity: toString() is a real override that renders
        // non-sensitive data (guards against the default Object form).
        assertThat(ts.contains("987654321") || ts.contains("FIRSTNAMESENTINEL")).isTrue();
    }
}
