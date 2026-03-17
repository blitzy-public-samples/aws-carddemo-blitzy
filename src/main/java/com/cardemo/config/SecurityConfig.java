/*
 * ============================================================================
 * SecurityConfig.java — Spring Security Configuration
 * ============================================================================
 * AWS CardDemo Mainframe Application
 * Migrated from COBOL/CICS/VSAM to Java 25 + Spring Boot 3.5.x
 *
 * Source COBOL artifacts translated by this configuration:
 *   - COSGN00C.cbl  — Sign-on authentication program (CC00 transaction)
 *   - CSUSR01Y.cpy  — User security record layout (SEC-USR-ID PK, SEC-USR-PWD,
 *                      SEC-USR-TYPE 'A'/'U')
 *   - COCOM01Y.cpy  — COMMAREA 88-level conditions:
 *                        88 CDEMO-USRTYP-ADMIN VALUE 'A' → ROLE_ADMIN
 *                        88 CDEMO-USRTYP-USER  VALUE 'U' → ROLE_USER
 *
 * Authentication Flow Mapping (COSGN00C.cbl → SecurityConfig):
 *   1. RECEIVE MAP COSGN0A (lines 110–115)   → HTTP Basic credentials extraction
 *   2. UPPER-CASE user ID (lines 132–136)     → Spring Security username handling
 *   3. READ USRSEC by key (lines 211–219)     → UserDetailsService.loadUserByUsername()
 *   4. IF SEC-USR-PWD = WS-USER-PWD (line 223)→ BCryptPasswordEncoder.matches()
 *   5. IF CDEMO-USRTYP-ADMIN (lines 230–240)  → hasRole("ADMIN") authorization rule
 *   6. RESP code 13 not-found (lines 247–251) → UsernameNotFoundException
 *
 * CRITICAL: The COBOL source stores passwords as plaintext PIC X(08) in
 * CSUSR01Y.cpy field SEC-USR-PWD. This Java migration replaces plaintext
 * comparison with BCrypt hashing per AAP security requirements. The seed
 * data loader must hash original plaintext passwords during database loading.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 * ============================================================================
 */
package com.cardemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration that implements role-based access control,
 * replacing the COBOL CICS authentication infrastructure from COSGN00C.cbl.
 *
 * <p>This configuration establishes the security foundation for the migrated
 * CardDemo application by defining:</p>
 * <ul>
 *   <li>A {@link PasswordEncoder} bean using BCrypt — replaces the COBOL plaintext
 *       password comparison ({@code IF SEC-USR-PWD = WS-USER-PWD}, COSGN00C.cbl
 *       line 223)</li>
 *   <li>A {@link SecurityFilterChain} bean that configures:
 *     <ul>
 *       <li>CSRF protection disabled for REST API (stateless, no browser forms)</li>
 *       <li>Role-based URL authorization matching COBOL 88-level conditions from
 *           COCOM01Y.cpy (CDEMO-USRTYP-ADMIN VALUE 'A' → ROLE_ADMIN,
 *           CDEMO-USRTYP-USER VALUE 'U' → ROLE_USER)</li>
 *       <li>Stateless session management matching the CICS pseudo-conversational
 *           model where each transaction is independent</li>
 *       <li>HTTP Basic authentication as the minimum auth mechanism for REST</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><strong>Note:</strong> The UserDetailsService implementation that performs
 * actual user lookup against the UserSecurityRepository is NOT in this config
 * class — it belongs in the service layer (SignonService). This config provides
 * only the infrastructure beans and the security filter chain.</p>
 *
 * <p><strong>Role Mapping from COBOL 88-level Conditions:</strong></p>
 * <pre>
 *   COCOM01Y.cpy:
 *     10 CDEMO-USER-TYPE               PIC X(01).
 *        88 CDEMO-USRTYP-ADMIN         VALUE 'A'.   → ROLE_ADMIN
 *        88 CDEMO-USRTYP-USER          VALUE 'U'.   → ROLE_USER
 * </pre>
 *
 * @see org.springframework.security.web.SecurityFilterChain
 * @see org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Creates a BCrypt password encoder bean for secure password hashing.
     *
     * <p>This bean replaces the COBOL plaintext password storage and comparison
     * pattern from the original CardDemo application:</p>
     * <ul>
     *   <li><strong>COBOL (CSUSR01Y.cpy):</strong> {@code SEC-USR-PWD PIC X(08)}
     *       — passwords stored as 8-character plaintext</li>
     *   <li><strong>COBOL (COSGN00C.cbl line 223):</strong>
     *       {@code IF SEC-USR-PWD = WS-USER-PWD} — direct string comparison</li>
     *   <li><strong>Java replacement:</strong> BCrypt adaptive hashing with
     *       strength factor 10 (default) — passwords stored as 60-character
     *       BCrypt hashes, compared using constant-time comparison</li>
     * </ul>
     *
     * <p>The seed data loader (V100__seed_data.sql or equivalent) must hash the
     * original plaintext passwords from {@code app/data/ASCII/} test fixtures
     * using this encoder during database initialization.</p>
     *
     * @return a {@link BCryptPasswordEncoder} instance for password encoding
     *         and verification throughout the application
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Builds the security filter chain that defines HTTP security rules for
     * the entire CardDemo REST API.
     *
     * <p>This method translates the COBOL CICS authentication and authorization
     * flow from COSGN00C.cbl into Spring Security 6.x configuration using the
     * lambda DSL. The filter chain enforces:</p>
     *
     * <ol>
     *   <li><strong>CSRF disabled</strong> — The REST API is stateless and does
     *       not use browser-based form submissions, so CSRF protection is not
     *       applicable. The original CICS application had no CSRF concern as
     *       3270 terminals communicate over SNA/LU 6.2, not HTTP.</li>
     *   <li><strong>Authorization rules</strong> — mapped from COBOL role checks:
     *     <ul>
     *       <li>{@code /api/auth/**} → {@code permitAll()} — Sign-on endpoint,
     *           maps to COSGN00C.cbl CC00 transaction (no prior auth required)</li>
     *       <li>{@code /api/admin/**} → {@code hasRole("ADMIN")} — Admin-only
     *           endpoints, maps to COADM01C.cbl which checks
     *           {@code IF CDEMO-USRTYP-ADMIN} before granting access to user
     *           management screens (COUSR00C–COUSR03C)</li>
     *       <li>{@code /actuator/health} → {@code permitAll()} — Health check
     *           for observability infrastructure (no COBOL equivalent)</li>
     *       <li>{@code /actuator/readiness} → {@code permitAll()} — Readiness
     *           probe for container orchestration (no COBOL equivalent)</li>
     *       <li>All other {@code /api/**} → {@code authenticated()} — Requires
     *           authentication, maps to all CICS programs that verify
     *           {@code CDEMO-USER-ID} is populated in the COMMAREA before
     *           processing (COCOM01Y.cpy)</li>
     *     </ul>
     *   </li>
     *   <li><strong>Stateless session management</strong> — Uses
     *       {@link SessionCreationPolicy#STATELESS} to match the CICS
     *       pseudo-conversational model where each transaction is independent.
     *       The COBOL COMMAREA (COCOM01Y.cpy, 1024 bytes) carried session state
     *       between transactions; in Java, the request-scoped CardDemoContext
     *       bean replaces this mechanism.</li>
     *   <li><strong>HTTP Basic authentication</strong> — Minimum auth mechanism
     *       for REST API access. The COBOL application used BMS map-based
     *       credential entry (COSGN0A map with USERIDI and PASSWDI fields);
     *       HTTP Basic provides equivalent credential transmission for a
     *       headless service layer.</li>
     * </ol>
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return a fully configured {@link SecurityFilterChain} encapsulating all
     *         authorization rules, session policy, and authentication config
     * @throws Exception if an error occurs during security configuration
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — REST API is stateless, no browser form submissions.
            // Original CICS 3270 terminal protocol (SNA/LU 6.2) had no CSRF concern.
            .csrf(csrf -> csrf.disable())

            // Authorization rules mapped from COBOL role-based access patterns.
            // COSGN00C.cbl authenticates users; COCOM01Y.cpy carries role info
            // (CDEMO-USER-TYPE: 'A' = Admin, 'U' = User) through the COMMAREA.
            .authorizeHttpRequests(auth -> auth
                // Sign-on endpoint — no prior auth required.
                // Maps to COSGN00C.cbl CC00 transaction entry point.
                .requestMatchers("/api/auth/**").permitAll()

                // Actuator health and readiness/liveness endpoints — publicly accessible
                // for observability infrastructure (load balancers, Kubernetes probes).
                // Wildcard covers /actuator/health, /actuator/health/readiness,
                // and /actuator/health/liveness sub-paths.
                .requestMatchers("/actuator/health/**").permitAll()

                // Admin-only endpoints — restricted to users with ROLE_ADMIN.
                // Maps to COBOL 88-level condition: CDEMO-USRTYP-ADMIN VALUE 'A'
                // from COCOM01Y.cpy line 27. Only admin users can access
                // user management (COUSR00C–COUSR03C) and admin menu (COADM01C).
                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                // All other API endpoints require authentication.
                // Maps to all CICS programs that check CDEMO-USER-ID is populated
                // in the COMMAREA (COCOM01Y.cpy line 25) before processing.
                .requestMatchers("/api/**").authenticated()
            )

            // Stateless session management — matches the CICS pseudo-conversational
            // model where each transaction is independent. The COBOL COMMAREA
            // (COCOM01Y.cpy, 1024 bytes) carried session state; in Java, the
            // request-scoped CardDemoContext bean replaces this mechanism.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // HTTP Basic authentication — minimum auth mechanism for REST API.
            // Replaces BMS map-based credential entry (COSGN0A map fields:
            // USERIDI for user ID, PASSWDI for password) from COSGN00C.cbl.
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}
