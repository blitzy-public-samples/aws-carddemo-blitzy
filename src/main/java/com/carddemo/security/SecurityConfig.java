package com.carddemo.security;

import com.carddemo.dto.ErrorResponse;
import com.carddemo.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Central Spring Security configuration for the CardDemo REST API.
 *
 * <p><strong>Migration role.</strong> This class is the keystone of the
 * authentication/authorization migration described in AAP &sect;0.3.2,
 * &sect;0.4.1.5, &sect;0.6.7 and &sect;0.7.2. It establishes a
 * <strong>stateless, JWT-based</strong> security model that replaces two
 * distinct mainframe mechanisms at once:</p>
 * <ol>
 *   <li><strong>CICS COMMAREA session hand-off</strong> ({@code app/cpy/COCOM01Y.cpy}).
 *       In the legacy design the signed-on identity ({@code CDEMO-USER-ID},
 *       {@code CDEMO-USER-TYPE}) was threaded through every pseudo-conversational
 *       {@code XCTL} program transfer. Here there is no server-side session: the
 *       identity travels in a bearer JWT validated on every request by
 *       {@link JwtAuthenticationFilter}, and {@link SessionCreationPolicy#STATELESS}
 *       guarantees no {@code HttpSession} is ever created.</li>
 *   <li><strong>Plaintext password comparison</strong> in {@code app/cbl/COSGN00C.cbl}
 *       ({@code IF SEC-USR-PWD = WS-USER-PWD}). That comparison is replaced by a
 *       hardened {@link BCryptPasswordEncoder} at <strong>strength 12</strong>,
 *       wired into a {@link DaoAuthenticationProvider} together with
 *       {@link CustomUserDetailsService}. The login semantics (locate the user,
 *       verify the credential, then authorize by user type) are preserved while
 *       the credential storage and verification are modernized.</li>
 * </ol>
 *
 * <p><strong>Component-based configuration (Spring Security 6).</strong> The
 * deprecated {@code WebSecurityConfigurerAdapter} is intentionally <em>not</em>
 * used (it was removed in Spring Security 6). Instead this class declares
 * first-class beans &mdash; a {@link SecurityFilterChain}, a single
 * {@link PasswordEncoder}, and an {@link AuthenticationManager} &mdash; and
 * configures the chain through the lambda DSL.</p>
 *
 * <p><strong>Method security.</strong> {@link EnableMethodSecurity} activates
 * annotation-based authorization so that controller methods guarded with
 * {@code @PreAuthorize("hasRole('ADMIN')")} (notably the user-management
 * endpoints) are enforced. The {@code ROLE_} prefix convention is honored
 * throughout: {@code hasRole("ADMIN")} resolves against the {@code ROLE_ADMIN}
 * authority granted by {@link CustomUserDetailsService} and
 * {@link JwtAuthenticationFilter}.</p>
 *
 * <p><strong>Public, unauthenticated paths.</strong> The filter chain permits,
 * without a token:</p>
 * <ul>
 *   <li>{@code POST /auth/signon} &mdash; the sign-on endpoint that issues the
 *       JWT (the REST analogue of {@code COSGN00C});</li>
 *   <li>{@code /v3/api-docs/**}, {@code /swagger-ui/**}, {@code /swagger-ui.html}
 *       &mdash; the springdoc / Swagger UI documentation surface, as required by
 *       the sibling {@code config/OpenApiConfig} contract;</li>
 *   <li>{@code /actuator/health} and {@code /actuator/info} &mdash; operational
 *       probes ({@code /actuator/metrics} stays authenticated);</li>
 *   <li>{@code /h2-console/**} &mdash; <strong>only when the {@code dev} profile
 *       is active</strong> (see below).</li>
 * </ul>
 * <p>Everything else requires an authenticated principal, and {@code /users/**}
 * additionally requires {@code ROLE_ADMIN} (defense-in-depth alongside the
 * controller-level {@code @PreAuthorize}).</p>
 *
 * <p><strong>Dev-only H2 console.</strong> The in-memory H2 console
 * ({@code application-dev.yml}, path {@code /h2-console}) renders inside an HTML
 * frame, which Spring Security blocks by default via the
 * {@code X-Frame-Options: DENY} header. When &mdash; and only when &mdash; the
 * {@code dev} profile is active, this configuration both permits
 * {@code /h2-console/**} and disables frame options so the console loads. In any
 * other profile (notably {@code prod}) neither concession is applied: the
 * console path falls through to {@code authenticated()} and the protective
 * frame-options header is retained. The active profile is read from the injected
 * {@link Environment} via {@link Environment#matchesProfiles(String...)}.</p>
 *
 * <p><strong>Error responses.</strong> Authentication and authorization failures
 * are handled by <em>inline</em> handlers that emit the <strong>same standardized
 * {@code ErrorResponse} JSON envelope</strong> (and the same generic messages
 * {@link GlobalExceptionHandler#MSG_AUTH_FAILED} / {@link GlobalExceptionHandler#MSG_ACCESS_DENIED})
 * that {@code exception/GlobalExceptionHandler} produces, so the API error contract
 * is uniform across the filter-chain and controller code paths: a missing/invalid
 * credential on a protected resource yields {@code 401} (the
 * {@code AuthenticationEntryPoint}), and an authenticated-but-forbidden request
 * yields {@code 403} (the {@code AccessDeniedHandler}). The body is serialized via
 * {@link #writeJsonError} using the shared {@link ObjectMapper}. These handlers are
 * kept inline deliberately &mdash; no additional class is introduced. Domain-level
 * exception-to-HTTP mapping (400/404/409) is the separate responsibility of
 * {@code exception/GlobalExceptionHandler}.</p>
 *
 * @see JwtAuthenticationFilter
 * @see CustomUserDetailsService
 * @see <a href="file:app/cbl/COSGN00C.cbl">app/cbl/COSGN00C.cbl (sign-on source-of-truth)</a>
 * @see <a href="file:app/cpy/COCOM01Y.cpy">app/cpy/COCOM01Y.cpy (COMMAREA source-of-truth)</a>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * BCrypt cost factor (work factor / log&#8322; rounds). Fixed at
     * <strong>12</strong> per AAP &sect;0.6.7 and &sect;0.7.2; it must match the
     * strength used to generate the {@code V4__seed_users.sql} hashes so that the
     * seeded {@code ADMIN001}/{@code USER0001} credentials verify successfully.
     */
    private static final int BCRYPT_STRENGTH = 12;

    /**
     * Name of the Spring profile that enables developer conveniences (in-memory
     * H2 database and its web console). Used to gate the {@code /h2-console}
     * allowance and the frame-options relaxation so they never apply in
     * production.
     */
    private static final String DEV_PROFILE = "dev";

    /**
     * The per-request bearer-token authentication filter. Registered <em>before</em>
     * {@link UsernamePasswordAuthenticationFilter} so that a valid JWT establishes
     * the {@link org.springframework.security.core.Authentication} ahead of any
     * form-login processing. Injected (immutable) rather than {@code new}-ed so the
     * Spring-managed singleton (with its own collaborators) is reused.
     */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Loads application users from the {@code users} table and adapts them to
     * Spring Security {@code UserDetails}. Supplied to the
     * {@link DaoAuthenticationProvider} that backs the {@link AuthenticationManager};
     * it performs the keyed user lookup (the {@code COSGN00C READ-USER-SEC-FILE}
     * analogue) while the provider performs the BCrypt credential check.
     */
    private final CustomUserDetailsService customUserDetailsService;

    /**
     * Spring {@link Environment}, used solely to detect whether the {@code dev}
     * profile is active so the H2-console allowances can be applied conditionally.
     */
    private final Environment environment;

    /**
     * The application's shared, fully-configured {@link ObjectMapper} (the single auto-configured
     * web mapper customized by {@code config/JacksonConfig}: {@code JavaTimeModule} registered,
     * {@code WRITE_DATES_AS_TIMESTAMPS} disabled, {@code NON_NULL} inclusion). Used by
     * {@link #writeJsonError} so the inline {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler}
     * serialize a filter-chain 401/403 into the <em>same</em> {@link ErrorResponse} JSON envelope
     * (identical field set, ISO-8601 timestamp, omitted {@code null}s) that
     * {@code exception/GlobalExceptionHandler} produces for every dispatch-level error.
     */
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection of the security collaborators.
     *
     * <p>Per AAP &sect;0.3.2 the migration uses constructor injection throughout,
     * replacing COBOL static {@code CALL}/{@code XCTL} linkage with explicit,
     * immutable dependencies. A single constructor is auto-detected by Spring, so
     * no {@code @Autowired} annotation is required.</p>
     *
     * @param jwtAuthenticationFilter  the bearer-token filter to install in the
     *                                 chain; never {@code null}
     * @param customUserDetailsService the user-details loader backing the
     *                                 authentication provider; never {@code null}
     * @param environment              the Spring environment used to detect the
     *                                 active profile; never {@code null}
     * @param objectMapper             the shared web {@link ObjectMapper} used to serialize the
     *                                 filter-chain 401/403 {@link ErrorResponse} body; never
     *                                 {@code null}
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          CustomUserDetailsService customUserDetailsService,
                          Environment environment,
                          ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.customUserDetailsService = customUserDetailsService;
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    /**
     * The application's single {@link PasswordEncoder}: a
     * {@link BCryptPasswordEncoder} at cost factor {@value #BCRYPT_STRENGTH}.
     *
     * <p>This bean hardens the legacy plaintext comparison
     * ({@code COSGN00C}: {@code SEC-USR-PWD = WS-USER-PWD}) into a BCrypt
     * verification. It is consumed by the {@link DaoAuthenticationProvider}
     * created in {@link #authenticationManager()} and is also available to the
     * (separately authored) {@code AuthService}/seed tooling. A password encoded
     * by this bean carries a {@code $2a$12$} / {@code $2b$12$} prefix and matches
     * the strength-12 hashes seeded by {@code V4__seed_users.sql}.</p>
     *
     * @return the singleton BCrypt password encoder at strength 12
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    /**
     * The application {@link AuthenticationManager}, built explicitly from a
     * {@link DaoAuthenticationProvider} so the collaborators are unambiguous.
     *
     * <p>The provider is wired with {@link #customUserDetailsService} (the keyed
     * user lookup) and the {@link #passwordEncoder()} bean (BCrypt strength 12).
     * Because this class is a CGLIB-proxied {@code @Configuration} (full mode),
     * the {@code passwordEncoder()} call returns the singleton bean rather than a
     * fresh instance, so the application has exactly one {@link PasswordEncoder}.</p>
     *
     * <p>This manager is the entry point the {@code AuthService} sign-on flow will
     * invoke as
     * {@code authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(userId, rawPassword))}:
     * the provider loads the user via {@link CustomUserDetailsService} and verifies
     * the raw password against the stored BCrypt hash, faithfully reproducing the
     * {@code COSGN00C} login decision while modernizing the credential check.</p>
     *
     * @return a {@link ProviderManager} wrapping a fully configured
     *         {@link DaoAuthenticationProvider}
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(customUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(provider);
    }

    /**
     * Builds the single {@link SecurityFilterChain} that governs every HTTP
     * request to the CardDemo API.
     *
     * <p>The chain is configured for a stateless JSON API:</p>
     * <ul>
     *   <li><strong>CSRF disabled</strong> &mdash; there are no browser form
     *       posts or cookies to protect; clients authenticate with a bearer
     *       token on each request.</li>
     *   <li><strong>{@link SessionCreationPolicy#STATELESS}</strong> &mdash; no
     *       {@code HttpSession} is created or consulted, mirroring the dissolution
     *       of the CICS COMMAREA hand-off.</li>
     *   <li><strong>Authorization rules</strong> are evaluated in the order
     *       declared below (most specific first, {@code anyRequest()} last).</li>
     *   <li><strong>Inline 401/403 handlers</strong> translate authentication and
     *       access-denied failures into bare status codes.</li>
     *   <li>The {@link JwtAuthenticationFilter} is inserted before
     *       {@link UsernamePasswordAuthenticationFilter} so a valid bearer token
     *       establishes the principal up front.</li>
     * </ul>
     *
     * <p>The {@code /h2-console/**} allowance and the frame-options relaxation are
     * applied <em>only</em> under the {@code dev} profile; in every other profile
     * the console path is left to {@code authenticated()} and the protective
     * {@code X-Frame-Options} header is retained.</p>
     *
     * @param http the {@link HttpSecurity} builder supplied by Spring Security
     * @return the immutable, fully configured {@link SecurityFilterChain}
     * @throws Exception if the {@link HttpSecurity} builder fails to assemble the
     *                   chain
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Read the active profile once; gates the dev-only H2-console concessions.
        final boolean devProfile = environment.matchesProfiles(DEV_PROFILE);

        http
                // Stateless JSON API: no CSRF tokens, no server-side session.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Authorization rules, evaluated top-to-bottom (specific -> general).
                .authorizeHttpRequests(auth -> {
                    // Sign-on issues the JWT and must be reachable without one
                    // (the REST analogue of COSGN00C). Restricted to POST.
                    auth.requestMatchers(HttpMethod.POST, "/auth/signon").permitAll();
                    // springdoc / Swagger UI documentation surface (OpenApiConfig contract).
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .permitAll();
                    // Operational probes are open; /actuator/metrics stays authenticated.
                    auth.requestMatchers("/actuator/health", "/actuator/info").permitAll();
                    // Dev-only: the in-memory H2 web console (never permitted in prod).
                    if (devProfile) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    // User administration is admin-only (defense-in-depth with @PreAuthorize).
                    auth.requestMatchers("/users/**").hasRole("ADMIN");
                    // Everything else requires an authenticated principal.
                    auth.anyRequest().authenticated();
                })
                // Inline failure handlers: 401 for missing/invalid auth, 403 for
                // authenticated-but-forbidden. Kept inline by design (no extra class). Each writes
                // the SAME standardized ErrorResponse JSON envelope (and the SAME generic messages)
                // that exception/GlobalExceptionHandler emits for dispatch-level errors, so the API's
                // error contract is uniform across the filter-chain and controller code paths.
                // Domain 400/404/409 mapping still lives in GlobalExceptionHandler.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(request, response, HttpStatus.UNAUTHORIZED,
                                        GlobalExceptionHandler.MSG_AUTH_FAILED))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJsonError(request, response, HttpStatus.FORBIDDEN,
                                        GlobalExceptionHandler.MSG_ACCESS_DENIED)));

        // Dev-only: the H2 console renders in a frame, which the default
        // X-Frame-Options: DENY header blocks. Disable frame options ONLY in dev;
        // prod retains the protective header.
        if (devProfile) {
            http.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable()));
        }

        // Establish the bearer-token principal before form-login processing.
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Suppresses the duplicate servlet-container registration of {@link JwtAuthenticationFilter}.
     *
     * <p><strong>Defect background (QA finding F-1).</strong> {@link JwtAuthenticationFilter} is a
     * Spring {@code @Component}, and any {@code Filter} bean is, by default, auto-registered by
     * Spring Boot as a top-level servlet filter. Because the same filter is <em>also</em> added to
     * the Spring Security chain via {@link HttpSecurity#addFilterBefore(jakarta.servlet.Filter, Class)}
     * in {@link #filterChain(HttpSecurity)}, the bean was registered <strong>twice</strong>. At real
     * runtime (embedded Tomcat) the standalone servlet-level copy runs first and marks the
     * {@code OncePerRequestFilter} as already-filtered; the in-chain copy is then skipped, so the
     * request reaches the {@code AuthorizationFilter} without the JWT principal established in the
     * security context. For an authenticated non-admin hitting {@code /users/**} this makes the
     * {@code ExceptionTranslationFilter} treat the principal as anonymous and route the
     * {@code AccessDeniedException} to the {@code AuthenticationEntryPoint} (HTTP&nbsp;401) instead of
     * the {@code AccessDeniedHandler} (HTTP&nbsp;403).</p>
     *
     * <p><strong>Fix.</strong> Wrapping the singleton in a {@link FilterRegistrationBean} and calling
     * {@link FilterRegistrationBean#setEnabled(boolean) setEnabled(false)} tells Spring Boot to NOT
     * register it as a standalone servlet filter, while the bean remains fully available for the
     * security chain. The filter therefore executes <strong>exactly once</strong> per request &mdash;
     * only where {@link #filterChain(HttpSecurity)} positions it &mdash; so an authenticated
     * non-admin is correctly denied with HTTP&nbsp;403 and an unauthenticated caller with
     * HTTP&nbsp;401, restoring correct REST authorization semantics (AAP &sect;0.4.1.4 / &sect;0.3.2).
     * This bean has no effect in {@code MockMvc}-based slice tests (which do not perform servlet-level
     * filter auto-registration); it matters only under the real servlet container.</p>
     *
     * @return a disabled registration that removes {@link JwtAuthenticationFilter} from the
     *         servlet-container filter chain (it stays active only inside the Spring Security chain)
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration() {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Writes a standardized {@link ErrorResponse} JSON body for a security failure raised
     * <em>inside</em> the filter chain (a missing/invalid credential &rarr; 401, or an
     * authenticated-but-forbidden request &rarr; 403).
     *
     * <p><strong>Why this exists (QA finding F-2).</strong> The inline
     * {@code AuthenticationEntryPoint} and {@code AccessDeniedHandler} run before the
     * {@code DispatcherServlet}, so the {@code @RestControllerAdvice}
     * ({@code exception/GlobalExceptionHandler}) never sees these failures. Previously they called
     * {@code response.sendError(...)}, which produced an <strong>empty body</strong> and broke the
     * uniform error contract every other failure honors. This helper instead reproduces the exact
     * {@link ErrorResponse} envelope the advice emits: the same component set
     * ({@code timestamp}/{@code status}/{@code error}/{@code message}/{@code path}), the same generic
     * non-enumerating messages ({@link GlobalExceptionHandler#MSG_AUTH_FAILED} /
     * {@link GlobalExceptionHandler#MSG_ACCESS_DENIED}), and the same shared {@link ObjectMapper} (so
     * the {@code timestamp} serializes to the same ISO-8601 form and {@code null} fields are omitted).</p>
     *
     * <p>No sensitive detail ever enters the body: the message is a fixed generic constant and the
     * {@code path} is only the request URI (AAP &sect;0.6.8).</p>
     *
     * @param request  the current request; only its URI is read (for {@code ErrorResponse.path})
     * @param response the response to which the JSON error envelope is written
     * @param status   the HTTP status to emit (401 {@code UNAUTHORIZED} or 403 {@code FORBIDDEN})
     * @param message  the generic, client-safe message (never reveals which credential/role failed)
     * @throws IOException if writing to the response output stream fails
     */
    private void writeJsonError(HttpServletRequest request, HttpServletResponse response,
                                HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = ErrorResponse.of(
                status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
