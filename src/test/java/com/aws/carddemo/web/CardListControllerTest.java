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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.dto.screen.CardListScreen;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.CardListService;
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
 * for {@link CardListController} &mdash; the modernized web equivalent of the legacy CICS card-list
 * / browse transaction {@code CCLI} ({@code legacy/app/cbl/COCRDLIC.cbl}). The card list is a
 * standard (non-admin) paged-list screen, so the controller deliberately carries no
 * {@code @PreAuthorize}; this test pins down its HTTP-to-view/routing contract (Agent Action Plan
 * &sect;0.4.1 {@code CardListController <- COCRDLIC}; &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link CardListService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the shared request
 * mapping ({@code GET}/{@code POST /card-list}), the resolved view name, the model attribute name
 * ({@code cardListForm}), and the program-name&rarr;redirect routing inherited from {@link
 * BaseScreenController}. The card-browse business rules (filter validation, the VSAM-equivalent
 * forward/backward keyset browse, the seven-row paging window, single-select enforcement, and the
 * {@code 'S'}/{@code 'U'} action decision) live in {@link CardListService} and are verified by its
 * own unit test, not here.
 *
 * <p><strong>No flash attribute.</strong> Unlike the transaction-list and user-list screens (which
 * hand the selected key to the next screen via a {@code RedirectAttributes} flash), the card list
 * writes the chosen account id / card number into the shared COMMAREA session state, and the
 * downstream {@code CardViewController} ({@code COCRDSLC}) / {@code CardUpdateController} ({@code
 * COCRDUPC}) read it from the session on their own GET. The controller therefore performs a pure
 * program&rarr;URL redirect with no flash payload, so these tests assert only the redirect targets
 * (no flash-attribute assertions) &mdash; reproducing the {@code COCRDLIC} {@code MOVE
 * WS-ROW-ACCTNO/WS-ROW-CARD-NUM TO CDEMO-ACCT-ID/CDEMO-CARD-NUM} hand-off (AAP &sect;0.6.5).
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard card-list user), the GET request needs no CSRF token, and
 * every state-changing POST carries one via {@code with(csrf())}.
 */
@WebMvcTest(CardListController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CardListControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COCRDLIC} business logic. Stubbing its {@code
   * processCardList(...)} return value lets each test isolate a single controller routing branch
   * (dispatch vs. redisplay) without exercising the real card-browse rules.
   */
  @MockitoBean private CardListService cardListService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /card-list} (the first-entry paint, {@code COCRDLIC 0000-MAIN} L315-L323 followed by
   * {@code 1000-SEND-MAP} L624) renders the {@code card-list} view and places the form-backing
   * object ({@code cardListForm}) on the model. The {@code CardListScreen} is always a fresh,
   * non-null instance, so the attribute exists regardless of the mocked service's behaviour.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_rendersView() throws Exception {
    mockMvc
        .perform(get("/card-list"))
        .andExpect(status().isOk())
        .andExpect(view().name("card-list"))
        .andExpect(model().attributeExists("cardListForm"));
  }

  /**
   * {@code POST /card-list} with a single row marked {@code 'S'} (select-to-view) dispatches to the
   * card detail program: when the service returns the program name {@code "COCRDSLC"}, the
   * controller issues the {@code EXEC CICS XCTL PROGRAM('COCRDSLC')} equivalent &mdash; a Spring
   * redirect to that screen's GET endpoint ({@code /card-view}) via {@link
   * BaseScreenController#redirectFor(String)}. The selected account/card already travels on the
   * persisted COMMAREA, so no flash attribute is asserted (see the class Javadoc).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_select_redirectsToCardView() throws Exception {
    given(cardListService.processCardList(any(), any(), any())).willReturn("COCRDSLC");

    mockMvc
        .perform(post("/card-list").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/card-view"));
  }

  /**
   * {@code POST /card-list} with a single row marked {@code 'U'} (select-to-update) dispatches to
   * the card update program: when the service returns {@code "COCRDUPC"}, the controller redirects
   * to that screen's GET endpoint ({@code /card-update}). As with the {@code 'S'} action, the
   * selected context travels on the COMMAREA, so only the redirect target is asserted.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_update_redirectsToCardUpdate() throws Exception {
    given(cardListService.processCardList(any(), any(), any())).willReturn("COCRDUPC");

    mockMvc
        .perform(post("/card-list").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/card-update"));
  }

  /**
   * {@code POST /card-list} with PF3 exits to the main menu: when the service returns {@code
   * "COMEN01C"} (the {@code COCRDLIC} PF03 exit path), the controller redirects to the main-menu
   * GET endpoint ({@code /menu}).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_redirectsToMenu() throws Exception {
    given(cardListService.processCardList(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/card-list").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /card-list} redisplays the list when the service returns {@code null} (a PF7/PF8
   * paging refresh, an invalid action code, a multi-row selection, or a "no records found"
   * outcome). The controller re-renders the {@code card-list} view with the form-backing object
   * instead of redirecting, each case carrying its message on the screen.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(cardListService.processCardList(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/card-list").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("card-list"))
        .andExpect(model().attributeExists("cardListForm"));
  }

  /**
   * Accessibility (review Finding 6): when the screen carries an informational message, the shared
   * {@code fragments/layout :: messages} fragment renders it inside an element with {@code
   * role="status"} so screen readers announce it politely. Here the mocked service populates {@code
   * infoMsg} on the bound {@link CardListScreen} and returns {@code null} (the re-display path),
   * and the rendered HTML is asserted to contain both {@code role="status"} and the message text.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_infoMessage_rendersPoliteStatusRole() throws Exception {
    willAnswer(
            invocation -> {
              CardListScreen bound = invocation.getArgument(0);
              bound.setInfoMsg("No records to display.");
              return null; // re-display the list (no program transfer)
            })
        .given(cardListService)
        .processCardList(any(), any(), any());

    mockMvc
        .perform(post("/card-list").param("pfKey", "ENTER").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("card-list"))
        .andExpect(content().string(containsString("role=\"status\"")))
        .andExpect(content().string(containsString("No records to display.")));
  }
}
