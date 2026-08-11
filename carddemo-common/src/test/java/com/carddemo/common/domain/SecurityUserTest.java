package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the {@link SecurityUser} JPA entity contract.
 *
 * :purpose: Lock the migration-critical shape of the security-user entity derived from the
 *     legacy COBOL ``SEC-USER-DATA`` record (copybook ``CSUSR01Y``). It verifies: (a) the JPA
 *     mapping to the ``security_users`` table and the ``secUsrId`` primary key; (b) the
 *     ``secUsrPwd`` column WIDENING from the legacy ``PIC X(08)`` plaintext width to a length
 *     that holds a BCrypt/PBKDF2 hash at rest (AAP 0.6.7); (c) that ``secUsrType`` is retained
 *     as a raw single-character ``String`` ('A'/'U') rather than a Java enum so the security
 *     layer performs role resolution on the preserved raw value (AAP 0.6.7); and (d) that the
 *     sensitive ``secUsrPwd`` value is excluded from ``toString`` so it cannot leak into logs.
 * :output: JUnit 5 / AssertJ assertions only. The class holds no state and touches no database,
 *     Spring context, Testcontainers, or other external resource; field access uses reflection
 *     to avoid coupling to accessor names and to read the persistence annotations directly.
 */
final class SecurityUserTest {

    // ------------------------------------------------------------------ helpers

    /**
     * Look up a declared field of {@link SecurityUser} by name.
     *
     * :param name: the declared field name (for example ``secUsrPwd``).
     * :return: the reflected {@link Field}.
     */
    private static Field field(String name) throws NoSuchFieldException {
        return SecurityUser.class.getDeclaredField(name);
    }

    /**
     * Read the ``jakarta.persistence`` {@link Column} annotation of a declared field.
     *
     * :param name: the declared field name whose column mapping is required.
     * :return: the {@link Column} annotation present on the field.
     */
    private static Column column(String name) throws NoSuchFieldException {
        Column mapping = field(name).getAnnotation(Column.class);
        assertThat(mapping)
                .as("field '%s' must carry a @Column mapping", name)
                .isNotNull();
        return mapping;
    }

    /**
     * Assign a private field of a {@link SecurityUser} instance by reflection.
     *
     * :param user: the target instance to mutate.
     * :param name: the declared field name to set.
     * :param value: the value to assign.
     */
    private static void setField(SecurityUser user, String name, Object value)
            throws ReflectiveOperationException {
        Field target = field(name);
        target.setAccessible(true);
        target.set(user, value);
    }

    /**
     * Construct a {@link SecurityUser} through its public no-argument constructor.
     *
     * :return: a fresh, empty {@link SecurityUser} instance.
     */
    private static SecurityUser newUser() throws ReflectiveOperationException {
        return SecurityUser.class.getDeclaredConstructor().newInstance();
    }

    // --------------------------------------------------- entity structure + key

    /**
     * Verify the class is a JPA-managed entity.
     *
     * :return: nothing; fails when the ``@Entity`` annotation is absent.
     */
    @Test
    @DisplayName("SecurityUser is a JPA @Entity")
    void isJpaEntity() {
        assertThat(SecurityUser.class.isAnnotationPresent(Entity.class))
                .as("SecurityUser must be annotated @Entity")
                .isTrue();
    }

    /**
     * Verify the entity maps to the ``security_users`` table.
     *
     * :return: nothing; fails when ``@Table`` is missing or names a different table.
     */
    @Test
    @DisplayName("SecurityUser maps to the security_users table")
    void mapsToSecurityUsersTable() {
        Table table = SecurityUser.class.getAnnotation(Table.class);
        assertThat(table).as("SecurityUser must declare @Table").isNotNull();
        assertThat(table.name()).isEqualTo("security_users");
    }

    /**
     * Verify ``secUsrId`` is the ``String`` primary key.
     *
     * :return: nothing; fails when the field lacks ``@Id`` or is not a ``String``.
     */
    @Test
    @DisplayName("secUsrId is the @Id primary key of type String")
    void secUsrIdIsPrimaryKey() throws NoSuchFieldException {
        Field id = field("secUsrId");
        assertThat(id.isAnnotationPresent(Id.class))
                .as("secUsrId must be annotated @Id")
                .isTrue();
        assertThat(id.getType()).isEqualTo(String.class);
    }

    // ------------------------------------------ password column WIDENING (0.6.7)

    /**
     * Verify the password field is a ``String``.
     *
     * :return: nothing; fails when ``secUsrPwd`` is not typed ``String``.
     */
    @Test
    @DisplayName("secUsrPwd is a String field")
    void passwordFieldIsString() throws NoSuchFieldException {
        assertThat(field("secUsrPwd").getType()).isEqualTo(String.class);
    }

    /**
     * Verify the password column was widened to hold an encoded hash.
     *
     * :return: nothing; fails when the column length is below a BCrypt hash (60) or still
     *     matches the legacy ``PIC X(08)`` plaintext width (8).
     */
    @Test
    @DisplayName("secUsrPwd column is widened for an encoded hash, not the legacy X(08)")
    void passwordColumnWidenedForHash() throws NoSuchFieldException {
        int len = column("secUsrPwd").length();
        assertThat(len)
                .as("password column must be widened to hold a BCrypt/PBKDF2 hash (>= 60 chars)")
                .isGreaterThanOrEqualTo(60);
        assertThat(len)
                .as("password column must not retain the legacy plaintext X(08) width of 8")
                .isNotEqualTo(8);
    }

    // ------------------------------------ secUsrType is a RAW String, not an enum

    /**
     * Verify ``secUsrType`` stays a raw single-character ``String`` and never an enum.
     *
     * :return: nothing; fails when the field is an enum or its column is not length 1.
     */
    @Test
    @DisplayName("secUsrType is a raw single-character String, not an enum")
    void userTypeIsRawSingleCharString() throws NoSuchFieldException {
        Field type = field("secUsrType");
        assertThat(type.getType()).isEqualTo(String.class);
        assertThat(type.getType().isEnum())
                .as("secUsrType must stay a raw 'A'/'U' String, never a Java enum")
                .isFalse();
        assertThat(column("secUsrType").length()).isEqualTo(1);
    }

    // ----------------------------------------- sensitive-field masking (0.6.7)

    /**
     * Verify ``toString`` masks the sensitive password hash while remaining non-vacuous.
     *
     * :return: nothing; fails when the rendered text leaks the password sentinel or omits
     *     every non-sensitive field (which would make the masking check vacuous).
     */
    @Test
    @DisplayName("toString() masks the sensitive password hash")
    void toStringExcludesPassword() throws ReflectiveOperationException {
        SecurityUser user = newUser();
        setField(user, "secUsrPwd", "PWDSENTINELHASH$2a$10$abcdefSENTINEL");
        setField(user, "secUsrId", "USERSENT");
        setField(user, "secUsrFname", "FNAMESENTINEL");

        String rendered = user.toString();

        assertThat(rendered)
                .as("toString() must not leak the encoded password value")
                .doesNotContain("PWDSENTINELHASH")
                .doesNotContain("PWDSENTINELHASH$2a$10$abcdefSENTINEL");
        assertThat(rendered)
                .as("toString() must render at least one non-sensitive field (non-vacuous masking)")
                .containsAnyOf("USERSENT", "FNAMESENTINEL");
    }

    // -------------------------------------------- public no-arg constructor (JPA)

    /**
     * Verify the entity exposes the public no-argument constructor the JPA provider requires.
     *
     * :return: nothing; fails when no public no-arg constructor is present.
     */
    @Test
    @DisplayName("SecurityUser exposes a public no-argument constructor for JPA")
    void hasPublicNoArgConstructor() throws NoSuchMethodException {
        var constructor = SecurityUser.class.getDeclaredConstructor();
        assertThat(Modifier.isPublic(constructor.getModifiers()))
                .as("JPA requires a public no-argument constructor")
                .isTrue();
    }
}
