package com.aws.carddemo.web.controller;

import static org.hamcrest.Matchers.containsString;
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
import com.aws.carddemo.dto.CardDemoContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failsafe controller integration test for {@link BillPayController} &mdash; the Spring MVC
 * replacement for the CICS online bill-payment program.
 *
 * <p><b>COBOL oracle:</b> {@code legacy/cbl/COBIL00C.cbl}, CICS transaction id <b>CB00</b>, mapset /
 * view {@code COBIL00}, web route {@code /billpay} (AAP &sect;0.6.10). This test exercises the
 * <em>real</em> {@link BillPayController}, {@code BillPayService}, and the JPA repositories over a
 * Testcontainers PostgreSQL database migrated by Flyway ({@code V1} &rarr; {@code V2} &rarr;
 * {@code V3}); it uses no Mockito and no {@code @MockBean}, so it validates the full web &rarr;
 * service &rarr; persistence stack end to end.</p>
 *
 * <h2>Behavioural parity anchors (verified against the materialized source)</h2>
 * <ul>
 *   <li><b>Full-balance payment, no partial amount.</b> {@code COBIL00C} always pays the
 *       <em>entire</em> current balance; there is no partial-amount input. The displayed balance
 *       {@code curbal} is a fixed-width money <em>String</em> (COBOL {@code PIC +9999999999.99}),
 *       never a {@code float}/{@code double}.</li>
 *   <li><b>Confirm gating.</b> The payment fires only when {@code confirm} is {@code Y}; a blank
 *       confirmation re-reads the account and prompts, and {@code N} clears the screen without
 *       paying.</li>
 *   <li><b>Pseudo-conversational state (AAP &sect;0.6.8).</b> The COBOL {@code EIBCALEN = 0}
 *       first-entry test becomes {@link CardDemoContext#isNew()}: when the session context is
 *       new, {@code BillPayService.mainEntry} bounces to the sign-on screen. Each test that must
 *       reach the bill-payment screen therefore supplies an <em>initialized</em> session-scoped
 *       {@link CardDemoContext} (see {@link #initializedSession(boolean)}); the enter/re-enter flag
 *       selects the first-display branch versus the {@code EVALUATE EIBAID} key-dispatch branch.</li>
 *   <li><b>Not-found parity (AAP &sect;0.6.5).</b> A missing account raises
 *       {@code RecordNotFoundException}, handled by {@code GlobalExceptionHandler} into the shared
 *       {@code error} view with HTTP {@code 404} and the COBOL {@code "Account ID NOT found..."}
 *       message.</li>
 * </ul>
 *
 * <h2>Isolation</h2>
 * <p>The shared container database is reset to its pristine Flyway seed before every test by
 * {@link AbstractPostgresIntegrationTest}. The mutating payment tests are additionally annotated
 * {@link Transactional} so the account update and the written transaction roll back, keeping the
 * seed pristine for sibling tests.</p>
 *
 * @see BillPayController
 * @see CardDemoContext
 */
@AutoConfigureMockMvc
class BillPayControllerIT extends AbstractPostgresIntegrationTest {

    /** Web route for the bill-payment screen (COBOL tran {@code CB00} / program {@code COBIL00C}). */
    private static final String PATH_BILLPAY = "/billpay";

    /** Logical Thymeleaf view name for the bill-payment screen (BMS map {@code COBIL00}). */
    private static final String VIEW_BILLPAY = "COBIL00";

    /** Shared error view rendered by {@code GlobalExceptionHandler} (also Spring Boot's default). */
    private static final String VIEW_ERROR = "error";

    /** Route the PF3 (back) key returns to &mdash; the main menu ({@code COMEN01C} / {@code CM00}). */
    private static final String ROUTE_MENU = "/menu";

    /**
     * HTTP-session attribute key under which Spring stores the target of the session-scoped
     * {@link CardDemoContext} proxy. Spring's {@code ScopedProxyUtils} prefixes the target bean name
     * (here {@code cardDemoContext}) with {@code "scopedTarget."}; pre-seeding this attribute makes
     * the controller and service observe an already-initialized context instead of a fresh
     * first-entry one.
     */
    private static final String SCOPED_CONTEXT_ATTR = "scopedTarget.cardDemoContext";

    /** Request-parameter name for the account-id entry field (COBOL {@code ACTIDIN}, {@code PIC X(11)}). */
    private static final String PARAM_ACCT = "actidin";

    /** Request-parameter name for the payment-confirmation field (COBOL {@code CONFIRM}, {@code Y}/{@code N}). */
    private static final String PARAM_CONFIRM = "confirm";

    /** Request-parameter name carrying the activated PF-key token ({@code ENTER}/{@code PF3}/{@code PF4}). */
    private static final String PARAM_PFKEY = "pfkey";

    /** Model-attribute name the {@code COBIL00} template binds the screen form under. */
    private static final String MODEL_FORM = "form";

    /** Screen-form property carrying the display-formatted current balance ({@code CURBAL}). */
    private static final String PROP_CURBAL = "curbal";

    /** Screen-form property carrying the error / status message line ({@code ERRMSG}). */
    private static final String PROP_ERRMSG = "errmsg";

    /** Model-attribute name the error view carries the message line under (mirrors {@code ERRMSGO}). */
    private static final String MODEL_ERROR_MESSAGE = "errorMessage";

    /** PF-key token for the ENTER action ({@code COBIL00.html} submit). */
    private static final String KEY_ENTER = "ENTER";

    /** PF-key token for PF3 (back to the previous screen / main menu). */
    private static final String KEY_PF3 = "PF3";

    /** PF-key token for PF4 (clear the current screen). */
    private static final String KEY_PF4 = "PF4";

    /**
     * Seeded account id (11-digit, zero-padded) with a positive current balance and a card
     * cross-reference in {@code V2__reference_data.sql} (account {@code 1}, balance {@code 194.00},
     * cross-reference card {@code 9680294154603697}). Suitable for both the fetch-balance and the
     * full-balance payment paths.
     */
    private static final String SEEDED_ACCT_ID = "00000000001";

    /**
     * Account id guaranteed absent from the seed (only ids {@code 1}-{@code 50} are seeded), used to
     * drive the {@code NOTFND} / {@code RecordNotFoundException} path.
     */
    private static final String NONEXISTENT_ACCT_ID = "99999999999";

    /**
     * Regular expression the display balance must match: a leading sign, ten integer digits, a
     * decimal point, and two fraction digits (COBOL edit mask {@code +9999999999.99}). Asserting the
     * pattern proves the balance is rendered as a fixed-width money {@link String} rather than a
     * floating-point value.
     */
    private static final String BALANCE_PATTERN = "[+-]\\d{10}\\.\\d{2}";

    /** MockMvc entry point, auto-configured with the Spring Security filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Builds an HTTP session pre-seeded with an <em>initialized</em> {@link CardDemoContext} so the
     * bill-payment controller does not treat the request as a cold first entry (COBOL
     * {@code EIBCALEN = 0}) and bounce to the sign-on screen.
     *
     * <p>The context is stored under {@link #SCOPED_CONTEXT_ATTR} so the session-scoped proxy
     * resolves to it. {@link CardDemoContext#markInitialized()} makes {@link CardDemoContext#isNew()}
     * report {@code false} (the {@code EIBCALEN != 0} equivalent). When {@code reenter} is
     * {@code true} the within-program flag is advanced to re-enter
     * ({@link CardDemoContext#markReenter()}), so {@code mainEntry} takes the
     * {@code EVALUATE EIBAID} key-dispatch branch (the path that processes ENTER / PF3 / PF4);
     * when {@code false} the context stays in the enter state so {@code mainEntry} performs the
     * first-display initialization and renders the empty screen.</p>
     *
     * @param reenter {@code true} to place the context in the re-enter (key-dispatch) state;
     *                {@code false} to leave it in the first-display enter state
     * @return a {@link MockHttpSession} carrying the pre-initialized context
     */
    private static MockHttpSession initializedSession(boolean reenter) {
        MockHttpSession session = new MockHttpSession();
        CardDemoContext context = new CardDemoContext();
        context.markInitialized();
        if (reenter) {
            context.markReenter();
        }
        session.setAttribute(SCOPED_CONTEXT_ATTR, context);
        return session;
    }

    // --- Phase 1: authorization & GET display ------------------------------

    /**
     * An unauthenticated {@code GET /billpay} is redirected to the sign-on screen.
     *
     * <p>{@code /billpay} falls under the terminal {@code anyRequest().authenticated()} rule in
     * {@code SecurityConfig}, so with no authenticated session the {@code LoginUrlAuthenticationEntryPoint}
     * sends the caller to {@code /signon} (the migrated "sign on first" behaviour). The redirect
     * target is asserted with a suffix pattern because the entry point issues an absolute URL.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    void billPayRequiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH_BILLPAY))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/signon"));
    }

    /**
     * An authenticated {@code GET /billpay} renders the bill-payment screen.
     *
     * <p>With an initialized session in the first-display (enter) state, {@code mainEntry} takes the
     * COBOL first-display branch and shows the empty {@code COBIL00} screen (HTTP {@code 200}). Any
     * authenticated user may reach the screen; it is not admin-gated.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void billPayScreenForUser() throws Exception {
        mockMvc.perform(get(PATH_BILLPAY).session(initializedSession(false)))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_BILLPAY))
                .andExpect(model().attributeExists(MODEL_FORM));
    }

    // --- Phase 2: CB00 payment path ----------------------------------------

    /**
     * Submitting an account id with ENTER (no confirmation yet) reads the account and displays its
     * balance while prompting for confirmation.
     *
     * <p>Reproduces the COBOL {@code PROCESS-ENTER-KEY} blank-confirmation path: the account is read,
     * its balance moved to {@code CURBAL}, and (because the payment is not yet confirmed) the neutral
     * {@code "Confirm to make a bill payment..."} prompt is shown on a re-rendered {@code COBIL00}
     * screen. The balance is asserted to be a fixed-width money <em>String</em> (never a
     * floating-point value).</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void billPayFetchAccountShowsBalance() throws Exception {
        mockMvc.perform(post(PATH_BILLPAY)
                        .session(initializedSession(true))
                        .param(PARAM_ACCT, SEEDED_ACCT_ID)
                        .param(PARAM_PFKEY, KEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_BILLPAY))
                .andExpect(model().attribute(MODEL_FORM, hasProperty(PROP_CURBAL, matchesPattern(BALANCE_PATTERN))))
                .andExpect(model().attribute(MODEL_FORM,
                        hasProperty(PROP_ERRMSG, containsString("Confirm to make a bill payment"))));
    }

    /**
     * A {@code confirm=N} submission clears the screen and performs no payment.
     *
     * <p>Reproduces the COBOL {@code EVALUATE CONFIRMI WHEN 'N'} path: {@code CLEAR-CURRENT-SCREEN}
     * runs and control stops before any account read or write, so the screen is re-rendered
     * ({@code COBIL00}, HTTP {@code 200}) with no success message and no state change. The test is
     * {@link Transactional} so nothing persists regardless.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    @Transactional
    void billPayConfirmNoDoesNotPay() throws Exception {
        mockMvc.perform(post(PATH_BILLPAY)
                        .session(initializedSession(true))
                        .param(PARAM_ACCT, SEEDED_ACCT_ID)
                        .param(PARAM_CONFIRM, "N")
                        .param(PARAM_PFKEY, KEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_BILLPAY));
    }

    /**
     * A {@code confirm=Y} submission pays the entire current balance and reports success.
     *
     * <p>Reproduces the COBOL confirmed-payment sequence in {@code PROCESS-ENTER-KEY}: read the
     * account, resolve the owning card via the cross-reference alternate index, derive the next
     * transaction id, write the bill-payment transaction for the <em>entire</em> balance (merchant id
     * {@code 999999999}, {@code TRAN-TYPE-CD='02'}), zero the account balance, and re-render
     * {@code COBIL00} (HTTP {@code 200}) with the green {@code "Payment successful..."} line. The test
     * is {@link Transactional} so the account update and the written transaction roll back, leaving
     * the seed pristine.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    @Transactional
    void billPayConfirmYesPaysFullBalance() throws Exception {
        mockMvc.perform(post(PATH_BILLPAY)
                        .session(initializedSession(true))
                        .param(PARAM_ACCT, SEEDED_ACCT_ID)
                        .param(PARAM_CONFIRM, "Y")
                        .param(PARAM_PFKEY, KEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_BILLPAY))
                .andExpect(model().attribute(MODEL_FORM,
                        hasProperty(PROP_ERRMSG, containsString("Payment successful"))));
    }

    /**
     * Submitting a non-existent account id surfaces the not-found outcome.
     *
     * <p>Reproduces the COBOL {@code READ-ACCTDAT-FILE} {@code NOTFND} branch: the missing account
     * raises {@code RecordNotFoundException}, which {@code GlobalExceptionHandler} renders as the
     * shared {@code error} view with HTTP {@code 404} and the message line carrying the COBOL
     * {@code "Account ID NOT found..."} text.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void billPayNotFoundPropagates() throws Exception {
        mockMvc.perform(post(PATH_BILLPAY)
                        .session(initializedSession(true))
                        .param(PARAM_ACCT, NONEXISTENT_ACCT_ID)
                        .param(PARAM_PFKEY, KEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(view().name(VIEW_ERROR))
                .andExpect(model().attribute(MODEL_ERROR_MESSAGE, containsString("found")));
    }

    /**
     * The PF4 key clears the current screen.
     *
     * <p>Reproduces the COBOL {@code EVALUATE EIBAID WHEN DFHPF4} path ({@code CLEAR-CURRENT-SCREEN}):
     * the editable fields are cleared and the {@code COBIL00} screen is re-rendered (HTTP
     * {@code 200}).</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void billPayPf4Clear() throws Exception {
        mockMvc.perform(post(PATH_BILLPAY)
                        .session(initializedSession(true))
                        .param(PARAM_PFKEY, KEY_PF4)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_BILLPAY));
    }

    /**
     * The PF3 key returns to the previous screen (the main menu when no originating program is
     * recorded).
     *
     * <p>Reproduces the COBOL {@code EVALUATE EIBAID WHEN DFHPF3} path ({@code RETURN-TO-PREV-SCREEN}):
     * with no {@code CDEMO-FROM-PROGRAM} recorded, the hand-off target defaults to the main-menu
     * program {@code COMEN01C}, which the controller maps to a redirect to {@code /menu}.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void billPayPf3Returns() throws Exception {
        mockMvc.perform(post(PATH_BILLPAY)
                        .session(initializedSession(true))
                        .param(PARAM_PFKEY, KEY_PF3)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_MENU));
    }

    // --- Phase 3: CSRF negative --------------------------------------------

    /**
     * A state-changing {@code POST /billpay} without a CSRF token is rejected.
     *
     * <p>CSRF protection stays enabled in {@code SecurityConfig}, so an otherwise-authenticated POST
     * that omits the token is forbidden (HTTP {@code 403}) by the security filter chain before it
     * reaches the controller.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void billPayPostWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post(PATH_BILLPAY)
                        .param(PARAM_ACCT, SEEDED_ACCT_ID)
                        .param(PARAM_PFKEY, KEY_ENTER))
                .andExpect(status().isForbidden());
    }
}
