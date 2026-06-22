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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.AccountViewService;
import com.aws.carddemo.web.AccountViewController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * End-to-end HTTP authorization test for the {@link SecurityConfig#securityFilterChain} bean,
 * complementing the pure-unit {@link SecurityConfigTest} (which verifies the {@code
 * passwordEncoder} and {@code userDetailsService} beans by reflection/direct invocation). This test
 * drives real requests through the actual Spring Security filter chain so the URL access rules
 * &mdash; the ones a unit test cannot exercise &mdash; are pinned (Agent Action Plan
 * &sect;0.6.5/&sect;0.6.6 role gating and credential flow).
 *
 * <p><strong>How the security decision is read.</strong> The URL-based authorization rules are
 * enforced by the Spring Security {@code AuthorizationFilter} <em>before</em> handler dispatch, so
 * the decision is observable independently of whether a controller is mapped:
 *
 * <ul>
 *   <li><b>Denied, authenticated wrong role</b> &rarr; {@code 403 Forbidden}.
 *   <li><b>Denied, unauthenticated</b> &rarr; {@code 401 Unauthorized} (this configuration
 *       registers an HTTP-Basic entry point alongside form login) or a {@code 3xx} redirect to the
 *       login page.
 *   <li><b>Permitted</b> &rarr; the request passes the filter chain and reaches the dispatcher; the
 *       downstream result is therefore <em>any status other than</em> {@code 401}/{@code 403} and
 *       <em>not</em> a login redirect. When a real handler is mapped this is a clean {@code 200};
 *       when no handler is mapped in this slice it surfaces as a no-handler error &mdash; either
 *       way the request demonstrably cleared security.
 * </ul>
 *
 * The {@link #permittedPastSecurity()} matcher encodes the "permitted" check (status is neither
 * {@code 401}, {@code 403}, nor a {@code 3xx} login redirect), making the gate-allow assertions
 * robust regardless of whether the target route has a controller in this slice. The slice registers
 * one real controller, {@link AccountViewController} (an ordinary authenticated data route fixed in
 * checkpoint 4), so the "authenticated data route is accessible" case asserts a clean {@code 200}.
 *
 * <p><strong>{@code POST /signon}.</strong> The dedicated sign-on controller is introduced in a
 * later checkpoint, so no handler is mapped for {@code /signon} in this build. The
 * security-relevant assertion is nonetheless decisive: with a CSRF token an unauthenticated {@code
 * POST /signon} is <em>permitted</em> by the chain (it clears {@link #permittedPastSecurity()}),
 * proving the {@code permitAll} rule; without a CSRF token it is {@code 403}, proving CSRF
 * protection still applies to the public endpoint.
 *
 * <p>The {@link SecurityConfig} filter chain is imported explicitly; its {@code
 * userDetailsService(UserSecurityRepository)} bean and the registered controller's {@link
 * AccountViewService} are supplied as Mockito beans (the {@link WithMockUser} principal removes any
 * need to invoke the repository).
 */
@WebMvcTest(AccountViewController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class SecurityFilterChainTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AccountViewService accountViewService;

  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * A {@link ResultMatcher} asserting that a request was <em>permitted</em> by the security filter
   * chain: its status is neither {@code 401 Unauthorized} nor {@code 403 Forbidden} (the denial
   * codes) and it was not redirected to the login page ({@code 3xx}). Any other status (a clean
   * {@code 200} when a handler is mapped, or a no-handler error otherwise) means the request
   * cleared every security filter and reached the dispatcher.
   *
   * @return the matcher
   */
  private static ResultMatcher permittedPastSecurity() {
    return result -> {
      int statusCode = result.getResponse().getStatus();
      // Denial codes for this chain: 401 (unauthenticated entry point), 403 (access denied),
      // and 302 (LoginUrlAuthenticationEntryPoint redirect to /login). Any other status proves
      // the request cleared every security filter and reached the dispatcher.
      assertThat(statusCode)
          .as("request should clear security (not a 401/403/302 denial)")
          .isNotIn(401, 403, 302);
    };
  }

  // ---------------------------------------------------------------------------------------------
  // POST /signon : permitAll, but CSRF-protected.
  // ---------------------------------------------------------------------------------------------

  /**
   * An unauthenticated {@code POST /signon} carrying a CSRF token is permitted by the filter chain
   * and reaches the dispatcher (it clears {@link #permittedPastSecurity()}), proving the {@code
   * permitAll} rule for {@code POST /signon} &mdash; it is neither {@code 403}-denied nor
   * redirected to {@code /login}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void postSignon_unauthenticatedWithCsrf_isPermitted() throws Exception {
    mockMvc.perform(post("/signon").with(csrf())).andExpect(permittedPastSecurity());
  }

  /**
   * Even though {@code POST /signon} is {@code permitAll}, CSRF protection remains enabled, so a
   * token-less {@code POST} is rejected with {@code 403 Forbidden} before dispatch.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void postSignon_withoutCsrf_isForbidden() throws Exception {
    mockMvc.perform(post("/signon")).andExpect(status().isForbidden());
  }

  // ---------------------------------------------------------------------------------------------
  // /admin/** : hasRole('ADMIN').
  // ---------------------------------------------------------------------------------------------

  /**
   * The {@code /admin/**} rule denies an authenticated non-admin with {@code 403 Forbidden}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "USER")
  void getAdmin_asUser_isForbidden() throws Exception {
    mockMvc.perform(get("/admin")).andExpect(status().isForbidden());
  }

  /**
   * The {@code /admin/**} rule admits an authenticated admin: the request clears the gate and
   * reaches the dispatcher, proving {@code ROLE_ADMIN} is accepted.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void getAdmin_asAdmin_passesGate() throws Exception {
    mockMvc.perform(get("/admin")).andExpect(permittedPastSecurity());
  }

  // ---------------------------------------------------------------------------------------------
  // User-management routes (/user-list, ...) : hasRole('ADMIN').
  // ---------------------------------------------------------------------------------------------

  /**
   * A user-management route ({@code /user-list}) denies an authenticated non-admin with {@code 403
   * Forbidden}, reproducing the legacy admin-only user-administration gate.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "USER")
  void getUserList_asUser_isForbidden() throws Exception {
    mockMvc.perform(get("/user-list")).andExpect(status().isForbidden());
  }

  /**
   * A user-management route ({@code /user-list}) admits an authenticated admin: the request clears
   * the gate and reaches the dispatcher.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void getUserList_asAdmin_passesGate() throws Exception {
    mockMvc.perform(get("/user-list")).andExpect(permittedPastSecurity());
  }

  // ---------------------------------------------------------------------------------------------
  // anyRequest().authenticated() : ordinary data routes.
  // ---------------------------------------------------------------------------------------------

  /**
   * An unauthenticated request to an ordinary data route is rejected by the authentication entry
   * point ({@code 401 Unauthorized} under this form-login + HTTP-Basic configuration), proving
   * {@code anyRequest().authenticated()} &mdash; the route is not accessible without a principal.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void getDataRoute_unauthenticated_isDenied() throws Exception {
    mockMvc.perform(get("/account-view")).andExpect(status().isUnauthorized());
  }

  /**
   * An authenticated (non-admin) user can reach an ordinary data route: {@code GET /account-view}
   * renders its view with {@code 200 OK}, confirming authenticated data routes are accessible.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void getDataRoute_authenticated_isAccessible() throws Exception {
    mockMvc.perform(get("/account-view")).andExpect(status().isOk());
  }

  // ---------------------------------------------------------------------------------------------
  // HSTS response header (QA Issue 4): emitted on secure (HTTPS / proxied) requests, correctly
  // absent on cleartext HTTP, per RFC 6797.
  // ---------------------------------------------------------------------------------------------

  /**
   * On a secure request (HTTPS, or behind a TLS-terminating proxy that sets {@code
   * X-Forwarded-Proto: https} with {@code server.forward-headers-strategy=framework}) the response
   * carries the HSTS header with the configured one-year max-age and {@code includeSubDomains}. The
   * default hardening headers (e.g. {@code X-Content-Type-Options: nosniff}) remain present,
   * proving the explicit HSTS customizer did not disable the other defaults.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void hsts_isEmitted_onSecureRequest() throws Exception {
    mockMvc
        .perform(get("/account-view").secure(true))
        .andExpect(status().isOk())
        .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")))
        .andExpect(
            header().string("Strict-Transport-Security", containsString("includeSubDomains")))
        // No regression to the QA-verified default headers.
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  /**
   * On a plain cleartext HTTP request the HSTS header is intentionally absent: RFC 6797 §7.2
   * forbids honoring an HSTS policy delivered over non-secure transport, and Spring Security's
   * {@code HstsHeaderWriter} writes the header only when {@code request.isSecure()} is true. This
   * is the exact (correct) behavior QA observed on local HTTP; the fix ensures the header IS
   * emitted over HTTPS (see {@link #hsts_isEmitted_onSecureRequest()}) without forcing it onto
   * cleartext.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void hsts_isAbsent_onCleartextRequest() throws Exception {
    mockMvc
        .perform(get("/account-view"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Strict-Transport-Security"));
  }
}
