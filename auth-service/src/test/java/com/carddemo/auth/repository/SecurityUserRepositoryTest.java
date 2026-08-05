/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.auth.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.carddemo.auth.AbstractIntegrationTest;
import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.security.PasswordEncoderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Testcontainers integration test verifying the VSAM ``USRSEC`` ->
 *     PostgreSQL ``security_users`` migration. Extends
 *     {@link AbstractIntegrationTest} to boot the full auth-service context
 *     against shared ``postgres:18`` + ``redis:8`` containers under the
 *     ``test`` profile; Flyway applies ``V1__create_security_users_table.sql``
 *     then ``V2__seed_security_users.sql`` against the container database, and
 *     the {@link SecurityUser} mapping is exercised through the repository under the
 *     ``test`` profile's ``ddl-auto: validate`` - the mapping of every entity-scanned
 *     table is therefore asserted against the migrated schema, never neutralised. The
 *     test asserts the ``findBySecUsrId`` keyed lookup, the ten-user seed (five
 *     admin plus five user) with frozen names, and the prefixed-BCrypt password
 *     contract — the ``{bcrypt}`` algorithm identifier that
 *     ``DelegatingPasswordEncoder`` requires, the cost factor declared by
 *     ``PasswordEncoderFactory.BCRYPT_STRENGTH``, and the documented
 *     case-sensitivity behavior.
 */
class SecurityUserRepositoryTest extends AbstractIntegrationTest {

    /**
     * :purpose: Algorithm-identifier prefix that ``DelegatingPasswordEncoder``
     *     requires on every stored hash; without it ``matches`` throws and no user
     *     can sign on.
     */
    private static final String BCRYPT_PREFIX = "{bcrypt}";

    /** :purpose: Bare 60-char BCrypt hash of ``PASSWORD`` seeded for all users. */
    private static final String EXPECTED_BCRYPT_HASH =
            "$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm";

    /** :purpose: Stored credential exactly as the seed migration writes it. */
    private static final String EXPECTED_STORED_PASSWORD = BCRYPT_PREFIX + EXPECTED_BCRYPT_HASH;

    /**
     * :purpose: Repository under test; the Spring Data JPA re-platforming of the
     *     legacy keyed ``USRSEC`` VSAM read.
     */
    @Autowired
    private SecurityUserRepository securityUserRepository;

    /**
     * :purpose: Application-wide password encoder bean wired by the booted
     *     context (the shared delegating ``{bcrypt}`` policy).
     */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * :purpose: Plain BCrypt encoder used to verify the seeded hash with its
     *     ``{bcrypt}`` identifier stripped, and its case-sensitive matching.
     */
    private final BCryptPasswordEncoder bcryptPasswordEncoder = new BCryptPasswordEncoder();

    /**
     * :purpose: Verify the keyed lookup returns the seeded admin and user rows
     *     with their frozen names and types, and yields an empty result for an
     *     unknown id (the legacy user-not-found path).
     */
    @Test
    @DisplayName("findBySecUsrId returns seeded admin/user rows and empty for an unknown id")
    void findBySecUsrIdReturnsSeededRowsAndEmptyForUnknown() {
        Optional<SecurityUser> admin = securityUserRepository.findBySecUsrId("ADMIN001");
        assertThat(admin).isPresent();
        assertThat(admin.get().getSecUsrFname()).isEqualTo("MARGARET");
        assertThat(admin.get().getSecUsrLname()).isEqualTo("GOLD");
        assertThat(admin.get().getSecUsrType()).isEqualTo("A");

        Optional<SecurityUser> user = securityUserRepository.findBySecUsrId("USER0001");
        assertThat(user).isPresent();
        assertThat(user.get().getSecUsrFname()).isEqualTo("LAWRENCE");
        assertThat(user.get().getSecUsrLname()).isEqualTo("THOMAS");
        assertThat(user.get().getSecUsrType()).isEqualTo("U");

        assertThat(securityUserRepository.findBySecUsrId("NOPE9999")).isEmpty();
    }

    /**
     * :purpose: Verify the seed migration loads exactly ten users split five
     *     admin and five user, and that every row matches the frozen id, first
     *     name, last name, and type contract.
     */
    @Test
    @DisplayName("Seed migration loads exactly 10 users (5 admin + 5 user) with frozen names")
    void seedLoadsExactlyTenUsers() {
        assertThat(securityUserRepository.count()).isEqualTo(10L);

        List<SecurityUser> all = securityUserRepository.findAll();
        long admins = all.stream().filter(u -> "A".equals(u.getSecUsrType())).count();
        long users = all.stream().filter(u -> "U".equals(u.getSecUsrType())).count();
        assertThat(admins).isEqualTo(5L);
        assertThat(users).isEqualTo(5L);

        Map<String, String[]> expected = Map.ofEntries(
                Map.entry("ADMIN001", new String[] {"MARGARET", "GOLD", "A"}),
                Map.entry("ADMIN002", new String[] {"RUSSELL", "RUSSELL", "A"}),
                Map.entry("ADMIN003", new String[] {"RAYMOND", "WHITMORE", "A"}),
                Map.entry("ADMIN004", new String[] {"EMMANUEL", "CASGRAIN", "A"}),
                Map.entry("ADMIN005", new String[] {"GRANVILLE", "LACHAPELLE", "A"}),
                Map.entry("USER0001", new String[] {"LAWRENCE", "THOMAS", "U"}),
                Map.entry("USER0002", new String[] {"AJITH", "KUMAR", "U"}),
                Map.entry("USER0003", new String[] {"LAURITZ", "ALME", "U"}),
                Map.entry("USER0004", new String[] {"AVERARDO", "MAZZI", "U"}),
                Map.entry("USER0005", new String[] {"LEE", "TING", "U"}));

        expected.forEach((id, exp) -> {
            Optional<SecurityUser> found = securityUserRepository.findBySecUsrId(id);
            assertThat(found).as("seeded user %s must be present", id).isPresent();
            assertThat(found.get().getSecUsrFname()).isEqualTo(exp[0]);
            assertThat(found.get().getSecUsrLname()).isEqualTo(exp[1]);
            assertThat(found.get().getSecUsrType()).isEqualTo(exp[2]);
        });
    }

    /**
     * :purpose: Verify the seeded credential is a ``{bcrypt}``-prefixed BCrypt hash
     *     that the WIRED delegating encoder can verify against ``PASSWORD`` and
     *     rejects for ``password``, that the hash carries the cost factor declared
     *     by {@link PasswordEncoderFactory#BCRYPT_STRENGTH}, and that every one of
     *     the ten seeded rows stores the same credential. The prefix is what makes
     *     sign-on possible at all: ``DelegatingPasswordEncoder`` throws
     *     ``IllegalArgumentException`` for an unprefixed hash.
     */
    @Test
    @DisplayName("Seeded password is a {bcrypt}-prefixed BCrypt hash verifiable by the wired encoder")
    void seededPasswordIsPrefixedBcryptAndCaseSensitive() {
        SecurityUser admin = securityUserRepository.findBySecUsrId("ADMIN001").orElseThrow();
        String storedCredential = admin.getSecUsrPwd();

        assertThat(storedCredential).isEqualTo(EXPECTED_STORED_PASSWORD);
        assertThat(storedCredential).startsWith(BCRYPT_PREFIX + "$2a$");
        assertThat(storedCredential).hasSize(BCRYPT_PREFIX.length() + 60);

        // The cost factor must match the encoding policy, otherwise a rehash is
        // silently triggered on every successful sign-on.
        assertThat(storedCredential.substring(BCRYPT_PREFIX.length() + 4, BCRYPT_PREFIX.length() + 6))
                .isEqualTo(String.format("%02d", PasswordEncoderFactory.BCRYPT_STRENGTH));

        // The WIRED encoder (the one the service actually injects) must verify the
        // stored credential; this is the assertion that catches a prefix mismatch.
        assertThat(passwordEncoder.matches("PASSWORD", storedCredential)).isTrue();
        assertThat(passwordEncoder.matches("password", storedCredential)).isFalse();
        assertThat(passwordEncoder.upgradeEncoding(storedCredential)).isFalse();

        assertThat(storedCredential).startsWith("{bcrypt}$2a$");
        assertThat(storedCredential).hasSize("{bcrypt}".length() + 60);

        // The encoder the service is wired with must verify the stored credential directly.
        assertThat(passwordEncoder.matches("PASSWORD", storedCredential)).isTrue();
        assertThat(passwordEncoder.matches("password", storedCredential)).isFalse();

        // The hash itself is an unchanged BCrypt hash of PASSWORD.
        assertThat(bcryptPasswordEncoder.matches("PASSWORD", EXPECTED_BCRYPT_HASH)).isTrue();
        assertThat(bcryptPasswordEncoder.matches("password", EXPECTED_BCRYPT_HASH)).isFalse();

        String encoded = passwordEncoder.encode("PASSWORD");
        assertThat(encoded).startsWith(BCRYPT_PREFIX);
        assertThat(encoded).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches("PASSWORD", encoded)).isTrue();
        assertThat(passwordEncoder.matches("password", encoded)).isFalse();

        assertThat(securityUserRepository.findAll())
                .allSatisfy(u -> assertThat(u.getSecUsrPwd()).isEqualTo(EXPECTED_STORED_PASSWORD))
                .allSatisfy(u -> assertThat(passwordEncoder.matches("PASSWORD", u.getSecUsrPwd())).isTrue());
    }
}
