/*
 * CardDemo — AWS mainframe (COBOL/CICS/VSAM) to Java 25 + Spring Boot re-platform.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.aws.carddemo.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * End-to-end authentication integration test that drives the <em>real</em> Spring Security
 * filter chain configured by {@link com.aws.carddemo.config.SecurityConfig} against a booted
 * application context and a real PostgreSQL&nbsp;16 database (Testcontainers).
 *
 * <p><strong>What this locks down (QA MAJOR-2).</strong> Every {@code @WebMvcTest} controller
 * slice authenticates with {@code @WithMockUser}, which injects a ready-made
 * {@code Authentication} into the {@code SecurityContext} and therefore <em>bypasses</em> the
 * HTTP Basic filter, the {@code AuthenticationManager}, the {@code DaoAuthenticationProvider},
 * the {@link com.aws.carddemo.security.UpperCasePasswordEncoder}, and
 * {@link com.aws.carddemo.security.CardDemoUserDetailsService}. The password encoder is
 * unit-tested in isolation, but nothing exercised its <em>wiring</em> into the live filter
 * chain end to end. This test closes that gap: it sends real
 * {@code Authorization: Basic} credentials through {@code MockMvc} (with the Spring Security
 * filters applied) so the full path —
 * {@code BasicAuthenticationFilter} &rarr; {@code AuthenticationManager} &rarr;
 * {@code DaoAuthenticationProvider} &rarr; {@code UpperCasePasswordEncoder} &rarr;
 * {@code CardDemoUserDetailsService} &rarr; {@code user_security} table — is proven to
 * authenticate, reject, and authorize exactly as configured.</p>
 *
 * <h2>Credentials and the COBOL upper-case fold</h2>
 * <p>Both seed users store {@code passwordEncoder.encode("PASSWORD")}. The application encoder
 * folds the raw password to upper case before delegating to BCrypt, reproducing the COBOL
 * {@code COSGN00C} {@code FUNCTION UPPER-CASE} sign-on behavior. This test asserts that fold
 * parity holds through the live chain: {@code "PASSWORD"}, {@code "password"} and
 * {@code "PaSsWoRd"} all authenticate, while a genuinely different password is rejected.</p>
 *
 * <h2>Authorization parity (role {@code A}=Admin / {@code U}=User)</h2>
 * <p>{@code ADMIN001} is seeded with type {@code 'A'} (mapped to {@code ROLE_ADMIN}) and
 * {@code USER0001} with type {@code 'U'} ({@code ROLE_USER}). The admin-only surface
 * {@code /api/v1/admin/**} must admit the admin and forbid the standard user (HTTP 403), while
 * both roles may reach an ordinary authenticated business endpoint (HTTP 200), matching the
 * {@code COCOM01Y} {@code 88}-level role contract (AAP&nbsp;0.7.3&nbsp;L1 / 0.8.3).</p>
 *
 * <p>The datasource is supplied dynamically from an ephemeral {@code postgres:16-alpine}
 * container via {@link DynamicPropertySource} (no hardcoded credentials; AAP&nbsp;0.8.1 /
 * 0.9.3), mirroring {@code CardDemoApplicationTests} and {@code AccountServiceTest}. Flyway
 * applies the production migrations and Hibernate runs in {@code validate} mode. The
 * {@code test} profile does not run {@code LocalSeedDataLoader}, so {@code user_security}
 * starts empty; the two users are seeded in {@link #seedUsers()} and removed in
 * {@link #cleanUp()} after each test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Authentication — end-to-end HTTP Basic through the real SecurityFilterChain")
class AuthenticationFilterChainTest {

    /** An ordinary authenticated business endpoint (blank Account View screen; no DB read). */
    private static final String PROTECTED_ENDPOINT = "/api/v1/accounts/view";

    /** An admin-only endpoint ({@code /api/v1/admin/**}, requires {@code ROLE_ADMIN}). */
    private static final String ADMIN_ENDPOINT = "/api/v1/admin/users";

    /** The raw password every seed user is created with (stored upper-case-folded + BCrypt). */
    private static final String RAW_PASSWORD = "PASSWORD";

    private static final String ADMIN_ID = "ADMIN001";
    private static final String USER_ID = "USER0001";

    /**
     * Shared, single-instance PostgreSQL 16 container backing the integration context. Declared
     * {@code static} so Testcontainers starts it once for the class and reuses it.
     */
    @Container
    static PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /**
     * Binds the Spring datasource to the running Testcontainers PostgreSQL instance so no
     * connection string or credential is ever hardcoded.
     *
     * @param registry the Spring test property registry to populate with the container coordinates
     */
    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private final MockMvc mockMvc;
    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructor injection of the {@link MockMvc} configured with the Spring Security filter
     * chain, the {@code user_security} repository used to seed credentials, and the application
     * {@link PasswordEncoder} (so the stored hash is produced exactly as production would).
     *
     * @param mockMvc                the security-filter-applied MockMvc
     * @param userSecurityRepository the user-security repository (seeding + cleanup)
     * @param passwordEncoder        the application password encoder (upper-case fold + BCrypt)
     */
    @Autowired
    AuthenticationFilterChainTest(MockMvc mockMvc,
                                  UserSecurityRepository userSecurityRepository,
                                  PasswordEncoder passwordEncoder) {
        this.mockMvc = mockMvc;
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Seeds one admin ({@code 'A'}) and one standard user ({@code 'U'}), both with the same raw
     * password stored through the application encoder. The test is non-transactional, so these
     * rows are committed and are removed in {@link #cleanUp()}.
     */
    @BeforeEach
    void seedUsers() {
        String stored = passwordEncoder.encode(RAW_PASSWORD);
        userSecurityRepository.save(new UserSecurity(ADMIN_ID, "Ada", "Admin", stored, "A"));
        userSecurityRepository.save(new UserSecurity(USER_ID, "Uma", "User", stored, "U"));
    }

    /** Removes the seeded users after each test (the context is not transactional). */
    @AfterEach
    void cleanUp() {
        userSecurityRepository.deleteAll();
    }

    // ------------------------------------------------------------------------
    // Authentication (BasicAuthenticationFilter -> DaoAuthenticationProvider ->
    // UpperCasePasswordEncoder -> CardDemoUserDetailsService)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("exact credentials authenticate and reach the protected endpoint (200)")
    void exactCredentialsAuthenticate() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT).with(httpBasic(USER_ID, RAW_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a lower-case password authenticates via the upper-case fold (COSGN00C parity, 200)")
    void lowerCasePasswordFoldsAndAuthenticates() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT).with(httpBasic(USER_ID, "password")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a mixed-case password authenticates via the upper-case fold (200)")
    void mixedCasePasswordFoldsAndAuthenticates() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT).with(httpBasic(USER_ID, "PaSsWoRd")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a genuinely wrong password is rejected by the filter chain (401)")
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT).with(httpBasic(USER_ID, "WRONGPASS")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown user is rejected by the filter chain (401)")
    void unknownUserIsRejected() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT).with(httpBasic("NOSUCHUS", RAW_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an anonymous request to a protected endpoint is rejected (401)")
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------
    // Authorization (role A=Admin / U=User over /api/v1/admin/**)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a standard user is forbidden from the admin surface (403)")
    void standardUserForbiddenFromAdmin() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT).with(httpBasic(USER_ID, RAW_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin user is admitted to the admin surface (200)")
    void adminUserAdmittedToAdmin() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT).with(httpBasic(ADMIN_ID, RAW_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an admin user may also reach an ordinary business endpoint (200)")
    void adminUserReachesBusinessEndpoint() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT).with(httpBasic(ADMIN_ID, RAW_PASSWORD)))
                .andExpect(status().isOk());
    }
}
