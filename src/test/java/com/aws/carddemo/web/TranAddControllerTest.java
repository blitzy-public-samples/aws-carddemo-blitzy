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
import com.aws.carddemo.service.online.TranAddService;
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
 * for {@link TranAddController} &mdash; the modernized web equivalent of the legacy CICS
 * transaction-add transaction {@code CT02} ({@code legacy/app/cbl/COTRN02C.cbl}). Add-transaction
 * is a standard (non-admin) function reached from the transaction-list / card context, so the
 * controller deliberately carries no {@code @PreAuthorize}; this test pins down its
 * HTTP-to-view/routing contract (Agent Action Plan &sect;0.4.1 {@code TranAddController <-
 * COTRN02C}; &sect;0.6.5 COMMAREA navigation; &sect;0.6.1 fixed-width decimal/format fidelity).
 *
 * <p><strong>Slice boundary.</strong> The {@link TranAddService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /transaction-add}, {@code POST /transaction-add}), the resolved view name ({@code
 * transaction-add}), the model attribute name ({@code transactionAddForm}), the COMMAREA-driven
 * card-number pre-fill, and the program-name&rarr;redirect routing inherited from {@link
 * BaseScreenController}. The cross-reference resolution, the field-by-field validation order, the
 * {@code CONFIRM} (Y/N) handling, and the {@link java.math.BigDecimal} transaction insert all live
 * in {@link TranAddService} and are verified by its own unit test, not here.
 *
 * <p><strong>The commarea pre-fill (the headline behavior; AAP &sect;0.6.5).</strong> When the user
 * drills into "add transaction" from a selected-card context, the legacy program copies the
 * COMMAREA-carried card number into the entry field ({@code COTRN02C} L124-129, {@code MOVE
 * CDEMO-CT02-TRN-SELECTED TO CARDNINI OF COTRN2AI}). The controller reproduces this on {@code GET}
 * by reading the session {@link CardDemoCommarea} and, when its {@link
 * CardDemoCommarea#getCardNum() card number} ({@code PIC 9(16)}) is present, pre-filling {@code
 * cardNin} zero-padded to 16 digits via {@code String.format("%016d", ...)}. The {@link
 * #get_prefillsCardNumberFromCommarea()} test seeds the request session under the exact production
 * key {@code "cardDemoCommarea"} and asserts the resulting fixed-width string, guarding the COBOL
 * {@code PIC 9(16)} display fidelity (AAP &sect;0.6.1) at the web boundary.
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard add-transaction user), {@code GET} requests need no CSRF
 * token, and every state-changing {@code POST} carries one via {@code with(csrf())}.
 */
@WebMvcTest(TranAddController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class TranAddControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code COTRN02C} business logic. Stubbing its {@code
   * processTranAdd(...)} return value lets each {@code POST} test isolate a single controller
   * routing branch (dispatch vs. redisplay) without exercising the real add-transaction rules; on
   * the {@code GET} paint paths the default (do-nothing, {@code null}-returning) stub is sufficient
   * because the controller ignores the {@code GET} return value and the mock never mutates the
   * pre-filled screen.
   */
  @MockitoBean private TranAddService tranAddService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /transaction-add} pre-fills the card-number field from the pseudo-conversational
   * COMMAREA, reproducing the {@code COTRN02C} selected-card hand-off ({@code MOVE
   * CDEMO-CT02-TRN-SELECTED TO CARDNINI}, L124-129; AAP &sect;0.6.5).
   *
   * <p>A {@link CardDemoCommarea} carrying card number {@code 123L} (a {@code PIC 9(16)} value) is
   * seeded into the request session under the exact production key {@code "cardDemoCommarea"} (the
   * {@code BaseScreenController.SESSION_COMMAREA_KEY}). The controller reads it via {@code
   * getCommarea(...)} and pre-fills {@code cardNin} as {@code String.format("%016d", 123L)} &mdash;
   * the zero-padded, 16-digit string {@code "0000000000000123"} that mirrors the COBOL {@code PIC
   * 9(16)} display (AAP &sect;0.6.1). The assertion verifies the {@code transactionAddForm} model
   * attribute exposes exactly that {@code cardNin}, confirming both the state carry and the
   * fixed-width format at the web boundary.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_prefillsCardNumberFromCommarea() throws Exception {
    CardDemoCommarea commarea = new CardDemoCommarea();
    commarea.setCardNum(123L);

    mockMvc
        .perform(get("/transaction-add").sessionAttr("cardDemoCommarea", commarea))
        .andExpect(status().isOk())
        .andExpect(view().name("transaction-add"))
        .andExpect(
            model()
                .attribute("transactionAddForm", hasProperty("cardNin", is("0000000000000123"))));
  }

  /**
   * {@code GET /transaction-add} without a COMMAREA card number renders the clean entry screen (the
   * {@code COTRN02C} first-entry paint with no selected card). With no session commarea the
   * controller's {@code getCommarea(...)} creates a fresh, empty one whose card number is {@code
   * null}, so no pre-fill occurs; this test confirms the screen still renders &mdash; {@code 200},
   * the {@code transaction-add} view, and a non-null {@code transactionAddForm} backing object.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_withoutCommareaCardNum_rendersView() throws Exception {
    mockMvc
        .perform(get("/transaction-add"))
        .andExpect(status().isOk())
        .andExpect(view().name("transaction-add"))
        .andExpect(model().attributeExists("transactionAddForm"));
  }

  /**
   * {@code POST /transaction-add} redisplays the screen when the service returns {@code null} (a
   * validation failure, the confirm prompt, an invalid-key message, or the green success
   * confirmation &mdash; every {@code COTRN02C SEND-TRNADD-SCREEN} path). The controller re-renders
   * the {@code transaction-add} view with the form-backing object instead of redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(tranAddService.processTranAdd(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/transaction-add").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("transaction-add"))
        .andExpect(model().attributeExists("transactionAddForm"));
  }

  /**
   * {@code POST /transaction-add} with PF3 returns to the main menu: when the service returns the
   * program name {@code "COMEN01C"} (the {@code COTRN02C} PF3 return-to-caller path), the
   * controller issues the {@code EXEC CICS XCTL} equivalent &mdash; a Spring redirect to that
   * program's GET endpoint ({@code /menu}) via {@link BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_redirectsToMenu() throws Exception {
    given(tranAddService.processTranAdd(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/transaction-add").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }
}
