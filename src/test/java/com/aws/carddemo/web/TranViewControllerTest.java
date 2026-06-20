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

import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
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
import com.aws.carddemo.service.online.TranViewService;
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
 * for {@link TranViewController} &mdash; the modernized web equivalent of the legacy CICS
 * transaction-view transaction {@code CT01} ({@code legacy/app/cbl/COTRN01C.cbl}, BMS mapset {@code
 * COTRN01}). This is a read-only, single-transaction detail screen reachable by standard
 * (non-admin) users, so the controller deliberately carries no {@code @PreAuthorize}; this test
 * pins down its HTTP-to-view/routing contract (Agent Action Plan &sect;0.4.1 {@code
 * TranViewController <- COTRN01C}; &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Authoritative parity assertion &mdash; the {@code selectedTranId} flash
 * handoff.</strong> This test is the positive, end-to-end CONSUME side of the list&rarr;view
 * drill-down that {@code COTRN00C} (the transaction-list program) initiates by moving the
 * operator's chosen {@code TRNIDxxI} row id into {@code CDEMO-CT01-TRN-SELECTED} of the COMMAREA
 * before {@code EXEC CICS XCTL PROGRAM('COTRN01C')} ({@code COTRN01C} L103-108). In the modernized
 * application that selected id travels as a Spring MVC flash attribute named {@code
 * selectedTranId}, set by the list controller on its {@code redirect:/transaction-view}. Spring MVC
 * merges inbound flash attributes into the GET handler's {@code Model} <em>before</em> the handler
 * runs, so {@code TranViewController.showTranView} observes {@code
 * model.containsAttribute("selectedTranId")} as {@code true} and copies the value into {@link
 * com.aws.carddemo.dto.screen.TranViewScreen#setTrnIdIn(String) trnIdIn}. The flash test below
 * drives that exact path with {@code MockMvc}'s {@code .flashAttr(...)} and asserts the resulting
 * model form via Hamcrest {@code hasProperty("trnIdIn", is(...))} &mdash; a robust assertion that
 * relies only on the documented {@code getTrnIdIn()} getter and is insensitive to service
 * internals.
 *
 * <p><strong>Slice boundary.</strong> The {@link TranViewService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * (both {@code GET} and {@code POST} bound to {@code /transaction-view}), the resolved view name
 * ({@code transaction-view}), the model attribute name ({@code transactionViewForm}), the flash
 * consume into {@code trnIdIn}, and the program-name&rarr;redirect routing inherited from {@link
 * BaseScreenController}. The transaction lookup, not-found / lookup-error messages, and PF-key
 * evaluation business rules live in {@link TranViewService} and are verified by its own unit test,
 * not here.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard transaction-view user), GET requests need no CSRF token,
 * and every state-changing POST carries one via {@code with(csrf())}.
 */
@WebMvcTest(TranViewController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class TranViewControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COTRN01C} business logic. Stubbing its {@code
   * processTranView(...)} return value lets each POST test isolate a single controller routing
   * branch (XCTL redirect vs. screen redisplay) without exercising the real lookup rules; in the
   * GET tests it is left as a default no-op mock so the handler's flash-consume and model wiring
   * are observed in isolation.
   */
  @MockitoBean private TranViewService tranViewService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /transaction-view} consumes the {@code selectedTranId} flash handoff: a flash
   * attribute set by the transaction-list controller on its {@code redirect:/transaction-view}
   * survives the redirect, is merged into the GET handler's {@code Model} before it runs, and the
   * controller copies it into the form's {@code trnIdIn} property &mdash; the web equivalent of
   * {@code COTRN00C} populating {@code CDEMO-CT01-TRN-SELECTED} which {@code COTRN01C}
   * auto-displays on first entry ({@code COTRN01C} L103-108, AAP &sect;0.6.5).
   *
   * <p>The assertion uses Hamcrest {@code hasProperty("trnIdIn", is(...))} against the {@code
   * transactionViewForm} model attribute, exercising only the documented {@code getTrnIdIn()}
   * getter so it stays robust against the mocked service's internals.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_consumesSelectedTranIdFlash() throws Exception {
    mockMvc
        .perform(get("/transaction-view").flashAttr("selectedTranId", "0000000000000123"))
        .andExpect(status().isOk())
        .andExpect(view().name("transaction-view"))
        .andExpect(
            model()
                .attribute("transactionViewForm", hasProperty("trnIdIn", is("0000000000000123"))));
  }

  /**
   * {@code GET /transaction-view} with no inbound flash (the operator navigated here directly,
   * rather than by drilling down from the transaction list) paints a fresh search form: the {@code
   * transaction-view} view is rendered with a non-null {@code transactionViewForm} model attribute
   * and no pre-filled selection.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_withoutFlash_rendersView() throws Exception {
    mockMvc
        .perform(get("/transaction-view"))
        .andExpect(status().isOk())
        .andExpect(view().name("transaction-view"))
        .andExpect(model().attributeExists("transactionViewForm"));
  }

  /**
   * {@code POST /transaction-view} with PF5 returns to the transaction list: when the service
   * returns the program name {@code "COTRN00C"} (the {@code COTRN01C WHEN DFHPF5} branch,
   * L125-126), the controller issues the {@code EXEC CICS XCTL} equivalent &mdash; a Spring
   * redirect to that screen's GET endpoint ({@code /transaction-list}) via {@link
   * BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf5_redirectsToList() throws Exception {
    given(tranViewService.processTranView(any(), any(), any())).willReturn("COTRN00C");

    mockMvc
        .perform(post("/transaction-view").param("pfKey", "PF5").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/transaction-list"));
  }

  /**
   * {@code POST /transaction-view} redisplays the screen when the service returns {@code null} (the
   * ENTER lookup result &mdash; populated detail or a not-found / lookup-error message, the PF4
   * clear, or an invalid key). The controller re-renders the {@code transaction-view} view with the
   * form-backing object instead of redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(tranViewService.processTranView(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/transaction-view").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("transaction-view"))
        .andExpect(model().attributeExists("transactionViewForm"));
  }
}
