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
import com.aws.carddemo.service.online.UserListService;
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
 * for {@link UserListController} &mdash; the modernized web equivalent of the legacy CICS
 * list-users transaction {@code CU00} ({@code legacy/app/cbl/COUSR00C.cbl},
 * <strong>admin-only</strong>). The legacy program browses the {@code USRSEC} file ten rows at a
 * time and, when the operator marks a row with {@code U}/{@code D}, transfers control to the update
 * ({@code COUSR02C}) or delete ({@code COUSR03C}) screen; {@code PF3} returns to the admin menu
 * ({@code COADM01C}). This test pins down the controller's HTTP-to-view/routing contract and,
 * critically, its admin gating (Agent Action Plan &sect;0.3.4 user management CU00&ndash;CU03
 * admin-only, &sect;0.4.1 {@code UserListController <- COUSR00C}, &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link UserListService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /user-list}, {@code POST /user-list}), the resolved view name ({@code user-list}),
 * the {@code userListForm} model attribute, the program-name&rarr;redirect routing inherited from
 * {@link BaseScreenController}, and the controller-mediated selection handoff. The list/browse
 * business rules (keyset paging, the ten-row page build, the row-selection scan, the operator
 * messages) live in {@link UserListService} and are verified by its own unit test, not here.
 *
 * <p><strong>Selection handoff (the {@code selectedUserId} flash, KEY INSIGHT).</strong> When the
 * service signals a row selection by returning {@code "COUSR02C"}/{@code "COUSR03C"}, the
 * controller re-derives the chosen user id from the bound rows and carries it to the update/delete
 * screen as a Spring MVC flash attribute. The <em>produce</em> side is exercised here with a
 * request that binds no rows: {@code firstSelectedUserId} then returns {@code null}, so the
 * redirect to {@code /user-update} still occurs but <em>no</em> flash attribute is added &mdash;
 * asserted via {@link org.springframework.test.web.servlet.result.MockMvcResultMatchers#flash()
 * flash().attributeCount(0)}. This deliberately avoids constructing the DTO-owned {@code
 * UserListScreen.UserListRow}; the positive (non-null id) hand-off is asserted end-to-end by {@code
 * UserUpdateControllerTest}/{@code UserDeleteControllerTest} via {@code flashAttr(...)}.
 *
 * <p><strong>Security wiring (distinct from the AdminMenu URL-rule test).</strong> The real {@link
 * SecurityConfig} filter chain is imported so the test exercises the production
 * authentication/CSRF/role posture rather than a relaxed test default. Importing {@code
 * SecurityConfig} instantiates its {@code userDetailsService(UserSecurityRepository)} bean, which
 * requires a {@link UserSecurityRepository} bean that does not exist inside a {@code @WebMvcTest}
 * slice; it is therefore supplied as a Mockito bean. {@code SecurityConfig} enables method security
 * ({@code @EnableMethodSecurity}), so the class-level {@code @PreAuthorize("hasRole('ADMIN')")} on
 * {@link UserListController} is enforced as defense-in-depth alongside the URL rule. {@code
 * GlobalExceptionHandler} is deliberately <strong>not</strong> imported: its
 * {@code @ExceptionHandler(Exception.class)} would catch an {@code AccessDeniedException} thrown
 * inside the dispatcher (the method-security path) and translate it to {@code 500}, masking the
 * {@code 403} that {@link #userGet_isForbidden()} asserts. Every test authenticates with {@link
 * WithMockUser} ({@code ADMIN} for the reachable flows, {@code USER} for the denial), GET requests
 * need no CSRF token, and every state-changing POST carries one via {@code with(csrf())}.
 */
@WebMvcTest(UserListController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class UserListControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COUSR00C} business logic. Stubbing its {@code
   * processUserList(...)} return value lets each test isolate a single controller routing branch
   * (update/delete dispatch, PF3 return, or redisplay) without exercising the real browse rules.
   */
  @MockitoBean private UserListService userListService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /user-list} (the first-entry paint, {@code COUSR00C MAIN-PARA} L115-119) renders the
   * {@code user-list} view and places the form-backing object ({@code userListForm}) on the model.
   * An administrator may reach the screen, so the request is authenticated with {@code ROLE_ADMIN}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminGet_rendersUserList() throws Exception {
    mockMvc
        .perform(get("/user-list"))
        .andExpect(status().isOk())
        .andExpect(view().name("user-list"))
        .andExpect(model().attributeExists("userListForm"));
  }

  /**
   * {@code POST /user-list} with a row marked for update dispatches to the update-user screen: when
   * the service returns the program name {@code "COUSR02C"} (the legacy {@code MOVE 'COUSR02C' TO
   * CDEMO-TO-PROGRAM} / {@code EXEC CICS XCTL} path, COUSR00C L192-199), the controller issues a
   * Spring redirect to that screen's GET endpoint ({@code /user-update}) via {@link
   * BaseScreenController#redirectFor(String)}.
   *
   * <p>Because the request binds no rows, {@code firstSelectedUserId} returns {@code null}, so the
   * selected-id flash attribute is <em>not</em> added &mdash; the redirect happens regardless. This
   * asserts the redirect target plus an empty flash scope ({@code flash().attributeCount(0)}),
   * which is the produce-side parity for a selection whose id is supplied to the target screen
   * end-to-end in the update/delete consume tests.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_updateSelectionWithoutRows_redirectsWithoutFlash() throws Exception {
    given(userListService.processUserList(any(), any(), any())).willReturn("COUSR02C");

    mockMvc
        .perform(post("/user-list").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/user-update"))
        .andExpect(flash().attributeCount(0));
  }

  /**
   * {@code POST /user-list} with PF3 returns to the admin menu: when the service returns {@code
   * "COADM01C"} (the legacy {@code MOVE 'COADM01C' TO CDEMO-TO-PROGRAM} / {@code RETURN-TO-PREV-
   * SCREEN} path, COUSR00C L125-127), the controller redirects to the admin-menu GET endpoint
   * ({@code /admin}). The return value is neither {@code COUSR02C} nor {@code COUSR03C}, so no
   * selection flash is produced.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_pf3_redirectsToAdminMenu() throws Exception {
    given(userListService.processUserList(any(), any(), any())).willReturn("COADM01C");

    mockMvc
        .perform(post("/user-list").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin"));
  }

  /**
   * {@code POST /user-list} redisplays the list when the service returns {@code null} (the
   * paging/invalid-selection/invalid-key redisplay paths, COUSR00C). The controller re-renders the
   * {@code user-list} view with the form-backing object instead of redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_serviceReturnsNull_redisplays() throws Exception {
    given(userListService.processUserList(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/user-list").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("user-list"))
        .andExpect(model().attributeExists("userListForm"));
  }

  /**
   * A standard (non-admin) user is denied access to the list-users screen with {@code 403
   * Forbidden}. {@code CU00} is admin-only (Agent Action Plan &sect;0.3.4): the legacy transaction
   * is reachable only from the admin menu, an invariant reproduced here by the class-level
   * {@code @PreAuthorize("hasRole('ADMIN')")} on {@link UserListController} (enabled by
   * {@code @EnableMethodSecurity} via the imported {@link SecurityConfig}) as well as the {@code
   * /user-list} URL rule. An authenticated {@code ROLE_USER} principal therefore receives {@code
   * 403} rather than the rendered screen.
   *
   * <p>This assertion holds only because {@code GlobalExceptionHandler} is not imported into the
   * slice: were it present, its {@code @ExceptionHandler(Exception.class)} would intercept the
   * method-security {@code AccessDeniedException} inside the dispatcher and return {@code 500},
   * masking the denial (see the class Javadoc).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "USER")
  void userGet_isForbidden() throws Exception {
    mockMvc.perform(get("/user-list")).andExpect(status().isForbidden());
  }
}
