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
package com.carddemo.card.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.repository.CardXrefRepository;
import com.carddemo.card.service.CardService;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.CardDetailResponseDto;
import com.carddemo.common.dto.CardListItemDto;
import com.carddemo.common.dto.CardListResponseDto;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.CardUpdateResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: Spring MVC web-slice specification for {@link CardController}, the REST facade
 *  that re-platforms three legacy CICS card transactions: ``CCLI`` / ``COCRDLIC`` (card
 *  list) as ``GET /cards``, ``CCDL`` / ``COCRDSLC`` (card detail) as
 *  ``GET /cards/{cardNumber}``, and ``CCUP`` / ``COCRDUPC`` (card update) as
 *  ``PUT /cards/{cardNumber}``. Drives only the servlet slice through {@link MockMvc} with
 *  a mocked {@link CardService}; it starts no database, no Testcontainers and no security.
 *  It locks the sixteen-digit card-number path edit, the account-filter edit, the shared
 *  session-context bridge, and the {@link GlobalExceptionHandler} status mapping for the
 *  not-found (404), optimistic-lock conflict (409) and domain (400) outcomes.
 * :output: JUnit 5 / MockMvc / AssertJ / Mockito assertions only; the card verification
 *  value and full card numbers are never asserted.
 */
@WebMvcTest(CardController.class)
@Import(GlobalExceptionHandler.class)
class CardControllerTest {

    /** :purpose: Session attribute key shared verbatim across every CardDemo controller. */
    private static final String SESSION_CONTEXT_ATTRIBUTE = "carddemoSessionContext";

    /** :purpose: Synthetic sixteen-digit card-number path variable (satisfies ``\d{16}``). */
    private static final String VALID_CARD = "1234567890123456";

    /** :purpose: Synthetic owning-account id used in non-PII detail and list assertions. */
    private static final long VALID_ACCT_ID = 12345678901L;

    /** :purpose: Servlet MockMvc entry point auto-configured by the web slice. */
    @Autowired
    private MockMvc mockMvc;

    /** :purpose: Jackson mapper (auto-configured by the slice) for request-body serialization. */
    @Autowired
    private ObjectMapper objectMapper;

    /** :purpose: Mocked card business-logic service; the controller's single collaborator. */
    @MockitoBean
    private CardService cardService;

    // Defensive: satisfy CardServiceApplication's explicit
    // @EnableJpaRepositories("com.carddemo.card.repository") so the slice context starts
    // without a real EntityManagerFactory; the controller never uses these beans.
    @MockitoBean
    private CardRepository cardRepository;

    @MockitoBean
    private CardXrefRepository cardXrefRepository;

    // Defensive: @EnableJpaRepositories also registers a shared-EntityManager infrastructure
    // bean that references "entityManagerFactory". The web slice auto-configures no JPA, so
    // supply a mock to satisfy that reference; it is wrapped lazily and never invoked here.
    @MockitoBean(name = "entityManagerFactory")
    private EntityManagerFactory entityManagerFactory;

    /**
     * :purpose: Build a non-PII card-detail stub carrying an owning account id, active
     *  status and expiry date; the full card number is set but never asserted.
     * :returns: a populated {@link CardDetailResponseDto}.
     */
    private static CardDetailResponseDto detailStub() {
        CardDetailResponseDto dto = new CardDetailResponseDto();
        dto.setCardNum(VALID_CARD);
        dto.setCardAcctId(VALID_ACCT_ID);
        dto.setCardActiveStatus("Y");
        dto.setCardExpiraionDate("2027-12-31");
        dto.setCustId(9L);
        return dto;
    }

    /**
     * :purpose: Build a card-update request satisfying the service field edits (alphabetic
     *  name, ``Y``/``N`` status, in-range ``YYYY-MM-DD`` expiry).
     * :param activeStatus: the active-status flag to carry.
     * :returns: a populated {@link CardUpdateRequestDto}.
     */
    private static CardUpdateRequestDto updateRequest(String activeStatus) {
        CardUpdateRequestDto dto = new CardUpdateRequestDto();
        dto.setCardEmbossedName("JOHN DOE");
        dto.setCardActiveStatus(activeStatus);
        dto.setCardExpiraionDate("2027-12-31");
        dto.setCardCvvCd("123");
        return dto;
    }

    /**
     * :purpose: Build a card-update response echoing the persisted non-PII fields.
     * :returns: a populated {@link CardUpdateResponseDto}.
     */
    private static CardUpdateResponseDto updateResponseStub() {
        CardUpdateResponseDto dto = new CardUpdateResponseDto();
        dto.setCardNum(VALID_CARD);
        dto.setCardAcctId(VALID_ACCT_ID);
        dto.setCardActiveStatus("Y");
        dto.setCardExpiraionDate("2027-12-31");
        dto.setCustId(9L);
        return dto;
    }

    /**
     * :purpose: Build a card-list stub of ``rowCount`` non-PII rows (owning account id and
     *  active status per row).
     * :param rowCount: the number of list rows to synthesize.
     * :returns: a populated {@link CardListResponseDto}.
     */
    private static CardListResponseDto listStub(int rowCount) {
        List<CardListItemDto> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            CardListItemDto row = new CardListItemDto();
            row.setCardAcctId(VALID_ACCT_ID);
            row.setCardNum(VALID_CARD);
            row.setCardActiveStatus("Y");
            rows.add(row);
        }
        CardListResponseDto response = new CardListResponseDto();
        response.setCards(rows);
        return response;
    }

    /**
     * :purpose: A valid sixteen-digit card-detail request returns HTTP 200 with the non-PII
     *  detail fields.
     */
    @Test
    void getCardDetail_withValid16DigitNumber_returns200() throws Exception {
        when(cardService.getCardDetail(eq(VALID_CARD), any(SessionContext.class)))
                .thenReturn(detailStub());

        mockMvc.perform(get("/cards/{cardNumber}", VALID_CARD))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardAcctId").value(VALID_ACCT_ID))
                .andExpect(jsonPath("$.cardActiveStatus").value("Y"))
                .andExpect(jsonPath("$.cardExpiraionDate").value("2027-12-31"));

        verify(cardService).getCardDetail(eq(VALID_CARD), any(SessionContext.class));
    }

    /**
     * :purpose: A card number that is non-numeric, too short, or too long is rejected by the
     *  controller path edit with HTTP 400 and the verbatim message, without touching the
     *  service.
     * :param badCard: an invalid card-number path variable.
     */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "123", "12345678901234567"})
    void getCardDetail_withInvalidNumber_returns400(String badCard) throws Exception {
        mockMvc.perform(get("/cards/{cardNumber}", badCard))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Card number if supplied must be a 16 digit number"));

        verifyNoInteractions(cardService);
    }

    /**
     * :purpose: A service {@link RecordNotFoundException} for a card read surfaces as HTTP
     *  404 with the verbatim message.
     */
    @Test
    void getCardDetail_whenServiceThrowsRecordNotFound_returns404() throws Exception {
        when(cardService.getCardDetail(eq(VALID_CARD), any(SessionContext.class)))
                .thenThrow(new RecordNotFoundException("Did not find cards for this search condition"));

        mockMvc.perform(get("/cards/{cardNumber}", VALID_CARD))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Did not find cards for this search condition"));
    }

    /**
     * :purpose: A valid update body returns HTTP 200 and reaches the service with the parsed
     *  card number and the deserialized request payload.
     */
    @Test
    void updateCard_withValidBody_returns200AndInvokesService() throws Exception {
        when(cardService.updateCard(eq(VALID_CARD), any(CardUpdateRequestDto.class), any(SessionContext.class)))
                .thenReturn(updateResponseStub());
        String json = objectMapper.writeValueAsString(updateRequest("Y"));

        mockMvc.perform(put("/cards/{cardNumber}", VALID_CARD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardActiveStatus").value("Y"));

        ArgumentCaptor<CardUpdateRequestDto> captor = ArgumentCaptor.forClass(CardUpdateRequestDto.class);
        verify(cardService).updateCard(eq(VALID_CARD), captor.capture(), any(SessionContext.class));
        assertThat(captor.getValue().getCardActiveStatus()).isEqualTo("Y");
        assertThat(captor.getValue().getCardExpiraionDate()).isEqualTo("2027-12-31");
    }

    /**
     * :purpose: A service field-edit {@link CardDemoException} surfaces as HTTP 400 with the
     *  verbatim active-status message.
     */
    @Test
    void updateCard_whenServiceRejectsField_returns400WithVerbatimMessage() throws Exception {
        when(cardService.updateCard(eq(VALID_CARD), any(CardUpdateRequestDto.class), any(SessionContext.class)))
                .thenThrow(new CardDemoException("Card Active Status must be Y or N"));
        String json = objectMapper.writeValueAsString(updateRequest("X"));

        mockMvc.perform(put("/cards/{cardNumber}", VALID_CARD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Card Active Status must be Y or N"));

        verify(cardService).updateCard(eq(VALID_CARD), any(CardUpdateRequestDto.class), any(SessionContext.class));
    }

    /**
     * :purpose: A malformed JSON body is rejected with HTTP 400 before the controller body
     *  runs, so the service is never invoked.
     */
    @Test
    void updateCard_withMalformedJson_returns400AndServiceNotInvoked() throws Exception {
        mockMvc.perform(put("/cards/{cardNumber}", VALID_CARD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"cardActiveStatus\": "))
                .andExpect(status().isBadRequest());

        verify(cardService, never()).updateCard(any(), any(), any());
    }

    /**
     * :purpose: A service {@link OptimisticLockConflictException} surfaces as HTTP 409 with
     *  the verbatim legacy conflict message.
     */
    @Test
    void updateCard_whenServiceThrowsOptimisticLockConflict_returns409() throws Exception {
        when(cardService.updateCard(eq(VALID_CARD), any(CardUpdateRequestDto.class), any(SessionContext.class)))
                .thenThrow(new OptimisticLockConflictException());
        String json = objectMapper.writeValueAsString(updateRequest("Y"));

        mockMvc.perform(put("/cards/{cardNumber}", VALID_CARD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(OptimisticLockConflictException.MESSAGE));
    }

    /**
     * :purpose: The controller creates and stores a fresh {@link SessionContext} under the
     *  shared attribute key when the session carries none.
     */
    @Test
    void getCardDetail_bridgesSessionContext_createsWhenAbsent() throws Exception {
        when(cardService.getCardDetail(eq(VALID_CARD), any(SessionContext.class)))
                .thenReturn(detailStub());
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/cards/{cardNumber}", VALID_CARD).session(session))
                .andExpect(status().isOk());

        assertThat(session.getAttribute(SESSION_CONTEXT_ATTRIBUTE)).isInstanceOf(SessionContext.class);
    }

    /**
     * :purpose: The controller reuses the existing {@link SessionContext}, passes that same
     *  instance to the service, and writes it back under the shared attribute key.
     */
    @Test
    void getCardDetail_bridgesSessionContext_reusesWhenPresent() throws Exception {
        when(cardService.getCardDetail(eq(VALID_CARD), any(SessionContext.class)))
                .thenReturn(detailStub());
        SessionContext existing = new SessionContext();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_CONTEXT_ATTRIBUTE, existing);

        mockMvc.perform(get("/cards/{cardNumber}", VALID_CARD).session(session))
                .andExpect(status().isOk());

        ArgumentCaptor<SessionContext> captor = ArgumentCaptor.forClass(SessionContext.class);
        verify(cardService).getCardDetail(eq(VALID_CARD), captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(session.getAttribute(SESSION_CONTEXT_ATTRIBUTE)).isSameAs(existing);
    }

    /**
     * :purpose: The list endpoint returns HTTP 200, defaults to page one, and delegates to
     *  the service (the seven-rows-per-page facade), asserting only non-PII row fields.
     */
    @Test
    void listCards_withDefaultPage_returns200AndInvokesService() throws Exception {
        when(cardService.listCards(any(), any(), eq(1), any(SessionContext.class)))
                .thenReturn(listStub(3));

        mockMvc.perform(get("/cards"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cards").isArray())
                .andExpect(jsonPath("$.cards.length()").value(3))
                .andExpect(jsonPath("$.cards[0].cardAcctId").value(VALID_ACCT_ID))
                .andExpect(jsonPath("$.cards[0].cardActiveStatus").value("Y"));

        verify(cardService).listCards(any(), any(), eq(1), any(SessionContext.class));
    }

    /**
     * :purpose: The ``page`` query parameter binds to the service page argument (legacy
     *  PF7/PF8 paging), here proving page two is forwarded.
     */
    @Test
    void listCards_withPageParam_bindsPageTwo() throws Exception {
        when(cardService.listCards(any(), any(), eq(2), any(SessionContext.class)))
                .thenReturn(listStub(1));

        mockMvc.perform(get("/cards").param("page", "2"))
                .andExpect(status().isOk());

        verify(cardService).listCards(any(), any(), eq(2), any(SessionContext.class));
    }

    /**
     * :purpose: An account filter that is not a one-to-eleven digit number is rejected by the
     *  controller with HTTP 400 and the verbatim message, without touching the service.
     */
    @Test
    void listCards_whenAccountFilterInvalid_returns400AndServiceNotInvoked() throws Exception {
        mockMvc.perform(get("/cards").param("accountId", "xyz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"));

        verifyNoInteractions(cardService);
    }
}
