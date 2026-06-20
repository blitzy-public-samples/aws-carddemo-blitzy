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
import com.aws.carddemo.service.online.AccountViewService;
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
 * for {@link AccountViewController} &mdash; the modernized web equivalent of the legacy CICS
 * account-view transaction {@code CAVW} ({@code legacy/app/cbl/COACTVWC.cbl}, BMS mapset {@code
 * COACTVW}). Account view is a <strong>read-only</strong> screen reachable by any authenticated
 * user, so the controller deliberately carries no {@code @PreAuthorize}; this test pins down its
 * HTTP-to-view/routing contract (Agent Action Plan &sect;0.4.1 {@code AccountViewController <-
 * COACTVWC}; &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link AccountViewService} collaborator is replaced with
 * a Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /account-view}, {@code POST /account-view}), the resolved view name ({@code
 * account-view}), the model attribute name ({@code accountViewForm}), and the program-name&rarr;
 * redirect routing inherited from {@link BaseScreenController}. The account-view business rules
 * &mdash; the account-filter validation and the keyed card-cross-reference &rarr; account-master
 * &rarr; customer-master read chain, with its {@link java.math.BigDecimal} monetary rendering
 * &mdash; live in {@link AccountViewService} and are verified by its own unit test, not here. The
 * not-found / validation messages painted into {@code errMsg} are likewise a service concern (they
 * surface as a {@code null} return that redisplays the screen), so they are intentionally out of
 * scope for this controller slice.
 *
 * <p><strong>Pseudo-conversational mapping.</strong> The single COBOL {@code 0000-MAIN} transaction
 * (the {@code COACTVWC} paint-vs-process split) is exercised across the two HTTP verbs the {@code
 * account-view} screen submits: {@code GET /account-view} is the first-entry paint (the {@code WHEN
 * CDEMO-PGM-ENTER} branch), and {@code POST /account-view} is the re-entry process (the {@code WHEN
 * CDEMO-PGM-REENTER} branch plus the PF3 exit {@code WHEN CCARD-AID-PFK03}, which the legacy
 * program routes to the main-menu program {@code COMEN01C} via {@code EXEC CICS XCTL}).
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard account-view operator), GET requests need no CSRF token,
 * and every state-changing POST that must succeed carries one via {@code with(csrf())}.
 */
@WebMvcTest(AccountViewController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AccountViewControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COACTVWC} business logic. Stubbing its {@code
   * processAccountView(...)} return value lets each test isolate a single controller routing branch
   * (PF3 exit vs. redisplay) without exercising the real read chain or filter validation.
   */
  @MockitoBean private AccountViewService accountViewService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /account-view} (the first-entry paint, {@code COACTVWC} {@code WHEN
   * CDEMO-PGM-ENTER}, L353-360) renders the {@code account-view} view and places the form-backing
   * object ({@code accountViewForm}) on the model.
   *
   * <p>The handler always binds a fresh, non-null {@code AccountViewScreen} as {@code
   * accountViewForm} before delegating to the service; with the mocked service returning its
   * default ({@code null}), the controller paints the blank filter screen rather than navigating,
   * so both the {@code 200 OK} status and the {@code accountViewForm} attribute are present
   * regardless of the service.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_rendersView() throws Exception {
    mockMvc
        .perform(get("/account-view"))
        .andExpect(status().isOk())
        .andExpect(view().name("account-view"))
        .andExpect(model().attributeExists("accountViewForm"));
  }

  /**
   * {@code POST /account-view} with PF3 exits to the main menu: when the service returns the
   * program name {@code "COMEN01C"} (the {@code COACTVWC WHEN CCARD-AID-PFK03} exit that moves
   * {@code LIT-MENUPGM} to {@code CDEMO-TO-PROGRAM} and issues {@code EXEC CICS XCTL}), the
   * controller issues the modern equivalent &mdash; a Spring redirect to that screen's GET endpoint
   * ({@code /menu}) via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_redirectsToMenu() throws Exception {
    given(accountViewService.processAccountView(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/account-view").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /account-view} redisplays the screen when the service returns {@code null}
   * (first-entry paint, a valid read painted into the detail fields, or a validation / not-found
   * message in {@code errMsg}). The controller re-renders the {@code account-view} view with the
   * form-backing object instead of redirecting (the {@code COACTVWC 1000-SEND-MAP} redisplay path).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(accountViewService.processAccountView(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/account-view").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("account-view"))
        .andExpect(model().attributeExists("accountViewForm"));
  }

  /**
   * Documents that {@link SecurityConfig} keeps CSRF protection enabled: an authenticated {@code
   * POST /account-view} that omits the CSRF token is rejected by the security filter with {@code
   * 403 Forbidden} before the controller handler ever runs.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_withoutCsrf_forbidden() throws Exception {
    mockMvc.perform(post("/account-view")).andExpect(status().isForbidden());
  }
}
