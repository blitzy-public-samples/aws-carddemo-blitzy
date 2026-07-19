package com.aws.carddemo.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.dto.screen.COCRDLIForm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failsafe integration test for {@link CardController}, the web tier that merges the three CICS
 * online card programs and documents the one CICS-only card-detail security pseudo-transaction.
 *
 * <p>This test drives the fully wired application through {@link MockMvc}: the real
 * {@link com.aws.carddemo.service.online.CardListService},
 * {@link com.aws.carddemo.service.online.CardDetailService} and
 * {@link com.aws.carddemo.service.online.CardUpdateService}, the real Spring Data repositories over
 * the Testcontainers PostgreSQL provisioned by {@link com.aws.carddemo.AbstractPostgresIntegrationTest}
 * (Flyway {@code V1}&rarr;{@code V2}&rarr;{@code V3}), Spring Security and the
 * {@link com.aws.carddemo.exception.GlobalExceptionHandler}. Nothing is mocked; no
 * {@code @MockBean} and no Mockito are used, and because {@code CardController} is a
 * {@code @Controller} rendering server-side Thymeleaf views the assertions are on HTTP status,
 * logical view name, model attributes and {@code redirect:} targets &mdash; never {@code jsonPath}.</p>
 *
 * <h2>Transaction-id / COBOL traceability</h2>
 * <ul>
 *   <li><b>{@code CCLI} &rarr; {@code GET|POST /card/list}</b> &mdash; COBOL oracle
 *       {@code legacy/cbl/COCRDLIC.cbl}: the paginated card list, page size exactly seven
 *       ({@code CA-SCREEN-NUM-CARDS = 7}), rendered as view {@code COCRDLI}. Row action
 *       {@code 'S'} navigates to the detail screen and {@code 'U'} to the update screen; PF3
 *       returns to the main menu; PF7/PF8 page backward/forward.</li>
 *   <li><b>{@code CCDL} &rarr; {@code GET|POST /card/detail}</b> &mdash; COBOL oracle
 *       {@code legacy/cbl/COCRDSLC.cbl}: the read-only card detail screen, view {@code COCRDSL}.</li>
 *   <li><b>{@code CCUP} &rarr; {@code GET|POST /card/update}</b> &mdash; COBOL oracle
 *       {@code legacy/cbl/COCRDUPC.cbl}: the card update screen, view {@code COCRDUP}.</li>
 * </ul>
 *
 * <h2>{@code CDV1} / {@code COCRDSEC} &mdash; documented, not implemented as a route</h2>
 * <p>The CICS transaction {@code CDV1} and its program {@code COCRDSEC} have <strong>no COBOL
 * source file</strong>: they exist only as CICS resource definitions in
 * {@code legacy/csd/CARDDEMO.CSD} (a card-detail <em>security</em> variant). There is therefore no
 * {@code COCRDSEC} service or controller method in the migration. Its behaviour is realized purely
 * as <strong>URL / method authorization on {@code /card/detail} in
 * {@link com.aws.carddemo.config.SecurityConfig}</strong>: {@code /card/**} falls under
 * {@code anyRequest().authenticated()}, so <em>any</em> authenticated user (either
 * {@code ROLE_USER} or {@code ROLE_ADMIN}) may reach the card-detail screen &mdash; it is
 * authenticated-only, not admin-gated. This mapping is recorded here to close the traceability
 * matrix (AAP &sect;0.6.10, &sect;0.4.1); omitting it would be a traceability gap. The
 * authenticated-access guarantee is verified at runtime by {@link #cardDetailScreenForUser()}
 * (a {@code ROLE_USER} principal successfully renders {@code COCRDSL}).</p>
 *
 * @see CardController
 */
@AutoConfigureMockMvc
class CardControllerIT extends com.aws.carddemo.AbstractPostgresIntegrationTest {

    /** Card-list route ({@code CCLI} / {@code COCRDLIC}); renders {@link #VIEW_LIST}. */
    private static final String ROUTE_LIST = "/card/list";

    /** Card-detail route ({@code CCDL} / {@code COCRDSLC}); renders {@link #VIEW_DETAIL}. */
    private static final String ROUTE_DETAIL = "/card/detail";

    /** Card-update route ({@code CCUP} / {@code COCRDUPC}); renders {@link #VIEW_UPDATE}. */
    private static final String ROUTE_UPDATE = "/card/update";

    /** Main-menu route; PF3 from the card list transfers here ({@code COMEN01C}). */
    private static final String ROUTE_MENU = "/menu";

    /** Logical view of the card-list screen (BMS map {@code COCRDLI}). */
    private static final String VIEW_LIST = "COCRDLI";

    /** Logical view of the card-detail screen (BMS map {@code COCRDSL}). */
    private static final String VIEW_DETAIL = "COCRDSL";

    /** Logical view of the card-update screen (BMS map {@code COCRDUP}). */
    private static final String VIEW_UPDATE = "COCRDUP";

    /** Shared error view rendered by {@link com.aws.carddemo.exception.GlobalExceptionHandler}. */
    private static final String VIEW_ERROR = "error";

    /** Model attribute holding the bound screen form for every card view. */
    private static final String ATTR_FORM = "form";

    /** Model attribute holding the on-screen error line (BMS {@code ERRMSGO} analogue). */
    private static final String ATTR_ERROR_MESSAGE = "errorMessage";

    /** Request parameter carrying the submitted PF-key token. */
    private static final String PARAM_PFKEY = "pfkey";

    /** Card-list per-row selection field for row one ({@code CRDSEL1}); {@code 'S'} view, {@code 'U'} update. */
    private static final String PARAM_ROW1_SELECT = "crdsel1";

    /** Card-detail / card-update account-number search field ({@code ACCTSID}). */
    private static final String PARAM_ACCT_ID = "acctsid";

    /** Card-detail / card-update card-number search field ({@code CARDSID}). */
    private static final String PARAM_CARD_ID = "cardsid";

    /** Card-update embossed-name field ({@code CRDNAME}). */
    private static final String PARAM_CARD_NAME = "crdname";

    /** Card-update active-status field ({@code CRDSTCD}); case-sensitive {@code 'Y'} / {@code 'N'}. */
    private static final String PARAM_CARD_STATUS = "crdstcd";

    /** Card-update expiry-month field ({@code EXPMON}); numeric {@code 1}..{@code 12}. */
    private static final String PARAM_EXP_MONTH = "expmon";

    /** Card-update expiry-year field ({@code EXPYEAR}); numeric {@code 1950}..{@code 2099}. */
    private static final String PARAM_EXP_YEAR = "expyear";

    /** Card-update expiry-day field ({@code EXPDAY}); copied unvalidated by the service. */
    private static final String PARAM_EXP_DAY = "expday";

    /** PF-key token for ENTER (the default; validate / submit). */
    private static final String PF_ENTER = "ENTER";

    /** PF-key token for PF3 (exit / back to menu). */
    private static final String PF_PF3 = "PF3";

    /** PF-key token for PF5 (confirm-and-save on the update screen). */
    private static final String PF_PF5 = "PF5";

    /** PF-key token for PF7 (page backward on the list). */
    private static final String PF_PF7 = "PF7";

    /** PF-key token for PF8 (page forward on the list). */
    private static final String PF_PF8 = "PF8";

    /** PF-key token for PF12 (re-fetch a clean copy on the update screen). */
    private static final String PF_PF12 = "PF12";

    /**
     * Seeded account id ({@code V2__reference_data.sql}), left-padded to the fixed
     * {@code PIC X(11)} width the account/card search fields require.
     */
    private static final String SEEDED_ACCT_ID = "00000000050";

    /**
     * Seeded card number ({@code V2__reference_data.sql}, account {@code 50}, embossed
     * {@code "Aniya Von"}, expiry {@code 2023-03-09}, active {@code "Y"}); the ascending-first
     * card in the base cluster, a fixed {@code PIC X(16)} value.
     */
    private static final String SEEDED_CARD_NUM = "0500024453765740";

    /** A card number absent from the seed &mdash; drives the {@code NOTFND} / not-found path. */
    private static final String MISSING_CARD_NUM = "9999999999999999";

    /** Seeded embossed name for {@link #SEEDED_CARD_NUM} (alphabetic + space, edit-valid). */
    private static final String SEEDED_CARD_NAME = "Aniya Von";

    /** Seeded active status for {@link #SEEDED_CARD_NUM}. */
    private static final String SEEDED_CARD_STATUS = "Y";

    /** A valid but distinct active status used to force a detectable change on the update screen. */
    private static final String CHANGED_CARD_STATUS = "N";

    /** Seeded expiry month for {@link #SEEDED_CARD_NUM} ({@code 2023-03-09}). */
    private static final String SEEDED_EXP_MONTH = "03";

    /** Seeded expiry year for {@link #SEEDED_CARD_NUM} ({@code 2023-03-09}). */
    private static final String SEEDED_EXP_YEAR = "2023";

    /** Seeded expiry day for {@link #SEEDED_CARD_NUM} ({@code 2023-03-09}). */
    private static final String SEEDED_EXP_DAY = "09";

    /** Fragment of the COBOL not-found message ({@code "Did not find cards for this search condition"}). */
    private static final String NOT_FOUND_FRAGMENT = "Did not find";

    /** Servlet-relative signon URL suffix used by the authentication-required redirect assertions. */
    private static final String SIGNON_URL_PATTERN = "**/signon";

    /**
     * MockMvc against the fully wired application context (real controller, services, repositories,
     * security and exception handler). Injected by {@link AutoConfigureMockMvc}.
     */
    @Autowired
    private MockMvc mockMvc;

    // ========================================================================
    // Phase 1 - Authorization and initial GET rendering.
    // ========================================================================

    /**
     * The card-list route is protected: an unauthenticated {@code GET} is redirected to the signon
     * screen by the Spring Security entry point ({@code /card/**} is
     * {@code anyRequest().authenticated()}).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    void cardListRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROUTE_LIST))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(SIGNON_URL_PATTERN));
    }

    /**
     * The card-detail route is protected: an unauthenticated {@code GET} is redirected to signon.
     * This is the negative half of the {@code CDV1}/{@code COCRDSEC} contract &mdash; the
     * card-detail surface demands authentication.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    void cardDetailRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROUTE_DETAIL))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(SIGNON_URL_PATTERN));
    }

    /**
     * The card-update route is protected: an unauthenticated {@code GET} is redirected to signon.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    void cardUpdateRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROUTE_UPDATE))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(SIGNON_URL_PATTERN));
    }

    /**
     * A signed-in {@code ROLE_USER} renders the card-list screen ({@code CCLI} fresh entry,
     * COBOL {@code EIBCALEN = 0}): HTTP 200 and logical view {@link #VIEW_LIST}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardListScreenForUser() throws Exception {
        mockMvc.perform(get(ROUTE_LIST))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
    }

    /**
     * A signed-in {@code ROLE_USER} renders the card-detail screen ({@code CCDL} fresh entry):
     * HTTP 200 and logical view {@link #VIEW_DETAIL}.
     *
     * <p>This is the positive {@code CDV1}/{@code COCRDSEC} parity check: because
     * {@code /card/detail} is authenticated-only (not admin-gated) in
     * {@link com.aws.carddemo.config.SecurityConfig}, a plain {@code ROLE_USER} may open it, exactly
     * as the CICS security variant permitted any authorized operator.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardDetailScreenForUser() throws Exception {
        mockMvc.perform(get(ROUTE_DETAIL))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DETAIL));
    }

    /**
     * A signed-in {@code ROLE_USER} renders the card-update screen ({@code CCUP} fresh entry):
     * HTTP 200 and logical view {@link #VIEW_UPDATE}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdateScreenForUser() throws Exception {
        mockMvc.perform(get(ROUTE_UPDATE))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
    }

    // ========================================================================
    // Phase 2 - CCLI card-list path (COCRDLIC): page seven; row 'S' -> detail,
    // 'U' -> update; PF3 -> menu; PF7/PF8 paging.
    // ========================================================================

    /**
     * The card list caps a page at exactly seven cards (COBOL {@code CA-SCREEN-NUM-CARDS = 7}).
     * With fifty seeded cards the first page fills all seven row slots, so the seventh row's card
     * number is populated; the form exposes exactly seven rows (there is no eighth), which fixes
     * the page size at seven.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardListShowsSevenRows() throws Exception {
        MvcResult result = mockMvc.perform(get(ROUTE_LIST))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST))
                .andReturn();
        COCRDLIForm form = (COCRDLIForm) result.getModelAndView().getModel().get(ATTR_FORM);
        assertThat(form).as("card-list form present in the model").isNotNull();
        assertThat(form.getCrdnum7())
                .as("seventh row populated -> a page holds exactly seven cards")
                .isNotBlank();
    }

    /**
     * Selecting a populated row with action {@code 'S'} transfers to the card-detail program
     * (COBOL {@code COCRDLIC} -> {@code COCRDSLC}). The list is a pseudo-conversational two-step:
     * the fresh request lists page one and remembers it in the session, then the selection request
     * dispatches to {@code /card/detail}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardListRowSelectSNavigatesToDetail() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_LIST).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
        mockMvc.perform(post(ROUTE_LIST).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ROW1_SELECT, "S"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_DETAIL));
    }

    /**
     * Selecting a populated row with action {@code 'U'} transfers to the card-update program
     * (COBOL {@code COCRDLIC} -> {@code COCRDUPC}), dispatching to {@code /card/update}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardListRowSelectUNavigatesToUpdate() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_LIST).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
        mockMvc.perform(post(ROUTE_LIST).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ROW1_SELECT, "U"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_UPDATE));
    }

    /**
     * PF8 pages forward and PF7 pages backward, each re-rendering the list view (COBOL
     * {@code COCRDLIC} {@code CCARD-AID-PFK08} / {@code CCARD-AID-PFK07}). Both remain on
     * {@link #VIEW_LIST}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardListPf7Pf8Paging() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_LIST).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
        mockMvc.perform(post(ROUTE_LIST).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_PF8))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
        mockMvc.perform(post(ROUTE_LIST).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_PF7))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
    }

    /**
     * PF3 from the card list exits to the main menu (COBOL {@code COCRDLIC} PF3 -> {@code COMEN01C}),
     * which the controller renders as {@code redirect:/menu}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardListPf3ReturnsToMenu() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_LIST).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
        mockMvc.perform(post(ROUTE_LIST).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_PF3))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_MENU));
    }

    // ========================================================================
    // Phase 3 - CCDL card detail (read-only, COCRDSLC) and CCUP card update
    // (COCRDUPC).
    // ========================================================================

    /**
     * Fetching an existing card renders the detail screen with the card populated (COBOL
     * {@code COCRDSLC} {@code 9100-GETCARD-BYACCTCARD} NORMAL response). The detail program is
     * pseudo-conversational: the fresh request shows the search screen and arms re-enter, then the
     * keyed request reads and displays the seeded card.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardDetailFetchExisting() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_DETAIL).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DETAIL));
        mockMvc.perform(post(ROUTE_DETAIL).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DETAIL));
    }

    /**
     * A non-existent card on the detail screen reproduces the COBOL {@code NOTFND} response:
     * {@code COCRDSLC} raises {@link com.aws.carddemo.exception.RecordNotFoundException}, which the
     * {@link com.aws.carddemo.exception.GlobalExceptionHandler} renders as the shared error view
     * with HTTP 404 and the not-found message line ({@code COCRDSLForm.errmsg} is {@code X(80)}).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardDetailNotFoundPropagates() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_DETAIL).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DETAIL));
        mockMvc.perform(post(ROUTE_DETAIL).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, MISSING_CARD_NUM))
                .andExpect(status().isNotFound())
                .andExpect(view().name(VIEW_ERROR))
                .andExpect(model().attribute(ATTR_ERROR_MESSAGE, containsString(NOT_FOUND_FRAGMENT)));
    }

    /**
     * Fetching an existing card on the update screen renders it for editing (COBOL {@code COCRDUPC}
     * branch that arrives from the search with valid keys and shows details). Like the detail
     * program it is a two-step: the fresh request shows the search screen, the keyed request fetches.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdateFetchExisting() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_UPDATE).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
    }

    /**
     * The PF5 save path of {@code COCRDUPC}: after fetching the card and entering a clean, detectable
     * change (active status {@code 'Y'} -> {@code 'N'}) the state becomes
     * {@code CCUP-CHANGES-OK-NOT-CONFIRMED}; PF5 then confirms and runs {@code 9200-WRITE-PROCESSING}
     * (the optimistic-lock re-read and rewrite). The update screen is re-rendered afterwards. The
     * test is {@link Transactional} so the write is rolled back and the seed stays pristine.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    @Transactional
    void cardUpdatePf5Save() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_UPDATE).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM)
                        .param(PARAM_CARD_NAME, SEEDED_CARD_NAME)
                        .param(PARAM_CARD_STATUS, CHANGED_CARD_STATUS)
                        .param(PARAM_EXP_MONTH, SEEDED_EXP_MONTH)
                        .param(PARAM_EXP_YEAR, SEEDED_EXP_YEAR)
                        .param(PARAM_EXP_DAY, SEEDED_EXP_DAY))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_PF5)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM)
                        .param(PARAM_CARD_NAME, SEEDED_CARD_NAME)
                        .param(PARAM_CARD_STATUS, CHANGED_CARD_STATUS)
                        .param(PARAM_EXP_MONTH, SEEDED_EXP_MONTH)
                        .param(PARAM_EXP_YEAR, SEEDED_EXP_YEAR)
                        .param(PARAM_EXP_DAY, SEEDED_EXP_DAY))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
    }

    /**
     * The PF12 path of {@code COCRDUPC}: after fetching the card, PF12 re-fetches a clean copy and
     * re-renders the update screen. The test is {@link Transactional} so any incidental write is
     * rolled back.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    @Transactional
    void cardUpdatePf12Refetch() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_UPDATE).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_PF12)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
    }

    // ========================================================================
    // Phase 4 - CSRF negative.
    // ========================================================================

    /**
     * With CSRF protection enabled a state-changing {@code POST} that omits the token is rejected
     * with HTTP 403, even for an authenticated user.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardListPostWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post(ROUTE_LIST).param(PARAM_PFKEY, PF_ENTER))
                .andExpect(status().isForbidden());
    }
}
