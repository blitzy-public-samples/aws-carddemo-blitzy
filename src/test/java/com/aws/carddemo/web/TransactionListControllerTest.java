/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aws.carddemo.config.JacksonConfig;
import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.TransactionListRequest;
import com.aws.carddemo.mapper.TransactionMapper;
import com.aws.carddemo.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest @WebMvcTest}
 * slice test for {@link TransactionListController} &mdash; the Java re-platform of the
 * online COBOL program {@code COTRN00C} (CICS transaction {@code CT00}, BMS map
 * {@code COTRN00}), relocated during the migration to {@code legacy/cbl/COTRN00C.cbl}.
 *
 * <p>The Transaction List screen is a key-ordered paged browse of the {@code TRANSACT}
 * store, ten rows per page ({@code SEL0001}&hellip;{@code SEL0010}), with a
 * transaction-id search key and a per-row single-character selection marker. These tests
 * exercise the controller's observable REST contract while mocking the
 * {@link TransactionService} collaborator, so that no database or Docker is required
 * (pure web slice). The behaviors pinned here trace to the migration parity hotspots
 * AAP&nbsp;H2 (BMS field-level UI contract), H5 (VSAM browse &rarr; paged query) and H3
 * (monetary fidelity via {@code BigDecimal}).</p>
 *
 * <h2>Contract verified</h2>
 * <ul>
 *   <li>Unauthenticated access is rejected with {@code 401} (stateless HTTP&nbsp;Basic API,
 *       {@link SecurityConfig}).</li>
 *   <li>The first page returns up to ten rows in ascending {@code tranId} order; the
 *       service receives a {@link Pageable} of size {@code 10} sorted by {@code tranId},
 *       and the response {@code pageNumber} is one-based.</li>
 *   <li>{@code PF7}/{@code PF8} page backward/forward (with top/bottom boundary messages),
 *       and {@code PF3} returns to the main menu {@code COMEN01C} ({@code CM00}).</li>
 *   <li>An {@code ENTER} row marker {@code 'S'} navigates to the transaction-view program
 *       {@code COTRN01C} ({@code CT01}) carrying the selected id via response headers; any
 *       other non-blank marker yields the invalid-selection message; a non-numeric search
 *       key yields the numeric message; both stay on the list screen with no navigation.</li>
 *   <li>Each row's monetary {@code amount} is rendered as a scale-2 plain decimal
 *       ({@link BigDecimal}; never {@code double}/{@code float}) and each row {@code date}
 *       is rendered {@code MM/DD/YY} by the real {@link TransactionMapper}.</li>
 * </ul>
 *
 * <p>The real {@link TransactionMapper} and {@link JacksonConfig} are imported so field
 * projection and JSON serialization (plain {@code BigDecimal}, {@code non_null} inclusion)
 * match production exactly; {@link SecurityConfig} is imported so the security filter chain
 * (and its {@code 401} problem-detail entry point) is exercised. Authentication itself is
 * driven declaratively by {@link WithMockUser} (authenticated cases) and
 * {@link WithAnonymousUser} (the {@code 401} case), so the production
 * {@code CardDemoUserDetailsService} is neither loaded nor needed in this slice.</p>
 */
@WebMvcTest(TransactionListController.class)
@Import({SecurityConfig.class, TransactionMapper.class, JacksonConfig.class})
@WithMockUser
class TransactionListControllerTest {

    /** Base path of the Transaction List endpoint ({@code COTRN00C} / {@code CT00}). */
    private static final String BASE_PATH = "/api/v1/transactions";

    /** A well-formed 26-character origination timestamp; its date part renders as {@link #EXPECTED_DATE}. */
    private static final String ORIG_TS = "2023-06-15-12.34.56.123456";

    /** The {@code MM/DD/YY} rendering the real mapper derives from {@link #ORIG_TS}. */
    private static final String EXPECTED_DATE = "06/15/23";

    /** Navigation header naming the COBOL program to enter next (the legacy {@code XCTL} target). */
    private static final String HDR_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Navigation header naming the CICS transaction id to enter next. */
    private static final String HDR_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** Navigation header carrying the selected 16-character transaction id for the view screen. */
    private static final String HDR_SELECTED_ID = "X-CardDemo-Selected-Transaction-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Mocked browse service; the controller's only business collaborator. */
    @MockitoBean
    private TransactionService transactionService;

    // ------------------------------------------------------------------------
    // Test fixtures / helpers
    // ------------------------------------------------------------------------

    /**
     * Builds a fully-populated stub {@link Transaction} with a deterministic 16-digit,
     * zero-padded {@code tranId} derived from {@code idNum}.
     *
     * @param idNum   the numeric suffix used to form the {@code tranId}
     * @param origTs  the origination timestamp (drives the rendered list date)
     * @param desc    the transaction description
     * @param amount  the signed monetary amount (scale 2)
     * @return a stub transaction row
     */
    private static Transaction row(int idNum, String origTs, String desc, BigDecimal amount) {
        return new Transaction(
                String.format("%016d", idNum), // TRAN-ID (16-char primary key)
                "01",                          // TRAN-TYPE-CD
                1,                             // TRAN-CAT-CD
                "POS",                         // TRAN-SOURCE
                desc,                          // TRAN-DESC
                amount,                        // TRAN-AMT (BigDecimal, scale 2)
                1L,                            // TRAN-MERCHANT-ID
                "MERCHANT NAME",               // TRAN-MERCHANT-NAME
                "CITY",                        // TRAN-MERCHANT-CITY
                "12345",                       // TRAN-MERCHANT-ZIP
                "0000000000000001",            // TRAN-CARD-NUM
                origTs,                        // TRAN-ORIG-TS
                origTs);                       // TRAN-PROC-TS
    }

    /**
     * Builds a full page of ten stub rows (ids {@code 1}&hellip;{@code 10}), each dated
     * {@link #ORIG_TS} with a fixed scale-2 amount of {@code 100.00}.
     *
     * @return ten stub transaction rows in ascending id order
     */
    private static List<Transaction> sampleRows() {
        return IntStream.rangeClosed(1, 10)
                .mapToObj(i -> row(i, ORIG_TS, "TX DESCRIPTION " + i, new BigDecimal("100.00")))
                .toList();
    }

    /**
     * Stubs {@link TransactionService#listTransactionsFrom(String, Pageable)} to answer
     * with a {@link PageImpl} that reflects the requested {@link Pageable} (so the response
     * page number is derived correctly) and the supplied total element count (so
     * {@link org.springframework.data.domain.Page#hasNext() hasNext} governs the forward
     * boundary).
     *
     * @param rows  the content each page returns
     * @param total the total number of elements across all pages
     */
    private void stubBrowse(List<Transaction> rows, long total) {
        when(transactionService.listTransactionsFrom(any(), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(rows, invocation.getArgument(1), total));
    }

    /**
     * Serializes a request DTO to its JSON body using the application {@link ObjectMapper}.
     *
     * @param request the request DTO
     * @return the JSON representation
     * @throws Exception if serialization fails
     */
    private String json(TransactionListRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    // ------------------------------------------------------------------------
    // A. Security — unauthenticated access is rejected
    // ------------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    @DisplayName("A. Unauthenticated GET is rejected with 401 and the service is never called")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transactionService);
    }

    // ------------------------------------------------------------------------
    // B. First page — paging parity, sort, one-based page number, field rendering
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("B. First page returns up to ten rows sorted by tranId; page number is one-based")
    void firstPageReturnsTenRowsSortedByTranId() throws Exception {
        stubBrowse(sampleRows(), 25L);

        String body = mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(10))
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.transactionName").value("CT00"))
                .andExpect(jsonPath("$.programName").value("COTRN00C"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(header().doesNotExist(HDR_NEXT_PROGRAM))
                // Field rendering via the real mapper: MM/DD/YY date and scale-2 amount.
                .andExpect(jsonPath("$.transactions[0].transactionId").value("0000000000000001"))
                .andExpect(jsonPath("$.transactions[0].date").value(EXPECTED_DATE))
                .andExpect(jsonPath("$.transactions[0].amount").isNumber())
                .andReturn().getResponse().getContentAsString();

        // Monetary fidelity: rendered as a plain, scale-2 decimal (never scientific / float).
        assertThat(body).contains("\"amount\":100.00");

        // The browse the service received must be page 0, size 10, ascending by tranId.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionService).listTransactionsFrom(isNull(), pageableCaptor.capture());
        Pageable used = pageableCaptor.getValue();
        assertThat(used.getPageSize()).isEqualTo(10);
        assertThat(used.getPageNumber()).isEqualTo(0);
        Sort.Order order = used.getSort().getOrderFor("tranId");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    // ------------------------------------------------------------------------
    // C. PF8 forward / PF7 back and their page boundaries
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("C1. PF8 advances to the next page (0-based to the service, 1-based in the response)")
    void pf8AdvancesToNextPage() throws Exception {
        stubBrowse(sampleRows(), 25L);

        mockMvc.perform(post(BASE_PATH)
                        .param("page", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TransactionListRequest(null, null, PfKeyAction.PF8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("2"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        // The forward turn must request 0-based page index 1 with the fixed page size 10.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionService, atLeastOnce()).listTransactionsFrom(any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getAllValues())
                .anyMatch(p -> p.getPageNumber() == 1 && p.getPageSize() == 10);
    }

    @Test
    @DisplayName("C2. PF7 returns to the previous page")
    void pf7ReturnsToPreviousPage() throws Exception {
        stubBrowse(sampleRows(), 25L);

        mockMvc.perform(post(BASE_PATH)
                        .param("page", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TransactionListRequest(null, null, PfKeyAction.PF7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionService, atLeastOnce()).listTransactionsFrom(any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getAllValues()).anyMatch(p -> p.getPageNumber() == 0);
    }

    @Test
    @DisplayName("C3. PF7 at the first page stays put and shows the top-of-page message")
    void pf7AtTopShowsBoundaryMessage() throws Exception {
        stubBrowse(sampleRows(), 25L);

        mockMvc.perform(post(BASE_PATH)
                        .param("page", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TransactionListRequest(null, null, PfKeyAction.PF7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.errorMessage").value("You are already at the top of the page..."));
    }

    @Test
    @DisplayName("C4. PF8 at the last page stays put and shows the bottom-of-page message")
    void pf8AtBottomShowsBoundaryMessage() throws Exception {
        // total == page size => a single page, so there is no next page.
        stubBrowse(sampleRows(), 10L);

        mockMvc.perform(post(BASE_PATH)
                        .param("page", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TransactionListRequest(null, null, PfKeyAction.PF8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.errorMessage").value("You are already at the bottom of the page..."));
    }

    // ------------------------------------------------------------------------
    // D. ENTER row selection 'S' -> navigate to the transaction-view screen
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("D. ENTER with row marker 'S' navigates to the transaction view (COTRN01C / CT01)")
    void enterWithSelectMarkerNavigatesToView() throws Exception {
        stubBrowse(sampleRows(), 25L);

        // Row 0 of the current page carries tranId "0000000000000001"; marking it 'S' selects it.
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TransactionListRequest(null, List.of("S"), PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(header().string(HDR_NEXT_PROGRAM, "COTRN01C"))
                .andExpect(header().string(HDR_NEXT_TRANSACTION, "CT01"))
                .andExpect(header().string(HDR_SELECTED_ID, "0000000000000001"));
    }

    // ------------------------------------------------------------------------
    // E. ENTER invalid selection flag -> invalid-selection message, no navigation
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("E. ENTER with a non-'S' row marker yields the invalid-selection message and no navigation")
    void enterWithInvalidMarkerShowsMessage() throws Exception {
        stubBrowse(sampleRows(), 25L);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TransactionListRequest(null, List.of("X"), PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Invalid selection. Valid value is S"))
                .andExpect(header().doesNotExist(HDR_NEXT_PROGRAM));
    }

    // ------------------------------------------------------------------------
    // F. ENTER non-numeric transaction-id filter -> numeric message, no navigation
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("F. ENTER with a non-numeric search key yields the numeric message and no navigation")
    void enterWithNonNumericSearchKeyShowsMessage() throws Exception {
        stubBrowse(sampleRows(), 25L);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TransactionListRequest("ABC", null, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Tran ID must be Numeric ..."))
                .andExpect(header().doesNotExist(HDR_NEXT_PROGRAM));
    }

    // ------------------------------------------------------------------------
    // G. PF3 -> back to the main menu (COMEN01C / CM00)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("G. PF3 returns to the main menu (COMEN01C / CM00)")
    void pf3ReturnsToMainMenu() throws Exception {
        stubBrowse(sampleRows(), 25L);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TransactionListRequest(null, null, PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string(HDR_NEXT_PROGRAM, "COMEN01C"))
                .andExpect(header().string(HDR_NEXT_TRANSACTION, "CM00"));
    }

    // ------------------------------------------------------------------------
    // H. Other / unmapped attention key -> invalid-key message, list still rendered
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("H. An unmapped attention key yields the invalid-key message with the list still rendered")
    void unmappedKeyShowsInvalidKeyMessage() throws Exception {
        stubBrowse(sampleRows(), 25L);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TransactionListRequest(null, null, PfKeyAction.PF5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value("Invalid key pressed. Please see below..."))
                .andExpect(jsonPath("$.transactions.length()").value(10))
                .andExpect(header().doesNotExist(HDR_NEXT_PROGRAM));
    }

    // ------------------------------------------------------------------------
    // I. Field-contract parity (COTRN00 row) + monetary fidelity
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("I. Row field contract matches COTRN00 and amount is a scale-2 signed plain decimal")
    void rowFieldContractAndMonetaryFidelity() throws Exception {
        stubBrowse(List.of(row(42, ORIG_TS, "PURCHASE AT STORE", new BigDecimal("-50.00"))), 1L);

        String body = mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                // TRNIDnnO / TDATEnnO / TDESCnnO / TAMT00nO row field contract from COTRN00.CPY.
                .andExpect(jsonPath("$.transactions[0].transactionId").value("0000000000000042"))
                .andExpect(jsonPath("$.transactions[0].date").value(EXPECTED_DATE))
                .andExpect(jsonPath("$.transactions[0].description").value("PURCHASE AT STORE"))
                .andExpect(jsonPath("$.transactions[0].amount").isNumber())
                .andReturn().getResponse().getContentAsString();

        // Signed, scale-2, plain decimal: no floating-point artifacts and no scientific notation.
        assertThat(body).contains("\"amount\":-50.00");
    }
}
