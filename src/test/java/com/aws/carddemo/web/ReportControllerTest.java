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
import com.aws.carddemo.service.online.ReportService;
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
 * for {@link ReportController} &mdash; the modernized web equivalent of the legacy CICS
 * transaction-report submission transaction {@code CR00} ({@code legacy/app/cbl/CORPT00C.cbl}).
 * Report submission is a standard (non-admin) function, so the controller deliberately carries no
 * {@code @PreAuthorize}; this test pins down its HTTP-to-view/routing contract (Agent Action Plan
 * &sect;0.4.1 {@code ReportController <- CORPT00C}; &sect;0.6.5 COMMAREA-driven
 * pseudo-conversational navigation).
 *
 * <p><strong>Slice boundary.</strong> The {@link ReportService} collaborator is replaced with a
 * Mockito bean, so these tests assert only what the controller owns &mdash; the request mappings
 * ({@code GET /report}, {@code POST /report}), the resolved view name ({@code report}), the model
 * attribute name ({@code reportForm}), and the program-name&rarr;redirect routing inherited from
 * {@link BaseScreenController}. The report business rules (report-type resolution, date-range
 * validation, the {@code CONFIRM} handling, and the batch-job launch) live in {@link ReportService}
 * and are verified by its own unit test, not here.
 *
 * <p><strong>No batch types in the slice (intentional).</strong> In the legacy system {@code
 * CORPT00C} submitted the {@code TRANREPT} batch job by writing an in-line JCL deck to the {@code
 * JOBS} transient-data queue; in the modernized stack that launch is performed by a Spring Batch
 * {@code JobLauncher.run(...)} encapsulated entirely inside {@link ReportService}. The controller
 * is a thin presentation-layer adapter that references no batch type ({@code JobLauncher} / {@code
 * Job}), preserving strict {@code web -> service} layering (AAP &sect;0.3.3, &sect;0.4.2).
 * Consequently this test imports no {@code org.springframework.batch.*} types and needs no real
 * job: the mocked service makes the batch trigger irrelevant here, and the application's {@code
 * spring.batch.job.enabled=false} setting means no job would auto-run on context start regardless.
 *
 * <p><strong>Pseudo-conversational mapping.</strong> The single COBOL {@code CR00} transaction is
 * split across the two HTTP verbs the {@code report} screen submits (the {@code CORPT00C MAIN-PARA}
 * paint-vs-process split, L163-202):
 *
 * <ul>
 *   <li>{@code GET /report} is the first-entry paint (the COBOL {@code IF NOT CDEMO-PGM-REENTER}
 *       branch that performs {@code SEND-TRNRPT-SCREEN} with a blank options map), verified by
 *       {@link #get_rendersView()}.
 *   <li>{@code POST /report} is the re-entry process (the COBOL {@code RECEIVE-TRNRPT-SCREEN} +
 *       {@code EVALUATE EIBAID}); its two controller-owned outcomes &mdash; a {@code DFHPF3} return
 *       to the main menu and a {@code null}-return screen redisplay &mdash; are verified by {@link
 *       #post_pf3_redirectsToMenu()} and {@link #post_serviceReturnsNull_redisplays()}.
 * </ul>
 *
 * <p><strong>Security wiring.</strong> The real {@link SecurityConfig} filter chain is imported so
 * the test exercises the production authentication/CSRF posture rather than a relaxed test default.
 * Importing {@code SecurityConfig} instantiates its {@code
 * userDetailsService(UserSecurityRepository)} bean, which requires a {@link UserSecurityRepository}
 * bean that does not exist inside a {@code @WebMvcTest} slice; it is therefore supplied as a
 * Mockito bean. Because {@code SecurityConfig} leaves CSRF protection enabled and gates {@code
 * anyRequest().authenticated()}, every test method authenticates with {@link WithMockUser} (default
 * {@code ROLE_USER}, matching a standard report operator), the GET request needs no CSRF token, and
 * every state-changing POST carries one via {@code with(csrf())}.
 */
@WebMvcTest(ReportController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ReportControllerTest {

  /** Auto-configured {@link MockMvc} entry point for driving the controller without a servlet. */
  @Autowired private MockMvc mockMvc;

  /**
   * Mockito stand-in for the migrated {@code CORPT00C} business logic. Stubbing its {@code
   * processReport(...)} return value lets each test isolate a single controller routing branch (PF3
   * dispatch vs. redisplay) without exercising the real report-submission rules or launching any
   * Spring Batch job; on the GET paint path the default (do-nothing, {@code null}-returning) stub
   * leaves the freshly painted {@code reportForm} untouched.
   */
  @MockitoBean private ReportService reportService;

  /**
   * Mockito stand-in required because {@link Import @Import(SecurityConfig.class)} defines the
   * {@code userDetailsService(UserSecurityRepository)} bean; the repository it depends on is not
   * present in a {@code @WebMvcTest} slice and must be provided as a mock. It is never invoked here
   * because {@link WithMockUser} supplies the authenticated principal directly.
   */
  @MockitoBean private UserSecurityRepository userSecurityRepository;

  /**
   * {@code GET /report} on first entry renders the {@code report} view and places a fresh, non-null
   * {@code reportForm} on the model, reproducing the COBOL first-entry paint ({@code IF NOT
   * CDEMO-PGM-REENTER} &rarr; {@code MOVE LOW-VALUES TO CORPT0AO} &rarr; {@code
   * SEND-TRNRPT-SCREEN}, {@code CORPT00C} L177-181). The report transaction has no selected-record
   * hand-off, so the controller paints a plain empty {@link
   * com.aws.carddemo.dto.screen.ReportScreen}; the mocked service performs no mutation on this
   * path.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void get_rendersView() throws Exception {
    mockMvc
        .perform(get("/report"))
        .andExpect(status().isOk())
        .andExpect(view().name("report"))
        .andExpect(model().attributeExists("reportForm"));
  }

  /**
   * {@code POST /report} with PF3 returns to the main menu: when the service returns {@code
   * "COMEN01C"} (the {@code CORPT00C} {@code WHEN DFHPF3} branch that moves {@code COMEN01C} into
   * {@code CDEMO-TO-PROGRAM} and performs {@code RETURN-TO-PREV-SCREEN} / {@code EXEC CICS XCTL},
   * {@code CORPT00C} L187-189, L540-549), the controller issues the modernized {@code XCTL}
   * equivalent &mdash; a Spring redirect to that program's GET endpoint ({@code /menu}) via {@link
   * BaseScreenController#redirectFor(String)}.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_pf3_redirectsToMenu() throws Exception {
    given(reportService.processReport(any(), any(), any())).willReturn("COMEN01C");

    mockMvc
        .perform(post("/report").param("pfKey", "PF3").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/menu"));
  }

  /**
   * {@code POST /report} redisplays the screen when the service returns {@code null} (the COBOL
   * re-send paths: a date-range validation error, the {@code CONFIRM} prompt, the {@code WHEN
   * OTHER} invalid-key guard, or the "report submitted for printing" confirmation produced by the
   * service). The controller re-renders the {@code report} view with the {@code reportForm} object
   * instead of redirecting.
   *
   * @throws Exception if the simulated request cannot be performed
   */
  @Test
  @WithMockUser
  void post_serviceReturnsNull_redisplays() throws Exception {
    given(reportService.processReport(any(), any(), any())).willReturn(null);

    mockMvc
        .perform(post("/report").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("report"))
        .andExpect(model().attributeExists("reportForm"));
  }
}
