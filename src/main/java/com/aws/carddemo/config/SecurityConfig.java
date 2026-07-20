package com.aws.carddemo.config;

import com.aws.carddemo.security.CardDemoAuthenticationProvider;
import com.aws.carddemo.security.CardDemoUserDetailsService;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Spring Security configuration for the CardDemo migration (RACF + COBOL signon -&gt; Spring Security).
 *
 * <p>This is the Java realization of the z/OS security model: it replaces RACF dataset/transaction
 * protection and the application-level signon program {@code COSGN00C} with a single
 * {@link SecurityFilterChain}, an {@link AuthenticationManager}, and URL/method authorization rules.
 * No new routes, roles, or external interfaces are introduced &mdash; every rule is traceable to a
 * CICS resource definition (no feature expansion, AAP &sect;0.2.2 / &sect;0.7.1).</p>
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.10):</strong></p>
 * <ul>
 *   <li>Origin: {@code legacy/csd/CARDDEMO.CSD} &mdash; the CICS transaction/program/file resource
 *       definitions drive the route + role rules (18 transactions, 8 files, 17 programs). Every CSD
 *       transaction carries {@code PROFILE(DFHCICST) STATUS(ENABLED)} with {@code RESSEC(NO)} /
 *       {@code CMDSEC(NO)}, i.e. there is <em>no</em> per-transaction RACF attribute; role enforcement
 *       is therefore application-level, keyed on the user type flag, exactly as below.</li>
 * </ul>
 *
 * <p><strong>Role model (AAP &sect;0.6.7).</strong> The COBOL user type flag {@code SEC-USR-TYPE}
 * maps to a single Spring Security authority: {@code 'A'} &rarr;
 * {@link CardDemoUserDetailsService#ROLE_ADMIN} (admin menu {@code COADM01C} / transaction
 * {@code CA00}), and every other value &rarr; {@link CardDemoUserDetailsService#ROLE_USER} (main menu
 * {@code COMEN01C} / transaction {@code CM00}). Admin-only functions are the admin menu ({@code CA00})
 * plus the user-administration transactions ({@code CU00}-{@code CU03} / {@code COUSR0[0-3]C}); they
 * are gated behind a single {@code /admin/**} rule.</p>
 *
 * <p><strong>{@code CDV1}/{@code COCRDSEC} realization (AAP &sect;0.4.1 / &sect;0.6.10; review
 * finding #1).</strong> The CICS transaction {@code CDV1} points at program {@code COCRDSEC}
 * ("DEVELOPER TRANSACTION - 1" in {@code legacy/csd/CARDDEMO.CSD}), a security-only program that has
 * <em>no</em> {@code .cbl} source, <em>no</em> menu entry (it appears on neither the user menu
 * {@code COMEN02Y} nor the admin menu {@code COADM02Y}), and therefore <em>no</em> migrated route. It
 * must not be conflated with {@code /card/detail}: that route is the distinct transaction {@code CCDL}
 * &rarr; program {@code COCRDSLC} ("Credit Card View", user-menu option&nbsp;4, user type {@code 'U'}),
 * an ordinary authenticated function reachable by both roles. The source-authoritative {@code CDV1}
 * policy is thus "no exposed route", and it is realized by the fail-closed terminal rule
 * {@code anyRequest().denyAll()} &mdash; any un-enumerated path (including any hypothetical
 * {@code CDV1}/{@code COCRDSEC} developer path) is denied rather than left incidentally reachable
 * through a permissive catch-all. Every business route below carries its own explicit authorization
 * rule, so no resource depends on the catch-all (no catch-all bypass). This intentional non-migration
 * is recorded in {@code docs/decision-log.md} and {@code docs/traceability-matrix.md}.</p>
 *
 * <p><strong>Session lifecycle (review findings #6, #8, #43).</strong> Because signon is
 * controller-managed (see below), this class exposes a {@link SessionAuthenticationStrategy} bean that
 * {@code SignonController} invokes on a successful authentication to (1) rotate the session id
 * (fixation protection, CWE-384) and (2) register the session in the {@link SessionRegistry}. The
 * registry, together with {@code sessionManagement().maximumSessions(-1)} (unlimited concurrent
 * sessions &mdash; no new concurrency cap is introduced &mdash; but with the concurrency filter active
 * so expiry is enforced) and the {@link HttpSessionEventPublisher} bean, lets the user-administration
 * services revoke a principal's live sessions when that user is deleted, demoted, or has their password
 * changed, so a stale identity cannot continue with an authorized session.</p>
 *
 * <p><strong>Authentication shape: controller-managed (not form-login).</strong> The signon screen
 * {@code COSGN00} (transaction {@code CC00}) is owned by {@code SignonController}, which declares its
 * own {@code POST /signon} handler so it can reproduce the COBOL ENTER / PF3 / invalid-key /
 * blank-field behavior and perform the role-based redirect (admin &rarr; {@code /admin/menu}, user
 * &rarr; {@code /menu}). Consequently this configuration deliberately does <em>not</em> enable Spring
 * Security's built-in form-login processing on {@code /signon} (which would let the security filter
 * intercept the POST and turn the controller handler into dead code). Instead it exposes an
 * {@link AuthenticationManager} bean that {@code SignonController}/{@code SignonService} inject by type
 * to authenticate programmatically, and an {@link LoginUrlAuthenticationEntryPoint} that redirects
 * unauthenticated access to a protected URL back to the signon screen.</p>
 *
 * <p><strong>Provider registration reconciliation.</strong> {@link CardDemoAuthenticationProvider}
 * documents direct registration on the {@link HttpSecurity} builder as one valid path; for
 * controller-managed authentication the equivalent, cleaner path is the single {@link ProviderManager}
 * {@link AuthenticationManager} bean exposed here &mdash; so this class registers the provider that way
 * and does <em>not</em> additionally register it on the {@link HttpSecurity} builder (which would build
 * a second, separate manager).</p>
 *
 * <p><strong>Password parity (AAP &sect;0.6.7).</strong> The cleartext password comparison of the COBOL
 * signon is preserved for functional parity by {@link CardDemoAuthenticationProvider}; accordingly this
 * class configures no Spring password-encoding bean and no DAO-based authentication provider. The
 * delegating encoder that Spring Security wires by default expects an {@code {id}} prefix on the stored
 * value and would break cleartext parity, while the deprecated no-op encoder would break the
 * zero-warning build; the custom provider is the sanctioned mechanism. Introducing password hashing is
 * recorded as a suggested next task, not a silent behavior change.</p>
 *
 * <p><strong>CSRF.</strong> CSRF protection stays enabled (Spring Security default): every
 * state-changing request is a server-rendered Thymeleaf form POST that carries the CSRF token, and
 * there are no REST endpoints to exempt. Sign-off is a CSRF-protected {@code POST /logout}.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Exposes the application {@link AuthenticationManager} as a {@link ProviderManager} wrapping the
     * custom cleartext {@link CardDemoAuthenticationProvider}.
     *
     * <p>This IS the provider registration for the controller-managed signon: {@code SignonController}
     * / {@code SignonService} inject this manager by type and call
     * {@link AuthenticationManager#authenticate(org.springframework.security.core.Authentication)}
     * directly, then persist the resulting {@code SecurityContext} to the {@code HttpSession} and
     * redirect by role. Because the provider is wired into this single manager, this class must
     * <em>not</em> additionally register it on the {@link HttpSecurity} builder (doing so would create
     * a second, separate {@link AuthenticationManager}). No password-encoding bean is configured
     * &mdash; the cleartext comparison lives entirely inside {@link CardDemoAuthenticationProvider} for
     * COBOL parity.</p>
     *
     * @param cardDemoAuthenticationProvider the custom cleartext authentication provider (injected by
     *                                        type; discovered via component scanning)
     * @return a {@link ProviderManager} backed solely by the CardDemo authentication provider
     */
    @Bean
    public AuthenticationManager authenticationManager(
            CardDemoAuthenticationProvider cardDemoAuthenticationProvider) {
        return new ProviderManager(cardDemoAuthenticationProvider);
    }

    /**
     * Builds the single {@link SecurityFilterChain}: the URL authorization rules derived from
     * {@code legacy/csd/CARDDEMO.CSD}, the unauthenticated-access entry point that redirects to the
     * signon screen, a CSRF-protected logout, session-registry-backed session management, and the
     * (default-enabled) CSRF protection.
     *
     * <p><strong>Fail-closed authorization (review finding #1).</strong> Every migrated route is
     * enumerated with its own explicit rule and the chain terminates in {@code anyRequest().denyAll()},
     * not {@code anyRequest().authenticated()}. Consequently no business resource relies on a permissive
     * catch-all, and any path that is <em>not</em> a migrated CICS transaction &mdash; including any
     * hypothetical {@code CDV1}/{@code COCRDSEC} developer path, which has no migrated route &mdash; is
     * denied by default rather than being incidentally reachable by any authenticated user. The rules
     * are ordered most-specific-first; the leading {@code dispatcherTypeMatchers(ERROR, FORWARD)} permit
     * is required so that server-side error/forward dispatches (e.g. rendering the error view) are not
     * themselves blocked by the terminal {@code denyAll()}.</p>
     *
     * <p><strong>Actuator exposure (review finding #7).</strong> Only {@code /actuator/health/**} is
     * anonymous (container/liveness probes). {@code /actuator/info} and {@code /actuator/prometheus}
     * expose build/environment and operational metrics and therefore require
     * {@link CardDemoUserDetailsService#ROLE_ADMIN}; they are no longer readable anonymously.</p>
     *
     * <p><strong>Session management (review findings #6, #8, #43).</strong> A {@link SessionRegistry} is
     * attached via {@code maximumSessions(-1)} (unlimited concurrent sessions &mdash; no new cap is
     * imposed &mdash; but the concurrency filter becomes active so that a session marked expired is
     * enforced on its next request and redirected to {@code /signon?expired}). This is what allows the
     * user-administration services to revoke the live sessions of a user who is deleted, demoted, or has
     * their password changed. Session-id rotation on signon (fixation protection) and registration of
     * the session into this registry are performed by {@code SignonController} through the
     * {@link #sessionAuthenticationStrategy(SessionRegistry) sessionAuthenticationStrategy} bean, because
     * signon is controller-managed rather than filter-managed.</p>
     *
     * @param http           the {@link HttpSecurity} builder supplied by Spring Security
     * @param sessionRegistry the shared {@link SessionRegistry} used for session tracking and revocation
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security filter chain cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SessionRegistry sessionRegistry)
            throws Exception {
        http.authorizeHttpRequests(auth -> auth
                // Permit server-side ERROR/FORWARD dispatches so the terminal denyAll() below does not
                // block error-view rendering or internal forwards (external requests cannot forge these
                // dispatcher types).
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                // CC00 / COSGN00C: the signon screen COSGN00 is public (pre-authentication entry point).
                .requestMatchers("/", "/signon").permitAll()
                // CSRF-protected sign-off endpoint (POST /logout); success returns to /signon?logout.
                .requestMatchers("/logout").permitAll()
                // Static web assets (not CICS resources): required to render the Thymeleaf screens.
                .requestMatchers("/css/**", "/js/**", "/webjars/**", "/images/**", "/favicon.ico")
                        .permitAll()
                // Liveness/readiness probes only: health is anonymous. info + prometheus are NOT (F#7).
                .requestMatchers("/actuator/health/**").permitAll()
                // Operational endpoints (build/env info + metrics scrape) require admin authority (F#7).
                .requestMatchers("/actuator/info", "/actuator/prometheus")
                        .hasAuthority(CardDemoUserDetailsService.ROLE_ADMIN)
                // Admin-only: CA00/COADM01C (admin menu) + CU00-CU03/COUSR0[0-3]C (user administration).
                // The granted authority string is exactly ROLE_ADMIN, referenced via the shared constant
                // (not a magic string and not a role-prefixed helper).
                .requestMatchers("/admin/**").hasAuthority(CardDemoUserDetailsService.ROLE_ADMIN)
                // Explicit rule per migrated business transaction reachable by both roles (F#1: no route
                // rides the catch-all). /card/detail is CCDL -> COCRDSLC ("Credit Card View", user-menu
                // option 4); it is an ordinary authenticated function and is NOT the CDV1/COCRDSEC
                // developer transaction (which has no migrated route and is denied by the terminal rule).
                .requestMatchers(
                        "/menu",                 // CM00 / COMEN01C
                        "/account/view",         // CAVW / COACTVWC
                        "/account/update",       // CAUP / COACTUPC
                        "/card/list",            // CCLI / COCRDLIC
                        "/card/detail",          // CCDL / COCRDSLC
                        "/card/update",          // CCUP / COCRDUPC
                        "/transaction/list",     // CT00 / COTRN00C
                        "/transaction/view",     // CT01 / COTRN01C
                        "/transaction/add",      // CT02 / COTRN02C
                        "/billpay",              // CB00 / COBIL00C
                        "/report")               // CR00 / CORPT00C
                        .authenticated()
                // Fail closed: anything not enumerated above (incl. CDV1/COCRDSEC developer paths and any
                // unmapped URL) is denied for everyone, so there is no catch-all authorization bypass.
                .anyRequest().denyAll()
        );

        // Unauthenticated access to a protected URL redirects to the signon screen COSGN00 (CC00),
        // reproducing the "sign on first" behavior WITHOUT enabling form-login processing on /signon
        // (SignonController owns POST /signon).
        http.exceptionHandling(ex -> ex.authenticationEntryPoint(
                new LoginUrlAuthenticationEntryPoint("/signon")));

        // Session management: attach the shared SessionRegistry so live sessions can be tracked and
        // revoked (findings #8/#43). maximumSessions(-1) imposes NO concurrency cap but activates the
        // ConcurrentSessionFilter, so a session marked expireNow() by SessionRevocationService is
        // enforced on its next request and redirected to the expired URL.
        http.sessionManagement(session -> session
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry)
                .expiredUrl("/signon?expired"));

        // Sign-off: a CSRF-protected POST to /logout that clears the pseudo-conversational session
        // (the COMMAREA replacement) and returns to the signon screen with a logout marker. The URL is
        // permitted explicitly in the authorization rules above (kept off the configurer to avoid
        // registering matchers after the terminal anyRequest() rule).
        http.logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/signon?logout")
                .invalidateHttpSession(true));

        // CSRF stays enabled (default): all state-changing requests are Thymeleaf form POSTs carrying
        // the CSRF token; there are no REST endpoints to exempt, so CSRF is never disabled.
        http.csrf(Customizer.withDefaults());

        return http.build();
    }

    /**
     * The shared {@link SessionRegistry} that tracks every authenticated {@code HttpSession} so that a
     * principal's live sessions can be enumerated and expired (revoked) when the user is deleted,
     * demoted, or has their password changed (review findings #8, #43).
     *
     * <p>It is consumed by three collaborators, all of which must share this single instance: the
     * {@link #filterChain(HttpSecurity, SessionRegistry) filter chain} (which activates the concurrency
     * filter so expiry is enforced), the {@link #sessionAuthenticationStrategy(SessionRegistry)
     * sessionAuthenticationStrategy} (which registers each session at signon), and
     * {@code SessionRevocationService} (which calls {@code expireNow()} on a target user's sessions).</p>
     *
     * @return a process-local {@link SessionRegistryImpl}
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Publishes servlet {@code HttpSessionEvent}s to the Spring {@link org.springframework.context
     * .ApplicationContext} so that {@link SessionRegistryImpl} is notified when a session is created or
     * destroyed and keeps its principal-to-session map accurate.
     *
     * <p>Without this publisher the registry would leak entries for sessions that were invalidated
     * (e.g. by logout or container timeout), which would make revocation and session enumeration
     * unreliable. It is a required companion to {@link #sessionRegistry()}.</p>
     *
     * @return the {@link HttpSessionEventPublisher} bridging servlet session events to the context
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * The {@link SessionAuthenticationStrategy} that {@code SignonController} invokes immediately after a
     * successful programmatic authentication, before persisting the {@code SecurityContext}.
     *
     * <p>Because signon is controller-managed (see the class Javadoc), the session-fixation and
     * session-registration behavior that Spring Security would normally apply during filter-managed
     * form-login must be applied explicitly by the controller. This composite runs, in order:</p>
     * <ol>
     *   <li>{@link ChangeSessionIdAuthenticationStrategy} &mdash; rotates the {@code HttpSession} id so a
     *       pre-authentication (possibly attacker-fixed) session id cannot be reused after signon
     *       (fixation protection, CWE-384; review finding #6); and</li>
     *   <li>{@link RegisterSessionAuthenticationStrategy} &mdash; records the <em>rotated</em> session in
     *       the shared {@link SessionRegistry} so it becomes eligible for later revocation (findings #8,
     *       #43). Registration follows rotation so the registry tracks the post-rotation id.</li>
     * </ol>
     *
     * @param sessionRegistry the shared registry (same instance used by the filter chain and revocation)
     * @return the composite fixation-then-register strategy consumed by {@code SignonController}
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(SessionRegistry sessionRegistry) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(sessionRegistry)));
    }
}
