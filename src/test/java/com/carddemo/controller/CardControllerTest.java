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
import com.carddemo.dto.card.CardDto;
import com.carddemo.dto.card.CardListResponse;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.service.CardService;
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
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc web-slice tests for {@link CardController} &mdash; the stateless REST replacement for
 * the three legacy CICS online card programs, organized by functional domain (AAP &sect;0.4.1.1):
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl} (card list, TRANID {@code 'CCLI'}) &rarr;
 *       {@code GET /api/accounts/{acctId}/cards} &mdash; the {@code CARDAIX} alternate-index browse
 *       ({@code STARTBR}/{@code READNEXT}/{@code READPREV} with PF7/PF8 paging) becomes stateless
 *       Spring Data {@code Pageable} pagination (AAP &sect;0.6.1, &sect;0.6.2);</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} (card view, TRANID {@code 'CCDL'}) &rarr;
 *       {@code GET /api/cards/{cardNum}} &mdash; a card-number keyed {@code READ CARDDAT};</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} (card update, TRANID {@code 'CCUP'}) &rarr;
 *       {@code PUT /api/cards/{cardNum}} &mdash; a {@code READ UPDATE}/{@code REWRITE} with
 *       optimistic locking.</li>
 * </ul>
 *
 * <p>The COBOL record under test is the 150-byte {@code CARD-RECORD} of {@code app/cpy/CVACT02Y.cpy}.
 * This slice verifies the HTTP boundary only &mdash; request binding (path variables, pagination
 * query parameters, JSON body), response serialization, and exception-to-HTTP-status mapping &mdash;
 * while the card business logic itself lives in {@link CardService} (stubbed here as a
 * {@link MockBean}).</p>
 *
 * <h2>Controller&rarr;service contract exercised (the REAL method names, not {@code findBy*}/{@code update})</h2>
 * <p>{@code CardController} delegates to the actual {@link CardService} API:</p>
 * <ul>
 *   <li>{@link CardService#listByAccount(Long, int, int)} &mdash; returns a {@link CardListResponse}
 *       (a flattened {@code Page} record: {@code content}, {@code totalElements}, {@code totalPages},
 *       {@code currentPage}, {@code pageSize}, {@code hasNext}, {@code hasPrevious}). The controller
 *       <em>destructures</em> the bound {@code Pageable} into {@code (acctId, page, size)} before the
 *       call, so the tests {@code verify} the exact {@code page}/{@code size} ints to prove the
 *       {@code ?page=&size=} query-parameter binding;</li>
 *   <li>{@link CardService#getCard(String)} &mdash; returns a {@link CardDto};</li>
 *   <li>{@link CardService#updateCard(String, CardDto)} &mdash; returns the refreshed {@link CardDto}.</li>
 * </ul>
 *
 * <h2>Slice configuration (matches the established pattern of every controller test in this module)</h2>
 * <ul>
 *   <li>{@link WebMvcTest @WebMvcTest(controllers = CardController.class)} loads only the
 *       {@code CardController} web layer; its sole collaborator {@link CardService} is supplied as a
 *       {@link MockBean}.</li>
 *   <li>{@code excludeFilters} drops the entire {@code com.carddemo.security} package from the
 *       slice's component scan. A {@code @WebMvcTest} slice always registers application
 *       {@code jakarta.servlet.Filter} beans, and the production
 *       {@code com.carddemo.security.JwtAuthenticationFilter} is a {@code @Component} extending
 *       {@code OncePerRequestFilter}; left untouched it would be instantiated here and fail the
 *       context with an {@code UnsatisfiedDependencyException} (its {@code CustomAuthorityMapper}
 *       collaborator is not loaded by the slice). {@code @AutoConfigureMockMvc(addFilters = false)}
 *       only removes filters from the MockMvc dispatch &mdash; it does NOT prevent bean creation
 *       &mdash; so the component-scan exclusion is what keeps the context minimal.</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} wires the {@code @RestControllerAdvice}
 *       so HTTP status-code assertions reflect production error handling
 *       ({@code AccountNotFoundException} &rarr; 404, {@code ObjectOptimisticLockingFailureException}
 *       &rarr; 409, {@code IllegalStateException} &rarr; 422, malformed body / field edits &rarr; 400).
 *       The advice has no injected dependencies, so importing it requires no additional beans.</li>
 *   <li>{@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} disables the security
 *       filter chain so the controller's HTTP behaviour is asserted in isolation. {@code CardController}
 *       carries no class-level {@code @PreAuthorize}: per AAP &sect;0.4.1.1 any <em>authenticated</em>
 *       caller (USER or ADMIN) may list, view, and update cards, exactly as the legacy card
 *       transactions were reachable from the regular user menu ({@code COMEN01C}). Each test therefore
 *       runs with {@code @WithMockUser} (a default {@code ROLE_USER} principal), which populates
 *       {@code SecurityContextHolder} via the test execution listener independently of the disabled
 *       filter chain.</li>
 * </ul>
 *
 * <h2>Refactoring rules exercised</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; the 16-digit {@code cardNum} ({@code CARD-NUM PIC X(16)}) is carried as a
 *       {@code String} so leading zeros survive; {@code acctId} mirrors {@code CARD-ACCT-ID PIC 9(11)}.</li>
 *   <li><b>PR-22</b> &mdash; a concurrent update raises {@link ObjectOptimisticLockingFailureException}
 *       (the JPA {@code @Version} replacement for the VSAM {@code READ UPDATE} exclusive lock,
 *       COCRDUPC L208), mapped to {@code 409 Conflict}.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 baseline (no {@code javax.*}).</li>
 *   <li><b>PR-29</b> &mdash; {@code CardController} uses constructor injection of a single
 *       {@code final CardService}; the slice supplies it via {@code @MockBean}.</li>
 * </ul>
 *
 * @see CardController
 * @see CardService
 * @see CardDto
 * @see CardListResponse
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = CardController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CardController web-slice tests (GET/PUT card endpoints)")
class CardControllerTest {

    /**
     * Account id used for the happy-path card-list lookups. Mirrors COBOL
     * {@code CARD-ACCT-ID PIC 9(11)} and lies within the controller's
     * {@code @Min(1)}/{@code @Max(99999999999L)} bounds. The value (100000001) is within
     * {@code int} range, so JSON-number assertions may use a plain integer literal.
     */
    private static final long ACCT_ID = 100000001L;

    /** A second, distinct valid account id used for the empty-result list scenario. */
    private static final long OTHER_ACCT_ID = 100000002L;

    /**
     * Canonical 16-digit card number ({@code CARD-NUM PIC X(16)}) used as the primary key in the
     * view and update flows. Carried as a {@code String} (PR-13) so a leading-zero PAN survives.
     */
    private static final String CARD_NUM = "4111111111111111";

    /** A valid-format 16-digit card number that the stubbed service treats as absent (404 path). */
    private static final String UNKNOWN_CARD_NUM = "0000000000000000";

    /** Auto-configured MockMvc for the {@code CardController} web slice (security filters disabled). */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson mapper used to serialize {@link CardDto} fixtures into JSON request bodies for PUT. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The sole controller collaborator, replaced by a Mockito mock in the slice context. The real
     * {@code CardService} performs the COCRDLIC/COCRDSLC/COCRDUPC logic; here it is stubbed so the
     * controller's HTTP behaviour is asserted in isolation.
     */
    @MockBean
    private CardService cardService;

    /**
     * A fully-populated, <em>valid</em> {@link CardDto} matching the CVACT02Y 150-byte layout. Field
     * names and types match the actual DTO exactly: {@code cvvCode} (not "cvvCd") and
     * {@code expirationDate} is an ISO {@code yyyy-MM-dd} {@code String} (not {@code LocalDate}).
     * Because every field is valid, this fixture passes {@code @Valid} body validation and is
     * therefore suitable as the request body for the not-found, optimistic-lock, no-change, and
     * field-edit PUT scenarios (where the stubbed service is what raises the exception).
     */
    private CardDto sampleCard;

    @BeforeEach
    void setUp() {
        sampleCard = CardDto.builder()
                .cardNum(CARD_NUM)
                .accountId(ACCT_ID)
                .cvvCode("123")
                .embossedName("JOHN DOE")
                .expirationDate("2025-12-31")
                .activeStatus("Y")
                .build();
    }

    /**
     * Tests for {@code GET /api/accounts/{acctId}/cards} &mdash; the paginated card-list flow
     * replacing {@code app/cbl/COCRDLIC.cbl} (TRANID {@code 'CCLI'}). The COCRDLIC {@code CARDAIX}
     * browse with PF7/PF8 paging is replaced by stateless Spring Data {@code Pageable}; the service
     * returns a {@link CardListResponse} (the flattened {@code Page} shape). An empty result is a
     * valid {@code 200} with an empty {@code content} list &mdash; never a {@code 404}.
     */
    @Nested
    @DisplayName("GET /api/accounts/{acctId}/cards — list paginated")
    class ListCardsByAccount {

        @Test
        @DisplayName("List cards (?page=0&size=10) -> 200 OK with CardListResponse content + metadata")
        @WithMockUser
        void shouldListCardsWithDefaultPagination() throws Exception {
            CardListResponse response = new CardListResponse(
                    List.of(sampleCard), 1L, 1, 0, 10, false, false);
            when(cardService.listByAccount(eq(ACCT_ID), anyInt(), anyInt()))
                    .thenReturn(response);

            mockMvc.perform(get("/api/accounts/{acctId}/cards", ACCT_ID)
                            .param("page", "0").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].cardNum").value(CARD_NUM))
                    .andExpect(jsonPath("$.content[0].accountId").value(100000001))
                    .andExpect(jsonPath("$.content[0].embossedName").value("JOHN DOE"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.currentPage").value(0))
                    .andExpect(jsonPath("$.pageSize").value(10));

            // The controller destructures the bound Pageable (page=0, size=10) into the service
            // ints, replacing the COCRDLIC PF7/PF8 cursor; verify the exact arguments.
            verify(cardService).listByAccount(eq(ACCT_ID), eq(0), eq(10));
        }

        @Test
        @DisplayName("Pagination ?page=1&size=10 -> 200 OK; proves page/size query-param binding")
        @WithMockUser
        void shouldPaginateToSecondPage() throws Exception {
            CardListResponse response = new CardListResponse(
                    List.of(), 5L, 1, 1, 10, false, true);
            when(cardService.listByAccount(eq(ACCT_ID), anyInt(), anyInt()))
                    .thenReturn(response);

            mockMvc.perform(get("/api/accounts/{acctId}/cards", ACCT_ID)
                            .param("page", "1").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.currentPage").value(1));

            // page=1,size=10 must be destructured to listByAccount(acctId, 1, 10) — the stateless
            // equivalent of the COCRDLIC PF8 page-down navigation.
            verify(cardService).listByAccount(eq(ACCT_ID), eq(1), eq(10));
        }

        @Test
        @DisplayName("Account with no cards -> 200 OK with empty content (NOT 404)")
        @WithMockUser
        void shouldReturnEmptyPageWhenNoCards() throws Exception {
            // No page/size params: the controller's @PageableDefault(size = 7) applies, so the
            // service is invoked with size 7 — matched here by anyInt().
            CardListResponse empty = new CardListResponse(
                    List.of(), 0L, 0, 0, 7, false, false);
            when(cardService.listByAccount(eq(OTHER_ACCT_ID), anyInt(), anyInt()))
                    .thenReturn(empty);

            mockMvc.perform(get("/api/accounts/{acctId}/cards", OTHER_ACCT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));

            verify(cardService).listByAccount(eq(OTHER_ACCT_ID), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Multiple cards for one account -> 200 OK with content array")
        @WithMockUser
        void shouldReturnMultipleCards() throws Exception {
            CardDto secondCard = CardDto.builder()
                    .cardNum("5500000000000004")
                    .accountId(ACCT_ID)
                    .cvvCode("456")
                    .embossedName("JANE DOE")
                    .expirationDate("2026-06-30")
                    .activeStatus("Y")
                    .build();
            CardListResponse response = new CardListResponse(
                    List.of(sampleCard, secondCard), 2L, 1, 0, 7, false, false);
            when(cardService.listByAccount(eq(ACCT_ID), anyInt(), anyInt()))
                    .thenReturn(response);

            mockMvc.perform(get("/api/accounts/{acctId}/cards", ACCT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].cardNum").value(CARD_NUM))
                    .andExpect(jsonPath("$.content[1].cardNum").value("5500000000000004"))
                    .andExpect(jsonPath("$.content[1].embossedName").value("JANE DOE"))
                    .andExpect(jsonPath("$.totalElements").value(2));

            verify(cardService).listByAccount(eq(ACCT_ID), anyInt(), anyInt());
        }
    }

    /**
     * Tests for {@code GET /api/cards/{cardNum}} &mdash; the single-card view flow replacing
     * {@code app/cbl/COCRDSLC.cbl} (TRANID {@code 'CCDL'}, a card-number keyed
     * {@code EXEC CICS READ DATASET('CARDDAT')}).
     */
    @Nested
    @DisplayName("GET /api/cards/{cardNum} — view single")
    class GetCard {

        @Test
        @DisplayName("Existing card -> 200 OK with full CardDto (CVACT02Y 150-byte layout)")
        @WithMockUser
        void shouldReturnCard() throws Exception {
            when(cardService.getCard(CARD_NUM)).thenReturn(sampleCard);

            mockMvc.perform(get("/api/cards/{cardNum}", CARD_NUM))
                    .andExpect(status().isOk())
                    // CARD-NUM PIC X(16) -> String (PR-13: leading zeros preserved).
                    .andExpect(jsonPath("$.cardNum").value(CARD_NUM))
                    // CARD-ACCT-ID PIC 9(11) -> Long; 100000001 is within int range.
                    .andExpect(jsonPath("$.accountId").value(100000001))
                    // CARD-CVV-CD PIC 9(03) -> String field named cvvCode (NOT cvvCd).
                    .andExpect(jsonPath("$.cvvCode").value("123"))
                    .andExpect(jsonPath("$.embossedName").value("JOHN DOE"))
                    // CARD-EXPIRAION-DATE PIC X(10) -> expirationDate String (PR-14 typo corrected).
                    .andExpect(jsonPath("$.expirationDate").value("2025-12-31"))
                    .andExpect(jsonPath("$.activeStatus").value("Y"));

            // The controller delegates the keyed read to the service (actual API: getCard).
            verify(cardService).getCard(CARD_NUM);
        }

        @Test
        @DisplayName("Unknown card -> 404 Not Found (COCRDSLC NOTFND, code 101)")
        @WithMockUser
        void shouldReturn404ForUnknownCard() throws Exception {
            // The service surfaces the COBOL DFHRESP(NOTFND) branch as AccountNotFoundException
            // carrying the exact COCRDSLC message literal; GlobalExceptionHandler maps it to 404 and
            // copies the message + request URI onto the uniform ErrorResponse payload.
            when(cardService.getCard(UNKNOWN_CARD_NUM))
                    .thenThrow(AccountNotFoundException.withMessage(
                            "Did not find cards for this search condition"));

            mockMvc.perform(get("/api/cards/{cardNum}", UNKNOWN_CARD_NUM))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.path").value("/api/cards/0000000000000000"));

            verify(cardService).getCard(UNKNOWN_CARD_NUM);
        }
    }

    /**
     * Tests for {@code PUT /api/cards/{cardNum}} &mdash; the card-update flow replacing
     * {@code app/cbl/COCRDUPC.cbl} (TRANID {@code 'CCUP'}), including its optimistic-locking
     * ({@code READ UPDATE}/{@code REWRITE}) race-condition semantics and the COCRDUPC field-edit /
     * "no change detected" branches.
     *
     * <p>Every request uses {@code .with(csrf())}: although {@code addFilters = false} disables the
     * security filter chain (making CSRF a no-op here), including the token keeps these tests correct
     * should CSRF protection ever be enabled for the slice.</p>
     */
    @Nested
    @DisplayName("PUT /api/cards/{cardNum} — update")
    class UpdateCard {

        @Test
        @DisplayName("Valid update -> 200 OK with updated CardDto")
        @WithMockUser
        void shouldUpdateCard() throws Exception {
            CardDto updated = CardDto.builder()
                    .cardNum(CARD_NUM)
                    .accountId(ACCT_ID)
                    .cvvCode("999")
                    .embossedName("JOHN DOE UPDATED")
                    .expirationDate("2027-12-31")
                    .activeStatus("Y")
                    .build();
            // The path cardNum is authoritative; the service ignores any cardNum on the body, so
            // any(CardDto.class) is the precise matcher for the deserialized request body.
            when(cardService.updateCard(eq(CARD_NUM), any(CardDto.class)))
                    .thenReturn(updated);

            mockMvc.perform(put("/api/cards/{cardNum}", CARD_NUM)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updated)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cardNum").value(CARD_NUM))
                    .andExpect(jsonPath("$.embossedName").value("JOHN DOE UPDATED"))
                    .andExpect(jsonPath("$.expirationDate").value("2027-12-31"));

            verify(cardService).updateCard(eq(CARD_NUM), any(CardDto.class));
        }

        @Test
        @DisplayName("Update unknown card -> 404 Not Found (COCRDUPC card absent, code 101)")
        @WithMockUser
        void shouldReturn404OnUnknownCard() throws Exception {
            when(cardService.updateCard(eq(UNKNOWN_CARD_NUM), any(CardDto.class)))
                    .thenThrow(AccountNotFoundException.withMessage(
                            "Did not find this account in cards database"));

            mockMvc.perform(put("/api/cards/{cardNum}", UNKNOWN_CARD_NUM)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sampleCard)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.path").value("/api/cards/0000000000000000"));

            verify(cardService).updateCard(eq(UNKNOWN_CARD_NUM), any(CardDto.class));
        }

        @Test
        @DisplayName("Concurrent update -> 409 Conflict (PR-22 — replaces VSAM READ UPDATE lock; COCRDUPC L208)")
        @WithMockUser
        void shouldReturn409OnOptimisticLockFailure() throws Exception {
            // The service rethrows the JPA @Version conflict as ObjectOptimisticLockingFailureException
            // carrying the exact COCRDUPC L208 message; GlobalExceptionHandler maps it to 409.
            when(cardService.updateCard(eq(CARD_NUM), any(CardDto.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException("Card", CARD_NUM));

            mockMvc.perform(put("/api/cards/{cardNum}", CARD_NUM)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sampleCard)))
                    .andExpect(status().isConflict());

            verify(cardService).updateCard(eq(CARD_NUM), any(CardDto.class));
        }

        @Test
        @DisplayName("No change detected -> 422 Unprocessable Entity (COCRDUPC L188 IllegalStateException)")
        @WithMockUser
        void shouldReturn422OnNoChange() throws Exception {
            // COCRDUPC "no change detected with respect to values fetched" (L188): the service raises
            // IllegalStateException, which GlobalExceptionHandler maps to 422.
            when(cardService.updateCard(eq(CARD_NUM), any(CardDto.class)))
                    .thenThrow(new IllegalStateException(
                            "No change detected with respect to values fetched."));

            mockMvc.perform(put("/api/cards/{cardNum}", CARD_NUM)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sampleCard)))
                    .andExpect(status().isUnprocessableEntity());

            verify(cardService).updateCard(eq(CARD_NUM), any(CardDto.class));
        }

        @Test
        @DisplayName("Service field-edit failure -> 400 Bad Request (COCRDUPC L196 IllegalArgumentException)")
        @WithMockUser
        void shouldReturn400OnServiceFieldEdit() throws Exception {
            // The body is valid (passes @Valid) and reaches the service, which rejects a field value
            // exactly as the COCRDUPC active-status edit does (L196); IllegalArgumentException -> 400.
            when(cardService.updateCard(eq(CARD_NUM), any(CardDto.class)))
                    .thenThrow(new IllegalArgumentException("Card Active Status must be Y or N"));

            mockMvc.perform(put("/api/cards/{cardNum}", CARD_NUM)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sampleCard)))
                    .andExpect(status().isBadRequest());

            verify(cardService).updateCard(eq(CARD_NUM), any(CardDto.class));
        }

        @Test
        @DisplayName("Empty request body -> 400 Bad Request (HttpMessageNotReadableException)")
        @WithMockUser
        void shouldReject400OnEmptyBody() throws Exception {
            // An empty body cannot be deserialized to CardDto; the dispatcher raises
            // HttpMessageNotReadableException during @RequestBody resolution (before the service is
            // reached), which GlobalExceptionHandler maps to 400.
            mockMvc.perform(put("/api/cards/{cardNum}", CARD_NUM)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(""))
                    .andExpect(status().isBadRequest());
        }
    }
}
