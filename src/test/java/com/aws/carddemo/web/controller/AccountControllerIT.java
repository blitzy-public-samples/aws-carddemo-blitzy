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
import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.screen.COACTUPForm;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CustomerRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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

    /** Model attribute holding the screen form ({@code th:object="${form}"}). */
    private static final String MODEL_ATTR_FORM = "form";

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

    /**
     * Seeded, fully cross-referenced account id from {@code V2__reference_data.sql} (xref+account+customer),
     * expressed as the full eleven-digit {@code PIC 9(11)} filter. Both account screens reject a shorter
     * entry (the legacy character {@code MOVE} into the fixed {@code X(11)} field leaves trailing spaces
     * and fails {@code IS NUMERIC}; QA finding w002 m1), so the exact eleven-digit form is required.
     */
    private static final String SEEDED_ACCT_ID = "00000000001";

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

    // --- Confirmation-integrity (findings #48, #10) constants ---

    /** Model attribute the controller sets to {@code true} only in the {@code CHANGES_OK_NOT_CONFIRMED} state. */
    private static final String MODEL_ATTR_CONFIRM_MODE = "confirmMode";

    /** Request-parameter / form property carrying the single-use confirmation token (hidden field). */
    private static final String PARAM_CONFIRM_TOKEN = "confirmToken";

    /** Primary key (as a JPA id) of the seeded, fully cross-referenced account under test. */
    private static final long ACCT1_KEY = 1L;

    /** Primary key of the customer linked to {@link #ACCT1_KEY} via {@code card_xref} (cust_id 1). */
    private static final long CUST1_KEY = 1L;

    /**
     * The seeded account 1 rendered as the 11-digit zero-padded filter CAUP's {@code 1210-EDIT-ACCOUNT}
     * requires ("Account Number if supplied must be a 11 digit Non-Zero Number"); parses to id 1.
     */
    private static final String CAUP_ACCT_ID = "00000000001";

    /** A second seeded account id (11-digit) used as the swapped-target victim; must never be written. */
    private static final String SWAP_ACCT_ID = "00000000002";

    /** Primary key of the swapped-target victim account. */
    private static final long ACCT2_KEY = 2L;

    /** Seeded credit limit of {@link #ACCT1_KEY} ({@code acct_credit_limit} 2020.00); the pre-edit value. */
    private static final BigDecimal SEED_ACCT1_CREDIT_LIMIT = new BigDecimal("2020.00");

    /** Seeded credit limit of {@link #ACCT2_KEY} ({@code acct_credit_limit} 6130.00); must stay untouched. */
    private static final BigDecimal SEED_ACCT2_CREDIT_LIMIT = new BigDecimal("6130.00");

    /** The meaningful, valid monetary edit applied through the confirm flow (finding #48): 1000.00. */
    private static final String EDITED_CREDIT_LIMIT = "1000.00";

    /** Expected committed credit limit after a legitimate confirm+PF5 write. */
    private static final BigDecimal COMMITTED_CREDIT_LIMIT = new BigDecimal("1000.00");

    /** Expected committed cash credit limit (form acshlim 500.00). */
    private static final BigDecimal COMMITTED_CASH_LIMIT = new BigDecimal("500.00");

    /** Expected committed current balance (form acurbal 250.00). */
    private static final BigDecimal COMMITTED_CURR_BAL = new BigDecimal("250.00");

    /** A tampered, well-formed credit limit posted on the PF5 turn that MUST be ignored (overpost, #10). */
    private static final String OVERPOST_CREDIT_LIMIT = "9999999.99";

    /** A second tampered credit limit used to prove a replayed token performs no further write (#10). */
    private static final String REPLAY_CREDIT_LIMIT = "8888888.88";

    /** A syntactically valid but wrong 64-hex confirmation token used to prove forged tokens are rejected. */
    private static final String FORGED_TOKEN =
            "0000000000000000000000000000000000000000000000000000000000000000";

    /** Fragment of {@code MSG_CONFIRM_INTEGRITY} re-rendered on the red error line when a token is rejected. */
    private static final String CONFIRM_INTEGRITY_FRAGMENT = "Confirmation could not be validated";

    /** Fragment of the finding #11 neutral length banner re-rendered when an over-width field is rejected. */
    private static final String FIELD_LENGTH_FRAGMENT = "exceeds the maximum length";

    /** A 12-digit account id (one over the {@code acctsid} PIC 9(11) / {@code @Size(max = 11)} width). */
    private static final String OVER_WIDTH_ACCT_ID = "123456789012";

    /** Non-submitted, non-allowlisted COACTUPForm display property used to prove mass-assignment is blocked. */
    private static final String OVERPOST_PROP_FKEYS = "fkeys";

    /** Hostile value an attacker tries to inject into the non-allowlisted {@link #OVERPOST_PROP_FKEYS} property. */
    private static final String OVERPOST_FKEYS_VALUE = "INJECTED_FKEYS";

    /** Fragment of {@code MSG_DATA_CHANGED} re-rendered when the 9700 re-read detects concurrent drift. */
    private static final String DATA_CHANGED_FRAGMENT = "Record changed by some one else";

    /** Trimmed first name committed by a legitimate write (form acsfnam JOHN); DB column is {@code CHAR(25)}. */
    private static final String COMMITTED_FIRST_NAME = "JOHN";

    /** Trimmed last name committed by a legitimate write (form acslnam DOE). */
    private static final String COMMITTED_LAST_NAME = "DOE";

    /** SSN committed by a legitimate write (form actssn1/2/3 -> 123456789). */
    private static final long COMMITTED_SSN = 123456789L;

    /** FICO score committed by a legitimate write (form acstfco 700). */
    private static final int COMMITTED_FICO = 700;

    /** Injected drift status written directly to the datastore between confirm and PF5 to force 9700 drift. */
    private static final String DRIFT_STATUS = "N";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

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
     * A not-found account on the CAVW path is re-displayed <em>inline</em> on the
     * {@code COACTVW} screen with HTTP&nbsp;200 and the byte-exact COBOL not-found message on
     * the form's {@code errmsg} line ({@code ERRMSGO}), mirroring {@code COACTVWC}'s
     * {@code 1100-SEND-MAP} re-display rather than abending to a full-page error (review
     * finding #1). {@code AccountViewService.mainEntry} catches the read chain's
     * {@code RecordNotFoundException} and routes it to the same pseudo-conversational
     * re-display the mainframe used. A fully-unknown account fails the
     * {@code 9200-GETCARDXREF-BYACCT} cross-reference read first, so the surfaced text is the
     * cross-reference not-found message (not the account-master one).
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountViewNotFoundReDisplaysInline() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_ACCOUNT_VIEW).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post(ROUTE_ACCOUNT_VIEW)
                        .session(session)
                        .param(PARAM_ACCT_ID, MISSING_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_VIEW))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(VIEW_NOT_FOUND_FRAGMENT))));
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

    // ------------------------------------------------------------------
    // Phase 5 - CAUP confirmation integrity (findings #48 monetary binding, #10 overposting)
    //
    // These tests drive the real pseudo-conversational edit->confirm->PF5 flow end-to-end against the
    // Testcontainers PostgreSQL instance (no @Transactional; committed state is isolated by the base
    // class per-test clean+migrate). They verify (a) that the five BMS FSET,UNPROT monetary inputs now
    // bind and reach the transactional write as BigDecimal (finding #48), and (b) that the single-use
    // server-side confirmation token + server-carried pending snapshot defeat overposting, target
    // swapping, token replay, forged tokens, and concurrent-change drift (finding #10, CWE-20/CWE-639),
    // exactly reproducing the mainframe contract where the confirmation-screen fields are protected and
    // the PF5 turn can only re-present the validated ACUP-NEW-DETAILS.
    // ------------------------------------------------------------------

    /**
     * A fully valid COACTUP edit for the seeded account 1: every one of the 24 field edits passes
     * (dates are real, valid calendar dates validated by the production {@code DateConversionService}),
     * and the credit limit is changed from the seeded 2020.00 to {@link #EDITED_CREDIT_LIMIT} so
     * {@code 1205-COMPARE-OLD-NEW} detects a change and the edit pass advances to the confirm state.
     * A fresh map is returned on each call so a test may tamper with a single field in isolation.
     *
     * @return a mutable request-parameter map of the account-update form fields
     */
    private static MultiValueMap<String, String> validEditForm() {
        LinkedMultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("acctsid", CAUP_ACCT_ID);
        p.add("acsttus", "Y");
        p.add("acrdlim", EDITED_CREDIT_LIMIT); // the meaningful, valid monetary change (finding #48)
        p.add("acshlim", "500.00");
        p.add("acurbal", "250.00");
        p.add("acrcycr", "100.00");
        p.add("acrcydb", "50.00");
        p.add("opnyear", "2020");
        p.add("opnmon", "01");
        p.add("opnday", "15");
        p.add("expyear", "2025");
        p.add("expmon", "12");
        p.add("expday", "31");
        p.add("risyear", "2021");
        p.add("rismon", "06");
        p.add("risday", "01");
        p.add("aaddgrp", "GROUP01");
        p.add("acstnum", "000000001");
        p.add("actssn1", "123");
        p.add("actssn2", "45");
        p.add("actssn3", "6789");
        p.add("dobyear", "1980");
        p.add("dobmon", "05");
        p.add("dobday", "20");
        p.add("acstfco", "700");
        p.add("acsfnam", "JOHN");
        p.add("acsmnam", "QUINCY");
        p.add("acslnam", "DOE");
        p.add("acsadl1", "123 MAIN ST");
        p.add("acsstte", "CA");
        p.add("acsadl2", "APT 4");
        p.add("acszipc", "90001");
        p.add("acscity", "LOS ANGELES");
        p.add("acsctry", "USA");
        p.add("acsph1a", "212");
        p.add("acsph1b", "555");
        p.add("acsph1c", "1234");
        p.add("acsgovt", "GOVT123");
        p.add("acsph2a", "");
        p.add("acsph2b", "");
        p.add("acsph2c", "");
        p.add("acseftc", "1234567890");
        p.add("acspflg", "Y");
        return p;
    }

    /**
     * Extracts the single-use confirmation token the controller echoed onto the rendered form model
     * attribute (the hidden {@code confirmToken} field bound with {@code th:field}).
     *
     * @param result the {@link MvcResult} of a confirm-state render
     * @return the issued confirmation token
     */
    private static String tokenFrom(MvcResult result) {
        COACTUPForm form = (COACTUPForm) result.getModelAndView().getModel().get(MODEL_ATTR_FORM);
        return form.getConfirmToken();
    }

    /**
     * Drives the first three pseudo-conversational turns - first-entry GET, an ENTER fetch of the
     * seeded account, and an ENTER edit pass - leaving the program in the armed confirm state, and
     * returns the issued single-use token. Asserts the confirm state was actually reached (so a
     * date-edit regression would fail fast here rather than silently skipping the write path).
     *
     * @param session the shared HTTP session standing in for the 3270 pseudo-conversation
     * @return the non-blank single-use confirmation token issued by the edit pass
     * @throws Exception if the MockMvc exchange fails
     */
    private String driveToConfirm(MockHttpSession session) throws Exception {
        // Turn 1 (GET, first entry): render the empty prompt and flip the context to re-enter.
        mockMvc.perform(get(ROUTE_ACCOUNT_UPDATE).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));
        // Turn 2 (POST ENTER, fetch): 9000-READ-ACCT stores the old snapshot + server-carried identity.
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .param(PARAM_ACCT_ID, CAUP_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));
        // Turn 3 (POST ENTER, edits): the full 1200 edit pass validates and advances to the confirm
        // state, arming the server-side pending snapshot + single-use token echoed to the form.
        MvcResult confirm = mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_CONFIRM_MODE, equalTo(Boolean.TRUE)))
                .andReturn();
        String token = tokenFrom(confirm);
        assertThat(token)
                .as("the confirm turn must issue a non-blank single-use confirmation token")
                .isNotBlank();
        return token;
    }

    /**
     * (Finding #48 + #10 happy path) A real edit&rarr;confirm&rarr;PF5 flow commits BOTH the account and
     * its customer atomically with the edited values, proving the monetary inputs now bind and reach the
     * transactional write as scale-2 {@link BigDecimal} (never float/double).
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdateEditConfirmPf5CommitsAccountAndCustomerAtomically() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session);

        // Turn 4 (POST PF5 + issued token): commit the server-carried validated snapshot.
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PFKEY_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_CONFIRM_MODE, equalTo(Boolean.FALSE)));

        Account saved = accountRepository.findById(ACCT1_KEY).orElseThrow();
        assertThat(saved.getCreditLimit()).isInstanceOf(BigDecimal.class);
        assertThat(saved.getCreditLimit()).isEqualByComparingTo(COMMITTED_CREDIT_LIMIT);
        assertThat(saved.getCreditLimit().scale()).isEqualTo(2);
        assertThat(saved.getCashCreditLimit()).isEqualByComparingTo(COMMITTED_CASH_LIMIT);
        assertThat(saved.getCurrBal()).isEqualByComparingTo(COMMITTED_CURR_BAL);

        Customer savedCust = customerRepository.findById(CUST1_KEY).orElseThrow();
        assertThat(savedCust.getFirstName().trim()).isEqualTo(COMMITTED_FIRST_NAME);
        assertThat(savedCust.getLastName().trim()).isEqualTo(COMMITTED_LAST_NAME);
        assertThat(savedCust.getSsn()).isEqualTo(COMMITTED_SSN);
        assertThat(savedCust.getFicoCreditScore()).isEqualTo(COMMITTED_FICO);
    }

    /**
     * (Finding #10 overpost) A PF5 turn that carries a valid token but a tampered, well-formed credit
     * limit must be ignored: because {@code editMapInputs} skips re-validation in the confirm state, only
     * the server-carried pending snapshot (the confirmed 1000.00) may be written - never the re-post.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdatePf5OverpostIsIgnoredCommittingServerCarriedValue() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session);

        MultiValueMap<String, String> tampered = validEditForm();
        tampered.set("acrdlim", OVERPOST_CREDIT_LIMIT);
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .params(tampered)
                        .param(PARAM_PFKEY, PFKEY_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));

        Account saved = accountRepository.findById(ACCT1_KEY).orElseThrow();
        assertThat(saved.getCreditLimit())
                .as("the PF5 overpost must be ignored; the confirmed value is committed")
                .isEqualByComparingTo(COMMITTED_CREDIT_LIMIT);
        assertThat(saved.getCreditLimit()).isNotEqualByComparingTo(new BigDecimal(OVERPOST_CREDIT_LIMIT));
    }

    /**
     * (Finding #10 swapped target) A PF5 turn that swaps the account-id filter to a different seeded
     * account must still write the server-carried identity captured at fetch (account 1), leaving the
     * swapped-in victim account untouched. {@code editAccount} runs only before the fetch, so the client
     * cannot re-aim the write on the confirm turn.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdatePf5SwappedTargetWritesServerCarriedIdentityOnly() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session); // server-carried target = account 1

        MultiValueMap<String, String> swapped = validEditForm();
        swapped.set("acctsid", SWAP_ACCT_ID);
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .params(swapped)
                        .param(PARAM_PFKEY, PFKEY_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));

        assertThat(accountRepository.findById(ACCT1_KEY).orElseThrow().getCreditLimit())
                .as("the server-carried target (account 1) is the one written")
                .isEqualByComparingTo(COMMITTED_CREDIT_LIMIT);
        assertThat(accountRepository.findById(ACCT2_KEY).orElseThrow().getCreditLimit())
                .as("the swapped-in victim account must be untouched")
                .isEqualByComparingTo(SEED_ACCT2_CREDIT_LIMIT);
    }

    /**
     * (Finding #10 replay) After a legitimate PF5 commit consumes the single-use token, replaying the
     * same token with a different tampered value performs no further write - the state has advanced past
     * confirm and the token is cleared, so the second attempt cannot re-drive the transactional write.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdatePf5ReplayedTokenPerformsNoFurtherWrite() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session);

        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PFKEY_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token)
                        .with(csrf()))
                .andExpect(status().isOk());
        assertThat(accountRepository.findById(ACCT1_KEY).orElseThrow().getCreditLimit())
                .isEqualByComparingTo(COMMITTED_CREDIT_LIMIT);

        MultiValueMap<String, String> replay = validEditForm();
        replay.set("acrdlim", REPLAY_CREDIT_LIMIT);
        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .params(replay)
                        .param(PARAM_PFKEY, PFKEY_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token)
                        .with(csrf()))
                .andExpect(status().isOk());
        Account after = accountRepository.findById(ACCT1_KEY).orElseThrow();
        assertThat(after.getCreditLimit())
                .as("a replayed, consumed token must not drive a second write")
                .isEqualByComparingTo(COMMITTED_CREDIT_LIMIT);
        assertThat(after.getCreditLimit()).isNotEqualByComparingTo(new BigDecimal(REPLAY_CREDIT_LIMIT));
    }

    /**
     * (Finding #10 forged token) A PF5 turn carrying a syntactically valid but wrong token is rejected:
     * the server writes nothing, keeps the operator in the confirm window, re-issues a fresh token, and
     * re-renders the red integrity banner.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdatePf5ForgedTokenIsRejectedWithNoWrite() throws Exception {
        MockHttpSession session = new MockHttpSession();
        driveToConfirm(session); // arm the confirm state, then submit a forged token instead of the issued one

        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PFKEY_PF5)
                        .param(PARAM_CONFIRM_TOKEN, FORGED_TOKEN)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_CONFIRM_MODE, equalTo(Boolean.TRUE)))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(CONFIRM_INTEGRITY_FRAGMENT))));

        assertThat(accountRepository.findById(ACCT1_KEY).orElseThrow().getCreditLimit())
                .as("a forged confirmation token must not write anything")
                .isEqualByComparingTo(SEED_ACCT1_CREDIT_LIMIT);
    }

    /**
     * (Finding #10 drift) A concurrent change committed by another writer between the snapshot (fetch)
     * and PF5 is detected by the {@code 9700} re-read: the confirm turn re-displays with the
     * "record changed" banner and applies no edit, and the injected drift persists (proving no overwrite).
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdatePf5ConcurrentDriftIsRejectedWithNoSave() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session);

        // Inject a concurrent change: another writer flips the account status after the snapshot was
        // taken (turn 2) but before PF5. Committed in its own transaction (this test is not @Transactional).
        Account concurrent = accountRepository.findById(ACCT1_KEY).orElseThrow();
        concurrent.setActiveStatus(DRIFT_STATUS);
        accountRepository.saveAndFlush(concurrent);

        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PFKEY_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(DATA_CHANGED_FRAGMENT))));

        Account after = accountRepository.findById(ACCT1_KEY).orElseThrow();
        assertThat(after.getCreditLimit())
                .as("a concurrent-change (drift) confirmation must not apply the edit")
                .isEqualByComparingTo(SEED_ACCT1_CREDIT_LIMIT);
        assertThat(after.getActiveStatus().trim())
                .as("the drift-injected status persists, proving the confirm turn overwrote nothing")
                .isEqualTo(DRIFT_STATUS);
    }

    /**
     * (Finding #11 - {@code @Valid} + {@code BindingResult} length guard) An {@code acctsid} one digit
     * wider than its {@code PIC 9(11)} / {@code @Size(max = 11)} width - only reachable by a crafted
     * request, since the {@code COACTUP} template pins {@code maxlength="11"} - must be bounced by the
     * controller with the neutral length banner and NO service call, before any fetch/edit/write. This
     * proves the update handler now honours the bean-validation constraint that was previously ignored
     * (no controller used {@code @Valid}); the confirm mode is reset so a rejected post cannot leave a
     * stale confirmation armed.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdateOverWidthFieldRejectedWithNeutralBannerNoWrite() throws Exception {
        BigDecimal before = accountRepository.findById(ACCT1_KEY).orElseThrow().getCreditLimit();

        mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .param(PARAM_ACCT_ID, OVER_WIDTH_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(FIELD_LENGTH_FRAGMENT))))
                .andExpect(model().attribute(MODEL_ATTR_CONFIRM_MODE, equalTo(Boolean.FALSE)));

        assertThat(accountRepository.findById(ACCT1_KEY).orElseThrow().getCreditLimit())
                .as("an over-width field must be rejected before any write reaches the datastore")
                .isEqualByComparingTo(before);
    }

    /**
     * (Finding #11 - {@code @InitBinder} allowlist / mass-assignment defense, CWE-915) The COACTUP screen
     * never submits the {@code fkeys} display property and {@code AccountController} never sets it, yet it
     * is a public settable bean property. A crafted request that injects {@code fkeys} must be dropped by
     * the per-form {@code setAllowedFields(...)} allowlist so it never binds - if the {@code @InitBinder}
     * were removed, the rendered form would carry the injected value and this test would fail. The
     * legitimate {@code acctsid} filter still binds (the ENTER fetch renders the update screen), proving
     * the allowlist restricts rather than disables binding.
     */
    @Test
    @WithMockUser(roles = ROLE_USER)
    void accountUpdateOverpostedNonAllowlistedFieldIsIgnored() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_ACCOUNT_UPDATE).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE));

        MvcResult fetch = mockMvc.perform(post(ROUTE_ACCOUNT_UPDATE)
                        .session(session)
                        .param(PARAM_ACCT_ID, CAUP_ACCT_ID)
                        .param(PARAM_PFKEY, PFKEY_ENTER)
                        .param(OVERPOST_PROP_FKEYS, OVERPOST_FKEYS_VALUE)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ACCOUNT_UPDATE))
                .andReturn();

        COACTUPForm rendered = (COACTUPForm) fetch.getModelAndView().getModel().get(MODEL_ATTR_FORM);
        assertThat(rendered.getFkeys())
                .as("a non-allowlisted property must not bind from the request (@InitBinder allowlist)")
                .isNotEqualTo(OVERPOST_FKEYS_VALUE);
    }
}
