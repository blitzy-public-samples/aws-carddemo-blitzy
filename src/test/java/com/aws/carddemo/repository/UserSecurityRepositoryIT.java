package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.TestCredentials;
import com.aws.carddemo.domain.UserSecurity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Integration tests for {@link UserSecurityRepository} against the Flyway-seeded Testcontainers
 * PostgreSQL, asserting the exact seeded row count and that {@code findByUsrId} (the authentication
 * lookup consumed by {@code com.aws.carddemo.security.CardDemoUserDetailsService}, replacing the
 * COBOL {@code COSGN00C} {@code USRSEC} READ) returns the seeded user with the correct ADMIN/USER
 * role type, preserving VSAM {@code USRSEC} parity.
 *
 * <p><strong>Parity oracles (AAP &sect;0.6.10):</strong></p>
 * <ul>
 *   <li>{@code legacy/cpy/CSUSR01Y.cpy} &mdash; {@code SEC-USER-DATA}, RECLN 80
 *       ({@code SEC-USR-ID X(08)}, {@code SEC-USR-FNAME X(20)}, {@code SEC-USR-LNAME X(20)},
 *       {@code SEC-USR-PWD X(08)} cleartext, {@code SEC-USR-TYPE X(01)} = {@code A}/{@code U},
 *       {@code FILLER X(23)}).</li>
 *   <li>{@code legacy/jcl/DUSRSECJ.jcl} &mdash; the load job whose inline {@code SYSUT1} data is the
 *       source of the 10 seeded users; there is NO {@code legacy/data/ASCII} fixture for users. The
 *       rows are materialized into {@code user_security} by {@code V2__reference_data.sql}.</li>
 * </ul>
 *
 * <p><strong>Mostly read-only:</strong> the base {@link AbstractPostgresIntegrationTest} declares no
 * {@code @Transactional} boundary and the {@code user_security} table is shared seed data, so the
 * positive tests exercise only non-mutating operations ({@code count()}, {@code findByUsrId(String)},
 * {@code findAll()}) and never mutate the shared seed rows. The single exception is the review
 * finding #32 parity test {@code duplicateUsrIdInsertsNotMergesReproducingDuprec}, whose attempted
 * {@code save} of an existing key is <em>rejected</em> with a {@code DataIntegrityViolationException}
 * (a true {@code INSERT}, not a merge/upsert) and therefore commits no change; regardless, the base
 * class's per-test Flyway {@code clean()+migrate()} restores the pristine seed before every method,
 * so no test observes another's writes.</p>
 *
 * <p><strong>Cleartext password parity (AAP &sect;0.6.7 &mdash; intentional):</strong> every seeded
 * user has the externalized cleartext seed password (CARDDEMO_SEED_PASSWORD, no committed default;
 * review finding #5), preserving the legacy COBOL
 * cleartext-comparison behavior. The value is asserted directly via {@link UserSecurity#getUsrPwd()}
 * (the entity {@link UserSecurity#toString() toString()} deliberately masks it, so it must never be
 * asserted through {@code toString()}) and is never written to logs.</p>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see AbstractPostgresIntegrationTest
 */
class UserSecurityRepositoryIT extends AbstractPostgresIntegrationTest {

    /**
     * The repository under test, injected from the Spring context started by the base class.
     */
    @Autowired
    private UserSecurityRepository userSecurityRepository;

    // Review finding #5: the seeded password is externalized (CARDDEMO_SEED_PASSWORD env var, no
    // committed default) and read here to assert against the real Flyway-seeded rows. The cleartext
    // COMPARISON behavior (AAP 0.6.7) is unchanged; only the committed literal is removed.
    private static final String SEEDED_PASSWORD = TestCredentials.seedPassword();

    /**
     * Verifies the Flyway-seeded {@code user_security} table holds exactly the 10 rows loaded from the
     * {@code DUSRSECJ.jcl} inline data (5 administrators plus 5 regular users).
     */
    @Test
    @DisplayName("user_security seeds exactly 10 rows (DUSRSECJ.jcl parity)")
    void seededRowCountMatchesFixture() {
        assertThat(userSecurityRepository.count()).isEqualTo(10L);
    }

    /**
     * Verifies the authentication lookup returns the administrator {@code ADMIN001} with the exact
     * seeded first/last name, admin role type {@code "A"}, and the externalized cleartext seed password.
     */
    @Test
    @DisplayName("findByUsrId(ADMIN001) returns MARGARET GOLD with admin role 'A'")
    void findByUsrIdReturnsAdminUser() {
        Optional<UserSecurity> found = userSecurityRepository.findByUsrId("ADMIN001");
        assertThat(found).isPresent();
        UserSecurity user = found.get();
        assertThat(user.getUsrType().trim()).isEqualTo("A");
        assertThat(user.getUsrFname().trim()).isEqualTo("MARGARET");
        assertThat(user.getUsrLname().trim()).isEqualTo("GOLD");
        assertThat(user.getUsrPwd().trim()).isEqualTo(SEEDED_PASSWORD);
    }

    /**
     * Verifies the authentication lookup returns the regular user {@code USER0001} with the exact
     * seeded first/last name and user role type {@code "U"}.
     */
    @Test
    @DisplayName("findByUsrId(USER0001) returns LAWRENCE THOMAS with user role 'U'")
    void findByUsrIdReturnsStandardUser() {
        Optional<UserSecurity> found = userSecurityRepository.findByUsrId("USER0001");
        assertThat(found).isPresent();
        UserSecurity user = found.get();
        assertThat(user.getUsrType().trim()).isEqualTo("U");
        assertThat(user.getUsrFname().trim()).isEqualTo("LAWRENCE");
        assertThat(user.getUsrLname().trim()).isEqualTo("THOMAS");
    }

    /**
     * Verifies the seeded user store partitions into exactly 5 administrators ({@code usrType} =
     * {@code "A"}) and 5 regular users ({@code usrType} = {@code "U"}), and that every row carries the
     * externalized cleartext seed password (cleartext parity, AAP &sect;0.6.7; review finding #5).
     */
    @Test
    @DisplayName("user store has exactly 5 admin and 5 standard users, all with the seeded password")
    void roleDistributionAndPasswordParity() {
        List<UserSecurity> all = userSecurityRepository.findAll();
        assertThat(all).hasSize(10);
        assertThat(all.stream().filter(u -> "A".equals(u.getUsrType().trim())).count()).isEqualTo(5L);
        assertThat(all.stream().filter(u -> "U".equals(u.getUsrType().trim())).count()).isEqualTo(5L);
        assertThat(all).allSatisfy(u -> assertThat(u.getUsrPwd().trim()).isEqualTo(SEEDED_PASSWORD));
    }

    /**
     * Verifies the authentication lookup for a user id absent from the seeded store returns an empty
     * {@link Optional}, mirroring the legacy signon "user not found" path.
     */
    @Test
    @DisplayName("findByUsrId for an unknown user id returns empty Optional")
    void findByUsrIdMissingReturnsEmpty() {
        assertThat(userSecurityRepository.findByUsrId("NOSUCH99")).isEmpty();
    }

    /**
     * Review finding #32 (insert semantics parity). Proves that a {@code save} of a
     * <em>brand-new</em> {@link UserSecurity} object carrying an <em>already-seeded</em>
     * {@code SEC-USR-ID} ({@code ADMIN001}) is executed as a true SQL {@code INSERT} that collides
     * with the existing primary key &mdash; the CICS {@code WRITE DUPKEY} / VSAM
     * {@code FILE STATUS "22"} (DUPREC) equivalent that the {@code COUSR01C} add path relies on
     * &mdash; rather than as a silent {@code UPDATE}.
     *
     * <p><strong>Why this proves the fix.</strong> {@link UserSecurity} now implements
     * {@code org.springframework.data.domain.Persistable} and reports {@code isNew() == true} on
     * fresh construction, so Spring Data's {@code save} routes to {@code EntityManager.persist} (an
     * unconditional {@code INSERT}). Before the fix, the assigned {@code @Id} with no {@code isNew}
     * override made {@code save} route to {@code EntityManager.merge} (a {@code SELECT}-then-
     * {@code UPDATE} upsert), so adding a user whose id already existed silently overwrote the
     * incumbent record and the {@code DuplicateKeyException} guard in
     * {@code UserAddService.writeUserSecFile} was dead code. This test fails on the pre-fix merge
     * behaviour (no exception, {@code ADMIN001} overwritten) and passes only with the {@code persist}
     * (insert) semantics that restore DUPREC parity.
     *
     * <p>The attempted write is rejected and rolled back in Spring Data's own transaction, so the
     * seeded {@code ADMIN001} (MARGARET GOLD) is asserted unchanged afterwards &mdash; the failed
     * {@code INSERT} never mutated the incumbent row.
     */
    @Test
    @DisplayName("#32 duplicate USR-ID INSERTs (Persistable), not merges: add of an existing id raises DUPREC and does not overwrite")
    void duplicateUsrIdInsertsNotMergesReproducingDuprec() {
        // A NEW UserSecurity with the SAME primary key as the seeded admin, but hostile field values.
        UserSecurity forged = new UserSecurity();
        forged.setUsrId("ADMIN001");
        forged.setUsrFname("MALLORY");
        forged.setUsrLname("HACKER");
        forged.setUsrPwd("BADPWD01");
        forged.setUsrType("A");

        // isNew()==true -> persist -> INSERT -> unique-key violation (DUPREC), never a silent merge.
        assertThatThrownBy(() -> userSecurityRepository.saveAndFlush(forged))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Parity: the seeded ADMIN001 is untouched by the rejected INSERT.
        UserSecurity survivor = userSecurityRepository.findByUsrId("ADMIN001").orElseThrow();
        assertThat(survivor.getUsrFname().trim()).isEqualTo("MARGARET");
        assertThat(survivor.getUsrLname().trim()).isEqualTo("GOLD");
        assertThat(survivor.getUsrPwd().trim()).isEqualTo(SEEDED_PASSWORD);
    }
}
