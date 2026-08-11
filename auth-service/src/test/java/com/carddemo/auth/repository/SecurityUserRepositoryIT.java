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
 *     ``test`` profile; Flyway applies the shared committed migration set - the
 *     ``security_users`` table comes from ``V1__create_schema.sql``, its ten seeded rows from
 *     ``V3__seed_test_data.sql``, and the optimistic-lock
 *     column from ``V6__security_users_optimistic_lock.sql`` - against the container database, and
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
class SecurityUserRepositoryIT extends AbstractIntegrationTest {

    /**
     * :purpose: Algorithm-identifier prefix that ``DelegatingPasswordEncoder``
     *     requires on every stored hash; without it ``matches`` throws and no user
     *     can sign on.
     */
    private static final String BCRYPT_PREFIX = "{bcrypt}";

    /**
     * :purpose: Stored credential every seeded row carries after the migration set is
     *     applied. ``V3__seed_test_data.sql`` inserts a BCrypt hash of the legacy
     *     fixture string 'PASSWORD' and ``V10__lock_seeded_credentials.sql``
     *     immediately replaces it with this sentinel, because a known, shared,
     *     documented administrator credential must not be active on a deployment.
     *     It is deliberately NOT a syntactically valid BCrypt hash.
     */
    private static final String EXPECTED_STORED_PASSWORD =
            BCRYPT_PREFIX + "$2a$10$locked-seeded-credential-not-provisioned!";

    /** :purpose: The legacy fixture password the sentinel retired. */
    private static final String RETIRED_DEFAULT_PASSWORD = "PASSWORD";

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
     * :purpose: Verify the seeded credential is the LOCKED sentinel that no input can
     *     match, and that the WIRED delegating encoder refuses it rather than throwing
     *     - a locked account must answer "wrong password", not fail the request with a
     *     500. The encoding POLICY (``{bcrypt}`` prefix, declared cost factor,
     *     case-sensitivity) is asserted against a freshly encoded credential, which is
     *     what the application actually writes.
     * :note: This is the assertion that keeps the shared-default-credential finding
     *     closed: if V10 is dropped or a future seed re-introduces a usable shared
     *     hash, the first two assertions fail.
     */
    @Test
    @DisplayName("Seeded credential is the locked sentinel; the wired encoder refuses every input")
    void seededCredentialIsLockedAndUnmatchable() {
        SecurityUser admin = securityUserRepository.findBySecUsrId("ADMIN001").orElseThrow();
        String storedCredential = admin.getSecUsrPwd();

        assertThat(storedCredential).isEqualTo(EXPECTED_STORED_PASSWORD);
        assertThat(passwordEncoder.matches(RETIRED_DEFAULT_PASSWORD, storedCredential)).isFalse();
        assertThat(passwordEncoder.matches("", storedCredential)).isFalse();
        assertThat(passwordEncoder.matches("locked", storedCredential)).isFalse();

        // Every seeded row is locked, not just the first administrator.
        assertThat(securityUserRepository.findAll())
                .allSatisfy(u -> assertThat(u.getSecUsrPwd()).isEqualTo(EXPECTED_STORED_PASSWORD))
                .allSatisfy(u -> assertThat(
                        passwordEncoder.matches(RETIRED_DEFAULT_PASSWORD, u.getSecUsrPwd())).isFalse());

        // The encoding policy, asserted on what the application writes. The prefix is
        // what makes sign-on possible at all: DelegatingPasswordEncoder throws
        // IllegalArgumentException for an unprefixed hash.
        String encoded = passwordEncoder.encode(RETIRED_DEFAULT_PASSWORD);
        assertThat(encoded).startsWith(BCRYPT_PREFIX + "$2a$");
        assertThat(encoded).hasSize(BCRYPT_PREFIX.length() + 60);
        assertThat(encoded.substring(BCRYPT_PREFIX.length() + 4, BCRYPT_PREFIX.length() + 6))
                .isEqualTo(String.format("%02d", PasswordEncoderFactory.BCRYPT_STRENGTH));
        assertThat(passwordEncoder.matches(RETIRED_DEFAULT_PASSWORD, encoded)).isTrue();
        assertThat(passwordEncoder.matches("password", encoded)).isFalse();
        assertThat(passwordEncoder.upgradeEncoding(encoded)).isFalse();

        // The bare BCrypt encoder behaves the same way on the same value, so the
        // delegating wrapper is not what makes the comparison case-sensitive.
        assertThat(bcryptPasswordEncoder.matches(
                RETIRED_DEFAULT_PASSWORD, encoded.substring(BCRYPT_PREFIX.length()))).isTrue();
        assertThat(bcryptPasswordEncoder.matches(
                "password", encoded.substring(BCRYPT_PREFIX.length()))).isFalse();
    }
}
