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
package com.aws.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.MainMenuService;
import com.aws.carddemo.web.MainMenuController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * Web-security slice test for {@link SecurityConfig}, verifying the HTTP authorization model that
 * gates the migrated CardDemo online transactions. It locks in the two access-control rules that
 * the rest of the application (and the still-incoming sign-on / user-management controllers) depend
 * on, reproducing the legacy CICS/RACF online gating with Spring Security (Agent Action Plan
 * &sect;0.3.4, &sect;0.6.5).
 *
 * <p>Two behaviours are asserted:
 *
 * <ol>
 *   <li><b>Public sign-on submit.</b> {@code POST /signon} &mdash; the modernized {@code COSGN00C}
 *       (transaction {@code CC00}) sign-on submit &mdash; must be reachable by an unauthenticated
 *       caller so the sign-on service can validate the credentials; if the security layer blocked
 *       it, sign-in would be impossible before the controller/service ever ran.
 *   <li><b>Admin-gated user management.</b> The user-management screens {@code
 *       COUSR00C}&ndash;{@code COUSR03C} ({@code CU00}&ndash;{@code CU03}) are routed to {@code
 *       /user-list}, {@code /user-add}, {@code /user-update}, {@code /user-delete} &mdash; outside
 *       {@code /admin/**} &mdash; and must be reachable only by {@code ROLE_ADMIN}. The existing
 *       {@code /admin/**} gate (the {@code COADM01C} admin menu) is asserted unchanged as a
 *       regression guard.
 * </ol>
 *
 * <p><strong>Slice design.</strong> This is a {@link WebMvcTest} limited to a single concrete
 * controller so the real {@link SecurityConfig} filter chain (imported explicitly) is exercised
 * without bootstrapping the persistence, batch, or full service layers. The two collaborators the
 * imported configuration and the loaded controller require &mdash; the {@link
 * UserSecurityRepository} behind {@code SecurityConfig.userDetailsService} and the {@link
 * MainMenuService} behind {@link MainMenuController} &mdash; are replaced with Mockito beans;
 * neither is invoked because the authorization decisions are reached by the security filter chain
 * before any handler runs.
 *
 * <p><strong>Assertion strategy.</strong> A denied <em>authenticated</em> request is rejected by
 * the security filter with a deterministic {@code 403 Forbidden} before Spring MVC dispatching, so
 * those cases assert {@code 403} exactly. A request that <em>passes</em> authorization continues
 * into the MVC layer where no handler is mapped in this slice; the resulting status is therefore
 * irrelevant to authorization, so those cases assert only that the response is neither {@code 401}
 * nor {@code 403} &mdash; i.e. the security filter let the request through.
 */
@WebMvcTest(controllers = MainMenuController.class)
@Import(SecurityConfig.class)
@DisplayName("SecurityConfig — public POST /signon + admin-gated user management")
class SecurityConfigTest {

  /** HTTP 401 Unauthorized status code (no/failed authentication). */
  private static final int HTTP_UNAUTHORIZED = 401;

  /** HTTP 403 Forbidden status code (authenticated but not authorized). */
  private static final int HTTP_FORBIDDEN = 403;

  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the {@link MainMenuController} collaborator. The controller is loaded only
   * so the web slice has a concrete handler bean; the service is never invoked by these
   * authorization assertions.
   */
  @MockitoBean private MainMenuService mainMenuService;

  /**
   * Mockito stand-in for the repository behind {@code SecurityConfig.userDetailsService}. It is
   * required for the imported configuration to instantiate but is never invoked, because {@link
   * WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  // ===== Finding #1 (CRITICAL): the sign-on submit POST /signon must be public ==================

  @Test
  @DisplayName("POST /signon is permitted for an unauthenticated caller")
  void postSignonIsPubliclyPermitted() throws Exception {
    // Anonymous submit carrying a valid CSRF token (CSRF stays enabled): the security filter must
    // let it through to the (later) SignonController, so the response must not be 401/403.
    assertAuthorizationPassed(post("/signon").with(csrf()));
  }

  // ===== Finding #2 (MAJOR): user-management screens are admin-only =============================

  @Test
  @WithMockUser(roles = "USER")
  @DisplayName("GET user-management routes are forbidden for a standard USER")
  void userManagementGetForbiddenForStandardUser() throws Exception {
    assertForbidden(get("/user-list"));
    assertForbidden(get("/user-add"));
    assertForbidden(get("/user-update"));
    assertForbidden(get("/user-delete"));
  }

  @Test
  @WithMockUser(roles = "USER")
  @DisplayName("POST user-management routes (the form submits) are forbidden for a standard USER")
  void userManagementPostForbiddenForStandardUser() throws Exception {
    assertForbidden(post("/user-list").with(csrf()));
    assertForbidden(post("/user-add").with(csrf()));
    assertForbidden(post("/user-update").with(csrf()));
    assertForbidden(post("/user-delete").with(csrf()));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("user-management routes pass the security gate for an ADMIN")
  void userManagementAllowedForAdmin() throws Exception {
    assertAuthorizationPassed(get("/user-list"));
    assertAuthorizationPassed(get("/user-add"));
    assertAuthorizationPassed(get("/user-update"));
    assertAuthorizationPassed(get("/user-delete"));
  }

  // ===== Regression: the existing /admin/** gate (COADM01C admin menu) is preserved =============

  @Test
  @WithMockUser(roles = "USER")
  @DisplayName("GET /admin/** remains forbidden for a standard USER")
  void adminSpaceForbiddenForStandardUser() throws Exception {
    assertForbidden(get("/admin/menu"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("GET /admin/** passes the security gate for an ADMIN")
  void adminSpaceAllowedForAdmin() throws Exception {
    assertAuthorizationPassed(get("/admin/menu"));
  }

  // ===== Helpers ================================================================================

  /**
   * Asserts the request is rejected by the security filter with a deterministic {@code 403
   * Forbidden}. Applies to an authenticated principal that lacks the required authority: the
   * rejection happens in the security filter chain before MVC dispatching, independent of any
   * handler or {@code @ControllerAdvice}.
   *
   * @param request the request to perform
   * @throws Exception if the request cannot be performed
   */
  private void assertForbidden(RequestBuilder request) throws Exception {
    int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
    assertThat(status)
        .as("request should be rejected with 403 Forbidden but was %d", status)
        .isEqualTo(HTTP_FORBIDDEN);
  }

  /**
   * Asserts the request passes the security filter, i.e. the response is neither {@code 401
   * Unauthorized} nor {@code 403 Forbidden}. The request then enters the MVC layer where no handler
   * is mapped in this slice, so the concrete downstream status is intentionally not asserted.
   *
   * @param request the request to perform
   * @throws Exception if the request cannot be performed
   */
  private void assertAuthorizationPassed(RequestBuilder request) throws Exception {
    int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
    assertThat(status)
        .as("request should pass the security filter (not 401/403) but was %d", status)
        .isNotEqualTo(HTTP_UNAUTHORIZED)
        .isNotEqualTo(HTTP_FORBIDDEN);
  }
}
