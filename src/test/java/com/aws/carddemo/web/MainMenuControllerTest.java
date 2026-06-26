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
import com.aws.carddemo.service.online.MainMenuService;
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
 * for {@link MainMenuController} &mdash; the modernized web equivalent of the legacy CICS main-menu
 * transaction {@code CM00} ({@code legacy/app/cbl/COMEN01C.cbl}). Standard (non-admin) users land
 * on this screen after a successful sign-on, so the controller deliberately carries no
 * {@code @PreAuthorize}; this test pins down its HTTP-to-view/routing contract (Agent Action Plan
 * &sect;0.4.1 {@code MainMenuController <- COMEN01C}; &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link MainMenuService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /menu}, {@code POST /main-menu}), the resolved view name, the model attribute names,
 * and the program-name&rarr;redirect routing inherited from {@link BaseScreenController}. The
 * menu-option business rules (option validation, per-option role gating, the option-number&rarr;
 * program dispatch) live in {@link MainMenuService} and are verified by its own unit test, not
 * here. The role-gating message {@code "No access - Admin Only option..."} from {@code COMEN01C} is
 * likewise a service concern (it surfaces as a {@code null} return that redisplays the menu), so it
 * is intentionally out of scope for this controller slice.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard menu user), GET requests need no CSRF token, and every
 * state-changing POST that must succeed carries one via {@code with(csrf())}.
 */
@WebMvcTest(MainMenuController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class MainMenuControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COMEN01C} business logic. Stubbing its {@code
   * processMainMenu(...)} return value lets each test isolate a single controller routing branch
   * (dispatch vs. redisplay) without exercising the real menu rules.
   */
  @MockitoBean private MainMenuService mainMenuService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /menu} (the first-entry paint, {@code COMEN01C MAIN-PARA} L87-90) renders the {@code
   * main-menu} view and places both the form-backing object ({@code mainMenuForm}) and the
   * menu-option labels ({@code menuOptions}) on the model.
   *
   * <p>{@code mainMenuForm} is always a fresh, non-null {@code MainMenuScreen}, and {@code
   * menuOptions} is {@code screen.getOptions()}, which the DTO initializes to an empty (but
   * non-null) list &mdash; so both attributes exist regardless of the mocked service.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_menu_rendersView() throws Exception {
    mockMvc
        .perform(get("/menu"))
        .andExpect(status().isOk())
        .andExpect(view().name("main-menu"))
        .andExpect(model().attributeExists("mainMenuForm"))
        .andExpect(model().attributeExists("menuOptions"));
  }

  /**
   * {@code POST /main-menu} with a valid selection dispatches to the chosen program: when the
   * service returns the program name {@code "COACTVWC"} (account view), the controller issues the
   * {@code EXEC CICS XCTL} equivalent &mdash; a Spring redirect to that screen's GET endpoint
   * ({@code /account-view}) via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_validOption_redirectsToTarget() throws Exception {
    given(mainMenuService.processMainMenu(any(), any(), any())).willReturn("COACTVWC");

    mockMvc
        .perform(post("/main-menu").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/account-view"));
  }

  /**
   * {@code POST /main-menu} with PF3 returns to the sign-on screen: when the service returns {@code
   * "COSGN00C"} (the {@code COMEN01C RETURN-TO-SIGNON-SCREEN} path), the controller redirects to
   * the sign-on GET endpoint ({@code /login}).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_returnsToSignon() throws Exception {
    given(mainMenuService.processMainMenu(any(), any(), any())).willReturn("COSGN00C");

    mockMvc
        .perform(post("/main-menu").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
  }

  /**
   * {@code POST /main-menu} redisplays the menu when the service returns {@code null} (first-entry
   * paint, invalid option, admin-only block, "coming soon", or an unmapped key). The controller
   * re-renders the {@code main-menu} view with the form-backing object instead of redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplaysMenu() throws Exception {
    given(mainMenuService.processMainMenu(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/main-menu").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("main-menu"))
        .andExpect(model().attributeExists("mainMenuForm"));
  }

  /**
   * Documents that {@link SecurityConfig} keeps CSRF protection enabled: an authenticated {@code
   * POST /main-menu} that omits the CSRF token is rejected by the security filter with {@code 403
   * Forbidden} before the controller handler ever runs.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_withoutCsrf_isForbidden() throws Exception {
    mockMvc.perform(post("/main-menu")).andExpect(status().isForbidden());
  }
}
