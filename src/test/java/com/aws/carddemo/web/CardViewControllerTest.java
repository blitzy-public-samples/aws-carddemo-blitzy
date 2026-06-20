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
import com.aws.carddemo.service.online.CardViewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link WebMvcTest} slice test for {@link CardViewController} &mdash; the modernized web
 * equivalent of the legacy CICS card-view (display a single card) transaction {@code CCDL} ({@code
 * legacy/app/cbl/COCRDSLC.cbl}, BMS mapset {@code COCRDSL}). It pins the controller's
 * HTTP-to-view/routing contract (Agent Action Plan &sect;0.4.1 {@code CardViewController <-
 * COCRDSLC}; &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Why this test renders the view.</strong> A {@code @WebMvcTest} auto-configures
 * Thymeleaf, so {@code mockMvc.perform(...).andExpect(status().isOk())} actually <em>renders</em>
 * the {@code card-view} template through the real view resolver. A render-time binding mismatch
 * &mdash; a {@code th:field}/{@code th:text} selection expression referencing a property absent
 * from the bound screen DTO &mdash; raises a Spring EL / binding exception during rendering and
 * fails the request, so the GET and POST redisplay assertions double as a Thymeleaf
 * template-evaluation guard (the regression that motivated the full CP4 controller-test set).
 *
 * <p><strong>Slice boundary.</strong> {@link CardViewService} (the migrated {@code COCRDSLC}
 * business logic) is replaced with a Mockito bean, so these tests assert only what the controller
 * owns: the {@code GET}/{@code POST /card-view} mappings, the resolved view name, the {@code
 * cardViewForm} model attribute, and the program-name&rarr;redirect routing inherited from {@link
 * BaseScreenController}. The read/list/validate rules live in {@code CardViewService} and are
 * covered by its own unit test.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * CSRF and authentication match production. Importing it instantiates {@code
 * userDetailsService(UserSecurityRepository)}, whose repository dependency is supplied as a Mockito
 * bean (never invoked &mdash; {@link WithMockUser} provides the principal). CSRF stays enabled, so
 * GETs need no token and state-changing POSTs that must succeed carry one via {@code with(csrf())}.
 */
@WebMvcTest(CardViewController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CardViewControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CardViewService cardViewService;

  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /card-view} (first-entry paint) renders the {@code card-view} view and places the
   * {@code cardViewForm} backing object on the model. Rendering the template here verifies every
   * {@code card-view} field binding resolves against its screen DTO.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_rendersViewAndBindsTemplate() throws Exception {
    mockMvc
        .perform(get("/card-view"))
        .andExpect(status().isOk())
        .andExpect(view().name("card-view"))
        .andExpect(model().attributeExists("cardViewForm"));
  }

  /**
   * {@code POST /card-view} with PF3 routes to the next program: the service returns {@code
   * "COMEN01C"} (main menu) and the controller issues the {@code EXEC CICS XCTL} equivalent &mdash;
   * a redirect to {@code /menu} via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsProgram_redirects() throws Exception {
    given(cardViewService.processCardView(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/card-view").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /card-view} redisplays the screen when the service returns {@code null} (paging,
   * validation-error, invalid-selection, or informational redisplay path). The controller
   * re-renders the {@code card-view} template with the bound form &mdash; a second
   * template-evaluation guard over the POST redisplay path.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplaysAndBindsTemplate() throws Exception {
    given(cardViewService.processCardView(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/card-view").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("card-view"))
        .andExpect(model().attributeExists("cardViewForm"));
  }

  /**
   * {@link SecurityConfig} keeps CSRF protection enabled: an authenticated {@code POST /card-view}
   * without a token is rejected with {@code 403 Forbidden} before the handler runs.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_withoutCsrf_isForbidden() throws Exception {
    mockMvc.perform(post("/card-view")).andExpect(status().isForbidden());
  }
}
