package com.aws.carddemo.web.controller;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COTRN00Form;
import com.aws.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Failsafe integration test for {@link TransactionController} - the single Spring MVC controller that
 * merges the three CICS online transaction programs of the AWS CardDemo transaction module into one
 * layered web tier, exercised end-to-end against a real, Flyway-migrated PostgreSQL database supplied
 * by Testcontainers (no mocks, real services and repositories).
 *
 * <p>The controller and its collaborating services are faithful Java migrations of three COBOL
 * pseudo-conversational programs; this test asserts their observable web contract (HTTP status,
 * logical view name, redirect target, and the message / model fields) against the behavior encoded in
 * those legacy oracles (AAP &sect;0.6.10 traceability):</p>
 * <ul>
 *   <li><b>CT00</b> &rarr; {@code GET/POST /transaction/list} - transaction browse, page size exactly
 *       {@code 10}, view {@code COTRN00} - oracle {@code legacy/cbl/COTRN00C.cbl}.</li>
 *   <li><b>CT01</b> &rarr; {@code GET/POST /transaction/view} - read-only transaction view, view
 *       {@code COTRN01} - oracle {@code legacy/cbl/COTRN01C.cbl}.</li>
 *   <li><b>CT02</b> &rarr; {@code GET/POST /transaction/add} - add transaction (write path), view
 *       {@code COTRN02} - oracle {@code legacy/cbl/COTRN02C.cbl}.</li>
 * </ul>
 *
 * <p>All {@code /transaction/**} routes fall under {@code anyRequest().authenticated()} in
 * {@code SecurityConfig}, so any authenticated {@code ROLE_USER} principal may reach them (they are
 * not admin-gated). Unauthenticated requests are redirected to {@code /signon} by the configured
 * {@code LoginUrlAuthenticationEntryPoint}. CSRF protection is enabled, so every mutating {@code POST}
 * carries {@code with(csrf())} and a dedicated negative test proves a missing token is rejected with
 * {@code 403 Forbidden}.</p>
 *
 * <p><b>Pseudo-conversational state.</b> The COBOL {@code COMMAREA} is migrated to the session-scoped
 * {@link CardDemoContext}. The controller never marks that context initialized (only the sign-on
 * screen does), so a first display with no COMMAREA ({@link CardDemoContext#isNew()}) bounces to
 * sign-on. These tests therefore pre-seed a {@link MockHttpSession} carrying an already-initialized
 * context under the scoped-proxy session attribute, exactly reproducing the state a user has after
 * signing on: {@link #firstDisplaySession()} models the enter state (a fresh screen, the COBOL
 * {@code NOT CDEMO-PGM-REENTER} branch) used by the {@code GET} handlers, and
 * {@link #reentrySession()} models the re-entry state (the COBOL {@code EVALUATE EIBAID} branch) in
 * which a submitted PF-key or {@code ENTER} is honored, used by the {@code POST} handlers.</p>
 *
 * <p><b>Seed data.</b> The {@code V2__reference_data.sql} migration seeds accounts, cards and
 * cross-references but leaves the {@code transaction} table empty; read-path tests therefore arrange
 * their own rows through the real {@link TransactionRepository} (a genuine repository, never a mock),
 * relying on the base class resetting the database to the pristine seed before every test.</p>
 *
 * @see TransactionController
 * @see AbstractPostgresIntegrationTest
 */
@AutoConfigureMockMvc
class TransactionControllerIT extends AbstractPostgresIntegrationTest {

    /** Scoped-proxy session attribute under which the session-scoped {@link CardDemoContext} lives. */
    private static final String CONTEXT_SESSION_ATTR = "scopedTarget.cardDemoContext";

    /** A seeded account id (account {@code 2}) whose {@code card_xref} row is {@code 0923877193247330}. */
    private static final String SEEDED_ACCOUNT_ID = "2";

    /** The card cross-referenced to {@link #SEEDED_ACCOUNT_ID}; used for arranged transaction rows. */
    private static final String SEEDED_CARD_NUM = "0923877193247330";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionRepository transactionRepository;

    // ============================================================================================
    // Test fixtures / helpers
    // ============================================================================================

    /**
     * Builds a {@link MockHttpSession} carrying an initialized {@link CardDemoContext} in the
     * <em>enter</em> (first-display) state - the COBOL {@code NOT CDEMO-PGM-REENTER} branch. Used by
     * the {@code GET} handlers, whose first display loads/clears the screen rather than evaluating a
     * PF-key. The context is not new ({@link CardDemoContext#isNew()} is {@code false}) so the service
     * does not bounce to sign-on.
     *
     * @return a session pre-seeded with an initialized, enter-state context
     */
    private static MockHttpSession firstDisplaySession() {
        CardDemoContext context = new CardDemoContext();
        context.markInitialized();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONTEXT_SESSION_ATTR, context);
        return session;
    }

    /**
     * Builds a {@link MockHttpSession} carrying an initialized {@link CardDemoContext} in the
     * <em>re-entry</em> state - the COBOL {@code CDEMO-PGM-REENTER} branch that reaches the
     * {@code EVALUATE EIBAID} dispatch. Used by the {@code POST} handlers so that the submitted
     * {@code ENTER} / PF-key is honored (in the enter state the services ignore the key and re-run the
     * first-display path).
     *
     * @return a session pre-seeded with an initialized, re-entry-state context
     */
    private static MockHttpSession reentrySession() {
        CardDemoContext context = new CardDemoContext();
        context.markInitialized();
        context.markReenter();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONTEXT_SESSION_ATTR, context);
        return session;
    }

    /**
     * Persists one transaction row through the real repository so read-path tests have data to browse
     * (the seed leaves the {@code transaction} table empty). The id is used verbatim as the 16-byte
     * {@code CHAR(16)} key, so callers pass an already-16-character id to match {@code findById}
     * lookups against the fixed-width column.
     *
     * @param tranId the 16-character transaction id (primary key)
     * @param amount the signed transaction amount (persisted as scale-2 {@link BigDecimal})
     * @return the persisted {@link Transaction}
     */
    private Transaction arrangeTransaction(String tranId, BigDecimal amount) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd("01");
        transaction.setTranCatCd(1);
        transaction.setTranSource("POS");
        transaction.setTranDesc("ARRANGED IT TRANSACTION");
        transaction.setTranAmt(amount);
        transaction.setCardNum(SEEDED_CARD_NUM);
        transaction.setMerchantId(999999999L);
        transaction.setMerchantName("IT MERCHANT");
        transaction.setMerchantCity("IT CITY");
        transaction.setMerchantZip("00000");
        transaction.setOrigTs(LocalDateTime.of(2024, 1, 15, 10, 30, 0));
        transaction.setProcTs(LocalDateTime.of(2024, 1, 15, 10, 30, 5));
        return transactionRepository.saveAndFlush(transaction);
    }

    /**
     * Re-issues a paging {@code POST /transaction/list} exactly as the browser would after a prior
     * render: it re-submits the pressed PF-key together with the pseudo-conversational cursor state
     * the {@code COTRN00} screen round-trips - the page counter {@code pagenum}
     * ({@code CDEMO-CT00-PAGE-NUM}) and the ten per-row transaction ids {@code trnid01..trnid10}
     * (the {@code TRNIDnn} hidden fields). {@code MockMvc} does not render and re-post HTML, so the
     * carried fields must be supplied explicitly; reproducing that round-trip is exactly what proves
     * review finding #9 (without the carried page number PF7 always saw page&nbsp;0/1 and reported
     * "You are already at the top of the page...").
     *
     * @param session the shared re-entry session (COMMAREA carrier)
     * @param pfkey   the PF-key to submit ({@code PF7} / {@code PF8})
     * @param prev    the form rendered by the previous step, supplying the round-tripped cursors
     * @return a request builder ready to {@code perform}
     */
    private static MockHttpServletRequestBuilder pagingPost(MockHttpSession session, String pfkey,
            COTRN00Form prev) {
        return post("/transaction/list")
                .session(session)
                .param("pfkey", pfkey)
                .param("pagenum", nullToEmpty(prev.getPagenum()))
                .param("trnid01", nullToEmpty(prev.getTrnid01()))
                .param("trnid02", nullToEmpty(prev.getTrnid02()))
                .param("trnid03", nullToEmpty(prev.getTrnid03()))
                .param("trnid04", nullToEmpty(prev.getTrnid04()))
                .param("trnid05", nullToEmpty(prev.getTrnid05()))
                .param("trnid06", nullToEmpty(prev.getTrnid06()))
                .param("trnid07", nullToEmpty(prev.getTrnid07()))
                .param("trnid08", nullToEmpty(prev.getTrnid08()))
                .param("trnid09", nullToEmpty(prev.getTrnid09()))
                .param("trnid10", nullToEmpty(prev.getTrnid10()))
                .with(csrf());
    }

    /** Null-safe param helper: MockMvc rejects a null param value, so blank stands in for absent. */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ============================================================================================
    // Phase 1 - authorization and first-display GET screens
    // ============================================================================================

    /**
     * An unauthenticated {@code GET /transaction/list} is redirected to the sign-on screen by the
     * {@code SecurityConfig} entry point before the controller runs.
     */
    @Test
    void transactionListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/transaction/list"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/signon"));
    }

    /**
     * An unauthenticated {@code GET /transaction/view} is redirected to the sign-on screen.
     */
    @Test
    void transactionViewRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/transaction/view"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/signon"));
    }

    /**
     * An unauthenticated {@code GET /transaction/add} is redirected to the sign-on screen.
     */
    @Test
    void transactionAddRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/transaction/add"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/signon"));
    }

    /**
     * A {@code ROLE_USER} first display of {@code GET /transaction/list} renders the {@code COTRN00}
     * browse screen (CT00, {@code legacy/cbl/COTRN00C.cbl}).
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionListScreenForUser() throws Exception {
        mockMvc.perform(get("/transaction/list").session(firstDisplaySession()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN00"));
    }

    /**
     * A {@code ROLE_USER} first display of {@code GET /transaction/view} renders the {@code COTRN01}
     * read-only view screen (CT01, {@code legacy/cbl/COTRN01C.cbl}).
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionViewScreenForUser() throws Exception {
        mockMvc.perform(get("/transaction/view").session(firstDisplaySession()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN01"));
    }

    /**
     * Finding #10 (INFO): a direct or bookmarked {@code GET /transaction/view} whose session still
     * carries a <em>stale</em> {@code CDEMO-PGM-REENTER} left by a prior screen must render a CLEAN
     * first-display search screen - not the "Tran ID can NOT be empty..." banner that the
     * {@code EVALUATE EIBAID -> WHEN DFHENTER} arm would emit for the empty form. Every legitimate
     * transfer into {@code COTRN01C} first sets {@code CDEMO-PGM-CONTEXT = 0} (the transaction-list
     * dispatch does {@code MOVE 0 TO CDEMO-PGM-CONTEXT} before its {@code XCTL},
     * {@code legacy/cbl/COTRN00C.cbl} line 191, and the menu likewise), so the arriving program
     * always sees {@code PGM-ENTER} on its first display. {@code showTransactionView} reproduces that
     * by re-seating {@link CardDemoContext#markEnter()} on the GET, forcing the first-display branch
     * of {@code mainEntry}. This models the defect scenario with {@link #reentrySession()} (an
     * already-initialized context in the re-enter state). A genuine blank-id submission still
     * surfaces the message through the {@code POST} handler - see
     * {@link #transactionViewEmptyTranIdRerenders()}.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionViewDirectGetWithStaleReentryIsCleanFirstDisplay() throws Exception {
        mockMvc.perform(get("/transaction/view").session(reentrySession()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN01"))
                .andExpect(model().attribute("form", hasProperty("errmsg", is(blankOrNullString()))));
    }

    /**
     * A {@code ROLE_USER} first display of {@code GET /transaction/add} renders the {@code COTRN02}
     * add screen (CT02, {@code legacy/cbl/COTRN02C.cbl}).
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionAddScreenForUser() throws Exception {
        mockMvc.perform(get("/transaction/add").session(firstDisplaySession()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN02"));
    }

    /**
     * Finding #10 (INFO): the sibling of {@link #transactionViewDirectGetWithStaleReentryIsCleanFirstDisplay()}
     * for the {@code CT02} add screen. A direct or bookmarked {@code GET /transaction/add} whose
     * session still carries a <em>stale</em> {@code CDEMO-PGM-REENTER} left by a prior screen must
     * render a CLEAN first-display add screen - not the "Account or Card Number must be entered..."
     * banner that the {@code EVALUATE EIBAID -> WHEN DFHENTER -> PROCESS-ENTER-KEY} arm emits for the
     * empty form. COBOL {@code COTRN02C} is only ever entered with {@code CDEMO-PGM-CONTEXT = 0} (the
     * menu {@code MOVE ZEROS} before its {@code XCTL}, and the card/transaction-list dispatch that
     * pre-selects a card sets pgmContext to enter), so {@code showTransactionAdd} re-seats
     * {@link CardDemoContext#markEnter()} on the GET to force the first-display branch. Modeled with
     * {@link #reentrySession()}. A genuine empty submission still surfaces the message through the
     * {@code POST} handler.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionAddDirectGetWithStaleReentryIsCleanFirstDisplay() throws Exception {
        mockMvc.perform(get("/transaction/add").session(reentrySession()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN02"))
                .andExpect(model().attribute("form", hasProperty("errmsg", is(blankOrNullString()))));
    }

    // ============================================================================================
    // Phase 2 - CT00 transaction list (COTRN00C)
    // ============================================================================================

    /**
     * The browse page shows at most ten rows - the hard COBOL parity constant
     * {@code WS-PAGE-SIZE = 10} ({@code legacy/cbl/COTRN00C.cbl}). Twelve rows are arranged; the first
     * page fills all ten row slots (there is no eleventh slot on the {@code COTRN00} form, so the cap
     * is structural), which is asserted by the tenth id cell being populated.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionListShowsTenRows() throws Exception {
        for (int i = 1; i <= 12; i++) {
            arrangeTransaction(String.format("%016d", i), new BigDecimal("-123.45"));
        }
        mockMvc.perform(post("/transaction/list")
                        .session(reentrySession())
                        .param("pfkey", "ENTER")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN00"))
                .andExpect(model().attribute("form", hasProperty("trnid10", not(blankOrNullString()))));
    }

    /**
     * Selecting a populated row with {@code 'S'} dispatches to the transaction-view program
     * {@code COTRN01C} - the COBOL {@code XCTL} to CT01 - which the controller realizes as a redirect
     * to {@code /transaction/view} carrying the chosen id as a flash attribute.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionListRowSelectSNavigatesToView() throws Exception {
        mockMvc.perform(post("/transaction/list")
                        .session(reentrySession())
                        .param("pfkey", "ENTER")
                        .param("sel0001", "S")
                        .param("trnid01", "0000000000000005")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/transaction/view"));
    }

    /**
     * A non-numeric transaction-id filter re-renders {@code COTRN00} with the COBOL
     * "Tran ID must be Numeric ..." message ({@code legacy/cbl/COTRN00C.cbl}); the page is not browsed
     * (the COBOL {@code IF NOT ERR-FLG-ON} guard).
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionListNonNumericTranIdRerenders() throws Exception {
        mockMvc.perform(post("/transaction/list")
                        .session(reentrySession())
                        .param("pfkey", "ENTER")
                        .param("trnidin", "ABC")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN00"))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("Numeric"))));
    }

    /**
     * An invalid row action (a flag other than {@code 'S'}) re-renders {@code COTRN00} with the COBOL
     * "Invalid selection. Valid value is S" message ({@code legacy/cbl/COTRN00C.cbl}). The COBOL
     * carries a single message field in which a page-load boundary note overwrites the pending
     * invalid-selection note (the {@code WS-MESSAGE} precedence in {@code PROCESS-ENTER-KEY}), so more
     * than a full page of rows is arranged: the first page then loads cleanly with a following page
     * available, no boundary message is raised, and the invalid-selection message survives.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionListInvalidSelectionRerenders() throws Exception {
        for (int i = 1; i <= 12; i++) {
            arrangeTransaction(String.format("%016d", i), new BigDecimal("-123.45"));
        }
        mockMvc.perform(post("/transaction/list")
                        .session(reentrySession())
                        .param("pfkey", "ENTER")
                        .param("sel0001", "X")
                        .param("trnid01", "0000000000000001")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN00"))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("Invalid selection"))));
    }

    /**
     * True bidirectional multipage paging (review finding #9). With twelve transactions the browse
     * spans two pages (ten + two): {@code ENTER} loads page one, {@code PF8} advances to page two,
     * and {@code PF7} returns to page one - each step re-submitting the pseudo-conversational cursor
     * state the {@code COTRN00} screen round-trips (the page counter {@code CDEMO-CT00-PAGE-NUM} and
     * the ten {@code TRNIDnn} hidden fields; {@code legacy/cbl/COTRN00C.cbl}
     * {@code PROCESS-PF8-KEY}/{@code PROCESS-PF7-KEY}).
     *
     * <p>Before the fix the page number was rendered display-only and excluded from binding, so it
     * never round-tripped and {@code PF7} after {@code PF8} always saw page&nbsp;0/1 and reported
     * "You are already at the top of the page..." without paging. This test asserts the page content
     * actually advances (page-two ids) and returns (page-one ids) and that the page counter
     * progresses 1&rarr;2&rarr;1; it also asserts the rendered page-one screen carries the hidden
     * {@code pagenum} input that makes the round-trip possible.</p>
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionListPf7Pf8Paging() throws Exception {
        // Twelve ids (…0001..…0012) => page one = …0001..…0010, page two = …0011..…0012.
        for (int i = 1; i <= 12; i++) {
            arrangeTransaction(String.format("%016d", i), new BigDecimal("-123.45"));
        }
        MockHttpSession session = reentrySession();

        // ENTER: PROCESS-ENTER-KEY loads page one (CDEMO-CT00-PAGE-NUM -> 1). The rendered screen
        // must carry the hidden pagenum round-trip input (finding #9), so the browser resubmits it.
        MvcResult page1 = mockMvc.perform(post("/transaction/list")
                        .session(session).param("pfkey", "ENTER").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN00"))
                .andExpect(model().attribute("form", hasProperty("trnid01", is("0000000000000001"))))
                .andExpect(model().attribute("form", hasProperty("trnid10", is("0000000000000010"))))
                .andExpect(model().attribute("form", hasProperty("pagenum", is("00000001"))))
                .andExpect(content().string(containsString("name=\"pagenum\"")))
                .andReturn();
        COTRN00Form f1 = (COTRN00Form) page1.getModelAndView().getModel().get("form");

        // PF8 (PROCESS-PF8-KEY): advance to page two (…0011, …0012); page counter -> 2.
        MvcResult page2 = mockMvc.perform(pagingPost(session, "PF8", f1))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN00"))
                .andExpect(model().attribute("form", hasProperty("trnid01", is("0000000000000011"))))
                .andExpect(model().attribute("form", hasProperty("trnid02", is("0000000000000012"))))
                .andExpect(model().attribute("form", hasProperty("pagenum", is("00000002"))))
                .andReturn();
        COTRN00Form f2 = (COTRN00Form) page2.getModelAndView().getModel().get("form");

        // PF7 (PROCESS-PF7-KEY): the carried page number (2 > 1) pages back to page one
        // (…0001..…0010); page counter -> 1. The boundary note is the "reached the top" browse
        // signal, never the pre-fix "already at the top" guard (which meant no paging happened).
        mockMvc.perform(pagingPost(session, "PF7", f2))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN00"))
                .andExpect(model().attribute("form", hasProperty("trnid01", is("0000000000000001"))))
                .andExpect(model().attribute("form", hasProperty("trnid10", is("0000000000000010"))))
                .andExpect(model().attribute("form", hasProperty("pagenum", is("00000001"))))
                .andExpect(model().attribute("form",
                        hasProperty("errmsg", not(containsString("already at the top")))));
    }

    /**
     * {@code PF3} transfers to the main menu {@code COMEN01C} ({@code RETURN-TO-PREV-SCREEN},
     * {@code legacy/cbl/COTRN00C.cbl}), which the controller realizes as a redirect to {@code /menu}.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionListPf3ReturnsToMenu() throws Exception {
        mockMvc.perform(post("/transaction/list")
                        .session(reentrySession())
                        .param("pfkey", "PF3")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/menu"));
    }

    // ============================================================================================
    // Phase 3a - CT01 transaction view (COTRN01C, read-only)
    // ============================================================================================

    /**
     * Entering an existing id reads and displays the record on {@code COTRN01}
     * ({@code PROCESS-ENTER-KEY} / {@code READ-TRANSACT-FILE}, {@code legacy/cbl/COTRN01C.cbl}). The
     * amount is rendered as a fixed {@code +99999999.99} <em>String</em> (BigDecimal parity - never a
     * {@code float}/{@code double}), asserted via a signed eight-integer, two-fraction pattern.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionViewFetchExisting() throws Exception {
        arrangeTransaction("0000000000000042", new BigDecimal("-123.45"));
        mockMvc.perform(post("/transaction/view")
                        .session(reentrySession())
                        .param("pfkey", "ENTER")
                        .param("trnidin", "0000000000000042")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN01"))
                .andExpect(model().attribute("form", hasProperty("trnid", not(blankOrNullString()))))
                .andExpect(model().attribute("form", hasProperty("trnamt", matchesPattern("[-+]\\d{8}\\.\\d{2}"))))
                .andExpect(model().attribute("form", hasProperty("trnamt", containsString("123.45"))));
    }

    /**
     * A blank transaction id re-renders {@code COTRN01} with the COBOL "Tran ID can NOT be empty..."
     * message ({@code legacy/cbl/COTRN01C.cbl}); nothing is read.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionViewEmptyTranIdRerenders() throws Exception {
        mockMvc.perform(post("/transaction/view")
                        .session(reentrySession())
                        .param("pfkey", "ENTER")
                        .param("trnidin", "")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN01"))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("empty"))));
    }

    /**
     * A non-existent transaction id follows the CICS {@code NOTFND} path in
     * {@code READ-TRANSACT-FILE} ({@code legacy/cbl/COTRN01C.cbl} line 285): {@code COTRN01C} sets
     * {@code WS-MESSAGE = "Transaction ID NOT found..."}, {@code SET ERR-FLG-ON}, and falls through
     * to {@code SEND-TRNVIEW-SCREEN}, re-displaying the SAME {@code COTRN01} screen inline - it is
     * <em>not</em> an abend. {@code TransactionViewService.processEnterKey} reproduces that by
     * catching {@code RecordNotFoundException} and returning an inline error redisplay (AAP
     * &sect;0.6.5 exception parity, review finding #8), so the response is HTTP {@code 200} on the
     * {@code COTRN01} view with the "Transaction ID NOT found..." message on the error line - never
     * the full-page {@code error} view (HTTP {@code 404}). The table is empty at seed, so any id is
     * not found.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionViewNotFoundReDisplaysInline() throws Exception {
        mockMvc.perform(post("/transaction/view")
                        .session(reentrySession())
                        .param("pfkey", "ENTER")
                        .param("trnidin", "0000000000000099")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN01"))
                .andExpect(model().attribute("form",
                        hasProperty("errmsg", containsString("NOT found"))));
    }

    /**
     * {@code PF4} clears the screen and re-renders {@code COTRN01} ({@code CLEAR-CURRENT-SCREEN} /
     * {@code INITIALIZE-ALL-FIELDS}, {@code legacy/cbl/COTRN01C.cbl}).
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionViewPf4Clear() throws Exception {
        mockMvc.perform(post("/transaction/view")
                        .session(reentrySession())
                        .param("pfkey", "PF4")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN01"));
    }

    /**
     * {@code PF5} transfers to the transaction list {@code COTRN00C} ({@code legacy/cbl/COTRN01C.cbl}),
     * which the controller realizes as a redirect to {@code /transaction/list}.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionViewPf5ReturnsToList() throws Exception {
        mockMvc.perform(post("/transaction/view")
                        .session(reentrySession())
                        .param("pfkey", "PF5")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/transaction/list"));
    }

    // ============================================================================================
    // Phase 3b - CT02 transaction add (COTRN02C, write path)
    // ============================================================================================

    /**
     * A complete, valid, confirmed add performs the {@code @Transactional} write
     * ({@code PROCESS-ENTER-KEY} &rarr; {@code ADD-TRANSACTION} &rarr; {@code WRITE-TRANSACT-FILE},
     * {@code legacy/cbl/COTRN02C.cbl}). The COBOL {@code WHEN DFHRESP(NORMAL)} outcome re-displays
     * {@code COTRN02} with the green "Transaction added successfully..." message; the derived id is
     * {@code max+1} (here {@code 1} on the empty seed), so exactly one row is written. The test method
     * is {@code @Transactional} so the write is rolled back.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "USER")
    void transactionAddValidAdds() throws Exception {
        mockMvc.perform(post("/transaction/add")
                        .session(reentrySession())
                        .param("pfkey", "ENTER")
                        .param("actidin", SEEDED_ACCOUNT_ID)
                        .param("ttypcd", "01")
                        .param("tcatcd", "0001")
                        .param("trnsrc", "POS")
                        .param("tdesc", "IT VALID ADD")
                        .param("trnamt", "+00000100.00")
                        .param("torigdt", "2024-01-15")
                        .param("tprocdt", "2024-01-15")
                        .param("mid", "123456789")
                        .param("mname", "IT MERCHANT")
                        .param("mcity", "IT CITY")
                        .param("mzip", "00000")
                        .param("confirm", "Y")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN02"))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("successfully"))))
                // Finding #11: the "Transaction added successfully" line is coloured green by the
                // COBOL MOVE DFHGREEN TO ERRMSGC (COTRN02C:727).
                .andExpect(model().attribute("form", hasProperty("errmsgColor", is("green"))));
    }

    /**
     * Adding while the {@code transaction} table already contains a row exercises the online add's
     * key-derivation parity ({@code legacy/cbl/COTRN02C.cbl}): the next id is the descending-browse
     * maximum plus one, so the write never collides with an existing key and the COBOL
     * {@code WHEN DFHRESP(DUPKEY)} branch is unreachable through the web path. The observed outcome is
     * therefore a successful add of a fresh, distinct id (a second row), not a duplicate error - the
     * ACTUAL handled result. The method is {@code @Transactional} so both rows are rolled back.
     */
    @Test
    @Transactional
    @WithMockUser(roles = "USER")
    void transactionAddDuplicatePropagates() throws Exception {
        arrangeTransaction("0000000000000001", new BigDecimal("-10.00"));
        mockMvc.perform(post("/transaction/add")
                        .session(reentrySession())
                        .param("pfkey", "ENTER")
                        .param("actidin", SEEDED_ACCOUNT_ID)
                        .param("ttypcd", "01")
                        .param("tcatcd", "0001")
                        .param("trnsrc", "POS")
                        .param("tdesc", "IT DUPLICATE ATTEMPT")
                        .param("trnamt", "+00000100.00")
                        .param("torigdt", "2024-01-15")
                        .param("tprocdt", "2024-01-15")
                        .param("mid", "123456789")
                        .param("mname", "IT MERCHANT")
                        .param("mcity", "IT CITY")
                        .param("mzip", "00000")
                        .param("confirm", "Y")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN02"))
                .andExpect(model().attribute("form", hasProperty("errmsg", containsString("successfully"))));
    }

    /**
     * {@code PF4} clears the add screen and re-renders {@code COTRN02} ({@code CLEAR-CURRENT-SCREEN},
     * {@code legacy/cbl/COTRN02C.cbl}); no write occurs.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionAddPf4Clear() throws Exception {
        mockMvc.perform(post("/transaction/add")
                        .session(reentrySession())
                        .param("pfkey", "PF4")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN02"));
    }

    /**
     * {@code PF5} copies the last transaction's data fields onto the add form
     * ({@code COPY-LAST-TRAN-DATA}, {@code legacy/cbl/COTRN02C.cbl}). Key-field validation runs first,
     * so a valid account is supplied; the copy is then observable in the re-rendered {@code COTRN02}
     * (the arranged transaction's description). No write occurs (confirmation is not given).
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionAddPf5CopyLast() throws Exception {
        arrangeTransaction("0000000000000042", new BigDecimal("-123.45"));
        mockMvc.perform(post("/transaction/add")
                        .session(reentrySession())
                        .param("pfkey", "PF5")
                        .param("actidin", SEEDED_ACCOUNT_ID)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("COTRN02"))
                .andExpect(model().attribute("form", hasProperty("tdesc", containsString("ARRANGED"))));
    }

    // ============================================================================================
    // Phase 4 - CSRF negative
    // ============================================================================================

    /**
     * A {@code POST /transaction/add} without a CSRF token is rejected with {@code 403 Forbidden} by
     * the enabled CSRF filter before the controller runs, even for an authenticated user.
     */
    @Test
    @WithMockUser(roles = "USER")
    void transactionAddPostWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/transaction/add")
                        .session(reentrySession())
                        .param("pfkey", "ENTER"))
                .andExpect(status().isForbidden());
    }
}
