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
import com.aws.carddemo.service.online.UserUpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link WebMvcTest} slice test for {@link UserUpdateController} &mdash; the modernized web
 * equivalent of the legacy admin-only CICS update-user transaction {@code CU02} ({@code
 * legacy/app/cbl/COUSR02C.cbl}, BMS mapset {@code COUSR02}). It pins the controller's
 * HTTP-to-view/routing contract, the user-list&rarr;update <em>flash handoff</em>, and its admin
 * role gate (Agent Action Plan &sect;0.4.1 {@code UserUpdateController <- COUSR02C}; &sect;0.6.5
 * COMMAREA navigation; &sect;0.3.4 user-management {@code CU00}&ndash;{@code CU03} admin-only).
 *
 * <p><strong>Authoritative consume-side assertion of the {@code selectedUserId} handoff.</strong>
 * The legacy {@code COUSR02C}, on fresh entry, copies {@code CDEMO-CU02-USR-SELECTED} &mdash; set
 * by the user-list screen {@code COUSR00C} when the operator picks a row with the {@code 'U'}
 * (update) action &mdash; into the {@code USRIDIN} field and looks that user up ({@code COUSR02C}
 * L95-104). The modernized user-list controller flashes that id as the {@code selectedUserId}
 * attribute across the redirect; {@link UserUpdateController#showUserUpdate} consumes it from the
 * {@code Model} and pre-fills {@code usrIdIn}. {@link #adminGet_consumesSelectedUserIdFlash()}
 * drives that consume path end-to-end: a GET carrying the flash attribute must surface on the
 * {@code userUpdateForm} backing object as {@code usrIdIn == "USER0001"}. ({@code USER0001} is a
 * legacy <em>user id</em>, not a credential &mdash; no password is involved.)
 *
 * <p><strong>Why these tests also render the view.</strong> A {@code @WebMvcTest} auto-configures
 * Thymeleaf, so {@code andExpect(status().isOk())} actually <em>renders</em> the {@code
 * user-update} template through the real view resolver. A render-time binding mismatch &mdash; a
 * {@code th:field}/{@code th:text} selection expression referencing a property absent from {@link
 * com.aws.carddemo.dto.screen.UserUpdateScreen} (for example a friendly {@code password} where the
 * COBOL-derived DTO exposes {@code passwd}) &mdash; raises a Spring EL / binding exception and
 * fails the request. The GET and POST-redisplay assertions therefore double as a Thymeleaf
 * template-evaluation guard over the {@code usrIdIn}/{@code fName}/{@code lName}/{@code
 * passwd}/{@code usrType} field contract.
 *
 * <p><strong>Slice boundary.</strong> {@link UserUpdateService} (the migrated {@code COUSR02C}
 * lookup/validate/optimistic-rewrite business logic) is replaced with a Mockito bean, so these
 * tests assert only what the controller owns: the {@code GET}/{@code POST /user-update} mappings,
 * the resolved view name ({@code user-update}), the {@code userUpdateForm} model attribute, the
 * flash-consume pre-fill, the {@code hasRole('ADMIN')} gate, and the program-name&rarr;redirect
 * routing inherited from {@link BaseScreenController}. The per-field business rules live in {@code
 * UserUpdateService} and are covered by its own unit test, not here.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * CSRF, authentication, and the {@code /user-update} admin authorization rule match production.
 * {@code SecurityConfig} gates {@code /user-update} to {@code ROLE_ADMIN} at two independent layers
 * &mdash; the URL-authorization matcher {@code requestMatchers("/user-list", "/user-add",
 * "/user-update", "/user-delete").hasRole("ADMIN")} and the controller's class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} (enabled by {@code @EnableMethodSecurity}) &mdash; so a
 * standard user is denied with a clean {@code 403 Forbidden} from Spring Security's {@code
 * ExceptionTranslationFilter}. Only {@code SecurityConfig} is imported: the application's {@code
 * GlobalExceptionHandler} ({@code @ControllerAdvice}) is deliberately <em>not</em> imported, so the
 * denial surfaces as the framework {@code 403} rather than being re-mapped by an exception handler.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, whose repository dependency is supplied as a
 * Mockito bean (never invoked &mdash; {@link WithMockUser} provides the principal and authorities).
 * CSRF stays enabled, so GETs need no token and every state-changing {@code POST} that must reach
 * the handler carries one via {@code with(csrf())}.
 */
@WebMvcTest(UserUpdateController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class UserUpdateControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COUSR02C} business logic. Stubbing its {@code
   * processUserUpdate(...)} return value lets each test isolate a single controller routing branch
   * (PF3 dispatch vs. {@code null}-return redisplay) without exercising the real lookup,
   * validation, or optimistic rewrite.
   */
  @MockitoBean private UserUpdateService userUpdateService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /user-update} consumes the user-list&rarr;update flash handoff. Supplying {@code
   * selectedUserId="USER0001"} as a flash attribute reproduces the {@code COUSR00C} {@code 'U'}
   * (update) row selection that the legacy program received in {@code CDEMO-CU02-USR-SELECTED}; the
   * controller copies it into the screen's {@code usrIdIn} lookup key ({@code COUSR02C} L95-104).
   * The assertion targets the {@code userUpdateForm} backing object directly via {@code
   * hasProperty}, keeping it robust against the mocked service's internals: the controller's
   * pre-fill is verified, not the service's lookup. ({@code USER0001} is a legacy user id, not a
   * credential.)
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminGet_consumesSelectedUserIdFlash() throws Exception {
    mockMvc
        .perform(get("/user-update").flashAttr("selectedUserId", "USER0001"))
        .andExpect(status().isOk())
        .andExpect(view().name("user-update"))
        .andExpect(model().attribute("userUpdateForm", hasProperty("usrIdIn", is("USER0001"))));
  }

  /**
   * {@code GET /user-update} without any flash handoff (the operator navigates to the screen
   * directly to type a user id) renders the {@code user-update} view for an admin and places the
   * {@code userUpdateForm} backing object on the model. The controller always allocates a fresh,
   * non-null {@code UserUpdateScreen} before delegating to the service, so the attribute exists
   * regardless of the mocked service's behavior, and rendering the template guards every {@code
   * user-update} field binding.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminGet_withoutFlash_rendersView() throws Exception {
    mockMvc
        .perform(get("/user-update"))
        .andExpect(status().isOk())
        .andExpect(view().name("user-update"))
        .andExpect(model().attributeExists("userUpdateForm"));
  }

  /**
   * {@code POST /user-update} with PF3 returns to the admin menu: PF3 is the COBOL {@code WHEN
   * DFHPF3} branch that performs the update and then moves {@code 'COADM01C'} to {@code
   * CDEMO-TO-PROGRAM} for the {@code EXEC CICS XCTL} transfer ({@code COUSR02C} L111-119). When the
   * service returns the program name {@code "COADM01C"}, the controller issues the XCTL equivalent
   * &mdash; a Spring redirect to the admin menu's GET endpoint ({@code /admin}) via {@link
   * BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_pf3_redirectsToAdminMenu() throws Exception {
    given(userUpdateService.processUserUpdate(any(), any(), any())).willReturn("COADM01C");

    mockMvc
        .perform(post("/user-update").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin"));
  }

  /**
   * {@code POST /user-update} redisplays the screen when the service returns {@code null} (the
   * re-entry paths that stay on the update-user map: the ENTER lookup result, a field-validation
   * failure, the PF5 update confirmation / no-change message, or the PF4 cleared form). The
   * controller re-renders the {@code user-update} view with the bound {@code userUpdateForm} object
   * instead of redirecting &mdash; a second template-evaluation guard over the POST redisplay path.
   * No {@code pfKey} is supplied, so the inherited AID resolution defaults to ENTER, matching a
   * plain submit; and no password value is sent (AAP &sect;0.6.6 credential hygiene).
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPost_serviceReturnsNull_redisplays() throws Exception {
    given(userUpdateService.processUserUpdate(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/user-update").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("user-update"))
        .andExpect(model().attributeExists("userUpdateForm"));
  }

  /**
   * The {@code /user-update} screen is admin-only: an authenticated non-admin (role {@code USER})
   * is denied with {@code 403 Forbidden}, reproducing the legacy {@code CDEMO-USRTYP-ADMIN} gate by
   * which {@code COUSR02C} ({@code CU02}) is reachable only from the administrator menu (Agent
   * Action Plan &sect;0.3.4, &sect;0.6.5). The request never reaches the handler; the denial is the
   * clean framework {@code 403} produced by Spring Security's {@code ExceptionTranslationFilter}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser(roles = "USER")
  void userGet_isForbidden() throws Exception {
    mockMvc.perform(get("/user-update")).andExpect(status().isForbidden());
  }
}
