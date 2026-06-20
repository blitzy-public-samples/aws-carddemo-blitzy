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
import com.aws.carddemo.service.online.CardUpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link WebMvcTest} slice test for {@link CardUpdateController} &mdash; the modernized web
 * equivalent of the legacy CICS card-update transaction {@code CCUP} ({@code
 * legacy/app/cbl/COCRDUPC.cbl}, BMS mapset {@code COCRDUP}). It pins the controller's
 * HTTP-to-view/routing contract (Agent Action Plan &sect;0.4.1 {@code CardUpdateController <-
 * COCRDUPC}; &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Why this test renders the view.</strong> A {@code @WebMvcTest} auto-configures
 * Thymeleaf, so {@code mockMvc.perform(...).andExpect(status().isOk())} actually <em>renders</em>
 * the {@code card-update} template through the real view resolver. A render-time binding mismatch
 * &mdash; a {@code th:field}/{@code th:text} selection expression referencing a property absent
 * from {@link com.aws.carddemo.dto.screen.CardUpdateScreen} &mdash; raises a Spring EL / binding
 * exception during rendering and fails the request. This is exactly the regression that motivated
 * the test (the template previously bound friendly names such as {@code cardNumber}/{@code
 * nameOnCard}/{@code activeStatus} that the COBOL-derived DTO does not expose, which exposes {@code
 * cardSid}/{@code crdName}/{@code crdStcd}); the GET and POST redisplay assertions therefore double
 * as a Thymeleaf template-evaluation guard.
 *
 * <p><strong>Slice boundary.</strong> {@link CardUpdateService} (the migrated {@code COCRDUPC}
 * business logic) is replaced with a Mockito bean, so these tests assert only what the controller
 * owns: the {@code GET}/{@code POST /card-update} mappings, the resolved view name, the {@code
 * cardUpdateForm} model attribute, and the program-name&rarr;redirect routing inherited from {@link
 * BaseScreenController}. The confirm-then-save rules live in {@code CardUpdateService} and are
 * covered by its own unit test.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * CSRF and authentication match production. Importing it instantiates {@code
 * userDetailsService(UserSecurityRepository)}, whose repository dependency is supplied as a Mockito
 * bean (never invoked &mdash; {@link WithMockUser} provides the principal). CSRF stays enabled, so
 * GETs need no token and state-changing POSTs that must succeed carry one via {@code with(csrf())}.
 */
@WebMvcTest(CardUpdateController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CardUpdateControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CardUpdateService cardUpdateService;

  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /card-update} (first-entry paint) renders the {@code card-update} view and places
   * the {@code cardUpdateForm} backing object on the model. Rendering the template here verifies
   * every {@code card-update} field binding &mdash; including the split expiry sub-fields &mdash;
   * resolves against {@code CardUpdateScreen}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_rendersCardUpdateAndBindsTemplate() throws Exception {
    mockMvc
        .perform(get("/card-update"))
        .andExpect(status().isOk())
        .andExpect(view().name("card-update"))
        .andExpect(model().attributeExists("cardUpdateForm"));
  }

  /**
   * {@code POST /card-update} with PF3 routes to the next program: the service returns {@code
   * "COMEN01C"} (main menu) and the controller issues the {@code EXEC CICS XCTL} equivalent &mdash;
   * a redirect to {@code /menu} via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsProgram_redirects() throws Exception {
    given(cardUpdateService.processCardUpdate(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/card-update").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /card-update} redisplays the screen when the service returns {@code null} (the
   * validation-error, PF5-confirm-prompt, or success redisplay path). The controller re-renders the
   * {@code card-update} template with the bound form &mdash; a second template-evaluation guard
   * over the POST redisplay path.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplaysAndBindsTemplate() throws Exception {
    given(cardUpdateService.processCardUpdate(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/card-update").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("card-update"))
        .andExpect(model().attributeExists("cardUpdateForm"));
  }

  /**
   * {@link SecurityConfig} keeps CSRF protection enabled: an authenticated {@code POST
   * /card-update} without a token is rejected with {@code 403 Forbidden} before the handler runs.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_withoutCsrf_isForbidden() throws Exception {
    mockMvc.perform(post("/card-update")).andExpect(status().isForbidden());
  }
}
