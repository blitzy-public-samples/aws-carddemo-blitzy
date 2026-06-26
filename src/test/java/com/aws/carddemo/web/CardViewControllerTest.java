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
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest WebMvcTest} slice test
 * for {@link CardViewController} &mdash; the modernized web equivalent of the legacy CICS card
 * detail/view transaction {@code CCDL} ({@code legacy/app/cbl/COCRDSLC.cbl}, BMS mapset {@code
 * COCRDSL}). The card-view screen is reachable by any authenticated user (it is not one of the
 * admin-only user-management screens), so the controller deliberately carries no
 * {@code @PreAuthorize}; this test pins down its HTTP-to-view/routing contract (Agent Action Plan
 * &sect;0.4.1 {@code CardViewController <- COCRDSLC}; &sect;0.6.5 COMMAREA navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link CardViewService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /card-view}, {@code POST /card-view}), the resolved view name, the model attribute
 * name, and the program-name&rarr;redirect routing inherited from {@link BaseScreenController}. The
 * card-view business rules &mdash; the account/card filter validation literals, the {@code
 * findById(cardNum)} single-card lookup, the found-card field population, and the
 * card-list&rarr;auto-read decision &mdash; all live in {@link CardViewService} (the migrated
 * {@code COCRDSLC} numbered paragraphs) and are verified by its own unit test, not here.
 *
 * <p><strong>Routing parity.</strong> On the PF3 exit ({@code COCRDSLC} {@code CCARD-AID-PFK03}
 * arm, L305-334) the service returns the next program name &mdash; the main menu default {@code
 * "COMEN01C"} when the screen was reached from the menu &mdash; and the controller forwards it to
 * {@link BaseScreenController#redirectFor(String)}, which maps {@code "COMEN01C"} to {@code
 * redirect:/menu} (the {@code EXEC CICS XCTL} equivalent). A {@code null} service return instead
 * means "redisplay this screen" (the empty/invalid filter, not-found, or successful-read paint),
 * re-rendering the {@code card-view} view with its form-backing object.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard card-view user), GET requests need no CSRF token, and
 * every state-changing POST carries one via {@code with(csrf())}.
 */
@WebMvcTest(CardViewController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CardViewControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COCRDSLC} business logic. Stubbing its {@code
   * processCardView(...)} return value lets each test isolate a single controller routing branch
   * (PF3 exit vs. redisplay) without exercising the real account/card filter validation or the
   * single-card repository read.
   */
  @MockitoBean private CardViewService cardViewService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /card-view} (the first-entry paint, {@code COCRDSLC 0000-MAIN} {@code
   * CDEMO-PGM-ENTER} arms L339-356) renders the {@code card-view} view and places the form-backing
   * object ({@code cardViewForm}) on the model.
   *
   * <p>The controller always seeds a fresh, non-null {@code CardViewScreen} under {@code
   * cardViewForm} before invoking the service, so the attribute exists regardless of whether the
   * mocked service populates card detail; this test asserts the view name and the attribute's
   * presence, which are the controller's responsibility.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_rendersView() throws Exception {
    mockMvc
        .perform(get("/card-view"))
        .andExpect(status().isOk())
        .andExpect(view().name("card-view"))
        .andExpect(model().attributeExists("cardViewForm"));
  }

  /**
   * {@code POST /card-view} with PF3 transfers to the main menu: when the service returns the
   * program name {@code "COMEN01C"} (the {@code COCRDSLC} PF3 exit with no prior caller, L316-318),
   * the controller issues the {@code EXEC CICS XCTL} equivalent &mdash; a Spring redirect to that
   * screen's GET endpoint ({@code /menu}) via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_redirectsToMenu() throws Exception {
    given(cardViewService.processCardView(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/card-view").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /card-view} redisplays the screen when the service returns {@code null} (the
   * empty/invalid filter, "not found", or successful-read paint that the COBOL handled with {@code
   * SEND MAP} then {@code RETURN}). The controller re-renders the {@code card-view} view with the
   * form-backing object instead of redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(cardViewService.processCardView(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/card-view").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("card-view"))
        .andExpect(model().attributeExists("cardViewForm"));
  }
}
