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
package com.carddemo.controller;

import com.carddemo.controller.advice.GlobalExceptionHandler;
import com.carddemo.dto.transaction.TransactionDto;
import com.carddemo.dto.transaction.TransactionListResponse;
import com.carddemo.dto.transaction.TransactionRequest;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.ExpiredAccountException;
import com.carddemo.exception.InvalidCardException;
import com.carddemo.exception.OverlimitException;
import com.carddemo.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.hasLength;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc web-slice tests for {@link TransactionController} &mdash; the stateless REST replacement
 * for the three legacy CICS online transaction programs:
 * <ul>
 *   <li>{@code app/cbl/COTRN00C.cbl} (transaction list, TRANID {@code 'CT00'}) &rarr;
 *       {@code GET /api/transactions} (paginated browse; PF7/PF8 cursor becomes a Spring
 *       {@link Pageable});</li>
 *   <li>{@code app/cbl/COTRN01C.cbl} (transaction view, TRANID {@code 'CT01'}) &rarr;
 *       {@code GET /api/transactions/{tranId}};</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} (transaction add, TRANID {@code 'CT02'}) &rarr;
 *       {@code POST /api/transactions}, whose validation chain mirrors the batch program
 *       {@code app/cbl/CBTRN02C.cbl} paragraph {@code 1500-VALIDATE-TRAN} (L370-L422).</li>
 * </ul>
 *
 * <p>This slice verifies the HTTP boundary (request binding, response serialization, and
 * exception-to-status mapping) while the transaction business logic itself lives in
 * {@link TransactionService} (stubbed here as a {@link MockBean}). The COBOL record under test is
 * the 350-byte {@code TRAN-RECORD} of {@code app/cpy/CVTRA05Y.cpy}.</p>
 *
 * <h2>What is verified</h2>
 * <ol>
 *   <li><b>List</b> &mdash; {@code GET /api/transactions} returns a {@link TransactionListResponse}
 *       (the flattened Spring-Data-{@code Page} shape: {@code content}, {@code totalElements},
 *       {@code totalPages}, {@code currentPage}, {@code pageSize}, {@code hasNext},
 *       {@code hasPrevious}). The controller delegates to
 *       {@link TransactionService#listTransactions(Long, String, Pageable)} &mdash; the actual
 *       service API &mdash; so tests stub and verify exactly that method.</li>
 *   <li><b>View</b> &mdash; {@code GET /api/transactions/{tranId}} returns the full
 *       {@link TransactionDto}; a missing / malformed id surfaces as
 *       {@code IllegalArgumentException} from {@link TransactionService#getTransaction(String)},
 *       which {@link GlobalExceptionHandler} maps to {@code 400 Bad Request} &mdash; the COTRN01C
 *       behaviour of treating a missing id as a re-enterable input error rather than a hard
 *       {@code 404} (per the controller contract).</li>
 *   <li><b>Create &mdash; PR-03 validation codes (the headline of this controller):</b> the four
 *       {@code CBTRN02C} reason codes are surfaced by
 *       {@link TransactionService#addTransaction(TransactionRequest)} as the dedicated exceptions
 *       and mapped by {@link GlobalExceptionHandler} to their HTTP statuses with the EXACT
 *       original COBOL messages (CBTRN02C L386/398/411/418):
 *       <ul>
 *         <li>100 {@link InvalidCardException} &rarr; {@code 400}, "INVALID CARD NUMBER FOUND";</li>
 *         <li>101 {@link AccountNotFoundException} &rarr; {@code 404}, "ACCOUNT RECORD NOT FOUND";</li>
 *         <li>102 {@link OverlimitException} &rarr; {@code 422}, "OVERLIMIT TRANSACTION";</li>
 *         <li>103 {@link ExpiredAccountException} &rarr; {@code 422},
 *             "TRANSACTION RECEIVED AFTER ACCT EXPIRATION".</li>
 *       </ul></li>
 *   <li><b>PR-10</b> &mdash; the persisted {@code tranId} is exactly 16 characters
 *       ({@code parmDate(10) + suffix(6)}); the happy-path create asserts both the value and the
 *       16-character length, and that the {@code Location} header echoes it.</li>
 *   <li><b>PR-11</b> &mdash; {@code origTimestamp}/{@code procTimestamp} are carried verbatim as
 *       26-character DB2-format strings ({@code yyyy-MM-dd-HH.mm.ss.SS'0000'}).</li>
 *   <li><b>PR-16</b> &mdash; the monetary {@code amount} is an exact scale-2 {@link BigDecimal}
 *       serialized as a plain JSON number (never {@code float}/{@code double}).</li>
 *   <li><b>Bad input</b> &mdash; an empty JSON body (all {@code @NotNull}/{@code @NotBlank} fields
 *       missing) fails {@code @Valid} &rarr; {@code 400}; malformed JSON raises
 *       {@code HttpMessageNotReadableException} &rarr; {@code 400}; in both cases the service is
 *       never invoked.</li>
 * </ol>
 *
 * <h2>Slice configuration (matches the established pattern used by every controller test in this
 * module)</h2>
 * <ul>
 *   <li>{@link WebMvcTest @WebMvcTest(controllers = TransactionController.class)} loads only the
 *       {@code TransactionController} web layer; its sole collaborator {@link TransactionService}
 *       is supplied as a {@link MockBean}.</li>
 *   <li>{@code excludeFilters} drops the entire {@code com.carddemo.security} package from the
 *       slice's component scan. A {@code @WebMvcTest} slice always registers application
 *       {@code jakarta.servlet.Filter} beans, and the production
 *       {@code com.carddemo.security.JwtAuthenticationFilter} is a {@code @Component} extending
 *       {@code OncePerRequestFilter}; left untouched it would be instantiated here and fail the
 *       context with an {@code UnsatisfiedDependencyException} because its
 *       {@code CustomAuthorityMapper} collaborator is not loaded by the slice.
 *       {@code @AutoConfigureMockMvc(addFilters = false)} only removes filters from the MockMvc
 *       dispatch &mdash; it does NOT prevent the bean from being created &mdash; so the
 *       component-scan exclusion is what actually keeps the context minimal.</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} wires the
 *       {@code @RestControllerAdvice} so HTTP status-code assertions reflect production error
 *       handling (codes 100/101/102/103 &rarr; 400/404/422/422, malformed/empty body &rarr; 400).
 *       The advice has no injected dependencies (it uses {@code com.carddemo.util.CardNumberMasker}
 *       statically), so importing it requires no additional beans.</li>
 *   <li>{@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} disables the
 *       security filter chain so the controller's HTTP behaviour is asserted in isolation.
 *       {@code TransactionController} carries no class-level {@code @PreAuthorize}: per AAP
 *       &sect;0.4.1.1 any <em>authenticated</em> caller (USER or ADMIN) may list, view, and create
 *       transactions, exactly as the legacy transaction screens were reachable from the regular
 *       user menu ({@code COMEN01C}). Each test therefore runs with {@code @WithMockUser} (a
 *       default {@code ROLE_USER} principal), which populates {@code SecurityContextHolder} via the
 *       test execution listener independently of the disabled filter chain.</li>
 * </ul>
 *
 * <h2>Why the service calls are {@code listTransactions} / {@code getTransaction} /
 * {@code addTransaction}</h2>
 * <p>{@code TransactionController} delegates to
 * {@link TransactionService#listTransactions(Long, String, Pageable)} (the COTRN00C browse, which
 * returns a {@link TransactionListResponse}), {@link TransactionService#getTransaction(String)}
 * (the COTRN01C keyed read), and {@link TransactionService#addTransaction(TransactionRequest)} (the
 * COTRN02C add + CBTRN02C validation chain). Tests stub and verify exactly those methods &mdash;
 * not a {@code findAll}/{@code findById}/{@code create} &mdash; so the slice exercises the real
 * controller-to-service contract.</p>
 *
 * <h2>Refactoring rules exercised</h2>
 * <ul>
 *   <li><b>PR-03</b> &mdash; the four validation codes 100/101/102/103 with their EXACT COBOL
 *       messages.</li>
 *   <li><b>PR-10</b> &mdash; 16-character {@code tranId} ({@code parmDate(10) + suffix(6)}).</li>
 *   <li><b>PR-11</b> &mdash; 26-character DB2 timestamp format.</li>
 *   <li><b>PR-16</b> &mdash; {@link BigDecimal} scale 2 for money, asserted with exact
 *       string-constructed values (never a {@code double} literal fixture).</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 baseline (no {@code javax.*}).</li>
 *   <li><b>PR-29</b> &mdash; {@code TransactionController} uses constructor injection of a single
 *       {@code final TransactionService}; the slice supplies it via {@code @MockBean}.</li>
 * </ul>
 *
 * @see TransactionController
 * @see TransactionService
 * @see TransactionDto
 * @see TransactionListResponse
 * @see TransactionRequest
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = TransactionController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TransactionController web-slice tests (GET list, GET /{tranId}, POST create)")
class TransactionControllerTest {

    /**
     * The canonical 16-character transaction id used across the fixtures (PR-10). The first ten
     * characters ({@code 2022071800}) are the {@code PARM-DATE} prefix; the last six
     * ({@code 000001}) are the sequential suffix &mdash; together exactly 16 characters as
     * mandated by {@code TRAN-ID PIC X(16)}.
     */
    private static final String TRAN_ID = "2022071800000001";

    /** A 16-digit card number used in the fixtures ({@code TRAN-CARD-NUM PIC X(16)}). */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Auto-configured MockMvc for the {@code TransactionController} web slice (filters disabled). */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson mapper used to serialize {@link TransactionRequest} fixtures into JSON POST bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The sole controller collaborator, replaced by a Mockito mock in the slice context. The real
     * {@code TransactionService} performs the COTRN00C list, COTRN01C view, and COTRN02C add; here
     * it is stubbed so the controller's HTTP behaviour is asserted in isolation.
     */
    @MockBean
    private TransactionService transactionService;

    /**
     * A fully-populated {@link TransactionDto} matching the CVTRA05Y 350-byte record. Field names
     * and types match the actual DTO exactly: {@code cardNumber} (not "cardNum"), {@code typeCd}
     * (not "tranTypeCd"), {@code categoryCd} as a 4-digit {@code String} (not "tranCatCd" int),
     * {@code source}/{@code description} (not "tranSource"/"tranDesc"). The {@code amount} is a
     * scale-2 {@link BigDecimal} (PR-16) and both timestamps are 26-character DB2-format strings
     * (PR-11). Used as the stubbed service response for the list and view happy paths and the
     * create happy path.
     */
    private TransactionDto sampleTransaction;

    @BeforeEach
    void setUp() {
        sampleTransaction = TransactionDto.builder()
                // PR-10: 16-char TRAN-ID = parmDate(10) + suffix(6).
                .tranId(TRAN_ID)
                // Real DTO field is `cardNumber` (mapper translates to entity `cardNum`).
                .cardNumber(CARD_NUMBER)
                .typeCd("01")
                // Real DTO field is `categoryCd`, a fixed 4-digit String (leading zeros preserved).
                .categoryCd("0001")
                .source("POS")
                .description("PURCHASE - GROCERY")
                // PR-16: BigDecimal with scale 2 built from the exact String constructor.
                .amount(new BigDecimal("125.50"))
                .merchantId(123456789L)
                .merchantName("ACME GROCERY")
                .merchantCity("SPRINGFIELD")
                .merchantZip("60601")
                // PR-11: DB2 external format yyyy-MM-dd-HH.mm.ss.SS'0000' (exactly 26 characters).
                .origTimestamp("2022-07-18-14.30.15.120000")
                .procTimestamp("2022-07-18-14.30.16.230000")
                .build();
    }

    /**
     * Tests for {@code GET /api/transactions} &mdash; the paginated list flow replacing
     * {@code app/cbl/COTRN00C.cbl} (TRANID {@code 'CT00'}). The COTRN00C PF7/PF8 browse cursor is
     * replaced by a stateless Spring {@link Pageable}; the service returns a
     * {@link TransactionListResponse} (the flattened {@code Page} shape).
     */
    @Nested
    @DisplayName("GET /api/transactions (list) — replaces COTRN00C browse")
    class ListTransactions {

        @Test
        @DisplayName("Default pagination -> 200 OK with TransactionListResponse content + metadata")
        @WithMockUser
        void shouldListTransactionsDefaultPagination() throws Exception {
            // The controller passes accountId=null, cardNumber=null and a non-null Pageable; the
            // service returns a single-element first page. any() matches the null filter arguments.
            TransactionListResponse response = new TransactionListResponse(
                    List.of(sampleTransaction), 1L, 1, 0, 10, false, false);
            when(transactionService.listTransactions(any(), any(), any(Pageable.class)))
                    .thenReturn(response);

            mockMvc.perform(get("/api/transactions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    // PR-10: the listed transaction carries the 16-char id.
                    .andExpect(jsonPath("$.content[0].tranId").value(TRAN_ID))
                    // PR-16: the listed amount is the exact scale-2 value as a JSON number.
                    .andExpect(jsonPath("$.content[0].amount").value(125.50))
                    // TransactionListResponse pagination metadata (mirrors COTRN00C page tracking).
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.currentPage").value(0))
                    .andExpect(jsonPath("$.pageSize").value(10))
                    .andExpect(jsonPath("$.hasNext").value(false))
                    .andExpect(jsonPath("$.hasPrevious").value(false));

            verify(transactionService).listTransactions(any(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("page=1&size=10 -> 200 OK (empty second page with PF7-equivalent hasPrevious)")
        @WithMockUser
        void shouldHandlePagedRequest() throws Exception {
            // Second page (zero-indexed page 1) with no content: hasPrevious=true models the
            // COTRN00C PF7 (page-backward) availability; hasNext=false models no PF8 forward.
            TransactionListResponse response = new TransactionListResponse(
                    List.of(), 0L, 0, 1, 10, false, true);
            when(transactionService.listTransactions(any(), any(), any(Pageable.class)))
                    .thenReturn(response);

            mockMvc.perform(get("/api/transactions").param("page", "1").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.currentPage").value(1))
                    .andExpect(jsonPath("$.hasPrevious").value(true));

            verify(transactionService).listTransactions(any(), any(), any(Pageable.class));
        }
    }

    /**
     * Tests for {@code GET /api/transactions/{tranId}} &mdash; the single-transaction view flow
     * replacing {@code app/cbl/COTRN01C.cbl} (TRANID {@code 'CT01'}).
     */
    @Nested
    @DisplayName("GET /api/transactions/{tranId} — replaces COTRN01C view")
    class GetTransaction {

        @Test
        @DisplayName("Existing transaction -> 200 OK with full DTO (PR-10 id, PR-11 DB2 ts, PR-16 amount)")
        @WithMockUser
        void shouldReturnTransaction() throws Exception {
            when(transactionService.getTransaction(TRAN_ID)).thenReturn(sampleTransaction);

            mockMvc.perform(get("/api/transactions/{tranId}", TRAN_ID))
                    .andExpect(status().isOk())
                    // PR-10: TRAN-ID value and exact 16-character length.
                    .andExpect(jsonPath("$.tranId").value(TRAN_ID))
                    .andExpect(jsonPath("$.tranId", hasLength(16)))
                    // PR-16: BigDecimal amount preserved with scale 2.
                    .andExpect(jsonPath("$.amount").value(125.50))
                    // Real DTO field name is `cardNumber` (not `cardNum`).
                    .andExpect(jsonPath("$.cardNumber").value(CARD_NUMBER))
                    // PR-11: DB2 timestamp format preserved verbatim as 26-char strings.
                    .andExpect(jsonPath("$.origTimestamp").value("2022-07-18-14.30.15.120000"))
                    .andExpect(jsonPath("$.procTimestamp").value("2022-07-18-14.30.16.230000"));

            verify(transactionService).getTransaction(TRAN_ID);
        }

        @Test
        @DisplayName("Unknown/missing transaction id -> 400 Bad Request (COTRN01C re-enterable input error)")
        @WithMockUser
        void shouldReturn400ForUnknownTransaction() throws Exception {
            // COTRN01C treats a missing transaction id as a re-enterable input error, NOT a hard
            // not-found: TransactionService.getTransaction throws IllegalArgumentException with the
            // COTRN02C message literal, which GlobalExceptionHandler maps to 400 (not 404).
            when(transactionService.getTransaction("9999999999999999"))
                    .thenThrow(new IllegalArgumentException("Transaction ID NOT found..."));

            mockMvc.perform(get("/api/transactions/{tranId}", "9999999999999999"))
                    .andExpect(status().isBadRequest());

            verify(transactionService).getTransaction("9999999999999999");
        }
    }

    /**
     * Tests for {@code POST /api/transactions} &mdash; the online add flow replacing
     * {@code app/cbl/COTRN02C.cbl} (TRANID {@code 'CT02'}), whose validation chain mirrors
     * {@code app/cbl/CBTRN02C.cbl} {@code 1500-VALIDATE-TRAN}. This is the headline scenario set:
     * the four PR-03 validation codes (100/101/102/103) are each asserted with their EXACT COBOL
     * messages and HTTP statuses.
     */
    @Nested
    @DisplayName("POST /api/transactions (create) — PR-03 validation codes (replaces COTRN02C)")
    class CreateTransaction {

        /**
         * Builds a fully-valid {@link TransactionRequest} that satisfies every Jakarta Bean
         * Validation constraint on the DTO, so the request passes the controller's {@code @Valid}
         * check and reaches the (stubbed) service. Required fields per the real
         * {@code TransactionRequest}: {@code accountId} ({@code @NotNull @Positive}), {@code typeCd}
         * ({@code @NotBlank}, 2 digits), {@code categoryCd} ({@code @NotNull}, 4 digits),
         * {@code description} ({@code @NotBlank}, &le; 60), {@code amount} ({@code @NotNull},
         * {@code @Digits(9,2)}), and {@code merchantId} ({@code @NotNull}). {@code cardNumber} is an
         * optional 16-digit alternative to {@code accountId} and is included here for completeness.
         *
         * @return a valid create-request payload
         */
        private TransactionRequest validRequest() {
            return TransactionRequest.builder()
                    .accountId(10000000001L)
                    .cardNumber(CARD_NUMBER)
                    .typeCd("01")
                    .categoryCd("0001")
                    .source("POS")
                    .description("PURCHASE - GROCERY")
                    .amount(new BigDecimal("125.50"))
                    .merchantId(123456789L)
                    .merchantName("ACME GROCERY")
                    .merchantCity("SPRINGFIELD")
                    .merchantZip("60601")
                    .build();
        }

        @Test
        @DisplayName("Happy path -> 201 Created with Location header + 16-char TRAN-ID (PR-10) + amount (PR-16)")
        @WithMockUser
        void shouldCreateTransaction() throws Exception {
            when(transactionService.addTransaction(any(TransactionRequest.class)))
                    .thenReturn(sampleTransaction);

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    // 201 semantics: Location header echoes the generated id (PR-10 round-trip).
                    .andExpect(header().string("Location", "/api/transactions/" + TRAN_ID))
                    // PR-10: TRAN-ID value and exact 16-character length.
                    .andExpect(jsonPath("$.tranId").value(TRAN_ID))
                    .andExpect(jsonPath("$.tranId", hasLength(16)))
                    // PR-16: BigDecimal amount preserved with scale 2.
                    .andExpect(jsonPath("$.amount").value(125.50));

            verify(transactionService).addTransaction(any(TransactionRequest.class));
        }

        @Test
        @DisplayName("PR-03 code 100 -> 400 Bad Request with EXACT message 'INVALID CARD NUMBER FOUND'")
        @WithMockUser
        void shouldRejectInvalidCardWithCode100() throws Exception {
            // No-arg constructor yields the EXACT COBOL literal (CBTRN02C L386); the String
            // constructor would append " (card=...)" and break the exact-message assertion.
            when(transactionService.addTransaction(any(TransactionRequest.class)))
                    .thenThrow(new InvalidCardException());

            TransactionRequest req = validRequest();
            req.setCardNumber("9999999999999999"); // a 16-digit card absent from the cross-reference

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    // EXACT COBOL message + numeric reason code preserved per PR-03.
                    .andExpect(jsonPath("$.message").value("INVALID CARD NUMBER FOUND"))
                    .andExpect(jsonPath("$.code").value("100"));

            verify(transactionService).addTransaction(any(TransactionRequest.class));
        }

        @Test
        @DisplayName("PR-03 code 101 -> 404 Not Found with EXACT message 'ACCOUNT RECORD NOT FOUND'")
        @WithMockUser
        void shouldRejectAccountNotFoundWithCode101() throws Exception {
            // No-arg constructor yields the EXACT COBOL literal (CBTRN02C L398); the String/Long
            // constructors would prepend "Account not found: " and break the assertion.
            when(transactionService.addTransaction(any(TransactionRequest.class)))
                    .thenThrow(new AccountNotFoundException());

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isNotFound())
                    // EXACT COBOL message + numeric reason code preserved per PR-03.
                    .andExpect(jsonPath("$.message").value("ACCOUNT RECORD NOT FOUND"))
                    .andExpect(jsonPath("$.code").value("101"));

            verify(transactionService).addTransaction(any(TransactionRequest.class));
        }

        @Test
        @DisplayName("PR-03 code 102 -> 422 Unprocessable with EXACT message 'OVERLIMIT TRANSACTION'")
        @WithMockUser
        void shouldRejectOverlimitWithCode102() throws Exception {
            when(transactionService.addTransaction(any(TransactionRequest.class)))
                    .thenThrow(new OverlimitException());

            TransactionRequest req = validRequest();
            req.setAmount(new BigDecimal("999999999.99")); // would exceed the credit limit

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableEntity())
                    // GlobalExceptionHandler emits OverlimitException.COBOL_MESSAGE verbatim per PR-03.
                    .andExpect(jsonPath("$.message").value("OVERLIMIT TRANSACTION"))
                    .andExpect(jsonPath("$.code").value("102"));

            verify(transactionService).addTransaction(any(TransactionRequest.class));
        }

        @Test
        @DisplayName("PR-03 code 103 -> 422 Unprocessable with EXACT message 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'")
        @WithMockUser
        void shouldRejectExpiredAccountWithCode103() throws Exception {
            when(transactionService.addTransaction(any(TransactionRequest.class)))
                    .thenThrow(new ExpiredAccountException());

            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isUnprocessableEntity())
                    // GlobalExceptionHandler emits ExpiredAccountException.COBOL_MESSAGE verbatim
                    // per PR-03 ("ACCT" is the intentional COBOL abbreviation, not expanded).
                    .andExpect(jsonPath("$.message").value("TRANSACTION RECEIVED AFTER ACCT EXPIRATION"))
                    .andExpect(jsonPath("$.code").value("103"));

            verify(transactionService).addTransaction(any(TransactionRequest.class));
        }

        @Test
        @DisplayName("Empty JSON body -> 400 Bad Request (bean validation; service never called)")
        @WithMockUser
        void shouldReject400OnEmptyBody() throws Exception {
            // {} leaves every @NotNull/@NotBlank field absent, so @Valid fails with
            // MethodArgumentNotValidException -> 400 before the controller body (and the service)
            // is ever reached.
            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionService);
        }

        @Test
        @DisplayName("Malformed JSON -> 400 Bad Request (service never called)")
        @WithMockUser
        void shouldReject400OnMalformedJson() throws Exception {
            // Non-JSON content raises HttpMessageNotReadableException -> 400 at body deserialization,
            // before the controller body (and the service) is ever reached.
            mockMvc.perform(post("/api/transactions")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("not a json"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(transactionService);
        }
    }
}
