package com.carddemo.controller;

import java.math.BigDecimal;

import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc REST-contract test for {@link BillPaymentController} &mdash; the integration-level proof that
 * the legacy CICS online bill-payment program {@code app/cbl/COBIL00C.cbl} ("Bill Payment", tran
 * {@code CB00}) is faithfully re-expressed as the JSON endpoints
 * {@code GET}/{@code POST /accounts/{accountId}/bill-payment}.
 *
 * <h2>What this test proves (COBIL00C parity)</h2>
 * <ul>
 *   <li><strong>Balance inquiry</strong> ({@code GET}) returns the account's current balance and the
 *       computed available credit ({@code ACCT-CREDIT-LIMIT - ACCT-CURR-BAL}) without posting anything
 *       &mdash; the read-only half of the legacy {@code COBIL00} screen.</li>
 *   <li><strong>Full payoff</strong> ({@code POST} with {@code confirm=true}) writes a
 *       {@code "BILL PAYMENT - ONLINE"} transaction for the <em>full</em> current balance and drives the
 *       account balance to zero ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}), returning the
 *       written transaction id and the post-payment balance.</li>
 *   <li><strong>Nothing-to-pay guard</strong> ({@code ACCT-CURR-BAL <= ZEROS}) rejects a confirmed
 *       payment with HTTP&nbsp;400 &mdash; the headline COBIL00C rule, proven by mutating the seeded
 *       balance to zero (and to a negative value) rather than relying on incidental seed data.</li>
 *   <li><strong>Decline</strong> ({@code confirm=false}) posts nothing (the legacy {@code CONFIRM = 'N'}
 *       clear-screen path), and a <strong>missing confirmation</strong> ({@code @NotNull} on
 *       {@link com.carddemo.dto.BillPaymentRequest}) is rejected with HTTP&nbsp;400.</li>
 *   <li><strong>Unknown account</strong> yields HTTP&nbsp;404 on both verbs, and an
 *       <strong>unauthenticated</strong> request yields HTTP&nbsp;401 from the security filter chain.</li>
 * </ul>
 *
 * <h2>Test strategy</h2>
 * <p>This is a full-context {@link SpringBootTest} running against the Flyway-seeded in-memory H2
 * {@code test} profile, exercising the real controller &rarr; service &rarr; repository &rarr; database
 * path (the sibling {@code BillPaymentServiceTest} covers the service in isolation with Mockito). The
 * class is {@link Transactional}, so each method runs in its own transaction that is rolled back on
 * completion; the balance mutations performed for the edge cases (via
 * {@link AccountRepository#saveAndFlush}) are therefore isolated and every method starts from the
 * pristine seed.</p>
 *
 * <p>A class-level {@link WithMockUser} supplies an authenticated non-admin principal because bill
 * payment carries no {@code @PreAuthorize} (any authenticated user may pay); CSRF is disabled on the
 * stateless API, so no CSRF token is attached. Monetary values are read out of the JSON response tree
 * and compared with AssertJ {@code isEqualByComparingTo} so that scale differences (e.g. {@code 0} vs
 * {@code 0.00}) never cause a false failure, mirroring the {@code BigDecimal.compareTo} discipline of the
 * production code.</p>
 *
 * @see BillPaymentController
 * @see com.carddemo.service.BillPaymentService
 * @see <a href="file:app/cbl/COBIL00C.cbl">app/cbl/COBIL00C.cbl (Bill Payment, tran CB00)</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "USER0001", roles = {"USER"})
class BillPaymentControllerTest {

    /**
     * Account&nbsp;1 from the {@code V3__seed_master.sql} seed: {@code current_balance = 194.00},
     * {@code credit_limit = 2020.00}. Used for the inquiry, decline, and zero/negative-balance edge
     * cases; its available credit is {@code 2020.00 - 194.00 = 1826.00}.
     */
    private static final long ACCT_WITH_KNOWN_BALANCE = 1L;

    /**
     * Account&nbsp;50 from the seed: it owns the first {@code card_xref} row
     * ({@code ('0500024453765740', 50, 50)}), guaranteeing the {@code cardNum} resolution that the
     * confirmed-payment path requires. Its seeded {@code credit_limit = 6169.00}.
     */
    private static final long ACCT_WITH_CARD = 50L;

    /** An account id that is intentionally absent from the seed (used to assert HTTP&nbsp;404). */
    private static final long MISSING_ACCT = 99999999L;

    /** The base path template for the bill-payment sub-resource; {@code {id}} binds the account id. */
    private static final String BILL_PAYMENT_PATH = "/accounts/{id}/bill-payment";

    /** Performs the HTTP requests against the fully wired (security + MVC) application context. */
    @Autowired
    private MockMvc mockMvc;

    /** Reads the JSON response body into a {@link JsonNode} tree for precise money assertions. */
    @Autowired
    private ObjectMapper objectMapper;

    /** Used to deterministically pin account balances for the edge cases (rolled back per method). */
    @Autowired
    private AccountRepository accountRepository;

    // =============================================================================================
    // Phase 2 — GET inquiry (200 + available-credit math, and 404 for an unknown account)
    // =============================================================================================

    /**
     * The balance <em>inquiry</em>: {@code GET} returns HTTP&nbsp;200 with the current balance and the
     * available credit ({@code creditLimit - currentBalance}), and writes nothing &mdash; so the
     * {@code transactionId} and {@code newBalance} fields are {@code null} and (being
     * {@code @JsonInclude(NON_NULL)}) omitted from the JSON entirely.
     */
    @Test
    @DisplayName("GET inquiry returns 200 with current balance and available credit (limit - balance), no payment")
    void getBillPayment_inquiry_returnsBalanceAndAvailableCredit() throws Exception {
        String json = mockMvc.perform(get(BILL_PAYMENT_PATH, ACCT_WITH_KNOWN_BALANCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1))
                .andExpect(jsonPath("$.currentBalance").exists())
                .andExpect(jsonPath("$.availableCredit").exists())
                .andExpect(jsonPath("$.transactionId").doesNotExist()) // inquiry -> no payment yet
                .andExpect(jsonPath("$.newBalance").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(json);
        assertThat(new BigDecimal(node.get("currentBalance").asText())).isEqualByComparingTo("194.00");
        // Available credit = credit limit (2020.00) - current balance (194.00) = 1826.00.
        assertThat(new BigDecimal(node.get("availableCredit").asText())).isEqualByComparingTo("1826.00");
    }

    /**
     * An inquiry against an account that does not exist surfaces the service's
     * {@code ResourceNotFoundException} as HTTP&nbsp;404 (the legacy "Account ID NOT found..." path).
     */
    @Test
    @DisplayName("GET inquiry for an unknown account returns 404")
    void getBillPayment_unknownAccount_returns404() throws Exception {
        mockMvc.perform(get(BILL_PAYMENT_PATH, MISSING_ACCT))
                .andExpect(status().isNotFound());
    }

    // =============================================================================================
    // Phase 3 — POST confirmed payment (200, full payoff to zero, 16-char transaction id written)
    // =============================================================================================

    /**
     * The confirmed payment ({@code confirm=true}): the account's full current balance is paid, the
     * balance is driven to zero, and a 16-character transaction id is returned. Account&nbsp;50 is used
     * because it owns a {@code card_xref} row (guaranteeing the {@code cardNum} resolution required by
     * the pay path); its balance is pinned to a known positive value first so the payoff is
     * deterministic.
     */
    @Test
    @DisplayName("POST confirm=true pays the full balance: 200, newBalance 0.00, 16-char transactionId written")
    void postBillPayment_confirmedPayment_fullPayoffToZero() throws Exception {
        // Arrange: pin a known positive balance on the card-owning account (xref guarantees cardNum).
        Account account = accountRepository.findById(ACCT_WITH_CARD).orElseThrow();
        account.setCurrBal(new BigDecimal("100.00"));
        accountRepository.saveAndFlush(account);

        // Act + Assert: confirm=true -> pays full balance -> newBalance 0, transactionId present.
        String json = mockMvc.perform(post(BILL_PAYMENT_PATH, ACCT_WITH_CARD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(50))
                .andExpect(jsonPath("$.transactionId").exists()) // payment transaction created
                .andExpect(jsonPath("$.newBalance").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(json);
        // currentBalance is the PRE-payment balance the operator saw.
        assertThat(new BigDecimal(node.get("currentBalance").asText())).isEqualByComparingTo("100.00");
        // Available credit = credit limit (6169.00) - current balance (100.00) = 6069.00.
        assertThat(new BigDecimal(node.get("availableCredit").asText())).isEqualByComparingTo("6069.00");
        // Full payoff (COBIL00C parity): new balance = current - amount = 0.
        assertThat(new BigDecimal(node.get("newBalance").asText())).isEqualByComparingTo("0.00");
        // TranIdGenerator left-pads to the legacy TRAN-ID PIC X(16) fixed width.
        assertThat(node.get("transactionId").asText()).hasSize(16);
    }

    // =============================================================================================
    // Phase 4 — POST decline (confirm=false posts nothing) and missing-confirmation validation (400)
    // =============================================================================================

    /**
     * The decline path ({@code confirm=false}): the service posts nothing (the legacy
     * {@code CONFIRM = 'N'} clear-screen path) and returns HTTP&nbsp;200 with {@code transactionId} and
     * {@code newBalance} {@code null} (and therefore omitted). Account&nbsp;1 has a positive balance, so
     * it clears the nothing-to-pay guard and the {@code confirm=false} branch is the outcome under test.
     */
    @Test
    @DisplayName("POST confirm=false posts nothing: 200 with transactionId and newBalance omitted")
    void postBillPayment_declined_postsNothing() throws Exception {
        mockMvc.perform(post(BILL_PAYMENT_PATH, ACCT_WITH_KNOWN_BALANCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1))
                .andExpect(jsonPath("$.currentBalance").exists())
                .andExpect(jsonPath("$.transactionId").doesNotExist()) // nothing paid
                .andExpect(jsonPath("$.newBalance").doesNotExist());
    }

    /**
     * A body with no {@code confirm} flag ({@code {}}) violates the {@code @NotNull} constraint on
     * {@link com.carddemo.dto.BillPaymentRequest}, so Spring's {@code MethodArgumentNotValidException} is
     * mapped to HTTP&nbsp;400 &mdash; the REST analogue of the legacy
     * "Invalid value. Valid values are (Y/N)..." guard.
     */
    @Test
    @DisplayName("POST with a missing confirm flag is rejected by @NotNull bean validation: 400")
    void postBillPayment_missingConfirm_returns400() throws Exception {
        mockMvc.perform(post(BILL_PAYMENT_PATH, ACCT_WITH_KNOWN_BALANCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // =============================================================================================
    // Phase 5 — Balance <= 0 nothing-to-pay rule (400), and unknown account on POST (404)
    // =============================================================================================

    /**
     * The headline COBIL00C parity rule: a confirmed payment against an account whose current balance is
     * <strong>zero</strong> is rejected with HTTP&nbsp;400 ({@code BusinessRuleException}, "You have
     * nothing to pay..."). The balance is set explicitly rather than assumed from the seed.
     */
    @Test
    @DisplayName("POST confirm=true on a zero-balance account triggers the nothing-to-pay rule: 400")
    void postBillPayment_zeroBalance_returns400() throws Exception {
        Account account = accountRepository.findById(ACCT_WITH_KNOWN_BALANCE).orElseThrow();
        account.setCurrBal(BigDecimal.ZERO);
        accountRepository.saveAndFlush(account);

        mockMvc.perform(post(BILL_PAYMENT_PATH, ACCT_WITH_KNOWN_BALANCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The {@code <= 0} boundary: a confirmed payment against a <strong>negative</strong>-balance account
     * is likewise rejected with HTTP&nbsp;400, reinforcing that the guard is {@code balance <= 0} (not
     * just {@code == 0}). The nothing-to-pay check precedes the {@code cardNum} resolution, so this
     * account need not own a card.
     */
    @Test
    @DisplayName("POST confirm=true on a negative-balance account also triggers nothing-to-pay (<= 0): 400")
    void postBillPayment_negativeBalance_returns400() throws Exception {
        Account account = accountRepository.findById(ACCT_WITH_KNOWN_BALANCE).orElseThrow();
        account.setCurrBal(new BigDecimal("-5.00"));
        accountRepository.saveAndFlush(account);

        mockMvc.perform(post(BILL_PAYMENT_PATH, ACCT_WITH_KNOWN_BALANCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A confirmed payment against an unknown account returns HTTP&nbsp;404: the account lookup precedes
     * the pay logic, so a non-null {@code confirm} still resolves to "account not found" rather than the
     * nothing-to-pay rule.
     */
    @Test
    @DisplayName("POST confirm=true on an unknown account returns 404 (account lookup precedes pay logic)")
    void postBillPayment_unknownAccount_returns404() throws Exception {
        mockMvc.perform(post(BILL_PAYMENT_PATH, MISSING_ACCT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isNotFound());
    }

    // =============================================================================================
    // Phase 6 — Authentication boundary (anonymous request -> 401)
    // =============================================================================================

    /**
     * Without an authenticated principal the request is rejected by the security filter chain's
     * {@code AuthenticationEntryPoint} with HTTP&nbsp;401 before the handler is reached.
     * {@link WithAnonymousUser} overrides the class-level {@link WithMockUser} for this method only.
     */
    @Test
    @WithAnonymousUser
    @DisplayName("Unauthenticated GET is rejected by the security filter chain: 401")
    void getBillPayment_anonymous_returns401() throws Exception {
        mockMvc.perform(get(BILL_PAYMENT_PATH, ACCT_WITH_KNOWN_BALANCE))
                .andExpect(status().isUnauthorized());
    }
}
