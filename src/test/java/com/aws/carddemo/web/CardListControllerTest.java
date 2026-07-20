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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardListRequest;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.CardMapper;
import com.aws.carddemo.service.CardService;

/**
 * {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest}
 * slice test for {@link CardListController} &mdash; the REST re-platform of the
 * online COBOL program {@code COCRDLIC} (CICS transaction {@code CCLI}, BMS map
 * {@code COCRDLI}). The legacy sources are retained under {@code legacy/**}
 * ({@code legacy/cbl/COCRDLIC.cbl}, {@code legacy/cpy-bms/COCRDLI.CPY},
 * {@code legacy/bms/COCRDLI.bms}).
 *
 * <p>These tests assert the observable Card-List contract that defines
 * behavioural parity with the mainframe program (AAP&nbsp;&sect;0.7.1 hotspots
 * H2, H5 and &sect;0.7.3 L1):</p>
 * <ul>
 *   <li><strong>Paging parity</strong> &mdash; the browse is always ordered by
 *       card number ascending (the {@code CARDDATA.VSAM.KSDS} key order of the
 *       legacy {@code STARTBR}/{@code READNEXT} browse) and renders at most seven
 *       rows per page (COBOL {@code WS-MAX-SCREEN-LINES VALUE 7}). The page
 *       indicator returned to the client is 1-based.</li>
 *   <li><strong>PF-key semantics</strong> &mdash; {@code PF7} pages backward,
 *       {@code PF8} pages forward, {@code PF3} exits to the main menu
 *       ({@code COMEN01C}/{@code CM00}), and any other key redisplays the current
 *       page with an invalid-key notice (the legacy invalid-key remap).</li>
 *   <li><strong>Row-selection navigation</strong> &mdash; a single {@code S}
 *       selection navigates to the card view ({@code COCRDSLC}/{@code CCDL}) and a
 *       single {@code U} selection navigates to the card update
 *       ({@code COCRDUPC}/{@code CCUP}), carrying the chosen card's number and
 *       owning account forward (COBOL 88-levels {@code VIEW-REQUESTED-ON VALUE
 *       'S'} / {@code UPDATE-REQUESTED-ON VALUE 'U'}).</li>
 *   <li><strong>CVV never present</strong> &mdash; the card verification value is
 *       sensitive (AAP&nbsp;&sect;0.9.3) and is never part of the list contract;
 *       every response is asserted to expose no {@code cvv} property and to never
 *       contain the stub CVV value.</li>
 * </ul>
 *
 * <h2>Slice configuration</h2>
 * <p>Only the {@link CardListController} web layer is loaded. The real
 * {@link SecurityConfig} and the real {@link CardMapper} are imported so the
 * authentication rules and the entity&rarr;DTO projection are exercised exactly
 * as they run in production; the business/data-access {@link CardService} is the
 * only mocked collaborator. Spring's {@code @MockitoBean} is used (never the
 * deprecated {@code @MockBean}). Security filters are on and CSRF is disabled by
 * the real {@link SecurityConfig} (a stateless HTTP&nbsp;Basic API), so the POST
 * cases need no CSRF token. The {@code GlobalExceptionHandler}
 * {@code @RestControllerAdvice} is auto-detected by the slice, so a Bean
 * Validation failure surfaces as an RFC&nbsp;7807 {@code application/problem+json}
 * response.</p>
 *
 * <p>The class is authenticated by a class-level {@link WithMockUser}; the single
 * unauthenticated case overrides it with {@link WithAnonymousUser}. The test is
 * deterministic and headless (pure {@code @WebMvcTest}; no Testcontainers or
 * Docker) and contributes to the mandated &ge;80% line coverage.</p>
 */
@WebMvcTest(CardListController.class)
@Import({SecurityConfig.class, CardMapper.class})
@WithMockUser(username = "TESTUSER")
class CardListControllerTest {

    // ------------------------------------------------------------------
    // Contract constants mirrored from CardListController (parity anchors)
    // ------------------------------------------------------------------

    /** The controller base path ({@code @RequestMapping("/api/v1/cards")}). */
    private static final String BASE_PATH = "/api/v1/cards";

    /** Rows per browse page &mdash; COBOL {@code WS-MAX-SCREEN-LINES VALUE 7}. */
    private static final int PAGE_SIZE = 7;

    /** JPA sort property preserving the VSAM card-number key order. */
    private static final String SORT_PROPERTY = "cardNum";

    /** Navigation response header carrying the next program (COBOL {@code CCARD-NEXT-PROG}). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Navigation response header carrying the next transaction id. */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** Navigation response header carrying the selected card number. */
    private static final String HEADER_CARD_NUMBER = "X-CardDemo-Card-Number";

    /** Navigation response header carrying the selected card's owning account. */
    private static final String HEADER_ACCOUNT_ID = "X-CardDemo-Account-Id";

    /** Row-select view target program ({@code LIT-CARDDTLPGM VALUE 'COCRDSLC'}). */
    private static final String CARD_VIEW_PROGRAM = "COCRDSLC";

    /** Row-select view target transaction ({@code LIT-CARDDTLTRANID VALUE 'CCDL'}). */
    private static final String CARD_VIEW_TRANSACTION = "CCDL";

    /** Row-select update target program ({@code LIT-CARDUPDPGM VALUE 'COCRDUPC'}). */
    private static final String CARD_UPDATE_PROGRAM = "COCRDUPC";

    /** Row-select update target transaction ({@code LIT-CARDUPDTRANID VALUE 'CCUP'}). */
    private static final String CARD_UPDATE_TRANSACTION = "CCUP";

    /** Back / exit target program on PF3 ({@code LIT-MENUPGM VALUE 'COMEN01C'}). */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Back / exit target transaction on PF3 ({@code LIT-MENUTRANID VALUE 'CM00'}). */
    private static final String MENU_TRANSACTION = "CM00";

    /** Verbatim COBOL {@code WS-INFORM-REC-ACTIONS}. */
    private static final String MSG_INFORM_REC_ACTIONS =
            "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /** Verbatim COBOL {@code WS-NO-RECORDS-FOUND}. */
    private static final String MSG_NO_RECORDS_FOUND =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /** Verbatim COBOL PF7-on-first-page boundary message. */
    private static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** Verbatim COBOL PF8-past-last-page boundary message. */
    private static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /** Verbatim COBOL {@code WS-EXIT-MESSAGE} set on PF3. */
    private static final String MSG_EXIT = "PF03 PRESSED.EXITING";

    /** Invalid-key redisplay notice (documented benign UI-contract addition). */
    private static final String MSG_INVALID_KEY = "INVALID KEY PRESSED";

    /**
     * A distinctive, non-numeric sentinel used as the stub card's CVV. It is
     * deliberately unique (and contains no {@code "cvv"} substring) so the
     * CVV-never-present assertions can prove independently that (a) no
     * {@code cvv} <em>property</em> is serialized and (b) the stored CVV
     * <em>value</em> never leaks into any response body.
     */
    private static final String CVV_SENTINEL = "SEC3T-321-XYZ";

    // ------------------------------------------------------------------
    // Slice collaborators
    // ------------------------------------------------------------------

    /** MockMvc entry point auto-configured by the {@code @WebMvcTest} slice. */
    @Autowired
    private MockMvc mockMvc;

    /** The application {@link ObjectMapper} used to render request bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The only mocked collaborator: the card browse/service layer. The real
     * {@link CardMapper} is imported (not mocked) so the entity&rarr;DTO
     * projection contract is exercised end-to-end.
     */
    @MockitoBean
    private CardService cardService;

    // ==================================================================
    // Test fixtures / helpers
    // ==================================================================

    /**
     * Builds a stub {@link Card} entity. The embossed name and expiration date
     * are fixed, non-sensitive placeholders; the CVV is always the
     * {@link #CVV_SENTINEL} so any accidental leak is detectable.
     *
     * @param cardNum the 16-character card number (primary key)
     * @param acctId  the owning account id
     * @param status  the one-character active-status flag
     * @return a populated, non-persisted {@link Card}
     */
    private static Card card(String cardNum, long acctId, String status) {
        return new Card(cardNum, acctId, CVV_SENTINEL, "JOHN Q PUBLIC", "2027-12-31", status);
    }

    /**
     * Builds a deterministic list of exactly {@link #PAGE_SIZE} stub cards. Row
     * {@code i} (0-based) has card number {@code 4000000000000001 + i}, owning
     * account {@code 42 + i}, and an alternating {@code Y}/{@code N} status. The
     * values are chosen so no field is a substring of another and none collides
     * with the {@link #CVV_SENTINEL}.
     *
     * @return a mutable list of seven cards in card-number order
     */
    private static List<Card> sampleCards() {
        List<Card> cards = new ArrayList<>(PAGE_SIZE);
        for (int i = 0; i < PAGE_SIZE; i++) {
            String cardNum = String.format("%016d", 4000000000000001L + i);
            String status = (i % 2 == 0) ? "Y" : "N";
            cards.add(card(cardNum, 42L + i, status));
        }
        return cards;
    }

    /**
     * Wraps content into a {@link Page} whose {@link Pageable} carries the given
     * zero-based page index, the fixed {@link #PAGE_SIZE}, and the card-number
     * sort. Because {@link CardListController} derives the 1-based response page
     * indicator from {@code page.getNumber() + 1}, the {@code pageIndex} chosen
     * here drives the asserted {@code pageNumber}.
     *
     * @param content   the page content (may be empty)
     * @param pageIndex the zero-based page index carried by the pageable
     * @param total     the total element count across all pages
     * @return the assembled page
     */
    private static Page<Card> pageOf(List<Card> content, int pageIndex, long total) {
        Pageable pageable = PageRequest.of(pageIndex, PAGE_SIZE, Sort.by(SORT_PROPERTY));
        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Serializes a {@link CardListRequest} to a JSON string for a POST body.
     *
     * @param request the request DTO
     * @return the JSON representation
     * @throws Exception if serialization fails
     */
    private String json(CardListRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    /**
     * Asserts the mandated CVV-never-present rule on a rendered response body:
     * the body exposes no {@code cvv} property (checked case-insensitively) and
     * never contains the stub CVV value.
     *
     * @param result the completed MVC result
     * @throws Exception if the body cannot be read
     */
    private static void assertNoCvv(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(CVV_SENTINEL);
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("cvv");
    }

    // ==================================================================
    // A. Authentication
    // ==================================================================

    /**
     * Test A &mdash; an unauthenticated request to the protected card-list
     * resource is rejected with {@code 401 Unauthorized} and never reaches the
     * service. The class-level {@link WithMockUser} is overridden here with
     * {@link WithAnonymousUser}; because {@link SecurityConfig} secures every
     * business endpoint ({@code anyRequest().authenticated()}), the anonymous
     * request is stopped by the security filter chain.
     */
    @Test
    @WithAnonymousUser
    void unauthenticatedRequestIsRejectedWith401() throws Exception {
        mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isUnauthorized());

        verify(cardService, never()).listCards(any(), any(), any());
    }

    // ==================================================================
    // B. First page (fresh entry)
    // ==================================================================

    /**
     * Test B &mdash; the fresh entry ({@code GET}) returns the first, unfiltered
     * browse page ordered by card number ascending, with at most seven rows and a
     * 1-based page indicator of {@code "1"}. The {@link Pageable} handed to the
     * service is asserted (via an {@link ArgumentCaptor}) to request page size
     * {@value #PAGE_SIZE} and the {@value #SORT_PROPERTY} ascending sort, proving
     * the {@code CARDDATA.VSAM.KSDS} key-order parity. Each row exposes only the
     * account number, card number, and status; the CVV never appears.
     */
    @Test
    void firstPageReturnsUpToSevenRowsOrderedByCardNumber() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 0, 20));

        MvcResult result = mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.cards.length()").value(PAGE_SIZE))
                .andExpect(jsonPath("$.cards[0].accountNumber").value("00000000042"))
                .andExpect(jsonPath("$.cards[0].cardNumber").value("4000000000000001"))
                .andExpect(jsonPath("$.cards[0].cardStatus").value("Y"))
                .andExpect(jsonPath("$.infoMessage").value(MSG_INFORM_REC_ACTIONS))
                .andReturn();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(cardService).listCards(isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(PAGE_SIZE);
        Sort.Order order = pageable.getValue().getSort().getOrderFor(SORT_PROPERTY);
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);

        assertNoCvv(result);
    }

    // ==================================================================
    // C. Filtered list
    // ==================================================================

    /**
     * Test C &mdash; an {@code ENTER} submission carrying an account and a card
     * filter passes both to the service exactly as parsed by the controller: the
     * account is parsed to a {@link Long} and the card filter is passed through as
     * the raw {@link String}, mirroring {@code CardListController.parseAccountFilter}
     * and the {@code 9500-FILTER-RECORDS} filter arguments.
     */
    @Test
    void enterAppliesAccountAndCardFiltersFromTheRequest() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 0, 7));

        CardListRequest request = new CardListRequest(
                "12345678901", "1234567890123456", null, PfKeyAction.ENTER);

        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andReturn();

        ArgumentCaptor<Long> account = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> cardFilter = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(cardService).listCards(account.capture(), cardFilter.capture(), pageable.capture());
        assertThat(account.getValue()).isEqualTo(12_345_678_901L);
        assertThat(cardFilter.getValue()).isEqualTo("1234567890123456");
        assertThat(pageable.getValue().getPageNumber()).isZero();

        assertNoCvv(result);
    }

    // ==================================================================
    // D. PF7 / PF8 paging (and boundary clamps)
    // ==================================================================

    /**
     * Test D1 &mdash; {@code PF8} from page one pages forward: the service is
     * called for the second page (zero-based index {@code 1}) and the response
     * page indicator is the 1-based {@code "2"}.
     */
    @Test
    void pf8PagesForwardToTheNextPage() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 1, 20));

        CardListRequest request = new CardListRequest(null, null, null, PfKeyAction.PF8);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("2"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(cardService).listCards(isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
    }

    /**
     * Test D2 &mdash; {@code PF7} from page two pages backward: the service is
     * called for the first page (zero-based index {@code 0}) and the response page
     * indicator is the 1-based {@code "1"}.
     */
    @Test
    void pf7PagesBackwardToThePreviousPage() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 0, 20));

        CardListRequest request = new CardListRequest(null, null, null, PfKeyAction.PF7);

        mockMvc.perform(post(BASE_PATH)
                        .param("page", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("1"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(cardService).listCards(isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    /**
     * Test D2b (F-P4-C) &mdash; an extreme {@code page} query parameter cannot
     * overflow the JPA offset ({@code index * PAGE_SIZE}). The controller clamps
     * the derived zero-based page index to {@code Integer.MAX_VALUE / PAGE_SIZE}
     * so the browse degrades to an empty final page (HTTP 200) instead of
     * propagating an arithmetic overflow into a repository {@code OFFSET} and
     * surfacing as an HTTP 500. Because the {@code CardService} is mocked in this
     * web slice the guard is asserted directly on the {@link Pageable} handed to
     * the service: its page number is the clamp ceiling, never the raw request
     * value.
     */
    @Test
    void extremePageParameterIsClampedToAvoidOffsetOverflow() throws Exception {
        int maxPageIndex = Integer.MAX_VALUE / PAGE_SIZE;
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(new ArrayList<>(), maxPageIndex, 0));

        CardListRequest request = new CardListRequest(null, null, null, PfKeyAction.ENTER);

        mockMvc.perform(post(BASE_PATH)
                        .param("page", String.valueOf(Integer.MAX_VALUE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                // Bounded outcome: a same-screen 200, never an HTTP 500.
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(cardService).listCards(isNull(), isNull(), pageable.capture());
        // The raw page (Integer.MAX_VALUE) would map to zero-based 2147483646; the
        // clamp caps it at Integer.MAX_VALUE / PAGE_SIZE so index * PAGE_SIZE stays
        // within int range (F-P4-C).
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(maxPageIndex);
    }

    /**
     * Test D3 &mdash; {@code PF7} on the first page is clamped: the browse cannot
     * move earlier, so the current (first) page is redisplayed with the verbatim
     * boundary message {@value #MSG_NO_PREVIOUS_PAGES} and the page indicator
     * stays {@code "1"} (COBOL {@code CCARD-AID-PFK07 AND CA-FIRST-PAGE}).
     */
    @Test
    void pf7OnFirstPageIsClampedWithNoPreviousPagesMessage() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 0, 7));

        CardListRequest request = new CardListRequest(null, null, null, PfKeyAction.PF7);

        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .param("page", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.errorMessage").value(MSG_NO_PREVIOUS_PAGES))
                .andReturn();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(cardService).listCards(isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();

        assertNoCvv(result);
    }

    /**
     * Test D4 &mdash; {@code PF8} past the last page is clamped: the requested
     * next page (index {@code 1}) is empty, so the controller redisplays the
     * current (last) populated page with the verbatim boundary message
     * {@value #MSG_NO_MORE_PAGES} (COBOL {@code CCARD-AID-PFK08} at end of file).
     * The stub answers per requested page index so both the empty next-page probe
     * and the current-page redisplay are exercised.
     */
    @Test
    void pf8PastLastPageIsClampedWithNoMorePagesMessage() throws Exception {
        when(cardService.listCards(any(), any(), any())).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(2);
            if (pageable.getPageNumber() == 0) {
                return pageOf(sampleCards(), 0, 7);
            }
            return pageOf(List.of(), pageable.getPageNumber(), 7);
        });

        CardListRequest request = new CardListRequest(null, null, null, PfKeyAction.PF8);

        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .param("page", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value("1"))
                .andExpect(jsonPath("$.cards.length()").value(PAGE_SIZE))
                .andExpect(jsonPath("$.errorMessage").value(MSG_NO_MORE_PAGES))
                .andReturn();

        assertNoCvv(result);
    }

    // ==================================================================
    // E. Row-selection navigation
    // ==================================================================

    /**
     * Test E1 &mdash; a single {@code S} row selection on {@code ENTER} navigates
     * to the card <em>view</em> screen: the navigation headers carry
     * {@value #CARD_VIEW_PROGRAM} / {@value #CARD_VIEW_TRANSACTION} and the chosen
     * card's number and owning account are carried forward (COBOL 88-level
     * {@code VIEW-REQUESTED-ON VALUE 'S'} &rarr; {@code XCTL LIT-CARDDTLPGM}). The
     * account header is the raw account id (the row-display projection zero-pads it
     * separately in the body).
     */
    @Test
    void enterWithViewSelectionNavigatesToCardView() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 0, 20));

        // Select row 1 (index 0) with 'S' -> card view (COCRDSLC / CCDL).
        CardListRequest request = new CardListRequest(
                null, null, List.of("S"), PfKeyAction.ENTER);

        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, CARD_VIEW_PROGRAM))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, CARD_VIEW_TRANSACTION))
                .andExpect(header().string(HEADER_CARD_NUMBER, "4000000000000001"))
                .andExpect(header().string(HEADER_ACCOUNT_ID, "42"))
                .andReturn();

        assertNoCvv(result);
    }

    /**
     * Test E2 &mdash; a single {@code U} row selection on {@code ENTER} (on the
     * second row, index {@code 1}) navigates to the card <em>update</em> screen:
     * the navigation headers carry {@value #CARD_UPDATE_PROGRAM} /
     * {@value #CARD_UPDATE_TRANSACTION} and the second row's card number and
     * account are carried forward (COBOL 88-level {@code UPDATE-REQUESTED-ON VALUE
     * 'U'} &rarr; {@code XCTL LIT-CARDUPDPGM}). Blank leading flags are skipped, so
     * the selection index correctly maps to screen row two.
     */
    @Test
    void enterWithUpdateSelectionNavigatesToCardUpdate() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 0, 20));

        // Blank row 1, select row 2 (index 1) with 'U' -> card update (COCRDUPC / CCUP).
        CardListRequest request = new CardListRequest(
                null, null, List.of("", "U"), PfKeyAction.ENTER);

        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, CARD_UPDATE_PROGRAM))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, CARD_UPDATE_TRANSACTION))
                .andExpect(header().string(HEADER_CARD_NUMBER, "4000000000000002"))
                .andExpect(header().string(HEADER_ACCOUNT_ID, "43"))
                .andReturn();

        assertNoCvv(result);
    }

    // ==================================================================
    // F. PF3 exit to main menu
    // ==================================================================

    /**
     * Test F &mdash; {@code PF3} exits to the main menu: the navigation headers
     * carry {@value #MENU_PROGRAM} / {@value #MENU_TRANSACTION}, the body shows the
     * verbatim exit message {@value #MSG_EXIT} with no card rows, and the browse
     * service is never queried because control transfers away from the screen
     * (COBOL {@code XCTL PROGRAM(LIT-MENUPGM)}).
     */
    @Test
    void pf3ExitsToMainMenuWithoutQueryingCards() throws Exception {
        CardListRequest request = new CardListRequest(null, null, null, PfKeyAction.PF3);

        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, MENU_PROGRAM))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, MENU_TRANSACTION))
                .andExpect(jsonPath("$.errorMessage").value(MSG_EXIT))
                .andExpect(jsonPath("$.cards.length()").value(0))
                .andReturn();

        verify(cardService, never()).listCards(any(), any(), any());
        assertNoCvv(result);
    }

    // ==================================================================
    // G. Unmapped / missing attention key
    // ==================================================================

    /**
     * Test G &mdash; an attention key other than Enter / PF3 / PF7 / PF8 (here
     * {@code PF1}) redisplays the current page with the invalid-key notice
     * {@value #MSG_INVALID_KEY} while still rendering the list, re-expressing the
     * COBOL invalid-key remap that fell through to a redisplay.
     */
    @Test
    void unmappedKeyRedisplaysCurrentPageWithInvalidKeyMessage() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 0, 20));

        CardListRequest request = new CardListRequest(null, null, null, PfKeyAction.PF1);

        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_KEY))
                .andExpect(jsonPath("$.cards.length()").value(PAGE_SIZE))
                .andReturn();

        assertNoCvv(result);
    }

    /**
     * Test G (missing key) &mdash; a submission with no attention key
     * ({@code action = null}) is treated the same as an unmapped key: the current
     * page is redisplayed with {@value #MSG_INVALID_KEY}. A {@code null} action
     * passes Bean Validation (no constraint applies to it), so the request reaches
     * the controller's null-key branch rather than failing with 400.
     */
    @Test
    void missingKeyIsTreatedAsInvalidKey() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 0, 20));

        CardListRequest request = new CardListRequest(null, null, null, null);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_KEY));
    }

    // ==================================================================
    // H. CVV never present (mandatory)
    // ==================================================================

    /**
     * Test H &mdash; the mandatory CVV-never-present rule (AAP&nbsp;&sect;0.9.3).
     * Every stub card carries the {@link #CVV_SENTINEL} verification value, yet
     * the rendered list response exposes no {@code cvv} property and never
     * contains the sentinel anywhere in its body. A positive sanity assertion
     * confirms the rows really were rendered, so the CVV assertions cannot pass
     * vacuously on an empty body.
     */
    @Test
    void listResponseNeverExposesTheCardVerificationValue() throws Exception {
        when(cardService.listCards(any(), any(), any()))
                .thenReturn(pageOf(sampleCards(), 0, 20));

        MvcResult result = mockMvc.perform(get(BASE_PATH))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("4000000000000001"); // sanity: rows really rendered
        assertThat(body).doesNotContain(CVV_SENTINEL);
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("cvv");
    }

    // ==================================================================
    // I. Field-contract parity + validation
    // ==================================================================

    /**
     * Test I &mdash; a malformed account filter (non-numeric) fails the DTO's
     * {@code @Pattern} field-format contract and is rejected with
     * {@code 400 Bad Request} as an RFC&nbsp;7807 {@code application/problem+json}
     * body produced by {@code GlobalExceptionHandler}. The problem detail names
     * the offending field ({@code accountId}) but, for the sensitive-data rule,
     * never echoes the rejected value; the service is never invoked because
     * validation runs before the controller body.
     */
    @Test
    void malformedAccountFilterIsRejectedWith400AndDoesNotEchoTheValue() throws Exception {
        CardListRequest request = new CardListRequest(
                "ABC123", null, null, PfKeyAction.ENTER);

        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("accountId");
        assertThat(body).doesNotContain("ABC123");

        verify(cardService, never()).listCards(any(), any(), any());
    }
}
