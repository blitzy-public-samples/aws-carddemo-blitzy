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
import com.aws.carddemo.service.online.AccountUpdateService;
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
 * for {@link AccountUpdateController} &mdash; the modernized web equivalent of the legacy CICS
 * account-update transaction {@code CAUP} ({@code legacy/app/cbl/COACTUPC.cbl}, the largest online
 * program in AWS CardDemo). It is reached from the main menu by a standard (non-admin) user, so the
 * controller deliberately carries no {@code @PreAuthorize}; this test pins down its HTTP-to-view/
 * routing contract (Agent Action Plan &sect;0.4.1 {@code AccountUpdateController <- COACTUPC};
 * &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link AccountUpdateService} collaborator is replaced
 * with a Mockito bean, so these tests assert only what the controller owns &mdash; the request
 * mappings ({@code GET /account-update}, {@code POST /account-update}), the resolved view name, the
 * model attribute name ({@code accountUpdateForm}), and the program-name&rarr;redirect routing
 * inherited from {@link BaseScreenController}. The behavioral heavy lifting of {@code COACTUPC}
 * &mdash; the 24-field validation order, the optimistic read-compare-rewrite update, the PF-key
 * state machine, and every user-facing message &mdash; lives in {@link AccountUpdateService} and is
 * verified by its own unit test, not here. In particular the {@code @Transactional}
 * account+customer update (the {@code EXEC CICS SYNCPOINT ROLLBACK} equivalent) and its concurrency
 * check never run in this slice, so there are no database or optimistic-locking concerns; this test
 * guards URL/view/ redirect parity only.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard account-update user), GET requests need no CSRF token, and
 * every state-changing POST that must succeed carries one via {@code with(csrf())}.
 */
@WebMvcTest(AccountUpdateController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AccountUpdateControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COACTUPC} business logic. Stubbing its {@code
   * processAccountUpdate(...)} return value lets each test isolate a single controller routing
   * branch (PF3 transfer vs. redisplay) without exercising the real account-update rules or any
   * persistence.
   */
  @MockitoBean private AccountUpdateService accountUpdateService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /account-update} (the first-entry paint, {@code COACTUPC MAIN-PARA} fresh-entry
   * branch) renders the {@code account-update} view and places the form-backing object ({@code
   * accountUpdateForm}) on the model.
   *
   * <p>The controller always paints a fresh, non-null {@code AccountUpdateScreen} and adds it to
   * the model regardless of what the mocked service returns, so the attribute exists
   * unconditionally.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_rendersView() throws Exception {
    mockMvc
        .perform(get("/account-update"))
        .andExpect(status().isOk())
        .andExpect(view().name("account-update"))
        .andExpect(model().attributeExists("accountUpdateForm"));
  }

  /**
   * {@code POST /account-update} with PF3 returns to the main menu: when the service returns the
   * program name {@code "COMEN01C"} (the {@code COACTUPC} {@code WHEN CCARD-AID-PFK03} branch that
   * {@code EXEC CICS XCTL}s back to the calling menu program), the controller issues the XCTL
   * equivalent &mdash; a Spring redirect to that screen's GET endpoint ({@code /menu}) via {@link
   * BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_redirectsToMenu() throws Exception {
    given(accountUpdateService.processAccountUpdate(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/account-update").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /account-update} redisplays the screen when the service returns {@code null}
   * (first-entry paint, validation errors, the "Changes validated.Press F5 to save" confirmation
   * prompt, the post-update success info message, or a concurrency-conflict message). The
   * controller re-renders the {@code account-update} view with the form-backing object instead of
   * redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(accountUpdateService.processAccountUpdate(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/account-update").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("account-update"))
        .andExpect(model().attributeExists("accountUpdateForm"));
  }

  /**
   * Documents that {@link SecurityConfig} keeps CSRF protection enabled: an authenticated {@code
   * POST /account-update} that omits the CSRF token is rejected by the security filter with {@code
   * 403 Forbidden} before the controller handler ever runs.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_withoutCsrf_forbidden() throws Exception {
    mockMvc.perform(post("/account-update")).andExpect(status().isForbidden());
  }
}
