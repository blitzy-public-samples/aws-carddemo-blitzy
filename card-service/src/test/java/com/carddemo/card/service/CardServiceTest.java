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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.carddemo.common.dto.CardListItemDto;
import com.carddemo.common.dto.CardListResponseDto;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.CardUpdateResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

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
    /** ``COCRDUPC`` ``WS-PROMPT-FOR-NAME`` literal for an absent embossed name. */
    private static final String MSG_NAME_NOT_PROVIDED = "Card name not provided";

    /** ``COACTUPC``/``COCRDUPC`` concurrent-change literal surfaced as HTTP 409. */
    private static final String MSG_DATA_WAS_CHANGED =
            "Record changed by some one else. Please review";

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
        // Mirror the DDL default: a stored card always carries a non-null version.
        c.setVersion(0L);
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
        // Mockito answers 0L (the primitive default) for an unstubbed Long-returning getter,
        // which would look like a submitted optimistic-lock token; the default fixture carries
        // no token, so stub it explicitly.
        lenient().when(request.getVersion()).thenReturn(null);
        return request;
    }


    /**
     * :purpose: Stub the unfiltered ordered browse so the store returns one window: the rows the
     *     page shows plus, when ``hasLookahead`` is set, the single extra record ``COCRDLIC``
     *     reads to learn a further page exists.
     * :param rowCount: rows the page itself shows.
     * :param hasLookahead: whether the window carries the extra lookahead record.
     */
    private void stubWindow(int rowCount, boolean hasLookahead) {
        when(cardRepository.findAllByOrderByCardNumAsc(any(Pageable.class)))
                .thenReturn(cards(rowCount + (hasLookahead ? 1 : 0)));
    }

    /**
     * :purpose: Stub the account-filtered ordered browse for one window.
     * :param acctId: the owning account whose browse is stubbed.
     * :param rowCount: rows the page itself shows.
     * :param hasLookahead: whether the window carries the extra lookahead record.
     */
    private void stubAccountWindow(Long acctId, int rowCount, boolean hasLookahead) {
        when(cardRepository.findByCardAcctIdOrderByCardNumAsc(eq(acctId), any(Pageable.class)))
                .thenReturn(cards(rowCount + (hasLookahead ? 1 : 0)));
    }

    // =====================================================================================
    // LIST scenarios (COCRDLIC, CICS CCLI) -- seven rows per page and the ACCTSID / CARDSID
    // filter edits. COCRDLIC has no user-type branch (9500-FILTER-RECORDS, L1382-1390), so
    // the operator-supplied filters are the only browse scope.
    // =====================================================================================

    /**
     * :purpose: A caller with no filter browses the full card master and receives the mapper's
     *     list response.
     */
    @Test
    void listCards_noFilter_returnsMappedResponse() {
        CardListResponseDto expected = mock(CardListResponseDto.class);
        stubWindow(3, false);
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        CardListResponseDto result = cardService.listCards(null, null, 1, mock(SessionContext.class));

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
        stubWindow(7, false);
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
        stubWindow(7, true);
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
        stubWindow(1, false);
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(null, null, 2, null);

        ArgumentCaptor<List<Card>> pageCaptor = ArgumentCaptor.forClass(List.class);
        verify(cardMapper).toListResponse(pageCaptor.capture());
        assertThat(pageCaptor.getValue()).hasSize(1);
    }

    /**
     * :purpose: The browse is scoped by the ACCTSID the operator typed, whatever the caller's
     *     role: ``COCRDLIC 9500-FILTER-RECORDS`` (L1382-1390) filters on the screen filter and
     *     has no user-type branch, so an ordinary user and an administrator resolve the same
     *     rows for the same filter.
     */
    @Test
    void listCards_acctFilter_scopesBrowseForEveryRole() {
        SessionContext user = mock(SessionContext.class);
        CardListResponseDto expected = mock(CardListResponseDto.class);
        stubAccountWindow(ACCT_ID, 2, false);
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        CardListResponseDto asUser = cardService.listCards(ACCT_ID, null, 1, user);

        assertThat(asUser).isSameAs(expected);
        verify(cardRepository).findByCardAcctIdOrderByCardNumAsc(eq(ACCT_ID), any(Pageable.class));
        verify(cardRepository, never()).findAllByOrderByCardNumAsc(any(Pageable.class));
    }

    /**
     * :purpose: A different ACCTSID resolves that account's browse; the session's own workflow
     *     account is never substituted for the filter the operator supplied.
     */
    @Test
    void listCards_foreignAcctFilter_readsTheRequestedAccountNotTheSessionAccount() {
        SessionContext user = mock(SessionContext.class);
        CardListResponseDto expected = mock(CardListResponseDto.class);
        stubAccountWindow(OTHER_ACCT_ID, 1, false);
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(OTHER_ACCT_ID, null, 1, user);

        verify(cardRepository).findByCardAcctIdOrderByCardNumAsc(eq(OTHER_ACCT_ID), any(Pageable.class));
        verify(cardRepository, never()).findByCardAcctIdOrderByCardNumAsc(eq(ACCT_ID), any(Pageable.class));
        verify(cardRepository, never()).findAllByOrderByCardNumAsc(any(Pageable.class));
        verify(cardRepository, never()).findAll();
    }

    /**
     * :purpose: A non-admin user with no account pinned in its session and no filter must not
     *     receive the entire card base; the browse yields no rows instead of every PAN.
     */
    @Test
    void listCards_nonAdminUnpinnedNoFilter_doesNotWidenToFindAll() {
        SessionContext user = mock(SessionContext.class);
        CardListResponseDto expected = mock(CardListResponseDto.class);
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        CardListResponseDto result = cardService.listCards(null, null, 1, user);

        assertThat(result).isSameAs(expected);
        verify(cardRepository, never()).findAll();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Card>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(cardMapper).toListResponse(rowsCaptor.capture());
        assertThat(rowsCaptor.getValue()).isEmpty();
    }

    /**
     * :purpose: A non-admin user supplying its OWN account id as the filter reads exactly that
     *     account (the filter is honoured, not discarded).
     */
    @Test
    void listCards_nonAdminOwnAcctFilter_isHonoured() {
        SessionContext user = mock(SessionContext.class);
        CardListResponseDto expected = mock(CardListResponseDto.class);
        stubAccountWindow(ACCT_ID, 2, false);
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(ACCT_ID, null, 1, user);

        verify(cardRepository).findByCardAcctIdOrderByCardNumAsc(eq(ACCT_ID), any(Pageable.class));
        verify(cardRepository, never()).findAll();
    }

    /**
     * :purpose: With no ACCTSID supplied the browse is the unfiltered ordered card-master
     *     browse, exactly as ``COCRDLIC`` browses ``CARDDAT`` when the filter is blank.
     */
    @Test
    void listCards_noFilter_usesTheUnfilteredOrderedBrowse() {
        CardListResponseDto expected = mock(CardListResponseDto.class);
        stubWindow(2, false);
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(null, null, 1, mock(SessionContext.class));

        verify(cardRepository).findAllByOrderByCardNumAsc(any(Pageable.class));
        verify(cardRepository, never()).findByCardAcctIdOrderByCardNumAsc(any(), any(Pageable.class));
    }

    /**
     * :purpose: The store is asked for one bounded window -- seven rows plus the single
     *     lookahead record -- so the whole card base is never materialised for a seven-row page.
     */
    @Test
    void listCards_readsOnlyOnePageWindowPlusOneLookaheadRow() {
        CardListResponseDto expected = mock(CardListResponseDto.class);
        stubWindow(7, true);
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(null, null, 2, null);

        ArgumentCaptor<Pageable> windowCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findAllByOrderByCardNumAsc(windowCaptor.capture());
        assertThat(windowCaptor.getValue().getPageSize()).isEqualTo(CardService.MAX_SCREEN_LINES + 1);
        assertThat(windowCaptor.getValue().getPageNumber()).isEqualTo(1);
    }

    /**
     * :purpose: A valid eleven-digit account filter reads exactly that account's cards.
     */
    @Test
    void listCards_withValidAcctFilter_usesFilter() {
        CardListResponseDto expected = mock(CardListResponseDto.class);
        stubAccountWindow(ACCT_ID, 2, false);
        when(cardMapper.toListResponse(any())).thenReturn(expected);

        cardService.listCards(ACCT_ID, null, 1, null);

        ArgumentCaptor<Long> acctCaptor = ArgumentCaptor.forClass(Long.class);
        verify(cardRepository)
                .findByCardAcctIdOrderByCardNumAsc(acctCaptor.capture(), any(Pageable.class));
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
        when(cardRepository.findAllByOrderByCardNumAsc(any(Pageable.class))).thenReturn(List.of());
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
     *     same not-found message: the ``ACCTSID``/``CARDSID`` pair is the selection, so a card
     *     outside the named account is not part of it.
     */
    @Test
    void getCardDetail_accountFilterMismatch_reportsCombinationNotFound() {
        Card card = card(CARD_NUM, OTHER_ACCT_ID);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.getCardDetail(CARD_NUM, ACCT_ID))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verifyNoInteractions(cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: ``COCRDSLC 2210-EDIT-ACCOUNT``: an ``ACCTSID`` outside the non-zero
     *     eleven-digit range fails the edit before any store access.
     */
    @Test
    void getCardDetail_invalidAccountFilter_throwsAccountEditMessage() {
        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.getCardDetail(CARD_NUM, 0L))
                .withMessage("Account number must be a non zero 11 digit number");
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A card that belongs to the supplied ``ACCTSID`` resolves to the mapped detail
     *     response.
     */
    @Test
    void getCardDetail_accountFilterMatches_returnsDetail() {
        Card card = card(CARD_NUM, ACCT_ID);
        CardXref xref = new CardXref(CARD_NUM, FAKE_CUST_ID, ACCT_ID);
        CardDetailResponseDto expected = mock(CardDetailResponseDto.class);
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref));
        when(cardMapper.toDetailResponse(card, xref)).thenReturn(expected);

        CardDetailResponseDto result = cardService.getCardDetail(CARD_NUM, ACCT_ID);

        assertThat(result).isSameAs(expected);
        verify(cardMapper).toDetailResponse(card, xref);
    }

    // =====================================================================================
    // UPDATE scenarios (COCRDUPC, CICS CCUP) -- fail-fast edits, no-change short-circuit,
    // in-transaction re-read under a row write lock, then rewrite. The card carries a
    // @Version column, so a stale version token or a stale CCUP-OLD-* snapshot is a conflict
    // (see CardService.updateCard docstring).
    // =====================================================================================

    /**
     * :purpose: A null request reproduces the first (name) edit failure, which for an absent
     *     name is the prompt literal ``COCRDUPC 1230-EDIT-NAME`` emits for SPACES /
     *     LOW-VALUES / ZEROS, not the alphabetic-content literal.
     */
    @Test
    void updateCard_nullRequest_throwsNameNotProvided() {
        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, null, null))
                .withMessage(MSG_NAME_NOT_PROVIDED);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: An embossed name containing a digit fails the name edit before any store access.
     */
    @Test
    void updateCard_invalidName_containsDigits_throws() {
        CardUpdateRequestDto request = mockRequest("John3 Smith", "Y", VALID_EXPIRY);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_NAME_ALPHA);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A blank embossed name fails the name edit with the prompt literal
     *     ``WS-PROMPT-FOR-NAME`` (``COCRDUPC 1230-EDIT-NAME`` treats SPACES as "not
     *     provided"), which is a different message from the alphabetic-content edit.
     */
    @Test
    void updateCard_blankName_throwsNameNotProvided() {
        CardUpdateRequestDto request = mockRequest("   ", "Y", VALID_EXPIRY);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_NAME_NOT_PROVIDED);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: An all-zeros embossed name is "not provided" as well
     *     (``COCRDUPC 1230-EDIT-NAME`` tests SPACES, LOW-VALUES and ZEROS alike).
     */
    @Test
    void updateCard_zerosName_throwsNameNotProvided() {
        CardUpdateRequestDto request = mockRequest("0000000000", "Y", VALID_EXPIRY);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_NAME_NOT_PROVIDED);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: An active status other than ``Y`` or ``N`` fails the status edit.
     */
    @Test
    void updateCard_invalidStatus_throws() {
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "X", VALID_EXPIRY);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
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
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
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
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_EXPIRY_MONTH);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: Expiry months one and twelve pass the month edit (inclusive boundaries); the
     *     flow proceeds to the re-read, which yields not-found rather than a month rejection.
     */
    @Test
    void updateCard_validMonthBoundaries_notRejected() {
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, mockRequest(VALID_NAME, "Y", "2025-01-15"), null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, mockRequest(VALID_NAME, "Y", "2025-12-15"), null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verify(cardRepository, never()).saveAndFlush(any());
    }

    /**
     * :purpose: An expiry year below 1950 fails the year edit.
     */
    @Test
    void updateCard_invalidYearBelowRange_throws() {
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "Y", "1949-06-15");

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
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
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_EXPIRY_YEAR);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: Expiry years 1950 and 2099 pass the year edit (inclusive boundaries); the flow
     *     proceeds to the re-read, which yields not-found rather than a year rejection.
     */
    @Test
    void updateCard_validYearBoundaries_notRejected() {
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, mockRequest(VALID_NAME, "Y", "1950-06-15"), null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, mockRequest(VALID_NAME, "Y", "2099-06-15"), null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verify(cardRepository, never()).saveAndFlush(any());
    }

    /**
     * :purpose: With both the name and the status invalid, the name edit wins, proving the
     *     fail-fast edit order (name before status).
     */
    @Test
    void updateCard_failFastOrder_nameBeforeStatus() {
        CardUpdateRequestDto request = mockRequest("John3 Smith", "X", VALID_EXPIRY);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_NAME_ALPHA);
        verifyNoInteractions(cardRepository, cardXrefRepository, cardMapper);
    }

    /**
     * :purpose: A well-formed request passes the four service-level validation edits and
     *     reaches the re-read. The CVV width/format edit (``CARD-CVV-CD PIC 9(03)``) is
     *     enforced by the ``CardUpdateRequestDto`` bean-validation contract at the request
     *     boundary, so the service performs no CVV edit of its own.
     */
    @Test
    void updateCard_wellFormedRequest_passesServiceValidationEdits() {
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.empty());
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "Y", VALID_EXPIRY);

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verify(cardRepository, never()).saveAndFlush(any());
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
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        CardUpdateResponseDto result = cardService.updateCard(CARD_NUM, null, request, null);

        assertThat(result).isSameAs(expected);
        verify(cardRepository, never()).saveAndFlush(any());
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
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        CardUpdateResponseDto result = cardService.updateCard(CARD_NUM, null, request, null);

        assertThat(result).isSameAs(expected);
        verify(cardRepository, never()).saveAndFlush(any());
        verify(cardMapper, never()).applyUpdate(any(), any());
    }

    /**
     * :purpose: A validated change against a card removed between display and submit raises
     *     {@link RecordNotFoundException} at the re-read and persists nothing.
     */
    @Test
    void updateCard_reReadNotFound_throwsRecordNotFound() {
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.empty());
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_DETAIL_NOT_FOUND);
        verify(cardRepository, never()).saveAndFlush(any());
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
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardRepository.saveAndFlush(card)).thenReturn(card);
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        CardUpdateResponseDto result = cardService.updateCard(CARD_NUM, null, request, session);

        assertThat(result).isSameAs(expected);
        InOrder inOrder = inOrder(cardRepository, cardMapper);
        inOrder.verify(cardRepository).findForUpdateByCardNum(CARD_NUM);
        inOrder.verify(cardMapper).applyUpdate(request, card);
        inOrder.verify(cardRepository).saveAndFlush(card);
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
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardRepository.saveAndFlush(card)).thenReturn(card);
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        CardUpdateResponseDto result = cardService.updateCard(CARD_NUM, null, request, null);

        assertThat(result).isSameAs(expected);
        verify(cardMapper).applyUpdate(request, card);
        verify(card, never()).setCardNum(any());
        verify(card, never()).setCardAcctId(any());
    }


    // =====================================================================================
    // Concurrency: @Version token, CCUP-OLD-* snapshot, and provider-detected conflicts
    // (COCRDUPC 9300-CHECK-CHANGE-IN-REC / DATA-WAS-CHANGED-BEFORE-UPDATE, AAP 0.6.2)
    // =====================================================================================

    /**
     * :purpose: A version token matching the stored card lets the rewrite proceed.
     */
    @Test
    void updateCard_matchingVersion_proceeds() {
        Card card = card(CARD_NUM, ACCT_ID, VALID_NAME, "Y", VALID_EXPIRY, "123");
        card.setVersion(4L);
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);
        lenient().when(request.getVersion()).thenReturn(4L);
        CardUpdateResponseDto expected = mock(CardUpdateResponseDto.class);
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardRepository.saveAndFlush(card)).thenReturn(card);
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        assertThat(cardService.updateCard(CARD_NUM, null, request, null)).isSameAs(expected);
        verify(cardRepository).saveAndFlush(card);
    }

    /**
     * :purpose: A version token that no longer matches the stored card abandons the rewrite
     *     with the legacy conflict outcome and persists nothing.
     */
    @Test
    void updateCard_staleVersion_throwsConflictAndPersistsNothing() {
        Card card = card(CARD_NUM, ACCT_ID, VALID_NAME, "Y", VALID_EXPIRY, "123");
        card.setVersion(5L);
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);
        lenient().when(request.getVersion()).thenReturn(4L);
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));

        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_DATA_WAS_CHANGED);
        verify(cardRepository, never()).saveAndFlush(any());
        verify(cardMapper, never()).applyUpdate(any(), any());
    }

    /**
     * :purpose: A request carrying no version token is not treated as a conflict (the caller
     *     may instead carry the ``CCUP-OLD-*`` snapshot).
     */
    @Test
    void updateCard_absentVersion_isNotAConflict() {
        Card card = card(CARD_NUM, ACCT_ID, VALID_NAME, "Y", VALID_EXPIRY, "123");
        card.setVersion(7L);
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);
        lenient().when(request.getVersion()).thenReturn(null);
        CardUpdateResponseDto expected = mock(CardUpdateResponseDto.class);
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardRepository.saveAndFlush(card)).thenReturn(card);
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        assertThat(cardService.updateCard(CARD_NUM, null, request, null)).isSameAs(expected);
    }

    /**
     * :purpose: A ``CCUP-OLD-*`` snapshot that omits the CVV still succeeds. No read path
     *     returns the CVV (AAP 0.6.7), so demanding it in the ``9300`` compare reported a
     *     change that had not happened and made every snapshot-bearing update fail.
     */
    @Test
    void updateCard_snapshotWithoutCvv_isNotAConflict() {
        Card card = card(CARD_NUM, ACCT_ID, VALID_NAME, "Y", VALID_EXPIRY, "123");
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);
        lenient().when(request.getOldCardEmbossedName()).thenReturn(VALID_NAME);
        lenient().when(request.getOldCardActiveStatus()).thenReturn("Y");
        lenient().when(request.getOldCardExpiraionDate()).thenReturn(VALID_EXPIRY);
        lenient().when(request.getOldCardCvvCd()).thenReturn(null);
        CardUpdateResponseDto expected = mock(CardUpdateResponseDto.class);
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardRepository.saveAndFlush(card)).thenReturn(card);
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        assertThat(cardService.updateCard(CARD_NUM, null, request, null)).isSameAs(expected);
        verify(cardMapper).applyUpdate(request, card);
    }

    /**
     * :purpose: A ``CCUP-OLD-*`` snapshot whose observable fields no longer match the stored
     *     card is still a conflict.
     */
    @Test
    void updateCard_snapshotFieldDiffers_throwsConflict() {
        Card card = card(CARD_NUM, ACCT_ID, "JANE DOE", "Y", VALID_EXPIRY, "123");
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);
        lenient().when(request.getOldCardEmbossedName()).thenReturn(VALID_NAME);
        lenient().when(request.getOldCardActiveStatus()).thenReturn("Y");
        lenient().when(request.getOldCardExpiraionDate()).thenReturn(VALID_EXPIRY);
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));

        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_DATA_WAS_CHANGED);
        verify(cardRepository, never()).saveAndFlush(any());
    }

    /**
     * :purpose: A concurrent commit detected by the persistence provider at flush time is
     *     reported as the same legacy conflict outcome, not as a raw provider failure.
     */
    @Test
    void updateCard_providerDetectedConflict_isReportedAsConflict() {
        Card card = card(CARD_NUM, ACCT_ID, VALID_NAME, "Y", VALID_EXPIRY, "123");
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "N", VALID_EXPIRY);
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardRepository.saveAndFlush(card))
                .thenThrow(new ObjectOptimisticLockingFailureException(Card.class, CARD_NUM));

        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> cardService.updateCard(CARD_NUM, null, request, null))
                .withMessage(MSG_DATA_WAS_CHANGED);
    }

    /**
     * :purpose: An identical submission that omits the CVV is still a no-op; an omitted CVV
     *     retains the stored value and therefore cannot look like a change.
     */
    @Test
    void updateCard_absentCvvOnIdenticalSubmission_isNoOp() {
        Card card = card(CARD_NUM, ACCT_ID, VALID_NAME, "Y", VALID_EXPIRY, "123");
        CardUpdateRequestDto request = mockRequest(VALID_NAME, "Y", VALID_EXPIRY);
        lenient().when(request.getCardCvvCd()).thenReturn(null);
        CardUpdateResponseDto expected = mock(CardUpdateResponseDto.class);
        when(cardRepository.findForUpdateByCardNum(CARD_NUM)).thenReturn(Optional.of(card));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        when(cardMapper.toUpdateResponse(card, null)).thenReturn(expected);

        assertThat(cardService.updateCard(CARD_NUM, null, request, null)).isSameAs(expected);
        verify(cardRepository, never()).saveAndFlush(any());
        verify(cardMapper, never()).applyUpdate(any(), any());
    }

    // =====================================================================
    // COCRDLIC 1400-SETUP-MESSAGE banners reaching the client (WS-ERROR-MSG)
    // =====================================================================

    /**
     * :purpose: Answer the mapper with a real response carrier so the service's message and
     *  paging assignments are observable, which a mocked carrier would swallow.
     * :param rows: the number of rows the page holds.
     */
    private void stubRealListResponse(int rows) {
        when(cardMapper.toListResponse(any())).thenAnswer(invocation -> {
            CardListResponseDto real = new CardListResponseDto();
            List<Card> page = invocation.getArgument(0);
            real.setCards(new ArrayList<>());
            for (int i = 0; i < (page == null ? 0 : page.size()); i++) {
                real.getCards().add(new CardListItemDto());
            }
            return real;
        });
    }

    /**
     * :purpose: ``COCRDLIC`` L1240-1244: the first screen with nothing read at all sets
     *  ``WS-NO-RECORDS-FOUND``, and that banner now reaches the client.
     */
    @Test
    @DisplayName("an empty first page carries 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'")
    void listCards_emptyFirstPage_carriesNoRecordsFound() {
        stubWindow(0, false);
        stubRealListResponse(0);

        CardListResponseDto result = cardService.listCards(null, null, 1, null, null, null, null);

        assertThat(result.getMessage()).isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
        assertThat(result.getInfoMessage()).isNull();
        assertThat(result.getPageNumber()).isEqualTo(1);
        assertThat(result.isNextPage()).isFalse();
    }

    /**
     * :purpose: ``COCRDLIC`` L1219/L1239: a page past the end means the browse reached
     *  ``ENDFILE``, so ``'NO MORE RECORDS TO SHOW'`` is displayed.
     */
    @Test
    @DisplayName("a page past the end carries 'NO MORE RECORDS TO SHOW'")
    void listCards_pagePastTheEnd_carriesNoMoreRecords() {
        stubWindow(0, false);
        stubRealListResponse(0);

        CardListResponseDto result = cardService.listCards(null, null, 3, null, null, null, null);

        assertThat(result.getCards()).isEmpty();
        assertThat(result.getMessage()).isEqualTo("NO MORE RECORDS TO SHOW");
        assertThat(result.getPageNumber()).isEqualTo(3);
    }

    /**
     * :purpose: ``COCRDLIC`` L905-909: pressing PF8 when no further page exists and the last
     *  page has already been shown displays ``'NO MORE PAGES TO DISPLAY'``.
     */
    @Test
    @DisplayName("PF8 past the last page carries 'NO MORE PAGES TO DISPLAY'")
    void listCards_pf8PastLastPage_carriesNoMorePages() {
        stubWindow(0, false);
        stubRealListResponse(0);

        CardListResponseDto result =
                cardService.listCards(null, null, 2, CardService.AID_PF8, null, null, null);

        assertThat(result.getCards()).isEmpty();
        assertThat(result.getMessage()).isEqualTo("NO MORE PAGES TO DISPLAY");
    }

    /**
     * :purpose: ``COCRDLIC`` L902-904: pressing PF7 on the first page displays
     *  ``'NO PREVIOUS PAGES TO DISPLAY'``, and it wins over any later condition because
     *  ``WS-ERROR-MSG`` holds only the first message.
     */
    @Test
    @DisplayName("PF7 on the first page carries 'NO PREVIOUS PAGES TO DISPLAY'")
    void listCards_pf7OnFirstPage_carriesNoPreviousPages() {
        stubWindow(3, false);
        stubRealListResponse(3);

        CardListResponseDto result =
                cardService.listCards(null, null, 1, CardService.AID_PF7, null, null, null);

        assertThat(result.getMessage()).isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
    }

    /**
     * :purpose: ``COCRDLIC`` ``WS-INVALID-ACTION-CODE`` (L125-126): a row-selection flag other
     *  than ``S`` or ``U`` is rejected with the verbatim message.
     */
    @Test
    @DisplayName("an invalid row action reports 'INVALID ACTION CODE'")
    void listCards_invalidRowAction_reportsInvalidActionCode() {
        stubWindow(3, false);
        stubRealListResponse(3);

        assertThatThrownBy(() -> cardService.listCards(null, null, 1, null, "X", CARD_NUM, null))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage("INVALID ACTION CODE");
    }

    /**
     * :purpose: The two valid row actions are echoed in canonical upper case together with the
     *  selected card number, so the client can transfer to the detail or update screen exactly
     *  as ``COCRDLIC`` transfers to ``COCRDSLC``/``COCRDUPC``.
     */
    @Test
    @DisplayName("row actions S and U are echoed with the selected card number")
    void listCards_validRowActions_areEchoed() {
        stubWindow(3, false);
        stubRealListResponse(3);

        CardListResponseDto select = cardService.listCards(null, null, 1, null, "s", CARD_NUM, null);
        assertThat(select.getSelectedAction()).isEqualTo("S");
        assertThat(select.getSelectedCardNumber()).isEqualTo(CARD_NUM);

        CardListResponseDto update = cardService.listCards(null, null, 1, null, "U", CARD_NUM, null);
        assertThat(update.getSelectedAction()).isEqualTo("U");
    }

    /**
     * :purpose: ``COCRDLIC`` ``WS-INFORM-REC-ACTIONS`` (L115-116): a page holding rows carries
     *  the informational line the screen shows beneath the list.
     */
    @Test
    @DisplayName("a populated page carries 'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD'")
    void listCards_populatedPage_carriesInformationalLine() {
        stubWindow(7, true);
        stubRealListResponse(7);

        CardListResponseDto result = cardService.listCards(null, null, 1, null, null, null, null);

        assertThat(result.getCards()).hasSize(7);
        assertThat(result.isNextPage()).isTrue();
        assertThat(result.getInfoMessage()).isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        // A page with a further forward page available reports no boundary banner.
        assertThat(result.getMessage()).isNull();
    }
}
