package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.UserSecurity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
 * <p><strong>Read-only:</strong> the base {@link AbstractPostgresIntegrationTest} declares no
 * {@code @Transactional} boundary and the {@code user_security} table is shared seed data, so these
 * tests exercise only non-mutating operations ({@code count()}, {@code findByUsrId(String)},
 * {@code findAll()}) and never mutate the shared seed rows.</p>
 *
 * <p><strong>Cleartext password parity (AAP &sect;0.6.7 &mdash; intentional):</strong> every seeded
 * user has the literal cleartext password {@code "PASSWORD"}, preserving the legacy COBOL
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
     * seeded first/last name, admin role type {@code "A"}, and cleartext password {@code "PASSWORD"}.
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
        assertThat(user.getUsrPwd().trim()).isEqualTo("PASSWORD");
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
     * seeded cleartext password {@code "PASSWORD"} (cleartext parity, AAP &sect;0.6.7).
     */
    @Test
    @DisplayName("user store has exactly 5 admin and 5 standard users, all with the seeded password")
    void roleDistributionAndPasswordParity() {
        List<UserSecurity> all = userSecurityRepository.findAll();
        assertThat(all).hasSize(10);
        assertThat(all.stream().filter(u -> "A".equals(u.getUsrType().trim())).count()).isEqualTo(5L);
        assertThat(all.stream().filter(u -> "U".equals(u.getUsrType().trim())).count()).isEqualTo(5L);
        assertThat(all).allSatisfy(u -> assertThat(u.getUsrPwd().trim()).isEqualTo("PASSWORD"));
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
}
