package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MockMvc REST-contract test for {@link TransactionController} &mdash; the parity gate proving that
 * the three legacy CICS online transaction programs are faithfully re-expressed as JSON endpoints:
 *
 * <ul>
 *   <li>{@code app/cbl/COTRN00C.cbl} (List Transactions) &rarr; {@code GET /transactions}
 *       &mdash; the {@code TRANSACT.AIX} origination-timestamp browse, standardized to the fixed
 *       legacy <strong>page size 7</strong> (AAP &sect;0.6.4 / &sect;0.7.3).</li>
 *   <li>{@code app/cbl/COTRN01C.cbl} (View Transaction) &rarr; {@code GET /transactions/{tranId}}
 *       &mdash; single-record lookup; a missing id yields HTTP&nbsp;404.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} (Add Transaction) &rarr; {@code POST /transactions}
 *       &mdash; the <em>Account&nbsp;ID&nbsp;XOR&nbsp;Card&nbsp;Number</em> key rule, the card
 *       cross-reference resolution, {@code CSUTLDTC} date validation, the {@code TranIdGenerator}
 *       16-character id, and the stamping of <strong>both</strong> the origination and processing
 *       timestamps ({@code app/cpy/CVTRA05Y.cpy} carries {@code TRAN-ORIG-TS} <em>and</em>
 *       {@code TRAN-PROC-TS}, AAP &sect;0.7.3 #10).</li>
 * </ul>
 *
 * <h2>Strategy &mdash; create then read</h2>
 * <p>The {@code transactions} table is created by the Flyway migrations but <strong>never
 * seeded</strong>; it starts empty on every test context. This is therefore a true
 * create-then-read contract test: the list / detail assertions first {@code POST} a transaction and
 * then read it back. Because the class is {@link Transactional}, every test method runs inside a
 * transaction that is rolled back on completion, so each method observes an empty
 * {@code transactions} table at its start and the assertions on totals (e.g.
 * {@code totalElements == 1}) are fully deterministic.</p>
 *
 * <p>The write performed by a {@code POST} is visible to a subsequent {@code GET} <em>within the same
 * test method</em>: the service {@code @Transactional} joins the test thread's transaction, so the
 * newly persisted row is resolvable both by id (first-level cache) and by the list query
 * (Hibernate auto-flushes the persistence context before the JPQL query executes).</p>
 *
 * <h2>Determinism via the seeded cross-reference</h2>
 * <p>The add requests use the <strong>card path</strong> ({@code cardNum = 0500024453765740}); the
 * V3 {@code card_xref} seed maps that card to account&nbsp;50, so the owning account is resolved
 * deterministically. The {@code (typeCd, categoryCd) = ("01", 1)} pair is the seeded
 * "Regular Sales Draft" combination, safe against any foreign key to {@code transaction_type} /
 * {@code transaction_category}.</p>
 *
 * <h2>Security &amp; serialization context</h2>
 * <p>The transaction endpoints require only authentication (no {@code @PreAuthorize}); the class runs
 * as {@link WithMockUser} {@code USER0001}/{@code ROLE_USER}. CSRF is disabled in the stateless
 * filter chain, so no CSRF post-processor is used. The {@code test} profile configures Jackson with
 * {@code non_null} inclusion and {@code write-dates-as-timestamps=false}; consequently a
 * {@link java.time.LocalDateTime} serializes as {@code "yyyy-MM-ddTHH:mm:ss"}, a
 * {@link java.time.LocalDate} as {@code "yyyy-MM-dd"}, and {@code null} fields are omitted &mdash; so
 * the mere <em>presence</em> of {@code $.procTs} proves it was populated (the &sect;0.7.3 #10
 * dual-timestamp parity point).</p>
 *
 * @see TransactionController
 * @see com.carddemo.service.TransactionService
 * @see com.carddemo.dto.TransactionResponse
 * @see com.carddemo.dto.TransactionListItem
 * @see com.carddemo.dto.TransactionAddRequest
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "USER0001", roles = {"USER"})
class TransactionControllerTest {

    /** Seeded card number whose V3 {@code card_xref} row maps it to account {@value #SEED_ACCT_ID}. */
    private static final String SEED_CARD_NUM = "0500024453765740";

    /** Account the seeded {@link #SEED_CARD_NUM} resolves to via the card cross-reference. */
    private static final long SEED_ACCT_ID = 50L;

    /**
     * A syntactically valid 16-character transaction id that is guaranteed absent: the
     * {@code transaction_id_seq} starts at 1 and increments by 1, so this maximal value can never be
     * generated within the lifetime of the test suite, and the table is empty at the start of each
     * rolled-back test anyway.
     */
    private static final String MISSING_TRAN_ID = "9999999999999999";

    /** Entry point for performing REST requests against the fully wired application context. */
    @Autowired
    private MockMvc mockMvc;

    /** Application {@link ObjectMapper}, used to parse response bodies for precise assertions. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Builds a valid {@code POST /transactions} request body that exercises the COTRN02C
     * <strong>card path</strong>: only {@code cardNum} is supplied (no {@code accountId}), so the
     * service resolves account {@value #SEED_ACCT_ID} through the seeded cross-reference. The
     * {@code (typeCd, categoryCd)} pair is the seeded "Regular Sales Draft" combination.
     *
     * @return a JSON object string accepted by {@code TransactionAddRequest} bean validation and the
     *         service-level XOR key rule
     */
    private String validAddJson() {
        return "{"
            + "\"cardNum\":\"0500024453765740\","
            + "\"typeCd\":\"01\",\"categoryCd\":1,"
            + "\"source\":\"POS\",\"description\":\"Test purchase via REST\","
            + "\"amount\":123.45,"
            + "\"origDate\":\"2024-01-15\",\"procDate\":\"2024-01-15\","
            + "\"merchantId\":123456789,\"merchantName\":\"TEST MERCHANT\","
            + "\"merchantCity\":\"SEATTLE\",\"merchantZip\":\"98101\""
            + "}";
    }

    // =============================================================================================
    // POST /transactions  (COTRN02C "Add Transaction")  ->  201 Created
    // =============================================================================================

    /**
     * A valid add returns <strong>201&nbsp;Created</strong> with a 16-character generated
     * {@code tranId}, <strong>both</strong> timestamps populated, the echoed type/category/card, and
     * the amount preserved exactly. This is the cornerstone test: the {@code POST} drives every
     * read-back test below.
     */
    @Test
    @DisplayName("POST /transactions (card path) -> 201 with 16-char tranId, both timestamps, money parity")
    void postTransaction_validCardPath_returns201WithBothTimestampsAnd16CharId() throws Exception {
        String json = mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAddJson()))
                .andExpect(status().isCreated())                       // 201
                .andExpect(jsonPath("$.tranId").exists())
                .andExpect(jsonPath("$.origTs").exists())              // BOTH timestamps (Section 0.7.3 #10)
                .andExpect(jsonPath("$.procTs").exists())
                .andExpect(jsonPath("$.typeCd").value("01"))
                .andExpect(jsonPath("$.categoryCd").value(1))
                .andExpect(jsonPath("$.cardNum").value(SEED_CARD_NUM))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(json);
        // LPAD-16: the TranIdGenerator zero-pads the sequence value to a fixed 16-character width.
        assertThat(node.get("tranId").asText()).hasSize(16);
        // Money parity: compare by value (scale-insensitive), never by literal equality.
        assertThat(new BigDecimal(node.get("amount").asText())).isEqualByComparingTo("123.45");
        // origTs / procTs = origDate / procDate at start of day -> serialized "2024-01-15T00:00:00".
        assertThat(node.get("origTs").asText()).startsWith("2024-01-15");
        assertThat(node.get("procTs").asText()).startsWith("2024-01-15");
    }

    // =============================================================================================
    // GET /transactions/{tranId}  (COTRN01C "View Transaction")  ->  200 / 404
    // =============================================================================================

    /**
     * Creating a transaction and then viewing it by its generated id returns the full detail
     * projection carrying <strong>both</strong> the origination and processing timestamps &mdash; the
     * &sect;0.7.3 #10 dual-timestamp parity check on the view contract.
     */
    @Test
    @DisplayName("GET /transactions/{tranId} after POST -> 200 with both timestamps")
    void getTransactionById_afterPost_returnsBothTimestamps() throws Exception {
        String created = mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAddJson()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tranId = objectMapper.readTree(created).get("tranId").asText();

        String detail = mockMvc.perform(get("/transactions/{tranId}", tranId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tranId").value(tranId))
                .andExpect(jsonPath("$.origTs").exists())              // BOTH timestamps on the view response
                .andExpect(jsonPath("$.procTs").exists())
                .andExpect(jsonPath("$.cardNum").value(SEED_CARD_NUM))
                .andExpect(jsonPath("$.amount").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(detail);
        assertThat(node.get("origTs").asText()).startsWith("2024-01-15"); // origTs = origDate.atStartOfDay()
        assertThat(new BigDecimal(node.get("amount").asText())).isEqualByComparingTo("123.45");
    }

    /**
     * Viewing an unknown transaction id returns <strong>404&nbsp;Not&nbsp;Found</strong>. The empty
     * table guarantees absence of any id within this rolled-back test.
     */
    @Test
    @DisplayName("GET /transactions/{tranId} unknown id -> 404")
    void getTransactionById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/transactions/{tranId}", MISSING_TRAN_ID))
                .andExpect(status().isNotFound());
    }

    // =============================================================================================
    // GET /transactions  (COTRN00C "List Transactions")  ->  200, fixed page size 7
    // =============================================================================================

    /**
     * Listing an account that has no transactions still reports the fixed legacy page
     * <strong>size 7</strong> (service-owned via {@code PageRequest.of(page, 7)}), zero total
     * elements, an empty content array, and {@code first == last == true}.
     */
    @Test
    @DisplayName("GET /transactions empty account -> 200, page size 7, zero elements")
    void listTransactions_emptyAccount_returnsPageSize7AndZeroElements() throws Exception {
        mockMvc.perform(get("/transactions")
                        .param("accountId", "1")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(7))                // page size 7 even when empty
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    /**
     * After adding one transaction (card path &rarr; account&nbsp;50), the list for that account
     * returns a single row, still on a page of size&nbsp;7. The list item exposes {@code origDate}
     * (the date portion of {@code origTs}), not the timestamps &mdash; the dual-timestamp assertion
     * belongs to the detail response, not the list projection.
     */
    @Test
    @DisplayName("GET /transactions after POST (account 50) -> 200, page size 7, single item")
    void listTransactions_afterPost_returnsSingleItemPageSize7() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAddJson()))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/transactions")
                        .param("accountId", String.valueOf(SEED_ACCT_ID))
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(7))
                .andExpect(jsonPath("$.totalElements").value(1))       // deterministic: table started empty
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].tranId").exists())
                .andExpect(jsonPath("$.content[0].origDate").value("2024-01-15")) // date part of origTs
                .andExpect(jsonPath("$.content[0].amount").exists());
    }

    // =============================================================================================
    // POST /transactions validation failures (400) and unauthenticated access (401)
    // =============================================================================================

    /**
     * Supplying <strong>both</strong> an account id and a card number violates the COTRN02C
     * Account-XOR-Card key rule. Both fields pass bean validation, so the service raises
     * {@code ValidationException} &rarr; HTTP&nbsp;400.
     */
    @Test
    @DisplayName("POST /transactions with BOTH accountId and cardNum -> 400 (XOR)")
    void postTransaction_bothAccountAndCard_returns400() throws Exception {
        String both = "{"
            + "\"accountId\":50,\"cardNum\":\"0500024453765740\","
            + "\"typeCd\":\"01\",\"categoryCd\":1,\"source\":\"POS\",\"description\":\"x\","
            + "\"amount\":1.00,\"origDate\":\"2024-01-15\",\"procDate\":\"2024-01-15\","
            + "\"merchantId\":1,\"merchantName\":\"M\",\"merchantCity\":\"C\",\"merchantZip\":\"1\"}";
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(both))
                .andExpect(status().isBadRequest());
    }

    /**
     * Supplying <strong>neither</strong> an account id nor a card number also violates the XOR key
     * rule: both are optional at the field level, so bean validation passes and the service raises
     * {@code ValidationException} &rarr; HTTP&nbsp;400.
     */
    @Test
    @DisplayName("POST /transactions with NEITHER accountId nor cardNum -> 400 (XOR)")
    void postTransaction_neitherAccountNorCard_returns400() throws Exception {
        String neither = "{"
            + "\"typeCd\":\"01\",\"categoryCd\":1,\"source\":\"POS\",\"description\":\"x\","
            + "\"amount\":1.00,\"origDate\":\"2024-01-15\",\"procDate\":\"2024-01-15\","
            + "\"merchantId\":1,\"merchantName\":\"M\",\"merchantCity\":\"C\",\"merchantZip\":\"1\"}";
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(neither))
                .andExpect(status().isBadRequest());
    }

    /**
     * Omitting a bean-validation-required field ({@code amount} is {@code @NotNull}) is rejected at
     * the controller boundary as {@code MethodArgumentNotValidException} &rarr; HTTP&nbsp;400, with
     * the per-field detail enumerated in {@code ErrorResponse.fieldErrors}.
     */
    @Test
    @DisplayName("POST /transactions missing required amount -> 400 with fieldErrors")
    void postTransaction_missingRequiredAmount_returns400WithFieldErrors() throws Exception {
        String missingAmount = "{"
            + "\"cardNum\":\"0500024453765740\","
            + "\"typeCd\":\"01\",\"categoryCd\":1,\"source\":\"POS\",\"description\":\"x\","
            + "\"origDate\":\"2024-01-15\",\"procDate\":\"2024-01-15\","
            + "\"merchantId\":1,\"merchantName\":\"M\",\"merchantCity\":\"C\",\"merchantZip\":\"1\"}";
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingAmount))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    /**
     * An unauthenticated (anonymous) caller is rejected by the stateless filter chain with
     * <strong>401&nbsp;Unauthorized</strong> before reaching the controller. The method-level
     * {@link WithAnonymousUser} overrides the class-level {@link WithMockUser}.
     */
    @Test
    @WithAnonymousUser
    @DisplayName("GET /transactions unauthenticated -> 401")
    void listTransactions_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/transactions"))
                .andExpect(status().isUnauthorized());
    }
}
