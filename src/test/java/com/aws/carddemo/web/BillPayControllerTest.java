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
import com.aws.carddemo.dto.CardDemoCommarea;
import com.aws.carddemo.repository.UserSecurityRepository;
import com.aws.carddemo.service.online.BillPayService;
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
 * for {@link BillPayController} &mdash; the modernized web equivalent of the legacy CICS
 * bill-payment transaction {@code CB00} ({@code legacy/app/cbl/COBIL00C.cbl}). Bill payment is a
 * standard (non-admin) function, so the controller deliberately carries no {@code @PreAuthorize};
 * this test pins down its HTTP-to-view/routing contract and the pseudo-conversational COMMAREA
 * pre-fill (Agent Action Plan &sect;0.4.1 {@code BillPayController <- COBIL00C}; &sect;0.6.5
 * COMMAREA state carried across screens; &sect;0.6.1 {@code PIC 9(11)} zero-padded fixed-width).
 *
 * <p><strong>Slice boundary.</strong> The {@link BillPayService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /bill-pay}, {@code POST /bill-pay}), the resolved view name ({@code bill-pay}), the
 * model attribute name ({@code billPayForm}), the COMMAREA&rarr;screen account-id pre-fill, and the
 * program-name&rarr;redirect routing inherited from {@link BaseScreenController}. The bill-payment
 * business rules (account/cross-reference lookup, balance read, the {@code CONFIRM} handling, the
 * transaction write, and the {@link java.math.BigDecimal} account-balance update) live in {@link
 * BillPayService} and are verified by its own unit test, not here.
 *
 * <p><strong>COMMAREA pre-fill parity.</strong> When the operator enters bill payment from an
 * account context, the COBOL {@code CDEMO-CB00-TRN-SELECTED} hand-off ({@code COBIL00C} L116-121)
 * moves the selected account id into {@code ACTIDINI} before painting the screen. The controller
 * reproduces this by formatting the {@code Long} COMMAREA account id with {@code String.format
 * ("%011d", ...)} &mdash; the zero-filled {@code PIC 9(11)} display (AAP &sect;0.6.1) &mdash; into
 * {@code billPayForm.actIdIn}. {@link #get_prefillsAccountIdFromCommarea()} seeds the COMMAREA into
 * the request session and asserts the resulting {@code 00000000123} value survives onto the model
 * (the mocked service performs no mutation on this path).
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard bill-payment operator), GET requests need no CSRF token,
 * and every state-changing POST carries one via {@code with(csrf())}.
 */
@WebMvcTest(BillPayController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class BillPayControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COBIL00C} business logic. Stubbing its {@code
   * processBillPay(...)} return value lets each test isolate a single controller routing branch
   * (dispatch vs. redisplay) without exercising the real bill-payment rules; on the GET pre-fill
   * path the default (do-nothing, {@code null}-returning) stub leaves the pre-filled account id
   * untouched.
   */
  @MockitoBean private BillPayService billPayService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /bill-pay} pre-fills the account-id field from the navigation COMMAREA, reproducing
   * the COBOL {@code CDEMO-CB00-TRN-SELECTED} hand-off ({@code COBIL00C} L116-121). A {@link
   * CardDemoCommarea} carrying {@code acctId = 123} is seeded into the request session under the
   * key {@code cardDemoCommarea} (the modernized COMMAREA home, AAP &sect;0.6.5); the controller
   * formats it with {@code String.format("%011d", 123L)} &mdash; the zero-filled {@code PIC 9(11)}
   * display (AAP &sect;0.6.1) &mdash; so the rendered {@code billPayForm} exposes {@code actIdIn =
   * "00000000123"}. The mocked service returns {@code null} and performs no mutation, so the
   * pre-filled value reaches the model unchanged.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_prefillsAccountIdFromCommarea() throws Exception {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setAcctId(123L);

    mockMvc
        .perform(get("/bill-pay").sessionAttr("cardDemoCommarea", commarea))
        .andExpect(status().isOk())
        .andExpect(view().name("bill-pay"))
        .andExpect(model().attribute("billPayForm", hasProperty("actIdIn", is("00000000123"))));
  }

  /**
   * {@code GET /bill-pay} on first entry without a pre-selected account (the COBOL empty-form
   * paint, {@code COBIL00C} L112-122 when {@code CDEMO-CB00-TRN-SELECTED} is blank) renders the
   * {@code bill-pay} view and places a fresh, non-null {@code billPayForm} on the model. With no
   * COMMAREA account id, the controller skips the pre-fill but still paints the screen.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_withoutCommareaAcctId_rendersView() throws Exception {
    mockMvc
        .perform(get("/bill-pay"))
        .andExpect(status().isOk())
        .andExpect(view().name("bill-pay"))
        .andExpect(model().attributeExists("billPayForm"));
  }

  /**
   * {@code POST /bill-pay} redisplays the screen when the service returns {@code null} (the COBOL
   * re-send paths: balance shown, confirm prompt, invalid-key, or a validation/informational
   * message). The controller re-renders the {@code bill-pay} view with the {@code billPayForm}
   * object instead of redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(billPayService.processBillPay(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/bill-pay").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("bill-pay"))
        .andExpect(model().attributeExists("billPayForm"));
  }

  /**
   * {@code POST /bill-pay} with PF3 returns to the main menu: when the service returns {@code
   * "COMEN01C"} (the {@code COBIL00C} {@code WHEN DFHPF3} return-to-caller path, defaulting to the
   * main-menu program), the controller issues the {@code EXEC CICS XCTL} equivalent &mdash; a
   * Spring redirect to that screen's GET endpoint ({@code /menu}) via {@link
   * BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_redirectsToMenu() throws Exception {
    given(billPayService.processBillPay(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/bill-pay").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }
}
