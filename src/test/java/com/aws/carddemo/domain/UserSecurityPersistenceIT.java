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

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.TestCredentials;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence integration test for the JPA entity {@link UserSecurity} against a real,
 * Flyway-migrated PostgreSQL database provisioned by Testcontainers.
 *
 * <p><strong>What this test locks down.</strong> Unlike the sibling {@code UserSecurityTest} (a pure,
 * in-process reflection/unit test running under Surefire), this class exercises the entity end-to-end
 * through Hibernate against a genuine PostgreSQL engine. It verifies three contracts that only a live
 * database can prove:</p>
 * <ol>
 *   <li>the entity mapping matches the {@code user_security} table declared in
 *       {@code src/main/resources/db/migration/V1__schema.sql} (Hibernate runs with
 *       {@code ddl-auto=validate}, so a context that starts at all already proves the mapping is
 *       schema-valid);</li>
 *   <li>the reference/seed rows loaded by {@code V2__reference_data.sql} are readable with the
 *       expected values, including the {@code A}/{@code U} role discriminator (AAP &sect;0.6.7); and</li>
 *   <li>a brand-new row round-trips through a real {@code INSERT} plus reload, and the cleartext
 *       password never leaks through {@link UserSecurity#toString()}.</li>
 * </ol>
 *
 * <p><strong>COBOL oracle (traceability, AAP &sect;0.6.10).</strong> The entity under test is migrated
 * one-for-one from the copybook {@code legacy/cpy/CSUSR01Y.cpy} ({@code 01 SEC-USER-DATA}, fixed
 * record length {@code RECLN 80}), which described the layout of the legacy VSAM KSDS {@code USRSEC}.
 * The columns asserted here correspond to that copybook's {@code 05}-level fields
 * ({@code SEC-USR-ID X(08)}, {@code SEC-USR-FNAME X(20)}, {@code SEC-USR-LNAME X(20)},
 * {@code SEC-USR-PWD X(08)}, {@code SEC-USR-TYPE X(01)}); the trailing {@code SEC-USR-FILLER X(23)}
 * padding is intentionally not persisted.</p>
 *
 * <p><strong>Role discriminator (AAP &sect;0.6.7).</strong> {@code usr_type} is the single byte that
 * drives Spring Security authority mapping: {@code "A"} becomes {@code ROLE_ADMIN} and {@code "U"}
 * becomes {@code ROLE_USER}, reproducing the legacy signon program's {@code XCTL} to the admin
 * ({@code COADM01C}) or main ({@code COMEN01C}) menu. This test spot-checks one seeded row of each
 * type ({@code ADMIN001} = admin, {@code USER0001} = user).</p>
 *
 * <p><strong>Cleartext-password parity and masking (AAP &sect;0.6.7 &mdash; intentional).</strong> The
 * legacy {@code USRSEC} store held the password as cleartext and the COBOL signon program compared it
 * directly, so the value is preserved verbatim in the database for 100% functional parity (password
 * hashing is deliberately deferred and recorded as a suggested next task in
 * {@code docs/decision-log.md}). As a security-hygiene safeguard, that cleartext value must never
 * appear in a diagnostic string; {@link #toStringMasksPasswordOnManagedEntity()} verifies the masking
 * holds even for an entity freshly materialised from the database.</p>
 *
 * <p><strong>Fixed-width text semantics.</strong> Every text column is {@code CHAR(n)}, so PostgreSQL
 * returns values right-padded with spaces to the declared width. Assertions therefore compare against
 * {@link String#strip()} of the fetched value. The primary-key and password literals used here are
 * exactly eight characters wide, matching the {@code CHAR(8)} columns so that key lookups and password
 * comparisons carry no trailing-space ambiguity.</p>
 *
 * <p><strong>Infrastructure.</strong> This class extends {@link AbstractPostgresIntegrationTest}, which
 * starts the shared {@code postgres:18-alpine} container, activates the {@code test} profile, and lets
 * Flyway apply {@code V0 -> V1 -> V2 -> V3} into that container. The class-level
 * {@link Transactional @Transactional} wraps each test method in a transaction that is rolled back on
 * completion, so the {@link #persistAndFindNewUser()} insert never pollutes the shared container and
 * the seeded rows remain intact for other tests. A Testcontainers-capable Docker daemon is required to
 * run this test; the same behavioural contracts are additionally covered in-process by
 * {@code UserSecurityTest} when Docker is unavailable.</p>
 *
 * @see UserSecurity
 * @see AbstractPostgresIntegrationTest
 */
@Transactional
class UserSecurityPersistenceIT extends AbstractPostgresIntegrationTest {

    /** Seeded administrator primary key (V2). {@code CHAR(8)} column, so exactly eight characters. */
    private static final String ADMIN_ID = "ADMIN001";

    /** Seeded standard-user primary key (V2). {@code CHAR(8)} column, so exactly eight characters. */
    private static final String STANDARD_ID = "USER0001";

    /** Cleartext password shared by every seeded row (V2). {@code CHAR(8)} column, exactly eight chars. */
    // Review finding #5: seed password externalized via CARDDEMO_SEED_PASSWORD (no committed default).
    private static final String SEED_PASSWORD = TestCredentials.seedPassword();

    /** Non-colliding primary key for the insert round-trip. Exactly eight chars for the {@code CHAR(8)} PK. */
    private static final String NEW_USER_ID = "ITUSER01";

    /** Cleartext password for the inserted row. Exactly eight chars for the {@code CHAR(8)} column. */
    private static final String NEW_USER_PWD = "SECRET99";

    /**
     * The JPA persistence context bound to the current (rolled-back) test transaction. Injected with
     * {@link PersistenceContext @PersistenceContext} rather than a repository so the test drives the
     * entity mapping directly through {@code find}/{@code persist}/{@code flush}/{@code clear}.
     */
    @PersistenceContext
    private EntityManager em;

    /**
     * The seeded administrator row ({@code ADMIN001}) loads with its expected identity and role. This
     * asserts the {@code A} discriminator that maps to {@code ROLE_ADMIN} (AAP &sect;0.6.7) and confirms
     * the cleartext password is stored verbatim for parity.
     */
    @Test
    void seededAdminUserLoads() {
        UserSecurity a = em.find(UserSecurity.class, ADMIN_ID);

        assertThat(a).isNotNull();
        assertThat(a.getUsrType().strip()).isEqualTo("A");
        assertThat(a.getUsrLname().strip()).isEqualTo("GOLD");
        assertThat(a.getUsrFname().strip()).isEqualTo("MARGARET");
        assertThat(a.getUsrPwd().strip()).isEqualTo(SEED_PASSWORD);
    }

    /**
     * The seeded standard-user row ({@code USER0001}) loads with the {@code U} discriminator that maps
     * to {@code ROLE_USER} (AAP &sect;0.6.7), confirming both role branches are represented in the seed.
     */
    @Test
    void seededStandardUserLoads() {
        UserSecurity u = em.find(UserSecurity.class, STANDARD_ID);

        assertThat(u).isNotNull();
        assertThat(u.getUsrType().strip()).isEqualTo("U");
        assertThat(u.getUsrLname().strip()).isEqualTo("THOMAS");
    }

    /**
     * A brand-new user row round-trips through a real {@code INSERT} and reload. After
     * {@code persist}/{@code flush}/{@code clear}, the first-level cache is emptied so {@code find}
     * reads the row back from the database within the same transaction, proving the write path and the
     * column mapping. The surrounding transaction is rolled back, so the shared container is left clean.
     */
    @Test
    void persistAndFindNewUser() {
        UserSecurity created = new UserSecurity();
        created.setUsrId(NEW_USER_ID);
        created.setUsrFname("Test");
        created.setUsrLname("User");
        created.setUsrPwd(NEW_USER_PWD);
        created.setUsrType("U");

        em.persist(created);
        em.flush();
        em.clear();

        UserSecurity found = em.find(UserSecurity.class, NEW_USER_ID);

        assertThat(found).isNotNull();
        assertThat(found.getUsrType().strip()).isEqualTo("U");
        assertThat(found.getUsrPwd().strip()).isEqualTo(NEW_USER_PWD);
    }

    /**
     * The cleartext password is masked in {@link UserSecurity#toString()} even for an entity freshly
     * materialised from the database. The test first proves the entity really does hold the cleartext
     * value (so the masking assertion is meaningful), then asserts that value never appears in the
     * diagnostic string (CWE-532, AAP &sect;0.6.7).
     */
    @Test
    void toStringMasksPasswordOnManagedEntity() {
        UserSecurity a = em.find(UserSecurity.class, ADMIN_ID);

        assertThat(a).isNotNull();
        assertThat(a.getUsrPwd().strip()).isEqualTo(SEED_PASSWORD);
        assertThat(a.toString()).doesNotContain(SEED_PASSWORD);
    }
}
