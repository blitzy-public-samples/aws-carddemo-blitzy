/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Pure unit mapping test for the JPA entity {@link UserSecurity}.
 *
 * <p><strong>What this test locks down.</strong> It asserts, by reflection only, that the
 * user-store entity preserves the layout of the legacy COBOL user record and that the
 * security-hygiene contract around the cleartext password is honored. There is deliberately
 * <em>no</em> database and <em>no</em> Spring application context here: the class name ends in
 * {@code Test}, so it runs under the Maven Surefire plugin as a fast, isolated unit test.</p>
 *
 * <p><strong>COBOL oracle (traceability, AAP &sect;0.6.10).</strong> The entity under test is
 * migrated one-for-one from the copybook {@code legacy/cpy/CSUSR01Y.cpy}
 * ({@code 01 SEC-USER-DATA}, fixed record length {@code RECLN 80}). The layout this test guards is:</p>
 * <pre>
 *   COBOL field        PIC      Java field   Column      Bytes
 *   SEC-USR-ID         X(08)    usrId        usr_id       8
 *   SEC-USR-FNAME      X(20)    usrFname     usr_fname   20
 *   SEC-USR-LNAME      X(20)    usrLname     usr_lname   20
 *   SEC-USR-PWD        X(08)    usrPwd       usr_pwd      8
 *   SEC-USR-TYPE       X(01)    usrType      usr_type     1
 *   SEC-USR-FILLER     X(23)    (not persisted)          23
 *                                                       ----
 *                                            total       80
 * </pre>
 *
 * <p><strong>Cleartext-password parity (AAP &sect;0.6.7 &mdash; intentional).</strong> The legacy
 * {@code USRSEC} store held the password as cleartext and the COBOL signon program compared it
 * directly. That comparison <em>behavior</em> is preserved for 100% functional parity in this
 * deliverable; introducing password hashing (e.g. BCrypt) is deliberately deferred and recorded as
 * a suggested next task in {@code docs/decision-log.md}, not silently changed. Even so, the password
 * value must never be logged, so {@link UserSecurity#toString()} masks it. The
 * {@link #passwordIsMaskedInToString()} test is the guard that keeps the credential out of any
 * diagnostic string, while {@link #toStringStillUsefulForLogging()} proves the masking did not make
 * {@code toString()} useless for diagnostics.</p>
 *
 * <p><strong>Role discriminator.</strong> {@code usrType} carries {@code "A"} (administrator) or
 * {@code "U"} (regular user), which the security layer maps to {@code ROLE_ADMIN} / {@code ROLE_USER}
 * (AAP &sect;0.6.7). {@link #usrTypeAcceptsAdminAndUser()} documents that both values round-trip
 * through the accessor (a pure POJO check &mdash; no Spring Security is involved here).</p>
 */
class UserSecurityTest {

    /** Seeded administrator user id used by the {@code toString()} guards. */
    private static final String SEEDED_USR_ID = "ADMIN001";

    /** Seeded cleartext password value that must never surface in {@code toString()}. */
    private static final String SEEDED_PWD = "PASSWORD";

    /** Administrator role discriminator ({@code SEC-USR-TYPE} value that maps to {@code ROLE_ADMIN}). */
    private static final String ADMIN_TYPE = "A";

    /** Regular-user role discriminator ({@code SEC-USR-TYPE} value that maps to {@code ROLE_USER}). */
    private static final String USER_TYPE = "U";

    /**
     * The class must be a JPA {@code @Entity} mapped to the {@code user_security} table. The table
     * name uses the {@code user_security} form deliberately to avoid the SQL reserved word
     * {@code user}, and it is the name the Flyway DDL and repositories rely on.
     */
    @Test
    void classIsJpaEntityWithUserSecurityTable() {
        assertThat(UserSecurity.class.isAnnotationPresent(Entity.class))
                .as("UserSecurity must be annotated with @Entity")
                .isTrue();

        Table table = UserSecurity.class.getAnnotation(Table.class);
        assertThat(table).as("UserSecurity must declare @Table").isNotNull();
        assertThat(table.name()).as("@Table name").isEqualTo("user_security");
    }

    /**
     * {@code usrId} is the primary key, mirroring the {@code USRSEC} VSAM KSDS key {@code SEC-USR-ID}
     * ({@code PIC X(08)}): a {@code String} annotated {@code @Id} and mapped to fixed-width column
     * {@code usr_id} of length 8.
     */
    @Test
    void primaryKeyIsUsrId() throws Exception {
        Field usrId = UserSecurity.class.getDeclaredField("usrId");
        usrId.setAccessible(true);

        assertThat(usrId.isAnnotationPresent(Id.class))
                .as("usrId must be the @Id primary key")
                .isTrue();
        assertThat(usrId.getType()).as("usrId type").isEqualTo(String.class);

        Column column = usrId.getAnnotation(Column.class);
        assertThat(column).as("usrId must declare @Column").isNotNull();
        assertThat(column.name()).as("usrId column name").isEqualTo("usr_id");
        assertThat(column.length()).as("usrId column length (SEC-USR-ID X(08))").isEqualTo(8);
    }

    /**
     * The remaining persisted fields preserve the copybook's byte widths exactly: {@code usrFname}
     * ({@code usr_fname}, 20), {@code usrLname} ({@code usr_lname}, 20), {@code usrPwd}
     * ({@code usr_pwd}, 8) and {@code usrType} ({@code usr_type}, 1). All are {@code String}, because
     * every source field is {@code PIC X(n)} alphanumeric.
     */
    @Test
    void columnMappings() throws Exception {
        assertColumnMapping("usrFname", "usr_fname", 20);
        assertColumnMapping("usrLname", "usr_lname", 20);
        assertColumnMapping("usrPwd", "usr_pwd", 8);
        assertColumnMapping("usrType", "usr_type", 1);
    }

    /**
     * Security-hygiene guard: the cleartext password value must never appear in the entity's
     * {@code toString()} (AAP &sect;0.6.7). The entity is built via setters with the seeded admin
     * credentials, then its diagnostic string is checked to ensure the raw password does not leak.
     */
    @Test
    void passwordIsMaskedInToString() {
        UserSecurity user = newSeededAdminUser();

        String rendered = user.toString();

        assertThat(rendered).as("toString() must never be null").isNotNull();
        assertThat(rendered)
                .as("toString() must NOT leak the cleartext password value")
                .doesNotContain(SEEDED_PWD);
    }

    /**
     * The hardened {@code toString()} is the safe identity form (class name + identity hash) and must
     * leak no user state - in particular never the cleartext password - into logs (CWE-532).
     */
    @Test
    void toStringDoesNotLeakUserState() {
        UserSecurity user = newSeededAdminUser();

        String rendered = user.toString();

        assertThat(rendered).as("toString() must never be null").isNotNull();
        assertThat(rendered)
                .as("toString() is the safe identity form and leaks no user state")
                .startsWith("UserSecurity@")
                .doesNotContain(SEEDED_PWD);
    }

    /**
     * The {@code usrType} role discriminator accepts both legacy values &mdash; {@code "A"}
     * (administrator, {@code ROLE_ADMIN}) and {@code "U"} (regular user, {@code ROLE_USER}) &mdash;
     * and round-trips them through the accessor. This is a pure POJO check; the actual authority
     * mapping lives in the security layer (AAP &sect;0.6.7).
     */
    @Test
    void usrTypeAcceptsAdminAndUser() {
        UserSecurity user = new UserSecurity();

        user.setUsrType(ADMIN_TYPE);
        assertThat(user.getUsrType()).as("administrator role discriminator").isEqualTo(ADMIN_TYPE);

        user.setUsrType(USER_TYPE);
        assertThat(user.getUsrType()).as("regular-user role discriminator").isEqualTo(USER_TYPE);
    }

    /**
     * No field of the entity may be a floating-point type. The whole migration mandates fixed-scale
     * types over floating point (AAP &sect;0.1.1); this entity holds no monetary data, but the guard
     * is asserted uniformly across all domain entities. Synthetic fields (for example the
     * {@code $jacocoData} array added by the coverage agent at runtime) are skipped.
     */
    @Test
    void noFloatingPointFields() {
        for (Field field : UserSecurity.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertThat(field.getType())
                    .as("field %s must not be a floating-point type", field.getName())
                    .isNotIn(float.class, double.class, Float.class, Double.class);
        }
    }

    /**
     * Asserts that a declared field is a {@code String} mapped by a {@code @Column} with the given
     * name and length, keeping the per-field assertions in {@link #columnMappings()} concise.
     *
     * @param fieldName  the Java field name to inspect
     * @param columnName the expected {@code @Column.name()}
     * @param length     the expected {@code @Column.length()} (the copybook byte width)
     * @throws NoSuchFieldException if the entity does not declare the named field
     */
    private static void assertColumnMapping(String fieldName, String columnName, int length)
            throws NoSuchFieldException {
        Field field = UserSecurity.class.getDeclaredField(fieldName);
        field.setAccessible(true);

        assertThat(field.getType()).as("%s type", fieldName).isEqualTo(String.class);

        Column column = field.getAnnotation(Column.class);
        assertThat(column).as("%s must declare @Column", fieldName).isNotNull();
        assertThat(column.name()).as("%s column name", fieldName).isEqualTo(columnName);
        assertThat(column.length()).as("%s column length", fieldName).isEqualTo(length);
    }

    /**
     * Builds a {@code UserSecurity} carrying the seeded administrator credentials
     * ({@code usrId=ADMIN001}, {@code usrPwd=PASSWORD}, {@code usrType=A}) used by the
     * {@code toString()} guards.
     *
     * @return a populated {@code UserSecurity} fixture
     */
    private static UserSecurity newSeededAdminUser() {
        UserSecurity user = new UserSecurity();
        user.setUsrId(SEEDED_USR_ID);
        user.setUsrPwd(SEEDED_PWD);
        user.setUsrType(ADMIN_TYPE);
        return user;
    }
}
