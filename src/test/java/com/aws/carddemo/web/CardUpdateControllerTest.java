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
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest WebMvcTest} slice test
 * for {@link CardUpdateController} &mdash; the modernized web equivalent of the legacy CICS
 * card-update transaction {@code CCUP} ({@code legacy/app/cbl/COCRDUPC.cbl}). The card-update
 * transaction is reachable by any authenticated operator (it is a card function, not an admin-only
 * user-management screen), so the controller deliberately carries no {@code @PreAuthorize}; this
 * test pins down its HTTP-to-view/routing contract (Agent Action Plan &sect;0.4.1 {@code
 * CardUpdateController <- COCRDUPC}; &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link CardUpdateService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /card-update}, {@code POST /card-update}), the resolved view name ({@code
 * card-update}), the model attribute name ({@code cardUpdateForm}), and the program-name&rarr;
 * redirect routing inherited from {@link BaseScreenController}. The optimistic read&rarr;confirm
 * &rarr;rewrite state machine, the field-level edits, and the PF5 confirm-then-save handshake all
 * live in {@link CardUpdateService} (the migrated {@code COCRDUPC} numbered paragraphs) and are
 * verified by its own unit test, not here. Mocking the {@code @Transactional} service isolates the
 * controller routing/view contract from the transactional update logic.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser}, GET
 * requests need no CSRF token, and every state-changing POST carries one via {@code with(csrf())}.
 */
@WebMvcTest(CardUpdateController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CardUpdateControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COCRDUPC} business logic. Stubbing its {@code
   * processCardUpdate(...)} return value lets each test isolate a single controller routing branch
   * (transfer-out vs. redisplay) without exercising the real confirm-then-save state machine.
   */
  @MockitoBean private CardUpdateService cardUpdateService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /card-update} (the first-entry paint, {@code COCRDUPC} re-set-to-ENTER branch)
   * renders the {@code card-update} view and places the form-backing object ({@code
   * cardUpdateForm}) on the model.
   *
   * <p>The handler always allocates a fresh {@code CardUpdateScreen}, invokes the service with
   * {@code ENTER}, and exposes the screen under {@code cardUpdateForm} regardless of the mocked
   * service's return value &mdash; so both the view name and the model attribute are deterministic.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_rendersView() throws Exception {
    mockMvc
        .perform(get("/card-update"))
        .andExpect(status().isOk())
        .andExpect(view().name("card-update"))
        .andExpect(model().attributeExists("cardUpdateForm"));
  }

  /**
   * {@code POST /card-update} with PF3 transfers back to the main menu: when the service returns
   * the program name {@code "COMEN01C"} (the legacy {@code COCRDUPC} PF03 path that moves {@code
   * LIT-MENUPGM} into {@code CDEMO-TO-PROGRAM} and issues {@code EXEC CICS XCTL}), the controller
   * issues the XCTL equivalent &mdash; a Spring redirect to that screen's GET endpoint ({@code
   * /menu}) via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_redirectsToMenu() throws Exception {
    given(cardUpdateService.processCardUpdate(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/card-update").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /card-update} redisplays the screen when the service returns {@code null} (first
   * paint after edits, a field-level validation error, the PF5 confirm prompt, or the post-commit
   * success message). The controller re-renders the {@code card-update} view with the form-backing
   * object instead of redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(cardUpdateService.processCardUpdate(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/card-update").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("card-update"))
        .andExpect(model().attributeExists("cardUpdateForm"));
  }
}
