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
package com.carddemo.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.repository.CardXrefRepository;
import com.carddemo.common.domain.Card;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.dto.CardDetailResponseDto;
import com.carddemo.common.dto.CardListResponseDto;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.CardUpdateResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.RecordNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * :purpose: Pure Mockito unit specification for {@link CardService}, the card
 *     business-logic service that re-platforms three legacy CICS programs:
 *     ``COCRDLIC`` (``CCLI``, seven-rows-per-page card list), ``COCRDSLC``
 *     (``CCDL``, card detail read) and ``COCRDUPC`` (``CCUP``, validated card
 *     update). The suite uses only {@link MockitoExtension}; it starts no Spring
 *     context, no test container and no database, and drives all three
 *     collaborators ({@link CardRepository}, {@link CardXrefRepository} and
 *     {@link CardMapper}) as mocks. It locks the account/card-number filter
 *     edits, admin-versus-user account scoping, the seven-rows-per-page browse
 *     cap, the fail-fast update validation edits with their byte-exact messages,
 *     the no-change short-circuit and the ordered re-read-then-rewrite update
 *     sequence. Typed domain exceptions are asserted by message; DTO fixtures are
 *     mocks asserted by identity and interaction, never by field value, and the
 *     card verification value and full primary account number are never asserted.
 * :output: JUnit 5 / AssertJ / Mockito assertions only; no container and no I/O.
 */
@ExtendWith(MockitoExtension.class)
class CardServiceTest {

    // -- Collaborators (exactly three; no date-utility collaborator is injected). --

    /** :purpose: Mock repository for the ``cards`` store. */
    @Mock
    private CardRepository cardRepository;

    /** :purpose: Mock repository for the ``card_xref`` store. */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** :purpose: Mock entity-to-DTO mapper for card responses. */
    @Mock
    private CardMapper cardMapper;

    /** :purpose: Service under test, wired through its single three-argument constructor. */
    @InjectMocks
    private CardService cardService;

    // -- Verbatim spec-literal messages (COCRDLIC / COCRDSLC / COCRDUPC). ----------

    /** :purpose: ``COCRDLIC`` L1022 account-filter edit message (no space after the comma). */
    private static final String MSG_ACCT_FILTER_11 =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** :purpose: ``COCRDLIC`` L1058 card-number-filter edit message (no space after the comma). */
    private static final String MSG_CARD_FILTER_16 =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** :purpose: ``COCRDSLC`` L154 / ``COCRDUPC`` L204 card-read-miss message (no trailing period). */
    private static final String MSG_DETAIL_NOT_FOUND =
            "Did not find cards for this search condition";

    /** :purpose: ``COCRDUPC`` L184 embossed-name edit message. */
    private static final String MSG_NAME_ALPHA =
            "Card name can only contain alphabets and spaces";

    /** :purpose: ``COCRDUPC`` L196 active-status edit message. */
    private static final String MSG_STATUS_YN = "Card Active Status must be Y or N";

    /** :purpose: ``COCRDUPC`` L198 expiry-month edit message. */
    private static final String MSG_EXPIRY_MONTH =
            "Card expiry month must be between 1 and 12";

    /** :purpose: ``COCRDUPC`` L200 expiry-year edit message. */
    private static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";

    // -- Opaque, non-real fixtures (never asserted by value; PII-safe). ------------

    /** :purpose: Well-known fake sixteen-digit card number (PAN); never asserted by value. */
    private static final String CARD_NUM = "4111111111111111";

    /** :purpose: Fake eleven-digit owning account id (non-sensitive). */
    private static final Long ACCT_ID = 12345678901L;

    /** :purpose: Second fake eleven-digit account id used for cross-account scoping tests. */
    private static final Long OTHER_ACCT_ID = 22222222222L;

    /** :purpose: Fake nine-digit owning customer id for cross-reference fixtures. */
    private static final Long FAKE_CUST_ID = 123456789L;

    /** :purpose: Valid embossed name (letters and a space) used across update fixtures. */
    private static final String VALID_NAME = "John Smith";

    /** :purpose: Valid expiry date in ``YYYY-MM-DD`` form (month 06, year 2025 both in range). */
    private static final String VALID_EXPIRY = "2025-06-15";

    /**
     * :purpose: Build a real {@link Card} carrying only the identity fields the card-list
     *     browse reads (card number for the primary-key sort, owning account id for scoping).
     * :param cardNum: the sixteen-character card number.
     * :param acctId: the owning account id.
     * :returns: a populated {@link Card}.
     */
    private static Card card(String cardNum, Long acctId) {
        Card c = new Card();
        c.setCardNum(cardNum);
        c.setCardAcctId(acctId);
        return c;
    }

    /**
     * :purpose: Build a real {@link Card} carrying the editable fields the update
     *     no-change compare reads, preserving the legacy ``Expiraion`` accessor spelling.
     * :param cardNum: the sixteen-character card number.
     * :param acctId: the owning account id.
     * :param name: the embossed cardholder name.
     * :param status: the single-character active-status flag.
     * :param expiraionDate: the expiry date in ``YYYY-MM-DD`` form.
     * :param cvv: the card verification value (opaque; never asserted by value).
     * :returns: a populated {@link Card}.
     */
    private static Card card(String cardNum, Long acctId, String name, String status,
                             String expiraionDate, String cvv) {
        Card c = card(cardNum, acctId);
        c.setCardEmbossedName(name);
        c.setCardActiveStatus(status);
        c.setCardExpiraionDate(expiraionDate);
        c.setCardCvvCd(cvv);
        return c;
    }

    /**
     * :purpose: Build a list of distinct, ordered, non-real cards for pagination fixtures.
     * :param count: the number of cards to create.
     * :returns: a mutable {@link List} of {@code count} cards with unique card numbers.
     */
    private static List<Card> cards(int count) {
        List<Card> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(card(String.format("4%015d", i), ACCT_ID));
        }
        return list;
    }

    /**
     * :purpose: Build a mock update request whose three edit-relevant getters are stubbed
     *     leniently, so a fail-fast validation short-circuit never leaves an unused stub
     *     under the extension's strict stubbing.
     * :param name: the embossed name the request returns.
     * :param status: the active-status flag the request returns.
     * :param expiraionDate: the expiry date the request returns.
     * :returns: a {@link CardUpdateRequestDto} mock.
     */
    private CardUpdateRequestDto mockRequest(String name, String status, String expiraionDate) {
        CardUpdateRequestDto request = mock(CardUpdateRequestDto.class);
        lenient().when(request.getCardEmbossedName()).thenReturn(name);
        lenient().when(request.getCardActiveStatus()).thenReturn(status);
        lenient().when(request.getCardExpiraionDate()).thenReturn(expiraionDate);
        return request;
    }

    // =====================================================================================
    // LIST scenarios (COCRDLIC, CICS CCLI) -- seven rows per page, filter edits, scoping.
    // =====================================================================================

    /**
     * :purpose: An administrator with no filter browses the full card master and receives
     *     the mapper's list response.
     */
    @Test
    void listCards_adminNoFilter_returnsMappedResponse() {
        SessionContext admin = mock(SessionContext.class);
        when(admin.getUserType()).thenReturn(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardRepository.findAll()).thenReturn(cards(3));
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        CardListResponseDto result = cardService.listCards(null, null, 1, admin);

        assertThat(result).isSameAs(expected);
        verify(cardMapper).toListResponse(any());
    }

    /**
     * :purpose: Exactly seven candidate cards fill a single page with no overflow; the mapper
     *     receives all seven rows.
     */
    @Test
    @SuppressWarnings("unchecked")
    void listCards_exactlySevenRows_singlePageNoOverflow() {
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardRepository.findAll()).thenReturn(cards(7));
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(null, null, 1, null);

        ArgumentCaptor<List<Card>> pageCaptor = ArgumentCaptor.forClass(List.class);
        verify(cardMapper).toListResponse(pageCaptor.capture());
        assertThat(pageCaptor.getValue()).hasSize(7);
    }

    /**
     * :purpose: More than seven candidates cap the first page at seven rows, pinning the
     *     ``WS-MAX-SCREEN-LINES VALUE 7`` browse limit.
     */
    @Test
    @SuppressWarnings("unchecked")
    void listCards_moreThanSevenRows_firstPageCapsAtSeven() {
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardRepository.findAll()).thenReturn(cards(8));
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(null, null, 1, null);

        ArgumentCaptor<List<Card>> pageCaptor = ArgumentCaptor.forClass(List.class);
        verify(cardMapper).toListResponse(pageCaptor.capture());
        assertThat(pageCaptor.getValue()).hasSize(7);
    }

    /**
     * :purpose: Page two of an eight-card result returns the one-row remainder slice at
     *     offset seven.
     */
    @Test
    @SuppressWarnings("unchecked")
    void listCards_pageTwo_returnsRemainderSlice() {
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardRepository.findAll()).thenReturn(cards(8));
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(null, null, 2, null);

        ArgumentCaptor<List<Card>> pageCaptor = ArgumentCaptor.forClass(List.class);
        verify(cardMapper).toListResponse(pageCaptor.capture());
        assertThat(pageCaptor.getValue()).hasSize(1);
    }

    /**
     * :purpose: A non-admin user's browse is scoped to the account carried in the session,
     *     using the account-filtered read and never the unfiltered browse.
     */
    @Test
    void listCards_nonAdminScopedToSessionAccount() {
        SessionContext user = mock(SessionContext.class);
        when(user.getUserType()).thenReturn(SessionContext.UserType.CDEMO_USRTYP_USER);
        when(user.getAcctId()).thenReturn(ACCT_ID);
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardRepository.findByCardAcctId(ACCT_ID)).thenReturn(cards(2));
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        CardListResponseDto result = cardService.listCards(null, null, 1, user);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<Long> acctCaptor = ArgumentCaptor.forClass(Long.class);
        verify(cardRepository).findByCardAcctId(acctCaptor.capture());
        assertThat(acctCaptor.getValue()).isEqualTo(ACCT_ID);
        verify(cardRepository, never()).findAll();
    }

    /**
     * :purpose: A non-admin user cannot widen scope with a broader account filter; the
     *     effective read still uses the session account id.
     */
    @Test
    void listCards_nonAdminIgnoresBroaderAcctFilter() {
        SessionContext user = mock(SessionContext.class);
        when(user.getUserType()).thenReturn(SessionContext.UserType.CDEMO_USRTYP_USER);
        when(user.getAcctId()).thenReturn(ACCT_ID);
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardRepository.findByCardAcctId(ACCT_ID)).thenReturn(cards(1));
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(OTHER_ACCT_ID, null, 1, user);

        ArgumentCaptor<Long> acctCaptor = ArgumentCaptor.forClass(Long.class);
        verify(cardRepository).findByCardAcctId(acctCaptor.capture());
        assertThat(acctCaptor.getValue()).isEqualTo(ACCT_ID);
    }

    /**
     * :purpose: An administrator supplying a valid eleven-digit account filter reads that
     *     account's cards.
     */
    @Test
    void listCards_adminWithValidAcctFilter_usesFilter() {
        SessionContext admin = mock(SessionContext.class);
        when(admin.getUserType()).thenReturn(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardRepository.findByCardAcctId(ACCT_ID)).thenReturn(cards(2));
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(ACCT_ID, null, 1, admin);

        ArgumentCaptor<Long> acctCaptor = ArgumentCaptor.forClass(Long.class);
        verify(cardRepository).findByCardAcctId(acctCaptor.capture());
        assertThat(acctCaptor.getValue()).isEqualTo(ACCT_ID);
    }

    /**
     * :purpose: A valid sixteen-digit card-number filter resolves the single card by keyed
     *     read rather than the unfiltered browse.
     */
    @Test
    void listCards_validCardFilter_usesFindById() {
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card(CARD_NUM, ACCT_ID)));
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        CardListResponseDto result = cardService.listCards(null, CARD_NUM, 1, null);

        assertThat(result).isSameAs(expected);
        verify(cardRepository).findById(CARD_NUM);
        verify(cardRepository, never()).findAll();
    }

    /**
     * :purpose: An account filter wider than eleven digits fails the filter edit before any
     *     store access.
     */
    @Test
    void listCards_invalidAcctFilterTooLarge_throwsCardDemoException() {
        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.listCards(100_000_000_000L, null, 1, null))
                .withMessage(MSG_ACCT_FILTER_11);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A non-positive account filter fails the same eleven-digit filter edit.
     */
    @Test
    void listCards_zeroAcctFilter_throwsCardDemoException() {
        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.listCards(0L, null, 1, null))
                .withMessage(MSG_ACCT_FILTER_11);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A card-number filter that is not sixteen digits fails the card-filter edit
     *     before any store access.
     */
    @Test
    void listCards_invalidCardFilter_throwsCardDemoException() {
        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.listCards(null, "12345", 1, null))
                .withMessage(MSG_CARD_FILTER_16);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: An empty candidate set yields an empty mapped page rather than an exception,
     *     reproducing the redisplay-with-message behaviour on no records.
     */
    @Test
    @SuppressWarnings("unchecked")
    void listCards_emptyResult_returnsMappedEmptyNotException() {
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardRepository.findAll()).thenReturn(List.of());
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        CardListResponseDto result = cardService.listCards(null, null, 1, null);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<List<Card>> pageCaptor = ArgumentCaptor.forClass(List.class);
        verify(cardMapper).toListResponse(pageCaptor.capture());
        assertThat(pageCaptor.getValue()).isEmpty();
    }

    // =====================================================================================
    // DETAIL scenarios (COCRDSLC, CICS CCDL) -- keyed card read plus cross-reference join.
    // =====================================================================================

    /**
     * :purpose: A found card with a present cross-reference returns the mapped detail response.
     */
    @Test
    void getCardDetail_found_returnsMappedDetail() {
        Card card = card(CARD_NUM, ACCT_ID);
        CardXref xref = new CardXref(CARD_NUM, FAKE_CUST_ID, ACCT_ID);
        CardDetailResponseDto expected = mock(CardDetailResponseDto.class);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref));
        when(cardMapper.toDetailResponse(card, xref)).thenReturn(expected);

        CardDetailResponseDto result = cardService.getCardDetail(CARD_NUM, null);

        assertThat(result).isSameAs(expected);
        verify(cardMapper).toDetailResponse(card, xref);
    }

    /**
     * :purpose: A found card whose cross-reference is absent still renders; the mapper is
     *     invoked with a null cross-reference.
     */
    @Test
    void getCardDetail_xrefMissing_stillReturnsCardDetail() {
        Card card = card(CARD_NUM, ACCT_ID);
        CardDetailResponseDto expected = mock(CardDetailResponseDto.class);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardMapper.toDetailResponse(card, null)).thenReturn(expected);

        CardDetailResponseDto result = cardService.getCardDetail(CARD_NUM, null);

        assertThat(result).isSameAs(expected);
        verify(cardMapper).toDetailResponse(card, null);
    }

    /**
     * :purpose: A missing card raises {@link RecordNotFoundException} with the card-read-miss
     *     message before any cross-reference or mapper interaction.
     */
    @Test
    void getCardDetail_notFound_throwsRecordNotFound() {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.getCardDetail(CARD_NUM, null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verifyNoInteractions(cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A non-admin user requesting a card outside its account is rejected with the
     *     same not-found message (scoping hides foreign cards).
     */
    @Test
    void getCardDetail_nonAdminForeignAccount_isScoped() {
        Card card = card(CARD_NUM, OTHER_ACCT_ID);
        SessionContext user = mock(SessionContext.class);
        when(user.getUserType()).thenReturn(SessionContext.UserType.CDEMO_USRTYP_USER);
        when(user.getAcctId()).thenReturn(ACCT_ID);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.getCardDetail(CARD_NUM, user))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verifyNoInteractions(cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A non-admin user requesting a card owned by its own account receives the
     *     mapped detail response.
     */
    @Test
    void getCardDetail_nonAdminOwnAccount_returnsDetail() {
        Card card = card(CARD_NUM, ACCT_ID);
        CardXref xref = new CardXref(CARD_NUM, FAKE_CUST_ID, ACCT_ID);
        SessionContext user = mock(SessionContext.class);
        when(user.getUserType()).thenReturn(SessionContext.UserType.CDEMO_USRTYP_USER);
        when(user.getAcctId()).thenReturn(ACCT_ID);
        CardDetailResponseDto expected = mock(CardDetailResponseDto.class);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref));
        when(cardMapper.toDetailResponse(card, xref)).thenReturn(expected);

        CardDetailResponseDto result = cardService.getCardDetail(CARD_NUM, user);

        assertThat(result).isSameAs(expected);
        verify(cardMapper).toDetailResponse(card, xref);
    }

    // =====================================================================================
    // UPDATE scenarios (COCRDUPC, CICS CCUP) -- fail-fast edits, no-change short-circuit,
    // in-transaction re-read then rewrite. The card entity has no version column, so there
    // is no snapshot-compare conflict path (see CardService.updateCard docstring).
    // =====================================================================================

    /**
     * :purpose: A null request reproduces the first (name) edit failure.
     */
    @Test
    void updateCard_nullRequest_throwsNameAlpha() {
        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, null))
                .withMessage(MSG_NAME_ALPHA);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: An embossed name containing a digit fails the name edit before any store access.
     */
    @Test
    void updateCard_invalidName_containsDigits_throws() {
        CardUpdateRequestDto request = mockRequest("John3 Smith", "Y", VALID_EXPIRY);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_NAME_ALPHA);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A blank embossed name fails the name edit (the name is required).
     */
    @Test
    void updateCard_blankName_throws() {
        CardUpdateRequestDto request = mockRequest("   ", "Y", VALID_EXPIRY);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_NAME_ALPHA);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: An active status other than ``Y`` or ``N`` fails the status edit.
     */
    @Test
    void updateCard_invalidStatus_throws() {
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "X", VALID_EXPIRY);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_STATUS_YN);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: An expiry month of zero fails the month edit (below the inclusive lower bound).
     */
    @Test
    void updateCard_invalidMonthZero_throws() {
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "Y", "2025-00-15");

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_EXPIRY_MONTH);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: An expiry month of thirteen fails the month edit (above the inclusive upper bound).
     */
    @Test
    void updateCard_invalidMonthThirteen_throws() {
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "Y", "2025-13-15");

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_EXPIRY_MONTH);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: Expiry months one and twelve pass the month edit (inclusive boundaries); the
     *     flow proceeds to the re-read, which yields not-found rather than a month rejection.
     */
    @Test
    void updateCard_validMonthBoundaries_notRejected() {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(
                        CARD_NUM, mockRequest(VALID_NAME, "Y", "2025-01-15"), null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(
                        CARD_NUM, mockRequest(VALID_NAME, "Y", "2025-12-15"), null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verify(cardRepository, never()).save(any());
    }

    /**
     * :purpose: An expiry year below 1950 fails the year edit.
     */
    @Test
    void updateCard_invalidYearBelowRange_throws() {
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "Y", "1949-06-15");

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_EXPIRY_YEAR);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: An expiry year above 2099 fails the year edit.
     */
    @Test
    void updateCard_invalidYearAboveRange_throws() {
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "Y", "2100-06-15");

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_EXPIRY_YEAR);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: Expiry years 1950 and 2099 pass the year edit (inclusive boundaries); the flow
     *     proceeds to the re-read, which yields not-found rather than a year rejection.
     */
    @Test
    void updateCard_validYearBoundaries_notRejected() {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(
                        CARD_NUM, mockRequest(VALID_NAME, "Y", "1950-06-15"), null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(
                        CARD_NUM, mockRequest(VALID_NAME, "Y", "2099-06-15"), null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verify(cardRepository, never()).save(any());
    }

    /**
     * :purpose: With both the name and the status invalid, the name edit wins, proving the
     *     fail-fast edit order (name before status).
     */
    @Test
    void updateCard_failFastOrder_nameBeforeStatus() {
        CardUpdateRequestDto request = mockRequest("John3 Smith", "X", VALID_EXPIRY);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_NAME_ALPHA);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A well-formed request passes all four validation edits (there is no CVV edit)
     *     and reaches the re-read, confirming the card verification value is never validated.
     */
    @Test
    void updateCard_cvvNotValidated_passesValidationEdits() {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "Y", VALID_EXPIRY);

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verify(cardRepository, never()).save(any());
    }

    /**
     * :purpose: A submission equal to the stored card is a no-op: the mapped response is
     *     returned without applying an update or persisting.
     */
    @Test
    void updateCard_noChangesDetected_skipsSave() {
        Card card = card(CARD_NUM, ACCT_ID, VALID_NAME, "Y", VALID_EXPIRY, null);
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "Y", VALID_EXPIRY);
        CardUpdateResponseDto expected = mock(CardUpdateResponseDto.class);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        CardUpdateResponseDto result = cardService.updateCard(CARD_NUM, request, null);

        assertThat(result).isSameAs(expected);
        verify(cardRepository, never()).save(any());
        verify(cardMapper, never()).applyUpdate(any(), any());
        verify(cardMapper).toUpdateResponse(card, null);
    }

    /**
     * :purpose: A name differing only by letter case is treated as unchanged (the name compare
     *     is case-insensitive), so no update is persisted.
     */
    @Test
    void updateCard_nameChangeCaseOnly_treatedAsNoChange() {
        Card card = card(CARD_NUM, ACCT_ID, VALID_NAME, "Y", VALID_EXPIRY, null);
        CardUpdateRequestDto request = mockRequest("JOHN SMITH", "Y", VALID_EXPIRY);
        CardUpdateResponseDto expected = mock(CardUpdateResponseDto.class);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        CardUpdateResponseDto result = cardService.updateCard(CARD_NUM, request, null);

        assertThat(result).isSameAs(expected);
        verify(cardRepository, never()).save(any());
        verify(cardMapper, never()).applyUpdate(any(), any());
    }

    /**
     * :purpose: A validated change against a card removed between display and submit raises
     *     {@link RecordNotFoundException} at the re-read and persists nothing.
     */
    @Test
    void updateCard_reReadNotFound_throwsRecordNotFound() {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, request, null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verify(cardRepository, never()).save(any());
        verifyNoInteractions(cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A validated change re-reads the card, delegates the field assembly to the
     *     mapper, persists once and returns the mapped response, in that order; the resolved
     *     identifiers are propagated into the session.
     */
    @Test
    void updateCard_happyPath_appliesUpdateThenSavesAndReturns() {
        Card card = card(CARD_NUM, ACCT_ID, VALID_NAME, "Y", VALID_EXPIRY, null);
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);
        CardUpdateResponseDto expected = mock(CardUpdateResponseDto.class);
        SessionContext session = mock(SessionContext.class);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardRepository.save(card)).thenReturn(card);
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        CardUpdateResponseDto result = cardService.updateCard(CARD_NUM, request, session);

        assertThat(result).isSameAs(expected);
        InOrder inOrder = inOrder(cardRepository, cardMapper);
        inOrder.verify(cardRepository).findById(CARD_NUM);
        inOrder.verify(cardMapper).applyUpdate(request, card);
        inOrder.verify(cardRepository).save(card);
        inOrder.verify(cardMapper).toUpdateResponse(card, null);
        verify(session).setCardNum(anyString());
        verify(session).setAcctId(ACCT_ID);
    }

    /**
     * :purpose: The service delegates field mutation to the mapper and never mutates the card's
     *     identity fields (card number, owning account id) itself.
     */
    @Test
    void updateCard_delegatesMutationToMapper_doesNotTouchIdentity() {
        Card card = mock(Card.class);
        lenient().when(card.getCardEmbossedName()).thenReturn(VALID_NAME);
        lenient().when(card.getCardActiveStatus()).thenReturn("Y");
        lenient().when(card.getCardExpiraionDate()).thenReturn(VALID_EXPIRY);
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);
        CardUpdateResponseDto expected = mock(CardUpdateResponseDto.class);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardRepository.save(card)).thenReturn(card);
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        CardUpdateResponseDto result = cardService.updateCard(CARD_NUM, request, null);

        assertThat(result).isSameAs(expected);
        verify(cardMapper).applyUpdate(request, card);
        verify(card, never()).setCardNum(any());
        verify(card, never()).setCardAcctId(any());
    }

}
