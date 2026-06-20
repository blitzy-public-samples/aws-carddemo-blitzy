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

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Central Spring Security configuration for the migrated AWS CardDemo application. This single
 * {@link Configuration} class is the modern replacement for the legacy z/OS security model: the
 * RACF-protected CICS sign-on transaction {@code CC00} ({@code legacy/app/cbl/COSGN00C.cbl}) and
 * the clear-text VSAM {@code USRSEC} credential file (copybook {@code
 * legacy/app/cpy/CSUSR01Y.cpy}). It wires three beans that, together, reproduce the legacy
 * authentication and role-gating behaviour with hashed, externalized credentials (Agent Action Plan
 * &sect;0.6.5, &sect;0.6.6, &sect;0.7.2).
 *
 * <ol>
 *   <li>{@link #passwordEncoder()} &mdash; a {@link BCryptPasswordEncoder} that replaces the legacy
 *       clear-text {@code SEC-USR-PWD PIC X(08)} comparison with a one-way BCrypt hash check.
 *   <li>{@link #userDetailsService(UserSecurityRepository)} &mdash; loads the credential/role
 *       record from PostgreSQL (table {@code user_security}) exactly as {@code COSGN00C} read the
 *       {@code USRSEC} KSDS, translating the legacy {@code SEC-USR-TYPE} flag into a Spring
 *       Security role.
 *   <li>{@link #securityFilterChain(HttpSecurity)} &mdash; enforces the role-based URL
 *       authorization that mirrors the CICS menu gating: administrator-only screens are unreachable
 *       by standard users, and a successful sign-on is routed to the correct landing menu by role.
 * </ol>
 *
 * <h2>Authentication parity with COSGN00C</h2>
 *
 * <p>The legacy program reads {@code USRSEC} keyed by user id, compares the stored password, and on
 * success branches on {@code SEC-USR-TYPE}: {@code 'A'} transfers control to the admin menu program
 * {@code COADM01C} (transaction {@code CA00}); any other value transfers to the main menu program
 * {@code COMEN01C} (transaction {@code CM00}). A user-not-found condition (CICS {@code RESP 13})
 * produces an error. Here, Spring Security's auto-configured {@code DaoAuthenticationProvider}
 * &mdash; assembled automatically from the {@link UserDetailsService} and {@link PasswordEncoder}
 * beans below &mdash; performs the lookup and BCrypt password check, the role derived in {@link
 * #userDetailsService(UserSecurityRepository)} drives authorization, and the {@linkplain
 * #roleBasedSuccessHandler() success handler} reproduces the post-sign-on menu routing.
 *
 * <h2>Role model</h2>
 *
 * <p>The role source of truth is the COMMAREA copybook {@code legacy/app/cpy/COCOM01Y.cpy}: {@code
 * 10 CDEMO-USER-TYPE PIC X(01)} with {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code 88
 * CDEMO-USRTYP-USER VALUE 'U'}. This maps directly to two Spring roles &mdash; {@code ADMIN} for
 * stored type {@code 'A'} and {@code USER} for type {@code 'U'} (or any non-admin value, matching
 * the legacy {@code ELSE} branch). Administrator-only functions are URL-gated to {@link
 * #ROLE_ADMIN}: the admin menu ({@code COADM01C}, transaction {@code CA00}) is gated by the {@code
 * /admin/**} space, and the user-management screens ({@code COUSR00C}&ndash;{@code COUSR03C},
 * {@code CU00}&ndash;{@code CU03}) are gated by explicit matchers on their web routes ({@code
 * /user-list}, {@code /user-add}, {@code /user-update}, {@code /user-delete}), which the web layer
 * maps outside {@code /admin/**}. Those user-management services additionally carry
 * {@code @PreAuthorize("hasRole('ADMIN')")} as defense-in-depth, so admin gating holds at both the
 * URL and the service layer.
 *
 * <h2>Security hardening</h2>
 *
 * <p>No credentials are hardcoded anywhere in this class (Agent Action Plan &sect;0.7.2; OWASP gate
 * &sect;0.7.3). The two seed identities {@code ADMIN001} / {@code USER0001} are inserted at startup
 * by a dedicated bootstrap seeder that reads externalized environment variables and BCrypt-encodes
 * them using the {@link #passwordEncoder()} bean defined here; this class only declares the encoder
 * and the lookup/authorization policy.
 *
 * <h2>CSRF</h2>
 *
 * <p>CSRF protection is deliberately left at its enabled Spring Security default. The target online
 * UI is Spring MVC + Thymeleaf form login (option A), and Thymeleaf automatically injects the CSRF
 * token into rendered forms, so protection is transparent and correct for the browser flow. A
 * future stateless REST profile could revisit this posture, but for the form-login application the
 * default-enabled stance is the secure and correct choice.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

  /**
   * Spring Security role name granted to administrators. Derived from the legacy {@code
   * SEC-USR-TYPE = 'A'} ({@code CDEMO-USRTYP-ADMIN}). Used both when building a user's authorities
   * and when gating {@code /admin/**} via {@code hasRole(...)} (which transparently prefixes {@code
   * ROLE_}).
   */
  private static final String ROLE_ADMIN = "ADMIN";

  /**
   * Spring Security role name granted to standard users. Derived from the legacy {@code
   * SEC-USR-TYPE = 'U'} ({@code CDEMO-USRTYP-USER}) and from any non-admin type, matching the
   * legacy {@code ELSE} branch in {@code COSGN00C}.
   */
  private static final String ROLE_USER = "USER";

  /**
   * Fully-qualified granted-authority string for the admin role. Spring Security prefixes role
   * names with {@code ROLE_} when forming authorities, so the {@code ADMIN} role surfaces as the
   * {@code ROLE_ADMIN} authority. This constant is matched against the authenticated principal's
   * authorities when choosing the post-sign-on landing page.
   */
  private static final String AUTHORITY_ADMIN = "ROLE_" + ROLE_ADMIN;

  /**
   * Legacy {@code SEC-USR-TYPE} value that denotes an administrator ({@code 'A'}). Compared
   * case-insensitively against the trimmed {@code char(1)} type column to select the granted role.
   */
  private static final String SEC_USR_TYPE_ADMIN = "A";

  /**
   * Post-sign-on landing path for administrators. Mirrors the legacy {@code XCTL
   * PROGRAM('COADM01C')} transfer to the admin menu (transaction {@code CA00}).
   */
  private static final String ADMIN_LANDING_PATH = "/admin";

  /**
   * Post-sign-on landing path for standard users. Mirrors the legacy {@code XCTL
   * PROGRAM('COMEN01C')} transfer to the main menu (transaction {@code CM00}).
   */
  private static final String USER_LANDING_PATH = "/menu";

  /**
   * Platform-wide password encoder. BCrypt replaces the legacy clear-text {@code SEC-USR-PWD PIC
   * X(08)} field (Agent Action Plan &sect;0.6.6): stored passwords are one-way BCrypt hashes rather
   * than recoverable plaintext, and authentication compares a presented password against the hash
   * instead of performing the legacy literal {@code SEC-USR-PWD = WS-USER-PWD} equality test.
   *
   * <p>This is the single, application-wide encoder. It is consumed by the bootstrap credential
   * seeder (same package) to encode the externalized seed passwords and by any future
   * credential-changing service, and it is picked up automatically by Spring Boot's {@code
   * DaoAuthenticationProvider} for sign-on verification. No credential value is created or stored
   * here (Agent Action Plan &sect;0.7.2).
   *
   * @return a {@link BCryptPasswordEncoder} for hashing and verifying user passwords
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Loads a user's credential and role record for authentication, reproducing the {@code USRSEC}
   * read performed by the legacy sign-on program {@code COSGN00C}.
   *
   * <p>The returned {@link UserDetailsService} looks the principal up by primary key via {@link
   * UserSecurityRepository#findById(Object)} (the modern equivalent of the legacy {@code EXEC CICS
   * READ DATASET('USRSEC') RIDFLD(user-id)}). When a record is found it is mapped to a Spring
   * {@link UserDetails}:
   *
   * <ul>
   *   <li>the username is the trimmed {@code SEC-USR-ID} (the {@code char(8)} key is blank-padded,
   *       so the padding is removed for a clean principal name);
   *   <li>the password is the already-BCrypt-hashed {@code SEC-USR-PWD} value, which Spring's
   *       authentication provider verifies against the presented password using {@link
   *       #passwordEncoder()};
   *   <li>the role is derived from {@code SEC-USR-TYPE} per copybook {@code COCOM01Y}: {@code 'A'}
   *       (compared case-insensitively after trimming the {@code char(1)} column) yields {@link
   *       #ROLE_ADMIN}; every other value yields {@link #ROLE_USER}, matching the legacy {@code IF
   *       CDEMO-USRTYP-ADMIN ... ELSE ...} branch.
   * </ul>
   *
   * <p>When no record exists the service throws {@link UsernameNotFoundException}, which is the
   * direct analogue of the legacy {@code WHEN 13} ({@code RESP 13}) "User not found" path in {@code
   * COSGN00C}. The lookup uses the supplied user id as-is: the seed identities {@code ADMIN001} /
   * {@code USER0001} are already uppercase, and the {@code char(8)} key column makes the match
   * insensitive to trailing-space padding.
   *
   * @param repository the Spring Data JPA repository backing the {@code user_security} table
   * @return a {@link UserDetailsService} that resolves CardDemo security records to Spring users
   */
  @Bean
  public UserDetailsService userDetailsService(UserSecurityRepository repository) {
    return username -> {
      UserSecurity user =
          repository
              .findById(username)
              .orElseThrow(
                  () ->
                      new UsernameNotFoundException(
                          "No CardDemo security record found for user id '" + username + "'"));
      String userType = user.getSecUsrType() == null ? "" : user.getSecUsrType().trim();
      String role = SEC_USR_TYPE_ADMIN.equalsIgnoreCase(userType) ? ROLE_ADMIN : ROLE_USER;
      UserDetails details =
          User.withUsername(user.getSecUsrId().trim())
              .password(user.getSecUsrPwd())
              .roles(role)
              .build();
      return details;
    };
  }

  /**
   * Defines the HTTP authorization policy, authentication entry points, and post-sign-on routing
   * that together reproduce the legacy CICS/RACF online security behaviour.
   *
   * <p><strong>Authorization.</strong> Public entry points &mdash; the root, the sign-on screen
   * ({@code /login}), the sign-on submit ({@code POST /signon}), the sign-off endpoint, the
   * framework error page, and static assets &mdash; are open to everyone so the sign-on screen can
   * render and the credentials can be submitted before any authentication exists. {@code POST
   * /signon} is permitted as an explicit method-specific matcher: the migrated {@code COSGN00C}
   * sign-on is handled by the web/service layer, which must run for an unauthenticated caller. The
   * {@code /admin/**} space requires the {@link #ROLE_ADMIN} role (the admin menu {@code COADM01C},
   * transaction {@code CA00}). The user-management screens ({@code COUSR00C}&ndash;{@code
   * COUSR03C}, {@code CU00}&ndash;{@code CU03}) are admin-gated by explicit matchers on {@code
   * /user-list}, {@code /user-add}, {@code /user-update}, and {@code /user-delete}; because each
   * screen's GET display and POST submit share its path, these method-agnostic matchers gate both,
   * so standard users can never reach them. Every other request requires authentication.
   *
   * <p><strong>Form login.</strong> A custom login page at {@code /login} is used, and a
   * {@linkplain #roleBasedSuccessHandler() role-based success handler} reproduces the legacy
   * post-sign-on transfer: administrators land on {@link #ADMIN_LANDING_PATH} (the {@code COADM01C}
   * equivalent) and standard users on {@link #USER_LANDING_PATH} (the {@code COMEN01C} equivalent).
   * The {@code /login} view and the {@code /admin} / {@code /menu} landing pages are provided by
   * the web and template layers; referencing their paths here does not affect context startup.
   *
   * <p><strong>HTTP Basic.</strong> Basic authentication is enabled so automated integration tests
   * (Testcontainers-backed) can exercise the role-gating rules without driving the HTML form.
   *
   * <p><strong>Logout and CSRF.</strong> Logout is permitted for all. CSRF protection is left at
   * the enabled Spring default (see the class Javadoc) because the Thymeleaf form-login UI injects
   * CSRF tokens automatically.
   *
   * @param http the {@link HttpSecurity} builder supplied by Spring Security
   * @return the configured {@link SecurityFilterChain}
   * @throws Exception if the security filter chain cannot be built
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            registry ->
                registry
                    // Public sign-on SUBMIT (migrated COSGN00C, transaction CC00). The
                    // later SignonController POST /signon must reach the web/service layer
                    // for an unauthenticated caller so SignonService can validate the
                    // credentials; otherwise sign-in is blocked before it ever runs (AAP
                    // §0.6.5). The explicit POST matcher opens only the submit, and CSRF
                    // stays enabled: the Thymeleaf sign-on form injects the token.
                    .requestMatchers(HttpMethod.POST, "/signon")
                    .permitAll()
                    .requestMatchers(
                        "/",
                        "/login",
                        "/logout",
                        "/error",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/webjars/**")
                    .permitAll()
                    // Admin menu (COADM01C, transaction CA00) and any future admin-only space.
                    .requestMatchers("/admin/**")
                    .hasRole(ROLE_ADMIN)
                    // User-management screens (COUSR00C-COUSR03C, transactions CU00-CU03)
                    // are admin-only. The web layer maps them outside /admin/**
                    // (BaseScreenController PROGRAM_TO_URL), and each screen's GET display
                    // and POST submit share its path, so this method-agnostic matcher gates
                    // both current and future routes to ROLE_ADMIN. The services keep
                    // @PreAuthorize("hasRole('ADMIN')") as defense-in-depth (AAP §0.3.4).
                    .requestMatchers("/user-list", "/user-add", "/user-update", "/user-delete")
                    .hasRole(ROLE_ADMIN)
                    .anyRequest()
                    .authenticated())
        .formLogin(
            form -> form.loginPage("/login").successHandler(roleBasedSuccessHandler()).permitAll())
        .httpBasic(Customizer.withDefaults())
        .logout(logout -> logout.permitAll());
    return http.build();
  }

  /**
   * Builds the {@link AuthenticationSuccessHandler} that reproduces the legacy {@code COSGN00C}
   * post-sign-on routing: a successful administrator sign-on is redirected to {@link
   * #ADMIN_LANDING_PATH} (legacy {@code XCTL PROGRAM('COADM01C')}, transaction {@code CA00}), while
   * every other authenticated user is redirected to {@link #USER_LANDING_PATH} (legacy {@code XCTL
   * PROGRAM('COMEN01C')}, transaction {@code CM00}).
   *
   * <p>The handler inspects the authenticated principal's granted authorities for {@link
   * #AUTHORITY_ADMIN}. A {@link DefaultRedirectStrategy} performs the redirect so the configured
   * servlet context path is honoured and the target URL is correctly encoded.
   *
   * @return a role-aware {@link AuthenticationSuccessHandler}
   */
  private AuthenticationSuccessHandler roleBasedSuccessHandler() {
    RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
    return (request, response, authentication) -> {
      boolean admin =
          authentication.getAuthorities().stream()
              .anyMatch(authority -> AUTHORITY_ADMIN.equals(authority.getAuthority()));
      redirectStrategy.sendRedirect(
          request, response, admin ? ADMIN_LANDING_PATH : USER_LANDING_PATH);
    };
  }
}
