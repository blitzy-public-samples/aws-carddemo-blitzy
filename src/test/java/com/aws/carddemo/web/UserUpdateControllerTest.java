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
import com.aws.carddemo.service.online.UserUpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link WebMvcTest} slice test for {@link UserUpdateController} &mdash; the modernized web
 * equivalent of the legacy admin-only CICS user-update transaction {@code CU02} ({@code
 * legacy/app/cbl/COUSR02C.cbl}, BMS mapset {@code COUSR02}). It pins the controller's
 * HTTP-to-view/routing contract and its admin role gate (Agent Action Plan &sect;0.4.1 {@code
 * UserUpdateController <- COUSR02C}; &sect;0.6.5 COMMAREA navigation; &sect;0.3.4 role gating).
 *
 * <p><strong>Why this test renders the view.</strong> A {@code @WebMvcTest} auto-configures
 * Thymeleaf, so {@code mockMvc.perform(...).andExpect(status().isOk())} actually <em>renders</em>
 * the {@code user-update} template through the real view resolver. A render-time binding mismatch
 * &mdash; a {@code th:field}/{@code th:text} selection expression referencing a property absent
 * from {@link com.aws.carddemo.dto.screen.UserUpdateScreen} &mdash; raises a Spring EL / binding
 * exception during rendering and fails the request. This is exactly the regression that motivated
 * the test (the template previously bound friendly names such as {@code userId}/{@code
 * firstName}/{@code password}/{@code userType} that the COBOL-derived DTO does not expose, which
 * exposes {@code usrIdIn}/{@code fName}/{@code passwd}/{@code usrType} &mdash; note the explicit
 * {@code password} &rarr; {@code passwd} mismatch); the GET and POST redisplay assertions therefore
 * double as a Thymeleaf template-evaluation guard.
 *
 * <p><strong>Slice boundary.</strong> {@link UserUpdateService} (the migrated {@code COUSR02C}
 * business logic) is replaced with a Mockito bean, so these tests assert only what the controller
 * owns: the {@code GET}/{@code POST /user-update} mappings, the resolved view name, the {@code
 * userUpdateForm} model attribute, the {@code hasRole('ADMIN')} gate, and the
 * program-name&rarr;redirect routing inherited from {@link BaseScreenController}. The
 * lookup/validate/update rules live in {@code UserUpdateService} and are covered by its own unit
 * test.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * CSRF, authentication, and the {@code /user-update} admin authorization rule match production.
 * Importing it instantiates {@code userDetailsService(UserSecurityRepository)}, whose repository
 * dependency is supplied as a Mockito bean (never invoked &mdash; {@link WithMockUser} provides the
 * principal and authorities). CSRF stays enabled, so GETs need no token and state-changing POSTs
 * that must succeed carry one via {@code with(csrf())}.
 */
@WebMvcTest(UserUpdateController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class UserUpdateControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private UserUpdateService userUpdateService;

  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /user-update} (first-entry paint) renders the {@code user-update} view for an admin
   * and places the {@code userUpdateForm} backing object on the model. Rendering the template here
   * verifies every {@code user-update} field binding &mdash; including the {@code passwd} field
   * that previously mismatched {@code password} &mdash; resolves against {@code UserUpdateScreen}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void get_asAdmin_rendersUserUpdateAndBindsTemplate() throws Exception {
    mockMvc
        .perform(get("/user-update"))
        .andExpect(status().isOk())
        .andExpect(view().name("user-update"))
        .andExpect(model().attributeExists("userUpdateForm"));
  }

  /**
   * {@code POST /user-update} with PF3 routes to the next program: the service returns {@code
   * "COMEN01C"} and the controller issues the {@code EXEC CICS XCTL} equivalent &mdash; a redirect
   * to {@code /menu} via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void post_asAdmin_serviceReturnsProgram_redirects() throws Exception {
    given(userUpdateService.processUserUpdate(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/user-update").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /user-update} redisplays the screen when the service returns {@code null} (the
   * ENTER lookup, validation-error, PF5 update confirmation/no-change, or PF4 cleared-form
   * redisplay path). The controller re-renders the {@code user-update} template with the bound form
   * &mdash; a second template-evaluation guard over the POST redisplay path.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void post_asAdmin_serviceReturnsNull_redisplaysAndBindsTemplate() throws Exception {
    given(userUpdateService.processUserUpdate(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/user-update").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("user-update"))
        .andExpect(model().attributeExists("userUpdateForm"));
  }

  /**
   * The {@code /user-update} screen is admin-only: an authenticated non-admin (role {@code USER})
   * is denied with {@code 403 Forbidden}, reproducing the legacy {@code CDEMO-USRTYP-ADMIN} gate
   * (Agent Action Plan &sect;0.3.4).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "USER")
  void get_asNonAdmin_isForbidden() throws Exception {
    mockMvc.perform(get("/user-update")).andExpect(status().isForbidden());
  }

  /**
   * {@link SecurityConfig} keeps CSRF protection enabled: an authenticated admin {@code POST
   * /user-update} without a token is rejected with {@code 403 Forbidden} before the handler runs.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void post_withoutCsrf_isForbidden() throws Exception {
    mockMvc.perform(post("/user-update")).andExpect(status().isForbidden());
  }
}
