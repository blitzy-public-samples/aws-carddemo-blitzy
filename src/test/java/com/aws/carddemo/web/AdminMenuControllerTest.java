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
import com.aws.carddemo.service.online.AdminMenuService;
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
 * for {@link AdminMenuController} &mdash; the modernized web equivalent of the legacy CICS
 * <strong>admin-menu</strong> transaction {@code CA00} ({@code legacy/app/cbl/COADM01C.cbl}).
 * Unlike its standard-user twin {@code MainMenuController} (program {@code COMEN01C}), {@code
 * COADM01C} is admin-only <em>in its entirety</em>: the legacy sign-on program {@code COSGN00C}
 * routes only a user whose {@code CDEMO-USER-TYPE} is {@code CDEMO-USRTYP-ADMIN} ({@code 'A'}) to
 * this menu (Agent Action Plan &sect;0.3.4, &sect;0.6.5; copybook {@code
 * legacy/app/cpy/COCOM01Y.cpy}). This test pins down both halves of that contract: the ADMIN happy
 * path (render + dispatch) and the role-gating denial that proves a standard user can never reach
 * the admin menu (AAP &sect;0.4.1 {@code AdminMenuController <- COADM01C}).
 *
 * <p><strong>Slice boundary.</strong> The {@link AdminMenuService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /admin}, {@code POST /admin-menu}), the resolved view name ({@code admin-menu}), the
 * model attribute names ({@code adminMenuForm} and {@code adminMenuOptions}), and the
 * program-name&rarr;redirect routing inherited from {@link BaseScreenController}. The menu-option
 * business rules (option validation, the whole-screen admin gate, the option-number&rarr;program
 * dispatch, and the "coming soon" message) live in {@link AdminMenuService} and are verified by its
 * own unit test, not here.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF/role-gating posture rather than a relaxed
 * test default. Two consequences drive the test design:
 *
 * <ol>
 *   <li>Importing {@code SecurityConfig} instantiates its {@code
 *       userDetailsService(UserSecurityRepository)} bean, which requires a {@link
 *       UserSecurityRepository} bean that does not exist inside a {@code @WebMvcTest} slice; it is
 *       therefore supplied as a Mockito bean. It is never invoked here because {@link WithMockUser}
 *       supplies the authenticated principal directly.
 *   <li>{@code SecurityConfig} maps {@code /admin/**} to {@code hasRole("ADMIN")}. Because {@code
 *       GET /admin} matches that pattern, a standard {@code ROLE_USER} principal is denied by the
 *       security filter chain ({@code AuthorizationFilter} &rarr; {@code
 *       ExceptionTranslationFilter}) with a clean {@code 403 Forbidden} <em>before</em> the
 *       controller handler ever runs. Only {@code SecurityConfig} is imported (deliberately
 *       <em>not</em> {@code GlobalExceptionHandler}) so the 403 produced by the filter is
 *       deterministic and is never reinterpreted by an application exception handler.
 * </ol>
 *
 * <p>Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (an
 * explicit {@code ROLE_ADMIN} for the happy paths, {@code ROLE_USER} for the denial), GET requests
 * need no CSRF token, and every state-changing POST that must succeed carries one via {@code
 * with(csrf())}.
 */
@WebMvcTest(AdminMenuController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AdminMenuControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COADM01C} business logic. Stubbing its {@code
   * processAdminMenu(...)} return value lets each test isolate a single controller routing branch
   * (dispatch vs. redisplay) without exercising the real admin-menu rules or the service-layer
   * admin gate.
   */
  @MockitoBean private AdminMenuService adminMenuService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /admin} (the first-entry paint, {@code COADM01C MAIN-PARA} L87-90) renders the
   * {@code admin-menu} view for an administrator and places both the form-backing object ({@code
   * adminMenuForm}) and the admin-menu option labels ({@code adminMenuOptions}) on the model.
   *
   * <p>{@code adminMenuForm} is always a fresh, non-null {@code AdminMenuScreen}, and {@code
   * adminMenuOptions} is {@code screen.getOptions()}, which the DTO initializes to an empty (but
   * non-null) list &mdash; so both attributes exist regardless of the mocked service. The mocked
   * {@link AdminMenuService} performs no gate and returns {@code null}, which the GET handler
   * ignores (it always paints).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminGet_rendersAdminMenu() throws Exception {
    mockMvc
        .perform(get("/admin"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin-menu"))
        .andExpect(model().attributeExists("adminMenuForm"))
        .andExpect(model().attributeExists("adminMenuOptions"));
  }

  /**
   * {@code POST /admin-menu} with a valid selection dispatches to the chosen admin program: when
   * the service returns the program name {@code "COUSR00C"} (the list-users screen {@code CU00}),
   * the controller issues the {@code EXEC CICS XCTL} equivalent &mdash; a Spring redirect to that
   * screen's GET endpoint ({@code /user-list}) via {@link
   * BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_listUsers_redirectsToUserList() throws Exception {
    given(adminMenuService.processAdminMenu(any(), any(), any())).willReturn("COUSR00C");

    mockMvc
        .perform(post("/admin-menu").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/user-list"));
  }

  /**
   * {@code POST /admin-menu} redisplays the menu when the service returns {@code null} (invalid
   * option, "coming soon", or an unmapped key, {@code COADM01C} L99-102, L131, L149-153). The
   * controller re-renders the {@code admin-menu} view with the form-backing object instead of
   * redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_serviceReturnsNull_redisplays() throws Exception {
    given(adminMenuService.processAdminMenu(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/admin-menu").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("admin-menu"))
        .andExpect(model().attributeExists("adminMenuForm"));
  }

  /**
   * Proves the canonical admin role-gating parity (AAP &sect;0.3.4): a standard {@code ROLE_USER}
   * principal that requests {@code GET /admin} is denied with {@code 403 Forbidden}. The {@code
   * SecurityConfig} {@code /admin/**} URL rule (which requires {@code hasRole("ADMIN")}) covers the
   * {@code /admin} display path, so the security filter chain rejects the request before the
   * controller handler runs &mdash; the modernized equivalent of the legacy invariant that only an
   * administrator ({@code CDEMO-USRTYP-ADMIN}) ever reaches {@code COADM01C}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "USER")
  void userGet_isForbidden() throws Exception {
    mockMvc.perform(get("/admin")).andExpect(status().isForbidden());
  }
}
