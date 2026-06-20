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
import com.aws.carddemo.service.online.UserDeleteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link WebMvcTest} slice test for {@link UserDeleteController} &mdash; the modernized web
 * equivalent of the legacy CICS admin delete-user confirm flow transaction {@code CU03} ({@code
 * legacy/app/cbl/COUSR03C.cbl}, BMS mapset {@code COUSR03}). It pins the controller's
 * HTTP-to-view/routing contract (Agent Action Plan &sect;0.4.1 {@code UserDeleteController <-
 * COUSR03C}; &sect;0.6.5 COMMAREA navigation; &sect;0.3.4 role gating).
 *
 * <p><strong>Why this test renders the view.</strong> A {@code @WebMvcTest} auto-configures
 * Thymeleaf, so {@code mockMvc.perform(...).andExpect(status().isOk())} actually <em>renders</em>
 * the {@code user-delete} template through the real view resolver. A render-time binding mismatch
 * &mdash; a {@code th:field}/{@code th:text} selection expression referencing a property absent
 * from the bound screen DTO &mdash; raises a Spring EL / binding exception during rendering and
 * fails the request, so the GET and POST redisplay assertions double as a Thymeleaf
 * template-evaluation guard (the regression that motivated the full CP4 controller-test set).
 *
 * <p><strong>Slice boundary.</strong> {@link UserDeleteService} (the migrated {@code COUSR03C}
 * business logic) is replaced with a Mockito bean, so these tests assert only what the controller
 * owns: the {@code GET}/{@code POST /user-delete} mappings, the resolved view name, the {@code
 * userDeleteForm} model attribute, the {@code hasRole('ADMIN')} gate, and the
 * program-name&rarr;redirect routing inherited from {@link BaseScreenController}. The
 * read/list/validate rules live in {@code UserDeleteService} and are covered by its own unit test.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * CSRF and authentication match production. Importing it instantiates {@code
 * userDetailsService(UserSecurityRepository)}, whose repository dependency is supplied as a Mockito
 * bean (never invoked &mdash; {@link WithMockUser} provides the principal). CSRF stays enabled, so
 * GETs need no token and state-changing POSTs that must succeed carry one via {@code with(csrf())}.
 */
@WebMvcTest(UserDeleteController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class UserDeleteControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private UserDeleteService userDeleteService;

  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /user-delete} (first-entry paint) renders the {@code user-delete} view and places
   * the {@code userDeleteForm} backing object on the model. Rendering the template here verifies
   * every {@code user-delete} field binding resolves against its screen DTO.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void get_rendersViewAndBindsTemplate() throws Exception {
    mockMvc
        .perform(get("/user-delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("user-delete"))
        .andExpect(model().attributeExists("userDeleteForm"));
  }

  /**
   * {@code POST /user-delete} with PF3 routes to the next program: the service returns {@code
   * "COMEN01C"} (main menu) and the controller issues the {@code EXEC CICS XCTL} equivalent &mdash;
   * a redirect to {@code /menu} via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void post_serviceReturnsProgram_redirects() throws Exception {
    given(userDeleteService.processUserDelete(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/user-delete").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /user-delete} redisplays the screen when the service returns {@code null} (paging,
   * validation-error, invalid-selection, or informational redisplay path). The controller
   * re-renders the {@code user-delete} template with the bound form &mdash; a second
   * template-evaluation guard over the POST redisplay path.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void post_serviceReturnsNull_redisplaysAndBindsTemplate() throws Exception {
    given(userDeleteService.processUserDelete(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/user-delete").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("user-delete"))
        .andExpect(model().attributeExists("userDeleteForm"));
  }

  /**
   * The {@code /user-delete} screen is admin-only: an authenticated non-admin (role {@code USER})
   * is denied with {@code 403 Forbidden}, reproducing the legacy {@code CDEMO-USRTYP-ADMIN} gate
   * (Agent Action Plan &sect;0.3.4).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "USER")
  void get_asNonAdmin_isForbidden() throws Exception {
    mockMvc.perform(get("/user-delete")).andExpect(status().isForbidden());
  }

  /**
   * {@link SecurityConfig} keeps CSRF protection enabled: an authenticated {@code POST
   * /user-delete} without a token is rejected with {@code 403 Forbidden} before the handler runs.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void post_withoutCsrf_isForbidden() throws Exception {
    mockMvc.perform(post("/user-delete")).andExpect(status().isForbidden());
  }
}
