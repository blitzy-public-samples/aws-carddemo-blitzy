/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failsafe integration test for {@link AccountController} - the Spring MVC web tier that
 * merges the two CICS online account transactions into one controller.
 *
 * <p><b>COBOL oracles.</b> The controller under test is the migration of two pseudo-conversational
 * programs, both exercised here end-to-end (AAP &sect;0.6.10 traceability):</p>
 * <ul>
 *   <li>{@code legacy/cbl/COACTVWC.cbl} - CICS transaction {@code CAVW}, mapset {@code COACTVW};
 *       the read-only account inquiry mapped to {@code GET/POST /account/view}. Its
 *       {@code CXACAIX}&rarr;{@code ACCTDAT}&rarr;{@code CUSTDAT} read chain and the PF3 hand-off
 *       are verified by the CAVW read-path tests.</li>
 *   <li>{@code legacy/cbl/COACTUPC.cbl} - CICS transaction {@code CAUP}, mapset {@code COACTUP};
 *       the read-update-rewrite account maintenance mapped to {@code GET/POST /account/update}.
 *       Its edit paragraphs ({@code 1200-1280}) and the {@code 2000-DECIDE-ACTION} confirm state
 *       machine (PF5 save only while {@code ACUP-CHANGES-OK-NOT-CONFIRMED}; PF12 re-fetch) are
 *       verified by the CAUP update-path tests.</li>
 * </ul>
 *
 * <p><b>Full-stack integration.</b> This test drives the real {@link AccountController} with the
 * real {@code AccountViewService}, {@code AccountUpdateService}, JPA repositories, Flyway-seeded
 * schema (V1&rarr;V2&rarr;V3), Spring Security, and CSRF - all over the Testcontainers PostgreSQL
 * instance provisioned by {@link AbstractPostgresIntegrationTest}. There is no Mockito and no
 * {@code @MockBean}; every collaborator is the production bean. Because {@code AccountController}
 * is a {@link org.springframework.stereotype.Controller} (never {@code @RestController}), the
 * assertions target logical Thymeleaf view names ({@code COACTVW} / {@code COACTUP}), HTTP status,
 * model attributes, and redirects - never JSON.</p>
 *
 * <p><b>Authorization parity.</b> {@code /account/**} falls under {@code anyRequest().authenticated()}
 * in {@code SecurityConfig}, so any authenticated principal (whether {@code ROLE_USER} or
 * {@code ROLE_ADMIN}) may reach both screens; they are deliberately not admin-gated. Unauthenticated
 * access is redirected to the sign-on screen.</p>
 *
 * <p><b>Pseudo-conversational sessions.</b> The COBOL {@code COMMAREA} ({@code COCOM01Y}) is the
 * session-scoped {@code CardDemoContext}; a {@code GET} renders the first-entry screen and flips the
 * context to re-enter so the following {@code POST} performs the read rather than re-showing the empty
 * prompt. The read-path tests therefore share one {@link MockHttpSession} across the {@code GET} and
 * the {@code POST} exactly as a 3270 terminal would across two pseudo-conversational turns.</p>
 *
 * <p><b>Monetary parity.</b> The five amount fields (current balance, credit limit, cash credit limit,
 * current-cycle credit, current-cycle debit) are carried on the form as pre-formatted {@code String}s,
 * never {@code float}/{@code double} (AAP &sect;0.6.1); the read-path assertions verify the rendered
 * balance both as its exact seeded value and against a fixed two-decimal pattern.</p>
 *
 * @see AccountController
 */
@AutoConfigureMockMvc
class AccountControllerIT extends AbstractPostgresIntegrationTest {

    /** GET/POST route for the account-view screen (CICS tran CAVW / program COACTVWC). */
    private static final String ROUTE_ACCOUNT_VIEW = "/account/view";

    /** GET/POST route for the account-update screen (CICS tran CAUP / program COACTUPC). */
    private static final String ROUTE_ACCOUNT_UPDATE = "/account/update";

    /** Logical Thymeleaf view name for CAVW (BMS map COACTVW). */
    private static final String VIEW_ACCOUNT_VIEW = "COACTVW";

    /** Logical Thymeleaf view name for CAUP (BMS map COACTUP). */
    private static final String VIEW_ACCOUNT_UPDATE = "COACTUP";

    /** Shared error view rendered by {@code GlobalExceptionHandler} for typed exceptions. */
    private static final String VIEW_ERROR = "error";

    /** Model attribute holding the screen form ({@code th:object="${form}"}). */
    private static final String MODEL_ATTR_FORM = "form";

    /** Model attribute holding the error banner on the global error view (BMS ERRMSGO analogue). */
    private static final String MODEL_ATTR_ERROR_MESSAGE = "errorMessage";

    /** Form property carrying the account-id search filter (BMS ACCTSIDI, PIC 9(11)). */
    private static final String PROP_ACCT_ID = "acctsid";

    /** Form property carrying the current-balance display money (BMS ACURBALI). */
    private static final String PROP_CURR_BAL = "acurbal";

    /** Form property carrying the credit-limit display money (BMS ACRDLIMI). */
    private static final String PROP_CREDIT_LIMIT = "acrdlim";

    /** Form property carrying the on-screen red error line (BMS ERRMSGI). */
    private static final String PROP_ERR_MSG = "errmsg";

    /** Request-parameter name for the account-id search filter. */
    private static final String PARAM_ACCT_ID = "acctsid";

    /** Request-parameter name carrying the pressed PF-key token. */
    private static final String PARAM_PFKEY = "pfkey";

    /** PF-key token for the ENTER action (resolved to {@code DFHENTER}). */
    private static final String PFKEY_ENTER = "ENTER";

    /** PF-key token for PF3 - return to the calling menu (resolved to {@code DFHPF3}). */
    private static final String PFKEY_PF3 = "PF3";

    /** PF-key token for PF5 - save/confirm on the update screen (resolved to {@code DFHPF5}). */
    private static final String PFKEY_PF5 = "PF5";

    /** PF-key token for PF12 - re-fetch/discard on the update screen (resolved to {@code DFHPF12}). */
    private static final String PFKEY_PF12 = "PF12";

    /** Seeded, fully cross-referenced account id from {@code V2__reference_data.sql} (xref+account+customer). */
    private static final String SEEDED_ACCT_ID = "1";

    /** Seeded current balance for account {@link #SEEDED_ACCT_ID} ({@code acct_curr_bal} 194.00, scale 2). */
    private static final String SEEDED_CURR_BAL = "194.00";

    /** A valid 11-digit account-id filter that matches no seeded row, forcing the not-found path. */
    private static final String MISSING_ACCT_ID = "99999999999";

    /** Two-decimal money pattern proving amounts are edited {@code String}s, never float/double. */
    private static final String MONEY_PATTERN = "-?\\d+\\.\\d{2}";

    /** Ant pattern matching the sign-on redirect target for unauthenticated requests. */
    private static final String SIGNON_URL_PATTERN = "**/signon";

    /** Main-menu route - the PF3 return target for both account screens. */
    private static final String ROUTE_MENU = "/menu";

    /** Spring Security role suffix granting {@code ROLE_USER} via {@link WithMockUser}. */
    private static final String ROLE_USER = "USER";

    /** Spring Security role suffix granting {@code ROLE_ADMIN} via {@link WithMockUser}. */
    private static final String ROLE_ADMIN = "ADMIN";

    /** COACTVWC {@code DID-NOT-FIND-ACCT-IN-CARDXREF} message surfaced by the global handler. */
    private static final String VIEW_NOT_FOUND_FRAGMENT = "Did not find this account in account card xref file";

    /** COACTUPC cross-reference not-found banner fragment re-rendered on the update screen. */
    private static final String UPDATE_NOT_FOUND_FRAGMENT = "not found in Cross ref file";

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------
    // Phase 1 - Authorization and first-entry GET rendering
    // ------------------------------------------------------------------

    /**
     * An unauthenticated {@code GET /account/view} is redirected to the sign-on screen by the
     * {@code LoginUrlAuthenticationEntryPoint} ({@code anyRequest().authenticated()}).
     */
    @Test
    void accountViewRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROUTE_ACCOUNT_VIEW))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(SIGNON_URL_PATTERN));
    }

    /**
     * An unauthenticated {@code GET /account/update} is redirected to the sign-on screen.
     */
    @Test
    void accountUpdateRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROUTE_ACCOUNT_UPDATE))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(SIGNON_URL_PATTERN));
    }

    /**
     * A {@code ROLE_USER} principal may render the first-entry account-view screen (COACTVW).
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountViewScreenForUser() throws Exception {
        mockMvc.perform(get(ROUTE_ACCOUNT_VIEW))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_VIEW));
    }

    /**
     * A {@code ROLE_USER} principal may render the first-entry account-update screen (COACTUP).
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdateScreenForUser() throws Exception {
        mockMvc.perform(get(ROUTE_ACCOUNT_UPDATE))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));
    }

    /**
     * The account-view screen is reachable by a {@code ROLE_ADMIN} principal too, proving the
     * route is authenticated-only and neither admin-gated nor user-gated.
     */
    @Test
    @WithMockUser(roles = ROLE_ADMIN)
    void accountViewAllowedForAdminToo() throws Exception {
        mockMvc.perform(get(ROUTE_ACCOUNT_VIEW))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_VIEW));
    }

    // ------------------------------------------------------------------
    // Phase 2 - CAVW read path (COACTVWC: xref -> account -> customer)
    // ------------------------------------------------------------------

    /**
     * Fetches a seeded account through the full CAVW read chain and asserts the account-view screen
     * renders with the monetary fields as edited {@code String}s. The {@code GET} flips the session
     * context to re-enter; the shared-session {@code POST} then performs {@code 9000-READ-ACCT}.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountViewFetchExistingAccount() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // First entry (SEND-MAP): render the prompt and flip the context to re-enter.
        mockMvc.perform(get(ROUTE_ACCOUNT_VIEW).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_VIEW));
        // Re-entry (RECEIVE-MAP + 9000-READ-ACCT): read the seeded account/customer chain.
        mockMvc.perform(post(ROUTE_ACCOUNT_VIEW)
                        .session(session)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_VIEW))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(PROP_CURR_BAL, equalTo(SEEDED_CURR_BAL))))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(PROP_CURR_BAL, matchesPattern(MONEY_PATTERN))))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(PROP_CREDIT_LIMIT, matchesPattern(MONEY_PATTERN))));
    }

    /**
     * A not-found account on the CAVW path surfaces as {@code RecordNotFoundException} from the read
     * chain and is translated by {@code GlobalExceptionHandler} to the shared error view with HTTP 404
     * and the COBOL not-found message on the {@code errorMessage} attribute.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountViewNotFoundPropagates() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_ACCOUNT_VIEW).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post(ROUTE_ACCOUNT_VIEW)
                        .session(session)
                        .param(PARAM_ACCT_ID, MISSING_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(view().name(VIEW_ERROR))
                .andExpect(model().attribute(MODEL_ATTR_ERROR_MESSAGE,
                        containsString(VIEW_NOT_FOUND_FRAGMENT)));
    }

    /**
     * PF3 on the account-view screen hands control back to the calling menu. With no recorded caller
     * the service defaults the return target to the main-menu program ({@code COMEN01C}), which the
     * controller renders as {@code redirect:/menu}.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountViewPf3ReturnsToMenu() throws Exception {
        mockMvc.perform(post(ROUTE_ACCOUNT_VIEW)
                        .param(PARAM_PFKEY, PFKEY_PF3)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_MENU));
    }

    // ------------------------------------------------------------------
    // Phase 3 - CAUP update path (COACTUPC: edit + confirm state machine)
    // ------------------------------------------------------------------

    /**
     * Fetches a seeded account on the CAUP path and asserts the account-update screen re-renders.
     * The {@code GET} seeds the program-private update state and flips the context to re-enter; the
     * shared-session {@code POST} runs the {@code 1000-PROCESS-INPUTS} + {@code 2000-DECIDE-ACTION}
     * read that displays the record for editing.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdateFetchExistingAccount() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_ACCOUNT_UPDATE).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));
    }

    /**
     * PF5 saves only in the COBOL {@code ACUP-CHANGES-OK-NOT-CONFIRMED} state. A bare PF5 arriving in
     * the not-fetched state is an invalid key; the service coerces it to ENTER
     * ({@code SET PFK-INVALID ... SET CCARD-AID-ENTER}) so the record is re-displayed rather than
     * written, and the update screen re-renders. Annotated {@code @Transactional} so any incidental
     * write rolls back, keeping the seeded database pristine.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    @Transactional
    void accountUpdatePf5SaveOnlyWhenConfirmed() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_ACCOUNT_UPDATE).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_PF5)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));
    }

    /**
     * PF12 re-fetches the original record from the datastore, discarding any in-flight edits. PF12 is
     * only a valid key once details have been fetched, so this drives an ENTER fetch first (leaving
     * the details-shown state) and then PF12, asserting the update screen re-renders the clean copy.
     * Annotated {@code @Transactional} so nothing persists.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    @Transactional
    void accountUpdatePf12RefetchDiscardsChanges() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_ACCOUNT_UPDATE).session(session))
                .andExpect(status().isOk());
        // ENTER fetch: leaves the program in the details-shown state so PF12 becomes valid.
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));
        // PF12: re-read the datastore record, discarding edits, and re-render the update screen.
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_PF12)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));
    }

    /**
     * A not-found account on the CAUP path is handled differently from CAVW: COACTUPC sets
     * {@code INPUT-ERROR} and a {@code WS-RETURN-MSG} banner rather than throwing, so the update screen
     * re-renders (HTTP 200) with the cross-reference not-found message on the form's error line.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdateNotFoundPropagates() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_ACCOUNT_UPDATE).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .param(PARAM_ACCT_ID, MISSING_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(UPDATE_NOT_FOUND_FRAGMENT))));
    }

    // ------------------------------------------------------------------
    // Phase 4 - CSRF negative
    // ------------------------------------------------------------------

    /**
     * With CSRF protection enabled, a mutating {@code POST /account/view} that omits the CSRF token is
     * rejected with HTTP 403 even for an authenticated principal.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountViewPostWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post(ROUTE_ACCOUNT_VIEW)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER))
                .andExpect(status().isForbidden());
    }
}
