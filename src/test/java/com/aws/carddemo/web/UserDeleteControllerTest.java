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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.UserDeleteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link WebMvcTest} slice test for {@link UserDeleteController} &mdash; the modernized web
 * equivalent of the legacy CICS admin-only delete-user transaction {@code CU03} ({@code
 * legacy/app/cbl/COUSR03C.cbl}, BMS mapset {@code COUSR03}). It pins the controller's
 * HTTP-to-view/model contract and its admin role gate (Agent Action Plan &sect;0.4.1 {@code
 * UserDeleteController <- COUSR03C}; &sect;0.6.5 COMMAREA / selected-user navigation; &sect;0.3.4
 * user-management {@code CU00}&ndash;{@code CU03} are admin-only).
 *
 * <p><strong>The headline behaviour: the selected-user flash hand-off (consume side).</strong> The
 * user-list screen ({@code COUSR00C}, {@code CU00}) lets an administrator pick a row with the
 * {@code 'D'} (delete) action; the legacy program stored that id in {@code CDEMO-CU03-USR-SELECTED}
 * and {@code COUSR03C MAIN-PARA} consumed it on first entry ({@code IF CDEMO-CU03-USR-SELECTED NOT
 * = SPACES AND LOW-VALUES &rarr; MOVE ... TO USRIDINI &rarr; PERFORM PROCESS-ENTER-KEY}, COUSR03C
 * L99-104) so the chosen user is shown read-only for confirmation. In the Spring application that
 * hand-off is a {@code RedirectAttributes} flash attribute named {@code selectedUserId}; this test
 * is the <em>consume</em> side, asserting that a {@code GET /user-delete} carrying that flash
 * copies the value onto {@link com.aws.carddemo.dto.screen.UserDeleteScreen#getUsrIdIn() usrIdIn}
 * of the bound {@code userDeleteForm}. ({@code USER0001} is a legacy user <em>identifier</em>,
 * never a credential; the delete screen has no password field.)
 *
 * <p><strong>Why this test renders the view.</strong> A {@code @WebMvcTest} auto-configures
 * Thymeleaf, so {@code mockMvc.perform(...).andExpect(status().isOk())} actually <em>renders</em>
 * the {@code user-delete} template through the real view resolver. A render-time binding mismatch
 * &mdash; a {@code th:field}/{@code th:text} selection expression referencing a property absent
 * from {@link com.aws.carddemo.dto.screen.UserDeleteScreen} &mdash; raises a Spring EL / binding
 * exception during rendering and fails the request, so the GET and POST redisplay assertions double
 * as a Thymeleaf template-evaluation guard.
 *
 * <p><strong>Slice boundary.</strong> {@link UserDeleteService} (the migrated {@code COUSR03C}
 * business logic) is replaced with a Mockito bean, so these tests assert only what the controller
 * owns: the {@code GET}/{@code POST /user-delete} mappings, the resolved view name, the {@code
 * userDeleteForm} model attribute, the flash consume, and the {@code hasRole('ADMIN')} gate. The
 * lookup / read-for-update / delete rules live in {@code UserDeleteService} and are covered by its
 * own unit test.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * authentication and the admin authorization rule match production; the application's {@code
 * GlobalExceptionHandler} is deliberately <em>not</em> imported, so a non-admin denial surfaces as
 * the security layer's clean {@code AccessDeniedException} &rarr; {@code 403}. Importing {@code
 * SecurityConfig} instantiates {@code userDetailsService(UserSecurityRepository)}, whose repository
 * dependency is supplied as a Mockito bean (never invoked &mdash; {@link WithMockUser} provides the
 * principal and authorities). CSRF stays enabled, so GETs need no token and the state-changing POST
 * carries one via {@code with(csrf())}.
 */
@WebMvcTest(UserDeleteController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class UserDeleteControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private UserDeleteService userDeleteService;

  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /user-delete} carrying the {@code selectedUserId} flash hand-off (the consume side
   * of the user-list {@code 'D'} selection, {@code CDEMO-CU03-USR-SELECTED}, COUSR03C L99-104)
   * copies that id onto the bound {@code userDeleteForm}'s {@code usrIdIn}, so the administrator
   * sees the chosen user pre-filled for delete confirmation. The template still renders, doubling
   * as a binding guard.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminGet_consumesSelectedUserIdFlash() throws Exception {
    mockMvc
        .perform(get("/user-delete").flashAttr("selectedUserId", "USER0001"))
        .andExpect(status().isOk())
        .andExpect(view().name("user-delete"))
        .andExpect(model().attribute("userDeleteForm", hasProperty("usrIdIn", is("USER0001"))));
  }

  /**
   * {@code GET /user-delete} without any inbound flash (a direct first-entry paint) renders the
   * {@code user-delete} view for an admin and places the {@code userDeleteForm} backing object on
   * the model. Rendering the template here verifies every {@code user-delete} field binding
   * resolves against {@link com.aws.carddemo.dto.screen.UserDeleteScreen}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminGet_withoutFlash_rendersView() throws Exception {
    mockMvc
        .perform(get("/user-delete"))
        .andExpect(status().isOk())
        .andExpect(view().name("user-delete"))
        .andExpect(model().attributeExists("userDeleteForm"));
  }

  /**
   * {@code POST /user-delete} redisplays the screen when the service returns {@code null} (the
   * ENTER lookup, empty-id / not-found validation message, PF5 delete-confirmation, or PF4
   * cleared-form redisplay path). The controller re-renders the {@code user-delete} template with
   * the bound form &mdash; a second template-evaluation guard over the POST redisplay path.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_serviceReturnsNull_redisplays() throws Exception {
    given(userDeleteService.processUserDelete(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/user-delete").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("user-delete"))
        .andExpect(model().attributeExists("userDeleteForm"));
  }

  /**
   * The {@code /user-delete} screen is admin-only: an authenticated non-admin (role {@code USER})
   * is denied with {@code 403 Forbidden} by the class-level
   * {@code @PreAuthorize("hasRole('ADMIN')")} gate, reproducing the legacy {@code
   * CDEMO-USRTYP-ADMIN} restriction (Agent Action Plan &sect;0.3.4, &sect;0.6.5).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "USER")
  void userGet_isForbidden() throws Exception {
    mockMvc.perform(get("/user-delete")).andExpect(status().isForbidden());
  }
}
