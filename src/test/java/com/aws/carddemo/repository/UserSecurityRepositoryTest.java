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
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.UserSecurity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed Spring Data JPA integration tests for
 * {@link UserSecurityRepository} exercised against a real PostgreSQL&nbsp;16
 * database.
 *
 * <p><strong>Behavioral parity.</strong> These tests validate the keyed and
 * key-ordered access that the legacy COBOL security programs performed against
 * the VSAM KSDS {@code USRSEC.VSAM.KSDS} (record layout copybook
 * {@code CSUSR01Y.cpy}, {@code KEYLEN=8}), now re-expressed as set-based access
 * over the PostgreSQL {@code user_security} table (AAP&nbsp;&sect;0.4.3,
 * &sect;0.5.3, &sect;0.9.2):
 * <ul>
 *   <li>sign-on ({@code legacy/cbl/COSGN00C.cbl}, paragraph
 *       {@code READ-USER-SEC-FILE}) issues a keyed
 *       {@code EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID)} and treats a
 *       {@code NOTFND} response as an invalid user id &mdash; reproduced by
 *       {@link UserSecurityRepository#findBySecUsrId(String)} returning an
 *       {@link Optional} (present on hit, empty on miss);</li>
 *   <li>the admin user-list screen ({@code legacy/cbl/COUSR00C.cbl}) browses
 *       {@code USRSEC} in ascending key order
 *       ({@code STARTBR}/{@code READNEXT}) &mdash; reproduced by
 *       {@link UserSecurityRepository#findAllByOrderBySecUsrIdAsc()};</li>
 *   <li>the online update screen ({@code legacy/cbl/COUSR02C.cbl}) performs a
 *       READ&#45;UPDATE&#45;REWRITE cycle &mdash; whose last-writer integrity is
 *       reproduced by the entity's JPA {@code @Version} optimistic lock, asserted
 *       here to be assigned on insert and incremented on update.</li>
 * </ul>
 *
 * <p><strong>Harness.</strong> A {@link DataJpaTest} slice runs against a
 * disposable {@code postgres:16-alpine} container wired into Spring Boot's
 * datasource by {@link ServiceConnection}, so no JDBC URL, username, or password
 * is ever hardcoded (AAP&nbsp;&sect;0.8.1, &sect;0.9.3). The embedded-database
 * replacement is switched off with {@link AutoConfigureTestDatabase.Replace#NONE}
 * so the real container is used. The {@code test} profile keeps Hibernate at
 * {@code ddl-auto=validate}, letting Flyway ({@code V1__schema.sql} +
 * {@code V2__reference_data.sql}) own the schema; the {@link UserSecurity}
 * mapping (including {@code sec_usr_pwd VARCHAR(255)} and
 * {@code version BIGINT NOT NULL}) is therefore validated against the actual
 * shipped DDL. Each test executes in its own transaction that is rolled back
 * afterwards, and {@code user_security} is not seeded under the slice, so the
 * table starts empty for every test.
 *
 * <p><strong>Sensitive credential.</strong> The password is sensitive and must
 * never be logged or otherwise exposed (AAP&nbsp;&sect;0.7.3&nbsp;L1,
 * &sect;0.9.3). The {@code toString} test therefore asserts the <em>absence</em>
 * of a sentinel password value rather than printing it, and no test writes the
 * password to any stream.
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class UserSecurityRepositoryTest {

    /**
     * A single PostgreSQL&nbsp;16 container shared by every test in this class.
     *
     * <p>Declared {@code static} so it is started once per class (not once per
     * test) for speed. {@link ServiceConnection} publishes its JDBC URL,
     * username, and password to the Spring {@code Environment} automatically,
     * so no connection details are ever hardcoded (AAP&nbsp;&sect;0.9.3).
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Repository under test. */
    private final UserSecurityRepository repository;

    /**
     * Wrapper around the shared persistence context, used to flush pending
     * writes and evict managed entities so that a subsequent finder issues a
     * real {@code SELECT} against PostgreSQL rather than returning the
     * first-level cache instance.
     */
    private final TestEntityManager entityManager;

    /**
     * Constructor injection of the collaborators managed by the
     * {@link DataJpaTest} slice.
     *
     * <p>No {@code @Autowired} annotation is required: the suite enables
     * {@code spring.test.constructor.autowire.mode=all} (in
     * {@code src/test/resources/junit-platform.properties}), so the Spring
     * TestContext framework resolves every test-constructor parameter. This is
     * constructor injection, the preferred style; no field injection is used
     * (AAP&nbsp;&sect;0.4.2, &sect;0.6.4).
     *
     * @param repository    the {@link UserSecurityRepository} bean under test
     * @param entityManager the slice's {@link TestEntityManager}
     */
    UserSecurityRepositoryTest(UserSecurityRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /**
     * Persisting a user and reading it back by user id round-trips every scalar
     * field through PostgreSQL.
     *
     * <p>The persistence context is cleared between the write and the read so
     * that {@link UserSecurityRepository#findBySecUsrId(String)} genuinely
     * reloads the row from the database. This reproduces the keyed sign-on
     * {@code READ} of {@code COSGN00C}; the returned {@link Optional} is present
     * on a hit (the {@code NOTFND} / empty case is covered separately).
     */
    @Test
    void saveAndFindBySecUsrId_roundTrips() {
        UserSecurity admin = new UserSecurity(
                "ADMIN001",         // secUsrId (PIC X(08))
                "Ada",              // secUsrFname
                "Lovelace",         // secUsrLname
                "hashed-or-plain",  // secUsrPwd (stored credential)
                "A");               // secUsrType ('A' = administrator)

        repository.saveAndFlush(admin);
        entityManager.clear();

        Optional<UserSecurity> found = repository.findBySecUsrId("ADMIN001");
        assertThat(found).isPresent();
        UserSecurity reloaded = found.orElseThrow();
        assertThat(reloaded.getSecUsrId()).isEqualTo("ADMIN001");
        assertThat(reloaded.getSecUsrFname()).isEqualTo("Ada");
        assertThat(reloaded.getSecUsrLname()).isEqualTo("Lovelace");
        assertThat(reloaded.getSecUsrPwd()).isEqualTo("hashed-or-plain");
        assertThat(reloaded.getSecUsrType()).isEqualTo("A");
    }

    /**
     * Looking up a user id that does not exist yields an empty {@link Optional}
     * (the {@code user_security} table is empty under the slice), mirroring the
     * legacy CICS {@code NOTFND} / {@code FILE STATUS '23'} "record not found"
     * outcome at sign-on without throwing.
     */
    @Test
    void findBySecUsrId_absent_returnsEmptyOptional() {
        assertThat(repository.findBySecUsrId("NOSUCH01")).isEmpty();
    }

    /**
     * {@link UserSecurityRepository#findAllByOrderBySecUsrIdAsc()} returns users
     * strictly ordered by ascending {@code secUsrId}, regardless of insertion
     * order.
     *
     * <p>Rows are inserted with scrambled ids ({@code USER0003}, {@code USER0001},
     * {@code USER0002}) to prove the ordering is produced by the query, not by
     * insertion sequence. This reproduces the ascending key-ordered
     * {@code STARTBR}/{@code READNEXT} browse of the {@code COUSR00C} admin
     * user-list screen.
     */
    @Test
    void findAllByOrderBySecUsrIdAsc_returnsAscending() {
        repository.saveAll(List.of(
                minimalUser("USER0003", "U"),
                minimalUser("USER0001", "U"),
                minimalUser("USER0002", "U")));
        repository.flush();
        entityManager.clear();

        List<UserSecurity> ordered = repository.findAllByOrderBySecUsrIdAsc();

        assertThat(ordered)
                .hasSize(3)
                .extracting(UserSecurity::getSecUsrId)
                .containsExactly("USER0001", "USER0002", "USER0003");
    }

    /**
     * Both role types persist and reload unchanged, guarding the
     * {@code sec_usr_type} role model ({@code 'A'} = administrator,
     * {@code 'U'} = standard user) that drives the security layer's
     * {@code UserDetailsService}.
     */
    @Test
    void roleTypesAdminAndUserPersist() {
        repository.saveAndFlush(minimalUser("ADMROLE1", "A"));
        repository.saveAndFlush(minimalUser("USRROLE1", "U"));
        entityManager.clear();

        assertThat(repository.findBySecUsrId("ADMROLE1").orElseThrow().getSecUsrType())
                .isEqualTo("A");
        assertThat(repository.findBySecUsrId("USRROLE1").orElseThrow().getSecUsrType())
                .isEqualTo("U");
    }

    /**
     * The JPA {@code @Version} column is assigned on insert and incremented on
     * update, providing the optimistic-lock integrity that reproduces the COBOL
     * READ&#45;UPDATE&#45;REWRITE cycle of the online user-update screen
     * ({@code COUSR02C}).
     *
     * <p>A flush plus {@code clear} is required after each write so the version
     * increment is materialized and the subsequent reload is served from the
     * database rather than the persistence-context cache. A non-key field
     * ({@code secUsrFname}) is mutated to trigger the update.
     */
    @Test
    void versionAssignedOnInsertAndIncrementsOnUpdate() {
        repository.saveAndFlush(minimalUser("VERUSR01", "U"));
        entityManager.clear();

        UserSecurity inserted = repository.findBySecUsrId("VERUSR01").orElseThrow();
        assertThat(inserted.getVersion()).isNotNull();
        long initialVersion = inserted.getVersion();

        inserted.setSecUsrFname("Changed");
        repository.saveAndFlush(inserted);
        entityManager.clear();

        UserSecurity updated = repository.findBySecUsrId("VERUSR01").orElseThrow();
        assertThat(updated.getVersion()).isNotNull();
        long updatedVersion = updated.getVersion();

        assertThat(updatedVersion).isGreaterThan(initialVersion);
    }

    /**
     * {@link UserSecurity#toString()} must never expose the sensitive password
     * (AAP&nbsp;&sect;0.7.3&nbsp;L1, &sect;0.9.3).
     *
     * <p>A distinctive sentinel value is assigned to {@code secUsrPwd} and the
     * rendered string is asserted to contain none of it. No persistence is
     * required and the password is never written to any stream.
     */
    @Test
    void toStringExcludesPassword() {
        UserSecurity user = new UserSecurity(
                "PWDUSR01",         // secUsrId
                "Pat",              // secUsrFname
                "Example",          // secUsrLname
                "SUPERSECRETPWD",   // secUsrPwd (sentinel; must not appear in toString)
                "U");               // secUsrType

        assertThat(user.toString()).doesNotContain("SUPERSECRETPWD");
    }

    /**
     * Builds a schema-valid transient {@link UserSecurity} with readable,
     * non-sensitive names and a placeholder credential. Only {@code sec_usr_id}
     * is {@code NOT NULL}, but every business field is populated for realism;
     * the optimistic-lock {@code version} is intentionally left {@code null} (the
     * convenience constructor does not set it) so the JPA provider treats the
     * first {@code save} as an insert.
     *
     * @param secUsrId   the primary-key user id (up to 8 characters)
     * @param secUsrType the role indicator: {@code 'A'} admin or {@code 'U'} user
     * @return a new, unpersisted {@link UserSecurity}
     */
    private static UserSecurity minimalUser(String secUsrId, String secUsrType) {
        return new UserSecurity(
                secUsrId,               // secUsrId
                "First" + secUsrId,     // secUsrFname
                "Last" + secUsrId,      // secUsrLname
                "pw-" + secUsrId,       // secUsrPwd (placeholder credential)
                secUsrType);            // secUsrType
    }
}
