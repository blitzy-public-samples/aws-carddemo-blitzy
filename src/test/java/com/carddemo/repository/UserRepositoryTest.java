package com.carddemo.repository;

import com.carddemo.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link UserRepository}.
 *
 * <p>Parity: replaces VSAM {@code USRSEC} keyed reads (copybook {@code app/cpy/CSUSR01Y.cpy};
 * key length 8 per {@code app/catlg/LISTCAT.txt}) used by {@code COSGN00C} signon. Because
 * {@code usrsec.txt} is absent, the two default users are generated with BCrypt-hashed
 * passwords by {@code V4__seed_users.sql} (AAP 0.6.7). Confirms user-type routing data and
 * that credentials are stored as BCrypt (never plaintext).</p>
 *
 * <h2>Test slice configuration</h2>
 * <ul>
 *   <li>{@link DataJpaTest @DataJpaTest} bootstraps only the JPA/repository slice and wraps each
 *       test in a transaction that is rolled back, so the seeded {@code users} rows are never
 *       mutated across tests.</li>
 *   <li>{@link AutoConfigureTestDatabase @AutoConfigureTestDatabase(replace = NONE)} keeps the
 *       profile-configured H2 datasource (PostgreSQL-compat mode) rather than substituting an
 *       embedded one, so Flyway migrations {@code V1__schema.sql}..{@code V4__seed_users.sql}
 *       run and the BCrypt user seed is present.</li>
 *   <li>{@link ActiveProfiles @ActiveProfiles("test")} selects {@code application-test.yml}.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    /**
     * The administrator seed row ({@code ADMIN001}) is present and carries user type
     * {@code "A"}, the discriminator that drives {@code ADMIN} authority in the migrated
     * sign-on routing.
     */
    @Test
    void findById_admin_hasAdminType() {
        Optional<User> result = userRepository.findById("ADMIN001");
        assertThat(result).isPresent();
        assertThat(result.get().getUserType()).isEqualTo("A");
    }

    /**
     * The regular-user seed row ({@code USER0001}) is present and carries user type
     * {@code "U"}, the discriminator that drives {@code USER} authority.
     */
    @Test
    void findById_standardUser_hasUserType() {
        Optional<User> result = userRepository.findById("USER0001");
        assertThat(result).isPresent();
        assertThat(result.get().getUserType()).isEqualTo("U");
    }

    /**
     * The seeded credential is a BCrypt hash, never the plaintext {@code PASSWORD}. The exact
     * digest is salted and therefore non-deterministic, so this asserts only the invariants of
     * a BCrypt hash: the {@code $2} prefix (variant {@code $2a$}/{@code $2b$}/{@code $2y$}) and
     * the fixed 60-character length.
     */
    @Test
    void seededPassword_isBCryptHash_notPlaintext() {
        User admin = userRepository.findById("ADMIN001").orElseThrow();
        assertThat(admin.getPassword())
                .as("password must be stored as a BCrypt hash, never plaintext")
                .isNotEqualTo("PASSWORD")
                .startsWith("$2")
                .hasSize(60);
    }

    /**
     * A lookup for a user id that is not seeded returns an empty {@link Optional}, mirroring the
     * legacy {@code USRSEC} "record not found" path of {@code COSGN00C}.
     */
    @Test
    void findById_unknownUser_isEmpty() {
        assertThat(userRepository.findById("NOSUCH99")).isEmpty();
    }

    /**
     * The generated seed populates exactly two users; there is no {@code usrsec.txt} fixture.
     */
    @Test
    void count_matchesSeedRowCount() {
        // V4__seed_users.sql seeds exactly 2 users (ADMIN001, USER0001)
        assertThat(userRepository.count()).isEqualTo(2L);
    }
}
