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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.service.online.SignonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest WebMvcTest} slice test
 * for {@link SignonController} &mdash; the modernized web equivalent of the legacy CICS sign-on
 * transaction {@code CC00} ({@code legacy/app/cbl/COSGN00C.cbl}). It pins down the controller's
 * HTTP-to-view/routing contract (Agent Action Plan &sect;0.4.1 {@code SignonController <-
 * COSGN00C}; &sect;0.6.5 pseudo-conversational COMMAREA navigation): the GET first-entry paint of
 * the sign-on screen, and the POST routing parity in which a standard user reaches the main menu
 * ({@code COMEN01C} &rarr; {@code /menu}), an administrator reaches the admin menu ({@code
 * COADM01C} &rarr; {@code /admin}), and invalid credentials redisplay the sign-on screen.
 *
 * <p><strong>Slice boundary.</strong> The {@link SignonService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /}, {@code GET /login}, {@code POST /signon}), the resolved view name and model
 * attribute, and the program-name&rarr;redirect routing inherited from {@link
 * BaseScreenController}. The sign-on business rules (the {@code USRSEC} lookup, BCrypt password
 * verification, role assignment, and the byte-exact legacy messages such as {@code "Wrong Password.
 * Try again ..."} and {@code "User not found. Try again ..."}) live in {@link SignonService} and
 * are verified by its own unit test, not here.
 *
 * <p><strong>Security posture (DESIGN DECISION: {@code addFilters = false}).</strong> The
 * production {@code SecurityConfig} explicitly {@code permitAll()}s the sign-on submit &mdash; it
 * registers a method-specific matcher {@code requestMatchers(HttpMethod.POST, "/signon")} so an
 * unauthenticated user can post the sign-on form &mdash; while CSRF protection is left at its
 * enabled default. This slice test is therefore <em>not</em> reproducing that production rule:
 * {@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} disables the Spring
 * Security filter chain purely to isolate the controller's RECEIVE/SEND routing logic from CSRF and
 * URL gating, so the routing branches can be exercised directly without seeding a CSRF token. The
 * end-to-end security contract for {@code POST /signon} &mdash; that it is permitted yet still
 * CSRF-protected (a token-less post is rejected with {@code 403}) &mdash; is verified separately by
 * {@code SecurityFilterChainTest} and the full-context Testcontainers {@code
 * CardDemoApplicationTests}, not here. Consequently this test imports no {@code SecurityConfig},
 * declares no {@code @WithMockUser}, sends no CSRF token, and mocks no {@code
 * UserSecurityRepository}.
 */
@WebMvcTest(SignonController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class SignonControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COSGN00C} business logic. Stubbing its {@code
   * processSignon(...)} return value (and, for the success paths, mutating the injected {@link
   * CardDemoCommarea}) lets each test isolate a single controller routing branch &mdash; route to a
   * menu vs. redisplay the sign-on screen &mdash; without exercising the real credential rules.
   */
  @MockitoBean private SignonService signonService;

  /**
   * {@code GET /login} (the first-entry paint, {@code COSGN00C MAIN-PARA} {@code EIBCALEN = 0}
   * branch, L80-83) renders the {@code signon} view and places the form-backing object ({@code
   * signonForm}) on the model.
   *
   * <p>The GET handler builds a fresh {@code SignonScreen} and delegates to {@code
   * signonService.prepareInitialScreen(...)} (a {@code void} call the mocked service treats as a
   * no-op), so the view renders without any stubbing.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void get_login_rendersSignonView() throws Exception {
    mockMvc
        .perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(view().name("signon"))
        .andExpect(model().attributeExists("signonForm"));
  }

  /**
   * {@code GET /} renders the same sign-on screen as {@code GET /login}: the controller maps
   * <em>both</em> paths to the first-entry paint so the {@code SecurityConfig} {@code
   * loginPage("/login")} and the permitAll root {@code "/"} both land on the {@code signon} view.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void get_root_rendersSignonView() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(view().name("signon"))
        .andExpect(model().attributeExists("signonForm"));
  }

  /**
   * {@code POST /signon} for a successful <em>standard user</em> sign-on routes to the main menu,
   * reproducing the {@code COSGN00C} L236-238 {@code EXEC CICS XCTL PROGRAM('COMEN01C')} transfer.
   *
   * <p>The stub mutates the COMMAREA exactly as the real service would &mdash; capturing the
   * resolved user id and the {@code 'U'} user type (so {@code commarea.isAdmin()} is {@code false})
   * &mdash; then returns {@code "COMEN01C"}. This mutation is essential: on a successful
   * authentication the controller dereferences the COMMAREA to build the Spring Security context
   * (principal = user id, role from {@code isAdmin()}) before issuing the redirect. The controller
   * then maps {@code "COMEN01C"} to {@code /menu} via {@link BaseScreenController#redirectFor}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void post_validUser_routesToMainMenu() throws Exception {
    given(signonService.processSignon(any(), any(), any()))
        .willAnswer(
            inv -> {
              CardDemoCommarea commarea = inv.getArgument(1);
              commarea.setUserId("USER0001");
              commarea.setUsrTypUser();
              return "COMEN01C";
            });

    mockMvc
        .perform(post("/signon"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /signon} for a successful <em>administrator</em> sign-on routes to the admin menu,
   * reproducing the {@code COSGN00C} L230-233 {@code IF CDEMO-USRTYP-ADMIN ... EXEC CICS XCTL
   * PROGRAM('COADM01C')} transfer.
   *
   * <p>The stub captures the admin user id and sets the {@code 'A'} user type (so {@code
   * commarea.isAdmin()} is {@code true} and the controller assigns {@code ROLE_ADMIN}), then
   * returns {@code "COADM01C"}, which the controller maps to {@code /admin}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void post_validAdmin_routesToAdminMenu() throws Exception {
    given(signonService.processSignon(any(), any(), any()))
        .willAnswer(
            inv -> {
              CardDemoCommarea commarea = inv.getArgument(1);
              commarea.setUserId("ADMIN001");
              commarea.setUsrTypAdmin();
              return "COADM01C";
            });

    mockMvc
        .perform(post("/signon"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin"));
  }

  /**
   * {@code POST /signon} redisplays the sign-on screen when the service returns {@code null}
   * &mdash; the {@code COSGN00C} validation/authentication failure path (blank field, wrong
   * password, user not found, or unable to verify) and the PF3 exit. The controller re-renders the
   * {@code signon} view with the form-backing object rather than redirecting, and builds
   * <em>no</em> security context (so the COMMAREA needs no mutation here). The specific failure
   * message is the service's responsibility and is asserted in the service-layer test.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void post_invalidCredentials_redisplaysSignon() throws Exception {
    given(signonService.processSignon(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/signon"))
        .andExpect(status().isOk())
        .andExpect(view().name("signon"))
        .andExpect(model().attributeExists("signonForm"));
  }

  /**
   * {@code POST /signon} for a successful sign-on rotates the servlet session identifier, defeating
   * session fixation (CWE-384 / OWASP A07:2021). The pre-authentication {@link MockHttpSession} id
   * captured before the request must differ afterwards, proving the controller invokes {@code
   * HttpServletRequest#changeSessionId()} on the anonymous&rarr;authenticated boundary &mdash; the
   * modernized equivalent of Spring Security's default {@code changeSessionId}
   * session-authentication strategy, which the custom, filter-bypassing sign-on flow would
   * otherwise skip. {@code changeSessionId()} preserves the session attributes, so the COMMAREA
   * navigation state survives the rotation.
   *
   * <p>The stub mutates the COMMAREA exactly as the real service would (resolved user id + the
   * {@code 'U'} type) and returns {@code "COMEN01C"}, so the controller takes the success branch
   * and the rotation executes before the redirect to {@code /menu}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void post_validSignon_rotatesSessionIdToDefeatFixation() throws Exception {
    given(signonService.processSignon(any(), any(), any()))
        .willAnswer(
            inv -> {
              CardDemoCommarea commarea = inv.getArgument(1);
              commarea.setUserId("USER0001");
              commarea.setUsrTypUser();
              return "COMEN01C";
            });

    MockHttpSession preAuthSession = new MockHttpSession();
    String preAuthSessionId = preAuthSession.getId();

    mockMvc
        .perform(post("/signon").session(preAuthSession))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));

    assertThat(preAuthSession.getId())
        .as("the servlet session id must be rotated on successful sign-on (CWE-384)")
        .isNotEqualTo(preAuthSessionId);
  }

  /**
   * {@code POST /signon} that fails authentication (the service returns {@code null}) must NOT
   * rotate the session identifier. The rotation is reserved for the successful
   * anonymous&rarr;authenticated transition, mirroring Spring Security's strategy of rotating the
   * session id only on a successful authentication; a failed attempt redisplays the sign-on screen
   * and leaves the session id untouched. The pre-request {@link MockHttpSession} id is therefore
   * unchanged afterwards.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  void post_failedSignon_doesNotRotateSessionId() throws Exception {
    given(signonService.processSignon(any(), any(), any())).willReturn(null);

    MockHttpSession preAuthSession = new MockHttpSession();
    String preAuthSessionId = preAuthSession.getId();

    mockMvc
        .perform(post("/signon").session(preAuthSession))
        .andExpect(status().isOk())
        .andExpect(view().name("signon"));

    assertThat(preAuthSession.getId())
        .as("the session id must not change when authentication fails")
        .isEqualTo(preAuthSessionId);
  }
}
