package com.carddemo.security;

import com.carddemo.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.LocalDateTime;

/**
 * Central Spring Security 6.x configuration for the CardDemo Spring Boot application.
 *
 * <h2>Origin &mdash; what this replaces</h2>
 *
 * <p>In the legacy mainframe system, authentication and authorization were realized by
 * the CICS COBOL signon program and the CICS resource (CSD) definitions:</p>
 * <ul>
 *   <li>{@code app/cbl/COSGN00C.cbl} (L211-L257) &mdash; the {@code READ-USER-SEC-FILE}
 *       paragraph performed a keyed VSAM read against the {@code USRSEC} dataset and then
 *       authenticated the caller with a <em>plaintext</em> password comparison
 *       {@code IF SEC-USR-PWD = WS-USER-PWD} (L223). On success it routed the user with
 *       {@code EXEC CICS XCTL PROGRAM('COADM01C')} for an administrator
 *       ({@code CDEMO-USRTYP-ADMIN}, type {@code 'A'}) or {@code PROGRAM('COMEN01C')} for a
 *       regular user ({@code CDEMO-USRTYP-USER}, type {@code 'U'}).</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} &mdash; {@code DEFINE PROGRAM(COSGN00C) GROUP(CARDDEMO)
 *       TRANSID(CC00)} and {@code DEFINE TRANSACTION(CC00) ... PROGRAM(COSGN00C)} bound the
 *       {@code CC00} transaction id to the signon program; downstream programs were reached
 *       by transaction-id routing through the CICS menu programs.</li>
 * </ul>
 *
 * <h2>What this configures</h2>
 *
 * <p>This class wires a stateless, JWT-based REST security model that supersedes the
 * pseudo-conversational CICS flow. There is no server-side session and no
 * {@code COMMAREA}; every request carries its own signed JSON Web Token, validated by
 * {@link JwtAuthenticationFilter}, and authorization is enforced at the URL boundary here
 * plus at the method boundary by the {@code @PreAuthorize} annotations activated in the
 * sibling {@link MethodSecurityConfig}.</p>
 *
 * <p>It exposes six beans:</p>
 * <ol>
 *   <li>{@link #passwordEncoder()} &mdash; the BCrypt {@link PasswordEncoder} (PR-17),
 *       consumed here by the {@link #authenticationProvider() DAO provider} and elsewhere by
 *       {@code UserService} and {@code UserSeedingJobConfig}.</li>
 *   <li>{@link #authenticationProvider()} &mdash; a {@link DaoAuthenticationProvider} backed
 *       by {@link UserDetailsServiceImpl} and the BCrypt encoder, replacing the COBOL keyed
 *       read + plaintext compare.</li>
 *   <li>{@link #authenticationManager(AuthenticationConfiguration)} &mdash; the Spring-managed
 *       {@link AuthenticationManager}, consumed by {@code AuthService} to authenticate login
 *       requests.</li>
 *   <li>{@link #filterChain(HttpSecurity)} &mdash; the {@link SecurityFilterChain} defining
 *       URL authorization, stateless sessions, CSRF/CORS policy, the JWT filter placement, and
 *       the custom 401/403 handlers.</li>
 *   <li>{@link #jsonAuthenticationEntryPoint()} &mdash; emits a 401 {@link ErrorResponse} JSON
 *       body for unauthenticated access.</li>
 *   <li>{@link #jsonAccessDeniedHandler()} &mdash; emits a 403 {@link ErrorResponse} JSON body
 *       for authenticated-but-unauthorized access.</li>
 * </ol>
 *
 * <h2>Endpoint authorization rules</h2>
 * <table border="1">
 *   <caption>URL-based authorization configured in {@link #filterChain(HttpSecurity)}</caption>
 *   <tr><th>Matcher</th><th>Access</th></tr>
 *   <tr><td>{@code POST /api/auth/login}</td><td>permitAll &mdash; the sign-on endpoint (replaces COSGN00C / CC00)</td></tr>
 *   <tr><td>{@code POST /api/auth/logout}</td><td>authenticated &mdash; rejects anonymous logout attempts with 401</td></tr>
 *   <tr><td>{@code /actuator/health}, {@code /actuator/info}</td><td>permitAll &mdash; operational probes</td></tr>
 *   <tr><td>{@code /v3/api-docs/**}, {@code /swagger-ui/**}, {@code /swagger-ui.html}</td><td>permitAll &mdash; API docs</td></tr>
 *   <tr><td>{@code OPTIONS /**}</td><td>permitAll &mdash; CORS preflight</td></tr>
 *   <tr><td>{@code /api/admin/**}</td><td>{@code hasRole('ADMIN')} &mdash; PR-18, closes the COUSR programmatic-auth gap</td></tr>
 *   <tr><td>any other request</td><td>authenticated</td></tr>
 * </table>
 *
 * <h2>Preservation / refactoring rules honored</h2>
 * <ul>
 *   <li><strong>PR-17</strong> &mdash; {@link BCryptPasswordEncoder} hashes/verifies passwords;
 *       the plaintext compare {@code IF SEC-USR-PWD = WS-USER-PWD} (COSGN00C L223) is gone.</li>
 *   <li><strong>PR-18</strong> &mdash; URL-based authorization ({@code /api/admin/**} &rarr;
 *       {@code ADMIN}) layered with method-level {@code @PreAuthorize} (enabled in
 *       {@link MethodSecurityConfig}) provides defense in depth.</li>
 *   <li><strong>PR-19</strong> &mdash; role mapping ({@code 'A'} &rarr; {@code ROLE_ADMIN},
 *       {@code 'U'} &rarr; {@code ROLE_USER}) is owned by {@code User.getAuthorities()} /
 *       {@code CustomAuthorityMapper}; this class consumes those authorities for
 *       {@code hasRole(...)} checks.</li>
 *   <li><strong>PR-28</strong> &mdash; the Jakarta EE 10 namespace
 *       ({@code jakarta.servlet.http.HttpServletResponse}, never {@code javax.servlet.*}).</li>
 *   <li><strong>PR-29</strong> &mdash; constructor injection over {@code final} fields via Lombok
 *       {@link RequiredArgsConstructor &#64;RequiredArgsConstructor}; no field injection.</li>
 * </ul>
 *
 * <h2>Spring Security 6.x conventions</h2>
 * <p>This configuration uses the {@link SecurityFilterChain} bean style (not the removed
 * {@code WebSecurityConfigurerAdapter}), the lambda DSL throughout (e.g.
 * {@code http.csrf(csrf -> csrf.disable())}), and {@code requestMatchers(...)} (not the removed
 * {@code antMatchers(...)}). Method security is intentionally <em>not</em> enabled here &mdash;
 * {@link MethodSecurityConfig} owns {@code @EnableMethodSecurity} so the responsibilities stay
 * cleanly separated and the annotation is declared exactly once.</p>
 *
 * @see JwtAuthenticationFilter
 * @see UserDetailsServiceImpl
 * @see MethodSecurityConfig
 * @see CustomAuthorityMapper
 * @see com.carddemo.exception.ErrorResponse
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    /**
     * Spring Security {@link UserDetailsService} used by the {@link #authenticationProvider()
     * DAO authentication provider} to load user records during authentication. At runtime this
     * resolves to {@link UserDetailsServiceImpl}, whose {@code loadUserByUsername} performs the
     * {@code UserRepository.findById(username)} lookup that replaces the legacy
     * {@code EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID)} keyed VSAM read in
     * {@code COSGN00C READ-USER-SEC-FILE}. Injected via the Lombok-generated constructor (PR-29).
     */
    private final UserDetailsService userDetailsService;

    /**
     * The stateless JWT bearer-token filter inserted into the {@link SecurityFilterChain} before
     * {@link UsernamePasswordAuthenticationFilter}. It reconstructs the
     * {@code Authentication} (user id + authorities) from the {@code Authorization: Bearer <jwt>}
     * header on every request &mdash; the per-request equivalent of the legacy CICS
     * {@code COMMAREA} identity hand-off ({@code CDEMO-USER-ID} / {@code CDEMO-USER-TYPE}).
     * Injected via the Lombok-generated constructor (PR-29).
     */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Jackson {@link ObjectMapper} (auto-configured by Spring Boot) used by the custom
     * {@link #jsonAuthenticationEntryPoint() 401} and {@link #jsonAccessDeniedHandler() 403}
     * handlers to serialize the {@link ErrorResponse} payload directly to the servlet output
     * stream, guaranteeing a consistent JSON error contract even for failures raised by the
     * security filter chain (which run before the MVC message converters). Injected via the
     * Lombok-generated constructor (PR-29).
     */
    private final ObjectMapper objectMapper;

    /**
     * Defines the application-wide password encoder &mdash; <strong>PR-17</strong>.
     *
     * <p>Returns a {@link BCryptPasswordEncoder} at the default strength (log rounds = 10), which
     * provides an adaptive, salted one-way hash. Each encoded value embeds its own 16-byte random
     * salt, so the ten default users seeded from {@code app/jcl/DUSRSECJ.jcl} &mdash; all sharing
     * the literal password {@code "PASSWORD"} &mdash; receive distinct 60-character hashes.</p>
     *
     * <p>This replaces the COBOL plaintext comparison {@code IF SEC-USR-PWD = WS-USER-PWD} at
     * {@code app/cbl/COSGN00C.cbl:L223}: verification is now performed by
     * {@code BCryptPasswordEncoder.matches(rawPassword, storedHash)} inside the
     * {@link #authenticationProvider() DAO provider}. The same bean is reused by
     * {@code UserService} (encode on create/update) and {@code UserSeedingJobConfig} (initial
     * BCrypt rehash of the default roster).</p>
     *
     * @return the BCrypt {@link PasswordEncoder} singleton
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Defines the DAO-based {@link org.springframework.security.authentication.AuthenticationProvider}
     * that authenticates username/password credentials.
     *
     * <p>The provider delegates user loading to {@link #userDetailsService} (i.e.
     * {@link UserDetailsServiceImpl}, which reads the {@code users} table) and password
     * verification to the {@link #passwordEncoder() BCrypt encoder}. Together they reproduce the
     * legacy {@code COSGN00C} signon: the keyed {@code USRSEC} read becomes
     * {@code UserRepository.findById(...)}, and the plaintext {@code SEC-USR-PWD = WS-USER-PWD}
     * compare becomes a BCrypt {@code matches(...)} check.</p>
     *
     * <p>Spring Security 6.2.x's {@link DaoAuthenticationProvider} no-argument constructor with
     * explicit setters is used (the deprecation of that constructor is a later-version change and
     * does not apply to the 6.2.x line resolved by the Spring Boot 3.2.12 BOM).</p>
     *
     * @return a configured {@link DaoAuthenticationProvider} singleton
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * Exposes the Spring-managed {@link AuthenticationManager} as a bean.
     *
     * <p>The manager is retrieved from the framework-provided
     * {@link AuthenticationConfiguration}, which assembles a {@code ProviderManager} from the
     * {@link #authenticationProvider() DAO provider} bean declared above. {@code AuthService}
     * injects this bean and calls {@code authenticate(...)} to validate {@code POST /api/auth/login}
     * credentials before issuing a JWT &mdash; the modern equivalent of the {@code COSGN00C}
     * sign-on decision that previously drove the {@code EXEC CICS XCTL} routing.</p>
     *
     * @param authConfig the Spring Security {@link AuthenticationConfiguration} (injected by the
     *                   framework)
     * @return the application {@link AuthenticationManager}
     * @throws Exception if the authentication manager cannot be obtained from the configuration
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    /**
     * Builds the application {@link SecurityFilterChain} &mdash; the heart of the configuration.
     *
     * <p>This single filter chain governs every HTTP request and encodes the migration of the
     * CICS {@code CC00} signon transaction and its downstream routing into stateless REST
     * authorization. The configuration, in order:</p>
     * <ol>
     *   <li><strong>CSRF disabled.</strong> The API is stateless and authenticates via a JWT in
     *       the {@code Authorization} header (never a cookie), so cross-site request forgery is
     *       not applicable. (Should cookie-based sessions ever be introduced, CSRF protection must
     *       be re-enabled.)</li>
     *   <li><strong>CORS enabled with {@link Customizer#withDefaults()}.</strong> The actual CORS
     *       source (allowed origins/methods/headers) is defined in {@code WebConfig}; this merely
     *       integrates Spring Security with whatever {@code CorsConfigurationSource} is present.</li>
     *   <li><strong>Stateless sessions.</strong> {@link SessionCreationPolicy#STATELESS} means no
     *       {@code JSESSIONID} is created or consulted; each request re-authenticates from its
     *       token, mirroring the per-interaction nature of CICS pseudo-conversational tasks.</li>
     *   <li><strong>URL authorization.</strong> Public endpoints (login, logout, health/info
     *       probes, API docs, and CORS preflight {@code OPTIONS}) are permitted to all; every
     *       {@code /api/admin/**} endpoint requires {@code ROLE_ADMIN} (PR-18); all other requests
     *       require authentication.</li>
     *   <li><strong>DAO provider.</strong> The {@link #authenticationProvider()} is registered so
     *       username/password authentication uses {@link UserDetailsServiceImpl} + BCrypt.</li>
     *   <li><strong>JWT filter placement.</strong> {@link #jwtAuthenticationFilter} runs before
     *       {@link UsernamePasswordAuthenticationFilter} so a valid bearer token establishes the
     *       {@code SecurityContext} for the rest of the chain (including the {@code @PreAuthorize}
     *       checks enabled by {@link MethodSecurityConfig}).</li>
     *   <li><strong>Error handling.</strong> Unauthenticated access yields a 401 via
     *       {@link #jsonAuthenticationEntryPoint()}; authenticated-but-forbidden access yields a
     *       403 via {@link #jsonAccessDeniedHandler()} &mdash; both as {@link ErrorResponse} JSON.</li>
     * </ol>
     *
     * @param http the {@link HttpSecurity} builder supplied by Spring Security
     * @return the fully configured {@link SecurityFilterChain}
     * @throws Exception if the filter chain cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless REST + JWT in the Authorization header — CSRF protection not applicable.
                .csrf(csrf -> csrf.disable())
                // Integrate with the CORS source defined in WebConfig (config/ package).
                .cors(Customizer.withDefaults())
                // No HTTP session — every request authenticates via its JWT.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // URL-based authorization rules.
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints — sign-on, operational probes, and API docs ONLY.
                        // SECURITY (CP4): /api/auth/logout is deliberately NOT public. The checkpoint
                        // public-route matrix admits only login, actuator health/info and the
                        // swagger/openapi docs; logout must invalidate an established identity and
                        // therefore requires an authenticated principal. Omitting it here lets it
                        // fall through to .anyRequest().authenticated() below, so an anonymous caller
                        // is rejected by jsonAuthenticationEntryPoint() with a 401 (matching the
                        // OpenAPI 401 contract on AuthController#logout).
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // CORS preflight — allow OPTIONS without authentication.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Admin-only endpoints (PR-18 — closes the COUSR programmatic-auth gap).
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Everything else requires an authenticated principal.
                        .anyRequest().authenticated())
                // Authenticate username/password via the BCrypt-backed DAO provider.
                .authenticationProvider(authenticationProvider())
                // Establish the principal from the bearer token before the username/password filter.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Custom 401 / 403 handlers emit a consistent ErrorResponse JSON body.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint())
                        .accessDeniedHandler(jsonAccessDeniedHandler()));

        return http.build();
    }

    /**
     * Returns the {@link AuthenticationEntryPoint} that handles <strong>401 Unauthorized</strong>
     * &mdash; i.e. an <em>unauthenticated</em> caller attempting to reach a protected endpoint
     * (missing, malformed, or expired JWT).
     *
     * <p>The handler writes an {@link ErrorResponse} JSON body directly to the servlet output
     * stream via the injected {@link #objectMapper}, because security-filter failures occur before
     * the Spring MVC message converters run. The attempt is logged at {@code WARN} with the target
     * URI and the framework's message; no credentials or token contents are ever logged. The
     * semantic is "who are you?" &mdash; authentication is required and was not satisfied.</p>
     *
     * @return an {@link AuthenticationEntryPoint} emitting a 401 {@link ErrorResponse}
     */
    @Bean
    public AuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            log.warn("Unauthenticated access attempt to {}: {}",
                    request.getRequestURI(), authException.getMessage());

            ErrorResponse body = ErrorResponse.builder()
                    .status(HttpServletResponse.SC_UNAUTHORIZED)
                    .code("UNAUTHORIZED")
                    .message("Authentication is required to access this resource")
                    .path(request.getRequestURI())
                    .timestamp(LocalDateTime.now())
                    .build();

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), body);
        };
    }

    /**
     * Returns the {@link AccessDeniedHandler} that handles <strong>403 Forbidden</strong> &mdash;
     * i.e. an <em>authenticated</em> caller who lacks the role required for the target endpoint
     * (for example a {@code ROLE_USER} principal calling {@code /api/admin/users}).
     *
     * <p>This is the structured-error counterpart to PR-18: instead of the legacy mainframe's
     * silent reliance on {@code COADM01C} menu routing (which a direct CICS transaction call could
     * bypass), an unauthorized REST caller now receives an explicit 403 {@link ErrorResponse} JSON
     * body. The handler writes the body directly via the injected {@link #objectMapper} and logs
     * the denial at {@code WARN} (target URI + message only; no principal credentials). The
     * semantic is "I know who you are, but you may not do this".</p>
     *
     * @return an {@link AccessDeniedHandler} emitting a 403 {@link ErrorResponse}
     */
    @Bean
    public AccessDeniedHandler jsonAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            log.warn("Access denied to {}: {}",
                    request.getRequestURI(), accessDeniedException.getMessage());

            ErrorResponse body = ErrorResponse.builder()
                    .status(HttpServletResponse.SC_FORBIDDEN)
                    .code("FORBIDDEN")
                    .message("You do not have permission to access this resource")
                    .path(request.getRequestURI())
                    .timestamp(LocalDateTime.now())
                    .build();

            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), body);
        };
    }
}
