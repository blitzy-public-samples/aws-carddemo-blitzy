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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.TranListService;
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
 * for {@link TranListController} &mdash; the modernized web equivalent of the legacy CICS
 * transaction-list transaction {@code CT00} ({@code legacy/app/cbl/COTRN00C.cbl}). This is a
 * standard (non-admin) paged-browse screen reached from the main menu, so the controller carries no
 * {@code @PreAuthorize}; this test pins down its HTTP-to-view / redirect routing contract and the
 * controller-mediated selection handoff (Agent Action Plan &sect;0.4.1 {@code TranListController <-
 * COTRN00C}; &sect;0.6.5 pseudo-conversational state &amp; navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link TranListService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns: the request mappings ({@code
 * GET}/{@code POST /transaction-list}), the resolved view name ({@code transaction-list}), the
 * model attribute name ({@code transactionListForm}), the program-name&rarr;redirect routing
 * inherited from {@link BaseScreenController}, and the {@code selectedTranId} flash handoff. The
 * keyset paging, the ten-row page build, and the row-selection scan live in {@link TranListService}
 * and are verified by its own unit test, not here.
 *
 * <p><strong>Selection-handoff contract (KEY INSIGHT).</strong> When a row is selected with {@code
 * S} the service returns the transaction-view program {@code "COTRN01C"} and the controller
 * re-derives the chosen transaction id from the bound rows, carrying it to the transaction-view
 * screen as the {@code selectedTranId} <em>flash attribute</em>. This test asserts the
 * <em>produce</em> side's defensive branch: a request that binds <em>no</em> rows leaves {@code
 * firstSelectedTranId(screen)} returning {@code null}, so <em>no</em> flash attribute is added even
 * though the redirect to {@code /transaction-view} still occurs. This is exactly the legacy {@code
 * COTRN00C PROCESS-ENTER-KEY} behaviour when ENTER is pressed with no row marked: the {@code WHEN
 * OTHER} branch (COTRN00C L179-181) clears {@code CDEMO-CT00-TRN-SELECTED}, so no id is propagated.
 * Asserting this branch lets the test exercise the routing without constructing the nested {@code
 * TranListScreen.TranListRow} type (owned by the DTO layer); the positive selection (the flash
 * <em>is</em> set) is verified end-to-end on the <em>consume</em> side in {@code
 * TranViewControllerTest} via {@code flashAttr(...)}.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard list user), the GET request needs no CSRF token, and every
 * state-changing POST carries one via {@code with(csrf())}.
 */
@WebMvcTest(TranListController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class TranListControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COTRN00C} business logic. Stubbing its {@code
   * processTranList(...)} return value lets each test isolate a single controller routing branch
   * (selection forward, PF3 return, or redisplay) without exercising the real browse/selection
   * rules.
   */
  @MockitoBean private TranListService tranListService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /transaction-list} (the first-entry paint, {@code COTRN00C MAIN-PARA} L112-116)
   * renders the {@code transaction-list} view and places the form-backing object ({@code
   * transactionListForm}) on the model. The screen is a fresh, non-null {@code TranListScreen}
   * regardless of the mocked service (its {@code processTranList} returns {@code null} by default),
   * so the attribute always exists.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_rendersView() throws Exception {
    mockMvc
        .perform(get("/transaction-list"))
        .andExpect(status().isOk())
        .andExpect(view().name("transaction-list"))
        .andExpect(model().attributeExists("transactionListForm"));
  }

  /**
   * {@code POST /transaction-list} forwards to the transaction-view screen when the service returns
   * {@code "COTRN01C"} (a row was selected with {@code S}), but adds <em>no</em> flash attribute
   * when the bound screen carries no selected rows.
   *
   * <p>With no {@code rows[...]} request parameters, the bound {@code TranListScreen} has an empty
   * rows list, so {@code firstSelectedTranId(screen)} returns {@code null} and the {@code
   * selectedTranId} flash attribute is never set &mdash; mirroring the legacy {@code WHEN OTHER}
   * branch that clears {@code CDEMO-CT00-TRN-SELECTED} (COTRN00C L179-181). The controller still
   * issues the {@code EXEC CICS XCTL PROGRAM('COTRN01C')} equivalent &mdash; a Spring redirect to
   * {@code /transaction-view} via {@link BaseScreenController#redirectFor(String)}. The flash map
   * is therefore asserted empty.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_selectionWithoutRows_redirectsToViewWithoutFlash() throws Exception {
    given(tranListService.processTranList(any(), any(), any())).willReturn("COTRN01C");

    mockMvc
        .perform(post("/transaction-list").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/transaction-view"))
        .andExpect(flash().attributeCount(0));
  }

  /**
   * {@code POST /transaction-list} with PF3 returns to the main menu: when the service returns
   * {@code "COMEN01C"} (the {@code COTRN00C} PF3 / {@code RETURN-TO-PREV-SCREEN} path, L122-124),
   * the controller redirects to the main-menu GET endpoint ({@code /menu}). Because the returned
   * program is not the transaction-view target, the selection-handoff branch is skipped and no
   * flash attribute is added.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_redirectsToMenu() throws Exception {
    given(tranListService.processTranList(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/transaction-list").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"))
        .andExpect(flash().attributeCount(0));
  }

  /**
   * {@code POST /transaction-list} redisplays the list when the service returns {@code null}
   * (PF7/PF8 paging, an invalid selection, a non-numeric filter, or an unmapped key). The
   * controller re-renders the {@code transaction-list} view with the form-backing object instead of
   * redirecting, so the screen can carry any error/informational message line.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(tranListService.processTranList(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/transaction-list").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("transaction-list"))
        .andExpect(model().attributeExists("transactionListForm"));
  }
}
