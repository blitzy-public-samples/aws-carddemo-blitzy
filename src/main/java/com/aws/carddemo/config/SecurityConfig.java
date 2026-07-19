package com.aws.carddemo.config;

import com.aws.carddemo.security.CardDemoAuthenticationProvider;
import com.aws.carddemo.security.CardDemoUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

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
 * <p><strong>{@code CDV1}/{@code COCRDSEC} realization (AAP &sect;0.4.1 / &sect;0.6.10).</strong> The
 * CICS transaction {@code CDV1} points at program {@code COCRDSEC}, a security-only program that has
 * <em>no</em> {@code .cbl} source and therefore no service/controller method. Its card-detail security
 * intent is realized here purely as URL/method authorization on {@code /card/detail} (covered by the
 * terminal {@code anyRequest().authenticated()} rule). This intentional non-migration is documented in
 * {@code docs/decision-log.md} and {@code docs/traceability-matrix.md} rather than silently dropped.</p>
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
     * signon screen, a CSRF-protected logout, and the (default-enabled) CSRF protection.
     *
     * <p>The authorization rules are ordered most-specific-first with a terminal
     * {@code anyRequest().authenticated()}, mirroring the CSD transaction-to-program-to-route model
     * with no new routes or roles.</p>
     *
     * @param http the {@link HttpSecurity} builder supplied by Spring Security
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security filter chain cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                // CC00 / COSGN00C: the signon screen COSGN00 is public (pre-authentication entry point).
                .requestMatchers("/", "/signon").permitAll()
                // Static web assets (not CICS resources): required to render the Thymeleaf screens.
                .requestMatchers("/css/**", "/js/**", "/webjars/**", "/images/**", "/favicon.ico")
                        .permitAll()
                // Observability endpoints exposed for the local Prometheus scrape / health checks only.
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                // Admin-only: CA00/COADM01C (admin menu) + CU00-CU03/COUSR0[0-3]C (user administration).
                // The granted authority string is exactly ROLE_ADMIN, referenced via the shared constant
                // (not a magic string and not a role-prefixed helper).
                .requestMatchers("/admin/**").hasAuthority(CardDemoUserDetailsService.ROLE_ADMIN)
                // Every other business function requires an authenticated session: CM00 (/menu),
                // CAVW/CAUP (/account/**), CCLI/CCDL/CCUP (/card/** incl. /card/detail = CDV1/COCRDSEC),
                // CT00/CT01/CT02 (/transaction/**), CB00 (/billpay), CR00 (/report).
                .anyRequest().authenticated()
        );

        // Unauthenticated access to a protected URL redirects to the signon screen COSGN00 (CC00),
        // reproducing the "sign on first" behavior WITHOUT enabling form-login processing on /signon
        // (SignonController owns POST /signon).
        http.exceptionHandling(ex -> ex.authenticationEntryPoint(
                new LoginUrlAuthenticationEntryPoint("/signon")));

        // Sign-off: a CSRF-protected POST to /logout that clears the pseudo-conversational session
        // (the COMMAREA replacement) and returns to the signon screen with a logout marker.
        http.logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/signon?logout")
                .invalidateHttpSession(true)
                .permitAll());

        // CSRF stays enabled (default): all state-changing requests are Thymeleaf form POSTs carrying
        // the CSRF token; there are no REST endpoints to exempt, so CSRF is never disabled.
        http.csrf(Customizer.withDefaults());

        return http.build();
    }
}
