package com.aws.carddemo.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COCRDLIForm;
import com.aws.carddemo.dto.screen.COCRDUPForm;
import com.aws.carddemo.repository.CardRepository;
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

    /** Model attribute holding the bound screen form for every card view. */
    private static final String ATTR_FORM = "form";

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

    /**
     * Account id filter for the list-narrowing / paging-boundary tests (review findings #6 and #7),
     * left-padded to the {@code PIC X(11)} field width. Deliberately <em>not</em>
     * {@link #SEEDED_ACCT_ID}: account {@code 50}'s card is the ascending-first card in the whole
     * cluster, so filtering to it would leave row one unchanged and a broken filter would still pass.
     * Account {@code 1}'s single card ({@link #FILTER_ACCT_LATE_CARD_NUM}) sorts at ascending rank 49
     * (page seven), so filtering to it visibly changes row one and collapses the list to a single row.
     */
    private static final String FILTER_ACCT_ID_LATE = "00000000001";

    /**
     * The single card owned by {@link #FILTER_ACCT_ID_LATE} (account {@code 1}). Its {@code card_num}
     * sorts near the end of the cluster (rank 49 of 50), so it never appears on the unfiltered first
     * page; seeing it in row one proves the account filter was actually applied (finding #6).
     */
    private static final String FILTER_ACCT_LATE_CARD_NUM = "9680294154603697";

    /**
     * The unfiltered ascending-first card ({@code card_num} order, not account order). Used as the
     * negative control in the filter-narrowing test: after filtering to account {@code 1}, row one
     * must no longer be this value (COBOL {@code COCRDLIC} lists by the {@code CARDDAT} KSDS key).
     */
    private static final String UNFILTERED_FIRST_CARD_NUM = "0500024453765740";

    /**
     * Paging-boundary line raised only on the <em>second</em> forward key-press once the last page is
     * already on screen (COBOL {@code COCRDLIC} {@code 1400-SETUP-MESSAGE},
     * {@code legacy/cbl/COCRDLIC.cbl} line 908). Distinct from {@link #MSG_NO_MORE_RECORDS}.
     */
    private static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /**
     * Read-forward end-of-file line (COBOL {@code COCRDLIC} {@code 9000-READ-FORWARD},
     * {@code legacy/cbl/COCRDLIC.cbl} lines 1219/1239). It is the overridable default the first
     * boundary key-press keeps; the second boundary key-press replaces it with
     * {@link #MSG_NO_MORE_PAGES} (finding #7).
     */
    private static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /** Scoped-proxy session attribute under which the session-scoped {@link CardDemoContext} lives. */
    private static final String CONTEXT_SESSION_ATTR = "scopedTarget.cardDemoContext";

    /** COBOL {@code LIT-CCLISTPGM} &mdash; the card-list program the detail/update screens return from. */
    private static final String LIT_CCLISTPGM = "COCRDLIC";

    /**
     * COBOL {@code LIT-THISPGM VALUE 'COCRDUPC'} - the card-update program itself. Used to build a
     * re-navigation context whose {@code CDEMO-FROM-PROGRAM} is neither the menu nor the card list,
     * exercising the finding #4 fresh-GET guard.
     */
    private static final String LIT_CCUPPGM = "COCRDUPC";

    /**
     * COBOL {@code 'Please enter Account and Card Number'} - the card-update fresh-entry information
     * line ({@code 3250-SETUP-INFOMSG} under {@code CDEMO-PGM-ENTER}). A clean first-entry GET must
     * show this prompt with no error line.
     */
    private static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Please enter Account and Card Number";

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

    // --- Confirmation-integrity (review finding #10) constants ---

    /**
     * Model attribute the controller sets to {@code true} only in the
     * {@code CCUP-CHANGES-OK-NOT-CONFIRMED} state; it drives {@code th:readonly} on the six editable
     * fields and marks the single-use confirmation window as open (COCRDUP.html).
     */
    private static final String MODEL_ATTR_CONFIRM_MODE = "confirmMode";

    /** Request-parameter / hidden-form-field name carrying the single-use confirmation token. */
    private static final String PARAM_CONFIRM_TOKEN = "confirmToken";

    /** COCRDUPForm property holding the on-screen red error line (BMS {@code ERRMSGO}, {@code X(80)}). */
    private static final String PROP_ERR_MSG = "errmsg";

    /**
     * A well-formed but distinct embossed name applied through the confirm flow: it is the validated
     * {@code CCUP-NEW-CRDNAME} snapshot the write must commit (never the seed, never a re-post).
     */
    private static final String CONFIRMED_CARD_NAME = "JANE DOE";

    /**
     * A well-formed (alphabetic) embossed name re-posted on the {@code PF5} turn that MUST be ignored:
     * {@code editMapInputs} skips re-validation in the confirm state, so only the server-carried
     * snapshot ({@link #CONFIRMED_CARD_NAME}) may reach the write (review finding #10 overpost).
     */
    private static final String TAMPERED_CARD_NAME = "HACKER";

    /**
     * A concurrent embossed name written directly to the datastore between the fetch snapshot and
     * {@code PF5} to force the {@code 9300} optimistic-lock drift check (review finding #10 drift).
     */
    private static final String DRIFT_CARD_NAME = "DRIFTED";

    /** A second seeded card on a different account, used as the swapped-target victim (must stay untouched). */
    private static final String VICTIM_CARD_NUM = "0683586198171516";

    /** The victim card's account id, left-padded to the {@code PIC X(11)} filter width (card_acct_id 27). */
    private static final String VICTIM_ACCT_ID = "00000000027";

    /** The victim card's seeded embossed name ({@code V2__reference_data.sql}); proves it was never written. */
    private static final String VICTIM_CARD_NAME = "Ward Jones";

    /** The victim card's seeded active status; proves it was never written. */
    private static final String VICTIM_STATUS = "Y";

    /** The victim card's seeded owning account id (JPA {@code card_acct_id}); proves no re-aim occurred. */
    private static final long VICTIM_ACCT_KEY = 27L;

    /** The card-under-test's owning account id; the write re-affirms it from the snapshot (never a re-post). */
    private static final long SEEDED_ACCT_KEY = 50L;

    /**
     * The CVV committed by any legitimate card-update write. The COCRDUP screen has no CVV field, so
     * {@code CCUP-NEW-CVV} is spaces and the COBOL {@code CARD-UPDATE} coerces it to zero
     * ({@code writeProcessing} {@code parseCvv} quirk, preserved for parity). Asserting it proves the
     * write actually reached the datastore.
     */
    private static final int WRITE_COERCED_CVV = 0;

    /** Seeded CVV for {@link #SEEDED_CARD_NUM} (unchanged when no write occurs). */
    private static final int SEEDED_CVV = 747;

    /** A syntactically valid but wrong 64-hex confirmation token proving forged tokens are rejected. */
    private static final String FORGED_TOKEN =
            "0000000000000000000000000000000000000000000000000000000000000000";

    /** Fragment of {@code MSG_CONFIRM_INTEGRITY} re-rendered when a token is missing/forged/replayed. */
    private static final String CONFIRM_INTEGRITY_FRAGMENT = "Confirmation could not be validated";

    /** Fragment of {@code MSG_DATA_WAS_CHANGED} re-rendered when the {@code 9300} re-read detects drift. */
    private static final String DATA_CHANGED_FRAGMENT = "Record changed by some one else";

    /**
     * MockMvc against the fully wired application context (real controller, services, repositories,
     * security and exception handler). Injected by {@link AutoConfigureMockMvc}.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Real Spring Data repository over the Testcontainers PostgreSQL, used by the confirmation-integrity
     * tests to assert committed state (the card row after a legitimate write, or its pristine seed after
     * a rejected/ignored attempt). Nothing is mocked.
     */
    @Autowired
    private CardRepository cardRepository;

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

    /**
     * Builds a session whose {@link CardDemoContext} is in the from-card-list <em>enter</em> state
     * ({@code CDEMO-FROM-PROGRAM = COCRDLIC}, program-enter, initialized) but carries <em>no</em>
     * selected card number. On the mainframe the 3270 card list guaranteed a valid PAN before the
     * {@code XCTL}, so this arm never faced a blank selection; over HTTP a cold/bookmarked GET, a
     * replayed request, or a stale session can present exactly this state. The helper reproduces it
     * so the review-finding-#47 guard can be exercised.
     *
     * @return a session pre-seeded with a from-list, program-enter context and no selection
     */
    private static MockHttpSession fromListEnterSessionWithoutSelection() {
        CardDemoContext context = new CardDemoContext();
        context.markInitialized();               // not new (EIBCALEN != 0)
        context.markEnter();                     // CDEMO-PGM-ENTER
        context.setFromProgram(LIT_CCLISTPGM);   // CDEMO-FROM-PROGRAM = COCRDLIC
        // Deliberately leave the account/card selection unset (the forged/stale gap).
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONTEXT_SESSION_ATTR, context);
        return session;
    }

    /**
     * Review finding #47 - card-detail cold route with an absent selection. A GET arriving in the
     * from-card-list enter state but with no selected card must NOT reach the keyed read (which
     * would call {@code CardRepository.findById(null)} and raise {@code IllegalArgumentException}
     * &rarr; HTTP 500). The guard routes it to the source-equivalent search prompt: HTTP 200 and the
     * card-detail search screen ({@link #VIEW_DETAIL}), never a server error.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardDetailColdRouteWithoutSelectionShowsSearchScreen() throws Exception {
        mockMvc.perform(get(ROUTE_DETAIL).session(fromListEnterSessionWithoutSelection()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DETAIL));
    }

    /**
     * Review finding #47 - card-update cold route with an absent selection. As for the detail
     * screen, a GET in the from-card-list enter state but with no selected card must fall through to
     * the fresh-entry search prompt (HTTP 200, {@link #VIEW_UPDATE}) rather than performing a keyed
     * read on a null key (HTTP 500).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdateColdRouteWithoutSelectionShowsSearchScreen() throws Exception {
        mockMvc.perform(get(ROUTE_UPDATE).session(fromListEnterSessionWithoutSelection()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
    }

    /**
     * Builds a session whose {@link CardDemoContext} is a live, re-entered context (a prior turn
     * already ran) whose {@code CDEMO-FROM-PROGRAM} is neither the menu ({@code COMEN01C}) nor the
     * card list ({@code COCRDLIC}) - here the card-update program itself, as after a completed
     * interaction the operator re-navigates to. Without the finding #4 guard a fresh GET in this
     * state falls through to input processing on the empty form and surfaces a stale error line.
     *
     * @return a session pre-seeded with a re-entered, non-list, non-menu context
     */
    private static MockHttpSession reNavigatedUpdateSessionNotFromList() {
        CardDemoContext context = new CardDemoContext();
        context.markInitialized();            // not new (EIBCALEN != 0)
        context.markReenter();                // NOT program-enter - a prior turn already ran
        context.setFromProgram(LIT_CCUPPGM);  // CDEMO-FROM-PROGRAM is neither COMEN01C nor COCRDLIC
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONTEXT_SESSION_ATTR, context);
        return session;
    }

    /**
     * Finding #4 - a GET to the card-update route that is NOT the card-list hand-off must render a
     * clean first-entry screen. A re-navigation while the session context still names a non-list,
     * non-menu from-program previously fell through to {@code mainEntry}'s input-processing branch
     * on the empty form, surfacing a stale red error line on a first-entry-looking screen. The GET
     * now re-seats the {@code CDEMO-PGM-ENTER} posture (COBOL {@code EIBCALEN = 0}), so the screen
     * shows the search prompt ({@link #INFO_PROMPT_FOR_SEARCH_KEYS}) with an empty error line.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdateReNavigatedGetNotFromListShowsCleanFirstEntry() throws Exception {
        mockMvc.perform(get(ROUTE_UPDATE).session(reNavigatedUpdateSessionNotFromList()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                // No stale error line carried over (the finding #4 defect).
                .andExpect(model().attribute("form", hasProperty(PROP_ERR_MSG, emptyOrNullString())))
                // The clean first-entry prompt is shown instead.
                .andExpect(model().attribute("form",
                        hasProperty("infomsg", equalTo(INFO_PROMPT_FOR_SEARCH_KEYS))));
    }

    /**
     * Builds a session reproducing the exact runtime scenario of finding #4 that a from-program
     * check alone does not cover: a live, RE-ENTERED context whose {@code CDEMO-FROM-PROGRAM} is
     * still the card list ({@code COCRDLIC}) - as it is after list -&gt; select 'U' -&gt; update
     * -&gt; F3 -&gt; (the list re-renders, restoring FROM = COCRDLIC) -&gt; the operator re-navigates
     * to the update route - yet the context is in re-enter state (a prior turn already ran) while
     * still carrying the live account/card selection from the earlier row pick. A fresh GET in this
     * state found neither Branch 2 (auto-fetch needs {@code CDEMO-PGM-ENTER}, which re-enter had
     * cleared) nor Branch 3, and fell through to input processing on the empty form, surfacing a
     * stale red error line ("Card name not provided"). Re-seating {@code CDEMO-PGM-ENTER} on every
     * GET restores Branch 2, so the live selection auto-fetches with no stale error.
     *
     * @return a session pre-seeded with a re-entered, from-list context holding a live selection
     */
    private static MockHttpSession reNavigatedUpdateSessionFromListWithSelection() {
        CardDemoContext context = new CardDemoContext();
        context.markInitialized();                        // not new (EIBCALEN != 0)
        context.markReenter();                            // NOT program-enter - a prior turn already ran
        context.setFromProgram(LIT_CCLISTPGM);            // FROM = COCRDLIC (restored by the list re-render)
        context.setAcctId(Long.valueOf(SEEDED_ACCT_ID));  // live selection carried from the row pick
        context.setCardNum(SEEDED_CARD_NUM);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONTEXT_SESSION_ATTR, context);
        return session;
    }

    /**
     * Finding #4 (runtime scenario) - a re-navigated GET whose stale {@code CDEMO-FROM-PROGRAM} is
     * still {@code COCRDLIC} and which carries a live selection must auto-fetch that selection with
     * NO stale error line, exactly as the card-list hand-off does, rather than falling through to
     * input processing on the empty form. This is the scenario the earlier from-program-only guard
     * missed (FROM is still the list, so the guard did not fire) and which the unconditional
     * {@code CDEMO-PGM-ENTER} re-seat on GET resolves. Asserts the selected card is auto-fetched and
     * shown ({@code CARDSID} populated with {@link #SEEDED_CARD_NUM}) with an empty error line.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdateReNavigatedGetFromListWithSelectionAutoFetchesNoStaleError() throws Exception {
        mockMvc.perform(get(ROUTE_UPDATE).session(reNavigatedUpdateSessionFromListWithSelection()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                // No stale error line carried over (the finding #4 defect signature).
                .andExpect(model().attribute(ATTR_FORM, hasProperty(PROP_ERR_MSG, emptyOrNullString())))
                // The live selection is auto-fetched and shown (Branch 2), not a blank/erroring form.
                .andExpect(model().attribute(ATTR_FORM,
                        hasProperty("cardsid", containsString(SEEDED_CARD_NUM))));
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
     * Submitting an account-id filter narrows the listing to that account's cards (review finding
     * #6). COBOL {@code COCRDLIC} {@code 2100-RECEIVE-SCREEN} copies {@code ACCTSIDI} into
     * {@code CC-ACCT-ID}, {@code 2210-EDIT-ACCOUNT} validates it, and {@code 9000-READ-FORWARD}
     * skips every card whose {@code CARD-ACCT-ID} differs; before the fix the controller dropped the
     * filter and every list came back unfiltered.
     *
     * <p>The list is keyed by the {@code CARDDAT} card-number, not by account, so the unfiltered
     * first row is {@link #UNFILTERED_FIRST_CARD_NUM} (account {@code 50}). Filtering to account
     * {@code 1} - whose only card sorts at ascending rank 49 - must change row one to
     * {@link #FILTER_ACCT_LATE_CARD_NUM} and leave row two blank (a single match). Asserting row one
     * is the account's late-sorting card (never the unfiltered first row) proves the filter was
     * applied rather than ignored.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardListFilterByAccountNarrowsToThatAccountsCards() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // Fresh entry lists the unfiltered first page and arms the pseudo-conversational re-enter.
        MvcResult first = mockMvc.perform(get(ROUTE_LIST).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST))
                .andReturn();
        COCRDLIForm unfiltered = (COCRDLIForm) first.getModelAndView().getModel().get(ATTR_FORM);
        assertThat(unfiltered.getCrdnum1())
                .as("sanity: the unfiltered listing is keyed by card number, so row one is account 50's card")
                .isEqualTo(UNFILTERED_FIRST_CARD_NUM);

        // Re-enter with the account filter set: the listing must collapse to account 1's single card.
        MvcResult filtered = mockMvc.perform(post(ROUTE_LIST).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, FILTER_ACCT_ID_LATE))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST))
                .andReturn();
        COCRDLIForm form = (COCRDLIForm) filtered.getModelAndView().getModel().get(ATTR_FORM);
        assertThat(form.getCrdnum1())
                .as("account filter applied -> row one is account 1's late-sorting card, not the unfiltered first row")
                .isEqualTo(FILTER_ACCT_LATE_CARD_NUM);
        assertThat(form.getCrdnum2())
                .as("account 1 owns exactly one card -> row two is blank (the list was narrowed, not left unfiltered)")
                .isBlank();
    }

    /**
     * The <em>second</em> forward key-press at the end of the list reports {@code NO MORE PAGES TO
     * DISPLAY}, not the read-forward {@code NO MORE RECORDS TO SHOW} default (review finding #7).
     * COBOL {@code COCRDLIC} {@code 1400-SETUP-MESSAGE} keeps the read-forward end-of-file line the
     * first time the final page is reached and raises {@code CA-LAST-PAGE-SHOWN}; only once that flag
     * is set does the next {@code PFK08} replace the line with the paging-boundary message
     * ({@code legacy/cbl/COCRDLIC.cbl} line 908).
     *
     * <p>Filtering to account {@code 1} yields a one-card, one-page listing, so the first page is the
     * last page and two successive PF8 presses exercise the boundary transition deterministically:
     * the first keeps {@link #MSG_NO_MORE_RECORDS} (and raises the flag), the second shows
     * {@link #MSG_NO_MORE_PAGES}.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardListSecondPf8AtBoundaryShowsNoMorePagesNotNoMoreRecords() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_LIST).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
        // ENTER with the one-card account filter: page one is also the last page (next page absent).
        mockMvc.perform(post(ROUTE_LIST).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, FILTER_ACCT_ID_LATE))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST));
        // First PF8 at the boundary keeps "NO MORE RECORDS TO SHOW" and raises CA-LAST-PAGE-SHOWN.
        mockMvc.perform(post(ROUTE_LIST).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_PF8)
                        .param(PARAM_ACCT_ID, FILTER_ACCT_ID_LATE))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST))
                .andExpect(model().attribute(ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(MSG_NO_MORE_RECORDS))));
        // Second PF8 at the boundary replaces it with the paging-boundary line "NO MORE PAGES...".
        mockMvc.perform(post(ROUTE_LIST).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_PF8)
                        .param(PARAM_ACCT_ID, FILTER_ACCT_ID_LATE))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_LIST))
                .andExpect(model().attribute(ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(MSG_NO_MORE_PAGES))));
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
     * A non-existent card on the detail screen reproduces the COBOL {@code COCRDSLC} {@code NOTFND}
     * response as an <em>inline re-display</em>, not a full-page error (review finding #3, dest
     * report). On {@code NOTFND} {@code COCRDSLC} sets the "... did not find ..." return message and
     * FELL THROUGH to {@code 1000-SEND-MAP} to re-display the SAME {@code COCRDSL} screen (it is not
     * an abend); the migrated
     * {@link com.aws.carddemo.service.online.CardDetailService#mainEntry} therefore catches the read
     * chain's {@link com.aws.carddemo.exception.RecordNotFoundException} (the re-enter arm) and routes
     * it back through {@code buildShowResult} / {@code 1000-SEND-MAP}. The response is HTTP 200 on
     * view {@link #VIEW_DETAIL} with the byte-exact COBOL message on the form's {@code errmsg} line
     * (the {@code ERRMSGO} analogue), mirroring the account-view sibling (finding #1) and AAP
     * &sect;0.6.5 exception parity. The full-page {@code error.html} path (finding #25) is guarded
     * separately by {@link com.aws.carddemo.web.ErrorViewIT}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardDetailNotFoundReDisplaysInline() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_DETAIL).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DETAIL));
        mockMvc.perform(post(ROUTE_DETAIL).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, MISSING_CARD_NUM))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DETAIL))
                .andExpect(model().attribute(ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(NOT_FOUND_FRAGMENT))));
    }

    /**
     * Companion to {@link #cardDetailNotFoundReDisplaysInline()} that asserts on the <em>rendered
     * response body</em> rather than the model attribute: the inline {@code NOTFND} re-display must
     * render the real {@code COCRDSL} terminal screen - its static chrome ({@code "View Credit Card
     * Detail"}) plus the not-found message on the error line - proving a genuine CardDemo screen is
     * returned and never a blank page or Spring Boot's generic Whitelabel page. Before the GROUP A
     * #3 fix this path abended to the full-page error view (HTTP 404); COBOL {@code COCRDSLC} instead
     * re-displays {@code COCRDSL} via {@code 1000-SEND-MAP} (AAP &sect;0.6.5). The dedicated full-page
     * {@code error.html} render check for finding #25 lives in {@link com.aws.carddemo.web.ErrorViewIT}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardDetailNotFoundReDisplaysCocrdslScreenNotWhitelabel() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get(ROUTE_DETAIL).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DETAIL));
        mockMvc.perform(post(ROUTE_DETAIL).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, MISSING_CARD_NUM))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_DETAIL))
                .andExpect(content().string(containsString("View Credit Card Detail")))
                .andExpect(content().string(containsString(NOT_FOUND_FRAGMENT)));
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
        // The ENTER edit pass advances to CCUP-CHANGES-OK-NOT-CONFIRMED and arms the single-use
        // token; confirmMode must be TRUE and the token non-blank (review finding #10).
        MvcResult confirm = mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM)
                        .param(PARAM_CARD_NAME, SEEDED_CARD_NAME)
                        .param(PARAM_CARD_STATUS, CHANGED_CARD_STATUS)
                        .param(PARAM_EXP_MONTH, SEEDED_EXP_MONTH)
                        .param(PARAM_EXP_YEAR, SEEDED_EXP_YEAR)
                        .param(PARAM_EXP_DAY, SEEDED_EXP_DAY))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_CONFIRM_MODE, equalTo(Boolean.TRUE)))
                .andReturn();
        String token = tokenFrom(confirm);
        assertThat(token).as("the confirm turn must issue a non-blank single-use token").isNotBlank();
        // PF5 must carry the issued token; the confirm consumes it and the state advances to
        // CCUP-CHANGES-OKAYED-AND-DONE (confirmMode flips FALSE). @Transactional rolls the write back.
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_PF5)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM)
                        .param(PARAM_CARD_NAME, SEEDED_CARD_NAME)
                        .param(PARAM_CARD_STATUS, CHANGED_CARD_STATUS)
                        .param(PARAM_EXP_MONTH, SEEDED_EXP_MONTH)
                        .param(PARAM_EXP_YEAR, SEEDED_EXP_YEAR)
                        .param(PARAM_EXP_DAY, SEEDED_EXP_DAY)
                        .param(PARAM_CONFIRM_TOKEN, token))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_CONFIRM_MODE, equalTo(Boolean.FALSE)));
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
    // Phase 3b - CCUP confirmation integrity (review finding #10, CWE-20/CWE-639).
    //
    // These tests drive the honest pseudo-conversation (fetch -> edit -> confirm -> PF5) end to end
    // against the real PostgreSQL and assert *committed* state through CardRepository, so they must
    // NOT be @Transactional (the controller commits in its own boundary; per-test isolation comes
    // from the base class's Flyway clean+migrate). On the mainframe the confirmation-screen fields
    // are protected, so a PF5 turn can only re-present the validated CCUP-NEW-DETAILS; the single-use
    // token + server-carried snapshot restores exactly that contract over HTTP - the parity fix is the
    // security fix.
    // ========================================================================

    /**
     * Builds the full COCRDUP edit post: the seeded search keys, the validated embossed-name edit
     * ({@link #CONFIRMED_CARD_NAME}), the detectable {@code 'Y' -> 'N'} status change, and the
     * (unchanged) seeded expiry. This is the exact body the confirm turn validates and the body a
     * crafted PF5 turn would tamper.
     *
     * @return a fresh mutable parameter map for one update post
     */
    private static MultiValueMap<String, String> validEditForm() {
        LinkedMultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add(PARAM_ACCT_ID, SEEDED_ACCT_ID);
        p.add(PARAM_CARD_ID, SEEDED_CARD_NUM);
        p.add(PARAM_CARD_NAME, CONFIRMED_CARD_NAME);   // the validated embossed-name edit
        p.add(PARAM_CARD_STATUS, CHANGED_CARD_STATUS); // the detectable Y -> N status edit
        p.add(PARAM_EXP_MONTH, SEEDED_EXP_MONTH);
        p.add(PARAM_EXP_YEAR, SEEDED_EXP_YEAR);
        p.add(PARAM_EXP_DAY, SEEDED_EXP_DAY);
        return p;
    }

    /**
     * Extracts the single-use confirmation token the controller echoed onto the rendered
     * {@link COCRDUPForm} (the hidden {@code confirmToken} field bound with {@code th:field}).
     *
     * @param result the {@link MvcResult} of a confirm-state render
     * @return the issued confirmation token
     */
    private static String tokenFrom(MvcResult result) {
        COCRDUPForm form = (COCRDUPForm) result.getModelAndView().getModel().get(ATTR_FORM);
        return form.getConfirmToken();
    }

    /**
     * Drives the first three pseudo-conversational turns - first-entry GET, an ENTER fetch of the
     * seeded card, and an ENTER edit pass - leaving {@code COCRDUPC} in the armed
     * {@code CCUP-CHANGES-OK-NOT-CONFIRMED} state, and returns the issued single-use token. Asserts
     * the confirm state was actually reached so a regression that skipped arming would fail fast here
     * rather than silently bypassing the write path.
     *
     * @param session the shared HTTP session standing in for the 3270 pseudo-conversation
     * @return the non-blank single-use confirmation token issued by the edit pass
     * @throws Exception if the MockMvc exchange fails
     */
    private String driveToConfirm(MockHttpSession session) throws Exception {
        // Turn 1 (GET, first entry): render the empty search prompt.
        mockMvc.perform(get(ROUTE_UPDATE).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
        // Turn 2 (POST ENTER, fetch): 9000-READ-DATA snapshots the old details + identity keys.
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .param(PARAM_PFKEY, PF_ENTER)
                        .param(PARAM_ACCT_ID, SEEDED_ACCT_ID)
                        .param(PARAM_CARD_ID, SEEDED_CARD_NUM))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));
        // Turn 3 (POST ENTER, edits): 1200 validates the change, advances to CCUP-CHANGES-OK-NOT-
        // CONFIRMED, and arms the server-side snapshot + single-use token echoed to the hidden field.
        MvcResult confirm = mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PF_ENTER))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_CONFIRM_MODE, equalTo(Boolean.TRUE)))
                .andReturn();
        String token = tokenFrom(confirm);
        assertThat(token)
                .as("the confirm turn must issue a non-blank single-use confirmation token")
                .isNotBlank();
        return token;
    }

    /**
     * (Finding #10 happy path) A real fetch&rarr;edit&rarr;confirm&rarr;PF5 flow commits the
     * server-carried validated snapshot: the seeded card's embossed name becomes the validated edit,
     * the active status flips {@code 'Y' -> 'N'}, the owning account is re-affirmed to the fetched
     * account, and the documented CVV-coercion quirk (no screen field &rarr; {@code 0}) is preserved -
     * proving the write actually reached the datastore.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdateEditConfirmPf5CommitsServerCarriedSnapshot() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session);

        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PF_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_CONFIRM_MODE, equalTo(Boolean.FALSE)));

        Card saved = cardRepository.findById(SEEDED_CARD_NUM).orElseThrow();
        assertThat(saved.getCardActiveStatus().trim()).isEqualTo(CHANGED_CARD_STATUS);
        assertThat(saved.getCardEmbossedName().trim()).isEqualTo(CONFIRMED_CARD_NAME);
        assertThat(saved.getCardAcctId()).isEqualTo(SEEDED_ACCT_KEY);
        // Documented COBOL CVV-coercion quirk: no screen field -> CCUP-NEW-CVV spaces -> 0 on write.
        assertThat(saved.getCardCvvCd()).isEqualTo(WRITE_COERCED_CVV);
    }

    /**
     * (Finding #10 overpost) A PF5 turn that carries a valid token but a tampered, well-formed
     * embossed name must be ignored: {@code editMapInputs} skips re-validation in the confirm state,
     * so only the server-carried snapshot ({@link #CONFIRMED_CARD_NAME}) may be written - never the
     * re-post ({@link #TAMPERED_CARD_NAME}).
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdatePf5OverpostIsIgnoredCommittingServerCarriedName() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session);

        MultiValueMap<String, String> tampered = validEditForm();
        tampered.set(PARAM_CARD_NAME, TAMPERED_CARD_NAME);
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .params(tampered)
                        .param(PARAM_PFKEY, PF_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));

        Card saved = cardRepository.findById(SEEDED_CARD_NUM).orElseThrow();
        assertThat(saved.getCardEmbossedName().trim())
                .as("the PF5 name overpost must be ignored; the validated snapshot is committed")
                .isEqualTo(CONFIRMED_CARD_NAME);
        assertThat(saved.getCardEmbossedName().trim()).isNotEqualTo(TAMPERED_CARD_NAME);
        assertThat(saved.getCardActiveStatus().trim()).isEqualTo(CHANGED_CARD_STATUS);
    }

    /**
     * (Finding #10 swapped target) A PF5 turn that swaps both the card-id and account-id filters to a
     * different seeded card must still write the server-carried identity captured at fetch (card
     * {@code 0500..5740} on account {@code 50}), re-affirming the owning account from the snapshot and
     * leaving the swapped-in victim card entirely untouched.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdatePf5SwappedTargetWritesServerCarriedIdentityOnly() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session); // server-carried target = card 0500..5740 / account 50

        MultiValueMap<String, String> swapped = validEditForm();
        swapped.set(PARAM_CARD_ID, VICTIM_CARD_NUM);
        swapped.set(PARAM_ACCT_ID, VICTIM_ACCT_ID);
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .params(swapped)
                        .param(PARAM_PFKEY, PF_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE));

        // The server-carried card was written (name + status changed, owning account re-affirmed to 50).
        Card written = cardRepository.findById(SEEDED_CARD_NUM).orElseThrow();
        assertThat(written.getCardEmbossedName().trim()).isEqualTo(CONFIRMED_CARD_NAME);
        assertThat(written.getCardActiveStatus().trim()).isEqualTo(CHANGED_CARD_STATUS);
        assertThat(written.getCardAcctId())
                .as("the write re-affirms the snapshot's owning account, not the swapped-in filter")
                .isEqualTo(SEEDED_ACCT_KEY);

        // The swapped-in victim card must be entirely untouched.
        Card victim = cardRepository.findById(VICTIM_CARD_NUM).orElseThrow();
        assertThat(victim.getCardEmbossedName().trim())
                .as("the swapped-in victim card must not be written")
                .isEqualTo(VICTIM_CARD_NAME);
        assertThat(victim.getCardActiveStatus().trim()).isEqualTo(VICTIM_STATUS);
        assertThat(victim.getCardAcctId()).isEqualTo(VICTIM_ACCT_KEY);
    }

    /**
     * (Finding #10 replay) After a legitimate PF5 commit consumes the single-use token, replaying the
     * same token with a tampered body (reverting the status back to {@code 'Y'}) performs no further
     * write - the state advanced past confirm and the token was cleared, so the second attempt cannot
     * re-drive the transactional write.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdatePf5ReplayedTokenPerformsNoFurtherWrite() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session);

        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PF_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token))
                .andExpect(status().isOk());
        assertThat(cardRepository.findById(SEEDED_CARD_NUM).orElseThrow().getCardActiveStatus().trim())
                .isEqualTo(CHANGED_CARD_STATUS);

        MultiValueMap<String, String> replay = validEditForm();
        replay.set(PARAM_CARD_STATUS, SEEDED_CARD_STATUS); // attempt to revert 'N' -> 'Y'
        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .params(replay)
                        .param(PARAM_PFKEY, PF_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token))
                .andExpect(status().isOk());
        assertThat(cardRepository.findById(SEEDED_CARD_NUM).orElseThrow().getCardActiveStatus().trim())
                .as("a replayed, consumed token must not drive a second write")
                .isEqualTo(CHANGED_CARD_STATUS);
    }

    /**
     * (Finding #10 forged token) A PF5 turn carrying a syntactically valid but wrong token is
     * rejected: nothing is written, the operator stays in the confirm window (confirmMode {@code true},
     * a fresh token re-issued), and the red integrity banner is re-rendered.
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdatePf5ForgedTokenIsRejectedWithNoWrite() throws Exception {
        MockHttpSession session = new MockHttpSession();
        driveToConfirm(session); // arm the confirm state, then submit a forged token instead

        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PF_PF5)
                        .param(PARAM_CONFIRM_TOKEN, FORGED_TOKEN))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute(MODEL_ATTR_CONFIRM_MODE, equalTo(Boolean.TRUE)))
                .andExpect(model().attribute(ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(CONFIRM_INTEGRITY_FRAGMENT))));

        Card seed = cardRepository.findById(SEEDED_CARD_NUM).orElseThrow();
        assertThat(seed.getCardActiveStatus().trim())
                .as("a forged confirmation token must not write anything")
                .isEqualTo(SEEDED_CARD_STATUS);
        assertThat(seed.getCardEmbossedName().trim()).isEqualTo(SEEDED_CARD_NAME);
        assertThat(seed.getCardCvvCd()).isEqualTo(SEEDED_CVV);
    }

    /**
     * (Finding #10 drift) A concurrent change committed by another writer between the fetch snapshot
     * and PF5 is detected by the {@code 9300} optimistic-lock re-read: the confirm turn re-displays
     * with the "record changed" banner and applies no edit, and the injected drift persists (proving
     * no overwrite).
     *
     * @throws Exception if a request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void cardUpdatePf5ConcurrentDriftIsRejectedWithNoSave() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = driveToConfirm(session);

        // Inject a concurrent change (another writer renames the card) after the snapshot (turn 2) but
        // before PF5. Committed in its own transaction (this test is not @Transactional).
        Card concurrent = cardRepository.findById(SEEDED_CARD_NUM).orElseThrow();
        concurrent.setCardEmbossedName(DRIFT_CARD_NAME);
        cardRepository.saveAndFlush(concurrent);

        mockMvc.perform(post(ROUTE_UPDATE).session(session).with(csrf())
                        .params(validEditForm())
                        .param(PARAM_PFKEY, PF_PF5)
                        .param(PARAM_CONFIRM_TOKEN, token))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_UPDATE))
                .andExpect(model().attribute(ATTR_FORM,
                        hasProperty(PROP_ERR_MSG, containsString(DATA_CHANGED_FRAGMENT))));

        Card after = cardRepository.findById(SEEDED_CARD_NUM).orElseThrow();
        assertThat(after.getCardEmbossedName().trim())
                .as("the drift-injected name persists, proving the confirm turn overwrote nothing")
                .isEqualTo(DRIFT_CARD_NAME);
        assertThat(after.getCardActiveStatus().trim())
                .as("a concurrent-change (drift) confirmation must not apply the status edit")
                .isEqualTo(SEEDED_CARD_STATUS);
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
