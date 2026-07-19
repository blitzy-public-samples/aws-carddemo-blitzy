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
package com.aws.carddemo.config;

import com.aws.carddemo.security.ProblemDetailAccessDeniedHandler;
import com.aws.carddemo.security.ProblemDetailAuthenticationEntryPoint;
import com.aws.carddemo.security.UpperCasePasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Security configuration for the CardDemo application.
 *
 * <p>This class declares the single, canonical HTTP {@link SecurityFilterChain} and the
 * application-wide {@link PasswordEncoder} bean, and enables method-level security. Together these
 * re-express the legacy CICS/COMMAREA authentication and role model (sign-on program
 * {@code app/cbl/COSGN00C.cbl} and the {@code COCOM01Y} {@code 88}-level condition names
 * {@code CDEMO-USRTYP-ADMIN VALUE 'A'} / {@code CDEMO-USRTYP-USER VALUE 'U'}) as a Spring Security
 * filter chain (AAP &sect;0.2.2, &sect;0.5.5, &sect;0.7 hotspot L1).
 *
 * <h2>Authentication delegation</h2>
 * <p>Authentication itself is delegated to the sibling {@code com.aws.carddemo.security} package.
 * {@code CardDemoUserDetailsService} is the single {@link org.springframework.security.core.userdetails.UserDetailsService}
 * bean (it loads {@code user_security} rows via {@code UserSecurityRepository} and performs no
 * password comparison). Because exactly one {@code UserDetailsService} bean and one
 * {@link PasswordEncoder} bean are present, Spring Security's default global
 * {@code AuthenticationManager} auto-creates a {@code DaoAuthenticationProvider} from them; this
 * class therefore intentionally declares no {@code UserDetailsService}, {@code AuthenticationManager},
 * {@code AuthenticationProvider}, or {@code DaoAuthenticationProvider} of its own, keeping the wiring
 * standard and warning-free.
 *
 * <h2>Credential-case normalization (parity across both authentication surfaces)</h2>
 * <p>The {@link PasswordEncoder} bean is an {@code UpperCasePasswordEncoder} wrapping
 * {@link BCryptPasswordEncoder}. Folding the raw password to upper case inside the shared encoder
 * makes the framework-built {@code DaoAuthenticationProvider} (the HTTP&nbsp;Basic gate) honor the
 * same case-insensitive password contract as the legacy CC00 sign-on program
 * ({@code legacy/cbl/COSGN00C.cbl} folds the password with {@code FUNCTION UPPER-CASE} before
 * comparison), so a credential accepted at sign-on is no longer rejected on the protected API. See
 * the {@link #passwordEncoder()} Javadoc and {@code docs/decision-log.md} (decision D26).
 *
 * <h2>Why a hashing encoder (documented deviation, AAP &sect;0.7 hotspot L1)</h2>
 * <p>The legacy mainframe design stored user passwords in the {@code USRSEC} VSAM dataset in
 * <em>plaintext</em> (an intentional demonstration anti-pattern). Reproducing plaintext storage
 * verbatim would be an insecure regression, so the migration contract records password hashing as
 * an explicit, documented security improvement rather than a silent behavior change: credentials
 * are verified through {@link PasswordEncoder#matches(CharSequence, String)} against a
 * {@link BCryptPasswordEncoder} hash (the seed data in {@code db/seed/user_security.csv} stores
 * BCrypt hashes accordingly). The observable authentication contract &mdash; role {@code A} = Admin
 * and role {@code U} = User &mdash; is preserved unchanged; only the on-storage credential
 * representation is hardened. Passwords are never logged.
 *
 * <h2>Authorization rules</h2>
 * <p>The chain is a stateless HTTP Basic API (no browser session, no form login). Rules are evaluated
 * most-specific-first:
 * <ul>
 *   <li>{@code permitAll} for the sign-on entry point ({@code /api/v1/auth/**}), the springdoc
 *       OpenAPI UI and documents ({@code /swagger-ui/**}, {@code /swagger-ui.html},
 *       {@code /v3/api-docs/**}, {@code /v3/api-docs.yaml}) plus the checked-in static OpenAPI
 *       snapshot ({@code /openapi/**}, a generated copy of {@code /v3/api-docs.yaml}), and the unauthenticated operational probes / scrape endpoints
 *       ({@code /actuator/health/**}, {@code /actuator/info}, {@code /actuator/prometheus}). Opening
 *       {@code /actuator/prometheus} lets the local Prometheus scrape without credentials; the other
 *       Actuator endpoints (e.g. {@code /actuator/metrics}) remain authenticated by the catch-all
 *       rule below (deny-by-default).</li>
 *   <li>{@code /api/v1/admin/**} requires {@code hasRole("ADMIN")} &mdash; the Admin Menu (CA00) and
 *       all user-management transactions (CU00&ndash;CU03). Because {@code UserRole} grants the
 *       {@code ROLE_}-prefixed authorities {@code ROLE_ADMIN}/{@code ROLE_USER}, the rule uses
 *       {@code hasRole} (which auto-prepends {@code ROLE_}), never {@code hasAuthority}.</li>
 *   <li>{@code anyRequest().authenticated()} secures every remaining business endpoint.</li>
 * </ul>
 *
 * <p>{@code @EnableMethodSecurity} is enabled so that class/method-level {@code @PreAuthorize}
 * annotations on the admin controllers are honored (defense-in-depth alongside the URL rule). CSRF
 * is disabled because this is a stateless API authenticated with HTTP Basic (there is no
 * browser-managed session or cookie to protect). The session-creation policy is
 * {@link SessionCreationPolicy#STATELESS}.
 *
 * <h2>Cross-cutting</h2>
 * <p>The {@code observability/CorrelationIdFilter} is a self-registering {@code @Component} at
 * {@code HIGHEST_PRECEDENCE} and is deliberately not referenced here (adding it to the chain would
 * double-register it). Because that filter runs ahead of the security filters, the correlation ID
 * it places in the SLF4J MDC is available to the {@link ProblemDetailAuthenticationEntryPoint} and
 * {@link ProblemDetailAccessDeniedHandler} installed below, which serialize authentication (401)
 * and authorization (403) failures as RFC-7807 {@code application/problem+json} bodies uniform with
 * the {@code @RestControllerAdvice GlobalExceptionHandler}. No connection strings or secrets are
 * declared in this class, honoring the "no hardcoded credentials" constraint (AAP &sect;0.8.1).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Declares the single application {@link SecurityFilterChain}: a stateless, CSRF-disabled HTTP
     * Basic API whose authorization rules are evaluated most-specific-first (public sign-on, OpenAPI
     * docs, and operational probes/scrape; admin URLs requiring {@code ROLE_ADMIN}; every other
     * request authenticated).
     *
     * <h3>RFC-7807 authentication/authorization failures</h3>
     * <p>Authentication (401) and authorization (403) failures are raised inside the security filter
     * chain, before the {@code DispatcherServlet} and therefore beyond the reach of the
     * {@code @RestControllerAdvice} {@code GlobalExceptionHandler}. To keep the error contract
     * uniform across the entire API surface, the chain installs a {@link
     * ProblemDetailAuthenticationEntryPoint} and a {@link ProblemDetailAccessDeniedHandler} that
     * serialize an RFC-7807 {@code application/problem+json} body (carrying the request-scoped
     * {@code correlationId}) identical in shape to the handler-produced responses.
     *
     * <p>The entry point is installed in two places on purpose: {@link
     * org.springframework.security.web.access.ExceptionTranslationFilter} invokes the configured
     * {@code exceptionHandling} entry point for an anonymous request to a protected resource, while
     * {@code BasicAuthenticationFilter} invokes its own entry point on a failed credential - both
     * paths must emit the same RFC-7807 body, so the entry point is set on {@code httpBasic} as well.
     *
     * @param http          the {@link HttpSecurity} builder supplied by Spring Security
     * @param objectMapper  the application {@link ObjectMapper} (retains Boot's {@code ProblemDetail}
     *                      support) used by the entry point / access-denied handler to serialize the
     *                      RFC-7807 body
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if the security configuration cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        // Shared RFC-7807 failure responders (401 / 403). A single AuthenticationEntryPoint instance
        // is used by both the ExceptionTranslationFilter (anonymous -> protected resource) and the
        // BasicAuthenticationFilter (failed credential) so every unauthenticated response is uniform.
        AuthenticationEntryPoint authenticationEntryPoint =
            new ProblemDetailAuthenticationEntryPoint(objectMapper);
        AccessDeniedHandler accessDeniedHandler =
            new ProblemDetailAccessDeniedHandler(objectMapper);
        http
            // Stateless API authenticated with HTTP Basic: no browser session/cookie to protect.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Sign-on entry point (COSGN00C / CC00) - the only unauthenticated business path.
                .requestMatchers("/api/v1/auth/**").permitAll()
                // springdoc OpenAPI UI + document endpoints (paths from application.yml). The
                // "/v3/api-docs/**" matcher covers the JSON document (/v3/api-docs) and its
                // sub-paths (e.g. /v3/api-docs/swagger-config); "/v3/api-docs.yaml" is listed
                // explicitly because the sibling YAML document is not matched by "/v3/api-docs/**"
                // and serves the same public OpenAPI content as the JSON form. "/openapi/**"
                // exposes the checked-in static OpenAPI snapshot (a generated copy of
                // /v3/api-docs.yaml served from static/openapi/openapi.yaml); it is published
                // documentation and must be reachable without credentials, consistent with the
                // live docs above.
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                 "/v3/api-docs/**", "/v3/api-docs.yaml",
                                 "/openapi/**").permitAll()
                // Operational probes + Prometheus scrape endpoint (unauthenticated by design);
                // other actuator endpoints (e.g. /actuator/metrics) stay behind authentication.
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                // Admin Menu (CA00) + user-management (CU00-CU03) require the ADMIN role.
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // Every remaining business endpoint requires authentication.
                .anyRequest().authenticated())
            .httpBasic(basic -> basic.authenticationEntryPoint(authenticationEntryPoint))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * The application-wide password encoder used to verify (and, for user administration, encode)
     * {@code USRSEC} credentials.
     *
     * <p>The bean is an {@link UpperCasePasswordEncoder} wrapping a {@link BCryptPasswordEncoder}.
     * The BCrypt delegate applies a per-hash random salt and an adaptive work factor, so equal
     * plaintext passwords produce distinct hashes and verification is performed with
     * {@link PasswordEncoder#matches(CharSequence, String)}. The {@link UpperCasePasswordEncoder}
     * wrapper folds the raw password to upper case ({@link java.util.Locale#ROOT}) before every
     * {@code encode}/{@code matches} call, reproducing the case-insensitive password contract of the
     * legacy sign-on program ({@code legacy/cbl/COSGN00C.cbl:L132-L136} folds the entered password
     * with {@code FUNCTION UPPER-CASE} before comparison).
     *
     * <p>This wrapping is the fix for the credential-case divergence between the two authentication
     * surfaces: the CC00 sign-on service ({@code SignonService}) already upper-cased the password
     * before comparison, but the framework-built {@code DaoAuthenticationProvider} that guards every
     * protected HTTP&nbsp;Basic endpoint compared the raw, exact-case password against the stored
     * hash, so a lower-/mixed-case credential accepted at sign-on was rejected (401) on every API
     * call. Performing the fold inside the shared encoder makes both surfaces &mdash; and user
     * administration ({@code UserService}) &mdash; apply one identical normalization rule. The fold
     * is idempotent, so the explicit service-level folds are unaffected. The rationale is recorded in
     * {@code docs/decision-log.md} (decision D26).
     *
     * <p>This is the single {@link PasswordEncoder} bean the authentication collaborators document as
     * their expected dependency; combined with the single {@code CardDemoUserDetailsService} bean it
     * forms the framework-built {@code DaoAuthenticationProvider}.
     *
     * @return the singleton {@link UpperCasePasswordEncoder} (wrapping {@link BCryptPasswordEncoder})
     *         shared across the authentication and user-administration services
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new UpperCasePasswordEncoder(new BCryptPasswordEncoder());
    }
}
