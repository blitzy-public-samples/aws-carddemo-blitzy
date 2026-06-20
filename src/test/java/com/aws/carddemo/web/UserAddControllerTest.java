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
package com.aws.carddemo.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.UserAddService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest WebMvcTest} slice test
 * for {@link UserAddController} &mdash; the modernized web equivalent of the legacy CICS add-user
 * transaction {@code CU01} ({@code legacy/app/cbl/COUSR01C.cbl}, BMS mapset {@code COUSR01}).
 * Adding a user is an <strong>administrator-only</strong> function: in the legacy application
 * {@code COUSR01C} is reachable only from the admin menu {@code COADM01C}. This test pins down the
 * controller's HTTP-to-view/routing contract <em>and</em> its admin-only access posture (Agent
 * Action Plan &sect;0.3.4 user management {@code CU00}&ndash;{@code CU03} admin-only; &sect;0.4.1
 * {@code UserAddController <- COUSR01C}; &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link UserAddService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /user-add}, {@code POST /user-add}), the resolved view name ({@code user-add}), the
 * model attribute name ({@code userAddForm}), and the program-name&rarr;redirect routing inherited
 * from {@link BaseScreenController}. The add-user business rules (five-field validation in COBOL
 * order, the duplicate-user-id pre-check, and the BCrypt-hashed insert) live in {@link
 * UserAddService} and are verified by its own unit test, not here.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF/authorization posture rather than a relaxed
 * test default. Admin-gating for {@code /user-add} is enforced by {@code SecurityConfig} at two
 * independent layers that both demand {@code ROLE_ADMIN}: the URL-authorization matcher {@code
 * requestMatchers("/user-list", "/user-add", "/user-update", "/user-delete").hasRole("ADMIN")} in
 * the security filter chain, and the controller's class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} method-security gate (enabled by
 * {@code @EnableMethodSecurity}). A standard user is therefore denied with a clean {@code 403
 * Forbidden} produced by Spring Security's {@code ExceptionTranslationFilter}. Only {@code
 * SecurityConfig} is imported here &mdash; the application's {@code GlobalExceptionHandler}
 * ({@code @ControllerAdvice}) is deliberately <em>not</em> imported, so the security-layer denial
 * is surfaced as the framework {@code 403} rather than being re-mapped by an exception handler.
 *
 * <p>Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. It is never invoked because {@link WithMockUser} supplies the authenticated
 * principal directly. Because {@code SecurityConfig} leaves CSRF protection enabled, every
 * state-changing {@code POST} that must reach the handler carries a token via {@code with(csrf())}.
 *
 * <p><strong>Credential hygiene (AAP &sect;0.6.6).</strong> The {@code user-add} screen carries a
 * sensitive password field, which in production is one-way BCrypt-hashed by {@link UserAddService}.
 * Honoring the no-hardcoded-credentials rule (AAP &sect;0.7.2), this slice never submits a
 * plaintext password value: the routing branches under test (PF3 return and the {@code null}-return
 * redisplay) are exercised purely through the mocked service's return value, with no credential
 * input.
 */
@WebMvcTest(UserAddController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class UserAddControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COUSR01C} business logic. Stubbing its {@code
   * processUserAdd(...)} return value lets each test isolate a single controller routing branch
   * (PF3 dispatch vs. redisplay) without exercising the real add-user validation or persistence.
   */
  @MockitoBean private UserAddService userAddService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /user-add} for an administrator (the first-entry paint, {@code COUSR01C MAIN-PARA}
   * L84-89) renders the {@code user-add} view and places the form-backing object ({@code
   * userAddForm}) on the model. The controller always allocates a fresh, non-null {@code
   * UserAddScreen} before delegating to the service, so the attribute exists regardless of the
   * mocked service's behavior.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminGet_rendersUserAdd() throws Exception {
    mockMvc
        .perform(get("/user-add"))
        .andExpect(status().isOk())
        .andExpect(view().name("user-add"))
        .andExpect(model().attributeExists("userAddForm"));
  }

  /**
   * {@code POST /user-add} with PF3 returns to the admin menu: PF3 is the COBOL {@code WHEN DFHPF3}
   * branch that moves {@code 'COADM01C'} to {@code CDEMO-TO-PROGRAM} and performs the {@code EXEC
   * CICS XCTL} transfer ({@code COUSR01C} L93-95). When the service returns the program name {@code
   * "COADM01C"}, the controller issues the XCTL equivalent &mdash; a Spring redirect to the admin
   * menu's GET endpoint ({@code /admin}) via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_pf3_redirectsToAdminMenu() throws Exception {
    given(userAddService.processUserAdd(any(), any(), any())).willReturn("COADM01C");

    mockMvc
        .perform(post("/user-add").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin"));
  }

  /**
   * {@code POST /user-add} redisplays the screen when the service returns {@code null} (the
   * re-entry paths that stay on the add-user map: a field-validation failure, the duplicate-user-id
   * error, the PF4 cleared form, an unmapped key, or the green success confirmation). The
   * controller re-renders the {@code user-add} view with the form-backing object instead of
   * redirecting. No {@code pfKey} is supplied, so the inherited AID resolution defaults to ENTER,
   * matching a plain submit; and no password value is sent (AAP &sect;0.6.6 credential hygiene).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_serviceReturnsNull_redisplays() throws Exception {
    given(userAddService.processUserAdd(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/user-add").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("user-add"))
        .andExpect(model().attributeExists("userAddForm"));
  }

  /**
   * A standard (non-admin) user is denied access to the admin-only add-user screen. {@code GET
   * /user-add} authenticated as {@code ROLE_USER} is rejected with {@code 403 Forbidden}: {@link
   * SecurityConfig} gates {@code /user-add} to {@code ROLE_ADMIN} (both via the URL authorization
   * matcher and the controller's class-level {@code @PreAuthorize("hasRole('ADMIN')")}), so the
   * request never reaches the handler and Spring Security's {@code ExceptionTranslationFilter}
   * returns a clean {@code 403}. This reproduces the legacy gate by which {@code COUSR01C} ({@code
   * CU01}) is reachable only from the administrator menu (AAP &sect;0.3.4, &sect;0.6.5).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "USER")
  void userGet_isForbidden() throws Exception {
    mockMvc.perform(get("/user-add")).andExpect(status().isForbidden());
  }
}
